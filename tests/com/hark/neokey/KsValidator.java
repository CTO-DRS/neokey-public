package com.hark.neokey;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Key;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

/**
 * KsValidator — the release-keystore acceptance validator of the week-28
 * secrets-recovery drill (phase 5, item 3, docs/PHASE5-PLAN.md).
 *
 * The documented pending ("استعادة أسرار التوقيع من النسخة المشفرة قبل أي بناء
 * موقّع" — NK-SEC-001 §2.2 + store-readiness) has two halves: the SECRET
 * itself never leaves the owner's vault (not reproducible here, stays
 * pending), and the PROCEDURE that decides "is this restored keystore the
 * release identity, valid for release?" — that half is now automated and
 * proven. This class IS that decision. The owner restores from the encrypted
 * backup and runs exactly this validator on the restored artifact; in CI the
 * same validator runs on a committed STAND-IN fixture (tests/ks/drill-*.p12,
 * generated only for the drill — documented passwords, zero production
 * material), and KsDrillGate proves every check still bites.
 *
 * Fail-closed everywhere: every rejection has ONE exact tag (the drill's
 * contract), and any unexpected exception is unreadable_ks — never a pass.
 * Checks, in deterministic order:
 *   ks_missing      — the artifact does not exist
 *   unreadable_ks   — not loadable as PKCS12 (wrong password, corrupt)
 *   ks_type         — the CONTAINER is not PKCS12 (magic-byte sniff:
 *                     JKS 0xFEEDFEED / JCEKS 0xCECECECE — read from the
 *                     bytes, NOT from the JDK, whose keystore.type.compat
 *                     mode happily loads a JKS through a PKCS12 instance)
 *   alias_missing   — the release alias is not in the store
 *   no_private_key  — the alias is not a PrivateKeyEntry
 *   no_cert_chain   — no certificate chain on the alias
 *   not_x509        — the end-entity certificate is not X.509
 *   not_self_signed — subject != issuer (a release keystore is self-signed)
 *   key_alg         — key algorithm is not RSA
 *   key_size        — RSA modulus below 2048 bits
 *   bad_signature   — the self-signature does not verify
 *   not_yet_valid   — now is before notBefore
 *   validity_expired— now is after notAfter
 *   validity_short  — days remaining below the 10-year release floor
 *   fp_mismatch     — sha256(cert DER) != the published fingerprint
 *                     ("المرجع = المنشور" — the identity anchor, the same
 *                     house rule as DictSign statements and TLS SPKI pins)
 *
 * Pure JDK, zero android.* — runs on any JRE 8+, CI or the owner's machine.
 * CLI:  java com.hark.neokey.KsValidator <keystore> <password> <alias> [expectedFp|-]
 *       exit 0 + "KSDRILL_OK …" on acceptance, exit 2 + "KSDRILL_FAIL <tag>" otherwise.
 */
public final class KsValidator {
    private KsValidator() {}

    /** release acceptance floor: RSA ≥ 2048 bits (RSABSafe/Play current floor). */
    public static final int MIN_RSA_BITS = 2048;
    /** release acceptance floor: ≥ 10 years remaining (Play requires validity to ≥ 2033). */
    public static final long MIN_DAYS_LEFT = 3650;

    // ---- drill stand-in constants (tests/ks/drill-release.p12, committed) ----
    // These are DRILL fixtures: generated only for this exercise, passwords are
    // documented here on purpose (a drill whose password is a secret defeats
    // the drill). The REAL keystore and its password live in the owner's vault
    // only and never appear anywhere in this repository.
    public static final String DRILL_KS = "tests/ks/drill-release.p12";
    public static final String DRILL_PASS = "drill-pass-2026";
    public static final String DRILL_ALIAS = "neokey-drill";

    public static final class Report {
        public boolean ok;
        public String tag;
        public String detail;
        public String type;
        public String alg;
        public int bits;
        public long daysLeft;
        public String fp;
    }

