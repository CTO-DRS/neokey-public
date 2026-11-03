package com.hark.neokey;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Frequency dictionary with prefix search, typo tolerance (1 edit) and on-device learning. */
final class Dictionary {
    private final String lang;
    private String[] words = new String[0];
    private String[] keys = new String[0];
    private int[] base = new int[0];
    private int[] rank = new int[0];   // raw corpus position (smaller = more frequent)
    private final Map<String, Integer> user = new HashMap<>();
    private final File userFile, bigramFile;
    private final Map<String, Map<String, Integer>> bigrams = new HashMap<>();
    private boolean bigramDirty;
    /** Corpus next-word predictions shipped in assets (bigram_<lang>_base.txt),
     *  keyed by the RAW corpus word (week-2 fix — norm keys collapsed hamza families). */
    private final Map<String, String[]> baseNext = new HashMap<>();

    private static final String[][] SEED = {
            {"إن", "شاء"}, {"شاء", "الله"}, {"ما", "شاء"}, {"السلام", "عليكم"}, {"عليكم", "السلام"},
            {"جزاك", "الله"}, {"الله", "يعطيك"}, {"يعطيك", "العافية"}, {"صباح", "الخير"}, {"مساء", "الخير"},
            {"الحمد", "لله"}, {"بارك", "الله"}, {"الله", "فيك"}, {"كيف", "حالك"}, {"تصبح", "على"},
            {"على", "خير"}, {"الله", "يسعدك"}, {"كل", "عام"}, {"عام", "وأنتم"}, {"وأنتم", "بخير"},
            {"شكرا", "لك"}, {"لا", "إله"}, {"إله", "إلا"}, {"إلا", "الله"}, {"أنا", "في"}, {"في", "الطريق"},
            {"رمضان", "كريم"}, {"عيد", "مبارك"}, {"جمعة", "مباركة"}, {"يا", "هلا"}, {"هلا", "والله"},
            {"how", "are"}, {"are", "you"}, {"thank", "you"}, {"good", "morning"}, {"good", "night"},
            {"see", "you"}, {"you", "soon"}, {"i", "am"}, {"i", "love"}, {"love", "you"}, {"what", "is"},
            {"talk", "to"}, {"to", "you"}, {"on", "my"}, {"my", "way"}, {"let", "me"}, {"me", "know"},
            {"happy", "birthday"}, {"no", "problem"}, {"of", "course"}, {"as", "soon"}, {"soon", "as"},
    };
    private volatile boolean loaded;
    private boolean dirty;
    /** حجم الأساس المثبت (corpus) — راونك الحزمة المجتمعية يبدأ عنده (الأسبوع 19). */
    private int baseRankN;
    /** الدرجة الأرضية الموثقة لكلمات حزمة القاموس المجتمعي — تحت عتبة
     *  المطابقة التامة (22000) فلا تحزمة تتصدر المثبت أبداً. */
    static final int PACK_SCORE = 3000;

    Dictionary(final Context c, final String lang) { this(c, lang, false); }

    boolean isLoaded() { return loaded; }

    /** baseOnly = the bundled dictionary without anything learned on this phone (for benchmarks). */
    Dictionary(final Context c, final String lang, boolean baseOnly) {
        this.lang = lang;
        File dir = baseOnly ? new File(c.getCacheDir(), "bench_none") : c.getFilesDir();
        userFile = new File(dir, "user_" + lang + ".txt");
        bigramFile = new File(dir, "bigram_" + lang + ".txt");
        new Thread(new Runnable() {
            @Override public void run() { load(c); }
        }).start();
    }

