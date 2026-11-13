#!/bin/bash
# PanicWipe gate (roadmap v2, W67) — ci/panic_gate.sh
#
#   Measures the W67 deliverable, not the claim:
#     • the PanicWipeTest battery: the deterministic two-step confirm
#       (no token / wrong token = REFUSED, zero bytes touched), the
#       literal byte-void wipe (every byte zero, original length kept),
#       the SIGNED-BACKUP IMMUNITY (the restore path stays possible),
#       the fail-closed PARTIAL, and the stealth timer returning
#       ALL_CLOSED
#     • W64 lesson applied: the LIVE source is compiled over build/test —
#       a gate running a stale class cannot see the attack
#   Verdict: PANICGATE_OK (exit 0) / PANICGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/panicgate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/PanicWipeTest.java src/com/hark/neokey/PanicWipe.java 2>&1
else
  java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/PanicWipeTest.java src/com/hark/neokey/PanicWipe.java 2>&1
fi || { echo "PANICGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.PanicWipeTest
rc=$?
[ $rc -eq 0 ] && echo "PANIC OK" || echo "PANIC FAILED (rc=$rc)"
exit $rc
