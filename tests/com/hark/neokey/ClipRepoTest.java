package com.hark.neokey;

import java.sql.*;
import java.util.*;

/** Plain-JVM tests: the real ClipRepo code on real SQLite (xerial JDBC). Run: tests/run.sh */
public class ClipRepoTest {
    static int pass = 0, fail = 0;

    static final class JdbcSql implements ClipRepo.Sql {
        final Connection c;
        JdbcSql(Connection c) { this.c = c; }
        PreparedStatement ps(String sql, Object[] a) throws SQLException {
            PreparedStatement p = c.prepareStatement(sql);
            for (int i = 0; i < a.length; i++) p.setObject(i + 1, a[i]);
            return p;
        }
        public void exec(String sql, Object... a) { try (PreparedStatement p = ps(sql, a)) { p.execute(); } catch (SQLException e) { throw new RuntimeException(e); } }
        public long insert(String sql, Object... a) {
            try (PreparedStatement p = ps(sql, a)) { p.executeUpdate();
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) { r.next(); return r.getLong(1); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        public List<Object[]> query(String sql, Object... a) {
            // mimic Android rawQuery: every bind arg is passed as a String
            Object[] s = new Object[a.length];
            for (int i = 0; i < a.length; i++) s[i] = String.valueOf(a[i]);
            List<Object[]> out = new ArrayList<>();
            try (PreparedStatement p = ps(sql, s); ResultSet r = p.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) { Object[] row = new Object[n]; for (int i = 0; i < n; i++) row[i] = r.getObject(i + 1); out.add(row); }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return out;
        }
        public void begin() { try { c.setAutoCommit(false); } catch (SQLException e) { throw new RuntimeException(e); } }
        public void commit() { try { c.commit(); c.setAutoCommit(true); } catch (SQLException e) { throw new RuntimeException(e); } }
        public void rollback() { try { c.rollback(); c.setAutoCommit(true); } catch (SQLException e) { throw new RuntimeException(e); } }
    }

    static ClipRepo fresh() throws Exception {
        Class.forName("org.sqlite.JDBC");
        JdbcSql s = new JdbcSql(DriverManager.getConnection("jdbc:sqlite::memory:"));
        ClipRepo.createSchema(s);
        return new ClipRepo(s);
    }

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static String mixed(int n) {
        String unit = "بِسْمِ اللَّهِ Hello, \"world\"\t😀👨‍👩‍👧 ١٢٣\n\nسطر جديد، 中文; a,b\r\n";
        StringBuilder b = new StringBuilder(n + unit.length());
        while (b.length() < n) b.append(unit);
        b.setLength(n);
        if (Character.isHighSurrogate(b.charAt(n - 1))) b.setCharAt(n - 1, 'x');
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        long now = 1_700_000_000_000L;

        ClipRepo r = fresh();
        long[] id = new long[1];
        check("short text saved", r.add("مرحبا", now, id) == ClipRepo.OK && "مرحبا".equals(r.getText(id[0])));

        for (int n : new int[]{100, 10_000, 100_000, ClipRepo.MAX_LEN}) {
            String t = mixed(n);
            int res = r.add(t, now + n, id);
            check(n + " chars round-trip exact (Arabic, tashkeel, emoji, tabs, CRLF)", res == ClipRepo.OK && t.equals(r.getText(id[0])) && r.item(id[0]).len == n);
        }
        check("over limit rejected, not truncated", r.add(mixed(ClipRepo.MAX_LEN + 1), now, id) == ClipRepo.TOO_LARGE);
        check("empty rejected", r.add("", now, id) == ClipRepo.EMPTY && r.add("  \n ", now, id) == ClipRepo.EMPTY);
        check("legacy SEP char kept", r.add("a\u0001b", now, id) == ClipRepo.OK && "a\u0001b".equals(r.getText(id[0])));

        // preview never loads full text
        ClipItem big = null;
        for (ClipItem c : r.list(100)) if (c.len == 100_000) big = c;
        check("list preview is capped", big != null && big.preview.codePointCount(0, big.preview.length()) == ClipItem.PREVIEW && big.preview.length() < 400 && big.isLong());

        // dedupe + bump
        r = fresh();
        r.add("one", now, id); long first = id[0];
        r.add("two", now + 1, id);
        check("same text again = bump, no duplicate", r.add("one", now + 2, id) == ClipRepo.BUMPED && id[0] == first && r.totalCount() == 2);
        check("bumped item moves to top", r.list(10).get(0).id == first);

        // pins: 100 limit
        r = fresh();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) { r.add("clip " + i, now + i, id); ids.add(id[0]); }
        boolean allOk = true;
        for (int i = 0; i < 100; i++) allOk &= r.pin(ids.get(i)) == ClipRepo.PIN_OK;
        check("100 pins accepted", allOk && r.pinCount() == 100);
        check("pin 101 refused", r.pin(ids.get(100)) == ClipRepo.PIN_FULL && r.pinCount() == 100);
        check("pinning twice is a no-op", r.pin(ids.get(0)) == ClipRepo.PIN_ALREADY && r.pinCount() == 100);
        String before = r.getText(ids.get(5));
        check("refusal loses nothing", r.totalCount() == 101 && "clip 5".equals(before));
        long last = r.lastPin();
        check("replace pin swaps atomically", r.replacePin(last, ids.get(100)) == ClipRepo.PIN_OK && r.pinCount() == 100
                && !r.item(last).pinned && r.item(ids.get(100)).pinned);
        r.unpin(ids.get(3));
        check("unpin", !r.item(ids.get(3)).pinned && r.pinCount() == 99);
        check("re-pin goes to end", r.pin(ids.get(3)) == ClipRepo.PIN_OK && r.pinnedList().get(99).id == ids.get(3));
        r.movePin(ids.get(3), 0);
        List<ClipItem> pl = r.pinnedList();
        check("move pin to first", pl.get(0).id == ids.get(3) && pl.get(1).id == ids.get(0));
        check("list shows pins first in order", r.list(500).get(0).id == ids.get(3) && r.list(500).get(99).pinned && !r.list(500).get(100).pinned);

        // retention keeps pins
        r = fresh();
        r.add("keep pinned", now, id); r.pin(id[0]); long pinned = id[0];
        r.add("keep fav", now, id); r.setFav(id[0], true); long fav = id[0];
        r.add("keep titled", now, id); r.setTitle(id[0], "عنوان", now);
        for (int i = 0; i < 60; i++) r.add("temp " + i, now + 10 + i, null);
        r.prune(50, 0, now + 100);
        check("prune keeps 50 temp + pinned/fav/titled", r.totalCount() == 53 && r.item(pinned) != null && r.item(fav) != null);
        r.prune(50, 86_400_000L, now + 86_400_000L + 40);
        check("age prune removes old temps only", r.totalCount() == 33);
        check("clearTemporary keeps pinned", r.clearTemporary() > 0 && r.totalCount() == 3 && r.item(pinned).pinned);
        check("clearAll removes pinned", r.clearAllClips() == 3 && r.totalCount() == 0);

        // editing + versions
        r = fresh();
        r.add("النص الأصلي", now, id);
        check("edit saved", r.updateText(id[0], "نص معدّل 😀", now + 1) == ClipRepo.OK && "نص معدّل 😀".equals(r.getText(id[0])));
        check("version kept", r.versionCount(id[0]) == 1);
        check("restore previous", r.restorePrevious(id[0], now + 2) == ClipRepo.OK && "النص الأصلي".equals(r.getText(id[0])));
        for (int i = 0; i < 15; i++) r.updateText(id[0], "v" + i, now + 3 + i);
        check("versions capped at 10", r.versionCount(id[0]) == ClipRepo.MAX_VERSIONS);
        check("edit to empty refused, source unchanged", r.updateText(id[0], "", now) == ClipRepo.EMPTY && "v14".equals(r.getText(id[0])));

        // legacy migration
        r = fresh();
        List<String> legacy = Arrays.asList("newest", "middle", "oldest");
        int made = r.migrateLegacy(legacy, new HashSet<>(Collections.singletonList("middle")), now);
        List<ClipItem> l = r.list(10);
        check("migration moves all, keeps pins first", made == 3 && l.size() == 3 && l.get(0).preview.equals("middle") && l.get(0).pinned
                && l.get(1).preview.equals("newest") && l.get(2).preview.equals("oldest"));
        check("migration is idempotent", r.migrateLegacy(legacy, Collections.<String>emptySet(), now) == 0 && r.totalCount() == 3);

        // delete
        r.delete(l.get(2).id);
        check("delete", r.totalCount() == 2);

        // stress: 1000 items mixed sizes, timed
        r = fresh();
        long t0 = System.nanoTime();
        Random rnd = new Random(1);
        for (int i = 0; i < 1000; i++) r.add(mixed(50 + rnd.nextInt(5000)) + i, now + i, null);
        long t1 = System.nanoTime();
        List<ClipItem> top = r.list(400);
        long t2 = System.nanoTime();
        check("1000 items stored", r.totalCount() == 1000 && top.size() == 400);
        System.out.printf("PERF insert 1000 mixed: %d ms, list(400): %d ms (JVM, in-memory SQLite)%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

        // ---- phase 3/4: search, tabs, schema upgrade, editor text ops
        r = fresh();
        r.add("السَّلامُ عليكم ورحمة الله", now, id); long ar = id[0];
        r.add("Meeting at 10, Room B", now + 1, id); long en = id[0];
        r.add(mixed(100_000) + " إبرة_مخفية", now + 2, id); long big2 = id[0];
        check("search ignores tashkeel", r.search(0, "السلام", 10).size() == 1 && r.search(0, "السلام", 10).get(0).id == ar);
        check("search with tashkeel in query", r.searchCount(0, "السَّلام") == 1);
        check("search case-insensitive", r.searchCount(0, "meeting") == 1 && r.searchCount(0, "ROOM b") == 1);
        check("alef forms unified", r.searchCount(0, "ابرة_مخفية") == 1);
        ClipItem hit = r.search(0, "ابرة_مخفية", 10).get(0);
        check("hit deep inside 100k text gives snippet with match", hit.id == big2 && hit.matchStart >= 0
                && hit.preview.substring(hit.matchStart, hit.matchEnd).contains("إبرة"));
        r.setArchived(en, true);
        check("archived hidden from all, found in archive tab", r.searchCount(0, "meeting") == 0 && r.searchCount(ClipRepo.TAB_ARCHIVE, "meeting") == 1);
        r.setFav(ar, true);
        check("fav tab", r.list(ClipRepo.TAB_FAV, 10).size() == 1);
        r.setTitle(big2, "Welcome message", now);
        check("search matches title", r.searchCount(0, "welcome") == 1);
        long[] tq = new long[1];
        long t0s = System.nanoTime();
        for (int i = 0; i < 20; i++) r.searchCount(0, "شيء غير موجود " + i);
        System.out.printf("PERF 20 searches over 100k text: %d ms%n", (System.nanoTime() - t0s) / 1_000_000);

        // upgrade v1 -> v2 keeps data and fills norm
        JdbcSql s1 = new JdbcSql(DriverManager.getConnection("jdbc:sqlite::memory:"));
        for (String c : ClipRepo.CREATE) s1.exec(c.replace(",norm TEXT)", ")").replace("tags TEXT,norm TEXT", "tags TEXT"));
        boolean hasNorm = false;
        for (Object[] row : s1.query("PRAGMA table_info(clips)")) if ("norm".equals(row[1])) hasNorm = true;
        s1.exec("INSERT INTO clips(text,hash,len,created,modified,used,pinned,pin_order) VALUES('مُحَمَّد',?,6,1,1,1,1,1)", ClipRepo.hash("x"));
        ClipRepo.upgradeTo2(s1);
        ClipRepo r1 = new ClipRepo(s1);
        check("v1 schema had no norm", !hasNorm);
        check("upgrade keeps rows and pins, search works", r1.totalCount() == 1 && r1.pinCount() == 1 && r1.searchCount(0, "محمد") == 1);

        // editor ops
        String doc = "قال: السَّلام عليكم. السلام! Peace, peace and PEACE. سلامة";
        check("find tashkeel-insensitive", ClipText.findAll(doc, "السلام", false, false, true).size() == 2);
        check("find exact without ignoring marks", ClipText.findAll(doc, "السلام", true, false, false).size() == 1);
        check("find case-sensitive", ClipText.findAll(doc, "peace", true, false, false).size() == 1);
        check("find case-insensitive", ClipText.findAll(doc, "peace", false, false, false).size() == 3);
        check("whole word", ClipText.findAll(doc, "سلام", false, true, true).size() == 0 && ClipText.findAll(doc, "سلام", false, false, true).size() == 3);
        List<int[]> sp = ClipText.findAll(doc, "peace", false, false, false);
        check("replace all", ClipText.replaceSpans(doc, sp, "سلام").equals("قال: السَّلام عليكم. السلام! سلام, سلام and سلام. سلامة"));
        String big3 = mixed(100_000);
        long tf = System.nanoTime();
        int nh = ClipText.findAll(big3, "سطر", false, false, true).size();
        System.out.printf("PERF find in 100k (marks-insensitive): %d hits, %d ms%n", nh, (System.nanoTime() - tf) / 1_000_000);
        check("find in 100k text", nh > 100);
        int[] cnt = ClipText.counts("سطر 😀\nline two");
        check("counts chars/words/lines (emoji = 1)", cnt[0] == 14 && cnt[1] == 4 && cnt[2] == 2);

        // ---- phase 3 completion: categories, templates, paging, pin drag target
        r = fresh();
        long[] cids = new long[6];
        for (int i = 0; i < 6; i++) { r.add("نص رقم " + i, now + i, id); cids[i] = id[0]; }
        long work = r.addCategory("عمل"), work2 = r.addCategory(" عمل ");
        check("category name unique (trimmed)", work > 0 && work == work2 && r.categories().size() == 1);
        r.setCategory(cids[0], work, true); r.setCategory(cids[1], work, true); r.setCategory(cids[1], work, true);
        check("category list and count", r.list(ClipRepo.TAB_CATS, work, 10).size() == 2 && r.categories().get(0).count == 2);
        check("categorised items survive clearTemporary", r.clearTemporary() == 4 && r.totalCount() == 2);
        check("search inside category", r.searchCount(ClipRepo.TAB_CATS, work, "رقم 1") == 1);
        check("rename rejects duplicate", r.addCategory("شخصي") > 0 && !r.renameCategory(work, "شخصي") && r.renameCategory(work, "العمل"));
        r.deleteCategory(work);
        check("delete category keeps texts", r.totalCount() == 2 && r.categoriesOf(cids[0]).isEmpty());
        long tpl = r.makeTemplate(cids[0], now), tpl2 = r.makeTemplate(cids[0], now);
        check("template copy, no duplicate", tpl > 0 && tpl == tpl2 && r.list(ClipRepo.TAB_TEMPLATES, 10).size() == 1);
        check("template not in all tab, survives clear history", r.list(ClipRepo.TAB_ALL, 10).size() == 2 && r.clearTemporary() == 2 && "نص رقم 0".equals(r.getText(tpl)));
        r = fresh();
        for (int i = 0; i < 130; i++) r.add("عنصر " + i, now + i, id);
        check("paged loading: limit 60 then 120", r.list(0, 60).size() == 60 && r.list(0, 120).size() == 120 && r.list(0, 120).get(60).preview.equals("عنصر 69"));
        long[] p = new long[5];
        List<ClipItem> all = r.list(0, 5);
        for (int i = 0; i < 5; i++) { p[i] = all.get(i).id; r.pin(p[i]); }
        r.movePin(p[4], 0);
        List<ClipItem> ppl = r.pinnedList();
        check("drag reorder to absolute index", ppl.get(0).id == p[4] && ppl.get(1).id == p[0] && ppl.size() == 5);

        // ---------------------------------------------------------------- W48 library: tags
        r = fresh();
        r.add("نص وسم", now, id); long tg1 = id[0];
        r.add("نص وسم آخر", now + 1, id); long tg2 = id[0];
        r.add("نص ثالث", now + 2, id); long tg3 = id[0];
        r.setTags(tg1, Arrays.asList(" عمل ", "خاص\nمزدوج", "عمل"));
        check("tags normalized (trim, single-line, dedupe)", r.tagsOf(tg1).equals(Arrays.asList("عمل", "خاص مزدوج")));
        r.setTags(tg2, Arrays.asList("عمل", "مهم"));
        check("second item carries its own tags", r.tagsOf(tg2).equals(Arrays.asList("عمل", "مهم")));
        List<String> tl = r.listTags();
        check("listTags distinct live tags", tl.size() == 3 && tl.contains("عمل") && tl.contains("مهم") && tl.contains("خاص مزدوج"));
        StringBuilder longTag = new StringBuilder();
        for (int i = 0; i < 30; i++) longTag.append('س');
        check("tag capped at 24 chars", ClipRepo.normTag(longTag.toString()).length() == ClipRepo.MAX_TAG);
        List<String> manyTags = new ArrayList<>();
        for (int i = 0; i < 20; i++) manyTags.add("وسم" + i);
        r.setTags(tg3, manyTags);
        check("tag count capped at 12", r.tagsOf(tg3).size() == ClipRepo.MAX_TAGS && r.listTags().size() == 15);
        check("text hit wins over tag hit", r.search(ClipRepo.TAB_ALL, "وسم", 10).size() == 2 && r.searchCount(ClipRepo.TAB_ALL, "وسم") == 2);
        check("tag fallback finds tagged only (no span)", r.search(ClipRepo.TAB_ALL, "مهم", 10).size() == 1
                && r.search(ClipRepo.TAB_ALL, "مهم", 10).get(0).id == tg2
                && r.search(ClipRepo.TAB_ALL, "مهم", 10).get(0).matchStart == -1
                && r.searchCount(ClipRepo.TAB_ALL, "مهم") == 1);
        check("tag fallback respects the tab", r.search(ClipRepo.TAB_FAV, "مهم", 10).isEmpty() && r.searchCount(ClipRepo.TAB_FAV, "مهم") == 0);
        r.pin(tg2);
        check("tag fallback inside pinned tab", r.search(ClipRepo.TAB_PINNED, "مهم", 10).size() == 1);
        r.setTags(tg1, new ArrayList<>());
        check("empty set clears tags + count drops", r.tagsOf(tg1).isEmpty() && r.listTags().size() == 14);
        // W48 retention law: tagged and shortcut-carrying items are curated — they survive the policy
        r = fresh();
        r.add("معلوم بوسم", now, id); long k1 = id[0]; r.setTags(k1, Arrays.asList("دائم"));
        r.add("مع اختصار", now + 1, id); long k2 = id[0]; r.setShortcut(k2, "سريع");
        r.add("عابر 1", now + 2, id);
        r.add("عابر 2", now + 3, id);
        check("retention keeps tagged + shortcut, deletes plain", r.clearTemporary() == 2 && r.totalCount() == 2
                && r.tagsOf(k1).equals(Arrays.asList("دائم")) && "سريع".equals(r.shortcutOf(k2)));

        // ---------------------------------------------------------------- W48 library: shortcuts
        r = fresh();
        r.add("نص الاختصار", now, id); long sc1 = id[0];
        r.add("نص آخر", now + 1, id); long sc2 = id[0];
        check("shortcut set + live map", r.setShortcut(sc1, "تحية") && r.shortcutMap().size() == 1
                && r.shortcutMap().get("تحية") == sc1);
        check("shortcut lowercased", r.setShortcut(sc2, "Hi") && "hi".equals(r.shortcutOf(sc2)));
        check("whitespace token rejected, old value stays", !r.setShortcut(sc2, "كلمة واحدة") && "hi".equals(r.shortcutOf(sc2)));
        StringBuilder longSc = new StringBuilder();
        for (int i = 0; i < 33; i++) longSc.append('س');
        check("over-32 rejected", !r.setShortcut(sc2, longSc.toString()));
        check("duplicate token rejected, first owner keeps", !r.setShortcut(sc2, "تحية") && "تحية".equals(r.shortcutOf(sc1)) && "hi".equals(r.shortcutOf(sc2)));
        check("same owner may re-set", r.setShortcut(sc1, "ترحيب") && "ترحيب".equals(r.shortcutOf(sc1)) && !r.shortcutMap().containsKey("تحية"));
        check("blank token clears", r.setShortcut(sc2, "hi") && r.setShortcut(sc2, "  ") && r.shortcutOf(sc2) == null && r.shortcutMap().size() == 1);
        long tplSc = r.makeTemplate(sc1, now);
        check("templates stay outside the shortcut domain", !r.setShortcut(tplSc, "قالب") && r.shortcutOf(tplSc) == null);
        check("unknown id rejected", !r.setShortcut(9999, "ما فيش"));

        // ---------------------------------------------------------------- W48 library: text tools
        check("tidy collapses runs + trims line edges", ClipText.tidy("  أهلاً   بالعالم  \n\n\nسطر\tثانٍ  ").equals("أهلاً بالعالم\n\nسطر ثانٍ"));
        check("tidy keeps one blank line between paragraphs", ClipText.tidy("أ\n\nب").equals("أ\n\nب"));
        check("tidy drops edge blanks only", ClipText.tidy("\n\n أ \n\n\n").equals("أ"));
        check("stripMarks drops tashkeel keeping letters", ClipText.stripMarks("مُحَمَّد").equals("محمد"));

        // perf: the W48 library operations on a 1000-item library (the number is read AFTER the colon)
        ClipRepo rp = fresh();
        for (int i = 0; i < 1000; i++) rp.add("عنصر مكتبة " + i, now + i, id);
        long p0 = System.nanoTime();
        for (int i = 0; i < 100; i++) rp.setTags(id[0], Arrays.asList("وسم" + (i % 7), "دائم"));
        long p1 = System.nanoTime();
        java.util.Map<String, Long> sm = rp.shortcutMap();
        long p2 = System.nanoTime();
        System.out.printf("PERF tags set 100: %d ms, shortcut map build 1000: %d ms%n", (p1 - p0) / 1_000_000, (p2 - p1) / 1_000_000);
        check("library perf run leaves the map empty", sm.isEmpty());

        ClipIOTest.run(now);
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
