package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Week 31 (phase 6 W2): SignedApk unit tests — the JDK-only surface of the
 * signing identity (the full sign/verify round-trip lives in the GATE, which
 * runs apksig + aapt2; these tests run everywhere tests/run.sh runs).
 * The anchor is asserted literally: sha256(cert DER) of the drill fixture ==
 * the published fingerprint («المرجع = المنشور»), one derivation
 * (KsValidator.sha256Hex) shared by the drill, the pins and the artifact.
 */
public class SignedApkTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static final String FP = "a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587";

    public static void run() throws Exception {
        System.out.println("== SignedApk: identity (one derivation, published anchor) ==");
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(KsValidator.DRILL_KS)) {
            ks.load(in, KsValidator.DRILL_PASS.toCharArray());
        }
        byte[] der = ks.getCertificateChain(KsValidator.DRILL_ALIAS)[0].getEncoded();
        check("anchor: fixture cert sha256 == the published literal (64 hex)",
                KsValidator.sha256Hex(der).equals(FP));
        check("fp16 is the 16-char prefix of the anchor",
                KsValidator.sha256Hex(der).substring(0, 16).equals(SignedApkGate.EXPECTED_FP16));

        System.out.println("== SignedApk: loadKey acceptance + every tag bites ==");
        SignedApk.Result ok = SignedApk.loadKey(KsValidator.DRILL_KS, KsValidator.DRILL_PASS,
                KsValidator.DRILL_ALIAS, FP);
        check("accept: correct pass+alias+published fp", ok.ok && FP.startsWith(ok.fp16));
        check("reject: wrong password -> unreadable_ks (fail-closed)",
                tagIs(KsValidator.DRILL_KS, "wrong-pass", KsValidator.DRILL_ALIAS, "unreadable_ks"));
        check("reject: JKS container -> ks_type (magic bytes, not JDK opinion)",
                tagIs("tests/ks/drill-jks.p12", KsValidator.DRILL_PASS, KsValidator.DRILL_ALIAS, "ks_type"));
        check("reject: missing artifact -> ks_missing",
                tagIs("tests/ks/does-not-exist.p12", KsValidator.DRILL_PASS, KsValidator.DRILL_ALIAS, "ks_missing"));
        check("reject: wrong alias -> alias_missing",
                tagIs(KsValidator.DRILL_KS, KsValidator.DRILL_PASS, "no-such-alias", "alias_missing"));
        check("reject: rogue key -> fp_mismatch (valid keystore, foreign identity)",
                tagIs("tests/ks/drill-rogue.p12", KsValidator.DRILL_PASS, KsValidator.DRILL_ALIAS, "fp_mismatch"));
        check("containerMagic: drill-release is PKCS12 (DER 0x30)",
                "PKCS12".equals(SignedApk.containerMagic(KsValidator.DRILL_KS)));
        check("containerMagic: drill-jks is JKS",
                "JKS".equals(SignedApk.containerMagic("tests/ks/drill-jks.p12")));

        System.out.println("== SignedApk: deterministic statement ==");
        String s1 = SignedApkGate.buildStatement("72", "2.5", "a80e71b9a5d15546",
                "04765b0d876c5348846bc48004249b28be8eb811d7df0a0d03f831ad8ec9ef90", 505636);
        String s2 = SignedApkGate.buildStatement("72", "2.5", "a80e71b9a5d15546",
                "04765b0d876c5348846bc48004249b28be8eb811d7df0a0d03f831ad8ec9ef90", 505636);
        check("statement is deterministic (same inputs -> same bytes)", s1.equals(s2));
        check("statement carries the schema + fields",
                s1.contains("\"schema\": \"neokey-signedapk-1\"")
                        && s1.contains("\"v1Scheme\": true") && s1.contains("\"v2Scheme\": true")
                        && s1.contains("\"dexCeiling\": 610000"));

        System.out.println("== SignedApk: raw zip utilities ==");
        File z = File.createTempFile("nkzip", ".zip");
        z.deleteOnExit();
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(z))) {
            ZipEntry stored = new ZipEntry("resources.arsc");
            stored.setMethod(ZipEntry.STORED);
            byte[] payload = "123456".getBytes(StandardCharsets.UTF_8);
            stored.setSize(payload.length);
            stored.setCrc(new java.util.zip.CRC32() {{ update(payload); }}.getValue());
            zo.putNextEntry(stored); zo.write(payload); zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/a.txt"));
            zo.write("hello".getBytes(StandardCharsets.UTF_8)); zo.closeEntry();
        }
        Map<String, long[]> cd = SignedApk.readCentralDirectory(z);
        check("central directory: names + methods parsed",
                cd.size() == 2 && cd.get("resources.arsc")[0] == 0 && cd.get("assets/a.txt")[0] == 8);
        check("dataOffset lands inside the file past the local header",
                SignedApk.dataOffset(z, cd.get("assets/a.txt")[1]) > 0
                        && SignedApk.dataOffset(z, cd.get("assets/a.txt")[1]) < z.length());
        check("arscCheck refuses a deflated/unaligned synthetic layout only when broken",
                SignedApkGate.arscCheck(z) == null || SignedApkGate.arscCheck(z).startsWith("resources.arsc"));
    }

    private static boolean tagIs(String ks, String pass, String alias, String wantTag) {
        SignedApk.Result r = SignedApk.loadKey(ks, pass, alias, FP);
        return !r.ok && wantTag.equals(r.tag);
    }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
