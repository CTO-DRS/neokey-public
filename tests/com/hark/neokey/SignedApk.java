package com.hark.neokey;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * SignedApk — week 31 (phase 6 W2): the release ARTIFACT half of the
 * documented "restore signing secrets from the encrypted backup before any
 * signed build" pending. Week 28 automated the DECISION (is this keystore the
 * release identity? — KsValidator); week 31 automates the ARTIFACT (build the
 * full APK and sign it v1+v2 with that identity, then verify the result).
 *
 * The owner's two commands (docs/PHASE6-W2-REPORT.md + deploy/README.md):
 *   1. ci/build_signedapk.sh                       — assemble + align (no secrets)
 *   2. java -cp build/signedapk-gate:tools/apksig.jar com.hark.neokey.SignedApk \
 *        sign build/sapk/aligned.apk build/NeoKey-2036.apk <ks> <pass> <alias> <published-fp16>
 * The owner restores the REAL keystore (W28 procedure) and runs the same two
 * commands; in CI the same code runs on the committed drill stand-in
 * (tests/ks/drill-release.p12 — documented drill password, zero production
 * material).
 *
 * Identity is decided by the ONE derivation: sha256 over the certificate DER
 * (KsValidator.sha256Hex — the same derivation as the W28 drill fingerprint,
 * the TLS arena pins and the DictSign statements). "المرجع = المنشور":
 * the signer refuses to produce an artifact whose certificate does not match
 * the PUBLISHED fingerprint — internal validity is never enough.
 *
 * Fail-closed everywhere; every rejection has ONE exact tag, in deterministic
 * order:
 *   ks_missing / ks_type (container magic bytes: DER 0x30 vs JKS feedfeed /
 *   cececece — the W28 lesson) / unreadable_ks (catch-all — any unexpected
 *   exception is a refusal, never a pass) / alias_missing / no_private_key /
 *   no_cert_chain / not_x509 / fp_mismatch
 * verification tags: not_verified / v1_missing / v2_missing / cert_mismatch
 *
 * Pure JDK + tools/apksig.jar (dl.google.com — see docs/R8.md); zero android.*.
 * CLI:  sign   <in.apk> <out.apk> <keystore> <password> <alias> <fp16|->
 *       verify <apk> <fp16|->
 *       exit 0 + "SIGNEDAPK_OK v1=1 v2=1 fp16=… apk_sha256=…" on success,
 *       exit 2 + "SIGNEDAPK_FAIL <tag>" otherwise.
 */
public final class SignedApk {
    private SignedApk() {}

    /** v1 (JAR) + v2 (APK Signature Scheme v2) — the release signing scheme. */
    public static final String SCHEMA = "neokey-signedapk-1";

    // ------------------------------------------------------------------ result
    public static final class Result {
        public boolean ok;
        public String tag = "";
        public String detail = "";
        public String fp16 = "";        // signer certificate fingerprint (16 hex)
        public String certFp16 = "";    // certificate fingerprint as verified INSIDE the artifact
        public boolean v1, v2;
        public String apkSha256 = "";
    }

    public static String fp16Of(Certificate c) throws Exception {
        return KsValidator.sha256Hex(c.getEncoded()).substring(0, 16);
    }

