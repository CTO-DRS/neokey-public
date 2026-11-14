package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * W73 (roadmap v2): حزم اللهجات المجتمعية الموقّعة — «لهجتك محفوظة
 * بدبوسها الذهبي». تمديد سلسلة DictSign/CommunityDict: الدفعة تُراجع،
 * الحزمة تُوقّع (قوانين DictSign السبعة)، ثم تُختم بهوية اللهجة
 * ودبوسها الذهبي — الدمج لا يج إلا لدبوس مطابق، بايت-دقيقة.
 * نقي JVM: لا شبكة، لا نموذج، لا حالة كائن.
 *
 * القوانين:
 *   1. الدبوس الذهبي حتمي: sha256(dialect + '\n' + wordsSha) — نفس
 *      اللهجة وكلماتها نفسها = نفس الدبوس دائماً، ولهجتان بكلمات
 *      واحدة دبوساهما مختلفان (الهوية جزء من القلم).
 *   2. الختم بايت-دقيق حتمي: ترويسة (سكيمة، لهجة، دبوس) + بايتات
 *      dpack + سطر التوقيع — توقيع الدفعة هو sha256 البايتات قبله.
 *   3. الفتح صارم بترتيب موثق: الشكل → DPACK_SCHEMA، شكل الحقول
 *      السداسية → وسمها، توقيع الختم → DPACK_SIG، ثم قوانين
 *      DictSign السبعة على الداخل، ثم الدبوس الذهبي مقابل wordsSha
 *      الداخل → DPACK_PIN_MISMATCH (أي إعادة ترتيب للكلمات تغيّر
 *      wordsSha فتعضّ بالدبوس — الترتيب جزء من الهوية).
 *   4. الدمج عبر mergePack القائم فقط — العدد الصادق المعلن، بلا
 *      طبقة ثانية؛ الفارغة/المجهولة ترفض موثقاً.
 *   5. عربي خالص — هوية اللهجة سلسلة عربية قصيرة مطبّعة (قصّ
 *      الأطراف، بلا فواصل)، والفارغة ترفض لا تخمين.
 */
public final class DialectPack {

    public static final String SCHEMA = "neokey-dialect-1";
    public static final String TAG_SCHEMA = "DIALECT_SCHEMA";
    public static final String TAG_PIN = "DIALECT_PIN";
    public static final String TAG_PIN_MISMATCH = "DPACK_PIN_MISMATCH";
    public static final String TAG_SIG = "DPACK_SIG";
    public static final String TAG_DIALECT = "DIALECT_ID";
    public static final String TAG_MERGE = "MERGE_REFUSED";

    /** حزمة لهجة مفتوحة متحقق منها — اللهجة والداخل الموقعان. */
    public static final class Open {
        public final String dialect;
        public final String goldenPin;
        public final DictSign.Pack pack;
        Open(String dialect, String goldenPin, DictSign.Pack pack) {
            this.dialect = dialect; this.goldenPin = goldenPin; this.pack = pack;
        }
    }

    /** الدبوس الذهبي: هوية اللهجة جزء من القلم — حتمي كلياً. */
    public static String goldenPin(String dialect, String wordsSha) {
        String basis = dialect(dialect) + "\n" + wordsSha;
        return CommunityDict.sha256Hex(basis.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * الختم: دفعة مجتازة للمراجعة + هوية اللهجة → بايتات ختم حتمية.
     * الترويسة: سكيمة، لهجة، دبوس، ثم بايتات dpack كما نُشرت، ثم
     * سطر التوقيع على كل ما قبله.
     */
    public static byte[] seal(String dialect, DictSign.Dpack dpack) {
        String d = dialect(dialect);
        if (d.isEmpty()) throw new IllegalArgumentException(TAG_DIALECT);
        String pin = goldenPin(d, dpack.wordsSha);
        StringBuilder h = new StringBuilder(96);
        h.append(SCHEMA).append('\n');
        h.append("dialect ").append(d).append('\n');
        h.append("pin ").append(pin).append('\n');
        byte[] head = h.toString().getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[head.length + dpack.bytes.length];
        System.arraycopy(head, 0, prefix, 0, head.length);
        System.arraycopy(dpack.bytes, 0, prefix, head.length, dpack.bytes.length);
        String sig = CommunityDict.sha256Hex(prefix);
        byte[] tail = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return out;
    }

    /**
     * الفتح الصارم — الترتيب الموثق: الشكل، شكل الحقول، توقيع الختم،
     * ثم قوانين DictSign السبعة، ثم الدبوس الذهبي مقابل داخل الحزمة.
     */
    public static Open open(byte[] sealed, String reportJson, Set<String> installedNorms) {
        if (sealed == null || sealed.length == 0) throw new IllegalArgumentException(TAG_SCHEMA);
        String text = new String(sealed, StandardCharsets.UTF_8);
        int nl1 = text.indexOf('\n');
        if (nl1 < 0 || !text.substring(0, nl1).equals(SCHEMA))
            throw new IllegalArgumentException(TAG_SCHEMA);
        String dialect = dialectLine(text);
        if (dialect.isEmpty()) throw new IllegalArgumentException(TAG_DIALECT);
        String pin = field(sealed, "pin ", 64, TAG_PIN);
        String sig = sigLineOf(sealed);
        // الإحداثيات بالبايتات خالصة — الهوية العربية في الجسم حروفها ≠ بايتاتها
        int bodyAt = (SCHEMA + "\ndialect " + dialect + "\npin " + pin + "\n")
                .getBytes(StandardCharsets.UTF_8).length;
        int sigAt = sealed.length - ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8).length;
        if (sigAt < bodyAt) throw new IllegalArgumentException(TAG_SIG);
        String expect = CommunityDict.sha256Hex(java.util.Arrays.copyOf(sealed, sigAt));
        if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);
        byte[] inner = new byte[sigAt - bodyAt];
        System.arraycopy(sealed, bodyAt, inner, 0, inner.length);
        DictSign.Pack p = DictSign.load(inner, reportJson, installedNorms);
        if (!goldenPin(dialect, wordsShaOf(inner)).equals(pin))
            throw new IllegalArgumentException(TAG_PIN_MISMATCH);
        return new Open(dialect, pin, p);
    }

