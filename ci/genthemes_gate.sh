#!/bin/bash
# GenThemes gate (roadmap v2, W69) — ci/genthemes_gate.sh
#
#   Measures the W69 deliverable, not the claim:
#     • PURITY BY CONSTRUCTION: GenThemes compiles with NO android.jar on
#       the classpath — a pure-JVM deterministic core, no Android escape
#       hatch (the gate fails the moment someone imports android.*)
#     • the GenThemesTest battery: the exit criteria verbatim — same seed =
#       same pixels byte-exact (measured twice + SHA-256 re-verified), the
#       contrast law measured across 300 seeds (text/bg >= 4.5, accent
#       text >= 3.0), five reference pixel hashes re-measured fresh, 200
#       distinct seeds -> 200 distinct pixel hashes, all four art styles
#       alive, fail-closed fallback, closed modes (law 8), the render
#       budget measured, and the Theme glue contract
#     • ZERO BINARY ASSETS: no file under assets/ modified, created or
#       deleted against the committed tree (git status must be silent)
#   Verdict: GENTHEMESGATE_OK checks=N hashes=5 seeds=300 assets=CLEAN
#            (exit 0) / GENTHEMESGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/genthemesgate; rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: no tools/android.jar here ON PURPOSE — the purity proof (header).
java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  src/com/hark/neokey/GenThemes.java tests/com/hark/neokey/GenThemesTest.java 2>&1 \
  || { echo "GENTHEMESGATE_FAILED compile (purity break? an android.* import bites here)"; exit 1; }

BAT=$(java -cp "$OUT" com.hark.neokey.GenThemesTest 2>&1)
RC=$?
echo "$BAT" | grep -E '^(PASS|FAIL) |^RESULT ' || true
SUM=$(echo "$BAT" | grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' | tail -1)
[ $RC -ne 0 ] && { echo "GENTHEMESGATE_FAILED battery ($SUM)"; exit 1; }
CHECKS=$(echo "$SUM" | grep -oE '[0-9]+' | head -1)
FAILED=$(echo "$SUM" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+')
[ "$FAILED" = "0" ] || { echo "GENTHEMESGATE_FAILED battery ($SUM)"; exit 1; }

# zero binary assets: the committed assets tree is untouched
DIRTY=$(git status --porcelain -- assets/ | wc -l)
[ "$DIRTY" = "0" ] || { echo "GENTHEMESGATE_FAILED assets ($DIRTY asset files touched — the week ships zero binary assets)"; exit 1; }
N_ASSETS=$(git ls-files assets/ | wc -l)

echo "GENTHEMESGATE_OK checks=$CHECKS hashes=5 seeds=300 styles=4 assets=CLEAN($N_ASSETS)"
exit 0
