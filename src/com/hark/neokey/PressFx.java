package com.hark.neokey;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Random;

/** Animated effects drawn where a key is pressed. Pure drawing, no allocations of views. */
final class PressFx {
    static final String[] NAMES = {
            "تموّج", "شرارات", "قلوب", "نجوم", "فقاعات", "جمر",
            "توهّج نيون", "قصاصات ملوّنة", "حرف طائر", "برق", "حلقات", "زهور",
            "دوّامة", "ثلج", "وجوه ضاحكة", "معادل صوت", "مربع نابض", "ألعاب نارية",
            "قطرات مطر", "شهاب", "حلقة قوس قزح", "أوراق شجر", "هالة ذهبية", "أسهم نيون",
    };
    static int count() { return NAMES.length; }

    static final long DUR = 480;

    private static final class Fx { float x, y, size; long t0; int type, seed, col; String label; }

    private final ArrayList<Fx> live = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int seq;

    void spawn(int type, float x, float y, float size, int col, String label, long now) {
        if (type < 0) return;
        if (live.size() >= 14) live.remove(0);
        Fx f = new Fx();
        f.x = x; f.y = y; f.size = size; f.t0 = now; f.type = type; f.seed = ++seq; f.col = col; f.label = label;
        live.add(f);
    }

    boolean busy() { return !live.isEmpty(); }

    /** Draws all live effects; returns true while any is still running. */
    boolean draw(Canvas c, long now, float dp) {
        for (int i = live.size() - 1; i >= 0; i--) {
            Fx f = live.get(i);
            float k = (now - f.t0) / (float) DUR;
            if (k >= 1f) { live.remove(i); continue; }
            drawOne(c, f, Math.max(0, k), dp);
        }
        p.setShader(null);
        return !live.isEmpty();
    }

    private static int a(int col, float al) { return ((int) (Math.max(0, Math.min(1, al)) * 255) << 24) | (col & 0xFFFFFF); }

