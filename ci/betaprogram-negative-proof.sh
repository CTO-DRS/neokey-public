#!/bin/bash
# Negative proof for the beta-week rehearsal gate (W52 prep) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN. The proof fails loudly if any attack fails
# to bite or any restore fails to heal.
#
#   1) dead week expectation: the harness's demand on the ledger's verdict
#      neutered (BETA_WEEK_OK renamed away in the ck) — the ledger could
#      return ANYTHING and the rehearsal would still call the week green;
#      the gate bails on ledger.p0: a rehearsal that accepts any verdict
#      rehearses nothing.
#   2) the P0 law severed: the documented 1% threshold (P0_RATE_PCT) replaced
#      by a rogue literal 100.0 — a P0-red day would pass and the week would
#      be called green; the designed negative no longer bites and the
#      rehearsal fails closed on NEG-FAILED neg.p0_day_rate: proves the
#      documented line is load-bearing — if a red day still passed, the
#      criterion «صفر أعطال P0» would guard nothing.
#   3) hidden tool: BetaProgram.java removed — the gate fails closed at
#      compile time (a missing rehearsal is never a silent pass).
#
# Identity: the gate's verdict carries vc=1 vn=0.1.0 from the ONE manifest —
# a gate green on any other identity is not this week's rehearsal.
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== betaprogram negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p build/betaprogram-proof-backup
cp tests/com/hark/neokey/BetaProgram.java build/betaprogram-proof-backup/BetaProgram.java

gate() { bash ci/betaprogram_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker, $3 expected line (grep -E), $4 restore
  local name="$1" attacker="$2" expect="$3" restore="$4"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/bp_neg.txt)
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/bp_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -4 /tmp/bp_neg.txt; FAILED=1
  else
    echo "bit: $(grep -m1 -E "$expect" /tmp/bp_neg.txt)"
  fi
  eval "$restore"
  local rrc=$(gate /tmp/bp_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^BETAPROGRAM_OK' /tmp/bp_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -4 /tmp/bp_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^BETAPROGRAM_OK' /tmp/bp_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/bp_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^BETAPROGRAM_OK' /tmp/bp_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/bp_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^BETAPROGRAM_OK' /tmp/bp_base.txt)"

run_attack "dead week expectation (the harness's demand on the ledger verdict renamed away — a rehearsal that accepts any verdict rehearses nothing)" \
  "sed -i 's|greenVerdict.startsWith(\"BETA_WEEK_OK\")|greenVerdict.startsWith(\"BETA_WEEK_NEVER_OK\")|' tests/com/hark/neokey/BetaProgram.java" \
  "BETAPROGRAM regression: ledger\.p0" \
  "cp build/betaprogram-proof-backup/BetaProgram.java tests/com/hark/neokey/BetaProgram.java"

run_attack "the P0 law severed (the documented 1% line replaced by a rogue literal 100.0 — a red day passes, the week is called green)" \
  "sed -i 's|P0_RATE_PCT = 1.0|P0_RATE_PCT = 100.0|' tests/com/hark/neokey/BetaProgram.java" \
  "NEG-FAILED neg\.p0_day_rate" \
  "cp build/betaprogram-proof-backup/BetaProgram.java tests/com/hark/neokey/BetaProgram.java"

run_attack "hidden tool (BetaProgram.java removed — the gate fails closed at compile)" \
  "mv tests/com/hark/neokey/BetaProgram.java /tmp/betaprogram-hidden.java" \
  "BETAPROGRAM_FAILED compile" \
  "mv /tmp/betaprogram-hidden.java tests/com/hark/neokey/BetaProgram.java"

# ---- final: the source byte-identical to the backup, and the gate still green ---
if ! cmp -s tests/com/hark/neokey/BetaProgram.java build/betaprogram-proof-backup/BetaProgram.java; then
  echo "SOURCE NOT RESTORED byte-exact"; FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "PROOF FAILED betaprogram (see the lines above)"
  exit 1
fi
echo ""
echo "PROOF PASSED betaprogram 3/3 attacks bit their exact tags and the restores healed ($(grep -m1 '^BETAPROGRAM_OK' /tmp/bp_heal.txt))"
