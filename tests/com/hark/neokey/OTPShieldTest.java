package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * بطارية W65 — OTPShieldTest: كل قانون اختباران موجب/سلبي على الأقل،
 * والمجموعة المرجعية assets/testset_otp.txt هي حصى الغلاف (الدقة 100%).
 * نقي JVM — يجري داخل tests/run.sh وبوابة ci/otp_gate.sh.
 */
public class OTPShieldTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) throws Exception {
        // ---- المجموعة المرجعية: حصى الغلاف — كل صف بحكمه ------------------------
        List<String> rows = Files.readAllLines(
                new File("assets/testset_otp.txt").toPath(), StandardCharsets.UTF_8);
        int rowsN = 0, hit = 0;
        for (String line : rows) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 2);
            if (p.length != 2) continue;
            rowsN++;
            boolean want = p[0].equals("otp");
            if (OTPShield.isOtp(p[1]) == want) hit++;
        }
        check("reference: المجموعة المرجعية كبيرة بما يكفي (" + rowsN + " صف ≥ 20)",
                rowsN >= 20);
        check("reference: الدقة 100% — الغلاف بالبايت (" + hit + "/" + rowsN + ")",
                hit == rowsN);

        // ---- الأنماط الموثقة: الامتداد الدقيق ----------------------------------
        check("span: المتتالي البسيط", "123456".equals(OTPShield.findOtp("123456")));
        check("span: نمط جوجل G- يُكتشف بعد الإصلاح",
                "482913".equals(OTPShield.findOtp("G-482913")));
        check("span: تجزيء 3+3 يُعاد بالفاصل كما يظهر",
                "482-913".equals(OTPShield.findOtp("رمزك 482-913")));
        check("span: تجزيء 3+3+3 جشع كامل",
                "123-456-789".equals(OTPShield.findOtp("الرمز: 123-456-789")));
        check("span: أول مطابقة تُعاد (نص متعدد الأرقام)",
                "482913".equals(OTPShield.findOtp("رمز 482913 والانتهاء 10 دقائق")));

        // ---- المستثنيات الموثقة: لا تعميم خارج الغلاف --------------------------
        check("excl: السنة 2026 لا تُكتشف", !OTPShield.isOtp("سنة التأسيس 2026"));
        check("excl: التاريخ 31-12 لا يُكتشف (تجزيء ثنائي ممنوع)",
                !OTPShield.isOtp("حسابك ينتهي 31-12"));
        check("excl: التاريخ الكامل 2026-12-31 لا يُكتشف",
                !OTPShield.isOtp("موعد الاجتماع 2026-12-31"));
        check("excl: الهاتف 01234567890 (11 متتالياً) لا يُكتشف",
                !OTPShield.isOtp("رقم الهاتف 01234567890"));
        check("excl: الأرقام العربية-الهندية خارج الغلاف (ASCII فقط)",
                !OTPShield.isOtp("الرمز ٤٨٢٩١٣"));
        check("excl: null والفارغ بأمان", !OTPShield.isOtp(null) && !OTPShield.isOtp(""));

        // ---- العداد الحتمي والمهلة المقيدة --------------------------------------
        check("expiry: الحساب الحتمي بالبايت (capturedAt + مهلة·1000)",
                OTPShield.expiryAt(1000L, 60) == 61000L
                        && OTPShield.expiryAt(1731500000000L, 60) == 1731500060000L);
        check("clamp: تحت الحد يرجع الافتراضي 60", OTPShield.clampTimeout(10) == 60);
        check("clamp: فوق الحد يقف عند 600", OTPShield.clampTimeout(9999) == 600);
        check("clamp: الحدان يمران (30 و600)",
                OTPShield.clampTimeout(30) == 30 && OTPShield.clampTimeout(600) == 600);

        // ---- المسح الذاتي الموثق: قبل المهلة لا يُمسّ شيء ------------------------
        check("due: قبل المهلة لا يُمسّ", !OTPShield.due("123456", 1000L, 60999L, 60, false, true));
        check("due: عند المهلة بالضبط يُمسّ", OTPShield.due("123456", 1000L, 61000L, 60, false, true));
        check("due: بعد المهلة يُمسّ", OTPShield.due("123456", 1000L, 999999L, 60, false, true));
        check("due: النص غير الرمزي لا يُمسّ أبداً حتى بعد المهلة",
                !OTPShield.due("طلب عادي 12345", 1000L, 999999L, 60, false, true));

        // ---- قانون التخفي والإعداد (القانون الثنائي) -----------------------------
        check("incog: التخفي يمنع الدرع كلياً حتى مع الإعداد يعمل",
                !OTPShield.isOtpAllowed(true, true));
        check("incog: التخفي يمنع المسح حتى للرمز الصريح",
                !OTPShield.due("123456", 1000L, 999999L, 60, true, true));
        check("pref: الإعداد مغلق يمنع الدرع حتى بلا تخفي",
                !OTPShield.isOtpAllowed(false, false)
                        && !OTPShield.due("123456", 1000L, 999999L, 60, false, false));
        check("pref: الإعداد يعمل والتخفي مفتوح = الدرع حي",
                OTPShield.isOtpAllowed(false, true));

        // ---- سطر التدقيق: أوسام وأرقام فقط ---------------------------------------
        String a = OTPShield.auditLine("AUTO_WIPE", 60, 1731500000000L);
        check("audit: البنية الثابتة بلا محتوى الرمز",
                a.startsWith("OTPSHIELD\tAUTO_WIPE\ttimeout=60\t") && !a.contains("123456"));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
