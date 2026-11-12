#!/bin/bash
# W55 perf-baseline gate (ROADMAP-V2 W55) — ci/perfbaseline_gate.sh
#
#   W55's second deliverable is the honest performance floor — measured, not
#   claimed, and NOT REWRITTEN silently:
#     • the JVM measurement re-runs on the REAL shipped assets (the real 134k
#       dictionary + the real NQF model) — the PerfGate discipline
#     • evidence/perf-baseline.tsv is the ONE record: NN inference p50/p95,
#       suggestion pipeline p50/p95, the cold-load proxy, the heap proxy,
#       plus the DEVICE floors (Perf.LIMITS) recorded verbatim
#     • every jvm budget must be SELF-CONSISTENT (budget == derive(value) at
#       %.3f) — a hand-loosened budget without its matching measurement refuses
#     • measured <= budget on every row — W56+ features that slow the engines
#       past the W55 floor fail here (the W57 exit criterion reads this line)
#     • the ANTI-REWRITE GUARD: src/Perf.java LIMITS/METRICS are parsed fresh
#       and must equal the recorded device floors — changing a floor = a
#       documented owner decision (the W54 ceiling pattern), never a quiet edit
#
#   Verdict: PERFBASELINE_OK checks=… metrics=8 device=7 (exit 0)
#            / PERFBASELINE_FAILED <tag> (exit 1)
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

OUT=build/perfbasegate; mkdir -p "$OUT"
SRC="src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/NQF.java src/com/hark/neokey/NNet.java src/com/hark/neokey/Predictor.java \
 tests/stubs/android/content/Context.java tests/stubs/android/content/res/AssetManager.java \
 tests/stubs/android/os/Build.java tests/stubs/android/graphics/Paint.java tests/stubs/org/json/JSONArray.java \
 tests/com/hark/neokey/FakeContext.java tests/com/hark/neokey/PerfBaseline.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "PERFBASELINE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "PERFBASELINE_FAILED compile"; exit 1; }

[ -f evidence/perf-baseline.tsv ] || { echo "PERFBASELINE_FAILED no record (evidence/perf-baseline.tsv missing — the floor is committed, its absence is tampering)"; exit 1; }

TOOL=$(java -cp "$OUT" com.hark.neokey.PerfBaseline --check 2>&1)
TRC=$?
echo "$TOOL"
if [ $TRC -eq 0 ] && echo "$TOOL" | grep -q '^PERFBASELINE_OK'; then
  echo "PERFBASELINE OK"
  exit 0
fi
echo "PERFBASELINE FAILED (rc=$TRC)"
exit 1
