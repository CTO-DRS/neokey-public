package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.hark.neokey.NqfPort.*;

/**
 * بوابة الحيادية المنصية — المرحلة 2، البند C (الأسبوع 4): القارئ المستقل المكتوب
 * من المواصفة يقرأ كل أصول الشحن ويعطي أرقاماً بت-لطرف مطابقة للقارئ الرسمي،
 * والمتجهات الذهبية المثبتة في docs/NQF-GOLDEN.json ما زالت تطابق البايتات المنشورة.
 *
 *   • التثبيت: كل ملف في assets/ — حجمه وsha256 له يطابقان الدبوس الذهبي
 *     (البايتات المراجَعة = البايتات المنشورة، نمط دبوس المتجر).
 *   • المطابقة المزدوجة: NQF.read (الرسمي) وNqfPort.read (من المواصفة فقط) على
 *     nnet_ar.nqf — عدد الموترات، الميتا، ولكل موتر: dtype/dims/بتات المقياس
 *     IEEE-754/crc32/sha256 للخام/تجزئة القيم منزوعة الكمّ — كلها متطابقة.
 *   • المحرك: deqRow في NNet (مسار الإنتاج) يطابق قيم المنفذ بت-لطرف على صفوف حقيقية.
 *   • عقد النصوص: أسطر كل أصل نصي (وفرادته) تطابق الذهبي؛ بنية TAB للثنائيات والإيموجي.
 *   • الزمن: قراءة المنفذ للنموذج كاملاً — p50 < 50ms (مسار تحقق بارد لا استدلال).
 *   • السلبيات على النموذج الحقيقي: تخريب موجَّه بوسم متوقع + انقلابات عشوائية
 *     حتمية — يرفضه القارئان معاً (الاستقلال يُفرض من الجهتين).
 *
 * Verdict: NQFPORT_GATE_OK … / NQFPORT_GATE_FAILED <reason> (exit 1).
 */
public class NqfPortGate {

    static int fails = 0;

