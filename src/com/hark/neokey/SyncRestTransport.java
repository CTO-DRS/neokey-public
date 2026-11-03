package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * نقل REST للعميل — المرحلة 2، بند A2: تنفيذ {@link SyncClient.Transport} فوق
 * عقد REST الموثق (docs/SYNC-REST.md). يعمل على أي JVM/Android بـ
 * HttpURLConnection فقط — لا مكتبات خارجية.
 *
 * الحدود الأمنية (فشل مغلق):
 *   • العنوان الأساسي يأتي من قائمة NetGate البيضاء حصراً — إن غاب أو غيّر
 *     النطاق لا يخرج طلب واحد.
 *   • لا رؤوس مصادقة: المصادقة هي القدرة على إنتاج كتلة تفك بعبارة الحساب —
 *     الخادم يرى بايتات عمياء لا معنى لها (عقد الصفر-المعرفة نفسه).
 *   • مهلات قصيرة وفشل صريح: أي خطأ شبكة/HTTP غير متوقع = SyncRefused(code)
 *     ولا تُكتب أي حالة محلية.
 */
final class SyncRestTransport implements SyncClient.Transport {

    private final String base;
    /** الحلبة المحلية فقط (بوابة RESTGATE): 127.0.0.1 حصراً — لا يمكن لهذا
     *  المسار مغادرة الجهاز، ويُستبدل فحص NetGate الاستنتاجي بفحص الحلبة. */
    private final boolean arena;

    /** يُبنى بنطاق البوابة فقط — أي مصدر آخر يُرفض عند البناء. */
    SyncRestTransport() {
        String ep = NetGate.endpoint(NetGate.CAP_SYNC);
        if (ep == null) throw new SyncVault.SyncRefused("no_endpoint");
        this.base = ep;
        this.arena = false;
    }

    /**
     * حصراً لبوابة الحلبة المحلية (المرحلة 4، الأسبوع 2): الخادم المرجعي
     * HTTP حقيقي على 127.0.0.1 — العقد يُثبت على السلك بعميل الإنتاج نفسه.
     * البناء يقبل 127.0.0.1 حصراً قبالة النص، فلا يمكن للحلبة أبداً أن
     * تولّد طلباً نحو نطاق حقيقي؛ فحص NetGate يبقى حكراً على مسار الإنتاج.
     */
    SyncRestTransport(String arenaBase) {
        if (arenaBase == null || !arenaBase.startsWith("http://127.0.0.1:"))
            throw new SyncVault.SyncRefused("not_arena");
        this.base = arenaBase;
        this.arena = true;
    }

    // ---- الحساب ------------------------------------------------------------------

    @Override public byte[] saltOf(String accountId) {
        return req("GET", path(accountId, "salt"), null, new int[]{200});
    }

    @Override public void register(String accountId, byte[] salt) {
        req("PUT", path(accountId, "salt"), salt, new int[]{200, 201, 409});
    }

    // ---- الكتل -------------------------------------------------------------------

    @Override public List<String> list(String accountId) {
        byte[] body = req("GET", path(accountId, "blobs"), null, new int[]{200, 404});
        List<String> out = new ArrayList<>();
        if (body == null || body.length == 0) return out;            // 404 = لا كتل بعد
        try {
            org.json.JSONArray a = new org.json.JSONArray(new String(body, "UTF-8"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) {
            throw new SyncVault.SyncRefused("bad_list");
        }
        return out;
    }

    @Override public byte[] get(String accountId, String blobId) {
        return req("GET", path(accountId, "blob/" + blobId), null, new int[]{200, 404});
    }

    @Override public void put(String accountId, String blobId, byte[] blob, boolean vip) {
        req("PUT", path(accountId, "blob/" + blobId), blob, new int[]{200, 201});
    }

    // ---- النقل -------------------------------------------------------------------

    private static String path(String accountId, String leaf) {
        // معرف الحساب b64url حصراً (A-Za-z0-9_-) — أي محرف آخر = رفض قبل الخروج
        for (int i = 0; i < accountId.length(); i++) {
            char ch = accountId.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (!ok) throw new SyncVault.SyncRefused("bad_account_id");
        }
        return "account/" + accountId + "/" + leaf;
    }

    private byte[] req(String method, String relPath, byte[] body, int[] okCodes) {
        if (!arena && !NetGate.endpointAllowed(NetGate.CAP_SYNC, base))  // دفاع ثانٍ عند كل طلب (الإنتاج)
            throw new SyncVault.SyncRefused("endpoint_not_allowed");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + relPath).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", "application/octet-stream");  // عقد الأجسام الخام
                OutputStream out = c.getOutputStream();
                out.write(body);
                out.close();
            }
            int code = c.getResponseCode();
            boolean ok = false;
            for (int k : okCodes) if (k == code) ok = true;
            if (!ok) throw new SyncVault.SyncRefused("http_" + code);
            if (code == 404 || code == 409) return null;             // دلالات العقد
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > SyncVault.MAX_BLOB_BYTES + 4096) { in.close(); throw new SyncVault.SyncRefused("too_big"); }
            }
            in.close();
            return bo.toByteArray();
        } catch (SyncVault.SyncRefused e) {
            throw e;
        } catch (Exception e) {
            throw new SyncVault.SyncRefused("network");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
