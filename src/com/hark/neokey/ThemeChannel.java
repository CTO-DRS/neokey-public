package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * W74 (roadmap v2): قناة الثيمات الموقّعة — «قائمة منسّقة تحمل دبوسها».
 * قائمة بذور منسّقة (بذرة نصية + قالب 0..3) تسافر كنص موقع — صفر بايت
 * أصول — والجهاز يفتحها ويعيدها بايت-دقيقة ويصيّر كل بذرة عبر GenThemes
 * الحتمي (نفس البذرة = نفس البكسلات على أي جهاز).
 *
 * القوانين (عائلة DialectPack البايتية نفسها):
 *   1. الشكل: سطر السكيمة أول سطر، ثم «pin <64hex>»، ثم أسطر المدخلات،
 *      وآخر سطر «sig <64hex>» يُقرأ من النهاية بالبايتات.
 *   2. الدبوس الذهبي: sha256(كل البايتات قبل سطر التوقيع) — أي تحريف
 *      في المدخلات أو ترتيبها يعض DPACK_PIN_MISMATCH.
 *   3. التوقيع الخارجي: sha256(كل ما قبل سطره) — تحريف قيمته يعض
 *      DPACK_SIG حتى مع قوانين داخلية سليمة.
 *   4. المدخل الصارم: «seed\t<بذرة 1..64>\tstyle\t<0..3>» — خارج الشكل
 *      يعض بوسمه؛ بلا مدخلات يعض CHAN_EMPTY وفوق 32 يعض CHAN_COUNT.
 *   5. الفتح حتمي: نفس البايتات = نفس المدخلات بنفس الترتيب.
 */
public final class ThemeChannel {

    public static final String SCHEMA = "neokey-themechan-1";
    public static final String TAG_SCHEMA = "CHAN_SCHEMA";
    public static final String TAG_PIN = "CHAN_PIN";
    public static final String TAG_PIN_MISMATCH = "DPACK_PIN_MISMATCH";
    public static final String TAG_SIG = "DPACK_SIG";
    public static final String TAG_ENTRY = "CHAN_ENTRY";
    public static final String TAG_EMPTY = "CHAN_EMPTY";
    public static final String TAG_COUNT = "CHAN_COUNT";

    /** مدخل قناة منسّق — بذرة نصية + رقم القالب. */
    public static final class Entry {
        public final String seed;
        public final int style;
        public Entry(String seed, int style) { this.seed = seed; this.style = style; }
    }

    /** قناة مفتوحة متحقق منها — المدخلات بترتيبها ودبوسها. */
    public static final class Open {
        public final String goldenPin;
        public final List<Entry> entries;
        Open(String goldenPin, List<Entry> entries) {
            this.goldenPin = goldenPin; this.entries = entries;
        }
    }

