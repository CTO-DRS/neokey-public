package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * اختبارات وحدة الخادم المرجعي REST — المرحلة 4، الأسبوع 2 (الأسبوع 23).
 *
 * تختبر العقد على السلك الخام (HttpURLConnection مباشرة — بلا عميل إنتاج)
 * نقطة-نقطة مقابل docs/SYNC-REST.md: رموز الحالة الخمسة في كل نقطة، شكل
 * المسار، نوع المحتوى، المصفوفة JSON المرتبة، دلالة 404 الرمضاء، صراع الملح
 * 409 بلا استبدال، سقف الكتلة 413 (حتى مع VIP)، حصة الحساب 507، المحاسبة
 * التفاضلية على الاستبدال، ورفض حقن المسار والمحارف الغريبة.
 *
 * نقي JVM (خادم JDK HttpServer على 127.0.0.1 بمنفذ ساذج) — تعمل في أي بيئة.
 */
public final class SyncServerContractTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) pass++;
        else { fail++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) throws Exception {
        SyncServer s = SyncServer.start();
        try {
            run(s);
        } finally {
            s.stop();
        }
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void run(SyncServer s) throws Exception {
        String base = s.base();
        byte[] salt1 = new byte[SyncVault.SALT_LEN], salt2 = new byte[SyncVault.SALT_LEN];
        new SecureRandom().nextBytes(salt1);
        for (int i = 0; i < salt2.length; i++) salt2[i] = (byte) (salt1[i] ^ 0x5A);
        String acct = "acct" + System.nanoTime();               // b64url آمن (حروف وأرقام)
        byte[] blobA = rnd(1000), blobB = rnd(2000);

        // #1/#2: الملح — 404 ثم 201 ثم قراءة مطابقة
        Raw r = raw("GET", base + "account/" + acct + "/salt", null);
        check("salt.unknown.404", r.code == 404);
        r = raw("PUT", base + "account/" + acct + "/salt", salt1);
        check("salt.put.201", r.code == 201);
        r = raw("GET", base + "account/" + acct + "/salt", null);
        check("salt.get.200", r.code == 200 && Arrays.equals(salt1, r.body));
        check("salt.get.octet", r.ctype != null && r.ctype.startsWith("application/octet-stream"));

        // #2: idempotence بنفس الملح = 201، وصراع ملح مختلف = 409 بلا استبدال
        r = raw("PUT", base + "account/" + acct + "/salt", salt1);
        check("salt.same.201", r.code == 201);
        r = raw("PUT", base + "account/" + acct + "/salt", salt2);
        check("salt.conflict.409", r.code == 409);
        r = raw("GET", base + "account/" + acct + "/salt", null);
        check("salt.conflict.kept", Arrays.equals(salt1, r.body));
        r = raw("PUT", base + "account/" + acct + "/salt", rnd(SyncVault.SALT_LEN - 1));
        check("salt.badlen.400", r.code == 400);

        // #3: القائمة — فارغة ثم معرفتان مرتبتان بمصفوفة JSON
        r = raw("GET", base + "account/" + acct + "/blobs", null);
        check("blobs.empty.200", r.code == 200 && "[]".equals(new String(r.body, StandardCharsets.UTF_8)));
        check("blobs.json.ctype", r.ctype != null && r.ctype.startsWith("application/json"));
        check("blobs.unknown.404", raw("GET", base + "account/zzUnknown9/blobs", null).code == 404);
        raw("PUT", base + "account/" + acct + "/blob/bbb", blobB);
        raw("PUT", base + "account/" + acct + "/blob/aaa", blobA);
        r = raw("GET", base + "account/" + acct + "/blobs", null);
        check("blobs.sorted.json", r.code == 200
                && "[\"aaa\",\"bbb\"]".equals(new String(r.body, StandardCharsets.UTF_8)));
        r = raw("GET", base + "account/" + acct + "/blobs", null);
        check("blobs.deterministic", "['aaa','bbb']".replace('\'', '"')
                .equals(new String(r.body, StandardCharsets.UTF_8)));

        // #4: جلب الكتلة بايت-لطرف + 404 للمجهول
        r = raw("GET", base + "account/" + acct + "/blob/aaa", null);
        check("blob.get.bytes", r.code == 200 && Arrays.equals(blobA, r.body));
        check("blob.get.octet", r.ctype != null && r.ctype.startsWith("application/octet-stream"));
        check("blob.unknown.404", raw("GET", base + "account/" + acct + "/blob/zzz", null).code == 404);

        // #5: الرفض المشكل — حساب مجهول 404، معرف غريب 400، حقن مسار 404
        check("blob.put.unknown.404", raw("PUT", base + "account/zzUnknown9/blob/aaa", blobA).code == 404);
        check("blob.id.badcharset.400", raw("PUT", base + "account/" + acct + "/blob/a<b", blobA).code == 400);
        check("acct.id.badcharset.400", raw("GET", base + "account/آ/salt", null).code == 400);
        check("blob.path.inject.404", raw("PUT", base + "account/" + acct + "/blob/aaa/../../evil", blobA).code == 404);
        check("version.v2.404", raw("GET", base.replace("/v1/", "/v2/") + "account/" + acct + "/salt", null).code == 404);
        check("blob.count.intact", s.blobCount(acct) == 2);

        // الحصص على السلك الخام: امتلاء مجاني 507، استبدال متضخم يُحاسب تفاضلياً
        String c = "quota" + System.nanoTime();
        raw("PUT", base + "account/" + c + "/salt", salt1);
        byte[] q1 = rnd(600 * 1024), q2 = rnd(500 * 1024);
        check("quota.first.201", raw("PUT", base + "account/" + c + "/blob/q1", q1).code == 201);
        check("quota.over.507", raw("PUT", base + "account/" + c + "/blob/q2", q2).code == 507);
        check("quota.notcounted", s.blobCount(c) == 1 && s.usedBytes(c) == q1.length);
        // الاستبدال بنفس المعرف داخل الحصة يمر ويُحاسب بالفرق
        byte[] q1b = rnd(400 * 1024);
        check("replace.within.201", raw("PUT", base + "account/" + c + "/blob/q1", q1b).code == 201);
        check("replace.delta.accounting", s.usedBytes(c) == q1b.length);
        // تهريب النمو: نفس المعرف من 400KB إلى 1MB+1 → 507 (المحاسبة التفاضلية تقطعه)
        byte[] grown = rnd(1024 * 1024 + 1);
        check("replace.growth.dodge.507", raw("PUT", base + "account/" + c + "/blob/q1", grown).code == 507);

        // سقف الكتلة 8MB+1 = 413، ومع VIP يبقى السقف سقفاً (الصفة خادمية)
        s.setVip(c, true);
        check("vip.quota.lifts.201", raw("PUT", base + "account/" + c + "/blob/q2", q2).code == 201);
        byte[] huge = rnd(SyncVault.MAX_BLOB_BYTES + 1);
        check("blob.cap.413", raw("PUT", base + "account/" + c + "/blob/huge", huge).code == 413);
        check("blob.cap.notcounted", s.blobCount(c) == 2 && s.usedBytes(c) == q1b.length + q2.length);

        // #1/#4 على VIP: سلوك القراءة لا يتغير
        check("vip.salt.read", Arrays.equals(salt1, raw("GET", base + "account/" + c + "/salt", null).body));

        // سجل السلك: كل الأشكال الموثقة خمسة ظهرت، وكل ما خارج العقد رُفض 404/400
        List<String> wire = s.wireLog();
        boolean sawSaltGet = false, sawSaltPut = false, sawBlobs = false, sawBlobGet = false, sawBlobPut = false;
        boolean outsideRefused = true;
        for (String line : wire) {
            if (line.startsWith("GET /v1/account/") && line.contains("/salt -> ")) sawSaltGet = true;
            if (line.startsWith("PUT /v1/account/") && line.contains("/salt -> ")) sawSaltPut = true;
            if (line.startsWith("GET /v1/account/") && line.contains("/blobs -> ")) sawBlobs = true;
            if (line.startsWith("GET /v1/account/") && line.contains("/blob/")) sawBlobGet = true;
            if (line.startsWith("PUT /v1/account/") && line.contains("/blob/")) sawBlobPut = true;
            if (!line.contains(" /v1/account/")
                    && !line.endsWith("-> 404") && !line.endsWith("-> 400")) outsideRefused = false;
        }
        check("wire.outsideContract.refused", outsideRefused);
        check("wire.five.shapes", sawSaltGet && sawSaltPut && sawBlobs && sawBlobGet && sawBlobPut);
    }

    // ---- HTTP خام ---------------------------------------------------------------

    static final class Raw {
        final int code; final byte[] body; final String ctype;
        Raw(int code, byte[] body, String ctype) { this.code = code; this.body = body; this.ctype = ctype; }
    }

    static byte[] rnd(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static Raw raw(String method, String url, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] b = in == null ? new byte[0] : readAll(in);
            String ct = c.getContentType();
            return new Raw(code, b, ct);
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
