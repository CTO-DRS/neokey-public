#!/bin/bash
# Theme-store gate (roadmap Phase 2, item B, week 3) — ci/theme_store_gate.sh
#
#   Measures the .neokey review line + the 70/30 creator economy, not the claim:
#     • the 25-pack founders catalog passes the automated reviewer; every pack's
#       sha256 matches its catalog pin (bytes reviewed == bytes published)
#     • review latency measured (p50 < 5 ms bar)
#     • 23 designed harmful packs refused with the right reason tag (planted
#       API keys/exfil URLs, oversized, undeclared vendor, bidi/tashkeel/
#       tatweel/length in the name, contrast violation, range/layout abuse,
#       wrong app, missing colours, malformed hex, dup JSON key, trailing
#       bytes, control char, neon without glow)
#     • post-review byte tampering caught by the publish hash pin
#     • full simulated month over the catalog: 70/30 exact to the cent, ledger
#       closes, refund reversal, refund-exceeds-gross adjustment, min-payout
#       carry, payout idempotence
#
#   Verdict: THEME_STORE_GATE_OK (exit 0) / THEME_STORE_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.ThemeStoreGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/storegate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/ThemeStoreGate.java src/com/hark/neokey/ThemePack.java src/com/hark/neokey/Economy.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "THEME_STORE_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "THEME_STORE_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.ThemeStoreGate
rc=$?
[ $rc -eq 0 ] && echo "THEME_STORE_GATE OK" || echo "THEME_STORE_GATE FAILED (rc=$rc)"
exit $rc
