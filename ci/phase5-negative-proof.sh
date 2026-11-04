#!/bin/bash
# Negative proof for the phase-5 CLOSING gate (roadmap phase 5, week 4 —
# week 29) — SAVED evidence. The closing gate must FAIL on tampered evidence
# and on a hidden phase-5 module. Three-way evidence integrity is the design
# (inherited twice): the fresh re-run is the un-forgeable anchor, so forging
# the pipeline log AND gates.env together is still caught. Procedure: run
# ci/phase5_gate.sh green, then three designed attacks, each restored
# (cp-backup, never git) and re-verified GREEN.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/phase5_gate.sh
LOGF=build/pipeline.log
STATE=build/release/gates.env
BACKUP=build/phase5-proof-backup
FAILED=0

echo "== phase5 negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p "$BACKUP"
cp "$LOGF" "$BACKUP/pipeline.log"
cp "$STATE" "$BACKUP/gates.env"

# ---- baseline: the gate must be green untampered -------------------------------
echo ""
echo "--- baseline (untampered)"
OUT=$($GATE 2>&1); RC=$?
BASE=$(echo "$OUT" | grep -m1 'PHASE5_OK' || echo MISSING)
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
  echo "$OUT" | grep -E 'PHASE5_(FAILED|regression)' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  [ -n "$extra" ] && eval "$extra"
  cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"
  OUT=$($GATE 2>&1); local rc2=$?
  local okline=$(echo "$OUT" | grep -m1 'PHASE5_OK' || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) tamper gates.env only — three-way consistency must catch the single source
#    (the drill's negative count is the bite-proof of the recovery drill itself)
run_attack "gates.env tamper (KSDRILL_NEGATIVES 8/8 -> 7/8)" \
'sed -i "s/^KSDRILL_NEGATIVES=8\/8/KSDRILL_NEGATIVES=7\/8/" build/release/gates.env' \
'PHASE5 regression: ksdrill.negatives: gates.env 7/8 != fresh re-run 8/8'

# 2) tamper pipeline.log AND gates.env together — the fresh re-run is the anchor
run_attack "log+env double tamper (TLS_PINS 1 -> 2 in both)" \
'sed -i "s/TLSGATE_OK pins=1/TLSGATE_OK pins=2/" build/pipeline.log
sed -i "s/^TLS_PINS=1/TLS_PINS=2/" build/release/gates.env' \
'PHASE5 regression: tls.pins: gates.env 2 != fresh re-run 1'

# 3) hide the phase-5 deployment-gate class — the closing gate re-runs the gate
#    FRESH, so a missing module cannot ride a cached verdict (same design as
#    the week-25 proof hiding PayGate.java; DeployGate.java is the one class
#    only the deploy gate needs — PinnedTls/SpkiPins/SyncRestTransport are
#    shared with tls, KsDrillGate with ksdrill, so choosing it isolates the
#    tag). Lesson the proof caught in ITSELF (the week-24/25 pattern, again):
#    the heal-check inside run_attack re-runs the gate while the class is
#    STILL hidden unless the move-back is part of the restore.
run_attack "phase-5 module hidden (DeployGate.java -> /tmp)" \
'mv tests/com/hark/neokey/DeployGate.java /tmp/DeployGate.java' \
'PHASE5_FAILED deploy' \
'mv /tmp/DeployGate.java tests/com/hark/neokey/DeployGate.java'

cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"

echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi
