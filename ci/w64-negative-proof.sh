#!/bin/bash
# W64 negative proof (v2 pattern) — ci/w64-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     airseal.fake  — the forged-seal law broken (verifySeal accepts any
#                     claim) → the battery must bite
#                     «fake: ادعاء جلسة معلنة ببصمة بلا شبكة يُرفض»
#     airseal.forget — the irreversible breach law broken (sealOf forgets
#                     the breaches) → the battery must bite
#                     «event: الختم انقلب إلى breached»
#     airseal.fp    — the comparable fingerprint broken (a clock/race leaks
#                     into the digest) → the battery must bite
#                     «fp: نفس الحالة = نفس البصمة على أي مثيل»
#
#   Verdict: PROOF PASSED w64 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w64-negative-proof.log
GATE=ci/airseal_gate.sh
TARGET=src/com/hark/neokey/AirSeal.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w64-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; AIRSEALGATE_OK on the clean tree)" >> "$LOG"
    echo "healed: $TARGET byte-exact (git-clean)"
    return 0
  fi
  echo "HEAL FAILED after attacking $TARGET" >> "$LOG"
  echo "HEAL FAILED after attacking $TARGET"
  return 3
}

attack() {
  local name="$1" expr="$2" tag="$3"
  save || return 2
  sed -i "$expr" "$TARGET"
  local rc=1
  if bash "$GATE" > /tmp/w64-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w64-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "airseal.fake" ] && \
  try airseal.fake 's/public static boolean verifySeal(Seal claimed, Seal computed) {/public static boolean verifySeal(Seal claimed, Seal computed) { if (claimed != null) return true;/' 'fake: ادعاء جلسة معلنة ببصمة بلا شبكة يُرفض'
[ "$ATK" = "all" ] || [ "$ATK" = "airseal.forget" ] && \
  try airseal.forget 's/if (breaches() > 0) kind = BREACHED;/if (false) kind = BREACHED;/' 'event: الختم انقلب إلى breached'
[ "$ATK" = "all" ] || [ "$ATK" = "airseal.fp" ] && \
  try airseal.fp 's/String seed = "AIRSEAL1|"/String seed = "AIRSEAL1|" + System.nanoTime() + "|"/' 'fp: نفس الحالة = نفس البصمة على أي مثيل'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w64 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w64 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w64: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w64-final.log 2>&1 && grep -q 'RESULT 22 passed, 0 failed' /tmp/w64-final.log; then
  echo "PROOF PASSED w64 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: AIRSEALGATE_OK on the clean tree — battery 22, dex 531308/532000)" >> "$LOG"
  echo "PROOF PASSED w64 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: AIRSEALGATE_OK on the clean tree — battery 22, dex 531308/532000)"
  exit 0
fi
echo "PROOF FAILED w64: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w64: the clean tree did not heal green"
exit 1
