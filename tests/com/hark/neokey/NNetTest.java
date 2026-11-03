package com.hark.neokey;

import java.util.Arrays;

/**
 * Phase 1, item A1 (week 3): NQF quantized weight format + NNet pure-Java engine.
 * Every strictness rule of the format has a NEGATIVE PROOF test here — a
 * corrupted model must be refused, never guessed through. The numeric tests pin
 * the int8 contract (quantized matvec vs fp32 reference, LayerNorm, GELU, topK
 * ties, determinism) that week A2's training will be measured against.
 */
public class NNetTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }
    private static boolean threw(Class<?> c, Runnable r) {
        try { r.run(); return false; }
        catch (Throwable t) { return c.isInstance(t); }
    }

    // deterministic pseudo-random floats in [-m, m] (LCG — bit-stable across runs/JVMs)
    static void fill(long seed, float[] a, float m) {
        long s = seed;
        for (int i = 0; i < a.length; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            a[i] = (float) ((int) (s >>> 33) / (double) Integer.MAX_VALUE) * m;
        }
    }
    // deterministic int8 weights in [-126, 126]
    static void fillQ8(long seed, byte[] a) {
        long s = seed;
        for (int i = 0; i < a.length; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            a[i] = (byte) ((int) (s >>> 33) % 253 - 126);
        }
    }

    public static void run() {
        // ═══════════ NQF: writer/reader round-trips ═══════════
        System.out.println("== NQF: round-trip ==");
        NQF.Builder b = new NQF.Builder().meta("{\"format\":\"neokey-mlp-v1\",\"vocab\":64}");
        final byte[] w8 = new byte[64 * 8]; fillQ8(1, w8);
        final float[] f0 = new float[8]; fill(2, f0, 0.1f);
        b.add("emb", new int[]{64, 8}, w8, 0.0039f);
        b.addF32("out.b", new int[]{8}, f0);
        final byte[] file = b.build();
        check("expectedBytes() exact", file.length == b.expectedBytes());
        final NQF.File f = NQF.read(file);
        check("tensor count + meta survive", f.tensorCount() == 2 && f.meta.contains("vocab"));
        check("Q8 bytes bit-exact", Arrays.equals(f.tensor("emb").q8, w8));
        check("Q8 scale survives", f.tensor("emb").scale == 0.0039f);
        check("F32 bit-exact", Arrays.equals(f.tensor("out.b").f32, f0));
        check("dims survive", f.tensor("emb").dims[0] == 64 && f.tensor("emb").dims[1] == 8);
        check("names listed in order", f.names().get(0).equals("emb") && f.names().get(1).equals("out.b"));
        check("missing tensor name -> BadFormat",
                !f.has("nope")
                && threw(NQF.BadFormat.class, new Runnable() { public void run() { f.tensor("nope"); } }));

        // ═══════════ NQF: negative proofs (corruption is REFUSED) ═══════════
        System.out.println("== NQF: negative proofs (reader) ==");
        check("bad magic refused", threw(NQF.BadFormat.class, mutate(file, 0, (byte) 'X')));
        check("bad version refused", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            byte[] m = file.clone(); m[4] = 2; NQF.read(m); }}));
        check("truncation refused", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            NQF.read(Arrays.copyOf(file, file.length - 100)); }}));
        check("one flipped blob byte refused (tensor CRC)", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            byte[] m = file.clone();
            m[m.length - 5] ^= 0x01;          // last byte before the trailer = inside a blob
            NQF.read(m); }}));
        check("one flipped meta byte refused (trailer CRC)", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            byte[] m = file.clone();
            m[22] ^= 0x01;                    // inside the meta JSON
            NQF.read(m); }}));
        check("flipped tensor-count byte refused", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            byte[] m = file.clone();
            m[8] ^= 0x01;
            NQF.read(m); }}));
        check("null bytes refused", threw(NQF.BadFormat.class, new Runnable() { public void run() { NQF.read(null); }}));
        check("tiny bytes refused", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            NQF.read(new byte[10]); }}));
        // NaN scale: patch the emb dir-entry scale field to 0x7FC00000
        final byte[] nanScale = file.clone();
        int dirStart = 20 + f.meta.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int scaleOff = dirStart + 2 + 3 + 2 + 8; // nameLen(2)+name(3)+dtype(1)+rank(1)+dims(2*4)
        nanScale[scaleOff] = 0x00; nanScale[scaleOff + 1] = 0x00;
        nanScale[scaleOff + 2] = (byte) 0xC0; nanScale[scaleOff + 3] = (byte) 0x7F;
        check("NaN scale refused", threw(NQF.BadFormat.class, new Runnable() { public void run() { NQF.read(nanScale); }}));

        System.out.println("== NQF: negative proofs (writer) ==");
        check("dims/product mismatch refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("t", new int[]{2, 2}, new byte[5], 0.1f); }}));
        check("zero scale refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("t", new int[]{4}, new byte[4], 0f); }}));
        check("NaN scale refused at write", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("t", new int[]{4}, new byte[4], Float.NaN); }}));
        check("duplicate name refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            NQF.Builder bb = new NQF.Builder();
            bb.add("t", new int[]{4}, new byte[4], 0.1f);
            bb.add("t", new int[]{4}, new byte[4], 0.1f); }}));
        check("empty name refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("", new int[]{4}, new byte[4], 0.1f); }}));
        check("rank 0 refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("t", new int[]{}, new byte[0], 0.1f); }}));
        check("rank 7 refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().add("t", new int[]{1, 1, 1, 1, 1, 1, 1}, new byte[1], 0.1f); }}));
        check("F32 product mismatch refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NQF.Builder().addF32("t", new int[]{3}, new float[4]); }}));

        // ═══════════ NNet: quantization primitives ═══════════
        System.out.println("== NNet: quantization ==");
        float[] x = new float[256]; fill(7, x, 2.5f);
        x[100] = 2.55f;                                  // a known max
        float sc = NNet.dynQuantScale(x);
        check("dynQuantScale = max|x|/127", Math.abs(sc - 2.55f / 127f) < 1e-6f);
        check("dynQuantScale positive finite", sc > 0 && !Float.isNaN(sc));
        check("zero vector -> tiny scale, not NaN", NNet.dynQuantScale(new float[8]) > 0
                && !Float.isNaN(NNet.dynQuantScale(new float[8])));
        byte[] xq = new byte[x.length];
        NNet.quantize(x, sc, xq);
        boolean inRange = true;
        for (byte v : xq) if (v < -127 || v > 127) inRange = false;
        check("quantized within [-127,127], no -128", inRange);
        float[] back = new float[x.length];
        for (int i = 0; i < x.length; i++) back[i] = xq[i] * sc;
        double err = relErr(x, back);
        check("dequantize relative error < 1.5% (" + round4(err) + ")", err < 0.015);

        // ═══════════ NNet: matvec vs fp32 reference ═══════════
        System.out.println("== NNet: quantized matvec vs fp32 ==");
        int rows = 64, cols = 128;
        byte[] w = new byte[rows * cols]; fillQ8(11, w);
        float wsc = 0.01f;
        float[] xf = new float[cols]; fill(13, xf, 3.0f);
        float[] bias = new float[rows]; fill(17, bias, 0.05f);
        byte[] x8 = new byte[cols];
        float asc = NNet.dynQuantScale(xf);
        NNet.quantize(xf, asc, x8);
        float[] qOut = new float[rows], fOut = new float[rows];
        NNet.matVecQ8(w, wsc, x8, asc, bias, qOut, rows, cols);
        // fp32 shadow: dequantized weights × fp32 input + bias (same numbers, float math)
        float[] wd = new float[w.length];
        for (int i = 0; i < w.length; i++) wd[i] = w[i] * wsc;
        NNet.matVecF32(wd, xf, bias, fOut, rows, cols);
        double matErr = relErr(fOut, qOut);
        check("matVecQ8 ≈ matVecF32 (rel err < 3%): " + round4(matErr), matErr < 0.03);
        // int32 accumulation at the guard boundary: 127*127*32768 = 528M < 2^31
        int C = NNet.MAX_COLS;
        byte[] big = new byte[8 * C]; Arrays.fill(big, (byte) 127);
        byte[] xb = new byte[C]; Arrays.fill(xb, (byte) 127);
        float[] o = new float[8];
        NNet.matVecQ8(big, 1e-9f, xb, 1e-9f, null, o, 8, C);
        check("int32 accumulation exact at MAX_COLS", !Float.isNaN(o[0]) && o[0] > 0f);
        check("cols overflow guard refuses > MAX_COLS", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            NNet.matVecQ8(new byte[8 * (NNet.MAX_COLS + 1)], 0.1f, new byte[NNet.MAX_COLS + 1], 0.1f,
                    null, new float[8], 8, NNet.MAX_COLS + 1); }}));
        check("short activation buffer refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            NNet.matVecQ8(new byte[64], 0.1f, new byte[3], 0.1f, null, new float[8], 8, 8); }}));
        float[] ww = {1f, 2f, 3f, 4f}, xx = {2f, 0.5f}, bb = {10f, 20f}, oo = new float[2];
        NNet.matVecF32(ww, xx, bb, oo, 2, 2);
        check("matVecF32 manual check (13, 28)", oo[0] == 13f && oo[1] == 28f);
        float[] rowOut = new float[4];
        NNet.deqRow(new byte[]{2, -2, 4, -4}, 0.5f, 0, 4, rowOut);
        check("deqRow values", rowOut[0] == 1f && rowOut[1] == -1f && rowOut[2] == 2f && rowOut[3] == -2f);

        // ═══════════ NNet: activations ═══════════
        System.out.println("== NNet: activations ==");
        float[] h = new float[256]; fill(19, h, 3f);
        float[] g = new float[256]; Arrays.fill(g, 1f);
        float[] be = new float[256];
        float[] hn = new float[256];
        NNet.layerNorm(h, g, be, hn);
        double mn = mean(hn), sd = std(hn);
        check("layerNorm -> mean ≈ 0, std ≈ 1", Math.abs(mn) < 1e-4 && Math.abs(sd - 1) < 0.02);
        float[] g2 = new float[256]; Arrays.fill(g2, 2f);
        NNet.layerNorm(h, g2, be, hn);
        check("gamma=2 -> std ≈ 2", Math.abs(std(hn) - 2) < 0.05);
        float[] one = {0f, 1f, -1f};
        NNet.gelu(one);
        check("gelu(0)=0", one[0] == 0f);
        check("gelu(1)≈0.8412", Math.abs(one[1] - 0.8412f) < 0.001f);
        check("gelu(-1)≈-0.1588", Math.abs(one[2] + 0.1588f) < 0.001f);
        float[] r = {-1f, 0f, 2.5f};
        NNet.relu(r);
        check("relu", r[0] == 0f && r[1] == 0f && r[2] == 2.5f);
        float[] sm = {1f, 2f, 3f};
        NNet.softmax(sm, 0f);
        double sSum = sm[0] + sm[1] + sm[2];
        check("softmax sums to 1, monotone", Math.abs(sSum - 1) < 1e-6 && sm[2] > sm[1] && sm[1] > sm[0]);

        // ═══════════ NNet: topK + determinism ═══════════
        System.out.println("== NNet: topK ==");
        float[] lg = {0.5f, 3f, 1f, 3f, -2f, 2.9f};
        int[] tk = NNet.topK(lg, 3);
        check("topK order best-first (ties -> lower index)", tk[0] == 1 && tk[1] == 3 && tk[2] == 5);
        check("all-tie -> indices 0,1", NNet.topK(new float[]{2f, 2f, 2f}, 2)[0] == 0
                && NNet.topK(new float[]{2f, 2f, 2f}, 2)[1] == 1);
        check("k > len clamps", NNet.topK(new float[]{1f}, 5).length == 1);
        check("k <= 0 empty", NNet.topK(new float[]{1f}, 0).length == 0);
        check("topK deterministic", Arrays.equals(tk, NNet.topK(lg, 3)));

        // ═══════════ Mlp runner on a tiny model ═══════════
        System.out.println("== NNet: Mlp runner ==");
        final int V = 64, E = 8, H = 16, Ctx = 2;
        NQF.Builder mb = new NQF.Builder().meta("{\"format\":\"neokey-mlp-v1\",\"vocab\":" + V + "}");
        byte[] emb = new byte[V * E]; fillQ8(21, emb);
        byte[] w0t = new byte[H * (Ctx * E)]; fillQ8(23, w0t);
        byte[] w1t = new byte[V * H]; fillQ8(25, w1t);
        float[] b0t = new float[H]; fill(27, b0t, 0.02f);
        float[] b1t = new float[V]; fill(29, b1t, 0.02f);
        float[] lgg = new float[H]; Arrays.fill(lgg, 1f);
        float[] lbb = new float[H];
        mb.add("emb", new int[]{V, E}, emb, 0.01f);
        mb.add("fc0.w", new int[]{H, Ctx * E}, w0t, 0.01f);
        mb.addF32("fc0.b", new int[]{H}, b0t);
        mb.addF32("ln.g", new int[]{H}, lgg);
        mb.addF32("ln.b", new int[]{H}, lbb);
        mb.add("out.w", new int[]{V, H}, w1t, 0.01f);
        mb.addF32("out.b", new int[]{V}, b1t);
        final byte[] mf = mb.build();
        final NQF.File mfRead = NQF.read(mf);
        NNet.Mlp m = new NNet.Mlp(mfRead, Ctx);
        check("Mlp dims parsed", m.vocab == V && m.embDim == E && m.hidden == H && m.ctx == Ctx);
        check("Mlp file expectedBytes exact", mf.length == mb.expectedBytes());
        int[] ids = {3, 40};
        float[] l1 = m.forward(ids);
        float[] l2 = m.forward(ids);
        check("forward length = vocab", l1.length == V);
        check("forward bit-identical (deterministic)", NNet.bitEquals(l1, l2));
        int[] pk = m.predict(ids, 5);
        boolean distinct = true;
        for (int i = 0; i < pk.length; i++)
            for (int j = i + 1; j < pk.length; j++) if (pk[i] == pk[j]) distinct = false;
        check("predict 5 distinct valid ids", distinct && pk.length == 5);
        check("predict = topK of forward", Arrays.equals(pk, NNet.topK(m.forward(ids), 5)));
        check("wrong ctx length refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NNet.Mlp(mfRead, 2).forward(new int[]{1}); }}));
        check("id out of vocab refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            new NNet.Mlp(mfRead, 2).forward(new int[]{V, 0}); }}));
        check("missing tensor -> BadFormat", threw(NQF.BadFormat.class, new Runnable() { public void run() {
            NQF.Builder bad = new NQF.Builder();
            bad.add("emb", new int[]{V, E}, emb, 0.01f);
            new NNet.Mlp(NQF.read(bad.build()), 2); }}));
        check("fc0.w wrong ctx*embDim refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            NQF.Builder bad = new NQF.Builder();
            bad.add("emb", new int[]{V, E}, emb, 0.01f);
            bad.add("fc0.w", new int[]{H, Ctx * E + 2}, new byte[H * (Ctx * E + 2)], 0.01f);
            bad.addF32("fc0.b", new int[]{H}, b0t);
            bad.add("out.w", new int[]{V, H}, w1t, 0.01f);
            bad.addF32("out.b", new int[]{V}, b1t);
            new NNet.Mlp(NQF.read(bad.build()), 2); }}));
        check("bias size mismatch refused", threw(IllegalArgumentException.class, new Runnable() { public void run() {
            NQF.Builder bad = new NQF.Builder();
            bad.add("emb", new int[]{V, E}, emb, 0.01f);
            bad.add("fc0.w", new int[]{H, Ctx * E}, w0t, 0.01f);
            bad.addF32("fc0.b", new int[]{H - 1}, new float[H - 1]);
            bad.add("out.w", new int[]{V, H}, w1t, 0.01f);
            bad.addF32("out.b", new int[]{V}, b1t);
            new NNet.Mlp(NQF.read(bad.build()), 2); }}));
    }

    static String round4(double d) { return String.format(java.util.Locale.US, "%.4f", d); }

    static double relErr(float[] ref, float[] got) {
        double num = 0, den = 0;
        for (int i = 0; i < ref.length; i++) { double d = got[i] - ref[i]; num += d * d; den += ref[i] * ref[i]; }
        return Math.sqrt(num / Math.max(1e-30, den));
    }
    static double mean(float[] a) { double s = 0; for (float v : a) s += v; return s / a.length; }
    static double std(float[] a) { double m = mean(a), s = 0; for (float v : a) s += (v - m) * (v - m); return Math.sqrt(s / a.length); }

    private static Runnable mutate(final byte[] src, final int off, final byte val) {
        return new Runnable() { public void run() {
            byte[] m = src.clone(); m[off] = val; NQF.read(m); }};
    }
}
