package com.hark.neokey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * RebuildDrillTest — اختبارات وحدة بوابة «الإثبات من لا شيء» (الأسبوع 38).
 *
 * مواضع RebuildGate النقية تُختبر هنا على ملفات مؤقتة: قراءة مراسيات
 * سلسلة الأدوات وتحليل سجل التشغيل الداخلي وتحليل سطر حفرية الحكم
 * والتقاطع بينهما — إيجابيات على أشكال حقيقية، وسلبيات مصممة تعضّ كلٌّ
 * بوسمها الحرفي (رفضٌ حرس ميت = إخفاق). RESULT يُحصى مرة واحدة في مرحلة
 * الاختبارات.
 */
public class RebuildDrillTest {

    static int passed = 0, failed = 0, total = 0;

    static void ok(String name, boolean cond, String desc) {
        total++;
        if (cond) { passed++; System.out.println("ok " + name + " — " + desc); }
        else { failed++; System.out.println("FAIL " + name + " — " + desc); }
    }

    static String expectTag(RebuildGate.Thunk t) {
        try { t.run(); return null; }
        catch (RebuildGate.Refused r) { return r.tag; }
        catch (Exception e) { return "exception:" + e.getClass().getSimpleName(); }
    }

    static Path tmpDir() throws IOException {
        return Files.createTempDirectory("rebuild-test");
    }

