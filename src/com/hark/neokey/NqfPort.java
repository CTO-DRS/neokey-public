package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NqfPort — القارئ المرجعي المستقل لـ NQF v1 (المرحلة 2، البند C: جاهزية iOS).
 *
 * مكتوب من docs/NQF.md §2 (تخطيط الملف) و§3 (عقد الصرامة) فقط — لا يستورد ولا
 * ينسخ أي سطر من NQF.java. هذا هو ما سيفعله منفّذ Swift: يقرأ المواصفة، يكتب
 * قارئاً، ويطالب بأرقام بت-لطرف مطابقة. إذا اختلفت أرقام هذا القارئ عن أرقام
 * NQF.java (الكاتب/القارئ الرسمي) على أي أصل شحن فالتنسيق نفسه غير حيادي —
 * والبوابة ci/nqfport_gate.sh تقيس ذلك المطابقة على كل أصول الشحن.
 *
 * نقي JVM، صفر android.*، صفر مكتبات خارجية. كل الأعداد LITTLE-ENDIAN.
 *
 * ── العقد العام ──────────────────────────────────────────────────────────────
 *   Port read(byte[] b)                     → يحلّل الملف كاملاً أو يرمي BadPort
 *   Entry { name,dtype,dims[],scaleBits,scale,dataOffset,dataBytes,crc32,raw }
 *     — scaleBits: بتات IEEE-754 الخام (int) كما قرئت من الملف؛ هذا هو الرقم
 *       الذي يجب أن يطابقه Swift (Float32 bitPattern) لا تمثيل العرض العشري.
 *   sha256Hex(byte[])                       → تجزئة قياسية للبايتات الخام
 *   deqBitsF32(entry)                       → بتات float لكل العناصر بعد إزالة
 *                                             الكمّ (q*scale للـ Q8، كما-هي للـ F32)
 *   digestDeq(entry)                        → sha256hex على بتات deqBitsF32
 *   readTextLines(byte[])                   → عقد الأصول النصية (قواميس/ثنائيات/
 *                                             إيموجي): UTF-8، سطر لكل سجل، فواصل
 *                                             \n أو \r\n، الأسطر الفارغة تُتخطى.
 */
public final class NqfPort {

    public static final int DT_Q8 = 1;   // int8 متماثل
    public static final int DT_F32 = 2;  // float32
    private static final int HEADER = 20; // magic4+ver2+flags2+count4+dir4+meta4
    private static final int TRAILER = 4;
    private static final int MAX_TENSORS = 65535;
    private static final int MAX_META = 1 << 24;
    private static final int MAX_RANK = 6;
    private static final long MAX_PRODUCT = (long) Integer.MAX_VALUE;

    /** تُرمى عند أي خرق بنيوي/سلامة — لا يُعاد أبداً نموذج ناقص بصمت. */
    public static final class BadPort extends RuntimeException {
        public final String tag; // وسم الرفض الحرفي — البوابة تطابقه بين القارئين
        public BadPort(String tag, String msg) { super(tag + ": " + msg); this.tag = tag; }
    }

    /** موتر واحد كما قرئ من الدليل + بايتاته الخامة. */
    public static final class Entry {
        public final String name;
        public final int dtype;
        public final int rank;
        public final int[] dims;
        public final int scaleBits;  // IEEE-754 الخام (المرجع لِلط-لطرف)
        public final float scale;
        public final long dataOffset; // مطلق داخل منطقة الكتل
        public final int dataBytes;
        public final int crc32;       // المخزن في الدليل
        public final byte[] raw;      // بايتات الموتر الخام
        Entry(String name, int dtype, int rank, int[] dims, int scaleBits,
              long dataOffset, int dataBytes, int crc32, byte[] raw) {
            this.name = name; this.dtype = dtype; this.rank = rank; this.dims = dims;
            this.scaleBits = scaleBits; this.scale = Float.intBitsToFloat(scaleBits);
            this.dataOffset = dataOffset; this.dataBytes = dataBytes;
            this.crc32 = crc32; this.raw = raw;
        }
        /** value = q × scale للعنصر i (Q8 فقط). */
        public float q8At(int i) { return raw[i] * scale; }
    }

    /** ملف محلول بالكامل: الترويسة + الدليل + الميتا + نتيجة المقتبس. */
    public static final class Port {
        public final int version, flags, tensorCount, metaLen, dirBytes;
        public final String meta;
        public final List<Entry> entries;
        public final int trailerCrc;   // المخزن
        public final int computedCrc;  // المحسوب على [0, n-4)
        public final int fileBytes;
        Port(int version, int flags, int tensorCount, int metaLen, int dirBytes,
             String meta, List<Entry> entries, int trailerCrc, int computedCrc, int fileBytes) {
            this.version = version; this.flags = flags; this.tensorCount = tensorCount;
            this.metaLen = metaLen; this.dirBytes = dirBytes; this.meta = meta;
            this.entries = entries; this.trailerCrc = trailerCrc; this.computedCrc = computedCrc;
            this.fileBytes = fileBytes;
        }
        public Entry entry(String name) {
            for (Entry e : entries) if (e.name.equals(name)) return e;
            throw new BadPort("tensor.missing", "tensor not found: " + name);
        }
        public long paramsQ8() { // عدد معاملات Q8 (للتحقق من أرقام التدريب)
            long n = 0; for (Entry e : entries) if (e.dtype == DT_Q8) n += e.raw.length;
            return n;
        }
    }

