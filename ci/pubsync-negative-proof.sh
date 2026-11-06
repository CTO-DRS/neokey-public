#!/bin/bash
# PUBSYNC NEGATIVE PROOF — phase 9 W1 (week 42) — ci/pubsync-negative-proof.sh
#
#   The weekly law: a gate is not proven by its greens — the saved proof
#   attacks the gate's own inputs and demands each attack bite with its
#   EXACT tag, then restores and demands green again. Four attacks:
#
#     1. manifest anchor tamper (versionCode -> an always-wrong 81, bump-independent per the W36 lesson) — bump-independent,
#        bites BEFORE anything is packed or uploaded (anchor.identity.vc).
#     2. outer-log drift (the prior PUBSYNC_OK line's base tag flipped in
#        build/pipeline.log) — the verdict-carrying log is evidence; a
#        drifted stable field bites outer_log.pubsync (members/proofs are
#        monotonic and masked; a stable field is not).
#     3. module hidden (PubSync.java -> /tmp) — the gate fails CLOSED at
#        compile (compile.tooling), nothing is packed, nothing is uploaded.
#     4. credential hidden (PUBSYNC_TOKEN_FILE -> an empty temp file) —
#        the gate fails CLOSED before the anchor's next step
#        (publish.credential): no publication without the owner's
#        credential, no pretending.
#
#   Baseline green first, every restore via cp (never the tampered bytes),
#   final green re-verify publishes the same deterministic bytes again.
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=ci/pubsync-negative-proof.log
OUTD=build/pubsync/proof-work

: > "$LOG"
note() { echo -e "$*" | tee -a "$LOG"; }
run_gate() { PUBSYNC_TOKEN_FILE="${PUBSYNC_TOKEN_FILE:-/tmp/gh.token}" ci/pubsync_gate.sh --roundtrip 2>&1; }
expect() { # $1 label, $2 wanted tag, $3 the gate output
  if printf '%s' "$3" | grep -q "PUBSYNC_FAILED $2"; then
    note "ok: $1 bit with its tag ($2)"
  else
    note "PROOF-FAILED: $1 — wanted PUBSYNC_FAILED $2, got: $(printf '%s' "$3" | tail -2)"
    exit 1
  fi
}

note "== pubsync negative proof — $(date -u +%Y-%m-%dT%H:%M:%SZ) =="

note "--- baseline (untampered)"
BASE_OUT=$(run_gate); RC=$?
note "rc=$RC"
printf '%s\n' "$BASE_OUT" | grep -E "^PUBSYNC_OK" | tee -a "$LOG"
[ "$RC" = "0" ] || { note "PROOF-FAILED: baseline not green"; exit 1; }

# ---- attack 1: manifest anchor tamper -----------------------------------------
note "--- attack: manifest anchor tamper (versionCode -> an always-wrong 81, bump-independent per the W36 lesson)"
cp AndroidManifest.xml "$OUTD-manifest.bak"
sed -i 's/android:versionCode="[0-9]*"/android:versionCode="81"/' AndroidManifest.xml
A1=$(run_gate); RC=$?
expect "attack 1" "anchor.identity.vc" "$A1"
cp "$OUTD-manifest.bak" AndroidManifest.xml
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^PUBSYNC_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 1 not green"; exit 1; }

# ---- attack 2: outer-log drift -------------------------------------------------
note "--- attack: outer-log drift (the prior PUBSYNC_OK line's base tag flipped)"
cp build/pipeline.log "$OUTD-pipeline.bak"
sed -i 's/rel=434872239 base=v2.7-week33 vc=83/rel=434872239 base=v2.7-week32 vc=83/' build/pipeline.log
A2=$(run_gate); RC=$?
expect "attack 2" "outer_log.pubsync" "$A2"
cp "$OUTD-pipeline.bak" build/pipeline.log
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^PUBSYNC_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 2 not green"; exit 1; }

# ---- attack 3: module hidden ---------------------------------------------------
note "--- attack: module hidden (PubSync.java -> /tmp)"
cp tests/com/hark/neokey/PubSync.java /tmp/PubSync.java.proof-bak
rm tests/com/hark/neokey/PubSync.java
A3=$(run_gate); RC=$?
expect "attack 3" "compile.tooling" "$A3"
cp /tmp/PubSync.java.proof-bak tests/com/hark/neokey/PubSync.java
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^PUBSYNC_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 3 not green"; exit 1; }

# ---- attack 4: credential hidden ----------------------------------------------
note "--- attack: credential hidden (PUBSYNC_TOKEN_FILE -> empty temp file)"
: > /tmp/pubsync-proof-empty.token
A4=$(PUBSYNC_TOKEN_FILE=/tmp/pubsync-proof-empty.token ci/pubsync_gate.sh 2>&1); RC=$?
expect "attack 4" "publish.credential" "$A4"
rm -f /tmp/pubsync-proof-empty.token
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^PUBSYNC_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 4 not green"; exit 1; }

note ""
note "== PROOF PASSED: all four attacks bit with their own tags, all files restored, final gate green =="
FINAL=$(run_gate); RC=$?
printf '%s\n' "$FINAL" | grep -E "^PUBSYNC_OK" | tee -a "$LOG"
[ "$RC" = "0" ] || { note "PROOF-FAILED: final green re-verify"; exit 1; }
note "$(printf '%s' "$FINAL" | grep -E '^PUBSYNC_OK' | head -1)"
