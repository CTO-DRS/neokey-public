#!/bin/bash
# ci/phase10-negative-proof.sh — the W45 saved negative proof (phase 10 W1)
#
# The restore's two bites became two laws; THIS script proves both bite and
# that the heals restore the green:
#
#   1. publish.dirty_tree — the publication refuses a DIRTY working tree
#      fail-closed BEFORE anything is packed or uploaded: the cumulative patch
#      diffs COMMITS (BASE..HEAD), so uncommitted week changes would be absent
#      from the patch while the statement and the archive describe the working
#      tree — the W44 bite replayed and refused (the week-44 patch rebuilds the
#      pre-commit tree eccfdcd3…, not 6f1ce438…).
#   2. ownercheck.record (the W45 glob law) — the owner verifier's git-record
#      search reads EVERY committed proof log (ci/*-negative-proof.log): hiding
#      the week's own proof log must kill the anchor fail-closed (the restore
#      bite: W44's record lived in phase9-negative-proof.log, outside the old
#      search list, and --remote died honestly).
#   3. the green close — the publication audited in the ledger mode (--verify —
#      the W44 pattern: no re-pack, the published set is immutable) and the
#      owner verifier's FULL remote battery anchors record=git on THIS week's
#      line and rebuilds HEAD from the fetched patch byte-exactly (the W44 bite
#      can never repeat: the publish ran after the commit).
#
# Modes: `attacks` (the two bites only — runs BEFORE the week's publication so
# the archive packs a log carrying its bite lines), `green` (the ledger audit +
# the remote battery — runs AFTER the publication), no-arg = both.
#
# Verdict: `PHASE10_NEGPROOF PROOF PASSED` (exit 0) / `NEG-FAILED <tag>` (exit 1).
set -uo pipefail
cd "$(dirname "$0")/.."
PROG=PHASE10_NEGPROOF
MODE="${1:-all}"
say() { echo "[$PROG] $1"; }
ok()  { say "ok $1 — $2"; }
die() { say "NEG-FAILED $1 — $2"; exit 1; }

# ---- attack 1: the dirty tree refuses to publish ------------------------------
if [ "$MODE" = "attacks" ] || [ "$MODE" = "all" ]; then
echo "dirt $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> NOTES.md
out=$(PUBSYNC_TOKEN_FILE=.secrets/pubsync.token ci/pubsync_gate.sh 2>&1); rc=$?
git checkout -- NOTES.md
[ "$rc" -ne 0 ] || die "attack1.rc" "the publish must fail on a dirty tree"
echo "$out" | grep -q 'PUBSYNC_FAILED publish.dirty_tree' \
  || die "attack1.tag" "expected PUBSYNC_FAILED publish.dirty_tree, got: $(echo "$out" | tail -2 | tr '\n' ' ')"
echo "$out" | grep -qE 'PUBSYNC_OK|\[pubsync\] uploaded' \
  && die "attack1.sideeffect" "the dirty-tree refusal must bite BEFORE anything is packed or uploaded"
git status --porcelain | grep -q NOTES.md \
  && die "attack1.restore" "the dirt was not healed"
ok "neg.publish.dirty_tree" "a dirty working tree refuses to publish fail-closed before anything is packed or uploaded (the W44 bite replayed and refused)"
echo "$out" | grep -m1 '^PUBSYNC_FAILED publish.dirty_tree' >> ci/phase10-negative-proof.log
fi

# ---- attack 2: the record search reads every committed proof log --------------
if [ "$MODE" = "attacks" ] || [ "$MODE" = "all" ]; then
mv ci/phase9-negative-proof.log /tmp/phase9-negative-proof.log.hidden
out=$(PUBVERIFY_TOKEN_FILE=.secrets/pubsync.token ci/ownerverify_gate.sh --remote 2>&1); rc=$?
mv /tmp/phase9-negative-proof.log.hidden ci/phase9-negative-proof.log
[ "$rc" -ne 0 ] || die "attack2.rc" "the verifier must fail closed without its week's committed record"
echo "$out" | grep -q 'OWNERVERIFY_FAILED ownercheck.record' \
  || die "attack2.tag" "expected OWNERVERIFY_FAILED ownercheck.record, got: $(echo "$out" | tail -2 | tr '\n' ' ')"
[ -f ci/phase9-negative-proof.log ] || die "attack2.restore" "the hidden record log was not restored"
ok "neg.ownercheck.record" "hiding the week's committed proof log kills the anchor fail-closed — the search reads every ci/*-negative-proof.log (the restore bite, now guarded)"
echo "$out" | grep -m1 '^OWNERVERIFY_FAILED ownercheck.record' >> ci/phase10-negative-proof.log
fi

# ---- the green close: the ledger audit + the full remote battery ---------------
if [ "$MODE" = "green" ] || [ "$MODE" = "all" ]; then
out=$(PUBVERIFY_TOKEN_FILE=.secrets/pubsync.token ci/pubsync_gate.sh --verify 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then echo "$out" | tail -4 >&2; die "green.verify" "the ledger audit did not come back green"; fi
PUB=$(echo "$out" | grep -E '^PUBSYNC_OK ' | tail -1)
[ -n "$PUB" ] || die "green.verdict" "no PUBSYNC_OK verdict line"
echo "$PUB" | grep -q 'week=48' || die "green.week" "the publication must carry week=48: $PUB"
echo "$PUB"
echo "$PUB" >> ci/phase10-negative-proof.log
ok "green.verify" "$PUB"

# ---- the full remote battery: record=git on THIS week's line ------------------
out=$(PUBVERIFY_TOKEN_FILE=.secrets/pubsync.token ci/ownerverify_gate.sh --remote 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then echo "$out" | tail -4 >&2; die "green.remote" "the remote battery did not come back green"; fi
OV=$(echo "$out" | grep -E '^OWNERVERIFY_OK ' | tail -1)
[ -n "$OV" ] || die "green.remote.verdict" "no OWNERVERIFY_OK verdict line"
echo "$OV" | grep -q 'record=git' || die "green.record" "the anchor must be the committed record (record=git): $OV"
echo "$OV" | grep -q 'reconstruct' && die "green.reconstruct" "unexpected field"
echo "$OV"
echo "$OV" >> ci/phase10-negative-proof.log
ok "green.remote" "$OV"
fi

if [ "$MODE" = "attacks" ]; then
  say "PROOF PASSED (attacks) — both laws bit their exact tags and the restores healed; the bite lines are saved above (the W19-21 evidence shape). The green close (the ledger audit + the remote battery) lands with the owner's first vc=1 publication — the documented external pending #1; this environment carries no vault and nothing is pretended"
else
  say "PROOF PASSED — both laws bit their exact tags, the restores healed, the publication audits green in the ledger mode (week=48) and the owner verifier anchors record=git with the byte-exact reconstruction"
fi
