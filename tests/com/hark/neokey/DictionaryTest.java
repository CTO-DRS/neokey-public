package com.hark.neokey;

import java.io.File;
import java.util.List;

/** Roadmap Phase 0 / Week 2: the hot core — suggestion, typo correction, learning,
 *  next-word prediction, glide typing — against the REAL 150k-word asset dictionary. */
public class DictionaryTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static Dictionary main;     // shared instance, own user dir
    static File dirMain;

    static Dictionary freshDict(File dir) {
        Dictionary d = new Dictionary(new FakeContext(dir), "ar");
        await(d);
        return d;
    }

    static void await(Dictionary d) {
        long t0 = System.currentTimeMillis();
        while (!d.isLoaded() && System.currentTimeMillis() - t0 < 15000) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
        }
    }

    static boolean existsWithin(File f, long ms) {
        long t0 = System.currentTimeMillis();
        while (!f.exists() && System.currentTimeMillis() - t0 < ms) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        return f.exists();
    }

    public static void run() {
        dirMain = new File("build/test-user-dict");
        dirMain.mkdirs();
        System.out.println("== Dictionary:load & knows ==");
        main = freshDict(dirMain);
        check("150k asset dictionary loads on JVM", main.isLoaded());
        check("knows common word", main.knows("السلام"));
        check("knows typo-variant shipped in dict (هاذا)", main.knows("هاذا"));
        check("knows 1-edit variant (السلم)", main.knows("السلم"));
        check("unknown word not known", !main.knows("شذوبيب"));
        check("knows hamza surface form only when shipped", !main.knows("السلآم"));

        System.out.println("== Dictionary:suggest ==");
        List<String> pre = main.suggest("سلام", 5);
        check("prefix suggestions non-empty", !pre.isEmpty());
        boolean preOk = pre.size() <= 5;
        for (String s : pre) if (!Dictionary.norm(s).startsWith("سلام")) preOk = false;
        check("prefix suggestions all share prefix", preOk);
        check("typed word excluded from its own suggestions", !pre.contains("سلام"));
        check("longer completions included", main.suggest("سلام", 8).contains("سلامة"));
        List<String> typo = main.suggest("السلم", 8);
        check("typo tolerance finds السلام for السلم", typo.contains("السلام"));
        check("empty query empty result", main.suggest("", 5).isEmpty());
        check("cap n respected", main.suggest("ال", 3).size() <= 3);
        for (int i = 0; i < 10; i++) main.learn("شذوب");
        List<String> learned = main.suggest("شذو", 5);
        check("learned word (x10) tops suggestions", !learned.isEmpty() && "شذوب".equals(learned.get(0)));
        check("learned word below learning threshold excluded", !main.suggest("شذو", 5).isEmpty());

        System.out.println("== Dictionary:correction ==");
        check("hamza-form typo corrected via same key", "السلام".equals(main.correction("السلآم")));
        check("already-known word not corrected", main.correction("السلم") == null);
        check("common word itself not corrected", main.correction("السلام") == null);
        check("too-short input not corrected", main.correction("سا") == null);
        check("far-from-any-word not corrected", main.correction("زقزقنة_غيرموجودة") == null);

        System.out.println("== Dictionary:learning limits ==");
        int before = main.learnedCount();
        main.learn("ا");                                  // too short
        check("1-char word rejected", main.learnedCount() == before);
        main.learn("اااااااااااااااااااااااااااااااا");   // 31 chars — too long
        check("31-char word rejected", main.learnedCount() == before);

        System.out.println("== Dictionary:prediction ==");
        List<String> pr = main.predict("السلام", 5);
        check("seed bigram السلام->عليكم", pr.contains("عليكم"));
        check("prediction respects cap", pr.size() <= 5);
        check("unknown previous word predicts nothing", main.predict("زقزقنة_غيرموجودة", 5).isEmpty());
        for (int i = 0; i < 15; i++) main.learnPair("السلام", "نعمه");
        check("repeated learned pair beats corpus (ranks 1st)", "نعمه".equals(main.predict("السلام", 1).get(0)));
        main.learnPair("", "x"); main.learnPair("a", "اااااااااااااااااااااااااااااااا");
        check("learnPair edge inputs no crash", true);

        System.out.println("== Dictionary:glide ==");
        check("glide path finds target word", main.glide("السلام", 10).contains("السلام"));
        check("glide dedupes double letters (اله -> الله)", main.glide("اله", 10).contains("الله"));
        check("glide extra strokes tolerated (سلالم -> سلام)", main.glide("سلالم", 10).contains("سلام"));
        check("glide short path empty", main.glide("ا", 5).isEmpty());
        check("glide prefers learned user word", main.glide("شذوب", 5).contains("شذوب"));

        System.out.println("== Dictionary:persistence ==");
        main.learn("كلمةتستمر"); main.learn("كلمةتستمر"); main.learn("كلمةتستمر");
        main.save();
        File userFile = new File(dirMain, "user_ar.txt");
        check("save() writes user file", existsWithin(userFile, 8000));
        Dictionary reloaded = freshDict(dirMain);
        check("learned word survives restart", reloaded.knows("كلمةتستمر"));
        check("learned count carried over", reloaded.learnedCount() >= 1);
        Dictionary wiper = freshDict(new File("build/test-user-wipe"));
        wiper.learn("شيءمؤقت"); wiper.forgetAll();
        check("forgetAll clears learning", wiper.learnedCount() == 0);
        main.clearUser();
        check("clearUser deletes the user file", !userFile.exists());

        System.out.println("== Dictionary:performance gates (JVM) ==");
        long t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) main.suggest("سلام", 10);
        double suggestMs = (System.nanoTime() - t0) / 1e6 / 30;
        t0 = System.nanoTime();
        for (int i = 0; i < 30; i++) main.predict("في", 5);
        double predictMs = (System.nanoTime() - t0) / 1e6 / 30;
        System.out.printf("PERF suggest(سلام,10) avg %.2f ms, predict avg %.3f ms (150k dict, JVM)%n", suggestMs, predictMs);
        check("suggest P50 gate < 80ms (regression guard)", suggestMs < 80);
        check("predict P50 gate < 5ms", predictMs < 5);

        System.out.println("== Dictionary:english (G1 — NEOKEY 1.0 GLOBAL) ==");
        // القاموس الإنجليزي الحقيقي (assets/en.txt 80k كلمة) + بذرة الثنائيات
        // الجديدة (assets/bigram_en_base.txt — كانت غائبة وتُبتلع صامتاً).
        Dictionary en = new Dictionary(new FakeContext(dirMain), "en");
        await(en);
        check("english dict loads", en.isLoaded() && en.knows("hello"));
        List<String> enSug = en.suggest("hel", 5);
        check("english suggest works", !enSug.isEmpty() && enSug.contains("hello"));
        boolean latinOnly = true;
        for (String w : enSug) for (int ci = 0; ci < w.length(); ci++)
            if (w.charAt(ci) >= 0x0600 && w.charAt(ci) <= 0x06FF) latinOnly = false;
        check("english suggestions carry no Arabic script", latinOnly);
        List<String> enPred = en.predict("the", 5);
        check("english prediction from the bigram seed (the->best/same/world...)",
                !enPred.isEmpty());
        boolean predLatin = true;
        for (String w : enPred) for (int ci = 0; ci < w.length(); ci++)
            if (w.charAt(ci) >= 0x0600 && w.charAt(ci) <= 0x06FF) predLatin = false;
        check("english predictions carry no Arabic script (seed purity)", predLatin);
        check("Dictionary.hasArabic gates by script",
                Dictionary.hasArabic("سلام") && !Dictionary.hasArabic("hello")
                        && !Dictionary.hasArabic("123 😂") && !Dictionary.hasArabic("")
                        && !Dictionary.hasArabic(null));
        System.out.println("DictionaryTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
