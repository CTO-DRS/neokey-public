package com.hark.neokey;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Week 17 (Phase 2, item D) — community dictionary enrichment pipeline, pure JVM.
 *
 * Contract (docs/COMMUNITY-DICT.md): every community batch arrives as one UTF-8
 * CSV file with the exact unified header
 *   word,diacritized,lemma,pos,gloss,contributor,license
 * The reviewer is STRICT and deterministic: each data row gets exactly one
 * verdict — null (accepted) or a refusal tag. The first failing rule wins.
 * Order of rules per row:
 *   ROW_FIELDS, EMPTY_WORD, HAS_TASHKEEL_IN_WORD, NOT_ARABIC, BAD_DIACRITICS,
 *   NO_DIACRITICS, DUPLICATE, FORBIDDEN, BAD_POS, BAD_LEMMA, BAD_GLOSS,
 *   BAD_CONTRIB, BAD_LICENSE
 * Whole-file refusals (BAD_ENCODING, BAD_HEADER) reject every data row with
 * that tag — a batch that violates the table format is not reviewed row-wise.
 *
 * Structural diacritics check: after stripping tashkeel from <diacritized> the
 * result must equal <word> character-for-character (catches missing marks AND
 * mismatched skeletons). A word with zero marks on its diacritized form is
 * NO_DIACRITICS — the community line exists to add vocalization.
 *
 * Duplicates are checked twice: inside the batch (after Arabic-insensitive
 * normalization, so ة/ه or أ/ا tricks cannot smuggle a second copy) and
 * against the installed dictionary's normalized word set.
 */
public final class CommunityDict {

    private CommunityDict() {}

    // ---------------------------------------------------------------- schema

    /** The unified table header — exact order, exact names. */
    public static final String[] HEADER = {
            "word", "diacritized", "lemma", "pos", "gloss", "contributor", "license"};

    /** Accepted part-of-speech values. */
    public static final String[] POS = {"noun", "verb", "adj", "adv", "particle", "proper", "phrase"};

    /** Accepted contribution licenses. */
    public static final String[] LICENSES = {"CC0-1.0", "CC-BY-4.0"};

    /** Minimum/maximum word length (letters excluding spaces). */
    static final int MIN_WORD = 2, MAX_WORD = 40;

    /** Burned-in forbidden-word list (profanity/spam) — matched after normAr. */
    static final String[] FORBIDDEN = {
            "كلب", "حمار", "غبي", "حقير", "تافه", "وسخ", "قذر", "خنزير",
            "زفت", "خرا", "لعنة", "سافل", "قمل", "جاهل",
            "http", "https", "www", "vkcom", "telegram", "whatsapp",
    };

    // ---------------------------------------------------------------- verdicts

    public static final String T_BAD_ENCODING = "BAD_ENCODING";
    public static final String T_BAD_HEADER   = "BAD_HEADER";
    public static final String T_ROW_FIELDS   = "ROW_FIELDS";
    public static final String T_EMPTY_WORD   = "EMPTY_WORD";
    public static final String T_TASHKEEL     = "HAS_TASHKEEL_IN_WORD";
    public static final String T_NOT_ARABIC   = "NOT_ARABIC";
    public static final String T_BAD_DIAC     = "BAD_DIACRITICS";
    public static final String T_NO_DIAC      = "NO_DIACRITICS";
    public static final String T_DUPLICATE    = "DUPLICATE";
    public static final String T_FORBIDDEN    = "FORBIDDEN";
    public static final String T_BAD_POS      = "BAD_POS";
    public static final String T_BAD_LEMMA    = "BAD_LEMMA";
    public static final String T_BAD_GLOSS    = "BAD_GLOSS";
    public static final String T_BAD_CONTRIB  = "BAD_CONTRIB";
    public static final String T_BAD_LICENSE  = "BAD_LICENSE";

