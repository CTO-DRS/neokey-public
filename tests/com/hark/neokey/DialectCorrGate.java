package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * W87 gate — «يصحح العامية دون تحويلها إلى الفصحى» measured, not claimed.
 * Measures Correction2.dialectFix AS SHIPPED on the plain JVM:
 *
 *   1. protection: every word of the three shipped dialect lexicons is fed
 *      back clean; with the lexicons attached the count of changes that are
 *      NOT a same-length hamza/ة standard re-spelling MUST be 0. The same
 *      run detached is printed (the pre-W87 damage — the gate bites it).
 *   2. dialect repair: each lexicon word (len ≥ 4) gets a deterministic
 *      typo (its last letter doubled); the active lexicon must restore the
 *      EXACT word at a rate ≥ MIN_REPAIR and strictly above the detached
 *      (MSA-only) chain.
 *   3. detach law: null active + null lexicons == the pre-W87 chain
 *      (fix → correction) on every word of the reference set, clean and with
 *      injected errors — any difference is red.
 *   4. latency: p50 < 8 ms per decision (the CorrectionGate budget class).
 *
 * Verdict: DIALECTCORR_GATE_OK / DIALECTCORR_GATE_FAILED <reason> (exit 1).
 */
public final class DialectCorrGate {

    private static final double MIN_REPAIR = 80.0;
    private static final String[] IDS = {"ar_hijazi", "ar_najdi", "ar_gulf"};

