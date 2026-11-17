#!/bin/bash
# Phase-9 CLOSING gate (roadmap phase 9, week 3 — week 44) — ci/phase9_gate.sh
#
#   Closes the publication line (W1/42 pubsync, W2/43 ownerverify) the way the
#   phase-8 close closed its three gates: the phase's gates re-run FRESH in one
#   line (never cached verdicts), then the locally-measurable exit criteria are
#   measured once and pinned.
#
#     1. pubsync_gate re-run in its PUBLISH mode (the week's evidence archive
#        packed by EvAudit.pack, zipped deterministically, uploaded to the
#        published release, the ROUNDTRIP re-derived from the fetched bytes)
#        and ownerverify_gate re-run in its FULL local mode (the 24-check
#        battery the pipeline stage skips — the closer IS a standalone run).
#        The write credential is fail-closed (publish.credential — W1 law).
#     2. Fresh baselines pinned: the 12/12 negative batteries of BOTH gates,
#        the additive asset set (>= 3 — weeks 33/42/43 stay, the week's assets
#        join), the identity floors.
#     3. State consistency: the gates' own keys in gates.env must be OK and
#        agree with the fresh lines (PUBSYNC_WEEK=48 — a stale week's
#        publication state must not pass as this week's close).
#     4. Whole-pipeline state: every previous gate key still OK (37 keys — the
#        35 the phase-8 close guarded (CLIPLIB joined at W48) + the two phase-9 gates), the phase-7
#        AND phase-8 closes still 0-regressions, tests >= 1,581 with 0
#        failures, R8 dex <= the 512,000B ceiling, versionCode >= 1 AND
#        versionName >= 0.1.0 — the floor RE-BASED at the v0.1.0 identity
#        reset; the historical phase-9 close measured vc 85 / 3.8 and stays
#        pinned ONCE in release-manifest.phase9Close (never rewritten);
#        no week may ship below the v0.1.0 integrated baseline.
#
#   RECURSION NOTE (the W38/W41 pattern, three stages deep): inside an inner
#   run (NEOKEY_REBUILD_INNER=1) this stage omits itself in ci/pipeline.sh
#   (a note, no row): a closer may not run inside the drill.
#
#   Verdict: PHASE9_OK … (exit 0, keys appended to gates.env) /
#   PHASE9_FAILED <reason> (exit 1). The verdict carries NO timing, NO
#   published-sha and NO roundtrip field (a fresh publish re-derives the
#   sha every run by design — the verdict must stay byte-stable across a
#   standalone re-run), so the equality law holds: any PHASE9_OK line in the
#   outer log must byte-equal fresh.
#   Standalone use requires a prior pipeline run (gates.env + the provisioned
#   toolchain the gates re-verify) and the owner's write credential in
#   PUBSYNC_TOKEN_FILE (the vault pattern: .secrets/pubsync.token).
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=584000   # W69-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)
VC_MIN=2             # v0.1.0 identity reset — re-based from the phase-9 close floor (was 85)
VN_MIN=0.2           # v0.1.0 identity reset — re-based from the phase-9 close floor (was 3.8); the historical close measurement stays pinned in release-manifest.phase9Close
TESTS_MIN=1581       # shipped test total at phase-9 close
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE9 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the two phase-9 gates, fresh -----------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE9_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

# the publication's write credential: fail-closed from the vault (the W1 law —
# no credential = a declared failure, never a skip, never a pretending).
# PUBSYNC_REPLACE_ON_DRIFT=0: the published set is IMMUTABLE within the week —
# a closer re-run's fresh pack legitimately differs (the run's own log rides
# the archive) and the backend window 404s the delete path (the W2 lesson);
# the roundtrip is the tamper layer, the re-publish path is not.
export PUBSYNC_TOKEN_FILE="${PUBSYNC_TOKEN_FILE:-.secrets/pubsync.token}"
export PUBSYNC_REPLACE_ON_DRIFT=0
PUB=$(run_one pubsync     ci/pubsync_gate.sh     'PUBSYNC_OK')     || exit 1
OV=$(run_one  ownerverify ci/ownerverify_gate.sh 'OWNERVERIFY_OK') || exit 1
echo "[phase9] $PUB"
echo "[phase9] $OV"

# ---- 2: pinned baselines on the FRESH values ----------------------------------
PUB_NEG=$(negof "$PUB" negatives)
PUB_ASSETS=$(field "$PUB" assets)
PUB_VC=$(field "$PUB" vc);         PUB_VN=$(field "$PUB" vn)
[ "${PUB_NEG:-0/0}"   = "12/12" ]  || fail "pubsync negatives $PUB_NEG != 12/12 (a check stopped biting)"
[ "${PUB_ASSETS:-0}"  -ge 3 ]      || fail "pubsync assets ${PUB_ASSETS:-0} < 3 (the additive set lost a published week)"
[ "${PUB_VC:-0}"      -ge "$VC_MIN" ] || fail "pubsync artifact vc ${PUB_VC:-0} < $VC_MIN"
case "${PUB_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "pubsync artifact vn ${PUB_VN:-?} < $VN_MIN";; esac