    /** All refusal tags a single row can carry. */
    public static final String[] ROW_TAGS = {
            T_ROW_FIELDS, T_EMPTY_WORD, T_TASHKEEL, T_NOT_ARABIC, T_BAD_DIAC,
            T_NO_DIAC, T_DUPLICATE, T_FORBIDDEN, T_BAD_POS, T_BAD_LEMMA,
            T_BAD_GLOSS, T_BAD_CONTRIB, T_BAD_LICENSE};
    /** Whole-file refusal tags. */
    public static final String[] FILE_TAGS = {T_BAD_ENCODING, T_BAD_HEADER};

    // ---------------------------------------------------------------- model

    /** One reviewed CSV row. tag==null → accepted. */
    public static final class Row {
        public final int lineNo;
        public final int nFields;
        public final String word, diacritized, lemma, pos, gloss, contributor, license;
        public String tag;
        public long checkNanos;
        Row(int lineNo, String[] f) {
            this.lineNo = lineNo;
            this.nFields = (f == null) ? 0 : f.length;
            String[] t = new String[7];
            for (int i = 0; i < 7; i++) t[i] = (f != null && i < f.length) ? f[i] : null;
            this.word = t[0]; this.diacritized = t[1]; this.lemma = t[2];
            this.pos = t[3]; this.gloss = t[4]; this.contributor = t[5]; this.license = t[6];
        }
        public boolean accepted() { return tag == null; }
    }

    /** Review report for one batch. */
    public static final class Report {
        public final String batchId;
        public final List<Row> rows;
        public final int total, accepted, rejected;
        public final Map<String, Integer> tags;
        public final long totalNanos;
        public long p50Nanos;

        Report(String batchId, List<Row> rows, Map<String, Integer> tags, long totalNanos) {
            this.batchId = batchId;
            this.rows = rows;
            this.total = rows.size();
            int acc = 0;
            for (Row r : rows) if (r.accepted()) acc++;
            this.accepted = acc;
            this.rejected = total - acc;
            this.tags = tags;
            this.totalNanos = totalNanos;
        }

