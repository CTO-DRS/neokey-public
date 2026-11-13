package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * W58 grammar-engine battery (ROADMAP-V2 W58) — the NUDGEGATE verdict source,
 * built exactly like the W56/W57 batteries: positives that pin the shape, and
 * the fail-closed negatives that BITE. The real committed asset (assets/
 * grammar_rules.txt — GRX1, CRC32-sealed, 28 rules / 48 pairs / 56 examples)
 * is loaded HERE, JVM-tested exactly as shipped.
 *
 * Covered here:
 *   • the REAL asset loads with its header claims re-counted and the SIDES
 *     LAW re-verified at load (every G example zero nudges, every B exactly
 *     one with its own rule) — a FAIL line carries the refusal tag, the exact
 *     surface ci/w58-negative-proof.sh bites (GRX_CRC / GRX_SIDE_B / GRX_DUP)
 *   • every rule's two examples re-scanned through the SHIPPED matcher
 *     independently of the load-time seal: the good example passes (zero
 *     nudges from ANY rule), the wrong one bites exactly once (id + fix)
 *   • the THREE MODES: OFF answers empty everywhere (shipped default), SOFT
 *     offers the unambiguous S rules only (the negation pairs stay silent),
 *     STRICT adds the pairs — and the raw-matching law: المدرسة (correct)
 *     never bites its own rule
 *   • run(Ctx) through the W55 contract + the dispatcher ride (source-labeled
 *     "grammar" through Engines.run, the ONE call site; single-word only —
 *     the pairs are scan-time educational)
 *   • the pure helpers: nudgeInto appends and never displaces the family
 *     tail, applyOne splices only on the exact span (a stale nudge edits
 *     nothing), incognito refuses the scan
 *   • the annoyance law: the clean reference testset (assets/
 *     testset_grammar.txt — 250+ words carrying the CORRECT form of every
 *     pattern and the near-miss traps incl. the name علي) scans to ZERO
 *     nudges in STRICT — the measured rate 0.0 is the bound's witness
 *   • strict-load negatives on synthetic GRX1 fixtures: magic, header shape,
 *     made/source/crc fields, CRC value, rules/examples/pairs claims, row
 *     shape, duplicate patterns, wrong-side examples, unknown/duplicate
 *     example ids, pair shape — every one refuses the WHOLE asset
 */
