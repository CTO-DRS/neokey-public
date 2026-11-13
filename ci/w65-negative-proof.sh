#!/bin/bash
# W65 negative proof (v2 pattern) — ci/w65-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     otp.detect — the detection envelope broken (findOtp always null) →
#                  the battery must bite «reference: الدقة 100%»
#     otp.expiry — the expiry math broken (no real delay) → the battery
#                  must bite «expiry: الحساب الحتمي بالبايت»
#     otp.incog  — the incognito law broken (the shield engages under
#                  incognito) → the battery must bite
#                  «incog: التخفي يمنع الدرع كلياً»
#
#   Verdict: PROOF PASSED w65 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w65-negative-proof.log
GATE=ci/otp_gate.sh
TARGET=src/com/hark/neokey/OTPShield.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w65-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; OTPGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w65-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w65-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "otp.detect" ] && \
  try otp.detect 's/public static String findOtp(String text) {/public static String findOtp(String text) { if (true) return null;/' 'reference: الدقة 100%'
[ "$ATK" = "all" ] || [ "$ATK" = "otp.expiry" ] && \
  try otp.expiry 's/return capturedAt + clampTimeout(timeoutSec) \* 1000L;/return capturedAt + 1L;/' 'expiry: الحساب الحتمي بالبايت'
[ "$ATK" = "all" ] || [ "$ATK" = "otp.incog" ] && \
  try otp.incog 's/return !incognito \&\& prefOn;/return true;/' 'incog: التخفي يمنع الدرع كلياً'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w65 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w65 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w65: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w65-final.log 2>&1 && grep -q 'RESULT 26 passed, 0 failed' /tmp/w65-final.log; then
  echo "PROOF PASSED w65 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: OTPGATE_OK on the clean tree — battery 26, reference 26/26)" >> "$LOG"
  echo "PROOF PASSED w65 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: OTPGATE_OK on the clean tree — battery 26, reference 26/26)"
  exit 0
fi
echo "PROOF FAILED w65: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w65: the clean tree did not heal green"
exit 1
