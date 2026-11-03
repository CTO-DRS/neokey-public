package com.hark.neokey;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * الخادم المرجعي REST — المرحلة 4، الأسبوع 2 (الأسبوع 23): HTTP حقيقي على
 * الحلبة المحلية (127.0.0.1) ينفذ docs/SYNC-REST.md حرفاً بحرف — العقد
 * يُثبت على السلك (مقابس ورموز حالة ومحتوى حقيقي) لا بمكالمات صنف.
 *
 * لماذا يسكن tests/ لا src/: بنية إثبات لا كود تطبيق. com.sun.net.httpserver
 * غير موجود في android.jar أصلاً فلا يمر في dex، ولوحة المفاتيح لا تفتح
 * مقبس استماع أبداً (صفر سطح هجوم في التطبيق). جهاز النشر يشغّل هذا العقد
 * خارج التطبيق، كما يقول العقد حرفياً.
 *
 * التطبيق الحرفي للعقد (نسخة /v1/ فقط — أي مسار آخر = 404):
 *   #1 GET  /v1/account/{id}/salt      → 200 + 32B ملح | 404
 *   #2 PUT  /v1/account/{id}/salt      → 201 (جديد، أو نفس الملح idempotent)
 *                                       | 409 (وُجد بملح مختلف — لا استبدال أبداً)
 *                                       | 400 (طول الملح)
 *   #3 GET  /v1/account/{id}/blobs     → 200 + مصفوفة JSON من معرفات b64url مرتبة | 404
 *   #4 GET  /v1/account/{id}/blob/{b}  → 200 + بايتات المغلف | 404
 *   #5 PUT  /v1/account/{id}/blob/{b}  → 201 | 400 (معرف/ملح/شكل مسار)
 *                                       | 404 (حساب مجهول)
 *                                       | 413 (سقف الكتلة 8MB — حتى مع VIP)
 *                                       | 507 (حصة الحساب: مجاني 1MB / VIP 50MB)
 * كل الأجسام بايتات خام (application/octet-stream) — JSON فقط في #3.
 *
 * قرارات تصميمية موثقة:
 *   • المحاسبة تفاضلية: استبدال نفس معرف الكتلة يُحاسب الفرق (new−old) —
 *     تُغلق ثغرة «تهريب النمو» حيث يُرفع معرف ببايت واحد ثم يُستبدل بمتن ضخم.
 *   • VIP صفة خادمية يقررها مشغل الحلبة (setVip) — العقد بلا حقل فئة على
 *     السلك، فلا يستطيع العميل تزوير ترقية حسابه.
 *   • معرفات الحساب/الكتل بـ b64url حصراً على المسار — أي محرف آخر = 400،
 *     وأي شكل مسار غريب (حقن / أو ..) = 404 «لا شيء بعد».
 *   • كل غموض في المعالجة = 400 (فلسفة الفشل المغلق نفسها).
 *   • سجل السلك (wireLog) يوثق كل طلب: METHOD path -> code — به تُقاس
 *     مطابقة العقد حرفاً بحرف في بوابة RESTGATE.
 *   • dump() = كل ما يراه الخادم (كما في المحاكي النقي) — عليه يُقاس
 *     الصفر-المعرفة: إنتروبيا، بلا عربية، بلا أنواع سجلات، سحر NSF1 عند
 *     بدايات الكتل فقط بعدد الكتل بالضبط.
 */
public final class SyncServer {

    /** هامش القراءة الآمن فوق سقف الكتلة — ما فوقه يُرفض 413 دون قراءة. */
    private static final int BODY_SLACK = 64 * 1024;

    /** استجابة مجمعة: الرمز + الجسم + النوع. */
    private static final class Out {
        final int code; final byte[] body; final String ctype;
        Out(int code, byte[] body, String ctype) { this.code = code; this.body = body; this.ctype = ctype; }
        Out(int code) { this(code, null, null); }
    }

    static final class Account {
        final String id;
        byte[] salt;
        long used = 0;
        boolean vip = false;
        final Map<String, byte[]> blobs = new LinkedHashMap<>();
        Account(String id, byte[] salt) { this.id = id; this.salt = salt.clone(); }
    }

    private final HttpServer http;
    private final ExecutorService pool;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<String> wire = Collections.synchronizedList(new ArrayList<String>());

    /** خادم حقيقي على 127.0.0.1 بمنفذ يختاره النظام (0) — عزلة كاملة للحلبة. */
    public static SyncServer start() throws IOException { return new SyncServer(); }

