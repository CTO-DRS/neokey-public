package com.hark.neokey;

import java.util.Arrays;

/**
 * NNet — pure-Java inference engine over NQF quantized weights (roadmap Phase 1,
 * item A1: «محرك استدلال نقي Java — مضاعفة مصفوفات كمية بلا أي مكتبة خارجية»).
 *
 * Numerics (the contract week A2 trains against):
 *   • weights: int8 symmetric per-tensor (Q8) — value = q * scale, scale fixed
 *     at export time; int8 keeps the model at ~1 byte/param (the <8MB budget).
 *   • activations: dynamically quantized per-tensor to int8 right before each
 *     dense layer (scale = max|x|/127), accumulated as int32, then dequantized
 *     once per output element by (wscale × ascale). That is the real
 *     int8×int8→int32 quantized matrix-vector product — no float FMA path.
 *   • overflow analysis: |Σ w·x| ≤ 127·127·cols; the engine refuses cols > 32768
 *     (127²·32768 = 528M < 2³¹), so int accumulation cannot overflow by design.
 *   • zero-vector inputs get a 1e-8 scale (never NaN); nothing here can produce
 *     NaN from finite weights.
 *
 * Everything is deterministic: same weights + same input → bit-identical logits
 * (no parallelism, no HashMap iteration in any numeric path).
 */
public final class NNet {

    /** Accumulation guard: beyond this width the int32 dot product could overflow. */
    public static final int MAX_COLS = 32768;

    private NNet() { }

    // ══════════════════════════ quantization ══════════════════════════

    /** Dynamic per-tensor activation scale: max|x| / 127 (≥ 1e-8, never 0). */
    public static float dynQuantScale(float[] x) {
        float m = 0f;
        for (int i = 0; i < x.length; i++) { float a = Math.abs(x[i]); if (a > m) m = a; }
        return m > 0f ? m / 127f : 1e-8f;
    }

    /** Quantize to int8 symmetric, clamped to [-127, 127] (never -128 — symmetric grid). */
    public static void quantize(float[] x, float scale, byte[] out) {
        for (int i = 0; i < x.length; i++) {
            int q = Math.round(x[i] / scale);
            if (q > 127) q = 127; else if (q < -127) q = -127;
            out[i] = (byte) q;
        }
    }

    /** Dequantize one row of a Q8 weight matrix into out[0..cols). */
    public static void deqRow(byte[] w, float scale, int row, int cols, float[] out) {
        final int off = row * cols;
        for (int c = 0; c < cols; c++) out[c] = w[off + c] * scale;
    }

    // ══════════════════════════ core ops ══════════════════════════

    /**
     * The quantized matvec: out[r] = (Σ_c w8[r][c]·x8[c]) · (wscale·ascale) + bias[r].
     * Pure int32 accumulation — this is the operation the 8ms budget measures.
     */
    public static void matVecQ8(byte[] w, float wscale, byte[] x8, float ascale,
                                float[] bias, float[] out, int rows, int cols) {
        if (cols > MAX_COLS) throw new IllegalArgumentException(
                "cols " + cols + " > " + MAX_COLS + " would risk int32 overflow");
        if (x8.length < cols) throw new IllegalArgumentException("activation buffer < cols");
        if (w.length < rows * cols) throw new IllegalArgumentException("weight buffer < rows*cols");
        final float deq = wscale * ascale;
        for (int r = 0; r < rows; r++) {
            int off = r * cols, sum = 0;
            for (int c = 0; c < cols; c++) sum += w[off + c] * x8[c];
            out[r] = sum * deq + (bias != null ? bias[r] : 0f);
        }
    }

    /** fp32 reference matvec (tests/gate compare the quantized path against this). */
    public static void matVecF32(float[] w, float[] x, float[] bias, float[] out, int rows, int cols) {
        for (int r = 0; r < rows; r++) {
            int off = r * cols;
            float sum = 0f;
            for (int c = 0; c < cols; c++) sum += w[off + c] * x[c];
            out[r] = sum + (bias != null ? bias[r] : 0f);
        }
    }

    /** LayerNorm (biased variance, eps 1e-5): out = (x-μ)/√(σ²+eps)·γ + β. */
    public static void layerNorm(float[] x, float[] gamma, float[] beta, float[] out) {
        float mean = 0f;
        for (int i = 0; i < x.length; i++) mean += x[i];
        mean /= x.length;
        float var = 0f;
        for (int i = 0; i < x.length; i++) { float d = x[i] - mean; var += d * d; }
        var /= x.length;
        final float inv = 1f / (float) Math.sqrt(var + 1e-5f);
        for (int i = 0; i < x.length; i++) out[i] = (x[i] - mean) * inv * gamma[i] + beta[i];
    }

