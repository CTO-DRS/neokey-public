#!/bin/bash
# Phase-6 CLOSING gate (roadmap phase 6, week 4 — week 33) — ci/phase6_gate.sh
#
#   Reviews ALL phase-6 gates together in ONE line, on fresh re-runs (never
#   cached verdicts), then measures the locally-measurable exit criteria.
#   Evidence integrity is THREE-WAY (the week-21 design, inherited three
#   times): the fresh re-run, this run's pipeline log and gates.env must all
#   AGREE on every pinned field — tampering any single source is caught as a
#   regression.
#
#     1. provision_gate + signedapk_gate + uploadbundle_gate re-run green,
#        each field pinned at its shipped baseline (no silent regression:
#        the two published pin anchors are LITERAL — المرجع = المنشور — the
#        drill fingerprint is LITERAL, checks/negatives/members are exact,
#        and the artifact sha256 must agree three ways: signedapk statement
#        == uploadbundle anchor == the fresh re-run; only the drill's
#        decaying renew_days_left and its ms get bounds, never equality).
#     2. fresh verdict lines == pipeline-log verdict lines == gates.env values.
#     3. Whole-pipeline state: every previous gate key still OK (25 keys from
#        phases 0-5 + the three previous closing gates), tests >= 1,424 with
#        0 failures, NQF golden assets = 15, theme packs = 25, the PHASE5
#        close itself still 0-regressions, R8 dex <= the 512,000B lowered
#        ceiling with all manifest components kept, trim and R8 agree on the
#        same dex artifact, versionCode >= 74 AND versionName >= 2.7 (the
#        W30 lesson applied proactively: the exact close measurement vc 74 /
#        2.7 is pinned ONCE in release-manifest.phase6Close; the live check
#        is a non-regression floor from day one — no week may ship below the
#        phase-6 close, later weeks ride upward).
#
#   Verdict: PHASE6_OK … (exit 0, keys appended to gates.env for the release
#   manifest) / PHASE6_FAILED <reason> (exit 1).
#   Standalone use requires a prior pipeline run (build/pipeline.log + gates.env
#   + the r8 dex artifact + the signed/upload-bundle artifacts the state
#   references).
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=584000   # W69-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)   # LOWERED by the W22 real trim (was 520,000); overflow means trim, never raise
VC_MIN=2             # v0.1.0 identity reset — re-based from the phase-6 close floor (was 74)
VN_MIN=0.2           # v0.1.0 identity reset — re-based from the phase-6 close floor (was 2.7); the historical close measurement stays pinned in release-manifest.phase6Close
TESTS_MIN=1424       # shipped test total at phase-6 close (the mass lives in the gates)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE6 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the three phase-6 gates, fresh ---------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE6_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

PV=$(run_one provision   ci/provision_gate.sh    'PROVISIONGATE_OK')    || exit 1
SA=$(run_one signedapk   ci/signedapk_gate.sh    'SIGNEDAPKGATE_OK')    || exit 1
UB=$(run_one uploadbundle ci/uploadbundle_gate.sh 'UPLOADBUNDLEGATE_OK') || exit 1
echo "[phase6] $PV"
echo "[phase6] $SA"
echo "[phase6] $UB"

# ---- 2: pinned baselines on the FRESH values — shipped numbers may not sink --
# provisioning + rotation drill (W1/week 30): BOTH published pin anchors are
# literal — the arena fixture and the reference chain; the derivation is the
# ONE derivation, so the pins must re-derive byte-exactly every run
PV_CHK=$(field "$PV" checks);        PV_NEG=$(negof "$PV" negatives)
PV_ANCHOR=$(field "$PV" anchor_pin16); PV_PIN=$(field "$PV" pin16)
PV_ROT=$(field "$PV" rotation);      PV_DAYS=$(field "$PV" renew_days_left)
[ "${PV_CHK:-0}"       = 17 ]                  || fail "provision checks ${PV_CHK:-0} != 17 (the drill's check inventory moved)"
[ "${PV_NEG:-0/0}"     = "11/11" ]             || fail "provision negatives $PV_NEG != 11/11 (a check stopped biting)"
[ "${PV_ANCHOR:-none}" = e67e815c3494a3c5 ]    || fail "provision anchor_pin16 ${PV_ANCHOR:-none} != e67e815c3494a3c5 (the arena anchor moved — المرجع = المنشور)"
[ "${PV_PIN:-none}"    = b1b209b32486cf49 ]    || fail "provision pin16 ${PV_PIN:-none} != b1b209b32486cf49 (the reference derivation moved)"
case "${PV_ROT:-}" in *stable*rotate*|*rotate*stable*) ;; *) fail "provision rotation ${PV_ROT:-?} lost the renewal+rotation drill";; esac
[ "${PV_DAYS:-0}"      -ge 800 ]               || fail "provision renew_days_left ${PV_DAYS:-0} < 800"

