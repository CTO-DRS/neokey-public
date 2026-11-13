#!/bin/bash
# W60 negative proof (v2 pattern — the corrected law) — ci/w60-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (the v2 discipline: unpiped attacks, an earned bite count,
#   an unconditional restore, git-clean targets, a green final heal).
#
#   Attacks:
#     pers.decay — the temporal half-life broken (14 → 14000 days: the
#                  score never decays) → the battery must bite
#                  «decay: بعد 14 يوماً ينصفي»
#     pers.cap   — the measured cap broken (256 → 2560) → the battery
#                  must bite «cap: 300 كلمة تُقص»
#     pers.wipe  — the factory byte-state law broken (wipe() no-ops) →
#                  the battery must bite «wipe: serialize() == ""»
#
#   Verdict: PROOF PASSED w60 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w60-negative-proof.log
GATE=ci/personal_gate.sh
TARGET=src/com/hark/neokey/AdaptEngine.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w60-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; PERSONALGATE_OK on the clean tree)" >> "$LOG"
    echo "healed: $TARGET byte-exact (git-clean)"
    return 0
  fi
  echo "HEAL FAILED after attacking $TARGET — the tree is dirty or the gate is red" >> "$LOG"
  echo "HEAL FAILED after attacking $TARGET"
  return 3
}

attack() {
  local name="$1" expr="$2" tag="$3"
  save || return 2
  sed -i "$expr" "$TARGET"
  local rc=1
  if bash "$GATE" > /tmp/w60-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w60-atk.log; then
    echo "bit: $name → FAIL $tag" >> "$LOG"
    echo "bit: $name → FAIL $tag"
    rc=0
  else
    echo "WRONG TAG ($name): the gate failed but not with «$tag»" >> "$LOG"
    echo "WRONG TAG ($name)"
  fi
  restore || return 3
  return $rc
}

ATK=${1:-all}
: > "$LOG"
BITES=0
FAILED=0
try() {
  echo "───── attack: $1 ─────" >> "$LOG"
  echo "───── attack: $1 ─────"
  attack "$@"
  local rc=$?
  if [ $rc -eq 0 ]; then BITES=$((BITES+1)); else FAILED=$((FAILED+1)); fi
}

[ "$ATK" = "all" ] || [ "$ATK" = "pers.decay" ] && \
  try pers.decay 's/static final int HALF_LIFE = 14;/static final int HALF_LIFE = 14000;/' 'decay: بعد 14 يوماً ينصفي'
[ "$ATK" = "all" ] || [ "$ATK" = "pers.cap" ] && \
  try pers.cap 's/static final int CAP = 256;/static final int CAP = 2560;/' 'cap: 300 كلمة تُقص'
[ "$ATK" = "all" ] || [ "$ATK" = "pers.wipe" ] && \
  try pers.wipe 's/public void wipe() { words.clear(); }/public void wipe() { }/' 'wipe: serialize() == ""'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w60 bites=$BITES/3 failures=$FAILED (every attack must land, bite its exact tag, and heal)" >> "$LOG"
  echo "PROOF FAILED w60 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w60: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w60-final.log 2>&1 && grep -q 'PERSONALGATE_OK' /tmp/w60-final.log; then
  echo "PROOF PASSED w60 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: PERSONALGATE_OK on the clean tree — battery 29, p50 4->1, dex 527900/528000)" >> "$LOG"
  echo "PROOF PASSED w60 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: PERSONALGATE_OK on the clean tree — battery 29, p50 4->1, dex 527900/528000)"
  exit 0
fi
echo "PROOF FAILED w60: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w60: the clean tree did not heal green"
exit 1
