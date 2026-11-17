#!/bin/bash
# EVAUDIT GATE — phase 8 W2 (week 39) — ci/evaudit_gate.sh
#
#   Pipeline stage 39, LAST (after the rebuild drill): مُدقِّق أدلة الأسبوع —
#   the tool that makes the EVIDENCE ITSELF verifiable in isolation. The
#   vault owner walks pendings #3 (the console click) and #4 (the restore)
#   ON the evidence; before W39 nothing checked the archive a stranger
#   hands him. docs/PHASE8-PLAN.md derives the week; the plan's law: no
#   pretending — the archive is verified, not trusted.
#
#     1. THE ANCHOR IS THE FIRST BARRIER (المرجع = المنشور): the week's
#        published identity (81 / 3.4) is pinned here and checked against
#        the live manifest before anything is packed.
#     2. THE PACK: EvAudit.pack builds THIS run's evidence archive
#        (build/evaudit/archive) deterministically — no timestamps: the
#        pipeline log, the gates ledger, the release manifest, the staged
#        rollout plans (+ the preflight sheet when it exists) and EVERY
#        saved negative-proof log — then a sorted SHA256SUMS.
#     3. THE AUDIT (EvAudit check): four law layers on the archive —
#        member integrity (every declared member byte-exact, none
#        undeclared), log shape (zero FAIL rows, RESULT sum consistent,
#        summary block == verdict rows in the complete shape, dex ceiling),
#        verdict↔ledger cross-check (every known gate line == its
#        gates.env keys, byte-exact), and identity consensus + the
#        saved-negative-proof law (every gate whose verdict appears must
#        find its proof log inside, ending PROOF PASSED). Then TWELVE
#        exact-tag negatives on dirty copies — including the consistent
#        forger who re-stamps SHA256SUMS — and the real archive
#        re-verifies green.
#
#   Verdict: `EVAUDITGATE_OK checks=N negatives=12/12 members=M proofs=P/P
#   vc=$PUB_VC vn=$PUB_VN tests=T/F dex=D/512000` / `EVAUDITGATE_FAILED <reason>`
#   (exit 1). The verdict line carries NO run shape (mid/complete) so a
#   standalone re-run after a pipeline byte-equals the pipeline's line
#   (the equality law below). Keys are appended to gates.env idempotently
#   (the week-33 hardening). The saved negative proof:
#   ci/evaudit-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "EVAUDITGATE_FAILED $1"; exit 1; }
STATE=build/release/gates.env
LOGF=build/pipeline.log

# ---- 0: THE ANCHOR — المرجع = المنشور ----------------------------------------
PUB_VC=3
PUB_VN=0.3.0

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the audit week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the audit week is $PUB_VN)"
[ -f "$LOGF" ] || fail "log.missing (the audit runs inside the pipeline or after one)"
[ -f "$STATE" ] || fail "ledger.missing (collect_gates must run first)"
[ -f build/release/release-manifest.json ] || fail "manifest.missing (the release stage must run first)"

# ---- 1: compile the module ----------------------------------------------------
OUTD=build/evaudit-gate; rm -rf "$OUTD"; mkdir -p "$OUTD"
SRC="tests/com/hark/neokey/EvAudit.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUTD" $SRC 2>&1
else
  [ -f tools/ecj.jar ] || fail "compile (no javac and no tools/ecj.jar — provision first)"
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUTD" $SRC 2>&1
fi || fail "compile"

# ---- 2: the pack — THIS run's archive, deterministic ---------------------------
rm -rf build/evaudit/archive
PACK_LINE=$(java -cp "$OUTD" com.hark.neokey.EvAudit pack build/evaudit/archive "$LOGF" build/release ci) || fail "pack ($PACK_LINE)"
echo "$PACK_LINE" | grep -q '^EVAUDIT_PACK_OK ' || fail "pack ($PACK_LINE)"
echo "[evaudit] $PACK_LINE"

# ---- 3: the audit — four law layers + twelve negatives + heal ------------------
AOUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit check build/evaudit/archive --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); ARC=$?
echo "$AOUT" | grep -E 'NEG-OK|EVAUDIT regression' >&2 || :
GATE_LINE=$(echo "$AOUT" | grep -m1 '^EVAUDITGATE_OK ')
if [ "$ARC" -ne 0 ] || [ -z "$GATE_LINE" ]; then
  echo "$AOUT" | tail -6 >&2
  fail "evaudit (rc=$ARC)"
