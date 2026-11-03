package com.hark.neokey;

import java.util.HashMap;
import java.util.Map;

/** Common Arabic spelling mistakes and joined phrases. */
final class ArabicFixes {
    private static final Map<String, String> MAP = new HashMap<>();

    private static void add(String right, String... wrong) {
        for (String w : wrong) MAP.put(Dictionary.norm(w), right);
    }

    static {
        add("إن شاء الله", "انشالله", "انشاءالله", "إنشاءالله", "انشاالله", "انشاللة", "ان شاءالله", "إنشالله");
        add("ما شاء الله", "ماشاءالله", "ماشالله", "مشاءالله", "ماشاالله", "مشالله");
        add("الحمد لله", "الحمدلله", "الحمدللة", "الحمدالله");
        add("جزاك الله خير", "جزاكالله", "جزاكاللهخير");
        add("بارك الله فيك", "باركالله", "باركاللهفيك");
        add("السلام عليكم", "السلامعليكم");
        add("لا إله إلا الله", "لااله", "لاإلهإلاالله");
        add("لكن", "لاكن");
        add("لكنه", "لاكنه");
        add("هذا", "هاذا");
        add("هذه", "هاذه");
        add("ذلك", "ذالك");
        add("هكذا", "هاكذا");
        add("طبعاً", "طبعن", "طبعا");
        add("شكراً", "شكرن", "شكرا");
        add("أهلاً", "اهلن", "اهلا");
        add("عفواً", "عفون", "عفوا");
        add("أيضاً", "ايضن", "ايضا");
        add("جداً", "جدن", "جدا");
        add("دائماً", "دائمن", "دايما", "دائما");
        add("أبداً", "ابدن", "ابدا");
        add("آسف", "اسف");
        add("أنا", "انا");
        add("إلى", "الى");
        add("إذا", "اذا");
        add("أمس", "امس");
        add("أحسن", "احسن");
        add("أكيد", "اكيد");
        add("إنسان", "انسان");
        add("مسؤول", "مسئول", "مسؤل");
        add("شيء", "شيئ");
        add("أصدقاء", "اصدقاء");
    }

    /** Returns the corrected form when the typed word is a known mistake, else null. */
    static String fix(String typed) {
        if (typed == null || typed.length() < 2) return null;
        String r = MAP.get(Dictionary.norm(typed));
        return r == null || r.equals(typed) ? null : r;
    }
}
