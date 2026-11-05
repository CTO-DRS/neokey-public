#!/bin/bash
# preflight_gate.sh — week 36 (phase 7 W3) — ci/preflight_gate.sh
#
#   The documented pendings keep their outside halves outside (a device, an
#   account, a console click, a domain + CA cert, the owner's vault) — this
#   week closes the LAST orchestration slice: THE OPERATOR GETS ONE TOOL that
#   walks ALL release conditions on FRESH evidence and emits THE HANDOVER
#   SHEET — every step either CHECKED here (with the fresh verdict line as
#   evidence) or OUTSIDE with its literal command and documented externality,
#   no pretending:
#
#     1. compile guard      — Preflight (the tool) + PreflightGate + SyncServerPack
#                             (the ONE identity source)
#     2. THE FIVE FRESH     — never cached verdicts, re-run green INSIDE this
#        PROOFS               stage: the upload bundle re-verified (uploadbundle
#                             gate), the keystore restore drill re-run (ksdrill),
#                             the providers' contract suite re-run (billing),
#                             the pack booted + a full wire round (deploysrv —
#                             also re-assembles the pack, so the sheet's
#                             INSTALL literals measure the shipped doc), and
#                             the release commands' existence in the tree
#     3. THE EVIDENCE FILE  — the four fresh verdict lines written to
#                             build/preflight-evidence.env (this run's
#                             evidence, not gates.env's memory)
#     4. THE GATE           — PreflightGate: the sheet emitted deterministically
#                             from the fresh evidence, then THE SHEET IS THE
#                             REFERENCE: every row chased (inventory order,
#                             CHECKED evidence byte-equal to the fresh line,
#                             OUTSIDE commands verbatim in their sources) +
#                             FOUR designed negatives each biting its exact tag
#                             (dropped row / tampered evidence / OUTSIDE step
#                             claimed CHECKED / fabricated command) on dirty
#                             copies — the real sheet untouched
#     5. THE ANCHOR         — the verdict's identity derives from SyncServerPack
#                             constants (single source, the W35 lesson) and the
#                             bash anchor pins the PUBLISHED identity 77 / 3.0
#
#   Verdict: PREFLIGHTGATE_OK checks=N negatives=4/4 rows=10 checked=5
#            outside=5 sheet=build/release/preflight-sheet.md vc=77 vn=3.0
#            / PREFLIGHTGATE_FAILED <reason> (exit 1). Keys appended to
#            gates.env idempotently (the week-33 hardening).
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "PREFLIGHTGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
OUT=build/preflight-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/Preflight.java tests/com/hark/neokey/PreflightGate.java tests/com/hark/neokey/SyncServerPack.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE FIVE FRESH PROOFS (never cached) ------------------------------------
EV=build/preflight-evidence.env

# 2a: the store-upload bundle, re-verified fresh (the bundle carries THE
#     proven artifact — its anchor re-derives from the signedapk statement)
UB_OUT=$(bash ci/uploadbundle_gate.sh 2>&1) || { echo "$UB_OUT" | tail -5; fail "fresh.uploadbundle"; }
UB=$(echo "$UB_OUT" | grep -m1 '^UPLOADBUNDLEGATE_OK') || fail "fresh.uploadbundle (no verdict)"
echo "FRESH $UB"

# 2b: the keystore restore drill, re-run fresh (the W28 decision procedure
#     provably still bites in every later run — pending #4's guard)
KS_OUT=$(bash ci/keystore_drill.sh 2>&1) || { echo "$KS_OUT" | tail -5; fail "fresh.ksdrill"; }
KS=$(echo "$KS_OUT" | grep -m1 '^KSDRILLGATE_OK') || fail "fresh.ksdrill (no verdict)"
echo "FRESH $KS"

# 2c: the providers' contract suite, fresh (the replacement stays proven)
BL_OUT=$(bash ci/billing_gate.sh 2>&1) || { echo "$BL_OUT" | tail -5; fail "fresh.billing"; }
BL=$(echo "$BL_OUT" | grep -m1 '^BILLINGGATE_OK') || fail "fresh.billing (no verdict)"
echo "FRESH $BL"

