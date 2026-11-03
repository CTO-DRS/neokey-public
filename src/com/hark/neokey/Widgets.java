package com.hark.neokey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small vector-like icons and the mini keyboard theme preview used by the app screen. */
public class Widgets {

    /** Mini keyboard drawn with a theme's colors. */
    public static class ThemePreview extends View {
        private Theme theme;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dp;
        private static final String[][] ROWS = {
                {"ض", "ص", "ث", "ق", "ف", "غ", "ع"},
                {"ش", "س", "ي", "ب", "ل", "ا", "ت"},
                {"ذ", "ء", "ؤ", "ر", "ى", "ة", "و"},
        };

        public ThemePreview(Context c, Theme t) {
            super(c);
            theme = t;
            dp = c.getResources().getDisplayMetrics().density;
            p.setTextAlign(Paint.Align.CENTER);
        }

        private android.graphics.Bitmap photo;
        private final long born = android.os.SystemClock.uptimeMillis();

        public void setTheme(Theme t) { theme = t; invalidate(); }

        public void setPhoto(android.graphics.Bitmap b) { photo = b; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            if (theme.bg2 != 0) p.setShader(new android.graphics.LinearGradient(0, 0, w, h, theme.bg, theme.bg2,
                    android.graphics.Shader.TileMode.CLAMP));
            p.setColor(theme.bg);
            c.drawRoundRect(r, 12 * dp, 12 * dp, p);
            p.setShader(null);
            if (theme.anim >= 0) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, 12 * dp, 12 * dp, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                Backdrops.drawAnim(c, theme.anim, (int) w, (int) h, (android.os.SystemClock.uptimeMillis() - born) / 1000f);
                c.restore();
                if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
            }
            if (theme.photo && photo != null) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, 12 * dp, 12 * dp, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                float s = Math.max(w / photo.getWidth(), h / photo.getHeight());
                float dw = photo.getWidth() * s, dh = photo.getHeight() * s;
                c.drawBitmap(photo, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), null);
                p.setColor(0x66000000);
                c.drawRect(r, p);
                c.restore();
            }
            if (theme.neon) {
                p.setColor(Theme.withAlpha(theme.glow, 0x30));
                c.drawCircle(w * 0.2f, h * 0.2f, w * 0.4f, p);
                p.setColor(Theme.withAlpha(theme.glow2, 0x28));
                c.drawCircle(w * 0.85f, h * 0.9f, w * 0.4f, p);
            }
            float pad = 5 * dp, g = 3 * dp;
            float rowH = (h - 2 * pad) / 4f;
            float kw = (w - 2 * pad) / 7f;
            float rad = 3.5f * dp;
            p.setTextSize(rowH * 0.48f);
            p.setTypeface(Theme.face(theme.font, android.graphics.Typeface.NORMAL));
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 7; j++) {
                    float x = pad + j * kw, y = pad + i * rowH;
                    r.set(x + g / 2, y + g / 2, x + kw - g / 2, y + rowH - g / 2);
                    key(c, r, theme.key, rad);
                    p.setColor(theme.text);
                    Paint.FontMetrics fm = p.getFontMetrics();
                    c.drawText(ROWS[i][j], r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, p);
                }
            }
            float y = pad + 3 * rowH;
            r.set(pad + g / 2, y + g / 2, pad + kw * 1.5f - g / 2, y + rowH - g / 2);
            key(c, r, theme.special, rad);
            r.set(pad + kw * 1.5f + g / 2, y + g / 2, pad + kw * 5.3f - g / 2, y + rowH - g / 2);
            key(c, r, theme.space != 0 ? theme.space : theme.key, rad);
            r.set(pad + kw * 5.3f + g / 2, y + g / 2, w - pad - g / 2, y + rowH - g / 2);
            key(c, r, theme.enter, rad);
        }

        private void key(Canvas c, RectF k, int color, float rad) {
            if (theme.glass) {
                p.setColor(0x1F000000);
                c.drawRoundRect(k.left, k.top + 1.2f * dp, k.right, k.bottom + 1.2f * dp, rad, rad, p);
            } else if (!theme.neon && (color >>> 24) == 0xFF) {
                p.setColor(theme.shadow);
                c.drawRoundRect(k.left, k.top + dp, k.right, k.bottom + dp, rad, rad, p);
            }
            p.setColor(color);
            c.drawRoundRect(k, rad, rad, p);
            if (theme.glass) {
                p.setShader(new android.graphics.LinearGradient(0, k.top, 0, k.bottom,
                        new int[]{0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f},
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(k, rad, rad, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp * 0.7f);
                p.setColor(0x66FFFFFF);
                c.drawRoundRect(k, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
            }
            if (theme.border != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(0.6f, theme.borderW * 0.6f) * dp);
                p.setColor(theme.border);
                c.drawRoundRect(k, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
            }
            if (theme.neon) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp * 0.8f);
                p.setColor(Theme.withAlpha(theme.glow, 0x99));
                c.drawRoundRect(k, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Line icons: keyboard, crown, check. */
    public static class Icon extends View {
        public static final int KEYBOARD = 0, CROWN = 1, CHECK = 2, KEYBOARD_DOWN = 3;
        private final int type;
        private int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final float dp;

        public Icon(Context c, int type, int color) {
            super(c);
            this.type = type;
            this.color = color;
            dp = c.getResources().getDisplayMetrics().density;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float s = Math.min(w, h) * 0.32f;
            p.setColor(color);
            switch (type) {
                case CROWN: {
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx - s, cy + s * 0.55f);
                    path.lineTo(cx - s * 1.05f, cy - s * 0.55f);
                    path.lineTo(cx - s * 0.45f, cy - s * 0.05f);
                    path.lineTo(cx, cy - s * 0.8f);
                    path.lineTo(cx + s * 0.45f, cy - s * 0.05f);
                    path.lineTo(cx + s * 1.05f, cy - s * 0.55f);
                    path.lineTo(cx + s, cy + s * 0.55f);
                    path.close();
                    c.drawPath(path, p);
                    c.drawRect(cx - s, cy + s * 0.7f, cx + s, cy + s * 0.95f, p);
                    break;
                }
                case CHECK: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.4f * dp);
                    path.reset();
                    path.moveTo(cx - s * 0.8f, cy);
                    path.lineTo(cx - s * 0.2f, cy + s * 0.6f);
                    path.lineTo(cx + s * 0.85f, cy - s * 0.55f);
                    c.drawPath(path, p);
                    break;
                }
                default: {
                    float top = type == KEYBOARD_DOWN ? cy - s * 0.95f : cy - s * 0.7f;
                    r.set(cx - s * 1.15f, top, cx + s * 1.15f, top + s * 1.4f);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(r, 3 * dp, 3 * dp, p);
                    p.setColor(0xFF2B2F9E);
                    float kw = r.width() / 7f;
                    for (int row = 0; row < 2; row++) {
                        for (int i = 0; i < 6; i++) {
                            float x = r.left + kw * 0.55f + i * kw * 1.05f;
                            float y = r.top + r.height() * (0.22f + row * 0.28f);
                            c.drawRect(x, y, x + kw * 0.6f, y + r.height() * 0.15f, p);
                        }
                    }
                    c.drawRect(r.left + kw * 1.6f, r.top + r.height() * 0.78f, r.right - kw * 1.6f,
                            r.top + r.height() * 0.9f, p);
                    if (type == KEYBOARD_DOWN) {
                        p.setColor(color);
                        path.reset();
                        path.moveTo(cx - s * 0.35f, r.bottom + s * 0.25f);
                        path.lineTo(cx + s * 0.35f, r.bottom + s * 0.25f);
                        path.lineTo(cx, r.bottom + s * 0.65f);
                        path.close();
                        c.drawPath(path, p);
                    }
                    break;
                }
            }
        }
    }
}
