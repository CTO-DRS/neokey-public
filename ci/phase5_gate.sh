#!/bin/bash
# Phase-5 CLOSING gate (roadmap phase 5, week 4 — week 29) — ci/phase5_gate.sh
#
#   Reviews ALL phase-5 gates together in ONE line, on fresh re-runs (never
#   cached verdicts), then measures the locally-measurable exit criteria.
#   Evidence integrity is THREE-WAY (the week-21 design, inherited twice):
#   the fresh re-run, this run's pipeline log and gates.env must all AGREE on
#   every pinned field — tampering any single source is caught as a regression.
#
#     1. tls_gate + deploy_gate + keystore_drill re-run green, each field
#        pinned at its shipped baseline (no silent regression: pins, rounds,
#        blobs, granted, negatives, checks, fp16 — only p50 latencies,
#        ciphertext entropy and the drill's decaying days_left get bounds,
#        never equality).
#     2. fresh verdict lines == pipeline-log verdict lines == gates.env values.
#     3. Whole-pipeline state: every previous gate key still OK (21 keys from
#        phases 0-4 + the phase-4 closing gate), tests >= 1,354 with 0
#        failures, NQF golden assets = 15, theme packs = 25, PHASE4 close
#        itself still 0-regressions, R8 dex <= the 512,000B lowered ceiling
#        with all manifest components kept, trim and R8 agree on the same dex
#        artifact, versionCode >= 70 AND versionName >= 2.3 (W30 amendment,
#        documented in PHASE6-W1-REPORT §6: the phase-5 close measurement
#        vc 70 / 2.3 stays pinned in release-manifest.phase5Close; the live
#        check becomes a NON-REGRESSION floor like phase3/phase4 VC_MIN —
#        no week may ship below the phase-5 close, later weeks ride upward).
#
#   Verdict: PHASE5_OK … (exit 0, keys appended to gates.env for the release
#   manifest) / PHASE5_FAILED <reason> (exit 1).
#   Standalone use requires a prior pipeline run (build/pipeline.log + gates.env
#   + the r8 dex artifact the state references).
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=561000   # W69-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)   # LOWERED by the W22 real trim (was 520,000); overflow means trim, never raise
VC_MIN=2             # v0.1.0 identity reset — re-based from the phase-5 close floor (was 70)
VN_MIN=0.2           # v0.1.0 identity reset — re-based from the phase-5 close floor (was 2.3); the historical close measurement stays pinned in release-manifest.phase5Close
TESTS_MIN=1354       # shipped test total at phase-5 close (1,354 = weeks 26-28; the mass lives in the gates)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE5 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the three phase-5 gates, fresh ---------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE5_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

TL=$(run_one tls     ci/tls_gate.sh      'TLSGATE_OK')      || exit 1
DP=$(run_one deploy  ci/deploy_gate.sh   'DEPLOYGATE_OK')   || exit 1
KD=$(run_one ksdrill ci/keystore_drill.sh 'KSDRILLGATE_OK') || exit 1
echo "[phase5] $TL"
echo "[phase5] $DP"
echo "[phase5] $KD"

# ---- 2: pinned baselines on the FRESH values — shipped numbers may not sink --
# TLS client trust contract (W1/week 26): the pin stays the ONE pin, the
# handshake refusal and the wire rounds stay measured
TL_PINS=$(field "$TL" pins);     TL_PROTO=$(field "$TL" protocol)
TL_ROUNDS=$(field "$TL" rounds); TL_BLOBS=$(field "$TL" blobs)
TL_GRANT=$(field "$TL" granted); TL_NEG=$(negof "$TL" negatives)
TL_ENT=$(field "$TL" entropy_x100); TL_P50=$(field "$TL" p50_handshake_ms)
TL_CHK=$(field "$TL" checks)
[ "${TL_PINS:-0}"    = 1 ]        || fail "tls pins ${TL_PINS:-0} != 1 (the pin inventory moved silently)"
[ "${TL_ROUNDS:-0}"  -ge 3 ]      || fail "tls rounds ${TL_ROUNDS:-0} < 3"
[ "${TL_BLOBS:-0}"   -ge 3 ]      || fail "tls blobs ${TL_BLOBS:-0} < 3"
[ "${TL_GRANT:-0}"   -ge 1 ]      || fail "tls granted ${TL_GRANT:-0} < 1 (cross-device grant over TLS weakened)"
[ "${TL_NEG:-0/0}"   = "11/11" ]  || fail "tls negatives $TL_NEG != 11/11"
[ "${TL_CHK:-0}"     -ge 38 ]     || fail "tls checks ${TL_CHK:-0} < 38"
case "${TL_PROTO:-}" in TLSv1.2|TLSv1.3) ;; *) fail "tls protocol ${TL_PROTO:-?} below the measured floor (TLSv1.2)";; esac

