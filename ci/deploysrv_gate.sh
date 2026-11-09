#!/bin/bash
# deploysrv_gate.sh — week 35 (phase 7 W2) — ci/deploysrv_gate.sh
#
#   The documented pending "live sync server deployment with TLS on a real
#   domain" keeps the domain + the CA cert + the two automated commands
#   outside this environment — this week closes the last local slice: WHAT
#   GETS TRANSFERRED TO THE SERVER is one verifiable pack, and WHAT GETS RUN
#   IS THE TESTED THING:
#
#     1. compile guard      — SyncServerPack (the assembler) + DeploySrvGate
#     2. THE JAR, FRESH     — ci/build_deploy.sh re-runs (the reference
#                             contract class, proven on the wire by W23/W26/W27)
#     3. THE PACK           — SyncServerPack assemble: the jar + generated
#                             start/stop/health scripts + the systemd unit +
#                             INSTALL.md with the LITERAL copy-paste commands
#                             (including THE TWO automated ones: Provision →
#                             NetGate.setSyncPins) + a deterministic
#                             neokey-deploysrv-1 statement (no timestamps)
#     4. THE ANCHOR         — the jar's --print-spki on the arena keystore ==
#                             the published arena pin16 e67e815c3494a3c5
#                             («المرجع = المنشور» — the jar carries the SAME
#                             identity the W26/W30 gates published)
#     5. THE BOOT           — the PACK'S OWN COPY runs as a separate process
#                             exactly as the operator will (serve, arena
#                             keystore): SYNC_SERVER_READY, then a FULL
#                             contract round over the SPKI-pinned channel
#                             (salt/idempotent/conflict/get/unknown/length/
#                             blob/get/list/foreign/traversal), health via
#                             the jar's own --check, a rogue pin refused,
#                             SIGTERM exits, the port goes silent
#     6. FOUR PACK NEGATIVES— removed member, tampered statement sha, script
#                             jar-name drift, dropped pin-set command — each
#                             biting its exact tag on a dirty COPY, then the
#                             real pack re-verified green
#
#   Verdict: DEPLOYSRVGATE_OK checks=N negatives=4/4 jar_sha256=<16hex>
#            members=N health_ms=<ms> vc=1 vn=0.1.0
#            / DEPLOYSRVGATE_FAILED <reason> (exit 1). Keys appended to
#            gates.env idempotently (the week-33 hardening).
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "DEPLOYSRVGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
OUT=build/deploysrv-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/SyncServerPack.java tests/com/hark/neokey/DeploySrvGate.java \
 src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE JAR, FRESH ----------------------------------------------------------
[ -f tests/tls/arena-server.p12 ] || fail "fixtures (tests/tls/arena-server.p12 missing — the anchor)"
bash ci/build_deploy.sh > /tmp/deploysrv-build.log 2>&1 || { cat /tmp/deploysrv-build.log | tail -5; fail "jar build"; }
grep -q "DEPLOY_BUILD_OK" /tmp/deploysrv-build.log || fail "jar build (no DEPLOY_BUILD_OK)"
JAR_SHA=$(sha256sum build/deploy/neokey-sync-server.jar | cut -d' ' -f1)
echo "JAR_OK build/deploy/neokey-sync-server.jar sha256=${JAR_SHA:0:16}.."

# ---- 3+4+5+6: THE GATE ------------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.DeploySrvGate) || { echo "$V" | grep -E "^(DEPLOYSRV regression|NEG-OK|PACK |DEPLOYSRVGATE_FAILED)" || echo "$V" | tail -5; fail "gate"; }
echo "$V" | grep -E "^(PACK |DEPLOYSRV regression|NEG-OK|DEPLOYSRVGATE_OK)" || true
VERDICT=$(echo "$V" | grep '^DEPLOYSRVGATE_OK' | tail -1)
[ -n "$VERDICT" ] || fail "gate (verdict missing)"
CHECKS=$(echo "$VERDICT" | grep -oE 'checks=[0-9]+' | cut -d= -f2)
NEG=$(echo "$VERDICT" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
MEMBERS=$(echo "$VERDICT" | grep -oE 'members=[0-9]+' | cut -d= -f2)
JARSHA=$(echo "$VERDICT" | grep -oE 'jar_sha256=[0-9a-f]+' | cut -d= -f2)
[ "$JARSHA" = "${JAR_SHA:0:16}" ] || fail "anchor (gate's jar sha $JARSHA != the fresh build ${JAR_SHA:0:16})"
[ -n "$CHECKS" ] && [ -n "$NEG" ] && [ -n "$MEMBERS" ] || fail "gate (verdict fields unparseable: $VERDICT)"
PUB_VC=1
PUB_VN=0.1.0
echo "$VERDICT" | grep -q "vc=$PUB_VC vn=$PUB_VN" || fail "anchor.identity (the pack statement must carry the week's identity $PUB_VC / $PUB_VN — got: $VERDICT)"
echo "$VERDICT"

# ---- 7: keys for the release manifest (idempotent upsert, week-33 hardening) ------
sed -i '/^DEPLOYSRV_/d' build/release/gates.env
{
  echo "DEPLOYSRV_GATE=OK"
  echo "DEPLOYSRV_CHECKS=$CHECKS"
  echo "DEPLOYSRV_NEGATIVES=$NEG"
  echo "DEPLOYSRV_MEMBERS=$MEMBERS"
  echo "DEPLOYSRV_JAR_SHA256=$JAR_SHA"
  echo "DEPLOYSRV_VC=$PUB_VC"
  echo "DEPLOYSRV_VN=$PUB_VN"
} >> build/release/gates.env
exit 0
