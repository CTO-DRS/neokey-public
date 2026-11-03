#!/bin/bash
# REST reference-server gate (roadmap phase 4, week 2 — week 23) — ci/rest_gate.sh
#
#   The contract docs/SYNC-REST.md proven ON THE WIRE, not by class calls:
#     • REAL HTTP server (SyncServer, JDK HttpServer) on 127.0.0.1 ephemeral port
#     • the PRODUCTION client transport (SyncRestTransport over HttpURLConnection)
#     • two real devices: register → sealed push → new-device login from the wire
#       salt → pull/open/merge → plan → grantFromSync by pin → install FROM THE
#       CATALOG (no binary bytes ever travel the sync)
#     • reverse round: B's purchase crosses back to A; ledger closes (restore ≠ buy)
#     • contract conformance from the server wire log: the 5 exact path shapes,
#       b64url-only ids, octet-stream bodies, sorted JSON array, 201/409/404/413/507
#     • grey 404 semantics: fresh account list = empty, no error
#     • quotas on the wire: quota_free → http_507, blob cap → http_413 (holds even
#       when the operator sets VIP — the class never comes from the client)
#     • zero-knowledge MEASURED on the real server store (dump()): entropy ≥ 7.9,
#       NSF1 at sealed-blob starts exactly, no Arabic/record-kinds/passphrase/pack-ids
#     • wire perf: p50 push/pull over 7 real rounds
#     • designed negatives with exact tags: unknown-account login (http_404), wrong
#       passphrase (account_mismatch), NetGate master off (netgate_master_off),
#       foreign arena base refused at construction (not_arena ×2)
#
#   Verdict: RESTGATE_OK … (exit 0) / RESTGATE_FAILED <reason> (exit 1).
#   JVM-only (zero android.* at runtime) — runs anywhere a JDK exists.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/restgate; mkdir -p "$OUT"
# StoreUi reuses ThemePack's strict reviewer + Economy's ledger; SyncState reuses
# Dictionary.norm. Compiles against android.jar when present (build machine), else
# the JVM stubs. The org.json stub is ALWAYS compiled: the production transport
# parses the server's JSON array with it on the pure JVM (android provides the
# real class on device).
AJ=""
[ -f tools/android.jar ] && AJ=tools/android.jar
CP=""
[ -n "$AJ" ] && CP="-cp $AJ"
STUBS="tests/stubs/org/json/JSONArray.java"
[ -z "$AJ" ] && STUBS="$STUBS $(find tests/stubs -name '*.java' | tr '\n' ' ')"
SRC="tests/com/hark/neokey/SyncServer.java tests/com/hark/neokey/RestGate.java \
 src/com/hark/neokey/SyncRestTransport.java src/com/hark/neokey/SyncVault.java \
 src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/NetGate.java \
 src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/Economy.java src/com/hark/neokey/ThemePack.java \
 src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "RESTGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || { echo "RESTGATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.RestGate
rc=$?
[ $rc -eq 0 ] && echo "RESTGATE OK" || echo "RESTGATE FAILED (rc=$rc)"
exit $rc
