#!/bin/bash
# W62 negative proof (v2 pattern) — ci/w62-negative-proof.sh
#
#   Three REAL on-disk tampering attacks against LIVE committed assets
#   must bite their exact tags inside the reassert gate and heal
#   byte-exact (the restore is a git checkout of HEAD — the byte-exact
#   heal, verified git-clean before the next attack runs).
#
#   Attacks:
#     assert.root — a live root_index.txt line corrupted → the gate must
#                   bite «root(index): a corrupt copy must be REFUSED»…
#                   the LIVE load check: «root(index): the LIVE asset (HEAD) loads»
#     assert.gram — a live grammar_rules.txt corrupted → the grammar
#                   live-load check must fail
#     assert.nqf  — a live nnet_ar.nqf byte flipped → the nnet live-load
#                   check must fail
#
#   Verdict: PROOF PASSED w62 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w62-negative-proof.log
GATE=ci/reassert_gate.sh

save() {
  git diff --quiet -- "$1" || { echo "REFUSING dirty attack target: $1"; return 2; }
  cp "$1" "$1.proofbak"
}

restore() {  # git checkout = the byte-exact heal (the asset is restored from HEAD)
  git checkout -- "$1" && rm -f "$1.proofbak"
  if git diff --quiet -- "$1"; then
    echo "healed: $1 byte-exact (git-clean vs HEAD)" >> "$LOG"
    echo "healed: $1 byte-exact (git-clean vs HEAD)"
    return 0
  fi
  echo "HEAL FAILED for $1" >> "$LOG"
  echo "HEAL FAILED for $1"
  return 3
}

attack() {  # attack <name> <asset> <corrupt-expr> <expected-fail-tag>
  local name="$1" asset="$2" expr="$3" tag="$4"
  save "$asset" || return 2
  sed -i "$expr" "$asset" 2>/dev/null || printf '\x00\x00' | dd of="$asset" bs=1 seek=200 conv=notrunc 2>/dev/null
  local rc=1
  if bash "$GATE" > /tmp/w62-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a corrupted live asset" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "GATE-FAIL $tag" /tmp/w62-atk.log; then
    echo "bit: $name → GATE-FAIL $tag" >> "$LOG"
    echo "bit: $name → GATE-FAIL $tag"
    rc=0
  else
    echo "WRONG TAG ($name): the gate failed but not with «$tag»" >> "$LOG"
    echo "WRONG TAG ($name)"
  fi
  restore "$asset" || return 3
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

[ "$ATK" = "all" ] || [ "$ATK" = "assert.root" ] && \
  try assert.root assets/root_index.txt 's/^/XX /' 'root(index): the LIVE asset (HEAD) loads'
[ "$ATK" = "all" ] || [ "$ATK" = "assert.gram" ] && \
  try assert.gram assets/grammar_rules.txt 's/^/XX /' 'grammar(rules): the LIVE asset (HEAD) loads'

# ---- attack: assert.nqf — a GUARANTEED byte flip (binary: dd-safe XOR) -----
[ "$ATK" = "all" ] || [ "$ATK" = "assert.nqf" ] && {
  echo "───── attack: assert.nqf ─────" >> "$LOG"
  echo "───── attack: assert.nqf ─────"
  save assets/nnet_ar.nqf
  python3 -c "p='assets/nnet_ar.nqf'; b=bytearray(open(p,'rb').read()); b[400]^=0x5A; open(p,'wb').write(bytes(b))"
  RC=1
  if bash "$GATE" > /tmp/w62-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE (assert.nqf): the gate passed a corrupted live model" >> "$LOG"
    echo "ATTACK DID NOT BITE (assert.nqf)"
  elif grep -q 'GATE-FAIL nnet(nqf): the LIVE asset (HEAD) loads' /tmp/w62-atk.log; then
    echo "bit: assert.nqf → GATE-FAIL nnet(nqf): the LIVE asset (HEAD) loads" >> "$LOG"
    echo "bit: assert.nqf → GATE-FAIL nnet(nqf): the LIVE asset (HEAD) loads"
    RC=0
  else
    echo "WRONG TAG (assert.nqf)" >> "$LOG"; echo "WRONG TAG (assert.nqf)"
  fi
  restore assets/nnet_ar.nqf || RC=3
  if [ $RC -eq 0 ]; then BITES=$((BITES+1)); else FAILED=$((FAILED+1)); fi
}

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w62 bites=$BITES/3 failures=$FAILED" >> "$LOG"
  echo "PROOF FAILED w62 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git status -s assets/ | grep -q . && { echo "PROOF FAILED w62: an asset is dirty"; exit 1; }
if bash "$GATE" > /tmp/w62-final.log 2>&1 && grep -q 'REASSERTGATE_OK dex=' /tmp/w62-final.log; then
  echo "PROOF PASSED w62 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: REASSERTGATE_OK with a fresh dex margin above the documented floor=500)" >> "$LOG"
  echo "PROOF PASSED w62 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: REASSERTGATE_OK with a fresh dex margin above the documented floor=500)"
  exit 0
fi
echo "PROOF FAILED w62: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w62: the clean tree did not heal green"
exit 1
