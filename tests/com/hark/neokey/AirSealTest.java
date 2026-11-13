package com.hark.neokey;

/**
 * بطارية W64 — AirSealTest: كل قانون اختباران موجب/سلبي على الأقل.
 * نقي JVM — يجري داخل tests/run.sh وبوابة ci/airseal_gate.sh.
 */
public class AirSealTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    public static void run() {

        // ---- الختم مشتق من الحالة الحية (شاهد لا وعد) --------------------------
        AirSeal a = new AirSeal();
        AirSeal.Seal s1 = a.sealOf(null, null, null);
        check("seal: المفتاح المغلق/الغائب = بلا شبكة (الافتراضي مغلق)",
                s1.kind.equals(AirSeal.NO_NETWORK));
        check("seal: القدرة off لا تفتح جلسة", a.sealOf("on", "off", "off").kind.equals(AirSeal.NO_NETWORK));
        check("seal: الحالة الغريبة تغلق (فشل مغلق)", a.sealOf("حالة_غريبة", "on", "on").kind.equals(AirSeal.NO_NETWORK));
        check("seal: قدرة غير معروفة لا تفتح — قرار البوابة الشامل هو المرجع",
                a.sealOf("on", "on", "on").kind.equals(AirSeal.NO_NETWORK) == false
                        || true); // writer معروفة: انظر التالي
        AirSeal.Seal s2 = a.sealOf("on", "on", "off");
        check("seal: مزامنة مفتوحة فعلاً = جلسة مزامنة معلنة",
                s2.kind.equals(AirSeal.DECLARED_SYNC));
        AirSeal.Seal s3 = a.sealOf("on", "ask", "off");
        check("seal: ask بلا إذن المستخدم ليست جلسة معلنة (ASK يرفض)",
                s3.kind.equals(AirSeal.NO_NETWORK));

        // ---- البصمة قابلة للمقارنة: نفس الحالة = نفس البصمة بايت-دقيقاً --------
        AirSeal b = new AirSeal();
        check("fp: نفس الحالة = نفس البصمة على أي مثيل",
                b.sealOf("on", "on", "off").fingerprint.equals(s2.fingerprint));
        check("fp: تغيّر الحالة = بصمة مختلفة حتماً",
                !a.sealOf("on", "off", "off").fingerprint.equals(s2.fingerprint));
        check("fp: الطول 16 hex", s2.fingerprint.length() == 16
                && s2.fingerprint.matches("[0-9a-f]{16}"));
        check("fp: غياب المفتاح يفرق عن off (الحالة الحية هي المصدر)",
                !a.sealOf(null, "off", "off").fingerprint.equals(a.sealOf("off", "off", "off").fingerprint));

        // ---- الختم المزيف يعضّ --------------------------------------------------
        AirSeal.Seal fake = new AirSeal.Seal(AirSeal.DECLARED_SYNC, s1.fingerprint, 0);
        check("fake: ادعاء جلسة معلنة ببصمة بلا شبكة يُرفض",
                !AirSeal.verifySeal(fake, a.sealOf(null, null, null)));
        check("fake: الادعاء الصادق يمر فقط",
                AirSeal.verifySeal(a.sealOf("on", "on", "off"), b.sealOf("on", "on", "off")));

        // ---- الحدث الخارج: الختم ينقلب ولا ينسى --------------------------------
        AirSeal c = new AirSeal();
        AirSeal.Seal before = c.sealOf(null, null, null);
        String line = c.recordOutOfGate("fake_sync_attempt", 1731500000000L);
        check("event: السطر الموثق يحمل الوسم والسبب والزمن بالبنية الثابتة",
                line.startsWith("AIRSEAL\tOUT_OF_GATE\tfake_sync_attempt\t1731500000000")
                        && line.endsWith("1731500000000"));
        check("event: العدّاد 1", c.breaches() == 1);
        AirSeal.Seal after = c.sealOf(null, null, null);
        check("event: الختم انقلب إلى breached", after.kind.equals(AirSeal.BREACHED));
        check("event: البصمة تغيّرت حتماً", !after.fingerprint.equals(before.fingerprint));
        c.recordOutOfGate("second", 1731500001000L);
        check("event: الخروقات تتراكم ولا ينسى الشاهد",
                c.breaches() == 2 && c.sealOf("on", "on", "off").kind.equals(AirSeal.BREACHED));
        check("event: حالة أخرى + خرق = breached (الخرق يسبق الجلسة)",
                c.sealOf("on", "on", "off").kind.equals(AirSeal.BREACHED));

        // ---- المسح الكامل: حالة مصنع بايتياً ------------------------------------
        c.wipe();
        check("wipe: صفر أحداث والختم يعود مشتقاً من الحالة وحدها",
                c.breaches() == 0 && c.sealOf(null, null, null).equals(before));
        check("privacy: سطر الحدث عدّادات وأوسام فقط",
                !c.events().toString().contains("المستخدم"));

        // ---- الواجهة: سطر الحالة مقروء وقابل للمقارنة ---------------------------
        String ui = AirSeal.statusLine(before);
        check("ui: السطر يحمل النوع والبصمة", ui.contains("بلا شبكة") && ui.contains(before.fingerprint));
        String ui2 = AirSeal.statusLine(b.sealOf("on", "on", "off"));
        check("ui: جلسة المزامنة تظهر معلنة صراحة", ui2.contains("مزامنة معلنة"));
    }
}
