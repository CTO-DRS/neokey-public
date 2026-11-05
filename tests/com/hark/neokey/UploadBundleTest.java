package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * UploadBundleTest — week 32 (phase 6 W3) unit suite for the upload-bundle
 * unit: the permission→answer derivation, the ui_en coupling, the deterministic
 * statement, the per-locale listing regeneration (PlayGate round-trip), and
 * EVERY refusal tag of the quiet validator on mutated synthetic bundles.
 *
 * No build artifacts are required: the synthetic bundle uses the real gate
 * artifact bytes when they exist (so the W31 anchor chain holds in every
 * environment) and fixed bytes otherwise. The repo LIVE manifest + LIVE
 * assets/ui_en.txt are the ground truth — exactly what the gate reads.
 */
public final class UploadBundleTest {
    private UploadBundleTest() {}

    static int PASS = 0, FAIL = 0;

    public static void main(String[] args) throws Exception {
        // ---------------- positives -----------------------------------------
        pos_manifestValue();
        pos_parsePerms_live();
        pos_uiKeys_liveCoupling();
        pos_safetyLines_shape();
        pos_safetyLines_refusals();
        pos_evidenceLines();
        pos_listingRegenerationRoundTrip();
        pos_statementDeterministic();
        pos_shaVectors();
        pos_codePointCounting();
        pos_syntheticBundleValidates();

        // ---------------- negatives: every refusal tag bites -----------------
        neg("members.missing", "drop privacy-policy.html");
        neg("apk.mismatch", "flip an APK byte");
        neg("identity.version", "statement claims a wrong versionCode");
        neg("statement.schema", "statement schema corrupted");
        neg("listing.over_limit", "31-char Arabic app name");
        neg("ui.decoupled", "evidence key not in ui_en.txt");
        neg("safety.unproven", "answer for a permission the manifest does not declare");
        neg("safety.unanswered", "declared permission without an answer");
        neg("safety.stale_citation", "declaredAt points at the wrong line");
        neg("safety.collected_true", "an answer claims collection");
        neg("privacy.missing", "privacy policy without the support contact");
        neg("rollout.missing_stage", "rollout plan missing the 50% stage");
        neg("mapping.invalid", "mapping.txt without R8 arrows");

        System.out.println();
        System.out.println("RESULT " + PASS + " passed, " + FAIL + " failed (UploadBundleTest)");
        if (FAIL > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { PASS++; System.out.println("PASS " + name); }
        else { FAIL++; System.out.println("FAIL " + name + "  " + detail); }
    }

    private static String repoText(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String manifest() throws Exception { return repoText("AndroidManifest.xml"); }

    /** a VALID bundle built from LIVE repo inputs (+ real gate APK when present) */
    private static File syntheticBundle(File dir) throws Exception {
        String mf = manifest();
        String vc = UploadBundle.manifestValue(mf, "versionCode");
        String vn = UploadBundle.manifestValue(mf, "versionName");
        List<UploadBundle.Perm> perms = UploadBundle.parsePerms(mf);
        PlayGate.Fields ar = PlayGate.section(repoText("play/listing.txt"), "العربية");
        PlayGate.Fields en = PlayGate.section(repoText("play/listing.txt"), "الإنجليزية");
        String uiEn = repoText("assets/ui_en.txt");

        dir.mkdirs();
        byte[] apkBytes = new File("build/NeoKey-2036.apk").isFile()
                ? Files.readAllBytes(new File("build/NeoKey-2036.apk").toPath())
                : "synthetic-apk-bytes-for-unit-tests-only".getBytes(StandardCharsets.UTF_8);
        write(dir, UploadBundle.MEMBERS[0], apkBytes);
        write(dir, UploadBundle.MEMBERS[1], "com.hark.neokey.A -> a\ncom.hark.neokey.B -> b\n".getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[2], UploadBundle.localeListing("العربية (ar-SA)", ar, "ar").getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[3], UploadBundle.localeListing("الإنجليزية (en-US)", en, "en").getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[4], UploadBundle.listingJson(ar, en).getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[5], ("{\n  \"schema\": \"" + UploadBundle.SAFETY_SCHEMA + "\",\n"
                + "  \"permissions\": [\n" + UploadBundle.safetyLines(perms) + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[6], repoText("play/privacy.html").getBytes(StandardCharsets.UTF_8));
        write(dir, UploadBundle.MEMBERS[7], ("{\"stages\": [{\"fraction\": 0.10}, {\"fraction\": 0.50}, {\"fraction\": 1.00}],"
                + " \"haltProcedure\": \"Play Console halt\"}\n").getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> hashes = new LinkedHashMap<String, String>();
        for (String m : UploadBundle.MEMBERS) hashes.put(m, UploadBundle.sha256Hex(Files.readAllBytes(new File(dir, m).toPath())));
        write(dir, "statement.json", UploadBundle.statementJson(vc, vn, UploadBundle.sha256Hex(apkBytes),
                UploadBundle.sha256Hex("signedstmt".getBytes(StandardCharsets.UTF_8)),
                hashes, ar, en, perms.size()).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static void write(File dir, String name, byte[] data) throws Exception {
        Files.write(new File(dir, name).toPath(), data);
    }

    private static File negDir(String name) { return new File("build/test-upload", name); }

    /** builds a fresh valid bundle, applies the mutation, expects the tag */
    private static void neg(String wantTag, String what) throws Exception {
        File dir = syntheticBundle(negDir(wantTag.replace('.', '_')));
        String mf = manifest();
        String uiEn = repoText("assets/ui_en.txt");
        try {
            if (wantTag.equals("members.missing")) {
                if (!new File(dir, UploadBundle.MEMBERS[6]).delete()) throw new IllegalStateException(what);
            } else if (wantTag.equals("apk.mismatch")) {
                byte[] apk = Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[0]).toPath());
                apk[apk.length / 2] ^= 0x5A;
                Files.write(new File(dir, UploadBundle.MEMBERS[0]).toPath(), apk);
            } else if (wantTag.equals("identity.version")) {
                String s = new String(Files.readAllBytes(new File(dir, "statement.json").toPath()), StandardCharsets.UTF_8);
                s = s.replaceFirst("\"versionCode\": [0-9]+", "\"versionCode\": 9999");
                Files.write(new File(dir, "statement.json").toPath(), s.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("statement.schema")) {
                String s = new String(Files.readAllBytes(new File(dir, "statement.json").toPath()), StandardCharsets.UTF_8);
                s = s.replace(UploadBundle.SCHEMA, "neokey-uploadbundle-0");
                Files.write(new File(dir, "statement.json").toPath(), s.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("listing.over_limit")) {
                String t = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[2]).toPath()), StandardCharsets.UTF_8);
                StringBuilder longName = new StringBuilder("اسم تطبيق طويل جداً ومبالغ فيه على القنصل");
                while (longName.codePointCount(0, longName.length()) < 31) longName.append("ز");
                t = t.replaceFirst("(?m)^اسم التطبيق \\([0-9]+/30\\):\n.*$",
                        "اسم التطبيق (31/30):\n" + longName);
                Files.write(new File(dir, UploadBundle.MEMBERS[2]).toPath(), t.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("ui.decoupled")) {
                String lj = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[4]).toPath()), StandardCharsets.UTF_8);
                lj = lj.replace("دقة التنبؤ بالكلمات", "عبارة غير موجودة في الجدول الحي إطلاقاً");
                Files.write(new File(dir, UploadBundle.MEMBERS[4]).toPath(), lj.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("safety.unproven")) {
                String ds = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[5]).toPath()), StandardCharsets.UTF_8);
                ds = ds.replace("\n  ]\n}", ",\n    {\"name\": \"android.permission.CAMERA\", \"declaredAt\": \"AndroidManifest.xml:999\", \"collected\": false, \"purpose\": \"x\", \"policyCitation\": \"y\"}\n  ]\n}");
                Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(), ds.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("safety.unanswered")) {
                String ds = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[5]).toPath()), StandardCharsets.UTF_8);
                StringBuilder out = new StringBuilder();
                for (String line : ds.split("\n", -1)) {
                    if (line.contains("android.permission.INTERNET") && line.contains("\"declaredAt\"")) continue;
                    out.append(line).append('\n');
                }
                Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(),
                        out.toString().replaceAll(",(\\s*\\])", "$1").getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("safety.stale_citation")) {
                String ds = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[5]).toPath()), StandardCharsets.UTF_8);
                ds = ds.replaceFirst("AndroidManifest.xml:[0-9]+", "AndroidManifest.xml:12345");
                Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(), ds.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("safety.collected_true")) {
                String ds = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[5]).toPath()), StandardCharsets.UTF_8);
                ds = ds.replaceFirst("\"collected\": false", "\"collected\": true");
                Files.write(new File(dir, UploadBundle.MEMBERS[5]).toPath(), ds.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("privacy.missing")) {
                String p = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[6]).toPath()), StandardCharsets.UTF_8);
                p = p.replace("cto.drs@gmail.com", "contact-removed@example.invalid");
                Files.write(new File(dir, UploadBundle.MEMBERS[6]).toPath(), p.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("rollout.missing_stage")) {
                String r = new String(Files.readAllBytes(new File(dir, UploadBundle.MEMBERS[7]).toPath()), StandardCharsets.UTF_8);
                r = r.replace("{\"fraction\": 0.50}, ", "");
                Files.write(new File(dir, UploadBundle.MEMBERS[7]).toPath(), r.getBytes(StandardCharsets.UTF_8));
            } else if (wantTag.equals("mapping.invalid")) {
                Files.write(new File(dir, UploadBundle.MEMBERS[1]).toPath(), "no arrows here\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (UploadBundle.Fail f) {
            ok("neg." + wantTag, false, "mutation failed upstream: " + f.tag);
            return;
        }
        String tag = UploadBundleGate.checkQuiet(dir, mf, uiEn);
        ok("neg." + wantTag, wantTag.equals(tag), "expected " + wantTag + " but " + (tag == null ? "ACCEPT (check stopped biting!)" : "got " + tag));
    }

    // -------------------------------------------------------------- positives

    private static void pos_manifestValue() throws Exception {
        String mf = manifest();
        ok("pos.manifestValue.vc", "73".equals(UploadBundle.manifestValue(mf, "versionCode")),
                "vc=" + UploadBundle.manifestValue(mf, "versionCode"));
        ok("pos.manifestValue.vn", "2.6".equals(UploadBundle.manifestValue(mf, "versionName")),
                "vn=" + UploadBundle.manifestValue(mf, "versionName"));
    }

    private static void pos_parsePerms_live() throws Exception {
        List<UploadBundle.Perm> perms = UploadBundle.parsePerms(manifest());
        ok("pos.parsePerms.count", perms.size() == 3, "perms=" + perms.size());
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (UploadBundle.Perm p : perms) names.add(p.name);
        ok("pos.parsePerms.noDuplicates", names.size() == perms.size(), "duplicate declarations present");
        ok("pos.parsePerms.internet",
                names.contains("android.permission.INTERNET") && names.contains("android.permission.RECORD_AUDIO")
                        && names.contains("android.permission.VIBRATE"), "names=" + names);
    }

    private static void pos_uiKeys_liveCoupling() throws Exception {
        LinkedHashSet<String> keys = UploadBundle.uiKeys(repoText("assets/ui_en.txt"));
        ok("pos.uiKeys.size", keys.size() > 800, "keys=" + keys.size());
        int live = 0;
        for (String[] ev : UploadBundle.UI_EVIDENCE) if (keys.contains(ev[1])) live++;
        ok("pos.uiKeys.evidenceAllLive", live == UploadBundle.UI_EVIDENCE.length, live + "/" + UploadBundle.UI_EVIDENCE.length);
    }

    private static void pos_safetyLines_shape() throws Exception {
        String lines = UploadBundle.safetyLines(UploadBundle.parsePerms(manifest()));
        ok("pos.safetyLines.count", lines.split("\n").length == 3, "lines=" + lines.split("\n").length);
        ok("pos.safetyLines.noCollection", !lines.contains("\"collected\": true"), "a collected=true leaked");
        ok("pos.safetyLines.citations", lines.contains("AndroidManifest.xml:") && lines.contains("سياسة الخصوصية"), "citations missing");
    }

    private static void pos_safetyLines_refusals() {
        String[] refused = { "safety.duplicate", "safety.unmapped" };
        int got = 0;
        List<UploadBundle.Perm> dup = new ArrayList<UploadBundle.Perm>();
        dup.add(new UploadBundle.Perm("android.permission.VIBRATE", 8));
        dup.add(new UploadBundle.Perm("android.permission.VIBRATE", 11));
        try { UploadBundle.safetyLines(dup); } catch (UploadBundle.Fail f) { if (f.tag.equals(refused[0])) got++; }
        List<UploadBundle.Perm> unmapped = new ArrayList<UploadBundle.Perm>();
        unmapped.add(new UploadBundle.Perm("android.permission.CAMERA", 12));
        try { UploadBundle.safetyLines(unmapped); } catch (UploadBundle.Fail f) { if (f.tag.equals(refused[1])) got++; }
        ok("pos.safetyLines.refusals", got == 2, got + "/2 — a declared permission without an answer MUST be refused");
    }

    private static void pos_evidenceLines() throws Exception {
        LinkedHashSet<String> keys = UploadBundle.uiKeys(repoText("assets/ui_en.txt"));
        String ev = UploadBundle.evidenceLines(keys);
        ok("pos.evidenceLines.count", ev.split("\n").length == UploadBundle.UI_EVIDENCE.length, "rows mismatch");
        LinkedHashSet<String> broken = new LinkedHashSet<String>(keys);
        broken.remove("حافظة ذكية");
        try { UploadBundle.evidenceLines(broken); ok("pos.evidenceLines.refusal", false, "decoupled key accepted"); }
        catch (UploadBundle.Fail f) { ok("pos.evidenceLines.refusal", "ui.decoupled".equals(f.tag), "tag=" + f.tag); }
    }

    private static void pos_listingRegenerationRoundTrip() throws Exception {
        String text = repoText("play/listing.txt");
        for (String[] loc : new String[][] { { "العربية", "ar" }, { "الإنجليزية", "en" } }) {
            PlayGate.Fields f = PlayGate.section(text, loc[0]);
            String regenerated = UploadBundle.localeListing(loc[0] + (loc[1].equals("ar") ? " (ar-SA)" : " (en-US)"), f, loc[1]);
            PlayGate.Fields back = PlayGate.section(regenerated, loc[0]);
            ok("pos.roundtrip." + loc[1], f.name.equals(back.name) && f.shortDesc.equals(back.shortDesc)
                            && f.full.equals(back.full) && f.nameNote.equals(UploadBundle.cp(back.name) + "/30"),
                    "regenerated listing does not re-parse to the same fields");
        }
    }

    private static void pos_statementDeterministic() throws Exception {
        PlayGate.Fields ar = PlayGate.section(repoText("play/listing.txt"), "العربية");
        PlayGate.Fields en = PlayGate.section(repoText("play/listing.txt"), "الإنجليزية");
        LinkedHashMap<String, String> h = new LinkedHashMap<String, String>();
        h.put("a", "aa"); h.put("b", "bb");
        String s1 = UploadBundle.statementJson("73", "2.6", "apk", "ss", h, ar, en, 3);
        String s2 = UploadBundle.statementJson("73", "2.6", "apk", "ss", h, ar, en, 3);
        ok("pos.statement.deterministic", s1.equals(s2) && s1.contains("\"schema\": \"neokey-uploadbundle-1\""), "statement not deterministic");
    }

    private static void pos_shaVectors() {
        ok("pos.sha256.empty", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(
                UploadBundle.sha256Hex(new byte[0])), "empty vector");
        ok("pos.sha256.abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(
                UploadBundle.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))), "abc vector");
    }

    private static void pos_codePointCounting() {
        String s = "نيو كيبورد - كيبورد عربي ذكي"; // 28 code points, no astral chars
        ok("pos.cp.arabic", UploadBundle.cp(s) == 28, "cp=" + UploadBundle.cp(s));
        String emoji = "a\uD83D\uDE00b"; // surrogate pair = 2 code points, 3 chars
        ok("pos.cp.astral", UploadBundle.cp(emoji) == 3, "cp=" + UploadBundle.cp(emoji));
    }

    /** the full positive: a bundle built from LIVE inputs passes ALL 12 checks */
    private static void pos_syntheticBundleValidates() throws Exception {
        File dir = syntheticBundle(negDir("valid"));
        String tag = UploadBundleGate.checkQuiet(dir, manifest(), repoText("assets/ui_en.txt"));
        ok("pos.syntheticBundle.valid", tag == null, "expected ACCEPT but got " + tag);
    }
}
