package com.hark.neokey;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TrainModel — item A2 trainer (roadmap Phase 1, week 4): «تدريب نموذج صغير (1–3M معامل)
 * على نص عربي مفتوح + تصدير بالتنسيق».
 *
 * Pure JVM, deterministic (fixed-seed LCG everywhere), no external libraries.
 *
 * Data (dictsrc/corpus/, produced by scripts/w10_prep.py — open text, none of it ships):
 *   vocab.txt        id \t word   (<unk>=0, <pad>=1, then frequency order)
 *   train_pairs.tsv  ctx2 \t ctx1 \t target \t weight   (real 2-word contexts from
 *                    WMT Arabic News Crawl 2020 + phrases.txt MINUS the 5 sentences
 *                    that overlap the held-out testset + rank-weighted bigram-table aux)
 *   dev_pairs.tsv    same layout — 2% of pairs held OUT of training; used ONLY to
 *                    freeze the combined-ranker weights (A,B). testset_ar.txt is
 *                    NEVER read here — the gate measures it once, frozen.
 *
 * Method: next-word MLP per the NNet.Mlp contract (emb → ctx concat → fc0 →
 * LayerNorm → GELU → out → logits), trained with logistic NEGATIVE SAMPLING
 * (K negatives from unigram^0.75, word2vec-style) — full-softmax over V=9300
 * would cost ~4.6M MAC/update; sampling cuts it to ~0.12M so 6.4M pairs/epoch
 * train in minutes on this CPU. Full softmax is used at inference and dev eval.
 *
 * Export: per-tensor symmetric int8 (scale = max|w|/127) through NQF.Builder →
 * assets/nnet_ar.nqf, self-describing (the vocab rides in the meta JSON so
 * Predictor2 never needs a sidecar file — the A1 format contract).
 */
public final class TrainModel {

    // ── hyperparameters (frozen here = reproducible; gate asserts them) ──
    static final int EMB = 64, HIDDEN = 256, CTX = 2;
    static final int K_NEG = 25;
    static final int EPOCHS = 2;
    static final float LR0 = 0.03f, LR_MIN = 0.002f;
    static final long SEED = 20361102L;
    static String CORPUS_DIR = "dictsrc/corpus";
    static int EPOCHS_OVERRIDE = -1;
    static final String CKPT = "build/model/ckpt_v1.bin";

    // weights
    static float[] emb, fc0, out, b0, b1, lng, lnb;   // flat row-major
    static int V, FC_IN = CTX * EMB;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) CORPUS_DIR = args[0];              // smoke-test override
        final String outPath = args.length > 1 ? args[1] : "assets/nnet_ar.nqf";
        if (args.length > 2) EPOCHS_OVERRIDE = Integer.parseInt(args[2]);
        final long t0 = System.currentTimeMillis();
        // ── 1. vocab ──
        List<String> vocab = new ArrayList<>();
        BufferedReader vr = new BufferedReader(new InputStreamReader(
                new FileInputStream(CORPUS_DIR + "/vocab.txt"), StandardCharsets.UTF_8), 1 << 16);
        for (String line; (line = vr.readLine()) != null; ) {
            int t = line.indexOf('\t');
            if (t > 0) vocab.add(line.substring(t + 1));
        }
        vr.close();
        V = vocab.size();
        System.out.println("vocab=" + V);

        // ── 2. pairs ──
        int[] c2 = new int[1 << 23], c1 = new int[1 << 23], tg = new int[1 << 23];
        float[] wt = new float[1 << 23];
        int n = 0;
        n = readPairs(CORPUS_DIR + "/train_pairs.tsv", c2, c1, tg, wt, n);
        System.out.println("train pairs=" + n);

        // ── 3. init (word2vec-style: emb small random, fc0 small random, out zeros) ──
        long s = SEED;
        emb = new float[V * EMB];
        for (int i = 0; i < emb.length; i++) { s = lcg(s); emb[i] = (float)((s >>> 33) % 2001 - 1000) / 10000f; }
        fc0 = new float[HIDDEN * FC_IN];
        for (int i = 0; i < fc0.length; i++) { s = lcg(s); fc0[i] = (float)((s >>> 33) % 2001 - 1000) / 10000f; }
        out = new float[V * HIDDEN];
        b0 = new float[HIDDEN]; b1 = new float[V];
        lng = new float[HIDDEN]; java.util.Arrays.fill(lng, 1f);
        lnb = new float[HIDDEN];

        // ── 4. unigram^0.75 negative table ──
        int[] counts = new int[V];
        for (int i = 0; i < n; i++) counts[tg[i]]++;
        final int TAB = 1 << 22; // 4M entries
        int[] negTab = new int[TAB];
        double sum = 0;
        for (int w = 0; w < V; w++) sum += Math.pow(counts[w], 0.75);
        { int p = 0; double acc = 0;
          for (int w = 0; w < V; w++) {
              acc += Math.pow(counts[w], 0.75) / sum * TAB;
              int until = (int) acc;
              while (p < until && p < TAB) negTab[p++] = w;
          }
          while (p < TAB) negTab[p++] = 0;
        }

        // hidden buffers
        float[] xin = new float[FC_IN], xpre = new float[HIDDEN], z = new float[HIDDEN],
                h = new float[HIDDEN], hGrad = new float[HIDDEN], dz = new float[HIDDEN],
                dx = new float[HIDDEN], de = new float[FC_IN];

        // ── checkpoint resume: weights + global step (deterministic continuation) ──
        long done = 0;
        File ck = new File(CKPT);
        if (ck.exists()) {
            DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(ck), 1 << 16));
            long magic = di.readLong();
            int cv = di.readInt();
            long step = di.readLong();
            if (magic == 0x4E4B4331L && cv == V && step > 0) {
                for (int i = 0; i < emb.length; i++) emb[i] = di.readFloat();
                for (int i = 0; i < fc0.length; i++) fc0[i] = di.readFloat();
                for (int i = 0; i < out.length; i++) out[i] = di.readFloat();
                for (int i = 0; i < b0.length; i++) b0[i] = di.readFloat();
                for (int i = 0; i < b1.length; i++) b1[i] = di.readFloat();
                done = step;
                System.out.println("RESUMED from checkpoint at global step " + step);
            }
            di.close();
        }

        final long total = (long) n * epochs();
        double lossAcc = 0; int lossN = 0;
        int epochs = epochs();
        int start = (int) (done % Math.max(1, n));   // exact mid-epoch resume index
        for (int ep = (int) (done / Math.max(1, n)); ep < epochs; ep++) {
            // Fisher-Yates shuffle — RNG re-seeded per epoch so resume is bit-exact
            s = SEED ^ ((ep + 1L) * 7919L);
            for (int i = n - 1; i > 0; i--) {
                s = lcg(s);
                int j = (int) ((s >>> 33) % (i + 1));
                int tc2 = c2[i]; c2[i] = c2[j]; c2[j] = tc2;
                int tc1 = c1[i]; c1[i] = c1[j]; c1[j] = tc1;
                int tt = tg[i]; tg[i] = tg[j]; tg[j] = tt;
                float tw = wt[i]; wt[i] = wt[j]; wt[j] = tw;
            }
            for (int i = start; i < n; i++) {
                float lr = Math.max(LR_MIN, LR0 * (1f - done / (float) total));
                lossAcc += trainOne(c2[i], c1[i], tg[i], wt[i], lr, negTab, xin, xpre, z, h, hGrad, dz, dx, de,
                        SEED ^ (done * 104729L));   // per-step seed — resume is bit-exact
                lossN++;
                done++;
                if (lossN == 500000) {
                    System.out.printf(Locale.US, "ep%d %d/%d loss=%.4f lr=%.4f %ds%n",
                            ep + 1, i + 1, n, lossAcc / lossN, lr, (System.currentTimeMillis() - t0) / 1000);
                    saveCkpt(done);   // mid-epoch checkpoint — safe against process kill
                    lossAcc = 0; lossN = 0;
                }
            }
            start = 0;
            saveCkpt(done);
            double dev = devTop3(2000);
            System.out.printf(Locale.US, "epoch %d done — dev top-3 (neural, %d sample) = %.1f%%  [%ds]%n",
                    ep + 1, 2000, 100 * dev, (System.currentTimeMillis() - t0) / 1000);
        }

        // ── 5. freeze combined-ranker weights on dev (never testset) ──
        double bestA = 1, bestB = 0.5, bestAcc = -1;
        double[] gridA = {0.5, 1, 2}, gridB = {0.3, 0.5, 0.8, 1.2, 1.6};
        double[][] res = new double[gridA.length][gridB.length];
        // dev stream
        int[] d2 = new int[4000], d1 = new int[4000], dt = new int[4000];
        float[] dw = new float[4000];
        int dn = readPairs(CORPUS_DIR + "/dev_pairs.tsv", d2, d1, dt, dw, 0);
        if (dn > 3000) dn = 3000;   // deterministic first-3000 sample
        devCombined(dn, d2, d1, dt, gridA, gridB, res);
        for (int ia = 0; ia < gridA.length; ia++)
            for (int ib = 0; ib < gridB.length; ib++)
                if (res[ia][ib] > bestAcc) { bestAcc = res[ia][ib]; bestA = gridA[ia]; bestB = gridB[ib]; }
        System.out.printf(Locale.US, "ranker frozen on dev: A=%.1f B=%.1f dev-top3=%.1f%%%n", bestA, bestB, 100 * bestAcc);

        // ── 6. export NQF (int8 symmetric per tensor) + report ──
        StringBuilder vb = new StringBuilder("[");
        for (int i = 0; i < V; i++) { if (i > 0) vb.append(','); vb.append('"').append(vocab.get(i)).append('"'); }
        vb.append(']');
        String meta = "{\"format\":\"neokey-mlp-v1\",\"quant\":\"int8-symmetric-per-tensor\""
                + ",\"ctx\":" + CTX + ",\"emb\":" + EMB + ",\"hidden\":" + HIDDEN + ",\"vocab\":" + V
                + ",\"trainedOn\":\"wmt-news-crawl-ar-2020-320K + phrases(minus-testset) + bigram-ar-base-aux\""
                + ",\"epochs\":" + EPOCHS + ",\"trainPairs\":" + total
                + ",\"rankerA\":" + bestA + ",\"rankerB\":" + bestB
                + ",\"vocabJson\":" + vb.toString() + "}";
        NQF.Builder b = new NQF.Builder().meta(meta);
        b.add("emb", new int[]{V, EMB}, q8(emb), maxAbs(emb) / 127f);
        b.add("fc0.w", new int[]{HIDDEN, FC_IN}, q8(fc0), maxAbs(fc0) / 127f);
        b.addF32("fc0.b", new int[]{HIDDEN}, b0);
        b.addF32("ln.g", new int[]{HIDDEN}, lng);
        b.addF32("ln.b", new int[]{HIDDEN}, lnb);
        b.add("out.w", new int[]{V, HIDDEN}, q8(out), maxAbs(out) / 127f);
        b.addF32("out.b", new int[]{V}, b1);
        byte[] bytes = b.build();
        File appAssets = new File(outPath);
        Files.write(appAssets.toPath(), bytes);
        if (!outPath.equals("assets/nnet_ar.nqf")) {
            File outDir = new File("build/model"); outDir.mkdirs();
            Files.write(new File(outDir, "model.nqf").toPath(), bytes);
        }

        double devNeural = devTop3(3000);
        String report = "{\n \"model\": \"nnet_ar.nqf\",\n \"params_q8\": " + (V * EMB + HIDDEN * FC_IN + V * HIDDEN)
                + ",\n \"params_total\": " + (V * EMB + HIDDEN * FC_IN + V * HIDDEN + 2 * HIDDEN + HIDDEN + V + HIDDEN)
                + ",\n \"vocab\": " + V + ",\n \"file_bytes\": " + bytes.length
                + ",\n \"epochs\": " + EPOCHS + ",\n \"train_pairs\": " + total
                + ",\n \"k_neg\": " + K_NEG + ",\n \"lr0\": " + LR0 + ",\n \"seed\": " + SEED
                + ",\n \"dev_top3_neural_pct\": " + String.format(Locale.US, "%.1f", 100 * devNeural)
                + ",\n \"dev_top3_combined_pct\": " + String.format(Locale.US, "%.1f", 100 * bestAcc)
                + ",\n \"rankerA\": " + bestA + ",\n \"rankerB\": " + bestB
                + ",\n \"ranker_grid\": " + gridJson(gridA, gridB, res)
                + ",\n \"train_seconds\": " + (System.currentTimeMillis() - t0) / 1000
                + "\n}\n";
        Files.write(new File("build/model/train-report.json").toPath(), report.getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.US, "EXPORTED %s bytes=%d (params %.2fM) dev-neural=%.1f%% dev-combined=%.1f%% %ds%n",
                appAssets, bytes.length, (V * EMB + HIDDEN * FC_IN + V * HIDDEN) / 1e6, 100 * devNeural, 100 * bestAcc,
                (System.currentTimeMillis() - t0) / 1000);
    }

    // ══════════════════ checkpoint ══════════════════
    static int epochs() { return EPOCHS_OVERRIDE > 0 ? EPOCHS_OVERRIDE : EPOCHS; }
    static void saveCkpt(long step) throws Exception {
        new File("build/model").mkdirs();
        File tmp = new File(CKPT + ".tmp");
        DataOutputStream dou = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        dou.writeLong(0x4E4B4331L); dou.writeInt(V); dou.writeLong(step);
        for (float v : emb) dou.writeFloat(v);
        for (float v : fc0) dou.writeFloat(v);
        for (float v : out) dou.writeFloat(v);
        for (float v : b0) dou.writeFloat(v);
        for (float v : b1) dou.writeFloat(v);
        dou.close();
        File f = new File(CKPT);
        f.delete();
        if (!tmp.renameTo(f)) throw new IllegalStateException("checkpoint rename failed");
        System.out.println("CHECKPOINT saved at global step " + step);
    }

    // ══════════════════ training core ══════════════════
    static double trainOne(int a2, int a1, int tgt, float w, float lr, int[] negTab,
                           float[] xin, float[] xpre, float[] z, float[] h,
                           float[] hGrad, float[] dz, float[] dx, float[] de, long st) {
        // forward: concat embeddings
        System.arraycopy(emb, a2 * EMB, xin, 0, EMB);
        System.arraycopy(emb, a1 * EMB, xin, EMB, EMB);
        dense(xin, fc0, b0, xpre, HIDDEN, FC_IN);
        layerNormF(xpre, lng, lnb, z);
        geluF(z, h);
        // positive logit
        float lp = dot(out, tgt * HIDDEN, h) + b1[tgt];
        double loss = -w * logsig(lp);
        // backward accumulators
        java.util.Arrays.fill(hGrad, 0f);
        float gp = (float) (w * (sig(lp) - 1));       // weighted positive grad
        axpy(hGrad, out, tgt * HIDDEN, gp);           // hGrad += gp·out[tgt]  (pre-update)
        // K negatives
        for (int k = 0; k < K_NEG; k++) {
            st = lcg(st);
            int neg = negTab[(int) ((st >>> 33) & (negTab.length - 1))];
            if (neg == tgt) continue;
            float ln = dot(out, neg * HIDDEN, h) + b1[neg];
            loss += -logsig(-ln);
            float gn = (float) sig(ln);
            axpy(hGrad, out, neg * HIDDEN, gn);
            // out row update: out[r] -= lr·g·h
            int off = neg * HIDDEN;
            for (int j = 0; j < HIDDEN; j++) out[off + j] -= lr * gn * h[j];
            b1[neg] -= lr * gn;
        }
        { int off = tgt * HIDDEN;
          for (int j = 0; j < HIDDEN; j++) out[off + j] -= lr * gp * h[j];
          b1[tgt] -= lr * gp; }
        // hidden backward: GELU' at z
        for (int j = 0; j < HIDDEN; j++) dz[j] = hGrad[j] * geluGrad(z[j], h[j]);
        layerNormBack(xpre, lng, dz, dx);
        // fc0 backward
        java.util.Arrays.fill(de, 0f);
        for (int r = 0; r < HIDDEN; r++) {
            float g = lr * dx[r];
            if (g == 0f) continue;
            int off = r * FC_IN;
            for (int j = 0; j < FC_IN; j++) { fc0[off + j] -= g * xin[j]; de[j] += dx[r] * fc0[off + j]; }
            b0[r] -= g;
        }
        for (int j = 0; j < EMB; j++) {
            emb[a2 * EMB + j] -= lr * de[j];
            emb[a1 * EMB + j] -= lr * de[j];
        }
        return loss;
    }

    // ══════════════════ full-softmax inference (dev eval) ══════════════════
    static void fullLogits(int a2, int a1, float[] xin, float[] xpre, float[] z, float[] h, float[] logits) {
        System.arraycopy(emb, a2 * EMB, xin, 0, EMB);
        System.arraycopy(emb, a1 * EMB, xin, EMB, EMB);
        dense(xin, fc0, b0, xpre, HIDDEN, FC_IN);
        layerNormF(xpre, lng, lnb, z);
        geluF(z, h);
        dense(h, out, b1, logits, V, HIDDEN);
    }

    static double devTop3(int sample) throws Exception {
        int[] d2 = new int[sample], d1 = new int[sample], dt = new int[sample];
        float[] dw = new float[sample];
        int dn = readPairs(CORPUS_DIR + "/dev_pairs.tsv", d2, d1, dt, dw, 0);
        if (dn > sample) dn = sample;
        return devNeuralAcc(dn, d2, d1, dt, new float[FC_IN], new float[HIDDEN], new float[HIDDEN], new float[HIDDEN], new float[V]);
    }

    static double devNeuralAcc(int dn, int[] d2, int[] d1, int[] dt,
                               float[] xin, float[] xpre, float[] z, float[] h, float[] logits) {
        int hit = 0, tot = 0;
        for (int i = 0; i < dn; i++) {
            fullLogits(d2[i], d1[i], xin, xpre, z, h, logits);
            int[] top = NNet.topK(logits, 3);
            if (top[0] == dt[i] || top[1] == dt[i] || top[2] == dt[i]) hit++;
            tot++;
        }
        return tot == 0 ? 0 : (double) hit / tot;
    }

    /** combined ranker accuracy on dev: score = A·p_neural + B·tableRankBonus.
     *  Full softmax is computed ONCE per unique context (real cache, not consecutive-only),
     *  candidate probs cached per context, then all (A,B) combos evaluate from the cache. */
    static double devCombined(int dn, int[] d2, int[] d1, int[] dt, double[] gridA, double[] gridB, double[][] res) throws Exception {
        java.util.Map<Integer, int[]> rows = new java.util.HashMap<>();
        String[] vocab = readVocab();
        java.util.Map<String, Integer> id = new java.util.HashMap<>();
        for (int i = 0; i < vocab.length; i++) id.putIfAbsent(vocab[i], i);
        BufferedReader tr = new BufferedReader(new InputStreamReader(
                new FileInputStream("assets/bigram_ar_base.txt"), StandardCharsets.UTF_8), 1 << 16);
        for (String line; (line = tr.readLine()) != null; ) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            Integer a = id.get(line.substring(0, t));
            if (a == null) continue;
            String[] nx = line.substring(t + 1).split(" ");
            int[] ids = new int[Math.min(nx.length, 6)];
            int m = 0;
            for (String w : nx) { Integer x = id.get(w); if (x != null && m < 6) ids[m++] = x; }
            if (m > 0) rows.put(a, java.util.Arrays.copyOf(ids, m));
        }
        tr.close();
        // pass 1: unique contexts -> cached candidate list + neural probs
        int[] keys = new int[dn];
        java.util.Map<Integer, Integer> keyIdx = new java.util.HashMap<>();
        int[] uk2 = new int[dn], uk1 = new int[dn];
        int un = 0;
        int[] pairCtx = new int[dn];
        for (int i = 0; i < dn; i++) {
            int key = d2[i] * 65536 + d1[i];
            Integer ix = keyIdx.get(key);
            if (ix == null) { ix = un; keyIdx.put(key, ix); uk2[un] = d2[i]; uk1[un] = d1[i]; un++; }
            pairCtx[i] = ix;
            keys[i] = key;
        }
        int[][] cand = new int[un][];
        double[][] prob = new double[un][];
        float[] xin = new float[FC_IN], xpre = new float[HIDDEN], z = new float[HIDDEN],
                h = new float[HIDDEN], logits = new float[V];
        double[] p = new double[V];
        for (int u = 0; u < un; u++) {
            fullLogits(uk2[u], uk1[u], xin, xpre, z, h, logits);
            double max = 0;
            for (int w = 0; w < V; w++) if (logits[w] > max) max = logits[w];
            double sum = 0;
            for (int w = 0; w < V; w++) { p[w] = Math.exp(logits[w] - max); sum += p[w]; }
            int[] top10 = NNet.topK(logits, 10);
            int[] row = rows.get(uk1[u]);
            int[] cc = new int[16]; int cn = 0;
            for (int w : top10) cc[cn++] = w;
            if (row != null) for (int w : row) {
                boolean seen = false;
                for (int x : top10) if (x == w) { seen = true; break; }
                if (!seen && cn < 16) cc[cn++] = w;
            }
            double[] pp = new double[cn];
            for (int j = 0; j < cn; j++) pp[j] = p[cc[j]] / sum;
            cand[u] = java.util.Arrays.copyOf(cc, cn);
            prob[u] = pp;
        }
        // pass 2: all combos from cache
        for (int ia = 0; ia < gridA.length; ia++)
            for (int ib = 0; ib < gridB.length; ib++) {
                double A = gridA[ia], B = gridB[ib];
                int hit = 0, tot = 0;
                for (int i = 0; i < dn; i++) {
                    int u = pairCtx[i];
                    int[] cc = cand[u]; double[] pp = prob[u];
                    int[] row = rows.get(uk1[u]);
                    double best1 = -1, best2 = -1, best3 = -1; int w1c = -1, w2c = -1, w3c = -1;
                    for (int ci = 0; ci < cc.length; ci++) {
                        double tb = 0;
                        if (row != null) for (int r = 0; r < row.length; r++) if (row[r] == cc[ci]) { tb = (6 - r) / 6.0; break; }
                        double sc = A * pp[ci] + B * tb;
                        if (sc > best1) { best3 = best2; w3c = w2c; best2 = best1; w2c = w1c; best1 = sc; w1c = cc[ci]; }
                        else if (sc > best2) { best3 = best2; w3c = w2c; best2 = sc; w2c = cc[ci]; }
                        else if (sc > best3) { best3 = sc; w3c = cc[ci]; }
                    }
                    if (dt[i] == w1c || dt[i] == w2c || dt[i] == w3c) hit++;
                    tot++;
                }
                res[ia][ib] = tot == 0 ? 0 : (double) hit / tot;
            }
        return 0;
    }

    // ══════════════════ small numerics (train-time fp32) ══════════════════
    static void dense(float[] x, float[] w, float[] bias, float[] y, int rows, int cols) {
        for (int r = 0; r < rows; r++) {
            int off = r * cols;
            float sum = bias != null ? bias[r] : 0f;
            for (int c = 0; c < cols; c++) sum += w[off + c] * x[c];
            y[r] = sum;
        }
    }
    static void layerNormF(float[] x, float[] g, float[] b, float[] y) {
        float m = 0; for (float v : x) m += v; m /= x.length;
        float var = 0; for (float v : x) { float d = v - m; var += d * d; } var /= x.length;
        float inv = 1f / (float) Math.sqrt(var + 1e-5f);
        for (int i = 0; i < x.length; i++) y[i] = (x[i] - m) * inv * g[i] + b[i];
    }
    /** dx given dz (biased-variance LN backward): dx = γ/σ·(dz − mean(dz) − x̂·mean(dz·x̂)) */
    static void layerNormBack(float[] x, float[] g, float[] dz, float[] dx) {
        int n = x.length;
        float m = 0; for (float v : x) m += v; m /= n;
        float var = 0; for (float v : x) { float d = v - m; var += d * d; } var /= n;
        float inv = 1f / (float) Math.sqrt(var + 1e-5f);
        float mdz = 0, mdzx = 0;
        for (int i = 0; i < n; i++) { float xh = (x[i] - m) * inv; mdz += dz[i]; mdzx += dz[i] * xh; }
        mdz /= n; mdzx /= n;
        for (int i = 0; i < n; i++) {
            float xh = (x[i] - m) * inv;
            dx[i] = g[i] * inv * (dz[i] - mdz - xh * mdzx);
        }
    }
    static void geluF(float[] x, float[] y) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            y[i] = 0.5f * v * (1f + (float) Math.tanh(0.7978845608f * (v + 0.044715f * v * v * v)));
        }
    }
    /** GELU' from the cached post-activation y (=0.5v(1+tanh u)). */
    static float geluGrad(float v, float y) {
        // recover tanh(u) from y: y = 0.5·v·(1+t) → t = 2y/v − 1 (v≈0 → t=0)
        float t = (Math.abs(v) < 1e-6f) ? 0f : 2f * y / v - 1f;
        float u = 0.7978845608f * (v + 0.044715f * v * v * v);
        float du = 0.7978845608f * (1f + 3f * 0.044715f * v * v);
        float sech2 = 1f - t * t;
        return 0.5f * (1f + t) + 0.5f * v * sech2 * du;
    }
    static float dot(float[] w, int off, float[] x) {
        float s = 0; for (int j = 0; j < HIDDEN; j++) s += w[off + j] * x[j]; return s;
    }
    static void axpy(float[] y, float[] w, int off, float a) {
        for (int j = 0; j < HIDDEN; j++) y[j] += a * w[off + j];
    }
    static double sig(double x) { return 1.0 / (1.0 + Math.exp(-x)); }
    static double logsig(double x) { return -Math.log(1.0 / (1.0 + Math.exp(-x))); }
    static long lcg(long s) { return s * 6364136223846793005L + 1442695040888963407L; }

    static int readPairs(String path, int[] c2, int[] c1, int[] tg, float[] wt, int n) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8), 1 << 20);
        for (String line; n < c2.length && (line = r.readLine()) != null; ) {
            int p1 = line.indexOf('\t'), p2 = line.indexOf('\t', p1 + 1), p3 = line.indexOf('\t', p2 + 1);
            if (p1 < 0 || p2 < 0 || p3 < 0) continue;
            c2[n] = Integer.parseInt(line.substring(0, p1));
            c1[n] = Integer.parseInt(line.substring(p1 + 1, p2));
            tg[n] = Integer.parseInt(line.substring(p2 + 1, p3));
            wt[n] = Float.parseFloat(line.substring(p3 + 1));
            n++;
        }
        r.close();
        return n;
    }

    static String[] readVocab() throws Exception {
        List<String> v = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(CORPUS_DIR + "/vocab.txt"), StandardCharsets.UTF_8), 1 << 16);
        for (String line; (line = r.readLine()) != null; ) {
            int t = line.indexOf('\t');
            if (t > 0) v.add(line.substring(t + 1));
        }
        r.close();
        return v.toArray(new String[0]);
    }

    static byte[] q8(float[] w) {
        float m = maxAbs(w);
        float scale = m / 127f;
        byte[] q = new byte[w.length];
        for (int i = 0; i < w.length; i++) {
            int v = Math.round(w[i] / scale);
            if (v > 127) v = 127; else if (v < -127) v = -127;
            q[i] = (byte) v;
        }
        return q;
    }
    static float maxAbs(float[] w) {
        float m = 0;
        for (float v : w) { float a = Math.abs(v); if (a > m) m = a; }
        return m;
    }
    static String gridJson(double[] A, double[] B, double[][] res) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < A.length; i++) for (int j = 0; j < B.length; j++) {
            if (i > 0 || j > 0) sb.append(',');
            sb.append("{\"A\":").append(A[i]).append(",\"B\":").append(B[j])
              .append(",\"devTop3\":").append(String.format(Locale.US, "%.4f", res[i][j])).append('}');
        }
        return sb.append(']').toString();
    }
}
