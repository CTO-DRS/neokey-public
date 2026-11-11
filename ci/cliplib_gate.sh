#!/bin/bash
# Clipboard phase-6 gate — the library (roadmap W48, 2026-11-23) — ci/cliplib_gate.sh
#
#   Proves the library surface the phase-6 close is built on, not the claim:
#     • tags: normalization (trim, single-line, dedupe, 24-char cap, 12-tag cap),
#       set/read round trip, empty set clears, listTags counts live items
#     • the W48 search law: text hits win; tags fill the silence (fallback only,
#       no match spans) — and the fallback respects the active tab
#     • shortcuts: token validation (one word, ≤32, lowercased), uniqueness
#       (first owner keeps the token, the rejectee keeps its old value), blank
#       clears, templates outside the domain, live token→id map
#     • the W48 retention law: tagged + shortcut-carrying items are curated —
#       they survive clearTemporary/prune exactly like titled/categorised ones
#     • text tools: tidy (runs collapse, edges trim, blank runs collapse to ONE
#       blank line), stripMarks drops tashkeel keeping letters
#     • the W48 UI law (checked in SOURCE): the pin action sheet carries no
#       reorder action keys — pins reorder by drag on the pinned tab (W48
#       «بالسحب بدل ▲▼»); the layout emits no CA_UP/CA_DOWN anywhere
#     • import/export: tags + shortcut ride JSON byte-verbatim and CSV columns;
#       on import the file's FIRST shortcut holder keeps the token
#     • perf measured live: tags set 100 + shortcut map build over 1000 items
#
# Verdict: CLIPLIB_GATE_OK … (exit 0) / CLIPLIB_GATE_FAILED <reason> (exit 1).
# JVM-only — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

SQLITE=$(find_jar sqlite-jdbc.jar) || { echo "CLIPLIB_GATE_FAILED no sqlite-jdbc.jar in ./tools"; exit 1; }
SLF4J=$(find_jar slf4j-api.jar || true); CP="$SQLITE:${SLF4J:-$SQLITE}"

OUT=build/cliplibgate; mkdir -p "$OUT"
SRC="src/com/hark/neokey/ClipItem.java src/com/hark/neokey/ClipRepo.java src/com/hark/neokey/ClipNorm.java src/com/hark/neokey/ClipText.java src/com/hark/neokey/ClipIO.java tests/com/hark/neokey/ClipRepoTest.java tests/com/hark/neokey/ClipIOTest.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "CLIPLIB_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
fi || { echo "CLIPLIB_GATE_FAILED compile"; exit 1; }

RAW=$(java -cp "$OUT:$CP" com.hark.neokey.ClipRepoTest 2>&1)
RC=$?
echo "$RAW"

# the suite's own verdict: every check green, no exceptions
RESULT_LINE=$(echo "$RAW" | grep '^RESULT ' | tail -1)
PASSED=$(echo "$RESULT_LINE" | sed -E 's/^RESULT ([0-9]+) passed.*/\1/')
FAILED=$(echo "$RESULT_LINE" | sed -E 's/.* ([0-9]+) failed$/\1/')
if [ -z "$RESULT_LINE" ]; then
  echo "CLIPLIB_GATE_FAILED no-result (the suite must print its RESULT line — rc=$RC)"
  exit 1
fi
[ "${FAILED:-1}" = "0" ] || { echo "CLIPLIB_GATE_FAILED tests.failed=$FAILED"; exit 1; }
[ "${PASSED:-0}" -ge 124 ] || { echo "CLIPLIB_GATE_FAILED checks.gone (passed=$PASSED < 124 — the suite may not shrink)"; exit 1; }
[ "$RC" -eq 0 ] || { echo "CLIPLIB_GATE_FAILED suite.rc=$RC"; exit 1; }

# the W48 laws must be IN the suite, not assumed by the gate
LAW=$(echo "$RAW" | grep -cE "^PASS (tags normalized \(trim, single-line, dedupe\)|tag fallback finds tagged only|retention keeps tagged \+ shortcut|templates stay outside the shortcut domain|import keeps one shortcut owner|tidy collapses runs)")
[ "$LAW" = "6" ] || { echo "CLIPLIB_GATE_FAILED law.absent (only $LAW/6 W48 law checks ran — the suite may not drop the phase-6 laws)"; exit 1; }

# the W48 UI law lives in the SOURCE: no reorder action keys anywhere in the
# keyboard (the drag on the pinned tab replaced them — a re-added key turns this red)
if grep -qE 'CA_UP|CA_DOWN' src/com/hark/neokey/KeyboardView.java src/com/hark/neokey/NeoKeyService.java; then
  echo "CLIPLIB_GATE_FAILED arrows.returned (the pin action sheet must not carry reorder action keys — drag is the reorder law)"
  exit 1
fi

# live perf numbers from this run (tags set 100 / shortcut map build 1000) —
# each fragment is isolated first, the number is taken AFTER its colon (the
# W47 gates.env lesson: a suite name's "1000" must never poison the key)
TAGS_MS=$(echo "$RAW" | grep -oE 'PERF tags set 100: [0-9]+ ms' | tail -1 | sed -E 's/.*: ([0-9]+) ms.*/\1/')
SC_MS=$(echo "$RAW" | grep -oE 'shortcut map build 1000: [0-9]+ ms' | tail -1 | sed -E 's/.*: ([0-9]+) ms.*/\1/')
echo "CLIPLIBGATE checks=$PASSED tags100=${TAGS_MS:-?}ms scmap1000=${SC_MS:-?}ms"
echo "CLIPLIB_GATE_OK"
exit 0
