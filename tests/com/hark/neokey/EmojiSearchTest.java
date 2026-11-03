package com.hark.neokey;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Roadmap Phase 0 / Week 2: emoji search ranking + AND semantics, cross-checked against assets/emoji_kw.txt. */
public class EmojiSearchTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** independent oracle: emoji -> its keyword tokens (normalised exactly like the app does) */
    private static final Map<String, String[]> ORACLE = new HashMap<>();
    private static com.hark.neokey.FakeContext ctx;

    private static void loadOracle() {
        if (!ORACLE.isEmpty()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File("assets", "emoji_kw.txt")), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String[] ws = line.substring(t + 1).split(" ");
                for (int i = 0; i < ws.length; i++) ws[i] = Dictionary.norm(ws[i]);
                ORACLE.put(line.substring(0, t), ws);
            }
            r.close();
        } catch (Exception ignored) { }
    }

    /** mirrors the production scoring: exact/prefix/contains(>=3) */
    private static boolean matches(String emoji, String part) {
        for (String x : ORACLE.get(emoji)) {
            if (x.equals(part) || x.startsWith(part) || (part.length() >= 3 && x.contains(part))) return true;
        }
        return false;
    }

    public static void run() {
        loadOracle();
        ctx = new com.hark.neokey.FakeContext(new File("build/test-user-emoji"));
        System.out.println("== EmojiSearch:ranking ==");
        List<String> q1 = EmojiSearch.search(ctx, "قلب", 20);
        check("query قلب non-empty", !q1.isEmpty());
        check("query قلب respects cap", q1.size() <= 20);
        check("query قلب unique results", q1.stream().distinct().count() == q1.size());
        check("heart is the top hit for قلب (exact keyword)", !q1.isEmpty() && "❤️".equals(q1.get(0)));
        int matchCount = 0;
        for (Map.Entry<String, String[]> e : ORACLE.entrySet()) if (matches(e.getKey(), "قلب")) matchCount++;
        List<String> qFull = EmojiSearch.search(ctx, "قلب", matchCount + 5);
        check("cap >= match-count returns them all", qFull.size() >= matchCount);
        boolean complete = true;
        int seen = 0;
        for (String e : qFull) {
            if (!matches(e, "قلب")) complete = false;
            if (seen++ < matchCount && ORACLE.containsKey(e) && matches(e, "قلب") && !qFull.contains(e)) complete = false;
        }
        for (Map.Entry<String, String[]> e : ORACLE.entrySet())
            if (matches(e.getKey(), "قلب") && !qFull.contains(e.getKey())) complete = false;
        check("search returns exactly the oracle match set", complete);
        boolean allMatch = true;
        for (String e : q1) if (ORACLE.containsKey(e) && !matches(e, "قلب")) allMatch = false;
        check("every result has a قلب keyword", allMatch);
        List<String> q2 = EmojiSearch.search(ctx, "قلب حب", 20);
        check("multi-word AND non-empty", !q2.isEmpty());
        boolean andOk = true;
        for (String e : q2) if (!matches(e, "قلب") || !matches(e, "حب")) andOk = false;
        check("multi-word AND semantics", andOk);
        check("AND with impossible word is empty", EmojiSearch.search(ctx, "قلب زقزقنة_غيرموجودة", 10).isEmpty());
        List<String> love = EmojiSearch.search(ctx, "love", 20);
        check("english query love non-empty", !love.isEmpty());
        boolean loveOk = true;
        for (String e : love) if (ORACLE.containsKey(e) && !matches(e, "love")) loveOk = false;
        check("english results match keyword", loveOk);
        List<String> tash = EmojiSearch.search(ctx, "قَلْب", 20);
        check("tashkeel query normalised", tash.equals(q1));

        System.out.println("== EmojiSearch:edges ==");
        check("empty query empty result", EmojiSearch.search(ctx, "", 5).isEmpty());
        check("blank query empty result", EmojiSearch.search(ctx, "   ", 5).isEmpty());
        check("unknown query empty result", EmojiSearch.search(ctx, "زقزقنة_غيرموجودة", 10).isEmpty());
        check("cap n=3", EmojiSearch.search(ctx, "حب", 3).size() <= 3);
        List<String> stable1 = EmojiSearch.search(ctx, "حب", 5);
        List<String> stable2 = EmojiSearch.search(ctx, "حب", 5);
        check("deterministic ordering", stable1.equals(stable2));
        System.out.println("EmojiSearchTest subtotal: " + pass + " passed, " + fail + " failed");
    }
}
