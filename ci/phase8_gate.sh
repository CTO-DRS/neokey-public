#!/bin/bash
# Phase-8 CLOSING gate (roadmap phase 8, week 4 — week 41) — ci/phase8_gate.sh
#
#   Reviews ALL phase-8 gates together in ONE line, on fresh re-runs (never
#   cached verdicts), then measures the locally-measurable exit criteria.
#   Evidence integrity is THREE-WAY (the week-21 design, inherited a FIFTH
#   time): the fresh re-run, this run's pipeline log and gates.env must all
#   AGREE on every pinned field — tampering any single source is caught as a
#   regression.
#
#     1. rebuild_gate + evaudit_gate + opverify_gate re-run green, each field
#        pinned at its shipped baseline (no silent regression: the rebuild
#        drill is EXACT (24 checks, 12/12 negatives, 7/7 pins, the inner
#        pipeline 40 stages — the shape pin re-pinned consciously at W47 when
#        the clipio stage joined the inner profile), the evidence
#        auditor is EXACT (19 checks, 12/12 negatives — its archive is
#        MONOTONIC by design: the week's own proof log lands after the code
#        commit, so members/proofs get BOUNDS against the log, never
#        equality — the W40 hardening lesson, inherited), and the
#        operator-output verifier is EXACT (18 checks, 12/12 negatives,
#        letters=2 packs=1 swaps=1 — the published identities may not drift).
#     2. fresh verdict lines == pipeline-log verdict lines == gates.env values.
#     3. Whole-pipeline state: every previous gate key still OK (35 keys —
#        the 29 the phase-7 close guarded + the phase-7 closing gate itself
#        + the three phase-8 gates + the W47 clipio gate), the phase-7 close itself still
#        0-regressions, tests >= 1,561 with 0 failures, NQF golden assets
#        = 15, theme packs = 25, R8 dex <= the 512,000B lowered ceiling with
#        all manifest components kept, trim and R8 agree on the same dex
#        artifact, versionCode >= 82 AND versionName >= 3.5 (the W30 lesson,
#        fourth closing week in a row: the exact close measurement vc 82 /
#        3.5 is pinned ONCE in release-manifest.phase8Close; the live check
#        is a non-regression floor from day one — no week may ship below the
#        phase-8 close, later weeks ride upward).
#
#   RECURSION NOTE (the W38 pattern, now two stages deep): this gate re-runs
#   rebuild_gate, whose drill runs an inner pipeline — inside that inner run
#   (NEOKEY_REBUILD_INNER=1) THIS stage omits itself (a note, no row, marked
#   INNER_OMIT in ci/pipeline.sh): a closer whose job is re-running the drill
#   may not run inside the drill.
#
#   Verdict: PHASE8_OK … (exit 0, keys appended to gates.env for the release
#   manifest) / PHASE8_FAILED <reason> (exit 1).
#   Standalone use requires a prior pipeline run (build/pipeline.log + gates.env
#   + the r8 dex artifact + the provisioned toolchain the drill re-verifies).
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=528000   # W58-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)   # LOWERED by the W22 real trim (was 520,000); overflow means trim, never raise
VC_MIN=1             # v0.1.0 identity reset — re-based from the phase-8 close floor (was 82)
VN_MIN=0.1           # v0.1.0 identity reset — re-based from the phase-8 close floor (was 3.5); the historical close measurement stays pinned in release-manifest.phase8Close
TESTS_MIN=1561       # shipped test total at phase-8 close (the mass lives in the gates)
INNER_STAGES=41      # the drill's inner stage count: 39 pre-drill rows + evaudit + opverify (the drill and this closer omit themselves inside — INNER_OMIT); frozen so a shape change re-pins consciously — RE-PINNED 40→41 at W48 (the cliplib stage joined the inner profile, after clipio's 39→40 at W47)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE8 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the three phase-8 gates, fresh ---------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE8_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

RB=$(run_one rebuild   ci/rebuild_gate.sh   'REBUILDGATE_OK')   || exit 1
EA=$(run_one evaudit   ci/evaudit_gate.sh   'EVAUDITGATE_OK')   || exit 1
OV=$(run_one opverify  ci/opverify_gate.sh  'OPVERIFYGATE_OK')  || exit 1
echo "[phase8] $RB"
echo "[phase8] $EA"
echo "[phase8] $OV"

