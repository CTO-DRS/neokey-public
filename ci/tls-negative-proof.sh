#!/bin/bash
# Negative proof for the TLS-SPKI gate (phase 5, week 26) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp-backup, never git) and the gate re-verified GREEN. The proof
# fails loudly if any attack fails to bite or any restore fails to heal.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/tlsgate
FAILED=0

echo "== tls negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/tls-proof-backup/tests/tls
cp tests/tls/arena-server.p12 build/tls-proof-backup/tests/tls/arena-server.p12
cp src/com/hark/neokey/SpkiPins.java build/tls-proof-backup/SpkiPins.java
cp src/com/hark/neokey/PinnedTls.java build/tls-proof-backup/PinnedTls.java

gate() { bash ci/tls_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected tag line in output
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  local rc=$(gate /tmp/tls_neg.txt)
  local verdict=$(grep -m1 'TLSGATE_FAILED' /tmp/tls_neg.txt || echo "(no verdict line)")
  local tagline=$(grep -m1 "$expect" /tmp/tls_neg.txt || echo "(tag line missing)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  echo "tag: $tagline"
  case "$tagline" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ "$rc" != "0" ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  cp build/tls-proof-backup/tests/tls/arena-server.p12 tests/tls/arena-server.p12
  cp build/tls-proof-backup/SpkiPins.java src/com/hark/neokey/SpkiPins.java
  cp build/tls-proof-backup/PinnedTls.java src/com/hark/neokey/PinnedTls.java
  local rc2=$(gate /tmp/tls_ok.txt)
  local okline=$(grep -m1 'TLSGATE_OK' /tmp/tls_ok.txt | head -c 60 || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ "$rc2" = "0" ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) fixture swap: the rogue cert planted as the arena fixture — the published
#    pin anchor (EXPECTED_ARENA_PIN re-derived from the file) must break even
#    though the swapped certificate is itself a perfectly valid TLS cert.
#    The unit suite runs FIRST in the gate — its exact FAIL line is the tag.
run_attack "fixture swap (rogue cert planted as the arena fixture)" \
'cp tests/tls/rogue-server.p12 tests/tls/arena-server.p12' \
"FAIL anchor.arena.pin"

# 2) neutered verifier: SpkiPins.verify forced to always-true (the classic
#    "pinning bypassed" tamper) — the unit suite's exact FAIL line is the tag
#    (the gate's own wire selftest would bite too, but unit runs first).
run_attack "neutered SpkiPins.verify (always-true pin check)" \
'python3 - <<PYEOF
s = open("src/com/hark/neokey/SpkiPins.java", encoding="utf-8").read()
old = "boolean any = false;"
assert old in s, "verify body not found"
open("src/com/hark/neokey/SpkiPins.java", "w", encoding="utf-8").write(s.replace(old, "boolean any = true;"))
PYEOF' \
"FAIL verify.rogue"

# 3) hidden class: the TLS context builder removed — the gate must fail closed
#    at compile time (a missing production class is never a silent pass).
run_attack "hide PinnedTls.java (compile gate must refuse)" \
'mv src/com/hark/neokey/PinnedTls.java /tmp/tls-proof-hidden-PinnedTls.java' \
"TLSGATE_FAILED compile"

echo ""
if [ "$FAILED" = "0" ]; then echo "TLS_NEGATIVE_PROOF_OK 3 attacks bitten, all restores healed"; else echo "TLS_NEGATIVE_PROOF_FAILED"; exit 1; fi
