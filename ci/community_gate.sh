#!/bin/bash
# Community-dictionary enrichment gate (roadmap Phase 2, item D, week 5) — ci/community_gate.sh
#
#   Proves the review pipeline, not the claim: community trust lives or dies here.
#     • unified CSV contract enforced (exact header, strict UTF-8, no BOM)
#     • clean 40-row batch accepted 40/40 → review report + CONTRIBUTORS.md
#     • nine designed poison batches (one per refusal class) refused with the
#       EXACT expected tag: duplicates (internal + installed), missing
#       diacritics, broken skeleton, non-Arabic, forbidden words, bad license,
#       bad header (whole file), BOM (whole file)
#     • 110 seeded negatives (seed 1703) inside a 220-row batch: every
#       sabotaged row refused, every clean row accepted
#     • p50 per-row check time measured on an 800-row clean batch (< 5000 µs)
#
# Verdict: COMMUNITY_GATE_OK … (exit 0) / COMMUNITY_GATE_FAILED <reason> (exit 1).
# JVM-only (com.hark.neokey.CommunityDictGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/communitygate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/CommunityDictGate.java src/com/hark/neokey/CommunityDict.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "COMMUNITY_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "COMMUNITY_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.CommunityDictGate
rc=$?
[ $rc -eq 0 ] && echo "COMMUNITY_GATE OK" || echo "COMMUNITY_GATE FAILED (rc=$rc)"
exit $rc
