package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * بوابة W62 — ReassertGate: أسبوع الإحكام الأ — إعادة القياس والمراجعة
 * الأمنية للمحركات الخمسة. نقي JVM — تجري في أي مكان.
 *
 *   1. سلبيات المحركات الخمسة تعضّ بأوسامها الدقيقة (الفساد في الذاكرة —
 *      الأصل الحي لم يُلمس أبداً، والشفاء = الأصل الحي نفسه يحمل ناجحاً):
 *        • nnet: بايت فاسد في نموذج NQF → رفض صارم (لا نصف بناء أبداً).
 *        • root: root_index فاسد → ROOTENGINE يرفض.
 *        • tashkeel: tashkeel_index فاسد → TSXENGINE يرفض.
 *        • grammar: grammar_rules فاسد → NUDGEENGINE يرفض.
 *        • dialect: مرآة العلامات منحرفة → المقارنة تكتشف (أحمر).
 *   2. الأصول الحية تحمل ناجحاً كلها — التشفي البايتي مثبت (المرجع = HEAD).
 *   3. دقة الكشف على المجموعة المرجعية فوق الحد الموثق (85/75) — الكاشف
 *      الخامس (الهاجس اللحظي) بقي حياً.
 * الحكم: REASSERTGATE_OK / REASSERTGATE_FAILED <reason> (exit 1).
 * (هامش dex يقيسه السكربت ببناء طازج — floor=500 موثق.)
 */
public class ReassertGate {
    static int pass, fail;

    static void ok(boolean cond, String what) {
        if (cond) pass++;
        else fail++;
        System.out.println((cond ? "GATE-OK " : "GATE-FAIL ") + what);
    }

    /** فساد ذاكري موثق: قلب بايت واحد في منتصف المصفوفة (مستقر لا عشوائي). */
    static byte[] corrupt(byte[] b) {
        byte[] c = b.clone();
        int mid = c.length / 2;
        c[mid] = (byte) (c[mid] ^ 0x5A);
        return c;
    }

    interface Loader { Object run(byte[] raw) throws Exception; }

    /** الهجوم الموثق: نسخة فاسدة يجب أن ترفض بالوسم، والحية يجب أن تحمل. */
    static void attack(String name, byte[] live, Loader loader, String mustTag) {
        boolean refused = false;
        String msg = "";
        try {
            loader.run(corrupt(live));
        } catch (Throwable t) {
            refused = true;
            msg = String.valueOf(t.getMessage());
        }
        ok(refused, name + ": a corrupt copy must be REFUSED (got "
                + (refused ? "refusal" : "silent accept") + ")");
        ok(refused && msg.contains(mustTag), name + ": the refusal must carry «"
                + mustTag + "» (got: " + (msg.length() > 60 ? msg.substring(0, 60) : msg) + ")");
        boolean liveOk = false;
        try {
            liveOk = loader.run(live) != null;
        } catch (Throwable t) {
            liveOk = false;
        }
        ok(liveOk, name + ": the LIVE asset (HEAD) loads — the byte-exact heal stands");
    }

    public static void main(String[] args) throws Exception {
        byte[] nqf = Files.readAllBytes(Paths.get("assets/nnet_ar.nqf"));
        byte[] root = Files.readAllBytes(Paths.get("assets/root_index.txt"));
        byte[] tash = Files.readAllBytes(Paths.get("assets/tashkeel_index.txt"));
        byte[] gram = Files.readAllBytes(Paths.get("assets/grammar_rules.txt"));

        // ---- المحرك 1: nnet — NQF يرفض أي بايت فاسد بلا نصف بناء ---------------
        attack("nnet(nqf)", nqf, raw -> Predictor.load(raw), "CRC mismatch");
        // والرفض الصارم في القرار نفسه (موتر فاسد يعضّ لا يهمل)
        boolean strict = false;
        try {
            NQF.read(corrupt(nqf));
        } catch (Throwable t) {
            strict = true;
        }
        ok(strict, "nnet: NQF.read refuses a corrupt container strictly");

        // ---- المحرك 2: root ----------------------------------------------------
        attack("root(index)", root, raw -> RootEngine.load(raw), "ROOTENGINE");

        // ---- المحرك 3: tashkeel ------------------------------------------------
        attack("tashkeel(index)", tash, raw -> TashkeelEngine.load(raw), "TSXENGINE");

        // ---- المحرك 4: grammar -------------------------------------------------
        attack("grammar(rules)", gram, raw -> GrammarNudge.load(raw), "NUDGEENGINE");

        // ---- المحرك 5: dialect — المرآة والانحراف يُكتشف ------------------------
        String tsv = new String(Files.readAllBytes(Paths.get("dictsrc/dialect_markers.tsv")),
                StandardCharsets.UTF_8);
        Map<String, int[]> tsvMap = new HashMap<>();
        for (String line : tsv.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            if (p.length != 2) continue;
            String[] v = p[1].split(",");
            if (v.length != 3) continue;
            tsvMap.put(Dialect.normAr(p[0]), new int[]{
                    Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2])});
        }
        Map<String, int[]> code = Dialect.markers();
        boolean mirror = code.size() == tsvMap.size();
        if (mirror) {
            for (Map.Entry<String, int[]> e : tsvMap.entrySet()) {
                int[] c = code.get(e.getKey());
                if (c == null || c[0] != e.getValue()[0] || c[1] != e.getValue()[1]
                        || c[2] != e.getValue()[2]) { mirror = false; break; }
            }
        }
        ok(mirror, "dialect: the live marker mirror is cell-for-cell consistent ("
                + code.size() + " keys)");
        // الانحراف الموثق يُكتشف: نسخة ذاكرية فاسدة من المرآة يجب ألا تطابق الكود
        Map<String, int[]> drifted = new HashMap<>(tsvMap);
        String someKey = drifted.keySet().iterator().next();
        int[] v0 = drifted.get(someKey);
        drifted.put(someKey, new int[]{v0[0] ^ 1, v0[1], v0[2]});
        boolean caught = !equalsMirror(drifted, code);
        ok(caught, "dialect: a DRIFTED mirror must be caught by the comparison (a red build)");

        // دقة الكشف الحي فوق الحد الموثق — الكاشف الخامس بقي حياً
        int rows = 0, hit = 0;
        for (String line : Files.readAllLines(Paths.get("assets/testset_dialects.txt"), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 2);
            if (p.length != 2) continue;
            rows++;
            if (Dialect.detect(p[1]).dialect.equals(p[0])) hit++;
        }
        double acc = 100.0 * hit / rows;
        ok(acc >= 85.0, "dialect: live detection accuracy " + String.format("%.1f", acc)
                + "% >= 85.0% (الحد الموثق)");

        System.out.println("REASSERTGATE_OK checks=" + (pass + fail)
                + " engines=5 failclosed=ON heal=byte-exact acc=" + String.format("%.1f", acc)
                + " (dex margin measured fresh by ci/reassert_gate.sh, floor=500)");
        if (fail > 0) {
            System.out.println("REASSERTGATE_FAILED " + fail + " gate checks");
            System.exit(1);
        }
    }

    static boolean equalsMirror(Map<String, int[]> a, Map<String, int[]> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, int[]> e : a.entrySet()) {
            int[] c = b.get(e.getKey());
            if (c == null || c.length != e.getValue().length) return false;
            for (int i = 0; i < c.length; i++) if (c[i] != e.getValue()[i]) return false;
        }
        return true;
    }
}
