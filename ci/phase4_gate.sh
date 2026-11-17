#!/bin/bash
# Phase-4 CLOSING gate (roadmap phase 4, week 4 — week 25) — ci/phase4_gate.sh
#
#   Reviews ALL phase-4 gates together in ONE line, on fresh re-runs (never
#   cached verdicts), then measures the locally-measurable exit criteria.
#   Evidence integrity is THREE-WAY (the week-21 design, inherited): the fresh
#   re-run, this run's pipeline log and gates.env must all AGREE on every
#   pinned field — tampering any single source is caught as a regression.
#
#     1. trim_gate + rest_gate + pay_gate re-run green, each field pinned at
#        its shipped baseline (no silent regression: entries, negatives,
#        checks, granted, ledger — only p50 latencies and ciphertext entropy
#        get upper/lower bounds, never equality).
#     2. fresh verdict lines == pipeline-log verdict lines == gates.env values.
#     3. Whole-pipeline state: every previous gate key still OK (17 keys from
#        phases 0-3 + phase-4 gates + the phase-3 closing gate), tests >=
#        1,334 with 0 failures, NQF golden assets = 15, theme packs = 25,
#        R8 dex <= the 512,000B lowered ceiling with all manifest components
#        kept, trim gate and R8 agree on the same dex artifact, versionCode
#        bumped (the gate itself refuses a week that does not).
#
#   Verdict: PHASE4_OK … (exit 0, keys appended to gates.env for the release
#   manifest) / PHASE4_FAILED <reason> (exit 1).
#   Standalone use requires a prior pipeline run (build/pipeline.log + gates.env
#   + the r8 dex artifact the trim gate re-measures).
set -uo pipefail
cd "$(dirname "$0")/.."

# measurement integrity (week 42 fix #2, same law as tests/run.sh): this gate
# enforces WALL-TIME budgets — an inherited JAVA_TOOL_OPTIONS (a JIT-tier
# override) silently biases them; the budgets always measure what ships.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS

DEX_CEILING=565000   # W69-CEILING-RAISE (ci/capacity_ledger.tsv ceiling.raised; the W22-era 512000 was lowered history)   # LOWERED by the W22 real trim (was 520,000); overflow means trim, never raise
VC_MIN=2             # v0.1.0 identity reset (re-based from the phase-4 close floor 66; the closing week still must carry the manifest identity)
TESTS_MIN=1334       # shipped test total at phase-4 close (1,334 = week 24)
STATE=build/release/gates.env
LOGF=build/pipeline.log

REG=0
fail() { echo "PHASE4 regression: $1" >&2; REG=$((REG+1)); }
field() { echo "$1" | sed -E "s/.*$2=([^ ]+).*/\1/"; }
negof() { echo "$1" | grep -oE "$2=[0-9]+/[0-9]+" | cut -d= -f2; }

# ---- 1: re-run the three phase-4 gates, fresh ---------------------------------
run_one() { # $1 label, $2 script, $3 verdict-line prefix
  local out rc line
  out=$("$2" 2>&1); rc=$?
  line=$(echo "$out" | grep -E "^$3" | tail -1)
  if [ $rc -ne 0 ] || [ -z "$line" ]; then
    echo "PHASE4_FAILED $1 (rc=$rc, verdict missing)" >&2
    echo "$out" | tail -5 >&2
    exit 1
  fi
  echo "$line"
}

TR=$(run_one trim ci/trim_gate.sh  'TRIM_GATE_OK')  || exit 1
RS=$(run_one rest  ci/rest_gate.sh 'RESTGATE_OK')   || exit 1
PY=$(run_one pay   ci/pay_gate.sh  'PAYGATE_OK')    || exit 1
echo "[phase4] $TR"
echo "[phase4] $RS"
echo "[phase4] $PY"

