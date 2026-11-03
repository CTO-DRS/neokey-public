package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * بوابة الاستحقاقات عبر الأجهزة — المرحلة 3، الأسبوع 3.
 *
 * تقيس البروتوكول لا الادعاء، على محاكي الخادم غير الموثوق (SyncServerSim):
 *   • جهازان حقيقيان: شراء فعلي من الكتالوج المثبت → سجل entitlement لكل حزمة
 *     (الدبيس فقط) → دفع مشفر طرف-لطرف → جهاز جديد يستعيد الملكية ويثبّت
 *     الثنائيات من الكتالوج حصراً — لا بايت ثنائي يمر في المزامنة.
 *   • جولة عكسية: شراء الجهاز الثاني يعبر للجهاز الأول (merge آخر-كاتب-يفوز).
 *   • الصفر-المعرفة يبقى مقياساً على مخزون الخادم: إنتروبيا + لا معرف حزمة
 *     ولا سطر استحقاق نصي في كل ما يراه الخادم.
 *   • سجل مزروع من نوع مجهول يُتجاهل: لا يسمّم الكتلة ولا يصل الحالة، والإنشاء
 *     المحلي لأنواع مجهولة يبقى مرفوضاً (فشل مغلق عند الكتابة، تسامح عند القراءة).
 *   • حصص الحساب تحترم: الدفع بحمل استحقاقات يرفض quota_free عند الامتلاء.
 *   • المنح idempotent مضبوطاً بالدبيس: نفس الدبيس نجاح بلا أثر ثانٍ، دبيس
 *     غريب PIN_MISMATCH، حزمة غريبة NO_PACK، وشاهد الحذف لا يمنح.
 *
 * Verdict: ENTGATE_OK … (exit 0) / ENTGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — تعمل في أي بيئة.
 */
public final class EntitlementGate {

