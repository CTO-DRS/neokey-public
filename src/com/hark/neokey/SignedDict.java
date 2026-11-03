package com.hark.neokey;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Week 19 (Phase 3, week 2) — the SIGNED merge of the community dictionary,
 * pure JVM, zero android.*.
 *
 * This closes the promise written in docs/COMMUNITY-DICT.md §4/§5 and in the
 * Phase-2/week-5 report §6: "الدمج الموقّع في حزمة قاموس مشحونة خطوة نشر
 * منفصلة". The separate publishing step becomes a MEASURED pipeline stage:
 *   publish(reviewed CSV) → one canonical pack asset (pinned) → the engine
 *   consumes it AFTER the installed dictionary, BEFORE user words (order
 *   proven by test, see docs/SIGNED-DICT.md).
 *
 * Two independent traps, both enforced here:
 *  1) PIN (post-review tampering): the pack carries bodySha256 = sha256 over
 *     the CANONICAL re-serialization of its words array. Publishing and
 *     verifying share ONE serializer, so any value flip breaks the pin.
 *     A different JSON escaping cannot smuggle content — values re-parse
 *     identically and every row is re-judged anyway (trap 2).
 *  2) RE-JUDGE (review bypass): verify() re-runs the SAME judge
 *     (CommunityDict.judge — no second implementation, no divergence) on every
 *     row with an in-pack seen-set. A hand-crafted pack with a perfectly
 *     recomputed pin but a poisoned/unreviewed row is REFUSED with the row's
 *     own tag (FORBIDDEN, BAD_LICENSE, BAD_DIACRITICS, ...).
 *
 * Duplicate handling is split by threat:
 *  - duplicate INSIDE the pack → pack refused (forge signature);
 *  - duplicate against the installed dictionary → SKIP at merge time, not a
 *    refusal (a corpus that grows between releases must not brick an old
 *    pack; the danger is NEW content, not repeated content — documented).
 *
 * Pack shape (canonical, fixed key order, no whitespace):
 *   {"schema":"neokey-community-1","batchId":"<sha256hex of source CSV>",
 *    "accepted":N,"words":[[w,d,l,pos,g,c,lic],...],"bodySha256":"<hex>"}
 */
public final class SignedDict {

    private SignedDict() {}

    // ---------------------------------------------------------------- schema

    public static final String SCHEMA = "neokey-community-1";

    // pack-level refusal tags (row tags come from CommunityDict itself)
    public static final String T_SCHEMA  = "SCHEMA";
    public static final String T_JSON    = "JSON";
    public static final String T_BATCH   = "BATCH";
    public static final String T_COUNT   = "COUNT";
    public static final String T_PIN     = "PIN";
    public static final String T_EMPTY   = "EMPTY_PACK";
    public static final String T_NOTHING = "NOTHING_ACCEPTED";

    /** All pack-level tags (the gate iterates this for the negative matrix). */
    public static final String[] PACK_TAGS = {
            T_SCHEMA, T_JSON, T_BATCH, T_COUNT, T_PIN, T_EMPTY, T_NOTHING};

    // ---------------------------------------------------------------- model

    /** One accepted word inside a verified pack (the 7 unified-table fields). */
    public static final class Entry {
        public final String word, diacritized, lemma, pos, gloss, contributor, license;
        Entry(String w, String d, String l, String p, String g, String c, String lic) {
            word = w; diacritized = d; lemma = l; pos = p; gloss = g;
            contributor = c; license = lic;
        }
    }

    /** A verified pack — immutable, rows in pack order. */
    public static final class Pack {
        public final String batchId;
        public final int claimedAccepted;
        public final List<Entry> rows;
        public final String bodySha256;
        Pack(String batchId, int claimedAccepted, List<Entry> rows, String bodySha256) {
            this.batchId = batchId; this.claimedAccepted = claimedAccepted;
            this.rows = rows; this.bodySha256 = bodySha256;
        }
        public int size() { return rows.size(); }
    }

    /** Result of publishing one reviewed batch. */
    public static final class PublishResult {
        public final CommunityDict.Report report;
        public final Pack pack;
        public final byte[] bytes;      // exact canonical asset bytes
        public final String packSha256; // sha256 of the whole file (evidence pin)
        PublishResult(CommunityDict.Report r, Pack p, byte[] b, String s) {
            report = r; pack = p; bytes = b; packSha256 = s;
        }
    }

    /** Publish refused (nothing usable in the batch). */
    public static final class PublishRefused extends RuntimeException {
        public final String tag;
        PublishRefused(String tag) { super(tag); this.tag = tag; }
    }

    /** Verify refused — carries the exact tag. */
    public static final class Refused extends RuntimeException {
        public final String tag;
        Refused(String tag) { super(tag); this.tag = tag; }
    }

