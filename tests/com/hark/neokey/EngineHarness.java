package com.hark.neokey;

/**
 * W55 engine-harness battery (ROADMAP-V2 W55) — the unified engine contract
 * tested WITH a test engine, exactly as the exit criterion demands: positives
 * that pin the contract's shape, and the fail-closed negatives that BITE.
 *
 * The reference engine (محرك تجريبي) `FamilyProbe` rehearses the W56 shape:
 * a composing word in → its suffix family out (الكتب → كتبات/كتبة/كتبون),
 * deterministic, pure, empty context → empty output. It is tests-only — it
 * never rides the dex.
 *
 * Covered here:
 *   • Ctx/Cand null-normalization at construction (nulls never enter an engine)
 *   • Cand equals/hashCode contract + the privacy toString (lengths, never words)
 *   • the empty registry ships fail-closed silent: run() → empty, never null
 *   • source labels: every candidate carries its engine id (the W60 badge primitive)
 *   • merge order = registration order, each engine best-first; dedupe first-source-wins
 *   • the cap: limit honored (2/0), MAX_PER_ENGINE truncation is shape not failure
 *   • slot sanitation: null/blank slots dropped AND counted, outer trim applied
 *   • strict registration: null engine / blank id / duplicate id refused, registry untouched
 *   • fail-closed negatives: thrower (Exception), error-thrower (AssertionError),
 *     null output — each contained to an EXACT counted failure, the others still flow
 *   • the failure counter is EXACT across the whole battery (no hidden failures)
 *   • determinism: same ctx twice → identical output
 *   • isolation: a fully green end-to-end run AFTER every negative ran
 *
 * Doubles as the permanent regression suite (tests/run.sh) and the HARNESSGATE
 * verdict source (ci/harness_gate.sh greps HARNESSGATE_OK). One derivation.
 */
public class EngineHarness {
    static int pass, fail;
    static long expectFailures;   // the exact expected counter across the battery

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    // ---- the reference engine (محرك تجريبي): the W56 root-family shape rehearsal
    static final class FamilyProbe implements Engine {
        public String id() { return "probe"; }
        public String[] run(Engine.Ctx c) {
            String base = !c.composing.isEmpty() ? c.composing : c.prev1;
            if (base.isEmpty()) return new String[0];
            return new String[] { base + "ات", base + "ة", base + "ون" };
        }
    }

    static final class Second implements Engine {
        public String id() { return "second"; }
        public String[] run(Engine.Ctx c) { return new String[] { "ثانٍ", "كاتب" }; }
    }

    static final class DupeFamily implements Engine {
        public String id() { return "dupe"; }
        public String[] run(Engine.Ctx c) { return new String[] { "كتبات", "بعيد" }; }
    }

    // ---- the fail-closed negatives: every misbehavior an engine can commit
    static final class Thrower implements Engine {
        public String id() { return "thrower"; }
        public String[] run(Engine.Ctx c) { throw new IllegalStateException("broken on purpose"); }
    }

    static final class ErrorThrower implements Engine {
        public String id() { return "error"; }
        public String[] run(Engine.Ctx c) { throw new AssertionError("error on purpose"); }
    }

    static final class Nuller implements Engine {
        public String id() { return "nuller"; }
        public String[] run(Engine.Ctx c) { return null; }
    }

    static final class Holes implements Engine {
        public String id() { return "holes"; }
        public String[] run(Engine.Ctx c) { return new String[] { "سليم", null, "   ", "فارغ" }; }
    }

    static final class Flood implements Engine {
        public String id() { return "flood"; }
        public String[] run(Engine.Ctx c) {
            String[] r = new String[20];
            for (int i = 0; i < 20; i++) r[i] = "فيض" + i;
            return r;
        }
    }

    /** Runs a negative scenario and asserts the EXACT failure delta it must cost. */
    static void neg(String name, long expectDelta, Runnable scenario) {
        long before = Engines.failures();
        scenario.run();
        long delta = Engines.failures() - before;
        expectFailures += expectDelta;
        check(name + " (delta=" + delta + " expect=" + expectDelta + ")", delta == expectDelta);
    }

