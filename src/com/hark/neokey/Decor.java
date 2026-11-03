package com.hark.neokey;

/** Text decoration (زخرفة): fancy Unicode styles for Latin letters and kashida/marks for Arabic. Applied per typed char. */
final class Decor {
    static final int OFF = 0, BOLD = 1, SCRIPT = 2, DOUBLE = 3, CIRCLED = 4, SQUARED = 5, SMALL = 6,
            STRIKE = 7, UNDERLINE = 8, KASHIDA = 9, HEARTS = 10, WAVE = 11,
            ITALIC = 12, BOLD_ITALIC = 13, SCRIPT_THIN = 14, FRAKTUR = 15, BOLD_FRAKTUR = 16, SANS = 17,
            SANS_BOLD = 18, SANS_ITALIC = 19, SANS_BOLD_ITALIC = 20, MONO = 21, FULLWIDTH = 22, PAREN = 23,
            NEG_CIRCLED = 24, NEG_SQUARED = 25, SUPER = 26, UPSIDE = 27, SLASH = 28, DOUBLE_UNDER = 29,
            OVERLINE = 30, STARS = 31, DOTS = 32, SPARKLE = 33, BRACKETS = 34, AR_DECO1 = 35, AR_SHADDA = 36,
            AR_DECO2 = 37, AR_LONG = 38, AR_SPARK = 39, FLOWERS = 40;
    static final int COUNT = 41;

