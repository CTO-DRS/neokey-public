package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TashkeelEngine — the W57 progressive-tashkeel engine («الكيبورد الذي ينطق
 * ما تكتبه», ROADMAP-V2 W57): the SECOND real engine through the W55 contract.
 * The knowledge (the vocalization index, 28,020 entries covering 28,196 forms
 * of the 134k dictionary) lives in assets/tashkeel_index.txt — RIX2,
 * CRC32-sealed, derived deterministically by ci/make_tashkeel_index.py (the
 * hand-verified lexicon + the ال-composition + the و/ف conjunction + the bare
 * article rule; the written-gemination rule was audited and REFUSED —
 * docs/TASHKEEL.md) — and THIS class is the thin strict interpreter (law 6):
 * pure in-memory lookups, zero android.*, JVM-tested exactly as shipped.
 *
 * Laws (docs/TASHKEEL.md is the ONE contract):
 *   • STRICT LOAD (the NQF.read discipline): magic, header keys, CRC32 of the
 *     payload, entry/covered counts, line shape (ONE tab), normed keys, and
 *     the TSX_FORM fold law — every form's norm folds back to its key — any
 *     breach refuses the WHOLE index with a loud exception. Never a
 *     half-built engine.
 *   • FAIL-CLOSED WIRING: a corrupt asset throws at load; the service keeps
 *     the engines registry without it (the W55 law) — typing unaffected.
 *   • LOOKUP: norm(word) → binary search over the sorted member keys (the
 *     Dictionary discipline), then the attested forms in derivation order.
 *   • THE THREE DEGREES (shipped CLOSED — the quiet default, law 8): OFF
 *     answers empty everywhere; AMBIG offers forms only for multi-reading
 *     keys (علم → عِلْم عَلَمَ عَلَّمَ — the user picks the reading, the
 *     engine never guesses); FULL offers the first attested form of every
 *     covered word. An uncovered word is NEVER guessed (the ladder skipped
 *     it honestly at derivation time).
 *   • PRIVACY: nothing stored, nothing logged, nothing sent — the composing
 *     word is transient (the Ctx contract, law 8/10).
 */
public final class TashkeelEngine implements Engine {

    public static final String FORMAT = "RIX2";

    /** The three degrees (docs/TASHKEEL.md §3) — shipped CLOSED. */
    public static final int MODE_OFF = 0, MODE_AMBIG = 1, MODE_FULL = 2;

    /** The active degree — mirrored from prefs by the service; OFF is the shipped default. */
    public static volatile int mode = MODE_OFF;

    private final String[] keys;      // sorted norm keys — the lookup spine
    private final String[][] forms;   // attested diacritized forms per key, derivation order
    private final int covered;

    private TashkeelEngine(String[] keys, String[][] forms, int covered) {
        this.keys = keys; this.forms = forms; this.covered = covered;
    }

    /** Distinct entries in the index (from the header, re-counted at load). */
    public int entryCount() { return keys.length; }
    /** Total attested forms (from the header, re-counted at load). */
    public int coveredForms() { return covered; }

    /**
     * Strict load — RIX2 (the one derivation of docs/TASHKEEL.md §2):
     * line 1 magic RIX2 · made/source/crc32/entries/covered key-TAB-value
     * header · the --- separator · the payload: key TAB space-separated
     * forms, one line per norm key. The CRC32 covers the payload bytes
     * exactly. Any breach throws IllegalArgumentException with a TSXENGINE
     * tag — never a partial load.
     */
    public static TashkeelEngine load(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        need(s.startsWith(FORMAT + "\n"), "TSX_MAGIC");
        int dash = s.indexOf("\n---\n");
        need(dash > 0, "TSX_HEADER");
        String header = s.substring(0, dash + 1);
        String payload = s.substring(dash + 5);
        String[] hl = header.split("\n");
        need(hl.length == 6, "TSX_HEADER");
        String made = null, source = null, crcHex = null;
        int entriesClaim = -1, coveredClaim = -1;
        for (int i = 1; i < hl.length; i++) {
            int t = hl[i].indexOf('\t');
            need(t > 0, "TSX_HEADER");
            String k = hl[i].substring(0, t), v = hl[i].substring(t + 1);
            if (k.equals("made")) made = v;
            else if (k.equals("source")) source = v;
            else if (k.equals("crc32")) crcHex = v;
            else if (k.equals("entries")) entriesClaim = parseInt(v, "TSX_ENTRIES");
            else if (k.equals("covered")) coveredClaim = parseInt(v, "TSX_COVERED");
        }
        need(made != null && made.matches("\\d{4}-\\d{2}-\\d{2}"), "TSX_MADE");
        need(source != null && source.equals("ci/make_tashkeel_index.py"), "TSX_SOURCE");
        need(crcHex != null && crcHex.matches("[0-9a-f]{8}"), "TSX_CRC_FIELD");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        need((int) crc.getValue() == (int) Long.parseLong(crcHex, 16), "TSX_CRC");

        String[] lines = payload.split("\n", -1);
        need(lines.length >= 1 && lines[lines.length - 1].isEmpty(), "TSX_PAYLOAD");
        int n = lines.length - 1;
        need(n == entriesClaim, "TSX_ENTRIES_COUNT");
        String[][] fs = new String[n][];
        int covered = 0;
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            int t = line.indexOf('\t');
            need(t > 0 && line.indexOf('\t', t + 1) < 0, "TSX_LINE");   // exactly one tab
            String key = line.substring(0, t);
            need(!key.isEmpty() && Dictionary.norm(key).equals(key), "TSX_KEY");
            String[] parts = line.substring(t + 1).split(" ");
            need(parts.length >= 1, "TSX_FORMS");
            for (int j = 0; j < parts.length; j++) {
                need(!parts[j].isEmpty(), "TSX_FORMS");
                // the TSX_FORM fold law: every form folds back to its key
                need(Dictionary.norm(parts[j]).equals(key), "TSX_FORM");
            }
            covered += parts.length;
            fs[i] = parts;
            lines[i] = key;
        }
        need(covered == coveredClaim, "TSX_COVERED_COUNT");

