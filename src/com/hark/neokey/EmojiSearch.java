package com.hark.neokey;

import android.content.Context;
import android.graphics.Paint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Emoji search by Arabic or English keyword (CLDR annotations, assets/emoji_kw.txt). */
final class EmojiSearch {
    private static String[] emojis;
    private static String[][] words;

    static synchronized void load(Context c) {
        if (emojis != null) return;
        List<String> e = new ArrayList<>();
        List<String[]> w = new ArrayList<>();
        Paint p = new Paint();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji_kw.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String em = line.substring(0, t);
                if (android.os.Build.VERSION.SDK_INT >= 23 && !p.hasGlyph(em)) continue;      // skip emoji this phone cannot draw
                e.add(em);
                w.add(line.substring(t + 1).split(" "));
            }
            r.close();
        } catch (Exception ignored) { }
        emojis = e.toArray(new String[0]);
        words = w.toArray(new String[0][]);
    }

    /** Best matches for the query, popular panel emoji first on ties. */
    static List<String> search(Context c, String query, int n) {
        load(c);
        List<String> out = new ArrayList<>();
        String q = Dictionary.norm(query.trim());
        if (q.isEmpty()) return out;
        String[] parts = q.split("\\s+");
        int best = 0;
        List<int[]> hits = new ArrayList<>();
        for (int i = 0; i < emojis.length; i++) {
            int total = 0;
            for (String part : parts) {
                int sc = 0;
                String[] ws = words[i];
                for (int j = 0; j < ws.length; j++) {
                    String x = ws[j];
                    int s;
                    if (x.equals(part)) s = 6;
                    else if (x.startsWith(part)) s = 4;
                    else if (part.length() >= 3 && x.contains(part)) s = 2;
                    else continue;
                    if (j < 3) s += 2;   // the emoji's own name
                    if (s > sc) sc = s;
                }
                if (sc == 0) { total = 0; break; }
                total += sc;
            }
            if (total > 0) hits.add(new int[]{total, i});
        }
        java.util.Collections.sort(hits, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return a[0] != b[0] ? b[0] - a[0] : a[1] - b[1]; }
        });
        for (int[] h : hits) { out.add(emojis[h[1]]); if (out.size() >= n) break; }
        return out;
    }
}
