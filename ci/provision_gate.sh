#!/bin/bash
# Provisioning + rotation drill (phase 6, week 1 — W30) — ci/provision_gate.sh
#
#   The documented pending "نشر خادم مزامنة حي بـ TLS — ما يبقى بالضبط: النطاق،
#   شهادة CA تجارية، وضبط الدبابيس عند NetGate.setSyncPins" (PHASE5-W4-REPORT §5
#   + store-readiness.pendingOutsideThisEnvironment) keeps ONE locally-closable
#   slice after phase 5: the pin-setting DECISION PROCEDURE itself. THIS is its
#   automated rehearsal, run against committed stand-in fixtures:
#
#     1. compile guard      — Provision + ProvisionGate (+ the wire deps)
#     2. fixture guard      — tests/provision/ stand-ins + the arena anchor
#     3. THE GATE           — ProvisionGate: THE ANCHOR IS THE FIRST BARRIER
#                             (the committed arena fixture re-derives the pinned
#                             literal EXPECTED_ARENA_PIN — swap the file and the
#                             anchor breaks on the first run), reference
#                             provisioning with an operator-CA pin, three-way
#                             pin agreement (decision == statement == gate
#                             recompute), deterministic statement, the rotation
#                             drill (same-key renewal = same pin; new key =
#                             new pin refused INSIDE the handshake until re-pin,
#                             then a green round) and eleven designed negatives.
#     4. RECOVERY SIMULATION— the reference keystore is deployed into
#                             build/provision/deployed.p12 (chmod 600) with a
#                             PROVISION-SOURCE note (the real chain comes from
#                             the operator's CA at deployment time)
#     5. THE DRILL          — the Provision CLI (the operator's command, PEM CA
#                             accepted) on the deployed copy against the pinned
#                             reference pin16: the decision the operator makes
#                             with the real chain.
#
#   Verdict: PROVISIONGATE_OK checks=N negatives=M/M anchor_pin16=… pin16=…
#            rotation=stable+rotate renew_days_left=…
#            / PROVISIONGATE_FAILED <reason> (exit 1)
#   JVM-only — runs anywhere a JRE exists; zero app code, the dex budget is
#   not spent (tests/ only).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/provision-gate; rm -rf "$OUT"; mkdir -p "$OUT"
EXPECTED_ARENA_PIN16=e67e815c3494a3c5   # أول 16 محرفاً من دبوس الحلبة المثبت حرفياً (W26)
EXPECTED_PIN16=b1b209b32486cf49         # أول 16 محرفاً من دبوس مخزن النشر البديل الملتزم («المرجع = المنشور»)

fail() { echo "PROVISIONGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
CP=""
AJ=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -f "$d/android.jar" ] && { AJ="$d/android.jar"; break; }
done
[ -n "$AJ" ] && CP="-cp $AJ"
STUBS="tests/stubs/org/json/JSONArray.java"
[ -z "$AJ" ] && STUBS="$STUBS $(find tests/stubs -name '*.java' | tr '\n' ' ')"
SRC="tests/com/hark/neokey/Provision.java tests/com/hark/neokey/ProvisionGate.java \
 tests/com/hark/neokey/SyncServer.java tests/com/hark/neokey/TlsGate.java \
 src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java \
 src/com/hark/neokey/SyncRestTransport.java src/com/hark/neokey/SyncVault.java \
 src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/NetGate.java \
 src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/Economy.java src/com/hark/neokey/ThemePack.java \
 src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java \
 src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: fixture guard ---------------------------------------------------------
for fx in ca ca2 server renew rotate wrongdom expired-leaf foreign; do
  [ -f "tests/provision/$fx.p12" ] || fail "fixtures (tests/provision/$fx.p12 missing)"
done
[ -f "tests/tls/arena-server.p12" ] || fail "fixtures (tests/tls/arena-server.p12 missing — the anchor)"

# ---- 3: THE GATE — the anchor is the FIRST barrier, then every negative bites -
java -cp "$OUT" com.hark.neokey.ProvisionGate || exit 1

# ---- 4: the recovery simulation (the operator's deploy step, rehearsed) -------
mkdir -p build/provision
cp tests/provision/server.p12 build/provision/deployed.p12
chmod 600 build/provision/deployed.p12
{
  echo "provision stand-in deploy — $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "source: tests/provision/server.p12 (committed fixture generated ONLY for this drill;"
  echo "        the real chain comes from the operator's CA at deployment time)"
  echo "decision procedure: ci/provision_gate.sh → Provision CLI (this run) — same class,"
  echo "                    same checks, same pinned-pin comparison the operator will"
  echo "                    run with the real chain; the emitted NetGate.setSyncPins"
  echo "                    line is the ONE deployment-time action (W26 contract)."
} > build/provision/PROVISION-SOURCE.txt

# ---- 5: THE DRILL — the operator's CLI on the deployed copy -------------------
PWP=prov-pass-2026   # drill password — documented ON PURPOSE in tests/provision/README.md
keytool -exportcert -keystore tests/provision/ca.p12 -alias nk-ca -storepass "$PWP" \
  -rfc -file build/provision/expected-ca.pem >/dev/null 2>&1 || fail "drill (expected-CA export)"
DRILL_OUT=$(java -cp "$OUT" com.hark.neokey.Provision \
      build/provision/deployed.p12 prov-pass-2026 sync sync.neokey.test \
      --ca build/provision/expected-ca.pem --statement build/provision/provision-statement.json 2>&1)
echo "$DRILL_OUT"
echo "$DRILL_OUT" | grep -q '^PROVISION_OK' || fail "drill ($(echo "$DRILL_OUT" | grep -m1 PROVISION_FAIL || echo 'no verdict'))"
DRILL_PIN16=$(echo "$DRILL_OUT" | grep -oE 'pin16=[0-9a-f]{16}' | head -1 | cut -d= -f2)
DRILL_DAYS=$(echo "$DRILL_OUT" | grep -oE 'days_left=[0-9]+' | head -1 | cut -d= -f2)
[ -f build/provision/provision-statement.json ] || fail "drill (statement not written)"
grep -q "\"pin16\": \"$DRILL_PIN16\"" build/provision/provision-statement.json || fail "drill (statement pin16 mismatch)"
[ "$DRILL_PIN16" = "$EXPECTED_PIN16" ] || fail "drill.pin16 ($DRILL_PIN16 != published $EXPECTED_PIN16 — المرجع = المنشور)"
echo "PROVISION_DRILL_OK deployed=build/provision/deployed.p12 pin16=${DRILL_PIN16:-none} days_left=${DRILL_DAYS:-0} checks=decision+chain+ca+san+validity+pin+statement"
echo "PROVISION_GATE=OK"
