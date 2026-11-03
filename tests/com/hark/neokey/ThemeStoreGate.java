package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Theme-store gate (roadmap Phase 2, item B — بوابة المتجر وخط المراجعة).
 *
 * Measures the review line on the REAL founders catalog, not the claim:
 *   • all 25 catalog packs PASS the automated review, and the sha256 of the
 *     exact reviewed bytes matches the catalog pin (bytes reviewed == bytes published)
 *   • review latency measured per pack (p50/p95), bar: p50 < 5 ms
 *   • harmful-pack negatives: every designed attack is REFUSED with the
 *     expected reason tag (planted keys, oversized, undeclared/missing vendor,
 *     bidi/tashkeel/tatweel in the name, name too long, contrast violation,
 *     range abuse incl. borderW, layout abuse, wrong app, missing required
 *     colours, malformed hex, duplicate JSON key, trailing garbage, raw
 *     control char, neon without glow, post-review byte tampering at publish)
 *   • 70/30 economy on a full simulated month over the catalog: per-price
 *     split exactness, ledger closes to the cent, min-payout carry, payout
 *     idempotence, refund-exceeds-gross adjustment reported and absorbed
 *
 * Verdict: THEME_STORE_GATE_OK (exit 0) / THEME_STORE_GATE_FAILED <reason> (exit 1).
 * JVM-only (zero android.*) — runs anywhere.
 */
public class ThemeStoreGate {

    static int checks = 0, fails = 0;

    public static void main(String[] args) throws Exception {
        // ---------- load the founders catalog (strict parser) ----------------
        byte[] catBytes = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("dictsrc/founders_catalog.json"));
        ThemePack.Json.Node cat = ThemePack.Json.parse(new String(catBytes, StandardCharsets.UTF_8));
        req("catalog.schema", "neokey-founders-1".equals(cat.obj().get("schema").str()));
        List<ThemePack.Json.Node> founders = cat.obj().get("founders").arr();
        req("catalog.founders==10", founders.size() == 10);

        HashSet<String> vendorIds = new HashSet<String>();
        List<String> vendorOrder = new ArrayList<String>();
        List<byte[]> packBytes = new ArrayList<byte[]>();
        List<String> packHash = new ArrayList<String>();
        List<String> packId = new ArrayList<String>();
        List<Long> packPrice = new ArrayList<Long>();
        for (ThemePack.Json.Node f : founders) {
            vendorIds.add(f.obj().get("id").str());
            vendorOrder.add(f.obj().get("id").str());
            for (ThemePack.Json.Node p : f.obj().get("packs").arr()) {
                String spec = p.obj().get("spec_json").str();
                packBytes.add(spec.getBytes(StandardCharsets.UTF_8));
                packHash.add(p.obj().get("sha256").str());
                packId.add(p.obj().get("id").str());
                packPrice.add((long) p.obj().get("price_cents").num());
            }
        }
        req("catalog.packs==25", packBytes.size() == 25);
        System.out.println("catalog: 10 founders, 25 packs, " + catBytes.length + " catalog bytes");

        // ---------- POSITIVE: every catalog pack passes review, hash-pinned --
        int passed = 0;
        for (int i = 0; i < packBytes.size(); i++) {
            ThemePack.Verdict v = ThemePack.review(packBytes.get(i), vendorIds);
            if (!v.ok) { req("review.pass " + packId.get(i) + " -> " + Arrays.toString(v.reasons), false); continue; }
            passed++;
            req("pin.sha256 " + packId.get(i),
                    ThemePack.sha256hex(packBytes.get(i)).equals(packHash.get(i)));
        }
        req("review.all-pass", passed == 25);
        System.out.println("review: " + passed + "/25 PASS, every pack hash-pinned (bytes reviewed == bytes published)");

        // ---------- review latency (warm round first) -------------------------
        for (byte[] b : packBytes) ThemePack.review(b, vendorIds);
        long[] ns = new long[packBytes.size()];
        for (int i = 0; i < ns.length; i++) ns[i] = ThemePack.review(packBytes.get(i), vendorIds).nanos;
        Arrays.sort(ns);
        double p50 = ns[ns.length / 2] / 1e6, p95 = ns[(int) Math.ceil(ns.length * 0.95) - 1] / 1e6;
        req("review.p50<5ms", p50 < 5.0);
        System.out.printf("review latency: p50 %.3f ms, p95 %.3f ms (bar 5 ms)%n", p50, p95);

