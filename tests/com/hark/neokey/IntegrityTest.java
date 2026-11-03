package com.hark.neokey;

/**
 * Roadmap Phase 0 / item C: runtime integrity shield — pure decision logic
 * (Integrity.java has zero android.* imports, so the whole shield policy is
 * tested here on the plain JVM, exactly as R8 will ship it).
 * Privacy contract is enforced by tests: the report carries NO device identifiers.
 */
public class IntegrityTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    private static final String OFFICIAL =
            "00:1DCF:BF4C:EB0F:9316:BEE8:5C27:01A7:F563:0860:AABA:4483:9C91:F760:C5D8:55B9:ED";
    private static final String FOREIGN =
            "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99";

    public static void run() {
        System.out.println("== Integrity: pin fidelity ==");
        // The embedded pin must be byte-identical to the vault record (docs/SECURITY.md) —
        // a drift here would brand every legitimate install as repackaged.
        check("embedded fingerprint matches the vault value", Integrity.OFFICIAL_UPLOAD_FP.equals(
                "00:1D:CF:BF:4C:EB:0F:93:16:BE:E8:5C:27:01:A7:F5:63:08:60:AA:BA:44:83:9C:91:F7:60:C5:D8:55:B9:ED"));
        check("official pin list is non-empty and normalized",
                Integrity.OFFICIAL_FINGERPRINTS.length >= 1
                && Integrity.normalize(Integrity.OFFICIAL_FINGERPRINTS[0]).length() == 64);
        check("normalize strips colons and case", Integrity.normalize(OFFICIAL).equals(Integrity.normalize(
                OFFICIAL.replace(':', ' ').replace("1dcf", "1DCF"))));
        StringBuilder ab63 = new StringBuilder();
        for (int i = 0; i < 63; i++) ab63.append("ab");
        check("normalize rejects short/garbage input",
                Integrity.normalize(null).isEmpty() && Integrity.normalize("").isEmpty()
                && Integrity.normalize("zz").isEmpty() && Integrity.normalize(ab63.toString()).isEmpty());
        check("sha256Hex known vector (abc)", "ba7816bf8f01cfea414140de5dae2223"
                .concat("b00361a396177a9cb410ff61f20015ad").equals(Integrity.sha256Hex("abc".getBytes())));
        check("sha256Hex empty input -> empty output", Integrity.sha256Hex(null).isEmpty()
                && Integrity.sha256Hex(new byte[0]).isEmpty());

        System.out.println("== Integrity:verdict matrix ==");
        check("official signature -> OK",
                Integrity.verdict(new String[]{Integrity.normalize(OFFICIAL)}, false)
                        == Integrity.State.OK);
        check("official signature with mixed formatting -> OK",
                Integrity.verdict(new String[]{OFFICIAL.toUpperCase()}, false) == Integrity.State.OK);
        check("foreign release signature -> MISMATCH (repackaged-apk pattern)",
                Integrity.verdict(new String[]{Integrity.normalize(FOREIGN)}, false)
                        == Integrity.State.MISMATCH);
        check("no signatures -> UNKNOWN (tooling context is not an accusation)",
                Integrity.verdict(new String[0], false) == Integrity.State.UNKNOWN
                && Integrity.verdict(null, false) == Integrity.State.UNKNOWN);
        check("debuggable build wins first -> SANDBOX even with a foreign signature",
                Integrity.verdict(new String[]{Integrity.normalize(FOREIGN)}, true)
                        == Integrity.State.SANDBOX);
        check("garbage entries among signatures do not crash the verdict",
                Integrity.verdict(new String[]{"", "not-hex", Integrity.normalize(OFFICIAL)}, false)
                        == Integrity.State.OK);
        check("garbage-only signatures -> MISMATCH (a real signed build with unreadable certs)",
                Integrity.verdict(new String[]{"", "not-hex"}, false) == Integrity.State.MISMATCH);
        check("verdict is deterministic across calls",
                Integrity.verdict(new String[]{Integrity.normalize(FOREIGN)}, false)
                        == Integrity.verdict(new String[]{Integrity.normalize(FOREIGN)}, false));

        System.out.println("== Integrity:feature gate (revenue surface only) ==");
        check("OK / SANDBOX / UNKNOWN keep features", Integrity.allowFeature(Integrity.State.OK)
                && Integrity.allowFeature(Integrity.State.SANDBOX)
                && Integrity.allowFeature(Integrity.State.UNKNOWN));
        check("only MISMATCH restricts the paid surface", !Integrity.allowFeature(Integrity.State.MISMATCH));

        System.out.println("== Integrity:emulator hint (record-only) ==");
        check("SDK emulator fingerprints hint true", Integrity.emulatorHint(
                "google/sdk_gphone64-x86_64/android-x86_64", "sdk_gphone64_x86_64", "ranchu"));
        check("genymotion hints true", Integrity.emulatorHint(
                "generic/vbox86p/vbox86p", "Genymotion", "vbox86"));
        check("a real device does not hint", !Integrity.emulatorHint(
                "samsung/a52sins/qcom", "SM-A528B", "qcom"));

        System.out.println("== Integrity:anonymous report (published privacy contract) ==");
        String r = Integrity.reportJson(Integrity.State.OK, 1, false, false, true);
        check("report parses back as the exact published fields", r.equals(
                "{\"shield_version\":1,\"state\":\"ok\",\"signature_count\":1,"
                + "\"debugger_attached\":false,\"emulator_hint\":false,\"obfuscated\":true}"));
        String repackaged = Integrity.reportJson(Integrity.State.MISMATCH, 2, true, true, false);
        check("MISMATCH report carries the evidence booleans", repackaged.contains("\"state\":\"mismatch\"")
                && repackaged.contains("\"debugger_attached\":true")
                && repackaged.contains("\"emulator_hint\":true")
                && repackaged.contains("\"obfuscated\":false"));
        // No identifiers ever: IMEI/serial/android-id/mac/model/fingerprint tokens must not appear
        String all = r + repackaged;
        boolean leak = all.matches(".*(imei|serial|android_id|\"model\"|\"fingerprint\"|\"mac\"|[0-9a-f]{32}).*");
        check("report contains no device identifiers (privacy contract)", !leak);
        check("null state never breaks the report",
                Integrity.reportJson(null, -1, false, false, false).contains("\"state\":\"unknown\""));

        // No RESULT line here on purpose: sub-suites report via static counters and the
        // CoreTests aggregator prints the single aggregate RESULT line (the pipeline
        // totals RESULT lines — a per-suite line here would double-count this suite).
        if (fail > 0) System.exit(1);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
    }
}
