package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * بطارية W59 — DialectAdaptTest: كل قانون اختباران موجب/سلبي على الأقل.
 * نقي JVM (صفر android.*) — يجري داخل tests/run.sh وبوابة ci/dialectadapt_gate.sh.
 *
 * يغطي:
 *   • التسجيل: قرار حي بدليل كافٍ يُسجَّل، الناقص الدليل لا يُسجَّل أبداً.
 *   • الإقلاع البارد: البذرة الجائزة (updates ≥ 3) تفوز على النافذة الفارغة،
 *     وغير الجائزة لا تبذّر شيئاً (فصحى كما كان تماماً).
 *   • لا كسر SmartSwitch: قرار الدليل الحي ≥ MIN_EVIDENCE يكسر البذرة فوراً
 *     بأصليها؛ الأوضاع الصريحة (off/msa/fixed) خارج الصنف أصلاً.
 *   • الإنزال للفصحى: ثلاثة auto-msa متتالية بلا علامة تنزل قانون لهجة.
 *   • دمج الأوزان: الكشف الحي يتصدر حتى cap، البذرة بنصف الوزن فقط.
 *   • الصيغة: جولة كاملة parse/serialize، السعة CAP، الأسطر الفاسدة تُتخطى.
 *   • التخفي: قانون التسجيل موقع الاستدعاء في الخدمة — البطارية تثبت أن
 *     record لا ينشئ شيئاً من مدخلات فاسدة (pkg فارغ/قاموس غير لهجي).
 */
public class DialectAdaptTest {
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
        // ---- التسجيل: الدليل الكافي يسجل، الناقص لا ---------------------------
        DialectAdapt d = new DialectAdapt();
        d.record("com.work.mail", "ar_hijazi");
        check("record: لهجة بدليل كافٍ تُسجَّل (u=1)",
                d.profile("com.work.mail").equals(new DialectAdapt.Profile("ar_hijazi", 1, 0)));
        d.record("com.work.mail", "ar_hijazi");
        d.record("com.work.mail", "ar_hijazi");
        check("record: ثلاثة قرارات تجعل التطبيق مستقراً (u=3)",
                d.profile("com.work.mail").updates == 3);
        check("seed: المستقر يبذّر الإقلاع البارد", "ar_hijazi".equals(d.seed("com.work.mail")));
        d.record("com.chat.app", "ar_gulf");
        d.record("com.chat.app", "ar_gulf");
        check("seed: غير المستقر (u=2) لا يبذّر شيئاً", d.seed("com.chat.app") == null);

        // ---- السلبيات: ما لا يُسجَّل -----------------------------------------
        DialectAdapt d2 = new DialectAdapt();
        d2.record("com.x", "ar");                      // الفصحى ليست لهجة
        d2.record(null, "ar_hijazi");                  // تطبيق فارغ
        d2.record("", "ar_najdi");                     // تطبيق فارغ نصاً
        d2.record("com.y", null);                      // قاموس فارغ
        d2.record("com.y", "");                        // قاموس فارغ نصاً
        d2.record("com.y", "ar_klingon");              // قاموس غير معروف
        check("record: الفصحى/الفارغ/غير المعروف لا يُنشئ ملفاً", d2.size() == 0);

        // ---- القرار الحي يكسر البذرة (لا كسر SmartSwitch) ---------------------
        // البذرة حجازي، والدليل الحي خليجي بدليل كافٍ — قرار SmartSwitch الأصلي يفوز.
        SmartSwitch.Decision live = SmartSwitch.decide(
                Arrays.asList("وايد", "زين", "الحين"), SmartSwitch.AUTO);
        check("switch: الدليل الحي الخليجي يقرر gulf أصلياً",
                live.dialectActive() && "ar_gulf".equals(live.dictId) && "auto:gulf".equals(live.reason));
        DialectAdapt d3 = new DialectAdapt();
        d3.record("com.a", "ar_hijazi");
        d3.record("com.a", "ar_hijazi");
        d3.record("com.a", "ar_hijazi");
        check("switch: البذرة حجازي لكن القرار الحي لم يتغير",
                "ar_hijazi".equals(d3.seed("com.a")) && "ar_gulf".equals(live.dictId));

