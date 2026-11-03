package com.hark.neokey;

import java.util.List;

/**
 * SmartSwitch — المرحلة 1، البند C2 (الأسبوع 6): «تبديل ذكي بين الفصحى واللهجة
 * حسب السياق» مع احترام إعدادات المستخدم. نقي JVM: صفر android.*.
 *
 * المبدأ: القاموس الفصيح (ar) يبقى الأساس، وعندما يكشف Dialect لهجة خليجية
 * في نافذة الكتابة الأخيرة يُدمج قاموس اللهجة في الترشيح (كلمات اللهجة أولاً
 * ثم سدادة الفصحى) — «بديلاً عن الفصحى في التنبؤ عند الحاجة» كما في الخارطة.
 *
 * الأوضاع (pref dialect_mode، الافتراضي auto):
 *   off        → فصحى فقط، بلا كشف (مثل ما قبل هذا الأسبوع تماماً).
 *   msa        → فصحى صريح، الكشف يعمل لا شيء (احترام إعداد المستخدم حرفياً).
 *   auto       → الكشف من السياق: دليل خليجي قوي في النافذة ⇒ قاموس اللهجة.
 *   hijazi/najdi/gulf → لهجة مثبّتة بقرار المستخدم، بلا كشف.
 *
 * مانع التذبذب (حتمي، بلا حالة): التبديل للهجة يحتاج دليلين فأكثر في نافذة
 * الكشف (آخر 12 كلمة) — كلمة لهجة واحدة وسط فصحى لا تقلب القاموس؛ والعودة
 * للفصحى تلقائية لحظة أن يخلو السياق من دليل (كاشف بلا حالة أصلاً). هكذا
 * نفس المدخل = نفس القرار على أي جهاز، ولا شيء يُخزن عن المستخدم.
 *
 * المخرجات: Decision{dictId للقاموس المدمج، اللهجة، عدد الدليل، سبب مقروء}.
 * dictId يطابق أسماء الأصول assets/ar_*.txt التي أنتجها خط dictsrc في C1.
 */
public final class SmartSwitch {

    public static final String OFF = "off";
    public static final String AUTO = "auto";
    public static final String MSA = "msa";

    /** أدنى دليل (كلمات دالة) للتبديل للهجة في وضع auto — أقل من ذلك: فصحى. */
    public static final int MIN_EVIDENCE = 2;

    /** قرار التبديل: أي قاموس يُدمج، ولماذا. */
    public static final class Decision {
        public final String dictId;    // "ar" | "ar_hijazi" | "ar_najdi" | "ar_gulf"
        public final String dialect;   // null للفصحى
        public final int evidence;     // كلمات الدليل في النافذة (−1 للوضع المثبّت)
        public final String reason;    // off / msa-mode / fixed:<d> / auto:<d> / weak-evidence

        Decision(String dictId, String dialect, int evidence, String reason) {
            this.dictId = dictId;
            this.dialect = dialect;
            this.evidence = evidence;
            this.reason = reason;
        }

        /** صحيح إن كان القرار يفعّل قاموس لهجة (لا الفصحى). */
        public boolean dialectActive() { return dialect != null; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Decision)) return false;
            Decision d = (Decision) o;
            return dictId.equals(d.dictId)
                    && (dialect == null ? d.dialect == null : dialect.equals(d.dialect))
                    && evidence == d.evidence && reason.equals(d.reason);
        }

        @Override public int hashCode() {
            return dictId.hashCode() * 31 + evidence * 7 + reason.hashCode();
        }

        @Override public String toString() {
            return dictId + "(" + reason + ",e" + evidence + ")";
        }
    }

    /** الأوضاع الصالحة للـ pref — أي قيمة أخرى ترتد إلى auto (لا يُرمى استثناء). */
    public static boolean validMode(String m) {
        return OFF.equals(m) || AUTO.equals(m) || MSA.equals(m)
                || Dialect.HIJAZI.equals(m) || Dialect.NAJDI.equals(m) || Dialect.GULF.equals(m);
    }

    /** معرّف القاموس للهجة — مطابق لأسماء الأصول. */
    public static String dictFor(String dialect) {
        if (Dialect.HIJAZI.equals(dialect)) return "ar_hijazi";
        if (Dialect.NAJDI.equals(dialect)) return "ar_najdi";
        if (Dialect.GULF.equals(dialect)) return "ar_gulf";
        return "ar";
    }

    /** قرار الوضع المثبّت: لهجة بقرار المستخدم بلا كشف. */
    private static Decision fixed(String mode) {
        return new Decision(dictFor(mode), mode, -1, "fixed:" + mode);
    }

    private static Decision msaDecision(String reason, int evidence) {
        return new Decision("ar", null, evidence, reason);
    }

    /**
     * القرار لنافذة الكلمات الأخيرة (تُقطّع في Dialect.tokenize).
     * recentWords الفارغة أو null في وضع auto = فصحى (بداية الكتابة).
     */
    public static Decision decide(List<String> recentWords, String mode) {
        if (!validMode(mode)) mode = AUTO;
        if (OFF.equals(mode)) return msaDecision("off", 0);
        if (MSA.equals(mode)) return msaDecision("msa-mode", 0);
        if (!AUTO.equals(mode)) return fixed(mode);
        Dialect.Result r = Dialect.detectWords(recentWords);
        if (Dialect.MSA.equals(r.dialect)) return msaDecision("auto-msa", 0);
        if (r.hits < MIN_EVIDENCE) return msaDecision("weak-evidence", r.hits);
        return new Decision(dictFor(r.dialect), r.dialect, r.hits, "auto:" + r.dialect);
    }

    private SmartSwitch() { }
}
