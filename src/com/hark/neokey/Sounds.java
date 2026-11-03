package com.hark.neokey;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Key sound packs, synthesised in code (no audio assets). Each pack has four sounds:
 * 0 = letter, 1 = delete, 2 = space, 3 = enter. Rendered once to small WAV files in the
 * cache and played through a low-latency SoundPool.
 */
final class Sounds {
    /** Pack 0 = the phone's own click sound. */
    static final String[] NAMES = {
            "صوت النظام", "نقرة ناعمة", "ميكانيكي أزرق", "ميكانيكي بني", "آلة كاتبة", "فقاعة",
            "قطرة ماء", "خشب", "زجاج", "ليزر 2036", "بيانو", "ألعاب 8-بت",
            "طبل", "نقرة عميقة", "كريستال", "نبضة نيون",
            "مطر", "ماريمبا", "جرس صغير", "قيثارة", "ورق", "نقر أصابع",
            "روبوت", "سحري", "سنث ويف", "عود", "فضاء", "ساعة",
    };
    static final String[] DESC = {
            "الصوت الافتراضي للجوال", "خفيفة وهادئة", "طقطقة عالية", "صوت مكتوم", "مع جرس عند الإدخال", "بوب لطيف",
            "قطرات متساقطة", "دافئ وقصير", "رنين صافٍ", "مستقبلي", "نغمات مختلفة", "ريترو",
            "إيقاعي", "منخفض وقوي", "رنّان", "إلكتروني",
            "قطرات خفيفة", "خشبي موسيقي", "رنّة لطيفة", "أوتار ناعمة", "حفيف", "هادئ جدًا",
            "آلي", "لمعة", "ثمانينات", "وتر شرقي", "صدى بعيد", "تك تك",
    };
    static int count() { return NAMES.length; }

    private static final int SR = 44100;
    private static final int VER = 2;

    private final Context ctx;
    private SoundPool pool;
    private final int[] ids = {0, 0, 0, 0};
    private int pack = -1;

    Sounds(Context c) { ctx = c.getApplicationContext(); }

    void setPack(int p) {
        if (p == pack) return;
        pack = p;
        if (pool == null) {
            pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        }
        for (int k = 0; k < 4; k++) {
            if (ids[k] != 0) { pool.unload(ids[k]); ids[k] = 0; }
            if (p <= 0) continue;
            try { ids[k] = pool.load(file(ctx, p, k).getAbsolutePath(), 1); } catch (Exception ignored) { }
        }
    }

    /** Plays kind 0..3 at volume 0..1. Returns false when the pack is the system sound. */
    boolean play(int kind, float vol) {
        if (pack <= 0 || pool == null || ids[kind] == 0) return false;
        pool.play(ids[kind], vol, vol, 1, 0, 1f);
        return true;
    }

    void release() { if (pool != null) { pool.release(); pool = null; } pack = -1; }

    // ------------------------------------------------------------------ synthesis

    static File file(Context c, int p, int k) throws Exception {
        File f = new File(c.getCacheDir(), "snd_" + VER + "_" + p + "_" + k + ".wav");
        if (!f.exists() || f.length() < 100) writeWav(f, render(p, k));
        return f;
    }

    private static double env(double t, double a, double d) {
        if (t < a) return t / a;
        return Math.exp(-(t - a) / d);
    }

