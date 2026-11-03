package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Predictor — the A2 on-device neural next-word model (roadmap Phase 1, item A2):
 * the Android-free bridge between the shipped NQF weights (assets/nnet_ar.nqf,
 * trained on open Arabic text — WMT News Crawl ar 2020) and the keyboard.
 *
 * PURE logic (zero android.*) — JVM-tested exactly as shipped, same discipline as
 * Integrity/Correction2: the service hands the asset bytes in, everything else
 * happens here.
 *
 * Contract (docs/NQF.md §4 — tensor names are the model's public API):
 *   emb (vocab×emb Q8) · fc0.w (hidden×ctx·emb Q8) + fc0.b · ln.g/ln.b ·
 *   out.w (vocab×hidden Q8) + out.b · vocab.words (rank-1 Q8 = raw UTF-8 of the
 *   vocabulary joined by '\n', id order = embedding row order).
 * Architecture is derived FROM the tensor directory (fc0.w cols / emb dim = ctx) —
 * no meta parsing, the format itself is the contract. A model whose vocab.words
 * line count disagrees with the embedding rows is refused at load (the same
 * "never a half-built model" rule as NQF.read).
 *
 * Lookup: exact word → id, else normalized (the corpus normalizer: hamza/ة/ى
 * families + diacritics), else <unk>(0) — the model was trained with OOV context
 * mapped to 0, so an unseen word degrades to the same convention, never an exception.
 *
 * Privacy: no user text is stored — top3() arguments are transient, there is no
 * logging, no static state, no identifiers anywhere.
 */
public final class Predictor {

    public final int vocab, ctx, params;
    private final NNet.Mlp mlp;
    private final String[] words;
    private final HashMap<String, Integer> exact;
    private final HashMap<String, Integer> normMap;

    /** Strict load: any inconsistency throws — never returns a half-built predictor. */
    public static Predictor load(byte[] nqf) {
        return new Predictor(NQF.read(nqf));
    }

    private Predictor(NQF.File f) {
        NQF.Tensor e = f.tensor("emb");
        if (e.dims.length != 2) throw new IllegalArgumentException("emb must be rank 2");
        this.vocab = e.dims[0];
        NQF.Tensor a = f.tensor("fc0.w");
        if (a.dims.length != 2 || a.dims[1] % e.dims[1] != 0 || a.dims[1] / e.dims[1] > 6)
            throw new IllegalArgumentException("fc0.w cols must be ctx·embDim (ctx 1..6)");
        this.ctx = a.dims[1] / e.dims[1];
        this.mlp = new NNet.Mlp(f, ctx);
        NQF.Tensor vw = f.tensor("vocab.words");
        String[] ws = new String(vw.q8, StandardCharsets.UTF_8).split("\n", -1);
        if (ws.length != vocab)
            throw new IllegalArgumentException("vocab.words has " + ws.length
                    + " entries but the embedding has " + vocab + " rows — refusing");
        this.words = ws;
        this.exact = new HashMap<>(vocab * 2);
        this.normMap = new HashMap<>(vocab * 2);
        for (int i = 0; i < vocab; i++) {
            String w = ws[i];
            if (!exact.containsKey(w)) exact.put(w, i);
            String n = norm(w);
            if (!normMap.containsKey(n)) normMap.put(n, i);
        }
        this.params = vocab * e.dims[1] + a.dims[0] * a.dims[1] + vocab * a.dims[0]
                + a.dims[0] + 2 * a.dims[0] + vocab;
    }

    /** Token id for a word: exact → normalized → <unk>(0). Empty/OOV context = 0. */
    public int id(String w) {
        if (w == null || w.isEmpty()) return 0;
        Integer i = exact.get(w);
        if (i != null) return i;
        i = normMap.get(norm(w));
        return i != null ? i : 0;
    }

    /**
     * Deterministic top-3 next-word candidates given up to two context words.
     * Returns model ids' words in ranking order; never null, length ≤ 3.
     * OOV context degrades to <unk> — the training convention — never an exception.
     */
    public String[] top3(String prev2, String prev1) {
        int[] k = topIds(prev2, prev1, 3);
        String[] out = new String[k.length];
        for (int i = 0; i < k.length; i++) out[i] = words[k[i]];
        return out;
    }

    /** Deterministic top-k TOKEN IDS (gate/ranker primitives). */
    public int[] topIds(String prev2, String prev1, int k) {
        int[] ids = new int[ctx];
        if (ctx == 1) { ids[0] = id(prev1); }
        else {
            ids[0] = id(prev2);
            ids[1] = id(prev1);
        }
        return mlp.predict(ids, k);
    }

    /**
     * Copy of the full logits vector for a context (the fused ranker's input;
     * a copy — the engine's internal buffer is never aliased out).
     */
    public float[] logits(String prev2, String prev1) {
        int[] ids = new int[ctx];
        if (ctx == 1) { ids[0] = id(prev1); }
        else {
            ids[0] = id(prev2);
            ids[1] = id(prev1);
        }
        float[] lg = mlp.forward(ids);
        return java.util.Arrays.copyOf(lg, lg.length);
    }

    public String word(int tokenId) {
        return tokenId >= 0 && tokenId < vocab ? words[tokenId] : null;
    }

    /**
     * The corpus normalizer (same families the training prep used): hamza variants
     * → ا, ة→ه, ى/ئ→ي, ؤ→و, Arabic diacritics stripped, ASCII lowercased.
     */
    static String norm(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ') ch = 'ا';
            else if (ch == 'ة') ch = 'ه';
            else if (ch == 'ى' || ch == 'ئ') ch = 'ي';
            else if (ch == 'ؤ') ch = 'و';
            else if (ch >= 0x064B && ch <= 0x0652) continue;
            out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }
}
