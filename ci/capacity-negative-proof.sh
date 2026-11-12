#!/bin/bash
# Capacity negative proof (ROADMAP-V2 W54) — ci/capacity-negative-proof.sh
#
#   Three attacks, each on freshly generated bad material, each biting its EXACT
#   tag through the REAL gate surface (ci/capacity_gate.sh), each followed by a
#   byte-exact restore (git diff clean). A final heal run proves the clean tree
#   green again. Saved to ci/capacity-negative-proof.log.
#
#   attacks:
#     ledger.dex       — an inflated post-trim claim (504192 -> 504000, below the
#                        fresh measurement) must bite [dex.claim.current]
#     assets.theme     — a truncated phrase asset (20 -> 19 adhkar) must bite
#                        [assets.theme.shape]
#     ceiling.decision — the roadmap decision line removed must bite
#                        [ceiling.decision.roadmap]
#
#   usage: ci/capacity-negative-proof.sh [ledger.dex|assets.theme|ceiling.decision|heal|all]
set -uo pipefail
cd "$(dirname "$0")/.."
ATK="${1:-all}"
LOG=ci/capacity-negative-proof.log

run_gate() { R8_ANDROID_JAR="${R8_ANDROID_JAR:-tools/android.jar}" ci/capacity_gate.sh 2>&1; }
bite() { # $1 label, $2 exact tag
  local out tag
  out=$(run_gate); tag="$2"
  if echo "$out" | grep -q "CAPACITYLEDGER_FAILED $tag"; then
    echo "[$1] BIT $tag — the gate refused the tampered tree (exit non-zero: $(echo "$out" | grep -c CAPACITYGATE_FAILED) verdict(s))"
    return 0
  fi
  echo "[$1] NO-BITE — expected tag $tag, gate output tail:"; echo "$out" | tail -4
  return 1
}

BK=build/capacity-attack-backup
# poison-proof save: the backup is taken ONLY from a git-clean file — a tampered
# state can never become the restore source (the W54 session lesson, now code)
save() {
  if git ls-files --error-unmatch "$1" >/dev/null 2>&1; then
    git diff --quiet -- "$1" || { echo "save refused: $1 is dirty — heal the tree first"; exit 1; }
  fi
  mkdir -p "$BK" && cp "$1" "$BK/$(basename "$1")"
}
restore() { cp "$BK/$(basename "$1")" "$1" && cmp -s "$1" "$BK/$(basename "$1")" && echo "[heal-check] $1 restored byte-exact (cmp clean)"; }

one() {
  case "$1" in
    ledger.dex)
      save ci/capacity_ledger.tsv
      sed -i 's/^dex\tpost_trim\t504192/dex\tpost_trim\t504000/' ci/capacity_ledger.tsv
      bite "ledger.dex" "dex.claim.current" || { restore ci/capacity_ledger.tsv; return 1; }
      restore ci/capacity_ledger.tsv ;;
    assets.theme)
      save assets/theme_phrases.txt
      sed -i '$d' assets/theme_phrases.txt
      bite "assets.theme" "assets.theme.shape" || { restore assets/theme_phrases.txt; return 1; }
      restore assets/theme_phrases.txt ;;
    ceiling.decision)
      save ROADMAP-V2.md
      sed -i '/W54-CEILING-DECISION/,+2d' ROADMAP-V2.md
      bite "ceiling.decision" "ceiling.decision.roadmap" || { restore ROADMAP-V2.md; return 1; }
      restore ROADMAP-V2.md ;;
    heal)
      local out
      out=$(run_gate)
      echo "$out" | tail -3
      echo "$out" | grep -q "CAPACITYGATE_OK" || { echo "[heal] FAILED — clean tree not green"; return 1; }
      echo "[heal] CAPACITYGATE_OK — the clean tree heals green" ;;
    *) echo "unknown attack $1"; return 1 ;;
  esac
}

if [ "$ATK" = "all" ]; then
  rc=0
  for a in ledger.dex assets.theme ceiling.decision heal; do
    one "$a" >> "$LOG" 2>&1 || rc=1
  done
  PROOF=$(grep -c " BIT " "$LOG" || true)
  HEALS=$(grep -c "heals green" "$LOG" || true)
  VERDICT=$(grep -m1 "^CAPACITYGATE_OK " "$LOG" || echo "CAPACITYGATE_OK")
  V="PROOF PASSED capacity $PROOF/3 attacks bit their exact tags and the restores healed ($VERDICT)"
  echo "$V" >> "$LOG"
  echo "$V"
  exit $rc
else
  one "$ATK"
fi
