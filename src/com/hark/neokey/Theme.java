package com.hark.neokey;

import android.content.Context;
import android.content.SharedPreferences;

/** Keyboard themes + saved preferences. */
public final class Theme {
    public static final String PREFS = "neokey";

    public final String name;
    public final int bg, key, keyPressed, text, special, specialText, shadow,
            enter, enterText, icon, hint, popup, popupText;
    public final boolean neon;
    public final int glow, glow2;
    public int bg2;          // gradient end (0 = flat)
    public boolean photo;    // user picture as background
    public int space;        // space bar colour (0 = same as letter keys)
    public int border;       // key border colour (0 = none)
    public float borderW = 1f; // key border width in dp
    public int font;         // index into FONTS
    public boolean glass;    // light 3D glass keys
    public boolean hyper;    // v21 futuristic glass: gradient neon edges (glow -> glow2), glowing enter, glass toolbar pill
    public String cat;       // category label (optional)
    public int anim = -1;    // animated background (Backdrops.drawAnim index), -1 = static
    public String gen = "";  // W69: seed phrase — "" = generator off (law 8: closed by default)

    Theme(String name, int bg, int key, int keyPressed, int text, int special, int specialText,
          int shadow, int enter, int enterText, int icon, int hint, int popup, int popupText,
          boolean neon, int glow, int glow2) {
        this.name = name; this.bg = bg; this.key = key; this.keyPressed = keyPressed; this.text = text;
        this.special = special; this.specialText = specialText; this.shadow = shadow;
        this.enter = enter; this.enterText = enterText; this.icon = icon; this.hint = hint;
        this.popup = popup; this.popupText = popupText; this.neon = neon; this.glow = glow; this.glow2 = glow2;
    }

    Theme grad(int end) { bg2 = end; return this; }
    Theme pic() { photo = true; return this; }
    Theme glass() { glass = true; return this; }
    Theme hyper() { hyper = true; return this; }
    Theme anim(int i) { anim = i; return this; }
    Theme border(int col, float w) { border = col; borderW = w; return this; }
    Theme font(int f) { font = f; return this; }
    Theme space(int col) { space = col; return this; }

    /** Full copy, optionally with a different enter colour (accent). */
    Theme copy(int enterCol, int enterTextCol, boolean recolorGlow) {
        Theme n = new Theme(name, bg, key, keyPressed, text, special, specialText, shadow, enterCol, enterTextCol,
                icon, hint, popup, popupText, neon, neon && recolorGlow ? enterCol : glow, neon && recolorGlow ? enterCol : glow2);
        n.bg2 = bg2; n.photo = photo; n.space = space; n.border = border; n.borderW = borderW;
        n.font = font; n.glass = glass; n.cat = cat; n.hyper = hyper; n.anim = anim;
        return n;
    }

    /** Builds a complete theme from a few base colours; the rest are derived. */
    static Theme make(String name, int bg, int bg2, int key, int special, int text, int enter) {
        int solidBg = bg | 0xFF000000;
        int solidKey = (key >>> 24) < 0xCC ? blend(solidBg, key | 0xFF000000, (key >>> 24) / 255f) : key;
        if (special == 0) special = (key >>> 24) < 0xCC ? withAlpha(key, Math.max(0x10, (key >>> 24) * 2 / 3)) : blend(key, bg, 0.45f);
        int pressed = (key >>> 24) < 0xCC ? withAlpha(key, Math.min(0xFF, (key >>> 24) * 2 + 0x20)) : blend(key, text, 0.22f) | 0xFF000000;
        Theme t = new Theme(name, bg, key, pressed, text, special, text, light(solidBg) ? 0x38000000 : 0x66000000,
                enter, light(enter) ? 0xFF111111 : 0xFFFFFFFF, blend(text, solidKey, 0.3f),
                blend(text, solidKey, 0.45f), solidKey, text, false, 0, 0);
        t.bg2 = bg2;
        return t;
    }

    static Theme neonOf(String name, int bg, int key, int text, int g1, int g2) {
        Theme t = new Theme(name, bg, key, withAlpha(key, Math.min(0xFF, (key >>> 24) * 2)), text, withAlpha(g2, 0x2E), text,
                0, g1, 0xFF05060F, blend(text, bg, 0.3f), blend(text, bg, 0.45f), 0xF2141730, 0xFFFFFFFF, true, g1, g2);
        return t;
    }

    public static final String[] FONTS = {"افتراضي", "متوسط", "رفيع", "مكثّف", "كلاسيكي", "برمجي"};

    public static android.graphics.Typeface face(int font, int style) {
        String fam;
        switch (font) {
            case 1: fam = "sans-serif-medium"; break;
            case 2: fam = "sans-serif-light"; break;
            case 3: fam = "sans-serif-condensed"; break;
            case 4: fam = "serif"; break;
            case 5: fam = "monospace"; break;
            default: fam = "sans-serif";
        }
        return android.graphics.Typeface.create(fam, style);
    }

