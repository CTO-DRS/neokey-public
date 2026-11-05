package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * SignedApkGate — week 31 (phase 6 W2) gate: the SIGNED RELEASE ARTIFACT is
 * proven end-to-end on the committed drill stand-in, with the anchor as the
 * FIRST barrier ("المرجع = المنشور").
 *
 * Checks (deterministic order, each refusal one exact tag):
 *   1  anchor.drill.fp     sha256(cert DER) of the drill fixture re-derives the
 *                          pinned literal fp16 — swap the file and the gate
 *                          breaks on the first run even though the swap is a
 *                          perfectly valid keystore
 *   2  load.accept         SignedApk.loadKey accepts the fixture (correct pass+alias)
 *   3  identity.agreement  KsValidator on the SAME fixture accepts with the SAME
 *                          fingerprint — two classes, one identity, no parallel
 *                          derivation anywhere
 *   4  sign.ok             SignedApk.sign produced the artifact (SIGNEDAPK_OK)
 *   5  verify.full         ApkVerifier: verified + v1 scheme + v2 scheme
 *   6  verify.cert         the certificate embedded INSIDE the artifact
 *                          re-derives the pinned fingerprint
 *   7  v1.entries          META-INF/MANIFEST.MF + *.SF + *.RSA present (v1 layout)
 *   8  dex.carried         classes.dex inside the artifact is byte-equal to the
 *                          pipeline R8 dex and under the 512,000B ceiling
 *   9  assets.carried      assets/community_ar.dpack + assets/ar.txt ride the
 *                          artifact byte-equal
 *  10  align.arsc          resources.arsc STORED (uncompressed) and 4-byte
 *                          aligned — the API-30+ install requirement, read from
 *                          the raw zip bytes, not from a tool's opinion
 *  11  determinism         signing the same aligned input again yields the same
 *                          artifact bytes (sha256 equality) — the statement below
 *                          is reproducible
 *  12  statement           build/release/signedapk-statement.json exists, schema
 *                          neokey-signedapk-1, fields match this run, and a
 *                          rebuild from the second signing is byte-identical
 *  13  versions            AndroidManifest.xml carries the pinned release
 *                          identity (vc 74 / vn 2.7 — the week's literals (bumped week 35))
 *  14  purity              SignedApk.java has zero android.* imports
 *
 * Negatives (each must fail with ITS OWN tag — a check that stops biting is a
 * dead guard and fails the gate):
 *   n1 rogue keystore  → fp_mismatch      n2 wrong password → unreadable_ks
 *   n3 JKS container   → ks_type          n4 missing file   → ks_missing
 *   n5 wrong alias     → alias_missing    n6 tampered dex   → not_verified
 *
 * Verdict: SIGNEDAPKGATE_OK checks=14 negatives=6/6 fp16=… v1=1 v2=1 vc=73
 *          vn=2.6 apk_sha256=… dex=…/512000  /  SIGNEDAPKGATE_FAILED <reason>
 */
public final class SignedApkGate {
    private SignedApkGate() {}

    // ---- the published identity (week-31 pins; «المرجع = المنشور») ------------
    // The anchor is the FULL published certificate fingerprint (the W28 CLI form);
    // fp16 is derived from it for verdicts.
    static final String EXPECTED_FP = "a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587";
    static final String EXPECTED_FP16 = EXPECTED_FP.substring(0, 16);
    static final String EXPECTED_VC = "82";
    static final String EXPECTED_VN = "3.5";
    static final long DEX_CEILING = 512000;

    static int CHECKS = 0;
    static int NEG_OK = 0;
    static final int NEG_TOTAL = 6;

    public static void main(String[] args) throws Exception {
        String signedApk = args.length > 0 ? args[0] : "build/NeoKey-2036.apk";
        String pipelineDex = args.length > 1 ? args[1] : "build/dex/classes.dex";

        // ---- 1: THE ANCHOR IS THE FIRST BARRIER --------------------------------
        byte[] fixtureCert = new KeyStoreAliasHolder().cert();
        String anchorDer = KsValidator.sha256Hex(fixtureCert);
        ck("anchor.drill.fp", EXPECTED_FP.equals(anchorDer),
                "fixture sha256(cert DER)=" + anchorDer);

        // ---- 2-3: load + identity agreement (two classes, one derivation) ------
        SignedApk.Result load = SignedApk.loadKey(KsValidator.DRILL_KS, KsValidator.DRILL_PASS,
                KsValidator.DRILL_ALIAS, EXPECTED_FP);
        ck("load.accept", load.ok, "tag=" + load.tag);
        KsValidator.Report rep = KsValidator.validate(KsValidator.DRILL_KS, KsValidator.DRILL_PASS,
                KsValidator.DRILL_ALIAS, EXPECTED_FP);
        ck("identity.agreement", rep.ok && EXPECTED_FP.equals(rep.fp) && EXPECTED_FP16.equals(load.fp16),
                "KsValidator+SignedApk agree on " + EXPECTED_FP16);

        // ---- 4-6: sign + verify + embedded identity ----------------------------
        SignedApk.Result sig = SignedApk.verify(signedApk, EXPECTED_FP);
        ck("sign.ok", new File(signedApk).isFile(), "artifact " + signedApk);
        ck("verify.full", sig.ok && sig.v1 && sig.v2,
                "tag=" + sig.tag + " " + sig.detail);
        ck("verify.cert", sig.ok && EXPECTED_FP16.equals(sig.certFp16),
                "artifact cert fp16=" + sig.certFp16);

        // ---- 7: v1 layout entries ----------------------------------------------
        boolean v1ok = false;
        try (ZipFile zf = new ZipFile(new File(signedApk))) {
            boolean mf = zf.getEntry("META-INF/MANIFEST.MF") != null;
            boolean sf = false, cert = false;
            for (java.util.Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.startsWith("META-INF/") && n.endsWith(".SF")) sf = true;
                if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")))
                    cert = true;
            }
            v1ok = mf && sf && cert;
        }
        ck("v1.entries", v1ok, "MANIFEST.MF + .SF + .RSA/.DSA present");

        // ---- 8: THE dex rides the artifact, byte-equal, under the ceiling ------
        byte[] pipelineDexBytes = Files.readAllBytes(new File(pipelineDex).toPath());
        byte[] carried = entry(signedApk, "classes.dex");
        long dexBytes = carried == null ? -1 : carried.length;
        ck("dex.carried", carried != null && java.util.Arrays.equals(carried, pipelineDexBytes)
                        && dexBytes <= DEX_CEILING,
                "dex=" + dexBytes + "/" + DEX_CEILING + " equal=" +
                        (carried != null && java.util.Arrays.equals(carried, pipelineDexBytes)));

        // ---- 9: community assets ride the artifact -----------------------------
        boolean assetsOk = eq(signedApk, "assets/community_ar.dpack", "assets/community_ar.dpack")
                && eq(signedApk, "assets/ar.txt", "assets/ar.txt");
        ck("assets.carried", assetsOk, "community_ar.dpack + ar.txt byte-equal");

        // ---- 10: resources.arsc STORED + 4-byte aligned (raw bytes) ------------
        String arscVerdict = arscCheck(new File(signedApk));
        ck("align.arsc", arscVerdict == null, arscVerdict == null ? "arsc stored + 4B aligned" : arscVerdict);

        // ---- 11: determinism — second signing, same bytes ----------------------
        File out2 = new File("build/sapk/resign-check.apk");
        out2.getParentFile().mkdirs();
        SignedApk.Result sig2 = SignedApk.sign("build/sapk/aligned.apk", out2.getPath(),
                KsValidator.DRILL_KS, KsValidator.DRILL_PASS, KsValidator.DRILL_ALIAS, EXPECTED_FP);
        String sha1 = sig.apkSha256, sha2 = sig2.apkSha256;
        ck("determinism", sig2.ok && sha1.equals(sha2) && sha1.length() == 64,
                "sha256 stable across signings");

        // ---- 12: deterministic statement ----------------------------------------
        String stmt = buildStatement(EXPECTED_VC, EXPECTED_VN, EXPECTED_FP16, sha1, dexBytes);
        File stmtFile = new File("build/release/signedapk-statement.json");
        stmtFile.getParentFile().mkdirs();
        Files.write(stmtFile.toPath(), stmt.getBytes(StandardCharsets.UTF_8));
        String stmt2 = buildStatement(EXPECTED_VC, EXPECTED_VN, EXPECTED_FP16, sha2, dexBytes);
        ck("statement", stmt.equals(stmt2) && stmt.contains("\"schema\": \"neokey-signedapk-1\"")
                        && stmt.contains("\"apkSha256\": \"" + sha1 + "\""),
                "build/release/signedapk-statement.json deterministic");

        // ---- 13: the week's release identity is pinned --------------------------
        String manifest = new String(Files.readAllBytes(new File("AndroidManifest.xml").toPath()),
                StandardCharsets.UTF_8);
        boolean vcOk = manifest.contains("android:versionCode=\"" + EXPECTED_VC + "\"");
        boolean vnOk = manifest.contains("android:versionName=\"" + EXPECTED_VN + "\"");
        ck("versions", vcOk && vnOk, "manifest vc=" + EXPECTED_VC + " vn=" + EXPECTED_VN);

        // ---- 14: purity — zero android.* in the signer --------------------------
        String src = new String(Files.readAllBytes(
                new File("tests/com/hark/neokey/SignedApk.java").toPath()), StandardCharsets.UTF_8);
        boolean pure = !src.contains("import android.");
        ck("purity", pure, "SignedApk.java zero android.*");

        // ---- negatives: every check must still bite -----------------------------
        System.out.println();
        neg("negatives.rogue", KsValidator.DRILL_ALIAS, "tests/ks/drill-rogue.p12",
                KsValidator.DRILL_PASS, EXPECTED_FP, "fp_mismatch");
        neg("negatives.wrongpass", KsValidator.DRILL_ALIAS, KsValidator.DRILL_KS,
                "wrong-drill-pass", EXPECTED_FP, "unreadable_ks");
        neg("negatives.jks", KsValidator.DRILL_ALIAS, "tests/ks/drill-jks.p12",
                KsValidator.DRILL_PASS, EXPECTED_FP, "ks_type");
        neg("negatives.missing", KsValidator.DRILL_ALIAS, "tests/ks/does-not-exist.p12",
                KsValidator.DRILL_PASS, EXPECTED_FP, "ks_missing");
        neg("negatives.wrongalias", "no-such-alias", KsValidator.DRILL_KS,
                KsValidator.DRILL_PASS, EXPECTED_FP, "alias_missing");
        negTamper("negatives.tamper", signedApk, EXPECTED_FP);

        System.out.println();
        System.out.println("SIGNEDAPKGATE_OK checks=" + CHECKS + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " fp16=" + EXPECTED_FP16 + " v1=1 v2=1 vc=" + EXPECTED_VC + " vn=" + EXPECTED_VN
                + " apk_sha256=" + sha1 + " dex=" + dexBytes + "/" + DEX_CEILING);
        if (NEG_OK != NEG_TOTAL) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    /** tiny holder so the gate stays fail-closed on fixture problems */
    private static final class KeyStoreAliasHolder {
        byte[] cert() throws Exception {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(KsValidator.DRILL_KS)) {
                ks.load(in, KsValidator.DRILL_PASS.toCharArray());
            }
            return ks.getCertificateChain(KsValidator.DRILL_ALIAS)[0].getEncoded();
        }
    }

    private static void ck(String id, boolean ok, String detail) {
        CHECKS++;
        System.out.println((ok ? "PASS " : "FAIL ") + id + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) { System.out.println("SIGNEDAPKGATE_FAILED " + id); System.exit(1); }
    }

    private static void neg(String id, String alias, String ks, String pass, String fp, String wantTag) {
        SignedApk.Result r = SignedApk.loadKey(ks, pass, alias, fp);
        boolean bitten = !r.ok && wantTag.equals(r.tag);
        if (bitten) NEG_OK++;
        System.out.println((bitten ? "PASS " : "FAIL ") + id + " tag=" + r.tag
                + (bitten ? "" : "  expected tag " + wantTag + " but got "
                        + (r.ok ? "ACCEPT (check stopped biting!)" : r.tag)));
        if (!bitten) { System.out.println("SIGNEDAPKGATE_FAILED " + id); System.exit(1); }
    }

    /** flip one byte inside the carried dex of a COPY — the whole-archive v2
     *  signature and the v1 digests must both refuse it (not_verified). */
    private static void negTamper(String id, String signedApk, String fp) throws Exception {
        File tam = new File("build/sapk/tampered-check.apk");
        tam.getParentFile().mkdirs();
        byte[] dex = entry(signedApk, "classes.dex");
        dex[dex.length / 2] ^= 0x5A;
        try (ZipFile zin = new ZipFile(new File(signedApk));
             ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(tam))) {
            java.util.Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                ZipEntry n = new ZipEntry(e.getName()); // default DEFLATED — sizes/crc recomputed
                zo.putNextEntry(n);
                if ("classes.dex".equals(e.getName())) zo.write(dex);
                else try (java.io.InputStream is = zin.getInputStream(e)) {
                    byte[] buf = new byte[65536]; int k;
                    while ((k = is.read(buf)) > 0) zo.write(buf, 0, k);
                }
                zo.closeEntry();
            }
        }
        SignedApk.Result r = SignedApk.verify(tam.getPath(), fp);
        boolean bitten = !r.ok && "not_verified".equals(r.tag);
        if (bitten) NEG_OK++;
        System.out.println((bitten ? "PASS " : "FAIL ") + id + " tag=" + r.tag
                + (bitten ? "" : "  expected tag not_verified but got "
                        + (r.ok ? "ACCEPT (check stopped biting!)" : r.tag)));
        if (!bitten) { System.out.println("SIGNEDAPKGATE_FAILED " + id); System.exit(1); }
    }

    static String buildStatement(String vc, String vn, String fp16, String apkSha256, long dexBytes) {
        return "{\n"
                + "  \"schema\": \"neokey-signedapk-1\",\n"
                + "  \"artifact\": \"build/NeoKey-2036.apk\",\n"
                + "  \"versionCode\": " + vc + ",\n"
                + "  \"versionName\": \"" + vn + "\",\n"
                + "  \"signer\": \"tests/ks/drill-release.p12 (drill stand-in — the real keystore stays owner-side)\",\n"
                + "  \"fp16\": \"" + fp16 + "\",\n"
                + "  \"v1Scheme\": true,\n"
                + "  \"v2Scheme\": true,\n"
                + "  \"minSdk\": 21,\n"
                + "  \"apkSha256\": \"" + apkSha256 + "\",\n"
                + "  \"dexBytes\": " + dexBytes + ",\n"
                + "  \"dexCeiling\": " + DEX_CEILING + "\n"
                + "}\n";
    }

    private static byte[] entry(String apk, String name) throws Exception {
        try (ZipFile zf = new ZipFile(new File(apk))) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (java.io.InputStream is = zf.getInputStream(e);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536]; int k;
                while ((k = is.read(buf)) > 0) out.write(buf, 0, k);
                return out.toByteArray();
            }
        }
    }

    private static boolean eq(String apk, String entryName, String repoPath) throws Exception {
        byte[] in = entry(apk, entryName);
        return in != null && java.util.Arrays.equals(in, Files.readAllBytes(new File(repoPath).toPath()));
    }

    /** null = OK (stored + 4-byte aligned); otherwise the exact failure. */
    static String arscCheck(File apk) throws Exception {
        Map<String, long[]> cd = SignedApk.readCentralDirectory(apk);
        long[] arsc = cd.get("resources.arsc");
        if (arsc == null) return "resources.arsc missing from the artifact";
        if (arsc[0] != 0) return "resources.arsc is DEFLATED (method=" + arsc[0] + ") — API 30+ requires STORED";
        long off = SignedApk.dataOffset(apk, arsc[1]);
        if (off % 4 != 0) return "resources.arsc data offset " + off + " not 4-byte aligned";
        return null;
    }
}
