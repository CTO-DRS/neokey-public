#!/bin/bash
# Personal gate (roadmap v2, W60) — ci/personal_gate.sh
#
#   Measures the W60 deliverable, not the claim:
#     • the AdaptEngineTest battery (learn/decay/cap/contract/badge/wipe laws)
#     • the measured improvement on assets/testset_personal.txt vs the
#       documented baseline: every queried token verified ABSENT from the
#       base dictionary (assets/ar.txt) — the baseline cannot surface it,
#       so p50/p90 4→1/2 is measured, not assumed
#     • the half-life law LIVES: the deliberately decayed token (59 days)
#       must miss — the hits alone are not the metric
#     • the one-tap wipe is factory byte-state: empty serialize, empty
#       parse, empty answers everywhere
#
#   Verdict: PERSONALGATE_OK (exit 0) / PERSONALGATE_FAILED <reason> (exit 1).
#   JVM-only — runs anywhere. Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/personalgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/PersonalGate.java tests/com/hark/neokey/AdaptEngineTest.java src/com/hark/neokey/AdaptEngine.java src/com/hark/neokey/Engine.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "PERSONALGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "PERSONALGATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.PersonalGate
rc=$?
[ $rc -eq 0 ] && echo "PERSONAL OK" || echo "PERSONAL FAILED (rc=$rc)"
exit $rc
