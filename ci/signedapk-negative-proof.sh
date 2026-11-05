#!/bin/bash
# Negative proof for the signed-artifact gate (phase 6, week 31) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp-snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN. The proof fails loudly if any attack fails to
# bite or any restore fails to heal.
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== signedapk negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/sapk-proof-backup
cp tests/ks/drill-release.p12 build/sapk-proof-backup/drill-release.p12
cp tests/com/hark/neokey/SignedApk.java build/sapk-proof-backup/SignedApk.java

gate() { bash ci/signedapk_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected tag line in output
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  local rc=$(gate /tmp/sapk_neg.txt)
  local verdict=$(grep -m1 'SIGNEDAPKGATE_FAILED' /tmp/sapk_neg.txt || echo "(no verdict line)")
  local tagline=$(grep -m1 "$expect" /tmp/sapk_neg.txt || echo "(tag line missing)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  echo "tag: $tagline"
  case "$tagline" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ "$rc" != "0" ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot (inside run_attack), then the gate MUST be green
  cp build/sapk-proof-backup/drill-release.p12 tests/ks/drill-release.p12
  cp build/sapk-proof-backup/SignedApk.java tests/com/hark/neokey/SignedApk.java
  local rc2=$(gate /tmp/sapk_ok.txt)
  local okline=$(grep -m1 'SIGNEDAPKGATE_OK' /tmp/sapk_ok.txt | head -c 80 || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  if [ "$rc2" != "0" ] || ! grep -q 'SIGNEDAPKGATE_OK' /tmp/sapk_ok.txt; then echo "RESTORE DID NOT HEAL"; FAILED=1; fi
}

# 1) fixture swap: the rogue keystore planted as the drill fixture — a perfectly
#    VALID, perfectly loadable keystore (same alias, same dname, different key).
#    The published-fingerprint anchor must break on the FIRST barrier
#    («المرجع = المنشور»): identity is decided by the published literal.
run_attack "fixture swap (rogue keystore planted as the drill fixture)" \
'cp tests/ks/drill-rogue.p12 tests/ks/drill-release.p12' \
"SIGNEDAPKGATE_FAILED anchor.drill.fp"

# 2) neutered verification: SignedApk.verify forced to skip the integrity
#    verdict (the classic "signature check bypassed" tamper) — the gate's own
#    tamper negative STOPS BITING with the expected tag and the gate fails on
#    it: a dead guard is a failure.
run_attack "neutered SignedApk.verify integrity verdict (always-skip)" \
'python3 - <<PYEOF
s = open("tests/com/hark/neokey/SignedApk.java", encoding="utf-8").read()
old = "if (!res.isVerified()) return fail(r, \"not_verified\", firstErr(res));"
assert old in s, "verify verdict line not found"
open("tests/com/hark/neokey/SignedApk.java", "w", encoding="utf-8").write(
    s.replace(old, "if (false) return fail(r, \"not_verified\", firstErr(res));"))
PYEOF' \
"SIGNEDAPKGATE_FAILED negatives.tamper"

# 3) hidden signer: the artifact's decision logic removed — the gate must fail
#    closed at compile time (a missing signer is never a silent pass).
run_attack "hide SignedApk.java (compile guard must refuse)" \
'mv tests/com/hark/neokey/SignedApk.java /tmp/sapk-proof-hidden-SignedApk.java' \
"SIGNEDAPKGATE_FAILED compile"

echo ""
if [ "$FAILED" = "0" ]; then echo "SIGNEDAPK_NEGATIVE_PROOF_OK 3 attacks bitten, all restores healed"; else echo "SIGNEDAPK_NEGATIVE_PROOF_FAILED"; exit 1; fi