    private void load(Context c) {
        try {
            List<String> list = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(lang + ".txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) list.add(line);
            r.close();
            final int n = list.size();
            Integer[] idx = new Integer[n];
            final String[] k = new String[n];
            for (int i = 0; i < n; i++) { idx[i] = i; k[i] = norm(list.get(i)); }
            Arrays.sort(idx, new Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) { return k[a].compareTo(k[b]); }
            });
            String[] w = new String[n], kk = new String[n];
            int[] s = new int[n];
            int[] rk = new int[n];
            for (int i = 0; i < n; i++) {
                w[i] = list.get(idx[i]);
                kk[i] = k[idx[i]];
                s[i] = (int) ((long) (n - idx[i]) * 30000 / n);   // keep a 0..30000 scale for any size
                rk[i] = idx[i];                                    // raw corpus position (Correction2)
            }
            if (userFile.exists()) {
                BufferedReader u = new BufferedReader(new InputStreamReader(new FileInputStream(userFile), "UTF-8"));
                while ((line = u.readLine()) != null) {
                    int t = line.lastIndexOf('\t');
                    if (t > 0) {
                        try { user.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                u.close();
            }
            for (String[] p : SEED) addBigram(p[0], p[1], 1);
            // Corpus next-word rows are keyed by the RAW corpus word (phase 1 item B,
            // week 2). Keying by norm() collapsed the hamza families (إن/أن/ان and
            // الآن/الأن/الان all share one norm key) so the last row in the file won
            // and next-word context could NEVER separate them — measured in Probe2:
            // bigramScore(الآن,في)=0 because the bare «الان» row overwrote «الآن»'s.
            // Duplicate raw rows are MERGED (append unseen, corpus order preserved).
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        c.getAssets().open("bigram_" + lang + "_base.txt"), "UTF-8"));
                while ((line = br.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t > 0) mergeBaseNext(line.substring(0, t), line.substring(t + 1).split(" "));
                }
                br.close();
            } catch (Exception ignored) { }
            if (bigramFile.exists()) {
                BufferedReader u = new BufferedReader(new InputStreamReader(new FileInputStream(bigramFile), "UTF-8"));
                while ((line = u.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length == 3) {
                        try { addBigram(parts[0], parts[1], Integer.parseInt(parts[2])); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                u.close();
            }
            synchronized (this) { words = w; keys = kk; base = s; rank = rk; }
            baseRankN = n;
            consumeCommunityPack(c);
            loaded = true;
        } catch (Exception ignored) {
            loaded = true;
        }
    }

    /** المرحلة 3/الأسبوع 2 — استهلاك حزمة القاموس المجتمعي الموقّعة من أصول
     *  الإصدار (community_<lang>.dpack) إن وُجدت: توقيع يُتحقق ثم دمج بترتيب
     *  موثق. غياب الأصل حالة طبيعية (الحزم اختيارية)؛ أي خرق شكل/توقيع يُرفض
     *  كله ولا يُستهلك شيء من حزمة مسمومة. */
    private void consumeCommunityPack(Context c) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getAssets().open("community_" + lang + ".dpack");
            byte[] buf = new byte[8192];
            int rd;
            while ((rd = in.read(buf)) > 0) bo.write(buf, 0, rd);
            in.close();
            DictSign.Pack p = DictSign.load(bo.toByteArray(), null, null);
            List<String> ws = new ArrayList<>(p.words.size());
            for (DictSign.Word w : p.words) ws.add(w.word);
            mergePack(ws);
        } catch (Exception ignored) { }
    }

