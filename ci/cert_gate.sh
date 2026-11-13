#!/bin/bash
# IntegrityCert gate (roadmap v2, W66) — ci/cert_gate.sh
#
#   Measures the W66 deliverable, not the claim:
#     • the IntegrityCertTest battery: deterministic vectors, the
#       byte-judged match, the planted-byte bite, the fail-closed law,
#       the deterministic display line, and the LIVE check — the signed
#       artifact build/NeoKey-2036.apk against the raised statement
#       (apkSha256), byte-exact
#     • W64 lesson applied: the LIVE source is compiled over build/test —
#       a gate running a stale class cannot see the attack
#   Verdict: CERTGATE_OK (exit 0) / CERTGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/certgate; mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/IntegrityCertTest.java src/com/hark/neokey/IntegrityCert.java 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "CERTGATE_FAILED no javac and no ecj.jar in ./tools"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -cp "build/test:tools/android.jar" -d "$OUT" tests/com/hark/neokey/IntegrityCertTest.java src/com/hark/neokey/IntegrityCert.java 2>&1
fi || { echo "CERTGATE_FAILED compile"; exit 1; }

java -cp "$OUT:build/test" com.hark.neokey.IntegrityCertTest
rc=$?
[ $rc -eq 0 ] && echo "CERT OK" || echo "CERT FAILED (rc=$rc)"
exit $rc
