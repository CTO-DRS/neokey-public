package com.hark.neokey;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * KeyGuard — تشفير البيانات الحساسة أثناء السكون (at-rest) — G1 NEOKEY 1.0 GLOBAL.
 *
 * <p>قبل الإصلاح كان مفتاح OpenRouter (`ai_key`) مخزّناً نصاً صريحاً في
 * SharedPreferences؛ نص صريح في مخزن خاص يبقى نصاً صريحاً عند أي وصول ثانوي
 * (نسخ احتياطي، استخراج بيانات، جهاز مفكوك قفله). هذا الصنف يغلّف أي نص حساس
 * في مغلف AES-256-GCM مصادق.</p>
 *
 * <p><b>النواة النقية (هذا الملف):</b> صفر android.* — تعمل وتُختبر على JVM.
 * المفتاح يُحقَن من المتصل، فيكون الاختبار بمفتاح محلي والتشغيل الحي بمفتاح
 * AndroidKeyStore (الغلاف الجهازي في WriterActivity: API 23+ مفتاح مقيم في
 * Keystore لا يُستخرج أبداً؛ API 21–22 مفتاح مولّد في filesDir الخاص — قيد
 * موثق: ليس مدعوماً بالعتاد هناك).</p>
 *
 * <p><b>التنسيق:</b> {@code kg1.<b64(iv 12)>.<b64(ct+tag 128bit)>} — البادئة
 * إصدار للتنسيق؛ أي مغلف غريب = فشل مغلق (رفض، لا تخمين).</p>
 *
 * <p><b>العقد الأمني:</b> GCM يرفض النص العبث به (فشل المغلق عند tag mismatch)،
 * والـiv عشوائي لكل عملية ختم (لا إعادة استخدام مفتاح-iv)، والمفتاح الواحد
 * يكفي لأن العدّاد المحلي محدود (عدد عمليات حفظ المفتاح لا يبلغ حصص GCM).</p>
 */
public final class KeyGuard {

    /** بادئة تنسيق المغلف — إصدار 1. */
    public static final String PREFIX = "kg1";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    /** خطأ موحد فشل-مغلق — الرسائل أسماء أسباب فقط، لا محتوى. */
    public static final class GuardRefused extends RuntimeException {
        public GuardRefused(String reason) { super(reason); }
    }

    /** ختم نص حساس بمغلف مصادق — iv عشوائي لكل ختم. */
    public static String seal(String plain, SecretKey key) {
        if (plain == null) throw new GuardRefused("null_plain");
        if (key == null) throw new GuardRefused("null_key");
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return PREFIX + "." + b64(iv) + "." + b64(ct);
        } catch (GuardRefused x) {
            throw x;
        } catch (Exception x) {
            throw new GuardRefused("seal_failed");
        }
    }

    /** فتح مغلف — أي عبث/تنسيق غريب/مفتاح خطأ = GuardRefused (فشل مغلق). */
    public static String open(String blob, SecretKey key) {
        if (blob == null || blob.isEmpty()) throw new GuardRefused("null_blob");
        if (key == null) throw new GuardRefused("null_key");
        String[] p = blob.split("\\.", -1);
        if (p.length != 3 || !PREFIX.equals(p[0])) throw new GuardRefused("bad_format");
        byte[] iv, ct;
        try {
            iv = unb64(p[1]);
            ct = unb64(p[2]);
        } catch (GuardRefused x) {
            throw x;
        }
        if (iv.length != IV_LEN || ct.length == 0) throw new GuardRefused("bad_lens");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception x) {
            throw new GuardRefused("open_failed");   // عبث أو مفتاح خطأ — بلا تفاصيل
        }
    }

    /** هل هذا المغلف من تنسيقنا؟ (فحص شكل بلا فتح — للترحيل الصامت). */
    public static boolean isSealed(String s) {
        if (s == null) return false;
        String[] p = s.split("\\.", -1);
        if (p.length != 3 || !PREFIX.equals(p[0])) return false;
        try { return unb64(p[1]).length == IV_LEN && unb64(p[2]).length > 0; }
        catch (Exception x) { return false; }
    }

    /** مفتاح من مادة خام (اختبارات JVM + مسار ترحيل الأجهزة القديمة) — 16/24/32 بايت. */
    public static SecretKey rawKey(byte[] material) {
        if (material == null || (material.length != 16 && material.length != 24 && material.length != 32))
            throw new GuardRefused("key_len");
        return new SecretKeySpec(material, "AES");
    }

    /** مشتق مفتاح من عبارة (PBKDF2-HMAC-SHA256، 210k دورة — نفس جدار SyncVault) — JVM فقط في الاختبارات. */
    public static SecretKey derivedKey(String secret, byte[] salt) {
        if (secret == null || secret.isEmpty()) throw new GuardRefused("empty_secret");
        if (salt == null || salt.length < 16) throw new GuardRefused("salt_len");
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt, 210_000, 256);
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] out = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(out, "AES");
        } catch (GuardRefused x) {
            throw x;
        } catch (Exception x) {
            throw new GuardRefused("derive_failed");
        }
    }

    // ---- b64 نقي (بدون android.util.Base64 — قابل للاختبار على JVM) -------------

    private static final char[] AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String b64(byte[] d) {
        StringBuilder s = new StringBuilder((((d.length + 2) / 3) * 4));
        for (int i = 0; i < d.length; i += 3) {
            int b = ((d[i] & 0xFF) << 16) | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 0xFF) : 0);
            s.append(AL[(b >> 18) & 63]).append(AL[(b >> 12) & 63])
             .append(i + 1 < d.length ? AL[(b >> 6) & 63] : '=')
             .append(i + 2 < d.length ? AL[b & 63] : '=');
        }
        return s.toString();
    }

    static byte[] unb64(String s) {
        int n = s.length();
        if (n == 0 || n % 4 != 0) throw new GuardRefused("b64_len");
        int pad = 0;
        while (pad < n && s.charAt(n - 1 - pad) == '=') pad++;
        if (pad > 2) throw new GuardRefused("b64_pad");
        byte[] out = new byte[n / 4 * 3 - pad];
        int o = 0;
        for (int i = 0; i < n; i += 4) {
            int b = 0;
            for (int k = 0; k < 4; k++) {
                char ch = s.charAt(i + k);
                if (ch == '=') {
                    if (i + k < n - pad) throw new GuardRefused("b64_pad");   // '=' في المنتصف
                    b <<= 6;
                } else {
                    int v = indexOf(AL, ch);
                    if (v < 0) throw new GuardRefused("b64_char");
                    b = (b << 6) | v;
                }
            }
            out[o++] = (byte) (b >> 16);
            if (o < out.length) out[o++] = (byte) (b >> 8);
            if (o < out.length) out[o++] = (byte) b;
        }
        return out;
    }

    private static int indexOf(char[] a, char c) {
        for (int i = 0; i < a.length; i++) if (a[i] == c) return i;
        return -1;
    }

    private KeyGuard() { }
}
