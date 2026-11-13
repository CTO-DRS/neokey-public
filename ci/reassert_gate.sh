#!/bin/bash
# Reassert gate (roadmap v2, W62) — ci/reassert_gate.sh
#
#   Measures the W62 deliverable, not the claim:
#     • the five engines' negatives bite with their EXACT tags and heal
#       byte-exact (the corruption is in-memory — the live HEAD assets
#       themselves are never touched, and they must load green)
#     • the live detection accuracy over the documented floor (85%)
#     • the dex margin measured FRESH (a real R8 build) above the
#       documented W62 line: margin >= 500 bytes
#
#   Verdict: REASSERTGATE_OK (exit 0) / REASSERTGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/reassertgate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/ReassertGate.java 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "REASSERTGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/ReassertGate.java 2>&1
fi || { echo "REASSERTGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.ReassertGate
rc=$?
[ $rc -ne 0 ] && { echo "REASSERT FAILED (gate rc=$rc)"; exit $rc; }

# ---- the dex margin, measured FRESH (a real R8 build) -----------------------
AJAR=$(find_jar android.jar) || { echo "REASSERTGATE_FAILED no android.jar"; exit 1; }
R8_ANDROID_JAR="$AJAR" ci/build_r8.sh > /tmp/reassert-build.log 2>&1 || {
  echo "REASSERTGATE_FAILED fresh build refused"; exit 1; }
DEX=$(grep -oE 'R8_DEX_BYTES=[0-9]+' build/release/gates.env 2>/dev/null | head -1 | cut -d= -f2)
[ -z "$DEX" ] && DEX=$(grep -oE 'dex [0-9]+/' /tmp/reassert-build.log | head -1 | grep -oE '[0-9]+')
[ -z "$DEX" ] && { echo "REASSERTGATE_FAILED no dex measurement"; exit 1; }
CEIL=$(grep -oE 'DEX_CEILING=[0-9]+' ci/phase3_gate.sh | head -1 | cut -d= -f2)
MARGIN=$((CEIL - DEX))
if [ "$MARGIN" -ge 500 ]; then
  echo "REASSERTGATE_OK dex=$DEX/$CEIL margin=$MARGIN floor=500 — الهامش فوق الخط الموثق"
  exit 0
fi
echo "REASSERTGATE_FAILED margin $MARGIN < 500 (the documented W62 line)"
exit 1
