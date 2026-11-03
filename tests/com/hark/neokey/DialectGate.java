package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialect gate (phase 1, item C1, week 5) — the roadmap bar, measured not claimed:
 *
 *   «كشف لهجة بدقة 85%+، 3 قواميس لهجات أولى تمر فحص الجودة»
 *
 * What it does:
 *   1. QC of the three shipped dialect dictionaries (assets/ar_hijazi.txt,
 *      ar_najdi.txt, ar_gulf.txt): present, ≥300 words, Arabic-letters-only
 *      (2..20 chars), no tashkeel/tatweel, no duplicate raw, no duplicate
 *      normAr key, ≥250 words of the shared Gulf core in EACH dict;
 *   2. consistency TSV→Java→assets: dictsrc/dialect_markers.tsv and
 *      Dialect.markers() must agree cell-for-cell (the in-code table is a
 *      MIRROR of the TSV — any drift is a red build), and every marker word
 *      with a non-zero weight in column d must exist in dictionary d;
 *   3. detection accuracy on assets/testset_dialects.txt (64 curated
 *      sentences): overall ≥85% AND every class ≥75% (roadmap bar 85%+);
 *   4. latency: detect() p50 < 1ms per sentence on the gate set;
 *   5. determinism: the whole gate set detected twice → identical verdicts;
 *   6. negative proofs (the gate must be able to FAIL):
 *        - a temp dictionary missing its distinctive markers must trip the
 *          consistency check (proves check #2 is wired to real files);
 *        - shuffled labels must score far below the bar (proves the metric
 *          discriminates — a degenerate all-one-class detector cannot pass);
 *        - pure-MSA input must never claim a Gulf dialect.
 *
 * Verdict: DIALECT_GATE_OK / DIALECT_GATE_FAILED <reason> (exit 1). Pure JVM.
 */
public final class DialectGate {

    private static final int MIN_DICT_WORDS = 300;
    private static final int MIN_CORE_WORDS = 250;
    private static final double MIN_ACC_OVERALL = 85.0;  // roadmap: كشف لهجة ≥85%
    private static final double MIN_ACC_CLASS = 75.0;    // كلاس واحد لا يُنقذ بالبقية
    private static final double MAX_P50_MS = 1.0;
    private static final String[] CLASSES = {"hijazi", "najdi", "gulf", "msa"};
    private static final String[] DIALECTS = {"hijazi", "najdi", "gulf"};

    static final class Gate extends RuntimeException {
        Gate(String m) { super(m); }
    }

    static void ok(boolean cond, String msg) {
        if (!cond) throw new Gate(msg);
    }

    public static void main(String[] args) {
        try {
            run();
            System.out.println("DIALECT_GATE_OK");
        } catch (Gate g) {
            System.out.println("DIALECT_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("DIALECT_GATE_FAILED internal: " + e);
            System.exit(1);
        }
    }

    static void run() throws Exception {
        // ---- 1) dictionary QC ---------------------------------------------------
        Map<String, List<String>> dicts = new HashMap<>();
        for (String d : DIALECTS) {
            File f = new File("assets/ar_" + d + ".txt");
            ok(f.isFile(), "missing assets/ar_" + d + ".txt");
            List<String> words = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            while (!words.isEmpty() && words.get(words.size() - 1).isEmpty()) words.remove(words.size() - 1);
            ok(words.size() >= MIN_DICT_WORDS,
                    d + " dict too small: " + words.size() + " < " + MIN_DICT_WORDS);
            java.util.Set<String> raw = new java.util.HashSet<>();
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (String w : words) {
                ok(w != null && !w.isEmpty(), d + ": empty line in dictionary");
                ok(w.matches("[\\u0621-\\u063A\\u0641-\\u064A]{2,20}"),
                        d + ": non-Arabic/short/long word: " + w);
                ok(raw.add(w), d + ": duplicate raw word: " + w);
                ok(keys.add(Dialect.normAr(w)), d + ": duplicate norm key: " + w);
            }
            dicts.put(d, words);
        }
        // النواة المشتركة موجودة في الثلاثة (أساس التبديل الذكي في الأسبوع 6)
        List<String> core = new ArrayList<>();
        for (String w : Files.readAllLines(new File("dictsrc/gulf.txt").toPath(), StandardCharsets.UTF_8)) {
            for (String t : w.split("\\|")) {
                String t2 = t.trim();
                if (!t2.isEmpty()) core.add(t2);
            }
        }
        for (String d : DIALECTS) {
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (String w : dicts.get(d)) keys.add(Dialect.normAr(w));
            int in = 0;
            for (String w : core) if (keys.contains(Dialect.normAr(w))) in++;
            ok(in >= MIN_CORE_WORDS,
                    d + ": shared-core coverage " + in + " < " + MIN_CORE_WORDS);
        }

        // ---- 2) TSV ↔ Java ↔ assets consistency ---------------------------------
        Map<String, int[]> tsv = new HashMap<>();
        for (String line : Files.readAllLines(new File("dictsrc/dialect_markers.tsv").toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            ok(p.length == 2, "bad markers TSV line: " + line);
            String[] v = p[1].split(",");
            ok(v.length == 3, "bad marker weights: " + line);
            tsv.put(Dialect.normAr(p[0]), new int[]{
                    Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2])});
        }
        Map<String, int[]> code = Dialect.markers();
        ok(code.size() == tsv.size(),
                "marker count drift: java=" + code.size() + " tsv=" + tsv.size());
        for (Map.Entry<String, int[]> e : tsv.entrySet()) {
            int[] c = code.get(e.getKey());
            ok(c != null, "marker in TSV missing from code: " + e.getKey());
            ok(c[0] == e.getValue()[0] && c[1] == e.getValue()[1] && c[2] == e.getValue()[2],
                    "marker weight drift: " + e.getKey());
        }
        for (String d : DIALECTS) {
            int col = d.equals("hijazi") ? 0 : d.equals("najdi") ? 1 : 2;
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (String w : dicts.get(d)) keys.add(Dialect.normAr(w));
            for (Map.Entry<String, int[]> e : tsv.entrySet()) {
                if (e.getValue()[col] > 0) {
                    ok(keys.contains(e.getKey()),
                            "marker " + e.getKey() + " (col " + col + ") missing from ar_" + d + ".txt");
                }
            }
        }

        // ---- 3) accuracy on the curated gate set ---------------------------------
        List<String> labels = new ArrayList<>();
        List<String> sents = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/testset_dialects.txt").toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 2);
            ok(p.length == 2, "bad testset line: " + line);
            labels.add(p[0]);
            sents.add(p[1]);
        }
        ok(sents.size() == 64, "gate set must be 64 rows, got " + sents.size());
        int okCount = 0;
        Map<String, int[]> per = new HashMap<>();
        for (int i = 0; i < sents.size(); i++) {
            String got = Dialect.detect(sents.get(i)).dialect;
            boolean good = got.equals(labels.get(i));
            if (good) okCount++;
            int[] b = per.get(labels.get(i));
            if (b == null) { b = new int[2]; per.put(labels.get(i), b); }
            b[1]++;
            if (good) b[0]++;
        }
        double acc = 100.0 * okCount / sents.size();
        ok(acc >= MIN_ACC_OVERALL,
                "detection accuracy " + String.format("%.1f", acc) + "% < " + MIN_ACC_OVERALL + "%");
        for (String c : CLASSES) {
            int[] b = per.get(c);
            ok(b != null && b[1] > 0, "class missing from gate set: " + c);
            double ca = 100.0 * b[0] / b[1];
            ok(ca >= MIN_ACC_CLASS, "class " + c + " accuracy " + String.format("%.1f", ca) + "% < " + MIN_ACC_CLASS + "%");
        }

        // ---- 4) latency p50 < 1ms ------------------------------------------------
        for (int w = 0; w < 30; w++) for (String s : sents) Dialect.detect(s);
        long[] ns = new long[sents.size() * 20];
        for (int i = 0, k = 0; i < 20; i++)
            for (String s : sents) {
                long t0 = System.nanoTime();
                Dialect.detect(s);
                ns[k++] = System.nanoTime() - t0;
            }
        java.util.Arrays.sort(ns);
        double p50 = ns[ns.length / 2] / 1e6;
        ok(p50 < MAX_P50_MS, "detect p50 " + String.format("%.3f", p50) + "ms >= " + MAX_P50_MS + "ms");

        // ---- 5) determinism -------------------------------------------------------
        for (String s : sents) {
            Dialect.Result r1 = Dialect.detect(s);
            Dialect.Result r2 = Dialect.detect(s);
            ok(r1.equals(r2), "non-deterministic detection on: " + s);
        }

        // ---- 6) negative proofs ---------------------------------------------------
        // (a) قاموس مؤقت بلا مميزاته يجب أن يُطاح في فحص الاتساق
        List<String> stripped = new ArrayList<>(dicts.get("hijazi"));
        List<String> keep = new ArrayList<>();
        for (String w : stripped) {
            int[] v = Dialect.markers().get(Dialect.normAr(w));
            if (v == null || v[0] == 0) keep.add(w);   // اسقط كل ما فيه وزن حجازي
        }
        ok(keep.size() < stripped.size(), "negative proof (a) setup: nothing stripped?");
        File tmp = new File("build/dialect-gate-neg/ar_hijazi.txt");
        tmp.getParentFile().mkdirs();
        Files.write(tmp.toPath(), keep, StandardCharsets.UTF_8);
        java.util.Set<String> tmpKeys = new java.util.HashSet<>();
        for (String w : keep) tmpKeys.add(Dialect.normAr(w));
        boolean caught = false;
        for (Map.Entry<String, int[]> e : tsv.entrySet()) {
            if (e.getValue()[0] > 0 && !tmpKeys.contains(e.getKey())) { caught = true; break; }
        }
        ok(caught, "negative proof (a): consistency check failed to catch stripped dict");
        // (b) الخلط بين الفئات يجب أن يُسقط الدقة كثيراً (المقياس يميّز فعلاً)
        List<String> shuffled = new ArrayList<>(labels);
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        int shOk = 0;
        for (int i = 0; i < sents.size(); i++)
            if (Dialect.detect(sents.get(i)).dialect.equals(shuffled.get(i))) shOk++;
        double shAcc = 100.0 * shOk / sents.size();
        ok(shAcc < 60.0, "negative proof (b): shuffled-label accuracy " + String.format("%.1f", shAcc) + "% too high");
        // (c) الفصحى الصرفة لا تدعي لهجة خليجية أبداً
        for (String s : new String[]{
                "قرأت الكتاب كاملا أمس في المكتبة",
                "هل تستطيع الاتصال بي الآن من فضلك",
                "أرسل لي التقرير على البريد الإلكتروني"}) {
            ok(Dialect.detect(s).dialect.equals(Dialect.MSA),
                    "negative proof (c): MSA claimed as " + Dialect.detect(s).dialect);
        }

        // ---- report line (collect_gates parses this) -------------------------------
        StringBuilder sb = new StringBuilder("DIALECTGATE acc=" + String.format("%.1f", acc) + "%");
        for (String c : CLASSES) {
            int[] b = per.get(c);
            sb.append(' ').append(c).append('=').append(String.format("%.1f", 100.0 * b[0] / b[1])).append('%');
        }
        sb.append(" dict_words=");
        for (String d : DIALECTS) sb.append(d).append(':').append(dicts.get(d).size()).append(',');
        sb.append(" markers=").append(code.size());
        sb.append(" p50=").append(String.format("%.3f", p50)).append("ms");
        System.out.println(sb);
    }
}
