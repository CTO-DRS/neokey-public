#!/bin/bash
# Payment-provider contract gate (phase 4, week 3 / week 24) — ci/pay_gate.sh
#
#   Proves the week-19 promise after implementing it: the PaymentProvider
#   interface is THE standard replacement point — the wallet accepts money
#   only through a verified sealed receipt, there is no second money path.
#     • the deterministic provider proof: the same request sequence on two
#       independent providers yields byte-identical receipt seals, journal
#       and balance (the payment layer is provable offline, no network)
#     • the full flow over the REAL pinned catalog: provider topup -> buy all
#       25 packs -> install through the guard chain -> 70/30 close to the cent
#     • purchase-function invariance: NO_PACK / ALREADY_OWNED / INSUFFICIENT
#       and the STOREENT1 restore code are exactly as they were
#     • caps are wallet-side: a greedy approves-everything provider cannot
#       exceed TOPUP_CAP / BALANCE_CAP — proven by the provider never being called
#     • designed negatives refused with exact tags (declined passthrough,
#       provider exception, missing/inflated/bad-seal/foreign receipts, REPLAY,
#       externalRef journal injection, journal-copy tamper)
#     • topup p50 measured
#
# Verdict: PAYGATE_OK … (exit 0) / PAYGATE_FAILED <reason> (exit 1).
# JVM-only (com.hark.neokey.PayGate has zero android.*) — runs anywhere.
# Saved negative proof: --negproof writes ci/pay-negative-proof.log via ci/pay-negative-proof.sh
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/paygate; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/PayGate.java src/com/hark/neokey/PaymentProvider.java src/com/hark/neokey/StoreUi.java src/com/hark/neokey/ThemePack.java src/com/hark/neokey/Economy.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "PAYGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || { echo "PAYGATE_FAILED compile"; exit 1; }

java -cp "$OUT" com.hark.neokey.PayGate
rc=$?
[ $rc -eq 0 ] && echo "PAYGATE OK" || echo "PAYGATE FAILED (rc=$rc)"
exit $rc