    private NqfPort() { }

    // ════════════════════ القراءة الصارمة (من المواصفة §2+§3) ════════════════════

    public static Port read(byte[] b) {
        if (b == null) throw new BadPort("null", "input is null");
        int n = b.length;
        if (n < HEADER + TRAILER) throw new BadPort("short", "file shorter than header+trailer: " + n);

        // §3.1 سحر NQF1 وإصدار 1 بالضبط
        if (!(b[0] == 'N' && b[1] == 'Q' && b[2] == 'F' && b[3] == '1'))
            throw new BadPort("magic", "bad magic");
        int version = u16(b, 4);
        if (version != 1) throw new BadPort("version", "unsupported version " + version);
        int flags = u16(b, 6); // محجوز — يقبل أي قيمة
        long tensorCountL = u32(b, 8);
        if (tensorCountL > MAX_TENSORS) throw new BadPort("tensor.count", "tensorCount " + tensorCountL);
        long dirBytesL = u32(b, 12);
        long metaLenL = u32(b, 16);
        if (metaLenL > MAX_META) throw new BadPort("meta.size", "metaLen " + metaLenL);
        int tensorCount = (int) tensorCountL;

        // حدود المناطق: [20, 20+metaLen) ميتا، ثم الدليل dirBytes، ثم كتل، ثم مقتبس 4
        long metaEnd = (long) HEADER + metaLenL;
        long dirEnd = metaEnd + dirBytesL;
        long blobStart = dirEnd;
        long blobEnd = n - TRAILER;
        if (dirEnd > blobEnd) throw new BadPort("region", "meta+dir overrun the blob region");

        String meta = metaLenL == 0 ? "" : new String(b, HEADER, (int) metaLenL, StandardCharsets.UTF_8);

        // §3.3 أسماء غير فارغة وفريدة؛ dtype ∈ {1,2}؛ الرتبة 1..6؛ الأبعاد/الطول؛ المقياس
        Set<String> names = new HashSet<String>();
        List<Entry> entries = new ArrayList<Entry>(tensorCount);
        int p = (int) metaEnd; // مؤشر القراءة داخل الدليل
        int dirRead = 0;
        for (int t = 0; t < tensorCount; t++) {
            if (p + 2 > blobEnd) throw new BadPort("dir.overrun", "directory truncated at tensor " + t);
            int nameLen = u16(b, p); p += 2; dirRead += 2;
            if (nameLen == 0) throw new BadPort("name.empty", "empty tensor name at " + t);
            if (p + nameLen > blobEnd) throw new BadPort("dir.overrun", "name truncated at tensor " + t);
            String name = new String(b, p, nameLen, StandardCharsets.UTF_8); p += nameLen; dirRead += nameLen;
            if (!names.add(name)) throw new BadPort("name.dup", "duplicate tensor name " + name);
            if (p + 2 > blobEnd) throw new BadPort("dir.overrun", "dtype/rank truncated at " + name);
            int dtype = b[p] & 0xFF; int rank = b[p + 1] & 0xFF; p += 2; dirRead += 2;
            if (dtype != DT_Q8 && dtype != DT_F32) throw new BadPort("dtype", "dtype " + dtype + " on " + name);
            if (rank < 1 || rank > MAX_RANK) throw new BadPort("rank", "rank " + rank + " on " + name);
            if (p + rank * 4 > blobEnd) throw new BadPort("dir.overrun", "dims truncated at " + name);
            int[] dims = new int[rank];
            long product = 1;
            for (int i = 0; i < rank; i++) {
                dims[i] = (int) u32(b, p); p += 4; dirRead += 4;
                if (dims[i] <= 0) throw new BadPort("dims", "non-positive dim on " + name);
                product *= dims[i];
                if (product > MAX_PRODUCT) throw new BadPort("product", "product overflows array on " + name);
            }
            if (p + 4 > blobEnd) throw new BadPort("dir.overrun", "scale truncated at " + name);
            int scaleBits = le32(b, p); p += 4; dirRead += 4;
            if (dtype == DT_Q8) { // §3.6 مقياس Q8 منتهٍ > 0
                float s = Float.intBitsToFloat(scaleBits);
                if (!(s > 0f) || Float.isNaN(s) || Float.isInfinite(s))
                    throw new BadPort("scale", "bad Q8 scale on " + name);
            }
            if (p + 8 > blobEnd) throw new BadPort("dir.overrun", "offset/size truncated at " + name);
            long off = u32(b, p); p += 4; dirRead += 4;
            long dlen = u32(b, p); p += 4; dirRead += 4;
            // §3.5 حاصل الأبعاد = طول البيانات (Q8) أو ÷4 (F32)
            long want = dtype == DT_Q8 ? product : product * 4L;
            if (want != dlen) throw new BadPort("size", "dims product " + product + " wants " + want + " bytes, dir says " + dlen + " on " + name);
            if (dlen > Integer.MAX_VALUE) throw new BadPort("size", "tensor too large for JVM array: " + name);
            if (p + 4 > blobEnd) throw new BadPort("dir.overrun", "crc truncated at " + name);
            int crc = le32(b, p); p += 4; dirRead += 4;
            // §3.7 الكتل بعد الدليل حصراً وداخل [0, n-4)
            // الإزاحة مطلقة في الملف (من 0) — أثبتها المنفذ: الكاتب يبدأ من blobStart.
            long abs = off;
            if (off < 0 || abs < blobStart || abs + dlen > blobEnd)
                throw new BadPort("blob.range", "blob outside region on " + name);
            for (Entry prev : entries) { // لا تباarian — الكتل المتراكبة ترفض
                long ps = prev.dataOffset, pe = ps + prev.dataBytes;
                if (abs < pe && ps < abs + dlen)
                    throw new BadPort("blob.overlap", "blob overlap " + name + " / " + prev.name);
            }
            byte[] raw = new byte[(int) dlen];
            System.arraycopy(b, (int) abs, raw, 0, (int) dlen);
            int actual = crc32(raw, 0, raw.length);
            if (actual != crc) throw new BadPort("tensor.crc", "tensor crc mismatch on " + name);
            entries.add(new Entry(name, dtype, rank, dims, scaleBits, off, (int) dlen, crc, raw));
        }
        if (dirRead != (int) dirBytesL) // الدليل يُستهلك بالضبط — لا بايتات فائضة
            throw new BadPort("dir.slack", "directory slack: declared " + dirBytesL + " consumed " + dirRead);

        // المقتبس: crc32 على [0, n-4)
        int stored = le32(b, n - 4);
        int computed = crc32(b, 0, n - 4);
        if (stored != computed) throw new BadPort("trailer.crc", "file trailer crc mismatch");

        return new Port(version, flags, tensorCount, (int) metaLenL, (int) dirBytesL,
                meta, entries, stored, computed, n);
    }

