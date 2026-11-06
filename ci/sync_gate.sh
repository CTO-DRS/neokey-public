#!/bin/bash
# Sync gate (roadmap Phase 2, item A, week 1) — ci/sync_gate.sh
#
#   Measures the E2EE-sync core deliverable, not the claim:
#     • full two-device protocol over the untrusted-server simulator
#       (register → push×3 → new-device restore, wrong passphrase refused)
#     • zero-knowledge scan of the SERVER's own inventory: no Arabic, no record
#       kinds / JSON / magic / passphrase, Shannon entropy ≥ 7.5 bits/byte
#     • exact envelope accounting (50-byte header + pt + 16 GCM tag), < 2% overhead
#     • perf: seal+open ms/100KB < 5, KDF in the documented unlock budget
#     • negatives: 7 tamper regions + cut/empty envelopes + server quota refusals
#
#   Verdict: SYNC_GATE_OK (exit 0) / SYNC_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.SyncGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

# measurement integrity (week 42 fix #2, same law as tests/run.sh): this gate
# enforces WALL-TIME budgets — an inherited JAVA_TOOL_OPTIONS (a JIT-tier
# override) silently biases them; the budgets always measure what ships.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/syncgate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/SyncGate.java src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SyncServerSim.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/NetGate.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "SYNC_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "SYNC_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.SyncGate
rc=$?
[ $rc -eq 0 ] && echo "SYNC_GATE OK" || echo "SYNC_GATE FAILED (rc=$rc)"
exit $rc
