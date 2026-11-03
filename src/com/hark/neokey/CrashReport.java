package com.hark.neokey;

/**
 * Roadmap Phase 0 / item A closure — anonymous crash report + recorded perf baseline.
 * ("ربط سجل الأعطال بخدمة تقارير مجهولة الهوية بالكامل تحترم مبدأ الخصوصية" + معيار الخروج:
 *  "قياس أداء أساسي مسجل: P50 وP95").
 *
 * Contract (published, tested, same philosophy as Integrity):
 *  - PURE logic, zero android.* imports; tested on the JVM as shipped.
 *  - The report carries NUMBERS ONLY plus our own crash frame (Class.method:line).
 *    No device identifiers, no typed text, no settings, no user words — ever.
 *  - Nothing is SENT anywhere from here. There is no upload endpoint in this build;
 *    the user exports by hand (share/copy) and the cloud service is switched on
 *    later behind an explicit opt-in when a server exists. Local-first is the point.
 *  - The crash location is sanitized: only our own "Class.method:line" shape survives;
 *    anything else (paths, external frames) degrades to "unknown". Length is capped.
 */
public final class CrashReport {

    private CrashReport() { }

    public static final String SCHEMA = "neokey-crash-1";
    public static final String BASELINE_SCHEMA = "neokey-baseline-1";
    public static final int REPORT_VERSION = 1;
    /** Cap for the crash location field — our own frame is short; abuse stays bounded. */
    private static final int WHERE_MAX = 80;
    /** Day bucket in ms — the report only ever says "days ago", never a precise time. */
    private static final long DAY_MS = 86_400_000L;

    /**
     * Anonymous stability report (the exported "تقرير الاستقرار").
     *
     * @param versionName  versionName from the manifest ("1.0")
     * @param versionCode  versionCode from the manifest (56)
     * @param apiLevel     android API level (coarse, functional — not an identifier)
     * @param sessions     keyboard-open sessions counter (Perf)
     * @param crashes      crash counter (Perf)
     * @param lastCrashRaw Perf's stored "Type @ Class.method:line" ("" when none)
     * @param lastCrashAtMs epoch ms of the last crash (0 when none); exported as day buckets
     * @param shieldState  Integrity shield state name (ok/mismatch/sandbox/unknown)
     * @param metricKeys   timing metric keys ("create","show","key","frame","sugg","ai")
     * @param metricPct    per key {p50, p95} in ms (same order as metricKeys)
     */
    public static String build(String versionName, int versionCode, int apiLevel,
                               long sessions, long crashes, String lastCrashRaw, long lastCrashAtMs,
                               String shieldState, String[] metricKeys, double[][] metricPct,
                               long generatedAtMs) {
        StringBuilder b = new StringBuilder(512);
        b.append("{\"schema\":\"").append(SCHEMA).append('"');
        b.append(",\"report_version\":").append(REPORT_VERSION);
        b.append(",\"app\":\"neokey\"");
        b.append(",\"version_name\":\"").append(esc(versionName)).append('"');
        b.append(",\"version_code\":").append(versionCode);
        b.append(",\"android_api\":").append(apiLevel);
        b.append(",\"sessions\":").append(Math.max(0, sessions));
        b.append(",\"crashes\":").append(Math.max(0, crashes));
        b.append(",\"crash_rate_pct\":").append(fmt2(crashRate(crashes, sessions)));
        if (lastCrashRaw == null || lastCrashRaw.trim().isEmpty()) {
            b.append(",\"last_crash\":null");
        } else {
            String raw = lastCrashRaw.trim();
            int at = raw.indexOf(" @ ");
            String type = at >= 0 ? raw.substring(0, at) : raw;
            String where = at >= 0 ? raw.substring(at + 3) : "";
            long days = (lastCrashAtMs <= 0 || generatedAtMs < lastCrashAtMs)
                    ? 0 : (generatedAtMs - lastCrashAtMs) / DAY_MS;
            b.append(",\"last_crash\":{\"type\":\"").append(esc(type)).append('"');
            b.append(",\"where\":\"").append(esc(sanitizeWhere(where))).append('"');
            b.append(",\"days_ago\":").append(days).append('}');
        }
        b.append(",\"shield\":\"").append(shieldState == null || shieldState.isEmpty()
                ? "unknown" : shieldState.toLowerCase()).append('"');
        b.append(",\"metrics\":{");
        if (metricKeys != null && metricPct != null) {
            for (int i = 0; i < metricKeys.length && i < metricPct.length; i++) {
                if (i > 0) b.append(',');
                double[] p = metricPct[i];
                b.append('"').append(esc(metricKeys[i])).append("\":{\"p50_ms\":")
                 .append(fmt2(num(p != null && p.length > 0 ? p[0] : 0)))
                 .append(",\"p95_ms\":").append(fmt2(num(p != null && p.length > 1 ? p[1] : 0))).append('}');
            }
        }
        b.append("},\"generated_at\":").append(generatedAtMs).append('}');
        return b.toString();
    }

    /**
     * The recorded perf baseline (Phase-0 exit criterion): P50/P95 of the board-open,
     * key-press and suggestion metrics for THIS build, ready to file next to the release.
     * Same privacy shape as the crash report minus the stability fields.
     */
    public static String baseline(String[] metricKeys, double[][] metricPct,
                                  int versionCode, int apiLevel, long generatedAtMs) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"schema\":\"").append(BASELINE_SCHEMA).append('"');
        b.append(",\"app\":\"neokey\"");
        b.append(",\"version_code\":").append(versionCode);
        b.append(",\"android_api\":").append(apiLevel);
        b.append(",\"metrics\":{");
        if (metricKeys != null && metricPct != null) {
            for (int i = 0; i < metricKeys.length && i < metricPct.length; i++) {
                if (i > 0) b.append(',');
                double[] p = metricPct[i];
                b.append('"').append(esc(metricKeys[i])).append("\":{\"p50_ms\":")
                 .append(fmt2(num(p != null && p.length > 0 ? p[0] : 0)))
                 .append(",\"p95_ms\":").append(fmt2(num(p != null && p.length > 1 ? p[1] : 0))).append('}');
            }
        }
        b.append("},\"generated_at\":").append(generatedAtMs).append('}');
        return b.toString();
    }

    /** Crashes per 100 sessions, two decimals; no sessions yet means no rate, not infinity. */
    public static double crashRate(long crashes, long sessions) {
        if (sessions <= 0) return 0;
        return Math.round(Math.max(0, crashes) * 10000.0 / sessions) / 100.0;
    }

    /**
     * Keep ONLY our own frame shape "Class.method:line" (optionally with inner-class $).
     * External paths, URLs, arbitrary text -> "unknown". Capped at WHERE_MAX chars.
     */
    public static String sanitizeWhere(String where) {
        if (where == null) return "unknown";
        String w = where.trim();
        if (w.isEmpty()) return "unknown";
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '$' || c == ':';
            if (!ok) return "unknown";
        }
        if (w.length() > WHERE_MAX) w = w.substring(0, WHERE_MAX);
        return w;
    }

    /** "NPE @ ClipRepo.insert:123" -> type; used by the report builder. */
    public static String crashType(String lastCrashRaw) {
        if (lastCrashRaw == null) return "";
        String raw = lastCrashRaw.trim();
        int at = raw.indexOf(" @ ");
        return at >= 0 ? raw.substring(0, at) : raw;
    }

    private static double num(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.max(0, v); }

    private static String fmt2(double v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        if (s.endsWith("00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Minimal JSON string escaping (backslash + quote + control chars). */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') { b.append('\\').append(c); }
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
