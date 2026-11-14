package com.hark.neokey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W68 (roadmap v2): مقياس الوضوح ClarityMeter — «مقياس عربي حي لجودة
 * ما تكتبه». مقاييس عربية خالصة محلية داخل Writer: طول الجملة،
 * التكرار، الحشو، كثافة التشكيل، تنوع المفردات (TTR) — درجة وضوح
 * مركبة معلومة المكونات. نقي JVM: لا شبكة، لا نموذج، لا حالة — دالة
 * نقية على النص.
 *
 * القوانين:
 *   1. عربي خالص محلي — كل مقياس حساب حروف/كلمات عربية مباشرة، والنتيجة
 *      تحمل مكوّناتها المعلومة (كل مقياس: قيمة + حكم) لا درجة معماة.
 *   2. الحتمية — نفس النص = نفس الدرجة دائماً (لا زمن، لا عشوائية،
 *      لا حالة كائن)؛ اختبار الحتمية عضّ أي كسر.
 *   3. كل مقياس له اختبار مرجعي — نص يفشل ونص يمر لكل مقياس من الخمسة.
 *   4. فشل مغلق — نص بلا ما يُقاس (أقل من 3 كلمات محتوى) يعيد
 *      SCORE_INSUFFICIENT موثقاً لا درجة مصطنعة.
 *   5. العرض الحتمي — سطر واحد بأرقام وعلامات (✓/✗) للمكونات الخمسة.
 *
 * التطبيع: المقارنة بين الكلمات على الحروف العربية بلا تشكيل
 * (U+064B–U+0652 تُزال)، والتشكيل نفسه يُقاس كثافة على الحروف الأصلية.
 */
public final class ClarityMeter {

    public static final String INSUFFICIENT = "SCORE_INSUFFICIENT";

    /** إعلان النص غير العربي — يعيش في الصنف النقي لا في أصناف shell (قانون الاقتطاع). */
    public static final String NON_ARABIC = "مقياس الوضوح عربي خالص — النص ليس عربياً";

    /** عتبة طول الجملة: متوسط كلمات الجملة فوقه يفشل. */
    static final int SENT_MAX = 20;
    /** عتبة التكرار: تردد أعلى كلمة محتوى فوقه يفشل. */
    static final double REPEAT_MAX = 0.25;
    /** عتبة TTR: تنوع المفردات دونه يفشل. */
    static final double TTR_MIN = 0.40;
    /** عتبة كثافة التشكيل: حركات/حرف فوقها يفشل. */
    static final double TASHKEEL_MAX = 0.35;

    /** حشو عربي معلن — عبارات متعددة الكلمات تقاس ظاهرياً على النص المطبّع. */
    static final String[] FILLERS = {
            "في الحقيقة", "على ما يبدو", "نوعا ما", "إلى حد ما",
            "على العموم", "كما تعلم", "بطريقة ما"
    };

    /** كلمات وقف عربية — لا تدخل التكرار ولا TTR. */
    static final String[] STOP = {
            "في", "من", "على", "إلى", "عن", "أن", "إن", "التي", "الذي",
            "هذا", "هذه", "ذلك", "هي", "هو", "ما", "لا", "كل", "بعد",
            "قبل", "مع", "أو", "ثم", "قد", "كان", "كانت", "هناك", "له", "لها"
    };

    private ClarityMeter() {
    }

    /** نتيجة مقياس واحد: القيمة + الحكم (معلومة المكونات لا درجة معماة). */
    public static final class Part {
        public final String name;
        public final String value;
        public final boolean pass;

        Part(String name, String value, boolean pass) {
            this.name = name;
            this.value = value;
            this.pass = pass;
        }
    }

    /** الدرجة المركبة: المكونات الخمسة + العدد 0–100 + سطر العرض. */
    public static final class Meter {
        public final List<Part> parts;
        public final int score;
        public final String state;
        public final String display;

        Meter(List<Part> parts, int score, String state, String display) {
            this.parts = parts;
            this.score = score;
            this.state = state;
            this.display = display;
        }
    }

