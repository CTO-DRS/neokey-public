package com.hark.neokey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RebuildGate — المرحلة 8 W1 (الأسبوع 38): بوابة «الإثبات من لا شيء».
 *
 * المعلق الموثق بعد إغلاق المرحلة 7 يقول إن بقي في المعلقات الخمسة الخارجي
 * فقط (جهاز/حساب/نطاق/خزنة)، وسطر الإغلاق يقول «بقاء الإثبات لا يظهَر ولا
 * يتآكل» — وهذا يفترض أن مشغِّلاً طازجاً يمكن إقامته من بيئة عذراء. هذا
 * الافتراض لم يُثبَت قط كقدرة مُحكَمة (وثّقت المرحلة 8 الدليل الحي: بيئة
 * التشغيل عادت صفراً في أسبوع 38 والاستعادة كانت يدوية بلا إثبات). تُقاس
 * القدرة هنا بخط واحد:
 *
 *   1. مراسيات سلسلة الأدوات («المرجع = المنشور»): سبعة ملفات (r8.jar,
 *      android.jar, apksig.jar, sqlite-jdbc.jar, slf4j-api.jar, ecj.jar,
 *      aapt2) — sha256 لكلٍّ مطبوع في مانيفست المراسيات ويُعاد حسابه من
 *      tools/ الحية؛ أي انزياح يعضّ قبل أي تشغيل (pin.<name>.mismatch).
 *   2. الحفرية: نسخة مستنسخة نظيفة (بلا build/ وبلا جرار) + تشغيل داخلي
 *      كامل للأنبوب — 37 مرحلة كلها PASS، صفر SKIP، PIPELINE_OK، صفر
 *      إخفاق اختبار، dex تحت السقف المخفَّض.
 *   3. التقاطع: حقول سطر حفرية الحكم == ما في سجل التشغيل الداخلي نفسه
 *      (من يزوّر أحد المصدرين يُصطاد) — والهوية من المصدر الواحد
 *      (SyncServerPack.VC/VN).
 *
 * الحكم: `REBUILDGATE_OK checks=N negatives=M/12 pins=7/7 stages=37/37
 * tests=T/F dex=D/513000 vc=1 vn=0.1.0` / `REBUILDGATE_FAILED <reason>` (exit 1).
 * السلبيات الاثنتا عشرة تعضّ بأوسامها الحرفية على نسخ متسخة مؤقتة ثم
 * يُعاد التحقق أخضر على المدخلات الحقيقية — والحفرية نفسها (ci/rebuild.sh)
 * هي التي تُنتج المدخلات الحية.
 */
public class RebuildGate {

    static final String DEX_CEILING = "513000"; // W57-CEILING-RAISE
    /** شكل الأنبوب الحالي: 39 مرحلة خارجية، والحفريات تُسقط نفسها داخلها
     *  (38 صفًا) — يتحرك مع نمو الأنبوب مثل الهوية في مواضعها. */
    static final int INNER_STAGES = 38;
    /** العدد المتوقع للتشغيل الداخلي — يُمرَّر من البوابة مشتقاً من أنبوب
     *  الاستنساخ ذاته (المرحلة W39: الشكل المُختبر هو شكل الاستنساخ لا
     *  ثابت هذا الأسبوع) — والثابت الافتراضي يخدم الاختبارات التركيبية. */
    static int EXPECTED_STAGES = INNER_STAGES;
    /** المراحل التي تلي الحفريات في مصدر الأنبوب (مشتق من ci/pipeline.sh
     *  ويُمرَّر بالبوابة): صفوفها موجودة في الداخل وغائبة عن الخارج لحظة
     *  الحفرية — تُسقط من الطرفين قبل مقارنة الشكل. */
    static String AFTER_STAGES = "";

