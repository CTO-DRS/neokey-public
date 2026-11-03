package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 1, item B (week 2): SendPreview — «ماذا كتبت فعلاً مقابل ما سيُرسل».
 * The when-to-show rule, the printed lines, and the privacy contract:
 * edited words only, capped sides, no field content, no identifiers.
 */
public class SendPreviewTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        System.out.println("== SendPreview: when the small window shows ==");
        List<SendPreview.Edit> one = Arrays.asList(new SendPreview.Edit("مشكله", "مشكلة"));
        check("corrections pending + normal field -> shows", SendPreview.shouldShow(one, false));
        check("sensitive field -> NEVER shows", !SendPreview.shouldShow(one, true));
        check("no edits -> nothing to show",
                !SendPreview.shouldShow(new ArrayList<SendPreview.Edit>(), false)
                        && !SendPreview.shouldShow(null, false));
        check("build empty/null -> null",
                SendPreview.build(null) == null
                        && SendPreview.build(new ArrayList<SendPreview.Edit>()) == null);

        System.out.println("== SendPreview: the printed lines ==");
        String line = SendPreview.lineFor(new SendPreview.Edit("مشكله", "مشكلة"));
        check("line carries both sides", line.contains("مشكله") && line.contains("مشكلة")
                && line.contains("كتبت فعلاً") && line.contains("سيُرسل"));
        String text = SendPreview.build(Arrays.asList(
                new SendPreview.Edit("مشكله", "مشكلة"),
                new SendPreview.Edit("اكيد", "أكيد"),
                new SendPreview.Edit("الى", "إلى"),
                new SendPreview.Edit("علي", "على"),
                new SendPreview.Edit("الان", "الآن")));
        // 4 shown lines + 1 count line + 1 undo hint = 6
        check("four lines shown + count line", text.split("\n").length == 6
                && text.contains("تصحيحات أخرى: 1"));
        check("undo hint present", text.contains("تراجع"));
        check("order preserved (newest correction first)",
                text.indexOf("مشكله") < text.indexOf("اكيد"));

        System.out.println("== SendPreview: the privacy contract ==");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20; i++) big.append("طويل");
        big.append("MARK").append("ب");
        String capped = SendPreview.lineFor(new SendPreview.Edit(big.toString(), "مشكلة"));
        check("side capped at " + SendPreview.MAX_SIDE + " chars + ellipsis",
                capped.contains("…") && !capped.contains("MARK"));
        String all = SendPreview.build(Arrays.asList(
                new SendPreview.Edit("مشكله", "مشكلة"), new SendPreview.Edit("اكيد", "أكيد")));
        check("no identifiers/paths/urls leak into the preview",
                !all.contains("/") && !all.contains("http") && !all.contains("@"));
        check("empty sides tolerated",
                SendPreview.lineFor(new SendPreview.Edit(null, null)).contains("«»"));
    }
}
