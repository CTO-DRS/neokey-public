#!/bin/bash
# R8 FULL MODE build (roadmap Phase 0, item C) — same chain as build.sh but the
# D8 step is replaced by R8 (shrink + obfuscate + optimize + dex in one), and the
# mapping is archived for crash symbolication (Play Console "deobfuscation file").
#   tools: aapt2/d8/zipalign/apksigner from ANDROID_SDK (final packaging), plus
#          tools/r8.jar (downloadable: dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar)
#   rules: proguard-rules.pro (keep = manifest components + line numbers only)
# Without ANDROID_SDK this script still proves the shrink step (dex + mapping)
# against tools/android.jar — the final APK assembly stays on the build machine.
set -euo pipefail
cd "$(dirname "$0")/.."

R8JAR="${R8_JAR:-tools/r8.jar}"
[ -f "$R8JAR" ] || { echo "ERROR: $R8JAR missing — see header comment for the download URL" >&2; exit 1; }
AJAR="${R8_ANDROID_JAR:-}"
if [ -z "$AJAR" ]; then
  SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/workspace/work/sdk}}}"
  AJAR=$SDK/android-35/android.jar
  [ -f "$AJAR" ] || AJAR=$SDK/platforms/android-35/android.jar
  [ -f "$AJAR" ] || AJAR=$SDK/android-30/android.jar
  [ -f "$AJAR" ] || { echo "ERROR: no android.jar (set R8_ANDROID_JAR or ANDROID_SDK)" >&2; exit 1; }
fi

# ---- compile (javac when available, else ECJ) ---------------------------------
rm -rf build/r8-classes && mkdir -p build/r8-classes build/release
if [ -f build/gen/com/hark/neokey/R.java ]; then
  SRCS="$(find src build/gen -name '*.java')"   # aapt2 R already generated (build machine)
else
  # offline proof path: generate a compile-time-only R for the two drawables the
  # code references — real builds get the authoritative R from aapt2 instead
  mkdir -p build/r8-gen/com/hark/neokey
  cat > build/r8-gen/com/hark/neokey/R.java <<'EOF'
package com.hark.neokey;
/** Compile-time-only R (offline R8 proof, no aapt2 here). Values never reach a real APK. */
public final class R {
  public static final class drawable { public static final int dev_full = 1; public static final int dev_photo = 2; }
}
EOF
  SRCS="$(find src build/r8-gen -name '*.java')"
  echo "NOTE: no aapt2 R (no SDK) — compile-time stub R generated for the shrink proof"
fi
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -classpath "$AJAR" -d build/r8-classes $SRCS
else
  [ -f tools/ecj.jar ] || { echo "ERROR: no javac and no tools/ecj.jar" >&2; exit 1; }
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -classpath "$AJAR" -d build/r8-classes $SRCS
fi
(cd build/r8-classes && zip -qr ../r8-prog.jar .)

# ---- R8 FULL MODE: shrink + obfuscate + optimize + dex ------------------------
java -cp "$R8JAR" com.android.tools.r8.R8 --release --min-api 21 --lib "$AJAR" \
  --pg-conf proguard-rules.pro --pg-map-output build/release/mapping.txt \
  --output build/r8-dex.zip build/r8-prog.jar
rm -rf build/dex && mkdir -p build/dex
unzip -o -q build/r8-dex.zip -d build/dex
R8D=$(stat -c%s build/dex/classes.dex)
MAPPED=$(grep -c '^com\.hark\.neokey\.' build/release/mapping.txt || true)

