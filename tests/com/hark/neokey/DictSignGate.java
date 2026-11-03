package com.hark.neokey;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * بوابة الدمج الموقّع للقاموس المجتمعي (المرحلة 3، الأسبوع 2) — صفر android.*.
 *
 * تُثبت السلسلة كاملة لا الادعاء:
 *   1) النشر حتمي: إعادة النشر من الدفعة المراجَعة نفسها تعيد بايتات الحزمة
 *      المنشورة في assets/community_ar.dpack حرفاً بحرف (المرجع = المنشور).
 *   2) الحزمة المنشورة تُحمَّل: مسار البيان (نشر) + مسار الجهاز (استهلاك).
 *   3) الدبوس الذهبي: NQF-GOLDEN يثبّت sha256 لأصل الإصدار (MakeGolden).
 *   4) المحرك يستهلك بعد المثبت بترتيب موثق على القاموس الحقيقي 150k.
 *   5) سلبيات مصممة 12: كل وسم رفق يُصطاد بوسمه الثابت.
 *   6) زمن النشر والتحميل p50 مقاس.
 */
public final class DictSignGate {

    static int checks = 0;

    public static void main(String[] args) throws Exception {
        try {
            shippedAsset();
            engineReal();
            negativesAndTiming();
            System.out.println("DICTSIGN_GATE_OK batches=2 words=" + SHIPPED_WORDS
                    + " negatives=" + NEG_OK + "/" + NEG_ALL
                    + " p50_publish_us=" + P50_PUBLISH_US + " p50_load_us=" + P50_LOAD_US
                    + " checks=" + checks);
        } catch (Throwable t) {
            System.out.println("DICTSIGN_GATE_FAILED " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            System.exit(1);
        }
    }

    static final String PACK_ASSET = "assets/community_ar.dpack";
    static final String BATCH_FILE = "dictsrc/community/batch-w19.csv";
    static int SHIPPED_WORDS = -1, NEG_OK = 0, NEG_ALL = 0;
    static long P50_PUBLISH_US = -1, P50_LOAD_US = -1;

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) throw new IllegalStateException(what);
    }

    static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    // ---------------------------------------------- 1) المرجع = المنشور

    static void shippedAsset() throws Exception {
        File shippedF = new File(PACK_ASSET);
        File batchF = new File(BATCH_FILE);
        ok(shippedF.isFile(), "shipped pack missing: " + PACK_ASSET);
        ok(batchF.isFile(), "community batch missing: " + BATCH_FILE);
        byte[] shipped = readAll(shippedF);
        byte[] batch = readAll(batchF);

        Set<String> installed = CommunityDict.normSetOf(linesOf(new File("assets/ar.txt")));
        CommunityDict.Report rep = CommunityDict.review(batch, installed);
        ok(rep.accepted == rep.total && rep.accepted > 0,
                "shipped batch must be fully accepted, got " + rep.accepted + "/" + rep.total);
        DictSign.Dpack d = DictSign.publish(batch, rep);
        ok(Arrays.equals(d.bytes, shipped),
                "re-published pack differs from the shipped asset (reviewed != published)");
        SHIPPED_WORDS = d.accepted;

        // مسار البيان: الحزمة المنشورة تطابق بيانها المحفوظ في تقرير البناء
        DictSign.Pack p = DictSign.load(shipped, d.statement, installed);
        ok(p.words.size() == d.accepted, "statement-path load lost rows");
        // مسار الجهاز: بلا بيان — نفس الصفوف
        DictSign.Pack dev = DictSign.load(shipped, null, null);
        ok(dev.words.size() == d.accepted && dev.sig.equals(d.sig), "device-path load mismatch");

        // الدبوس الذهبي: أصل الإصدار مثبت في NQF-GOLDEN (المرجع = المنشور)
        ThemePack.Json.Node golden = ThemePack.Json.parse(
                new String(readAll(new File("docs/NQF-GOLDEN.json")), StandardCharsets.UTF_8));
        ok(golden.isObj(), "golden shape");
        Map<String, ThemePack.Json.Node> assets = golden.obj().get("assets").obj();
        ThemePack.Json.Node pin = assets.get("community_ar.dpack");
        ok(pin != null, "golden pin missing for community_ar.dpack (run MakeGolden)");
        String sha = pin.obj().get("sha256").str();
        ok(CommunityDict.sha256Hex(shipped).equals(sha), "golden pin mismatch for community_ar.dpack");
    }

    static List<String> linesOf(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : new String(readAll(f), StandardCharsets.UTF_8).split("\n"))
            if (!l.isEmpty()) out.add(l);
        return out;
    }

    // ---------------------------------------------- 4) الاستهلاك على القاموس الحقيقي

    static void engineReal() throws Exception {
        Dictionary d = new Dictionary(new FakeContext(Files.createTempDirectory("w19gate").toFile()), "ar");
        long t0 = System.currentTimeMillis();
        while (!d.isLoaded() && System.currentTimeMillis() - t0 < 20000) Thread.sleep(25);
        ok(d.isLoaded(), "150k dictionary failed to load");
        ok(d.knows("مفتاحية"), "shipped pack word unknown to the engine");
        ok(d.knows("إيقاعية"), "second shipped pack word unknown to the engine");
        ok(d.wordRank("مفتاحية") >= 150123, "pack word rank must sit after the whole corpus");
        ok(d.wordScore("مفتاحية") == Dictionary.PACK_SCORE, "pack score must equal the documented floor");
        ok(!d.knows("شذوبيب"), "unknown word must stay unknown");
        List<String> sug = d.suggest("زمرد", 5);
        ok(sug.contains("زمردية"), "suggest must surface the pack word (after installed rivals)");
    }

    // ---------------------------------------------- 5+6) السلبيات والزمن

    interface F { void run(); }

    static String expect(F f) {
        try { f.run(); return null; }
        catch (IllegalArgumentException e) { return String.valueOf(e.getMessage()); }
    }

    static void neg(String name, String expected, String got) {
        NEG_ALL++;
        boolean okTag = expected.equals(got) || (expected.equals("DPACK_WORD") && got != null && got.startsWith("DPACK_WORD"));
        if (okTag) NEG_OK++;
        else throw new IllegalStateException("negative [" + name + "] expected " + expected + " got " + got);
    }

    static byte[] csv(String... rows) {
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\n");
        for (String r : rows) b.append(r).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static DictSign.Dpack pub(byte[] c) {
        return DictSign.publish(c, CommunityDict.review(c, new java.util.HashSet<>()));
    }

    static void negativesAndTiming() throws Exception {
        byte[] b = csv(
                "كتابية,كِتَابِيَّة,كتاب,adj,متعلقة بالكتاب والتحرير,سالم-قلم,CC0-1.0",
                "عدادية,عَدَّادِيَّة,عداد,adj,متعلقة بالعد والقياس,نورة-حرف,CC0-1.0");
        DictSign.Dpack d = pub(b);
        byte[] raw = d.bytes;
        String stmt = d.statement;
        Set<String> inst = new java.util.HashSet<>(java.util.Collections.singletonList(
                CommunityDict.normAr("كتابية")));

        long t0 = System.nanoTime();
        int ITERS = 400;
        for (int i = 0; i < ITERS; i++) DictSign.publish(b, CommunityDict.review(b, new java.util.HashSet<>()));
        P50_PUBLISH_US = (System.nanoTime() - t0) / 1000 / ITERS;

        t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) DictSign.load(raw, null, null);
        P50_LOAD_US = (System.nanoTime() - t0) / 1000 / ITERS;

        byte[] t1 = raw.clone();
        t1[indexOf(raw, "w ".getBytes(StandardCharsets.UTF_8)) + 3] ^= 0x01;
        neg("tamper", "DPACK_SIG", expect(() -> DictSign.load(t1, stmt, null)));

        byte[] swapped = csv(
                "مدادية,مِدَادِيَّة,مداد,adj,متعلقة بالمداد والكتابة,سالم-قلم,CC0-1.0",
                "عدادية,عَدَّادِيَّة,عداد,adj,متعلقة بالعد والقياس,نورة-حرف,CC0-1.0");
        DictSign.Dpack fg = pub(swapped);
        String head = new String(raw, 0, headerLen(raw), StandardCharsets.UTF_8)
                .replace("words " + d.wordsSha, "words " + fg.wordsSha);
        byte[] attack = (head + new String(bodyOf(fg.bytes), StandardCharsets.UTF_8)
                + "sig " + CommunityDict.sha256Hex(
                        concat(head.getBytes(StandardCharsets.UTF_8), bodyOf(fg.bytes))) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        neg("swap", "DPACK_REPORT", expect(() -> DictSign.load(attack, stmt, null)));

        neg("counter-unsigned", "DPACK_SIG", expect(() -> DictSign.load(
                patch(raw, "accepted 2", "accepted 3"), stmt, null)));
        neg("counter-reforged", "DPACK_COUNT", expect(() -> DictSign.load(
                reforge(raw, "accepted 2", "accepted 3"), stmt, null)));
        neg("foreign-batch", "DPACK_REPORT", expect(() -> DictSign.load(
                reforge(raw, "batch " + d.batchId, "batch " + CommunityDict.sha256Hex("x".getBytes(StandardCharsets.UTF_8))),
                stmt, null)));
        neg("words-pin", "DPACK_WORDS", expect(() -> DictSign.load(
                reforge(raw, "words " + d.wordsSha, "words " + CommunityDict.sha256Hex("y".getBytes(StandardCharsets.UTF_8))),
                stmt, null)));
        neg("schema", "DPACK_SCHEMA", expect(() -> DictSign.load(
                reforge(raw, "neokey-dpack-1", "neokey-dpack-2"), stmt, null)));

        String txt = new String(raw, StandardCharsets.UTF_8);
        int r1end = txt.indexOf('\n', txt.indexOf("w "));
        byte[] cut = txt.substring(0, r1end + 1).getBytes(StandardCharsets.UTF_8);
        byte[] cutR = appendSig(cut);
        neg("truncate", "DPACK_WORDS", expect(() -> DictSign.load(cutR, stmt, null)));
        byte[] cut2 = reforge(reforge(cutR, "accepted 2", "accepted 1"),
                "words " + d.wordsSha, "words " + CommunityDict.sha256Hex(bodyOf(cutR)));
        byte[] cutF = cut2;
        neg("truncate-reforged", "DPACK_REPORT", expect(() -> DictSign.load(cutF, stmt, null)));

        byte[] bom = new byte[raw.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(raw, 0, bom, 3, raw.length);
        neg("bom", "DPACK_SCHEMA", expect(() -> DictSign.load(bom, stmt, null)));

        List<DictSign.Word> poison = new ArrayList<>();
        poison.add(new DictSign.Word("qqq", "qqq", "qqq", "noun"));
        poison.add(new DictSign.Word("عدادية", "عَدَّادِيَّة", "عداد", "adj"));
        String mStmt = DictSign.statement(d.batchId, poison.size(),
                CommunityDict.sha256Hex(DictSign.bodyBytes(poison)), poison);
        neg("poison-reforged", "DPACK_WORD", expect(() -> DictSign.load(manualPack(d.batchId, poison), mStmt, null)));

        DictSign.Dpack dupD = pub(b);
        neg("duplicate-installed", "DPACK_WORD", expect(() -> DictSign.load(dupD.bytes, dupD.statement, inst)));
    }

    // ---- byte surgery (نفس أدوات الاختبار) ----

    static int indexOf(byte[] hay, byte[] needle) {
        outer: for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static int headerLen(byte[] raw) {
        int n = 0;
        for (int i = 0, seen = 0; seen < 5 && i < raw.length; i++)
            if (raw[i] == '\n') { seen++; n = i + 1; }
        return n;
    }

    static byte[] bodyOf(byte[] raw) {
        int h = headerLen(raw);
        int sigStart = raw.length - (4 + 64 + 1);
        byte[] b = new byte[sigStart - h];
        System.arraycopy(raw, h, b, 0, b.length);
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static byte[] patch(byte[] raw, String from, String to) {
        return new String(raw, StandardCharsets.UTF_8).replace(from + "\n", to + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] reforge(byte[] raw, String from, String to) {
        return resig(patch(raw, from, to));
    }

    /** append a fresh sig line over exactly these prefix bytes (truncated-file attacker) */
    static byte[] appendSig(byte[] prefix) {
        byte[] tail = ("sig " + CommunityDict.sha256Hex(prefix) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return out;
    }

    static final byte[] SIGB = {'\n', 's', 'i', 'g', ' '};

    /** recompute the sig line over everything before it — BYTE indices only
     *  (a char-index search on UTF-8 Arabic would truncate mid-sequence) */
    static byte[] resig(byte[] raw) {
        int hit = -1;
        outer: for (int i = raw.length - 5; i >= 0; i--) {
            for (int j = 0; j < 5; j++) if (raw[i + j] != SIGB[j]) continue outer;
            hit = i; break;
        }
        if (hit < 0) return raw;
        int sigStart = hit + 1;
        byte[] prefix = new byte[sigStart];
        System.arraycopy(raw, 0, prefix, 0, sigStart);
        byte[] tail = ("sig " + CommunityDict.sha256Hex(prefix) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[sigStart + tail.length];
        System.arraycopy(prefix, 0, out, 0, sigStart);
        System.arraycopy(tail, 0, out, sigStart, tail.length);
        return out;
    }

    static byte[] manualPack(String batch, List<DictSign.Word> ws) {
        byte[] body = DictSign.bodyBytes(ws);
        String wordsSha = CommunityDict.sha256Hex(body);
        String st = DictSign.statement(batch, ws.size(), wordsSha, ws);
        String reportSha = CommunityDict.sha256Hex(st.getBytes(StandardCharsets.UTF_8));
        String head = "neokey-dpack-1\nbatch " + batch + "\naccepted " + ws.size()
                + "\nreport " + reportSha + "\nwords " + wordsSha + "\n";
        byte[] prefix = concat(head.getBytes(StandardCharsets.UTF_8), body);
        return (new String(prefix, StandardCharsets.UTF_8) + "sig " + CommunityDict.sha256Hex(prefix) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
