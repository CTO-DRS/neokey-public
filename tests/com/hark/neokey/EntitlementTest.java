package com.hark.neokey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * اختبارات الاستحقاقات عبر الأجهزة — المرحلة 3، الأسبوع 3.
 *
 * وحدة السجل الجديد (entitlement) في الخزنة والجسر: التجميع يبني سجلاً لكل
 * حزمة مملوكة بدبيسها الكامل، التفكيك يُخرج ملكية قابلة للمنح، القراءة من
 * الشبكة تتسامح مع نوع مجهول (يُتجاهل) والكتابة المحلية ترفضه (فشل مغلق)،
 * والمنح مضبوط بالدبيس ضد الكتالوج — الاستعادة تعيد الملكية لا الثنائيات.
 * نقي JVM — تعمل في أي بيئة.
 */
public class EntitlementTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static String pin64(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        String pin = pin64("pack-spec-1");

        // 1) التجميع: سجل entitlement لكل حزمة بدبيس 64-hex وبلا دبس فاسد
        SyncState.Snapshot s = new SyncState.Snapshot();
        s.entitlements.put("f01-p01", pin);
        s.entitlements.put("f02-p03", "ZZ-not-hex");
        s.entitlements.put("f03-p01", null);
        List<SyncVault.Rec> recs = SyncState.collect(s, 1, 100L);
        int ent = 0;
        SyncVault.Rec the = null;
        for (SyncVault.Rec r : recs) {
            if (SyncVault.KIND_ENTITLEMENT.equals(r.kind)) { ent++; if ("f01-p01".equals(r.key)) the = r; }
        }
        check("collect.oneValidEnt", ent == 1);
        check("collect.entShape", the != null && the.id.equals("ent:f01-p01")
                && the.body.equals(pin) && the.rev == 1 && !the.tomb);
        check("collect.badPinDropped", SyncState.collect(s, 1, 100L).size() == 1);

        // سقف الاستحقاقات = سقف مكتبة المتجر
        SyncState.Snapshot many = new SyncState.Snapshot();
        for (int i = 0; i < StoreUi.MAX_LIBRARY + 20; i++)
            many.entitlements.put("p" + String.format("%03d", i), pin);
        int capped = 0;
        for (SyncVault.Rec r : SyncState.collect(many, 1, 1L))
            if (SyncVault.KIND_ENTITLEMENT.equals(r.kind)) capped++;
        check("collect.cap", capped == StoreUi.MAX_LIBRARY);

        // 2) التفكيك: ملكية قابلة للمنح، وشاهد الحذف لا يمنح، والدبس الفاسد لا يصل
        List<SyncVault.Rec> state = new ArrayList<>();
        state.add(new SyncVault.Rec("ent:f01-p01", SyncVault.KIND_ENTITLEMENT, "f01-p01", pin, 2, 10L, false));
        state.add(new SyncVault.Rec("ent:f09-p09", SyncVault.KIND_ENTITLEMENT, "f09-p09", "short", 2, 10L, false));
        state.add(new SyncVault.Rec("ent:f08-p08", SyncVault.KIND_ENTITLEMENT, "f08-p08", pin, 3, 20L, true));
        SyncState.Plan p = SyncState.apply(state);
        check("apply.grant", pin.equals(p.entitlements.get("f01-p01")));
        check("apply.badPinFiltered", !p.entitlements.containsKey("f09-p09"));
        check("apply.tombNoGrant", !p.entitlements.containsKey("f08-p08"));
        check("apply.countsEnt", p.count() == p.entitlements.size());

        // 3) القراءة من الشبكة: نوع مجهول يُتجاهل (توافق مستقبلي)، والكتابة ترفض
        List<SyncVault.Rec> base = new ArrayList<>();
        base.add(new SyncVault.Rec("theme:current", SyncVault.KIND_THEME, "current", "3", 1, 1L, false));
        String json = new String(SyncVault.stateToBytes(base), java.nio.charset.StandardCharsets.UTF_8);
        String planted = json.substring(0, json.length() - 2)
                + ",[\"zz:x\",\"walletx\",\"k\",1,1,0,\"v\"]]}";
        List<SyncVault.Rec> out = SyncVault.bytesToState(planted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check("parse.unknownIgnored", out.size() == 1 && "theme:current".equals(out.get(0).id));
        List<SyncVault.Rec> empty = SyncVault.bytesToState(
                "{\"v\":1,\"recs\":[[\"a\",\"ghost\",\"k\",1,1,0,\"x\"]]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check("parse.allUnknownEmpty", empty.isEmpty());
        try {
            new SyncVault.Rec("x", "walletx", "k", "v", 1, 1, false);
            check("local.unknownRefused", false);
        } catch (SyncVault.SyncRefused e) {
            check("local.unknownRefused", "rec_kind".equals(e.getMessage()));
        }

        // 4) الحالة المتزامنة بلا ثنائيات: جسم السجل دبيس فقط (64 محرف hex)
        check("record.pinOnly", the.body.length() == 64 && the.body.matches("[0-9a-f]{64}"));

        // 5) المنح ضد الكتالوج الحقيقي: نجاح/تكرار/دبيس غريب/حزمة غريبة
        StoreUi.Catalog cat = StoreUi.loadCatalog(new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/founders_catalog.json")),
                java.nio.charset.StandardCharsets.UTF_8));
        StoreUi.Store st = new StoreUi.Store(cat, new StoreUi.Wallet());
        StoreUi.Pack pk = cat.byId.values().iterator().next();
        check("grant.ok", st.grantFromSync(pk.id, pk.sha256).ok);
        check("grant.idempotent", st.grantFromSync(pk.id, pk.sha256).ok && st.ownedCount() == 1);
        check("grant.wrongPin", !st.grantFromSync(pk.id, pin64("other")).ok);
        check("grant.noPack", !st.grantFromSync("pack:غيره", pk.sha256).ok);
        check("grant.ownedPins", pk.sha256.equals(st.ownedPins().get(pk.id)));

        // 6) الاستعادة ≠ شراء: لا مال ولا بيع، والمال محلي الحقيقة
        check("grant.noMoney", st.paidCents() == 0 && st.sales().isEmpty() && st.wallet().balance() == 0);

        // 7) الملكية ≠ التثبيت: إزالة التثبيت تبقي الملكية المتزامنة
        check("ownNotInstall", st.install(pk.id, pk.specJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1L).ok
                && st.uninstall(pk.id).ok && st.ownedPins().containsKey(pk.id)
                && !st.installed(pk.id));

        // 8) دمج حتمي عبر الأجهزة: ent من جهازين يتبع آخر-كاتب-يفوز بالترتيب الكلي
        List<SyncVault.Rec> a = new ArrayList<>();
        a.add(new SyncVault.Rec("ent:f01-p01", SyncVault.KIND_ENTITLEMENT, "f01-p01", pin, 1, 5L, false));
        List<SyncVault.Rec> b = new ArrayList<>();
        b.add(new SyncVault.Rec("ent:f01-p01", SyncVault.KIND_ENTITLEMENT, "f01-p01", pin, 2, 9L, false));
        List<SyncVault.Rec> m1 = SyncVault.merge(a, b), m2 = SyncVault.merge(b, a);
        check("merge.commutative", m1.equals(m2) && m1.get(0).rev == 2);

        System.out.println("EntitlementTest: RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    private EntitlementTest() { }
}
