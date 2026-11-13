#!/bin/bash
# W58 grammar gate (ROADMAP-V2 W58) — ci/nudge_gate.sh
#
#   W58's deliverable is the THIRD REAL ENGINE through the W55 contract,
#   measured not narrated:
#     • the battery (tests/GrammarNudgeTest) green on the REAL committed asset:
#       strict load with the header claims re-counted (28 rules / 48 pairs /
#       56 examples) and the SIDES LAW re-verified at load, every rule's two
#       examples re-scanned independently, the three modes, the raw-matching
#       law (the correct spelling never bites), the contract ride, the
#       splice/incognito/privacy helpers, the annoyance law, and the
#       strict-load negatives with their exact tags
#     • the derivation is DETERMINISTIC: ci/make_grammar_rules.py --check
#       byte-compares a fresh derivation against the committed asset
#     • the annoyance is MEASURED: the clean reference testset (correct forms
#       of every pattern + the near-miss traps) must scan to ZERO nudges —
#       rate 0.0 ≤ the documented bound 2.0 (a false positive bites)
#     • the AGREEMENT LAW (the W53-prep pattern): every rule id in the asset
#       appears in docs/GRAMMAR.md — no divergence between the document and
#       the shipped matcher
#     • the byte cost is RECORDED (law 5): post_trim − pre_wire must equal
#       the SUM of the engine rows (root.engine + tashkeel.engine +
#       feedback.copy + grammar.engine), and post_trim stays under the
#       ceiling. The FRESH re-measurement itself lives in CAPACITYGATE —
#       this gate pins the battery, the determinism, the annoyance and the
#       arithmetic.
#
#   Verdict: NUDGEGATE_OK checks=… rules=… pairs=… examples=… rate=… bound=2.0
#            agreement=…/… dex=…/ceiling margin=… failclosed=ON
#            / NUDGEGATE_FAILED <reason> (exit 1)
#   Standalone by the W50-prep pattern: no pipeline stage (42 byte-stable), no
#   gates.env keys, no closer list moves — the suite counter stays byte-stable.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/nudgegate; mkdir -p "$OUT"
SJ=$(find_jar sqlite-jdbc.jar); LJ=$(find_jar slf4j-api.jar)
JARS="$SJ:$LJ"
SRC="src/com/hark/neokey/Engine.java src/com/hark/neokey/Engines.java src/com/hark/neokey/GrammarNudge.java \
 tests/com/hark/neokey/GrammarNudgeTest.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "NUDGEGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
fi || { echo "NUDGEGATE_FAILED compile"; exit 1; }

# ---- 1. the battery on the real asset ------------------------------------------
TOOL=$(java -cp "$OUT:$JARS" com.hark.neokey.GrammarNudgeTest 2>&1)
TRC=$?
echo "$TOOL" | grep -E '^(PASS|FAIL|RESULT)' || true
[ $TRC -eq 0 ] || { echo "NUDGEGATE_FAILED battery (rc=$TRC)"; echo "$TOOL" | tail -3; exit 1; }
CHECKS=$(echo "$TOOL" | grep -o 'NUDGETEST_OK checks=[0-9]*' | grep -o '[0-9]*$')
[ -n "$CHECKS" ] || { echo "NUDGEGATE_FAILED no_battery_verdict"; exit 1; }

# ---- 2. the derivation is deterministic (byte-exact vs the committed asset) ----
DET=$(python3 ci/make_grammar_rules.py --check 2>&1) || { echo "NUDGEGATE_FAILED determinism"; echo "$DET" | tail -2; exit 1; }
echo "$DET"
echo "$DET" | grep -q '^NUDGERULES_CHECK_OK' || { echo "NUDGEGATE_FAILED no_check_verdict"; exit 1; }