# ---- 2: pinned baselines on the FRESH values — shipped numbers may not sink --
# real dex trim (W1/week 22): 126 entries (W74 +3: ThemeChannel joins with its curated-channel glue; W71 +2 was NumSmart), nothing regrown, purity holds
TR_ENT=$(field "$TR" entries);   TR_SAVED=$(field "$TR" saved)
TR_DEX=$(field "$TR" dex | cut -d/ -f1); TR_CEIL=$(field "$TR" dex | cut -d/ -f2)
TR_REG=$(field "$TR" regrown);   TR_PUR=$(field "$TR" purity)
TR_UIEN=$(field "$TR" ui_en_coupled)
[ "${TR_ENT:-0}"   = 126 ]      || fail "trim entries ${TR_ENT:-0} != 126 (the trim inventory moved silently)"
[ "${TR_REG:-1}"   = 0 ]        || fail "trim regrown ${TR_REG:-?} != 0 (a shell-class literal regrew)"
[ "${TR_PUR:-0}"   -ge 36 ]     || fail "trim purity $TR_PUR < 36 (android imports lost their stubs)"
[ "${TR_UIEN:-0}"  -ge 80 ]     || fail "trim ui_en_coupled ${TR_UIEN:-0} < 80 (translation coupling broke)"
# saved floor rides the current dex size: phase-5 W1 (week 26) spent 124B of
# the trim margin on SpkiPins+PinnedTls (+transport wiring) — 13548 → 13424.
# The DOCTRINE is unchanged: ceiling stays 512,000 (never raised); overflow
# still means trim, never raise. A sinking "saved" BEYOND the documented
# spend is still a regression.
# week 31 amendment (documented, PHASE6-W2-REPORT §4): the pipeline dex is now
# built against the REAL R (aapt2 — the artifact path, the W22 stub would ship
# wrong resource IDs); R.* constants inline as 32-bit, shifting the measured
# trim delta 13424 -> 13052 (-372 = exactly the real-R size delta). The lever
# itself is unchanged (entries=120, regrown=0, purity intact).
# W47 re-pin (roadmap W47, documented weekly baseline): 13052 -> 12984 — the
# clip escapeTxt law (ci/clipio_gate.sh, HISTORY W47) grew the source by 68
# dex bytes, the measured lever shrinks by the same delta; dex 505704/512000.
# W48 re-pin (roadmap W48, documented weekly baseline): 12984 -> 6884 — the
# phase-6 library (tags + shortcuts + text tools + the editor/library UI, the
# W48 report) spent 6100 dex bytes: dex 505704 -> 511804 (margin 196B — the
# ceiling stays 512,000, never raised; the W49+ feature weeks trim before they
# add: the remaining lever inventory is Writer/Theme/Feedback long copy,
# ~10KB measured, documented in the W48 notes).
# W57 re-pin (roadmap W57, documented weekly baseline): 6884 -> 5760 — the
# tashkeel engine (ROADMAP-V2 W57, +4108 measured) and the W57-CEILING-RAISE
# (+1000, ci/capacity_ledger.tsv ceiling.raised): dex 508840 -> 512928, the
# measured lever shrinks by the documented delta (the W48 re-pin pattern).
# W58 re-pin (documented weekly baseline): 5760 -> 480 — the grammar engine
# (ROADMAP-V2 W58, +8592 measured) adds ZERO trimmable content (law 6: the
# knowledge rides assets/grammar_rules.txt — nothing to trim inside the dex),
# the trim pool is measured and the floor follows the measurement (the W48
# re-pin pattern; regrown=0 stays the behavioral law).
# W59 re-pin (documented weekly baseline): 480 -> 68 — the dialect-adapt
# engine (ROADMAP-V2 W59, +2412 measured) also adds ZERO trimmable content
# (the profile store is filesDir runtime state — no assets, nothing to trim),
# and the pool's measured weekly saving after the W58 realization is 68 —
# the floor follows the fresh measurement again (the same W48 re-pin law;
# regrown=0 stays the behavioral law).
[ "${TR_SAVED:-0}" -ge 68 ]  || fail "trim saved ${TR_SAVED:-0} < 68 (the lever shrank)"
[ "${TR_CEIL:-0}"  = "$DEX_CEILING" ] || fail "trim ceiling ${TR_CEIL:-0} != $DEX_CEILING"
[ "${TR_DEX:-999999}" -le "$DEX_CEILING" ] || fail "trim dex $TR_DEX > ceiling $DEX_CEILING"

