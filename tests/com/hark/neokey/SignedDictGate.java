package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Week 19 gate (Phase 3, week 2) — the SIGNED community-dictionary pack.
 *
 *   • the SHIPPED asset assets/community_dict.json verifies (pin + re-judge)
 *     and merges after the installed corpus with a measured plan (add/skip)
 *   • the publishing step is proven end-to-end: clean batch → strict re-review
 *     → canonical pinned pack → verify
 *   • 19 designed negatives, each with the EXACT expected tag:
 *       1  SCHEMA    wrong schema string
 *       2  JSON      truncated bytes
 *       3  JSON      invalid UTF-8 (strict decoder)
 *       4  JSON      unknown key
 *       5  JSON      keys out of canonical order
 *       6  JSON      trailing garbage
 *       7  JSON      raw control char inside a string
 *       8  BATCH     batchId too short
 *       9  BATCH     batchId uppercase hex
 *      10  COUNT     claimed accepted < words
 *      11  COUNT     claimed accepted > words
 *      12  EMPTY_PACK accepted=0 / words=[]
 *      13  PIN       word flipped, pin kept (post-review tampering)
 *      14  PIN       pin corrupted
 *      15  PIN       rows reordered, pin kept
 *      16  FORBIDDEN re-judge catches a poison row behind a RECOMPUTED pin
 *      17  DUPLICATE re-judge catches an in-pack duplicate behind a RECOMPUTED pin
 *      18  BAD_LICENSE re-judge catches a bad license behind a RECOMPUTED pin
 *      19  NOTHING_ACCEPTED publish refuses an all-rejected batch
 *   • p50 verify time measured on the real pack (< 5000 µs bar)
 *
 * Verdict: SIGNEDICT_GATE_OK verified=N negatives=K/19 pack_words=W
 *          merge_add=A merge_skip=S p50_verify_us=X installed=I (exit 0)
 *        / SIGNEDICT_GATE_FAILED <reason> (exit 1).
 * Writes build/signeddict/negative-proof.log — one line per negative.
 * Pure JVM (zero android.*) — runs anywhere.
 */
public final class SignedDictGate {

    static int fails = 0;
    static List<String> proof = new ArrayList<>();

    static void need(boolean cond, String reason) {
        if (!cond) { System.out.println("SIGNEDICT_GATE_FAILED " + reason); fails++; }
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("build/signeddict"));

        // ---- 0: installed corpus (assets/ar.txt) + the shipped pack -----------
        List<String> installed = Files.readAllLines(
                Paths.get("assets/ar.txt"), StandardCharsets.UTF_8);
        Set<String> installedNorms = CommunityDict.normSetOf(installed);
        need(installedNorms.size() > 1000, "installed dict too small: " + installedNorms.size());

        byte[] asset = Files.readAllBytes(Paths.get("assets/community_dict.json"));
        SignedDict.Pack real = SignedDict.verify(asset, null);
        need(real.size() == real.claimedAccepted, "shipped pack count mismatch");
        need(real.batchId.equals(CommunityDict.sha256Hex(
                Files.readAllBytes(Paths.get("dictsrc/community_batch_w19.csv")))),
                "shipped pack batchId != sha256(source batch)");

        SignedDict.MergePlan mreal = SignedDict.mergePlan(installed, real);
        need(mreal.add.size() + mreal.skipped.size() == real.size(),
                "merge plan lost rows: add=" + mreal.add.size() + " skip=" + mreal.skipped.size());

