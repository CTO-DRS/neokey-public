#!/bin/bash
# W55 harness gate (ROADMAP-V2 W55) — ci/harness_gate.sh
#
#   W55's first deliverable is the unified engine contract, TESTED not
#   narrated — the exit criterion is "the contract tested with a test engine":
#     • src/Engine.java + src/Engines.java compile fresh and ride the battery
#     • the reference engine (FamilyProbe — the W56 root-family rehearsal)
#       runs the contract end-to-end: one Ctx shape in, source-labeled Cands out
#     • the fail-closed negatives BITE: a throwing engine, an Error-throwing
#       engine, a null-output engine, null/blank holes — each contained to an
#       EXACT counted failure while the other engines still flow
#     • strict registration: null engine / blank id / duplicate id refused,
#       the registry never half-built
#     • the failure counter is exact across the whole battery (no hidden failures)
#
#   Verdict: HARNESSGATE_OK checks=… engines=… failclosed=… (exit 0)
#            / HARNESSGATE_FAILED … (exit 1)
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

OUT=build/harnessgate; mkdir -p "$OUT"
SRC="src/com/hark/neokey/Engine.java src/com/hark/neokey/Engines.java tests/com/hark/neokey/EngineHarness.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "HARNESSGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "HARNESSGATE_FAILED compile"; exit 1; }

TOOL=$(java -cp "$OUT" com.hark.neokey.EngineHarness 2>&1)
TRC=$?
echo "$TOOL"
if [ $TRC -eq 0 ] && echo "$TOOL" | grep -q '^HARNESSGATE_OK'; then
  echo "HARNESSGATE OK"
  exit 0
fi
echo "HARNESSGATE FAILED (rc=$TRC)"
exit 1
