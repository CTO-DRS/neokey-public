#!/bin/bash
# Saved negative proof for the payment-provider contract (week 24).
# Runs PayGate --negproof: 12 hostile-provider / receipt-tampering attacks,
# each caught by its exact tag (or by proof the provider was never consulted),
# each followed by a full green baseline (topup -> purchase -> install ->
# monthClose) on a fresh wallet. Output pinned at ci/pay-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/paygate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/PayGate.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/StoreUi.java src/com/hark/neokey/ThemePack.java src/com/hark/neokey/Economy.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=./tools/ecj.jar
  [ -f "$ECJ_JAR" ] || { echo "PAY-PROOF_FAILED no javac and no ecj.jar"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "PAY-PROOF_FAILED compile"; exit 1; }

{
  echo "# pay-negative-proof — week 24 (phase 4 W3), $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "# Each attack runs on a fresh wallet and must be caught by its exact tag"
  echo "# (or by evidence the provider was never called). After EVERY attack a full"
  echo "# green baseline re-runs on a clean wallet: containment, not restore."
  java -cp "$OUT" com.hark.neokey.PayGate --negproof
  rc=$?
  echo "# exit=$rc"
  exit $rc
} | tee ci/pay-negative-proof.log
RC=${PIPESTATUS[0]}
[ "$RC" -eq 0 ] && echo "PAY-PROOF OK (log: ci/pay-negative-proof.log)" || echo "PAY-PROOF FAILED (rc=$RC)"
exit $RC
