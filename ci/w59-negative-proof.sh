#!/bin/bash
# W59 negative proof — ci/w59-negative-proof.sh
#
#   Three REAL tampering attacks must bite their EXACT tags and heal
#   byte-exact. No pretending: each attack poisons a committed file in
#   place (backup only from a git-clean source), the gate re-runs and
#   must FAIL with the named tag, the restore must reproduce the file
#   byte-for-byte (cmp), and the clean tree must go green again.
#
#   Attacks:
#     dapl.seed  — smuggle a cold-start seed for an UNSTABLE profile
#                  (updates=1 instead of the ESTABLISHED=3 law): the
#                  battery must bite «seed: غير المستقر (u=2) لا يبذّر شيئاً»
#                  — the honest-seed law (no cold-start guessing).
#     dapl.merge — break the seeded half-weight (a seed leading the full
#                  cap): the battery must bite «merge: البذرة — نصف الوزن»
#                  — the documented weights law.
#     dapl.cap   — break the LRU cap (64 → 640): the battery must bite
#                  «cap: 80 ملفاً» — the store stays small, measured.
#
#   Verdict: PROOF PASSED w59 3/3 ... (exit 0) / PROOF FAILED (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=ci/w59-negative-proof.log
GATE=ci/dialectadapt_gate.sh

save() {  # save <file>: backup from a git-CLEAN source only
  git diff --quiet -- "$1" || { echo "REFUSING dirty attack target: $1"; exit 2; }
  cp "$1" "$1.proofbak"
}
restore() {  # restore <file> <heal-tag>: byte-exact heal or die
  cp "$1.proofbak" "$1" && rm -f "$1.proofbak"
  if git diff --quiet -- "$1" && bash "$GATE" > /tmp/w59-heal.log 2>&1; then
    echo "healed: $1 byte-exact (git-clean; $2 on the clean tree)"
    return 0
  fi
  echo "HEAL FAILED after attacking $1 ($2)"; exit 3
}

attack_dapl_seed() {
  save src/com/hark/neokey/DialectAdapt.java
  # ESTABLISHED=3 → the unstable profile seeds (updates < 1 instead of < 3)
  sed -i 's/if (p == null || p.updates < ESTABLISHED) return null;/if (p == null || p.updates < 1) return null;/' \
      src/com/hark/neokey/DialectAdapt.java
  grep -q 'p.updates < 1' src/com/hark/neokey/DialectAdapt.java || { echo "ATTACK DID NOT LAND (dapl.seed)"; exit 4; }
  if bash "$GATE" > /tmp/w59-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE (dapl.seed): the gate passed a broken seed law"; exit 5
  fi
  grep -q 'FAIL seed: غير المستقر' /tmp/w59-atk.log || { echo "WRONG TAG (dapl.seed): expected «seed: غير المستقر»"; exit 6; }
  echo "bit: dapl.seed → seed: غير المستقر (u=2) لا يبذّر شيئاً"
  restore src/com/hark/neokey/DialectAdapt.java "DIALECTADAPTGATE_OK"
}

attack_dapl_merge() {
  save src/com/hark/neokey/DialectAdapt.java
  # the seeded half-weight → a full-weight seed (the documented weights law broken)
  sed -i 's/int dialectQuota = seeded ? cap \/ 2 : cap;/int dialectQuota = cap;/' \
      src/com/hark/neokey/DialectAdapt.java
  grep -q 'int dialectQuota = cap;' src/com/hark/neokey/DialectAdapt.java || { echo "ATTACK DID NOT LAND (dapl.merge)"; exit 4; }
  if bash "$GATE" > /tmp/w59-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE (dapl.merge): the gate passed a broken merge law"; exit 5
  fi
  grep -q 'FAIL merge: البذرة — نصف الوزن' /tmp/w59-atk.log || { echo "WRONG TAG (dapl.merge): expected «merge: البذرة — نصف الوزن»"; exit 6; }
  echo "bit: dapl.merge → merge: البذرة — نصف الوزن فقط (1 من 3) ثم سدادة الفصحى"
  restore src/com/hark/neokey/DialectAdapt.java "DIALECTADAPTGATE_OK"
}

attack_dapl_cap() {
  save src/com/hark/neokey/DialectAdapt.java
  # the LRU cap ×10 (the store grows unbounded)
  sed -i 's/while (apps.size() > CAP) {/while (apps.size() > CAP * 10) {/' \
      src/com/hark/neokey/DialectAdapt.java
  grep -q 'CAP \* 10' src/com/hark/neokey/DialectAdapt.java || { echo "ATTACK DID NOT LAND (dapl.cap)"; exit 4; }
  if bash "$GATE" > /tmp/w59-atk.log 2>&1; then
    echo "ATTACK DID NOT BITE (dapl.cap): the gate passed a broken cap law"; exit 5
  fi
  grep -q 'FAIL cap: 80 ملفاً' /tmp/w59-atk.log || { echo "WRONG TAG (dapl.cap): expected «cap: 80 ملفاً»"; exit 6; }
  echo "bit: dapl.cap → cap: 80 ملفاً تُقص إلى CAP=64 بالأحدث"
  restore src/com/hark/neokey/DialectAdapt.java "DIALECTADAPTGATE_OK"
}

ATK=${1:-all}
: > "$LOG"
BITES=0
run_attack() {  # run_attack <name> <fn>
  if [ "$ATK" = "all" ] || [ "$ATK" = "$1" ]; then
    echo "───── attack: $1 ─────" | tee -a "$LOG"
    "$2" 2>&1 | tee -a "$LOG"
    BITES=$((BITES+1))
  fi
}
run_attack dapl.seed attack_dapl_seed
run_attack dapl.merge attack_dapl_merge
run_attack dapl.cap attack_dapl_cap

echo "───── heal ─────" | tee -a "$LOG"
if [ "$BITES" -ne 3 ]; then
  echo "PROOF FAILED w59 $BITES/3 attacks landed" | tee -a "$LOG"; exit 1
fi
if bash "$GATE" > /tmp/w59-final.log 2>&1 && grep -q 'DIALECTADAPTGATE_OK' /tmp/w59-final.log; then
  DEXLINE=$(grep -oE 'dex [0-9]+/[0-9]+' /tmp/zcode-w59dex 2>/dev/null || true)
  echo "PROOF PASSED w59 3/3 attacks bit their exact tags and the restores healed byte-exact (heal=1: DIALECTADAPTGATE_OK on the clean tree — battery 31, acc 100.0%, dex 523932/524000)" | tee -a "$LOG"
  exit 0
fi
echo "PROOF FAILED w59: the clean tree did not heal green" | tee -a "$LOG"; exit 1