    static String[] texts(Engine.Cand[] c) {
        String[] t = new String[c.length];
        for (int i = 0; i < c.length; i++) t[i] = c[i].text;
        return t;
    }

    static String[] sources(Engine.Cand[] c) {
        String[] t = new String[c.length];
        for (int i = 0; i < c.length; i++) t[i] = c[i].source;
        return t;
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.out.println("HARNESSGATE_FAILED checks=" + (pass + fail));
            System.exit(1);
        }
        System.out.println("HARNESSGATE_OK checks=" + (pass + fail)
                + " engines=" + Engines.ids().length
                + " failclosed=" + expectFailures);
    }

    public static void run() {
        // ---- 1. the input contract: nulls never enter an engine ----------------
        Engine.Ctx c0 = new Engine.Ctx(null, null, null);
        check("ctx.null_safe.prev2", c0.prev2.isEmpty() && c0.prev1.isEmpty() && c0.composing.isEmpty());
        Engine.Ctx c1 = new Engine.Ctx(null, "في", "الكتب");
        check("ctx.null_safe.prev2_only", c1.prev2.isEmpty() && c1.prev1.equals("في") && c1.composing.equals("الكتب"));
        check("ctx.toString.privacy", c1.toString().equals("ctx(0,2,5)"));   // lengths, never the words

        // ---- 2. the output contract: source-labeled candidates -----------------
        Engine.Cand d0 = new Engine.Cand(null, null);
        check("cand.null_safe", d0.text.isEmpty() && d0.source.isEmpty());
        Engine.Cand d1 = new Engine.Cand("كتاب", "probe");
        Engine.Cand d2 = new Engine.Cand("كتاب", "probe");
        Engine.Cand d3 = new Engine.Cand("كتاب", "dupe");
        check("cand.equals_hash", d1.equals(d2) && d1.hashCode() == d2.hashCode()
                && !d1.equals(d3) && !d1.equals("كتاب"));

        // ---- 3. the empty registry ships fail-closed silent ---------------------
        Engines.set();
        check("empty.registry.run_not_null", Engines.run(c1, 5) != null && Engines.run(c1, 5).length == 0);
        check("empty.registry.ids_empty", Engines.ids().length == 0);

        // ---- 4. the reference engine rides the contract end-to-end --------------
        Engines.set(new FamilyProbe());
        Engine.Cand[] fam = Engines.run(new Engine.Ctx(null, null, "كتب"), 5);
        check("probe.family.labeled", fam.length == 3
                && fam[0].text.equals("كتبات") && fam[1].text.equals("كتبة") && fam[2].text.equals("كتبون")
                && sources(fam)[0].equals("probe") && sources(fam)[1].equals("probe") && sources(fam)[2].equals("probe"));
        check("probe.empty_context.empty", Engines.run(new Engine.Ctx(null, null, null), 5).length == 0);
        check("probe.falls_back_to_prev1", texts(Engines.run(new Engine.Ctx(null, "كتب", null), 5)).length == 3);
        check("run.null_ctx_safe", Engines.run(null, 5) != null);   // the dispatcher normalizes, never NPEs

        // ---- 5. merge: registration order, dedupe first-source-wins -------------
        Engines.set(new FamilyProbe(), new Second());
        Engine.Cand[] merged = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
        check("merge.order.registration", merged.length == 5
                && merged[0].text.equals("كتبات") && merged[3].text.equals("ثانٍ") && merged[4].text.equals("كاتب")
                && sources(merged)[3].equals("second"));
        Engines.set(new FamilyProbe(), new DupeFamily());
        Engine.Cand[] dedup = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
        check("merge.dedupe.first_source_wins", dedup.length == 4
                && dedup[0].text.equals("كتبات") && dedup[0].source.equals("probe")
                && dedup[3].text.equals("بعيد") && dedup[3].source.equals("dupe"));

        // ---- 6. the cap and the shape -------------------------------------------
        check("cap.limit.two", Engines.run(new Engine.Ctx(null, null, "كتب"), 2).length == 2);
        check("cap.limit.zero", Engines.run(new Engine.Ctx(null, null, "كتب"), 0).length == 0);
        Engines.set(new Flood());
        check("shape.flood.truncated", Engines.run(new Engine.Ctx(null, null, "كتب"), 99).length == Engines.MAX_PER_ENGINE);
        Engines.set(new Engine() {
            public String id() { return "trim"; }
            public String[] run(Engine.Ctx c) { return new String[] { "  كتب  " }; }
        });
        Engine.Cand[] tr = Engines.run(new Engine.Ctx(null, null, "س"), 5);
        check("shape.slot.trimmed", tr.length == 1 && tr[0].text.equals("كتب"));

        // ---- 7. strict registration: the registry is never half-built -----------
        Engines.set(new FamilyProbe(), new Second());
        boolean dupRefused = false;
        try { Engines.set(new FamilyProbe(), new FamilyProbe()); }
        catch (IllegalArgumentException e) { dupRefused = true; }
        check("set.duplicate_id.refused", dupRefused
                && Engines.ids().length == 2 && Engines.ids()[0].equals("probe") && Engines.ids()[1].equals("second"));
        boolean nullRefused = false;
        try { Engines.set(new FamilyProbe(), null); }
        catch (IllegalArgumentException e) { nullRefused = true; }
        check("set.null_element.refused", nullRefused && Engines.ids().length == 2);
        boolean blankRefused = false;
        try { Engines.set(new Engine() { public String id() { return "  "; } public String[] run(Engine.Ctx c) { return new String[0]; } }); }
        catch (IllegalArgumentException e) { blankRefused = true; }
        check("set.blank_id.refused", blankRefused && Engines.ids().length == 2);
        String[] before = Engines.ids();
        before[0] = "tampered";
        check("ids.defensive_copy", Engines.ids()[0].equals("probe"));

        // ---- 8. the fail-closed negatives bite — each EXACTLY counted -----------
        neg("neg.thrower.contained", 1, () -> {
            Engines.set(new Thrower(), new FamilyProbe());
            Engine.Cand[] r = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
            check("neg.thrower.others_still_flow", r.length == 3 && r[0].source.equals("probe"));
        });
        neg("neg.error.contained", 1, () -> {
            Engines.set(new ErrorThrower(), new FamilyProbe());
            Engine.Cand[] r = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
            check("neg.error.others_still_flow", r.length == 3);
        });
        neg("neg.null_output.contained", 1, () -> {
            Engines.set(new Nuller(), new FamilyProbe());
            Engine.Cand[] r = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
            check("neg.null.others_still_flow", r.length == 3);
        });
        neg("neg.holes.dropped_counted", 2, () -> {
            Engines.set(new Holes());
            Engine.Cand[] r = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
            check("neg.holes.clean_slots_flow", r.length == 2
                    && r[0].text.equals("سليم") && r[1].text.equals("فارغ") && r[0].source.equals("holes"));
        });
        neg("neg.holes.then_others_flow", 2, () -> {
            Engines.set(new Holes(), new FamilyProbe());
            Engine.Cand[] r = Engines.run(new Engine.Ctx(null, null, "كتب"), 10);
            check("neg.holes.mixed_flow", r.length == 5 && r[2].source.equals("probe"));
        });

        // ---- 9. the counter is EXACT — no hidden failures anywhere --------------
        check("failures.counter.exact", Engines.failures() == expectFailures);

        // ---- 10. determinism + isolation after every negative -------------------
        Engines.set(new FamilyProbe(), new Second());
        Engine.Cand[] a1 = Engines.run(new Engine.Ctx("ذهب", "إلى", "المدرسة"), 10);
        Engine.Cand[] a2 = Engines.run(new Engine.Ctx("ذهب", "إلى", "المدرسة"), 10);
        boolean same = a1.length == a2.length;
        for (int i = 0; same && i < a1.length; i++) same = a1[i].equals(a2[i]);
        check("determinism.same_ctx.same_output", same && a1.length == 5);
        check("isolation.end_to_end_green", a1[0].source.equals("probe") && a1[3].source.equals("second"));
        check("failures.counter.exact_end", Engines.failures() == expectFailures);
    }
}
