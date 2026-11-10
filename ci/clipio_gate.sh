#!/bin/bash
# Clipboard phase-5 gate — import/export integrity (roadmap W47, 2026-11-16) — ci/clipio_gate.sh
#
#   Proves the clipboard import/export surface, not the claim. The code exists
#   since the week-18 store line (072b792) with its suite; W47 gives it the
#   gate the roadmap demanded + the integrity law it was missing:
#     • the three formats re-run fresh: JSON round trip byte-exact (every
#       property), CSV text+basic fields, TXT text-only
#     • duplicate handling: re-import detects dups; skip / copy / replace
#       modes each behave exactly as documented; preview counts new/dup/invalid
#     • hostile input rejected with exact outcomes: broken JSON, wrong format
#       tag, newer schema, 5000-deep nesting, oversized item, over-MAX_ITEMS
#       file (50,000 cap — rejected, not truncated), unclosed CSV quote
#     • W47 integrity law: a line equal to TXT_SEP inside an item must NOT
#       silently split the item on TXT re-import — writeTxt escapes the
#       occurrence (zero-width space inside the dash run, documented delta),
#       JSON stays byte-exact
#     • transaction: a failing write rolls back completely; pin limit (100)
#       respected on import
#     • perf measured live: export 1000 + parse+dup+import 1000 (ms)
#
# Verdict: CLIPIO_GATE_OK … (exit 0) / CLIPIO_GATE_FAILED <reason> (exit 1).
# JVM-only (ClipIO has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

SQLITE=$(find_jar sqlite-jdbc.jar) || { echo "CLIPIO_GATE_FAILED no sqlite-jdbc.jar in ./tools"; exit 1; }
SLF4J=$(find_jar slf4j-api.jar || true); CP="$SQLITE:${SLF4J:-$SQLITE}"

OUT=build/clipiogate; mkdir -p "$OUT"
SRC="src/com/hark/neokey/ClipItem.java src/com/hark/neokey/ClipRepo.java src/com/hark/neokey/ClipNorm.java src/com/hark/neokey/ClipText.java src/com/hark/neokey/ClipIO.java tests/com/hark/neokey/ClipRepoTest.java tests/com/hark/neokey/ClipIOTest.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "CLIPIO_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
fi || { echo "CLIPIO_GATE_FAILED compile"; exit 1; }

RAW=$(java -cp "$OUT:$CP" com.hark.neokey.ClipRepoTest 2>&1)
RC=$?
echo "$RAW"

# the suite's own verdict: every check green, no exceptions
RESULT_LINE=$(echo "$RAW" | grep '^RESULT ' | tail -1)
PASSED=$(echo "$RESULT_LINE" | sed -E 's/^RESULT ([0-9]+) passed.*/\1/')
FAILED=$(echo "$RESULT_LINE" | sed -E 's/.* ([0-9]+) failed$/\1/')
if [ -z "$RESULT_LINE" ]; then
  echo "CLIPIO_GATE_FAILED no-result (the suite must print its RESULT line — rc=$RC)"
  exit 1
fi
[ "${FAILED:-1}" = "0" ] || { echo "CLIPIO_GATE_FAILED tests.failed=$FAILED"; exit 1; }
[ "${PASSED:-0}" -ge 89 ] || { echo "CLIPIO_GATE_FAILED checks.gone (passed=$PASSED < 89 — the suite may not shrink)"; exit 1; }
[ "$RC" -eq 0 ] || { echo "CLIPIO_GATE_FAILED suite.rc=$RC"; exit 1; }

# the W47 integrity law must be IN the suite, not assumed by the gate
echo "$RAW" | grep -q "txt separator-line escape keeps item count" \
  || { echo "CLIPIO_GATE_FAILED law.absent (the TXT separator escape check is missing from the suite)"; exit 1; }

# live perf numbers from this run (export 1000 / parse+dup+import 1000)
EX_MS=$(echo "$RAW" | grep -oE 'PERF export 1000: [0-9]+ ms' | tail -1 | grep -oE '[0-9]+')
IM_MS=$(echo "$RAW" | grep -oE 'parse\+dup\+import 1000: [0-9]+ ms' | tail -1 | grep -oE '[0-9]+')
echo "CLIPIOGATE checks=$PASSED export1000=${EX_MS:-?}ms import1000=${IM_MS:-?}ms"
echo "CLIPIO_GATE_OK"
exit 0
