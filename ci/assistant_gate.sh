#!/bin/bash
# Assistant gate (roadmap Phase 1, items C2+D1, week 6) — ci/assistant_gate.sh
#
#   Measures the week's deliverables, not the claim:
#     • SmartSwitch: curated 48-row context set (assets/testset_switch.txt) —
#       auto mode picks the right DICTIONARY (ar / ar_hijazi / ar_najdi /
#       ar_gulf), anti-flap hysteresis rows stay MSA, determinism, p50 < 1ms
#     • Assistant (3 local capabilities, 100% on-device): summarize (shorter,
#       top-topic retained, frequency-not-position scoring, deterministic,
#       p50 < 2ms) · rephrase (both directions fire, no leftover source
#       phrase, word boundaries intact) · tones (3 distinct drafts, formal/
#       friendly/short markers, 6 intents covered)
#     • NetGate: full fail-closed matrix (every ambiguous state denies,
#       unknown cap denies, endpoint allowlist refuses look-alike hosts,
#       5-field audit line carries zero user text)
#     • settings wiring: all 6 legal modes valid, unknown mode rejected
#     • negative proofs INSIDE the gate: maxSentences=0 → "", scoring is
#       frequency-based (last-placed top sentence still wins), unknown cap
#       denied with everything on, mode=msa never switches, look-alike host
#       refused
#
#   Verdict: ASSIST_GATE_OK (exit 0) / ASSIST_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.AssistantGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/assistgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/AssistantGate.java src/com/hark/neokey/SmartSwitch.java src/com/hark/neokey/Assistant.java src/com/hark/neokey/NetGate.java src/com/hark/neokey/Dialect.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "ASSIST_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "ASSIST_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.AssistantGate
rc=$?
[ $rc -eq 0 ] && echo "ASSIST_GATE OK" || echo "ASSIST_GATE FAILED (rc=$rc)"
exit $rc
