package com.hark.neokey;

/**
 * W71 (roadmap v2): الأرقام الذكية NumSmart — «أرقامك بالعربية كما
 * تُقرأ». تبديل هندية/عربية للفواصل الرقمية، تنسيق عملة ونسبة مقاساً،
 * وتعميق أداة التاريخ الهجري — تحويل حسابي موثق أثناء الكتابة ضمن
 * شريط الأدوات. نقي JVM: لا شبكة، لا نموذج، لا حالة كائن — دالة نقية.
 *
 * القوانين:
 *   1. الأرقام الهندية (٠-٩ U+0660–U+0669) والعربية (0-9) تتبادل
 *      بحتمية، والتجميع الثلاثي في الاتجاهين مقاس بالجدول المرجعي
 *      — لا تنسيق مبتكر خارج الجدول.
 *   2. العملة والنسبة بصيغ عربية موثقة حتمية («١٬٢٣٤٫٥٠ ر.س»،
 *      «٤٥٫٢٪») — الفاصلة العشرية «٫» وفاصل الآلاف «٬».
 *   3. العملات موثقة بالجدول (SAR/USD/EGP/AED/MAD/DZD) — خارجها
 *      رفض موثق (NUM_BAD_INPUT) لا تخمين.
 *   4. الهجري تحويل حسابي بالمواصفة القياسية (التقويم الجدولي:
 *      دورة 30 سنة × 354 يوماً + 11 كبيساً = 10631 يوماً، الكبيسة
 *      في مواضع 2,5,7,10,13,16,18,21,24,26,29، الشهور مفردة 30
 *      وزوجية 29 وذي الحجة 30 في الكبيسة) — والقلم المعياري
 *      1 محرم 1400هـ = 16 نوفمبر 1979م يُثبت في الاختبار.
 *   5. النطاق المعلن 1920–2100 ميلادي؛ خارجه رفض موثق
 *      (HIJRI_OUT_OF_RANGE) لا تخمين، والسنة الهجرية أعلى قيد
 *      بالمواصفة (عمر النمط الدوري) ترفض كذلك.
 *   6. كل تحويل يختبر ذهاباً وعودة على كامل النطاق — اليوم بميلاده
 *      يعود بنفسه بايتياً؛ أي انكسار يعض.
 *   7. المدخل التالف (رقم ناقص، شهر 13، يوم 30 في شهر 29) يرفض
 *      موثقاً — لا افتراض صامت.
 */
public final class NumSmart {

    public static final String OUT_OF_RANGE = "HIJRI_OUT_OF_RANGE";
    public static final String BAD_INPUT = "NUM_BAD_INPUT";

    /** النطاق المعلن للتحويل الهجري الحسابي (ميلادي) — خارجه رفض لا تخمين. */
    public static final int HIJRI_MIN_GREG = 1920;
    public static final int HIJRI_MAX_GREG = 2100;

    static final char[] AR_DIGITS = {'٠','١','٢','٣','٤','٥','٦','٧','٨','٩'};

    /** مواضع السنوات الكبيسة في الدورة الثلاثينية (المواصفة الجدولية القياسية). */
    static final int[] LEAP_POS = {2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29};
    /** طول الدورة الثلاثينية بالأيام: 30×354 + 11 كبيساً = 10631. */
    static final long CYCLE_DAYS = 10631;
    /** القلم المعياري: 1 محرم 1400هـ = 16 نوفمبر 1979م (يُثبت في الاختبار). */
    static final long H1400_ABS = hijriAbs(1400, 1, 1);
    static final long G1979_11_16_ABS = gregAbs(1979, 11, 16);

    // ------------------------------------------------------------ الأرقام والتنسيق

    public static boolean isArabicIndic(char c) { return c >= '\u0660' && c <= '\u0669'; }

