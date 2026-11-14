#!/bin/bash
# ClarityMeter gate (roadmap v2, W68) — ci/clarity_gate.sh
#
#   Measures the W68 deliverable, not the claim:
#     • the ClarityMeterTest battery: a REFERENCE TEST FOR EACH of the
#       five Arabic-only metrics (sentence length, repetition, filler,
#       diacritic density, TTR — a failing text and a passing text per
#       metric, the exit criterion verbatim), the determinism law (same
#       text = same score, always), the fail-closed insufficient state,
#       and the deterministic display line
#     • W64 lesson applied: the LIVE source is compiled over build/test —
#       a gate running a stale class cannot see the attack
#   Verdict: CLARITYGATE_OK (exit 0) / CLARITYGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/claritygate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/ClarityMeterTest.java src/com/hark/neokey/ClarityMeter.java 2>&1
else
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/ClarityMeterTest.java src/com/hark/neokey/ClarityMeter.java 2>&1
fi || { echo "CLARITYGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.ClarityMeterTest
rc=$?
[ $rc -eq 0 ] && echo "CLARITY OK" || echo "CLARITY FAILED (rc=$rc)"
exit $rc
