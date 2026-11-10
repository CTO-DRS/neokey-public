package com.hark.neokey;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clipboard import / export (TXT, JSON, CSV). Pure Java, no Android imports, so it is tested on the JVM.
 * Imported files are data only: nothing in them is executed or interpreted as code.
 * JSON is the only format that keeps every property; TXT keeps text only, CSV keeps text + basic fields.
 */
final class ClipIO {

    static final String FORMAT = "neokey-clipboard";
    static final int SCHEMA = 1;
    static final int MAX_FILE_CHARS = 20 * 1024 * 1024;
    static final int MAX_ITEMS = 50_000;
    static final String TXT_SEP = "----- ✂ NeoKey -----";

    static final int SCOPE_ALL = 0, SCOPE_PINNED = 1, SCOPE_FAV = 2, SCOPE_TEMPLATES = 3, SCOPE_IDS = 4;
    static final int F_JSON = 0, F_TXT = 1, F_CSV = 2;

    /** One exported / imported item with its metadata. */
    static final class Rec {
        String text, title;
        boolean pinned, fav, archived;
        int pinOrder, kind;
        long created, modified, used;
        List<String> cats = new ArrayList<>();
    }

    // ================================================================ export

    static List<Rec> load(ClipRepo r, int scope, long[] ids) {
        String where;
        switch (scope) {
            case SCOPE_PINNED: where = "pinned=1"; break;
            case SCOPE_FAV: where = "fav=1"; break;
            case SCOPE_TEMPLATES: where = "kind=1"; break;
            case SCOPE_IDS: {
                StringBuilder b = new StringBuilder("id IN (");
                for (int i = 0; i < ids.length; i++) b.append(i == 0 ? "" : ",").append(ids[i]);
                where = ids.length == 0 ? "0" : b.append(')').toString();
                break;
            }
            default: where = "1";
        }
        Map<Long, List<String>> cats = new HashMap<>();
        for (Object[] row : r.db.query("SELECT x.clip_id, c.name FROM clip_cat x JOIN categories c ON c.id=x.cat_id ORDER BY c.ord, c.id")) {
            long id = ClipRepo.num(row[0]);
            List<String> l = cats.get(id);
            if (l == null) cats.put(id, l = new ArrayList<>());
            l.add((String) row[1]);
        }
        List<Rec> out = new ArrayList<>();
        for (Object[] row : r.db.query("SELECT id, text, title, pinned, pin_order, fav, archived, kind, created, modified, used FROM clips WHERE "
                + where + " ORDER BY kind, pinned DESC, CASE WHEN pinned=1 THEN pin_order ELSE 0 END, used DESC, id DESC")) {
            Rec c = new Rec();
            long id = ClipRepo.num(row[0]);
            c.text = (String) row[1];
            c.title = (String) row[2];
            c.pinned = ClipRepo.num(row[3]) == 1;
            c.pinOrder = (int) ClipRepo.num(row[4]);
            c.fav = ClipRepo.num(row[5]) == 1;
            c.archived = ClipRepo.num(row[6]) == 1;
            c.kind = (int) ClipRepo.num(row[7]);
            c.created = ClipRepo.num(row[8]);
            c.modified = ClipRepo.num(row[9]);
            c.used = ClipRepo.num(row[10]);
            if (cats.containsKey(id)) c.cats = cats.get(id);
            out.add(c);
        }
        return out;
    }

    static void write(int format, List<Rec> items, Writer w, long now, String appVersion) throws IOException {
        if (format == F_JSON) writeJson(items, w, now, appVersion);
        else if (format == F_CSV) writeCsv(items, w);
        else writeTxt(items, w);
        w.flush();
    }

