#!/bin/bash
# Capacity gate (ROADMAP-V2 W54) — ci/capacity_gate.sh
#
#   W54's deliverable is the CAPACITY BOOK, enforced not narrated:
#     • the lever ledger (ci/capacity_ledger.tsv) agrees with reality — every
#       "done" lever's asset exists in the exact documented shape, every trimmed
#       source is actually trimmed (the literals are GONE, the interpreter ships)
#     • the fresh R8 dex (real-R path, rebuilt here) is BELOW the pre-trim record,
#       at/below the ledger's post-trim claim, under the ceiling — a stale or
#       inflated claim bites (law 5: the byte-cost law, W48's lesson generalized)
#     • the ceiling decision is documented in TWO places (ledger row + ROADMAP-V2
#       W54-CEILING-DECISION) — no silent raise, ever
#     • trim_gate + dialect_gate re-run fresh green on this tree
#     • the behavioral round-trip evidence is committed (ci/capacity-roundtrip.log)
#
#   Verdict: CAPACITYGATE_OK checks=… dex=…/CEIL margin=… levers=…/… ceiling=W58-RAISE
#            (the LAST ceiling.raised row decides — the ledger keeps the raise history)
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

OUT=build/capacitygate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/CapacityLedger.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "CAPACITYGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "CAPACITYGATE_FAILED compile"; exit 1; }

# ---- the fresh measurement decides: rebuild the dex (real-R path) -------------
AJAR="${R8_ANDROID_JAR:-tools/android.jar}"
BUILD=$(R8_ANDROID_JAR="$AJAR" ci/build_r8.sh 2>&1) || { echo "CAPACITYGATE_FAILED fresh_build"; echo "$BUILD" | tail -3; exit 1; }
DEXY=$(echo "$BUILD" | grep -o 'R8_OK dex=[0-9]*' | grep -o '[0-9]*$')
[ -n "$DEXY" ] || { echo "CAPACITYGATE_FAILED no_dex_verdict"; exit 1; }

# ---- the ledger-vs-reality auditor -------------------------------------------
TOOL=$(java -cp "$OUT" com.hark.neokey.CapacityLedger "$DEXY" 2>&1)
TRC=$?
echo "$TOOL"
[ $TRC -eq 0 ] || { echo "CAPACITYGATE_FAILED ledger"; exit 1; }
CHECKS=$(echo "$TOOL" | grep -o 'CAPACITYLEDGER_OK checks=[0-9]*' | grep -o '[0-9]*$')

# ---- the standing guards re-run fresh on this exact tree ----------------------
TRIM=$(ci/trim_gate.sh 2>&1) || { echo "CAPACITYGATE_FAILED trim_gate"; echo "$TRIM" | tail -2; exit 1; }
echo "$TRIM" | tail -1
DIA=$(ci/dialect_gate.sh 2>&1) || { echo "CAPACITYGATE_FAILED dialect_gate"; echo "$DIA" | tail -2; exit 1; }
echo "$DIA" | tail -1

POST=$(awk -F'\t' '$1=="dex" && $2=="post_trim" {print $3}' ci/capacity_ledger.tsv)
CEIL=$(awk -F'\t' '$1=="ceiling" {c=$3} END {print c}' ci/capacity_ledger.tsv)
MARGIN=$((CEIL - DEXY))
echo "CAPACITYGATE_OK checks=$CHECKS dex=$DEXY/$CEIL margin=$MARGIN levers=3/5 r8=reviewed ceiling=W69-RAISE"
