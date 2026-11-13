package com.hark.neokey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StripAccept — W61: قياس القبول المحلي للشريط الواعي. نقي JVM: صفر android.*.
 *
 * الخارطة: «قياس قبول محلي (أي اقتراح قُبل) يغذي AdaptEngine ويظل في الجهاز».
 *
 * ما يُقاس: المصادر المعلَمة وقت الضغط فقط — لا تخمين ولا ادعاء:
 *   • personal  — رقاقة ★ (طبقة التكرار الشخصية، الشارة المرئية).
 *   • root      — عضو شجرة العائلة (الضغط الطويل ثم الاختيار).
 *   • clip      — رقاقة حافظة (CLIP_MARK / CLIP_SC_MARK).
 *   • strip     — رقاقة عامة من الشريط المعجمي (المصدر غير المعلَم وقت
 *                 الضغط يُحتسب هنا بصدق — لا يُنسب لقاموس بعينه ادعاءً).
 *
 * التغذية الموثقة لـ AdaptEngine: قبول رقاقة شخصية يعني الكلمة اجتازت
 * المقعد الشخصي — تعزيز +1 إضافي فوق تعلم الكتابة (وزن القبول المعلَم).
 * العدّادات تبقى في الجهاز (filesDir/strip_accept.txt — قانون 8).
 *
 * القوانين:
 *   • المصدر الفارغ/المجهول يُحتسب strip (الصدق في العدّ).
 *   • CAP للعدّادات ثابت بالعدّادات الست المعرفة (لا نمو).
 *   • المسح الكامل: wipe() → serialize() == "" (حالة مصنع بايتياً).
 *   • toString يطبع العدّادات فقط.
 *
 * الصيغة: source ␉ count لكل سطر (المصادر الست ثابتة الترتيب).
 */
public final class StripAccept {

    /** المصادر المعلَمة وقت الضغط — ثابتة. */
    public static final String PERSONAL = "personal";
    public static final String ROOT = "root";
    public static final String CLIP = "clip";
    public static final String STRIP = "strip";
    public static final String[] SOURCES = {PERSONAL, ROOT, CLIP, STRIP};

    private final Map<String, Long> counts = new LinkedHashMap<>();

    public StripAccept() { }

    /** عدّ قبولاً واحداً — المصدر المجهول/الفارغ يُحتسب strip (الصدق). */
    public void accept(String source) {
        String s = source == null || source.isEmpty() ? STRIP : source;
        boolean known = false;
        for (String k : SOURCES) if (k.equals(s)) { known = true; break; }
        if (!known) s = STRIP;
        Long c = counts.get(s);
        counts.put(s, (c == null ? 0L : c) + 1L);
    }

    /** عدّاد مصدر — 0 للمجهول. */
    public long count(String source) {
        Long c = source == null ? null : counts.get(source);
        return c == null ? 0L : c;
    }

    /** مجموع القبول الكلي. */
    public long total() {
        long t = 0;
        for (Long c : counts.values()) t += c;
        return t;
    }

    /** المسح الكامل — حالة مصنع بايتياً. */
    public void wipe() { counts.clear(); }

    /** التحليل من نص الملف — الأسطر الفاسدة/المجهولة تُتخطى ولا يُرمى استثناء. */
    public static StripAccept parse(String text) {
        StripAccept a = new StripAccept();
        if (text == null || text.isEmpty()) return a;
        for (String line : text.split("\n")) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length != 2) continue;
            boolean known = false;
            for (String k : SOURCES) if (k.equals(f[0])) { known = true; break; }
            if (!known) continue;
            long n;
            try { n = Long.parseLong(f[1].trim()); } catch (NumberFormatException e) { continue; }
            if (n < 0) continue;
            a.counts.put(f[0], n);
        }
        return a;
    }

    /** التسلسل — المصادر المعرفة فقط بترتيبها الثابت. */
    public String serialize() {
        StringBuilder b = new StringBuilder();
        for (String k : SOURCES) {
            Long c = counts.get(k);
            if (c != null) b.append(k).append('\t').append(c).append('\n');
        }
        return b.toString();
    }

    /**
     * التغذية الموثقة لـ AdaptEngine (W61): قبول رقاقة شخصية = تعزيز +1
     * إضافي فوق تعلم الكتابة — وزن القبول المعلَم، والكل في الجهاز.
     */
    public void feed(AdaptEngine engine, String word, int day) {
        if (engine != null && word != null && !word.isEmpty()) engine.learn(word, day);
    }
}