    /** Frames wrap the selected text, or insert both sides with the cursor in the middle. Panel ids COUNT.. */
    static final String[][] FRAMES = {
            {"꧁ ", " ꧂"}, {"★彡 ", " 彡★"}, {"『", "』"}, {"【", "】"}, {"〖", "〗"}, {"《", "》"}, {"「", "」"},
            {"⟦", "⟧"}, {"﴾ ", " ﴿"}, {"༺ ", " ༻"}, {"✿ ", " ✿"}, {"❀ ", " ❀"}, {"♡ ", " ♡"}, {"♥ ", " ♥"},
            {"✦ ", " ✦"}, {"⋆｡˚ ", " ˚｡⋆"}, {"•´¯`•. ", " .•´¯`•"}, {"ıllıllı ", " ıllıllı"}, {"▄︻デ ", " ══━一"},
            {"◥ ", " ◤"}, {"⊱ ", " ⊰"}, {"✧･ﾟ: ", " :ﾟ･✧"}, {"꒰ ", " ꒱"}, {"⫷ ", " ⫸"}, {"❝ ", " ❞"},
            {"〘 ", " 〙"}, {"⚜ ", " ⚜"}, {"༄ ", " ࿐"}, {"♛ ", " ♛"}, {"⚡ ", " ⚡"}, {"⌜ ", " ⌟"}, {"︻ ", " ︼"},
            {"꧁༺ ", "༻꧂"},
            {"꧁★ ", "★꧂"},
            {"꧁♡ ", "♡꧂"},
            {"꧁✿ ", "✿꧂"},
            {"꧁𓊈 ", "𓊉꧂"},
            {"༺❀ ", "❀༻"},
            {"༺♥ ", "♥༻"},
            {"༺☆ ", "☆༻"},
            {"☆彡 ", "彡☆"},
            {"★·.·´¯`·.·★ ", "★·.·´¯`·.·★"},
            {"•°•✿ ", "✿•°•"},
            {"•°¯`•• ", "••´¯°•"},
            {"¸.·´¯`·.¸ ", "¸.·´¯`·.¸"},
            {".•°¤*(¯`★´¯)*¤° ", "°¤*(¯´★`¯)*¤°•."},
            {"(¯`·._.· ", "·._.·´¯)"},
            {"(¯`'•.¸ ", "¸.•'´¯)"},
            {"•´¨`•.¸¸. ", ".¸¸.•´¨`•"},
            {"❤ ", "❤"},
            {"💕 ", "💕"},
            {"💖 ", "💖"},
            {"💞 ", "💞"},
            {"✨ ", "✨"},
            {"🌸 ", "🌸"},
            {"🌹 ", "🌹"},
            {"🌙 ", "🌙"},
            {"⭐ ", "⭐"},
            {"🔥 ", "🔥"},
            {"👑 ", "👑"},
            {"💎 ", "💎"},
            {"🦋 ", "🦋"},
            {"🍃 ", "🍃"},
            {"🕊 ", "🕊"},
            {"🤍 ", "🤍"},
            {"🖤 ", "🖤"},
            {"❥ ", "❥"},
            {"➳♥ ", "♥➳"},
            {"❦ ", "❦"},
            {"❧ ", "☙"},
            {"❁ ", "❁"},
            {"❃ ", "❃"},
            {"✾ ", "✾"},
            {"❋ ", "❋"},
            {"✺ ", "✺"},
            {"✵ ", "✵"},
            {"⁂ ", "⁂"},
            {"※ ", "※"},
            {"۞ ", "۞"},
            {"۩ ", "۩"},
            {"﷽ ", ""},
            {"ﷺ ", "ﷺ"},
            {"☪ ", "☪"},
            {"〖✿ ", "✿〗"},
            {"【★ ", "★】"},
            {"『✦ ", "✦』"},
            {"「♡ ", "♡」"},
            {"《✯ ", "✯》"},
            {"⟪ ", "⟫"},
            {"⟨ ", "⟩"},
            {"⦅ ", "⦆"},
            {"❮ ", "❯"},
            {"❰ ", "❱"},
            {"❴ ", "❵"},
            {"❨ ", "❩"},
            {"⌈ ", "⌋"},
            {"⌊ ", "⌉"},
            {"╰☆☆ ", "☆☆╮"},
            {"╚» ", "«╝"},
            {"▁ ▂ ▄ ▅ ▆ ▇ █ ", " █ ▇ ▆ ▅ ▄ ▂ ▁"},
            {"▀▄▀▄ ", "▄▀▄▀"},
            {"░▒▓ ", "▓▒░"},
            {"▓▒░ ", "░▒▓"},
            {"═══ ", "═══"},
            {"━━━ ", "━━━"},
            {"──── ", "────"},
            {"∘₊✧─ ", "─✧₊∘"},
            {"⊹˚. ", ".˚⊹"},
            {"˗ˏˋ ", "ˎˊ˗"},
            {"⋆˚✿˖° ", "°˖✿˚⋆"},
            {"｡ﾟ☆: *.☽ ", "☽.* :☆ﾟ｡"},
            {"ʚ ", "ɞ"},
            {"ʚ♡ɞ ", "ʚ♡ɞ"},
            {"(っ◔◡◔)っ ♥ ", "♥"},
            {"ღ ", "ღ"},
            {"♪ ", "♪"},
            {"♫ ", "♫"},
            {"♬ ", "♬"},
            {"☾ ", "☽"},
            {"☀ ", "☀"},
            {"☁ ", "☁"},
            {"⚘ ", "⚘"},
            {"☘ ", "☘"},
            {"♔ ", "♔"},
            {"♕ ", "♕"},
            {"♚ ", "♚"},
            {"✞ ", "✞"},
            {"✟ ", "✟"},
            {"⚝ ", "⚝"},
            {"✪ ", "✪"},
            {"✯ ", "✯"},
            {"✰ ", "✰"},
            {"➶ ", "➷"},
            {"⇝ ", "⇜"},
            {"↬ ", "↫"},
            {"⟿ ", "⇜"},
            {"『•』 ", "『•』"},
            {"ıllı ", "ıllı"},
            {"ılıılı ", "ılıılı"},
            {"✧˖°. ", ".°˖✧"},
            {"‿︵‿︵ ", "‿︵‿︵"},
            {"﹏﹏ ", "﹏﹏"},
            {"⏤͟͟͞͞ ", "⏤͟͟͞͞"},
            {"ـــــــ ", "ـــــــ"},
            {"╭─ ", "─╮"},
            {"𖣔 ", "𖣔"},
            {"𓆩 ", "𓆪"},
            {"𓂀 ", "𓂀"},
            {"⊹ ", "⊹"},
            {"ꕥ ", "ꕥ"},
    };
    static final int TOTAL = COUNT + FRAMES.length;

    static String frameSample(int i) { return FRAMES[i][0] + "نيو" + FRAMES[i][1]; }