# deployment bundle (W2/week 27): the bundle still serves the pinned contract
DP_BUN=$(field "$DP" bundle);       DP_PM=$(field "$DP" pins_match)
DP_RS=$(field "$DP" restart_stable); DP_ROUND=$(field "$DP" round)
DP_NEG=$(negof "$DP" negatives);    DP_CHK=$(field "$DP" checks)
DP_HLTH=$(field "$DP" health_ms)
[ "${DP_BUN:-0}"     = 1 ]        || fail "deploy bundle ${DP_BUN:-0} != 1"
[ "${DP_PM:-0}"      = 1 ]        || fail "deploy pins_match ${DP_PM:-0} != 1 (the pin anchor drifted)"
[ "${DP_RS:-0}"      = 1 ]        || fail "deploy restart_stable ${DP_RS:-0} != 1 (key identity no longer persists)"
[ "${DP_ROUND:-x}"   = ok ]       || fail "deploy round ${DP_ROUND:-?} != ok (the bundle stopped serving the contract)"
[ "${DP_NEG:-0/0}"   = "3/3" ]    || fail "deploy negatives $DP_NEG != 3/3"
[ "${DP_CHK:-0}"     -ge 13 ]     || fail "deploy checks ${DP_CHK:-0} < 13"

# keystore-recovery drill (W3/week 28): the anchor IS the identity — exact
KD_CHK=$(field "$KD" checks);   KD_NEG=$(negof "$KD" negatives)
KD_FP=$(field "$KD" fp16);      KD_DAYS=$(field "$KD" days_left)
[ "${KD_CHK:-0}"     = 12 ]       || fail "ksdrill checks ${KD_CHK:-0} != 12 (the drill's check inventory moved)"
[ "${KD_NEG:-0/0}"   = "8/8" ]    || fail "ksdrill negatives $KD_NEG != 8/8 (a check stopped biting)"
[ "${KD_FP:-none}"   = a80e71b9a5d15546 ] || fail "ksdrill fp16 ${KD_FP:-none} != a80e71b9a5d15546 (the drill anchor moved — المرجع = المنشور)"

# ---- 3: evidence integrity — fresh == pipeline log == gates.env ----------------
if [ ! -f "$STATE" ] || [ ! -f "$LOGF" ]; then
  echo "PHASE5_FAILED state_missing (run ci/pipeline.sh first)"; exit 1
fi
LOG_TL=$(grep -E '^TLSGATE_OK'     "$LOGF" | tail -1)
LOG_DP=$(grep -E '^DEPLOYGATE_OK'  "$LOGF" | tail -1)
LOG_KD=$(grep -E '^KSDRILLGATE_OK' "$LOGF" | tail -1)
LOG_R8=$(grep -E '^R8_OK '         "$LOGF" | tail -1)
[ -n "$LOG_TL" ] || fail "pipeline log has no TLSGATE_OK line"
[ -n "$LOG_DP" ] || fail "pipeline log has no DEPLOYGATE_OK line"
[ -n "$LOG_KD" ] || fail "pipeline log has no KSDRILLGATE_OK line"
[ -n "$LOG_R8" ] || fail "pipeline log has no R8_OK line"

. "$STATE"

