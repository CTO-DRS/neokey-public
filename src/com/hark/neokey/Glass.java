package com.hark.neokey;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** v21.1 futuristic glass look for the app screens: dark aurora background, glass cards with a
 *  purple-to-cyan neon rim, and glowing gradient buttons. Pure drawing, no resources. */
final class Glass {
    static final int PURPLE = 0xFF8E5BD0, INDIGO = 0xFF5558C2, CYAN = 0xFF2FA9C0, VIOLET = 0xFF6D3FC9;
    static final int BG = 0xFF05061A, BG2 = 0xFF0D0A2E;

    private Glass() {}

    /** Full-screen background: deep navy gradient with purple, cyan and magenta aurora glows. */
    static Drawable background() { return new Bg(); }

    /** Glass card. {@code lit} gives a brighter, thicker neon rim (selected state). */
    static Drawable panel(float dp, float radiusDp, boolean lit) { return new Panel(dp, radiusDp, lit, false); }

    /** Primary button / selected chip: purple -> indigo -> cyan gradient with a glass sheen. */
    static Drawable neon(float dp, float radiusDp) { return new Panel(dp, radiusDp, true, true); }

    private static final class Bg extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader base, a, b, m;

        @Override protected void onBoundsChange(Rect r) {
            float w = Math.max(1, r.width()), h = Math.max(1, r.height());
            base = new LinearGradient(0, 0, 0, h, BG, BG2, Shader.TileMode.CLAMP);
            a = new RadialGradient(w * 0.85f, h * 0.06f, w * 0.75f, 0x3A7C3AED, 0, Shader.TileMode.CLAMP);
            b = new RadialGradient(w * 0.1f, h * 0.55f, w * 0.7f, 0x2006B6D4, 0, Shader.TileMode.CLAMP);
            m = new RadialGradient(w * 0.6f, h * 0.95f, w * 0.7f, 0x16C026D3, 0, Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            if (base == null) onBoundsChange(r);
            for (Shader s : new Shader[]{base, a, b, m}) { p.setShader(s); c.drawRect(r, p); }
            p.setShader(null);
        }

        @Override public void setAlpha(int al) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private static final class Panel extends Drawable {
        private final float dp, rad;
        private final boolean lit, filled;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private int alpha = 255;

        Panel(float dp, float radiusDp, boolean lit, boolean filled) {
            this.dp = dp; this.rad = radiusDp * dp; this.lit = lit; this.filled = filled;
            stroke.setStyle(Paint.Style.STROKE);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float r = Math.min(rad, Math.min(b.width(), b.height()) / 2f);
            rf.set(b);
            if (filled) {
                fill.setShader(new LinearGradient(rf.left, rf.top, rf.right, rf.bottom,
                        new int[]{PURPLE, INDIGO, CYAN}, null, Shader.TileMode.CLAMP));
                fill.setAlpha(alpha * 0xD8 / 255);
                c.drawRoundRect(rf, r, r, fill);
            } else {
                fill.setShader(null);
                fill.setColor(0xB80B0C24);
                fill.setAlpha(alpha * 0xB8 / 255);
                c.drawRoundRect(rf, r, r, fill);
                fill.setColor(lit ? 0x187C3AED : 0x0CFFFFFF);
                c.drawRoundRect(rf, r, r, fill);
            }
            // top glass sheen
            fill.setShader(new LinearGradient(0, rf.top, 0, rf.top + Math.min(rf.height(), 60 * dp),
                    new int[]{filled ? 0x40FFFFFF : 0x1CFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            fill.setAlpha(alpha);
            c.drawRoundRect(rf, r, r, fill);
            fill.setShader(null);
            // neon rim
            float sw = (lit && !filled ? 1.6f : 1f) * dp;
            rf.inset(sw / 2, sw / 2);
            stroke.setStrokeWidth(sw);
            if (filled) {
                stroke.setShader(null);
                stroke.setColor(0x66FFFFFF);
            } else {
                stroke.setShader(new LinearGradient(rf.left, rf.top, rf.right, rf.bottom,
                        new int[]{PURPLE, INDIGO, CYAN}, null, Shader.TileMode.CLAMP));
            }
            stroke.setAlpha(alpha * (filled ? 0x55 : lit ? 0xB4 : 0x5C) / 255);
            c.drawRoundRect(rf, r, r, stroke);
        }

        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Glass card with a soft coloured aurora wisp across it (2x2 tool grid). */
    static Drawable aurora(float dp, float radiusDp, int color) { return new Aurora(dp, radiusDp, color); }

    /** Glowing filled circle (accent dots, floating orb). Glow is drawn inside the bounds, so leave room. */
    static Drawable orb(int c1, int c2, boolean ring) { return new Orb(c1, c2, ring); }

    private static final class Aurora extends Drawable {
        private final Panel base;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final float rad;
        Aurora(float dp, float radiusDp, int color) { base = new Panel(dp, radiusDp, false, false); this.color = color; rad = radiusDp * dp; }

        @Override protected void onBoundsChange(Rect r) { base.setBounds(r); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            base.draw(c);
            int save = c.save();
            path.reset();
            path.addRoundRect(new RectF(b), rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(path);
            float w = b.width(), h = b.height();
            // three overlapping soft blobs along a wave = a light ribbon
            float[][] pts = {{0.18f, 0.62f, 0.42f}, {0.42f, 0.48f, 0.36f}, {0.62f, 0.3f, 0.3f}};
            for (float[] q : pts) {
                p.setShader(new RadialGradient(b.left + w * q[0], b.top + h * q[1], w * q[2],
                        new int[]{(color & 0x00FFFFFF) | 0x5C000000, (color & 0x00FFFFFF) | 0x18000000, 0}, new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(b, p);
            }
            p.setShader(null);
            c.restoreToCount(save);
        }

        @Override public void setAlpha(int a) { base.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Orb extends Drawable {
        private final int c1, c2;
        private final boolean ring;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Orb(int c1, int c2, boolean ring) { this.c1 = c1; this.c2 = c2; this.ring = ring; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), R = Math.min(b.width(), b.height()) / 2f, r = R * 0.78f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, R, new int[]{(c1 & 0x00FFFFFF) | 0x4C000000, (c1 & 0x00FFFFFF) | 0x1A000000, 0},
                    new float[]{0.6f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, p);
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, c1, c2, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setColor(0x2E000000); // mute the fill a touch
            c.drawCircle(cx, cy, r, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, r * 0.05f));
            p.setColor(ring ? 0xFFFFFFFF : 0x66FFFFFF);
            c.drawCircle(cx, cy, r - p.getStrokeWidth() / 2, p);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
