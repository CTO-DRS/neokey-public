package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Week 17 gate (Phase 2, item D) — community dictionary enrichment pipeline.
 *
 *   • a clean 40-row batch is accepted 40/40 and produces the review report +
 *     CONTRIBUTORS.md (thanks section for the release page)
 *   • nine designed poison batches — one per refusal class — are refused with
 *     the EXACT expected tag (duplicates internal + installed, missing
 *     diacritics, broken skeleton, non-Arabic, forbidden words, bad license,
 *     bad header, BOM)
 *   • 110 seeded negatives (seed 1703) inside a 220-row batch: every sabotaged
 *     row refused, every clean row accepted
 *   • p50 per-row check time measured on an 800-row clean batch (< 5000 µs bar)
 *
 * Verdict: COMMUNITY_GATE_OK accepted=N rejected=M negatives=K p50_us=X
 * batches=B (exit 0) / COMMUNITY_GATE_FAILED <reason> (exit 1).
 * Pure JVM (zero android.*) — runs anywhere.
 */
public final class CommunityDictGate {

    static int fails = 0;

    static void need(boolean cond, String reason) {
        if (!cond) { System.out.println("COMMUNITY_GATE_FAILED " + reason); fails++; }
    }

    public static void main(String[] args) throws Exception {
        // installed dictionary word set (assets/ar.txt — one word per line)
        List<String> installed = Files.readAllLines(Paths.get("assets/ar.txt"), StandardCharsets.UTF_8);
        Set<String> installedNorms = CommunityDict.normSetOf(installed);
        need(installedNorms.size() > 1000, "installed dict too small: " + installedNorms.size());
        if (fails > 0) System.exit(1);

        Files.createDirectories(Paths.get("build/community"));

        // ---- 1: clean batch (40 rows, 4 contributors) → report + thanks ----
        int accepted = 0, rejected = 0, batches = 0;
        StringBuilder cleanCsv = new StringBuilder(
                "word,diacritized,lemma,pos,gloss,contributor,license\n");
        String[] contribs = {"amina", "kareem", "sami", "layan"};
        String[] poses = {"noun", "verb", "adj"};
        String[] lics = {"CC0-1.0", "CC-BY-4.0"};
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String base = genWord(i);
            while (used.contains(base) || installedNorms.contains(CommunityDict.normAr(base)))
                base = base + "ا";
            used.add(base);
            String diac = vocalize(base, i);
            cleanCsv.append(row(base, diac, base, poses[i % 3],
                    "معنى " + (i + 1) + " موثق", contribs[i % 4], lics[i % 2])).append('\n');
        }
        CommunityDict.Report clean = CommunityDict.review(
                cleanCsv.toString().getBytes(StandardCharsets.UTF_8), installedNorms);
        batches++;
        accepted += clean.accepted;
        rejected += clean.rejected;
        need(clean.total == 40 && clean.accepted == 40,
                "clean batch not fully accepted: " + clean.accepted + "/" + clean.total);
        need(clean.contributors().size() == 4, "clean contributors != 4");
        Files.write(Paths.get("build/community/review-clean40.json"),
                clean.toJson().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get("build/community/CONTRIBUTORS.md"),
                clean.contributorsMd().getBytes(StandardCharsets.UTF_8));

        // ---- 2: designed poison batches — exact refusal tags ----
        int[] acc = new int[1];
        int batchesPoison = poisonSuite(installedNorms, acc);
        batches += batchesPoison;
        accepted += acc[0];
        rejected += batchesPoison - acc[0];

