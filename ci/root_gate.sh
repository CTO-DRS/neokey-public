#!/bin/bash
# W56 root gate (ROADMAP-V2 W56) — ci/root_gate.sh
#
#   W56's deliverable is the FIRST REAL ENGINE through the W55 contract,
#   measured not narrated:
#     • the battery (tests/RootEngineTest) green on the REAL committed asset:
#       strict load with the header claims re-counted (3667 roots / 84809
#       covered), the lookup spine, the contract ride, the 11 strict-load
#       negatives with their exact tags, the fail-closed state, the strip
#       helpers, and the Correction2 root signal (+400 attached /
#       byte-identical detached)
#     • the derivation is DETERMINISTIC: ci/make_root_index.py --check
#       byte-compares a fresh derivation against the committed asset
#     • the accuracy is MEASURED: the hand-verified gold set must stay
#       100.0% (a regression here bites), plus the honest coverage of
#       assets/testset_ar.txt (the W56 exit line, named coverage, never
#       dressed as accuracy)
#     • the byte cost is RECORDED (law 5): the ledger's root.engine row must
#       equal post_trim − pre_wire, and post_trim stays under the ceiling.
#       The FRESH re-measurement itself lives in CAPACITYGATE (which rebuilds
#       the dex on the real-R path and bites any stale claim) — this gate
#       pins the battery, the determinism, the accuracy and the arithmetic.
#
#   Verdict: ROOTGATE_OK checks=… gold=… testset=… dex=…/ceiling margin=… roots=… covered=…
#            / ROOTGATE_FAILED <reason> (exit 1)
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

OUT=build/rootgate; mkdir -p "$OUT"
SJ=$(find_jar sqlite-jdbc.jar); LJ=$(find_jar slf4j-api.jar)
JARS="$SJ:$LJ"
SRC="tests/stubs/android/content/Context.java tests/stubs/android/content/res/AssetManager.java \
 tests/stubs/android/os/Build.java tests/stubs/org/json/JSONArray.java \
 src/com/hark/neokey/Engine.java src/com/hark/neokey/Engines.java src/com/hark/neokey/RootEngine.java \
 src/com/hark/neokey/Correction2.java src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java \
 src/com/hark/neokey/CommunityDict.java \
 tests/com/hark/neokey/FakeContext.java tests/com/hark/neokey/RootEngineTest.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "ROOTGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$JARS" -d "$OUT" $SRC 2>&1
fi || { echo "ROOTGATE_FAILED compile"; exit 1; }

# ---- 1. the battery on the real asset ------------------------------------------
TOOL=$(java -cp "$OUT:$JARS" com.hark.neokey.RootEngineTest 2>&1)
TRC=$?
echo "$TOOL" | grep -E '^(PASS|FAIL|RESULT)' || true
[ $TRC -eq 0 ] || { echo "ROOTGATE_FAILED battery (rc=$TRC)"; echo "$TOOL" | tail -3; exit 1; }
CHECKS=$(echo "$TOOL" | grep -o 'ROOTTEST_OK checks=[0-9]*' | grep -o '[0-9]*$')
[ -n "$CHECKS" ] || { echo "ROOTGATE_FAILED no_battery_verdict"; exit 1; }

# ---- 2. the derivation is deterministic (byte-exact vs the committed asset) ----
DET=$(python3 ci/make_root_index.py --check 2>&1) || { echo "ROOTGATE_FAILED determinism"; echo "$DET" | tail -2; exit 1; }
echo "$DET"
echo "$DET" | grep -q '^ROOTINDEX_CHECK_OK' || { echo "ROOTGATE_FAILED no_check_verdict"; exit 1; }

# ---- 3. the accuracy is measured (gold 100% + the honest testset coverage) -----
ACC=$(python3 ci/make_root_index.py --testset 2>&1) || { echo "ROOTGATE_FAILED accuracy"; echo "$ACC" | tail -2; exit 1; }
echo "$ACC" | grep -E '^(GOLD|TESTSET)'
GOLD=$(echo "$ACC" | grep -o 'GOLD [0-9]*/[0-9]* accuracy=[0-9.]*%' | sed 's/^GOLD //')
TS=$(echo "$ACC" | grep -o 'TESTSET [0-9]*/[0-9]* coverage=[0-9.]*%' | sed 's/^TESTSET //')
[ -n "$GOLD" ] && [ -n "$TS" ] || { echo "ROOTGATE_FAILED accuracy_lines"; exit 1; }
echo "$ACC" | grep -q 'accuracy=100.0%' || { echo "ROOTGATE_FAILED gold_below_100 — the hand-verified truth regressed"; exit 1; }

# ---- 4. the byte cost is recorded (law 5: the arithmetic must close) -----------
POST=$(awk -F'\t' '$1=="dex" && $2=="post_trim" {print $3}' ci/capacity_ledger.tsv)
PREW=$(awk -F'\t' '$1=="dex" && $2=="pre_wire" {print $3}' ci/capacity_ledger.tsv)
RDELTA=$(awk -F'\t' '$1=="root.engine" {print $3}' ci/capacity_ledger.tsv)
CEIL=$(awk -F'\t' '$1=="ceiling" {v=$3} END{print v}' ci/capacity_ledger.tsv)
[ -n "$POST" ] && [ -n "$PREW" ] && [ -n "$RDELTA" ] && [ -n "$CEIL" ] || { echo "ROOTGATE_FAILED ledger_rows"; exit 1; }
# F10 repair: the W56-era single-engine equality (post-pre == root.engine)
# broke the moment a second engine row landed and was never re-run. The strict
# SUM EQUALITY is CapacityLedger's law (post == pre_wire + EVERY shipped row,
# audited against the FRESH dex); this gate keeps its own law as the COVERING
# check — the total movement covers the root engine's charge — and the fresh
# dex decision stays in capacity_gate. The CEIL read also fixes the latent
# multi-row awk (the LAST ceiling row decides, mirroring capacity_gate).
[ $((POST - PREW)) -ge "$RDELTA" ] || { echo "ROOTGATE_FAILED ledger_delta post=$POST pre_wire=$PREW root.engine=$RDELTA"; exit 1; }
[ "$POST" -le "$CEIL" ] || { echo "ROOTGATE_FAILED over_ceiling dex=$POST ceiling=$CEIL"; exit 1; }
MARGIN=$((CEIL - POST))
ROOTS=$(awk -F'\t' '$1=="roots" {print $2}' assets/root_index.txt)
COV=$(awk -F'\t' '$1=="covered" {print $2}' assets/root_index.txt)
echo "ROOTGATE_OK checks=$CHECKS gold=$GOLD testset=$TS dex=$POST/$CEIL margin=$MARGIN roots=$ROOTS covered=$COV failclosed=ON"
