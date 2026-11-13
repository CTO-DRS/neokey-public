package com.hark.neokey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AdaptEngine — W60: «يتعلم منك ولا يغادر جهازك». نقي JVM: صفر android.*،
 * والرابع عبر عقد W55 (implements Engine — قانون 9: التكامل لا الشقّ).
 *
 * الخارطة: «طبقة تكرار شخصية محلية (توهين زمني، سقف مقيس) تدمج مع Predictor
 * top3 بترجيح موثق، ومسح كامل من زر واحد، ووقف تام في التخفي» + «شفافية
 * المصدر: كل اقتراح يحمل شارة مصدره».
 *
 * الطبقة: عدّاد تكرار لكل كلمة يكتبها المستخدم (محلية filesDir — لا يغادر
 * الجهاز)، بتوهين زمني حتمي: score = count · 0.5^(الأيام منذ آخر استخدام ÷
 * HALF_LIFE=14) — الكلمة المهجورة تنطفئ كما تنطفئ عادة اليد، والكلمة
 * الحية تبقى. السقف المقيس CAP=256 (الأحدث استخداماً يبقى — LRU).
 * اليوم (epochDay) يضخّه الخدمة عبر setToday عند بدء الحقل — بلا ساعة
 * داخل المحرك (العرق حتمي على أي جهاز: نفس المدخلات = نفس الترتيب).
 *
 * الدمج الموثق (ترجيح حتمي لا يُدّعى):
 *   • مقطع التكوين (composing): مرشحات التكرار بالبادئة مرتبة تنازلياً
 *     بالنقش الفعّال، حتى 3 — عبر Engines.run (نقطة الالتحام الواحدة،
 *     المصدر personal) — append-only لا يزيح أحداً في الشريط.
 *   • مقطع التنبؤ (prev2/prev1): المقعد الشخصي الواحد (أعلى نقش) قبل
 *     السدادة العصبية — الشخصي يسبق الشبكة، والمعجم يسبق الجميع.
 *   • الشارة: كل مرشح شخصي يحمل "★ " بادئة مرئية على الرقاقة نفسها —
 *     المستخدم يرى لماذا اقترح الكيبورد (شفافية المصدر)، والضغط يجرد
 *     الشارة ويكتب الكلمة الخالصة، والضغط الطويل يجرد قبل البحث عن الجذر.
 *
 * القوانين:
 *   • التخفي (قانون 8): لا تعلّم ولا إجابة في التخفي إطلاقاً — التعلم
 *     موقع استدعاءه في finishWord خلف حارس التخفي (قانون W59 نفسه)،
 *     والاستدعاءات عبر Engines.run لا تقع في التخفي (حارس الخدمة).
 *   • المسح الكامل: wipe() يعيد حالة المصنع بايتياً — serialize() يصبح ""
 *     والملف يحذف، وparse("") == حالة المصنع.
 *   • الخصوصية: toString يطبع العدّادات فقط — كلمات المستخدم لا تُسلسل
 *     أبداً (قانون Ctx نفسه).
 *   • الفشل المغلق: run() يبني من مخزن مفكوك فقط — ملف فاسد يُتخطى سطره
 *     ولا يبني طبقة نصفية.
 *
 * الصيغة (سطر لكل كلمة): word ␉ count ␉ lastDay — lastDay أيام إEpoch.
 */
public final class AdaptEngine implements Engine {

    /** أقصى كلمات شخصية محفوظة — الأحدث استخداماً يبقى والأقدم يسقط. */
    static final int CAP = 256;
    /** نصف عمر التوهين الزمني بالأيام — القياس الموثق (score ينصفي كل 14 يوماً). */
    static final int HALF_LIFE = 14;
    /** أقل نقش فعّال يعتبر المرشح جائزاً للعرض (تحت هذا: لا اقتراح). */
    static final double MIN_SCORE = 0.5;
    /** شارة المصدر المرئية على كل رقاقة شخصية — المستخدم يرى لماذا اقترحنا. */
    public static final String MARK = "★ ";

    /** إدخال واحد: كلمة، عدد الاستخدام، يوم آخر استخدام. */
    public static final class Entry {
        public final String word;
        public final int count;
        public final int lastDay;

        Entry(String word, int count, int lastDay) {
            this.word = word;
            this.count = count;
            this.lastDay = lastDay;
        }

        /** النقش الفعّال بتوهين زمني حتمي — count · 0.5^(الأيام ÷ 14). */
        public double score(int today) {
            int days = today - lastDay;
            if (days < 0) days = 0;
            return count * Math.pow(0.5, (double) days / HALF_LIFE);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return word.equals(e.word) && count == e.count && lastDay == e.lastDay;
        }

        @Override public int hashCode() { return word.hashCode() * 31 + count * 7 + lastDay; }

        @Override public String toString() { return "entry(u" + count + ",d" + lastDay + ")"; }
    }

    /** الإدخالات بترتيب الاستخدام (الأحدث آخراً) — LRU للسعة. */
    private final Map<String, Entry> words = new LinkedHashMap<>();
    /** اليوم المضخوم من الخدمة — بلا ساعة داخل المحرك (حتمية كاملة). */
    private volatile int today;

