package com.hark.neokey;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * بوابة إجراء التجهيز والدوران — المرحلة 6، الأسبوع 1 (الأسبوع 30).
 *
 * ترتيب الحواجز غير قابل للتفاوض (قاعدة W26):
 *   1. المرساة أول الحواجز — تشغيل Provision على مخزن الحلبة المنشور
 *      (tests/tls/arena-server.p12) يجب أن يعيد الدبوس المثبت حرفياً
 *      (TlsGate.EXPECTED_ARENA_PIN): «المرجع = المنشور» — تبديل الملف يكسر
 *      المرساة من أول تشغيل ولو كان البديل مخزناً سليماً تماماً.
 *   2. التجهيز المرجعي — server.p12 على النطاق المعلن: بيان التجهيز يُكتب،
 *      وسطر الضبط الحرفي يُصدر، والاتساق الثلاثي: دبوس القرار == دبوس
 *      البيان == إعادة الاشتقاق في البوابة نفسها (SpkiPins.pinOf).
 *   3. حتمية البيان — تشغيلان متتاليان: البيانان متطابقان بايتاً فيما عدا
 *      الوقت الحي (generatedAt) والأيام المتبقية (حدّ لا مساواة).
 *   4. تدريب الدوران — التجديد بنفس المفتاح: الدبوس نفسه (الشفافية)؛
 *      الدوران بمفتاح جديد: دبوس مختلف + إثبات على السلك: العميل القديم
 *      يُرفض داخل المصافحة (spki_not_pinned من TrustManager — لا بايت طلب
 *      يغادر) ثم العميل المعاد ضبطه يجول أخضر على الخادم الدائر.
 *   5. السلبيات — كل فحص يعضّ بوسمه الحرفي؛ فحص يقف عن العضّ = حارس ميت
 *      = فشل البوابة.
 *
 * Verdict: PROVISIONGATE_OK checks=N negatives=M/M anchor_pin16=… days_left=…
 *          / PROVISIONGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — صفر dex بتصميم.
 */
public final class ProvisionGate {

    /** المرساة: دبوس SPKI لمخزن الحلبة المنشور — حرفياً كما في TlsGate (W26). */
    public static final String EXPECTED_ARENA_PIN = TlsGate.EXPECTED_ARENA_PIN;
    /** المرساة الثانية: أول 16 محرفاً من دبوس مخزن النشر البديل الملتزم —
     *  «المرجع = المنشور»: تبديل الملف بسلسلة صالحة تماماً بمفتاح مغترب
     *  يُصطاد هنا لا عند أي فحص داخلي (الفحوص الداخلية متسقة مع نفسها). */
    public static final String EXPECTED_REF_PIN16 = "b1b209b32486cf49";
    static final char[] ARENA_PASS = "nk-arena".toCharArray();
    static final char[] PASS = "prov-pass-2026".toCharArray();
    static final String DIR = "tests/provision";

