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
 * الدمج الموقّع للقاموس المجتمعي — المرحلة 3، الأسبوع 2. نقي: صفر android.*.
 *
 * خطوة النشر المنفصلة (تقرير المرحلة 2/الأسبوع 5 §6: «الدمج الموقّع في حزمة
 * قاموس مشحونة خطوة نشر منفصلة يوقّعها الإنسان») صارت آلية مقاسة: دفعة مجتازة
 * للمراجعة (CommunityDict.Report على بايتات CSV محددة) تتحول حزمة قاموس موقّعة
 * (dpack) تحمل: batchId = sha256 بايتات الدفعة، عدّاد القبول، دبوس بيان
 * المراجعة، دبوس كلمات الحزمة، والتوقيع = sha256 بايتات الحزمة نفسها — توقيع
 * الدفعة هو sha256 بايتاتها (بوابة الأسبوع)، فأي تعديل بعد المراجعة يكسر
 * التوقيع ويُصطاد عند التحميل.
 *
 * صيغة الحزمة (نص UTF-8 صارم بلا BOM، أسطر LF، سطر التوقيع أخيراً):
 *   neokey-dpack-1
 *   batch <64hex>                             = sha256(csv) — batchId التقرير
 *   accepted <n>                              = عدّاد القبول في التقرير
 *   report <64hex>                            = sha256 بايتات بيان المراجعة
 *   words <64hex>                             = sha256 بايتات جسم الكلمات
 *   w <word>\t<diacritized>\t<lemma>\t<pos>   = سطر لكل كلمة مقبولة بترتيب القبول
 *   sig <64hex>                               = sha256(كل بايتات الملف قبله)
 *
 * بيان المراجعة (statement): JSON حتمي تصدره publish ويتضمن صفوف القبول نفسها —
 * هو المرجع البشري المعتمد للنشر. التحميل مع البيان يرفض أي حزمة لا تطابقه
 * بايت-لطرف (استبدال كلمة نظيفة بكلمة نظيفة أخرى يُصطاد — لا ثقة بالعدّادات
 * وحدها). على الجهاز يُحمَّل بلا بيان: يثق الحمل بأصل الإصدار المثبت بدبوسه
 * الذهبي (NQF-GOLDEN)، وتبقى إعادة فحص كل صف بقواعد المراجعة نفسها دفاعاً
 * في العمق (توقيع سليم لمحتوى مسموم لا يمر).
 *
 * سلسلة الثقة كاملة: مراجعة CommunityDict → بيان → توقيع → أصل إصدار → دبوس
 * ذهبي (MakeGolden) → استهلاك المحرك بعد القاموس المثبت بترتيب موثق.
 */
public final class DictSign {

    private DictSign() {}

    public static final String SCHEMA = "neokey-dpack-1";

    // وسوم رفض ثابتة قصيرة (بصمة بوابة الأسبوع)
    public static final String TAG_SCHEMA = "DPACK_SCHEMA";
    public static final String TAG_BATCH  = "DPACK_BATCH";
    public static final String TAG_COUNT  = "DPACK_COUNT";
    public static final String TAG_REPORT = "DPACK_REPORT";
    public static final String TAG_WORDS  = "DPACK_WORDS";
    public static final String TAG_SIG    = "DPACK_SIG";
    public static final String TAG_WORD   = "DPACK_WORD";
    public static final String TAG_EMPTY  = "EMPTY_PACK";
    public static final String TAG_CSV    = "BATCH_MISMATCH";

    /** صف كلمة في الحزمة — المجالات التي يستهلكها المحرك ويعيد التحقق منها. */
    public static final class Word {
        public final String word, diacritized, lemma, pos;
        public Word(String word, String diacritized, String lemma, String pos) {
            this.word = word; this.diacritized = diacritized;
            this.lemma = lemma; this.pos = pos;
        }
    }

    /** ناتج النشر: البايتات المنشورة + أختامها + بيان المراجعة. */
    public static final class Dpack {
        public final byte[] bytes;
        public final String batchId, reportSha, wordsSha, sig, statement;
        public final int accepted;
        public final List<Word> words;
        Dpack(byte[] bytes, String batchId, String reportSha, String wordsSha,
              String sig, String statement, int accepted, List<Word> words) {
            this.bytes = bytes; this.batchId = batchId; this.reportSha = reportSha;
            this.wordsSha = wordsSha; this.sig = sig; this.statement = statement;
            this.accepted = accepted; this.words = words;
        }
    }

    /** حزمة محمّلة متحقق منها — مُدخل الاستهلاك للمحرك. */
    public static final class Pack {
        public final String batchId, sig;
        public final List<Word> words;
        Pack(String batchId, String sig, List<Word> words) {
            this.batchId = batchId; this.sig = sig; this.words = words;
        }
    }

    // ---------------------------------------------------------------- النشر

