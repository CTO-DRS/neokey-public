package com.hark.neokey;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1, item C1 (week 5): the dialect layer — Dialect.java (pure JVM).
 * الكشف من الكتابة: hijazi / najdi / gulf / msa — بلا إنترنت وبلا بيانات مستخدم.
 *
 * Covered here:
 *   • per-class detection positives (curated sentences, the C1 gate set)
 *   • robustness: tashkeel/tatweel ignored, hamza/ة/ى variants collapse via
 *     normAr (ايش=إيش، دحينة=دحينه)، word order irrelevant, window caps at 12
 *   • honesty: MSA/Latin/empty/numbers never claim a Gulf dialect
 *   • determinism: same input twice → equal Result (equals contract)
 *   • the marker table contract: 81 keys, weights 0..3, no all-zero row,
 *     TSV mirror equality is the GATE's job — here we pin shape + samples
 *   • confidence/hits semantics: msa = 1.0/hits 0, dialect = top/total
 *   • tokenize: punctuation/Latin/digits are separators, not words
 */
public class DialectTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    public static void run() {
        // ---- positives: one curated sentence per class -----------------------
        check("hijazi: ايش تسوى دحين", Dialect.detect("ايش تسوى دحين").dialect.equals(Dialect.HIJAZI));
        check("hijazi: يمه ايش عندك اليوم", Dialect.detect("يمه ايش عندك اليوم").dialect.equals(Dialect.HIJAZI));
        check("hijazi: الاكل مليح اليوم", Dialect.detect("الاكل مليح اليوم").dialect.equals(Dialect.HIJAZI));
        check("najdi: وش تسوى الحين", Dialect.detect("وش تسوى الحين").dialect.equals(Dialect.NAJDI));
        check("najdi: ذلحين وصلنا الشركه", Dialect.detect("ذلحين وصلنا الشركه").dialect.equals(Dialect.NAJDI));
        check("najdi: مب زين كذا تسوي", Dialect.detect("مب زين كذا تسوي").dialect.equals(Dialect.NAJDI));
        check("gulf: وايد زين هذا الشي", Dialect.detect("وايد زين هذا الشي").dialect.equals(Dialect.GULF));
        check("gulf: شخبارك عساك بخير", Dialect.detect("شخبارك عساك بخير").dialect.equals(Dialect.GULF));
        check("gulf: حيل حلو المكان", Dialect.detect("حيل حلو المكان").dialect.equals(Dialect.GULF));

        // ---- normAr robustness ------------------------------------------------
        check("normAr: ايش=إيش (همزة)", Dialect.detect("إيش تسوى دحين").dialect.equals(Dialect.HIJAZI));
        check("normAr: دحينة=دحينه (ة/ه)", Dialect.detect("دحينه وصلت البيت").dialect.equals(Dialect.HIJAZI));
        check("normAr: التشكيل يتجاهل", Dialect.detect("إيشُ تسوِى دحـينً").dialect.equals(Dialect.HIJAZI));
        check("normAr: التطويل يتجاهل", Dialect.detect("دحــين ابغاك").dialect.equals(Dialect.HIJAZI));
        check("normAr وحدة: أ→ا، ة→ه، ى→ي، ؤ→و، ئ→ي", Dialect.normAr("أإآةىؤئ").equals("اااهيوي"));

        // ---- word order / window ---------------------------------------------
        check("ترتيب الكلمات لا يغير الفئة", Dialect.detect("دحين ايش تسوى").dialect.equals(Dialect.HIJAZI));
        List<String> many = Arrays.asList("الطقس", "جميل", "اليوم", "وش", "تسوى", "الحين",
                "وترى", "الكذا", "زين", "مب", "خوي", "كذا", "وش", "ترى");
        check("النافذة آخر 12 كلمة فقط", Dialect.detectWords(many).hits >= 6);

