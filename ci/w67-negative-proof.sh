#!/bin/bash
# W67 negative proof (v2 pattern) — ci/w67-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     panic.confirm  — the two-step confirm broken (a wipe that runs
#                      without matching the armed token) → the battery
#                      must bite «confirm: بلا رمز → PANIC_REFUSED»
#     panic.zerofill — the byte-void law broken (a logical delete: the
#                      store byte-array is dropped instead of rewritten
#                      to zeros) → the battery must bite
#                      «wiped: اللمسة المؤكدة → PANIC_WIPED»
#     panic.protect  — the signed-backup immunity broken (the protected
#                      stores get wiped too) → the battery must bite
#                      «protect: النسخة الموقعة بايت-لطرفاً»
#
#   Verdict: PROOF PASSED w67 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w67-negative-proof.log
GATE=ci/panic_gate.sh
TARGET=src/com/hark/neokey/PanicWipe.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w67-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; PANICGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w67-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w67-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "panic.confirm" ] && \
  try panic.confirm 's|!confirmToken.equals(expectedToken)|confirmToken.equals(expectedToken)|' 'confirm: رمز خاطئ → PANIC_REFUSED (البيانات سليمة)'
[ "$ATK" = "all" ] || [ "$ATK" = "panic.zerofill" ] && \
  try panic.zerofill 's|s.data\[i\] = 0;|s.data[i] = (byte) 127;|' 'wiped: اللمسة المؤكدة → PANIC_WIPED وكل بايت صفر بطول الأصل'
[ "$ATK" = "all" ] || [ "$ATK" = "panic.protect" ] && \
  try panic.protect 's|if (s.name != null && s.name.startsWith("signed-backup")) {|if (false) {|' 'protect: النسخة الموقعة بايت-لطرفاً بعد اللمسة وتظهر في kept'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w67 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w67 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w67: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w67-final.log 2>&1 && grep -q 'RESULT 10 passed, 0 failed' /tmp/w67-final.log; then
  echo "PROOF PASSED w67 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: PANICGATE_OK on the clean tree — battery 10, the literal byte-void wipe with the signed-backup immunity and the stealth ALL_CLOSED)" >> "$LOG"
  echo "PROOF PASSED w67 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: PANICGATE_OK on the clean tree — battery 10, the literal byte-void wipe with the signed-backup immunity and the stealth ALL_CLOSED)"
  exit 0
fi
echo "PROOF FAILED w67: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w67: the clean tree did not heal green"
exit 1