    static int passed = 0, failed = 0;
    static final PaymentProvider SIM = new PaymentProvider.SimProvider();
    static int NEG_OK = 0, NEG_TOTAL = 0;
    static long P50_COLLECT_US = -1, P50_APPLY_US = -1, P50_GRANT_US = -1;
    static int GRANTED = -1;
    static int ENTROPY_X100 = -1;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    /** سلبية مصممة: يجب أن يُرفض السلوك بالوسم المحدد. */
    static void neg(String name, String expectedTag, String actualTag) {
        NEG_TOTAL++;
        if (expectedTag.equals(actualTag)) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name + " expected=" + expectedTag + " got=" + actualTag);
    }

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Exception e) {
            System.out.println("ENTGATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (failed > 0 || NEG_OK != NEG_TOTAL) {
            System.out.println("ENTGATE_FAILED checks=" + failed + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
        System.out.println("ENTGATE_OK devices=2 granted=" + GRANTED
                + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " p50_collect_us=" + P50_COLLECT_US
                + " p50_apply_us=" + P50_APPLY_US
                + " p50_grant_us=" + P50_GRANT_US
                + " entropy_x100=" + ENTROPY_X100
                + " checks=" + passed);
    }

    // ---- أدوات -------------------------------------------------------------

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

    static boolean contains(byte[] hay, byte[] needle) {
        if (needle.length == 0 || needle.length > hay.length) return false;
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static String refusalTag(Runnable r) {
        try {
            r.run();
            return "NO_REFUSAL";
        } catch (SyncVault.SyncRefused e) {
            return e.getMessage();
        }
    }

    /** أرخص N حزمة بالسعر ثم بالمعرف — حتمية. */
    static List<StoreUi.Pack> cheapest(StoreUi.Catalog c, int n) {
        List<StoreUi.Pack> all = new ArrayList<>(c.byId.values());
        Collections.sort(all, (x, y) -> x.priceCents != y.priceCents
                ? Long.compare(x.priceCents, y.priceCents) : x.id.compareTo(y.id));
        return all.subList(0, Math.min(n, all.size()));
    }

    // ---- الجولة الكاملة ------------------------------------------------------

    static void run() throws Exception {
        // 0) الكتالوج الحقيقي المثبت (مصدر الثنائيات الوحيد) ببنية صارمة
        byte[] asset = catBytes();
        StoreUi.Catalog cat = StoreUi.loadCatalog(new String(asset, StandardCharsets.UTF_8));
        check("catalog.strict", cat.packCount() == 25 && cat.vendorIds.size() == 10);

        List<StoreUi.Pack> first4 = cheapest(cat, 4);
        check("picked4", first4.size() == 4);

        // 1) جهاز A: محفظة حقيقية، شراء فعلي، تثبيت من بايتات الكتالوج
        StoreUi.Wallet walletA = new StoreUi.Wallet();
        check("walletA.topup", walletA.topUp(SIM, 100_00, "ent-a1", 1000L).ok);
        StoreUi.Store storeA = new StoreUi.Store(cat, walletA);
        for (StoreUi.Pack p : first4) {
            check("A.buy." + p.id, storeA.purchase(p.id, 1000L).ok);
            check("A.install." + p.id, storeA.install(p.id, p.specJson.getBytes(StandardCharsets.UTF_8), 1001L).ok);
        }
        check("A.owned", storeA.ownedCount() == 4 && storeA.installedCount() == 4);

        // 2) التجميع: سجل entitlement لكل حزمة (الدبيس فقط) — بلا أي ثنائيات
        SyncState.Snapshot snapA = new SyncState.Snapshot();
        snapA.themeIdx = 7;
        snapA.entitlements.putAll(storeA.ownedPins());
        snapA.dictWords.put("مفتاحية", "ar");
        snapA.settings.put("haptics", "on");
        long t0 = System.nanoTime();
        List<SyncVault.Rec> stateA = SyncState.collect(snapA, 1, 5000L);
        P50_COLLECT_US = Math.max(1, (System.nanoTime() - t0) / 1000); // وسطي نهائي أدناه
        int entCount = 0;
        for (SyncVault.Rec r : stateA) if (SyncVault.KIND_ENTITLEMENT.equals(r.kind)) entCount++;
        check("A.collect.ents", entCount == 4);
        for (SyncVault.Rec r : stateA) {
            if (!SyncVault.KIND_ENTITLEMENT.equals(r.kind)) continue;
            check("A.ent.pin64." + r.key, r.body.length() == 64 && r.id.equals("ent:" + r.key)
                    && cat.pack(r.key) != null && cat.pack(r.key).sha256.equals(r.body));
        }

        // لا بايت ثنائي في الحالة المتزامنة: مواصفة أي حزمة غريبة عن السجل
        byte[] stateBytesA = SyncVault.stateToBytes(stateA);
        String probe = first4.get(0).specJson.substring(30, 90);
        check("sync.noBinaries", !contains(stateBytesA, probe.getBytes(StandardCharsets.UTF_8)));

        // 3) الخادم غير الموثوق: صفر-معرفة مقاساً على مخزونه
        SyncServerSim sim = SyncServerSim.fresh();
        SyncClient.Session sessA = SyncClient.register("عبارة-الجهازين", null);
        sim.register(sessA.keys.accountId, sessA.salt);
        SyncClient.PushResult pr = SyncClient.push(sessA, sim, stateA, "on", "on", true, false);
        check("A.push", pr.bytes > 0);
        byte[] seen = sim.dump();
        // عينة أولى صغيرة: أثر العينة المحدودة يُحسب بحد أدنى أخف (7.5)
        int entSmall = (int) Math.round(entropy(seen) * 100);
        check("server.entropy.first", entSmall >= 750);
        boolean leaks = false;
        for (StoreUi.Pack p : first4) if (contains(seen, p.id.getBytes(StandardCharsets.UTF_8))) leaks = true;
        check("server.noPackIds", !leaks && !contains(seen, "STOREENT1".getBytes(StandardCharsets.UTF_8)));

        // 4) جهاز B جديد: دخول بنفس العبارة → سحب → خطة → منح → تثبيت من الكتالوج
        SyncClient.Session sessB = SyncClient.login(sim, "عبارة-الجهازين", sessA.keys.accountId);
        check("B.sameAccount", sessB.keys.accountId.equals(sessA.keys.accountId));
        List<SyncVault.Rec> mergedB = SyncClient.pull(sessB, sim, new ArrayList<SyncVault.Rec>(), "on", "on", true);
        SyncState.Plan planB = SyncState.apply(mergedB);
        check("B.plan.ents", planB.entitlements.size() == 4);
        check("B.plan.theme", planB.themeIdx == 7 && "مفتاحية".equals(planB.dictAr.get(0)));

        StoreUi.Wallet walletB = new StoreUi.Wallet();
        StoreUi.Store storeB = new StoreUi.Store(cat, walletB);
        int granted = 0;
        for (Map.Entry<String, String> e : planB.entitlements.entrySet()) {
            if (storeB.grantFromSync(e.getKey(), e.getValue()).ok) {
                granted++;
                StoreUi.Pack p = cat.pack(e.getKey());
                check("B.install." + e.getKey(), storeB.install(e.getKey(),
                        p.specJson.getBytes(StandardCharsets.UTF_8), 2000L).ok);
            }
        }
        GRANTED = granted;
        check("B.granted4", granted == 4 && storeB.ownedCount() == 4 && storeB.installedCount() == 4);
        check("B.wallet.intact", walletB.balance() == 0 && storeB.paidCents() == 0); // لا مال في الاستعادة
        check("B.restoreCode", storeB.restoreCode().equals(storeA.restoreCode()));

        // 5) جولة عكسية: B يشتري خامسة → تعبر إلى A
        List<StoreUi.Pack> all5 = cheapest(cat, 5);
        StoreUi.Pack fifth = all5.get(4);
        check("B.topup", walletB.topUp(SIM, 100_00, "ent-b1", 3000L).ok);
        check("B.buy5", storeB.purchase(fifth.id, 3000L).ok);
        SyncState.Snapshot snapB = new SyncState.Snapshot();
        snapB.entitlements.putAll(storeB.ownedPins());
        SyncClient.push(sessB, sim, SyncState.collect(snapB, 2, 6000L), "on", "on", true, false);
        List<SyncVault.Rec> mergedA = SyncClient.pull(sessA, sim, stateA, "on", "on", true);
        SyncState.Plan planA = SyncState.apply(mergedA);
        check("A.plan.ents5", planA.entitlements.size() == 5);
        check("A.grant5", storeA.grantFromSync(fifth.id, planA.entitlements.get(fifth.id)).ok
                && storeA.ownedCount() == 5);
        long sumFirst4 = 0;
        for (StoreUi.Pack p : first4) sumFirst4 += p.priceCents;
        check("ledger.restoreNotPurchase", storeA.paidCents() == sumFirst4
                && storeB.paidCents() == fifth.priceCents);

        // 6) سجل مزروع من نوع مجهول يُتجاهل — ولا يسمّم الكتلة
        String good = new String(SyncVault.stateToBytes(stateA), StandardCharsets.UTF_8);
        String planted = good.substring(0, good.length() - 2)
                + ",[\"zz:evil\",\"walletx\",\"k\",3,50,0,\"payload\"]]}";
        List<SyncVault.Rec> tolerated = SyncVault.bytesToState(planted.getBytes(StandardCharsets.UTF_8));
        check("planted.ignored", tolerated.size() == stateA.size());
        boolean plantedIn = false;
        for (SyncVault.Rec r : tolerated) if (r.id.equals("zz:evil")) plantedIn = true;
        check("planted.absent", !plantedIn);
        List<SyncVault.Rec> allUnknown = SyncVault.bytesToState(
                "{\"v\":1,\"recs\":[[\"a\",\"walletx\",\"k\",1,1,0,\"x\"],[\"b\",\"ghost\",\"k\",1,1,0,\"y\"]]}"
                        .getBytes(StandardCharsets.UTF_8));
        check("allUnknown.empty", allUnknown.isEmpty());
        neg("local.unknownKind", "rec_kind", refusalTag(() -> {
            new SyncVault.Rec("x", "walletx", "k", "v", 1, 1, false);
        }));
        // الاستعادة عبر السحب مع كتلة مسمومة بنوع مجهول + سليمة: تعمل وتتجاهل
        byte[] poisonBlob = SyncVault.seal(sessA.keys,
                planted.getBytes(StandardCharsets.UTF_8), new byte[SyncVault.NONCE_LEN]);
        List<SyncVault.Rec> restored = SyncVault.restore(stateA, sessA.keys, Arrays.asList(poisonBlob));
        check("poisonBlob.mergeSafe", restored.size() >= stateA.size());

        // 7) حصص الحساب تحترم مع حمل استحقاقات — فشل مغلق بالوسم
        SyncState.Snapshot big = new SyncState.Snapshot();
        for (int i = 0; i < 3000; i++) big.dictWords.put("كلمة" + i, "ar");
        big.entitlements.putAll(storeA.ownedPins());
        List<SyncVault.Rec> bigState = SyncState.collect(big, 3, 7000L);
        boolean hitQuota = false;
        int pushes = 0;
        try {
            while (pushes < 40) {
                SyncClient.push(sessA, sim, bigState, "on", "on", true, false);
                pushes++;
            }
        } catch (SyncVault.SyncRefused e) {
            if ("quota_free".equals(e.getMessage())) hitQuota = true;
        }
        check("quota.enforced", hitQuota && pushes >= 1);
        check("quota.vipWouldPass", SyncVault.checkQuota(200_000, sim.usedBytes(sessA.keys.accountId), true).allowed);
        // الصفر-المعرفة على المخزون الكامل بعد حمل الاستحقاقات — القياس الأقوى
        byte[] fullDump = sim.dump();
        ENTROPY_X100 = (int) Math.round(entropy(fullDump) * 100);
        check("server.entropy.full", ENTROPY_X100 >= 790);
        boolean leakFull = false;
        for (StoreUi.Pack p : cheapest(cat, 5)) if (contains(fullDump, p.id.getBytes(StandardCharsets.UTF_8))) leakFull = true;
        check("server.noPackIds.full", !leakFull && !contains(fullDump, "STOREENT1".getBytes(StandardCharsets.UTF_8)));

        // 8) سلوك المنح: idempotent بالدبيس، ورفض بالأوسمة
        StoreUi.Store storeC = new StoreUi.Store(cat, new StoreUi.Wallet());
        StoreUi.Pack p0 = first4.get(0);
        check("C.grant1", storeC.grantFromSync(p0.id, p0.sha256).ok);
        check("C.grant.idempotent", storeC.grantFromSync(p0.id, p0.sha256).ok && storeC.ownedCount() == 1);
        neg("C.grant.wrongPin", "PIN_MISMATCH", tag(() -> storeC.grantFromSync(p0.id,
                "0000000000000000000000000000000000000000000000000000000000000000")));
        neg("C.grant.unknownPack", "NO_PACK", tag(() -> storeC.grantFromSync("pack:غريب", p0.sha256)));
        // شاهد الحذف لا يمنح: سجل ent مع tomb يُهمل في الخطة
        List<SyncVault.Rec> withTomb = new ArrayList<>(stateA);
        withTomb.add(new SyncVault.Rec("ent:" + p0.id, SyncVault.KIND_ENTITLEMENT, p0.id, p0.sha256, 9, 9999L, true));
        SyncState.Plan planTomb = SyncState.apply(withTomb);
        check("tomb.noGrant", !planTomb.entitlements.containsKey(p0.id));
        // الملكية ≠ التثبيت: إزالة التثبيت لا تُبطل الملكية المتزامنة
        check("A.uninstall", storeA.uninstall(first4.get(1).id).ok);
        check("A.ownedAfterUninstall", storeA.ownedCount() == 5
                && storeA.ownedPins().containsKey(first4.get(1).id));

        // 9) p50 قياسات: تجميع وتفكيك بعبء حقيقي (3000 كلمة + 200 استحقاق)
        SyncState.Snapshot heavy = new SyncState.Snapshot();
        for (int i = 0; i < 3000; i++) heavy.dictWords.put("كلمة" + i, "ar");
        StoreUi.Catalog catF = cat;
        List<StoreUi.Pack> all = new ArrayList<>(catF.byId.values());
        for (int i = 0; i < 200; i++) {
            StoreUi.Pack p = all.get(i % all.size());
            heavy.entitlements.put(p.id + ":v" + i, p.sha256);   // مفاتيح فريدة لقياس السقف
        }
        List<Long> cs = new ArrayList<>(), as = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            long a = System.nanoTime();
            List<SyncVault.Rec> st = SyncState.collect(heavy, 5, 100L);
            long b = System.nanoTime();
            SyncState.apply(st);
            long c = System.nanoTime();
            cs.add(b - a); as.add(c - b);
        }
        P50_COLLECT_US = medianUs(cs);
        P50_APPLY_US = medianUs(as);
        check("perf.collect", P50_COLLECT_US <= 150_000);   // نفس ميزانية بوابة syncclient
        check("perf.apply", P50_APPLY_US <= 150_000);
        long g50 = 0;
        List<Long> gs = new ArrayList<>();
        StoreUi.Store storeD = new StoreUi.Store(cat, new StoreUi.Wallet());
        for (int i = 0; i < 15; i++) {
            long a = System.nanoTime();
            for (Map.Entry<String, String> e : heavy.entitlements.entrySet())
                storeD.grantFromSync(e.getKey().substring(0, e.getKey().indexOf(":v")), e.getValue());
            gs.add(System.nanoTime() - a);
        }
        P50_GRANT_US = medianUs(gs);
        check("perf.grant", P50_GRANT_US <= 50_000);
    }

    static String tag(java.util.concurrent.Callable<StoreUi.Result> c) {
        try {
            StoreUi.Result r = c.call();
            return r.ok ? "OK" : r.tag;
        } catch (Exception e) {
            return e instanceof SyncVault.SyncRefused ? e.getMessage() : "EXCEPTION";
        }
    }
}
