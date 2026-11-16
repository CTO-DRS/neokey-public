package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RootEngine — the W56 root-family engine («الكيبورد الذي يعرف أصل الكلمة»,
 * ROADMAP-V2 W56): the FIRST real engine through the W55 contract. The
 * knowledge (the root index, 3,670 roots over 95k dictionary words) lives in
 * assets/root_index.txt — RIX1, CRC32-sealed, derived deterministically by
 * ci/make_root_index.py from the shipped dictionary — and THIS class is the
 * thin strict interpreter (law 6): pure in-memory lookups, zero android.*,
 * JVM-tested exactly as shipped (the Predictor/EngineHarness discipline).
 *
 * Laws (docs/ROOT.md is the ONE contract):
 *   • STRICT LOAD (the NQF.read discipline): magic, header keys, CRC32 of the
 *     payload, root/covered counts, line shape, one root per word — any
 *     breach refuses the WHOLE index with a loud exception. Never a
 *     half-built engine.
 *   • FAIL-CLOSED WIRING: a corrupt asset throws at load; the service keeps
 *     the engines registry EMPTY (the W55 law: the keyboard is fail-closed
 *     silent) — no family suggestions, no correction bonus, typing unaffected.
 *   • LOOKUP: norm(word) → binary search over the sorted member keys (the
 *     Dictionary discipline — no per-keystroke scan, no hash flood), then the
 *     family slice in corpus-rank order.
 *   • PRIVACY: nothing stored, nothing logged, nothing sent — the composing
 *     word is transient (the Ctx contract, law 8/10). The attachment for
 *     Correction2 is the same pure lookup — root-neutral when detached
 *     (byte-identical to the measured baseline).
 */
public final class RootEngine implements Engine, Correction2.RootSignal {

    public static final String FORMAT = "RIX1";

    private final String[] keys;      // sorted norm(member) keys — the lookup spine
    private final int[] keyRoot;      // root index per key
    private final String[] roots;     // norm root values (the family keys)
    private final String[][] families;// members per root, corpus-rank order
    private final int covered;

    private RootEngine(String[] keys, int[] keyRoot, String[] roots,
                       String[][] families, int covered) {
        this.keys = keys; this.keyRoot = keyRoot; this.roots = roots;
        this.families = families; this.covered = covered;
    }

    /** Words covered by the index (from the header, re-counted at load). */
    public int coveredWords() { return covered; }
    /** Distinct roots in the index. */
    public int rootCount() { return roots.length; }

    /**
     * Strict load — RIX1 (the one derivation of docs/ROOT.md §2):
     *   line 1 magic RIX1 · made/source/crc32/roots/covered key-TAB-value
     *   header · the --- separator · the payload: root TAB space-separated
     * members, one root per line. The CRC32 covers the payload bytes exactly
     * (the NQF trailer discipline, text form). Any breach throws
     * IllegalArgumentException with a ROOTENGINE tag — never a partial load.
     */
    public static RootEngine load(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        need(s.startsWith(FORMAT + "\n"), "RIX_MAGIC");
        int dash = s.indexOf("\n---\n");
        need(dash > 0, "RIX_HEADER");
        String header = s.substring(0, dash + 1);
        String payload = s.substring(dash + 5);
        String[] hl = header.split("\n");
        need(hl.length == 6, "RIX_HEADER");
        String made = null, source = null, crcHex = null;
        int rootsClaim = -1, coveredClaim = -1;
        for (int i = 1; i < hl.length; i++) {
            int t = hl[i].indexOf('\t');
            need(t > 0, "RIX_HEADER");
            String k = hl[i].substring(0, t), v = hl[i].substring(t + 1);
            if (k.equals("made")) made = v;
            else if (k.equals("source")) source = v;
            else if (k.equals("crc32")) crcHex = v;
            else if (k.equals("roots")) rootsClaim = parseInt(v, "RIX_ROOTS");
            else if (k.equals("covered")) coveredClaim = parseInt(v, "RIX_COVERED");
        }
        need(made != null && made.matches("\\d{4}-\\d{2}-\\d{2}"), "RIX_MADE");
        need(source != null && source.equals("assets/ar.txt"), "RIX_SOURCE");
        need(crcHex != null && crcHex.matches("[0-9a-f]{8}"), "RIX_CRC_FIELD");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        // the CRC is an unsigned 32-bit value — parse via Long (9344e364 overflows int)
        need((int) crc.getValue() == (int) Long.parseLong(crcHex, 16), "RIX_CRC");

        String[] lines = payload.split("\n", -1);
        need(lines.length >= 1 && lines[lines.length - 1].isEmpty(), "RIX_PAYLOAD");
        int n = lines.length - 1;
        need(n == rootsClaim, "RIX_ROOTS_COUNT");
        String[][] fam = new String[n][];
        String[] rootArr = new String[n];
        int covered = 0;
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            int t = line.indexOf('\t');
            need(t == 3, "RIX_LINE");               // the root value is normed 3-letter
            rootArr[i] = line.substring(0, t);
            need(!rootArr[i].isEmpty(), "RIX_ROOT");
            String[] ms = line.substring(t + 1).split(" ");
            need(ms.length >= 1 && !ms[0].isEmpty(), "RIX_MEMBERS");
            for (int j = 0; j < ms.length; j++) need(!ms[j].isEmpty(), "RIX_MEMBERS");
            covered += ms.length;
            fam[i] = ms;
        }
        need(covered == coveredClaim, "RIX_COVERED_COUNT");

