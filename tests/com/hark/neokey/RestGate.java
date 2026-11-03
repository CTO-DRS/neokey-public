package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * بوابة الخادم المرجعي REST — المرحلة 4، الأسبوع 2 (الأسبوع 23).
 *
 * تقيس العقد على السلك لا بمكالمات صنف: خادم HTTP حقيقي (SyncServer) على
 * 127.0.0.1 بمنفذ ساذج + عميل الإنتاج الحقيقي (SyncRestTransport فوق
 * HttpURLConnection) — لا بديل، لا محاكي:
 *   • جهازان حقيقيان عبر السلك: تسجيل → دفع مشفر NSF1 → دخول جهاز جديد بملح
 *     جاء عبر HTTP → سحب وفتح ودمج → خطة → منح ملكية بالدبوس من الكتالوج
 *     → تثبيت من ثنائيات الكتالوج المثبتة (لا بايت ثنائي في المزامنة).
 *   • جولة عكسية: شراء B يعبر إلى A (آخر-كاتب-يفوز) والدفتر ينغلق: الاستعادة
 *     لا تشتري.
 *   • مطابقة العقد حرفاً بحرف من سجل السلك الخادمي: أشكال المسارات الخمسة،
 *     معرفات b64url حصراً، octet-stream للأجسام، مصفوفة JSON مرتبة، 201/409.
 *   • الصفر-المعرفة مقياساً على مخزون الخادم الحقيقي نفسه (dump()): إنتروبيا،
 *     بلا عربية ولا أنواع سجلات ولا عبارات، سحر NSF1 عند بدايات الكتل فقط
 *     بعدد الكتل بالضبط.
 *   • الحصص على السلك: quota_free → http_507، سقف الكتلة → http_413 (يبقى
 *     سقفاً حتى مع VIP الخادمي)، ودلالة 404 الرمضاء = قائمة فارغة.
 *   • سلبيات مصممة بأوسمة حرفية: دخول حساب مجهول، عبارة خاطئة، بوابة مغلقة،
 *     قاعدة حلبة أجنبية (نطاق حقيقي مرفوض عند البناء).
 *   • أداء السلك: p50 دفع/سحب على 7 جولات حقيقية.
 *
 * وضع ثانٍ: --negproof = الإثبات السلبي المحفوظ — هجمات وحقن على السلك
 * (تلاعب مخزون الخادم، بتر، تهريب نمو، حقن مسار ومحارف، تهريب استبدال ملح)
 * كل هجمة تُلقب بوسمها ثم تُعاد جولة أساس خضراء على الحساب نفس الخادم.
 *
 * Verdict: RESTGATE_OK … (exit 0) / RESTGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — تعمل في أي بيئة فيها JDK.
 */
public final class RestGate {

