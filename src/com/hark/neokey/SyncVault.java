package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * خزنة المزامنة المشفرة طرف-لطرف — المرحلة 2، البند A، الأسبوع 1 (نواة التشفير
 * الصفرية المعرفة). نقي JVM: صفر android.*، وبلا شبكة أصلاً (الشبكة للعميل لاحقاً).
 *
 * العقد الصفري-المعرفة (Zero-Knowledge) كما في الخارطة: «مشفرة على الجهاز بمفتاح
 * مشتق من حساب المستخدم نفسه، بحيث يرى الخادم بيانات عشوائية لا معنى لها».
 *   • المفتاح = PBKDF2-HMAC-SHA256 (منفّذ يدوياً بـ javax.crypto.Mac — نفس
 *     النتيجة على أي منصة، ويشتغل من API 21 حيث مزوّد Android لا يوفّر
 *     PBKDF2WithHmacSHA256 قبل API 26) + HKDF (RFC 5869) يدوي للمفاتيح الفرعية.
 *   • التشفير = AES-256-GCM بترويسة موقّعة كبيانات إضافية (AAD) — أي تبديل بايت
 *     في الترويسة أو المتن يُرفض، ولا يُعاد أبداً نص الحالة نصف-مبني.
 *   • الخادم يرى: اسم حساب مستعار مشتق + معرف كتلة عشوائي + بايتات عشوائية.
 *     المصادقة هي فك التشفير نفسه: كلمة سر خاطئة = رفض، لا تحقق لدى الخادم.
 *   • الدمج حتمي (آخر-كاتب-يفوز بترتيب كلي) — نفس المدخلات على أي جهاز = نفس
 *     المخرجات، والدالتان تبادليتان (merge(a,b) = merge(b,a)).
 *
 * تنسيق المغلف NSF1 (نسخة 1):
 *   [0..3]  سحر "NSF1"   [4] نسخة=1   [5] kdf=1
 *   [6..37] ملح الحساب (32B)   [38..49] nonce (12B)
 *   [50..]  ciphertext||tag(16B)  — AAD = الترويسة كاملة [0..50)
 */
public final class SyncVault {

    // ---- ثوابت التنسيق --------------------------------------------------------
    public static final byte[] MAGIC = {'N', 'S', 'F', '1'};
    public static final int VERSION = 1;
    public static final int KDF_ID = 1;                    // PBKDF2-HMAC-SHA256 يدوي
    public static final int KDF_ITERATIONS = 210_000;      // OWASP 2023 لـ PBKDF2-SHA256
    public static final int SALT_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;
    public static final int HEADER_LEN = 4 + 1 + 1 + SALT_LEN + NONCE_LEN; // = 50

    // ---- الحصص (سياسة نقية — تُقاس في البوابة) ---------------------------------
    /** سقف الكتلة الواحدة للخادم (كل الأجهزة). */
    public static final int MAX_BLOB_BYTES = 8 * 1024 * 1024;
    /** حصة الحساب المجانية — «حصص معقولة للمجاني». */
    public static final int ACCOUNT_FREE_BYTES = 1024 * 1024;
    /** حصة VIP — «حصص رحيمة لـ VIP». */
    public static final int ACCOUNT_VIP_BYTES = 50 * 1024 * 1024;

    /** قرار سياسة الحصة — حتمي بالكامل. */
    public static final class Quota {
        public final boolean allowed;
        public final String reason;   // ok / blob_too_big / quota_free / quota_vip

        Quota(boolean allowed, String reason) { this.allowed = allowed; this.reason = reason; }
        @Override public String toString() { return (allowed ? "ALLOW(" : "DENY(") + reason + ")"; }
    }

    /** فحص الحصة قبل أي إرسال: حجم الكتلة ثم مجموع الحساب (مجاني أو VIP). */
    public static Quota checkQuota(int blobBytes, long accountUsedBytes, boolean vip) {
        if (blobBytes < 0 || accountUsedBytes < 0)
            return new Quota(false, "quota_free");            // غموض = رفض
        if (blobBytes > MAX_BLOB_BYTES) return new Quota(false, "blob_too_big");
        long cap = vip ? ACCOUNT_VIP_BYTES : ACCOUNT_FREE_BYTES;
        if (accountUsedBytes + blobBytes > cap)
            return new Quota(false, vip ? "quota_vip" : "quota_free");
        return new Quota(true, "ok");
    }

