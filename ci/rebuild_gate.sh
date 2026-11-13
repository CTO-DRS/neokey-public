#!/bin/bash
# The REBUILD GATE — phase 8 W1 (week 38) — ci/rebuild_gate.sh
#
#   Pipeline stage 38, LAST (after the phase-7 closure): the capability the
#   phase-7 close PRESUMED — a virgin environment re-running the whole proof
#   green — becomes a measured, gate-guarded fact. docs/PHASE8-PLAN.md
#   documents the derivation and the live lesson (this week's environment
#   recycled to zero; the recovery was manual, uncoded, unverified).
#
#     1. THE ANCHOR IS THE FIRST BARRIER (المرجع = المنشور): the week's
#        published identity (79 / 3.2) is pinned here and checked against
#        the live manifest; the SEVEN toolchain sha256 pins are printed
#        HERE (the single written source) and handed to the drill and to
#        RebuildGate.java — any drift bites before anything runs.
#     2. THE DRILL (ci/rebuild.sh) runs FRESH — no cached verdicts: a clean
#        local clone of HEAD (no build/, no tool jars — all gitignored),
#        the seven verified tools copied in, the FULL INNER PIPELINE
#        (NEOKEY_REBUILD_INNER=1 omits this stage inside: 38 stages since
#        W39 grew the pipeline to 39),
#        required 38/38 PASS with ZERO SKIP.
#     3. RebuildGate.java (the module) re-computes every pin from tools/,
#        re-parses the inner log (38 stages, zero FAIL, zero SKIP,
#        PIPELINE_OK, zero test failures, dex under the lowered ceiling,
#        components 10/10, identity == SyncServerPack.VC/VN — the ONE
#        source), cross-checks the drill verdict against the inner log,
#        and bites TWELVE exact-tag negatives on dirty copies — then the
#        real inputs re-verify green.
#     4. Ledger consistency: the REBUILD_ keys append to gates.env as an
#        IDEMPOTENT upsert (the week-33 hardening, day-one); the fresh
#        values must equal the ledger after the upsert, and any
#        REBUILDGATE_OK line already in the outer pipeline log (a
#        standalone re-run after a pipeline) must byte-equal fresh.
#
#   Verdict: `REBUILDGATE_OK checks=24 negatives=12/12 pins=7/7 stages=39/39
#   tests=T/F dex=D/512000 vc=$PUB_VC vn=$PUB_VN` / `REBUILDGATE_FAILED <reason>`
#   (exit 1). The saved negative proof: ci/rebuild-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "REBUILDGATE_FAILED $1"; exit 1; }
STATE=build/release/gates.env
LOGF=build/pipeline.log

# ---- 0: THE ANCHOR — المرجع = المنشور ----------------------------------------
# the week's published identity + the seven toolchain pins (single written
# source: this file; the drill and RebuildGate.java receive them from here)
PUB_VC=2
PUB_VN=0.2.0
PINS="r8.jar 4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294
android.jar 96ccfdc84d15fad4e22d76cbb8ef38b150a4b56327957067875ca7e18113a424
apksig.jar c4f6fdf2148490296f422a7eb37647ea11d1c3b02f1f2349c33ce257f3c29b1f
sqlite-jdbc.jar 6dc7464e3803648d3ff18a7359bab6adf079fcd8495b18991f6f5edcb8ac6e3b
slf4j-api.jar d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0
ecj.jar f7686c4960cf70c2ebc5c500a73a8cfc04541b730c18f1c5c21329889b137f45
aapt2 da28a8bdafafb91cf917f7635cce6e146db9df74da92fd5420ea293a1ea1de5b"

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the rebuild week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the rebuild week is $PUB_VN)"

mkdir -p build
echo "$PINS" | grep -E '^[a-z0-9.-]+ [0-9a-f]{64}$' > build/rebuild-pins.txt
[ "$(wc -l < build/rebuild-pins.txt)" = "7" ] || fail "pins.manifest_shape (the anchor must print exactly seven pins)"

# ---- 1: THE DRILL — fresh, no cached verdicts ---------------------------------
DOUT=$(ci/rebuild.sh 2>&1); DRC=$?
echo "$DOUT" | tail -4 >&2
DRILL_LINE=$(echo "$DOUT" | grep -m1 '^REBUILD_DRILL_OK ')
[ "$DRC" -eq 0 ] && [ -n "$DRILL_LINE" ] || fail "drill (rc=$DRC)"
[ -f build/rebuild/inner-pipeline.log ] || fail "drill artifacts missing (inner log)"
[ -f build/rebuild/drill-verdict.txt ] || fail "drill artifacts missing (verdict file)"

