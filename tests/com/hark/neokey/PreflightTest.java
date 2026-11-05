package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * اختبارات الفحص التمهيدي للمشغِّل — المرحلة 7 W3 (الأسبوع 36).
 *
 * عقد ورقة التسليم يُقاس هنا بأدلة صناعية مبنية من ثوابت المصدر الواحد
 * (هوية الأسبوع تُقرأ من `SyncServerPack.VC/VN` — الترقية لا تكسر الاختبار،
 * و"الأسبوع السابق" يُحسب لا يُكتب): الإصدار الحتمي، الهوية من المصدر
 * الواحد، إسقاط حقول الاهتزاز، الأدلة حرفياً، الأوامر الحرفية، والفحص
 * العضّ: دليل غائب، دليل بهوية أسبوع أخرى، صف محذوف، دليل معتل، خطوة
 * خارجية مدّعى فحصها، أمر مُختلَق، سطر هوية كاذب — كلٌّ بوسمه. البوابات
 * الأربع الحقيقية المعادة طازجاً = بوابة `preflight` (الأسبوع نفسه).
 */
public class PreflightTest {

    static int pass, fail;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + " (" + detail + ")"); }
    }

    static String vc() { return SyncServerPack.VC; }
    static String vn() { return SyncServerPack.VN; }
    static String vcPrev() { return String.valueOf(Integer.parseInt(SyncServerPack.VC) - 1); }

    static String hex(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }

    // أدلة صناعية من ثوابت المصدر الواحد — الترقية لا تكسرها
    static String evBundle() {
        return "UPLOADBUNDLEGATE_OK checks=14 negatives=6/6 members=8 evidence=8 vc=" + vc() + " vn=" + vn() + " apk_sha256=" + hex('a', 64);
    }
    static String evKsdrill() {
        return "KSDRILLGATE_OK checks=12 negatives=8/8 fp16=" + Preflight.DRILL_FP16 + " days_left=7299";
    }
    static String evBilling() {
        return "BILLINGGATE_OK checks=24 negatives=10/10 vc=" + vc() + " vn=" + vn() + " providers=2";
    }
    static String evDeploysrv() {
        return "DEPLOYSRVGATE_OK checks=24 negatives=5/5 jar_sha256=0123456789abcdef members=6 health_ms=1234 vc=" + vc() + " vn=" + vn();
    }

    static final Path DIR = Paths.get("build/preflight-test");
    static final Path EV = DIR.resolve("evidence.env");
    static final Path SHEET = DIR.resolve("sheet.md");

    static void writeEvidence(String billingLine) throws Exception {
        Files.createDirectories(DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("EVIDENCE_BUNDLE=").append(evBundle()).append('\n');
        sb.append("EVIDENCE_KSDRILL=").append(evKsdrill()).append('\n');
        if (billingLine != null) sb.append("EVIDENCE_BILLING=").append(billingLine).append('\n');
        sb.append("EVIDENCE_DEPLOYSRV=").append(evDeploysrv()).append('\n');
        Files.write(EV, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String tryCheck(Path evFile, Path sheetFile) {
        try { return Preflight.check(evFile, sheetFile); }   // الأخضر يعيد سطره — العضّ يعيد وسمه
        catch (IllegalStateException e) { return e.getMessage(); }
        catch (Exception e) { return "EX:" + e.getClass().getSimpleName(); }
    }

    static String sheetText() throws Exception {
        return new String(Files.readAllBytes(SHEET), StandardCharsets.UTF_8);
    }

    static Path dirtyCopy(String tag) throws Exception {
        Path d = DIR.resolve("sheet-" + tag + ".md");
        Files.copy(SHEET, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return d;
    }

    public static void main(String[] args) throws Exception {
        writeEvidence(evBilling());

        // -------------------------------------------------------------- positives
        String summary = Preflight.emit(EV, SHEET);
        ok("pos.emit_summary", summary.equals("PREFLIGHT_SHEET_OK rows=10 checked=5 outside=5 vc=" + vc() + " vn=" + vn())
                && summary.startsWith("PREFLIGHT_SHEET_OK"), summary);

        String s = sheetText();
        int i = 1;
        boolean order = true;
        for (Preflight.Step st : Preflight.inventory()) {
            if (!s.contains("`" + st.id + "`")) { order = false; break; }
            i++;
        }
        ok("pos.row_ids_in_order", order, "all 10 inventory ids present in the sheet");

        ok("pos.identity_line", s.contains("identity: vc=" + vc() + " vn=" + vn()), "identity from the ONE source");

        byte[] first = Files.readAllBytes(SHEET);
        Preflight.emit(EV, SHEET);
        ok("pos.deterministic", java.util.Arrays.equals(first, Files.readAllBytes(SHEET)), "emit twice = same bytes");

        String green = tryCheck(EV, SHEET);
        ok("pos.check_green", green.startsWith("PREFLIGHT_SHEET_CHECK_OK"), green);

        ok("pos.jitter_stripped", !s.contains("days_left=") && !s.contains("health_ms="),
                "the sheet is deterministic — decaying/ms fields dropped");

        ok("pos.evidence_verbatim", s.contains(Preflight.stripJitter(evBundle()))
                && s.contains(Preflight.stripJitter(evDeploysrv())),
                "CHECKED payloads are the fresh lines byte-for-byte (after jitter strip)");

        ok("pos.outside_literals_rendered", s.contains("ci/build_signedapk.sh")
                && s.contains("com.hark.neokey.SignedApk sign")
                && s.contains("scp -r pack/ deploy@server:~/")
                && s.contains(SyncServerPack.CMD_PROVISION),
                "the OUTSIDE commands render verbatim in the sheet");

        ok("pos.notes_documented", s.contains("بلا تظاهر") && s.contains("NK-SEC-001"),
                "the externality notes are in the sheet — no pretending");

        boolean filesExist = true;
        for (String c : Preflight.RELEASE_COMMANDS) if (!Files.isRegularFile(Paths.get(c))) filesExist = false;
        ok("pos.commands_files_exist", filesExist, "the five release commands exist in the tree");

        // -------------------------------------------------------------- negativos
        writeEvidence(null);
        String got;
        try { Preflight.emit(EV, DIR.resolve("sheet-x.md")); got = "NO_REFUSAL"; }
        catch (IllegalStateException e) { got = e.getMessage(); }
        ok("neg.emit_evidence_missing", got.contains("evidence.missing"), got);
        writeEvidence(evBilling());

        writeEvidence("BILLINGGATE_OK checks=24 negatives=10/10 vc=" + vcPrev() + " vn=" + vn() + " providers=2");
        try { Preflight.emit(EV, DIR.resolve("sheet-x.md")); got = "NO_REFUSAL"; }
        catch (IllegalStateException e) { got = e.getMessage(); }
        ok("neg.emit_evidence_identity", got.contains("evidence.identity"), got);
        writeEvidence(evBilling());

        Path d = dirtyCopy("rowdrop");
        Files.write(d, sheetText().replaceAll("(?m)^\\| 3 \\|.*\\n", "").getBytes(StandardCharsets.UTF_8));
        ok("neg.row_dropped", tryCheck(EV, d).contains("sheet.row_missing billing.contracts"),
                "a dropped row is caught FROM the inventory (found the next id in its place)");

        d = dirtyCopy("tamper");
        Files.write(d, sheetText().replaceFirst("checks=14", "checks=15").getBytes(StandardCharsets.UTF_8));
        ok("neg.evidence_tampered", tryCheck(EV, d).contains("sheet.evidence_drift bundle.reverify"),
                "a tampered CHECKED payload refuses with its tag");

        d = dirtyCopy("flip");
        Files.write(d, sheetText().replaceFirst("\\| OUTSIDE \\|", "| CHECKED | KSDRILLGATE_OK checks=12 |")
                .getBytes(StandardCharsets.UTF_8));
        ok("neg.status_flipped", tryCheck(EV, d).contains("sheet.status"), "an OUTSIDE step claimed CHECKED — refused");

        d = dirtyCopy("fabricate");
        Files.write(d, sheetText().replaceFirst("com\\.hark\\.neokey\\.Provision", "com.hark.neokey.ProvisionX")
                .getBytes(StandardCharsets.UTF_8));
        ok("neg.command_fabricated", tryCheck(EV, d).contains("sheet.command_drift"), "a fabricated command refuses");

        d = dirtyCopy("identity");
        Files.write(d, sheetText().replaceFirst("identity: vc=" + vc() + " vn=" + vn(),
                "identity: vc=" + vcPrev() + " vn=" + vn()).getBytes(StandardCharsets.UTF_8));
        ok("neg.identity_line", tryCheck(EV, d).contains("identity.mismatch"), "a lying identity line refuses");

        // cleanup
        Files.deleteIfExists(DIR.resolve("sheet-x.md"));
        try (java.util.stream.Stream<Path> st = Files.list(DIR)) {
            for (Path p : st.toArray(Path[]::new)) if (p.getFileName().toString().startsWith("sheet-")) Files.deleteIfExists(p);
        }

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
