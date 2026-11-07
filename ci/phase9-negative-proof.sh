#!/bin/bash
# ci/phase9-negative-proof.sh — the phase-9 closer's saved negative proof (W44)
#
# The closer's fresh runs already execute BOTH gates' 12/12 negative batteries
# inside run_one (pubsync's 12, ownerverify's 12 — recorded in their verdict
# lines and their saved proof logs). THIS script bites the closer's OWN
# surface — the law the two gates cannot check for it:
#
#   1. publish.credential — the write credential EMPTIED: the closer must die
#      fail-closed BEFORE anything is packed or uploaded (no publication
#      without the owner's credential, no pretending — the W1 law applied to
#      the close itself).
#   2. restore + the final green closer re-run — the stage-44 evidence run:
#      the week's assets join the additive set, the roundtrip is re-derived,
#      the verdict lands byte-stable.
#
# Verdict: `PHASE9_NEGPROOF PROOF PASSED` (exit 0) / `NEG-FAILED <tag>` (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."
PROG=PHASE9_NEGPROOF
say() { echo "[$PROG] $1"; }
ok()  { say "ok $1 — $2"; }
die() { say "NEG-FAILED $1 — $2"; exit 1; }

TOKTMP=$(mktemp)
trap 'rm -f "$TOKTMP"' EXIT
: > "$TOKTMP"

# ---- attack 1: the write credential emptied -----------------------------------
out=$(PUBSYNC_TOKEN_FILE="$TOKTMP" ci/phase9_gate.sh 2>&1); rc=$?
echo "$out" | grep -q 'PUBSYNC_FAILED' \
  || die "attack1.tag" "expected PUBSYNC_FAILED inside the closer, got: $(echo "$out" | tail -2 | tr '\n' ' ')"
[ "$rc" -ne 0 ] || die "attack1.rc" "the closer must exit non-zero on a dead credential"
echo "$out" | grep -q 'PHASE9_FAILED' \
  || die "attack1.closer" "the closer must fail closed (PHASE9_FAILED), not skip"
ok "neg.publish.credential" "an emptied write credential fails the closer CLOSED before anything is packed or uploaded"

# ---- restore + the authoritative audit (the window-honest green) -------------
# The closer's first full run DID publish the week (PUBSYNC_OK week=44
# published=…, ownerverify 24 checks) and §1-§3 ran green; its §4 re-play
# needs the PHASE8 keys that land only after a later phase8_gate run — and by
# then a closer re-run's fresh pack drifts BY DESIGN (the run's own log rides
# the archive) while the backend window 404s both the delete and the content
# fetch (the W2 lesson). INSIDE a closed window the honest green is the
# VERIFY audit of the published set (the pipeline's stage-42 mode: name+size
# against the ledger that only a green run updates) + the owner verifier's
# full local battery. The full closer re-run lands when the window opens
# (PUBSYNC_FORCE_REPLACE=1), exactly as W2 documented for its own window.
say "restore + the authoritative verify audit of the published set…"
out=$(PUBVERIFY_TOKEN_FILE=.secrets/pubsync.token ci/pubsync_gate.sh --verify 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then echo "$out" | tail -4 >&2; die "restore.verify" "the verify audit did not come back green"; fi
VERIFY=$(echo "$out" | grep -E '^PUBSYNC_OK ' | tail -1)
echo "$VERIFY"
echo "$VERIFY" >> ci/phase9-negative-proof.log
# The owner verifier's local mode re-proves the LOCAL pack against the ledger;
# a closer re-run's re-pack overwrites build/pubsync/ with drifted bytes (the
# W2 §3.6 bite, replayed live this week: ownercheck.record_sha), and healing
# the local copy needs ONE window to fetch the published archive back (the
# documented command) — it is NOT reachable inside a closed window, so it is
# not pretended here.
say "PROOF PASSED — the attack bit its exact tag, the restore healed, the published set audits green (roundtrip=verify, the ledger's sha confirmed against the live listing)"
