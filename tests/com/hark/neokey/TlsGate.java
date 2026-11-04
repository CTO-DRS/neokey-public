package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * بوابة قناة TLS المثبتة SPKI — المرحلة 5، الأسبوع 1 (الأسبوع 26).
 *
 * الشريحة المحلية القابلة للإغلاق من المعلق الموثق «نشر خادم مزامنة حي بTLS
 * على نطاق حقيقي» (docs/PHASE4-W4-REPORT.md §5): عقد ثقة العميل يُقاس على
 * السلك لا بمكالمات صنف — خادم TLS حقيقي (SyncServer.startTls، HttpsServer)
 * + عميل الإنتاج الحقيقي (SyncRestTransport بمنشئه المثبت):
 *   • جولة مزامنة كاملة لجهازين عبر TLS حقيقي على 127.0.0.1: تسجيل → دفع
 *     NSF1 → دخول جهاز جديد بملح السلك → سحب/دمج → منح بالدبوس → تثبيت من
 *     الكتالوج — الصفر-المعرفة مقياساً على مخزون الخادم نفسه فوق TLS.
 *   • المرساة: بصمة SPKI للشهادة المنشورة تعاد من الملف حرفياً
 *     (EXPECTED_ARENA_PIN — «المرجع = المنشور»: أي تبديل ملف الشهادة يكسر
 *     المرساة فوراً حتى لو كانت الشهادة البديلة صالحة تقنياً).
 *   • MITM بشهادة مغترة (مفتاح مختلف، CN نفسه): المصافحة نفسها ترفض — لا بايت
 *     طلب واحد يغادر الجهاز، وعلى مستوى النقل يُغلق "network" بلا تسريب تفاصيل.
 *   • مهاجم موثوق لكنه غير مثبت (مرساة مهاجم + دبابيس الضحية): رفض
 *     "spki_not_pinned" من داخل TrustManager.
 *   • فشل مغلق: لا دبابيس، دبوس مشوه، دبوس مكرر، حلبة http في منشئ TLS،
 *     حلبة بنطاق حقيقي، مرساة غائبة، مزامنة إنتاج https بلا دبابيس.
 *   • أرضية البروتوكول: TLSv1.2 فما فوق؛ p50 مصافحة كاملة (سياق جديد لكل
 *     اتصال — لا إعادة استخدام جلسة).
 *
 * Verdict: TLSGATE_OK … (exit 0) / TLSGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — تعمل في أي بيئة فيها JDK 8+.
 */
public final class TlsGate {

    /** البصمة المرساة للشهادة المنشورة tests/tls/arena-server.p12 (المرجع = المنشور). */
    public static final String EXPECTED_ARENA_PIN =
            "e67e815c3494a3c5a980036b12d97fc67b7c7c317e8ce2de61f6bd3187a9a395";
    static final char[] PASS = "nk-arena".toCharArray();
    static final String ARENA_P12 = "tests/tls/arena-server.p12";
    static final String ROGUE_P12 = "tests/tls/rogue-server.p12";