    static final String[] NAMES = {"بدون زخرفة", "عريض", "مزخرف", "مزدوج", "دوائر", "مربعات", "صغير",
            "مشطوب", "تحته خط", "تطويل عربي", "قلوب", "موجة",
            "مائل", "عريض مائل", "خط يد", "قوطي", "قوطي عريض", "بسيط", "بسيط عريض", "بسيط مائل",
            "بسيط عريض مائل", "آلة كاتبة", "عريض واسع", "أقواس", "دوائر سوداء", "مربعات سوداء", "علوي صغير",
            "مقلوب", "خط مائل عليه", "خطين تحته", "خط فوقه", "نجوم", "نقاط", "لمعة", "إطارات",
            "زخرفة عربية ١", "شدّة عربية", "زخرفة عربية ٢", "تطويل طويل", "لمعة عربية", "ورود"};

    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";
    private static final String SUP = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖᑫʳˢᵗᵘᵛʷˣʸᶻ";
    private static final String SUP_D = "⁰¹²³⁴⁵⁶⁷⁸⁹";
    private static final String FLIP = "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz";
    private static final String FLIP_D = "0ƖᄅƐㄣϛ9ㄥ86";
    private static final String NON_JOINING = "اأإآٱدذرزوؤةىء";

    private static boolean arabicSample(int s) {
        return s == STRIKE || s == UNDERLINE || s == KASHIDA || s == HEARTS || s == WAVE || s == SLASH
                || s == DOUBLE_UNDER || s == OVERLINE || s == STARS || s == DOTS || s == SPARKLE || s >= AR_DECO1;
    }

    static String sample(int style) {
        if (style == OFF) return NAMES[OFF];
        if (arabicSample(style)) return apply(style == FLOWERS || style == STARS ? "نيو" : "نيو كيبورد", style);
        if (style == BRACKETS) return apply("Neo", style);
        return apply("NeoKey", style);
    }

