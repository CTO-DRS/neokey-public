#!/bin/bash
# DialectAdapt gate (roadmap v2, W59) — ci/dialectadapt_gate.sh
#
#   Measures the W59 deliverable, not the claim:
#     • the DialectAdaptTest battery (every law: record/seed/downgrade/merge/
#       format/cap/incognito-refusals) — every law at least a positive+negative
#     • detection accuracy on assets/testset_dialects.txt ≥85% overall and
#       ≥75% per class — the documented W54 first-measure floor, same set
#     • honest seeding: seed() answers exactly the recorded dictId for
#       established profiles, null for unstable/downgraded/unknown apps —
#       no cold-start guessing, ever
#     • SmartSwitch untouched: live auto/off/msa/fixed decisions on the
#       curated windows byte-identical to the pre-W59 law
#
#   Verdict: DIALECTADAPTGATE_OK (exit 0) / DIALECTADAPT_FAILED <reason> (exit 1).
#   JVM-only — runs anywhere. Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/dialectadaptgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/DialectAdaptGate.java tests/com/hark/neokey/DialectAdaptTest.java src/com/hark/neokey/DialectAdapt.java src/com/hark/neokey/Dialect.java src/com/hark/neokey/SmartSwitch.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "DIALECTADAPT_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "DIALECTADAPT_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.DialectAdaptGate
rc=$?
[ $rc -eq 0 ] && echo "DIALECTADAPT OK" || echo "DIALECTADAPT FAILED (rc=$rc)"
exit $rc
