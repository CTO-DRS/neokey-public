package com.hark.neokey;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * اختبارات دبابيس SPKI — المرحلة 5، الأسبوع 1 (الأسبوع 26).
 *
 * الوحدة النقية قبل السلك: البناء صارم (فارغ/طول/سداسي/تكرار)، pinOf حتمي
 * (نفس DER = نفس البصمة)، verify يفرّق المثبت من غيره ومن الملخّص المقلوب،
 * والبصمة المرساة للشهادة المثبتة في tests/tls/arena-server.p12 تعاد حرفياً
 * («المرجع = المنشور»: أي تبديل ملف الشهادة يكسر المرساة فوراً).
 */
public class SpkiPinsTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static String tag(Runnable r) {
        try { r.run(); return "NO_REFUSAL"; }
        catch (SyncVault.SyncRefused e) { return e.getMessage(); }
    }

    /** sha256 على DER المفتاح العمومي لشهادة المرساة داخل مخزن اختبار —
     *  اسم مستعار غائب = قيمة مميزة لا انهيار: قلبا موثوقاً حتى مع ملف مثبت
     *  تم تبديله (الإثبات السلبي يعض بالسطر الحرفي لا بانهيار). */
    static String pinOfCert(String p12, String pass, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.FileInputStream(p12)) {
            ks.load(in, pass.toCharArray());
        }
        Certificate c = ks.getCertificate(alias);
        return c == null ? "NULL_ALIAS:" + alias : SpkiPins.pinOf(c.getPublicKey().getEncoded());
    }

    public static void main(String[] args) throws Exception {
        String good = pinOfCert("tests/tls/arena-server.p12", "nk-arena", "nkarena");
        String rogue = pinOfCert("tests/tls/rogue-server.p12", "nk-arena", "nkrogue");

        // 0) المرساة أولاً: بصمة الشهادة المنشورة تعاد حرفياً — أول حاجز يعض
        //    عند أي تبديل لملف الشهادة، قبل أي بناء يعتمد على قيم الملف
        check("anchor.arena.pin", TlsGate.EXPECTED_ARENA_PIN.equals(good));

        // 1) pinOf حتمي ومحرف-64
        check("pinof.64hex", good.length() == 64 && rogue.length() == 64);
        check("pinof.deterministic", good.equals(pinOfCert("tests/tls/arena-server.p12", "nk-arena", "nkarena")));
        check("pinof.distinct", !good.equals(rogue));

        // 2) البناء الصارم — الفشل المغلق بأوسمه حرفية
        check("ctor.empty", tag(() -> new SpkiPins()).equals("no_pins"));
        check("ctor.null", tag(() -> new SpkiPins((String[]) null)).equals("no_pins"));
        check("ctor.short", tag(() -> new SpkiPins("abcd")).equals("bad_pin_hex"));
        check("ctor.notsixtyfour", tag(() -> new SpkiPins(good.substring(1))).equals("bad_pin_hex"));
        check("ctor.nonhex", tag(() -> new SpkiPins(good.substring(0, 63) + "g")).equals("bad_pin_hex"));
        check("ctor.dup", tag(() -> new SpkiPins(good, good)).equals("dup_pin"));
        check("ctor.ok", new SpkiPins(good).size() == 1);
        check("ctor.mixedcase.canonical", new SpkiPins(good.toUpperCase()).pins()[0].equals(good));
        check("ctor.multi", new SpkiPins(good, rogue).size() == 2);

        // 3) verify — المثبت يمر، غير المثبت يرفض، الملخص المقلوب يرفض
        SpkiPins pins = new SpkiPins(good);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.FileInputStream("tests/tls/arena-server.p12")) {
            ks.load(in, "nk-arena".toCharArray());
        }
        byte[] spkiGood = ((X509Certificate) ks.getCertificate("nkarena")).getPublicKey().getEncoded();
        byte[] spkiRogue = certBytes("tests/tls/rogue-server.p12");
        check("verify.good", pins.verify(spkiGood));
        check("verify.rogue", !pins.verify(spkiRogue));
        byte[] flipped = spkiGood.clone();
        flipped[0] ^= (byte) 0xFF;
        check("verify.tampered", !pins.verify(flipped));
        check("verify.null", !pins.verify(null));
        check("verify.empty", !pins.verify(new byte[0]));
        check("pins.hexform", pins.pins()[0].equals(good));

        // 4) المرساة معاد التحقق بجوار الوحدة الكاملة
        check("anchor.arena.pin.again", TlsGate.EXPECTED_ARENA_PIN.equals(good));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static byte[] certBytes(String res) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.FileInputStream(res)) {
            ks.load(in, "nk-arena".toCharArray());
        }
        return ks.getCertificate(ks.aliases().nextElement()).getPublicKey().getEncoded();
    }
}