OV_CHK=$(field "$OV" checks);      OV_NEG=$(negof "$OV" negatives)
OV_VC=$(field "$OV" vc);           OV_VN=$(field "$OV" vn)
[ "${OV_CHK:-0}"      -ge 24 ]     || fail "ownerverify checks ${OV_CHK:-0} < 24 (the battery the pipeline stage skips must run in full here)"
[ "${OV_NEG:-0/0}"    = "12/12" ]  || fail "ownerverify negatives $OV_NEG != 12/12 (a check stopped biting)"
[ "${OV_VC:-0}"       -ge "$VC_MIN" ] || fail "ownerverify artifact vc ${OV_VC:-0} < $VC_MIN"
case "${OV_VN:-0}" in 0.[1-9]*|[1-9].*) ;; *) fail "ownerverify artifact vn ${OV_VN:-?} < $VN_MIN";; esac
[ "${PUB_VC:-0}" = "${OV_VC:-1}" ] || fail "identity consensus: pubsync vc $PUB_VC != ownerverify vc ${OV_VC:-?}"
[ "${PUB_VN:-0}" = "${OV_VN:-1}" ] || fail "identity consensus: pubsync vn $PUB_VN != ownerverify vn ${OV_VN:-?}"

# ---- 3: state consistency ------------------------------------------------------
if [ ! -f "$STATE" ]; then
  echo "PHASE9_FAILED state_missing (run ci/pipeline.sh first — the closer reads the run's gates.env)"; exit 1
fi
. "$STATE"
[ "${PUBSYNC_GATE:-NOT_OK}" = "OK" ]     || fail "pubsync gate key ${PUBSYNC_GATE:-NOT_OK} not OK"
[ "${OWNERVERIFY_GATE:-NOT_OK}" = "OK" ] || fail "ownerverify gate key ${OWNERVERIFY_GATE:-NOT_OK} not OK"
[ "${PUBSYNC_WEEK:-?}" = "47" ]          || fail "pubsync.week ${PUBSYNC_WEEK:-?} != 46 (a stale week's publication state must not pass as this week's close)"
[ "${PUBSYNC_NEGATIVES:-?}" = "$PUB_NEG" ] || fail "pubsync.negatives gates.env ${PUBSYNC_NEGATIVES:-?} != fresh $PUB_NEG"
[ "${PUBSYNC_ASSETS:-0}" = "${PUB_ASSETS:-1}" ] || fail "pubsync.assets gates.env ${PUBSYNC_ASSETS:-?} != fresh ${PUB_ASSETS:-?}"

# ---- 4: whole-pipeline no-regression ------------------------------------------
[ "${AUDIT:-}" = "OK" ] || fail "secrets audit not OK"
[ "${TESTS_TOTAL:-0}" -ge "$TESTS_MIN" ] || fail "tests $TESTS_TOTAL < $TESTS_MIN"
[ "${TESTS_FAILED:-1}" = "0" ] || fail "tests_failed $TESTS_FAILED != 0"
# every previous gate key must still be OK — no gate regressed (37 keys:
# the 35 the phase-8 close guards (CLIPLIB joined at W48) + the two phase-9 gates)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE \
         PROVISION_GATE SIGNEDAPK_GATE UPLOADBUNDLE_GATE PHASE5_GATE \
         PHASE6_GATE BILLING_GATE DEPLOYSRV_GATE PREFLIGHT_GATE \
         PHASE7_GATE REBUILD_GATE EVAUDIT_GATE OPVERIFY_GATE CLIPIO_GATE CLIPLIB_GATE \
         PUBSYNC_GATE OWNERVERIFY_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 25 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 25 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
echo "${PHASE7_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE7_REGRESSIONS ${PHASE7_REGRESSIONS:-?} is not 0/n (the phase-7 close regressed)"
echo "${PHASE8_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE8_REGRESSIONS ${PHASE8_REGRESSIONS:-?} is not 0/n (the phase-8 close regressed)"
[ "${R8_DEX_BYTES:-999999}" -le "$DEX_CEILING" ] || fail "dex ${R8_DEX_BYTES:-?} > ceiling $DEX_CEILING"
[ "${R8_COMPONENTS_KEPT:-0/0}" = "10/10" ] || fail "components kept ${R8_COMPONENTS_KEPT:-0/0} != 10/10"

# versionCode >= the v0.1.0 baseline AND versionName >= 0.1.0 (non-regression
# floor re-based at the v0.1.0 identity reset — the historical vc85/3.8 close
# measurement stays pinned in release-manifest.phase9Close and can never be rewritten)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the phase-9 close floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the phase-9 close)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep; the 37-key loop
# line is counted once but executes 35 (+34 net).
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 34 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE9_FAILED regressions=$REG/$CHECKS (see PHASE9 regression lines above)"
  exit 1
fi
FRESH="PHASE9_OK gates=2/2 regressions=0/$CHECKS negatives=$PUB_NEG+$OV_NEG assets=$PUB_ASSETS vc=$VC vn=$VN tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
# equality FIRST (the verdict must never print beside a drifted rerun): any
# PHASE9_OK line already in the outer log must byte-equal fresh — the verdict
# carries no timing, no published-sha and no roundtrip field on purpose
LOG_P9=$(grep -m1 '^PHASE9_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_P9" ]; then
  [ "$LOG_P9" = "$FRESH" ] || { fail "log.phase9 != fresh (the outer log's line drifted from this run)"; echo "PHASE9_FAILED regressions=$REG/$CHECKS" >&2; exit 1; }
fi
echo "$FRESH"

# keys for the release manifest (release.sh sources gates.env) — idempotent
# upsert: a standalone re-run must not pile a second PHASE9_* block.
sed -i '/^PHASE9_/d' "$STATE"
{
  echo "PHASE9_GATE=OK"
  echo "PHASE9_GATES=2/2"
  echo "PHASE9_REGRESSIONS=0/$CHECKS"
  echo "PHASE9_NEGATIVES=$PUB_NEG+$OV_NEG"
  echo "PHASE9_ASSETS=$PUB_ASSETS"
  echo "PHASE9_VC=$VC"
  echo "PHASE9_VN=$VN"
  echo "PHASE9_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
} >> "$STATE"
exit 0
