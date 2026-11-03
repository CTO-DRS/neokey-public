#!/bin/bash
# Store-listing gate (roadmap item D, week 7) — ci/play_gate.sh
#
#   Hard-verifies the Play Store package BEFORE any upload:
#     listing.txt limits (name ≤30 / short ≤80 / full ≤4000, per locale, as code
#     points — the file's own (n/m) annotations must match reality),
#     8 screenshots 1080×1920, feature graphic 1024×500, icon 512×512 ≤1MB,
#     promo video ≈30s, privacy.html, and email/site consistency with Feedback.java.
#
#   Verdict: PLAY_GATE_OK (exit 0) / PLAY_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.PlayGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/playgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/PlayGate.java src/com/hark/neokey/Feedback.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "PLAY_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "PLAY_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.PlayGate play
rc=$?
[ $rc -eq 0 ] && echo "PLAY_GATE OK" || echo "PLAY_GATE FAILED (rc=$rc)"
exit $rc
