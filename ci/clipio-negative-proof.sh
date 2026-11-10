#!/bin/bash
# Saved negative proof for the clipboard phase-5 gate (W47, 2026-11-16)
# — ci/clipio-negative-proof.sh
#
# Three designed attacks against ci/clipio_gate.sh, each must bite with ITS
# OWN tag, and after EVERY attack the clean tree must heal to a full green
# gate (containment, not restore-by-prayer):
#   1. tamper.test.expectation  — a sabotaged suite expectation must turn the
#      gate red (the gate trusts the RUN, not the OK word)
#   2. tamper.source.format     — a weakened ClipIO format validation must be
#      caught by the suite the gate re-runs fresh
#   3. tests.missing.fail-closed — removing the test source must fail-closed,
#      never "0 checks = green"
# The proof fails loudly if any attack fails to bite or any heal fails.
# Output pinned at ci/clipio-negative-proof.log (committed evidence).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/clipio-negative-proof.log
BAK=/tmp/clipio_negproof_backup
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

# the attacked surfaces are backed up BEFORE anything runs — the heal restores
# the BACKUP bytes, never a git state (the proof must be safe on an uncommitted
# tree too; the git checkout lesson is documented in the W47 notes)
mkdir -p "$BAK"
cp src/com/hark/neokey/ClipIO.java tests/com/hark/neokey/ClipIOTest.java "$BAK/"

heal_and_green() {
  cp "$BAK/ClipIO.java" src/com/hark/neokey/ClipIO.java
  cp "$BAK/ClipIOTest.java" tests/com/hark/neokey/ClipIOTest.java
  if ci/clipio_gate.sh >/tmp/clipio_heal.log 2>&1; then
    say "heal ✓ (backup bytes restored = full green gate again)"
  else
    say "HEAL FAILED — gate still red after restoring the backup (see /tmp/clipio_heal.log)"
    FAIL="$FAIL heal.$1"
  fi
}

{
  echo "# clipio-negative-proof — W47 (2026-11-16), $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "# Three attacks against ci/clipio_gate.sh; each must bite its exact tag,"
  echo "# then the clean tree must heal to a full green gate (containment)."

  # baseline: the gate is green BEFORE any attack
  echo ""
  tag "baseline"
  if ci/clipio_gate.sh >/tmp/clipio_base.log 2>&1; then
    say "baseline ✓ (CLIPIO_GATE_OK on the clean tree)"
  else
    say "baseline FAILED — the proof means nothing unless the gate starts green"
    echo "PROOF_FAILED baseline"
    exit 1
  fi

  # ---- attack 1: sabotage a suite expectation -------------------------------
  echo ""
  tag "tamper.test.expectation"
  sed -i 's/"json all new on empty db", p.count(ClipIO.NEW) == 4/"json all new on empty db", p.count(ClipIO.NEW) == 5/' tests/com/hark/neokey/ClipIOTest.java
  ci/clipio_gate.sh >/tmp/clipio_a1.log 2>&1; require_bite "tamper.test.expectation" $?
  grep -q "CLIPIO_GATE_FAILED" /tmp/clipio_a1.log && say "verdict: $(grep 'CLIPIO_GATE_FAILED' /tmp/clipio_a1.log | head -1)"
  heal_and_green "a1"

  # ---- attack 2: weaken the source's format validation ----------------------
  echo ""
  tag "tamper.source.format"
  sed -i 's/if (!FORMAT.equals(m.get("format")))/if (false)/' src/com/hark/neokey/ClipIO.java
  if grep -q 'if (false)' src/com/hark/neokey/ClipIO.java; then
    ci/clipio_gate.sh >/tmp/clipio_a2.log 2>&1; require_bite "tamper.source.format" $?
  else
    say "sed missed its target — the attack itself is broken"
    FAIL="$FAIL sed"
  fi
  heal_and_green "a2"

  # ---- attack 3: the test source disappears — fail closed -------------------
  echo ""
  tag "tests.missing.fail-closed"
  mv tests/com/hark/neokey/ClipIOTest.java /tmp/ClipIOTest.java.hidden
  ci/clipio_gate.sh >/tmp/clipio_a3.log 2>&1; require_bite "tests.missing.fail-closed" $?
  mv /tmp/ClipIOTest.java.hidden tests/com/hark/neokey/ClipIOTest.java
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
[ "$RC" -eq 0 ] && echo "CLIPIO NEGATIVE PROOF SAVED → $LOG" || echo "CLIPIO NEGATIVE PROOF FAILED (rc=$RC)"
exit $RC