    /** المقياس الكامل — دالة نقية بحتة: نفس النص → نفس النتيجة حرفاً. */
    public static Meter evaluate(String text) {
        String norm = normalize(text);
        List<String> words = words(norm);
        List<String> content = contentWords(words);
        if (content.size() < 3) {
            List<Part> parts = new ArrayList<Part>();
            for (String n : new String[]{"الجملة", "التكرار", "الحشو",
                    "التشكيل", "التنوع"}) {
                parts.add(new Part(n, "—", true));
            }
            return new Meter(parts, -1, INSUFFICIENT,
                    "الوضوح: غير كافٍ — النص أقصر من أن يُقاس");
        }

        // 1) طول الجملة
        List<String> sents = sentences(text);
        double sentAvg = sents.isEmpty() ? 0
                : (double) totalWords(sents) / sents.size();
        boolean sentPass = sentAvg <= SENT_MAX;
        Part pSent = new Part("الجملة", avgStr(sentAvg), sentPass);

        // 2) التكرار (كلمة محتوى مسيطرة)
        Map<String, Integer> freq = new LinkedHashMap<String, Integer>();
        for (String w : content) {
            Integer c = freq.get(w);
            freq.put(w, c == null ? 1 : c + 1);
        }
        int top = 0;
        for (Integer c : freq.values()) if (c > top) top = c;
        double repeat = (double) top / content.size();
        boolean repeatPass = repeat <= REPEAT_MAX;
        Part pRepeat = new Part("التكرار", pct(repeat), repeatPass);

        // 3) الحشو (عبارات معلنة تقاس ظاهرياً على المطبّع)
        int fillers = 0;
        for (String f : FILLERS) {
            int i = norm.indexOf(f);
            while (i >= 0) {
                fillers++;
                i = norm.indexOf(f, i + f.length());
            }
        }
        boolean fillPass = fillers == 0;
        Part pFill = new Part("الحشو", String.valueOf(fillers), fillPass);

        // 4) كثافة التشكيل (حركات/حرف عربي على النص الأصلي)
        int letters = 0, harakat = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u0621' && ch <= '\u064A') letters++;
            if (ch >= '\u064B' && ch <= '\u0652') harakat++;
        }
        double density = letters == 0 ? 0 : (double) harakat / letters;
        boolean tashPass = density <= TASHKEEL_MAX;
        Part pTash = new Part("التشكيل", pct(density), tashPass);

        // 5) تنوع المفردات TTR (مفردات محتوى فريدة/المجموع)
        Map<String, Boolean> uniq = new LinkedHashMap<String, Boolean>();
        for (String w : content) uniq.put(w, Boolean.TRUE);
        double ttr = (double) uniq.size() / content.size();
        boolean ttrPass = ttr >= TTR_MIN;
        Part pTtr = new Part("التنوع", pct(ttr), ttrPass);

        List<Part> parts = new ArrayList<Part>();
        parts.add(pSent);
        parts.add(pRepeat);
        parts.add(pFill);
        parts.add(pTash);
        parts.add(pTtr);
        int score = 0;
        for (Part p : parts) if (p.pass) score += 20;
        StringBuilder sb = new StringBuilder("الوضوح ").append(score)
                .append("/100");
        for (Part p : parts) sb.append(' ')
                .append(p.pass ? '✓' : '✗').append(p.name);
        return new Meter(parts, score, "SCORE_COMPOSED", sb.toString());
    }

    /** إزالة التشكيل + توحيد المسافات — المقارنة على هذا الشكل. */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\u064B' && ch <= '\u0652') continue;
            if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
                if (!space && sb.length() > 0) sb.append(' ');
                space = true;
                continue;
            }
            space = false;
            sb.append(ch);
        }
        return sb.toString();
    }

    static List<String> words(String norm) {
        List<String> l = new ArrayList<String>();
        for (String w : norm.split(" ")) {
            if (!w.isEmpty()) l.add(w);
        }
        return l;
    }

    static List<String> contentWords(List<String> words) {
        List<String> l = new ArrayList<String>();
        for (String w : words) {
            if (w.length() < 2) continue;
            boolean stop = false;
            for (String s : STOP) if (s.equals(w)) { stop = true; break; }
            if (!stop) l.add(w);
        }
        return l;
    }

    /** تقسيم الجمل على . ؟ ! ؛ والأسطر — جملة فارغة لا تُحسب. */
    static List<String> sentences(String text) {
        List<String> l = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '.' || ch == '؟' || ch == '!' || ch == '؛' || ch == '\n') {
                if (cur.length() > 0) l.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) l.add(cur.toString());
        return l;
    }

    static int totalWords(List<String> sents) {
        int n = 0;
        for (String s : sents) n += words(normalize(s)).size();
        return n;
    }

    static String pct(double d) {
        int v = (int) Math.round(d * 100);
        return v + "٪";
    }

    static String avgStr(double d) {
        return String.valueOf(Math.round(d * 10) / 10.0);
    }
}
