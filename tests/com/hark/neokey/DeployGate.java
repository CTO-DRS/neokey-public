package com.hark.neokey;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * بوابة حزمة نشر خادم المزامنة — المرحلة 5، الأسبوع 2 (الأسبوع 27).
 *
 * الحزمة (build/deploy/neokey-sync-server.jar من ci/build_deploy.sh) تُشغَّل
 * **عمليةً مستقلة** كما سيفعل مشغل النشر حرفاً — لا استدعاء صنف، لا اختصار:
 *   • سطر الجاهزية: SYNC_SERVER_READY port=N host=H spki=64hex — بصمة الجرة
 *     المطبوعة == بصمة الشهادة المنشورة == الثابت المرساة («المرجع = المنشور»
 *     ثلاث مرات من ثلاثة مصادر: الجرة، المخزن، والبوابة).
 *   • الربط الافتراضي loopback (host=127.0.0.1 في سطر الجاهزية) — كشف واجهة
 *     عامة قرار مشغل صريح لا سلوك افتراضي.
 *   • إعادة التشغيل = البصمة نفسها (هوية المفتاح تدوم — تدوير الشهادة حدث
 *     موثق لا صدفة).
 *   • جولة مزامنة حقيقية (تسجيل ← دفع ← دخول بملح السلك ← سحب/دمج) عبر
 *     عميل الإنتاج المثبت ضد العملية المستقلة — الحزمة تخدم العقد.
 *   • فحص الحي من الحزمة نفسها (--check): العقد هو المسبار (404 الرمضاء)،
 *     فوق قناة مثبتة؛ دبوس مغترب → SYNCH_HEALTH_FAIL spki_not_pinned.
 *   • إقلاع مغلقة الفشل: عبارة خاطئة / وسيط مجهول = DEPLOY_REFUSED بلا READY
 *     ورمز خروج غير صفري؛ صنف مفقود من الحزمة = فشل بناء.
 *
 * Verdict: DEPLOYGATE_OK … (exit 0) / DEPLOYGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — تعمل في أي بيئة فيها JRE 8+.
 */
public final class DeployGate {

    /** البصمة المرساة — نفس الثابت المثبت في TlsGate (نفس الشهادة المنشورة):
     *  مكرر عمداً ليقف البوابتان مستقلتين في التجميع ويشتركا في المرساة. */
    static final String EXPECTED_ARENA_PIN =
            "e67e815c3494a3c5a980036b12d97fc67b7c7c317e8ce2de61f6bd3187a9a395";
    static final char[] PASS = "nk-arena".toCharArray();
    static final String ARENA_P12 = "tests/tls/arena-server.p12";
    static final String ROGUE_P12 = "tests/tls/rogue-server.p12";
    static final String JAR = "build/deploy/neokey-sync-server.jar";

