package com.hark.neokey;

/** Roadmap Phase 0 / Week 2: every ArabicFixes mapping + edge behaviour. */
public class ArabicFixesTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        System.out.println("== ArabicFixes:joined phrases ==");
        check("انشالله", "إن شاء الله".equals(ArabicFixes.fix("انشالله")));
        check("انشاءالله", "إن شاء الله".equals(ArabicFixes.fix("انشاءالله")));
        check("إنشاءالله", "إن شاء الله".equals(ArabicFixes.fix("إنشاءالله")));
        check("انشاالله", "إن شاء الله".equals(ArabicFixes.fix("انشاالله")));
        check("انشاللة", "إن شاء الله".equals(ArabicFixes.fix("انشاللة")));
        check("ان شاءالله", "إن شاء الله".equals(ArabicFixes.fix("ان شاءالله")));
        check("إنشالله", "إن شاء الله".equals(ArabicFixes.fix("إنشالله")));
        check("ماشاءالله", "ما شاء الله".equals(ArabicFixes.fix("ماشاءالله")));
        check("ماشالله", "ما شاء الله".equals(ArabicFixes.fix("ماشالله")));
        check("مشاءالله", "ما شاء الله".equals(ArabicFixes.fix("مشاءالله")));
        check("ماشاالله", "ما شاء الله".equals(ArabicFixes.fix("ماشاالله")));
        check("مشالله", "ما شاء الله".equals(ArabicFixes.fix("مشالله")));
        check("الحمدلله", "الحمد لله".equals(ArabicFixes.fix("الحمدلله")));
        check("الحمدللة", "الحمد لله".equals(ArabicFixes.fix("الحمدللة")));
        check("الحمدالله", "الحمد لله".equals(ArabicFixes.fix("الحمدالله")));
        check("جزاكالله", "جزاك الله خير".equals(ArabicFixes.fix("جزاكالله")));
        check("جزاكاللهخير", "جزاك الله خير".equals(ArabicFixes.fix("جزاكاللهخير")));
        check("باركالله", "بارك الله فيك".equals(ArabicFixes.fix("باركالله")));
        check("باركاللهفيك", "بارك الله فيك".equals(ArabicFixes.fix("باركاللهفيك")));
        check("السلامعليكم", "السلام عليكم".equals(ArabicFixes.fix("السلامعليكم")));
        check("لااله", "لا إله إلا الله".equals(ArabicFixes.fix("لااله")));
        check("لاإلهإلاالله", "لا إله إلا الله".equals(ArabicFixes.fix("لاإلهإلاالله")));

        System.out.println("== ArabicFixes:words ==");
        check("لاكن", "لكن".equals(ArabicFixes.fix("لاكن")));
        check("لاكنه", "لكنه".equals(ArabicFixes.fix("لاكنه")));
        check("هاذا", "هذا".equals(ArabicFixes.fix("هاذا")));
        check("هاذه", "هذه".equals(ArabicFixes.fix("هاذه")));
        check("ذالك", "ذلك".equals(ArabicFixes.fix("ذالك")));
        check("هاكذا", "هكذا".equals(ArabicFixes.fix("هاكذا")));
        check("مسئول", "مسؤول".equals(ArabicFixes.fix("مسئول")));
        check("مسؤل", "مسؤول".equals(ArabicFixes.fix("مسؤل")));
        check("شيئ", "شيء".equals(ArabicFixes.fix("شيئ")));
        check("اصدقاء", "أصدقاء".equals(ArabicFixes.fix("اصدقاء")));
        check("اسف", "آسف".equals(ArabicFixes.fix("اسف")));
        check("انا", "أنا".equals(ArabicFixes.fix("انا")));
        check("الى", "إلى".equals(ArabicFixes.fix("الى")));
        check("اذا", "إذا".equals(ArabicFixes.fix("اذا")));
        check("امس", "أمس".equals(ArabicFixes.fix("امس")));
        check("احسن", "أحسن".equals(ArabicFixes.fix("احسن")));
        check("اكيد", "أكيد".equals(ArabicFixes.fix("اكيد")));
        check("انسان", "إنسان".equals(ArabicFixes.fix("انسان")));

        System.out.println("== ArabicFixes:tanween-to-alef-nun ==");
        check("طبعن", "طبعاً".equals(ArabicFixes.fix("طبعن")));
        check("طبعا", "طبعاً".equals(ArabicFixes.fix("طبعا")));
        check("شكرن", "شكراً".equals(ArabicFixes.fix("شكرن")));
        check("شكرا", "شكراً".equals(ArabicFixes.fix("شكرا")));
        check("اهلن", "أهلاً".equals(ArabicFixes.fix("اهلن")));
        check("اهلا", "أهلاً".equals(ArabicFixes.fix("اهلا")));
        check("عفون", "عفواً".equals(ArabicFixes.fix("عفون")));
        check("عفوا", "عفواً".equals(ArabicFixes.fix("عفوا")));
        check("ايضن", "أيضاً".equals(ArabicFixes.fix("ايضن")));
        check("ايضا", "أيضاً".equals(ArabicFixes.fix("ايضا")));
        check("جدن", "جداً".equals(ArabicFixes.fix("جدن")));
        check("جدا", "جداً".equals(ArabicFixes.fix("جدا")));
        check("دائمن", "دائماً".equals(ArabicFixes.fix("دائمن")));
        check("دايما", "دائماً".equals(ArabicFixes.fix("دايما")));
        check("دائما", "دائماً".equals(ArabicFixes.fix("دائما")));
        check("ابدن", "أبداً".equals(ArabicFixes.fix("ابدن")));
        check("ابدا", "أبداً".equals(ArabicFixes.fix("ابدا")));

        System.out.println("== ArabicFixes:edges ==");
        check("null input", ArabicFixes.fix(null) == null);
        check("1-char input ignored", ArabicFixes.fix("ا") == null);
        check("correct form passes through (لكن)", ArabicFixes.fix("لكن") == null);
        check("already-correct tanween form (أهلاً)", ArabicFixes.fix("أهلاً") == null);
        check("unknown word null", ArabicFixes.fix("زقزقنة_غيرموجودة") == null);
        check("tashkeel wrong form still fixed", "شكراً".equals(ArabicFixes.fix("شُكرا")));
        System.out.println("ArabicFixesTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
