package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GrammarNudge — the W58 light grammar engine («المدقق العربي على أي جهاز»,
 * ROADMAP-V2 W58): the THIRD real engine through the W55 contract. The
 * knowledge (the five documented families: همزة القطع، التاء المربوطة/
 * الهاء، المقصورة/الياء، لا/لن/لم مع الماضي، أشهر الالتباسات — every rule
 * with ONE correct example that passes and ONE wrong example that bites)
 * lives in assets/grammar_rules.txt — GRX1, CRC32-sealed, derived
 * deterministically by ci/make_grammar_rules.py (the hand-verified source +
 * the independent reference scanner; the bare ان/علي/الا rules were audited
 * and REFUSED — a context choice or a shared written shape is never a rule —
 * docs/GRAMMAR.md §5) — and THIS class is the thin strict interpreter
 * (law 6): pure in-memory matching, zero android.*, JVM-tested exactly as
 * shipped.
 *
 * Laws (docs/GRAMMAR.md is the ONE contract):
 *   • STRICT LOAD (the NQF.read discipline): magic, header keys, CRC32 of the
 *     payload, the rules/examples/pairs claims re-counted, row shapes (exact
 *     field counts), unique patterns, suggestion != pattern, and the SIDES
 *     LAW re-verified at load — every GOOD example scans to ZERO nudges and
 *     every BAD example to EXACTLY ONE with its own rule and suggestion (the
 *     W53-prep agreement law inside the engine: the derivation-side Python
 *     checker and this shipped matcher must agree on every row). Any breach
 *     refuses the WHOLE asset with a loud NUDGEENGINE tag — never a
 *     half-built engine.
 *   • RAW MATCHING: words are matched EXACTLY after stripping harakat only —
 *     Dictionary.norm folds ة→ه and ى→ي, which would make every CORRECT word
 *     bite its rule; the misspellings live in a different spelling from the
 *     corrections and only the raw shape tells them apart.
 *   • THE THREE MODES (shipped CLOSED — the quiet default, law 8): OFF
 *     answers empty everywhere; SOFT (ناعم) offers only the unambiguous
 *     written-shape rules (S); STRICT (صارم) adds the context-dependent
 *     negation pairs (X). The engine EXPLAINS, it never force-corrects: the
 *     strip shows the corrected form as an OPTIONAL tap-to-apply candidate,
 *     and the whole-text scan lists every nudge with its explanation — each
 *     one applies ONLY on the user's tap.
 *   • TYPING-TIME = SINGLE-WORD: run() consults the word rules on the
 *     composing word only — the negation pairs need the next word, so they
 *     are scan-time educational only (a two-word cand would splice wrong).
 *   • INCOGNITO: the scan refuses when incognito (and the wiring suppresses
 *     the ride) — وضع التخفي يعني لا تحليل إطلاقا.
 *   • PRIVACY: nothing stored, nothing logged, nothing sent — Nudge.toString
 *     prints offsets and rule indices, never the user's words.
 */
public final class GrammarNudge implements Engine {

    public static final String FORMAT = "GRX1";

    /** The three modes (docs/GRAMMAR.md §3) — shipped CLOSED. */
    public static final int MODE_OFF = 0, MODE_SOFT = 1, MODE_STRICT = 2;

    /** The active mode — mirrored from prefs by the service; OFF is the shipped default. */
    public static volatile int mode = MODE_OFF;

    // ------------------------------------------------------------- the data
    private final String[] wKeys, wFix;           // word patterns (sorted) → corrections
    private final int[] wRule;                    // pattern → rule index
    private final String[] pKeys, pFix;           // pair patterns (sorted) → corrections
    private final int[] pRule;                    // pair → rule index
    private final String[] rId, rFam, rExplain;   // rule meta (authoring order)
    private final int[] rSev;                     // 0=S (soft+strict) 1=X (strict-only)
    private final int rules, pairs, examples;

    private GrammarNudge(String[] wk, String[] wf, int[] wr, String[] pk, String[] pf, int[] pr,
                         String[] id, String[] fam, int[] sev, String[] ex,
                         int rules, int pairs, int examples) {
        this.wKeys = wk; this.wFix = wf; this.wRule = wr;
        this.pKeys = pk; this.pFix = pf; this.pRule = pr;
        this.rId = id; this.rFam = fam; this.rSev = sev; this.rExplain = ex;
        this.rules = rules; this.pairs = pairs; this.examples = examples;
    }