    static String apply(String s, int style) {
        if (style == OFF) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) b.append(one(s.charAt(i), style));
        return b.toString();
    }

    /** Math alphanumeric block helper: upper base, lower base, digit base (0 = none). */
    private static String math(char c, int up, int lo, int dg) {
        if (c >= 'A' && c <= 'Z' && up != 0) return cp(up + c - 'A');
        if (c >= 'a' && c <= 'z' && lo != 0) return cp(lo + c - 'a');
        if (c >= '0' && c <= '9' && dg != 0) return cp(dg + c - '0');
        return String.valueOf(c);
    }

    static String one(char c, int style) {
        boolean up = c >= 'A' && c <= 'Z', lo = c >= 'a' && c <= 'z', dig = c >= '0' && c <= '9';
        boolean ar = c >= 0x0621 && c <= 0x064A;
        boolean letter = up || lo || dig || ar;
        boolean joins = ar && NON_JOINING.indexOf(c) < 0;
        switch (style) {
            case BOLD: return math(c, 0x1D400, 0x1D41A, 0x1D7CE);
            case SCRIPT: return math(c, 0x1D4D0, 0x1D4EA, 0);
            case DOUBLE:
                if (up) {
                    switch (c) {
                        case 'C': return "ℂ"; case 'H': return "ℍ"; case 'N': return "ℕ"; case 'P': return "ℙ";
                        case 'Q': return "ℚ"; case 'R': return "ℝ"; case 'Z': return "ℤ";
                        default: return cp(0x1D538 + c - 'A');
                    }
                }
                return math(c, 0, 0x1D552, 0x1D7D8);
            case CIRCLED:
                if (up) return cp(0x24B6 + c - 'A');
                if (lo) return cp(0x24D0 + c - 'a');
                if (c == '0') return "⓪";
                if (dig) return cp(0x2460 + c - '1');
                return String.valueOf(c);
            case SQUARED:
                if (up) return cp(0x1F130 + c - 'A');
                if (lo) return cp(0x1F130 + c - 'a');
                return String.valueOf(c);
            case SMALL:
                if (lo) return String.valueOf(SMALL_CAPS.charAt(c - 'a'));
                if (up) return String.valueOf(SMALL_CAPS.charAt(c - 'A'));
                return String.valueOf(c);
            case STRIKE: return letter ? c + "\u0336" : String.valueOf(c);
            case UNDERLINE: return letter ? c + "\u0332" : String.valueOf(c);
            case KASHIDA: return joins ? c + "ـ" : String.valueOf(c);
            case HEARTS: return (up || lo || ar) ? c + "♥" : String.valueOf(c);
            case WAVE: return letter ? c + "\u0330" : String.valueOf(c);
            case ITALIC: return c == 'h' ? "ℎ" : math(c, 0x1D434, 0x1D44E, 0);
            case BOLD_ITALIC: return math(c, 0x1D468, 0x1D482, 0);
            case SCRIPT_THIN: {
                switch (c) {
                    case 'B': return "ℬ"; case 'E': return "ℰ"; case 'F': return "ℱ"; case 'H': return "ℋ";
                    case 'I': return "ℐ"; case 'L': return "ℒ"; case 'M': return "ℳ"; case 'R': return "ℛ";
                    case 'e': return "ℯ"; case 'g': return "ℊ"; case 'o': return "ℴ";
                    default: return math(c, 0x1D49C, 0x1D4B6, 0);
                }
            }
            case FRAKTUR: {
                switch (c) {
                    case 'C': return "ℭ"; case 'H': return "ℌ"; case 'I': return "ℑ"; case 'R': return "ℜ"; case 'Z': return "ℨ";
                    default: return math(c, 0x1D504, 0x1D51E, 0);
                }
            }
            case BOLD_FRAKTUR: return math(c, 0x1D56C, 0x1D586, 0);
            case SANS: return math(c, 0x1D5A0, 0x1D5BA, 0x1D7E2);
            case SANS_BOLD: return math(c, 0x1D5D4, 0x1D5EE, 0x1D7EC);
            case SANS_ITALIC: return math(c, 0x1D608, 0x1D622, 0);
            case SANS_BOLD_ITALIC: return math(c, 0x1D63C, 0x1D656, 0);
            case MONO: return math(c, 0x1D670, 0x1D68A, 0x1D7F6);
            case FULLWIDTH:
                if (c == ' ') return "\u3000";
                return math(c, 0xFF21, 0xFF41, 0xFF10);
            case PAREN:
                if (up) return cp(0x249C + c - 'A');
                if (lo) return cp(0x249C + c - 'a');
                if (dig && c != '0') return cp(0x2474 + c - '1');
                return String.valueOf(c);
            case NEG_CIRCLED:
                if (up) return cp(0x1F150 + c - 'A');
                if (lo) return cp(0x1F150 + c - 'a');
                if (c == '0') return "⓿";
                if (dig) return cp(0x2776 + c - '1');
                return String.valueOf(c);
            case NEG_SQUARED:
                if (up) return cp(0x1F170 + c - 'A');
                if (lo) return cp(0x1F170 + c - 'a');
                return String.valueOf(c);
            case SUPER:
                if (lo) return String.valueOf(SUP.charAt(c - 'a'));
                if (up) return String.valueOf(SUP.charAt(c - 'A'));
                if (dig) return String.valueOf(SUP_D.charAt(c - '0'));
                return String.valueOf(c);
            case UPSIDE:
                if (lo) return String.valueOf(FLIP.charAt(c - 'a'));
                if (up) return String.valueOf(FLIP.charAt(c - 'A'));
                if (dig) return String.valueOf(FLIP_D.charAt(c - '0'));
                return String.valueOf(c);
            case SLASH: return letter ? c + "\u0338" : String.valueOf(c);
            case DOUBLE_UNDER: return letter ? c + "\u0333" : String.valueOf(c);
            case OVERLINE: return letter ? c + "\u0305" : String.valueOf(c);
            case STARS: return (up || lo || ar) ? c + "✦" : String.valueOf(c);
            case DOTS: return letter ? c + "\u0324" : String.valueOf(c);
            case SPARKLE: return letter ? c + "\u0489" : String.valueOf(c);
            case BRACKETS: return (up || lo || dig) ? "【" + c + "】" : String.valueOf(c);
            case AR_DECO1: return joins ? c + "ـ۪ٜ" : String.valueOf(c);
            case AR_SHADDA: return joins ? c + "ـّ" : (ar ? c + "ّ" : String.valueOf(c));
            case AR_DECO2: return joins ? c + "ـۨ" : String.valueOf(c);
            case AR_LONG: return joins ? c + "ـــ" : String.valueOf(c);
            case AR_SPARK: return joins ? c + "ـ҈" : (ar ? c + "҈" : String.valueOf(c));
            case FLOWERS: return (up || lo || ar) ? c + "✿" : String.valueOf(c);
            default: return String.valueOf(c);
        }
    }

    private static String cp(int code) { return new String(Character.toChars(code)); }
}
