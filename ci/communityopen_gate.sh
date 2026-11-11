#!/bin/bash
# Community-channel OPEN rehearsal gate (roadmap W53 prep — the local half of
# «القاموس المجتمعي — فتح خط المساهمات») — ci/communityopen_gate.sh
#
#   W53's OWN exit criterion («أول دفعة خارجية مقبولة عبر COMMUNITYGATE_OK +
#   صفر رفض خاطئ») stays with the owner — real external batches need real
#   external contributors, which need the store upload first (the documented
#   external pending #1). But the MECHANISM of the channel is fully
#   rehearsable locally on documented stand-in fixtures, and this gate walks
#   it end to end through the REAL production contracts (CommunityDict +
#   DictSign as shipped, the REAL installed dictionary assets/ar.txt):
#
#     1. compile guard      — CommunityOpen + CommunityDict + DictSign +
#                             Feedback (+ the identity constants SyncServerPack)
#     2. THE GUIDE DOC      — docs/COMMUNITY-DICT.md is read and checked: §1's
#                             header is a verbatim mirror of
#                             CommunityDict.HEADER (order included), all 15
#                             refusal tags present, the pos list, both
#                             licenses, the channel (email + documented
#                             subject), the thanks law and the signed-merge
#                             chain — no drift between the guide and the code
#     3. THE INTAKE SCREENER — the doc's §1/§2 rules made mechanical as a
#                             SECOND independent derivation: it must AGREE
#                             with the shipped judge on EVERY row of EVERY
#                             batch (a doc that promised rules the code does
#                             not enforce would show as a loud disagreement)
#     4. THE THANKS PAGE    — CONTRIBUTORS.md checked: the verbatim header,
#                             both licenses, the channel, the credit law
#                             (accepted-only, count-desc-then-alpha, refusals
#                             never appear), the SHA-256 batch law, the dpack
#                             chain — and the generated thanks section obeys
#                             the same laws on live material (a sabotaged
#                             alias NEVER appears)
#     5. THE CHANNEL AS IT ARRIVES — a clean external batch (9 rows, 3 stand-in
#                             contributors 4/3/2) with CRLF lines, a quoted
#                             comma gloss, a tolerated blank line and PARTIAL
#                             diacritics — accepted 9/9 (zero false refusals),
#                             deterministic (same bytes = same report)
#     6. THE SIGNED MERGE   — DictSign.publish carries the report's batchId and
#                             the accepted rows in order, the statement pins
#                             the human sign-off, load passes byte-exact with
#                             the statement and without (the device path), and
#                             three merge refusals bite their exact tags
#                             (DPACK_SIG / EMPTY_PACK / BATCH_MISMATCH)
#     7. THE CHANNEL NEGATIVES — thirteen fresh bad materials each biting its
#                             EXACT tag (eleven row classes through the shipped
#                             judge — including the documented security order
#                             «forbidden BEFORE duplicate» — plus the two
#                             file-level classes BOM / bad header), then the
#                             green channel re-runs byte-identical (heal)
#
#   Verdict: COMMUNITYOPEN_OK checks=N negatives=M/M words=W contribs=K
#            batches=B pack=P vc=1 vn=0.1.0 / COMMUNITYOPEN_FAILED <reason>
#            (exit 1).
#   The first REAL external batch stays the owner's closure through the
#   EXISTING pipeline gate (COMMUNITY_GATE_OK, week-17 stage) on real material
#   after publication — nothing pretended.
#   Standalone by design (the W50/W51/W52-prep pattern): no pipeline stage, no
#   gates.env keys, no closer list moves, no suite-list entry (the file
#   compiles via the runner's glob and is NOT a suite) — every pin stays
#   byte-stable.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "COMMUNITYOPEN_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/communityopen-gate; rm -rf "$OUT"; mkdir -p "$OUT"
SRC="tests/com/hark/neokey/CommunityOpen.java tests/com/hark/neokey/SyncServerPack.java \
src/com/hark/neokey/CommunityDict.java src/com/hark/neokey/DictSign.java \
src/com/hark/neokey/Feedback.java"

# ---- 1: compile guard -----------------------------------------------------------
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: the W53 deliverables are present (the rehearsal reads them itself) -------
[ -f docs/COMMUNITY-DICT.md ] || fail "doc.missing (docs/COMMUNITY-DICT.md)"
[ -f CONTRIBUTORS.md ] || fail "page.missing (CONTRIBUTORS.md)"

# ---- 3: identity from the ONE manifest ------------------------------------------
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ -n "$VC" ] && [ -n "$VN" ] || fail "identity (manifest unreadable)"

# ---- 4: THE REHEARSAL -----------------------------------------------------------
V=$(java -cp "$OUT" com.hark.neokey.CommunityOpen "$VN" "$VC") || {
  echo "$V" | grep -E "^(NEG-FAILED|COMMUNITYOPEN regression|COMMUNITYOPEN_FAILED)" || echo "$V" | tail -5
  fail "rehearsal"
}
echo "$V" | grep -E "^(COMMUNITYOPEN_OK|NEG-FAILED|COMMUNITYOPEN regression|COMMUNITYOPEN_FAILED)" || true
VERDICT=$(echo "$V" | grep '^COMMUNITYOPEN_OK' | tail -1)
[ -n "$VERDICT" ] || fail "rehearsal (verdict missing)"
echo "$VERDICT" | grep -q "vc=$VC vn=$VN" || fail "identity (verdict carries another identity, want vc=$VC vn=$VN)"
echo "COMMUNITYOPEN_GATE=OK"
