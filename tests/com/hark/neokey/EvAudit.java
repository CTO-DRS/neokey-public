package com.hark.neokey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EvAudit — المرحلة 8 W2 (الأسبوع 39): مُدقِّق أدلة الأسبوع.
 *
 * سطر إغلاق المرحلة 7 يقول «بقاء الإثبات لا يظهَر ولا يتآكل»، وأرشيفات
 * الأدلة (نسخ evidence القديمة، و`build/release/` الحية) هي ما يبقى من
 * الإثبات بعد انطفاء التشغيل — لكن لا شيء كان يتحقق من الأرشيف ذاته
 * معزولاً: من يفتح الأرشيف بعد أسبوعين يثق بعينيه. المعلقتان #3 و#4
 * (نقرة اللوحة، استعادة الخزنة) تقولان إن المالك يخطو خطوته الخارجية
 * **بناءً على الأدلة** — فالمدقق يجعل الأدلة نفسها قابلة للتحقق آلياً
 * قبل الخطوة، لا بعد الأسف.
 *
 * العقد (نقي JVM، صفر android.*، صفر تبعيات — يعمل على أرشيف أي أسبوع):
 *   • `pack <archiveDir> <pipelineLog> <releaseDir> <ciDir>` — يجمع أرشيف
 *     الأدلة حتمياً (بلا طوابع زمن): السجل + الدفتر + المانيفست + خطتي
 *     التدرج + ورقة التسليم (إن وجدت) + كل سجلات الإثبات السلبي المحفوظة،
 *     ويكتب `SHA256SUMS` (مرتباً بالاسم، متوافقاً مع sha256sum -c).
 *   • `check <archiveDir> [--expect-vc N] [--expect-vn X]` — التدقيق
 *     المعزول، أربع طبقات من القوانين:
 *       1. سلامة الأعضاء: كل عضو معلَن حاضر وsha256 مطابق بايت-بايت، ولا
 *          عضو غير معلَن، والأعضاء الإلزامية الخمسة حاضرة.
 *       2. شكل السجل: صفر صفوف FAIL، مجموع سطور RESULT بلا إخفاق، سطر
 *          الملخص == المجموع وكتلة الملخص == صفوف الحكم في الشكل المكتمل،
 *          وسقف dex المخفَّض. الشكلان مشروعان ويُكتشفان من السجل: «mid»
 *          (أثناء التشغيل: لا PIPELINE_OK بعد) و«complete».
 *       3. تطابق الأحكام مع الدفتر: كل سطر حكم معروف في السجل يجب أن تجد
 *          مفاتيحه في gates.env بقيم مطابقة — سطر مزوَّر أو مفتاح ناقص
 *          أو قيمة منحرفة يُصطاد كلٌّ بوسمه.
 *       4. إجماع الهوية وقانون الإثبات: كل مصادر vc/vn (سطور الأحكام +
 *          الدفتر + المانيفست) إجماع واحد (--expect يُطبَّق فوقه)، وكل
 *          بوابة ظهر حكمها يجب أن تجد سجل إثباتها السلبي داخل الأرشيف
 *          حاملاً دليله (سطر نجاحه الحديث أو سطور عضّه في محوّطة
 *          الأسابيع الأولى) — «الإثبات السلبي المحفوظ إلزامي» قانون
 *          مشروع لا نصيحة.
 *
 * الحكم: `EVAUDITGATE_OK checks=N negatives=M/12 members=N proofs=P/P
 * vc=V vn=W tests=T/F dex=D/539000` / `EVAUDITGATE_FAILED <reason>`
 * (exit 1). اثنتا عشرة سلبة تعضّ بأوسامها الحرفية على نسخ متسخة مؤقتة —
 * ومنها التزوير «المتسق» الذي يعيد ختم SHA256SUMS كله بعد العبث: الهاش
 * وحده لا يحمي ممن يملك المدقق، فالقوانين الأعمق (تطابق الدفتر، إجماع
 * الهوية، قانون الإثبات، السقف) هي التي تعضّ — درس البيان في W35 مطبَّق
 * على الأرشيف كله.
 */
public class EvAudit {

    static final String DEX_CEILING = "539000"; // W58-CEILING-RAISE
    /** الأعضاء الإلزامية — أرشيف بلا أحدها ليس أرشيف أدلة أسبوع. */
    static final String[] REQUIRED_MEMBERS = {
        "pipeline.log", "gates.env", "release-manifest.json",
        "rollout-plan.json", "rollout-plan.txt"
    };
    static final String SUMS_NAME = "SHA256SUMS";
    /** دليل الإثبات السلبي المحفوظ — شكلانه عبر الأسابيع: سطر نجاح حديث
     *  (PROOF PASSED / *_NEGATIVE_PROOF_OK / *_PROOF_PASSED) أو محوّطة
     *  الأسابيع 19-21 (سطور العضّ الحرفية *_FAILED) — كلاهما إثبات، ولا
     *  شيء منهما = ملف مفرّغ لا دليل فيه. */
    static final Pattern PROOF_EVIDENCE =
            Pattern.compile("PROOF[ _]PASSED|NEGATIVE_PROOF_OK|[A-Z][A-Z0-9_]*FAILED");

