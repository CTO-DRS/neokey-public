#!/bin/bash
# Trim gate (phase 4, item 1, week 22) — ci/trim_gate.sh
#
#   The REAL dex trim, enforced forever after (the promise documented twice in
#   phase 3 — "التقليم الحقيقي لا رفع" — finally pulled and now guarded):
#     • dex <= 512,000B (ceiling LOWERED from 520,000 in ci/build_r8.sh) and
#       strictly below the pre-trim shipped 518,688B
#     • res/values/strings.xml <-> src bijection (no missing name = runtime
#       crash, no dead entry, no duplicates — aapt2 refuses duplicates)
#     • XML QC per nk_ entry (name shape, @/? guard, %/formatted pairing,
#       origin comment)
#     • ZERO long Arabic literals (>=60B) in the 7 shell classes outside
#       static initializers — new long copy goes to resources, not the dex
#     • purity: the tests/run.sh SRC set still has zero `import android`
#     • translation coupling: moved strings stay byte-identical, so exact
#       ui_en.txt keys still hit (coupled count printed)
#
#   Verdict: TRIM_GATE_OK / TRIM_GATE_FAILED <tag> (exit 1). Pure JVM.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/trimgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/TrimGate.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "TRIM_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "TRIM_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.TrimGate
rc=$?
[ $rc -eq 0 ] && echo "TRIM_GATE OK" || echo "TRIM_GATE FAILED (rc=$rc)"
exit $rc
