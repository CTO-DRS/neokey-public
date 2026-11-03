package com.hark.neokey;

import java.io.File;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;

/** Roadmap Phase 0 / Week 3 (CI/CD item B): the performance gate.
 *  Benchmarks the hot core against the REAL 150k-word dictionary and real SQLite,
 *  then enforces the reference budgets — the roadmap's "تقيس الأداء ضد الحدود المرجعية".
 *  Machine-readable output (for ci/pipeline.sh): PERFGATE lines + PERF_GATE_OK / PERF_GATE_FAILED.
 *  Exit code 1 on any budget breach. Usage: java -cp build/test:<jars> com.hark.neokey.PerfGate */
public class PerfGate {
    static int breaches = 0;

    static void verdict(String name, double p50, double p95, double budgetMs) {
        boolean ok = p50 < budgetMs;
        if (!ok) breaches++;
        System.out.printf(Locale.US, "PERFGATE name=%s p50=%.3fms p95=%.3fms budget=%.1fms verdict=%s%n",
                name, p50, p95, budgetMs, ok ? "OK" : "BREACH");
    }

    /** Per-op gate from an array of per-op nanos. */
    static void gate(String name, long[] ns, double budgetMs) {
        Arrays.sort(ns);
        double p50 = ns[ns.length / 2] / 1e6;
        double p95 = ns[Math.min(ns.length - 1, (int) Math.floor(ns.length * 0.95))] / 1e6;
        verdict(name, p50, p95, budgetMs);
    }

    /** Total-time gate (bulk op measured once, warm run). */
    static void gateTotal(String name, long totalNs, double budgetMs) {
        double ms = totalNs / 1e6;
        System.out.printf(Locale.US, "PERFGATE name=%s total=%.1fms budget=%.1fms verdict=%s%n",
                name, ms, budgetMs, ms < budgetMs ? "OK" : "BREACH");
        if (!(ms < budgetMs)) breaches++;
    }

    static Dictionary dict;