    /** Validate one keystore artifact. expectedFpHex may be "-" to skip the identity check (the DRILL never skips it). */
    public static Report validate(String path, String pass, String alias, String expectedFpHex) {
        Report r = new Report();
        try {
            File f = new File(path);
            if (!f.isFile()) return fail(r, "ks_missing", path);

            // the TYPE check reads the CONTAINER BYTES, not the JDK's opinion:
            // keystore.type.compat (on by default) makes the PKCS12 instance
            // load a JKS file without complaint — the only honest reject is
            // the file's own magic, before any load happens.
            String sniff = containerMagic(path);
            if (sniff.equals("feedfeed") || sniff.equals("cececece"))
                return fail(r, "ks_type", "container is JKS/JCEKS (magic " + sniff + ") not PKCS12");

            // load: PKCS12 is the required container; anything unloadable is
            // unreadable_ks (wrong password, corrupt file).
            KeyStore ks;
            try {
                ks = tryLoad(path, pass, "PKCS12");
            } catch (Exception e1) {
                return fail(r, "unreadable_ks", "load failed: " + e1.getClass().getSimpleName());
            }

            if (!ks.containsAlias(alias)) return fail(r, "alias_missing", alias);
            if (!ks.isKeyEntry(alias)) return fail(r, "no_private_key", alias);
            Certificate[] chain = ks.getCertificateChain(alias);
            if (chain == null || chain.length == 0) return fail(r, "no_cert_chain", alias);
            if (!(chain[0] instanceof X509Certificate)) return fail(r, "not_x509", chain[0].getClass().getName());
            X509Certificate cert = (X509Certificate) chain[0];
            if (!cert.getSubjectDN().getName().equals(cert.getIssuerDN().getName()))
                return fail(r, "not_self_signed", cert.getSubjectDN() + " != " + cert.getIssuerDN());

            Key key = ks.getKey(alias, pass.toCharArray());
            if (!(key instanceof PrivateKey)) return fail(r, "no_private_key", key == null ? "null" : key.getClass().getName());
            if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) return fail(r, "key_alg", key.getAlgorithm());
            if (!(cert.getPublicKey() instanceof RSAPublicKey)) return fail(r, "key_alg", cert.getPublicKey().getAlgorithm());
            int bits = ((RSAPublicKey) cert.getPublicKey()).getModulus().bitLength();
            if (bits < MIN_RSA_BITS) return fail(r, "key_size", bits + "<" + MIN_RSA_BITS);

            try {
                cert.verify(cert.getPublicKey());
            } catch (Exception e) {
                return fail(r, "bad_signature", e.getClass().getSimpleName());
            }

            Date now = new Date();
            try {
                cert.checkValidity(now);
            } catch (CertificateNotYetValidException e) {
                return fail(r, "not_yet_valid", "notBefore=" + cert.getNotBefore());
            } catch (CertificateExpiredException e) {
                return fail(r, "validity_expired", "notAfter=" + cert.getNotAfter());
            }
            long daysLeft = (cert.getNotAfter().getTime() - now.getTime()) / 86400000L;
            if (daysLeft < MIN_DAYS_LEFT) return fail(r, "validity_short", daysLeft + "<" + MIN_DAYS_LEFT);

            String fp = sha256Hex(cert.getEncoded());
            if (expectedFpHex != null && expectedFpHex.length() > 0 && !"-".equals(expectedFpHex)) {
                if (!fpEqual(fp, expectedFpHex)) return fail(r, "fp_mismatch", fp + " != " + expectedFpHex);
            }

            r.ok = true;
            r.type = ks.getType();
            r.alg = key.getAlgorithm();
            r.bits = bits;
            r.daysLeft = daysLeft;
            r.fp = fp;
            return r;
        } catch (Exception e) {
            return fail(r, "unreadable_ks", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static KeyStore tryLoad(String path, String pass, String type) throws Exception {
        InputStream in = new FileInputStream(path);
        try {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(in, pass.toCharArray());
            return ks;
        } finally {
            try { in.close(); } catch (Exception ignore) {}
        }
    }

    /** first four container bytes as hex — the container's own identity, JDK-independent. */
    private static String containerMagic(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        try {
            byte[] m = new byte[4];
            int n = 0;
            while (n < 4) { int got = in.read(m, n, 4 - n); if (got < 0) break; n += got; }
            StringBuilder sb = new StringBuilder(n * 2);
            for (int i = 0; i < n; i++) sb.append(Character.forDigit((m[i] >> 4) & 0xF, 16)).append(Character.forDigit(m[i] & 0xF, 16));
            return sb.toString();
        } finally {
            try { in.close(); } catch (Exception ignore) {}
        }
    }

    /** The identity comparison of the drill. Deliberately one tiny named method: if it stops comparing, the gate's rogue-negative stops biting and the gate fails — a neutered identity check is never a silent pass. */
    public static boolean fpEqual(String a, String b) {
        if (a == null || b == null) return false;
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s.trim().toLowerCase().replace(":", "");
    }

    /** sha256 over the certificate DER — the same derivation the TLS arena pin and DictSign statements use. */
    public static String sha256Hex(byte[] der) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static Report fail(Report r, String tag, String detail) {
        r.ok = false;
        r.tag = tag;
        r.detail = detail == null ? "" : detail;
        return r;
    }

    // ---- CLI: the owner-facing entry point (restore → validate → decide) ----
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("usage: KsValidator <keystore> <password> <alias> [expectedSha256Fp|-]");
            System.out.println("  pass the fingerprint published for the release identity ('-' skips the identity check; the drill never skips it)");
            System.exit(2);
        }
        String expect = args.length >= 4 ? args[3] : "-";
        Report r = validate(args[0], args[1], args[2], expect);
        if (r.ok) {
            System.out.println("KSDRILL_OK type=" + r.type + " alg=" + r.alg + " bits=" + r.bits
                    + " days_left=" + r.daysLeft + " fp=" + r.fp);
        } else {
            System.out.println("KSDRILL_FAIL " + r.tag + " (" + r.detail + ")");
            System.exit(2);
        }
    }
}
