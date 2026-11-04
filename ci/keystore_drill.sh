#!/bin/bash
# Keystore-recovery drill (phase 5, week 3 — W28) — ci/keystore_drill.sh
#
#   The documented pending "استعادة أسرار التوقيع من النسخة المشفرة قبل أي بناء
#   موقّع" (NK-SEC-001 + store-readiness.pendingOutsideThisEnvironment) has two
#   halves: the SECRET stays in the owner's vault (never reproducible here),
#   and the PROCEDURE that decides whether a restored keystore is the release
#   identity, valid for release — THIS is the automated rehearsal of that
#   procedure, run against a committed stand-in fixture:
#
#     1. compile guard      — KsValidator + KsDrillGate (pure JDK, no classpath)
#     2. fixture guard      — the stand-in keystores are present
#     3. THE GATE           — KsDrillGate: THE ANCHOR IS THE FIRST BARRIER (the
#                             W26 house rule — nothing rides a fixture that is
#                             not the published identity), then eight designed
#                             negatives proving every check still bites
#     4. RECOVERY SIMULATION— the fixture is restored into build/ks-drill/
#                             restored.p12 (chmod 600, exactly the vault
#                             discipline) with a RESTORE-SOURCE note
#     5. THE DRILL          — KsValidator CLI on the RESTORED COPY against the
#                             published fingerprint: the identity decision the
#                             owner will make on the real artifact
#
#   Verdict: KSDRILLGATE_OK checks=N negatives=M/M fp16=… days_left=…
#            / KSDRILLGATE_FAILED <reason> (exit 1)
#   JVM-only — runs anywhere a JRE exists; zero app code, the dex budget is
#   not spent (tests/ only).
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/ksdrill; rm -rf "$OUT"; mkdir -p "$OUT"
EXPECTED_FP=a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587

fail() { echo "KSDRILLGATE_FAILED $1"; exit 1; }

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

# ---- 1: compile guard ---------------------------------------------------------
SRC="tests/com/hark/neokey/KsValidator.java tests/com/hark/neokey/KsDrillGate.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -d "$OUT" $SRC 2>&1
else
  ECJ_JAR=$(find_jar ecj.jar) || fail "compile (no javac and no ecj.jar)"
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d "$OUT" $SRC 2>&1
fi || fail "compile"

# ---- 2: fixture guard ---------------------------------------------------------
for fx in drill-release drill-rogue drill-weak drill-expired drill-short drill-jks; do
  [ -f "tests/ks/$fx.p12" ] || fail "fixtures (tests/ks/$fx.p12 missing)"
done

# ---- 3: THE GATE — the anchor is the FIRST barrier, then every negative bites -
java -cp "$OUT" com.hark.neokey.KsDrillGate || exit 1

# ---- 4: the recovery simulation (the owner's restore step, rehearsed) ---------
mkdir -p build/ks-drill
cp tests/ks/drill-release.p12 build/ks-drill/restored.p12
chmod 600 build/ks-drill/restored.p12
{
  echo "drill stand-in restore — $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "source: tests/ks/drill-release.p12 (committed fixture generated ONLY for this drill;"
  echo "        the real artifact comes from the owner's ENCRYPTED BACKUP and never leaves the vault)"
  echo "decision procedure: ci/keystore_drill.sh → KsValidator (this run) — same class, same checks,"
  echo "                    same pinned fingerprint comparison the owner will run on the real restore."
} > build/ks-drill/RESTORE-SOURCE.txt

# ---- 5: THE DRILL — validate the restored copy against the published pin ------
DRILL_OUT=$(java -cp "$OUT" com.hark.neokey.KsValidator \
      build/ks-drill/restored.p12 drill-pass-2026 neokey-drill "$EXPECTED_FP" 2>&1)
echo "$DRILL_OUT"
echo "$DRILL_OUT" | grep -q '^KSDRILL_OK' || fail "drill ($(echo "$DRILL_OUT" | grep -m1 KSDRILL_FAIL || echo 'no verdict'))"
DRILL_FP16=$(echo "$DRILL_OUT" | sed -nE 's/^KSDRILL_OK .*fp=([0-9a-f]{16}).*/\1/p')
DRILL_DAYS=$(echo "$DRILL_OUT" | sed -nE 's/^KSDRILL_OK .*days_left=([0-9]+).*/\1/p')
echo "KSDRILL_DRILL_OK restored=build/ks-drill/restored.p12 fp16=${DRILL_FP16:-none} days_left=${DRILL_DAYS:-0} checks=restore+type+alias+key+alg+bits+signature+validity+identity"
echo "KSDRILL_GATE=OK"
exit 0
