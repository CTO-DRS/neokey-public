package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UploadBundleGate — week 32 (phase 6 W3) gate: THE STORE-UPLOAD BUNDLE is
 * proven complete and self-consistent — every member cites the gate that
 * verified it, every data-safety answer is derived from the permissions
 * actually declared in the manifest, and every listing claim is coupled to a
 * LIVE ui_en.txt key. A bundle that misses a member, carries an unproven
 * answer, or quotes a UI string the translation table does not know is
 * refused here — never at the store console.
 *
 * Checks (deterministic order, each refusal one exact tag; 1-12 run on the
 * bundle alone via checkQuiet — the owner's `check` CLI and the negatives use
 * the same bodies; 13-14 are gate-mode checks about the process):
 *   1  members.complete     all 8 members present and non-empty
 *   2  identity.version     statement vc/vn == the LIVE AndroidManifest.xml
 *   3  statement.schema     schema neokey-uploadbundle-1 + apkSha256 64-hex
 *   4  apk.intact           bundle APK sha256 == statement.apkSha256 ==
 *                              build/release/signedapk-statement.json apkSha256
 *                              (the W31 anchor chain — the bundle carries THE
 *                              proven artifact, never a rebuild) and, when the
 *                              gate artifact exists, byte-equal to it
 *   5  listing.parse        both locale files re-parsed by PlayGate.section
 *                              + checkLocale — limits 30/80/4000 re-measured,
 *                              (n/m) annotations must be true
 *   6  listing.languages    ar AND en names both non-empty
 *   7  ui.coupled           every uiEvidence uiKey is an EXACT key of the
 *                              LIVE assets/ui_en.txt (the W22 coupling
 *                              discipline applied to the listing)
 *   8  safety.derived       data-safety names == declared manifest permissions,
 *                              both directions; declaredAt line citations match
 *                              the live lines; zero duplicate declarations
 *   9  safety.no_collection every answer is collected=false
 *  10  privacy.consistent   privacy-policy.html carries the identity, the
 *                              no-collection statement and the support contact
 *  11  rollout.staged       rollout-plan.json has 10/50/100 stages + halt
 *  12  mapping.carried      mapping.txt non-empty with real R8 arrows
 *  13  determinism          a second assembly of the same inputs produces the
 *                              byte-identical statement (gate mode)
 *  14  purity               UploadBundle.java + UploadBundleGate.java zero
 *                              android.* imports
 *
 * Negatives (each must fail with ITS OWN tag — a check that stops biting is a
 * dead guard and fails the gate):
 *   n1 missing privacy policy → members.missing
 *   n2 flipped APK byte       → apk.mismatch
 *   n3 answer w/o permission  → safety.unproven
 *   n4 permission w/o answer  → safety.unanswered
 *   n5 31-char app name       → listing.over_limit
 *   n6 evidence not in ui_en  → ui.decoupled
 *
 * Verdict: UPLOADBUNDLEGATE_OK checks=14 negatives=6/6 members=7 evidence=8
 *          permissions=3 vc=73 vn=2.6 apk_sha256=…  /  FAILED <reason>
 */
public final class UploadBundleGate {
    private UploadBundleGate() {}

    static final String EXPECTED_VC = "84";
    static final String EXPECTED_VN = "3.7";

    static int CHECKS = 0;
    static int NEG_OK = 0;
    static final int NEG_TOTAL = 6;

