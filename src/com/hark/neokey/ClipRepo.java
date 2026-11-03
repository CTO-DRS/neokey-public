package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Clipboard library logic. Pure Java over a tiny {@link Sql} interface so the same code
 * runs on Android SQLite (ClipStore) and on JDBC SQLite in plain-JVM tests.
 * Never logs or transmits clip content.
 */
final class ClipRepo {

    interface Sql {
        void exec(String sql, Object... args);
        long insert(String sql, Object... args);          // returns new rowid
        List<Object[]> query(String sql, Object... args);
        void begin();
        void commit();
        void rollback();
    }

    static final int SCHEMA = 2;
    static final int MAX_LEN = 500_000;
    static final int MAX_PINS = 100;
    static final int MAX_VERSIONS = 10;

    // add() results
    static final int OK = 0, BUMPED = 1, EMPTY = 2, TOO_LARGE = 3, VERIFY_FAIL = 4, ERROR = 5;
    // pin() results
    static final int PIN_OK = 0, PIN_ALREADY = 1, PIN_FULL = 2, PIN_MISSING = 3;

    static final String[] CREATE = {
        "CREATE TABLE IF NOT EXISTS clips(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "text TEXT NOT NULL," +
            "hash TEXT NOT NULL," +
            "len INTEGER NOT NULL," +
            "title TEXT," +
            "created INTEGER NOT NULL," +
            "modified INTEGER NOT NULL," +
            "used INTEGER NOT NULL," +
            "pinned INTEGER NOT NULL DEFAULT 0," +
            "pin_order INTEGER NOT NULL DEFAULT 0," +
            "fav INTEGER NOT NULL DEFAULT 0," +
            "archived INTEGER NOT NULL DEFAULT 0," +
            "kind INTEGER NOT NULL DEFAULT 0," +       // 0 clip, 1 template
            "shortcut TEXT," +
            "source TEXT," +
            "tags TEXT," +
            "norm TEXT)",
        "CREATE INDEX IF NOT EXISTS clips_hash ON clips(hash)",
        "CREATE INDEX IF NOT EXISTS clips_list ON clips(kind, archived, pinned, used)",
        "CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, ord INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS clip_cat(clip_id INTEGER NOT NULL, cat_id INTEGER NOT NULL, PRIMARY KEY(clip_id, cat_id))",
        "CREATE TABLE IF NOT EXISTS clip_versions(id INTEGER PRIMARY KEY AUTOINCREMENT, clip_id INTEGER NOT NULL, text TEXT NOT NULL, saved INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS versions_clip ON clip_versions(clip_id)",
    };

    /** Items that are "temporary": the retention policy may delete them. */
    private static final String TEMP = "pinned=0 AND fav=0 AND archived=0 AND kind=0 AND title IS NULL " +
            "AND id NOT IN (SELECT clip_id FROM clip_cat)";

    private static final String COLS = "id, substr(text,1," + ClipItem.PREVIEW + "), len, title, pinned, fav, archived, " +
            "pin_order, kind, created, modified, used";

    final Sql db;

    ClipRepo(Sql db) { this.db = db; }

    static void createSchema(Sql db) {
        for (String s : CREATE) db.exec(s);
    }

