package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;

/**
 * اختبارات عقد مزوّد الدفع — المرحلة 4، الأسبوع 3 (الأسبوع 24).
 *
 * المال لا يدخل المحفظة إلا بإيصال مزوّد مختوم مُتحقق منه: الختم الحتمي
 * (نفس الطلب = نفس الختم بايت-لطرف)، تحقق الإيصال بإعادة الاشتقاق لا بالثقة،
 * السقوف محفظية قبل مناداة المزوّد (مزوّد شريد لا يضخّم رصيداً)، REPLAY
 * بمعرف الطلب، رفض المزوّد بوسمه الحرفي، وعدم مساس أي دالة شراء.
 */
public class PaymentProviderTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static final PaymentProvider SIM = new PaymentProvider.SimProvider();

    /** مزوّد سينمائي للفضاء السلبي: يعتمد/يرفض/يفسد حسب السيناريو. */
    static final class Scripted implements PaymentProvider {
        final String pid;
        final String behavior;                 // ok | decline | throw | null | badCents | badSeal | badProvider | badRequest
        final String declineTag;
        Scripted(String pid, String behavior, String declineTag) {
            this.pid = pid; this.behavior = behavior; this.declineTag = declineTag;
        }
        public String id() { return pid; }
        public PaymentProvider.Receipt charge(PaymentProvider.ChargeRequest req) {
            if ("decline".equals(behavior)) throw new PaymentProvider.Declined(declineTag);
            if ("throw".equals(behavior)) throw new IllegalStateException("boom");
            if ("null".equals(behavior)) return null;
            if ("badCents".equals(behavior)) return new PaymentProvider.Receipt(pid, req.requestId, req.cents + 1000,
                    PaymentProvider.Receipt.seal(pid, req.requestId, req.cents + 1000), null);
            if ("badSeal".equals(behavior)) return new PaymentProvider.Receipt(pid, req.requestId, req.cents,
                    "0000000000000000", null);
            if ("badProvider".equals(behavior)) return new PaymentProvider.Receipt("other-provider", req.requestId,
                    req.cents, PaymentProvider.Receipt.seal("other-provider", req.requestId, req.cents), null);
            if ("badRequest".equals(behavior)) return new PaymentProvider.Receipt(pid, "different-id", req.cents,
                    PaymentProvider.Receipt.seal(pid, "different-id", req.cents), null);
            if ("ext".equals(behavior)) return PaymentProvider.Receipt.sealed(pid, req.requestId, req.cents, "EXT-77");
            return PaymentProvider.Receipt.sealed(pid, req.requestId, req.cents, null);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- the seal: deterministic, canonical, self-verifying -----------------
        String s1 = PaymentProvider.Receipt.seal("local-sim", "req-1", 2000);
        String s2 = PaymentProvider.Receipt.seal("local-sim", "req-1", 2000);
        String s3 = PaymentProvider.Receipt.seal("other", "req-1", 2000);
        String s4 = PaymentProvider.Receipt.seal("local-sim", "req-2", 2000);
        String s5 = PaymentProvider.Receipt.seal("local-sim", "req-1", 2001);
        check("seal.deterministic", s1.equals(s2));
        check("seal.providerBinds", !s1.equals(s3));
        check("seal.requestBinds", !s1.equals(s4));
        check("seal.centsBind", !s1.equals(s5));
        check("seal.hex16", s1.length() == 16 && s1.matches("[0-9a-f]{16}"));
        PaymentProvider.Receipt r1 = PaymentProvider.Receipt.sealed("local-sim", "req-1", 2000, "EXT-77");
        check("receipt.sealedMatches", s1.equals(r1.receiptId));

        // ---- wallet green path through the provider -----------------------------
        StoreUi.Wallet w = new StoreUi.Wallet();
        check("topup.ok", w.topUp(SIM, 2000, "req-1", 1).ok);
        check("topup.balance", w.balance() == 2000);
        check("topup.locked", w.requestUsed("req-1"));
        List<String> j = w.journal();
        check("topup.journalColumns", j.size() == 1 && j.get(0).startsWith("TOPUP\t2000\t1\tlocal-sim\treq-1\t" + s1 + "\t"));
        check("topup.externalRefDash", j.get(0).endsWith("\t-"));
        StoreUi.Wallet w2 = new StoreUi.Wallet();
        check("topup.externalRefAudited", w2.topUp(new Scripted("payco", "ext", null), 500, "rq-9", 3).ok
                && w2.journal().get(0).endsWith("\tEXT-77"));

        // ---- replay: same request id never credits twice ------------------------
        StoreUi.Result rp = w.topUp(SIM, 2000, "req-1", 2);
        check("replay.refused", !rp.ok && rp.tag.equals("REPLAY"));
        check("replay.noDoubleCredit", w.balance() == 2000);
        check("replay.journalStable", w.journal().size() == 1);
        check("replay.freshIdCredits", w.topUp(SIM, 2000, "req-2", 3).ok && w.balance() == 4000);

        // ---- provider refusal passes through its literal tag --------------------
        StoreUi.Wallet w3 = new StoreUi.Wallet();
        StoreUi.Result rd = w3.topUp(new Scripted("payco", "decline", "card_declined"), 2000, "rq-1", 1);
        check("decline.literalTag", !rd.ok && rd.tag.equals("card_declined"));
        check("decline.noCredit", w3.balance() == 0 && w3.journal().isEmpty());
        check("decline.freesRequest", !w3.requestUsed("rq-1") && w3.topUp(SIM, 2000, "rq-1", 2).ok);
        check("decline.emptyTagGuarded", !w3.topUp(new Scripted("payco", "decline", ""), 100, "rq-2", 3).ok
                && w3.topUp(new Scripted("payco", "decline", ""), 100, "rq-2", 3).tag.equals("PROVIDER_DECLINED"));
        check("decline.nullTagGuarded", !w3.topUp(new Scripted("payco", "decline", null), 100, "rq-3", 3).ok
                && w3.topUp(new Scripted("payco", "decline", null), 100, "rq-3", 3).tag.equals("PROVIDER_DECLINED"));

        // ---- receipt verification: derivation, not trust ------------------------
        StoreUi.Wallet w4 = new StoreUi.Wallet();
        check("receipt.nullRefused", !w4.topUp(new Scripted("payco", "null", null), 500, "rq-1", 1).ok
                && w4.topUp(new Scripted("payco", "null", null), 500, "rq-1", 1).tag.equals("RECEIPT_MISSING"));
        check("receipt.inflatedRefused", !w4.topUp(new Scripted("payco", "badCents", null), 500, "rq-2", 1).ok
                && w4.topUp(new Scripted("payco", "badCents", null), 500, "rq-2", 1).tag.equals("RECEIPT_MISMATCH"));
        check("receipt.noCreditOnInflation", w4.balance() == 0);
        check("receipt.badSealRefused", !w4.topUp(new Scripted("payco", "badSeal", null), 500, "rq-3", 1).ok
                && w4.topUp(new Scripted("payco", "badSeal", null), 500, "rq-3", 1).tag.equals("RECEIPT_MISMATCH"));
        check("receipt.foreignProviderRefused", !w4.topUp(new Scripted("payco", "badProvider", null), 500, "rq-4", 1).ok
                && w4.topUp(new Scripted("payco", "badProvider", null), 500, "rq-4", 1).tag.equals("RECEIPT_MISMATCH"));
        check("receipt.wrongRequestRefused", !w4.topUp(new Scripted("payco", "badRequest", null), 500, "rq-5", 1).ok
                && w4.topUp(new Scripted("payco", "badRequest", null), 500, "rq-5", 1).tag.equals("RECEIPT_MISMATCH"));
        check("receipt.zeroCredit", w4.balance() == 0 && w4.journal().isEmpty());
        check("receipt.honestThenCredits", w4.topUp(SIM, 500, "rq-6", 2).ok && w4.balance() == 500);

        // ---- caps are wallet-side, checked BEFORE the provider is called --------
        final boolean[] providerCalled = {false};
        PaymentProvider greedy = new PaymentProvider() {
            public String id() { return "greedy"; }
            public PaymentProvider.Receipt charge(PaymentProvider.ChargeRequest req) {
                providerCalled[0] = true;
                return PaymentProvider.Receipt.sealed("greedy", req.requestId, req.cents, null);
            }
        };
        StoreUi.Wallet w5 = new StoreUi.Wallet();
        StoreUi.Result rc = w5.topUp(greedy, StoreUi.MAX_TOPUP_CENTS + 1, "gq-1", 1);
        check("caps.topupCapBeforeProvider", !rc.ok && rc.tag.equals("TOPUP_CAP") && !providerCalled[0]);
        check("caps.zeroBeforeProvider", !w5.topUp(greedy, 0, "gq-2", 1).ok && !providerCalled[0]);
        for (int i = 0; i < 5; i++) w5.topUp(greedy, StoreUi.MAX_TOPUP_CENTS, "gq3-" + i, 1);
        providerCalled[0] = false;   // العلم يقيس ما بعد هذه النقطة فقط
        check("caps.balanceCapBeforeProvider", !w5.topUp(greedy, 1, "gq-4", 1).ok
                && w5.topUp(greedy, 1, "gq-4", 1).tag.equals("BALANCE_CAP") && !providerCalled[0]);

        // ---- request id shape: the journal and seal substrate -------------------
        StoreUi.Wallet w6 = new StoreUi.Wallet();
        check("reqid.null", !w6.topUp(SIM, 100, null, 1).ok && w6.topUp(SIM, 100, null, 1).tag.equals("BAD_REQUEST_ID"));
        check("reqid.empty", !w6.topUp(SIM, 100, "", 1).ok && w6.topUp(SIM, 100, "", 1).tag.equals("BAD_REQUEST_ID"));
        check("reqid.pipe", !w6.topUp(SIM, 100, "a|b", 1).ok && w6.topUp(SIM, 100, "a|b", 1).tag.equals("BAD_REQUEST_ID"));
        check("reqid.space", !w6.topUp(SIM, 100, "a b", 1).ok && w6.topUp(SIM, 100, "a b", 1).tag.equals("BAD_REQUEST_ID"));
        check("reqid.oversize", !w6.topUp(SIM, 100, new String(new char[65]).replace('\0', 'a'), 1).ok);
        check("reqid.maxLengthOk", w6.topUp(SIM, 100, new String(new char[64]).replace('\0', 'a'), 1).ok);
        check("reqid.allowedPunct", w6.topUp(SIM, 100, "A-z.9:_", 1).ok);

        // ---- null provider and provider exceptions ------------------------------
        StoreUi.Wallet w7 = new StoreUi.Wallet();
        check("provider.nullRefused", !w7.topUp(null, 100, "nq-1", 1).ok
                && w7.topUp(null, 100, "nq-1", 1).tag.equals("BAD_PROVIDER"));
        check("provider.throwRefused", !w7.topUp(new Scripted("payco", "throw", null), 100, "nq-2", 1).ok
                && w7.topUp(new Scripted("payco", "throw", null), 100, "nq-2", 1).tag.equals("PROVIDER_ERROR"));
        check("provider.throwNoCredit", w7.balance() == 0);
        check("provider.throwFreesRequest", w7.topUp(SIM, 100, "nq-2", 2).ok);

        // ---- purchases untouched: same tags, same ledger, same economy ----------
        StoreUi.Wallet w8 = new StoreUi.Wallet();
        StoreUi.Catalog c = StoreUi.loadCatalog(catalogJson());
        StoreUi.Store s = new StoreUi.Store(c, w8);
        check("buy.insufficientUnchanged", !s.purchase("f01-p01", 1).ok && s.purchase("f01-p01", 1).tag.equals("INSUFFICIENT"));
        w8.topUp(SIM, 50_00, "bq-1", 2);
        check("buy.afterProviderTopup", s.purchase("f01-p01", 3).ok && w8.balance() == 50_00 - 149);
        check("buy.doubleUnchanged", !s.purchase("f01-p01", 4).ok && s.purchase("f01-p01", 4).tag.equals("ALREADY_OWNED"));
        Economy.Month m = s.monthClose();
        check("buy.economyCloses", Economy.creatorTotal(m.statements) + m.platformTotal == 149);

        // ---- determinism: identical request sequence = identical ledger bytes ---
        List<String> runA = ledgerOf("det");
        List<String> runB = ledgerOf("det");
        check("determinism.ledgerByteEqual", runA.equals(runB) && runA.size() == 3);
        check("determinism.balance", balanceOf("det") == 6000);

        System.out.println();
        System.out.println("PaymentProviderTest: " + pass + " pass, " + fail + " fail");
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static List<String> ledgerOf(String prefix) {
        StoreUi.Wallet ww = new StoreUi.Wallet();
        ww.topUp(SIM, 2000, prefix + "-a", 5);
        ww.topUp(SIM, 2000, prefix + "-b", 6);
        ww.topUp(SIM, 2000, prefix + "-c", 7);
        return ww.journal();
    }

    static long balanceOf(String prefix) {
        StoreUi.Wallet ww = new StoreUi.Wallet();
        ww.topUp(SIM, 2000, prefix + "-a", 5);
        ww.topUp(SIM, 2000, prefix + "-b", 6);
        ww.topUp(SIM, 2000, prefix + "-c", 7);
        return ww.balance();
    }

    static String catalogJson() throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/founders_catalog.json"));
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}