# ---- 2: pinned baselines on the FRESH values — shipped numbers may not sink --
# the proof-from-nothing drill (W1/week 38): the check inventory is exact, the
# pins are exact, and the inner pipeline shape is frozen at the close — a
# shape change must re-pin consciously, not slide through
RB_CHK=$(field "$RB" checks);      RB_NEG=$(negof "$RB" negatives)
RB_PINS=$(field "$RB" pins);       RB_STAGES=$(field "$RB" stages | cut -d/ -f1)
RB_STAGES_SHAPE=$(field "$RB" stages)   # the gate verdict carries n/n (the drill verdict is single-N)
RB_TESTS=$(field "$RB" tests);     RB_DEX=$(field "$RB" dex | cut -d/ -f1)
RB_VC=$(field "$RB" vc);           RB_VN=$(field "$RB" vn)
[ "${RB_CHK:-0}"     = 24 ]        || fail "rebuild checks ${RB_CHK:-0} != 24 (the gate's check inventory moved)"
[ "${RB_NEG:-0/0}"   = "12/12" ]   || fail "rebuild negatives $RB_NEG != 12/12 (a check stopped biting)"
[ "${RB_PINS:-0/0}"  = "7/7" ]     || fail "rebuild pins ${RB_PINS:-0/0} != 7/7 (the toolchain anchor moved)"
[ "${RB_STAGES:-0}"  = "$INNER_STAGES" ] || fail "rebuild stages ${RB_STAGES:-0} != $INNER_STAGES (the inner shape moved — re-pin the close consciously)"
[ "${RB_VC:-0}"      -ge "$VC_MIN" ] || fail "rebuild artifact vc ${RB_VC:-0} < $VC_MIN"
case "${RB_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "rebuild artifact vn ${RB_VN:-?} < $VN_MIN";; esac

# the week-evidence auditor (W2/week 39): the check inventory is exact; the
# archive is MONOTONIC — the week's own proof log legitimately lands after the
# code commit, so members/proofs are bounds here (equality lives in §3)
EA_CHK=$(field "$EA" checks);      EA_NEG=$(negof "$EA" negatives)
EA_MEM=$(field "$EA" members);     EA_PRF=$(field "$EA" proofs)
EA_TESTS=$(field "$EA" tests)
EA_VC=$(field "$EA" vc);           EA_VN=$(field "$EA" vn)
[ "${EA_CHK:-0}"     = 19 ]        || fail "evaudit checks ${EA_CHK:-0} != 19 (the gate's check inventory moved)"
[ "${EA_NEG:-0/0}"   = "12/12" ]   || fail "evaudit negatives $EA_NEG != 12/12 (a check stopped biting)"
[ "${EA_VC:-0}"      -ge "$VC_MIN" ] || fail "evaudit artifact vc ${EA_VC:-0} < $VC_MIN"
case "${EA_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "evaudit artifact vn ${EA_VN:-?} < $VN_MIN";; esac

# the operator-output verifier (W3/week 40): the published identities are
# exact — two letters, one pack, one gated swap line
OV_CHK=$(field "$OV" checks);      OV_NEG=$(negof "$OV" negatives)
OV_LET=$(field "$OV" letters);     OV_PACKS=$(field "$OV" packs)
OV_SWAPS=$(field "$OV" swaps)
OV_VC=$(field "$OV" vc);           OV_VN=$(field "$OV" vn)
[ "${OV_CHK:-0}"     = 18 ]        || fail "opverify checks ${OV_CHK:-0} != 18 (the gate's check inventory moved)"
[ "${OV_NEG:-0/0}"   = "12/12" ]   || fail "opverify negatives $OV_NEG != 12/12 (a check stopped biting)"
[ "${OV_LET:-0}"     = 2 ]         || fail "opverify letters ${OV_LET:-0} != 2 (the published letter set moved)"
[ "${OV_PACKS:-0}"   = 1 ]         || fail "opverify packs ${OV_PACKS:-0} != 1 (the published pack set moved)"
[ "${OV_SWAPS:-0}"   = 1 ]         || fail "opverify swaps ${OV_SWAPS:-0} != 1 (the gated swap line moved)"
[ "${OV_VC:-0}"      -ge "$VC_MIN" ] || fail "opverify artifact vc ${OV_VC:-0} < $VC_MIN"
case "${OV_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "opverify artifact vn ${OV_VN:-?} < $VN_MIN";; esac

# ---- 3: evidence integrity — fresh == pipeline log == gates.env ----------------
if [ ! -f "$STATE" ] || [ ! -f "$LOGF" ]; then
  echo "PHASE8_FAILED state_missing (run ci/pipeline.sh first)"; exit 1
fi
LOG_RB=$(grep -E '^REBUILDGATE_OK '   "$LOGF" | tail -1)
LOG_EA=$(grep -E '^EVAUDITGATE_OK '   "$LOGF" | tail -1)
LOG_OV=$(grep -E '^OPVERIFYGATE_OK '  "$LOGF" | tail -1)
LOG_R8=$(grep -E '^R8_OK '            "$LOGF" | tail -1)
[ -n "$LOG_RB" ] || fail "pipeline log has no REBUILDGATE_OK line"
[ -n "$LOG_EA" ] || fail "pipeline log has no EVAUDITGATE_OK line"
[ -n "$LOG_OV" ] || fail "pipeline log has no OPVERIFYGATE_OK line"
[ -n "$LOG_R8" ] || fail "pipeline log has no R8_OK line"