    public static void main(String[] args) throws Exception {
        File bundle = new File(args.length > 0 ? args[0] : "build/upload/bundle");
        String manifest = new String(Files.readAllBytes(new File("AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        String uiKeys = new String(Files.readAllBytes(new File("assets/ui_en.txt").toPath()), StandardCharsets.UTF_8);

        // ---- 1-12: the bundle checks (verbose PASS lines, exit on first fail) --
        checkVerbose(bundle, manifest, uiKeys);

        // ---- 13: determinism — same inputs, byte-identical statement -----------
        File again = new File("build/upload/determinism-check");
        UploadBundle.assemble(again);
        String s1 = new String(UploadBundle.member(bundle, "statement.json"), StandardCharsets.UTF_8);
        String s2 = new String(UploadBundle.member(again, "statement.json"), StandardCharsets.UTF_8);
        ck("determinism", s1.equals(s2), "two assemblies = one statement");

        // ---- 14: purity — zero android.* in both week-32 classes ---------------
        // (the needle is split so this check never matches its own source)
        String needle = "import android" + ".";
        boolean pure = !new String(Files.readAllBytes(
                new File("tests/com/hark/neokey/UploadBundle.java").toPath()), StandardCharsets.UTF_8).contains(needle)
                && !new String(Files.readAllBytes(
                new File("tests/com/hark/neokey/UploadBundleGate.java").toPath()), StandardCharsets.UTF_8).contains(needle);
        ck("purity", pure, "UploadBundle + UploadBundleGate zero android.*");

        // ---- negatives: every check must still bite ----------------------------
        System.out.println();
        File neg = new File("build/upload-neg");
        negTrip("negatives.missing_privacy", bundle, neg, manifest, uiKeys) ;
        negTrip("negatives.apk_tamper", bundle, neg, manifest, uiKeys);
        negTrip("negatives.safety_unproven", bundle, neg, manifest, uiKeys);
        negTrip("negatives.safety_unanswered", bundle, neg, manifest, uiKeys);
        negTrip("negatives.listing_over", bundle, neg, manifest, uiKeys);
        negTrip("negatives.ui_decoupled", bundle, neg, manifest, uiKeys);

        System.out.println();
        String apkSha = UploadBundle.sha256Hex(UploadBundle.member(bundle, UploadBundle.MEMBERS[0]));
        System.out.println("UPLOADBUNDLEGATE_OK checks=" + CHECKS + " negatives=" + NEG_OK + "/" + NEG_TOTAL
                + " members=" + UploadBundle.MEMBERS.length + " evidence=" + UploadBundle.UI_EVIDENCE.length
                + " vc=" + EXPECTED_VC + " vn=" + EXPECTED_VN + " apk_sha256=" + apkSha);
        if (NEG_OK != NEG_TOTAL) System.exit(1);
    }

    // ----------------------------------------------------------- the checks

    /** gate mode: run checks 1-12 with PASS lines, exit 1 on first refusal. */
    private static void checkVerbose(File bundle, String manifest, String uiKeys) {
        for (int i = 1; i <= 12; i++) {
            String tag = oneCheck(i, bundle, manifest, uiKeys);
            CHECKS++;
            System.out.println((tag == null ? "PASS " : "FAIL ") + checkName(i)
                    + (tag == null ? "" : "  " + tag));
            if (tag != null) { System.out.println("UPLOADBUNDLEGATE_FAILED " + checkName(i)); System.exit(1); }
        }
    }

    /** quiet mode: checks 1-12, first refusal tag (BARE — no detail suffix) or
     *  null — ONE body per check, shared by the gate, the owner's `check` CLI,
     *  and the negatives. */
    static String checkQuiet(File bundle, String manifest, String uiKeys) {
        for (int i = 1; i <= 12; i++) {
            String tag = oneCheck(i, bundle, manifest, uiKeys);
            if (tag != null) {
                int sp = tag.indexOf(' ');
                return sp < 0 ? tag : tag.substring(0, sp);
            }
        }
        return null;
    }

    private static String checkName(int i) {
        String[] names = { "members.complete", "identity.version", "statement.schema", "apk.intact",
                "listing.parse", "listing.languages", "ui.coupled", "safety.derived",
                "safety.no_collection", "privacy.consistent", "rollout.staged", "mapping.carried" };
        return names[i - 1];
    }

    /** the ONE derivation of each check — returns null when it passes, else the tag */
    private static String oneCheck(int i, File dir, String manifest, String uiKeysText) {
        try {
            switch (i) {
                case 1: { // members.complete
                    for (String m : UploadBundle.MEMBERS) {
                        byte[] b = UploadBundle.member(dir, m);
                        if (b == null || b.length == 0) return "members.missing (" + m + ")";
                    }
                    byte[] st = UploadBundle.member(dir, "statement.json");
                    if (st == null || st.length == 0) return "members.missing (statement.json)";
                    return null;
                }
                case 2: { // identity.version — statement vs LIVE manifest
                    String stmt = stmt(dir);
                    String vc = UploadBundle.manifestValue(manifest, "versionCode");
                    String vn = UploadBundle.manifestValue(manifest, "versionName");
                    if (!stmt.contains("\"versionCode\": " + vc + ",")) return "identity.version (statement vc != manifest " + vc + ")";
                    if (!stmt.contains("\"versionName\": \"" + vn + "\",")) return "identity.version (statement vn != manifest " + vn + ")";
                    return null;
                }
                case 3: { // statement.schema
                    String stmt = stmt(dir);
                    if (!stmt.contains("\"schema\": \"neokey-uploadbundle-1\"")) return "statement.schema";
                    Matcher m = Pattern.compile("\"apkSha256\": \"([0-9a-f]{64})\"").matcher(stmt);
                    if (!m.find()) return "statement.schema (apkSha256 not 64-hex)";
                    return null;
                }
                case 4: { // apk.intact — the W31 anchor chain
                    byte[] apk = UploadBundle.member(dir, UploadBundle.MEMBERS[0]);
                    String sha = UploadBundle.sha256Hex(apk);
                    String stmt = stmt(dir);
                    if (!stmt.contains("\"apkSha256\": \"" + sha + "\"")) return "apk.mismatch (bundle APK != statement)";
                    File signedStmt = new File("build/release/signedapk-statement.json");
                    if (signedStmt.isFile()) {
                        String ss = new String(Files.readAllBytes(signedStmt.toPath()), StandardCharsets.UTF_8);
                        if (!ss.contains("\"apkSha256\": \"" + sha + "\"")) return "apk.mismatch (bundle APK != the signedapk gate's artifact)";
                    }
                    File gateApk = new File("build/NeoKey-2036.apk");
                    if (gateApk.isFile() && !java.util.Arrays.equals(apk, Files.readAllBytes(gateApk.toPath())))
                        return "apk.mismatch (bundle APK != build/NeoKey-2036.apk byte-wise)";
                    return null;
                }
                case 5: { // listing.parse — PlayGate re-measures BOTH locale files
                    for (String[] loc : new String[][] { { UploadBundle.MEMBERS[2], "العربية", "ar" }, { UploadBundle.MEMBERS[3], "الإنجليزية", "en" } }) {
                        String text = new String(UploadBundle.member(dir, loc[0]), StandardCharsets.UTF_8);
                        try {
                            PlayGate.Fields f = PlayGate.section(text, loc[1]);
                            PlayGate.checkLocale(loc[2], f);
                        } catch (PlayGate.Gate g) {
                            String msg = g.getMessage() == null ? "" : g.getMessage();
                            return (msg.contains("chars, must be") ? "listing.over_limit (" + msg + ")" : "listing.invalid (" + msg + ")");
                        }
                    }
                    return null;
                }
                case 6: { // listing.languages
                    for (String[] loc : new String[][] { { UploadBundle.MEMBERS[2], "ar" }, { UploadBundle.MEMBERS[3], "en" } }) {
                        PlayGate.Fields f = PlayGate.section(new String(UploadBundle.member(dir, loc[0]), StandardCharsets.UTF_8), loc[1].equals("ar") ? "العربية" : "الإنجليزية");
                        if (f.name.trim().isEmpty()) return "listing.language_missing (" + loc[1] + " name empty)";
                    }
                    return null;
                }
                case 7: { // ui.coupled — evidence keys must be LIVE ui_en keys
                    String lj = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[4]), StandardCharsets.UTF_8);
                    LinkedHashSet<String> keys = UploadBundle.uiKeys(uiKeysText);
                    Matcher m = Pattern.compile("\\{\"claim\": \"[^\"]+\", \"uiKey\": \"([^\"]+)\"\\}").matcher(lj);
                    int n = 0;
                    while (m.find()) {
                        n++;
                        if (!keys.contains(m.group(1))) return "ui.decoupled (\"" + m.group(1) + "\" is not a live ui_en.txt key)";
                    }
                    if (n != UploadBundle.UI_EVIDENCE.length) return "ui.decoupled (evidence rows " + n + " != " + UploadBundle.UI_EVIDENCE.length + ")";
                    return null;
                }
                case 8: { // safety.derived — bidirectional + citations + no duplicates
                    List<UploadBundle.Perm> live = UploadBundle.parsePerms(manifest);
                    for (int a = 0; a < live.size(); a++)
                        for (int b = a + 1; b < live.size(); b++)
                            if (live.get(a).name.equals(live.get(b).name))
                                return "safety.duplicate (" + live.get(a).name + " declared twice)";
                    LinkedHashSet<String> liveNames = new LinkedHashSet<String>();
                    for (UploadBundle.Perm p : live) liveNames.add(p.name);
                    String ds = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[5]), StandardCharsets.UTF_8);
                    LinkedHashSet<String> answered = new LinkedHashSet<String>();
                    Matcher m = Pattern.compile("\\{\"name\": \"([^\"]+)\", \"declaredAt\": \"AndroidManifest.xml:([0-9]+)\"").matcher(ds);
                    while (m.find()) {
                        String name = m.group(1);
                        if (answered.contains(name)) return "safety.duplicate (answered twice: " + name + ")";
                        answered.add(name);
                        if (!liveNames.contains(name)) return "safety.unproven (" + name + " answered but NOT declared in the manifest)";
                        int wantLine = -1;
                        for (UploadBundle.Perm p : live) if (p.name.equals(name)) wantLine = p.line;
                        if (Integer.parseInt(m.group(2)) != wantLine)
                            return "safety.stale_citation (" + name + " cited at line " + m.group(2) + ", live line " + wantLine + ")";
                    }
                    for (UploadBundle.Perm p : live)
                        if (!answered.contains(p.name)) return "safety.unanswered (" + p.name + " declared at line " + p.line + " with NO data-safety answer)";
                    return null;
                }
                case 9: { // safety.no_collection
                    String ds = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[5]), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("\"collected\": (true|false)").matcher(ds);
                    int n = 0;
                    while (m.find()) {
                        n++;
                        if ("true".equals(m.group(1))) return "safety.collected_true (answer " + n + " claims collection — the developer collects nothing)";
                    }
                    if (n == 0) return "safety.collected_true (no answers found)";
                    return null;
                }
                case 10: { // privacy.consistent
                    String html = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[6]), StandardCharsets.UTF_8);
                    if (!html.contains("لا نجمع أي بيانات")) return "privacy.missing (no-collection statement absent)";
                    if (!html.contains("cto.drs@gmail.com")) return "privacy.missing (support contact absent)";
                    if (!html.contains("NeoKey")) return "privacy.missing (app identity absent)";
                    if (!html.contains("الميزات الاختيارية التي تستخدم الإنترنت")) return "privacy.missing (optional-features section absent — the INTERNET answer cites it)";
                    return null;
                }
                case 11: { // rollout.staged
                    String rp = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[7]), StandardCharsets.UTF_8);
                    if (!(rp.contains("0.10") && rp.contains("0.50") && rp.contains("1.00"))) return "rollout.missing_stage";
                    if (!rp.contains("haltProcedure")) return "rollout.missing_stage (no halt procedure)";
                    return null;
                }
                case 12: { // mapping.carried
                    String mp = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[1]), StandardCharsets.UTF_8);
                    if (mp.trim().isEmpty()) return "mapping.invalid (empty)";
                    if (!mp.contains(" -> ")) return "mapping.invalid (no R8 arrows)";
                    return null;
                }
                default: return "internal (no such check)";
            }
        } catch (UploadBundle.Fail f) {
            return f.tag;
        } catch (Exception e) {
            return "internal " + e;
        }
    }

    private static String stmt(File dir) throws Exception {
        byte[] b = UploadBundle.member(dir, "statement.json");
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static void ck(String id, boolean ok, String detail) {
        CHECKS++;
        System.out.println((ok ? "PASS " : "FAIL ") + id + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) { System.out.println("UPLOADBUNDLEGATE_FAILED " + id); System.exit(1); }
    }

    // ------------------------------------------------------------ negatives

    /** Each negative mutates a FRESH copy of the bundle, expects checkQuiet to
     *  refuse with the exact tag, then deletes the copy (restore inside the
     *  attack — the W24/25 lesson) and re-verifies the real bundle green. */
    private static void negTrip(String id, File bundle, File negRoot, String manifest, String uiKeys) throws Exception {
        String want;
        File dir = new File(negRoot, id);
        rmrf(dir);
        UploadBundle.copyDir(bundle, dir);
        String tag;
        if (id.endsWith("missing_privacy")) {
            want = "members.missing";
            if (!new File(dir, UploadBundle.MEMBERS[6]).delete()) throw new IllegalStateException("attack setup failed");
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        } else if (id.endsWith("apk_tamper")) {
            want = "apk.mismatch";
            byte[] apk = UploadBundle.member(dir, UploadBundle.MEMBERS[0]);
            apk[apk.length / 2] ^= 0x5A;
            java.nio.file.Files.write(new File(dir, UploadBundle.MEMBERS[0]).toPath(), apk);
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        } else if (id.endsWith("safety_unproven")) {
            want = "safety.unproven";
            appendSafety(dir, "    {\"name\": \"android.permission.CAMERA\", \"declaredAt\": \"AndroidManifest.xml:999\", \"collected\": false, \"purpose\": \"x\", \"policyCitation\": \"y\"}");
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        } else if (id.endsWith("safety_unanswered")) {
            want = "safety.unanswered";
            dropSafety(dir, "android.permission.INTERNET");
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        } else if (id.endsWith("listing_over")) {
            want = "listing.over_limit";
            overLimitName(dir);
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        } else {
            want = "ui.decoupled";
            String lj = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[4]), StandardCharsets.UTF_8);
            lj = lj.replace("دقة التنبؤ بالكلمات", "عبارة غير موجودة في الجدول الحي إطلاقاً");
            java.nio.file.Files.write(new File(dir, UploadBundle.MEMBERS[4]).toPath(), lj.getBytes(StandardCharsets.UTF_8));
            tag = UploadBundleGate.checkQuiet(dir, manifest, uiKeys);
        }
        boolean bitten = want.equals(tag);
        if (bitten) NEG_OK++;
        System.out.println((bitten ? "PASS " : "FAIL ") + id + " tag=" + tag
                + (bitten ? "" : "  expected " + want + " but got " + (tag == null ? "ACCEPT (check stopped biting!)" : tag)));
        rmrf(dir);
        // restore-inside-run: the REAL bundle must still be green after the attack
        String after = UploadBundleGate.checkQuiet(bundle, manifest, uiKeys);
        if (after != null) { System.out.println("UPLOADBUNDLEGATE_FAILED " + id + ".restore (" + after + ")"); System.exit(1); }
        if (!bitten) { System.out.println("UPLOADBUNDLEGATE_FAILED " + id); System.exit(1); }
    }

    private static void appendSafety(File dir, String line) throws Exception {
        String ds = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[5]), StandardCharsets.UTF_8);
        ds = ds.replace("\n  ]\n}", ",\n" + line + "\n  ]\n}");
        java.nio.file.Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(), ds.getBytes(StandardCharsets.UTF_8));
    }

    private static void dropSafety(File dir, String perm) throws Exception {
        String ds = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[5]), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : ds.split("\n", -1)) {
            if (line.contains("\"" + perm + "\"") && line.contains("\"declaredAt\"")) continue;
            out.append(line).append('\n');
        }
        // fix the JSON: the line before a removed trailing member may end with a comma
        String res = out.toString().replaceAll(",(\\s*\\])", "$1");
        java.nio.file.Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(), res.getBytes(StandardCharsets.UTF_8));
    }

    private static void overLimitName(File dir) throws Exception {
        String text = new String(UploadBundle.member(dir, UploadBundle.MEMBERS[2]), StandardCharsets.UTF_8);
        StringBuilder longName = new StringBuilder("اسم تطبيق طويل جداً ومبالغ فيه على القنصل");
        while (longName.codePointCount(0, longName.length()) < 31) longName.append("ز");
        text = text.replaceFirst("(?m)^اسم التطبيق \\([0-9]+/30\\):\n.*$",
                "اسم التطبيق (31/30):\n" + longName);
        java.nio.file.Files.write(new File(dir, UploadBundle.MEMBERS[2]).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void rmrf(File f) {
        if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) rmrf(x); }
        if (f.exists() && !f.delete()) throw new IllegalStateException("cannot delete " + f);
    }
}
