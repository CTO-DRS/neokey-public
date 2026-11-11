#!/bin/bash
# Negative proof for the preflight gate (phase 7 W3, week 36) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN. The proof fails loudly if any attack fails
# to bite or any restore fails to heal.
#
#   1) stale identity fed to the gate: the evidence file rewritten to LAST
#      week's identity (76 / 3.0 — bump-independent: any stale literal is
#      wrong the same way; the W34 lesson: a sed pinned to last week's value
#      goes stale at the next bump and the attack silently stops biting) —
#      the sheet would emit from the stale evidence and agree with the lie
#      (a sheet can agree with a lie), so the GATE CLASS's evidence identity
#      check bites FIRST, before anything emits: every fresh verdict must
#      carry the published identity («المرجع = المنشور» — evidence is
#      measured against the week, not against itself).
#      THE W34 LESSON BIT THE ATTACK ITSELF (found at the W50 prep, 2026-11-11):
#      the sed's MATCH side was pinned to the then-current 77/3.0 and became a
#      silent no-op at the identity unification (vc 1 / 0.1.0) — the attack
#      stopped biting and the proof failed loudly (that is what proofs are
#      for). The match side is now bump-independent (vc=[0-9]+ vn=[0-9.]+);
#      the STALE TARGET stays a literal — any stale literal is wrong the same
#      way.
#   2) dead guard: the sheet checker's evidence comparison neutered (its tag
#      renamed away) — the tampered-evidence negative STOPS BITING with its
#      expected tag and the gate fails on it: a checker that compares
#      nothing is a failure, caught by the gate's own negative.
#   3) hidden tool: Preflight.java removed — the gate fails closed at
#      compile time (a missing walker is never a silent pass).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== preflight negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p build/preflight-proof-backup
cp tests/com/hark/neokey/SyncServerPack.java build/preflight-proof-backup/SyncServerPack.java
cp tests/com/hark/neokey/Preflight.java build/preflight-proof-backup/Preflight.java

gate() { bash ci/preflight_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker, $3 expected line (grep -E), $4 restore, $5 gate cmd (optional, default: full script)
  local name="$1" attacker="$2" expect="$3" restore="$4" gcmd="${5:-bash ci/preflight_gate.sh}"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$($gcmd > /tmp/pf_neg.txt 2>&1; echo $?)
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/pf_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -4 /tmp/pf_neg.txt; FAILED=1
  else
    echo "bit: $(grep -m1 -E "$expect" /tmp/pf_neg.txt)"
  fi
  eval "$restore"
  local rrc=$(gate /tmp/pf_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^PREFLIGHTGATE_OK' /tmp/pf_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -4 /tmp/pf_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^PREFLIGHTGATE_OK' /tmp/pf_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/pf_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^PREFLIGHTGATE_OK' /tmp/pf_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/pf_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^PREFLIGHTGATE_OK' /tmp/pf_base.txt)"

run_attack "stale identity fed to the gate (last week's verdicts 76/3.0 in the evidence file — the sheet would agree with the lie, the evidence identity check does not)" \
  "sed -i -E 's/vc=[0-9]+ vn=[0-9.]+/vc=76 vn=3.0/g' build/preflight-evidence.env" \
  "PREFLIGHTGATE_FAILED evidence" \
  ":" \
  "java -cp build/preflight-gate com.hark.neokey.PreflightGate"

run_attack "dead guard (the sheet checker's evidence comparison neutered — its tag renamed away)" \
  "sed -i 's/sheet\.evidence_drift/attack.neutered.tag/' tests/com/hark/neokey/Preflight.java" \
  "PREFLIGHT regression: neg.evidence_tampered" \
  "cp build/preflight-proof-backup/Preflight.java tests/com/hark/neokey/Preflight.java"

run_attack "hidden tool (Preflight.java removed)" \
  "mv tests/com/hark/neokey/Preflight.java /tmp/Preflight.java.hidden" \
  "PREFLIGHTGATE_FAILED compile" \
  "cp build/preflight-proof-backup/Preflight.java tests/com/hark/neokey/Preflight.java"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo "== PROOF FAILED: an attack did not bite or a restore did not heal =="
  exit 1
fi
FINAL=$(gate /tmp/pf_final.txt)
if [ "$FINAL" -ne 0 ] || ! grep -q '^PREFLIGHTGATE_OK' /tmp/pf_final.txt; then
  echo "== PROOF FAILED: final gate not green =="; exit 1
fi
echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
grep -m1 '^PREFLIGHTGATE_OK' /tmp/pf_final.txt
