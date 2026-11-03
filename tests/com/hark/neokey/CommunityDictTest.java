package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Week 17 tests — community dictionary enrichment pipeline (Phase 2, item D).
 * 36 tests: clean batch, every refusal tag, file-level refusals, CSV quirks,
 * report metrics, contributors section, and a 128-seed fuzz sweep.
 */
public final class CommunityDictTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        cleanBatch();
        fileLevelRefusals();
        rowRules();
        csvQuirks();
        reportChecks();
        contributorsCheck();
        fuzz();
        System.out.println("CommunityDictTest: RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ helpers

    static String row(String word, String diac, String lemma, String pos,
                      String gloss, String contrib, String lic) {
        return word + "," + diac + "," + lemma + "," + pos + "," + gloss + "," + contrib + "," + lic;
    }

    static byte[] csv(String... dataRows) {
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\n");
        for (String r : dataRows) b.append(r).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final String G = "noun";
    static final String L = "CC0-1.0";

    static CommunityDict.Row find(CommunityDict.Report rep, int idx) {
        return rep.rows.get(idx);
    }

    static void ok(boolean cond, String name) {
        if (cond) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static void tagIs(CommunityDict.Report rep, int idx, String expected, String name) {
        String got = rep.rows.get(idx).tag;
        ok(java.util.Objects.equals(expected, got), name + " [expected " + expected + " got " + got + "]");
    }

    static final java.util.Set<String> INSTALLED =
            CommunityDict.normSetOf(Arrays.asList("في", "من", "سيارة", "طائرة", "سلام"));

    // ------------------------------------------------------------ 1: clean

    static void cleanBatch() {
        CommunityDict.Report r = CommunityDict.review(csv(
                row("كتاب", "كِتَاب", "كِتَاب", G, "thing you read", "amina", L),
                row("مدرسة", "مَدْرَسَة", "مَدْرَسَة", "noun", "place of learning", "kareem", "CC-BY-4.0"),
                row("جميل", "جَمِيل", "جَمِيل", "adj", "beautiful", "amina", L)), INSTALLED);
        ok(r.total == 3 && r.accepted == 3 && r.rejected == 0, "clean-3-accepted");
        ok(r.batchId != null && r.batchId.length() == 64, "clean-batchid-64hex");
        ok(r.p50Nanos > 0, "clean-p50-measured");
    }

    // ------------------------------------------------------------ 2: file-level

    static void fileLevelRefusals() {
        // header missing a column
        CommunityDict.Report r1 = CommunityDict.review(
                "word,diacritized,lemma,pos,gloss,contributor\nكتاب,كِتَاب,كِتَاب,noun,x,amina\n".getBytes(StandardCharsets.UTF_8),
                INSTALLED);
        ok(r1.rejected == r1.total && r1.total == 1, "bad-header-missing-all-rejected");
        tagIs(r1, 0, CommunityDict.T_BAD_HEADER, "bad-header-missing-tag");

        // header wrong order
        CommunityDict.Report r2 = CommunityDict.review(csv(
                row("كتاب", "كِتَاب", "كِتَاب", G, "xx", "amina", L)), INSTALLED);
        ok(r2.accepted == 1, "good-header-parses");

        StringBuilder wrong = new StringBuilder("diacritized,word,lemma,pos,gloss,contributor,license\n");
        wrong.append("كِتَاب,كتاب,كِتَاب,noun,x,amina,").append(L).append('\n');
        CommunityDict.Report r3 = CommunityDict.review(wrong.toString().getBytes(StandardCharsets.UTF_8), INSTALLED);
        tagIs(r3, 0, CommunityDict.T_BAD_HEADER, "bad-header-order-tag");

        // empty file
        CommunityDict.Report r4 = CommunityDict.review(new byte[0], INSTALLED);
        ok(r4.rejected == 0 && r4.total == 0, "empty-file-zero-rows");
        ok(r4.tags.containsKey(CommunityDict.T_BAD_HEADER), "empty-file-tagged");

        // BOM rejected
        byte[] body = csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x", "amina", L));
        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        CommunityDict.Report r5 = CommunityDict.review(bom, INSTALLED);
        ok(r5.total == 1 && r5.rejected == 1, "bom-all-rejected");
        tagIs(r5, 0, CommunityDict.T_BAD_ENCODING, "bom-tag");

        // invalid UTF-8
        byte[] bad = csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x", "amina", L));
        bad[bad.length - 1] = (byte) 0xFF;
        CommunityDict.Report r6 = CommunityDict.review(bad, INSTALLED);
        ok(r6.rejected >= 1, "invalid-utf8-rejected");
        ok(r6.tags.containsKey(CommunityDict.T_BAD_ENCODING), "invalid-utf8-tag");
    }

    // ------------------------------------------------------------ 3: row rules

    static void rowRules() {
        // ROW_FIELDS: 6 fields
        CommunityDict.Report r = CommunityDict.review(
                ("word,diacritized,lemma,pos,gloss,contributor,license\n"
                        + "كتاب,كِتَاب,كِتَاب,noun,x,amina\n").getBytes(StandardCharsets.UTF_8), INSTALLED);
        tagIs(r, 0, CommunityDict.T_ROW_FIELDS, "row-fields-tag");

        // EMPTY_WORD
        r = CommunityDict.review(csv(row("", "كِتَاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_EMPTY_WORD, "empty-word-tag");

        // HAS_TASHKEEL_IN_WORD
        r = CommunityDict.review(csv(row("كِتاب", "كِتَاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_TASHKEEL, "tashkeel-in-word-tag");

        // NOT_ARABIC: latin / digits / tatweel / leading space
        r = CommunityDict.review(csv(row("hello", "hello", "hello", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NOT_ARABIC, "latin-tag");
        r = CommunityDict.review(csv(row("كتاب123", "كتاب123", "كِتاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NOT_ARABIC, "digits-tag");
        r = CommunityDict.review(csv(row("كتـــاب", "كِتَاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NOT_ARABIC, "tatweel-tag");
        r = CommunityDict.review(csv(row(" كتاب", "كِتَاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NOT_ARABIC, "leading-space-tag");
        r = CommunityDict.review(csv(row("ك", "كَ", "كَ", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NOT_ARABIC, "single-letter-tag");

        // NO_DIACRITICS: diacritized == word
        r = CommunityDict.review(csv(row("كتاب", "كتاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_NO_DIAC, "no-diacritics-tag");

        // BAD_DIACRITICS: skeleton mismatch (missing letter, different letter, extra letter)
        r = CommunityDict.review(csv(row("كتاب", "كِتَا", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_DIAC, "bad-diac-short-tag");
        r = CommunityDict.review(csv(row("كتاب", "كِتَار", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_DIAC, "bad-diac-wrong-letter-tag");
        r = CommunityDict.review(csv(row("كتاب", "كِتَابَا", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_DIAC, "bad-diac-long-tag");

        // partial diacritics (some letters marked) — accepted, skeleton intact
        r = CommunityDict.review(csv(row("كتاب", "كِتاب", "كِتَاب", G, "xx", "amina", L)), INSTALLED);
        tagIs(r, 0, null, "partial-diacritics-accepted");

        // DUPLICATE inside batch: exact + normalization trick (ة/ه)
        r = CommunityDict.review(csv(
                row("كتاب", "كِتَاب", "كِتَاب", G, "xx", "amina", L),
                row("كتاب", "كَتَاب", "كِتَاب", G, "yy", "kareem", L)), INSTALLED);
        tagIs(r, 1, CommunityDict.T_DUPLICATE, "dup-internal-exact");
        r = CommunityDict.review(csv(
                row("مدرسة", "مَدْرَسَة", "مَدْرَسَة", G, "xx", "amina", L),
                row("مدرسه", "مَدْرَسَه", "مَدْرَسَه", G, "yy", "kareem", L)), INSTALLED);
        tagIs(r, 1, CommunityDict.T_DUPLICATE, "dup-internal-norm-trick");

        // DUPLICATE against installed dictionary
        r = CommunityDict.review(csv(row("في", "فِي", "فِي", "particle", "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_DUPLICATE, "dup-installed-tag");
        r = CommunityDict.review(csv(row("مدرسات", "مَدْرَسَات", "مَدْرَسَات", G, "xx", "amina", L)), INSTALLED);
        tagIs(r, 0, null, "not-installed-accepted");

        // FORBIDDEN: direct + diacritized + gloss
        r = CommunityDict.review(csv(row("حقير", "حَقِير", "حَقِير", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_FORBIDDEN, "forbidden-direct");
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", G, "انا غبي جدا", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_FORBIDDEN, "forbidden-in-gloss");
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "خنزير", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_FORBIDDEN, "forbidden-in-lemma");

        // BAD_POS
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", "pronoun", "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_POS, "bad-pos-tag");
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", "", "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_POS, "empty-pos-tag");

        // BAD_LEMMA: empty + latin
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_LEMMA, "empty-lemma-tag");
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "book", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_LEMMA, "latin-lemma-tag");

        // BAD_GLOSS
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x", "amina", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_GLOSS, "short-gloss-tag");

        // BAD_CONTRIB: short, bad chars
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x y", "ab", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_CONTRIB, "short-contrib-tag");
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x y", "amina ali!", L)), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_CONTRIB, "bad-contrib-chars");

        // BAD_LICENSE
        r = CommunityDict.review(csv(row("كتاب", "كِتَاب", "كِتَاب", G, "x y", "amina", "GPL-3.0")), INSTALLED);
        tagIs(r, 0, CommunityDict.T_BAD_LICENSE, "bad-license-tag");
    }

    // ------------------------------------------------------------ 4: csv quirks

    static void csvQuirks() {
        // quoted gloss containing a comma
        String line = "كتاب,كِتَاب,كِتَاب,noun,\"shiny, new gloss\",amina," + L;
        CommunityDict.Report r = CommunityDict.review(
                ("word,diacritized,lemma,pos,gloss,contributor,license\n" + line + "\n")
                        .getBytes(StandardCharsets.UTF_8), INSTALLED);
        tagIs(r, 0, null, "quoted-comma-accepted");
        ok(r.rows.get(0).gloss.equals("shiny, new gloss"), "quoted-comma-value");

        // CRLF line endings
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\r\n");
        b.append("مدرسة,مَدْرَسَة,مَدْرَسَة,noun,place,kareem,").append(L).append("\r\n");
        r = CommunityDict.review(b.toString().getBytes(StandardCharsets.UTF_8), INSTALLED);
        ok(r.accepted == 1, "crlf-accepted");

        // blank line tolerated mid-file
        String bl = "word,diacritized,lemma,pos,gloss,contributor,license\n"
                + "كتاب,كِتَاب,كِتَاب,noun,thing,amina," + L + "\n"
                + "\n"
                + "جميل,جَمِيل,جَمِيل,adj,nice,kareem," + L + "\n";
        r = CommunityDict.review(bl.getBytes(StandardCharsets.UTF_8), INSTALLED);
        ok(r.total == 2 && r.accepted == 2, "blank-line-tolerated");

        // escaped quotes inside gloss
        String esc = "كتاب,كِتَاب,كِتَاب,noun,\"the \"\"best\"\" word\",amina," + L;
        r = CommunityDict.review(("word,diacritized,lemma,pos,gloss,contributor,license\n" + esc + "\n")
                .getBytes(StandardCharsets.UTF_8), INSTALLED);
        ok(r.accepted == 1 && r.rows.get(0).gloss.equals("the \"best\" word"), "escaped-quotes");
    }

    // ------------------------------------------------------------ 5: report

    static void reportChecks() {
        CommunityDict.Report r = CommunityDict.review(csv(
                row("كتاب", "كِتَاب", "كِتَاب", G, "thing", "amina", L),
                row("مدرسة", "مَدْرَسَة", "مَدْرَسَة", G, "place", "kareem", "CC-BY-4.0"),
                row("جميل", "جَمِيل", "جَمِيل", "adj", "nice", "amina", L),
                row("hello", "hello", "x", G, "z w", "amina", L)), INSTALLED);
        ok(r.total == 4 && r.accepted == 3 && r.rejected == 1, "report-counts");
        ok(r.tags.get("ACCEPT") == 3 && r.tags.get(CommunityDict.T_NOT_ARABIC) == 1, "report-tag-histogram");
        String j = r.toJson();
        ok(j.contains("\"total\":4") && j.contains("\"accepted\":3") && j.contains("\"rejected\":1"), "json-counts");
        ok(j.contains("\"NOT_ARABIC\":1"), "json-tags");
        ok(j.contains("\"amina\":2") && j.contains("\"kareem\":1"), "json-contributors");

        // deterministic batch id: same bytes → same id, different bytes → different
        byte[] a = csv(row("كتاب", "كِتَاب", "كِتَاب", G, "thing", "amina", L));
        byte[] b2 = csv(row("درس", "دَرْس", "دَرْس", G, "lesson", "amina", L));
        String ida1 = CommunityDict.review(a, INSTALLED).batchId;
        String ida2 = CommunityDict.review(a, INSTALLED).batchId;
        String idb = CommunityDict.review(b2, INSTALLED).batchId;
        ok(ida1.equals(ida2) && !ida1.equals(idb), "batchid-deterministic");
    }

    // ------------------------------------------------------------ 6: contributors

    static void contributorsCheck() {
        CommunityDict.Report r = CommunityDict.review(csv(
                row("كتاب", "كِتَاب", "كِتَاب", G, "thing", "amina", L),
                row("مدرسة", "مَدْرَسَة", "مَدْرَسَة", G, "place", "amina", L),
                row("جميل", "جَمِيل", "جَمِيل", "adj", "nice", "kareem", L),
                row("hello", "hello", "x", G, "z w", "nobody", L)), INSTALLED);
        String md = r.contributorsMd();
        ok(md.contains("# شكر المساهمين"), "thanks-title");
        ok(md.indexOf("amina") < md.indexOf("kareem"), "thanks-sorted-by-count");
        ok(md.contains("| 2 |"), "thanks-count-column");
        ok(!md.contains("nobody"), "thanks-excludes-rejected");
        CommunityDict.Report empty = CommunityDict.review(new byte[0], INSTALLED);
        ok(empty.contributorsMd().contains("لا مساهمين"), "thanks-empty-note");
    }

    // ------------------------------------------------------------ 7: fuzz

    /** Fuzz: rows are clean or sabotaged in a detectable way — sabotage MUST be refused. */
    static void fuzz() {
        String[][] segs = {
                {"ك", "ت", "ب", "ر"}, {"م", "د", "ر", "س"}, {"ج", "م", "ي"}, {"س", "ل", "م"},
                {"ع", "ل", "م"}, {"ق", "ن", "ط", "ر"}, {"ش", "م", "س"}, {"ب", "ح", "ر"}};
        String[] pos = {"noun", "verb", "adj", "adv", "particle"};
        String[] contribs = {"amina", "kareem", "sami", "layan", "سالم7"};
        String[] lic = {"CC0-1.0", "CC-BY-4.0"};
        String[] badPos = {"pronoun", "interj", "", "NOUN"};
        String[] badLic = {"GPL", "MIT", "", "CC-BY-NC"};
        int refusals = 0, totalBad = 0, accepts = 0;
        for (int seed = 1704; seed < 1704 + 128; seed++) {
            Random rnd = new Random(seed);
            StringBuilder body = new StringBuilder();
            List<int[]> expect = new ArrayList<>(); // 0=clean 1=sabotaged
            int n = 3 + rnd.nextInt(6);
            java.util.Set<String> used = new HashSet<>();
            for (int i = 0; i < n; i++) {
                String base = buildWord(segs, rnd);
                // unique inside batch AND not colliding with the installed dict
                while (!used.add(base) || INSTALLED.contains(CommunityDict.normAr(base))) base = base + "ا";
                String word = base, diac = vocalize(base, rnd), lemma = base, gl = "gloss " + i;
                String p = pos[rnd.nextInt(pos.length)], c = contribs[rnd.nextInt(contribs.length)],
                       l = lic[rnd.nextInt(lic.length)];
                boolean sabotage = rnd.nextBoolean();
                if (sabotage) {
                    switch (rnd.nextInt(10)) {
                        case 0: word = "lat" + i; diac = "lat" + i; break;              // NOT_ARABIC
                        case 1: word = base + "1"; diac = base + "1"; break;            // NOT_ARABIC digits
                        case 2: word = "كِت" + base; diac = "كِتَ" + base; break;       // HAS_TASHKEEL (if kept arabic)
                        case 3: diac = base; break;                                     // NO_DIACRITICS
                        case 4: diac = base + "ا"; break;                               // BAD_DIACRITICS (unless word ended with ا)
                        case 5: p = badPos[rnd.nextInt(badPos.length)]; break;          // BAD_POS
                        case 6: l = badLic[rnd.nextInt(badLic.length)]; break;          // BAD_LICENSE
                        case 7: gl = "غبي"; break;                                      // FORBIDDEN
                        case 8: c = "x"; break;                                         // BAD_CONTRIB
                        case 9: lemma = ""; break;                                      // BAD_LEMMA
                        default: break;
                    }
                }
                body.append(row(word, diac, lemma, p, gl, c, l)).append('\n');
                expect.add(new int[]{sabotage ? 1 : 0});
            }
            CommunityDict.Report rep = CommunityDict.review(
                    ("word,diacritized,lemma,pos,gloss,contributor,license\n" + body).getBytes(StandardCharsets.UTF_8),
                    INSTALLED);
            ok(rep.total == n, "fuzz-" + seed + "-rowcount");
            for (int i = 0; i < n; i++) {
                CommunityDict.Row rw = rep.rows.get(i);
                if (expect.get(i)[0] == 1) {
                    totalBad++;
                    if (!rw.accepted()) refusals++;
                    else failed++;
                } else {
                    if (rw.accepted()) accepts++;
                    else {
                        // known benign collisions in the generator itself are not tolerated:
                        failed++;
                        System.out.println("FAIL: fuzz-" + seed + " clean row " + i + " tagged " + rw.tag);
                    }
                }
            }
        }
        ok(totalBad > 0 && refusals == totalBad, "fuzz-all-sabotage-refused (" + refusals + "/" + totalBad + ")");
        ok(accepts > 0, "fuzz-clean-accepted (" + accepts + ")");
    }

    static String buildWord(String[][] segs, Random rnd) {
        int k = 2 + rnd.nextInt(2);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < k; i++) b.append(segs[rnd.nextInt(segs.length)][0]);
        return b.toString() + (rnd.nextBoolean() ? "اب" : "ان");
    }

    static String vocalize(String w, Random rnd) {
        char[] marks = {'َ', 'ُ', 'ِ'};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            b.append(w.charAt(i));
            if (rnd.nextBoolean() && i % 2 == 0) b.append(marks[rnd.nextInt(marks.length)]);
        }
        b.append('ً'); // guarantee at least one mark
        return b.toString();
    }
}
