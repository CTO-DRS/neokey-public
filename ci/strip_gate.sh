#!/bin/bash
# Strip gate (roadmap v2, W61) — ci/strip_gate.sh
#
#   Measures the W61 deliverable, not the claim:
#     • the StripAcceptTest battery (the honest unknown-bucket law, the
#       factory wipe, the format roundtrip)
#     • the acceptance feed: the personal acceptance = the +1 reinforcement
#       weight on top of the typing learn (the documented feed into
#       AdaptEngine), measured against the engine's own counter
#     • the fresh suggest p50 from the perf baseline (صفر انحدار زمني —
#       the keystroke p50 holds under the documented budget)
#
#   Verdict: STRIPGATE_OK (exit 0) / STRIPGATE_FAILED <reason> (exit 1).
#   JVM-only — runs anywhere. Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/stripgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/StripGate.java tests/com/hark/neokey/StripAcceptTest.java src/com/hark/neokey/StripAccept.java src/com/hark/neokey/AdaptEngine.java src/com/hark/neokey/Engine.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "STRIPGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "STRIPGATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.StripGate
rc=$?
[ $rc -eq 0 ] && echo "STRIP OK" || echo "STRIP FAILED (rc=$rc)"
exit $rc
