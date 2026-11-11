#!/bin/bash
# Negative proof for the community-channel OPEN rehearsal gate (W53 prep) —
# SAVED evidence. Three designed attacks; each must fail the gate with ITS OWN
# tag, then be restored (cp snapshot, restore INSIDE run_attack — the
# weeks-24/25 lesson) and the gate re-verified GREEN. The proof fails loudly
# if any attack fails to bite or any restore fails to heal.
#
#   1) dead thanks expectation: the harness's demand on the thanks verdict
#      neutered (the documented title check renamed away) — the generated
#      section could open with ANYTHING and the rehearsal would still call the
#      page lawful; the gate bails on thanks.format: a rehearsal that accepts
#      any thanks page credits nobody and proves nothing.
#   2) the intake's documented gloss line severed: the guide's «أقل من حرفين»
#      (INTAKE_GLOSS_MIN = 2) replaced by a rogue 0 — the doc-mirror screener
#      would accept a one-letter gloss the shipped judge refuses; the designed
#      negative no longer bites and the rehearsal fails closed on
#      NEG-FAILED neg.gloss_intake: proves the documented line is load-bearing
#      — if the intake still agreed, the «doc == code» law would guard nothing.
#   3) hidden tool: CommunityOpen.java removed — the gate fails closed at
#      compile time (a missing rehearsal is never a silent pass).
#
# Identity: the gate's verdict carries vc=1 vn=0.1.0 from the ONE manifest —
# a gate green on any other identity is not this week's rehearsal.
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== communityopen negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p build/communityopen-proof-backup
cp tests/com/hark/neokey/CommunityOpen.java build/communityopen-proof-backup/CommunityOpen.java

gate() { bash ci/communityopen_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker, $3 expected line (grep -E), $4 restore
  local name="$1" attacker="$2" expect="$3" restore="$4"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/co_neg.txt)
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/co_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -4 /tmp/co_neg.txt; FAILED=1
  else
    echo "bit: $(grep -m1 -E "$expect" /tmp/co_neg.txt)"
  fi
  eval "$restore"
  local rrc=$(gate /tmp/co_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^COMMUNITYOPEN_OK' /tmp/co_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -4 /tmp/co_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^COMMUNITYOPEN_OK' /tmp/co_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/co_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^COMMUNITYOPEN_OK' /tmp/co_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/co_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^COMMUNITYOPEN_OK' /tmp/co_base.txt)"

run_attack "dead thanks expectation (the harness's demand on the thanks title renamed away — a rehearsal that accepts any thanks page credits nobody)" \
  "sed -i 's|thanks.startsWith(THANKS_TITLE)|thanks.startsWith(\"N\"+THANKS_TITLE)|' tests/com/hark/neokey/CommunityOpen.java" \
  "COMMUNITYOPEN regression: thanks\.format" \
  "cp build/communityopen-proof-backup/CommunityOpen.java tests/com/hark/neokey/CommunityOpen.java"

run_attack "the intake's documented gloss line severed (the guide's «أقل من حرفين» replaced by a rogue 0 — the doc-mirror screener accepts what the shipped judge refuses)" \
  "sed -i 's|INTAKE_GLOSS_MIN = 2|INTAKE_GLOSS_MIN = 0|' tests/com/hark/neokey/CommunityOpen.java" \
  "NEG-FAILED neg\.gloss_intake" \
  "cp build/communityopen-proof-backup/CommunityOpen.java tests/com/hark/neokey/CommunityOpen.java"

run_attack "hidden tool (CommunityOpen.java removed — the gate fails closed at compile)" \
  "mv tests/com/hark/neokey/CommunityOpen.java /tmp/communityopen-hidden.java" \
  "COMMUNITYOPEN_FAILED compile" \
  "mv /tmp/communityopen-hidden.java tests/com/hark/neokey/CommunityOpen.java"

# ---- final: the source byte-identical to the backup, and the gate still green ---
if ! cmp -s tests/com/hark/neokey/CommunityOpen.java build/communityopen-proof-backup/CommunityOpen.java; then
  echo "SOURCE NOT RESTORED byte-exact"; FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "PROOF FAILED communityopen (see the lines above)"
  exit 1
fi
echo ""
echo "PROOF PASSED communityopen 3/3 attacks bit their exact tags and the restores healed ($(grep -m1 '^COMMUNITYOPEN_OK' /tmp/co_heal.txt))"
