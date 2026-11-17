#!/bin/bash
# Compile gate — full Android build WITHOUT signing (no secrets needed).
# Runs the same chain as build.sh: aapt2 -> javac/ECJ -> d8 -> zipalign, then stops.
# CI (GitHub Actions / any machine) uses this to prove a clean merge compiles,
# while the signed release stays on the release workflow with real secrets.
# Requires: ANDROID_SDK (or ANDROID_SDK_ROOT/ANDROID_HOME) with platform android-36 and build-tools.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/workspace/work/sdk}}}"
[ -d "$SDK" ] || { echo "BUILD_UNSIGNED_SKIPPED (no SDK at $SDK — set ANDROID_SDK; signed releases run on the release machine/CI)" >&2; exit 42; }

# newest build-tools wins (aapt2, d8, zipalign)
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
[ -n "$BT" ] || { echo "ERROR: no build-tools under $SDK" >&2; exit 1; }
PATH="$BT:$PATH"; export PATH
AJ=$( [ -f "$SDK/android-36/android.jar" ] && echo "$SDK/android-36/android.jar" || echo "$SDK/platforms/android-36/android.jar" )
[ -f "$AJ" ] || { echo "ERROR: platform android-36 required (got $AJ)" >&2; exit 1; }
AL="$AJ"   # W130: one platform level everywhere (the old 35/30 split is gone)

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

# surgical clean — never wipe build/release gates state
rm -rf build/res build/gen build/classes build/dex build/base.apk build/unsigned.apk
mkdir -p build/res build/gen build/classes build/dex
aapt2 compile --dir res -o build/res/res.zip
aapt2 link -I "$AL" --manifest AndroidManifest.xml -o build/base.apk -A assets --java build/gen \
  build/res/res.zip --min-sdk-version 21 --target-sdk-version 36 --version-code "$VC" --version-name "$VN"

if command -v javac >/dev/null 2>&1; then
  javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -classpath "$AJ" -d build/classes $(find src build/gen -name '*.java')
else
  ECJ_JAR=$( [ -f tools/ecj.jar ] && echo tools/ecj.jar || true)
  [ -n "$ECJ_JAR" ] || { echo "ERROR: no javac and no tools/ecj.jar" >&2; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -classpath "$AJ" -d build/classes $(find src build/gen -name '*.java')
fi

D8JAR=""
[ -f "$BT/r8.jar" ] && D8JAR="$BT/r8.jar"
[ -z "$D8JAR" ] && [ -f "$SDK/r8.jar" ] && D8JAR="$SDK/r8.jar"
if [ -n "$D8JAR" ]; then
  java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 21 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
else
  d8 --release --min-api 21 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
fi

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/NeoKey-unsigned-aligned.apk
echo "BUILD_UNSIGNED_OK (NeoKey-unsigned-aligned.apk, vc=$VC/$VN) — signing happens in the release stage only"
