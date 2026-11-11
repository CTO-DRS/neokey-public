#!/bin/bash
# Negative proof for the deploy-day rehearsal gate (W51 prep) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN. The proof fails loudly if any attack fails
# to bite or any restore fails to heal.
#
#   1) dead decision expectation: the harness's PROVISION_OK prefix neutered
#      (renamed away) — the operator's decision could return ANYTHING and the
#      rehearsal would still call it green; the gate bails at decision.ok:
#      a rehearsal that accepts any verdict rehearses nothing.
#   2) the pin law severed from the decision: the DERIVED pin (the one the
#      Provision decision emitted and --print-spki re-derived) replaced by a
#      hardcoded rogue literal — the health probe and the round then run with
#      a pin nobody derived; the wire refuses (spki_not_pinned) and the gate
#      fails on health.ok: proves the rehearsal CONSUMES the decision's pin —
#      if a rogue pin still passed, the pin would not be load-bearing.
#   3) hidden tool: DeployDay.java removed — the gate fails closed at compile
#      time (a missing rehearsal is never a silent pass).
#
# Identity: the gate's verdict carries vc=1 vn=0.1.0 from the ONE source
# (SyncServerPack constants) — a gate green on any other identity is not this
# week's rehearsal.
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== deployday negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p build/deployday-proof-backup
cp tests/com/hark/neokey/DeployDay.java build/deployday-proof-backup/DeployDay.java

gate() { bash ci/deployday_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker, $3 expected line (grep -E), $4 restore
  local name="$1" attacker="$2" expect="$3" restore="$4"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/dd_neg.txt)
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/dd_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -4 /tmp/dd_neg.txt; FAILED=1
  else
    echo "bit: $(grep -m1 -E "$expect" /tmp/dd_neg.txt)"
  fi
  eval "$restore"
  local rrc=$(gate /tmp/dd_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^DEPLOYDAY_OK' /tmp/dd_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -4 /tmp/dd_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^DEPLOYDAY_OK' /tmp/dd_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/dd_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^DEPLOYDAY_OK' /tmp/dd_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/dd_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^DEPLOYDAY_OK' /tmp/dd_base.txt)"

run_attack "dead decision expectation (the PROVISION_OK prefix renamed away — a rehearsal that accepts any verdict rehearses nothing)" \
  "sed -i 's|\"PROVISION_OK\"|\"PROVISION_NEVER_OK\"|g' tests/com/hark/neokey/DeployDay.java" \
  "DEPLOYDAY_FAILED decision\.ok" \
  "cp build/deployday-proof-backup/DeployDay.java tests/com/hark/neokey/DeployDay.java"

run_attack "the pin law severed from the decision (the derived pin replaced by a hardcoded rogue literal — the wire must refuse what nobody derived)" \
  "sed -i 's|String pin = field(dec, \"pin=\");|String pin = \"0000000000000000000000000000000000000000000000000000000000000000\";|' tests/com/hark/neokey/DeployDay.java" \
  "DEPLOYDAY regression: health\.ok" \
  "cp build/deployday-proof-backup/DeployDay.java tests/com/hark/neokey/DeployDay.java"

run_attack "hidden tool (DeployDay.java removed — the gate fails closed at compile)" \
  "mv tests/com/hark/neokey/DeployDay.java /tmp/deployday-hidden.java" \
  "DEPLOYDAY_FAILED compile" \
  "mv /tmp/deployday-hidden.java tests/com/hark/neokey/DeployDay.java"

# ---- final: the source byte-identical to the backup, and the gate still green ---
if ! cmp -s tests/com/hark/neokey/DeployDay.java build/deployday-proof-backup/DeployDay.java; then
  echo "SOURCE NOT RESTORED byte-exact"; FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "PROOF FAILED deployday (see the lines above)"
  exit 1
fi
echo ""
echo "PROOF PASSED deployday 3/3 attacks bit their exact tags and the restores healed ($(grep -m1 '^DEPLOYDAY_OK' /tmp/dd_heal.txt))"