    /** سطر الحكم ← حقل السطر ← مفتاح الدفتر. كل ثلاثية مؤكدة من
     *  سكربتات البوابات نفسها — لا تخمين. */
    static final String[][] ENV_TABLE = {
        {"SIGNEDAPKGATE_OK", "checks", "SIGNEDAPK_CHECKS"},
        {"SIGNEDAPKGATE_OK", "negatives", "SIGNEDAPK_NEGATIVES"},
        {"SIGNEDAPKGATE_OK", "fp16", "SIGNEDAPK_FP16"},
        {"SIGNEDAPKGATE_OK", "vc", "SIGNEDAPK_VC"},
        {"SIGNEDAPKGATE_OK", "vn", "SIGNEDAPK_VN"},
        {"UPLOADBUNDLEGATE_OK", "checks", "UPLOADBUNDLE_CHECKS"},
        {"UPLOADBUNDLEGATE_OK", "negatives", "UPLOADBUNDLE_NEGATIVES"},
        {"UPLOADBUNDLEGATE_OK", "members", "UPLOADBUNDLE_MEMBERS"},
        {"UPLOADBUNDLEGATE_OK", "evidence", "UPLOADBUNDLE_EVIDENCE"},
        {"BILLINGGATE_OK", "checks", "BILLING_CHECKS"},
        {"BILLINGGATE_OK", "negatives", "BILLING_NEGATIVES"},
        {"BILLINGGATE_OK", "vc", "BILLING_VC"},
        {"BILLINGGATE_OK", "vn", "BILLING_VN"},
        {"DEPLOYSRVGATE_OK", "checks", "DEPLOYSRV_CHECKS"},
        {"DEPLOYSRVGATE_OK", "negatives", "DEPLOYSRV_NEGATIVES"},
        {"DEPLOYSRVGATE_OK", "members", "DEPLOYSRV_MEMBERS"},
        {"DEPLOYSRVGATE_OK", "vc", "DEPLOYSRV_VC"},
        {"DEPLOYSRVGATE_OK", "vn", "DEPLOYSRV_VN"},
        {"PREFLIGHTGATE_OK", "checks", "PREFLIGHT_CHECKS"},
        {"PREFLIGHTGATE_OK", "negatives", "PREFLIGHT_NEGATIVES"},
        {"PREFLIGHTGATE_OK", "rows", "PREFLIGHT_ROWS"},
        {"PREFLIGHTGATE_OK", "checked", "PREFLIGHT_CHECKED"},
        {"PREFLIGHTGATE_OK", "outside", "PREFLIGHT_OUTSIDE"},
        {"PREFLIGHTGATE_OK", "vc", "PREFLIGHT_VC"},
        {"PREFLIGHTGATE_OK", "vn", "PREFLIGHT_VN"},
        {"REBUILDGATE_OK", "checks", "REBUILD_CHECKS"},
        {"REBUILDGATE_OK", "negatives", "REBUILD_NEGATIVES"},
        {"REBUILDGATE_OK", "pins", "REBUILD_PINS"},
        {"REBUILDGATE_OK", "stages", "REBUILD_STAGES"},
        {"REBUILDGATE_OK", "tests", "REBUILD_TESTS"},
        {"REBUILDGATE_OK", "dex", "REBUILD_DEX"},
        {"REBUILDGATE_OK", "vc", "REBUILD_VC"},
        {"REBUILDGATE_OK", "vn", "REBUILD_VN"},
        {"PHASE7_OK", "gates", "PHASE7_GATES"},
        {"PHASE7_OK", "regressions", "PHASE7_REGRESSIONS"},
        {"PHASE7_OK", "vc", "PHASE7_VC"},
        {"PHASE7_OK", "tests", "PHASE7_TESTS"},
        {"OPVERIFYGATE_OK", "checks", "OPVERIFY_CHECKS"},
        {"OPVERIFYGATE_OK", "negatives", "OPVERIFY_NEGATIVES"},
        {"OPVERIFYGATE_OK", "vc", "OPVERIFY_VC"},
        {"OPVERIFYGATE_OK", "vn", "OPVERIFY_VN"},
        {"PHASE8_OK", "gates", "PHASE8_GATES"},
        {"PHASE8_OK", "regressions", "PHASE8_REGRESSIONS"},
        {"PHASE8_OK", "vc", "PHASE8_VC"},
        {"PHASE8_OK", "tests", "PHASE8_TESTS"},
    };

