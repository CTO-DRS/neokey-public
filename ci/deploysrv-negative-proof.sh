#!/bin/bash
# Negative proof for the deploysrv gate (phase 7 W2, week 35) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp snapshot, restore INSIDE run_attack — the weeks-24/25 lesson)
# and the gate re-verified GREEN.
#
#   1) identity tamper: the pack assembler's week identity rewritten one
#      week behind — the java gate stays self-consistent (a statement can
#      agree with a lie),
#      so the BASH anchor bites: the verdict must carry 77 / 3.0, the week
#      published identity («المرجع = المنشور» — the pack's identity is
#      checked against the week, not against itself). The tamper is
#      BUMP-INDEPENDENT (any current literal -> 76, always wrong): a sed
#      pinned to last week's value goes stale at the next bump (the W34
#      billing proof caught this class of drift; W3 applies it).
#   2) dead guard: SyncServerPack's jar-sha comparison neutered — the
#      statement_sha_tampered negative STOPS BITING with its expected tag
#      and the gate fails on it: a checker that compares nothing is a
#      failure, caught by the gate's own negative.
#   3) hidden assembler: SyncServerPack.java removed — the gate fails closed
#      at compile time (a missing assembler is never a silent pass).
set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=0

echo "== deploysrv negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

mkdir -p build/deploysrv-proof-backup
cp tests/com/hark/neokey/SyncServerPack.java build/deploysrv-proof-backup/SyncServerPack.java

gate() { bash ci/deploysrv_gate.sh > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker, $3 expected line (grep -E), $4 restore
  local name="$1" attacker="$2" expect="$3" restore="$4"
  echo ""
  echo "--- attack: $name"
  eval "$attacker"
  local rc=$(gate /tmp/ds_neg.txt)
  if [ "$rc" -eq 0 ]; then
    echo "ATTACK DID NOT BITE: $name (gate rc=0)"; FAILED=1
  elif ! grep -qE "$expect" /tmp/ds_neg.txt; then
    echo "WRONG FAILURE: $name (expected /$expect/)"; tail -4 /tmp/ds_neg.txt; FAILED=1
  else
    echo "bit: $(grep -m1 -E "$expect" /tmp/ds_neg.txt)"
  fi
  eval "$restore"
  local rrc=$(gate /tmp/ds_heal.txt)
  if [ "$rrc" -ne 0 ] || ! grep -q '^DEPLOYSRVGATE_OK' /tmp/ds_heal.txt; then
    echo "RESTORE DID NOT HEAL: $name (rc=$rrc)"; tail -4 /tmp/ds_heal.txt; FAILED=1
  else
    echo "restored: green re-verify OK ($(grep -m1 '^DEPLOYSRVGATE_OK' /tmp/ds_heal.txt))"
  fi
}

# ---- baseline ------------------------------------------------------------------
BASE_RC=$(gate /tmp/ds_base.txt)
if [ "$BASE_RC" -ne 0 ] || ! grep -q '^DEPLOYSRVGATE_OK' /tmp/ds_base.txt; then
  echo "BASELINE NOT GREEN (rc=$BASE_RC) — fix the tree before proving negatives"; tail -5 /tmp/ds_base.txt; exit 1
fi
echo "baseline: $(grep -m1 '^DEPLOYSRVGATE_OK' /tmp/ds_base.txt)"

run_attack "pack identity tamper (week identity -> 76, always one behind; the statement agrees with the lie, the bash anchor does not)" \
  "sed -i 's/static final String VC = \"[0-9]*\";/static final String VC = \"76\";/' tests/com/hark/neokey/SyncServerPack.java" \
  "DEPLOYSRVGATE_FAILED anchor.identity" \
  "cp build/deploysrv-proof-backup/SyncServerPack.java tests/com/hark/neokey/SyncServerPack.java"

run_attack "dead guard (the pack checker's jar-sha comparison neutered)" \
  "sed -i 's|if (!sha256Hex(read(packDir.resolve(JAR_NAME))).equals(jarSha)) refuse(\"pack.jar_sha\");|/\* ATTACK: neutered \*/|' tests/com/hark/neokey/SyncServerPack.java" \
  "DEPLOYSRV regression: neg.statement_sha_tampered" \
  "cp build/deploysrv-proof-backup/SyncServerPack.java tests/com/hark/neokey/SyncServerPack.java"

run_attack "hidden assembler (SyncServerPack.java removed)" \
  "mv tests/com/hark/neokey/SyncServerPack.java /tmp/SyncServerPack.java.hidden" \
  "DEPLOYSRVGATE_FAILED" \
  "cp build/deploysrv-proof-backup/SyncServerPack.java tests/com/hark/neokey/SyncServerPack.java"

echo ""
if [ "$FAILED" -ne 0 ]; then
  echo "== PROOF FAILED: an attack did not bite or a restore did not heal =="
  exit 1
fi
FINAL=$(gate /tmp/ds_final.txt)
if [ "$FINAL" -ne 0 ] || ! grep -q '^DEPLOYSRVGATE_OK' /tmp/ds_final.txt; then
  echo "== PROOF FAILED: final gate not green =="; exit 1
fi
echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
grep -m1 '^DEPLOYSRVGATE_OK' /tmp/ds_final.txt
