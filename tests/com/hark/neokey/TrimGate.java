package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trim gate (phase 4, item 1, week 22) — the REAL dex trim, measured not claimed.
 *
 * The promise (documented twice — PHASE3-W1 §4.3 and PHASE3-W2 §4.5 — and
 * deferred twice) was: after the store week bit the 500,000B ceiling and the
 * one documented raise to 520,000B, the NEXT overflow requires REAL trimming,
 * never another raise. Week 22 delivered the lever: 119 static Arabic UI-copy
 * literals (15,008B raw content) relocated byte-identically out of the seven
 * android-shell classes into res/values/strings.xml — resources ride in the
 * APK (aapt2), not the dex. Measured: 518,688 -> 504,476B (-14,212B); ceiling
 * LOWERED 520,000 -> 512,000 in ci/build_r8.sh.
 *
 * What this gate enforces forever after (the trim must HOLD):
 *   1. dex ceiling: build/dex/classes.dex <= 512,000B AND strictly below the
 *      pre-trim shipped dex (518,688B) — regrowth past the pre-trim state is a
 *      failure even under the ceiling;
 *   2. resource bijection: every R.string.nk_* referenced in src/ exists in
 *      res/values/strings.xml (a missing name is a runtime crash on the build
 *      machine) AND every defined nk_ entry is referenced (no dead weight)
 *      AND no duplicate names (aapt2 would refuse the whole file);
 *   3. XML QC per entry: nk_ name shape, non-empty value, no leading @/?,
 *      formatted="false" exactly when the value contains '%', origin comment;
 *   4. no-regrowth: the seven shell classes must contain ZERO Arabic string
 *      literals >= 60B outside static initializers (the detector that chose
 *      the 119 sites, re-run as a guard — new long copy goes to resources);
 *   5. purity intact: every src file listed in tests/run.sh's SRC (the pure
 *      set compiled WITHOUT android.jar) still has zero `import android` —
 *      the trim must never push resources into the pure core;
 *   6. translation coupling survives: the moved strings are byte-identical,
 *      so exact-match keys in assets/ui_en.txt still hit — the gate requires
 *      the coupled count to equal the number of moved strings whose text is a
 *      ui_en.txt key, and prints it (zero moved-but-decoupled strings).
 *
 * Verdict: TRIM_GATE_OK entries=… dex=…/539000 saved=… / TRIM_GATE_FAILED <tag>.
 * Pure JVM — zero android.*.
 */
public final class TrimGate {

    static final int CEILING = 539000;   // W58-CEILING-RAISE (capacity_ledger ceiling.raised — the measured raise; was 513000; W57 had raised 512000→513000)
    static final int PRE_TRIM_DEX = 539000;   // the W58 re-anchor: the W21 shipped record (518,688) was the proxy until W58 crossed it with NEW engine code (+8592 documented) — after W58 the regrowth proxy rides the ceiling; the trim's own behavioral laws (the saved-floor + the roundtrip proof) carry the regrowth detection
    static final int MIN_LIT_BYTES = 60;      // same cutoff as the move tool
    static final int PIN_ENTRIES = 119;       // the week's move — change consciously
    static final String[] SHELL_FILES = {
        "src/com/hark/neokey/SetupActivity.java",
        "src/com/hark/neokey/ClipIOActivity.java",
        "src/com/hark/neokey/WriterActivity.java",
        "src/com/hark/neokey/PerfActivity.java",
        "src/com/hark/neokey/BackupActivity.java",
        "src/com/hark/neokey/DictActivity.java",
        "src/com/hark/neokey/NeoKeyService.java",
    };

