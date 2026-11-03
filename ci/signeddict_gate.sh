#!/bin/bash
# Signed community-pack gate (Phase 3, week 2) — ci/signeddict_gate.sh
#
#   The separate publishing step becomes a MEASURED pipeline stage:
#     publish(reviewed CSV) → one canonical pinned pack asset → verify → merge.
#   Proves, on the SHIPPED asset (assets/community_dict.json):
#     • pin over the canonical re-serialization (post-review tampering caught)
#     • re-judge with the SAME reviewer (review bypass caught even with a
#       RECOMPUTED pin — the attacker's own arithmetic cannot launder a row)
#     • 19 designed negatives refused with the EXACT tag (matrix in the gate)
#     • merge plan: corpus first, pack second, first-wins (order contract)
#     • p50 verify time measured on the real pack (< 5000 µs)
#
# Verdict: SIGNEDICT_GATE_OK … (exit 0) / SIGNEDICT_GATE_FAILED <reason> (exit 1).
# JVM-only (com.hark.neokey.SignedDictGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/signeddictgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/SignedDictGate.java src/com/hark/neokey/SignedDict.java src/com/hark/neokey/CommunityDict.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "SIGNEDICT_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "SIGNEDICT_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.SignedDictGate
rc=$?
if [ $rc -eq 0 ] && [ -f build/signeddict/negative-proof.log ]; then
  cp build/signeddict/negative-proof.log ci/signeddict-negative-proof.log
  echo "SIGNEDICT_GATE OK"
else
  echo "SIGNEDICT_GATE FAILED (rc=$rc)"
fi
exit $rc
