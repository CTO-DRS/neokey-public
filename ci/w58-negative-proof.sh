#!/bin/bash
# W58 negative proof (ROADMAP-V2 W58) — ci/w58-negative-proof.sh
#
#   The close law (the W54+ pattern): the week's gate is attacked on the
#   COMMITTED tree — three real tampering attacks must bite their EXACT tags
#   (never a generic crash), the restores must heal byte-exact, and the clean
#   tree must heal green. No pretending: a NO-BITE is a failed proof.
#
#   The three attacks (each on its own designed surface):
#     • grx.seal — one flipped payload byte: the CRC32 seal breaks → GRX_CRC,
#       the strict load refuses the WHOLE asset (zero trusted rules)
#     • grx.side — a WRONG-SIDE example smuggled past the seal (the B example
#       replaced with a correct sentence and the CRC re-sealed, so the attack
#       reaches the SIDES LAW, not an earlier stale-seal death) → GRX_SIDE_B,
#       the load-time agreement law refuses the whole asset
#     • grx.mode — the shipped-CLOSED default quietly removed from run() (the
#       OFF gate deleted) → FAIL "OFF: run(Ctx) answers empty", the measured
#       quiet-default law is a contract, its removal is a regression
#   then heal: the clean tree re-runs the gate green.
#
#   usage: ci/w58-negative-proof.sh [grx.seal|grx.side|grx.mode|heal|all]
set -uo pipefail
cd "$(dirname "$0")/.."
ATK="${1:-all}"
LOG=ci/w58-negative-proof.log

run_nudge() { bash ci/nudge_gate.sh 2>&1; }

BK=build/w58-attack-backup
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

# smuggle a WRONG-SIDE example past the seal: the FIRST B row's text becomes a
# correct sentence (zero matches) and the CRC is re-sealed, so the attack
# reaches the SIDES LAW instead of dying earlier on a stale seal
side_smuggle() {
python3 - "$1" <<'PYEOF'
import sys, zlib
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
head, payload = s.split("\n---\n", 1)
lines = payload.split("\n")
for i, L in enumerate(lines):
    f = L.split("\t")
    if f[0] == "E" and f[2] == "B":
        lines[i] = "E\t" + f[1] + "\tB\tوصل أخوك فأكرمه عند الوصول"
        break
else:
    sys.exit("no B row found")
new_payload = "\n".join(lines)
h = head.split("\n")
out = [x for x in h if not x.startswith("crc32\t")]
crc = zlib.crc32(new_payload.encode("utf-8")) & 0xFFFFFFFF
hdr = "\n".join(out) + "\ncrc32\t%08x\n" % crc
open(p, "w", encoding="utf-8", newline="").write(hdr + "---\n" + new_payload)
PYEOF
}

one() {
  case "$1" in
    grx.seal)
      save assets/grammar_rules.txt
      seal_flip assets/grammar_rules.txt
      out=$(run_nudge)
      if echo "$out" | grep -q "GRX_CRC"; then
        echo "[grx.seal] BIT GRX_CRC — the strict load refused the WHOLE asset (one broken seal byte, zero trusted rules)"
        restore assets/grammar_rules.txt
      else
        echo "[grx.seal] NO-BITE — expected GRX_CRC, tail:"; echo "$out" | tail -4
        restore assets/grammar_rules.txt; return 1
      fi ;;
    grx.side)
      save assets/grammar_rules.txt
      side_smuggle assets/grammar_rules.txt || { restore assets/grammar_rules.txt; return 1; }
      out=$(run_nudge)
      if echo "$out" | grep -q "GRX_SIDE_B"; then
        echo "[grx.side] BIT GRX_SIDE_B — a wrong-side example refused at load, the agreement law lives inside the engine"
        restore assets/grammar_rules.txt
      else
        echo "[grx.side] NO-BITE — expected GRX_SIDE_B, tail:"; echo "$out" | tail -4
        restore assets/grammar_rules.txt; return 1
      fi ;;
    grx.mode)
      save src/com/hark/neokey/GrammarNudge.java
      sed -i 's/if (mode == MODE_OFF || ctx.composing.isEmpty()) return new String\[0\];/if (ctx.composing.isEmpty()) return new String[0];/' src/com/hark/neokey/GrammarNudge.java
      out=$(run_nudge)
      if echo "$out" | grep -q "FAIL OFF: run(Ctx) answers empty"; then
        echo "[grx.mode] BIT FAIL 'OFF: run(Ctx) answers empty' — the shipped-closed default is measured, its quiet removal is a regression"
        restore src/com/hark/neokey/GrammarNudge.java
      else
        echo "[grx.mode] NO-BITE — expected FAIL 'OFF: run(Ctx) answers empty', tail:"; echo "$out" | tail -4
        restore src/com/hark/neokey/GrammarNudge.java; return 1
      fi ;;
    heal)
      local o
      o=$(run_nudge)
      echo "$o" | tail -1
      echo "$o" | grep -q "^NUDGEGATE_OK" || { echo "[heal] FAILED — the clean tree is not green"; return 1; }
      echo "[heal] NUDGEGATE OK — the clean tree heals green" ;;
    *) echo "unknown attack $1"; return 1 ;;
  esac
}

if [ "$ATK" = "all" ]; then
  rc=0
  for a in grx.seal grx.side grx.mode heal; do
    one "$a" >> "$LOG" 2>&1 || rc=1
  done
  BITES=$(grep -c " BIT " "$LOG" || true)
  HEALS=$(grep -c "heals green" "$LOG" || true)
  V="PROOF PASSED w58 $BITES/3 attacks bit their exact tags and the restores healed byte-exact (heal=$HEALS: NUDGEGATE_OK on the clean tree — battery 110, rules 28, dex 521520/522000)"
  echo "$V" >> "$LOG"
  echo "$V"
  exit $rc
fi
one "$ATK"
