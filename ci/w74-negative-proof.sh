#!/bin/bash
# W74 saved negative proof — ci/w74-negative-proof.sh
#
#   ثلاث هجمات على قانون ThemeChannel، كل واحدة تعض بوسمها الحرفي ثم
#   يُشفى الهدف بايتياً (git-clean والبوابة خضراء على الشجرة النقية):
#     «chan.pin»   — تعطيل مقارنة الدبوس الذهبي في open
#                    → «forge: دبوس مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)»
#     «chan.sig»   — تعطيل مقارنة التوقيع الخارجي في open
#                    → «forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)»
#     «chan.style» — تعطيل رفض القالب خارج النطاق في entryLine (النشر)
#                    → «forge: النشر يرفض قالباً خارج النطاق (CHAN_ENTRY)»
#
#   Verdict: PROOF PASSED w74 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w74-negative-proof.log
GATE=ci/themechannel_gate.sh
TARGET=src/com/hark/neokey/ThemeChannel.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w74-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; THEMECHANNELGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w74-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w74-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "chan.pin" ] && \
  try chan.pin "s|if (!CommunityDict.sha256Hex(headOnly).equals(pin))|if (false)|" 'forge: دبوس مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)'
[ "$ATK" = "all" ] || [ "$ATK" = "chan.sig" ] && \
  try chan.sig "s|if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);|if (false) throw new IllegalArgumentException(TAG_SIG);|" 'forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)'
[ "$ATK" = "all" ] || [ "$ATK" = "chan.style" ] && \
  try chan.style "s#if (e.style < 0 || e.style >= GenThemes.STYLE_COUNT)#if (false)#" 'forge: النشر يرفض قالباً خارج النطاق (CHAN_ENTRY)'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w74 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w74 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w74: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w74-final.log 2>&1 && grep -qE 'RESULT [0-9]+ passed, 0 failed' /tmp/w74-final.log; then
  echo "PROOF PASSED w74 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: THEMECHANNELGATE_OK on the clean tree — the golden pin over the header+entries, the envelope signature law, and the seal refusing an out-of-range style)" >> "$LOG"
  echo "PROOF PASSED w74 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: THEMECHANNELGATE_OK on the clean tree)"
  exit 0
fi
echo "PROOF FAILED w74: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w74: the clean tree did not heal green"
exit 1
