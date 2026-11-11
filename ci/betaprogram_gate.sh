#!/bin/bash
# Beta-week rehearsal gate (roadmap W52 prep — the local half of the soft-launch
# beta row «برنامج التجربة») — ci/betaprogram_gate.sh
#
#   W52's OWN exit criterion («دفعة مختبرين أولى + صفر أعطال P0 لمدة أسبوع كامل»)
#   stays with the owner — the first real batch needs the store upload first
#   (the documented external pending #1) and then a REAL week. But the MECHANISM
#   of that week is fully rehearsable locally on documented stand-in fixtures,
#   and this gate walks it end to end through the REAL production contracts
#   (Feedback + CrashReport, exactly as shipped):
#
#     1. compile guard      — BetaProgram + Feedback + CrashReport (+ the
#                             identity constants SyncServerPack)
#     2. THE DOCUMENT       — play/beta-program.md is read and checked: the four
#                             seat segments sum to 100, §4's survey is a verbatim
#                             mirror of Feedback.surveyQuestions() (no drift
#                             between the doc and the app — the PlayGate law),
#                             and all five §5 lines are present as literals
#                             (< 1% / 0.47% / صفر حرجة / 4.4 / 3-per-segment)
#     3. THE JOIN LOOP      — the whole Feedback contract: joinSteps carries the
#                             support email + join subject (the loop never dies
#                             before a tester link exists), surveyText numbered
#                             1..5, the mail subject carries the build identity,
#                             the mail body exports the anonymous report verbatim
#                             (or the documented no-report hint)
#     4. THE TESTERS' REPORTS — seven day-reports built through the REAL
#                             CrashReport.build from fixed stand-ins, parsed back:
#                             schema, rate matches sessions/crashes, the privacy
#                             regex (no identifier/path/url), an external path
#                             degrades to unknown, days_ago buckets
#     5. THE WEEK LEDGER    — the exit criterion made mechanical: a P0 day is a
#                             day at/over the documented 1% line; the week is
#                             refused on week.short / privacy.identifier /
#                             p0.day_rate / p0.week_rate / anr.budget /
#                             rating.below / coverage.segment /
#                             classification.incomplete; BETA_WEEK_OK needs
#                             7/7 green days and p0=0
#     6. THE NEGATIVES      — six fresh bad materials each biting its exact tag,
#                             then the green week re-runs byte-identical (heal)
#
#   Verdict: BETAPROGRAM_OK checks=N negatives=M/M seats=100 batch=22 rate=…%
#            vc=1 vn=0.1.0 / BETAPROGRAM_FAILED <reason> (exit 1).
#   The ANR/rating/coverage/classification columns are the OPERATOR's weekly
#   sheet (Play vitals + the day-14 survey batch) — here documented stand-ins;
#   the real first week feeds the same ledger the same way. The upload itself
#   stays the documented external pending #1 — nothing pretended.
#   Standalone by design (the W50/W51-prep pattern): no pipeline stage, no
#   gates.env keys, no closer list moves, no suite-list entry (the file compiles
#   via the runner's glob and is NOT a suite) — every pin stays byte-stable.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "BETAPROGRAM_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/betaprogram-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/BetaProgram.java tests/com/hark/neokey/SyncServerPack.java \
src/com/hark/neokey/Feedback.java src/com/hark/neokey/CrashReport.java"

# ---- 1: compile guard -----------------------------------------------------------
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: the program document is present (the rehearsal reads it itself) ---------
[ -f play/beta-program.md ] || fail "doc.missing (play/beta-program.md)"

# ---- 3: identity from the ONE manifest ------------------------------------------
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ -n "$VC" ] && [ -n "$VN" ] || fail "identity (manifest unreadable)"

# ---- 4: THE REHEARSAL -----------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.BetaProgram "$VN" "$VC") || {
  echo "$V" | grep -E "^(NEG-FAILED|BETAPROGRAM regression|BETA_WEEK_REFUSED|BETAPROGRAM_FAILED)" || echo "$V" | tail -5
  fail "rehearsal"
}
echo "$V" | grep -E "^(BETA_WEEK_OK|NEG-FAILED|BETAPROGRAM regression|BETAPROGRAM_OK)" || true
VERDICT=$(echo "$V" | grep '^BETAPROGRAM_OK' | tail -1)
[ -n "$VERDICT" ] || fail "rehearsal (verdict missing)"
echo "$VERDICT" | grep -q "vc=$VC vn=$VN" || fail "identity (verdict carries another identity, want vc=$VC vn=$VN)"
echo "BETAPROGRAM_GATE=OK"
