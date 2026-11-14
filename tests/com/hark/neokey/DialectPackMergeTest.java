package com.hark.neokey;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * بطارية W73 الحية — DialectPackMergeTest: الحزمة النظيفة تُدمج
 * بايت-دقيقة في القاموس الحي — العدد الصادق، والرتبة نفسها في
 * قاموسين مستقلين، وبعد الجسم المثبت — عبر WordSink بمرجع دالة.
 * (النقاء النصي في DialectPackTest — هذه تعمل داخل بطارية run.sh
 * بكامل المسار كما في بطارية W19.)
 */
public class DialectPackMergeTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static String row(String word, String diac, String lemma, String pos,
                      String gloss, String contrib, String lic) {
        return word + "," + diac + "," + lemma + "," + pos + "," + gloss + "," + contrib + "," + lic;
    }

    static byte[] csv(String... dataRows) {
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\n");
        for (String r : dataRows) b.append(r).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Set<String> inst(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(CommunityDict.normAr(w));
        return s;
    }

    static Dictionary dictIn(File assetsDir, File filesDir) throws Exception {
        Dictionary d = new Dictionary(new FakeContext(assetsDir, filesDir,
                new File(filesDir.getParentFile(), filesDir.getName() + "-cache")), "ar");
        long t0 = System.currentTimeMillis();
        while (!d.isLoaded() && System.currentTimeMillis() - t0 < 15000) Thread.sleep(25);
        return d;
    }

    static File engineAssets(byte[] arTxt) throws Exception {
        File dir = Files.createTempDirectory("w73m-assets").toFile();
        Files.write(new File(dir, "ar.txt").toPath(), arTxt);
        return dir;
    }

    public static void main(String[] args) throws Exception {
        Set<String> installed = inst("سلام", "كتاب", "قلم");
        byte[] b = csv(
                row("كتابية", "كِتَابِيَّة", "كتاب", "adj", "متعلقة بالكتاب والتحرير", "سالم-قلم", "CC0-1.0"),
                row("عدادية", "عَدَّادِيَّة", "عداد", "adj", "متعلقة بالعد والقياس", "نورة-حرف", "CC0-1.0"));
        DictSign.Dpack d = DictSign.publish(b, CommunityDict.review(b, installed));
        byte[] sealed = DialectPack.seal("مصري", d);
        DialectPack.Open o = DialectPack.open(sealed, null, null);

        byte[] ar = "سلام\nكتاب\nقلم\n".getBytes(StandardCharsets.UTF_8);
        Dictionary d1 = dictIn(engineAssets(ar), Files.createTempDirectory("w73m-u1").toFile());
        int added = DialectPack.merge(o, d1::mergePack);
        check("live: العدد الصادق عبر مرجع دالة القاموس (كلمتان)", added == 2);
        check("live: الكلمتان معروفتان بعد الدمج", d1.knows("كتابية") && d1.knows("عدادية"));
        check("live: الدمج بايت-دقيق — بعد الجسم المثبت (رتب 3,4)",
                d1.wordRank("كتابية") == 3 && d1.wordRank("عدادية") == 4);
        Dictionary d2 = dictIn(engineAssets(ar), Files.createTempDirectory("w73m-u2").toFile());
        DialectPack.Open o2 = DialectPack.open(DialectPack.seal("مصري", d), null, null);
        DialectPack.merge(o2, d2::mergePack);
        check("live: قاموسان مستقلان رتبهما متطابقة (حتمية الدمج)",
                d2.wordRank("كتابية") == d1.wordRank("كتابية")
                        && d2.wordRank("عدادية") == d1.wordRank("عدادية"));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
