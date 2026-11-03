package com.hark.neokey;

/**
 * Theme-pack review line (roadmap Phase 2, item B — متجر السمات وخط المراجعة).
 *
 * Pure JVM, zero android.*: the automated reviewer the store runs BEFORE any
 * theme pack (.neokey JSON, exactly the Theme.exportPack shape) is published.
 * The client (Theme.importPack) stays tolerant; the STORE is the strict side —
 * a pack either passes every rule here or never reaches a user.
 *
 * Contract (docs/STORE.md):
 *   bytes   ≤ 64 KB (a theme is metadata; bigger = smuggling)
 *   strict JSON — no duplicate keys, no raw control chars, no trailing garbage,
 *                 depth ≤ 8, root object
 *   top-level keys on an explicit allowlist ONLY (catches planted api keys /
 *   webhook urls / tracking fields — anything not in pack format v1)
 *   neokey==1, app=="NeoKey" when present
 *   vendor declared AND in the founders registry (مورد غير معلن = رفض)
 *   Arabic copy quality: name 1..40 chars, no bidi controls (U+202A..202E,
 *   U+2066..2069, U+200E/200F), no tatweel, no tashkeel in a theme name,
 *   no leading/trailing whitespace
 *   colours parse as hex; bg alpha ≥ 0x40
 *   ranges: font 0..5, anim -1..29, borderW 0.5..8, layout ints bounded
 *   contrast (WCAG ratio, translucent keys composited over bg) ≥ 3.0
 *   neon packs must declare glow + glow2 (identity completeness)
 *   publish = hash-pinned: the bytes that pass review are the bytes published
 *   (sha256 checked at publish time — post-review tampering is refused)
 */
public final class ThemePack {

    private ThemePack() { }

    // ---- the store contract numbers (documented in docs/STORE.md) ----------
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_NAME = 40;
    public static final double MIN_CONTRAST = 3.0;
    public static final int MAX_DEPTH = 8;
    public static final int FONT_MIN = 0, FONT_MAX = 5;                        // Theme.FONTS 0..5
    public static final int ANIM_MIN = -1, ANIM_MAX = 29;                      // 30 animated backdrops
    public static final double BORDER_W_MIN = 0.5, BORDER_W_MAX = 8.0;

    /** Layout settings travel inside a pack — same keys as Theme.PACK_INTS + pressglow. */
    static final String[] LAYOUT_KEYS = {"keyshape", "radius", "keygap", "hpct", "wpct", "keysize", "pressglow"};
    static final int[][] LAYOUT_RANGE = {          // min, max (inclusive) per key above (pressglow excluded)
        {0, 5}, {0, 40}, {0, 12}, {60, 220}, {60, 200}, {0, 2}
    };

    static final String[] TOP_KEYS = {
        "neokey", "name", "app", "vendor",
        "bg", "bg2", "key", "keyPressed", "special", "text", "specialText", "shadow",
        "enter", "enterText", "icon", "hint", "popup", "popupText", "space", "border", "borderW",
        "font", "glass", "photo", "anim", "neon", "glow", "glow2", "layout"
    };
    static final String[] COLOR_KEYS = {
        "bg", "bg2", "key", "keyPressed", "special", "text", "specialText", "shadow",
        "enter", "enterText", "icon", "hint", "popup", "popupText", "space", "border", "glow", "glow2"
    };

    // ============================ verdict ===================================

    public static final class Verdict {
        public final boolean ok;
        public final String[] reasons;   // stable short tags, empty when ok
        public final long nanos;         // measured review wall time
        public final String name, vendor;
        Verdict(boolean ok, String[] reasons, long nanos, String name, String vendor) {
            this.ok = ok; this.reasons = reasons; this.nanos = nanos;
            this.name = name; this.vendor = vendor;
        }
    }

    // ============================ review ====================================