        /** Contributors (accepted rows only) sorted by count desc then name asc. */
        public Map<String, Integer> contributors() {
            Map<String, Integer> m = new HashMap<>();
            for (Row r : rows) if (r.accepted()) m.merge(r.contributor, 1, Integer::sum);
            List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort((a, b) -> !b.getValue().equals(a.getValue())
                    ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
            return out;
        }

        /** Thanks section for the release page (Markdown). */
        public String contributorsMd() {
            StringBuilder b = new StringBuilder();
            b.append("# شكر المساهمين\n\n");
            b.append("مفردات مقبولة في الدفعة `").append(batchId, 0, 12)
             .append("…` بعد الفحص الصارم.\n\n");
            Map<String, Integer> c = contributors();
            if (c.isEmpty()) { b.append("(لا مساهمين مقبولين في هذه الدفعة)\n"); return b.toString(); }
            b.append("| المساهم | مفردات مقبولة |\n|---|---|\n");
            for (Map.Entry<String, Integer> e : c.entrySet())
                b.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            return b.toString();
        }

        /** Deterministic JSON for the gate archive. */
        public String toJson() {
            StringBuilder b = new StringBuilder(256);
            b.append("{\"batchId\":\"").append(batchId).append('"');
            b.append(",\"total\":").append(total);
            b.append(",\"accepted\":").append(accepted);
            b.append(",\"rejected\":").append(rejected);
            b.append(",\"p50Us\":").append(p50Nanos / 1000);
            b.append(",\"totalMs\":").append(totalNanos / 1_000_000);
            b.append(",\"tags\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : new TreeMap<>(tags).entrySet()) {
                if (!first) b.append(',');
                b.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            b.append("},\"contributors\":{");
            first = true;
            for (Map.Entry<String, Integer> e : contributors().entrySet()) {
                if (!first) b.append(',');
                b.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            b.append("}}");
            return b.toString();
        }
    }

    /** Thrown on whole-file refusal (encoding/header). */
    public static final class BadCsv extends RuntimeException {
        public final String tag;
        BadCsv(String tag) { super(tag); this.tag = tag; }
    }

    // ---------------------------------------------------------------- entry

    /**
     * Review one community batch. installedNorms = normalized word set of the
     * shipping dictionary (build with {@link #normSetOf(java.util.List)}).
     */
    public static Report review(byte[] csv, Set<String> installedNorms) {
        long t0 = System.nanoTime();
        String batchId = sha256Hex(csv);
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> tags = new LinkedHashMap<>();
        List<String[]> records;
        try {
            records = parseCsv(csv);
        } catch (BadCsv e) {
            tags.put(e.tag, 1);
            // every row refused with the file-level tag; row count = data lines found
            int n = countDataLines(csv);
            for (int i = 0; i < n; i++) {
                Row r = new Row(i + 2, new String[]{"", "", "", "", "", "", ""});
                r.tag = e.tag;
                r.checkNanos = 0;
                rows.add(r);
            }
            Report rep = new Report(batchId, rows, tags, System.nanoTime() - t0);
            rep.p50Nanos = 0;
            return rep;
        }
        Set<String> seen = new java.util.HashSet<>();
        List<Long> nanos = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            Row r = new Row(i + 2, records.get(i));
            long s = System.nanoTime();
            r.tag = judge(r, installedNorms, seen);
            r.checkNanos = System.nanoTime() - s;
            nanos.add(r.checkNanos);
            if (r.accepted()) seen.add(normAr(r.word));
            rows.add(r);
            tags.merge(r.tag == null ? "ACCEPT" : r.tag, 1, Integer::sum);
        }
        Report rep = new Report(batchId, rows, tags, System.nanoTime() - t0);
        rep.p50Nanos = p50(nanos);
        return rep;
    }

    // ---------------------------------------------------------------- judge

    /** Strict ordered rules — first failure wins, null = accepted. */
    static String judge(Row r, Set<String> installedNorms, Set<String> seenInBatch) {
        String w = r.word;
        if (r.nFields != 7) return T_ROW_FIELDS;
        if (w == null || w.isEmpty()) return T_EMPTY_WORD;
        if (hasTashkeel(w)) return T_TASHKEEL;
        if (!isArabicWord(w)) return T_NOT_ARABIC;
        // structural diacritics match
        String d = r.diacritized == null ? "" : r.diacritized;
        if (stripDiac(d).equals(w)) {
            if (d.equals(w) || !hasTashkeel(d)) return T_NO_DIAC;
        } else {
            return T_BAD_DIAC;
        }
        // forbidden FIRST — a poisoned word must be reported as poisoned even
        // if it also duplicates something (security over dedup reporting)
        if (isForbidden(r.lemma) || isForbidden(r.gloss) || isForbidden(w) || isForbidden(d))
            return T_FORBIDDEN;
        String nw = normAr(w);
        if (seenInBatch.contains(nw)) return T_DUPLICATE;
        if (installedNorms != null && installedNorms.contains(nw)) return T_DUPLICATE;
        if (r.pos == null || !inList(POS, r.pos)) return T_BAD_POS;
        if (r.lemma == null || r.lemma.isEmpty() || !isArabicWord(stripDiac(r.lemma)))
            return T_BAD_LEMMA;
        if (r.gloss == null || r.gloss.length() < 2) return T_BAD_GLOSS;
        if (r.contributor == null || !r.contributor.matches("[A-Za-z0-9_.\\-\\u0621-\\u064A]{3,32}"))
            return T_BAD_CONTRIB;
        if (r.license == null || !inList(LICENSES, r.license)) return T_BAD_LICENSE;
        return null;
    }

    static boolean inList(String[] list, String v) {
        for (String s : list) if (s.equals(v)) return true;
        return false;
    }

    static boolean isForbidden(String s) {
        if (s == null) return false;
        String n = normAr(s);
        for (String f : FORBIDDEN) if (n.contains(f)) return true;
        return false;
    }

    // ---------------------------------------------------------------- arabic

    /** True if any tashkeel mark (0x064B..0x0652) present. */
    public static boolean hasTashkeel(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x064B && c <= 0x0652) return true;
        }
        return false;
    }

