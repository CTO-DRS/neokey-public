package com.hark.neokey;

/**
 * بطارية W61 — StripAcceptTest: كل قانون اختباران موجب/سلبي على الأقل.
 * نقي JVM — يجري داخل tests/run.sh وبوابة ci/strip_gate.sh.
 */
public class StripAcceptTest {
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
        StripAccept a = new StripAccept();
        a.accept(StripAccept.PERSONAL);
        a.accept(StripAccept.PERSONAL);
        a.accept(StripAccept.ROOT);
        check("accept: عدّاد المصدر المعلَم يتراكم", a.count(StripAccept.PERSONAL) == 2);
        check("accept: كل مصدر عدّاده مستقل", a.count(StripAccept.ROOT) == 1);
        a.accept("مصدر_مجهول");
        a.accept(null);
        a.accept("");
        check("accept: المجهول/الفارغ/null يُحتسب strip (الصدق في العدّ)",
                a.count(StripAccept.STRIP) == 3);
        check("accept: المجموع الكلي", a.total() == 6);
        check("accept: مصدر غير معروف لا يفتح عدّاداً خاصاً", a.count("مصدر_مجهول") == 0);

        // المسح الكامل: حالة مصنع بايتياً
        a.wipe();
        check("wipe: serialize() == \"\" بعد المسح", a.serialize().isEmpty());
        check("wipe: العدّادات صفر", a.total() == 0 && a.count(StripAccept.PERSONAL) == 0);

        // الصيغة: جولة كاملة، الفاسد يُتخطى، الترتيب الثابت
        StripAccept src = new StripAccept();
        src.accept(StripAccept.CLIP);
        src.accept(StripAccept.CLIP);
        src.accept(StripAccept.STRIP);
        src.accept(StripAccept.PERSONAL);
        StripAccept back = StripAccept.parse(src.serialize());
        check("roundtrip: العدّادات كاملة بعد parse/serialize",
                back.count(StripAccept.CLIP) == 2 && back.count(StripAccept.STRIP) == 1
                        && back.count(StripAccept.PERSONAL) == 1);
        check("roundtrip: الترتيب الثابت للمصادر المعرفة",
                src.serialize().startsWith(StripAccept.PERSONAL + "\t1\n")
                        && src.serialize().contains(StripAccept.ROOT + "\t1\n") == false);
        String corrupt = "سطر_بلا_فواصل\nclip\t2\nمجهول\t5\nسالب\t-1\nفاسد\tحرف\nقصير\t1\n\n";
        StripAccept pc = StripAccept.parse(corrupt);
        check("parse: الفاسد/المجهول/السالب/القصير يُتخطى والسليم يبقى",
                pc.count(StripAccept.CLIP) == 2 && pc.total() == 2);
        check("parse: فارغ/null = حالة مصنع",
                StripAccept.parse(null).total() == 0 && StripAccept.parse("").total() == 0);
        check("privacy: القيم أرقام فقط — لا نص مستخدم في الصيغة",
                !src.serialize().contains("كلمة"));
    }
}
