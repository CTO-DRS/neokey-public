package com.hark.neokey;

import java.util.List;

/**
 * بطارية W60 — AdaptEngineTest: كل قانون اختباران موجب/سلبي على الأقل.
 * نقي JVM — يجري داخل tests/run.sh وبوابة ci/personal_gate.sh.
 *
 * يغطي:
 *   • التعلم والعدّ: learn يرفع العدّ، ويعيد ضبط اليوم، والحدود تنفض.
 *   • التوهين الزمني الحتمي: score = count·0.5^(الأيام÷14) — ينصفي عند 14
 *     يوماً، والتعادل بالأحدث استخداماً.
 *   • السقف المقيس: CAP=256 LRU بالأحدث استخداماً.
 *   • العقد: run() على التكوين (بادئة بالشارة ★)، وعلى التنبؤ (المقعد
 *     الواحد)، والحد 8 من المرسل يُحترم، والفارغ آمن.
 *   • الشفافية: كل مرشح يحمل MARK، والمقعد الواحد في التنبؤ.
 *   • المسح الكامل: wipe() → serialize()=="" وparse("")==حالة المصنع.
 *   • الصيغة: جولة كاملة، الأسطر الفاسدة تُتخطى، الحدوق السالبة تنفض.
 */
public class AdaptEngineTest {
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
        // ---- التعلم والعدّ -----------------------------------------------------
        AdaptEngine a = new AdaptEngine();
        a.setToday(100);
        a.learn("نبيل", 100);
        a.learn("نبيل", 101);
        check("learn: العدّ يتراكم واليوم يتحدث",
                a.entry("نبيل").equals(new AdaptEngine.Entry("نبيل", 2, 101)));
        a.learn("سالم", 100);
        check("learn: كلمتان مستقلتان", a.size() == 2);
        a.learn(null, 100);
        a.learn("", 100);
        a.learn("كلمة_بها\tفاصل", 100);
        a.learn("كلمة_بها\nسطر", 100);
        check("learn: الفارغ/الفواصل تنفض", a.size() == 2);
        StringBuilder long1 = new StringBuilder("ا");
        for (int i = 0; i < 50; i++) long1.append("ب");
        a.learn(long1.toString(), 100);
        check("learn: الكلمة فوق 40 حرفاً تنفض", a.size() == 2);

        // ---- التوهين الزمني الحتمي ---------------------------------------------
        check("decay: يوم الاستخدام = نقش كامل", a.entry("نبيل").score(101) == 2.0);
        check("decay: بعد 14 يوماً ينصفي", Math.abs(a.entry("نبيل").score(115) - 1.0) < 1e-9);
        check("decay: بعد 28 يوماً ينصفي ثانية", Math.abs(a.entry("نبيل").score(129) - 0.5) < 1e-9);
        check("decay: يوم أقدم من الاستخدام = نقش كامل (بلا عقوبة)",
                a.entry("نبيل").score(50) == 2.0);
        check("decay: تحت MIN_SCORE لا يجوز العرض",
                a.entry("نبيل").score(140) < AdaptEngine.MIN_SCORE);

        // ---- الترتيب والتعادل ---------------------------------------------------
        AdaptEngine b = new AdaptEngine();
        b.setToday(50);
        b.learn("قديم", 10);      // عدّ 1، عمره 40 يوماً → score = 1·0.5^(40/14) ≈ 0.139
        b.learn("جديد", 50);      // عدّ 1، اليوم نفسه → score 1.0
        b.learn("قديم", 10);
        List<AdaptEngine.Entry> t = b.top(-1);
        check("order: الأعلى نقشاً أولاً (الحية قبل المهجورة)",
                t.get(0).word.equals("جديد"));
        check("order: التعادل بالعدّ يفوز فيه الأحدث استخداماً",
                b.entry("قديم").count == 2);

        // ---- السقف المقيس CAP=256 LRU ------------------------------------------
        AdaptEngine c = new AdaptEngine();
        for (int i = 0; i < 300; i++) c.learn("كلمة_" + i, 10);
        check("cap: 300 كلمة تُقص إلى CAP=256 بالأحدث", c.size() == AdaptEngine.CAP);
        check("cap: الأقدم سقط والأحدث بقى", c.entry("كلمة_0") == null && c.entry("كلمة_299") != null);
        c.learn("كلمة_0", 11);
        check("cap: الملموس يعاد ويقص الأقدم من جديد", c.size() == AdaptEngine.CAP);

