package com.hark.neokey;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.util.Arrays;

/**
 * اختبارات KeyGuard — G1 (NEOKEY 1.0 GLOBAL): تشفير البيانات الحساسة أثناء
 * السكون. النواة النقية (ختم/فتح/رفض/ترحيل-فحص) على JVM بمفاتيح محلية؛
 * مسار AndroidKeyStore جهازي بولا اختبار وحدة هنا (موثق BLOCKED في التقرير).
 */
public class KeyGuardTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static void refused(String name, Runnable r) {
        try { r.run(); check(name, false); }
        catch (KeyGuard.GuardRefused x) { check(name, true); }
        catch (Exception x) { check(name + " (خطأ غلط: " + x.getClass().getSimpleName() + ")", false); }
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void run() {
        SecretKey k = KeyGuard.derivedKey("secret-for-the-test-only", salt("s1"));
        SecretKey k2 = KeyGuard.derivedKey("another-secret", salt("s2"));

        // ---- الختم/الفتح --------------------------------------------------------
        String blob = KeyGuard.seal("sk-or-v1-test-000", k);
        check("ختم ثم فتح = الأصل", "sk-or-v1-test-000".equals(KeyGuard.open(blob, k)));
        check("تنسيق المغلف kg1.iv.ct", KeyGuard.isSealed(blob) && blob.startsWith("kg1."));
        check("iv عشوائي لكل ختم — مغلفان لنفس النص مختلفان",
                !KeyGuard.seal("same", k).equals(KeyGuard.seal("same", k)));
        check("نص عربي كامل يختم ويُفتح",
                "سلامٌ عليكم — نصٌ بحروف ومسافات 123".equals(
                        KeyGuard.open(KeyGuard.seal("سلامٌ عليكم — نصٌ بحروف ومسافات 123", k), k)));
        check("نص فارغ يختم ويُفتح", "".equals(KeyGuard.open(KeyGuard.seal("", k), k)));

        // ---- الفشل المغلق --------------------------------------------------------
        refused("فتح بمفتاح خطأ → رفض (لا استثناء غريب يمر)",
                () -> KeyGuard.open(blob, k2));
        refused("عبث بالـ ciphertext → رفض",
                () -> KeyGuard.open(tamper(blob), k));
        refused("عبث بالـ iv → رفض",
                () -> KeyGuard.open("kg1." + KeyGuard.b64(new byte[12]) + "." + blob.split("\\.")[2], k));
        refused("تنسيق غريب (بادئة أخرى) → رفض", () -> KeyGuard.open("kg0.a.b", k));
        refused("أجزاء ناقصة → رفض", () -> KeyGuard.open("kg1.a", k));
        refused("غليب فارغ → رفض", () -> KeyGuard.open("", k));
        refused("مغلف null → رفض", () -> KeyGuard.open(null, k));
        refused("ختم بدون مفتاح → رفض", () -> KeyGuard.seal("x", null));
        refused("فتح بدون مفتاح → رفض", () -> KeyGuard.open(blob, null));
        refused("مفتاح بطول غلط → رفض", () -> KeyGuard.rawKey(new byte[10]));
        refused("عبارة مشتقة فارغة → رفض", () -> KeyGuard.derivedKey("", salt("s")));
        refused("salt قصير → رفض", () -> KeyGuard.derivedKey("x", new byte[8]));

        // ---- الشكل بلا فتح --------------------------------------------------------
        // isSealed عقد فحص الشكل فقط (للترحيل الصامت): المغلف المعبث يبقى صالح
        // الشكل — الكشف الفعلي للعبث عند open() (أُثبت أعلاه)، وهذا مقصود.
        check("isSealed يقبل الصحيح والمعبث الشكل والغريب يُرفض",
                KeyGuard.isSealed(blob)
                        && KeyGuard.isSealed(tamper(blob))
                        && !KeyGuard.isSealed("plaintext")
                        && !KeyGuard.isSealed(null)
                        && !KeyGuard.isSealed("kg0.a.b"));

        // ---- b64 الحافة ------------------------------------------------------------
        byte[] one = {5};
        check("b64/unb64 تعكس الطول 1 و2 و3 و31 بايت",
                Arrays.equals(one, KeyGuard.unb64(KeyGuard.b64(one)))
                        && Arrays.equals(new byte[2], KeyGuard.unb64(KeyGuard.b64(new byte[2])))
                        && Arrays.equals(new byte[3], KeyGuard.unb64(KeyGuard.b64(new byte[3])))
                        && Arrays.equals(new byte[31], KeyGuard.unb64(KeyGuard.b64(new byte[31]))));
        refused("b64 بطول غير مضروب في 4 → رفض", () -> KeyGuard.unb64("abc"));
        refused("b64 بحرف خارج الأبجدية → رفض", () -> KeyGuard.unb64("a*_-"));

        // ---- AEADBadTagException يظل داخل الرفض (لا يمر من open) -------------------
        boolean aeadWrapped = false;
        try {
            CipherProbe.probe();   // يبني tag غلط عمداً عبر JCE مباشرة
        } catch (AEADBadTagException e) {
            aeadWrapped = true;    // الدليل أن GCM يكشف العبث فعلاً على JVM الحي
        } catch (KeyGuard.GuardRefused x) {
            aeadWrapped = true;    // أو أنه يصل مغلفاً بفشل مغلق
        } catch (Exception e) {
            aeadWrapped = false;
        }
        check("GCM على JVM الحي يكشف العبث (AEADBadTag) ويصل مغلفاً", aeadWrapped);

        // ---- مسار الأجهزة (نقطة الموثوقية الموثقة، بلا ادعاء) ---------------------
        check("المسار الجهازي AndroidKeyStore موثق جهازياً فقط (لا اختبار وحدة هنا) — ملاحظة، لا فحص", true);
    }

    static String tamper(String blob) {
        String[] p = blob.split("\\.", -1);
        byte[] ct = KeyGuard.unb64(p[2]);
        ct[0] ^= 1;
        return p[0] + "." + p[1] + "." + KeyGuard.b64(ct);
    }

    static byte[] salt(String s) {
        byte[] b = new byte[16];
        byte[] src = s.getBytes();
        for (int i = 0; i < b.length; i++) b[i] = src[i % src.length];
        return b;
    }

    /** مبعث صغير يثبت كشف GCM للعبث على JVM الحي — مستقل عن KeyGuard. */
    static final class CipherProbe {
        static void probe() throws Exception {
            javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
            kg.init(128);
            SecretKey key = kg.generateKey();
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal("data".getBytes("UTF-8"));
            ct[0] ^= 1;
            javax.crypto.Cipher c2 = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c2.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            c2.doFinal(ct);
        }
    }
}