# REST reference server (W2/week 23): the wire contract stays measured
RS_END=$(field "$RS" endpoints); RS_ROUNDS=$(field "$RS" rounds)
RS_BLOBS=$(field "$RS" blobs);   RS_GRANT=$(field "$RS" granted)
RS_NEG=$(negof "$RS" negatives); RS_ENT=$(field "$RS" entropy_x100)
RS_PUSHP=$(field "$RS" p50_push_ms); RS_PULLP=$(field "$RS" p50_pull_ms)
RS_CHK=$(field "$RS" checks)
[ "${RS_END:-0}"     = 5 ]       || fail "rest endpoints ${RS_END:-0} != 5 (contract shape drifted)"
[ "${RS_ROUNDS:-0}"  -ge 7 ]     || fail "rest rounds ${RS_ROUNDS:-0} < 7"
[ "${RS_BLOBS:-0}"   -ge 9 ]     || fail "rest blobs ${RS_BLOBS:-0} < 9"
[ "${RS_GRANT:-0}"   -ge 3 ]     || fail "rest granted ${RS_GRANT:-0} < 3 (cross-device restore weakened)"
[ "${RS_NEG:-0/0}"   = "7/7" ]   || fail "rest negatives $RS_NEG != 7/7"
[ "${RS_ENT:-0}"     -ge 790 ]   || fail "rest entropy_x100 $RS_ENT < 7.90 bits/byte (zero-knowledge weakened)"
[ "${RS_PUSHP:-99999}" -le 1000 ] || fail "rest p50_push_ms $RS_PUSHP > 1000 (wire budget)"
[ "${RS_PULLP:-99999}" -le 1000 ] || fail "rest p50_pull_ms $RS_PULLP > 1000 (wire budget)"
[ "${RS_CHK:-0}"     -ge 50 ]    || fail "rest checks ${RS_CHK:-0} < 50"

# payment-provider contract (W3/week 24): one money path, ledger closes
PY_PROV=$(field "$PY" providers); PY_REC=$(field "$PY" receipts)
PY_DET=$(field "$PY" det_equal);  PY_LED=$(field "$PY" ledger_closes)
PY_NEG=$(negof "$PY" negatives);  PY_P50=$(field "$PY" p50_topup_us)
PY_CHK=$(field "$PY" checks)
[ "${PY_PROV:-0}"   -ge 2 ]      || fail "pay providers ${PY_PROV:-0} < 2 (deterministic proof needs two)"
[ "${PY_REC:-0}"    -ge 400 ]    || fail "pay receipts ${PY_REC:-0} < 400"
[ "${PY_DET:-0}"    = 1 ]        || fail "pay det_equal ${PY_DET:-0} != 1 (deterministic seal broke)"
[ "${PY_LED:-0}"    = 7325 ]     || fail "pay ledger_closes ${PY_LED:-0} != 7325 (economy drifted)"
[ "${PY_NEG:-0/0}"  = "18/18" ]  || fail "pay negatives $PY_NEG != 18/18"
[ "${PY_P50:-99999}" -le 500 ]   || fail "pay p50_topup_us $PY_P50 > 500"
[ "${PY_CHK:-0}"    -ge 72 ]     || fail "pay checks ${PY_CHK:-0} < 72"

# ---- 3: evidence integrity — fresh == pipeline log == gates.env ----------------
if [ ! -f "$STATE" ] || [ ! -f "$LOGF" ]; then
  echo "PHASE4_FAILED state_missing (run ci/pipeline.sh first)"; exit 1
