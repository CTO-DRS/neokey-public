package com.hark.neokey;

/** Roadmap Phase 0 / Week 2: Unicode text decoration (زخرفة) styles + frames. */
public class DecorTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static String cp(int code) { return new String(Character.toChars(code)); }

    public static void run() {
        System.out.println("== Decor:catalog integrity ==");
        check("NAMES covers every style", Decor.NAMES.length == Decor.COUNT);
        check("TOTAL = styles + frames", Decor.TOTAL == Decor.COUNT + Decor.FRAMES.length);
        check("bismillah frame is the open-only ornament", Decor.FRAMES.length > 0 && Decor.frameSample(0).contains("نيو"));
        boolean framesOk = true;
        int emptyClose = 0;
        for (String[] f : Decor.FRAMES) {
            if (f.length != 2 || f[0].isEmpty()) framesOk = false;
            if (f.length == 2 && f[1].isEmpty()) emptyClose++;   // bismillah frame ships with no closing side by design
        }
        check("every frame has 2 columns and non-empty open", framesOk);
        check("only the bismillah frame has an empty close side", emptyClose == 1);
        boolean samplesOk = true;
        for (int i = 0; i < Decor.FRAMES.length; i++) if (!Decor.frameSample(i).contains("نيو")) samplesOk = false;
        check("frameSample wraps نيو", samplesOk);
        check("sample(OFF) is the plain name", Decor.NAMES[Decor.OFF].equals(Decor.sample(Decor.OFF)));
        check("sample(BOLD) non-empty", !Decor.sample(Decor.BOLD).isEmpty());
        check("OFF apply is identity", "نص عربي".equals(Decor.apply("نص عربي", Decor.OFF)));
        check("unknown style is identity", "x".equals(Decor.one('x', 999)));

        System.out.println("== Decor:latin math styles ==");
        check("BOLD a", cp(0x1D41A).equals(Decor.apply("a", Decor.BOLD)));
        check("BOLD A", cp(0x1D400).equals(Decor.apply("A", Decor.BOLD)));
        check("BOLD digit 5", cp(0x1D7CE + 5).equals(Decor.apply("5", Decor.BOLD)));
        check("BOLD keeps arabic untouched", "سلام".equals(Decor.apply("سلام", Decor.BOLD)));
        check("MONO digit 5", cp(0x1D7F6 + 5).equals(Decor.apply("5", Decor.MONO)));
        check("SANS Z", cp(0x1D5A0 + 'Z' - 'A').equals(Decor.apply("Z", Decor.SANS)));
        check("SCRIPT_THIN special e", "ℯ".equals(Decor.apply("e", Decor.SCRIPT_THIN)));
        check("ITALIC special h", "ℎ".equals(Decor.apply("h", Decor.ITALIC)));
        check("FRAKTUR special C", "ℭ".equals(Decor.apply("C", Decor.FRAKTUR)));
        check("DOUBLE special R", "ℝ".equals(Decor.apply("R", Decor.DOUBLE)));
        check("FULLWIDTH space", "\u3000".equals(Decor.one(' ', Decor.FULLWIDTH)));
        check("FULLWIDTH A", cp(0xFF21).equals(Decor.apply("A", Decor.FULLWIDTH)));

        System.out.println("== Decor:enclosed styles ==");
        check("CIRCLED b", cp(0x24D0 + 1).equals(Decor.apply("b", Decor.CIRCLED)));
        check("CIRCLED 0", "⓪".equals(Decor.apply("0", Decor.CIRCLED)));
        check("NEG_CIRCLED 0", "⓿".equals(Decor.apply("0", Decor.NEG_CIRCLED)));
        check("PAREN digit 3", cp(0x2474 + 2).equals(Decor.apply("3", Decor.PAREN)));
        check("PAREN 0 untouched", "0".equals(Decor.apply("0", Decor.PAREN)));
        check("SQUARED a", cp(0x1F130).equals(Decor.apply("a", Decor.SQUARED)));
        check("BRACKETS wraps letter", "【a】".equals(Decor.apply("a", Decor.BRACKETS)));
        check("BRACKETS skips space", " ".equals(Decor.one(' ', Decor.BRACKETS)));

        System.out.println("== Decor:combining marks ==");
        check("STRIKE adds U+0336", "ب\u0336".equals(Decor.apply("ب", Decor.STRIKE)));
        check("UNDERLINE adds U+0332", "a\u0332".equals(Decor.apply("a", Decor.UNDERLINE)));
        check("DOUBLE_UNDER adds U+0333", "a\u0333".equals(Decor.apply("a", Decor.DOUBLE_UNDER)));
        check("OVERLINE adds U+0305", "a\u0305".equals(Decor.apply("a", Decor.OVERLINE)));
        check("DOTS adds U+0324", "a\u0324".equals(Decor.apply("a", Decor.DOTS)));
        check("WAVE adds U+0330", "a\u0330".equals(Decor.apply("a", Decor.WAVE)));
        check("SLASH adds U+0338", "a\u0338".equals(Decor.apply("a", Decor.SLASH)));

        System.out.println("== Decor:arabic styles ==");
        check("KASHIDA joins ب", "بـ".equals(Decor.apply("ب", Decor.KASHIDA)));
        check("KASHIDA skips non-joining ا", "ا".equals(Decor.apply("ا", Decor.KASHIDA)));
        check("AR_SHADDA on joining ب", "بـّ".equals(Decor.apply("ب", Decor.AR_SHADDA)));
        check("AR_SHADDA on non-joining ا", "اّ".equals(Decor.apply("ا", Decor.AR_SHADDA)));
        check("AR_LONG stretches ب", "بـــ".equals(Decor.apply("ب", Decor.AR_LONG)));
        check("HEARTS decorates arabic", "م♥".equals(Decor.apply("م", Decor.HEARTS)));
        check("FLOWERS decorates arabic", "م✿".equals(Decor.apply("م", Decor.FLOWERS)));
        check("STARS decorates arabic", "م✦".equals(Decor.apply("م", Decor.STARS)));

        System.out.println("== Decor:mapped + superscript ==");
        check("SUPER digit 3", "³".equals(Decor.apply("3", Decor.SUPER)));
        check("UPSIDE a", "ɐ".equals(Decor.apply("a", Decor.UPSIDE)));
        check("SMALL caps a", "ᴀ".equals(Decor.apply("a", Decor.SMALL)));
        check("SMALL caps upper A", "ᴀ".equals(Decor.apply("A", Decor.SMALL)));
        String whole = Decor.apply("NeoKey 2036", Decor.SANS);
        StringBuilder manual = new StringBuilder();
        for (int i = 0; i < "NeoKey 2036".length(); i++) manual.append(Decor.one("NeoKey 2036".charAt(i), Decor.SANS));
        check("apply == per-char one()", manual.toString().equals(whole));
        System.out.println("DecorTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