    /** الختم: مدخلات منسّقة → بايتات قناة موقّعة حتمية (الشكل: سكيمة/دبوس/مدخلات/توقيع). */
    public static byte[] seal(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (entries.size() > 32) throw new IllegalArgumentException(TAG_COUNT);
        StringBuilder b = new StringBuilder(160);
        b.append(SCHEMA).append('\n');
        for (Entry e : entries) b.append(entryLine(e));
        byte[] head = b.toString().getBytes(StandardCharsets.UTF_8);
        // الدبوس = sha256(الترويسة + المدخلات) — يُحسب أولاً ثم يُكتب في موقعه
        String pin = CommunityDict.sha256Hex(head);
        int headLen = (SCHEMA + "\n").getBytes(StandardCharsets.UTF_8).length;
        byte[] pinLine = ("pin " + pin + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[headLen + pinLine.length + (head.length - headLen)];
        System.arraycopy(head, 0, prefix, 0, headLen);
        System.arraycopy(pinLine, 0, prefix, headLen, pinLine.length);
        System.arraycopy(head, headLen, prefix, headLen + pinLine.length, head.length - headLen);
        String sig = CommunityDict.sha256Hex(prefix);
        byte[] tail = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return out;
    }

    /**
     * الفتح الصارم بالبايتات — الترتيب الموثق: السكيمة، الدبوس، شكل
     * المدخلات والعدد، التوقيع من النهاية، ثم الدبوس الذهبي.
     */
    public static Open open(byte[] chan) {
        if (chan == null || chan.length == 0) throw new IllegalArgumentException(TAG_SCHEMA);
        String text = new String(chan, StandardCharsets.UTF_8);
        int nl1 = text.indexOf('\n');
        if (nl1 < 0 || !text.substring(0, nl1).equals(SCHEMA))
            throw new IllegalArgumentException(TAG_SCHEMA);
        String pin = hexField(text, "\npin ", TAG_PIN);
        String sig = sigLineOf(chan);
        int bodyAt = (SCHEMA + "\npin " + pin + "\n").getBytes(StandardCharsets.UTF_8).length;
        int sigAt = chan.length - ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8).length;
        if (sigAt < bodyAt) throw new IllegalArgumentException(TAG_SIG);
        String expect = CommunityDict.sha256Hex(java.util.Arrays.copyOf(chan, sigAt));
        if (!expect.equals(sig)) throw new IllegalArgumentException(TAG_SIG);
        String body = new String(java.util.Arrays.copyOfRange(chan, bodyAt, sigAt),
                StandardCharsets.UTF_8);
        List<Entry> es = new ArrayList<>();
        int at = 0;
        while (at < body.length()) {
            int end = body.indexOf('\n', at);
            if (end < 0) throw new IllegalArgumentException(TAG_ENTRY);
            es.add(parseEntry(body.substring(at, end)));
            at = end + 1;
        }
        if (es.isEmpty()) throw new IllegalArgumentException(TAG_EMPTY);
        if (es.size() > 32) throw new IllegalArgumentException(TAG_COUNT);
        // الدبوس الذهبي = sha256(الترويسة + المدخلات) — أي تحريف أو إعادة
        // ترتيب للمدخلات يغيّر القلم فيعض، وسطر الدبوس نفسه خارج القلم.
        int headLen = (SCHEMA + "\n").getBytes(StandardCharsets.UTF_8).length;
        byte[] headOnly = new byte[headLen + (sigAt - bodyAt)];
        System.arraycopy(chan, 0, headOnly, 0, headLen);
        System.arraycopy(chan, bodyAt, headOnly, headLen, sigAt - bodyAt);
        if (!CommunityDict.sha256Hex(headOnly).equals(pin))
            throw new IllegalArgumentException(TAG_PIN_MISMATCH);
        return new Open(pin, es);
    }

    // ------------------------------------------------------------ أدوات داخلية

    /** سطر مدخل موثق: «seed\t<1..64>\tstyle\t<0..3>». */
    static String entryLine(Entry e) {
        if (e == null || e.seed == null) throw new IllegalArgumentException(TAG_ENTRY);
        String s = e.seed.trim();
        if (s.isEmpty() || s.length() > 64 || s.indexOf('\n') >= 0 || s.indexOf('\t') >= 0)
            throw new IllegalArgumentException(TAG_ENTRY);
        if (e.style < 0 || e.style >= GenThemes.STYLE_COUNT)
            throw new IllegalArgumentException(TAG_ENTRY);
        return "seed\t" + s + "\tstyle\t" + e.style + "\n";
    }

    private static Entry parseEntry(String line) {
        String[] p = line.split("\t");
        if (p.length != 4 || !p[0].equals("seed") || !p[2].equals("style"))
            throw new IllegalArgumentException(TAG_ENTRY);
        String seed = p[1];
        if (seed.isEmpty() || seed.length() > 64) throw new IllegalArgumentException(TAG_ENTRY);
        int style;
        try { style = Integer.parseInt(p[3]); }
        catch (NumberFormatException nfe) { throw new IllegalArgumentException(TAG_ENTRY); }
        if (style < 0 || style >= GenThemes.STYLE_COUNT)
            throw new IllegalArgumentException(TAG_ENTRY);
        return new Entry(seed, style);
    }

    /** حقل سداسي 64 بعد بادئة في النص — خارج الشكل يعض بوسمه. */
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
    private static String sigLineOf(byte[] chan) {
        int tail = "sig ".length() + 64 + 1;
        if (chan.length < tail + 2 || chan[chan.length - tail - 1] != '\n')
            throw new IllegalArgumentException(TAG_SIG);
        String t = new String(java.util.Arrays.copyOfRange(chan,
                chan.length - tail, chan.length), StandardCharsets.UTF_8);
        if (!t.startsWith("sig ") || !t.endsWith("\n")) throw new IllegalArgumentException(TAG_SIG);
        String v = t.substring(4, t.length() - 1);
        for (int i = 0; i < 64; i++) {
            char c = v.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException(TAG_SIG);
        }
        return v;
    }

    private ThemeChannel() { }
}
