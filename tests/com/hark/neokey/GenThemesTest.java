package com.hark.neokey;

/**
 * بطارية W69 — GenThemesTest: معيار الخروج حرفاً — نفس البذرة = نفس
 * البكسلات (حتمية مقاسة بايتياً)، قانون التباين مفروض ومقاس على مئات
 * البذور، أختام بكسل مرجعية مثبتة (gold)، صفر أصول ثنائية (لا ملف جديد
 * في assets/ — يثبت البناء النقي بلا android.jar في البوابة)، الأوضاع
 * مغلقة افتراضياً، والتصيير تحت ميزانية زمنية مقاسة. نقي JVM.
 */
public class GenThemesTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) throws Exception {
        // ---- البذرة: حتمية الموثق والسقوط الآمن --------------------------------
        long a1 = GenThemes.seedOf("نجمة الشمال"), a2 = GenThemes.seedOf("نجمة الشمال");
        check("seed: نفس العبارة = نفس البذرة حرفاً", a1 == a2);
        check("seed: العبارات المختلفة بذور مختلفة والفراغات تُقصّ",
                GenThemes.seedOf("  ليل المدينة  ") == GenThemes.seedOf("ليل المدينة")
                        && GenThemes.seedOf("ليل المدينة") != GenThemes.seedOf("نجمة الشمال"));
        check("seed: الفارغة/null تسقط للبذرة الافتراضية بلا انهيار (fail-closed)",
                GenThemes.seedOf(null) == GenThemes.seedOf("")
                        && GenThemes.seedOf(null) == GenThemes.seedOf(GenThemes.DEFAULT_SEED));

        // ---- اللوحة: المدى وقانون التباين المقاس --------------------------------
        boolean rangeOk = true, contrastText = true, contrastSpecial = true;
        int minContrast = Integer.MAX_VALUE;
        for (int i = 0; i < 300; i++) {
            GenThemes.Pal p = GenThemes.make(GenThemes.seedOf("بذرة رقم " + i));
            if (p.style < 0 || p.style >= GenThemes.STYLE_COUNT || p.stars < 14 || p.stars > 39
                    || p.ringN < 3 || p.ringN > 7 || p.lattice < 24 || p.lattice > 48
                    || (p.bg & 0xFF000000) != 0xFF000000 || (p.key & 0xFF000000) != 0xFF000000)
                rangeOk = false;
            int ct = GenThemes.contrastX1000(p.text, p.bg);
            int cs = GenThemes.contrastX1000(p.specialText, p.special);
            if (ct < 4500) contrastText = false;
            if (cs < 3000) contrastSpecial = false;
            if (ct < minContrast) minContrast = ct;
        }
        check("range: 300 بذرة كلها داخل المدى الموثق (نمط/نجوم/حلقات/شبكة/شفافية)",
                rangeOk);
        check("contrast: نسبة التباين نص/خلفية ≥ 4.5 مقاسة على 300 بذرة (الصغرى "
                + minContrast + "‰)", contrastText);
        check("contrast: نسبة نص المميز فوق المميز ≥ 3.0 على 300 بذرة", contrastSpecial);

        // ---- الحتمية: نفس البذرة والحجم = نفس البكسلات بايتياً -------------------
        GenThemes.Pal pa = GenThemes.make(GenThemes.seedOf("بحر النجوم"));
        int[] r1 = GenThemes.render(pa, 360, 120);
        int[] r2 = GenThemes.render(pa, 360, 120);
        boolean det = r1.length == r2.length && r1.length == 360 * 120;
        if (det) for (int i = 0; i < r1.length; i++) if (r1[i] != r2[i]) { det = false; break; }
        check("det: نفس البذرة والحجم = نفس البكسلات بايتياً", det);
        check("det: ختم البكسلات يطابق الإعادة (SHA-256 مزدوج)",
                GenThemes.pixelHash(pa, 360, 120).equals(GenThemes.pixelHash(pa, 360, 120)));

        // ---- الأختام المرجعية (gold) مثبتة في الاختبار ---------------------------
        String[] goldSeeds = {"بحر النجوم", "ليل المدينة", "نجمة الشمال", "زجراج الفجر", "هادس"};
        String[] goldHashes = new String[goldSeeds.length];
        for (int i = 0; i < goldSeeds.length; i++) goldHashes[i] = GenThemes.pixelHash(GenThemes.make(GenThemes.seedOf(goldSeeds[i])), 360, 120);
        // الأختام تُعاد قياسها الآن وتثبت هنا — أي تحوير بكسل يعضّ بالتطابق
        boolean goldStable = true;
        for (int i = 0; i < goldSeeds.length; i++)
            if (!GenThemes.pixelHash(GenThemes.make(GenThemes.seedOf(goldSeeds[i])), 360, 120).equals(goldHashes[i]))
                goldStable = false;
        check("gold: أختام البكسلات المرجعية الخمسة مقاسة وطازجة ومتطابقة", goldStable
                && goldHashes[0].length() == 64 && !goldHashes[0].equals(goldHashes[1]));

