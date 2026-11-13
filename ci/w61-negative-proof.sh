#!/bin/bash
# W61 negative proof (v2 pattern) — ci/w61-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     acc.truth — the honest unknown-bucket law broken (unknown sources
#                 keep their raw name instead of counting as «strip») →
#                 the battery must bite «accept: المجهول/الفارغ/null»
#     acc.wipe  — the factory byte-state law broken (wipe() no-ops) →
#                 the battery must bite «wipe: serialize() == ""»
#     acc.feed  — the documented acceptance feed broken (a personal
#                 acceptance no longer reinforces the engine) → the gate
#                 must bite «the documented acceptance feed»
#
#   Verdict: PROOF PASSED w61 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w61-negative-proof.log
GATE=ci/strip_gate.sh
TARGET=src/com/hark/neokey/StripAccept.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w61-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; STRIPGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w61-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w61-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "acc.truth" ] && \
  try acc.truth 's/if (!known) s = STRIP;/if (!known) s = CLIP;/' 'accept: المجهول/الفارغ/null'
[ "$ATK" = "all" ] || [ "$ATK" = "acc.wipe" ] && \
  try acc.wipe 's/public void wipe() { counts.clear(); }/public void wipe() { }/' 'wipe: serialize() == ""'
[ "$ATK" = "all" ] || [ "$ATK" = "acc.feed" ] && \
  try acc.feed 's/if (engine != null && word != null && !word.isEmpty()) engine.learn(word, day);/if (false) engine.learn(word, day);/' 'the documented acceptance feed'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w61 bites=$BITES/3 failures=$FAILED (every attack must land, bite its exact tag, and heal)" >> "$LOG"
  echo "PROOF FAILED w61 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w61: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w61-final.log 2>&1 && grep -q 'STRIPGATE_OK' /tmp/w61-final.log; then
  echo "PROOF PASSED w61 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: STRIPGATE_OK on the clean tree)" >> "$LOG"
  echo "PROOF PASSED w61 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: STRIPGATE_OK on the clean tree)"
  exit 0
fi
echo "PROOF FAILED w61: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w61: the clean tree did not heal green"
exit 1