        // ---- الدليل الضعيف + البذرة: البذرة تبقى (قانون C2 يحفظ MIN_EVIDENCE) --
        SmartSwitch.Decision weak = SmartSwitch.decide(
                Arrays.asList("الحين", "الكتاب", "العمل"), SmartSwitch.AUTO);
        check("switch: الدليل الضعيف لا يقلب القاموس (weak-evidence كما كان)",
                !weak.dialectActive() && "weak-evidence".equals(weak.reason));
        check("switch: مع البذرة المستقرة، الضعيف لا يكسرها — البذرة تفوز",
                "ar_hijazi".equals(d3.seed("com.a")));

        // ---- الأوضاع الصريحة خارج الصنف كلياً ---------------------------------
        SmartSwitch.Decision off = SmartSwitch.decide(Arrays.asList("وايد", "زين"), SmartSwitch.OFF);
        SmartSwitch.Decision msa = SmartSwitch.decide(Arrays.asList("وايد", "زين"), SmartSwitch.MSA);
        SmartSwitch.Decision fixed = SmartSwitch.decide(Arrays.asList("وايد", "زين"), Dialect.HIJAZI);
        check("switch: off يبقى فصحى بلا كشف", "off".equals(off.reason) && !off.dialectActive());
        check("switch: msa يبقى فصحى صريحاً", "msa-mode".equals(msa.reason));
        check("switch: المثبت يبقى بقرار المستخدم", "fixed:hijazi".equals(fixed.reason));

        // ---- الإنزال للفصحى (تطبيق العمل فصحى) --------------------------------
        DialectAdapt d4 = new DialectAdapt();
        d4.record("com.work.mail", "ar_najdi");
        d4.record("com.work.mail", "ar_najdi");
        d4.record("com.work.mail", "ar_najdi");
        d4.recordMsa("com.work.mail", 0, 5);
        d4.recordMsa("com.work.mail", 0, 4);
        check("downgrade: اثنان فقط لا ينزلان القانون",
                d4.profile("com.work.mail").dictId.equals("ar_najdi")
                        && d4.profile("com.work.mail").msas == 2);
        d4.recordMsa("com.work.mail", 0, 3);
        check("downgrade: الثالثة تنزل القانون إلى الفصحى",
                d4.profile("com.work.mail").dictId.equals("ar"));
        check("downgrade: المنزَّل لم يبذّر بعد ذلك (فصحى كما طلب المستخدم عملياً)",
                d4.seed("com.work.mail") == null);
        DialectAdapt d5 = new DialectAdapt();
        d5.record("com.a", "ar_gulf");
        d5.record("com.a", "ar_gulf");
        d5.record("com.a", "ar_gulf");
        d5.recordMsa("com.a", 1, 5);   // علامة واحدة = قرار لهجة ناقص، يصفّر لا يعدّ
        d5.recordMsa("com.a", 0, 2);   // نافذة أقصر من الحد، لا تعدّ
        check("downgrade: العلامة الواحدة والنافذة القصيرة لا تعدّان",
                d5.profile("com.a").dictId.equals("ar_gulf") && d5.profile("com.a").msas == 0);

