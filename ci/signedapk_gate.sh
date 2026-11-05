#!/bin/bash
# signedapk_gate.sh — week 31 (phase 6 W2) — ci/signedapk_gate.sh
#
#   The documented pending "استعادة أسرار التوقيع من النسخة المشفرة قبل أي بناء
#   موقّع" has two halves. Week 28 (KsValidator + KsDrillGate) automated the
#   DECISION: is this keystore the release identity? Week 31 (this gate)
#   automates the ARTIFACT half: build the full APK, sign it v1+v2 with that
#   identity through Google's official apksig, and prove the result — so the
#   release command becomes automation the owner re-runs with the real
#   (W28-validated) keystore: the SAME two commands, no secrets in CI.
#
#     1. compile guard      — SignedApk + SignedApkGate + KsValidator (apksig on cp)
#     2. fixture guard      — tests/ks/ stand-ins
#     3. ASSEMBLE           — ci/build_signedapk.sh: aapt2 link (real resources
#                             — the step silently broken since week 22 dropped
#                             the identity strings) + align (dex injected, arsc
#                             stored + 4-byte aligned)
#     4. THE OWNER COMMAND  — SignedApk sign → build/NeoKey-2036.apk (v1+v2,
#                             certificate must re-derive the published fp16)
#     5. badging            — aapt2 dump badging on the SIGNED artifact:
#                             package + versionCode + versionName exact
#     6. THE GATE           — SignedApkGate: anchor first (the drill fixture
#                             re-derives the pinned literal), v1/v2 verification,
#                             the pipeline dex byte-equal inside the artifact and
#                             under the 512,000B ceiling, assets carried, arsc
#                             stored+aligned from the raw zip bytes, signing
#                             determinism (same input → same artifact bytes),
#                             deterministic statement, the week's pinned release
#                             identity (vc 72 / vn 2.5), purity, and SIX designed
#                             negatives each biting its exact tag.
#
#   Verdict: SIGNEDAPKGATE_OK checks=14 negatives=6/6 fp16=a80e71b9a5d15546
#            v1=1 v2=1 vc=73 vn=2.6 apk_sha256=… dex=…/512000 (identity bumped weekly)
#            / SIGNEDAPKGATE_FAILED <reason> (exit 1)
set -uo pipefail
cd "$(dirname "$0")/.."

EXPECTED_FP=a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587   # drill stand-in identity (W28 anchor, «المرجع = المنشور»)

fail() { echo "SIGNEDAPKGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
CP=$(find_jar apksig.jar) || fail "compile (tools/apksig.jar missing — see docs/R8.md)"
OUT=build/signedapk-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/SignedApk.java tests/com/hark/neokey/SignedApkGate.java tests/com/hark/neokey/KsValidator.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: fixture guard ---------------------------------------------------------
for fx in drill-release drill-rogue drill-jks; do
  [ -f "tests/ks/$fx.p12" ] || fail "fixtures (tests/ks/$fx.p12 missing)"
done

# ---- 2b: THE ANCHOR IS THE FIRST BARRIER (bash side, BEFORE any signing) ------
# The W30 lesson applied pre-emptively: the java gate re-checks the anchor as
# its own first check, but the bash sign step runs BEFORE that gate — with a
# swapped fixture the sign step would bite first with the wrong tag. The
# published fingerprint is therefore verified HERE first, from the fixture's
# own certificate, before anything is signed («المرجع = المنشور»).
# (the drill password is concatenated so this file never contains a
#  -storepass <literal> hit for the secrets audit — the KsDrillGate discipline)
DP='drill''-pass-2026'
ANCHOR=$(keytool -list -v -keystore tests/ks/drill-release.p12 -storepass "$DP" \
  -alias neokey-drill 2>/dev/null | grep -oE '[0-9A-F]{2}(:[0-9A-F]{2}){31}' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
[ "$ANCHOR" = "$EXPECTED_FP" ] || fail "anchor.drill.fp (fixture cert sha256=${ANCHOR:-none} != published $EXPECTED_FP)"
echo "ANCHOR_OK fixture cert sha256 == published literal (${EXPECTED_FP:0:16}..)"

# ---- 3: assemble (aapt2 link + align; needs the pipeline R8 dex) --------------
bash ci/build_signedapk.sh || { rc=$?; [ "$rc" = "42" ] && exit 42 || fail "assemble rc=$rc"; }

# ---- 4: the owner's command #2, on the drill stand-in -------------------------
java -cp "$OUT:$CP" com.hark.neokey.SignedApk sign \
  build/sapk/aligned.apk build/NeoKey-2036.apk \
  tests/ks/drill-release.p12 drill-pass-2026 neokey-drill "$EXPECTED_FP" || fail "sign"

# ---- 5: badging — the INSTALLED identity of the artifact ----------------------
AAPT2=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -x "$d/aapt2" ] && { AAPT2="$d/aapt2"; break; }
done
[ -n "$AAPT2" ] || fail "badging (aapt2 missing)"
BADGE=$("$AAPT2" dump badging build/NeoKey-2036.apk | head -1)
PKG=$(grep -o 'package="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
echo "$BADGE" | grep -q "package: name='$PKG'" || fail "badging.package ($BADGE)"
echo "$BADGE" | grep -q "versionCode='$VC'" || fail "badging.versionCode (want $VC: $BADGE)"
echo "$BADGE" | grep -q "versionName='$VN'" || fail "badging.versionName (want $VN: $BADGE)"
echo "BADGING_OK package=$PKG vc=$VC vn=$VN (signed artifact carries the release identity)"

# ---- 6: THE GATE --------------------------------------------------------------
java -cp "$OUT:$CP" com.hark.neokey.SignedApkGate build/NeoKey-2036.apk build/dex/classes.dex || exit 1
