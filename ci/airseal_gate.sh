#!/bin/bash
# AirSeal gate (roadmap v2, W64) — ci/airseal_gate.sh
#
#   Measures the W64 deliverable, not the claim:
#     • the AirSealTest battery (the live-state derivation, the comparable
#       fingerprint, the forged-seal refusal, the irreversible breach)
#     • the seal matches the NetGate state at every check: a truth table
#       over master/cap states asserted in the battery itself
#   Verdict: AIRSEALGATE_OK (exit 0) / AIRSEALGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/airsealgate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/AirSealTest.java src/com/hark/neokey/AirSeal.java 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "AIRSEALGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/AirSealTest.java src/com/hark/neokey/AirSeal.java 2>&1
fi || { echo "AIRSEALGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.AirSealTest
rc=$?
[ $rc -eq 0 ] && echo "AIRSEAL OK" || echo "AIRSEAL FAILED (rc=$rc)"
exit $rc
