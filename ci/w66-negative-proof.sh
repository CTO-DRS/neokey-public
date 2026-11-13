#!/bin/bash
# W66 negative proof (v2 pattern) — ci/w66-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact (unpiped attacks, an earned bite count, an unconditional
#   restore, git-clean targets, a green final heal).
#
#   Attacks:
#     cert.digest   — the digest computation broken (always a fixed fake
#                     digest equal to the pinned one) → the battery must
#                     bite «sha: المحتوى أ → البصمة الثابتة»
#     cert.failclose— the fail-closed law broken (unread/missing artifact
#                     treated as a match) → the battery must bite
#                     «cert: الأثر المفقود → CERT_UNKNOWN»
#     cert.display  — the certificate display broken (claims a match on
#                     any state) → the battery must bite
#                     «cert: بايت مزروع → CERT_MISMATCH لا مطابقة»
#
#   Verdict: PROOF PASSED w66 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w66-negative-proof.log
GATE=ci/cert_gate.sh
TARGET=src/com/hark/neokey/IntegrityCert.java

save() {
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w66-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; CERTGATE_OK on the clean tree)" >> "$LOG"
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
  if bash "$GATE" > /tmp/w66-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w66-atk.log; then
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

[ "$ATK" = "all" ] || [ "$ATK" = "cert.digest" ] && \
  try cert.digest 's|return hex(md.digest());|return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";|' 'sha: المحتوى أ → البصمة الثابتة'
[ "$ATK" = "all" ] || [ "$ATK" = "cert.failclose" ] && \
  try cert.failclose 's|return new Cert(UNKNOWN,|return new Cert(MATCH,|' 'cert: الأثر المفقود → CERT_UNKNOWN'
[ "$ATK" = "all" ] || [ "$ATK" = "cert.display" ] && \
  try cert.display 's|return new Cert(MISMATCH,|return new Cert(MATCH,|' 'cert: بايت مزروع → CERT_MISMATCH لا مطابقة'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w66 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w66 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w66: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w66-final.log 2>&1 && grep -q 'RESULT 12 passed, 0 failed' /tmp/w66-final.log; then
  echo "PROOF PASSED w66 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: CERTGATE_OK on the clean tree — battery 12, the live signed artifact byte-exact to the raised statement)" >> "$LOG"
  echo "PROOF PASSED w66 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: CERTGATE_OK on the clean tree — battery 12, the live signed artifact byte-exact to the raised statement)"
  exit 0
fi
echo "PROOF FAILED w66: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w66: the clean tree did not heal green"
exit 1
