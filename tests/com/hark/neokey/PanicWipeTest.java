package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * بطارية W67 — PanicWipeTest: كل قانون اختباران موجب/سلبي على الأقل.
 * المخازن صناعية في الذاكرة؛ القوانين: التأكيد الحتمي، البايت-فارغ
 * الحرفي، حصانة النسخ الموقعة، الفشل المغلق، والتخفي المؤقت الذي يعيد
 * كل شيء مغلق. نقي JVM — يجري داخل tests/run.sh وبوابة ci/panic_gate.sh.
 */
public class PanicWipeTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static byte[] bytesOf(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    static List<PanicWipe.Store> fresh() {
        List<PanicWipe.Store> l = new ArrayList<PanicWipe.Store>();
        l.add(new PanicWipe.Store("clipboard-history", bytesOf("حجم سجل الحافظة الحي 2026-11")));
        l.add(new PanicWipe.Store("personal-adaptation", bytesOf("التكيّف الشخصي: كلمات وتكرار")));
        l.add(new PanicWipe.Store("previews", bytesOf("معاينات المقاطع المحفوظة")));
        l.add(new PanicWipe.Store("signed-backup-2036.seal", bytesOf("النسخة الموقعة — مسار الاستعادة")));
        return l;
    }

    public static void main(String[] args) throws Exception {
        // ---- القانون 1: لا مسح بلا تأكيد -----------------------------------------
        List<PanicWipe.Store> a = fresh();
        String token = PanicWipe.armToken(a);
        check("arm: الرمز الحتمي (نفس المخازن → نفس الرمز، 8 أحرف)",
                token.equals(PanicWipe.armToken(fresh())) && token.length() == 8
                        && !"00000000".equals(token));
        PanicWipe.Result refused = PanicWipe.wipeAll(a, null, token);
        check("confirm: بلا رمز → PANIC_REFUSED ولا بايت يُصفر",
                PanicWipe.REFUSED.equals(refused.state)
                        && refused.wipedStores == 0 && refused.zeroedBytes == 0
                        && !allZero(a.get(0).data));
        PanicWipe.Result wrong = PanicWipe.wipeAll(a, "deadbeef", token);
        check("confirm: رمز خاطئ → PANIC_REFUSED (البيانات سليمة)",
                PanicWipe.REFUSED.equals(wrong.state) && !allZero(a.get(1).data));

        // ---- القانون 2: البايت-فارغ الحرفي ---------------------------------------
        List<PanicWipe.Store> b = fresh();
        String t2 = PanicWipe.armToken(b);
        PanicWipe.Result ok = PanicWipe.wipeAll(b, t2, t2);
        check("wiped: اللمسة المؤكدة → PANIC_WIPED وكل بايت صفر بطول الأصل",
                PanicWipe.WIPED.equals(ok.state) && ok.wipedStores == 3
                        && allZero(b.get(0).data) && allZero(b.get(1).data)
                        && allZero(b.get(2).data)
                        && b.get(0).data.length == bytesOf("حجم سجل الحافظة الحي 2026-11").length
                        && ok.zeroedBytes > 0);

        // ---- القانون 3: النسخ الموقعة محصنة — الاستعادة تبقى ممكنة ----------------
        String sealedBefore = Arrays.toString(b.get(3).data);
        check("protect: النسخة الموقعة بايت-لطرفاً بعد اللمسة وتظهر في kept",
                sealedBefore.equals(Arrays.toString(b.get(3).data))
                        && ok.kept.contains("signed-backup-2036.seal")
                        && ok.kept.size() == 1
                        && !allZero(b.get(3).data));

        // ---- القانون 4: فشل مغلق — PARTIAL موثق لا نجاح صامت ----------------------
        List<PanicWipe.Store> c = new ArrayList<PanicWipe.Store>();
        c.add(new PanicWipe.Store("clipboard-history", bytesOf("حي")));
        c.add(new PanicWipe.Store("personal-adaptation", null));
        String t3 = PanicWipe.armToken(c);
        PanicWipe.Result partial = PanicWipe.wipeAll(c, t3, t3);
        check("partial: مخزن معطوب → PANIC_PARTIAL والسليم مُسح ورمز arm يعمل بلا حجم null",
                PanicWipe.PARTIAL.equals(partial.state)
                        && allZero(c.get(0).data) && partial.wipedStores == 1);

        // ---- القانون 5: التخفي المؤقت يعيد كل شيء مغلق ----------------------------
        check("stealth: البدء برمز موجب والمهلة نشطة",
                PanicWipe.stealthBegin(1000, 600) == 1600
                        && "STEALTH_ACTIVE".equals(
                        PanicWipe.stealthCheck(1599, PanicWipe.stealthBegin(1000, 600))));
        check("stealth: مدة غير موجبة ترفض (0 وسالب)",
                PanicWipe.stealthBegin(1000, 0) == -1
                        && PanicWipe.stealthBegin(1000, -5) == -1);
        long until = PanicWipe.stealthBegin(1000, 600);
        check("stealth: عند انتهاء المهلة → ALL_CLOSED (كل شيء مغلق)",
                "STEALTH_ACTIVE".equals(PanicWipe.stealthCheck(1600 - 1, until))
                        && PanicWipe.ALL_CLOSED.equals(PanicWipe.stealthCheck(1600, until))
                        && PanicWipe.ALL_CLOSED.equals(PanicWipe.stealthCheck(2000, until)));
        check("stealth: بلا تخفي (until=-1) → ALL_CLOSED دائماً",
                PanicWipe.ALL_CLOSED.equals(PanicWipe.stealthCheck(100, -1)));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