    static short[] render(int p, int k) {
        java.util.Random rnd = new java.util.Random(p * 31 + k);
        double pitch = k == 1 ? 0.8 : k == 2 ? 0.65 : k == 3 ? 1.25 : 1.0;
        double len = k == 2 ? 0.14 : k == 3 ? 0.2 : 0.1;
        if (p == 4 && k == 3) len = 0.6;
        if (p == 8 || p == 14) len = 0.3;
        if (p == 10) len = 0.45;
        if (p == 18 || p == 19 || p == 25 || p == 26) len = 0.4;
        if (p == 17 || p == 23 || p == 24) len = 0.25;
        double[] ks = null; int kp = 0;
        if (p == 19 || p == 25) { // Karplus-Strong plucked string
            double f = p == 19 ? (k == 0 ? 784 : k == 1 ? 523 : k == 2 ? 587 : 1046) : (k == 0 ? 220 : k == 1 ? 165 : k == 2 ? 196 : 294);
            ks = new double[Math.max(2, (int) (SR / f))];
            for (int i = 0; i < ks.length; i++) ks[i] = rnd.nextDouble() * 2 - 1;
        }
        double nf = k == 0 ? 1 : k == 1 ? 0.75 : k == 2 ? 0.84 : 1.5;
        int n = (int) (SR * len);
        double[] s = new double[n];
        double lp = 0, ph = 0, ph2 = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR, v = 0, noise = rnd.nextDouble() * 2 - 1;
            switch (p) {
                case 1: // soft click
                    v = noise * env(t, 0.0005, 0.004) * 0.5 + Math.sin(2 * Math.PI * 1800 * pitch * t) * env(t, 0.0005, 0.01) * 0.4;
                    break;
                case 2: { // blue switch: two sharp clicks
                    double c1 = noise * env(t, 0.0003, 0.003), t2 = t - 0.012;
                    double c2 = t2 > 0 ? noise * env(t2, 0.0003, 0.004) : 0;
                    v = (c1 * 0.7 + c2 * 0.9) + Math.sin(2 * Math.PI * 3200 * pitch * t) * env(t, 0.0005, 0.008) * 0.3;
                    break;
                }
                case 3: { // brown / thock
                    lp += (noise - lp) * 0.15;
                    v = lp * env(t, 0.0005, 0.006) * 1.2 + Math.sin(2 * Math.PI * 220 * pitch * t) * env(t, 0.001, 0.03) * 0.6;
                    break;
                }
                case 4: // typewriter (+ bell on enter)
                    if (k == 3) v = (Math.sin(2 * Math.PI * 2100 * t) + 0.5 * Math.sin(2 * Math.PI * 4230 * t)) * env(t, 0.001, 0.18) * 0.45
                            + noise * env(t, 0.0003, 0.004) * 0.6;
                    else v = noise * env(t, 0.0003, 0.005) * 0.8 + (Math.sin(2 * Math.PI * 2500 * pitch * t) + Math.sin(2 * Math.PI * 3700 * pitch * t)) * env(t, 0.0005, 0.02) * 0.2;
                    break;
                case 5: { // bubble pop: rising sweep
                    double f = (400 + 1600 * t / len) * pitch;
                    ph += 2 * Math.PI * f / SR;
                    v = Math.sin(ph) * env(t, 0.002, 0.03) * 0.7;
                    break;
                }
                case 6: { // water drop: falling sweep
                    double f = (1600 - 900 * Math.min(1, t / 0.05)) * pitch;
                    ph += 2 * Math.PI * f / SR;
                    v = Math.sin(ph) * env(t, 0.001, 0.035) * 0.7;
                    break;
                }
                case 7: // wood
                    v = (Math.sin(2 * Math.PI * 700 * pitch * t) + 0.6 * Math.sin(2 * Math.PI * 1400 * pitch * t)) * env(t, 0.0005, 0.012) * 0.5
                            + noise * env(t, 0.0003, 0.002) * 0.3;
                    break;
                case 8: // glass
                    v = (Math.sin(2 * Math.PI * 3200 * pitch * t) + 0.6 * Math.sin(2 * Math.PI * 4870 * pitch * t) + 0.3 * Math.sin(2 * Math.PI * 6900 * pitch * t))
                            * env(t, 0.0005, 0.06) * 0.3;
                    break;
                case 9: { // laser
                    double f = (2200 - 1800 * Math.min(1, t / len)) * pitch;
                    ph += 2 * Math.PI * f / SR;
                    v = Math.signum(Math.sin(ph)) * env(t, 0.001, 0.03) * 0.25;
                    break;
                }
                case 10: { // piano-ish
                    double f = (k == 0 ? 523.25 : k == 1 ? 329.63 : k == 2 ? 392.0 : 1046.5);
                    v = (Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(4 * Math.PI * f * t) + 0.25 * Math.sin(6 * Math.PI * f * t)) * env(t, 0.003, 0.12) * 0.35;
                    break;
                }
                case 11: { // 8-bit square (enter = arpeggio)
                    double f = 880 * pitch;
                    if (k == 3) f = t < 0.05 ? 660 : t < 0.1 ? 880 : 1320;
                    ph += 2 * Math.PI * f / SR;
                    v = Math.signum(Math.sin(ph)) * (t < len * 0.8 ? 0.22 : 0.22 * (1 - (t - len * 0.8) / (len * 0.2)));
                    break;
                }
                case 12: { // drum
                    double f = (160 - 100 * Math.min(1, t / 0.06)) * pitch;
                    ph += 2 * Math.PI * f / SR;
                    v = Math.sin(ph) * env(t, 0.001, 0.05) * 0.9 + noise * env(t, 0.0003, 0.004) * 0.3;
                    break;
                }
                case 13: { // deep click
                    lp += (noise - lp) * 0.06;
                    v = lp * env(t, 0.0005, 0.008) * 2.2 + Math.sin(2 * Math.PI * 120 * pitch * t) * env(t, 0.001, 0.025) * 0.7;
                    break;
                }
                case 14: // crystal
                    v = (Math.sin(2 * Math.PI * 2093 * pitch * t) + 0.7 * Math.sin(2 * Math.PI * 3136 * pitch * t)) * env(t, 0.001, 0.09) * 0.3;
                    break;
                case 16: { // rain: tiny random drops
                    double f = 2400 * pitch + 600 * Math.sin(t * 90);
                    ph += 2 * Math.PI * f / SR;
                    lp += (noise - lp) * 0.3;
                    v = Math.sin(ph) * env(t, 0.001, 0.012) * 0.5 + lp * env(t, 0.0005, 0.02) * 0.5;
                    break;
                }
                case 17: { // marimba
                    double f = 523.25 * nf;
                    v = (Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(2 * Math.PI * f * 4 * t) * Math.exp(-t / 0.01)) * env(t, 0.001, 0.07) * 0.5;
                    break;
                }
                case 18: { // small bell (inharmonic)
                    double f = 1500 * nf;
                    v = (Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 2.76 * t) + 0.25 * Math.sin(2 * Math.PI * f * 5.4 * t)) * env(t, 0.001, 0.12) * 0.3;
                    break;
                }
                case 19: case 25: { // harp / oud
                    int j = kp % ks.length, j2 = (kp + 1) % ks.length;
                    double out = ks[j];
                    ks[j] = (ks[j] + ks[j2]) * (p == 25 ? 0.4985 : 0.4995);
                    kp++;
                    v = out * (p == 25 ? env(t, 0.001, 0.25) : 1) * 0.6;
                    break;
                }
                case 20: { // paper rustle: high noise
                    double hp = noise - lp; lp += (noise - lp) * 0.5;
                    v = hp * env(t, 0.003, 0.02 * (k == 2 ? 2 : 1)) * 0.6;
                    break;
                }
                case 21: { // finger tap
                    lp += (noise - lp) * 0.08;
                    v = lp * env(t, 0.0005, 0.004) * 2 + Math.sin(2 * Math.PI * 400 * pitch * t) * env(t, 0.0005, 0.008) * 0.3;
                    break;
                }
                case 22: { // robot: FM square
                    double f = 300 * pitch * (1 + 0.3 * Math.sin(2 * Math.PI * 40 * t));
                    ph += 2 * Math.PI * f / SR;
                    v = Math.signum(Math.sin(ph)) * env(t, 0.002, 0.03) * 0.2;
                    break;
                }
                case 23: { // magic sparkle: rising + shimmer
                    double f = (900 + 2500 * t / len) * nf;
                    ph += 2 * Math.PI * f / SR;
                    v = (Math.sin(ph) + 0.5 * Math.sin(2 * Math.PI * 4186 * t) * (Math.sin(t * 160) > 0 ? 1 : 0.2)) * env(t, 0.004, 0.06) * 0.3;
                    break;
                }
                case 24: { // synthwave: filtered saw
                    double f = 220 * nf;
                    ph += f / SR;
                    double saw = 2 * (ph - Math.floor(ph)) - 1;
                    double cut = 0.05 + 0.5 * Math.exp(-t / 0.03);
                    lp += (saw - lp) * cut;
                    v = lp * env(t, 0.002, 0.08) * 0.6;
                    break;
                }
                case 26: { // space: vibrato sine with echo
                    double f = 880 * nf * (1 + 0.02 * Math.sin(2 * Math.PI * 7 * t));
                    ph += 2 * Math.PI * f / SR;
                    double e1 = env(t, 0.002, 0.03), te = t - 0.12;
                    double e2 = te > 0 ? env(te, 0.002, 0.04) * 0.4 : 0;
                    v = Math.sin(ph) * (e1 + e2) * 0.5;
                    break;
                }
                case 27: // clock tick
                    v = Math.sin(2 * Math.PI * (k == 0 ? 4200 : 3000 * nf) * t) * env(t, 0.0002, 0.003) * 0.7 + noise * env(t, 0.0002, 0.0015) * 0.3;
                    break;
                default: { // neon pulse: detuned saw-ish
                    double f = 660 * pitch;
                    ph += 2 * Math.PI * f / SR;
                    ph2 += 2 * Math.PI * f * 1.01 / SR;
                    v = (Math.sin(ph) + Math.sin(ph2) + 0.4 * Math.sin(3 * ph)) * env(t, 0.002, 0.03) * 0.22;
                    break;
                }
            }
            s[i] = v;
        }
        double peak = 1e-6;
        for (double v : s) peak = Math.max(peak, Math.abs(v));
        short[] out = new short[n];
        double g = 0.85 / peak;
        int fade = Math.min(n, SR / 200);
        for (int i = 0; i < n; i++) {
            double v = s[i] * g;
            if (i > n - fade) v *= (n - i) / (double) fade;
            out[i] = (short) Math.max(-32767, Math.min(32767, v * 32767));
        }
        return out;
    }

    private static void writeWav(File f, short[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        byte[] b = new byte[44 + dataLen];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(dataLen);
        for (short v : pcm) bb.putShort(v);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }
}