    /**
     * دفعة مجتازة للمراجعة → حزمة موقّعة حتمية. يشتمّ قبل التوليد: التقرير
     * لهذه الدفعة فعلاً (batchId التقرير = sha256 بايتات csv)، والقبول > 0.
     * صفوف القبول تدخل بترتيب الدفعة نفسه — التكرار الكامل للمدخلات يعيد
     * البايتات نفسها حرفاً بحرف (حتمية النشر تُقاس في الاختبارات).
     */
    public static Dpack publish(byte[] csv, CommunityDict.Report report) {
        String batchId = CommunityDict.sha256Hex(csv);
        if (report == null || !batchId.equals(report.batchId))
            throw new IllegalArgumentException(TAG_CSV);
        if (report.accepted <= 0) throw new IllegalArgumentException(TAG_EMPTY);
        List<Word> ws = new ArrayList<>(report.accepted);
        for (CommunityDict.Row r : report.rows)
            if (r.accepted()) ws.add(new Word(r.word, r.diacritized, r.lemma, r.pos));
        return assemble(batchId, ws);
    }

    /** توليد الحزمة من صفوف جاهزة (يستعمله load في بيان التحقق العكسي أيضاً). */
    private static Dpack assemble(String batchId, List<Word> ws) {
        byte[] body = bodyBytes(ws);
        String wordsSha = CommunityDict.sha256Hex(body);
        String statement = statement(batchId, ws.size(), wordsSha, ws);
        byte[] stmt = statement.getBytes(StandardCharsets.UTF_8);
        String reportSha = CommunityDict.sha256Hex(stmt);
        StringBuilder h = new StringBuilder(160);
        h.append(SCHEMA).append('\n');
        h.append("batch ").append(batchId).append('\n');
        h.append("accepted ").append(ws.size()).append('\n');
        h.append("report ").append(reportSha).append('\n');
        h.append("words ").append(wordsSha).append('\n');
        byte[] head = h.toString().getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[head.length + body.length];
        System.arraycopy(head, 0, prefix, 0, head.length);
        System.arraycopy(body, 0, prefix, head.length, body.length);
        String sig = CommunityDict.sha256Hex(prefix);
        byte[] tail = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return new Dpack(out, batchId, reportSha, wordsSha, sig, statement, ws.size(), ws);
    }