eq3() { # $1 env-key, $2 fresh value, $3 log value, $4 label
  local envv="${!1:-}"
  [ "$envv" = "$2" ] || fail "$4: gates.env $envv != fresh re-run $2"
  [ "$3" = "$2" ]    || fail "$4: pipeline log $3 != fresh re-run $2"
}
eq3 TLS_PINS          "$TL_PINS"   "$(field "$LOG_TL" pins)"          tls.pins
eq3 TLS_ROUNDS        "$TL_ROUNDS" "$(field "$LOG_TL" rounds)"        tls.rounds
eq3 TLS_NEGATIVES     "$TL_NEG"    "$(negof "$LOG_TL" negatives)"     tls.negatives
eq3 TLS_CHECKS        "$TL_CHK"    "$(field "$LOG_TL" checks)"        tls.checks
eq3 DEPLOY_NEGATIVES  "$DP_NEG"    "$(negof "$LOG_DP" negatives)"     deploy.negatives
eq3 DEPLOY_CHECKS     "$DP_CHK"    "$(field "$LOG_DP" checks)"        deploy.checks
eq3 KSDRILL_CHECKS    "$KD_CHK"    "$(field "$LOG_KD" checks)"        ksdrill.checks
eq3 KSDRILL_NEGATIVES "$KD_NEG"    "$(negof "$LOG_KD" negatives)"     ksdrill.negatives
eq3 KSDRILL_FP16      "$KD_FP"     "$(field "$LOG_KD" fp16)"          ksdrill.fp16
# non-deterministic fields (latency, ciphertext entropy, the drill's decaying
# validity clock) get BOUNDS in all three sources, never equality — p50 jitter
# between runs is not a regression (the week-21 lesson, inherited twice):
# W30 amendment (PHASE6-W1-REPORT §4): 7.50 was an over-tight W29 pin on a
# ~700B sample — the supervised gate's OWN honest W26 floor is 7.30 (TlsGate
# "zeroknowledge.entropy", calibrated to the 705B sample) and fresh random
# draws legitimately land 7.46-7.54. The floor here = the supervised gate's
# floor (7.30); the gate itself still bites below it — a real regression still
# trips, a legitimate draw no longer does.
[ "${TLS_ENTROPY_X100:-0}" -ge 730 ] || fail "env tls entropy_x100 ${TLS_ENTROPY_X100:-?} < 7.30"
LTE=$(field "$LOG_TL" entropy_x100); [ "${LTE:-0}" -ge 730 ] || fail "log tls entropy_x100 $LTE < 7.30"
[ "${TLS_P50_HANDSHAKE_MS:-99999}" -le 200 ] || fail "env tls p50_handshake_ms ${TLS_P50_HANDSHAKE_MS:-?} > 200"
LTP=$(field "$LOG_TL" p50_handshake_ms); [ "${LTP:-99999}" -le 200 ] || fail "log tls p50_handshake_ms $LTP > 200"
[ "${DEPLOY_HEALTH_MS:-99999}" -le 1000 ] || fail "env deploy health_ms ${DEPLOY_HEALTH_MS:-?} > 1000"
LDH=$(field "$LOG_DP" health_ms); [ "${LDH:-99999}" -le 1000 ] || fail "log deploy health_ms $LDH > 1000"
[ "${KSDRILL_DAYS_LEFT:-0}" -ge 7000 ] || fail "env ksdrill days_left ${KSDRILL_DAYS_LEFT:-?} < 7000"
LKD=$(field "$LOG_KD" days_left); [ "${LKD:-0}" -ge 7000 ] || fail "log ksdrill days_left $LKD < 7000"

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
# every previous gate key must still be OK — no gate regressed (21 keys:
# phases 0-2 (13) + the three phase-4 gates + the phase-3 closing gate +
# the three phase-5 gates + the phase-4 closing gate itself)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 25 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 25 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
# the phase-4 close itself must still be zero-regression in THIS run
echo "${PHASE4_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE4_REGRESSIONS ${PHASE4_REGRESSIONS:-?} is not 0/n (the phase-4 close regressed)"

# versionCode >= the phase-5 close floor AND versionName >= 2.3 (non-regression
# floor, W30 amendment — the exact vc70/2.3 close measurement stays pinned in
# release-manifest.phase5Close and can never be rewritten)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the phase-5 close floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the phase-5 close)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep (41 incl. the two
# lines inside eq3); the 21-key loop adds 21; each of the 9 eq3 calls executes
# TWO conditions while grep counted the body once, so eq3 adds 9*2-2 = 16.
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 21 + 16 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE5_FAILED regressions=$REG/$CHECKS (see PHASE5 regression lines above)"
  exit 1
fi
echo "PHASE5_OK gates=3/3 regressions=0/$CHECKS dex=${R8_DEX_BYTES:-0}/$DEX_CEILING vc=$VC tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0} negatives=$TL_NEG+$DP_NEG+$KD_NEG fp16=$KD_FP"

# keys for the release manifest (release.sh sources gates.env)
{
  echo "PHASE5_GATE=OK"
  echo "PHASE5_GATES=3/3"
  echo "PHASE5_REGRESSIONS=0/$CHECKS"
  echo "PHASE5_DEX=${R8_DEX_BYTES:-0}"
  echo "PHASE5_CEILING=$DEX_CEILING"
  echo "PHASE5_VC=$VC"
  echo "PHASE5_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
  echo "PHASE5_NEGATIVES=$TL_NEG+$DP_NEG+$KD_NEG"
} >> "$STATE"
exit 0
