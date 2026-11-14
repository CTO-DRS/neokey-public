package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * W76 (roadmap v2): بيان المتجر v2 الموقّع — «ما ينشر يحمل بصمته».
 * بيان القائمة المراد رفعها إلى Play Console يُختَم قبل النشر: أسطر
 * الحقول (مفتاح\tقيمة)، دبوس المحتوى sha256(الحقول خالصة بترتيبها)،
 * وتوقيع الظرف من النهاية بالبايتات — عائلة SyncV2/DialectPack نفسها.
 * أي تحريف حقل أو إعادة ترتيب يكسر الدبوس فتعض، وأي تزوير توقيع يعض.
 *
 * القوانين:
 *   1. الشكل: سطر السكيمة «neokey-storev2-1»، ثم «body <64hex>» =
 *      sha256(أسطر الحقول خالصة بترتيبها)، ثم أسطر الحقول
 *      «f\t<مفتاح>\t<قيمة>»، ثم «sig <64hex>» من النهاية.
 *   2. الحقل الصارم: مفتاح 1..48 بايتاً بلا جدولة/أسطر، قيمة 1..2048
 *      بايتاً بلا أسطر — خارجها يعض بوسمه.
 *   3. الحقول المطلوبة للحقل الحي: appName, shortDesc, fullDesc,
 *      contentRating — غياب أي منها عند الفتح يعض REQUIRED_MISSING.
 *   4. العدد: 1..64 حقلاً — خارجها يعض بوسمه.
 */
public final class StoreV2 {

    public static final String SCHEMA = "neokey-storev2-1";
    public static final String TAG_SCHEMA = "STOREV2_SCHEMA";
    public static final String TAG_SIG = "DPACK_SIG";
    public static final String TAG_PIN_MISMATCH = "DPACK_PIN_MISMATCH";
    public static final String TAG_FIELD = "STOREV2_FIELD";
    public static final String TAG_REQUIRED = "REQUIRED_MISSING";
    public static final String TAG_EMPTY = "STOREV2_EMPTY";
    public static final String TAG_LIMIT = "STOREV2_LIMIT";

    /** بيان مفتوح متحقق منه — الحقول بترتيبها ودبوسها. */
    public static final class Open {
        public final List<String[]> fields;
        Open(List<String[]> fields) { this.fields = fields; }
    }

    static final String[] REQUIRED = {"appName", "shortDesc", "fullDesc", "contentRating"};

    /** الختم: حقول البيان → بايتات موقّعة حتمية. */
    public static byte[] seal(List<String[]> fields) {
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (fields.size() > 64) throw new IllegalArgumentException(TAG_LIMIT);
        StringBuilder b = new StringBuilder(256);
        for (String[] f : fields) b.append(fieldLine(f));
        byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        String bodySha = CommunityDict.sha256Hex(body);
        byte[] prefix = (SCHEMA + "\nbody " + bodySha + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        String sig = CommunityDict.sha256Hex(out);
        byte[] tail = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] o2 = new byte[out.length + tail.length];
        System.arraycopy(out, 0, o2, 0, out.length);
        System.arraycopy(tail, 0, o2, out.length, tail.length);
        return o2;
    }

