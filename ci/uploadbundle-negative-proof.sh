#!/bin/bash
# Negative proof for the upload-bundle gate (phase 6, week 32) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (snapshot, restore INSIDE run_attack — the weeks-24/25 lesson) and
# the gate re-verified GREEN. The proof fails loudly if any attack fails to
# bite or any restore fails to heal.
#
#   1) swapped artifact: the bundle is re-assembled from a REBUILT (not the
#      gate's) APK — the anchor must break on the FIRST barrier
#      («المرجع = المنشور»): the bundle carries THE verified artifact or nothing.
#   2) neutered safety derivation: UploadBundle.safetyLines forced to emit an
#      answer for a permission the manifest does not declare (the classic
#      "form answered beyond the truth" tamper) — the gate's own
#      safety_unproven negative STOPS BITING with the expected tag and the
#      gate fails on it: a dead guard is a failure.
#   3) hidden validator: UploadBundleGate.java removed — the gate must fail
#      closed at compile time (a missing validator is never a silent pass).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== uploadbundle negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/upload-proof-backup
cp build/NeoKey-2036.apk build/upload-proof-backup/NeoKey-2036.apk
cp build/release/signedapk-statement.json build/upload-proof-backup/signedapk-statement.json
cp tests/com/hark/neokey/UploadBundle.java build/upload-proof-backup/UploadBundle.java
cp tests/com/hark/neokey/UploadBundleGate.java build/upload-proof-backup/UploadBundleGate.java

gate() { bash ci/uploadbundle_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected tag line in output
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  local rc=$(gate /tmp/ub_neg.txt)
  local verdict=$(grep -m1 'UPLOADBUNDLEGATE_FAILED' /tmp/ub_neg.txt || echo "(no verdict line)")
  local tagline=$(grep -m1 "$expect" /tmp/ub_neg.txt || echo "(tag line missing)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  echo "tag: $tagline"
  case "$tagline" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ "$rc" != "0" ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot (inside run_attack), then the gate MUST be green
  cp build/upload-proof-backup/NeoKey-2036.apk build/NeoKey-2036.apk
  cp build/upload-proof-backup/signedapk-statement.json build/release/signedapk-statement.json
  cp build/upload-proof-backup/UploadBundle.java tests/com/hark/neokey/UploadBundle.java
  cp build/upload-proof-backup/UploadBundleGate.java tests/com/hark/neokey/UploadBundleGate.java
  local rc2=$(gate /tmp/ub_ok.txt)
  local okline=$(grep -m1 'UPLOADBUNDLEGATE_OK' /tmp/ub_ok.txt | head -c 80 || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  if [ "$rc2" != "0" ] || ! grep -q 'UPLOADBUNDLEGATE_OK' /tmp/ub_ok.txt; then echo "RESTORE DID NOT HEAL"; FAILED=1; fi
}

# 1) swapped artifact: plant a DIFFERENT valid APK as the gate artifact (rebuilt
#    in the wild; here a byte-rotated copy that still assembles) — the anchor
#    compares sha256 against the PINNED signedapk statement and refuses the swap
#    before anything is assembled.
run_attack "swapped artifact (rebuilt APK planted as build/NeoKey-2036.apk)" \
'python3 - <<PYEOF
b = bytearray(open("build/NeoKey-2036.apk", "rb").read())
b[len(b)//2] ^= 0x5A
open("build/NeoKey-2036.apk", "wb").write(bytes(b))
PYEOF' \
"UPLOADBUNDLEGATE_FAILED anchor.apk.sha"

# 2) neutered safety derivation: safetyLines forced to answer a permission the
#    manifest does not declare (CAMERA) — the data-safety form would claim more
#    than the app declares. The gate's negatives.safety_unproven negative stops
#    biting with its tag and the gate fails on it (dead guard = failure).
run_attack "neutered safety derivation (answers beyond the declared permissions)" \
'python3 - <<PYEOF
g = open("tests/com/hark/neokey/UploadBundleGate.java", encoding="utf-8").read()
old = "if (!liveNames.contains(name)) return \"safety.unproven (\" + name + \" answered but NOT declared in the manifest)\";"
assert old in g, "unproven check line not found in the gate"
g = g.replace(old, "if (false) return \"safety.unproven (\" + name + \" answered but NOT declared in the manifest)\";")
open("tests/com/hark/neokey/UploadBundleGate.java", "w", encoding="utf-8").write(g)
PYEOF' \
"UPLOADBUNDLEGATE_FAILED negatives.safety_unproven"

# 3) hidden validator: the bundle's decision logic removed — the gate must fail
#    closed at compile time (a missing validator is never a silent pass).
run_attack "hide UploadBundleGate.java (compile guard must refuse)" \
'mv tests/com/hark/neokey/UploadBundleGate.java /tmp/ub-proof-hidden-UploadBundleGate.java' \
"UPLOADBUNDLEGATE_FAILED compile"

echo ""
if [ "$FAILED" = "0" ]; then echo "UPLOADBUNDLE_NEGATIVE_PROOF_OK 3 attacks bitten, all restores healed"; else echo "UPLOADBUNDLE_NEGATIVE_PROOF_FAILED"; exit 1; fi
