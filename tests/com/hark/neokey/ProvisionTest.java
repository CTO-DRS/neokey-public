package com.hark.neokey;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * اختبارات وحدة إجراء التجهيز — المرحلة 6، الأسبوع 1 (الأسبوع 30).
 *
 * مصفوفة الأوسمة الحرفية + حتمية الاشتقاق + بيان التجهيز — على المخازن
 * الملتزمة في tests/provision/ (بدائل التدريب، كلماتها موثقة عمداً).
 * هذه المجموعة داخل tests/ (صفر dex بتصميم) وتسجل نفسها في tests/run.sh.
 */
public final class ProvisionTest {

    static int passed = 0, failed = 0;
    static final char[] PASS = "prov-pass-2026".toCharArray();
    static final char[] ARENA_PASS = "nk-arena".toCharArray();
    static final String DIR = "tests/provision";

    public static void main(String[] args) throws Exception {
        // المرساة: مخزن الحلبة المنشور يعيد الدبوس المثبت حرفياً (المرجع = المنشور)
        eq("arena.pin.anchor", pinOfArena(), TlsGate.EXPECTED_ARENA_PIN);

        // الاشتقاق حتمي: تشغيلان = نفس الدبوس بايتاً
        Provision.Result r1 = ok(DIR + "/server.p12", null);
        Provision.Result r2 = ok(DIR + "/server.p12", null);
        eq("ref.pin.deterministic", r1.pin, r2.pin);
        eq("ref.pin16", r1.pin16(), r1.pin.substring(0, 16));

        // التجديد بنفس المفتاح = الدبوس نفسه (الشفافية)؛ الدوران = دبوس مختلف
        eq("rotation.renew.same.pin", ok(DIR + "/renew.p12", null).pin, r1.pin);
        ne("rotation.rotate.diff.pin", ok(DIR + "/rotate.p12", null).pin, r1.pin);

        // سطر الضبط الحرفي — الإجراء الواحد عند النشر
        eq("emitted.literal", r1.emittedLine(), "NetGate.setSyncPins(\"" + r1.pin + "\")");

        // بيان التجهيز: بنيته + حتميته (نفس المدخلات = نفس البيان بايتاً)
        String s1 = Provision.statement(r1, "2026-11-05T00:00:00Z");
        ok("stmt.schema", s1.contains("\"schema\": \"neokey-provision-1\""));
        ok("stmt.pin.and.emitted", s1.contains("\"pin\": \"" + r1.pin + "\"")
                && s1.contains("NetGate.setSyncPins(\\\"" + r1.pin + "\\\")"));
        eq("stmt.deterministic", Provision.statement(r2, "2026-11-05T00:00:00Z"), s1);

        // السلسلة المرجعية: [leaf, CA] مع قرار CA صريح
        eq("ref.chain.length", String.valueOf(r1.chainLen), "2");
        eq("ref.self.signed", String.valueOf(r1.selfSigned), "false");

        // مصفوفة الأوسمة الحرفية — كل فحص يعضّ بوسمه
        eqTag("neg.chain_missing", ProvisionTest.missing());
        eqTag("neg.chain_type", ProvisionTest.corrupt());
        eqTag("neg.unreadable_chain", ProvisionTest.wrongPass());
        eqTag("neg.san_mismatch", ProvisionTest.wrongDom());
        eqTag("neg.validity_expired", ProvisionTest.expired());
        eqTag("neg.ca_mismatch", ProvisionTest.foreign());
        eqTag("neg.no_ca_anchor", ProvisionTest.selfSignedImplicit());
        eqTag("neg.bad_pin_hex", ProvisionTest.badPinHex());
        eqTag("neg.pin_mismatch", ProvisionTest.wrongPin());

        // جرد الفحوص الحرفي — نفس ترتيب القرار (19 وسم رفض)
        eq("checknames.count", String.valueOf(Provision.checkNames().size()), "19");

        System.out.println("RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- مساعدات -----------------------------------------------------------------

    static Provision.Result ok(String ks, String expectedPin) throws Exception {
        return Provision.provision(ks, PASS, "sync", "sync.neokey.test", expectedPin, false, caDer());
    }

    static byte[] caDer() throws Exception {
        KeyStore ca = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(DIR + "/ca.p12")) { ca.load(in, PASS); }
        return ca.getCertificate("nk-ca").getEncoded();
    }

    static String pinOfArena() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream("tests/tls/arena-server.p12")) { ks.load(in, ARENA_PASS); }
        return Provision.provision(ks, "nkarena", "127.0.0.1", TlsGate.EXPECTED_ARENA_PIN, true).pin;
    }

    static String missing() { return tag(() -> Provision.provision(DIR + "/does-not-exist.p12", PASS, "sync", "sync.neokey.test", null, false)); }

    static String corrupt() {
        String tag;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(DIR + "/server.p12"));
            bytes[0] = 0x00; bytes[1] = 0x01; bytes[2] = 0x02; bytes[3] = 0x03;
            java.nio.file.Path bad = java.nio.file.Paths.get("build/provision-test-corrupt.p12");
            java.nio.file.Files.createDirectories(bad.getParent());
            java.nio.file.Files.write(bad, bytes);
            tag = tag(() -> Provision.provision(bad.toString(), PASS, "sync", "sync.neokey.test", null, false));
        } catch (Exception e) { tag = "setup_" + e.getClass().getSimpleName(); }
        return tag;
    }

    static String wrongPass() {
        return tag(() -> Provision.provision(DIR + "/server.p12", "nope".toCharArray(), "sync", "sync.neokey.test", null, false));
    }

    static String wrongDom() { return tag(() -> Provision.provision(DIR + "/wrongdom.p12", PASS, "sync", "sync.neokey.test", null, false)); }

    static String expired() { return tag(() -> Provision.provision(DIR + "/expired-leaf.p12", PASS, "sync", "sync.neokey.test", null, false)); }

    static String foreign() {
        String t = "setup";
        try {
            final byte[] ca = caDer();
            t = tag(() -> Provision.provision(DIR + "/foreign.p12", PASS, "sync", "sync.neokey.test", null, false, ca));
        } catch (Exception e) { t = "setup_" + e.getClass().getSimpleName(); }
        return t;
    }

    static String selfSignedImplicit() {
        String t;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream("tests/tls/arena-server.p12")) { ks.load(in, ARENA_PASS); }
            final KeyStore k = ks;
            t = tag(() -> Provision.provision(k, "nkarena", "127.0.0.1", null, false));
        } catch (Exception e) { t = "setup_" + e.getClass().getSimpleName(); }
        return t;
    }

    static String badPinHex() { return tag(() -> Provision.provision(DIR + "/server.p12", PASS, "sync", "sync.neokey.test", "zz-not-hex", false)); }

    static String wrongPin() {
        return tag(() -> Provision.provision(DIR + "/server.p12", PASS, "sync", "sync.neokey.test",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", false));
    }

    static String tag(Runnable r) {
        try { r.run(); return "ACCEPT"; }
        catch (Provision.Refused re) { return re.tag; }
        catch (Exception e) { return "exception:" + e.getClass().getSimpleName(); }
    }

    static void eq(String name, String actual, String want) {
        if (want.equals(actual)) passed++;
        else { failed++; System.out.println("FAIL " + name + ": want=" + want + " got=" + actual); }
    }

    static void ne(String name, String a, String b) {
        if (!a.equals(b)) passed++;
        else { failed++; System.out.println("FAIL " + name + ": values equal but must differ"); }
    }

    static void ok(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("FAIL " + name); }
    }

    static void eqTag(String name, String tag) { eq(name, tag, name.substring(4)); }

    private ProvisionTest() { }
}
