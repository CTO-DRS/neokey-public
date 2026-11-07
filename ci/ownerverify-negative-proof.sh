#!/bin/bash
# OWNERVERIFY NEGATIVE PROOF — phase 9 W2 (week 43) — ci/ownerverify-negative-proof.sh
#
#   The weekly law: a gate is not proven by its greens — the saved proof
#   attacks the gate's own inputs and demands each attack bite with its
#   EXACT tag, then restores and demands green again. Four attacks, each a
#   different law class of the owner's fetch chain:
#
#     1. manifest anchor tamper (versionCode -> an always-wrong 81,
#        bump-independent per the W36 lesson) — the anchor bites BEFORE the
#        chain runs («المرجع = المنشور»).
#     2. the record emptied (OWNERVERIFY_RECORD_FILE -> an empty temp file,
#        pinned source) — the gate fails CLOSED (ownercheck.record): no
#        verification without an anchor, no pretending.
#     3. module hidden (PubSync.java -> /tmp) — the gate fails CLOSED at
#        compile (compile.tooling): the one-derivation law has no
#        reimplementations, so hiding the module kills the verifier.
#     4. THE CONSISTENT FORGER (the week's headline law): the evidence
#        archive tampered AND the statement re-stamped to match it
#        (asset_sha1 = sha(tampered)) — PubSync.verify passes GREEN on the
#        forged pair (it is consistent!) and the RECORD is what bites
#        (ownercheck.record_sha): whoever can write the release cannot
#        rewrite the git the owner cloned.
#
#   Baseline green first, every restore via cp (never the tampered bytes),
#   final green re-verify.
set -uo pipefail
cd "$(dirname "$0")/.."
LOG=ci/ownerverify-negative-proof.log
OUTD=build/ownerverify/proof-work
GATE=ci/ownerverify_gate.sh

: > "$LOG"
note() { echo -e "$*" | tee -a "$LOG"; }
run_gate() { OWNERVERIFY_SKIP_BATTERY=1 bash "$GATE" 2>&1; }
expect() { # $1 label, $2 wanted tag, $3 the gate output
  if printf '%s' "$3" | grep -qE "OWNERVERIFY_FAILED $2|PUBSYNC_FAILED $2"; then
    note "ok: $1 bit with its tag ($2)"
  else
    note "PROOF-FAILED: $1 — wanted tag $2, got: $(printf '%s' "$3" | tail -2)"
    exit 1
  fi
}

note "== ownerverify negative proof — $(date -u +%Y-%m-%dT%H:%M:%SZ) =="

note "--- baseline (untampered)"
BASE_OUT=$(run_gate); RC=$?
note "rc=$RC"
printf '%s\n' "$BASE_OUT" | grep -E "^OWNERVERIFY_OK" | tee -a "$LOG"
[ "$RC" = "0" ] || { note "PROOF-FAILED: baseline not green — the publication state must exist (the weekly flow: ci/pubsync_gate.sh first)"; exit 1; }

# ---- attack 1: manifest anchor tamper ------------------------------------------
note "--- attack: manifest anchor tamper (versionCode -> an always-wrong 81, bump-independent per the W36 lesson)"
cp AndroidManifest.xml "$OUTD-manifest.bak"
sed -i 's/android:versionCode="[0-9]*"/android:versionCode="81"/' AndroidManifest.xml
A1=$(run_gate); RC=$?
expect "attack 1" "anchor.identity.vc" "$A1"
cp "$OUTD-manifest.bak" AndroidManifest.xml
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^OWNERVERIFY_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 1 not green"; exit 1; }

# ---- attack 2: the record emptied ----------------------------------------------
note "--- attack: the record emptied (pinned source -> empty file — no anchor, no pretending)"
: > "$OUTD-empty-record"
A2=$(OWNERVERIFY_RECORD_FILE="$OUTD-empty-record" run_gate); RC=$?
expect "attack 2" "ownercheck.record" "$A2"
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^OWNERVERIFY_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 2 not green"; exit 1; }

# ---- attack 3: module hidden ----------------------------------------------------
note "--- attack: module hidden (PubSync.java -> /tmp — the one-derivation law kills the verifier)"
cp tests/com/hark/neokey/PubSync.java "$OUTD-pubsync.bak"
mv tests/com/hark/neokey/PubSync.java /tmp/PubSync.java.hidden
A3=$(run_gate); RC=$?
expect "attack 3" "compile.tooling" "$A3"
mv /tmp/PubSync.java.hidden tests/com/hark/neokey/PubSync.java
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^OWNERVERIFY_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 3 not green"; exit 1; }

# ---- attack 4: the consistent forger --------------------------------------------
note "--- attack: THE CONSISTENT FORGER (archive tampered + statement re-stamped — the module passes green, the RECORD bites)"
mkdir -p "$OUTD-forged"
PUBDIR=build/pubsync
EV_NAME=$(ls "$PUBDIR" | grep -oE 'neokey-evidence-week[0-9]+\.zip' | head -1)
PATCH_NAME=$(ls "$PUBDIR" | grep -oE 'neokey-patch-week[0-9]+-[0-9]+\.patch' | head -1)
[ -n "$EV_NAME" ] && [ -n "$PATCH_NAME" ] || { note "PROOF-FAILED: the publication state is missing its assets"; exit 1; }
cp "$PUBDIR/$EV_NAME" "$OUTD-forged/$EV_NAME"
cp "$PUBDIR/$PATCH_NAME" "$OUTD-forged/$PATCH_NAME"
python3 - "$OUTD-forged/$EV_NAME" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
b[35] ^= 0x5A
open(p, "wb").write(bytes(b))
PY
FSHA=$(sha256sum "$OUTD-forged/$EV_NAME" | cut -d' ' -f1)
sed "s/\"asset_sha1\": \"[0-9a-f]*\"/\"asset_sha1\": \"$FSHA\"/" "$PUBDIR/statement.json" > "$OUTD-forged/statement.json"
A4=$(OWNERVERIFY_STATE_DIR="$OUTD-forged" run_gate); RC=$?
# the forged pair is CONSISTENT: the module's own verify passes on it —
# assert that first (the forger is not caught by the statement laws), then
# demand the RECORD bite
if printf '%s' "$A4" | grep -q "PUBSYNC_FAILED pubsync.sha_mismatch"; then
  note "PROOF-FAILED: the forger was caught too early — the re-stamped statement must PASS the module (that is what makes it 'consistent'); got sha_mismatch"
  exit 1
fi
expect "attack 4" "ownercheck.record_sha" "$A4"
rm -rf "$OUTD-forged"
R=$(run_gate); RC=$?
note "restored: $(printf '%s\n' "$R" | grep -E '^OWNERVERIFY_OK' | head -1)"
[ "$RC" = "0" ] || { note "PROOF-FAILED: restore 4 not green"; exit 1; }

note ""
note "== PROOF PASSED: all four attacks bit with their own tags, all files restored, final gate green =="