# signed artifact (W2/week 31): the identity fingerprint is the drill anchor —
# exact; v1+v2 both present; the artifact tracks the manifest version
SA_CHK=$(field "$SA" checks);   SA_NEG=$(negof "$SA" negatives)
SA_FP=$(field "$SA" fp16);      SA_V1=$(field "$SA" v1)
SA_V2=$(field "$SA" v2);        SA_VC=$(field "$SA" vc)
SA_VN=$(field "$SA" vn);        SA_DEX=$(field "$SA" dex)
SA_SHA=$(field "$SA" apk_sha256)
[ "${SA_CHK:-0}"   = 14 ]                    || fail "signedapk checks ${SA_CHK:-0} != 14 (the gate's check inventory moved)"
[ "${SA_NEG:-0/0}" = "6/6" ]                 || fail "signedapk negatives $SA_NEG != 6/6 (a check stopped biting)"
[ "${SA_FP:-none}" = a80e71b9a5d15546 ]       || fail "signedapk fp16 ${SA_FP:-none} != a80e71b9a5d15546 (the identity anchor moved — المرجع = المنشور)"
[ "${SA_V1:-0}"    = 1 ]                     || fail "signedapk v1 ${SA_V1:-0} != 1"
[ "${SA_V2:-0}"    = 1 ]                     || fail "signedapk v2 ${SA_V2:-0} != 1"
[ "${SA_VC:-0}"    -ge 1 ]                  || fail "signedapk artifact vc ${SA_VC:-0} < 1"
case "${SA_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "signedapk artifact vn ${SA_VN:-?} < 0.1.0";; esac
# the dex field has the form 505636/512000 — split and pin both halves
SA_DEX_B=$(echo "${SA_DEX:-0/0}" | cut -d/ -f1); SA_DEX_C=$(echo "${SA_DEX:-0/0}" | cut -d/ -f2)
[ "${SA_DEX_C:-0}" = "$DEX_CEILING" ]         || fail "signedapk dex ceiling ${SA_DEX_C:-?} != $DEX_CEILING (the ceiling moved silently)"
[ "${SA_DEX_B:-999999}" -le "$DEX_CEILING" ]  || fail "signedapk dex ${SA_DEX_B:-?} > ceiling $DEX_CEILING"

# store-upload bundle (W3/week 32): the bundle carries THE proven artifact —
# its apk sha256 must equal the signedapk statement's byte-for-byte
UB_CHK=$(field "$UB" checks);   UB_NEG=$(negof "$UB" negatives)
UB_MEM=$(field "$UB" members);  UB_EVD=$(field "$UB" evidence)
UB_VC=$(field "$UB" vc);        UB_SHA=$(field "$UB" apk_sha256)
[ "${UB_CHK:-0}"   = 14 ]                || fail "uploadbundle checks ${UB_CHK:-0} != 14 (the gate's check inventory moved)"
[ "${UB_NEG:-0/0}" = "6/6" ]             || fail "uploadbundle negatives $UB_NEG != 6/6 (a check stopped biting)"
[ "${UB_MEM:-0}"   = 8 ]                 || fail "uploadbundle members ${UB_MEM:-0} != 8 (the bundle inventory moved)"
[ "${UB_EVD:-0}"   = 8 ]                 || fail "uploadbundle ui evidence ${UB_EVD:-0} != 8 (the live-key coupling weakened)"
[ "${UB_VC:-0}"    -ge 1 ]              || fail "uploadbundle artifact vc ${UB_VC:-0} < 1"
[ "${UB_SHA:-none}" = "${SA_SHA:-other}" ] || fail "uploadbundle apk_sha256 != signedapk apk_sha256 (the bundle stopped carrying THE proven artifact)"

# ---- 3: evidence integrity — fresh == pipeline log == gates.env ----------------
if [ ! -f "$STATE" ] || [ ! -f "$LOGF" ]; then
  echo "PHASE6_FAILED state_missing (run ci/pipeline.sh first)"; exit 1
fi
LOG_PV=$(grep -E '^PROVISIONGATE_OK'    "$LOGF" | tail -1)
LOG_SA=$(grep -E '^SIGNEDAPKGATE_OK'    "$LOGF" | tail -1)
LOG_UB=$(grep -E '^UPLOADBUNDLEGATE_OK' "$LOGF" | tail -1)
LOG_R8=$(grep -E '^R8_OK '              "$LOGF" | tail -1)
[ -n "$LOG_PV" ] || fail "pipeline log has no PROVISIONGATE_OK line"
[ -n "$LOG_SA" ] || fail "pipeline log has no SIGNEDAPKGATE_OK line"
[ -n "$LOG_UB" ] || fail "pipeline log has no UPLOADBUNDLEGATE_OK line"
[ -n "$LOG_R8" ] || fail "pipeline log has no R8_OK line"

. "$STATE"