        // the lookup spine: every member key sorted once, a word under two
        // roots is a format breach (RIX_DUP) — never silently resolved
        int total = covered;
        String[] keys = new String[total];
        int[] kr = new int[total];
        int p = 0;
        for (int r = 0; r < n; r++) {
            for (String m : fam[r]) {
                String k = Dictionary.norm(m);
                need(!k.isEmpty(), "RIX_KEY");
                keys[p] = k; kr[p] = r; p++;
            }
        }
        Integer[] idx = new Integer[total];
        for (int i = 0; i < total; i++) idx[i] = i;
        final String[] fk = keys;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b2) { return fk[a].compareTo(fk[b2]); }
        });
        String[] sk = new String[total];
        int[] sr = new int[total];
        for (int i = 0; i < total; i++) {
            sk[i] = keys[idx[i]];
            if (i > 0 && sk[i].equals(sk[i - 1])) throw new IllegalArgumentException("ROOTENGINE RIX_DUP " + sk[i]);
            sr[i] = kr[idx[i]];
        }
        return new RootEngine(sk, sr, rootArr, fam, covered);
    }

    private static int parseInt(String v, String tag) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("ROOTENGINE " + tag); }
    }

    private static void need(boolean ok, String tag) {
        if (!ok) throw new IllegalArgumentException("ROOTENGINE " + tag);
    }

    // ------------------------------------------------------------------ lookup

    /** Root index for a word (raw or normalized spelling), -1 when unmapped. */
    public int rootOf(String word) {
        if (word == null || word.isEmpty()) return -1;
        String k = Dictionary.norm(word);
        if (k.isEmpty()) return -1;
        int lo = 0, hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[mid].compareTo(k);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return keyRoot[mid];
        }
        return -1;
    }

    /** The norm root value for a word, or null when unmapped (the tree chip). */
    public String rootValue(String word) {
        int r = rootOf(word);
        return r < 0 ? null : roots[r];
    }

    /** A defensive copy of the family (corpus-rank order), or null when unmapped. */
    public String[] familyTree(String word) {
        int r = rootOf(word);
        if (r < 0) return null;
        String[] f = families[r];
        String[] out = new String[f.length];
        System.arraycopy(f, 0, out, 0, f.length);
        return out;
    }

    // ------------------------------------------------------------ the W86 recovery

    /**
     * The W86 form-error recovery (the vision doc's rank 3 — the second
     * generation of the root-aware correction): the typed word is unmapped,
     * yet it is a FORM error — the derivation's own affix inventory strips
     * it, layered (max 2), each remainder len>=3, until a remainder is a
     * WITNESSED spine key; that key's family (corpus-rank) holds the attested
     * forms of the inferred root. DISPLAY ONLY — the silent path never
     * consumes this (the W56 law: the root serves, it never rewrites). No
     * witness → null (لا تخمين — the derivation's discipline at typing time).
     * The attested VERB conjugations joined RIX1 by the W86ب layer (the
     * derivation's ي/ن strips) — mirrored here in PSINGLE. The derivation's
     * raw-word plain-alef refusal (يا-vocatives, foreign remnants) cannot
     * survive the norm() this path applies first; the WITNESS law
     * (rootOf >= 0) carries the protection instead — a strip remainder that
     * is not an attested root refuses, exactly as it always has.
     */
    public String[] recover(String typed) {
        if (typed == null || typed.length() < 4) return null;
        String t = Dictionary.norm(typed);
        if (rootOf(t) >= 0) return null;      // mapped words ride run(Ctx) already
        int r = -1;
        for (int round = 0; round < 2 && r < 0; round++) {
            String s = null;
            for (String p : PMORPH)
                if (t.startsWith(p) && t.length() - p.length() >= 3) { s = t.substring(p.length()); break; }
            if (s == null)
                for (String p : PSUF)
                    if (t.endsWith(p) && t.length() - p.length() >= 3) { s = t.substring(0, t.length() - p.length()); break; }
            if (s == null)
                for (String p : PSINGLE)
                    if (t.startsWith(p) && t.length() - p.length() >= 3) { s = t.substring(p.length()); break; }
            if (s == null) break;
            t = s;
            r = rootOf(t);
        }
        if (r < 0) return null;
        String key = Dictionary.norm(typed);
        String[] f = families[r];
        List<String> out = new ArrayList<>(6);
        for (int i = 0; i < f.length && out.size() < 6; i++)
            if (!Dictionary.norm(f[i]).equals(key) && !out.contains(f[i])) out.add(f[i]);
        return out.toArray(new String[0]);
    }

    /** The derivation's own affix inventory (ci/make_root_index.py layers
     *  7-9 + the W86ب present-verb layer), mirrored verbatim — one strip per
     *  round, morphemic first. The ي/ن entries ride the WITNESS law, not the
     *  derivation's raw-word guard (norm() erases the hamza before any
     *  strip; an unattested remainder refuses as always). */
    private static final String[] PMORPH = {"ال", "است", "ان"};
    private static final String[] PSUF = {"ات", "ون", "ين", "ية", "ان", "اء", "ء",
            "هم", "كم", "هن", "ها", "نا", "ه", "ة", "ا", "ي", "ت"};
    private static final String[] PSINGLE = {"إ", "ا", "أ", "و", "ف", "ب", "ل", "ت", "ي", "ن"};

    /**
     * The strip ride (the service calls it right after familyInto): ONE
     * recovered family member (corpus-rank first, not already shown) fills
     * the same tail slot — detached → silent no-op (the W55 law). Pure.
     */
    public static void recoverInto(List<String> strip, String typed, int cap) {
        RootEngine e = active;
        if (strip == null || cap <= 0 || e == null) return;
        String[] rec = e.recover(typed);
        if (rec == null || rec.length == 0) return;
        for (int i = 0; i < rec.length; i++) {
            String t = rec[i] == null ? "" : rec[i].trim();
            if (t.isEmpty() || strip.contains(t)) continue;
            if (strip.size() < cap) strip.add(t);
            else strip.set(cap - 1, t);
            return;
        }
    }

    // --------------------------------------------------- the W55 engine contract

    public String id() { return "root"; }

    /**
     * Family branches for the composing word (the typing-time feature): the
     * top members in corpus-rank order, the composing word's own norm family
     * excluded, capped at 6. Empty context or an unmapped word → empty.
     */
    public String[] run(Engine.Ctx ctx) {
        String w = ctx.composing;
        if (w.length() < 3) return new String[0];
        int r = rootOf(w);
        if (r < 0) return new String[0];
        String[] f = families[r];
        String key = Dictionary.norm(w);
        List<String> out = new ArrayList<>(6);
        for (int i = 0; i < f.length && out.size() < 6; i++)
            if (!Dictionary.norm(f[i]).equals(key)) out.add(f[i]);
        return out.toArray(new String[0]);
    }

    // ------------------------------------- the Correction2 root signal (W56)

    private static volatile RootEngine active;

    /** Attach the verified engine: the registry + the Correction2 root signal
     *  go live together (one truth — the W56 wiring, docs/ROOT.md §4). */
    public static void attach(RootEngine e) { active = e; Correction2.rootSignal = e; }
    /** Detach: correction becomes root-neutral (the byte-identical baseline). */
    public static void detach() { active = null; Correction2.rootSignal = null; }
    /** The attached engine, or null (the fail-closed state). */
    public static RootEngine attached() { return active; }

    /** Static root lookup for Correction2: -1 when no verified engine rides. */
    public static int rootOfStatic(String word) {
        RootEngine e = active;
        return e == null ? -1 : e.rootOf(word);
    }

    // ------------------------------------------------- the strip helpers (pure)

    /**
     * The family branch rides the strip's TAIL slot (W56): the first family
     * candidate not already shown replaces the last dict completion when the
     * strip is full, appends when not — the dict completions keep their lead
     * slots, the fixes/shortcuts/emoji manipulations run after and may still
     * claim their own slots. Pure — JVM-tested.
     */
    public static void familyInto(List<String> strip, Engine.Cand[] family, int cap) {
        if (strip == null || family == null || cap <= 0) return;
        for (int i = 0; i < family.length; i++) {
            Engine.Cand c = family[i];
            if (c == null) continue;
            String t = c.text == null ? "" : c.text.trim();
            if (t.isEmpty() || strip.contains(t)) continue;
            if (strip.size() < cap) strip.add(t);
            else strip.set(cap - 1, t);
            return;
        }
    }

    /**
     * The long-press family tree as strip chips: the ✕ root chip first (tapping
     * it exits), then up to 10 members (tap to commit). Pure — JVM-tested.
     */
    public static List<String> treeChips(String root, String[] family) {
        List<String> out = new ArrayList<>();
        out.add("✕ " + (root == null ? "" : root) + " • شجرة العائلة");
        int n = family == null ? 0 : Math.min(family.length, 10);
        for (int i = 0; i < n; i++) out.add(family[i]);
        return out;
    }
}