    static void need(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("GATE-FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();

        // ── 1) التثبيت الذهبي: كل أصل يطابق دبوسه ──────────────────────────────
        Map<String, ThemePack.Json.Node> golden = ThemePack.Json.parse(
                new String(Files.readAllBytes(new File("docs/NQF-GOLDEN.json").toPath()), StandardCharsets.UTF_8)
        ).obj();
        Map<String, ThemePack.Json.Node> pins = golden.get("assets").obj();

        File dir = new File("assets");
        Set<String> live = new HashSet<>(Arrays.asList(dir.list()));
        Set<String> pinned = pins.keySet();
        need(live.equals(pinned), "pins.set: live=" + live.size() + " pinned=" + pinned.size()
                + (live.equals(pinned) ? "" : " diff=" + diff(live, pinned)));

        int pinOk = 0;
        for (Map.Entry<String, ThemePack.Json.Node> e : pins.entrySet()) {
            byte[] b = Files.readAllBytes(new File(dir, e.getKey()).toPath());
            ThemePack.Json.Node g = e.getValue();
            boolean ok = b.length == (int) g.obj().get("bytes").num()
                    && sha256Hex(b).equals(g.obj().get("sha256").str());
            if (ok) pinOk++; else System.out.println("GATE-FAIL pin." + e.getKey());
        }
        need(pinOk == pins.size(), "pins.count " + pinOk + "/" + pins.size());
        System.out.println("pins=" + pinOk);

        // ── 2) المطابقة المزدوجة على نموذج الشحن ──────────────────────────────
        byte[] model = Files.readAllBytes(new File("assets/nnet_ar.nqf").toPath());
        NQF.File f = NQF.read(model);          // القارئ الرسمي
        NqfPort.Port p = NqfPort.read(model);  // القارئ المستقل (من المواصفة)

        ThemePack.Json.Node mg = golden.get("model");
        need(f.tensorCount() == p.tensorCount, "dual.tensorCount " + f.tensorCount() + "/" + p.tensorCount);
        need(f.tensorCount() == (int) mg.obj().get("tensorCount").num(), "golden.tensorCount");
        need(f.meta.equals(p.meta) && !p.meta.isEmpty(), "dual.meta");
        need(model.length == (int) mg.obj().get("bytes").num() && p.fileBytes == model.length, "golden.modelBytes");
        need(p.paramsQ8() == (long) doubleOf(mg.obj().get("paramsQ8")), "golden.paramsQ8");

        Map<String, ThemePack.Json.Node> gTensors = new java.util.HashMap<String, ThemePack.Json.Node>();
        for (ThemePack.Json.Node n : mg.obj().get("tensors").arr())
            gTensors.put(n.obj().get("name").str(), n);

        int agree = 0, tensors = f.tensorCount();
        List<String> names = f.names();
        for (String n : names) {
            NQF.Tensor a = f.tensor(n);
            Entry b = p.entry(n);
            ThemePack.Json.Node g = gTensors.get(n);
            boolean okName = g != null;
            need(okName, "golden.tensor." + n);
            if (!okName) continue;
            boolean ok = a.dtype == b.dtype
                    && Arrays.equals(a.dims, b.dims)
                    && Float.floatToIntBits(a.scale) == b.scaleBits
                    && Float.floatToIntBits(a.scale) == (int) (double) g.obj().get("scaleBits").num()
                    && (a.dtype == NQF.DT_Q8 ? Arrays.equals(a.q8, b.raw) : Arrays.equals(f32Bytes(a.f32), b.raw))
                    && crc32(b.raw, 0, b.raw.length) == b.crc32
                    && sha256Hex(b.raw).equals(g.obj().get("sha256").str())
                    && digestDeq(b).equals(g.obj().get("deqSha256").str())
                    && digestDeq(b).equals(digestDeqRaw(a.dtype == NQF.DT_Q8 ? a.q8 : f32Bytes(a.f32), a.dtype, a.scale));
            if (ok) agree++; else System.out.println("GATE-FAIL dual." + n);
        }
        need(agree == tensors, "dual.agree " + agree + "/" + tensors);
        System.out.println("tensors=" + tensors + " agree=" + agree);

        // ── 3) المحرك الحقيقي: deqRow بت-لطرف مقابل المنفذ ─────────────────────
        Entry emb = p.entry(names.get(0)); // أول موتر Q8
        int cols = emb.dims[emb.rank - 1];
        int rows = Math.min(emb.raw.length / cols, 512);
        int deqOk = 0;
        float[] viaPort = new float[cols], viaEngine = new float[cols];
        for (int r = 0; r < rows; r++) {
            for (int j = 0; j < cols; j++) viaPort[j] = emb.raw[r * cols + j] * emb.scale;
            NNet.deqRow(emb.raw, emb.scale, r, cols, viaEngine);
            if (NNet.bitEquals(viaPort, viaEngine)) deqOk++; else break;
        }
        need(deqOk == rows, "engine.deq " + deqOk + "/" + rows);
        System.out.println("engine-deq-rows=" + deqOk);

        // ── 4) عقد الأصول النصية ────────────────────────────────────────────────
        int textOk = 0, textN = 0, tabOk = 0;
        for (Map.Entry<String, ThemePack.Json.Node> e : pins.entrySet()) {
            Map<String, ThemePack.Json.Node> g = e.getValue().obj();
            if (!g.containsKey("lines")) continue;
            textN++;
            byte[] b = Files.readAllBytes(new File(dir, e.getKey()).toPath());
            List<String> lines = readTextLines(b);
            Set<String> uniq = new HashSet<String>(lines);
            boolean ok = lines.size() == (int) g.get("lines").num()
                    && uniq.size() == (int) g.get("unique").num();
            if (ok) textOk++; else System.out.println("GATE-FAIL text." + e.getKey());
            if (e.getKey().startsWith("bigram_") || e.getKey().startsWith("emoji_")) {
                int bad = 0;
                for (String ln : lines) if (tabCount(ln) != 1) bad++;
                if (bad == 0 && lines.size() > 0) tabOk++;
                else System.out.println("GATE-FAIL tabs." + e.getKey());
            }
        }
        need(textOk == textN, "text.contract " + textOk + "/" + textN);
        System.out.println("text-assets=" + textN + " tab-structured=" + tabOk);

        // ── 5) زمن قراءة المنفذ على النموذج الحقيقي ────────────────────────────
        int N = 40;
        long[] ms = new long[N];
        for (int i = 0; i < N; i++) {
            long s = System.nanoTime();
            NqfPort.read(model);
            ms[i] = (System.nanoTime() - s) / 1000; // µs
        }
        Arrays.sort(ms);
        double p50 = ms[N / 2] / 1000.0, p95 = ms[(int) (N * 0.95)] / 1000.0;
        need(p50 < 50.0 && p95 < 120.0, "latency p50=" + p50 + " p95=" + p95);
        System.out.println(String.format("port-read p50=%.3fms p95=%.3fms", p50, p95));

        // ── 6) السلبيات على النموذج الحقيقي (منفذ + رسمي معاً) ─────────────────
        int metaLen = p.metaLen, dirStart = 20 + metaLen;
        int nameLen = u16(model, dirStart);
        int dPos = dirStart + 2 + nameLen;               // dtype/rank أول موتر
        int dimsPos = dPos + 2 + 4;                       // أول بُعد
        int rank = model[dPos + 1] & 0xFF;
        int scalePos = dPos + 2 + 4 * rank;
        long blobStart = dirStart + (long) p.dirBytes;

        int neg = 0, negTotal = 0;
        negTotal++; if (targeted(model, mut(model, 1, (byte) 'X'), "magic")) neg++;
        negTotal++; if (targeted(model, mut(model, 4, (byte) 2), "version")) neg++;
        negTotal++; if (targeted(model, mut(model, dimsPos, (byte) 9), "size")) neg++;
        negTotal++; if (targeted(model, mut(model, scalePos + 3, (byte) 0), "scale")) neg++;
        negTotal++; if (targeted(model, flip(model, (int) (blobStart + 11)), "tensor.crc")) neg++;
        negTotal++; if (targeted(model, flip(model, model.length - 3), "trailer.crc")) neg++;
        negTotal++; if (targeted(model, Arrays.copyOf(model, 100), "short")) neg++;
        System.out.println("targeted=" + neg + "/" + negTotal);
        need(neg == negTotal, "negatives.targeted");

        Random rnd = new Random(1616);
        int fuzz = 0, fuzzN = 96;
        for (int i = 0; i < fuzzN; i++) {
            byte[] m = model.clone();
            m[rnd.nextInt(m.length)] ^= (byte) (1 << rnd.nextInt(8));
            if (bothRefuse(m)) fuzz++;
        }
        need(fuzz == fuzzN, "negatives.fuzz " + fuzz + "/" + fuzzN);
        System.out.println("fuzz=" + fuzz + "/" + fuzzN);
        System.out.println("negatives=" + (neg + fuzz));

        // ── الحكم ────────────────────────────────────────────────────────────────
        double secs = (System.nanoTime() - t0) / 1e9;
        if (fails == 0) {
            System.out.printf("NQFPORT_GATE_OK assets=%d tensors=%d params=%d agree=%d negatives=%d p50=%.3fms p95=%.3fms gate=%.2fs%n",
                    pinOk, tensors, p.paramsQ8(), agree, neg + fuzz, p50, p95, secs);
            return;
        }
        System.out.println("NQFPORT_GATE_FAILED checks=" + fails);
        System.exit(1);
    }

    // ── مساعدات ────────────────────────────────────────────────────────────────
    static boolean targeted(byte[] orig, byte[] corrupt, String label) {
        boolean port = refusesPort(corrupt), official = refusesOfficial(corrupt);
        if (port && official) return true;
        System.out.println("GATE-FAIL neg." + label + " port=" + port + " official=" + official);
        return false;
    }

    static boolean refusesPort(byte[] b) { try { NqfPort.read(b); return false; } catch (BadPort e) { return true; } }
    static boolean refusesOfficial(byte[] b) { try { NQF.read(b); return false; } catch (NQF.BadFormat e) { return true; } }
    static boolean bothRefuse(byte[] b) { return refusesPort(b) && refusesOfficial(b); }

    static byte[] mut(byte[] b, int off, byte v) { byte[] m = b.clone(); m[off] = v; return m; }
    static byte[] flip(byte[] b, int off) { byte[] m = b.clone(); m[off] ^= 0x5A; return m; }

    static int tabCount(String s) { int c = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\t') c++; return c; }

    static String diff(Set<String> a, Set<String> b) {
        Set<String> onlyA = new HashSet<String>(a); onlyA.removeAll(b);
        Set<String> onlyB = new HashSet<String>(b); onlyB.removeAll(a);
        return "live-only" + onlyA + " pinned-only" + onlyB;
    }

    static double doubleOf(Object n) { return ((ThemePack.Json.Node) n).num(); }

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
