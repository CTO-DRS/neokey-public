package com.hark.neokey;

/** W54 round-trip probe: prints EVERY Writer.compose output over the full input
 *  grid (lang x cat x tone x variant(incl. negatives) x to x from x topic) plus
 *  shortReplies — the before/after byte-diff is the trim's behavioral proof. */
public class W54Probe {
    public static void main(String[] a) {
        StringBuilder b = new StringBuilder();
        String[] tos = {"", "أحمد", "Mr. Sami"};
        String[] froms = {"", "م. خالد"};
        String[] topics = {"", "  ", "عقود الإيجار", "الطلب 2251"};
        int[] vs = {-1, 0, 2, 99};
        for (int lang = 0; lang <= 1; lang++)
            for (int cat = 0; cat < 7; cat++) {
                int vars = Writer.variants(cat, lang);
                for (int tone = 0; tone < 3; tone++)
                    for (int v0 = 0; v0 < vars; v0++)
                        for (int v : vs)
                            for (String to : tos)
                                for (String fr : froms)
                                    for (String tp : topics) {
                                        b.append("=== ").append(lang).append(' ').append(cat).append(' ')
                                         .append(tone).append(' ').append(v0).append('|').append(v)
                                         .append('|').append(to).append('|').append(fr).append('|').append(tp).append('\n');
                                        b.append(Writer.compose(cat, tone, lang, to, fr, tp, v0 + v)).append('\n');
                                    }
                b.append("--- shortReplies lang=").append(lang).append('\n');
                for (String s : Writer.shortReplies(lang)) b.append(s).append('\n');
            }
        System.out.print(b);
    }
}