    /** Rules in the asset (from the header, re-counted at load). */
    public int ruleCount() { return rules; }
    /** Pair rows in the asset (from the header, re-counted at load). */
    public int pairCount() { return pairs; }
    /** Example rows in the asset (from the header, re-counted at load). */
    public int exampleCount() { return examples; }

    /** A nudge found by the scanner — a complete display object (offsets over
     *  the ORIGINAL text, the exact correction, and the explanation). */
    public static final class Nudge {
        public final int start, end;      // the wrong span in the scanned text
        public final String wrong, fix;   // the matched misspelling and its correction
        public final String id, family, explain;
        public final int rule;            // index into the rule meta
        Nudge(int start, int end, String wrong, String fix, int rule,
              String id, String family, String explain) {
            this.start = start; this.end = end; this.wrong = wrong; this.fix = fix;
            this.rule = rule; this.id = id; this.family = family; this.explain = explain;
        }
        /** Offsets and rule indices only — the user's words are never stringified (privacy law). */
        public String toString() {
            return "nudge(" + start + "," + end + ",r" + rule + ")";
        }
    }

    // ------------------------------------------------------------------ load

    /**
     * Strict load — GRX1 (the one derivation of docs/GRAMMAR.md §2):
     * line 1 magic GRX1 · made/source/crc32/rules/examples/pairs key-TAB-value
     * header · the --- separator · the payload: R rows (7 tabs), P rows (3
     * tabs), E rows (3 tabs). The CRC32 covers the payload bytes exactly, and
     * the SIDES LAW re-verifies every example with the shipped matcher itself.
     * Any breach throws IllegalArgumentException with a NUDGEENGINE tag —
     * never a partial load.
     */
    public static GrammarNudge load(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        need(s.startsWith(FORMAT + "\n"), "GRX_MAGIC");
        int dash = s.indexOf("\n---\n");
        need(dash > 0, "GRX_HEADER");
        String header = s.substring(0, dash + 1);
        String payload = s.substring(dash + 5);
        String[] hl = header.split("\n");
        need(hl.length == 7, "GRX_HEADER");   // magic + made/source/crc32/rules/examples/pairs
        String made = null, source = null, crcHex = null;
        int rulesClaim = -1, exClaim = -1, pairsClaim = -1;
        for (int i = 1; i < hl.length; i++) {
            int t = hl[i].indexOf('\t');
            need(t > 0, "GRX_HEADER");
            String k = hl[i].substring(0, t), v = hl[i].substring(t + 1);
            if (k.equals("made")) made = v;
            else if (k.equals("source")) source = v;
            else if (k.equals("crc32")) crcHex = v;
            else if (k.equals("rules")) rulesClaim = parseInt(v, "GRX_RULES");
            else if (k.equals("examples")) exClaim = parseInt(v, "GRX_EXAMPLES");
            else if (k.equals("pairs")) pairsClaim = parseInt(v, "GRX_PAIRS");
            else need(false, "GRX_HEADER");
        }
        need(made != null && made.matches("\\d{4}-\\d{2}-\\d{2}"), "GRX_MADE");
        need(source != null && source.equals("ci/make_grammar_rules.py"), "GRX_SOURCE");
        need(crcHex != null && crcHex.matches("[0-9a-f]{8}"), "GRX_CRC_FIELD");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        need((int) crc.getValue() == (int) Long.parseLong(crcHex, 16), "GRX_CRC");

        // the payload rows
        ArrayList<String[]> rl = new ArrayList<>(), pl = new ArrayList<>(), el = new ArrayList<>();
        String[] lines = payload.split("\n", -1);
        need(lines.length >= 1 && lines[lines.length - 1].isEmpty(), "GRX_PAYLOAD");
        for (int i = 0; i < lines.length - 1; i++) {
            String L = lines[i];
            need(!L.isEmpty(), "GRX_PAYLOAD");
            char kind = L.charAt(0);
            if (kind == 'R') {
                need(L.split("\t", -1).length == 8, "GRX_LINE");
                rl.add(L.split("\t"));
            } else if (kind == 'P') {
                need(L.split("\t", -1).length == 4, "GRX_LINE");
                pl.add(L.split("\t"));
            } else if (kind == 'E') {
                need(L.split("\t", -1).length == 4, "GRX_LINE");
                el.add(L.split("\t"));
            } else need(false, "GRX_LINE");
        }
        need(rl.size() == rulesClaim, "GRX_RULES_COUNT");
        need(pl.size() == pairsClaim, "GRX_PAIRS_COUNT");
        need(el.size() == exClaim, "GRX_EXAMPLES_COUNT");
        need(el.size() == 2 * rl.size(), "GRX_EXAMPLES_SHAPE");

        // the R rows: unique ids, unique patterns, legal fields
        int n = rl.size();
        String[] id = new String[n], fam = new String[n], pat = new String[n],
                 sug = new String[n], ex = new String[n];
        int[] sev = new int[n];
        java.util.HashSet<String> ids = new java.util.HashSet<>(), pats = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            String[] r = rl.get(i);
            id[i] = r[1]; fam[i] = r[2]; sev[i] = r[3].equals("S") ? 0 : (r[3].equals("X") ? 1 : -1);
            boolean word = r[4].equals("W");
            pat[i] = r[5]; sug[i] = r[6]; ex[i] = r[7];
            need(!id[i].isEmpty() && ids.add(id[i]), "GRX_ID " + id[i]);
            need(!fam[i].isEmpty(), "GRX_FAMILY");
            need(sev[i] >= 0, "GRX_SEV " + r[3]);
            if (word) {
                need(pat[i].indexOf(' ') < 0 && !pat[i].isEmpty(), "GRX_PATTERN " + pat[i]);
            } else {
                need(r[4].equals("P") && pat[i].endsWith("-ماض") && pat[i].indexOf(' ') < 0,
                        "GRX_PATTERN " + pat[i]);
            }
            need(!sug[i].isEmpty() && !sug[i].equals(pat[i]), "GRX_SUGGEST " + pat[i]);
            need(!ex[i].isEmpty(), "GRX_EXPLAIN " + pat[i]);
            need(pats.add(pat[i]), "GRX_DUP " + pat[i]);
        }