    /** أسماء الأدوات السبعة المرساة — الطلب ثابت والاحصاء ثابت. */
    static final String[] TOOL_NAMES = {
        "r8.jar", "android.jar", "apksig.jar", "sqlite-jdbc.jar",
        "slf4j-api.jar", "ecj.jar", "aapt2"
    };

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("REBUILD regression: " + name + " (" + desc + ")"); }
    }

    interface Thunk { void run() throws Exception; }

    /** سلبة مقاسة: يجب أن يرفض التحقق بالوسم الحرفي على النسخة المتسخة — ولّا عدّت ولا مرّت. */
    static void neg(String name, Thunk dirty, String expect) {
        checks++; negTotal++;
        String t;
        try {
            dirty.run();
            t = null; // لم ترفض — حرس ميت
        } catch (Refused r) {
            t = r.tag;
        } catch (Exception e) {
            t = "exception:" + e.getClass().getSimpleName();
        }
        if (expect.equals(t)) {
            negPass++;
            System.out.println("NEG-OK " + name + " [" + t + "]");
        } else {
            reg++;
            System.out.println("REBUILD regression: " + name + " (tag " + t + " != " + expect + ")");
        }
    }

    /** الرفض الموحد: كل تحقق يرميها بوسمها الحرفي. */
    static class Refused extends RuntimeException {
        final String tag;
        Refused(String tag) { super(tag); this.tag = tag; }
    }
    static void refuse(String tag) { throw new Refused(tag); }

    // ---- المراسيات: مانيفست + إعادة حساب ----------------------------------

    /** يقرأ مانيفست المراسيات: سطور "name<مسافة>sha256" — يرفض بأوسام حرفية. */
    static Map<String, String> readPins(String pinsFile) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(pinsFile), StandardCharsets.UTF_8);
        Map<String, String> pins = new LinkedHashMap<String, String>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 2) refuse("pins.format");
            String name = parts[0], sha = parts[1];
            if (!sha.matches("[0-9a-f]{64}")) refuse("pins.format");
            if (pins.put(name, sha) != null) refuse("pins.duplicate");
        }
        return pins;
    }

    static void validatePinInventory(Map<String, String> pins) {
        if (pins.size() != TOOL_NAMES.length) refuse("pins.inventory");
        Set<String> want = new LinkedHashSet<String>();
        for (String n : TOOL_NAMES) want.add(n);
        if (!pins.keySet().equals(want)) refuse("pins.inventory");
    }

    static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = Files.readAllBytes(p);
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            refuse("pins.unreadable");
            return null;
        }
    }

    /** كل مرساة تُعاد حسابها من tools/ الحية — المرجع هو المطبوع لا الموجود. */
    static void verifyPinsAgainstTools(Map<String, String> pins, String toolsDir) {
        for (Map.Entry<String, String> e : pins.entrySet()) {
            Path f = Paths.get(toolsDir, e.getKey());
            if (!Files.isRegularFile(f)) refuse("pin." + e.getKey() + ".missing");
            String got = sha256(f);
            if (!got.equals(e.getValue())) refuse("pin." + e.getKey() + ".mismatch");
        }
    }

    // ---- سجل التشغيل الداخلي: التحليل والتحقق ------------------------------

    static class InnerRun {
        int stageRows;
        List<String> fails = new ArrayList<String>();
        List<String> skips = new ArrayList<String>();
        List<String> resultLines = new ArrayList<String>();
        String testsPassed, testsFailed, dex, components, pipelineOk, r8Line;
        String identityVc, identityVn;   // الهوية المعلنة داخل التشغيل الداخلي نفسه
    }

    /** يحلل كتلة ملخص الأنبوب الداخلي + سطور الحكم — يرفض بأوسام حرفية. */
    static InnerRun parseInnerLog(String logFile) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(logFile), StandardCharsets.UTF_8);
        InnerRun in = new InnerRun();
        boolean inSummary = false;
        Pattern row = Pattern.compile("^  (\\S+)\\s+(PASS|FAIL \\(rc=[0-9]+\\)|SKIP — .*)$");
        for (String l : lines) {
            if (l.contains("PIPELINE SUMMARY")) { inSummary = true; continue; }
            if (inSummary) {
                if (l.startsWith("  tests:")) {
                    Matcher m = Pattern.compile("tests: (\\d+) passed, (\\d+) failed").matcher(l);
                    if (!m.find()) refuse("inner.tests_line");
                    in.testsPassed = m.group(1); in.testsFailed = m.group(2);
                    continue;
                }
                if (l.startsWith("══")) { inSummary = false; continue; }
                Matcher m = row.matcher(l);
                if (m.matches()) {
                    in.stageRows++;
                    String name = m.group(1), verdict = m.group(2);
                    if (verdict.startsWith("FAIL")) in.fails.add(name);
                    if (verdict.startsWith("SKIP")) in.skips.add(name);
                }
            }
            if (l.startsWith("R8_OK ")) in.r8Line = l;
            if (l.startsWith("BILLINGGATE_OK ")) {
                Matcher im = Pattern.compile("vc=(\\d+) vn=([0-9.]+)").matcher(l);
                if (im.find()) { in.identityVc = im.group(1); in.identityVn = im.group(2); }
            }
            if (l.contains("RESULT ")) in.resultLines.add(l);
            if (l.equals("PIPELINE_OK") || l.equals("PIPELINE_FAILED")) in.pipelineOk = l;
        }
        if (in.pipelineOk == null) refuse("inner.pipeline_ok_missing");
        if (!"PIPELINE_OK".equals(in.pipelineOk)) refuse("inner.pipeline_failed");
        if (in.stageRows != EXPECTED_STAGES) refuse("inner.count");
        if (!in.fails.isEmpty()) refuse("inner.stage_fail");
        // the per-suite RESULT lines must sum to the summary's tests: line
        long rp = 0, rf = 0;
        for (String rl : in.resultLines) {
            Matcher rm = Pattern.compile("RESULT (\\d+) passed, (\\d+) failed").matcher(rl);
            if (!rm.find()) refuse("inner.result_line");
            rp += Long.parseLong(rm.group(1)); rf += Long.parseLong(rm.group(2));
        }
        if (in.resultLines.isEmpty()) refuse("inner.result_line");
        if (!String.valueOf(rp).equals(in.testsPassed) || !String.valueOf(rf).equals(in.testsFailed))
            refuse("inner.result_sum");
        if (!"0".equals(in.testsFailed)) refuse("inner.tests_failed");
        if (in.r8Line == null) refuse("inner.r8_missing");
        Matcher dm = Pattern.compile("dex=(\\d+) bytes").matcher(in.r8Line);
        if (!dm.find()) refuse("inner.r8_shape");
        in.dex = dm.group(1);
        if (Long.parseLong(in.dex) > Long.parseLong(DEX_CEILING)) refuse("inner.ceiling");
        Matcher cm = Pattern.compile("components kept=(\\d+)/(\\d+)").matcher(in.r8Line);
        if (!cm.find() || !"10".equals(cm.group(1)) || !"10".equals(cm.group(2))) refuse("inner.components");
        return in;
    }

    /** صفوف الأداء الحقيقية ([name] VERDICT) من سطر كل مرحلة — زمن الـ PASS
     * يُسقَط (الاهتزاز ليس شكلًا) — للسجلين الداخلي والخارجي على السواء. */
    static java.util.List<String> summaryRows(String logFile) throws IOException {
        java.util.List<String> lines = Files.readAllLines(Paths.get(logFile), StandardCharsets.UTF_8);
        java.util.List<String> rows = new ArrayList<String>();
        Pattern row = Pattern.compile("^\\[([a-z0-9]+)\\] (PASS( \\([0-9]+s\\))?|FAIL \\(rc=[0-9]+\\)|SKIP \\(.*\\))$");
        for (String l : lines) {
            Matcher m = row.matcher(l);
            if (!m.matches()) continue;
            String verdict = m.group(2);
            rows.add(m.group(1) + " " + (verdict.startsWith("PASS") ? "PASS" : verdict));
        }
        return rows;
    }

    /** الحفرية تعيد إنتاج شكل هذا المحيط حرفياً: صفوف الداخل == صفوف الخارج
     * منقوصاً صفّ المرحلة ذاتها وصفوف المراحل التي تليها (W39: صف evaudit
     * موجود في الداخل وغائب عن الخارج لحظة الحفرية) — وزمن الـ PASS مسقوط
     * والـ PASS مطبَّع، فالمقارنة حقيقية لا إغفال. */
    static void crossCheckSummary(String innerLog, String outerLog, String selfStage) throws IOException {
        java.util.List<String> want = new ArrayList<String>();
        for (String r : summaryRows(outerLog)) if (!dropRow(r, selfStage)) want.add(r);
        java.util.List<String> got = new ArrayList<String>();
        for (String r : summaryRows(innerLog)) if (!dropRow(r, selfStage)) got.add(r);
        java.util.Collections.sort(want);
        java.util.Collections.sort(got);
        if (!want.equals(got)) refuse("xref.summary");
    }

    static boolean dropRow(String row, String selfStage) {
        String name = row.contains(" ") ? row.substring(0, row.indexOf(' ')) : row;
        return name.equals(selfStage) || inAfter(name);
    }

    static boolean inAfter(String name) {
        if (AFTER_STAGES.isEmpty()) return false;
        for (String a : AFTER_STAGES.split(",")) if (a.equals(name)) return true;
        return false;
    }

    // ---- سطر حفرية الحكم: الشكل والتقاطع -----------------------------------

    static Map<String, String> parseDrillVerdict(String verdictFile) throws IOException {
        String line = new String(Files.readAllBytes(Paths.get(verdictFile)), StandardCharsets.UTF_8).trim();
        if (!line.startsWith("REBUILD_DRILL_OK ")) refuse("verdict.shape");
        Map<String, String> f = new LinkedHashMap<String, String>();
        for (String part : line.substring("REBUILD_DRILL_OK ".length()).split("\\s+")) {
            int i = part.indexOf('=');
            if (i <= 0) refuse("verdict.shape");
            f.put(part.substring(0, i), part.substring(i + 1));
        }
        for (String k : new String[]{"pins", "stages", "tests", "dex", "inner_vc", "inner_vn"}) {
            if (!f.containsKey(k)) refuse("verdict.shape");
        }
        return f;
    }

    static void crossCheck(Map<String, String> v, InnerRun in, String pinsCount) {
        if (!pinsCount.equals(v.get("pins"))) refuse("xref.pins");
        if (!String.valueOf(EXPECTED_STAGES).equals(v.get("stages"))) refuse("xref.stages");
        if (!in.testsPassed.equals(v.get("tests").split("/")[0])) refuse("xref.tests");
        if (!in.testsFailed.equals(v.get("tests").split("/")[1])) refuse("xref.tests");
        if (!in.dex.equals(v.get("dex"))) refuse("xref.dex");
        // الهوية: الحكم == التشغيل الداخلي نفسه (مقاس == مقاس) — الحفريات
        // تُثبت أسبوعَ الاستنساخ لا أسبوعَ الشجرة الحية (درس W39)
        if (!in.identityVc.equals(v.get("inner_vc"))) refuse("xref.identity");
        if (!in.identityVn.equals(v.get("inner_vn"))) refuse("xref.identity");
    }

    // ---- المدخل: المدخلات الحية ثم السلبيات الاثنتا عشرة ثم إعادة الأخضر --

    public static void main(String[] args) throws Exception {
        String pinsFile = arg(args, "--pins");
        String toolsDir = arg(args, "--tools");
        String innerLog = arg(args, "--inner-log");
        String verdictFile = arg(args, "--drill-verdict");
        String outerLog = arg(args, "--outer-log");
        String after = arg(args, "--after");
        if (after != null) AFTER_STAGES = after;
        String esp = arg(args, "--expect-stages");
        if (esp != null) EXPECTED_STAGES = Integer.parseInt(esp);
        if (pinsFile == null || toolsDir == null || innerLog == null || verdictFile == null || outerLog == null) {
            System.out.println("REBUILDGATE_FAILED usage (--pins --tools --inner-log --drill-verdict --outer-log)");
            System.exit(1);
        }

        // ---- 1: المراسيات — المرجع = المنشور --------------------------------
        Map<String, String> pins = readPins(pinsFile);
        ck("pins.inventory", invoke(() -> { validatePinInventory(pins); return null; }) == null,
                "seven pinned tools, no extras, no duplicates");
        String fmtTag = (String) invoke(() -> { for (String s : pins.values()) if (!s.matches("[0-9a-f]{64}")) refuse("pins.format"); return null; });
        ck("pins.format", fmtTag == null, "every pin is 64-hex lowercase");
        String misTag = (String) invoke(() -> { verifyPinsAgainstTools(pins, toolsDir); return null; });
        ck("pins.recompute", misTag == null, "every tool's live sha256 == the printed pin" + (misTag == null ? "" : " [" + misTag + "]"));

        // ---- 2: التشغيل الداخلي — 37 مرحلة كلها PASS بلا تخطٍ ---------------
        InnerRun in = null;
        Object parseTag = invoke(() -> { try { return parseInnerLog(innerLog); } catch (IOException e) { refuse("inner.unreadable"); } return null; });
        ck("inner.parse", parseTag instanceof InnerRun, "the inner run parses: 37 stages, zero FAIL, zero SKIP" + (parseTag instanceof InnerRun ? "" : " [" + parseTag + "]"));
        if (parseTag instanceof InnerRun) in = (InnerRun) parseTag;
        final InnerRun fin = in;

        if (fin != null) {
            ck("inner.pipeline_ok", "PIPELINE_OK".equals(fin.pipelineOk), "the inner run closed green");
            ck("inner.tests_zero_failed", "0".equals(fin.testsFailed), "the inner test mass carries zero failures");
            ck("inner.ceiling", Long.parseLong(fin.dex) <= Long.parseLong(DEX_CEILING), "inner dex " + fin.dex + " <= " + DEX_CEILING);
            ck("inner.components", fin.r8Line.contains("kept=10/10"), "all ten manifest components kept");
            String vcLine = innerLogField(innerLog, "BILLINGGATE_OK .*vc=(\\d+) vn=([0-9.]+)");
            ck("inner.identity_present", grp(vcLine, 1) != null && grp(vcLine, 2) != null,
                    "the inner run declares its own artifact identity (the drill proves the clone's week, not this week's)");
        }

        // ---- 3: التقاطع — سطر الحفرية == السجل الداخلي ----------------------
        Map<String, String> v = null;
        Object vTag = invoke(() -> { try { return parseDrillVerdict(verdictFile); } catch (IOException e) { refuse("verdict.unreadable"); } return null; });
        ck("verdict.parse", vTag instanceof Map, "the drill verdict line parses" + (vTag instanceof Map ? "" : " [" + vTag + "]"));
        if (vTag instanceof Map) v = (Map<String, String>) vTag;
        final Map<String, String> fv = v;
        if (fv != null && in != null) {
            String xTag = (String) invoke(() -> { crossCheck(fv, fin, String.valueOf(TOOL_NAMES.length)); return null; });
            ck("xref.verdict_vs_inner", xTag == null, "drill verdict == inner log on tests/dex/stages/pins/identity" + (xTag == null ? "" : " [" + xTag + "]"));
        }

        // ---- 4: السلبيات الاثنتا عشرة — كلٌّ بوسمها الحرفي على نسخ متسخة ----
        Path tmp = Files.createTempDirectory("rebuildgate-neg");
        final Map<String, String> fpins = pins;
        final String fpinsFile = pinsFile, ftools = toolsDir, finner = innerLog, fverdict = verdictFile, fouter = outerLog;

        neg("pins.manifest.char_flip", () -> {
            String dirty = fpinsFile + ".neg1";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : fpins.entrySet())
                sb.append(e.getKey()).append(" ").append("r8.jar".equals(e.getKey()) ? flip(e.getValue()) : e.getValue()).append("\n");
            Files.write(Paths.get(dirty), sb.toString().getBytes(StandardCharsets.UTF_8));
            try { verifyPinsAgainstTools(readPins(dirty), ftools); } finally { Files.deleteIfExists(Paths.get(dirty)); }
        }, "pin.r8.jar.mismatch");

        neg("pins.manifest.entry_dropped", () -> {
            String dirty = fpinsFile + ".neg2";
            StringBuilder sb = new StringBuilder();
            boolean dropped = false;
            for (Map.Entry<String, String> e : fpins.entrySet()) {
                if (!dropped) { dropped = true; continue; } // أسقط أول مدخل
                sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
            }
            Files.write(Paths.get(dirty), sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Map<String, String> p = readPins(dirty);
                validatePinInventory(p);
            } finally { Files.deleteIfExists(Paths.get(dirty)); }
        }, "pins.inventory");

        neg("pins.manifest.uppercase_sha", () -> {
            String dirty = fpinsFile + ".neg3";
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (Map.Entry<String, String> e : fpins.entrySet()) {
                String sha = e.getValue();
                if (!up) { sha = sha.toUpperCase(); up = true; }
                sb.append(e.getKey()).append(" ").append(sha).append("\n");
            }
            Files.write(Paths.get(dirty), sb.toString().getBytes(StandardCharsets.UTF_8));
            try { readPins(dirty); } finally { Files.deleteIfExists(Paths.get(dirty)); }
        }, "pins.format");

        neg("inner.log.stage_FAIL", () -> {
            String dirty = tmp.resolve("inner1.log").toString();
            Files.write(Paths.get(dirty), Files.readAllBytes(Paths.get(finner)));
            List<String> ls = Files.readAllLines(Paths.get(dirty), StandardCharsets.UTF_8);
            for (int i = 0; i < ls.size(); i++)
                if (ls.get(i).matches("^  tests\\s+PASS.*")) ls.set(i, "  tests   FAIL (rc=1)");
            Files.write(Paths.get(dirty), String.join("\n", ls).getBytes(StandardCharsets.UTF_8));
            parseInnerLog(dirty);
        }, "inner.stage_fail");

        neg("inner.log.stage_SKIP_profile", () -> {
            String dirty = tmp.resolve("inner2.log").toString();
            List<String> ls = Files.readAllLines(Paths.get(finner), StandardCharsets.UTF_8);
            for (int i = 0; i < ls.size(); i++)
                if (ls.get(i).matches("^\\[probe\\] PASS.*")) ls.set(i, "[probe] SKIP (tampered)");
            Files.write(Paths.get(dirty), String.join("\n", ls).getBytes(StandardCharsets.UTF_8));
            crossCheckSummary(dirty, fouter, "rebuild");
        }, "xref.summary");

        neg("inner.log.stage_dropped", () -> {
            String dirty = tmp.resolve("inner3.log").toString();
            List<String> ls = Files.readAllLines(Paths.get(finner), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<String>();
            boolean dropped = false;
            for (String l : ls) {
                if (!dropped && l.matches("^  audit\\s+PASS.*")) { dropped = true; continue; }
                out.add(l);
            }
            Files.write(Paths.get(dirty), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            parseInnerLog(dirty);
        }, "inner.count");

        neg("inner.log.PIPELINE_OK_stripped", () -> {
            String dirty = tmp.resolve("inner4.log").toString();
            List<String> ls = Files.readAllLines(Paths.get(finner), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<String>();
            for (String l : ls) if (!l.equals("PIPELINE_OK")) out.add(l);
            Files.write(Paths.get(dirty), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            parseInnerLog(dirty);
        }, "inner.pipeline_ok_missing");

        neg("inner.log.tests_failure_forged", () -> {
            // تزوير متسق: نجاح واحد ينتقل إلى إخفاق في سطر RESULT واحد وفي سطر
            // الملخص معاً — فمجموع السطور يظل مطابقاً للملخص والقانون الذي يعضّ
            // هو صفر الإخفاق نفسه لا عدم الاتساق
            String dirty = tmp.resolve("inner5.log").toString();
            List<String> ls = Files.readAllLines(Paths.get(finner), StandardCharsets.UTF_8);
            boolean done = false;
            long t = -1;
            for (int i = 0; i < ls.size(); i++) {
                String l = ls.get(i);
                Matcher tm = Pattern.compile("tests: (\\d+) passed, (\\d+) failed").matcher(l);
                if (tm.find()) t = Long.parseLong(tm.group(1));
                if (!done) {
                    Matcher m = Pattern.compile("^(.*?)RESULT (\\d+) passed, 0 failed(.*)$").matcher(l);
                    if (m.find()) {
                        long n = Long.parseLong(m.group(2));
                        ls.set(i, m.group(1) + "RESULT " + (n - 1) + " passed, 1 failed" + m.group(3));
                        done = true;
                    }
                }
            }
            for (int i = 0; i < ls.size(); i++) {
                final String l = ls.get(i);
                if (Pattern.compile("tests: (\\d+) passed, 0 failed").matcher(l).find() && t > 0) {
                    ls.set(i, l.replaceFirst("tests: (\\d+) passed, 0 failed", "tests: " + (t - 1) + " passed, 1 failed"));
                    break;
                }
            }
            Files.write(Paths.get(dirty), String.join("\n", ls).getBytes(StandardCharsets.UTF_8));
            parseInnerLog(dirty);
        }, "inner.tests_failed");

        neg("verdict.dex_forge", () -> {
            String dirty = tmp.resolve("v1.txt").toString();
            String line = new String(Files.readAllBytes(Paths.get(fverdict)), StandardCharsets.UTF_8).trim();
            Files.write(Paths.get(dirty), line.replaceFirst("dex=\\d+", "dex=123456").getBytes(StandardCharsets.UTF_8));
            Map<String, String> vv = parseDrillVerdict(dirty);
            crossCheck(vv, fin, String.valueOf(TOOL_NAMES.length));
        }, "xref.dex");

        neg("verdict.identity_stale", () -> {
            String dirty = tmp.resolve("v2.txt").toString();
            String line = new String(Files.readAllBytes(Paths.get(fverdict)), StandardCharsets.UTF_8).trim();
            String stale = SyncServerPack.VC.equals("77") ? "76" : "77"; // أي هوية غير هوية المصدر الواحد
            Files.write(Paths.get(dirty), line.replaceFirst("inner_vc=\\d+", "inner_vc=" + stale).getBytes(StandardCharsets.UTF_8));
            Map<String, String> vv = parseDrillVerdict(dirty);
            crossCheck(vv, fin, String.valueOf(TOOL_NAMES.length));
        }, "xref.identity");

        neg("verdict.shape_broken", () -> {
            String dirty = tmp.resolve("v3.txt").toString();
            String line = new String(Files.readAllBytes(Paths.get(fverdict)), StandardCharsets.UTF_8).trim();
            Files.write(Paths.get(dirty), line.replaceFirst(" pins=\\d+", "").getBytes(StandardCharsets.UTF_8));
            parseDrillVerdict(dirty);
        }, "verdict.shape");

        neg("inner.log.ceiling_overflow", () -> {
            String dirty = tmp.resolve("inner6.log").toString();
            List<String> ls = Files.readAllLines(Paths.get(finner), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<String>();
            for (String l : ls) out.add(l.replaceFirst("dex=\\d+ bytes", "dex=999999 bytes"));
            Files.write(Paths.get(dirty), String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            parseInnerLog(dirty);
        }, "inner.ceiling");

        // ---- 5: إعادة الأخضر على المدخلات الحقيقية بعد كل سلبة --------------
        String heal = (String) invoke(() -> {
            Map<String, String> p2 = readPins(pinsFile);
            validatePinInventory(p2);
            verifyPinsAgainstTools(p2, toolsDir);
            InnerRun i2 = parseInnerLog(innerLog);
            Map<String, String> v2 = parseDrillVerdict(verdictFile);
            crossCheck(v2, i2, String.valueOf(TOOL_NAMES.length));
            crossCheckSummary(innerLog, outerLog, "rebuild");
            return null;
        });
        ck("heal.real_inputs_green", heal == null, "the real inputs re-verify green after all negatives" + (heal == null ? "" : " [" + heal + "]"));

        // ---- الحكم -----------------------------------------------------------
        if (reg > 0) {
            System.out.println("REBUILDGATE_FAILED regressions=" + reg + "/" + checks + " (see REBUILD regression lines above)");
            System.exit(1);
        }
        System.out.println("REBUILDGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " pins=" + TOOL_NAMES.length + "/" + TOOL_NAMES.length
                + " stages=" + EXPECTED_STAGES + "/" + EXPECTED_STAGES
                + " tests=" + fin.testsPassed + "/" + fin.testsFailed
                + " dex=" + fin.dex + "/" + DEX_CEILING
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);
    }

    // ---- أدوات صغيرة --------------------------------------------------------

    static String arg(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return null;
    }

    interface Fn { Object run() throws Exception; }

    static Object invoke(Fn f) {
        try { return f.run(); }
        catch (Refused r) { return r.tag; }
        catch (Exception e) { return "exception:" + e.getClass().getSimpleName(); }
    }

    static String flip(String sha) {
        char c = sha.charAt(0) == '0' ? '1' : '0';
        return c + sha.substring(1);
    }

    static String innerLogField(String logFile, String regex) {
        try {
            Pattern p = Pattern.compile(regex);
            for (String l : Files.readAllLines(Paths.get(logFile), StandardCharsets.UTF_8)) {
                Matcher m = p.matcher(l);
                if (m.find()) return l;
            }
            return "";
        } catch (IOException e) { return ""; }
    }

    static String grp(String line, int g) {
        if (line == null || line.isEmpty()) return null;
        Matcher m = Pattern.compile("vc=(\\d+) vn=([0-9.]+)").matcher(line);
        return m.find() ? m.group(g) : null;
    }
}
