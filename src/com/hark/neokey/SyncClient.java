package com.hark.neokey;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * عميل المزامنة — المرحلة 2، بند A2 (الأسبوع 2): «توصيل الخزنة بالإعدادات …
 * المزامنة تمر من NetGate بإذن قدرت جديد (sync) — فشل مغلق كما هو، الافتراضي
 * مغلق» + «نسخ احتياطي ملفي مشفر للسحابة الذاتية (نفس المغلف NSF1)» + «سطر
 * تدقيق بلا أي محتوى».
 *
 * نقي JVM: صفر android.*. الشبكة نفسها خلف واجهة {@link Transport} — في
 * الاختبارات والبوابة يُنفّذها محاكي الخادم غير الموثوق (SyncServerSim)، وعلى
 * جهاز النشر يُنفّذها SyncRestTransport بعقد REST الموثق (docs/SYNC-REST.md).
 * أي قرار شبكي يمر أولاً من بوابة NetGate: بلا إذن القدرة لا تُلمس الشبكة
 * إطلاقاً — لا طلب واحد، لا DNS، لا شيء.
 *
 * عقد الاستعادة: كل شيء أو لا شيء — فشل فتح أي كتلة يرفض الاستعادة كاملة
 * ويترك السجل المحلي كما هو (نفس عقد SyncVault.restore).
 */
public final class SyncClient {

    /** نقل الخادم — تنفيذ المحاكي في الاختبارات، وتنفيذ REST على جهاز النشر. */
    public interface Transport {
        byte[] saltOf(String accountId);
        void register(String accountId, byte[] salt);
        List<String> list(String accountId);
        byte[] get(String accountId, String blobId);
        void put(String accountId, String blobId, byte[] blob, boolean vip);
    }

    /** جلسة مسجلة — تُبنى من التسجيل أو من التفضيلات المحفوظة (ملح + حساب). */
    public static final class Session {
        public final SyncVault.KeySet keys;
        public final byte[] salt;

        Session(SyncVault.KeySet keys, byte[] salt) { this.keys = keys; this.salt = salt; }
    }

    // ---- التسجيل ----------------------------------------------------------------

    /**
     * تسجيل حساب: عبارة + ملح (جديد عشوائي إن لم يُعطى). الاشتقاق موحد في
     * SyncVault.derive — نفس العبارة والملح على أي جهاز = نفس الحساب المستعار.
     */
    public static Session register(String passphrase, byte[] salt) {
        byte[] s = salt == null ? SyncVault.newSalt() : salt;
        return new Session(SyncVault.derive(passphrase, s), s);
    }

    /** تسجيل دخول جهاز جديد: الملح العلني من الخادم ثم نفس الاشتقاق. */
    public static Session login(Transport t, String passphrase, String accountId) {
        byte[] salt = t.saltOf(accountId);
        Session s = register(passphrase, salt);
        if (!s.keys.accountId.equals(accountId)) throw new SyncVault.SyncRefused("account_mismatch");
        return s;
    }

    // ---- البوابة ----------------------------------------------------------------

    /**
     * قرار البوابة الكامل للقدرة sync — المسار الوحيد قبل أي دفع/سحب.
     * الفشل مغلق: master off أو قدرة off/غريبة = رفض بلا أي اتصال.
     */
    public static NetGate.Decision gate(String master, String capState, boolean consent) {
        return NetGate.full(master, capState, NetGate.CAP_SYNC, consent);
    }

    // ---- الدفع والسحب ---------------------------------------------------------------

    /** نتيجة دفع — أرقام فقط لسطر التدقيق، لا محتوى. */
    public static final class PushResult {
        public final String blobId;
        public final int bytes;
        PushResult(String blobId, int bytes) { this.blobId = blobId; this.bytes = bytes; }
    }

    /**
     * دفعة واحدة: تحقق البوابة ← حصة ← ختم NSF1 بnonce عشوائي ← رفع. أي رفض
     * بوابة/حصة = SyncRefused ولا يُبنى المغلف أصلاً.
     */
    public static PushResult push(Session s, Transport t, List<SyncVault.Rec> state,
                                  String master, String capState, boolean consent, boolean vip) {
        NetGate.Decision d = gate(master, capState, consent);
        if (!d.allowed) throw new SyncVault.SyncRefused("netgate_" + d.reason);
        SecureRandom rnd = new SecureRandom();
        byte[] random16 = new byte[16];
        rnd.nextBytes(random16);
        byte[] body = SyncVault.stateToBytes(state);
        byte[] nonce = Arrays.copyOf(random16, SyncVault.NONCE_LEN);
        byte[] blob = SyncVault.seal(s.keys, body, nonce);
        SyncVault.Quota q = SyncVault.checkQuota(blob.length, usedBytes(t, s.keys.accountId), vip);
        if (!q.allowed) throw new SyncVault.SyncRefused(q.reason);
        String blobId = SyncVault.b64url(random16);
        t.put(s.keys.accountId, blobId, blob, vip);
        return new PushResult(blobId, blob.length);
    }

    /**
     * سحب كامل: تحقق البوابة ← فتح كل كتل الحساب ← دمجها مع المحلي. أي كتلة
     * ترفض الفتح = رفض الاستعادة كاملة (كل شيء أو لا شيء).
     */
    public static List<SyncVault.Rec> pull(Session s, Transport t, List<SyncVault.Rec> local,
                                           String master, String capState, boolean consent) {
        NetGate.Decision d = gate(master, capState, consent);
        if (!d.allowed) throw new SyncVault.SyncRefused("netgate_" + d.reason);
        List<byte[]> blobs = new ArrayList<>();
        for (String id : t.list(s.keys.accountId)) blobs.add(t.get(s.keys.accountId, id));
        return SyncVault.restore(local, s.keys, blobs);
    }

    // ---- النسخة الاحتياطية الملفية (بلا شبكة — لا تحتاج بوابة) -------------------

    /** تصدير NSF1 إلى بايتات ملف — نفس مغلف المزامنة، nonce عشوائي. */
    public static byte[] exportBackup(Session s, List<SyncVault.Rec> state) {
        byte[] nonce = new byte[SyncVault.NONCE_LEN];
        new SecureRandom().nextBytes(nonce);
        return SyncVault.seal(s.keys, SyncVault.stateToBytes(state), nonce);
    }

    /** استيراد NSF1 من ملف — أي خلل = SyncRefused والملف لا يُكتب في المخازن. */
    public static List<SyncVault.Rec> importBackup(Session s, byte[] file) {
        return SyncVault.bytesToState(SyncVault.open(s.keys, file));
    }

    // ---- سطر التدقيق (بلا أي محتوى) ----------------------------------------------

    /**
     * SYNCAUDIT &lt;action&gt; &lt;verdict&gt; &lt;bytes&gt; &lt;ts&gt; — القدرة
     * والقرار والزمن والحجم فقط، لا نص مستخدم ولا معرفات كتل (نفس انضباط NetGate).
     */
    public static String auditLine(String action, boolean ok, int bytes, long epochMs) {
        return "SYNCAUDIT\t" + action + "\t" + (ok ? "OK" : "REFUSED") + "\t" + bytes + "\t" + epochMs;
    }

    /** الحجم المستهلك من حصة الحساب كما يراها الخادم (مجموع الكتل). */
    static long usedBytes(Transport t, String accountId) {
        long used = 0;
        for (String id : t.list(accountId)) {
            byte[] b = t.get(accountId, id);
            if (b != null) used += b.length;
        }
        return used;
    }

    private SyncClient() { }
}