    /** منفذ الدمج النقي — القاموس الحي يتصل به بمرجع دالة، بلا استيراد. */
    public interface WordSink { int mergePack(java.util.List<String> words); }

    /** الدمج عبر المسار القائم فقط — العدد الصادق؛ فريق مرفوض يرفض موثقاً. */
    public static int merge(Open pack, WordSink sink) {
        if (pack == null || pack.pack == null || sink == null || pack.pack.words.isEmpty())
            throw new IllegalArgumentException(TAG_MERGE);
        List<String> ws = new ArrayList<>(pack.pack.words.size());
        for (DictSign.Word w : pack.pack.words) ws.add(w.word);
        return sink.mergePack(ws);
    }

    // ------------------------------------------------------------ أدوات داخلية

    /** مطبّع هوية اللهجة: قصّ الأطراف، بلا فواصل جدولة/أسطر، أقصى 24 محرفاً. */
    static String dialect(String raw) {
        if (raw == null) return "";
        String d = raw.trim();
        if (d.isEmpty() || d.length() > 24) return "";
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r' || c == ' ') return "";
        }
        return d;
    }

    /** حقل ترويسة سداسي بحجم موثق — خارج الشكل يعض بوسمه. */
    private static String field(byte[] sealed, String prefix, int hexLen, String tag) {
        String text = new String(sealed, StandardCharsets.UTF_8);
        int at = text.indexOf("\n" + prefix) + 1;
        if (at < 1 || !text.startsWith(prefix, at)) throw new IllegalArgumentException(tag);
        int end = at + prefix.length() + hexLen;
        if (end > text.length() || text.charAt(end) != '\n') throw new IllegalArgumentException(tag);
        String v = text.substring(at + prefix.length(), end);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException(tag);
        }
        return v;
    }

    /** قارئ سطر اللهجة: من «dialect » حتى نهاية السطر — الشكل الصارم. */
    private static String dialectLine(String text) {
        int at = text.indexOf("\ndialect ") + 1;
        if (at < 1 || !text.startsWith("dialect ", at)) throw new IllegalArgumentException(TAG_DIALECT);
        int end = text.indexOf('\n', at);
        if (end < 0) throw new IllegalArgumentException(TAG_DIALECT);
        return text.substring(at + "dialect ".length(), end);
    }

    /**
     * سطر التوقيع الأخير: قراءة من النهاية بالبايتات — «sig <64hex>\n».
     * الجسم العربي قبلها حروفه ≠ بايتاته، فالنهاية هي المرجع الوحيد الآمن.
     */
    private static String sigLineOf(byte[] sealed) {
        int tail = "sig ".length() + 64 + 1;
        if (sealed.length < tail + 2 || sealed[sealed.length - tail - 1] != '\n')
            throw new IllegalArgumentException(TAG_SIG);
        String t = new String(java.util.Arrays.copyOfRange(sealed,
                sealed.length - tail, sealed.length), StandardCharsets.UTF_8);
        if (!t.startsWith("sig ") || !t.endsWith("\n")) throw new IllegalArgumentException(TAG_SIG);
        String v = t.substring(4, t.length() - 1);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException(TAG_SIG);
        }
        return v;
    }

    /** wordsSha الداخل: من ترويسة dpack نفسها («words <64hex>»). */
    static String wordsShaOf(byte[] innerDpack) {
        return field(innerDpack, "words ", 64, DictSign.TAG_WORDS);
    }

    private DialectPack() { }
}
