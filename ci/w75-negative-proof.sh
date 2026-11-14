#!/bin/bash
# W74 saved negative proof — ci/w75-negative-proof.sh
#
#   ثلاث هجمات على قانون SyncV2، كل واحدة تعض بوسمها الحرفي ثم
#   يُشفى الهدف بايتياً (git-clean والبوابة خضراء على الشجرة النقية):
#     «sv.body»    — تعطيل فحص دبوس المحتوى في open
#                    → «forge: دبوس مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)»
#     «sv.sig»     — تعطيل مقارنة التوقيع الخارجي في open
#                    → «forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)»
#     «sv.op»      — تعطيل حد الحجم في opLine (النشر)
#                    → «forge: النشر يرفض قالباً خارج النطاق (CHAN_ENTRY)»
#
#   Verdict: PROOF PASSED w75 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w75-negative-proof.log
GATE=ci/syncv2_gate.sh
TARGET=src/com/hark/neokey/SyncV2.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w75-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; SYNCV2GATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w75-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w75-atk.log; then
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
  try sv.body "s|if (!CommunityDict.sha256Hex(bodyBytes).equals(bodySha))|if (false)|" 'forge: دبوس محتوى مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)'
[ "$ATK" = "all" ] || [ "$ATK" = "chan.sig" ] && \
  try sv.sig "s|if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);|if (false) throw new IllegalArgumentException(TAG_SIG);|" 'forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)'
[ "$ATK" = "all" ] || [ "$ATK" = "chan.style" ] && \
  try sv.op "s|bytes > 512|bytes > 9999|" 'forge: سطر عملية فوق الحجم يعض (SYNCV2_OP)'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w75 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w75 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w75: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w75-final.log 2>&1 && grep -qE 'RESULT [0-9]+ passed, 0 failed' /tmp/w75-final.log; then
  echo "PROOF PASSED w75 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: SYNCV2GATE_OK on the clean tree — the content pin over the ordered op lines, the envelope signature law, and the size cap on an op line)" >> "$LOG"
  echo "PROOF PASSED w75 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: SYNCV2GATE_OK on the clean tree)"
  exit 0
fi
echo "PROOF FAILED w75: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w75: the clean tree did not heal green"
exit 1