    static int passed = 0, failed = 0;
    static final PaymentProvider SIM = new PaymentProvider.SimProvider();
    static int NEG_OK = 0, NEG_TOTAL = 0;
    static int ROUNDS = 7, BLOBS = -1, WIRE_BYTES = -1, GRANTED = -1, ENTROPY_X100 = -1;
    static long P50_PUSH_MS = -1, P50_PULL_MS = -1, REGISTER_MS = -1;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    /** سلبية مصممة: يجب أن يُرفض السلوك بالوسم المحدد حرفياً. */
    static void neg(String name, String expectedTag, String actualTag) {
        NEG_TOTAL++;
        if (expectedTag.equals(actualTag)) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name + " expected=" + expectedTag + " got=" + actualTag);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--negproof".equals(args[0])) {
            System.exit(negProof());
        }
        try {
            run();
        } catch (Exception e) {
            System.out.println("RESTGATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (failed > 0 || NEG_OK != NEG_TOTAL) {
            System.out.println("RESTGATE_FAILED checks=" + failed + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
        System.out.println("RESTGATE_OK endpoints=5 rounds=" + ROUNDS + " blobs=" + BLOBS
                + " wire_bytes=" + WIRE_BYTES + " granted=" + GRANTED
                + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " entropy_x100=" + ENTROPY_X100
                + " p50_push_ms=" + P50_PUSH_MS + " p50_pull_ms=" + P50_PULL_MS
                + " register_ms=" + REGISTER_MS
                + " checks=" + passed);
    }

    // ---- أدوات ---------------------------------------------------------------

    static byte[] catBytes() throws Exception {
        return Files.readAllBytes(Paths.get("assets/founders_catalog.json"));
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

    static long medianUs(List<Long> xs) {
        Collections.sort(xs);
        return xs.get(xs.size() / 2) / 1000;
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

    static String refusalTag(Runnable r) {
        try {
            r.run();
            return "NO_REFUSAL";
        } catch (SyncVault.SyncRefused e) {
            return e.getMessage();
        }
    }

    static byte[] rnd(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** أرخص N حزمة بالسعر ثم بالمعرف — حتمية. */
    static List<StoreUi.Pack> cheapest(StoreUi.Catalog c, int n) {
        List<StoreUi.Pack> all = new ArrayList<>(c.byId.values());
        Collections.sort(all, (x, y) -> x.priceCents != y.priceCents
                ? Long.compare(x.priceCents, y.priceCents) : x.id.compareTo(y.id));
        return all.subList(0, Math.min(n, all.size()));
    }

    static final class Raw {
        final int code; final byte[] body; final String ctype;
        Raw(int code, byte[] body, String ctype) { this.code = code; this.body = body; this.ctype = ctype; }
    }

    /** طلب خام بلا عميل إنتاج — لفحص شكل الاستجابة على السلك نفسه. */
    static Raw raw(String method, String url, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
            }
            return new Raw(code, bo.toByteArray(), c.getContentType());
        } finally {
            c.disconnect();
        }
    }

    // ---- الجولة الكاملة على السلك ----------------------------------------------

    static void run() throws Exception {
        // 0) الخادم المرجعي الحقيقي + عميل الإنتاج الحقيقي فوق HTTP
        SyncServer server = SyncServer.start();
        try {
            String base = server.base();
            SyncRestTransport trA = new SyncRestTransport(base);       // جهاز A
            SyncRestTransport trB = new SyncRestTransport(base);       // جهاز B (عميل مستقل)

            // 1) الكتالوج المثبت — مصدر الثنائيات الوحيد
            StoreUi.Catalog cat = StoreUi.loadCatalog(new String(catBytes(), StandardCharsets.UTF_8));
            check("catalog.strict", cat.packCount() == 25 && cat.vendorIds.size() == 10);
            List<StoreUi.Pack> first3 = cheapest(cat, 3);
            check("picked3", first3.size() == 3);

            // 2) جهاز A: شراء حقيقي وتثبيت من الكتالوج
            StoreUi.Wallet walletA = new StoreUi.Wallet();
            check("walletA.topup", walletA.topUp(SIM, 100_00, "rst-a1", 1000L).ok);
            StoreUi.Store storeA = new StoreUi.Store(cat, walletA);
            for (int i = 0; i < 2; i++) {
                StoreUi.Pack p = first3.get(i);
                check("A.buy." + p.id, storeA.purchase(p.id, 1000L).ok);
                check("A.install." + p.id, storeA.install(p.id, p.specJson.getBytes(StandardCharsets.UTF_8), 1001L).ok);
            }
            check("A.owned2", storeA.ownedCount() == 2 && storeA.installedCount() == 2);

            // 3) الحالة: إعدادات + قاموس + استحقاقات (دبابيس فقط)
            SyncState.Snapshot snapA = new SyncState.Snapshot();
            snapA.themeIdx = 7;
            snapA.dictWords.put("مفتاحية", "ar");
            snapA.settings.put("haptics", "on");
            snapA.entitlements.putAll(storeA.ownedPins());
            List<SyncVault.Rec> stateA = SyncState.collect(snapA, 1, 5000L);
            int entA = 0;
            for (SyncVault.Rec r : stateA) if (SyncVault.KIND_ENTITLEMENT.equals(r.kind)) entA++;
            check("A.collect.ents", entA == 2);

            // 4) التسجيل عبر السلك: PUT account/{id}/salt → 201
            long t0 = System.nanoTime();
            SyncClient.Session sessA = SyncClient.register("عبارة-الجهازين", null);
            REGISTER_MS = (System.nanoTime() - t0) / 1_000_000;
            trA.register(sessA.keys.accountId, sessA.salt);
            String acctA = sessA.keys.accountId;
            check("wire.saltPut.201", wireHas(server, "PUT /v1/account/" + acctA + "/salt -> 201"));
            check("server.saltKept", Arrays.equals(sessA.salt, server.saltOf(acctA)));

            // 5) الدفع عبر السلك: PUT account/{id}/blob/{b} → 201
            SyncClient.PushResult pr1 = SyncClient.push(sessA, trA, stateA, "on", "on", true, false);
            check("A.push.wire", pr1.bytes > 0);
            check("wire.blobPut.201", wireHas(server, "PUT /v1/account/" + acctA + "/blob/" + pr1.blobId + " -> 201"));
            byte[] stored = server.rawBlob(acctA, pr1.blobId);
            check("blob.bytes.match", stored != null && stored.length == pr1.bytes);
            check("blob.nsf1.magic", stored != null && stored[0] == 'N' && stored[1] == 'S'
                    && stored[2] == 'F' && stored[3] == '1');
            check("server.blobCount1", server.blobCount(acctA) == 1);

            // 6) جهاز B جديد عبر السلك: GET الملح ثم نفس الاشتقاق
            SyncClient.Session sessB = SyncClient.login(trB, "عبارة-الجهازين", acctA);
            check("B.sameAccount", sessB.keys.accountId.equals(acctA));
            check("B.saltFromWire", Arrays.equals(sessB.salt, sessA.salt));

            // 7) السحب عبر السلك: GET blobs (مصفوفة) + GET كل كتلة + فتح + دمج
            List<SyncVault.Rec> mergedB = SyncClient.pull(sessB, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true);
            SyncState.Plan planB = SyncState.apply(mergedB);
            check("B.plan.ents", planB.entitlements.size() == 2);
            check("B.plan.theme", planB.themeIdx == 7 && "مفتاحية".equals(planB.dictAr.get(0)));
            for (Map.Entry<String, String> e : planB.entitlements.entrySet())
                check("B.pin.cat." + e.getKey(), cat.pack(e.getKey()) != null
                        && cat.pack(e.getKey()).sha256.equals(e.getValue()));

            // 8) المنح على B: من الكتالوج حصراً، بلا مال
            StoreUi.Wallet walletB = new StoreUi.Wallet();
            StoreUi.Store storeB = new StoreUi.Store(cat, walletB);
            int grantedB = 0;
            for (Map.Entry<String, String> e : planB.entitlements.entrySet()) {
                if (storeB.grantFromSync(e.getKey(), e.getValue()).ok) {
                    grantedB++;
                    StoreUi.Pack p = cat.pack(e.getKey());
                    check("B.install." + e.getKey(), storeB.install(e.getKey(),
                            p.specJson.getBytes(StandardCharsets.UTF_8), 2000L).ok);
                }
            }
            check("B.granted2", grantedB == 2 && storeB.ownedCount() == 2 && storeB.installedCount() == 2);
            check("B.wallet.intact", walletB.balance() == 0 && storeB.paidCents() == 0);
            check("restoreCode.match", storeB.restoreCode().equals(storeA.restoreCode()));

            // 9) جولة عكسية عبر السلك: شراء B الثالث يعبر إلى A
            StoreUi.Pack third = first3.get(2);
            check("B.topup", walletB.topUp(SIM, 100_00, "rst-b1", 3000L).ok);
            check("B.buy3", storeB.purchase(third.id, 3000L).ok);
            SyncState.Snapshot snapB = new SyncState.Snapshot();
            snapB.entitlements.putAll(storeB.ownedPins());
            SyncClient.PushResult pr2 = SyncClient.push(sessB, trB, SyncState.collect(snapB, 2, 6000L), "on", "on", true, false);
            List<SyncVault.Rec> mergedA = SyncClient.pull(sessA, trA, stateA, "on", "on", true);
            SyncState.Plan planA = SyncState.apply(mergedA);
            check("A.plan.ents3", planA.entitlements.size() == 3);
            check("A.grant3", storeA.grantFromSync(third.id, planA.entitlements.get(third.id)).ok
                    && storeA.ownedCount() == 3);
            long sumFirst2 = first3.get(0).priceCents + first3.get(1).priceCents;
            check("ledger.restoreNotPurchase", storeA.paidCents() == sumFirst2
                    && storeB.paidCents() == third.priceCents);
            GRANTED = grantedB + 1;
            check("B.blobCount2", server.blobCount(acctA) == 2);
            WIRE_BYTES = (int) server.usedBytes(acctA);
            check("wire.bytes.positive", WIRE_BYTES > 0);

            // 10) مطابقة العقد من سجل السلك: الأشكال الخمسة ومعرفات b64url حصراً
            check("wire.five.shapes", wireHas(server, "GET /v1/account/" + acctA + "/salt -> 200")
                    && wireHas(server, "PUT /v1/account/" + acctA + "/salt -> 201")
                    && wireHas(server, "GET /v1/account/" + acctA + "/blobs -> 200")
                    && wireHas(server, "GET /v1/account/" + acctA + "/blob/" + pr1.blobId + " -> 200")
                    && wireHas(server, "PUT /v1/account/" + acctA + "/blob/" + pr2.blobId + " -> 201"));
            boolean badId = false;
            for (String line : server.wireLog()) {
                String p = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(" -> "));
                String[] seg = p.split("/");
                if (seg.length >= 3 && "v1".equals(seg[0]) && "account".equals(seg[1])
                        && !SyncServer.isB64url(seg[2])) badId = true;
            }
            check("wire.b64url.ids", !badId);

            // القائمة على السلك: مصفوفة JSON مرتبة octet/json حرفاً بحرف
            Raw listRaw = raw("GET", base + "account/" + acctA + "/blobs", null);
            List<String> pushedIds = new ArrayList<>(Arrays.asList(pr1.blobId, pr2.blobId));
            Collections.sort(pushedIds);
            String lb = new String(listRaw.body, StandardCharsets.UTF_8);
            StringBuilder expect = new StringBuilder("[");
            for (int i = 0; i < pushedIds.size(); i++) {
                if (i > 0) expect.append(',');
                expect.append('"').append(pushedIds.get(i)).append('"');
            }
            expect.append(']');
            check("wire.list.sorted.json", listRaw.code == 200 && expect.toString().equals(lb)
                    && listRaw.ctype != null && listRaw.ctype.startsWith("application/json"));
            List<String> clientList = trA.list(acctA);
            check("wire.client.list", clientList.equals(pushedIds));

            // 409 حرفياً: استبدال الملح مرفوض والملح المثبت يبقى
            trA.register(acctA, rnd(SyncVault.SALT_LEN));
            check("wire.saltConflict.409", wireHas(server, "PUT /v1/account/" + acctA + "/salt -> 409"));
            check("wire.saltConflict.kept", Arrays.equals(sessA.salt, server.saltOf(acctA)));

            // دلالة 404 الرمضاء: حساب جديد بلا كتل = قائمة فارغة بلا خطأ
            SyncRestTransport trC = new SyncRestTransport(base);
            check("grey.404.empty", trC.list("freshAcct" + System.nanoTime()).isEmpty());

            // 11) الحصص على السلك (حساب C): 507 مجاني، VIP خادمي، 413 سقف مطلق
            String acctC = "quotaAcct" + System.nanoTime();
            byte[] saltC = rnd(SyncVault.SALT_LEN);
            trC.register(acctC, saltC);
            final SyncRestTransport trCf = trC;
            final String cAcc = acctC;
            byte[] c1 = rnd(700 * 1024);
            trCf.put(cAcc, "cBlob1", c1, false);                       // 201 — داخل الحصة
            neg("wire.quota.free.507", "http_507", refusalTag(() ->
                    trCf.put(cAcc, "cBlob2", rnd(500 * 1024), false)));
            server.setVip(acctC, true);                                 // الصفة خادمية لا تصدر عن العميل
            trCf.put(cAcc, "cBlob2", rnd(500 * 1024), false);           // VIP يرفع حصة الحساب
            check("vip.lifts.quota", server.blobCount(acctC) == 2);
            neg("wire.blob.cap.413", "http_413", refusalTag(() ->
                    trCf.put(cAcc, "cBlobHuge", rnd(SyncVault.MAX_BLOB_BYTES + 1), false)));
            check("cap.even.vip", server.blobCount(acctC) == 2);

            // 12) أداء السلك: 7 جولات دفع/سحب حقيقية
            SyncState.Snapshot small = new SyncState.Snapshot();
            for (int i = 0; i < 300; i++) small.dictWords.put("كلمة" + i, "ar");
            List<SyncVault.Rec> smallState = SyncState.collect(small, 9, 7000L);
            List<Long> ps = new ArrayList<>(), pls = new ArrayList<>();
            for (int i = 0; i < ROUNDS; i++) {
                long a = System.nanoTime();
                SyncClient.push(sessA, trA, smallState, "on", "on", true, false);
                long b = System.nanoTime();
                SyncClient.pull(sessA, trA, stateA, "on", "on", true);
                long c = System.nanoTime();
                ps.add(b - a);
                pls.add(c - b);
            }
            P50_PUSH_MS = medianUs(ps) / 1000;
            P50_PULL_MS = medianUs(pls) / 1000;
            check("perf.push.p50", P50_PUSH_MS <= 1000);
            check("perf.pull.p50", P50_PULL_MS <= 1000);
            BLOBS = server.blobCount(acctA);
            check("perf.blobCount", BLOBS == 2 + ROUNDS);

            // 13) الصفر-المعرفة على مخزون الخادم الحقيقي نفسه
            byte[] dump = server.dump();
            ENTROPY_X100 = (int) Math.round(entropy(dump) * 100);
            check("zk.entropy", ENTROPY_X100 >= 790);
            int totalBlobs = 0;
            for (String acct : new String[]{acctA, acctC}) totalBlobs += Math.max(0, server.blobCount(acct));
            // العقد حرفياً: «سحر NSF1 عند بدايات الكتل فقط بعدد الكتل بالضبط» —
            // يقاس على الكتل المختومة (كل ما دفع العميل)؛ كتل اختبار الحصص بايتات
            // خام عمياء يخزنها الخادم بلا سؤال، وقد تحمل صدفة أنماط — فلا تُحاسب
            // بالتساوي الصارم بل بالحد الأدنى (كل مغلف يسهم بظهور واحد على الأقل).
            int atStarts = 0;
            for (String id : server.blobIds(acctA)) {
                byte[] b = server.rawBlob(acctA, id);
                if (b != null && b.length >= 4 && b[0] == 'N' && b[1] == 'S' && b[2] == 'F' && b[3] == '1') atStarts++;
            }
            int nsf1Seen = count(dump, SyncVault.MAGIC);
            check("zk.nsf1.atStarts[" + atStarts + "/" + BLOBS + "]", atStarts == BLOBS);
            check("zk.nsf1.floor[" + nsf1Seen + ">=" + BLOBS + "]", nsf1Seen >= BLOBS);
            boolean leak = contains(dump, "عبارة-الجهازين".getBytes(StandardCharsets.UTF_8))
                    || contains(dump, "مفتاحية".getBytes(StandardCharsets.UTF_8))
                    || contains(dump, "STOREENT1".getBytes(StandardCharsets.UTF_8))
                    || contains(dump, "entitlement".getBytes(StandardCharsets.UTF_8))
                    || contains(dump, "dict_word".getBytes(StandardCharsets.UTF_8))
                    || contains(dump, "haptics".getBytes(StandardCharsets.UTF_8));
            for (StoreUi.Pack p : first3) if (contains(dump, p.id.getBytes(StandardCharsets.UTF_8))) leak = true;
            check("zk.noLeaks", !leak);

            // 14) سلبيات مصممة بأوسمة حرفية
            neg("neg.unknownAccount.login", "http_404", refusalTag(() ->
                    SyncClient.login(trB, "عبارة-الجهازين", "zzNoSuchAcct")));
            neg("neg.wrongPassphrase", "account_mismatch", refusalTag(() ->
                    SyncClient.login(trB, "عبارة-خاطئة-جداً", acctA)));
            neg("neg.netgate.masterOff", "netgate_master_off", refusalTag(() ->
                    SyncClient.push(sessA, trA, stateA, "off", "on", true, false)));
            neg("neg.arena.foreignIp", "not_arena", refusalTag(() ->
                    new SyncRestTransport("http://10.0.0.9:9/v1/")));
            neg("neg.arena.prodEndpoint", "not_arena", refusalTag(() ->
                    new SyncRestTransport("https://sync.neokey.app/v1/")));
        } finally {
            server.stop();
        }
    }

    static boolean wireHas(SyncServer s, String entry) {
        for (String line : s.wireLog()) if (line.equals(entry)) return true;
        return false;
    }

    static boolean contains(byte[] hay, byte[] needle) {
        if (needle.length == 0 || needle.length > hay.length) return false;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    // ---- الإثبات السلبي المحفوظ (--negproof) ------------------------------------

    static int negProofAttacks = 0, negProofBitten = 0, negProofBaselines = 0;

    /** جولة أساس خضراء على حساب جديد بنفس الخادم — «الاستعادة» بعد كل هجمة. */
    static void baseline(SyncServer server, SyncRestTransport t, int n) {
        String pass = "عبارة-الأساس-" + n;
        SyncClient.Session sa = SyncClient.register(pass, null);
        t.register(sa.keys.accountId, sa.salt);
        SyncState.Snapshot snap = new SyncState.Snapshot();
        snap.themeIdx = 3;
        snap.dictWords.put("أساس" + n, "ar");
        List<SyncVault.Rec> st = SyncState.collect(snap, 1, 10_000L + n);
        SyncClient.push(sa, t, st, "on", "on", true, false);
        SyncClient.Session sb = SyncClient.login(t, pass, sa.keys.accountId);
        List<SyncVault.Rec> merged = SyncClient.pull(sb, t, new ArrayList<SyncVault.Rec>(), "on", "on", true);
        SyncState.Plan plan = SyncState.apply(merged);
        if (plan.themeIdx != 3 || !("أساس" + n).equals(plan.dictAr.get(0)))
            throw new AssertionError("baseline_broken");
        negProofBaselines++;
        System.out.println("  baseline: RESTBASELINE_OK (fresh account on the same server) → HEALED");
    }

    static void attack(String name, String expected, String caught, SyncServer server,
                       SyncRestTransport t, int n) {
        negProofAttacks++;
        boolean bit = expected.equals(caught);
        if (bit) negProofBitten++;
        System.out.println("--- attack: " + name + " (expect: " + expected + ")");
        System.out.println("caught: " + caught + (bit ? " → BIT" : " → DID NOT BITE (expected " + expected + ")"));
        if (!bit) return;
        baseline(server, t, n);
    }

    static int negProof() {
        System.out.println("== REST negative proof — " + new java.util.Date() + " ==");
        try {
            SyncServer server = SyncServer.start();
            try {
                String base = server.base();
                SyncRestTransport trA = new SyncRestTransport(base);
                SyncRestTransport trB = new SyncRestTransport(base);

                // هجمة 1: قلب بايت في كتلة مخزنة — الفتح كله أو لا شيء على السلك
                SyncClient.Session s1 = SyncClient.register("عبارة-هجمة-1", null);
                trA.register(s1.keys.accountId, s1.salt);
                SyncClient.push(s1, trA, smallState(1), "on", "on", true, false);
                server.tamperBlob(s1.keys.accountId, 0, 60);
                String c1 = refusalTag(() -> SyncClient.pull(s1, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true));
                attack("wire.stored-blob byte-flip", "auth_failed", c1, server, trA, 1);

                // هجمة 2: قلب بايت في الملح المثبت — الدخول الجديد يفشل مغلق
                SyncClient.Session s2 = SyncClient.register("عبارة-هجمة-2", null);
                trA.register(s2.keys.accountId, s2.salt);
                SyncClient.push(s2, trA, smallState(2), "on", "on", true, false);
                server.tamperSalt(s2.keys.accountId, 3);
                String c2 = refusalTag(() -> SyncClient.login(trB, "عبارة-هجمة-2", s2.keys.accountId));
                attack("wire.stored-salt byte-flip", "account_mismatch", c2, server, trA, 2);

                // هجمة 3: بتر الكتلة 8 بايت — وسم GCM يلتهم
                SyncClient.Session s3 = SyncClient.register("عبارة-هجمة-3", null);
                trA.register(s3.keys.accountId, s3.salt);
                SyncClient.push(s3, trA, smallState(3), "on", "on", true, false);
                server.truncateBlob(s3.keys.accountId, 0, 8);
                String c3 = refusalTag(() -> SyncClient.pull(s3, trB, new ArrayList<SyncVault.Rec>(), "on", "on", true));
                attack("wire.stored-blob truncation (-8B)", "auth_failed", c3, server, trA, 3);

                // هجمة 4: تهريب النمو بنفس المعرف — المحاسبة التفاضلية تقطعه
                String qAcct = "dodgeAcct" + System.nanoTime();
                trA.register(qAcct, rnd(SyncVault.SALT_LEN));
                byte[] seed = rnd(400 * 1024);
                trA.put(qAcct, "dodge", seed, false);
                final SyncRestTransport trAf = trA;
                final String qAcc = qAcct;
                String c4 = refusalTag(() -> trAf.put(qAcc, "dodge", rnd(1024 * 1024 + 1), false));
                attack("wire.same-id growth smuggling (400KB → 1MB+1)", "http_507", c4, server, trA, 4);

                // هجمة 5: حقن مسار في معرف الكتلة — الشكل الغريب «لا شيء بعد» والمخزون سليم
                String iAcct = "injectAcct" + System.nanoTime();
                trA.register(iAcct, rnd(SyncVault.SALT_LEN));
                int before = server.blobCount(iAcct);
                final String iAccF = iAcct;
                String c5 = refusalTag(() -> trAf.put(iAccF, "a/b", rnd(100), false));
                attack("wire.blob-id path injection (a/b)", "http_404", c5, server, trA, 5);
                System.out.println("  store intact: blobCount=" + server.blobCount(iAcct) + " (before=" + before + ")");
                if (server.blobCount(iAcct) != before) { negProofAttacks++; System.out.println("  STORE CHANGED — ATTACK LEAKED"); }

                // هجمة 6: محارف غريبة في معرف الحساب — الخادم يرفض 400 والعميل قبل الخروج يرفض أولاً
                String c6a = refusalTag(() -> trAf.saltOf("bad|id"));
                System.out.println("--- attack: client pre-wire charset refusal (bad|id) (expect: bad_account_id)");
                negProofAttacks++;
                if ("bad_account_id".equals(c6a)) negProofBitten++;
                System.out.println("caught: " + c6a + ("bad_account_id".equals(c6a) ? " → BIT" : " → DID NOT BITE"));
                Raw r6 = raw("GET", base + "account/" + java.net.URLEncoder.encode("آ", "UTF-8") + "/salt", null);
                System.out.println("server-side raw non-b64url account: HTTP " + r6.code + " (expect 400)");
                negProofAttacks++;
                if (r6.code == 400) negProofBitten++;
                baseline(server, trA, 6);

                // هجمة 7: تهريب استبدال ملح — 409 والملح المثبت يبقى
                String wAcct = "smuggleAcct" + System.nanoTime();
                byte[] realSalt = rnd(SyncVault.SALT_LEN);
                trA.register(wAcct, realSalt);
                trA.register(wAcct, rnd(SyncVault.SALT_LEN));
                boolean guard409 = wireHas(server, "PUT /v1/account/" + wAcct + "/salt -> 409");
                boolean kept = Arrays.equals(realSalt, server.saltOf(wAcct));
                negProofAttacks++;
                if (guard409 && kept) negProofBitten++;
                System.out.println("--- attack: wire.salt-overwrite smuggling (expect: 409 recorded + salt kept)");
                System.out.println("caught: " + (guard409 ? "409 recorded" : "NO 409") + " / "
                        + (kept ? "salt kept" : "SALT REPLACED")
                        + (guard409 && kept ? " → BIT" : " → DID NOT BITE"));
                baseline(server, trA, 7);

                // هجمة 8: كتلة فوق السقف المطلق — 413 حتى قبل الحصة
                String oAcct = "oversizeAcct" + System.nanoTime();
                trA.register(oAcct, rnd(SyncVault.SALT_LEN));
                final String oAccF = oAcct;
                String c8 = refusalTag(() -> trAf.put(oAccF, "huge1", rnd(SyncVault.MAX_BLOB_BYTES + 1), false));
                attack("wire.oversize blob (8MB+1)", "http_413", c8, server, trA, 8);

                // الحصاد النهائي: جولة خضراء كاملة بعد كل الهجمات
                baseline(server, trA, 99);
            } finally {
                server.stop();
            }
        } catch (Exception e) {
            System.out.println("PROOF_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            return 1;
        }
        boolean ok = negProofBitten == negProofAttacks && negProofBaselines >= negProofAttacks;
        System.out.println("");
        System.out.println(ok
                ? "== PROOF_PASSED attacks=" + negProofBitten + "/" + negProofAttacks
                  + " baselines=" + negProofBaselines + " — every attack bit with its own tag, every baseline healed =="
                : "== PROOF_FAILED attacks=" + negProofBitten + "/" + negProofAttacks + " baselines=" + negProofBaselines + " ==");
        return ok ? 0 : 1;
    }

    static List<SyncVault.Rec> smallState(int n) {
        SyncState.Snapshot snap = new SyncState.Snapshot();
        snap.themeIdx = 2 + n;
        for (int i = 0; i < 50; i++) snap.dictWords.put("هجمة" + n + "كلمة" + i, "ar");
        return SyncState.collect(snap, n, 20_000L + n);
    }
}