    /** الفتح الصارم — الشكل، دبوس المحتوى، الحقول، المطلوب، التوقيع من النهاية. */
    public static Open open(byte[] sealed) {
        if (sealed == null || sealed.length == 0) throw new IllegalArgumentException(TAG_SCHEMA);
        String text = new String(sealed, StandardCharsets.UTF_8);
        int nl1 = text.indexOf('\n');
        if (nl1 < 0 || !text.substring(0, nl1).equals(SCHEMA))
            throw new IllegalArgumentException(TAG_SCHEMA);
        String bodySha = hexField(text, "\nbody ", TAG_PIN_MISMATCH);
        String sig = sigLineOf(sealed);
        int sigAt = sealed.length - ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8).length;
        int bodyAt = (SCHEMA + "\nbody " + bodySha + "\n").getBytes(StandardCharsets.UTF_8).length;
        if (sigAt <= bodyAt) throw new IllegalArgumentException(TAG_EMPTY);
        String expect = CommunityDict.sha256Hex(java.util.Arrays.copyOf(sealed, sigAt));
        if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);
        String body = new String(java.util.Arrays.copyOfRange(sealed, bodyAt, sigAt),
                StandardCharsets.UTF_8);
        byte[] bodyBytes = java.util.Arrays.copyOfRange(sealed, bodyAt, sigAt);
        List<String[]> fs = new ArrayList<>();
        int at = 0;
        while (at < body.length()) {
            int end = body.indexOf('\n', at);
            if (end < 0) throw new IllegalArgumentException(TAG_FIELD);
            fs.add(parseField(body.substring(at, end)));
            at = end + 1;
        }
        if (fs.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (fs.size() > 64) throw new IllegalArgumentException(TAG_LIMIT);
        if (!CommunityDict.sha256Hex(bodyBytes).equals(bodySha))
            throw new IllegalArgumentException(TAG_PIN_MISMATCH);
        for (String r : REQUIRED) {
            boolean found = false;
            for (String[] f : fs) if (f[0].equals(r)) { found = true; break; }
            if (!found) throw new IllegalArgumentException(TAG_REQUIRED);
        }
        return new Open(fs);
    }

    // ------------------------------------------------------------ أدوات داخلية

    /** سطر حقل: «f\t<مفتاح 1..48B>\t<قيمة 1..2048B>» — بلا أسطر. */
    static String fieldLine(String[] f) {
        if (f == null || f.length != 2 || f[0] == null || f[1] == null)
            throw new IllegalArgumentException(TAG_FIELD);
        String k = f[0].trim();
        String v = f[1].trim();
        int kb = k.getBytes(StandardCharsets.UTF_8).length;
        int vb = v.getBytes(StandardCharsets.UTF_8).length;
        if (k.isEmpty() || kb > 48 || v.isEmpty() || vb > 2048
                || k.indexOf('\n') >= 0 || v.indexOf('\n') >= 0)
            throw new IllegalArgumentException(TAG_FIELD);
        return "f\t" + k + "\t" + v + "\n";
    }

    private static String[] parseField(String line) {
        String[] p = line.split("\t");
        if (p.length != 3 || !p[0].equals("f")) throw new IllegalArgumentException(TAG_FIELD);
        String[] f = {p[1], p[2]};
        fieldLine(f);
        return f;
    }

    /** حقل سداسي 64 بعد بادئة — خارج الشكل يعض بوسمه. */
    private static String hexField(String text, String prefixWithNl, String tag) {
        int at = text.indexOf(prefixWithNl) + 1;
        if (at < 1 || !text.startsWith(prefixWithNl.substring(1), at))
            throw new IllegalArgumentException(tag);
        int vAt = at + prefixWithNl.length() - 1;
        if (vAt + 64 >= text.length() || text.charAt(vAt + 64) != '\n')
            throw new IllegalArgumentException(tag);
        String v = text.substring(vAt, vAt + 64);
        for (int i = 0; i < 64; i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException(tag);
        }
        return v;
    }

    /** سطر التوقيع الأخير من النهاية بالبايتات — «sig <64hex>\n». */
    private static String sigLineOf(byte[] sealed) {
        int tail = "sig ".length() + 64 + 1;
        if (sealed.length < tail + 2 || sealed[sealed.length - tail - 1] != '\n')
            throw new IllegalArgumentException(TAG_SIG);
        String t = new String(java.util.Arrays.copyOfRange(sealed,
                sealed.length - tail, sealed.length), StandardCharsets.UTF_8);
        if (!t.startsWith("sig ") || !t.endsWith("\n")) throw new IllegalArgumentException(TAG_SIG);
        String v = t.substring(4, t.length() - 1);
        for (int i = 0; i < 64; i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException(TAG_SIG);
        }
        return v;
    }

    private StoreV2() { }
}