# ---- 2: the module — RebuildGate.java compiles and bites ----------------------
OUTD=build/rebuild-gate; rm -rf "$OUTD"; mkdir -p "$OUTD"
SRC="tests/com/hark/neokey/RebuildGate.java tests/com/hark/neokey/SyncServerPack.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUTD" $SRC 2>&1
else
  [ -f tools/ecj.jar ] || fail "compile (no javac and no tools/ecj.jar — provision first)"
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUTD" $SRC 2>&1
fi || fail "compile"

GOUT=$(java -cp "$OUTD" com.hark.neokey.RebuildGate \
        --pins build/rebuild-pins.txt --tools tools \
        --inner-log build/rebuild/inner-pipeline.log \
        --drill-verdict build/rebuild/drill-verdict.txt \
        --outer-log build/pipeline.log \
        --expect-stages "$(echo "$DRILL_LINE" | sed -E 's/.* stages=([0-9]+) .*/\1/')" \
        --after "$(awk '/stage_rebuild\(\)|run_stage "rebuild"/{f=1; next} f && match($0, /(run_stage|skip_stage) "[a-z0-9]+"/){s=substr($0, RSTART, RLENGTH); sub(/^[a-z_]+ "/, "", s); sub(/"$/, "", s); print s}' ci/pipeline.sh | grep -v '^rebuild$' | sort -u | paste -sd, -)" 2>&1); GRC=$?
echo "$GOUT" | grep -E 'NEG-OK|REBUILD regression' >&2 || :
GATE_LINE=$(echo "$GOUT" | grep -m1 '^REBUILDGATE_OK ')
if [ "$GRC" -ne 0 ] || [ -z "$GATE_LINE" ]; then
  echo "$GOUT" | tail -6 >&2
  fail "rebuildgate (rc=$GRC)"
fi
echo "[rebuild] $DRILL_LINE"
echo "[rebuild] $GATE_LINE"

# ---- 3: ledger consistency — the idempotent upsert + the two equalities -------
field() { echo "$1" | sed -E "s/.* $2=([^ ]+).*/\1/"; }
CHK=$(field "$GATE_LINE" checks);     NEG=$(echo "$GATE_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
TPF=$(field "$GATE_LINE" tests);      DEX=$(field "$GATE_LINE" dex | cut -d/ -f1)
STAGES=$(field "$GATE_LINE" stages)
mkdir -p build/release; [ -f "$STATE" ] || : > "$STATE"
sed -i '/^REBUILD_/d' "$STATE"
{
  echo "REBUILD_GATE=OK"
  echo "REBUILD_CHECKS=$CHK"
  echo "REBUILD_NEGATIVES=$NEG"
  echo "REBUILD_PINS=7/7"
  echo "REBUILD_STAGES=$STAGES"
  echo "REBUILD_TESTS=$TPF"
  echo "REBUILD_DEX=$DEX"
  echo "REBUILD_CEILING=532000"   # W58-CEILING-RAISE
  echo "REBUILD_VC=$PUB_VC"
  echo "REBUILD_VN=$PUB_VN"
  echo "REBUILD_DRILL_LINE=$DRILL_LINE"
} >> "$STATE"
# equality 1: the ledger must now carry THIS run's fresh values
. "$STATE"
[ "${REBUILD_CHECKS:-}" = "$CHK" ]        || fail "env.rebuild_checks ${REBUILD_CHECKS:-?} != fresh $CHK"
[ "${REBUILD_NEGATIVES:-}" = "$NEG" ]     || fail "env.rebuild_negatives ${REBUILD_NEGATIVES:-?} != fresh $NEG"
[ "${REBUILD_TESTS:-}" = "$TPF" ]         || fail "env.rebuild_tests ${REBUILD_TESTS:-?} != fresh $TPF"
[ "${REBUILD_DEX:-}" = "$DEX" ]           || fail "env.rebuild_dex ${REBUILD_DEX:-?} != fresh $DEX"
[ "${REBUILD_VC:-}" = "$PUB_VC" ]         || fail "env.rebuild_vc ${REBUILD_VC:-?} != $PUB_VC"
# equality 2: any REBUILDGATE_OK line already in the outer log (a standalone
# re-run after a pipeline) must byte-equal fresh — a drifted rerun is caught;
# on the FIRST run (no line yet) the ledger above is the only half needed and
# the phase-8 closer (W4) inherits the full three-way
LOG_LINE=$(grep -m1 '^REBUILDGATE_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_LINE" ]; then
  [ "$LOG_LINE" = "$GATE_LINE" ] || fail "log.rebuild != fresh (the outer log's line drifted from this run)"
fi

echo "$GATE_LINE"
exit 0
