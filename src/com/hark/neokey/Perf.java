package com.hark.neokey;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Local-only performance metrics. Stores numbers (timings, memory, counters) and never any typed text.
 * Nothing here is sent anywhere; the user can export a report by hand from the performance center.
 */
final class Perf {
    static final String PREFS = "neokey_perf";
    /** Metric keys and their Arabic names, in display order. */
    static final String[][] METRICS = {
            {"create", "إنشاء الكيبورد (أول مرة)", "ms"},
            {"show", "زمن ظهور الكيبورد", "ms"},
            {"key", "الاستجابة عند الضغط", "ms"},
            {"frame", "من اللمس حتى رسم الإطار", "ms"},
            {"sugg", "سرعة ظهور الاقتراحات", "ms"},
            {"ai", "استجابة محرك الذكاء الاصطناعي", "ms"},
            {"mem", "استهلاك الذاكرة (PSS)", "MB"},
    };
    /** Thresholds (good, ok) per metric for the text status label. */
    static final float[][] LIMITS = {{400, 900}, {120, 300}, {8, 20}, {24, 50}, {12, 35}, {2500, 7000}, {90, 160}};

    private static final int KEEP = 120;
    private static final HashMap<String, Stat> stats = new HashMap<>();
    private static SharedPreferences prefs;
    private static Context appCtx;
    private static boolean enabled = true, dirty, installed;

    static final class Stat {
        long count;
        double sum, max;
        final ArrayList<Float> recent = new ArrayList<>();

        float pct(float q) {
            if (recent.isEmpty()) return 0;
            Float[] a = recent.toArray(new Float[0]);
            Arrays.sort(a);
            return a[Math.min(a.length - 1, (int) Math.floor(q * (a.length - 1) + 0.5))];
        }

        double avg() { return count == 0 ? 0 : sum / count; }
    }

    private Perf() {}

