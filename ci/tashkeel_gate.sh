#!/bin/bash
# W57 tashkeel gate (ROADMAP-V2 W57) — ci/tashkeel_gate.sh
#
#   W57's deliverable is the SECOND REAL ENGINE through the W55 contract,
#   measured not narrated:
#     • the battery (tests/TashkeelTest) green on the REAL committed asset:
#       strict load with the header claims re-counted (28020 entries / 28196
#       forms), the lookup spine (lexicon, norm fold, anti-gemination pins,
#       AMBIG variants, the R2 sun partial, ال-composition, the conjunction),
#       the three degrees, the contract ride, the 13 strict-load negatives
#       with their exact tags, the fail-closed state, the pure helpers
#     • the derivation is DETERMINISTIC: ci/make_tashkeel_index.py --check
#       byte-compares a fresh derivation against the committed asset
#     • the accuracy is MEASURED: the hand-verified gold set must stay
#       100.0% (a regression bites) plus the honest reference-testset
#       report (full / coverage / contradictions=0 — named coverage, never
#       dressed as accuracy)
#     • the batch-2 lever pull is DETERMINISTIC too: ci/make_feedback_asset.
#       py --check byte-compares the feedback wording asset
#     • the byte cost is RECORDED (law 5): post_trim − pre_wire must equal
#       the SUM of the engine rows (root.engine + tashkeel.engine +
#       feedback.copy), and post_trim stays under the ceiling. The FRESH
#       re-measurement itself lives in CAPACITYGATE — this gate pins the
#       battery, the determinism, the accuracy and the arithmetic.
#
#   Verdict: TASHKEELGATE_OK checks=… gold=… testset=… dex=…/ceiling margin=…
#            entries=… covered=… failclosed=ON / TASHKEELGATE_FAILED <reason> (exit 1)
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

OUT=build/tashkeelgate; mkdir -p "$OUT"
SJ=$(find_jar sqlite-jdbc.jar); LJ=$(find_jar slf4j-api.jar)
JARS="$SJ:$LJ"
SRC="tests/stubs/android/content/Context.java tests/stubs/android/content/res/AssetManager.java \
 tests/stubs/android/os/Build.java tests/stubs/org/json/JSONArray.java \
 src/com/hark/neokey/Engine.java src/com/hark/neokey/Engines.java src/com/hark/neokey/TashkeelEngine.java \
 src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java \
 src/com/hark/neokey/CommunityDict.java \
 tests/com/hark/neokey/TashkeelTest.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "TASHKEELGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
fi || { echo "TASHKEELGATE_FAILED compile"; exit 1; }

# ---- 1. the battery on the real asset ------------------------------------------
TOOL=$(java -cp "$OUT:$JARS" com.hark.neokey.TashkeelTest 2>&1)
TRC=$?
echo "$TOOL" | grep -E '^(PASS|FAIL|RESULT)' || true
[ $TRC -eq 0 ] || { echo "TASHKEELGATE_FAILED battery (rc=$TRC)"; echo "$TOOL" | tail -3; exit 1; }
CHECKS=$(echo "$TOOL" | grep -o 'TSXTEST_OK checks=[0-9]*' | grep -o '[0-9]*$')
[ -n "$CHECKS" ] || { echo "TASHKEELGATE_FAILED no_battery_verdict"; exit 1; }

# ---- 2. the derivation is deterministic (byte-exact vs the committed asset) ----
DET=$(python3 ci/make_tashkeel_index.py --check 2>&1) || { echo "TASHKEELGATE_FAILED determinism"; echo "$DET" | tail -2; exit 1; }
echo "$DET"
echo "$DET" | grep -q '^TASHKEELINDEX_CHECK_OK' || { echo "TASHKEELGATE_FAILED no_check_verdict"; exit 1; }

