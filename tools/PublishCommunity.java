import com.hark.neokey.CommunityDict;
import com.hark.neokey.SignedDict;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * The publishing step (Phase 3 week 2) — reads the reviewed batch CSV, runs
 * the STRICT reviewer, and writes the canonical pinned pack asset.
 *
 * Usage: java -cp <cp> PublishCommunity [batchCsv] [assetsDir] [outDir]
 *   batchCsv  default dictsrc/community_batch_w19.csv
 *   assetsDir default assets            → community_dict.json written here
 *   outDir    default build/community   → publish-report.json written here
 *
 * Exit 1 on refusal (NOTHING_ACCEPTED) or on any unexpected error.
 */
public final class PublishCommunity {
    public static void main(String[] a) throws Exception {
        String csv = a.length > 0 ? a[0] : "dictsrc/community_batch_w19.csv";
        String assets = a.length > 1 ? a[1] : "assets";
        String out = a.length > 2 ? a[2] : "build/community";

        byte[] batch = Files.readAllBytes(Paths.get(csv));
        List<String> installed =
                Files.readAllLines(Paths.get(assets + "/ar.txt"), StandardCharsets.UTF_8);
        Set<String> norms = CommunityDict.normSetOf(installed);

        SignedDict.PublishResult pub = SignedDict.publish(batch, norms);

        Files.createDirectories(Paths.get(out));
        Files.write(Paths.get(assets + "/community_dict.json"), pub.bytes);
        String report = "{\"schema\":\"" + SignedDict.SCHEMA + "\""
                + ",\"batchId\":\"" + pub.pack.batchId + "\""
                + ",\"source\":\"" + csv + "\""
                + ",\"sourceSha256\":\"" + CommunityDict.sha256Hex(batch) + "\""
                + ",\"rowsTotal\":" + pub.report.total
                + ",\"rowsAccepted\":" + pub.report.accepted
                + ",\"rowsRejected\":" + pub.report.rejected
                + ",\"packWords\":" + pub.pack.size()
                + ",\"packSha256\":\"" + pub.packSha256 + "\""
                + ",\"bodySha256\":\"" + pub.pack.bodySha256 + "\""
                + ",\"tags\":" + pub.report.toJson().substring(
                        pub.report.toJson().indexOf("\"tags\":") + 7,
                        pub.report.toJson().indexOf("\"contributors\":") - 1)
                + "}";
        Files.write(Paths.get(out + "/publish-report.json"), report.getBytes(StandardCharsets.UTF_8));

        System.out.println("PUBLISH_OK batch=" + pub.pack.batchId.substring(0, 12)
                + " total=" + pub.report.total
                + " accepted=" + pub.report.accepted
                + " rejected=" + pub.report.rejected
                + " packSha256=" + pub.packSha256.substring(0, 12) + "…");
    }
}
