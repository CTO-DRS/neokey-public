#!/bin/bash
# The REBUILD DRILL — phase 8 W1 (week 38): «الإثبات من لا شيء».
#
# The capability the phase-7 close PRESUMED and never proved as a gate: a
# virgin environment can be stood up anywhere and re-run the whole proof
# green. The live lesson is documented in docs/PHASE8-PLAN.md: this week's
# environment recycled to zero (tree + toolchain + credentials gone) and the
# recovery was manual, uncoded, unverified. This drill turns that recovery
# into a measured, gate-guarded capability:
#
#   ci/rebuild.sh            — the drill (called by ci/rebuild_gate.sh):
#     1. a CLEAN LOCAL CLONE of HEAD (git clone — no build/, no tool jars:
#        exactly what a fresh checkout carries; build/ and tools/*.jar are
#        gitignored, so the clone cannot inherit them),
#     2. the SEVEN pinned toolchain artifacts sha256-verified against the
#        pins manifest (المرجع = المنشور) and copied into the clone's tools/
#        (what a provisioned runner has),
#     3. the FULL INNER PIPELINE on the clone (NEOKEY_REBUILD_INNER=1 — the
#        drill stage omits itself inside the inner run: 37 stages, no
#        recursion), required 37/37 PASS with ZERO SKIP rows,
#     4. a single-line verdict carrying the inner run's measured fields.
#
#   ci/rebuild.sh provision — one-time toolchain fetch into tools/ from the
#        documented URLs (docs/R8.md), primary then mirror; the GATE never
#        touches the network — it only verifies pins.
#
# The inner run's log and the verdict land at build/rebuild/ for the gate's
# Java checks (RebuildGate.java) and the saved negative proof.
set -uo pipefail
cd "$(dirname "$0")/.."

PROG=REBUILD_DRILL
PINS_FILE=build/rebuild-pins.txt
OUTDIR=build/rebuild

fail() { echo "${PROG}_FAILED $1"; exit 1; }

# ---- provision mode: the one-time network fetch (documented, not gated) ------
if [ "${1:-}" = "provision" ]; then
  TOOLS=tools; mkdir -p "$TOOLS"
  fetch() { # $1 dest, $2 primary URL, $3 mirror URL
    local d="tools/$1"
    if curl -fsSL --retry 3 --retry-all-errors -o "$d" "$2" 2>/dev/null; then echo "provisioned $1 (primary)"; return 0; fi
    if [ -n "$3" ] && curl -fsSL --retry 3 --retry-all-errors -o "$d" "$3" 2>/dev/null; then echo "provisioned $1 (mirror)"; return 0; fi
    echo "PROVISION_FAILED $1"; return 1
  }
  fetch r8.jar          https://dl.google.com/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar "" || exit 1
  fetch apksig.jar      https://dl.google.com/android/maven2/com/android/tools/build/apksig/8.5.0/apksig-8.5.0.jar "" || exit 1
  fetch sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar https://maven.aliyun.com/repository/central/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar || exit 1
  fetch slf4j-api.jar   https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar https://maven.aliyun.com/repository/central/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar || exit 1
  fetch ecj.jar         https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar https://maven.aliyun.com/repository/central/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar || exit 1
  fetch aapt2-pkg.jar   https://dl.google.com/android/maven2/com/android/tools/build/aapt2/8.5.0-11315950/aapt2-8.5.0-11315950-linux.jar "" || exit 1
  [ -f tools/aapt2 ] || { echo "aapt2-pkg.jar fetched — extract and chmod +x as tools/aapt2 (docs/R8.md)"; }
  if [ ! -f tools/android.jar ]; then
    curl -fsSL --retry 3 --retry-all-errors -o /tmp/platform-30.zip https://dl.google.com/android/repository/platform-30_r03.zip \
      && unzip -o -q /tmp/platform-30.zip "android-11/*" -d /tmp/p30x \
      && cp /tmp/p30x/android-11/android.jar tools/android.jar && echo "provisioned android.jar"
  fi
  echo "PROVISION_DONE — verify with: ci/rebuild_gate.sh (pins re-computed there)"
  exit 0
fi

# ---- the drill ----------------------------------------------------------------
[ -f "$PINS_FILE" ] || fail "pins manifest missing ($PINS_FILE) — the gate writes it from its printed pins"
mkdir -p "$OUTDIR"
# stale drill dirs from previous runs die here (no accumulation)
rm -rf build/rebuild-drill.* 2>/dev/null || :
RB=$(mktemp -d build/rebuild-drill.XXXXXX) || fail "mktemp"
trap 'rm -rf "$RB" 2>/dev/null' EXIT

# 1: the clean clone — HEAD only, no build/, no tool jars (all gitignored)
git clone -q . "$RB/repo" 2>/dev/null || fail "clone"
HEAD_LOCAL=$(git rev-parse HEAD)
HEAD_CLONE=$(git -C "$RB/repo" rev-parse HEAD)
[ "$HEAD_LOCAL" = "$HEAD_CLONE" ] || fail "clone.head ($HEAD_CLONE != $HEAD_LOCAL)"
[ -d "$RB/repo/build" ] && fail "clone.carries_build (a fresh checkout must not)"
[ -f "$RB/repo/tools/r8.jar" ] && fail "clone.carries_jars (a fresh checkout must not)"

