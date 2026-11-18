#!/bin/bash
# Negative proof for the REBUILD gate (phase 8 W1 — week 38) — SAVED evidence.
#
# The gate must FAIL on a drifted toolchain artifact, on a drifted PUBLISHED
# pin, and on a dead guard. The pin layer is the anchor (المرجع = المنشور):
# the drill verifies every tool against the printed pins BEFORE anything
# runs, and RebuildGate.java re-computes every pin from tools/ independently
# — a tamper that slips one layer is caught by the other.
#
# Procedure (the weeks-24/25/33/34/35/36/37 pattern): the gate green untampered,
# then three designed attacks, each restored (cp, never git) and re-verified
# GREEN with a fresh full drill — no cached verdicts anywhere.
#
# The proof runs STEPWISE so every step fits one runner invocation and every
# green is still a fresh full drill:
#   ci/rebuild-negative-proof.sh baseline   — cp backups + the gate green
#   ci/rebuild-negative-proof.sh attack1    — toolchain tamper  → bites → restore → green
#   ci/rebuild-negative-proof.sh attack2    — published pin drift → bites → restore → green
#   ci/rebuild-negative-proof.sh attack3    — dead guard → bites → restore → green
#   ci/rebuild-negative-proof.sh finalize   — last full green + the PROOF verdict
# With no argument it runs all steps in sequence (one long runner).
set -uo pipefail
cd "$(dirname "$0")/.."

GATE=ci/rebuild_gate.sh
BACKUP=build/rebuild-proof-backup
STATE=build/rebuild-proof-state
FAILED=0
STEP="${1:-all}"

say() { echo "== rebuild negative proof [$STEP] — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="; }

need_state() { # $1 expected state value
  [ -f "$STATE" ] && [ "$(cat "$STATE")" = "$1" ] || { echo "PROOF_STATE $1 required (got: $(cat "$STATE" 2>/dev/null || echo none))"; exit 1; }
}

gate_green() { # runs the gate; exits non-zero on any failure
  OUT=$($GATE 2>&1); local rc=$?
  local okline=$(echo "$OUT" | grep -m1 'REBUILDGATE_OK' || echo MISSING)
  echo "rc=$rc $okline"
  [ $rc -eq 0 ] && [ "$okline" != "MISSING" ]
}

run_attack() { # $1 name, $2 attacker, $3 expected grep
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  OUT=$($GATE 2>&1); local rc=$?
  echo "rc=$rc"
  echo "$OUT" | grep -E 'REBUILDGATE_FAILED|REBUILD_DRILL_FAILED|REBUILD regression' | head -4 | sed 's/^/verdict: /'
  echo "$OUT" | grep -qE "$expect" || { echo "TAG WRONG (expected '$expect')"; FAILED=1; }
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  cp "$BACKUP/r8.jar" tools/r8.jar
  cp "$BACKUP/android.jar" tools/android.jar
  cp "$BACKUP/rebuild_gate.sh" ci/rebuild_gate.sh
  cp "$BACKUP/RebuildGate.java" tests/com/hark/neokey/RebuildGate.java
  echo "restoring and re-verifying green (a fresh full drill)…"
  gate_green || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

case "$STEP" in
  baseline)
    say
    mkdir -p "$BACKUP"
    cp tools/r8.jar "$BACKUP/r8.jar"
    cp tools/android.jar "$BACKUP/android.jar"
    cp ci/rebuild_gate.sh "$BACKUP/rebuild_gate.sh"
    cp tests/com/hark/neokey/RebuildGate.java "$BACKUP/RebuildGate.java"
    echo "--- baseline (untampered)"
    echo "baseline: $(gate_green)" || { echo "BASELINE NOT GREEN — proof invalid"; exit 1; }
    echo OK > "$STATE"
    echo "--- baseline state: OK"
    ;;
  attack1)
    say; need_state OK
    run_attack "toolchain tamper (tools/r8.jar byte flipped)" \
      'printf "X" | dd of=tools/r8.jar bs=1 seek=100 count=1 conv=notrunc status=none' \
      'REBUILD_DRILL_FAILED tool\.r8\.jar sha'
    [ $FAILED -eq 0 ] && echo ATTACK1_OK > "$STATE" || { echo "PROOF FAILED at attack1"; exit 1; }
    ;;
  attack2)
    say; need_state ATTACK1_OK
    run_attack "published pin drift (android.jar pin flipped in the anchor)" \
      'sed -i "s/d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a/09eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a/" ci/rebuild_gate.sh' \
      'REBUILD_DRILL_FAILED tool\.android\.jar sha'
    [ $FAILED -eq 0 ] && echo ATTACK2_OK > "$STATE" || { echo "PROOF FAILED at attack2"; exit 1; }
    ;;
  attack3)
    say; need_state ATTACK2_OK
    # compilable neuter: the map emptied inside the method — the loop iterates
    # nothing, the guard is always-true, and the file still compiles (the W34
    # dead-guard class, no unreachable-code escape hatch)
    run_attack "dead guard (RebuildGate.verifyPinsAgainstTools neutered)" \
      'sed -i "s|static void verifyPinsAgainstTools(Map<String, String> pins, String toolsDir) {|static void verifyPinsAgainstTools(Map<String, String> pins, String toolsDir) { pins = new java.util.LinkedHashMap<String, String>(); |" tests/com/hark/neokey/RebuildGate.java' \
      'REBUILD regression: pins\.manifest\.char_flip'
    [ $FAILED -eq 0 ] && echo ATTACK3_OK > "$STATE" || { echo "PROOF FAILED at attack3"; exit 1; }
    ;;
  finalize)
    say; need_state ATTACK3_OK
    echo "--- final full green on the restored tree"
    gate_green || { echo "PROOF FAILED at finalize"; exit 1; }
    echo ""
    echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
    echo DONE > "$STATE"
    ;;
  all)
    for s in baseline attack1 attack2 attack3 finalize; do
      "$0" "$s" || exit 1
    done
    ;;
  *) echo "usage: $0 [baseline|attack1|attack2|attack3|finalize|all]"; exit 1;;
esac
