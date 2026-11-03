package com.hark.neokey;

/**
 * Roadmap item D (week 7): the beta-program feedback channel promised by
 * play/beta-program.md §1, play/listing.txt and play/reviews.md — surfaced in
 * Settings as «تواصل ومختبر بيتا».
 *
 * Pure logic only (zero android.*): the support address, the mail envelope and
 * the join/survey wording are JVM-tested exactly as shipped, and PlayGate
 * asserts the store listing shows the SAME address (no drift between the
 * listing and the app). SetupActivity wires three rows to these methods:
 *   1) copy support email + open the mail app (subject pre-filled),
 *   2) copy the ready anonymous stability report (CrashReport contract),
 *   3) join-steps dialog (works even before a tester link exists).
 */
public final class Feedback {

    /** The one support address — play/listing.txt must show the same (PlayGate enforces). */
    public static final String SUPPORT_EMAIL = "cto.drs@gmail.com";

    /** Developer site shown next to the email in the listing (consistency check target). */
    public static final String SITE = "https://cto-drs.com";

    /** The subject line that identifies a beta-program feedback mail. */
    public static final String JOIN_SUBJECT = "انضمام بيتا";

    private Feedback() { }

    /** Mail subject carrying the version, e.g. «نيو كيبورد — ملاحظة من الإصدار 1.0 (56)». */
    public static String mailSubject(String versionName, int versionCode) {
        return "نيو كيبورد — ملاحظة من الإصدار " + versionName + " (" + versionCode + ")";
    }

    /**
     * Mail body: the user's own note (optional, trimmed) + the anonymous stability
     * report VERBATIM (neokey-crash-1 contract — numbers only, no identifiers).
     *
     * @param reportLabel localized label for the report block (L.t at the call site)
     * @param anonymousReport the CrashReport.build(...) text, or null/empty when none
     */
    public static String mailBody(String userNote, String reportLabel, String anonymousReport) {
        StringBuilder b = new StringBuilder(256);
        if (userNote != null && !userNote.trim().isEmpty()) {
            b.append(userNote.trim()).append("\n\n");
        }
        if (reportLabel != null && !reportLabel.isEmpty()) b.append(reportLabel).append('\n');
        if (anonymousReport == null || anonymousReport.trim().isEmpty()) {
            b.append("(لا تقرير بعد — ولّده من الأداء والاستقرار ← تقرير الأعطال المجهول)\n");
        } else {
            b.append(anonymousReport.trim()).append('\n');
        }
        return b.toString();
    }

    /**
     * The 5-question first-impression survey — the in-app mirror of
     * play/beta-program.md §4. Same questions, same order, sent as plain text.
     */
    public static String[] surveyQuestions() {
        return new String[]{
                "وش أول شي لاحظته بعد التفعيل؟",
                "صار لك عطل؟ وش كنت تسوي لحظتها؟ (لو موجود: انسخ تقرير الاستقرار المجهول)",
                "وش الميزة اللي استخدمتها أكثر؟",
                "وش الشي اللي أبعدك أو أزعجك؟",
                "من 10، كم احتمال ترشح نيو كيبورد لصديق؟"
        };
    }

    /** The survey as one paste-ready text block (used by the dialog's copy action). */
    public static String surveyText() {
        StringBuilder b = new StringBuilder(256);
        b.append("نموذج الانطباع الأول — مختبر بيتا نيو كيبورد:\n");
        String[] q = surveyQuestions();
        for (int i = 0; i < q.length; i++) b.append(i + 1).append(") ").append(q[i]).append('\n');
        return b.toString();
    }

    /**
     * Join steps shown in the dialog — they work even before a tester link
     * exists: the first step is the mail (Feedback.JOIN_SUBJECT), so the loop
     * is never dead. Pure text; SetupActivity shows it in an AlertDialog.
     */
    public static String joinSteps() {
        return "مختبر البيتا: 100 مقعد، دورتان كل شهر، وملاحظاتك هي التي تحدد ترتيب الإصلاحات.\n\n"
                + "1) أرسل لنا بريداً على " + SUPPORT_EMAIL + " وعنوانه «" + JOIN_SUBJECT
                + "» (زر النسخ في هذا القسم يجهز العنوان لك).\n"
                + "2) نرد عليك برابط الاختبار الداخلي من Google Play.\n"
                + "3) افتح الرابط واضغط «اشترك في الاختبار»، ثم ثبّت نيو كيبورد من المتجر.\n"
                + "4) بعد يوم من الاستخدام أرسل نموذج الانطباع الأول (5 أسئلة) على البريد.\n\n"
                + "الخصوصية مع المختبرين: كل تقرير تخرجه مجهول بالكامل (أرقام فقط بلا أي "
                + "بيانات شخصية)، ولا نجمع شيئاً منك بأي نظام خارجي.";
    }
}
