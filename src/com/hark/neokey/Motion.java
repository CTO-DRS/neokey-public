package com.hark.neokey;

/**
 * W116 «لمسة NeoKey»: the keyboard's signature motion curves. Pure math (no android.*)
 * so DialectCorrGate 3ab can pin every curve on a plain JVM.
 *
 *  - entrance: keys rise in a wave from the space bar outward with a spring overshoot
 *  - bounce:   a key springs past rest once after it is released
 *  - breath:   the Enter key breathes softly while a message waits to be sent
 *  - flow:     a light line on the top edge that grows with typing rhythm
 *  - shimmer:  one light sweep across the space bar as the keyboard opens
 */
final class Motion {
    private Motion() { }

    static final int ENTER_MS = 430, WAVE_MS = 170, BOUNCE_MS = 300, BREATH_MS = 2600,
            BREATH_AFTER = 1400, BREATH_FOR = 14000, SHIMMER_AT = 260, SHIMMER_MS = 650, BRAND_MS = 1300;

    /** Under-damped spring from 0 to 1: peaks ~1.08 near 35%, settles at 1. */
    static float spring(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return (float) (1 - Math.exp(-7 * x) * Math.cos(9 * x));
    }

    /** Wave delay for a key centred at (cx, cy) in a w×h board: 0 at the bottom centre, WAVE_MS at a top corner. */
    static float waveDelay(float cx, float cy, float w, float h) {
        if (w <= 0 || h <= 0) return 0;
        double dx = (cx - w / 2f) / w, dy = (h - cy) / h;
        double d = Math.sqrt(dx * dx + dy * dy) / Math.sqrt(1.25);
        return (float) (WAVE_MS * Math.max(0, Math.min(1, d)));
    }

    /** Entrance progress (spring) for a key, ms since the keyboard opened; 1 once it has landed. */
    static float entrance(float sinceShow, float delay) {
        float x = (sinceShow - delay) / (ENTER_MS - WAVE_MS);
        return spring(x);
    }

    /** Release overshoot as a scale delta: up to ~+4%, back to 0 within BOUNCE_MS. */
    static float bounce(float sinceRelease) {
        if (sinceRelease <= 0 || sinceRelease >= BOUNCE_MS) return 0;
        return (float) (0.045 * Math.exp(-sinceRelease / 75.0) * Math.sin(sinceRelease * Math.PI / 110.0));
    }

    /** Enter breathing level 0..1, or 0 when it should be still (too soon, too long idle, nothing typed). */
    static float breath(float sinceType, boolean hasText) {
        if (!hasText || sinceType < BREATH_AFTER || sinceType > BREATH_AFTER + BREATH_FOR) return 0;
        float t = sinceType - BREATH_AFTER;
        float in = Math.min(1f, t / 600f);
        return in * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t / BREATH_MS));
    }

    /** Flow level after a keystroke that came `gapMs` after the previous one (0..1, ~9 keys/s fills it). */
    static float flowHit(float level, float gapMs) {
        if (gapMs <= 0) gapMs = 1;
        float inst = Math.min(1f, (1000f / gapMs) / 9f);
        if (gapMs > 1500) inst = 0.12f;
        return Math.max(0, Math.min(1, level * 0.72f + inst * 0.28f + 0.04f));
    }

    /** Flow level fading out while idle. */
    static float flowDecay(float level, float idleMs) {
        if (idleMs <= 250) return level;
        return (float) (level * Math.exp(-(idleMs - 250) / 450.0));
    }

    /** Space-bar light sweep position 0..1, or -1 outside the sweep. */
    static float shimmer(float sinceShow) {
        float t = sinceShow - SHIMMER_AT;
        if (t < 0 || t > SHIMMER_MS) return -1;
        float x = t / SHIMMER_MS;
        return x * x * (3 - 2 * x);
    }

    /** Brand wordmark alpha on the space bar (1 → 0) as the keyboard opens; the language label takes over. */
    static float brand(float sinceShow) {
        if (sinceShow < 0 || sinceShow >= BRAND_MS) return 0;
        if (sinceShow < BRAND_MS - 450) return 1;
        return (BRAND_MS - sinceShow) / 450f;
    }

    // ---------------------------------------------------------------- W117 touch & chips

    /** Ink ripple: grows from the finger over INK_GROW, then fades over INK_FADE after lift. */
    static final long INK_GROW = 260, INK_FADE = 240, HOLD_FROM = 120, CHIP_MS = 260, CHIP_STAGGER = 45;

    static float inkRadius(long t) {
        if (t <= 0) return 0.15f;
        if (t >= INK_GROW) return 1f;
        float x = t / (float) INK_GROW;
        return 0.15f + 0.85f * (1f - (1f - x) * (1f - x) * (1f - x));
    }

    /** sinceUp < 0 while the finger is still down. */
    static float inkAlpha(long sinceUp) {
        if (sinceUp < 0) return 1f;
        if (sinceUp >= INK_FADE) return 0f;
        float x = sinceUp / (float) INK_FADE;
        return (1f - x) * (1f - x);
    }

    /** Long-press progress bar: 0 until HOLD_FROM, 1 at the long-press delay. */
    static float hold(long held, long delay) {
        if (held <= HOLD_FROM || delay <= HOLD_FROM) return 0f;
        if (held >= delay) return 1f;
        return (held - HOLD_FROM) / (float) (delay - HOLD_FROM);
    }

    /** Suggestion chip entrance, staggered by slot. */
    static float chipIn(long t, int slot) {
        long d = t - slot * CHIP_STAGGER;
        if (d <= 0) return 0f;
        if (d >= CHIP_MS) return 1f;
        float x = d / (float) CHIP_MS;
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    // ---------------------------------------------------------------- W118 long-press motion

    static final long POP_MS = 300;

    /** Back-ease pop: 0 → ~1.1 → 1 over POP_MS (popups grow out of the key). */
    static float popIn(long t) {
        if (t <= 0) return 0f;
        if (t >= POP_MS) return 1f;
        float x = t / (float) POP_MS - 1f, c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    /** Frame-rate independent glide of a value toward its target (~60 ms time constant). */
    static float approach(float cur, float target, float dtMs) {
        if (dtMs <= 0) return cur;
        float k = 1f - (float) Math.exp(-dtMs / 60f);
        float v = cur + (target - cur) * k;
        return Math.abs(target - v) < 0.002f ? target : v;
    }

    /** Held delete accelerates: 55 ms for the first 8 repeats, then down to 22 ms. */
    static long repeatGap(int n) {
        if (n < 8) return 55;
        return Math.max(22, 55 - (n - 8) * 3);
    }

    /** Delete-key heat 0..1 with the repeat count. */
    static float heat(int n) { return n <= 0 ? 0f : Math.min(1f, n / 30f); }

    // ---------------------------------------------------------------- W119 six-chip strip

    /**
     * Clamps each chip width to [minW, 0.75·avail] in place and returns how many
     * chips fit: the weakest tail drops while the row would squeeze past 1.35×.
     */
    static int fitChips(float[] w, int n, float avail, float minW) {
        for (int i = 0; i < n; i++) w[i] = Math.max(minW, Math.min(avail * 0.75f, w[i]));
        float tot = 0;
        for (int i = 0; i < n; i++) tot += w[i];
        while (n > 1 && tot > avail * 1.35f) tot -= w[--n];
        return n;
    }
}
