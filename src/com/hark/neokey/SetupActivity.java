package com.hark.neokey;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import android.widget.Switch;
import android.widget.TextView;

/** App screen styled like modern keyboard apps: header, banner, tabs, theme grid, try-it bar. */
public class SetupActivity extends Activity {

    private static final int BG = Glass.BG, CARD = 0xFF14171F, LINE = 0x40A78BFA, BLUE = Glass.VIOLET;

    private float dp;
    private LinearLayout root, tabBar, content;
    private int tab = 0;
    private EditText test;
    private int maxW;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Perf.installCrashHandler(this);
        Shield.ensure(this); // integrity shield, once per process (roadmap §11 layer 6)
        dp = getResources().getDisplayMetrics().density;
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Glass.background());
        frame.setLayoutDirection(L.dir());

        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(L.dir());
        int screenW = getResources().getDisplayMetrics().widthPixels;
        maxW = (int) Math.min(screenW, 640 * dp);
        center.addView(root, new FrameLayout.LayoutParams(maxW, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // floating bottom dock navigation (v21.2)
        final LinearLayout bar = buildDock();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(maxW, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        frame.addView(bar, blp);
        setContentView(frame);

        final int pad = (int) (18 * dp);
        root.setPadding(pad, pad, pad, (int) (110 * dp));
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
            bar.setPadding(pad, (int) (46 * dp), pad, bottom + (int) (10 * dp));
            root.setPadding(pad, pad, pad, bottom + (int) (150 * dp));
            return in;
        });

        buildHeader();
        buildBanner();
        buildQuickGrid();
        buildAccents();
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        tab = isReady() ? 0 : 3;
        if (getIntent() != null && getIntent().getBooleanExtra("mic", false)) {
            tab = 3;
            askMic();
        }
        showTab();
        showLastCrash();
        if (!Theme.prefs(this).getBoolean("onboard_done", false)) root.post(() -> showOnboarding(0));
    }

    private static final String[][] ONBOARD = {
            {"keyboard", "أهلاً في نيو كيبورد", "كيبورد عربي وإنجليزي بقاموس ضخم، سمات كثيرة، وكل شي يشتغل على جوالك بدون ما ترسل كلماتك لأي مكان."},
            {"check", "فعّله بخطوتين", "من تبويب «التفعيل»: شغّل نيو كيبورد في إعدادات الجوال، وبعدين اختره ككيبورد أساسي."},
            {"gesture", "اكتب أسرع", "اسحب إصبعك على الحروف لكتابة الكلمة كاملة، واسحب على المسافة لتبديل اللغة، وعلى زر الحذف لمسح كلمات."},
            {"tools", "كل الأدوات في •••", "زر ••• فيه مساعد المراسلات، الترجمة، الحاسبة، الزخرفة، العبارات والأذكار، ووضع اليد الواحدة."},
            {"palette", "خلّه على ذوقك", "اختر سمة جاهزة أو صمّم سمتك من تبويب «الثيمات»، وغيّر الحجم والشكل من «الإعدادات»."},
    };

    /** First-run walkthrough, one step per dialog page. */
    private void showOnboarding(final int step) {
        if (isFinishing()) return;
        String[] s = ONBOARD[step];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (24 * dp);
        box.setPadding(p, p, p, (int) (8 * dp));
        box.setLayoutDirection(L.dir());
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageDrawable(Icons.tinted(this, s[0], Color.WHITE, (int) (34 * dp)));
        ic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        ic.setBackground(round(BLUE, 32, 0));
        box.addView(ic, new LinearLayout.LayoutParams((int) (64 * dp), (int) (64 * dp)));
        TextView t = text(s[1], 19, Typeface.BOLD, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (16 * dp), 0, (int) (8 * dp));
        box.addView(t);
        TextView d = text(s[2], 14, Typeface.NORMAL, 0xFFB9BECA);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(0, 1.2f);
        box.addView(d);
        TextView dots = text((step + 1) + " / " + ONBOARD.length, 12, Typeface.NORMAL, 0xFF8A90A0);
        dots.setPadding(0, (int) (14 * dp), 0, 0);
        box.addView(dots);
        final boolean last = step == ONBOARD.length - 1;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(last ? "ابدأ" : "التالي", (x, w) -> {
                    if (last) { Theme.prefs(this).edit().putBoolean("onboard_done", true).apply(); tab = isReady() ? 0 : 3; showTab(); }
                    else showOnboarding(step + 1);
                })
                .setNegativeButton(last ? null : "تخطي", (x, w) -> Theme.prefs(this).edit().putBoolean("onboard_done", true).apply())
                .create();
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(round(CARD, 24, LINE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) showTab();
        if (bnStage != null) { bnPaused = false; bnSchedule(); }
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f && tab == 3) showTab();
    }

    // ---------------------------------------------------------------- header + banner

    /** Top glass pill: app logo orb, three quick circles, user avatar ring; then the slim try-it bar. */
    private void buildHeader() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Glass.panel(dp, 36, true));
        int pp = (int) (9 * dp);
        pill.setPadding(pp, pp, pp, pp);

        FrameLayout kb = new FrameLayout(this);
        kb.setBackground(Glass.orb(Glass.PURPLE, Glass.CYAN, false));
        Widgets.Icon ki = new Widgets.Icon(this, Widgets.Icon.KEYBOARD, Color.WHITE);
        kb.addView(ki, new FrameLayout.LayoutParams((int) (30 * dp), (int) (30 * dp), Gravity.CENTER));
        kb.setContentDescription(L.t("إعدادات لوحات المفاتيح"));
        kb.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        pill.addView(kb, new LinearLayout.LayoutParams((int) (60 * dp), (int) (60 * dp)));

        String[][] quick = {{"mic", "الكتابة الصوتية"}, {"edit_note", "المساعد"}, {"share", "مشاركة"}};
        for (int i = 0; i < quick.length; i++) {
            final int k = i;
            android.widget.ImageView q = new android.widget.ImageView(this);
            q.setImageDrawable(Icons.tinted(this, quick[i][0], 0xFFD8D4FF, (int) (20 * dp)));
            q.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            q.setBackground(Glass.panel(dp, 23, false));
            q.setContentDescription(L.t(quick[i][1]));
            q.setOnClickListener(v -> {
                if (k == 0) askMic();
                else if (k == 1) startActivity(new Intent(this, WriterActivity.class));
                else {
                    Intent it = new Intent(Intent.ACTION_SEND);
                    it.setType("text/plain");
                    it.putExtra(Intent.EXTRA_TEXT, getString(R.string.nk_setupactivity_buildheader_1));
                    startActivity(Intent.createChooser(it, "مشاركة"));
                }
            });
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams((int) (46 * dp), (int) (46 * dp));
            ql.setMarginStart((int) (8 * dp));
            pill.addView(q, ql);
        }
        pill.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        FrameLayout av = new FrameLayout(this);
        av.setBackground(Glass.orb(Glass.INDIGO, Glass.PURPLE, true));
        Bitmap me = null;
        try { if (avatarFile().exists()) me = BitmapFactory.decodeFile(avatarFile().getAbsolutePath()); } catch (Throwable ignored) {}
        if (me != null) {
            android.widget.ImageView ph = new android.widget.ImageView(this);
            ph.setImageBitmap(circle(me));
            int ps = (int) (60 * dp * 0.78f) - (int) (4 * dp);
            av.addView(ph, new FrameLayout.LayoutParams(ps, ps, Gravity.CENTER));
        } else {
            Widgets.Icon cr = new Widgets.Icon(this, Widgets.Icon.CROWN, 0xFFFFE08A);
            av.addView(cr, new FrameLayout.LayoutParams((int) (28 * dp), (int) (28 * dp), Gravity.CENTER));
        }
        av.setContentDescription(L.t("صورتك الشخصية"));
        av.setOnClickListener(v -> { tab = 1; showTab(); });
        pill.addView(av, new LinearLayout.LayoutParams((int) (60 * dp), (int) (60 * dp)));
        root.addView(pill);

        // slim glass try-it bar
        test = new EditText(this);
        test.setHint(L.t("اكتب هنا للتجربة..."));
        test.setHintTextColor(0xFF8F8AB8);
        test.setTextColor(Color.WHITE);
        test.setTextSize(15);
        test.setSingleLine(false);
        test.setMaxLines(3);
        test.setBackground(Glass.panel(dp, 26, false));
        int tp = (int) (20 * dp);
        test.setPadding(tp, (int) (15 * dp), tp, (int) (15 * dp));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = (int) (14 * dp);
        tl.bottomMargin = (int) (16 * dp);
        root.addView(test, tl);
    }

    /** 2x2 grid of glass tool cards with aurora ribbons and a square icon tile. */
    private void buildQuickGrid() {
        Object[][] items = {
                {"المساعد الذكي", "edit_note", Glass.PURPLE, WriterActivity.class},
                {"القاموس", "book", Glass.CYAN, DictActivity.class},
                {"النسخ الاحتياطي", "download", Glass.INDIGO, BackupActivity.class},
                {"الأداء", "speed", 0xFFF0ABFC, PerfActivity.class},
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 2 == 0) { row = new LinearLayout(this); grid.addView(row, top(i == 0 ? 18 : 12)); }
            final Class<?> cls = (Class<?>) items[i][3];
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(Glass.aurora(dp, 26, (Integer) items[i][2]));
            int cp = (int) (14 * dp);
            card.setPadding(cp + (int) (4 * dp), cp, cp, cp);
            card.setMinimumHeight((int) (84 * dp));
            TextView t = text((String) items[i][0], 14, Typeface.BOLD, Color.WHITE);
            t.setMaxLines(2);
            card.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            android.widget.ImageView ic = new android.widget.ImageView(this);
            ic.setImageDrawable(Icons.tinted(this, (String) items[i][1], Color.WHITE, (int) (22 * dp)));
            ic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ic.setBackground(round(0x33FFFFFF, 14, 0x40FFFFFF));
            card.addView(ic, new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp)));
            card.setContentDescription(L.t((String) items[i][0]));
            card.setOnClickListener(v -> startActivity(new Intent(this, cls)));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 2 == 1) cl.setMarginStart((int) (12 * dp));
            row.addView(card, cl);
        }
        root.addView(grid);
    }

    // accent colours shown as glowing circles: index into Theme.ACCENTS
    private static final int[] ACC_IDX = {1, 6, 7, 2, 4};
    private static final int[][] ACC_COL = {{0xFF4A78D0, 0xFF4644B0}, {0xFF35AFC4, 0xFF1D86AE}, {0xFFD06A9E, 0xFFB0336C},
            {0xFF3FAF88, 0xFF16785E}, {0xFFD9894A, 0xFFB65A1E}};
    private LinearLayout accRow;

    /** Row of five glowing colour circles: the keyboard accent (enter key / highlights). */
    private void buildAccents() {
        TextView h = text("لون الكيبورد", 13, Typeface.BOLD, 0xFFB9B4E0);
        h.setPadding((int) (4 * dp), (int) (20 * dp), (int) (4 * dp), (int) (4 * dp));
        root.addView(h);
        accRow = new LinearLayout(this);
        accRow.setGravity(Gravity.CENTER);
        root.addView(accRow, top(2));
        refreshAccents();
    }

    private void refreshAccents() {
        if (accRow == null) return;
        accRow.removeAllViews();
        int cur = Theme.prefs(this).getInt("accent", 0);
        for (int i = 0; i < ACC_IDX.length; i++) {
            final int a = ACC_IDX[i];
            boolean on = cur == a;
            FrameLayout f = new FrameLayout(this);
            f.setBackground(Glass.orb(ACC_COL[i][0], ACC_COL[i][1], on));
            if (on) {
                android.widget.ImageView ck = new android.widget.ImageView(this);
                ck.setImageDrawable(Icons.tinted(this, "check", Color.WHITE, (int) (22 * dp)));
                ck.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                f.addView(ck, new FrameLayout.LayoutParams(-1, -1));
            }
            f.setContentDescription(L.t("لون") + " " + (i + 1) + (on ? "، محدد" : ""));
            f.setOnClickListener(v -> {
                int now = Theme.prefs(this).getInt("accent", 0);
                Theme.prefs(this).edit().putInt("accent", now == a ? 0 : a).apply();
                refreshAccents();
            });
            accRow.addView(f, new LinearLayout.LayoutParams(0, (int) (66 * dp), 1f));
        }
    }

    // Auto-playing promo carousel (story style): themes, features, how-to, developer.
    private FrameLayout bnStage;
    private final java.util.ArrayList<View> bnSlides = new java.util.ArrayList<>();
    private LinearLayout bnDots;
    private View bnProgress;
    private int bnIdx = 0;
    private boolean bnPaused = false;
    private final android.os.Handler bnH = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long BN_MS = 4500;
    private final Runnable bnNext = () -> bnGo(bnIdx + 1, true);

    private void buildBanner() {
        final float sx0[] = new float[2];
        bnStage = new FrameLayout(this) {
            boolean swiping;
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        sx0[0] = e.getX(); sx0[1] = e.getY(); swiping = false; bnPause(true); break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - sx0[0], dy = e.getY() - sx0[1];
                        if (bnIdx != 0 && Math.abs(dx) > 24 * dp && Math.abs(dx) > Math.abs(dy) * 1.4f) { swiping = true; return true; }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        bnPause(false); break;
                }
                return false;
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) { sx0[0] = e.getX(); bnPause(true); return true; }
                if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    float dx = e.getX() - sx0[0];
                    bnPause(false);
                    if (Math.abs(dx) > 40 * dp) bnGo(dx > 0 ? bnIdx + 1 : bnIdx - 1, true); // RTL: swipe right = next
                    else bnGo(bnIdx + 1, true);
                    swiping = false;
                }
                return true;
            }
        };
        int H = (int) (196 * dp);
        bnStage.setClipChildren(true);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(30 * dp);
        bnStage.setBackground(clip);
        bnStage.setClipToOutline(true);

        // slide 0: themes
        LinearLayout s0 = slideBase(new int[]{0xFF6E3BAE, 0xFF4B4DAE, 0xFF1F7EA6});
        TextView t = text(getString(R.string.nk_setupactivity_bngo_1), 19, Typeface.BOLD, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        s0.addView(t, new LinearLayout.LayoutParams(-1, -2));
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setLayoutDirection(L.dir());
        int p = (int) (16 * dp);
        int cw = (int) ((maxW - 2 * 18 * dp - 2 * p) / 3.2f);
        for (int i = 0; i < Theme.ALL.length; i++) {
            if (Theme.ALL[i].photo) continue;
            final int idx = i;
            Widgets.ThemePreview tp = new Widgets.ThemePreview(this, Theme.resolve(this, i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, (int) (cw * 0.78f));
            lp.setMarginStart((int) (8 * dp));
            tp.setOnClickListener(v -> { selectTheme(idx); tab = 0; themeView = 2; showTab(); });
            strip.addView(tp, lp);
        }
        hs.addView(strip);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = (int) (14 * dp);
        s0.addView(hs, hlp);
        s0.setTag(hs);
        addSlide(s0);

        addSlide(newsSlide());
        addSlide(feature(new int[]{0xFF7A2E9E, 0xFF4B3AAE, 0xFF1F5FA6}, null, "جديد",
                "٢٤ تأثير عند الضغط", getString(R.string.nk_setupactivity_addslide_1),
                "جرّب التأثيرات", () -> openThemes(4), new PressFx.Demo(this, -1)));
        addSlide(feature(new int[]{0xFF5E35B0, 0xFF3F3AA8, 0xFF135A70}, "volume", "جديد",
                "٢٧ صوت للأزرار", getString(R.string.nk_setupactivity_addslide_2),
                "اسمع الأصوات", () -> openThemes(3), null));
        Backdrops.AnimView av = new Backdrops.AnimView(this, -1);
        addSlide(feature(new int[]{0xFF6E3BAE, 0xFF43217E, 0xFF191848}, null, "جديد",
                "خلفيات وسمات متحركة", getString(R.string.nk_setupactivity_addslide_3),
                "شوف الخلفيات", () -> openThemes(1), av));
        addSlide(feature(new int[]{0xFF7A48B8, 0xFF4B4DAE, 0xFF2A4FA8}, "paste", "حافظة ذكية",
                "حافظتك صارت مكتبة", getString(R.string.nk_setupactivity_addslide_4),
                "إعدادات الحافظة", () -> { tab = 1; showTab(); }, null));
        addSlide(feature(new int[]{0xFF5E35B0, 0xFF3F3AA8, 0xFF135A70}, "upload", "نقل",
                "صدّر واستورد حافظتك", getString(R.string.nk_setupactivity_addslide_5),
                "تصدير الآن", () -> startActivity(new Intent(this, ClipIOActivity.class).putExtra(ClipIOActivity.EXTRA_MODE, 0)), null));
        addSlide(feature(new int[]{0xFF6E3BAE, 0xFF43217E, 0xFF191848}, "star", "رموز وزخرفة",
                "+٨٠٠ رمز و١٥٠ إطار", getString(R.string.nk_setupactivity_addslide_6), null, null, null));
        addSlide(feature(new int[]{0xFF7A48B8, 0xFF4B4DAE, 0xFF2A4FA8}, "gesture", "سرعة",
                "اكتب بالسحب", getString(R.string.nk_setupactivity_addslide_7), null, null, null));
        addSlide(feature(new int[]{0xFF5E35B0, 0xFF3F3AA8, 0xFF135A70}, "book", "قاموس ضخم",
                "١٥٠ ألف كلمة عربية", getString(R.string.nk_setupactivity_addslide_8), null, null, null));
        addSlide(feature(new int[]{0xFF6E3BAE, 0xFF43217E, 0xFF191848}, "tools", "أدوات ذكية",
                "كل شي من زر •••", getString(R.string.nk_setupactivity_addslide_9), null, null, null));
        addSlide(feature(new int[]{0xFF7A48B8, 0xFF4B4DAE, 0xFF2A4FA8}, "palette", "تخصيص",
                "+٦٠ سمة + سمتك الخاصة", getString(R.string.nk_setupactivity_addslide_10), "اختر سمة", () -> openThemes(2), null));
        addSlide(feature(new int[]{0xFF5E35B0, 0xFF3F3AA8, 0xFF135A70}, "shield", "خصوصية",
                "كل شي على جوالك", getString(R.string.nk_setupactivity_addslide_11), null, null, null));
        addSlide(feature(new int[]{0xFF6E3BAE, 0xFF43217E, 0xFF191848}, "bolt", "التفعيل",
                "شغّله في ٣ خطوات", getString(R.string.nk_setupactivity_addslide_12), "ابدأ التفعيل", () -> { tab = 3; showTab(); }, null));
        addSlide(devSlide());

        H = Math.max(H, (int) (cw * 0.78f + 84 * dp));
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-1, H);
        root.addView(bnStage, stl);
        // progress bar inside card bottom
        bnProgress = new View(this);
        bnProgress.setBackgroundColor(0xCCFFFFFF);
        bnProgress.setPivotX(0);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, (int) (3 * dp), Gravity.BOTTOM);
        bnStage.addView(bnProgress, pl);

        bnDots = new LinearLayout(this);
        bnDots.setGravity(Gravity.CENTER);
        bnDots.setPadding(0, (int) (14 * dp), 0, (int) (18 * dp));
        for (int i = 0; i < bnSlides.size(); i++) {
            final int k = i;
            View d = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (7 * dp), (int) (7 * dp));
            dl.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            d.setOnClickListener(v -> bnGo(k, true));
            bnDots.addView(d, dl);
        }
        root.addView(bnDots);
        for (int i = 1; i < bnSlides.size(); i++) bnSlides.get(i).setVisibility(View.INVISIBLE);
        bnGo(0, false);
    }

    private LinearLayout slideBase(int[] cols) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, cols);
        g.setCornerRadius(30 * dp);
        g.setStroke((int) (1.5f * dp), 0x40C4B5FD);
        s.setBackground(g);
        int p = (int) (16 * dp);
        s.setPadding(p, (int) (18 * dp), p, p);
        return s;
    }

    private void addSlide(View v) {
        bnSlides.add(v);
        bnStage.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    /** A feature slide: big glowing emoji, chip, title, description, optional CTA. */
    private void openThemes(int view) {
        tab = 0; themeView = view; showTab();
        if (mainScroll != null && content != null) mainScroll.post(() -> mainScroll.smoothScrollTo(0, content.getTop()));
    }

    private View feature(int[] cols, String emo, String chip, String title, String desc, String cta, final int ctaTab) {
        return feature(cols, emo, chip, title, desc, cta, () -> { tab = ctaTab; showTab(); }, null);
    }

    /** What's new: version chip + grid of tappable feature chips. */
    private View newsSlide() {
        LinearLayout s = slideBase(new int[]{0xFF8B3FB8, 0xFF5146C0, 0xFF1C86A8});
        s.setGravity(Gravity.CENTER_HORIZONTAL);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView c = text("✦ " + L.t("الإصدار") + " " + ver, 11, Typeface.BOLD, Color.WHITE);
        c.setPadding((int) (12 * dp), (int) (3 * dp), (int) (12 * dp), (int) (3 * dp));
        c.setBackground(round(0x33FFFFFF, 12, 0x55FFFFFF));
        col.addView(c, new LinearLayout.LayoutParams(-2, -2));
        TextView tt = text("وش الجديد؟", 20, Typeface.BOLD, Color.WHITE);
        tt.setPadding(0, (int) (6 * dp), 0, (int) (8 * dp));
        col.addView(tt);
        String[] lb = {"٢٤ تأثير ضغط", "٢٧ صوت", "خلفيات حيّة", "سمات متحركة", "حافظة ذكية", "استيراد وتصدير"};
        final Runnable[] go = {() -> openThemes(4), () -> openThemes(3), () -> openThemes(1), () -> openThemes(2),
                () -> { tab = 1; showTab(); }, () -> startActivity(new Intent(this, ClipIOActivity.class).putExtra(ClipIOActivity.EXTRA_MODE, 0))};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutDirection(L.dir());
            for (int k = 0; k < 3; k++) {
                final int n = r * 3 + k;
                TextView ch = text(lb[n], 12, Typeface.BOLD, Color.WHITE);
                ch.setGravity(Gravity.CENTER);
                ch.setSingleLine(true);
                ch.setEllipsize(android.text.TextUtils.TruncateAt.END);
                ch.setBackground(round(0x26FFFFFF, 14, 0x40FFFFFF));
                ch.setOnClickListener(v -> go[n].run());
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, (int) (34 * dp), 1f);
                cl.setMargins((int) (3 * dp), (int) (3 * dp), (int) (3 * dp), (int) (3 * dp));
                row.addView(ch, cl);
            }
            col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        s.addView(col, new LinearLayout.LayoutParams(-1, -2));
        s.setTag(new View(this)); // tag non-null so text children animate in
        return s;
    }

    /** A feature slide: chip, title, description, optional CTA; right side is an icon or a live visual. */
    private View feature(int[] cols, String emo, String chip, String title, String desc, String cta, final Runnable action, View visual) {
        LinearLayout s = slideBase(cols);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding((int) (20 * dp), (int) (16 * dp), (int) (20 * dp), (int) (16 * dp));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView c = text(chip, 11, Typeface.BOLD, Color.WHITE);
        c.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
        c.setBackground(round(0x33FFFFFF, 12, 0x40FFFFFF));
        col.addView(c, new LinearLayout.LayoutParams(-2, -2));
        TextView tt = text(title, 21, Typeface.BOLD, Color.WHITE);
        tt.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        col.addView(tt);
        TextView dd = text(desc, 13, Typeface.NORMAL, 0xE6FFFFFF);
        dd.setLineSpacing(2 * dp, 1f);
        dd.setMaxLines(3);
        col.addView(dd);
        if (cta != null) {
            TextView b = text(cta, 13, Typeface.BOLD, cols[1]);
            Icons.withIcon(b, "arrow", cols[1], (int) (16 * dp), true, (int) (6 * dp));
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
            b.setBackground(round(Color.WHITE, 16, 0));
            b.setOnClickListener(v -> { if (action != null) action.run(); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = (int) (10 * dp);
            col.addView(b, bl);
        }
        s.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout e = new FrameLayout(this);
        if (visual != null) {
            e.addView(visual, new FrameLayout.LayoutParams(-1, -1));
            e.setClipToOutline(true);
        } else {
            android.widget.ImageView ei = new android.widget.ImageView(this);
            ei.setImageDrawable(Icons.tinted(this, emo, Color.WHITE, (int) (46 * dp)));
            ei.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            e.addView(ei, new FrameLayout.LayoutParams(-1, -1));
        }
        GradientDrawable eg = new GradientDrawable();
        eg.setShape(GradientDrawable.OVAL);
        eg.setColor(0x26FFFFFF);
        eg.setStroke((int) (1.5f * dp), 0x40FFFFFF);
        e.setBackground(eg);
        int es = (int) (96 * dp);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(es, es);
        el.setMarginStart((int) (12 * dp));
        s.addView(e, el);
        s.setTag(e);
        return s;
    }

    private View devSlide() {
        LinearLayout s = slideBase(new int[]{0xFF2390A6, 0xFF4B4DAE, 0xFF5E35B0});
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding((int) (20 * dp), (int) (16 * dp), (int) (20 * dp), (int) (16 * dp));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView c = text("صُنع بحب", 11, Typeface.BOLD, Color.WHITE);
        c.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
        c.setBackground(round(0x33FFFFFF, 12, 0x40FFFFFF));
        col.addView(c, new LinearLayout.LayoutParams(-2, -2));
        TextView tt = text("CTO.DRS", 21, Typeface.BOLD, Color.WHITE);
        Icons.withIcon(tt, "verified", 0xFFFFFFFF, (int) (20 * dp), true, (int) (6 * dp));
        tt.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        col.addView(tt);
        col.addView(text(getString(R.string.nk_setupactivity_devslide_1), 13, Typeface.NORMAL, 0xE6FFFFFF));
        TextView b = text("تواصل معي", 13, Typeface.BOLD, 0xFF4338CA);
        Icons.withIcon(b, "arrow", 0xFF4338CA, (int) (16 * dp), true, (int) (6 * dp));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
        b.setBackground(round(Color.WHITE, 16, 0));
        b.setOnClickListener(v -> { tab = 1; showTab(); scrollToBottom(); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = (int) (10 * dp);
        col.addView(b, bl);
        s.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        int as = (int) (96 * dp);
        FrameLayout ring = new FrameLayout(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setColor(Color.WHITE);
        ring.setBackground(rg);
        android.widget.ImageView ph = new android.widget.ImageView(this);
        ph.setImageBitmap(circle(BitmapFactory.decodeResource(getResources(), R.drawable.dev_photo)));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(as - (int) (6 * dp), as - (int) (6 * dp), Gravity.CENTER);
        ring.addView(ph, pl);
        ring.setOnClickListener(v -> showDevPhoto());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(as, as);
        rl.setMarginStart((int) (12 * dp));
        s.addView(ring, rl);
        s.setTag(ring);
        return s;
    }

    private void scrollToBottom() {
        View v = root;
        while (v != null && !(v instanceof ScrollView)) v = (View) (v.getParent() instanceof View ? v.getParent() : null);
        final ScrollView sv = (ScrollView) v;
        if (sv != null) sv.postDelayed(() -> sv.fullScroll(View.FOCUS_DOWN), 120);
    }

    private void bnGo(int to, boolean animate) {
        int n = bnSlides.size();
        to = ((to % n) + n) % n;
        int from = bnIdx;
        bnIdx = to;
        android.view.animation.DecelerateInterpolator dec = new android.view.animation.DecelerateInterpolator(1.6f);
        float w = bnStage.getWidth() > 0 ? bnStage.getWidth() : maxW;
        if (animate && from != to) {
            final View out = bnSlides.get(from);
            float dir = (to == (from + 1) % n) ? 1 : -1; // RTL: next comes from the left
            out.animate().translationX(dir * w * 0.35f).alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(420)
                    .setInterpolator(dec).withEndAction(() -> { out.setVisibility(View.INVISIBLE); out.setScaleX(1); out.setScaleY(1); }).start();
            View in = bnSlides.get(to);
            in.setVisibility(View.VISIBLE);
            in.bringToFront();
            in.setTranslationX(-dir * w);
            in.setAlpha(1f);
            in.animate().translationX(0).setDuration(480).setInterpolator(dec).start();
            bnProgress.bringToFront();
        } else {
            View in = bnSlides.get(to);
            in.setVisibility(View.VISIBLE);
            in.setTranslationX(0); in.setAlpha(1f);
        }
        // inner motion
        View cur = bnSlides.get(to);
        Object tag = cur.getTag();
        if (tag instanceof HorizontalScrollView) {
            final HorizontalScrollView hs = (HorizontalScrollView) tag;
            hs.post(() -> hs.fullScroll(View.FOCUS_RIGHT));
            hs.postDelayed(() -> hs.smoothScrollBy(-(int) (180 * dp), 0), 1400);
            hs.postDelayed(() -> hs.smoothScrollBy(-(int) (180 * dp), 0), 2900);
        } else if (tag instanceof View) {
            View e = (View) tag;
            e.setScaleX(0.4f); e.setScaleY(0.4f); e.setRotation(-20);
            e.animate().scaleX(1f).scaleY(1f).rotation(0).setStartDelay(180).setDuration(650)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                    .withEndAction(() -> bob(e)).start();
            if (cur instanceof LinearLayout && ((LinearLayout) cur).getChildCount() > 0) {
                View col = ((LinearLayout) cur).getChildAt(0);
                if (col instanceof LinearLayout) {
                    LinearLayout c = (LinearLayout) col;
                    for (int i = 0; i < c.getChildCount(); i++) {
                        View ch = c.getChildAt(i);
                        ch.setAlpha(0f); ch.setTranslationY(14 * dp);
                        ch.animate().alpha(1f).translationY(0).setStartDelay(160 + i * 110L).setDuration(380).setInterpolator(dec).start();
                    }
                }
            }
        }
        // dots
        for (int i = 0; i < bnDots.getChildCount(); i++) {
            View d = bnDots.getChildAt(i);
            boolean on = i == to;
            d.setBackground(round(on ? Color.WHITE : 0xFF4A4F5C, 4, 0));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
            lp.width = (int) ((on ? 22 : 7) * dp);
            d.setLayoutParams(lp);
        }
        bnSchedule();
    }

    private void bob(View e) {
        if (Theme.reduceMotion(this)) return;
        if (e.getParent() == null) return;
        e.animate().translationY(-5 * dp).setStartDelay(0).setDuration(900)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> e.animate().translationY(0).setDuration(900)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start())
                .start();
    }

    private void bnSchedule() {
        bnH.removeCallbacks(bnNext);
        if (bnProgress == null) return;
        bnProgress.animate().cancel();
        bnProgress.setScaleX(0f);
        if (bnPaused || Theme.reduceMotion(this)) return;
        bnProgress.animate().scaleX(1f).setDuration(BN_MS).setStartDelay(0)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        bnH.postDelayed(bnNext, BN_MS);
    }

    private void bnPause(boolean pause) {
        if (bnPaused == pause) return;
        bnPaused = pause;
        if (pause) { bnH.removeCallbacks(bnNext); if (bnProgress != null) bnProgress.animate().cancel(); }
        else bnSchedule();
    }

    @Override
    protected void onPause() {
        super.onPause();
        bnH.removeCallbacks(bnNext);
        if (bnProgress != null) bnProgress.animate().cancel();
    }

    // ---------------------------------------------------------------- tabs

    private void showTab() {
        for (int i = 0; i < dockSlots.length; i++) {
            if (dockSlots[i] == null) continue;
            boolean on = DOCK_TAB[i] == tab;
            dockSlots[i].setBackground(on ? Glass.neon(dp, 16) : Glass.panel(dp, 16, false));
            dockSlots[i].setImageDrawable(Icons.tinted(this, DOCK_ICON[i], on ? Color.WHITE : 0xFFC4BFEA, (int) (22 * dp)));
            dockSlots[i].setSelected(on);
            dockLabels[i].setTextColor(on ? Color.WHITE : 0xFF8F8AB8);
        }
        refreshAccents();
        content.removeAllViews();
        try {
            if (tab == 0) buildThemes();
            else if (tab == 1) buildSettings();
            else if (tab == 2) buildPhrases();
            else buildActivation();
        } catch (Throwable e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            content.addView(errorCard(getString(R.string.nk_setupactivity_buildactivation_1), sw.toString()));
        }
    }

    /** Shows an error with its technical details and a copy button instead of crashing. */
    private View errorCard(String msg, String details) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (14 * dp);
        c.setPadding(p, p, p, p);
        c.setBackground(round(0xFF2A1215, 20, 0xFF7F1D1D));
        TextView t = text(msg, 14, Typeface.BOLD, 0xFFFCA5A5);
        Icons.withIcon(t, "bug", 0xFFFCA5A5, (int) (20 * dp), false, (int) (8 * dp));
        c.addView(t);
        final String d = details.length() > 4000 ? details.substring(0, 4000) : details;
        TextView dt = text(d, 11, Typeface.NORMAL, 0xFFD1D5DB);
        dt.setTypeface(Typeface.MONOSPACE);
        dt.setTextDirection(View.TEXT_DIRECTION_LTR);
        dt.setTextIsSelectable(true);
        dt.setMaxLines(14);
        dt.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        c.addView(dt);
        TextView cp = text("نسخ التفاصيل", 14, Typeface.BOLD, Color.WHITE);
        cp.setGravity(Gravity.CENTER);
        cp.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        cp.setBackground(round(BLUE, 18, 0));
        cp.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("NeoKey error", d));
            Toast.makeText(this, L.t("تم النسخ"), Toast.LENGTH_SHORT).show();
        });
        c.addView(cp, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (16 * dp);
        c.setLayoutParams(lp);
        return c;
    }

    /** If the app or keyboard crashed last time, show the saved details once. */
    private void showLastCrash() {
        File f = new File(getFilesDir(), "last_crash.txt");
        if (!f.exists()) return;
        String s;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 8000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            s = new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) { s = ""; }
        f.delete();
        if (s.isEmpty()) return;
        root.addView(errorCard(getString(R.string.nk_setupactivity_showlastcrash_1), s), 1);
    }

    private void section(String name) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (26 * dp), 0, (int) (14 * dp));
        View bar = new View(this);
        bar.setBackground(round(BLUE, 2, 0));
        row.addView(bar, new LinearLayout.LayoutParams((int) (4 * dp), (int) (20 * dp)));
        TextView t = text(name, 17, Typeface.NORMAL, 0xFFDDE1EA);
        t.setPadding((int) (10 * dp), 0, (int) (12 * dp), 0);
        row.addView(t);
        View line = new View(this);
        line.setBackgroundColor(LINE);
        row.addView(line, new LinearLayout.LayoutParams(0, (int) dp, 1f));
        content.addView(row);
    }

    private int themeFilter = 0;
    private ScrollView mainScroll;

    /** Rebuild the current tab but keep the scroll position. */
    private void refresh() {
        final int y = mainScroll != null ? mainScroll.getScrollY() : 0;
        showTab();
        if (mainScroll != null) mainScroll.post(() -> mainScroll.scrollTo(0, y));
    }

    private interface IntPick { void pick(int v); }

    // themes tab pages: 0 = two tall entry cards, 1 = backgrounds editor, 2 = themes editor/library
    private int themeView = 0;

    private void buildThemes() {
        if (themeView == 0) { buildThemeHome(); return; }
        if (themeView == 1) { buildBackdrops(); return; }
        if (themeView == 3) { buildSounds(); return; }
        if (themeView == 4) { buildPressFx(); return; }
        content.addView(backRow("محرر السمات"));
        if (!isReady()) content.addView(alert());
        int cur = Theme.index(this);

        // studio actions
        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f);
        a1.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1f);
        a2.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
        LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(0, -2, 1f);
        a3.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
        actions.addView(studioBtn("سمة جديدة", "add", v -> newFromScratch()), a1);
        actions.addView(studioBtn("استيراد", "download", v -> importDialog()), a2);
        actions.addView(studioBtn("تصدير", "upload", v -> exportDialog(Theme.current(this))), a3);
        actions.addView(studioBtn("مولّد", "star", v -> genDialog()), a3);
        actions.addView(studioBtn("القناة المنسّقة", "verified", v -> channelThemes()), a3);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = (int) (16 * dp);
        content.addView(actions, alp);

        if (Theme.prefs(this).getBoolean("autotheme", false)) {
            TextView note = text(getString(R.string.nk_setupactivity_buildthemes_1),
                    12, Typeface.NORMAL, 0xFFFBBF24);
            note.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
            note.setBackground(round(0xFF2A2210, 14, 0xFF4A3B10));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
            nlp.topMargin = (int) (10 * dp);
            content.addView(note, nlp);
        }

        // category chips
        java.util.List<Integer> moving = new java.util.ArrayList<>();
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        int still = 0;
        for (int i = 0; i < Theme.ALL.length; i++) {
            if (i == Theme.CUSTOM_INDEX) continue;
            if (Theme.ALL[i].anim >= 0) { moving.add(i); continue; }
            still++;
            if (themeFilter == 0 || Theme.category(Theme.ALL[i]).equals(Theme.CATS[themeFilter])) ids.add(i);
        }
        content.addView(chips(Theme.CATS, themeFilter, v -> { themeFilter = v; refresh(); }));
        LinearLayout[] tc = twoCols(L.t("الثابتة") + " · " + ids.size(), L.t("المتحركة") + " · " + moving.size());
        themeGrid(ids, cur, tc[0], 1);
        themeGrid(moving, cur, tc[1], 1);

        // my library
        org.json.JSONArray lib = Theme.library(this);
        if (lib.length() > 0) {
            section("مكتبتي · " + lib.length());
            java.util.List<Integer> mine = new java.util.ArrayList<>();
            for (int i = 0; i < lib.length(); i++) mine.add(Theme.LIB_BASE + i);
            themeGrid(mine, cur);
            TextView h = text(getString(R.string.nk_setupactivity_themegrid_1), 12, Typeface.NORMAL, 0xFF8A90A0);
            h.setPadding((int) (6 * dp), (int) (4 * dp), 0, 0);
            content.addView(h);
        }

        // editor
        section("محرر السمات");
        java.util.List<Integer> one = new java.util.ArrayList<>();
        one.add(Theme.CUSTOM_INDEX);
        if (cur != Theme.CUSTOM_INDEX) {
            themeGrid(one, cur);
            TextView h = text("اختر «" + Theme.custom(this).name + getString(R.string.nk_setupactivity_themegrid_2), 12, Typeface.NORMAL, 0xFF8A90A0);
            h.setPadding((int) (6 * dp), (int) (4 * dp), 0, 0);
            content.addView(h);
        } else {
            buildEditor();
        }

        if (false) {
            section("تعتيم الصورة");
            SeekBar sb = new SeekBar(this);
            sb.setMax(85);
            sb.setProgress(Theme.photoDim(this));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int v, boolean u) { Theme.prefs(SetupActivity.this).edit().putInt("dim", v).apply(); }
                @Override public void onStartTrackingTouch(SeekBar b) { }
                @Override public void onStopTrackingTouch(SeekBar b) { }
            });
            content.addView(sb);
        }
    }

    private View backRow(String title) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = text(L.t("رجوع"), 13, Typeface.BOLD, Color.WHITE);
        Icons.withIcon(b, "arrow_forward", 0xFFC4BFEA, (int) (18 * dp), false, (int) (6 * dp));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding((int) (14 * dp), (int) (9 * dp), (int) (16 * dp), (int) (9 * dp));
        b.setBackground(Glass.panel(dp, 20, false));
        b.setOnClickListener(v -> { themeView = 0; showTab(); });
        r.addView(b, new LinearLayout.LayoutParams(-2, -2));
        TextView t = text(title, 20, Typeface.BOLD, Color.WHITE);
        t.setPadding((int) (14 * dp), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (16 * dp);
        lp.bottomMargin = (int) (4 * dp);
        r.setLayoutParams(lp);
        return r;
    }

    /** Themes tab home: background editor (start side) and theme editor, as two tall cards. */
    private void buildThemeHome() {
        if (!isReady()) content.addView(alert());
        LinearLayout row = new LinearLayout(this);
        int H = (int) (330 * dp);
        Theme cur = Theme.current(this);

        // background editor card
        FrameLayout bgCard = new FrameLayout(this);
        bgCard.setBackground(Glass.panel(dp, 28, true));
        int pd = (int) (8 * dp);
        bgCard.setPadding(pd, pd, pd, pd);
        View bgPrev = new View(this);
        int bd = Theme.prefs(this).getInt("backdrop", 0);
        Bitmap ph = cur.photo ? loadPhoto() : null;
        int abg = Theme.prefs(this).getInt("anim_bg", -1);
        if (cur.photo && abg >= 0) bgPrev = new Backdrops.AnimView(this, abg);
        else if (ph != null) {
            android.graphics.drawable.BitmapDrawable bdw = new android.graphics.drawable.BitmapDrawable(getResources(), ph);
            bgPrev.setBackground(new CropDrawable(bdw));
        } else bgPrev.setBackground(Backdrops.preview(Math.max(0, Math.min(Backdrops.count() - 1, bd))));
        bgPrev.setClipToOutline(true);
        bgPrev.setOutlineProvider(roundOutline(22));
        bgCard.addView(bgPrev, new FrameLayout.LayoutParams(-1, -1));
        bgCard.addView(cardLabel("محرر الخلفيات", (Backdrops.count() + Backdrops.animCount() + 1) + " " + L.t("خلفية") + " · " + Backdrops.animCount() + " " + L.t("متحركة"), "palette"),
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        bgCard.setContentDescription(L.t("محرر الخلفيات"));
        bgCard.setOnClickListener(v -> { themeView = 1; showTab(); if (mainScroll != null) mainScroll.smoothScrollTo(0, content.getTop()); });
        row.addView(bgCard, new LinearLayout.LayoutParams(0, H, 1f));

        // theme editor card: three stacked previews
        FrameLayout thCard = new FrameLayout(this);
        thCard.setBackground(Glass.panel(dp, 28, false));
        thCard.setPadding(pd, pd, pd, pd);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        int ci = Theme.index(this);
        int[] show = {ci, ci == 1 ? 0 : 1, ci == 3 ? 2 : 3};
        for (int i = 0; i < show.length; i++) {
            Widgets.ThemePreview tp = new Widgets.ThemePreview(this, Theme.resolve(this, show[i]));
            if (Theme.resolve(this, show[i]).photo) tp.setPhoto(loadPhoto());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (i > 0) lp.topMargin = (int) (6 * dp);
            stack.addView(tp, lp);
        }
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1);
        sl.bottomMargin = (int) (66 * dp);
        thCard.addView(stack, sl);
        thCard.addView(cardLabel("محرر السمات", (Theme.ALL.length - 1) + " " + L.t("سمة"), "tune"),
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        thCard.setContentDescription(L.t("محرر السمات"));
        thCard.setOnClickListener(v -> { themeView = 2; showTab(); if (mainScroll != null) mainScroll.smoothScrollTo(0, content.getTop()); });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, H, 1f);
        tl.setMarginStart((int) (12 * dp));
        row.addView(thCard, tl);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = (int) (16 * dp);
        content.addView(row, rl);

        // second row: sounds + press effects
        LinearLayout row2 = new LinearLayout(this);
        int H2 = (int) (210 * dp);
        boolean sndOn = Theme.sound(this);
        int sp = Theme.prefs(this).getInt("sound_pack", 0);
        FrameLayout sCard = previewCard(new Backdrops.AnimView(this, 22), "الأصوات",
                sndOn ? L.t(Sounds.NAMES[Math.max(0, Math.min(Sounds.count() - 1, sp))]) : L.t("بدون صوت"), "volume", false);
        sCard.setOnClickListener(v -> { themeView = 3; showTab(); if (mainScroll != null) mainScroll.smoothScrollTo(0, content.getTop()); });
        row2.addView(sCard, new LinearLayout.LayoutParams(0, H2, 1f));
        int fx = Theme.prefs(this).getInt("press_fx", -1);
        FrameLayout fCard = previewCard(new PressFx.Demo(this, fx >= 0 ? fx : 7), "تأثيرات الضغط",
                fx >= 0 ? L.t(PressFx.NAMES[Math.min(PressFx.count() - 1, fx)]) : PressFx.count() + " " + L.t("تأثير"), "sparkle", false);
        fCard.setOnClickListener(v -> { themeView = 4; showTab(); if (mainScroll != null) mainScroll.smoothScrollTo(0, content.getTop()); });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, H2, 1f);
        fl.setMarginStart((int) (12 * dp));
        row2.addView(fCard, fl);
        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(-1, -2);
        rl2.topMargin = (int) (12 * dp);
        content.addView(row2, rl2);
    }

    private FrameLayout previewCard(View preview, String title, String sub, String icon, boolean on) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Glass.panel(dp, 28, on));
        int pd = (int) (8 * dp);
        card.setPadding(pd, pd, pd, pd);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(roundOutline(22));
        card.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        card.addView(cardLabel(title, sub, icon), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setContentDescription(L.t(title));
        return card;
    }

    private Sounds appSounds;

    private void previewSound(int pack) {
        float vol = Theme.prefs(this).getInt("sound_vol", 50) / 100f;
        if (pack <= 0) {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD, vol);
            return;
        }
        if (appSounds == null) appSounds = new Sounds(this);
        appSounds.setPack(pack);
        final Sounds snd = appSounds;
        // SoundPool loads asynchronously: play a short "typing" phrase once loaded.
        int[] kinds = {0, 0, 2, 0, 1, 3};
        for (int i = 0; i < kinds.length; i++) {
            final int kd = kinds[i];
            content.postDelayed(() -> snd.play(kd, vol), 120 + i * 140L);
        }
    }

    /** Sounds page: volume, then sound packs as tiles (tap = choose + listen). */
    private void buildSounds() {
        content.addView(backRow("الأصوات"));
        boolean on = Theme.sound(this);
        int sel = on ? Theme.prefs(this).getInt("sound_pack", 0) : -1;
        section("مستوى الصوت");
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Theme.prefs(this).getInt("sound_vol", 50));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean u) { Theme.prefs(SetupActivity.this).edit().putInt("sound_vol", Math.max(5, v)).apply(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { previewSound(Math.max(0, Theme.prefs(SetupActivity.this).getInt("sound_pack", 0))); }
        });
        content.addView(sb);
        TextView hint = text(getString(R.string.nk_setupactivity_section_1), 12, Typeface.NORMAL, 0xFF8F8AB8);
        hint.setPadding((int) (6 * dp), (int) (8 * dp), 0, (int) (4 * dp));
        content.addView(hint);
        section(L.t("أصوات الأزرار") + " · " + Sounds.count());
        int n = Sounds.count() + 1;
        LinearLayout row = null;
        for (int k = 0; k < n; k++) {
            if (k % 2 == 0) { row = new LinearLayout(this); content.addView(row, top(k == 0 ? 4 : 10)); }
            final int pk = k - 1; // -1 = silent
            boolean isOn = pk == sel;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Glass.panel(dp, 22, isOn));
            tile.setPadding((int) (10 * dp), (int) (14 * dp), (int) (10 * dp), (int) (14 * dp));
            android.widget.ImageView ic = new android.widget.ImageView(this);
            ic.setImageDrawable(Icons.tinted(this, pk < 0 ? "close" : "volume", isOn ? Color.WHITE : 0xFFC4BFEA, (int) (22 * dp)));
            ic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ic.setBackground(isOn ? Glass.neon(dp, 20) : Glass.orb(Glass.PURPLE, Glass.INDIGO, true));
            tile.addView(ic, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
            TextView nm = text(pk < 0 ? "بدون صوت" : Sounds.NAMES[pk], 14, Typeface.BOLD, Color.WHITE);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, (int) (8 * dp), 0, 0);
            tile.addView(nm);
            TextView ds = text(pk < 0 ? "الأزرار صامتة" : Sounds.DESC[pk], 11, Typeface.NORMAL, 0xFF9D98C8);
            ds.setGravity(Gravity.CENTER);
            tile.addView(ds);
            tile.setContentDescription(nm.getText() + (isOn ? "، " + L.t("محددة") : ""));
            tile.setOnClickListener(v -> {
                if (pk < 0) Theme.prefs(this).edit().putBoolean("sound", false).apply();
                else {
                    Theme.prefs(this).edit().putBoolean("sound", true).putInt("sound_pack", pk).apply();
                    previewSound(pk);
                }
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (k % 2 == 1) lp.setMarginStart((int) (10 * dp));
            row.addView(tile, lp);
        }
        if (n % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /** Press effects page: animated demo tiles. */
    private void buildPressFx() {
        content.addView(backRow("تأثيرات الضغط"));
        int sel = Theme.prefs(this).getInt("press_fx", -1);
        TextView hint = text(getString(R.string.nk_setupactivity_buildpressfx_1), 12, Typeface.NORMAL, 0xFF8F8AB8);
        hint.setPadding((int) (6 * dp), (int) (8 * dp), 0, (int) (4 * dp));
        content.addView(hint);
        if (Theme.reduceMotion(this)) {
            TextView w = text(getString(R.string.nk_setupactivity_buildpressfx_2), 12, Typeface.BOLD, 0xFFFBBF24);
            w.setPadding((int) (6 * dp), (int) (4 * dp), 0, (int) (4 * dp));
            content.addView(w);
        }
        section(L.t("التأثيرات") + " · " + PressFx.count());
        int n = PressFx.count() + 1;
        int TH = (int) (170 * dp);
        LinearLayout row = null;
        for (int k = 0; k < n; k++) {
            if (k % 2 == 0) { row = new LinearLayout(this); content.addView(row, top(k == 0 ? 4 : 10)); }
            final int t = k - 1;
            View pv;
            if (t >= 0) {
                pv = new PressFx.Demo(this, t);
                pv.setBackground(Glass.aurora(dp, 20, t % 2 == 0 ? Glass.PURPLE : Glass.INDIGO));
            } else {
                pv = new View(this);
                pv.setBackground(Glass.aurora(dp, 20, Glass.INDIGO));
            }
            row.addView(bdTile(pv, t < 0 ? "بدون تأثير" : PressFx.NAMES[t], t == sel, false, v -> {
                Theme.prefs(this).edit().putInt("press_fx", t).apply();
                refresh();
            }), bdLpW(k, TH));
        }
        if (n % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, TH, 1f));
    }

    private LinearLayout.LayoutParams bdLpW(int k, int th) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, th, 1f);
        if (k % 2 == 1) lp.setMarginStart((int) (10 * dp));
        return lp;
    }

    private View cardLabel(String title, String sub, String icon) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE60B0C24, 0x990B0C24, 0x000B0C24});
        g.setCornerRadii(new float[]{0, 0, 0, 0, 22 * dp, 22 * dp, 22 * dp, 22 * dp});
        l.setBackground(g);
        l.setPadding((int) (12 * dp), (int) (22 * dp), (int) (12 * dp), (int) (10 * dp));
        TextView t = text(title, 16, Typeface.BOLD, Color.WHITE);
        Icons.withIcon(t, icon, 0xFFC4BFEA, (int) (18 * dp), false, (int) (6 * dp));
        l.addView(t);
        l.addView(text(sub, 11, Typeface.NORMAL, 0xFFB9B4E0));
        return l;
    }

    private android.view.ViewOutlineProvider roundOutline(final int rDp) {
        return new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rDp * dp); }
        };
    }

    /** Center-crops a bitmap drawable into any bounds. */
    private static final class CropDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.BitmapDrawable b;
        CropDrawable(android.graphics.drawable.BitmapDrawable b) { this.b = b; }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            Bitmap bm = b.getBitmap();
            if (bm == null || r.width() <= 0) return;
            float sc = Math.max(r.width() / (float) bm.getWidth(), r.height() / (float) bm.getHeight());
            float sw = r.width() / sc, sh = r.height() / sc;
            int sx = (int) ((bm.getWidth() - sw) / 2), sy = (int) ((bm.getHeight() - sh) / 2);
            c.drawBitmap(bm, new android.graphics.Rect(sx, sy, sx + (int) sw, sy + (int) sh), r, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** Backgrounds editor: dim slider, user's photo, then the built-in backgrounds as tall tiles. */
    private void buildBackdrops() {
        content.addView(backRow("محرر الخلفيات"));
        boolean photoOn = Theme.current(this).photo;
        int sel = photoOn ? Theme.prefs(this).getInt("backdrop", -2) : -99;

        if (photoOn) {
            section("تعتيم الخلفية");
            SeekBar sb = new SeekBar(this);
            sb.setMax(85);
            sb.setProgress(Theme.photoDim(this));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int v, boolean u) { Theme.prefs(SetupActivity.this).edit().putInt("dim", v).apply(); }
                @Override public void onStartTrackingTouch(SeekBar b) { }
                @Override public void onStopTrackingTouch(SeekBar b) { }
            });
            content.addView(sb);
        }
        TextView hint = text(getString(R.string.nk_setupactivity_section_2), 12, Typeface.NORMAL, 0xFF8F8AB8);
        hint.setPadding((int) (6 * dp), (int) (8 * dp), 0, (int) (4 * dp));
        content.addView(hint);

        int animSel = photoOn ? Theme.prefs(this).getInt("anim_bg", -1) : -1;
        int TH = (int) (250 * dp);

        int n = Backdrops.count() + 1;
        LinearLayout[] bc = twoCols(L.t("الثابتة") + " · " + n, L.t("المتحركة") + " · " + Backdrops.animCount());
        // first column: static (first tile = user's own photo)
        for (int k = 0; k < n; k++) {
            final int i = k - 1; // -1 = user photo
            boolean on = animSel < 0 && ((i == -1 && (sel == -1 || sel == -2)) || (i >= 0 && sel == i));
            View pv = new View(this);
            if (i >= 0) pv.setBackground(Backdrops.preview(i));
            else {
                Bitmap ub = loadUserPhoto();
                if (ub != null) pv.setBackground(new CropDrawable(new android.graphics.drawable.BitmapDrawable(getResources(), ub)));
                else pv.setBackground(Glass.aurora(dp, 20, Glass.PURPLE));
            }
            String nm = i >= 0 ? Backdrops.NAMES[i] : (userPhotoFile().exists() ? "صورتك" : "＋ صورة من جهازك");
            bc[0].addView(bdTile(pv, nm, on, i == -1 && userPhotoFile().exists(), v -> {
                if (i >= 0) applyBackdrop(i);
                else if (userPhotoFile().exists()) applyUserPhoto();
                else pickPhoto();
            }), bdLp(TH));
        }
        // second column: animated
        for (int k = 0; k < Backdrops.animCount(); k++) {
            final int i = k;
            bc[1].addView(bdTile(new Backdrops.AnimView(this, i), Backdrops.ANIM_NAMES[i], photoOn && animSel == i, false,
                    v -> applyAnimBg(i)), bdLp(TH));
        }
    }

    private LinearLayout.LayoutParams bdLp(int th) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, th);
        lp.setMargins((int) (5 * dp), 0, (int) (5 * dp), (int) (12 * dp));
        return lp;
    }

    private View bdTile(View pv, String nm, boolean on, boolean changeChip, View.OnClickListener click) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Glass.panel(dp, 24, on));
        int pd = (int) (6 * dp);
        tile.setPadding(pd, pd, pd, pd);
        pv.setClipToOutline(true);
        pv.setOutlineProvider(roundOutline(19));
        tile.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        TextView name = text(nm, 13, Typeface.BOLD, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setPadding((int) (10 * dp), (int) (18 * dp), (int) (10 * dp), (int) (10 * dp));
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000});
        fg.setCornerRadii(new float[]{0, 0, 0, 0, 19 * dp, 19 * dp, 19 * dp, 19 * dp});
        name.setBackground(fg);
        tile.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (changeChip) {
            TextView ch = text("تغيير", 11, Typeface.BOLD, Color.WHITE);
            ch.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
            ch.setBackground(round(0xAA000000, 12, 0x55FFFFFF));
            ch.setOnClickListener(v -> pickPhoto());
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cl.setMargins((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), 0);
            tile.addView(ch, cl);
        }
        if (on) {
            android.widget.ImageView ck = new android.widget.ImageView(this);
            ck.setImageDrawable(Icons.tinted(this, "check", Color.WHITE, (int) (18 * dp)));
            ck.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            ck.setBackground(Glass.neon(dp, 15));
            FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams((int) (30 * dp), (int) (30 * dp), Gravity.TOP | Gravity.END);
            kl.setMargins((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), 0);
            tile.addView(ck, kl);
        }
        tile.setContentDescription(name.getText() + (on ? "، " + L.t("محددة") : ""));
        tile.setOnClickListener(click);
        return tile;
    }

    private void applyAnimBg(int i) {
        Theme.prefs(this).edit().putInt("anim_bg", i).apply();
        selectTheme(Theme.PHOTO_INDEX);
        refresh();
        Toast.makeText(this, L.t("تم تعيين الخلفية"), Toast.LENGTH_SHORT).show();
    }

    private File userPhotoFile() { return new File(getFilesDir(), "user_bg.jpg"); }

    private Bitmap userPhotoCache;
    private long userPhotoStamp = -1;

    private Bitmap loadUserPhoto() {
        File f = userPhotoFile();
        long st = f.exists() ? f.lastModified() : 0;
        if (st != userPhotoStamp) {
            userPhotoStamp = st;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            userPhotoCache = st == 0 ? null : BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        }
        return userPhotoCache;
    }

    private void applyBackdrop(int i) {
        try {
            int w = Math.min(1440, getResources().getDisplayMetrics().widthPixels);
            int h = (int) (w * 0.75f);
            Bitmap b = Backdrops.render(i, w, h);
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "bg.jpg"));
            b.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            b.recycle();
            Theme.prefs(this).edit().putInt("backdrop", i).putInt("anim_bg", -1).apply();
            selectTheme(Theme.PHOTO_INDEX);
            refresh();
            Toast.makeText(this, L.t("تم تعيين الخلفية"), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, L.t("تعذر تعيين الخلفية"), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyUserPhoto() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(userPhotoFile());
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "bg.jpg"));
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            out.close();
            Theme.prefs(this).edit().putInt("backdrop", -1).putInt("anim_bg", -1).apply();
            selectTheme(Theme.PHOTO_INDEX);
            refresh();
            Toast.makeText(this, L.t("تم تعيين الخلفية"), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, L.t("تعذر تعيين الخلفية"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (tab == 0 && themeView != 0) { themeView = 0; showTab(); return; }
        super.onBackPressed();
    }

    private TextView studioBtn(String label, String icon, View.OnClickListener l) {
        TextView b = text(label, 13, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        Icons.withIcon(b, icon, 0xFF93B4FF, (int) (18 * dp), false, (int) (6 * dp));
        b.setBackground(round(CARD, 18, LINE));
        b.setOnClickListener(l);
        return b;
    }

    private View chips(String[] names, int sel, final IntPick on) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 13, Typeface.BOLD, i == sel ? Color.WHITE : 0xFFB9BECA);
            t.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
            t.setBackground(i == sel ? round(BLUE, 18, 0) : round(CARD, 18, LINE));
            t.setOnClickListener(v -> on.pick(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            row.addView(t, lp);
        }
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (6 * dp);
        hs.setLayoutParams(lp);
        return hs;
    }

    private void themeGrid(java.util.List<Integer> ids, int cur) { themeGrid(ids, cur, content, 2); }

    private void themeGrid(java.util.List<Integer> ids, int cur, LinearLayout target, int cols) {
        LinearLayout row = null;
        for (int n = 0; n < ids.size(); n++) {
            if (n % cols == 0) {
                row = new LinearLayout(this);
                target.addView(row);
            }
            final int idx = ids.get(n);
            final Theme t = Theme.resolve(this, idx);
            boolean on = idx == cur;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int p = (int) (8 * dp);
            card.setPadding(p, p, p, p);
            card.setBackground(round(CARD, 20, on ? BLUE : LINE, on ? 2 : 1));
            FrameLayout holder = new FrameLayout(this);
            Widgets.ThemePreview tp = new Widgets.ThemePreview(this, t);
            if (t.photo) tp.setPhoto(loadPhoto());
            holder.addView(tp, new FrameLayout.LayoutParams(-1, (int) (110 * dp)));
            if (t.photo && idx == Theme.PHOTO_INDEX) {
                TextView add = text(loadPhoto() == null ? "＋ اختر صورة" : "تغيير الصورة", 13, Typeface.BOLD, Color.WHITE);
                add.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
                add.setBackground(round(0xCC000000, 14, 0x55FFFFFF));
                add.setOnClickListener(v -> pickPhoto());
                holder.addView(add, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            }
            if (on) {
                FrameLayout badge = new FrameLayout(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(BLUE);
                badge.setBackground(bg);
                badge.addView(new Widgets.Icon(this, Widgets.Icon.CHECK, Color.WHITE), new FrameLayout.LayoutParams(-1, -1));
                int bs = (int) (26 * dp);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.TOP | Gravity.START);
                blp.setMargins((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), 0);
                holder.addView(badge, blp);
            }
            String tag = idx == Theme.CUSTOM_INDEX ? "محرر" : idx >= Theme.LIB_BASE ? "مكتبتي" : Theme.category(t);
            TextView chip = text(tag, 10, Typeface.BOLD, Color.WHITE);
            chip.setPadding((int) (8 * dp), (int) (2 * dp), (int) (8 * dp), (int) (3 * dp));
            chip.setBackground(round(0x99000000, 10, 0));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            clp.setMargins(0, (int) (7 * dp), (int) (7 * dp), 0);
            holder.addView(chip, clp);
            card.addView(holder);
            TextView name = text(t.name, 14, Typeface.BOLD, on ? Color.WHITE : 0xFFB9BECA);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, (int) (8 * dp), 0, (int) (2 * dp));
            card.addView(name, new LinearLayout.LayoutParams(-1, -2));
            card.setOnClickListener(v -> {
                if (t.photo && loadPhoto() == null) { pickPhoto(); return; }
                selectTheme(idx);
                refresh();
            });
            card.setOnLongClickListener(v -> { themeMenu(idx, t); return true; });
            card.setContentDescription(L.t("سمة ") + t.name + "، " + tag + (on ? "، محددة" : "") + ". اضغط مطولاً للخيارات");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins((int) (5 * dp), (int) (5 * dp), (int) (5 * dp), (int) (5 * dp));
            row.addView(card, lp);
        }
        if (ids.size() % cols != 0 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /** Two side-by-side columns: first (start side) and second. Returns {first, second}. */
    private LinearLayout[] twoCols(String h1, String h2) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] out = new LinearLayout[2];
        String[] hs = {h1, h2};
        for (int i = 0; i < 2; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView h = text(hs[i], 14, Typeface.BOLD, 0xFFDDE1EA);
            h.setGravity(Gravity.CENTER);
            h.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            h.setBackground(Glass.panel(dp, 16, false));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.setMargins((int) (5 * dp), 0, (int) (5 * dp), (int) (6 * dp));
            col.addView(h, hl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i == 1) cl.setMarginStart((int) (6 * dp));
            wrap.addView(col, cl);
            out[i] = col;
        }
        content.addView(wrap, top(10));
        return out;
    }

    private void themeMenu(final int idx, final Theme t) {
        boolean lib = idx >= Theme.LIB_BASE;
        String[] items = lib ? new String[]{"تعديل في المحرر", "تصدير", "حذف من مكتبتي"}
                : new String[]{"تعديل نسخة منها في المحرر", "تصدير"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(t.name)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        org.json.JSONObject o = Theme.toSpec(t);
                        try { o.put("name", t.name + (lib ? "" : " ٢")); } catch (Exception ignored) { }
                        Theme.saveCustomSpec(this, o);
                        selectTheme(Theme.CUSTOM_INDEX);
                        refresh();
                    } else if (w == 1) {
                        exportDialog(t);
                    } else {
                        deleteLib(idx - Theme.LIB_BASE);
                    }
                }).show();
    }

    private void deleteLib(int pos) {
        org.json.JSONArray a = Theme.library(this), n = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) if (i != pos) n.put(a.opt(i));
        Theme.saveLibrary(this, n);
        android.content.SharedPreferences p = Theme.prefs(this);
        android.content.SharedPreferences.Editor e = p.edit();
        for (String k : new String[]{"theme", "day_theme", "night_theme"}) {
            int v = p.getInt(k, k.equals("night_theme") ? 1 : 0);
            if (v == Theme.LIB_BASE + pos) e.putInt(k, k.equals("night_theme") ? 1 : 0);
            else if (v > Theme.LIB_BASE + pos) e.putInt(k, v - 1);
        }
        e.apply();
        Toast.makeText(this, L.t("انحذفت السمة"), Toast.LENGTH_SHORT).show();
        refresh();
    }

    // ---------------------------------------------------------------- theme editor

    /** W69: ثيم مولّد من بذرة — عبارة واحدة تولّد الألوان والخلفية، صفر صور. */
    /** W74: القناة المنسّقة الموقّعة — تُفتح بايت-دقيقة وتُستورد بذورها حية عبر GenThemes. */
    private void channelThemes() {
        String chan = getString(R.string.nk_setupactivity_themechannel);
        try {
            ThemeChannel.Open o = ThemeChannel.open(chan.getBytes(StandardCharsets.UTF_8));
            int n = 0;
            for (ThemeChannel.Entry en : o.entries) {
                GenThemes.Pal p = GenThemes.make(GenThemes.seedOf(en.seed));
                org.json.JSONObject ob = new org.json.JSONObject();
                ob.put("neokey", 1);
                ob.put("name", "منسّق: " + en.seed);
                ob.put("gen", en.seed);
                ob.put("bg", hex8(p.bg)); ob.put("key", hex8(p.key));
                ob.put("text", hex8(p.text)); ob.put("enter", hex8(p.special));
                Theme.importPack(this, ob);
                n++;
            }
            Toast.makeText(this, L.t(getString(R.string.nk_setupactivity_channeltoast).replace("%1$d", String.valueOf(n))), Toast.LENGTH_SHORT).show();
            tab = 0;
            refresh();
        } catch (Exception ex) {
            Toast.makeText(this, L.t(getString(R.string.nk_setupactivity_channelrefuse)), Toast.LENGTH_SHORT).show();
        }
    }

    private static String hex8(int c) { return String.format("#%08X", c & 0xFFFFFF); }

    private void genDialog() {
        EditText seed = new EditText(this);
        seed.setSingleLine(true);
        seed.setTextColor(Color.WHITE);
        seed.setHintTextColor(0xFF6B7280);
        seed.setHint(L.t("اكتب أي عبارة — بذرة الثيم"));
        seed.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        seed.setBackground(round(CARD, 16, LINE));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("ثيم مولّد من بذرة"))
                .setMessage(getString(R.string.nk_setupactivity_genthemes_msg))
                .setView(seed)
                .setPositiveButton(L.t("ولّد"), (d, w) -> {
                    String sd = seed.getText().toString().trim();
                    if (sd.isEmpty()) sd = "neokey";
                    try {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("neokey", 1);
                        o.put("name", "مولَّد: " + sd);
                        o.put("gen", sd);
                        o.put("bg", "#FF15161B"); o.put("key", "#FF2A2C33");
                        o.put("text", "#FFFFFFFF"); o.put("enter", "#FF2563EB");
                        int idx = Theme.importPack(this, o);
                        selectTheme(idx);
                        tab = 0;
                        refresh();
                        Toast.makeText(this, L.t("الثيم ولد من بذرك — صفر بايت صور"), Toast.LENGTH_SHORT).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, L.t("تعذر التوليد — جرّب عبارة أخرى"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(L.t("إلغاء"), null)
                .show();
    }

    private void newFromScratch() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("neokey", 1); o.put("name", "سمتي");
            o.put("bg", "#FF15161B"); o.put("key", "#FF2A2C33"); o.put("special", "#FF1F2127");
            o.put("text", "#FFFFFFFF"); o.put("enter", "#FF2563EB");
        } catch (Exception ignored) { }
        Theme.saveCustomSpec(this, o);
        selectTheme(Theme.CUSTOM_INDEX);
        tab = 0;
        refresh();
        Toast.makeText(this, L.t("ابدأ التصميم من المحرر تحت"), Toast.LENGTH_SHORT).show();
    }

    private org.json.JSONObject spec;

    private void setSpec(String k, Object v) {
        try {
            if (v == null) spec.remove(k); else spec.put(k, v);
            // keep derived colours in sync with the base ones
            if (k.equals("key") || k.equals("text") || k.equals("bg") || k.equals("enter")) {
                for (String d : new String[]{"keyPressed", "specialText", "shadow", "enterText", "icon", "hint", "popup", "popupText"})
                    spec.remove(d);
            }
        } catch (Exception ignored) { }
        Theme.saveCustomSpec(this, spec);
        refresh();
    }

    private void buildEditor() {
        spec = Theme.customSpec(this);
        Theme t = Theme.fromSpec(spec);
        Widgets.ThemePreview live = new Widgets.ThemePreview(this, t);
        if (t.photo) live.setPhoto(loadPhoto());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, (int) (160 * dp));
        llp.setMargins((int) (5 * dp), 0, (int) (5 * dp), (int) (6 * dp));
        content.addView(live, llp);

        EditText name = new EditText(this);
        name.setText(t.name);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(0xFF6B7280);
        name.setHint(L.t("اسم السمة"));
        name.setSingleLine(true);
        name.setTextSize(15);
        name.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        name.setBackground(round(CARD, 16, LINE));
        name.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                try { spec.put("name", e.toString().trim().isEmpty() ? "سمتي" : e.toString().trim()); } catch (Exception ignored) { }
                Theme.saveCustomSpec(SetupActivity.this, spec);
            }
        });
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));

        specColor("الخلفية", "bg", t.bg, false);
        specColor("تدرّج الخلفية", "bg2", t.bg2, true);
        specColor("أزرار الحروف", "key", t.key, false);
        specColor("الأزرار الخاصة (شفت، حذف، رموز)", "special", t.special, false);
        specColor("زر المسافة", "space", t.space, true);
        specColor("زر الإدخال", "enter", t.enter, false);
        specColor("لون الخط", "text", t.text, false);
        specColor("الحدود", "border", t.border, true);

        label("سماكة الحدود");
        final float[] bws = {0.6f, 1f, 1.6f, 2.4f};
        int bsel = 1;
        for (int i = 0; i < bws.length; i++) if (Math.abs(bws[i] - t.borderW) < 0.05f) bsel = i;
        content.addView(chips(new String[]{"رفيع جداً", "رفيع", "متوسط", "سميك"}, bsel, v -> setSpec("borderW", (double) bws[v])));

        label("الخط");
        content.addView(chips(Theme.FONTS, t.font, v -> setSpec("font", v)));

        LinearLayout card = cardBox();
        card.addView(specToggle("زجاج ثلاثي الأبعاد (خفيف)", "glass", t.glass));
        card.addView(divider());
        card.addView(specToggle("توهّج نيون", "neon", t.neon));
        card.addView(divider());
        Switch ph = new Switch(this);
        ph.setText("صورة مخصصة كخلفية");
        ph.setTextColor(Color.WHITE);
        ph.setTextSize(15);
        ph.setChecked(t.photo);
        ph.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        ph.setOnCheckedChangeListener((b, on) -> {
            if (on && loadPhoto() == null) { pickPhotoFor = true; pickPhoto(); b.setChecked(false); return; }
            setSpec("photo", on);
        });
        card.addView(ph);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = (int) (14 * dp);
        content.addView(card, clp);

        LinearLayout btns = new LinearLayout(this);
        TextView save = text("حفظ في مكتبتي", 14, Typeface.BOLD, Color.WHITE);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        save.setBackground(round(BLUE, 18, 0));
        Icons.withIcon(save, "check", Color.WHITE, (int) (18 * dp), false, (int) (6 * dp));
        save.setOnClickListener(v -> {
            int idx = Theme.addToLibrary(this, Theme.toSpec(Theme.fromSpec(spec)));
            selectTheme(idx);
            Toast.makeText(this, L.t(getString(R.string.nk_setupactivity_selecttheme_1)), Toast.LENGTH_SHORT).show();
            refresh();
        });
        TextView exp = text("تصدير", 14, Typeface.BOLD, Color.WHITE);
        exp.setGravity(Gravity.CENTER);
        exp.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        exp.setBackground(round(CARD, 18, LINE));
        Icons.withIcon(exp, "upload", Color.WHITE, (int) (18 * dp), false, (int) (6 * dp));
        exp.setOnClickListener(v -> exportDialog(Theme.fromSpec(spec)));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1.4f);
        b1.setMarginEnd((int) (8 * dp));
        btns.addView(save, b1);
        btns.addView(exp, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = (int) (16 * dp);
        content.addView(btns, blp);
    }

    private boolean pickPhotoFor;

    private void label(String s) {
        TextView t = text(s, 13, Typeface.BOLD, 0xFFB9BECA);
        t.setPadding((int) (6 * dp), (int) (14 * dp), (int) (6 * dp), (int) (6 * dp));
        content.addView(t);
    }

    private View specToggle(String label, final String key, boolean on) {
        Switch s = new Switch(this);
        s.setText(L.t(label));
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setChecked(on);
        s.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        s.setOnCheckedChangeListener((b, v) -> setSpec(key, v));
        return s;
    }

    private void specColor(String title, final String key, int cur, boolean allowNone) {
        label(title);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (8 * dp);
        row.setPadding(p, p, p, p);
        hs.setBackground(round(CARD, 26, LINE));
        int sz = (int) (34 * dp);
        // custom colour (HSV picker)
        TextView plus = text("", 15, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(plus, "tune", Color.WHITE, (int) (18 * dp), sz);
        plus.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFEF4444, 0xFFF59E0B, 0xFF22C55E, 0xFF3B82F6, 0xFFA855F7});
        pd.setShape(GradientDrawable.OVAL);
        plus.setBackground(pd);
        plus.setOnClickListener(v -> colorPicker(title, cur == 0 ? 0xFF3B82F6 : cur, c -> setSpec(key, Theme.hex(c))));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(sz, sz);
        pl.setMargins((int) (4 * dp), 0, (int) (6 * dp), 0);
        row.addView(plus, pl);
        if (allowNone) row.addView(swatch(0, cur == 0, sz, v -> setSpec(key, null)), swLp(sz));
        boolean found = cur == 0;
        for (int col : Theme.PALETTE) {
            if (col == 0x00FFFFFF && !key.equals("key") && !key.equals("special") && !key.equals("space")) continue;
            boolean on = col == cur;
            found |= on;
            final int cc = col;
            row.addView(swatch(col, on, sz, v -> setSpec(key, Theme.hex(cc))), swLp(sz));
        }
        if (!found) row.addView(swatch(cur, true, sz, null), 1, swLp(sz));
        hs.addView(row);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout.LayoutParams swLp(int sz) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
        return lp;
    }

    private TextView swatch(int col, boolean on, int sz, View.OnClickListener l) {
        TextView dot = text(col == 0 ? "بدون" : "", 9, Typeface.BOLD, Color.WHITE);
        dot.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int shown = col == 0 ? 0xFF262B36 : (col >>> 24) < 0x80 ? Theme.blend(0xFF1B1C20, col | 0xFF000000, Math.max(0.2f, (col >>> 24) / 255f)) : col;
        d.setColor(shown);
        d.setStroke((int) ((on ? 3 : 1) * dp), on ? (Theme.light(shown) ? 0xFF2563EB : Color.WHITE) : 0x33FFFFFF);
        dot.setBackground(d);
        if (on && col != 0) Icons.iconOnly(dot, "check", Theme.light(shown) ? 0xFF111111 : Color.WHITE, (int) (16 * dp), sz);
        if (l != null) dot.setOnClickListener(l);
        dot.setContentDescription(col == 0 ? "بدون لون" : "لون " + Theme.hex(col) + (on ? "، محدد" : ""));
        return dot;
    }

    /** Small HSV + opacity colour picker. */
    private void colorPicker(String title, int start, final IntPick done) {
        final float[] hsv = new float[3];
        Color.colorToHSV(start | 0xFF000000, hsv);
        final int[] alpha = {start >>> 24 == 0 ? 255 : start >>> 24};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * dp);
        box.setPadding(p, p, p, p);
        final View sample = new View(this);
        box.addView(sample, new LinearLayout.LayoutParams(-1, (int) (54 * dp)));
        final TextView hex = text("", 13, Typeface.BOLD, 0xFF111827);
        hex.setGravity(Gravity.CENTER);
        hex.setPadding(0, (int) (6 * dp), 0, (int) (4 * dp));
        box.addView(hex);
        final Runnable upd = () -> {
            int c = (alpha[0] << 24) | (Color.HSVToColor(hsv) & 0xFFFFFF);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(14 * dp);
            g.setColor(c);
            g.setStroke((int) dp, 0x33000000);
            sample.setBackground(g);
            hex.setText(Theme.hex(c));
        };
        String[] names = {"اللون", "التشبّع", "السطوع", "الشفافية"};
        int[] max = {360, 100, 100, 255};
        int[] val = {(int) hsv[0], (int) (hsv[1] * 100), (int) (hsv[2] * 100), alpha[0]};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView l = text(names[i], 12, Typeface.BOLD, 0xFF4B5563);
            l.setPadding(0, (int) (10 * dp), 0, 0);
            box.addView(l);
            SeekBar sb = new SeekBar(this);
            sb.setMax(max[i]);
            sb.setProgress(val[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int v, boolean u) {
                    if (k == 0) hsv[0] = v; else if (k == 1) hsv[1] = v / 100f; else if (k == 2) hsv[2] = v / 100f; else alpha[0] = v;
                    upd.run();
                }
                @Override public void onStartTrackingTouch(SeekBar b) { }
                @Override public void onStopTrackingTouch(SeekBar b) { }
            });
            box.addView(sb);
        }
        upd.run();
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(L.t("تم"), (d, w) -> done.pick((alpha[0] << 24) | (Color.HSVToColor(hsv) & 0xFFFFFF)))
                .setNegativeButton(L.t("إلغاء"), null)
                .show();
    }

    // ---------------------------------------------------------------- import / export (design packs)

    private static final int EXPORT = 42, IMPORT = 43;
    private String pendingExport;

    private void exportDialog(Theme t) {
        final String json;
        try { json = Theme.exportPack(this, t).toString(2); } catch (Exception e) { return; }
        String[] items = {"حفظ كملف (.neokey)", "مشاركة", "نسخ"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("تصدير «") + t.name + "»")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        pendingExport = json;
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/json");
                        i.putExtra(Intent.EXTRA_TITLE, t.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".neokey");
                        try { startActivityForResult(i, EXPORT); } catch (Exception e) { copyText(json); }
                    } else if (w == 1) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, "سمة NeoKey: " + t.name);
                        i.putExtra(Intent.EXTRA_TEXT, json);
                        startActivity(Intent.createChooser(i, "مشاركة السمة"));
                    } else copyText(json);
                }).show();
    }

    private void copyText(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("NeoKey theme", s));
        Toast.makeText(this, L.t("انسخت حزمة السمة"), Toast.LENGTH_SHORT).show();
    }

    private void importDialog() {
        String[] items = {"من ملف", "من الحافظة (نص منسوخ)"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("استيراد حزمة تصميم"))
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        try { startActivityForResult(i, IMPORT); } catch (Exception e) { Toast.makeText(this, L.t("ما في مدير ملفات"), Toast.LENGTH_SHORT).show(); }
                    } else {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        CharSequence s = cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0
                                ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
                        importJson(s == null ? "" : s.toString());
                    }
                }).show();
    }

    private void importJson(String s) {
        try {
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            org.json.JSONObject o = new org.json.JSONObject(s.substring(a, b + 1));
            Theme.importPack(this, o);
            tab = 0;
            refresh();
            Toast.makeText(this, L.t("تم استيراد «") + o.optString("name", "السمة") + "» وتفعيلها", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, L.t("الملف مو حزمة سمة صالحة"), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDoc(int req, Uri u) {
        try {
            if (req == EXPORT) {
                java.io.OutputStream out = getContentResolver().openOutputStream(u);
                out.write(pendingExport.getBytes("UTF-8"));
                out.close();
                Toast.makeText(this, L.t("انحفظ ملف السمة"), Toast.LENGTH_SHORT).show();
            } else {
                InputStream in = getContentResolver().openInputStream(u);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 200000) bo.write(buf, 0, n);
                in.close();
                importJson(bo.toString("UTF-8"));
            }
        } catch (Exception e) {
            Toast.makeText(this, L.t("تعذر الوصول للملف"), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- day / night pickers

    private View themePicker(final String pref, int def) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        int p = (int) (8 * dp);
        row.setPadding(p, p, p, p);
        hs.setBackground(round(CARD, 22, LINE));
        int cur = Theme.prefs(this).getInt(pref, def);
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (int i = 0; i < Theme.ALL.length; i++) if (!Theme.ALL[i].photo) ids.add(i);
        int libN = Theme.library(this).length();
        for (int i = 0; i < libN; i++) ids.add(Theme.LIB_BASE + i);
        for (final int idx : ids) {
            Theme t = Theme.resolve(this, idx);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            int pp = (int) (4 * dp);
            col.setPadding(pp, pp, pp, pp);
            col.setBackground(idx == cur ? round(0xFF1E2A4A, 14, BLUE, 2) : null);
            Widgets.ThemePreview tp = new Widgets.ThemePreview(this, t);
            col.addView(tp, new LinearLayout.LayoutParams((int) (84 * dp), (int) (58 * dp)));
            TextView n = text(t.name, 11, Typeface.BOLD, idx == cur ? Color.WHITE : 0xFF9AA0AA);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setGravity(Gravity.CENTER);
            n.setPadding(0, (int) (4 * dp), 0, 0);
            col.addView(n, new LinearLayout.LayoutParams((int) (84 * dp), -2));
            col.setOnClickListener(v -> { Theme.prefs(this).edit().putInt(pref, idx).apply(); refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            row.addView(col, lp);
        }
        hs.addView(row);
        return hs;
    }

    private View slider(String title, final String key, int min, int max, int cur, final String unit) {
        LinearLayout box = cardBox();
        LinearLayout head = new LinearLayout(this);
        TextView t = text(title, 15, Typeface.NORMAL, Color.WHITE);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = text(cur + unit, 14, Typeface.BOLD, 0xFF93B4FF);
        head.addView(val);
        head.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(head);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean u) {
                val.setText((v + min) + unit);
                Theme.prefs(SetupActivity.this).edit().putInt(key, v + min).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        sb.setPadding((int) (4 * dp), (int) (12 * dp), (int) (4 * dp), (int) (8 * dp));
        box.addView(sb);
        return box;
    }

    // ---------------------------------------------------------------- photo background

    private static final int PICK = 41;
    private Bitmap photoCache;
    private long photoStamp = -1;

    private Bitmap loadPhoto() {
        File f = new File(getFilesDir(), "bg.jpg");
        long st = f.exists() ? f.lastModified() : 0;
        if (st != photoStamp) {
            photoStamp = st;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            photoCache = st == 0 ? null : BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        }
        return photoCache;
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try { startActivityForResult(Intent.createChooser(i, "اختر صورة الخلفية"), PICK); }
        catch (Exception e) { Toast.makeText(this, L.t("ما في تطبيق صور"), Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req == EXPORT || req == IMPORT) && res == RESULT_OK && data != null && data.getData() != null) {
            handleDoc(req, data.getData());
            return;
        }
        if (req == AVATAR_PICK) {
            if (res == RESULT_OK && data != null && data.getData() != null) saveAvatar(data.getData());
            return;
        }
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1080 && o.outHeight / (sample * 2) >= 700) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            if (b == null) throw new Exception();
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "bg.jpg"));
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            try {
                FileOutputStream uo = new FileOutputStream(userPhotoFile());
                b.compress(Bitmap.CompressFormat.JPEG, 90, uo);
                uo.close();
            } catch (Exception ignored) {}
            if (!pickPhotoFor) Theme.prefs(this).edit().putInt("backdrop", -1).putInt("anim_bg", -1).apply();
            if (pickPhotoFor) {
                pickPhotoFor = false;
                org.json.JSONObject cs = Theme.customSpec(this);
                cs.put("photo", true);
                Theme.saveCustomSpec(this, cs);
                selectTheme(Theme.CUSTOM_INDEX);
                themeView = 2;
            } else { selectTheme(Theme.PHOTO_INDEX); themeView = 1; }
            tab = 0;
            refresh();
            Toast.makeText(this, L.t("تم تعيين الخلفية"), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, L.t("تعذر فتح الصورة"), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- personal avatar

    private static final int AVATAR_PICK = 44;

    private File avatarFile() { return new File(getFilesDir(), "avatar.png"); }

    private void buildAvatarSection() {
        section("الأسهم والصورة الرقمية");
        LinearLayout card = cardBox();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));

        // avatar preview: gradient ring + gap + circular photo
        FrameLayout av = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BLUE, 0xFF8B5CF6, 0xFF22D3EE});
        ring.setShape(GradientDrawable.OVAL);
        av.setBackground(ring);
        int rp = (int) (2.5f * dp);
        av.setPadding(rp, rp, rp, rp);
        android.widget.ImageView img = new android.widget.ImageView(this);
        GradientDrawable gap = new GradientDrawable();
        gap.setShape(GradientDrawable.OVAL);
        gap.setColor(CARD);
        img.setBackground(gap);
        int gp = (int) (2.5f * dp);
        img.setPadding(gp, gp, gp, gp);
        Bitmap cur = null;
        try { if (avatarFile().exists()) cur = BitmapFactory.decodeFile(avatarFile().getAbsolutePath()); } catch (Throwable ignored) {}
        if (cur != null) img.setImageBitmap(circle(cur));
        else {
            android.graphics.drawable.Drawable d = Icons.tinted(this, "person", 0xFF8A90A0, (int) (32 * dp));
            if (d != null) img.setImageDrawable(d);
            int ip = (int) (14 * dp);
            img.setPadding(ip, ip, ip, ip);
        }
        av.addView(img, new FrameLayout.LayoutParams(-1, -1));
        int sz = (int) (64 * dp);
        row.addView(av, new LinearLayout.LayoutParams(sz, sz));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        txt.addView(text(cur != null ? "بصمتك الرقمية" : "أضف صورتك", 16, Typeface.BOLD, Color.WHITE));
        TextView sub = text(getString(R.string.nk_setupactivity_section_3), 12, Typeface.NORMAL, 0xFF8A90A0);
        sub.setPadding(0, (int) (3 * dp), 0, 0);
        txt.addView(sub);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row);

        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, (int) (2 * dp), 0, (int) (10 * dp));
        TextView pick = smallBtn(cur != null ? "تغيير الصورة" : "اختيار صورة", true);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            try { startActivityForResult(Intent.createChooser(i, L.t("اختر صورتك الشخصية")), AVATAR_PICK); }
            catch (Exception e) { Toast.makeText(this, L.t("ما في تطبيق صور"), Toast.LENGTH_SHORT).show(); }
        });
        btns.addView(pick, new LinearLayout.LayoutParams(0, (int) (44 * dp), 1f));
        if (cur != null) {
            TextView rm = smallBtn("إزالة", false);
            rm.setOnClickListener(v -> {
                avatarFile().delete();
                refresh();
                Toast.makeText(this, L.t("تمت إزالة الصورة"), Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, (int) (44 * dp), 1f);
            rl.setMarginStart((int) (10 * dp));
            btns.addView(rm, rl);
        }
        card.addView(btns);
        card.addView(divider());
        card.addView(toggle("إظهار صورتي في شريط الكيبورد", "avatar_show", true));
        content.addView(card);
        buildArrowsCard();
        buildClipCard();
    }

    private static final int[] CLIP_MAX = {30, 50, 100, 200, 500, 1000};
    private static final int[] CLIP_DAYS = {0, 1, 7, 30, 90};

    private void buildClipCard() {
        LinearLayout card = cardBox();
        card.addView(text("الحافظة", 16, Typeface.BOLD, Color.WHITE));
        final TextView stats = text("…", 12, Typeface.NORMAL, 0xFF8A90A0);
        stats.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        card.addView(stats);
        ClipStore.get(this).run(r -> new long[]{r.totalCount(), r.pinCount(), r.storageBytes()}, v -> {
            if (v == null) return;
            String size = v[2] < 1024 * 1024 ? (v[2] / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", v[2] / 1048576.0);
            stats.setText(L.t("العناصر: ") + v[0] + "  •  " + L.t("المثبّتة: ") + v[1] + "/" + ClipRepo.MAX_PINS + "  •  " + size);
        });
        card.addView(divider());
        card.addView(text("عدد العناصر المؤقتة المحفوظة", 13, Typeface.BOLD, 0xFFDDE1EA));
        int curMax = Theme.prefs(this).getInt("clip_max", 50), selMax = 1;
        String[] mx = new String[CLIP_MAX.length];
        for (int i = 0; i < mx.length; i++) { mx[i] = String.valueOf(CLIP_MAX[i]); if (CLIP_MAX[i] == curMax) selMax = i; }
        card.addView(chips(mx, selMax, i -> { Theme.prefs(this).edit().putInt("clip_max", CLIP_MAX[i]).apply(); refresh(); }));
        card.addView(text("مدة الاحتفاظ بالعناصر المؤقتة", 13, Typeface.BOLD, 0xFFDDE1EA));
        int curDays = Theme.prefs(this).getInt("clip_days", 0), selDays = 0;
        for (int i = 0; i < CLIP_DAYS.length; i++) if (CLIP_DAYS[i] == curDays) selDays = i;
        card.addView(chips(new String[]{L.t("بدون حد"), L.t("يوم"), L.t("أسبوع"), L.t("شهر"), L.t("٣ أشهر")}, selDays,
                i -> { Theme.prefs(this).edit().putInt("clip_days", CLIP_DAYS[i]).apply(); refresh(); }));
        TextView hint = text(getString(R.string.nk_setupactivity_buildclipcard_1), 12, Typeface.NORMAL, 0xFF8A90A0);
        hint.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        card.addView(hint);
        card.addView(divider());
        card.addView(toggle(getString(R.string.nk_setupactivity_buildclipcard_2), "clip_sensitive", false));
        card.addView(divider());
        LinearLayout io = new LinearLayout(this);
        io.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
        String[] ioL = {"تصدير الحافظة", "استيراد"};
        for (int i = 0; i < 2; i++) {
            final int m = i;
            TextView b = text(ioL[i], 14, Typeface.BOLD, Color.WHITE);
            b.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == 0 ? 0xFF2563EB : 0xFF1F2430);
            g.setCornerRadius(12 * dp);
            b.setBackground(g);
            b.setOnClickListener(v -> startActivity(new Intent(this, ClipIOActivity.class).putExtra(ClipIOActivity.EXTRA_MODE, m)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, (int) (46 * dp), 1f);
            if (i == 0) bl.setMarginEnd((int) (8 * dp));
            io.addView(b, bl);
        }
        card.addView(io);
        TextView ioHint = text(getString(R.string.nk_setupactivity_buildclipcard_3), 12, Typeface.NORMAL, 0xFF8A90A0);
        ioHint.setPadding(0, (int) (2 * dp), 0, (int) (6 * dp));
        card.addView(ioHint);
        card.addView(divider());
        TextView clearTemp = text("مسح السجل المؤقت", 14, Typeface.BOLD, 0xFF60A5FA);
        clearTemp.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        clearTemp.setGravity(Gravity.CENTER);
        clearTemp.setOnClickListener(v -> ClipStore.get(this).run(r -> r.clearTemporary(), n -> {
            Toast.makeText(this, L.t(getString(R.string.nk_setupactivity_buildclipcard_4)), Toast.LENGTH_SHORT).show();
            refresh();
        }));
        card.addView(clearTemp);
        TextView clearAll = text("حذف كل الحافظة مع المثبّتة", 14, Typeface.BOLD, 0xFFF87171);
        clearAll.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("حذف كل الحافظة؟"))
                .setMessage(L.t(getString(R.string.nk_setupactivity_refresh_1)))
                .setPositiveButton(L.t("احذف الكل"), (d, w) -> ClipStore.get(this).run(r -> r.clearAllClips(), n -> {
                    Toast.makeText(this, L.t("انحذفت الحافظة كاملة"), Toast.LENGTH_SHORT).show();
                    refresh();
                }))
                .setNegativeButton(L.t("إلغاء"), null)
                .show());
        card.addView(clearAll);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (10 * dp);
        content.addView(card, lp);
    }

    private void buildArrowsCard() {
        LinearLayout card = cardBox();
        Switch on = (Switch) toggle("إظهار السهمين في شريط الكيبورد", "arrows", true);
        on.setOnCheckedChangeListener((b, v) -> { Theme.prefs(this).edit().putBoolean("arrows", v).apply(); refresh(); });
        card.addView(on);
        TextView hint = text(getString(R.string.nk_setupactivity_buildarrowscard_1), 12, Typeface.NORMAL, 0xFF8A90A0);
        hint.setPadding(0, 0, 0, (int) (8 * dp));
        card.addView(hint);
        if (Features.on(this)) {
            card.addView(divider());
            card.addView(arrowRow(true));
            card.addView(divider());
            card.addView(arrowRow(false));
            card.addView(divider());
            TextView reset = text("استرجاع المميزات الافتراضية", 14, Typeface.BOLD, 0xFF60A5FA);
            reset.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
            reset.setGravity(Gravity.CENTER);
            reset.setOnClickListener(v -> {
                Theme.prefs(this).edit().remove("arrow_r").remove("arrow_l").apply();
                refresh();
            });
            card.addView(reset);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (10 * dp);
        content.addView(card, lp);
    }

    private View arrowRow(boolean right) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        TextView title = text(right ? "السهم الأيمن ‹" : "السهم الأيسر ›", 14, Typeface.BOLD, Color.WHITE);
        title.setPadding(0, 0, 0, (int) (8 * dp));
        box.addView(title);
        LinearLayout row = new LinearLayout(this);
        // slots read outward from the arrow, like they open on the keyboard
        row.setLayoutDirection(right ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        final int[] ids = right ? Features.right(this) : Features.left(this);
        for (int i = 0; i < ids.length; i++) {
            final int slot = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding((int) (2 * dp), (int) (8 * dp), (int) (2 * dp), (int) (8 * dp));
            cell.setBackground(round(0xFF1B1F2A, 14, LINE));
            android.widget.ImageView ic = new android.widget.ImageView(this);
            android.graphics.drawable.Drawable d = Icons.tinted(this, Features.icon(ids[i]), 0xFFDDE1EA, (int) (22 * dp));
            if (d != null) ic.setImageDrawable(d);
            cell.addView(ic, new LinearLayout.LayoutParams((int) (22 * dp), (int) (22 * dp)));
            TextView nm = text(Features.label(ids[i]), 10, Typeface.NORMAL, 0xFFB9BECA);
            nm.setGravity(Gravity.CENTER);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setPadding(0, (int) (4 * dp), 0, 0);
            cell.addView(nm, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(v -> pickFeature(right, ids, slot));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) cl.setMarginStart((int) (6 * dp));
            row.addView(cell, cl);
        }
        box.addView(row);
        return box;
    }

    private void pickFeature(boolean right, int[] ids, int slot) {
        String[] names = new String[Features.ALL.length];
        for (int i = 0; i < names.length; i++) {
            int id = (int) Features.ALL[i][0];
            boolean used = false;
            for (int j = 0; j < ids.length; j++) if (j != slot && ids[j] == id) used = true;
            names[i] = Features.label(id) + (id == ids[slot] ? "  ✓" : used ? "  •" : "");
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("اختر الميزة للخانة ") + (slot + 1))
                .setItems(names, (dlg, which) -> {
                    int id = (int) Features.ALL[which][0];
                    // picking a feature already in another slot swaps the two
                    for (int j = 0; j < ids.length; j++) if (j != slot && ids[j] == id) ids[j] = ids[slot];
                    ids[slot] = id;
                    Features.save(this, right, ids);
                    refresh();
                })
                .setNegativeButton(L.t("إلغاء"), null)
                .show();
    }

    private TextView smallBtn(String label, boolean primary) {
        TextView b = text(label, 14, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(BLUE, 14, 0) : round(CARD, 14, LINE));
        return b;
    }

    private void saveAvatar(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            if (b == null) throw new Exception();
            int n = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - n) / 2, (b.getHeight() - n) / 2, n, n);
            Bitmap out = Bitmap.createScaledBitmap(sq, 256, 256, true);
            FileOutputStream fo = new FileOutputStream(avatarFile());
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            Theme.prefs(this).edit().putBoolean("avatar_show", true).apply();
            refresh();
            Toast.makeText(this, L.t("تم تعيين صورتك"), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, L.t("تعذر فتح الصورة"), Toast.LENGTH_SHORT).show();
        }
    }

    private void buildSettings() {
        // stats
        android.content.SharedPreferences pr = Theme.prefs(this);
        LinearLayout stats = new LinearLayout(this);
        stats.setBackground(Glass.panel(dp, 22, true));
        int sp = (int) (14 * dp);
        stats.setPadding(sp, sp, sp, sp);
        stats.addView(stat(String.valueOf(pr.getInt("stat_words", 0)), "كلمة كتبتها"), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(String.valueOf(pr.getInt("stat_chars", 0)), "حرف"), new LinearLayout.LayoutParams(0, -2, 1f));
        stats.addView(stat(String.valueOf(new File(getFilesDir(), "user_ar.txt").exists() ? countLines("user_ar.txt") + countLines("user_en.txt") : 0), "كلمة تعلّمها"), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = (int) (20 * dp);
        content.addView(stats, slp);

        section(L.en ? "App language" : "لغة التطبيق");
        content.addView(chips(new String[]{"العربية", "English"}, L.en ? 1 : 0, i -> {
            Theme.prefs(this).edit().putBoolean("ui_en", i == 1).apply();
            L.init(this);
            recreate();
        }));

        buildAvatarSection();

        section("الكتابة الذكية");
        LinearLayout card = cardBox();
        card.addView(toggle("اقتراح الكلمات", "suggest", true));
        card.addView(divider());
        card.addView(toggle("تعلّم كلماتي الجديدة من كتابتي", "learn", true));
        card.addView(divider());
        card.addView(toggle("مسافتين = نقطة", "dblspace", true));
        card.addView(divider());
        card.addView(toggle(getString(R.string.nk_setupactivity_section_4), "autocorrect", false));
        card.addView(divider());
        // item B week 2: الفاصلة المنقوطة والأرقام العربية — one pref for both
        // layout/script mismatch classes (semicolon + script-adjacent numerals).
        card.addView(toggle(getString(R.string.nk_setupactivity_section_5), "ar_punct", true));
        card.addView(divider());
        card.addView(toggle("الكتابة بالسحب على الحروف", "glide", true));
        card.addView(divider());
        // C2 (الأسبوع 6): التبديل الذكي فصحى/لهجة — افتراضي تلقائي، الكشف لحظي بلا تخزين
        card.addView(dialectRow());
        card.addView(divider());
        TextView privNote = text(getString(R.string.nk_setupactivity_section_6), 13, Typeface.NORMAL, 0xFF8A8FA3);
        privNote.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        card.addView(privNote);
        content.addView(card);

        section("الإحساس");
        LinearLayout card2 = cardBox();
        card2.addView(toggle("الاهتزاز عند الكتابة", "haptics", true));
        card2.addView(divider());
        card2.addView(toggle("صوت الأزرار", "sound", false));
        card2.addView(divider());
        card2.addView(toggle("معاينة الحرف عند الضغط", "popup", true));
        card2.addView(divider());
        card2.addView(toggle("صف الأرقام", "numrow", true));
        card2.addView(divider());
        card2.addView(toggle(getString(R.string.nk_setupactivity_section_7), "numpad", true));
        content.addView(card2);

        section("المظهر والحركة");
        LinearLayout card3 = cardBox();
        card3.addView(toggle("تأثير إضاءة عند الضغط", "pressglow", true));
        card3.addView(divider());
        card3.addView(toggle("حركات انتقال سلسة", "anims", true));
        card3.addView(divider());
        card3.addView(toggle(getString(R.string.nk_setupactivity_signature), "signature", true));
        card3.addView(toggle(getString(R.string.nk_setupactivity_suggrow), "sugg_row", true));
        content.addView(card3);

        section("تخطيط الكيبورد");
        content.addView(seg(new String[]{"قياسي", "برمجة", "ألعاب", "مراسلات"}, pr.getInt("kblayout", 0), "kblayout"));
        TextView lh = text(new String[]{"التخطيط العادي.", getString(R.string.nk_setupactivity_section_8),
                getString(R.string.nk_setupactivity_section_9),
                getString(R.string.nk_setupactivity_section_10)}[Math.max(0, Math.min(3, pr.getInt("kblayout", 0)))],
                12, Typeface.NORMAL, 0xFF8A90A0);
        lh.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), 0);
        content.addView(lh);

        section("سمات نهارية وليلية تلقائية");
        LinearLayout card4 = cardBox();
        Switch at = (Switch) toggle("تبديل السمة تلقائياً", "autotheme", false);
        at.setOnCheckedChangeListener((b, on) -> { Theme.prefs(this).edit().putBoolean("autotheme", on).apply(); refresh(); });
        card4.addView(at);
        content.addView(card4);
        if (pr.getBoolean("autotheme", false)) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = (int) (8 * dp);
            content.addView(seg(new String[]{"حسب وضع الجوال", "حسب الوقت"}, pr.getInt("automode", 0), "automode"), sl);
            if (pr.getInt("automode", 0) == 1) {
                LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(-1, -2);
                s2.topMargin = (int) (8 * dp);
                content.addView(slider("يبدأ النهار الساعة", "day_from", 0, 23, pr.getInt("day_from", 6), ":00"), s2);
                LinearLayout.LayoutParams s3 = new LinearLayout.LayoutParams(-1, -2);
                s3.topMargin = (int) (8 * dp);
                content.addView(slider("يبدأ الليل الساعة", "night_from", 0, 23, pr.getInt("night_from", 18), ":00"), s3);
            }
            label("سمة النهار");
            content.addView(themePicker("day_theme", 0));
            label("سمة الليل");
            content.addView(themePicker("night_theme", 1));
        }

        section("قوة الاهتزاز");
        content.addView(seg(new String[]{"ناعم", "متوسط", "قوي", "مخصص"}, pr.getInt("vib", 0), "vib"));
        if (pr.getInt("vib", 0) == 3) {
            content.addView(slider("مدة الاهتزاز", "vibms", 3, 80, pr.getInt("vibms", 20), " ms"), new LinearLayout.LayoutParams(-1, -2) {{ topMargin = (int) (8 * dp); }});
            content.addView(slider("شدة الاهتزاز", "vibamp", 1, 255, pr.getInt("vibamp", 160), ""), new LinearLayout.LayoutParams(-1, -2) {{ topMargin = (int) (8 * dp); }});
        }
        section("سرعة الضغط المطوّل");
        content.addView(seg(new String[]{"سريع", "عادي", "بطيء"}, pr.getInt("lpdelay", 1), "lpdelay"));
        section("الحجم والمقاسات");
        content.addView(slider("ارتفاع الكيبورد", "hpct", 75, 140, Theme.heightPct(this), "٪"));
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(-1, -2);
        w1.topMargin = (int) (8 * dp);
        content.addView(slider("عرض الكيبورد", "wpct", 70, 100, pr.getInt("wpct", 100), "٪"), w1);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(-1, -2);
        w2.topMargin = (int) (8 * dp);
        content.addView(slider("استدارة الأزرار (0 = حسب الشكل)", "radius", 0, 30, pr.getInt("radius", 0), ""), w2);
        section("المسافة بين الأزرار");
        content.addView(seg(new String[]{"ملتصقة", "عادية", "واسعة", "أوسع"}, pr.getInt("keygap", 1), "keygap"));
        section("اللون المميز");
        content.addView(accentRow());
        section("حجم خط الأزرار");
        content.addView(seg(new String[]{"صغير", "عادي", "كبير", "كبير جداً"}, pr.getInt("keysize", 1), "keysize"));
        section("شكل الأزرار");
        content.addView(seg(new String[]{"دائري", "مربع", "كبسولة", "ناعم"}, pr.getInt("keyshape", 0), "keyshape"));
        section("السحب على المسافة");
        content.addView(seg(new String[]{"تغيير اللغة", "تحريك المؤشر"}, pr.getInt("swipe", 0), "swipe"));
        section("شكل الأرقام");
        content.addView(seg(new String[]{"123", "١٢٣"}, pr.getInt("digits", 0), "digits"));
        section("وضع اليد الواحدة");
        content.addView(seg(new String[]{"إيقاف", "يمين", "يسار"}, Theme.oneHand(this), "onehand"));
        content.addView(slider("عرض وضع اليد الواحدة", "onehandw", 60, 92, pr.getInt("onehandw", 82), "٪"), new LinearLayout.LayoutParams(-1, -2) {{ topMargin = (int) (8 * dp); }});

        section("إمكانية الوصول");
        LinearLayout a11y = cardBox();
        a11y.addView(toggle(getString(R.string.nk_setupactivity_section_11), "contrast", false));
        a11y.addView(divider());
        a11y.addView(toggle("مفاتيح كبيرة", "bigkeys", false));
        a11y.addView(divider());
        Switch rm = (Switch) toggle("تقليل الحركة والمؤثرات", "reducemotion", false);
        rm.setOnCheckedChangeListener((b, on) -> { Theme.prefs(this).edit().putBoolean("reducemotion", on).apply(); bnPause(on); });
        a11y.addView(rm);
        a11y.addView(divider());
        a11y.addView(toggle(getString(R.string.nk_setupactivity_section_12), "cues", true));
        content.addView(a11y);
        if (pr.getBoolean("contrast", false)) {
            content.addView(seg(new String[]{"تباين داكن", "تباين فاتح"}, pr.getInt("contrast_mode", 0), "contrast_mode"), new LinearLayout.LayoutParams(-1, -2) {{ topMargin = (int) (8 * dp); }});
        }
        TextView sr = text(getString(R.string.nk_setupactivity_section_13),
                12, Typeface.NORMAL, 0xFF8A90A0);
        sr.setPadding((int) (6 * dp), (int) (8 * dp), (int) (6 * dp), 0);
        content.addView(sr);

        TextView reset = text("مسح الكلمات المتعلّمة", 14, Typeface.BOLD, 0xFFF87171);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        reset.setBackground(round(CARD, 22, 0xFF4A1F24));
        reset.setOnClickListener(v -> {
            new File(getFilesDir(), "user_ar.txt").delete();
            new File(getFilesDir(), "user_en.txt").delete();
            new File(getFilesDir(), "bigram_ar.txt").delete();
            new File(getFilesDir(), "bigram_en.txt").delete();
            Theme.prefs(this).edit().putBoolean("reset_learn", true).apply();
            Toast.makeText(this, L.t("تم مسح الكلمات المتعلّمة"), Toast.LENGTH_SHORT).show();
            showTab();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = (int) (24 * dp);
        content.addView(reset, rlp);

        TextView tip = text(getString(R.string.nk_setupactivity_showtab_1),
                13, Typeface.NORMAL, 0xFF8A90A0);
        tip.setPadding((int) (4 * dp), (int) (16 * dp), (int) (4 * dp), 0);
        content.addView(tip);

        // المرحلة 2، بند A2 (الأسبوع 2): الحساب والمزامنة المشفرة طرف-لطرف —
        // صف واحد يفتح الشاشة المخصصة؛ الافتراضي مغلق والإذن منفصل داخل الشاشة.
        section("الحساب والمزامنة");
        LinearLayout syncCard = cardBox();
        TextView syncRow = text(getString(R.string.nk_setupactivity_section_14), 14, Typeface.BOLD, 0xFF93B4FF);
        syncRow.setMinHeight((int) (48 * dp));
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        syncRow.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
        syncCard.addView(syncRow);
        syncCard.addView(divider());
        TextView syncNote = text(getString(R.string.nk_setupactivity_section_15),
                13, Typeface.NORMAL, 0xFF8A8FA3);
        syncNote.setLineSpacing(0, 1.2f);
        syncNote.setPadding(0, (int) (6 * dp), 0, (int) (4 * dp));
        syncCard.addView(syncNote);
        content.addView(syncCard);

        section("الخصوصية");
        LinearLayout pc = cardBox();
        TextView pt = text(getString(R.string.nk_setupactivity_section_16)
                + getString(R.string.nk_setupactivity_section_17)
                + getString(R.string.nk_setupactivity_section_18), 13, Typeface.NORMAL, 0xFFB9BECA);
        pt.setLineSpacing(0, 1.25f);
        pt.setPadding(0, (int) (6 * dp), 0, (int) (8 * dp));
        pc.addView(pt);
        pc.addView(divider());
        // Shield row (roadmap §11 layer 6: "فحوصات محدودة وموثقة علناً" — the only
        // user-facing surface; silent everywhere else, no toasts while typing).
        Integrity.State st = Shield.current();
        int shColor = st == Integrity.State.OK ? 0xFF7BC99A
                : (st == Integrity.State.MISMATCH ? 0xFFE0A15A : 0xFF8A90A0);
        TextView sh = text(Shield.statusLine(this), 13, Typeface.NORMAL, shColor);
        sh.setPadding(0, (int) (6 * dp), 0, (int) (8 * dp));
        sh.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("درع الحماية")
                .setMessage(Shield.details(this))
                .setPositiveButton("حسناً", null).show());
        pc.addView(sh);
        pc.addView(divider());
        pc.addView(toggle(getString(R.string.nk_setupactivity_section_19), "incognito", false));
        pc.addView(divider());
        Switch perfSw = new Switch(this);
        perfSw.setText(L.t("قياس الأداء والأعطال محلياً"));
        perfSw.setTextColor(Color.WHITE);
        perfSw.setTextSize(15);
        perfSw.setMinHeight((int) (52 * dp));
        Perf.init(this);
        perfSw.setChecked(Perf.enabled());
        perfSw.setOnCheckedChangeListener((bv, on) -> Perf.setEnabled(on));
        pc.addView(perfSw);
        pc.addView(divider());
        TextView forget = text(getString(R.string.nk_setupactivity_section_20), 14, Typeface.BOLD, 0xFFF87171);
        forget.setMinHeight((int) (48 * dp));
        forget.setGravity(Gravity.CENTER_VERTICAL);
        forget.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage(L.t(getString(R.string.nk_setupactivity_section_21)))
                .setPositiveButton(L.t("مسح"), (d, w) -> {
                    for (String n : new String[]{"user_ar.txt", "user_en.txt", "bigram_ar.txt", "bigram_en.txt"}) new java.io.File(getFilesDir(), n).delete();
                    Theme.prefs(this).edit().remove("nocorrect").putBoolean("dict_reload", true).apply();
                    android.widget.Toast.makeText(this, L.t("تم المسح"), android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.t("إلغاء"), null).show());
        pc.addView(forget);
        pc.addView(divider());
        // item B week 2: قوائم استثناء ذكية موصولة بالإعدادات — the never-correct
        // list (nocorrect) with its own management surface.
        final TextView exc = text(exclLabel(), 14, Typeface.BOLD, 0xFF93B4FF);
        exc.setMinHeight((int) (48 * dp));
        exc.setGravity(Gravity.CENTER_VERTICAL);
        exc.setOnClickListener(v -> showExceptionsDialog(exc));
        pc.addView(exc);
        content.addView(pc);

        section("قاموسي والنسخ الاحتياطي");
        LinearLayout tools = new LinearLayout(this);
        TextView dictB = text("قاموسي الشخصي", 14, Typeface.BOLD, Color.WHITE);
        dictB.setGravity(Gravity.CENTER);
        dictB.setMinHeight((int) (52 * dp));
        dictB.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(dictB, "book", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        dictB.setOnClickListener(v -> startActivity(new Intent(this, DictActivity.class)));
        TextView bakB = text("النسخ الاحتياطي", 14, Typeface.BOLD, Color.WHITE);
        bakB.setGravity(Gravity.CENTER);
        bakB.setMinHeight((int) (52 * dp));
        bakB.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(bakB, "download", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        bakB.setOnClickListener(v -> startActivity(new Intent(this, BackupActivity.class)));
        LinearLayout.LayoutParams tl1 = new LinearLayout.LayoutParams(0, -2, 1f);
        tl1.setMarginEnd((int) (5 * dp));
        LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(0, -2, 1f);
        tl2.setMarginStart((int) (5 * dp));
        tools.addView(dictB, tl1);
        tools.addView(bakB, tl2);
        content.addView(tools);
        LinearLayout adk = cardBox();
        adk.addView(toggle("أذكار وأدعية في لوحة العبارات", "adhkar", true));
        LinearLayout.LayoutParams adl = new LinearLayout.LayoutParams(-1, -2);
        adl.topMargin = (int) (10 * dp);
        content.addView(adk, adl);

        // Beta feedback channel (roadmap item D: play/beta-program.md §1 — the loop
        // promised by the store listing, wired to the tested Feedback contract).
        section("تواصل ومختبر بيتا");
        LinearLayout fbc = cardBox();
        // W57 batch-2: the survey/join wording rides the keyboard's engines-load
        // thread (assets/feedback_copy.txt — Feedback.init is strict; a corrupt
        // asset leaves the banks EMPTY — fail-closed, the mail row is a code
        // constant and never dies).
        TextView fbt = text(getString(R.string.nk_setupactivity_section_22)
                + getString(R.string.nk_setupactivity_section_23),
                13, Typeface.NORMAL, 0xFFB9BECA);
        fbt.setLineSpacing(0, 1.25f);
        fbt.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
        fbc.addView(fbt);
        TextView mailB = text(L.t("نسخ بريد الدعم وفتح الرسالة"), 14, Typeface.BOLD, Color.WHITE);
        mailB.setGravity(Gravity.CENTER_VERTICAL);
        mailB.setMinHeight((int) (52 * dp));
        mailB.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(mailB, "send", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        mailB.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("NeoKey", Feedback.SUPPORT_EMAIL));
            Toast.makeText(this, L.t("تم النسخ"), Toast.LENGTH_SHORT).show();
            try {
                Intent mi = new Intent(Intent.ACTION_SENDTO);
                mi.setData(android.net.Uri.parse("mailto:" + Feedback.SUPPORT_EMAIL));
                mi.putExtra(Intent.EXTRA_SUBJECT, Feedback.mailSubject(versionName(), versionCode()));
                startActivity(Intent.createChooser(mi, L.t("تواصل ومختبر بيتا")));
            } catch (Exception ignored) { } // no mail app — the copy above still did the job
        });
        fbc.addView(mailB);
        fbc.addView(divider());
        TextView repB = text(L.t(getString(R.string.nk_setupactivity_startactivity_1)), 14, Typeface.BOLD, Color.WHITE);
        repB.setGravity(Gravity.CENTER_VERTICAL);
        repB.setMinHeight((int) (52 * dp));
        repB.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(repB, "copy", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        repB.setOnClickListener(v -> {
            Perf.init(this);
            String rep = CrashReport.build(versionName(), versionCode(), android.os.Build.VERSION.SDK_INT,
                    Perf.sessions(), Perf.crashes(), Perf.lastCrash(), Perf.lastCrashAt(),
                    Shield.current().name(), Perf.STAB_KEYS, Perf.stabPct(), System.currentTimeMillis());
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("NeoKey", rep));
            Toast.makeText(this, L.t("تم النسخ"), Toast.LENGTH_SHORT).show();
        });
        fbc.addView(repB);
        fbc.addView(divider());
        TextView joinB = text(L.t("كيف تنضم لمختبر البيتا؟"), 14, Typeface.BOLD, Color.WHITE);
        joinB.setGravity(Gravity.CENTER_VERTICAL);
        joinB.setMinHeight((int) (52 * dp));
        joinB.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(joinB, "rocket", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        joinB.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("كيف تنضم لمختبر البيتا؟"))
                .setMessage(Feedback.joinSteps())
                .setPositiveButton(L.t("حسناً"), null).show());
        fbc.addView(joinB);
        content.addView(fbc);

        section("للمطورين");
        TextView perf = text("مركز قياس الأداء", 15, Typeface.BOLD, Color.WHITE);
        perf.setGravity(Gravity.CENTER);
        perf.setMinHeight((int) (52 * dp));
        perf.setBackground(round(CARD, 20, LINE));
        Icons.withIcon(perf, "monitoring", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        perf.setOnClickListener(v -> startActivity(new Intent(this, PerfActivity.class)));
        content.addView(perf, new LinearLayout.LayoutParams(-1, -2));
        section("عن المطوّر");
        content.addView(developerCard());
    }

    /** Developer card: CTO.DRS with tap-to-open social accounts. */
    private View developerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (16 * dp);
        card.setPadding(p, p, p, p);
        card.setBackground(Glass.panel(dp, 24, false));

        // Photo avatar: gradient ring + dark gap + circular photo + verified badge
        int as = (int) (96 * dp);
        FrameLayout avWrap = new FrameLayout(this);
        View ring = new View(this);
        GradientDrawable rg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6366F1, 0xFF06B6D4, 0xFFA855F7});
        rg.setShape(GradientDrawable.OVAL);
        ring.setBackground(rg);
        avWrap.addView(ring, new FrameLayout.LayoutParams(as, as));
        android.widget.ImageView photo = new android.widget.ImageView(this);
        photo.setImageBitmap(circle(BitmapFactory.decodeResource(getResources(), R.drawable.dev_photo)));
        GradientDrawable gap = new GradientDrawable();
        gap.setShape(GradientDrawable.OVAL);
        gap.setColor(0xFF161437);
        photo.setBackground(gap);
        int gp = (int) (3 * dp);
        photo.setPadding(gp, gp, gp, gp);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(as - (int) (6 * dp), as - (int) (6 * dp), Gravity.CENTER);
        avWrap.addView(photo, pl);
        TextView badge = text("", 12, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(badge, "check", Color.WHITE, (int) (16 * dp), (int) (26 * dp));
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(0xFF1D9BF0);
        bd.setStroke((int) (2.5f * dp), 0xFF161437);
        badge.setBackground(bd);
        int bs = (int) (26 * dp);
        avWrap.addView(badge, new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.RIGHT));
        avWrap.setElevation(8 * dp);
        avWrap.setOnClickListener(v -> showDevPhoto());
        card.addView(avWrap, new LinearLayout.LayoutParams(as, as));

        TextView name = text("CTO.DRS", 20, Typeface.BOLD, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, (int) (8 * dp), 0, 0);
        card.addView(name);
        TextView role = text(getString(R.string.nk_setupactivity_developercard_1), 13, Typeface.NORMAL, 0xFFA5B4FC);
        role.setGravity(Gravity.CENTER);
        role.setPadding(0, (int) (2 * dp), 0, (int) (12 * dp));
        card.addView(role, new LinearLayout.LayoutParams(-1, -2));

        final String[][] links = {
                {"واتساب", "b_whatsapp", "#25D366", "https://wa.me/message/MVTVWPOPFOVFF1", "qr_whatsapp"},
                {"تيليجرام", "b_telegram", "#229ED9", "https://t.me/CTO_DRS", "qr_telegram"},
                {"تيك توك", "b_tiktok", "#111111", "https://www.tiktok.com/@cto.drs", "qr_tiktok"},
                {"إنستغرام", "b_instagram", "#E1306C", "https://www.instagram.com/cto.drs", "qr_instagram"},
                {"X", "b_x", "#000000", "https://x.com/cto_drs", "qr_x"},
                {"يوتيوب", "b_youtube", "#FF0000", "https://youtube.com/@cto-drs", "qr_youtube"},
                {"GitHub", "b_github", "#24292F", "https://github.com/CTO-DRS", "qr_github"},
                {"الموقع", "globe", "#4F46E5", "https://cto-drs.com", "qr_site"},
        };
        LinearLayout row = null;
        for (int i = 0; i < links.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final String url = links[i][3];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
            TextView ic = text("", 18, Typeface.BOLD, Color.WHITE);
            Icons.iconOnly(ic, links[i][1], Color.WHITE, (int) (22 * dp), (int) (46 * dp));
            ic.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Color.parseColor(links[i][2]));
            d.setStroke((int) dp, 0x40FFFFFF);
            ic.setBackground(d);
            int is = (int) (46 * dp);
            cell.addView(ic, new LinearLayout.LayoutParams(is, is));
            TextView lb = text(links[i][0], 11, Typeface.NORMAL, 0xFFCBD5E1);
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, (int) (4 * dp), 0, 0);
            cell.addView(lb);
            final String[] L = links[i];
            cell.setOnClickListener(v -> showQr(L[0], L[1], L[2], L[3], L[4]));
            cell.setOnLongClickListener(v -> { openUrl(url); return true; });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        TextView ver = text("نيو كيبورد • الإصدار " + versionName(), 11, Typeface.NORMAL, 0xFF64748B);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, (int) (10 * dp), 0, 0);
        card.addView(ver);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (24 * dp);
        card.setLayoutParams(lp);
        return card;
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, url, Toast.LENGTH_LONG).show(); }
    }

    /** QR sheet for one account: code, link, scan instructions, actions. */
    private void showQr(String label, String glyph, String color, final String url, String qrRes) {
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        int brand = Color.parseColor(color);
        if (brand == 0xFF000000 || brand == 0xFF111111 || brand == 0xFF24292F) brand = 0xFF334155;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xE6000000);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setFillViewport(true);
        root.addView(sc, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout center = new FrameLayout(this);
        sc.addView(center, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (20 * dp);
        card.setPadding(p, p, p, p);
        card.setBackground(Glass.panel(dp, 28, true));
        card.setClickable(true);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        int m = (int) (18 * dp);
        cl.setMargins(m, (int) (30 * dp), m, (int) (30 * dp));
        center.addView(card, cl);

        // header: close + brand chip
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text("", 16, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(ic, glyph, Color.WHITE, (int) (20 * dp), (int) (40 * dp));
        ic.setGravity(Gravity.CENTER);
        GradientDrawable icd = new GradientDrawable();
        icd.setShape(GradientDrawable.OVAL);
        icd.setColor(Color.parseColor(color));
        icd.setStroke((int) dp, 0x40FFFFFF);
        ic.setBackground(icd);
        int is = (int) (40 * dp);
        head.addView(ic, new LinearLayout.LayoutParams(is, is));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0);
        tt.addView(text(label, 17, Typeface.BOLD, Color.WHITE));
        tt.addView(text("CTO.DRS", 12, Typeface.NORMAL, 0xFFA5B4FC));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text("", 16, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(close, "close", Color.WHITE, (int) (20 * dp), (int) (36 * dp));
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(0x26FFFFFF, 18, 0));
        int cs = (int) (36 * dp);
        head.addView(close, new LinearLayout.LayoutParams(cs, cs));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // QR on white
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setImageResource(getResources().getIdentifier(qrRes, "drawable", getPackageName()));
        int qp = (int) (12 * dp);
        qr.setPadding(qp, qp, qp, qp);
        GradientDrawable qbg = new GradientDrawable();
        qbg.setColor(Color.WHITE);
        qbg.setCornerRadius(22 * dp);
        qbg.setStroke((int) (3 * dp), brand);
        qr.setBackground(qbg);
        qr.setElevation(6 * dp);
        int qs = (int) (240 * dp);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(qs, qs);
        ql.topMargin = (int) (18 * dp);
        card.addView(qr, ql);

        TextView link = text(url.replace("https://", ""), 13, Typeface.BOLD, 0xFFE2E8F0);
        link.setGravity(Gravity.CENTER);
        link.setTextDirection(View.TEXT_DIRECTION_LTR);
        link.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
        link.setBackground(round(0x1AFFFFFF, 14, 0));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = (int) (14 * dp);
        card.addView(link, ll);

        TextView how = text(getString(R.string.nk_setupactivity_showqr_1),
                13, Typeface.NORMAL, 0xFFCBD5E1);
        how.setLineSpacing(4 * dp, 1f);
        how.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
        how.setBackground(round(0x14FFFFFF, 16, 0));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = (int) (14 * dp);
        card.addView(how, hl);

        // primary buttons
        LinearLayout r1 = new LinearLayout(this);
        TextView open = qrButton("فتح تلقائي", "open", brand, true);
        TextView scan = qrButton("مسح باركود", "scan", brand, false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, (int) (50 * dp), 1f);
        bl.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
        r1.addView(open, bl);
        r1.addView(scan, new LinearLayout.LayoutParams(bl));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = (int) (16 * dp);
        card.addView(r1, rl);
        LinearLayout r2 = new LinearLayout(this);
        TextView copy = qrButton("نسخ الرابط", "copy", 0, false);
        TextView share = qrButton("مشاركة", "share", 0, false);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, (int) (44 * dp), 1f);
        bl2.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
        r2.addView(copy, bl2);
        r2.addView(share, new LinearLayout.LayoutParams(bl2));
        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(-1, -2);
        rl2.topMargin = (int) (8 * dp);
        card.addView(r2, rl2);

        open.setOnClickListener(v -> { dlg.dismiss(); openUrl(url); });
        scan.setOnClickListener(v -> openScanner());
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, url));
            Toast.makeText(this, L.t("تم نسخ الرابط"), Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            Intent si = new Intent(Intent.ACTION_SEND);
            si.setType("text/plain");
            si.putExtra(Intent.EXTRA_TEXT, label + " — CTO.DRS\n" + url);
            startActivity(Intent.createChooser(si, "مشاركة"));
        });
        close.setOnClickListener(v -> dlg.dismiss());
        center.setOnClickListener(v -> dlg.dismiss());
        dlg.setContentView(root);
        dlg.show();
        card.setTranslationY(60 * dp); card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private TextView qrButton(String s, String icon, int brand, boolean primary) {
        TextView b = text(s, 14, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        Icons.withIcon(b, icon, Color.WHITE, (int) (18 * dp), false, (int) (6 * dp));
        b.setSingleLine(true);
        if (primary) b.setBackground(round(brand, 16, 0));
        else if (brand != 0) b.setBackground(round(0x14FFFFFF, 16, brand, 2));
        else b.setBackground(round(0x14FFFFFF, 14, 0x33FFFFFF));
        return b;
    }

    /** Opens a QR scanner: barcode scanner app → Google Lens → camera. */
    private void openScanner() {
        try { startActivity(new Intent("com.google.zxing.client.android.SCAN")); return; } catch (Exception ignored) {}
        try {
            Intent lens = getPackageManager().getLaunchIntentForPackage("com.google.ar.lens");
            if (lens != null) { startActivity(lens); return; }
        } catch (Exception ignored) {}
        try {
            Intent g = new Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens"));
            startActivity(g); return;
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            Toast.makeText(this, L.t("وجّه الكاميرا على الباركود"), Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, L.t("ما لقيت تطبيق كاميرا"), Toast.LENGTH_SHORT).show(); }
    }

    /** Full-screen developer photo viewer: tap anywhere or ✕ to close. */
    private void showDevPhoto() {
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xF2000000);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setImageResource(R.drawable.dev_full);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(true);
        GradientDrawable fr = new GradientDrawable();
        fr.setCornerRadius(22 * dp);
        img.setBackground(fr);
        img.setClipToOutline(true);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        int m = (int) (18 * dp);
        ip.setMargins(m, (int) (70 * dp), m, (int) (90 * dp));
        root.addView(img, ip);

        TextView close = text("", 20, Typeface.BOLD, Color.WHITE);
        Icons.iconOnly(close, "close", Color.WHITE, (int) (22 * dp), (int) (44 * dp));
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(0x33FFFFFF, 22 * dp, 0));
        int cs = (int) (44 * dp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cs, cs, Gravity.TOP | Gravity.LEFT);
        cp.setMargins(m, (int) (18 * dp), 0, 0);
        root.addView(close, cp);

        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView n = text("CTO.DRS", 20, Typeface.BOLD, Color.WHITE);
        Icons.withIcon(n, "verified", 0xFF1D9BF0, (int) (20 * dp), true, (int) (6 * dp));
        n.setGravity(Gravity.CENTER);
        cap.addView(n);
        TextView r = text("مطوّر نيو كيبورد", 13, Typeface.NORMAL, 0xFFA5B4FC);
        r.setGravity(Gravity.CENTER);
        cap.addView(r);
        FrameLayout.LayoutParams capl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        capl.bottomMargin = (int) (26 * dp);
        root.addView(cap, capl);

        root.setOnClickListener(v -> dlg.dismiss());
        close.setOnClickListener(v -> dlg.dismiss());
        dlg.setContentView(root);
        dlg.show();
        img.setScaleX(0.85f); img.setScaleY(0.85f); img.setAlpha(0f);
        img.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private static Bitmap circle(Bitmap src) {
        int n = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        pt.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(n / 2f, n / 2f, n / 2f, pt);
        return out;
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private int versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    private View stat(String num, String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        TextView n = text(num, 24, Typeface.BOLD, Color.WHITE);
        n.setGravity(Gravity.CENTER);
        TextView t = text(label, 12, Typeface.NORMAL, 0xFFC7D2FE);
        t.setGravity(Gravity.CENTER);
        l.addView(n);
        l.addView(t);
        return l;
    }

    private int countLines(String name) {
        File f = new File(getFilesDir(), name);
        if (!f.exists()) return 0;
        int n = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            while (r.readLine() != null) n++;
            r.close();
        } catch (Exception ignored) { }
        return n;
    }

    /** A titled, scrollable row of colour swatches saved to an int preference. */
    private void colorRow(String title, int[] colors, final String pref, int def) {
        TextView t = text(title, 13, Typeface.BOLD, 0xFFB9BECA);
        t.setPadding((int) (6 * dp), (int) (10 * dp), (int) (6 * dp), (int) (4 * dp));
        content.addView(t);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        int p = (int) (8 * dp);
        row.setPadding(p, p, p, p);
        hs.setBackground(round(CARD, 26, LINE));
        int cur = Theme.prefs(this).getInt(pref, def);
        for (int i = 0; i < colors.length; i++) {
            final int col = colors[i];
            boolean on = col == cur;
            TextView dot = text(col == 0 ? "بدون" : (on ? "✓" : ""), col == 0 ? 10 : 15, Typeface.BOLD,
                    col != 0 && Theme.light(col) && (col >>> 24) > 0x80 ? 0xFF111111 : Color.WHITE);
            dot.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(col == 0 ? 0xFF262B36 : ((col >>> 24) < 0x80 ? Theme.blend(0xFF1B1C20, col | 0xFF000000, 0.35f) : col));
            d.setStroke((int) ((on ? 3 : 1) * dp), on ? Color.WHITE : 0x33FFFFFF);
            dot.setBackground(d);
            int sz = (int) (38 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
            dot.setOnClickListener(v -> { Theme.prefs(this).edit().putInt(pref, col).apply(); showTab(); });
            row.addView(dot, lp);
        }
        hs.addView(row);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    private View accentRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setBackground(round(CARD, 26, LINE));
        int p = (int) (10 * dp);
        row.setPadding(p, p, p, p);
        int cur = Theme.prefs(this).getInt("accent", 0);
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            final int idx = i;
            TextView dot = text(i == 0 ? "تلقائي" : (i == cur ? "✓" : ""), i == 0 ? 10 : 15, Typeface.BOLD, Color.WHITE);
            dot.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == 0 ? 0xFF262B36 : Theme.ACCENTS[i]);
            if (i == cur) d.setStroke((int) (3 * dp), Color.WHITE);
            dot.setBackground(d);
            int s = (int) (34 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            dot.setOnClickListener(v -> { Theme.prefs(this).edit().putInt("accent", idx).apply(); refresh(); });
            row.addView(dot, lp);
        }
        return row;
    }

    private View seg(String[] names, int sel, final String key) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(round(CARD, 26, LINE));
        int p = (int) (5 * dp);
        seg.setPadding(p, p, p, p);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 15, Typeface.BOLD, i == sel ? Color.WHITE : 0xFFB9BECA);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
            if (i == sel) t.setBackground(round(BLUE, 22, 0));
            if (i == sel && Theme.prefs(this).getBoolean("cues", true)) Icons.withIcon(t, "check", Color.WHITE, (int) (15 * dp), false, (int) (4 * dp));
            t.setContentDescription(names[i] + (i == sel ? "، محدد" : ""));
            t.setMinHeight((int) (44 * dp));
            t.setOnClickListener(v -> { Theme.prefs(this).edit().putInt(key, idx).apply(); refresh(); });
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return seg;
    }

    private void buildPhrases() {
        LinearLayout wc = new LinearLayout(this);
        wc.setOrientation(LinearLayout.VERTICAL);
        wc.setBackground(Glass.neon(dp, 22));
        int wp = (int) (16 * dp);
        wc.setPadding(wp, wp, wp, wp);
        TextView wt = text("مساعد المراسلات", 17, Typeface.BOLD, Color.WHITE);
        Icons.withIcon(wt, "edit_note", Color.WHITE, (int) (22 * dp), false, (int) (8 * dp));
        wc.addView(wt);
        TextView wd = text(getString(R.string.nk_setupactivity_buildphrases_1),
                13, Typeface.NORMAL, 0xFFDDE4FF);
        wd.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
        wc.addView(wd);
        TextView wb = text("افتح المساعد", 14, Typeface.BOLD, 0xFF4C1D95);
        wb.setGravity(Gravity.CENTER);
        wb.setMinHeight((int) (46 * dp));
        wb.setBackground(round(Color.WHITE, 16, 0));
        wb.setOnClickListener(v -> startActivity(new Intent(this, WriterActivity.class)));
        wc.addView(wb, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = (int) (18 * dp);
        content.addView(wc, wl);
        section("أضف عبارتك");
        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText in = new EditText(this);
        in.setHint(L.t("مثال: أنا مشغول، أكلمك بعد شوي"));
        in.setHintTextColor(0xFF6B7280);
        in.setTextColor(Color.WHITE);
        in.setTextSize(15);
        in.setBackground(round(0xFF0F1218, 22, LINE));
        int p = (int) (14 * dp);
        in.setPadding(p, p, p, p);
        addRow.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = text("إضافة", 15, Typeface.BOLD, Color.WHITE);
        add.setGravity(Gravity.CENTER);
        add.setPadding((int) (18 * dp), p, (int) (18 * dp), p);
        add.setBackground(round(BLUE, 22, 0));
        add.setOnClickListener(v -> {
            String t = in.getText().toString().trim().replace("\u0001", " ");
            if (t.isEmpty()) return;
            List<String> l = Theme.userPhrases(this);
            l.add(0, t);
            Theme.saveUserPhrases(this, l);
            showTab();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.setMarginStart((int) (8 * dp));
        addRow.addView(add, alp);
        content.addView(addRow);

        final List<String> mine = Theme.userPhrases(this);
        if (!mine.isEmpty()) {
            section("عباراتي");
            LinearLayout card = cardBox();
            for (int i = 0; i < mine.size(); i++) {
                final int idx = i;
                if (i > 0) card.addView(divider());
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
                row.addView(text(mine.get(i), 15, Typeface.NORMAL, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
                TextView del = text("", 16, Typeface.BOLD, 0xFFF87171);
                Icons.iconOnly(del, "close", 0xFFF87171, (int) (20 * dp), 0);
                del.setPadding((int) (12 * dp), 0, (int) (4 * dp), 0);
                del.setOnClickListener(v -> { mine.remove(idx); Theme.saveUserPhrases(this, mine); showTab(); });
                row.addView(del);
                card.addView(row);
            }
            content.addView(card);
        }

        section("الاختصارات");
        TextView sTip = text(getString(R.string.nk_setupactivity_section_24), 13, Typeface.NORMAL, 0xFF8A90A0);
        sTip.setPadding((int) (4 * dp), 0, (int) (4 * dp), (int) (8 * dp));
        content.addView(sTip);
        LinearLayout scRow = new LinearLayout(this);
        scRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText key = new EditText(this);
        key.setHint(L.t("سع"));
        final EditText val = new EditText(this);
        val.setHint(L.t("السلام عليكم ورحمة الله"));
        for (EditText e : new EditText[]{key, val}) {
            e.setHintTextColor(0xFF6B7280);
            e.setTextColor(Color.WHITE);
            e.setTextSize(15);
            e.setSingleLine(true);
            e.setBackground(round(0xFF0F1218, 22, LINE));
            e.setPadding(p, p, p, p);
        }
        scRow.addView(key, new LinearLayout.LayoutParams((int) (80 * dp), -2));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
        vlp.setMarginStart((int) (6 * dp));
        scRow.addView(val, vlp);
        TextView addSc = text("+", 20, Typeface.BOLD, Color.WHITE);
        addSc.setGravity(Gravity.CENTER);
        addSc.setPadding((int) (18 * dp), (int) (10 * dp), (int) (18 * dp), (int) (10 * dp));
        addSc.setBackground(round(BLUE, 22, 0));
        addSc.setOnClickListener(v -> {
            String k = key.getText().toString().trim().toLowerCase(), t = val.getText().toString().trim();
            if (k.isEmpty() || t.isEmpty() || k.contains(" ")) {
                Toast.makeText(this, L.t("الاختصار كلمة وحدة بدون مسافات"), Toast.LENGTH_SHORT).show();
                return;
            }
            java.util.LinkedHashMap<String, String> m = Theme.shortcuts(this);
            m.put(k, t.replace("\u0001", " ").replace("\u001F", " "));
            Theme.saveShortcuts(this, m);
            showTab();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMarginStart((int) (6 * dp));
        scRow.addView(addSc, slp);
        content.addView(scRow);
        final java.util.LinkedHashMap<String, String> scs = Theme.shortcuts(this);
        if (!scs.isEmpty()) {
            LinearLayout card = cardBox();
            boolean first = true;
            for (final java.util.Map.Entry<String, String> e : scs.entrySet()) {
                if (!first) card.addView(divider());
                first = false;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
                TextView kv = text(e.getKey(), 14, Typeface.BOLD, Color.WHITE);
                kv.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
                kv.setBackground(round(BLUE, 12, 0));
                row.addView(kv);
                TextView vv = text("   " + e.getValue(), 14, Typeface.NORMAL, 0xFFD1D5DB);
                row.addView(vv, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView del = text("", 16, Typeface.BOLD, 0xFFF87171);
                Icons.iconOnly(del, "close", 0xFFF87171, (int) (20 * dp), 0);
                del.setPadding((int) (12 * dp), 0, (int) (4 * dp), 0);
                del.setOnClickListener(v -> { scs.remove(e.getKey()); Theme.saveShortcuts(this, scs); showTab(); });
                row.addView(del);
                card.addView(row);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.topMargin = (int) (8 * dp);
            content.addView(card, clp);
        }

        section("العبارات الجاهزة");
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        for (String ph : Theme.defaultPhrases(this)) {
            TextView t = text(ph, 14, Typeface.NORMAL, 0xFFD1D5DB);
            t.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
            t.setBackground(round(CARD, 16, LINE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.bottomMargin = (int) (6 * dp);
            chips.addView(t, lp);
        }
        content.addView(chips);
        TextView tip = text(getString(R.string.nk_setupactivity_section_25), 13, Typeface.NORMAL, 0xFF8A90A0);
        tip.setPadding((int) (4 * dp), (int) (12 * dp), (int) (4 * dp), 0);
        content.addView(tip);
    }

    private void buildActivation() {
        boolean enabled = isEnabled(), selected = isSelected();
        section("التفعيل");
        content.addView(step("١", "فعّل نيو كيبورد", getString(R.string.nk_setupactivity_section_26), enabled,
                "افتح الإعدادات", v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        content.addView(step("٢", "اختره كيبورد أساسي", "اختر NeoKey 2036 من القائمة.", selected,
                "اختيار الكيبورد", v -> imm().showInputMethodPicker()));
        content.addView(step("٣", "الكتابة بالصوت (اختياري)", getString(R.string.nk_setupactivity_section_27), micGranted(),
                "السماح بالمايك", v -> askMic()));
        content.addView(step("٤", "تنبيه البنوك الفوري (اختياري)", getString(R.string.nk_setupactivity_bankwatch), BankWatch.on(this),
                "إمكانية الوصول", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        if (Build.VERSION.SDK_INT >= 33 && !BankWatch.on(this))
            content.addView(step("!", "ظهر «تم منع التطبيق»؟", getString(R.string.nk_setupactivity_restricted), false,
                    "صفحة التطبيق", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        if (Build.VERSION.SDK_INT >= 33 && !BankWatch.on(this))
            content.addView(step("📷", "الشرح بالصور", getString(R.string.nk_setupactivity_guide), false,
                    "عرض الصور", v -> restrictGuide()));
        if (enabled && selected) {
            TextView ok = text("كل شي جاهز، جرّب الكتابة تحت.", 15, Typeface.BOLD, 0xFF4ADE80);
            ok.setGravity(Gravity.CENTER);
            ok.setPadding(0, (int) (14 * dp), 0, 0);
            content.addView(ok);
        }
    }

    private boolean micGranted() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askMic() {
        if (!micGranted()) requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 7) {
            Toast.makeText(this, micGranted() ? getString(R.string.nk_setupactivity_onrequestpermissionsresult_1) : getString(R.string.nk_setupactivity_onrequestpermissionsresult_2),
                    Toast.LENGTH_SHORT).show();
            showTab();
        }
    }

    /** W129c: the restricted-settings walkthrough as cropped screenshots (res/drawable-nodpi/nk_rs_1..7). */
    private void restrictGuide() {
        String[] cap = {"١- الإعدادات ← إدارة التطبيق", "٢- قائمة التطبيقات", "٣- نيو كيبورد",
                getString(R.string.nk_setupactivity_guide4), getString(R.string.nk_setupactivity_guide5),
                "٦- فعّل الزر", "٧- اضغط «سماح»"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        box.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < cap.length; i++) {
            TextView t = text(cap[i], 15, Typeface.BOLD, 0xFFFFFFFF);
            t.setPadding(0, i == 0 ? 0 : pad, 0, (int) (6 * dp));
            box.addView(t);
            android.widget.ImageView im = new android.widget.ImageView(this);
            im.setImageResource(getResources().getIdentifier("nk_rs_" + (i + 1), "drawable", getPackageName()));
            im.setAdjustViewBounds(true);
            box.addView(im, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("تفعيل تنبيه البنوك").setView(sv)
                .setPositiveButton("صفحة التطبيق", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                .setNegativeButton("إغلاق", null).show();
    }

    private View step(String n, String title, String sub, boolean done, String btn, View.OnClickListener l) {
        LinearLayout card = cardBox();
        card.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (16 * dp));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = text(done ? "" : n, 15, Typeface.BOLD, Color.WHITE);
        num.setGravity(Gravity.CENTER);
        if (done) Icons.iconOnly(num, "check", Color.WHITE, (int) (20 * dp), (int) (32 * dp));
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(done ? 0xFF16A34A : BLUE);
        num.setBackground(c);
        int s = (int) (32 * dp);
        head.addView(num, new LinearLayout.LayoutParams(s, s));
        TextView t = text(title, 17, Typeface.BOLD, Color.WHITE);
        t.setPadding((int) (12 * dp), 0, 0, 0);
        head.addView(t);
        card.addView(head);
        TextView st = text(sub, 14, Typeface.NORMAL, 0xFF9AA0AE);
        st.setPadding(0, (int) (10 * dp), 0, 0);
        card.addView(st);
        TextView b = text(done ? "تم" : btn, 15, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, (int) (13 * dp), 0, (int) (13 * dp));
        b.setBackground(done ? round(0xFF1C2029, 22, LINE) : round(BLUE, 22, 0));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (14 * dp);
        card.addView(b, lp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = (int) (12 * dp);
        card.setLayoutParams(clp);
        return card;
    }

    private View alert() {
        LinearLayout a = new LinearLayout(this);
        a.setGravity(Gravity.CENTER_VERTICAL);
        int p = (int) (14 * dp);
        a.setPadding(p, p, p, p);
        a.setBackground(round(0xFF2A1F0B, 18, 0xFF6B5524));
        TextView t = text("الكيبورد غير مفعّل بعد", 14, Typeface.BOLD, 0xFFF5C243);
        a.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = text("فعّله", 14, Typeface.BOLD, Color.WHITE);
        go.setPadding((int) (16 * dp), (int) (8 * dp), (int) (16 * dp), (int) (8 * dp));
        go.setBackground(round(BLUE, 16, 0));
        go.setOnClickListener(v -> { tab = 3; showTab(); });
        a.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (18 * dp);
        a.setLayoutParams(lp);
        return a;
    }

    private View toggle(String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setChecked(Theme.prefs(this).getBoolean(key, def));
        s.setPadding(0, (int) (12 * dp), 0, (int) (12 * dp));
        s.setOnCheckedChangeListener((b, on) -> Theme.prefs(this).edit().putBoolean(key, on).apply());
        return s;
    }

    // ---------------------------------------- التبديل الذكي فصحى/لهجة (item C2, week 6)

    private static final String[] DIALECT_MODES = {SmartSwitch.AUTO, SmartSwitch.OFF, Dialect.HIJAZI, Dialect.NAJDI, Dialect.GULF};

    private static String dialectLabel(String m) {
        if (SmartSwitch.AUTO.equals(m)) return "تلقائي حسب السياق";
        if (SmartSwitch.OFF.equals(m)) return "فصحى فقط";
        if (Dialect.HIJAZI.equals(m)) return "حجازي";
        if (Dialect.NAJDI.equals(m)) return "نجدي";
        return "خليجي عام";
    }

    /** صف اختيار وضع لهجة التنبؤ: تلقائي (افتراضي) / فصحى / لهجة مثبتة. */
    private View dialectRow() {
        final TextView row = text("لهجة التنبؤ · " + dialectLabel(Theme.prefs(this).getString("dialect_mode", SmartSwitch.AUTO)),
                14, Typeface.BOLD, 0xFF93B4FF);
        row.setMinHeight((int) (48 * dp));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> {
            String cur = Theme.prefs(this).getString("dialect_mode", SmartSwitch.AUTO);
            int sel = 0;
            for (int i = 0; i < DIALECT_MODES.length; i++) if (DIALECT_MODES[i].equals(cur)) sel = i;
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("لهجة التنبؤ")
                    .setMessage(getString(R.string.nk_setupactivity_dialectrow_1))
                    .setSingleChoiceItems(new String[]{"تلقائي حسب السياق", "فصحى فقط", "حجازي", "نجدي", "خليجي عام"},
                            sel, (d, w) -> {
                                Theme.prefs(this).edit().putString("dialect_mode", DIALECT_MODES[w]).apply();
                                row.setText("لهجة التنبؤ · " + dialectLabel(DIALECT_MODES[w]));
                                d.dismiss();
                            })
                    .setNegativeButton("إلغاء", null)
                    .show();
        });
        return row;
    }

    // ---------------------------------------- قوائم الاستثناء الذكية (item B, week 2)

    private String exclLabel() {
        int n = Exceptions.count(Theme.prefs(this).getString("nocorrect", ""));
        return L.t("كلمات لا تُصحح") + " (" + n + ")";
    }

    /** The never-correct list manager: tap a word to remove it, or clear everything. */
    private void showExceptionsDialog(final TextView row) {
        final java.util.List<String> l = Exceptions.parse(Theme.prefs(this).getString("nocorrect", ""));
        if (l.isEmpty()) {
            android.widget.Toast.makeText(this, L.t(getString(R.string.nk_setupactivity_showexceptionsdialog_1)), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = l.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("كلمات لا تُصحح"))
                .setMessage(L.t(getString(R.string.nk_setupactivity_showexceptionsdialog_2)))
                .setItems(items, (d, w) -> {
                    java.util.List<String> rest = Exceptions.remove(l, items[w]);
                    Theme.prefs(SetupActivity.this).edit().putString("nocorrect", Exceptions.serialize(rest)).apply();
                    row.setText(exclLabel());
                    android.widget.Toast.makeText(this, L.t("أُزيلت من الاستثناءات"), android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(L.t("مسح الكل"), (d, w) -> {
                    Theme.prefs(this).edit().remove("nocorrect").apply();
                    row.setText(exclLabel());
                })
                .setPositiveButton(L.t("إلغاء"), null)
                .show();
    }

    // ---------------------------------------------------------------- try-it bar

    // bottom dock: 4 tabs around a centre slot under the floating orb (opens the keyboard on the try bar)
    private static final int[] DOCK_TAB = {0, 1, -1, 2, 3};
    private static final String[] DOCK_ICON = {"palette", "tune", "keyboard", "chat", "bolt"};
    private static final String[] DOCK_NAME = {"الثيمات", "الإعدادات", "جرّب", "العبارات", "التفعيل"};
    private final android.widget.ImageView[] dockSlots = new android.widget.ImageView[5];
    private final TextView[] dockLabels = new TextView[5];

    private LinearLayout buildDock() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setLayoutDirection(L.dir());
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF205061A, 0xB305061A, 0x0005061A});
        bar.setBackground(fade);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        LinearLayout dock = new LinearLayout(this);
        dock.setBackground(Glass.panel(dp, 34, true));
        int dp8 = (int) (8 * dp);
        dock.setPadding(dp8, (int) (10 * dp), dp8, (int) (6 * dp));
        for (int i = 0; i < 5; i++) {
            final int k = i;
            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER_HORIZONTAL);
            android.widget.ImageView sq = new android.widget.ImageView(this);
            sq.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            if (DOCK_TAB[i] < 0) {
                sq.setBackground(Glass.panel(dp, 16, false));
                sq.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            } else {
                dockSlots[i] = sq;
            }
            slot.addView(sq, new LinearLayout.LayoutParams((int) (50 * dp), (int) (50 * dp)));
            TextView lb = text(DOCK_NAME[i], 10, Typeface.BOLD, 0xFF8F8AB8);
            lb.setGravity(Gravity.CENTER);
            lb.setSingleLine(true);
            lb.setPadding(0, (int) (3 * dp), 0, 0);
            if (DOCK_TAB[i] >= 0) dockLabels[i] = lb; else lb.setTextColor(0xFFC4BFEA);
            slot.addView(lb, new LinearLayout.LayoutParams(-1, -2));
            slot.setContentDescription(L.t(DOCK_NAME[i]));
            slot.setOnClickListener(v -> {
                if (DOCK_TAB[k] < 0) { openTry(); return; }
                if (DOCK_TAB[k] == 0) themeView = 0;
                tab = DOCK_TAB[k];
                showTab();
                if (mainScroll != null) mainScroll.smoothScrollTo(0, content.getTop());
            });
            dock.addView(slot, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        wrap.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // floating orb above the centre slot
        FrameLayout orb = new FrameLayout(this);
        orb.setBackground(Glass.orb(Glass.CYAN, Glass.PURPLE, false));
        Widgets.Icon oi = new Widgets.Icon(this, Widgets.Icon.KEYBOARD_DOWN, Color.WHITE);
        orb.addView(oi, new FrameLayout.LayoutParams((int) (34 * dp), (int) (34 * dp), Gravity.CENTER));
        orb.setContentDescription(L.t("جرّب الكيبورد"));
        orb.setOnClickListener(v -> openTry());
        int os = (int) (76 * dp);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(os, os, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ol.topMargin = -(int) (34 * dp);
        wrap.addView(orb, ol);
        bar.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        return bar;
    }

    private LinearLayout.LayoutParams top(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (m * dp);
        return lp;
    }

    private void openTry() {
        if (mainScroll != null) mainScroll.smoothScrollTo(0, 0);
        test.requestFocus();
        imm().showSoftInput(test, InputMethodManager.SHOW_IMPLICIT);
    }

    // ---------------------------------------------------------------- state helpers

    private void selectTheme(int i) { Theme.prefs(this).edit().putInt("theme", i).apply(); }

    private InputMethodManager imm() { return (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); }

    private boolean isReady() { return isEnabled() && isSelected(); }

    private boolean isEnabled() {
        for (InputMethodInfo i : imm().getEnabledInputMethodList())
            if (i.getPackageName().equals(getPackageName())) return true;
        return false;
    }

    private boolean isSelected() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return !TextUtils.isEmpty(id) && id.startsWith(getPackageName() + "/");
    }

    // ---------------------------------------------------------------- ui helpers

    private TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(L.t(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setTextColor(color);
        return t;
    }

    private android.graphics.drawable.Drawable round(int color, float r, int stroke) { return round(color, r, stroke, 1); }

    private android.graphics.drawable.Drawable round(int color, float r, int stroke, int sw) {
        if (color == CARD) return Glass.panel(dp, r, stroke == BLUE || sw > 1);
        if (color == BLUE) return Glass.neon(dp, r);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        if (stroke != 0) g.setStroke((int) (sw * dp), stroke);
        return g;
    }

    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * dp);
        c.setPadding(p, (int) (8 * dp), p, (int) (8 * dp));
        c.setBackground(round(CARD, 22, LINE));
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new ViewGroup.LayoutParams(-1, (int) dp));
        return v;
    }
}