        // ---------- NEGATIVES: designed harmful packs, all refused ------------
        String good = new String(packBytes.get(0), StandardCharsets.UTF_8);   // f01-p01

        expectRefused(injectKey(good, "layout", "\"api_key\":\"sk-live-9f8a2b7c\""),
                "key:api_key", "planted API key");
        expectRefused(injectKey(good, "layout", "\"webhook_url\":\"https://evil.example/collect\""),
                "key:webhook_url", "planted exfil URL");
        String huge = good;
        while (huge.length() < ThemePack.MAX_BYTES + 1024) huge += good;
        expectRefused(huge, "size", "oversized pack (>64KB)");
        expectRefused(rewrite(good, "vendor", "\"f99\""), "vendor.unknown", "undeclared vendor");
        expectRefused(dropKey(good, "vendor"), "vendor.missing", "missing vendor");
        expectRefused(rewrite(good, "name", "\"زجاج\u200Fنيون\""), "name.bidi", "bidi control (RLM) in name");
        expectRefused(rewrite(good, "name", "\"زُجاجٌ نيون\""), "name.tashkeel", "tashkeel in theme name");
        expectRefused(rewrite(good, "name", "\"زجــاج نيون\""), "name.tatweel", "tatweel in theme name");
        expectRefused(rewrite(good, "name", "\"زجاج نيون بنفسجي داكن جداً بطول يتجاوز الحد المسموح به بوضوح تام\""),
                "name.length", "name > 40 chars");
        expectRefused(rewrite(good, "text", val(good, "bg")), "contrast.key", "ink == backdrop (unreadable)");
        expectRefused(rewrite(good, "font", "9"), "range.font", "font index out of range");
        String withAnim = null;
        for (byte[] b : packBytes) {
            String s2 = new String(b, StandardCharsets.UTF_8);
            if (s2.contains("\"anim\"")) { withAnim = s2; break; }
        }
        req("anim-negative-fixture", withAnim != null);
        expectRefused(rewrite(withAnim, "anim", "99"), "range.anim", "anim index out of range");
        expectRefused(rewrite(good, "borderW", "99"), "range.borderW", "border width out of range");
        expectRefused(rewrite(good, "hpct", "999"), "layout.hpct", "layout height out of range");
        expectRefused(rewrite(good, "keyshape", "77"), "layout.keyshape", "layout keyshape out of range");
        expectRefused(rewrite(good, "app", "\"NeoCopy\""), "app", "wrong app identity");
        expectRefused(rewrite(good, "key", "\"#112233\",\"enter2\":\"#445566\""),
                "key:enter2", "unknown colour-ish field");
        expectRefused("{\"neokey\":1,\"name\":\"س\",\"vendor\":\"f01\"}", "required", "missing bg/key");
        expectRefused(rewrite(good, "bg", "\"#ZZZZZZ\""), "color:bg", "malformed hex colour");
        int dupAt = good.indexOf("\"name\"");
        expectRefused(good.substring(0, dupAt) + "\"name\":\"قناع\"," + good.substring(dupAt),
                "json:dup", "duplicate JSON key");
        expectRefused(good + " garbage", "json:trailing", "trailing bytes");
        expectRefused(rewrite(good, "name", "\"زجاج\u0007نيون\""), "json:ctrl", "raw control char");
        expectRefused(dropKey(good, "glow"), "neon.glow", "neon pack without glow");
        expectAccepted(rewrite(rewrite(good, "glow", "\"#112233\""), "glow2", "\"#445566\""),
                "sanity: glow rewrite keeps the pack valid");
        System.out.println("negatives: 23 harmful packs designed, ALL refused with the right tag");

