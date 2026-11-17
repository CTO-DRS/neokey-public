#!/bin/bash
# Manual Android build (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# SECURITY (incident NK-SEC-001, fixed Week 1 of Roadmap Phase 0):
#   Signing secrets are NO LONGER in this file. They load, in order, from:
#     1. Environment:  NEOKEY_KS_PASS, NEOKEY_KEYSTORE, NEOKEY_KEY_ALIAS
#     2. Secrets file: $NEOKEY_SECRETS  (default: .secrets/neokey.env, chmod 600)
#   The keystore file itself lives OUTSIDE version control (.gitignore).
#   If it is ever lost, Google Play update continuity is lost with it — keep
#   an encrypted off-machine backup (see docs/SECURITY.md).
set -euo pipefail
cd "$(dirname "$0")"

# ---- secrets loading (never print values) ---------------------------------
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

# ---- toolchain -------------------------------------------------------------
SDK="${ANDROID_SDK:-/workspace/work/sdk}"
AJAR=$SDK/android-36/android.jar

# versionCode/versionName come from AndroidManifest.xml (single source of truth —
# the old hardcoded 56/1.0 here was a release-engineering drift hazard)
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

# surgical clean (NOT `rm -rf build` — that would wipe build/release gates state
# and build/test classes used by the CI pipeline stages)
rm -rf build/res build/gen build/classes build/dex build/aab build/base.apk build/unsigned.apk build/aligned.apk
mkdir -p build/res build/gen build/classes build/dex
aapt2 compile --dir res -o build/res/res.zip
aapt2 link -I $SDK/android-36/android.jar --manifest AndroidManifest.xml -o build/base.apk \
  -A assets --java build/gen build/res/res.zip --min-sdk-version 21 --target-sdk-version 36 \
  --version-code $VC --version-name $VN
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -classpath $AJAR \
  -d build/classes $(find src build/gen -name '*.java')
java -cp $SDK/r8.jar com.android.tools.r8.D8 --release --min-api 21 --lib $AJAR \
  --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
# password passed via env: reference — never on the command line, never in logs
apksigner sign --ks "$NEOKEY_KEYSTORE" --ks-key-alias "$NEOKEY_KEY_ALIAS" \
  --ks-pass env:NEOKEY_KS_PASS --key-pass env:NEOKEY_KS_PASS \
  --out build/NeoKey-2036.apk build/aligned.apk
apksigner verify build/NeoKey-2036.apk && echo BUILD_OK
