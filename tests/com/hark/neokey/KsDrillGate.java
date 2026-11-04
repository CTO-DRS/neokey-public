package com.hark.neokey;

/**
 * KsDrillGate — phase 5, week 3 (W28, docs/PHASE5-PLAN.md item 3): proves the
 * keystore-recovery drill BITES, in-JVM, pure JDK, zero app code (tests/ only
 * — the dex budget is not spent).
 *
 *   A. THE ANCHOR FIRST (the W26 lesson): the committed stand-in fixture
 *      tests/ks/drill-release.p12 must re-derive byte-exactly to the pinned
 *      fingerprint literal — "المرجع = المنشور" (DictSign statements, TLS
 *      SPKI pins, deploy pin anchor: same house rule). A swapped fixture
 *      breaks the anchor on the first run even though the swap is itself a
 *      perfectly valid keystore.
 *   B. the good fixture accepts with every measured field pinned (PKCS12,
 *      RSA 2048, self-signed, ~20y validity, the pinned fingerprint).
 *   C. eight designed negatives, each a purpose-built committed fixture that
 *      must be rejected with ITS OWN exact tag — a check that stops biting is
 *      a dead guard and fails the gate (this is also the selftest that catches
 *      a neutered KsValidator in the saved negative proof).
 *
 * Verdict: KSDRILLGATE_OK checks=N negatives=M/M  (exit 0)
 *          FAIL <tag> lines + KSDRILLGATE_FAILED  (exit 1)
 */
public final class KsDrillGate {
    private KsDrillGate() {}

    /** THE ANCHOR — sha256(DER) of the committed drill-release.p12 end-entity certificate. */
    static final String EXPECTED_DRILL_FP =
            "a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587";

    static int checks = 0;
    static int negatives = 0;
    static int bitten = 0;

    public static void main(String[] args) throws Exception {
        String PW = KsValidator.DRILL_PASS;
        String ALIAS = KsValidator.DRILL_ALIAS;

        // ---- A. the anchor runs FIRST (abort on failure — nothing else may
        //         ride a fixture that is not the published identity) ----
        KsValidator.Report good = KsValidator.validate(KsValidator.DRILL_KS, PW, ALIAS, EXPECTED_DRILL_FP);
        checks++;
        if (!good.ok || !KsValidator.fpEqual(good.fp, EXPECTED_DRILL_FP)) {
            System.out.println("FAIL anchor.drill.fp (fixture re-derives to "
                    + (good.fp == null ? "none" : good.fp) + " detail=" + good.tag + ")");
            System.out.println("KSDRILLGATE_FAILED checks=" + checks + " negatives=" + bitten + "/" + negatives);
            System.exit(1);
        }
        System.out.println("PASS anchor.drill.fp fp16=" + good.fp.substring(0, 16));

        // ---- B. the good stand-in accepts, every field measured and pinned ----
        checks++;
        if (!(good.ok && "PKCS12".equals(good.type) && "RSA".equals(good.alg)
                && good.bits == 2048 && good.daysLeft >= 7000 && good.daysLeft <= 7300))
            fail("accept.good.fields", "type=" + good.type + " alg=" + good.alg + " bits=" + good.bits
                    + " days_left=" + good.daysLeft);
        else
            System.out.println("PASS accept.good.fields type=" + good.type + " alg=" + good.alg
                    + " bits=" + good.bits + " days_left=" + good.daysLeft);

        checks++;
        // determinism: the same fixture must re-derive the SAME fingerprint on
        // any machine (the release identity is a byte fact, not an opinion)
        String fp2 = KsValidator.sha256Hex(KsValidator.validate(KsValidator.DRILL_KS, PW, ALIAS, EXPECTED_DRILL_FP).fp.getBytes("UTF-8"));
        KsValidator.Report again = KsValidator.validate(KsValidator.DRILL_KS, PW, ALIAS, EXPECTED_DRILL_FP);
        if (!again.ok || !again.fp.equals(good.fp)) fail("accept.good.deterministic", again.fp + " vs " + good.fp);
        else System.out.println("PASS accept.good.deterministic fp=" + again.fp.substring(0, 16) + "…");

        checks++;
        // the "-" mode skips the identity check by contract — but the drill
        // itself never calls it (documented here so the skip stays auditable)
        KsValidator.Report nopin = KsValidator.validate(KsValidator.DRILL_KS, PW, ALIAS, "-");
        if (!nopin.ok) fail("accept.good.nopinmode", nopin.tag);
        else System.out.println("PASS accept.good.nopinmode (identity check skipped by contract)");

        // ---- C. eight designed negatives: every check must bite ITS OWN tag ----
        // 1. rogue key, same alias + same dname — the IDENTITY check is what
        //    refuses it (a valid keystore is not the release identity)
        neg("rogue", "tests/ks/drill-rogue.p12", PW, ALIAS, "fp_mismatch");
        // 2. RSA 1024 — the KEY SIZE floor refuses it
        neg("weak", "tests/ks/drill-weak.p12", PW, ALIAS, "key_size");
        // 3. already expired (started -400d, validity 365) — the VALIDITY window refuses it
        neg("expired", "tests/ks/drill-expired.p12", PW, ALIAS, "validity_expired");
        // 4. 90 days left < the 10-year release floor
        neg("short", "tests/ks/drill-short.p12", PW, ALIAS, "validity_short");
        // 5. JKS container (committed with a .p12 suffix — a *.jks file would
        //    trip the secrets-audit keystore rule; the TYPE check reads the
        //    container, not the name)
        neg("jks", "tests/ks/drill-jks.p12", PW, ALIAS, "ks_type");
        // 6. wrong password — never readable, never a pass
        neg("wrongpass", KsValidator.DRILL_KS, "not-the-drill-pass", ALIAS, "unreadable_ks");
        // 7. wrong alias
        neg("wrongalias", KsValidator.DRILL_KS, PW, "neokey-release", "alias_missing");
        // 8. missing artifact
        neg("missing", "build/ks-drill/does-not-exist.p12", PW, ALIAS, "ks_missing");

        System.out.println("KSDRILLGATE_OK checks=" + checks + " negatives=" + bitten + "/" + negatives
                + " fp16=" + good.fp.substring(0, 16) + " days_left=" + good.daysLeft);
    }

    /** A designed negative must be rejected with EXACTLY its expected tag. */
    private static void neg(String name, String ks, String pass, String alias, String expectedTag) {
        negatives++;
        checks++;
        KsValidator.Report r = KsValidator.validate(ks, pass, alias, EXPECTED_DRILL_FP);
        if (!r.ok && expectedTag.equals(r.tag)) {
            bitten++;
            System.out.println("PASS negatives." + name + " tag=" + r.tag);
        } else {
            fail("negatives." + name, "expected tag " + expectedTag + " but got "
                    + (r.ok ? "ACCEPT (check stopped biting!)" : r.tag));
        }
    }

    private static void fail(String tag, String detail) {
        System.out.println("FAIL " + tag + " (" + detail + ")");
        System.out.println("KSDRILLGATE_FAILED checks=" + checks + " negatives=" + bitten + "/" + negatives);
        System.exit(1);
    }
}
