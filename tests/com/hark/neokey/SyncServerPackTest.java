package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * اختبارات مُجمِّع حزمة نشر الخادم — المرحلة 7 W2 (الأسبوع 35).
 *
 * عقد الحزمة يُقاس هنا بمدخلات صناعية (بايتات جرة وهمية — الحزمة لا تفتح
 * الجرة، تجمعها وتشهدها): التجميع، البيان الحتمي (نفس المدخلات = نفس
 * البايتات، بلا طوابع زمن)، هوية الأسبوع في البيان، **الأمران الحرفيان**
 * في INSTALL.md، السكربتات تشير إلى اسم الجرة الحرفي، والفحص العضّ:
 * عضو محذوف، sha بيانية مُعتل، انسياب اسم، أمر محذوف — كلٌّ بوسمه. الجرة
 * الحقيقية وجولة السلك على نسخة الحزمة = بوابة `deploysrv` (الأسبوع نفسه).
 */
public class SyncServerPackTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static Path work, jar, unit;

    static void fixtures() throws Exception {
        work = Paths.get("build/test-pack-fixtures");
        if (Files.exists(work)) {
            try (java.util.stream.Stream<Path> s = Files.walk(work)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
            }
        }
        Files.createDirectories(work);
        jar = work.resolve("build/deploy/neokey-sync-server.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, ("fake-jar-bytes-" + SyncServerPack.VC + "-" + SyncServerPack.VN).getBytes(StandardCharsets.UTF_8));
        unit = work.resolve("deploy-unit/neokey-sync-server.service");
        Files.createDirectories(unit.getParent());
        Files.write(unit, "[Unit]\nDescription=NeoKey sync server (fixture)\n".getBytes(StandardCharsets.UTF_8));
    }

    /** يُجمِّع من الإيماءات: يحوّل مسارَي القراءة الثابتين مؤقتاً بالنسخ. */
    static String assembleTo(Path out) throws Exception {
        // SyncServerPack يقرأ مسارَي build/deploy وdeploy النسبيين — الإيماءات
        // توضع فيهما داخل هذا الاختبار (المستودع الحقيقي يملكهما أصلاً).
        return SyncServerPack.assemble(out);
    }

    static String tryCheck(Path p) {
        try { SyncServerPack.check(p); return "NO_REFUSAL"; }
        catch (IllegalStateException e) { return e.getMessage(); }
        catch (Exception e) { return "EX:" + e.getClass().getSimpleName(); }
    }

    public static void main(String[] args) throws Exception {
        fixtures();
        // الإيماءات في المسارات التي يقرأها المُجمِّع (نسخ احتياطي لما هو حاضر)
        Path realJar = Paths.get("build/deploy/neokey-sync-server.jar");
        Path realUnit = Paths.get("deploy/neokey-sync-server.service");
        boolean hadJar = Files.exists(realJar), hadUnit = Files.exists(realUnit);
        byte[] jarBackup = hadJar ? Files.readAllBytes(realJar) : null;
        byte[] unitBackup = hadUnit ? Files.readAllBytes(realUnit) : null;
        Files.createDirectories(realJar.getParent());
        Files.createDirectories(realUnit.getParent());
        Files.write(realJar, Files.readAllBytes(jar));
        Files.write(realUnit, Files.readAllBytes(unit));

        try {
            Path pack = work.resolve("pack");
            String ok = assembleTo(pack);
            check("assemble: verdict line", ok.startsWith("DEPLOYSRV_PACK_OK") && ok.contains("members=6"));

            String stmt = new String(Files.readAllBytes(pack.resolve("statement.json")), StandardCharsets.UTF_8);
            check("statement: schema neokey-deploysrv-1", stmt.contains("\"schema\": \"neokey-deploysrv-1\""));
            check("statement: week identity pinned", stmt.contains("\"versionCode\": " + SyncServerPack.VC)
                    && stmt.contains("\"versionName\": \"" + SyncServerPack.VN + "\""));
            check("statement: no timestamps (deterministic by construction)", !stmt.contains("generatedAt") && !stmt.contains("timestamp"));
            check("INSTALL: the Provision command literal", new String(Files.readAllBytes(pack.resolve("INSTALL.md")), StandardCharsets.UTF_8)
                    .contains(SyncServerPack.CMD_PROVISION));
            check("INSTALL: the pin-set command literal", new String(Files.readAllBytes(pack.resolve("INSTALL.md")), StandardCharsets.UTF_8)
                    .contains(SyncServerPack.CMD_PINSET_PREFIX));
            check("scripts: reference the jar by its literal name",
                    Files.readAllBytes(pack.resolve("start-server.sh")).length > 0
                            && new String(Files.readAllBytes(pack.resolve("start-server.sh")), StandardCharsets.UTF_8).contains(SyncServerPack.JAR_NAME)
                            && new String(Files.readAllBytes(pack.resolve("stop-server.sh")), StandardCharsets.UTF_8).contains(SyncServerPack.JAR_NAME)
                            && new String(Files.readAllBytes(pack.resolve("health-server.sh")), StandardCharsets.UTF_8).contains(SyncServerPack.JAR_NAME));

            // الحتمية: تجميع ثانٍ = بيان بايت-لطرفاً
            byte[] s1 = Files.readAllBytes(pack.resolve("statement.json"));
            assembleTo(pack);
            byte[] s2 = Files.readAllBytes(pack.resolve("statement.json"));
            check("determinism: assemble twice = same statement bytes", java.util.Arrays.equals(s1, s2));

            // الفحص الأخضر
            check("check: green on the assembled pack", SyncServerPack.check(pack).startsWith("DEPLOYSRV_PACK_CHECK_OK"));

            // الفحص العضّ (نسخ ملطخة — الأصل لا يُلمس)
            Path dirty = work.resolve("dirty");
            copyDir(pack, dirty);
            Files.deleteIfExists(dirty.resolve("health-server.sh"));
            check("negative: removed member refused [statement.member_missing]",
                    tryCheck(dirty).startsWith("statement.member_missing"));

            copyDir(pack, dirty);
            String t = new String(Files.readAllBytes(dirty.resolve("statement.json")), StandardCharsets.UTF_8);
            int i = t.indexOf("\"jarSha256\": \"") + "\"jarSha256\": \"".length();
            char repl = t.charAt(i) == '0' ? '1' : '0';
            Files.write(dirty.resolve("statement.json"),
                    (t.substring(0, i) + repl + t.substring(i + 1)).getBytes(StandardCharsets.UTF_8));
            check("negative: tampered jar sha refused [pack.jar_sha]", tryCheck(dirty).startsWith("pack.jar_sha"));

            copyDir(pack, dirty);
            Files.write(dirty.resolve("start-server.sh"),
                    new String(Files.readAllBytes(dirty.resolve("start-server.sh")), StandardCharsets.UTF_8)
                            .replace(SyncServerPack.JAR_NAME, "other-name.jar").getBytes(StandardCharsets.UTF_8));
            SyncServerPack.restamp(dirty, "start-server.sh");   // مزوّر يعيد الختم — الحرفية أعمق
            check("negative: script drift refused [script.jar_ref]", tryCheck(dirty).startsWith("script.jar_ref"));

            copyDir(pack, dirty);
            Files.write(dirty.resolve("INSTALL.md"),
                    new String(Files.readAllBytes(dirty.resolve("INSTALL.md")), StandardCharsets.UTF_8)
                            .replace(SyncServerPack.CMD_PROVISION, "REMOVED").getBytes(StandardCharsets.UTF_8));
            SyncServerPack.restamp(dirty, "INSTALL.md");
            check("negative: dropped Provision command refused [install.provision_command]",
                    tryCheck(dirty).startsWith("install.provision_command"));

            copyDir(pack, dirty);
            Files.write(dirty.resolve("INSTALL.md"),
                    new String(Files.readAllBytes(dirty.resolve("INSTALL.md")), StandardCharsets.UTF_8)
                            .replace(SyncServerPack.CMD_PINSET_PREFIX, "REMOVED").getBytes(StandardCharsets.UTF_8));
            SyncServerPack.restamp(dirty, "INSTALL.md");
            check("negative: dropped pin-set command refused [install.pinset_command]",
                    tryCheck(dirty).startsWith("install.pinset_command"));

            check("negative: missing pack refused [pack.missing]", tryCheck(work.resolve("nope")).startsWith("pack.missing"));
        } finally {
            // استعادة الحقيقي دائماً (درس الأسابيع 24/25: الاستعادة خارج كل مسار فشل)
            if (hadJar) Files.write(realJar, jarBackup); else Files.deleteIfExists(realJar);
            if (hadUnit) Files.write(realUnit, unitBackup); else Files.deleteIfExists(realUnit);
        }

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void copyDir(Path from, Path to) throws Exception {
        if (Files.exists(to)) {
            try (java.util.stream.Stream<Path> s = Files.walk(to)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
            }
        }
        Files.createDirectories(to);
        try (java.util.stream.Stream<Path> s = Files.list(from)) {
            for (Path p : s.toArray(Path[]::new))
                if (Files.isRegularFile(p)) Files.copy(p, to.resolve(p.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
