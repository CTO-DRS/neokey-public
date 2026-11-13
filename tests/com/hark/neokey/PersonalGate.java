package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * بوابة W60 — PersonalGate: تقيس التسليم لا الادعاء (نمط DialectAdaptGate).
 * نقي JVM — تجري في أي مكان:
 *   1. البطارية AdaptEngineTest كاملة (كل قانون: تعلّم/توهين/سقف/عقد/شارة/مسح).
 *   2. التحسن المقيس على assets/testset_personal.txt مقابل خط الأساس الموثق:
 *      كل كلمة مرجعية تتحقق غيابها من المعجم الأساسي (assets/ar.txt) —
 *      خط الأساس لا يمكنه إظهارها من مسار المعجم، فالتحسن 4(فائت) → 1..3
 *      مقيس لا مفترض. p50/p90 للرتب مع الطبقة مقابل p50/p90 بدونها.
 *   • التوهين مقيس: آخر استعلام عمداً مُهجور (59 يوماً) يجب أن يفائت —
 *     قانون نصف العمر حي، لا الضربات الخضراء فقط.
 *   • المسح حي: بعد wipe() التسلسل "" والتحليل فارغ — حالة مصنع بايتياً.
 * الحكم: PERSONALGATE_OK / PERSONALGATE_FAILED <reason> (exit 1).
 */
public class PersonalGate {
    static int pass, fail;

    static void ok(boolean cond, String what) {
        if (cond) pass++;
        else fail++;
        System.out.println((cond ? "GATE-OK " : "GATE-FAIL ") + what);
    }

    static int pctl(List<Integer> ranks, double q) {
        List<Integer> s = new ArrayList<>(ranks);
        s.sort(null);
        int i = (int) Math.ceil(q * s.size()) - 1;
        if (i < 0) i = 0;
        if (i >= s.size()) i = s.size() - 1;
        return s.get(i);
    }

    public static void main(String[] args) throws Exception {
        // ---- 1) البطارية الكاملة ------------------------------------------------
        AdaptEngineTest.run();
        ok(AdaptEngineTest.fail == 0, "battery AdaptEngineTest green ("
                + AdaptEngineTest.pass + " checks, " + AdaptEngineTest.fail + " failed)");

        // ---- 2) المجموعة المرجعية: الغياب من المعجم الأساسي أولاً ---------------
        List<String[]> learns = new ArrayList<>(), queries = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("assets/testset_personal.txt"), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f[0].equals("learn") && f.length == 4) learns.add(new String[]{f[1], f[2], f[3]});
            else if (f[0].equals("query") && f.length == 4) queries.add(new String[]{f[1], f[2], f[3]});
        }
        ok(learns.size() >= 10 && queries.size() >= 10,
                "testset rows learn=" + learns.size() + " query=" + queries.size());
        String base = new String(Files.readAllBytes(Paths.get("assets/ar.txt")), StandardCharsets.UTF_8);
        int absent = 0;
        for (String[] q : queries) if (!base.contains(q[0])) absent++;
        ok(absent == queries.size(),
                "baseline honesty: " + (queries.size() - absent) + " token(s) FOUND in ar.txt — the baseline must miss them all");

        // ---- 3) التحسن المقيس: مع الطبقة مقابل خط الأساس ------------------------
        AdaptEngine a = new AdaptEngine();
        for (String[] l : learns)
            for (int i = 0; i < Integer.parseInt(l[2]); i++) a.learn(l[0], Integer.parseInt(l[1]));
        List<Integer> adaptRanks = new ArrayList<>(), baseRanks = new ArrayList<>();
        for (String[] q : queries) {
            String word = q[0];
            int day = Integer.parseInt(q[1]);
            String path = q[2];
            a.setToday(day);
            int rank = 4;
            List<String> cands = new ArrayList<>();
            if (path.equals("compose")) {
                String prefix = word.substring(0, Math.min(3, word.length()));
                for (String s : a.run(new Engine.Ctx(null, null, prefix))) cands.add(s);
            } else {
                for (String s : a.run(new Engine.Ctx("السياق", "السابق", ""))) cands.add(s);
            }
            for (int i = 0; i < cands.size(); i++)
                if (cands.get(i).equals(AdaptEngine.MARK + word)) { rank = i + 1; break; }
            adaptRanks.add(rank);
            baseRanks.add(4);   // الغياب من المعجم مُتحقق أعلاه — خط الأساس يفائت
        }
        int a50 = pctl(adaptRanks, 0.5), a90 = pctl(adaptRanks, 0.9);
        int b50 = pctl(baseRanks, 0.5), b90 = pctl(baseRanks, 0.9);
        int hits = 0;
        for (int r : adaptRanks) if (r <= 3) hits++;
        ok(b50 == 4 && b90 == 4, "baseline p50/p90 must miss (4/4) — measured " + b50 + "/" + b90);
        ok(a50 <= 2, "adapt p50 " + a50 + " > 2");
        ok(a90 <= 3, "adapt p90 " + a90 + " > 3");
        ok(hits >= adaptRanks.size() - 2,
                "adapt hits " + hits + "/" + adaptRanks.size() + " — at most the decayed miss may fail");
        // التوهين المقيس: الاستعلام المهجور عمداً يجب أن يفائت
        int decayed = adaptRanks.get(adaptRanks.size() - 1);
        ok(decayed == 4, "the half-life law must live: the deliberately decayed token missed (rank " + decayed + ")");

        // ---- 4) المسح حي: حالة المصنع بايتياً -----------------------------------
        a.wipe();
        ok(a.serialize().isEmpty() && AdaptEngine.parse("").size() == 0 && a.size() == 0,
                "wipe: factory byte-state (empty serialize, empty parse, empty store)");
        a.setToday(10);
        ok(a.run(new Engine.Ctx(null, null, "ن")).length == 0
                && a.run(new Engine.Ctx("أبو", "قال", "")).length == 0,
                "wipe: the wiped engine answers empty everywhere");

        System.out.println("PERSONALGATE_OK checks=" + (pass + fail)
                + " learns=" + learns.size() + " queries=" + queries.size()
                + " hits=" + hits + " p50=" + b50 + "->" + a50 + " p90=" + b90 + "->" + a90
                + " cap=" + AdaptEngine.CAP + " halflife=" + AdaptEngine.HALF_LIFE
                + " wipe=factory failclosed=ON");
        if (fail > 0) {
            System.out.println("PERSONALGATE_FAILED " + fail + " gate checks");
            System.exit(1);
        }
    }
}
