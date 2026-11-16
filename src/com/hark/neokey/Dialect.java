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
