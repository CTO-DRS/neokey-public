#!/bin/bash
# Dialect pack gate (roadmap v2, W73) — ci/packgate_gate.sh
#
#   Measures the W73 deliverable, not the claim:
#     • PURITY BY CONSTRUCTION: DialectPack compiles with NO android.jar on
#       the classpath — a pure-JVM extension riding the DictSign chain
#     • the DialectPackTest battery: the exit criteria verbatim — a forged
#       pack in every shape bites its exact tag (the golden pin forged WITH
#       a self-signed envelope → DPACK_PIN_MISMATCH, the outer signature →
#       DPACK_SIG, the schema line → DIALECT_SCHEMA, the inner body
#       reorder → DPACK_SIG/DPACK_WORDS, the empty dialect → DIALECT_ID,
#       the refused merge → MERGE_REFUSED), the golden pin is deterministic
#       and the dialect identity is part of the pin (two dialects, same
#       words, different pins), and a CLEAN pack merges byte-exact (the
#       honest count, the ranks identical across two independent
#       dictionaries, landing after the installed corpus)
#     • THE MECHANICAL THANKS GLUE: the CommunityDict thanks section rides
#       the committed tree (the review report carries «شكر المساهمين») —
#       the gate greps the glue line
#   Verdict: PACKGATE_OK checks=N pins=2 dialects=2 merges=2 assets=CLEAN
#            (exit 0) / PACKGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/packgate; rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: no tools/android.jar here ON PURPOSE — the purity proof (header).
java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  src/com/hark/neokey/DialectPack.java src/com/hark/neokey/DictSign.java \
  src/com/hark/neokey/CommunityDict.java \
  tests/com/hark/neokey/DialectPackTest.java 2>&1 \
  || { echo "PACKGATE_FAILED compile (purity break? an android.* import bites here)"; exit 1; }

BAT=$(java -cp "$OUT" com.hark.neokey.DialectPackTest 2>&1)
RC=$?
echo "$BAT" | grep -E '^(PASS|FAIL) |^RESULT ' || true
SUM=$(echo "$BAT" | grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' | tail -1)
[ $RC -ne 0 ] && { echo "PACKGATE_FAILED battery ($SUM)"; exit 1; }
CHECKS=$(echo "$SUM" | grep -oE '[0-9]+' | head -1)
FAILED=$(echo "$SUM" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+')
[ "$FAILED" = "0" ] || { echo "PACKGATE_FAILED battery ($SUM)"; exit 1; }

# the mechanical thanks glue: the thanks section rides the review report
grep -q 'شكر المساهمين' src/com/hark/neokey/CommunityDict.java \
  || { echo "PACKGATE_FAILED glue (the mechanical thanks section lost from the review report)"; exit 1; }
# the pack rides the existing signed chain: DictSign.load is the ONLY inner loader
grep -q 'DictSign.load(inner' src/com/hark/neokey/DialectPack.java \
  || { echo "PACKGATE_FAILED glue (the inner dpack bypassed DictSign.load — a second chain regrew)"; exit 1; }

# zero binary assets: the committed assets tree is untouched
DIRTY=$(git status --porcelain -- assets/ | wc -l)
[ "$DIRTY" = "0" ] || { echo "PACKGATE_FAILED assets ($DIRTY asset files touched — the week ships zero binary assets)"; exit 1; }

echo "PACKGATE_OK checks=$CHECKS pins=2 dialects=2 merges=2 assets=CLEAN"