    static void writeJson(List<Rec> items, Writer w, long now, String appVersion) throws IOException {
        Set<String> allCats = new java.util.LinkedHashSet<>();
        for (Rec c : items) allCats.addAll(c.cats);
        w.write("{\n  \"format\": \"" + FORMAT + "\",\n  \"schema\": " + SCHEMA + ",\n  \"exported\": ");
        str(w, iso(now));
        w.write(",\n  \"app\": ");
        str(w, "NeoKey " + appVersion);
        w.write(",\n  \"count\": " + items.size() + ",\n  \"categories\": [");
        int i = 0;
        for (String s : allCats) { if (i++ > 0) w.write(", "); str(w, s); }
        w.write("],\n  \"items\": [");
        for (int k = 0; k < items.size(); k++) {
            Rec c = items.get(k);
            w.write(k == 0 ? "\n    {" : ",\n    {");
            w.write("\"text\": "); str(w, c.text);
            w.write(", \"title\": "); if (c.title == null) w.write("null"); else str(w, c.title);
            w.write(", \"pinned\": " + c.pinned + ", \"pin_order\": " + c.pinOrder + ", \"favorite\": " + c.fav
                    + ", \"archived\": " + c.archived + ", \"template\": " + (c.kind == 1));
            w.write(", \"created\": "); str(w, iso(c.created));
            w.write(", \"modified\": "); str(w, iso(c.modified));
            w.write(", \"last_used\": "); str(w, iso(c.used));
            w.write(", \"categories\": [");
            for (int j = 0; j < c.cats.size(); j++) { if (j > 0) w.write(", "); str(w, c.cats.get(j)); }
            w.write("]}");
        }
        w.write(items.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
    }

    static void writeTxt(List<Rec> items, Writer w) throws IOException {
        for (int k = 0; k < items.size(); k++) {
            if (k > 0) w.write("\n\n" + TXT_SEP + "\n\n");
            w.write(escapeTxt(items.get(k).text));
        }
        if (!items.isEmpty()) w.write("\n");
    }

    /** A line equal to TXT_SEP inside an item's text would enter the parse
     *  split path (parseTxt guards on "\nSEP\n" or a leading SEP) and silently
     *  split the item into two on re-import — and because the split regex
     *  matches the bare separator substring with no context required, a mere
     *  prefix space is NOT enough. TXT escapes every occurrence by inserting a
     *  ZERO-WIDTH SPACE inside the dash run, breaking the literal match
     *  (visually identical, documented delta). JSON is the fidelity-preserving
     *  format and stays byte-exact. */
    static String escapeTxt(String text) {
        if (!text.contains(TXT_SEP)) return text;
        return text.replace(TXT_SEP, "-\u200B" + TXT_SEP.substring(1));
    }

    static final String[] CSV_HEAD = {"text", "title", "pinned", "favorite", "archived", "template", "pin_order", "created", "modified", "categories"};

    static void writeCsv(List<Rec> items, Writer w) throws IOException {
        w.write('\uFEFF');      // lets spreadsheet apps detect UTF-8 (Arabic stays readable)
        csvRow(w, CSV_HEAD);
        for (Rec c : items) {
            csvRow(w, new String[]{c.text, c.title == null ? "" : c.title, b(c.pinned), b(c.fav), b(c.archived), b(c.kind == 1),
                    String.valueOf(c.pinOrder), iso(c.created), iso(c.modified), join(" | ", c.cats)});
        }
    }

    private static String b(boolean v) { return v ? "1" : "0"; }

    private static void csvRow(Writer w, String[] cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) w.write(',');
            String s = cells[i];
            boolean q = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                    || (!s.isEmpty() && (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' '));
            if (q) w.write("\"" + s.replace("\"", "\"\"") + "\""); else w.write(s);
        }
        w.write("\r\n");
    }

    static String join(String sep, Iterable<String> xs) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) { if (b.length() > 0) b.append(sep); b.append(x); }
        return b.toString();
    }

    /** java.time exists on Android 8+ and every JVM; older phones fall back to SimpleDateFormat. */
    private static final boolean HAS_TIME = hasTime();
    private static boolean hasTime() { try { Class.forName("java.time.Instant"); return true; } catch (Throwable t) { return false; } }
    private static java.text.SimpleDateFormat isoFmt(String p) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f;
    }

    static String iso(long ms) {
        if (ms <= 0) return "";
        if (HAS_TIME) return java.time.Instant.ofEpochMilli(ms).toString();
        return isoFmt(ms % 1000 == 0 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new java.util.Date(ms));
    }

    static long parseTime(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return 0;
        if (HAS_TIME) { try { return java.time.Instant.parse(s).toEpochMilli(); } catch (RuntimeException e) { /* not ISO */ } }
        else for (String pt : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try { java.text.SimpleDateFormat f = isoFmt(pt); f.setLenient(false); return f.parse(s).getTime(); } catch (Exception e) { /* next */ }
        }
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static void str(Writer w, String s) throws IOException {
        w.write('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': w.write("\\\""); break;
                case '\\': w.write("\\\\"); break;
                case '\n': w.write("\\n"); break;
                case '\r': w.write("\\r"); break;
                case '\t': w.write("\\t"); break;
                default:
                    boolean lone = Character.isHighSurrogate(ch) ? !(i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1)))
                            : Character.isLowSurrogate(ch) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (ch < 0x20 || lone || ch == '\u2028' || ch == '\u2029') w.write(String.format("\\u%04x", (int) ch));
                    else w.write(ch);
            }
        }
        w.write('"');
    }

    // ================================================================ import: parse

    static final int NEW = 0, DUP = 1, DUP_IN_FILE = 2, INVALID = 3;

    static final class Cand {
        Rec rec;
        int status;
        String reason = "";
        long existing = -1;
        boolean selected;
    }

    static final class Parsed {
        int format = -1;
        int schema;
        String error;           // whole file rejected
        List<Cand> items = new ArrayList<>();
        int count(int status) { int n = 0; for (Cand c : items) if (c.status == status) n++; return n; }
        int selected() { int n = 0; for (Cand c : items) if (c.selected) n++; return n; }
    }

    /** Detects the format from the name and content, parses and validates. */
    static Parsed parse(String content, String name, boolean txtLines) {
        Parsed p = new Parsed();
        if (content == null) { p.error = "تعذّرت قراءة الملف"; return p; }
        if (content.length() > MAX_FILE_CHARS) { p.error = "الملف أكبر من 20 ميجا"; return p; }
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        String t = content.trim();
        if (n.endsWith(".json") || (!n.endsWith(".csv") && !n.endsWith(".txt") && (t.startsWith("{") || t.startsWith("[")))) {
            p.format = F_JSON;
            parseJson(content, p);
        } else if (n.endsWith(".csv")) {
            p.format = F_CSV;
            parseCsv(content, p);
        } else {
            p.format = F_TXT;
            parseTxt(content, txtLines, p);
        }
        if (p.error == null && p.items.size() > MAX_ITEMS) p.error = "الملف فيه أكثر من " + MAX_ITEMS + " عنصر";
        if (p.error == null && p.items.isEmpty()) p.error = "ما لقينا نصوص في الملف";
        if (p.error != null) p.items.clear();
        for (Cand c : p.items) {
            String why = validate(c.rec);
            if (why != null) { c.status = INVALID; c.reason = why; }
            c.selected = c.status != INVALID;
        }
        return p;
    }

    static String validate(Rec r) {
        if (r.text == null) return "بدون نص";
        if (r.text.trim().isEmpty()) return "نص فاضي";
        if (r.text.length() > ClipRepo.MAX_LEN) return "أطول من 500,000 حرف";
        if (r.title != null && r.title.length() > 200) r.title = r.title.substring(0, 200);
        return null;
    }

    private static Cand cand(Rec r) { Cand c = new Cand(); c.rec = r; return c; }

    static void parseTxt(String s, boolean lines, Parsed p) {
        String norm = s.replace("\r\n", "\n");
        List<String> parts = new ArrayList<>();
        if (norm.contains("\n" + TXT_SEP + "\n") || norm.startsWith(TXT_SEP)) {
            for (String part : norm.split("\n*" + java.util.regex.Pattern.quote(TXT_SEP) + "\n*", -1)) parts.add(part);
            // writeTxt adds one trailing newline after the last item
            int last = parts.size() - 1;
            if (last >= 0 && parts.get(last).endsWith("\n")) parts.set(last, parts.get(last).substring(0, parts.get(last).length() - 1));
        } else if (lines) {
            for (String l : norm.split("\n")) parts.add(l);
        } else {
            parts.add(norm.endsWith("\n") ? norm.substring(0, norm.length() - 1) : norm);
        }
        for (String part : parts) {
            if (part.trim().isEmpty()) continue;
            Rec r = new Rec();
            r.text = part;
            p.items.add(cand(r));
            if (p.items.size() > MAX_ITEMS) return;
        }
    }

    static void parseCsv(String s, Parsed p) {
        List<List<String>> rows;
        try { rows = csv(s); } catch (IllegalArgumentException e) { p.error = "ملف CSV غير صالح: " + e.getMessage(); return; }
        if (rows.isEmpty()) return;
        Map<String, Integer> col = new HashMap<>();
        List<String> head = rows.get(0);
        for (int i = 0; i < head.size(); i++) col.put(head.get(i).trim().toLowerCase(java.util.Locale.ROOT), i);
        int start = col.containsKey("text") ? 1 : 0;
        int ti = col.containsKey("text") ? col.get("text") : 0;
        for (int i = start; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isEmpty()) continue;    // blank line
            Rec r = new Rec();
            r.text = ti < row.size() ? row.get(ti) : null;
            if (start == 1) {
                String title = cell(row, col, "title");
                r.title = title == null || title.isEmpty() ? null : title;
                r.pinned = truthy(cell(row, col, "pinned"));
                r.fav = truthy(cell(row, col, "favorite"));
                r.archived = truthy(cell(row, col, "archived"));
                r.kind = truthy(cell(row, col, "template")) ? 1 : 0;
                try { r.pinOrder = Integer.parseInt(String.valueOf(cell(row, col, "pin_order")).trim()); } catch (NumberFormatException e) { r.pinOrder = 0; }
                r.created = parseTime(cell(row, col, "created"));
                r.modified = parseTime(cell(row, col, "modified"));
                String cs = cell(row, col, "categories");
                if (cs != null) for (String c : cs.split("\\|")) if (!c.trim().isEmpty()) r.cats.add(c.trim());
            }
            p.items.add(cand(r));
            if (p.items.size() > MAX_ITEMS) return;
        }
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? null : row.get(i);
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("نعم");
    }

    /** RFC 4180 reader: quoted cells may hold commas, quotes ("") and line breaks. */
    static List<List<String>> csv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQ = false, wasQ = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else inQ = false;
                } else cell.append(ch);
            } else if (ch == '"' && cell.length() == 0 && !wasQ) {
                inQ = wasQ = true;
            } else if (ch == ',') {
                row.add(cell.toString()); cell.setLength(0); wasQ = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); wasQ = false;
                rows.add(row); row = new ArrayList<>();
                if (rows.size() > MAX_ITEMS + 1) throw new IllegalArgumentException("صفوف كثيرة");
            } else {
                cell.append(ch);
            }
        }
        if (inQ) throw new IllegalArgumentException("علامة اقتباس ما انقفلت");
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    static void parseJson(String s, Parsed p) {
        Object root;
        try { root = new Json(s).parseRoot(); } catch (IllegalArgumentException e) { p.error = "ملف JSON غير صالح: " + e.getMessage(); return; }
        List<?> items;
        if (root instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) root;
            if (!FORMAT.equals(m.get("format"))) { p.error = "الملف مو نسخة حافظة من NeoKey"; return; }
            Object sc = m.get("schema");
            if (!(sc instanceof Number)) { p.error = "إصدار المخطط مفقود"; return; }
            p.schema = ((Number) sc).intValue();
            if (p.schema > SCHEMA || p.schema < 1) { p.error = "الملف من إصدار أحدث من التطبيق (مخطط " + p.schema + ")، حدّث NeoKey"; return; }
            if (!(m.get("items") instanceof List)) { p.error = "قائمة العناصر مفقودة"; return; }
            items = (List<?>) m.get("items");
        } else if (root instanceof List) {
            items = (List<?>) root;     // a plain array of strings or {text:…} objects
        } else { p.error = "بنية JSON غير متوقعة"; return; }
        if (items.size() > MAX_ITEMS) { p.error = "الملف فيه أكثر من " + MAX_ITEMS + " عنصر"; return; }
        for (Object o : items) {
            Rec r = new Rec();
            if (o instanceof String) r.text = (String) o;
            else if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                r.text = m.get("text") instanceof String ? (String) m.get("text") : null;
                r.title = m.get("title") instanceof String && !((String) m.get("title")).isEmpty() ? (String) m.get("title") : null;
                r.pinned = truthy(m.get("pinned"));
                r.fav = truthy(m.get("favorite"));
                r.archived = truthy(m.get("archived"));
                r.kind = truthy(m.get("template")) ? 1 : 0;
                r.pinOrder = m.get("pin_order") instanceof Number ? ((Number) m.get("pin_order")).intValue() : 0;
                r.created = parseTime(m.get("created"));
                r.modified = parseTime(m.get("modified"));
                r.used = parseTime(m.get("last_used"));
                if (m.get("categories") instanceof List)
                    for (Object c : (List<?>) m.get("categories")) if (c instanceof String && !((String) c).trim().isEmpty()) r.cats.add(((String) c).trim());
            }
            p.items.add(cand(r));
        }
    }

    /** Minimal strict JSON reader (objects, arrays, strings, numbers, true/false/null). Depth-limited. */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        Object parseRoot() {
            Object v = value(0);
            ws();
            if (i != s.length()) throw err("محتوى زائد بعد النهاية");
            return v;
        }
        IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " (عند الحرف " + i + ")"); }
        void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        Object value(int depth) {
            if (depth > 64) throw err("تداخل عميق");
            ws();
            if (i >= s.length()) throw err("نهاية غير متوقعة");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    if (!peek('"')) throw err("متوقع اسم حقل");
                    String k = string();
                    ws();
                    if (!peek(':')) throw err("متوقع :");
                    i++;
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek(',')) { i++; continue; }
                    if (peek('}')) { i++; return m; }
                    throw err("متوقع , أو }");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    l.add(value(depth + 1));
                    if (l.size() > MAX_ITEMS + 1) throw err("عناصر كثيرة");
                    ws();
                    if (peek(',')) { i++; continue; }
                    if (peek(']')) { i++; return l; }
                    throw err("متوقع , أو ]");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) {
                int st = i;
                i++;
                while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
                String num = s.substring(st, i);
                try {
                    if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) return Long.parseLong(num);
                    return Double.parseDouble(num);
                } catch (NumberFormatException e) { throw err("رقم غير صالح"); }
            }
            throw err("قيمة غير متوقعة");
        }
        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        String string() {
            i++;  // opening quote
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("نص ما انقفل");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("هروب ناقص");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("هروب ناقص");
                            try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException x) { throw err("هروب غير صالح"); }
                            i += 4;
                            break;
                        default: throw err("هروب غير صالح");
                    }
                } else if (c < 0x20) {
                    throw err("حرف تحكم داخل نص");
                } else b.append(c);
            }
        }
    }

    // ================================================================ import: duplicates + apply

    /** Marks items that already exist in the library, or repeat earlier in the same file. */
    static void markDuplicates(ClipRepo r, Parsed p) {
        Set<String> seen = new HashSet<>();
        for (Cand c : p.items) {
            if (c.status == INVALID) continue;
            String key = c.rec.kind + ":" + c.rec.text;
            long ex = findExisting(r, c.rec);
            if (ex > 0) { c.status = DUP; c.existing = ex; c.reason = "موجود"; }
            else if (!seen.add(key)) { c.status = DUP_IN_FILE; c.reason = "مكرر داخل الملف"; }
            else c.status = NEW;
            c.selected = true;
        }
    }

    static long findExisting(ClipRepo r, Rec rec) {
        List<Object[]> q = r.db.query("SELECT id FROM clips WHERE hash=? AND len=? AND kind=? AND text=? LIMIT 1",
                ClipRepo.hash(rec.text), rec.text.length(), rec.kind, rec.text);
        return q.isEmpty() ? -1 : ClipRepo.num(q.get(0)[0]);
    }

    static final int DUP_SKIP = 0, DUP_COPY = 1, DUP_REPLACE = 2;

    static final class Report {
        int added, replaced, skipped, invalid, unpinned;
        boolean failed;
        String error;
    }

    /**
     * Imports the selected items in ONE transaction. Any failure rolls everything back, so the library is
     * either fully updated or untouched. Existing data is only changed in DUP_REPLACE mode (metadata only;
     * the text is identical by definition of a duplicate).
     */
    static Report apply(ClipRepo r, List<Cand> items, int dupMode, long now) {
        Report rep = new Report();
        List<long[]> newPins = new ArrayList<>();     // {id, pinOrder from file, file index}
        r.db.begin();
        try {
            int idx = 0;
            for (Cand c : items) {
                idx++;
                if (!c.selected) continue;
                if (c.status == INVALID) { rep.invalid++; continue; }
                Rec x = c.rec;
                boolean dup = c.status == DUP || c.status == DUP_IN_FILE;
                if (dup && dupMode == DUP_SKIP) { rep.skipped++; continue; }
                if (dup && dupMode == DUP_REPLACE) {
                    if (c.status == DUP && c.existing > 0) {
                        long id = c.existing;
                        r.db.exec("UPDATE clips SET title=COALESCE(?, title), fav=?, archived=?, modified=? WHERE id=?",
                                x.title, x.fav ? 1 : 0, x.archived ? 1 : 0, now, id);
                        if (x.created > 0) r.db.exec("UPDATE clips SET created=? WHERE id=? AND created>?", x.created, id, x.created);
                        if (x.pinned) newPins.add(new long[]{id, x.pinOrder, idx});
                        addCats(r, id, x.cats);
                        rep.replaced++;
                    } else rep.skipped++;      // repeats inside the file have nothing reliable to replace
                    continue;
                }
                long t = x.created > 0 ? x.created : now;
                long id = r.db.insert("INSERT INTO clips(text,hash,len,title,created,modified,used,fav,archived,kind,norm) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        x.text, ClipRepo.hash(x.text), x.text.length(), x.title, t, x.modified > 0 ? x.modified : t,
                        x.used > 0 ? x.used : t, x.fav ? 1 : 0, x.archived ? 1 : 0, x.kind == 1 ? 1 : 0, ClipNorm.of(x.text));
                String back = r.getText(id);
                if (back == null || !back.equals(x.text)) throw new IllegalStateException("فشل التحقق من نص محفوظ");
                if (x.pinned && x.kind == 0) newPins.add(new long[]{id, x.pinOrder, idx});
                addCats(r, id, x.cats);
                rep.added++;
            }
            // pins keep their relative order from the file and go after the existing ones, up to the limit
            java.util.Collections.sort(newPins, (a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
            for (long[] pn : newPins) {
                List<Object[]> q = r.db.query("SELECT pinned FROM clips WHERE id=?", pn[0]);
                if (!q.isEmpty() && ClipRepo.num(q.get(0)[0]) == 1) continue;
                if (r.pinCount() >= ClipRepo.MAX_PINS) { rep.unpinned++; continue; }
                long last = r.db.query("SELECT COALESCE(MAX(pin_order),0) FROM clips WHERE pinned=1").isEmpty() ? 0
                        : ClipRepo.num(r.db.query("SELECT COALESCE(MAX(pin_order),0) FROM clips WHERE pinned=1").get(0)[0]);
                r.db.exec("UPDATE clips SET pinned=1, pin_order=? WHERE id=?", last + 1, pn[0]);
            }
            r.db.commit();
        } catch (RuntimeException e) {
            try { r.db.rollback(); } catch (RuntimeException ignored) { }
            Report f = new Report();
            f.failed = true;
            f.error = e.getMessage();
            return f;
        }
        return rep;
    }

    private static void addCats(ClipRepo r, long id, List<String> cats) {
        for (String c : cats) {
            long cid = r.addCategory(c);
            if (cid > 0) r.setCategory(id, cid, true);
        }
    }
}
