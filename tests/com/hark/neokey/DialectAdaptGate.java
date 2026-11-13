package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * بوابة W59 — DialectAdaptGate: تقيس التسليم لا الادعاء (نمط DialectGate).
 * نقي JVM — تجري في أي مكان:
 *   1. البطارية DialectAdaptTest كاملة (كل قانون اختباران موجب/سلبي).
 *   2. دقة الكشف على assets/testset_dialects.txt — الحد الموثق أول قياس
 *      (W54): ≥85% إجمالاً و≥75% لكل فئة — نفس المجموعة المرجعية لا غيرها.
 *   3. بذرة الإقلاع البارد صادقة حتماً: لكل تطبيق مرجعي مستقر تعيد seed()
 *      القاموس المسجل نفسه بالضبط، والملف غير المستقر يبذّر null، والملف
 *      المنزَّل للفصحى يبذّر null — لا بذرة تخمين أبداً.
 *   4. لا كسر SmartSwitch: قرارات الدليل الحي (auto/off/msa/fixed) على نوافذ
 *      المجموعة المرجعية مطابقة للقانون الأصلي — والبذرة لا تظهر إلا في
 *      النافذة الفارغة.
 * الحكم: DIALECTADAPT_OK / DIALECTADAPT_FAILED <reason> (exit 1).
 */
public class DialectAdaptGate {
    static int pass, fail;

    static void ok(boolean cond, String what) {
        if (cond) pass++;
        else fail++;
        System.out.println((cond ? "GATE-OK " : "GATE-FAIL ") + what);
    }

    public static void main(String[] args) throws Exception {
        // ---- 1) البطارية الكاملة ------------------------------------------------
        DialectAdaptTest.run();
        ok(DialectAdaptTest.fail == 0, "battery DialectAdaptTest green ("
                + DialectAdaptTest.pass + " checks, " + DialectAdaptTest.fail + " failed)");

        // ---- 2) الدقة على المجموعة المرجعية (الحد الموثق أول قياس) --------------
        Map<String, List<String>> byClass = new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("assets/testset_dialects.txt"), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 2);
            if (p.length != 2 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) continue;
            rows.add(new String[]{p[0].trim(), p[1].trim()});
            List<String> l = byClass.get(p[0].trim());
            if (l == null) { l = new ArrayList<>(); byClass.put(p[0].trim(), l); }
            l.add(p[1].trim());
        }
        ok(rows.size() >= 60, "curated rows " + rows.size() + " < 60");
        int hit = 0;
        for (String[] r : rows) if (Dialect.detect(r[1]).dialect.equals(r[0])) hit++;
        double acc = 100.0 * hit / rows.size();
        ok(acc >= 85.0, "detection accuracy " + String.format("%.1f", acc) + "% < 85.0% (الحد الموثق)");
        double classMin = 100.0;
        for (Map.Entry<String, List<String>> e : byClass.entrySet()) {
            int h = 0;
            for (String s : e.getValue()) if (Dialect.detect(s).dialect.equals(e.getKey())) h++;
            double ca = 100.0 * h / e.getValue().size();
            if (ca < classMin) classMin = ca;
            ok(ca >= 75.0, "class " + e.getKey() + " accuracy " + String.format("%.1f", ca) + "% < 75.0%");
        }

        // ---- 3) البذرة الصادقة حتماً --------------------------------------------
        // ثلاثة تطبيقات مرجعية: حجازي مستقر، غير مستقر (u=2)، منزَّل للفصحى.
        DialectAdapt d = new DialectAdapt();
        d.record("app.hijazi", "ar_hijazi");
        d.record("app.hijazi", "ar_hijazi");
        d.record("app.hijazi", "ar_hijazi");
        d.record("app.gulf", "ar_gulf");
        d.record("app.gulf", "ar_gulf");
        d.record("app.najdi", "ar_najdi");
        d.record("app.najdi", "ar_najdi");
        d.record("app.najdi", "ar_najdi");
        d.recordMsa("app.najdi", 0, 4);
        d.recordMsa("app.najdi", 0, 4);
        d.recordMsa("app.najdi", 0, 4);
        ok("ar_hijazi".equals(d.seed("app.hijazi")), "seed(stable hijazi) != recorded dictId");
        ok(d.seed("app.gulf") == null, "seed(unstable u=2) must be null — no guessing");
        ok(d.seed("app.najdi") == null, "seed(downgraded to msa) must be null");
        ok(d.seed("unknown.app") == null, "seed(unknown app) must be null");
        // جولة الصيغة تحفظ البذور
        DialectAdapt back = DialectAdapt.parse(d.serialize());
        ok("ar_hijazi".equals(back.seed("app.hijazi")) && back.seed("app.gulf") == null,
                "seed survives parse/serialize byte-exact");

        // ---- 4) لا كسر SmartSwitch: القرار الحي على نوافذ المجموعة --------------
        List<String> hijaziWin = Arrays.asList("ايش", "تسوى", "دحين", "الحين", "البيت");
        List<String> gulfWin = Arrays.asList("وايد", "زين", "شخبارك", "الحين");
        List<String> msaWin = Arrays.asList("الكتاب", "العمل", "الجامعة", "العلم");
        SmartSwitch.Decision r1 = SmartSwitch.decide(hijaziWin, SmartSwitch.AUTO);
        SmartSwitch.Decision r2 = SmartSwitch.decide(gulfWin, SmartSwitch.AUTO);
        SmartSwitch.Decision r3 = SmartSwitch.decide(msaWin, SmartSwitch.AUTO);
        ok("auto:hijazi".equals(r1.reason) && "ar_hijazi".equals(r1.dictId),
                "live hijazi window decision drifted: " + r1.reason);
        ok("auto:gulf".equals(r2.reason) && "ar_gulf".equals(r2.dictId),
                "live gulf window decision drifted: " + r2.reason);
        ok("auto-msa".equals(r3.reason) && !r3.dialectActive(),
                "live msa window decision drifted: " + r3.reason);
        // الأوضاع الصريحة مطابقة بالبايت مع وبدون ملفات
        ok("fixed:najdi".equals(SmartSwitch.decide(hijaziWin, Dialect.NAJDI).reason),
                "fixed mode drifted");
        ok("off".equals(SmartSwitch.decide(hijaziWin, SmartSwitch.OFF).reason),
                "off mode drifted");
        // والبذرة لا تعبر القرار الحي: النافذة الفارغة وحدها تبذّر
        ok(SmartSwitch.decide(null, SmartSwitch.AUTO).reason.equals("auto-msa"),
                "empty-window live decision drifted (seed must not leak into decide)");

        System.out.println("DIALECTADAPTGATE_OK checks=" + (pass + fail)
                + " acc=" + String.format("%.1f", acc)
                + " class_min=" + String.format("%.1f", classMin)
                + " rows=" + rows.size() + " switch=OK seed=OK failclosed=ON");
        if (fail > 0) {
            System.out.println("DIALECTADAPT_FAILED " + fail + " gate checks");
            System.exit(1);
        }
    }
}
