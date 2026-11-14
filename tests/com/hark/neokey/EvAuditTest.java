package com.hark.neokey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * EvAuditTest — اختبارات وحدة مُدقِّق أدلة الأسبوع (المرحلة 8 W2، الأسبوع 39).
 *
 * مواضع EvAudit النقية تُختبر هنا على أرشيفات تركيبية في مجلدات مؤقتة:
 * الحزم الحتمية (تجميعان = بايت-لطرفاً)، وتدقيق الشكلين mid وcomplete،
 * وطبقات القوانين الأربع — إيجابيات على أشكال حقيقية، وسلبيات مصممة تعضّ
 * كلٌّ بوسمها الحرفي (رفضٌ حرس ميت = إخفاق). هوية الأرشيف التركيبي تأخذ
 * ثابتَي المصدر الواحد (SyncServerPack.VC/VN) — فالاختبار لا يشيخ عند
 * ترقية الأسبوع (درس W36/W38).
 */
public class EvAuditTest {

    static int passed = 0, failed = 0, total = 0;

    static void ok(String name, boolean cond, String desc) {
        total++;
        if (cond) { passed++; System.out.println("ok " + name + " — " + desc); }
        else { failed++; System.out.println("FAIL " + name + " — " + desc); }
    }

    static String expectTag(EvAudit.Thunk t) {
        try { t.run(); return null; }
        catch (EvAudit.Refused r) { return r.tag; }
        catch (Exception e) { return "exception:" + e.getClass().getSimpleName(); }
    }

    // ---- الأرشيف التركيبي — شكل mid (أثناء التشغيل) وشكل complete ------------

    static final String[] STAGES = {"probe", "audit", "tests"};

    static String syntheticLog(boolean complete) {
        StringBuilder sb = new StringBuilder();
        for (String s : STAGES) sb.append("[").append(s).append("] PASS (1s)\n");
        sb.append("R8_OK dex=505636 bytes, mapping=210 program classes, manifest components kept=10/10\n");
        sb.append("CoreTests: RESULT 10 passed, 0 failed\n");
        sb.append("ClipRepoTest: RESULT 12 passed, 0 failed\n");
        sb.append("BILLINGGATE_OK checks=24 negatives=10/10 vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN + " providers=2\n");
        sb.append("REBUILDGATE_OK checks=24 negatives=12/12 pins=7/7 stages=38/38 tests=22/0 dex=505636/554000 vc=")
          .append(SyncServerPack.VC).append(" vn=").append(SyncServerPack.VN).append("\n");
        if (complete) {
            sb.append("════════════════ PIPELINE SUMMARY ════════════════\n");
            for (String s : STAGES) sb.append("  ").append(s).append("   PASS\n");
            sb.append("  tests: 22 passed, 0 failed\n");
            sb.append("═══════════════════════════════════════════════════\n");
            sb.append("PIPELINE_OK\n");
        }
        return sb.toString();
    }

    static String syntheticEnv() {
        return "AUDIT=OK\n"
                + "TESTS_TOTAL=22\nTESTS_FAILED=0\n"
                + "R8_DEX_BYTES=505636\nR8_COMPONENTS_KEPT=10/10\n"
                + "BILLING_CHECKS=24\nBILLING_NEGATIVES=10/10\nBILLING_VC=" + SyncServerPack.VC + "\nBILLING_VN=" + SyncServerPack.VN + "\n"
                + "REBUILD_CHECKS=24\nREBUILD_NEGATIVES=12/12\nREBUILD_PINS=7/7\nREBUILD_STAGES=38/38\n"
                + "REBUILD_TESTS=22/0\nREBUILD_DEX=505636\nREBUILD_CEILING=554000\n"
                + "REBUILD_VC=" + SyncServerPack.VC + "\nREBUILD_VN=" + SyncServerPack.VN + "\n";
    }

    static String syntheticManifest() {
        return "{\n  \"identity\": { \"versionCode\": " + SyncServerPack.VC + ", \"versionName\": \"" + SyncServerPack.VN + "\" },\n"
                + "  \"gates\": {}\n}\n";
    }

    static String proofBody() {
        return "== billing negative proof ==\nbit: attack one\n"
                + "\n== PROOF PASSED: all attacks bit with their own tags, all files restored ==\n";
    }

    /** يبني أرشيفاً تركيبياً كاملاً (بدون SHA256SUMS — الاستدعاء يختمه). */
    static Path buildArchive(Path dir, boolean complete) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("pipeline.log"), syntheticLog(complete).getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("gates.env"), syntheticEnv().getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("release-manifest.json"), syntheticManifest().getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("rollout-plan.json"), "{\"stages\":[10,50,100]}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("rollout-plan.txt"), "10% -> 50% -> 100%\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("billing-negative-proof.log"), proofBody().getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("rebuild-negative-proof.log"), proofBody().getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    static void seal(Path dir) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<String>();
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            for (Path p : (Iterable<Path>) st::iterator)
                if (Files.isRegularFile(p) && !p.getFileName().toString().equals("SHA256SUMS"))
                    names.add(p.getFileName().toString());
        }
        EvAudit.writeSums(dir, names);
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("evaudit-test");

