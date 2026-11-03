package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.hark.neokey.Economy.*;

/**
 * اختبارات اقتصاد المبدعين 70/30 — المرحلة 2، البند B: دقة التقسيم بالسنتات
 * (لا فاصلة عائمة)، انغلاق الدفتر محاسبياً، الاسترداد والتعديلات، حد الأدنى
 * للدفع والحمل للشهر التالي، وجوبية الدفعة (idempotence)، والترتيب الحتمي.
 */
public class EconomyTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static boolean eq(long[] a, long[] b) { return Arrays.equals(a, b); }

    public static void main(String[] args) {
        // ---- the split: exact integers, sums to the price, no float ----
        check("split.70-30-100", eq(split(100), new long[]{70, 30}));
        check("split.149-floor", eq(split(149), new long[]{104, 45}));
        check("split.1-cent", eq(split(1), new long[]{0, 1}));
        check("split.10-cents", eq(split(10), new long[]{7, 3}));
        check("split.499", eq(split(499), new long[]{349, 150}));
        check("split.999999", eq(split(999999), new long[]{699999, 300000}));
        check("split.zero", eq(split(0), new long[]{0, 0}));
        boolean negRejected = false;
        try { split(-1); } catch (IllegalArgumentException e) { negRejected = true; }
        check("split.negative-refused", negRejected);
        boolean fracRejected = false;
        try { new Economy.Sale("f01", "p", 1, 0); } catch (IllegalArgumentException e) { fracRejected = true; }
        check("sale.zero-price-refused", fracRejected);
        boolean badKind = false;
        List<Sale> bad = new ArrayList<Sale>();
        bad.add(new Sale("f01", "p", 7, 100));
        try { monthClose(bad); } catch (IllegalArgumentException e) { badKind = true; }
        check("sale.bad-kind-refused", badKind);

        // ---- simple month: two vendors, closes exactly ----
        List<Sale> ev = new ArrayList<Sale>();
        ev.add(Sale.sale("f01", "f01-p01", 200));
        ev.add(Sale.sale("f01", "f01-p01", 300));
        ev.add(Sale.sale("f02", "f02-p01", 100));
        Month m = monthClose(ev);
        check("month.statements", m.statements.size() == 2);
        Statement s1 = m.statements.get(0), s2 = m.statements.get(1);
        check("month.f01-aggregate", s1.sales == 2 && s1.gross == 500 && s1.net == 500);
        check("month.f01-split", s1.creatorNet == 350 && s1.platformNet == 150);
        check("month.f02-split", s2.creatorNet == 70 && s2.platformNet == 30);
        check("month.closes", creatorTotal(m.statements) + m.platformTotal == 600);

        // ---- refunds reverse cent-for-cent ----
        List<Sale> ev2 = new ArrayList<Sale>();
        ev2.add(Sale.sale("f01", "f01-p01", 200));
        ev2.add(Sale.refund("f01", "f01-p01", 200));
        ev2.add(Sale.sale("f01", "f01-p02", 100));
        Month m2 = monthClose(ev2);
        Statement r1 = m2.statements.get(0);
        check("refund.netted", r1.gross == 300 && r1.refunded == 200 && r1.net == 100);
        check("refund.split-of-net", r1.creatorNet == 70 && r1.platformNet == 30);
        check("refund.counted", r1.sales == 2 && r1.refunds == 1);

        // ---- refund exceeding gross: creator owes nothing, platform absorbs, reported ----
        List<Sale> ev3 = new ArrayList<Sale>();
        ev3.add(Sale.refund("f01", "f01-p01", 500));
        Month m3 = monthClose(ev3);
        Statement a = m3.statements.get(0);
        check("adjustment.creator-zero", a.creatorNet == 0);
        check("adjustment.reported", a.adjustments == 500 && a.platformNet == -500);
        check("adjustment.platform-total", m3.platformTotal == -500);

        // ---- min payout: below the bar carries, at the bar pays ----
        List<Sale> ev4 = new ArrayList<Sale>();
        ev4.add(Sale.sale("f01", "f01-p01", 1500));   // creator 1050 >= MIN_PAYOUT → pays
        ev4.add(Sale.sale("f02", "f02-p01", 999));    // creator 699 < MIN → carries
        Month m4 = monthClose(ev4);
        Statement p1 = m4.statements.get(0), p2 = m4.statements.get(1);
        check("payout.above-bar-pays", p1.pay() == 1050 && p1.paidOut == 1050 && p1.carried == 0);
        check("payout.below-bar-carries", p2.pay() == 0 && p2.carried == 699);
        check("payout.idempotent", p1.pay() == 0 && p2.pay() == 0);
        check("payout.conserves", 1050 + 699 == creatorTotal(m4.statements));

        // ---- determinism: same events → same ledger, vendor-id order ----
        Month m5 = monthClose(ev);   // same events re-closed
        check("determinism.statements-order", m5.statements.get(0).vendor.equals("f01")
                && m5.statements.get(1).vendor.equals("f02") && m5.statements.size() == m.statements.size());
        check("determinism.same-money", creatorTotal(m5.statements) == creatorTotal(m.statements)
                && m5.platformTotal == m.platformTotal);

        // ---- unknown vendor still settled (appended at the end) ----
        List<Sale> ev6 = new ArrayList<Sale>();
        ev6.add(Sale.sale("f99", "x", 100));
        Month m6 = monthClose(ev6);
        check("unknown-vendor.settled", m6.statements.size() == 1
                && m6.statements.get(0).vendor.equals("f99") && m6.statements.get(0).creatorNet == 70);

        System.out.println();
        System.out.println("EconomyTest: " + pass + " pass, " + fail + " fail");
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
