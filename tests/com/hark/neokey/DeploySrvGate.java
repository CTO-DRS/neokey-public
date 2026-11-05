package com.hark.neokey;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;

/**
 * بوابة أرتقف نشر الخادم — المرحلة 7 W2 (الأسبوع 35) — ci/deploysrv_gate.sh
 *
 * المعلق الموثق يقول إن ما يبقى من نشر خادم المزامنة هو «شراء النطاق +
 * شهادة CA ثم الأمران الآليان». هذا الأسبوع يغلق الشريحة المحلية الباقية:
 * **ما سيُنقل إلى الخادم أرتقف واحد قابل للتحقق، وما يُشغَّل هو المُختبر
 * ذاته**:
 *
 *   1. الحزمة: `SyncServerPack` يجمع الجرة المُبرهَنة (بوابة W27 تُقلعها
 *      وتجري عليها جولة سلك) + سكربتات التشغيل/الإيقاف/الصحة المولّدة
 *      كودياً + وحدة systemd + INSTALL.md بأوامر نسخ-لصق حرفية **منها
 *      الأمران الآليان** (Provision ← `NetGate.setSyncPins`) + بيان حتمي
 *      `neokey-deploysrv-1` بلا طوابع زمن.
 *   2. المرساة أولاً («المرجع = المنشور»): بصمة SPKI المطبوعة من الجرة على
 *      مخزن الحلبة == pin16 الحلبة المنشور حرفياً (e67e815c3494a3c5) —
 *      الجرة تحمل الهوية نفسها التي نشرتها بوابتا W26/W30.
 *   3. **إقامة نسخة الحزمة ذاتها**: `java -jar pack/neokey-sync-server.jar`
 *      كعملية منفصلة تماماً كما يشغّلها المشغل — سطر READY، ثم **جولة عقد
 *      كاملة على السلك** فوق قناة مثبتة بالدبوس المطبوع (ملح، idempotent،
 *      تعارض، جلب، مجهول، طول باطل، كتلة، قائمة، وصول محرم، حقن مسار)، ثم
 *      فحص الصحة من الجرة نفسها (--check) والدبوس الشريد يُرفض.
 *   4. أربع سلبيات على الحزمة كلٌّ بوسمها: عضو محذوف، sha بيانية مُعتل،
 *      انسياب اسم الجرة في سكربت، أمر الدبوس محذوف من INSTALL — ثم إعادة
 *      تحقق خضراء للحزمة الحقيقية.
 *
 * الحكم: `DEPLOYSRVGATE_OK checks=N negatives=4/4 jar_sha256=<16hex>
 * members=N vc=76 vn=2.9` / `DEPLOYSRVGATE_FAILED <reason>` (exit 1).
 */
