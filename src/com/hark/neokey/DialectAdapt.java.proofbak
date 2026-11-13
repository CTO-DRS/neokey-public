package com.hark.neokey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DialectAdapt — W59: «الكيبورد الذي يعرف مكانك». نقي JVM: صفر android.*.
 *
 * الخارطة: «ملف لكل تطبيق (تطبيق العمل فصحى، محادثة العائلة لهجة) — تظل
 * الملفات محلية» + «دمج أوزان الخطوط الثلاثة الموجودة مع القاموس الأساسي
 * بحسب الكشف». SmartSwitch نفسه لا يُمَس حرفاً — هذا الملف طبقة حوله:
 *
 * الملف لكل تطبيق (pref-like store بنمط Exceptions، نص محلي في filesDir):
 *   • record(app, dictId): يُسجَّل فقط قرار لهجة حي بدليل ≥ SmartSwitch.MIN_EVIDENCE
 *     (أقل من ذلك تخمين — لا يُسجَّل أبداً). القرار الصريح للمستخدم
 *     (off/msa/fixed) لا يمر هنا أصلاً — يُرد قبل الكشف كما كان.
 *   • recordMsa(app, hits, words): في تطبيق له قانون لهجة ثابت، ثلاثة قرارات
 *     auto-msa متتالية بلا أي علامة (hits=0 والنافذة ≥ 3 كلمات) تُنزل
 *     القانون إلى الفصحى — اتجاه «تطبيق العمل فصحى» مقيس لا مُدّعى.
 *   • seed(app): بذرة الإقلاع البارد فقط — وضع auto ونافذة فارغة (بداية
 *     الكتابة بلا أي دليل حي) تعيد آخر قانون لهجة مستقر (updates ≥
 *     ESTABLISHED=3) بدل الفصحى العمياء؛ أي دليل حي يظهر فيكسر البذرة
 *     فوراً بقرار SmartSwitch الأصلي (التبديل لا يُكسر — القانون الوحيد
 *     الذي لا يعبره هذا الصنف هو قرار الدليل الحي ≥ MIN_EVIDENCE).
 *   • التخفي (قانون 8): التسجيل يتوقف كلياً (لا تخزين جديد أبداً)؛ الكشف
 *     الحي اللحظي بلا حالة يبقى كما كان قبل W59، وقراءة الملف المحلي
 *     القائم ليست تحليلاً جديداً ولا تخزيناً.
 *
 * دمج الأوزان (الخطوط الثلاثة بحسب الكشف — موثق لا مُدّعى):
 *   • كشف حي: قاموس اللهجة يتصدر بحد cap ثم سدادة الفصحى — قانون C2 الأصلي.
 *   • بذرة (ثقة أنقض): قاموس اللهجة يسهم بنصف الوزن فقط — floor(cap/2)
 *     مدخلات ثم سدادة الفصحى — الثقة الأنقض وزنها أنقض، حتماً.
 *   • الخط الثالث (NN) قانونه الأصلي باقٍ: يملأ الفراغات في التنبؤ فقط
 *     ولا يزيح قاموساً أبداً (Predictor.top3 عبر predictions).
 *
 * الصيغة (سطر لكل تطبيق، TAB فواصل): pkg ␉ dictId ␉ updates ␉ msas
 * الأسطر الفاسدة تُتخطى، السعة CAP=64 (الأحدث استخداماً يبقى).
 */
public final class DialectAdapt {

    /** أقصى تطبيقات محفوظة — الأحدث استخداماً يبقى والأقدم يسقط. */
    static final int CAP = 64;
    /** عدد القرارات الحية قبل أن تصبح بذرة التطبيق جائزة بالإقلاع البارد. */
    static final int ESTABLISHED = 3;
    /** قرارات auto-msa المتتالية بلا علامة لإنزال قانون لهجة إلى الفصحى. */
    static final int DOWNGRADE_HITS0 = 3;
    /** أدنى كلمات في النافذة لعدّ قرار auto-msa دليلاً على الفصحى. */
    static final int MSA_MIN_WORDS = 3;

    static final String SEP = "\u0001";

    /** ملف تطبيق واحد: آخر قاموس، عدد القرارات المسجلة، عدّاد الإنزال للفصحى. */
    public static final class Profile {
        public final String dictId;
        public final int updates;
        public final int msas;

