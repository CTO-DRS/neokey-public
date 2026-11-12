package com.hark.neokey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Engines — the ONE fail-closed dispatcher over the registered Engine
 * implementations (the W55 contract, ROADMAP-V2 W55). The keyboard's single
 * call site: Engines.run(ctx, limit) — every registered engine consulted in
 * registration order, its output sanitized and source-labeled, its failure
 * contained to a counter. The registry ships EMPTY: the keyboard is
 * fail-closed silent until W56+ registers the first real engine.
 *
 * Failure containment (the exit-safe law — خروج آمن عند كل فشل):
 *   • an engine that throws (any Throwable) → contained, failures++, the rest flow;
 *   • an engine that returns null → a failure, the rest flow;
 *   • null/blank slots in an otherwise legal output → dropped, counted;
 *   • more than MAX_PER_ENGINE candidates → truncated (shape, not failure);
 *   • run() itself never throws and never returns null — the strip never starves,
 *     never breaks, whatever the engines do.
 *
 * Registration is the strict boundary (the NQF.read/Predictor discipline):
 * a null engine, a blank id or a duplicate id is REFUSED with the previous
 * registry untouched — never a half-built registry.
 */
public final class Engines {

    /** Output shape: no engine floods the strip — anything beyond this is dropped. */
    public static final int MAX_PER_ENGINE = 8;

    private static final Engine[] NONE = {};
    private static volatile Engine[] list = NONE;
    private static final AtomicLong failures = new AtomicLong();

    private Engines() {}

    /**
     * Strict registration: null engine / blank id / duplicate id refused with
     * the registry untouched. An empty call clears the registry (the harness
     * and the drills use this to reset). Synchronized: registration is rare,
     * run() is hot — run() reads the volatile array lock-free.
     */
    public static synchronized void set(Engine... engines) {
        Engine[] next = engines == null ? NONE : engines.clone();
        HashSet<String> ids = new HashSet<>();
        for (Engine e : next) {
            if (e == null) throw new IllegalArgumentException("engine.contract: null engine refused");
            String id = e.id();
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("engine.contract: blank id refused");
            if (!ids.add(id)) throw new IllegalArgumentException("engine.contract: duplicate id refused");
        }
        list = next;
    }

    /** Registered engine ids in registration order (a defensive copy — diagnostics only). */
    public static String[] ids() {
        Engine[] es = list;
        String[] out = new String[es.length];
        for (int i = 0; i < es.length; i++) out[i] = es[i].id();
        return out;
    }

    /** Contained failures since process start — a count only, never text (privacy law). */
    public static long failures() { return failures.get(); }

    /**
     * The ONE call site: merged candidates, best first (registration order,
     * each engine best-first), deduped across engines (first source wins),
     * capped at limit. Never throws, never returns null, never emits null or
     * blank text, never more than limit items.
     */
    public static Engine.Cand[] run(Engine.Ctx ctx, int limit) {
        if (limit <= 0) return new Engine.Cand[0];
        Engine.Ctx c = ctx == null ? new Engine.Ctx(null, null, null) : ctx;
        List<Engine.Cand> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Engine e : list) {
            String[] r;
            try { r = e.run(c); }
            catch (Throwable t) { failures.incrementAndGet(); continue; }
            if (r == null) { failures.incrementAndGet(); continue; }
            int n = Math.min(r.length, MAX_PER_ENGINE);
            for (int i = 0; i < n; i++) {
                String s = r[i];
                if (s == null || s.trim().isEmpty()) { failures.incrementAndGet(); continue; }
                s = s.trim();
                if (!seen.add(s)) continue;   // dedupe: first source wins
                out.add(new Engine.Cand(s, e.id()));
                if (out.size() >= limit) return out.toArray(new Engine.Cand[0]);
            }
        }
        return out.toArray(new Engine.Cand[0]);
    }
}