    // ---- المفاتيح --------------------------------------------------------------
    /** مجموعة المفاتيح المشتقة من كلمة سر + ملح — تُبنى مرة لكل جلسة. */
    public static final class KeySet {
        public final byte[] salt;
        public final byte[] enc;       // AES-256
        public final byte[] id;        // اشتقاق الاسم المستعار للحساب
        public final String accountId; // ما يراه الخادم فقط — لا علاقة له بالبريد/الاسم

        KeySet(byte[] salt, byte[] enc, byte[] id, String accountId) {
            this.salt = salt; this.enc = enc; this.id = id; this.accountId = accountId;
        }
    }

    /** ملح حساب جديد عشوائي (تسجيل أول جهاز). */
    public static byte[] newSalt() {
        byte[] s = new byte[SALT_LEN];
        new SecureRandom().nextBytes(s);
        return s;
    }

    /** الاشتقاق الكامل: PBKDF2 ثم HKDF إلى (enc, id) ثم الاسم المستعار. */
    public static KeySet derive(String passphrase, byte[] salt) {
        if (passphrase == null || passphrase.isEmpty())
            throw new SyncRefused("empty_passphrase");
        if (salt == null || salt.length != SALT_LEN) throw new SyncRefused("bad_salt");
        byte[] master = pbkdf2(passphrase.toCharArray(), salt, KDF_ITERATIONS, 32);
        byte[] enc = hkdfExpand(hkdfExtract(salt, master), "neokey/enc", 32);
        byte[] idk = hkdfExpand(hkdfExtract(salt, master), "neokey/id", 32);
        byte[] acct = hmac(idk, "neokey/account-v1".getBytes(StandardCharsets.UTF_8));
        byte[] masked = Arrays.copyOf(acct, 16);            // 128 بت مستعار كافٍ
        Arrays.fill(master, (byte) 0);
        return new KeySet(copy(salt), enc, idk, b64url(masked));
    }