    static String sevenPins() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            StringBuilder sha = new StringBuilder();
            for (int j = 0; j < 64; j++) sha.append((char) ('a' + ((i * 7 + j) % 6)));
            sb.append(RebuildGate.TOOL_NAMES[i]).append(' ').append(sha).append('\n');
        }
        return sb.toString();
    }

    /** سجل داخلي تركيبي مطابق لشكل الأنبوب الحرفي — 38 صف PASS (الأنبوب
     *  نمى إلى 39 مرحلة خارجية، والحفريات تُسقط نفسها داخلها — درس W39). */
    static String syntheticInnerLog(int rows, String testsF, String dex, String comps, boolean pipelineOk, String vcLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("some earlier log line\n");
        String[] all = {"probe","audit","tests","perf","playstore","correction","nnet","dialect","assistant",
                "sync","syncclient","rest","tls","deploy","ksdrill","provision","paygate","themestore","nqfport",
                "community","dictsign","storeui","entitlement","build","r8","trim","signedapk","phase3","phase4",
                "phase5","release","uploadbundle","phase6","billing","deploysrv","preflight","phase7","evaudit"};
        for (int i = 0; i < rows && i < all.length; i++) sb.append("[").append(all[i]).append("] PASS (1s)\n");
        sb.append("R8_OK dex=").append(dex).append(" bytes, mapping=210 program classes, manifest components kept=").append(comps).append("\n");
        sb.append("RESULT 1479 passed, ").append(testsF).append(" failed\n");
        sb.append("BILLINGGATE_OK checks=24 negatives=12/12 pins=7/7 stages=38/38 tests=1479/").append(testsF)
          .append(" dex=").append(dex).append("/582000 ").append(vcLine).append("\n");
        sb.append("════════════════ PIPELINE SUMMARY ════════════════\n");
        String[] names = {"probe","audit","tests","perf","playstore","correction","nnet","dialect","assistant",
                "sync","syncclient","rest","tls","deploy","ksdrill","provision","paygate","themestore","nqfport",
                "community","dictsign","storeui","entitlement","build","r8","trim","signedapk","phase3","phase4",
                "phase5","release","uploadbundle","phase6","billing","deploysrv","preflight","phase7","evaudit"};
        for (int i = 0; i < rows && i < names.length; i++)
            sb.append("  ").append(names[i]).append("   PASS\n");
        sb.append("  tests: 1479 passed, ").append(testsF).append(" failed\n");
        sb.append("═══════════════════════════════════════════════════\n");
        if (pipelineOk) sb.append("PIPELINE_OK\n");
        return sb.toString();
    }

    static String drillVerdict(String tests, String dex, String vc, String vn) {
        return "REBUILD_DRILL_OK pins=7 stages=" + RebuildGate.INNER_STAGES + " tests=" + tests + " dex=" + dex + " inner_vc=" + vc + " inner_vn=" + vn + "\n";
    }

    public static void main(String[] args) throws Exception {
        // شكل الأنبوب يتحرك مع نموه: الداخل 38 والمراحل التي بعد الحفريات
        // تسقط من الطرفين عند مقارنة الشكل (القائمة مشتقة في البوابة الحقيقية)
        RebuildGate.AFTER_STAGES = "evaudit";
        Path tmp = tmpDir();

        // ---- المراسيات ------------------------------------------------------
        Path pinsGood = tmp.resolve("pins-good.txt");
        Files.write(pinsGood, sevenPins().getBytes(StandardCharsets.UTF_8));
        Map<String, String> pins = RebuildGate.readPins(pinsGood.toString());
        ok("pos.pins.read", pins.size() == 7 && "r8.jar".equals(RebuildGate.TOOL_NAMES[0]),
                "seven pins read in the fixed order");

        Path pinsBadHex = tmp.resolve("pins-badhex.txt");
        Files.write(pinsBadHex, sevenPins().replaceFirst("[0-9a-f]{64}", "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF").getBytes(StandardCharsets.UTF_8));
        ok("neg.pins.uppercase", "pins.format".equals(expectTag(() -> RebuildGate.readPins(pinsBadHex.toString()))),
                "an uppercase pin refuses pins.format");

        Path pinsDup = tmp.resolve("pins-dup.txt");
        StringBuilder shaDup = new StringBuilder();
        for (int j = 0; j < 64; j++) shaDup.append('a');
        Files.write(pinsDup, (sevenPins() + "r8.jar " + shaDup + "\n").getBytes(StandardCharsets.UTF_8));
        ok("neg.pins.duplicate", "pins.duplicate".equals(expectTag(() -> RebuildGate.readPins(pinsDup.toString()))),
                "a duplicated tool refuses pins.duplicate");

        Path pinsShort = tmp.resolve("pins-short.txt");
        StringBuilder sb6 = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            StringBuilder sha = new StringBuilder();
            for (int j = 0; j < 64; j++) sha.append('a');
            sb6.append(RebuildGate.TOOL_NAMES[i]).append(' ').append(sha).append('\n');
        }
        Files.write(pinsShort, sb6.toString().getBytes(StandardCharsets.UTF_8));
        ok("neg.pins.inventory_short", "pins.inventory".equals(expectTag(() -> { Map<String, String> p = RebuildGate.readPins(pinsShort.toString()); RebuildGate.validatePinInventory(p); })),
                "six pins refuse pins.inventory");

        Path pinsExtra = tmp.resolve("pins-extra.txt");
        StringBuilder shaA = new StringBuilder();
        for (int j = 0; j < 64; j++) shaA.append('a');
        Files.write(pinsExtra, (sevenPins() + "rogue.jar " + shaA + "\n").getBytes(StandardCharsets.UTF_8));
        ok("neg.pins.inventory_extra", "pins.inventory".equals(expectTag(() -> { Map<String, String> p = RebuildGate.readPins(pinsExtra.toString()); RebuildGate.validatePinInventory(p); })),
                "an eighth tool refuses pins.inventory");

        ok("pos.sha256.empty", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(
                RebuildGate.sha256(write(tmp.resolve("empty.bin"), new byte[0]))),
                "sha256 of the empty input is the published digest");

        // ---- السجل الداخلي ---------------------------------------------------
        Path logGood = tmp.resolve("inner-good.log");
        Files.write(logGood, syntheticInnerLog(38, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        RebuildGate.InnerRun in = RebuildGate.parseInnerLog(logGood.toString());
        ok("pos.inner.parse", in.stageRows == 38 && "1479".equals(in.testsPassed)
                && "0".equals(in.testsFailed) && "505636".equals(in.dex),
                "the synthetic inner run parses: 38 stages, 1479/0, dex 505636");

        Path logFail = tmp.resolve("inner-fail.log");
        Files.write(logFail, syntheticInnerLog(38, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN)
                .replaceFirst("  phase7\\s+PASS", "  phase7   FAIL (rc=1)").getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.stage_fail", "inner.stage_fail".equals(expectTag(() -> RebuildGate.parseInnerLog(logFail.toString()))),
                "a FAIL row refuses inner.stage_fail");

        // شكل المحيط لحظة الحفرية: 37 صف PASS (المراحل قبلها فقط — evaudit
        // لم يحِن بعد) + صف الحفريات نفسه (يُسقط عند المقارنة مع ما بعدها)
        Path outerShape = tmp.resolve("outer-shape.log");
        Files.write(outerShape, ("[rebuild] PASS (9s)\n" + syntheticInnerLog(37, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN)).getBytes(StandardCharsets.UTF_8));
        ok("pos.summary.profile", expectTag(() -> RebuildGate.crossCheckSummary(logGood.toString(), outerShape.toString(), "rebuild")) == null,
                "the inner rows equal the outer rows minus the drill's own row");

        Path outerSkip = tmp.resolve("outer-skip.log");
        Files.write(outerSkip, syntheticInnerLog(37, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN)
                .replaceFirst("\\[build\\] PASS \\(1s\\)", "[build] SKIP (no Android SDK in this environment)").getBytes(StandardCharsets.UTF_8));
        Path innerSkip2 = tmp.resolve("inner-skip2.log");
        Files.write(innerSkip2, syntheticInnerLog(38, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN)
                .replaceFirst("\\[build\\] PASS \\(1s\\)", "[build] SKIP (no Android SDK in this environment)").getBytes(StandardCharsets.UTF_8));
        ok("pos.summary.skip_profile", expectTag(() -> RebuildGate.crossCheckSummary(innerSkip2.toString(), outerSkip.toString(), "rebuild")) == null,
                "the documented build SKIP reproduces on both sides — profile equality holds");

        Path innerSkip3 = tmp.resolve("inner-skip3.log");
        Files.write(innerSkip3, syntheticInnerLog(38, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN)
                .replaceFirst("\\[probe\\] PASS \\(1s\\)", "[probe] SKIP (tampered)").getBytes(StandardCharsets.UTF_8));
        ok("neg.summary.profile_drift", "xref.summary".equals(expectTag(() -> RebuildGate.crossCheckSummary(innerSkip3.toString(), outerShape.toString(), "rebuild"))),
                "a SKIP the outer never had refuses xref.summary");

        Path logCount = tmp.resolve("inner-count.log");
        Files.write(logCount, syntheticInnerLog(36, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.count", "inner.count".equals(expectTag(() -> RebuildGate.parseInnerLog(logCount.toString()))),
                "36 stage rows refuse inner.count");

        Path logNoOk = tmp.resolve("inner-nook.log");
        Files.write(logNoOk, syntheticInnerLog(38, "0", "505636", "10/10", false, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.pipeline_ok_missing", "inner.pipeline_ok_missing".equals(expectTag(() -> RebuildGate.parseInnerLog(logNoOk.toString()))),
                "no closing verdict refuses inner.pipeline_ok_missing");

        Path logBad = tmp.resolve("inner-bad.log");
        Files.write(logBad, syntheticInnerLog(38, "0", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).replace("PIPELINE_OK", "PIPELINE_FAILED").getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.pipeline_failed", "inner.pipeline_failed".equals(expectTag(() -> RebuildGate.parseInnerLog(logBad.toString()))),
                "PIPELINE_FAILED refuses inner.pipeline_failed");

        Path logTests = tmp.resolve("inner-tests.log");
        Files.write(logTests, syntheticInnerLog(38, "1", "505636", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.tests_failed", "inner.tests_failed".equals(expectTag(() -> RebuildGate.parseInnerLog(logTests.toString()))),
                "one forged test failure refuses inner.tests_failed");

        Path logCeil = tmp.resolve("inner-ceil.log");
        Files.write(logCeil, syntheticInnerLog(38, "0", "999999", "10/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.ceiling", "inner.ceiling".equals(expectTag(() -> RebuildGate.parseInnerLog(logCeil.toString()))),
                "dex over the lowered ceiling refuses inner.ceiling");

        Path logComp = tmp.resolve("inner-comp.log");
        Files.write(logComp, syntheticInnerLog(38, "0", "505636", "9/10", true, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.inner.components", "inner.components".equals(expectTag(() -> RebuildGate.parseInnerLog(logComp.toString()))),
                "a dropped manifest component refuses inner.components");

        // ---- سطر حفرية الحكم والتقاطع ---------------------------------------
        Path vGood = tmp.resolve("verdict-good.txt");
        Files.write(vGood, drillVerdict("1479/0", "505636", SyncServerPack.VC, SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        Map<String, String> v = RebuildGate.parseDrillVerdict(vGood.toString());
        ok("pos.verdict.parse", "7".equals(v.get("pins")) && String.valueOf(RebuildGate.INNER_STAGES).equals(v.get("stages"))
                && "1479/0".equals(v.get("tests")) && "505636".equals(v.get("dex")),
                "the drill verdict parses with every field");

        Path vNoField = tmp.resolve("verdict-nofield.txt");
        Files.write(vNoField, ("REBUILD_DRILL_OK pins=7 stages=" + RebuildGate.INNER_STAGES + " tests=1479/0 dex=505636 inner_vc=" + SyncServerPack.VC + "\n").getBytes(StandardCharsets.UTF_8));
        ok("neg.verdict.missing_field", "verdict.shape".equals(expectTag(() -> RebuildGate.parseDrillVerdict(vNoField.toString()))),
                "a dropped field refuses verdict.shape");

        Path vNotOk = tmp.resolve("verdict-notok.txt");
        Files.write(vNotOk, "REBUILD_DRILL_FAILED inner_pipeline\n".getBytes(StandardCharsets.UTF_8));
        ok("neg.verdict.not_ok", "verdict.shape".equals(expectTag(() -> RebuildGate.parseDrillVerdict(vNotOk.toString()))),
                "a FAILED line refuses verdict.shape");

        String xTag = expectTag(() -> RebuildGate.crossCheck(v, in, "7"));
        ok("pos.xref.agree", xTag == null, "verdict and inner log agree on every pinned field");
        ok("neg.xref.pins", "xref.pins".equals(expectTag(() -> RebuildGate.crossCheck(v, in, "6"))),
                "a pins-count disagreement refuses xref.pins");

        Path vDex = tmp.resolve("verdict-dex.txt");
        Files.write(vDex, drillVerdict("1479/0", "123456", SyncServerPack.VC, SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        ok("neg.xref.dex", "xref.dex".equals(expectTag(() -> { Map<String, String> vv = RebuildGate.parseDrillVerdict(vDex.toString()); RebuildGate.crossCheck(vv, in, "7"); })),
                "a forged dex in the verdict refuses xref.dex");

        Path vIdent = tmp.resolve("verdict-ident.txt");
        Files.write(vIdent, drillVerdict("1479/0", "505636", "78", "3.1").getBytes(StandardCharsets.UTF_8));
        ok("neg.xref.identity", "xref.identity".equals(expectTag(() -> { Map<String, String> vv = RebuildGate.parseDrillVerdict(vIdent.toString()); RebuildGate.crossCheck(vv, in, "7"); })),
                "a stale identity in the verdict refuses xref.identity (the verdict == the inner run identity)");

        // ---- الحصيلة ----------------------------------------------------------
        System.out.println("RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Path write(Path p, byte[] b) throws IOException {
        Files.write(p, b);
        return p;
    }
}
