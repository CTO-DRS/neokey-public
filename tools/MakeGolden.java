import com.hark.neokey.NQF;
import com.hark.neokey.NqfPort;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * مولّد المتجهات الذهبية — المرحلة 2، البند C.
 *
 * يقرأ كل أصول الشحن من assets/ ويصدر docs/NQF-GOLDEN.json:
 *   - لكل أصل: الحجم + sha256 للبايتات (الدبوس — البايتات المراجَعة = البايتات المنشورة)
 *   - للأصول النصية: عدد الأسطر غير الفارغة + الفريدة (عقد القارئ النصي)
 *   - للنموذج: عدد الموترات، الميتا، لكل موتر: dtype/dims/scaleBits (بتات IEEE الخام)/
 *     crc32/sha256 للخام/تجزئة القيم منزوعة الكمّ — الأرقام التي يجب أن يطابقها
 *     أي قارئ مكتوب من المواصفة (Swift مثلاً) بت-لطرف.
 *
 * تشغيل: java -cp build/porttest MakeGolden  (من جذر المشروع)
 */
public final class MakeGolden {

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"spec\": \"NQF v1\",\n");
        sb.append("  \"contract\": \"docs/NQF-PORT.md\",\n");
        sb.append("  \"generatedAt\": \"").append(java.time.Instant.now().toString()).append("\",\n");
        sb.append("  \"assets\": {\n");

        File dir = new File("assets");
        File[] files = dir.listFiles();
        Arrays.sort(files);
        Set<String> textNames = new HashSet<>(Arrays.asList(
                "ar.txt", "ar_gulf.txt", "ar_hijazi.txt", "ar_najdi.txt", "en.txt",
                "bigram_ar_base.txt", "emoji_kw.txt", "testset_ar.txt", "testset_dialects.txt",
                "testset_en.txt", "testset_switch.txt", "ui_en.txt"));

        boolean first = true;
        byte[] model = null;
        for (File f : files) {
            byte[] b = Files.readAllBytes(f.toPath());
            String sha = NqfPort.sha256Hex(b);
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    \"").append(f.getName()).append("\": {\"bytes\": ").append(b.length)
              .append(", \"sha256\": \"").append(sha).append("\"");
            if (textNames.contains(f.getName())) {
                java.util.List<String> lines = NqfPort.readTextLines(b);
                Set<String> uniq = new HashSet<>(lines);
                sb.append(", \"lines\": ").append(lines.size()).append(", \"unique\": ").append(uniq.size());
            }
            sb.append(" }");
            if (f.getName().equals("nnet_ar.nqf")) model = b;
        }
        sb.append("\n  },\n");

        if (model == null) { System.err.println("assets/nnet_ar.nqf not found"); System.exit(1); }
        NQF.File f = NQF.read(model);
        NqfPort.Port p = NqfPort.read(model);
        sb.append("  \"model\": {\n");
        sb.append("    \"file\": \"nnet_ar.nqf\",\n    \"bytes\": ").append(model.length).append(",\n");
        sb.append("    \"tensorCount\": ").append(f.tensorCount()).append(",\n");
        sb.append("    \"metaLen\": ").append(p.metaLen).append(",\n");
        sb.append("    \"dirBytes\": ").append(p.dirBytes).append(",\n");
        sb.append("    \"paramsQ8\": ").append(p.paramsQ8()).append(",\n");
        sb.append("    \"tensors\": [\n");
        java.util.List<String> names = f.names();
        java.util.Collections.sort(names);
        for (int i = 0; i < names.size(); i++) {
            NQF.Tensor t = f.tensor(names.get(i));
            byte[] raw = t.dtype == NQF.DT_Q8 ? t.q8 : f32Bytes(t.f32);
            sb.append("      {\"name\": \"").append(t.name).append("\", \"dtype\": ").append(t.dtype)
              .append(", \"dims\": ").append(dimsJson(t.dims))
              .append(", \"scaleBits\": ").append(Float.floatToIntBits(t.scale))
              .append(", \"crc32\": ").append(crcUnsigned(raw))
              .append(", \"sha256\": \"").append(NqfPort.sha256Hex(raw)).append("\"")
              .append(", \"deqSha256\": \"").append(NqfPort.digestDeqRaw(raw, t.dtype, t.scale)).append("\"")
              .append("}").append(i < names.size() - 1 ? "," : "").append("\n");
        }
        sb.append("    ]\n  }\n}\n");
        Files.write(new File("docs/NQF-GOLDEN.json").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("golden written: assets=" + (files.length) + " tensors=" + f.tensorCount()
                + " paramsQ8=" + p.paramsQ8() + " bytes=" + model.length);
    }

    static long crcUnsigned(byte[] b) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b, 0, b.length);
        return c.getValue();
    }

    static String dimsJson(int[] d) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < d.length; i++) sb.append(d[i]).append(i < d.length - 1 ? "," : "");
        return sb.append("]").toString();
    }

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
