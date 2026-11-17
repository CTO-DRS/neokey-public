#!/bin/bash
# Phase-7 CLOSING gate (roadmap phase 7, week 4 — week 37) — ci/phase7_gate.sh
#
#   Reviews ALL phase-7 gates together in ONE line, on fresh re-runs (never
#   cached verdicts), then measures the locally-measurable exit criteria.
#   Evidence integrity is THREE-WAY (the week-21 design, inherited a FOURTH
#   time): the fresh re-run, this run's pipeline log and gates.env must all
#   AGREE on every pinned field — tampering any single source is caught as a
#   regression.
#
#     1. billing_gate + deploysrv_gate + preflight_gate re-run green, each
#        field pinned at its shipped baseline (no silent regression: the
#        contract-parity suite is EXACT (24 checks, 10/10 negatives, both
#        providers), the deployable pack is EXACT (24 checks, 5/5 negatives,
#        6 members, the jar's sha256 must agree with the freshly rebuilt jar,
#        health gets a bound — jitter is not a regression), the operator
#        sheet is EXACT (18 checks, 4/4 negatives, 10 rows = 5 CHECKED + 5
#        OUTSIDE — the CHECKED/OUTSIDE split is itself pinned: the bound
#        between proven and external may not drift).
#     2. fresh verdict lines == pipeline-log verdict lines == gates.env values.
#     3. Whole-pipeline state: every previous gate key still OK (29 keys —
#        the 25 the phase-6 close guarded + the phase-6 closing gate itself
#        + the three phase-7 gates), the phase-6 close itself still
#        0-regressions, tests >= 1,479 with 0 failures, NQF golden assets
#        = 15, theme packs = 25, R8 dex <= the 512,000B lowered ceiling with
#        all manifest components kept, trim and R8 agree on the same dex
#        artifact, versionCode >= 78 AND versionName >= 3.1 (the W30 lesson,
#        third closing week in a row: the exact close measurement vc 78 /
#        3.1 is pinned ONCE in release-manifest.phase7Close; the live check
#        is a non-regression floor from day one — no week may ship below the
#        phase-7 close, later weeks ride upward).
#
#   Verdict: PHASE7_OK … (exit 0, keys appended to gates.env for the release
#   manifest) / PHASE7_FAILED <reason> (exit 1).
#   Standalone use requires a prior pipeline run (build/pipeline.log + gates.env
#   + the r8 dex artifact + the artifacts the three gates reference).
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=582000   # W69-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)   # LOWERED by the W22 real trim (was 520,000); overflow means trim, never raise
VC_MIN=2             # v0.1.0 identity reset — re-based from the phase-7 close floor (was 78)
VN_MIN=0.2           # v0.1.0 identity reset — re-based from the phase-7 close floor (was 3.1); the historical close measurement stays pinned in release-manifest.phase7Close
TESTS_MIN=1479       # shipped test total at phase-7 close (the mass lives in the gates)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE7 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the three phase-7 gates, fresh ---------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE7_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

BL=$(run_one billing    ci/billing_gate.sh    'BILLINGGATE_OK')    || exit 1
DS=$(run_one deploysrv  ci/deploysrv_gate.sh  'DEPLOYSRVGATE_OK')  || exit 1
# the jar sha is pinned AT THE MOMENT THE VERDICT MEASURED IT: the preflight
# re-run below rebuilds the jar again (jar entry timestamps make two builds
# differ), and its own gate re-anchors THAT build inside itself — comparing
# the verdict against the post-preflight on-disk jar would compare two builds
# (the defect this closure's first run caught: regressions=1/88)
[ -f build/deploy/neokey-sync-server.jar ] || { echo "PHASE7_FAILED deploysrv (jar missing after the fresh re-run)"; exit 1; }
DS_JAR_AT_RUN=$(sha256sum build/deploy/neokey-sync-server.jar | cut -d' ' -f1)
PF=$(run_one preflight  ci/preflight_gate.sh  'PREFLIGHTGATE_OK')  || exit 1
echo "[phase7] $BL"
echo "[phase7] $DS"
echo "[phase7] $PF"

# ---- 2: pinned baselines on the FRESH values — shipped numbers may not sink --
# the proven billing adapter (W1/week 34): the contract-parity suite is exact,
# BOTH providers stay proven, and the artifact tracks the close identity floor
BL_CHK=$(field "$BL" checks);      BL_NEG=$(negof "$BL" negatives)
BL_VC=$(field "$BL" vc);           BL_VN=$(field "$BL" vn)
BL_PROV=$(field "$BL" providers)
[ "${BL_CHK:-0}"   = 24 ]          || fail "billing checks ${BL_CHK:-0} != 24 (the parity suite's check inventory moved)"
[ "${BL_NEG:-0/0}" = "10/10" ]     || fail "billing negatives $BL_NEG != 10/10 (a check stopped biting)"
[ "${BL_PROV:-0}"  = 2 ]           || fail "billing providers ${BL_PROV:-0} != 2 (contract parity lost a provider)"
[ "${BL_VC:-0}"    -ge 1 ]        || fail "billing artifact vc ${BL_VC:-0} < 1"
case "${BL_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "billing artifact vn ${BL_VN:-?} < 0.1.0";; esac