        // the P rows: pair patterns under the strict-only rules
        ArrayList<String> pk = new ArrayList<>(), pf = new ArrayList<>();
        ArrayList<Integer> pr = new ArrayList<>();
        for (String[] p : pl) {
            int ri = indexOf(id, p[1]);
            need(ri >= 0, "GRX_PAIR_RULE " + p[1]);
            need(sev[ri] == 1, "GRX_PAIR_RULE " + p[1]);
            int sp = p[2].indexOf(' ');
            need(sp > 0 && p[2].indexOf(' ', sp + 1) < 0, "GRX_PAIR_SHAPE " + p[2]);
            int sp2 = p[3].indexOf(' ');
            need(sp2 > 0 && p[3].indexOf(' ', sp2 + 1) < 0 && !p[3].equals(p[2]), "GRX_PAIR_SHAPE " + p[3]);
            need(pats.add(p[2]), "GRX_DUP " + p[2]);
            pk.add(p[2]); pf.add(p[3]); pr.add(ri);
        }
        need(pk.size() == pairsClaim, "GRX_PAIRS_COUNT");

        // the E rows: exactly one G + one B per rule, ids known
        String[] gEx = new String[n], bEx = new String[n];
        for (String[] e : el) {
            int ri = indexOf(id, e[1]);
            need(ri >= 0, "GRX_EX_RULE " + e[1]);
            if (e[2].equals("G")) {
                need(gEx[ri] == null, "GRX_EX_DUP " + e[1]);
                gEx[ri] = e[3];
            } else if (e[2].equals("B")) {
                need(bEx[ri] == null, "GRX_EX_DUP " + e[1]);
                bEx[ri] = e[3];
            } else need(false, "GRX_EX_SIDE " + e[2]);
            need(!e[3].isEmpty(), "GRX_EX_TEXT");
        }
        for (int i = 0; i < n; i++) {
            need(gEx[i] != null && bEx[i] != null, "GRX_EX_MISSING " + id[i]);
        }

        // the lookups: every pattern sorted once (the Dictionary discipline),
        // assembled BEFORE the engine exists — never a half-built one
        int[] ruleIdentity = new int[n];
        for (int i = 0; i < n; i++) ruleIdentity[i] = i;
        String[] wKeys = new String[n], wFix = new String[n];
        int[] wRule = new int[n];
        sortKeys(pat, sug, ruleIdentity, wFix, wRule, wKeys, n, "NUDGEENGINE GRX_DUP ");
        int[] pRuleIn = new int[pk.size()];
        for (int i = 0; i < pk.size(); i++) pRuleIn[i] = pr.get(i);
        String[] pKeys = new String[pk.size()], pFix = new String[pk.size()];
        int[] pRule = new int[pk.size()];
        sortKeys(pk.toArray(new String[0]), pf.toArray(new String[0]), pRuleIn, pFix, pRule,
                pKeys, pk.size(), "NUDGEENGINE GRX_DUP ");
        GrammarNudge g = new GrammarNudge(wKeys, wFix, wRule, pKeys, pFix, pRule,
                id, fam, sev, ex, n, pk.size(), el.size());

