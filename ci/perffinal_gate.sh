#!/bin/bash
# Perf final gate (roadmap v2, W78) — ci/perffinal_gate.sh
#
#   W78's deliverable is the final stability certification, measured not
#   claimed:
#     • the W55 performance floors STILL HOLD — the engines did not slow
#       past their documented budgets across W56..W77 (perfbaseline reruns)
#     • the global measured numbers (tests total, dex/ceiling, identity)
#       are read LIVE from build/release/gates.env and ci/phase3_gate.sh
#     • STABILITY.md is the ONE record and it is checked against the LIVE
#       numbers — any drift between the doc and the measured reality
#       refuses (the anti-rewrite discipline, applied to the whole ledger)
#     • the verdict carries the HEAD: a stability certificate on a moving
#       head is worthless
#
#   Verdict: PERFFINALGATE_OK perf=W55-green tests=N dex=X/Y head=<sha12> (exit 0)
#            / PERFFINALGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

# ---- 1) the W55 floors still hold -------------------------------------------
bash ci/perfbaseline_gate.sh > /tmp/perffinal-base.log 2>&1 || {
  echo "PERFFINALGATE_FAILED the W55 perf floor no longer holds ($(tail -1 /tmp/perffinal-base.log))"; exit 1; }
BASE=$(grep -oE 'PERFBASELINE_OK[^|]*' /tmp/perffinal-base.log | head -1)
echo "perf floors: $BASE"

# ---- 2) the live global numbers ----------------------------------------------
TESTS=$(grep -oE 'TESTS_TOTAL=[0-9]+' build/release/gates.env 2>/dev/null | head -1 | cut -d= -f2)
[ -z "$TESTS" ] && { echo "PERFFINALGATE_FAILED no measured TESTS_TOTAL (run the pipeline first)"; exit 1; }
DEX=$(grep -oE 'R8_DEX_BYTES=[0-9]+' build/release/gates.env 2>/dev/null | head -1 | cut -d= -f2)
[ -z "$DEX" ] && { echo "PERFFINALGATE_FAILED no measured R8_DEX_BYTES"; exit 1; }
CEIL=$(grep -oE 'DEX_CEILING=[0-9]+' ci/phase3_gate.sh | head -1 | cut -d= -f2)
[ -z "$CEIL" ] && { echo "PERFFINALGATE_FAILED no ceiling in phase3"; exit 1; }
[ "$DEX" -le "$CEIL" ] || { echo "PERFFINALGATE_FAILED dex $DEX over the ceiling $CEIL"; exit 1; }

# ---- 3) STABILITY.md matches the measured reality ----------------------------
[ -f STABILITY.md ] || { echo "PERFFINALGATE_FAILED STABILITY.md missing (the one record)"; exit 1; }
grep -q "tests: $TESTS" STABILITY.md || { echo "PERFFINALGATE_FAILED STABILITY.md tests drift (doc vs measured $TESTS)"; exit 1; }
grep -q "dex: $DEX/$CEIL" STABILITY.md || { echo "PERFFINALGATE_FAILED STABILITY.md dex drift (doc vs measured $DEX/$CEIL)"; exit 1; }
HEAD=$(git rev-parse HEAD)
CERT=$(grep -oE 'head: [0-9a-f]{12}' STABILITY.md | head -1 | cut -d' ' -f2)
[ -n "$CERT" ] || { echo "PERFFINALGATE_FAILED STABILITY.md carries no certified head"; exit 1; }
git merge-base --is-ancestor "$CERT" "$HEAD" || { echo "PERFFINALGATE_FAILED the certified head $CERT is not an ancestor of $HEAD — the certificate anchored a foreign line"; exit 1; }

echo "PERFFINALGATE_OK perf=W55-green tests=$TESTS dex=$DEX/$CEIL head=${HEAD:0:12}"