        // the lookup spine: every key sorted once; a key under two lines is a
        // format breach (TSX_DUP) — never silently resolved
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final String[] fk = lines;
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b2) { return fk[a].compareTo(fk[b2]); }
        });
        String[] sk = new String[n];
        String[][] sf = new String[n][];
        for (int i = 0; i < n; i++) {
            sk[i] = fk[idx[i]];
            if (i > 0 && sk[i].equals(sk[i - 1])) throw new IllegalArgumentException("TSXENGINE TSX_DUP " + sk[i]);
            sf[i] = fs[idx[i]];
        }
        return new TashkeelEngine(sk, sf, covered);
    }

    private static int parseInt(String v, String tag) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("TSXENGINE " + tag); }
    }

    private static void need(boolean ok, String tag) {
        if (!ok) throw new IllegalArgumentException("TSXENGINE " + tag);
    }

    // ------------------------------------------------------------------ lookup

    private int indexOf(String word) {
        if (word == null || word.isEmpty()) return -1;
        String k = Dictionary.norm(word);
        if (k.isEmpty()) return -1;
        int lo = 0, hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = keys[mid].compareTo(k);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    /** True when the word has MORE than one attested reading (the AMBIG degree surface). */
    public boolean ambiguous(String word) {
        int i = indexOf(word);
        return i >= 0 && forms[i].length > 1;
    }

    /** A defensive copy of the attested forms (derivation order), empty when unmapped. */
    public String[] formsOf(String word) {
        int i = indexOf(word);
        if (i < 0) return new String[0];
        String[] f = forms[i];
        String[] out = new String[f.length];
        System.arraycopy(f, 0, out, 0, f.length);
        return out;
    }

    // ----------------------------------------------- the mode-gated static API

    private static volatile TashkeelEngine active;

    /** Attach the verified engine (the registry + this signal go live together). */
    public static void attach(TashkeelEngine e) { active = e; }
    /** Detach: every static lookup answers empty/null (the fail-closed state). */
    public static void detach() { active = null; }
    /** The attached engine, or null (the fail-closed state). */
    public static TashkeelEngine attached() { return active; }

    /**
     * The mode-gated attested readings of a word: OFF → empty; AMBIG → the
     * readings of a multi-reading word only; FULL → all readings of any
     * covered word. The FIRST reading is the tashkeel() pick — the whole-text
     * and strip surfaces take it; the long-press picker offers them all. An
     * uncovered word is never guessed. Null engine or OFF → empty (the
     * shipped-quiet state).
     */
    public static String[] variants(String word) {
        TashkeelEngine e = active;
        if (e == null || mode == MODE_OFF || word == null || word.isEmpty()) return new String[0];
        String[] fs = e.formsOf(word);
        if (mode == MODE_AMBIG && fs.length == 1) return new String[0];
        return fs;
    }

    // --------------------------------------------------- the W55 engine contract

    public String id() { return "tashkeel"; }

    /**
     * The progressive-tashkeel candidates for the composing word
     * (the typing-time feature): the mode-gated attested readings, best
     * (most attested) first. OFF, short or unmapped → empty.
     */
    public String[] run(Engine.Ctx ctx) {
        String w = ctx.composing;
        if (mode == MODE_OFF || w.length() < 2) return new String[0];
        if (mode == MODE_AMBIG && !ambiguous(w)) return new String[0];
        return formsOf(w);
    }

    // ------------------------------------------------------ the pure helpers

    /**
     * The typing-time ride: the first tashkeel candidate not already shown
     * APPENDS to the strip when there is room and NEVER displaces the
     * family tail (W56 owns the tail slot). Pure — JVM-tested.
     */
    public static void tashkeelInto(List<String> strip, Engine.Cand[] cands, int cap) {
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

    /**
     * The long-press reading picker as strip chips: the ✕ word chip first
     * (tapping it exits), then the mode-gated readings (tap to commit).
     * Pure — JVM-tested.
     */
    public static List<String> variantChips(String word, String[] forms) {
        List<String> out = new ArrayList<>();
        out.add("✕ " + (word == null ? "" : word) + " • قراءات التشكيل");
        int n = forms == null ? 0 : Math.min(forms.length, 8);
        for (int i = 0; i < n; i++) out.add(forms[i]);
        return out;
    }

    /**
     * Whole-text tashkeel (the ClipEdit tool surface): every maximal Arabic
     * run that the index covers (mode-gated) is replaced by its first
     * attested form; everything else — punctuation, digits, Latin, uncovered
     * words, already-diacritized spacing — passes through UNTOUCHED. An
     * uncovered word is never guessed. Null engine / OFF → the text
     * unchanged. Pure — JVM-tested.
     */
    public static String apply(String text, TashkeelEngine e, int m) {
        if (text == null || text.isEmpty() || e == null || m == MODE_OFF) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            boolean letter = (c >= '\u0621' && c <= '\u0652');
            if (!letter) { out.append(c); i++; continue; }
            int j = i;
            while (j < n) {
                char d = text.charAt(j);
                if (d >= '\u0621' && d <= '\u0652') j++;
                else break;
            }
            String run = text.substring(i, j);
            String[] fs = e.formsOf(run);
            out.append(fs.length == 0 || (m == MODE_AMBIG && fs.length == 1) ? run : fs[0]);
            i = j;
        }
        return out.toString();
    }
}