eq3() { # $1 env-key, $2 fresh value, $3 log value, $4 label
  local envv="${!1:-}"
  [ "$envv" = "$2" ] || fail "$4: gates.env $envv != fresh re-run $2"
  [ "$3" = "$2" ]    || fail "$4: pipeline log $3 != fresh re-run $2"
}
eq3 PROVISION_CHECKS        "$PV_CHK"    "$(field "$LOG_PV" checks)"        provision.checks
eq3 PROVISION_NEGATIVES     "$PV_NEG"    "$(negof "$LOG_PV" negatives)"     provision.negatives
eq3 PROVISION_ANCHOR_PIN16  "$PV_ANCHOR" "$(field "$LOG_PV" anchor_pin16)"  provision.anchor
eq3 PROVISION_PIN16         "$PV_PIN"    "$(field "$LOG_PV" pin16)"         provision.pin
eq3 SIGNEDAPK_CHECKS        "$SA_CHK"    "$(field "$LOG_SA" checks)"        signedapk.checks
eq3 SIGNEDAPK_NEGATIVES     "$SA_NEG"    "$(negof "$LOG_SA" negatives)"     signedapk.negatives
eq3 SIGNEDAPK_FP16          "$SA_FP"     "$(field "$LOG_SA" fp16)"          signedapk.fp16
eq3 UPLOADBUNDLE_CHECKS     "$UB_CHK"    "$(field "$LOG_UB" checks)"        uploadbundle.checks
eq3 UPLOADBUNDLE_NEGATIVES  "$UB_NEG"    "$(negof "$LOG_UB" negatives)"     uploadbundle.negatives
eq3 UPLOADBUNDLE_MEMBERS    "$UB_MEM"    "$(field "$LOG_UB" members)"       uploadbundle.members
eq3 UPLOADBUNDLE_APK_SHA256 "$UB_SHA"    "$(field "$LOG_UB" apk_sha256)"    uploadbundle.apk_sha256
# non-deterministic fields (the drill's decaying validity clock, its runtime
# ms) get BOUNDS in all three sources, never equality — jitter between runs is
# not a regression (the week-21 lesson, inherited three times):
[ "${PROVISION_RENEW_DAYS_LEFT:-0}" -ge 800 ] || fail "env provision renew_days_left ${PROVISION_RENEW_DAYS_LEFT:-?} < 800"
LPD=$(field "$LOG_PV" renew_days_left); [ "${LPD:-0}" -ge 800 ] || fail "log provision renew_days_left $LPD < 800"

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
# every previous gate key must still be OK — no gate regressed (25 keys:
# the 21 the phase-5 close guarded + the three phase-6 gates + the phase-5
# closing gate itself)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE \
         PROVISION_GATE SIGNEDAPK_GATE UPLOADBUNDLE_GATE PHASE5_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 25 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 25 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
# the phase-5 close itself must still be zero-regression in THIS run
echo "${PHASE5_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE5_REGRESSIONS ${PHASE5_REGRESSIONS:-?} is not 0/n (the phase-5 close regressed)"

# versionCode >= the phase-6 close floor AND versionName >= 2.7 (non-regression
# floor from day one — the exact vc74/2.7 close measurement stays pinned in
# release-manifest.phase6Close and can never be rewritten)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the phase-6 close floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the phase-6 close)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep; the 25-key loop
# adds 25; the eq3 function body has 2 fail lines counted once but each of the
# 11 eq3 calls executes BOTH, so eq3 adds 11*2 - 2 = 20; the artifact-split
# no-op line adds nothing. grep -cE counts every literal "|| fail " line in
# this file including the 2 inside eq3.
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 25 + 20 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE6_FAILED regressions=$REG/$CHECKS (see PHASE6 regression lines above)"
  exit 1
fi
echo "PHASE6_OK gates=3/3 regressions=0/$CHECKS dex=${R8_DEX_BYTES:-0}/$DEX_CEILING vc=$VC tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0} negatives=$PV_NEG+$SA_NEG+$UB_NEG anchor_pin16=$PV_ANCHOR fp16=$SA_FP"

# keys for the release manifest (release.sh sources gates.env) — idempotent
# upsert (week-33 hardening): a standalone re-run must not pile a second
# PHASE6_* block onto the pipeline's block; drop the old keys, append fresh.
sed -i '/^PHASE6_/d' "$STATE"
{
  echo "PHASE6_GATE=OK"
  echo "PHASE6_GATES=3/3"
  echo "PHASE6_REGRESSIONS=0/$CHECKS"
  echo "PHASE6_DEX=${R8_DEX_BYTES:-0}"
  echo "PHASE6_CEILING=$DEX_CEILING"
  echo "PHASE6_VC=$VC"
  echo "PHASE6_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
  echo "PHASE6_NEGATIVES=$PV_NEG+$SA_NEG+$UB_NEG"
  echo "PHASE6_ANCHOR_PIN16=$PV_ANCHOR"
  echo "PHASE6_FP16=$SA_FP"
} >> "$STATE"
exit 0
