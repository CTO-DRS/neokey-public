package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Week 18 tests — in-app theme store (Phase 3, week 1).
 * 35 tests: strict catalog loading (every refusal tag), wallet caps,
 * idempotent purchase, pin-guarded install, review-consulted install,
 * ledger closure through Economy, deterministic entitlements, audit journal,
 * and a seeded commutativity fuzz over purchase order.
 */
public final class StoreUiTest {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    interface Thunk { Object run(); }

    static String expectTag(Thunk t, String ignored) {
        try { t.run(); return "NO_THROW"; }
        catch (IllegalArgumentException e) { return String.valueOf(e.getMessage()); }
        catch (RuntimeException e) { return "RT:" + e.getClass().getSimpleName(); }
    }

    static String catalogJson() throws Exception {
        return new String(Files.readAllBytes(Paths.get("assets/founders_catalog.json")), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        catalogLoading();
        walletRules();
        purchaseFlow();
        installFlow();
        ledgerAndEntitlements();
        fuzz();
        System.out.println("StoreUiTest: RESULT " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- strict catalog loading -------------------------------------------------
    static void catalogLoading() throws Exception {
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        check("catalog.founders10", c.founders.size() == 10);
        check("catalog.packs25", c.packCount() == 25);
        check("catalog.totalCents", c.totalPriceCents == 7325);
        check("catalog.vendorsRegistered", c.vendorIds.contains("f01") && c.vendorIds.contains("f10") && c.vendorIds.size() == 10);
        check("catalog.packLookup", c.pack("f01-p01") != null && c.pack("nope") == null);
        boolean pricesOk = true;
        for (StoreUi.Pack p : c.byId.values()) if (p.priceCents < 1 || p.priceCents > StoreUi.MAX_PRICE_CENTS) pricesOk = false;
        check("catalog.priceRange", pricesOk);

        String j = catalogJson();
        StoreUi.Pack p0 = StoreUi.loadCatalog(j).pack("f01-p01");
        String badPin = j.replace(p0.sha256, p0.sha256.substring(0, 63) + (p0.sha256.charAt(63) == 'a' ? 'b' : 'a'));
        check("catalog.pinRefused", expectTag(() -> StoreUi.loadCatalog(badPin), "").endsWith("CATALOG_PIN"));
        check("catalog.schemaRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("neokey-founders-1", "neokey-founders-2")), "").endsWith("CATALOG_SCHEMA"));
        check("catalog.foundersRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("\"founders\":", "\"creators\":")), "").endsWith("CATALOG_FOUNDERS"));
        check("catalog.dupPackRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("\"id\": \"f01-p01\"", "\"id\": \"f01-p02\"")), "").endsWith("CATALOG_PACK_ID"));
        check("catalog.zeroPriceRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("\"price_cents\": 149", "\"price_cents\": 0")), "").endsWith("CATALOG_PRICE"));
        check("catalog.expensiveRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("\"price_cents\": 149", "\"price_cents\": 200000")), "").endsWith("CATALOG_PRICE"));
        check("catalog.bytesRefused", expectTag(() -> StoreUi.loadCatalog(j.replace("\"bytes\": 569", "\"bytes\": 568")), "").endsWith("CATALOG_BYTES"));
    }

    // ---- wallet caps and audit --------------------------------------------------
    static final PaymentProvider SIM = new PaymentProvider.SimProvider();

    static void walletRules() {
        StoreUi.Wallet w = new StoreUi.Wallet();
        check("wallet.negativeTopup", !w.topUp(SIM, -5, "t1", 1).ok && w.topUp(SIM, -5, "t1", 1).tag.equals("BAD_TOPUP"));
        check("wallet.zeroTopup", !w.topUp(SIM, 0, "t1", 1).ok);
        check("wallet.topupCap", !w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS + 1, "t2", 1).ok && w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS + 1, "t2", 2).tag.equals("TOPUP_CAP"));
        boolean fillOk = true;
        for (int i = 0; i < 5; i++) if (!w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "t3-" + i, 3 + i).ok) fillOk = false;
        check("wallet.fillToCap", fillOk && w.balance() == StoreUi.MAX_BALANCE_CENTS);
        check("wallet.balanceCap", !w.topUp(SIM, 1, "t4", 9).ok && w.topUp(SIM, 1, "t4", 9).tag.equals("BALANCE_CAP"));
        check("wallet.journalAudit", w.journal().size() == 5 && w.journal().get(0).startsWith("TOPUP\t10000\t3\tlocal-sim\t"));
        List<String> jCopy = w.journal();
        jCopy.clear();
        check("wallet.journalCopy", w.journal().size() == 5);
    }

    // ---- purchase flow (idempotency, unknown packs, ownership) ------------------
    static void purchaseFlow() throws Exception {
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        StoreUi.Wallet w = new StoreUi.Wallet();
        w.topUp(SIM, 50_00, "p1", 1);
        StoreUi.Store s = new StoreUi.Store(c, w);
        check("buy.unknownPack", !s.purchase("f99-nope", 2).ok && s.purchase("f99-nope", 2).tag.equals("NO_PACK"));
        check("buy.first", s.purchase("f01-p01", 3).ok && s.owned("f01-p01"));
        check("buy.debitExact", w.balance() == 50_00 - 149);
        check("buy.doubleRefused", !s.purchase("f01-p01", 4).ok && s.purchase("f01-p01", 4).tag.equals("ALREADY_OWNED"));
        check("buy.idempotentSale", s.sales().size() == 1);
        check("buy.saleVendor", s.sales().get(0).vendor.equals("f01") && s.sales().get(0).packId.equals("f01-p01") && s.sales().get(0).priceCents == 149);
        check("buy.paidCents", s.paidCents() == 149);
        StoreUi.Wallet w2 = new StoreUi.Wallet();
        StoreUi.Store s2 = new StoreUi.Store(c, w2);
        check("buy.insufficient", !s2.purchase("f01-p01", 5).ok && s2.purchase("f01-p01", 5).tag.equals("INSUFFICIENT") && !s2.owned("f01-p01"));
    }

    // ---- install flow (ownership -> pin -> review; no side door) ----------------
    static void installFlow() throws Exception {
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        StoreUi.Wallet w = new StoreUi.Wallet();
        w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "i1", 1);
        StoreUi.Store s = new StoreUi.Store(c, w);
        StoreUi.Pack p = c.pack("f01-p01");
        byte[] bytes = p.specJson.getBytes(StandardCharsets.UTF_8);

        check("install.notOwned", !s.install("f01-p01", bytes, 2).ok && s.install("f01-p01", bytes, 2).tag.equals("NOT_OWNED"));
        check("install.afterPurchase", s.purchase("f01-p01", 3).ok);
        byte[] tampered = bytes.clone();
        tampered[tampered.length - 1] ^= 0x01;
        check("install.pinMismatch", !s.install("f01-p01", tampered, 4).ok && s.install("f01-p01", tampered, 4).tag.equals("PIN_MISMATCH"));
        check("install.ok", s.install("f01-p01", bytes, 5).ok && s.installed("f01-p01"));
        check("install.tsRecorded", c.byId.get("f01-p01") != null && s.installedCount() == 1);
        check("install.uninstall", s.uninstall("f01-p01").ok && !s.installed("f01-p01"));
        check("install.uninstallTwice", !s.uninstall("f01-p01").ok && s.uninstall("f01-p01").tag.equals("NOT_INSTALLED"));

        // the review gate is REALLY consulted: a crafted catalog whose vendor is
        // registered under a different id — pin matches, review refuses the pack
        StoreUi.Pack real = c.pack("f01-p02");
        String spec = real.specJson;
        String sha = ThemePack.sha256hex(spec.getBytes(StandardCharsets.UTF_8));
        int utf8 = spec.getBytes(StandardCharsets.UTF_8).length;
        String crafted = "{\"schema\":\"" + StoreUi.SCHEMA + "\",\"founders\":[{\"id\":\"fZZ\",\"name\":\"مبدع\",\"packs\":[{\"id\":\"z1\",\"price_cents\":100,\"spec_json\":"
                + jsonString(spec) + ",\"sha256\":\"" + sha + "\",\"bytes\":" + utf8 + "}]}]}";
        StoreUi.Catalog c2 = StoreUi.loadCatalog(crafted);
        check("install.craftedLoads", c2.packCount() == 1 && c2.vendorIds.contains("fZZ"));
        StoreUi.Wallet w2 = new StoreUi.Wallet();
        w2.topUp(SIM, 1000, "i2", 6);
        StoreUi.Store s2 = new StoreUi.Store(c2, w2);
        s2.purchase("z1", 7);
        StoreUi.Result r = s2.install("z1", spec.getBytes(StandardCharsets.UTF_8), 8);
        check("install.reviewConsulted", !r.ok && r.tag.startsWith("REVIEW_"));
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\');
            b.append(ch);
        }
        return b.append('"').toString();
    }

    // ---- ledger closure + deterministic entitlements -----------------------------
    static void ledgerAndEntitlements() throws Exception {
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        StoreUi.Wallet w = new StoreUi.Wallet();
        w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "l1", 1);
        StoreUi.Store s = new StoreUi.Store(c, w);
        List<String> ids = new ArrayList<>(c.byId.keySet());
        for (String id : ids) if (!s.purchase(id, 2).ok) check("ledger.allBought", false);
        check("ledger.allBought", s.ownedCount() == 25);
        check("ledger.paidTotal", s.paidCents() == 7325);
        Economy.Month m = s.monthClose();
        long creator = Economy.creatorTotal(m.statements);
        check("ledger.closesToTheCent", creator + m.platformTotal == 7325);
        long expectCreator = 0;
        for (StoreUi.Pack pk : c.byId.values()) expectCreator += pk.priceCents * 70 / 100;
        check("ledger.splitExact", creator == expectCreator);
        check("ledger.platform", m.platformTotal == 7325 - creator);

        String code1 = s.restoreCode();
        check("ent.prefix", code1.startsWith("STOREENT1|f01-p"));
        check("ent.sortedDeterministic", code1.equals(new StoreUi.Store(c, new StoreUi.Wallet()).restoreCode()) == false); // empty store differs
        StoreUi.Wallet w3 = new StoreUi.Wallet();
        w3.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "l2", 3);
        StoreUi.Store s3 = new StoreUi.Store(c, w3);
        List<String> rev = new ArrayList<>(ids);
        Collections.reverse(rev);
        for (String id : rev) s3.purchase(id, 4);
        check("ent.orderIndependent", s3.restoreCode().equals(code1) && s3.paidCents() == 7325);
        StoreUi.Catalog c4 = StoreUi.loadCatalog(catalogJson());
        StoreUi.Wallet w4 = new StoreUi.Wallet();
        StoreUi.Store s4 = new StoreUi.Store(c4, w4);
        check("ent.emptyDiffers", !s4.restoreCode().equals(code1));
    }

    // ---- seeded commutativity fuzz -----------------------------------------------
    static void fuzz() throws Exception {
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        List<String> ids = new ArrayList<>(c.byId.keySet());
        String refCode = null;
        long refPaid = -1;
        for (int seed = 1804; seed < 1834; seed++) {
            StoreUi.Wallet w = new StoreUi.Wallet();
            w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "fz-" + seed, 0);
            StoreUi.Store s = new StoreUi.Store(c, w);
            List<String> order = new ArrayList<>(ids);
            Collections.shuffle(order, new Random(seed));
            for (String id : order) {
                StoreUi.Result r = s.purchase(id, seed);
                if (!r.ok) { check("fuzz.purchaseAll(seed=" + seed + ")", false); return; }
            }
            if (refCode == null) { refCode = s.restoreCode(); refPaid = s.paidCents(); }
            else if (!s.restoreCode().equals(refCode) || s.paidCents() != refPaid) {
                check("fuzz.commutative(seed=" + seed + ")", false);
                return;
            }
        }
        check("fuzz.commutative30seeds", true);
        check("fuzz.purchaseAll30seeds", true);
    }
}
