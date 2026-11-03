package com.hark.neokey;

import android.content.Context;
import android.view.View;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/** UI language: Arabic source strings, translated to English at runtime from assets/ui_en.txt when pref ui_en is on. */
final class L {
    static volatile boolean en;
    private static Map<String, String> map;
    private static String[] longKeys;
    private static final Map<String, String> cache = new HashMap<>();

    private L() { }

    static synchronized void init(Context c) {
        en = Theme.prefs(c).getBoolean("ui_en", false);
        if (en && map == null) {
            map = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("ui_en.txt"), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) map.put(line.substring(0, t), line.substring(t + 1));
                }
            } catch (Exception ignored) { }
            List<String> l = new ArrayList<>();
            for (String k : map.keySet()) if (k.trim().length() >= 6) l.add(k);
            Collections.sort(l, (a, b) -> b.length() - a.length());
            longKeys = l.toArray(new String[0]);
        }
    }

    /** Exact match only (keyboard key labels: never touch letters). */
    static String k(String s) {
        if (!en || s == null || map == null) return s;
        String v = map.get(s);
        return v != null ? v : s;
    }

    /** Exact match, else replace known phrases inside a composed string. */
    static String t(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString();
        if (!en || map == null || s.isEmpty()) return s;
        String v = map.get(s);
        if (v != null) return v;
        synchronized (cache) {
            v = cache.get(s);
            if (v != null) return v;
            String out = s;
            boolean ar = false;
            for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch >= 0x0600 && ch <= 0x06FF) { ar = true; break; } }
            if (ar) for (String key : longKeys) if (out.contains(key)) out = out.replace(key, map.get(key));
            if (cache.size() < 2000) cache.put(s, out);
            return out;
        }
    }

    static int dir() { return en ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL; }
}
