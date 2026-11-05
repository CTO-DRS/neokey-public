package com.hark.neokey;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UploadBundle — week 32 (phase 6 W3): assembles THE store-upload bundle from
 * inputs that are already proven by their own gates — nothing is claimed here
 * that a gate did not measure:
 *
 *   NeoKey-2036.apk        ← build/NeoKey-2036.apk      (SIGNEDAPKGATE_OK, W31)
 *   mapping.txt            ← build/release/mapping.txt   (R8 deobfuscation file)
 *   listing-ar/en.txt      ← play/listing.txt, re-measured via PlayGate
 *                            (PLAY_GATE_OK — same parser, one derivation)
 *   listing.json           ← fields + uiEvidence: every feature claim in the
 *                            listing is paired with a LIVE assets/ui_en.txt key
 *                            (the same coupling discipline as the W22 trim)
 *   data-safety.json       ← DERIVED from the uses-permission entries actually
 *                            declared in AndroidManifest.xml — no answer without
 *                            a declared permission, no permission without an
 *                            answer; duplicates refused; every answer is
 *                            collected=false and cites the privacy policy
 *   privacy-policy.html    ← play/privacy.html
 *   rollout-plan.json      ← build/release/rollout-plan.json (staged 10/50/100)
 *   statement.json         ← schema neokey-uploadbundle-1, DETERMINISTIC
 *                            (sha256 of the members + counts + identity — no
 *                            timestamps), so two assemblies of the same inputs
 *                            are byte-identical
 *
 * The validation half lives in UploadBundleGate (same package): the gate is the
 * one derivation of the checks; this class is the owner's assemble/check CLI.
 * Pure JDK, zero android.* — runs anywhere the pipeline runs.
 *
 * CLI:
 *   assemble <outDir>   build the bundle from the repo's proven inputs
 *   check <bundleDir>   validate an assembled bundle (the quiet path into
 *                       UploadBundleGate.checkQuiet — first refusal wins)
 * Exit: 0 OK / 2 UPLOADBUNDLE_FAIL <tag>.
 */
public final class UploadBundle {
    private UploadBundle() {}

    static final String SCHEMA = "neokey-uploadbundle-1";
    static final String LISTING_SCHEMA = "neokey-listing-1";
    static final String SAFETY_SCHEMA = "neokey-datasafety-1";

    /** the seven content members (statement.json is the eighth, generated last) */
    static final String[] MEMBERS = {
        "NeoKey-2036.apk", "mapping.txt", "listing-ar.txt", "listing-en.txt",
        "listing.json", "data-safety.json", "privacy-policy.html", "rollout-plan.json"
    };

    /**
     * listing claim → the LIVE ui_en.txt key that proves the feature ships.
     * Fail-closed in BOTH directions: assemble refuses a key that is not an
     * exact ui_en.txt key today, and the gate re-checks against the LIVE file
     * every run — if the translation table ever loses the key, the bundle is
     * stale and the gate bites (ui.decoupled).
     */
    static final String[][] UI_EVIDENCE = {
        {"تنبؤ وتصحيح عربي ذكي — قاموس ضخم يتعلّم من كتابتك", "دقة التنبؤ بالكلمات"},
        {"كتابة بالسحب — اسحب إصبعك على الحروف", "اكتب بالسحب"},
        {"سمات زجاج نيون — محرر سمات وخلفية من صورك", "محرر السمات"},
        {"أصوات ضغط ومؤثرات — حزم أصوات مُخلَّقة", "أصوات الأزرار"},
        {"حافظة ذكية — مثبّتات ومفضلة وتصنيفات وقوالب", "حافظة ذكية"},
        {"أدوات فوق الكيبورد — كتابة صوتية", "كتابة صوتية"},
        {"إمكانية وصول — تباين عالٍ", "تباين عالٍ (يتجاوز السمة الحالية)"},
        {"إمكانية وصول — وضع اليد الواحدة", "وضع اليد الواحدة"}
    };

    /**
     * The data-safety answer per permission — the ONLY permissions this table
     * may answer. A declared permission missing here is an assembly refusal
     * (unmapped_permission): no silent answer, no answer without a declared
     * permission. Every answer is collected=false — the developer collects
     * nothing; optional network features ride user-supplied endpoints/keys.
     */
    static final String[][] SAFETY_ANSWERS = {
        {"android.permission.VIBRATE",
            "تأثير لمسي عند الضغط — إعداد المستخدم، لا بيانات تُجمع أو تُرسل",
            "سياسة الخصوصية — «ما الذي يُحفظ على جهازك فقط؟»"},
        {"android.permission.RECORD_AUDIO",
            "الكتابة الصوتية عند طلب صريح من المستخدم؛ الصوت يُعالج لحظياً ولا يُحفظ ولا يُرسل من التطبيق",
            "سياسة الخصوصية — «الميزات الاختيارية التي تستخدم الإنترنت»"},
        {"android.permission.INTERNET",
            "ميزات اختيارية يفعّلها المستخدم بمحتواه الخاص: مزامنة على خادم يختاره المستخدم، وتحسين نصي بمفتاح OpenRouter الخاص به",
            "سياسة الخصوصية — «الميزات الاختيارية التي تستخدم الإنترنت»"}
    };

