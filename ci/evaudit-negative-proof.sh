#!/bin/bash
# Negative proof for the evaudit gate (phase 8 W2, week 39) — SAVED evidence.
# Four designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25/33
# lesson: no fake RESTORE-DID-NOT-HEAL) and the gate re-verified GREEN. The
# proof fails loudly if any attack fails to bite or any restore fails to heal.
#
#   1) anchor identity tamper: the manifest's versionCode rewritten (bump-
#      independent: any identity, always wrong — the W34 lesson: a sed pinned
#      to LAST week's literal goes stale at the next bump) — the gate's anchor
#      bites BEFORE anything is packed («المرجع = المنشور»).
#   2) live-ledger tamper: BILLING_CHECKS forged in build/release/gates.env
#      (24 → 999) — the gate re-packs the archive from the tampered ledger
#      and the audit's verdict↔ledger cross-check bites env.billing_checks_
#      mismatch INSIDE the archived copy: a forged number smuggled through
#      the pack is still caught (the archive is verified, not trusted).
#   3) saved-proof emptied: ci/billing-negative-proof.log truncated to zero
#      bytes — the archive inherits the empty file and the saved-negative-
#      proof LAW bites proof.not_passed: an emptied proof is no proof
#      (erased evidence is no evidence — the W38 lesson), and the law
#      accepts BOTH honest archive shapes (the modern success marker and
#      the early-weeks bite transcripts) but nothing at all is nothing.
#   4) dead guard: EvAudit.lawIdentity neutered to a no-op — the gate's own
#      identity.expect negative STOPS BITING with its expected tag and the
#      gate fails on regressions: a verifier that verifies nothing is a
#      failure, caught by the gate's own negative, not by hope (class W34).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== evaudit negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/evaudit-proof-backup
cp AndroidManifest.xml build/evaudit-proof-backup/AndroidManifest.xml
cp build/release/gates.env build/evaudit-proof-backup/gates.env
cp ci/billing-negative-proof.log build/evaudit-proof-backup/billing-negative-proof.log
cp tests/com/hark/neokey/EvAudit.java build/evaudit-proof-backup/EvAudit.java

gate() { bash ci/evaudit_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected line (grep -E)
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/evaudit_neg.txt)
  local verdict=$(grep -m1 -E "$expect" /tmp/evaudit_neg.txt | head -1 || echo "(no expected line)")
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/evaudit_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -3 /tmp/evaudit_neg.txt; FAILED=1
  else
    echo "bit: $verdict"
  fi
  eval "$(cat <<'RESTORE'
cp build/evaudit-proof-backup/AndroidManifest.xml AndroidManifest.xml
cp build/evaudit-proof-backup/gates.env build/release/gates.env
cp build/evaudit-proof-backup/billing-negative-proof.log ci/billing-negative-proof.log
cp build/evaudit-proof-backup/EvAudit.java tests/com/hark/neokey/EvAudit.java
RESTORE
)"
  local rrc=$(gate /tmp/evaudit_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^EVAUDITGATE_OK' /tmp/evaudit_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -3 /tmp/evaudit_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^EVAUDITGATE_OK' /tmp/evaudit_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/evaudit_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^EVAUDITGATE_OK' /tmp/evaudit_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/evaudit_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^EVAUDITGATE_OK' /tmp/evaudit_base.txt)"

# ---- the four attacks -----------------------------------------------------------
run_attack "anchor.identity_tamper" \
  'sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"79\"/" AndroidManifest.xml' \
  'EVAUDITGATE_FAILED anchor.identity.vc'

run_attack "ledger.billing_checks_forged" \
  'sed -i "s/^BILLING_CHECKS=[0-9]*/BILLING_CHECKS=999/" build/release/gates.env' \
  'EVAUDITGATE_FAILED evaudit|EVAUDITGATE_FAILED regressions|env.billing_checks_mismatch'

run_attack "proof.emptied" \
  ': > ci/billing-negative-proof.log' \
  'EVAUDITGATE_FAILED evaudit|proof.not_passed billing-negative-proof.log'

run_attack "deadguard.lawIdentity_neutered" \
  'sed -i "s/if (vcs.size() > 1 || vns.size() > 1) refuse(\"identity.mismatch\");/if (false) refuse(\"identity.mismatch\");/" tests/com/hark/neokey/EvAudit.java' \
  'EVAUDITGATE_FAILED regressions|EVAUDIT regression: identity.expect_wrong_week'

# ---- الحصيلة --------------------------------------------------------------------
if [ "$FAILED" -ne 0 ]; then
  echo ""
  echo "PROOF FAILED: an attack did not bite, a restore did not heal — see above"
  exit 1
fi
echo ""
echo "== PROOF PASSED: all four attacks bit with their own tags, all files restored, final gate green =="
