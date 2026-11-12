package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * W56 root-engine battery (ROADMAP-V2 W56) — the ROOTGATE verdict source,
 * built exactly like the W55 harness: positives that pin the shape, and the
 * fail-closed negatives that BITE. The real committed asset (assets/
 * root_index.txt — 1,079,858 bytes, CRC32-sealed) is loaded HERE, JVM-tested
 * exactly as shipped, the same discipline as the W55 perf pin.
 *
 * Covered here:
 *   • the REAL asset loads with its header claims re-counted (3667 roots,
 *     84809 covered) — a FAIL line carries the refusal tag, which is the
 *     exact surface ci/w56-negative-proof.sh bites (RIX_CRC / RIX_DUP)
 *   • the lookup spine: norm-key binary search, same root for the attested
 *     family (كاتب/كتاب/مكتوب/المكتوب — all gold-set pairs), unmapped → -1/null
 *   • run(Ctx) through the W55 contract: family branches minus the composing
 *     word, corpus-rank order, capped at 6; short/unmapped/empty → empty
 *   • the dispatcher ride: source-labeled "root" candidates through Engines.run
 *   • strict-load negatives on synthetic RIX1 fixtures: magic, header shape,
 *     made/source/crc fields, CRC value, roots/covered claims, line shape,
 *     empty members, one-word-under-two-roots (RIX_DUP) — each with its EXACT
 *     tag, and the registry/attach state still fail-closed empty afterwards
 *   • the strip helpers (pure): familyInto append/replace-tail/skip-shown/
 *     skip-holes/null-safety; treeChips shape + the 10-member cap
 *   • the Correction2 root signal (docs/ROOT.md §4): detached = byte-identical
 *     baseline (re-scored equality), attached = the exact documented +400
 *     delta for the root-preserving candidate, and the zero-annoyance laws
 *     intact throughout
 *
 * Doubles as the permanent regression suite (tests/run.sh) and the ROOTGATE
 * verdict source (ci/root_gate.sh greps ROOTTEST_OK). One derivation.
 */