    private void drawOne(Canvas c, Fx f, float k, float dp) {
        float e = 1 - (1 - k) * (1 - k), fade = 1 - k, S = f.size;
        Random r = new Random(f.seed);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        switch (f.type) {
            case 0: // ripple
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * dp * fade + 0.5f);
                p.setColor(a(f.col, 0.8f * fade));
                c.drawCircle(f.x, f.y, S * (0.3f + 1.2f * e), p);
                break;
            case 1: // sparks
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * dp);
                for (int i = 0; i < 10; i++) {
                    double an = r.nextDouble() * Math.PI * 2;
                    float d0 = S * 0.3f + S * 1.3f * e, d1 = d0 + S * 0.25f * fade;
                    p.setColor(a(i % 2 == 0 ? 0xFDE047 : f.col, fade));
                    c.drawLine(f.x + (float) Math.cos(an) * d0, f.y + (float) Math.sin(an) * d0,
                            f.x + (float) Math.cos(an) * d1, f.y + (float) Math.sin(an) * d1, p);
                }
                break;
            case 2: // hearts float up
                for (int i = 0; i < 3; i++) {
                    float hx = f.x + (r.nextFloat() - 0.5f) * S * 1.4f, hy = f.y - S * 1.6f * e * (0.7f + r.nextFloat() * 0.5f);
                    heart(c, hx, hy, S * 0.32f * (0.7f + 0.5f * e), a(i == 1 ? 0xF472B6 : 0xEF4444, fade));
                }
                break;
            case 3: // stars burst
                for (int i = 0; i < 6; i++) {
                    double an = i * Math.PI / 3 + r.nextDouble() * 0.5;
                    float d = S * 1.3f * e;
                    star(c, f.x + (float) Math.cos(an) * d, f.y + (float) Math.sin(an) * d, S * 0.22f * fade + 1, a(0xFDE047, fade));
                }
                break;
            case 4: // bubbles rise
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * dp);
                for (int i = 0; i < 5; i++) {
                    float bx = f.x + (r.nextFloat() - 0.5f) * S * 1.6f, by = f.y - S * 2f * e * (0.5f + r.nextFloat());
                    p.setColor(a(0x7DD3FC, fade));
                    c.drawCircle(bx, by, S * (0.1f + r.nextFloat() * 0.15f), p);
                }
                break;
            case 5: // embers
                for (int i = 0; i < 9; i++) {
                    float bx = f.x + (r.nextFloat() - 0.5f) * S * 1.4f + (float) Math.sin(k * 8 + i) * S * 0.1f;
                    float by = f.y - S * 2f * e * (0.4f + r.nextFloat());
                    p.setColor(a(i % 3 == 0 ? 0xFDE047 : 0xF97316, fade));
                    c.drawCircle(bx, by, S * 0.07f * (1 + fade), p);
                }
                break;
            case 6: { // neon glow burst
                float rr = S * (0.5f + 1.3f * e);
                p.setShader(new RadialGradient(f.x, f.y, rr, a(f.col, 0.7f * fade), 0, Shader.TileMode.CLAMP));
                c.drawCircle(f.x, f.y, rr, p);
                p.setShader(null);
                break;
            }
            case 7: { // confetti
                int[] cs = {0xF472B6, 0x22D3EE, 0xFDE047, 0xA3E635, 0xA78BFA};
                for (int i = 0; i < 12; i++) {
                    double an = r.nextDouble() * Math.PI * 2;
                    float d = S * 1.5f * e;
                    float cx = f.x + (float) Math.cos(an) * d, cy = f.y + (float) Math.sin(an) * d + S * 1.2f * k * k;
                    c.save();
                    c.rotate(k * 540 + i * 30, cx, cy);
                    p.setColor(a(cs[i % cs.length], fade));
                    c.drawRect(cx - S * 0.08f, cy - S * 0.04f, cx + S * 0.08f, cy + S * 0.04f, p);
                    c.restore();
                }
                break;
            }
            case 8: // flying letter
                if (f.label != null && !f.label.isEmpty()) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTextSize(S * (0.9f + 0.8f * e));
                    p.setColor(a(f.col, fade));
                    c.drawText(f.label, f.x, f.y - S * 2.2f * e, p);
                }
                break;
            case 9: { // lightning
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * dp);
                p.setStrokeJoin(Paint.Join.ROUND);
                for (int b = 0; b < 3; b++) {
                    path.reset();
                    path.moveTo(f.x, f.y);
                    double an = -Math.PI / 2 + (b - 1) * 0.8;
                    float px = f.x, py = f.y;
                    for (int s = 0; s < 4; s++) {
                        px += (float) Math.cos(an) * S * 0.4f + (r.nextFloat() - 0.5f) * S * 0.3f;
                        py += (float) Math.sin(an) * S * 0.4f + (r.nextFloat() - 0.5f) * S * 0.3f;
                        path.lineTo(px, py);
                    }
                    p.setColor(a(0xBFDBFE, fade * (k < 0.15f ? 1 : 0.7f)));
                    c.drawPath(path, p);
                }
                break;
            }
            case 10: // double rings
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * dp);
                p.setColor(a(f.col, fade));
                c.drawCircle(f.x, f.y, S * (0.2f + 1.1f * e), p);
                p.setColor(a(0x22D3EE, fade * 0.8f));
                c.drawCircle(f.x, f.y, S * (0.1f + 0.7f * e), p);
                break;
            case 12: // swirl
                for (int i = 0; i < 10; i++) {
                    double an = i * Math.PI / 5 + k * 6;
                    float d = S * (0.2f + 1.2f * e) * (0.6f + 0.4f * (i % 2));
                    p.setColor(a(i % 2 == 0 ? f.col : 0x22D3EE, fade));
                    c.drawCircle(f.x + (float) Math.cos(an) * d, f.y + (float) Math.sin(an) * d, S * 0.06f + 1, p);
                }
                break;
            case 13: // snowflakes
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f * dp);
                for (int i = 0; i < 5; i++) {
                    float sx = f.x + (r.nextFloat() - 0.5f) * S * 1.8f + (float) Math.sin(k * 6 + i) * S * 0.15f;
                    float sy = f.y - S * 0.8f + S * 1.6f * e * (0.5f + r.nextFloat() * 0.6f), sr = S * 0.13f;
                    p.setColor(a(0xE0F2FE, fade));
                    for (int j = 0; j < 3; j++) {
                        double an = j * Math.PI / 3 + k * 2;
                        float dx = (float) Math.cos(an) * sr, dy = (float) Math.sin(an) * sr;
                        c.drawLine(sx - dx, sy - dy, sx + dx, sy + dy, p);
                    }
                }
                break;
            case 14: // laughing faces
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(a(0xFFFFFF, fade));
                p.setTextSize(S * 0.5f);
                for (int i = 0; i < 2; i++)
                    c.drawText(i == 0 ? "\uD83D\uDE02" : "\uD83E\uDD23", f.x + (i == 0 ? -1 : 1) * S * 0.4f * e, f.y - S * 1.8f * e * (0.8f + 0.3f * i), p);
                break;
            case 15: { // equalizer bars above the key
                float bw = S * 0.12f;
                for (int i = 0; i < 5; i++) {
                    float h = S * (0.3f + 0.9f * (float) Math.abs(Math.sin(k * 9 + i * 1.3f))) * fade;
                    float bx = f.x + (i - 2) * bw * 1.6f;
                    p.setColor(a(i % 2 == 0 ? f.col : 0x22D3EE, fade));
                    c.drawRoundRect(new android.graphics.RectF(bx - bw / 2, f.y - S * 0.6f - h, bx + bw / 2, f.y - S * 0.6f), bw / 2, bw / 2, p);
                }
                break;
            }
            case 16: { // pulsing rounded square
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * dp * fade + 0.5f);
                p.setColor(a(f.col, fade));
                float h = S * (0.5f + 0.9f * e);
                c.drawRoundRect(new android.graphics.RectF(f.x - h, f.y - h, f.x + h, f.y + h), h * 0.3f, h * 0.3f, p);
                break;
            }
            case 17: { // fireworks: rise then burst
                int[] cs = {0xF472B6, 0xFDE047, 0x22D3EE, 0xA3E635};
                if (k < 0.3f) {
                    p.setColor(a(0xFDE047, 1));
                    c.drawCircle(f.x, f.y - S * 1.5f * (k / 0.3f), S * 0.06f + 1, p);
                } else {
                    float kk = (k - 0.3f) / 0.7f, ee = 1 - (1 - kk) * (1 - kk);
                    float cy = f.y - S * 1.5f;
                    for (int i = 0; i < 14; i++) {
                        double an = i * Math.PI * 2 / 14;
                        float d = S * 1.1f * ee;
                        p.setColor(a(cs[i % cs.length], 1 - kk));
                        c.drawCircle(f.x + (float) Math.cos(an) * d, cy + (float) Math.sin(an) * d + S * 0.5f * kk * kk, S * 0.05f + 1, p);
                    }
                }
                break;
            }
            case 18: // rain drops falling
                for (int i = 0; i < 6; i++) {
                    float dx = f.x + (r.nextFloat() - 0.5f) * S * 1.4f, dy = f.y + S * 1.8f * k * k * (0.6f + r.nextFloat());
                    float dr = S * 0.08f;
                    p.setColor(a(0x60A5FA, fade));
                    path.reset();
                    path.moveTo(dx, dy - dr * 2.4f);
                    path.quadTo(dx + dr * 1.2f, dy, dx, dy + dr);
                    path.quadTo(dx - dr * 1.2f, dy, dx, dy - dr * 2.4f);
                    c.drawPath(path, p);
                }
                break;
            case 19: { // shooting star
                float hx = f.x + S * 2.2f * e, hy = f.y - S * 1.6f * e;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(2.4f * dp);
                p.setShader(new android.graphics.LinearGradient(f.x, f.y, hx, hy, 0, a(0xFFFFFF, fade), Shader.TileMode.CLAMP));
                c.drawLine(f.x + (hx - f.x) * 0.3f, f.y + (hy - f.y) * 0.3f, hx, hy, p);
                p.setShader(null);
                star(c, hx, hy, S * 0.18f, a(0xFDE047, fade));
                break;
            }
            case 20: { // rainbow ring
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f * dp * fade + 0.5f);
                p.setShader(new android.graphics.SweepGradient(f.x, f.y, new int[]{a(0xEF4444, fade), a(0xFDE047, fade), a(0x22C55E, fade),
                        a(0x3B82F6, fade), a(0xA855F7, fade), a(0xEF4444, fade)}, null));
                c.save();
                c.rotate(k * 360, f.x, f.y);
                c.drawCircle(f.x, f.y, S * (0.3f + 1.1f * e), p);
                c.restore();
                p.setShader(null);
                break;
            }
            case 21: // leaves
                for (int i = 0; i < 5; i++) {
                    float lx = f.x + (r.nextFloat() - 0.5f) * S * 1.8f + (float) Math.sin(k * 7 + i) * S * 0.25f;
                    float ly = f.y - S * 0.5f + S * 1.4f * e * (0.4f + r.nextFloat());
                    c.save();
                    c.rotate(k * 300 + i * 70, lx, ly);
                    p.setColor(a(i % 2 == 0 ? 0x84CC16 : 0xF59E0B, fade));
                    c.drawOval(new android.graphics.RectF(lx - S * 0.14f, ly - S * 0.06f, lx + S * 0.14f, ly + S * 0.06f), p);
                    c.restore();
                }
                break;
            case 22: { // golden halo
                float rr = S * (0.6f + 0.7f * e);
                p.setShader(new RadialGradient(f.x, f.y, rr, new int[]{a(0xFDE68A, 0), a(0xFBBF24, 0.6f * fade), 0}, new float[]{0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(f.x, f.y, rr, p);
                p.setShader(null);
                break;
            }
            case 23: // neon arrows in four directions
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2f * dp);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(a(f.col, fade));
                for (int i = 0; i < 4; i++) {
                    c.save();
                    c.rotate(i * 90 + 45, f.x, f.y);
                    float d = S * (0.3f + 1.2f * e), w = S * 0.15f;
                    c.drawLine(f.x + d - w, f.y - w, f.x + d, f.y, p);
                    c.drawLine(f.x + d - w, f.y + w, f.x + d, f.y, p);
                    c.drawLine(f.x + d - w * 2.2f, f.y, f.x + d, f.y, p);
                    c.restore();
                }
                break;
            default: // flowers
                for (int i = 0; i < 4; i++) {
                    float fx = f.x + (r.nextFloat() - 0.5f) * S * 1.8f * e, fy = f.y - S * 1.4f * e * (0.5f + r.nextFloat());
                    float pr = S * 0.12f * (0.6f + e);
                    p.setColor(a(i % 2 == 0 ? 0xF9A8D4 : 0xFDE68A, fade));
                    for (int j = 0; j < 5; j++) {
                        double an = j * Math.PI * 2 / 5 + k * 3;
                        c.drawCircle(fx + (float) Math.cos(an) * pr, fy + (float) Math.sin(an) * pr, pr * 0.7f, p);
                    }
                    p.setColor(a(0xF59E0B, fade));
                    c.drawCircle(fx, fy, pr * 0.5f, p);
                }
                break;
        }
    }

    private void heart(Canvas c, float x, float y, float s, int col) {
        p.setColor(col);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x, y + s * 0.9f);
        path.cubicTo(x - s * 1.4f, y - s * 0.1f, x - s * 0.6f, y - s * 1.1f, x, y - s * 0.35f);
        path.cubicTo(x + s * 0.6f, y - s * 1.1f, x + s * 1.4f, y - s * 0.1f, x, y + s * 0.9f);
        c.drawPath(path, p);
    }

    private void star(Canvas c, float x, float y, float s, int col) {
        p.setColor(col);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        for (int i = 0; i < 10; i++) {
            double an = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? s : s * 0.45f;
            float px = x + (float) Math.cos(an) * rr, py = y + (float) Math.sin(an) * rr;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, p);
    }

    /** App preview: a small view that keeps tapping itself with one effect. */
    static final class Demo extends android.view.View {
        private final int type;
        private final PressFx fx = new PressFx();
        private long last;
        private final float dp;
        Demo(android.content.Context c, int type) { super(c); this.type = type; dp = c.getResources().getDisplayMetrics().density; }
        @Override protected void onDraw(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - last > 750) {
                last = now;
                Random r = new Random(now);
                int ty = type >= 0 ? type : (int) ((now / 1500) % count());
                fx.spawn(ty, getWidth() * (0.3f + 0.4f * r.nextFloat()), getHeight() * (0.4f + 0.3f * r.nextFloat()),
                        Math.min(getWidth(), getHeight()) * 0.18f, 0xA855F7, "ن", now);
            }
            fx.draw(c, now, dp);
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(33);
        }
    }
}
