package com.hark.neokey;

import java.nio.charset.StandardCharsets;

/** Offline professional message templates (Arabic + English). Every result is a draft the user edits.
 *
 *  W54 capacity trim (law 6 — knowledge out of the dex): the template text lives in
 *  assets/writer_templates.txt, extracted byte-exactly from this file's history by
 *  scripts/trim_w54.py (the ONE derivation). This class is the thin strict interpreter:
 *  init(byte[]) refuses anything but the exact documented shape — 7 categories,
 *  3/3/3/3/8/3/3 variants per language, sequential variant ids, no empty text — so a
 *  truncated or tampered asset is a loud failure, never a half-loaded template bank. */
final class Writer {
    static final String[] CATS = {"رسمية", "خدمة العملاء", "عمل", "توظيف", "ردود مختصرة", "اعتذار", "شكر"};
    static final String[] TONES = {"رسمي", "مهني ودّي", "مختصر"};
    static final String[] LANGS = {"عربي", "English"};
    static final int SHORT = 4;

    private Writer() {}

    /** Variants per category — the shape the asset must reproduce exactly. */
    private static final int[] PER_CAT = {3, 3, 3, 3, 8, 3, 3};
    private static volatile String[][] AR, EN;

    /** Strict load (fail-closed): any drift in shape refuses the whole bank. */
    static void init(byte[] raw) {
        String[][] a = new String[7][], e = new String[7][];
        int[] ac = new int[7], ec = new int[7];
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.split("\t", 4);
            if (p.length != 4) throw new IllegalArgumentException("writer.templates.columns");
            int lang;
            switch (p[0]) { case "A": lang = 0; break; case "E": lang = 1; break; default: throw new IllegalArgumentException("writer.templates.lang"); }
            int cat, v;
            try { cat = Integer.parseInt(p[1]); v = Integer.parseInt(p[2]); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("writer.templates.index"); }
            if (cat < 0 || cat > 6 || p[3].isEmpty()) throw new IllegalArgumentException("writer.templates.shape");
            int[] cnt = lang == 0 ? ac : ec;
            if (v != cnt[cat] || ++cnt[cat] > PER_CAT[cat]) throw new IllegalArgumentException("writer.templates.order");
            String[][] tgt = lang == 0 ? a : e;
            if (tgt[cat] == null) tgt[cat] = new String[PER_CAT[cat]];
            tgt[cat][v] = p[3];
        }
        for (int c = 0; c < 7; c++) {
            if (ac[c] != PER_CAT[c] || ec[c] != PER_CAT[c]) throw new IllegalArgumentException("writer.templates.count");
            for (int v = 0; v < PER_CAT[c]; v++)
                if (a[c][v] == null || e[c][v] == null) throw new IllegalArgumentException("writer.templates.hole");
        }
        AR = a; EN = e;
    }

    private static String[][] set(int lang) {
        String[][] s = lang == 1 ? EN : AR;
        if (s == null) throw new IllegalStateException("writer.templates.not_loaded");
        return s;
    }

    static int variants(int cat, int lang) { return set(lang)[cat].length; }

    static String[] shortReplies(int lang) { return set(lang)[SHORT]; }

    /** Builds a draft. Empty fields become [brackets] so the user sees what to fill in. */
    static String compose(int cat, int tone, int lang, String to, String from, String topic, int variant) {
        boolean en = lang == 1;
        String[] set = set(lang)[cat];
        String body = set[((variant % set.length) + set.length) % set.length];
        if (cat == SHORT) return body;
        String t = topic == null || topic.trim().isEmpty() ? (en ? "[subject]" : "[الموضوع]") : topic.trim();
        body = body.replace("{t}", t);
        if (tone == 2) body = firstSentence(body, en);
        to = to == null ? "" : to.trim();
        from = from == null ? "" : from.trim();
        StringBuilder b = new StringBuilder();
        if ((cat == 0 || cat == 2 || cat == 3) && tone != 2) b.append(en ? "Subject: " : "الموضوع: ").append(subject(cat, t, en)).append("\n\n");
        b.append(greeting(tone, en, to, cat)).append("\n\n").append(body).append("\n\n").append(closing(tone, en, from, cat));
        return b.toString();
    }

    private static String subject(int cat, String t, boolean en) {
        if (cat == 3) return en ? "Application for " + t : "طلب توظيف - " + t;
        return t;
    }

    private static String greeting(int tone, boolean en, String to, int cat) {
        if (en) {
            if (tone == 0) return to.isEmpty() ? "Dear Sir or Madam," : "Dear " + to + ",";
            if (tone == 1) return (to.isEmpty() ? "Hello," : "Hi " + to + ",") + (cat == 1 ? "" : "\nI hope you are well.");
            return to.isEmpty() ? "Hi," : "Hi " + to + ",";
        }
        if (tone == 0) return (to.isEmpty() ? "" : "سعادة/ " + to + " المحترم\n") + "السلام عليكم ورحمة الله وبركاته، وبعد:";
        if (tone == 1) return (to.isEmpty() ? "مرحباً،" : "مرحباً " + to + "،") + (cat == 1 ? "" : "\nأتمنى أن تكون بخير.");
        return to.isEmpty() ? "أهلاً،" : "أهلاً " + to + "،";
    }

    private static String closing(int tone, boolean en, String from, int cat) {
        String sign = from.isEmpty() ? (en ? "[Your name]" : "[اسمك]") : from;
        if (cat == 1) sign = from.isEmpty() ? (en ? "Customer Care Team" : "فريق خدمة العملاء") : from;
        if (en) return (tone == 0 ? "Yours faithfully," : tone == 1 ? "Kind regards," : "Thanks,") + "\n" + sign;
        return (tone == 0 ? "وتفضلوا بقبول فائق الاحترام والتقدير،" : tone == 1 ? "مع خالص التحية،" : "شكراً لك،") + "\n" + sign;
    }

    private static String firstSentence(String s, boolean en) {
        int i = s.indexOf(". ");
        if (i < 0) i = s.indexOf(".");
        return i > 0 ? s.substring(0, i + 1) : s;
    }
}