        // ---- 3: seeded negatives (seed 1703, 110 sabotaged of 220) ----
        Random rnd = new Random(1703);
        StringBuilder negCsv = new StringBuilder(
                "word,diacritized,lemma,pos,gloss,contributor,license\n");
        int sabotaged = 0;
        Set<String> used2 = new HashSet<>();
        for (int i = 0; i < 220; i++) {
            String base = genWord(100 + i);
            while (used2.contains(base) || installedNorms.contains(CommunityDict.normAr(base)))
                base = base + "ا";
            used2.add(base);
            boolean poison = i % 2 == 0;
            String w = base, d = vocalize(base, i), l = base, gl = "معنى صحيح " + i,
                   p = poses[i % 3], c = contribs[i % 4], lic = lics[i % 2];
            if (poison) {
                sabotaged++;
                switch (rnd.nextInt(8)) {
                    case 0: d = base; break;                       // NO_DIACRITICS
                    case 1: d = base + "ا"; break;                 // BAD_DIACRITICS
                    case 2: w = "lat" + i; d = "lat" + i; break;   // NOT_ARABIC
                    case 3: w = base + "9"; d = base + "9"; break; // NOT_ARABIC digits
                    case 4: gl = "حقير"; break;                    // FORBIDDEN
                    case 5: p = "interj"; break;                   // BAD_POS
                    case 6: lic = "MIT"; break;                    // BAD_LICENSE
                    case 7: w = "كِت" + base; d = "كِتَ" + base; break; // HAS_TASHKEEL_IN_WORD
                    default: break;
                }
            }
            negCsv.append(row(w, d, l, p, gl, c, lic)).append('\n');
        }
        CommunityDict.Report neg = CommunityDict.review(
                negCsv.toString().getBytes(StandardCharsets.UTF_8), installedNorms);
        batches++;
        accepted += neg.accepted;
        rejected += neg.rejected;
        int negRefused = 0;
        for (int i = 0; i < neg.total; i++) {
            CommunityDict.Row r = neg.rows.get(i);
            if (i % 2 == 0) {
                if (!r.accepted()) negRefused++;
            } else {
                need(r.accepted(), "clean row " + i + " refused in negatives: " + r.tag);
            }
        }
        need(negRefused == sabotaged, "negatives refused " + negRefused + " != " + sabotaged);
        Files.write(Paths.get("build/community/review-negatives.json"),
                neg.toJson().getBytes(StandardCharsets.UTF_8));

        // ---- 4: p50 check-time on an 800-row clean batch ----
        StringBuilder big = new StringBuilder(
                "word,diacritized,lemma,pos,gloss,contributor,license\n");
        Set<String> used3 = new HashSet<>();
        for (int i = 0; i < 800; i++) {
            String base = genWord(1000 + i);
            while (used3.contains(base) || installedNorms.contains(CommunityDict.normAr(base)))
                base = base + "ا";
            used3.add(base);
            big.append(row(base, vocalize(base, i), base, "noun", "واصل " + i,
                    contribs[i % 4], "CC0-1.0")).append('\n');
        }
        CommunityDict.Report perf = CommunityDict.review(
                big.toString().getBytes(StandardCharsets.UTF_8), installedNorms);
        long p50us = perf.p50Nanos / 1000;
        need(perf.accepted == 800, "perf batch not clean: " + perf.accepted + "/800");
        need(p50us < 5000, "p50 too slow: " + p50us + "us >= 5000us");

