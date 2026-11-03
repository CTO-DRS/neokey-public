package org.json;

/**
 * ستب تجميعي-وتشغيلي مصغّر لـ org.json.JSONArray — يخدم حصراً
 * SyncRestTransport.list() الذي يقرأ من الخادم مصفوفة سلاسل (نقطة النهاية #3
 * في docs/SYNC-REST.md: «مصفوفة JSON من معرفات b64url»).
 *
 * على جهاز أندرويد يوفّر android.jar الصنف الكامل؛ هذا الستب لبيئة JVM
 * الخالصة (tests/run.sh وبوابات الحلبة) فقط — تحليل صارم لمصفوفة سلاسل:
 * بلا أرقام/كائنات/true/null، بلا فواصل زائدة، بلا محارف تحكم خام.
 * أي غموض = IllegalArgumentException، والعميل يحوّله إلى SyncRefused("bad_list")
 * (فشل مغلق — لا يُقبَل نص نصف-مبني من الشبكة إطلاقاً).
 */
public final class JSONArray {

    private final String[] vals;

    public JSONArray(String src) { vals = parse(src); }

    public int length() { return vals.length; }

    public String getString(int i) {
        if (i < 0 || i >= vals.length) throw new IllegalArgumentException("index " + i);
        return vals[i];
    }

    // ---- تحليل صارم لمصفوفة سلاسل فقط ------------------------------------------

    private static String[] parse(String s) {
        if (s == null) throw new IllegalArgumentException("null");
        P p = new P(s);
        p.ws();
        p.expect('[');
        p.ws();
        if (p.peek() == ']') { p.take(); p.ws(); p.end(); return new String[0]; }
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        while (true) {
            out.add(p.str());
            p.ws();
            char c = p.take();
            if (c == ']') break;
            if (c != ',') throw new IllegalArgumentException("sep");
            p.ws();
        }
        p.ws();
        p.end();
        return out.toArray(new String[out.size()]);
    }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("eof"); return s.charAt(i); }
        char take() { if (i >= s.length()) throw new IllegalArgumentException("eof"); return s.charAt(i++); }
        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }
        void expect(char c) { if (take() != c) throw new IllegalArgumentException("char"); }
        void end() { if (i != s.length()) throw new IllegalArgumentException("trailing"); }
        String str() {
            if (take() != '"') throw new IllegalArgumentException("str");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') break;
                if (c < 0x20) throw new IllegalArgumentException("ctrl");
                if (c == '\\') {
                    char e = take();
                    if (e == '"') sb.append('"');
                    else if (e == '\\') sb.append('\\');
                    else if (e == '/') sb.append('/');
                    else if (e == 'n') sb.append('\n');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'b') sb.append('\b');
                    else if (e == 'f') sb.append('\f');
                    else if (e == 'u') {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("u");
                        try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                        catch (NumberFormatException nfe) { throw new IllegalArgumentException("u"); }
                        i += 4;
                    } else throw new IllegalArgumentException("esc");
                } else sb.append(c);
            }
            return sb.toString();
        }
    }
}
