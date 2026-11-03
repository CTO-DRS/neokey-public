#!/bin/bash
# Signed-dictionary-merge gate (roadmap Phase 3, week 2) — ci/dictsign_gate.sh
#
#   Proves the publishing chain, not the claim: a reviewed community batch
#   becomes a signed release asset whose bytes the engine consumes AFTER the
#   installed dictionary, in documented order — or nothing ships.
#     • the shipped asset equals a fresh re-publish of the reviewed batch
#       byte-for-byte (reviewed == published)
#     • golden pin: docs/NQF-GOLDEN.json carries the asset's sha256
#     • engine consumption on the real 150k corpus: pack ranks start after
#       the corpus, pack order preserved, score floor 3000, suggest works
#     • 12 designed negatives refused with exact tags (tamper, post-review
#       swap, forged counter, foreign batch, corrupted pins, truncation,
#       BOM, reforged poison, installed-duplicate)
#     • publish/load p50 measured
#
# Verdict: DICTSIGN_GATE_OK … (exit 0) / DICTSIGN_GATE_FAILED <reason> (exit 1).
# JVM-only (com.hark.neokey.DictSignGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/dictsigngate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/DictSignGate.java tests/com/hark/neokey/FakeContext.java $(find tests/stubs -name '*.java') src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java src/com/hark/neokey/Dictionary.java src/com/hark/neokey/ThemePack.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "DICTSIGN_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "DICTSIGN_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.DictSignGate
rc=$?
[ $rc -eq 0 ] && echo "DICTSIGN_GATE OK" || echo "DICTSIGN_GATE FAILED (rc=$rc)"
exit $rc
