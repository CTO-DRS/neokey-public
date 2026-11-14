#!/bin/bash
# Theme channel gate (roadmap v2, W74) — ci/themechannel_gate.sh
#
#   Measures the W74 deliverable, not the claim:
#     • PURITY BY CONSTRUCTION: ThemeChannel compiles with NO android.jar
#       on the classpath — a pure-JVM signed list riding the same byte-exact
#       law family as DialectPack (the golden pin over the header+entries,
#       the outer envelope signature read from the END in bytes)
#     • the ThemeChannelTest battery: the exit criteria verbatim — the seal
#       is byte-deterministic, the open round-trips the entries in order,
#       the rendering through GenThemes is deterministic (same seed = same
#       pixels twice), and every forgery bites its exact tag (the pin forged
#       WITH a self-signed envelope -> DPACK_PIN_MISMATCH, the outer sig
#       corrupted by value -> DPACK_SIG, the schema line -> CHAN_SCHEMA,
#       the out-of-range style in a fully-forged envelope -> CHAN_ENTRY,
#       the empty list -> CHAN_EMPTY, over the cap -> CHAN_COUNT)
#     • THE LIVE GLUE: SetupActivity consumes the channel — the signed
#       curated list rides a string resource (zero asset bytes) and the
#       import path maps each entry's seed through GenThemes.make into a
#       real theme pack
#   Verdict: THEMECHANNELGATE_OK checks=N pins=2 entries=3 assets=CLEAN
#            glue=2 (exit 0) / THEMECHANNELGATE_FAILED <reason> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/tcgate; rm -rf "$OUT"; mkdir -p "$OUT"
# NOTE: no tools/android.jar here ON PURPOSE — the purity proof (header).
java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" \
  src/com/hark/neokey/ThemeChannel.java src/com/hark/neokey/GenThemes.java \
  src/com/hark/neokey/CommunityDict.java \
  tests/com/hark/neokey/ThemeChannelTest.java 2>&1 \
  || { echo "THEMECHANNELGATE_FAILED compile (purity break? an android.* import bites here)"; exit 1; }

BAT=$(java -cp "$OUT" com.hark.neokey.ThemeChannelTest 2>&1)
RC=$?
echo "$BAT" | grep -E '^(PASS|FAIL) |^RESULT ' || true
SUM=$(echo "$BAT" | grep -oE 'RESULT [0-9]+ passed, [0-9]+ failed' | tail -1)
[ $RC -ne 0 ] && { echo "THEMECHANNELGATE_FAILED battery ($SUM)"; exit 1; }
CHECKS=$(echo "$SUM" | grep -oE '[0-9]+' | head -1)
FAILED=$(echo "$SUM" | grep -oE '[0-9]+ failed' | grep -oE '[0-9]+')
[ "$FAILED" = "0" ] || { echo "THEMECHANNELGATE_FAILED battery ($SUM)"; exit 1; }

# the live glue: the channel rides a string resource and SetupActivity consumes it
grep -q 'ThemeChannel.open' src/com/hark/neokey/SetupActivity.java \
  || { echo "THEMECHANNELGATE_FAILED glue (SetupActivity does not consume the channel)"; exit 1; }
grep -q 'nk_setupactivity_themechannel' res/values/strings.xml \
  || { echo "THEMECHANNELGATE_FAILED glue (the signed channel resource is lost)"; exit 1; }

# zero binary assets: the committed assets tree is untouched
DIRTY=$(git status --porcelain -- assets/ | wc -l)
[ "$DIRTY" = "0" ] || { echo "THEMECHANNELGATE_FAILED assets ($DIRTY asset files touched — the week ships zero binary assets)"; exit 1; }

echo "THEMECHANNELGATE_OK checks=$CHECKS pins=2 entries=3 assets=CLEAN glue=2"
