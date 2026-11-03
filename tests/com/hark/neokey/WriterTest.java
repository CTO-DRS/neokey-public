package com.hark.neokey;

/** Roadmap Phase 0 / Week 2: offline professional message templates (مساعد المراسلات). */
public class WriterTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        System.out.println("== Writer:catalog integrity ==");
        check("7 categories", Writer.CATS.length == 7);
        check("3 tones", Writer.TONES.length == 3);
        check("2 languages", Writer.LANGS.length == 2);
        check("SHORT category index", Writer.SHORT == 4);
        boolean v = true;
        for (int c = 0; c < 7; c++) if (Writer.variants(c, 0) <= 0 || Writer.variants(c, 1) <= 0) v = false;
        check("every category has variants in both languages", v);
        check("8 short replies AR", Writer.shortReplies(0).length == 8);
        check("8 short replies EN", Writer.shortReplies(1).length == 8);

        System.out.println("== Writer:arabic formal letter ==");
        String f = Writer.compose(0, 0, 0, "أحمد", "", "عقود الإيجار", 0);
        check("subject line with topic", f.contains("الموضوع: عقود الإيجار"));
        check("formal greeting names the recipient", f.contains("سعادة/ أحمد المحترم"));
        check("islamic formal greeting", f.contains("السلام عليكم ورحمة الله وبركاته"));
        check("topic replaces {t} in body", f.contains("عقود الإيجار"));
        check("empty sender becomes [اسمك]", f.contains("[اسمك]"));
        String f2 = Writer.compose(0, 0, 0, "", "م. خالد", "عقود", 0);
        check("sender name signed", f2.contains("م. خالد"));
        check("empty recipient formal greeting", f2.contains("السلام عليكم ورحمة الله وبركاته"));

        System.out.println("== Writer:tones, placeholders, variants ==");
        String brief = Writer.compose(0, 2, 0, "", "", "عقود", 0);
        check("brief tone drops subject line", !brief.contains("الموضوع:"));
        check("brief tone keeps first sentence only", !brief.contains("وأنا على استعداد"));
        check("brief tone closing", brief.contains("شكراً لك،"));
        String ph = Writer.compose(0, 1, 0, "", "", "  ", 0);
        check("empty topic becomes [الموضوع] placeholder", ph.contains("[الموضوع]"));
        check("negative variant wraps (v=-1 == v=2)", Writer.compose(0, 1, 0, "", "", "عقود", -1).equals(Writer.compose(0, 1, 0, "", "", "عقود", 2)));
        check("overflow variant wraps (v=3 == v=0)", Writer.compose(0, 1, 0, "", "", "عقود", 3).equals(Writer.compose(0, 1, 0, "", "", "عقود", 0)));

        System.out.println("== Writer:customer care + job ==");
        String care = Writer.compose(1, 1, 0, "", "", "تأخير الطلب", 0);
        check("customer care signs as the team", care.contains("فريق خدمة العملاء"));
        check("customer care skips warmup line", !care.contains("أتمنى أن تكون بخير."));
        check("customer care greeting", care.contains("مرحباً،"));
        String job = Writer.compose(3, 0, 0, "", "", "مهندس", 0);
        check("job subject titled طلب توظيف", job.contains("طلب توظيف - مهندس"));
        String short0 = Writer.compose(Writer.SHORT, 0, 0, "س", "م", "ت", 0);
        check("short category returns body only", short0.equals(Writer.shortReplies(0)[0]));

        System.out.println("== Writer:english letters ==");
        String en = Writer.compose(0, 0, 1, "", "", "contracts", 0);
        check("english subject", en.contains("Subject: contracts"));
        check("english formal greeting", en.contains("Dear Sir or Madam,"));
        check("english faithful closing", en.contains("Yours faithfully,"));
        check("english placeholder name", en.contains("[Your name]"));
        String en1 = Writer.compose(0, 1, 1, "Sara", "", "x", 0);
        check("english friendly greeting names", en1.contains("Hi Sara,"));
        check("english warmup line", en1.contains("I hope you are well."));
        String en2 = Writer.compose(0, 2, 1, "", "", "x", 0);
        check("english brief closing", en2.contains("Thanks,"));
        System.out.println("WriterTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