public class GrammarNudgeTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    // ---- the committed asset rows, parsed by the battery itself ----
    static String[] rId, rPat, rSug, rFam, rExplain, rSev, rKind;
    static String[] eId, eSide, eText;
    static String[] pId, pWrong, pFix;

    private static void parseAsset() throws Exception {
        String s = new String(java.nio.file.Files.readAllBytes(
                Paths.get("assets/grammar_rules.txt")), StandardCharsets.UTF_8);
        String payload = s.substring(s.indexOf("\n---\n") + 5);
        List<String> ri = new ArrayList<>(), pa = new ArrayList<>(), su = new ArrayList<>(),
                fm = new ArrayList<>(), ex = new ArrayList<>(), sv = new ArrayList<>(),
                kd = new ArrayList<>(), ei = new ArrayList<>(), es = new ArrayList<>(),
                et = new ArrayList<>(), pi = new ArrayList<>(), pw = new ArrayList<>(),
                px = new ArrayList<>();
        for (String L : payload.split("\n")) {
            if (L.isEmpty()) continue;
            String[] f = L.split("\t", -1);
            if (f[0].equals("R")) {
                kd.add(f[4]); fm.add(f[2]); sv.add(f[3]);
                ri.add(f[1]); pa.add(f[5]); su.add(f[6]); ex.add(f[7]);
            } else if (f[0].equals("E")) {
                ei.add(f[1]); es.add(f[2]); et.add(f[3]);
            } else if (f[0].equals("P")) {
                pi.add(f[1]); pw.add(f[2]); px.add(f[3]);
            }
        }
        rId = ri.toArray(new String[0]); rPat = pa.toArray(new String[0]);
        rSug = su.toArray(new String[0]); rFam = fm.toArray(new String[0]);
        rExplain = ex.toArray(new String[0]); rSev = sv.toArray(new String[0]);
        rKind = kd.toArray(new String[0]);
        eId = ei.toArray(new String[0]); eSide = es.toArray(new String[0]);
        eText = et.toArray(new String[0]);
        pId = pi.toArray(new String[0]); pWrong = pw.toArray(new String[0]);
        pFix = px.toArray(new String[0]);
    }

    /** The expected correction of a matched pair (from the P rows). */
    private static String pairFixOf(String wrong) {
        for (int i = 0; i < pWrong.length; i++) if (pWrong[i].equals(wrong)) return pFix[i];
        return "";
    }

    /** The rule's index (into the R-row arrays) by its id. */
    private static int ruleIndexOf(String id) {
        for (int i = 0; i < rId.length; i++) if (rId[i].equals(id)) return i;
        return -1;
    }

    public static void run() {
        GrammarNudge g = null;
        System.out.println("== GrammarNudge:strict load ==");
        try {
            g = GrammarNudge.load(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("assets/grammar_rules.txt")));
        } catch (Throwable t) {
            check("the real GRX1 asset loads (NUDGEENGINE " + t.getMessage() + ")", false);
        }
        check("the real GRX1 asset loads", g != null);
        if (g == null) return;
        try { parseAsset(); } catch (Exception e) { check("asset rows parse", false); }
        check("header claims re-counted (28 rules)", g.ruleCount() == 28);
        check("header claims re-counted (48 pairs)", g.pairCount() == 48);
        check("header claims re-counted (56 examples)", g.exampleCount() == 56);
        check("the battery sees the same rows", rId.length == 28 && eId.length == 56);

        System.out.println("== GrammarNudge:every rule, both sides ==");
        // the shipped matcher re-verifies every example, independently of the
        // load-time seal: the good example passes, the wrong one bites once
        for (int i = 0; i < eId.length; i++) {
            int ri = ruleIndexOf(eId[i]);
            List<GrammarNudge.Nudge> ng = g.scanCore(eText[i]);
            boolean gOk = eSide[i].equals("G") && ng.isEmpty();
            boolean bOk = false;
            if (eSide[i].equals("B") && ng.size() == 1 && ng.get(0).id.equals(eId[i])) {
                // word rules carry their fix in the R row; pair rules in their P row
                bOk = rKind[ri].equals("W") ? ng.get(0).fix.equals(rSug[ri])
                        : ng.get(0).fix.equals(pairFixOf(ng.get(0).wrong));
            }
            check("rule " + eId[i] + " (" + rPat[ri] + ") " + eSide[i] + "-side example", gOk || bOk);
        }
        boolean metaOk = true;
        for (int i = 0; i < rId.length; i++) {
            if (rId[i].isEmpty() || rFam[i].isEmpty() || rExplain[i].isEmpty()
                    || rSug[i].equals(rPat[i])) metaOk = false;
        }
        check("every rule carries id + family + explanation, suggestion != pattern", metaOk);
        check("every rule family is one of the five documented",
                famAll(rFam));

        System.out.println("== GrammarNudge:raw matching + modes ==");
        GrammarNudge.mode = GrammarNudge.MODE_STRICT;
        GrammarNudge.attach(g);
        // the raw-matching law: the CORRECT spelling never bites its own rule
        check("المدرسة (correct ة) passes in STRICT",
                g.scanCore("المدرسة قريبة من البيت").isEmpty());
        check("المدرسه (misspelled) bites in STRICT",
                g.scanCore("المدرسه قريبة من البيت").size() == 1);
        // OFF answers empty everywhere (shipped default, law 8)
        GrammarNudge.mode = GrammarNudge.MODE_OFF;
        check("OFF: the scan answers empty everywhere",
                GrammarNudge.scan("المدرسه ولم ذهب الى الجامعه", g, GrammarNudge.MODE_OFF, false).isEmpty());
        check("OFF: run(Ctx) answers empty",
                g.run(new Engine.Ctx(null, null, "المدرسه")).length == 0);
        // SOFT: the S rules fire, the strict-only pairs stay silent
        check("SOFT: المدرسه fires (S rule)",
                GrammarNudge.scan("دخلوا المدرسه مبكرين", g, GrammarNudge.MODE_SOFT, false).size() == 1);
        check("SOFT: the negation pair لم ذهب stays silent (X rule)",
                GrammarNudge.scan("لم ذهب أخي إلى الحفل", g, GrammarNudge.MODE_SOFT, false).isEmpty());
        check("STRICT: the negation pair لم ذهب bites with the pair fix",
                pairBites(g.scanCore("لم ذهب أخي إلى الحفل"), "لم يذهب"));
        check("SOFT: المدرسه still bites via the public scan",
                GrammarNudge.scan("دخلوا المدرسه مبكرين", g, GrammarNudge.MODE_SOFT, false).get(0).fix.equals("المدرسة"));
        // the audited refusals: no rule fires on the shared/context shapes
        check("علي (the name) never fires — the audited refusal",
                g.scanCore("علي حفظ القصيدة كاملة").isEmpty());
        check("ان (context choice) never fires — the audited refusal",
                g.scanCore("ذهبت الى المدرسة وان كنت متعبا").size() == 1); // only الى bites

        System.out.println("== GrammarNudge:the W55 contract ==");
        GrammarNudge.mode = GrammarNudge.MODE_STRICT;
        Engines.set(g);
        Engine.Cand[] out = Engines.run(new Engine.Ctx(null, null, "المدرسه"), 8);
        check("Engines.run carries the grammar cand", out.length == 1
                && out[0].text.equals("المدرسة") && out[0].source.equals("grammar"));
        check("the dispatcher rides the correct spelling empty",
                Engines.run(new Engine.Ctx(null, null, "المدرسة"), 8).length == 0);
        check("run() is single-word: pairs never ride the strip",
                Engines.run(new Engine.Ctx("لم", "ذهب", ""), 8).length == 0);
        check("run() survives null context words (the Ctx law)",
                g.run(new Engine.Ctx(null, null, null)) != null);
        check("run() never offers more than one cand",
                Engines.run(new Engine.Ctx(null, null, "المدرسه"), 8).length <= 1);
        Engines.set();   // the empty registration clears the registry (tests only)
        check("id() is grammar", g.id().equals("grammar"));

        System.out.println("== GrammarNudge:the strip ride + splice + incognito ==");
        List<String> strip = new ArrayList<>();
        strip.add("المدرسة");  // the dict already shows the correction
        GrammarNudge.nudgeInto(strip, out, 3);
        check("nudgeInto never duplicates a shown suggestion", strip.size() == 1);
        List<String> strip2 = new ArrayList<>();
        strip2.add("الدخول");
        GrammarNudge.nudgeInto(strip2, out, 2);
        check("nudgeInto appends within the cap", strip2.size() == 2 && strip2.get(1).equals("المدرسة"));
        List<String> tail = new ArrayList<>();
        tail.add("كتاب"); tail.add("مكتبة");
        Engine.Cand[] two = new Engine.Cand[]{ out[0], out[0] };
        GrammarNudge.nudgeInto(tail, two, 2);
        check("nudgeInto never displaces the full tail", tail.size() == 2 && tail.get(0).equals("كتاب"));
        String text = "دخل التلاميذ المدرسه مبكرين";
        List<GrammarNudge.Nudge> ns = GrammarNudge.scan(text, g, GrammarNudge.MODE_STRICT, false);
        String fixed = GrammarNudge.applyOne(text, ns.get(0));
        check("applyOne splices the exact span", fixed.equals("دخل التلاميذ المدرسة مبكرين"));
        check("applyOne edits nothing on a stale span",
                GrammarNudge.applyOne("دخلوا المتأخرون", ns.get(0)).equals("دخلوا المتأخرون"));
        check("applyOne survives nulls",
                GrammarNudge.applyOne(null, null) == null);
        String pairText = "لم ذهب أخي إلى الحفل";
        List<GrammarNudge.Nudge> pn = GrammarNudge.scan(pairText, g, GrammarNudge.MODE_STRICT, false);
        String pairFixed = GrammarNudge.applyOne(pairText, pn.get(0));
        check("the pair splice replaces the two-word span",
                pairFixed.equals("لم يذهب أخي إلى الحفل"));
        check("incognito refuses the whole scan (وضع التخفي: لا تحليل)",
                GrammarNudge.scan(text, g, GrammarNudge.MODE_STRICT, true).isEmpty());
        check("the Nudge toString carries no user words (privacy law)",
                !ns.get(0).toString().contains("المدرسه") && ns.get(0).toString().startsWith("nudge("));

        System.out.println("== GrammarNudge:the annoyance law ==");
        try {
            String clean = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("assets/testset_grammar.txt")), StandardCharsets.UTF_8);
            List<GrammarNudge.Nudge> hits = GrammarNudge.scan(clean, g, GrammarNudge.MODE_STRICT, false);
            check("the clean reference testset scans to ZERO nudges (STRICT)", hits.isEmpty());
            int words = clean.split("\\s+").length;
            double rate = hits.size() * 100.0 / words;
            check("the measured annoyance rate 0.0 ≤ the documented bound 2.0", rate <= 2.0);
        } catch (Exception e) {
            check("the reference testset reads", false);
        }

        System.out.println("== GrammarNudge:strict-load negatives (synthetic GRX1) ==");
        String good = fixture(
                "R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tالمفرد المؤنث ينتهي بتاء مربوطة\n"
                + "E\tx01\tG\tالمدرسة قريبة من البيت\n"
                + "E\tx01\tB\tدخل التلاميذ المدرسه مبكرين\n", 1, 0, 2, null);
        check("the minimal fixture loads", loads(good));
        check("GRX_MAGIC bites", bites(mutate(good, "GRX1", "GRX2")));
        check("GRX_HEADER bites (a dropped header key)", bites(headerDrop(good)));
        check("GRX_MADE bites", bites(mutate(good, "made\t2026-11-13", "made\tbad-date")));
        check("GRX_SOURCE bites", bites(mutate(good, "ci/make_grammar_rules.py", "other.py")));
        check("GRX_CRC_FIELD bites", bites(mutate(good, "crc32\t", "crc32\tzzzz")));
        check("GRX_CRC bites (one flipped payload byte)", bites(flipPayload(good)));
        check("GRX_RULES_COUNT bites", bites(mutate(good, "rules\t1", "rules\t2")));
        check("GRX_EXAMPLES_COUNT bites", bites(mutate(good, "examples\t2", "examples\t3")));
        check("GRX_PAIRS_COUNT bites", bites(mutate(good, "pairs\t0", "pairs\t1")));
        check("GRX_LINE bites (an R row short a field)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\n"
                        + "E\tx01\tG\tالمدرسة قريبة من البيت\n"
                        + "E\tx01\tB\tدخل التلاميذ المدرسه مبكرين\n", 1, 0, 2, null)));
        check("GRX_DUP bites (a pattern under two rules)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tشرح اول\n"
                        + "R\tx02\thamza\tS\tW\tالمدرسه\tالمدرسات\tشرح ثان\n"
                        + "E\tx01\tG\tالمدرسة قريبة\n"
                        + "E\tx01\tB\tدخل التلاميذ المدرسه\n"
                        + "E\tx02\tG\tالمدرسات يجلسن\n"
                        + "E\tx02\tB\tدخل التلميذات المدرسه\n", 2, 0, 4, null)));
        check("GRX_SUGGEST bites (suggestion == pattern)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسه\tشرح\n"
                        + "E\tx01\tG\tالمدرسة قريبة من البيت\n"
                        + "E\tx01\tB\tدخل التلاميذ المدرسه مبكرين\n", 1, 0, 2, null)));
        check("GRX_SIDE_G bites (the good example matches)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tشرح\n"
                        + "E\tx01\tG\tالمدرسه قريبة من البيت\n"
                        + "E\tx01\tB\tدخل التلاميذ المدرسه مبكرين\n", 1, 0, 2, null)));
        check("GRX_SIDE_B bites (the wrong example passes)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tشرح\n"
                        + "E\tx01\tG\tالمدرسة قريبة من البيت\n"
                        + "E\tx01\tB\tالمدرسة قريبة من البيت\n", 1, 0, 2, null)));
        check("GRX_EX_MISSING bites (a rule without its pair of examples)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tشرح\n"
                        + "E\tx01\tG\tالمدرسة قريبة من البيت\n", 1, 0, 1, null)));
        check("GRX_EX_RULE bites (an example for an unknown rule)", bites(
                fixture("R\tx01\thamza\tS\tW\tالمدرسه\tالمدرسة\tشرح\n"
                        + "E\tx09\tG\tالمدرسة قريبة\n"
                        + "E\tx01\tB\tدخل التلاميذ المدرسه مبكرين\n", 1, 0, 2, null)));
        check("GRX_PAIR_SHAPE bites (a pair row without its space)", bites(
                fixture("R\tx01\tnafi\tX\tP\tلا-ماض\tلم+مضارع\tلا تنفي الماضي\n"
                        + "P\tx01\tلاذهب\tلم يذهب\n"
                        + "E\tx01\tG\tلم يذهب أخي\n"
                        + "E\tx01\tB\tلا ذهب أخي\n", 1, 1, 2, null)));
        String pairFix = fixture(
                "R\tx01\tnafi\tX\tP\tلا-ماض\tلم+مضارع\tلا تنفي الماضي\n"
                + "P\tx01\tلا ذهب\tلم يذهب\n"
                + "E\tx01\tG\tلم يذهب أخي إلى السوق\n"
                + "E\tx01\tB\tلا ذهب أخي إلى السوق\n", 1, 1, 2, null);
        check("the pair fixture loads (X rule + one pair)", loads(pairFix));
        check("GRX_EX_SIDE bites (a third side letter)", bites(
                fixture("R\tx01\tnafi\tX\tP\tلا-ماض\tلم+مضارع\tلا تنفي الماضي\n"
                        + "P\tx01\tلا ذهب\tلم يذهب\n"
                        + "E\tx01\tG\tلم يذهب أخي\n"
                        + "E\tx01\tB\tلا ذهب أخي\n"
                        + "E\tx01\tZ\tلا ذهب أخي\n", 1, 1, 3, null)));
    }

    private static boolean famAll(String[] fams) {
        for (String f : fams) {
            boolean ok = f.equals("hamza") || f.equals("taa") || f.equals("maqsura")
                    || f.equals("nafi") || f.equals("iltibas");
            if (!ok) return false;
        }
        return true;
    }

    private static boolean pairBites(List<GrammarNudge.Nudge> ns, String fix) {
        return ns.size() == 1 && ns.get(0).fix.equals(fix);
    }

    // ---- synthetic GRX1 fixture helpers (the negative surface) ----
    private static String fixture(String payload, int rules, int pairs, int examples, String crcOverride) {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String c = crcOverride != null ? crcOverride : String.format("%08x", crc.getValue());
        return "GRX1\nmade\t2026-11-13\nsource\tci/make_grammar_rules.py\ncrc32\t" + c
                + "\nrules\t" + rules + "\nexamples\t" + examples + "\npairs\t" + pairs
                + "\n---\n" + payload;
    }

    private static boolean loads(String asset) {
        try { GrammarNudge.load(asset.getBytes(StandardCharsets.UTF_8)); return true; }
        catch (Throwable t) { return false; }
    }

    private static boolean bites(String asset) {
        try { GrammarNudge.load(asset.getBytes(StandardCharsets.UTF_8)); return false; }
        catch (Throwable t) { return true; }
    }

    private static String mutate(String s, String from, String to) {
        int i = s.indexOf(from);
        if (i < 0) return s;   // a missed mutation bites as GRX_HEADER anyway
        return s.substring(0, i) + to + s.substring(i + from.length());
    }

    private static String headerDrop(String s) {
        int i = s.indexOf("pairs\t");
        return s.substring(0, i) + s.substring(s.indexOf('\n', i) + 1);
    }

    private static String flipPayload(String s) {
        int i = s.indexOf("\n---\n") + 5;
        return s.substring(0, i) + "و" + s.substring(i + 1);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.out.println("NUDGETEST_FAILED checks=" + (pass + fail));
            System.exit(1);
        }
        System.out.println("NUDGETEST_OK checks=" + (pass + fail));
    }
}
