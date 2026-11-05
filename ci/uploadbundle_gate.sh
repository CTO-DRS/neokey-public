#!/bin/bash
# uploadbundle_gate.sh — week 32 (phase 6 W3) — ci/uploadbundle_gate.sh
#
#   The documented pending "حسابات المتاجر + الرفع الفعلي" keeps the account
#   and the click outside this environment — but EVERYTHING ELSE about the
#   upload is now one verifiable bundle assembled from inputs that are already
#   proven by their own gates:
#
#     1. compile guard      — UploadBundle + UploadBundleGate + PlayGate (one
#                             listing parser, one derivation)
#     2. THE ANCHOR FIRST   — sha256(build/NeoKey-2036.apk) == the apkSha256
#                             pinned in build/release/signedapk-statement.json
#                             (W31, «المرجع = المنشور»): the bundle carries THE
#                             proven artifact, never a rebuild — a swapped or
#                             rebuilt APK breaks here, before anything assembles
#     3. ASSEMBLE           — UploadBundle assemble: signed APK + mapping +
#                             per-locale listing (re-measured by PlayGate) +
#                             listing.json with uiEvidence coupled to LIVE
#                             assets/ui_en.txt keys + data-safety.json DERIVED
#                             from the manifest's actually-declared permissions
#                             (duplicates refused, no answer without a declared
#                             permission) + privacy policy + staged rollout plan
#                             + deterministic statement (neokey-uploadbundle-1)
#     4. badging            — aapt2 dump badging on the BUNDLE's copy: package +
#                             versionCode + versionName exact (the console sees
#                             what the manifest says, byte-for-byte)
#     5. THE GATE           — UploadBundleGate: 14 checks (members, identity,
#                             schema, the W31 anchor chain, listing limits and
#                             true annotations, ui coupling, safety derivation
#                             both directions with line-true citations,
#                             collected=false everywhere, privacy consistency,
#                             staged rollout, mapping, determinism, purity) +
#                             SIX designed negatives each biting its exact tag,
#                             restore-inside-run, green re-verify after each.
#
#   Verdict: UPLOADBUNDLEGATE_OK checks=14 negatives=6/6 members=7 evidence=8
#            vc=73 vn=2.6 apk_sha256=… / UPLOADBUNDLEGATE_FAILED <reason> (exit 1)
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "UPLOADBUNDLEGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
OUT=build/uploadbundle-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/UploadBundle.java tests/com/hark/neokey/UploadBundleGate.java tests/com/hark/neokey/PlayGate.java src/com/hark/neokey/Feedback.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE ANCHOR IS THE FIRST BARRIER ---------------------------------------
# The bundle's APK must be THE artifact the signedapk gate verified and pinned,
# byte-for-byte — checked BEFORE anything is assembled (the W30/W31 lesson:
# «المرساة أول الحواجز» — a swap bites at the anchor with its own tag).
[ -f build/NeoKey-2036.apk ] || fail "anchor.apk.missing (run the signedapk stage first)"
[ -f build/release/signedapk-statement.json ] || fail "anchor.statement.missing (the signedapk statement pins the artifact)"
APK_SHA=$(sha256sum build/NeoKey-2036.apk | cut -d' ' -f1)
PINNED_SHA=$(grep -oE '"apkSha256": "[0-9a-f]{64}"' build/release/signedapk-statement.json | grep -oE '[0-9a-f]{64}')
[ -n "$PINNED_SHA" ] || fail "anchor.statement.sha (signedapk statement carries no apkSha256)"
[ "$APK_SHA" = "$PINNED_SHA" ] || fail "anchor.apk.sha (artifact sha256=$APK_SHA != pinned $PINNED_SHA — the bundle refuses a rebuilt or swapped APK)"
echo "ANCHOR_OK build/NeoKey-2036.apk sha256 == signedapk-statement.apkSha256 (${APK_SHA:0:16}..)"

# ---- 3: assemble ---------------------------------------------------------------
rm -rf build/upload/bundle
java -cp "$OUT" com.hark.neokey.UploadBundle assemble build/upload/bundle || fail "assemble"

# ---- 4: badging — what the console will see, from the BUNDLE's copy -----------
AAPT2=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -x "$d/aapt2" ] && { AAPT2="$d/aapt2"; break; }
done
[ -n "$AAPT2" ] || fail "badging (aapt2 missing)"
BADGE=$("$AAPT2" dump badging build/upload/bundle/NeoKey-2036.apk | head -1)
PKG=$(grep -o 'package="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
echo "$BADGE" | grep -q "package: name='$PKG'" || fail "badging.package ($BADGE)"
echo "$BADGE" | grep -q "versionCode='$VC'" || fail "badging.versionCode (want $VC: $BADGE)"
echo "$BADGE" | grep -q "versionName='$VN'" || fail "badging.versionName (want $VN: $BADGE)"
echo "BADGING_OK bundle package=$PKG vc=$VC vn=$VN"

# ---- 5: THE GATE ----------------------------------------------------------------
java -cp "$OUT" com.hark.neokey.UploadBundleGate build/upload/bundle || exit 1
