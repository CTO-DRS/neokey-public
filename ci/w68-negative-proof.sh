#!/bin/bash
# W68 negative proof (v2 pattern) — ci/w68-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     clarity.det    — the determinism law broken (an object-state counter
#                      leaks into the score: same text → different score)
#                      → the battery must bite
#                      «det: نفس النص = نفس الدرجة والمكونات وسطر العرض حرفاً»
#     clarity.sent   — the sentence-length metric broken (always passes)
#                      → the battery must bite
#                      «sent: النص الطويل الجملة يفشل»
#     clarity.filler — the filler metric broken (never counts)
#                      → the battery must bite
#                      «filler: الحشو المعلن يُكتسب ظاهرياً ويُفشل»
#
#   Verdict: PROOF PASSED w68 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w68-negative-proof.log
GATE=ci/clarity_gate.sh
TARGET=src/com/hark/neokey/ClarityMeter.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w68-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; CLARITYGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w68-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w68-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "clarity.det" ] && \
  try clarity.det 's|public static Meter evaluate(String text) {|static int proofDrift; public static Meter evaluate(String text) { proofDrift++;|; s|return new Meter(parts, score, "SCORE_COMPOSED", sb.toString());|return new Meter(parts, score + proofDrift, "SCORE_COMPOSED", sb.toString());|' 'det: نفس النص = نفس الدرجة والمكونات وسطر العرض حرفاً'
[ "$ATK" = "all" ] || [ "$ATK" = "clarity.sent" ] && \
  try clarity.sent 's|boolean sentPass = sentAvg <= SENT_MAX;|boolean sentPass = true;|' 'sent: النص الطويل الجملة يفشل'
[ "$ATK" = "all" ] || [ "$ATK" = "clarity.filler" ] && \
  try clarity.filler 's|int fillers = 0;|int fillers = 0; if (true) { } else|' 'filler: الحشو المعلن يُكتسب ظاهرياً ويُفشل'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w68 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w68 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w68: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w68-final.log 2>&1 && grep -q 'RESULT 17 passed, 0 failed' /tmp/w68-final.log; then
  echo "PROOF PASSED w68 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: CLARITYGATE_OK on the clean tree — battery 17, a reference test for each of the five metrics plus determinism, fail-closed, and the display line)" >> "$LOG"
  echo "PROOF PASSED w68 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: CLARITYGATE_OK on the clean tree — battery 17, a reference test for each of the five metrics plus determinism, fail-closed, and the display line)"
  exit 0
fi
echo "PROOF FAILED w68: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w68: the clean tree did not heal green"
exit 1
