package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W54 capacity rehearsal (ROADMAP-V2 W54) — the ledger-vs-reality auditor.
 *
 * NOT a test suite: it compiles via the runner's glob and is never counted
 * (the 1864 counter stays byte-stable — the W50-prep pattern). The gate
 * ci/capacity_gate.sh hands it the FRESH dex size; every check carries a tag
 * a negative proof can bite. Fail-closed: any disagreement between the ledger's
 * claims and the tree's reality is a loud refusal, never a warning.
 */
public class CapacityLedger {
    static int checks = 0;
    static boolean failed = false;
    static void check(String tag, boolean ok) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + tag);
        if (!ok) { failed = true; System.out.println("CAPACITYLEDGER_FAILED " + tag); }
    }

    static List<String[]> rows() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("ci/capacity_ledger.tsv"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            out.add(line.split("\t", -1));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        int dex = Integer.parseInt(args[0]);

        // ---- 1. ledger shape -----------------------------------------------------
        List<String[]> rows;
        try { rows = rows(); } catch (Exception e) { check("ledger.readable", false); done(); return; }
        check("ledger.readable", true);
        Map<String, String[]> byKey = new LinkedHashMap<>();
        boolean shape = true;
        for (String[] r : rows) {
            if (r.length < 3 || r.length > 4) { shape = false; continue; }
            byKey.put(r[0] + "." + r[1], r);
        }
        check("ledger.shape", shape);
        check("ledger.levers", byKey.containsKey("writer.templates.done")
                && byKey.containsKey("theme.phrases.done")
                && byKey.containsKey("feedback.copy.pulled")
                && byKey.containsKey("layouts.symbols.remaining")
                && byKey.containsKey("cliprepo.defaults.remaining")
                && byKey.containsKey("r8.flags.reviewed"));

        // ---- 2. the done levers are REAL (assets exist with the exact shape) -----
        int[] perCat = {3, 3, 3, 3, 8, 3, 3};
        try {
            List<String> lines = Files.readAllLines(Paths.get("assets/writer_templates.txt"), StandardCharsets.UTF_8);
            int[] ac = new int[7], ec = new int[7];
            boolean cols = true;
            for (String l : lines) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                String[] p = l.split("\t", 4);
                if (p.length != 4 || p[3].isEmpty()) { cols = false; continue; }
                if (p[0].equals("A")) ac[Integer.parseInt(p[1])]++;
                else if (p[0].equals("E")) ec[Integer.parseInt(p[1])]++;
                else cols = false;
            }
            boolean counts = cols;
            for (int c = 0; c < 7; c++) counts &= ac[c] == perCat[c] && ec[c] == perCat[c];
            check("assets.writer.shape", counts);
        } catch (Exception e) { check("assets.writer.shape", false); }

        try {
            List<String> lines = Files.readAllLines(Paths.get("assets/theme_phrases.txt"), StandardCharsets.UTF_8);
            int d = 0, k = 0, section = 0;
            for (String l : lines) {
                if (l.isEmpty()) continue;
                if (l.charAt(0) == '#') {
                    if (l.equals("#defaults")) section = 1;
                    else if (l.equals("#adhkar")) section = 2;
                    else if (!l.startsWith("# ")) throw new IllegalStateException("marker");
                    continue;
                }
                if (section == 1) d++;
                else if (section == 2) k++;
                else throw new IllegalStateException("order");
            }
            check("assets.theme.shape", d == 24 && k == 20);
        } catch (Exception e) { check("assets.theme.shape", false); }

        // ---- 3. the sources are actually trimmed (no pretending) -----------------
        String writer = new String(Files.readAllBytes(Paths.get("src/com/hark/neokey/Writer.java")), StandardCharsets.UTF_8);
        check("source.writer.interpreter", writer.contains("writer.templates.not_loaded")
                && !writer.contains("String[][] AR = {")
                && !writer.contains("أتقدم إليكم بهذا الخطاب"));
        String theme = new String(Files.readAllBytes(Paths.get("src/com/hark/neokey/Theme.java")), StandardCharsets.UTF_8);
        check("source.theme.loader", theme.contains("theme.phrases.count")
                && !theme.contains("public static final String[] DEFAULT_PHRASES")
                && !theme.contains("public static final String[] ADHKAR"));

        // ---- 4. dex vs the ledger (the fresh measurement decides) ----------------
        // W57 re-anchor (the documented ceiling decision): the part-A engines
        // legitimately EXCEED the W54 pre-trim record — the honest-delta law is
        // now the measured SUM EQUALITY (post == pre_wire + every engine row +
        // the batch-2 net), stricter than the old inequality.
        int preWire = Integer.parseInt(byKey.get("dex.pre_wire")[2]);
        int post = Integer.parseInt(byKey.get("dex.post_trim")[2]);
        int ceiling = Integer.parseInt(byKey.get("ceiling.raised")[2]);
        int eng = 0;
        for (String k : new String[]{"root.engine.shipped", "tashkeel.engine.shipped", "feedback.copy.pulled", "grammar.engine.shipped",
                "claritymeter.engine.shipped", "panicwipe.engine.shipped", "integritycert.engine.shipped", "otpshield.engine.shipped",
                "airseal.engine.shipped", "strip.engine.shipped", "personal.engine.shipped", "dialect.engine.shipped", "genthemes.engine.shipped",
                "aacboard.engine.shipped", "numsmart.engine.shipped", "themechannel.engine.shipped",
                "root.recover.shipped", "root.recover.verbs.shipped", "trim.toasts.shipped", "drift.w75_80.shipped", "dialect.correction.shipped", "dialect.vocab.shipped"})
            eng += Integer.parseInt(byKey.get(k)[2]);
        check("dex.claim.current", dex <= post);
        check("dex.under.ceiling", dex <= ceiling);
        check("ledger.delta.honest", post == preWire + eng);
        check("dex.batch2.measured", byKey.get("feedback.copy.pulled")[3].startsWith("measured net"));

        // ---- 5. the ceiling decision is documented (no silent raise, ever) -------
        // F10 repair: the pinned "W69 decision" prefix went stale the moment the
        // W70/W71/W74 raises landed after it (the map holds the LAST row) — the
        // law is "a documented raise, submitted for ratification", any week id.
        check("ceiling.decision.ledger", byKey.containsKey("ceiling.raised")
                && "554000".equals(byKey.get("ceiling.raised")[2])
                && byKey.get("ceiling.raised")[3].matches("W\\d+ decision.*")
                && byKey.get("ceiling.raised")[3].contains("submitted for owner ratification in NOTES"));
        String roadmap = new String(Files.readAllBytes(Paths.get("ROADMAP-V2.md")), StandardCharsets.UTF_8);
        check("ceiling.decision.roadmap", roadmap.contains("W54-CEILING-DECISION")
                && roadmap.contains("W57-CEILING-RAISE")
                && roadmap.contains("W58-CEILING-RAISE"));

        // ---- 6. the round-trip evidence is on disk (the trim's behavioral proof) --
        String proof;
        try { proof = new String(Files.readAllBytes(Paths.get("ci/capacity-roundtrip.log")), StandardCharsets.UTF_8); }
        catch (Exception e) { proof = ""; }
        check("proof.roundtrip.saved", proof.contains("WRITER OK: 14976") && proof.contains("THEME OK: 44/44"));

        done();
    }

    static void done() {
        System.out.println("CAPACITYLEDGER_OK checks=" + checks);
        System.exit(checks > 0 && !failed ? 0 : 1);
    }
}