    static final class Perm {
        final String name; final int line;
        Perm(String name, int line) { this.name = name; this.line = line; }
    }

    static final class Fail extends RuntimeException {
        final String tag;
        Fail(String tag, String detail) { super(tag + (detail.isEmpty() ? "" : " — " + detail)); this.tag = tag; }
    }

    // ------------------------------------------------------------------ CLI

    public static void main(String[] args) {
        try {
            String cmd = args.length > 0 ? args[0] : "";
            if ("assemble".equals(cmd) && args.length >= 2) { assemble(new File(args[1])); return; }
            if ("check".equals(cmd) && args.length >= 2) {
                File dir = new File(args[1]);
                String tag = UploadBundleGate.checkQuiet(dir,
                        new String(Files.readAllBytes(new File("AndroidManifest.xml").toPath()), StandardCharsets.UTF_8),
                        new String(Files.readAllBytes(new File("assets/ui_en.txt").toPath()), StandardCharsets.UTF_8));
                if (tag == null) {
                    System.out.println("UPLOADBUNDLE_OK members=" + MEMBERS.length
                            + " apk_sha256=" + sha256Hex(Files.readAllBytes(new File(dir, MEMBERS[0]).toPath())));
                    return;
                }
                System.out.println("UPLOADBUNDLE_FAIL " + tag);
                System.exit(2);
            }
            System.err.println("usage: UploadBundle assemble <outDir> | check <bundleDir>");
            System.exit(2);
        } catch (Fail f) {
            System.out.println("UPLOADBUNDLE_FAIL " + f.tag);
            System.exit(2);
        } catch (Exception e) {
            System.out.println("UPLOADBUNDLE_FAIL internal " + e);
            System.exit(2);
        }
    }

    // -------------------------------------------------------------- assemble