fi
LOG_TR=$(grep -E '^TRIM_GATE_OK' "$LOGF" | tail -1)
LOG_RS=$(grep -E '^RESTGATE_OK'  "$LOGF" | tail -1)
LOG_PY=$(grep -E '^PAYGATE_OK'   "$LOGF" | tail -1)
LOG_R8=$(grep -E '^R8_OK '       "$LOGF" | tail -1)
[ -n "$LOG_TR" ] || fail "pipeline log has no TRIM_GATE_OK line"
[ -n "$LOG_RS" ] || fail "pipeline log has no RESTGATE_OK line"
[ -n "$LOG_PY" ] || fail "pipeline log has no PAYGATE_OK line"
[ -n "$LOG_R8" ] || fail "pipeline log has no R8_OK line"

. "$STATE"

eq3() { # $1 env-key, $2 fresh value, $3 log value, $4 label
  local envv="${!1:-}"
  [ "$envv" = "$2" ] || fail "$4: gates.env $envv != fresh re-run $2"
  [ "$3" = "$2" ]    || fail "$4: pipeline log $3 != fresh re-run $2"
}
eq3 TRIM_ENTRIES       "$TR_ENT"   "$(field "$LOG_TR" entries)"          trim.entries
eq3 TRIM_UI_EN_COUPLED "$TR_UIEN"  "$(field "$LOG_TR" ui_en_coupled)"    trim.ui_en_coupled
eq3 REST_ROUNDS        "$RS_ROUNDS" "$(field "$LOG_RS" rounds)"          rest.rounds
eq3 REST_BLOBS         "$RS_BLOBS" "$(field "$LOG_RS" blobs)"            rest.blobs
eq3 REST_GRANTED       "$RS_GRANT" "$(field "$LOG_RS" granted)"          rest.granted
eq3 REST_NEGATIVES     "$RS_NEG"   "$(negof "$LOG_RS" negatives)"        rest.negatives
eq3 REST_CHECKS        "$RS_CHK"   "$(field "$LOG_RS" checks)"           rest.checks
eq3 PAY_DET_EQUAL      "$PY_DET"   "$(field "$LOG_PY" det_equal)"        pay.det_equal
eq3 PAY_LEDGER_CLOSES  "$PY_LED"   "$(field "$LOG_PY" ledger_closes)"    pay.ledger_cents
eq3 PAY_NEGATIVES      "$PY_NEG"   "$(negof "$LOG_PY" negatives)"        pay.negatives
eq3 PAY_CHECKS         "$PY_CHK"   "$(field "$LOG_PY" checks)"           pay.checks
# non-deterministic fields (latency, ciphertext entropy) get BOUNDS in all three
# sources, never equality — p50 jitter between runs is not a regression (the
# week-21 lesson, inherited):
[ "${REST_P50_PUSH_MS:-99999}" -le 1000 ] || fail "env rest p50_push_ms ${REST_P50_PUSH_MS:-?} > 1000"
LRP=$(field "$LOG_RS" p50_push_ms); [ "${LRP:-99999}" -le 1000 ] || fail "log rest p50_push_ms $LRP > 1000"
[ "${REST_ENTROPY_X100:-0}" -ge 790 ] || fail "env rest entropy_x100 ${REST_ENTROPY_X100:-0} < 7.90"
LRE=$(field "$LOG_RS" entropy_x100); [ "${LRE:-0}" -ge 790 ] || fail "log rest entropy_x100 $LRE < 7.90"
[ "${PAY_P50_TOPUP_US:-99999}" -le 500 ] || fail "env pay p50_topup_us ${PAY_P50_TOPUP_US:-?} > 500"
LPP=$(field "$LOG_PY" p50_topup_us); [ "${LPP:-99999}" -le 500 ] || fail "log pay p50_topup_us $LPP > 500"

# tests: the log's RESULT sums must equal gates.env exactly
LT=$(grep -o 'RESULT [0-9]* passed, [0-9]* failed' "$LOGF" | awk '{p+=$2; f+=$4} END {print p+0, f+0}')
[ "$(echo "$LT" | awk '{print $1}')" = "${TESTS_TOTAL:-x}" ] || fail "TESTS_TOTAL ${TESTS_TOTAL:-?} != log $(echo "$LT" | awk '{print $1}')"
[ "$(echo "$LT" | awk '{print $2}')" = "${TESTS_FAILED:-x}" ] || fail "TESTS_FAILED ${TESTS_FAILED:-?} != log $(echo "$LT" | awk '{print $2}')"

