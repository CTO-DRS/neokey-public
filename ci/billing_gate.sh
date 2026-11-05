#!/bin/bash
# billing_gate.sh — week 34 (phase 7 W1) — ci/billing_gate.sh
#
#   The documented pending "real payment provider (Play Billing) — replaces
#   PaymentProvider.SimProvider in StoreActivity only" keeps the android
#   BillingClient wiring (a device) and the console key outside this
#   environment — but the REPLACEMENT ITSELF is provable NOW:
#
#     1. compile guard      — PaymentProvider (the contract + SimProvider +
#                             PlayProvider) + PlayProviderTest + BillingGate
#     2. THE ANCHOR FIRST   — the SHIPPED artifact (build/NeoKey-2036.apk,
#                             badging via aapt2) must carry THIS week's
#                             identity (79 / 3.2) — the week carrying the
#                             adapter declares it («المرجع = المنشور»: the
#                             manifest the console sees is the manifest the
#                             gate pins, byte-for-byte)
#     3. THE GATE           — BillingGate: anchors + purity (zero android.*,
#                             ONE seam) + the CONTRACT PARITY suite on BOTH
#                             providers (same seal law, same externalRef
#                             law) + TEN designed negatives each biting its
#                             exact tag + construction refusals.
#
#   Verdict: BILLINGGATE_OK checks=N negatives=10/10 vc=80 vn=3.3 providers=2
#            / BILLINGGATE_FAILED <reason> (exit 1). Keys are appended to
#            gates.env idempotently (the week-33 hardening: no block piling).
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "BILLINGGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
OUT=build/billing-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/ThemePack.java tests/com/hark/neokey/PlayProviderTest.java tests/com/hark/neokey/BillingGate.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: THE ANCHOR IS THE FIRST BARRIER ---------------------------------------
# The artifact shipping the adapter carries THIS week's identity: aapt2 badging
# on the shipped APK == the manifest == the gate's pinned literals.
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "80" ] || fail "anchor.identity.vc (manifest declares $VC — the week carrying the adapter is 80)"
[ "$VN" = "3.3" ] || fail "anchor.identity.vn (manifest declares $VN — the week carrying the adapter is 3.3)"
[ -f build/NeoKey-2036.apk ] || fail "anchor.apk.missing (run the build stage first)"
AAPT2=""
for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work; do
  [ -n "$d" ] && [ -x "$d/aapt2" ] && { AAPT2="$d/aapt2"; break; }
done
[ -n "$AAPT2" ] || fail "anchor.badging (aapt2 missing)"
BADGE=$("$AAPT2" dump badging build/NeoKey-2036.apk | head -1)
echo "$BADGE" | grep -q "versionCode='80'" || fail "anchor.badging.vc (want 80: $BADGE)"
echo "$BADGE" | grep -q "versionName='3.3'" || fail "anchor.badging.vn (want 3.3: $BADGE)"
echo "ANCHOR_OK build/NeoKey-2036.apk badging vc=80 vn=3.3 (the artifact and the manifest agree)"

# ---- 3: THE GATE ----------------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.BillingGate) || { echo "$V" | grep -E "^(BILLING regression|NEG-OK|BILLINGGATE_FAILED)" || echo "$V" | tail -5; fail "gate"; }
echo "$V" | grep -E "^(BILLING regression|NEG-OK|BILLINGGATE_OK)" || true
VERDICT=$(echo "$V" | grep '^BILLINGGATE_OK' | tail -1)
[ -n "$VERDICT" ] || fail "gate (verdict missing)"
CHECKS=$(echo "$VERDICT" | grep -oE 'checks=[0-9]+' | cut -d= -f2)
NEG=$(echo "$VERDICT" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)
[ -n "$CHECKS" ] && [ -n "$NEG" ] || fail "gate (verdict fields unparseable: $VERDICT)"
echo "$VERDICT"

# ---- 4: keys for the release manifest (idempotent upsert, week-33 hardening) ----
sed -i '/^BILLING_/d' build/release/gates.env
{
  echo "BILLING_GATE=OK"
  echo "BILLING_CHECKS=$CHECKS"
  echo "BILLING_NEGATIVES=$NEG"
  echo "BILLING_VC=$VC"
  echo "BILLING_VN=$VN"
} >> build/release/gates.env
exit 0
