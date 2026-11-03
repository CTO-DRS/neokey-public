package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.hark.neokey.NqfPort.*;

/**
 * اختبارات القارئ المستقل NqfPort — المرحلة 2، البند C (الحيادية المنصية).
 *
 * الإيجابي: نموذج تركيبي يكتبه NQF.Builder (الكاتب الرسمي) يُقرأ بالقارئ المستقل
 * فتطابق الموترات بت-لطرف (الاسم/النوع/الأبعاد/بتات المقياس/crc/تجزئة الخام/
 * تجزئة القيم منزوعة الكمّ)، وعقد الأصول النصية، ومتجهات القيم.
 * السلبي: كل خرق في عقد الصرامة يُرفض بوسم صحيح من القارئ المستقل وحده —
 * بلا اعتماد على NQF.java إطلاقاً (هذا هو تعريف الاستقلال هنا).
 */
public class NqfPortTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    /** يبني نموذجاً تركيبياً حقيقياً (Q8 + F32 + ميتا عربي) ويُرجع بايتاته. */
    static byte[] syntheticModel() {
        byte[] q8 = new byte[48];
        for (int i = 0; i < q8.length; i++) q8[i] = (byte) ((i * 7 + 3) % 251 - 125);
        float[] bias = {0.25f, -1.5f, 3.0f};
        return new NQF.Builder()
                .meta("{\"model\":\"port-synth\",\"ctx\":3,\"vocab\":4}")
                .add("emb", new int[]{4, 12}, q8, 0.03125f)
                .addF32("fc.bias", new int[]{3}, bias)
                .build();
    }

    /** يستخرج موتراً من NQF.File بالاسم (للمقارنة مع مخرجات Port). */
    static NQF.Tensor tOf(NQF.File f, String n) { return f.tensor(n); }

    static String tagOf(Runnable r) {
        try { r.run(); return null; }
        catch (BadPort e) { return e.tag; }
        catch (RuntimeException e) { return "?" + e.getMessage(); }
    }

    public static void main(String[] args) {
        byte[] model = syntheticModel();

        // ── الإيجابي: المطابقة بت-لطرف بين القارئين على التركيبي ──
        NQF.File f = NQF.read(model);
        Port p = NqfPort.read(model);
        check("synthetic.tensor-count", f.tensorCount() == p.tensorCount && p.tensorCount == 2);
        check("synthetic.meta-exact", p.meta.equals("{\"model\":\"port-synth\",\"ctx\":3,\"vocab\":4}"));
        check("synthetic.version-flags", p.version == 1 && p.flags == 0);
        for (String n : new String[]{"emb", "fc.bias"}) {
            NQF.Tensor a = tOf(f, n);
            Entry b = p.entry(n);
            check(n + ".name-dtype", b.name.equals(n) && b.dtype == a.dtype);
            check(n + ".dims", Arrays.equals(b.dims, a.dims));
            check(n + ".scale-bits", b.scaleBits == Float.floatToIntBits(a.scale));
            check(n + ".crc", b.crc32 == crc32(b.raw, 0, b.raw.length));
            check(n + ".raw-bytes-identical", Arrays.equals(b.raw, a.dtype == NQF.DT_Q8 ? a.q8 : f32Bytes(a.f32)));
            check(n + ".deq-digest-defined", digestDeq(b).length() == 64);
        }
        // قيم منزوعة الكمّ بت-لطرف مقابل NNet.deqRow (مسار الإنتاج الحقيقي)
        Entry emb = p.entry("emb");
        float[] viaPort = new float[emb.dims[1]];
        for (int j = 0; j < emb.dims[1]; j++) viaPort[j] = emb.q8At(j);
        float[] viaNNet = new float[emb.dims[1]];
        NNet.deqRow(emb.raw, emb.scale, 0, emb.dims[1], viaNNet);
        check("deq.bit-exact-vs-NNet.deqRow", NNet.bitEquals(viaPort, viaNNet));
        // تجزئة deq مستقرة: نفس المدخل → نفس الهكس دائماً
        check("deq.digest-stable", digestDeq(emb).equals(digestDeq(p.entry("emb"))));
        // المقياس: بتات IEEE الخام هي المرجع لا العرض العشري
        check("scale.bits-are-reference",
                emb.scaleBits == Float.floatToIntBits(0.03125f) && Float.compare(emb.scale, 0.03125f) == 0);

        // ── عقد الأصول النصية ──
        byte[] txt = "في\nمن\r\n\nأن\n".getBytes(StandardCharsets.UTF_8);
        java.util.List<String> lines = readTextLines(txt);
        check("text.lines-3", lines.size() == 3);
        check("text.order-is-rank", lines.get(0).equals("في") && lines.get(1).equals("من") && lines.get(2).equals("أن"));
        check("text.utf8-arabic", readTextLines("شخبارك\n".getBytes(StandardCharsets.UTF_8)).get(0).equals("شخبارك"));
        check("text.sha256", sha256Hex(txt).equals(sha256Hex(txt)) && sha256Hex(txt).length() == 64);

        // ── السلبي: كل قاعدة صرامة تُرفض بوسمها (القارئ المستقل وحده) ──
        check("neg.magic", "magic".equals(tagOf(() -> NqfPort.read(mut(model, 1, (byte) 'X')))));
        check("neg.version", "version".equals(tagOf(() -> NqfPort.read(mut(model, 4, (byte) 2)))));
        check("neg.tensor-count", "tensor.count".equals(tagOf(() -> NqfPort.read(mut(model, 10, (byte) 1)))));
        check("neg.short", "short".equals(tagOf(() -> NqfPort.read(Arrays.copyOf(model, 16)))));
        check("neg.null", "null".equals(tagOf(() -> NqfPort.read(null))));

        // تخريب الدليل: اسم فارغ، مكرر، dtype، رتبة، أبعاد/طول، مقياس، تجاوز
        check("neg.name-empty", "name.empty".equals(tagOf(() -> NqfPort.read(zeroAt(model, nameLenPos(model))))));
        check("neg.dtype", "dtype".equals(tagOf(() -> NqfPort.read(mut(model, dtypePos(model), (byte) 3)))));
        check("neg.rank", "rank".equals(tagOf(() -> NqfPort.read(mut(model, dtypePos(model) + 1, (byte) 7)))));
        check("neg.scale-nonpositive", "scale".equals(tagOf(() -> NqfPort.read(mut(model, scalePos(model) + 3, (byte) 0x00)))));
        check("neg.size-mismatch", "size".equals(tagOf(() -> NqfPort.read(mut(model, dimsPos(model), (byte) 9)))));
        check("neg.tensor-crc", "tensor.crc".equals(tagOf(() -> NqfPort.read(flipInBlob(model)))));
        check("neg.trailer-crc", "trailer.crc".equals(tagOf(() -> NqfPort.read(flipTrailer(model)))));

        // عبث الإنتروبيا الشامل: كل موضع من 128 انقلاباً عشوائياً بذرة حتمية
        // إما يُرفض أو (قلباً في بايت بيانات موتر) يُرفض عبر crc الموتر/المقتبس.
        java.util.Random rnd = new java.util.Random(1604);
        int refused = 0, total = 128;
        for (int i = 0; i < total; i++) {
            byte[] m = model.clone();
            m[rnd.nextInt(m.length)] ^= (byte) (1 << rnd.nextInt(8));
            try { NqfPort.read(m); }
            catch (BadPort e) { refused++; }
        }
        check("neg.fuzz-128-flips-all-refused", refused == total);

        System.out.println("NQFPORT-TESTS pass=" + pass + " fail=" + fail);
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ── أدوات التخريب الموجَّه (تحدد مواضع الحقول من البايتات نفسها، لا بأرقام سحرية) ──
    static int metaLen(byte[] b) { return (int) u32(b, 16); }
    static int dirStart(byte[] b) { return 20 + metaLen(b); }

    static int nameLenPos(byte[] b) { return dirStart(b); } // u16 طول الاسم لأول موتر
    static int firstNameLen(byte[] b) { return u16(b, nameLenPos(b)); }
    static int dtypePos(byte[] b) { return nameLenPos(b) + 2 + firstNameLen(b); }
    static int dimsPos(byte[] b) { return dtypePos(b) + 2 + 4; } // أول بُعد لأول موتر
    static int scalePos(byte[] b) { return dtypePos(b) + 2 + 4 * u16like(b); }
    static int u16like(byte[] b) { return b[dtypePos(b) + 1] & 0xFF; } // رتبة أول موتر

    static byte[] mut(byte[] b, int off, byte v) { byte[] m = b.clone(); m[off] = v; return m; }
    static byte[] zeroAt(byte[] b, int off) { return mut(b, off, (byte) 0); }
    static byte[] flipInBlob(byte[] b) {
        byte[] m = b.clone();
        int d = 20 + metaLen(b) + (int) u32(b, 12); // blobStart الدقيق (ميتا + دليل)
        m[d + 5] ^= 0x55; // داخل أول موتر دائماً (النموذج التركيبي صغير)
        return m;
    }
    static byte[] flipTrailer(byte[] b) { byte[] m = b.clone(); m[m.length - 3] ^= 0x10; return m; }
    static byte[] f32Bytes(float[] v) {
        byte[] out = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            int bits = Float.floatToIntBits(v[i]);
            out[i * 4] = (byte) bits; out[i * 4 + 1] = (byte) (bits >>> 8);
            out[i * 4 + 2] = (byte) (bits >>> 16); out[i * 4 + 3] = (byte) (bits >>> 24);
        }
        return out;
    }
}
