#!/bin/bash
# W70 saved negative proof — ci/w70-negative-proof.sh
#
#   ثلاث هجمات على قانون AACBoard، كل واحدة تعض بوسمها الحرفي ثم
#   يُشفى الهدف بايتياً (git-clean والبوابة خضراء على الشجرة النقية):
#     «aac.det»    — كسر الحتمية بعدّاد كائن يقلب ترتيب العبارات بين
#                    الاستدعاءين (نفس الفئة = لوحتان مختلفتان)
#                    → «det: نفس الفئة والمستودع = نفس الصفوف والعبارات حرفاً»
#     «aac.cats»   — تحوير اسم الفئة الثابتة الرابعة (الطوارئ)
#                    → «cats: الفئات الأربع الثابتة بالترتيب الموثق (احتياجات/مشاعر/تحيات/طوارئ)»
#     «aac.insert» — تعطيل الرفض المغلق للعبارة الفاضية
#                    → «insert: العبارة الفاضية/null ترفض موثقاً (AAC_EMPTY_PHRASE)»
#
#   Verdict: PROOF PASSED w70 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w70-negative-proof.log
GATE=ci/aac_gate.sh
TARGET=src/com/hark/neokey/AACBoard.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w70-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; AACGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w70-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w70-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "aac.det" ] && \
  try aac.det 's|/\*\* العبارة الفعّالة للفئة|static int proofN = 0;\n    /** العبارة الفعّالة للفئة|; s|Snapshot s = new Snapshot(category, taken);|proofN++; Snapshot s = new Snapshot(category, taken);|; s|while (all.size() > PHRASES_MAX) all.remove(all.size() - 1);|while (all.size() > PHRASES_MAX) all.remove(all.size() - 1); if (proofN % 2 == 1 \&\& all.size() > 1) { String tp = all.get(0); all.set(0, all.get(1)); all.set(1, tp); }|' 'det: نفس الفئة والمستودع = نفس الصفوف والعبارات حرفاً'
[ "$ATK" = "all" ] || [ "$ATK" = "aac.cats" ] && \
  try aac.cats 's|public static final String CAT_URGENT = "طوارئ";|public static final String CAT_URGENT = "خطر";|' 'cats: الفئات الأربع الثابتة بالترتيب الموثق (احتياجات/مشاعر/تحيات/طوارئ)'
[ "$ATK" = "all" ] || [ "$ATK" = "aac.insert" ] && \
  try aac.insert 's|if (p.isEmpty()) return new Insert(false, null, EMPTY_PHRASE);|if (false) return new Insert(false, null, EMPTY_PHRASE);|' 'insert: العبارة الفاضية/null ترفض موثقاً (AAC_EMPTY_PHRASE)'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w70 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w70 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w70: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w70-final.log 2>&1 && grep -q 'RESULT 13 passed, 0 failed' /tmp/w70-final.log; then
  echo "PROOF PASSED w70 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: AACGATE_OK on the clean tree — battery 13, the four fixed categories documented and fail-closed, the determinism law measured twice, the big-touch grid capped at 3 per row, the community-dictionary customization with its 12 cap and its non-Arabic declaration, and the pure insert plan refusing empty/over-limit)" >> "$LOG"
  echo "PROOF PASSED w70 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: AACGATE_OK on the clean tree — battery 13)"
  exit 0
fi
echo "PROOF FAILED w70: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w70: the clean tree did not heal green"
exit 1