    private SyncServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> route(ex));
        pool = Executors.newFixedThreadPool(2);
        http.setExecutor(pool);
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }
    /** القاعدة الموحدة للعملاء — نفس شكل قائمة NetGate البيضاء مع /v1/. */
    public String base() { return "http://127.0.0.1:" + port() + "/v1/"; }
    public void stop() { http.stop(0); pool.shutdownNow(); }

    // ---- التوجيه والمعالجة -------------------------------------------------------

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Out out;
        try {
            out = handle(method, path, ex);
        } catch (Throwable t) {
            out = new Out(400);                                    // أي غموض = إغلاق
        }
        wire.add(method + " " + path + " -> " + out.code);
        if (out.body != null && out.body.length > 0) ex.getResponseHeaders().set("Content-Type", out.ctype);
        ex.sendResponseHeaders(out.code, out.body == null || out.body.length == 0 ? -1 : out.body.length);
        if (out.body != null && out.body.length > 0) {
            OutputStream os = ex.getResponseBody();
            os.write(out.body);
            os.close();
        }
        ex.close();
    }

    private Out handle(String method, String path, HttpExchange ex) throws IOException {
        if (!path.startsWith("/v1/")) return new Out(404);         // نسخة واحدة فقط
        String[] seg = path.substring(4).split("/");
        if (seg.length < 2 || !"account".equals(seg[0])) return new Out(404);
        if (!isB64url(seg[1])) return new Out(400);                // معرف الحساب b64url حصراً
        String acct = seg[1];
        if (seg.length == 3 && "salt".equals(seg[2])) {
            if ("GET".equals(method)) return saltGet(acct);
            if ("PUT".equals(method)) return saltPut(ex, acct);
            return new Out(405);
        }
        if (seg.length == 3 && "blobs".equals(seg[2])) {
            if ("GET".equals(method)) return blobsList(acct);
            return new Out(405);
        }
        if (seg.length == 4 && "blob".equals(seg[2])) {
            if ("GET".equals(method)) return blobGet(acct, seg[3]);
            if ("PUT".equals(method)) return blobPut(ex, acct, seg[3]);
            return new Out(405);
        }
        return new Out(404);                                       // شكل مسار غريب = لا شيء بعد
    }

    // ---- النقاط الخمس ------------------------------------------------------------

    /** #1: 200 + الملح العلني | 404 (لا شيء بعد). */
    private Out saltGet(String acct) {
        byte[] s;
        synchronized (this) {
            Account a = accounts.get(acct);
            s = a == null ? null : a.salt.clone();
        }
        return s == null ? new Out(404) : new Out(200, s, "application/octet-stream");
    }

    /** #2: جديد → 201؛ نفس الملح → 201 (idempotent)؛ ملح مختلف → 409 بلا استبدال. */
    private Out saltPut(HttpExchange ex, String acct) throws IOException {
        byte[] b = readBody(ex);
        if (b == null) return new Out(413);
        if (b.length != SyncVault.SALT_LEN) return new Out(400);
        synchronized (this) {
            Account a = accounts.get(acct);
            if (a == null) { accounts.put(acct, new Account(acct, b)); return new Out(201); }
            if (MessageDigest.isEqual(a.salt, b)) return new Out(201);
            return new Out(409);                                   // الملح المثبت لا يُستبدل
        }
    }

    /** #3: مصفوفة JSON من المعرفات المرتبة — الحتمية نفسها كما في المحاكي. */
    private Out blobsList(String acct) {
        List<String> ids;
        synchronized (this) {
            Account a = accounts.get(acct);
            if (a == null) return new Out(404);
            ids = new ArrayList<>(a.blobs.keySet());
        }
        Collections.sort(ids);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(ids.get(i)));
        }
        sb.append(']');
        return new Out(200, sb.toString().getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** #4: 200 + بايتات المغلف الخام | 400 معرف غريب | 404. */
    private Out blobGet(String acct, String blobId) {
        if (!isB64url(blobId)) return new Out(400);
        byte[] b;
        synchronized (this) {
            Account a = accounts.get(acct);
            b = a == null ? null : a.blobs.get(blobId);
        }
        if (b == null) return new Out(404);
        return new Out(200, b.clone(), "application/octet-stream");
    }

    /** #5 بالترتيب الحرفي للعقد: 400 شكل/معرف ← 404 حساب ← 413 سقف ← 507 حصة ← 201. */
    private Out blobPut(HttpExchange ex, String acct, String blobId) throws IOException {
        if (!isB64url(blobId) || blobId.isEmpty()) return new Out(400);
        byte[] b = readBody(ex);
        if (b == null) return new Out(413);                        // فوق هامش القراءة الآمنة
        synchronized (this) {
            Account a = accounts.get(acct);
            if (a == null) return new Out(404);
            if (b.length > SyncVault.MAX_BLOB_BYTES) return new Out(413);   // السقف — حتى مع VIP
            long old = a.blobs.containsKey(blobId) ? a.blobs.get(blobId).length : 0;
            SyncVault.Quota q = SyncVault.checkQuota(b.length, a.used - old, a.vip);
            if (!q.allowed) return new Out("blob_too_big".equals(q.reason) ? 413 : 507);
            a.used += b.length - old;                              // محاسبة تفاضلية
            a.blobs.put(blobId, b.clone());
            return new Out(201);
        }
    }

    // ---- أدوات -------------------------------------------------------------------

    /** قراءة جسم الطلب بحد آمن؛ null = تجاوز ساحق يُرفض دون قراءة. */
    private byte[] readBody(HttpExchange ex) throws IOException {
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        long n = 0;
        try { n = cl == null ? 0 : Long.parseLong(cl.trim()); } catch (NumberFormatException e) { return new byte[0]; }
        if (n > SyncVault.MAX_BLOB_BYTES + BODY_SLACK) return null;
        byte[] b = new byte[(int) n];
        InputStream in = ex.getRequestBody();
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, (int) n - off);
            if (r < 0) break;
            off += r;
        }
        return off == n ? b : Arrays.copyOf(b, off);
    }

    static boolean isB64url(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** تهريب JSON نصي بسيط لمعرفات ASCII — المعرفات b64url بلا escapes أصلاً. */
    private static String q(String v) { return "\"" + v + "\""; }

    // ---- فاحص الحلبة (نموذج التهديد: الخادم نفسه الطرف غير الموثوق) ---------------

    /** سجل السلك الكامل: كل طلب بصيغة "METHOD path -> code". */
    public List<String> wireLog() { synchronized (wire) { return new ArrayList<>(wire); } }

    public synchronized byte[] saltOf(String acct) {
        Account a = accounts.get(acct);
        return a == null ? null : a.salt.clone();
    }

    public synchronized byte[] rawBlob(String acct, String blobId) {
        Account a = accounts.get(acct);
        if (a == null) return null;
        byte[] b = a.blobs.get(blobId);
        return b == null ? null : b.clone();
    }

    public synchronized List<String> blobIds(String acct) {
        Account a = accounts.get(acct);
        if (a == null) return new ArrayList<>();
        List<String> ids = new ArrayList<>(a.blobs.keySet());
        Collections.sort(ids);
        return ids;
    }

    public synchronized int blobCount(String acct) {
        Account a = accounts.get(acct);
        return a == null ? -1 : a.blobs.size();
    }

    public synchronized long usedBytes(String acct) {
        Account a = accounts.get(acct);
        return a == null ? -1 : a.used;
    }

    /** صفة الحساب خادمية — العميل لا يستطيع إصدارها على السلك (العقد بلا حقل فئة). */
    public synchronized void setVip(String acct, boolean vip) {
        Account a = accounts.get(acct);
        if (a != null) a.vip = vip;
    }

    /** كل ما يراه الخادم — عليه يُقاس تجريد الصفر-المعرفة (نفس بنية المحاكي). */
    public synchronized byte[] dump() {
        List<byte[]> all = new ArrayList<>();
        for (Account a : accounts.values()) {
            all.add(a.id.getBytes(StandardCharsets.UTF_8));
            all.add(a.salt);
            for (Map.Entry<String, byte[]> e : a.blobs.entrySet()) {
                all.add(e.getKey().getBytes(StandardCharsets.UTF_8));
                all.add(e.getValue());
            }
        }
        int n = 0;
        for (byte[] b : all) n += b.length;
        byte[] outb = new byte[n];
        int off = 0;
        for (byte[] b : all) { System.arraycopy(b, 0, outb, off, b.length); off += b.length; }
        return outb;
    }

    // ---- هجمات الفاحص (للإثبات السلبي المحفوظ — التلف دائم ووُعٍ بالحساب) ---------

    /** قلب بايت واحد في الكتلة رقم index (بترتيبها المرتب). */
    public synchronized void tamperBlob(String acct, int index, int offset) {
        Account a = accounts.get(acct);
        if (a == null) throw new IllegalStateException("no_account");
        List<String> ids = new ArrayList<>(a.blobs.keySet());
        Collections.sort(ids);
        byte[] b = a.blobs.get(ids.get(index));
        b[offset] ^= (byte) 0xFF;
    }

    /** بتر الكتلة رقم index بمقدار cut بايت من النهاية (يلتهم وسم GCM). */
    public synchronized void truncateBlob(String acct, int index, int cut) {
        Account a = accounts.get(acct);
        if (a == null) throw new IllegalStateException("no_account");
        List<String> ids = new ArrayList<>(a.blobs.keySet());
        Collections.sort(ids);
        byte[] b = a.blobs.get(ids.get(index));
        a.blobs.put(ids.get(index), Arrays.copyOf(b, b.length - cut));
    }

    /** قلب بايت في الملح المثبت — الدخول الجديد يجب أن يفشل مغلق. */
    public synchronized void tamperSalt(String acct, int offset) {
        Account a = accounts.get(acct);
        if (a == null) throw new IllegalStateException("no_account");
        a.salt[offset] ^= (byte) 0xFF;
    }
}
