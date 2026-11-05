#!/bin/bash
# Negative proof for the phase-7 CLOSING gate (roadmap phase 7, week 4 —
# week 37) — SAVED evidence. The closing gate must FAIL on tampered state,
# on a forged pipeline log, and on a hidden phase-7 module. Three-way evidence
# integrity is the design (inherited a FOURTH time): the fresh re-run is the
# un-forgeable anchor, so the gate re-runs all three phase-7 gates inside
# itself — and their own idempotent key upserts REWRITE the phase-7 env keys
# from the fresh run, which means a forged LOG line is the tamper that
# survives, while a flipped PREVIOUS-phase key (which no phase-7 sub-gate
# rewrites) is the tamper the whole-pipeline loop must catch. Procedure:
# run ci/phase7_gate.sh green, then three designed attacks, each restored
# (cp-backup, never git) and re-verified GREEN.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/phase7_gate.sh
LOGF=build/pipeline.log
STATE=build/release/gates.env
BACKUP=build/phase7-proof-backup
FAILED=0

echo "== phase7 negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p "$BACKUP"
cp "$LOGF" "$BACKUP/pipeline.log"
cp "$STATE" "$BACKUP/gates.env"

# ---- baseline: the gate must be green untampered -------------------------------
echo ""
echo "--- baseline (untampered)"
OUT=$($GATE 2>&1); RC=$?
BASE=$(echo "$OUT" | grep -m1 'PHASE7_OK' || echo MISSING)
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
  echo "$OUT" | grep -E 'PHASE7.*(FAILED|regression)' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  [ -n "$extra" ] && eval "$extra"
  cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"
  OUT=$($GATE 2>&1); local rc2=$?
  local okline=$(echo "$OUT" | grep -m1 'PHASE7_OK' || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) flip a PREVIOUS-phase key in gates.env — the phase-7 sub-gate re-runs
#    rewrite their OWN keys from the fresh runs, so a key they never touch
#    (the payment-provider contract gate of phase 4) is what the 29-key
#    no-regression loop must catch
run_attack "gates.env tamper (PAY_GATE OK -> NOT_OK)" \
'sed -i "s/^PAY_GATE=OK/PAY_GATE=NOT_OK/" build/release/gates.env' \
'PHASE7 regression: PAY_GATE not OK'

# 2) forge the pipeline log AND gates.env together — the fresh re-run is the
#    anchor: the sub-gate upserts heal the env half, but the forged LOG line
#    (the billing contract-parity negatives 10/10 -> 9/10) is caught by the
#    three-way equality (bump-independent: no identity literal involved)
run_attack "log+env double tamper (BILLING negatives 10/10 -> 9/10 in both)" \
'sed -i "s/negatives=10\/10/negatives=9\/10/g" build/pipeline.log
sed -i "s/^BILLING_NEGATIVES=10\/10/BILLING_NEGATIVES=9\/10/" build/release/gates.env' \
'PHASE7 regression: billing.negatives: pipeline log 9/10 != fresh re-run 10/10'

# 3) hide the phase-7 preflight module — the closing gate re-runs the gate
#    FRESH, so a missing module cannot ride a cached verdict (PreflightGate.java
#    is the one class only the preflight gate needs — Preflight.java and
#    SyncServerPack.java are shared with the pack tests, so choosing the gate
#    isolates the tag; and the third gate failing proves the first two re-ran
#    green inside the same closer invocation). Lesson the week-24/25/33
#    pattern, again: the heal-check re-runs the gate while the class is STILL
#    hidden unless the move-back is part of the restore.
run_attack "phase-7 module hidden (PreflightGate.java -> /tmp)" \
'mv tests/com/hark/neokey/PreflightGate.java /tmp/PreflightGate.java' \
'PHASE7_FAILED preflight' \
'mv /tmp/PreflightGate.java tests/com/hark/neokey/PreflightGate.java'

cp "$BACKUP/pipeline.log" "$LOGF"; cp "$BACKUP/gates.env" "$STATE"

echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi
