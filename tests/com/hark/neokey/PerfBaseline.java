package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * W55 perf baseline (ROADMAP-V2 W55) — the honest floor, measured not claimed.
 *
 * JVM-measured on the REAL shipped assets (the PerfGate discipline — the JVM
 * tests exactly what ships): the real 134k dictionary (assets/ar.txt through
 * the FakeContext stubs) and the real NQF model (assets/nnet_ar.nqf through
 * Predictor.load). The four W55 measurement categories, on the measurable side:
 *   • NN inference p50/p95  (nnet.top3)          — the W57 "no suggestion-speed
 *     regression past the W55 floor" gate measures THIS
 *   • suggestion pipeline p50/p95 (dict.suggest / dict.predict)
 *   • service-load proxy (dict.load.ms — the cold 134k parse)
 *   • memory (heap.mb — the JVM proxy; the on-device PSS floor stays Perf.LIMITS)
 *
 * The DEVICE floors (زمن استجابة الضغط، الذاكرة، إنزال الخدمة) are the
 * Perf.METRICS LIMITS the app enforces live on itself — recorded here and
 * guarded by the ANTI-REWRITE GUARD: the guard parses the shipped
 * src/Perf.java LIMITS/METRICS literals and refuses any silent rewrite.
 * "أرضية لا تُعاد كتابتها" is machine-checked: changing a floor = a
 * documented owner decision (the W54 ceiling pattern), never a quiet edit.
 *
 * evidence/perf-baseline.tsv — the ONE record (the ledger style, tab-separated):
 *   # jvm rows:    kind=key, value=measured@pin, budget=enforced line
 *   # device rows: kind=device, value=LIMITS good, budget=LIMITS ok
 *
 * The record is SELF-CONSISTENT by law: every jvm budget must equal
 * derive(value) exactly (the pin formula below, string-exact at %.3f) — a
 * hand-loosened budget without its matching measured value is a refusal.
 * Budget formula (documented at pin time, not re-derived per machine):
 *   p50 rows: value*1.5 + 1.0ms     p95 rows: value*1.5 + 2.0ms
 *   dict.load.ms: value*2 + 500ms   heap.mb: value*2 + 10
 *
 * Modes: --pin   measure + write the record (the W55 pinning run, once)
 *        --check measure + enforce (the gate; fail-closed: missing/malformed/
 *                inconsistent record or a floor rewrite is a loud refusal)
 */
public class PerfBaseline {

    static final String[] JVM_KEYS = {
            "nnet.top3.p50", "nnet.top3.p95",
            "dict.suggest.p50", "dict.suggest.p95",
            "dict.predict.p50", "dict.predict.p95",
            "dict.load.ms", "heap.mb"};
    /** The device metric keys, in Perf.METRICS display order. */
    static final String[] DEVICE_KEYS = {"create", "show", "key", "frame", "sugg", "ai", "mem"};
    /** The device floors as they shipped at W55 — the guard compares source vs record vs this. */
    static final double[][] EXPECTED_LIMITS = {
            {400, 900}, {120, 300}, {8, 20}, {24, 50}, {12, 35}, {2500, 7000}, {90, 160}};

    static final String RECORD = "evidence/perf-baseline.tsv";

    static int checks = 0;
    static boolean failed = false;

    static void check(String tag, boolean ok) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + tag);
        if (!ok) { failed = true; System.out.println("PERFBASELINE_FAILED " + tag); }
    }

    /** The pin formula — the record's budgets must equal this exactly (%.3f string-exact). */
    static String derive(String key, String valueStr) {
        double v = Double.parseDouble(valueStr);
        double b;
        if (key.endsWith(".p50")) b = v * 1.5 + 1.0;
        else if (key.endsWith(".p95")) b = v * 1.5 + 2.0;
        else if (key.equals("dict.load.ms")) b = v * 2 + 500;
        else if (key.equals("heap.mb")) b = v * 2 + 10;
        else throw new IllegalArgumentException("no derive rule for " + key);
        return String.format(Locale.US, "%.3f", b);
    }

    // ------------------------------------------------------------- measurement

    static final class Measured {
        final Map<String, String> jvm = new HashMap<>();   // key -> "%.3f"
        boolean dictLoaded, nnetLoaded;
        int nnetVocab, nnetCtx;
    }

    static Measured measure() throws Exception {
        Measured m = new Measured();

        // ---- the real 134k dictionary (cold load = the service-load proxy) ----
        File dir = new File("build/perfbase");
        File[] stale = dir.listFiles();
        if (stale != null) for (File f : stale) f.delete();
        dir.mkdirs();
        long t0 = System.currentTimeMillis();
        Dictionary dict = new Dictionary(new FakeContext(dir), "ar");
        long end = System.currentTimeMillis() + 20000;
        while (!dict.isLoaded() && System.currentTimeMillis() < end) Thread.sleep(5);
        m.dictLoaded = dict.isLoaded();
        m.jvm.put("dict.load.ms", String.format(Locale.US, "%.3f", (double) (System.currentTimeMillis() - t0)));

        // ---- suggestion pipeline p50/p95 (the PerfGate probes, mirrored) ------
        for (int i = 0; i < 25; i++) dict.suggest("سلام", 10);
        double[] sg = timed(() -> dict.suggest("سلام", 10), 200);
        m.jvm.put("dict.suggest.p50", fmt(sg[0]));
        m.jvm.put("dict.suggest.p95", fmt(sg[1]));
        for (int i = 0; i < 50; i++) dict.predict("في", 5);
        double[] pr = timed(() -> dict.predict("في", 5), 500);
        m.jvm.put("dict.predict.p50", fmt(pr[0]));
        m.jvm.put("dict.predict.p95", fmt(pr[1]));

        // ---- NN inference p50/p95 on the REAL model ---------------------------
        Predictor p;
        try {
            p = Predictor.load(Files.readAllBytes(Paths.get("assets/nnet_ar.nqf")));
            m.nnetLoaded = true;
            m.nnetVocab = p.vocab;
            m.nnetCtx = p.ctx;
        } catch (Exception e) {
            p = null;
        }
        List<String[]> pairs = new ArrayList<>();
        try {
            for (String s : Files.readAllLines(Paths.get("assets/testset_ar.txt"), StandardCharsets.UTF_8)) {
                String[] w = s.trim().split(" ");
                if (w.length >= 3) pairs.add(new String[]{w[0], w[1]});
                if (pairs.size() >= 30) break;
            }
        } catch (Exception ignored) { }
        if (p != null && !pairs.isEmpty()) {
            for (int i = 0; i < 25; i++) p.topIds(pairs.get(i % pairs.size())[0], pairs.get(i % pairs.size())[1], 3);
            long[] ns = new long[300];
            for (int i = 0; i < ns.length; i++) {
                String[] pctx = pairs.get(i % pairs.size());
                long a = System.nanoTime();
                p.topIds(pctx[0], pctx[1], 3);
                ns[i] = System.nanoTime() - a;
            }
            m.jvm.put("nnet.top3.p50", fmt(pctOf(ns, 0.5)));
            m.jvm.put("nnet.top3.p95", fmt(pctOf(ns, 0.95)));
        }

        // ---- memory (the JVM proxy; the PSS floor is Perf.LIMITS on-device) ---
        Runtime rt = Runtime.getRuntime();
        double used = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        m.jvm.put("heap.mb", String.format(Locale.US, "%.3f", used));
        return m;
    }

    interface Op { void run(); }

    /** Runs op n times (after the caller's own warmup), returns {p50ms, p95ms}. */
    static double[] timed(Op op, int n) {
        long[] ns = new long[n];
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            op.run();
            ns[i] = System.nanoTime() - a;
        }
        Arrays.sort(ns);
        return new double[]{
                ns[n / 2] / 1e6,
                ns[Math.min(n - 1, (int) Math.floor(n * 0.95))] / 1e6};
    }

    static double pctOf(long[] ns, double q) {
        Arrays.sort(ns);
        return ns[Math.min(ns.length - 1, (int) Math.floor(ns.length * q))] / 1e6;
    }

    static String fmt(double ms) { return String.format(Locale.US, "%.3f", ms); }

    // ------------------------------------------------------------- the record

    /** Strict read: exactly the 15 expected rows, no dup, no missing, no extra. */
    static Map<String, double[]> readRecord() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(RECORD), StandardCharsets.UTF_8);
        Map<String, double[]> out = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] p = line.split("\t", -1);
            if (p.length != 4) throw new IllegalStateException("malformed row: " + line);
            String kind = p[0], key = p[1];
            double v = Double.parseDouble(p[2]), b = Double.parseDouble(p[3]);
            if (seen.contains(kind + "." + key)) throw new IllegalStateException("duplicate: " + key);
            seen.add(kind + "." + key);
            out.put(kind + "." + key, new double[]{v, b});
        }
        Set<String> expected = new LinkedHashSet<>();
        for (String k : JVM_KEYS) expected.add("jvm." + k);
        for (String k : DEVICE_KEYS) expected.add("device." + k);
        if (!seen.equals(expected)) throw new IllegalStateException("keyset mismatch (" + seen.size() + " rows vs 15 expected) — refusing");
        return out;
    }

    /** The anti-rewrite guard: parse the shipped Perf.java LIMITS/METRICS literals. */
    static String[][] parsePerfSource() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/com/hark/neokey/Perf.java")), StandardCharsets.UTF_8);
        int i = src.indexOf("static final String[][] METRICS = {");
        int j = src.indexOf("};", i);
        String metrics = src.substring(i, j);
        String[] keys = new String[DEVICE_KEYS.length];
        int k = 0;
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\{\"(\\w+)\",").matcher(metrics);
        while (mm.find()) if (k < keys.length) keys[k++] = mm.group(1);
        i = src.indexOf("static final float[][] LIMITS = {");
        j = src.indexOf("};", i);
        String limits = src.substring(i, j);
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(limits);
        List<Double> nums = new ArrayList<>();
        while (nm.find()) nums.add(Double.parseDouble(nm.group(1)));
        String[][] out = new String[DEVICE_KEYS.length][];
        if (k != DEVICE_KEYS.length || nums.size() != DEVICE_KEYS.length * 2) return out; // shape refusal
        for (int r = 0; r < DEVICE_KEYS.length; r++)
            out[r] = new String[]{keys[r], trimNum(nums.get(r * 2)), trimNum(nums.get(r * 2 + 1))};
        return out;
    }

    static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------ modes

    public static void main(String[] args) throws Exception {
        boolean pin = args.length > 0 && args[0].equals("--pin");
        Measured m = measure();
        check("dict.loaded.real_134k", m.dictLoaded);
        check("nnet.loaded.real_model", m.nnetLoaded && m.nnetVocab > 1000 && m.nnetCtx >= 1);

        if (pin) {
            if (failed || m.jvm.size() != JVM_KEYS.length) {
                System.out.println("PERFBASELINE_FAILED pin_refused (measurement incomplete — nothing written)");
                System.exit(1);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# evidence/perf-baseline.tsv — the W55 perf floor (ROADMAP-V2 W55)\n");
            sb.append("# jvm rows: kind=key value=measured@pin budget=enforced line (the gate re-measures and enforces measured<=budget)\n");
            sb.append("# device rows: value=Perf.LIMITS good budget=LIMITS ok — the anti-rewrite guard compares src/Perf.java vs this file\n");
            sb.append("# the record is SELF-CONSISTENT: every jvm budget == derive(value) at %.3f (p50 v*1.5+1 / p95 v*1.5+2 / load v*2+500 / heap v*2+10)\n");
            sb.append("# changing any line = a documented owner decision in NOTES.md (the W54 ceiling pattern) — never a quiet edit\n");
            sb.append("# pinned: ").append(java.time.Instant.now()).append(" java ").append(System.getProperty("java.version")).append("\n");
            for (String k : JVM_KEYS) {
                String v = m.jvm.get(k);
                sb.append("jvm\t").append(k).append('\t').append(v).append('\t').append(derive(k, v)).append('\n');
            }
            for (int i = 0; i < DEVICE_KEYS.length; i++)
                sb.append("device\t").append(DEVICE_KEYS[i]).append('\t').append(trimNum(EXPECTED_LIMITS[i][0]))
                        .append('\t').append(trimNum(EXPECTED_LIMITS[i][1])).append('\n');
            Files.write(Paths.get(RECORD), sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("PERFBASELINE_PINNED metrics=" + JVM_KEYS.length + " device=" + DEVICE_KEYS.length
                    + " load=" + m.jvm.get("dict.load.ms") + " nnet=" + m.jvm.get("nnet.top3.p50") + "/" + m.jvm.get("nnet.top3.p95"));
            System.exit(0);
        }

        // ---- --check: the gate path ------------------------------------------
        Map<String, double[]> rec;
        boolean readable = true;
        try { rec = readRecord(); } catch (Exception e) {
            readable = false;
            rec = new HashMap<>();
            System.out.println("record.refused: " + e.getMessage());
        }
        check("record.readable.strict", readable);

        String[][] src = null;
        try { src = parsePerfSource(); } catch (Exception e) { src = null; }
        boolean srcOk = src != null && src.length == DEVICE_KEYS.length;
        if (srcOk) for (int i = 0; i < DEVICE_KEYS.length; i++) if (src[i] == null) srcOk = false;
        check("device.source.guard", srcOk
                && src != null && Arrays.equals(src[0], new String[]{"create", "400", "900"}));

        for (int i = 0; i < DEVICE_KEYS.length && srcOk; i++)
            check("device.floor." + DEVICE_KEYS[i],
                    src[i][0].equals(DEVICE_KEYS[i])
                            && Double.parseDouble(src[i][1]) == EXPECTED_LIMITS[i][0]
                            && Double.parseDouble(src[i][2]) == EXPECTED_LIMITS[i][1]);

        for (String k : JVM_KEYS) {
            double[] vb = rec.get("jvm." + k);
            if (vb == null) { check("jvm." + k, false); continue; }
            double measured = Double.parseDouble(m.jvm.get(k));
            boolean selfConsistent = derive(k, String.format(Locale.US, "%.3f", vb[0]))
                    .equals(String.format(Locale.US, "%.3f", vb[1]));
            check("jvm." + k + ".self_consistent", selfConsistent);
            check("jvm." + k + ".within_budget", measured <= vb[1]);
        }
        System.out.println("measured: load=" + m.jvm.get("dict.load.ms") + "ms nnet=" + m.jvm.get("nnet.top3.p50")
                + "/" + m.jvm.get("nnet.top3.p95") + "ms sugg=" + m.jvm.get("dict.suggest.p50")
                + "ms pred=" + m.jvm.get("dict.predict.p50") + "ms heap=" + m.jvm.get("heap.mb") + "MB");
        if (failed) {
            System.out.println("PERFBASELINE_FAILED checks=" + checks);
            System.exit(1);
        }
        System.out.println("PERFBASELINE_OK checks=" + checks + " metrics=" + JVM_KEYS.length
                + " device=" + DEVICE_KEYS.length);
    }
}