    static int passed = 0, failed = 0;
    static int NEG_OK = 0, NEG_TOTAL = 0;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    static void neg(String name, String expectedTag, String actualTag) {
        NEG_TOTAL++;
        if (expectedTag.equals(actualTag)) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name + " expected=" + expectedTag + " got=" + actualTag);
    }

    static String tagOf(Provision.Refused r) { return r.tag; }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // ---- 1: المرساة أول الحواجز («المرجع = المنشور») ----------------------
        Provision.Result arena = null;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream("tests/tls/arena-server.p12")) { ks.load(in, ARENA_PASS); }
            arena = Provision.provision(ks, "nkarena", "127.0.0.1", EXPECTED_ARENA_PIN, true);
        } catch (Provision.Refused r) { arena = null; }
        check("anchor.arena.pin", arena != null && arena.pin.equals(EXPECTED_ARENA_PIN));

        // المرساة تعضّ أيضاً في الاتجاه الآخر: دبوس متوقع مغترب = رفض pin_mismatch.
        String roguePin = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        String arenaRogueTag;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream("tests/tls/arena-server.p12")) { ks.load(in, ARENA_PASS); }
            Provision.provision(ks, "nkarena", "127.0.0.1", roguePin, true);
            arenaRogueTag = "ACCEPT";
        } catch (Provision.Refused r) { arenaRogueTag = tagOf(r); }
        neg("anchor.rogue.pin", "pin_mismatch", arenaRogueTag);

        // ---- 2: التجهيز المرجعي + الاتساق الثلاثي ------------------------------
        // CA المشغّل = مرساة السلسلة (نفس مبدأ «المرجع = المنشور» على مستوى CA):
        // قرار التجهيز نسبي لمرساة المشغّل، لا «أي سلسلة متسقة تُقبل».
        byte[] opCa = caCertDer();
        Provision.Result ref = null;
        String refTag = "";
        try { ref = Provision.provision(DIR + "/server.p12", PASS, "sync", "sync.neokey.test", null, false, opCa); }
        catch (Provision.Refused r) { refTag = tagOf(r); }
        check("ref.provision.ok", ref != null);
        check("ref.ca.of.operator", ref != null);   // نجاح التجهيز مع تثبيت CA = الفحص نفسه
        // المرساة الثانية («المرجع = المنشور»): دبوس المخزن الملتزم مثبت حرفياً —
        // الهجوم الوحيد الذي تجتاز كل الفحوص الداخلية (سلسلة صالحة تماماً بمفتاح
        // مغترب) يُصطاد بهذا الحرف لا بأي اتساق داخلي.
        check("ref.pin.published", ref != null && ref.pin16().equals(EXPECTED_REF_PIN16));
        if (ref != null) {
            // الاتساق الثلاثي: القرار == البيان == إعادة الاشتقاق في البوابة.
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(DIR + "/server.p12")) { ks.load(in, PASS); }
            X509Certificate leaf = (X509Certificate) ks.getCertificateChain("sync")[0];
            String recomputed = SpkiPins.pinOf(leaf.getPublicKey().getEncoded());
            check("ref.pin.threeway", ref.pin.equals(recomputed));
            // سطر الضبط الحرفي — الإجراء الواحد عند النشر.
            check("ref.emitted.literal", ref.emittedLine().equals(
                    "NetGate.setSyncPins(\"" + recomputed + "\")"));
            // حدود النشر: سلسلة [leaf, CA] + صلاحية فوق الأرضية + نطاق معلن.
            check("ref.chain.shape", ref.chainLen == 2 && !ref.selfSigned);
            check("ref.days.bound", ref.daysLeft >= Provision.MIN_DAYS_LEFT);
            check("ref.domain.match", "sync.neokey.test".equals(ref.domain));

            // ---- 3: بيان التجهيز — كتابة + بنيته + حتميته --------------------
            String at = "2026-11-05T00:00:00Z";       // وقت مثبت للحتمية
            String s1 = Provision.statement(ref, at);
            check("stmt.schema", s1.contains("\"schema\": \"neokey-provision-1\""));
            check("stmt.pin", s1.contains("\"pin\": \"" + ref.pin + "\"")
                    && s1.contains("\"pin16\": \"" + ref.pin16() + "\""));
            check("stmt.emitted", s1.contains("NetGate.setSyncPins(\\\"" + ref.pin + "\\\")")
                    || s1.contains(ref.emittedLine()));
            Provision.Result ref2 = Provision.provision(DIR + "/server.p12", PASS, "sync",
                    "sync.neokey.test", ref.pin, false, opCa);
            String s2 = Provision.statement(ref2, at);
            check("stmt.deterministic", s1.equals(s2));   // نفس المدخلات = نفس البيان بايتاً
            // الأيام المتبقية حدّ لا مساواة (ساعة حية):
            check("stmt.days.bound", ref2.daysLeft >= Provision.MIN_DAYS_LEFT);
        }

        // ---- 4: تدريب الدوران ---------------------------------------------------
        Provision.Result renew = null, rotate = null;
        String renewTag = "", rotateTag = "";
        try { renew = Provision.provision(DIR + "/renew.p12", PASS, "sync", "sync.neokey.test", ref == null ? null : ref.pin, false, opCa); }
        catch (Provision.Refused r) { renewTag = tagOf(r); }
        try { rotate = Provision.provision(DIR + "/rotate.p12", PASS, "sync", "sync.neokey.test", null, false, opCa); }
        catch (Provision.Refused r) { rotateTag = tagOf(r); }
        check("rotation.stable", renew != null && ref != null && renew.pin.equals(ref.pin));
        check("rotation.rotated", rotate != null && ref != null && !rotate.pin.equals(ref.pin));

        // إثبات الدوران على السلك: خادم دائر بمفتاح جديد + عميل الإنتاج المثبت.
        if (ref != null && rotate != null) {
            tests_sync_rotation(ref.pin, rotate.pin);
        }

        // ---- 5: السلبيات — كل فحص يعضّ بوسمه الحرفي ----------------------------
        // (أ) نطاق مغترب: SAN يقول other.neokey.test
        neg("neg.san_mismatch", "san_mismatch", runTag(DIR + "/wrongdom.p12", "sync.neokey.test", null, null));
        // (ب) منتهية الصلاحية: CA سليم، الشهادة انتهت
        neg("neg.validity_expired", "validity_expired", runTag(DIR + "/expired-leaf.p12", "sync.neokey.test", null, null));
        // (ج) سلسلة CA مغترب: موقعة من ca2 — قرار التجهيز نسبي لمرساة المشغّل
        //     (السلسلة متسقة داخلياً لكنها ليست CA المشغّل = ca_mismatch)
        neg("neg.ca_mismatch", "ca_mismatch", runTag(DIR + "/foreign.p12", "sync.neokey.test", null, opCa));
        // (د) ملف غائب
        neg("neg.chain_missing", "chain_missing", runTag(DIR + "/does-not-exist.p12", "sync.neokey.test", null, null));
        // (هـ) عبارة خاطئة = فشل مغلق
        String wrongPass;
        try {
            Provision.provision(DIR + "/server.p12", "wrong-pass".toCharArray(), "sync", "sync.neokey.test", null, false);
            wrongPass = "ACCEPT";
        } catch (Provision.Refused r) { wrongPass = tagOf(r); }
        neg("neg.unreadable_chain", "unreadable_chain", wrongPass);
        // (و) حاوية ليست PKCS12 — سحر بايتات مغترب (ملف مؤقت مبني هنا لا ملتزم)
        String corruptTag;
        try {
            java.nio.file.Path bad = Paths.get("build/provision-corrupt.p12");
            Files.createDirectories(bad.getParent());
            byte[] bytes = Files.readAllBytes(Paths.get(DIR + "/server.p12"));
            bytes[0] = 0x00; bytes[1] = 0x01; bytes[2] = 0x02; bytes[3] = 0x03;
            Files.write(bad, bytes);
            Provision.provision(bad.toString(), PASS, "sync", "sync.neokey.test", null, false);
            corruptTag = "ACCEPT";
        } catch (Provision.Refused r) { corruptTag = tagOf(r); }
        neg("neg.chain_type", "chain_type", corruptTag);
        // (ز) التوقيع الذاتي بلا قرار صريح — لا يمرص صمتاً
        String ssTag;
        try {
            Provision.provision("tests/tls/arena-server.p12", ARENA_PASS, "nkarena", "127.0.0.1", null, false);
            ssTag = "ACCEPT";
        } catch (Provision.Refused r) { ssTag = tagOf(r); }
        neg("neg.self_signed_explicit", "no_ca_anchor", ssTag);
        // (ح) دبوس متوقع مشوه — bad_pin_hex قبل أي مقارنة
        neg("neg.bad_pin_hex", "bad_pin_hex", runTag(DIR + "/server.p12", "sync.neokey.test", "zzzz", null));

        // ---- الحكم --------------------------------------------------------------
        long ms = System.currentTimeMillis() - t0;
        if (failed == 0 && NEG_OK == NEG_TOTAL && NEG_TOTAL >= 8) {
            // المرساة = أول 16 محرفاً من الدبوس المثبت حرفياً (e67e815c…) —
            // وبين16 المرجع = أول 16 محرفاً من دبوس مخزن النشر البديل.
            String anchor16 = arena != null ? arena.pin16()
                    : EXPECTED_ARENA_PIN.substring(0, 16);
            String pin16 = ref != null ? ref.pin16() : "none";
            long days = renew != null ? renew.daysLeft : (arena != null ? arena.daysLeft : -1);
            System.out.println("PROVISIONGATE_OK checks=" + passed + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                    + " anchor_pin16=" + anchor16 + " pin16=" + pin16 + " rotation=stable+rotate"
                    + " renew_days_left=" + days + " ms=" + ms);
            System.out.println("PROVISION_GATE=OK");
        } else {
            System.out.println("PROVISIONGATE_FAILED checks=" + passed + "/" + (passed + failed)
                    + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
    }

    /** جولة الدوران على السلك: خادم بدوران.p12 — القديم يُرفض داخل المصافحة،
     *  المعاد ضبطه يجول أخضر (عقد النشر نفسه هو المسبار). طبقتا الرفض كما في
     *  بوابة TLS: النقل يُغلق "network" بلا تفاصيل، والوسم الحرفي
     *  "spki_not_pinned" من داخل TrustManager (جلسة اتصال خام مثبتة). */
    static void tests_sync_rotation(String oldPin, String newPin) throws Exception {
        KeyStore ca = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(DIR + "/ca.p12")) { ca.load(in, PASS); }
        byte[] anchor = ca.getCertificate("nk-ca").getEncoded();
        SyncServer srv = SyncServer.startTlsAt("127.0.0.1", 0, DIR + "/rotate.p12", PASS);
        try {
            String base = srv.base();
            // (1) العميل القديم عبر النقل الإنتاجي: النقل يُغلق "network" بلا تفاصيل.
            String netTag = "none";
            try {
                SyncRestTransport old = new SyncRestTransport(base, new SpkiPins(oldPin), anchor);
                old.saltOf("rotate-old-device");
                netTag = "NO_EXCEPTION";
            } catch (SyncVault.SyncRefused r) { netTag = r.getMessage(); }
            neg("rotation.wire.transport.closes.as.network", "network", netTag);
            // (2) الوسم الحرفي من TrustManager: اتصال خام مثبت بالدبوس القديم.
            String refusedTag = "none";
            try {
                javax.net.ssl.HttpsURLConnection c = pinned(base + "account/rotate-old/salt", anchor, new SpkiPins(oldPin));
                c.getResponseCode();
                drain(c);
                refusedTag = "NO_EXCEPTION";
            } catch (javax.net.ssl.SSLException e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                refusedTag = root instanceof java.security.cert.CertificateException
                        ? root.getMessage() : "network:" + e.getClass().getSimpleName();
            } catch (Exception e) {
                refusedTag = e.getClass().getSimpleName();
            }
            neg("rotation.wire.old_pin_refused", "spki_not_pinned", refusedTag);
            // (3) العميل المعاد ضبطه بالدبوس الجديد: جولة عقد صغيرة خضراء —
            //     تسجيل الملح ثم قراءته من السلك (نفس شكل حسابات البوابة: 20 محرفاً).
            SyncRestTransport fresh = new SyncRestTransport(base, new SpkiPins(newPin), anchor);
            String acct = "ROTATEDDEVICE0000001";
            byte[] salt = new byte[SyncVault.SALT_LEN];
            new java.security.SecureRandom().nextBytes(salt);
            fresh.register(acct, salt);
            byte[] got = fresh.saltOf(acct);
            check("rotation.wire.repin.round", got != null && java.util.Arrays.equals(salt, got));
        } finally {
            srv.stop();
        }
    }

    static javax.net.ssl.HttpsURLConnection pinned(String url, byte[] anchorDer, SpkiPins pins) throws Exception {
        javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) new java.net.URL(url).openConnection();
        c.setSSLSocketFactory(PinnedTls.arenaContext(anchorDer, pins).getSocketFactory());
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        return c;
    }

    static void drain(javax.net.ssl.HttpsURLConnection c) {
        try { java.io.InputStream in = c.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0); in.close(); } catch (Exception ignore) {}
        c.disconnect();
    }

    /** شهادة CA التدريب DER — مرساة المشغّل في التدريب (نفس ملف CA الملتزم). */
    static byte[] caCertDer() throws Exception {
        KeyStore ca = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(DIR + "/ca.p12")) { ca.load(in, PASS); }
        return ca.getCertificate("nk-ca").getEncoded();
    }

    /** تشغيل القرار على ملف وإرجاع وسم الرفض (أو ACCEPT). */
    static String runTag(String ksPath, String domain, String expectedPin, byte[] expectedCa) {
        try {
            Provision.provision(ksPath, PASS, "sync", domain, expectedPin, false, expectedCa);
            return "ACCEPT";
        } catch (Provision.Refused r) { return tagOf(r); }
    }
}