# ---- the gate that BITES: every manifest component must keep its exact name ----
# (the system instantiates them by the textual name in AndroidManifest.xml — a
#  rename is a crash on launch, so the build refuses instead of hoping)
PKG=$(grep -o 'package="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
FAILS=0
for C in $(grep -o 'android:name="[^"]*"' AndroidManifest.xml | cut -d'"' -f2 \
           | grep -E '^\.' | sed "s/^\./$PKG./"); do
  if ! grep -q "^$C -> $C:" build/release/mapping.txt; then
    echo "R8_GATE_FAILED: manifest component $C was renamed/removed — add a keep rule to proguard-rules.pro" >&2
    FAILS=$((FAILS+1))
  fi
done
KEPT=$(( $(grep -o 'android:name="[^"]*"' AndroidManifest.xml | cut -d'"' -f2 | grep -cE '^\.' ) - FAILS ))
TOTAL=$(grep -o 'android:name="[^"]*"' AndroidManifest.xml | cut -d'"' -f2 | grep -cE '^\.' )
# budget gate — the shipped dex ceiling, tracked since Phase 0 at 500,000B:
# Phase 3 store week (vc 59) BIT the ceiling exactly as designed: the store UI
# pulled ThemePack+Economy from R8-stripped (zero-byte) into the reachable set
# (+13.4KB measured). Alternatives examined and refused: trimming Writer
# templates / symbol layouts (functional, test-bound content), UI-string
# gutting (unreviewable Arabic quality), deferring the store (the week's
# deliverable). Decision: one documented raise to 520,000B (+4%). The
# entitlements week must live UNDER this ceiling — the next overflow requires
# real trimming, not another raise.
# Phase 3 W2 (vc 60, signed dictionary merge) measured: 513,020 -> 518,200B
# (+5,180B for DictSign + engine merge + gate) — 1,800B remain. W3 entitlements
# must TRIM, not raise.
# Phase 3 W3 (vc 61, entitlements across devices) measured: 518,200 -> 518,744B
# (+544B for the entitlement record type + collect/apply + grantFromSync bridge;
# prediction said it would not fit — it did, so NO raise and the trim lever was
# still pulled: isHex64 deduplicated to one reference). W4 close-out gate: no
# regression under the same ceiling with all features reachable.
[ "$R8D" -le 520000 ] || { echo "R8_GATE_FAILED: dex $R8D > 520000 budget — trim before shipping" >&2; exit 1; }
echo "R8_OK dex=$R8D bytes, mapping=$MAPPED program classes, manifest components kept=$KEPT/$TOTAL"
[ "$FAILS" = "0" ] || exit 1
[ -s build/release/mapping.txt ] || { echo "R8_GATE_FAILED: mapping.txt empty" >&2; exit 1; }

# ---- full APK packaging + signing (build machine only) -------------------------
if [ -n "${ANDROID_SDK:-}" ] || [ -d /workspace/work/sdk ]; then
  SDK="${ANDROID_SDK:-/workspace/work/sdk}"
  BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
  PATH="$BT:$PATH"; export PATH
  AL=$SDK/android-30/android.jar
  VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
  VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
  rm -rf build/res build/base.apk build/unsigned.apk build/aligned.apk && mkdir -p build/res
  aapt2 compile --dir res -o build/res/res.zip
  aapt2 link -I "$AL" --manifest AndroidManifest.xml -o build/base.apk -A assets --java build/gen \
    build/res/res.zip --min-sdk-version 21 --target-sdk-version 35 --version-code "$VC" --version-name "$VN"
  cp build/base.apk build/unsigned.apk
  (cd build/dex && zip -q -u ../unsigned.apk classes.dex)
  zipalign -f -p 4 build/unsigned.apk build/aligned.apk

  # signing — same secret discipline as build.sh: env or vault, never command line
  if [ -z "${NEOKEY_KS_PASS:-}" ]; then
    SECRETS="${NEOKEY_SECRETS:-.secrets/neokey.env}"
    [ -f "$SECRETS" ] && { set -a; . "$SECRETS"; set +a; }
  fi
  if [ -n "${NEOKEY_KS_PASS:-}" ]; then
    NEOKEY_KEYSTORE="${NEOKEY_KEYSTORE:-.secrets/neokey.keystore}"
    NEOKEY_KEY_ALIAS="${NEOKEY_KEY_ALIAS:-neokey}"
    [ -f "$NEOKEY_KEYSTORE" ] || { echo "ERROR: keystore missing — do NOT regenerate; restore backup (docs/SECURITY.md)" >&2; exit 1; }
    apksigner sign --ks "$NEOKEY_KEYSTORE" --ks-key-alias "$NEOKEY_KEY_ALIAS" \
      --ks-pass env:NEOKEY_KS_PASS --key-pass env:NEOKEY_KS_PASS \
      --out build/NeoKey-2036.apk build/aligned.apk
    apksigner verify build/NeoKey-2036.apk
    echo "PACKAGING_OK — signed R8 APK: build/NeoKey-2036.apk"
  else
    echo "PACKAGING_OK — unsigned R8 APK: build/aligned.apk (signing happens in the release stage only)"
  fi
else
  echo "PACKAGING_SKIPPED (no ANDROID_SDK — R8 dex + mapping proven; APK assembly runs on the build machine / release CI)"
fi