    static synchronized void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("enabled", true);
        for (String[] m : METRICS) {
            Stat s = new Stat();
            s.count = prefs.getLong(m[0] + "_n", 0);
            s.sum = Double.longBitsToDouble(prefs.getLong(m[0] + "_s", 0));
            s.max = Double.longBitsToDouble(prefs.getLong(m[0] + "_x", 0));
            String r = prefs.getString(m[0] + "_r", "");
            if (!r.isEmpty()) for (String v : r.split(",")) try { s.recent.add(Float.parseFloat(v)); } catch (Exception ignored) { }
            stats.put(m[0], s);
        }
    }

    /** Records uncaught crashes (exception type + our top frame only), then lets Android handle them. */
    static synchronized void installCrashHandler(Context c) {
        init(c);
        if (installed) return;
        appCtx = c.getApplicationContext();
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String where = "";
                for (StackTraceElement el : e.getStackTrace()) {
                    if (el.getClassName().startsWith("com.hark.neokey")) {
                        where = el.getClassName().replace("com.hark.neokey.", "") + "." + el.getMethodName() + ":" + el.getLineNumber();
                        break;
                    }
                }
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    String s = sw.toString();
                    if (s.length() > 6000) s = s.substring(0, 6000);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(appCtx.getFilesDir(), "last_crash.txt"));
                    fo.write(("NeoKey crash, thread " + t.getName() + "\n" + s).getBytes("UTF-8"));
                    fo.close();
                } catch (Throwable ignored) { }
                flush();
                prefs.edit().putLong("crashes", prefs.getLong("crashes", 0) + 1)
                        .putString("last_crash", e.getClass().getSimpleName() + (where.isEmpty() ? "" : " @ " + where))
                        .putLong("last_crash_at", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    static boolean enabled() { return enabled; }

    static synchronized void setEnabled(boolean on) {
        enabled = on;
        if (prefs != null) prefs.edit().putBoolean("enabled", on).apply();
    }

    static synchronized void record(String key, double v) {
        if (!enabled || prefs == null || v < 0 || Double.isNaN(v)) return;
        Stat s = stats.get(key);
        if (s == null) { s = new Stat(); stats.put(key, s); }
        s.count++;
        s.sum += v;
        s.max = Math.max(s.max, v);
        s.recent.add((float) v);
        if (s.recent.size() > KEEP) s.recent.remove(0);
        dirty = true;
    }

    static synchronized void session() {
        if (!enabled || prefs == null) return;
        prefs.edit().putLong("sessions", prefs.getLong("sessions", 0) + 1).apply();
    }

    static synchronized void sampleMemory(Context c) {
        if (!enabled) return;
        try {
            android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
            android.os.Debug.getMemoryInfo(mi);
            record("mem", mi.getTotalPss() / 1024.0);
        } catch (Exception ignored) { }
    }

    static synchronized void flush() {
        if (prefs == null || !dirty) return;
        SharedPreferences.Editor e = prefs.edit();
        for (java.util.Map.Entry<String, Stat> en : stats.entrySet()) {
            Stat s = en.getValue();
            String k = en.getKey();
            StringBuilder b = new StringBuilder();
            for (Float f : s.recent) { if (b.length() > 0) b.append(','); b.append(String.format(Locale.US, "%.2f", f)); }
            e.putLong(k + "_n", s.count).putLong(k + "_s", Double.doubleToLongBits(s.sum))
                    .putLong(k + "_x", Double.doubleToLongBits(s.max)).putString(k + "_r", b.toString());
        }
        e.apply();
        dirty = false;
    }

    static synchronized Stat get(String key) {
        Stat s = stats.get(key);
        return s == null ? new Stat() : s;
    }

    static synchronized void clear() {
        stats.clear();
        for (String[] m : METRICS) stats.put(m[0], new Stat());
        boolean on = enabled;
        prefs.edit().clear().putBoolean("enabled", on).apply();
        dirty = false;
    }

    static long sessions() { return prefs == null ? 0 : prefs.getLong("sessions", 0); }
    static long crashes() { return prefs == null ? 0 : prefs.getLong("crashes", 0); }
    static String lastCrash() { return prefs == null ? "" : prefs.getString("last_crash", ""); }
    static long lastCrashAt() { return prefs == null ? 0 : prefs.getLong("last_crash_at", 0); }

    /** The six stability metric keys (CrashReport contract order) — one source of truth. */
    static final String[] STAB_KEYS = {"create", "show", "key", "frame", "sugg", "ai"};

    /** P50/P95 table over STAB_KEYS (stability dashboard, baseline, anonymous report). */
    static double[][] stabPct() {
        double[][] p = new double[STAB_KEYS.length][2];
        for (int i = 0; i < STAB_KEYS.length; i++) {
            Stat s = get(STAB_KEYS[i]);
            p[i][0] = s.pct(0.5f);
            p[i][1] = s.pct(0.95f);
        }
        return p;
    }

    /** Called after a crash is triaged/fixed — zeroes the local counter (Play history untouched). */
    static synchronized void resetCrashes() {
        if (prefs == null) return;
        prefs.edit().remove("last_crash").remove("last_crash_at").putLong("crashes", 0).apply();
    }

    static void putResult(String key, String json) { if (prefs != null) prefs.edit().putString("res_" + key, json).apply(); }
    static String result(String key) { return prefs == null ? null : prefs.getString("res_" + key, null); }

    /** Status in words, so the meaning never depends on colour alone. */
    static String status(int metric, double v) {
        if (v <= 0) return "لا توجد بيانات";
        float[] l = LIMITS[metric];
        return v <= l[0] ? "ممتاز" : v <= l[1] ? "مقبول" : "بطيء";
    }

    static List<String> keys() {
        List<String> l = new ArrayList<>();
        for (String[] m : METRICS) l.add(m[0]);
        return l;
    }

    /** Metrics-only report (no typed text, no clipboard, no dictionary words). */
    static String report(Context c) {
        flush();
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("app", "NeoKey");
            try {
                android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                o.put("version", pi.versionName);
                o.put("version_code", pi.versionCode);
            } catch (Exception ignored) { }
            // No device name/model here: the published contract is "numbers only" — a
            // manufacturer+model pair is a device identifier and does not belong in any export.
            o.put("android", android.os.Build.VERSION.SDK_INT);
            o.put("generated", System.currentTimeMillis());
            org.json.JSONObject m = new org.json.JSONObject();
            for (String[] d : METRICS) {
                Stat s = get(d[0]);
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("unit", d[2]);
                j.put("count", s.count);
                j.put("avg", round(s.avg()));
                j.put("p50", round(s.pct(0.5f)));
                j.put("p95", round(s.pct(0.95f)));
                j.put("max", round(s.max));
                m.put(d[0], j);
            }
            o.put("metrics", m);
            o.put("sessions", sessions());
            o.put("crashes", crashes());
            o.put("crash_rate_pct", sessions() == 0 ? 0 : round(crashes() * 100.0 / sessions()));
            if (!lastCrash().isEmpty()) o.put("last_crash", lastCrash());
            for (String k : new String[]{"bench", "accuracy"}) {
                String r = result(k);
                if (r != null) o.put(k, new org.json.JSONObject(r));
            }
            o.put("privacy", "metrics only - no typed text is collected");
        } catch (Exception ignored) { }
        try { return o.toString(2); } catch (Exception e) { return o.toString(); }
    }

    static double round(double v) { return Math.round(v * 100) / 100.0; }
}
