package com.hark.neokey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * كشف اللهجة من الكتابة — المرحلة 1، البند C1 (الأسبوع 5). نقي JVM: صفر android.*
 *
 * الخارطة: «كشف تلقائي للهجة المستخدم من كتابته … كشف لهجة بدقة 85%+».
 * المخرجات: أربع فئات — hijazi (حجازي) / najdi (نجدي) / gulf (خليجي عام:
 * الكويت، الإمارات، قطر، البحرين، عمان) / msa (فصحى أو لا دليل).
 *
 * الطريقة: جدول علامات موزون (dictsrc/dialect_markers.tsv هو مصدر الحقيقة،
 * والجدول هنا مرآة تتحقق منها البوابة حرفياً — أي انحراف = بناء أحمر):
 *   • علامة مميزة (وزن 3 أو 2 ولها صفر في العمودين الآخرين) تدفع لهجتها وحدها.
 *   • كلمة ميل (وزنان غير صفرين) تدفع لهجتها الأقوى وتلمّح للجار.
 *   • كلمة عائلة (1,1,1) تدفع للعائلة الخليجية بلا تخصيص.
 * القرار (حتمي بالكامل، نفس المدخل = نفس النتيجة على أي جهاز):
 *   1. لا دليل خليجي إطلاقاً  → msa.
 *   2. هامش الأعلى على الثاني ≥ 2 → اللهجة الأعلى.
 *   3. دليل خليجي والهامش أقل → gulf («خليجي عام» — إجابة العائلة الصادقة:
 *      الكتابة خليجية لكن لا يكفي لإسنادها لهجة بعينها).
 * الثقة = الأعلى ÷ مجموع الدليل (0..1)، وللفصحى 1.0 عند صفر دليل.
 *
 * المطابقة على الكلمة الكاملة بعد normAr فقط — بلا stemming؛ الفجوة (الصيغ
 * المرفومة غير الموجودة في الجدول) موثقة في تقرير الأسبوع لضبط الأسبوع 6.
 */
public final class Dialect {

    public static final String HIJAZI = "hijazi";
    public static final String NAJDI = "najdi";
    public static final String GULF = "gulf";
    public static final String MSA = "msa";

    /** نتيجة الكشف: اللهجة، الثقة 0..1، عدد الكلمات الدالة. */
    public static final class Result {
        public final String dialect;
        public final double confidence;
        public final int hits;