    /**
     * Deterministic merge plan (docs/SIGNED-DICT.md §4): the engine consumes
     * community words AFTER the installed corpus, in pack order, norm-deduped
     * against the corpus AND against already-planned words (first wins).
     */
    public static final class MergePlan {
        public final List<String> add;     // surface words to append, pack order
        public final List<String> skipped; // norm-duplicates vs corpus/plan
        MergePlan(List<String> a, List<String> s) { add = a; skipped = s; }
    }

    // ---------------------------------------------------------------- publish

    /**
     * The publishing step, fully mechanical and measured: review the batch
     * STRICTLY (the publisher never trusts a verdict it did not compute),
     * refuse an all-rejected batch, then emit the canonical pinned pack.
     */
    public static PublishResult publish(byte[] csv, Set<String> installedNorms) {
        CommunityDict.Report rep = CommunityDict.review(csv, installedNorms);
        if (rep.accepted == 0) throw new PublishRefused(T_NOTHING);
        List<Entry> rows = new ArrayList<>(rep.accepted);
        for (CommunityDict.Row r : rep.rows) {
            if (!r.accepted()) continue;
            rows.add(new Entry(r.word, r.diacritized, r.lemma, r.pos,
                    r.gloss, r.contributor, r.license));
        }
        String body = wordsJson(rows);
        String bodySha = CommunityDict.sha256Hex(utf8(body));
        StringBuilder b = new StringBuilder(64 + body.length());
        b.append("{\"schema\":\"").append(SCHEMA).append('"');
        b.append(",\"batchId\":\"").append(rep.batchId).append('"');
        b.append(",\"accepted\":").append(rows.size());
        b.append(",\"words\":").append(body);
        b.append(",\"bodySha256\":\"").append(bodySha).append("\"}");
        byte[] bytes = utf8(b.toString());
        Pack pack = new Pack(rep.batchId, rows.size(), rows, bodySha);
        return new PublishResult(rep, pack, bytes, CommunityDict.sha256Hex(bytes));
    }

    // ---------------------------------------------------------------- verify

    /**
     * Verify one pack asset. installedNorms may be null (dictionary not loaded
     * yet); duplicates against the installed corpus are a MERGE skip, never a
     * verify refusal. Whole pack refused on the first hard failure.
     */
    public static Pack verify(byte[] raw, Set<String> installedNorms) {
        String text = strictUtf8(raw); // BAD encoding → JSON (shape, not row content)
        Parsed p = parse(text);

        if (!SCHEMA.equals(p.schema)) throw new Refused(T_SCHEMA);
        if (p.batchId == null || !p.batchId.matches("[0-9a-f]{64}"))
            throw new Refused(T_BATCH);
        if (p.accepted < 0 || p.accepted != p.words.size())
            throw new Refused(T_COUNT);
        if (p.words.isEmpty()) throw new Refused(T_EMPTY);

        // PIN over the canonical re-serialization (single serializer rule):
        // re-serialize the parsed words, hash, compare against the stored
        // digest — a flipped value (or reordered row) changes the body, the
        // digest no longer matches, the whole pack is refused.
        String recomputedBody = wordsJsonFromParsed(p.words);
        String recomputedSha = CommunityDict.sha256Hex(utf8(recomputedBody));
        if (!recomputedSha.equals(p.bodySha256)) throw new Refused(T_PIN);

        // RE-JUDGE every row with the SAME reviewer — review bypass is caught
        Set<String> seenInPack = new HashSet<>();
        List<Entry> rows = new ArrayList<>(p.words.size());
        for (String[] f : p.words) {
            CommunityDict.Row r = new CommunityDict.Row(0, f);
            String tag = CommunityDict.judge(r, null, seenInPack);
            if (tag != null) throw new Refused(tag);
            seenInPack.add(CommunityDict.normAr(r.word));
            rows.add(new Entry(r.word, r.diacritized, r.lemma, r.pos,
                    r.gloss, r.contributor, r.license));
        }
        return new Pack(p.batchId, p.accepted, rows, p.bodySha256);
    }

    // ---------------------------------------------------------------- merge

    /** Corpus first, pack second — norm-dedup, first wins. */
    public static MergePlan mergePlan(List<String> corpus, Pack pack) {
        Set<String> taken = new HashSet<>();
        if (corpus != null)
            for (String w : corpus) taken.add(CommunityDict.normAr(w));
        List<String> add = new ArrayList<>(), skip = new ArrayList<>();
        for (Entry e : pack.rows) {
            String n = CommunityDict.normAr(e.word);
            if (n.isEmpty() || taken.contains(n)) skip.add(e.word);
            else { taken.add(n); add.add(e.word); }
        }
        return new MergePlan(add, skip);
    }

    // ------------------------------------------------- canonical serializer

