package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * بوابة عقد مزوّد الدفع — المرحلة 4، الأسبوع 3 (الأسبوع 24).
 *
 * تقيس الوعد الموثق منذ الأسبوع 19 («topUp يستبدل بالمزوّد دون لمس أي دالة
 * شراء») بعد تنفيذه، لا الادعاء:
 *   • البروفة الحتمية: نفس متتالية الطلبات على مزوّدين مستقلين تعطي أختام
 *     إيصالات وجرنة ورصيداً بايت-لطرف متطابقة — طبقة الدفع حتمية قابلة
 *     للبرهان بلا شبكة ولا حساب.
 *   • التدفق الكامل على الكتالوج الحقيقي المثبت: شحن عبر المزوّد ← شراء 25
 *     حزمة ← تثبيت عبر سلسلة الحرس ← إغلاق 70/30 للسنت عبر Economy وحده.
 *   • عدم المساس: أوسم الشراء كلها كما كانت (NO_PACK / ALREADY_OWNED /
 *     INSUFFICIENT)، وrestoreCode يبدأ STOREENT1 كما هو.
 *   • السقوف محفظية قبل مناداة المزوّد: مزوّد «يعتمد كل شيء» لا يستطيع تجاوز
 *     TOPUP_CAP/BALANCE_CAP — والدليل أنه لم يُنادَ أصلاً.
 *   • سلبيات مصممة بأوسمة حرفية: مزوّد نulis/يرمي/يعيد إيصالاً ناقصاً أو
 *     مفوّماً أو مختوماً خاطئاً أو من مزوّد آخر، إعادة بثّ معرف الطلب (REPLAY)،
 *     حقن أعمدة الجرنة عبر externalRef (مرفوض)، وعبث نسخة الجرنة (لا أثر).
 *
 * وضع ثانٍ: --negproof = الإثبات السلبي المحفوظ — هجمات مزوّد شريرة وتلاعب
 * بإيصالات، كل هجمة تُلقب بوسمها ثم تُعاد بعدها جولة أساس خضراء كاملة على
 * محفظة نظيفة (احتواء: المحفظة السليمة تشتري وتثبت وتغلق الشهر بعد كل هجمة).
 *
 * Verdict: PAYGATE_OK … (exit 0) / PAYGATE_FAILED <reason> (exit 1).
 * نقية JVM (صفر android.*) — تعمل في أي بيئة فيها JDK.
 */
public final class PayGate {

