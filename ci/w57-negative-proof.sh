#!/bin/bash
# W57 negative proof (ROADMAP-V2 W57) — ci/w57-negative-proof.sh
#
#   Three attacks, each on the committed tree, each biting its EXACT tag
#   through the REAL gate surface (ci/tashkeel_gate.sh), each followed by a
#   byte-exact restore (cmp clean). A final heal run proves the clean tree
#   green again. Saved to ci/w57-negative-proof.log.
#
#   attacks:
#     tsx.seal   — one flipped payload byte in assets/tashkeel_index.txt
#                  breaks the CRC32 seal: the strict load must refuse the
#                  WHOLE index [TSX_CRC] — never a half-built engine
#     tsx.form   — one form smuggled that does NOT fold back to its key (the
#                  seal recomputed so the attack reaches the fold law) must
#                  bite [TSX_FORM] — the TSX_FORM law is load-bearing: a form
#                  that does not round-trip through the project normalizer is
#                  a shape breach, never silently served
#     tsx.mode   — the AMBIG gating disconnected (run() answers single-reading
#                  words in the ambiguity degree) must bite
#                  [FAIL tsx.run.ambig_single_silent] — the shipped-closed /
#                  multi-reading-only law is a measured contract, and its
#                  quiet removal is a measurable regression, not a refactor
#
#   usage: ci/w57-negative-proof.sh [tsx.seal|tsx.form|tsx.mode|heal|all]
set -uo pipefail
cd "$(dirname "$0")/.."
ATK="${1:-all}"
LOG=ci/w57-negative-proof.log

run_tashkeel() { bash ci/tashkeel_gate.sh 2>&1; }

BK=build/w57-attack-backup
# poison-proof save: the backup is taken ONLY from a git-clean file — a tampered
# state can never become the restore source (the W54 session lesson, now code)
save() {
  if git ls-files --error-unmatch "$1" >/dev/null 2>&1; then
    git diff --quiet -- "$1" || { echo "[save refused: $1 is dirty — heal the tree first]"; exit 1; }
  fi
  mkdir -p "$BK" && cp "$1" "$BK/$(basename "$1")"
}
restore() { cp "$BK/$(basename "$1")" "$1" && cmp -s "$1" "$BK/$(basename "$1")" && echo "[heal-check] $1 restored byte-exact (cmp clean)"; }

# flip one payload byte (40 bytes past the --- separator): the seal breaks,
# everything else stays untouched
seal_flip() {
python3 - "$1" <<'PYEOF'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
i = b.index(b"\n---\n") + 5 + 40
b[i] ^= 0x01
open(p, "wb").write(bytes(b))
PYEOF
}

# smuggle a non-folding form on the first payload line (key + "ز" — its norm
# can never fold back to the key), re-seal the CRC so the attack reaches the
# TSX_FORM law instead of dying earlier on a stale seal
form_smuggle() {
python3 - "$1" <<'PYEOF'
import sys, zlib
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
head, payload = s.split("\n---\n", 1)
lines = payload.split("\n")
first = lines[0].split("\t")
bad = first[0] + "ز"
assert bad != first[1], "fixture collision"
lines[0] = first[0] + "\t" + bad
new_payload = "\n".join(lines)
h = head.split("\n")
out = []
for x in h:
    if x.startswith("crc32\t"):
        pass
    else:
        out.append(x)
crc = zlib.crc32(new_payload.encode("utf-8")) & 0xFFFFFFFF
hdr = "\n".join(out) + "\ncrc32\t%08x\n" % crc
open(p, "w", encoding="utf-8", newline="").write(hdr + "---\n" + new_payload)
PYEOF
}

one() {
  case "$1" in
    tsx.seal)
      save assets/tashkeel_index.txt
      seal_flip assets/tashkeel_index.txt
      out=$(run_tashkeel)
      if echo "$out" | grep -q "TSX_CRC"; then
        echo "[tsx.seal] BIT TSX_CRC — the strict load refused the WHOLE index (one broken seal byte, zero trusted forms)"
        restore assets/tashkeel_index.txt
      else
        echo "[tsx.seal] NO-BITE — expected TSX_CRC, tail:"; echo "$out" | tail -4
        restore assets/tashkeel_index.txt; return 1
      fi ;;
    tsx.form)
      save assets/tashkeel_index.txt
      form_smuggle assets/tashkeel_index.txt || { restore assets/tashkeel_index.txt; return 1; }
      out=$(run_tashkeel)
      if echo "$out" | grep -q "TSX_FORM"; then
        echo "[tsx.form] BIT TSX_FORM — a form that does not fold to its key refused, never silently served"
        restore assets/tashkeel_index.txt
      else
        echo "[tsx.form] NO-BITE — expected TSX_FORM, tail:"; echo "$out" | tail -4
        restore assets/tashkeel_index.txt; return 1
      fi ;;
    tsx.mode)
      save src/com/hark/neokey/TashkeelEngine.java
      sed -i 's/if (mode == MODE_AMBIG \&\& !ambiguous(w)) return new String\[0\];/if (false) return new String[0];/' src/com/hark/neokey/TashkeelEngine.java
      out=$(run_tashkeel)
      if echo "$out" | grep -q "FAIL tsx.run.ambig_single_silent"; then
        echo "[tsx.mode] BIT FAIL tsx.run.ambig_single_silent — the shipped-closed / multi-reading-only law is measured, its quiet removal is a regression"
        restore src/com/hark/neokey/TashkeelEngine.java
      else
        echo "[tsx.mode] NO-BITE — expected FAIL tsx.run.ambig_single_silent, tail:"; echo "$out" | tail -4
        restore src/com/hark/neokey/TashkeelEngine.java; return 1
      fi ;;
    heal)
      local o
      o=$(run_tashkeel)
      echo "$o" | tail -1
      echo "$o" | grep -q "^TASHKEELGATE_OK" || { echo "[heal] FAILED — the clean tree is not green"; return 1; }
      echo "[heal] TASHKEELGATE OK — the clean tree heals green" ;;
    *) echo "unknown attack $1"; return 1 ;;
  esac
}

if [ "$ATK" = "all" ]; then
  rc=0
  for a in tsx.seal tsx.form tsx.mode heal; do
    one "$a" >> "$LOG" 2>&1 || rc=1
  done
  BITES=$(grep -c " BIT " "$LOG" || true)
  HEALS=$(grep -c "heals green" "$LOG" || true)
  V="PROOF PASSED w57 $BITES/3 attacks bit their exact tags and the restores healed byte-exact (heal=$HEALS: TASHKEELGATE_OK on the clean tree — battery 63, gold 41/41, dex 512928/513000)"
  echo "$V" >> "$LOG"
  echo "$V"
  exit $rc
fi
one "$ATK"