    /** Canonical words-array serialization (publish and verify share this). */
    static String wordsJson(List<Entry> rows) {
        StringBuilder b = new StringBuilder(64 * rows.size() + 2);
        b.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) b.append(',');
            Entry e = rows.get(i);
            b.append('[')
             .append(str(e.word)).append(',')
             .append(str(e.diacritized)).append(',')
             .append(str(e.lemma)).append(',')
             .append(str(e.pos)).append(',')
             .append(str(e.gloss)).append(',')
             .append(str(e.contributor)).append(',')
             .append(str(e.license)).append(']');
        }
        return b.append(']').toString();
    }

    private static String wordsJsonFromParsed(List<String[]> rows) {
        StringBuilder b = new StringBuilder(64 * rows.size() + 2);
        b.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) b.append(',');
            String[] f = rows.get(i);
            b.append('[');
            for (int j = 0; j < 7; j++) {
                if (j > 0) b.append(',');
                b.append(str(f[j]));
            }
            b.append(']');
        }
        return b.append(']').toString();
    }

    /** Canonical JSON string: only " \ and \n escaped; controls refused upstream. */
    static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                default:   b.append(c); break;
            }
        }
        return b.append('"').toString();
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String strictUtf8(byte[] raw) {
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new Refused(T_JSON);
        }
    }

    // ----------------------------------------------------- strict JSON parse

    /** Minimal strict parse of the fixed pack shape — no dups, no controls, fixed key order. */
    static Parsed parse(String text) {
        try {
            Px x = new Px(text);
            x.ws();
            x.expect('{');
            Parsed p = new Parsed();
            int lastKey = -1; // enforce canonical order: schema(0) batchId(1) accepted(2) words(3) bodySha256(4)
            while (true) {
                x.ws();
                String key = x.string();
                int ki = keyIndex(key);
                if (ki < 0 || ki <= lastKey) throw new Refused(T_JSON); // unknown or out of order
                lastKey = ki;
                x.ws(); x.expect(':'); x.ws();
                switch (ki) {
                    case 0: p.schema = x.string(); break;
                    case 1: p.batchId = x.string(); break;
                    case 2: p.accepted = x.intVal(); break;
                    case 3: p.words = x.wordsArray(); break;
                    case 4: p.bodySha256 = x.string(); break;
                    default: throw new Refused(T_JSON);
                }
                x.ws();
                char c = x.next();
                if (c == ',') continue;
                if (c == '}') break;
                throw new Refused(T_JSON);
            }
            x.ws();
            if (!x.eof()) throw new Refused(T_JSON); // trailing garbage
            if (p.schema == null || p.batchId == null || p.words == null
                    || p.bodySha256 == null) throw new Refused(T_JSON);
            return p;
        } catch (Refused e) {
            throw e;
        } catch (Exception e) {
            throw new Refused(T_JSON);
        }
    }

    static final class Parsed {
        String schema, batchId, bodySha256;
        int accepted = -1;
        List<String[]> words;
    }

    static int keyIndex(String k) {
        if ("schema".equals(k)) return 0;
        if ("batchId".equals(k)) return 1;
        if ("accepted".equals(k)) return 2;
        if ("words".equals(k)) return 3;
        if ("bodySha256".equals(k)) return 4;
        return -1;
    }

    /** Char scanner with strict JSON strings (controls refused, standard escapes). */
    static final class Px {
        private final String s; private int i;
        Px(String s) { this.s = s; }
        boolean eof() { return i >= s.length(); }
        char next() { if (eof()) throw new Refused(T_JSON); return s.charAt(i++); }
        void expect(char c) { if (next() != c) throw new Refused(T_JSON); }
        void ws() { while (!eof() && s.charAt(i) == ' ') i++; }
        String string() {
            expect('"');
            StringBuilder b = new StringBuilder(16);
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': case 'f': throw new Refused(T_JSON); // not in our canonical form
                        case 'u':
                            int v = 0;
                            for (int k = 0; k < 4; k++) {
                                char h = next();
                                int d = Character.digit(h, 16);
                                if (d < 0) throw new Refused(T_JSON);
                                v = (v << 4) | d;
                            }
                            b.append((char) v);
                            break;
                        default: throw new Refused(T_JSON);
                    }
                } else if (c < 0x20) {
                    throw new Refused(T_JSON); // raw control char
                } else {
                    b.append(c);
                }
            }
        }
        int intVal() {
            int start = i;
            while (!eof() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            if (i == start) throw new Refused(T_JSON);
            return Integer.parseInt(s.substring(start, i));
        }
        List<String[]> wordsArray() {
            expect('[');
            List<String[]> out = new ArrayList<>();
            xws();
            if (peek() == ']') { next(); return out; }
            while (true) {
                xws(); expect('[');
                String[] f = new String[7];
                for (int j = 0; j < 7; j++) {
                    xws(); f[j] = string(); xws();
                    if (j < 6) expect(',');
                }
                xws(); expect(']');
                out.add(f);
                xws();
                char c = next();
                if (c == ',') continue;
                if (c == ']') break;
                throw new Refused(T_JSON);
            }
            return out;
        }
        private char peek() { if (eof()) throw new Refused(T_JSON); return s.charAt(i); }
        private void xws() { while (!eof() && s.charAt(i) == ' ') i++; }
    }
}
