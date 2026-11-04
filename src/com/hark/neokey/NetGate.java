package com.hark.neokey;

/**
 * بوابة أذونات الاتصال الخارجي — المرحلة 1، البند D1 (الأسبوع 6): «بوابة
 * أذونات واضحة لكل اتصال خارجي». نقي JVM: صفر android.*، بلا شبكة أصلاً.
 *
 * الفلسفة: **الفشل المغلق** — أي حالة غامضة = منع. لا يمرّ اتصال خارجي واحد
 * إلا بمستويين: مفتاح رئيسي (net_state) ثم إذن القدرة نفسها (net_<cap>)،
 * وكل قدرة مقيدة بنطاق شبكي واحد مسموح (قائمة بيضاء حرفية) — حتى لو سُرق
 * مفتاح المستخدم لا يمكن إرسال أي شيء لنطاق آخر.
 *
 *   net_state  : "off" (الافتراضي) | "on" — المفتاح الرئيسي للاتصال الخارجي.
 *   net_<cap>  : "off" (الافتراضي) | "ask" | "on" — إذن القدرة نفسها.
 *
 * القرار (حتمي):
 *   master off/غائب            → DENIED (master_off) — الافتراضي مغلق.
 *   قدرة غير معروفة            → DENIED (unknown_cap) — لا قدرات مكتشفة تلقائياً.
 *   إذن القدرة off/غائب        → DENIED (cap_off).
 *   إذن "ask" وبدون موافقة     → ASK — تُعرض نافذة موافقة صريحة للمستخدم.
 *   إذن "ask" مع موافقة الآن   → ALLOWED (ask_granted) — موافقة لهذا الإرسال.
 *   إذن "on"                   → ALLOWED (cap_on).
 *
 * الخصوصية: سطر التدقيق لا يسجل أي نص مستخدم إطلاقاً — القدرة والقرار
 * والزمن فقط، وهو ما يكفي لصفحة «كيف يعمل ذكاء نيو كيبورد».
 */
public final class NetGate {

    /** القدرات المعروفة — كل جديد يُضاف هنا يدوياً بقلب البوابة. */
    public static final String CAP_WRITER_AI = "writer_ai";   // تحسين الصياغة عبر مزود المستخدم
    /** المرحلة 2، بند A2: المزامنة المشفرة — إذن منفصل عن الرئيسي، افتراضه مغلق. */
    public static final String CAP_SYNC = "sync";             // دفع/سحب الخزنة المشفرة

    private static final String[] CAPS = {CAP_WRITER_AI, CAP_SYNC};

    /** النطاق المسموح الوحيد لكل قدرة (قائمة بيضاء حرفية — فشل مغلق). */
    private static final String[][] ENDPOINTS = {
            {CAP_WRITER_AI, "https://openrouter.ai/api/v1/"},
            {CAP_SYNC, "https://sync.neokey.app/v1/"},
    };

    public static final String MASTER_PREF = "net_state";
    public static String capPref(String cap) { return "net_" + cap; }

    /** دبابيس SPKI لمزامنة الإنتاج (المرحلة 5/الأسبوع 26): هوية مفتاح الخادم
     *  مثبتة عند النشر (NetGate.setSyncPins) — https بلا دبابيس = مزامنة مغلقة.
     *  المتقلب مع حاجز القراءة/الكتابة يكفي: يُكتب مرة عند الإقلاع قبل أي مزامنة. */
    private static volatile String[] syncPins = null;

    /** يضبط دبابيس مزامنة الإنتاج عند النشر فقط — من الواجهة/التهيئة، قبل أي طلب. */
    public static void setSyncPins(String... pins) {
        if (pins == null || pins.length == 0) throw new SyncVault.SyncRefused("no_pins");
        syncPins = pins.clone();
    }

    /** دبابيس المزامنة الحالية أو null إن لم تُضبط (المزامنة https تُغلق). */
    public static String[] syncPins() {
        String[] p = syncPins;
        return p == null ? null : p.clone();
    }

    /** مسح الدبابيس — عزل الاختبارات والتبديل الموثق فقط؛ الإنتاج لا يمسحها أبداً. */
    public static void clearSyncPins() { syncPins = null; }

    /** قرار البوابة. */
    public static final class Decision {
        public final boolean allowed;   // يمرّ الآن
        public final boolean ask;       // يحتاج موافقة صريحة أولاً
        public final String reason;     // master_off / unknown_cap / cap_off / ask / ask_granted / cap_on

        Decision(boolean allowed, boolean ask, String reason) {
            this.allowed = allowed; this.ask = ask; this.reason = reason;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Decision)) return false;
            Decision d = (Decision) o;
            return allowed == d.allowed && ask == d.ask && reason.equals(d.reason);
        }

        @Override public int hashCode() { return (allowed ? 1 : 0) * 31 + (ask ? 2 : 0) + reason.hashCode(); }

        @Override public String toString() { return (allowed ? "ALLOWED(" : ask ? "ASK(" : "DENIED(") + reason + ")"; }
    }

    public static final Decision DENY_MASTER = new Decision(false, false, "master_off");
    public static final Decision DENY_UNKNOWN = new Decision(false, false, "unknown_cap");
    public static final Decision DENY_CAP = new Decision(false, false, "cap_off");
    public static final Decision ASK_USER = new Decision(false, true, "ask");
    public static final Decision ALLOW_ASK = new Decision(true, false, "ask_granted");
    public static final Decision ALLOW_CAP = new Decision(true, false, "cap_on");

    static boolean known(String cap) {
        for (String c : CAPS) if (c.equals(cap)) return true;
        return false;
    }

    /** نسخة بمستوى القدرة مباشرة (تُستخدم من الواجهة بعد قراءة pref القدرة). */
    public static Decision decide(String capState, boolean consent) {
        if (capState == null || "off".equals(capState)) return DENY_CAP;
        if ("ask".equals(capState)) return consent ? ALLOW_ASK : ASK_USER;
        if ("on".equals(capState)) return ALLOW_CAP;
        return DENY_CAP;   // أي قيمة غريبة = إغلاق
    }

    /** البوابة الكاملة بقراءة المفتاحين معاً — المسار الوحيد المسموح للواجهة. */
    public static Decision full(String master, String capState, String cap, boolean consent) {
        if (master == null || !"on".equals(master)) return DENY_MASTER;
        if (!known(cap)) return DENY_UNKNOWN;
        return decide(capState, consent);
    }

    /** النطاق المسموح الوحيد للقدرة، أو null لقدرة غير معروفة. */
    public static String endpoint(String cap) {
        for (String[] e : ENDPOINTS) if (e[0].equals(cap)) return e[1];
        return null;
    }

    /** هل يمرّ هذا العنوان؟ مطابقة بادئة حرفية للنطاق الأبيض — فشل مغلق. */
    public static boolean endpointAllowed(String cap, String url) {
        String ep = endpoint(cap);
        return ep != null && url != null && url.startsWith(ep);
    }

    /** سطر تدقيق: القدرة والقرار والزمن فقط — لا نص مستخدم أبداً. */
    public static String auditLine(String cap, Decision d, long epochMs) {
        return "NETGATE\t" + cap + "\t" + (d.allowed ? "ALLOWED" : d.ask ? "ASK" : "DENIED")
                + "\t" + d.reason + "\t" + epochMs;
    }

    private NetGate() { }
}