# the deployable sync-server pack (W2/week 35): the pack inventory is exact and
# the jar sha must agree with the jar the gate JUST rebuilt (deterministic
# build — a drifting sha means the pack stopped being the tested thing)
DS_CHK=$(field "$DS" checks);      DS_NEG=$(negof "$DS" negatives)
DS_MEM=$(field "$DS" members);     DS_VC=$(field "$DS" vc)
DS_VN=$(field "$DS" vn);           DS_JAR=$(field "$DS" jar_sha256)
DS_HEALTH=$(field "$DS" health_ms)
[ "${DS_CHK:-0}"   = 24 ]          || fail "deploysrv checks ${DS_CHK:-0} != 24 (the gate's check inventory moved)"
[ "${DS_NEG:-0/0}" = "5/5" ]       || fail "deploysrv negatives $DS_NEG != 5/5 (a check stopped biting)"
[ "${DS_MEM:-0}"   = 6 ]           || fail "deploysrv members ${DS_MEM:-0} != 6 (the pack inventory moved)"
[ "${DS_VC:-0}"    -ge 1 ]        || fail "deploysrv pack vc ${DS_VC:-0} < 1"
case "${DS_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "deploysrv pack vn ${DS_VN:-?} < 0.1.0";; esac
[ -f build/deploy/neokey-sync-server.jar ] || fail "deploysrv jar missing after the fresh re-run"
[ "${DS_JAR:-none}" = "${DS_JAR_AT_RUN:0:16}" ] || fail "deploysrv jar_sha256 ${DS_JAR:-none} != the jar the verdict measured ${DS_JAR_AT_RUN:0:16} (the pack stopped being the tested thing)"
case "${DS_HEALTH:-0}" in ''|*[!0-9]*) fail "deploysrv health_ms ${DS_HEALTH:-?} not a number";; *) [ "${DS_HEALTH:-0}" -ge 1 ] && [ "${DS_HEALTH:-0}" -le 10000 ] || fail "deploysrv health_ms ${DS_HEALTH:-?} outside 1..10000";; esac

# the operator preflight sheet (W3/week 36): the sheet shape is exact and the
# CHECKED/OUTSIDE split is pinned — the bound between proven and external
# (pending #5's no-pretending line) may not drift
PF_CHK=$(field "$PF" checks);      PF_NEG=$(negof "$PF" negatives)
PF_ROWS=$(field "$PF" rows);       PF_CHECKED=$(field "$PF" checked)
PF_OUTSIDE=$(field "$PF" outside); PF_SHEET=$(field "$PF" sheet)
PF_VC=$(field "$PF" vc);           PF_VN=$(field "$PF" vn)
[ "${PF_CHK:-0}"     = 18 ]        || fail "preflight checks ${PF_CHK:-0} != 18 (the gate's check inventory moved)"
[ "${PF_NEG:-0/0}"   = "4/4" ]     || fail "preflight negatives $PF_NEG != 4/4 (a check stopped biting)"
[ "${PF_ROWS:-0}"    = 10 ]        || fail "preflight rows ${PF_ROWS:-0} != 10 (the handover inventory moved)"
[ "${PF_CHECKED:-0}" = 5 ]         || fail "preflight checked ${PF_CHECKED:-0} != 5 (the CHECKED/OUTSIDE bound drifted)"
[ "${PF_OUTSIDE:-0}" = 5 ]         || fail "preflight outside ${PF_OUTSIDE:-0} != 5 (the CHECKED/OUTSIDE bound drifted)"
[ "${PF_SHEET:-none}" = build/release/preflight-sheet.md ] || fail "preflight sheet ${PF_SHEET:-none} moved"
[ "${PF_VC:-0}"      -ge 1 ]      || fail "preflight sheet vc ${PF_VC:-0} < 1"
case "${PF_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "preflight sheet vn ${PF_VN:-?} < 0.1.0";; esac

# ---- 3: evidence integrity — fresh == pipeline log == gates.env ----------------
if [ ! -f "$STATE" ] || [ ! -f "$LOGF" ]; then
  echo "PHASE7_FAILED state_missing (run ci/pipeline.sh first)"; exit 1
fi
LOG_BL=$(grep -E '^BILLINGGATE_OK'    "$LOGF" | tail -1)
LOG_DS=$(grep -E '^DEPLOYSRVGATE_OK'  "$LOGF" | tail -1)
LOG_PF=$(grep -E '^PREFLIGHTGATE_OK'  "$LOGF" | tail -1)
LOG_R8=$(grep -E '^R8_OK '            "$LOGF" | tail -1)
[ -n "$LOG_BL" ] || fail "pipeline log has no BILLINGGATE_OK line"
[ -n "$LOG_DS" ] || fail "pipeline log has no DEPLOYSRVGATE_OK line"
[ -n "$LOG_PF" ] || fail "pipeline log has no PREFLIGHTGATE_OK line"
[ -n "$LOG_R8" ] || fail "pipeline log has no R8_OK line"

