#!/bin/bash
# W69 negative proof (v2 pattern) — ci/w69-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     genthemes.det      — the determinism law broken (a stateful counter
#                          leaks into the pixel rows: same seed -> different
#                          pixels) -> the battery must bite
#                          «det: نفس البذرة والحجم = نفس البكسلات بايتياً»
#     genthemes.contrast — the accent-contrast push loop disabled (the
#                          accent ships unadjusted) -> the battery must bite
#                          «contrast: نسبة نص المميز فوق المميز ≥ 3.0 على 300 بذرة»
#     genthemes.hash     — the pixel hash returns a constant (the tamper
#                          detector and the seed-distinctness law broken)
#                          -> the battery must bite
#                          «hash: تحوير بكسل واحد يغيّر الختم (م كاشف التحريف)»
#
#   Verdict: PROOF PASSED w69 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w69-negative-proof.log
GATE=ci/genthemes_gate.sh
TARGET=src/com/hark/neokey/GenThemes.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w69-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; GENTHEMESGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w69-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w69-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "genthemes.det" ] && \
  try genthemes.det 's|static int\[\] render(Pal p, int w, int h) {|static int proofN = 0; static int[] render(Pal p, int w, int h) { proofN++;|; s|int row = lerp(p.bg, p.bg2, (y \* 255) / den);|int row = lerp(p.bg, p.bg2, (y * 255) / den + proofN);|' 'det: نفس البذرة والحجم = نفس البكسلات بايتياً'
[ "$ATK" = "all" ] || [ "$ATK" = "genthemes.contrast" ] && \
  try genthemes.contrast 's|for (int k = 0; k < 24; k++) {|for (int k = 0; k < 0; k++) {|' 'contrast: نسبة نص المميز فوق المميز ≥ 3.0 على 300 بذرة'
[ "$ATK" = "all" ] || [ "$ATK" = "genthemes.hash" ] && \
  try genthemes.hash 's|return sb.toString();|return "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f";|' 'hash: تحوير بكسل واحد يغيّر الختم (م كاشف التحريف)'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w69 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w69 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w69: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w69-final.log 2>&1 && grep -q 'RESULT 19 passed, 0 failed' /tmp/w69-final.log; then
  echo "PROOF PASSED w69 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: GENTHEMESGATE_OK on the clean tree — battery 19, the determinism law byte-exact, the contrast law measured on 300 seeds, five reference pixel hashes, and the tamper-detector hash)" >> "$LOG"
  echo "PROOF PASSED w69 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: GENTHEMESGATE_OK on the clean tree — battery 19, the determinism law byte-exact, the contrast law measured on 300 seeds, five reference pixel hashes, and the tamper-detector hash)"
  exit 0
fi
echo "PROOF FAILED w69: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w69: the clean tree did not heal green"
exit 1
