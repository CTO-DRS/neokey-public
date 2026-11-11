package com.hark.neokey;

import java.io.StringWriter;
import java.util.*;

/** Import / export tests, called from ClipRepoTest.main. */
final class ClipIOTest {
    static String out(int f, List<ClipIO.Rec> l) throws Exception {
        StringWriter w = new StringWriter();
        ClipIO.write(f, l, w, 1_700_000_000_000L, "20.5");
        return w.toString();
    }

    static void run(long now) throws Exception {
        ClipRepo r = ClipRepoTest.fresh();
        long[] id = new long[1];
        String tricky = "بِسْمِ اللَّهِ, \"quoted\"\nسطر ثاني\r\n😀👨‍👩‍👧 \\ / \t tab\u2028end";
        String big = ClipRepoTest.mixed(100_000);
        r.add(tricky, now, id); long a = id[0];
        r.add(big, now + 1, id); long b = id[0];
        r.add("قالب الترحيب", now + 2, id); long c = id[0];
        r.pin(a); r.setFav(b, true); r.setTitle(a, "عنوان, مع فاصلة", now);
        long cat = r.addCategory("عمل"); r.setCategory(a, cat, true);
        r.makeTemplate(c, now);
        List<ClipIO.Rec> all = ClipIO.load(r, ClipIO.SCOPE_ALL, null);
        ClipRepoTest.check("export load all incl. template", all.size() == 4);
        ClipRepoTest.check("export pinned scope", ClipIO.load(r, ClipIO.SCOPE_PINNED, null).size() == 1);
        ClipRepoTest.check("export ids scope", ClipIO.load(r, ClipIO.SCOPE_IDS, new long[]{a, b}).size() == 2);

        // JSON round trip into an empty library keeps everything
        String json = out(ClipIO.F_JSON, all);
        ClipIO.Parsed p = ClipIO.parse(json, "x.json", false);
        ClipRepoTest.check("json parses, schema 1", p.error == null && p.format == ClipIO.F_JSON && p.schema == 1 && p.items.size() == 4);
        ClipRepo r2 = ClipRepoTest.fresh();
        ClipIO.markDuplicates(r2, p);
        ClipRepoTest.check("json all new on empty db", p.count(ClipIO.NEW) == 4);
        ClipIO.Report rep = ClipIO.apply(r2, p.items, ClipIO.DUP_SKIP, now);
        ClipRepoTest.check("json import report", !rep.failed && rep.added == 4);
        List<ClipIO.Rec> back = ClipIO.load(r2, ClipIO.SCOPE_ALL, null);
        boolean same = back.size() == 4;
        for (int i = 0; same && i < 4; i++) {
            ClipIO.Rec x = all.get(i), y = back.get(i);
            same = x.text.equals(y.text) && Objects.equals(x.title, y.title) && x.pinned == y.pinned && x.fav == y.fav
                    && x.kind == y.kind && x.created == y.created && x.cats.equals(y.cats);
        }
        ClipRepoTest.check("json round trip exact (text, title, pin, fav, template, dates, categories)", same);
        ClipRepoTest.check("100k text survives json", big.equals(back.get(1).text) || big.equals(back.get(0).text) || containsText(back, big));

        // second import: everything is a duplicate
        ClipIO.Parsed p2 = ClipIO.parse(json, "x.json", false);
        ClipIO.markDuplicates(r2, p2);
        ClipRepoTest.check("re-import detects duplicates", p2.count(ClipIO.DUP) == 4);
        ClipIO.Report rs = ClipIO.apply(r2, p2.items, ClipIO.DUP_SKIP, now);
        ClipRepoTest.check("skip mode changes nothing", rs.skipped == 4 && r2.totalCount() == 4);
        ClipIO.Report rc = ClipIO.apply(r2, p2.items, ClipIO.DUP_COPY, now);
        ClipRepoTest.check("copy mode adds independent copies", rc.added == 4 && r2.totalCount() == 8);

        // replace: metadata updated on the existing item
        ClipRepo r3 = ClipRepoTest.fresh();
        r3.add(tricky, now, id);
        ClipIO.Parsed p3 = ClipIO.parse(json, "x.json", false);
        ClipIO.markDuplicates(r3, p3);
        ClipIO.Report rr = ClipIO.apply(r3, p3.items, ClipIO.DUP_REPLACE, now);
        ClipItem it = r3.item(id[0]);
        ClipRepoTest.check("replace mode updates existing metadata", rr.replaced == 1 && rr.added == 3 && it.pinned && "عنوان, مع فاصلة".equals(it.title) && r3.categoriesOf(id[0]).size() == 1);

        // CSV round trip of text + basic fields
        String csv = out(ClipIO.F_CSV, all);
        ClipIO.Parsed pc = ClipIO.parse(csv, "x.csv", false);
        ClipRepoTest.check("csv bom + header + quoting round trip", pc.error == null && pc.items.size() == 4
                && containsRec(pc, tricky) && pc.items.get(0).rec.title != null && pc.items.get(0).rec.pinned);
        ClipRepoTest.check("csv reader edge cases", ClipIO.csv("a,\"b,\"\"c\"\"\nd\",e\r\nx").size() == 2
                && ClipIO.csv("a,\"b,\"\"c\"\"\nd\",e\r\nx").get(0).get(1).equals("b,\"c\"\nd"));
        ClipRepoTest.check("csv unclosed quote rejected", ClipIO.parse("text\n\"abc", "x.csv", false).error != null);
        ClipRepoTest.check("csv without header uses first column", ClipIO.parse("مرحبا,1\nثاني,2\n", "y.csv", false).items.size() == 2);

        // TXT
        String txt = out(ClipIO.F_TXT, all);
        ClipIO.Parsed pt = ClipIO.parse(txt, "x.txt", false);
        ClipRepoTest.check("txt separator round trip (texts)", pt.items.size() == 4 && containsRec(pt, tricky.replace("\r\n", "\n")) && containsRec(pt, big.replace("\r\n", "\n")));
        ClipRepoTest.check("txt plain file = one item / lines option", ClipIO.parse("سطر1\nسطر2\n", "a.txt", false).items.size() == 1
                && ClipIO.parse("سطر1\n\nسطر2\n", "a.txt", true).items.size() == 2);

        // invalid / hostile input
        ClipRepoTest.check("broken json rejected", ClipIO.parse("{\"format\": \"neokey-clipboard\", \"schema\": 1, \"items\": [", "a.json", false).error != null);
        // the wrong-format probe carries a VALID item: only the format check can
        // reject it (an empty items list would trip the no-texts path instead —
        // the W47 negative proof caught exactly this masking)
        ClipRepoTest.check("wrong format rejected", ClipIO.parse("{\"format\": \"other\", \"schema\": 1, \"items\": [{\"text\":\"س\"}]}", "a.json", false).error != null);
        ClipRepoTest.check("newer schema rejected", ClipIO.parse("{\"format\": \"neokey-clipboard\", \"schema\": 9, \"items\": [{\"text\":\"x\"}]}", "a.json", false).error != null);
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append('[');
        ClipRepoTest.check("deep nesting rejected safely", ClipIO.parse(deep.toString(), "a.json", false).error != null);
        ClipIO.Parsed pv = ClipIO.parse("{\"format\":\"neokey-clipboard\",\"schema\":1,\"items\":[{\"text\":\"ok\"},{\"text\":\"   \"},{\"title\":\"no text\"},{\"text\":\"ok\"},\"<script>alert(1)</script>\"]}", "a.json", false);
        ClipIO.markDuplicates(ClipRepoTest.fresh(), pv);
        ClipRepoTest.check("preview counts new/dup/invalid", pv.count(ClipIO.NEW) == 2 && pv.count(ClipIO.DUP_IN_FILE) == 1 && pv.count(ClipIO.INVALID) == 2);
        ClipRepoTest.check("generic json array accepted", ClipIO.parse("[\"a\", {\"text\": \"b\"}]", "g.json", false).items.size() == 2);
        StringBuilder huge = new StringBuilder("[\"");
        for (int i = 0; i < ClipRepo.MAX_LEN + 5; i++) huge.append('x');
        huge.append("\"]");
        ClipIO.Parsed ph = ClipIO.parse(huge.toString(), "h.json", false);
        ClipRepoTest.check("oversized item marked invalid", ph.items.size() == 1 && ph.items.get(0).status == ClipIO.INVALID);

        // TXT separator-line escape: an item containing the separator line must
        // NOT be silently split into two items on re-import (W47 integrity law).
        // The escape inserts a zero-width space inside the dash run.
        String esc = "-\u200B" + ClipIO.TXT_SEP.substring(1);
        String sepText = "قبل الفاصل\n" + ClipIO.TXT_SEP + "\nبعد الفاصل";
        ClipRepo rSep = ClipRepoTest.fresh();
        rSep.add(sepText, now, id);
        rSep.add(ClipIO.TXT_SEP, now + 1, id);          // item that IS the separator
        rSep.add("عنصر عادي", now + 2, id);
        List<ClipIO.Rec> sepAll = ClipIO.load(rSep, ClipIO.SCOPE_ALL, null);
        String sepTxt = out(ClipIO.F_TXT, sepAll);
        ClipIO.Parsed ps = ClipIO.parse(sepTxt, "s.txt", false);
        ClipRepoTest.check("txt separator-line escape keeps item count", ps.error == null && ps.items.size() == 3);
        ClipRepoTest.check("txt escaped occurrences carry the documented ZWSP delta", ps.items.size() == 3
                && containsRec(ps, sepText.replace(ClipIO.TXT_SEP, esc))
                && containsRec(ps, esc)
                && containsRec(ps, "عنصر عادي"));
        String sepJson = out(ClipIO.F_JSON, sepAll);
        ClipRepoTest.check("json stays exact for separator items", ClipIO.parse(sepJson, "s.json", false).items.size() == 3
                && containsRec(ClipIO.parse(sepJson, "s.json", false), sepText)
                && containsRec(ClipIO.parse(sepJson, "s.json", false), ClipIO.TXT_SEP));

        // MAX_ITEMS: a file over the 50,000-item cap is rejected, not truncated
        StringBuilder over = new StringBuilder("{\"format\":\"neokey-clipboard\",\"schema\":1,\"items\":[");
        for (int i = 0; i <= ClipIO.MAX_ITEMS; i++) over.append(i > 0 ? "," : "").append("{\"text\":\"ع\"}");
        over.append("]}");
        ClipIO.Parsed po = ClipIO.parse(over.toString(), "over.json", false);
        ClipRepoTest.check("over-MAX_ITEMS file rejected", po.error != null && po.error.contains("أكثر من"));

        // pin limit respected on import
        ClipRepo r4 = ClipRepoTest.fresh();
        for (int i = 0; i < 99; i++) { r4.add("p" + i, now + i, id); r4.pin(id[0]); }
        StringBuilder pj = new StringBuilder("{\"format\":\"neokey-clipboard\",\"schema\":1,\"items\":[");
        for (int i = 0; i < 3; i++) pj.append(i > 0 ? "," : "").append("{\"text\":\"new").append(i).append("\",\"pinned\":true,\"pin_order\":").append(3 - i).append('}');
        ClipIO.Parsed pp = ClipIO.parse(pj.append("]}").toString(), "p.json", false);
        ClipIO.markDuplicates(r4, pp);
        ClipIO.Report rp = ClipIO.apply(r4, pp.items, ClipIO.DUP_SKIP, now);
        ClipRepoTest.check("import respects 100 pin limit", rp.added == 3 && rp.unpinned == 2 && r4.pinCount() == 100);

        // rollback: a failing write leaves the library untouched
        ClipRepo r5 = ClipRepoTest.fresh();
        r5.add("قديم", now, id);
        final ClipRepo.Sql real = r5.db;
        final int[] n = {0};
        ClipRepo.Sql flaky = new ClipRepo.Sql() {
            public void exec(String s, Object... a2) { real.exec(s, a2); }
            public long insert(String s, Object... a2) { if (++n[0] == 3) throw new RuntimeException("disk full"); return real.insert(s, a2); }
            public List<Object[]> query(String s, Object... a2) { return real.query(s, a2); }
            public void begin() { real.begin(); }
            public void commit() { real.commit(); }
            public void rollback() { real.rollback(); }
        };
        ClipRepo r6 = new ClipRepo(flaky);
        ClipIO.Parsed pf = ClipIO.parse("[\"a\",\"b\",\"c\",\"d\"]", "f.json", false);
        ClipIO.markDuplicates(r6, pf);
        ClipIO.Report rf = ClipIO.apply(r6, pf.items, ClipIO.DUP_SKIP, now);
        ClipRepoTest.check("failed import rolls back completely", rf.failed && r5.totalCount() == 1);

        // ---------------------------------------------------------------- W48 library: tags + shortcut ride the formats
        ClipRepo rT = ClipRepoTest.fresh();
        rT.add("رسالة ترحيب للعملاء", now, id); long ta = id[0];
        rT.add("عنوان بارز", now + 1, id);
        rT.setTags(ta, Arrays.asList("عمل", "جاهز"));
        rT.setShortcut(ta, "ترحيب");
        List<ClipIO.Rec> withT = ClipIO.load(rT, ClipIO.SCOPE_ALL, null);
        ClipIO.Rec ra = null;
        for (ClipIO.Rec x : withT) if ("رسالة ترحيب للعملاء".equals(x.text)) ra = x;
        ClipRepoTest.check("load carries tags + shortcut", ra != null && ra.tags.equals(Arrays.asList("عمل", "جاهز")) && "ترحيب".equals(ra.shortcut));
        String jt = out(ClipIO.F_JSON, withT);
        ClipRepoTest.check("json carries tags + shortcut verbatim", jt.contains("\"tags\": [\"عمل\", \"جاهز\"]") && jt.contains("\"shortcut\": \"ترحيب\""));
        ClipIO.Parsed ptw = ClipIO.parse(jt, "t.json", false);
        ClipRepo rT2 = ClipRepoTest.fresh();
        ClipIO.markDuplicates(rT2, ptw);
        ClipIO.apply(rT2, ptw.items, ClipIO.DUP_SKIP, now);
        long ia = -1;
        for (ClipItem x : rT2.list(0, 10)) if ("رسالة ترحيب للعملاء".equals(x.preview)) ia = x.id;
        ClipRepoTest.check("json import restores tags + shortcut", ia > 0 && rT2.tagsOf(ia).equals(Arrays.asList("عمل", "جاهز"))
                && "ترحيب".equals(rT2.shortcutOf(ia)));
        ClipRepoTest.check("tag search works right after import", rT2.search(ClipRepo.TAB_ALL, "جاهز", 10).size() == 1
                && rT2.searchCount(ClipRepo.TAB_ALL, "جاهز") == 1);
        String ct = out(ClipIO.F_CSV, withT);
        ClipIO.Parsed pcv = ClipIO.parse(ct, "t.csv", false);
        ClipIO.Cand candT = null;
        for (ClipIO.Cand cd : pcv.items) if ("رسالة ترحيب للعملاء".equals(cd.rec.text)) candT = cd;
        ClipRepoTest.check("csv round trip keeps tags + shortcut", candT != null && candT.rec.tags.equals(Arrays.asList("عمل", "جاهز")) && "ترحيب".equals(candT.rec.shortcut));
        // import conflict law: the file's first holder keeps the token, later ones import without it
        ClipIO.Parsed pcf = ClipIO.parse("{\"format\":\"neokey-clipboard\",\"schema\":1,\"items\":[{\"text\":\"أول\",\"shortcut\":\"مفتاح\"},{\"text\":\"ثانٍ\",\"shortcut\":\"مفتاح\"}]}", "c.json", false);
        ClipRepo rT3 = ClipRepoTest.fresh();
        ClipIO.markDuplicates(rT3, pcf);
        ClipIO.Report rf2 = ClipIO.apply(rT3, pcf.items, ClipIO.DUP_SKIP, now);
        long f1 = -1, f2 = -1;
        for (ClipItem x : rT3.list(0, 10)) { if ("أول".equals(x.preview)) f1 = x.id; if ("ثانٍ".equals(x.preview)) f2 = x.id; }
        ClipRepoTest.check("import keeps one shortcut owner", !rf2.failed && rf2.added == 2 && "مفتاح".equals(rT3.shortcutOf(f1)) && rT3.shortcutOf(f2) == null);

        // perf: export + parse 1000 mixed items
        ClipRepo r7 = ClipRepoTest.fresh();
        for (int i = 0; i < 1000; i++) r7.add(ClipRepoTest.mixed(200 + i % 50) + i, now + i, id);
        long t0 = System.nanoTime();
        String j7 = out(ClipIO.F_JSON, ClipIO.load(r7, ClipIO.SCOPE_ALL, null));
        long t1 = System.nanoTime();
        ClipIO.Parsed p7 = ClipIO.parse(j7, "p.json", false);
        ClipRepo r8 = ClipRepoTest.fresh();
        ClipIO.markDuplicates(r8, p7);
        ClipIO.Report r8r = ClipIO.apply(r8, p7.items, ClipIO.DUP_SKIP, now);
        long t2 = System.nanoTime();
        System.out.printf("PERF export 1000: %d ms, parse+dup+import 1000: %d ms%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        ClipRepoTest.check("1000 items import", r8r.added == 1000 && r8.totalCount() == 1000);
    }

    static boolean containsText(List<ClipIO.Rec> l, String t) { for (ClipIO.Rec r : l) if (t.equals(r.text)) return true; return false; }
    static boolean containsRec(ClipIO.Parsed p, String t) { for (ClipIO.Cand c : p.items) if (t.equals(c.rec.text)) return true; return false; }
}
