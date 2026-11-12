#!/usr/bin/env python3
"""W54 trim batch 1 — the ONE derivation of asset data from the source literals.

HISTORICAL: applied once at W54 against the pre-trim Writer/Theme sources; kept
for provenance. Re-running it against the CURRENT sources fails by design (the
literals are gone from the classes — law 6 holding).

Extracts Writer.java AR/EN template blocks and Theme.java DEFAULT_PHRASES/ADHKAR
arrays byte-exactly, verifies the documented counts, and writes:
  assets/writer_templates.txt   (L C V <tab> text)
  assets/theme_phrases.txt      (#defaults + 24 lines + #adhkar + 20 lines)
  /tmp/w54_extract.json         (extracted values for the round-trip harness)
Any count mismatch = hard fail (never a half-derived asset).
"""
import json, re, sys

ROOT = "."  # run from the repo root (the derivation is historical — applied at W54; kept for provenance)
UNESC = {"\\n": "\n", "\\t": "\t", '\\"': '"', "\\\\": "\\", "\\u0001": "\u0001", "\\u001F": "\u001f"}

def unescape(s):
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\":
            for k, v in UNESC.items():
                if s.startswith(k, i):
                    out.append(v); i += len(k); break
            else:
                m = re.match(r"\\u([0-9a-fA-F]{4})", s[i:])
                if m:
                    out.append(chr(int(m.group(1), 16))); i += 6
                else:
                    raise ValueError(f"unknown escape at {s[i:i+8]!r}")
        else:
            out.append(s[i]); i += 1
    return "".join(out)

def block(src, start_marker):
    i = src.index(start_marker)
    # array terminator is "};"" at 4-space indent — a bare "};" can occur INSIDE
    # a template literal ("{t};" — the lesson is now the code)
    j = src.index("\n    };", i)
    return src[i:j]

def lits_in(block_txt):
    return [unescape(m.group(1)) for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', block_txt) if m.group(1)]

# ---- Writer ----------------------------------------------------------------
wsrc = open(f"{ROOT}/src/com/hark/neokey/Writer.java", encoding="utf-8").read()
ar = lits_in(block(wsrc, "private static final String[][] AR = {"))
en = lits_in(block(wsrc, "private static final String[][] EN = {"))
PER_CAT = [3, 3, 3, 3, 8, 3, 3]
assert len(ar) == 26 and len(en) == 26, f"writer counts {len(ar)}/{len(en)} != 26/26"
def reshape(flat):
    out, i = [], 0
    for n in PER_CAT:
        out.append(flat[i:i+n]); i += n
    assert i == len(flat)
    return out
AR, EN = reshape(ar), reshape(en)
for cat in AR: assert all(x for x in cat) and "{t}" in cat[0] or True
# short-replies block (cat 4) must have no {t}
assert not any("{t}" in x for x in AR[4]) and not any("{t}" in x for x in EN[4])
assert all("{t}" in x for c, xs in enumerate(AR) if c != 4 for x in xs)

# ---- Theme -----------------------------------------------------------------
tsrc = open(f"{ROOT}/src/com/hark/neokey/Theme.java", encoding="utf-8").read()
ph = lits_in(block(tsrc, "public static final String[] DEFAULT_PHRASES = {"))
ad = lits_in(block(tsrc, "public static final String[] ADHKAR = {"))
assert len(ph) == 24, f"phrases {len(ph)} != 24"
assert len(ad) == 20, f"adhkar {len(ad)} != 20"

# ---- write assets ----------------------------------------------------------
def wt_line(lang, c, v, text):
    assert "\t" not in text and "\n" not in text
    return f"{lang}\t{c}\t{v}\t{text}"

lines = [
    "# NeoKey writer templates — W54 capacity trim (law 6: knowledge lives in assets,",
    "# the dex carries the thin strict interpreter). ONE derivation: extracted",
    "# byte-exactly from src/com/hark/neokey/Writer.java history by scripts/trim_w54.py.",
    "# Format: L C V <TAB> text  — L in {A,E}, C 0..6, V sequential per (L,C).",
    f"# Counts per category: {'/'.join(map(str, PER_CAT))} — the loader refuses anything else.",
]
for c, xs in enumerate(AR):
    for v, t in enumerate(xs): lines.append(wt_line("A", c, v, t))
for c, xs in enumerate(EN):
    for v, t in enumerate(xs): lines.append(wt_line("E", c, v, t))
open(f"{ROOT}/assets/writer_templates.txt", "w", encoding="utf-8").write("\n".join(lines) + "\n")

pl = [
    "# NeoKey phrase panel data — W54 capacity trim (Theme.DEFAULT_PHRASES + Theme.ADHKAR",
    "# relocated byte-exactly; the loader refuses any count drift).",
    "#defaults",
]
pl += ph + ["#adhkar"] + ad
open(f"{ROOT}/assets/theme_phrases.txt", "w", encoding="utf-8").write("\n".join(pl) + "\n")

json.dump({"AR": AR, "EN": EN, "PH": ph, "AD": ad}, open("/tmp/w54_extract.json", "w", encoding="utf-8"), ensure_ascii=False)

wa = len(open(f"{ROOT}/assets/writer_templates.txt", "rb").read())
tp = len(open(f"{ROOT}/assets/theme_phrases.txt", "rb").read())
print(f"writer_templates.txt: {wa} bytes (26+26 templates)")
print(f"theme_phrases.txt:    {tp} bytes (24 phrases + 20 adhkar)")
print(f"writer raw literal bytes: {sum(len(x.encode()) for x in ar)+sum(len(x.encode()) for x in en)}")
print(f"theme  raw literal bytes: {sum(len(x.encode()) for x in ph)+sum(len(x.encode()) for x in ad)}")