    /** Reviews raw pack bytes. knownVendors = the founders registry (ids). */
    public static Verdict review(byte[] bytes, java.util.Set<String> knownVendors) {
        long t0 = System.nanoTime();
        java.util.List<String> r = new java.util.ArrayList<String>();

        if (bytes == null || bytes.length == 0) return fail(r, t0, "empty");
        if (bytes.length > MAX_BYTES) return fail(r, t0, "size");

        Json.Node root;
        try { root = Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e) { return fail(r, t0, "json:" + shortTag(e.getMessage())); }
        if (!root.isObj()) return fail(r, t0, "shape");

        java.util.Map<String, Json.Node> o = root.obj();

        // format version + app identity
        Json.Node v = o.get("neokey");
        if (v == null || !v.isNum() || (long) v.num() != 1L) r.add("version");
        Json.Node app = o.get("app");
        if (app != null && (!app.isStr() || !"NeoKey".equals(app.str()))) r.add("app");

        // unknown top-level keys (planted secrets / tracking fields)
        for (String k : o.keySet())
            if (!allowed(k)) r.add("key:" + k);

        // vendor must be declared and registered
        Json.Node vendor = o.get("vendor");
        if (vendor == null || !vendor.isStr() || vendor.str().isEmpty()) r.add("vendor.missing");
        else if (knownVendors == null || !knownVendors.contains(vendor.str())) r.add("vendor.unknown");

        // name — Arabic copy quality
        Json.Node name = o.get("name");
        if (name == null || !name.isStr()) r.add("name.missing");
        else {
            String n = name.str();
            if (n.isEmpty() || n.length() > MAX_NAME) r.add("name.length");
            if (!n.equals(n.trim())) r.add("name.ws");
            if (hasBidi(n)) r.add("name.bidi");
            if (hasTatweel(n)) r.add("name.tatweel");
            if (hasTashkeel(n)) r.add("name.tashkeel");
        }

        // colours
        java.util.Map<String, Integer> col = new java.util.HashMap<String, Integer>();
        for (String k : COLOR_KEYS) {
            Json.Node c = o.get(k);
            if (c == null) continue;
            Integer argb = parseColor(c);
            if (argb == null) { r.add("color:" + k); continue; }
            if (k.equals("bg") && (argb >>> 24) < 0x40) r.add("color.bg.alpha");
            col.put(k, argb);
        }
        if (!col.containsKey("bg") || !col.containsKey("key")) r.add("required");

        // numeric ranges
        range(r, o, "font", FONT_MIN, FONT_MAX);
        range(r, o, "anim", ANIM_MIN, ANIM_MAX);
        Json.Node bw = o.get("borderW");
        if (bw != null && (!bw.isNum() || bw.num() < BORDER_W_MIN || bw.num() > BORDER_W_MAX)) r.add("range.borderW");

        // layout block
        Json.Node lay = o.get("layout");
        if (lay != null) {
            if (!lay.isObj()) r.add("layout.shape");
            else {
                java.util.Map<String, Json.Node> l = lay.obj();
                for (String k : l.keySet()) if (!inArray(LAYOUT_KEYS, k)) r.add("layout.key:" + k);
                for (int i = 0; i < LAYOUT_KEYS.length; i++) {
                    Json.Node n = l.get(LAYOUT_KEYS[i]);
                    if (n == null) continue;
                    if (LAYOUT_KEYS[i].equals("pressglow")) { if (!n.isBool()) r.add("layout.pressglow"); continue; }
                    if (!n.isNum() || n.num() != Math.floor(n.num())
                            || n.num() < LAYOUT_RANGE[i][0] || n.num() > LAYOUT_RANGE[i][1])
                        r.add("layout." + LAYOUT_KEYS[i]);
                }
            }
        }

        // contrast — readability bar, translucent surfaces composited over bg
        int bg = col.containsKey("bg") ? col.get("bg") : 0xFF1B1C20;
        contrast(r, col, bg, "key", "text");
        contrast(r, col, bg, "enter", "enterText");
        contrast(r, col, bg, "special", "specialText");

        // neon identity completeness
        Json.Node neon = o.get("neon");
        if (neon != null && neon.isBool() && neon.b() && (!col.containsKey("glow") || !col.containsKey("glow2")))
            r.add("neon.glow");

        String nm = (name != null && name.isStr()) ? name.str() : "";
        String vd = (vendor != null && vendor.isStr()) ? vendor.str() : "";
        return new Verdict(r.isEmpty(), r.toArray(new String[0]), System.nanoTime() - t0, nm, vd);
    }

