#!/bin/bash
# Platform-neutrality gate (roadmap Phase 2, item C, week 4) — ci/nqfport_gate.sh
#
#   Proves the port, not the claim: the iOS promise lives or dies here.
#     • every shipping asset's bytes + sha256 match its golden pin
#       (docs/NQF-GOLDEN.json — bytes reviewed == bytes published)
#     • the spec-only reference reader (NqfPort — written from docs/NQF.md,
#       zero code shared with NQF.java) reads the shipping model and agrees
#       bit-exactly with the official reader on every tensor: dtype, dims,
#       IEEE-754 scale bits, crc32, raw sha256, dequantized-value digest
#     • NNet.deqRow (the production engine path) matches port values bit-exactly
#     • text-asset contract: line/unique counts per pin, TAB structure for
#       bigram/emoji assets
#     • port read latency measured (p50 < 50 ms bar — verification path, not inference)
#     • negatives on the REAL model: 7 targeted corruptions with expected
#       refusal tags + 96 deterministic bit flips — refused by BOTH readers
#       (independence is enforced from both sides)
#
#   Verdict: NQFPORT_GATE_OK … (exit 0) / NQFPORT_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.NqfPortGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/portgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/NqfPortGate.java src/com/hark/neokey/NqfPort.java src/com/hark/neokey/NQF.java src/com/hark/neokey/NNet.java src/com/hark/neokey/ThemePack.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "NQFPORT_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "NQFPORT_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.NqfPortGate
rc=$?
[ $rc -eq 0 ] && echo "NQFPORT_GATE OK" || echo "NQFPORT_GATE FAILED (rc=$rc)"
exit $rc