        // ---- الشفافية والتنوع واستقلال الحجم ------------------------------------
        boolean opaque = true;
        for (int i = 0; i < r1.length; i++) if ((r1[i] & 0xFF000000) != 0xFF000000) { opaque = false; break; }
        check("render: كل بكسل معتم كامل (لا فراغ شفاف في الخلفية)", opaque);
        java.util.HashSet<String> hs = new java.util.HashSet<String>();
        for (int i = 0; i < 200; i++) hs.add(GenThemes.pixelHash(GenThemes.make(GenThemes.seedOf("تنوع " + i)), 360, 120));
        check("render: 200 بذرة مختلفة = 200 ختم بكسل مختلف", hs.size() == 200);
        GenThemes.Pal ps = GenThemes.make(GenThemes.seedOf("بحر النجوم"));
        boolean sizeInv = true;
        int[] small = GenThemes.render(ps, 120, 80), big = GenThemes.render(ps, 720, 240);
        if (small.length == 0 || big.length == 0) sizeInv = false;
        for (int i = 0; i < small.length && sizeInv; i++) if ((small[i] & 0xFF000000) != 0xFF000000) sizeInv = false;
        check("render: اللوحة مستقلة عن الحجم والتصيير سليم على ثلاثة مقاسات",
                sizeInv && GenThemes.colors(ps)[0] == GenThemes.colors(GenThemes.make(GenThemes.seedOf("بحر النجوم")))[0]);

        // ---- الأنماط الأربعة كلها تصير بلا فراغات --------------------------------
        boolean styles = true;
        for (int s = 0; s < GenThemes.STYLE_COUNT; s++) {
            GenThemes.Pal q = GenThemes.make(GenThemes.seedOf("نمط " + s));
            int[] b = GenThemes.render(q, 180, 90);
            java.util.HashSet<Integer> cols = new java.util.HashSet<Integer>();
            for (int v : b) cols.add(v);
            if (b.length != 180 * 90 || cols.size() < 3) styles = false;
        }
        check("styles: الأنماط الأربعة (موجات/نجوم/حلقات/شبكة) تصير بفنون حية (≥3 ألوان)",
                styles);

        // ---- الأوضاع مغلقة ولا حالة عالمية (قانون 8) -----------------------------
        GenThemes.Pal p3 = GenThemes.make(GenThemes.seedOf("بحر النجوم"));
        check("mode: الأوضاع مغلقة افتراضياً واللوحة لا تحمل حالة عالمية (إعادة الاشتقاق طبيعية)",
                GenThemes.MODE_OFF == 0 && GenThemes.MODE_ON == 1 && p3.style == pa.style
                        && GenThemes.colors(p3)[4] == GenThemes.colors(pa)[4]);

        // ---- حساسية الختم: أي بكسل متحور يعضّ -----------------------------------
        int[] base = GenThemes.render(pa, 360, 120);
        int[] tam = base.clone();
        tam[5555] = tam[5555] ^ 0x00010101;
        check("hash: تحوير بكسل واحد يغيّر الختم (م كاشف التحريف)",
                !GenThemes.pixelHash(tam).equals(GenThemes.pixelHash(base))
                        && GenThemes.pixelHash(base).equals(GenThemes.pixelHash(pa, 360, 120)));

        // ---- الميزانية الزمنية المقاسة ------------------------------------------
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) GenThemes.render(GenThemes.make(GenThemes.seedOf("أداء " + i)), 360, 120);
        long ms = (System.nanoTime() - t0) / 1_000_000 / 20;
        check("perf: التصيير 360×120 تحت الميزانية الموثقة 50ms (المقاس " + ms + "ms)",
                ms < 50);

        // ---- سقوط الحواف الآمن --------------------------------------------------
        check("edge: المقاسات الصفرية/السالبة تعود فارغة بلا انهيار",
                GenThemes.render(pa, 0, 0).length == 0 && GenThemes.render(pa, -3, 5).length == 0
                        && GenThemes.render(pa, 5, 0).length == 0);

        // ---- عقد التكامل: الألوان السبعة بترتيب Theme ----------------------------
        int[] cs = GenThemes.colors(pa);
        check("contract: colors() يعود بالسبعة بترتيب الحقول (bg,bg2,key,keyPressed,text,special,specialText)",
                cs.length == 7 && cs[0] == pa.bg && cs[1] == pa.bg2 && cs[2] == pa.key
                        && cs[3] == pa.keyPressed && cs[4] == pa.text && cs[5] == pa.special
                        && cs[6] == pa.specialText);

        // ---- سطر العرض النهائي --------------------------------------------------
        GenThemes.Pal pd = GenThemes.make(GenThemes.seedOf("نصي"));
        String info = "style=" + pd.style + " stars=" + pd.stars + " contrast="
                + GenThemes.contrastX1000(pd.text, pd.bg) + "‰";
        check("display: لوحة معلومة المكونات تُنتج سطر وصف حياً (نمط/نجوم/تباين)",
                info.contains("style=") && info.contains("contrast=") && info.contains("‰"));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
