#!/bin/bash
# Negative proof for the phase-4 CLOSING gate (roadmap phase 4, week 4) —
# SAVED evidence. The closing gate must FAIL on tampered evidence and on a
# hidden phase-4 module. Three-way evidence integrity is the design: the fresh
# re-run is the un-forgeable anchor, so forging the pipeline log AND gates.env
# together is still caught. Procedure: run ci/phase4_gate.sh green, then three
# designed attacks, each restored (cp-backup, never git) and re-verified GREEN.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/phase4_gate.sh
LOGF=build/pipeline.log
STATE=build/release/gates.env
BACKUP=build/phase4-proof-backup
FAILED=0

echo "== phase4 negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p "$BACKUP"
cp "$LOGF" "$BACKUP/pipeline.log"
cp "$STATE" "$BACKUP/gates.env"

# ---- baseline: the gate must be green untampered -------------------------------
echo ""
echo "--- baseline (untampered)"
OUT=$($GATE 2>&1); RC=$?
BASE=$(echo "$OUT" | grep -m1 'PHASE4_OK' || echo MISSING)
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
  echo "$OUT" | grep -E 'PHASE4_(FAILED|regression)' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  [ -n "$extra" ] && eval "$extra"
  cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"
  OUT=$($GATE 2>&1); local rc2=$?
  local okline=$(echo "$OUT" | grep -m1 'PHASE4_OK' || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) tamper gates.env only — three-way consistency must catch the single source
run_attack "gates.env tamper (PAY_NEGATIVES 18/18 -> 17/18)" \
'sed -i "s/^PAY_NEGATIVES=18\/18/PAY_NEGATIVES=17\/18/" build/release/gates.env' \
'PHASE4 regression: pay.negatives: gates.env 17/18 != fresh re-run 18/18'

# 2) tamper pipeline.log AND gates.env together — the fresh re-run is the anchor
run_attack "log+env double tamper (TRIM_ENTRIES 119 -> 118 in both)" \
'sed -i "s/TRIM_GATE_OK entries=119/TRIM_GATE_OK entries=118/" build/pipeline.log
sed -i "s/^TRIM_ENTRIES=119/TRIM_ENTRIES=118/" build/release/gates.env' \
'PHASE4 regression: trim.entries: gates.env 118 != fresh re-run 119'

# 3) hide the phase-4 payment-gate class — the closing gate re-runs the gate
#    FRESH, so a missing module cannot ride a cached verdict (same design as
#    the week-21 proof hiding DictSign.java; PayGate.java is the one class
#    only the pay gate needs — PaymentProvider/RestGate are shared with rest).
#    Lesson the proof caught in ITSELF (the week-23/24 pattern, again): the
#    heal-check inside run_attack re-runs the gate while the class is STILL
#    hidden unless the move-back is part of the restore — first run fired
#    RESTORE DID NOT HEAL on a correctly-restored-evidence, still-damaged-tree
#    state; fixed by passing the move-back as the attack's extra restore cmd.
run_attack "phase-4 module hidden (PayGate.java -> /tmp)" \
'mv tests/com/hark/neokey/PayGate.java /tmp/PayGate.java' \
'PHASE4_FAILED pay' \
'mv /tmp/PayGate.java tests/com/hark/neokey/PayGate.java'

cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"

echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi
