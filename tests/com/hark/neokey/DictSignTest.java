package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Week 19 tests — signed community-dictionary merge (Phase 3, week 2).
 * Positives: deterministic publishing, byte-exact signature, statement match,
 * engine consumption order (installed first, pack order after, duplicate drop,
 * score cap). Negatives: every designed refusal tag of DictSign.load/publish,
 * including a fully-reforged poison pack caught by row re-validation.
 */
public final class DictSignTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        publishPositives();
        loadPositives();
        engineOrder();
        engineDuplicateDrop();
        negatives();
        System.out.println("DictSignTest: RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ helpers

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static String row(String word, String diac, String lemma, String pos,
                      String gloss, String contrib, String lic) {
        return word + "," + diac + "," + lemma + "," + pos + "," + gloss + "," + contrib + "," + lic;
    }

    static byte[] csv(String... dataRows) {
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\n");
        for (String r : dataRows) b.append(r).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Clean 2-row batch (both rows accepted). */
    static byte[] cleanCsv() {
        return csv(
                row("كتابية", "كِتَابِيَّة", "كتاب", "adj", "متعلقة بالكتاب والتحرير", "سالم-قلم", "CC0-1.0"),
                row("عدادية", "عَدَّادِيَّة", "عداد", "adj", "متعلقة بالعد والقياس", "نورة-حرف", "CC0-1.0"));
    }

    static Set<String> inst(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(CommunityDict.normAr(w));
        return s;
    }

    static DictSign.Dpack publishOf(byte[] csvBytes, Set<String> installed) {
        return DictSign.publish(csvBytes, CommunityDict.review(csvBytes, installed));
    }

    interface F { void run(); }

    static String expect(F f) {
        try { f.run(); return null; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    // ------------------------------------------------------------ positives

    static void publishPositives() {
        System.out.println("== DictSign:publish ==");
        Set<String> installed = inst("سلام");
        byte[] b = cleanCsv();
        CommunityDict.Report rep = CommunityDict.review(b, installed);
        check("clean 2-row batch accepted 2/2", rep.accepted == 2 && rep.rejected == 0);
        DictSign.Dpack d = publishOf(b, installed);
        check("publish carries the report batchId", d.batchId.equals(rep.batchId));
        check("publish counter = accepted count", d.accepted == 2);
        check("dpack sig is a 64-hex digest distinct from batchId", d.sig.length() == 64 && !d.sig.equals(d.batchId));
        check("words sha differs from batch sha", !d.wordsSha.equals(d.batchId));
        DictSign.Dpack d2 = publishOf(b, installed);
        check("publish is byte-deterministic (re-publish identical)", Arrays.equals(d.bytes, d2.bytes));
        check("statement embedded and pinned (report sha)", d.statement.indexOf("\"rows\":") > 0
                && CommunityDict.sha256Hex(d.statement.getBytes(StandardCharsets.UTF_8)).equals(d.reportSha));
        byte[] allBad = csv(row("qqq", "qqq", "qqq", "noun", "غ", "سالم-قلم", "CC0-1.0"));
        String t = expect(() -> DictSign.publish(allBad, CommunityDict.review(allBad, inst())));
        check("publish refuses zero-accepted batch (EMPTY_PACK)", "EMPTY_PACK".equals(t));
        String t2 = expect(() -> DictSign.publish(csv(
                row("كتابية", "كِتَابِيَّة", "كتاب", "adj", "متعلقة بالكتاب والتحرير", "سالم-قلم", "CC0-1.0")),
                rep));
        check("publish refuses foreign report (BATCH_MISMATCH)", "BATCH_MISMATCH".equals(t2));
    }

    static void loadPositives() {
        System.out.println("== DictSign:load ==");
        Set<String> installed = inst("سلام");
        byte[] b = cleanCsv();
        DictSign.Dpack d = publishOf(b, installed);
        DictSign.Pack p = DictSign.load(d.bytes, d.statement, installed);
        check("load with statement passes", p != null && p.words.size() == 2);
        check("pack order = review acceptance order", p.words.get(0).word.equals("كتابية")
                && p.words.get(1).word.equals("عدادية"));
        check("pack batchId matches", p.batchId.equals(d.batchId));
        DictSign.Pack p2 = DictSign.load(d.bytes, null, null);
        check("device path loads without statement", p2.words.size() == 2);
        check("sig survives the round-trip", p.sig.equals(d.sig));
    }

    // ------------------------------------------------------------ engine

    static Dictionary dictIn(File assetsDir, File filesDir) throws Exception {
        Dictionary d = new Dictionary(new FakeContext(assetsDir, filesDir,
                new File(filesDir.getParentFile(), filesDir.getName() + "-cache")), "ar");
        long t0 = System.currentTimeMillis();
        while (!d.isLoaded() && System.currentTimeMillis() - t0 < 15000) Thread.sleep(25);
        return d;
    }

    static File engineAssets(byte[] arTxt, byte[] dpack) throws Exception {
        File dir = Files.createTempDirectory("w19-assets").toFile();
        Files.write(new File(dir, "ar.txt").toPath(), arTxt);
        if (dpack != null) Files.write(new File(dir, "community_ar.dpack").toPath(), dpack);
        return dir;
    }

    static void engineOrder() throws Exception {
        System.out.println("== DictSign:engine consumption order ==");
        byte[] ar = "سلام\nكتاب\nقلم\n".getBytes(StandardCharsets.UTF_8);
        byte[] b = csv(
                row("مفتاحية", "مَفْتَاحِيَّة", "مفتاح", "noun", "كلمة سرية تفتح قفلا رقميا", "سالم-قلم", "CC0-1.0"),
                row("توأمية", "تَوَأُمِيَّة", "توأم", "adj", "متعلقة بتوأم متطابق", "نورة-حرف", "CC0-1.0"),
                row("زمردية", "زُمُرُدِيَّة", "زمرد", "adj", "لون أخضر زاه كالزمرد", "فارس-مجتمع", "CC0-1.0"));
        DictSign.Dpack d = publishOf(b, inst("سلام", "كتاب", "قلم"));
        Dictionary dNo = dictIn(engineAssets(ar, null), Files.createTempDirectory("w19-u1").toFile());
        Dictionary dYes = dictIn(engineAssets(ar, d.bytes), Files.createTempDirectory("w19-u2").toFile());
        check("without pack asset: pack word unknown", !dNo.knows("مفتاحية"));
        check("with pack asset: pack words known", dYes.knows("مفتاحية") && dYes.knows("زمردية"));
        check("pack rank starts after the whole installed corpus (3,4,5)",
                dYes.wordRank("مفتاحية") == 3 && dYes.wordRank("توأمية") == 4 && dYes.wordRank("زمردية") == 5);
        check("installed corpus always precedes the pack",
                dYes.wordRank("سلام") < dYes.wordRank("كتاب") && dYes.wordRank("كتاب") < dYes.wordRank("مفتاحية"));
        check("pack order preserved inside the pack",
                dYes.wordRank("مفتاحية") < dYes.wordRank("توأمية") && dYes.wordRank("توأمية") < dYes.wordRank("زمردية"));
        check("pack score capped at PACK_SCORE (3000)", dYes.wordScore("مفتاحية") == Dictionary.PACK_SCORE);
        check("pack words never fire the exact-match boost (3000 < 22000)",
                dYes.wordScore("مفتاحية") < 22000);
        List<String> sug = dYes.suggest("مفتاح", 5);
        check("suggest surfaces the pack word (after installed rivals)", sug.contains("مفتاحية"));
    }

    static void engineDuplicateDrop() throws Exception {
        System.out.println("== DictSign:installed wins ==");
        byte[] ar = "سلام\nكتاب\nقلم\n".getBytes(StandardCharsets.UTF_8);
        byte[] b = csv(
                row("سلآم", "سَلَام", "سلام", "noun", "تحية البدء والسلام", "بدر-نحو", "CC0-1.0"),
                row("كتابية", "كِتَابِيَّة", "كتاب", "adj", "متعلقة بالكتاب والتحرير", "نورة-حرف", "CC0-1.0"));
        DictSign.Dpack d = publishOf(b, inst("سلام", "كتاب", "قلم"));
        check("review refused the hamza-variant duplicate (1 accepted)", d.accepted == 1
                && d.words.get(0).word.equals("كتابية"));
        Dictionary dYes = dictIn(engineAssets(ar, d.bytes), Files.createTempDirectory("w19-u3").toFile());
        check("merged pack word lands right after the corpus", dYes.knows("كتابية") && dYes.wordRank("كتابية") == 3);
        check("duplicate word still resolves to the installed spelling", dYes.wordRank("سلام") < 3);
        int added = dYes.mergePack(Arrays.asList("قلم", "جديد", "قلم"));
        check("mergePack drops duplicates and reports the honest count", added == 1 && dYes.knows("جديد"));
    }

    // ------------------------------------------------------------ negatives

    static void negatives() throws Exception {
        System.out.println("== DictSign:negatives ==");
        Set<String> installed = inst("سلام");
        byte[] b = cleanCsv();
        DictSign.Dpack d = publishOf(b, installed);
        byte[] raw = d.bytes;
        String stmt = d.statement;

        // 1) one body byte flipped → signature break
        byte[] t1 = raw.clone();
        int wordByte = indexOf(raw, "w ".getBytes(StandardCharsets.UTF_8)) + 3;
        t1[wordByte] ^= 0x01;
        check("NEG tampered body byte → DPACK_SIG", "DPACK_SIG".equals(expect(() -> DictSign.load(t1, stmt, null))));

        // 2) clean word swapped after review, sig+words reforged, original statement kept
        byte[] swapped = csv(
                row("مدادية", "مِدَادِيَّة", "مداد", "adj", "متعلقة بالمداد والكتابة", "سالم-قلم", "CC0-1.0"),
                row("عدادية", "عَدَّادِيَّة", "عداد", "adj", "متعلقة بالعد والقياس", "نورة-حرف", "CC0-1.0"));
        DictSign.Dpack forged = publishOf(swapped, inst());
        String head = new String(raw, 0, headerLen(raw), StandardCharsets.UTF_8)
                .replace("words " + d.wordsSha, "words " + forged.wordsSha);
        byte[] attack = (head + new String(bodyOf(forged.bytes), StandardCharsets.UTF_8)
                + "sig " + CommunityDict.sha256Hex(
                        concat(head.getBytes(StandardCharsets.UTF_8), bodyOf(forged.bytes))) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        check("NEG post-review word swap vs original statement → DPACK_REPORT",
                "DPACK_REPORT".equals(expect(() -> DictSign.load(attack, stmt, null))));

        // 3) forged accepted counter — unsigned edit breaks the signature first
        byte[] t3 = patchLine(raw, "accepted 2", "accepted 3");
        check("NEG forged counter, unsigned edit → DPACK_SIG",
                "DPACK_SIG".equals(expect(() -> DictSign.load(t3, stmt, null))));
        // 3b) counter reforged consistently → count ≠ body rows
        byte[] t3b = reforge(raw, "accepted 2", "accepted 3");
        check("NEG reforged counter mismatching body → DPACK_COUNT",
                "DPACK_COUNT".equals(expect(() -> DictSign.load(t3b, stmt, null))));

        // 4) foreign batchId in the header (reforged) — the statement pins the batch
        byte[] t4 = reforge(raw, "batch " + d.batchId,
                "batch " + CommunityDict.sha256Hex("x".getBytes(StandardCharsets.UTF_8)));
        check("NEG foreign batchId → DPACK_REPORT (statement pins the batch)",
                "DPACK_REPORT".equals(expect(() -> DictSign.load(t4, stmt, null))));

        // 5) words pin corrupted (reforged sig)
        byte[] t5 = reforge(raw, "words " + d.wordsSha,
                "words " + CommunityDict.sha256Hex("y".getBytes(StandardCharsets.UTF_8)));
        check("NEG corrupted words pin → DPACK_WORDS",
                "DPACK_WORDS".equals(expect(() -> DictSign.load(t5, stmt, null))));

        // 6) schema line replaced
        byte[] t6 = reforge(raw, "neokey-dpack-1", "neokey-dpack-2");
        check("NEG wrong schema line → DPACK_SCHEMA",
                "DPACK_SCHEMA".equals(expect(() -> DictSign.load(t6, stmt, null))));

        // 7) truncated body (one row silently dropped, a fresh sig appended)
        String txt = new String(raw, StandardCharsets.UTF_8);
        int r1end = txt.indexOf('\n', txt.indexOf("w "));
        byte[] t7 = appendSig(txt.substring(0, r1end + 1).getBytes(StandardCharsets.UTF_8));
        check("NEG truncated body → DPACK_WORDS (body sha no longer matches)",
                "DPACK_WORDS".equals(expect(() -> DictSign.load(t7, stmt, null))));
        // 7b) truncation fully reforged (words+counter+sig) → statement mismatch
        byte[] t7b = reforge(reforge(t7, "accepted 2", "accepted 1"),
                "words " + d.wordsSha,
                "words " + CommunityDict.sha256Hex(bodyOf(t7)));
        check("NEG fully-reforged truncation → DPACK_REPORT",
                "DPACK_REPORT".equals(expect(() -> DictSign.load(t7b, stmt, null))));

        // 8) BOM-prefixed package
        byte[] t8 = new byte[raw.length + 3];
        t8[0] = (byte) 0xEF; t8[1] = (byte) 0xBB; t8[2] = (byte) 0xBF;
        System.arraycopy(raw, 0, t8, 3, raw.length);
        check("NEG BOM-prefixed package → DPACK_SCHEMA",
                "DPACK_SCHEMA".equals(expect(() -> DictSign.load(t8, stmt, null))));

        // 9) poison word in a fully-reforged pack with attacker-supplied statement —
        //    row re-validation is the last line of defense (NOT_ARABIC)
        List<DictSign.Word> poisonRows = new ArrayList<>();
        poisonRows.add(new DictSign.Word("qqq", "qqq", "qqq", "noun"));
        poisonRows.add(new DictSign.Word("عدادية", "عَدَّادِيَّة", "عداد", "adj"));
        byte[] manual = manualPack(d.batchId, poisonRows);
        String manualStmt = DictSign.statement(d.batchId, poisonRows.size(),
                CommunityDict.sha256Hex(DictSign.bodyBytes(poisonRows)), poisonRows);
        check("NEG latin poison word, fully-reforged pack + own statement → DPACK_WORD (NOT_ARABIC)",
                expect(() -> DictSign.load(manual, manualStmt, null)).startsWith("DPACK_WORD"));

        // 10) pack duplicating the installed dictionary — caught with norms provided
        byte[] dup = cleanCsv();
        DictSign.Dpack dupD = publishOf(dup, inst());
        check("NEG pack duplicating the installed dictionary → DPACK_WORD (DUPLICATE)",
                expect(() -> DictSign.load(dupD.bytes, dupD.statement, inst("كتابية"))).startsWith("DPACK_WORD"));
    }

    // ---- byte-surgery helpers (attack simulations keep the gates honest) ----

    static int indexOf(byte[] hay, byte[] needle) {
        outer: for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** byte length of the 5 header lines (incl. their LFs) */
    static int headerLen(byte[] raw) {
        int n = 0;
        for (int i = 0, seen = 0; seen < 5 && i < raw.length; i++)
            if (raw[i] == '\n') { seen++; n = i + 1; }
        return n;
    }

    /** body bytes = everything between the header and the final "sig " line */
    static byte[] bodyOf(byte[] raw) {
        int h = headerLen(raw);
        int sigStart = raw.length - (4 + 64 + 1);   // "sig " + 64 hex + "\n"
        byte[] b = new byte[sigStart - h];
        System.arraycopy(raw, h, b, 0, b.length);
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static byte[] patchLine(byte[] raw, String from, String to) {
        String s = new String(raw, StandardCharsets.UTF_8);
        return s.replace(from + "\n", to + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** replace a header field AND re-sign the whole package (the honest attacker) */
    static byte[] reforge(byte[] raw, String from, String to) {
        return resig(patchLine(raw, from, to));
    }

    /** append a fresh sig line over exactly these prefix bytes (truncated-file attacker) */
    static byte[] appendSig(byte[] prefix) {
        byte[] tail = ("sig " + CommunityDict.sha256Hex(prefix) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return out;
    }

    static final byte[] SIGB = {'\n', 's', 'i', 'g', ' '};

    /** recompute the sig line over everything before it — BYTE indices only
     *  (a char-index search on UTF-8 Arabic would truncate mid-sequence) */
    static byte[] resig(byte[] raw) {
        int hit = -1;
        outer: for (int i = raw.length - 5; i >= 0; i--) {
            for (int j = 0; j < 5; j++) if (raw[i + j] != SIGB[j]) continue outer;
            hit = i; break;
        }
        if (hit < 0) return raw;
        int sigStart = hit + 1;
        byte[] prefix = new byte[sigStart];
        System.arraycopy(raw, 0, prefix, 0, sigStart);
        byte[] tail = ("sig " + CommunityDict.sha256Hex(prefix) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[sigStart + tail.length];
        System.arraycopy(prefix, 0, out, 0, sigStart);
        System.arraycopy(tail, 0, out, sigStart, tail.length);
        return out;
    }

    /** hand-build a consistent pack with arbitrary rows (bypassing publish's review filter) */
    static byte[] manualPack(String batch, List<DictSign.Word> ws) {
        byte[] body = DictSign.bodyBytes(ws);
        String wordsSha = CommunityDict.sha256Hex(body);
        String stmt = DictSign.statement(batch, ws.size(), wordsSha, ws);
        String reportSha = CommunityDict.sha256Hex(stmt.getBytes(StandardCharsets.UTF_8));
        String head = "neokey-dpack-1\nbatch " + batch + "\naccepted " + ws.size()
                + "\nreport " + reportSha + "\nwords " + wordsSha + "\n";
        byte[] prefix = concat(head.getBytes(StandardCharsets.UTF_8), body);
        return (new String(prefix, StandardCharsets.UTF_8) + "sig " + CommunityDict.sha256Hex(prefix) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