        Profile(String dictId, int updates, int msas) {
            this.dictId = dictId;
            this.updates = updates;
            this.msas = msas;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Profile)) return false;
            Profile p = (Profile) o;
            return dictId.equals(p.dictId) && updates == p.updates && msas == p.msas;
        }

        @Override public int hashCode() {
            return dictId.hashCode() * 31 + updates * 7 + msas;
        }

        @Override public String toString() {
            return dictId + "(u" + updates + ",m" + msas + ")";
        }
    }

    /** الإدخالات بترتيب الاستخدام (الأحدث آخراً) — LinkedHashMap للسعة LRU. */
    private final Map<String, Profile> apps = new LinkedHashMap<>();

    public DialectAdapt() { }

    // ---- التسجيل (يُستدعى من الخدمة فقط عند قرار حي، وفي التخفي لا يُستدعى) ----

    /** تسجيل قرار لهجة حي بدليل كافٍ — يرفع updates ويصفّر عدّاد الفصحى. */
    public void record(String app, String dictId) {
        if (app == null || app.isEmpty() || dictId == null || dictId.isEmpty()) return;
        if (!isDialectDict(dictId)) return;
        Profile p = apps.get(app);
        int updates = (p == null ? 0 : p.updates) + 1;
        put(app, new Profile(dictId, Math.min(updates, 999), 0));
    }

    /**
     * عدّ قرار auto-msa بلا أي علامة — ثلاثة متتالية في تطبيق ذي قانون لهجة
     * تُنزله إلى الفصحى (اتجاه «تطبيق العمل فصحى»). أي علامة واحدة تصفّره.
     */
    public void recordMsa(String app, int hits, int windowWords) {
        if (app == null || app.isEmpty() || hits != 0 || windowWords < MSA_MIN_WORDS) return;
        Profile p = apps.get(app);
        if (p == null || "ar".equals(p.dictId)) return;
        int msas = p.msas + 1;
        if (msas >= DOWNGRADE_HITS0) put(app, new Profile("ar", p.updates, 0));
        else put(app, new Profile(p.dictId, p.updates, msas));
    }

    /** تحديث ترتيب الاستخدام (يُستدعى عند أي قرار حي ولو بلا تسجيل). */
    public void touch(String app) {
        if (app == null || app.isEmpty()) return;
        Profile p = apps.get(app);
        if (p != null) put(app, p);
    }

    private void put(String app, Profile p) {
        apps.remove(app);
        apps.put(app, p);
        while (apps.size() > CAP) {
            String oldest = apps.keySet().iterator().next();
            apps.remove(oldest);
        }
    }

    // ---- القراءة ----

    /** بذرة الإقلاع البارد: قاموس التطبيق المستقر، أو null (فصحى كما كان). */
    public String seed(String app) {
        if (app == null || app.isEmpty()) return null;
        Profile p = apps.get(app);
        if (p == null || p.updates < ESTABLISHED) return null;
        return "ar".equals(p.dictId) ? null : p.dictId;
    }

    /** ملف التطبيق الخام — للبوابة والفحص. */
    public Profile profile(String app) {
        return app == null ? null : apps.get(app);
    }

    public int size() { return apps.size(); }

    // ---- دمج الأوزان (الخطوط الثلاثة بحسب الكشف) ----

    /**
     * الدمج الموثق: كشف حي → اللهجة تتصدر حتى cap ثم سدادة الفصحى؛
     * بذرة → اللهجة بنصف الوزن (floor(cap/2)) ثم سدادة الفصحى.
     * بلا تكرار (أول ظهور يفوز) — قانون الدمج الأصلي محفوظ.
     */
    public static List<String> mergeLines(List<String> dialectWords, List<String> msaWords,
                                          int cap, boolean seeded) {
        List<String> out = new ArrayList<>();
        if (cap <= 0) return out;
        int dialectQuota = seeded ? cap / 2 : cap;
        if (dialectWords != null) {
            for (String x : dialectWords) {
                if (out.size() >= dialectQuota || out.size() >= cap) break;
                if (x != null && !x.isEmpty() && !out.contains(x)) out.add(x);
            }
        }
        if (msaWords != null) {
            for (String x : msaWords) {
                if (out.size() >= cap) break;
                if (x != null && !x.isEmpty() && !out.contains(x)) out.add(x);
            }
        }
        return out;
    }

    private static boolean isDialectDict(String dictId) {
        return "ar_hijazi".equals(dictId) || "ar_najdi".equals(dictId)
                || "ar_gulf".equals(dictId);
    }

    // ---- الصيغة: pkg ␉ dictId ␉ updates ␉ msas (سطر لكل تطبيق) ----------------

    /** التحليل من نص الملف — الأسطر الفاسدة تُتخطى ولا يُرمى استثناء أبداً. */
    public static DialectAdapt parse(String text) {
        DialectAdapt d = new DialectAdapt();
        if (text == null || text.isEmpty()) return d;
        for (String line : text.split("\n")) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length != 4) continue;
            String app = f[0].trim();
            String dictId = f[1].trim();
            int updates, msas;
            try {
                updates = Integer.parseInt(f[2].trim());
                msas = Integer.parseInt(f[3].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (app.isEmpty() || updates < 0 || msas < 0) continue;
            if (!"ar".equals(dictId) && !isDialectDict(dictId)) continue;
            d.put(app, new Profile(dictId, Math.min(updates, 999), Math.min(msas, 999)));
        }
        return d;
    }

    /** التسلسل إلى نص الملف (سطر لكل تطبيق، ترتيب الاستخدام تصاعدياً). */
    public String serialize() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Profile> e : apps.entrySet()) {
            b.append(e.getKey()).append('\t').append(e.getValue().dictId).append('\t')
                    .append(e.getValue().updates).append('\t').append(e.getValue().msas).append('\n');
        }
        return b.toString();
    }
}