    public static void main(String[] args) throws Exception {
        System.out.println("== PerfGate: dictionary (150k asset, JVM) ==");
        File dir = new File("build/perf-dict");
        dir.mkdirs();
        dict = new Dictionary(new FakeContext(dir), "ar");
        long t0 = System.currentTimeMillis();
        while (!dict.isLoaded() && System.currentTimeMillis() - t0 < 15000) Thread.sleep(25);
        if (!dict.isLoaded()) { System.out.println("PERF_GATE_FAILED (dictionary did not load)"); System.exit(2); }

        // warmup (JIT) then measured runs
        for (int i = 0; i < 25; i++) dict.suggest("سلام", 10);
        long[] sugg = new long[200];
        for (int i = 0; i < sugg.length; i++) {
            long a = System.nanoTime(); dict.suggest("سلام", 10); sugg[i] = System.nanoTime() - a;
        }
        gate("suggest(سلام,10)", sugg, 8.0);

        for (int i = 0; i < 50; i++) dict.predict("في", 5);
        long[] pred = new long[500];
        for (int i = 0; i < pred.length; i++) {
            long a = System.nanoTime(); dict.predict("في", 5); pred[i] = System.nanoTime() - a;
        }
        gate("predict(في,5)", pred, 1.0);

        long[] corr = new long[100];
        for (int i = 0; i < corr.length; i++) {
            long a = System.nanoTime(); dict.correction("السلآم"); corr[i] = System.nanoTime() - a;
        }
        gate("correction(السلآم)", corr, 8.0);

        System.out.println("== PerfGate: clipboard (real SQLite, in-memory) ==");
        Class.forName("org.sqlite.JDBC");
        final Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        ClipRepo.Sql sql = new ClipRepo.Sql() {
            public void exec(String q, Object... a) { run(q, a, false); }
            public long insert(String q, Object... a) { run(q, a, false); try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) { r.next(); return r.getLong(1); } catch (SQLException e) { throw new RuntimeException(e); } }
            public List<Object[]> query(String q, Object... a) { return run(q, a, true); }
            private List<Object[]> run(String q, Object[] a, boolean fetch) {
                Object[] b = new Object[a.length];
                for (int i = 0; i < a.length; i++) b[i] = String.valueOf(a[i]);
                try (PreparedStatement p = c.prepareStatement(q)) {
                    for (int i = 0; i < b.length; i++) p.setObject(i + 1, b[i]);
                    if (!fetch) { p.execute(); return null; }
                    List<Object[]> out = new ArrayList<>();
                    try (ResultSet r = p.executeQuery()) {
                        int n = r.getMetaData().getColumnCount();
                        while (r.next()) { Object[] row = new Object[n]; for (int i = 0; i < n; i++) row[i] = r.getObject(i + 1); out.add(row); }
                    }
                    return out;
                } catch (SQLException e) { throw new RuntimeException(e); }
            }
            public void begin() { try { c.setAutoCommit(false); } catch (SQLException e) { throw new RuntimeException(e); } }
            public void commit() { try { c.commit(); c.setAutoCommit(true); } catch (SQLException e) { throw new RuntimeException(e); } }
            public void rollback() { try { c.rollback(); c.setAutoCommit(true); } catch (SQLException e) { throw new RuntimeException(e); } }
        };
        ClipRepo.createSchema(sql);
        ClipRepo repo = new ClipRepo(sql);
        long now = System.currentTimeMillis();
        String unit = "بِسْمِ اللَّهِ Hello, \"world\"\t😀 ١٢٣ سطر جديد، 中文; a,b\n";
        long ti = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            StringBuilder sb = new StringBuilder(unit);
            while (sb.length() < 50 + (i % 500)) sb.append(unit);
            sb.setLength(50 + (i % 500));
            repo.add(sb + "i" + i, now + i, null);
        }
        gateTotal("clip.insert1000", System.nanoTime() - ti, 900.0);

        long tl = System.nanoTime();
        for (int i = 0; i < 10; i++) repo.list(400);
        gateTotal("clip.list400x10", System.nanoTime() - tl, 200.0);

        StringBuilder big = new StringBuilder(unit);
        while (big.length() < 100_000) big.append(unit);
        big.append(" إبرة_مخفية_للبحث");
        long[] ids = new long[1];
        repo.add(big.toString(), now, ids);
        long ts = System.nanoTime();
        for (int i = 0; i < 20; i++) repo.searchCount(0, "شيء غير موجود " + i);
        gateTotal("clip.search20in100k", System.nanoTime() - ts, 60.0);
        gate("clip.searchHit(deep)", new long[]{ probeHit(repo) }, 120.0);

        long te = System.nanoTime();
        StringWriter sw = new StringWriter();
        ClipIO.write(ClipIO.F_JSON, ClipIO.load(repo, ClipIO.SCOPE_ALL, null), sw, now, "perfgate");
        gateTotal("clip.export1000", System.nanoTime() - te, 400.0);

        long tp = System.nanoTime();
        ClipIO.Parsed parsed = ClipIO.parse(sw.toString(), "perfgate.json", false);
        long tn = System.nanoTime();
        gateTotal("clip.parse1000", tn - tp, 400.0);

        // the items came from this same repo, so every record is a duplicate —
        // markDuplicates exercises the findSame/norm path over 1000 rows
        long td = System.nanoTime();
        ClipIO.markDuplicates(repo, parsed);
        gateTotal("clip.dupScan1000", System.nanoTime() - td, 400.0);
        ClipIO.apply(repo, parsed.items, ClipIO.DUP_SKIP, now); // all dups -> adds nothing (correctness lives in ClipIOTest)

        System.out.println(breaches == 0 ? "PERF_GATE_OK" : "PERF_GATE_FAILED (" + breaches + " breach(es))");
        if (breaches > 0) System.exit(1);
    }

    /** one hit-search inside the 100k text, timed */
    static long probeHit(ClipRepo repo) {
        long a = System.nanoTime();
        List<ClipItem> hits = repo.search(0, "إبرة_مخفية", 5);
        if (hits.isEmpty()) { System.out.println("PERF_GATE_FAILED (hit probe found nothing)"); System.exit(2); }
        return System.nanoTime() - a;
    }
}
