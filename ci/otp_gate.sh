#!/bin/bash
# OTPShield gate (roadmap v2, W65) — ci/otp_gate.sh
#
#   Measures the W65 deliverable, not the claim:
#     • the OTPShieldTest battery: the reference set IS the envelope
#       (accuracy 100% required), the exact-span checks, the documented
#       exclusions, the deterministic expiry counter, the self-wipe due()
#       law, and the incognito/pref double law
#     • W64 lesson applied: the LIVE source is compiled over build/test —
#       a gate running a stale class cannot see the attack
#   Verdict: OTPGATE_OK (exit 0) / OTPGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/otpgate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/OTPShieldTest.java src/com/hark/neokey/OTPShield.java 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "OTPGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/OTPShieldTest.java src/com/hark/neokey/OTPShield.java 2>&1
fi || { echo "OTPGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.OTPShieldTest
rc=$?
[ $rc -eq 0 ] && echo "OTP OK" || echo "OTP FAILED (rc=$rc)"
exit $rc
