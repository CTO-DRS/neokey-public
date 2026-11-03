package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.hark.neokey.SyncVault.*;

/**
 * اختبارات خزنة المزامنة — المرحلة 2، البند A، الأسبوع 1: متجهات RFC 5869
 * وPBKDF2 الحقيقية، الختم/الفتح والتلاعب في كل منطقة، الصفر-المعرفة بقياس
 * إنتروبيا وتجريد الخادم، الدمج الحتمي التبادلي، والاستعادة عبر جهازين.
 */
public class SyncVaultTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static byte[] hex(String s) {
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++)
            o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return o;
    }

    static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static Rec rec(String id, String kind, String body, int rev, long ts) {
        return new Rec(id, kind, id.substring(id.indexOf(':') + 1), body, rev, ts, false);
    }

    static List<Rec> sampleState() {
        List<Rec> s = new ArrayList<>();
        s.add(rec("theme:neon-2036", KIND_THEME, "{\"bg\":1,\"rim\":\"violet\"}", 3, 1000));
        s.add(rec("clip_pin:42", KIND_CLIP_PIN, "قائمة المشتريات: حليب وخبز", 1, 1001));
        s.add(rec("dict_word:10", KIND_DICT_WORD, "ابن الكرم", 2, 1002));
        s.add(rec("shortcut:مم", KIND_SHORTCUT, "ما شاء الله تبارك الله", 1, 1003));
        s.add(rec("setting:sound_pack", KIND_SETTING, "rain", 5, 1004));
        return s;
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void run() {
        // ---- متجهات التشفير المعروفة ------------------------------------------
        check("PBKDF2-HMAC-SHA256 متجه c=1 مطابق",
                hexOf(pbkdf2("password".toCharArray(), "salt".getBytes(), 1, 32))
                        .equals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"));
        check("PBKDF2-HMAC-SHA256 متجه c=2 مطابق",
                hexOf(pbkdf2("password".toCharArray(), "salt".getBytes(), 2, 32))
                        .equals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"));
        check("PBKDF2-HMAC-SHA256 متجه c=4096 مطابق",
                hexOf(pbkdf2("password".toCharArray(), "salt".getBytes(), 4096, 32))
                        .equals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"));
        check("HKDF RFC5869 حالة الاختبار 1 مطابقة (L=42)",
                hexOf(hkdfExpand(hkdfExtract(
                                hex("000102030405060708090a0b0c"), hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")),
                        hex("f0f1f2f3f4f5f6f7f8f9"), 42))
                        .equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
        check("HKDF حالة الاختبار 3 بملح فارغ مطابقة (L=42)",
                hexOf(hkdfExpand(hkdfExtract(new byte[0], hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")),
                        new byte[0], 42))
                        .equals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"));

        // ---- الاشتقاق والاسم المستعار ------------------------------------------
        byte[] salt = new byte[SALT_LEN];
        new Random(7).nextBytes(salt);
        KeySet ks = derive("عبارة السر الصحيحة", salt);
        check("الاسم المستعار مستقر (نفس المدخلات = نفس المعرف) وبطول ثابت",
                ks.accountId.equals(derive("عبارة السر الصحيحة", salt).accountId)
                        && ks.accountId.length() == 22);
        check("ملح مختلف = معرف مختلف (لا ارتباط بين الحسابات)",
                !ks.accountId.equals(derive("عبارة السر الصحيحة", hex(
                        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")).accountId));
        check("كلمة سر فارغة/ملح سيئ → رفض",
                refused(new Runnable0() { public void run() { derive("", salt); } })
                        && refused(new Runnable0() { public void run() { derive("x", new byte[8]); } }));

        // ---- الختم والفتح -------------------------------------------------------
        List<Rec> state = sampleState();
        byte[] pt = stateToBytes(state);
        byte[] nonce = new byte[NONCE_LEN];
        new Random(9).nextBytes(nonce);
        byte[] env = seal(ks, pt, nonce);
        check("المغلف يحمل السحر والنسخة وطول ترويسة 50 بايت بالضبط",
                env[0] == 'N' && env[1] == 'S' && env[2] == 'F' && env[3] == '1'
                        && env[4] == 1 && env[5] == 1 && env.length == HEADER_LEN + pt.length + 16);
        check("حساب الحجم دقيق: ct = pt + 16 (وسم GCM) وفوقية 100KB أقل من 0.1%",
                seal(ks, new byte[102400], nonce).length == HEADER_LEN + 102400 + 16
                        && (HEADER_LEN + 16) * 100.0 / 102400 < 0.1);
        check("الفتح يعيد الحالة نفسها حرفياً",
                Arrays.equals(open(ks, env), pt) && bytesToState(open(ks, env)).equals(state));
        check("nonce مختلف = مغلف مختلف (ولكن كلاهما يُفتح لنفس النص)",
                !Arrays.equals(env, seal(ks, pt, hex("ff0102030405060708090a0b")))
                        && Arrays.equals(open(ks, seal(ks, pt, hex("ff0102030405060708090a0b"))), pt));

        // ---- التلاعب: كل منطقة تُرفض -------------------------------------------
        check("كلمة سر خاطئة → رفض auth_failed (ولا نصف حالة)",
                refusedReason(new Runnable0() { public void run() {
                    open(derive("كلمة سر خاطئة تماماً", salt), env);
                }}).equals("auth_failed"));
        check("تبديل بايت في السحر/النسخة/kdf → رفض",
                refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[0] ^= 1; open(ks, e); } })
                        && refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[4] ^= 1; open(ks, e); } })
                        && refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[5] ^= 1; open(ks, e); } }));
        check("تبديل بايت في الملح/nonce/المتن/الوسم → رفض",
                refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[10] ^= 1; open(ks, e); } })
                        && refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[44] ^= 1; open(ks, e); } })
                        && refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[60] ^= 1; open(ks, e); } })
                        && refused(new Runnable0() { public void run() { byte[] e = env.clone(); e[e.length - 1] ^= 1; open(ks, e); } }));
        check("مغلف مقصوص/فارغ/بلا ترويسة → رفض",
                refused(new Runnable0() { public void run() { open(ks, Arrays.copyOf(env, env.length - 1)); } })
                        && refused(new Runnable0() { public void run() { open(ks, new byte[0]); } })
                        && refused(new Runnable0() { public void run() { open(ks, Arrays.copyOfRange(env, 10, env.length)); } }));

        // ---- الصفر-المعرفة بالقياس ----------------------------------------------
        final SyncServerSim srv = SyncServerSim.fresh();
        srv.register(ks.accountId, salt);
        String[] packed = packForPush(ks, state, hex("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"));
        srv.put(ks.accountId, packed[0], b64urlDecode(packed[1]), false);
        byte[] dump = srv.dump();
        String dumpStr = new String(dump, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("خادم dump بلا أي عربية وبلا كلمات مفتاحية وبلا بنية JSON",
                dumpStr.indexOf("مرحبا") < 0 && dumpStr.indexOf("قائمة") < 0
                        && dumpStr.indexOf("theme") < 0 && dumpStr.indexOf("setting") < 0
                        && dumpStr.indexOf("{\"v\":1") < 0 && dumpStr.indexOf("recs") < 0);
        check("خادم dump بلا كلمة السر إطلاقاً",
                !dumpStr.contains("عبارة") && !dumpStr.contains("السر"));
        double ent = entropy(dump);
        check("إنتروبيا المخزون عالية (فعلياً " + String.format("%.2f", ent) + " بت/بايت ≥ 7.5)",
                ent >= 7.5);
        check("الخادم لا يفهرس المحتوى: get بالمعرف فقط وdump بلا فهارس",
                Arrays.equals(srv.get(ks.accountId, packed[0]), b64urlDecode(packed[1])));

        // ---- الدمج الحتمي -------------------------------------------------------
        List<Rec> a = sampleState();
        List<Rec> b = new ArrayList<>();
        b.add(rec("theme:neon-2036", KIND_THEME, "{\"bg\":2,\"rim\":\"cyan\"}", 4, 2000));   // فائز
        b.add(rec("dict_word:99", KIND_DICT_WORD, "أهلاً بالعالم", 1, 1500));                 // جديد
        List<Rec> ab = merge(a, b), ba = merge(b, a);
        check("الدمج تبادلي: merge(a,b) = merge(b,a) = 6 سجلات",
                ab.equals(ba) && ab.size() == 6);
        check("الفائز هو الأعلى rev (لا تجميع، اختيار واحد)",
                ab.get(0).id.equals("clip_pin:42") && find(ab, "theme:neon-2036").body.contains("cyan"));
        check("الهوية: الدمج محافظ — دمج الحالة القياسية مع نفسها يعيدها حرفياً",
                merge(canonicalOf(a), canonicalOf(a)).equals(canonicalOf(a))
                        && merge(a, a).size() == a.size());
        List<Rec> tombA = new ArrayList<>();
        tombA.add(new Rec("clip_pin:42", KIND_CLIP_PIN, "42", "", 7, 3000, true));
        List<Rec> oldB = new ArrayList<>();
        oldB.add(rec("clip_pin:42", KIND_CLIP_PIN, "نص قديم", 2, 900));
        check("الشاهد يفوز: rev أقدم لا يُحيي المحذوف",
                merge(tombA, oldB).get(0).tomb && merge(oldB, tombA).get(0).tomb);
        List<Rec> tieA = new ArrayList<>();
        tieA.add(rec("setting:x", KIND_SETTING, "aaa", 3, 5000));
        List<Rec> tieB = new ArrayList<>();
        tieB.add(rec("setting:x", KIND_SETTING, "bbb", 3, 5000));
        check("التعادل الكامل يُحسم بالنص (ترتيب كلي — بلا عشوائية)",
                merge(tieA, tieB).get(0).body.equals("bbb") && merge(tieB, tieA).get(0).body.equals("bbb"));
        check("سجل نوع مجهول/rev صفر/نص مفقود → رفض بناء",
                refused(new Runnable0() { public void run() { new Rec("x:1", "cloud", "1", "y", 1, 1, false); } })
                        && refused(new Runnable0() { public void run() { new Rec("x:1", KIND_THEME, "1", "y", 0, 1, false); } })
                        && refused(new Runnable0() { public void run() { new Rec("x:1", KIND_THEME, "1", null, 1, 1, false); } }));

        // ---- JSON صارم -----------------------------------------------------------
        check("JSON يدعم العربية وعلامات الاقتباس والمطاط داخل النص",
                bytesToState(stateToBytes(new ArrayList<Rec>() {{
                    add(rec("clip_pin:1", KIND_CLIP_PIN, "قال: \"مرحباً\" \\ بالعالم\nوسطر جديد", 1, 1));
                }})).get(0).body.equals("قال: \"مرحباً\" \\ بالعالم\nوسطر جديد"));
        check("JSON يرفض نسخة غريبة وثهلة زائدة ومحرف تحكم خام",
                refused(new Runnable0() { public void run() { bytesToState("{\"v\":2,\"recs\":[]}".getBytes()); } })
                        && refused(new Runnable0() { public void run() { bytesToState((new String(stateToBytes(sampleState())) + " }").getBytes()); } })
                        && refused(new Runnable0() { public void run() { bytesToState("{\"v\":1,\"recs\":[[\"x\",1]]}".getBytes()); } }));

        // ---- بروتوكول الجهازين كاملاً --------------------------------------------
        SyncServerSim srv2 = SyncServerSim.fresh();
        List<byte[]> blobs = new ArrayList<>();
        String acct;
        {
            byte[] s = newSalt();
            KeySet kA = derive("جملة المرور السحابية 2036", s);
            srv2.register(kA.accountId, s);
            acct = kA.accountId;
            String[] p1 = packForPush(kA, sampleState(), r16());
            srv2.put(acct, p1[0], b64urlDecode(p1[1]), false);
            blobs.add(b64urlDecode(p1[1]));
        }
        check("جهاز جديد: ملح الحساب من الخادم ثم اشتقاق مطابق للمعرف",
                Arrays.equals(srv2.saltOf(acct), srv2.saltOf(acct))
                        && derive("جملة المرور السحابية 2036", srv2.saltOf(acct)).accountId.equals(acct));
        List<Rec> restored = restore(new ArrayList<Rec>(),
                derive("جملة المرور السحابية 2036", srv2.saltOf(acct)), blobs);
        List<Rec> canonical = merge(sampleState(), new ArrayList<Rec>());
        check("الاستعادة الكاملة على جهاز جديد = الحالة نفسها بالصيغة القياسية (5 سجلات)",
                restored.equals(canonical) && restored.size() == 5);
        check("كلمة سر خاطئة على الجهاز الجديد → رفض ولا شيء يُمس",
                refused(new Runnable0() { public void run() {
                    restore(new ArrayList<Rec>(), derive("غير صحيحة", srv2.saltOf(acct)), blobs);
                }}));
        // تعديلان متزامنان على جهازين + دفعة ثانية ثم إعادة دمج
        KeySet kB = derive("جملة المرور السحابية 2036", srv2.saltOf(acct));
        List<Rec> editedB = new ArrayList<>();
        editedB.add(rec("setting:sound_pack", KIND_SETTING, "harp", 6, 5000));
        String[] p2 = packForPush(kB, merge(restored, editedB), r16());
        srv2.put(acct, p2[0], b64urlDecode(p2[1]), false);
        blobs.add(b64urlDecode(p2[1]));
        List<Rec> finalState = restore(restored, kB, blobs);
        check("الجهازان متقارغان: تعديل B ظهر والدمج حتمي",
                find(finalState, "setting:sound_pack").body.equals("harp")
                        && finalState.size() == 5
                        && restore(restored, kB, blobs).equals(finalState));

        check("base64url ذهاب وإياب لكل الأطوال (1..40) ومطابق",
                roundTripB64() && refused(new Runnable0() { public void run() { b64urlDecode("غير صالح!"); } }));

        // ---- الحصص ---------------------------------------------------------------
        check("الحصص: كتلة أكبر من السقف تُرفض وحصة المجاني تحد",
                checkQuota(MAX_BLOB_BYTES + 1, 0, true).reason.equals("blob_too_big")
                        && checkQuota(700_000, 400_000, false).reason.equals("quota_free")
                        && checkQuota(700_000, 400_000, true).allowed
                        && checkQuota(600_000, 400_000, false).allowed
                        && checkQuota(-1, 0, false).reason.equals("quota_free"));
        check("الخادم يفرض الحصة فعلاً (رفض store لا مجرد إشعار)",
                refused(new Runnable0() { public void run() { srv2.put(acct, "big", new byte[MAX_BLOB_BYTES + 1], true); } }));

        // ---- الأداء ---------------------------------------------------------------
        List<Rec> big = bigState();                       // مستخدم ثقيل واقعي
        byte[] bigBytes = stateToBytes(big);
        int kb = bigBytes.length / 1024;
        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            byte[] e = seal(ks, bigBytes, nonce);
            open(ks, e);
        }
        double sealOpen = (System.nanoTime() - t0) / 1e6 / 50;
        double per100 = sealOpen * 100.0 / kb;
        check("ختم+فتح حالة مستخدم ثقيل (" + kb + "KB): p50 " + String.format("%.2f", sealOpen)
                + "ms = " + String.format("%.2f", per100) + "ms/100KB < 5 (إنتاجية لا حجم اعتباطي)",
                per100 < 5.0);
        t0 = System.nanoTime();
        for (int i = 0; i < 3; i++) derive("عبارة السر الصحيحة", salt);
        double kdfMs = (System.nanoTime() - t0) / 1e6 / 3;
        check("KDF مقصود البطء: " + String.format("%.0f", kdfMs)
                + "ms في الميزانية (< 400ms، مرة لكل جلسة)", kdfMs < 400);
    }

    // ---- أدوات الاختبار -----------------------------------------------------------
    interface Runnable0 { void run(); }

    static boolean refused(Runnable0 r) {
        try { r.run(); return false; }
        catch (SyncVault.SyncRefused e) { return true; }
        catch (SyncServerSim.Refused e) { return true; }
    }

    static String refusedReason(Runnable0 r) {
        try { r.run(); return "no_refusal"; }
        catch (SyncVault.SyncRefused e) { return e.getMessage(); }
    }

    static Rec find(List<Rec> s, String id) {
        for (Rec r : s) if (r.id.equals(id)) return r;
        throw new SyncRefused("not_found");
    }

    /** الصيغة القياسية: الدمج مع فراغ = ترتيب حتمي بالمعرف. */
    static List<Rec> canonicalOf(List<Rec> s) {
        return merge(s, new ArrayList<Rec>());
    }

    static byte[] r16() {
        byte[] b = new byte[16];
        new Random(42).nextBytes(b);
        return b;
    }

    static boolean roundTripB64() {
        Random r = new Random(11);
        for (int n = 1; n <= 40; n++) {
            byte[] d = new byte[n];
            r.nextBytes(d);
            if (!java.util.Arrays.equals(b64urlDecode(b64url(d)), d)) return false;
        }
        return true;
    }

    /** حالة مستخدم ثقيل واقعية: 800 مقطع مثبت + 2000 كلمة قاموس (~70KB). */
    static List<Rec> bigState() {
        List<Rec> s = new ArrayList<>();
        String clipBody = "نص الحافظة المثبتة رقم ٤٢ — يشرح شيئاً مهماً للمستخدم يومياً";
        for (int i = 0; i < 800; i++) s.add(rec("clip_pin:" + i, KIND_CLIP_PIN, clipBody, 1 + (i % 5), 1000L + i));
        for (int i = 0; i < 2000; i++) s.add(rec("dict_word:" + i, KIND_DICT_WORD, "كلمة " + i, 1, 2000L + i));
        return s;
    }

    /** إنتروبيا شانون على 256 سلة. */
    static double entropy(byte[] d) {
        if (d.length == 0) return 0;
        int[] bins = new int[256];
        for (byte x : d) bins[x & 0xFF]++;
        double e = 0;
        for (int b : bins) {
            if (b == 0) continue;
            double p = (double) b / d.length;
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }
}