# 2: verify the seven pins, then copy the verified tools into the clone
while read -r name sha; do
  [ -n "$name" ] || continue
  [ -f "tools/$name" ] || fail "tool.$name missing (provision first: ci/rebuild.sh provision)"
  got=$(sha256sum "tools/$name" | cut -d' ' -f1)
  [ "$got" = "$sha" ] || fail "tool.$name sha $got != pin $sha"
  cp "tools/$name" "$RB/repo/tools/$name"
done < "$PINS_FILE"

# 3: the FULL INNER PIPELINE on the clean clone — 37 stages, no recursion
# a FAILED inner run leaves its own evidence file (the next run must not
# overwrite it — the W38 proof lesson: erased evidence is no evidence)
( cd "$RB/repo" && NEOKEY_REBUILD_INNER=1 ci/pipeline.sh ) > "$OUTDIR/inner-run.last.out" 2>&1 \
  || { cp "$OUTDIR/inner-run.last.out" "$OUTDIR/inner-run.last-failed.out"; fail "inner_pipeline (see $OUTDIR/inner-run.last-failed.out)"; }
INNER_LOG="$RB/repo/build/pipeline.log"
[ -f "$INNER_LOG" ] || fail "inner_log missing"
grep -q '^PIPELINE_OK$' "$INNER_LOG" || fail "inner_pipeline_not_green"
INNER_SUMMARY=$(grep -cE '^  [a-z0-9]+ +(PASS|FAIL|SKIP)' "$INNER_LOG")
INNER_FAILS=$(grep -cE '^  [a-z0-9]+ +FAIL' "$INNER_LOG")
[ "$INNER_SUMMARY" = "37" ] || fail "inner_stage_count ($INNER_SUMMARY != 37)"
[ "$INNER_FAILS" = "0" ]    || fail "inner_stage_fails ($INNER_FAILS)"
# the inner run must reproduce THIS environment's exact proof shape: the
# per-stage verdict lines ([name] PASS/FAIL/SKIP, the PASS timing stripped —
# jitter is not a profile) equal the outer run's lines minus the rebuild row
# itself (written only after this gate exits) — the documented build SKIP is
# part of this environment's honest profile and must reproduce, no more, no less
prof() { grep -E '^\[[a-z0-9]+\] (PASS|FAIL|SKIP)' "$1" | sed -E 's/ \([0-9]+s\)$//' | grep -vE '^\[rebuild\] ' | sort; }
prof build/pipeline.log > "$OUTDIR/outer.rows"
prof "$INNER_LOG"          > "$OUTDIR/inner.rows"
cmp -s "$OUTDIR/outer.rows" "$OUTDIR/inner.rows" || fail "inner_profile_drift (the inner run's stage profile != the outer run's — see $OUTDIR/{outer,inner}.rows)"

# the authoritative total is the pipeline's own summary line (the RESULT
# lines are per-suite; the summary line is their sum)
SUM_LINE=$(grep -oE 'tests: [0-9]+ passed, [0-9]+ failed' "$INNER_LOG" | tail -1)
[ -n "$SUM_LINE" ] || fail "inner_tests_line missing"
TP=$(echo "$SUM_LINE" | sed -E 's/tests: ([0-9]+) passed.*/\1/')
TF=$(echo "$SUM_LINE" | sed -E 's/.* ([0-9]+) failed/\1/')
RT=$(grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' "$INNER_LOG" | awk '{p+=$2; f+=$4} END {print p+0, f+0}')
[ "$RT" = "$TP $TF" ] || fail "inner_result_sum ($RT != $TP $TF) — the suite RESULTs must sum to the summary"
R8_LINE=$(grep -oE 'R8_OK dex=[0-9]+ bytes' "$INNER_LOG" | tail -1)
[ -n "$R8_LINE" ] || fail "inner_r8_line missing"
DEX=$(echo "$R8_LINE" | sed -E 's/R8_OK dex=([0-9]+) bytes/\1/')
IDENT=$(grep -oE 'BILLINGGATE_OK.*vc=[0-9]+ vn=[0-9.]+' "$INNER_LOG" | tail -1)
IVC=$(echo "$IDENT" | grep -oE 'vc=[0-9]+' | cut -d= -f2)
IVN=$(echo "$IDENT" | grep -oE 'vn=[0-9.]+' | cut -d= -f2)
[ -n "$IVC" ] && [ -n "$IVN" ] || fail "inner_identity missing (billing verdict line absent)"

# 4: the single-line verdict — the gate cross-checks every field against the
# inner log itself (a forged verdict or a forged log is caught by RebuildGate)
VERDICT="REBUILD_DRILL_OK pins=$(grep -cE '^[a-z0-9.-]+ [0-9a-f]{64}$' "$PINS_FILE") stages=$INNER_SUMMARY tests=$TP/$TF dex=$DEX inner_vc=$IVC inner_vn=$IVN"
echo "$VERDICT" > "$OUTDIR/drill-verdict.txt"
cp "$INNER_LOG" "$OUTDIR/inner-pipeline.log"
cp "$OUTDIR/inner-run.last.out" "$OUTDIR/inner-run.last-green.out"
echo "$VERDICT"
echo "$VERDICT" | grep -q '^REBUILD_DRILL_OK ' || fail "verdict_shape"
exit 0