    // ---- PBKDF2-HMAC-SHA256 (RFC 2898) يدوياً — نفس السلوك على كل منصة ----------
    public static byte[] pbkdf2(char[] pass, byte[] salt, int iterations, int dkLen) {
        if (iterations < 1 || dkLen < 1) throw new SyncRefused("kdf_args");
        byte[] p = new String(pass).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[dkLen];
        byte[] u = new byte[0];
        int block = 1, off = 0;
        try {
            while (off < dkLen) {
                byte[] s1 = concat(salt, be32(block));
                u = hmac(p, s1);
                byte[] t = copy(u);
                for (int i = 1; i < iterations; i++) {
                    u = hmac(p, u);
                    for (int j = 0; j < t.length; j++) t[j] ^= u[j];
                }
                int n = Math.min(t.length, dkLen - off);
                System.arraycopy(t, 0, out, off, n);
                off += n; block++;
            }
        } finally {
            Arrays.fill(p, (byte) 0);
            if (u.length > 0) Arrays.fill(u, (byte) 0);
        }
        return out;
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    // ---- HMAC-SHA256 + HKDF (RFC 5869) ----------------------------------------
    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new SyncRefused("hmac_unavailable");
        }
    }

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        byte[] s = (salt == null || salt.length == 0) ? new byte[32] : salt;
        return hmac(s, ikm);
    }

    public static byte[] hkdfExpand(byte[] prk, String info, int len) {
        return hkdfExpand(prk, info.getBytes(StandardCharsets.UTF_8), len);
    }

    /** التوسيع بصيغة RFC 5869 الحقيقية (info ثنائي — للبوابات والمتجهات). */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {
        if (len > 255 * 32) throw new SyncRefused("hkdf_len");
        byte[] okm = new byte[len];
        byte[] t = new byte[0];
        int off = 0, counter = 1;
        while (off < len) {
            t = hmac(prk, concat(concat(t, info), new byte[]{(byte) counter}));
            int n = Math.min(t.length, len - off);
            System.arraycopy(t, 0, okm, off, n);
            off += n; counter++;
        }
        return okm;
    }

    // ---- المغلف: ختم/فتح --------------------------------------------------------
    /**
     * ختم الحالة في مغلف NSF1. nonce صريح (12B) ليكون الاختبار حتمياً؛
     * الإنتاج يولّده عشوائياً لكل ختم. AAD = الترويسة كاملة.
     */
    public static byte[] seal(KeySet ks, byte[] plaintext, byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_LEN) throw new SyncRefused("bad_nonce");
        if (plaintext == null) throw new SyncRefused("null_plain");
        byte[] header = new byte[HEADER_LEN];
        System.arraycopy(MAGIC, 0, header, 0, 4);
        header[4] = (byte) VERSION;
        header[5] = (byte) KDF_ID;
        System.arraycopy(ks.salt, 0, header, 6, SALT_LEN);
        System.arraycopy(nonce, 0, header, 6 + SALT_LEN, NONCE_LEN);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ks.enc, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(header);
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[HEADER_LEN + ct.length];
            System.arraycopy(header, 0, out, 0, HEADER_LEN);
            System.arraycopy(ct, 0, out, HEADER_LEN, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new SyncRefused("seal_failed");
        }
    }

    /** فتح مغلف: تحقق صارم من الترويسة أولاً، ثم GCM — أي خلل = SyncRefused. */
    public static byte[] open(KeySet ks, byte[] envelope) {
        if (envelope == null || envelope.length < HEADER_LEN + 16) throw new SyncRefused("too_short");
        if (envelope[0] != MAGIC[0] || envelope[1] != MAGIC[1]
                || envelope[2] != MAGIC[2] || envelope[3] != MAGIC[3]) throw new SyncRefused("bad_magic");
        if ((envelope[4] & 0xFF) != VERSION) throw new SyncRefused("bad_version");
        if ((envelope[5] & 0xFF) != KDF_ID) throw new SyncRefused("bad_kdf");
        if (!MessageDigest.isEqual(ks.salt, Arrays.copyOfRange(envelope, 6, 6 + SALT_LEN)))
            throw new SyncRefused("salt_mismatch");          // ملح آخر = مفتاح آخر = رفض
        byte[] nonce = Arrays.copyOfRange(envelope, 6 + SALT_LEN, HEADER_LEN);
        byte[] ct = Arrays.copyOfRange(envelope, HEADER_LEN, envelope.length);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ks.enc, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(envelope, 0, HEADER_LEN);
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new SyncRefused("auth_failed");            // كلمة سر خاطئة أو تلاعب
        }
    }

    /** الاستثناء الوحيد للوحدة: أي رفض (تلاعب/كلمة سر/تنسيق) بلا نص حساس. */
    public static final class SyncRefused extends RuntimeException {
        public SyncRefused(String reason) { super(reason); }
    }

    // ---- سجلات الحالة -----------------------------------------------------------
    /** أنواع السجلات المتزامنة — كل جديد يُضاف هنا بقلب النوع. */
    public static final String KIND_THEME = "theme";
    public static final String KIND_CLIP_PIN = "clip_pin";
    public static final String KIND_DICT_WORD = "dict_word";
    public static final String KIND_SHORTCUT = "shortcut";
    public static final String KIND_SETTING = "setting";
    static final String[] KINDS = {KIND_THEME, KIND_CLIP_PIN, KIND_DICT_WORD, KIND_SHORTCUT, KIND_SETTING};

    public static boolean knownKind(String k) {
        for (String x : KINDS) if (x.equals(k)) return true;
        return false;
    }

    /** سجل مزامنة واحد: id مستقر + rev عدّاد الجهاز + ts آخر كتابة + شاهد حذف. */
    public static final class Rec {
        public final String id, kind, key, body;
        public final int rev;
        public final long ts;
        public final boolean tomb;

        public Rec(String id, String kind, String key, String body, int rev, long ts, boolean tomb) {
            this.id = id; this.kind = kind; this.key = key; this.body = body;
            this.rev = rev; this.ts = ts; this.tomb = tomb;
            if (id == null || id.isEmpty()) throw new SyncRefused("rec_id");
            if (!knownKind(kind)) throw new SyncRefused("rec_kind");
            if (rev < 1) throw new SyncRefused("rec_rev");
            if (ts < 0) throw new SyncRefused("rec_ts");
            if (!tomb && body == null) throw new SyncRefused("rec_body");
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Rec)) return false;
            Rec r = (Rec) o;
            return id.equals(r.id) && rev == r.rev && ts == r.ts && tomb == r.tomb
                    && kind.equals(r.kind) && key.equals(r.key)
                    && (body == null ? r.body == null : body.equals(r.body));
        }
    }

    private static int cmp(Rec a, Rec b) {
        if (a.rev != b.rev) return a.rev < b.rev ? -1 : 1;
        if (a.ts != b.ts) return a.ts < b.ts ? -1 : 1;
        if (a.tomb != b.tomb) return a.tomb ? -1 : 1;             // الشاهد يفوز عند التساوي
        byte[] x = a.body == null ? new byte[0] : a.body.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.body == null ? new byte[0] : b.body.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) return d;
        }
        int d = x.length - y.length;
        if (d != 0) return d;
        return a.kind.compareTo(b.kind) != 0 ? a.kind.compareTo(b.kind)
                : safeCmp(a.key, b.key);
    }

    private static int safeCmp(String a, String b) {
        String x = a == null ? "" : a, y = b == null ? "" : b;
        return x.compareTo(y);
    }

    /**
     * الدمج الحتمي: اتحاد السجلات، ولكل id الفائز بالترتيب الكلي. تبادلي
     * (merge(a,b)=merge(b,a)) ومحافظ على الهوية (merge(s,s)=s) — مُختبر.
     * الشاهد لا يقوم بكسر: نسخة أقدم rev لا تُحيي سجلاً محذوفاً.
     */
    public static List<Rec> merge(List<Rec> a, List<Rec> b) {
        if (a == null || b == null) throw new SyncRefused("merge_null");
        List<Rec> all = new ArrayList<>(a);
        all.addAll(b);
        Collections.sort(all, new Comparator<Rec>() {
            @Override public int compare(Rec x, Rec y) {
                if (!x.id.equals(y.id)) return x.id.compareTo(y.id);
                return cmp(y, x);                                  // الأعلى رتبةً أولاً
            }
        });
        List<Rec> out = new ArrayList<>();
        String prev = null;
        for (Rec r : all) {
            if (r.id.equals(prev)) continue;                       // الفائز الأول فقط
            prev = r.id;
            out.add(r);
        }
        return out;
    }

    // ---- JSON صارم للحالة --------------------------------------------------------
    /** تسلسل الحالة: {"v":1,"recs":[[id,kind,key,rev,ts,tomb,body],…]} — UTF-8. */
    public static byte[] stateToBytes(List<Rec> state) {
        if (state == null) throw new SyncRefused("null_state");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":1,\"recs\":[");
        boolean first = true;
        for (Rec r : state) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(q(r.id)).append(',').append(q(r.kind)).append(',')
              .append(q(r.key)).append(',').append(r.rev).append(',').append(r.ts).append(',')
              .append(r.tomb ? '1' : '0').append(',').append(q(r.body == null ? "" : r.body))
              .append(']');
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** تحليل صارم: نسخة واحدة معروفة، بلا فواصل زائدة، بلا محارف تحكم خام. */
    public static List<Rec> bytesToState(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        List<Rec> out = new ArrayList<>();
        P p = new P(s);
        p.ws(); p.expect('{'); p.ws(); p.lit("\"v\""); p.ws(); p.expect(':'); p.ws();
        if (p.take() != '1') throw new SyncRefused("json_version");
        p.ws(); p.expect(','); p.ws(); p.lit("\"recs\""); p.ws(); p.expect(':'); p.ws(); p.expect('[');
        p.ws();
        if (p.peek() == ']') { p.take(); p.ws(); p.expect('}'); p.end(); return out; }
        while (true) {
            p.ws(); p.expect('['); p.ws();
            String id = p.str(); p.ws(); p.expect(',');
            String kind = p.str(); p.ws(); p.expect(',');
            String key = p.str(); p.ws(); p.expect(',');
            p.ws(); int rev = (int) p.num(); p.ws(); p.expect(',');
            p.ws(); long ts = p.num(); p.ws(); p.expect(',');
            p.ws(); boolean tomb = p.take() == '1'; p.ws(); p.expect(',');
            p.ws(); String body = p.str(); p.ws(); p.expect(']');
            out.add(new Rec(id, kind, key, body, rev, ts, tomb));
            p.ws();
            char c = p.take();
            if (c == ']') break;
            if (c != ',') throw new SyncRefused("json_sep");
        }
        p.ws(); p.expect('}'); p.end();
        return out;
    }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        char peek() { if (i >= s.length()) throw new SyncRefused("json_eof"); return s.charAt(i); }
        char take() { if (i >= s.length()) throw new SyncRefused("json_eof"); return s.charAt(i++); }
        void ws() { while (i < s.length()) { char c = s.charAt(i); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break; } }
        void expect(char c) { if (take() != c) throw new SyncRefused("json_char"); }
        void lit(String t) { if (!s.startsWith(t, i)) throw new SyncRefused("json_lit"); i += t.length(); }
        void end() { if (i != s.length()) throw new SyncRefused("json_trailing"); }
        long num() {
            int st = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == st) throw new SyncRefused("json_num");
            return Long.parseLong(s.substring(st, i));
        }
        String str() {
            if (take() != '"') throw new SyncRefused("json_str");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') break;
                if (c < 0x20) throw new SyncRefused("json_ctrl");
                if (c == '\\') {
                    char e = take();
                    if (e == '"') sb.append('"');
                    else if (e == '\\') sb.append('\\');
                    else if (e == '/') sb.append('/');
                    else if (e == 'n') sb.append('\n');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'b') sb.append('\b');
                    else if (e == 'f') sb.append('\f');
                    else if (e == 'u') {
                        if (i + 4 > s.length()) throw new SyncRefused("json_u");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else throw new SyncRefused("json_esc");
                } else sb.append(c);
            }
            return sb.toString();
        }
    }

    static String q(String v) {
        if (v == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') { sb.append('\\').append(c); }
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\b') sb.append("\\b");
            else if (c == '\f') sb.append("\\f");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ---- البروتوكول: جهاز A يدفع، جهاز B يستعيد ---------------------------------
    /** دفعة واحدة: ختم الحالة + معرف كتلة عشوائي (16B). يُعيد (blobId, bytes). */
    public static String[] packForPush(KeySet ks, List<Rec> state, byte[] random16) {
        byte[] nonce = Arrays.copyOf(random16, NONCE_LEN);
        byte[] blob = seal(ks, stateToBytes(state), nonce);
        return new String[]{b64url(Arrays.copyOf(random16, 16)), b64url(blob)};
    }

    /** استقبال كتلة: فتح + تحليل — أي خلل رفض والسجل المحلي لا يُمس. */
    public static List<Rec> unpack(KeySet ks, byte[] blob) {
        return bytesToState(open(ks, blob));
    }

    /** استعادة كاملة: فتح كل كتل الحساب ثم دمجها مع المحلي (يرفض جزئياً: كل شيء أو لا شيء). */
    public static List<Rec> restore(List<Rec> local, KeySet ks, List<byte[]> blobs) {
        List<Rec> merged = new ArrayList<>(local == null ? Collections.<Rec>emptyList() : local);
        for (byte[] b : blobs) merged = merge(merged, unpack(ks, b));
        return merged;
    }

    // ---- أدوات -----------------------------------------------------------------
    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static byte[] copy(byte[] a) { return Arrays.copyOf(a, a.length); }

    static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    static String b64url(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xFF, b1 = i + 1 < d.length ? d[i + 1] & 0xFF : 0,
                b2 = i + 2 < d.length ? d[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            if (i + 1 < d.length) sb.append(B64[((b1 & 15) << 2) | (b2 >> 6)]);
            if (i + 2 < d.length) sb.append(B64[b2 & 63]);
        }
        return sb.toString();
    }

    /** فك base64url صارم: بلا حشو، ورمز غريب أو طول 1 (mod 4) = رفض. */
    static byte[] b64urlDecode(String s) {
        if (s == null || s.isEmpty() || s.length() % 4 == 1) throw new SyncRefused("b64_len");
        int rem = s.length() % 4;
        int n = s.length() / 4 * 3 + (rem == 2 ? 1 : rem == 3 ? 2 : 0);
        byte[] out = new byte[n];
        int o = 0, acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = s.charAt(i) < 128 ? rev64(s.charAt(i)) : -1;
            if (v < 0) throw new SyncRefused("b64_char");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (acc >> bits);
            }
        }
        return out;
    }

    private static int rev64(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        return -1;
    }

    private SyncVault() { }
}
