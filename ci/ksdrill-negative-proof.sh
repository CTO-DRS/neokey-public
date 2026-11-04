#!/bin/bash
# Negative proof for the keystore-recovery drill (phase 5, week 28) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp-backup, never git) and the gate re-verified GREEN. The proof
# fails loudly if any attack fails to bite or any restore fails to heal.
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== ksdrill negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/ks-proof-backup/tests/ks
cp tests/ks/drill-release.p12 build/ks-proof-backup/tests/ks/drill-release.p12
cp tests/com/hark/neokey/KsValidator.java build/ks-proof-backup/KsValidator.java
cp tests/com/hark/neokey/KsDrillGate.java build/ks-proof-backup/KsDrillGate.java

gate() { bash ci/keystore_drill.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected tag line in output
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  local rc=$(gate /tmp/ks_neg.txt)
  local verdict=$(grep -m1 'KSDRILLGATE_FAILED' /tmp/ks_neg.txt || echo "(no verdict line)")
  local tagline=$(grep -m1 "$expect" /tmp/ks_neg.txt || echo "(tag line missing)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  echo "tag: $tagline"
  case "$tagline" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ "$rc" != "0" ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  cp build/ks-proof-backup/tests/ks/drill-release.p12 tests/ks/drill-release.p12
  cp build/ks-proof-backup/KsValidator.java tests/com/hark/neokey/KsValidator.java
  cp build/ks-proof-backup/KsDrillGate.java tests/com/hark/neokey/KsDrillGate.java
  local rc2=$(gate /tmp/ks_ok.txt)
  local okline=$(grep -m1 'KSDRILLGATE_OK' /tmp/ks_ok.txt | head -c 70 || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ "$rc2" = "0" ] && [ "$okline" != "MISSING" ] && grep -q 'KSDRILLGATE_OK' /tmp/ks_ok.txt || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) fixture swap: the rogue keystore planted as the drill fixture — the pinned
#    fingerprint anchor must break on the FIRST barrier even though the swap is
#    itself a perfectly valid, perfectly loadable keystore ("المرجع = المنشور").
run_attack "fixture swap (rogue keystore planted as the drill fixture)" \
'cp tests/ks/drill-rogue.p12 tests/ks/drill-release.p12' \
"FAIL anchor.drill.fp"

# 2) neutered identity check: KsValidator.fpEqual forced to always-true (the
#    classic "fingerprint check bypassed" tamper) — the gate's own rogue
#    negative STOPS BITING and the gate fails on it: a dead guard is a failure.
run_attack "neutered KsValidator.fpEqual (always-true identity check)" \
'python3 - <<PYEOF
s = open("tests/com/hark/neokey/KsValidator.java", encoding="utf-8").read()
old = "return norm(a).equals(norm(b));"
assert old in s, "fpEqual body not found"
open("tests/com/hark/neokey/KsValidator.java", "w", encoding="utf-8").write(s.replace(old, "return true;"))
PYEOF' \
"FAIL negatives.rogue"

# 3) hidden validator: the drill's decision logic removed — the gate must fail
#    closed at compile time (a missing validator is never a silent pass).
run_attack "hide KsValidator.java (compile guard must refuse)" \
'mv tests/com/hark/neokey/KsValidator.java /tmp/ks-proof-hidden-KsValidator.java' \
"KSDRILLGATE_FAILED compile"

echo ""
if [ "$FAILED" = "0" ]; then echo "KSDRILL_NEGATIVE_PROOF_OK 3 attacks bitten, all restores healed"; else echo "KSDRILL_NEGATIVE_PROOF_FAILED"; exit 1; fi
