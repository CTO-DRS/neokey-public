package com.hark.neokey;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PubSyncTest — المرحلة 9 W1 (الأسبوع 42): قوانين النشر.
 *
 * نقي JVM — يدير PubSync عمليةً (رفضاته System.exit بوسمها الحرفي،
 * فالعملية هي العقد) على مجلدات مؤقتة: حتمية التزويد، جولة zip↔unzip،
 * قوانين البيان والجولة، وكل رفض بوسمه الحرفي — ومنها التزوير
 * «المتسق» (بيان معاد ختمه بهاش خاطئ) وzip-slip المصنوع يدوياً.
 * لا اعتماد على الشبكة ولا على مفتاح — قوانين الوحدة نقية.
 */
public class PubSyncTest {

    static int passed = 0, failed = 0, total = 0;
    static final Path TMP = Paths.get("build/pubsync-test");

    static void ok(String name, boolean cond, String desc) {
        total++;
        if (cond) { passed++; System.out.println("ok " + name + " — " + desc); }
        else { failed++; System.out.println("FAIL " + name + " — " + desc); }
    }

    /** يعمل PubSync عملية ويعيد [rc, stdout]. */
    static String[] run(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("java", "-cp", System.getProperty("java.class.path"), "com.hark.neokey.PubSync"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return new String[]{String.valueOf(p.exitValue()), out};
    }

    static boolean tagIs(String want, String got) {
        return got != null && got.startsWith(want);
    }

    static String sha(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] all = Files.readAllBytes(p);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(all)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** أرشيف أعضلة صغير + بيان + مجلد جولة صحيح. */
    static Path miniArchive() throws Exception {
        Path dir = TMP.resolve("archive");
        Files.createDirectories(dir);
        Files.write(dir.resolve("gates.env"), "PUBSYNC_GATE=OK\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("pipeline.log"), "PIPELINE_OK\n".getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    static Path writeStatement(Path f, String evidenceName, String evidenceSha, String patchName, String patchSha,
                               String week, String rel, String base, String vc, String vn) throws Exception {
        String json = "{\n"
                + "  \"schema\": \"neokey-pubsync-1\",\n"
                + "  \"week\": \"" + week + "\",\n"
                + "  \"rel\": \"" + rel + "\",\n"
                + "  \"base_tag\": \"" + base + "\",\n"
                + "  \"vc\": \"" + vc + "\",\n"
                + "  \"vn\": \"" + vn + "\",\n"
                + "  \"asset_name1\": \"" + evidenceName + "\",\n"
                + "  \"asset_sha1\": \"" + evidenceSha + "\",\n"
                + "  \"asset_name2\": \"" + patchName + "\",\n"
                + "  \"asset_sha2\": \"" + patchSha + "\"\n"
                + "}\n";
        Files.write(f, json.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        rmdir(TMP);
        Files.createDirectories(TMP);

        // ---- الحتمية: تزويدان = بايتات متطابقة ----
        Path archive = miniArchive();
        Path z1 = TMP.resolve("det1.zip"), z2 = TMP.resolve("det2.zip");
        run("zip", archive.toString(), z1.toString());
        Files.write(archive.resolve("pipeline.log"), "PIPELINE_OK\n".getBytes(StandardCharsets.UTF_8)); // نفس المحتوى
        run("zip", archive.toString(), z2.toString());
        ok("pos.zip_deterministic", sha(z1).equals(sha(z2)), "two zips of the same archive = identical bytes (the determinism law)");

        // ---- الجولة: unzip يعيد البايتات ----
        Path out1 = TMP.resolve("out1");
        String[] rt = run("unzip", z1.toString(), out1.toString());
        boolean same = rt[0].equals("0")
                && new String(Files.readAllBytes(out1.resolve("gates.env")), StandardCharsets.UTF_8).equals("PUBSYNC_GATE=OK\n")
                && new String(Files.readAllBytes(out1.resolve("pipeline.log")), StandardCharsets.UTF_8).equals("PIPELINE_OK\n");
        ok("pos.zip_roundtrip", same, "unzip restores the archive bytes exactly");

        // ---- البيان الأخضر ----
        Path evZip = TMP.resolve("neokey-evidence-week42.zip");
        Files.copy(z1, evZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path patch = TMP.resolve("neokey-patch-week33-42.patch");
        Files.write(patch, "diff --git a/x b/x\n".getBytes(StandardCharsets.UTF_8));
        Path dl = TMP.resolve("download");
        Files.createDirectories(dl);
        Files.copy(evZip, dl.resolve("neokey-evidence-week42.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(patch, dl.resolve("neokey-patch-week33-42.patch"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path st = TMP.resolve("statement.json");
        writeStatement(st, "neokey-evidence-week42.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch),
                "42", "434872239", "v2.7-week33", SyncServerPack.VC, SyncServerPack.VN);
        String[] v = run("verify", st.toString(), dl.toString());
        ok("pos.verify_ok", v[0].equals("0"), "a true statement over the true published bytes verifies green");

        // ---- الرفضات الاثنا عشر بوسمها ----
        String[] r;
        Path bad;

        bad = TMP.resolve("st-schema.json");
        writeStatement(bad, "neokey-evidence-week42.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch), "42", "434872239", "v2.7-week33", SyncServerPack.VC, SyncServerPack.VN);
        Files.write(bad, new String(Files.readAllBytes(bad), StandardCharsets.UTF_8).replace("neokey-pubsync-1", "neokey-pubsync-2").getBytes(StandardCharsets.UTF_8));
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.statement_schema", r[0].equals("1") && tagIs(PubSync.E_SCHEMA, firstTag(r[1])), "a foreign schema refuses");

        bad = TMP.resolve("st-identity.json");
        writeStatement(bad, "neokey-evidence-week42.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch), "42", "434872239", "v2.7-week33", "99", "9.9");
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.statement_identity", r[0].equals("1") && tagIs(PubSync.E_IDENTITY, firstTag(r[1])), "an identity other than SyncServerPack's refuses (the single source)");

        bad = TMP.resolve("st-weekname.json");
        writeStatement(bad, "neokey-evidence-week41.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch), "42", "434872239", "v2.7-week33", SyncServerPack.VC, SyncServerPack.VN);
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.name_identity", r[0].equals("1") && tagIs(PubSync.E_WEEKNAME, firstTag(r[1])), "an asset name not carrying THIS week refuses");

        bad = TMP.resolve("st-shamissing.json");
        String j = new String(Files.readAllBytes(st), StandardCharsets.UTF_8).replaceAll("(?m)^\\s*\"asset_sha1\":.*\\n", "");
        Files.write(bad, j.getBytes(StandardCharsets.UTF_8));
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.sha_missing", r[0].equals("1") && tagIs(PubSync.E_SHA_MISSING, firstTag(r[1])), "a declared asset without a sha refuses");

        bad = TMP.resolve("st-forger.json");
        writeStatement(bad, "neokey-evidence-week42.zip", "0".repeat(64), "neokey-patch-week33-42.patch", sha(patch), "42", "434872239", "v2.7-week33", SyncServerPack.VC, SyncServerPack.VN);
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.forger_sha", r[0].equals("1") && tagIs(PubSync.E_SHA_MISMATCH, firstTag(r[1])), "the consistent forger's re-stamped sha bites (re-derived from the downloaded bytes)");

        Path dlMissing = TMP.resolve("download-missing");
        Files.createDirectories(dlMissing);
        Files.copy(patch, dlMissing.resolve("neokey-patch-week33-42.patch"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        r = run("verify", st.toString(), dlMissing.toString());
        ok("neg.asset_missing", r[0].equals("1") && tagIs(PubSync.E_ASSET_MISSING, firstTag(r[1])), "a published asset absent from the roundtrip refuses");

        Path dlExtra = TMP.resolve("download-extra");
        Files.createDirectories(dlExtra);
        Files.copy(evZip, dlExtra.resolve("neokey-evidence-week42.zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(patch, dlExtra.resolve("neokey-patch-week33-42.patch"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.write(dlExtra.resolve("planted.bin"), "x".getBytes(StandardCharsets.UTF_8));
        r = run("verify", st.toString(), dlExtra.toString());
        ok("neg.extra_asset", r[0].equals("1") && tagIs(PubSync.E_EXTRA_ASSET, firstTag(r[1])), "an undeclared file in the roundtrip refuses");

        bad = TMP.resolve("st-rel.json");
        writeStatement(bad, "neokey-evidence-week42.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch), "42", "not-a-number", "v2.7-week33", SyncServerPack.VC, SyncServerPack.VN);
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.rel_shape", r[0].equals("1") && tagIs(PubSync.E_REL_SHAPE, firstTag(r[1])), "a non-numeric release id refuses");

        bad = TMP.resolve("st-base.json");
        writeStatement(bad, "neokey-evidence-week42.zip", sha(evZip), "neokey-patch-week33-42.patch", sha(patch), "42", "434872239", "week-thirty-three", SyncServerPack.VC, SyncServerPack.VN);
        r = run("verify", bad.toString(), dl.toString());
        ok("neg.base_tag_shape", r[0].equals("1") && tagIs(PubSync.E_BASE_SHAPE, firstTag(r[1])), "a malformed base tag refuses");

        // zip-slip مصنوع يدوياً
        Path slip = TMP.resolve("slip.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(slip))) {
            ZipEntry e = new ZipEntry("../evil.txt");
            e.setTime(631180800000L);
            z.putNextEntry(e);
            z.write("planted".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        r = run("unzip", slip.toString(), TMP.resolve("slip-out").toString());
        ok("neg.zip_slip", r[0].equals("1") && tagIs(PubSync.E_TRAVERSAL, firstTag(r[1])), "a hand-crafted ../ entry refuses (zip-slip)");

        // عضو مقطوع: قلب بايت داخل بيانات مدخل مضغوط
        Path corrupt = TMP.resolve("corrupt.zip");
        byte[] zb = Files.readAllBytes(z1);
        for (int off : new int[]{35, 45, 55, 65, 75}) if (off < zb.length) zb[off] ^= 0x5A; // inside the entry data region
        Files.write(corrupt, zb);
        r = run("unzip", corrupt.toString(), TMP.resolve("corrupt-out").toString());
        boolean truncated = r[0].equals("1") && (tagIs(PubSync.E_TRUNCATED, firstTag(r[1])) || tagIs(PubSync.E_FORMAT, firstTag(r[1])));
        ok("neg.member_corrupt", truncated, "a flipped byte inside the zip refuses (crc/format — the truncated-member class)");

        // بيان مفقود أصلاً
        r = run("verify", TMP.resolve("no-such-statement.json").toString(), dl.toString());
        ok("neg.statement_missing", r[0].equals("1") && tagIs(PubSync.E_SCHEMA, firstTag(r[1])), "a missing statement refuses closed");

        System.out.println("PubSyncTest: RESULT " + passed + " passed, " + failed + " failed ("
                + (System.currentTimeMillis() - t0) + "ms)");
        if (failed > 0) System.exit(1);
    }

    static String firstTag(String out) {
        for (String ln : out.split("\n")) {
            ln = ln.trim();
            if (ln.startsWith("PUBSYNC_FAILED ")) return ln.substring("PUBSYNC_FAILED ".length()).trim();
        }
        return null;
    }

    static void rmdir(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception ignored) {} });
        }
    }
}
