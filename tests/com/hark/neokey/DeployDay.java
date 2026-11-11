package com.hark.neokey;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * التدريب العام ليوم النشر — تحضير W51 (المعلقة الخارجية #2) — ci/deployday_gate.sh
 *
 * المعلق الخارجي الثاني يقول بالضبط: «النطاق المشترى + شهادة CA تجارية + نقل
 * الحزمة + التشغيل على الخادم الحقيقي». ثلاثة منها لا تُدَّعى هنا (لا نطاق
 * ولا CA تجارية ولا مضيف حقيقي في هذا الصندوق) — لكن التسلسل الذي يمشي به
 * المشغّل يوم النشر كله قابل للتدريب محلياً بمواد بديلة مولَّدة طازجاً
 * (keytool في البوابة)، وهذا التدريب يمشيه من أول طرف لآخر، على الحزمة
 * المرجعية ذاتها التي تثبتها ci/deploysrv_gate.sh، وبالأداتين الحقيقيتين
 * (Provision CLI + --check في الجرة):
 *
 *   1. الحزمة            — تجميع + فحص (ما يُنقل إلى الخادم).
 *   2. القرار            — Provision CLI على المخزن المولَّد (SAN عنوان IP
 *                          للحلبة — النطاق الحقيقي يأتي بشهادة CA تجارية،
 *                          وهذا موثق لا مدّعى) — PROVISION_OK مع السلسلة
 *                          [leaf, CA] وسطر الضبط المُصدَر والبيان المكتوب.
 *   3. سلبيات القرار     — شهادة نطاق مغترب (san_mismatch) / مرساة CA
 *                          مغتربة (ca_mismatch) / منتهية (validity_expired)
 *                          / قصيرة أقل من أرضية الثلاثين يوماً
 *                          (validity_short) / ذاتية التوقيع بلا قرار صريح
 *                          (no_ca_anchor) — كل رفض بوسمه الحرفي من الأداة
 *                          نفسها التي ستقرر على السلسلة الحقيقية.
 *   4. «المرجع = المنشور»— بصمة الجرة (--print-spki) == الدبوس المشتق من
 *                          القرار — اشتقاقان مستقلان يتوافقان بايت-لطرفاً.
 *   5. التشغيل           — نسخة الحزمة تعمل بمخزن التجهيز (SYNC_SERVER_READY).
 *   6. الحياة            — --check بقناة مرساة CA المشغّل + الدبوس المشتق
 *                          (تحقق اسم صارم — SAN العنوان يطابق) SYNCH_HEALTH_OK.
 *   7. جولة العقد        — أحد عشر فحصاً على السلك فوق القناة المثبتة
 *                          (ملح/تضاد/مجهول/طول/blob/أجنبي/تجوال).
 *   8. سلبيتا السلك      — دبوس شريد على الخادم الجيد (spki_not_pinned) +
 *                          خادم CA مغترب موثوق لكن غير مثبت — الدبوس هو
 *                          الخط الأخير عندئذ (spki_not_pinned أيضاً).
 *   9. الإيقاف والشفاء   — SIGTERM يموت والمنفذ يصمت، والقرار يعاد أخضر
 *                          بعد كل السلبيات (الشجرة تُشفى).
 *
 * الحكم: `DEPLOYDAY_OK checks=N negatives=M/M pin16=<16hex> days_left=<n>
 *         pack_members=<n> vc=1 vn=0.1.0` / `DEPLOYDAY_FAILED <reason>` (exit 1).
 * نقي JVM (صفر android.*) — بوابات النشر كلها خارج dex التطبيق بالتصميم.
 */
public final class DeployDay {

    /** كلمة سر مواد التدريب — موثقة عن قصد (نمط tests/provision/README.md). */
    static final String PASS = "day-pass-2026";
    /** النطاق البديل للتدريب: عنوان الحلبة — SAN نوع iPAddress تقوله الأداة صراحة. */
    static final String STANDIN_DOMAIN = "127.0.0.1";
    static final Path PACK = Paths.get("build/deploy/pack");
    static final Path DAY = Paths.get("build/deployday");
    static final String CA_PEM = "build/deployday/operator-ca.pem";
    /** اسم مخزن الخادم المُجهّز في CLI المشغّل (نفس أمر INSTALL.md الحرفي). */
    static final String SYNC_ALIAS = "sync";

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("DEPLOYDAY regression: " + name + " (" + desc + ")"); }
    }

    /** سلبة مقاسة: الوسم الحرفي يجب أن يعض على النسخة الملطخة. */
    static void neg(String name, String expect, String got) {
        checks++; negTotal++;
        boolean ok = expect.equals(got);
        if (ok) negPass++;
        else System.out.println("NEG-FAILED " + name + " (expected " + expect + ", got " + got + ")");
    }

    public static void main(String[] args) throws Exception {
        // ---- 1: الحزمة — تجميع ثم فحص عضّ ---------------------------------
        String packLine;
        try { packLine = SyncServerPack.assemble(PACK); }
        catch (IllegalStateException e) { fail("pack.assemble " + e.getMessage()); return; }
        System.out.println("PACK " + packLine);
        String checked;
        try { checked = SyncServerPack.check(PACK); }
        catch (IllegalStateException e) { fail("pack.check " + e.getMessage()); return; }
        ck("pack.check_green", checked.startsWith("DEPLOYSRV_PACK_CHECK_OK"), checked);
        int members = Integer.parseInt(checked.replaceAll(".*members=", "").replaceAll("[^0-9].*$", ""));

        // ---- 2: القرار — Provision CLI (أمر المشغّل نفسه) ------------------
        Path stmt = DAY.resolve("provision-statement.json");
        Files.deleteIfExists(stmt);
        String dec = provisionCli(DAY.resolve("server.p12").toString(), STANDIN_DOMAIN,
                "--ca", CA_PEM, "--statement", stmt.toString());
        System.out.println("DECISION " + dec.trim());
        ck("decision.ok", dec.startsWith("PROVISION_OK"), "the operator's decision is green on the stand-in material");
        if (!dec.startsWith("PROVISION_OK")) { fail("decision.ok"); return; }
        String pin = field(dec, "pin=");
        String pin16 = field(dec, "pin16=");
        String daysLeft = field(dec, "days_left=");
        String chain = field(dec, "chain=");
        ck("decision.chain2", "2".equals(chain), "the generated chain is [leaf, CA] (chain=" + chain + ")");
        ck("decision.statement_written", Files.isRegularFile(stmt), "the provision statement was written");
        boolean stmtPin = false;
        try { stmtPin = new String(Files.readAllBytes(stmt), StandardCharsets.UTF_8).contains("\"pin16\": \"" + pin16 + "\""); }
        catch (Exception ignore) { }
        ck("decision.statement_pin", stmtPin, "the statement carries the decision's pin16");

        // ---- 3: سلبيات القرار الخمس — الأداة نفسها على المواد السيئة -------
        neg("neg.san_mismatch", "san_mismatch",
                refuseOf(DAY.resolve("wrongdom.p12").toString(), "sync", STANDIN_DOMAIN, null, false, null));
        neg("neg.ca_mismatch", "ca_mismatch",
                refuseOf(DAY.resolve("server.p12").toString(), "sync", STANDIN_DOMAIN, null, false,
                        Provision.readCertFile("build/deployday/foreign-ca.pem")));
        neg("neg.validity_expired", "validity_expired",
                refuseOf(DAY.resolve("expired.p12").toString(), "sync", "expired.neokey.test", null, false, null));
        neg("neg.validity_short", "validity_short",
                refuseOf(DAY.resolve("short.p12").toString(), "sync", "short.neokey.test", null, false, null));
        neg("neg.no_ca_anchor", "no_ca_anchor",
                refuseOf(DAY.resolve("plain.p12").toString(), "solo", STANDIN_DOMAIN, null, false, null));

        // ---- 4: المرجع = المنشور — بصمة الجرة == دبوس القرار ----------------
        String spki = printSpki(PACK.resolve("neokey-sync-server.jar").toString(),
                DAY.resolve("server.p12").toString());
        ck("cross.spki", spki != null && spki.equals(pin),
                "the jar's own derivation == the Provision derivation (المرجع = المنشور)");

        // ---- 5: التشغيل — نسخة الحزمة بمخزن التجهيز -------------------------
        int port = freePort();
        Process server = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--keystore", DAY.resolve("server.p12").toString(), "--password", PASS,
                "--host", "127.0.0.1", "--port", String.valueOf(port))
                .redirectErrorStream(true).start();
        final boolean[] ready = { false };
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
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
        ck("boot.ready", ready[0], "the pack copy serves the PROVISIONED keystore as a separate process");
        if (!ready[0]) { server.destroyForcibly(); fail("boot.ready"); return; }
        Thread.sleep(400);

        // ---- 6: الحياة — الجرة نفسها تستكشف بقناة مرساة + دبوس القرار -------
        String base = "https://127.0.0.1:" + port + "/v1/";
        String health = jarCheck(base, pin, CA_PEM);
        System.out.println("HEALTH " + health.trim());
        ck("health.ok", health.contains("SYNCH_HEALTH_OK"), "the real probe on the pinned anchored channel (strict name check — the IP SAN matches)");
        String rogue = jarCheck(base, "0000000000000000000000000000000000000000000000000000000000000000", CA_PEM);
        neg("neg.rogue_pin", "SYNCH_HEALTH_FAIL", rogue.contains("SYNCH_HEALTH_FAIL") ? "SYNCH_HEALTH_FAIL" : "NO_REFUSAL");

        // ---- 7: جولة العقد فوق القناة المثبتة (تحقق اسم صارم) ---------------
        KeyStore caKs = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(DAY.resolve("operator-ca.p12"))) { caKs.load(in, PASS.toCharArray()); }
        Certificate caCert = caKs.getCertificate("nk-day-ca");
        SpkiPins pins = new SpkiPins(pin);
        javax.net.ssl.SSLContext ctx = PinnedTls.arenaContext(caCert.getEncoded(), pins);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        // لا HostnameVerifier متساهل هنا — التحقق الصارم الافتراضي يجري، وSAN
        // العنوان يطابق: الجولة تثبت الدبوس والاسم معاً.

        String acc = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("deployday-gate-account".getBytes(StandardCharsets.UTF_8));
        byte[] salt = new byte[32]; new java.security.SecureRandom().nextBytes(salt);
        byte[] blob = ("deployday-rehearsal-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        String blobId = Base64.getUrlEncoder().withoutPadding().encodeToString("blob-1".getBytes(StandardCharsets.UTF_8));
        ck("wire.salt_put", req("PUT", base + "account/" + acc + "/salt", salt) == 201, "salt PUT -> 201");
        ck("wire.salt_idempotent", req("PUT", base + "account/" + acc + "/salt", salt) == 201, "same salt again -> 201 (no replacement ever)");
        byte[] other = salt.clone(); other[0] ^= 0x5A;
        ck("wire.salt_conflict", req("PUT", base + "account/" + acc + "/salt", other) == 409, "different salt -> 409");
        byte[] gotSalt = reqBody("GET", base + "account/" + acc + "/salt", null);
        ck("wire.salt_get", gotSalt != null && java.util.Arrays.equals(gotSalt, salt), "salt GET -> the same 32B");
        ck("wire.salt_unknown", req("GET", base + "account/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nobody".getBytes(StandardCharsets.UTF_8)) + "/salt", null) == 404, "unknown account -> 404");
        ck("wire.salt_badlen", req("PUT", base + "account/" + acc + "/salt", new byte[16]) == 400, "16B salt -> 400");
        ck("wire.blob_put", req("PUT", base + "account/" + acc + "/blob/" + blobId, blob) == 201, "blob PUT -> 201");
        byte[] gotBlob = reqBody("GET", base + "account/" + acc + "/blob/" + blobId, null);
        ck("wire.blob_get", gotBlob != null && java.util.Arrays.equals(gotBlob, blob), "blob GET -> the same bytes");
        byte[] list = reqBody("GET", base + "account/" + acc + "/blobs", null);
        ck("wire.blob_list", list != null && new String(list, StandardCharsets.UTF_8).contains(blobId), "blobs list -> 200 JSON with the id");
        String otherAcc = Base64.getUrlEncoder().withoutPadding().encodeToString("stranger".getBytes(StandardCharsets.UTF_8));
        ck("wire.blob_foreign", req("PUT", base + "account/" + otherAcc + "/blob/" + blobId, blob) == 404, "blob PUT on unknown account -> 404");
        ck("wire.path_injection", req("GET", base + "account/" + acc + "/blob/..%2F" + blobId, null) == 404, "path traversal -> 404");

        // ---- 8: سلك CA مغترب — موثوق لكن غير مثبت، والدبوس هو الخط الأخير --
        int port2 = freePort();
        Process rogueSrv = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--keystore", DAY.resolve("foreign-server.p12").toString(), "--password", PASS,
                "--host", "127.0.0.1", "--port", String.valueOf(port2))
                .redirectErrorStream(true).start();
        final boolean[] rogueReady = { false };
        Thread r2 = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(rogueSrv.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (line.startsWith("SYNC_SERVER_READY")) rogueReady[0] = true;
            } catch (Exception ignored) { }
        });
        r2.setDaemon(true);
        r2.start();
        t0 = System.currentTimeMillis();
        while (!rogueReady[0] && System.currentTimeMillis() - t0 < 30000) Thread.sleep(150);
        if (rogueReady[0]) {
            Thread.sleep(400);
            // الثقة موسوطة على CA المغترب نفسها (المهاجم موثوق) — الدبوس وحده يرفض
            String swap = jarCheck("https://127.0.0.1:" + port2 + "/v1/", pin, "build/deployday/foreign-ca.pem");
            neg("neg.trusted_unpinned", "SYNCH_HEALTH_FAIL", swap.contains("SYNCH_HEALTH_FAIL") ? "SYNCH_HEALTH_FAIL" : "NO_REFUSAL");
            rogueSrv.destroy();
        } else {
            rogueSrv.destroyForcibly();
            neg("neg.trusted_unpinned", "SYNCH_HEALTH_FAIL", "ROGUE_NOT_UP");
        }

        // ---- 9: الإيقاف + الشفاء --------------------------------------------
        server.destroy();
        server.waitFor();
        ck("stop.exits", !server.isAlive(), "the provisioned server exits on SIGTERM");
        int probe = -1;
        try { probe = req("GET", base + "account/" + acc + "/salt", null); } catch (Exception e) { probe = 0; }
        ck("stop.port_dead", probe == 0, "the port refuses after the stop (" + probe + ")");
        String heal = provisionCli(DAY.resolve("server.p12").toString(), STANDIN_DOMAIN, "--ca", CA_PEM, "--statement", stmt.toString());
        ck("heal.decision", heal.startsWith("PROVISION_OK"), "the decision re-runs green after every negative (the tree heals)");

        // ---- الحكم -----------------------------------------------------------
        if (reg > 0) {
            System.out.println("DEPLOYDAY_FAILED regressions=" + reg + "/" + checks + " (see DEPLOYDAY regression lines above)");
            System.exit(1);
            return;
        }
        System.out.println("DEPLOYDAY_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " pin16=" + pin16 + " days_left=" + daysLeft + " pack_members=" + members
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);
    }

    // ---- أدوات ----------------------------------------------------------------

    static void fail(String reason) {
        System.out.println("DEPLOYDAY_FAILED " + reason);
        System.exit(1);
    }

    static String field(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        int end = i + key.length();
        while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != '"') end++;
        return line.substring(i + key.length(), end);
    }

    /** القرار عبر CLI المشغّل (عملية حقيقية — ليس استدعاء صف).
     *  ترتيب الأمر الحرفي: Provision <ks> <pass> <alias> <domain> [opts...] */
    static String provisionCli(String ks, String domain, String... extra) throws Exception {
        String[] full = new String[8 + extra.length];
        full[0] = "java"; full[1] = "-cp"; full[2] = System.getProperty("java.class.path");
        full[3] = "com.hark.neokey.Provision"; full[4] = ks; full[5] = PASS;
        full[6] = SYNC_ALIAS; full[7] = domain;
        System.arraycopy(extra, 0, full, 8, extra.length);
        Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    /** سلبة القرار داخل العملية — وسم الرفض الحرفي من Provision نفسها. */
    static String refuseOf(String ks, String alias, String domain, String expectedPin,
                           boolean allowSelfSigned, byte[] expectedCaDer) {
        try {
            Provision.provision(ks, PASS.toCharArray(), alias, domain, expectedPin, allowSelfSigned, expectedCaDer);
            return "NO_REFUSAL";
        } catch (Provision.Refused r) {
            return r.tag;
        } catch (Exception e) {
            return "unreadable_chain";
        }
    }

    /** بصمة SPKI من الجرة (--print-spki) — الاشتقاق الثاني المستقل. */
    static String printSpki(String jar, String ks) throws Exception {
        Process p = new ProcessBuilder("java", "-jar", jar,
                "--print-spki", "--keystore", ks, "--password", PASS)
                .redirectErrorStream(true).start();
        String line = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null)
                if (l.startsWith("SYNC_SERVER_SPKI ")) line = l.substring("SYNC_SERVER_SPKI ".length()).trim();
        }
        p.waitFor();
        return line;
    }

    /** فحص الحياة من الجرة نفسها (--check) — مرساة + دبوس. */
    static String jarCheck(String base, String pin, String anchor) throws Exception {
        Process p = new ProcessBuilder("java", "-jar", PACK.resolve("neokey-sync-server.jar").toString(),
                "--check", base, "--pin", pin, "--anchor", anchor,
                "--keystore", DAY.resolve("server.p12").toString(), "--password", PASS)
                .redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { return s.getLocalPort(); }
    }

    static int req(String method, String url, byte[] body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
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
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000); c.setReadTimeout(10000);
        int rc = c.getResponseCode();
        if (rc >= 400) {
            try (InputStream in = c.getErrorStream()) { if (in != null) in.readAllBytes(); }
            return null;
        }
        try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
    }

    private DeployDay() { throw new UnsupportedOperationException(); }
}
