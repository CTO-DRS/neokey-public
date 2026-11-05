#!/bin/bash
# Negative proof for the opverify gate (phase 8 W3, week 40) — SAVED evidence.
# Four designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25/33
# lesson: no fake RESTORE-DID-NOT-HEAL) and the gate re-verified GREEN. The
# proof fails loudly if any attack fails to bite or any restore fails to heal.
#
# The gate REGENERATES its workspace every run (fresh arena letters, fresh
# pack assembly) — so the attacks hit what the gate CONSUMES but does not
# rebuild, each a different law class:
#
#   1) anchor identity tamper: the manifest's versionCode rewritten (bump-
#      independent: any identity, always wrong — the W34 lesson) — the gate's
#      anchor bites BEFORE anything runs («المرجع = المنشور»).
#   2) the reference fixture unreadable: the arena keystore truncated — the
#      gate cannot produce the published letter shape to compare against and
#      FAILS CLOSED (no letter, no verdict — never a green without the
#      reference; Provision refuses unreadable_chain, the gate refuses with
#      it).
#   3) the gated swap line drifted: one character changed in the REAL
#      StoreActivity line → the published-identity law bites (swap.identity)
#      — the line is documented with its sha256 and the drift is caught.
#   4) dead guard: OpVerify's emitted-coupling law neutered to a no-op — the
#      gate's own letter.pin_flip negative STOPS BITING with its expected
#      tag and the gate fails on regressions: a verifier that verifies
#      nothing is a failure, caught by the gate's own negative, not by hope
#      (the W34 class).
set -uo pipefail
cd "$(dirname "$0")/.."

# the proof writes itself to a TEMP file and moves into ci/ ONLY when
# complete — the gate must never see its own mid-write proof (an in-progress
# log is not evidence; the W39 self-reference lesson, inherited).
PROOF_TMP=/tmp/opverify-proof-in-progress.log
rm -f ci/opverify-negative-proof.log
exec > "$PROOF_TMP" 2>&1

FAILED=0

echo "== opverify negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/opverify-proof-backup
cp AndroidManifest.xml build/opverify-proof-backup/AndroidManifest.xml
cp tests/com/hark/neokey/OpVerify.java build/opverify-proof-backup/OpVerify.java
cp src/com/hark/neokey/StoreActivity.java build/opverify-proof-backup/StoreActivity.java
cp tests/tls/arena-server.p12 build/opverify-proof-backup/arena-server.p12

gate() { bash ci/opverify_gate.sh > "$1" 2>&1; echo $?; }
PROOF_OUT=/tmp/opverify-proof-in-progress.log

run_attack() { # $1 name, $2 attacker (bash), $3 expected line (grep -E)
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/opverify_neg.txt)
  local verdict=$(grep -m1 -E "$expect" /tmp/opverify_neg.txt | head -1 || echo "(no expected line)")
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/opverify_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -3 /tmp/opverify_neg.txt; FAILED=1
  else
    echo "bit: $verdict"
  fi
  eval "$(cat <<'RESTORE'
cp build/opverify-proof-backup/AndroidManifest.xml AndroidManifest.xml
cp build/opverify-proof-backup/OpVerify.java tests/com/hark/neokey/OpVerify.java
cp build/opverify-proof-backup/StoreActivity.java src/com/hark/neokey/StoreActivity.java
cp build/opverify-proof-backup/arena-server.p12 tests/tls/arena-server.p12
RESTORE
)"
  local rrc=$(gate /tmp/opverify_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^OPVERIFYGATE_OK' /tmp/opverify_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -3 /tmp/opverify_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^OPVERIFYGATE_OK' /tmp/opverify_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/opverify_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^OPVERIFYGATE_OK' /tmp/opverify_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/opverify_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^OPVERIFYGATE_OK' /tmp/opverify_base.txt)"

# ---- the four attacks -----------------------------------------------------------
# (1) the anchor is the first barrier — the manifest identity rewritten to an
#     always-wrong value (bump-independent, the W34 lesson).
run_attack "anchor.identity_tamper" \
  'sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"3\"/" AndroidManifest.xml' \
  'OPVERIFYGATE_FAILED anchor.identity.vc'

# (2) the reference fixture unreadable — the gate cannot produce the published
#     letter shape and fails closed (no letter, no verdict).
run_attack "fixture.arena_keystore_unreadable" \
  'head -c 40 tests/tls/arena-server.p12 > /tmp/arena.trunc && mv /tmp/arena.trunc tests/tls/arena-server.p12' \
  'OPVERIFYGATE_FAILED arena letter|unreadable_chain'

# (3) the gated swap line drifted — one character in the REAL file.
run_attack "swap.identity_drift" \
  'sed -i "s/sim = new PaymentProvider.SimProvider();/sim  = new PaymentProvider.SimProvider();/" src/com/hark/neokey/StoreActivity.java' \
  'OPVERIFYGATE_FAILED swap.committed|OPVERIFYGATE_FAILED regressions|swap.identity_drift'

# (4) dead guard: the emitted-coupling law neutered — the gate's own
#     letter.pin_flip negative stops biting ITS tag and the verdict becomes
#     a regression (the W34 class).
run_attack "deadguard.emitted_law_neutered" \
  'sed -i "/^        if (emitted == null/c\\        if (false \\&\\& emitted != null)" tests/com/hark/neokey/OpVerify.java' \
  'OPVERIFYGATE_FAILED regressions|OPVERIFYGATE_FAILED log.opverify'

# ---- الحصيلة --------------------------------------------------------------------
if [ "$FAILED" -ne 0 ]; then
  echo ""
  echo "PROOF FAILED: an attack did not bite, a restore did not heal — see above"
  mv "$PROOF_TMP" ci/opverify-negative-proof.log
  exit 1
fi
echo ""
echo "== PROOF PASSED: all four attacks bit with their own tags, all files restored, final gate green =="
mv "$PROOF_TMP" ci/opverify-negative-proof.log
