#!/bin/bash
# Negative proof for the billing gate (phase 7 W1, week 34) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN. The proof fails loudly if any attack fails
# to bite or any restore fails to heal.
#
#   1) identity tamper: the manifest's versionCode 75 -> 74 — the gate's
#      anchor bites BEFORE anything compiles («المرجع = المنشور»: the week
#      carrying the adapter declares its own identity).
#   2) dead guard: PlayProvider.verify neutered to always-true — the
#      tampered-purchase-text negative STOPS BITING with its expected tag
#      and the gate fails on it: a verifier that verifies nothing is a
#      failure, caught by the gate's own negative, not by hope.
#   3) hidden validator: BillingGate.java removed — the gate fails closed at
#      compile time (a missing validator is never a silent pass).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== billing negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/billing-proof-backup
cp AndroidManifest.xml build/billing-proof-backup/AndroidManifest.xml
cp src/com/hark/neokey/PaymentProvider.java build/billing-proof-backup/PaymentProvider.java
cp tests/com/hark/neokey/BillingGate.java build/billing-proof-backup/BillingGate.java

gate() { bash ci/billing_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected line (grep -E), $4 extra restore (optional)
  local name="$1" attacker="$2" expect="$3" extra="${4:-:}"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/bill_neg.txt)
  local verdict=$(grep -m1 -E "$expect" /tmp/bill_neg.txt | head -1 || echo "(no expected line)")
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/bill_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -3 /tmp/bill_neg.txt; FAILED=1
  else
    echo "bit: $verdict"
  fi
  eval "$extra"
  eval "$(cat <<'RESTORE'
cp build/billing-proof-backup/AndroidManifest.xml AndroidManifest.xml
cp build/billing-proof-backup/PaymentProvider.java src/com/hark/neokey/PaymentProvider.java
cp build/billing-proof-backup/BillingGate.java tests/com/hark/neokey/BillingGate.java
RESTORE
)"
  local rrc=$(gate /tmp/bill_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^BILLINGGATE_OK' /tmp/bill_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -3 /tmp/bill_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^BILLINGGATE_OK' /tmp/bill_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/bill_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^BILLINGGATE_OK' /tmp/bill_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/bill_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^BILLINGGATE_OK' /tmp/bill_base.txt)"

run_attack "manifest identity tamper (75 -> 74)" \
  "sed -i 's/android:versionCode=\"75\"/android:versionCode=\"74\"/' AndroidManifest.xml" \
  "BILLINGGATE_FAILED anchor.identity.vc"

run_attack "dead guard (PlayProvider.verify neutered to always-true)" \
  "sed -i 's/return s.verify(java.util.Base64.getDecoder().decode(sigBase64));/return true; \/\* ATTACK \*\//' src/com/hark/neokey/PaymentProvider.java" \
  "BILLING regression: neg.tampered_text"

run_attack "hidden validator (BillingGate.java removed)" \
  "mv tests/com/hark/neokey/BillingGate.java /tmp/BillingGate.java.hidden" \
  "BILLINGGATE_FAILED"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo "== PROOF FAILED: an attack did not bite or a restore did not heal =="
  exit 1
fi
FINAL=$(gate /tmp/bill_final.txt)
if [ "$FINAL" -ne 0 ] || ! grep -q '^BILLINGGATE_OK' /tmp/bill_final.txt; then
  echo "== PROOF FAILED: final gate not green =="; exit 1
fi
echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
grep -m1 '^BILLINGGATE_OK' /tmp/bill_final.txt
