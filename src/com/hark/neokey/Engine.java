package com.hark.neokey;

/**
 * Engine — the W55 unified contract for the deep-Arabic language engines
 * (ROADMAP-V2 W55): RootEngine (W56), TashkeelEngine (W57), GrammarNudge (W58)
 * and AdaptEngine (W60) all implement this ONE shape — integration, not fork
 * (law 9). Pure logic, zero android.* — JVM-tested exactly as shipped (the
 * Predictor/Integrity discipline).
 *
 * The contract's laws:
 *   • ONE input shape: Ctx (prev2/prev1/composing) — no engine invents its own
 *     input; nulls never enter an engine (the Ctx constructor normalizes them).
 *   • SOURCE-LABELED output: every candidate carries the engine's id() — the
 *     W60 badge primitive (dict/net/personal/community/root/tashkeel/…).
 *   • FAIL-CLOSED (the project convention): run() may throw or return any
 *     illegal shape — the dispatcher (Engines) contains it to a counted
 *     failure; a broken engine costs its place, never the keyboard.
 *   • PURE + SYNCHRONOUS: in-memory over assets (law 6: knowledge lives in
 *     assets/, the dex carries thin interpreters) — no I/O, no threads, no
 *     blocking, no network inside run() (NetGate owns the network; law 10).
 *   • PRIVACY: the context is transient — nothing stored, nothing logged,
 *     nothing sent (law 8).
 */
public interface Engine {

    /** The source label stamped on every candidate this engine emits. */
    String id();

    /**
     * Candidates for the context, best first; may be empty; never null by
     * convention — null and any illegal shape are contained by the dispatcher,
     * never shipped to the strip.
     */
    String[] run(Ctx ctx);

    /** The unified input context — one shape for every engine, transient by law. */
    final class Ctx {
        public final String prev2, prev1, composing;
        public Ctx(String prev2, String prev1, String composing) {
            this.prev2 = prev2 == null ? "" : prev2;
            this.prev1 = prev1 == null ? "" : prev1;
            this.composing = composing == null ? "" : composing;
        }
        /** Lengths only — the words themselves are never stringified (privacy law). */
        public String toString() {
            return "ctx(" + prev2.length() + "," + prev1.length() + "," + composing.length() + ")";
        }
    }

    /** A source-labeled candidate (the W60 badge primitive). */
    final class Cand {
        public final String text, source;
        public Cand(String text, String source) {
            this.text = text == null ? "" : text;
            this.source = source == null ? "" : source;
        }
        public boolean equals(Object o) {
            return o instanceof Cand && ((Cand) o).text.equals(text) && ((Cand) o).source.equals(source);
        }
        public int hashCode() { return text.hashCode() * 31 + source.hashCode(); }
        public String toString() { return text + "<-" + source; }
    }
}
