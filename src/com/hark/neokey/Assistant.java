package com.hark.neokey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * المساعد المحلي — المرحلة 1، البند D1 (الأسبوع 6): ثلاث قدرات ذكاء تعمل
 * 100% على الجهاز، صفر شبكة، صفر android.*، حتمية بالكامل (نفس المدخل =
 * نفس المخرج على أي جهاز، بلا تخزين أي شيء عن المستخدم):
 *
 *   1. تلخيص الحافظة    summarize(text, maxSentences) — استخلاص جمل بتردد
 *      المصطلحات (بلا كلمات وقف) مع الحفاظ على ترتيبها الأصلي.
 *   2. إعادة الصياغة    rephrase(text, mode) — جدول عبارات باتجاهين:
 *      simple     مكتب/رسمي → لغة أبسط
 *      respectful عامي خليجي → أرقى وأكثر احتراماً
 *   3. ثلاث نبرات       draft(note, tone) — صياغة رد على رسالة بثلاث نبرات
 *      (رسمي، ودّي، قصير) بقالب يعتمد نية الرسالة المستخرجة من كلماتها
 *      (شكر/اعتذار/طلب/متابعة/تأكيد/سؤال/عام) + تلميع عبارة النص بالجداول.
 *
 * الصدق المنهجي (نفس منهجية C1): هذه قواعد جاهزة مُقاسة — ليست نموذجاً
 * عصبياً. الفجوة موثقة في التقرير: الصيغ المرفومة غير الموجودة في الجدول
 * تمر كما هي، والتلخيص استخلاصي لا توليدي. الاتصال الخارجي (تحسين سحابي)
 * يبقى خلف NetGate — القدرات هنا تعمل بلا أي اتصال إطلاقاً.
 */
public final class Assistant {

    // ------------------------------------------------------------------ 1) تلخيص

    /** كلمات الوقف (مطبَّعة بـ normAr) — لا تدخل في تردد المصطلحات. */
    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "من", "في", "على", "عن", "الى", "التي", "الذي", "هذا", "هذه", "ذلك", "تلك",
            "هو", "هي", "هم", "كان", "كانت", "ما", "لا", "مع", "او", "ثم", "قد", "لقد",
            "كل", "بين", "بعد", "قبل", "عند", "عندما", "حتى", "اذا", "لكن", "بل", "بعض",
            "غير", "كما", "اي", "ايضا", "ان", "لان", "لكي", "حول", "دون", "نحو", "سوف",
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were"));

