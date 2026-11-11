#!/bin/bash
# Deploy-day rehearsal gate (roadmap W51 prep — the local half of external
# pending #2 «النطاق والنشر الحقيقي») — ci/deployday_gate.sh
#
#   W51's OWN exit criterion (PROVISIONGATE_OK with a LIVE health check on the
#   bought domain) stays with the owner — the bought domain, the commercial CA
#   and the real host are three things this environment does not have and does
#   not pretend to have. But the SEQUENCE the operator walks on deploy day is
#   fully rehearsable locally, and this gate walks it end to end on fresh
#   stand-in material (keytool-generated: an operator CA + a loopback IP-SAN
#   leaf — the iPAddress SAN is spelled out by the Provision contract itself;
#   the real week brings a dNSName leaf from a commercial CA):
#
#     1. compile guard      — DeployDay + SyncServerPack + Provision (+ the
#                             pure wire deps SyncVault/SpkiPins/PinnedTls)
#     2. THE JAR, FRESH     — ci/build_deploy.sh re-runs (no cached artifact)
#     3. THE MATERIAL       — keytool: operator CA (bc=ca:true) + the
#                             provisioned server.p12 [leaf, CA] + the five
#                             designed bad leaves (wrong domain, foreign CA
#                             pair, expired, short, self-signed)
#     4. THE REHEARSAL      — DeployDay: pack assemble+check -> the operator's
#                             Provision CLI decision (PROVISION_OK + the
#                             emitted NetGate.setSyncPins line + the written
#                             statement) -> five decision refusals biting
#                             their exact tags -> the jar's --print-spki ==
#                             the derived pin (two independent derivations) ->
#                             the pack copy BOOTS on the provisioned
#                             keystore -> the real --check probe (strict
#                             hostname verification — the IP SAN matches)
#                             SYNCH_HEALTH_OK -> an 11-check contract round
#                             over the pinned anchored channel -> rogue pin
#                             refused on the wire -> a FOREIGN-CA server
#                             (trusted-but-unpinned) refused by the pin alone
#                             -> SIGTERM exits, port dead -> the decision
#                             re-runs green (the tree heals)
#
#   Verdict: DEPLOYDAY_OK checks=N negatives=M/M pin16=<16hex> days_left=<n>
#            pack_members=<n> vc=1 vn=0.1.0
#            / DEPLOYDAY_FAILED <reason> (exit 1).
#   Standalone by design (the W50-prep pattern): no pipeline stage, no
#   gates.env keys, no closer list moves — the 42-stage pipeline and every
#   pin stay byte-stable.
#   Drill password day-pass-2026 is DOCUMENTED ON PURPOSE (the
#   tests/provision/README.md pattern) — fixtures only, never real material.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "DEPLOYDAY_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/deployday-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/DeployDay.java tests/com/hark/neokey/SyncServerPack.java \
tests/com/hark/neokey/Provision.java src/com/hark/neokey/SyncVault.java \
src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java"

# ---- 1: compile guard -----------------------------------------------------------
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE JAR, FRESH ----------------------------------------------------------
bash ci/build_deploy.sh > /tmp/deployday-build.log 2>&1 || { tail -5 /tmp/deployday-build.log; fail "jar build"; }
grep -q "DEPLOY_BUILD_OK" /tmp/deployday-build.log || fail "jar build (no DEPLOY_BUILD_OK)"
echo "JAR_OK $(grep DEPLOY_BUILD_OK /tmp/deployday-build.log)"

# ---- 3: THE MATERIAL (keytool, fresh per run — no committed fixtures here) ------
DAY=build/deployday; rm -rf "$DAY"; mkdir -p "$DAY"
P=day-pass-2026   # documented on purpose — drill fixtures only, never real material
KT() { keytool "$@" >/dev/null 2>&1 || fail "keytool $1"; }

KT -genkeypair -alias nk-day-ca -dname "CN=NeoKey Deploy-Day Operator CA,OU=Hark,O=NeoKey,L=Riyadh,C=SA" \
   -keyalg RSA -keysize 2048 -validity 3650 -ext bc=ca:true \
   -keystore "$DAY/operator-ca.p12" -storetype PKCS12 -storepass "$P"
