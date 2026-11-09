#!/bin/bash
# OPVERIFY GATE — phase 8 W3 (week 40) — ci/opverify_gate.sh
#
#   Pipeline stage 40, LAST (after the evidence auditor): مُحقِّق مخرجات
#   المشغّل — the OUTPUTS of the documented outside steps become
#   machine-verifiable. Pendings #1 (the BillingClient wiring + the pasted
#   console key) and #2 (the domain + CA cert + the transferred pack + the
#   two automated commands) stay external by nature — no pretending — but
#   what the operator PRODUCES when he walks them can be verified HERE:
#
#     1. THE ANCHOR IS THE FIRST BARRIER (المرجع = المنشور): the week's
#        published identity (81 / 3.4) is pinned here and checked against
#        the live manifest before anything runs.
#     2. THE LETTER: a fresh Provision run on the arena keystore produces
#        the reference letter (the published deployment shape); OpVerify
#        letter verifies schema/pin/pin16/emitted-coupling/floor/chain/
#        domain laws on BOTH letters and the field-SET equality between
#        them — the letter the operator brings home must carry the same
#        published shape as the one this environment just measured.
#     3. THE PACK: assembled fresh through SyncServerPack (the W35 module)
#        and re-verified BEFORE RUN through the same check — the transfer
#        surface (truncation, dropped statement, extra member, the
#        consistent forger who re-stamps) refuses with its tag before any
#        ./start-server.sh.
#     4. THE SWAP LINE: the ONE provider-construction line in
#        StoreActivity documented as a GATED LINE with its published
#        identity (literal text + sha256); an edited copy must touch
#        exactly that line and nothing else.
#
#   TWELVE exact-tag negatives on temp dirty copies (never the real
#   inputs), then the real inputs re-verify green.
#
#   Verdict: `OPVERIFYGATE_OK checks=N negatives=12/12 letters=2 packs=1
#   swaps=1 vc=$PUB_VC vn=$PUB_VN` / `OPVERIFYGATE_FAILED <reason>` (exit 1).
#   Keys are appended to gates.env idempotently (the week-33 hardening).
#   The saved negative proof: ci/opverify-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "OPVERIFYGATE_FAILED $1"; exit 1; }
STATE=build/release/gates.env
LOGF=build/pipeline.log

# ---- 0: THE ANCHOR — المرجع = المنشور ----------------------------------------
PUB_VC=1
PUB_VN=0.1.0

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the verifier week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the verifier week is $PUB_VN)"
[ -f tests/tls/arena-server.p12 ] || fail "fixtures (tests/tls/arena-server.p12 missing — the anchor)"
[ -f src/com/hark/neokey/StoreActivity.java ] || fail "fixtures (StoreActivity missing — the gated line lives there)"

# ---- 1: compile the module (with its two proven dependencies) -----------------
OUTD=build/opverify-gate; rm -rf "$OUTD"; mkdir -p "$OUTD"
SRC="tests/com/hark/neokey/OpVerify.java tests/com/hark/neokey/SyncServerPack.java tests/com/hark/neokey/Provision.java src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/SyncVault.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUTD" $SRC 2>&1
else
  [ -f tools/ecj.jar ] || fail "compile (no javac and no tools/ecj.jar — provision first)"
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUTD" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE LETTER — the arena runs Provision fresh, twice --------------------
rm -rf build/opverify; mkdir -p build/opverify
A1=$(java -cp "$OUTD" com.hark.neokey.Provision tests/tls/arena-server.p12 nk-arena nkarena 127.0.0.1 \
      --allow-self-signed --statement build/opverify/arena-statement.json 2>&1) \
  || fail "arena letter ($(echo "$A1" | grep -m1 PROVISION_FAIL || echo 'no verdict'))"
echo "$A1" | grep -q '^PROVISION_OK' || fail "arena letter ($(echo "$A1" | grep -m1 PROVISION_FAIL || echo 'no verdict'))"
A2=$(java -cp "$OUTD" com.hark.neokey.Provision tests/tls/arena-server.p12 nk-arena nkarena 127.0.0.1 \
      --allow-self-signed --statement build/opverify/arena-ref.json 2>&1) \
  || fail "reference letter ($(echo "$A2" | grep -m1 PROVISION_FAIL || echo 'no verdict'))"
echo "$A2" | grep -q '^PROVISION_OK' || fail "reference letter ($(echo "$A2" | grep -m1 PROVISION_FAIL || echo 'no verdict'))"
echo "[opverify] $(echo "$A1" | grep -oE 'PROVISION_OK [^e]+emitted=.*' | cut -c1-120)"

