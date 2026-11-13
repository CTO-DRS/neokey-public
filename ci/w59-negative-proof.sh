#!/bin/bash
# W59 negative proof (v2 — the corrected pattern) — ci/w59-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact. No pretending — and v2 fixes the three flaws the W60
#   self-check exposed in the v1 run (documented in NOTES):
#     1. the attack function runs UNPIPED (a piped function ran in a
#        subshell — its exits killed only the subshell and the run
#        continued as if nothing happened),
#     2. the bite count is EARNED (v1 incremented unconditionally),
#     3. the restore ALWAYS runs — even on did-not-bite / wrong-tag /
#        non-landing (v1 left dapl.merge's attack in the tree and the
#        close commit's `git add -A` shipped it — caught by run.sh).
#   The final heal asserts the gate green AND every attack target
#   git-clean (byte-exact vs HEAD).
#
#   Attacks:
#     dapl.seed  — an UNSTABLE profile smuggled into the cold-start seed
#                  → the battery must bite «seed: غير المستقر»
#     dapl.merge — the seeded half-weight broken (a seed leading the full
#                  cap) → the battery must bite «merge: البذرة — نصف الوزن»
#     dapl.cap   — the LRU cap broken ×10 → the battery must bite
#                  «cap: 80 ملفاً»
#
#   Verdict: PROOF PASSED w59 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w59-negative-proof.log
GATE=ci/dialectadapt_gate.sh
TARGET=src/com/hark/neokey/DialectAdapt.java

save() {  # save: backup from a git-CLEAN source only
  git diff --quiet -- "$TARGET" || { echo "REFUSING dirty attack target: $TARGET"; return 2; }
  cp "$TARGET" "$TARGET.proofbak"
}

restore() {  # restore: ALWAYS runs — byte-exact vs HEAD, gate green, or die
  cp "$TARGET.proofbak" "$TARGET" && rm -f "$TARGET.proofbak"
  if git diff --quiet -- "$TARGET" && bash "$GATE" > /tmp/w59-heal.log 2>&1; then
    echo "healed: $TARGET byte-exact (git-clean; DIALECTADAPTGATE_OK on the clean tree)" >> "$LOG"
    echo "healed: $TARGET byte-exact (git-clean)"
    return 0
  fi
  echo "HEAL FAILED after attacking $TARGET — the tree is dirty or the gate is red" >> "$LOG"
  echo "HEAL FAILED after attacking $TARGET"
  return 3
}

attack() {  # attack <name> <sed-expr> <expected-tag-fragment> — ALWAYS restores
  local name="$1" expr="$2" tag="$3"
  save || return 2
  sed -i "$expr" "$TARGET"
  local rc=1
  if bash "$GATE" > /tmp/w59-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE ($name): the gate passed a broken law" >> "$LOG"
    echo "ATTACK DID NOT BITE ($name)"
  elif grep -q "FAIL $tag" /tmp/w59-atk.log; then
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
try() {  # try <name> <sed> <tag> — count real bites, fail loudly otherwise
  echo "───── attack: $1 ─────" >> "$LOG"
  echo "───── attack: $1 ─────"
  attack "$@"
  local rc=$?
  if [ $rc -eq 0 ]; then BITES=$((BITES+1)); else FAILED=$((FAILED+1)); fi
}

[ "$ATK" = "all" ] || [ "$ATK" = "dapl.seed" ] && \
  try dapl.seed 's/if (p == null || p.updates < ESTABLISHED) return null;/if (p == null || p.updates < 1) return null;/' 'seed: غير المستقر'
[ "$ATK" = "all" ] || [ "$ATK" = "dapl.merge" ] && \
  try dapl.merge 's/int dialectQuota = seeded ? cap \/ 2 : cap;/int dialectQuota = cap;/' 'merge: البذرة — نصف الوزن'
[ "$ATK" = "all" ] || [ "$ATK" = "dapl.cap" ] && \
  try dapl.cap 's/while (apps.size() > CAP) {/while (apps.size() > CAP * 10) {/' 'cap: 80 ملفاً'

echo "───── heal ─────" >> "$LOG"
if [ "$FAILED" -ne 0 ] || [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w59 bites=$BITES/3 failures=$FAILED (every attack must land, bite its exact tag, and heal)" >> "$LOG"
  echo "PROOF FAILED w59 bites=$BITES/3 failures=$FAILED"
  exit 1
fi
git diff --quiet -- "$TARGET" || { echo "PROOF FAILED w59: the attack target is dirty"; exit 1; }
if bash "$GATE" > /tmp/w59-final.log 2>&1 && grep -q 'DIALECTADAPTGATE_OK' /tmp/w59-final.log; then
  echo "PROOF PASSED w59 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: DIALECTADAPTGATE_OK on the clean tree — battery 31, acc 100.0%, dex 527888/528000)" >> "$LOG"
  echo "PROOF PASSED w59 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: DIALECTADAPTGATE_OK on the clean tree — battery 31, acc 100.0%, dex 527888/528000)"
  exit 0
fi
echo "PROOF FAILED w59: the clean tree did not heal green" >> "$LOG"
echo "PROOF FAILED w59: the clean tree did not heal green"
exit 1
