#!/bin/bash
# Negative proof for the phase-6 CLOSING gate (roadmap phase 6, week 4 —
# week 33) — SAVED evidence. The closing gate must FAIL on tampered evidence
# and on a hidden phase-6 module. Three-way evidence integrity is the design
# (inherited three times): the fresh re-run is the un-forgeable anchor, so
# forging the pipeline log AND gates.env together is still caught. Procedure:
# run ci/phase6_gate.sh green, then three designed attacks, each restored
# (cp-backup, never git) and re-verified GREEN.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/phase6_gate.sh
LOGF=build/pipeline.log
STATE=build/release/gates.env
BACKUP=build/phase6-proof-backup
FAILED=0

echo "== phase6 negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p "$BACKUP"
cp "$LOGF" "$BACKUP/pipeline.log"
cp "$STATE" "$BACKUP/gates.env"

# ---- baseline: the gate must be green untampered -------------------------------
echo ""
echo "--- baseline (untampered)"
OUT=$($GATE 2>&1); RC=$?
BASE=$(echo "$OUT" | grep -m1 'PHASE6_OK' || echo MISSING)
echo "rc=$RC"
echo "verdict: $BASE"
[ $RC -eq 0 ] && [ "$BASE" != "MISSING" ] || { echo "BASELINE NOT GREEN — proof invalid"; exit 1; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected rc!=0 + verdict grep, $4 extra restore cmd
  local name="$1" attacker="$2" expect="$3" extra="${4:-}"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  OUT=$($GATE 2>&1); local rc=$?
  echo "rc=$rc"
  echo "$OUT" | grep -E 'PHASE6_(FAILED|regression)' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  [ -n "$extra" ] && eval "$extra"
  cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"
  OUT=$($GATE 2>&1); local rc2=$?
  local okline=$(echo "$OUT" | grep -m1 'PHASE6_OK' || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) tamper gates.env only — three-way consistency must catch the single source
#    (the bundle's member inventory is the bite-proof of the upload bundle)
run_attack "gates.env tamper (UPLOADBUNDLE_MEMBERS 8 -> 7)" \
'sed -i "s/^UPLOADBUNDLE_MEMBERS=8/UPLOADBUNDLE_MEMBERS=7/" build/release/gates.env' \
'PHASE6 regression: uploadbundle.members: gates.env 7 != fresh re-run 8'

# 2) tamper pipeline.log AND gates.env together — the fresh re-run is the anchor
#    (the signed-artifact identity fingerprint is LITERAL — المرجع = المنشور)
run_attack "log+env double tamper (SIGNEDAPK_FP16 -> 0123456789abcdef in both)" \
'sed -i "s/fp16=a80e71b9a5d15546/fp16=0123456789abcdef/" build/pipeline.log
sed -i "s/^SIGNEDAPK_FP16=a80e71b9a5d15546/SIGNEDAPK_FP16=0123456789abcdef/" build/release/gates.env' \
'PHASE6 regression: signedapk.fp16: gates.env 0123456789abcdef != fresh re-run a80e71b9a5d15546'

# 3) hide the phase-6 upload-bundle module — the closing gate re-runs the gate
#    FRESH, so a missing module cannot ride a cached verdict (UploadBundle.java
#    is the one class only the uploadbundle gate needs — Provision.java is
#    shared with the provision tests, SignedApkGate with signedapk, so choosing
#    it isolates the tag). Lesson the proof caught in ITSELF (the week-24/25
#    pattern, again): the heal-check inside run_attack re-runs the gate while
#    the class is STILL hidden unless the move-back is part of the restore.
run_attack "phase-6 module hidden (UploadBundle.java -> /tmp)" \
'mv tests/com/hark/neokey/UploadBundle.java /tmp/UploadBundle.java' \
'PHASE6_FAILED uploadbundle' \
'mv /tmp/UploadBundle.java tests/com/hark/neokey/UploadBundle.java'

cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"

echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi
