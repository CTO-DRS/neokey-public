package com.hark.neokey;

/**
 * OTPShield — W65: درع الرموز «قانون المسح الذاتي». نقي JVM: صفر android.*.
 *
 * الخارطة: «كشف رمز التحقق (OTP) الواصل للحافظة + عداد انتهاء + مسح تلقائي
 * موثق من الحافظة والسجل بعد مهلة يضبطها المستخدم (الافتراضي 60 ثانية)»
 * + التكامل مع درع الحافظة الحالي (منطق Shield، سجل ClipStore) + قانون
 * وضع التخفي.
 *
 * ما يثبت هذا الصنف (الصرف لا الواجهة):
 *   • الكشف: أنماط موثقة حرفياً (المجموعة المرجعية assets/testset_otp.txt
 *     إيجابياتها وسلبياتها) — 4-8 أرقام متتالية، أو مجموعات مفصولة بشرطة
 *     أو مسافة (3+3، 3+3+3، 4+4) — مع سلبيات صريحة: السنوات، التواريخ،
 *     الأسعار، أرقام الطوارئ القصيرة، النص العادي.
 *   • العداد: expiryAt = capturedAt + timeoutSec·1000 — حساب حتمي بايتي.
 *   • المسح الذاتي الموثق: due() — بعد المهلة (وليس قبلها) يبقى الرمز
 *     «مستحق المسح» بالبايت؛ والقرار الصرف الوحيد الذي تبنى عليه الواجهة
 *     (الحافظة والسجل تمسح فعلياً عند due — موثق بالبطارية عبر dueAfter
 *     مع capturedAt/now).
 *   • التخفي (قانون 8): قراءة الحافظة تحليل — في التخفي الدرع معطّل كلياً:
 *     isOtpAllowed(incognito=true) = false بلا استثناء.
 *
 * الحدود: المهلة مسار مستخدم (otp_timeout، الافتراضي 60ث، الحدود 30..600)
 * يضبطها الواجهة؛ والكشف صارم بالأنماط الموثقة — لا تعميم يتجاوزها.
 */
public final class OTPShield {

    /** المهلة الافتراضية بالثواني — القياس الموثق في الخارطة. */
    public static final int DEFAULT_TIMEOUT = 60;
    /** حدود المهلة التي تضبطها الواجهة (ثوانٍ). */
    public static final int MIN_TIMEOUT = 30;
    public static final int MAX_TIMEOUT = 600;

    private OTPShield() { }

    /** هل المسح الذاتي مسموح؟ التخفي يمنعه كلياً (قراءة الحافظة تحليل). */
    public static boolean isOtpAllowed(boolean incognito, boolean prefOn) {
        return !incognito && prefOn;
    }

    /** المهلة المقيدة: خارج الحدود تعود للحد (فشل مغلق). */
    public static int clampTimeout(int sec) {
        if (sec < MIN_TIMEOUT) return DEFAULT_TIMEOUT;
        if (sec > MAX_TIMEOUT) return MAX_TIMEOUT;
        return sec;
    }

    /** لحظة الانتهاء الحتمية: capturedAt + مهلة·1000 (بايت-دقيقاً). */
    public static long expiryAt(long capturedAt, int timeoutSec) {
        return capturedAt + clampTimeout(timeoutSec) * 1000L;
    }

    /**
     * الكشف على نمط OTP موثق: 4-8 أرقام متتالية، أو مجموعات مفصولة بشرطة
     * أو مسافة (3+3، 3+3+3، 4+4). الأرقام داخل كلمات أطول لا تُحتسب.
     */
    public static boolean isOtp(String text) {
        if (text == null) return false;
        return findOtp(text) != null;
    }

    /** أول رمز مطابق (للعرض والمسح الدقيق) — أو null. الغلاف الموثق:
     *  متتالي 6-8 أرقام ASCII، أو تجزيء 3+3 / 3+3+3 / 4+4 بفاصلة شرطة أو
     *  مسافة — والحدود بعد المجموعة ليست رقماً ولا فاصلاً. خارج هذا
     *  الغلاف لا شيء يُطابق (سنوات/أسعار/هواتف/أرقام عربية-هندية مستثناة
     *  صراحة — موثقة بالمجموعة المرجعية). */
    public static String findOtp(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.trim();
        int n = t.length();
        for (int i = 0; i < n; i++) {
            if (!isDigit(t.charAt(i))) continue;
            if (i > 0 && isDigit(t.charAt(i - 1))) continue;   // داخل رقم أطول
            int j = i;
            while (j < n && isDigit(t.charAt(j))) j++;
            int len = j - i;
            // 1) المتتالي الموثق: 6-8 — الحد: نهاية النص أو حرف ليس رقماً
            //    (فاصل بلا رقم بعده مقبول: «776655 لا تشاركه» يُكتشف،
            //    و«0100 1234 567» يبقى مرفوضاً لأن الفاصل يتبعه رقم)
            if (len >= 6 && len <= 8 && (j >= n || !isDigit(t.charAt(j)))) {
                return t.substring(i, j);
            }
            // 2) التجزيء الموثق فقط: 3+3 / 3+3+3 / 4+4 — وطول المجموعة 2
            //    أو 5+ مستبعد صراحة (تواريخ 31-12، أرقام طلبات 12345-6789)
            if ((len == 3 || len == 4) && j < n && isSep(t.charAt(j))) {
                StringBuilder b = new StringBuilder(t.substring(i, j));
                int groups = 1;
                int maxGroups = len == 3 ? 3 : 2;   // 3+3(+3) أو 4+4
                while (groups < maxGroups && j < n && isSep(t.charAt(j))) {
                    int k = j + 1;
                    int l2 = 0;
                    while (k < n && isDigit(t.charAt(k))) { l2++; k++; }
                    if (l2 != len) break;
                    b.append(t, j, k);
                    groups++;
                    j = k;
                }
                // 3+3 يقبل مجموعتين أو ثلاثاً (الجشع لا يمنع القبول)
                if ((len == 3 && groups >= 2) || (len == 4 && groups == 2)) {
                    if (j >= n || !isDigit(t.charAt(j))) return b.toString();
                }
            }
            while (i < n - 1 && isDigit(t.charAt(i + 1))) i++;
        }
        return null;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static boolean isSep(char c) { return c == '-' || c == ' '; }

    /**
     * المسح الذاتي الموثق: الرمز مستحق المسح فقط إذا (كشف OTP) وكانت الحالة
     * مسموحة (لا تخفي) والوقت بلغ الانتهاء الحتمي — قبل المهلة لا يُمسّ شيء.
     */
    public static boolean due(String text, long capturedAt, long now,
                              int timeoutSec, boolean incognito, boolean prefOn) {
        if (!isOtpAllowed(incognito, prefOn)) return false;
        if (!isOtp(text)) return false;
        return now >= expiryAt(capturedAt, timeoutSec);
    }

    /** سطر التدقيق المحلي — أرقام وأوسام فقط، بلا محتوى الرمز أبداً. */
    public static String auditLine(String action, int timeoutSec, long epochMs) {
        return "OTPSHIELD\t" + action + "\ttimeout=" + timeoutSec + "\t" + epochMs;
    }
}
