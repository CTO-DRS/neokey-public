#!/bin/bash
# Phase-10 CLOSING gate (docs/PHASE10-PLAN.md, week 46) — ci/phase10_gate.sh
#
#   Closes the publish-line-under-hostile-restore phase the way every closer
#   does: the phase's laws re-run FRESH in one line (never cached verdicts),
#   then the locally-measurable exit criteria are measured once and pinned.
#
#     1. LAW 1 (publish.dirty_tree) re-run fresh: dirt lands in NOTES.md, the
#        publish must refuse fail-closed with the exact tag BEFORE anything is
#        packed or uploaded, and the dirt must heal. No credential needed —
#        the tree check precedes the credential check by the W1 law, so the
#        BITE ORDER itself is part of the proof (the refusal fires where the
#        law put it, not where a missing vault would put it).
#     2. LAW 2 (ownercheck.record) re-run fresh: the owner verifier's
#        git-record anchor fail-closes at the v0.1.0 identity. No vc=1
#        publication exists yet (the owner's first publication is the
#        documented external pending #1 — no pretending), and the record
#        check precedes the read-credential check by the W45 law, so the
#        bite needs no credential and no network: the anchor dies with its
#        exact tag (no committed record and no ledger state — nothing to
#        anchor on). Where the vault lives, the full battery re-runs inside
#        the phase-9 closer as before.
#     3. Whole-pipeline state: the 34 pre-publication gate keys must all be
#        OK (hard); the publication keys (PUBSYNC_/OWNERVERIFY_) and the
#        phase-9 closer key are guarded WHEREVER that state exists — a fresh
#        clone without the owner's vault has no publication state, and the
#        absence must be TOTAL (all three together or none: a half-state is
#        a lie). Tests >= the shipped total with 0 failures, R8 dex <= the
#        512,000B ceiling, components 10/10, the phase-7 AND phase-8 closes
#        still 0-regressions, NQF 15 goldens, themestore >= 25 packs.
#     4. Identity floors: versionCode >= 1 AND versionName >= 0.1.0 — the
#        v0.1.0 re-based floor; the historical phase-9 close measurement
#        (vc 85 / 3.8) stays pinned ONCE in release-manifest.phase9Close
#        (never rewritten); no week may ship below the v0.1.0 baseline.
#
#   RECURSION NOTE (the W38/W41/W44 pattern, four stages deep): inside an
#   inner run (NEOKEY_REBUILD_INNER=1) this stage omits itself in
#   ci/pipeline.sh (a note, no row): a closer may not run inside the drill.
#
#   Verdict: PHASE10_OK laws=2/2 regressions=0/N vc=1 vn=0.1.0 tests=T/F
#   (exit 0, keys appended to gates.env) / PHASE10_FAILED <reason> (exit 1).
#   The verdict carries NO timing and NO published field (the line's own
#   publication awaits the owner's vault — the verdict must stay byte-stable
#   across a standalone re-run), so the equality law holds: any PHASE10_OK
#   line in the outer log must byte-equal fresh.
set -uo pipefail
cd "$(dirname "$0")/.."

DEX_CEILING=512000
VC_MIN=1             # v0.1.0 identity reset — re-based from the phase-9 close floor (was 85)
VN_MIN=0.1           # v0.1.0 identity reset — re-based from the phase-9 close floor (was 3.8); the historical close measurement stays pinned in release-manifest.phase9Close
TESTS_MIN=1576       # the fresh-clone first-run total (the W45-documented five pack-check skips; with build/deploy/neokey-sync-server.jar present the total is 1581 — both shapes all-green)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE10 regression: $1" >&2; REG=$((REG+1)); }

# ---- 1: LAW 1 re-runs fresh — publish.dirty_tree --------------------------------
[ -z "$(git status --porcelain -- NOTES.md | head -1)" ] || {
  echo "PHASE10_FAILED law1.pre (NOTES.md is already dirty — the law test needs a clean file to dirty and heal)"; exit 1; }
printf 'dirt %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> NOTES.md
DOUT=$(PUBSYNC_TOKEN_FILE="${PUBSYNC_TOKEN_FILE:-.secrets/pubsync.token}" ci/pubsync_gate.sh 2>&1); DRC=$?
git checkout -- NOTES.md 2>/dev/null || :
[ "$DRC" -ne 0 ] || fail "law1.rc (the publish must fail on a dirty tree)"
echo "$DOUT" | grep -q 'PUBSYNC_FAILED publish.dirty_tree' || fail "law1.tag (expected PUBSYNC_FAILED publish.dirty_tree — got: $(echo "$DOUT" | tail -1 | tr '\n' ' '))"
! echo "$DOUT" | grep -qE 'PUBSYNC_OK|\[pubsync\] uploaded' || fail "law1.sideeffect (the refusal must bite BEFORE anything is packed or uploaded)"
! git status --porcelain | grep -q NOTES.md || fail "law1.restore (the dirt was not healed)"

# ---- 2: LAW 2 re-runs fresh — ownercheck.record at the v0.1.0 identity ----------
OVOUT=$(PUBVERIFY_TOKEN_FILE="${PUBVERIFY_TOKEN_FILE:-/nonexistent/p10.probe}" ci/ownerverify_gate.sh --remote 2>&1); OVRC=$?
[ "$OVRC" -ne 0 ] || fail "law2.rc (the verifier must fail closed with no committed vc=1 record)"
echo "$OVOUT" | grep -q 'OWNERVERIFY_FAILED ownercheck.record' || fail "law2.tag (expected OWNERVERIFY_FAILED ownercheck.record — got: $(echo "$OVOUT" | tail -1 | tr '\n' ' '))"