    private static Verdict fail(java.util.List<String> r, long t0, String tag) {
        r.add(tag);
        return new Verdict(false, r.toArray(new String[0]), System.nanoTime() - t0, "", "");
    }

    private static boolean allowed(String k) { return inArray(TOP_KEYS, k); }
    private static boolean inArray(String[] a, String k) {
        for (String s : a) if (s.equals(k)) return true;
        return false;
    }

    // ============================ publish pinning ===========================

    /** Publish accepts ONLY the exact bytes that passed review (anti post-review tampering). */
    public static String sha256hex(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(d.length * 2);
            for (byte x : d) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ============================ Arabic copy rules =========================

    static boolean hasBidi(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || c == 0x200E || c == 0x200F)
                return true;
        }
        return false;
    }

    static boolean hasTatweel(String s) { return s.indexOf('\u0640') >= 0; }

    static boolean hasTashkeel(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x064B && c <= 0x0652) return true;
        }
        return false;
    }

    // ============================ colours / contrast ========================

    /** #RGB | #RRGGBB | #AARRGGBB (Theme.hex writes RRGGBB; alpha form accepted). null = refused. */
    static Integer parseColor(Json.Node n) {
        if (!n.isStr()) return null;
        String s = n.str();
        if (s.isEmpty() || s.charAt(0) != '#') return null;
        String h = s.substring(1);
        try {
            if (h.length() == 3 || h.length() == 6) return (int) (Long.parseLong(h, 16) | 0xFF000000L);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
            return null;
        } catch (Exception e) { return null; }
    }

    static void contrast(java.util.List<String> r, java.util.Map<String, Integer> col, int bg, String surface, String ink) {
        Integer s = col.get(surface), t = col.get(ink);
        if (s == null || t == null) return;               // derived client-side by fromSpec; not a store failure
        double ratio = contrastRatio(blend(s, bg), blend(t, bg));
        if (ratio < MIN_CONTRAST) r.add("contrast." + surface);
    }

    /** Alpha-composite over an opaque backdrop. */
    static int blend(int fg, int bg) {
        int a = fg >>> 24;
        if (a == 0xFF) return fg | 0xFF000000;
        int r = (fg >> 16) & 0xFF, g = (fg >> 8) & 0xFF, b = fg & 0xFF;
        int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int rr = (r * a + br * (255 - a)) / 255;
        int gg = (g * a + bgc * (255 - a)) / 255;
        int bl = (b * a + bb * (255 - a)) / 255;
        return 0xFF000000 | (rr << 16) | (gg << 8) | bl;
    }

    /** WCAG 2.1 relative-luminance ratio. */
    static double contrastRatio(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double lum(int argb) {
        return 0.2126 * lin(((argb >> 16) & 0xFF) / 255.0)
             + 0.7152 * lin(((argb >> 8) & 0xFF) / 255.0)
             + 0.0722 * lin((argb & 0xFF) / 255.0);
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static void range(java.util.List<String> r, java.util.Map<String, Json.Node> o, String k, int min, int max) {
        Json.Node n = o.get(k);
        if (n == null) return;
        if (!n.isNum() || n.num() != Math.floor(n.num()) || n.num() < min || n.num() > max) r.add("range." + k);
    }

    private static String shortTag(String m) {
        if (m == null) return "bad";
        int i = m.indexOf(':');
        return (i > 0 ? m.substring(0, i) : m).replaceAll("[^a-z.]", "");
    }

    // ============================ strict JSON ===============================

    /** Minimal strict JSON: duplicate keys / raw control chars / trailing bytes / depth are REFUSED. */
    public static final class Json {
        private Json() { }

        public static final class Node {
            public final Object val;                       // Map<String,Node> | List<Node> | String | Double | Boolean | null
            Node(Object v) { val = v; }
            public boolean isObj() { return val instanceof java.util.Map; }
            public boolean isArr() { return val instanceof java.util.List; }
            public boolean isStr() { return val instanceof String; }
            public boolean isNum() { return val instanceof Double; }
            public boolean isBool() { return val instanceof Boolean; }
            @SuppressWarnings("unchecked")
            public java.util.Map<String, Node> obj() { return (java.util.Map<String, Node>) val; }
            @SuppressWarnings("unchecked")
            public java.util.List<Node> arr() { return (java.util.List<Node>) val; }
            public String str() { return (String) val; }
            public double num() { return (Double) val; }
            public boolean b() { return (Boolean) val; }
        }

        public static Node parse(String s) {
            P p = new P(s);
            p.ws();
            Node n = p.value(0);
            p.ws();
            if (p.i < p.s.length()) throw new IllegalArgumentException("trailing:extra bytes after JSON value");
            return n;
        }

        private static final class P {
            final String s; int i;
            P(String s) { this.s = s; }
            void ws() { while (i < s.length()) { char c = s.charAt(i); if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break; } }
            char peek() { if (i >= s.length()) throw new IllegalArgumentException("eof:unexpected end"); return s.charAt(i); }
            Node value(int d) {
                if (d > MAX_DEPTH) throw new IllegalArgumentException("depth:nesting too deep");
                char c = peek();
                switch (c) {
                    case '{': return obj(d);
                    case '[': return arr(d);
                    case '"': return new Node(string());
                    case 't': word("true"); return new Node(Boolean.TRUE);
                    case 'f': word("false"); return new Node(Boolean.FALSE);
                    case 'n': word("null"); return new Node(null);
                    default:
                        if (c == '-' || (c >= '0' && c <= '9')) return num();
                        throw new IllegalArgumentException("char:unexpected '" + c + "'");
                }
            }
            void word(String w) {
                if (!s.startsWith(w, i)) throw new IllegalArgumentException("literal:bad literal");
                i += w.length();
            }
            Node obj(int d) {
                i++; ws();
                java.util.Map<String, Node> m = new java.util.LinkedHashMap<String, Node>();
                if (peek() == '}') { i++; return new Node(m); }
                while (true) {
                    if (peek() != '"') throw new IllegalArgumentException("key:expected string key");
                    String k = string();
                    if (m.containsKey(k)) throw new IllegalArgumentException("dup:duplicate key " + k);
                    ws(); if (peek() != ':') throw new IllegalArgumentException("colon:expected :");
                    i++; ws();
                    m.put(k, value(d + 1));
                    ws(); char c = peek(); i++;
                    if (c == '}') return new Node(m);
                    if (c != ',') throw new IllegalArgumentException("comma:expected , or }");
                    ws();
                }
            }
            Node arr(int d) {
                i++; ws();
                java.util.List<Node> l = new java.util.ArrayList<Node>();
                if (peek() == ']') { i++; return new Node(l); }
                while (true) {
                    l.add(value(d + 1));
                    ws(); char c = peek(); i++;
                    if (c == ']') return new Node(l);
                    if (c != ',') throw new IllegalArgumentException("comma:expected , or ]");
                    ws();
                }
            }
            String string() {
                i++;
                StringBuilder b = new StringBuilder();
                while (true) {
                    if (i >= s.length()) throw new IllegalArgumentException("eof:unterminated string");
                    char c = s.charAt(i++);
                    if (c == '"') return b.toString();
                    if (c < 0x20) throw new IllegalArgumentException("ctrl:raw control char in string");
                    if (c == '\\') {
                        if (i >= s.length()) throw new IllegalArgumentException("eof:bad escape");
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
                                if (i + 4 > s.length()) throw new IllegalArgumentException("eof:bad \\u");
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                                break;
                            default: throw new IllegalArgumentException("esc:bad escape \\" + e);
                        }
                    } else b.append(c);
                }
            }
            Node num() {
                int st = i;
                if (peek() == '-') i++;
                boolean dig = false;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c >= '0' && c <= '9') { dig = true; i++; }
                    else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                if (!dig) throw new IllegalArgumentException("num:bad number");
                try { return new Node(Double.parseDouble(s.substring(st, i))); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("num:bad number"); }
            }
        }
    }
}
