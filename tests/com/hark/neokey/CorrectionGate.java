package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Correction accuracy gate (phase 1, item B week 1) — measures Correction2 AS SHIPPED:
 *
 *   • realistic errors: every confusion-marked char in the clean reference sentences
 *     (assets/testset_ar.txt) is deterministically replaced by its bare form — the
 *     exact mistake real users make (إلى→الى، مشكلة→مشكله، على→علي…). The engine
 *     must restore the EXACT original word, with the REAL prev AND next word as
 *     context (week 2: الجملة الكاملة).
 *   • v1 baseline on the identical error set (ArabicFixes + isolated
 *     Dictionary.correction) — the improvement is a measured number, not an opinion.
 *   • zero-annoyance (roadmap: «صفر تصحيحات مزعجة»): every clean word is fed back
 *     with its real context; the gate counts
 *       fp_wrong        — any change that is NOT a same-norm hamza/ة standard
 *                         normalization (the documented policy) — MUST be 0;
 *       normalizations  — standard-orthography re-spellings of informal variants
 *                         (الى→إلى، ان→أن، اكيد→أكيد) — counted, reported, bounded.
 *   • الفاصلة المنقوطة والأرقام العربية: the roadmap's punctuation/numeral classes
 *     measured as cases (semicolon, script-adjacent digits) — punct_fails MUST be 0.
 *   • الحقول الحرجة: the no-silent-correction policy asserted (sensitive/incognito).
 *   • latency p50 < 8 ms per decision (the PerfGate budget class).
 *
 * Verdict: CORR_GATE_OK / CORR_GATE_FAILED <reason> (exit 1). Pure JVM.
 */
public final class CorrectionGate {