fi
# the module's own audit line carries the run shape (mid/complete) — recorded,
# deliberately NOT part of the pinned verdict (the equality law below)
AUD_LINE=$(echo "$AOUT" | grep -m1 '^EVAUDIT_OK ' || true)
[ -n "$AUD_LINE" ] && echo "[evaudit] $AUD_LINE"
echo "[evaudit] $GATE_LINE"

# ---- 4: ledger consistency — the idempotent upsert + the two equalities -------
field() { echo "$1" | sed -E "s/.* $2=([^ ]+).*/\1/"; }
CHK=$(field "$GATE_LINE" checks); NEG=$(echo "$GATE_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
MEM=$(field "$GATE_LINE" members); PRF=$(echo "$GATE_LINE" | grep -oE 'proofs=[0-9]+(/[0-9]+)?' | cut -d= -f2)
TPF=$(field "$GATE_LINE" tests); DEX=$(field "$GATE_LINE" dex | cut -d/ -f1)
mkdir -p build/release; [ -f "$STATE" ] || : > "$STATE"
sed -i '/^EVAUDIT_/d' "$STATE"
{
  echo "EVAUDIT_GATE=OK"
  echo "EVAUDIT_CHECKS=$CHK"
  echo "EVAUDIT_NEGATIVES=$NEG"
  echo "EVAUDIT_MEMBERS=$MEM"
  echo "EVAUDIT_PROOFS=$PRF"
  echo "EVAUDIT_TESTS=$TPF"
  echo "EVAUDIT_DEX=$DEX"
  echo "EVAUDIT_CEILING=575000"   # W69-CEILING-RAISE
  echo "EVAUDIT_VC=$PUB_VC"
  echo "EVAUDIT_VN=$PUB_VN"
  echo "EVAUDIT_ARCHIVE=build/evaudit/archive"
  echo "EVAUDIT_PACK_LINE=$PACK_LINE"
} >> "$STATE"
# equality 1: the ledger must now carry THIS run's fresh values
. "$STATE"
[ "${EVAUDIT_CHECKS:-}" = "$CHK" ]    || fail "env.evaudit_checks ${EVAUDIT_CHECKS:-?} != fresh $CHK"
[ "${EVAUDIT_NEGATIVES:-}" = "$NEG" ] || fail "env.evaudit_negatives ${EVAUDIT_NEGATIVES:-?} != fresh $NEG"
[ "${EVAUDIT_MEMBERS:-}" = "$MEM" ]   || fail "env.evaudit_members ${EVAUDIT_MEMBERS:-?} != fresh $MEM"
[ "${EVAUDIT_TESTS:-}" = "$TPF" ]     || fail "env.evaudit_tests ${EVAUDIT_TESTS:-?} != fresh $TPF"
[ "${EVAUDIT_VC:-}" = "$PUB_VC" ]     || fail "env.evaudit_vc ${EVAUDIT_VC:-?} != $PUB_VC"
# equality 2: any EVAUDITGATE_OK line already in the outer log (a standalone
# re-run after a pipeline) must agree with fresh — a drifted rerun is caught;
# the verdict line carries no run shape so both invocation modes can agree.
# MEMBERS and PROOFS are masked in the comparison: the evidence set grows
# MONOTONICALLY — a proof log saved after the pipeline (the week's own proof
# lands post-commit by design) joins the next pack legitimately, so those two
# fields are expected to advance; every stable field (checks, negatives,
# tests, dex, vc, vn) must stay byte-equal, and the deeper laws (shape, xref,
# identity, the proof law) still audit the archive content itself.
mask() { echo "$1" | sed -E 's/members=[0-9]+/members=M/; s/proofs=[0-9/]+/proofs=P/'; }
LOG_LINE=$(grep -m1 '^EVAUDITGATE_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_LINE" ]; then
  [ "$(mask "$LOG_LINE")" = "$(mask "$GATE_LINE")" ] || fail "log.evaudit != fresh (the outer log's line drifted from this run)"
fi

echo "$GATE_LINE"
exit 0
