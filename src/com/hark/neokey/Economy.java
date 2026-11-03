package com.hark.neokey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creator economy (roadmap Phase 2, item B — اقتصاد 70/30).
 *
 * Pure JVM, exact integer-cents arithmetic — no floating point anywhere near
 * money. The split is defined so the two shares always SUM to the price:
 * creator gets floor(price × 70 / 100), platform gets the remainder, so an
 * odd cent never appears or disappears (price 1 → creator 0 / platform 1).
 *
 * monthClose() produces one statement per creator from a month of sales and
 * refunds: refunds reverse their sale cent-for-cent, a creator balance never
 * goes negative in its own statement (a refund without enough gross is an
 * adjustment the platform absorbs and reports), balances below MIN_PAYOUT_CENTS
 * carry to the next month, payable balances are paid in full. pay() is
 * idempotent — a second run on the same statement pays exactly zero.
 * Statements are ordered by vendor id (deterministic, same input = same ledger).
 */
public final class Economy {

    private Economy() { }

    public static final int CREATOR_PCT = 70;
    public static final int PLATFORM_PCT = 30;
    public static final long MIN_PAYOUT_CENTS = 1000;   // $10.00

    /** Exact split: {creator, platform}, creator + platform == price always. */
    public static long[] split(long priceCents) {
        if (priceCents < 0) throw new IllegalArgumentException("negative price");
        long creator = priceCents * CREATOR_PCT / 100;
        return new long[]{creator, priceCents - creator};
    }

    /** One store event. kind: 1 = sale, 2 = refund (priceCents always positive). */
    public static final class Sale {
        public final String vendor, packId;
        public final int kind;
        public final long priceCents;
        public Sale(String vendor, String packId, int kind, long priceCents) {
            if (priceCents <= 0) throw new IllegalArgumentException("price must be positive cents");
            this.vendor = vendor; this.packId = packId; this.kind = kind; this.priceCents = priceCents;
        }
        public static Sale sale(String v, String p, long c) { return new Sale(v, p, 1, c); }
        public static Sale refund(String v, String p, long c) { return new Sale(v, p, 2, c); }
    }

    /** Monthly statement for one creator — exact to the cent. */
    public static final class Statement {
        public final String vendor;
        public final int sales, refunds;
        public final long gross, refunded, net;          // store-level money (gross = sales sum)
        public final long creatorNet, platformNet;       // the 70/30 split OF THE NET
        public final long adjustments;                   // refunds that exceeded a creator's gross (platform absorbed)
        public long paidOut, carried;                    // filled by payout
        Statement(String vendor, int sales, int refunds, long gross, long refunded, long net,
                  long creatorNet, long platformNet, long adjustments) {
            this.vendor = vendor; this.sales = sales; this.refunds = refunds;
            this.gross = gross; this.refunded = refunded; this.net = net;
            this.creatorNet = creatorNet; this.platformNet = platformNet; this.adjustments = adjustments;
        }

        /** Idempotent payout: pays once, then zero. Below the minimum → carried. */
        public long pay() {
            if (paidOut > 0 || carried > 0) return 0;
            if (creatorNet < MIN_PAYOUT_CENTS) { carried = creatorNet; return 0; }
            paidOut = creatorNet;
            return paidOut;
        }
    }

    public static final class Month {
        public final List<Statement> statements;         // vendor-id order, deterministic
        public final long platformTotal;                 // 30% side, adjustments included
        Month(List<Statement> s, long platformTotal) { this.statements = s; this.platformTotal = platformTotal; }
    }

    /**
     * Closes a month: aggregate by vendor, net out refunds, split 70/30 exactly.
     * A refund may target a vendor whose gross it exceeds — the excess is an
     * adjustment absorbed by the platform (reported, never hidden).
     */
    public static Month monthClose(List<Sale> events) {
        Map<String, long[]> agg = new LinkedHashMap<String, long[]>();   // vendor -> {sales,refunds,gross,refunded}
        for (Sale e : events) {
            long[] a = agg.get(e.vendor);
            if (a == null) { a = new long[4]; agg.put(e.vendor, a); }
            if (e.kind == 1) { a[0]++; a[2] += e.priceCents; }
            else if (e.kind == 2) { a[1]++; a[3] += e.priceCents; }
            else throw new IllegalArgumentException("bad kind");
        }
        List<Statement> out = new ArrayList<Statement>();
        long platform = 0;
        List<String> order = new ArrayList<String>(agg.keySet());
        java.util.Collections.sort(order);              // vendor-id order, always deterministic
        for (String vendor : order) {
            long[] a = agg.get(vendor);
            long net = a[2] - a[3];
            long creatorNet, platformNet, adjustments = 0;
            if (net >= 0) {
                long[] sp = split(net);
                creatorNet = sp[0]; platformNet = sp[1];
            } else {
                // refund exceeded gross: the creator owes nothing back — platform absorbs the loss
                creatorNet = 0;
                platformNet = net;               // negative
                adjustments = -net;
            }
            out.add(new Statement(vendor, (int) a[0], (int) a[1], a[2], a[3], net,
                    creatorNet, platformNet, adjustments));
            platform += platformNet;
        }
        return new Month(out, platform);
    }

    /** Σ creatorNet over statements — used by the gate to prove the ledger closes. */
    public static long creatorTotal(List<Statement> st) {
        long t = 0; for (Statement s : st) t += s.creatorNet; return t;
    }
}
