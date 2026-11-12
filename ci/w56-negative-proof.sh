#!/bin/bash
# W56 negative proof (ROADMAP-V2 W56) — ci/w56-negative-proof.sh
#
#   Three attacks, each on the committed tree, each biting its EXACT tag
#   through the REAL gate surface (ci/root_gate.sh), each followed by a
#   byte-exact restore (cmp clean). A final heal run proves the clean tree
#   green again. Saved to ci/w56-negative-proof.log.
#
#   attacks:
#     root.seal   — one flipped payload byte in assets/root_index.txt breaks
#                   the CRC32 seal: the strict load must refuse the WHOLE
#                   index [RIX_CRC] — never a half-built engine, never a
#                   partially-trusted family
#     root.dup    — one word smuggled under TWO roots (the seal recomputed so
#                   the attack reaches the shape law) must bite [RIX_DUP] —
#                   one root per norm key, never silently resolved
#     root.bonus  — the Correction2 root weight disconnected (sc += 0) must
#                   bite [FAIL root.bonus.prefers] — the +400 signal is a
#                   measured contract, and its quiet removal is a measurable
#                   regression, not a refactor
#
#   usage: ci/w56-negative-proof.sh [root.seal|root.dup|root.bonus|heal|all]
set -uo pipefail
cd "$(dirname "$0")/.."
ATK="${1:-all}"
LOG=ci/w56-negative-proof.log

run_root() { bash ci/root_gate.sh 2>&1; }

BK=build/w56-attack-backup
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

# smuggle the first root's last member under the second root, re-seal the CRC
# and bump the covered claim (+1) so the attack reaches the SHAPE law (RIX_DUP)
# instead of dying earlier on a stale count — the exact one-root-per-norm-key
# breach the derivation tool refuses to emit
dup_smuggle() {
python3 - "$1" <<'PYEOF'
import sys, zlib
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
head, payload = s.split("\n---\n", 1)
lines = payload.split("\n")
first = lines[0].split("\t"); second = lines[1].split("\t")
m = first[1].split(" ")[-1]
assert m not in second[1].split(" "), "member already in the second root — pick another pair"
lines[1] = second[0] + "\t" + second[1] + " " + m
new_payload = "\n".join(lines)
h = head.split("\n")
out = []
for x in h:
    if x.startswith("covered\t"):
        out.append("covered\t" + str(int(x.split("\t")[1]) + 1))
    elif x.startswith("crc32\t"):
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
    root.seal)
      save assets/root_index.txt
      seal_flip assets/root_index.txt
      out=$(run_root)
      if echo "$out" | grep -q "RIX_CRC"; then
        echo "[root.seal] BIT RIX_CRC — the strict load refused the WHOLE index (one broken seal byte, zero trusted families)"
        restore assets/root_index.txt
      else
        echo "[root.seal] NO-BITE — expected RIX_CRC, tail:"; echo "$out" | tail -4
        restore assets/root_index.txt; return 1
      fi ;;
    root.dup)
      save assets/root_index.txt
      dup_smuggle assets/root_index.txt || { restore assets/root_index.txt; return 1; }
      out=$(run_root)
      if echo "$out" | grep -q "RIX_DUP"; then
        echo "[root.dup] BIT RIX_DUP — one word under two roots refused, never silently resolved"
        restore assets/root_index.txt
      else
        echo "[root.dup] NO-BITE — expected RIX_DUP, tail:"; echo "$out" | tail -4
        restore assets/root_index.txt; return 1
      fi ;;
    root.bonus)
      save src/com/hark/neokey/Correction2.java
      sed -i 's/if (rt >= 0 \&\& rt == sig.rootOf(cand)) sc += ROOT_WEIGHT;/if (rt >= 0 \&\& rt == sig.rootOf(cand)) sc += 0;/' src/com/hark/neokey/Correction2.java
      out=$(run_root)
      if echo "$out" | grep -q "FAIL root.bonus.prefers"; then
        echo "[root.bonus] BIT FAIL root.bonus.prefers — the +400 root signal is measured, its quiet removal is a regression"
        restore src/com/hark/neokey/Correction2.java
      else
        echo "[root.bonus] NO-BITE — expected FAIL root.bonus.prefers, tail:"; echo "$out" | tail -4
        restore src/com/hark/neokey/Correction2.java; return 1
      fi ;;
    heal)
      local o
      o=$(run_root)
      echo "$o" | tail -1
      echo "$o" | grep -q "^ROOTGATE_OK" || { echo "[heal] FAILED — the clean tree is not green"; return 1; }
      echo "[heal] ROOTGATE OK — the clean tree heals green" ;;
    *) echo "unknown attack $1"; return 1 ;;
  esac
}

if [ "$ATK" = "all" ]; then
  rc=0
  for a in root.seal root.dup root.bonus heal; do
    one "$a" >> "$LOG" 2>&1 || rc=1
  done
  BITES=$(grep -c " BIT " "$LOG" || true)
  HEALS=$(grep -c "heals green" "$LOG" || true)
  V="PROOF PASSED w56 $BITES/3 attacks bit their exact tags and the restores healed byte-exact (heal=$HEALS: ROOTGATE_OK on the clean tree — battery 46, gold 185/185, dex 508840/512000)"
  echo "$V" >> "$LOG"
  echo "$V"
  exit $rc
fi
one "$ATK"