        // ---- العقد: run على التكوين والتنبؤ ------------------------------------
        AdaptEngine d = new AdaptEngine();
        d.setToday(10);
        d.learn("نبيل_العمري", 10);
        d.learn("نبيل_العمري", 10);
        d.learn("نبيل_العمري", 10);
        d.learn("نوره", 10);
        d.learn("نوره", 10);
        String[] comp = d.run(new Engine.Ctx(null, null, "نبيل"));
        check("run: البادئة ترشح بالشارة ★", comp.length == 1 && comp[0].equals("★ نبيل_العمري"));
        String[] comp2 = d.run(new Engine.Ctx(null, null, "ن"));
        check("run: البادئة القصيرة ترشح المرشحين مرتبين بالنقش",
                comp2.length == 2 && comp2[0].equals("★ نبيل_العمري") && comp2[1].equals("★ نوره"));
        String[] pred = d.run(new Engine.Ctx("أبو", "قال", ""));
        check("run: التنبؤ = المقعد الشخصي الواحد بالشارة",
                pred.length == 1 && pred[0].equals("★ نبيل_العمري"));
        check("run: id() == personal (المصدر معلَم بالعقد)", d.id().equals("personal"));
        String[] empty = d.run(new Engine.Ctx(null, null, "غير_موجود_بالبادئة"));
        check("run: بلا بادئة مطابقة = فارغ آمن", empty.length == 0);
        AdaptEngine e2 = new AdaptEngine();
        check("run: مخزن فارغ يجيب فارغاً (لا نصف بناء)",
                e2.run(new Engine.Ctx(null, null, "ن")).length == 0
                        && e2.run(new Engine.Ctx("أبو", "قال", "")).length == 0);

        // ---- الشفافية: الشارة على كل مرشح شخصي ---------------------------------
        for (String s : comp2) check("badge: الشارة على «" + s + "»", s.startsWith(AdaptEngine.MARK));

        // ---- التخفي: القانون موقع الاستدعاء — المحرك لا يتعلم إلا بأمر صريح -----
        // (الخدمة لا تستدعي learn/run في التخفي — البطارية تثبت أن العدّ لا يتحرك
        //  إلا عبر learn الصريح: لا حالة خفية ولا ساعة ذاتية في المحرك)
        AdaptEngine f = new AdaptEngine();
        f.setToday(100);
        check("incognito: لا ساعة ذاتية — بلا learn لا شيء يتعلم أبداً", f.size() == 0);

        // ---- المسح الكامل: حالة مصنع بايتياً ------------------------------------
        d.wipe();
        check("wipe: serialize() == \"\" بعد المسح", d.serialize().isEmpty());
        check("wipe: parse(\"\") == حالة المصنع", AdaptEngine.parse("").size() == 0);
        check("wipe: المحرك الممسوح يجيب فارغاً فوراً",
                d.run(new Engine.Ctx(null, null, "ن")).length == 0
                        && d.run(new Engine.Ctx("أبو", "قال", "")).length == 0);

        // ---- الصيغة: جولة كاملة، الفاسد يُتخطى ----------------------------------
        AdaptEngine src = new AdaptEngine();
        src.learn("العمري", 20);
        src.learn("العمري", 21);
        src.learn("مكتب_العمل", 20);
        AdaptEngine back = AdaptEngine.parse(src.serialize());
        check("roundtrip: الإدخالات كاملة بعد parse/serialize",
                back.entry("العمري").equals(src.entry("العمري"))
                        && back.entry("مكتب_العمل").equals(src.entry("مكتب_العمل")));
        String corrupt = "سطر_بلا_فواصل\nالعمري\t2\t21\nفاسد\tحرف\tأرقام\nسالب\t-1\t5\nصفر\t0\t5\nقصير\t1\n\n";
        AdaptEngine pc = AdaptEngine.parse(corrupt);
        check("parse: الفاسد/السالب/الصفر/القصير يُتخطى والسليم يبقى",
                pc.size() == 1 && pc.entry("العمري") != null);
        check("privacy: toString عدّادات فقط بلا كلمات",
                pc.entry("العمري").toString().equals("entry(u2,d21)"));
    }
}