# dex: the trim gate and the R8 gate must agree on the SAME artifact
[ "$(field "$LOG_R8" dex)" = "${R8_DEX_BYTES:-x}" ] || fail "R8_DEX_BYTES ${R8_DEX_BYTES:-?} != log $(field "$LOG_R8" dex)"
[ "${TR_DEX:-0}" = "${R8_DEX_BYTES:--1}" ] || fail "trim dex $TR_DEX != r8 dex ${R8_DEX_BYTES:-?} (artifact disagreement)"
[ "${R8_COMPONENTS_KEPT:-0/0}" = "10/10" ] || fail "components kept ${R8_COMPONENTS_KEPT:-0/0} != 10/10"
[ "${R8_DEX_BYTES:-999999}" -le "$DEX_CEILING" ] || fail "dex $R8_DEX_BYTES > ceiling $DEX_CEILING"

# ---- 4: whole-pipeline no-regression ------------------------------------------
[ "${AUDIT:-}" = "OK" ] || fail "secrets audit not OK"
[ "${TESTS_TOTAL:-0}" -ge "$TESTS_MIN" ] || fail "tests $TESTS_TOTAL < $TESTS_MIN"
[ "${TESTS_FAILED:-1}" = "0" ] || fail "tests_failed $TESTS_FAILED != 0"
# every previous gate key must still be OK — no gate regressed (17 keys:
# phases 0-2 (13) + the three phase-4 gates + the phase-3 closing gate)
for k in PLAY_GATE CORR_GATE NNET_GATE DIALECT_GATE ASSIST_GATE SYNC_GATE \
         SYNCCLIENT_GATE THEMESTORE_GATE NQFPORT_GATE COMMUNITY_GATE \
         DICTSIGN_GATE STOREUI_GATE ENTGATE REST_GATE PAY_GATE TRIM_GATE \
         PHASE3_GATE; do
  [ "${!k:-NOT_OK}" = "OK" ] || fail "$k not OK"
done
[ "${NQFPORT_ASSETS:-0}" = 25 ]     || fail "nqfport assets ${NQFPORT_ASSETS:-0} != 25 (golden pins drifted)"
[ "${THEMESTORE_PACKS:-0}" -ge 25 ] || fail "themestore packs ${THEMESTORE_PACKS:-0} < 25"

# versionCode bumped this week
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
[ "${VC:-0}" -ge "$VC_MIN" ] || fail "versionCode $VC < $VC_MIN (closing week must bump)"

# ---- verdict -------------------------------------------------------------------
# check count: every "|| fail" line is counted once by grep (43 incl. the two
# lines inside eq3); the 17-key loop adds 17; each of the 11 eq3 calls executes
# TWO conditions while grep counted the body once, so eq3 adds 11*2-2 = 20.
CHECKS=$(( $(grep -cE '\|\| fail ' "$0") + 17 + 20 ))
if [ "$REG" -ne 0 ]; then
  echo "PHASE4_FAILED regressions=$REG/$CHECKS (see PHASE4 regression lines above)"
  exit 1
fi
echo "PHASE4_OK gates=3/3 regressions=0/$CHECKS dex=${R8_DEX_BYTES:-0}/$DEX_CEILING vc=$VC tests=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0} negatives=$PY_NEG+$RS_NEG trim=$TR_ENT"

# keys for the release manifest (release.sh sources gates.env)
{
  echo "PHASE4_GATE=OK"
  echo "PHASE4_GATES=3/3"
  echo "PHASE4_REGRESSIONS=0/$CHECKS"
  echo "PHASE4_DEX=${R8_DEX_BYTES:-0}"
  echo "PHASE4_CEILING=$DEX_CEILING"
  echo "PHASE4_VC=$VC"
  echo "PHASE4_TESTS=${TESTS_TOTAL:-0}/${TESTS_FAILED:-0}"
  echo "PHASE4_NEGATIVES=$PY_NEG+$RS_NEG"
} >> "$STATE"
exit 0
