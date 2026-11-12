package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * W57 tashkeel-engine battery (ROADMAP-V2 W57) — the TASHKEELGATE verdict
 * source, built exactly like the W56 battery: positives that pin the shape,
 * and the fail-closed negatives that BITE. The real committed asset (assets/
 * tashkeel_index.txt — 939,777 bytes, CRC32-sealed) is loaded HERE,
 * JVM-tested exactly as shipped.
 *
 * Covered here:
 *   • the REAL asset loads with its header claims re-counted (28020 entries,
 *     28196 forms) — a FAIL line carries the refusal tag, the exact surface
 *     ci/w57-negative-proof.sh bites (TSX_CRC / TSX_FORM / TSX_DUP)
 *   • the lookup spine: lexicon singles, the norm fold (مدينة/أخت), the
 *     audited anti-gemination pins (أمم/سبب), AMBIG variants, the R2 sun
 *     partial, ال-composition (moon+sun), the و/ف conjunction, unmapped safe
 *   • the THREE DEGREES: OFF answers empty everywhere (shipped default),
 *     AMBIG offers multi-reading keys only, FULL offers every covered word —
 *     and an uncovered word is never guessed in any degree
 *   • run(Ctx) through the W55 contract + the dispatcher ride (source-labeled
 *     "tashkeel" through Engines.run, the ONE call site)
 *   • strict-load negatives on synthetic RIX2 fixtures: magic, header shape,
 *     made/source/crc fields, CRC value, entries/covered claims, line shape
 *     (exactly one tab), non-normed key (TSX_KEY), form that does not fold
 *     back to its key (TSX_FORM), empty form slot, duplicate key (TSX_DUP) —
 *     each with its EXACT tag, registry/attach fail-closed afterwards
 *   • the pure helpers: tashkeelInto (appends, never displaces the W56
 *     family tail), variantChips (the reading picker), apply (whole-text,
 *     uncovered untouched, OFF identical)
 *   • determinism: a second load answers byte-equal
 *
 * Doubles as the permanent regression suite (tests/run.sh) and the
 * TASHKEELGATE verdict source (ci/tashkeel_gate.sh greps TSXTEST_OK).
 * One derivation.
 */
