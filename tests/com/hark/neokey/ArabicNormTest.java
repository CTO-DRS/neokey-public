package com.hark.neokey;

/** Roadmap Phase 0 / Week 2: Arabic normalisation — Dictionary.norm + ClipNorm. */
public class ArabicNormTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        System.out.println("== ArabicNorm: Dictionary.norm ==");
        check("hamza alef forms all fold to ا", Dictionary.norm("أإآٱ").equals("اااا"));
        check("taa marbuta folds to ه", Dictionary.norm("ة").equals("ه"));
        check("alef maqsura folds to ي", Dictionary.norm("ى").equals("ي"));
        check("hamza on waw folds to و", Dictionary.norm("ؤ").equals("و"));
        check("hamza on yaa folds to ي", Dictionary.norm("ئ").equals("ي"));
        check("standalone hamza kept", Dictionary.norm("ء").equals("ء"));
        check("tashkeel stripped (بِسْمِ)", Dictionary.norm("بِسْمِ").equals("بسم"));
        check("full diacritics word (مُحَمَّد)", Dictionary.norm("مُحَمَّد").equals("محمد"));
        check("mixed marks inside phrase", Dictionary.norm("السَّلامُ عليكم").equals("السلام عليكم"));
        check("english lowercased", Dictionary.norm("Hello WORLD").equals("hello world"));
        check("empty stays empty", Dictionary.norm("").isEmpty());
        check("twins أحمد/احمد share key", Dictionary.norm("أحمد").equals(Dictionary.norm("احمد")));
        check("twins علي/على share key", Dictionary.norm("علي").equals(Dictionary.norm("على")));

        System.out.println("== ArabicNorm: ClipNorm.isMark ==");
        check("fatha is a mark", ClipNorm.isMark('\u064E'));
        check("shadda is a mark", ClipNorm.isMark('\u0651'));
        check("sukun is a mark", ClipNorm.isMark('\u0652'));
        check("tatweel is a mark", ClipNorm.isMark('\u0640'));
        check("superscript alef is a mark", ClipNorm.isMark('\u0670'));
        check("quranic annotation is a mark", ClipNorm.isMark('\u06D6'));
        check("ZWJ is a mark", ClipNorm.isMark('\u200D'));
        check("RLM is a mark", ClipNorm.isMark('\u200F'));
        check("alef is not a mark", !ClipNorm.isMark('ا'));
        check("latin letter is not a mark", !ClipNorm.isMark('a'));
        check("space is not a mark", !ClipNorm.isMark(' '));

        System.out.println("== ArabicNorm: ClipNorm.fold ==");
        check("fold أ", ClipNorm.fold('أ') == 'ا');
        check("fold إ", ClipNorm.fold('إ') == 'ا');
        check("fold آ", ClipNorm.fold('آ') == 'ا');
        check("fold ٱ", ClipNorm.fold('ٱ') == 'ا');
        check("fold ى", ClipNorm.fold('ى') == 'ي');
        check("fold ة", ClipNorm.fold('ة') == 'ه');
        check("fold ؤ", ClipNorm.fold('ؤ') == 'و');
        check("fold ئ", ClipNorm.fold('ئ') == 'ي');
        check("fold arabic-indic digits", ClipNorm.fold('٠') == '0' && ClipNorm.fold('٩') == '9');
        check("fold extended arabic digits", ClipNorm.fold('۰') == '0' && ClipNorm.fold('۹') == '9');
        check("fold uppercase latin", ClipNorm.fold('A') == 'a');
        check("fold keeps plain arabic", ClipNorm.fold('ب') == 'ب');

        System.out.println("== ArabicNorm: ClipNorm.of ==");
        check("of(null) empty", ClipNorm.of(null).isEmpty());
        check("of(empty) empty", ClipNorm.of("").isEmpty());
        check("of strips marks + folds", ClipNorm.of("أهْلاً").equals("اهلا"));
        check("of converts digits", ClipNorm.of("١٢٣").equals("123"));
        check("of mixed text+digits+latin", ClipNorm.of("بِسْم Hello ٥").equals("بسم hello 5"));
        check("of marks-only is empty", ClipNorm.of("ِْـً").isEmpty());

        System.out.println("== ArabicNorm: ClipNorm.find ==");
        int[] r = ClipNorm.find("السَّلامُ عليكم", ClipNorm.of("السلام"));
        check("find skips interior marks", r != null && r[0] == 0 && r[1] == "السَّلامُ".length());
        check("find miss returns null", ClipNorm.find("مرحبا", "سلام") == null);
        check("find folds آ inside raw", ClipNorm.find("الآخر امرأأ", ClipNorm.of("الاخر")) != null);
        check("find matches arabic-indic digits as ascii", ClipNorm.find("اتصل ٠٥٥٥ الآن", "0555") != null);
        check("find in shadda word", ClipNorm.find("مَكَّة المكرمة", ClipNorm.of("مكة")) != null);
        int[] t = ClipNorm.find("سلامٌ يا صديق", "سلام");
        check("find keeps trailing mark with match", t != null && t[0] == 0 && t[1] == 5);
        check("find(null, q) null", ClipNorm.find(null, "سلام") == null);
        check("find(raw, null) null", ClipNorm.find("سلام", null) == null);
        check("find(raw, empty) null", ClipNorm.find("سلام", "") == null);
        int[] m = ClipNorm.find("وعليكم السلام ورحمة", "السلام");
        check("find mid-text position", m != null && m[0] == 7);
        System.out.println("ArabicNormTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
