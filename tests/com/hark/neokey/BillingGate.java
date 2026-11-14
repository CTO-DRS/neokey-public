package com.hark.neokey;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * بوابة مُحوّل الفاتورة — المرحلة 7 W1 (الأسبوع 34) — ci/billing_gate.sh
 *
 * المعلق الموثق «المزوّد الحقيقي يستبدل SimProvider في StoreActivity فقط»
 * يُقاس هنا بخط واحد: **الاستبدال مُثبَت قبل وجود الحساب**. البوابة تُحقق:
 *
 *   1. المراساة أولاً («المرجع = المنشور»): هوية الأسبوع 79 / 3.2 معلنة
 *      حرفياً في الـ manifest — الأسبوع الذي يحمل المُحوّل يحمل هويته.
 *   2. النقاء: عقد الدفع صفر android.* — المُحوّل نقي-JVM يتشغَّل في
 *      الاختبار والبوابة دون جهاز، ونقطة الوصل `PurchaseSource` هي امتداد
 *      BillingClient الوحيد.
 *   3. تعادل العقد على المزوّدين: نفس تأكيدات العقد (الختم بإعادة
 *      الاشتقاق، نفس الطلب = نفس الختم بايت-لطرفاً على نسخة جديدة، قانون
 *      externalRef المُجرَن غير الختومي) على SimProvider وعلى
 *      PlayProvider — نتائج واحدة، فالاستبدال سطر واحد لا يعرفوا فرقاً.
 *   4. عشر سلبيات كلٌّ بوسمها الحرفي: نص مُعتل بعد التوقيع، توقيع مفتاح
 *      أجنبي، JSON تالف موقّع، سجل null، حالة غير مشتراة، منتج خارج
 *      الفهرس، سعر مخالف، ربط طلب مخالف، orderId مفقود، إعادة تشغيل
 *      orderId — والمُحوّل يرفض البناء نفسه على مفتاح لوحة باطل أو فهرس
 *      أسعار فارغ.
 *
 * الحكم: `BILLINGGATE_OK checks=N negatives=10/10 vc=3 vn=0.3.0 providers=2`
 * / `BILLINGGATE_FAILED <reason>` (exit 1).
 */
