package com.hark.neokey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * اختبارات مُحوّل الفاتورة الحقيقية — المرحلة 7 W1 (الأسبوع 34).
 *
 * المعلق الموثق «المزوّد الحقيقي يستبدل SimProvider في StoreActivity فقط»
 * يُثبَت هنا قبل وجود الحساب: **تعادل العقد** — نفس تأكيدات عقد المزوّد
 * (الختم الحتمي بإعادة الاشتقاق لا بالثقة، externalRef مُجرَن غير ختومي،
 * الرفض بوسمه الحرفي بلا حالة ثالثة) تجري على `SimProvider` وعلى
 * `PlayProvider` وتعطي النتائج ذاتها. وسلبيات المُحوّل كلٌّ بوسمها الحرفي:
 * نص مُعتل بعد التوقيع، توقيع بمفتاح أجنبي، نص JSON تالف موقّع، سجل null،
 * حالة غير مشتراة، منتج خارج الفهرس، سعر مخالف، ربط طلب مخالف، إعادة
 * تشغيل orderId، وorderId مفقود. مفتاح RSA اختباري (2048) يولَّد زمن
 * الاختبار — لا شبكة ولا حساب ولا مفتاح حقيقي.
 */
public class PlayProviderTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    // ---- المفتاح الاختباري وفهرس الأسعار -------------------------------------
    static KeyPair KP;
    static String PUB_B64;
    static Map<String, Long> CATALOG;

    static String sign(String json) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(KP.getPrivate());
        s.update(json.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static String purchaseJson(String orderId, String productId, long state, String ppaa) {
        return "{\"orderId\":\"" + orderId + "\",\"productId\":\"" + productId
                + "\",\"purchaseState\":" + state
                + ",\"purchaseTime\":1700000000000"
                + ",\"obfuscatedExternalAccountId\":\"" + ppaa + "\"}";
    }

    /** سجل شراء سليم: منتج الفهرس، حالة مشتراة، ربط == الطلب. */
    static PaymentProvider.PlayProvider.PurchaseRecord good(String requestId, String orderId) throws Exception {
        String json = purchaseJson(orderId, "packs_500", 0L, requestId);
        return new PaymentProvider.PlayProvider.PurchaseRecord(json, sign(json));
    }

    /** مزوّد بمصدر سليم: orderId جديد لكل طلب. */
    static PaymentProvider.PlayProvider play() {
        return new PaymentProvider.PlayProvider(PUB_B64, CATALOG,
                req -> {
                    try { return good(req, "GPA.3373-" + req + "-01"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
    }

    /** مزوّد بمصدر ثابت يعيد السجل المُعطى (للسلبيات الموقّعة سلفاً). */
    static PaymentProvider.PlayProvider playFixed(final PaymentProvider.PlayProvider.PurchaseRecord rec) {
        return new PaymentProvider.PlayProvider(PUB_B64, CATALOG, req -> rec);
    }

    /** مزوّد بمصدر يرفض بوسم ثابت — لاختبار مرور الرفض كما هو. */
    static PaymentProvider.PlayProvider playDeclining(final String tag) {
        return new PaymentProvider.PlayProvider(PUB_B64, CATALOG,
                req -> { throw new PaymentProvider.Declined(tag); });
    }

    /** سلبة قياسية: الطلب يجب أن يُرفض بالوسم الحرفي المُعطى. */
    static void neg(String tag, String name, PaymentProvider.PlayProvider p) {
        try {
            p.charge(new PaymentProvider.ChargeRequest("rq-" + tag, 500L, 7L));
            check(name + " [" + tag + "]", false);
        } catch (PaymentProvider.Declined d) {
            check(name + " [" + tag + "]", tag.equals(d.tag));
        }
    }

    // ---- تعادل العقد: نفس التأكيدات على المزوّدين ---------------------------
    static void contractSuite(PaymentProvider p, PaymentProvider fresh, String expectedExternalRef) throws Exception {
        String req = "rq-" + p.id() + "-1";
        PaymentProvider.Receipt r = p.charge(new PaymentProvider.ChargeRequest(req, 500L, 42L));
        check(p.id() + ": seal is re-derived not trusted",
                r.receiptId.equals(PaymentProvider.Receipt.seal(p.id(), req, 500L)));
        PaymentProvider.Receipt r2 = fresh.charge(new PaymentProvider.ChargeRequest(req, 500L, 43L));
        check(p.id() + ": same request = same seal byte-for-byte (fresh instance)",
                r2.receiptId.equals(PaymentProvider.Receipt.seal(p.id(), req, 500L)));
        check(p.id() + ": externalRef journaled (law: " + (expectedExternalRef == null ? "null" : "orderId") + ")",
                expectedExternalRef == null ? r.externalRef == null : expectedExternalRef.equals(r.externalRef));
        check(p.id() + ": externalRef is NOT part of the seal (tamper-proof only by re-derivation)",
                r.receiptId.equals(PaymentProvider.Receipt.seal(p.id(), req, 500L)));
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KP = g.generateKeyPair();
        PUB_B64 = Base64.getEncoder().encodeToString(KP.getPublic().getEncoded());
        CATALOG = new LinkedHashMap<String, Long>();
        CATALOG.put("packs_500", 500L);
        CATALOG.put("packs_1200", 1200L);

        // ---- تعادل العقد على المزوّدين -------------------------------------
        contractSuite(new PaymentProvider.SimProvider(), new PaymentProvider.SimProvider(), null);
        contractSuite(play(), play(), "GPA.3373-rq-play-1-01");

        // ---- المسار السليم للمُحوّل ----------------------------------------
        PaymentProvider.PlayProvider pl = play();
        PaymentProvider.Receipt ok = pl.charge(new PaymentProvider.ChargeRequest("rq-ok", 500L, 7L));
        check("play: valid purchase -> sealed receipt",
                ok.receiptId.equals(PaymentProvider.Receipt.seal("play", "rq-ok", 500L)));
        check("play: externalRef = the Play orderId", "GPA.3373-rq-ok-01".equals(ok.externalRef));

        // ---- سلبيات المُحوّل (كلٌّ بوسمها الحرفي) ---------------------------

        // 1) نص مُعتل بعد التوقيع — التوقيع لا يصح
        String base = purchaseJson("GPA.t1", "packs_500", 0L, "rq-purchase_signature_invalid");
        PaymentProvider.PlayProvider.PurchaseRecord tampered =
                new PaymentProvider.PlayProvider.PurchaseRecord(
                        base.replace("packs_500", "packs_1200"), sign(base));
        neg("purchase_signature_invalid", "play: tampered purchase text after signing", playFixed(tampered));

        // 2) توقيع بمفتاح أجنبي — ليس مفتاح اللوحة المُمرَّر
        KeyPairGenerator g2 = KeyPairGenerator.getInstance("RSA"); g2.initialize(2048);
        KeyPair FOREIGN = g2.generateKeyPair();
        String fj = purchaseJson("GPA.t2", "packs_500", 0L, "rq-purchase_signature_invalid");
        Signature fs = Signature.getInstance("SHA256withRSA"); fs.initSign(FOREIGN.getPrivate());
        fs.update(fj.getBytes("UTF-8"));
        neg("purchase_signature_invalid", "play: signature by a foreign key",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(fj,
                        Base64.getEncoder().encodeToString(fs.sign()))));

        // 3) نص JSON تالف موقّع — توقيع سليم على قمامة
        String gj = "{oops: not json";
        neg("purchase_json_invalid", "play: signed malformed purchase text",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(gj, sign(gj))));

        // 4) مصدر يعيد null — رفض التوقيع بوسمه
        neg("purchase_signature_invalid", "play: null purchase record",
                playFixed(null));

        // 5) حالة غير مشتراة (ملغى = 1)
        String cj = purchaseJson("GPA.t3", "packs_500", 1L, "rq-purchase_state_invalid");
        neg("purchase_state_invalid", "play: canceled purchase state",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(cj, sign(cj))));

        // 6) منتج خارج فهرس الأسعار
        String uj = purchaseJson("GPA.t4", "packs_999", 0L, "rq-purchase_product_unknown");
        neg("purchase_product_unknown", "play: product outside the price catalog",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(uj, sign(uj))));

        // 7) سعر مخالف للفهرس: شراء موقّع على packs_500 لكن الطلب بـ900 سنت
        String pj = purchaseJson("GPA.t5", "packs_500", 0L, "rq-price-mismatch");
        PaymentProvider.PlayProvider.PurchaseRecord priced =
                new PaymentProvider.PlayProvider.PurchaseRecord(pj, sign(pj));
        try {
            playFixed(priced).charge(new PaymentProvider.ChargeRequest("rq-price-mismatch", 900L, 7L));
            check("play: price mismatch refuses [purchase_price_mismatch]", false);
        } catch (PaymentProvider.Declined d) {
            check("play: price mismatch refuses [purchase_price_mismatch]",
                    "purchase_price_mismatch".equals(d.tag));
        }

        // 8) ربط الطلب مخالف (شراء تابع لطلب آخر)
        String mj = purchaseJson("GPA.t6", "packs_500", 0L, "rq-other-request");
        neg("purchase_request_mismatch", "play: purchase bound to another request",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(mj, sign(mj))));

        // 9) orderId مفقود
        String noj = "{\"productId\":\"packs_500\",\"purchaseState\":0,\"obfuscatedExternalAccountId\":\"rq-purchase_order_missing\"}";
        neg("purchase_order_missing", "play: missing orderId",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(noj, sign(noj))));

        // 10) إعادة تشغيل orderId — نفس الشراء بطلب مختلف
        PaymentProvider.PlayProvider pl2 = new PaymentProvider.PlayProvider(PUB_B64, CATALOG,
                req -> {
                    try { return good(req, "GPA.fixed-777"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
        pl2.charge(new PaymentProvider.ChargeRequest("rq-first", 500L, 7L));
        try {
            pl2.charge(new PaymentProvider.ChargeRequest("rq-second", 500L, 8L));
            check("play: replayed orderId refuses [purchase_replayed]", false);
        } catch (PaymentProvider.Declined d) {
            check("play: replayed orderId refuses [purchase_replayed]", "purchase_replayed".equals(d.tag));
        }

        // 11) رفض المصدر يمرّ بوسمه الحرفي (user_canceled) — لا حالة ثالثة
        try {
            playDeclining("user_canceled").charge(new PaymentProvider.ChargeRequest("rq-cancel", 500L, 7L));
            check("play: source decline passes through its own tag", false);
        } catch (PaymentProvider.Declined d) {
            check("play: source decline passes through its own tag", "user_canceled".equals(d.tag));
        }

        // 12) مفتاح لوحة غير صالح — بني المزوّد نفسه يرفض
        try {
            new PaymentProvider.PlayProvider("!!!not-base64!!!", CATALOG, req -> null);
            check("play: invalid console key refused at construction", false);
        } catch (IllegalArgumentException e) {
            check("play: invalid console key refused at construction",
                    "console_key_invalid".equals(e.getMessage()));
        }

        // 13) فهرس أسعار فارغ — رفض البناء
        try {
            new PaymentProvider.PlayProvider(PUB_B64, new LinkedHashMap<String, Long>(), req -> null);
            check("play: empty price catalog refused at construction", false);
        } catch (IllegalArgumentException e) {
            check("play: empty price catalog refused at construction",
                    "price_catalog_empty".equals(e.getMessage()));
        }

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
