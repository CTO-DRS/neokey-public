package com.hark.neokey;

import java.util.List;

/**
 * بوابة W61 — StripGate: تقيس التسليم لا الادعاء (نمط v2 — تصرّ البطارية).
 * نقي JVM — تجري في أي مكان:
 *   1. البطارية StripAcceptTest كاملة (القانون الصادق للمصدر المجهول،
 *      المسح بايتي، جولة الصيغة) — وبعدادّادها الحقيقي (قانون الحادثة:
 *      لا ok(true) على بطارية).
 *   2. التغذية الموثقة لـ AdaptEngine: قبول رقاقة شخصية = +1 تعزيز فوق
 *      تعلم الكتابة — مقيس على عدّاد المحرك نفسه (1 → 2).
 *   3. حتمية العدّ: نفس التسلسل = نفس العدّادات (بلا ساعة ولا عرق).
 * الحكم: STRIPGATE_OK / STRIPGATE_FAILED <reason> (exit 1).
 * (p50 الضغط يقيسه ci/perfbaseline_gate.sh الطازج — السكربت يستدعيه ويشترطه.)
 */
public class StripGate {
    static int pass, fail;

    static void ok(boolean cond, String what) {
        if (cond) pass++;
        else fail++;
        System.out.println((cond ? "GATE-OK " : "GATE-FAIL ") + what);
    }

    public static void main(String[] args) {
        // ---- 1) البطارية الكاملة بعدّادها الحقيقي (قانون الحادثة) --------------
        StripAcceptTest.run();
        ok(StripAcceptTest.fail == 0, "battery StripAcceptTest green ("
                + StripAcceptTest.pass + " checks, " + StripAcceptTest.fail + " failed)");

        // ---- 2) التغذية الموثقة: القبول = +1 تعزيز فوق تعلم الكتابة ------------
        AdaptEngine e = new AdaptEngine();
        e.setToday(100);
        e.learn("العمري", 100);                       // تعلم الكتابة (finishWord)
        long before = e.entry("العمري").count;
        StripAccept acc = new StripAccept();
        acc.accept(StripAccept.PERSONAL);
        acc.feed(e, "العمري", 100);                   // التغذية عند القبول
        ok(before == 1 && e.entry("العمري").count == 2,
                "the documented acceptance feed: typing-learn 1 -> acceptance +1 = "
                        + e.entry("العمري").count);
        // والكلمة الفارغة/الخالية لا تغذي
        acc.feed(e, "", 100);
        acc.feed(null, "العمري", 100);
        ok(e.entry("العمري").count == 2, "the feed refuses empty/null without touching the count");

        // ---- 3) حتمية العدّ: نفس التسلسل = نفس العدّادات -----------------------
        StripAccept a1 = new StripAccept();
        StripAccept a2 = new StripAccept();
        String[] seq = {StripAccept.PERSONAL, StripAccept.ROOT, StripAccept.CLIP, StripAccept.STRIP,
                StripAccept.PERSONAL, "مجهول", StripAccept.STRIP};
        for (String s : seq) { a1.accept(s); a2.accept(s); }
        ok(a1.serialize().equals(a2.serialize()),
                "determinism: the same sequence serializes byte-identical (no clock, no race)");
        ok(a1.count(StripAccept.PERSONAL) == 2 && a1.count(StripAccept.STRIP) == 3
                && a1.count(StripAccept.ROOT) == 1 && a1.count(StripAccept.CLIP) == 1
                && a1.total() == 7,
                "the honest buckets: personal=2 strip=3 (2 explicit + the unknown) root=1 clip=1 total=7");

        System.out.println("STRIPGATE_OK checks=" + (pass + fail)
                + " sources=" + StripAccept.SOURCES.length + " feed=+1 determinism=OK");
        if (fail > 0) {
            System.out.println("STRIPGATE_FAILED " + fail + " gate checks");
            System.exit(1);
        }
    }
}