    /** تحويل كل الأرقام الهندية في النص إلى عربية (0-9) — بلا مساس بغيرها. */
    public static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isArabicIndic(c)) b.append((char) ('0' + (c - '\u0660')));
            else b.append(c);
        }
        return b.toString();
    }

    /** تحويل كل الأرقام العربية (0-9) في النص إلى هندية — بلا مساس بغيرها. */
    public static String toArabicIndic(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(AR_DIGITS[c - '0']);
            else b.append(c);
        }
        return b.toString();
    }

    /** عدد صحيح بالتجميع الثلاثي العربي — «١٬٢٣٤٬٥٧٧» (فاصل الآلاف «٬»). */
    public static String groupArabic(long n) {
        String d = Long.toString(Math.abs(n));
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            if (i > 0 && (d.length() - i) % 3 == 0) g.append('٬');
            g.append(d.charAt(i));
        }
        String out = toArabicIndic(g.toString());
        return n < 0 ? '−' + out : out;
    }

    /** عدد عشري بالعربية — الفاصلة العشرية «٫» والتجميع الثلاثي للجزء الصحيح. */
    public static String decimalArabic(String value) {
        String v = normalizeDigits(value == null ? "" : value.trim());
        if (v.isEmpty()) return BAD_INPUT;
        int dot = v.indexOf('.');
        String whole = dot < 0 ? v : v.substring(0, dot);
        String frac = dot < 0 ? "" : v.substring(dot + 1);
        if (whole.isEmpty() && frac.isEmpty()) return BAD_INPUT;
        for (int i = 0; i < whole.length(); i++)
            if (whole.charAt(i) < '0' || whole.charAt(i) > '9') return BAD_INPUT;
        for (int i = 0; i < frac.length(); i++)
            if (frac.charAt(i) < '0' || frac.charAt(i) > '9') return BAD_INPUT;
        StringBuilder b = new StringBuilder(groupArabic(Long.parseLong(whole.isEmpty() ? "0" : whole)));
        if (dot >= 0) { b.append('٫'); b.append(toArabicIndic(frac)); }
        return b.toString();
    }

    /** العملات الموثقة — رمزها العربي الحتمي؛ خارجها null (الرفض لا تخمين). */
    public static String currencySymbol(String code) {
        if ("SAR".equals(code)) return "ر.س";
        if ("USD".equals(code)) return "دولار";
        if ("EGP".equals(code)) return "ج.م";
        if ("AED".equals(code)) return "د.إ";
        if ("MAD".equals(code)) return "د.م";
        if ("DZD".equals(code)) return "د.ج";
        return null;
    }

    /** عملة بالعربية: «١٬٢٣٤٫٥٠ ر.س» — رمز خارج الجدول = NUM_BAD_INPUT. */
    public static String formatCurrency(String amount, String code) {
        String sym = currencySymbol(code);
        if (sym == null) return BAD_INPUT;
        String a = decimalArabic(amount);
        if (a.equals(BAD_INPUT)) return BAD_INPUT;
        return a + " " + sym;
    }

    /** نسبة بالعربية: «٤٥٫٢٪» — مقاسة، الفاصلة العربية الحتمية. */
    public static String formatPercent(String value) {
        String a = decimalArabic(value);
        if (a.equals(BAD_INPUT)) return BAD_INPUT;
        return a + '٪';
    }

    // ------------------------------------------------------------ الهجري الحسابي

    /** الأيام المطلقة منذ 0001-01-01 ميلادي (يوم 0) — بالحساب القياسي. */
    static long gregAbs(int y, int m, int d) {
        long days = 365L * (y - 1) + (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400;
        int[] len = {31, isLeapG(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (m < 1 || m > 12 || d < 1 || d > len[m - 1]) return -1;
        for (int i = 1; i < m; i++) days += len[i - 1];
        return days + d - 1;
    }

    static boolean isLeapG(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    static boolean isLeapH(int hy) {
        if (hy < 1) return false;
        int pos = ((hy - 1) % 30) + 1;
        for (int l : LEAP_POS) if (l == pos) return true;
        return false;
    }

    /** طول الشهر الهجري: المفردة 30، الزوجية 29، وذي الحجة 30 في الكبيسة. */
    public static int lenMonthH(int hy, int hm) {
        if (hy < 1 || hm < 1 || hm > 12) return -1;
        if (hm % 2 == 1) return 30;
        return (hm == 12 && isLeapH(hy)) ? 30 : 29;
    }

    /** الأيام المطلقة منذ 0001-01-01 ليوم هجري (سنة/شهر/يوم) — بحساب مغلق. */
    static long hijriAbs(int hy, int hm, int hd) {
        if (hy < 1 || lenMonthH(hy, hm) < 0 || hd < 1 || hd > lenMonthH(hy, hm)) return -1;
        long years = hy - 1;
        long cycles = years / 30, rem = years % 30;
        long days = cycles * CYCLE_DAYS;
        for (int i = 1; i <= rem; i++) days += isLeapH(i) ? 355 : 354;
        for (int i = 1; i < hm; i++) days += lenMonthH(hy, i);
        return days + hd - 1;
    }

    /** التاريخ الهجري: سنة/شهر/يوم معروفة الأعضاء. */
    public static final class Hijri {
        public final int hy, hm, hd;
        Hijri(int hy, int hm, int hd) { this.hy = hy; this.hm = hm; this.hd = hd; }
        /** العرض الموثق: «١٤٤٧/٠٥/١٢ هـ» — حتمي بالأرقام الهندية. */
        public String display() {
            return toArabicIndic(String.format("%04d", hy)) + '/' + toArabicIndic(String.format("%02d", hm))
                    + '/' + toArabicIndic(String.format("%02d", hd)) + " هـ";
        }
    }

    /** ميلادي → هجري بالحساب المغلق؛ خارج النطاق أو مدخل تالف = null (الرفض لا تخمين). */
    public static Hijri toHijri(int y, int m, int d) {
        long g = gregAbs(y, m, d);
        if (g < 0 || y < HIJRI_MIN_GREG || y > HIJRI_MAX_GREG) return null;
        long h = g - G1979_11_16_ABS + H1400_ABS;   // اليوم الهجري المطلق
        if (h < 0) return null;
        long cycles = h / CYCLE_DAYS, rem = h % CYCLE_DAYS;
        int hy = (int) (cycles * 30 + 1);
        while (rem >= 354 + (isLeapH(hy) ? 1 : 0)) { rem -= 354 + (isLeapH(hy) ? 1 : 0); hy++; }
        int hm = 1;
        while (rem >= lenMonthH(hy, hm)) { rem -= lenMonthH(hy, hm); hm++; }
        return new Hijri(hy, hm, (int) rem + 1);
    }

    /** هجري → ميلادي (سنة/شهر/يوم) — بالمصفوفة {y,m,d}؛ رفض موثق بالـ null. */
    public static int[] hijriToGregorian(int hy, int hm, int hd) {
        long h = hijriAbs(hy, hm, hd);
        if (h < 0) return null;
        long g = h - H1400_ABS + G1979_11_16_ABS;
        // أمشي بالسنوات الميلادية على الأيام المطلقة
        int y = (int) (g / 366);
        while (gregAbs(y + 1, 1, 1) <= g) y++;
        if (y < HIJRI_MIN_GREG - 1 || y > HIJRI_MAX_GREG) return null;
        while (gregAbs(y, 1, 1) > g) y--;
        int[] len = {31, isLeapG(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        long left = g - gregAbs(y, 1, 1);
        int m = 1;
        while (left >= len[m - 1]) { left -= len[m - 1]; m++; }
        return new int[]{y, m, (int) left + 1};
    }

    private NumSmart() { }
}
