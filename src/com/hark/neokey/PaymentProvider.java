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
}