    static int passed = 0, failed = 0;
    static int NEG_OK = 0, NEG_TOTAL = 0;
    static long HEALTH_MS = -1;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    static void neg(String name, boolean bitten) {
        NEG_TOTAL++;
        if (bitten) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name + " (attack did not bite)");
    }

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Exception e) {
            System.out.println("DEPLOYGATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (failed > 0 || NEG_OK != NEG_TOTAL) {
            System.out.println("DEPLOYGATE_FAILED checks=" + failed + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
        System.out.println("DEPLOYGATE_OK bundle=1 pins_match=1 restart_stable=1 round=ok"
                + " health_ms=" + HEALTH_MS + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " checks=" + passed);
    }

    // ---- أدوات -------------------------------------------------------------------

    static final class Proc {
        final Process p;
        String ready;                       // أول سطر stdout (سطر الجاهزية)
        final StringBuilder err = new StringBuilder();
        Proc(Process p) { this.p = p; }
    }

    /** يشغّل الجرة بأوسمة معينة ويلتقط سطر الجاهزية بمراقب مهلة 20 ثانية. */
    static Proc boot(String... extra) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("java"); cmd.add("-jar"); cmd.add(JAR);
        for (String a : extra) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        Proc proc = new Proc(p);
        Thread out = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (proc) { if (proc.ready == null) proc.ready = line; }
                }
            } catch (Exception ignore) { }
        });
        Thread errT = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) synchronized (proc) { proc.err.append(line).append('\n'); }
            } catch (Exception ignore) { }
        });
        out.setDaemon(true); errT.setDaemon(true);
        out.start(); errT.start();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (proc) { if (proc.ready != null) return proc; }
            if (!p.isAlive() && proc.ready == null) return proc;
            Thread.sleep(50);
        }
        p.destroyForcibly();
        return proc;
    }

    static int waitFor(Proc pr, int seconds) throws InterruptedException {
        if (!pr.p.waitFor(seconds, TimeUnit.SECONDS)) pr.p.destroyForcibly();
        return pr.p.exitValue();
    }

    static void stop(Proc pr) {
        if (pr != null && pr.p.isAlive()) pr.p.destroy();
        try { if (pr != null) pr.p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
    }

    static byte[] anchorDer() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get(ARENA_P12))) { ks.load(in, PASS); }
        return ks.getCertificate(ks.aliases().nextElement()).getEncoded();
    }

    static String roguePin() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get(ROGUE_P12))) { ks.load(in, PASS); }
        return SpkiPins.pinOf(ks.getCertificate(ks.aliases().nextElement()).getPublicKey().getEncoded());
    }

    /** نتيجة تشغيل --check من الجرة (عملية منفصلة قصيرة العمر). */
    static final class Check {
        final int rc; final String line; final String err;
        Check(int rc, String line, String err) { this.rc = rc; this.line = line; this.err = err; }
    }

    static Check runCheck(String base, String pin, String anchorPath) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("java"); cmd.add("-jar"); cmd.add(JAR);
        cmd.add("--check"); cmd.add(base);
        cmd.add("--pin"); cmd.add(pin);
        cmd.add("--anchor"); cmd.add(anchorPath);
        cmd.add("--keystore"); cmd.add(ARENA_P12);          // مطلوبان للتحليل قبل الفحص
        cmd.add("--password"); cmd.add("nk-arena");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) all.append(line).append('\n');
        }
        int rc = p.waitFor(30, TimeUnit.SECONDS) ? p.exitValue() : -1;
        String outp = all.toString();
        String ok = null, err = null;
        for (String line : outp.split("\n")) {
            if (line.startsWith("SYNCH_HEALTH_OK")) ok = line;
            if (line.startsWith("SYNCH_HEALTH_FAIL")) err = line.trim();
        }
        return new Check(rc, ok != null ? ok : "", err != null ? err : (ok == null ? outp.trim() : ""));
    }

    // ---- الجولة ------------------------------------------------------------------

    static void run() throws Exception {
        // 0) الحزمة موجودة وبُنيت من ci/build_deploy.sh (المرحلة تضمن البناء)
        check("bundle.exists", new File(JAR).isFile() && new File(JAR).length() > 10_000);

        // 1) الإقلاع: سطر الجاهزية + المرساة ثلاثية المصادر + الربط الافتراضي
        byte[] anchor = anchorDer();
        String rogue = roguePin();
        Proc a = null, b = null;
        try {
            a = boot("--keystore", ARENA_P12, "--password", "nk-arena", "--port", "0");
            check("boot.ready.line", a.ready != null && a.ready.startsWith("SYNC_SERVER_READY "));
            String pinA = field(a.ready, "spki");
            int port = Integer.parseInt(field(a.ready, "port"));
            String host = field(a.ready, "host");
            check("boot.pin.anchor", EXPECTED_ARENA_PIN.equals(pinA));   // الجرة == المرساة
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(Paths.get(ARENA_P12))) { ks.load(in, PASS); }
            String fixturePin = SpkiPins.pinOf(ks.getCertificate(ks.aliases().nextElement()).getPublicKey().getEncoded());
            check("boot.pin.fixture", fixturePin.equals(pinA));           // الجرة == المخزن المنشور
            check("boot.loopback.default", "127.0.0.1".equals(host));
            String base = "https://127.0.0.1:" + port + "/v1/";

            // 2) جولة مزامنة حقيقية عبر عميل الإنتاج المثبت ضد العملية المستقلة
            SpkiPins pins = new SpkiPins(pinA);
            SyncRestTransport trA = new SyncRestTransport(base, pins, anchor);
            SyncRestTransport trB = new SyncRestTransport(base, pins, anchor);
            SyncState.Snapshot snap = new SyncState.Snapshot();
            snap.themeIdx = 5;
            snap.settings.put("haptics", "on");
            List<SyncVault.Rec> recs = SyncState.collect(snap, 1, 5000L);
            SyncClient.Session sa = SyncClient.register("عبارة-deploy", null);
            trA.register(sa.keys.accountId, sa.salt);
            SyncClient.PushResult pr = SyncClient.push(sa, trA, recs, "on", "on", true, false);
            check("round.push", pr.bytes > 0);
            SyncClient.Session sb = SyncClient.login(trB, "عبارة-deploy", sa.keys.accountId);
            check("round.salt.from.wire", java.util.Arrays.equals(sb.salt, sa.salt));
            List<SyncVault.Rec> merged = SyncClient.pull(sb, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true);
            SyncState.Plan plan = SyncState.apply(merged);
            check("round.plan.equal", plan.themeIdx == 5 && "on".equals(plan.settings.get("haptics")));

            // 3) فحص الحي من الحزمة نفسها — العقد هو المسبار فوق قناة مثبتة
            File anchorPem = File.createTempFile("nk-anchor", ".pem");
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            Files.write(anchorPem.toPath(), pem(anchor));
            Check ok = runCheck(base, pinA, anchorPem.getAbsolutePath());
            check("health.ok.line", ok.rc == 0 && ok.line.startsWith("SYNCH_HEALTH_OK")
                    && ok.line.contains("code=404"));
            HEALTH_MS = parseMs(ok.line);
            check("health.latency.measured", HEALTH_MS >= 0);

            // 4) دبوس مغترب — الفحص يرفض قناة المهاجم بوسمها الحرفي
            Check mitm = runCheck(base, rogue, anchorPem.getAbsolutePath());
            check("health.wrongpin.tag", mitm.rc != 0 && mitm.err.contains("spki_not_pinned"));
            anchorPem.delete();

            // 5) إعادة التشغيل = البصمة نفسها
            stop(a); a = null;
            b = boot("--keystore", ARENA_P12, "--password", "nk-arena", "--port", "0");
            check("restart.ready", b.ready != null && b.ready.startsWith("SYNC_SERVER_READY "));
            String pinB = field(b.ready, "spki");
            check("restart.pin.stable", EXPECTED_ARENA_PIN.equals(pinB) && !pinB.equals(""));

            // 6) إقلاع مغلقة الفشل: عبارة خاطئة — بلا READY ورمز خروج 3
            Proc bad = boot("--keystore", ARENA_P12, "--password", "WRONG", "--port", "0");
            int rcBad = waitFor(bad, 20);
            neg("boot.wrong.password.fail.closed",
                    rcBad != 0 && bad.ready == null && bad.err.toString().contains("DEPLOY_REFUSED"));
            stop(bad);

            // 7) وسيط مجهول — رفض عند التحليل برمز 2
            Proc unk = boot("--frobnicate");
            int rcUnk = waitFor(unk, 20);
            neg("boot.unknown.arg.refused",
                    rcUnk == 2 && unk.ready == null && unk.err.toString().contains("unknown_arg --frobnicate"));
            stop(unk);

            // 8) فحص حي قبالة منفذ ميت — فشل صريح بلا تجميل
            Check dead = runCheck("https://127.0.0.1:1/v1/", pinA, "/tmp/nk-anchor-does-not-matter.pem");
            neg("health.dead.port.fails", dead.rc != 0 && dead.err.startsWith("SYNCH_HEALTH_FAIL"));
        } finally {
            stop(a); stop(b);
        }
    }

    static String field(String ready, String key) {
        if (ready == null) return "";
        for (String tok : ready.split(" ")) {
            if (tok.startsWith(key + "=")) return tok.substring(key.length() + 1);
        }
        return "";
    }

    static long parseMs(String okLine) {
        try {
            for (String tok : okLine.split(" "))
                if (tok.startsWith("latency_ms=")) return Long.parseLong(tok.substring("latency_ms=".length()));
        } catch (Exception ignore) { }
        return -1;
    }

    static byte[] pem(byte[] der) {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return ("-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