public class RootEngineTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    // ------------------------------------------------- RIX1 fixture builder

    static byte[] rix(String made, String source, String crcHex, String rootsClaim,
                      String coveredClaim, String payload) {
        String s = "RIX1\nmade\t" + made + "\nsource\t" + source + "\ncrc32\t" + crcHex
                + "\nroots\t" + rootsClaim + "\ncovered\t" + coveredClaim + "\n---\n" + payload;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String crcOf(String payload) {
        CRC32 c = new CRC32();
        c.update(payload.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }

    static final String PAYLOAD = "كتب\tكتب كاتب مكتوب المكتبة\nقول\tقال مقول\n";

    static byte[] good() {
        return rix("2026-11-12", "assets/ar.txt", crcOf(PAYLOAD), "2", "6", PAYLOAD);
    }

    static String refused(byte[] b) {
        try { RootEngine.load(b); return ""; }
        catch (IllegalArgumentException ex) { return ex.getMessage(); }
    }

    static void refusedTag(String name, byte[] b, String tag) {
        String m = refused(b);
        check(name + " [" + tag + "]", m.contains(tag));
    }

    static boolean contains(String[] a, String v) {
        for (String s : a) if (s.equals(v)) return true;
        return false;
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.out.println("ROOTTEST_FAILED checks=" + (pass + fail));
            System.exit(1);
        }
        System.out.println("ROOTTEST_OK checks=" + (pass + fail) + " failclosed=ON");
    }

    public static void run() {
        RootEngine.detach();
        Engines.set();

        // ---- 1. the REAL asset loads — JVM-tested exactly as shipped ---------
        byte[] real = null;
        try { real = Files.readAllBytes(Paths.get("assets/root_index.txt")); }
        catch (Exception ex) { real = null; }
        check("root.realasset.present", real != null && real.length > 1000000);
        RootEngine e = null;
        String loadErr = "";
        if (real != null) {
            try { e = RootEngine.load(real); }
            catch (IllegalArgumentException ex) { loadErr = ex.getMessage(); }
        }
        // the FAIL line carries the refusal tag — the W56 negative proof bites it
        check("root.realasset.load" + (loadErr.isEmpty() ? "" : " (" + loadErr + ")"), e != null);
        check("root.realasset.header", e != null && e.rootCount() == 3667 && e.coveredWords() == 84809);
        if (e == null) {
            System.out.println("ROOTTEST_FAILED the committed index refused: " + loadErr);
            return;
        }

        // ---- 2. the lookup spine ----------------------------------------------
        int rk = e.rootOf("كتب");
        check("root.lookup.same_root", rk >= 0
                && rk == e.rootOf("كاتب") && rk == e.rootOf("كتاب")
                && rk == e.rootOf("مكتوب") && rk == e.rootOf("المكتوب"));
        check("root.value.norm", "كتب".equals(e.rootValue("الكتاب")));
        String[] fam = e.familyTree("كتب");
        check("root.family.ranked", fam != null && fam.length >= 4 && contains(fam, "كاتب"));
        check("root.family.defensive_copy", fam != e.familyTree("كتب")
                && Arrays.equals(fam, e.familyTree("كتب")));
        check("root.unmapped.safe", e.rootOf("zzz") == -1 && e.rootOf("") == -1 && e.rootOf(null) == -1
                && e.rootValue("zzz") == null && e.familyTree("zzz") == null);

        // ---- 3. the W55 contract: run(Ctx) family branches ---------------------
        check("root.id", e.id().equals("root"));
        String[] br = e.run(new Engine.Ctx(null, null, "كتب"));
        List<String> want = new ArrayList<>();
        for (String m : fam) {
            if (!Dictionary.norm(m).equals("كتب") && want.size() < 6) want.add(m);
        }
        check("root.run.excludes_self_capped", br.length >= 1 && br.length <= 6
                && Arrays.equals(br, want.toArray(new String[0])));
        check("root.run.short_composing", e.run(new Engine.Ctx(null, null, "كت")).length == 0);
        check("root.run.unmapped", e.run(new Engine.Ctx(null, null, "zzz")).length == 0);
        check("root.run.empty_ctx", e.run(new Engine.Ctx(null, null, null)).length == 0);

        // ---- 4. through the dispatcher (the ONE call site) ---------------------
        Engines.set(e);
        Engine.Cand[] out = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
        check("root.contract.labeled", out.length == br.length && out.length > 0
                && out[0].text.equals(br[0]) && out[0].source.equals("root"));
        Engines.set();   // back to the shipped-empty registry (isolation)
        check("root.contract.empty_registry_silent",
                Engines.run(new Engine.Ctx(null, null, "كتب"), 5).length == 0);

        // ---- 5. strict-load negatives bite (synthetic RIX1 fixtures) -----------
        RootEngine f = null;
        try { f = RootEngine.load(good()); } catch (IllegalArgumentException ignored) { }
        check("root.fixture.load", f != null && f.rootCount() == 2 && f.coveredWords() == 6
                && f.rootOf("كاتب") >= 0);
        refusedTag("root.neg.magic",
                ("RIX2\nmade\t2026-11-12\nsource\tassets/ar.txt\ncrc32\t" + crcOf(PAYLOAD)
                        + "\nroots\t2\ncovered\t6\n---\n" + PAYLOAD).getBytes(StandardCharsets.UTF_8),
                "RIX_MAGIC");
        refusedTag("root.neg.header_shape",
                ("RIX1\nmade\t2026-11-12\nsource\tassets/ar.txt\ncrc32\t" + crcOf(PAYLOAD)
                        + "\nroots\t2\n---\n" + PAYLOAD).getBytes(StandardCharsets.UTF_8),
                "RIX_HEADER");
        refusedTag("root.neg.made_format",
                rix("20261112", "assets/ar.txt", crcOf(PAYLOAD), "2", "6", PAYLOAD), "RIX_MADE");
        refusedTag("root.neg.source",
                rix("2026-11-12", "assets/en.txt", crcOf(PAYLOAD), "2", "6", PAYLOAD), "RIX_SOURCE");
        refusedTag("root.neg.crc_field",
                rix("2026-11-12", "assets/ar.txt", "zzzzzzzz", "2", "6", PAYLOAD), "RIX_CRC_FIELD");
        refusedTag("root.neg.crc_value",
                rix("2026-11-12", "assets/ar.txt", "00000000", "2", "6", PAYLOAD), "RIX_CRC");
        refusedTag("root.neg.roots_claim",
                rix("2026-11-12", "assets/ar.txt", crcOf(PAYLOAD), "3", "6", PAYLOAD), "RIX_ROOTS_COUNT");
        refusedTag("root.neg.covered_claim",
                rix("2026-11-12", "assets/ar.txt", crcOf(PAYLOAD), "2", "7", PAYLOAD), "RIX_COVERED_COUNT");
        refusedTag("root.neg.line_shape",
                rix("2026-11-12", "assets/ar.txt", crcOf("كتبات\tكتب\n"), "1", "1", "كتبات\tكتب\n"),
                "RIX_LINE");
        refusedTag("root.neg.empty_member",
                rix("2026-11-12", "assets/ar.txt", crcOf("كتب\tكتب  مكتوب\n"), "1", "2",
                        "كتب\tكتب  مكتوب\n"), "RIX_MEMBERS");
        // the one-root-per-norm-key law: the same member under two roots is a
        // format breach — never silently resolved (the derivation claims it too)
        refusedTag("root.neg.dup_key",
                rix("2026-11-12", "assets/ar.txt", crcOf("كتب\tمكتوب\nقول\tمكتوب\n"), "2", "2",
                        "كتب\tمكتوب\nقول\tمكتوب\n"), "RIX_DUP");
        check("root.failclosed.registry_empty", Engines.ids().length == 0);
        check("root.failclosed.no_attach", RootEngine.attached() == null
                && RootEngine.rootOfStatic("الكتاب") == -1);
        RootEngine e2 = RootEngine.load(real);
        check("root.determinism", e2.rootCount() == e.rootCount()
                && Arrays.equals(e2.familyTree("كتب"), e.familyTree("كتب")));

        // ---- 6. the strip helpers (pure) ---------------------------------------
        List<String> strip = new ArrayList<>(Arrays.asList("كتاب", "كتبات"));
        RootEngine.familyInto(strip, new Engine.Cand[]{ new Engine.Cand("كاتب", "root") }, 3);
        check("strip.family.appends", strip.size() == 3 && strip.get(2).equals("كاتب"));
        RootEngine.familyInto(strip, new Engine.Cand[]{ new Engine.Cand("مكتوب", "root") }, 3);
        check("strip.family.replaces_tail", strip.size() == 3 && strip.get(2).equals("مكتوب"));
        RootEngine.familyInto(strip, new Engine.Cand[]{
                new Engine.Cand("مكتوب", "root"), new Engine.Cand("كاتب", "root") }, 3);
        check("strip.family.skips_shown", strip.get(2).equals("كاتب"));
        RootEngine.familyInto(strip, new Engine.Cand[]{
                new Engine.Cand(null, "root"), new Engine.Cand("  ", "root"),
                new Engine.Cand("مكتوبة", "root") }, 3);
        check("strip.family.skips_holes", strip.get(2).equals("مكتوبة"));
        List<String> s2 = new ArrayList<>(Arrays.asList("أ", "ب"));
        RootEngine.familyInto(s2, null, 3);
        RootEngine.familyInto(s2, new Engine.Cand[]{ new Engine.Cand("كاتب", "root") }, 0);
        RootEngine.familyInto(null, new Engine.Cand[]{ new Engine.Cand("كاتب", "root") }, 3);
        check("strip.family.null_safe", s2.size() == 2);
        List<String> chips = RootEngine.treeChips("كتب",
                new String[]{"كاتب", "مكتوب", "المكتبة"});
        check("strip.treechips.shape", chips.size() == 4
                && chips.get(0).equals("✕ كتب • شجرة العائلة") && chips.get(1).equals("كاتب"));
        String[] big = new String[15];
        Arrays.fill(big, "م");
        check("strip.treechips.cap10", RootEngine.treeChips("كتب", big).size() == 11);
        check("strip.treechips.null_family", RootEngine.treeChips("كتب", null).size() == 1);

        // ---- 7. the Correction2 root signal (docs/ROOT.md §4) ------------------
        File dir = new File("build/test-rootengine");
        dir.mkdirs();
        Dictionary dict = new Dictionary(new FakeContext(dir), "ar");
        long t0 = System.currentTimeMillis();
        while (!dict.isLoaded() && System.currentTimeMillis() - t0 < 20000) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
        }
        check("root.corr.dict_loaded", dict.isLoaded());
        // preconditions: the three words ride the real index with the documented roots
        check("root.corr.precondition", e.rootOf("مكتب") >= 0
                && e.rootOf("مكتب") == e.rootOf("كتاب")
                && e.rootOf("مدرس") != e.rootOf("مكتب"));
        RootEngine.detach();
        int d0a = Correction2.score("مكتب", "كتاب", "", "", dict);
        int d0b = Correction2.score("مكتب", "مدرس", "", "", dict);
        RootEngine.attach(e);
        check("root.corr.attach_wires", Correction2.rootSignal == e && RootEngine.attached() == e);
        int d1a = Correction2.score("مكتب", "كتاب", "", "", dict);
        int d1b = Correction2.score("مكتب", "مدرس", "", "", dict);
        // the exact documented weight: the root-preserving candidate gains 400
        check("root.bonus.prefers", (d1a - d1b) - (d0a - d0b) == 400);
        RootEngine.detach();
        // the detach law: detached scores return EXACTLY to the baseline values
        check("root.neutral.detached", Correction2.rootSignal == null
                && Correction2.score("مكتب", "كتاب", "", "", dict) == d0a
                && Correction2.score("مكتب", "مدرس", "", "", dict) == d0b);
        Set<String> none = new HashSet<>();
        check("root.corr.zero_annoyance_intact", "مشكلة".equals(Correction2.fix("مشكله", "", dict, none))
                && Correction2.fix("أرسل", "", dict, none) == null);

        // ---- 8. the static correction lookup follows attach/detach -------------
        RootEngine.attach(e);
        check("root.static.lookup_live", RootEngine.rootOfStatic("الكتاب") == e.rootOf("الكتاب")
                && RootEngine.rootOfStatic("zzz") == -1);
        RootEngine.detach();
        check("root.static.lookup_dead", RootEngine.rootOfStatic("الكتاب") == -1
                && RootEngine.attached() == null);
        Engines.set();   // leave the registry exactly as the service ships it
    }
}