        // ---- دمج الأوزان: الكشف الحي يتصدر، البذرة بنصف الوزن ------------------
        List<String> dialect = Arrays.asList("دحين", "ايش", "يبه", "وايد", "منو");
        List<String> msaWords = Arrays.asList("الكتاب", "العمل", "البيت", "الجامعة");
        List<String> liveMerge = DialectAdapt.mergeLines(dialect, msaWords, 3, false);
        check("merge: الكشف الحي — اللهجة تتصدر حتى cap", liveMerge.equals(
                Arrays.asList("دحين", "ايش", "يبه")));
        List<String> seededMerge = DialectAdapt.mergeLines(dialect, msaWords, 3, true);
        check("merge: البذرة — نصف الوزن فقط (1 من 3) ثم سدادة الفصحى",
                seededMerge.equals(Arrays.asList("دحين", "الكتاب", "العمل")));
        List<String> cap4 = DialectAdapt.mergeLines(dialect, msaWords, 4, true);
        check("merge: cap=4 والبذرة — اثنان من اللهجة ثم سداد",
                cap4.equals(Arrays.asList("دحين", "ايش", "الكتاب", "العمل")));
        check("merge: قائمة اللهجة أقصر من النصف تُمَل من الفصحى",
                DialectAdapt.mergeLines(Arrays.asList("دحين"), msaWords, 3, true)
                        .equals(Arrays.asList("دحين", "الكتاب", "العمل")));
        check("merge: بلا لهجة = الفصحى وحدها حتى cap",
                DialectAdapt.mergeLines(null, msaWords, 2, false).equals(Arrays.asList("الكتاب", "العمل")));
        check("merge: بلا تكرار — الأول يفوز",
                DialectAdapt.mergeLines(Arrays.asList("كتاب"), Arrays.asList("كتاب", "قلم"), 3, false)
                        .equals(Arrays.asList("كتاب", "قلم")));
        check("merge: cap صفر أو سالب = فارغة",
                DialectAdapt.mergeLines(dialect, msaWords, 0, false).isEmpty());

        // ---- الصيغة: جولة كاملة، السعة، الفاسد --------------------------------
        DialectAdapt src = new DialectAdapt();
        src.record("app.one", "ar_hijazi");
        src.record("app.two", "ar_gulf");
        src.record("app.two", "ar_gulf");
        src.record("app.two", "ar_gulf");
        src.record("app.three", "ar_najdi");
        src.record("app.three", "ar_najdi");
        src.recordMsa("app.three", 0, 4);
        DialectAdapt back = DialectAdapt.parse(src.serialize());
        check("roundtrip: الملفات كاملة بعد parse/serialize",
                back.profile("app.one").equals(src.profile("app.one"))
                        && back.profile("app.two").equals(src.profile("app.two"))
                        && back.profile("app.three").equals(src.profile("app.three")));
        check("roundtrip: ترتيب الاستخدام محفوظ (app.three الأحدث آخراً)",
                back.serialize().endsWith("app.three\tar_najdi\t2\t1\n"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 80; i++) big.append("com.app").append(i).append("\tar_hijazi\t5\t0\n");
        check("cap: 80 ملفاً تُقص إلى CAP=64 بالأحدث",
                DialectAdapt.parse(big.toString()).size() == DialectAdapt.CAP);
        String corrupt = "nosep-line\napp.ok\tar_hijazi\t4\t0\napp.bad\tar_klingon\t3\t0\n"
                + "app.neg\tar_gulf\t-1\t0\napp.short\tar_hijazi\n\n";
        DialectAdapt pc = DialectAdapt.parse(corrupt);
        check("parse: السطر الفاسد/القاموس المجهول/السالب/القصير تُتخطى والسليم يبقى",
                pc.size() == 1 && pc.profile("app.ok") != null);
        check("parse: فارغ/null = حالة مصنع", DialectAdapt.parse(null).size() == 0
                && DialectAdapt.parse("").size() == 0);

        // ----touch: ترتيب الاستخدام يتحدث دون تغيير القيم -----------------------
        DialectAdapt d6 = new DialectAdapt();
        d6.record("app.old", "ar_hijazi");
        d6.record("app.new", "ar_gulf");
        d6.touch("app.old");
        String s6 = d6.serialize();
        check("touch: الملامس يرفع الأقدم إلى الأحدث",
                s6.indexOf("app.old") > s6.indexOf("app.new"));
        check("touch: القيم لا تتغير بالملاس",
                d6.profile("app.old").equals(new DialectAdapt.Profile("ar_hijazi", 1, 0)));

        // ---- الخصوصية: toString يحمل عدّاداً لا كلمات مستخدم -------------------
        check("privacy: toString عدّادات فقط",
                d6.profile("app.old").toString().equals("ar_hijazi(u1,m0)"));
    }
}