    public static void main(String[] args) {
        try {
            run();
        } catch (Gate g) {
            System.out.println("DIALECTCORR_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("DIALECTCORR_GATE_FAILED internal: " + e);
            System.exit(1);
        }
        System.out.println("DIALECTCORR_GATE_OK");
    }

    private static final class Gate extends RuntimeException { Gate(String m) { super(m); } }
    private static void need(boolean ok, String m) { if (!ok) throw new Gate(m); }

    private static boolean standardOnly(String typed, String c) {
        return c.length() == typed.length() && Correction2.isStandardOnlyDirection(typed, c);
    }

    private static void run() throws Exception {
        File files = new File("build/dialectcorr-files");
        files.mkdirs();
        FakeContext fc = new FakeContext(files);
        Dictionary msa = new Dictionary(fc, "ar");
        Dictionary[] lex = new Dictionary[IDS.length];
        for (int i = 0; i < IDS.length; i++) lex[i] = new Dictionary(fc, IDS[i]);
        long t0 = System.currentTimeMillis();
        boolean all;
        do {
            all = msa.isLoaded();
            for (Dictionary d : lex) all &= d.isLoaded();
            if (!all) Thread.sleep(50);
        } while (!all && System.currentTimeMillis() - t0 < 30000);
        need(all, "dictionaries did not load");
        Set<String> none = new HashSet<>();

        // ---- 1. protection
        int words = 0, damageAttached = 0, damageDetached = 0, normalized = 0;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < IDS.length; i++) {
            for (String w : lexicon(IDS[i])) {
                words++;
                String det = Correction2.dialectFix(w, "", "", msa, null, null, none);
                if (det != null && !det.equals(w) && !standardOnly(w, det)) damageDetached++;
                String att = Correction2.dialectFix(w, "", "", msa, lex[i], lex, none);
                if (att == null || att.equals(w)) continue;
                if (standardOnly(w, att)) { normalized++; continue; }
                damageAttached++;
                if (bad.length() < 300) bad.append(w).append("→").append(att).append(' ');
            }
        }
        System.out.println("DIALECTCORR protect words=" + words + " damage_detached=" + damageDetached
                + " damage_attached=" + damageAttached + " standard_respell=" + normalized);
        need(damageDetached > 0, "protect.no_bite (the detached chain shows no damage — the measure is blind)");
        need(damageAttached == 0, "protect.damage " + damageAttached + ": " + bad);

        // ---- 2. dialect repair
        int cases = 0, okAtt = 0, okDet = 0;
        for (int i = 0; i < IDS.length; i++) {
            for (String w : lexicon(IDS[i])) {
                if (w.length() < 4 || w.indexOf(' ') >= 0) continue;
                String typo = w + w.charAt(w.length() - 1);
                if (msa.knows(typo) || lex[i].knows(typo)) continue;
                cases++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, lex[i], lex, none))) okAtt++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, null, null, none))) okDet++;
            }
        }
        double accAtt = cases == 0 ? 0 : 100.0 * okAtt / cases;
        double accDet = cases == 0 ? 0 : 100.0 * okDet / cases;
        System.out.printf("DIALECTCORR repair cases=%d attached=%.1f%% detached=%.1f%%%n", cases, accAtt, accDet);
        need(cases >= 300, "repair.too_few " + cases);
        need(accAtt >= MIN_REPAIR, "repair.below_floor " + accAtt);
        need(okAtt > okDet, "repair.no_gain");

        // ---- 3. detach law
        List<String> ref = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/testset_ar.txt").toPath(), StandardCharsets.UTF_8))
            for (String w : line.trim().split("\\s+")) if (w.length() >= 2) ref.add(w);
        need(ref.size() >= 200, "detach.ref_too_small " + ref.size());
        int diffs = 0, checked = 0;
        long[] lat = new long[ref.size() * 2];
        int li = 0;
        for (String w : ref) {
            for (String t : new String[]{w, w + w.charAt(w.length() - 1)}) {
                String old = Correction2.fix(t, "", "", msa, none);
                if (old == null) old = msa.correction(t);
                long s = System.nanoTime();
                String now = Correction2.dialectFix(t, "", "", msa, null, null, none);
                lat[li++] = System.nanoTime() - s;
                checked++;
                if (old == null ? now != null : !old.equals(now)) diffs++;
            }
        }
        System.out.println("DIALECTCORR detach checked=" + checked + " diffs=" + diffs);
        need(diffs == 0, "detach.diff " + diffs);

        // ---- 3b. MSA no-regression: the reference errors with every lexicon attached
        // (gulf active — the widest) restore at least as often as detached.
        int msaOkAtt = 0, msaOkDet = 0, msaCases = 0;
        for (String w : ref) {
            if (w.length() < 3) continue;
            String t = w + w.charAt(w.length() - 1);
            if (msa.knows(t)) continue;
            msaCases++;
            if (w.equals(Correction2.dialectFix(t, "", "", msa, lex[2], lex, none))) msaOkAtt++;
            if (w.equals(Correction2.dialectFix(t, "", "", msa, null, null, none))) msaOkDet++;
        }
        System.out.println("DIALECTCORR msa cases=" + msaCases + " attached=" + msaOkAtt + " detached=" + msaOkDet);
        need(msaOkAtt >= msaOkDet, "msa.regression " + msaOkAtt + " < " + msaOkDet);

        // ---- 3c. sentence context (W89). Dialect: every lexicon typo placed after a
        // word of a same-dialect sentence (assets/testset_dialects.txt, seeded, the
        // sentence's own next word after it) — with context it must beat no context.
        // MSA: every reference word's typo inside its own testset_ar.txt sentence with
        // the widest lexicon (gulf) attached restores at least as often as detached.
        java.util.Map<String, List<String[]>> sents = new java.util.HashMap<>();
        for (String line : Files.readAllLines(new File("assets/testset_dialects.txt").toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.indexOf('\t') < 0) continue;
            String[] p = line.split("\t");
            sents.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1].trim().split("\\s+"));
        }
        java.util.Random rnd = new java.util.Random(89);
        int cx = 0, cxOk = 0, cxBare = 0;
        for (int i = 0; i < IDS.length; i++) {
            List<String[]> ss = sents.get(IDS[i].substring(3));
            need(ss != null && !ss.isEmpty(), "context.no_sentences " + IDS[i]);
            for (String w : lexicon(IDS[i])) {
                if (w.length() < 4) continue;
                String typo = w + w.charAt(w.length() - 1);
                if (msa.knows(typo) || lex[i].knows(typo)) continue;
                String[] s = ss.get(rnd.nextInt(ss.size()));
                int j = rnd.nextInt(s.length);
                cx++;
                if (w.equals(Correction2.dialectFix(typo, s[j], j + 1 < s.length ? s[j + 1] : "", msa, lex[i], lex, none))) cxOk++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, lex[i], lex, none))) cxBare++;
            }
        }
        int ms = 0, msAtt = 0, msDet = 0;
        for (String line : Files.readAllLines(new File("assets/testset_ar.txt").toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] s = line.trim().split("\\s+");
            for (int j = 0; j < s.length; j++) {
                String w = s[j];
                if (w.length() < 4 || !msa.knows(w)) continue;
                String t = w + w.charAt(w.length() - 1);
                if (msa.knows(t)) continue;
                String p = j > 0 ? s[j - 1] : "", nx = j + 1 < s.length ? s[j + 1] : "";
                ms++;
                if (w.equals(Correction2.dialectFix(t, p, nx, msa, lex[2], lex, none))) msAtt++;
                if (w.equals(Correction2.dialectFix(t, p, nx, msa, null, null, none))) msDet++;
            }
        }
        System.out.printf("DIALECTCORR context dialect=%d/%d (bare %d) msa_sentences=%d/%d (detached %d)%n",
                cxOk, cx, cxBare, msAtt, ms, msDet);
        need(cxOk > cxBare, "context.no_gain " + cxOk + " <= " + cxBare);
        need(msAtt >= msDet, "context.msa_regression " + msAtt + " < " + msDet);

        // ---- 3d. dialect synonyms (W90): every row has ≥2 distinct members, every
        // single-word dialect cell is a word of its dialect lexicon (or MSA), and every
        // member opens a picker that never repeats itself.
        String synText = new String(Files.readAllBytes(new File("assets/dialect_synonyms.txt").toPath()), StandardCharsets.UTF_8);
        Dialect.synLoad(synText);
        int rows = 0, members = 0;
        for (String line : synText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t");
            need(cells.length == 4, "syn.columns " + line);
            rows++;
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (int c = 0; c < 4; c++)
                for (String w : cells[c].split("،")) {
                    w = w.trim();
                    need(w.matches("[\\u0621-\\u064A ]{2,20}"), "syn.word " + w);
                    keys.add(Dialect.normAr(w));
                    members++;
                    if (c > 0 && w.indexOf(' ') < 0)
                        need(lex[c - 1].knows(w) || msa.knows(w), "syn.unknown " + IDS[c - 1] + " " + w);
                    List<String> ch = Dialect.synChips(w);
                    need(ch != null && ch.get(0).startsWith("✕"), "syn.no_picker " + w);
                    for (int q = 1; q < ch.size(); q++)
                        need(!Dialect.normAr(ch.get(q)).equals(Dialect.normAr(w)), "syn.self " + w);
                }
            need(keys.size() >= 2, "syn.trivial_row " + line);
        }
        need(Dialect.synChips("بيت") == null, "syn.false_picker");
        System.out.println("DIALECTCORR synonyms rows=" + rows + " members=" + members);

        // ---- 3e. voice N-best (W91): for every synonym row, an MSA first result and
        // the dialect form as an alternative — the active dialect takes its own form
        // whenever that form is a dialect cue; no active dialect, or no cue, keeps
        // the recognizer's first result byte-identical.
        int vCases = 0, vDialect = 0, vKept = 0;
        for (String line : synText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t");
            String m0 = "طيب " + cells[0].split("،")[0].trim() + " الحين";
            for (int c = 1; c < 4; c++) {
                String dw = cells[c].split("،")[0].trim();
                String alt = "طيب " + dw + " الحين";
                List<String> alts = java.util.Arrays.asList(m0, alt);
                need(Correction2.voicePick(alts, msa, null).equals(m0), "voice.detached " + alt);
                String got = Correction2.voicePick(alts, msa, lex[c - 1]);
                boolean cue = Correction2.dialectCue(dw, msa, lex[c - 1]);
                vCases++;
                if (cue && !m0.equals(alt)) { need(got.equals(alt), "voice.miss " + IDS[c - 1] + " " + alt); vDialect++; }
                else { need(got.equals(m0), "voice.flip " + IDS[c - 1] + " " + alt); vKept++; }
            }
        }
        List<String> plain = java.util.Arrays.asList("أريد أن أذهب إلى البيت", "اريد ان اذهب الى البيت");
        for (Dictionary d : lex) need(Correction2.voicePick(plain, msa, d) == plain.get(0), "voice.msa_flip");
        System.out.println("DIALECTCORR voice cases=" + vCases + " dialect=" + vDialect + " kept=" + vKept);

        // ---- 4. latency (attached path)
        long[] la = new long[cases + words];
        int n = 0;
        for (int i = 0; i < IDS.length; i++)
            for (String w : lexicon(IDS[i])) {
                long s = System.nanoTime();
                Correction2.dialectFix(w + "ه", "", "", msa, lex[i], lex, none);
                if (n < la.length) la[n++] = System.nanoTime() - s;
            }
        long[] cut = java.util.Arrays.copyOf(la, n);
        java.util.Arrays.sort(cut);
        double p50 = cut[n / 2] / 1e6;
        System.out.printf("DIALECTCORR latency p50=%.3fms n=%d%n", p50, n);
        need(p50 < 8.0, "latency.p50 " + p50);
    }

    private static List<String> lexicon(String id) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/" + id + ".txt").toPath(), StandardCharsets.UTF_8)) {
            String w = line.trim();
            int sp = w.indexOf('\t');
            if (sp >= 0) w = w.substring(0, sp);
            if (w.length() >= 2) out.add(w);
        }
        return out;
    }
}
