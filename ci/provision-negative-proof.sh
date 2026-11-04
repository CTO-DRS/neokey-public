#!/bin/bash
# Negative proof for the provisioning + rotation drill (week 30) — saved log:
# ci/provision-negative-proof.log
#
# Three designed attacks, each must bite its EXACT tag, then the artifact is
# restored and the gate re-verified GREEN (a proof that cannot heal is a proof
# that lied). The weeks-24/25 lesson applied preemptively: any artifact moved
# out of the repo is restored by an explicit move-back command INSIDE
# run_attack's restore list — the false RESTORE-DID-NOT-HEAL alarm never fires.
#
#   1. fixture swap (rotate.p12 planted as server.p12) — the swapped fixture is
#      a perfectly VALID chain for the domain and the operator CA, so every
#      internal consistency check passes; the SECOND anchor catches it inside
#      the gate: ref.pin.published pins the committed fixture's pin16 literally
#      («المرجع = المنشور») — internal consistency never judges identity.
#   2. the pin derivation neutered (SpkiPins.pinOf returns a constant) — the
#      classic derivation bypass; the ANCHOR is the first barrier and bites:
#      anchor.arena.pin (the committed arena fixture no longer re-derives the
#      pinned literal).
#   3. Provision.java hidden — a missing production-side class never rides a
#      cached verdict: PROVISIONGATE_FAILED compile.
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/provision-negative-proof.log
: > "$LOG"

note() { echo "$@" | tee -a "$LOG"; }

EXPECT() { # $1 name, $2 want-tag, $3 haystack
  if echo "$3" | grep -q "$2"; then
    note "BITE  $1 -> $2"
    return 0
  else
    note "MISS  $1 -> expected $2, got: $(echo "$3" | tail -2 | tr '\n' ' ')"
    return 1
  fi
}

MISSES=0
run_attack() { # $1 name, $2 want-tag, $3 attack fn, $4... extra restore cmds
  local name=$1 want=$2; shift 2
  local out
  out=$("$@" 2>&1)
  EXPECT "$name" "$want" "$out" || MISSES=$((MISSES+1))
  # restore (explicit commands, the move-back lesson)
  case "$name" in
    attack1.fixture.swap)
      cp tests/provision/rotate.p12.keep tests/provision/server.p12
      rm -f tests/provision/rotate.p12.keep tests/provision/rotate.p12.bak ;;
    attack2.derivation.neutered)
      cp src/com/hark/neokey/SpkiPins.java.keep src/com/hark/neokey/SpkiPins.java
      rm -f src/com/hark/neokey/SpkiPins.java.keep ;;
    attack3.class.hidden)
      mv /tmp/Provision.java.hidden tests/com/hark/neokey/Provision.java ;;
  esac
  # green re-verify — the proof must heal (grep the FULL output: the gate's
  # verdict line prints before the CLI-drill lines)
  local heal
  heal=$(bash ci/provision_gate.sh 2>&1)
  if echo "$heal" | grep -q "PROVISIONGATE_OK" && echo "$heal" | grep -q "PROVISION_GATE=OK"; then
    note "HEAL  $name -> PROVISIONGATE_OK (restored + re-verified green)"
  else
    note "HEAL-FAILED $name: $(echo "$heal" | grep -E 'GATEFAIL|FAILED' | head -3 | tr '\n' ' ')"
    MISSES=$((MISSES+1))
  fi
}

# ---- attack 1: fixture swap with a VALID different chain -----------------------
attack1() {
  cp tests/provision/rotate.p12 tests/provision/rotate.p12.bak
  cp tests/provision/server.p12 tests/provision/rotate.p12.keep
  cp tests/provision/rotate.p12 tests/provision/server.p12
  bash ci/provision_gate.sh 2>&1
}

# ---- attack 2: neuter the ONE derivation (SpkiPins.pinOf -> constant) ----------
attack2() {
  cp src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/SpkiPins.java.keep
  python3 - <<'EOF'
import re
p='src/com/hark/neokey/SpkiPins.java'
s=open(p,encoding='utf-8').read()
s=s.replace('public static String pinOf(byte[] spkiDer) {',
            'public static String pinOf(byte[] spkiDer) { if (Boolean.TRUE) return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";')
open(p,'w',encoding='utf-8').write(s)
EOF
  bash ci/provision_gate.sh 2>&1
}

# ---- attack 3: hide the class (cached-verdict riding attempt) ------------------
attack3() {
  mv tests/com/hark/neokey/Provision.java /tmp/Provision.java.hidden
  bash ci/provision_gate.sh 2>&1
}

note "NEGATIVE PROOF — provisioning + rotation drill (week 30) — $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
note "baseline: $(bash ci/provision_gate.sh 2>&1 | grep -m1 PROVISIONGATE_OK)"
run_attack attack1.fixture.swap "ref.pin.published" attack1
run_attack attack2.derivation.neutered "anchor.arena.pin" attack2
run_attack attack3.class.hidden "PROVISIONGATE_FAILED compile" attack3

if [ "$MISSES" = "0" ]; then
  note "NEGATIVE_PROOF_OK attacks=3/3 bites=exact heals=green"
  exit 0
else
  note "NEGATIVE_PROOF_FAILED misses=$MISSES"
  exit 1
fi
