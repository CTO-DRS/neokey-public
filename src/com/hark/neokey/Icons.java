package com.hark.neokey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import java.util.HashMap;

/** Vector icon helper (Material Symbols Rounded + Simple Icons brand marks in res/drawable/ic_*.xml). */
final class Icons {
    private static final HashMap<String, Drawable> cache = new HashMap<>();

    static Drawable get(Context c, String name) {
        Drawable d = cache.get(name);
        if (d == null) {
            int id = c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
            if (id == 0) return null;
            try { d = c.getDrawable(id).mutate(); } catch (Throwable t) { return null; }
            cache.put(name, d);
        }
        return d;
    }

    /** Fresh tinted copy for use in views. */
    static Drawable tinted(Context c, String name, int color, int sizePx) {
        int id = c.getResources().getIdentifier("ic_" + name, "drawable", c.getPackageName());
        if (id == 0) return null;
        Drawable d;
        try { d = c.getDrawable(id).mutate(); } catch (Throwable t) { return null; } // a bad icon must never crash a screen
        d.setTint(color);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    static void draw(Canvas cv, Context c, String name, float cx, float cy, float size, int color) {
        Drawable d = get(c, name);
        if (d == null) return;
        d.setTint(color);
        int h = Math.round(size / 2);
        d.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h);
        d.draw(cv);
    }

    /** Turns a TextView into an icon-only button, centred in a box of boxPx (0 = no box). */
    static void iconOnly(TextView t, String name, int color, int sizePx, int boxPx) {
        t.setText("");
        Drawable d = tinted(t.getContext(), name, color, sizePx);
        t.setCompoundDrawablesRelative(null, null, null, null);
        t.setCompoundDrawables(d, null, null, null);
        t.setCompoundDrawablePadding(0);
        if (boxPx > 0) {
            int p = (boxPx - sizePx) / 2;
            t.setPadding(p, 0, 0, 0);
        }
    }

    /** Adds an icon before (start) or after (end) a TextView's text. */
    static void withIcon(TextView t, String name, int color, int sizePx, boolean end, int gapPx) {
        Drawable d = tinted(t.getContext(), name, color, sizePx);
        if (end) t.setCompoundDrawablesRelative(null, null, d, null);
        else t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(gapPx);
    }
}