        // ---------- post-review tampering at publish --------------------------
        byte[] tampered = packBytes.get(0).clone();
        for (int i = 0; i < tampered.length; i++)
            if (tampered[i] == '#') {
                tampered[i + 1] = (byte) (tampered[i + 1] == 'F' ? 'A' : 'F');
                break;
            }
        req("publish.tamper-pin-refused", !ThemePack.sha256hex(tampered).equals(packHash.get(0)));
        ThemePack.Verdict vt = ThemePack.review(tampered, vendorIds);
        System.out.println("publish: post-review byte flip caught by the hash pin ("
                + (vt.ok ? "review pass, pin refused" : "review refused too") + ")");

        // ---------- 70/30 economy on the full catalog -------------------------
        for (long p : packPrice) {
            long[] sp = Economy.split(p);
            req("split.exact " + p, sp[0] + sp[1] == p && sp[0] == p * 70 / 100);
        }
        req("split.penny 149", eq(Economy.split(149), new long[]{104, 45}));
        req("split.penny 1", eq(Economy.split(1), new long[]{0, 1}));
        req("split.penny 10", eq(Economy.split(10), new long[]{7, 3}));
        req("split.penny 499", eq(Economy.split(499), new long[]{349, 150}));

        // simulated month: deterministic sales + refunds over the catalog
        List<Economy.Sale> events = new ArrayList<Economy.Sale>();
        Random rnd = new Random(20261103);
        long gross = 0, refunded = 0;
        for (int d = 0; d < 420; d++) {
            int i = rnd.nextInt(25);
            long price = packPrice.get(i);
            String vendor = packId.get(i).substring(0, 3);
            events.add(Economy.Sale.sale(vendor, packId.get(i), price));
            gross += price;
            if (rnd.nextInt(20) == 0) {
                events.add(Economy.Sale.refund(vendor, packId.get(i), price));
                refunded += price;
            }
        }
        Economy.Month m = Economy.monthClose(events);
        req("month.statements==10", m.statements.size() == 10);
        long creatorSum = Economy.creatorTotal(m.statements);
        req("ledger.closes", creatorSum + m.platformTotal == gross - refunded);
        for (Economy.Statement s : m.statements)
            req("ledger.vendor " + s.vendor, s.creatorNet + s.platformNet == s.net
                    && s.gross - s.refunded == s.net);
        boolean ordered = true;
        for (int i = 1; i < m.statements.size(); i++)
            if (m.statements.get(i - 1).vendor.compareTo(m.statements.get(i).vendor) > 0) ordered = false;
        req("ledger.deterministic-order", ordered);

        // min-payout carry + idempotence on real statements
        long paid = 0, carried = 0;
        for (Economy.Statement s : m.statements) {
            long p1 = s.pay();
            paid += p1; carried += s.carried;
            req("payout.idempotent " + s.vendor,
                    s.pay() == 0 && (p1 == s.creatorNet || s.carried == s.creatorNet));
        }
        req("payout.conserves", paid + carried == creatorSum);
        System.out.printf("economy month: gross %d c, refunds %d c, creator %d c (%.2f%%), platform %d c (%.2f%%), paid %d c, carried %d c%n",
                gross, refunded, creatorSum, 100.0 * creatorSum / (gross - refunded),
                m.platformTotal, 100.0 * m.platformTotal / (gross - refunded), paid, carried);

        // refund-exceeds-gross: creator owes nothing, platform absorbs, reported
        List<Economy.Sale> edge = new ArrayList<Economy.Sale>();
        edge.add(Economy.Sale.refund("f01", "f01-p01", 500));
        edge.add(Economy.Sale.sale("f02", "f02-p01", 300));
        Economy.Month em = Economy.monthClose(edge);
        Economy.Statement s1 = em.statements.get(0), s2 = em.statements.get(1);
        req("adjustment.creator-zero", s1.creatorNet == 0 && s1.adjustments == 500);
        req("adjustment.platform-absorbs", s1.platformNet == -500
                && em.platformTotal == -500 + Economy.split(300)[1]);
        req("adjustment.victim-unaffected", s2.creatorNet == 210);
        System.out.println("economy edge: refund-exceeds-gross absorbed and reported, other vendors unaffected");

