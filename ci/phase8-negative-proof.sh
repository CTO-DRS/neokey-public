#!/bin/bash
# Negative proof for the phase-8 CLOSING gate (roadmap phase 8, week 4 —
# week 41) — SAVED evidence. The closing gate must FAIL on a tampered
# manifest anchor, on tampered state, on a forged pipeline log, and on a
# hidden phase-8 module. Three-way evidence integrity is the design
# (inherited a FIFTH time): the fresh re-run is the un-forgeable anchor, so
# the gate re-runs all three phase-8 gates inside itself — and their own
# idempotent key upserts REWRITE the phase-8 env keys from the fresh run,
# which means a forged LOG line is the tamper that survives (and the
# sub-gates' own outer-log byte-equal laws bite it FIRST — defense in
# depth), while a flipped PREVIOUS-phase key (which no phase-8 sub-gate
# rewrites) is the tamper the whole-pipeline loop must catch. Procedure:
# run ci/phase8_gate.sh green, then four designed attacks, each restored
# (cp-backup, never git) and re-verified GREEN.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/phase8_gate.sh
LOGF=build/pipeline.log
STATE=build/release/gates.env
MANIFEST=AndroidManifest.xml
BACKUP=build/phase8-proof-backup
FAILED=0

echo "== phase8 negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p "$BACKUP"
cp "$LOGF" "$BACKUP/pipeline.log"
cp "$STATE" "$BACKUP/gates.env"
cp "$MANIFEST" "$BACKUP/AndroidManifest.xml"

# ---- baseline: the gate must be green untampered -------------------------------
echo ""
echo "--- baseline (untampered)"
OUT=$($GATE 2>&1); RC=$?
BASE=$(echo "$OUT" | grep -m1 'PHASE8_OK' || echo MISSING)
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
  echo "$OUT" | grep -E 'PHASE8.*(FAILED|regression)' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  [ -n "$extra" ] && eval "$extra"
  cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"; cp "$BACKUP/AndroidManifest.xml" "$MANIFEST"
  OUT=$($GATE 2>&1); local rc2=$?
  local okline=$(echo "$OUT" | grep -m1 'PHASE8_OK' || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) the published anchor rewritten — the FIRST barrier is the rebuild gate's
#    identity anchor (المرجع = المنشور): the manifest is set to the previous
#    week's identity (an honest-LOOKING wrong value, bump-independent — no
#    identity literal in the proof's sed survives a later bump) and the anchor
#    bites BEFORE the drill runs any inner pipeline
run_attack "manifest anchor tamper (versionCode 82 -> 81)" \
'sed -i "s/android:versionCode=\"82\"/android:versionCode=\"81\"/" AndroidManifest.xml' \
'REBUILDGATE_FAILED anchor.identity.vc'

# 2) flip a PREVIOUS-phase key in gates.env — the phase-8 sub-gate re-runs
#    rewrite their OWN keys from the fresh runs, so a key they never touch
#    (the keystore drill of phase 4) is what the 33-key no-regression loop
#    must catch
run_attack "gates.env tamper (KSDRILL_GATE OK -> NOT_OK)" \
'sed -i "s/^KSDRILL_GATE=OK/KSDRILL_GATE=NOT_OK/" build/release/gates.env' \
'PHASE8 regression: KSDRILL_GATE not OK'

# 3) forge the pipeline log AND gates.env together — the fresh re-run is the
#    anchor: the sub-gate upsert heals the env half, but the forged LOG line
#    (the rebuild drill negatives 12/12 -> 11/12) is caught by the sub-gate's
#    own outer-log byte-equal law first (log.rebuild != fresh) and would be
#    caught by the closer's three-way equality anyway (defense in depth;
#    bump-independent: no identity literal involved)
run_attack "log+env double tamper (REBUILD negatives 12/12 -> 11/12 in both)" \
'sed -i "/^REBUILDGATE_OK /s/negatives=12\/12/negatives=11\/12/" build/pipeline.log
sed -i "s/^REBUILD_NEGATIVES=12\/12/REBUILD_NEGATIVES=11\/12/" build/release/gates.env' \
'log\.rebuild != fresh'

# 4) hide the phase-8 verifier module — the closing gate re-runs the gate
#    FRESH, so a missing module cannot ride a cached verdict (OpVerify.java
#    is the one class only the opverify gate needs — SyncServerPack.java and
#    Provision.java are shared, so choosing OpVerify isolates the tag; and
#    the third gate failing proves the rebuild drill AND the evidence
#    auditor re-ran green inside the same closer invocation). Lesson the
#    week-24/25/33/37 pattern, again: the heal-check re-runs the gate while
#    the class is STILL hidden unless the move-back is part of the restore.
run_attack "phase-8 module hidden (OpVerify.java -> /tmp)" \
'mv tests/com/hark/neokey/OpVerify.java /tmp/OpVerify.java' \
'PHASE8_FAILED opverify' \
'mv /tmp/OpVerify.java tests/com/hark/neokey/OpVerify.java'

cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"; cp "$BACKUP/AndroidManifest.xml" "$MANIFEST"

echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all four attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi
