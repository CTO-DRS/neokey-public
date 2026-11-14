package com.hark.neokey;

/**
 * بطارية W71 — NumSmartTest: معيار الخروج حرفاً — جدول تحويلات
 * مرجعي مقاساً: الأرقام والتجميع الثلاثي والعملات والنسب بقيم gold
 * مثبتة، والقلم الهجري المعياري (1 محرم 1400هـ = 16 نوفمبر 1979م)
 * يُثبت، والذهاب/العودة يقاس على كل يوم في النطاق المعلن 1920–2100
 * بايتياً، والتواريخ الحساسة خارج النطاق تعض وتُرفض لا تخمَّن.
 * نقي JVM.
 */
public class NumSmartTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** ميلادي ← هجري ← ميلادي على يوم واحد — العودة بايتياً. */
    static boolean roundTrip(int y, int m, int d) {
        NumSmart.Hijri h = NumSmart.toHijri(y, m, d);
        if (h == null) return false;
        int[] g = NumSmart.hijriToGregorian(h.hy, h.hm, h.hd);
        return g != null && g[0] == y && g[1] == m && g[2] == d;
    }

    public static void main(String[] args) throws Exception {
        // ---- الجدول المرجعي للأرقام: قيم gold مثبتة ----------------------------
        check("digits: التطبيع الهندي→عربي بالجدول (٠١٢٣→0123 مع إبقاء غير الأرقام)",
                NumSmart.normalizeDigits("أموال ٠١٢٣ ريال").equals("أموال 0123 ريال"));
        check("digits: التحويل العربي→هندي بالجدول (2026→٢٠٢٦)",
                NumSmart.toArabicIndic("سنة 2026").equals("سنة ٢٠٢٦"));
        check("digits: التبادل الذهاب/العودة بايتياً على كل رقم",
                NumSmart.normalizeDigits(NumSmart.toArabicIndic("0123456789")).equals("0123456789"));
        check("group: التجميع الثلاثي بالفاصل «٬» (1234567→١٬٢٣٤٬٥٦٧)",
                NumSmart.groupArabic(1234567L).equals("١٬٢٣٤٬٥٦٧"));
        check("group: الصفر والسالب الموثقان (0→٠، −987→−٩٨٧)",
                NumSmart.groupArabic(0L).equals("٠") && NumSmart.groupArabic(-987L).equals("−٩٨٧"));
        check("decimal: العشري بالفاصلة «٫» (12.5→١٢٫٥) والمدخل الهندي يُطبَّع",
                NumSmart.decimalArabic("12.5").equals("١٢٫٥")
                        && NumSmart.decimalArabic("١٢٫٥".replace('٫', '.')).equals("١٢٫٥"));
        check("decimal: المدخل التالف يرفض موثقاً (NUM_BAD_INPUT)",
                NumSmart.decimalArabic("").equals(NumSmart.BAD_INPUT)
                        && NumSmart.decimalArabic("١٢x").equals(NumSmart.BAD_INPUT)
                        && NumSmart.decimalArabic(null).equals(NumSmart.BAD_INPUT));

        // ---- الجدول المرجعي للعملة والنسبة ------------------------------------
        check("currency: «١٬٢٣٤٫٥٠ ر.س» بالجدول (SAR gold)",
                NumSmart.formatCurrency("1234.50", "SAR").equals("١٬٢٣٤٫٥٠ ر.س"));
        boolean sixSyms = true;
        String[] codes = {"SAR", "USD", "EGP", "AED", "MAD", "DZD"};
        for (String c : codes) if (NumSmart.currencySymbol(c) == null) sixSyms = false;
        check("currency: العملات الست الموثقة كلها برموز عربية", sixSyms);
        check("currency: عملة خارج الجدول ترفض لا تخمَّن (EUR → NUM_BAD_INPUT)",
                NumSmart.formatCurrency("10", "EUR").equals(NumSmart.BAD_INPUT)
                        && NumSmart.currencySymbol("JPY") == null);
        check("percent: النسبة بالفاصلة العربية (45.2→٤٥٫٢٪)",
                NumSmart.formatPercent("45.2").equals("٤٥٫٢٪")
                        && NumSmart.formatPercent("١٠٠").equals("١٠٠٪"));

        // ---- الهجري: القلم المعياري يُثبت حسابياً ------------------------------
        NumSmart.Hijri e1 = NumSmart.toHijri(1979, 11, 16);
        check("hijri: القلم المعياري 1 محرم 1400هـ = 16 نوفمبر 1979م (ذهاب)",
                e1 != null && e1.hy == 1400 && e1.hm == 1 && e1.hd == 1);
        int[] e2 = NumSmart.hijriToGregorian(1400, 1, 1);
        check("hijri: القلم المعياري بالعودة (1400/1/1هـ → 1979/11/16م)",
                e2 != null && e2[0] == 1979 && e2[1] == 11 && e2[2] == 16);
        check("hijri: عرض القلم بالأرقام الهندية «١٤٠٠/٠١/٠١ هـ»",
                e1 != null && e1.display().equals("١٤٠٠/٠١/٠١ هـ"));

        // ---- الذهاب/العودة على كامل النطاق المعلن ------------------------------
        boolean full = true;
        for (int y = NumSmart.HIJRI_MIN_GREG; y <= NumSmart.HIJRI_MAX_GREG && full; y++) {
            for (int m = 1; m <= 12 && full; m++) {
                int dim = (m == 2) ? (NumSmart.isLeapG(y) ? 29 : 28) : ((m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31);
                for (int d = 1; d <= dim; d++) if (!roundTrip(y, m, d)) { full = false; break; }
            }
        }
        check("hijri: ذهاب/عودة بايتياً على كل يوم في النطاق المعلن 1920–2100 (~66 ألف يوم)", full);

        // ---- التواريخ الحساسة تعض وتُرفض لا تخمَّن -----------------------------
        check("hijri: خارج النطاق المعلن يرفض موثقاً (1899 و2101 → null)",
                NumSmart.toHijri(1899, 12, 31) == null && NumSmart.toHijri(2101, 1, 1) == null);
        check("hijri: مدخل تالف يرفض موثقاً (شهر 13، يوم 31 نيسان، يوم 30 شباط)",
                NumSmart.toHijri(2026, 13, 1) == null && NumSmart.toHijri(2026, 4, 31) == null
                        && NumSmart.toHijri(2026, 2, 30) == null);
        check("hijri: هجري تالف يرفض موثقاً (شهر 13، يوم 30 في شهر 29، سنة 0)",
                NumSmart.hijriToGregorian(1447, 13, 1) == null
                        && NumSmart.hijriToGregorian(1447, 4, 30) == null
                        && NumSmart.hijriToGregorian(0, 1, 1) == null);
        boolean ram = true;
        for (int y = 1400; y <= 1460; y++) if (NumSmart.lenMonthH(y, 9) != 30) ram = false;
        check("hijri: رمضان 30 يوماً دائماً في المواصفة (مقاس 61 سنة)", ram);
        boolean thul = true;
        for (int y = 1400; y <= 1460; y++)
            if (NumSmart.lenMonthH(y, 12) != (NumSmart.isLeapH(y) ? 30 : 29)) thul = false;
        check("hijri: ذو الحجة 30 في الكبيسة و29 في العاديّ (مقاس 61 سنة)", thul);

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
