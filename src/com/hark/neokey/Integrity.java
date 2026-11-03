package com.hark.neokey;

import java.security.MessageDigest;

/**
 * Roadmap Phase 0 / item C — runtime integrity shield (security section 11, layer 6:
 * "فحص توقيع الحزمة وقت التشغيل ضد البصمة الرسمية، بهدوء وبلا إزعاج للمستخدم الشريف،
 * وبلا تجسس على الجهاز: فحوصات محدودة وموثقة علناً").
 *
 * Design rules (all binding):
 *  - PURE logic: zero android.* imports. Runs identically on the JVM (tests) and on
 *    device, and survives R8 renaming without keep rules.
 *  - Fail-CLOSED only for MISMATCH (a real signature that is not ours = repackaged).
 *    Fail-OPEN for UNKNOWN / SANDBOX / internal errors: a legitimate user on an odd
 *    device is never punished (roadmap §11 layer 4: "إنصافاً للمستخدم المشروع").
 *  - The verdict RESTRICTS paid features only (allowFeature). It never disables the
 *    keyboard — the real money decision lives server-side (layer 5, when VIP ships).
 *  - No device identifiers, no persistence of signatures, no network. The report is
 *    a handful of booleans + counts, published here in code (موثق علناً).
 *  - The official fingerprint is PUBLIC by nature (anyone can read it from Play),
 *    so embedding it is not a secret leak; it is the pin.
 */
public final class Integrity {

    private Integrity() { }

    /** Bump when the check set changes — appears in the on-device inspection report. */
    public static final int SHIELD_VERSION = 1;

    /**
     * Official upload-key certificate SHA-256 (keystore alias "neokey", docs/SECURITY.md,
     * vault KEY_FINGERPRINT.txt). Rotating the keystore PASSWORD does not change it;
     * regenerating the key WOULD — the tests below pin this constant to the vault file.
     * When Play App Signing is enabled, append the Play-managed signing certificate
     * fingerprint here (Play Console → Setup → App signing) so Play installs verify OK.
     */
    public static final String OFFICIAL_UPLOAD_FP =
            "00:1D:CF:BF:4C:EB:0F:93:16:BE:E8:5C:27:01:A7:F5:63:08:60:AA:BA:44:83:9C:91:F7:60:C5:D8:55:B9:ED";

    /** All signatures the app must accept (add the Play App Signing cert when enabled). */
    public static final String[] OFFICIAL_FINGERPRINTS = { OFFICIAL_UPLOAD_FP };

    /** Verdict of the shield. Only MISMATCH restricts anything. */
    public enum State {
        OK,        // at least one signing certificate matches the official pin
        MISMATCH,  // real signature(s) present, none official -> repackaged/resigned build
        SANDBOX,   // android:debuggable build (IDE/dev) — tolerated, logged only
        UNKNOWN    // no signature available (tooling/JVM context) — tolerated, logged only
    }

    /** Lowercases and strips colons/spaces/dashes; returns "" for null/blank input. */
    public static String normalize(String fp) {
        if (fp == null) return "";
        StringBuilder b = new StringBuilder(64);
        for (int i = 0; i < fp.length(); i++) {
            char c = fp.charAt(i);
            if (c == ':' || c == '-' || c == ' ') continue;
            if (c >= 'A' && c <= 'Z') c += 32;
            b.append(c);
        }
        String s = b.toString();
        if (s.length() != 64) return "";
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return "";
        }
        return s;
    }

    /** SHA-256 of a DER-encoded certificate (what PackageManager hands us), hex lowercase. */
    public static String sha256Hex(byte[] der) {
        if (der == null || der.length == 0) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(der);
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) {
                int v = x & 0xFF;
                b.append("0123456789abcdef".charAt(v >>> 4));
                b.append("0123456789abcdef".charAt(v & 0xF));
            }
            return b.toString();
        } catch (Exception e) {
            return ""; // SHA-256 exists on every JVM/Android; a throw here is impossible
        }
    }

    /**
     * The decision. debuggable wins first: dev builds are always tolerated even if they
     * carry a foreign signature (adb-deployed IDE builds). No signatures at all is
     * UNKNOWN (tools/JVM), not an accusation. Any official match -> OK. Otherwise the
     * build carries a real non-official signature: that is the repackaged-apk pattern
     * (roadmap threat table: "إعادة نشر باسم آخر") -> MISMATCH.
     */
    public static State verdict(String[] normalizedSignatures, boolean debuggable) {
        if (debuggable) return State.SANDBOX;
        if (normalizedSignatures == null || normalizedSignatures.length == 0) return State.UNKNOWN;
        for (String sig : normalizedSignatures) {
            String s = normalize(sig);
            if (s.isEmpty()) continue;
            for (String official : OFFICIAL_FINGERPRINTS) {
                if (s.equals(normalize(official))) return State.OK;
            }
        }
        return State.MISMATCH;
    }

    /**
     * Feature gate for revenue-sensitive features (VIP when it ships). The keyboard and
     * all free functionality are NEVER gated by this — only the small paid surface.
     * UNKNOWN/SANDBOX deliberately pass (testers, dev builds); only a non-official
     * release signature is denied.
     */
    public static boolean allowFeature(State s) {
        return s != State.MISMATCH;
    }

    /**
     * Emulator hint — RECORDED ONLY, never acted on by itself: our own 100-beta testers
     * legitimately run emulators. Matches on build fingerprints of common SDK emulators
     * (goldfish/ranchu, sdk_gphone, genymotion/vbox, plain "generic"). Explicitly limited
     * and published here: nothing else about the device is inspected.
     */
    public static boolean emulatorHint(String buildFingerprint, String model, String hardware) {
        String a = low(buildFingerprint) + "|" + low(model) + "|" + low(hardware);
        return a.contains("goldfish") || a.contains("ranchu") || a.contains("sdk_gphone")
                || a.contains("genymotion") || a.contains("vbox86") || a.contains("/generic")
                || a.contains("generic_x86") || a.contains("emulator");
    }

    private static String low(String s) { return s == null ? "" : s.toLowerCase(); }

    /**
     * The anonymous inspection report shown to the user in Settings and exportable with
     * the local perf data. Privacy contract (published, tested): NO device identifiers,
     * NO signature bytes, NO timestamps beyond what Perf already handles — exactly the
     * fields listed here, nothing else.
     * "obfuscated" is a cheap layer-1+6 cross-check: if the class still carries its
     * source name, this build did NOT go through R8 (missed build line or an IDE build).
     */
    public static String reportJson(State s, int signatureCount, boolean debuggerAttached,
                                    boolean emuHint, boolean obfuscated) {
        StringBuilder b = new StringBuilder(160);
        b.append("{\"shield_version\":").append(SHIELD_VERSION);
        b.append(",\"state\":\"").append(s == null ? "unknown" : s.name().toLowerCase()).append('"');
        b.append(",\"signature_count\":").append(Math.max(0, signatureCount));
        b.append(",\"debugger_attached\":").append(debuggerAttached);
        b.append(",\"emulator_hint\":").append(emuHint);
        b.append(",\"obfuscated\":").append(obfuscated);
        b.append('}');
        return b.toString();
    }
}