    // ------------------------------------------------------------- keystore load
    /** Load the signing key with the W28 discipline (magic-byte container check,
     *  fail-closed, one tag per rejection). expectedFp16 "-" disables the
     *  identity comparison (verification-only modes). */
    public static Result loadKey(String ksPath, String pass, String alias, String expectedFp16) {
        Result r = new Result();
        try {
            File f = new File(ksPath);
            if (!f.isFile()) return fail(r, "ks_missing", ksPath);
            String magic = containerMagic(ksPath);
            if (!"PKCS12".equals(magic))
                return fail(r, "ks_type", "container magic=" + magic + " (want DER 0x30 sequence)");
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(f)) {
                ks.load(in, pass.toCharArray());
            } catch (Exception e) {
                return fail(r, "unreadable_ks", String.valueOf(e.getMessage()));
            }
            KeyStore.Entry e;
            try {
                e = ks.getEntry(alias, new KeyStore.PasswordProtection(pass.toCharArray()));
            } catch (Exception ex) {
                return fail(r, "unreadable_ks", String.valueOf(ex.getMessage()));
            }
            if (e == null || !(e instanceof KeyStore.PrivateKeyEntry))
                return fail(r, "alias_missing", alias);
            KeyStore.PrivateKeyEntry pke = (KeyStore.PrivateKeyEntry) e;
            if (pke.getPrivateKey() == null) return fail(r, "no_private_key", alias);
            Certificate[] chain = pke.getCertificateChain();
            if (chain == null || chain.length == 0) return fail(r, "no_cert_chain", alias);
            if (!(chain[0] instanceof X509Certificate)) return fail(r, "not_x509", chain[0].getType());
            String fullFp = KsValidator.sha256Hex(chain[0].getEncoded());
            r.fp16 = fullFp.substring(0, 16);
            if (expectedFp16 != null && !"-".equals(expectedFp16)
                    && !KsValidator.fpEqual(fullFp, expectedFp16))
                return fail(r, "fp_mismatch", fullFp + " != " + expectedFp16);
            r.ok = true;
            return r;
        } catch (Exception ex) {
            return fail(r, "unreadable_ks", String.valueOf(ex.getMessage()));
        }
    }

    /** Container type from the file's OWN bytes (not the JDK's compat opinion):
     *  JKS 0xFEEDFEED / JCEKS 0xCECECECE / anything starting with a DER
     *  0x30 SEQUENCE is accepted as PKCS12-family. */
    public static String containerMagic(String path) throws Exception {
        byte[] b = new byte[4];
        int n;
        try (FileInputStream in = new FileInputStream(path)) { n = in.read(b); }
        if (n < 4) return "short";
        if ((b[0] & 0xFE) == 0xFE && b[1] == (byte) 0xED && b[2] == (byte) 0xFE && b[3] == (byte) 0xED)
            return "JKS"; // FEEDFEED
        if (b[0] == (byte) 0xCE && b[1] == (byte) 0xCE && b[2] == (byte) 0xCE && b[3] == (byte) 0xCE)
            return "JCEKS";
        if (b[0] == 0x30) return "PKCS12"; // DER SEQUENCE
        return String.format("%02x%02x%02x%02x", b[0], b[1], b[2], b[3]);
    }

    // -------------------------------------------------------------------- sign
    /** Sign in.apk → out.apk with v1+v2, then verify the result with the same
     *  rules as verify() — a signer that does not verify its own output is not
     *  a signer. Identity: the certificate must match expectedFp16. */
    public static Result sign(String inApk, String outApk, String ksPath, String pass,
                              String alias, String expectedFp16) {
        Result r = new Result();
        Result k = loadKey(ksPath, pass, alias, expectedFp16);
        if (!k.ok) return k;
        try {
            File in = new File(inApk);
            if (!in.isFile()) return fail(r, "not_verified", "input apk missing: " + inApk);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream is = new FileInputStream(ksPath)) { ks.load(is, pass.toCharArray()); }
            KeyStore.PrivateKeyEntry pke = (KeyStore.PrivateKeyEntry)
                    ks.getEntry(alias, new KeyStore.PasswordProtection(pass.toCharArray()));
            PrivateKey pk = pke.getPrivateKey();
            List<java.security.cert.X509Certificate> certs = new ArrayList<>();
            for (Certificate c : pke.getCertificateChain())
                certs.add((java.security.cert.X509Certificate) c);
            ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(alias, pk, certs).build();
            ApkSigner signer = new ApkSigner.Builder(java.util.Collections.singletonList(sc))
                    .setInputApk(in)
                    .setOutputApk(new File(outApk))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .build();
            signer.sign();
            return verify(outApk, expectedFp16);
        } catch (Exception ex) {
            return fail(r, "sign_failed", String.valueOf(ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ verify
    /** Verify a signed APK: full integrity (v1+v2 schemes) and identity
     *  (the embedded signer certificate re-derives the published fingerprint). */
    public static Result verify(String apkPath, String expectedFp16) {
        Result r = new Result();
        try {
            File apk = new File(apkPath);
            if (!apk.isFile()) return fail(r, "not_verified", "apk missing: " + apkPath);
            ApkVerifier.Result res = new ApkVerifier.Builder(apk)
                    .setMinCheckedPlatformVersion(21)
                    .build()
                    .verify();
            if (!res.isVerified()) return fail(r, "not_verified", firstErr(res));
            if (!res.isVerifiedUsingV1Scheme()) return fail(r, "v1_missing", "v1 scheme not verified");
            if (!res.isVerifiedUsingV2Scheme()) return fail(r, "v2_missing", "v2 scheme not verified");
            List<X509Certificate> certs = res.getSignerCertificates();
            if (certs == null || certs.isEmpty()) return fail(r, "cert_mismatch", "no signer certificates");
            String fullFp = KsValidator.sha256Hex(certs.get(0).getEncoded());
            r.certFp16 = fullFp.substring(0, 16);
            r.fp16 = r.certFp16;
            if (expectedFp16 != null && !"-".equals(expectedFp16)
                    && !KsValidator.fpEqual(fullFp, expectedFp16))
                return fail(r, "cert_mismatch", fullFp + " != " + expectedFp16);
            r.v1 = true; r.v2 = true;
            r.apkSha256 = KsValidator.sha256Hex(readAll(apk));
            r.ok = true;
            return r;
        } catch (Exception ex) {
            return fail(r, "not_verified", String.valueOf(ex.getMessage()));
        }
    }

    private static String firstErr(ApkVerifier.Result res) {
        List<ApkVerifier.IssueWithParams> errs = res.getErrors();
        if (errs != null && !errs.isEmpty()) return "apksig: " + errs.get(0).toString();
        return "apksig: not verified";
    }

    private static Result fail(Result r, String tag, String detail) {
        r.ok = false; r.tag = tag; r.detail = detail; return r;
    }

    // ------------------------------------------------------- raw zip utilities
    /** name → [method, localHeaderOffset, size] for every central-directory entry. */
    public static Map<String, long[]> readCentralDirectory(File f) throws IOException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int scan = (int) Math.min(len, 22 + 65535L);
            byte[] buf = new byte[scan];
            raf.seek(len - scan);
            raf.readFully(buf);
            long eocd = -1;
            for (int i = buf.length - 22; i >= 0; i--)
                if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) { eocd = i; break; }
            if (eocd < 0) throw new IOException("EOCD not found");
            int count = s2(buf, (int) (eocd + 10));
            long cd = u4(buf, (int) (eocd + 16));
            raf.seek(cd);
            for (int i = 0; i < count; i++) {
                byte[] e = new byte[46];
                raf.readFully(e);
                if (u4(e, 0) != 0x02014b50L) throw new IOException("bad central entry " + i);
                int method = s2(e, 10);
                int nl = s2(e, 28), el = s2(e, 30), cl = s2(e, 32);
                long off = u4(e, 42);
                byte[] nb = new byte[nl];
                raf.readFully(nb);
                raf.skipBytes(el + cl);
                out.put(new String(nb, "UTF-8"), new long[]{method, off, u4(e, 24)});
            }
        }
        return out;
    }

    /** Absolute data offset of an entry (local header parsed at its own bytes). */
    public static long dataOffset(File f, long localHeaderOffset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(localHeaderOffset);
            byte[] h = new byte[30];
            raf.readFully(h);
            if (u4(h, 0) != 0x04034b50L) throw new IOException("bad local header @" + localHeaderOffset);
            int nl = s2(h, 26), el = s2(h, 28);
            return localHeaderOffset + 30 + nl + el;
        }
    }

    // -------------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "verify".equals(args[0])) {
            Result r = verify(args[1], args.length >= 3 ? args[2] : "-");
            System.out.println(line("SIGNEDAPK", r));
            System.exit(r.ok ? 0 : 2);
        }
        if (args.length >= 2 && "zipinfo".equals(args[0])) {
            Map<String, long[]> cd = readCentralDirectory(new File(args[1]));
            for (Map.Entry<String, long[]> e : cd.entrySet())
                System.out.println(e.getKey() + " method=" + e.getValue()[0]
                        + " off=" + e.getValue()[1] + " size=" + e.getValue()[2]);
            System.exit(0);
        }
        if (args.length == 7 && "sign".equals(args[0])) {
            Result r = sign(args[1], args[2], args[3], args[4], args[5], args[6]);
            System.out.println(line("SIGNEDAPK", r));
            System.exit(r.ok ? 0 : 2);
        }
        System.out.println("usage: SignedApk sign <in> <out> <ks> <pass> <alias> <fp16|-> "
                + "| verify <apk> <fp16|-> | zipinfo <apk>");
        System.exit(2);
    }

    static String line(String prefix, Result r) {
        if (!r.ok) return prefix + "_FAIL " + r.tag + (r.detail.isEmpty() ? "" : " (" + r.detail + ")");
        return prefix + "_OK v1=" + (r.v1 ? 1 : 0) + " v2=" + (r.v2 ? 1 : 0)
                + " fp16=" + r.fp16 + " apk_sha256=" + r.apkSha256;
    }

    static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static int s2(byte[] b, int off) { return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8); }
    static long u4(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