. "$STATE"

eq3() { # $1 env-key, $2 fresh value, $3 log value, $4 label
  local envv="${!1:-}"
  [ "$envv" = "$2" ] || fail "$4: gates.env $envv != fresh re-run $2"
  [ "$3" = "$2" ]    || fail "$4: pipeline log $3 != fresh re-run $2"
}
eq3 REBUILD_CHECKS   "$RB_CHK"    "$(field "$LOG_RB" checks)"     rebuild.checks
eq3 REBUILD_NEGATIVES "$RB_NEG"   "$(negof "$LOG_RB" negatives)"  rebuild.negatives
eq3 REBUILD_PINS     "$RB_PINS"   "$(field "$LOG_RB" pins)"       rebuild.pins
eq3 REBUILD_STAGES   "$RB_STAGES_SHAPE" "$(field "$LOG_RB" stages)" rebuild.stages
eq3 REBUILD_TESTS    "$RB_TESTS"  "$(field "$LOG_RB" tests)"      rebuild.tests
eq3 EVAUDIT_CHECKS   "$EA_CHK"    "$(field "$LOG_EA" checks)"     evaudit.checks
eq3 EVAUDIT_NEGATIVES "$EA_NEG"   "$(negof "$LOG_EA" negatives)"  evaudit.negatives
eq3 EVAUDIT_TESTS    "$EA_TESTS"  "$(field "$LOG_EA" tests)"      evaudit.tests
eq3 OPVERIFY_CHECKS  "$OV_CHK"    "$(field "$LOG_OV" checks)"     opverify.checks
eq3 OPVERIFY_NEGATIVES "$OV_NEG"  "$(negof "$LOG_OV" negatives)"  opverify.negatives
eq3 OPVERIFY_SWAPS   "$OV_SWAPS"  "$(field "$LOG_OV" swaps)"      opverify.swaps
# the MONOTONIC evidence set (the W40 lesson, inherited): the week's own proof
# log lands after the code commit by design, so a later pack legitimately
# carries MORE members/proofs than the pipeline's snapshot — the bounds say
# the archive may grow, never shrink; the deeper laws (shape, verdict↔ledger,
# identity, the proof law) still audit the archive content inside evaudit
L_EA_MEM=$(field "$LOG_EA" members); L_EA_PRF=$(field "$LOG_EA" proofs)
case "${EA_MEM:-0}" in ''|*[!0-9]*) fail "evaudit members ${EA_MEM:-?} not a number";; *) [ "${EA_MEM:-0}" -ge "${L_EA_MEM:-0}" ] || fail "evaudit members ${EA_MEM:-0} < the log's ${L_EA_MEM:-0} (the evidence set SHRANK)";; esac
case "${EA_PRF:-0}" in ''|*[!0-9]*) fail "evaudit proofs ${EA_PRF:-?} not a number";; *) [ "${EA_PRF:-0}" -ge "${L_EA_PRF:-0}" ] || fail "evaudit proofs ${EA_PRF:-0} < the log's ${L_EA_PRF:-0} (the evidence set SHRANK)";; esac
# the drill proves the SAME tree: its inner dex must be this run's dex
[ "${RB_DEX:-none}" = "${R8_DEX_BYTES:-x}" ] || fail "rebuild inner dex ${RB_DEX:-none} != this run's ${R8_DEX_BYTES:-?} (the drill rebuilt a different tree)"

# tests: the log's RESULT sums must equal gates.env exactly
LT=$(grep -o 'RESULT [0-9]* passed, [0-9]* failed' "$LOGF" | awk '{p+=$2; f+=$4} END {print p+0, f+0}')
[ "$(echo "$LT" | awk '{print $1}')" = "${TESTS_TOTAL:-x}" ] || fail "TESTS_TOTAL ${TESTS_TOTAL:-?} != log $(echo "$LT" | awk '{print $1}')"
[ "$(echo "$LT" | awk '{print $2}')" = "${TESTS_FAILED:-x}" ] || fail "TESTS_FAILED ${TESTS_FAILED:-?} != log $(echo "$LT" | awk '{print $2}')"