        // ---- honesty: no dialect where there is none ---------------------------
        check("فصحى لا تدعي لهجة", Dialect.detect("الطقس جميل اليوم في المدينة").dialect.equals(Dialect.MSA));
        check("فارغ = msa", Dialect.detect("").dialect.equals(Dialect.MSA) && Dialect.detect(null).dialect.equals(Dialect.MSA));
        check("لاتيني/أرقام = msa", Dialect.detect("hello 123 bye").dialect.equals(Dialect.MSA));
        check("ترقيم فقط = msa", Dialect.detect("،؛: ؟!").dialect.equals(Dialect.MSA));
        check("msa ثقة 1.0 وصفر دليل", Dialect.detect("نلتقي في المكتب الساعة العاشرة").confidence == 1.0
                && Dialect.detect("نلتقي في المكتب الساعة العاشرة").hits == 0);

        // ---- determinism -------------------------------------------------------
        Dialect.Result a = Dialect.detect("شخبارك حيل وايد زين");
        Dialect.Result b = Dialect.detect("شخبارك حيل وايد زين");
        check("حتمية: نفس المدخل = نفس النتيجة", a.equals(b) && a.hashCode() == b.hashCode());
        check("toString يحمل الفئة والثقة", a.toString().contains(a.dialect));

        // ---- confidence/hits semantics -----------------------------------------
        Dialect.Result r = Dialect.detect("ايش تسوى دحين");
        check("الثقة = الأعلى/المجموع (0..1]", r.confidence > 0.5 && r.confidence <= 1.0);
        check("hits يحصي الكلمات الدالة فقط", r.hits == 2);
        Dialect.Result fam = Dialect.detect("وينك ابغاك الحين");
        check("عائلة بلا حسم = خليجي عام", fam.dialect.equals(Dialect.GULF) && fam.hits == 3);

        // ---- tokenize -----------------------------------------------------------
        List<String> tk = Dialect.tokenize("ايش، تسوى؟ dحين 123");
        check("tokenize: الترقيم واللاتيني فواصل", tk.contains("ايش") && tk.contains("تسوى") && tk.contains("حين") && tk.size() == 3);
        check("tokenize: فارغ آمن", Dialect.tokenize(null).isEmpty());

        // ---- marker table contract ----------------------------------------------
        Map<String, int[]> m = Dialect.markers();
        check("جدول العلامات: 81 مدخلاً", m.size() == 81);
        boolean wOk = true, zOk = true;
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            int[] v = e.getValue();
            if (v.length != 3) wOk = false;
            int s = v[0] + v[1] + v[2];
            if (s < 1 || s > 6 || v[0] < 0 || v[1] < 0 || v[2] < 0 || v[0] > 3 || v[1] > 3 || v[2] > 3) zOk = false;
        }
        check("كل صف: ثلاثة أوزان 0..3 ومجموع 1..6", wOk && zOk);
        int famRows = 0;
        for (int[] v : m.values()) if (v[0] == 1 && v[1] == 1 && v[2] == 1) famRows++;
        check("صفوف العائلة الخليجية (1,1,1) موجودة", famRows >= 25);
        check("مفاتيح الجدول بنسخة normAr",
                m.containsKey(Dialect.normAr("ترى")) && m.containsKey(Dialect.normAr("ابغى")));
        Map<String, int[]> ro = m;
        try {
            ro.put("test", new int[]{1, 1, 1});
            check("markers() غير قابل للتعديل", false);
        } catch (UnsupportedOperationException ok) {
            check("markers() غير قابل للتعديل", true);
        } catch (Throwable t) {
            check("markers() غير قابل للتعديل", false);
        }
        check("كاشف بلا حالة: نتيجتان متتاليتان لمُدخلين مختلفين مستقلتان",
                !Dialect.detect("ايش تسوى دحين").equals(Dialect.detect("شخبارك حيل وايد")));

        // ---- curated gate set runs through the same engine -----------------------
        int ok = 0, tot = 0;
        try {
            for (String line : Files.readAllLines(Paths.get("assets/testset_dialects.txt"), java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t", 2);
                if (p.length != 2) continue;
                tot++;
                if (Dialect.detect(p[1]).dialect.equals(p[0])) ok++;
            }
        } catch (Exception e) {
            check("مجموعة البوابة مقروءة", false);
        }
        check("مجموعة البوابة الكاملة عبر نفس المحرك (64 صف، دقة ≥85%)", tot == 64 && ok >= 55);
        System.out.println("CURATED_SET " + ok + "/" + tot);
    }
}