    /** جسم الكلمات: سطر لكل كلمة، حقول بفواصل جدولة، LF حتمي. */
    static byte[] bodyBytes(List<Word> ws) {
        StringBuilder b = new StringBuilder(64 * ws.size());
        for (Word w : ws)
            b.append("w ").append(w.word).append('\t').append(w.diacritized)
             .append('\t').append(w.lemma).append('\t').append(w.pos).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** بيان المراجعة: JSON حتمي يثبّت صفوف القبول نفسها (لا ثقة بالعدّادات). */
    static String statement(String batchId, int accepted, String wordsSha, List<Word> ws) {
        StringBuilder b = new StringBuilder(96 * ws.size() + 128);
        b.append("{\"dpack\":\"").append(SCHEMA).append('"');
        b.append(",\"batch\":\"").append(batchId).append('"');
        b.append(",\"accepted\":").append(accepted);
        b.append(",\"words\":\"").append(wordsSha).append('"');
        b.append(",\"rows\":[");
        for (int i = 0; i < ws.size(); i++) {
            Word w = ws.get(i);
            if (i > 0) b.append(',');
            b.append("[\"").append(esc(w.word)).append("\",\"").append(esc(w.diacritized))
             .append("\",\"").append(esc(w.lemma)).append("\",\"").append(esc(w.pos)).append("\"]");
        }
        b.append("]}");
        return b.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- التحميل

    /**
     * تحميل صارم حتمي — ترتيب الفحوص موثق:
     *   1) الترميز والشكل (schema/سطور الترويسة)               → DPACK_SCHEMA
     *   2) شكل الحقول السداسية (batch/report/words/sig)         → وسم الحقل
     *   3) التوقيع = sha256 بايتات الملف قبل سطر sig           → DPACK_SIG
     *   4) دبوس جسم الكلمات                                     → DPACK_WORDS
     *   5) عدّاد القبول = عدد صفوف الجسم فعلاً                  → DPACK_COUNT
     *   6) بيان المراجعة (إن وُجد): دبوسه في الترويسة + تطابق   → DPACK_REPORT
     *      إعادة توليده من الجسم بايت-لطرف (استبدال كلمة يُصطاد)
     *   7) إعادة فحص كل صف بقواعد المراجعة نفسها (دفاع في       → DPACK_WORD
     *      العمق: عربي، تشكيل بنيوي، ممنوع، مكرر، pos سليم)
     * installedNorms اختياري (قانون المثبت — يمرره الناشر/الاختبار، والجهاز
     * يكتفي بتطهير الحزمة ذاتياً لأن أصل الإصدار مثبت بدبوسه الذهبي).
     */
    public static Pack load(byte[] dpack, String reportJson, Set<String> installedNorms) {
        String text = strictUtf8(dpack);
        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] t = new String[lines.length - 1];
            System.arraycopy(lines, 0, t, 0, t.length);
            lines = t;
        }
        if (lines.length < 7 || !SCHEMA.equals(lines[0])) throw new IllegalArgumentException(TAG_SCHEMA);

        String batch   = field(lines[1], "batch ",   64, TAG_BATCH);
        String accS    = field(lines[2], "accepted ", 0, TAG_COUNT);
        String repSha  = field(lines[3], "report ", 64, TAG_REPORT);
        String wordsSha= field(lines[4], "words ",  64, TAG_WORDS);
        int accepted;
        try { accepted = Integer.parseInt(accS); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(TAG_COUNT); }
        if (accepted <= 0) throw new IllegalArgumentException(TAG_COUNT);

        // التوقيع: sha256 لكل بايتات الملف قبل سطر sig (يجب أن يكون أخيراً)
        if (!lines[lines.length - 1].startsWith("sig ")) throw new IllegalArgumentException(TAG_SIG);
        String sig = field(lines[lines.length - 1], "sig ", 64, TAG_SIG);
        int headerLen = lenOf(lines, 0, 5);
        int bodyRows = lines.length - 6;
        int prefixLen = headerLen + lenOf(lines, 5, bodyRows);
        byte[] prefix = new byte[prefixLen];
        System.arraycopy(dpack, 0, prefix, 0, prefixLen);
        if (!CommunityDict.sha256Hex(prefix).equals(sig)) throw new IllegalArgumentException(TAG_SIG);

        // جسم الكلمات: صفوف w محفوظة الترتيب
        List<Word> ws = new ArrayList<>(bodyRows);
        for (int i = 0; i < bodyRows; i++) {
            String ln = lines[5 + i];
            if (!ln.startsWith("w ")) throw new IllegalArgumentException(TAG_WORD);
            String[] f = ln.substring(2).split("\t", -1);
            if (f.length != 4) throw new IllegalArgumentException(TAG_WORD);
            ws.add(new Word(f[0], f[1], f[2], f[3]));
        }
        byte[] body = new byte[prefixLen - headerLen];
        System.arraycopy(dpack, headerLen, body, 0, body.length);
        if (!CommunityDict.sha256Hex(body).equals(wordsSha)) throw new IllegalArgumentException(TAG_WORDS);

        // عدّاد القبول = عدد صفوف الجسم فعلاً (قبل مطابقة البيان — رخيص وحاسم)
        if (accepted != ws.size()) throw new IllegalArgumentException(TAG_COUNT);

        // بيان المراجعة: دبوس الترويسة + إعادة توليد بايت-لطرف من الجسم
        if (reportJson != null) {
            byte[] provided = reportJson.getBytes(StandardCharsets.UTF_8);
            String expected = statement(batch, accepted, wordsSha, ws);
            if (!CommunityDict.sha256Hex(provided).equals(repSha)
                    || !CommunityDict.sha256Hex(expected.getBytes(StandardCharsets.UTF_8)).equals(repSha))
                throw new IllegalArgumentException(TAG_REPORT);
        }

        // دفاع في العمق: قواعد المراجعة نفسها على كل صف (الممنوع/غير العربي/
        // التشكيل البنيوي/المكرر الداخلي/المكرر مع المثبت — أول مخالفة تفوز)
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ws.size(); i++) {
            Word w = ws.get(i);
            CommunityDict.Row r = new CommunityDict.Row(i + 6,
                    new String[]{w.word, w.diacritized, w.lemma, w.pos, "dpack", "dpack", "CC0-1.0"});
            String tag = CommunityDict.judge(r, installedNorms, seen);
            if (tag != null) throw new IllegalArgumentException(TAG_WORD + ": " + tag);
        }
        return new Pack(batch, sig, ws);
    }

    // ---------------------------------------------------------------- أدوات

    private static String field(String line, String prefix, int hexLen, String tag) {
        if (line == null || !line.startsWith(prefix)) throw new IllegalArgumentException(tag);
        String v = line.substring(prefix.length());
        if (hexLen > 0 && !isHex64(v)) throw new IllegalArgumentException(tag);
        return v;
    }

    private static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** طول أسطر [from, from+n) بالبايتات (UTF-8 + LF) — لتقطيع التوقيع بدقة. */
    private static int lenOf(String[] lines, int from, int n) {
        int t = 0;
        for (int i = from; i < from + n; i++)
            t += lines[i].getBytes(StandardCharsets.UTF_8).length + 1;
        return t;
    }

    /** UTF-8 صارم بلا BOM — الحزمة آلة-مولدة، أي انحراف ترميز رفض كله. */
    private static String strictUtf8(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
            throw new IllegalArgumentException(TAG_SCHEMA);
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(TAG_SCHEMA);
        }
    }
}