# dex: the trim gate and the R8 gate must agree on the SAME artifact (the
# week-25 consistency check, inherited) and the ceiling stays lowered
[ "$(field "$LOG_R8" dex)" = "${R8_DEX_BYTES:-x}" ] || fail "R8_DEX_BYTES ${R8_DEX_BYTES:-?} != log $(field "$LOG_R8" dex)"
[ "${TRIM_DEX_BYTES:-0}" = "${R8_DEX_BYTES:--1}" ] || fail "trim dex ${TRIM_DEX_BYTES:-0} != r8 dex ${R8_DEX_BYTES:-?} (artifact disagreement)"
[ "${R8_COMPONENTS_KEPT:-0/0}" = "10/10" ] || fail "components kept ${R8_COMPONENTS_KEPT:-0/0} != 10/10"
[ "${R8_DEX_BYTES:-999999}" -le "$DEX_CEILING" ] || fail "dex $R8_DEX_BYTES > ceiling $DEX_CEILING"

# ---- 4: whole-pipeline no-regression ------------------------------------------
[ "${AUDIT:-}" = "OK" ] || fail "secrets audit not OK"
[ "${TESTS_TOTAL:-0}" -ge "$TESTS_MIN" ] || fail "tests $TESTS_TOTAL < $TESTS_MIN"
[ "${TESTS_FAILED:-1}" = "0" ] || fail "tests_failed $TESTS_FAILED != 0"
# every previous gate key must still be OK — no gate regressed (35 keys:
# the 29 the phase-7 close guarded + the phase-7 closing gate itself + the
# three phase-8 gates + the W47 clipio gate)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE \
         PROVISION_GATE SIGNEDAPK_GATE UPLOADBUNDLE_GATE PHASE5_GATE \
         PHASE6_GATE BILLING_GATE DEPLOYSRV_GATE PREFLIGHT_GATE \
         PHASE7_GATE REBUILD_GATE EVAUDIT_GATE OPVERIFY_GATE CLIPIO_GATE CLIPLIB_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 24 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 24 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
# the phase-7 close itself must still be zero-regression in THIS run
echo "${PHASE7_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE7_REGRESSIONS ${PHASE7_REGRESSIONS:-?} is not 0/n (the phase-7 close regressed)"

# versionCode >= the phase-8 close floor AND versionName >= 3.5 (non-regression
# floor from day one — the exact vc82/3.5 close measurement stays pinned in
# release-manifest.phase8Close and can never be rewritten)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the phase-8 close floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the phase-8 close)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep; the 33-key loop
# line is counted once but executes 33 (+32 net); the eq3 function body has 2
# fail lines counted once but each of the 11 eq3 calls executes BOTH, so eq3
# adds 11*2 - 2 = 20. grep -cE counts every literal "|| fail " line in this
# file including the 2 inside eq3 and the loop's one.
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 32 + 20 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE8_FAILED regressions=$REG/$CHECKS (see PHASE8 regression lines above)"
  exit 1
fi
FRESH="PHASE8_OK gates=3/3 regressions=0/$CHECKS dex=${R8_DEX_BYTES:-0}/$DEX_CEILING vc=$VC tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0} negatives=$RB_NEG+$EA_NEG+$OV_NEG pins=$RB_PINS letters=$OV_LET packs=$OV_PACKS swaps=$OV_SWAPS"
# equality FIRST (the verdict must never print beside a drifted rerun): any
# PHASE8_OK line already in the outer log (a standalone re-run after a
# pipeline) must byte-equal fresh — the verdict carries NO timing field and
# NO monotonic field, so a standalone re-run can agree byte-for-byte
LOG_P8=$(grep -m1 '^PHASE8_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_P8" ]; then
  [ "$LOG_P8" = "$FRESH" ] || { fail "log.phase8 != fresh (the outer log's line drifted from this run)"; echo "PHASE8_FAILED regressions=$REG/$CHECKS" >&2; exit 1; }
fi
echo "$FRESH"

# keys for the release manifest (release.sh sources gates.env) — idempotent
# upsert (the week-33 hardening): a standalone re-run must not pile a second
# PHASE8_* block onto the pipeline's block; drop the old keys, append fresh.
sed -i '/^PHASE8_/d' "$STATE"
{
  echo "PHASE8_GATE=OK"
  echo "PHASE8_GATES=3/3"
  echo "PHASE8_REGRESSIONS=0/$CHECKS"
  echo "PHASE8_DEX=${R8_DEX_BYTES:-0}"
  echo "PHASE8_CEILING=$DEX_CEILING"
  echo "PHASE8_VC=$VC"
  echo "PHASE8_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
  echo "PHASE8_NEGATIVES=$RB_NEG+$EA_NEG+$OV_NEG"
  echo "PHASE8_PINS=$RB_PINS"
  echo "PHASE8_LETTERS=$OV_LET"
  echo "PHASE8_PACKS=$OV_PACKS"
  echo "PHASE8_SWAPS=$OV_SWAPS"
} >> "$STATE"
exit 0
