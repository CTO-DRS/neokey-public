package com.hark.neokey;

import java.nio.charset.StandardCharsets;

/**
 * عقد مزوّد الدفع — المرحلة 4، الأسبوع 3 (الأسبوع 24).
 *
 * الوعد القديم الموثق (`docs/PHASE3-PLAN.md` و`StoreUi` الأسبوع 19): «الشحن
 * محاكى — مزوّد الدفع الحقيقي خارج البيئة ويستبدل topUp فقط دون لمس أي دالة
 * شراء». هذا الأسبوع يُنفَّذ الوعد: هذه الواجهة تصبح **نقطة الاستبدال القياسية** —
 * `Wallet.topUp` لم يعد يقبل مالاً إلا بإيصال مزوّد مُتحقق منه؛ لا يوجد مسار
 * ثانٍ للمال في الكود كله.
 *
 * العقد (نقي JVM، صفر android.*):
 *   • `charge(req)` إما يعيد **إيصالاً مختوماً** أو يرمي `Declined` بوسم ثابت
 *     قصير — لا وسوم حرة ولا حالة كتم.
 *   • الإيصال **حتمي ومكتفي بذاته**: ختمه `receiptId` مشتق إقطاعياً من
 *     (providerId|requestId|cents) بـ sha256 — نفس الطلب من نفس المزوّد يعطي
 *     نفس الختم بايت-لطرف على أي جهاز وفي أي زمن، لذا البروفة ضد مزوّد حتمي
 *     ممكنة بلا شبكة وبلا حساب، والمحفظة تتحقق بإعادة اشتقاق الختم لا بثقة.
 *   • المزوّد لا يقرر الحدود: سقوف الشحن والرصيد تُفحص **قبل** مناداة المزوّد
     وتظل نافذة مهما كان ما يعيده (مزوّد شريد لا يستطيع تضخيم رصيد).
 *   • المزوّد الحقيقي (Play Billing وأمثاله) قرار نشر: يستبدل
 *     `PaymentProvider.SimProvider` في `StoreActivity` فقط — أي دالة شراء
 *     أو دالة مكتبة أو دالة اقتصاد لا تُلمس.
 *
 * المرجع الخارجي الحر `externalRef` (رقم طلب المزوّد الحقيقي مثلاً) ليس جزء
 * الختم — يُدفَّع إلى جرنة المحفظة كما هو للتدقيق، ومحاولة التلاعب به لا تصحح
 * ختماً ولا تمرر إيصالاً.
 */
public interface PaymentProvider {

    /** هوية المزوّد الثابتة — تدخل في ختم الإيصال وجرنة المحفظة. غير فارغة. */
    String id();

    /** طلب خصم: معرف طلب فريد من العميل + مبلغ سنت-دقيق. */
    final class ChargeRequest {
        public final String requestId;
        public final long cents;
        public final long ts;
        public ChargeRequest(String requestId, long cents, long ts) {
            this.requestId = requestId; this.cents = cents; this.ts = ts;
        }
    }

    /** إيصال موافقة مختوم — المحفظة لا تُصدّقه إلا بإعادة اشتقاق الختم. */
    final class Receipt {
        public final String providerId, requestId;
        public final long cents;
        public final String receiptId;
        public final String externalRef;        // حر، ليس جزء الختم، للجرنة فقط
        public Receipt(String providerId, String requestId, long cents, String receiptId, String externalRef) {
            this.providerId = providerId; this.requestId = requestId; this.cents = cents;
            this.receiptId = receiptId; this.externalRef = externalRef;
        }
        /** الختم الحتمي المرجعي: hex16 من sha256("neokey-pay-1|providerId|requestId|cents"). */
        public static String seal(String providerId, String requestId, long cents) {
            String canon = "neokey-pay-1|" + providerId + "|" + requestId + "|" + cents;
            return ThemePack.sha256hex(canon.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        }
        /** إيصال مختوم صحيح البناء لنفس الطلب — ما يستخدمه المزوّد المطابق. */
        public static Receipt sealed(String providerId, String requestId, long cents, String externalRef) {
            return new Receipt(providerId, requestId, cents, seal(providerId, requestId, cents), externalRef);
        }
    }

    /** رفض مزوّد بوسم ثابت قصير (card_declined, insufficient_funds, ...). */
    final class Declined extends RuntimeException {
        public final String tag;
        public Declined(String tag) { super(tag); this.tag = tag; }
    }

    /** محاولة خصم واحدة: إيصال مختوم أو رفض — لا قيمة ثالثة ولا كتم. */
    Receipt charge(ChargeRequest req);

    /**
     * مزوّد المحاكاة المحلية المرجعي — ما كان يتظاهر به `topUp` القديم صار
     * صنفاً معلناً بعقد صريح. يعتمد كل طلب داخل سقوف المحفظة (السقوف
     * محفظية لا مزوّدية) ويختم إيصالاته حتمياً. الاستبدال عند النشر = سطر
     * واحد في `StoreActivity`، ولا شيء آخر في التطبيق يعرف الفرق.
     */
    final class SimProvider implements PaymentProvider {
        public String id() { return "local-sim"; }
        public Receipt charge(ChargeRequest req) {
            return Receipt.sealed(id(), req.requestId, req.cents, null);
        }
    }