        // p50 verify on the real pack (51 runs, median)
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            long s = System.nanoTime();
            SignedDict.verify(asset, null);
            nanos.add(System.nanoTime() - s);
        }
        nanos.sort(Long::compareTo);
        long p50us = nanos.get(25) / 1000;
        need(p50us < 5000, "p50 verify too slow: " + p50us + "us");

        // ---- 1: publishing step end-to-end (clean batch → pack → verify) ------
        int verified = 0;
        List<String> batchWords = new ArrayList<>();
        byte[] csv = cleanBatch(12, installedNorms, batchWords);
        SignedDict.PublishResult pub = SignedDict.publish(csv, installedNorms);
        need(pub.report.accepted == 12 && pub.pack.size() == 12, "publish not 12/12");
        SignedDict.Pack vpub = SignedDict.verify(pub.bytes, null);
        need(vpub.size() == 12 && vpub.batchId.equals(pub.pack.batchId), "roundtrip broken");
        String pubText = new String(pub.bytes, StandardCharsets.UTF_8);
        verified++;

        // evidence: publish report for the gate batch
        Files.write(Paths.get("build/signeddict/publish-report.json"),
                ("{\"schema\":\"" + SignedDict.SCHEMA + "\",\"batchId\":\"" + pub.pack.batchId
                        + "\",\"rowsTotal\":" + pub.report.total
                        + ",\"rowsAccepted\":" + pub.report.accepted
                        + ",\"rowsRejected\":" + pub.report.rejected
                        + ",\"packWords\":" + pub.pack.size()
                        + ",\"packSha256\":\"" + pub.packSha256
                        + "\",\"bodySha256\":\"" + pub.pack.bodySha256 + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        verified++;   // the shipped asset verified above

        // ---- 2: the negative matrix -------------------------------------------
        int neg = 0, refused = 0;
        String[][] rows2 = {csvFields(batchWords.get(0), 0), csvFields(batchWords.get(1), 1)};

        // 1 wrong schema
        neg++; refused += negNeg("wrong-schema", SignedDict.T_SCHEMA,
                buildPack("neokey-community-2", pub.pack.batchId, "1",
                        arr(rows2), pin(arr(rows2))).bytes);
        // 2 truncated bytes
        byte[] trunc = new byte[pub.bytes.length - 10];
        System.arraycopy(pub.bytes, 0, trunc, 0, trunc.length);
        neg++; refused += negNeg("truncated-bytes", SignedDict.T_JSON, trunc);
        // 3 invalid UTF-8
        byte[] bad = pub.bytes.clone();
        for (int i = 0; i < bad.length - 1; i++)
            if ((bad[i] & 0xE0) == 0xC0) { bad[i + 1] = (byte) 0xFF; break; }
        neg++; refused += negNeg("invalid-utf8", SignedDict.T_JSON, bad);
        // 4 unknown key
        neg++; refused += negNeg("unknown-key", SignedDict.T_JSON,
                buildPack("neokey-community-1", pub.pack.batchId, "1",
                        arr(rows2), pin(arr(rows2))).text
                        .replace("\"schema\"", "\"schemaX\"")
                        .getBytes(StandardCharsets.UTF_8));
        // 5 keys out of order
        neg++; refused += negNeg("keys-out-of-order", SignedDict.T_JSON,
                ("{\"accepted\":1,\"schema\":\"" + SignedDict.SCHEMA + "\",\"batchId\":\""
                        + pub.pack.batchId + "\",\"words\":" + arr(rows2)
                        + ",\"bodySha256\":\"" + pin(arr(rows2)) + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        // 6 trailing garbage
        neg++; refused += negNeg("trailing-garbage", SignedDict.T_JSON,
                concat(pub.bytes, " x".getBytes(StandardCharsets.UTF_8)));
        // 7 raw control char inside a string
        neg++; refused += negNeg("raw-control-char", SignedDict.T_JSON,
                buildPack("neokey\n-community-1", pub.pack.batchId, "1",
                        arr(rows2), pin(arr(rows2))).bytes);
        // 8/9 batchId shape
        String short63 = new String(new char[63]).replace('\0', 'a');
        String upper64 = new String(new char[64]).replace('\0', 'A');
        neg++; refused += negNeg("batchid-short", SignedDict.T_BATCH,
                buildPack(SignedDict.SCHEMA, short63, "1", arr(rows2), pin(arr(rows2))).bytes);
        neg++; refused += negNeg("batchid-uppercase", SignedDict.T_BATCH,
                buildPack(SignedDict.SCHEMA, upper64, "1", arr(rows2), pin(arr(rows2))).bytes);
        // 10/11 count lies (both directions)
        neg++; refused += negNeg("count-claim-low", SignedDict.T_COUNT,
                pubText.replace("\"accepted\":12", "\"accepted\":11")
                        .getBytes(StandardCharsets.UTF_8));
        neg++; refused += negNeg("count-claim-high", SignedDict.T_COUNT,
                pubText.replace("\"accepted\":12", "\"accepted\":13")
                        .getBytes(StandardCharsets.UTF_8));
        // 12 empty pack
        neg++; refused += negNeg("empty-pack", SignedDict.T_EMPTY,
                buildPack(SignedDict.SCHEMA, pub.pack.batchId, "0", "[]", pin("[]")).bytes);
        // 13 value flip with the original pin kept (post-review tampering)
        String w0 = batchWords.get(0);
        String w0flip = w0.endsWith("ب") ? w0.substring(0, w0.length() - 1) + "ت" : w0 + "ب";
        neg++; refused += negNeg("word-flip-keep-pin", SignedDict.T_PIN,
                pubText.replace(w0, w0flip).getBytes(StandardCharsets.UTF_8));
        // 14 corrupted pin
        neg++; refused += negNeg("corrupt-pin", SignedDict.T_PIN,
                pubText.replace("\"bodySha256\":\"" + pub.pack.bodySha256,
                        "\"bodySha256\":\"0000" + pub.pack.bodySha256.substring(4))
                        .getBytes(StandardCharsets.UTF_8));
        // 15 reordered rows with the pin over the ORIGINAL order
        String[][] rowsRev = {rows2[1], rows2[0]};
        neg++; refused += negNeg("rows-reordered-keep-pin", SignedDict.T_PIN,
                buildPack(SignedDict.SCHEMA, pub.pack.batchId, "2",
                        arr(rowsRev), pin(arr(rows2))).bytes);
        // 16/17/18 — the RE-JUDGE trap: attacker RECOMPUTES the pin, the same
        // reviewer refuses the row anyway
        String[][] poisonF = {rows2[0],
                {"سلعلاب", "سُلعلَاب", "سلعلاب", "noun", "حقير", "amina", "CC0-1.0"}};
        neg++; refused += negNeg("rejudge-forbidden-recomputed-pin", CommunityDict.T_FORBIDDEN,
                buildPack(SignedDict.SCHEMA, pub.pack.batchId, "2",
                        arr(poisonF), pin(arr(poisonF))).bytes);
        String[][] dupIn = {rows2[0], rows2[0]};
        neg++; refused += negNeg("rejudge-duplicate-recomputed-pin", CommunityDict.T_DUPLICATE,
                buildPack(SignedDict.SCHEMA, pub.pack.batchId, "2",
                        arr(dupIn), pin(arr(dupIn))).bytes);
        String[][] badLic = {rows2[0], csvFields(batchWords.get(1), 9)};
        badLic[1][6] = "MIT";
        neg++; refused += negNeg("rejudge-bad-license-recomputed-pin", CommunityDict.T_BAD_LICENSE,
                buildPack(SignedDict.SCHEMA, pub.pack.batchId, "2",
                        arr(badLic), pin(arr(badLic))).bytes);
        // 19 publish refuses an all-rejected batch
        neg++;
        String ptag = null;
        try { SignedDict.publish(("word,diacritized,lemma,pos,gloss,contributor,license\n"
                + "حقير,حَقِير,حقير,adj,معنى,amina,CC0-1.0\n")
                .getBytes(StandardCharsets.UTF_8), installedNorms); }
        catch (SignedDict.PublishRefused e) { ptag = e.tag; }
        boolean ok19 = SignedDict.T_NOTHING.equals(ptag);
        if (ok19) refused++;
        proof.add("publish-nothing-accepted expected=" + SignedDict.T_NOTHING
                + " got=" + ptag + " status=" + (ok19 ? "OK" : "BROKEN"));
        need(ok19, "publish did not refuse the all-rejected batch");

        need(neg == 19, "negative matrix size " + neg + " != 19");
        need(refused == 19, "negatives refused " + refused + "/19");

        Files.write(Paths.get("build/signeddict/negative-proof.log"),
                (String.join("\n", proof) + "\n").getBytes(StandardCharsets.UTF_8));

        if (fails > 0) System.exit(1);
        System.out.println("SIGNEDICT_GATE_OK verified=" + verified
                + " negatives=" + refused + "/" + neg
                + " pack_words=" + real.size()
                + " merge_add=" + mreal.add.size()
                + " merge_skip=" + mreal.skipped.size()
                + " p50_verify_us=" + p50us
                + " installed=" + installedNorms.size());
    }

    // ------------------------------------------------------------- negative

    /** Verify a crafted pack expecting the exact tag; log the proof line. */
    static int negNeg(String name, String expectedTag, byte[] raw) {
        String got;
        try { SignedDict.verify(raw, null); got = "NO_REFUSAL"; }
        catch (SignedDict.Refused e) { got = e.tag; }
        catch (RuntimeException e) { got = "EX:" + e.getClass().getSimpleName(); }
        boolean ok = expectedTag.equals(got);
        if (ok) proof.add(name + " expected=" + expectedTag + " got=" + got + " status=OK");
        else proof.add(name + " expected=" + expectedTag + " got=" + got + " status=BROKEN");
        need(ok, name + ": expected " + expectedTag + " got " + got);
        return ok ? 1 : 0;
    }

    // ------------------------------------------------------------- pack craft

    static final class Text {
        final String text; final byte[] bytes;
        Text(String t) { text = t; bytes = t.getBytes(StandardCharsets.UTF_8); }
    }

    static Text buildPack(String schema, String batchId, String accepted,
                          String wordsArrJson, String bodySha) {
        return new Text("{\"schema\":\"" + schema + "\",\"batchId\":\"" + batchId
                + "\",\"accepted\":" + accepted + ",\"words\":" + wordsArrJson
                + ",\"bodySha256\":\"" + bodySha + "\"}");
    }

    /** Canonical words-array serialization for crafted rows (mirrors SignedDict.str). */
    static String arr(String[][] rows) {
        StringBuilder b = new StringBuilder().append('[');
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) b.append(',');
            b.append('[');
            for (int j = 0; j < 7; j++) {
                if (j > 0) b.append(',');
                b.append('"').append(rows[i][j]).append('"');
            }
            b.append(']');
        }
        return b.append(']').toString();
    }

    static String pin(String wordsArrJson) {
        return CommunityDict.sha256Hex(wordsArrJson.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Swap the first two rows inside the words array of a canonical pack text. */
    // ------------------------------------------------------------- fixtures

    static String[] csvFields(String word, int i) {
        return new String[]{word, vocalize(word, i), word,
                i % 2 == 0 ? "noun" : "verb", "معنى موثق " + i,
                i % 2 == 0 ? "amina" : "kareem", i % 3 == 0 ? "CC-BY-4.0" : "CC0-1.0"};
    }

    static byte[] cleanBatch(int n, Set<String> norms, List<String> batchWords) {
        batchWords.clear();
        StringBuilder b = new StringBuilder(
                "word,diacritized,lemma,pos,gloss,contributor,license\n");
        String[] contribs = {"amina", "kareem", "sami", "layan"};
        String[] lics = {"CC0-1.0", "CC-BY-4.0"};
        Set<String> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String w = genWord(i);
            while (used.contains(w) || norms.contains(CommunityDict.normAr(w))) w = w + "ا";
            used.add(w);
            batchWords.add(w);
            b.append(w).append(',').append(vocalize(w, i)).append(',').append(w)
             .append(',').append(i % 2 == 0 ? "noun" : "verb")
             .append(",معنى موثق ").append(i).append(',')
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
}
