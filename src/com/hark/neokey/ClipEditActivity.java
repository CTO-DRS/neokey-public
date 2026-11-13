package com.hark.neokey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Full editor for one clipboard item: find / replace (case, whole word, tashkeel-insensitive),
 * undo / redo, counters, versions, title. Opened from the keyboard's clip action menu.
 */
public class ClipEditActivity extends Activity {
    static final String EXTRA_ID = "clip_id";
    static final int BG = Glass.BG, CARD = 0xFF14171F, LINE = 0x40A78BFA, BLUE = Glass.VIOLET, MUTED = 0xFF8A90A0;
    private static final int HL = 0x553B82F6, HL_CUR = 0xCCF59E0B;

    private float dp;
    private long id;
    private String original = "", originalTitle = "";
    private EditText body, title, findQ, replQ;
    private TextView stats, findInfo, verBtn;
    private LinearLayout findBar;
    private boolean caseSens, wholeWord, ignoreMarks = true, loading = true, applying;
    private List<int[]> hits = new ArrayList<>();
    private int cur = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private long lastTypeAt;
    private String[] pendingEdit;   // {start, removed} captured before a change
    private int versions;
    private LinearLayout catRow;
    private List<ClipRepo.Cat> allCats = new ArrayList<>();
    private java.util.Set<Long> myCats = new java.util.HashSet<>();
    private LinearLayout tagRow;                       // W48: the tags of this item
    private List<String> myTags = new ArrayList<>();
    private EditText scEdit;                           // W48: the clip's expansion shortcut

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Perf.installCrashHandler(this);
        dp = getResources().getDisplayMetrics().density;
        id = getIntent() == null ? -1 : getIntent().getLongExtra(EXTRA_ID, -1);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(0x99000000);
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        build();
        load();
    }

    // ------------------------------------------------------------ UI

    private void build() {
        // floating card over a dimmed scrim; the app underneath stays visible
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0x99000000);
        frame.setOnClickListener(v -> onBackPressed());   // tap outside = close (asks first if unsaved)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(L.dir());
        root.setBackground(round(BG, 22, LINE));
        root.setClickable(true);
        final int ip = (int) (12 * dp);
        root.setPadding(ip, ip, ip, ip);
        root.setElevation(12 * dp);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, -1);
        frame.addView(root, rl);
        setContentView(frame);
        final int pad = (int) (12 * dp);
        frame.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
                left = in.getSystemWindowInsetLeft(); right = in.getSystemWindowInsetRight();
            }
            // a little margin around the card; the top margin leaves the app's own header visible
            int topGap = pad * 3;
            v.setPadding(left + pad, top + topGap, right + pad, bottom + pad);
            return in;
        });

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("", false);
        Icons.iconOnly(back, "arrow_forward", Color.WHITE, (int) (20 * dp), (int) (40 * dp));
        back.setContentDescription(L.t("رجوع"));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        TextView h = text("تعديل النص", 18, Typeface.BOLD, Color.WHITE);
        h.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0);
        bar.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView find = btn("", false);
        Icons.iconOnly(find, "search", Color.WHITE, (int) (20 * dp), (int) (40 * dp));
        find.setContentDescription(L.t("بحث واستبدال"));
        find.setOnClickListener(v -> toggleFind());
        bar.addView(find, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        TextView save = btn("حفظ", true);
        save.setOnClickListener(v -> save(false));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, (int) (40 * dp));
        sl.setMarginStart((int) (8 * dp));
        save.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
        bar.addView(save, sl);
        root.addView(bar);

        title = new EditText(this);
        styleEdit(title, "عنوان اختياري (مثل: رسالة ترحيب للعملاء)");
        title.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = (int) (10 * dp);
        root.addView(title, tl);

        catRow = new LinearLayout(this);
        catRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView cs = new HorizontalScrollView(this);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(catRow);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = (int) (8 * dp);
        root.addView(cs, cl);

        // W48: tags row — chips with instant persistence, the + chip opens the add dialog
        tagRow = new LinearLayout(this);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        ts.addView(tagRow);
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(-1, -2);
        tsl.topMargin = (int) (6 * dp);
        root.addView(ts, tsl);

        // W48: the clip's expansion shortcut — typed token → the clip text; the keyboard's
        // done/✓ action commits (validated by the repo: shape, uniqueness, domain)
        scEdit = new EditText(this);
        styleEdit(scEdit, "اختصار للتوسع السريع (كلمة واحدة)");
        scEdit.setSingleLine(true);
        scEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        scEdit.setOnEditorActionListener((v, a2, ev) -> { saveShortcut(); return true; });
        root.addView(scEdit, new LinearLayout.LayoutParams(-1, -2));

        // find / replace
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.VERTICAL);
        findBar.setVisibility(View.GONE);
        findBar.setPadding(0, (int) (8 * dp), 0, 0);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findQ = new EditText(this);
        styleEdit(findQ, "ابحث في النص");
        findQ.setSingleLine(true);
        findQ.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        findQ.setOnEditorActionListener((v, act, ev) -> { step(1); return true; });
        r1.addView(findQ, new LinearLayout.LayoutParams(0, -2, 1f));
        findInfo = text("", 12, Typeface.NORMAL, MUTED);
        findInfo.setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
        r1.addView(findInfo);
        TextView prev = btn("‹", false), next = btn("›", false);
        prev.setContentDescription(L.t("النتيجة السابقة"));
        next.setContentDescription(L.t("النتيجة التالية"));
        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        r1.addView(prev, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp));
        nl.setMarginStart((int) (4 * dp));
        r1.addView(next, nl);
        findBar.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, (int) (6 * dp), 0, 0);
        replQ = new EditText(this);
        styleEdit(replQ, "استبدل بـ");
        replQ.setSingleLine(true);
        r2.addView(replQ, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView one = btn("استبدال", false), all = btn("الكل", false);
        one.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        all.setPadding((int) (12 * dp), 0, (int) (12 * dp), 0);
        one.setOnClickListener(v -> replaceOne());
        all.setOnClickListener(v -> replaceAll());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, (int) (40 * dp));
        ol.setMarginStart((int) (6 * dp));
        r2.addView(one, ol);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, (int) (40 * dp));
        al.setMarginStart((int) (4 * dp));
        r2.addView(all, al);
        findBar.addView(r2);
        LinearLayout r3 = new LinearLayout(this);
        r3.setPadding(0, (int) (6 * dp), 0, 0);
        r3.addView(optChip("حساس لحالة الأحرف", () -> caseSens, v -> caseSens = v));
        r3.addView(optChip("كلمة كاملة", () -> wholeWord, v -> wholeWord = v));
        r3.addView(optChip("تجاهل التشكيل", () -> ignoreMarks, v -> ignoreMarks = v));
        HorizontalScrollView hs3 = new HorizontalScrollView(this);
        hs3.setHorizontalScrollBarEnabled(false);
        hs3.addView(r3);
        findBar.addView(hs3);
        root.addView(findBar);
        findQ.addTextChangedListener(simpleWatcher(this::scheduleFind));

        body = new EditText(this);
        styleEdit(body, "النص");
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.setSingleLine(false);
        body.setHorizontallyScrolling(false);
        body.setVerticalScrollBarEnabled(true);
        body.setTextSize(16);
        body.setText(L.t("جاري التحميل…"));
        body.setEnabled(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        bl.topMargin = (int) (8 * dp);
        root.addView(body, bl);
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) {
                if (applying || loading) return;
                pendingEdit = new String[]{String.valueOf(st), s.subSequence(st, st + count).toString()};
            }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                if (applying || loading || pendingEdit == null) return;
                String ins = s.subSequence(st, st + count).toString();
                long now = System.currentTimeMillis();
                String[] top = undo.peek();
                // typing a word is one undo step: merge plain inserts that continue the previous one
                if (top != null && pendingEdit[1].isEmpty() && top[1].isEmpty() && count > 0 && count < 3
                        && now - lastTypeAt < 1500 && Integer.parseInt(top[0]) + top[2].length() == st
                        && top[2].length() < 40 && ins.indexOf('\n') < 0
                        && !(ins.startsWith(" ") && !top[2].endsWith(" "))) {
                    top[2] = top[2] + ins;
                    lastTypeAt = now;
                    redo.clear();
                    pendingEdit = null;
                    return;
                }
                lastTypeAt = now;
                undo.push(new String[]{pendingEdit[0], pendingEdit[1], ins});
                while (undo.size() > 300) undo.removeLast();
                redo.clear();
                pendingEdit = null;
            }
            @Override public void afterTextChanged(Editable e) {
                if (loading) return;
                scheduleStats();
                if (findBar.getVisibility() == View.VISIBLE && !applying) scheduleFind();
            }
        });

        stats = text("", 12, Typeface.NORMAL, MUTED);
        stats.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (4 * dp));
        root.addView(stats);

        // tools
        LinearLayout tools = new LinearLayout(this);
        tools.addView(tool("تحديد الكل", v -> { body.requestFocus(); body.selectAll(); }));
        tools.addView(tool("قص", v -> cutCopy(true)));
        tools.addView(tool("نسخ", v -> cutCopy(false)));
        tools.addView(tool("لصق", v -> paste()));
        tools.addView(tool("تراجع", v -> undoRedo(true)));
        tools.addView(tool("إعادة", v -> undoRedo(false)));
        tools.addView(tool("إزالة التشكيل", v -> transform(ClipText.stripMarks(body.getText().toString()))));
        // W57: the tashkeel tools — the chip cycles the three degrees (shipped
        // CLOSED; persisted), the tool applies the mode-gated first forms
        // through the attached engine (uncovered words are never guessed) —
        // the inverse of إزالة التشكيل rides beside it.
        final TextView tashChip = tool(tashLabel(), null);
        tashChip.setOnClickListener(v -> cycleTashkeel(tashChip));
        tools.addView(tashChip);
        tools.addView(tool("تشكيل النص", v -> applyTashkeel()));
        // W58: the grammar chip cycles the three modes (shipped CLOSED;
        // persisted), and تدقيق النص scans the whole text and EXPLAINS each
        // nudge — the tap applies that one (one undo step, never قهراً).
        final TextView gramChip = tool(gramLabel(), null);
        gramChip.setOnClickListener(v -> cycleGrammar(gramChip));
        tools.addView(gramChip);
        tools.addView(tool("تدقيق النص", v -> scanGrammar()));
        // W60: مسح كامل من زر واحد — طبقة التكرار الشخصية تعود لحالة المصنع
        // بايتياً (الملف يحذف والخدمة تمحو نسختها عند بدء الحقل القادم).
        tools.addView(tool("مسح التنبؤ الشخصي", v -> wipePersonal()));
        tools.addView(tool("تنظيم المسافات", v -> transform(ClipText.tidy(body.getText().toString()))));
        tools.addView(tool("البداية", v -> jump(0)));
        tools.addView(tool("النهاية", v -> jump(body.length())));
        tools.addView(tool("نسخ الكل للحافظة", v -> copyAll()));
        tools.addView(tool("حفظ كنص جديد", v -> save(true)));
        verBtn = tool("النسخة السابقة", v -> restoreVersion());
        tools.addView(verBtn);
        tools.addView(tool("إدراج في الحقل", v -> insertAndClose()));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tools);
        root.addView(hs);
    }

    private interface Get { boolean get(); }
    private interface Set { void set(boolean v); }

    private TextView optChip(String label, Get g, Set s) {
        TextView t = text(label, 12, Typeface.BOLD, Color.WHITE);
        t.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
        Runnable paint = () -> t.setBackground(g.get() ? round(BLUE, 16, 0) : round(CARD, 16, LINE));
        paint.run();
        t.setOnClickListener(v -> { s.set(!g.get()); paint.run(); scheduleFind(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd((int) (6 * dp));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView tool(String label, View.OnClickListener l) {
        TextView t = btn(label, false);
        t.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, (int) (40 * dp));
        lp.setMarginEnd((int) (6 * dp));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(L.t(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setTextColor(color);
        return t;
    }

    private TextView btn(String label, boolean primary) {
        TextView b = text(label, 14, Typeface.BOLD, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(BLUE, 12, 0) : round(CARD, 12, LINE));
        return b;
    }

    private void styleEdit(EditText e, String hint) {
        e.setHint(L.t(hint));
        e.setHintTextColor(0xFF5B6170);
        e.setTextColor(Color.WHITE);
        e.setTextSize(15);
        e.setBackground(round(CARD, 12, LINE));
        e.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (10 * dp));
        e.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
    }

    private android.graphics.drawable.Drawable round(int color, int r, int stroke) {
        if (color == BG) return Glass.panel(dp, r, true);
        if (color == CARD) return Glass.panel(dp, r, false);
        if (color == BLUE) return Glass.neon(dp, r);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        if (stroke != 0) g.setStroke((int) Math.max(1, dp), stroke);
        return g;
    }

    private static TextWatcher simpleWatcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    // ------------------------------------------------------------ data

    private void load() {
        ClipStore.get(this).run(r -> {
            String t = r.getText(id);
            ClipItem it = r.item(id);
            return t == null || it == null ? null : new Object[]{t, it.title == null ? "" : it.title, r.versionCount(id),
                    r.categories(), r.categoriesOf(id), r.tagsOf(id), r.shortcutOf(id)};
        }, res -> {
            if (res == null) {
                Toast.makeText(this, L.t("النص غير موجود، يمكن انحذف"), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            original = (String) res[0];
            originalTitle = (String) res[1];
            versions = (Integer) res[2];
            @SuppressWarnings("unchecked") List<ClipRepo.Cat> ac = (List<ClipRepo.Cat>) res[3];
            @SuppressWarnings("unchecked") java.util.Set<Long> mc = (java.util.Set<Long>) res[4];
            @SuppressWarnings("unchecked") List<String> tg = (List<String>) res[5];
            allCats = ac;
            myCats = mc;
            myTags = new ArrayList<>(tg);
            renderCats();
            renderTags();
            loading = true;
            body.setText(original);
            title.setText(originalTitle);
            scEdit.setText(res[6] == null ? "" : (String) res[6]);
            body.setEnabled(true);
            loading = false;
            undo.clear(); redo.clear();
            updateStats();
            verBtn.setAlpha(versions > 0 ? 1f : 0.4f);
        });
    }

    // ------------------------------------------------------------ tags + shortcut (W48 library phase 6)

    /** The shared chip: one styled tag/category pill (12sp bold, CARD bg, 6dp gap). */
    private TextView chip(String label, int color) {
        TextView t = text(label, 12, Typeface.BOLD, color);
        t.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
        t.setBackground(round(CARD, 16, LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd((int) (6 * dp));
        t.setLayoutParams(lp);
        return t;
    }

    /** Chips: tap removes (tags are cheap to re-add), + opens the add dialog. Persisted instantly,
     *  exactly like the categories row — the body's own dirty()/save() flow stays text-only. */
    private void renderTags() {
        tagRow.removeAllViews();
        TextView lab = text("الوسوم:", 12, Typeface.NORMAL, MUTED);
        lab.setPadding(0, 0, (int) (8 * dp), 0);
        tagRow.addView(lab);
        for (final String t : myTags) {
            TextView c = chip(t, Color.WHITE);
            c.setOnClickListener(v -> { myTags.remove(t); persistTags(); });
            tagRow.addView(c);
        }
        TextView add = chip("+ وسم", 0xFF93C5FD);
        add.setOnClickListener(v -> tagDialog());
        tagRow.addView(add);
    }

    private void persistTags() {
        ClipStore.get(this).run(r -> { r.setTags(id, myTags); return true; }, ok -> renderTags());
    }

    /** The add dialog lists the library's existing tags as one-tap adds; "وسم جديد"
     *  rides the shared nameDialog. No custom view — the platform builds the list. */
    private void tagDialog() {
        ClipStore.get(this).run(r -> r.listTags(), tags -> {
            List<String> names = new ArrayList<>();
            names.add("＋ وسم جديد");
            if (tags != null) for (String t : tags) if (!myTags.contains(t)) names.add(t);
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("وسم جديد"))
                    .setItems(names.toArray(new String[0]), (d, w) -> {
                        if (myTags.size() >= ClipRepo.MAX_TAGS) { Toast.makeText(this, L.t("الحد ١٢ وسماً للنص"), Toast.LENGTH_SHORT).show(); return; }
                        if (w == 0) nameDialog("وسم جديد", "", n -> { String t = ClipRepo.normTag(n); if (!t.isEmpty() && !myTags.contains(t)) { myTags.add(t); persistTags(); } });
                        else { myTags.add(names.get(w)); persistTags(); }
                    })
                    .setNegativeButton(L.t("إلغاء"), null).show();
        });
    }

    /** The token is validated by the repo (shape, uniqueness, domain); the field shows the
     *  canonical value. Job result: null = rejected, "" = cleared, else the stored token. */
    private void saveShortcut() {
        final String sc = scEdit.getText().toString();
        ClipStore.get(this).run(r -> {
            if (!r.setShortcut(id, sc)) return null;
            String cur = r.shortcutOf(id);
            return cur == null ? "" : cur;
        }, canon -> {
            if (canon == null) {
                Toast.makeText(this, L.t("الاختصار لازم كلمة واحدة بلا مسافات وغير مستخدم"), Toast.LENGTH_LONG).show();
                return;
            }
            scEdit.setText(canon);
            Toast.makeText(this, L.t(canon.isEmpty() ? "أُزيل الاختصار" : "انحفظ الاختصار"), Toast.LENGTH_SHORT).show();
        });
    }

    /** W48 text tools: one undo step for the whole transform (same shape as replaceAll). */
    /** W57: the chip label for the current tashkeel degree (shipped CLOSED). */
    private String tashLabel() {
        int m = TashkeelEngine.mode;
        return m == TashkeelEngine.MODE_FULL ? "التشكيل: كاملة"
                : m == TashkeelEngine.MODE_AMBIG ? "التشكيل: الالتباس" : "التشكيل: مغلقة";
    }

    /** W57: cycle the three degrees — the static mirror + the persisted pref. */
    private void cycleTashkeel(TextView chip) {
        int m = (TashkeelEngine.mode + 1) % 3;
        TashkeelEngine.mode = m;
        Theme.prefs(this).edit().putInt("tashkeel_mode", m).apply();
        chip.setText(L.t(tashLabel()));
    }

    /** W57: apply the mode-gated tashkeel to the whole text (OFF → no-op). */
    private void applyTashkeel() {
        TashkeelEngine e = TashkeelEngine.attached();
        if (e == null || TashkeelEngine.mode == TashkeelEngine.MODE_OFF) return;
        transform(TashkeelEngine.apply(body.getText().toString(), e, TashkeelEngine.mode));
    }

    /** W58: the chip label for the current grammar mode (shipped CLOSED). */
    private String gramLabel() {
        int m = GrammarNudge.mode;
        return m == GrammarNudge.MODE_STRICT ? "التدقيق: صارم"
                : m == GrammarNudge.MODE_SOFT ? "التدقيق: ناعم" : "التدقيق: مغلقة";
    }

    /** W58: cycle the three grammar modes — the static mirror + the persisted pref. */
    private void cycleGrammar(TextView chip) {
        int m = (GrammarNudge.mode + 1) % 3;
        GrammarNudge.mode = m;
        Theme.prefs(this).edit().putInt("grammar_mode", m).apply();
        chip.setText(L.t(gramLabel()));
    }

    /** W60: مسح كامل من زر واحد — طبقة التكرار الشخصية تعود لحالة المصنع
     *  بايتياً: الملف المحلي يحذف وعلم adapt_reset يمحو النسخة الحية في
     *  الخدمة عند بدء الحقل القادم. لا شيء يغادر الجهاز (قانون 8). */
    private void wipePersonal() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("مسح التنبؤ الشخصي؟"))
                .setMessage(L.t("تُمحى كل الكلمات التي تعلّمها الكيبورد منك محلياً والعودة لحالة المصنع. لا شيء يغادر جهازك."))
                .setPositiveButton(L.t("مسح"), (d, w) -> {
                    new java.io.File(getFilesDir(), "adapt_store.txt").delete();
                    Theme.prefs(this).edit().putBoolean("adapt_reset", true).apply();
                    Toast.makeText(this, L.t("تم المسح — التنبؤ الشخصي في حالة المصنع"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(L.t("إلغاء"), null)
                .create().show();
    }

    /** W58: the whole-text educational scan (detached/OFF → a quiet toast; the
     *  nudges EXPLAIN — التدخل تعليمي يشرح لا يصحح قهراً — and a tap applies
     *  that ONE nudge through the W48 transform, one undo step). */
    private void scanGrammar() {
        GrammarNudge e = GrammarNudge.attached();
        if (e == null || GrammarNudge.mode == GrammarNudge.MODE_OFF) {
            Toast.makeText(this, L.t("التدقيق مغلقة — شغّله من رقاقة «التدقيق»"), Toast.LENGTH_SHORT).show();
            return;
        }
        String text = body.getText().toString();
        java.util.List<GrammarNudge.Nudge> ns =
                GrammarNudge.scan(text, e, GrammarNudge.mode, false);
        if (ns.isEmpty()) {
            Toast.makeText(this, L.t("لا ملاحظات — ما لقينا شي ننبّه عليه"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] rows = new String[ns.size()];
        for (int i = 0; i < ns.size(); i++) {
            GrammarNudge.Nudge n = ns.get(i);
            rows[i] = text.substring(n.start, n.end) + " ← " + n.fix
                    + " — " + n.explain;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("تدقيق النص — " + ns.size() + " ملاحظة"))
                .setItems(rows, (d, which) -> {
                    GrammarNudge.Nudge n = ns.get(which);
                    String out = GrammarNudge.applyOne(body.getText().toString(), n);
                    if (!out.equals(body.getText().toString())) transform(out);
                })
                .setNegativeButton(L.t("النص كما هو"), null)
                .show();
    }

    private void transform(String out) {
        if (out == null) return;
        final String cur = body.getText().toString();
        if (cur.equals(out)) return;
        undo.push(new String[]{"0", cur, out});
        redo.clear();
        applying = true;
        body.getText().replace(0, body.length(), out);
        applying = false;
        body.setSelection(out.length());
        scheduleStats();
        if (findBar.getVisibility() == View.VISIBLE) scheduleFind();
    }

    // ------------------------------------------------------------ categories

    private void renderCats() {
        catRow.removeAllViews();
        TextView lab = text("التصنيف:", 12, Typeface.NORMAL, MUTED);
        lab.setPadding(0, 0, (int) (8 * dp), 0);
        catRow.addView(lab);
        for (final ClipRepo.Cat c : allCats) {
            final boolean on = myCats.contains(c.id);
            TextView t = chip(c.name, Color.WHITE);
            if (on) t.setBackground(round(BLUE, 16, 0));
            t.setOnClickListener(v -> ClipStore.get(this).run(r -> { r.setCategory(id, c.id, !on); return true; }, ok -> {
                if (on) myCats.remove(c.id); else myCats.add(c.id);
                renderCats();
            }));
            t.setOnLongClickListener(v -> { catDialog(c); return true; });
            catRow.addView(t);
        }
        TextView add = chip("+ تصنيف جديد", 0xFF93C5FD);
        add.setOnClickListener(v -> nameDialog("تصنيف جديد", "", name -> ClipStore.get(this).run(r -> {
            long cid = r.addCategory(name);
            if (cid > 0) r.setCategory(id, cid, true);
            return new Object[]{r.categories(), r.categoriesOf(id)};
        }, this::applyCats)));
        catRow.addView(add);
    }

    @SuppressWarnings("unchecked")
    private void applyCats(Object[] res) {
        if (res == null) return;
        allCats = (List<ClipRepo.Cat>) res[0];
        myCats = (java.util.Set<Long>) res[1];
        renderCats();
    }

    private interface Named { void name(String n); }

    private void nameDialog(String titleText, String init, Named done) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(init);
        e.setTextColor(Color.WHITE);
        e.setHint(L.t("الاسم"));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t(titleText))
                .setView(e)
                .setPositiveButton(L.t("حفظ"), (d, w) -> { String n = e.getText().toString().trim(); if (!n.isEmpty()) done.name(n); })
                .setNegativeButton(L.t("إلغاء"), null).show();
    }

    private void catDialog(final ClipRepo.Cat c) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(c.name)
                .setItems(new String[]{L.t("إعادة تسمية"), L.t("حذف التصنيف (النصوص تبقى)")}, (d, which) -> {
                    if (which == 0) nameDialog("إعادة تسمية", c.name, n -> ClipStore.get(this).run(r -> {
                        if (!r.renameCategory(c.id, n)) return null;
                        return new Object[]{r.categories(), r.categoriesOf(id)};
                    }, res -> { if (res == null) Toast.makeText(this, L.t("الاسم مستخدم"), Toast.LENGTH_SHORT).show(); else applyCats(res); }));
                    else ClipStore.get(this).run(r -> { r.deleteCategory(c.id); return new Object[]{r.categories(), r.categoriesOf(id)}; }, this::applyCats);
                }).show();
    }

    private boolean dirty() {
        return !loading && (!body.getText().toString().equals(original) || !title.getText().toString().trim().equals(originalTitle.trim()));
    }

    private void save(final boolean asNew) {
        if (loading) return;
        final String t = body.getText().toString();
        final String tt = title.getText().toString();
        if (t.trim().isEmpty()) { Toast.makeText(this, L.t("ما ينفع تحفظ نص فاضي"), Toast.LENGTH_SHORT).show(); return; }
        if (t.length() > ClipRepo.MAX_LEN) { Toast.makeText(this, L.t("النص أطول من ٥٠٠ ألف حرف"), Toast.LENGTH_LONG).show(); return; }
        ClipStore.get(this).run(r -> {
            long now = System.currentTimeMillis();
            if (asNew) {
                long[] out = new long[1];
                int res = r.add(t, now, out);
                if ((res == ClipRepo.OK || res == ClipRepo.BUMPED) && !tt.trim().isEmpty()) r.setTitle(out[0], tt, now);
                return res == ClipRepo.OK || res == ClipRepo.BUMPED ? 0 : res;
            }
            int res = r.updateText(id, t, now);
            if (res == ClipRepo.OK) r.setTitle(id, tt, now);
            return res;
        }, res -> {
            if (res != null && res == ClipRepo.OK) {
                if (!asNew) { original = t; originalTitle = tt.trim(); versions++; verBtn.setAlpha(1f); }
                Toast.makeText(this, L.t(asNew ? "انحفظ كنص جديد" : "انحفظت التعديلات"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, L.t("تعذّر الحفظ، النص الأصلي ما تغيّر"), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void restoreVersion() {
        if (versions == 0) { Toast.makeText(this, L.t("ما فيه نسخة سابقة لهذا النص"), Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("استعادة النسخة السابقة؟"))
                .setMessage(L.t("النص الحالي ينحفظ كنسخة، فتقدر ترجع له."))
                .setPositiveButton(L.t("استعادة"), (d, w) -> ClipStore.get(this).run(r -> r.restorePrevious(id, System.currentTimeMillis()), res -> {
                    if (res != null && res == ClipRepo.OK) load();
                    else Toast.makeText(this, L.t("تعذّرت الاستعادة"), Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton(L.t("إلغاء"), null).show();
    }

    @Override
    public void onBackPressed() {
        if (!dirty()) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("فيه تعديلات ما انحفظت"))
                .setPositiveButton(L.t("حفظ"), (d, w) -> { save(false); ui.postDelayed(this::finish, 350); })
                .setNegativeButton(L.t("تجاهل التعديلات"), (d, w) -> finish())
                .setNeutralButton(L.t("متابعة التحرير"), null)
                .show();
    }

    // ------------------------------------------------------------ tools

    private void cutCopy(boolean cut) {
        int s = Math.min(body.getSelectionStart(), body.getSelectionEnd()), e = Math.max(body.getSelectionStart(), body.getSelectionEnd());
        if (s < 0 || s == e) { Toast.makeText(this, L.t("حدّد نص أول"), Toast.LENGTH_SHORT).show(); return; }
        setClip(body.getText().subSequence(s, e).toString());
        if (cut) body.getText().delete(s, e);
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t == null) return;
        int s = Math.max(0, Math.min(body.getSelectionStart(), body.getSelectionEnd()));
        int e = Math.max(0, Math.max(body.getSelectionStart(), body.getSelectionEnd()));
        body.getText().replace(s, e, t);
    }

    private void copyAll() {
        setClip(body.getText().toString());
        Toast.makeText(this, L.t("انسخ النص كامل"), Toast.LENGTH_SHORT).show();
    }

    private void setClip(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NeoKey", s));
    }

    private void jump(int pos) {
        body.requestFocus();
        body.setSelection(Math.max(0, Math.min(pos, body.length())));
    }

    private void undoRedo(boolean isUndo) {
        ArrayDeque<String[]> from = isUndo ? undo : redo, to = isUndo ? redo : undo;
        String[] op = from.poll();
        if (op == null) return;
        int st = Integer.parseInt(op[0]);
        String removed = op[1], inserted = op[2];
        applying = true;
        Editable e = body.getText();
        if (isUndo) { e.replace(st, Math.min(e.length(), st + inserted.length()), removed); body.setSelection(Math.min(e.length(), st + removed.length())); }
        else { e.replace(st, Math.min(e.length(), st + removed.length()), inserted); body.setSelection(Math.min(e.length(), st + inserted.length())); }
        applying = false;
        to.push(op);
        if (findBar.getVisibility() == View.VISIBLE) scheduleFind();
    }

    /** Puts the text into the field the user was typing in (the keyboard inserts it when they return). */
    private void insertAndClose() {
        String t = body.getText().toString();
        if (dirty()) save(false);
        Theme.prefs(this).edit().putString("w_insert", t).putLong("w_insert_at", System.currentTimeMillis()).apply();
        ui.postDelayed(this::finish, dirty() ? 350 : 0);
    }

    // ------------------------------------------------------------ stats

    private final Runnable statsJob = this::updateStats;
    private void scheduleStats() { ui.removeCallbacks(statsJob); ui.postDelayed(statsJob, 250); }

    private void updateStats() {
        int[] c = ClipText.counts(body.getText().toString());
        stats.setText(String.format(java.util.Locale.US, "%s %,d  •  %s %,d  •  %s %,d%s", L.t("حرف"), c[0], L.t("كلمة"), c[1], L.t("سطر"), c[2],
                dirty() ? "  •  " + L.t("غير محفوظ") : ""));
    }

    // ------------------------------------------------------------ find / replace

    private void toggleFind() {
        boolean show = findBar.getVisibility() != View.VISIBLE;
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { findQ.requestFocus(); scheduleFind(); } else clearHighlights();
    }

    private final Runnable findJob = this::runFind;
    private int findSeq;
    private void scheduleFind() { ui.removeCallbacks(findJob); ui.postDelayed(findJob, 200); }

    private void runFind() {
        final String text = body.getText().toString(), q = findQ.getText().toString();
        final boolean cs = caseSens, ww = wholeWord, im = ignoreMarks;
        final int seq = ++findSeq;
        if (q.isEmpty()) { hits = new ArrayList<>(); cur = -1; clearHighlights(); findInfo.setText(""); return; }
        // off the UI thread; an older search finishing late is ignored
        new Thread(() -> {
            final List<int[]> h = ClipText.findAll(text, q, cs, ww, im);
            ui.post(() -> {
                if (seq != findSeq || !text.equals(body.getText().toString())) return;
                hits = h;
                int caret = body.getSelectionStart();
                cur = -1;
                for (int i = 0; i < h.size(); i++) if (h.get(i)[0] >= caret) { cur = i; break; }
                if (cur < 0 && !h.isEmpty()) cur = 0;
                paintHits();
            });
        }).start();
    }

    private void clearHighlights() {
        Editable e = body.getText();
        for (BackgroundColorSpan s : e.getSpans(0, e.length(), BackgroundColorSpan.class)) e.removeSpan(s);
    }

    private void paintHits() {
        clearHighlights();
        Editable e = body.getText();
        int lim = Math.min(hits.size(), 2000);   // highlight cap keeps huge texts responsive
        for (int i = 0; i < lim; i++) {
            int[] h = hits.get(i);
            if (h[1] <= e.length()) e.setSpan(new BackgroundColorSpan(i == cur ? HL_CUR : HL), h[0], h[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (cur >= 0 && cur >= lim && hits.get(cur)[1] <= e.length())
            e.setSpan(new BackgroundColorSpan(HL_CUR), hits.get(cur)[0], hits.get(cur)[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        findInfo.setText(hits.isEmpty() ? L.t("لا نتائج") : (cur + 1) + "/" + String.format(java.util.Locale.US, "%,d", hits.size()));
        if (cur >= 0) {
            int[] h = hits.get(cur);
            if (body.getLayout() != null) body.bringPointIntoView(h[0]);
        }
    }

    private void step(int d) {
        if (hits.isEmpty()) return;
        cur = (cur + d + hits.size()) % hits.size();
        paintHits();
        int[] h = hits.get(cur);
        body.setSelection(h[0], h[1]);
    }

    private void replaceOne() {
        if (cur < 0 || cur >= hits.size()) return;
        int[] h = hits.get(cur);
        Editable e = body.getText();
        if (h[1] > e.length()) return;
        e.replace(h[0], h[1], replQ.getText().toString());
        body.setSelection(Math.min(e.length(), h[0] + replQ.length()));
        runFind();
    }

    private void replaceAll() {
        if (hits.isEmpty()) return;
        final String text = body.getText().toString();
        final String out = ClipText.replaceSpans(text, hits, replQ.getText().toString());
        int n = hits.size();
        // one undo step for the whole replace
        undo.push(new String[]{"0", text, out});
        redo.clear();
        applying = true;
        body.getText().replace(0, body.length(), out);
        applying = false;
        clearHighlights();
        hits = new ArrayList<>();
        cur = -1;
        findInfo.setText("");
        Toast.makeText(this, L.t("تم استبدال ") + String.format(java.util.Locale.US, "%,d", n) + L.t(" نتيجة"), Toast.LENGTH_SHORT).show();
        scheduleStats();
    }
}
