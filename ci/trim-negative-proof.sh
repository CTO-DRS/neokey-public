#!/bin/bash
# Negative proof for the trim gate (phase 4, week 22) — SAVED evidence.
# Three designed attacks; each must fail the gate with ITS OWN tag, then be
# restored (cp-backup, never git — the files may be uncommitted) and the gate
# re-verified GREEN. The proof fails loudly if any attack fails to bite or any
# restore fails to heal.
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=build/trimgate
GATE="java -cp $OUT com.hark.neokey.TrimGate"
FAILED=0

echo "== trim negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/trim-proof-backup
for f in res/values/strings.xml src/com/hark/neokey/DictActivity.java src/com/hark/neokey/Writer.java; do
  mkdir -p "build/trim-proof-backup/$(dirname "$f")"; cp "$f" "build/trim-proof-backup/$f"
done

run_attack() { # $1 name, $2 attacker (bash), $3 expected verdict substring
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  $GATE > /tmp/trim_neg.txt 2>&1; local rc=$?
  local verdict=$(grep -m1 'TRIM_GATE_FAILED' /tmp/trim_neg.txt || echo "(no verdict line)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  case "$verdict" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ $rc -ne 0 ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  for f in res/values/strings.xml src/com/hark/neokey/DictActivity.java src/com/hark/neokey/Writer.java; do
    cp "build/trim-proof-backup/$f" "$f"
  done
  $GATE > /tmp/trim_ok.txt 2>&1; local rc2=$?
  local okline=$(grep -m1 'TRIM_GATE_OK' /tmp/trim_ok.txt || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ $rc2 -eq 0 ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) missing resource: delete one nk_ entry (and its origin comment) precisely
run_attack "missing resource (delete nk_setupactivity_buildheader_1)" \
'python3 - <<PYEOF
import re
s = open("res/values/strings.xml", encoding="utf-8").read()
s2 = re.sub(r"<!-- origin: SetupActivity\.java:212 -->\n    <string name=\"nk_setupactivity_buildheader_1\"[^<]*</string>\n", "", s)
assert s2 != s, "victim entry not found"
open("res/values/strings.xml", "w", encoding="utf-8").write(s2)
PYEOF' \
"missing resource R.string.nk_setupactivity_buildheader_1"

# 2) long Arabic literal regrows in a shell class (new copy must go to resources)
run_attack "regrown long Arabic literal in shell class (DictActivity)" \
'python3 - <<PYEOF
s = open("src/com/hark/neokey/DictActivity.java", encoding="utf-8").read()
marker = "private void buildChips()"
lit = "نص عربي طويل جداً للتقليم: هذا نص تجريبي إضافي يتجاوز الستين بايتاً من المحتوى العربي الثابت داخل صنف واجهة، وهو ممنوع بعد أسبوع التقليم الحقيقي ويتوجب نقله إلى موارد strings.xml."
assert marker in s
s = s.replace(marker, "private void __trimNeg() { String regrown = \"" + lit + "\"; }\n\n    " + marker, 1)
open("src/com/hark/neokey/DictActivity.java", "w", encoding="utf-8").write(s)
PYEOF' \
"long Arabic literals back in shell classes"

# 3) purity broken: android import WITHOUT a stub class in a run.sh pure file
run_attack "purity broken (android.webkit.WebView in pure Writer)" \
'python3 - <<PYEOF
s = open("src/com/hark/neokey/Writer.java", encoding="utf-8").read()
s = s.replace("package com.hark.neokey;", "package com.hark.neokey;\n\nimport android.webkit.WebView; // trim-negative: purity attack", 1)
open("src/com/hark/neokey/Writer.java", "w", encoding="utf-8").write(s)
PYEOF' \
"purity broken (android import without a stub class"

rm -rf build/trim-proof-backup
echo ""
if [ $FAILED -eq 0 ]; then
  echo "== PROOF PASSED: all three attacks bit with their own tags, all files restored, final gate green =="
  $GATE 2>&1 | tail -1
else
  echo "== PROOF FAILED: see above =="
  exit 1
fi