#!/bin/bash
# W71 saved negative proof — ci/w71-negative-proof.sh
#
#   ثلاث هجمات على قانون NumSmart، كل واحدة تعض بوسمها الحرفي ثم
#   يُشفى الهدف بايتياً (git-clean والبوابة خضراء على الشجرة النقية):
#     «num.group»   — تعطيل فاصل الآلاف «٬» (التجميع الثلاثي يختفي)
#                     → «group: التجميع الثلاثي بالفاصل «٬» (1234567→١٬٢٣٤٬٥٦٧)»
#     «num.currency»— تحوير رمز العملة الذهبية (ر.س → ريال)
#                     → «currency: «١٬٢٣٤٫٥٠ ر.س» بالجدول (SAR gold)»
#     «num.hijri.epoch» — تحوير القلم المعياري بيوم واحد (16→15 نوفمبر 1979)
#                     → «hijri: القلم المعياري 1 محرم 1400هـ = 16 نوفمبر 1979م (ذهاب)»
#
#   Verdict: PROOF PASSED w71 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w71-negative-proof.log
GATE=ci/numsmart_gate.sh
TARGET=src/com/hark/neokey/NumSmart.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w71-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; NUMGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w71-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w71-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "num.group" ] && \
  try num.group "s|if (i > 0 \&\& (d.length() - i) % 3 == 0) g.append('٬');|if (false) g.append('٬');|" 'group: التجميع الثلاثي بالفاصل «٬» (1234567→١٬٢٣٤٬٥٦٧)'
[ "$ATK" = "all" ] || [ "$ATK" = "num.currency" ] && \
  try num.currency 's|if ("SAR".equals(code)) return "ر.س";|if ("SAR".equals(code)) return "ريال";|' 'currency: «١٬٢٣٤٫٥٠ ر.س» بالجدول (SAR gold)'
[ "$ATK" = "all" ] || [ "$ATK" = "num.hijri.epoch" ] && \
  try num.hijri.epoch 's|G1979_11_16_ABS = gregAbs(1979, 11, 16);|G1979_11_16_ABS = gregAbs(1979, 11, 15);|' 'hijri: القلم المعياري 1 محرم 1400هـ = 16 نوفمبر 1979م (ذهاب)'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w71 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w71 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w71: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w71-final.log 2>&1 && grep -q 'RESULT 20 passed, 0 failed' /tmp/w71-final.log; then
  echo "PROOF PASSED w71 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: NUMGATE_OK on the clean tree — battery 20, the reference table with gold values, the pinned Hijri epoch, the full go/return over ~66k days, and the sensitive-date refusals)" >> "$LOG"
  echo "PROOF PASSED w71 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: NUMGATE_OK on the clean tree — battery 20)"
  exit 0
fi
echo "PROOF FAILED w71: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w71: the clean tree did not heal green"
exit 1