# ---- 3: whole-pipeline state -----------------------------------------------------
if [ ! -f "$STATE" ]; then
  echo "PHASE10_FAILED state_missing (run ci/pipeline.sh first — the closer reads the run's gates.env)"; exit 1
fi
. "$STATE"
# the publication line's keys: wherever the publication state exists it must be
# OK, and the absence must be TOTAL (a fresh clone without the owner's vault
# has none — the documented pending #1; a half-state is a lie)
PCOUNT=0
[ -n "${PUBSYNC_GATE:-}" ] && PCOUNT=$((PCOUNT+1))
[ -n "${OWNERVERIFY_GATE:-}" ] && PCOUNT=$((PCOUNT+1))
[ -n "${PHASE9_GATE:-}" ] && PCOUNT=$((PCOUNT+1))
[ "$PCOUNT" = "0" ] || [ "$PCOUNT" = "3" ] || fail "publication.state.partial ($PCOUNT/3 — the publication keys must exist together or not at all)"
if [ "$PCOUNT" = "3" ]; then
  [ "${PUBSYNC_GATE:-NOT_OK}" = "OK" ]     || fail "pubsync gate key ${PUBSYNC_GATE:-NOT_OK} not OK"
  [ "${OWNERVERIFY_GATE:-NOT_OK}" = "OK" ] || fail "ownerverify gate key ${OWNERVERIFY_GATE:-NOT_OK} not OK"
  [ "${PHASE9_GATE:-NOT_OK}" = "OK" ]      || fail "phase9 closer key ${PHASE9_GATE:-NOT_OK} not OK"
fi
# every pre-publication gate key must still be OK — no gate regressed (34 keys:
# the phase-9 close's 36 minus the two publication keys it guarded where its
# vault lived; the W47 clipio gate joined the guarded set)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE TLS_GATE DEPLOY_GATE KSDRILL_GATE PHASE4_GATE \
         PROVISION_GATE SIGNEDAPK_GATE UPLOADBUNDLE_GATE PHASE5_GATE \
         PHASE6_GATE BILLING_GATE DEPLOYSRV_GATE PREFLIGHT_GATE \
         PHASE7_GATE REBUILD_GATE EVAUDIT_GATE OPVERIFY_GATE CLIPIO_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${AUDIT:-}" = "OK" ] || fail "secrets audit not OK"
[ "${TESTS_TOTAL:-0}" -ge "$TESTS_MIN" ] || fail "tests $TESTS_TOTAL < $TESTS_MIN"
[ "${TESTS_FAILED:-1}" = "0" ] || fail "tests_failed $TESTS_FAILED != 0"
[ "${NQFPORT_ASSETS:-0}" = 15 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 15 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"
echo "${PHASE7_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE7_REGRESSIONS ${PHASE7_REGRESSIONS:-?} is not 0/n (the phase-7 close regressed)"
echo "${PHASE8_REGRESSIONS:-?}" | grep -qE '^0/' || fail "PHASE8_REGRESSIONS ${PHASE8_REGRESSIONS:-?} is not 0/n (the phase-8 close regressed)"
[ "${R8_DEX_BYTES:-999999}" -le "$DEX_CEILING" ] || fail "dex ${R8_DEX_BYTES:-?} > ceiling $DEX_CEILING"
[ "${R8_COMPONENTS_KEPT:-0/0}" = "10/10" ] || fail "components kept ${R8_COMPONENTS_KEPT:-0/0} != 10/10"

# ---- 4: identity floors (the v0.1.0 re-based floor) ------------------------------
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (the v0.1.0 floor)"
[ "$(printf '%s\n' "${VN:-0}" "$VN_MIN" | sort -V | head -1)" = "$VN_MIN" ] \
  || fail "versionName ${VN:-?} < $VN_MIN (no week may ship below the v0.1.0 baseline)"

# ---- verdict ---------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep; the 33-key loop
# line is counted once but executes 33 (+32 net).
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 32 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE10_FAILED regressions=$REG/$CHECKS (see PHASE10 regression lines above)"
  exit 1
fi
FRESH="PHASE10_OK laws=2/2 regressions=0/$CHECKS vc=$VC vn=$VN tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
# equality FIRST (the verdict must never print beside a drifted rerun): any
# PHASE10_OK line already in the outer log must byte-equal fresh — the verdict
# carries no timing and no published field on purpose
LOG_P10=$(grep -m1 '^PHASE10_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_P10" ]; then
  [ "$LOG_P10" = "$FRESH" ] || { fail "log.phase10 != fresh (the outer log's line drifted from this run)"; echo "PHASE10_FAILED regressions=$REG/$CHECKS" >&2; exit 1; }
fi
echo "$FRESH"

# keys for the release manifest (release.sh sources gates.env) — idempotent
# upsert: a standalone re-run must not pile a second PHASE10_* block.
sed -i '/^PHASE10_/d' "$STATE"
{
  echo "PHASE10_GATE=OK"
  echo "PHASE10_LAWS=2/2"
  echo "PHASE10_REGRESSIONS=0/$CHECKS"
  echo "PHASE10_VC=$VC"
  echo "PHASE10_VN=$VN"
  echo "PHASE10_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
} >> "$STATE"
exit 0