public class BillingGate {

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("BILLING regression: " + name + " (" + desc + ")"); }
    }

    /** سلبة مقاسة: يجب أن يرفض المزوّد بالوسم الحرفي — ولّا عدّت ولا مرّت. */
    static void neg(String name, String tag, PaymentProvider.PlayProvider p, String requestId, long cents) {
        checks++; negTotal++;
        try {
            p.charge(new PaymentProvider.ChargeRequest(requestId, cents, 7L));
            reg++;
            System.out.println("BILLING regression: " + name + " (no refusal — a dead guard)");
        } catch (PaymentProvider.Declined d) {
            if (!tag.equals(d.tag)) {
                reg++;
                System.out.println("BILLING regression: " + name + " (tag " + d.tag + " != " + tag + ")");
            } else {
                negPass++;
                System.out.println("NEG-OK " + name + " [" + tag + "]");
            }
        }
    }

    // ---- أدوات التجهيز (مفتاح RSA اختباري + سجلات شراء موقّعة) --------------
    static KeyPair KP, FOREIGN;
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

    static PaymentProvider.PlayProvider.PurchaseRecord good(String requestId, String orderId) throws Exception {
        String json = purchaseJson(orderId, "packs_500", 0L, requestId);
        return new PaymentProvider.PlayProvider.PurchaseRecord(json, sign(json));
    }

    static PaymentProvider.PlayProvider play() {
        return new PaymentProvider.PlayProvider(PUB_B64, CATALOG,
                req -> {
                    try { return good(req, "GPA.3373-" + req + "-01"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
    }

    static PaymentProvider.PlayProvider playFixed(final PaymentProvider.PlayProvider.PurchaseRecord rec) {
        return new PaymentProvider.PlayProvider(PUB_B64, CATALOG, req -> rec);
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KP = g.generateKeyPair();
        FOREIGN = g.generateKeyPair();
        PUB_B64 = Base64.getEncoder().encodeToString(KP.getPublic().getEncoded());
        CATALOG = new LinkedHashMap<String, Long>();
        CATALOG.put("packs_500", 500L);
        CATALOG.put("packs_1200", 1200L);

        // ---- 1: المراساة أولاً — هوية الأسبوع في المانيفست الحي -------------
        String mf = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), "UTF-8");
        ck("anchor.vc", mf.contains("android:versionCode=\"3\""), "the week carrying the adapter declares 3");
        ck("anchor.vn", mf.contains("android:versionName=\"0.3.0\""), "the week carrying the adapter declares 0.3.0");

        // ---- 2: النقاء ونقطة الوصل ------------------------------------------
        String src = new String(Files.readAllBytes(Paths.get("src/com/hark/neokey/PaymentProvider.java")), "UTF-8");
        ck("purity.no_android", !src.contains("import android."), "the payment contract stays pure JVM — it tests without a device");
        ck("purity.one_seam", src.contains("public interface PurchaseSource"), "the BillingClient seam is the only extension point");

        // ---- 3: تعادل العقد على المزوّدين -----------------------------------
        String req = "rq-parity";
        PaymentProvider.Receipt sim = new PaymentProvider.SimProvider()
                .charge(new PaymentProvider.ChargeRequest(req, 500L, 42L));
        ck("parity.sim.seal", sim.receiptId.equals(PaymentProvider.Receipt.seal("local-sim", req, 500L)),
                "SimProvider: the seal is re-derived, never trusted");
        PaymentProvider.Receipt sim2 = new PaymentProvider.SimProvider()
                .charge(new PaymentProvider.ChargeRequest(req, 500L, 43L));
        ck("parity.sim.fresh_same_seal", sim2.receiptId.equals(sim.receiptId),
                "same request = same seal byte-for-byte");
        ck("parity.sim.externalRef_null", sim.externalRef == null, "SimProvider journals no externalRef (law: null)");

        PaymentProvider.PlayProvider pl = play();
        PaymentProvider.Receipt rec = pl.charge(new PaymentProvider.ChargeRequest(req, 500L, 42L));
        ck("parity.play.seal", rec.receiptId.equals(PaymentProvider.Receipt.seal("play", req, 500L)),
                "PlayProvider: the SAME seal formula, re-derived");
        PaymentProvider.Receipt rec2 = play().charge(new PaymentProvider.ChargeRequest(req, 500L, 43L));
        ck("parity.play.fresh_same_seal", rec2.receiptId.equals(rec.receiptId),
                "same request = same seal byte-for-byte on a fresh instance");
        ck("parity.play.externalRef_orderId", "GPA.3373-rq-parity-01".equals(rec.externalRef),
                "PlayProvider journals the Play orderId");
        ck("parity.play.valid_receipt", rec.receiptId.length() == 16, "the wallet-side receipt carries the 16-hex seal");
        ck("parity.seal_formula_agnostic",
                !sim.receiptId.equals(rec.receiptId) && sim.receiptId.equals(PaymentProvider.Receipt.seal("local-sim", req, 500L))
                        && rec.receiptId.equals(PaymentProvider.Receipt.seal("play", req, 500L)),
                "the seal is a function of (providerId|requestId|cents) — provider-agnostic by law");

        // ---- 4: السلبيات العشر (كلٌّ بوسمها الحرفي) --------------------------
        String base = purchaseJson("GPA.g1", "packs_500", 0L, "rq-purchase_signature_invalid");
        neg("neg.tampered_text", "purchase_signature_invalid",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(
                        base.replace("packs_500", "packs_1200"), sign(base))),
                "rq-purchase_signature_invalid", 500L);

        String fj = purchaseJson("GPA.g2", "packs_500", 0L, "rq-foreign");
        Signature fs = Signature.getInstance("SHA256withRSA");
        fs.initSign(FOREIGN.getPrivate());
        fs.update(fj.getBytes("UTF-8"));
        neg("neg.foreign_key_signature", "purchase_signature_invalid",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(fj,
                        Base64.getEncoder().encodeToString(fs.sign()))),
                "rq-foreign", 500L);

        String gj = "{oops: not json";
        neg("neg.signed_malformed_json", "purchase_json_invalid",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(gj, sign(gj))),
                "rq-purchase_json_invalid", 500L);

        neg("neg.null_record", "purchase_signature_invalid", playFixed(null),
                "rq-null", 500L);

        String cj = purchaseJson("GPA.g3", "packs_500", 1L, "rq-purchase_state_invalid");
        neg("neg.canceled_state", "purchase_state_invalid",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(cj, sign(cj))),
                "rq-purchase_state_invalid", 500L);

        String uj = purchaseJson("GPA.g4", "packs_999", 0L, "rq-purchase_product_unknown");
        neg("neg.unknown_product", "purchase_product_unknown",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(uj, sign(uj))),
                "rq-purchase_product_unknown", 500L);

        String pj = purchaseJson("GPA.g5", "packs_500", 0L, "rq-price");
        neg("neg.price_mismatch", "purchase_price_mismatch",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(pj, sign(pj))),
                "rq-price", 900L);

        String mj = purchaseJson("GPA.g6", "packs_500", 0L, "rq-other-request");
        neg("neg.request_mismatch", "purchase_request_mismatch",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(mj, sign(mj))),
                "rq-purchase_request_mismatch", 500L);

        String noj = "{\"productId\":\"packs_500\",\"purchaseState\":0,\"obfuscatedExternalAccountId\":\"rq-purchase_order_missing\"}";
        neg("neg.missing_order", "purchase_order_missing",
                playFixed(new PaymentProvider.PlayProvider.PurchaseRecord(noj, sign(noj))),
                "rq-purchase_order_missing", 500L);

        PaymentProvider.PlayProvider pl2 = new PaymentProvider.PlayProvider(PUB_B64, CATALOG,
                req2 -> {
                    try { return good(req2, "GPA.fixed-777"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
        pl2.charge(new PaymentProvider.ChargeRequest("rq-first", 500L, 7L));
        neg("neg.replayed_order", "purchase_replayed", pl2, "rq-second", 500L);

        // ---- 5: رفض البناء نفسه على مدخلات باطلة ----------------------------
        boolean badKey = false;
        try { new PaymentProvider.PlayProvider("!!!not-base64!!!", CATALOG, r -> null); }
        catch (IllegalArgumentException e) { badKey = "console_key_invalid".equals(e.getMessage()); }
        ck("construct.console_key_invalid", badKey, "a malformed console key is refused at construction");

        boolean badCat = false;
        try { new PaymentProvider.PlayProvider(PUB_B64, new LinkedHashMap<String, Long>(), r -> null); }
        catch (IllegalArgumentException e) { badCat = "price_catalog_empty".equals(e.getMessage()); }
        ck("construct.price_catalog_empty", badCat, "an empty price catalog is refused at construction");

        // ---- الحكم -----------------------------------------------------------
        if (reg > 0) {
            System.out.println("BILLINGGATE_FAILED regressions=" + reg + "/" + checks + " (see BILLING regression lines above)");
            System.exit(1);
        }
        System.out.println("BILLINGGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " vc=3 vn=0.3.0 providers=2");
    }
}