    /** سطر الحكم ← سجل الإثبات السلبي المحفوظ الإلزامي (القانون لا النصيحة). */
    static final String[][] PROOF_TABLE = {
        {"BILLINGGATE_OK", "billing-negative-proof.log"},
        {"DEPLOYSRVGATE_OK", "deploysrv-negative-proof.log"},
        {"PREFLIGHTGATE_OK", "preflight-negative-proof.log"},
        {"REBUILDGATE_OK", "rebuild-negative-proof.log"},
        {"PHASE7_OK", "phase7-negative-proof.log"},
        {"PHASE6_OK", "phase6-negative-proof.log"},
        {"PHASE5_OK", "phase5-negative-proof.log"},
        {"PHASE4_OK", "phase4-negative-proof.log"},
        {"PHASE3_OK", "phase3-negative-proof.log"},
        {"SIGNEDAPKGATE_OK", "signedapk-negative-proof.log"},
        {"UPLOADBUNDLEGATE_OK", "uploadbundle-negative-proof.log"},
        {"PROVISIONGATE_OK", "provision-negative-proof.log"},
        {"PAYGATE_OK", "pay-negative-proof.log"},
        {"DEPLOYGATE_OK", "deploy-negative-proof.log"},
        {"TLSGATE_OK", "tls-negative-proof.log"},
        {"RESTGATE_OK", "rest-negative-proof.log"},
        {"KSDRILLGATE_OK", "ksdrill-negative-proof.log"},
        {"ENTGATE_OK", "entitlement-negative-proof.log"},
        {"OPVERIFYGATE_OK", "opverify-negative-proof.log"},
        // PHASE8_OK يُستثنى قصداً (درس W39 الآن لصنفين): إغلاق phase8 يعيد
        // تشغيل هذا المدقّق داخل كل استدعاء، وإثباته يُكتب بتشغيل الإغلاق
        // ذاته — مطلبه داخل الأرشيف مرجع ذاتي لا يمكن الوفاء به إلا بالتزوير،
        // وهو محفوظ في مستوى المستودع (ci/phase8-negative-proof.log) وفي حزمة
        // الأسبوع، وقارئ الأرشيف يتحقق منه هناك
    };

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("EVAUDIT regression: " + name + " (" + desc + ")"); }
    }

    interface Thunk { void run() throws Exception; }

    /** سلبة مقاسة: يجب أن يرفض التدقيق بالوسم الحرفي على النسخة المتسخة. */
    static void neg(String name, Thunk dirty, String expect) {
        checks++; negTotal++;
        String t;
        try {
            dirty.run();
            t = null; // لم يرفض — حرس ميت
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
            System.out.println("EVAUDIT regression: " + name + " (tag " + t + " != " + expect + ")");
        }
    }

    static class Refused extends RuntimeException {
        final String tag;
        Refused(String tag) { super(tag); this.tag = tag; }
    }
    static void refuse(String tag) { throw new Refused(tag); }

    // ---- الهاشات والمانيفست ---------------------------------------------------

    static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            refuse("member.unreadable " + p.getFileName());
            return null;
        }
    }

    static byte[] read(Path p) throws IOException { return Files.readAllBytes(p); }

    static void write(Path p, String s) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    /** يقرأ SHA256SUMS: سطور "<64hex>  <name>" — يرفض بأوسام حرفية. */
    static Map<String, String> readSums(Path sumsFile) throws IOException {
        if (!Files.isRegularFile(sumsFile)) refuse("sums.missing");
        List<String> lines = Files.readAllLines(sumsFile, StandardCharsets.UTF_8);
        Map<String, String> sums = new LinkedHashMap<String, String>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}") || parts[1].isEmpty())
                refuse("sums.format");
            if (parts[1].equals(SUMS_NAME)) refuse("sums.self_listed");
            if (sums.put(parts[1], parts[0]) != null) refuse("sums.duplicate " + parts[1]);
        }
        if (sums.isEmpty()) refuse("sums.empty");
        return sums;
    }

    /** يكتب SHA256SUMS حتمياً: مرتباً بالاسم، "<sha>  <name>". */
    static void writeSums(Path archiveDir, List<String> names) throws IOException {
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String n : sorted)
            sb.append(sha256(archiveDir.resolve(n))).append("  ").append(n).append('\n');
        write(archiveDir.resolve(SUMS_NAME), sb.toString());
    }

    /** إعادة ختم المانيفست كله — نمذجة مهاجم يملك المدقق نفسه: من يُصطاده
     *  إذن هو القوانين الأعمق لا الهاشات (درس البيان W35). */
    static void restampAll(Path archiveDir) throws IOException {
        List<String> names = new ArrayList<String>();
        try (java.util.stream.Stream<Path> st = Files.list(archiveDir)) {
            for (Path p : (Iterable<Path>) st::iterator)
                if (Files.isRegularFile(p) && !p.getFileName().toString().equals(SUMS_NAME))
                    names.add(p.getFileName().toString());
        }
        writeSums(archiveDir, names);
    }

    // ---- الحزم: أرشيف حتمي بلا طوابع زمن -------------------------------------

    /** يجمع أرشيف الأدلة من مصادر التشغيل الحية — يجمع لا يقرر. سجل إثبات
     *  المدقق ذاته يُستثنى استثناءً موثقاً: لا يمكن للأرشيف أن يحمل إثبات
     *  البوابة التي تحزمه دون تزوير ذاتي (يكتب في التشغيل ذاته) — هو محفوظ
     *  في مستوى المستودع (ci/evaudit-negative-proof.log) وفي حزمة الأسبوع،
     *  وقارئ الأرشيف يتحقق منه هناك. */
    public static String pack(Path archiveDir, Path pipelineLog, Path releaseDir, Path ciDir) throws IOException {
        for (Path src : new Path[]{pipelineLog, releaseDir, ciDir})
            if (!Files.exists(src)) refuse("pack.source_missing " + src);
        Files.createDirectories(archiveDir);
        List<String[]> copies = new ArrayList<String[]>();
        copies.add(new String[]{pipelineLog.toString(), "pipeline.log"});
        copies.add(new String[]{releaseDir.resolve("gates.env").toString(), "gates.env"});
        copies.add(new String[]{releaseDir.resolve("release-manifest.json").toString(), "release-manifest.json"});
        copies.add(new String[]{releaseDir.resolve("rollout-plan.json").toString(), "rollout-plan.json"});
        copies.add(new String[]{releaseDir.resolve("rollout-plan.txt").toString(), "rollout-plan.txt"});
        Path sheet = releaseDir.resolve("preflight-sheet.md");
        if (Files.isRegularFile(sheet)) copies.add(new String[]{sheet.toString(), "preflight-sheet.md"});
        List<Path> proofs = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> st = Files.list(ciDir)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && n.endsWith("-negative-proof.log")) proofs.add(p);
            }
        }
        Collections.sort(proofs, new Comparator<Path>() {
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        for (Path p : proofs) {
            String n = p.getFileName().toString();
            // لا إثبات ذاتي — انظر أعلاه: سجل المدقّق نفسه، ومنذ W41 سجل
            // إغلاق phase8 (الذي يعيد تشغيل المدقّق داخل كل استدعاء)
            // فئة الإثبات الذاتي (أعضاؤها الثلاثة): مدقق الأدلة نفسه، إغلاق
            // phase8 (يعيد تشغيل المدقق داخل كل استدعائه)، وبوابة النشر (W42:
            // إثباتها يُكتتب بتشغيلها نفسه عبر سكربت الإثبات) — لا بوابة
            // تحمل إثباتها داخل الأرشيف الذي تنشره
            if (n.equals("evaudit-negative-proof.log") || n.equals("phase8-negative-proof.log")
                    || n.equals("pubsync-negative-proof.log")) continue;
            copies.add(new String[]{p.toString(), n});
        }
        List<String> names = new ArrayList<String>();
        for (String[] c : copies) {
            Path src = Paths.get(c[0]);
            if (!Files.isRegularFile(src)) refuse("pack.source_missing " + c[1]);
            if (c[1].equals("gates.env")) {
                Files.copy(src, archiveDir.resolve(c[1]), StandardCopyOption.REPLACE_EXISTING);
            } else if (c[1].equals("pipeline.log")) {
                logMember(Paths.get(c[0]), archiveDir.resolve(c[1]));
            } else {
                textMember(Paths.get(c[0]), archiveDir.resolve(c[1]));
            }
            names.add(c[1]);
        }
        writeSums(archiveDir, names);
        return "EVAUDIT_PACK_OK members=" + names.size();
    }

    /** طابع زمن التشغيل الوحيد في أعضاء النصوص — يُطبَّع عند الحزم. */
    static final java.util.regex.Pattern TIMING = java.util.regex.Pattern.compile(" \\(\\d+s\\)$");
    static final java.util.regex.Pattern RUN_TS =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    /** عضو نصي مُطبَّع: أزواج توقيت المراحل (jitter — قانون W38) وأطوابع
     *  التشغيل ISO تُقلَع/تُطبَّع حتى تكون حزمُ نتيجتين متطابقتين بايتاتٍ
     *  متطابقة — الحتمية التي صُمِّم عليها الحزم، والنشر (W42) مستقرّ
     *  البايتات عبر التشغيلات. المحتوى (الصفوف والأحكام والأرقام) بايت-بايت. */
    /** عضو السجل: مثل textMember (إقالة التوقيت + تطابق الطوابع) زائد
     *  إسقاط سطور تشويش الـJVM («Picked up JAVA_TOOL_OPTIONS») — حديث
     *  البيئة لا حديث الأنبوب؛ إسقاطها يجعل عضو السجل مستقرّ البايتات
     *  عبر التشغيلات والبيئات (نتيجتان متطابقتان = بايتات متطابقة). */
    static void logMember(Path src, Path dst) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String ln : Files.readAllLines(src, StandardCharsets.UTF_8)) {
            if (ln.startsWith("Picked up ")) continue;
            java.util.regex.Matcher m = TIMING.matcher(ln);
            if (m.find()) ln = ln.substring(0, m.start());
            java.util.regex.Matcher ts = RUN_TS.matcher(ln);
            if (ts.find()) ln = ts.replaceAll("RUN-TIMESTAMP");
            out.add(ln);
        }
        Files.write(dst, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
    }

    static void textMember(Path src, Path dst) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String ln : Files.readAllLines(src, StandardCharsets.UTF_8)) {
            java.util.regex.Matcher m = TIMING.matcher(ln);
            if (m.find()) ln = ln.substring(0, m.start());
            java.util.regex.Matcher ts = RUN_TS.matcher(ln);
            if (ts.find()) ln = ts.replaceAll("RUN-TIMESTAMP");
            out.add(ln);
        }
        Files.write(dst, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
    }

    // ---- تحليل السجل: الصفوف والأرقام -----------------------------------------

    static class LogShape {
        List<String> stageRows = new ArrayList<String>();   // "name|PASS|FAIL|SKIP" (~ = صف الملخص)
        List<String> fails = new ArrayList<String>();
        List<String> resultLines = new ArrayList<String>();
        List<String> verdictLines = new ArrayList<String>(); // كل سطور السجل الخام
        String testsPassed, testsFailed;   // سطر الملخص (الشكل المكتمل فقط)
        String pipelineOk;                  // PIPELINE_OK / PIPELINE_FAILED / null (mid)
        String dex, components;             // من R8_OK
        boolean complete;
    }

    /** يحلل السجل — الشكلان مكتشفان من المحتوى: PIPELINE_OK حاضر = مكتمل. */
    static LogShape parseLog(Path logFile) throws IOException {
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        LogShape s = new LogShape();
        Pattern row = Pattern.compile("^\\[([a-z0-9]+)\\] (PASS( \\([0-9]+s\\))?|FAIL \\(rc=[0-9]+\\)|SKIP \\(.*\\))$");
        Pattern sumRow = Pattern.compile("^  (\\S+)\\s+(PASS|FAIL \\(rc=[0-9]+\\)|SKIP — .*)$");
        boolean inSummary = false;
        for (String l : lines) {
            Matcher m = row.matcher(l);
            if (m.matches()) {
                String st = m.group(2).startsWith("PASS") ? "PASS" : m.group(2).startsWith("FAIL") ? "FAIL" : "SKIP";
                s.stageRows.add(m.group(1) + "|" + st);
                if (st.equals("FAIL")) s.fails.add(m.group(1));
                continue;
            }
            if (l.contains("PIPELINE SUMMARY")) { inSummary = true; continue; }
            if (inSummary) {
                if (l.startsWith("  tests:")) {
                    Matcher tm = Pattern.compile("tests: (\\d+) passed, (\\d+) failed").matcher(l);
                    if (!tm.find()) refuse("log.tests_line");
                    s.testsPassed = tm.group(1); s.testsFailed = tm.group(2);
                    continue;
                }
                if (l.startsWith("══")) { inSummary = false; continue; }
                Matcher sm = sumRow.matcher(l);
                if (sm.matches()) {
                    String st = sm.group(2).startsWith("PASS") ? "PASS" : sm.group(2).startsWith("FAIL") ? "FAIL" : "SKIP";
                    s.stageRows.add("~" + sm.group(1) + "|" + st);
                }
            }
            if (l.equals("PIPELINE_OK") || l.equals("PIPELINE_FAILED")) s.pipelineOk = l;
            if (l.contains("RESULT ")) s.resultLines.add(l);
            if (l.startsWith("R8_OK ")) {
                Matcher dm = Pattern.compile("dex=(\\d+) bytes").matcher(l);
                if (!dm.find()) refuse("log.r8_shape");
                s.dex = dm.group(1);
                Matcher cm = Pattern.compile("components kept=(\\d+)/(\\d+)").matcher(l);
                s.components = cm.find() ? cm.group(1) + "/" + cm.group(2) : null;
            }
        }
        s.complete = "PIPELINE_OK".equals(s.pipelineOk);
        s.verdictLines = lines; // كل سطور السجل الخام — لصيد سطور الأحكام
        return s;
    }

    /** مجموع سطور RESULT — بادئة اسم المجموعة تُطوَى (درس W38). */
    static long[] resultSum(List<String> resultLines) {
        long p = 0, f = 0;
        for (String rl : resultLines) {
            Matcher m = Pattern.compile("RESULT (\\d+) passed, (\\d+) failed").matcher(rl);
            if (!m.find()) refuse("log.result_line");
            p += Long.parseLong(m.group(1)); f += Long.parseLong(m.group(2));
        }
        return new long[]{p, f};
    }

    // ---- قوانين التدقيق (كل واحدة ترفض بوسمها) --------------------------------

    static Map<String, String> envMap(Path gates) throws IOException {
        Map<String, String> env = new LinkedHashMap<String, String>();
        for (String raw : Files.readAllLines(gates, StandardCharsets.UTF_8)) {
            String l = raw.trim();
            int i = l.indexOf('=');
            if (i > 0) env.put(l.substring(0, i), l.substring(i + 1));
        }
        return env;
    }

    /** قيمة حقل key= من سطر حكم (بين "key=" وأول مسافة تالية). */
    static String field(String line, String key) {
        Matcher m = Pattern.compile("[ ]" + Pattern.quote(key) + "=([^ ]+)").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** أول سطر في السجل يبدأ بالبادئة المعطاة، أو null. */
    static String verdictLine(LogShape s, String prefix) {
        for (String l : s.verdictLines) if (l.startsWith(prefix + " ")) return l;
        return null;
    }

    /** الطبقة 1: سلامة الأعضاء — المعلَن حاضر ومطابق، والحاضر معلَن. */
    static void lawMembers(Path archiveDir) throws IOException {
        Map<String, String> sums = readSums(archiveDir.resolve(SUMS_NAME));
        Set<String> present = new LinkedHashSet<String>();
        try (java.util.stream.Stream<Path> st = Files.list(archiveDir)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                String n = p.getFileName().toString();
                if (!Files.isRegularFile(p) || n.equals(SUMS_NAME)) continue;
                present.add(n);
            }
        }
        for (String n : present) if (!sums.containsKey(n)) refuse("member.undeclared " + n);
        for (Map.Entry<String, String> e : sums.entrySet()) {
            Path f = archiveDir.resolve(e.getKey());
            if (!Files.isRegularFile(f)) refuse("member.missing " + e.getKey());
            if (!sha256(f).equals(e.getValue())) refuse("member.sha " + e.getKey());
        }
        for (String r : REQUIRED_MEMBERS) if (!sums.containsKey(r)) refuse("required.missing " + r);
    }

    /** الطبقة 2: شكل السجل — صفر إخفاق، أرقام متسقة، سقف المخفَّض. */
    static void lawLogShape(LogShape s) {
        if (!s.fails.isEmpty()) refuse("log.stage_fail " + s.fails.get(0));
        if ("PIPELINE_FAILED".equals(s.pipelineOk)) refuse("log.pipeline_failed");
        if (s.resultLines.isEmpty()) refuse("log.result_missing");
        long[] sum = resultSum(s.resultLines);
        if (sum[1] != 0) refuse("log.tests_failed");
        if (s.complete) {
            if (s.testsPassed == null || s.testsFailed == null) refuse("log.tests_line");
            if (!String.valueOf(sum[0]).equals(s.testsPassed) || !String.valueOf(sum[1]).equals(s.testsFailed))
                refuse("log.result_sum");
        }
        if (s.dex != null && Long.parseLong(s.dex) > Long.parseLong(DEX_CEILING)) refuse("log.ceiling");
    }

    /** كتلة الملخص == صفوف الحكم (الشكل المكتمل فقط — الغائب لا يُطارد). */
    static void lawSummaryDrift(LogShape s) {
        if (!s.complete) return;
        List<String> fromVerdicts = new ArrayList<String>();
        List<String> fromSummary = new ArrayList<String>();
        for (String r : s.stageRows) {
            if (r.startsWith("~")) fromSummary.add(r.substring(1));
            else fromVerdicts.add(r);
        }
        Collections.sort(fromVerdicts);
        Collections.sort(fromSummary);
        if (!fromVerdicts.equals(fromSummary)) refuse("log.summary_drift");
    }

    /** الطبقة 3: كل سطر حكم معروف == مفاتيحه في الدفتر حرفياً. */
    static void lawEnvMatch(LogShape s, Map<String, String> env) {
        for (String[] t : ENV_TABLE) {
            String line = verdictLine(s, t[0]);
            if (line == null) continue;
            String want = field(line, t[1]);
            if (want == null) refuse("env.field_missing " + t[0] + "." + t[1]);
            String got = env.get(t[2]);
            if (got == null) refuse("env.key_missing " + t[2]);
            // dex في السطر يحمل "D/سقف" والمفتاح الرقم الصرف — قارن الجزء الأول
            String wantCmp = t[1].equals("dex") ? want.substring(0, want.indexOf('/')) : want;
            if (!wantCmp.equals(got)) refuse("env." + t[2].toLowerCase() + "_mismatch");
        }
        // R8: dex ومكوّنات المانيفست
        if (s.dex != null) {
            String rd = env.get("R8_DEX_BYTES");
            if (rd == null) refuse("env.key_missing R8_DEX_BYTES");
            if (!s.dex.equals(rd)) refuse("env.r8_dex_bytes_mismatch");
            if (s.components != null) {
                String rc = env.get("R8_COMPONENTS_KEPT");
                if (rc != null && !s.components.equals(rc)) refuse("env.r8_components_mismatch");
            }
        }
        // اختبارات collect_gates == مجموع RESULT
        String tt = env.get("TESTS_TOTAL"), tf = env.get("TESTS_FAILED");
        if (tt != null && tf != null) {
            long[] sum = resultSum(s.resultLines);
            if (!String.valueOf(sum[0]).equals(tt) || !String.valueOf(sum[1]).equals(tf))
                refuse("env.tests_total_mismatch");
        }
    }

    /** الطبقة 4أ: إجماع الهوية من كل المصادر الحاضرة — والمرجع المنشور فوقه. */
    static String[] lawIdentity(LogShape s, Map<String, String> env, Path manifest,
                                String expectVc, String expectVn) throws IOException {
        Set<String> vcs = new LinkedHashSet<String>();
        Set<String> vns = new LinkedHashSet<String>();
        String[] idLines = {"BILLINGGATE_OK", "DEPLOYSRVGATE_OK", "PREFLIGHTGATE_OK", "REBUILDGATE_OK", "OPVERIFYGATE_OK", "PHASE8_OK"};
        for (String p : idLines) {
            String line = verdictLine(s, p);
            if (line == null) continue;
            String vc = field(line, "vc"), vn = field(line, "vn");
            if (vc != null) vcs.add(vc);
            if (vn != null) vns.add(vn);
        }
        String[][] idKeys = {{"BILLING_VC", "BILLING_VN"}, {"DEPLOYSRV_VC", "DEPLOYSRV_VN"},
                {"PREFLIGHT_VC", "PREFLIGHT_VN"}, {"REBUILD_VC", "REBUILD_VN"}, {"PHASE7_VC", null},
                {"OPVERIFY_VC", "OPVERIFY_VN"}, {"PHASE8_VC", null}};
        for (String[] k : idKeys) {
            if (env.get(k[0]) != null) vcs.add(env.get(k[0]));
            if (k[1] != null && env.get(k[1]) != null) vns.add(env.get(k[1]));
        }
        // المانيفست: كل ورود versionCode/versionName يوافق الإجماع
        String mj = new String(read(manifest), StandardCharsets.UTF_8);
        Matcher vm = Pattern.compile("\"versionCode\": (\\d+)").matcher(mj);
        while (vm.find()) vcs.add(vm.group(1));
        Matcher nm = Pattern.compile("\"versionName\": \"([0-9][^\"]*)\"").matcher(mj);
        while (nm.find()) vns.add(nm.group(1));
        if (vcs.size() > 1 || vns.size() > 1) refuse("identity.mismatch");
        if (vcs.isEmpty() || vns.isEmpty()) refuse("identity.absent");
        String vc = vcs.iterator().next(), vn = vns.iterator().next();
        if (expectVc != null && !expectVc.equals(vc)) refuse("identity.expect");
        if (expectVn != null && !expectVn.equals(vn)) refuse("identity.expect");
        return new String[]{vc, vn};
    }

    /** الطبقة 4ب: قانون الإثبات السلبي المحفوظ — الإلزام لا النصيحة. */
    static int lawProofs(LogShape s, Map<String, String> sums, Path archiveDir) throws IOException {
        for (String[] t : PROOF_TABLE) {
            if (verdictLine(s, t[0]) == null) continue;
            if (!sums.containsKey(t[1])) refuse("proof.missing " + t[1]);
        }
        int proofs = 0;
        for (String n : sums.keySet()) {
            if (!n.endsWith("-negative-proof.log")) continue;
            String body = new String(read(archiveDir.resolve(n)), StandardCharsets.UTF_8);
            if (!PROOF_EVIDENCE.matcher(body).find()) refuse("proof.not_passed " + n);
            proofs++;
        }
        return proofs;
    }

    /** التدقيق الكامل — يرفض بأول قانون ينكسر، ويعيد حقول الحكم. */
    static String[] audit(Path archiveDir, String expectVc, String expectVn) throws IOException {
        if (!Files.isDirectory(archiveDir)) refuse("archive.missing");
        lawMembers(archiveDir);
        Map<String, String> sums = readSums(archiveDir.resolve(SUMS_NAME));
        Path logFile = archiveDir.resolve("pipeline.log");
        LogShape s = parseLog(logFile);
        lawLogShape(s);
        lawSummaryDrift(s);
        Map<String, String> env = envMap(archiveDir.resolve("gates.env"));
        lawEnvMatch(s, env);
        String[] id = lawIdentity(s, env, archiveDir.resolve("release-manifest.json"), expectVc, expectVn);
        int proofs = lawProofs(s, sums, archiveDir);
        long[] sum = resultSum(s.resultLines);
        String dex = s.dex == null ? "0" : s.dex;
        String line = "EVAUDIT_OK members=" + sums.size() + " proofs=" + proofs
                + " shape=" + (s.complete ? "complete" : "mid")
                + " vc=" + id[0] + " vn=" + id[1] + " tests=" + sum[0] + "/" + sum[1]
                + " dex=" + dex + "/" + DEX_CEILING;
        return new String[]{line, String.valueOf(sums.size()), String.valueOf(proofs),
                s.complete ? "complete" : "mid", id[0], id[1], sum[0] + "/" + sum[1], dex};
    }

    // ---- المدخل: الأرشيف الحي ثم السلبيات الاثنتا عشرة ثم إعادة الأخضر -------

    public static void main(String[] args) throws Exception {
        if (args.length >= 5 && "pack".equals(args[0])) {
            System.out.println(pack(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4])));
            return;
        }
        if (args.length >= 2 && "check".equals(args[0])) {
            runGate(Paths.get(args[1]), arg(args, "--expect-vc"), arg(args, "--expect-vn"));
            return;
        }
        System.err.println("usage: EvAudit pack <archiveDir> <pipelineLog> <releaseDir> <ciDir>"
                + " | check <archiveDir> [--expect-vc N] [--expect-vn X]");
        System.exit(2);
    }

    static void runGate(Path archive, String expectVc, String expectVn) throws Exception {
        // ---- 1: التدقيق الحي — أربع طبقات على الأرشيف الحقيقي -----------------
        Object aTag = invoke(() -> { return audit(archive, expectVc, expectVn); });
        String[] a = (aTag instanceof String[]) ? (String[]) aTag : null;
        ck("audit.real_inputs", a != null, "the live archive audits green" + (a == null ? " [" + aTag + "]" : ""));
        if (a != null) {
            ck("audit.members_intact", Integer.parseInt(a[1]) > 0, "every member sha256 == SHA256SUMS, none undeclared (" + a[1] + " members)");
            ck("audit.proofs_passed", Integer.parseInt(a[2]) >= 1, "every archived negative-proof log ends PROOF PASSED (" + a[2] + ")");
            ck("audit.tests_zero_failed", a[6].endsWith("/0"), "the archived test mass carries zero failures: " + a[6]);
            ck("audit.dex_under_ceiling", Long.parseLong(a[7]) <= Long.parseLong(DEX_CEILING), "archived dex " + a[7] + " <= " + DEX_CEILING);
            ck("audit.identity_matches_week", expectVc == null || expectVc.equals(a[4]),
                    "the identity consensus == the week's published identity (vc=" + a[4] + " vn=" + a[5] + ")");
        }
        final String fVc = expectVc, fVn = expectVn;

        // ---- 2: السلبيات الاثنتا عشرة — كلٌّ بوسمها على نسخة متسخة ------------
        Path tmp = Files.createTempDirectory("evaudit-neg");
        neg("member.byte_flip", () -> {
            Path d = copyArchive(tmp, "a1", archive);
            byte[] b = read(d.resolve("gates.env"));
            b[0] = (byte) (b[0] ^ 0x01);
            Files.write(d.resolve("gates.env"), b);
            lawMembers(d);
        }, "member.sha gates.env");

        neg("member.dropped", () -> {
            Path d = copyArchive(tmp, "a2", archive);
            Files.delete(d.resolve("rollout-plan.txt"));
            lawMembers(d);
        }, "member.missing rollout-plan.txt");

        neg("member.undeclared", () -> {
            Path d = copyArchive(tmp, "a3", archive);
            write(d.resolve("planted.txt"), "planted");
            lawMembers(d);
        }, "member.undeclared planted.txt");

        neg("sums.format_corrupted", () -> {
            Path d = copyArchive(tmp, "a4", archive);
            String sums = new String(read(d.resolve(SUMS_NAME)), StandardCharsets.UTF_8);
            write(d.resolve(SUMS_NAME), sums.replaceFirst("[0-9a-f]{64}", "0123abcd"));
            readSums(d.resolve(SUMS_NAME));
        }, "sums.format");

        neg("log.billing_checks_forged", () -> {
            Path d = copyArchive(tmp, "a5", archive);
            forgeField(d.resolve("pipeline.log"), "BILLINGGATE_OK", "checks", "999");
            lawEnvMatch(parseLog(d.resolve("pipeline.log")), envMap(d.resolve("gates.env")));
        }, "env.billing_checks_mismatch");

        neg("identity.billing_vc_forged_consistent", () -> {
            // تزوير متسق: السطر + الدفتر معاً وإعادة ختم المانيفست كله —
            // إجماع الهوية من باقي المصادر (rebuild/phase7/manifest) هو الذي يعضّ
            Path d = copyArchive(tmp, "a6", archive);
            forgeField(d.resolve("pipeline.log"), "BILLINGGATE_OK", "vc", "79");
            String env = new String(read(d.resolve("gates.env")), StandardCharsets.UTF_8)
                    .replaceAll("BILLING_VC=\\d+", "BILLING_VC=79");
            write(d.resolve("gates.env"), env);
            restampAll(d);
            lawIdentity(parseLog(d.resolve("pipeline.log")), envMap(d.resolve("gates.env")), d.resolve("release-manifest.json"), fVc, fVn);
        }, "identity.mismatch");

        neg("proof.passed_erased", () -> {
            // ملف الإثبات مُفرَّغ بالكامل + إعادة الختم: لا سطر نجاح ولا سطر
            // عضّ — قانون الإثبات يرفض (المفرَّغ ليس دليلاً — درس W38)
            Path d = copyArchive(tmp, "a7", archive);
            write(d.resolve("billing-negative-proof.log"), "");
            restampAll(d);
            Map<String, String> sums = readSums(d.resolve(SUMS_NAME));
            lawProofs(parseLog(d.resolve("pipeline.log")), sums, d);
        }, "proof.not_passed billing-negative-proof.log");

        neg("proof.member_dropped", () -> {
            Path d = copyArchive(tmp, "a8", archive);
            Files.delete(d.resolve("phase7-negative-proof.log"));
            restampAll(d);
            Map<String, String> sums = readSums(d.resolve(SUMS_NAME));
            lawProofs(parseLog(d.resolve("pipeline.log")), sums, d);
        }, "proof.missing phase7-negative-proof.log");

        neg("log.tests_failure_forged", () -> {
            Path d = copyArchive(tmp, "a9", archive);
            rewriteFirstLine(d.resolve("pipeline.log"), "RESULT ", "RESULT 0 passed, 1 failed");
            lawLogShape(parseLog(d.resolve("pipeline.log")));
        }, "log.tests_failed");

        neg("log.stage_fail_forged", () -> {
            Path d = copyArchive(tmp, "a10", archive);
            rewriteFirstLine(d.resolve("pipeline.log"), "[probe] PASS", "[probe] FAIL (rc=1)");
            lawLogShape(parseLog(d.resolve("pipeline.log")));
        }, "log.stage_fail probe");

        neg("identity.expect_wrong_week", () -> {
            audit(archive, "79", null); // أي هوية غير هوية هذا الأسبوع
        }, "identity.expect");

        neg("log.ceiling_overflow_consistent", () -> {
            // تزوير متسق: سطر R8 + المفتاح معاً وإعادة الختم — السقف المخفَّض يعضّ
            Path d = copyArchive(tmp, "a11", archive);
            forgeField(d.resolve("pipeline.log"), "R8_OK", "dex", "999999");
            String env = new String(read(d.resolve("gates.env")), StandardCharsets.UTF_8)
                    .replaceAll("R8_DEX_BYTES=\\d+", "R8_DEX_BYTES=999999");
            write(d.resolve("gates.env"), env);
            restampAll(d);
            lawLogShape(parseLog(d.resolve("pipeline.log")));
        }, "log.ceiling");

        // ---- 3: إعادة الأخضر على الأرشيف الحي بعد كل سلبة ---------------------
        Object heal = invoke(() -> { audit(archive, fVc, fVn); return null; });
        ck("heal.real_archive_green", heal == null, "the real archive re-verifies green after all negatives" + (heal == null ? "" : " [" + heal + "]"));

        // ---- الحكم --------------------------------------------------------------
        if (reg > 0) {
            System.out.println("EVAUDITGATE_FAILED regressions=" + reg + "/" + checks + " (see EVAUDIT regression lines above)");
            System.exit(1);
        }
        if (a == null) { System.out.println("EVAUDITGATE_FAILED audit"); System.exit(1); }
        // سطر الحكم بلا شكل التشغيل (mid/complete) — يبقى متطابقاً بايت-بايت
        // بين تشغيل الأنبوب وإعادة التشغيل المستقلة بعده (قانون تساوي السجل)
        System.out.println("EVAUDITGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " members=" + a[1] + " proofs=" + a[2]
                + " vc=" + a[4] + " vn=" + a[5] + " tests=" + a[6] + " dex=" + a[7]);
    }

    // ---- أدوات صغيرة -----------------------------------------------------------

    static Path copyArchive(Path tmp, String name, Path src) throws IOException {
        Path d = tmp.resolve(name);
        Files.createDirectories(d);
        try (java.util.stream.Stream<Path> st = Files.list(src)) {
            for (Path p : (Iterable<Path>) st::iterator)
                if (Files.isRegularFile(p))
                    Files.copy(p, d.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
        return d;
    }

    /** يزوّر حقل واحد في أول سطر يبدأ بالبادئة — بقية السطر سليمة (أداة
     *  تزوير السلبيات: المهاجم الدقيق لا يهدم السطر بل يبدّل قيمة). */
    static void forgeField(Path file, String linePrefix, String fieldKey, String newValue) throws IOException {
        String s = new String(read(file), StandardCharsets.UTF_8);
        int start = s.startsWith(linePrefix) ? 0 : s.indexOf("\n" + linePrefix) + 1;
        if (start <= 0 && !s.startsWith(linePrefix)) refuse("attack.line_missing " + linePrefix);
        int e = s.indexOf('\n', start);
        if (e < 0) e = s.length();
        String line = s.substring(start, e);
        Pattern p = Pattern.compile(Pattern.quote(fieldKey + "=") + "[^ ]+");
        Matcher m = p.matcher(line);
        if (!m.find()) refuse("attack.field_missing " + linePrefix + "." + fieldKey);
        line = line.substring(0, m.start()) + fieldKey + "=" + newValue + line.substring(m.end());
        write(file, s.substring(0, start) + line + s.substring(e));
    }

    /** يستبدل أول ورود لاحقة النمط حتى نهاية سطرها (للأهداف المستقلة نفسها). */
    static void rewriteFirstLine(Path file, String anchor, String replacement) throws IOException {
        String s = new String(read(file), StandardCharsets.UTF_8);
        int i = s.indexOf(anchor);
        if (i < 0) refuse("attack.anchor_missing " + anchor);
        int e = s.indexOf('\n', i);
        if (e < 0) e = s.length();
        write(file, s.substring(0, i) + replacement + s.substring(e));
    }

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
}