public class TashkeelTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    // ------------------------------------------------- RIX2 fixture builder

    static byte[] rix2(String made, String source, String crcHex, String entriesClaim,
                       String coveredClaim, String payload) {
        String s = "RIX2\nmade\t" + made + "\nsource\t" + source + "\ncrc32\t" + crcHex
                + "\nentries\t" + entriesClaim + "\ncovered\t" + coveredClaim + "\n---\n" + payload;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String crcOf(String payload) {
        CRC32 c = new CRC32();
        c.update(payload.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }

    static final String PAYLOAD = "كتاب\tكِتَاب\nعلم\tعِلْم عَلَمَ عَلَّمَ\n";

    static byte[] good() {
        return rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf(PAYLOAD), "2", "4", PAYLOAD);
    }

    static String refused(byte[] b) {
        try { TashkeelEngine.load(b); return ""; }
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

    static boolean eq(String[] a, String... want) {
        return Arrays.equals(a, want);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.out.println("TSXTEST_FAILED checks=" + (pass + fail));
            System.exit(1);
        }
        System.out.println("TSXTEST_OK checks=" + (pass + fail) + " failclosed=ON");
    }

    public static void run() {
        TashkeelEngine.detach();
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;
        Engines.set();

        // ---- 1. the REAL asset loads — JVM-tested exactly as shipped ---------
        byte[] real = null;
        try { real = java.nio.file.Files.readAllBytes(Paths.get("assets/tashkeel_index.txt")); }
        catch (Exception ex) { real = null; }
        check("tsx.realasset.present", real != null && real.length > 900000);
        TashkeelEngine e = null;
        String loadErr = "";
        if (real != null) {
            try { e = TashkeelEngine.load(real); }
            catch (IllegalArgumentException ex) { loadErr = ex.getMessage(); }
        }
        check("tsx.realasset.load" + (loadErr.isEmpty() ? "" : " (" + loadErr + ")"), e != null);
        check("tsx.realasset.header", e != null && e.entryCount() == 28020 && e.coveredForms() == 28196);
        if (e == null) {
            System.out.println("TSXTEST_FAILED the committed index refused: " + loadErr);
            return;
        }

        // ---- 2. the lookup spine ----------------------------------------------
        check("tsx.lexicon.single", eq(e.formsOf("كتاب"), "كِتَاب"));
        check("tsx.normfold.tee_marbuta", eq(e.formsOf("مدينة"), "مَدِينَة"));
        check("tsx.normfold.hamza_seat", eq(e.formsOf("أخت"), "أُخْت"));
        String[] amb = e.formsOf("علم");
        check("tsx.ambig.variants", amb.length == 3 && eq(amb, "عِلْم", "عَلَمَ", "عَلَّمَ")
                && e.ambiguous("علم") && !e.ambiguous("كتاب"));
        check("tsx.antigemination.umam", eq(e.formsOf("أمم"), "أُمَم"));
        check("tsx.antigemination.sabab", eq(e.formsOf("سبب"), "سَبَب"));
        check("tsx.partial.sun_r2", eq(e.formsOf("الناس"), "النّاس"));
        check("tsx.comp.moon", eq(e.formsOf("الكتاب"), "الْكِتَاب"));
        check("tsx.comp.sun", eq(e.formsOf("الشمس"), "الشَّمْس"));
        check("tsx.conj.waw", eq(e.formsOf("والكتاب"), "وَالْكِتَاب"));
        String[] dc = e.formsOf("كتاب");
        check("tsx.defensive_copy", dc != e.formsOf("كتاب") && Arrays.equals(dc, e.formsOf("كتاب")));
        check("tsx.unmapped.safe", e.formsOf("zzz").length == 0 && e.formsOf("").length == 0
                && e.formsOf(null).length == 0
                && !e.ambiguous(null));

        // ---- 3. the THREE DEGREES (run through the W55 contract) --------------
        check("tsx.id", e.id().equals("tashkeel"));
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;
        check("tsx.run.off", e.run(new Engine.Ctx(null, null, "كتاب")).length == 0
                && e.run(new Engine.Ctx(null, null, "علم")).length == 0);
        TashkeelEngine.mode = TashkeelEngine.MODE_AMBIG;
        check("tsx.run.ambig_single_silent", e.run(new Engine.Ctx(null, null, "كتاب")).length == 0);
        check("tsx.run.ambig_multi", Arrays.equals(e.run(new Engine.Ctx(null, null, "علم")),
                new String[]{"عِلْم", "عَلَمَ", "عَلَّمَ"}));
        TashkeelEngine.mode = TashkeelEngine.MODE_FULL;
        check("tsx.run.full_single", Arrays.equals(e.run(new Engine.Ctx(null, null, "كتاب")),
                new String[]{"كِتَاب"}));
        check("tsx.run.full_partial", Arrays.equals(e.run(new Engine.Ctx(null, null, "الناس")),
                new String[]{"النّاس"}));
        check("tsx.run.short", e.run(new Engine.Ctx(null, null, "ك")).length == 0);
        check("tsx.run.unmapped", e.run(new Engine.Ctx(null, null, "zzz")).length == 0);
        check("tsx.run.empty_ctx", e.run(new Engine.Ctx(null, null, null)).length == 0);
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;   // back to the shipped default

        // ---- 4. the static mode-gated API (attach/detach) ---------------------
        check("tsx.static.detached", TashkeelEngine.attached() == null
                && TashkeelEngine.variants("كتاب").length == 0
                && TashkeelEngine.variants("علم").length == 0
                && TashkeelEngine.apply("كتاب", null, TashkeelEngine.MODE_FULL).equals("كتاب"));
        TashkeelEngine.attach(e);
        check("tsx.static.attach_wires", TashkeelEngine.attached() == e);
        TashkeelEngine.mode = TashkeelEngine.MODE_FULL;
        check("tsx.static.full", TashkeelEngine.variants("كتاب").length == 1
                && TashkeelEngine.variants("علم").length == 3
                && TashkeelEngine.variants("zzz").length == 0
                && TashkeelEngine.variants(null).length == 0);
        TashkeelEngine.mode = TashkeelEngine.MODE_AMBIG;
        check("tsx.static.ambig", TashkeelEngine.variants("كتاب").length == 0
                && TashkeelEngine.variants("علم").length == 3);
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;
        check("tsx.static.off", TashkeelEngine.variants("كتاب").length == 0
                && TashkeelEngine.variants("علم").length == 0);
        TashkeelEngine.detach();
        check("tsx.static.detach", TashkeelEngine.attached() == null
                && TashkeelEngine.variants("كتاب").length == 0);

        // ---- 5. through the dispatcher (the ONE call site) --------------------
        TashkeelEngine.mode = TashkeelEngine.MODE_FULL;
        TashkeelEngine.attach(e);
        Engines.set(e);
        Engine.Cand[] out = Engines.run(new Engine.Ctx(null, null, "علم"), 10);
        check("tsx.contract.labeled", out.length == 3 && out[0].text.equals("عِلْم")
                && out[0].source.equals("tashkeel"));
        Engines.set();   // back to the shipped-empty registry (isolation)
        check("tsx.contract.empty_registry_silent",
                Engines.run(new Engine.Ctx(null, null, "علم"), 5).length == 0);
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;
        TashkeelEngine.detach();

        // ---- 6. strict-load negatives bite (synthetic RIX2 fixtures) ----------
        TashkeelEngine f = null;
        try { f = TashkeelEngine.load(good()); } catch (IllegalArgumentException ignored) { }
        check("tsx.fixture.load", f != null && f.entryCount() == 2 && f.coveredForms() == 4
                && f.formsOf("علم").length == 3);
        refusedTag("tsx.neg.magic",
                ("RIX1\nmade\t2026-11-12\nsource\tci/make_tashkeel_index.py\ncrc32\t" + crcOf(PAYLOAD)
                        + "\nentries\t2\ncovered\t4\n---\n" + PAYLOAD).getBytes(StandardCharsets.UTF_8),
                "TSX_MAGIC");
        refusedTag("tsx.neg.header_shape",
                ("RIX2\nmade\t2026-11-12\nsource\tci/make_tashkeel_index.py\ncrc32\t" + crcOf(PAYLOAD)
                        + "\nentries\t2\n---\n" + PAYLOAD).getBytes(StandardCharsets.UTF_8),
                "TSX_HEADER");
        refusedTag("tsx.neg.made_format",
                rix2("20261112", "ci/make_tashkeel_index.py", crcOf(PAYLOAD), "2", "4", PAYLOAD),
                "TSX_MADE");
        refusedTag("tsx.neg.source",
                rix2("2026-11-12", "assets/ar.txt", crcOf(PAYLOAD), "2", "4", PAYLOAD), "TSX_SOURCE");
        refusedTag("tsx.neg.crc_field",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", "zzzzzzzz", "2", "4", PAYLOAD),
                "TSX_CRC_FIELD");
        refusedTag("tsx.neg.crc_value",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", "00000000", "2", "4", PAYLOAD),
                "TSX_CRC");
        refusedTag("tsx.neg.entries_claim",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf(PAYLOAD), "3", "4", PAYLOAD),
                "TSX_ENTRIES_COUNT");
        refusedTag("tsx.neg.covered_claim",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf(PAYLOAD), "2", "5", PAYLOAD),
                "TSX_COVERED_COUNT");
        refusedTag("tsx.neg.line_shape",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf("كتاب\tكِتَاب\tمَكْتَب\n"),
                        "1", "2", "كتاب\tكِتَاب\tمَكْتَب\n"), "TSX_LINE");
        refusedTag("tsx.neg.key_not_normed",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf("كِتاب\tكِتَاب\n"),
                        "1", "1", "كِتاب\tكِتَاب\n"), "TSX_KEY");
        refusedTag("tsx.neg.form_does_not_fold",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf("كتاب\tمَكْتَب\n"),
                        "1", "1", "كتاب\tمَكْتَب\n"), "TSX_FORM");
        refusedTag("tsx.neg.empty_form",
                rix2("2026-11-12", "ci/make_tashkeel_index.py", crcOf("كتاب\tكِتَاب  مَكْتَب\n"),
                        "1", "3", "كتاب\tكِتَاب  مَكْتَب\n"), "TSX_FORMS");
        refusedTag("tsx.neg.dup_key",
                rix2("2026-11-12", "ci/make_tashkeel_index.py",
                        crcOf("كتاب\tكِتَاب\nاخت\tأُخْت\nكتاب\tكِتَاب\n"),
                        "3", "3", "كتاب\tكِتَاب\nاخت\tأُخْت\nكتاب\tكِتَاب\n"), "TSX_DUP");
        check("tsx.failclosed.registry_empty", Engines.ids().length == 0);
        check("tsx.failclosed.no_attach", TashkeelEngine.attached() == null
                && TashkeelEngine.variants("الكتاب").length == 0);
        TashkeelEngine e2 = TashkeelEngine.load(real);
        check("tsx.determinism", e2.entryCount() == e.entryCount()
                && Arrays.equals(e2.formsOf("علم"), e.formsOf("علم")));

        // ---- 7. the pure helpers ----------------------------------------------
        List<String> strip = new ArrayList<>(Arrays.asList("كتاب", "كِتَاب"));
        TashkeelEngine.tashkeelInto(strip, new Engine.Cand[]{ new Engine.Cand("عِلْم", "tashkeel") }, 2);
        check("tsx.ride.full_strip_untouched", strip.size() == 2 && strip.get(1).equals("كِتَاب"));
        List<String> s0 = new ArrayList<>(Arrays.asList("كتاب"));
        TashkeelEngine.tashkeelInto(s0, new Engine.Cand[]{ new Engine.Cand("كِتَاب", "tashkeel") }, 2);
        check("tsx.ride.appends", s0.size() == 2 && s0.get(1).equals("كِتَاب"));
        TashkeelEngine.tashkeelInto(s0, new Engine.Cand[]{ new Engine.Cand("عَلَمَ", "tashkeel") }, 2);
        check("tsx.ride.skip_shown", s0.size() == 2 && s0.get(1).equals("كِتَاب"));
        TashkeelEngine.tashkeelInto(s0, new Engine.Cand[]{
                new Engine.Cand(null, "tashkeel"), new Engine.Cand("  ", "tashkeel"),
                new Engine.Cand("عَلَّمَ", "tashkeel") }, 3);
        check("tsx.ride.skips_holes", s0.size() == 3 && s0.get(2).equals("عَلَّمَ"));
        TashkeelEngine.tashkeelInto(null, new Engine.Cand[]{ new Engine.Cand("كِتَاب", "tashkeel") }, 3);
        TashkeelEngine.tashkeelInto(s0, null, 3);
        TashkeelEngine.tashkeelInto(s0, new Engine.Cand[]{ new Engine.Cand("كِتَاب", "tashkeel") }, 0);
        check("tsx.ride.null_safe", s0.size() == 3);
        List<String> chips = TashkeelEngine.variantChips("علم",
                new String[]{"عِلْم", "عَلَمَ", "عَلَّمَ"});
        check("tsx.chips.shape", chips.size() == 4 && chips.get(0).equals("✕ علم • قراءات التشكيل")
                && chips.get(1).equals("عِلْم"));
        String[] big = new String[12];
        Arrays.fill(big, "مَ");
        check("tsx.chips.cap8", TashkeelEngine.variantChips("علم", big).size() == 9);
        check("tsx.chips.null_forms", TashkeelEngine.variantChips("علم", null).size() == 1);
        // apply: covered replaced, uncovered untouched, OFF identical, punct kept
        TashkeelEngine.mode = TashkeelEngine.MODE_FULL;
        check("tsx.apply.full",
                TashkeelEngine.apply("الكتاب على طاولة باردة.", e, TashkeelEngine.MODE_FULL)
                        .equals("الْكِتَاب عَلَى طاولة باردة."));
        TashkeelEngine.mode = TashkeelEngine.MODE_AMBIG;
        check("tsx.apply.ambig",
                TashkeelEngine.apply("الكتاب علم.", e, TashkeelEngine.MODE_AMBIG)
                        .equals("الكتاب عِلْم."));
        check("tsx.apply.already_vocalized",
                TashkeelEngine.apply("مُحَمَّد جَاءَ.", e, TashkeelEngine.MODE_FULL)
                        .equals("مُحَمَّد جَاءَ."));
        check("tsx.apply.digits_latin",
                TashkeelEngine.apply("كتاب 12 abc", e, TashkeelEngine.MODE_FULL)
                        .equals("كِتَاب 12 abc"));
        TashkeelEngine.mode = TashkeelEngine.MODE_OFF;
        check("tsx.apply.off_identical",
                TashkeelEngine.apply("الكتاب علم.", e, TashkeelEngine.MODE_OFF).equals("الكتاب علم."));
        check("tsx.apply.null_text", TashkeelEngine.apply(null, e, TashkeelEngine.MODE_FULL) == null);
        Engines.set();   // leave the registry exactly as the service ships it
    }
}