    /** الدمج الموقّع — ترتيب الاستهلاك الموثق (بوابة الأسبوع 19):
     *  1) كلمات الحزمة تُلحق بعد القاموس المثبت: راونكها يبدأ عند baseRankN
     *     بترتيب الحزمة نفسه (حتمي مهما كان حالة الجهاز)؛
     *  2) درجتها الأرضية PACK_SCORE=3000 — لا دفعة مطابقة تامة ولا تصدير؛
     *  3) المكرر الطبيعي مع المثبت يُسقَط — القاموس المثبت يفوز دائماً؛
     *  4) إعادة الفرز (المفتاح ثم الراونك) تحافظ على ترتيب الأساس الأصلي
     *     حرفياً (راونك الأساس = موضع corpus الأصلي).
     *  العائد: عدد الكلمات المضافة فعلاً. */
    int mergePack(List<String> packWords) {
        if (packWords == null || packWords.isEmpty()) return 0;
        synchronized (this) {
            String[] w = this.words, k = this.keys;
            int[] s = this.base, r = this.rank;
            java.util.HashSet<String> have = new java.util.HashSet<>(k.length * 2);
            for (String key : k) have.add(key);
            List<String> nw = new ArrayList<>();
            List<Integer> nr = new ArrayList<>();
            for (int i = 0; i < packWords.size(); i++) {
                String pw = packWords.get(i);
                String nk = norm(pw);
                if (nk.isEmpty() || have.contains(nk)) continue;
                have.add(nk);
                nw.add(pw);
                nr.add(baseRankN + i);
            }
            if (nw.isEmpty()) return 0;
            int m = nw.size(), old = w.length, tot = old + m;
            String[] W = new String[tot]; String[] K = new String[tot];
            int[] S = new int[tot]; int[] R = new int[tot];
            System.arraycopy(w, 0, W, 0, old); System.arraycopy(k, 0, K, 0, old);
            System.arraycopy(s, 0, S, 0, old); System.arraycopy(r, 0, R, 0, old);
            for (int i = 0; i < m; i++) {
                W[old + i] = nw.get(i); K[old + i] = norm(nw.get(i));
                S[old + i] = PACK_SCORE; R[old + i] = nr.get(i);
            }
            Integer[] idx = new Integer[tot];
            for (int i = 0; i < tot; i++) idx[i] = i;
            final String[] fk = K; final int[] fr = R;
            Arrays.sort(idx, new Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    int c = fk[a].compareTo(fk[b]);
                    return c != 0 ? c : fr[a] - fr[b];
                }
            });
            String[] w2 = new String[tot], k2 = new String[tot];
            int[] s2 = new int[tot], r2 = new int[tot];
            for (int i = 0; i < tot; i++) {
                w2[i] = W[idx[i]]; k2[i] = K[idx[i]]; s2[i] = S[idx[i]]; r2[i] = R[idx[i]];
            }
            this.words = w2; this.keys = k2; this.base = s2; this.rank = r2;
            return m;
        }
    }

    /** Arabic-insensitive key: hamza forms, taa marbuta, alef maqsura folded; lowercase for English. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': ch = 'ا'; break;
                case 'ة': ch = 'ه'; break;
                case 'ى': ch = 'ي'; break;
                case 'ؤ': ch = 'و'; break;
                case 'ئ': ch = 'ي'; break;
                default:
                    if (ch >= 0x064B && ch <= 0x0652) continue; // tashkeel
                    ch = Character.toLowerCase(ch);
            }
            b.append(ch);
        }
        return b.toString();
    }

    /** Up to n suggestions for the typed word, best first. */
    List<String> suggest(String typed, int n) {
        List<String> out = new ArrayList<>();
        if (!loaded || typed.isEmpty()) return out;
        String key = norm(typed);
        final Map<String, Integer> score = new HashMap<>();
        String[] w; String[] k; int[] s;
        synchronized (this) { w = words; k = keys; s = base; }

        // 1) prefix matches
        int lo = lower(k, key);
        java.util.PriorityQueue<Integer> top = new java.util.PriorityQueue<>();
        for (int i = lo; i < k.length && k[i].startsWith(key); i++) {
            int sc = s[i] + boost(w[i]);
            if (k[i].equals(key) && s[i] > 22000) sc += 40000;   // exact, only for common words
            sc -= (k[i].length() - key.length()) * 120;
            if (top.size() >= 12 && sc <= top.peek()) continue;   // keep only the best few
            top.add(sc);
            if (top.size() > 12) top.poll();
            put(score, w[i], sc);
        }
        // 2) learned words
        for (Map.Entry<String, Integer> e : user.entrySet()) {
            if (e.getValue() >= 2 && norm(e.getKey()).startsWith(key)) put(score, e.getKey(), 20000 + e.getValue() * 3000);
        }
        // 3) typo tolerance: words of similar length within one edit
        if (key.length() >= 3) {
            String first = key.substring(0, 1);
            int a = lower(k, first);
            for (int i = a; i < k.length && k[i].startsWith(first); i++) {
                if (Math.abs(k[i].length() - key.length()) <= 1 && k[i].length() >= key.length()
                        && oneEdit(k[i], key)) {
                    put(score, w[i], s[i] / 2 + boost(w[i]) - 5000);
                }
            }
        }
        List<Map.Entry<String, Integer>> all = new ArrayList<>(score.entrySet());
        java.util.Collections.sort(all, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        for (Map.Entry<String, Integer> e : all) {
            if (e.getKey().equals(typed)) continue;
            out.add(e.getKey());
            if (out.size() >= n) break;
        }
        return out;
    }

    /** Merge a corpus row into baseNext under its RAW key — unseen nexts appended
     *  in corpus order, list capped (bigramScore never reads past 20 anyway). */
    private void mergeBaseNext(String prev, String[] nexts) {
        if (prev.isEmpty() || nexts.length == 0) return;
        synchronized (baseNext) {
            String[] old = baseNext.get(prev);
            if (old == null) {
                int n = Math.min(nexts.length, 20);
                String[] copy = new String[n];
                System.arraycopy(nexts, 0, copy, 0, n);
                baseNext.put(prev, copy);
                return;
            }
            java.util.LinkedHashSet<String> m = new java.util.LinkedHashSet<>(java.util.Arrays.asList(old));
            for (String x : nexts) if (m.size() >= 20) break; else m.add(x);
            baseNext.put(prev, m.toArray(new String[0]));
        }
    }

    /** Corpus next-list for a prev word: EXACT raw key first, then the normalized
     *  key (typed spellings like «الان» reach the corpus rows of their family). */
    private String[] nextList(String prev) {
        if (prev == null || prev.isEmpty()) return null;
        String[] r;
        synchronized (baseNext) { r = baseNext.get(prev); }
        if (r != null) return r;
        synchronized (baseNext) { return baseNext.get(norm(prev)); }
    }

    boolean knows(String word) {
        if (user.containsKey(word)) return true;
        String key = norm(word);
        String[] k; String[] w;
        synchronized (this) { k = keys; w = words; }
        for (int i = lower(k, key); i < k.length && k[i].equals(key); i++) if (w[i].equalsIgnoreCase(word)) return true;
        return false;
    }

    // ---- read accessors for Correction2 (phase 1, item B) — read-only, no behavior change ----

    /** Frequency score of an exactly-known word (base rank + user boost), 0 when unknown. */
    int wordScore(String word) {
        if (!loaded || word == null || word.isEmpty()) return 0;
        String key = norm(word);
        String[] k; String[] w; int[] s;
        synchronized (this) { k = keys; w = words; s = base; }
        for (int i = lower(k, key); i < k.length && k[i].equals(key); i++)
            if (w[i].equalsIgnoreCase(word)) return s[i] + boost(w[i]);
        return 0;
    }

    /** Raw corpus rank of an exactly-known word (smaller = more frequent), -1 when unknown.
     *  The int-truncated wordScore cannot separate same-norm spellings — the raw rank can. */
    int wordRank(String word) {
        if (!loaded || word == null || word.isEmpty()) return -1;
        Integer uv = user.get(word);
        if (uv != null) return Integer.MAX_VALUE;   // user words sit at the end
        String key = norm(word);
        String[] k; String[] w; int[] r;
        synchronized (this) { k = keys; w = words; r = rank; }
        for (int i = lower(k, key); i < k.length && k[i].equals(key); i++)
            if (w[i].equalsIgnoreCase(word)) return r[i];
        return -1;
    }

    /** Context evidence for (prev -> word): learned-pair count + corpus rank, 0 when unseen. */
    int bigramScore(String prev, String word) {
        if (!loaded || prev == null || prev.isEmpty() || word == null || word.isEmpty()) return 0;
        int sc = 0;
        synchronized (bigrams) {
            Map<String, Integer> m = bigrams.get(norm(prev));
            Integer v = m == null ? null : m.get(word);
            if (v != null) sc += v * 1000;
        }
        String[] corpus = nextList(prev);
        if (corpus != null) {
            int cap = Math.min(corpus.length, 20);
            for (int i = 0; i < cap; i++)
                if (corpus[i].equals(word)) { sc += 2000 - i * 100; break; }
        }
        return sc;
    }

    /** All dictionary spellings sharing this word's normalized key (the typed spelling excluded). */
    java.util.List<String> normVariants(String word) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!loaded || word == null || word.isEmpty()) return out;
        String key = norm(word);
        String[] k; String[] w;
        synchronized (this) { k = keys; w = words; }
        for (int i = lower(k, key); i < k.length && k[i].equals(key); i++)
            if (!w[i].equalsIgnoreCase(word)) out.add(w[i]);
        return out;
    }

    void clearUser() {
        user.clear();
        bigrams.clear();
        userFile.delete();
        bigramFile.delete();
        dirty = false;
        bigramDirty = false;
    }

    void learn(String word) {
        if (word.length() < 2 || word.length() > 30) return;
        Integer v = user.get(word);
        user.put(word, v == null ? 1 : Math.min(v + 1, 999));
        dirty = true;
    }

    void forgetAll() {
        user.clear();
        synchronized (bigrams) { bigrams.clear(); }
        dirty = true;
        bigramDirty = true;
        save();
    }

    private void addBigram(String a, String b, int n) {
        String ka = norm(a);
        synchronized (bigrams) {
            Map<String, Integer> m = bigrams.get(ka);
            if (m == null) { m = new HashMap<>(); bigrams.put(ka, m); }
            Integer v = m.get(b);
            m.put(b, Math.min(999, (v == null ? 0 : v) + n));
        }
    }

    void learnPair(String prev, String next) {
        if (prev.isEmpty() || next.length() < 1 || prev.length() > 30 || next.length() > 30) return;
        addBigram(prev, next, 2);
        bigramDirty = true;
    }

    /** Words likely to follow prev. */
    List<String> predict(String prev, int n) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> m;
        synchronized (bigrams) {
            Map<String, Integer> src = bigrams.get(norm(prev));
            m = src == null ? null : new HashMap<>(src);
        }
        if (m == null) m = new HashMap<>();
        String[] corpus = nextList(prev);
        if (corpus != null) {
            // corpus order gives a gentle base score; learned pairs (weight 2+) rise above quickly
            for (int i = 0; i < corpus.length; i++) {
                Integer v = m.get(corpus[i]);
                m.put(corpus[i], (v == null ? 0 : v) + (corpus.length - i));
            }
        }
        if (m.isEmpty()) return out;
        List<Map.Entry<String, Integer>> all = new ArrayList<>(m.entrySet());
        java.util.Collections.sort(all, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        for (Map.Entry<String, Integer> e : all) { out.add(e.getKey()); if (out.size() >= n) break; }
        return out;
    }

    /** Gesture typing: words whose letters appear in order along the swiped key path. */
    List<String> glide(String path, int n) {
        List<String> out = new ArrayList<>();
        if (!loaded || path.length() < 2) return out;
        String p = norm(path);
        char first = p.charAt(0), last = p.charAt(p.length() - 1);
        String[] w; String[] k; int[] s;
        synchronized (this) { w = words; k = keys; s = base; }
        final Map<String, Integer> score = new HashMap<>();
        String f = String.valueOf(first);
        int total = 30000;
        for (int i = lower(k, f); i < k.length && k[i].charAt(0) == first; i++) {
            String key = k[i];
            if (key.length() < 2 || key.charAt(key.length() - 1) != last) continue;
            if (!alongPath(key, p)) continue;
            int sc = s[i] + boost(w[i]) + key.length() * total / 8;
            Integer old = score.get(w[i]);
            if (old == null || sc > old) score.put(w[i], sc);
        }
        synchronized (user) {
            for (Map.Entry<String, Integer> e : user.entrySet()) {
                String key = norm(e.getKey());
                if (key.length() < 2 || key.charAt(0) != first || key.charAt(key.length() - 1) != last) continue;
                if (!alongPath(key, p)) continue;
                int sc = total / 2 + e.getValue() * total / 40 + key.length() * total / 8;
                Integer old = score.get(e.getKey());
                if (old == null || sc > old) score.put(e.getKey(), sc);
            }
        }
        List<String> all = new ArrayList<>(score.keySet());
        java.util.Collections.sort(all, new Comparator<String>() {
            @Override public int compare(String a, String b) { return score.get(b) - score.get(a); }
        });
        for (String x : all) { out.add(x); if (out.size() >= n) break; }
        return out;
    }

    private static boolean alongPath(String word, String path) {
        int j = 0;
        char prev = 0;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (ch == prev) continue; // double letters
            while (j < path.length() && path.charAt(j) != ch) j++;
            if (j >= path.length()) return false;
            prev = ch;
        }
        return true;
    }

    /** A confident correction for an unknown word, or null. */
    String correction(String typed) {
        if (!loaded || typed.length() < 3 || knows(typed)) return null;
        String key = norm(typed);
        String[] w; String[] k; int[] s;
        synchronized (this) { w = words; k = keys; s = base; }
        // hamza / taa marbuta fix: same normalized key, different spelling
        int lo = lower(k, key);
        if (lo < k.length && k[lo].equals(key)) return w[lo];
        String best = null;
        int bestScore = 0;
        String first = key.substring(0, 1);
        for (int i = lower(k, first); i < k.length && k[i].startsWith(first); i++) {
            if (Math.abs(k[i].length() - key.length()) <= 1 && oneEdit(k[i], key)) {
                int sc = s[i] + boost(w[i]);
                if (sc > bestScore) { bestScore = sc; best = w[i]; }
            }
        }
        return bestScore > 18000 ? best : null;
    }

    int learnedCount() { return user.size(); }

    void save() {
        if (bigramDirty) {
            bigramDirty = false;
            final StringBuilder sb = new StringBuilder();
            synchronized (bigrams) {
                int lines = 0;
                for (Map.Entry<String, Map<String, Integer>> e : bigrams.entrySet())
                    for (Map.Entry<String, Integer> f : e.getValue().entrySet()) {
                        if (lines++ > 20000) break;
                        sb.append(e.getKey()).append('\t').append(f.getKey()).append('\t').append(f.getValue()).append('\n');
                    }
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Writer wr = new OutputStreamWriter(new FileOutputStream(bigramFile), "UTF-8");
                        wr.write(sb.toString());
                        wr.close();
                    } catch (Exception ignored) { }
                }
            }).start();
        }
        if (!dirty) return;
        dirty = false;
        final Map<String, Integer> copy = new HashMap<>(user);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Writer wr = new OutputStreamWriter(new FileOutputStream(userFile), "UTF-8");
                    for (Map.Entry<String, Integer> e : copy.entrySet())
                        wr.write(e.getKey() + "\t" + e.getValue() + "\n");
                    wr.close();
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private int boost(String w) {
        Integer v = user.get(w);
        return v == null ? 0 : v * 4000;
    }

    private static void put(Map<String, Integer> m, String w, int sc) {
        Integer old = m.get(w);
        if (old == null || sc > old) m.put(w, sc);
    }

    private static int lower(String[] k, String key) {
        int lo = 0, hi = k.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (k[mid].compareTo(key) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** true when a and b differ by exactly one substitution, insertion or deletion (or swap). */
    private static boolean oneEdit(String a, String b) {
        if (a.equals(b)) return false;
        int la = a.length(), lb = b.length();
        if (la == lb) {
            int diff = 0, first = -1;
            for (int i = 0; i < la; i++) if (a.charAt(i) != b.charAt(i)) { if (diff++ == 0) first = i; }
            if (diff == 1) return true;
            return diff == 2 && first + 1 < la && a.charAt(first) == b.charAt(first + 1)
                    && a.charAt(first + 1) == b.charAt(first);
        }
        if (Math.abs(la - lb) != 1) return false;
        String l = la > lb ? a : b, s = la > lb ? b : a;
        int i = 0, j = 0; boolean skipped = false;
        while (i < l.length() && j < s.length()) {
            if (l.charAt(i) == s.charAt(j)) { i++; j++; }
            else { if (skipped) return false; skipped = true; i++; }
        }
        return true;
    }
}
