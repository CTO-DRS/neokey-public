package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * AirSeal — W64: ختم الهواء «شاهد لا وعد». نقي JVM: صفر android.*.
 *
 * الخارطة: «ختم دائم في الواجهة مبني على حالة NetGate الفعلية: جلسة بلا
 * شبكة مقابل جلسة مزامنة معلنة صراحة، مع بصمة جلسة (hash) قابلة للمقارنة.
 * أي محاولة شبكة خارج البوابات المعلنة تُغيّر الختم وتسجل حدثاً محلياً».
 *
 * المبدأ (شاهد لا وعد): الختم **يُشتق** من حالة NetGate المعيشة في لحظة
 * الفحص — لا يُخزن ولا يُعاد سكبه من ذاكرة قديمة: نفس الحالة = نفس البصمة
 * بايت-دقيقاً على أي جهاز (sha256 حتمي)، وتغيّر الحالة = بصمة مختلفة حتماً.
 * البصمة القصيرة (16 hex) هي ما يراه المستخدم — قابلة للمقارنة بين فحصين.
 *
 * أنواع الجلسة (من حالة NetGate الحية):
 *   • no-network     — المفتاح الرئيسي مغلق/غائب، أو لا قدرة شبكية مفتوحة.
 *   • declared-sync  — جلسة مزامنة معلنة صراحة (full() سمحت بقدرة sync).
 *   • breached       — حدث شبكة خارج البوابات المعلنة مُسجّل — الختم انقلب
 *                      ولن يعود لسابق العهد في نفس الجلسة (الشاهد لا ينسى).
 *
 * الحدث المحلي: سطر AIRSEAL في الملف المحلي (الزمن + السبب + بصمة ما قبل
 * الحدث فقط — لا نص مستخدم أبداً، قانون 8/الصفر تتبع).
 *
 * القوانين:
 *   • الختم المزيف يُرفض: verifySeal يعيد الحساب من الحالة الحية — ادعاء
 *     بصمة لا تطابق الحالة يعضّ بوسم SEAL_MISMATCH (لا ثقة بالادعاء).
 *   • التخفي: قراءة الحالة ليست تحليلاً للنص المستخدم — الختم يعمل في
 *     التخفي (هو حماية المستخدم نفسها).
 *   • الفشل المغلق: حالة غريبة/فارغة → no-network (الافتراضي مغلق كما في
 *     NetGate نفسها) — لا ختم متفائل أبداً.
 */
public final class AirSeal {

    public static final String NO_NETWORK = "no-network";
    public static final String DECLARED_SYNC = "declared-sync";
    public static final String BREACHED = "breached";

    /** بصمة قابلة للمقارنة: 16 hex من sha256 للحالة الحية. */
    public static final class Seal {
        public final String kind;
        public final String fingerprint;
        public final int breaches;

        Seal(String kind, String fingerprint, int breaches) {
            this.kind = kind;
            this.fingerprint = fingerprint;
            this.breaches = breaches;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Seal)) return false;
            Seal s = (Seal) o;
            return kind.equals(s.kind) && fingerprint.equals(s.fingerprint)
                    && breaches == s.breaches;
        }

        @Override public int hashCode() {
            return kind.hashCode() * 31 + fingerprint.hashCode() * 7 + breaches;
        }

        @Override public String toString() { return kind + "/" + fingerprint; }
    }

    private final List<String> events = new ArrayList<>();

    public AirSeal() { }

    /** سطر حدث محلي مخزّن (للواجهة والبوابة — الأحدث آخراً). */
    public List<String> events() { return new ArrayList<>(events); }

    public int breaches() { return events.size(); }

    /**
     * محاولة شبكة خارج البوابات المعلنة — تُسجّل محلياً وتُغيّر الختم: كل
     * sealOf() لاحق في هذه الجلسة يعيد breached (الشاهد لا ينسى).
     */
    public String recordOutOfGate(String reason, long epochMs) {
        String line = "AIRSEAL\tOUT_OF_GATE\t" + (reason == null ? "unknown" : reason)
                + "\t" + epochMs;
        events.add(line);
        return line;
    }

    /** مسح الأحداث (حالة مصنع بايتياً — مع المسح الكامل من الواجهة). */
    public void wipe() { events.clear(); }

    /**
     * الختم المشتق من حالة NetGate الحية: master (null/off = مغلق) + إذنا
     * القدرتين الشبكيتين المعلنتين — بالقرار الشامل نفسه (full) لا بتخمين.
     */
    public Seal sealOf(String master, String syncState, String writerState) {
        NetGate.Decision sync = NetGate.full(master, syncState, NetGate.CAP_SYNC, false);
        // الجلسة المعلنة: مزامنة سُمحت فعلاً بقرار البوابة الشامل نفسه.
        String kind = sync.allowed ? DECLARED_SYNC : NO_NETWORK;
        if (breaches() > 0) kind = BREACHED;
        String fp = fingerprint(master, syncState, writerState, kind, breaches());
        return new Seal(kind, fp, breaches());
    }

    /** البصمة الحتمية: sha256(الحالة + النوع + عدد الخروقات) — أول 16 hex. */
    public static String fingerprint(String master, String syncState, String writerState,
                                     String kind, int breaches) {
        String seed = "AIRSEAL1|" + (master == null ? "absent" : master)
                + "|" + (syncState == null ? "absent" : syncState)
                + "|" + (writerState == null ? "absent" : writerState)
                + "|" + kind + "|" + breaches;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("AIRSEAL sha256 unavailable", e);
        }
    }

    /** الختم المزيف يعضّ: الادعاء لا يطابق الحساب من الحالة الحية. */
    public static boolean verifySeal(Seal claimed, Seal computed) {
        return claimed != null && claimed.equals(computed);
    }

    /** سطر الختم للواجهة — بصمة قصيرة قابلة للمقارنة، بلا نص مستخدم. */
    public static String statusLine(Seal s) {
        return "ختم الجلسة: " + (NO_NETWORK.equals(s.kind) ? "بلا شبكة"
                : DECLARED_SYNC.equals(s.kind) ? "مزامنة معلنة"
                : "خرق مسجّل (" + s.breaches + ")")
                + " — البصمة " + s.fingerprint;
    }
}