    /** جمل التلخيص: تُفصل على نهايات الجمل والأسطر — مع إبقاء علامة النهاية. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '؟' || c == '؛' || c == ';' || c == '\n') {
                if (cur.length() > 0) {
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) out.add(c == '\n' ? s : s + c);   // علامة النهاية جزء من الجملة
                    cur.setLength(0);
                }
            } else cur.append(c);
        }
        if (cur.length() > 0) { String s = cur.toString().trim(); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    /** مفاتيح التردد: normAr + نزع «ال» التعريف للجذر (للقياس فقط لا للعرض). */
    static String freqKey(String token) {
        String n = Dialect.normAr(token);
        for (String p : new String[]{"وال", "بال", "كال", "فال", "ال"})
            if (n.startsWith(p) && n.length() > p.length() + 1) { n = n.substring(p.length()); break; }
        return n;
    }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && !Character.isLetterOrDigit(s.charAt(i))) i++;
            int j = i;
            while (j < n && Character.isLetterOrDigit(s.charAt(j))) j++;
            if (j > i) out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    /**
     * تلخيص استخلاصي: أفضل maxSentences جملة بتردد المصطلحات، بترتيبها الأصلي.
     * نص فارغ أو maxSentences ≤ 0 → "" (حارس لا استثناء). جمل أقل من المطلوب →
     * النص كله مصغّر المسافات. تكرار النص مرتين = مخرج واحد (حتمي).
     */
    public static String summarize(String text, int maxSentences) {
        if (text == null || maxSentences <= 0) return "";
        String t = text.replace('\u200F', ' ').replace('\u200E', ' ').trim();
        if (t.isEmpty()) return "";
        List<String> sents = sentences(t);
        if (sents.size() <= maxSentences) return t.replaceAll("\\s+", " ");
        // تردد المصطلحات (بلا وقف، بلا كلمات قصيرة جداً)
        Map<String, Integer> freq = new HashMap<>();
        for (String s : sents)
            for (String w : words(s)) {
                String k = freqKey(w);
                if (k.length() < 2 || STOP.contains(k)) continue;
                Integer c = freq.get(k);
                freq.put(k, c == null ? 1 : c + 1);
            }
        // درجة كل جملة: مجموع التردد ÷ (عدد الكلمات)^0.6 — لا تحابي الجملة القصيرة المفرطة
        double[] score = new double[sents.size()];
        for (int i = 0; i < sents.size(); i++) {
            List<String> ws = words(sents.get(i));
            double sum = 0;
            for (String w : ws) {
                String k = freqKey(w);
                Integer c = freq.get(k);
                if (c != null) sum += c;
            }
            score[i] = ws.isEmpty() ? 0 : sum / Math.pow(ws.size(), 0.6);
        }
        // أفضل k جملة: الدرجات الأعلى أولاً، التعادل للسابق — ثم ترتيب أصلي بالفهرس
        java.util.TreeSet<Integer> pick = new java.util.TreeSet<>();  // يبقي الترتيب الأصلي
        List<Integer> byScore = new ArrayList<>();
        for (int i = 0; i < sents.size(); i++) byScore.add(i);
        java.util.Collections.sort(byScore, (a, b) -> {
            int c = Double.compare(score[b], score[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int i = 0; i < maxSentences && i < byScore.size(); i++) pick.add(byScore.get(i));
        StringBuilder out = new StringBuilder();
        for (int idx : pick) {
            if (out.length() > 0) out.append(' ');
            out.append(sents.get(idx));
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 2) إعادة الصياغة

    /** اتجاها إعادة الصياغة. */
    public static final String SIMPLE = "simple";          // مكتب → أبسط
    public static final String RESPECTFUL = "respectful";  // عامي → أكثر احتراماً

    /** جداول الكلمات المفردة: مفتاح normAr → بديل (كُتب بالفصحى القياسية). */
    private static final Map<String, String> RESPECTFUL_W = new LinkedHashMap<>();
    private static final Map<String, String> SIMPLE_W = new LinkedHashMap<>();
    /** عبارات متعددة الكلمات: normAr مفصولة بمسافة → بديل. */
    private static final Map<String, String> RESPECTFUL_P = new LinkedHashMap<>();
    private static final Map<String, String> SIMPLE_P = new LinkedHashMap<>();

    static {
        // عامي خليجي → أرقى (مفاتيح مطبَّعة بـ normAr: أإآ→ا، ة→ه، ى→ي)
        String[][] rw = {
                {"ابغي", "أرغب"}, {"ابي", "أرغب"}, {"ابغاك", "أرجوك"}, {"ابيك", "أرجوك"},
                {"وش", "ما"}, {"وشو", "ما"}, {"شلون", "كيف"}, {"شلونك", "كيف حالك"},
                {"وشلونك", "كيف حالك"}, {"وشلونكم", "كيف حالكم"}, {"وشلون", "كيف"},
                {"ليش", "لماذا"}, {"الحين", "الآن"}, {"دحين", "الآن"}, {"ذلحين", "الآن"},
                {"مب", "ليس"}, {"مو", "ليس"}, {"عشان", "لأن"}, {"علشان", "لأن"},
                {"زين", "جيد"}, {"زينه", "جيدة"}, {"وايد", "جداً"}, {"كذا", "هكذا"},
                {"شوي", "قليل"}, {"منو", "مَن"}, {"وين", "أين"}, {"وينك", "أين أنت"},
                {"خوش", "طيب"}, {"عيال", "أطفال"}, {"طاري", "خبر"}, {"عقب", "بعد"},
                {"مدري", "لا أعلم"}, {"ادري", "أعلم"}, {"تدرين", "تعلمين"}, {"هين", "سهل"},
                {"هينه", "سهلة"}, {"مليح", "جيد"}, {"مليحه", "جيدة"}, {"شخبارك", "كيف حالك"},
                {"عيل", "إذن"}, {"بعدني", "ما زلت"}, {"ابغيه", "أرغب به"}, {"يبي", "يرغب"},
                {"نبي", "نرغب"}, {"تبغي", "ترغب"}, {"دريت", "علمت"},
        };
        for (String[] p : rw) RESPECTFUL_W.put(Dialect.normAr(p[0]), p[1]);
        // عبارات: عامي → أرقى
        String[][] rp = {
                {"لو سمحت ابغي", "هل من الممكن"},
                {"ابغي منك", "أرجو منك"},
                {"وش رايك", "ما رأيك"},
                {"وش رايكم", "ما رأيكم"},
                {"شخبارك شلونك", "كيف حالك"},
        };
        for (String[] p : rp) RESPECTFUL_P.put(Dialect.normAr(p[0]), p[1]);
        // مكتب → أبسط
        String[][] sw = {
                {"بخصوص", "عن"}, {"بشأن", "عن"}, {"قبيل", "قبل"}, {"بُعيد", "بعد"},
                {"حاليا", "الآن"}, {"لاسيما", "خاصة"}, {"علاوة", "وأيضاً"},
                {"يفيدون", "يخبرون"}, {"استفسار", "سؤال"}, {"موافاة", "إرسال"},
                {"تفضلوا", "من فضلكم"}, {"تكرموا", "من فضلكم"}, {"إفادة", "إخبار"},
                {"إفادتنا", "إخبارنا"}, {"توضيحه", "شرحه"},
        };
        for (String[] p : sw) SIMPLE_W.put(Dialect.normAr(p[0]), p[1]);
        // عبارات: مكتبية → أبسط (أطولها أولاً عند التطبيق)
        String[][] sp = {
                {"نرجو التكرم", "رجاءً"},
                {"نرجو منكم التكرم", "رجاءً"},
                {"يفيد الإشارة", "ملاحظة"},
                {"في حال", "إذا"},
                {"بناءً على", "حسب"},
                {"استنادا إلى", "حسب"},
                {"على وجه الخصوص", "خاصة"},
                {"على الرغم من", "رغم"},
                {"بالرغم من", "رغم"},
                {"في الوقت الحالي", "الآن"},
                {"بالإضافة إلى ذلك", "وكذلك"},
                {"إضافة إلى ذلك", "وكذلك"},
                {"من خلال", "عبر"},
                {"يتم إجراء", "يُجرى"},
                {"يتم تنفيذ", "يُنفَّذ"},
                {"حيث تم", "وتم"},
                {"ولسيتم", "وسيُجرى"},
        };
        for (String[] p : sp) SIMPLE_P.put(Dialect.normAr(p[0]), p[1]);
    }

    /** مرآة للبوابة والتوثيق: عدد قواعد كل اتجاه (كلمات + عبارات). */
    public static int rules(String mode) {
        boolean simple = SIMPLE.equals(mode);
        return (simple ? SIMPLE_W.size() : RESPECTFUL_W.size())
                + (simple ? SIMPLE_P.size() : RESPECTFUL_P.size());
    }

    /** تقسيم يحافظ على الفواصل: مقاطع كلمة (حروف/أرقام + تشكيل وتطويل) + محارف مفردة. */
    static List<String> segments(String s) {
        List<String> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= 0x064B && c <= 0x0652) || c == 0x0640) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j))
                        || (s.charAt(j) >= 0x064B && s.charAt(j) <= 0x0652) || s.charAt(j) == 0x0640)) j++;
                out.add(s.substring(i, j));
                i = j;
            } else { out.add(String.valueOf(c)); i++; }
        }
        return out;
    }

    private static boolean word(String s) {
        return !s.isEmpty() && (Character.isLetterOrDigit(s.charAt(0))
                || (s.charAt(0) >= 0x064B && s.charAt(0) <= 0x0652) || s.charAt(0) == 0x0640);
    }

    /**
     * إعادة صياغة بجدول عبارات: العبارات الأطول أولاً ثم الكلمات المفردة،
     * مطابقة على normAr بمفاتيح كاملة (حدود كلمة حقيقية — لا استبدال داخل
     * كلمة مركبة). نص فارغ → "". وضع غير معروف → نص كما هو (لا تخمين).
     */
    public static String rephrase(String text, String mode) {
        if (text == null || text.isEmpty()) return "";
        boolean simple = SIMPLE.equals(mode), respect = RESPECTFUL.equals(mode);
        if (!simple && !respect) return text;
        Map<String, String> pw = simple ? SIMPLE_P : RESPECTFUL_P;
        Map<String, String> ww = simple ? SIMPLE_W : RESPECTFUL_W;
        // مفاتيح العبارات مرتبة: الأكثر كلمات أولاً (حتمي)
        List<String> pk = new ArrayList<>(pw.keySet());
        java.util.Collections.sort(pk, (a, b) -> {
            int c = Integer.compare(b.split(" ").length, a.split(" ").length);
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> seg = segments(text);
        // 1) العبارات: نافذة على المقاطع الكلامية المتتالية
        for (String key : pk) {
            String[] kw = key.split(" ", -1);
            for (int i = 0; i < seg.size(); i++) {
                if (!word(seg.get(i))) continue;
                if (i + kw.length * 2 - 2 >= seg.size()) break;   // كل كلمة يحيطها فاصل
                boolean hit = true;
                for (int k = 0; k < kw.length && hit; k++) {
                    int wIdx = i + k * 2;
                    if (wIdx >= seg.size() || !word(seg.get(wIdx))
                            || !kw[k].equals(Dialect.normAr(seg.get(wIdx)))) hit = false;
                }
                if (hit) {
                    int spanEnd = i + (kw.length - 1) * 2;
                    seg.set(i, pw.get(key));
                    for (int j = i + 1; j <= spanEnd; j++) seg.set(j, "");
                }
            }
        }
        // 2) الكلمات المفردة على normAr
        for (int i = 0; i < seg.size(); i++)
            if (word(seg.get(i))) {
                String rep = ww.get(Dialect.normAr(seg.get(i)));
                if (rep != null) seg.set(i, rep);
            }
        StringBuilder out = new StringBuilder();
        for (String s : seg) out.append(s);
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    // ------------------------------------------------------------------ 3) ثلاث نبرات

    /** أسماء النبرات بالترتيب: رسمي، ودّي، قصير. */
    public static final String[] TONE_NAMES = {"رسمي", "ودّي", "قصير"};
    public static final int TONE_FORMAL = 0, TONE_FRIENDLY = 1, TONE_SHORT = 2;

    /** نوايا الرسالة — تُستخرج من كلمات المفتاح (أول نية مطابقة بالترتيب). */
    private static final String[][] INTENTS = {
            // {اسم النية، كلماتها المطبَّعة مفصولة بمسافة}
            {"thanks", "شكرا مشكور ممتن اشكرك يعطيك العافية"},
            {"apology", "اسف اعتذر اعتذار متاخر تأخر"},
            {"request", "ارجو ممكن لوسمحت من فضلك احتاج ابغي ابي نرجو"},
            {"followup", "متابعة تذكير عاجل مستعجل"},
            {"confirm", "تم تمام اكيد مؤكد سأقوم سوف"},
            {"question", "هل كيف متى اين وين لماذا ليش ما"},
    };

    /** نية النص: أول نية كلماتها موجودة فيه (حتمي)، أو "general". */
    public static String intent(String note) {
        List<String> ws = words(Dialect.normAr(note == null ? "" : note));
        java.util.Set<String> set = new java.util.HashSet<>(ws);
        for (String[] it : INTENTS)
            for (String k : it[1].split(" "))
                if (set.contains(k)) return it[0];
        return "general";
    }

    /** جوهر الملاحظة: نصها مصغّر المسافات بلا علامات نهاية مكررة. */
    private static String core(String note) {
        if (note == null) return "";
        String c = note.replaceAll("\\s+", " ").trim();
        while (!c.isEmpty() && ".!؟?؛;\n-–—".indexOf(c.charAt(c.length() - 1)) >= 0)
            c = c.substring(0, c.length() - 1).trim();
        return c;
    }

    /**
     * صياغة رد بثلاث نبرات — القالب من نية الملاحظة، ونص الملاحظة نفسه
     * يبقى هو الجوهر (لا نبتكر حقائق). مخرجات الثلاث نبرات متمايزة حتماً.
     */
    public static String[] tones(String note) {
        return new String[]{draft(note, TONE_FORMAL), draft(note, TONE_FRIENDLY), draft(note, TONE_SHORT)};
    }

    /** رد بنبرة واحدة (0 رسمي، 1 ودّي، 2 قصير) — خارج النطاق يرتد لرسمي. */
    public static String draft(String note, int tone) {
        String c = core(rephrase(note, SIMPLE));   // الملاحظة نفسها ملمّعة بالاتجاه الأبسط
        String it = intent(note);
        if (tone != TONE_FORMAL && tone != TONE_FRIENDLY && tone != TONE_SHORT) tone = TONE_FORMAL;
        switch (tone) {
            case TONE_FORMAL: return formal(it, c);
            case TONE_FRIENDLY: return friendly(it, c);
            default: return shortTone(it, c);
        }
    }

    private static String formal(String it, String c) {
        String body;
        if (c.isEmpty()) body = "بخصوص موضوعكم، نرجو التكرم بالاطلاع وإفادتنا بما يلزم.";
        else switch (it) {
            case "thanks": body = "نشكر لكم تواصلكم بخصوص: " + c + ". نقدّر لكم هذا."; break;
            case "apology": body = "بخصوص: " + c + " — نعتذر عن أي تأخر ونتشرف بمتابعة الموضوع."; break;
            case "request": body = "الموضوع: " + c + ".\nنرجو منكم التكرم بالنظر في الموضوع أعلاه، ولكم جزيل الشكر."; break;
            case "followup": body = "نرجو التكرم بمتابعة: " + c + "، وإفادتنا بأي جديد."; break;
            case "confirm": body = "نفيدكم بأن: " + c + ". وسيتم إبلاغكم بأي مستجدات."; break;
            case "question": body = "نرجو التكرم بالإفادة عن: " + c + "؟"; break;
            default: body = "بخصوص: " + c + " — نرجو الاطلاع وإفادتنا برأيكم الكريم.";
        }
        return "السلام عليكم ورحمة الله وبركاته،\n" + body + "\nمع التقدير،";
    }

    private static String friendly(String it, String c) {
        String body;
        if (c.isEmpty()) body = "وش رايكم نكمل الموضوع؟";
        else switch (it) {
            case "thanks": body = "مشكورين على: " + c + "! ما قصرتوا."; break;
            case "apology": body = "أعتذر عن التأخر بخصوص: " + c + " — أسويها حالاً."; break;
            case "request": body = "ممكن مساعدة في: " + c + "؟ مشكور مقدماً!"; break;
            case "followup": body = "أي جديد في: " + c + "؟"; break;
            case "confirm": body = "تمام! " + c + " — وأخبرك بأي جديد."; break;
            case "question": body = "سؤال سريع: " + c + "؟"; break;
            default: body = "بخصوص: " + c + " — وش رايكم؟";
        }
        return "هلا!\n" + body;
    }

    private static String shortTone(String it, String c) {
        if (c.isEmpty()) return "خبرني إذا تحتاج شي.";
        switch (it) {
            case "thanks": return "مشكور على: " + c + "!";
            case "apology": return "آسف على التأخر — " + c + ".";
            case "request": return "ممكن " + c + "؟ وشكراً!";
            case "followup": return "أي جديد في " + c + "؟";
            case "confirm": return "تم — " + c + ".";
            case "question": return "سؤال: " + c + "؟";
            default: return "بخصوص " + c + " — كمّلوا فوقها.";
        }
    }

    private Assistant() { }
}