# ---- 2b. the batch-2 lever asset is deterministic too ---------------------------
FBK=$(python3 ci/make_feedback_asset.py --check 2>&1) || { echo "TASHKEELGATE_FAILED feedback_determinism"; echo "$FBK" | tail -2; exit 1; }
echo "$FBK"
echo "$FBK" | grep -q '^FEEDBACK_CHECK_OK' || { echo "TASHKEELGATE_FAILED no_feedback_verdict"; exit 1; }

# ---- 3. the accuracy is measured (gold 100% + the honest testset report) -------
ACC=$(python3 ci/make_tashkeel_index.py --testset 2>&1) || { echo "TASHKEELGATE_FAILED accuracy"; echo "$ACC" | tail -2; exit 1; }
echo "$ACC" | grep -E '^(GOLD|TESTSET)'
GOLD=$(echo "$ACC" | grep -o 'GOLD [0-9]*/[0-9]* accuracy=[0-9.]*%' | sed 's/^GOLD //')
TS=$(echo "$ACC" | grep -o 'TESTSET words=[0-9]* full=[0-9]* ([0-9.]*%) coverage=[0-9]* ([0-9.]*%)' | sed 's/^TESTSET //')
CONTRA=$(echo "$ACC" | grep -o 'contradictions=[0-9]*' | grep -o '[0-9]*$')
[ -n "$GOLD" ] && [ -n "$TS" ] && [ -n "$CONTRA" ] || { echo "TASHKEELGATE_FAILED accuracy_lines"; exit 1; }
echo "$ACC" | grep -q 'accuracy=100.0%' || { echo "TASHKEELGATE_FAILED gold_below_100 — the hand-verified truth regressed"; exit 1; }
[ "$CONTRA" = "0" ] || { echo "TASHKEELGATE_FAILED testset_contradictions=$CONTRA — a conflicting mark is a hard failure"; exit 1; }

# ---- 4. the byte cost is recorded (law 5: the arithmetic must close) -----------
POST=$(awk -F'\t' '$1=="dex" && $2=="post_trim" {print $3}' ci/capacity_ledger.tsv)
PREW=$(awk -F'\t' '$1=="dex" && $2=="pre_wire" {print $3}' ci/capacity_ledger.tsv)
RDELTA=$(awk -F'\t' '$1=="root.engine" {print $3}' ci/capacity_ledger.tsv)
TDELTA=$(awk -F'\t' '$1=="tashkeel.engine" {print $3}' ci/capacity_ledger.tsv)
FDELTA=$(awk -F'\t' '$1=="feedback.copy" {print $3}' ci/capacity_ledger.tsv)
CEIL=$(awk -F'\t' '$1=="ceiling" {print $3}' ci/capacity_ledger.tsv)
[ -n "$POST" ] && [ -n "$PREW" ] && [ -n "$RDELTA" ] && [ -n "$TDELTA" ] && [ -n "$FDELTA" ] && [ -n "$CEIL" ] || { echo "TASHKEELGATE_FAILED ledger_rows"; exit 1; }
[ $((POST - PREW)) -eq $((RDELTA + TDELTA + FDELTA)) ] || { echo "TASHKEELGATE_FAILED ledger_delta post=$POST pre_wire=$PREW sum=$((RDELTA + TDELTA + FDELTA))"; exit 1; }
[ "$POST" -le "$CEIL" ] || { echo "TASHKEELGATE_FAILED over_ceiling dex=$POST ceiling=$CEIL"; exit 1; }
MARGIN=$((CEIL - POST))
ENTRIES=$(awk -F'\t' '$1=="entries" {print $2}' assets/tashkeel_index.txt)
COV=$(awk -F'\t' '$1=="covered" {print $2}' assets/tashkeel_index.txt)
echo "TASHKEELGATE_OK checks=$CHECKS gold=$GOLD testset=$TS contradictions=$CONTRA dex=$POST/$CEIL margin=$MARGIN entries=$ENTRIES covered=$COV failclosed=ON"
