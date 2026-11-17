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

    // ---- W115 «مدقق الآيات» 📖: a quoted verse checked word by word against the Tanzil text (assets/quran.txt,
    // Simple Clean, verbatim, CC BY 3.0 — tanzil.net). Exact → the reference; one word off → the true wording.
    static final String[] SURA = ("الفاتحة البقرة آل_عمران النساء المائدة الأنعام الأعراف الأنفال التوبة يونس هود يوسف الرعد إبراهيم الحجر"
            + " النحل الإسراء الكهف مريم طه الأنبياء الحج المؤمنون النور الفرقان الشعراء النمل القصص العنكبوت الروم لقمان السجدة"
            + " الأحزاب سبأ فاطر يس الصافات ص الزمر غافر فصلت الشورى الزخرف الدخان الجاثية الأحقاف محمد الفتح الحجرات ق الذاريات"
            + " الطور النجم القمر الرحمن الواقعة الحديد المجادلة الحشر الممتحنة الصف الجمعة المنافقون التغابن الطلاق التحريم الملك"
            + " القلم الحاقة المعارج نوح الجن المزمل المدثر القيامة الإنسان المرسلات النبأ النازعات عبس التكوير الانفطار المطففين"
            + " الانشقاق البروج الطارق الأعلى الغاشية الفجر البلد الشمس الليل الضحى الشرح التين العلق القدر البينة الزلزلة العاديات"
            + " القارعة التكاثر العصر الهمزة الفيل قريش الماعون الكوثر الكافرون النصر المسد الإخلاص الفلق الناس").split(" ");

    /** W115: the comparison form of one word — marks, tatweel and hamza seats folded, ى→ي, ة→ه; "" when no letter. */
    static String qNorm(String w) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ((c >= '\u064B' && c <= '\u065F') || c == '\u0670' || (c >= '\u06D6' && c <= '\u06ED') || c == '\u0640' || c == 'ء') continue;
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';
            else if (c == 'ى' || c == 'ئ') c = 'ي';
            else if (c == 'ة') c = 'ه';
            else if (c == 'ؤ') c = 'و';
            if (c >= '\u0621' && c <= '\u064A') b.append(c);
        }
        return b.toString();
    }

    static final class Quran {
        final String[] w, raw;   // comparison words / the text's own words, the whole mushaf in order
        final int[] ref;         // sura * 1000 + aya, per word
        final java.util.HashMap<String, int[]> at = new java.util.HashMap<>();

        Quran(String text) {
            java.util.ArrayList<String> nw = new java.util.ArrayList<>(), rw = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> rf = new java.util.ArrayList<>();
            for (String line : text.split("\n")) {
                String[] f = line.split("\\|", 3);
                if (f.length < 3 || line.startsWith("#")) continue;
                int r = Integer.parseInt(f[0].trim()) * 1000 + Integer.parseInt(f[1].trim());
                for (String t : f[2].trim().split(" ")) {
                    String n = qNorm(t);
                    if (n.isEmpty()) continue;
                    nw.add(n); rw.add(t); rf.add(r);
                }
            }
            w = nw.toArray(new String[0]);
            raw = rw.toArray(new String[0]);
            ref = new int[rf.size()];
            java.util.HashMap<String, Integer> cnt = new java.util.HashMap<>();
            for (int i = 0; i < w.length; i++) { ref[i] = rf.get(i); Integer c = cnt.get(w[i]); cnt.put(w[i], c == null ? 1 : c + 1); }
            for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) at.put(e.getKey(), new int[e.getValue() + 1]);
            for (int i = 0; i < w.length; i++) { int[] a = at.get(w[i]); a[++a[0]] = i; }
        }

        /** W115: {exact "1"/"0", fixed text "﴿…﴾ [سورة: آية]", reference label} for the best place, or null. */
        String[] check(String phrase) {
            java.util.ArrayList<String> t = new java.util.ArrayList<>();
            for (String x : phrase.trim().split("\\s+")) { String n = qNorm(x); if (!n.isEmpty()) t.add(n); }
            int n = t.size();
            if (n < 3) return null;
            int bestS = -1, bestL = 0, bestD = 2, more = 0;
            int[][] anchors = {at.get(t.get(0)), at.get(t.get(1)), at.get(t.get(n - 1))};
            int[] off = {0, -1, -(n - 1)};
            java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (int k = 0; k < 3; k++) {
                int[] a = anchors[k];
                if (a == null) continue;
                for (int j = 1; j <= a[0]; j++) {
                    for (int sh = -1; sh <= 1; sh++) {
                        int st = a[j] + off[k] + (k == 2 ? sh : 0);
                        if (st < 0 || (k != 2 && sh != 0) || !seen.add(st)) continue;
                        for (int L = n - 1; L <= n + 1; L++) {
                            if (st + L > w.length || L < 3) continue;
                            int d = wordDist(t, st, L);
                            if (d == 0 && bestD == 0) more++;
                            boolean better = d < bestD || (d == bestD && d < 2 && (st < bestS
                                    || (st == bestS && Math.abs(L - n) < Math.abs(bestL - n))));
                            if (better) { if (d < bestD) more = 0; bestD = d; bestS = st; bestL = L; }
                        }
                    }
                }
            }
            if (bestS < 0 || (bestD == 1 && n < 4)) return null;
            StringBuilder b = new StringBuilder("﴿");
            for (int i = bestS; i < bestS + bestL; i++) b.append(i == bestS ? "" : " ").append(raw[i]);
            int r0 = ref[bestS], r1 = ref[bestS + bestL - 1];
            String sura = SURA[r0 / 1000 - 1].replace('_', ' ');
            String ay = (r0 % 1000) + (r1 != r0 ? "-" + (r1 % 1000) : "");
            String lbl = sura + " " + ay + (more > 0 ? " وغيرها" : "");
            return new String[]{bestD == 0 ? "1" : "0", b.append("﴾ [").append(sura).append(": ").append(ay).append("] ").toString(), lbl};
        }

        /** word Levenshtein of the typed words against w[st..st+L), capped at 2 */
        private int wordDist(java.util.List<String> t, int st, int L) {
            int n = t.size();
            if (Math.abs(n - L) > 1) return 2;
            int[] prev = new int[L + 1], cur = new int[L + 1];
            for (int j = 0; j <= L; j++) prev[j] = j;
            for (int i = 1; i <= n; i++) {
                cur[0] = i;
                int rowMin = cur[0];
                for (int j = 1; j <= L; j++) {
                    int c = t.get(i - 1).equals(w[st + j - 1]) ? 0 : 1;
                    cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + c);
                    rowMin = Math.min(rowMin, cur[j]);
                }
                if (rowMin >= 2) return 2;
                int[] x = prev; prev = cur; cur = x;
            }
            return Math.min(prev[L], 2);
        }
    }

    private static final String[] Q_LEADS = {"قال الله تعالى", "قال تعالى", "قوله تعالى", "قال الله عز وجل", "يقول الله تعالى"};

    /** W115: the quoted words at the cursor — after an open ﴿, inside a just-closed ﴿…﴾, or after «قال تعالى» —
     *  as {phrase, chars to replace}; only at a word boundary, so the last word is whole. */
    static String[] quranQuote(String before) {
        if (before == null || before.isEmpty()) return null;
        char last = before.charAt(before.length() - 1);
        if (last != ' ' && last != '﴾') return null;
        int o = before.lastIndexOf('﴿');
        if (o >= 0) {
            int c = before.indexOf('﴾', o);
            if (c < 0 || before.substring(c + 1).trim().isEmpty()) {
                String ph = before.substring(o + 1, c < 0 ? before.length() : c);
                return ph.trim().split("\\s+").length >= 3 ? new String[]{ph, String.valueOf(before.length() - o)} : null;
            }
        }
        int best = -1, bl = 0;
        for (String l : Q_LEADS) { int i = before.lastIndexOf(l); if (i > best) { best = i; bl = l.length(); } }
        if (best < 0) return null;
        int st = best + bl;
        while (st < before.length() && (before.charAt(st) == ':' || before.charAt(st) == ' ' || before.charAt(st) == '«')) st++;
        String ph = before.substring(st);
        if (ph.indexOf('.') >= 0 || ph.indexOf('»') >= 0 || ph.indexOf('﴿') >= 0 || ph.indexOf('﴾') >= 0 || ph.trim().split("\\s+").length < 3) return null;
        return new String[]{ph, String.valueOf(before.length() - st)};
    }

    // ---- W114 «المسودة الخالدة» 📝: a long text that vanished with its app comes back on the next visit.
    static final long DRAFT_TTL = 86400000L;

    /** W114: worth keeping = 40+ letters of real writing, never a money-shaped value. */
    static boolean draftWorth(String t) {
        if (t == null) return false;
        String x = t.trim();
        return x.length() >= 40 && x.length() <= 20000 && clipKind(x) == null;
    }

    /** W114: offer the kept draft when the same app opens an empty field within a day. */
    static boolean draftOffer(String dPkg, long dAt, String dText, String pkg, long now, String field) {
        return dText != null && dPkg != null && !dPkg.isEmpty() && dPkg.equals(pkg)
                && now >= dAt && now - dAt <= DRAFT_TTL && (field == null || field.trim().isEmpty());
    }

    /** W114: the chip's glimpse — the first four words. */
    static String draftLabel(String t) {
        String[] w = t.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(4, w.length); i++) b.append(i == 0 ? "" : " ").append(w[i]);
        return b.append(w.length > 4 ? "…" : "").toString();
    }

    // ---- W121 الحافظة الذكية: a glance tag + a relative age on every clip card.
    /** W121: the card's glance tag — 🔗 link, ✉ mail, 🏦 IBAN, 💳 card, 🪙 wallet, 🔑 one-time code, 📞 phone, 📍 place, # number; else null. */
    static String clipTag(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        String lo = t.toLowerCase(java.util.Locale.ROOT);
        if (lo.matches("(?s).*(maps\\.google|goo\\.gl/maps|maps\\.app\\.goo\\.gl|maps\\.apple).*")) return "📍";
        if (lo.matches("(?s).*\\b(https?://|www\\.)\\S+.*")) return "🔗";
        if (t.length() <= 80 && t.matches("[^\\s@]+@[^\\s@]+\\.[a-zA-Z]{2,}")) return "✉";
        String k = clipKind(t);
        if (k != null) {
            if (k.equals("iban")) return "🏦";
            if (k.equals("card")) return "💳";
            if (k.equals("phone")) return "📞";
            if (k.equals("eth") || k.equals("btc") || k.equals("trx")) return "🪙";
        }
        String c = t.replace(" ", "").replace("-", "");
        if (c.matches("[0-9]{4,8}")) return "🔑";
        if (c.matches("\\+?[0-9]{9,15}")) return "📞";
        if (c.matches("[0-9.,]+")) return "#";
        return null;
    }

    /** W121: a short Arabic age for a clip card: الآن، قبل 5 د، قبل 3 س، أمس، قبل 4 أيام، or a week count. */
    static String ago(long ms) {
        if (ms < 60_000L) return "الآن";
        long m = ms / 60_000L;
        if (m < 60) return "قبل " + m + " د";
        long h = m / 60;
        if (h < 24) return "قبل " + h + " س";
        long d = h / 24;
        if (d == 1) return "أمس";
        if (d < 7) return "قبل " + d + (d <= 10 ? " أيام" : " يوم");
        long w = d / 7;
        return w == 1 ? "قبل أسبوع" : w == 2 ? "قبل أسبوعين" : w < 5 ? "قبل " + w + " أسابيع" : "قديم";
    }

    // ---- W113 «شاهد اللصق» 🔍: the clipboard changed itself — a money-shaped value replaced by another of the same shape.
    /** W113: iban|eth|btc|trx|card|phone|account for a value worth stealing, else null. */
    static String clipKind(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.length() > 70 || t.isEmpty()) return null;
        String c = t.replace(" ", "").replace("-", "");
        if (c.matches("0x[0-9a-fA-F]{40}")) return "eth";
        if (c.matches("(bc1[02-9ac-hj-np-z]{25,62})|([13][1-9A-HJ-NP-Za-km-z]{25,34})")) return "btc";
        if (c.matches("T[1-9A-HJ-NP-Za-km-z]{33}")) return "trx";
        String[] ib = iban(c);
        if (ib != null && ib[1].length() == c.length() && !"len".equals(ib[0])) return "iban";
        if (c.matches("(\\+?966|0)5[0-9]{8}")) return "phone";
        if (c.matches("[0-9]{13,19}") && scamSpan(c) != null) return "card";
        if (c.matches("[0-9]{10,24}")) return "account";
        return null;
    }

    /** W113: prev → now looks like a hijack: same kind, different value, and either within 3 s of the copy or the
     *  address-poisoning shape (same first four and last four, different middle) within 10 min. */
    static boolean clipSwap(String prev, long dtMs, String now) {
        String k = clipKind(prev);
        if (k == null || !k.equals(clipKind(now)) || dtMs < 0) return false;
        String a = prev.trim().replace(" ", "").replace("-", ""), b = now.trim().replace(" ", "").replace("-", "");
        if (a.equalsIgnoreCase(b)) return false;
        if (dtMs <= 3000) return true;
        return dtMs <= 600000 && a.length() > 12 && b.length() == a.length()
                && a.substring(0, 4).equalsIgnoreCase(b.substring(0, 4))
                && a.substring(a.length() - 4).equalsIgnoreCase(b.substring(b.length() - 4));
    }

    // ---- W112 «حارس الروابط» 🔗: a link at the cursor that imitates a bank, a government service or a big brand.
    private static final String[] BRANDS = {"alrajhibank", "alrajhi", "alahli", "riyadbank", "alinma", "albilad", "absher",
            "tawakkalna", "paypal", "apple", "google", "microsoft", "amazon", "netflix", "whatsapp", "instagram", "facebook",
            "aramex", "smsaexpress", "splonline", "tabby", "tamara", "stcpay", "binance", "icloud", "outlook", "snapchat", "tiktok"};
    private static final String[] PRIZED = {"alrajhi", "alahli", "riyadbank", "alinma", "albilad", "absher", "tawakkalna",
            "paypal", "stcpay", "tabby", "tamara", "splonline", "aramex", "smsaexpress", "binance", "icloud"};

    private static String unleet(String l) {
        return l.replace("rn", "m").replace("vv", "w").replace('1', 'l').replace('0', 'o').replace('3', 'e')
                .replace('5', 's').replace('4', 'a').replace('7', 't').replace('-', ' ').replace(" ", "");
    }

    private static int lev(String a, String b) {
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0];
            d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int t = d[j];
                d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = t;
            }
        }
        return d[b.length()];
    }

    /** W112: {kind homograph|lookalike|disguise, the brand or ""} for the link at the end of before, or null. */
    static String[] linkGuard(String before) {
        if (before == null) return null;
        String t = before.trim();
        int sp = Math.max(t.lastIndexOf(' '), t.lastIndexOf('\n'));
        String tok = t.substring(sp + 1).toLowerCase(java.util.Locale.ROOT);
        tok = tok.replaceFirst("^[a-z]+://", "").replaceFirst("^www\\.", "");
        int sl = tok.indexOf('/');
        String host = (sl < 0 ? tok : tok.substring(0, sl)).replaceAll("[.,;:!?]+$", "");
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);   // user@host disguises: the real host is after @
        String[] lb = host.split("\\.");
        if (lb.length < 2 || lb[lb.length - 1].length() < 2) return null;
        for (String x : lb) if (x.isEmpty()) return null;
        String tld = lb[lb.length - 1];
        if (!tld.matches("[a-z]{2,}|xn--[a-z0-9-]+|[\\p{L}]{2,}")) return null;
        if (!host.matches(".*[a-z\\p{L}].*")) return null;
        if (host.contains("xn--") || !host.matches("[a-z0-9.-]+")) return new String[]{"homograph", ""};
        int k = lb.length - 2;
        if (k > 0 && lb[k].matches("com|gov|net|org|edu|co") && tld.length() == 2) k--;
        String reg = lb[k];
        for (String b : BRANDS) if (b.equals(reg)) return null;
        String u = unleet(reg);
        for (String b : BRANDS) {
            if (b.length() >= 4 && (u.equals(b) || (b.length() >= 5 && reg.length() >= 5 && lev(reg, b) == 1)))
                return new String[]{"lookalike", b};
        }
        for (String b : PRIZED) {
            if (reg.contains(b) || u.contains(b)) return new String[]{"disguise", b};
            for (int i = 0; i < k; i++) if (lb[i].contains(b)) return new String[]{"disguise", b};
        }
        return null;
    }

    // ---- W111 «حارس الآيبان» 🏦: an IBAN at the cursor is checked (length + ISO 7064 mod-97) before money moves.
    private static final String IBAN_LEN = "SA24AE23KW30BH22QA29OM23EG29JO30GB22DE22FR27TR26LB28PS29IQ23";
    private static final String[][] SA_BANKS = {{"10", "الأهلي"}, {"15", "البلاد"}, {"20", "الرياض"}, {"30", "العربي الوطني"},
            {"45", "ساب"}, {"55", "الفرنسي"}, {"60", "الجزيرة"}, {"65", "الاستثمار"}, {"80", "الراجحي"}, {"05", "الإنماء"}};
    private static final java.util.regex.Pattern IBAN_AT_END =
            java.util.regex.Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]{2}[0-9]{2}(?: ?[A-Za-z0-9]){10,30} ?$");

    /** W111: {verdict ok|bad|len, the exact span before the cursor, the IBAN in groups of four, the Saudi bank or ""}, or null. */
    static String[] iban(String before) {
        if (before == null) return null;
        StringBuilder t = new StringBuilder(before.length());
        for (int i = 0; i < before.length(); i++) {
            char c = before.charAt(i);
            if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));
            t.append(c);
        }
        java.util.regex.Matcher m = IBAN_AT_END.matcher(t);
        if (!m.find()) return null;
        String span = before.substring(m.start());
        String cc = m.group().replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        int at = -1;
        for (int i = 0; i + 4 <= IBAN_LEN.length(); i += 4) if (IBAN_LEN.startsWith(cc.substring(0, 2), i)) at = i;
        if (at < 0) return null;
        int want = Integer.parseInt(IBAN_LEN.substring(at + 2, at + 4));
        if (cc.length() < want) return null;   // still typing
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < cc.length(); i++) { if (i > 0 && i % 4 == 0) g.append(' '); g.append(cc.charAt(i)); }
        if (cc.length() > want) return new String[]{"len", span, g.toString(), ""};
        String r = cc.substring(4) + cc.substring(0, 4);
        int mod = 0;
        for (int i = 0; i < r.length(); i++) {
            int v = Character.digit(r.charAt(i), 36);
            if (v < 0) return null;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        String bank = "";
        if (cc.startsWith("SA")) for (String[] b : SA_BANKS) if (cc.startsWith(b[0], 4)) bank = b[1];
        return new String[]{mod == 1 ? "ok" : "bad", span, g.toString(), bank};
    }

    // ---- W110 «بصمة الكتابة» ✋: the owner's typing rhythm (log key-to-key intervals, never the keys
    // themselves). A run of 24 intervals far from it = someone else → guest mode hides the personal layer.
    static final class Rhythm {
        static final int MIN_PROFILE = 300, WIN = 24;
        double n, mean, m2;
        private final double[] win = new double[WIN];
        private int wn, wi;
        boolean guest;

        /** One key-to-key gap in ms; pauses (> 1.5 s) and bounces (< 40 ms) break or skip the run. */
        boolean add(long dt) {
            if (dt > 1500) { wn = 0; wi = 0; return guest; }
            if (dt < 40) return guest;
            double v = Math.log(dt);
            win[wi] = v; wi = (wi + 1) % WIN; if (wn < WIN) wn++;
            if (wn < WIN || n < MIN_PROFILE) { if (n < MIN_PROFILE) learn(v); return guest = false; }
            double mw = 0, vw = 0;
            for (double x : win) mw += x;
            mw /= WIN;
            for (double x : win) vw += (x - mw) * (x - mw);
            double sw = Math.sqrt(vw / (WIN - 1)), sp = Math.sqrt(m2 / (n - 1));
            if (sp < 1e-6) sp = 1e-6;
            double d = Math.abs(mw - mean) / sp, r = sw / sp;
            if (guest) guest = !(d < 1.0 && r > 0.6 && r < 1.7);   // hysteresis: back only on a clearly-owner run
            else guest = d > 1.5 || r > 2.2 || r < 0.45;
            if (!guest) learn(v);   // a stranger never teaches the profile
            return guest;
        }

        private void learn(double v) {
            n++;
            double dl = v - mean;
            mean += dl / n;
            m2 += dl * (v - mean);
        }

        String save() { return n + "," + mean + "," + m2; }

        static Rhythm load(String s) {
            Rhythm r = new Rhythm();
            try {
                String[] a = s.split(",");
                r.n = Double.parseDouble(a[0]); r.mean = Double.parseDouble(a[1]); r.m2 = Double.parseDouble(a[2]);
                if (!(r.n >= 0) || Double.isNaN(r.mean) || !(r.m2 >= 0)) return new Rhythm();
            } catch (RuntimeException e) {
                return new Rhythm();
            }
            return r;
        }
    }

    // ---- W109 «مرآة النبرة» 🪞: how a chat message will land (حادّة / باردة) and a one-tap warmer version.
    private static final String[] HARSH = {"لازم", "المفروض", "مفروض", "فورا", "حالا", "بسرعه", "ليش", "ليه", "مو", "مب", "مش",
            "ابي", "ابغى", "ابغي", "بدي", "ترا", "تراك", "غلطان", "غلطك", "سخيف", "تافه", "اسكت", "خلاص", "يكفي", "كفايه",
            "مافي", "ماعندك", "ابدا", "مره", "عشرين", "حرام"};
    private static final String[] WARM = {"حبيبي", "حبيبتي", "غالي", "الغالي", "يعافيك", "يعطيك", "العافيه", "سمحت", "فضلك",
            "شكرا", "مشكور", "تكرم", "تكرمين", "ياليت", "ممكن", "يسعدك", "عساك", "الله", "حياك", "فديتك"};
    private static final String[] COLD = {"ok", "okay", "k", "اوك", "اوكي", "اوكيه", "طيب", "تمام", "ك", "اها", "ماشي", "اوه", "مم", "طيب."};
    private static final String[][] WARMER = {{"ابي", "ممكن"}, {"ابغى", "ممكن"}, {"ابغي", "ممكن"}, {"بدي", "ممكن"}, {"لازم", "ياليت"},
            {"المفروض", "ياليت"}, {"مفروض", "ياليت"}, {"فورا", "لما تقدر"}, {"حالا", "لما تقدر"}, {"بسرعه", "لما تقدر"},
            {"اسكت", "خلنا نهدا"}, {"غلطان", "يمكن صار لبس"}, {"غلطك", "يمكن صار لبس"}, {"ترا", ""}, {"تراك", ""}};

    private static boolean in(String[] set, String v) {
        for (String x : set) if (x.equals(v)) return true;
        return false;
    }

    /** W109: «harsh» (two or more sharp words or doubled !!/؟؟, no warm word), «cold» (a lone ok/طيب/تمام), else null. */
    static String tone(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) return null;
        String[] x = words(sentence);
        if (x.length == 1 && in(COLD, x[0])) return "cold";
        int h = 0;
        for (String w : x) {
            if (in(WARM, w) || w.contains("🌹") || w.contains("❤")) return null;
            if (in(HARSH, w)) h++;
        }
        if (sentence.contains("🌹") || sentence.contains("❤") || sentence.contains("😊") || sentence.contains("🙏")) return null;
        if (sentence.contains("!!") || sentence.contains("؟؟") || sentence.contains("??")) h++;
        return h >= 2 ? "harsh" : null;
    }

    /** W109: the same message, warmer — sharp words swapped, doubled marks single, «لو سمحت» and «الله يعافيك 🌹» around it. */
    static String warmer(String sentence) {
        String t = sentence.trim();
        if ("cold".equals(tone(t))) return t + "، الله يعطيك العافية 🌹";
        StringBuilder b = new StringBuilder();
        for (String tok : t.split("\\s+")) {
            String core = normAr(tok.replaceAll("[!?؟.،,]+$", ""));
            String tail = tok.substring(tok.replaceAll("[!?؟.،,]+$", "").length());
            String rep = null;
            for (String[] r : WARMER) if (r[0].equals(core)) rep = r[1];
            String w = rep == null ? tok : rep + tail;
            if (!w.isEmpty()) b.append(b.length() == 0 ? "" : " ").append(w);
        }
        String r = b.toString().replaceAll("!{2,}", "!").replaceAll("[؟?]{2,}", "؟");
        if (!r.contains("سمحت")) r = "لو سمحت، " + r;
        return r + " الله يعافيك 🌹";
    }

    // ---- W108 «ذاكرة الوعد» 🤝: a promise with a time in it («أبشر بكرة أرسله») earns a reminder.
    private static final String[] PROMISE = {"ابشر", "ابشري", "ابشروا", "وعد", "اوعدك", "اوعدكم", "وعدتك", "اكيد", "تم", "سوف", "راح", "رح",
            "حاضر", "طيب", "اوكي", "ok", "اكيدا"};
    private static final String[] WEEK = {"الاحد", "الاثنين", "الثلاثاء", "الاربعاء", "الخميس", "الجمعه", "السبت"};

    private static String[] words(String t) {
        return normAr(t.toLowerCase(java.util.Locale.ROOT)).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+");
    }

    /** W108: is this word one half of a promise (a promise word or a time word)? The strip only looks then. */
    static boolean promiseMarker(String w) {
        String[] x = words(w);
        if (x.length != 1) return false;
        String v = x[0];
        return isPromise(v) || timeOf(new String[]{v}, 0) != null || "الله".equals(v) || "الجاي".equals(v) || "القادم".equals(v);
    }

    private static boolean isPromise(String v) {
        for (String p : PROMISE) if (p.equals(v)) return true;
        // سأرسل / بأرسل (future), بكلمك / برسلك (dialect «بـ» + verb to you); never بالـ / باذن / باكر
        return v.length() >= 4 && !v.startsWith("بال") && !v.startsWith("باذن") && !v.equals("باكر")
                && (v.startsWith("سا") || v.startsWith("با") || v.startsWith("ب") && v.length() <= 6 && v.endsWith("ك"));
    }

    /** {kind, value, label}: kind d = days ahead at 9:00, m = minutes ahead, w = next weekday (1=Sunday…7) at 9:00,
     *  n = tonight at 20:00. Null when the words at i are no time. */
    private static String[] timeOf(String[] x, int i) {
        String v = x[i], nx = i + 1 < x.length ? x[i + 1] : "";
        if (v.equals("بعد")) {
            if (nx.equals("بكره") || nx.equals("بكرا") || nx.equals("غد")) return new String[]{"d", "2", "بعد بكرة"};
            if (nx.equals("ساعه")) return new String[]{"m", "60", "بعد ساعة"};
            if (nx.equals("ساعتين")) return new String[]{"m", "120", "بعد ساعتين"};
            if (nx.equals("شوي") || nx.equals("شويه") || nx.equals("قليل")) return new String[]{"m", "30", "بعد نص ساعة"};
            return null;
        }
        if (v.equals("بكره") || v.equals("بكرا") || v.equals("غدا") || v.equals("باكر")) return new String[]{"d", "1", "بكرة ٩ص"};
        if (v.equals("الليله") || v.equals("المسا") || v.equals("المساء") || v.equals("الليل")) return new String[]{"n", "20", "الليلة ٨م"};
        if ((v.equals("الاسبوع") || v.equals("الاسبوع")) && (nx.equals("الجاي") || nx.equals("القادم") || nx.equals("الياي")))
            return new String[]{"d", "7", "الأسبوع الجاي"};
        for (int k = 0; k < WEEK.length; k++) if (WEEK[k].equals(v)) return new String[]{"w", String.valueOf(k + 1), v.equals("الجمعه") ? "الجمعة" : WEEK[k]};
        return null;
    }

    /** W108: the sentence holds a promise and a time → the time ({kind, value, label}); else null.
     *  «ان شاء الله» counts as a promise. */
    static String[] promise(String sentence) {
        if (sentence == null) return null;
        String[] x = words(sentence);
        boolean pr = false;
        String[] when = null;
        for (int i = 0; i < x.length; i++) {
            if (isPromise(x[i]) || x[i].equals("انشالله") || x[i].equals("الله") && i > 0 && (x[i - 1].equals("شاء") || x[i - 1].equals("انشاء")))
                pr = true;
            if (when == null) when = timeOf(x, i);
        }
        return pr ? when : null;
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
