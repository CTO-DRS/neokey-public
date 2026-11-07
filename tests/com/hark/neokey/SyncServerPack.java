package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * مُجمِّع حزمة نشر خادم المزامنة — المرحلة 7 W2 (الأسبوع 35).
 *
 * المعلق الموثق يقول إن ما يبقى من نشر الخادم هو «النطاق + شهادة CA ثم
 * الأمران الآليان» — وهذه الحزمة تجعل ما يُنقل إلى الخادم **أرتقفاً واحداً
 * قابلاً للتحقق** بدل مجموعة ملفات متناثرة: الجرة المُبرهَنة (بوابة W27
 * تُقلعها وتجري عليها جولة سلك) + سكربتات التشغيل/الإيقاف/الصحة المولّدة
 * كودياً (لا نصوص يدوية تنساب) + وحدة systemd + **INSTALL.md بأوامر
 * نسخ-لصق حرفية منها الأمران الآليان** (القرار Provision ← سطر
 * `NetGate.setSyncPins` المُصدر) + بيان حتمي `neokey-deploysrv-1` بلا
 * طوابع زمن (أسماء الأعضاء + sha256 لكل عضو + sha256 الجرة + هوية الأسبوع
 * — نفس المدخلات = نفس البايتات، وتجميع ثانٍ = بيان بايت-لطرفاً).
 *
 * العقد (نقي JVM، صفر android.*):
 *   • `assemble <packDir>` يبني الحزمة من مخرجات `ci/build_deploy.sh`
 *     (الجرة) و`deploy/` (وحدة systemd) — جرة غائبة = رفض بوسمه.
 *   • `check <packDir>` يتحقق عضّاً: كل عضو حاضر، sha256 كل عضو == البيان،
 *     sha256 الجرة == البيان، السكربتات تشير إلى اسم الجرة الحرفي، و
 *     INSTALL.md يحمل الأمرين الحرفيين — أي انحراف = رفض بوسم ثابت.
 *   • «المرجع = المنشور»: البيان يكتب ما هو حاضر بايت-لطرفاً — تزوير عضو
 *     أو استبدال الجرة أو انسياب سطر أمر يُصطاد في `check` من أول تشغيل.
 *
 * الحكم: `DEPLOYSRV_PACK_OK members=N jar_sha256=<16hex>` / `DEPLOYSRV_PACK_FAIL <tag>`.
 */
public final class SyncServerPack {

    /** هوية الأسبوع المُجمِّعة — تتحرك مع ترقية الأسبوع (درس المواضع الخمسة). */
    static final String VC = "84";
    static final String VN = "3.7";
    /** اسم الجرة الحرفي الذي تشير إليه السكربتات والوثيقة — انحرافه = رفض. */
    static final String JAR_NAME = "neokey-sync-server.jar";
    /** الأمران الآليان الحرفيان — نص المعلق الموثق نفسه، والفحص يطابق وجودهما. */
    static final String CMD_PROVISION =
            "java -cp <classes> com.hark.neokey.Provision server.p12 '<pass>' sync sync.example.com --ca ca.pem --statement provision-statement.json";
    static final String CMD_PINSET_PREFIX = "NetGate.setSyncPins(\"";

    private SyncServerPack() { }

