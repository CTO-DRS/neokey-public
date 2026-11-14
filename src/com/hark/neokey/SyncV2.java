package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * W75 (roadmap v2): المزامنة v2 الموقّعة — «دفعتك محمولة بتوقيع حي».
 * دفعة مزامنة من جهاز إلى خادم تُختَم قبل الرفع: نونس أحادي الاتجاه
 * يمنع الإعادة، وأسطر العمليات الصغيرة، ودبوس المحتوى، وتوقيع الظرف
 * الخارجي. الخادم يفتح بالترتيب الموثق الصارم — عائلة DialectPack/
 * ThemeChannel البايتية نفسها (التوقيع من النهاية بالبايتات).
 *
 * القوانين:
 *   1. الشكل: سطر السكيمة «neokey-syncv2-1»، ثم «nonce <12hex>»، ثم
 *      «body <64hex>» = sha256(أسطر العمليات خالصة بترتيبها)، ثم أسطر
 *      العمليات «op\t<سطر>»، ثم «sig <64hex>» من النهاية = sha256(كل
 *      ما قبله).
 *   2. النونس: 12 محرفاً سداسياً حتمياً من المحتوى — الإعادة بنفس
 *      النونس يرفضها سجل الخادم الحي (الفتح يعلن النونس ليُفحص).
 *   3. دبوس المحتوى: أي تعديل أو إعادة ترتيب للعمليات تكسر الدبوس
 *      فتعض DPACK_PIN_MISMATCH حتى لو سار التوقيع.
 *   4. الحجم: 1..64 عمليات، كل سطر 1..512 بايتاً — خارجها يعض بوسمه.
 */
public final class SyncV2 {

    public static final String SCHEMA = "neokey-syncv2-1";
    public static final String TAG_SCHEMA = "SYNCV2_SCHEMA";
    public static final String TAG_NONCE = "SYNCV2_NONCE";
    public static final String TAG_SIG = "DPACK_SIG";
    public static final String TAG_PIN_MISMATCH = "DPACK_PIN_MISMATCH";
    public static final String TAG_COUNT = "COUNT_MISMATCH";
    public static final String TAG_EMPTY = "SYNCV2_EMPTY";
    public static final String TAG_LIMIT = "SYNCV2_LIMIT";
    public static final String TAG_OP = "SYNCV2_OP";

    /** دفعة مفتوحة متحقق منها — النونس والعمليات بترتيبها ودبوسها. */
    public static final class Open {
        public final String nonce;
        public final List<String> ops;
        Open(String nonce, List<String> ops) {
            this.nonce = nonce; this.ops = ops;
        }
    }

    /** الختم: نونس حتمي من المحتوى (بدون عشوائية — حتمية الختم قانون). */
    public static byte[] seal(List<String> ops) {
        if (ops == null || ops.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (ops.size() > 64) throw new IllegalArgumentException(TAG_LIMIT);
        StringBuilder b = new StringBuilder(160);
        for (String op : ops) {
            String s = opLine(op);
            b.append("op\t").append(s).append('\n');
        }
        byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        String bodySha = CommunityDict.sha256Hex(body);
        String nonce = nonceOf(bodySha);
        byte[] prefix = (SCHEMA + "\nnonce " + nonce + "\nbody " + bodySha + "\n")
                .getBytes(StandardCharsets.UTF_8);
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

    /** الفتح الصارم بالبايتات — الشكل، النونس، دبوس المحتوى، العدّاد، التوقيع. */
    public static Open open(byte[] sealed) {
        if (sealed == null || sealed.length == 0) throw new IllegalArgumentException(TAG_SCHEMA);
        String text = new String(sealed, StandardCharsets.UTF_8);
        int nl1 = text.indexOf('\n');
        if (nl1 < 0 || !text.substring(0, nl1).equals(SCHEMA))
            throw new IllegalArgumentException(TAG_SCHEMA);
        String nonce = hexField(text, "\nnonce ", 12, TAG_NONCE);
        String bodySha = hexField(text, "\nbody ", 64, TAG_PIN_MISMATCH);
        String sig = sigLineOf(sealed);
        int sigAt = sealed.length - ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8).length;
        int bodyAt = (SCHEMA + "\nnonce " + nonce + "\nbody " + bodySha + "\n")
                .getBytes(StandardCharsets.UTF_8).length;
        if (sigAt <= bodyAt) throw new IllegalArgumentException(TAG_EMPTY);
        String expect = CommunityDict.sha256Hex(java.util.Arrays.copyOf(sealed, sigAt));
        if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);
        String body = new String(java.util.Arrays.copyOfRange(sealed, bodyAt, sigAt),
                StandardCharsets.UTF_8);
        byte[] bodyBytes = java.util.Arrays.copyOfRange(sealed, bodyAt, sigAt);
        List<String> ops = new ArrayList<>();
        int at = 0;
        while (at < body.length()) {
            int end = body.indexOf('\n', at);
            if (end < 0) throw new IllegalArgumentException(TAG_OP);
            String line = body.substring(at, end);
            if (!line.startsWith("op\t")) throw new IllegalArgumentException(TAG_OP);
            ops.add(line.substring(3));
            at = end + 1;
        }
        if (ops.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (ops.size() > 64) throw new IllegalArgumentException(TAG_LIMIT);
        if (!CommunityDict.sha256Hex(bodyBytes).equals(bodySha))
            throw new IllegalArgumentException(TAG_PIN_MISMATCH);
        return new Open(nonce, ops);
    }

    // ------------------------------------------------------------ أدوات داخلية

    /** سطر عملية: 1..512 بايتاً، بلا أسطر داخلية — الجدولة قيمة مشروعة (الفتح يقسم بالأسطر فقط). */
    static String opLine(String raw) {
        if (raw == null) throw new IllegalArgumentException(TAG_OP);
        String s = raw.trim();
        int bytes = s.getBytes(StandardCharsets.UTF_8).length;
        if (s.isEmpty() || bytes > 512 || s.indexOf('\n') >= 0)
            throw new IllegalArgumentException(TAG_OP);
        return s;
    }

    /** نونس حتمي: أول 12 محرفاً من sha256(دبوس المحتوى + «/nonce») — غير الصفر الكلي. */
    static String nonceOf(String bodySha) {
        String basis = bodySha + "/nonce";
        String n = CommunityDict.sha256Hex(basis.getBytes(StandardCharsets.UTF_8))
                .substring(0, 12);
        if ("000000000000".equals(n)) n = "000000000001";
        return n;
    }

    /** حقل سداسي بحجم موثق بعد بادئة — خارج الشكل يعض بوسمه. */
    private static String hexField(String text, String prefixWithNl, int hexLen, String tag) {
        int at = text.indexOf(prefixWithNl) + 1;
        if (at < 1 || !text.startsWith(prefixWithNl.substring(1), at))
            throw new IllegalArgumentException(tag);
        int vAt = at + prefixWithNl.length() - 1;
        if (vAt + hexLen >= text.length() || text.charAt(vAt + hexLen) != '\n')
            throw new IllegalArgumentException(tag);
        String v = text.substring(vAt, vAt + hexLen);
        for (int i = 0; i < v.length(); i++) {
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

    private SyncV2() { }
}
