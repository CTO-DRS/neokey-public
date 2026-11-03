import com.hark.neokey.CommunityDict;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class DiagBatch {
    public static void main(String[] a) throws Exception {
        byte[] csv = Files.readAllBytes(Paths.get("dictsrc/community_batch_w19.csv"));
        Set<String> norms = CommunityDict.normSetOf(
                Files.readAllLines(Paths.get("assets/ar.txt"), StandardCharsets.UTF_8));
        CommunityDict.Report r = CommunityDict.review(csv, norms);
        for (CommunityDict.Row row : r.rows)
            System.out.println((row.accepted() ? "OK  " : row.tag) + "\t" + row.word);
    }
}
