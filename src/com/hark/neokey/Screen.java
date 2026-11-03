package com.hark.neokey;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared dark, RTL, scrolling screen scaffold for the writer and performance screens. */
abstract class Screen extends Activity {
    static final int BG = Glass.BG, CARD = 0xFF14171F, LINE = 0x40A78BFA, BLUE = Glass.VIOLET, MUTED = 0xFF8A90A0;
    float dp;
    LinearLayout root;
    ScrollView scroll;

    interface IntPick { void pick(int v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Perf.installCrashHandler(this);
        dp = getResources().getDisplayMetrics().density;
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Glass.background());
        frame.setLayoutDirection(L.dir());
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(L.dir());
        int maxW = (int) Math.min(getResources().getDisplayMetrics().widthPixels, 640 * dp);
        center.addView(root, new FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        final int pad = (int) (18 * dp);
        frame.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
                left = in.getSystemWindowInsetLeft(); right = in.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, 0);
            root.setPadding(pad, pad, pad, bottom + (int) (40 * dp));
            return in;
        });
    }

    /** Header with a back button, an icon and a title. */
    void header(String title, String sub, String icon) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("", 14, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(back, "arrow_forward", Color.WHITE, (int) (22 * dp), (int) (42 * dp));
        back.setBackground(round(CARD, 21, LINE));
        back.setContentDescription(L.t("رجوع"));
        back.setOnClickListener(v -> finish());
        h.addView(back, new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        TextView t = text(title, 20, Typeface.BOLD, Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        col.addView(t);
        if (sub != null) col.addView(text(sub, 12, Typeface.NORMAL, MUTED));
        h.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(Icons.tinted(this, icon, 0xFF93B4FF, (int) (26 * dp)));
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.addView(ic, new LinearLayout.LayoutParams((int) (26 * dp), (int) (26 * dp)));
        root.addView(h);
    }

    void section(String name) {
        TextView t = text(name, 15, Typeface.BOLD, 0xFFDDE1EA);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        t.setPadding((int) (4 * dp), (int) (22 * dp), (int) (4 * dp), (int) (10 * dp));
        root.addView(t);
    }

    TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(L.t(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setTextColor(color);
        return t;
    }

    android.graphics.drawable.Drawable round(int color, float r, int stroke) { return round(color, r, stroke, 1); }

    android.graphics.drawable.Drawable round(int color, float r, int stroke, int sw) {
        if (color == CARD) return Glass.panel(dp, r, stroke == BLUE || sw > 1);
        if (color == BLUE) return Glass.neon(dp, r);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        if (stroke != 0) g.setStroke((int) (sw * dp), stroke);
        return g;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        c.setPadding(p, (int) (12 * dp), p, (int) (12 * dp));
        c.setBackground(round(CARD, 20, LINE));
        return c;
    }

    TextView button(String label, String icon, boolean primary, View.OnClickListener l) {
        TextView b = text(label, 14, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight((int) (48 * dp));
        b.setPadding((int) (10 * dp), (int) (12 * dp), (int) (10 * dp), (int) (12 * dp));
        if (icon != null) Icons.withIcon(b, icon, Color.WHITE, (int) (18 * dp), false, (int) (6 * dp));
        b.setBackground(primary ? round(BLUE, 16, 0) : round(CARD, 16, LINE));
        b.setOnClickListener(l);
        return b;
    }

    EditText edit(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(L.t(hint));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF6B7280);
        e.setTextSize(15);
        e.setGravity(Gravity.TOP | Gravity.START);
        if (!multi) e.setSingleLine(true); else e.setMinLines(3);
        int p = (int) (14 * dp);
        e.setPadding(p, (int) (11 * dp), p, (int) (11 * dp));
        e.setBackground(round(CARD, 14, LINE));
        e.setContentDescription(hint);
        return e;
    }

    /** Selectable chips; the chosen one also carries a check icon so state does not rely on colour. */
    View chips(String[] names, int sel, final IntPick on) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 13, Typeface.BOLD, i == sel ? Color.WHITE : 0xFFB9BECA);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (40 * dp));
            t.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            t.setBackground(i == sel ? round(BLUE, 18, 0) : round(CARD, 18, LINE));
            if (i == sel) Icons.withIcon(t, "check", Color.WHITE, (int) (15 * dp), false, (int) (4 * dp));
            t.setContentDescription(names[i] + (i == sel ? "، محدد" : ""));
            t.setSelected(i == sel);
            t.setOnClickListener(v -> on.pick(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            row.addView(t, lp);
        }
        hs.addView(row);
        return hs;
    }

    LinearLayout.LayoutParams top(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (m * dp);
        return lp;
    }
}