        if (fails > 0) System.exit(1);
        System.out.println("COMMUNITY_GATE_OK accepted=" + accepted
                + " rejected=" + rejected
                + " negatives=" + negRefused
                + " p50_us=" + p50us
                + " batches=" + batches
                + " installed=" + installedNorms.size());
    }

    // ------------------------------------------------------------ poison suite

    /**
     * Nine designed poison batches — one per refusal class. Every batch starts
     * with one clean row (must pass) except the two file-level refusals
     * (BAD_HEADER / BAD_ENCODING) where both rows must be refused. acc[0]
     * accumulates accepted rows; the returned value is the batch count.
     */
    static int poisonSuite(Set<String> installedNorms, int[] acc) {
        String cleanWord = "مكتبات";
        while (installedNorms.contains(CommunityDict.normAr(cleanWord))) cleanWord = cleanWord + "ا";
        String w2 = genWord(777);
        while (installedNorms.contains(CommunityDict.normAr(w2))) w2 = w2 + "ا";
        String brokenD = w2.endsWith("ن")
                ? w2.substring(0, w2.length() - 1) + "ب" : w2.substring(0, w2.length() - 1) + "ن";
        String cw = cleanWord, cd = vocalize(cleanWord, 5), g = "معنى موثق", c = "amina", l = "CC0-1.0";
        int n = 0;
        n += poison(installedNorms, "poison-dup-internal", acc,
                row(cw, cd, cw, "noun", g, c, l),                      // clean
                row(cw, vocalize(cleanWord, 6), cw, "noun", g, c, l)); // same word again
        n += poison(installedNorms, "poison-dup-installed", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row("في", "فِي", "فِي", "particle", g, c, l));          // installed dict word
        n += poison(installedNorms, "poison-no-diac", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row(w2, w2, w2, "noun", g, c, l));                      // diacritized == word
        n += poison(installedNorms, "poison-broken-skeleton", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row(w2, brokenD, w2, "noun", g, c, l));                 // skeleton mismatch
        n += poison(installedNorms, "poison-latin", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row("hello", "hello", "hello", "noun", g, c, l));      // NOT_ARABIC
        n += poison(installedNorms, "poison-forbidden", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row("حقير", "حَقِير", "حقير", "adj", g, c, l));         // FORBIDDEN
        n += poison(installedNorms, "poison-bad-license", acc,
                row(cw, cd, cw, "noun", g, c, l),
                row(w2, vocalize(w2, 9), w2, "noun", g, c, "MIT")); // BAD_LICENSE

        // file-level: bad header rejects both rows
        String badHeaderCsv = "word,diacritized,lemma,pos,gloss,contributor\n"
                + row6(cw, cd, cw, "noun", g, c) + "\n"
                + row6(w2, w2, w2, "noun", g, c) + "\n";
        CommunityDict.Report rep = CommunityDict.review(
                badHeaderCsv.getBytes(StandardCharsets.UTF_8), installedNorms);
        need(rep.total == 2 && rep.rejected == 2, "poison-bad-header: rejected " + rep.rejected + "/" + rep.total);
        need(CommunityDict.T_BAD_HEADER.equals(rep.rows.get(0).tag)
                && CommunityDict.T_BAD_HEADER.equals(rep.rows.get(1).tag), "poison-bad-header: wrong tags");
        n++;
        // file-level: BOM rejects both rows
        byte[] body = ("word,diacritized,lemma,pos,gloss,contributor,license\n"
                + row(cw, cd, cw, "noun", g, c, l) + "\n"
                + row(w2, vocalize(w2, 9), w2, "noun", g, c, l) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        rep = CommunityDict.review(bom, installedNorms);
        need(rep.total == 2 && rep.rejected == 2, "poison-bom: rejected " + rep.rejected + "/" + rep.total);
        need(CommunityDict.T_BAD_ENCODING.equals(rep.rows.get(0).tag)
                && CommunityDict.T_BAD_ENCODING.equals(rep.rows.get(1).tag), "poison-bom: wrong tags");
        n++;
        return n;
    }

    /** One batch = clean row (must be accepted) + poisoned row (exact expected tag). */
    static int poison(Set<String> installedNorms, String name, int[] acc,
                      String cleanRow, String poisonRow) {
        String csv = "word,diacritized,lemma,pos,gloss,contributor,license\n"
                + cleanRow + "\n" + poisonRow + "\n";
        CommunityDict.Report rep = CommunityDict.review(csv.getBytes(StandardCharsets.UTF_8), installedNorms);
        need(rep.total == 2, name + ": total " + rep.total);
        need(rep.rows.get(0).accepted(), name + ": clean row refused: " + rep.rows.get(0).tag);
        need(rep.rows.get(1).tag != null && rep.rows.get(1).tag.equals(expectedTagOf(name)),
                name + ": expected " + expectedTagOf(name) + " got " + rep.rows.get(1).tag);
        acc[0]++;
        return 1;
    }

    static String expectedTagOf(String name) {
        switch (name) {
            case "poison-dup-internal":    return CommunityDict.T_DUPLICATE;
            case "poison-dup-installed":   return CommunityDict.T_DUPLICATE;
            case "poison-no-diac":         return CommunityDict.T_NO_DIAC;
            case "poison-broken-skeleton": return CommunityDict.T_BAD_DIAC;
            case "poison-latin":           return CommunityDict.T_NOT_ARABIC;
            case "poison-forbidden":       return CommunityDict.T_FORBIDDEN;
            case "poison-bad-license":     return CommunityDict.T_BAD_LICENSE;
            default: return "UNKNOWN";
        }
    }

    // ------------------------------------------------------------ generators

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

    static String row(String w, String d, String l, String p, String g, String c, String lic) {
        return w + "," + d + "," + l + "," + p + "," + g + "," + c + "," + lic;
    }

    static String row6(String w, String d, String l, String p, String g, String c) {
        return w + "," + d + "," + l + "," + p + "," + g + "," + c;
    }
}