        System.out.println();
        System.out.println("THEMESTORE packs=25 pass=" + passed + " hash_pinned=" + passed);
        System.out.printf("THEMESTORE latency p50 %.3fms p95 %.3fms negatives=23 checks=%d%n", p50, p95, checks);
        System.out.printf("THEMESTORE month gross=%d refunds=%d creator=%d platform=%d%n",
                gross, refunded, creatorSum, m.platformTotal);
        System.out.println("checks: " + checks + ", failures: " + fails);
        if (fails > 0) { System.out.println("THEME_STORE_GATE_FAILED " + fails + " failing checks"); System.exit(1); }
        System.out.println("THEME_STORE_GATE_OK");
    }

    static void req(String label, boolean ok) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL: " + label); }
    }

    /** Pack must be REFUSED and the reasons must contain the expected tag. */
    static void expectRefused(String json, String tag, String label) {
        ThemePack.Verdict v = ThemePack.review(json.getBytes(StandardCharsets.UTF_8), vendors());
        checks++;
        if (v.ok) { fails++; System.out.println("FAIL(refused): " + label + " — pack PASSED review"); return; }
        if (tag == null) return;
        boolean found = false;
        for (String r : v.reasons) if (r.equals(tag)) found = true;
        if (!found) { fails++; System.out.println("FAIL(tag): " + label + " — got " + Arrays.toString(v.reasons) + ", wanted " + tag); }
    }

    /** Pack must PASS (used to prove a surgery didn't corrupt the fixture). */
    static void expectAccepted(String json, String label) {
        ThemePack.Verdict v = ThemePack.review(json.getBytes(StandardCharsets.UTF_8), vendors());
        req("accept:" + label, v.ok);
    }

    static HashSet<String> vendors() {
        HashSet<String> s = new HashSet<String>();
        for (int i = 1; i <= 10; i++) s.add(String.format("f%02d", i));
        return s;
    }

    // ---- canonical-JSON surgery helpers (pack JSON is sorted-key canonical) --
    static int valueEnd(String s, int vs) {
        char c0 = s.charAt(vs);
        if (c0 == '"') { int ve = vs + 1; while (s.charAt(ve) != '"') ve++; return ve + 1; }
        if (c0 == '{' || c0 == '[') {
            int depth = 0, ve = vs;
            boolean inStr = false;
            while (ve < s.length()) {
                char c = s.charAt(ve);
                if (inStr) { if (c == '\\') ve++; else if (c == '"') inStr = false; }
                else {
                    if (c == '"') inStr = true;
                    else if (c == '{' || c == '[') depth++;
                    else if (c == '}' || c == ']') { depth--; if (depth == 0) return ve + 1; }
                }
                ve++;
            }
            throw new IllegalStateException("unbalanced value");
        }
        int ve = vs;
        while (",}]".indexOf(s.charAt(ve)) < 0) ve++;
        return ve;
    }

    static int keyStart(String s, String key) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) throw new IllegalStateException(key + " not found");
        return i;
    }

    static String val(String s, String key) {
        int vs = keyStart(s, key) + key.length() + 3;
        return s.substring(vs, valueEnd(s, vs));
    }

    static String rewrite(String s, String key, String newVal) {
        int vs = keyStart(s, key) + key.length() + 3;
        return s.substring(0, vs) + newVal + s.substring(valueEnd(s, vs));
    }

    static String dropKey(String s, String key) {
        int i = keyStart(s, key);
        int vs = i + key.length() + 3;
        int ve = valueEnd(s, vs);
        boolean last = ve < s.length() && (s.charAt(ve) == '}' || s.charAt(ve) == ']');
        if (!last && ve < s.length() && s.charAt(ve) == ',') ve++;      // drop the following comma
        else if (last) {                                                 // key was last: drop the PRECEDING comma
            int p = i - 1;
            while (p >= 0 && s.charAt(p) == ' ') p--;
            if (p >= 0 && s.charAt(p) == ',') i = p;
        }
        return s.substring(0, i) + s.substring(ve);
    }

    /** Inserts a full "key":value declaration BEFORE the given key (key position, not value position). */
    static String injectKey(String s, String beforeKey, String decl) {
        int i = keyStart(s, beforeKey);
        return s.substring(0, i) + decl + "," + s.substring(i);
    }

    static boolean eq(long[] a, long[] b) { return Arrays.equals(a, b); }
}
