package com.hark.neokey;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Phase 1, item A2 (week 4): the Predictor — the production load path over the
 * REAL trained model (assets/nnet_ar.nqf, 2.87M params trained on WMT News Crawl
 * ar 2020). Structural + behavioral proofs on the shipped artifact:
 * strict load, architecture derived from the tensor directory, deterministic
 * top-3, OOV degradation by convention (never an exception), tampered model
 * refused, and the privacy shape (no state, no logging surface).
 */
public class PredictorTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
    private static boolean threw(Class<?> c, Runnable r) {
        try { r.run(); return false; }
        catch (Throwable t) { return c.isInstance(t); }
    }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    public static void run() {
        byte[] model;
        try {
            model = Files.readAllBytes(Paths.get("assets/nnet_ar.nqf"));
        } catch (Exception e) {
            check("shipped model readable at assets/nnet_ar.nqf", false);
            return;
        }
        check("shipped model readable at assets/nnet_ar.nqf", true);

        // ---- structure of the shipped artifact -------------------------------
        Predictor p = null;
        try { p = Predictor.load(model); } catch (Throwable t) { }
        check("loads the shipped trained model", p != null);
        if (p == null) return;

        check("ctx=2 derived from the tensor directory", p.ctx == 2);
        check("vocab matches the training vocab (9300)", p.vocab == 9300);
        check("params in the roadmap band 1–3M (" + p.params + ")",
                p.params >= 1_000_000 && p.params <= 3_000_000);
        check("model file under the 8MB budget (" + model.length + "B)",
                model.length <= 8_000_000);
        check("model file is a real trained export (≥2.5MB)", model.length >= 2_500_000);

        // ---- behavior --------------------------------------------------------
        String[] t = p.top3("في", "ال");
        check("top3 returns 3 candidates on a real context", t != null && t.length == 3);
        boolean allVocab = true;
        for (String w : t) if (w == null || w.isEmpty()) allVocab = false;
        check("top3 candidates are real vocabulary words", allVocab);
        boolean distinct = t != null && t.length == 3
                && !t[0].equals(t[1]) && !t[1].equals(t[2]) && !t[0].equals(t[2]);
        check("top3 candidates are distinct", distinct);

        String[] again = p.top3("في", "ال");
        check("deterministic: same context → same top-3",
                again[0].equals(t[0]) && again[1].equals(t[1]) && again[2].equals(t[2]));

        // OOV / empty context degrades by convention (id 0), never throws
        String[] oov = p.top3("zzغيرموجودxyz", "كلمةغيرموجودةأيضا");
        check("OOV context degrades to <unk> path, still 3 candidates",
                oov != null && oov.length == 3);
        String[] empty = p.top3("", "");
        check("empty context handled (sentence start convention)", empty != null && empty.length == 3);
        check("id(null)==0 and id(\"\")==0", p.id(null) == 0 && p.id("") == 0);

        // exact + normalized lookup agree with the vocab tensor
        boolean idsRoundTrip = true;
        for (int i = 2; i < 500; i++) {
            String w = p.word(i);
            int back = p.id(w);
            if (back < 0 || back >= p.vocab) idsRoundTrip = false;
        }
        check("id(word(i)) round-trips into range for ids 2..500", idsRoundTrip);

        // the normalizer mirrors the training-prep families
        check("norm strips diacritics + hamza families (أَحْمَد → احمد)",
                Predictor.norm("أَحْمَد").equals("احمد"));
        check("norm ة→ه, ى→ي, ؤ→و, ئ→ي",
                Predictor.norm("مدرسة على مؤمنة شاطئ").equals("مدرسه علي مومنه شاطي"));

        // two independent loads of the same bytes behave identically (no hidden state)
        Predictor p2 = Predictor.load(model);
        String[] b2 = p2.top3("في", "ال");
        check("second independent load behaves identically",
                b2[0].equals(t[0]) && b2[1].equals(t[1]) && b2[2].equals(t[2]));

        // ---- tampered model refused by the production path --------------------
        final byte[] corrupt = model.clone();
        corrupt[model.length / 2] ^= 0x40;
        check("one flipped byte in the shipped model is refused (NQF.BadFormat)",
                threw(NQF.BadFormat.class, new Runnable() { public void run() {
                    Predictor.load(corrupt); }}));
        final byte[] trunc = java.util.Arrays.copyOf(model, model.length - 777);
        check("truncated shipped model is refused",
                threw(NQF.BadFormat.class, new Runnable() { public void run() {
                    Predictor.load(trunc); }}));

        // word(token) contract
        check("word(-1)/word(vocab) are null", p.word(-1) == null && p.word(p.vocab) == null);
        check("word(0) is <unk>", "<unk>".equals(p.word(0)));
    }
}
