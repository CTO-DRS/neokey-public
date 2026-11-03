package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Week 19 (Phase 3, week 2) — positive proofs for the SIGNED community pack.
 *
 *   • publish → verify roundtrip (the publisher re-reviews its own batch)
 *   • batchId = sha256(source CSV), packSha256 = sha256(asset bytes)
 *   • canonical bytes (fixed key order, no whitespace)
 *   • deterministic re-verify
 *   • JSON re-escaping cannot smuggle content (trap-1 rationale, proven)
 *   • merge plan: corpus first, pack second, first-wins, corpus dup = SKIP
 *   • publish refuses an all-rejected batch (NOTHING_ACCEPTED)
 *   • the ENGINE (Dictionary on real assets) consumes the pack and suggests
 *     a pack word that the installed corpus does not have
 *
 * Pure JVM (zero android.* — Dictionary runs on the test stubs).
 */
public class SignedDictTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        List<String> installed = installedSample();
        Set<String> norms = CommunityDict.normSetOf(installed);
        List<String> batchWords = new ArrayList<>();
        byte[] csv = cleanBatch(20, norms, batchWords);

        // ---- 1: publish (the strict, measured publishing step) ----------------
        SignedDict.PublishResult pub = SignedDict.publish(csv, norms);
        check("publish accepts all 20 rows",
                pub.report.total == 20 && pub.report.accepted == 20 && pub.pack.size() == 20);
        check("batchId = sha256(source csv)",
                pub.pack.batchId.equals(CommunityDict.sha256Hex(csv)));
        check("packSha256 = sha256(asset bytes)",
                pub.packSha256.equals(CommunityDict.sha256Hex(pub.bytes)));
        check("canonical pack prefix (fixed key order, no whitespace)",
                new String(pub.bytes, StandardCharsets.UTF_8)
                        .startsWith("{\"schema\":\"neokey-community-1\",\"batchId\":\""));
        check("pack rows keep review order",
                pub.pack.rows.get(0).word.equals(batchWords.get(0))
                        && pub.pack.rows.get(19).word.equals(batchWords.get(19)));

        // ---- 2: verify roundtrip, deterministic -------------------------------
        SignedDict.Pack v1 = SignedDict.verify(pub.bytes, null);
        SignedDict.Pack v2 = SignedDict.verify(pub.bytes, null);
        check("verify roundtrip size", v1.size() == 20);
        check("verify deterministic (rows + pin)",
                v1.rows.get(7).word.equals(v2.rows.get(7).word)
                        && v1.bodySha256.equals(v2.bodySha256)
                        && v1.batchId.equals(v2.batchId));
        check("verify keeps the claimed count", v1.claimedAccepted == 20);

        // ---- 3: re-escaping cannot smuggle content (trap-1 rationale) ---------
        String raw = new String(pub.bytes, StandardCharsets.UTF_8);
        String w0 = batchWords.get(0);
        String escaped = raw.replace("\"" + w0 + "\"", "\"" + uEscape(w0) + "\"");
        SignedDict.Pack v3 = SignedDict.verify(escaped.getBytes(StandardCharsets.UTF_8), null);
        check("escaped pack verifies to IDENTICAL rows (no content smuggling)",
                v3.size() == 20 && v3.rows.get(0).word.equals(w0));

        // ---- 4: merge plan — corpus first, pack second, first wins ------------
        List<String> corpus = new ArrayList<>(installed);
        corpus.add(batchWords.get(2));   // corpus already contains pack row 2
        SignedDict.MergePlan plan = SignedDict.mergePlan(corpus, v1);
        check("merge skips the corpus duplicate", plan.add.size() == 19
                && !plan.add.contains(batchWords.get(2)));
        check("merge keeps pack order for the rest",
                plan.add.get(0).equals(batchWords.get(0))
                        && plan.add.get(1).equals(batchWords.get(1))
                        && plan.add.get(2).equals(batchWords.get(3)));
        check("merge skip list names the duplicate", plan.skipped.size() == 1
                && plan.skipped.get(0).equals(batchWords.get(2)));
        SignedDict.MergePlan planNull = SignedDict.mergePlan(null, v1);
        check("null corpus → everything added", planNull.add.size() == 20
                && planNull.skipped.isEmpty());

        // ---- 5: publish refuses an all-rejected batch -------------------------
        String poison = "word,diacritized,lemma,pos,gloss,contributor,license\n"
                + "حقير,حَقِير,حقير,adj,معنى,amina,CC0-1.0\n";
        boolean refused = false; String ptag = null;
        try { SignedDict.publish(poison.getBytes(StandardCharsets.UTF_8), norms); }
        catch (SignedDict.PublishRefused e) { refused = true; ptag = e.tag; }
        check("publish refuses nothing-accepted batch",
                refused && SignedDict.T_NOTHING.equals(ptag));

        // ---- 6: a hand-crafted count lie is refused (spot check) --------------
        String lie = raw.replace("\"accepted\":20", "\"accepted\":19");
        boolean lied = false; String ltag = null;
        try { SignedDict.verify(lie.getBytes(StandardCharsets.UTF_8), null); }
        catch (SignedDict.Refused e) { lied = true; ltag = e.tag; }
        check("accepted-count lie refused with COUNT",
                lied && SignedDict.T_COUNT.equals(ltag));

        // ---- 7: the ENGINE consumes the pack on real assets -------------------
        File ud = new File("build/test-user-signeddict");
        ud.mkdirs();
        Dictionary d = new Dictionary(new FakeContext(ud), "ar");
        long t0 = System.currentTimeMillis();
        while (!d.isLoaded() && System.currentTimeMillis() - t0 < 20000) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
        }
        check("engine loaded on real assets", d.isLoaded());
        // دحين ships ONLY in the signed pack (publish dedupes vs the corpus) —
        // knows() true proves the ENGINE consumed the pack after the corpus.
        check("engine knows the pack word دحين", d.knows("دحين"));
        check("control word still unknown",
                !d.knows("ززلززقزقزق"));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------ fixtures

    /** A tiny installed-corpus sample (the real ar.txt is not needed here). */
    static List<String> installedSample() {
        List<String> l = new ArrayList<>();
        for (String w : new String[]{"في", "من", "على", "الى", "عن", "مع", "هذا", "ذلك",
                "كتاب", "مكتب", "مدرسة", "سيارة", "سلام", "كيف", "حال", "شكرا"})
            l.add(w);
        return l;
    }

    /** Clean n-row CSV; fills batchWords with the exact word column. */
    static byte[] cleanBatch(int n, Set<String> norms, List<String> batchWords) {
        batchWords.clear();
        StringBuilder b = new StringBuilder(
                "word,diacritized,lemma,pos,gloss,contributor,license\n");
        String[] contribs = {"amina", "kareem", "sami", "layan"};
        String[] poses = {"noun", "verb", "adj"};
        String[] lics = {"CC0-1.0", "CC-BY-4.0"};
        Set<String> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String w = genWord(i);
            while (used.contains(w) || norms.contains(CommunityDict.normAr(w))) w = w + "ا";
            used.add(w);
            batchWords.add(w);
            b.append(w).append(',').append(vocalize(w, i)).append(',').append(w).append(',')
             .append(poses[i % 3]).append(",معنى موثق ").append(i).append(',')
             .append(contribs[i % 4]).append(',').append(lics[i % 2]).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final String[] SEGS = {"كت", "مد", "جم", "سل", "عل", "قر", "شم", "بح", "فر", "نج"};

    static String genWord(int i) {
        StringBuilder b = new StringBuilder();
        b.append(SEGS[i % SEGS.length]);
        b.append(SEGS[(i / SEGS.length + 3) % SEGS.length]);
        b.append(i % 2 == 0 ? "اب" : "ان");
        int extra = i / (SEGS.length * 2);
        for (int k = 0; k < extra && k < 5; k++) b.append("ا");
        return b.toString();
    }

    static char[] MARKS = {'َ', 'ُ', 'ِ'};

    static String vocalize(String w, int i) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < w.length(); k++) {
            b.append(w.charAt(k));
            if (k % 2 == 0) b.append(MARKS[(i + k) % 3]);
        }
        return b.toString();
    }

    /** Unicode-escape (backslash-u + 4 hex) for every char: ASCII-safe re-encoding of an Arabic value. */
    static String uEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() * 6);
        for (int i = 0; i < s.length(); i++)
            b.append(String.format("\\u%04x", (int) s.charAt(i)));
        return b.toString();
    }

    public static void main(String[] args) { run(); }
}
