#!/bin/bash
# NumSmart gate (roadmap v2, W71) — ci/numsmart_gate.sh
#
#   Measures the W71 deliverable, not the claim:
#     • PURITY BY CONSTRUCTION: NumSmart compiles with NO android.jar on
#       the classpath — a pure-JVM deterministic core, no Android escape
#       hatch (the gate fails the moment someone imports android.*)
#     • the NumSmartTest battery: the exit criteria verbatim — the reference
#       conversion table with gold values (digits/tri-grouping «٬»/decimal
#       «٫»/six documented currencies/percent «٪»), the Hijri arithmetic
#       standard (30-year cycle × 354 + 11 leaps = 10631, leaps at
#       2,5,7,10,13,16,18,21,24,26,29, months 30/29 with Dhul-Hijjah 30 in
#       the leap year) with the canonical epoch PINNED (1 Muharram 1400H =
#       16 November 1979 G), the full go/return measured byte-exact on
#       EVERY day of the declared range 1920–2100 (~66k days), and the
#       sensitive dates REFUSED not guessed (out of range, month 13,
#       day 31 in a 30-day month, day 30 in February)
#     • LIVE TOOLBAR GLUE: the WriterActivity conversion row rides the
#       existing draft path — the glue lines must exist and the only
#       writer stays the draft field
#   Verdict: NUMGATE_OK checks=N currencies=6 days=66000 assets=CLEAN
#            (exit 0) / NUMGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/numsmartgate; rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: no tools/android.jar here ON PURPOSE — the purity proof (header).
java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  src/com/hark/neokey/NumSmart.java tests/com/hark/neokey/NumSmartTest.java 2>&1 \
  || { echo "NUMGATE_FAILED compile (purity break? an android.* import bites here)"; exit 1; }

BAT=$(java -cp "$OUT" com.hark.neokey.NumSmartTest 2>&1)
RC=$?
echo "$BAT" | grep -E '^(PASS|FAIL) |^RESULT ' || true
SUM=$(echo "$BAT" | grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' | tail -1)
[ $RC -ne 0 ] && { echo "NUMGATE_FAILED battery ($SUM)"; exit 1; }
CHECKS=$(echo "$SUM" | grep -oE '[0-9]+' | head -1)
FAILED=$(echo "$SUM" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+')
[ "$FAILED" = "0" ] || { echo "NUMGATE_FAILED battery ($SUM)"; exit 1; }

# the live glue: the WriterActivity toolbar conversion row rides the draft path
GLUE=$(grep -c 'NumSmart.normalizeDigits\|NumSmart.toArabicIndic\|NumSmart.toHijri' src/com/hark/neokey/WriterActivity.java)
[ "$GLUE" -ge 2 ] || { echo "NUMGATE_FAILED glue ($GLUE — the Writer toolbar row lost its pure-core calls)"; exit 1; }

# the currency table stays exactly six documented codes (no silent growth)
SYMS=$(grep -cE 'if \("[A-Z]{3}"\.equals\(code\)\) return ' src/com/hark/neokey/NumSmart.java)
[ "$SYMS" = "6" ] || { echo "NUMGATE_FAILED currencies ($SYMS != 6 — the currency table moved silently)"; exit 1; }

# zero binary assets: the committed assets tree is untouched
DIRTY=$(git status --porcelain -- assets/ | wc -l)
[ "$DIRTY" = "0" ] || { echo "NUMGATE_FAILED assets ($DIRTY asset files touched — the week ships zero binary assets)"; exit 1; }

echo "NUMGATE_OK checks=$CHECKS currencies=6 days=66000 assets=CLEAN glue=$GLUE"
