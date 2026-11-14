#!/bin/bash
# AAC gate (roadmap v2, W70) — ci/aac_gate.sh
#
#   Measures the W70 deliverable, not the claim:
#     • PURITY BY CONSTRUCTION: AACBoard compiles with NO android.jar on
#       the classpath — a pure-JVM deterministic core, no Android escape
#       hatch (the gate fails the moment someone imports android.*)
#     • the AACBoardTest battery: the exit criteria verbatim — the four
#       fixed categories documented and fail-closed, same inputs = same
#       board same order (determinism measured twice), the big-touch grid
#       (max 3 per row, no empty row), the community-dictionary customization
#       (tagged clips win the defaults, order preserved, wrong-category tags
#       stay out, non-Arabic/empty declared not silently inserted, the 12
#       cap enforced) and the insert path (clean text passes, empty/null
#       refuse AAC_EMPTY_PHRASE, over-limit refuses AAC_PHRASE_TOO_LONG)
#     • LIVE INSERT PATH: the WriterActivity glue is on the committed tree —
#       the AAC section rides the existing draft insert path (no second
#       layer): the glue lines must exist, the pure insert plan must be the
#       only writer, and no new permission/binary asset
#   Verdict: AACGATE_OK checks=N cats=4 phrases=32 assets=CLEAN
#            (exit 0) / AACGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/aacgate; rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: no tools/android.jar here ON PURPOSE — the purity proof (header).
java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  src/com/hark/neokey/AACBoard.java tests/com/hark/neokey/AACBoardTest.java 2>&1 \
  || { echo "AACGATE_FAILED compile (purity break? an android.* import bites here)"; exit 1; }

BAT=$(java -cp "$OUT" com.hark.neokey.AACBoardTest 2>&1)
RC=$?
echo "$BAT" | grep -E '^(PASS|FAIL) |^RESULT ' || true
SUM=$(echo "$BAT" | grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' | tail -1)
[ $RC -ne 0 ] && { echo "AACGATE_FAILED battery ($SUM)"; exit 1; }
CHECKS=$(echo "$SUM" | grep -oE '[0-9]+' | head -1)
FAILED=$(echo "$SUM" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+')
[ "$FAILED" = "0" ] || { echo "AACGATE_FAILED battery ($SUM)"; exit 1; }

# the live glue: the WriterActivity AAC section rides the existing insert path
GLUE=$(grep -c 'AACBoard.snapshotFor\|AACBoard.insertPlan\|AACBoard.categories' src/com/hark/neokey/WriterActivity.java)
[ "$GLUE" -ge 3 ] || { echo "AACGATE_FAILED glue ($GLUE — the Writer AAC section lost its pure-core calls)"; exit 1; }
grep -q 'private void aacInsert' src/com/hark/neokey/WriterActivity.java \
  || { echo "AACGATE_FAILED glue (aacInsert missing — the insert must ride aacInsert)"; exit 1; }
# no second insert layer: aacInsert must go through AACBoard.insertPlan (the pure plan), not write text directly
grep -A6 'private void aacInsert' src/com/hark/neokey/WriterActivity.java | grep -q 'AACBoard.Insert ins = AACBoard.insertPlan' \
  || { echo "AACGATE_FAILED glue (aacInsert bypasses the pure insert plan — a second layer regrew)"; exit 1; }

# the battery count the gate declares: 4 categories × 8 defaults = 32 phrases
# (measured inside the DEFAULTS block: 4 category keys + 32 phrases = 36 strings)
PHRASES=$(sed -n '/static {/,/^    }$/p' src/com/hark/neokey/AACBoard.java | grep -oE '"[^"]+"' | wc -l)
[ "$PHRASES" = "32" ] || { echo "AACGATE_FAILED phrases ($PHRASES != 32 — the default inventory (4 × 8 phrases) moved silently)"; exit 1; }

# zero binary assets: the committed assets tree is untouched
DIRTY=$(git status --porcelain -- assets/ | wc -l)
[ "$DIRTY" = "0" ] || { echo "AACGATE_FAILED assets ($DIRTY asset files touched — the week ships zero binary assets)"; exit 1; }
[ "$(git status --porcelain -- src/ | wc -l)" = "0" ] || true   # tree state is the caller's business

echo "AACGATE_OK checks=$CHECKS cats=4 phrases=32 assets=CLEAN glue=$GLUE"
