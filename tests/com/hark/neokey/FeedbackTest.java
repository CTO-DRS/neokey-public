package com.hark.neokey;

/**
 * Roadmap item D (week 7): Feedback contract tests — the beta channel wording,
 * the mail envelope, and the promise chain between the app and the store
 * listing (same email, same join story).
 */
public class FeedbackTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    private static boolean containsAny(String hay, String[] needles) {
        String h = hay == null ? "" : hay.toLowerCase();
        for (String n : needles) if (h.contains(n)) return true;
        return false;
    }

    public static void run() {
        System.out.println("== Feedback: envelope ==");

        // The address is a hard contract: PlayGate asserts play/listing.txt shows
        // the same one, so a typo here would silently break the whole beta loop.
        check("support email is the documented address", "cto.drs@gmail.com".equals(Feedback.SUPPORT_EMAIL));
        check("email is normal (lowercase, no spaces, single @)",
                Feedback.SUPPORT_EMAIL.equals(Feedback.SUPPORT_EMAIL.toLowerCase())
                && !Feedback.SUPPORT_EMAIL.contains(" ")
                && Feedback.SUPPORT_EMAIL.indexOf('@') == Feedback.SUPPORT_EMAIL.lastIndexOf('@')
                && Feedback.SUPPORT_EMAIL.endsWith(".com"));
        check("site is https", Feedback.SITE.startsWith("https://"));
        check("mail subject carries version name and code",
                Feedback.mailSubject("1.0", 56).contains("1.0")
                && Feedback.mailSubject("1.0", 56).contains("56"));

        String rep = "{\"schema\":\"neokey-crash-1\",\"version_code\":56}";
        String body = Feedback.mailBody("  ملاحظتي أن الحروف تتأخر  ", "— التقرير المجهول:", rep);
        check("mail body embeds the anonymous report verbatim", body.contains(rep));
        check("mail body trims the user note", body.contains("ملاحظتي أن الحروف تتأخر")
                && !body.contains("  ملاحظتي"));
        check("mail body keeps report after the label",
                body.indexOf("— التقرير المجهول:") < body.indexOf(rep));
        String empty = Feedback.mailBody(null, "— التقرير المجهول:", "");
        check("empty report -> explicit fallback line, never a dangling label",
                empty.contains("لا تقرير بعد") && !Feedback.mailBody(null, "—:", null).endsWith("—:\n"));

        System.out.println("== Feedback: beta loop wording ==");
        check("join steps mention the email (loop works before any link exists)",
                Feedback.joinSteps().contains(Feedback.SUPPORT_EMAIL));
        check("join steps mention the join subject",
                Feedback.joinSteps().contains("انضمام بيتا"));
        check("join steps promise the privacy contract",
                Feedback.joinSteps().contains("مجهول"));
        String[] q = Feedback.surveyQuestions();
        check("survey has exactly the 5 documented questions", q != null && q.length == 5);
        boolean allNonEmpty = true;
        for (String s : q) if (s == null || s.trim().isEmpty()) allNonEmpty = false;
        check("survey questions are non-empty", allNonEmpty);
        check("survey text block numbers the questions",
                Feedback.surveyText().contains("1)") && Feedback.surveyText().contains("5)"));

        // Privacy: nothing in the wording may carry a device identifier or a
        // tracking-style URL parameter (same bar the crash contract is held to).
        String everything = Feedback.joinSteps() + Feedback.surveyText()
                + Feedback.mailSubject("1.0", 56) + Feedback.mailBody(null, "x", rep);
        check("no device identifiers anywhere in the wording",
                !containsAny(everything, new String[]{"imei", "serial", "android_id", "mac_address", "model="}));
        check("no tracking params in any URL", !everything.contains("?utm_") && !everything.contains("&utm_"));
        check("the only URL allowed is the developer site on https",
                !everything.contains("http://") && (!everything.contains("https://") || everything.contains(Feedback.SITE)));
    }
}
