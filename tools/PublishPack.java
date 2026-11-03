import com.hark.neokey.CommunityDict;
import com.hark.neokey.DictSign;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * خطوة نشر حزمة القاموس المجتمعي — المرحلة 3، الأسبوع 2.
 *
 * دفعة مجتازة للمراجعة → حزمة قاموس موقّعة تُحمَّل كأصل إصدار:
 *   java -cp build/publish tools.PublishPack <batch.csv> <installed-dict.txt> \
 *        assets/community_ar.dpack build/release/community-pack-statement.json
 *
 * لا قرار إنسان هنا يتجاوز المراجعة: publish يرفض نفسه إذا كان التقرير ليس
 * لهذه الدفعة، أو القبول صفر، ويُصدر بيان المراجعة (الأثر البشري المعتمد)
 * بجوار الأصل — البايتات حتمية، إعادة النشر تعيد الملف نفسه حرفاً بحرف.
 */
public final class PublishPack {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: PublishPack <batch.csv> <installed-dict.txt> <out.dpack> <out.statement.json>");
            System.exit(2);
        }
        byte[] batch = readAll(new File(args[0]));
        List<String> dict = lines(new File(args[1]));
        java.util.Set<String> installed = CommunityDict.normSetOf(dict);

        CommunityDict.Report rep = CommunityDict.review(batch, installed);
        if (rep.accepted != rep.total || rep.accepted == 0) {
            System.err.println("PUBLISH_REFUSED — batch is not fully accepted: "
                    + rep.accepted + "/" + rep.total + " (tags: " + rep.tags + ")");
            System.exit(1);
        }
        DictSign.Dpack d = DictSign.publish(batch, rep);
        Files.write(new File(args[2]).toPath(), d.bytes);
        Files.write(new File(args[3]).toPath(), d.statement.getBytes(StandardCharsets.UTF_8));

        System.out.println("PUBLISH_OK batch=" + d.batchId);
        System.out.println("  words=" + d.accepted
                + " bytes=" + d.bytes.length
                + " sig=" + d.sig);
        System.out.println("  reportSha=" + d.reportSha);
        System.out.println("  wordsSha=" + d.wordsSha);
        System.out.println("  out=" + args[2]);
        System.out.println("  statement=" + args[3]);
    }

    static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    static List<String> lines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : new String(readAll(f), StandardCharsets.UTF_8).split("\n"))
            if (!l.isEmpty()) out.add(l);
        return out;
    }
}