    // ------------------------------------------------------------ hashing

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(40);
            for (byte x : h) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "-" + s.length();
        }
    }

    // ------------------------------------------------------------ add / dedupe

    /** Adds a copied text. Out[0] receives the row id when one exists. */
    int add(String text, long now, long[] out) {
        if (text == null || text.isEmpty()) return EMPTY;
        if (text.trim().isEmpty()) return EMPTY;
        if (text.length() > MAX_LEN) return TOO_LARGE;
        String h = hash(text);
        try {
            long dup = findSame(text, h);
            if (dup > 0) {
                // policy: copying an existing text again moves it to the top, no second copy
                db.exec("UPDATE clips SET used=? WHERE id=?", now, dup);
                if (out != null) out[0] = dup;
                return BUMPED;
            }
            long id = db.insert("INSERT INTO clips(text,hash,len,created,modified,used,norm) VALUES(?,?,?,?,?,?,?)",
                    text, h, text.length(), now, now, now, ClipNorm.of(text));
            String back = getText(id);
            if (back == null || !back.equals(text)) {
                db.exec("DELETE FROM clips WHERE id=?", id);
                return VERIFY_FAIL;
            }
            if (out != null) out[0] = id;
            return OK;
        } catch (RuntimeException e) {
            return ERROR;
        }
    }

    long findSame(String text, String h) {
        List<Object[]> r = db.query("SELECT id FROM clips WHERE hash=? AND len=? AND kind=0 AND text=? LIMIT 1",
                h, text.length(), text);
        return r.isEmpty() ? -1 : num(r.get(0)[0]);
    }

    // ------------------------------------------------------------ retention

    /** Deletes temporary items beyond maxCount (newest kept) or older than maxAgeMs (0 = no age limit). */
    int prune(int maxCount, long maxAgeMs, long now) {
        int before = count("SELECT COUNT(*) FROM clips");
        if (maxAgeMs > 0) db.exec("DELETE FROM clips WHERE " + TEMP + " AND used<?", now - maxAgeMs);
        if (maxCount >= 0) {
            db.exec("DELETE FROM clips WHERE id IN (SELECT id FROM clips WHERE " + TEMP +
                    " ORDER BY used DESC, id DESC LIMIT -1 OFFSET " + maxCount + ")");
        }
        cleanOrphans();
        return before - count("SELECT COUNT(*) FROM clips");
    }

    /** "مسح السجل": removes temporary items only; pinned, favourite, archived, titled, categorised stay. */
    int clearTemporary() {
        int before = count("SELECT COUNT(*) FROM clips");
        db.exec("DELETE FROM clips WHERE " + TEMP);
        cleanOrphans();
        return before - count("SELECT COUNT(*) FROM clips");
    }

    /** Deletes every clip (not templates) including pinned. Caller must confirm with the user first. */
    int clearAllClips() {
        int before = count("SELECT COUNT(*) FROM clips WHERE kind=0");
        db.begin();
        try {
            db.exec("DELETE FROM clips WHERE kind=0");
            cleanOrphans();
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
        return before;
    }

    private void cleanOrphans() {
        db.exec("DELETE FROM clip_cat WHERE clip_id NOT IN (SELECT id FROM clips)");
        db.exec("DELETE FROM clip_versions WHERE clip_id NOT IN (SELECT id FROM clips)");
    }

    // ------------------------------------------------------------ reading

    /** Main list: pinned first (in their order), then the rest newest-used first. */
    List<ClipItem> list(int limit) {
        return items("SELECT " + COLS + " FROM clips WHERE kind=0 AND archived=0 " +
                "ORDER BY pinned DESC, CASE WHEN pinned=1 THEN pin_order ELSE 0 END ASC, used DESC, id DESC LIMIT " + limit);
    }

    static final int TAB_ALL = 0, TAB_PINNED = 1, TAB_FAV = 2, TAB_ARCHIVE = 3, TAB_TEMPLATES = 4, TAB_CATS = 5;

    private static String tabWhere(int tab) { return tabWhere(tab, 0); }

    private static String tabWhere(int tab, long cat) {
        switch (tab) {
            case TAB_CATS: return "kind=0 AND id IN (SELECT clip_id FROM clip_cat WHERE cat_id=" + cat + ")";
            case TAB_PINNED: return "kind=0 AND pinned=1";
            case TAB_FAV: return "kind=0 AND fav=1";
            case TAB_ARCHIVE: return "kind=0 AND archived=1";
            case TAB_TEMPLATES: return "kind=1";
            default: return "kind=0 AND archived=0";
        }
    }

    private static final String ORDER = " ORDER BY pinned DESC, CASE WHEN pinned=1 THEN pin_order ELSE 0 END ASC, used DESC, id DESC";

    /** One tab of the panel, pinned first. */
    List<ClipItem> list(int tab, int limit) { return list(tab, 0, limit); }

    List<ClipItem> list(int tab, long cat, int limit) {
        return items("SELECT " + COLS + " FROM clips WHERE " + tabWhere(tab, cat) + ORDER + " LIMIT " + limit);
    }

    List<ClipItem> search(int tab, String query, int limit) { return search(tab, 0, query, limit); }

    /**
     * Searches the whole text of every item in a tab (diacritics-, case- and alef-insensitive).
     * The preview of each hit is a snippet that starts shortly before the match.
     */
    List<ClipItem> search(int tab, long cat, String query, int limit) {
        String q = ClipNorm.of(query).trim();
        if (q.isEmpty()) return list(tab, cat, limit);
        List<ClipItem> out = items("SELECT id, substr(text, max(1, instr(norm, ?) - 40), " + (ClipItem.PREVIEW + 80) + "), len, title, pinned, fav, archived, " +
                "pin_order, kind, created, modified, used FROM clips WHERE " + tabWhere(tab, cat) +
                " AND (instr(norm, ?) > 0 OR instr(lower(COALESCE(title,'')), ?) > 0)" + ORDER + " LIMIT " + limit, q, q, q);
        for (ClipItem c : out) {
            int[] m = ClipNorm.find(c.preview, q);
            if (m == null) {
                // heavy tashkeel shifts positions between norm and text: scan forward in chunks
                String chunk = locate(c.id, q);
                if (chunk != null) { c.preview = chunk; m = ClipNorm.find(chunk, q); }
            }
            if (m != null) {
                int from = Math.max(0, m[0] - 20);
                if (from > 0 && Character.isLowSurrogate(c.preview.charAt(from))) from--;
                String pre = from > 0 ? "…" : "";
                c.preview = pre + c.preview.substring(from);
                c.matchStart = m[0] - from + pre.length();
                c.matchEnd = m[1] - from + pre.length();
            }
        }
        return out;
    }

    private String locate(long id, String q) {
        List<Object[]> r = db.query("SELECT instr(norm, ?), len FROM clips WHERE id=?", q, id);
        if (r.isEmpty()) return null;
        long pos = num(r.get(0)[0]), len = num(r.get(0)[1]);
        if (pos <= 0) return null;
        final int win = 20000;
        for (long st = Math.max(1, pos - 40); st <= len; st += win - 4L * q.length()) {
            List<Object[]> c = db.query("SELECT substr(text, " + st + ", " + win + ") FROM clips WHERE id=?", id);
            if (c.isEmpty()) return null;
            String chunk = (String) c.get(0)[0];
            int[] m = ClipNorm.find(chunk, q);
            if (m != null) {
                int from = Math.max(0, m[0] - 40);
                int to = Math.min(chunk.length(), from + ClipItem.PREVIEW + 80);
                return chunk.substring(from, to);
            }
        }
        return null;
    }

    int searchCount(int tab, String query) { return searchCount(tab, 0, query); }

    int searchCount(int tab, long cat, String query) {
        String q = ClipNorm.of(query).trim();
        if (q.isEmpty()) return 0;
        return count("SELECT COUNT(*) FROM clips WHERE " + tabWhere(tab, cat) +
                " AND (instr(norm, ?) > 0 OR instr(lower(COALESCE(title,'')), ?) > 0)", q, q);
    }

    /** Schema 1 → 2: adds the search column and fills it in batches. */
    static void upgradeTo2(Sql db) {
        try { db.exec("ALTER TABLE clips ADD COLUMN norm TEXT"); } catch (RuntimeException ignored) { /* already there */ }
        fillNorm(db);
    }

    static void fillNorm(Sql db) {
        while (true) {
            List<Object[]> rows = db.query("SELECT id, text FROM clips WHERE norm IS NULL LIMIT 50");
            if (rows.isEmpty()) return;
            for (Object[] r : rows) db.exec("UPDATE clips SET norm=? WHERE id=?", ClipNorm.of((String) r[1]), num(r[0]));
        }
    }

    /** Moves a pinned item up (-1) or down (+1) one place. */
    void movePinBy(long id, int delta) {
        List<ClipItem> p = pinnedList();
        for (int i = 0; i < p.size(); i++) if (p.get(i).id == id) { movePin(id, i + delta); return; }
    }

    List<ClipItem> pinnedList() {
        return items("SELECT " + COLS + " FROM clips WHERE pinned=1 ORDER BY pin_order ASC, id ASC");
    }

    ClipItem item(long id) {
        List<ClipItem> l = items("SELECT " + COLS + " FROM clips WHERE id=?", id);
        return l.isEmpty() ? null : l.get(0);
    }

    String getText(long id) {
        List<Object[]> r = db.query("SELECT text FROM clips WHERE id=?", id);
        return r.isEmpty() ? null : (String) r.get(0)[0];
    }

    int pinCount() { return count("SELECT COUNT(*) FROM clips WHERE pinned=1"); }
    int totalCount() { return count("SELECT COUNT(*) FROM clips"); }

    /** Approximate storage used by texts, in bytes (UTF-8). */
    long storageBytes() {
        List<Object[]> r = db.query("SELECT COALESCE(SUM(length(CAST(text AS BLOB))),0) FROM clips");
        return r.isEmpty() ? 0 : num(r.get(0)[0]);
    }

    void markUsed(long id, long now) { db.exec("UPDATE clips SET used=? WHERE id=?", now, id); }

    // ------------------------------------------------------------ pins

    int pin(long id) {
        List<Object[]> r = db.query("SELECT pinned FROM clips WHERE id=?", id);
        if (r.isEmpty()) return PIN_MISSING;
        if (num(r.get(0)[0]) == 1) return PIN_ALREADY;
        if (pinCount() >= MAX_PINS) return PIN_FULL;
        long next = num(db.query("SELECT COALESCE(MAX(pin_order),0)+1 FROM clips WHERE pinned=1").get(0)[0]);
        db.exec("UPDATE clips SET pinned=1, pin_order=? WHERE id=?", next, id);
        return PIN_OK;
    }

    void unpin(long id) { db.exec("UPDATE clips SET pinned=0, pin_order=0 WHERE id=?", id); }

    /** Unpins {@code drop} and pins {@code id} atomically (the "replace a pin" choice at 100/100). */
    int replacePin(long drop, long id) {
        db.begin();
        try {
            unpin(drop);
            int r = pin(id);
            if (r != PIN_OK && r != PIN_ALREADY) { db.rollback(); return r; }
            db.commit();
            return r;
        } catch (RuntimeException e) {
            db.rollback();
            return PIN_MISSING;
        }
    }

    /** The pin shown last in the pinned order (first candidate to give up at the limit). */
    long lastPin() {
        List<Object[]> r = db.query("SELECT id FROM clips WHERE pinned=1 ORDER BY pin_order DESC, id DESC LIMIT 1");
        return r.isEmpty() ? -1 : num(r.get(0)[0]);
    }

    /** Moves a pinned item to position {@code index} (0-based) and renumbers all pins. */
    void movePin(long id, int index) {
        db.begin();
        try {
            List<Long> ids = new ArrayList<>();
            for (Object[] row : db.query("SELECT id FROM clips WHERE pinned=1 ORDER BY pin_order ASC, id ASC")) ids.add(num(row[0]));
            if (!ids.remove(Long.valueOf(id))) { db.rollback(); return; }
            index = Math.max(0, Math.min(index, ids.size()));
            ids.add(index, id);
            for (int i = 0; i < ids.size(); i++) db.exec("UPDATE clips SET pin_order=? WHERE id=?", i + 1, ids.get(i));
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    // ------------------------------------------------------------ flags & delete

    void setFav(long id, boolean on) { db.exec("UPDATE clips SET fav=? WHERE id=?", on ? 1 : 0, id); }
    void setArchived(long id, boolean on) { db.exec("UPDATE clips SET archived=? WHERE id=?", on ? 1 : 0, id); }
    void setTitle(long id, String title, long now) {
        String t = title == null || title.trim().isEmpty() ? null : title.trim();
        db.exec("UPDATE clips SET title=?, modified=? WHERE id=?", t, now, id);
    }

    void delete(long id) {
        db.begin();
        try {
            db.exec("DELETE FROM clips WHERE id=?", id);
            cleanOrphans();
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    // ------------------------------------------------------------ editing

    /** Saves edited text in place, keeping the previous text as a version (max MAX_VERSIONS). */
    int updateText(long id, String text, long now) {
        if (text == null || text.isEmpty()) return EMPTY;
        if (text.length() > MAX_LEN) return TOO_LARGE;
        String old = getText(id);
        if (old == null) return ERROR;
        if (old.equals(text)) return OK;
        db.begin();
        try {
            db.insert("INSERT INTO clip_versions(clip_id,text,saved) VALUES(?,?,?)", id, old, now);
            db.exec("DELETE FROM clip_versions WHERE clip_id=? AND id NOT IN (SELECT id FROM clip_versions WHERE clip_id=? ORDER BY id DESC LIMIT " + MAX_VERSIONS + ")", id, id);
            db.exec("UPDATE clips SET text=?, hash=?, len=?, modified=?, norm=? WHERE id=?", text, hash(text), text.length(), now, ClipNorm.of(text), id);
            if (!text.equals(getText(id))) { db.rollback(); return VERIFY_FAIL; }
            db.commit();
            return OK;
        } catch (RuntimeException e) {
            db.rollback();
            return ERROR;
        }
    }

    int versionCount(long id) { return count("SELECT COUNT(*) FROM clip_versions WHERE clip_id=?", id); }

    /** Restores the newest saved version; the current text becomes a version itself. */
    int restorePrevious(long id, long now) {
        List<Object[]> r = db.query("SELECT id, text FROM clip_versions WHERE clip_id=? ORDER BY id DESC LIMIT 1", id);
        if (r.isEmpty()) return ERROR;
        long vid = num(r.get(0)[0]);
        String prev = (String) r.get(0)[1];
        int res = updateText(id, prev, now);
        if (res == OK) db.exec("DELETE FROM clip_versions WHERE id=?", vid);
        return res;
    }

    // ------------------------------------------------------------ legacy migration

    /**
     * Moves the old SharedPreferences history (newest first) into the database in one transaction.
     * Returns the number of rows created, or -1 on failure (nothing is written then).
     */
    int migrateLegacy(List<String> clips, Collection<String> pins, long now) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String s : clips) if (s != null && !s.trim().isEmpty()) uniq.add(s);
        for (String s : pins) if (s != null && !s.trim().isEmpty()) uniq.add(s);
        List<String> all = new ArrayList<>(uniq);
        db.begin();
        try {
            int pinOrder = 0, made = 0;
            for (int i = all.size() - 1; i >= 0; i--) {
                String s = all.get(i);
                if (findSame(s, hash(s)) > 0) continue;
                long t = now - i;   // keeps the old order: index 0 newest
                db.insert("INSERT INTO clips(text,hash,len,created,modified,used,norm) VALUES(?,?,?,?,?,?,?)",
                        s, hash(s), s.length(), t, t, t, ClipNorm.of(s));
                made++;
            }
            for (String s : pins) {
                long id = findSame(s, hash(s));
                if (id > 0 && pinOrder < MAX_PINS) db.exec("UPDATE clips SET pinned=1, pin_order=? WHERE id=?", ++pinOrder, id);
            }
            for (String s : all) if (findSame(s, hash(s)) <= 0) { db.rollback(); return -1; }
            db.commit();
            return made;
        } catch (RuntimeException e) {
            db.rollback();
            return -1;
        }
    }

    // ------------------------------------------------------------ categories

    static final class Cat {
        long id; String name; int count;
        Cat(long id, String name, int count) { this.id = id; this.name = name; this.count = count; }
    }

    static final int MAX_CAT_NAME = 40;

    /** Creates a category (or returns the existing one with that name). -1 for an empty name. */
    long addCategory(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return -1;
        if (n.length() > MAX_CAT_NAME) n = n.substring(0, MAX_CAT_NAME);
        List<Object[]> r = db.query("SELECT id FROM categories WHERE name=?", n);
        if (!r.isEmpty()) return num(r.get(0)[0]);
        int ord = count("SELECT COALESCE(MAX(ord),0)+1 FROM categories");
        return db.insert("INSERT INTO categories(name, ord) VALUES(?,?)", n, ord);
    }

    boolean renameCategory(long id, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || !db.query("SELECT id FROM categories WHERE name=? AND id<>?", n, id).isEmpty()) return false;
        db.exec("UPDATE categories SET name=? WHERE id=?", n.length() > MAX_CAT_NAME ? n.substring(0, MAX_CAT_NAME) : n, id);
        return true;
    }

    /** Removes a category; its texts stay. */
    void deleteCategory(long id) {
        db.exec("DELETE FROM clip_cat WHERE cat_id=?", id);
        db.exec("DELETE FROM categories WHERE id=?", id);
    }

    List<Cat> categories() {
        List<Cat> out = new ArrayList<>();
        for (Object[] r : db.query("SELECT c.id, c.name, (SELECT COUNT(*) FROM clip_cat x JOIN clips k ON k.id=x.clip_id " +
                "WHERE x.cat_id=c.id AND k.kind=0) FROM categories c ORDER BY c.ord, c.id"))
            out.add(new Cat(num(r[0]), (String) r[1], (int) num(r[2])));
        return out;
    }

    void setCategory(long clipId, long catId, boolean on) {
        if (on) db.exec("INSERT OR IGNORE INTO clip_cat(clip_id, cat_id) VALUES(?,?)", clipId, catId);
        else db.exec("DELETE FROM clip_cat WHERE clip_id=? AND cat_id=?", clipId, catId);
    }

    java.util.Set<Long> categoriesOf(long clipId) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Object[] r : db.query("SELECT cat_id FROM clip_cat WHERE clip_id=?", clipId)) out.add(num(r[0]));
        return out;
    }

    // ------------------------------------------------------------ templates

    /** Saves a copy of a clip as a template (kind=1). Returns the template id; an identical template is reused. */
    long makeTemplate(long id, long now) {
        String t = getText(id);
        if (t == null) return -1;
        String h = hash(t);
        List<Object[]> r = db.query("SELECT id FROM clips WHERE kind=1 AND hash=? AND len=? AND text=? LIMIT 1", h, t.length(), t);
        if (!r.isEmpty()) return num(r.get(0)[0]);
        ClipItem it = item(id);
        return db.insert("INSERT INTO clips(text,hash,len,created,modified,used,kind,title,norm) VALUES(?,?,?,?,?,?,1,?,?)",
                t, h, t.length(), now, now, now, it == null ? null : it.title, ClipNorm.of(t));
    }

    // ------------------------------------------------------------ helpers

    private List<ClipItem> items(String sql, Object... args) {
        List<ClipItem> out = new ArrayList<>();
        for (Object[] r : db.query(sql, args)) {
            ClipItem c = new ClipItem();
            c.id = num(r[0]);
            c.preview = (String) r[1];
            c.len = (int) num(r[2]);
            c.title = (String) r[3];
            c.pinned = num(r[4]) == 1;
            c.fav = num(r[5]) == 1;
            c.archived = num(r[6]) == 1;
            c.pinOrder = (int) num(r[7]);
            c.kind = (int) num(r[8]);
            c.created = num(r[9]);
            c.modified = num(r[10]);
            c.used = num(r[11]);
            out.add(c);
        }
        return out;
    }

    private int count(String sql, Object... args) {
        List<Object[]> r = db.query(sql, args);
        return r.isEmpty() ? 0 : (int) num(r.get(0)[0]);
    }

    static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.parseLong(o.toString());
    }
}