    public static final Theme[] ALL = {
            new Theme("فاتح", 0xFFD2D6DD, 0xFFFFFFFF, 0xFFC3C9D2, 0xFF14161B, 0xFFFFFFFF, 0xFF14161B,
                    0x40000000, 0xFF1E6FE8, 0xFFFFFFFF, 0xFF4B505A, 0xFF7A808B, 0xFFFFFFFF, 0xFF14161B,
                    false, 0, 0),
            new Theme("داكن", 0xFF1B1C20, 0xFF3B3D44, 0xFF575A62, 0xFFF3F4F6, 0xFF2C2E34, 0xFFF3F4F6,
                    0x66000000, 0xFF2F7BF0, 0xFFFFFFFF, 0xFFB7BBC4, 0xFF9AA0AA, 0xFF4A4D55, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("ليلي", 0xFF0E1830, 0xFF1F2D50, 0xFF33467A, 0xFFEAF0FF, 0xFF16223F, 0xFFEAF0FF,
                    0x66000000, 0xFF3D8BFF, 0xFFFFFFFF, 0xFFA9B8DD, 0xFF8796BD, 0xFF2A3C68, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("أخضر", 0xFF0F3A1D, 0xFF1D6531, 0xFF2C8545, 0xFFFFFFFF, 0xFF154D26, 0xFFFFFFFF,
                    0x55000000, 0xFF4CD06D, 0xFF06210F, 0xFFBFE6C9, 0xFFA5D6B1, 0xFF25763B, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("وردي", 0xFFF6DCE6, 0xFFFFFFFF, 0xFFF0C3D4, 0xFF3A1224, 0xFFFBEEF3, 0xFF3A1224,
                    0x33A0305A, 0xFFE0457B, 0xFFFFFFFF, 0xFF8C4A64, 0xFFB07A90, 0xFFFFFFFF, 0xFF3A1224,
                    false, 0, 0),
            new Theme("نيون 2036", 0xFF070814, 0x24FFFFFF, 0x44FFFFFF, 0xFFF2F4FF, 0x2E00E5FF, 0xFFF2F4FF,
                    0x00000000, 0xFF00E5FF, 0xFF05060F, 0xFF9FB3D9, 0xFF7C8DB5, 0xF2141730, 0xFFFFFFFF,
                    true, 0xFF00E5FF, 0xFF7C4DFF),
            new Theme("غروب", 0xFFFF7E5F, 0x33FFFFFF, 0x55FFFFFF, 0xFFFFFFFF, 0x22FFFFFF, 0xFFFFFFFF,
                    0x22000000, 0xFFFFC371, 0xFF4A1942, 0xFFFFE6DA, 0xFFFFE0D0, 0xFF7B3F7A, 0xFFFFFFFF,
                    false, 0, 0).grad(0xFF6A3093),
            new Theme("مجرة", 0xFF2B1055, 0x2EFFFFFF, 0x4DFFFFFF, 0xFFFFFFFF, 0x1FFFFFFF, 0xFFFFFFFF,
                    0x33000000, 0xFF8E7CFF, 0xFFFFFFFF, 0xFFD5CCFF, 0xFFC4B8F5, 0xFF3D2A7A, 0xFFFFFFFF,
                    false, 0, 0).grad(0xFF7597DE),
            new Theme("ذهبي", 0xFF0B0B0C, 0xFF1C1C1F, 0xFF34322B, 0xFFEBCB7A, 0xFF141416, 0xFFEBCB7A,
                    0x66000000, 0xFFC9A227, 0xFF141210, 0xFFC9A95A, 0xFF8F7A45, 0xFF2A2720, 0xFFEBCB7A,
                    false, 0, 0),
            new Theme("سماوي", 0xFFDDEBFA, 0xFFFFFFFF, 0xFFC7DDF5, 0xFF0F2A47, 0xFFF1F7FE, 0xFF0F2A47,
                    0x332B5C8F, 0xFF0EA5E9, 0xFFFFFFFF, 0xFF3B6A96, 0xFF7C9CBD, 0xFFFFFFFF, 0xFF0F2A47,
                    false, 0, 0),
            new Theme("صورتي", 0xFF101218, 0x38FFFFFF, 0x66FFFFFF, 0xFFFFFFFF, 0x26FFFFFF, 0xFFFFFFFF,
                    0x00000000, 0xFF2F7BF0, 0xFFFFFFFF, 0xFFFFFFFF, 0xDDFFFFFF, 0xF0222630, 0xFFFFFFFF,
                    false, 0, 0).pic(),
            new Theme("أسود AMOLED", 0xFF000000, 0xFF161616, 0xFF2E2E2E, 0xFFFFFFFF, 0xFF0C0C0C, 0xFFFFFFFF,
                    0x00000000, 0xFF2F7BF0, 0xFFFFFFFF, 0xFFA3A3A3, 0xFF808080, 0xFF262626, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("سعودي", 0xFF00512E, 0xFF006C3B, 0xFF0B8A50, 0xFFFFFFFF, 0xFF005F33, 0xFFFFFFFF,
                    0x55000000, 0xFFFFFFFF, 0xFF006C3B, 0xFFCDEBD9, 0xFFA7D8BB, 0xFF0B7A45, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("عنابي", 0xFF3B0A1A, 0xFF5C1229, 0xFF7A1D39, 0xFFFDECEF, 0xFF4A0E21, 0xFFFDECEF,
                    0x66000000, 0xFFE8B04B, 0xFF2A0712, 0xFFE7B9C5, 0xFFC995A3, 0xFF6B1832, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("قهوة", 0xFF2E1F17, 0xFF4A3326, 0xFF654636, 0xFFF5E6D3, 0xFF3B281E, 0xFFF5E6D3,
                    0x66000000, 0xFFC08552, 0xFFFFFFFF, 0xFFD9BFA5, 0xFFB79C82, 0xFF5A3E2F, 0xFFF5E6D3,
                    false, 0, 0),
            new Theme("رملي", 0xFFEADBC4, 0xFFFFF9F0, 0xFFE5D3B5, 0xFF4A3520, 0xFFF6EBDA, 0xFF4A3520,
                    0x339C7A4F, 0xFFB7793D, 0xFFFFFFFF, 0xFF8A6D4E, 0xFFA88E70, 0xFFFFF9F0, 0xFF4A3520,
                    false, 0, 0),
            new Theme("بحري", 0xFF0A2540, 0x2EFFFFFF, 0x4DFFFFFF, 0xFFFFFFFF, 0x1FFFFFFF, 0xFFFFFFFF,
                    0x33000000, 0xFF2DD4BF, 0xFF042F2E, 0xFFBDE7F0, 0xFF9CCFDB, 0xFF12395E, 0xFFFFFFFF,
                    false, 0, 0).grad(0xFF0E7490),
            new Theme("بنفسجي", 0xFF1E1035, 0xFF35205C, 0xFF4B2F80, 0xFFF3EEFF, 0xFF2A1748, 0xFFF3EEFF,
                    0x66000000, 0xFFA78BFA, 0xFF1E1035, 0xFFCBB8F5, 0xFFA996D6, 0xFF432A72, 0xFFFFFFFF,
                    false, 0, 0),
            new Theme("ليموني", 0xFFF4F9E3, 0xFFFFFFFF, 0xFFE3EFC0, 0xFF1F2A0B, 0xFFF9FCEF, 0xFF1F2A0B,
                    0x33617A1E, 0xFF84CC16, 0xFF1A2E05, 0xFF5B6B33, 0xFF8A9A62, 0xFFFFFFFF, 0xFF1F2A0B,
                    false, 0, 0),
            new Theme("سايبر", 0xFF0A0014, 0x22FF2BD6, 0x44FF2BD6, 0xFFFFE9FB, 0x2200FFC6, 0xFFFFE9FB,
                    0x00000000, 0xFFFF2BD6, 0xFF0A0014, 0xFFE0A8D8, 0xFFB57CAE, 0xF21A0630, 0xFFFFFFFF,
                    true, 0xFFFF2BD6, 0xFF00FFC6),
            new Theme("شفق", 0xFF0F2027, 0x2EFFFFFF, 0x4DFFFFFF, 0xFFFFFFFF, 0x1FFFFFFF, 0xFFFFFFFF,
                    0x33000000, 0xFF34D399, 0xFF052E1C, 0xFFC9F2E3, 0xFFA5DCC8, 0xFF1D4044, 0xFFFFFFFF,
                    false, 0, 0).grad(0xFF2C5364),
            new Theme("مخصص", 0xFF1B1C20, 0xFF3B3D44, 0xFF575A62, 0xFFFFFFFF, 0xFF2C2E34, 0xFFFFFFFF,
                    0x66000000, 0xFFE11D48, 0xFFFFFFFF, 0xFFB7BBC4, 0xFF9AA0AA, 0xFF4A4D55, 0xFFFFFFFF,
                    false, 0, 0),
            // ---- v16 collection (indexes 22+)
            make("زجاج فاتح", 0xFFE6ECF5, 0xFFC7D3EA, 0xA6FFFFFF, 0x66FFFFFF, 0xFF111827, 0xFF2563EB).glass(),
            make("زجاج داكن", 0xFF0F172A, 0xFF1E293B, 0x29FFFFFF, 0x17FFFFFF, 0xFFFFFFFF, 0xFF38BDF8).glass(),
            make("كريستال وردي", 0xFFFBCFE8, 0xFFC4B5FD, 0x80FFFFFF, 0x4DFFFFFF, 0xFF3B0764, 0xFFDB2777).glass(),
            make("محيط زجاجي", 0xFF0369A1, 0xFF0F766E, 0x2EFFFFFF, 0x1AFFFFFF, 0xFFFFFFFF, 0xFFFDE047).glass(),
            make("ثلجي", 0xFFEAF6FF, 0xFFFFFFFF, 0xCCFFFFFF, 0x99E0F0FF, 0xFF0B3954, 0xFF3FA7D6).glass().border(0x330B3954, 0.8f),
            make("ماتريكس", 0xFF000A00, 0, 0xFF001A08, 0xFF00110A, 0xFF22FF66, 0xFF16A34A).border(0x5522FF66, 1f).font(5),
            make("محرر الأكواد", 0xFF1E1E1E, 0, 0xFF2D2D30, 0xFF252526, 0xFFD4D4D4, 0xFF007ACC).font(5).space(0xFF37373D),
            make("دراكولا", 0xFF282A36, 0, 0xFF44475A, 0xFF343746, 0xFFF8F8F2, 0xFFBD93F9).font(5),
            make("نورد", 0xFF2E3440, 0, 0xFF3B4252, 0xFF353B48, 0xFFECEFF4, 0xFF88C0D0),
            make("سولارايز", 0xFFEEE8D5, 0, 0xFFFDF6E3, 0xFFE4DDC8, 0xFF586E75, 0xFF268BD2).font(5),
            neonOf("ألعاب RGB", 0xFF0B0B12, 0x26FFFFFF, 0xFFFFFFFF, 0xFFFF0055, 0xFF00E5FF).border(0x66FF0055, 1.2f).font(1),
            make("ورق كلاسيكي", 0xFFF3EFE6, 0, 0xFFFFFDF8, 0xFFEDE6D8, 0xFF2B2B2B, 0xFF8B5E34).font(4).border(0x22000000, 0.8f),
            make("حدود فقط", 0xFF111214, 0, 0x00FFFFFF, 0x0AFFFFFF, 0xFFFFFFFF, 0xFFF43F5E).border(0x66FFFFFF, 1.2f),
            make("خوخي", 0xFFFFE4D6, 0xFFFECACA, 0xFFFFFFFF, 0xFFFFF1EA, 0xFF7C2D12, 0xFFFB7185),
            make("غابة", 0xFF1A2E1A, 0xFF2D4A22, 0xFF2F4A2C, 0xFF24391F, 0xFFE8F5E0, 0xFFA3E635),
            make("ملكي", 0xFF12081F, 0xFF3B0A45, 0xFF24123A, 0xFF1A0D2C, 0xFFF5D68A, 0xFFD4AF37).font(4).border(0x40F5D68A, 0.9f),
            neonOf("زجاج نيون 2036", 0xFF05061A, 0x1C9F8CFF, 0xFFF5F3FF, 0xFFA855F7, 0xFF22D3EE).grad(0xFF0E0A2E).hyper(),
            // ---- v21.5 animated themes (background moves on the keyboard)
            neonOf("شفق حي", 0xFF0B0820, 0x22FFFFFF, 0xFFFFFFFF, 0xFFA855F7, 0xFF22D3EE).anim(0),
            neonOf("سديم", 0xFF0A0418, 0x22FFFFFF, 0xFFFFFFFF, 0xFFC026D3, 0xFF6366F1).anim(1),
            make("نجوم الليل", 0xFF020617, 0, 0x26FFFFFF, 0x14FFFFFF, 0xFFFFFFFF, 0xFF6366F1).glass().anim(2),
            make("موج البحر", 0xFF075985, 0, 0x2EFFFFFF, 0x1AFFFFFF, 0xFFFFFFFF, 0xFF38BDF8).glass().anim(3),
            neonOf("سينث ويف", 0xFF0B0320, 0x1FFFFFFF, 0xFFFFFFFF, 0xFFF472B6, 0xFF22D3EE).anim(4),
            neonOf("فقاعات", 0xFF0F0A24, 0x22FFFFFF, 0xFFFFFFFF, 0xFF7C3AED, 0xFFFBBF24).anim(5),
            neonOf("RGB حي", 0xFF101010, 0x2A000000, 0xFFFFFFFF, 0xFFFF0055, 0xFF00E5FF).border(0x55FFFFFF, 1f).anim(6),
            make("ماتريكس حي", 0xFF000A04, 0, 0xCC001A08, 0xCC00110A, 0xFF22FF66, 0xFF16A34A).border(0x5522FF66, 1f).font(5).anim(7),
            neonOf("غروب 2036", 0xFF1E0B3A, 0x26000000, 0xFFFFFFFF, 0xFFF0ABFC, 0xFFFDE68A).anim(8),
            make("حرير", 0xFF3B0764, 0, 0x26FFFFFF, 0x14FFFFFF, 0xFFFFFFFF, 0xFFE9D5FF).glass().anim(9),
            make("شهب", 0xFF030712, 0, 0x22FFFFFF, 0x12FFFFFF, 0xFFFFFFFF, 0xFF93C5FD).glass().anim(10),
            neonOf("جمر", 0xFF0C0A09, 0x26000000, 0xFFFFF7ED, 0xFFF97316, 0xFFFDE047).anim(11),
            make("ثلج الليل", 0xFF1E3A5F, 0, 0x2EFFFFFF, 0x1AFFFFFF, 0xFFFFFFFF, 0xFF7DD3FC).glass().anim(12),
            neonOf("مجرة", 0xFF05030F, 0x1FFFFFFF, 0xFFFFFFFF, 0xFF7C3AED, 0xFFFDE68A).anim(13),
            make("نبض", 0xFF020617, 0, 0xCC0B1120, 0xCC060A16, 0xFF22FF88, 0xFF16A34A).border(0x5522FF88, 1f).font(5).anim(14),
            make("قوس قزح", 0xFF222222, 0, 0x33000000, 0x22000000, 0xFFFFFFFF, 0xFFFFFFFF).border(0x44FFFFFF, 1f).anim(15),
            neonOf("تموّجات", 0xFF0B1026, 0x22FFFFFF, 0xFFFFFFFF, 0xFF22D3EE, 0xFF6366F1).anim(16),
            neonOf("لافا", 0xFF1A0505, 0x2A000000, 0xFFFFFFFF, 0xFFF97316, 0xFFDC2626).anim(17),
            neonOf("سيبراني", 0xFF020617, 0x1A22D3EE, 0xFFE0F2FE, 0xFF22D3EE, 0xFF0EA5E9).border(0x4422D3EE, 1f).font(5).anim(18),
            make("عاصفة", 0xFF111827, 0, 0x26FFFFFF, 0x14FFFFFF, 0xFFFFFFFF, 0xFF60A5FA).glass().anim(19),
            neonOf("ألعاب نارية", 0xFF020617, 0x1FFFFFFF, 0xFFFFFFFF, 0xFFF472B6, 0xFFFDE047).anim(20),
            make("يراعات", 0xFF0B2A1A, 0, 0x22FFFFFF, 0x12FFFFFF, 0xFFECFCCB, 0xFF84CC16).glass().anim(21),
            neonOf("معادل صوت", 0xFF0B0320, 0x26000000, 0xFFFFFFFF, 0xFF22D3EE, 0xFFF472B6).anim(22),
            make("خريف", 0xFF7C2D12, 0, 0x33000000, 0x22000000, 0xFFFFF7ED, 0xFFF59E0B).anim(23),
            neonOf("كوكبة", 0xFF020617, 0x1AFFFFFF, 0xFFFFFFFF, 0xFF93C5FD, 0xFF6366F1).anim(24),
            neonOf("نفق", 0xFF05030F, 0x22000000, 0xFFFFFFFF, 0xFFA855F7, 0xFF22D3EE).anim(25),
            neonOf("إطارات نيون", 0xFF0A0A12, 0x1AFFFFFF, 0xFFFFFFFF, 0xFFF472B6, 0xFF22D3EE).border(0x44F472B6, 1f).anim(26),
            make("ضباب", 0xFF374151, 0, 0x2EFFFFFF, 0x1AFFFFFF, 0xFFFFFFFF, 0xFF9CA3AF).glass().anim(27),
            make("صحراء", 0xFFF97316, 0, 0x33000000, 0x22000000, 0xFFFFFBEB, 0xFF7C2D12).anim(28),
            make("هلال", 0xFF0C1445, 0, 0x22FFFFFF, 0x12FFFFFF, 0xFFFEF3C7, 0xFFFDE68A).glass().font(4).anim(29),
    };

    public static final int CUSTOM_INDEX = 21;
    public static final int HYPER_INDEX = hyperIndex();
    private static int hyperIndex() { for (int i = 0; i < ALL.length; i++) if (ALL[i].hyper) return i; return 0; }
    /** Library (user-saved / imported) themes live at LIB_BASE + position in the "lib" pref. */
    public static final int LIB_BASE = 1000;

    public static final int[] C_BG = {0xFF0B0B0C, 0xFF1B1C20, 0xFF0E1830, 0xFF0F3A1D, 0xFF3B0A2A, 0xFF2B1055,
            0xFF134E4A, 0xFF7C2D12, 0xFFD2D6DD, 0xFFF6DCE6, 0xFFDDEBFA, 0xFFFDF6E3};
    public static final int[] C_KEY = {0xFF3B3D44, 0xFFFFFFFF, 0xFF111827, 0xFF1F2D50, 0xFF1D6531, 0xFF6D28D9,
            0xFF0F766E, 0xFF9A3412, 0xFFFDE68A, 0xFFFBCFE8, 0x33FFFFFF, 0x22000000};
    public static final int[] PALETTE = {0xFFFFFFFF, 0xFFF3F4F6, 0xFFD1D5DB, 0xFF9CA3AF, 0xFF4B5563, 0xFF1F2937, 0xFF111827, 0xFF000000,
            0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFEAB308, 0xFF84CC16, 0xFF22C55E, 0xFF10B981, 0xFF14B8A6,
            0xFF06B6D4, 0xFF0EA5E9, 0xFF3B82F6, 0xFF6366F1, 0xFF8B5CF6, 0xFFA855F7, 0xFFD946EF, 0xFFEC4899,
            0xFFF43F5E, 0xFF7C2D12, 0xFF14532D, 0xFF1E3A8A, 0xFF4C1D95, 0xFFC9A227, 0xFFF5E6D3, 0xFF006C3B,
            0x33FFFFFF, 0x66FFFFFF, 0x22000000, 0x00FFFFFF};
    public static final int[] C_TEXT = {0xFFFFFFFF, 0xFF14161B, 0xFFEBCB7A, 0xFF00E5FF, 0xFFFF6B9A, 0xFFA7F3D0,
            0xFFFDE047, 0xFFC4B5FD, 0xFFFB923C, 0xFF60A5FA};
    public static final int[] C_ENTER = {0xFFE11D48, 0xFF2F7BF0, 0xFF16A34A, 0xFFF59E0B, 0xFF8B5CF6, 0xFF06B6D4,
            0xFFEC4899, 0xFFC9A227, 0xFF00E5FF, 0xFF111827};
    public static final int[] C_GRAD = {0, 0xFF6A3093, 0xFF7597DE, 0xFFFF7E5F, 0xFF16A34A, 0xFF0EA5E9,
            0xFFEC4899, 0xFF000000, 0xFFFFFFFF};

    static int blend(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
        }
        return r;
    }

    static boolean light(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r * 0.299 + g * 0.587 + b * 0.114 > 150;
    }

    // ------------------------------------------------------------ specs (editor, library, import/export)

    static String hex(int c) { return String.format("#%08X", c); }

    static int col(org.json.JSONObject o, String k, int def) {
        String v = o.optString(k, "");
        if (v.isEmpty()) return def;
        try {
            v = v.replace("#", "");
            long l = Long.parseLong(v, 16);
            if (v.length() <= 6) l |= 0xFF000000L;
            return (int) l;
        } catch (Exception e) { return def; }
    }

    /** Full description of a theme (all colours + style). */
    public static org.json.JSONObject toSpec(Theme t) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("neokey", 1); o.put("name", t.name);
            o.put("bg", hex(t.bg)); if (t.bg2 != 0) o.put("bg2", hex(t.bg2));
            o.put("key", hex(t.key)); o.put("keyPressed", hex(t.keyPressed)); o.put("special", hex(t.special));
            o.put("text", hex(t.text)); o.put("specialText", hex(t.specialText)); o.put("shadow", hex(t.shadow));
            o.put("enter", hex(t.enter)); o.put("enterText", hex(t.enterText)); o.put("icon", hex(t.icon));
            o.put("hint", hex(t.hint)); o.put("popup", hex(t.popup)); o.put("popupText", hex(t.popupText));
            if (t.space != 0) o.put("space", hex(t.space));
            if (t.border != 0) { o.put("border", hex(t.border)); o.put("borderW", t.borderW); }
            o.put("font", t.font); o.put("glass", t.glass); o.put("photo", t.photo);
            if (t.anim >= 0) o.put("anim", t.anim);
            if (t.gen != null && !t.gen.isEmpty()) o.put("gen", t.gen);
            if (t.neon) { o.put("neon", true); o.put("glow", hex(t.glow)); o.put("glow2", hex(t.glow2)); }
        } catch (Exception ignored) { }
        return o;
    }

    /** Builds a theme from a spec. Missing colours are derived from the base ones. */
    public static Theme fromSpec(org.json.JSONObject o) {
        int bg = col(o, "bg", 0xFF1B1C20), key = col(o, "key", 0xFF3B3D44), text = col(o, "text", 0xFFFFFFFF),
                enter = col(o, "enter", 0xFF2F7BF0), bg2 = col(o, "bg2", 0), special = col(o, "special", 0);
        String name = o.optString("name", "سمة");
        Theme d = o.optBoolean("neon", false)
                ? neonOf(name, bg, key, text, col(o, "glow", enter), col(o, "glow2", enter))
                : make(name, bg, bg2, key, special, text, enter);
        Theme t = new Theme(name, bg, key, col(o, "keyPressed", d.keyPressed), text, special == 0 ? d.special : special,
                col(o, "specialText", d.specialText), col(o, "shadow", d.shadow), enter,
                col(o, "enterText", d.enterText), col(o, "icon", d.icon), col(o, "hint", d.hint),
                col(o, "popup", d.popup), col(o, "popupText", d.popupText), d.neon, d.glow, d.glow2);
        t.bg2 = bg2;
        t.space = col(o, "space", 0);
        t.border = col(o, "border", 0);
        t.borderW = (float) o.optDouble("borderW", 1);
        t.font = Math.max(0, Math.min(FONTS.length - 1, o.optInt("font", 0)));
        t.glass = o.optBoolean("glass", false);
        t.photo = o.optBoolean("photo", false);
        t.anim = Math.max(-1, Math.min(Backdrops.animCount() - 1, o.optInt("anim", -1)));
        t.gen = o.optString("gen", "");
        if (!t.gen.isEmpty()) {           // W69: الألوان السبعة ولدت من البذرة قبل البناء (الحقول final)
            int[] cs = GenThemes.colors(GenThemes.make(GenThemes.seedOf(t.gen)));
            t = new Theme(t.name, cs[0], cs[2], cs[3], cs[4], cs[5], cs[6],
                    col(o, "shadow", d.shadow), enter, col(o, "enterText", d.enterText),
                    col(o, "icon", d.icon), col(o, "hint", d.hint), col(o, "popup", d.popup),
                    col(o, "popupText", d.popupText), d.neon, d.glow, d.glow2);
            t.bg2 = cs[1];
        }
        return t;
    }

    /** The editable "from scratch" spec behind the custom theme. */
    public static org.json.JSONObject customSpec(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("c_spec", null);
        if (s != null) try { return new org.json.JSONObject(s); } catch (Exception ignored) { }
        org.json.JSONObject o = new org.json.JSONObject();   // migrate the v12 colour builder
        try {
            o.put("neokey", 1); o.put("name", "مخصص");
            o.put("bg", hex(p.getInt("c_bg", 0xFF1B1C20)));
            int g = p.getInt("c_grad", 0); if (g != 0) o.put("bg2", hex(g));
            o.put("key", hex(p.getInt("c_key", 0xFF3B3D44)));
            o.put("text", hex(p.getInt("c_text", 0xFFFFFFFF)));
            o.put("enter", hex(p.getInt("c_enter", 0xFFE11D48)));
        } catch (Exception ignored) { }
        return o;
    }

    public static void saveCustomSpec(Context c, org.json.JSONObject o) {
        prefs(c).edit().putString("c_spec", o.toString()).apply();
    }

    public static org.json.JSONArray library(Context c) {
        try { return new org.json.JSONArray(prefs(c).getString("lib", "[]")); }
        catch (Exception e) { return new org.json.JSONArray(); }
    }

    public static void saveLibrary(Context c, org.json.JSONArray a) {
        prefs(c).edit().putString("lib", a.toString()).apply();
    }

    /** Adds a spec to the library, returns its theme index. */
    public static int addToLibrary(Context c, org.json.JSONObject o) {
        org.json.JSONArray a = library(c);
        a.put(o);
        saveLibrary(c, a);
        return LIB_BASE + a.length() - 1;
    }

    public static int count(Context c) { return ALL.length + library(c).length(); }

    /** Layout settings that travel inside a design pack. */
    static final String[] PACK_INTS = {"keyshape", "radius", "keygap", "hpct", "wpct", "keysize", "pressglow_i", "kblayout"};

    public static org.json.JSONObject exportPack(Context c, Theme t) {
        org.json.JSONObject o = toSpec(t);
        try {
            org.json.JSONObject l = new org.json.JSONObject();
            SharedPreferences p = prefs(c);
            l.put("keyshape", p.getInt("keyshape", 0));
            l.put("radius", p.getInt("radius", 0));
            l.put("keygap", p.getInt("keygap", 1));
            l.put("hpct", heightPct(c));
            l.put("wpct", p.getInt("wpct", 100));
            l.put("keysize", p.getInt("keysize", 1));
            l.put("pressglow", p.getBoolean("pressglow", true));
            o.put("layout", l);
            o.put("app", "NeoKey");
        } catch (Exception ignored) { }
        return o;
    }

    /** Applies a pack: adds its theme to the library, selects it and applies its layout settings. */
    public static int importPack(Context c, org.json.JSONObject o) {
        if (!o.has("bg") || !o.has("key")) throw new IllegalArgumentException("not a theme");
        org.json.JSONObject l = o.optJSONObject("layout");
        org.json.JSONObject spec;
        try { spec = new org.json.JSONObject(o.toString()); } catch (Exception e) { spec = o; }
        spec.remove("layout");
        int idx = addToLibrary(c, spec);
        SharedPreferences.Editor e = prefs(c).edit().putInt("theme", idx).putBoolean("autotheme", false);
        if (l != null) {
            if (l.has("keyshape")) e.putInt("keyshape", l.optInt("keyshape"));
            if (l.has("radius")) e.putInt("radius", l.optInt("radius"));
            if (l.has("keygap")) e.putInt("keygap", l.optInt("keygap"));
            if (l.has("hpct")) e.putInt("hpct", l.optInt("hpct"));
            if (l.has("wpct")) e.putInt("wpct", l.optInt("wpct"));
            if (l.has("keysize")) e.putInt("keysize", l.optInt("keysize"));
            if (l.has("pressglow")) e.putBoolean("pressglow", l.optBoolean("pressglow"));
        }
        e.apply();
        return idx;
    }

    /** Category used by the theme filter chips. */
    public static String category(Theme t) {
        if (t.gen != null && !t.gen.isEmpty()) return "مولَّدة";
        if (t.anim >= 0) return "متحركة";
        if (t.photo) return "صور";
        if (t.neon) return "نيون";
        if (t.glass) return "زجاج";
        if (t.font == 5) return "برمجة";
        if (t.bg2 != 0) return "متدرجة";
        return light(t.bg | 0xFF000000) ? "فاتحة" : "داكنة";
    }

    public static final String[] CATS = {"الكل", "مولَّدة", "فاتحة", "داكنة", "زجاج", "متدرجة", "نيون", "برمجة", "صور"};

    /** The user's own colour builder theme (legacy entry point). */
    public static Theme customLegacy(Context c) {
        SharedPreferences p = prefs(c);
        int bg = p.getInt("c_bg", 0xFF1B1C20), key = p.getInt("c_key", 0xFF3B3D44),
                text = p.getInt("c_text", 0xFFFFFFFF), enter = p.getInt("c_enter", 0xFFE11D48),
                grad = p.getInt("c_grad", 0);
        int solidKey = (key >>> 24) < 0xCC ? blend(bg | 0xFF000000, key | 0xFF000000, (key >>> 24) / 255f) : key;
        Theme t = new Theme("مخصص", bg, key, blend(key, text, 0.22f) | (key & 0xFF000000), text,
                blend(key, bg, 0.45f), text, light(bg) ? 0x40000000 : 0x66000000,
                enter, light(enter) ? 0xFF111111 : 0xFFFFFFFF, blend(text, solidKey, 0.3f),
                blend(text, solidKey, 0.45f), solidKey, text, false, 0, 0);
        if (grad != 0) t.bg2 = grad;
        return t;
    }

    public static Theme custom(Context c) {
        Theme t = fromSpec(customSpec(c));
        return t;
    }

    public static Theme resolve(Context c, int i) {
        if (i >= LIB_BASE) {
            org.json.JSONObject o = library(c).optJSONObject(i - LIB_BASE);
            return o == null ? ALL[0] : fromSpec(o);
        }
        if (i < 0 || i >= ALL.length) return ALL[0];
        return i == CUSTOM_INDEX ? custom(c) : ALL[i];
    }

    public static final int PHOTO_INDEX = 10;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int index(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("mig13", false)) {      // v12 had the custom theme at index 11
            SharedPreferences.Editor e = p.edit().putBoolean("mig13", true);
            if (p.getInt("theme", 0) == 11) e.putInt("theme", CUSTOM_INDEX);
            e.apply();
            if (p.getInt("theme", 0) == 11) return CUSTOM_INDEX;
        }
        int i = p.getInt("theme", 0);
        if (i >= LIB_BASE) return i - LIB_BASE < library(c).length() ? i : 0;
        return (i < 0 || i >= ALL.length) ? 0 : i;
    }

    public static final int[] ACCENTS = {0, 0xFF2F7BF0, 0xFF16A34A, 0xFFE11D48, 0xFFF59E0B, 0xFF8B5CF6,
            0xFF06B6D4, 0xFFEC4899, 0xFF111827};

    public static Theme current(Context c) {
        Theme t = resolve(c, index(c));
        if (prefs(c).getBoolean("autotheme", false)) t = resolve(c, isNight(c) ? nightTheme(c) : dayTheme(c));
        if (prefs(c).getBoolean("contrast", false)) return highContrast(prefs(c).getInt("contrast_mode", 0) == 1);
        int a = prefs(c).getInt("accent", 0);
        if (a <= 0 || a >= ACCENTS.length) return t;
        return t.copy(ACCENTS[a], 0xFFFFFFFF, true);
    }

    /** Accessibility high-contrast theme (WCAG AAA level text contrast, borders on every key). */
    public static Theme highContrast(boolean light) {
        Theme t = light
                ? new Theme("تباين عالٍ", 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFE600, 0xFF000000, 0xFFE8E8E8, 0xFF000000,
                0x00000000, 0xFF0033CC, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFF000000, 0xFFFFFFFF, false, 0, 0)
                : new Theme("تباين عالٍ", 0xFF000000, 0xFF000000, 0xFF3A3A00, 0xFFFFFFFF, 0xFF1A1A1A, 0xFFFFFFFF,
                0x00000000, 0xFFFFE600, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, false, 0, 0);
        t.border = light ? 0xFF000000 : 0xFFFFFFFF;
        t.borderW = 1.6f;
        t.font = 1;
        return t;
    }

    /** User's reduce-motion switch, or animations turned off system-wide. */
    public static boolean reduceMotion(Context c) {
        if (prefs(c).getBoolean("reducemotion", false)) return true;
        try {
            return android.provider.Settings.Global.getFloat(c.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) { return false; }
    }

    public static int dayTheme(Context c) { return prefs(c).getInt("day_theme", 0); }
    public static int nightTheme(Context c) { return prefs(c).getInt("night_theme", 1); }

    /** Night by the phone's dark mode (automode 0) or by the clock (automode 1, night_from..day_from). */
    public static boolean isNight(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getInt("automode", 0) == 1) {
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            int day = p.getInt("day_from", 6), night = p.getInt("night_from", 18);
            return day < night ? (h < day || h >= night) : (h >= night && h < day);
        }
        int n = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return n == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean haptics(Context c) { return prefs(c).getBoolean("haptics", true); }
    public static boolean numberRow(Context c) { return prefs(c).getBoolean("numrow", true); }
    public static boolean popups(Context c) { return prefs(c).getBoolean("popup", true); }
    public static int heightLevel(Context c) { return prefs(c).getInt("height", 1); }
    /** Keyboard height in percent (75..140). Falls back to the old 3-level setting. */
    public static int heightPct(Context c) {
        int p = prefs(c).getInt("hpct", 0);
        if (p > 0) return p;
        int l = heightLevel(c);
        return l == 0 ? 86 : l == 2 ? 114 : 100;
    }
    public static boolean sound(Context c) { return prefs(c).getBoolean("sound", false); }
    public static boolean suggestions(Context c) { return prefs(c).getBoolean("suggest", true); }
    public static boolean doubleSpace(Context c) { return prefs(c).getBoolean("dblspace", true); }
    public static boolean swipeCursor(Context c) { return prefs(c).getInt("swipe", 0) == 1; }
    public static boolean arabicDigits(Context c) { return prefs(c).getInt("digits", 0) == 1; }
    public static int oneHand(Context c) { return prefs(c).getInt("onehand", 0); }
    public static int photoDim(Context c) { return prefs(c).getInt("dim", 45); }

    /** W54 capacity trim (law 6 — knowledge out of the dex): the phrase data lives in
     *  assets/theme_phrases.txt (24 defaults + 20 adhkar, byte-exact relocation by
     *  scripts/trim_w54.py — the ONE derivation); this is the thin strict loader — a
     *  count or marker drift refuses the whole set, never a half-loaded panel. */
    private static final int DEF_COUNT = 24, ADHKAR_COUNT = 20;
    private static volatile String[] DEF_PHRASES, ADHKAR_ARR;

    /** Strict parse (pure — no android here; the JVM probe hands the file bytes). */
    static void initPhrases(byte[] raw) {
        String[] def = new String[DEF_COUNT], adh = new String[ADHKAR_COUNT];
        int d = 0, k = 0, section = 0;
        for (String line : new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '#') {
                if (line.equals("#defaults")) section = 1;
                else if (line.equals("#adhkar")) section = 2;
                else if (!line.startsWith("# ")) throw new IllegalArgumentException("theme.phrases.marker");
                continue;
            }
            if (section == 1) { if (d >= DEF_COUNT) throw new IllegalArgumentException("theme.phrases.count"); def[d++] = line; }
            else if (section == 2) { if (k >= ADHKAR_COUNT) throw new IllegalArgumentException("theme.phrases.count"); adh[k++] = line; }
            else throw new IllegalArgumentException("theme.phrases.order");
        }
        if (d != DEF_COUNT || k != ADHKAR_COUNT) throw new IllegalArgumentException("theme.phrases.count");
        DEF_PHRASES = def; ADHKAR_ARR = adh;
    }

    private static synchronized void ensurePhrases(Context c) {
        if (DEF_PHRASES != null) return;
        try (java.io.InputStream in = c.getAssets().open("theme_phrases.txt")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            initPhrases(bo.toByteArray());
        } catch (java.io.IOException e) { throw new IllegalStateException("theme.phrases.asset", e); }
    }

    /** Defaults for the phrase editor (SetupActivity). */
    public static String[] defaultPhrases(Context c) { ensurePhrases(c); return DEF_PHRASES; }


    public static java.util.List<String> userPhrases(Context c) {
        java.util.List<String> l = new java.util.ArrayList<>();
        String s = prefs(c).getString("phrases", "");
        if (!s.isEmpty()) for (String p : s.split("\u0001")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    public static void saveUserPhrases(Context c, java.util.List<String> l) {
        prefs(c).edit().putString("phrases", android.text.TextUtils.join("\u0001", l)).apply();
    }

    /** Text shortcuts: typing the key offers the expansion as the first suggestion. */
    public static java.util.LinkedHashMap<String, String> shortcuts(Context c) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        String s = prefs(c).getString("shortcuts", null);
        if (s == null) s = "سع\u001Fالسلام عليكم ورحمة الله وبركاته\u0001وع\u001Fوعليكم السلام ورحمة الله وبركاته"
                + "\u0001جخ\u001Fجزاك الله خير\u0001يع\u001Fالله يعطيك العافية\u0001omw\u001Fon my way"
                + "\u0001btw\u001Fby the way";
        for (String line : s.split("\u0001")) {
            int i = line.indexOf('\u001F');
            if (i > 0) m.put(line.substring(0, i).toLowerCase(), line.substring(i + 1));
        }
        return m;
    }

    public static void saveShortcuts(Context c, java.util.Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (b.length() > 0) b.append('\u0001');
            b.append(e.getKey()).append('\u001F').append(e.getValue());
        }
        prefs(c).edit().putString("shortcuts", b.toString()).apply();
    }

    /** Daily adhkar and short duas, shown in the phrases panel when pref "adhkar" is on. */
    /** (W54: the adhkar text itself lives in assets/theme_phrases.txt — loaded strictly.) */


    public static java.util.List<String> allPhrases(Context c) {
        ensurePhrases(c);
        java.util.List<String> l = userPhrases(c);
        for (String p : DEF_PHRASES) l.add(p);
        if (prefs(c).getBoolean("adhkar", true)) for (String p : ADHKAR_ARR) l.add(p);
        return l;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