    // ════════════════════ الأرقام القياسية (بت-لطرف) ════════════════════

    /** SHA-256 hex للبايتات الخامة — الرقم المرجعي لأي منفّذ. */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte x : md.digest(data)) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** بتات float (IEEE-754) لكل العناصر بعد إزالة الكمّ — الرقم الذي يطابقه Swift bitPattern. */
    public static int[] deqBitsF32(Entry e) {
        int m = e.raw.length / (e.dtype == DT_F32 ? 4 : 1);
        int[] out = new int[m];
        if (e.dtype == DT_F32) {
            for (int i = 0; i < m; i++) out[i] = le32(e.raw, i * 4);
        } else {
            float s = e.scale;
            for (int i = 0; i < m; i++) out[i] = Float.floatToIntBits(e.raw[i] * s);
        }
        return out;
    }

    /** sha256hex فوق بتات القيم منزوعة الكمّ (big-endian hex لكل بت int) — نفس الأرقام في Swift. */
    public static String digestDeq(Entry e) { return digestDeqRaw(e.raw, e.dtype, e.scale); }

    /** النسخة الخام — تُستخدم أيضاً لمقارنة مخرجات القارئ الرسمي NQF.java بنفس الخوارزمية. */
    public static String digestDeqRaw(byte[] raw, int dtype, float scale) {
        int step = dtype == DT_F32 ? 4 : 1;
        int m = raw.length / step;
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception ex) { throw new IllegalStateException(ex); }
        byte[] buf = new byte[4];
        for (int i = 0; i < m; i++) {
            int v = dtype == DT_F32 ? le32(raw, i * 4) : Float.floatToIntBits(raw[i] * scale);
            buf[0] = (byte) (v >>> 24); buf[1] = (byte) (v >>> 16); buf[2] = (byte) (v >>> 8); buf[3] = (byte) v;
            md.update(buf);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte x : md.digest()) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** عقد الأصول النصية: UTF-8، \n أو \r\n، الأسطر الفارغة تُتخطى (سلوك readLine في Dictionary). */
    public static List<String> readTextLines(byte[] b) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                int end = i;
                if (end > start && b[end - 1] == '\r') end--;
                if (end > start) out.add(new String(b, start, end - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < b.length) {
            int end = b.length;
            if (end > start && b[end - 1] == '\r') end--;
            if (end > start) out.add(new String(b, start, end - start, StandardCharsets.UTF_8));
        }
        return out;
    }

    // ════════════════════ بدائل منخفضة المستوى (لا مكتبات) ════════════════════

    static int u16(byte[] b, int off) { return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8); }
    static long u32(byte[] b, int off) { return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24); }
    static int le32(byte[] b, int off) { return (int) u32(b, off); }

    static int crc32(byte[] b, int off, int len) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b, off, len);
        return (int) (c.getValue() & 0xFFFFFFFFL);
    }
}
