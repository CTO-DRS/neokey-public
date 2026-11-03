package com.hark.neokey;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/** Built-in keyboard backgrounds, drawn in code (no image assets, so the APK stays small). */
final class Backdrops {
    private Backdrops() {}

    static final String[] NAMES = {
            "شفق بنفسجي", "شفق سماوي", "سديم", "ليل النجوم", "غروب", "محيط",
            "غابة", "وردي ناعم", "ذهبي", "خطوط نيون", "موجات", "فقاعات ضوء",
            "زجاج متدرج", "مثلثات", "نقش إسلامي", "كربون", "كثبان", "حرير",
            "منتصف الليل", "شمس 2036",
    };

    static int count() { return NAMES.length; }

    /** Renders a background at keyboard size for the IME (saved as bg.jpg). */
    static Bitmap render(int idx, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), idx, w, h);
        return b;
    }

    static Drawable preview(int idx) {
        return new Drawable() {
            @Override public void draw(Canvas c) {
                Rect r = getBounds();
                int s = c.save();
                c.translate(r.left, r.top);
                c.clipRect(0, 0, r.width(), r.height());
                Backdrops.draw(c, idx, r.width(), r.height());
                c.restoreToCount(s);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    private static void lin(Canvas c, Paint p, float w, float h, boolean diag, int... cols) {
        p.setShader(new LinearGradient(0, 0, diag ? w : 0, h, cols, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int col) {
        if (r <= 0) return;
        p.setShader(new RadialGradient(x, y, r, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    private static void ribbon(Canvas c, Paint p, float w, float h, float y0, float amp, float thick, int col) {
        Path path = new Path();
        int n = 24;
        for (int i = 0; i <= n; i++) {
            float x = w * i / n, y = y0 + (float) Math.sin(i / (float) n * Math.PI * 2.2) * amp;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        for (int i = n; i >= 0; i--) {
            float x = w * i / n, y = y0 + thick + (float) Math.sin(i / (float) n * Math.PI * 2.2 + 0.6) * amp;
            path.lineTo(x, y);
        }
        path.close();
        p.setShader(new LinearGradient(0, y0 - amp, 0, y0 + thick + amp, new int[]{col & 0x00FFFFFF, col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    static void draw(Canvas c, int idx, int W, int H) {
        float w = W, h = H, m = Math.max(w, h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random rnd = new Random(1000 + idx);
        switch (idx) {
            case 0: // purple aurora
                lin(c, p, w, h, false, 0xFF0B0820, 0xFF1A0F3D, 0xFF0A0A1F);
                ribbon(c, p, w, h, h * 0.30f, h * 0.08f, h * 0.22f, 0x997C3AED);
                ribbon(c, p, w, h, h * 0.45f, h * 0.06f, h * 0.16f, 0x6622D3EE);
                glow(c, p, w * 0.8f, h * 0.1f, m * 0.5f, 0x55A855F7);
                break;
            case 1: // cyan aurora
                lin(c, p, w, h, false, 0xFF031420, 0xFF06283A, 0xFF020A12);
                ribbon(c, p, w, h, h * 0.25f, h * 0.1f, h * 0.2f, 0x9922D3EE);
                ribbon(c, p, w, h, h * 0.5f, h * 0.07f, h * 0.14f, 0x6634D399);
                break;
            case 2: // nebula
                lin(c, p, w, h, true, 0xFF0A0418, 0xFF14072A);
                glow(c, p, w * 0.3f, h * 0.35f, m * 0.45f, 0x88C026D3);
                glow(c, p, w * 0.7f, h * 0.6f, m * 0.5f, 0x776366F1);
                glow(c, p, w * 0.5f, h * 0.2f, m * 0.3f, 0x5522D3EE);
                stars(c, p, rnd, w, h, 90);
                break;
            case 3: // starry night
                lin(c, p, w, h, false, 0xFF020617, 0xFF0B1437, 0xFF1E1B4B);
                stars(c, p, rnd, w, h, 220);
                glow(c, p, w * 0.8f, h * 0.18f, m * 0.08f, 0xCCFDF4D7);
                break;
            case 4: // sunset
                lin(c, p, w, h, false, 0xFF2E1065, 0xFFBE185D, 0xFFF97316, 0xFFFBBF24);
                glow(c, p, w * 0.5f, h * 0.75f, m * 0.25f, 0xAAFFF1C1);
                break;
            case 5: // ocean
                lin(c, p, w, h, false, 0xFF0C4A6E, 0xFF075985, 0xFF082F49);
                for (int i = 0; i < 6; i++) ribbon(c, p, w, h, h * (0.15f + i * 0.14f), h * 0.03f, h * 0.05f, 0x3338BDF8);
                break;
            case 6: // forest
                lin(c, p, w, h, false, 0xFF052E16, 0xFF14532D, 0xFF022C22);
                glow(c, p, w * 0.2f, h * 0.2f, m * 0.5f, 0x5534D399);
                glow(c, p, w * 0.9f, h * 0.9f, m * 0.4f, 0x4484CC16);
                break;
            case 7: // soft pink
                lin(c, p, w, h, true, 0xFFFBCFE8, 0xFFF9A8D4, 0xFFC4B5FD);
                glow(c, p, w * 0.2f, h * 0.3f, m * 0.4f, 0x88FFFFFF);
                break;
            case 8: // gold
                lin(c, p, w, h, true, 0xFF1C1206, 0xFF3D2A0A, 0xFF1C1206);
                for (int i = 0; i < 5; i++) ribbon(c, p, w, h, h * (0.1f + i * 0.2f), h * 0.05f, h * 0.06f, 0x66F5C243);
                break;
            case 9: { // neon grid (synthwave)
                lin(c, p, w, h, false, 0xFF0B0320, 0xFF2A0845, 0xFF0B0320);
                glow(c, p, w * 0.5f, h * 0.42f, m * 0.3f, 0x99F472B6);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, m / 400f));
                p.setColor(0x9922D3EE);
                float hz = h * 0.5f;
                for (int i = 0; i < 12; i++) { float y = hz + (h - hz) * (float) Math.pow(i / 11f, 1.8); c.drawLine(0, y, w, y, p); }
                for (int i = -10; i <= 10; i++) c.drawLine(w / 2 + i * w * 0.04f, hz, w / 2 + i * w * 0.18f, h, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 10: // waves
                lin(c, p, w, h, false, 0xFF1E1B4B, 0xFF312E81);
                for (int i = 0; i < 7; i++) ribbon(c, p, w, h, h * (0.05f + i * 0.14f), h * 0.06f, h * 0.1f, i % 2 == 0 ? 0x558B5CF6 : 0x4438BDF8);
                break;
            case 11: // bokeh
                lin(c, p, w, h, true, 0xFF0F0A24, 0xFF1F1147);
                for (int i = 0; i < 26; i++) {
                    int[] cs = {0x557C3AED, 0x5522D3EE, 0x55F472B6, 0x55FBBF24};
                    glow(c, p, rnd.nextFloat() * w, rnd.nextFloat() * h, m * (0.03f + rnd.nextFloat() * 0.08f), cs[i % 4]);
                }
                break;
            case 12: // glass gradient
                lin(c, p, w, h, true, 0xFF6D28D9, 0xFF4F46E5, 0xFF0891B2);
                p.setColor(0x1FFFFFFF);
                c.drawCircle(w * 0.15f, h * 0.2f, m * 0.25f, p);
                c.drawCircle(w * 0.9f, h * 0.85f, m * 0.3f, p);
                break;
            case 13: // triangles
                lin(c, p, w, h, true, 0xFF111827, 0xFF1F2937);
                float ts = m / 9f;
                for (float y = 0; y < h + ts; y += ts) for (float x = 0; x < w + ts; x += ts) {
                    Path t = new Path();
                    t.moveTo(x, y); t.lineTo(x + ts, y); t.lineTo(x, y + ts); t.close();
                    p.setColor((0x10 + rnd.nextInt(0x30)) << 24 | 0x818CF8);
                    c.drawPath(t, p);
                }
                break;
            case 14: { // islamic star pattern
                lin(c, p, w, h, false, 0xFF042F2E, 0xFF064E3B);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, m / 500f));
                p.setColor(0x66FDE68A);
                float s = m / 7f;
                for (float y = 0; y < h + s; y += s) for (float x = 0; x < w + s; x += s) star8(c, p, x, y, s * 0.42f);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 15: { // carbon
                lin(c, p, w, h, false, 0xFF0A0A0A, 0xFF171717);
                float s = m / 60f;
                p.setColor(0x14FFFFFF);
                for (float y = 0; y < h; y += s * 2) for (float x = ((int) (y / (s * 2)) % 2) * s; x < w; x += s * 2) c.drawRect(x, y, x + s, y + s, p);
                glow(c, p, w * 0.5f, 0, m * 0.6f, 0x22FFFFFF);
                break;
            }
            case 16: // dunes
                lin(c, p, w, h, false, 0xFFFCD34D, 0xFFF59E0B, 0xFFB45309);
                for (int i = 0; i < 4; i++) ribbon(c, p, w, h, h * (0.35f + i * 0.15f), h * 0.05f, h * 0.3f, 0x55783C0B);
                break;
            case 17: // silk
                lin(c, p, w, h, true, 0xFF3B0764, 0xFF581C87);
                for (int i = 0; i < 5; i++) ribbon(c, p, w, h, h * (0.1f + i * 0.17f), h * 0.12f, h * 0.12f, 0x44E9D5FF);
                break;
            case 18: // midnight
                lin(c, p, w, h, false, 0xFF000000, 0xFF0B1020);
                glow(c, p, w * 0.5f, h, m * 0.6f, 0x443B82F6);
                stars(c, p, rnd, w, h, 60);
                break;
            default: // sun 2036
                lin(c, p, w, h, false, 0xFF1E0B3A, 0xFF4C1D95, 0xFF0E7490);
                glow(c, p, w * 0.5f, h * 0.55f, m * 0.35f, 0x88F0ABFC);
                p.setColor(0xFFF472B6);
                p.setShader(new LinearGradient(0, h * 0.35f, 0, h * 0.75f, 0xFFFDE68A, 0xFFDB2777, Shader.TileMode.CLAMP));
                float r = Math.min(w, h) * 0.22f;
                c.drawCircle(w * 0.5f, h * 0.55f, r, p);
                p.setShader(null);
                p.setColor(0xFF1E0B3A);
                for (int i = 0; i < 5; i++) { float y = h * 0.55f + r * (0.1f + i * 0.18f); c.drawRect(0, y, w, y + r * (0.03f + i * 0.02f), p); }
                break;
        }
    }

    private static void stars(Canvas c, Paint p, Random rnd, float w, float h, int n) {
        float m = Math.max(w, h);
        for (int i = 0; i < n; i++) {
            p.setColor(((0x55 + rnd.nextInt(0xAA)) << 24) | 0xFFFFFF);
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, m * (0.0008f + rnd.nextFloat() * 0.0022f), p);
        }
    }

    private static void star8(Canvas c, Paint p, float cx, float cy, float r) {
        Path a = new Path();
        for (int i = 0; i < 16; i++) {
            double ang = Math.PI * i / 8;
            float rr = i % 2 == 0 ? r : r * 0.62f;
            float x = cx + (float) Math.cos(ang) * rr, y = cy + (float) Math.sin(ang) * rr;
            if (i == 0) a.moveTo(x, y); else a.lineTo(x, y);
        }
        a.close();
        c.drawPath(a, p);
    }

    // ------------------------------------------------------------ animated backgrounds (keyboard + app)

    static final String[] ANIM_NAMES = {
            "شفق متحرك", "سديم نابض", "نجوم متلألئة", "موج البحر", "سينث ويف",
            "فقاعات طافية", "RGB حي", "مطر رقمي", "غروب 2036", "حرير متموّج",
            "شهب", "جمر متصاعد", "ثلج", "مجرة دوّارة", "نبض",
            "قوس قزح", "تموّجات", "لافا", "شبكة سيبرانية", "عاصفة وبرق",
            "ألعاب نارية", "يراعات", "معادل صوت", "أوراق الخريف", "كوكبة",
            "نفق دوّار", "إطارات نيون", "ضباب", "كثبان الصحراء", "هلال ونجوم",
    };

    static int animCount() { return ANIM_NAMES.length; }

    private static final Paint AP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path APATH = new Path();

    private static void wave(Canvas c, Paint p, float w, float y0, float amp, float thick, int col, float ph) {
        Path path = APATH;
        path.reset();
        int n = 20;
        for (int i = 0; i <= n; i++) {
            float x = w * i / n, y = y0 + (float) Math.sin(i / (float) n * Math.PI * 2.2 + ph) * amp;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        for (int i = n; i >= 0; i--) {
            float x = w * i / n, y = y0 + thick + (float) Math.sin(i / (float) n * Math.PI * 2.2 + ph * 1.3 + 0.6) * amp;
            path.lineTo(x, y);
        }
        path.close();
        p.setShader(new LinearGradient(0, y0 - amp, 0, y0 + thick + amp, new int[]{col & 0x00FFFFFF, col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    /** Draws animated background {@code idx} at time {@code t} seconds. Cheap enough for ~30 fps. */
    static void drawAnim(Canvas c, int idx, int W, int H, float t) {
        float w = W, h = H, m = Math.max(w, h);
        Paint p = AP;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        switch (idx) {
            case 0: // aurora
                lin(c, p, w, h, false, 0xFF0B0820, 0xFF1A0F3D, 0xFF0A0A1F);
                wave(c, p, w, h * 0.22f, h * 0.10f, h * 0.24f, 0xAA7C3AED, t * 0.9f);
                wave(c, p, w, h * 0.42f, h * 0.08f, h * 0.18f, 0x7722D3EE, -t * 0.7f + 1);
                wave(c, p, w, h * 0.60f, h * 0.06f, h * 0.12f, 0x55F472B6, t * 0.5f + 2);
                break;
            case 1: { // pulsing nebula
                lin(c, p, w, h, true, 0xFF0A0418, 0xFF14072A);
                float a = (float) (0.5 + 0.5 * Math.sin(t * 1.4));
                glow(c, p, w * (0.3f + 0.08f * (float) Math.sin(t * 0.5)), h * 0.4f, m * (0.38f + 0.08f * a), 0x99C026D3);
                glow(c, p, w * (0.7f + 0.08f * (float) Math.cos(t * 0.4)), h * 0.6f, m * (0.45f - 0.06f * a), 0x886366F1);
                glow(c, p, w * 0.5f, h * (0.25f + 0.1f * (float) Math.sin(t * 0.7)), m * 0.25f, 0x6622D3EE);
                twinkle(c, p, w, h, 60, t);
                break;
            }
            case 2: // twinkling stars
                lin(c, p, w, h, false, 0xFF020617, 0xFF0B1437, 0xFF1E1B4B);
                twinkle(c, p, w, h, 140, t);
                glow(c, p, w * 0.82f, h * 0.2f, m * 0.07f, 0xCCFDF4D7);
                break;
            case 3: // ocean waves
                lin(c, p, w, h, false, 0xFF0C4A6E, 0xFF075985, 0xFF082F49);
                for (int i = 0; i < 6; i++) wave(c, p, w, h * (0.1f + i * 0.15f), h * 0.04f, h * 0.06f, 0x4438BDF8, t * (1.2f + i * 0.15f) + i);
                break;
            case 4: { // synthwave grid scrolling
                lin(c, p, w, h, false, 0xFF0B0320, 0xFF2A0845, 0xFF0B0320);
                glow(c, p, w * 0.5f, h * 0.45f, m * (0.28f + 0.03f * (float) Math.sin(t * 2)), 0x99F472B6);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, m / 420f));
                p.setColor(0x9922D3EE);
                float hz = h * 0.5f, off = (t * 0.35f) % 1f;
                for (int i = 0; i < 12; i++) { float k = (i + off) / 12f; float y = hz + (h - hz) * k * k; c.drawLine(0, y, w, y, p); }
                for (int i = -10; i <= 10; i++) c.drawLine(w / 2 + i * w * 0.04f, hz, w / 2 + i * w * 0.18f, h, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 5: { // rising bokeh
                lin(c, p, w, h, true, 0xFF0F0A24, 0xFF1F1147);
                int[] cs = {0x667C3AED, 0x6622D3EE, 0x66F472B6, 0x55FBBF24};
                Random r = new Random(77);
                for (int i = 0; i < 22; i++) {
                    float sp = 0.04f + r.nextFloat() * 0.08f, x0 = r.nextFloat(), y0 = r.nextFloat(), rr = m * (0.025f + r.nextFloat() * 0.06f);
                    float y = ((y0 - t * sp) % 1f + 1f) % 1f;
                    glow(c, p, w * (x0 + 0.03f * (float) Math.sin(t + i)), h * (y * 1.2f - 0.1f), rr, cs[i % 4]);
                }
                break;
            }
            case 6: { // rgb hue flow
                float hu = (t * 30) % 360;
                int a = android.graphics.Color.HSVToColor(new float[]{hu, 0.75f, 0.55f});
                int b = android.graphics.Color.HSVToColor(new float[]{(hu + 120) % 360, 0.75f, 0.45f});
                int d = android.graphics.Color.HSVToColor(new float[]{(hu + 240) % 360, 0.75f, 0.5f});
                lin(c, p, w, h, true, a, b, d);
                p.setColor(0x55000000);
                c.drawRect(0, 0, w, h, p);
                break;
            }
            case 7: { // digital rain
                c.drawColor(0xFF000A04);
                int cols = 26;
                float cw = w / cols, cell = cw * 0.9f;
                Random r = new Random(5);
                for (int i = 0; i < cols; i++) {
                    float sp = 0.25f + r.nextFloat() * 0.5f, ph = r.nextFloat();
                    float head = (((t * sp + ph) % 1.3f)) * h;
                    int len = 6 + r.nextInt(10);
                    for (int j = 0; j < len; j++) {
                        float y = head - j * cell * 1.1f;
                        if (y < -cell || y > h) continue;
                        int al = j == 0 ? 0xEE : Math.max(0x18, 0xB0 - j * 0xB0 / len);
                        p.setColor((al << 24) | (j == 0 ? 0xCCFFDD : 0x22FF66));
                        c.drawRect(i * cw + cw * 0.3f, y, i * cw + cw * 0.7f, y + cell * 0.7f, p);
                    }
                }
                break;
            }
            case 8: { // sunset 2036 with moving stripes
                lin(c, p, w, h, false, 0xFF1E0B3A, 0xFF4C1D95, 0xFF0E7490);
                float r0 = Math.min(w, h) * 0.24f, cy = h * 0.55f;
                glow(c, p, w * 0.5f, cy, m * 0.34f, 0x88F0ABFC);
                p.setShader(new LinearGradient(0, cy - r0, 0, cy + r0, 0xFFFDE68A, 0xFFDB2777, Shader.TileMode.CLAMP));
                c.drawCircle(w * 0.5f, cy, r0, p);
                p.setShader(null);
                p.setColor(0xFF1E0B3A);
                float off = (t * 0.25f) % 1f;
                for (int i = 0; i < 6; i++) { float k = (i + off) / 6f; float y = cy + r0 * k; c.drawRect(0, y, w, y + r0 * 0.04f * (1 + 2 * k), p); }
                break;
            }
            case 10: { // meteors
                lin(c, p, w, h, false, 0xFF030712, 0xFF0F172A, 0xFF1E1B4B);
                twinkle(c, p, w, h, 80, t);
                Random r = new Random(31);
                p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 7; i++) {
                    float sp = 0.25f + r.nextFloat() * 0.35f, ph = r.nextFloat(), x0 = r.nextFloat() * 1.4f;
                    float k = ((t * sp + ph) % 1.6f);
                    if (k > 1f) continue;
                    float hx = w * (x0 - k * 0.8f), hy = h * (k * 1.2f - 0.1f), L = m * 0.18f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, m / 320f));
                    p.setShader(new LinearGradient(hx, hy, hx + L * 0.8f, hy - L * 0.6f, 0xEEFFFFFF, 0x0093C5FD, Shader.TileMode.CLAMP));
                    c.drawLine(hx, hy, hx + L * 0.8f, hy - L * 0.6f, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.FILL);
                    glow(c, p, hx, hy, m * 0.02f, 0xCCBFDBFE);
                }
                break;
            }
            case 11: { // rising embers
                lin(c, p, w, h, false, 0xFF0C0A09, 0xFF1C0A05, 0xFF451A03);
                glow(c, p, w * 0.5f, h * 1.05f, m * (0.55f + 0.04f * (float) Math.sin(t * 3)), 0xAAEA580C);
                Random r = new Random(13);
                for (int i = 0; i < 40; i++) {
                    float sp = 0.08f + r.nextFloat() * 0.15f, x0 = r.nextFloat(), y0 = r.nextFloat(), sz = m * (0.003f + r.nextFloat() * 0.006f);
                    float y = ((y0 - t * sp) % 1f + 1f) % 1f;
                    float x = w * (x0 + 0.03f * (float) Math.sin(t * 2 + i));
                    int al = (int) (0xFF * y);
                    p.setColor((al << 24) | (i % 3 == 0 ? 0xFDE047 : 0xFB923C));
                    c.drawCircle(x, h * y, sz, p);
                }
                break;
            }
            case 12: { // snow
                lin(c, p, w, h, false, 0xFF0F172A, 0xFF1E3A5F, 0xFF334155);
                Random r = new Random(21);
                p.setColor(0xDDFFFFFF);
                for (int i = 0; i < 70; i++) {
                    float sp = 0.05f + r.nextFloat() * 0.1f, x0 = r.nextFloat(), y0 = r.nextFloat(), sz = m * (0.002f + r.nextFloat() * 0.005f);
                    float y = ((y0 + t * sp) % 1f);
                    c.drawCircle(w * (x0 + 0.02f * (float) Math.sin(t + i)), h * y, sz, p);
                }
                break;
            }
            case 13: { // rotating galaxy
                c.drawColor(0xFF05030F);
                float cx = w / 2, cy = h / 2;
                glow(c, p, cx, cy, m * 0.3f, 0x887C3AED);
                glow(c, p, cx, cy, m * 0.1f, 0xCCFDE68A);
                Random r = new Random(3);
                for (int i = 0; i < 160; i++) {
                    float d = r.nextFloat(), arm = (i % 2) * 3.1416f;
                    float a = arm + d * 5.5f + t * 0.25f * (1.2f - d) + (r.nextFloat() - 0.5f) * 0.5f;
                    float rr = d * m * 0.5f;
                    p.setColor(((int) (0x50 + 0xA0 * (1 - d)) << 24) | (d < 0.4f ? 0xFDE68A : 0xA5B4FC));
                    c.drawCircle(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr * 0.55f, m * 0.0025f, p);
                }
                break;
            }
            case 14: { // heartbeat line
                lin(c, p, w, h, false, 0xFF020617, 0xFF0B1120);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, m / 700f));
                p.setColor(0x2222D3EE);
                for (int i = 1; i < 10; i++) { c.drawLine(w * i / 10, 0, w * i / 10, h, p); c.drawLine(0, h * i / 10, w, h * i / 10, p); }
                Path path = APATH;
                path.reset();
                float off = (t * 0.3f) % 1f, cy = h * 0.5f;
                for (int i = 0; i <= 120; i++) {
                    float u = i / 120f, ph = (u + off) % 0.5f / 0.5f, y = 0;
                    if (ph > 0.40f && ph < 0.44f) y = -0.35f; else if (ph >= 0.44f && ph < 0.48f) y = 0.25f; else if (ph >= 0.48f && ph < 0.52f) y = -0.08f;
                    float x = w * u;
                    if (i == 0) path.moveTo(x, cy + y * h); else path.lineTo(x, cy + y * h);
                }
                p.setStrokeWidth(Math.max(2.5f, m / 260f));
                p.setColor(0xFF22FF88);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 15: { // moving rainbow bands
                float off = (t * 0.15f) % 1f;
                int[] cs = {0xFFEF4444, 0xFFF59E0B, 0xFFEAB308, 0xFF22C55E, 0xFF06B6D4, 0xFF6366F1, 0xFFA855F7, 0xFFEF4444};
                p.setShader(new LinearGradient(-w * off, 0, w * 2 - w * off, h, cs, null, Shader.TileMode.MIRROR));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                p.setColor(0x66000000);
                c.drawRect(0, 0, w, h, p);
                break;
            }
            case 16: { // expanding ripples
                lin(c, p, w, h, true, 0xFF0B1026, 0xFF111C44);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, m / 300f));
                float[][] cen = {{0.25f, 0.4f}, {0.75f, 0.6f}};
                for (float[] cc : cen) for (int i = 0; i < 4; i++) {
                    float k = ((t * 0.25f + i / 4f) % 1f);
                    p.setColor(((int) (0xCC * (1 - k)) << 24) | 0x67E8F9);
                    c.drawCircle(w * cc[0], h * cc[1], m * 0.5f * k, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 17: { // lava blobs
                lin(c, p, w, h, false, 0xFF1A0505, 0xFF3B0A0A);
                for (int i = 0; i < 6; i++) {
                    float x = w * (0.5f + 0.38f * (float) Math.sin(t * (0.2f + i * 0.05f) + i * 1.7f));
                    float y = h * (0.5f + 0.4f * (float) Math.cos(t * (0.17f + i * 0.04f) + i));
                    glow(c, p, x, y, m * (0.18f + 0.03f * i), i % 2 == 0 ? 0xCCF97316 : 0xCCDC2626);
                }
                break;
            }
            case 18: { // cyber grid with travelling pulse
                c.drawColor(0xFF020617);
                float gs = m / 18f, pulse = (t * 0.4f) % 1.4f;
                for (float y = gs / 2; y < h; y += gs) for (float x = gs / 2; x < w; x += gs) {
                    float d = Math.abs((x / w + y / h) / 2f - pulse + 0.2f);
                    int al = (int) (0x30 + Math.max(0, 0xCF * (1 - d * 6)));
                    p.setColor((Math.min(0xFF, al) << 24) | 0x22D3EE);
                    c.drawCircle(x, y, m * 0.003f, p);
                }
                break;
            }
            case 19: { // storm with lightning
                lin(c, p, w, h, false, 0xFF111827, 0xFF1F2937, 0xFF0F172A);
                float cyc = t % 4f;
                if (cyc < 0.12f || (cyc > 0.22f && cyc < 0.3f)) { p.setColor(0x88E0E7FF); c.drawRect(0, 0, w, h, p); }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, m / 600f));
                p.setColor(0x6693C5FD);
                Random r = new Random(41);
                for (int i = 0; i < 50; i++) {
                    float sp = 0.6f + r.nextFloat() * 0.6f, x0 = r.nextFloat(), y0 = r.nextFloat();
                    float y = ((y0 + t * sp) % 1f) * h, x = x0 * w;
                    c.drawLine(x, y, x - m * 0.01f, y + m * 0.04f, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 20: { // fireworks
                lin(c, p, w, h, false, 0xFF020617, 0xFF0F0A2A);
                int[] cs = {0xFFF472B6, 0xFF22D3EE, 0xFFFDE047, 0xFFA3E635, 0xFFA78BFA};
                for (int b = 0; b < 4; b++) {
                    float per = 2.2f + b * 0.4f, k = ((t + b * 0.7f) % per) / per;
                    Random r = new Random(b * 7 + (int) ((t + b * 0.7f) / per));
                    float cx = w * (0.15f + r.nextFloat() * 0.7f), cy = h * (0.15f + r.nextFloat() * 0.5f), R = m * (0.12f + r.nextFloat() * 0.1f) * k;
                    int col = cs[r.nextInt(cs.length)], al = (int) (0xFF * (1 - k));
                    p.setColor((al << 24) | (col & 0xFFFFFF));
                    for (int i = 0; i < 18; i++) {
                        double a = i * Math.PI * 2 / 18;
                        c.drawCircle(cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R + k * k * m * 0.04f, m * 0.004f, p);
                    }
                }
                break;
            }
            case 21: { // fireflies
                lin(c, p, w, h, false, 0xFF04130B, 0xFF0B2A1A, 0xFF052012);
                Random r = new Random(17);
                for (int i = 0; i < 24; i++) {
                    float x = w * (r.nextFloat() + 0.06f * (float) Math.sin(t * (0.3f + r.nextFloat() * 0.4f) + i));
                    float y = h * (r.nextFloat() + 0.06f * (float) Math.cos(t * (0.25f + r.nextFloat() * 0.4f) + i * 2));
                    float a = (float) (0.5 + 0.5 * Math.sin(t * (1 + r.nextFloat() * 2) + i));
                    glow(c, p, x, y, m * 0.025f, ((int) (0xCC * a) << 24) | 0xD9F99D);
                }
                break;
            }
            case 22: { // equalizer bars
                lin(c, p, w, h, false, 0xFF0B0320, 0xFF1E0B3A);
                int n = 24;
                float bw = w / n;
                for (int i = 0; i < n; i++) {
                    float v = 0.2f + 0.7f * (float) Math.abs(Math.sin(t * (1.3f + (i % 5) * 0.3f) + i * 0.6f));
                    float bh = h * 0.8f * v;
                    p.setShader(new LinearGradient(0, h, 0, h - bh, 0xCC22D3EE, 0xCCF472B6, Shader.TileMode.CLAMP));
                    c.drawRect(i * bw + bw * 0.18f, h - bh, (i + 1) * bw - bw * 0.18f, h, p);
                }
                p.setShader(null);
                break;
            }
            case 23: { // autumn leaves
                lin(c, p, w, h, false, 0xFF431407, 0xFF7C2D12, 0xFF451A03);
                Random r = new Random(29);
                int[] cs = {0xDDF59E0B, 0xDDEA580C, 0xDDDC2626, 0xDDFBBF24};
                for (int i = 0; i < 26; i++) {
                    float sp = 0.05f + r.nextFloat() * 0.08f, x0 = r.nextFloat(), y0 = r.nextFloat(), sz = m * (0.012f + r.nextFloat() * 0.014f);
                    float y = ((y0 + t * sp) % 1f) * h, x = w * (x0 + 0.05f * (float) Math.sin(t * 1.2f + i));
                    c.save();
                    c.rotate((t * 60 + i * 40) % 360, x, y);
                    p.setColor(cs[i % 4]);
                    c.drawOval(x - sz, y - sz * 0.45f, x + sz, y + sz * 0.45f, p);
                    c.restore();
                }
                break;
            }
            case 24: { // constellation network
                lin(c, p, w, h, true, 0xFF020617, 0xFF0F172A);
                Random r = new Random(55);
                int n = 22;
                float[] xs = new float[n], ys = new float[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = w * (r.nextFloat() + 0.05f * (float) Math.sin(t * (0.2f + r.nextFloat() * 0.3f) + i));
                    ys[i] = h * (r.nextFloat() + 0.05f * (float) Math.cos(t * (0.2f + r.nextFloat() * 0.3f) + i));
                }
                p.setStrokeWidth(Math.max(1f, m / 700f));
                float lim = m * 0.22f;
                for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                    float d = (float) Math.hypot(xs[i] - xs[j], ys[i] - ys[j]);
                    if (d < lim) { p.setColor(((int) (0x90 * (1 - d / lim)) << 24) | 0x93C5FD); c.drawLine(xs[i], ys[i], xs[j], ys[j], p); }
                }
                p.setColor(0xEEFFFFFF);
                for (int i = 0; i < n; i++) c.drawCircle(xs[i], ys[i], m * 0.004f, p);
                break;
            }
            case 25: { // spinning tunnel
                c.drawColor(0xFF05030F);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, m / 260f));
                float cx = w / 2, cy = h / 2;
                for (int i = 0; i < 10; i++) {
                    float k = ((i / 10f + t * 0.15f) % 1f), rr = m * 0.7f * k * k;
                    c.save();
                    c.rotate(t * 20 + i * 9, cx, cy);
                    p.setColor(((int) (0xEE * k) << 24) | (i % 2 == 0 ? 0xA855F7 : 0x22D3EE));
                    c.drawRect(cx - rr, cy - rr * 0.6f, cx + rr, cy + rr * 0.6f, p);
                    c.restore();
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 26: { // pulsing neon frames
                c.drawColor(0xFF0A0A12);
                p.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 4; i++) {
                    float a = (float) (0.5 + 0.5 * Math.sin(t * 2 + i * 1.3));
                    float in = m * (0.04f + i * 0.06f);
                    p.setStrokeWidth(Math.max(2f, m / 220f));
                    p.setColor(((int) (0x40 + 0xBF * a) << 24) | (i % 2 == 0 ? 0xF472B6 : 0x22D3EE));
                    c.drawRoundRect(new android.graphics.RectF(in, in, w - in, h - in), m * 0.04f, m * 0.04f, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case 27: { // drifting fog
                lin(c, p, w, h, false, 0xFF1F2937, 0xFF374151, 0xFF111827);
                for (int i = 0; i < 7; i++) {
                    float x = w * (((i * 0.23f + t * (0.02f + i * 0.006f)) % 1.4f) - 0.2f);
                    glow(c, p, x, h * (0.2f + (i % 4) * 0.22f), m * (0.25f + (i % 3) * 0.06f), 0x44E5E7EB);
                }
                break;
            }
            case 28: { // desert dunes with drifting sun
                lin(c, p, w, h, false, 0xFFFDBA74, 0xFFF97316, 0xFF9A3412);
                float sx = w * (0.3f + 0.4f * (float) ((Math.sin(t * 0.1) + 1) / 2));
                glow(c, p, sx, h * 0.3f, m * 0.25f, 0xAAFEF3C7);
                p.setColor(0xFFFEF3C7);
                c.drawCircle(sx, h * 0.3f, m * 0.06f, p);
                int[] dc = {0xFFC2410C, 0xFF9A3412, 0xFF7C2D12};
                for (int d = 0; d < 3; d++) {
                    Path path = APATH;
                    path.reset();
                    float base = h * (0.55f + d * 0.15f);
                    path.moveTo(0, h);
                    for (int i = 0; i <= 20; i++) {
                        float x = w * i / 20f;
                        path.lineTo(x, base + (float) Math.sin(i * 0.5f + d * 2 + t * 0.15f * (d + 1)) * h * 0.05f);
                    }
                    path.lineTo(w, h);
                    path.close();
                    p.setColor(dc[d]);
                    c.drawPath(path, p);
                }
                break;
            }
            case 29: { // crescent and stars
                lin(c, p, w, h, false, 0xFF020617, 0xFF0C1445, 0xFF1E1B4B);
                twinkle(c, p, w, h, 110, t);
                float cx = w * 0.72f, cy = h * 0.3f, R = m * 0.09f;
                glow(c, p, cx, cy, R * 3f * (1f + 0.05f * (float) Math.sin(t)), 0x55FDE68A);
                p.setColor(0xFFFDE68A);
                c.drawCircle(cx, cy, R, p);
                p.setColor(0xFF071038);
                c.drawCircle(cx + R * 0.45f, cy - R * 0.2f, R * 0.88f, p);
                break;
            }
            default: // silk
                lin(c, p, w, h, true, 0xFF3B0764, 0xFF581C87);
                for (int i = 0; i < 5; i++) wave(c, p, w, h * (0.05f + i * 0.18f), h * 0.12f, h * 0.12f, 0x44E9D5FF, t * 0.6f + i * 0.8f);
                break;
        }
    }

    private static void twinkle(Canvas c, Paint p, float w, float h, int n, float t) {
        Random r = new Random(9);
        float m = Math.max(w, h);
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h, sz = m * (0.0009f + r.nextFloat() * 0.0024f), ph = r.nextFloat() * 6.28f, sp = 1 + r.nextFloat() * 2;
            int al = (int) (0x30 + 0xC0 * (0.5 + 0.5 * Math.sin(t * sp + ph)));
            p.setColor((al << 24) | 0xFFFFFF);
            c.drawCircle(x, y, sz, p);
        }
    }

    /** A view that plays an animated background (app previews). Stops when detached. */
    static final class AnimView extends android.view.View {
        private final int idx;
        private final long start = android.os.SystemClock.uptimeMillis();
        AnimView(android.content.Context c, int idx) { super(c); this.idx = idx; }
        @Override protected void onDraw(Canvas c) {
            float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
            drawAnim(c, idx >= 0 ? idx : (int) ((t / 3.5f) % animCount()), getWidth(), getHeight(), t);
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(40);
        }
    }
}