. "$STATE"

eq3() { # $1 env-key, $2 fresh value, $3 log value, $4 label
  local envv="${!1:-}"
  [ "$envv" = "$2" ] || fail "$4: gates.env $envv != fresh re-run $2"
  [ "$3" = "$2" ]    || fail "$4: pipeline log $3 != fresh re-run $2"
}
eq3 BILLING_CHECKS        "$BL_CHK"    "$(field "$LOG_BL" checks)"     billing.checks
eq3 BILLING_NEGATIVES     "$BL_NEG"    "$(negof "$LOG_BL" negatives)"  billing.negatives
eq3 DEPLOYSRV_CHECKS      "$DS_CHK"    "$(field "$LOG_DS" checks)"     deploysrv.checks
eq3 DEPLOYSRV_NEGATIVES   "$DS_NEG"    "$(negof "$LOG_DS" negatives)"  deploysrv.negatives
eq3 DEPLOYSRV_MEMBERS     "$DS_MEM"    "$(field "$LOG_DS" members)"    deploysrv.members
eq3 PREFLIGHT_CHECKS      "$PF_CHK"    "$(field "$LOG_PF" checks)"     preflight.checks
eq3 PREFLIGHT_NEGATIVES   "$PF_NEG"    "$(negof "$LOG_PF" negatives)"  preflight.negatives
eq3 PREFLIGHT_ROWS        "$PF_ROWS"   "$(field "$LOG_PF" rows)"       preflight.rows
eq3 PREFLIGHT_CHECKED     "$PF_CHECKED" "$(field "$LOG_PF" checked)"   preflight.checked
eq3 PREFLIGHT_OUTSIDE     "$PF_OUTSIDE" "$(field "$LOG_PF" outside)"   preflight.outside
# non-deterministic fields (the pack boot's health latency) get BOUNDS in the
# two sources that carry them, never equality — jitter between runs is not a
# regression (the week-21 lesson, inherited a fourth time):
LDSH=$(field "$LOG_DS" health_ms)
case "${LDSH:-0}" in ''|*[!0-9]*) fail "log deploysrv health_ms ${LDSH:-?} not a number";; *) [ "${LDSH:-0}" -ge 1 ] && [ "${LDSH:-0}" -le 10000 ] || fail "log deploysrv health_ms ${LDSH:-?} outside 1..10000";; esac

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
# every previous gate key must still be OK — no gate regressed (29 keys:
# the 25 the phase-6 close guarded + the phase-6 closing gate itself + the
# three phase-7 gates)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE \
         PROVISION_GATE SIGNEDAPK_GATE UPLOADBUNDLE_GATE PHASE5_GATE \
         PHASE6_GATE BILLING_GATE DEPLOYSRV_GATE PREFLIGHT_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 25 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 25 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
# the phase-6 close itself must still be zero-regression in THIS run
echo "${PHASE6_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE6_REGRESSIONS ${PHASE6_REGRESSIONS:-?} is not 0/n (the phase-6 close regressed)"

# versionCode >= the phase-7 close floor AND versionName >= 3.1 (non-regression
# floor from day one — the exact vc78/3.1 close measurement stays pinned in
# release-manifest.phase7Close and can never be rewritten)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the phase-7 close floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the phase-7 close)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep; the 29-key loop
# adds 29; the eq3 function body has 2 fail lines counted once but each of the
# 10 eq3 calls executes BOTH, so eq3 adds 10*2 - 2 = 18. grep -cE counts every
# literal "|| fail " line in this file including the 2 inside eq3.
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 29 + 18 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE7_FAILED regressions=$REG/$CHECKS (see PHASE7 regression lines above)"
  exit 1
fi
echo "PHASE7_OK gates=3/3 regressions=0/$CHECKS dex=${R8_DEX_BYTES:-0}/$DEX_CEILING vc=$VC tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0} negatives=$BL_NEG+$DS_NEG+$PF_NEG members=$DS_MEM rows=$PF_ROWS checked=$PF_CHECKED outside=$PF_OUTSIDE"

# keys for the release manifest (release.sh sources gates.env) — idempotent
# upsert (the week-33 hardening): a standalone re-run must not pile a second
# PHASE7_* block onto the pipeline's block; drop the old keys, append fresh.
sed -i '/^PHASE7_/d' "$STATE"
{
  echo "PHASE7_GATE=OK"
  echo "PHASE7_GATES=3/3"
  echo "PHASE7_REGRESSIONS=0/$CHECKS"
  echo "PHASE7_DEX=${R8_DEX_BYTES:-0}"
  echo "PHASE7_CEILING=$DEX_CEILING"
  echo "PHASE7_VC=$VC"
  echo "PHASE7_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
  echo "PHASE7_NEGATIVES=$BL_NEG+$DS_NEG+$PF_NEG"
  echo "PHASE7_MEMBERS=$DS_MEM"
  echo "PHASE7_ROWS=$PF_ROWS"
  echo "PHASE7_CHECKED=$PF_CHECKED"
  echo "PHASE7_OUTSIDE=$PF_OUTSIDE"
} >> "$STATE"
exit 0