        // ---- الإيجابيات: الحزم الحتمية والشكلان --------------------------------
        Path src = tmp.resolve("src"); Files.createDirectories(src);
        Files.write(src.resolve("pipeline.log"), syntheticLog(false).getBytes(StandardCharsets.UTF_8));
        Path rel = tmp.resolve("rel"); Files.createDirectories(rel);
        Files.write(rel.resolve("gates.env"), syntheticEnv().getBytes(StandardCharsets.UTF_8));
        Files.write(rel.resolve("release-manifest.json"), syntheticManifest().getBytes(StandardCharsets.UTF_8));
        Files.write(rel.resolve("rollout-plan.json"), "{\"stages\":[10,50,100]}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(rel.resolve("rollout-plan.txt"), "10% -> 50% -> 100%\n".getBytes(StandardCharsets.UTF_8));
        Files.write(rel.resolve("preflight-sheet.md"), "PREFLIGHT_SHEET_OK rows=10\n".getBytes(StandardCharsets.UTF_8));
        Path ci = tmp.resolve("ci"); Files.createDirectories(ci);
        Files.write(ci.resolve("billing-negative-proof.log"), proofBody().getBytes(StandardCharsets.UTF_8));
        Files.write(ci.resolve("rebuild-negative-proof.log"), proofBody().getBytes(StandardCharsets.UTF_8));
        String a1 = EvAudit.pack(tmp.resolve("arc1"), src.resolve("pipeline.log"), rel, ci);
        String a2 = EvAudit.pack(tmp.resolve("arc2"), src.resolve("pipeline.log"), rel, ci);
        ok("pos.pack.shape", a1.startsWith("EVAUDIT_PACK_OK members=8"),
                "pack reports the full member list (5 core + sheet + 2 proofs)");
        String s1 = new String(Files.readAllBytes(tmp.resolve("arc1/SHA256SUMS")), StandardCharsets.UTF_8);
        String s2 = new String(Files.readAllBytes(tmp.resolve("arc2/SHA256SUMS")), StandardCharsets.UTF_8);
        ok("pos.pack.deterministic", s1.equals(s2), "two pack runs = byte-identical SHA256SUMS (no timestamps)");
        ok("pos.sums.parse", EvAudit.readSums(tmp.resolve("arc1/SHA256SUMS")).size() == 8,
                "SHA256SUMS parses: 8 declared members, none self-listed");

        String[] mid = EvAudit.audit(tmp.resolve("arc1"), SyncServerPack.VC, SyncServerPack.VN);
        ok("pos.audit.mid.green", mid[0].startsWith("EVAUDIT_OK") && "mid".equals(mid[3]),
                "the mid-shape archive audits green (no PIPELINE_OK yet — that shape is honest)");
        ok("pos.audit.mid.fields", "8".equals(mid[1]) && "2".equals(mid[2])
                        && SyncServerPack.VC.equals(mid[4]) && SyncServerPack.VN.equals(mid[5])
                        && "22/0".equals(mid[6]) && "505636".equals(mid[7]),
                "the verdict fields carry members/proofs/identity/tests/dex");

        Path comp = buildArchive(tmp.resolve("comp"), true);
        seal(comp);
        String[] full = EvAudit.audit(comp, null, null);
        ok("pos.audit.complete.green", full[0].startsWith("EVAUDIT_OK") && "complete".equals(full[3]),
                "the complete-shape archive audits green (summary + PIPELINE_OK enforced)");

        // ---- السلبيات: كل قانون يعضّ بوسمه على نسخة متسخة -----------------------
        neg("member.byte_flip", () -> {
            Path d = copyOf(tmp, "n1", comp);
            byte[] b = Files.readAllBytes(d.resolve("gates.env")); b[0] ^= 1;
            Files.write(d.resolve("gates.env"), b);
            EvAudit.lawMembers(d);
        }, "member.sha gates.env");

        neg("member.dropped", () -> {
            Path d = copyOf(tmp, "n2", comp);
            Files.delete(d.resolve("rollout-plan.txt"));
            EvAudit.lawMembers(d);
        }, "member.missing rollout-plan.txt");

        neg("member.undeclared", () -> {
            Path d = copyOf(tmp, "n3", comp);
            Files.write(d.resolve("planted.txt"), "x".getBytes(StandardCharsets.UTF_8));
            EvAudit.lawMembers(d);
        }, "member.undeclared planted.txt");

        neg("required.missing", () -> {
            Path d = copyOf(tmp, "n4", comp);
            Files.delete(d.resolve("release-manifest.json"));
            EvAudit.restampAll(d);
            EvAudit.lawMembers(d);
        }, "required.missing release-manifest.json");

        neg("sums.format", () -> {
            Path d = copyOf(tmp, "n5", comp);
            String sums = new String(Files.readAllBytes(d.resolve("SHA256SUMS")), StandardCharsets.UTF_8);
            Files.write(d.resolve("SHA256SUMS"), sums.replaceFirst("[0-9a-f]{64}", "zz").getBytes(StandardCharsets.UTF_8));
            EvAudit.readSums(d.resolve("SHA256SUMS"));
        }, "sums.format");

        neg("sums.duplicate", () -> {
            Path d = copyOf(tmp, "n6", comp);
            String sums = new String(Files.readAllBytes(d.resolve("SHA256SUMS")), StandardCharsets.UTF_8);
            String first = sums.split("\n", 2)[0];
            Files.write(d.resolve("SHA256SUMS"), (sums + first + "\n").getBytes(StandardCharsets.UTF_8));
            EvAudit.readSums(d.resolve("SHA256SUMS"));
        }, "sums.duplicate billing-negative-proof.log");

        neg("sums.missing", () -> {
            Path d = copyOf(tmp, "n7", comp);
            Files.delete(d.resolve("SHA256SUMS"));
            EvAudit.lawMembers(d);
        }, "sums.missing");

        neg("log.stage_fail", () -> {
            Path d = copyOf(tmp, "n8", comp);
            rewrite(d.resolve("pipeline.log"), "[probe] PASS", "[probe] FAIL (rc=1)");
            EvAudit.lawLogShape(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.stage_fail probe");

        neg("log.tests_failed", () -> {
            Path d = copyOf(tmp, "n9", comp);
            rewrite(d.resolve("pipeline.log"), "RESULT 10 passed", "RESULT 10 passed, 1 failed");
            EvAudit.lawLogShape(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.tests_failed");

        neg("log.result_sum", () -> {
            Path d = copyOf(tmp, "n10", comp);
            rewrite(d.resolve("pipeline.log"), "tests: 22 passed, 0 failed", "tests: 21 passed, 0 failed");
            EvAudit.lawLogShape(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.result_sum");

        neg("log.pipeline_failed", () -> {
            Path d = copyOf(tmp, "n11", comp);
            rewrite(d.resolve("pipeline.log"), "PIPELINE_OK", "PIPELINE_FAILED");
            EvAudit.lawLogShape(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.pipeline_failed");

        neg("log.summary_drift", () -> {
            Path d = copyOf(tmp, "n12", comp);
            rewrite(d.resolve("pipeline.log"), "  audit   PASS", "  audit   SKIP — tampered");
            EvAudit.lawSummaryDrift(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.summary_drift");

        neg("log.ceiling", () -> {
            Path d = copyOf(tmp, "n13", comp);
            EvAudit.forgeField(d.resolve("pipeline.log"), "R8_OK", "dex", "999999");
            EvAudit.lawLogShape(EvAudit.parseLog(d.resolve("pipeline.log")));
        }, "log.ceiling");

        neg("env.billing_checks_mismatch", () -> {
            Path d = copyOf(tmp, "n14", comp);
            EvAudit.forgeField(d.resolve("pipeline.log"), "BILLINGGATE_OK", "checks", "999");
            EvAudit.lawEnvMatch(EvAudit.parseLog(d.resolve("pipeline.log")), EvAudit.envMap(d.resolve("gates.env")));
        }, "env.billing_checks_mismatch");

        neg("env.key_missing", () -> {
            Path d = copyOf(tmp, "n15", comp);
            String env = new String(Files.readAllBytes(d.resolve("gates.env")), StandardCharsets.UTF_8)
                    .replaceAll("BILLING_CHECKS=.*\n", "");
            Files.write(d.resolve("gates.env"), env.getBytes(StandardCharsets.UTF_8));
            EvAudit.lawEnvMatch(EvAudit.parseLog(d.resolve("pipeline.log")), EvAudit.envMap(d.resolve("gates.env")));
        }, "env.key_missing BILLING_CHECKS");

        neg("env.tests_total_mismatch", () -> {
            Path d = copyOf(tmp, "n16", comp);
            String env = new String(Files.readAllBytes(d.resolve("gates.env")), StandardCharsets.UTF_8)
                    .replaceAll("TESTS_TOTAL=\\d+", "TESTS_TOTAL=21");
            Files.write(d.resolve("gates.env"), env.getBytes(StandardCharsets.UTF_8));
            EvAudit.lawEnvMatch(EvAudit.parseLog(d.resolve("pipeline.log")), EvAudit.envMap(d.resolve("gates.env")));
        }, "env.tests_total_mismatch");

        neg("identity.mismatch", () -> {
            Path d = copyOf(tmp, "n17", comp);
            String env = new String(Files.readAllBytes(d.resolve("gates.env")), StandardCharsets.UTF_8)
                    .replaceAll("BILLING_VC=\\d+", "BILLING_VC=79");
            Files.write(d.resolve("gates.env"), env.getBytes(StandardCharsets.UTF_8));
            EvAudit.lawIdentity(EvAudit.parseLog(d.resolve("pipeline.log")), EvAudit.envMap(d.resolve("gates.env")),
                    d.resolve("release-manifest.json"), null, null);
        }, "identity.mismatch");

        neg("identity.expect", () -> {
            String stale = SyncServerPack.VC.equals("79") ? "78" : "79"; // أي هوية غير هوية المصدر الواحد
            EvAudit.audit(comp, stale, null);
        }, "identity.expect");

        neg("proof.missing", () -> {
            Path d = copyOf(tmp, "n18", comp);
            Files.delete(d.resolve("rebuild-negative-proof.log"));
            EvAudit.restampAll(d);
            EvAudit.lawProofs(EvAudit.parseLog(d.resolve("pipeline.log")),
                    EvAudit.readSums(d.resolve("SHA256SUMS")), d);
        }, "proof.missing rebuild-negative-proof.log");

        neg("proof.not_passed", () -> {
            // الملف يُفرَّغ بالكامل: لا سطر نجاح ولا سطر عضّ — لا دليل
            Path d = copyOf(tmp, "n19", comp);
            Files.write(d.resolve("billing-negative-proof.log"), new byte[0]);
            EvAudit.restampAll(d);
            EvAudit.lawProofs(EvAudit.parseLog(d.resolve("pipeline.log")),
                    EvAudit.readSums(d.resolve("SHA256SUMS")), d);
        }, "proof.not_passed billing-negative-proof.log");

        neg("archive.missing", () -> {
            EvAudit.audit(tmp.resolve("does-not-exist"), null, null);
        }, "archive.missing");

        // ---- الحصيلة ----------------------------------------------------------
        System.out.println("RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Path copyOf(Path tmp, String name, Path src) throws IOException {
        Path d = tmp.resolve(name);
        Files.createDirectories(d);
        try (java.util.stream.Stream<Path> st = Files.list(src)) {
            for (Path p : (Iterable<Path>) st::iterator)
                if (Files.isRegularFile(p))
                    Files.copy(p, d.resolve(p.getFileName().toString()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return d;
    }

    static void rewrite(Path file, String anchor, String replacement) throws IOException {
        String s = new String(Files.readAllBytes(Paths.get(file.toString())), StandardCharsets.UTF_8);
        int i = s.indexOf(anchor);
        if (i < 0) throw new IllegalStateException("attack.anchor_missing " + anchor);
        int e = s.indexOf('\n', i);
        if (e < 0) e = s.length();
        Files.write(Paths.get(file.toString()), (s.substring(0, i) + replacement + s.substring(e)).getBytes(StandardCharsets.UTF_8));
    }

    static void neg(String name, EvAudit.Thunk dirty, String expect) {
        total++;
        String t;
        try { dirty.run(); t = null; }
        catch (EvAudit.Refused r) { t = r.tag; }
        catch (Exception e) { t = "exception:" + e.getClass().getSimpleName(); }
        if (expect.equals(t)) {
            passed++;
            System.out.println("ok neg." + name + " — refuses with its exact tag [" + t + "]");
        } else {
            failed++;
            System.out.println("FAIL neg." + name + " — tag " + t + " != " + expect);
        }
    }
}