    private static final double MIN_V2_ACC = 96.0;    // week-2 bar = the item-final roadmap bar
    private static final double V1_GAP = 20.0;        // v2 must beat v1 by this many points
    private static final int FP_WRONG_MAX = 0;
    private static final double NORM_MAX_PCT = 15.0;  // documented normalizations on clean words
    private static final double MAX_P50_MS = 8.0;
    private static final int PUNCT_FAILS_MAX = 0;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Gate g) {
            System.out.println("CORR_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("CORR_GATE_FAILED internal: " + e);
            System.exit(1);
        }
        System.out.println("CORR_GATE_OK");
    }

    private static final class Gate extends RuntimeException {
        Gate(String m) { super(m); }
    }
    private static void need(boolean ok, String msg) {
        if (!ok) throw new Gate(msg);
    }

    private static void run() throws Exception {
        File files = new File("build/corrgate-files");
        files.mkdirs();
        Dictionary dict = new Dictionary(new FakeContext(files), "ar");
        long t0 = System.currentTimeMillis();
        while (!dict.knows("من") && System.currentTimeMillis() - t0 < 30000) Thread.sleep(100);
        need(dict.knows("من"), "dictionary did not load");

        List<String[]> sentences = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/testset_ar.txt").toPath(), StandardCharsets.UTF_8)) {
            String[] ws = line.trim().split("\\s+");
            if (ws.length > 1) sentences.add(ws);
        }
        need(sentences.size() >= 40, "reference sentences too few: " + sentences.size());

        // ---- build the injected error set (deterministic: first marked char wins)
        // Each case carries the FULL sentence context: {typed, prev, next, expected}.
        List<String[]> cases = new ArrayList<>();
        for (String[] ws : sentences) {
            for (int i = 0; i < ws.length; i++) {
                String err = inject(ws[i]);
                if (err != null) cases.add(new String[]{err,
                        i > 0 ? ws[i - 1] : "", i + 1 < ws.length ? ws[i + 1] : "", ws[i]});
            }
        }
        need(cases.size() >= 30, "injectable errors too few: " + cases.size());

        // ---- v2 on the error set
        int v2ok = 0, v2wrong = 0, v2sameNorm = 0;
        List<String> misses = new ArrayList<>();
        for (String[] c : cases) {
            String got = Correction2.fix(c[0], c[1], c[2], dict, Collections.<String>emptySet());
            if (got == null) { misses.add(c[0] + "→" + c[3] + " (none/" + Correction2.lastReason + ")"); }
            else if (got.equals(c[3])) v2ok++;
            else if (Dictionary.norm(got).equals(Dictionary.norm(c[3]))) {
                v2sameNorm++;   // hamza family member picked — recorded, must not hide in the accuracy
                misses.add(c[0] + "→" + got + " (same-norm of " + c[3] + ")");
            }
            else { v2wrong++; misses.add(c[0] + "→" + got + " (expected " + c[3] + ")"); }
        }
        double v2acc = 100.0 * v2ok / cases.size();

        // ---- v1 baseline on the identical set (expected is case[3])
        int v1ok = 0;
        for (String[] c : cases) {
            String got = ArabicFixes.fix(c[0]);
            if (got == null) got = dict.correction(c[0]);
            if (got != null && got.equals(c[3])) v1ok++;
        }
        double v1acc = 100.0 * v1ok / cases.size();

        // ---- zero-annoyance: clean words must never be wrongly changed
        Set<String> normPolicy = new HashSet<>();
        int fpWrong = 0, norms = 0, cleanChecked = 0;
        List<String> fpList = new ArrayList<>();
        for (String[] ws : sentences) {
            for (int i = 0; i < ws.length; i++) {
                String next = i + 1 < ws.length ? ws[i + 1] : "";
                String got = Correction2.fix(ws[i], i > 0 ? ws[i - 1] : "", next, dict, Collections.<String>emptySet());
                if (got == null) continue;
                cleanChecked++;
                if (isDocumentedNorm(ws[i], got)) norms++;
                else { fpWrong++; fpList.add(ws[i] + "→" + got); }
            }
        }
        // plus the most frequent dictionary words (no context) — the everyday-vocabulary bar.
        // assets/ar.txt IS the frequency-ordered corpus: line r = rank r.
        List<String> corpus = Files.readAllLines(new File("assets/ar.txt").toPath(), StandardCharsets.UTF_8);
        int topChecked = 0;
        for (int r = 0; r < Math.min(300, corpus.size()); r++) {
            String w = corpus.get(r).trim();
            if (w.length() < 3 || w.contains(" ")) continue;   // single/two-letter words: fix() ignores <2 anyway
            String got = Correction2.fix(w, "", dict, Collections.<String>emptySet());
            topChecked++;
            if (got == null) continue;
            if (isDocumentedNorm(w, got)) norms++;
            else { fpWrong++; fpList.add(w + "→" + got); }
        }
        double normPct = 100.0 * norms / Math.max(1, cleanChecked + topChecked);

        // ---- latency (warm pass)
        for (String[] c : cases) Correction2.fix(c[0], c[1], c[2], dict, Collections.<String>emptySet());
        long[] ns = new long[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            String[] c = cases.get(i);
            long s = System.nanoTime();
            Correction2.fix(c[0], c[1], c[2], dict, Collections.<String>emptySet());
            ns[i] = System.nanoTime() - s;
        }
        double p50ms = percentileMs(ns, 0.5);
        double p95ms = percentileMs(ns, 0.95);

        // ---- الفاصلة المنقوطة والأرقام العربية (the roadmap's punctuation/numeral classes)
        int punctCases = 0, punctFails = 0;
        List<String> punctFailsList = new ArrayList<>();
        // semicolon: Arabic layout means '؛'; Latin layout keeps ';'
        punctCases++;
        if (!"؛".equals(Correction2.punctFor(";", true))) { punctFails++; punctFailsList.add("; (ar) stayed latin"); }
        punctCases++;
        if (Correction2.punctFor(";", false) != null) { punctFails++; punctFailsList.add("; (en) was touched"); }
        // digits attached to Arabic text → Arabic-Indic; attached to Latin → Latin
        String[][] digitCases = {
                {"2", "ح", "٢"}, {"0", "ح", "٠"}, {"9", "ح", "٩"},          // Arabic neighbor
                {"3", "x", "3"}, {"7", "x", "7"},                              // Latin neighbor
                {"٢", "x", "2"}, {"٥", "h", "5"},                              // reverse direction
        };
        for (String[] dc : digitCases) {
            punctCases++;
            char got = Correction2.digitFor(dc[0].charAt(0), dc[1].charAt(0), false);
            if (got != dc[2].charAt(0)) { punctFails++; punctFailsList.add(dc[0] + " after " + dc[1] + " → " + got + " (want " + dc[2] + ")"); }
        }
        punctCases++;
        if (!Correction2.punctTableIntegrity()) { punctFails++; punctFailsList.add("table integrity"); }

        // ---- الحقول الحرجة: no silent correction in sensitive fields / when off
        int policyFails = 0;
        if (Correction2.silentCorrectionAllowed(true, true)) policyFails++;
        if (Correction2.silentCorrectionAllowed(false, false)) policyFails++;
        if (!Correction2.silentCorrectionAllowed(false, true)) policyFails++;

        System.out.printf(Locale.US, "CORRGATE n=%d v2_acc=%.1f%% v2_same_norm=%d v2_wrong=%d v1_acc=%.1f%% gap=%.1f fp_wrong=%d norms=%d(%.1f%%) p50=%.3fms p95=%.3fms punct=%d/%d policy_fails=%d%n",
                cases.size(), v2acc, v2sameNorm, v2wrong, v1acc, v2acc - v1acc, fpWrong, norms, normPct, p50ms, p95ms,
                punctCases - punctFails, punctCases, policyFails);
        for (int i = 0; i < Math.min(12, misses.size()); i++) System.out.println("CORRGATE miss: " + misses.get(i));
        for (String f : fpList) System.out.println("CORRGATE fp_wrong: " + f);
        for (String f : punctFailsList) System.out.println("CORRGATE punct_fail: " + f);

        need(v2acc >= MIN_V2_ACC, String.format(Locale.US, "v2 accuracy %.1f%% < %.0f%%", v2acc, MIN_V2_ACC));
        need(v2acc - v1acc >= V1_GAP, String.format(Locale.US, "v2 only +%.1f points over v1 (need +%.0f)", v2acc - v1acc, V1_GAP));
        need(fpWrong <= FP_WRONG_MAX, "annoying corrections on clean words: " + fpList);
        need(normPct <= NORM_MAX_PCT, String.format(Locale.US, "standard normalizations %.1f%% > %.0f%%", normPct, NORM_MAX_PCT));
        need(p50ms <= MAX_P50_MS, String.format(Locale.US, "p50 %.3fms > %.0fms", p50ms, MAX_P50_MS));
        need(punctFails <= PUNCT_FAILS_MAX, "punctuation/numeral class failures: " + punctFailsList);
        need(policyFails == 0, "critical-field policy violations: " + policyFails);
    }

    /** The bare form typed instead of a marked char (realistic confusion error), or null. */
    private static String inject(String w) {
        if (w.length() < 2) return null;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            char bare = 0;
            if (c == 'أ' || c == 'إ' || c == 'آ') bare = 'ا';
            else if (c == 'ة') bare = 'ه';
            else if (c == 'ى') bare = 'ي';
            else if (c == 'ؤ') bare = 'و';
            else if (c == 'ئ') bare = 'ي';
            if (bare != 0) return w.substring(0, i) + bare + w.substring(i + 1);
        }
        return null;
    }

    /** A same-norm hamza/ة re-spelling — the documented standard-orthography policy. */
    private static boolean isDocumentedNorm(String typed, String got) {
        return Correction2.confusionOf(typed, got)
                && Dictionary.norm(typed).equals(Dictionary.norm(got))
                && Correction2.isStandardOnlyDirection(typed, got);
    }

    private static double percentileMs(long[] ns, double p) {
        long[] copy = ns.clone();
        java.util.Arrays.sort(copy);
        int idx = (int) Math.min(copy.length - 1, Math.round(p * copy.length));
        return copy[idx] / 1e6;
    }
}