        Result(String dialect, double confidence, int hits) {
            this.dialect = dialect;
            this.confidence = confidence;
            this.hits = hits;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Result)) return false;
            Result r = (Result) o;
            return dialect.equals(r.dialect)
                    && Double.doubleToLongBits(confidence) == Double.doubleToLongBits(r.confidence)
                    && hits == r.hits;
        }

        @Override public int hashCode() {
            return dialect.hashCode() * 31 + (int) (confidence * 1000) * 7 + hits;
        }

        @Override public String toString() {
            return dialect + String.format("(%.2f,h%d)", confidence, hits);
        }
    }

    /** نوافذ الفحص: آخر كلمات الجلسة — الكشف يعتمد الحديث، لا كامل الرسالة. */
    private static final int WINDOW = 12;
    /** هامش الحسم: أقل من هذا = إجابة العائلة gulf. */
    private static final int DECIDE_MARGIN = 2;

    // ---- جدول العلامات: normAr(كلمة) → {hijazi, najdi, gulf} --------------------
    // مرآة حرفية لـ dictsrc/dialect_markers.tsv — ci/dialect_gate.sh يفحص التطابق خلية-خلية.
    private static final String[] M_W = {
            // مميزة حجازي
            "دحين", "دحينه", "يبه", "يمه", "ايش", "ايشو", "ايشك",
            "مليح", "مليحه", "هين", "هينه", "منوره", "مين",
            // مميزة خليجي عام
            "وايد", "خوش", "شخبارك", "شخبارج", "شخباركم",
            "حيل", "شنو", "منو", "بعدني", "عيل", "تدرين", "تدريين",
            // مميزة نجدي
            "ذلحين",
            // ميل نجدي
            "كذا", "كذي", "تري", "تراه", "ترانا", "تراني", "مب",
            "وش", "وشو", "زين", "زينه", "علومك", "وشلون", "طاري", "عقب", "اللحين",
            // عائلة خليجية
            "الحين", "وين", "وينك", "وينكم", "شلون", "شلونك", "شلونكم",
            "ابغي", "ابغاك", "ابي", "ابيك", "تبغي", "تبي", "يبي", "يبغي", "نبي",
            "عيال", "عيالي", "لين", "عشان", "علشان", "شوي", "مو",
            "ادري", "مدري", "ماادري", "دريت", "ديره", "الديره",
            "سالفه", "السالفه", "فديتك", "خوي", "اخوي", "تكفي",
            "هلا", "ياهلا", "كفو", "معليش",
    };
    private static final int[][] M_V = {
            //        h  n  g
            new int[]{3, 0, 0}, new int[]{3, 0, 0}, new int[]{3, 0, 0}, new int[]{3, 0, 0},
            new int[]{3, 0, 0}, new int[]{3, 0, 0}, new int[]{3, 0, 0},
            new int[]{2, 0, 0}, new int[]{2, 0, 0}, new int[]{2, 0, 0}, new int[]{2, 0, 0},
            new int[]{2, 0, 0}, new int[]{2, 0, 0},
            new int[]{0, 0, 3}, new int[]{0, 0, 3}, new int[]{0, 0, 3}, new int[]{0, 0, 3},
            new int[]{0, 0, 3},
            new int[]{0, 0, 2}, new int[]{0, 0, 2}, new int[]{0, 0, 2}, new int[]{0, 0, 2},
            new int[]{0, 0, 2}, new int[]{0, 0, 2}, new int[]{0, 0, 2},
            new int[]{0, 3, 0},
            new int[]{0, 3, 1}, new int[]{0, 3, 1}, new int[]{0, 3, 1}, new int[]{0, 3, 1},
            new int[]{0, 3, 1}, new int[]{0, 3, 1}, new int[]{0, 3, 1},
            new int[]{0, 3, 2}, new int[]{0, 2, 2}, new int[]{0, 2, 2}, new int[]{0, 2, 2},
            new int[]{0, 2, 1}, new int[]{0, 2, 1}, new int[]{0, 2, 1}, new int[]{0, 2, 1},
            new int[]{0, 2, 1},
            new int[]{1, 2, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
            new int[]{1, 1, 1}, new int[]{1, 1, 1}, new int[]{1, 1, 1},
    };
    // فهرس المفاتيح المبنى مرة واحدة (ثابت، بلا تخصيص وقت كشف)
    private static final Map<String, int[]> MARKERS = new HashMap<>();
    /** ترتيب الفئات الثابت — كسر التعادل حتمي: الأسبق في هذا الترتيب يفوز. */
    private static final String[] ORDER = {HIJAZI, NAJDI, GULF};

    static {
        if (M_W.length != M_V.length) throw new IllegalStateException("marker table mismatch");
        for (int i = 0; i < M_W.length; i++) MARKERS.put(normAr(M_W[i]), M_V[i]);
    }

    /** مرآة غير قابلة للتعديل — للبوابة والاختبارات. */
    public static Map<String, int[]> markers() {
        return Collections.unmodifiableMap(MARKERS);
    }

    private Dialect() { }

    /**
     * التطبيع العربي الموحد للمفاتيح: بلا تشكيل/تطويل، أإآ→ا، ة→ه، ى→ي، ؤ→و، ئ→ي.
     * أقسو من norm الفصحى عمداً: كتابة اللهجات تهمل الهمزات والتاء المربوطة.
     */
    public static String normAr(String w) {
        if (w == null) return "";
        StringBuilder b = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= 0x064B && c <= 0x0652) || c == 0x0640) continue; // تشكيل + تطويل
            switch (c) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c = 'ا'; break;
                case 'ة': c = 'ه'; break;
                case 'ى': c = 'ي'; break;
                case 'ؤ': c = 'و'; break;
                case 'ئ': c = 'ي'; break;
                default: break;
            }
            b.append(c);
        }
        return b.toString();
    }

    /** تقطيع نص حر: كلمات عربية متتالية (الترقيم/اللاتيني/الأرقام فواصل). */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean arabic = (c >= 0x0621 && c <= 0x063A) || (c >= 0x0641 && c <= 0x064A)
                    || (c >= 0x064B && c <= 0x0652) || c == 0x0640 || c == 0x0671;
            if (arabic) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** الكشف من نص حر (آخر WINDOW كلمة فقط). */
    public static Result detect(String text) {
        List<String> w = tokenize(text);
        int from = Math.max(0, w.size() - WINDOW);
        return detectWords(w.subList(from, w.size()));
    }

    /** الكشف من كلمات مكتوبة (مسار الخدمة مستقبلاً — الأسبوع 6). */
    public static Result detectWords(List<String> words) {
        int h = 0, n = 0, g = 0, hits = 0;
        if (words != null) {
            int from = Math.max(0, words.size() - WINDOW);
            for (int i = from; i < words.size(); i++) {
                int[] v = MARKERS.get(normAr(words.get(i)));
                if (v == null) continue;
                h += v[0]; n += v[1]; g += v[2];
                hits++;
            }
        }
        int total = h + n + g;
        if (total == 0) return new Result(MSA, 1.0, 0);
        int[] sc = {h, n, g};
        int top = 0;
        for (int i = 1; i < 3; i++) if (sc[i] > sc[top]) top = i;   // حتمي: التعادل يبقى بالأسبق
        int second = -1;
        for (int i = 0; i < 3; i++) if (i != top && (second < 0 || sc[i] > sc[second])) second = i;
        if (sc[top] - sc[second] < DECIDE_MARGIN) {
            // دليل خليجي بلا حسم — «خليجي عام» هي الإجابة الصادقة للعائلة
            return new Result(GULF, (double) sc[top] / total, hits);
        }
        String d = ORDER[top];
        return new Result(d, (double) sc[top] / total, hits);
    }

    // ---------------------------------------------- W90: dialect synonyms
    // assets/dialect_synonyms.txt: فصحى<TAB>حجازي<TAB>نجدي<TAB>خليجي, alternatives
    // in a cell joined by «،». A long-press on a member reveals the rest of its row.
    private static Map<String, String> syn;

    static boolean synReady() { return syn != null; }

    static void synLoad(String text) {
        Map<String, String> m = new HashMap<>();
        if (text != null)
            for (String line : text.split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#' || line.indexOf('\t') < 0) continue;
                for (String w : line.split("[\t،]")) if (!m.containsKey(normAr(w.trim()))) m.put(normAr(w.trim()), line);
            }
        syn = m;
    }

    /** W95/W96: «triggers TAB text» rows (assets/dialect_proverbs.txt, dialect_soften.txt)
     *  as a normAr(trigger) → text map; '#' lines and malformed rows are skipped. */
    /** W100: the typed word that earns today's date chip (📅) — اليوم / التاريخ / هجري. */
    static boolean dateWord(String n) {
        return "اليوم".equals(n) || "التاريخ".equals(n) || "تاريخ".equals(n) || "هجري".equals(n) || "الهجري".equals(n);
    }

    private static final String[] N1 = {"", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة",
            "عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"};
    private static final String[] N10 = {"", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون"};
    private static final String[] N100 = {"", "مائة", "مئتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة"};

    private static String under1000(int n) {
        String h = N100[n / 100], r;
        n %= 100;
        r = n < 20 ? N1[n] : (n % 10 == 0 ? N10[n / 10] : N1[n % 10] + " و" + N10[n / 10]);
        return h.isEmpty() ? r : r.isEmpty() ? h : h + " و" + r;
    }

    private static String scaled(int n, String one, String two, String few, String many) {
        if (n == 1) return one;
        if (n == 2) return two;
        if (n <= 10) return under1000(n) + " " + few;
        int t = n % 100;
        return under1000(n) + " " + (t >= 11 ? many + "ًا" : many);
    }

    /** W101 «الأرقام كتابةً»: a typed whole number (10 … 999,999,999, Latin or Arabic-Indic
     *  digits, no leading zero) → its Arabic words (1500 → ألف وخمسمائة); else null. */
    static String numWords(String t) {
        if (t == null || t.length() < 2 || t.length() > 9) return null;
        int v = 0;
        for (int i = 0; i < t.length(); i++) {
            int d = Character.digit(t.charAt(i), 10);
            if (d < 0 || (i == 0 && d == 0)) return null;
            v = v * 10 + d;
        }
        int m = v / 1000000, k = v / 1000 % 1000, u = v % 1000;
        StringBuilder b = new StringBuilder();
        if (m > 0) b.append(scaled(m, "مليون", "مليونان", "ملايين", "مليون"));
        if (k > 0) b.append(b.length() > 0 ? " و" : "").append(scaled(k, "ألف", "ألفان", "آلاف", "ألف"));
        if (u > 0) b.append(b.length() > 0 ? " و" : "").append(under1000(u));
        return b.toString();
    }

    /** W102 «المبلغ كتابةً»: «1500» + «ريال» (ريالات/ريالًا) → «فقط ألف وخمسمائة ريال سعودي لا غير»
     *  (the cheque/invoice form); W103 adds halalas «1500.50» → «… ريال وخمسون هللة لا غير». */
    private static final String[] F3 = {"ثلاث", "أربع", "خمس", "ست", "سبع", "ثماني", "تسع", "عشر"};

    private static String intWords(String n) {
        return n.length() == 1 && Character.digit(n.charAt(0), 10) > 0 ? N1[Character.digit(n.charAt(0), 10)] : numWords(n);
    }

    /** W103/W104: the fraction tail (1 … 99) in the cheque register; fem picks the 3–10 number form. */
    private static String sub(int h, String one, String two, String few, String many, boolean fem) {
        if (h == 1) return one;
        if (h == 2) return two;
        if (h <= 10) return (fem ? F3[h - 3] : N1[h]) + " " + few;
        return under1000(h) + " " + many;
    }

    // W104: currency families {unit-prefix, whole name, 1 sub, 2 subs, 3–10 subs, 11+ sub, fem}
    private static final String[][] CUR = {
            {"ريال", "ريال سعودي", "هللة واحدة", "هللتان", "هللات", "هللة", "f"},
            {"دولار", "دولار أمريكي", "سنت واحد", "سنتان", "سنتات", "سنت", ""},
            {"درهم", "درهم إماراتي", "فلس واحد", "فلسان", "فلوس", "فلس", ""},
            {"دراهم", "درهم إماراتي", "فلس واحد", "فلسان", "فلوس", "فلس", ""}};

    static String amountWords(String num, String unit) {
        if (num == null || unit == null) return null;
        String u = normAr(unit).replace("ً", "");
        String[] c = null;
        for (String[] x : CUR) if (u.startsWith(x[0])) { c = x; break; }
        if (c == null) return null;
        int dot = Math.max(num.indexOf('.'), num.indexOf('٫'));
        String ip = dot < 0 ? num : num.substring(0, dot), fp = dot < 0 ? "" : num.substring(dot + 1);
        if (dot >= 0 && (fp.isEmpty() || fp.length() > 2 || ip.isEmpty())) return null;
        int h = 0;
        for (int i = 0; i < fp.length(); i++) {
            int d = Character.digit(fp.charAt(i), 10);
            if (d < 0) return null;
            h = h * 10 + d;
        }
        if (fp.length() == 1) h *= 10;
        String w = ip.equals("0") || ip.equals("٠") ? "" : intWords(ip);
        if (w == null || (w.isEmpty() && h == 0)) return null;
        String t = h == 0 ? "" : sub(h, c[2], c[3], c[4], c[5], !c[6].isEmpty());
        String whole = c[1].substring(0, c[1].indexOf(' '));
        String out = w.isEmpty() ? t : w + " " + (h > 0 ? whole + " و" + t : c[1]);
        return "فقط " + out + " لا غير";
    }

    /** W105 «المحوّل الهجري»: a typed date (25/12/2026, 2026-12-25, ١٤٤٨/٧/٥) → {y, m, d, hijri?1:0};
     *  years 1900–2100 are Gregorian (day checked against the month, leap years kept), 1300–1500
     *  Hijri (day ≤ 30); anything else null. */
    static int[] parseDate(String t) {
        if (t == null) return null;
        String[] p = t.split("[/\\-]", -1);
        if (p.length != 3) return null;
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (p[i].isEmpty() || p[i].length() > 4) return null;
            for (int j = 0; j < p[i].length(); j++) {
                int d = Character.digit(p[i].charAt(j), 10);
                if (d < 0) return null;
                v[i] = v[i] * 10 + d;
            }
        }
        int y, m, d;
        if (p[0].length() == 4 && p[2].length() <= 2) { y = v[0]; m = v[1]; d = v[2]; }
        else if (p[2].length() == 4 && p[0].length() <= 2) { y = v[2]; m = v[1]; d = v[0]; }
        else return null;
        if (m < 1 || m > 12 || d < 1) return null;
        if (y >= 1300 && y <= 1500) return d <= 30 ? new int[]{y, m, d, 1} : null;
        if (y < 1900 || y > 2100) return null;
        int[] ml = {31, (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return d <= ml[m - 1] ? new int[]{y, m, d, 0} : null;
    }

    // ---- W107 «ميزان الشعر» 🎼: the meter (البحر) of a typed hemistich, by the classical feet.
    // 1 = متحرك, 0 = ساكن. Each meter is its feet, each foot its common variants (زحافات وعلل).
    static final String[] METER_NAMES = {"الطويل", "البسيط", "الوافر", "الكامل", "الرجز", "الرمل", "الخفيف",
            "المتقارب", "المتدارك", "السريع", "الهزج", "المجتث", "المنسرح"};
    static final String[] METER_FEET = {"فعولن مفاعيلن فعولن مفاعلن", "مستفعلن فاعلن مستفعلن فاعلن", "مفاعلتن مفاعلتن فعولن",
            "متفاعلن متفاعلن متفاعلن", "مستفعلن مستفعلن مستفعلن", "فاعلاتن فاعلاتن فاعلن", "فاعلاتن مستفع لن فاعلاتن",
            "فعولن فعولن فعولن فعولن", "فاعلن فاعلن فاعلن فاعلن", "مستفعلن مستفعلن فاعلن", "مفاعيلن مفاعيلن",
            "مستفع لن فاعلاتن", "مستفعلن مفعولات مستفعلن"};
    private static final String FAU = "11010|1101", MAF = "1101010|110110|110101", FAE = "10110|1110",
            MUS = "1010110|110110|+101110|++11110", FAT = "1011010|111010|+101101|+11101", MUT = "1110110|1010110",
            MFL = "1101110|1101010", MSL = "1010110|110110";
    private static final String[][] METERS = {
            {FAU, MAF, FAU, "110110|1101010"}, {MUS, FAE, MUS, "1110|1010|10110"}, {MFL, MFL, "11010"},
            {MUT, MUT, MUT + "|111010|101010"}, {MUS, MUS, MUS}, {FAT, FAT, "10110|1110"},
            {FAT, MSL, "1011010|111010|101010|10110"}, {FAU, FAU, FAU, "11010|1101|110"},
            {FAE + "|1010", FAE + "|1010", FAE + "|1010", FAE + "|1010"}, {MUS, MUS, "10110|1010"}, {MAF, MAF},
            {MSL, FAT + "|1010"}, {MUS, "1010101|+110101|+101101", "1010110|++11110"}};
    // a rarer meter needs a clearer reading to win a near tie (المتدارك، الهزج، المجتث، المنسرح)
    private static final String RARE = "\u0008\n\u000b\u000c";
    private static List<int[]> meterPen;
    private static List<List<String>> meterPats;

    private static final String SUN = "تثدذرزسشصضطظلن";
    // unit kinds: 1 forced متحرك, 0 forced ساكن, M madd (0), W و/ي (either), P plain, F plain word-first,
    // S shadda (01), T tanween (10). A lower-case p marks a plain word-final letter (tanween possible).
    static String arudUnits(String line) {
        StringBuilder u = new StringBuilder();
        String[] ws = line.trim().split("\\s+");
        for (int wi = 0; wi < ws.length; wi++) {
            String w = ws[wi].replace("ـ", "");
            StringBuilder ch = new StringBuilder(), di = new StringBuilder();
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c >= '\u064B' && c <= '\u0652') {
                    if (di.length() == 0) continue;
                    char k = c == '\u0651' ? 'S' : c == '\u0652' ? '0' : c <= '\u064D' ? 'T' : '1';
                    if (di.charAt(di.length() - 1) != 'S') di.setCharAt(di.length() - 1, k);
                } else if (c >= '\u0621' && c <= '\u064A' || c == 'ى') {
                    ch.append(c);
                    di.append('.');
                }
            }
            int n = ch.length(), i = 0;
            if (n == 0) continue;
            StringBuilder wu = new StringBuilder();
            int pre = n > 3 && "وفبك".indexOf(ch.charAt(0)) >= 0 && ch.charAt(1) == 'ا' && ch.charAt(2) == 'ل' ? 1 : 0;
            if (n > 2 + pre && ch.charAt(pre) == 'ا' && ch.charAt(pre + 1) == 'ل') {
                if (pre == 1) wu.append('1');
                else if (wi == 0) wu.append('1');
                i = pre + 2;
                wu.append('0');   // لامٌ قمرية ساكنة، أو أول الحرف الشمسي المشدّد
            } else if (ch.charAt(0) == 'ا' && wi > 0 && n > 2) {
                wu.append('0');   // همزة وصل تسقط درجًا والتالي ساكن
                i = 2;
            } else {
                wu.append(di.charAt(0) == '.' ? (ch.charAt(0) == 'آ' ? "1M" : "1") : String.valueOf(di.charAt(0)));
                i = 1;
            }
            for (; i < n; i++) wu.append(kind(ch, di, i, false, n));
            u.append(wu);
        }
        return u.toString();
    }

    private static String kind(CharSequence ch, CharSequence di, int i, boolean first, int n) {
        char d = di.charAt(i), c = ch.charAt(i);
        if (d != '.') return String.valueOf(d);
        if (c == 'آ') return "1M";
        if (c == 'ا' || c == 'ى') return "M";
        if (c == 'و' || c == 'ي') return "W";
        return i == n - 1 ? "p" : "P";
    }

    /** Minimal cost of reading the units as the concrete pattern pat (-1 = impossible). */
    static int arudCost(String u, String pat) {
        int n = u.length(), m = pat.length(), INF = 1 << 20;
        int[][] dp = new int[n + 1][m + 1];
        for (int[] r : dp) java.util.Arrays.fill(r, INF);
        dp[0][0] = 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j <= m; j++) {
                int c0 = dp[i][j];
                if (c0 >= INF) continue;
                char k = u.charAt(i);
                boolean last = i == n - 1;
                String[] opt;
                int[] cost;
                switch (k) {
                    case '1': opt = new String[]{"1"}; cost = new int[]{0}; break;
                    case '0': case 'M': opt = new String[]{"0"}; cost = new int[]{0}; break;
                    case 'W': opt = new String[]{"0", "1"}; cost = new int[]{0, 0}; break;
                    case 'S': opt = new String[]{"01"}; cost = new int[]{0}; break;
                    case 'T': opt = new String[]{"10"}; cost = new int[]{0}; break;
                    case 'p': opt = new String[]{"1", "0", "10", "01"}; cost = new int[]{0, last ? 0 : 1, 1, 2}; break;
                    default: opt = new String[]{"1", "0", "01"}; cost = new int[]{0, last ? 0 : 1, 2};
                }
                for (int o = 0; o < opt.length; o++)
                    if (pat.startsWith(opt[o], j) && c0 + cost[o] < dp[i + 1][j + opt[o].length()])
                        dp[i + 1][j + opt[o].length()] = c0 + cost[o];
            }
        int best = dp[n][m];
        if (m > 0 && pat.charAt(m - 1) == '0') best = Math.min(best, dp[n][m - 1]);   // إشباع القافية
        return best >= INF ? -1 : best;
    }

    private static synchronized List<List<String>> pats() {
        if (meterPats == null) {
            meterPats = new ArrayList<>();
            meterPen = new ArrayList<>();
            for (int mi = 0; mi < METERS.length; mi++) {
                List<String> acc = new ArrayList<>();
                List<Integer> pen = new ArrayList<>();
                acc.add("");
                pen.add(RARE.indexOf((char) mi) >= 0 ? 1 : 0);
                for (String f : METERS[mi]) {
                    List<String> nx = new ArrayList<>();
                    List<Integer> np = new ArrayList<>();
                    for (int a = 0; a < acc.size(); a++)
                        for (String v : f.split("\\|")) {
                            int plus = 0;
                            while (v.charAt(plus) == '+') plus++;
                            nx.add(acc.get(a) + v.substring(plus));
                            np.add(pen.get(a) + plus);
                        }
                    acc = nx;
                    pen = np;
                }
                meterPats.add(acc);
                int[] pa = new int[pen.size()];
                for (int a = 0; a < pa.length; a++) pa[a] = pen.get(a);
                meterPen.add(pa);
            }
        }
        return meterPats;
    }

    /** Cost of the hemistich on meter mi (-1 = no reading fits). */
    static int meterCost(String hem, int mi) {
        String u = arudUnits(hem);
        int best = -1;
        List<String> ps = pats().get(mi);
        int[] pen = meterPen.get(mi);
        for (int x = 0; x < ps.size(); x++) {
            int c = arudCost(u, ps.get(x));
            if (c >= 0) c += pen[x];
            if (c >= 0 && (best < 0 || c < best)) best = c;
        }
        return best;
    }

    /** W107: the meter of a hemistich → its index, or -1 when none fits closely enough (or it is too short). */
    static int meterOf(String hem) {
        String u = arudUnits(hem);
        if (u.length() < 8) return -1;
        int bi = -1, bc = Integer.MAX_VALUE;
        for (int mi = 0; mi < METERS.length; mi++) {
            int c = meterCost(hem, mi);
            if (c >= 0 && c < bc) { bc = c; bi = mi; }
        }
        return bc <= Math.max(2, u.length() / 4) ? bi : -1;
    }

    /** W107: does the hemistich sit on meter mi within the same tolerance? */
    static boolean onMeter(String hem, int mi) {
        int c = meterCost(hem, mi);
        return c >= 0 && c <= Math.max(2, arudUnits(hem).length() / 4);
    }

    private static final String[] CHAT_APPS = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
            "org.telegram.plus", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.facebook.orca", "com.instagram.android", "com.snapchat.android", "com.imo.android.imoim",
            "com.discord", "com.viber.voip", "com.tencent.mm", "com.zhiliaoapp.musically", "com.twitter.android"};

    /** W106 «حارس الاحتيال»: is this a chat app, where a code or a card number is someone else's to read? */
    static boolean chatApp(String pkg) {
        if (pkg == null) return false;
        for (String c : CHAT_APPS) if (c.equals(pkg)) return true;
        return false;
    }

    private static final String[] OTP_WORDS = {"رمز", "الرمز", "كود", "الكود", "التحقق", "code", "otp", "pin"};

    /** W106: the text before the cursor ends in something a scammer asks for → {kind, the exact
     *  suffix to delete}: «card» = 13–19 digits (spaces/dashes allowed) passing Luhn; «otp» = the whole
     *  message is a bare 4–8 digit code (a 19xx/20xx year excepted), or a code right after رمز/كود/code.
     *  Else null. Latin or Arabic-Indic digits. */
    static String[] scamSpan(String t) {
        if (t == null) return null;
        int e = t.length();
        while (e > 0 && t.charAt(e - 1) == ' ') e--;
        int s = e, digits = 0;
        while (s > 0) {
            char c = t.charAt(s - 1);
            if (Character.isDigit(c)) digits++;
            else if (c != ' ' && c != '-') break;
            s--;
        }
        while (s < e && !Character.isDigit(t.charAt(s))) s++;
        if (digits >= 13 && digits <= 19 && (s == 0 || !Character.isLetter(t.charAt(s - 1)))) {
            int sum = 0;
            boolean dbl = false;
            for (int i = e - 1; i >= s; i--) {
                int d = Character.digit(t.charAt(i), 10);
                if (d < 0) continue;
                if (dbl && (d *= 2) > 9) d -= 9;
                sum += d;
                dbl = !dbl;
            }
            if (sum % 10 == 0) return new String[]{"card", t.substring(s)};
        }
        int r = e;
        while (r > 0 && Character.isDigit(t.charAt(r - 1))) r--;
        int n = e - r;
        if (n < 4 || n > 8 || (r > 0 && Character.isLetter(t.charAt(r - 1)))) return null;
        String head = t.substring(0, r).trim();
        if (head.isEmpty()) {
            if (n == 4) {
                int y = Integer.parseInt(normDigits(t.substring(r, e)));
                if (y >= 1900 && y <= 2099) return null;
            }
            return new String[]{"otp", t.substring(r)};
        }
        String low = head.toLowerCase(java.util.Locale.ROOT).replace(":", " ").trim();
        int sp = low.lastIndexOf(' ');
        String last = normAr(sp < 0 ? low : low.substring(sp + 1));
        for (String w : OTP_WORDS) if (last.equals(normAr(w))) return new String[]{"otp", t.substring(r)};
        return null;
    }

    private static String normDigits(String d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length(); i++) b.append((char) ('0' + Character.digit(d.charAt(i), 10)));
        return b.toString();
    }

    static Map<String, String> pairs(String text) {
        Map<String, String> m = new HashMap<>();
        if (text != null)
            for (String line : text.split("\n")) {
                int t = line.indexOf('\t');
                if (line.isEmpty() || line.charAt(0) == '#' || t < 0) continue;
                for (String w : line.substring(0, t).split("،")) m.put(normAr(w.trim()), line.substring(t + 1).trim());
            }
        return m;
    }

    /** W98 «العربيزي»: an ASCII token (letters + 2/3/5/6/7/9) → Arabic; the
     *  dictionary wins, else a rule pass for tokens with an Arabizi digit; English
     *  texting (4, 8, a leading 2: «b4», «gr8», «2day») and plain words stay null. */
    static String arabizi(String t, Map<String, String> dict) {
        t = t.toLowerCase();
        String d = dict.get(t);
        int n = t.length(), dg = 0;
        for (int i = 0; i < n; i++) {
            int c = t.charAt(i);
            dg |= c >= 'a' ? 4 : "235679".indexOf(c) >= 0 ? 1 : 2;   // 4: a letter, 1: Arabizi digit, 2: English one
        }
        if (d != null || dg != 5 || t.charAt(0) == '2') return d;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i), x = i + 1 < n ? t.charAt(i + 1) : ' ';
            int k = "shkhghthdh".indexOf(c + "" + x);
            if (k >= 0 && k % 2 == 0) { b.append("شخغثذ".charAt(k / 2)); i++; continue; }
            if (c == x && "aeiou".indexOf(c) >= 0) { b.append(c == 'a' ? 'ا' : c > 'i' ? 'و' : 'ي'); i++; continue; }
            if (c == 'a' || c == 'e' || (c == 'o' || c == 'u') && i > 0 && i < n - 1) { if (i == 0 || i == n - 1) b.append(i == 0 || c == 'a' ? 'ا' : 'ه'); continue; }
            int j = "bdfhjklmnqrstwyzgpvicuo235679".indexOf(c);
            if (j >= 0) b.append("بدفهجكلمنقرستويزجبفيكووءعخطحص".charAt(j));
        }
        return b.toString();
    }

    /** W94 «محوّل اللهجات»: every word of a synonym row becomes that row's column
     *  {@code col} (0 فصحى، 1 حجازي، 2 نجدي، 3 خليجي), first alternative; a leading
     *  «و» rides along; everything else (unknown words, spaces, punctuation) stays. */
    static String synConvert(String text, int col) {
        StringBuilder b = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            int j = i;
            while (j < n && Character.isLetter(text.charAt(j))) j++;
            if (j == i) { b.append(text.charAt(i++)); continue; }
            String w = text.substring(i, j), p = "", line = syn.get(normAr(w));
            if (line == null && w.length() > 2 && w.charAt(0) == 'و' && (line = syn.get(normAr(w.substring(1)))) != null) p = "و";
            b.append(line == null ? w : p + line.split("\t")[col].split("،")[0].trim());
            i = j;
        }
        return b.toString();
    }

    /** The picker chips for a word's row ("✕ …" header first), or null when none. */
    static List<String> synChips(String word) {
        String line = syn == null ? null : syn.get(normAr(word));
        if (line == null) return null;
        String[] row = line.split("[\t،]");
        List<String> out = new ArrayList<>();
        out.add("✕ " + word + " • مرادفات اللهجات");
        String k = normAr(word);
        for (String w : row) {
            w = w.trim();
            if (!w.isEmpty() && !normAr(w).equals(k) && !out.contains(w) && out.size() < 11) out.add(w);
        }
        return out.size() > 1 ? out : null;
    }
}