    public AdaptEngine() { }

    @Override public String id() { return "personal"; }

    /** اليوم الحالي (أيام إEpoch) — تضخّه الخدمة عند بدء الحقل؛ للتخمين الحتمي في الاختبار. */
    public void setToday(int day) { this.today = day; }

    private int today() { return today; }

    // ---- التعلم (يُستدعى من الخدمة عند إكمال الكلمة، خلف حارس التخفي) ----

    /** عدّ استخداماً واحداً — كلمة فارغة/طويلة/بها فواصل الصيغة تنفض، السقف يقص بالأقدم. */
    public void learn(String word, int day) {
        if (word == null || word.isEmpty() || word.length() > 40) return;
        if (word.indexOf('\t') >= 0 || word.indexOf('\n') >= 0) return;
        Entry p = words.get(word);
        int count = (p == null ? 0 : p.count) + 1;
        put(new Entry(word, Math.min(count, 9999), Math.max(day, 0)));
    }

    private void put(Entry e) {
        words.remove(e.word);
        words.put(e.word, e);
        while (words.size() > CAP) {
            String oldest = words.keySet().iterator().next();
            words.remove(oldest);
        }
    }

    // ---- القراءة ----

    /** أعلى الكلمات بالنقش الفعّال (توهين حتمي على اليوم المضخوم، مرتبة تنازلياً). */
    public List<Entry> top(int limit) {
        List<Entry> all = new ArrayList<>();
        for (Entry e : words.values()) {
            if (e.score(today()) >= MIN_SCORE) all.add(e);
        }
        all.sort((a, b) -> {
            double sa = a.score(today()), sb = b.score(today());
            if (sa != sb) return sa > sb ? -1 : 1;
            return b.lastDay - a.lastDay;   // التعادل: الأحدث استخداماً يفوز
        });
        if (limit >= 0 && all.size() > limit) all = new ArrayList<>(all.subList(0, limit));
        return all;
    }

    public Entry entry(String word) { return word == null ? null : words.get(word); }

    public int size() { return words.size(); }

    // ---- العقد: run(Ctx) — المصدر personal، بادئة الشارة على كل مرشح ----

    /**
     * مقطع التكوين: مرشحات البادئة بالنقش الفعّال حتى 3؛ مقطع التنبؤ
     * (بلا تكوين): المقعد الشخصي الواحد (أعلى نقش). الحد 8 من المرسل،
     * والنتيجة لا تفارغ أبداً (fail-closed بالتصميم).
     */
    @Override public String[] run(Ctx ctx) {
        if (ctx == null) return new String[0];
        List<Entry> ranked = top(-1);
        if (!ctx.composing.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Entry e : ranked) {
                if (out.size() >= 3) break;
                if (e.word.startsWith(ctx.composing) && e.word.length() > ctx.composing.length())
                    out.add(MARK + e.word);
            }
            return out.toArray(new String[0]);
        }
        if (ranked.isEmpty()) return new String[0];
        return new String[]{MARK + ranked.get(0).word};
    }

    // ---- المسح الكامل: حالة المصنع بايتياً ----

    /** مسح كامل — بعد هذا serialize() == "" والملف يحذف (حالة مصنع). */
    public void wipe() { words.clear(); }

    /**
     * ركوب الشريط (نمط familyInto/tashkeelInto/nudgeInto): يلحق مرشحات
     * المصدر personal فقط — append-only لا يزيح أحداً أبداً، والبادئات
     * تحمل الشارة ★ (شفافية المصدر على الرقاقة نفسها).
     */
    public static void personalInto(List<String> strip, Engine.Cand[] cands, int cap) {
        if (strip == null || cands == null) return;
        for (Engine.Cand c : cands) {
            if (strip.size() >= cap) return;
            if (c != null && "personal".equals(c.source) && !c.text.isEmpty()
                    && !strip.contains(c.text)) strip.add(c.text);
        }
    }

    // ---- الصيغة: word ␉ count ␉ lastDay (سطر لكل كلمة) ------------------------

    /** التحليل من نص الملف — الأسطر الفاسدة تُتخطى ولا يُرمى استثناء أبداً. */
    public static AdaptEngine parse(String text) {
        AdaptEngine a = new AdaptEngine();
        if (text == null || text.isEmpty()) return a;
        for (String line : text.split("\n")) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length != 3) continue;
            int count, day;
            try {
                count = Integer.parseInt(f[1].trim());
                day = Integer.parseInt(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String w = f[0];
            if (w.isEmpty() || count <= 0 || day < 0) continue;
            a.put(new Entry(w, Math.min(count, 9999), Math.min(day, 999999)));
        }
        return a;
    }

    /** التسلسل إلى نص الملف (سطر لكل كلمة، ترتيب الاستخدام تصاعدياً). */
    public String serialize() {
        StringBuilder b = new StringBuilder();
        for (Entry e : words.values()) {
            b.append(e.word).append('\t').append(e.count).append('\t').append(e.lastDay).append('\n');
        }
        return b.toString();
    }
}