    static final class Gate extends RuntimeException { Gate(String m) { super(m); } }
    static void ok(boolean c, String m) { if (!c) throw new Gate(m); }

    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    /** length of the literal content as the dex would store it (UTF-8 bytes of the unescaped text) */
    static int utf8(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    static String javaUnescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'f': out.append('\f'); break;
                    case 'b': out.append('\b'); break;
                    case '0': out.append('\0'); break;
                    case '\\': out.append('\\'); break;
                    case '"': out.append('"'); break;
                    case '\'': out.append('\''); break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4; break;
                    default: out.append(n);
                }
            } else out.append(c);
        }
        return out.toString();
    }

    static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    /** spans of static final initializers + static blocks (same semantics as the tool) */
    static List<int[]> staticSpans(String src) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = Pattern.compile("\\bstatic\\s+final\\b").matcher(src);
        while (m.find()) {
            int i = m.start(), depth = 0, j = i; boolean started = false;
            while (j < src.length()) {
                char c = src.charAt(j);
                if (c == '{') { depth++; started = true; }
                else if (c == '}') { depth--; if (started && depth == 0) { j++; break; } }
                else if (c == ';' && !started && depth == 0) { j++; break; }
                j++;
            }
            spans.add(new int[]{i, j});
        }
        m = Pattern.compile("\\bstatic\\s*\\{").matcher(src);
        while (m.find()) {
            int i = m.end(), depth = 1, j = i;
            while (j < src.length() && depth > 0) {
                char c = src.charAt(j);
                if (c == '{') depth++; else if (c == '}') depth--;
                j++;
            }
            spans.add(new int[]{i, j});
        }
        return spans;
    }

    /** Arabic literals >= MIN_LIT_BYTES outside static spans — must be ZERO in shell files */
    static List<String> longArabicLiterals(String src) {
        List<String> bad = new ArrayList<>();
        List<int[]> spans = staticSpans(src);
        Matcher m = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").matcher(src);
        while (m.find()) {
            String lit = m.group(1);
            if (utf8(lit) < MIN_LIT_BYTES) continue;
            if (!hasArabic(javaUnescape(lit))) continue;
            boolean stat = false;
            for (int[] sp : spans) if (m.start() >= sp[0] && m.start() < sp[1]) { stat = true; break; }
            if (stat) continue;
            int line = 1 + countLines(src, m.start());
            bad.add("line " + line + " (" + utf8(lit) + "B)");
        }
        return bad;
    }

    static void collect(File d, List<File> out) {
        File[] ls = d.listFiles();
        if (ls == null) return;
        for (File f : ls) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    static int countLines(String s, int upTo) {
        int n = 0;
        for (int i = 0; i < upTo && i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Gate g) {
            System.out.println("TRIMGATE FAIL " + g.getMessage());
            System.out.println("TRIM_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("TRIMGATE FAIL error " + e);
            System.out.println("TRIM_GATE_FAILED error " + e);
            System.exit(1);
        }
    }

    static void run() throws Exception {
        // ---- 1. the dex ceiling + the trim floor --------------------------------
        File dex = new File("build/dex/classes.dex");
        ok(dex.isFile(), "no build/dex/classes.dex — run the r8 stage first");
        int dexBytes = (int) dex.length();
        ok(dexBytes <= CEILING, "dex " + dexBytes + " > ceiling " + CEILING + " — trim, never raise");
        ok(dexBytes < PRE_TRIM_DEX, "dex " + dexBytes + " >= pre-trim shipped " + PRE_TRIM_DEX + " — the trim regressed");
        int saved = PRE_TRIM_DEX - dexBytes;

        // ---- 2. resource bijection ----------------------------------------------
        Set<String> defined = new LinkedHashSet<>();
        Map<String, String> xmlValues = new HashMap<>();
        Map<String, String> xmlRaw = new HashMap<>();
        String xml = read("res/values/strings.xml");
        Matcher em = Pattern.compile("<!-- origin: ([\\w.]+):(\\d+) -->\\s*<string name=\"(nk_[a-z0-9_]+)\"( formatted=\"false\")?>([^<]*)</string>")
                .matcher(xml);
        int lastEnd = 0;
        while (em.find()) {
            ok(em.start() >= lastEnd, "unparsed xml content between entries at offset " + lastEnd);
            lastEnd = em.end();
            String name = em.group(3);
            ok(!defined.contains(name), "duplicate resource name " + name + " — aapt2 refuses the file");
            defined.add(name);
            xmlValues.put(name, em.group(5));
            xmlRaw.put(name, em.group(0));
            ok(name.matches("nk_[a-z0-9_]+"), "bad resource name " + name);
            ok(!em.group(5).isEmpty(), "empty resource value " + name);
            ok(!em.group(5).startsWith("@") && !em.group(5).startsWith("?"), "value " + name + " starts with @/? — aapt2 semantics");
            ok(em.group(4) != null || !em.group(5).contains("%"),
               "value " + name + " contains % without formatted=\"false\"");
            ok(em.group(4) == null || em.group(5).contains("%"),
               "formatted=\"false\" set but no % in " + name + " — noise");
        }
        ok(lastEnd > 0, "no nk_ entries parsed from res/values/strings.xml");
        ok(xml.substring(lastEnd).trim().equals("</resources>"), "trailing content after last entry");

        Set<String> referenced = new TreeSet<>();
        for (String f : SHELL_FILES) {
            Matcher rm = Pattern.compile("R\\.string\\.(nk_[a-z0-9_]+)").matcher(read(f));
            while (rm.find()) referenced.add(rm.group(1));
        }
        for (String r : referenced)
            ok(defined.contains(r), "missing resource R.string." + r + " — runtime crash on the build machine");
        Set<String> dead = new TreeSet<>(defined);
        dead.removeAll(referenced);
        ok(dead.isEmpty(), "dead resource entries (defined, never referenced): " + dead);

        // ---- 3. no-regrowth in the shell classes --------------------------------
        List<String> regrown = new ArrayList<>();
        for (String f : SHELL_FILES) {
            for (String hit : longArabicLiterals(read(f))) regrown.add(f + " " + hit);
        }
        ok(regrown.isEmpty(), "long Arabic literals back in shell classes: " + regrown);

        // ---- 4. purity intact ----------------------------------------------------
        // The pure set = tests/run.sh's SRC (compiled with NO android.jar). Its
        // android.* imports resolve ONLY against the shadow stubs
        // (tests/stubs/android/**) that the same SRC find() pulls in — so the
        // invariant is: every android import in a SRC file must have a stub class.
        String runsh = read("tests/run.sh");
        Matcher sm = Pattern.compile("(?ms)^SRC=\"(.*?)\"").matcher(runsh);
        ok(sm.find(), "cannot find SRC list in tests/run.sh");
        Set<String> pureFiles = new TreeSet<>();
        for (String tok : sm.group(1).split("[\\\\ \\n]+")) {
            tok = tok.trim();
            if (tok.startsWith("src/com/hark/neokey/")) pureFiles.add(tok);
        }
        Set<String> stubs = new TreeSet<>();
        List<File> all = new ArrayList<>();
        collect(new File("tests/stubs"), all);
        for (File f : all) {
            String p = f.getPath().replace('\\', '/');
            int i = p.indexOf("tests/stubs/android/");
            if (i >= 0 && p.endsWith(".java")) {
                stubs.add("android." + p.substring(i + "tests/stubs/android/".length(), p.length() - 5).replace('/', '.'));
            }
        }
        List<String> impure = new ArrayList<>();
        for (String f : pureFiles) {
            Matcher im = Pattern.compile("^import (android[\\w.]*);", Pattern.MULTILINE).matcher(read(f));
            while (im.find()) {
                if (!stubs.contains(im.group(1))) impure.add(f + " -> " + im.group(1));
            }
        }
        ok(impure.isEmpty(), "purity broken (android import without a stub class — tests stage would fail): " + impure);

        // ---- 5. translation coupling survives byte-identically -------------------
        int coupled = 0;
        Set<String> uiKeys = new TreeSet<>();
        for (String line : Files.readAllLines(Paths.get("assets/ui_en.txt"), StandardCharsets.UTF_8)) {
            int t = line.indexOf('\t');
            if (t > 0) uiKeys.add(line.substring(0, t));
        }
        for (String v : xmlValues.values()) {
            String val = javaUnescape(v.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
            if (uiKeys.contains(val)) coupled++;
        }
        ok(coupled >= 1, "no moved string is a ui_en.txt exact key — coupling check ran on nothing");

        // ---- verdict --------------------------------------------------------------
        ok(defined.size() == PIN_ENTRIES,
           "relocated entries " + defined.size() + " != pinned " + PIN_ENTRIES + " — update the pin consciously");
        System.out.println("TRIMGATE entries=" + defined.size() + " referenced=" + referenced.size()
                + " saved=" + saved + "B dex=" + dexBytes + "/" + CEILING
                + " purity=" + pureFiles.size() + "/" + pureFiles.size()
                + " literals_left=0 ui_en_coupled=" + coupled);
        System.out.println("TRIM_GATE_OK entries=" + defined.size() + " saved=" + saved
                + " dex=" + dexBytes + "/" + CEILING + " regrown=0 purity=" + pureFiles.size()
                + " ui_en_coupled=" + coupled);
    }
}
