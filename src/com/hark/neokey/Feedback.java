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
     *
     * W57 batch-2 lever pull (law 5, the W54 Writer/Theme pattern): the
     * wording lives in assets/feedback_copy.txt (ci/make_feedback_asset.py —
     * the ONE derivation, parse-proven against the captured runtime truth);
     * init(byte[]) is the thin strict interpreter — any drift refuses the
     * WHOLE bank (never a half-loaded one). Until init answers, the banks
     * are EMPTY — the fail-closed state (the survey copies nothing, the join
     * dialog shows nothing) — the mail loop's step 1 (SUPPORT_EMAIL + the
     * subject) is a code constant and never dies.
     */
    public static String[] surveyQuestions() {
        String[] s = survey;
        return s == null ? new String[0] : s.clone();
    }

    private static volatile String[] survey;
    private static volatile String joinBody;

    /**
     * Strict load (fail-closed): F rows (the 5 survey questions, sequential
     * ids, non-empty) + J rows (the 8 join lines, sequential ids, empty
     * separator lines legal) — any drift in shape refuses the whole bank.
     * The %E/%S placeholders fold to the code constants at init (the one
     * truth for the support address).
     */
    public static void init(byte[] raw) {
        String[] sv = new String[5];
        StringBuilder jb = new StringBuilder(320);
        int fc = 0, jc = 0;
        for (String line : new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.split("\t", 3);
            if (p.length != 3) throw new IllegalArgumentException("feedback.copy.columns");
            int v;
            try { v = Integer.parseInt(p[1]); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("feedback.copy.index"); }
            if (p[0].equals("F")) {
                if (v != fc || fc >= 5 || p[2].isEmpty()) throw new IllegalArgumentException("feedback.copy.survey");
                sv[fc++] = p[2];
            } else if (p[0].equals("J")) {
                if (v != jc || jc >= 8) throw new IllegalArgumentException("feedback.copy.join");
                if (jc > 0) jb.append('\n');
                jb.append(p[2].replace("%E", SUPPORT_EMAIL).replace("%S", JOIN_SUBJECT));
                jc++;
            } else throw new IllegalArgumentException("feedback.copy.row");
        }
        if (fc != 5 || jc != 8) throw new IllegalArgumentException("feedback.copy.count");
        survey = sv; joinBody = jb.toString();
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
     * is never dead. Loaded from assets/feedback_copy.txt (init — the W57
     * batch-2 lever); empty until init answers (fail-closed).
     */
    public static String joinSteps() {
        String j = joinBody;
        return j == null ? "" : j;
    }
}