public final class DeploySrvGate {

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("DEPLOYSRV regression: " + name + " (" + desc + ")"); }
    }

    static void neg(String name, String tag, String got) {
        checks++; negTotal++;
        if (got != null && got.contains(tag)) { negPass++; System.out.println("NEG-OK " + name + " [" + tag + "]"); }
        else { reg++; System.out.println("DEPLOYSRV regression: " + name + " (got: " + got + ")"); }
    }

    static final String ARENA_P12 = "tests/tls/arena-server.p12";
    static final char[] PASS = "nk-arena".toCharArray();
    /** pin16 الحلبة المنشور — مرساة provision/TLS الحرفية («المرجع = المنشور»). */
    static final String ARENA_PIN16 = "e67e815c3494a3c5";
    static final Path PACK = Paths.get("build/deploy/pack");

    public static void main(String[] args) throws Exception {
        // ---- 1: الحزمة — تجميع ثم فحص عضّ ---------------------------------
        String assembled;
        try { assembled = SyncServerPack.assemble(PACK); }
        catch (IllegalStateException e) { System.out.println("DEPLOYSRVGATE_FAILED pack.assemble " + e.getMessage()); System.exit(1); return; }
        System.out.println("PACK " + assembled);
        String checked;
        try { checked = SyncServerPack.check(PACK); }
        catch (IllegalStateException e) { System.out.println("DEPLOYSRVGATE_FAILED pack.check " + e.getMessage()); System.exit(1); return; }
        System.out.println("PACK " + checked);
        ck("pack.check_green", checked.startsWith("DEPLOYSRV_PACK_CHECK_OK"), "the assembled pack passes its own check");

        // الحتمية: تجميع ثانٍ = بيان بايت-لطرفاً
        byte[] stmt1 = Files.readAllBytes(PACK.resolve("statement.json"));
        SyncServerPack.assemble(PACK);
        byte[] stmt2 = Files.readAllBytes(PACK.resolve("statement.json"));
        ck("pack.deterministic", java.util.Arrays.equals(stmt1, stmt2), "assemble twice = same statement bytes (no timestamps)");

        // ---- 2: المرساة — بصمة الجرة == pin16 الحلبة المنشور ----------------
        String spki = printSpki();
        ck("anchor.spki16", spki != null && spki.startsWith(ARENA_PIN16),
                "the jar's printed SPKI == the published arena pin16 (" + ARENA_PIN16 + ") — got " + spki);
        if (spki == null) { System.out.println("DEPLOYSRVGATE_FAILED anchor.spki"); System.exit(1); return; }

        // ---- 3: إقامة نسخة الحزمة ذاتها كعملية منفصلة ------------------------
        int port = freePort();
        Process server = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--keystore", ARENA_P12, "--password", "nk-arena",
                "--host", "127.0.0.1", "--port", String.valueOf(port))
                .redirectErrorStream(true).start();
        final InputStream out = server.getInputStream();
        final boolean[] ready = { false };
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[srv] " + line);
                    if (line.startsWith("SYNC_SERVER_READY")) ready[0] = true;
                }
            } catch (Exception ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        long t0 = System.currentTimeMillis();
        while (!ready[0] && System.currentTimeMillis() - t0 < 30000) Thread.sleep(150);
        ck("boot.ready", ready[0], "the pack copy prints SYNC_SERVER_READY as a separate process");
        if (!ready[0]) { server.destroyForcibly(); System.out.println("DEPLOYSRVGATE_FAILED boot.ready"); System.exit(1); return; }
        Thread.sleep(400);

        // قناة مثبتة: سياق الحلبة (CA الحلبة + الدبوس المطبوع من الجرة)
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(ARENA_P12))) { ks.load(in, PASS); }
        Certificate leaf = ks.getCertificate(ks.aliases().nextElement());
        SpkiPins pins = new SpkiPins(spki);
        javax.net.ssl.SSLContext ctx = PinnedTls.arenaContext(leaf.getEncoded(), pins);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);   // الحلبة بالـIP — الثقة بالدبوس لا بالاسم

        String base = "https://127.0.0.1:" + port;
        String acc = Base64.getUrlEncoder().withoutPadding().encodeToString("deploysrv-gate-account".getBytes(StandardCharsets.UTF_8));
        byte[] salt = new byte[32]; new java.security.SecureRandom().nextBytes(salt);
        byte[] blob = ("deploysrv-pack-round-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        String blobId = Base64.getUrlEncoder().withoutPadding().encodeToString("blob-1".getBytes(StandardCharsets.UTF_8));

        // ---- 4: جولة العقد على نسخة الحزمة المشغَّلة -------------------------
        ck("wire.salt_put", req("PUT", base + "/v1/account/" + acc + "/salt", salt) == 201, "salt PUT -> 201");
        ck("wire.salt_idempotent", req("PUT", base + "/v1/account/" + acc + "/salt", salt) == 201, "same salt again -> 201 (idempotent)");
        byte[] other = salt.clone(); other[0] ^= 0x5A;
        ck("wire.salt_conflict", req("PUT", base + "/v1/account/" + acc + "/salt", other) == 409, "different salt -> 409 (no replacement ever)");
        byte[] gotSalt = reqBody("GET", base + "/v1/account/" + acc + "/salt", null);
        ck("wire.salt_get", gotSalt != null && java.util.Arrays.equals(gotSalt, salt), "salt GET -> 200 + the same 32B");
        ck("wire.salt_unknown", req("GET", base + "/v1/account/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nobody".getBytes(StandardCharsets.UTF_8)) + "/salt", null) == 404, "unknown account -> 404");
        ck("wire.salt_badlen", req("PUT", base + "/v1/account/" + acc + "/salt", new byte[16]) == 400, "16B salt -> 400");
        ck("wire.blob_put", req("PUT", base + "/v1/account/" + acc + "/blob/" + blobId, blob) == 201, "blob PUT -> 201");
        byte[] gotBlob = reqBody("GET", base + "/v1/account/" + acc + "/blob/" + blobId, null);
        ck("wire.blob_get", gotBlob != null && java.util.Arrays.equals(gotBlob, blob), "blob GET -> 200 + the same bytes");
        byte[] list = reqBody("GET", base + "/v1/account/" + acc + "/blobs", null);
        ck("wire.blob_list", list != null && new String(list, StandardCharsets.UTF_8).contains(blobId), "blobs list -> 200 JSON with the id");
        String otherAcc = Base64.getUrlEncoder().withoutPadding().encodeToString("stranger".getBytes(StandardCharsets.UTF_8));
        ck("wire.blob_foreign", req("PUT", base + "/v1/account/" + otherAcc + "/blob/" + blobId, blob) == 404, "blob PUT on unknown account -> 404");
        ck("wire.path_injection", req("GET", base + "/v1/account/" + acc + "/blob/..%2F" + blobId, null) == 404, "path traversal -> 404 (nothing after)");

        // ---- 5: الصحة من الجرة نفسها + الدبوس الشريد يُرفض ------------------
        long h0 = System.currentTimeMillis();
        String health = jarCheck(base + "/v1/", spki);
        long healthMs = System.currentTimeMillis() - h0;
        ck("health.ok", health.contains("SYNCH_HEALTH_OK"), "the pack copy probes itself over the pinned channel (" + health.trim() + ")");
        String rogue = jarCheck(base + "/v1/", "0000000000000000000000000000000000000000000000000000000000000000");
        neg("neg.rogue_pin", "SYNCH_HEALTH_FAIL", rogue);

        // ---- 6: الإيقاف — العملية تموت والمنفذ يصمت -------------------------
        server.destroy();
        server.waitFor();
        ck("stop.exits", !server.isAlive(), "the pack copy exits on SIGTERM");
        int probe = -1;
        try { probe = req("GET", base + "/v1/account/" + acc + "/salt", null); } catch (Exception e) { probe = 0; }
        ck("stop.port_dead", probe == 0, "the port refuses after the stop (" + probe + ")");

        // ---- 7: سلبيات الحزمة الأربع (نسخ ملطخة — الحزمة الحقيقية تُعاد) ----
        negPack("neg.member_removed", "statement.member_missing", pack -> Files.deleteIfExists(pack.resolve("health-server.sh")));
        negPack("neg.statement_sha_tampered", "pack.jar_sha", pack -> {
            Path st = pack.resolve("statement.json");
            String s = new String(Files.readAllBytes(st), StandardCharsets.UTF_8);
            int i = s.indexOf("\"jarSha256\": \"") + "\"jarSha256\": \"".length();
            char repl = s.charAt(i) == '0' ? '1' : '0';   // حتمي ومختلف دائماً
            Files.write(st, (s.substring(0, i) + repl + s.substring(i + 1)).getBytes(StandardCharsets.UTF_8));
        });
        negPack("neg.script_drift", "script.jar_ref", pack -> {
            Path sc = pack.resolve("start-server.sh");
            Files.write(sc, new String(Files.readAllBytes(sc), StandardCharsets.UTF_8)
                    .replace("neokey-sync-server.jar", "neokey-sync-server-OTHER.jar").getBytes(StandardCharsets.UTF_8));
            SyncServerPack.restamp(pack, "start-server.sh");   // المزوّر يعيد الختم — الحرفية أعمق من الهاش
        });
        negPack("neg.install_command_dropped", "install.pinset_command", pack -> {
            Path in = pack.resolve("INSTALL.md");
            Files.write(in, new String(Files.readAllBytes(in), StandardCharsets.UTF_8)
                    .replace("NetGate.setSyncPins(\"", "NetGate.PINS_DISABLED(\"").getBytes(StandardCharsets.UTF_8));
            SyncServerPack.restamp(pack, "INSTALL.md");   // المزوّر يعيد الختم — نص الأمر أعمق من الهاش
        });
        try { ck("pack.green_after_negatives", SyncServerPack.check(PACK).startsWith("DEPLOYSRV_PACK_CHECK_OK"), "the real pack still checks green"); }
        catch (IllegalStateException e) { ck("pack.green_after_negatives", false, e.getMessage()); }

        // ---- الحكم -----------------------------------------------------------
        if (reg > 0) {
            System.out.println("DEPLOYSRVGATE_FAILED regressions=" + reg + "/" + checks + " (see DEPLOYSRV regression lines above)");
            System.exit(1);
        }
        String jarSha = SyncServerPack.sha256Hex(Files.readAllBytes(PACK.resolve("neokey-sync-server.jar")));
        System.out.println("DEPLOYSRVGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " jar_sha256=" + jarSha.substring(0, 16) + " members=" + countMembers() + " health_ms=" + healthMs
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);   // مصدر واحد: ثوابت المُجمِّع — الكذبة تصل الحكم فتصطادها مرساة الأسبوع
    }

    // ---- أدوات ----------------------------------------------------------------

    static int countMembers() throws Exception { return Files.list(PACK).filter(Files::isRegularFile).toArray().length - 1; }

    /** سلبة الحزمة: نسخة ملطخة يجب أن يرفضها `check` بوسمها — الحزمة الحقيقية لا تُلمس. */
    interface PackMut { void m(Path pack) throws Exception; }

    static void negPack(String name, String tag, PackMut mut) throws Exception {
        Path dirty = Paths.get("build/deploy/pack-dirty");
        rm(dirty);
        java.util.stream.Stream<Path> files = Files.list(PACK);
        Files.createDirectories(dirty);
        for (Path p : files.toArray(Path[]::new)) {
            if (p.getFileName().toString().equals("pack-dirty")) continue;
            if (Files.isRegularFile(p)) Files.copy(p, dirty.resolve(p.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        files.close();
        mut.m(dirty);
        String got;
        try { SyncServerPack.check(dirty); got = "NO_REFUSAL"; }
        catch (IllegalStateException e) { got = e.getMessage(); }
        neg(name, tag, got);
        rm(dirty);
    }

    static void rm(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(x);
        }
    }

    /** بصمة SPKI من الجرة نفسها (--print-spki على مخزن الحلبة). */
    static String printSpki() throws Exception {
        Process p = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--print-spki", "--keystore", ARENA_P12, "--password", "nk-arena")
                .redirectErrorStream(true).start();
        String line = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith("SYNC_SERVER_SPKI ")) line = l.substring("SYNC_SERVER_SPKI ".length()).trim();
        }
        p.waitFor();
        return line;
    }

    /** فحص الصحة من الجرة (--check) — العقد هو المسبار (المخزن مطلوب،
     *  ومرساة الحلبة DER تجعل القناة مقبولة: شهادة الحلبة ذاتية التوقيع). */
    static String jarCheck(String base, String pin) throws Exception {
        Path anchor = Paths.get("build/deploysrv-gate/arena-leaf.der");
        if (!Files.exists(anchor)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Paths.get(ARENA_P12))) { ks.load(in, PASS); }
            Certificate leaf = ks.getCertificate(ks.aliases().nextElement());
            Files.createDirectories(anchor.getParent());
            Files.write(anchor, leaf.getEncoded());
        }
        Process p = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--check", base, "--pin", pin,
                "--anchor", anchor.toString(),
                "--keystore", ARENA_P12, "--password", "nk-arena").redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { return s.getLocalPort(); }
    }

    /** طلب سلك خام — يُعاد رمز الحالة (الجسم يُستهلك). */
    static int req(String method, String url, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000); c.setReadTimeout(10000);
        if (body != null && !method.equals("GET")) { c.setDoOutput(true); c.getOutputStream().write(body); }
        int rc = c.getResponseCode();
        try (InputStream in = rc >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in != null) in.readAllBytes();
        }
        return rc;
    }

    static byte[] reqBody(String method, String url, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000); c.setReadTimeout(10000);
        int rc = c.getResponseCode();
        if (rc >= 400) {
            try (InputStream in = c.getErrorStream()) { if (in != null) in.readAllBytes(); }
            return null;
        }
        try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
    }
}