        // THE SIDES LAW — the shipped matcher itself re-verifies every example:
        // G → ZERO nudges from ANY rule; B → EXACTLY ONE with its own rule
        // and its own correction (the pair rules' corrections ride their P
        // rows — the R suggest field of a pair shell is meta, never compared).
        String[][] ruleFixes = new String[n][];
        for (int i = 0; i < n; i++) {
            if (sev[i] == 1) {
                ArrayList<String> fs = new ArrayList<>();
                for (int j = 0; j < pk.size(); j++) if (pr.get(j) == i) fs.add(pf.get(j));
                ruleFixes[i] = fs.toArray(new String[0]);
            } else {
                ruleFixes[i] = new String[]{ sug[i] };
            }
        }
        for (int i = 0; i < n; i++) {
            List<Nudge> ng = g.scanCore(gEx[i]);
            if (!ng.isEmpty()) throw new IllegalArgumentException("NUDGEENGINE GRX_SIDE_G " + id[i]);
            List<Nudge> nb = g.scanCore(bEx[i]);
            boolean okB = nb.size() == 1 && g.rId[nb.get(0).rule].equals(id[i]);
            if (okB) {
                okB = false;
                for (String f : ruleFixes[i]) if (f.equals(nb.get(0).fix)) { okB = true; break; }
            }
            if (!okB) throw new IllegalArgumentException("NUDGEENGINE GRX_SIDE_B " + id[i]);
        }
        return g;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    /** Sorts one pattern bank (word rules or pair rows) with its parallel
     *  fix/rule arrays; a pattern under two lines is a format breach with the
     *  given tag — never silently resolved. */
    private static void sortKeys(String[] pat, String[] fixIn, int[] ruleIn,
                                 String[] fixOut, int[] ruleOut, String[] keysOut,
                                 int n, String dupTag) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final String[] fp = pat;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b2) { return fp[a].compareTo(fp[b2]); }
        });
        for (int i = 0; i < n; i++) {
            keysOut[i] = fp[idx[i]]; fixOut[i] = fixIn[idx[i]]; ruleOut[i] = ruleIn[idx[i]];
            if (i > 0 && keysOut[i].equals(keysOut[i - 1]))
                throw new IllegalArgumentException(dupTag + keysOut[i]);
        }
    }

    private static int parseInt(String v, String tag) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("NUDGEENGINE " + tag); }
    }

    private static void need(boolean ok, String tag) {
        if (!ok) throw new IllegalArgumentException("NUDGEENGINE " + tag);
    }

    // ---------------------------------------------------------------- matching

    private static String stripMarks(String t) {
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x064B && c <= 0x0652) continue;   // harakat — the only fold
            b.append(c);
        }
        return b.toString();
    }

    private int wordIndex(String w) {
        int lo = 0, hi = wKeys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = wKeys[mid].compareTo(w);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    private int pairIndex(String p) {
        int lo = 0, hi = pKeys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = pKeys[mid].compareTo(p);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    /** The core scanner: word + pair matching over the stripped tokens, ALL
     *  rules regardless of mode — the mode gate is the caller's (the load-time
     *  sides law runs it in FULL; the public scan applies the gate).
     *  Package-visible: the battery (same package) drives it directly. */
    List<Nudge> scanCore(String text) {
        List<Nudge> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int i = 0, n = text.length(), nt = 0;
        int[] tStart = new int[n / 2 + 2];
        String[] tTok = new String[tStart.length];
        while (i < n) {
            char c = text.charAt(i);
            if (c >= 0x0621 && c <= 0x0652) {
                int j = i;
                while (j < n && text.charAt(j) >= 0x0621 && text.charAt(j) <= 0x0652) j++;
                tStart[nt] = i; tTok[nt] = text.substring(i, j); nt++;
                i = j;
            } else i++;
        }
        String[] st = new String[nt];
        for (int t = 0; t < nt; t++) st[t] = stripMarks(tTok[t]);
        for (int t = 0; t < nt; t++) {
            int wi = wordIndex(st[t]);
            if (wi >= 0) {
                int r = wRule[wi];
                out.add(new Nudge(tStart[t], tStart[t] + tTok[t].length(), tTok[t], wFix[wi], r,
                        rId[r], rFam[r], rExplain[r]));
            }
        }
        for (int t = 0; t + 1 < nt; t++) {
            int pi = pairIndex(st[t] + " " + st[t + 1]);
            if (pi >= 0) {
                int r = pRule[pi];
                out.add(new Nudge(tStart[t], tStart[t + 1] + tTok[t + 1].length(),
                        tTok[t] + " " + tTok[t + 1], pFix[pi], r, rId[r], rFam[r], rExplain[r]));
            }
        }
        out.sort(new java.util.Comparator<Nudge>() {
            @Override public int compare(Nudge a, Nudge b2) { return Integer.compare(a.start, b2.start); }
        });
        return out;
    }

    // ------------------------------------------------ the mode-gated static API

    private static volatile GrammarNudge active;

    /** Attach the verified engine (the registry + this signal go live together). */
    public static void attach(GrammarNudge e) { active = e; }
    /** Detach: every static lookup answers empty (the fail-closed state). */
    public static void detach() { active = null; }
    /** The attached engine, or null (the fail-closed state). */
    public static GrammarNudge attached() { return active; }

    /**
     * The whole-text educational scan (the ClipEdit «تدقيق النص» surface):
     * the mode-gated nudges — OFF → empty; SOFT → the unambiguous S rules;
     * STRICT → all rules. INCOGNITO → empty (وضع التخفي: لا تحليل). Null
     * engine → empty. The nudges EXPLAIN — the wiring applies them one by one
     * ONLY on the user's tap (the engine never force-corrects).
     */
    public static List<Nudge> scan(String text, GrammarNudge e, int m, boolean incognito) {
        List<Nudge> out = new ArrayList<>();
        if (e == null || m == MODE_OFF || incognito) return out;
        for (Nudge nd : e.scanCore(text)) if (m == MODE_STRICT || e.rSev[nd.rule] == 0) out.add(nd);
        return out;
    }

    /**
     * The fail-closed splice: replace the nudge's span with its correction —
     * but ONLY when the text still carries the exact wrong span at the exact
     * offset (a stale nudge edits nothing). Pure — JVM-tested.
     */
    public static String applyOne(String text, Nudge nd) {
        if (text == null || nd == null || nd.start < 0 || nd.end > text.length() || nd.start >= nd.end)
            return text;
        if (!text.substring(nd.start, nd.end).equals(nd.wrong)) return text;
        return text.substring(0, nd.start) + nd.fix + text.substring(nd.end);
    }

    // --------------------------------------------------- the W55 engine contract

    public String id() { return "grammar"; }

    /**
     * The typing-time candidate for the composing word (single-word rules
     * only): when the raw stripped word matches a misspelling pattern, the
     * corrected form is offered — an OPTIONAL tap-to-apply suggestion, never
     * forced. OFF, or no match, or a strict-only rule under SOFT → empty.
     * The negation pairs never ride here (they need the next word —
     * scan-time educational only).
     */
    public String[] run(Engine.Ctx ctx) {
        if (mode == MODE_OFF || ctx.composing.isEmpty()) return new String[0];
        String w = stripMarks(ctx.composing);
        int wi = wordIndex(w);
        if (wi < 0) return new String[0];
        if (mode == MODE_SOFT && rSev[wRule[wi]] != 0) return new String[0];
        return new String[]{ wFix[wi] };
    }

    // ------------------------------------------------------ the pure helpers

    /**
     * The typing-time ride: the grammar correction APPENDS to the strip when
     * there is room and NEVER displaces the family tail (W56) or the tashkeel
     * reading (W57). Pure — JVM-tested.
     */
    public static void nudgeInto(List<String> strip, Engine.Cand[] cands, int cap) {
        if (strip == null || cands == null || cap <= 0) return;
        for (int i = 0; i < cands.length; i++) {
            Engine.Cand c = cands[i];
            if (c == null) continue;
            String t = c.text == null ? "" : c.text.trim();
            if (t.isEmpty() || strip.contains(t)) continue;
            if (strip.size() < cap) strip.add(t);
            return;
        }
    }
}
