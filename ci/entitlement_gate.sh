#!/bin/bash
# Entitlements-across-devices gate (roadmap Phase 3, week 3) — ci/entitlement_gate.sh
#
#   Measures the PROTOCOL, not the claim, on the untrusted-server simulator:
#     • two real devices over SyncServerSim: real catalog purchase on A ->
#       entitlement records (pins only) -> E2E sealed push -> fresh device B
#       login/pull/apply -> grantFromSync -> install binaries FROM THE CATALOG
#     • reverse round: B's purchase crosses back to A (last-writer-wins merge)
#     • zero-knowledge stays MEASURED on server storage: entropy + no pack id
#       and no restore-code text anywhere in what the server sees
#     • planted record of unknown KIND is ignored: never poisons the blob,
#       never reaches state; local construction of unknown kinds stays refused
#     • account quotas respected with an entitlement-bearing payload (quota_free)
#     • grant contract: idempotent by pin, PIN_MISMATCH / NO_PACK refused,
#       tombstones grant nothing, uninstall never revokes ownership
#     • perf: collect/apply over 3000 dict words + 200 entitlements, grant p50
#
#   Verdict: ENTGATE_OK … (exit 0) / ENTGATE_FAILED <reason> (exit 1).
#   JVM-only (com.hark.neokey.EntitlementGate has zero android.*) — runs anywhere.
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/entgate; mkdir -p "$OUT"
# StoreUi intentionally reuses ThemePack's strict reviewer + sha256 pin and
# Economy's single ledger; SyncState reuses Dictionary.norm. The gate compiles
# against android.jar when present (build machine), else the JVM stubs.
AJ=""
[ -f tools/android.jar ] && AJ=tools/android.jar
CP=""
[ -n "$AJ" ] && CP="-cp $AJ"
STUBS=""
[ -z "$AJ" ] && STUBS=$(find tests/stubs -name '*.java' | tr '\n' ' ')
SRC="tests/com/hark/neokey/EntitlementGate.java src/com/hark/neokey/StoreUi.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/Economy.java src/com/hark/neokey/ThemePack.java src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SyncState.java src/com/hark/neokey/SyncClient.java src/com/hark/neokey/SyncServerSim.java src/com/hark/neokey/NetGate.java src/com/hark/neokey/Dictionary.java src/com/hark/neokey/DictSign.java src/com/hark/neokey/CommunityDict.java src/com/hark/neokey/ArabicFixes.java src/com/hark/neokey/Exceptions.java $STUBS"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "ENTGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 $CP -d "$OUT" $SRC 2>&1
fi || { echo "ENTGATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.EntitlementGate
rc=$?
[ $rc -eq 0 ] && echo "ENTGATE OK" || echo "ENTGATE FAILED (rc=$rc)"
exit $rc
