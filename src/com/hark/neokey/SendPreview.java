package com.hark.neokey;

import java.util.List;

/**
 * Phase 1, item B (week 2): «إظهار "ماذا كتبت فعلاً مقابل ما سيُرسل" في نافذة
 * صغيرة قبل الإرسال في الحقول الحرجة» — the roadmap's UX treasure as pure logic.
 *
 * The service collects every correction v2 applies to the current field as a
 * typed→sent pair (SendPreview.Edit). On the send action (Enter with an editor
 * action) the small window shows the pairs and asks إرسال / تعديل. The decision
 * of WHEN to show and WHAT to print lives here so it is JVM-tested; the service
 * only draws the dialog (and runs its text through L.t like every dialog).
 *
 * Privacy contract (tested): the preview carries only the edited WORDS, each
 * side capped at MAX_SIDE chars with an ellipsis — never the field content,
 * never any identifier. In sensitive fields no correction ever applies
 * (Correction2.silentCorrectionAllowed), so the window can never appear there.
 *
 * Pure logic, zero android.*, zero L/Theme deps — JVM-tested as shipped.
 */
final class SendPreview {

    private SendPreview() { }

    /** One correction applied in the current field: what was typed, what will be sent. */
    static final class Edit {
        final String typed, sent;
        Edit(String typed, String sent) {
            this.typed = typed == null ? "" : typed;
            this.sent = sent == null ? "" : sent;
        }
    }

    /** Each side of a pair is capped at this many chars (privacy + dialog width). */
    static final int MAX_SIDE = 40;
    /** At most this many lines are shown; the rest collapses into a count line. */
    static final int MAX_LINES = 4;

    /** True when the small window must appear before the send action fires. */
    static boolean shouldShow(List<Edit> edits, boolean sensitive) {
        return !sensitive && edits != null && !edits.isEmpty();
    }

    /** The window text, or null when there is nothing to show. */
    static String build(List<Edit> edits) {
        if (edits == null || edits.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        int shown = Math.min(edits.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) b.append(lineFor(edits.get(i))).append('\n');
        if (edits.size() > shown)
            b.append("تصحيحات أخرى: ").append(edits.size() - shown).append('\n');
        b.append("تراجع عن أي تصحيح بالمسافة للخلف قبل الإرسال.");
        return b.toString();
    }

    /** One line: «كتبت فعلاً «X» ← سيُرسل «Y»» with both sides capped. */
    static String lineFor(Edit e) {
        return "كتبت فعلاً «" + cap(e.typed) + "» ← سيُرسل «" + cap(e.sent) + "»";
    }

    private static String cap(String s) {
        return s.length() <= MAX_SIDE ? s : s.substring(0, MAX_SIDE) + "…";
    }
}
