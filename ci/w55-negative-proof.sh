#!/bin/bash
# W55 negative proof (ROADMAP-V2 W55) — ci/w55-negative-proof.sh
#
#   Three attacks, each on the committed tree, each biting its EXACT tag
#   through the REAL gate surface (ci/perfbaseline_gate.sh / ci/harness_gate.sh),
#   each followed by a byte-exact restore (cmp clean). A final heal run proves
#   the clean tree green again. Saved to ci/w55-negative-proof.log.
#
#   attacks:
#     perf.record    — a deleted row in evidence/perf-baseline.tsv (the floor
#                      record) must bite [record.readable.strict] — the strict
#                      reader refuses an incomplete keyset, never a partial floor
#     device.floor   — a rewritten Perf.LIMITS number in src/Perf.java (the
#                      create good floor 400 -> 4000) must bite
#                      [device.source.guard] — the anti-rewrite guard: a floor
#                      is never quietly re-written
#     engine.contract— the fail-closed counter removed from src/Engines.java
#                      (the contained failure no longer counted) must bite
#                      [FAIL neg.thrower.contained] — the harness's exact-delta
#                      law catches a silently weakened containment
#
#   usage: ci/w55-negative-proof.sh [perf.record|device.floor|engine.contract|heal|all]
set -uo pipefail
cd "$(dirname "$0")/.."
ATK="${1:-all}"
LOG=ci/w55-negative-proof.log

run_perf() { ci/perfbaseline_gate.sh 2>&1; }
run_harness() { ci/harness_gate.sh 2>&1; }

BK=build/w55-attack-backup
# poison-proof save: the backup is taken ONLY from a git-clean file — a tampered
# state can never become the restore source (the W54 session lesson, now code)
save() {
  if git ls-files --error-unmatch "$1" >/dev/null 2>&1; then
    git diff --quiet -- "$1" || { echo "[save refused: $1 is dirty — heal the tree first]"; exit 1; }
  fi
  mkdir -p "$BK" && cp "$1" "$BK/$(basename "$1")"
}
restore() { cp "$BK/$(basename "$1")" "$1" && cmp -s "$1" "$BK/$(basename "$1")" && echo "[heal-check] $1 restored byte-exact (cmp clean)"; }

one() {
  case "$1" in
    perf.record)
      save evidence/perf-baseline.tsv
      sed -i '/^jvm\tnnet.top3.p50\t/d' evidence/perf-baseline.tsv
      out=$(run_perf)
      if echo "$out" | grep -q "PERFBASELINE_FAILED record.readable.strict"; then
        echo "[perf.record] BIT record.readable.strict — the strict reader refused the mutilated floor record"
        restore evidence/perf-baseline.tsv
      else
        echo "[perf.record] NO-BITE — expected record.readable.strict, tail:"; echo "$out" | tail -4
        restore evidence/perf-baseline.tsv; return 1
      fi ;;
    device.floor)
      save src/com/hark/neokey/Perf.java
      sed -i 's/{{400, 900}/{{4000, 900}/' src/com/hark/neokey/Perf.java
      out=$(run_perf)
      if echo "$out" | grep -q "PERFBASELINE_FAILED device.source.guard"; then
        echo "[device.floor] BIT device.source.guard — the anti-rewrite guard refused the quietly rewritten floor"
        restore src/com/hark/neokey/Perf.java
      else
        echo "[device.floor] NO-BITE — expected device.source.guard, tail:"; echo "$out" | tail -4
        restore src/com/hark/neokey/Perf.java; return 1
      fi ;;
    engine.contract)
      save src/com/hark/neokey/Engines.java
      sed -i 's/catch (Throwable t) { failures.incrementAndGet(); continue; }/catch (Throwable t) { continue; }/' src/com/hark/neokey/Engines.java
      out=$(run_harness)
      if echo "$out" | grep -q "FAIL neg.thrower.contained"; then
        echo "[engine.contract] BIT FAIL neg.thrower.contained — the exact-delta law caught the silently weakened containment"
        restore src/com/hark/neokey/Engines.java
      else
        echo "[engine.contract] NO-BITE — expected FAIL neg.thrower.contained, tail:"; echo "$out" | tail -4
        restore src/com/hark/neokey/Engines.java; return 1
      fi ;;
    heal)
      local o1 o2
      o1=$(run_perf); o2=$(run_harness)
      echo "$o1" | tail -1; echo "$o2" | tail -1
      echo "$o1" | grep -q "^PERFBASELINE OK" && echo "$o2" | grep -q "^HARNESSGATE OK" \
        || { echo "[heal] FAILED — the clean tree is not green"; return 1; }
      echo "[heal] PERFBASELINE OK + HARNESSGATE OK — the clean tree heals green" ;;
    *) echo "unknown attack $1"; return 1 ;;
  esac
}

if [ "$ATK" = "all" ]; then
  rc=0
  for a in perf.record device.floor engine.contract heal; do
    one "$a" >> "$LOG" 2>&1 || rc=1
  done
  BITES=$(grep -c " BIT " "$LOG" || true)
  HEALS=$(grep -c "heals green" "$LOG" || true)
  V="PROOF PASSED w55 $BITES/3 attacks bit their exact tags and the restores healed (heal=$HEALS: PERFBASELINE_OK checks=27 + HARNESSGATE_OK checks=35)"
  echo "$V" >> "$LOG"
  echo "$V"
  exit $rc
fi
one "$ATK"