    /** Strip tashkeel only (keep letters, keep tatweel). */
    public static String stripDiac(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x064B && c <= 0x0652) continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Word shape: Arabic letters and single inner spaces only; 2..40 letters
     * after removing spaces; no tatweel (0x0640) — the surface form is bare.
     */
    public static boolean isArabicWord(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') return false;
        int letters = 0, prevSpace = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (prevSpace == i - 1) return false; // double space
                prevSpace = i;
                continue;
            }
            boolean arabic = (c >= 0x0621 && c <= 0x064A && c != 0x0640) || c == 0x0671;
            if (!arabic) return false;
            letters++;
            if (letters > MAX_WORD) return false;
        }
        return letters >= MIN_WORD;
    }

    /** Arabic-insensitive key (mirrors Dialect.normAr): tashkeel+tatweel gone, hamza/ta/alef folded. */
    public static String normAr(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x064B && c <= 0x0652) || c == 0x0640) continue;
            switch (c) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c = 'ا'; break;
                case 'ة': c = 'ه'; break;
                case 'ى': c = 'ي'; break;
                case 'ؤ': c = 'و'; break;
                case 'ئ': c = 'ي'; break;
                default: break;
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Normalized word set of an installed dictionary (one word per line). */
    public static Set<String> normSetOf(List<String> words) {
        Set<String> s = new java.util.HashSet<>();
        for (String w : words) { String n = normAr(w); if (!n.isEmpty()) s.add(n); }
        return s;
    }

    // ---------------------------------------------------------------- csv

    /**
     * Strict CSV: UTF-8 (strict decoder, no BOM), LF or CRLF, comma-separated,
     * double-quote escaping ("" inside quotes), no multi-line fields, exactly
     * the unified header, then data rows of exactly 7 fields.
     */
    public static List<String[]> parseCsv(byte[] bytes) {
        String text;
        try {
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
                throw new BadCsv(T_BAD_ENCODING);
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BadCsv(T_BAD_ENCODING);
        }
        if (text.isEmpty()) throw new BadCsv(T_BAD_HEADER);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] t = new String[lines.length - 1];
            System.arraycopy(lines, 0, t, 0, t.length);
            lines = t;
        }
        if (lines.length < 1) throw new BadCsv(T_BAD_HEADER);
        String[] head = splitCsv(lines[0]);
        if (head.length != HEADER.length) throw new BadCsv(T_BAD_HEADER);
        for (int i = 0; i < HEADER.length; i++)
            if (!HEADER[i].equals(head[i].trim())) throw new BadCsv(T_BAD_HEADER);
        List<String[]> out = new ArrayList<>(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue; // tolerate blank lines
            out.add(splitCsv(lines[i])); // field count checked at judge time (ROW_FIELDS)
        }
        return out;
    }

    private static int countDataLines(byte[] bytes) {
        try {
            List<String[]> r = parseLenient(bytes);
            return r.size();
        } catch (Exception e) {
            String t = new String(bytes, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
            if (t.isEmpty()) return 0;
            int n = 0;
            for (String line : t.split("\n", -1)) if (!line.isEmpty()) n++;
            return Math.max(0, n - 1); // minus header (best effort)
        }
    }

    /** Lenient parse for counting rows of a header-rejected batch. */
    private static List<String[]> parseLenient(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String[]> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) if (!lines[i].isEmpty()) out.add(splitCsv(lines[i]));
        return out;
    }

    /** Split one CSV line honoring double quotes with "" escape. */
    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>(8);
        StringBuilder cur = new StringBuilder();
        boolean inQ = false, fieldStarted = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(c);
            } else if (c == '"') {
                if (cur.length() == 0 && !fieldStarted) inQ = true;
                else cur.append('"');
                fieldStarted = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
                fieldStarted = false;
            } else {
                cur.append(c);
                fieldStarted = true;
            }
            i++;
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- util

    /** p50 of a list of durations (nearest-rank). */
    static long p50(List<Long> xs) {
        if (xs.isEmpty()) return 0;
        List<Long> c = new ArrayList<>(xs);
        Collections.sort(c);
        return c.get((c.size() - 1) / 2);
    }

    /** sha256 hex of bytes (lowercase). */
    public static String sha256Hex(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder s = new StringBuilder(64);
            for (byte x : d) s.append(Character.forDigit((x >> 4) & 0xF, 16))
                             .append(Character.forDigit(x & 0xF, 16));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
