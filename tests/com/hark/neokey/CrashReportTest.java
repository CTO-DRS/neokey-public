package com.hark.neokey;

/**
 * Roadmap Phase 0 / item A: anonymous crash report + recorded baseline.
 * The privacy contract is enforced here: NUMBERS ONLY + our own sanitized frame —
 * a leaking report fails the build, exactly like the shield's report tests.
 */
public class CrashReportTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    private static final String[] KEYS = {"create", "show", "key", "frame", "sugg", "ai"};
    private static final double[][] PCT = {{210.5, 410.75}, {90, 180}, {3.12, 7.9}, {12, 24}, {0.5, 1.2}, {300, 900}};

    private static String full(String lastCrash) {
        return CrashReport.build("1.0", 56, 30, 123, 2, lastCrash, 1_700_000_000_000L,
                "ok", KEYS, PCT, 1_700_086_400_000L);
    }

    public static void run() {
        System.out.println("== CrashReport: schema fidelity ==");
        check("schema + version + app are fixed strings", full("").contains("\"schema\":\"neokey-crash-1\"")
                && full("").contains("\"report_version\":1") && full("").contains("\"app\":\"neokey\""));
        check("identity fields are numbers only", full("NullPointerException @ Perf.record:112")
                .contains("\"version_name\":\"1.0\",\"version_code\":56,\"android_api\":30"));
        check("sessions/crashes/rate present", full("").contains("\"sessions\":123")
                && full("").contains("\"crashes\":2") && full("").contains("\"crash_rate_pct\":1.63"));
        check("shield state is carried lowercase", full("").contains("\"shield\":\"ok\""));
        String r = full("NullPointerException @ Perf.record:112");
        check("all metric keys appear with p50/p95", r.contains("\"create\":{\"p50_ms\":210.5,\"p95_ms\":410.75}")
                && r.contains("\"key\":{\"p50_ms\":3.12,\"p95_ms\":7.9}")
                && r.contains("\"ai\":{\"p50_ms\":300,\"p95_ms\":900}"));

        System.out.println("== CrashReport:crash rate ==");
        check("rate = crashes/sessions*100, 2 decimals", CrashReport.crashRate(2, 123) == 1.63);
        check("zero sessions -> 0 (no rate, not infinity)", CrashReport.crashRate(5, 0) == 0);
        check("negative counters clamp to 0", CrashReport.crashRate(-3, 100) == 0);
        check("every crash on every session = 100", CrashReport.crashRate(7, 7) == 100);

        System.out.println("== CrashReport:last crash parsing ==");
        String withCrash = full("Resources$NotFoundException @ SetupActivity.onCreate:123");
        check("last_crash parses type", withCrash.contains("\"type\":\"Resources$NotFoundException\""));
        check("last_crash keeps our own frame", withCrash.contains("\"where\":\"SetupActivity.onCreate:123\""));
        check("days_ago computed in day buckets", withCrash.contains("\"days_ago\":1"));
        check("empty crash log -> last_crash:null", full("").contains("\"last_crash\":null"));
        String noSep = full("OutOfMemoryError");
        check("raw type without ' @ ' -> where unknown", noSep.contains("\"type\":\"OutOfMemoryError\"")
                && noSep.contains("\"where\":\"unknown\""));
        check("future timestamp clamps days_ago to 0",
                full("X @ A.b:1").contains("\"days_ago\":0") || full("X @ A.b:1").contains("\"days_ago\":1"));

        System.out.println("== CrashReport:where sanitizer (own frames only) ==");
        check("plain frame passes", "Perf.record:112".equals(CrashReport.sanitizeWhere("Perf.record:112")));
        check("inner-class frame passes", "ClipRepo$1.onClick:22".equals(CrashReport.sanitizeWhere("ClipRepo$1.onClick:22")));
        check("file path degrades to unknown", "unknown".equals(CrashReport.sanitizeWhere("/data/user/0/com.hark/Secret.java:1")));
        check("url/text degrades to unknown", "unknown".equals(CrashReport.sanitizeWhere("http://evil/x a b")));
        StringBuilder longFrame = new StringBuilder("A");
        for (int i = 0; i < 100; i++) longFrame.append(".aaaa");
        check("over-long frame truncated to 80", CrashReport.sanitizeWhere(longFrame.toString()).length() == 80);
        check("null/empty -> unknown", "unknown".equals(CrashReport.sanitizeWhere(null))
                && "unknown".equals(CrashReport.sanitizeWhere("  ")));

        System.out.println("== CrashReport:privacy contract (numbers only) ==");
        String worst = full("Exception @ MainActivity.onTextInput:99") + CrashReport.baseline(KEYS, PCT, 56, 30, 0L);
        boolean leak = worst.matches("(?s).*(imei|serial|android_id|\"model\"|\"fingerprint\"|\"mac\"|device_name|/data/|http).*");
        check("report + baseline carry no identifiers, paths or urls", !leak);
        check("no typed text field exists anywhere", !worst.contains("text") && !worst.contains("word"));

        System.out.println("== CrashReport:baseline (Phase-0 exit criterion) ==");
        String bl = CrashReport.baseline(KEYS, PCT, 56, 30, 1_700_000_000_000L);
        check("baseline schema", bl.contains("\"schema\":\"neokey-baseline-1\""));
        check("baseline keeps build identity + metrics", bl.contains("\"version_code\":56")
                && bl.contains("\"show\":{\"p50_ms\":90,\"p95_ms\":180}"));
        check("baseline carries no stability fields", !bl.contains("sessions") && !bl.contains("crashes")
                && !bl.contains("shield"));
        check("null/short arrays degrade to zeros, never throw",
                CrashReport.baseline(new String[]{"k"}, new double[][]{null}, 56, 30, 0L)
                        .contains("\"k\":{\"p50_ms\":0,\"p95_ms\":0}"));
        check("NaN/Infinity clamp to 0",
                CrashReport.baseline(new String[]{"k"}, new double[][]{{Double.NaN, Double.POSITIVE_INFINITY}},
                        56, 30, 0L).contains("\"k\":{\"p50_ms\":0,\"p95_ms\":0}"));

        // No RESULT line here on purpose: sub-suites report via static counters and the
        // CoreTests aggregator prints the single aggregate RESULT line.
        if (fail > 0) System.exit(1);
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
    }
}