    /**
     * مُحوّل الفاتورة الحقيقية — المرحلة 7 W1 (الأسبوع 34).
     *
     * المعلق الموثق يقول إن المزوّد الحقيقي «يستبدل SimProvider في
     * StoreActivity فقط» — وهذا الصنف يُثبت أن الاستبدال **آمن قبل وجود
     * الحساب**: شكل مسار Play Billing الفعلي بنقي JVM (صفر android.*)،
     * نقطة الوصل `PurchaseSource` هي ما سيغذّيه `BillingClient` لاحقاً
     * (onPurchasesUpdated ← سجل شراء خام + توقيعه)، والمُحوّل **يُحقق قبل
     * أن يعيِن أي إيصال** — لا ثقة، تحقق (نفس قانون الدبوس والختم):
     *
     *   • التوقيع RSA/SHA256 بمفتاح اللوحة العام المُمرَّر بنيته X509
     *     («المرجع = المنشور» — المفتاح يُلصق من اللوحة كما هو).
     *   • حالة الشراء purchased (0) — ملغى/معلّق = رفض بوسمه.
     *   • المنتج في فهرس أسعار المُنشئ، وسعره == سنتات الطلب — التسعير
     *     قرارات محفظية/فهرسية، الشراء الموقّع لا يضخّم مبلغاً.
     *   • ربط الطلب `obfuscatedExternalAccountId == requestId` — شراء
     *     تابع لطلب آخر = رفض.
     *   • `orderId` لا يتكرر في جلسة المزوّد (إعادة تشغيل إيصال حقيقي
     *     بطلب مختلف = رفض — الختم لا يحمي من شراء مزدوج بطلبين).
     *
     * ثم يختم **بالاشتقاق الواحد نفسه** `Receipt.sealed(id(), requestId,
     * cents, externalRef=orderId)` — فالمحفظة وجرنتها لا تعرفان أن
     * المزوّد تغيّر، والاستبدال يبقى سطراً واحداً في `StoreActivity`.
     * ما يبقى عند النشر: سلك `BillingClient` (يحتاج جهازاً) + لصق مفتاح
     * اللوحة الحقيقي — كلاهما موثق في تقرير الأسبوع.
     */
    final class PlayProvider implements PaymentProvider {
        public String id() { return "play"; }
        private final java.security.PublicKey consoleKey;
        private final java.util.Map<String, Long> prices;      // productId → cents (فهرس أسعار الاستعلام)
        private final PurchaseSource source;
        private final java.util.Set<String> seenOrders = new java.util.HashSet<>();

        /** سجل شراء خام كما يأتي من اللوحة: نص الشراء + توقيعه base64. */
        public static final class PurchaseRecord {
            public final String purchaseJson, signatureBase64;
            public PurchaseRecord(String purchaseJson, String signatureBase64) {
                this.purchaseJson = purchaseJson; this.signatureBase64 = signatureBase64;
            }
        }

        /** نقطة الوصل — الواجهة الوظيفية التي يغذّيها BillingClient لاحقاً. */
        public interface PurchaseSource {
            PurchaseRecord purchase(String requestId);   // يرمي Declined بوسمه (user_canceled ...)
        }

        PlayProvider(String consoleKeyBase64, java.util.Map<String, Long> productPrices, PurchaseSource src) {
            if (productPrices == null || productPrices.isEmpty()) throw new IllegalArgumentException("price_catalog_empty");
            try {
                byte[] der = java.util.Base64.getDecoder().decode(consoleKeyBase64);
                this.consoleKey = java.security.KeyFactory.getInstance("RSA")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            } catch (RuntimeException | java.security.GeneralSecurityException e) {
                throw new IllegalArgumentException("console_key_invalid");
            }
            this.prices = productPrices; this.source = src;
        }

        /** تحقق التوقيع: RSA/SHA256 فوق بايتات نص الشراء بمفتاح اللوحة. */
        private boolean verify(String purchaseJson, String sigBase64) {
            try {
                java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
                s.initVerify(consoleKey);
                s.update(purchaseJson.getBytes(StandardCharsets.UTF_8));
                return s.verify(java.util.Base64.getDecoder().decode(sigBase64));
            } catch (RuntimeException | java.security.GeneralSecurityException e) {
                return false;
            }
        }

        public Receipt charge(ChargeRequest req) {
            PurchaseRecord rec = source.purchase(req.requestId);   // Declined يمر بوسمه كما هو
            if (rec == null || rec.purchaseJson == null || rec.signatureBase64 == null
                    || !verify(rec.purchaseJson, rec.signatureBase64)) {
                throw new Declined("purchase_signature_invalid");
            }
            ThemePack.Json.Node n;
            try { n = ThemePack.Json.parse(rec.purchaseJson); } catch (RuntimeException e) {
                throw new Declined("purchase_json_invalid");
            }
            if (!n.isObj()) throw new Declined("purchase_json_invalid");
            ThemePack.Json.Node order = n.obj().get("orderId");
            if (order == null || !order.isStr() || order.str().isEmpty()) throw new Declined("purchase_order_missing");
            ThemePack.Json.Node st = n.obj().get("purchaseState");
            if (st == null || !st.isNum() || ((long) st.num()) != 0L) throw new Declined("purchase_state_invalid");
            ThemePack.Json.Node prod = n.obj().get("productId");
            String productId = (prod != null && prod.isStr()) ? prod.str() : null;
            if (productId == null || !prices.containsKey(productId)) throw new Declined("purchase_product_unknown");
            if (prices.get(productId).longValue() != req.cents) throw new Declined("purchase_price_mismatch");
            ThemePack.Json.Node ppaa = n.obj().get("obfuscatedExternalAccountId");
            if (ppaa == null || !ppaa.isStr() || !ppaa.str().equals(req.requestId)) throw new Declined("purchase_request_mismatch");
            if (!seenOrders.add(order.str())) throw new Declined("purchase_replayed");
            return Receipt.sealed(id(), req.requestId, req.cents, order.str());
        }
    }
}
