#!/bin/bash
# Plain-JVM tests: clipboard logic (ClipRepoTest + ClipIOTest, 89 tests) + the
# Week-2 hot core (ArabicNorm/ArabicFixes/EmojiHints/EmojiSearch/Dictionary/Decor/Writer
# via CoreTests) — roadmap Phase 0 goal: 89 -> 200+ tests.
# Week 1: portable jar discovery + ECJ fallback (no JDK needed).
#
#   deps:  sqlite-jdbc-3.46.1.0.jar + slf4j-api-1.7.36.jar
#          (curl -sSfL -o sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar)
#   usage: tests/run.sh          (auto-finds jars in $NEOKEY_TOOLS, ./tools, /workspace/work)
set -euo pipefail
cd "$(dirname "$0")/.."

# ---- locate dependency jars ------------------------------------------------
find_jar() { # $1 = jar filename
  local d
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}
SQLITE_JAR=$(find_jar sqlite-jdbc.jar) || { echo "ERROR: sqlite-jdbc.jar not found — put it in ./tools or set NEOKEY_TOOLS" >&2; exit 1; }
SLF4J_JAR=$(find_jar slf4j-api.jar)  || SLF4J_JAR=""
JAR=$SQLITE_JAR:${SLF4J_JAR:-$SQLITE_JAR}
[ -n "$SLF4J_JAR" ] && JAR=$SQLITE_JAR:$SLF4J_JAR
# week 31 (phase 6 W2): the signed-artifact classes need tools/apksig.jar (a
# documented download, docs/R8.md). Present -> they compile + their suite runs;
# absent -> an explicit skip line (the SIGNEDAPK gate hard-requires the jar
# anyway, so a pipeline run that matters always has it).
APKSIG_JAR=$(find_jar apksig.jar) || APKSIG_JAR=""
APKSIG_FILES=""; APKSIG_SUITE=""
if [ -n "$APKSIG_JAR" ]; then
  JAR="$JAR:$APKSIG_JAR"
  APKSIG_FILES=" $(find tests -name 'SignedApk*.java' | tr '\n' ' ')"
  APKSIG_SUITE="com.hark.neokey.SignedApkTest"
fi

# ---- measurement integrity (week 42 fix #2): the perf budgets in the vault
# suites (the KDF wall-time budget, the stamp/unseal throughput) measure
# PRODUCTION JIT throughput — an inherited JAVA_TOOL_OPTIONS (e.g. a
# TieredStopAtLevel override from the operator's shell or a CI wrapper)
# silently biases them (measured live: the KDF budget missed at 748ms vs 400
# under C1-only). The runner strips inherited JVM overrides so the budgets
# always measure what ships.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS

# ---- compile: javac when available, else Eclipse ECJ on plain JRE ----------
rm -rf build/test && mkdir -p build/test
SRC="src/com/hark/neokey/ClipItem.java src/com/hark/neokey/ClipRepo.java src/com/hark/neokey/ClipNorm.java src/com/hark/neokey/ClipText.java src/com/hark/neokey/ClipIO.java \
 src/com/hark/neokey/Dictionary.java src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/EmojiHints.java \
 src/com/hark/neokey/EmojiSearch.java src/com/hark/neokey/Writer.java src/com/hark/neokey/Decor.java src/com/hark/neokey/Integrity.java src/com/hark/neokey/CrashReport.java src/com/hark/neokey/Feedback.java src/com/hark/neokey/Correction2.java src/com/hark/neokey/Exceptions.java src/com/hark/neokey/SendPreview.java \
 src/com/hark/neokey/NQF.java src/com/hark/neokey/NNet.java src/com/hark/neokey/Predictor.java \
 src/com/hark/neokey/Engine.java src/com/hark/neokey/Engines.java \
 src/com/hark/neokey/Dialect.java src/com/hark/neokey/SmartSwitch.java src/com/hark/neokey/Assistant.java src/com/hark/neokey/NetGate.java \
 src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SyncServerSim.java \
 src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java \
 src/com/hark/neokey/SyncRestTransport.java \
 src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java \
 src/com/hark/neokey/ThemePack.java src/com/hark/neokey/Economy.java \
 src/com/hark/neokey/NqfPort.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/DictSign.java \
 $(find tests -name '*.java' -not -name 'SignedApk*')$APKSIG_FILES"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "$JAR" -d build/test $SRC
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "ERROR: no javac and no ecj.jar in ./tools" >&2; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$JAR" -d build/test $SRC
fi

# ---- run: suite 1 (clipboard) + suite 2 (hot core) + suite 3 (dialect C1) + suite 4 (C2+D1 week 6) ----
java -cp build/test:$JAR com.hark.neokey.ClipRepoTest
java -cp build/test:$JAR com.hark.neokey.EngineHarness
java -cp build/test:$JAR com.hark.neokey.CoreTests
java -cp build/test:$JAR com.hark.neokey.DialectTest
java -cp build/test:$JAR com.hark.neokey.SmartSwitchTest
java -cp build/test:$JAR com.hark.neokey.AssistantTest
java -cp build/test:$JAR com.hark.neokey.NetGateTest
java -cp build/test:$JAR com.hark.neokey.SyncVaultTest
java -cp build/test:$JAR com.hark.neokey.SyncClientTest
java -cp build/test:$JAR com.hark.neokey.ThemePackTest
java -cp build/test:$JAR com.hark.neokey.EconomyTest
java -cp build/test:$JAR com.hark.neokey.NqfPortTest
java -cp build/test:$JAR com.hark.neokey.CommunityDictTest
java -cp build/test:$JAR com.hark.neokey.StoreUiTest
java -cp build/test:$JAR com.hark.neokey.PaymentProviderTest
java -cp build/test:$JAR com.hark.neokey.PlayProviderTest
java -cp build/test:$JAR com.hark.neokey.SyncServerPackTest
java -cp build/test:$JAR com.hark.neokey.PreflightTest
java -cp build/test:$JAR com.hark.neokey.RebuildDrillTest
java -cp build/test:$JAR com.hark.neokey.EvAuditTest
java -cp build/test:$JAR com.hark.neokey.OpVerifyTest
java -cp build/test:$JAR com.hark.neokey.PubSyncTest
java -cp build/test:$JAR com.hark.neokey.DictSignTest
java -cp build/test:$JAR com.hark.neokey.EntitlementTest
java -cp build/test:$JAR com.hark.neokey.SyncServerContractTest
java -cp build/test:$JAR com.hark.neokey.SpkiPinsTest
java -cp build/test:$JAR com.hark.neokey.ProvisionTest
java -cp build/test:$JAR com.hark.neokey.UploadBundleTest
if [ -n "${APKSIG_SUITE:-}" ]; then
  java -cp build/test:$JAR $APKSIG_SUITE
else
  echo "SKIP SignedApkTest (tools/apksig.jar absent — see docs/R8.md; the SIGNEDAPK gate requires it)"
fi