# ---- 3: THE PACK — assembled fresh, the W35 module -----------------------------
[ -f build/deploy/neokey-sync-server.jar ] || bash ci/build_deploy.sh > /tmp/opverify-deploybuild.log 2>&1 \
  || fail "pack jar (ci/build_deploy.sh failed — see /tmp/opverify-deploybuild.log)"
[ -f build/deploy/neokey-sync-server.jar ] || fail "pack jar (missing after build)"
rm -rf build/opverify/pack
PACK_LINE=$(java -cp "$OUTD" com.hark.neokey.SyncServerPack assemble build/opverify/pack 2>&1) \
  || fail "pack assemble ($PACK_LINE)"
echo "$PACK_LINE" | grep -q '^DEPLOYSRV_PACK_OK ' || fail "pack assemble ($PACK_LINE)"
echo "[opverify] $PACK_LINE"

# ---- 4: THE GATE — positives + twelve exact-tag negatives + green re-verify ----
GOUT=$(java -cp "$OUTD" com.hark.neokey.OpVerify gate build/opverify/arena-statement.json \
        build/opverify/arena-ref.json build/opverify/pack 127.0.0.1 2>&1); GRC=$?
echo "$GOUT" | grep -E 'NEG-OK|OPVERIFY regression' >&2 || :
GATE_LINE=$(echo "$GOUT" | grep -m1 '^OPVERIFYGATE_OK ')
if [ "$GRC" -ne 0 ] || [ -z "$GATE_LINE" ]; then
  echo "$GOUT" | tail -6 >&2
  fail "opverify (rc=$GRC)"
fi
echo "[opverify] $GATE_LINE"

# ---- 5: the module's verdict must carry THE WEEK (the single source) -----------
GVC=$(echo "$GATE_LINE" | grep -oE 'vc=[0-9]+' | cut -d= -f2)
GVN=$(echo "$GATE_LINE" | grep -oE 'vn=[0-9.]+' | cut -d= -f2)
[ "$GVC" = "$PUB_VC" ] || fail "anchor.identity.gate (module says vc=$GVC — the week is $PUB_VC)"
[ "$GVN" = "$PUB_VN" ] || fail "anchor.identity.gate (module says vn=$GVN — the week is $PUB_VN)"

# ---- 6: ledger consistency — the idempotent upsert + the two equalities --------
field() { echo "$1" | sed -E "s/.* $2=([^ ]+).*/\1/"; }
CHK=$(field "$GATE_LINE" checks); NEG=$(echo "$GATE_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
LTT=$(field "$GATE_LINE" letters); PKS=$(field "$GATE_LINE" packs); SWP=$(field "$GATE_LINE" swaps)
SWAP_SHA16=$(java -cp "$OUTD" com.hark.neokey.OpVerify swap | grep -oE 'line16=[0-9a-f]+' | cut -d= -f2)
mkdir -p build/release; [ -f "$STATE" ] || : > "$STATE"
sed -i '/^OPVERIFY_/d' "$STATE"
{
  echo "OPVERIFY_GATE=OK"
  echo "OPVERIFY_CHECKS=$CHK"
  echo "OPVERIFY_NEGATIVES=$NEG"
  echo "OPVERIFY_LETTERS=$LTT"
  echo "OPVERIFY_PACKS=$PKS"
  echo "OPVERIFY_SWAPS=$SWP"
  echo "OPVERIFY_SWAP_SHA16=$SWAP_SHA16"
  echo "OPVERIFY_VC=$PUB_VC"
  echo "OPVERIFY_VN=$PUB_VN"
} >> "$STATE"
# equality 1: the ledger must now carry THIS run's fresh values
. "$STATE"
[ "${OPVERIFY_CHECKS:-}" = "$CHK" ]    || fail "env.opverify_checks ${OPVERIFY_CHECKS:-?} != fresh $CHK"
[ "${OPVERIFY_NEGATIVES:-}" = "$NEG" ] || fail "env.opverify_negatives ${OPVERIFY_NEGATIVES:-?} != fresh $NEG"
[ "${OPVERIFY_SWAPS:-}" = "$SWP" ]     || fail "env.opverify_swaps ${OPVERIFY_SWAPS:-?} != fresh $SWP"
[ "${OPVERIFY_VC:-}" = "$PUB_VC" ]     || fail "env.opverify_vc ${OPVERIFY_VC:-?} != $PUB_VC"
# equality 2: any OPVERIFYGATE_OK line already in the outer log (a standalone
# re-run after a pipeline) must byte-equal fresh — a drifted rerun is caught
LOG_LINE=$(grep -m1 '^OPVERIFYGATE_OK ' "$LOGF" 2>/dev/null || true)
if [ -n "$LOG_LINE" ]; then
  [ "$LOG_LINE" = "$GATE_LINE" ] || fail "log.opverify != fresh (the outer log's line drifted from this run)"
fi

echo "$GATE_LINE"
exit 0