# 2d: the sync server — the pack's own copy boots + a full wire round, fresh
#     (also re-assembles build/deploy/pack, so the sheet's INSTALL literals
#     measure the doc the operator will actually paste)
DS_OUT=$(bash ci/deploysrv_gate.sh 2>&1) || { echo "$DS_OUT" | tail -5; fail "fresh.deploysrv"; }
DS=$(echo "$DS_OUT" | grep -m1 '^DEPLOYSRVGATE_OK') || fail "fresh.deploysrv (no verdict)"
echo "FRESH $DS"

# 2e: the release commands exist in the tree (the java gate re-verifies the
#     five paths itself — this line only documents the fifth proof ran)
for c in build.sh build_aab.sh ci/build_signedapk.sh ci/release.sh ci/build_deploy.sh; do
  [ -f "$c" ] || fail "fresh.commands ($c missing)"
done
echo "FRESH release commands: build.sh build_aab.sh ci/build_signedapk.sh ci/release.sh ci/build_deploy.sh"

# ---- 3: THE EVIDENCE FILE — this run's fresh verdicts ---------------------------
{
  echo "EVIDENCE_BUNDLE=$UB"
  echo "EVIDENCE_KSDRILL=$KS"
  echo "EVIDENCE_BILLING=$BL"
  echo "EVIDENCE_DEPLOYSRV=$DS"
} > "$EV"

# ---- 4: THE GATE ----------------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.PreflightGate) || { echo "$V" | grep -E "^(PREFLIGHT regression|NEG-OK|SHEET |PREFLIGHTGATE_FAILED)" || echo "$V" | tail -5; fail "gate"; }
echo "$V" | grep -E "^(SHEET |PREFLIGHT regression|NEG-OK|PREFLIGHTGATE_OK)" || true
VERDICT=$(echo "$V" | grep '^PREFLIGHTGATE_OK' | tail -1)
[ -n "$VERDICT" ] || fail "gate (verdict missing)"
CHECKS=$(echo "$VERDICT" | grep -oE 'checks=[0-9]+' | cut -d= -f2)
NEG=$(echo "$VERDICT" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
ROWS=$(echo "$VERDICT" | grep -oE 'rows=[0-9]+' | cut -d= -f2)
CHECKED=$(echo "$VERDICT" | grep -oE 'checked=[0-9]+' | cut -d= -f2)
OUTSIDE=$(echo "$VERDICT" | grep -oE 'outside=[0-9]+' | cut -d= -f2)
[ -n "$CHECKS" ] && [ -n "$NEG" ] && [ -n "$ROWS" ] && [ -n "$CHECKED" ] && [ -n "$OUTSIDE" ] || fail "gate (verdict fields unparseable: $VERDICT)"

# ---- 5: THE ANCHOR — the sheet exists, its summary agrees, identity is published -
[ -f build/release/preflight-sheet.md ] || fail "anchor.sheet.missing"
grep -q '^PREFLIGHT_SHEET_OK' build/release/preflight-sheet.md || fail "anchor.sheet.summary"
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
echo "$VERDICT" | grep -q "vc=$VC vn=$VN" || fail "anchor.identity (the verdict must carry the week's identity $VC / $VN — got: $VERDICT)"
echo "$VERDICT"

# ---- 6: keys for the release manifest (idempotent upsert, week-33 hardening) ----
sed -i '/^PREFLIGHT_/d' build/release/gates.env
{
  echo "PREFLIGHT_GATE=OK"
  echo "PREFLIGHT_CHECKS=$CHECKS"
  echo "PREFLIGHT_NEGATIVES=$NEG"
  echo "PREFLIGHT_ROWS=$ROWS"
  echo "PREFLIGHT_CHECKED=$CHECKED"
  echo "PREFLIGHT_OUTSIDE=$OUTSIDE"
  echo "PREFLIGHT_VC=$VC"
  echo "PREFLIGHT_VN=$VN"
  echo "PREFLIGHT_SHEET=build/release/preflight-sheet.md"
} >> build/release/gates.env
exit 0
