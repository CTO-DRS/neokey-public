#!/bin/bash
# Saved negative proof for the REST gate (phase 4, week 23) — ci/rest-negative-proof.sh
#
# Nine designed wire attacks against the reference server + the production
# transport. Each must bite with ITS OWN tag, then a fresh-account green round
# on the SAME server must heal (containment per account). The proof fails
# loudly if any attack fails to bite or any baseline fails to heal.
# Output is saved as ci/rest-negative-proof.log (committed evidence).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/restgate
if [ ! -f "$OUT/com/hark/neokey/RestGate.class" ]; then
  ci/rest_gate.sh > /tmp/restgate_boot.log 2>&1 || { echo "REST NEGATIVE PROOF FAILED: gate compile"; exit 1; }
fi

mkdir -p ci
java -cp "$OUT" com.hark.neokey.RestGate --negproof 2>&1 | tee ci/rest-negative-proof.log
rc=${PIPESTATUS[0]}
echo ""
if [ $rc -eq 0 ] && grep -q 'PROOF_PASSED' ci/rest-negative-proof.log; then
  echo "REST NEGATIVE PROOF SAVED → ci/rest-negative-proof.log"
  exit 0
fi
echo "REST NEGATIVE PROOF FAILED (rc=$rc)"
exit 1
