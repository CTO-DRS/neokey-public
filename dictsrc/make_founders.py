#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Founders catalog generator — roadmap Phase 2 item B (متجر السمات).
10 founding creators × 25 exclusive packs, glass/neon identity (the roadmap's
«مصممون عرب ينشرون سماتهم بهوية الزجاج النيون»), deterministic (seed 20261103).

Every pack is emitted as CANONICAL spec JSON (sorted keys, no spaces, UTF-8);
its sha256 pins the reviewed bytes (ThemePack.sha256hex over the same bytes).
Contrast pairs (key/text, enter/enterText, special/specialText) are computed
here with the SAME math as ThemePack (alpha-composite over bg + WCAG 2.1) and
asserted >= 3.0 BEFORE the catalog is written — the gate re-proves it in Java.
"""
import json, hashlib, math, os, sys

SEED = 20261103

def lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lum(argb):
    r, g, b = ((argb >> 16) & 255) / 255, ((argb >> 8) & 255) / 255, (argb & 255) / 255
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

def blend(fg, bg):
    a = (fg >> 24) & 255
    if a == 255: return fg | 0xFF000000
    r, g, b = (fg >> 16) & 255, (fg >> 8) & 255, fg & 255
    br, bgc, bb = (bg >> 16) & 255, (bg >> 8) & 255, bg & 255
    return (0xFF000000
            | (((r * a + br * (255 - a)) // 255) & 255) << 16
            | (((g * a + bgc * (255 - a)) // 255) & 255) << 8
            | (((b * a + bb * (255 - a)) // 255) & 255))

def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def hsl(h, s, l, a=0xFF):
    """h 0..360, s/l 0..1 -> AARRGGBB int."""
    c = (1 - abs(2 * l - 1)) * s
    x = c * (1 - abs((h / 60.0) % 2 - 1))
    m = l - c / 2
    rp, gp, bp = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)][int(h // 60) % 6]
    r, g, b = int((rp + m) * 255 + 0.5), int((gp + m) * 255 + 0.5), int((bp + m) * 255 + 0.5)
    return (a << 24) | (min(r, 255) << 16) | (min(g, 255) << 8) | min(b, 255)

def hex6(c):
    return "#%06X" % (c & 0xFFFFFF)

def hex8(c):
    return "#%08X" % (c & 0xFFFFFFFF)

# ---- founders --------------------------------------------------------------
FOUNDERS = [
    ("f01", "استوديو نيون"), ("f02", "زجاج الرياض"), ("f03", "أورورا العربية"),
    ("f04", "حافات داكنة"), ("f05", "مدينة النيون"), ("f06", "قطرة قمر"),
    ("f07", "سديم بارد"), ("f08", "درجات الشام"), ("f09", "ريشة نيون"),
    ("f10", "لؤلؤة الخليج"),
]
COUNTS = [3, 3, 3, 3, 3, 2, 2, 2, 2, 2]          # 25 packs
PRICES = [149, 199, 249, 299, 349, 399, 449, 499]

NAMES = [
    "زجاج نيون بنفسجي", "أورورا سيان", "حافة بنفسجية", "نيون الجدوال", "زجاج ملكي داكن",
    "أزرق عميق", "بنفسج الرمال", "سيان الشبكة", "مدينة نيون ليلية", "زجاج غامق",
    "توهج مزدوج", "نيلي كوني", "أرجواني هادئ", "زجاج الشتاء", "نيون مغربي",
    "سماء نيون", "بنفسجي نيون", "أزرق نيون", "زجاج مصمت", "ليل سديم",
    "كهرمان داكن", "زجاج المحيط", "نيون الفجر", "إندغو عميق", "زجاج اللؤلؤ",
]

# per-pack hue for the neon family (glass identity: dark glass bg + neon glow)
HUES = [276, 190, 265, 300, 250, 210, 28, 185, 320, 240,
        155, 232, 285, 200, 12, 205, 280, 225, 260, 250,
        38, 195, 15, 248, 180]

def build_pack(i, vendor_id):
    hue = HUES[i % len(HUES)]
    bg = hsl((hue + 200) % 360, 0.30, 0.075)                       # near-black glass backdrop, opaque
    key = hsl(hue, 0.22, 0.16, 0xE6)                               # translucent glass key
    pressed = hsl(hue, 0.30, 0.24, 0xE6)
    special = hsl(hue, 0.25, 0.11, 0xE6)
    text = hsl(hue, 0.25, 0.93)                                    # near-white ink
    special_text = hsl(hue, 0.20, 0.80)
    enter = hsl(hue, 0.85, 0.55)                                   # neon accent
    glow = hsl(hue, 0.95, 0.60)
    glow2 = hsl((hue + 55) % 360, 0.95, 0.62)

    # ink for the enter key: pick white or near-black, whichever clears the bar
    cand = [hsl(hue, 0.25, 0.96), hsl(hue, 0.30, 0.10)]
    enter_text = max(cand, key=lambda c: ratio(blend(c, enter), blend(enter, bg)))
    icon = hsl(hue, 0.20, 0.78)
    hint = hsl(hue, 0.15, 0.62)
    popup = hsl((hue + 200) % 360, 0.28, 0.14)
    popup_text = hsl(hue, 0.20, 0.92)
    shadow = 0x66000000
    border = hsl(hue, 0.80, 0.60, 0x66)

    # prove the readability bar HERE, in the generator (same math as ThemePack)
    assert ratio(blend(key, bg), blend(text, bg)) >= 3.0, "key/text contrast %d" % i
    assert ratio(blend(enter, bg), blend(enter_text, bg)) >= 3.0, "enter contrast %d" % i
    assert ratio(blend(special, bg), blend(special_text, bg)) >= 3.0, "special contrast %d" % i

    anim = (i % 31) - 1                                            # -1..29 spread across the catalog
    anim = anim if anim >= 0 else None

    spec = {
        "neokey": 1,
        "app": "NeoKey",
        "vendor": vendor_id,
        "name": NAMES[i],
        "bg": hex6(bg), "bg2": hex6(hsl((hue + 30) % 360, 0.35, 0.10)),
        "key": hex8(key), "keyPressed": hex8(pressed),
        "special": hex8(special), "specialText": hex6(special_text),
        "text": hex6(text), "shadow": hex8(shadow),
        "enter": hex6(enter), "enterText": hex6(enter_text),
        "icon": hex6(icon), "hint": hex6(hint),
        "popup": hex6(popup), "popupText": hex6(popup_text),
        "neon": True, "glass": True, "photo": False,
        "glow": hex6(glow), "glow2": hex6(glow2),
        "border": hex8(border), "borderW": 1 + (i % 3) * 0.5,
        "font": i % 6,
        "layout": {
            "keyshape": i % 4, "radius": 18 + (i % 4) * 6, "keygap": 1 + (i % 3),
            "hpct": 92 + (i % 3) * 6, "wpct": 100, "keysize": i % 3, "pressglow": True,
        },
    }
    if anim is not None:
        spec["anim"] = anim
    return spec

def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "dictsrc/founders_catalog.json"
    idx = 0
    founders_out = []
    total = 0
    for fi, (fid, fname) in enumerate(FOUNDERS):
        packs = []
        for k in range(COUNTS[fi]):
            spec = build_pack(idx, fid)
            canonical = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
            b = canonical.encode("utf-8")
            packs.append({
                "id": "%s-p%02d" % (fid, k + 1),
                "price_cents": PRICES[(idx + fi) % len(PRICES)],
                "spec_json": canonical,
                "sha256": hashlib.sha256(b).hexdigest(),
                "bytes": len(b),
            })
            idx += 1
        total += len(packs)
        founders_out.append({"id": fid, "name": fname, "packs": packs})
    assert total == 25, total
    cat = {"schema": "neokey-founders-1", "seed": SEED, "generated": "2026-11-03",
           "split": {"creator_pct": 70, "platform_pct": 30},
           "founders": founders_out}
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(cat, f, ensure_ascii=False, indent=1)
    print("catalog: %d founders, %d packs -> %s (%d bytes)" %
          (len(FOUNDERS), total, out_path, os.path.getsize(out_path)))

if __name__ == "__main__":
    main()