    static int passed = 0, failed = 0;
    static int NEG_OK = 0, NEG_TOTAL = 0;
    static final PaymentProvider SIM = new PaymentProvider.SimProvider();
    static int ROUNDS = 3, BLOBS = -1, WIRE_BYTES = -1, GRANTED = -1, ENTROPY_X100 = -1, PINS = -1;
    static long P50_HS_MS = -1;
    static String PROTOCOL = "", CIPHER = "";

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    static void neg(String name, String expectedTag, String actualTag) {
        NEG_TOTAL++;
        if (expectedTag.equals(actualTag)) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name + " expected=" + expectedTag + " got=" + actualTag);
    }

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Exception e) {
            System.out.println("TLSGATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (failed > 0 || NEG_OK != NEG_TOTAL) {
            System.out.println("TLSGATE_FAILED checks=" + failed + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
        System.out.println("TLSGATE_OK pins=" + PINS + " protocol_floor=TLSv1.2 protocol=" + PROTOCOL
                + " rounds=" + ROUNDS + " blobs=" + BLOBS + " wire_bytes=" + WIRE_BYTES
                + " granted=" + GRANTED + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " entropy_x100=" + ENTROPY_X100 + " p50_handshake_ms=" + P50_HS_MS
                + " cipher=" + CIPHER + " checks=" + passed);
    }

    // ---- أدوات -------------------------------------------------------------------

    static KeyStore loadP12(String path) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path)) { ks.load(in, PASS); }
        return ks;
    }

    static String aliasOf(KeyStore ks) throws Exception {
        java.util.Enumeration<String> e = ks.aliases();
        return e.nextElement();
    }

    /** DER للشهادة (المرساة الذاتية في الحلبة = الشهادة نفسها). */
    static byte[] certDer(KeyStore ks, String alias) throws Exception {
        return ks.getCertificate(alias).getEncoded();
    }

    static byte[] spkiOf(KeyStore ks, String alias) throws Exception {
        return ks.getCertificate(alias).getPublicKey().getEncoded();
    }

    static double entropy(byte[] d) {
        if (d.length == 0) return 0;
        int[] f = new int[256];
        for (byte b : d) f[b & 0xFF]++;
        double e = 0;
        for (int v : f) {
            if (v == 0) continue;
            double p = (double) v / d.length;
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }

    static String refusalTag(Runnable r) {
        try { r.run(); return "NO_REFUSAL"; }
        catch (SyncVault.SyncRefused e) { return e.getMessage(); }
    }

    static byte[] rnd(int n) { byte[] b = new byte[n]; new SecureRandom().nextBytes(b); return b; }

    static boolean wireHas(SyncServer s, String line) {
        for (String w : s.wireLog()) if (w.equals(line)) return true;
        return false;
    }

    static int count(byte[] hay, byte[] needle) {
        int n = 0;
        if (needle.length == 0 || needle.length > hay.length) return 0;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            n++;
        }
        return n;
    }

    static long medianMs(List<Long> xs) {
        Collections.sort(xs);
        return xs.get(xs.size() / 2) / 1_000_000;
    }

    /** اتصال TLS خام بمصنع مثبت — لقياس المصافحة واختيار الهجمات مباشرة. */
    static HttpsURLConnection pinned(String url, byte[] anchorDer, SpkiPins pins) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setSSLSocketFactory(PinnedTls.arenaContext(anchorDer, pins).getSocketFactory());
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        return c;
    }

    static void drain(HttpsURLConnection c) {
        try { InputStream in = c.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0); in.close(); } catch (Exception ignore) {}
        c.disconnect();
    }

    // ---- الجولة على السلك المشفر -------------------------------------------------

    static void run() throws Exception {
        // 0) المرساة: «المرجع = المنشور» — بصمة الشهادة المنشورة تعاد حرفياً
        KeyStore arena = loadP12(ARENA_P12);
        KeyStore rogue = loadP12(ROGUE_P12);
        String arenaAlias = aliasOf(arena), rogueAlias = aliasOf(rogue);
        String arenaPin = SpkiPins.pinOf(spkiOf(arena, arenaAlias));
        String roguePin = SpkiPins.pinOf(spkiOf(rogue, rogueAlias));
        check("fixture.pin.anchor", EXPECTED_ARENA_PIN.equals(arenaPin));
        check("fixture.rogue.distinct", !arenaPin.equals(roguePin) && roguePin.length() == 64);
        byte[] anchorDer = certDer(arena, arenaAlias);

        // 1) اختبر ذاتي الصلابة — تلاعب SpkiPins يُصطاد من هذا التشغيل نفسه
        SpkiPins pins = new SpkiPins(arenaPin);
        check("selftest.verify.good", pins.verify(spkiOf(arena, arenaAlias)));
        check("selftest.verify.rogue", !pins.verify(spkiOf(rogue, rogueAlias)));
        neg("selftest.empty.refused", "no_pins", refusalTag(() -> new SpkiPins()));
        neg("selftest.bad_hex.refused", "bad_pin_hex", refusalTag(() -> new SpkiPins("zz" + arenaPin.substring(2))));
        neg("selftest.dup_pin.refused", "dup_pin", refusalTag(() -> new SpkiPins(arenaPin, arenaPin)));
        byte[] flipped = spkiOf(arena, arenaAlias).clone();
        flipped[10] ^= (byte) 0x55;
        check("selftest.verify.tampered", !pins.verify(flipped));
        PINS = pins.size();

        // 2) الخادم المرجعي الحقيقي فوق TLS + عميل الإنتاج المثبت
        SyncServer server = SyncServer.startTls(ARENA_P12, PASS);
        try {
            String base = server.base();
            check("server.https.base", base.startsWith("https://127.0.0.1:"));
            SyncRestTransport trA = new SyncRestTransport(base, pins, anchorDer);   // جهاز A
            SyncRestTransport trB = new SyncRestTransport(base, pins, anchorDer);   // جهاز B

            // 3) جولة مزامنة كاملة فوق TLS: شراء → دفع → دخول → سحب → منح → تثبيت
            StoreUi.Catalog cat = StoreUi.loadCatalog(
                    new String(java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get("assets/founders_catalog.json")), StandardCharsets.UTF_8));
            check("catalog.strict", cat.packCount() == 25 && cat.vendorIds.size() == 10);
            List<StoreUi.Pack> all = new ArrayList<>(cat.byId.values());
            Collections.sort(all, (x, y) -> x.priceCents != y.priceCents
                    ? Long.compare(x.priceCents, y.priceCents) : x.id.compareTo(y.id));
            StoreUi.Pack p = all.get(0);

            StoreUi.Wallet walletA = new StoreUi.Wallet();
            check("A.topup", walletA.topUp(SIM, 100_00, "tls-a1", 1000L).ok);
            StoreUi.Store storeA = new StoreUi.Store(cat, walletA);
            check("A.buy", storeA.purchase(p.id, 1000L).ok);
            check("A.install", storeA.install(p.id, p.specJson.getBytes(StandardCharsets.UTF_8), 1001L).ok);

            SyncState.Snapshot snapA = new SyncState.Snapshot();
            snapA.themeIdx = 3;
            snapA.dictWords.put("مفتاحية", "ar");
            snapA.entitlements.putAll(storeA.ownedPins());
            List<SyncVault.Rec> stateA = SyncState.collect(snapA, 1, 5000L);

            SyncClient.Session sessA = SyncClient.register("عبارة-tls", null);
            trA.register(sessA.keys.accountId, sessA.salt);
            String acctA = sessA.keys.accountId;
            check("wire.tls.saltPut.201", wireHas(server, "PUT /v1/account/" + acctA + "/salt -> 201"));

            SyncClient.PushResult pr1 = SyncClient.push(sessA, trA, stateA, "on", "on", true, false);
            check("A.push.tls", pr1.bytes > 0);
            check("wire.tls.blobPut.201", wireHas(server, "PUT /v1/account/" + acctA + "/blob/" + pr1.blobId + " -> 201"));
            byte[] stored = server.rawBlob(acctA, pr1.blobId);
            check("blob.nsf1.over.tls", stored != null && stored[0] == 'N' && stored[1] == 'S'
                    && stored[2] == 'F' && stored[3] == '1');
            BLOBS = server.blobCount(acctA);
            check("wire.blob.count", BLOBS == 1);

            SyncClient.Session sessB = SyncClient.login(trB, "عبارة-tls", acctA);
            check("B.salt.over.tls", Arrays.equals(sessB.salt, sessA.salt));
            List<SyncVault.Rec> mergedB = SyncClient.pull(sessB, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true);
            SyncState.Plan planB = SyncState.apply(mergedB);
            check("B.plan.ent", planB.entitlements.size() == 1 && planB.themeIdx == 3);
            StoreUi.Wallet walletB = new StoreUi.Wallet();
            StoreUi.Store storeB = new StoreUi.Store(cat, walletB);
            int grantedB = 0;
            for (Map.Entry<String, String> e : planB.entitlements.entrySet())
                if (storeB.grantFromSync(e.getKey(), e.getValue()).ok) {
                    grantedB++;
                    StoreUi.Pack gp = cat.pack(e.getKey());
                    check("B.install.from.catalog", storeB.install(e.getKey(),
                            gp.specJson.getBytes(StandardCharsets.UTF_8), 2000L).ok);
                }
            GRANTED = grantedB;
            check("B.granted1", grantedB == 1 && storeB.ownedCount() == 1);
            check("B.wallet.intact", walletB.balance() == 0 && storeB.paidCents() == 0);
            check("restoreCode.match", storeB.restoreCode().equals(storeA.restoreCode()));
            WIRE_BYTES = (int) server.usedBytes(acctA);
            check("wire.bytes.positive", WIRE_BYTES > 0);

            // 4) مطابقة العقد من سجل السلك فوق TLS
            check("wire.five.shapes", wireHas(server, "GET /v1/account/" + acctA + "/salt -> 200")
                    && wireHas(server, "PUT /v1/account/" + acctA + "/salt -> 201")
                    && wireHas(server, "GET /v1/account/" + acctA + "/blobs -> 200")
                    && wireHas(server, "GET /v1/account/" + acctA + "/blob/" + pr1.blobId + " -> 200")
                    && wireHas(server, "PUT /v1/account/" + acctA + "/blob/" + pr1.blobId + " -> 201"));
            boolean badId = false;
            for (String line : server.wireLog()) {
                String pp = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(" -> "));
                String[] seg = pp.split("/");
                if (seg.length >= 3 && "v1".equals(seg[0]) && "account".equals(seg[1])
                        && !SyncServer.isB64url(seg[2])) badId = true;
            }
            check("wire.b64url.ids", !badId);

            // 5) الصفر-المعرفة فوق TLS: الخادم لا يزى عمياء لا معنى له —
            // القياس بعد كل الجولات ليكون على عينة ذات معنى (القسم 7 يغذيها)
            byte[] dump = server.dump();
            check("zeroknowledge.no.passphrase", count(dump, "عبارة-tls".getBytes(StandardCharsets.UTF_8)) == 0);
            check("zeroknowledge.no.dictword", count(dump, "مفتاحية".getBytes(StandardCharsets.UTF_8)) == 0);
            check("zeroknowledge.no.kindstring", count(dump, "entitlement".getBytes(StandardCharsets.US_ASCII)) == 0);

            // 6) البروتوكول والتشفير وp50 مصافحة كاملة (سياق جديد لكل اتصال)
            HttpsURLConnection hc = pinned(base + "account/" + acctA + "/salt", anchorDer, pins);
            check("handshake.200", hc.getResponseCode() == 200);
            CIPHER = hc.getCipherSuite();
            PROTOCOL = PinnedTls.LAST_PROTOCOL;
            drain(hc);
            check("protocol.floor", "TLSv1.3".equals(PROTOCOL) || "TLSv1.2".equals(PROTOCOL));
            check("cipher.present", CIPHER != null && CIPHER.length() > 4 && !CIPHER.contains("NULL"));
            List<Long> hs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                HttpsURLConnection m = pinned(base + "account/" + acctA + "/salt", anchorDer, pins);
                m.getResponseCode();
                drain(m);
                hs.add(System.nanoTime() - t0);
            }
            P50_HS_MS = medianMs(hs);
            check("handshake.p50.measured", P50_HS_MS >= 0);

            // 7) جولات إضافية فوق نفس القناة: دفع/سحب متكرر يثبت ثبات القناة
            int extra = 0;
            for (int i = 0; i < ROUNDS - 1; i++) {
                SyncState.Snapshot s2 = new SyncState.Snapshot();
                s2.settings.put("haptics", "on");
                List<SyncVault.Rec> recs = SyncState.collect(s2, 3 + i, 6000L + i);
                SyncClient.PushResult pr = SyncClient.push(sessA, trA, recs, "on", "on", true, false);
                if (pr.bytes > 0 && wireHas(server, "PUT /v1/account/" + acctA + "/blob/" + pr.blobId + " -> 201"))
                    extra++;
                SyncClient.pull(sessB, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true);
            }
            check("rounds.stable", extra == ROUNDS - 1);
            BLOBS = server.blobCount(acctA);

            // 5-تكملة) إنتروبيا المخزون على العينة الكاملة بعد كل الجولات —
            // العينة هنا صغيرة (~600B: معرف+ملح+معرفات كتل+أكتال مختومة)، أرضية
            // 7.30 معايرة لها عادلة (نص/JSON يقارب 4.5-5.5؛ القيم الأرسية تحمل
            // ضمان النمط، والإنتروبيا تحمل الغموض)
            byte[] fullDump = server.dump();
            int e100 = (int) Math.round(entropy(fullDump) * 100);
            ENTROPY_X100 = e100;
            System.out.println("TLSGATE_INFO entropy_x100=" + e100 + " dump_bytes=" + fullDump.length);
            check("zeroknowledge.entropy", e100 >= 730);
            check("zeroknowledge.dump.meaningful", fullDump.length >= 512);
        } finally {
            server.stop();
        }

        // 8) MITM: خادم مغترب (CN نفسه، مفتاح مختلف) — المصافحة ترفض قبل أي بايت
        SyncServer rogueServer = SyncServer.startTls(ROGUE_P12, PASS);
        try {
            String rogueBase = rogueServer.base();
            // 8a) دبابيس الضحية + مرساة الضحية قبالة خادم المهاجم: السلسلة غير موثوقة
            String tNetwork = refusalTag(() -> {
                SyncRestTransport tr = new SyncRestTransport(rogueBase, pins, anchorDer);
                tr.saltOf("AAAAAAAAAAAAAAAAAAAAAA");
            });
            neg("mitm.transport.closes.as.network", "network", tNetwork);
            // 8b) المهاجم موثوق (مرساة المهاجم) لكن مفتاحه غير مثبت: الوسم الحرفي
            byte[] rogueAnchor = certDer(rogue, rogueAlias);
            String unpinned = "";
            try {
                HttpsURLConnection c = pinned(rogueBase + "account/x/salt", rogueAnchor, pins);
                c.getResponseCode();
                drain(c);
                unpinned = "NO_EXCEPTION";
            } catch (javax.net.ssl.SSLException e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                unpinned = root instanceof CertificateException ? root.getMessage() : e.getClass().getSimpleName();
            } catch (Exception e) {
                unpinned = e.getClass().getSimpleName();
            }
            neg("trusted.mitm.spki_not_pinned", "spki_not_pinned", unpinned);
            // 8c) العميل المثبت لن يرسل شيئاً: مخزون المهاجم فارغ تماماً بعد المحاولتين
            check("mitm.no.bytes.delivered", rogueServer.blobCount("AAAAAAAAAAAAAAAAAAAAAA") <= 0
                    && rogueServer.dump().length == 0);
        } finally {
            rogueServer.stop();
        }

        // 9) فشل مغلق في المنشئات — كل حالة غامضة تُرفض بوسمها الحرفي
        neg("ctor.http.in.tls.arena", "not_arena", refusalTag(() -> new SyncRestTransport("http://127.0.0.1:1/v1/", pins, anchorDer)));
        neg("ctor.real.domain.in.arena", "not_arena", refusalTag(() -> new SyncRestTransport("https://sync.neokey.app/v1/", pins, anchorDer)));
        neg("ctor.null.pins", "no_pins", refusalTag(() -> new SyncRestTransport("https://127.0.0.1:1/v1/", null, anchorDer)));
        neg("ctor.null.anchor", "no_trust_anchor", refusalTag(() -> new SyncRestTransport("https://127.0.0.1:1/v1/", pins, null)));
        neg("pinnedtls.no.anchor", "no_trust_anchor", refusalTag(() -> {
            try { PinnedTls.arenaContext(null, pins); } catch (SyncVault.SyncRefused e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        }));

        // 10) الإنتاج: https بلا دبابيس = مزامنة مغلقة؛ بالدبابيس = يُبنى
        NetGate.clearSyncPins();
        neg("prod.https.unpinned.closed", "https_unpinned", refusalTag(SyncRestTransport::new));
        NetGate.setSyncPins(arenaPin);
        SyncClient.Transport prod = new SyncRestTransport();   // يُبنى فقط — لا طلب حقيقي لنطاق النشر هنا
        check("prod.pinned.constructs", prod != null);
        NetGate.clearSyncPins();

        // 11) نقاء البوابات: الصنفان الجديدان صفر android.* (عقد معماري) —
        // على الشيفرة لا التعليقات: تُقتطع أسطر /** */ و* و// ثم يُفحص النقي.
        String pinsSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/com/hark/neokey/SpkiPins.java")), StandardCharsets.UTF_8);
        String tlsSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/com/hark/neokey/PinnedTls.java")), StandardCharsets.UTF_8);
        check("purity.spkipins.no.android", !codeLines(pinsSrc).contains("android"));
        check("purity.pinnedtls.no.android", !codeLines(tlsSrc).contains("android"));
    }

    /** أسطر الشيفرة الفعلية (بلا تعليقات كتلية أو سطرية) — عليه يُفحص النقاء. */
    static String codeLines(String src) {
        StringBuilder sb = new StringBuilder();
        boolean block = false;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (block) { if (t.contains("*/")) block = false; continue; }
            if (t.startsWith("/*")) { block = !t.contains("*/") || t.endsWith("*/") && !t.startsWith("*/"); continue; }
            if (t.startsWith("*") || t.startsWith("//")) continue;
            int ci = t.indexOf("//");
            sb.append(ci >= 0 ? t.substring(0, ci) : t).append('\n');
        }
        return sb.toString();
    }
}