    /** Assembles the bundle from the repo's proven inputs. Refusals are Fail(tag). */
    static void assemble(File out) throws Exception {
        String manifest = new String(Files.readAllBytes(new File("AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        String vc = manifestValue(manifest, "versionCode"), vn = manifestValue(manifest, "versionName");

        // ---- permissions actually declared — the data-safety ground truth ----
        List<Perm> perms = parsePerms(manifest);
        String permLines = safetyLines(perms);

        // ---- listing: the SAME parser the weekly play gate uses --------------
        String listingText = new String(Files.readAllBytes(new File("play/listing.txt").toPath()), StandardCharsets.UTF_8);
        PlayGate.Fields ar, en;
        try {
            ar = PlayGate.section(listingText, "العربية");
            en = PlayGate.section(listingText, "الإنجليزية");
            PlayGate.checkLocale("ar", ar);
            PlayGate.checkLocale("en", en);
        } catch (PlayGate.Gate g) {
            throw new Fail("listing.invalid", g.getMessage());
        }

        // ---- ui evidence: every claim paired with a LIVE ui_en.txt key -------
        String uiEn = new String(Files.readAllBytes(new File("assets/ui_en.txt").toPath()), StandardCharsets.UTF_8);
        LinkedHashSet<String> keys = uiKeys(uiEn);
        String evLines = evidenceLines(keys);

        // ---- proven inputs must exist — no placeholder members ---------------
        byte[] apk = readExact(new File("build/NeoKey-2036.apk"), "apk.missing", "build/NeoKey-2036.apk (run the signedapk stage first)");
        byte[] mapping = readExact(new File("build/release/mapping.txt"), "mapping.missing", "build/release/mapping.txt (run the r8 stage first)");
        if (!new String(mapping, StandardCharsets.UTF_8).contains(" -> "))
            throw new Fail("mapping.invalid", "build/release/mapping.txt has no R8 mapping arrows");
        byte[] privacy = readExact(new File("play/privacy.html"), "privacy.missing", "play/privacy.html");
        byte[] rollout = readExact(new File("build/release/rollout-plan.json"), "rollout.missing", "build/release/rollout-plan.json (run the release stage first)");
        byte[] signedStmt = readExact(new File("build/release/signedapk-statement.json"), "signedstmt.missing",
                "build/release/signedapk-statement.json (the W31 signed-artifact statement — the bundle anchors to it)");

        // ---- write the bundle -------------------------------------------------
        out.mkdirs();
        write(out, MEMBERS[0], apk);
        write(out, MEMBERS[1], mapping);
        write(out, MEMBERS[2], localeListing("العربية (ar-SA)", ar, "ar").getBytes(StandardCharsets.UTF_8));
        write(out, MEMBERS[3], localeListing("الإنجليزية (en-US)", en, "en").getBytes(StandardCharsets.UTF_8));
        write(out, MEMBERS[4], listingJson(ar, en).getBytes(StandardCharsets.UTF_8));
        write(out, MEMBERS[5], ("{\n  \"schema\": \"" + SAFETY_SCHEMA + "\",\n"
                + "  \"derivedFrom\": \"AndroidManifest.xml uses-permission (parsed live at assembly)\",\n"
                + "  \"developerCollectedData\": \"none — every answer below is collected=false; optional network features ride user-supplied endpoints and user-supplied keys\",\n"
                + "  \"permissions\": [\n" + permLines + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8));
        write(out, MEMBERS[6], privacy);
        write(out, MEMBERS[7], rollout);

        // ---- deterministic statement (no timestamps — same inputs, same bytes)
        java.util.LinkedHashMap<String, String> hashes = new java.util.LinkedHashMap<String, String>();
        for (String m : MEMBERS) hashes.put(m, sha256Hex(Files.readAllBytes(new File(out, m).toPath())));
        String stmt = statementJson(vc, vn, sha256Hex(apk), sha256Hex(signedStmt), hashes, ar, en, perms.size());
        write(out, "statement.json", stmt.getBytes(StandardCharsets.UTF_8));
        System.out.println("UPLOADBUNDLE_ASSEMBLED members=" + MEMBERS.length
                + " evidence=" + UI_EVIDENCE.length + " permissions=" + perms.size()
                + " vc=" + vc + " vn=" + vn + " apk_sha256=" + sha256Hex(apk));
    }

    // ----------------------------------------------------------- primitives

    /**
     * The data-safety JSON member lines for the declared permissions — THE one
     * derivation of "answer from declaration": a duplicate declaration is
     * refused (safety.duplicate), a declared permission without an answer in
     * SAFETY_ANSWERS is refused (safety.unmapped) — never a silent answer.
     */
    static String safetyLines(List<Perm> perms) {
        for (int i = 0; i < perms.size(); i++)
            for (int j = i + 1; j < perms.size(); j++)
                if (perms.get(i).name.equals(perms.get(j).name))
                    throw new Fail("safety.duplicate", perms.get(i).name + " declared twice (lines "
                            + perms.get(i).line + "," + perms.get(j).line + ") — one permission, one answer");
        StringBuilder b = new StringBuilder();
        for (Perm p : perms) {
            String[] answer = answerFor(p.name);
            if (answer == null) throw new Fail("safety.unmapped",
                    p.name + " is declared (line " + p.line + ") but has no data-safety answer — add it to SAFETY_ANSWERS with its proof");
            if (b.length() > 0) b.append(",\n");
            b.append("    {\"name\": \"").append(p.name)
                    .append("\", \"declaredAt\": \"AndroidManifest.xml:").append(p.line)
                    .append("\", \"collected\": false, \"purpose\": \"").append(answer[1])
                    .append("\", \"policyCitation\": \"").append(answer[2]).append("\"}");
        }
        return b.toString();
    }

    /** The uiEvidence JSON lines — refuses any key that is not a LIVE ui_en.txt
     *  key (ui.decoupled): the listing may claim only what the shipped UI says. */
    static String evidenceLines(LinkedHashSet<String> uiEnKeys) {
        StringBuilder b = new StringBuilder();
        for (String[] ev : UI_EVIDENCE) {
            if (!uiEnKeys.contains(ev[1])) throw new Fail("ui.decoupled",
                    "listing claim \"" + ev[0] + "\" cites \"" + ev[1] + "\" which is NOT a live ui_en.txt key");
            if (b.length() > 0) b.append(",\n");
            b.append("    {\"claim\": \"").append(ev[0]).append("\", \"uiKey\": \"").append(ev[1]).append("\"}");
        }
        return b.toString();
    }

    /** The deterministic statement — pure: same arguments, byte-identical JSON. */
    static String statementJson(String vc, String vn, String apkSha, String signedStmtSha,
            java.util.LinkedHashMap<String, String> memberHashes, PlayGate.Fields ar, PlayGate.Fields en, int perms) {
        StringBuilder memberHashesJson = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : memberHashes.entrySet()) {
            if (memberHashesJson.length() > 0) memberHashesJson.append(",\n");
            memberHashesJson.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
        }
        return "{\n"
                + "  \"schema\": \"" + SCHEMA + "\",\n"
                + "  \"app\": \"com.hark.neokey\",\n"
                + "  \"versionCode\": " + vc + ",\n"
                + "  \"versionName\": \"" + vn + "\",\n"
                + "  \"apkSha256\": \"" + apkSha + "\",\n"
                + "  \"signedApkStatementSha256\": \"" + signedStmtSha + "\",\n"
                + "  \"members\": {\n" + memberHashesJson + "\n  },\n"
                + "  \"listing\": { \"name_ar\": " + cp(ar.name) + ", \"short_ar\": " + cp(ar.shortDesc)
                + ", \"full_ar\": " + cp(ar.full) + ", \"name_en\": " + cp(en.name)
                + ", \"short_en\": " + cp(en.shortDesc) + ", \"full_en\": " + cp(en.full) + " },\n"
                + "  \"uiEvidence\": " + UI_EVIDENCE.length + ",\n"
                + "  \"permissions\": " + perms + ",\n"
                + "  \"validator\": \"UploadBundleGate (tests/) — checks=14 negatives=6\"\n"
                + "}\n";
    }

    /** uses-permission entries with their 1-based line numbers (the citation). */
    static List<Perm> parsePerms(String manifest) {
        List<Perm> out = new ArrayList<Perm>();
        Pattern p = Pattern.compile("<uses-permission\\s+android:name=\"([^\"]+)\"");
        String[] lines = manifest.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            while (m.find()) out.add(new Perm(m.group(1), i + 1));
        }
        return out;
    }

    /** ui_en.txt keys: the exact text before the first TAB on each line. */
    static LinkedHashSet<String> uiKeys(String uiEnText) {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        for (String line : uiEnText.split("\n", -1)) {
            int t = line.indexOf('\t');
            if (t > 0) keys.add(line.substring(0, t));
        }
        return keys;
    }

    static String manifestValue(String manifest, String attr) {
        Matcher m = Pattern.compile("android:" + attr + "=\"([^\"]+)\"").matcher(manifest);
        if (!m.find()) throw new Fail("manifest." + attr, "attribute missing");
        return m.group(1);
    }

    private static String[] answerFor(String perm) {
        for (String[] a : SAFETY_ANSWERS) if (a[0].equals(perm)) return a;
        return null;
    }

    /** the per-locale paste-text, in the SAME format play/listing.txt uses so
     *  PlayGate.section re-parses it byte-for-byte (one parser, one derivation). */
    static String localeListing(String header, PlayGate.Fields f, String tag) {
        return "──────────────────────────── " + header + " ────────────────────────────\n"
                + "\nاسم التطبيق (" + cp(f.name) + "/30):\n" + f.name + "\n\n"
                + "الوصف المختصر (" + cp(f.shortDesc) + "/80):\n" + f.shortDesc + "\n\n"
                + "الوصف الكامل:\n" + f.full + "\n";
    }

    static String listingJson(PlayGate.Fields ar, PlayGate.Fields en) {
        StringBuilder ev = new StringBuilder();
        for (String[] e : UI_EVIDENCE) {
            if (ev.length() > 0) ev.append(",\n");
            ev.append("    {\"claim\": \"").append(e[0]).append("\", \"uiKey\": \"").append(e[1]).append("\"}");
        }
        return "{\n  \"schema\": \"" + LISTING_SCHEMA + "\",\n"
                + "  \"source\": \"play/listing.txt (verified by ci/play_gate.sh — one parser: PlayGate.section)\",\n"
                + "  \"localeAr\": { \"name\": \"" + jsonEsc(ar.name) + "\", \"nameChars\": " + cp(ar.name)
                + ", \"shortChars\": " + cp(ar.shortDesc) + ", \"fullChars\": " + cp(ar.full) + " },\n"
                + "  \"localeEn\": { \"name\": \"" + jsonEsc(en.name) + "\", \"nameChars\": " + cp(en.name)
                + ", \"shortChars\": " + cp(en.shortDesc) + ", \"fullChars\": " + cp(en.full) + " },\n"
                + "  \"uiEvidence\": [\n" + ev + "\n  ]\n}\n";
    }

    static int cp(String s) { return s.codePointCount(0, s.length()); }

    static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] readExact(File f, String tag, String hint) throws Exception {
        if (!f.isFile() || f.length() == 0) throw new Fail(tag, hint);
        return Files.readAllBytes(f.toPath());
    }

    private static void write(File dir, String name, byte[] data) throws Exception {
        Files.write(new File(dir, name).toPath(), data);
    }

    /** byte[] of a bundle member (null-safe helper for the gate/tests) */
    static byte[] member(File dir, String name) throws Exception {
        File f = new File(dir, name);
        return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
    }

    /** convenience for tests/gates: copy a whole bundle directory */
    static void copyDir(File from, File to) throws Exception {
        to.mkdirs();
        List<File> all = new ArrayList<File>();
        collect(from, all);
        for (File f : all) {
            File dst = new File(to, f.getName());
            FileInputStream in = new FileInputStream(f);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536]; int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            in.close(); out.close();
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) if (f.isFile()) out.add(f);
    }
}
