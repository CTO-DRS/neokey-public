#!/bin/bash
# TLS-SPKI-pinning gate (roadmap phase 5, week 1 — week 26) — ci/tls_gate.sh
#
#   The locally-closable slice of the documented pending "live TLS sync server
#   deployment": the CLIENT TRUST CONTRACT measured ON THE WIRE, not by class
#   calls:
#     • REAL TLS server (SyncServer.startTls, JDK HttpsServer) on 127.0.0.1
#     • the PRODUCTION client transport with the new pinned constructor
#       (SyncRestTransport(base, SpkiPins, anchor) over HttpsURLConnection)
#     • the PIN ANCHOR ("المرجع = المنشور"): the published fixture cert's SPKI
#       sha256 must re-derive to EXPECTED_ARENA_PIN byte-exactly — any fixture
#       swap breaks the anchor on the first run even if the swap is valid TLS
#     • full two-device sync round over real TLS: register → NSF1 push →
#       new-device login from the wire salt → pull/open/merge → grantFromSync
#       by pin → install FROM THE CATALOG; zero-knowledge measured on the real
#       server store above TLS (entropy ≥ 7.90, no passphrase/dict/kind bytes)
#     • MITM with a rogue cert (same CN, different key): the HANDSHAKE refuses
#       before any request byte leaves ("network" at transport level, exact
#       "spki_not_pinned" from the TrustManager); a trusted-but-unpinned
#       attacker is refused by the pin alone; the rogue store stays EMPTY
#     • protocol floor TLSv1.2 (measured from the live session), cipher non-null
#     • p50 full handshake (fresh SSLContext per connection — no session reuse)
#     • fail-closed constructors: no pins / bad hex / dup pin / http into the
#       TLS arena ctor / real domain into the arena ctor / missing trust anchor /
#       production https without NetGate pins ("https_unpinned")
#     • architectural purity: SpkiPins.java + PinnedTls.java are zero android.*
#
#   Verdict: TLSGATE_OK … (exit 0) / TLSGATE_FAILED <reason> (exit 1).
#   JVM-only (zero android.* at runtime) — runs anywhere a JDK exists.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/tlsgate; mkdir -p "$OUT"
# Same dependency set as the REST gate (StoreUi reuses ThemePack/Economy,
# SyncState reuses Dictionary) + the two new pure TLS classes.
AJ=""
[ -f tools/android.jar ] && AJ=tools/android.jar
CP=""
[ -n "$AJ" ] && CP="-cp $AJ"
STUBS="tests/stubs/org/json/JSONArray.java"
[ -z "$AJ" ] && STUBS="$STUBS $(find tests/stubs -name '*.java' | tr '\n' ' ')"
SRC="tests/com/hark/neokey/SyncServer.java tests/com/hark/neokey/TlsGate.java tests/com/hark/neokey/SpkiPinsTest.java \
 src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java \
 src/com/hark/neokey/SyncRestTransport.java src/com/hark/neokey/SyncVault.java \
 src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/NetGate.java \
 src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/Economy.java src/com/hark/neokey/ThemePack.java \
 src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "TLSGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || { echo "TLSGATE_FAILED compile"; exit 1; }

[ -f tests/tls/arena-server.p12 ] && [ -f tests/tls/rogue-server.p12 ] \
  || { echo "TLSGATE_FAILED fixtures (tests/tls/*.p12 missing)"; exit 1; }

# The unit suite's RESULT line is NOT echoed into the pipeline log — the tests
# stage already counts it once (a second RESULT line would double-count
# TESTS_TOTAL). Failures still surface: the exact FAIL lines are printed.
if ! java -cp "$OUT" com.hark.neokey.SpkiPinsTest > /tmp/spki_unit.txt 2>&1; then
  echo "TLSGATE_FAILED unit (SpkiPinsTest)"
  grep -E "^FAIL|Exception" /tmp/spki_unit.txt | head -8
  exit 1
fi
echo "SPKI_UNIT_OK $(grep -m1 -oE '[0-9]+ passed, [0-9]+ failed' /tmp/spki_unit.txt) (counted once — in the tests stage)"
java -cp "$OUT" com.hark.neokey.TlsGate
rc=$?
[ $rc -eq 0 ] && echo "TLSGATE OK" || echo "TLSGATE FAILED (rc=$rc)"
exit $rc
