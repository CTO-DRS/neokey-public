package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Store gate (roadmap Phase 3, week 1) — proves the in-app store line, not the claim:
 *   • the SHIPPED asset (assets/founders_catalog.json) loads under the strict
 *     contract and equals the reviewed dictsrc source byte-for-byte
 *   • all 25 founder packs purchase + install through the full guard chain
 *     (ownership -> sha256 pin -> ThemePack.review with registered vendors)
 *   • a full simulated month over the real catalog closes the 70/30 ledger to
 *     the cent through Economy (one economy, no copy)
 *   • designed negatives all refused with the exact tag (double purchase,
 *     unknown pack, insufficient balance, caps, install without ownership,
 *     post-purchase byte flip, review consulted via unregistered vendor,
 *     catalog pin/schema/bytes/dup/price refusals)
 *   • install p50 measured; entitlement restore code deterministic
 * Week 24: every topup in the flow goes through the PaymentProvider contract
 * (SimProvider) — the wallet accepts money only via a verified sealed receipt.
 * Verdict: STOREUI_GATE_OK … (exit 0) / STOREUI_GATE_FAILED <reason> (exit 1).
 */
public final class StoreUiGate {

    static int passed = 0, failed = 0;
    static final PaymentProvider SIM = new PaymentProvider.SimProvider();

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Exception e) {
            System.out.println("STOREUI_GATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
        if (failed > 0) {
            System.out.println("STOREUI_GATE_FAILED checks=" + failed);
            System.exit(1);
        }
        System.out.println("STOREUI_GATE_OK founders=10 packs=25 installed=25 negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " p50_install_us=" + P50_US + " catalog_cents=7325 ledger_closes=7325 checks=" + passed);
    }

    static int NEG_OK = 0, NEG_TOTAL = 0, P50_US = -1;

    static void run() throws Exception {
        byte[] srcBytes = Files.readAllBytes(Paths.get("dictsrc/founders_catalog.json"));
        byte[] assetBytes = Files.readAllBytes(Paths.get("assets/founders_catalog.json"));
        check("asset.equalsReviewedSource", java.util.Arrays.equals(srcBytes, assetBytes));
        String json = new String(assetBytes, StandardCharsets.UTF_8);

        StoreUi.Catalog c = StoreUi.loadCatalog(json);
        check("catalog.packs", c.packCount() == 25);
        check("catalog.founders", c.founders.size() == 10);
        check("catalog.total", c.totalPriceCents == 7325);
        check("catalog.vendors", c.vendorIds.size() == 10);

        // full month: buy everything, install everything through the guard chain
        StoreUi.Wallet w = new StoreUi.Wallet();
        check("wallet.topup", w.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "g1", 1).ok);
        StoreUi.Store s = new StoreUi.Store(c, w);
        long t0 = System.nanoTime();
        List<Long> installNanos = new ArrayList<>();
        for (StoreUi.Founder f : c.founders) {
            for (StoreUi.Pack p : f.packs) {
                check("buy." + p.id, s.purchase(p.id, 2).ok);
                byte[] bytes = p.specJson.getBytes(StandardCharsets.UTF_8);
                long i0 = System.nanoTime();
                StoreUi.Result r = s.install(p.id, bytes, 3);
                installNanos.add(System.nanoTime() - i0);
                check("install." + p.id, r.ok);
            }
        }
        long totalNanos = System.nanoTime() - t0;
        check("installed.all", s.installedCount() == 25);
        check("ledger.paid", s.paidCents() == 7325);
        Economy.Month m = s.monthClose();
        long creator = Economy.creatorTotal(m.statements);
        check("ledger.closes", creator + m.platformTotal == 7325);
        check("ledger.gross", m.statements.size() == 10);
        java.util.Collections.sort(installNanos);
        P50_US = (int) (installNanos.get(installNanos.size() / 2) / 1000);
        check("perf.installP50", P50_US >= 0);
        System.out.println("STOREGATE install_total_ms=" + (totalNanos / 1_000_000));

        // ---- designed negatives (exact tags) ------------------------------------
        neg("NO_PACK", !s.purchase("fXX-nope", 4).ok && s.purchase("fXX-nope", 4).tag.equals("NO_PACK"));
        neg("ALREADY_OWNED", !s.purchase("f01-p01", 4).ok && s.purchase("f01-p01", 4).tag.equals("ALREADY_OWNED"));
        StoreUi.Wallet w2 = new StoreUi.Wallet();
        StoreUi.Store s2 = new StoreUi.Store(c, w2);
        neg("INSUFFICIENT", !s2.purchase("f01-p01", 4).ok && s2.purchase("f01-p01", 4).tag.equals("INSUFFICIENT"));
        StoreUi.Wallet w3 = new StoreUi.Wallet();
        neg("BAD_TOPUP", !w3.topUp(SIM, -1, "g3x", 4).ok && w3.topUp(SIM, -1, "g3x", 4).tag.equals("BAD_TOPUP"));
        neg("TOPUP_CAP", !w3.topUp(SIM, StoreUi.MAX_TOPUP_CENTS + 1, "g3y", 4).ok && w3.topUp(SIM, StoreUi.MAX_TOPUP_CENTS + 1, "g3y", 4).tag.equals("TOPUP_CAP"));
        boolean fill3 = true;
        for (int i = 0; i < 5; i++) if (!w3.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "g3-" + i, 4).ok) fill3 = false;
        neg("BALANCE_CAP", fill3 && !w3.topUp(SIM, 1, "g3z", 4).ok && w3.topUp(SIM, 1, "g3z", 4).tag.equals("BALANCE_CAP"));
        neg("NOT_OWNED", !s2.install("f01-p01", c.pack("f01-p01").specJson.getBytes(StandardCharsets.UTF_8), 4).ok
                && s2.install("f01-p01", c.pack("f01-p01").specJson.getBytes(StandardCharsets.UTF_8), 4).tag.equals("NOT_OWNED"));
        byte[] flip = c.pack("f02-p01").specJson.getBytes(StandardCharsets.UTF_8).clone();
        flip[flip.length - 2] ^= 0x02;
        neg("PIN_MISMATCH", !s.install("f02-p01", flip, 4).ok && s.install("f02-p01", flip, 4).tag.equals("PIN_MISMATCH"));
        neg("NOT_INSTALLED", s.uninstall("f03-p01").ok && !s.uninstall("f03-p01").ok
                && s.uninstall("f03-p01").tag.equals("NOT_INSTALLED"));

        neg("CATALOG_PIN", tag(json.replace(c.pack("f01-p01").sha256, flip64(c.pack("f01-p01").sha256))).endsWith("CATALOG_PIN"));
        neg("CATALOG_SCHEMA", tag(catalogWith(j -> j.replace("neokey-founders-1", "neokey-founders-9"))).endsWith("CATALOG_SCHEMA"));
        neg("CATALOG_FOUNDERS", tag(catalogWith(j -> j.replace("\"founders\":", "\"creators\":"))).endsWith("CATALOG_FOUNDERS"));
        neg("CATALOG_PACK_ID", tag(catalogWith(j -> j.replace("\"id\": \"f01-p01\"", "\"id\": \"f01-p02\""))).endsWith("CATALOG_PACK_ID"));
        neg("CATALOG_PRICE0", tag(catalogWith(j -> j.replace("\"price_cents\": 149", "\"price_cents\": 0"))).endsWith("CATALOG_PRICE"));
        neg("CATALOG_BYTES", tag(catalogWith(j -> j.replace("\"bytes\": 569", "\"bytes\": 999"))).endsWith("CATALOG_BYTES"));

        // the review gate is consulted on the install path, not just the pin:
        // pack pinned under founder fZZ but declaring vendor f01 → review refuses
        StoreUi.Pack real = c.pack("f01-p02");
        String spec = real.specJson;
        String sha = ThemePack.sha256hex(spec.getBytes(StandardCharsets.UTF_8));
        String crafted = "{\"schema\":\"" + StoreUi.SCHEMA + "\",\"founders\":[{\"id\":\"fZZ\",\"name\":\"مبدع\",\"packs\":[{\"id\":\"z1\",\"price_cents\":100,\"spec_json\":"
                + jstr(spec) + ",\"sha256\":\"" + sha + "\",\"bytes\":" + spec.getBytes(StandardCharsets.UTF_8).length + "}]}]}";
        StoreUi.Catalog c2 = StoreUi.loadCatalog(crafted);
        StoreUi.Wallet w4 = new StoreUi.Wallet();
        w4.topUp(SIM, 1000, "g4", 5);
        StoreUi.Store s4 = new StoreUi.Store(c2, w4);
        s4.purchase("z1", 6);
        StoreUi.Result rv = s4.install("z1", spec.getBytes(StandardCharsets.UTF_8), 7);
        neg("REVIEW_VENDOR", !rv.ok && rv.tag.startsWith("REVIEW_"));

        // entitlement determinism
        StoreUi.Catalog c5 = StoreUi.loadCatalog(json);
        StoreUi.Wallet w5 = new StoreUi.Wallet();
        w5.topUp(SIM, StoreUi.MAX_TOPUP_CENTS, "g5", 8);
        StoreUi.Store s5 = new StoreUi.Store(c5, w5);
        List<String> ids = new ArrayList<>(c5.byId.keySet());
        java.util.Collections.reverse(ids);
        for (String id : ids) s5.purchase(id, 9);
        check("ent.deterministicAcrossOrders", s.restoreCode().equals(s5.restoreCode()));
        check("ent.ownedAll", s5.ownedCount() == 25);
    }

    static void neg(String tag, boolean cond) {
        NEG_TOTAL++;
        if (cond) NEG_OK++;
        check("neg." + tag, cond);
    }

    interface Tweaker { String tweak(String j); }

    static String catalogWith(Tweaker t) { return t.tweak(readAsset()); }

    static String readAsset() {
        try { return new String(Files.readAllBytes(Paths.get("assets/founders_catalog.json")), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static String tag(String json) {
        try { StoreUi.loadCatalog(json); return "NO_THROW"; }
        catch (IllegalArgumentException e) { return String.valueOf(e.getMessage()); }
    }

    static String flip64(String s) { return s.substring(0, 63) + (s.charAt(63) == 'a' ? 'b' : 'a'); }

    static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\');
            b.append(ch);
        }
        return b.append('"').toString();
    }
}
