#!/bin/bash
# Dialect gate (roadmap Phase 1, item C1, week 5) — ci/dialect_gate.sh
#
#   Measures the C1 deliverable, not the claim:
#     • 3 dialect dictionaries (hijazi/najdi/gulf) built by dictsrc/build_dialect.py
#       from the in-repo Gulf core + curated marker table — QC'd as files
#     • dictsrc/dialect_markers.tsv ↔ Dialect.markers() ↔ assets: cell-for-cell
#       consistency (the in-code marker table is a mirror; drift = red build)
#     • detection accuracy ≥85% overall / ≥75% per class on the curated 64-row
#       gate set (hijazi/najdi/gulf/msa)
#     • detect() p50 < 1ms, deterministic verdicts
#     • negative proofs: stripped dictionary caught, shuffled labels collapse,
#       pure MSA never claims a Gulf dialect
#
#   Verdict: DIALECT_GATE_OK (exit 0) / DIALECT_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.DialectGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/dialectgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/DialectGate.java src/com/hark/neokey/Dialect.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "DIALECT_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "DIALECT_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.DialectGate
rc=$?
[ $rc -eq 0 ] && echo "DIALECT_GATE OK" || echo "DIALECT_GATE FAILED (rc=$rc)"
exit $rc