KT -exportcert -alias nk-day-ca -rfc -keystore "$DAY/operator-ca.p12" -storepass "$P" -file "$DAY/operator-ca.pem"
KT -genkeypair -alias nk-foreign-ca -dname "CN=Foreign Authority,OU=Hark,O=NeoKey,C=SA" \
   -keyalg RSA -keysize 2048 -validity 3650 -ext bc=ca:true \
   -keystore "$DAY/foreign-ca.p12" -storetype PKCS12 -storepass "$P"
KT -exportcert -alias nk-foreign-ca -rfc -keystore "$DAY/foreign-ca.p12" -storepass "$P" -file "$DAY/foreign-ca.pem"

# mkchain <ks> <alias> <dname> <san> <extra gencert args...>
#   keypair + CSR + CA-issued reply + [leaf, CA] chain imported back
mkchain() {
  local ks="$DAY/$1" alias="$2" dname="$3" san="$4"; shift 4
  KT -genkeypair -alias "$alias" -dname "$dname" -keyalg RSA -keysize 2048 \
     -ext "san=$san" -keystore "$ks" -storetype PKCS12 -storepass "$P"
  KT -certreq -alias "$alias" -keystore "$ks" -storepass "$P" -file "$ks.csr"
  KT -gencert -alias nk-day-ca -infile "$ks.csr" -outfile "$ks.leaf.pem" -rfc \
     -ext "san=$san" -keystore "$DAY/operator-ca.p12" -storepass "$P" "$@"
  KT -importcert -alias nk-day-ca -file "$DAY/operator-ca.pem" -keystore "$ks" -storetype PKCS12 -storepass "$P" -noprompt
  KT -importcert -alias "$alias" -file "$ks.leaf.pem" -keystore "$ks" -storetype PKCS12 -storepass "$P" -noprompt
  rm -f "$ks.csr" "$ks.leaf.pem"
}

mkchain server.p12 sync "CN=127.0.0.1" "ip:127.0.0.1" -validity 365
mkchain wrongdom.p12 sync "CN=127.0.0.2" "ip:127.0.0.2" -validity 365
mkchain short.p12 sync "CN=short.neokey.test" "dns:short.neokey.test" -validity 10
mkchain expired.p12 sync "CN=expired.neokey.test" "dns:expired.neokey.test" -validity 30 -startdate -400d
KT -genkeypair -alias solo -dname "CN=127.0.0.1" -keyalg RSA -keysize 2048 \
   -validity 365 -ext "san=ip:127.0.0.1" -keystore "$DAY/plain.p12" -storetype PKCS12 -storepass "$P"
# the foreign-CA server: a VALID chain from a DIFFERENT authority (the
# trusted-but-unpinned attacker — only the pin stands between it and the client)
FS="$DAY/foreign-server.p12"
KT -genkeypair -alias sync -dname "CN=127.0.0.1" -keyalg RSA -keysize 2048 \
   -ext "san=ip:127.0.0.1" -keystore "$FS" -storetype PKCS12 -storepass "$P"
KT -certreq -alias sync -keystore "$FS" -storepass "$P" -file "$FS.csr"
KT -gencert -alias nk-foreign-ca -infile "$FS.csr" -outfile "$FS.leaf.pem" -rfc \
   -ext "san=ip:127.0.0.1" -keystore "$DAY/foreign-ca.p12" -storepass "$P" -validity 365
KT -importcert -alias nk-foreign-ca -file "$DAY/foreign-ca.pem" -keystore "$FS" -storetype PKCS12 -storepass "$P" -noprompt
KT -importcert -alias sync -file "$FS.leaf.pem" -keystore "$FS" -storetype PKCS12 -storepass "$P" -noprompt
rm -f "$FS.csr" "$FS.leaf.pem"
echo "MATERIAL_OK $(ls "$DAY" | wc -l) files in $DAY"

# ---- 4: THE REHEARSAL -----------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.DeployDay) || {
  echo "$V" | grep -E "^(DEPLOYDAY regression|NEG-FAILED|PACK |HEALTH |DECISION |DEPLOYDAY_FAILED)" || echo "$V" | tail -5
  fail "rehearsal"
}
echo "$V" | grep -E "^(PACK |HEALTH |DECISION |NEG-FAILED|DEPLOYDAY regression|DEPLOYDAY_OK)" || true
VERDICT=$(echo "$V" | grep '^DEPLOYDAY_OK' | tail -1)
[ -n "$VERDICT" ] || fail "rehearsal (verdict missing)"
echo "DEPLOYDAY_GATE=OK"
