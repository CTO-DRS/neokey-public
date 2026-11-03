#!/bin/bash
# Sync client gate (roadmap Phase 2, item A, week 2 — A2) — ci/sync_client_gate.sh
#
#   Measures the DELIVERY slice, not the claim:
#     • real state collected by SyncState from real store shapes (460 dict words,
#       40 exceptions, 30 phrases, 20 shortcuts, 25 pins, 4 whitelisted settings)
#     • full two-device round: push → new-device login → pull → apply plan counts
#     • reverse round: device B's local edit reaches A (last-writer-wins merge)
#     • zero-knowledge re-check on the server inventory AFTER the new push
#     • full NetGate matrix for the new "sync" capability + endpoint whitelist
#     • audit line discipline: SYNCAUDIT carries no state content at all
#     • file backup NSF1: round trip + wrong passphrase + header tamper + cut file
#     • apply-time whitelist defence: planted ai_key/net_sync records are ignored
#     • perf: collect+seal 3000-word dict ≤ 150ms, KDF within the 400ms unlock budget
#
#   Verdict: SYNCCLIENT_GATE_OK (exit 0) / SYNCCLIENT_GATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.SyncClientGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/syncclientgate; mkdir -p "$OUT"
# SyncState intentionally reuses Dictionary.norm (single source of Arabic
# normalization — hamza-family dedup must match the correction engine exactly),
# so the gate compiles against the platform jar like the R8 stage does.
AJ=""
[ -f tools/android.jar ] && AJ=tools/android.jar
CP=""
[ -n "$AJ" ] && CP="-cp $AJ"
# Compile contract: android.jar when present (build machine); otherwise the same
# JVM stubs tests/run.sh uses (tests/stubs/android/**) — compile-time-only types,
# behaviour comes from the pure classes under test either way.
STUBS=""
[ -z "$AJ" ] && STUBS=$(find tests/stubs -name '*.java' | tr '\n' ' ')
SRC="tests/com/hark/neokey/SyncClientGate.java src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SyncServerSim.java src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/NetGate.java src/com/hark/neokey/Dictionary.java src/com/hark/neokey/SignedDict.java src/com/hark/neokey/CommunityDict.java src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "SYNCCLIENT_GATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || { echo "SYNCCLIENT_GATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.SyncClientGate
rc=$?
[ $rc -eq 0 ] && echo "SYNCCLIENT_GATE OK" || echo "SYNCCLIENT_GATE FAILED (rc=$rc)"
exit $rc