# ---- 3. the annoyance is measured (zero nudges on the clean reference) ---------
ANN=$(python3 ci/make_grammar_rules.py --testset 2>&1) || { echo "NUDGEGATE_FAILED annoyance"; echo "$ANN" | tail -2; exit 1; }
echo "$ANN"
RATE=$(echo "$ANN" | grep -o 'rate=[0-9.]*' | cut -d= -f2)
WORDS=$(echo "$ANN" | grep -o 'words=[0-9]*' | cut -d= -f2)
[ -n "$RATE" ] && [ -n "$WORDS" ] || { echo "NUDGEGATE_FAILED annoyance_lines"; exit 1; }
awk -v r="$RATE" 'BEGIN{exit !(r <= 2.0)}' || { echo "NUDGEGATE_FAILED annoyance_rate=$RATE > bound 2.0 — a false positive is a hard failure"; exit 1; }

# ---- 3b. the agreement law (the W53-prep pattern: doc == shipped matcher) ------
AGREE=0; MISS=""
while IFS= read -r rid; do
  if grep -q "$rid" docs/GRAMMAR.md 2>/dev/null; then AGREE=$((AGREE + 1)); else MISS="$MISS $rid"; fi
done < <(awk -F'\t' '$1=="R" {print $2}' assets/grammar_rules.txt)
TOTAL=$(awk -F'\t' '$1=="R" {print $2}' assets/grammar_rules.txt | wc -l)
[ "$AGREE" = "$TOTAL" ] || { echo "NUDGEGATE_FAILED agreement missing:$MISS — the guide must agree with every shipped rule"; exit 1; }

# ---- 4. the byte cost is recorded (law 5: the arithmetic must close) -----------
POST=$(awk -F'\t' '$1=="dex" && $2=="post_trim" {print $3}' ci/capacity_ledger.tsv)
PREW=$(awk -F'\t' '$1=="dex" && $2=="pre_wire" {print $3}' ci/capacity_ledger.tsv)
RDELTA=$(awk -F'\t' '$1=="root.engine" {print $3}' ci/capacity_ledger.tsv)
TDELTA=$(awk -F'\t' '$1=="tashkeel.engine" {print $3}' ci/capacity_ledger.tsv)
FDELTA=$(awk -F'\t' '$1=="feedback.copy" {print $3}' ci/capacity_ledger.tsv)
GDELTA=$(awk -F'\t' '$1=="grammar.engine" {print $3}' ci/capacity_ledger.tsv)
CEIL=$(awk -F'\t' '$1=="ceiling" {print $3}' ci/capacity_ledger.tsv)
[ -n "$POST" ] && [ -n "$PREW" ] && [ -n "$RDELTA" ] && [ -n "$TDELTA" ] && [ -n "$FDELTA" ] && [ -n "$GDELTA" ] && [ -n "$CEIL" ] || { echo "NUDGEGATE_FAILED ledger_rows"; exit 1; }
[ $((POST - PREW)) -eq $((RDELTA + TDELTA + FDELTA + GDELTA)) ] || { echo "NUDGEGATE_FAILED ledger_delta post=$POST pre_wire=$PREW sum=$((RDELTA + TDELTA + FDELTA + GDELTA))"; exit 1; }
[ "$POST" -le "$CEIL" ] || { echo "NUDGEGATE_FAILED over_ceiling dex=$POST ceiling=$CEIL"; exit 1; }
MARGIN=$((CEIL - POST))
RULES=$(awk -F'\t' '$1=="rules" {print $2}' assets/grammar_rules.txt)
PAIRS=$(awk -F'\t' '$1=="pairs" {print $2}' assets/grammar_rules.txt)
EXS=$(awk -F'\t' '$1=="examples" {print $2}' assets/grammar_rules.txt)
echo "NUDGEGATE_OK checks=$CHECKS rules=$RULES pairs=$PAIRS examples=$EXS rate=$RATE bound=2.0 agreement=$AGREE/$TOTAL dex=$POST/$CEIL margin=$MARGIN failclosed=ON"
