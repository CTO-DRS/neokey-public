package com.hark.neokey.deploy;

import com.hark.neokey.PinnedTls;
import com.hark.neokey.SpkiPins;
import com.hark.neokey.SyncServer;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;

/**
 * قاذف حزمة نشر خادم المزامنة المرجعي — المرحلة 5، الأسبوع 27.
 *
 * الحزمة هي الشريحة القابلة للإغلاق محلياً من المعلق «نشر خادم مزامنة حي»:
 * الجرة المستقلة نفسها التي سيشغّلها مشغل النشر، فوق منطق العقد المرجعي
 * (SyncServer — نفس الصنف الذي ثبتت بوابتا W23/W26 عقدَه على السلك) لا نسخة
 * موازية منه. لا كود تطبيق ولا dex: deploy/ وtests/ خارج التطبيق بالتصميم.
 *
 * الأنماط الثلاثة (فشل مغلق — لا جاهزية غامضة):
 *   serve (الافتراضي): --keystore PKCS12 --password PASS [--host H] [--port N]
 *       يطبع سطر جاهزية واحد ثم يخدم إلى الأبد:
 *       SYNC_SERVER_READY port=N host=H spki=64hex
 *       الافتراضي loopback — كشف 0.0.0.0 قرار مشغل صريح بعلامة --host.
 *   --print-spki: يطبع SYNC_SERVER_SPKI <64hex> ويخرج — البصمة التي يضبطها
 *       النشر في NetGate.setSyncPins («المرجع = المنشور»).
 *   --check BASE --pin HEX [--anchor CERT]: فحص حي فوق قناة مثبتة — العقد
 *       نفسه هو الفحص: GET حساب-فحص/ملح المتوقع 404 (الرمضاء الرمادي = حي)،
 *       SYNCH_HEALTH_OK base=... latency_ms=N / SYNCH_HEALTH_FAIL <وسم>.
 *
 * كل خطأ قبل سطر الجاهزية = رسالة DEPLOY_REFUSED <وسم> على stderr ورمز خروج
 * غير صفري (عبارة خاطئة، ملف غائب، وسيط مجهول، مخزن بلا مفتاح) — لا يشغّل
 * منفذاً ولا يطبع READY أبداً.
 */
public final class DeployMain {

    private static void refused(String tag) {
        System.err.println("DEPLOY_REFUSED " + tag);
        System.exit(2);
    }

    /** DER لشهادة من ملف PEM أو DER — لمرساة فحص الحي. */
    private static byte[] certDer(String path) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(path));
        String s = new String(raw, StandardCharsets.US_ASCII);
        if (s.contains("-----BEGIN")) {
            String b64 = s.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            return java.util.Base64.getDecoder().decode(b64);
        }
        return raw;
    }

    /** بصمة SPKI لمفتاح أول مدخل شهادة في المخزن. */
    static String spkiOf(KeyStore ks) throws Exception {
        java.util.Enumeration<String> e = ks.aliases();
        while (e.hasMoreElements()) {
            Certificate c = ks.getCertificate(e.nextElement());
            if (c != null) return SpkiPins.pinOf(c.getPublicKey().getEncoded());
        }
        throw new IllegalStateException("no_cert");
    }

    public static void main(String[] args) {
        String keystore = null, password = null, host = "127.0.0.1", check = null, pin = null, anchor = null;
        int port = 0;
        boolean printSpki = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--keystore":   keystore = next(args, ++i, a); break;
                case "--password":   password = next(args, ++i, a); break;
                case "--host":       host = next(args, ++i, a); break;
                case "--port":       String p = next(args, ++i, a);
                                     try { port = Integer.parseInt(p); }
                                     catch (NumberFormatException e) { refused("bad_port " + p); }
                                     if (port < 0 || port > 65535) refused("bad_port " + p);
                                     break;
                case "--print-spki": printSpki = true; break;
                case "--check":      check = next(args, ++i, a); break;
                case "--pin":        pin = next(args, ++i, a); break;
                case "--anchor":     anchor = next(args, ++i, a); break;
                default:             refused("unknown_arg " + a);
            }
        }
        if (keystore == null || password == null) refused("missing_arg --keystore/--password");

        KeyStore ks;
        try {
            ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(keystore)) { ks.load(in, password.toCharArray()); }
        } catch (Exception e) {
            System.err.println("DEPLOY_REFUSED keystore_load " + e.getClass().getSimpleName());
            System.exit(3);
            return;
        }
        String spki;
        try { spki = spkiOf(ks); }
        catch (Exception e) { refused("keystore_no_key " + e.getMessage()); return; }

        if (printSpki) {
            System.out.println("SYNC_SERVER_SPKI " + spki);
            return;
        }

        if (check != null) {
            health(check, pin, anchor, spki);
            return;
        }

        // الخدمة: بلا READY قبل نجاح الربط، وبعده سطر واحد ثم الصمت (سجل النشر
        // أدوات المشغل لا stdout الحزمة).
        try {
            SyncServer server = SyncServer.startTlsAt(host, port, keystore, password.toCharArray());
            System.out.println("SYNC_SERVER_READY port=" + server.port()
                    + " host=" + server.host() + " spki=" + spki);
            System.out.flush();
            Thread.currentThread().join();                     // يخدم حتى الإشارة
        } catch (Throwable t) {
            System.err.println("DEPLOY_REFUSED serve " + t.getClass().getSimpleName());
            System.exit(4);
        }
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) refused("missing_value " + flag);
        return args[i];
    }

    /** فحص حي فوق قناة مثبتة — العقد نفسه هو المسبار (404 الرمضاء = حي). */
    private static void health(String base, String pinHex, String anchorPath, String serverSpki) {
        long t0 = System.nanoTime();
        try {
            SpkiPins pins = new SpkiPins(pinHex != null ? pinHex : serverSpki);
            HttpsURLConnection c = (HttpsURLConnection) new URL(base + "account/healthprobe0/salt").openConnection();
            c.setSSLSocketFactory((anchorPath != null
                    ? PinnedTls.arenaContext(certDer(anchorPath), pins)
                    : PinnedTls.systemContext(pins)).getSocketFactory());
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (code == 404 || code == 200) {                  // العقد أجاب — الخادم حي
                System.out.println("SYNCH_HEALTH_OK base=" + base + " code=" + code + " latency_ms=" + ms);
                return;
            }
            System.err.println("SYNCH_HEALTH_FAIL http_" + code);
            System.exit(1);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            String tag = root instanceof java.security.cert.CertificateException
                    ? root.getMessage() : t.getClass().getSimpleName();
            System.err.println("SYNCH_HEALTH_FAIL " + tag);
            System.exit(1);
        }
    }

    private DeployMain() { throw new UnsupportedOperationException(); }
}