    /** GELU (tanh approximation), in place: 0.5x(1+tanh(√(2/π)(x+0.044715x³))). */
    public static void gelu(float[] x) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            x[i] = 0.5f * v * (1f + (float) Math.tanh(0.7978845608f * (v + 0.044715f * v * v * v)));
        }
    }

    /** ReLU, in place. */
    public static void relu(float[] x) {
        for (int i = 0; i < x.length; i++) if (x[i] < 0f) x[i] = 0f;
    }

    /** Numerically stable softmax (in place), optional temperature (≤ 0 → 1). */
    public static void softmax(float[] x, float temp) {
        float t = temp > 0f ? temp : 1f;
        float max = x[0];
        for (int i = 1; i < x.length; i++) if (x[i] > max) max = x[i];
        float sum = 0f;
        for (int i = 0; i < x.length; i++) { x[i] = (float) Math.exp((x[i] - max) / t); sum += x[i]; }
        for (int i = 0; i < x.length; i++) x[i] /= sum;
    }

    /**
     * Top-k indices by value, best first. O(k·V) selection — k is single digits,
     * V ~ thousands; no allocation beyond the result. Ties resolve to the LOWER
     * index (deterministic across runs and devices).
     */
    public static int[] topK(float[] x, int k) {
        if (k <= 0) return new int[0];
        if (k > x.length) k = x.length;
        int[] res = new int[k];
        boolean[] used = new boolean[x.length];
        for (int j = 0; j < k; j++) {
            int best = -1;
            for (int i = 0; i < x.length; i++) {
                if (used[i]) continue;
                if (best < 0 || x[i] > x[best] || (x[i] == x[best] && i < best)) best = i;
            }
            used[best] = true;
            res[j] = best;
        }
        return res;
    }

    // ══════════════════════════ model runner ══════════════════════════

    /**
     * The A1/A2 model contract: a next-word MLP head over a shared token
     * embedding, loaded from an NQF file. Tensor names are the format's public
     * API (documented in docs/NQF.md):
     *
     *   emb   (vocab × embDim, Q8)  shared token embedding
     *   fc0.w (hidden × ctx·embDim, Q8) + fc0.b (hidden, F32)
     *   ln.g, ln.b (hidden, F32)    LayerNorm after fc0 (optional but recommended)
     *   out.w (vocab × hidden, Q8)  + out.b (vocab, F32)
     *
     * forward(ctx ids) = concat deq(emb[id]) → quantize → matVecQ8(fc0) →
     *                    LayerNorm → GELU → quantize → matVecQ8(out) → logits.
     */
    public static final class Mlp {
        public final int vocab, embDim, hidden, ctx;
        private final byte[] emb, w0, w1;
        private final float sEmb, s0, s1;
        private final float[] b0, b1, lng, lnb;
        private final float[] hid, hid2, cat;   // reusable buffers — zero allocation per inference
        private final byte[] q0, q1;
        private final float[] logits;

        public Mlp(NQF.File f, int ctx) {
            NQF.Tensor e = f.tensor("emb");
            if (e.dims.length != 2) throw new IllegalArgumentException("emb must be rank 2");
            this.vocab = e.dims[0];
            this.embDim = e.dims[1];
            this.ctx = ctx;
            NQF.Tensor a = f.tensor("fc0.w");
            if (a.dims.length != 2 || a.dims[1] != ctx * embDim)
                throw new IllegalArgumentException("fc0.w must be hidden × ctx·embDim, got "
                        + a.dims[0] + "×" + a.dims[1] + " (ctx·embDim=" + (ctx * embDim) + ")");
            this.hidden = a.dims[0];
            NQF.Tensor o = f.tensor("out.w");
            if (o.dims.length != 2 || o.dims[0] != vocab || o.dims[1] != hidden)
                throw new IllegalArgumentException("out.w must be vocab × hidden");
            this.emb = e.q8; this.sEmb = e.scale;
            this.w0 = a.q8; this.s0 = a.scale;
            this.w1 = o.q8; this.s1 = o.scale;
            this.b0 = f.tensor("fc0.b").f32;
            this.b1 = f.tensor("out.b").f32;
            boolean hasG = f.has("ln.g"), hasB = f.has("ln.b");
            if (hasG != hasB) throw new IllegalArgumentException("ln.g/ln.b must come together");
            this.lng = hasG ? f.tensor("ln.g").f32 : null;
            this.lnb = hasB ? f.tensor("ln.b").f32 : null;
            if (hasG && (lng.length != hidden || lnb.length != hidden))
                throw new IllegalArgumentException("ln.g/ln.b must be hidden-sized");
            if (b0.length != hidden || b1.length != vocab)
                throw new IllegalArgumentException("bias sizes must match layers");
            this.cat = new float[ctx * embDim];
            this.hid = new float[hidden];
            this.hid2 = new float[hidden];
            this.q0 = new byte[ctx * embDim];
            this.q1 = new byte[hidden];
            this.logits = new float[vocab];
        }

        /** logits for a context of exactly ctx token ids (id must be < vocab). */
        public float[] forward(int[] ids) {
            if (ids.length != ctx) throw new IllegalArgumentException("need ctx=" + ctx + " ids");
            for (int i = 0; i < ctx; i++) {
                if (ids[i] < 0 || ids[i] >= vocab) throw new IllegalArgumentException("id out of vocab: " + ids[i]);
                deqRow(emb, sEmb, ids[i], embDim, cat, i * embDim);
            }
            float as0 = dynQuantScale(cat);
            quantize(cat, as0, q0);
            matVecQ8(w0, s0, q0, as0, b0, hid, hidden, ctx * embDim);
            if (lng != null) layerNorm(hid, lng, lnb, hid2);
            else System.arraycopy(hid, 0, hid2, 0, hidden);
            gelu(hid2);
            float as1 = dynQuantScale(hid2);
            quantize(hid2, as1, q1);
            matVecQ8(w1, s1, q1, as1, b1, logits, vocab, hidden);
            return logits;
        }

        /** Deterministic top-k next-word prediction (ids → token indices). */
        public int[] predict(int[] ids, int k) {
            return topK(forward(ids), k);
        }
        public float[] logits() { return logits; }
    }

    /** deqRow overload writing into a slice of out (used by the concat path). */
    public static void deqRow(byte[] w, float scale, int row, int cols, float[] out, int outOff) {
        final int off = row * cols;
        for (int c = 0; c < cols; c++) out[outOff + c] = w[off + c] * scale;
    }

    /** Deep-equality helper for tests/gate: bit-exact float comparison. */
    public static boolean bitEquals(float[] a, float[] b) {
        return Arrays.equals(a, b);
    }
}
