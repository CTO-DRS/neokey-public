#!/bin/bash
# Neural-inference gate (roadmap Phase 1, item A1, week 3) — ci/nnet_gate.sh
#
#   Measures the A1 deliverable, not the claim:
#     • NQF int8 quantized weight format (writer + strict reader, CRC-guarded)
#     • NNet pure-Java engine: real int8×int8→int32 quantized matmul, zero
#       android.*, zero external libraries
#     • p50 inference < 8ms on JVM on a deterministic ~1.63M-param test model
#       (the A2 scale), model file < 8MB, format overhead < 2% over 1B/param
#     • negative proofs: magic/version/truncation/blob-flip/meta-flip all refused
#
#   Verdict: NNET_GATE_OK (exit 0) / NNET_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.NNetGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/nnetgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/NNetGate.java src/com/hark/neokey/NQF.java src/com/hark/neokey/NNet.java src/com/hark/neokey/Predictor.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "NNET_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "NNET_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.NNetGate
rc=$?
[ $rc -eq 0 ] && echo "NNET_GATE OK" || echo "NNET_GATE FAILED (rc=$rc)"
exit $rc