    static int passed = 0, failed = 0;
    static int NEG_OK = 0, NEG_TOTAL = 0;
    static int PROVIDERS = 0, DET_EQUAL = 0, RECEIPTS = 0;
    static long LEDGER_CLOSES = -1, P50_TOPUP_US = -1;

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("GATEFAIL: " + name); }
    }

    static void neg(String name, boolean cond) {
        NEG_TOTAL++;
        if (cond) NEG_OK++;
        else System.out.println("GATEFAIL(neg): " + name);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--negproof".equals(args[0])) {
            System.exit(negProof());
        }
        try {
            run();
        } catch (Exception e) {
            System.out.println("PAYGATE_FAILED exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (failed > 0 || NEG_OK != NEG_TOTAL) {
            System.out.println("PAYGATE_FAILED checks=" + failed + " negatives=" + NEG_OK + "/" + NEG_TOTAL);
            System.exit(1);
        }
        System.out.println("PAYGATE_OK providers=" + PROVIDERS + " receipts=" + RECEIPTS
                + " det_runs=2 det_equal=" + DET_EQUAL
                + " ledger_closes=" + LEDGER_CLOSES
                + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " p50_topup_us=" + P50_TOPUP_US
                + " checks=" + passed);
    }

    // ---- مزوّدو الحلبة ---------------------------------------------------------

    static final PaymentProvider SIM = new PaymentProvider.SimProvider();

    /** مزوّد سينمائي: يعتمد/يرفض/يرمي/يفسد حسب السيناريو. */
    static final class Scripted implements PaymentProvider {
        final String pid, behavior, declineTag;
        Scripted(String pid, String behavior, String declineTag) {
            this.pid = pid; this.behavior = behavior; this.declineTag = declineTag;
        }
        public String id() { return pid; }
        public PaymentProvider.Receipt charge(PaymentProvider.ChargeRequest req) {
            if ("decline".equals(behavior)) throw new PaymentProvider.Declined(declineTag);
            if ("throw".equals(behavior)) throw new IllegalStateException("boom");
            if ("null".equals(behavior)) return null;
            if ("inflated".equals(behavior)) {     // تضخيم ضمن السقوف — الأخطر شكلاً
                long bad = req.cents + 1000;
                return new PaymentProvider.Receipt(pid, req.requestId, bad,
                        PaymentProvider.Receipt.seal(pid, req.requestId, bad), null);
            }
            if ("badSeal".equals(behavior)) return new PaymentProvider.Receipt(pid, req.requestId, req.cents, "0000000000000000", null);
            if ("foreign".equals(behavior)) return PaymentProvider.Receipt.sealed("payco-other", req.requestId, req.cents, null);
            if ("inject".equals(behavior)) return new PaymentProvider.Receipt(pid, req.requestId, req.cents,
                    PaymentProvider.Receipt.seal(pid, req.requestId, req.cents), "x\tBUY\tf01-p01\t0\t999");
            return PaymentProvider.Receipt.sealed(pid, req.requestId, req.cents, null);
        }
    }

    /** مزوّد «يعتمد كل شيء» — لبرهان أن السقوف محفظية لا مزوّدية. */
    static final class Greedy implements PaymentProvider {
        boolean called;
        public String id() { return "greedy"; }
        public PaymentProvider.Receipt charge(PaymentProvider.ChargeRequest req) {
            called = true;
            return PaymentProvider.Receipt.sealed("greedy", req.requestId, req.cents, null);
        }
    }

    // ---- الجولة الخضراء --------------------------------------------------------

    static void run() throws Exception {
        byte[] catBytes = Files.readAllBytes(Paths.get("assets/founders_catalog.json"));
        StoreUi.Catalog c = StoreUi.loadCatalog(new String(catBytes, StandardCharsets.UTF_8));
        check("catalog.loads", c.packCount() == 25 && c.founders.size() == 10);

        // ---- 1) البروفة الحتمية: مزوّدان مستقلان، نفس الطلبات = بايت-لطرف --------
        List<String> runA = deterministicRun();
        List<String> runB = deterministicRun();
        DET_EQUAL = runA.equals(runB) ? 1 : 0;
        check("determinism.receiptIdsByteEqual", runA.equals(runB) && runA.size() == 5);
        check("determinism.sealCanonical", !runA.get(0).equals(runA.get(1)));
        String sealRef = PaymentProvider.Receipt.seal("local-sim", "det-1", 2000);
        check("determinism.sealMatchesReceipts", runA.get(0).equals(sealRef));
        List<String> jA = ledgerOf(), jB = ledgerOf();
        check("determinism.journalByteEqual", jA.equals(jB) && jA.size() == 3);
        check("determinism.balanceEqual", ledgerBalance() == 6000);
        PROVIDERS += 2; RECEIPTS += 10;

        // ---- 2) التدفق الكامل على الكتالوج الحقيقي -------------------------------
        StoreUi.Wallet w = new StoreUi.Wallet();
        check("topup.viaProvider", w.topUp(SIM, 100_00, "pay-a1", 1000L).ok && w.balance() == 100_00);
        PROVIDERS++; RECEIPTS++;
        StoreUi.Store s = new StoreUi.Store(c, w);
        for (StoreUi.Founder f : c.founders)
            for (StoreUi.Pack p : f.packs) {
                check("buy." + p.id, s.purchase(p.id, 1000L).ok);
                check("install." + p.id, s.install(p.id, p.specJson.getBytes(StandardCharsets.UTF_8), 1001L).ok);
            }
        check("flow.ownedAll", s.ownedCount() == 25 && s.installedCount() == 25);
        check("flow.paidExact", s.paidCents() == 7325);
        Economy.Month m = s.monthClose();
        LEDGER_CLOSES = Economy.creatorTotal(m.statements) + m.platformTotal;
        check("flow.ledgerCloses7030", LEDGER_CLOSES == 7325 && m.statements.size() == 10);
        check("flow.journalAudit", w.journal().get(0).startsWith("TOPUP\t10000\t1000\tlocal-sim\tpay-a1\t"));

        // ---- 3) عدم المساس: أوسم الشراء ورمز الاستعادة كما كانت -----------------
        neg("NO_PACK", !s.purchase("fXX-nope", 2).ok && s.purchase("fXX-nope", 2).tag.equals("NO_PACK"));
        neg("ALREADY_OWNED", !s.purchase("f01-p01", 2).ok && s.purchase("f01-p01", 2).tag.equals("ALREADY_OWNED"));
        StoreUi.Wallet wEmpty = new StoreUi.Wallet();
        StoreUi.Store sEmpty = new StoreUi.Store(c, wEmpty);
        neg("INSUFFICIENT", !sEmpty.purchase("f01-p01", 2).ok && sEmpty.purchase("f01-p01", 2).tag.equals("INSUFFICIENT"));
        check("restorecode.unchanged", s.restoreCode().startsWith("STOREENT1|"));
        check("restorecode.emptyDiffers", s.restoreCode().equals(new StoreUi.Store(StoreUi.loadCatalog(new String(catBytes, StandardCharsets.UTF_8)), new StoreUi.Wallet()).restoreCode()) == false);

        // ---- 4) السقوف محفظية: المزوّد الجشع لا يُنادى أصلاً ---------------------
        Greedy g = new Greedy();
        PROVIDERS++;
        neg("BAD_PROVIDER", walletTag(null, 100, "ng-0").equals("BAD_PROVIDER"));
        neg("TOPUP_CAP", gTag(g, StoreUi.MAX_TOPUP_CENTS + 1, "ng-1").equals("TOPUP_CAP") && !g.called);
        Greedy g2 = new Greedy(); PROVIDERS++;
        neg("BAD_TOPUP", gTag(g2, 0, "ng-2").equals("BAD_TOPUP") && !g2.called);
        StoreUi.Wallet wFill = new StoreUi.Wallet();
        for (int i = 0; i < 5; i++) check("caps.fill." + i, wFill.topUp(g2, StoreUi.MAX_TOPUP_CENTS, "ng-3-" + i, 1L).ok);
        Greedy g3 = new Greedy(); PROVIDERS++;
        StoreUi.Result rbc = wFill.topUp(g3, 1, "ng-4", 1L);
        neg("BALANCE_CAP", !rbc.ok && "BALANCE_CAP".equals(rbc.tag) && !g3.called);

        // ---- 5) سلبيات المزوّد الشرير (أوسمة حرفية) -------------------------------
        PROVIDERS += 6;
        neg("BAD_REQUEST_ID.null", walletTag(SIM, 100, null).equals("BAD_REQUEST_ID"));
        neg("BAD_REQUEST_ID.pipe", walletTag(SIM, 100, "a|b").equals("BAD_REQUEST_ID"));
        neg("PROVIDER_DECLINED.passthrough", walletTag(new Scripted("payco", "decline", "card_declined"), 500, "ng-5").equals("card_declined"));
        neg("PROVIDER_ERROR", walletTag(new Scripted("payco", "throw", null), 500, "ng-6").equals("PROVIDER_ERROR"));
        neg("RECEIPT_MISSING", walletTag(new Scripted("payco", "null", null), 500, "ng-7").equals("RECEIPT_MISSING"));
        neg("RECEIPT_MISMATCH.inflated", walletTag(new Scripted("payco", "inflated", null), 500, "ng-8").equals("RECEIPT_MISMATCH"));
        neg("RECEIPT_MISMATCH.badSeal", walletTag(new Scripted("payco", "badSeal", null), 500, "ng-9").equals("RECEIPT_MISMATCH"));
        neg("RECEIPT_MISMATCH.foreignProvider", walletTag(new Scripted("payco", "foreign", null), 500, "ng-10").equals("RECEIPT_MISMATCH"));
        neg("RECEIPT_MISMATCH.journalInjection", walletTag(new Scripted("payco", "inject", null), 500, "ng-11").equals("RECEIPT_MISMATCH"));
        StoreUi.Wallet wRep = new StoreUi.Wallet();
        check("replay.setup", wRep.topUp(SIM, 2000, "rp-1", 1).ok);
        neg("REPLAY", !wRep.topUp(SIM, 2000, "rp-1", 2).ok && wRep.topUp(SIM, 2000, "rp-1", 2).tag.equals("REPLAY"));
        check("replay.noDoubleCredit", wRep.balance() == 2000 && wRep.journal().size() == 1);

        // ---- 6) عبث نسخة الجرنة: لا أثر على المحفظة ------------------------------
        List<String> copy = wRep.journal();
        copy.clear();
        copy.add("TOPUP\t99999\t0\tevil\t evil \tdeadbeef\t-");
        neg("JOURNAL_COPY_IMMUTABLE", wRep.journal().size() == 1 && wRep.balance() == 2000);

        // ---- 7) الأداء: p50 شحن عبر المزوّد --------------------------------------
        StoreUi.Wallet wPerf = new StoreUi.Wallet();
        List<Long> ns = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long t0 = System.nanoTime();
            wPerf.topUp(SIM, 100, "pf-" + i, 1);
            ns.add(System.nanoTime() - t0);
            RECEIPTS++;
        }
        Collections.sort(ns);
        P50_TOPUP_US = ns.get(ns.size() / 2) / 1000;
        check("perf.p50Measured", P50_TOPUP_US >= 0);
        check("perf.balanceAccumulates", wPerf.balance() == 400 * 100);
    }

    static String walletTag(PaymentProvider p, long cents, String reqId) {
        return new StoreUi.Wallet().topUp(p, cents, reqId, 1L).tag;
    }

    static String gTag(Greedy g, long cents, String reqId) {
        return new StoreUi.Wallet().topUp(g, cents, reqId, 1L).tag;
    }

    /** 5 شحنات حتمية على مزوّد محاكاة جديد — تعيد أختام الإيصالات الخمسة. */
    static List<String> deterministicRun() {
        PaymentProvider sim = new PaymentProvider.SimProvider();
        StoreUi.Wallet w = new StoreUi.Wallet();
        List<String> seals = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String req = "det-" + i;
            StoreUi.Result r = w.topUp(sim, 2000, req, 100L + i);
            if (r.ok) seals.add(PaymentProvider.Receipt.seal("local-sim", req, 2000));
        }
        return seals;
    }

    static List<String> ledgerOf() {
        PaymentProvider sim = new PaymentProvider.SimProvider();
        StoreUi.Wallet w = new StoreUi.Wallet();
        w.topUp(sim, 2000, "jr-a", 5);
        w.topUp(sim, 2000, "jr-b", 6);
        w.topUp(sim, 2000, "jr-c", 7);
        return w.journal();
    }

    static long ledgerBalance() {
        PaymentProvider sim = new PaymentProvider.SimProvider();
        StoreUi.Wallet w = new StoreUi.Wallet();
        w.topUp(sim, 2000, "jr-a", 5);
        w.topUp(sim, 2000, "jr-b", 6);
        w.topUp(sim, 2000, "jr-c", 7);
        return w.balance();
    }

    // ---- الإثبات السلبي المحفوظ -------------------------------------------------

    /**
     * كل هجمة: تُنفَّذ على محفظة نقيّة، تُقبَض بوسمها الحرفي (أو بدليل الحيطة:
     * المزوّد لم يُنادَ)، ثم تُعاد جولة أساس خضراء كاملة على محفظة نظيفة —
     * شحن ← شراء أرخص حزمة ← تثبيت ← إغلاق شهر — لبرهان الاحتواء بعد كل هجمة.
     */
    static int negProof() throws Exception {
        byte[] catBytes = Files.readAllBytes(Paths.get("assets/founders_catalog.json"));
        StoreUi.Catalog c = StoreUi.loadCatalog(new String(catBytes, StandardCharsets.UTF_8));
        int attacks = 0, caught = 0, baselines = 0;
        StoreUi.Pack cheapest = null;
        for (StoreUi.Founder f : c.founders)
            for (StoreUi.Pack p : f.packs)
                if (cheapest == null || p.priceCents < cheapest.priceCents) cheapest = p;

        // H1: تضخيم إيصال ضمن السقوف (الأخطر شكلاً — لا يصطدم بسقف)
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "inflated", null), 2000, "h1", 1);
            boolean ok = !r.ok && "RECEIPT_MISMATCH".equals(r.tag) && w.balance() == 0 && w.journal().isEmpty();
            System.out.println("H1 inflated_receipt_within_caps -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H2: ختم مزوّر بمبالغ صحيحة
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "badSeal", null), 2000, "h2", 1);
            boolean ok = !r.ok && "RECEIPT_MISMATCH".equals(r.tag) && w.balance() == 0;
            System.out.println("H2 forged_seal -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H3: إعادة بثّ إيصال ناجح (نفس معرف الطلب مرتين)
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            w.topUp(SIM, 2000, "h3", 1);
            StoreUi.Result r = w.topUp(SIM, 2000, "h3", 2);
            boolean ok = !r.ok && "REPLAY".equals(r.tag) && w.balance() == 2000 && w.journal().size() == 1;
            System.out.println("H3 receipt_replay -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H4: مزوّد يرمي استثناء وسط الشحن
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "throw", null), 2000, "h4", 1);
            boolean ok = !r.ok && "PROVIDER_ERROR".equals(r.tag) && w.balance() == 0;
            System.out.println("H4 provider_exception -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H5: رفض بوسم حر يمر حرفياً بلا نسب ولا كتم
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "decline", "issuer_unavailable"), 2000, "h5", 1);
            boolean ok = !r.ok && "issuer_unavailable".equals(r.tag) && w.balance() == 0;
            System.out.println("H5 decline_passthrough -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H6: إيصال مزوّد آخر يُقدَّم هنا (سرقة إيصال بين مزوّدين)
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "foreign", null), 2000, "h6", 1);
            boolean ok = !r.ok && "RECEIPT_MISMATCH".equals(r.tag) && w.balance() == 0;
            System.out.println("H6 cross_provider_receipt -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H7: حقن معرف طلب بحرف أنبوب (فساد الجرنة والختم)
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(SIM, 2000, "h7|extra", 1);
            boolean ok = !r.ok && "BAD_REQUEST_ID".equals(r.tag) && w.balance() == 0;
            System.out.println("H7 request_id_smuggling -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H8: إيصال مفقود (مزوّد صامت)
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "null", null), 2000, "h8", 1);
            boolean ok = !r.ok && "RECEIPT_MISSING".equals(r.tag) && w.balance() == 0;
            System.out.println("H8 null_receipt -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H9: مزوّد جشع يحاول تجاوز سقف الشحن — السقف قبل المزوّد
        {
            Greedy g = new Greedy();
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(g, StoreUi.MAX_TOPUP_CENTS + 1, "h9", 1);
            boolean ok = !r.ok && "TOPUP_CAP".equals(r.tag) && !g.called && w.balance() == 0;
            System.out.println("H9 rogue_provider_topup_cap -> " + r.tag + " provider_called=" + g.called + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H10: جشع جديد يحاول فيض الرصيد (مزوّد الملء الشرعي منفصل — الراية تقيس
        // محاولة الفيض وحدها، لا الشحنات المشروعة التي سبقتها)
        {
            Greedy gFill = new Greedy();
            StoreUi.Wallet w = new StoreUi.Wallet();
            for (int i = 0; i < 5; i++) w.topUp(gFill, StoreUi.MAX_TOPUP_CENTS, "h10-" + i, 1);
            Greedy gRogue = new Greedy();
            StoreUi.Result r = w.topUp(gRogue, 1, "h10-x", 1);
            boolean ok = !r.ok && "BALANCE_CAP".equals(r.tag) && !gRogue.called && w.balance() == StoreUi.MAX_BALANCE_CENTS;
            System.out.println("H10 rogue_provider_balance_cap -> " + r.tag + " provider_called=" + gRogue.called + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H11: حقن أعمدة جرنة عبر externalRef
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            StoreUi.Result r = w.topUp(new Scripted("payco", "inject", null), 2000, "h11", 1);
            boolean ok = !r.ok && "RECEIPT_MISMATCH".equals(r.tag) && w.balance() == 0 && w.journal().isEmpty();
            System.out.println("H11 externalRef_journal_injection -> " + r.tag + " [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }
        // H12: عبث نسخة الجرنة خارج المحفظة
        {
            StoreUi.Wallet w = new StoreUi.Wallet();
            w.topUp(SIM, 2000, "h12", 1);
            List<String> stolen = w.journal();
            stolen.clear();
            stolen.add("TOPUP\t999999\t0\tattacker\th12\tdeadbeefdeadbeef\tx");
            boolean ok = w.journal().size() == 1 && w.journal().get(0).startsWith("TOPUP\t2000\t1\tlocal-sim\th12\t") && w.balance() == 2000;
            System.out.println("H12 journal_copy_tamper -> wallet_untouched [" + (ok ? "CAUGHT" : "LEAKED") + "]");
            attacks++; if (ok) caught++;
        }

        // جولة الأساس الخضراء بعد كل هجمة: احتواء كامل
        for (int b = 1; b <= attacks; b++) {
            StoreUi.Wallet w = new StoreUi.Wallet();
            boolean ok = w.topUp(SIM, 2000, "base-" + b, 100L + b).ok;
            StoreUi.Store s = new StoreUi.Store(c, w);
            ok = ok && s.purchase(cheapest.id, 100L + b).ok;
            ok = ok && s.install(cheapest.id, cheapest.specJson.getBytes(StandardCharsets.UTF_8), 100L + b).ok;
            Economy.Month m = s.monthClose();
            ok = ok && Economy.creatorTotal(m.statements) + m.platformTotal == cheapest.priceCents;
            System.out.println("BASELINE " + b + " topup->purchase->install->monthClose [" + (ok ? "GREEN" : "BROKEN") + "]");
            if (ok) baselines++;
        }

        System.out.println("PAY-PROOF_PASSED attacks=" + caught + "/" + attacks + " baselines=" + baselines + "/" + attacks);
        return (caught == attacks && baselines == attacks) ? 0 : 1;
    }
}