    static String sha256Hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] read(Path p) throws Exception { return Files.readAllBytes(p); }

    static void refuse(String tag) { throw new IllegalStateException(tag); }

    // ---- الأعضاء المولّدة كودياً (لا نصوص يدوية تنساب) -----------------------

    static String startScript() {
        return "#!/bin/sh\n"
                + "# NeoKey sync server — start (foreground). The jar in THIS pack is\n"
                + "# the artifact proven by ci/deploysrv_gate.sh — byte-for-byte.\n"
                + "DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                + "exec java -jar \"$DIR/" + JAR_NAME + "\" \\\n"
                + "  --keystore \"${SYNC_KEYSTORE:?set SYNC_KEYSTORE}\" \\\n"
                + "  --password \"${SYNC_KEYPASS:?set SYNC_KEYPASS}\" \\\n"
                + "  --host \"${SYNC_HOST:-127.0.0.1}\" \\\n"
                + "  --port \"${SYNC_PORT:-8443}\"\n";
    }

    static String stopScript() {
        return "#!/bin/sh\n"
                + "# NeoKey sync server — stop by jar identity (not by guesswork).\n"
                + "pkill -f \"" + JAR_NAME + "\" || true\n"
                + "sleep 1\n"
                + "pgrep -f \"" + JAR_NAME + "\" >/dev/null 2>&1 && { echo STOP_FAILED; exit 1; }\n"
                + "echo STOP_OK\n";
    }

    static String healthScript() {
        return "#!/bin/sh\n"
                + "# NeoKey sync server — health: the CONTRACT is the probe (grey 404 = alive)\n"
                + "# over a pinned channel, via the jar's own --check mode.\n"
                + "DIR=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                + "exec java -jar \"$DIR/" + JAR_NAME + "\" --check \"${SYNC_BASE:?set SYNC_BASE}\" --pin \"${SYNC_PIN:?set SYNC_PIN}\"\n";
    }

    static String installDoc() {
        return "# NeoKey sync server — install (copy-paste, in order)\n\n"
                + "الحزمة أرتقف واحد مُبرهَن: الجرة داخل هذا المجلد هي التي أثبتتها\n"
                + "`ci/deploysrv_gate.sh` على السلك (جولة عقد كاملة على نسخة الحزمة ذاتها).\n\n"
                + "## 1) البناء (في بيئة التطوير)\n\n"
                + "```bash\nci/build_deploy.sh   # build/deploy/neokey-sync-server.jar\n```\n\n"
                + "## 2) النقل إلى الخادم والتشغيل\n\n"
                + "```bash\nscp -r pack/ deploy@server:~/\n"
                + "SYNC_KEYSTORE=server.p12 SYNC_KEYPASS='<pass>' SYNC_HOST=127.0.0.1 ./start-server.sh\n```\n\n"
                + "## 3) القرار الآلي (الأمر الأول من الأمرين — قرار «هل هذه السلسلة صالحة للنشر؟»)\n\n"
                + "```bash\n" + CMD_PROVISION + "\n# PROVISION_OK domain=sync.example.com pin=<64hex> pin16=<16hex> days_left=… emitted=\"NetGate.setSyncPins(\\\"<pin>\\\")\"\n```\n\n"
                + "## 4) ضبط الدبوس في العميل (الأمر الثاني — السطر المُصدر حرفياً)\n\n"
                + "```bash\n" + CMD_PINSET_PREFIX + "<64hex من PROVISION_OK>\")\n```\n\n"
                + "دبوس الحلبة المنشور مرجعاً: pin16 = e67e815c3494a3c5 — وكل بصمة إنتاج\n"
                + "تُشتق باشتقاق واحد (`SpkiPins.pinOf`) من شهادة الخادم نفسها («المرجع = المنشور»).\n\n"
                + "## 5) الخدمة والصحة\n\n"
                + "```bash\nsudo cp neokey-sync-server.service /etc/systemd/system/ && sudo systemctl enable --now neokey-sync-server\n"
                + "SYNC_BASE=https://sync.example.com SYNC_PIN=<64hex> ./health-server.sh\n```\n\n"
                + "الفشل مغلق: `SYNCH_HEALTH_FAIL <tag>` / `PROVISION_FAIL <tag>` / `DEPLOY_REFUSED <tag>` — لا جاهزية غامضة.\n";
    }

    /** البيان الحتمي: أعضاء مرتبة + sha256 لكل عضو + هوية الأسبوع — بلا طوابع زمن. */
    static String statement(Path packDir) throws Exception {
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(packDir)) {
            for (Path p : st.sorted().toArray(Path[]::new)) {
                if (Files.isRegularFile(p) && !p.getFileName().toString().equals("statement.json"))
                    names.add(p.getFileName().toString());
            }
        }
        Collections.sort(names);
        TreeMap<String, String> sha = new TreeMap<>();
        for (String n : names) sha.put(n, sha256Hex(read(packDir.resolve(n))));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schema\": \"neokey-deploysrv-1\",\n");
        sb.append("  \"identity\": { \"versionCode\": ").append(VC).append(", \"versionName\": \"").append(VN).append("\" },\n");
        sb.append("  \"jar\": \"").append(JAR_NAME).append("\",\n");
        sb.append("  \"jarSha256\": \"").append(sha.get(JAR_NAME)).append("\",\n");
        sb.append("  \"members\": {\n");
        int i = 0;
        for (String n : names) {
            sb.append("    \"").append(n).append("\": \"").append(sha.get(n)).append("\"");
            sb.append(++i < names.size() ? ",\n" : "\n");
        }
        sb.append("  },\n  \"membersCount\": ").append(names.size()).append("\n}\n");
        return sb.toString();
    }

    // ---- assemble / check -----------------------------------------------------

    /** يبني الحزمة — يجمع، لا يقرر: مدخلاتها مُبرهَنة ببواباتها. */
    public static String assemble(Path packDir) throws Exception {
        Path jar = Paths.get("build/deploy/" + JAR_NAME);
        if (!Files.isRegularFile(jar)) refuse("jar.missing (run ci/build_deploy.sh first)");
        Path unit = Paths.get("deploy/neokey-sync-server.service");
        if (!Files.isRegularFile(unit)) refuse("unit.missing");
        Files.createDirectories(packDir);
        Files.copy(jar, packDir.resolve(JAR_NAME), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(unit, packDir.resolve("neokey-sync-server.service"), StandardCopyOption.REPLACE_EXISTING);
        write(packDir.resolve("start-server.sh"), startScript());
        write(packDir.resolve("stop-server.sh"), stopScript());
        write(packDir.resolve("health-server.sh"), healthScript());
        write(packDir.resolve("INSTALL.md"), installDoc());
        write(packDir.resolve("statement.json"), statement(packDir).getBytes(StandardCharsets.UTF_8));
        return "DEPLOYSRV_PACK_OK members=" + countOf(new String(read(packDir.resolve("statement.json")), StandardCharsets.UTF_8)) + " jar_sha256="
                + sha256Hex(read(jar)).substring(0, 16);
    }

    static void write(Path p, byte[] b) throws Exception { Files.write(p, b); }
    static void write(Path p, String s) throws Exception { write(p, s.getBytes(StandardCharsets.UTF_8)); }

    /** عدد الأعضاء من البيان — القيمة تنتهي بسطر لا اقتباس. */
    static int countOf(String s) {
        int i = s.indexOf("\"membersCount\": ");
        if (i < 0) refuse("statement.count_field");
        int v = i + "\"membersCount\": ".length();
        int e = s.indexOf('\n', v);
        if (e < 0) refuse("statement.count_field");
        return Integer.parseInt(s.substring(v, e).trim());
    }

    /** فحص عضّ لحزمة قائمة — البيان هو المرجع: أعضاؤه يُطاردون واحداً واحداً. */
    public static String check(Path packDir) throws Exception {
        if (!Files.isDirectory(packDir)) refuse("pack.missing");
        Path stmt = packDir.resolve("statement.json");
        if (!Files.isRegularFile(stmt)) refuse("statement.missing");
        String s = new String(read(stmt), StandardCharsets.UTF_8);
        // 1) أعضاء البيان (المرجع = المنشور): كل معلَن يجب أن يكون حاضراً —
        //    عضو محذوف يُصطاد من البيان لا من المجلد
        List<String> declared = declaredMembers(s);
        if (declared.isEmpty()) refuse("statement.members_empty");
        for (String n : declared) {
            if (!Files.isRegularFile(packDir.resolve(n))) refuse("statement.member_missing " + n);
            String want = extract(s, "\"" + n + "\": \"");
            if (want == null) refuse("statement.member_missing " + n);
            if (!sha256Hex(read(packDir.resolve(n))).equals(want)) refuse("pack.member_sha " + n);
        }
        // 2) لا عضو زائد عن البيان
        List<String> present = new ArrayList<>();
        try (java.util.stream.Stream<Path> st = Files.list(packDir)) {
            for (Path p : st.sorted().toArray(Path[]::new)) {
                if (Files.isRegularFile(p) && !p.getFileName().toString().equals("statement.json"))
                    present.add(p.getFileName().toString());
            }
        }
        if (present.size() != declared.size()) refuse("statement.count " + present.size() + "!=" + declared.size());
        // 3) الجرة هي الجرة المعلنة
        String jarSha = extract(s, "\"jarSha256\": \"");
        if (!sha256Hex(read(packDir.resolve(JAR_NAME))).equals(jarSha)) refuse("pack.jar_sha");
        // 4) السكربتات تشير إلى اسم الجرة الحرفي (انسياب اسم = رفض — حتى مع
        //    بيان مُعاد ختمه: هذه الحرفية أعمق من الهاشات)
        for (String sc : new String[]{"start-server.sh", "stop-server.sh", "health-server.sh"}) {
            if (!new String(read(packDir.resolve(sc)), StandardCharsets.UTF_8).contains(JAR_NAME))
                refuse("script.jar_ref " + sc);
        }
        // 5) الأمران الحرفيان في INSTALL.md — نص المعلق الموثق نفسه (أعمق من
        //    الهاشات كذلك: إعادة ختم البيان لا تنقذ أمراً محذوفاً)
        String inst = new String(read(packDir.resolve("INSTALL.md")), StandardCharsets.UTF_8);
        if (!inst.contains(CMD_PROVISION)) refuse("install.provision_command");
        if (!inst.contains(CMD_PINSET_PREFIX)) refuse("install.pinset_command");
        // 6) هوية الأسبوع في البيان
        if (!s.contains("\"versionCode\": " + VC) || !s.contains("\"versionName\": \"" + VN + "\""))
            refuse("statement.identity");
        return "DEPLOYSRV_PACK_CHECK_OK members=" + declared.size() + " jar_sha256=" + jarSha.substring(0, 16);
    }

    /** أسماء أعضاء كتلة members في البيان — بترتيبها المعلن. */
    static List<String> declaredMembers(String s) {
        List<String> out = new ArrayList<>();
        int b = s.indexOf("\"members\": {");
        if (b < 0) return out;
        int e = s.indexOf("\n  },", b);
        if (e < 0) e = s.length();
        String block = s.substring(b, e);
        int i = 0;
        while (true) {
            int k = block.indexOf("    \"", i);
            if (k < 0) break;
            int colon = block.indexOf("\": \"", k);
            if (colon < 0) break;
            out.add(block.substring(k + 5, colon));
            i = colon + 4;
        }
        return out;
    }

    /** إعادة ختم sha عضو في البيان — نمذجة مزوِّر يحدّث الهاشات بعد العبث
     *  (الذي يصطاده إذن: الحرفيات الأعمق — اسم الجرة، نص الأمران). */
    public static void restamp(Path packDir, String member) throws Exception {
        Path st = packDir.resolve("statement.json");
        String s = new String(read(st), StandardCharsets.UTF_8);
        String fresh = sha256Hex(read(packDir.resolve(member)));
        String key = "\"" + member + "\": \"";
        int i = s.indexOf(key);
        if (i < 0) refuse("statement.member_missing " + member);
        int v = i + key.length();
        s = s.substring(0, v) + fresh + s.substring(v + 64);
        write(st, s);
    }

    static String extract(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return null;
        int v = i + key.length();
        int e = s.indexOf('"', v);
        int e2 = s.indexOf(',', v);
        int end = (e < 0 || (e2 >= 0 && e2 < e)) ? e2 : e;
        if (end < 0) return null;
        String r = s.substring(v, end).trim().replace("\"", "");
        return r.isEmpty() ? null : r;
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 2 && "assemble".equals(args[0])) { System.out.println(assemble(Paths.get(args[1]))); return; }
            if (args.length == 2 && "check".equals(args[0])) { System.out.println(check(Paths.get(args[1]))); return; }
            System.err.println("usage: SyncServerPack assemble|check <packDir>");
            System.exit(2);
        } catch (IllegalStateException e) {
            System.out.println("DEPLOYSRV_PACK_FAIL " + e.getMessage());
            System.exit(2);
        }
    }
}
