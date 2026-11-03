#!/bin/bash
# In-app theme store gate (roadmap Phase 3, week 1) — ci/store_ui_gate.sh
#
#   Proves the store line, not the claim: money and ownership live or die here.
#     • the SHIPPED asset loads under the strict catalog contract and equals
#       the reviewed dictsrc source byte-for-byte
#     • all 25 founder packs purchase + install through the full guard chain
#       (ownership -> sha256 pin -> ThemePack.review with registered vendors)
#     • a full simulated month closes the 70/30 ledger to the cent (Economy only)
#     • designed negatives refused with exact tags; install p50 measured
#
# Verdict: STOREUI_GATE_OK … (exit 0) / STOREUI_GATE_FAILED <reason> (exit 1).
# JVM-only (com.hark.neokey.StoreUiGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/storeuigate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/StoreUiGate.java src/com/hark/neokey/StoreUi.java src/com/hark/neokey/ThemePack.java src/com/hark/neokey/Economy.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "STOREUI_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "STOREUI_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.StoreUiGate
rc=$?
[ $rc -eq 0 ] && echo "STOREUI_GATE OK" || echo "STOREUI_GATE FAILED (rc=$rc)"
exit $rc
