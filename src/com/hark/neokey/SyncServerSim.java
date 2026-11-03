package com.hark.neokey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * محاكي الخادم غير الموثوق — المرحلة 2، البند A، الأسبوع 1. نقي JVM، بلا شبكة.
 *
 * الغرض: إثبات العقد الصفري-المعرفة بالقياس لا بالادعاء. الخادم يخزّن فقط:
 *   accountId (مستعار مشتق) → (blobId → بايتات عشوائية) + ملح الحساب العلني.
 * كل ما يراه الخادم يُجمع في dump() لتجريده الصفري في البوابة: لا عربية،
 * لا كلمات مفتاحية، لا بنية JSON، إنتروبيا عالية، ولا كلمة السر إطلاقاً.
 * سياسات الخادم فشل-مغلق: كتلة أكبر من السقف تُرفض، ومجموع الحساب محكوم بالحصة.
 */
public final class SyncServerSim implements SyncClient.Transport {

    /** سجل الرفض — الأسباب فقط، بلا أي محتوى (نفس انضباط NetGate). */
    public static final class Refused extends RuntimeException {
        public Refused(String reason) { super(reason); }
    }

    static final class Account {
        final String accountId;
        byte[] salt;
        long usedBytes;
        final Map<String, byte[]> blobs = new LinkedHashMap<>();
        Account(String id, byte[] salt) { this.accountId = id; this.salt = salt.clone(); }
    }

    private final Map<String, Account> accounts = new LinkedHashMap<>();

    /** تسجيل حساب جديد بملحه العلني (الملح ليس سراً — يُقرأ بأي جهاز جديد). */
    public synchronized void register(String accountId, byte[] salt) {
        if (accountId == null || accountId.isEmpty()) throw new Refused("bad_account");
        if (salt == null || salt.length != SyncVault.SALT_LEN) throw new Refused("bad_salt");
        if (accounts.containsKey(accountId)) throw new Refused("already_registered");
        accounts.put(accountId, new Account(accountId, salt));
    }

    /** ملح الحساب — الخطوة الأولى لاستعادة أي جهاز جديد. */
    public synchronized byte[] saltOf(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) throw new Refused("unknown_account");
        return a.salt.clone();
    }

    /** رفع كتلة: سياسة الحصص أولاً (فشل مغلق)، ثم التخزين كبايتات عمياء. */
    public synchronized void put(String accountId, String blobId, byte[] blob, boolean vip) {
        Account a = accounts.get(accountId);
        if (a == null) throw new Refused("unknown_account");
        if (blobId == null || blobId.isEmpty()) throw new Refused("bad_blob_id");
        SyncVault.Quota q = SyncVault.checkQuota(blob == null ? -1 : blob.length, a.usedBytes, vip);
        if (!q.allowed) throw new Refused(q.reason);
        if (!a.blobs.containsKey(blobId)) a.usedBytes += blob.length;
        a.blobs.put(blobId, blob.clone());
    }

    /** تنزيل كتلة بالمعرف — الخادم لا يعرف ولا يهتم بمحتواها. */
    public synchronized byte[] get(String accountId, String blobId) {
        Account a = accounts.get(accountId);
        if (a == null) throw new Refused("unknown_account");
        byte[] b = a.blobs.get(blobId);
        if (b == null) throw new Refused("unknown_blob");
        return b.clone();
    }

    /** كل معرفات كتل الحساب — للاستعادة الكاملة على جهاز جديد. */
    public synchronized List<String> list(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) throw new Refused("unknown_account");
        List<String> ids = new ArrayList<>(a.blobs.keySet());
        Collections.sort(ids);                                     // حتمي
        return ids;
    }

    /** كل ما يراه الخادم — مدخلات فحص الصفر-المعرفة في البوابة. */
    public synchronized byte[] dump() {
        List<byte[]> all = new ArrayList<>();
        for (Account a : accounts.values()) {
            all.add(a.accountId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            all.add(a.salt);
            for (Map.Entry<String, byte[]> e : a.blobs.entrySet()) {
                all.add(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                all.add(e.getValue());
            }
        }
        int n = 0;
        for (byte[] b : all) n += b.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] b : all) { System.arraycopy(b, 0, out, off, b.length); off += b.length; }
        return out;
    }

    /** إجمالي البايتات المخزنة لحساب (لأغراض القياس فقط). */
    public synchronized long usedBytes(String accountId) {
        Account a = accounts.get(accountId);
        return a == null ? -1 : a.usedBytes;
    }

    private SyncServerSim() { }

    /** الخادم الوحيد للاختبارات (حالة نظيفة في كل مرة). */
    public static SyncServerSim fresh() { return new SyncServerSim(); }
}
