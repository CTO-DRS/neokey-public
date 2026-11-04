#!/bin/bash
# Deployment-bundle gate (roadmap phase 5, week 2 — week 27) — ci/deploy_gate.sh
#
#   The bundle (ci/build_deploy.sh → build/deploy/neokey-sync-server.jar) is
#   launched as a REAL SEPARATE PROCESS exactly as the deployment operator will:
#     • READY line: SYNC_SERVER_READY port=N host=H spki=64hex — the jar's
#       printed pin == the pinned fixture pin == the gate's anchor constant
#       ("المرجع = المنشور" three ways: jar, keystore, gate)
#     • default bind loopback (host=127.0.0.1 in the READY line); public
#       exposure is an explicit operator decision
#     • restart → the SAME pin (key identity persists; cert rotation is a
#       documented event, not an accident)
#     • a REAL sync round (register → push → new-device login from the wire
#       salt → pull/merge) through the PRODUCTION pinned transport against
#       the standalone process — the bundle serves the contract
#     • health check FROM THE JAR ITSELF (--check): the contract is the probe
#       (grey 404 = alive) over a pinned channel; a rogue pin →
#       SYNCH_HEALTH_FAIL spki_not_pinned
#     • fail-closed boot: wrong password / unknown arg = DEPLOY_REFUSED, no
#       READY, non-zero exit; a missing class from the bundle = build failure
#
#   Verdict: DEPLOYGATE_OK … (exit 0) / DEPLOYGATE_FAILED <reason> (exit 1).
#   JVM-only (zero android.* at runtime) — runs anywhere a JRE exists.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/deploygate; mkdir -p "$OUT"
AJ=""
[ -f tools/android.jar ] && AJ=tools/android.jar
CP=""
[ -n "$AJ" ] && CP="-cp $AJ"
STUBS="tests/stubs/org/json/JSONArray.java"
[ -z "$AJ" ] && STUBS="$STUBS $(find tests/stubs -name '*.java' | tr '\n' ' ')"
SRC="tests/com/hark/neokey/DeployGate.java \
 src/com/hark/neokey/SyncRestTransport.java src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java \
 src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java \
 src/com/hark/neokey/NetGate.java src/com/hark/neokey/Dictionary.java src/com/hark/neokey/ArabicFixes.java \
 src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/Economy.java src/com/hark/neokey/ThemePack.java \
 src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "DEPLOYGATE_FAILED no javac and no ecj.jar"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || { echo "DEPLOYGATE_FAILED compile"; exit 1; }

[ -f tests/tls/arena-server.p12 ] && [ -f tests/tls/rogue-server.p12 ] \
  || { echo "DEPLOYGATE_FAILED fixtures (tests/tls/*.p12 missing)"; exit 1; }

bash ci/build_deploy.sh || { echo "DEPLOYGATE_FAILED bundle build"; exit 1; }

java -cp "$OUT" com.hark.neokey.DeployGate
rc=$?
[ $rc -eq 0 ] && echo "DEPLOYGATE OK" || echo "DEPLOYGATE FAILED (rc=$rc)"
exit $rc
