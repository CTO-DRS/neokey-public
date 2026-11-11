#!/bin/bash
# Saved negative proof for the clipboard phase-6 gate (W48, 2026-11-23)
# — ci/cliplib-negative-proof.sh
#
# Three designed attacks against ci/cliplib_gate.sh, each must bite with ITS
# OWN tag, and after EVERY attack the clean tree must heal to a full green
# gate (containment, not restore-by-prayer — the heal restores BACKUP bytes,
# never a git state, so the proof is safe on an uncommitted tree; the W47
# git-checkout lesson is documented):
#   1. tamper.tags-cap        — a dropped tag-count cap must fail the suite
#                               the gate re-runs fresh
#   2. tamper.sc-uniqueness   — a dropped shortcut uniqueness guard must be
#                               caught by the "first owner keeps" law check
#   3. tamper.arrows.returned — re-adding a pin-reorder action key must turn
#                               the gate red at the SOURCE law (the W48
#                               «بالسحب بدل ▲▼» guard), not at the suite
# The proof fails loudly if any attack fails to bite or any heal fails.
# Output pinned at ci/cliplib-negative-proof.log (committed evidence).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/cliplib-negative-proof.log
BAK=/tmp/cliplib_negproof_backup
FAIL=""

say()  { echo "  $*"; }
tag()  { echo "ATTACK $1"; }

require_bite() { # $1 attack tag, $2 rc of the gate run
  if [ "$2" -eq 0 ]; then
    say "BITE MISSED — gate stayed green under [$1]"
    FAIL="$FAIL $1"
  else
    say "bit ✓ ($1 — rc=$2, gate refused)"
  fi
}

# the attacked surfaces are backed up BEFORE anything runs
mkdir -p "$BAK"
cp src/com/hark/neokey/ClipRepo.java src/com/hark/neokey/KeyboardView.java "$BAK/"

heal_and_green() {
  cp "$BAK/ClipRepo.java" src/com/hark/neokey/ClipRepo.java
  cp "$BAK/KeyboardView.java" src/com/hark/neokey/KeyboardView.java
  if ci/cliplib_gate.sh >/tmp/cliplib_heal.log 2>&1; then
    say "heal ✓ (backup bytes restored = full green gate again)"
  else
    say "HEAL FAILED — gate still red after restoring the backup (see /tmp/cliplib_heal.log)"
    FAIL="$FAIL heal.$1"
  fi
}

{
  echo "# cliplib-negative-proof — W48 (2026-11-23), $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "# Three attacks against ci/cliplib_gate.sh; each must bite its exact tag,"
  echo "# then the clean tree must heal to a full green gate (containment)."

  # baseline: the gate is green BEFORE any attack
  echo ""
  tag "baseline"
  if ci/cliplib_gate.sh >/tmp/cliplib_base.log 2>&1; then
    say "baseline ✓ (CLIPLIB_GATE_OK on the clean tree)"
  else
    say "baseline FAILED — the proof means nothing unless the gate starts green"
    echo "PROOF_FAILED baseline"
    exit 1
  fi

  # ---- attack 1: the tag-count cap disappears -------------------------------
  echo ""
  tag "tamper.tags-cap"
  sed -i 's/if (keep.size() == MAX_TAGS) break;/if (false) break;/' src/com/hark/neokey/ClipRepo.java
  if grep -q 'if (false) break;' src/com/hark/neokey/ClipRepo.java; then
    ci/cliplib_gate.sh >/tmp/cliplib_a1.log 2>&1; require_bite "tamper.tags-cap" $?
    grep -q "CLIPLIB_GATE_FAILED" /tmp/cliplib_a1.log && say "verdict: $(grep 'CLIPLIB_GATE_FAILED' /tmp/cliplib_a1.log | head -1)"
  else
    say "sed missed its target — the attack itself is broken"
    FAIL="$FAIL sed.a1"
  fi
  heal_and_green "a1"

  # ---- attack 2: the shortcut uniqueness guard disappears -------------------
  echo ""
  tag "tamper.sc-uniqueness"
  sed -i 's#if (!db.query("SELECT id FROM clips WHERE shortcut=? AND id<>? AND kind=0 LIMIT 1", s, id).isEmpty()) return false;#if (false) return false;#' src/com/hark/neokey/ClipRepo.java
  if grep -q 'if (false) return false;' src/com/hark/neokey/ClipRepo.java; then
    ci/cliplib_gate.sh >/tmp/cliplib_a2.log 2>&1; require_bite "tamper.sc-uniqueness" $?
    grep -q "CLIPLIB_GATE_FAILED" /tmp/cliplib_a2.log && say "verdict: $(grep 'CLIPLIB_GATE_FAILED' /tmp/cliplib_a2.log | head -1)"
  else
    say "sed missed its target — the attack itself is broken"
    FAIL="$FAIL sed.a2"
  fi
  heal_and_green "a2"

  # ---- attack 3: a pin-reorder action key returns to the sheet --------------
  echo ""
  tag "tamper.arrows.returned"
  sed -i 's/CA_DELETE = 9, CA_BACK = 12, CA_EDIT = 3, CA_TEMPLATE = 13, CA_EXPORT = 14;/CA_DELETE = 9, CA_BACK = 12, CA_EDIT = 3, CA_TEMPLATE = 13, CA_EXPORT = 14, CA_UP = 10;/' src/com/hark/neokey/KeyboardView.java
  if grep -q 'CA_UP = 10;' src/com/hark/neokey/KeyboardView.java; then
    ci/cliplib_gate.sh >/tmp/cliplib_a3.log 2>&1; require_bite "tamper.arrows.returned" $?
    grep -q "CLIPLIB_GATE_FAILED" /tmp/cliplib_a3.log && say "verdict: $(grep 'CLIPLIB_GATE_FAILED' /tmp/cliplib_a3.log | head -1)"
  else
    say "sed missed its target — the attack itself is broken"
    FAIL="$FAIL sed.a3"
  fi
  heal_and_green "a3"

  # ---- verdict ---------------------------------------------------------------
  echo ""
  if [ -z "$FAIL" ]; then
    echo "PROOF_PASSED — all three attacks bit their exact tags and the clean tree healed green after each"
    echo "# exit=0"
    exit 0
  fi
  echo "PROOF_FAILED — missed bites:$FAIL"
  echo "# exit=1"
  exit 1
} | tee "$LOG"
RC=${PIPESTATUS[0]}
[ "$RC" -eq 0 ] && echo "CLIPLIB NEGATIVE PROOF SAVED → $LOG" || echo "CLIPLIB NEGATIVE PROOF FAILED (rc=$RC)"
exit $RC
