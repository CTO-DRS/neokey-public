#!/bin/bash
# build_signedapk.sh — week 31 (phase 6 W2): the FULL APK assembly, offline.
#   aapt2 compile+link (real resources + real R) → align_apk.py (dex injected,
#   arsc stored + 4-byte aligned) → build/sapk/aligned.apk.
#   NO SECRETS at this step — signing is the owner's command #2 (SignedApk
#   sign), run by ci/signedapk_gate.sh in CI on the drill stand-in.
# Tools (see docs/R8.md): tools/aapt2 + tools/android.jar + tools/apksig.jar.
# Needs the pipeline R8 artifact build/dex/classes.dex (ci/build_r8.sh runs first).
set -euo pipefail
cd "$(dirname "$0")/.."

AAPT2=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -x "$d/aapt2" ] && { AAPT2="$d/aapt2"; break; }
done
[ -n "$AAPT2" ] || { echo "SIGNEDAPK_SKIPPED (no tools/aapt2 — see docs/R8.md)" >&2; exit 42; }
AJ=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -f "$d/android.jar" ] && { AJ="$d/android.jar"; break; }
done
[ -n "$AJ" ] || { echo "SIGNEDAPK_SKIPPED (no tools/android.jar — see docs/R8.md)" >&2; exit 42; }
[ -f tools/apksig.jar ] || { echo "SIGNEDAPK_SKIPPED (no tools/apksig.jar — see docs/R8.md)" >&2; exit 42; }
[ -f build/dex/classes.dex ] || { echo "ERROR: build/dex/classes.dex missing — the pipeline R8 artifact (ci/build_r8.sh) must run first; the artifact carries THE shipped dex, never a rebuild" >&2; exit 1; }

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

rm -rf build/sapk && mkdir -p build/sapk/gen
"$AAPT2" compile --dir res -o build/sapk/res.zip
"$AAPT2" link -I "$AJ" --manifest AndroidManifest.xml -o build/sapk/base.apk -A assets \
  --java build/sapk/gen build/sapk/res.zip \
  --min-sdk-version 21 --target-sdk-version 35 --version-code "$VC" --version-name "$VN"
echo "AAPT2_LINK_OK base=build/sapk/base.apk vc=$VC vn=$VN (the full resource link — the step that was silently broken since week 22's trim dropped the identity strings)"

python3 ci/align_apk.py build/sapk/base.apk build/dex/classes.dex build/sapk/aligned.apk
