#!/bin/bash
# Builds a signed Android App Bundle (.aab) for Google Play. Run build.sh first (needs build/dex and build/res).
#
# SECURITY (incident NK-SEC-001, fixed Week 1 of Roadmap Phase 0):
#   Secrets load from env or .secrets/neokey.env — never hardcoded here.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${NEOKEY_KS_PASS:-}" ]; then
  SECRETS="${NEOKEY_SECRETS:-.secrets/neokey.env}"
  if [ -f "$SECRETS" ]; then
    set -a; . "$SECRETS"; set +a
  fi
fi
: "${NEOKEY_KS_PASS:?ERROR: signing password not set. source .secrets/neokey.env or export NEOKEY_KS_PASS (see docs/SECURITY.md)}"
NEOKEY_KEYSTORE="${NEOKEY_KEYSTORE:-.secrets/neokey.keystore}"
NEOKEY_KEY_ALIAS="${NEOKEY_KEY_ALIAS:-neokey}"
[ -f "$NEOKEY_KEYSTORE" ] || { echo "ERROR: keystore not found at $NEOKEY_KEYSTORE — do NOT regenerate (breaks update continuity); restore the backup. docs/SECURITY.md" >&2; exit 1; }

SDK="${ANDROID_SDK:-/workspace/work/sdk}"
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
rm -rf build/aab && mkdir -p build/aab/base
aapt2 link --proto-format -I $SDK/android-30/android.jar --manifest AndroidManifest.xml \
  -o build/aab/proto.apk -A assets build/res/res.zip --min-sdk-version 21 --target-sdk-version 35 \
  --version-code $VC --version-name $VN
cd build/aab/base
unzip -q ../proto.apk
mkdir -p manifest dex && mv AndroidManifest.xml manifest/ && cp ../../dex/classes.dex dex/
zip -q -r ../base.zip .
cd ..
java -jar $SDK/bundletool.jar build-bundle --modules=base.zip --output=NeoKey.aab --overwrite
jarsigner -keystore "$NEOKEY_KEYSTORE" -storepass:env NEOKEY_KS_PASS -keypass:env NEOKEY_KS_PASS \
  -sigalg SHA256withRSA -digestalg SHA-256 NeoKey.aab "$NEOKEY_KEY_ALIAS" >/dev/null
java -jar $SDK/bundletool.jar validate --bundle=NeoKey.aab >/dev/null && echo AAB_OK
