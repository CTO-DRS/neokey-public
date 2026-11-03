package com.hark.neokey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard export (TXT / JSON / CSV through the system "save as" picker) and import with a checked
 * preview, duplicate handling and an all-or-nothing database transaction. Files go only where the user
 * picks; nothing is sent over the network.
 */
public class ClipIOActivity extends Activity {

    static final String EXTRA_MODE = "mode", EXTRA_IDS = "ids";
    static final int MODE_EXPORT = 0, MODE_IMPORT = 1;
    private static final int REQ_CREATE = 11, REQ_OPEN = 12;
    private static final int MAX_FILE_BYTES = ClipIO.MAX_FILE_CHARS;
    private static final int BG = ClipEditActivity.BG, CARD = ClipEditActivity.CARD, LINE = ClipEditActivity.LINE,
            BLUE = ClipEditActivity.BLUE, MUTED = ClipEditActivity.MUTED;

    private float dp;
    private int mode;
    private long[] ids = new long[0];
    private LinearLayout root;

    // export state
    private int scope = ClipIO.SCOPE_ALL, format = ClipIO.F_JSON;
    private TextView countView, formatNote;

    // import state
    private String fileName = "", rawContent;
    private boolean txtLines;
    private int dupMode = ClipIO.DUP_SKIP;
    private ClipIO.Parsed parsed;
    private LinearLayout importBox;
    private TextView importBtn;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Perf.installCrashHandler(this);
        dp = getResources().getDisplayMetrics().density;
        Intent in = getIntent();
        mode = in == null ? MODE_EXPORT : in.getIntExtra(EXTRA_MODE, MODE_EXPORT);
        long[] x = in == null ? null : in.getLongArrayExtra(EXTRA_IDS);
        if (x != null && x.length > 0) { ids = x; scope = ClipIO.SCOPE_IDS; }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Glass.background());
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(L.dir());
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        final int pad = (int) (14 * dp);
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft() + pad, ins.getSystemWindowInsetTop() + pad / 2,
                    ins.getSystemWindowInsetRight() + pad, ins.getSystemWindowInsetBottom() + pad / 2);
            return ins;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = btn("", false);
        Icons.iconOnly(back, "arrow_forward", Color.WHITE, (int) (20 * dp), (int) (40 * dp));
        back.setContentDescription(L.t("رجوع"));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
        TextView h = text(mode == MODE_EXPORT ? "تصدير الحافظة" : "استيراد إلى الحافظة", 18, Typeface.BOLD, Color.WHITE);
        h.setPadding((int) (10 * dp), 0, (int) (10 * dp), 0);
        bar.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sw = text(mode == MODE_EXPORT ? "استيراد" : "تصدير", 13, Typeface.BOLD, 0xFF93C5FD);
        sw.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp), (int) (8 * dp));
        sw.setOnClickListener(v -> {
            startActivity(new Intent(this, ClipIOActivity.class).putExtra(EXTRA_MODE, mode == MODE_EXPORT ? MODE_IMPORT : MODE_EXPORT));
            finish();
        });
        bar.addView(sw);
        root.addView(bar);

        if (mode == MODE_EXPORT) buildExport(); else buildImport();
    }

    // ================================================================ export

    private void buildExport() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        box.addView(section("وش تبي تصدّر؟"));
        List<String> labels = new ArrayList<>();
        final List<Integer> scopes = new ArrayList<>();
        if (ids.length > 0) { labels.add(ids.length == 1 ? "هذا النص" : "المحدد (" + ids.length + ")"); scopes.add(ClipIO.SCOPE_IDS); }
        labels.add("الكل"); scopes.add(ClipIO.SCOPE_ALL);
        labels.add("المثبّتة"); scopes.add(ClipIO.SCOPE_PINNED);
        labels.add("المفضلة"); scopes.add(ClipIO.SCOPE_FAV);
        labels.add("القوالب"); scopes.add(ClipIO.SCOPE_TEMPLATES);
        labels.add("اختيار عناصر…"); scopes.add(-1);
        final LinearLayout scopeRow = new LinearLayout(this);
        box.addView(hscroll(scopeRow));
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            scopeRow.removeAllViews();
            for (int i = 0; i < labels.size(); i++) {
                final int sc = scopes.get(i);
                String lab = sc == ClipIO.SCOPE_IDS && labels.get(i).startsWith("المحدد") ? "المحدد (" + ids.length + ")" : labels.get(i);
                scopeRow.addView(chip(lab, sc == scope, () -> {
                    if (sc == -1) pickItems(() -> { if (!labels.get(0).startsWith("المحدد") && !labels.get(0).equals("هذا النص")) {
                            labels.add(0, "المحدد"); scopes.add(0, ClipIO.SCOPE_IDS); }
                        labels.set(0, "المحدد"); scope = ClipIO.SCOPE_IDS; paint[0].run(); updateCount(); });
                    else { scope = sc; paint[0].run(); updateCount(); }
                }));
            }
        };
        paint[0].run();
        countView = text("", 13, Typeface.NORMAL, MUTED);
        countView.setPadding(0, (int) (6 * dp), 0, 0);
        box.addView(countView);
        updateCount();

        box.addView(section("الصيغة"));
        final LinearLayout fmtRow = new LinearLayout(this);
        box.addView(hscroll(fmtRow));
        formatNote = text("", 13, Typeface.NORMAL, MUTED);
        formatNote.setPadding(0, (int) (8 * dp), 0, 0);
        formatNote.setLineSpacing(0, 1.25f);
        box.addView(formatNote);
        String[] fl = {"JSON (نسخة كاملة)", "TXT (نص فقط)", "CSV (جدول)"};
        Runnable[] fp = new Runnable[1];
        fp[0] = () -> {
            fmtRow.removeAllViews();
            for (int i = 0; i < 3; i++) { final int f = i; fmtRow.addView(chip(fl[i], f == format, () -> { format = f; fp[0].run(); })); }
            formatNote.setText(L.t(format == ClipIO.F_JSON
                    ? getString(R.string.nk_clipioactivity_updatecount_1)
                    : format == ClipIO.F_TXT
                    ? getString(R.string.nk_clipioactivity_updatecount_2)
                    : getString(R.string.nk_clipioactivity_updatecount_3)));
        };
        fp[0].run();

        TextView go = btn("اختر مكان الحفظ وصدّر", true);
        go.setOnClickListener(v -> startExport());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, (int) (50 * dp));
        gl.topMargin = (int) (12 * dp);
        root.addView(go, gl);
    }

    private void updateCount() {
        final int sc = scope;
        final long[] x = ids;
        ClipStore.get(this).run(r -> ClipIO.load(r, sc, x).size(), n -> {
            if (n != null) countView.setText(n == 0 ? L.t("ما فيه عناصر في هذا الاختيار") : L.t("بيتصدّر ") + n + L.t(" عنصر"));
        });
    }

    private void pickItems(Runnable done) {
        ClipStore.get(this).run(r -> r.db.query("SELECT id, substr(text,1,90), title FROM clips ORDER BY kind, pinned DESC, used DESC LIMIT 3000"), rows -> {
            if (rows == null || rows.isEmpty()) return;
            final long[] rid = new long[rows.size()];
            final String[] lab = new String[rows.size()];
            final boolean[] on = new boolean[rows.size()];
            java.util.Set<Long> cur = new java.util.HashSet<>();
            for (long l : ids) cur.add(l);
            for (int i = 0; i < rows.size(); i++) {
                Object[] r = rows.get(i);
                rid[i] = ClipRepo.num(r[0]);
                String t = r[2] != null ? "【" + r[2] + "】 " + r[1] : (String) r[1];
                lab[i] = t.replace('\n', ' ');
                on[i] = cur.contains(rid[i]);
            }
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("اختر العناصر"))
                    .setMultiChoiceItems(lab, on, (d, w, c) -> on[w] = c)
                    .setPositiveButton(L.t("تم"), (d, w) -> {
                        List<Long> sel = new ArrayList<>();
                        for (int i = 0; i < on.length; i++) if (on[i]) sel.add(rid[i]);
                        if (sel.isEmpty()) return;
                        ids = new long[sel.size()];
                        for (int i = 0; i < ids.length; i++) ids[i] = sel.get(i);
                        done.run();
                    })
                    .setNegativeButton(L.t("إلغاء"), null).show();
        });
    }

    private void startExport() {
        String ext = format == ClipIO.F_JSON ? "json" : format == ClipIO.F_CSV ? "csv" : "txt";
        String mime = format == ClipIO.F_JSON ? "application/json" : format == ClipIO.F_CSV ? "text/csv" : "text/plain";
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, "neokey-clipboard-" + day + "." + ext);
        try { startActivityForResult(i, REQ_CREATE); }
        catch (RuntimeException e) { alert("ما لقينا تطبيق ملفات", getString(R.string.nk_clipioactivity_startexport_1)); }
    }

    private static final class Counting extends FilterOutputStream {
        long n;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
    }

    private void doExport(final Uri uri) {
        final ContentResolver cr = getContentResolver();
        final int sc = scope, f = format;
        final long[] x = ids;
        final String ver = versionName();
        final String name = displayName(uri);
        ClipStore.get(this).run(r -> {
            List<ClipIO.Rec> items = ClipIO.load(r, sc, x);
            try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                if (os == null) throw new IOException("ما انفتح الملف للكتابة");
                Counting cnt = new Counting(os);
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(cnt, StandardCharsets.UTF_8), 64 * 1024);
                ClipIO.write(f, items, w, System.currentTimeMillis(), ver);
                w.flush();
                return new Object[]{items.size(), cnt.n, null};
            } catch (IOException | RuntimeException e) {
                return new Object[]{0, 0L, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()};
            }
        }, res -> {
            if (res == null || res[2] != null) {
                try { DocumentsContract.deleteDocument(cr, uri); } catch (Exception ignored) { }
                alert("ما تم التصدير", L.t(getString(R.string.nk_clipioactivity_doexport_1)) + "\n\n" + (res == null ? "" : res[2]));
                return;
            }
            long bytes = (Long) res[1];
            String size = bytes < 1024 ? bytes + " B" : bytes < 1048576 ? (bytes / 1024) + " KB" : String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
            alert("تم التصدير ✓", L.t("العناصر: ") + res[0] + "\n" + L.t("الملف: ") + name + "\n" + L.t("الحجم: ") + size
                    + (f == ClipIO.F_JSON ? "" : "\n\n" + L.t(getString(R.string.nk_clipioactivity_alert_1))));
        });
    }

    // ================================================================ import

    private void buildImport() {
        TextView pick = btn("اختر ملف (JSON أو TXT أو CSV)", true);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "text/csv",
                    "text/comma-separated-values", "application/csv", "application/octet-stream"});
            try { startActivityForResult(i, REQ_OPEN); }
            catch (RuntimeException e) { alert("ما لقينا تطبيق ملفات", "جهازك ما فيه منتقي ملفات."); }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, (int) (50 * dp));
        pl.topMargin = (int) (10 * dp);
        root.addView(pick, pl);
        TextView note = text(getString(R.string.nk_clipioactivity_buildimport_1), 12, Typeface.NORMAL, MUTED);
        note.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        root.addView(note);
        importBox = new LinearLayout(this);
        importBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(importBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        importBtn = btn("استيراد", true);
        importBtn.setVisibility(View.GONE);
        importBtn.setOnClickListener(v -> confirmImport());
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, (int) (50 * dp));
        il.topMargin = (int) (8 * dp);
        root.addView(importBtn, il);
    }

    private void readFile(final Uri uri) {
        fileName = displayName(uri);
        final long size = fileSize(uri);
        if (size > MAX_FILE_BYTES) { alert("الملف كبير", "الحد 20 ميجا، وحجم هذا الملف " + (size / 1048576) + " ميجا."); return; }
        showBusy("جاري فحص الملف…");
        final ContentResolver cr = getContentResolver();
        new Thread(() -> {
            String err = null, content = null;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new IOException("ما انفتح الملف");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_FILE_BYTES) throw new IOException("الملف أكبر من 20 ميجا");
                }
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bo.toByteArray())).toString();
            } catch (CharacterCodingException e) {
                err = getString(R.string.nk_clipioactivity_thread_1);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "تعذّرت قراءة الملف" : e.getMessage();
            }
            final String fe = err, fc = content;
            runOnUiThread(() -> {
                if (fe != null) { importBox.removeAllViews(); importBtn.setVisibility(View.GONE); alert("ما قدرنا نقرأ الملف", fe); return; }
                rawContent = fc;
                txtLines = false;
                analyse();
            });
        }).start();
    }

    private void analyse() {
        final String c = rawContent, n = fileName;
        final boolean lines = txtLines;
        showBusy(getString(R.string.nk_clipioactivity_analyse_1));
        ClipStore.get(this).run(r -> {
            ClipIO.Parsed p = ClipIO.parse(c, n, lines);
            if (p.error == null) ClipIO.markDuplicates(r, p);
            return p;
        }, p -> {
            if (p == null) return;
            parsed = p;
            if (p.error != null) {
                importBox.removeAllViews();
                importBtn.setVisibility(View.GONE);
                alert("الملف ما ينفع للاستيراد", p.error);
                return;
            }
            showPreview();
        });
    }

    private void showBusy(String msg) {
        importBox.removeAllViews();
        importBtn.setVisibility(View.GONE);
        TextView t = text(msg, 14, Typeface.NORMAL, MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (30 * dp), 0, 0);
        importBox.addView(t);
    }

    private void showPreview() {
        importBox.removeAllViews();
        final ClipIO.Parsed p = parsed;
        String fmt = p.format == ClipIO.F_JSON ? (p.schema > 0 ? "JSON (مخطط " + p.schema + ")" : "JSON") : p.format == ClipIO.F_CSV ? "CSV" : "TXT";
        TextView info = text("", 13, Typeface.NORMAL, Color.WHITE);
        info.setText(fileName + "  •  " + L.t(fmt) + "\n"
                + L.t("جديد: ") + p.count(ClipIO.NEW) + "   " + L.t("مكرر: ") + (p.count(ClipIO.DUP) + p.count(ClipIO.DUP_IN_FILE))
                + "   " + L.t("غير صالح: ") + p.count(ClipIO.INVALID));
        info.setLineSpacing(0, 1.3f);
        info.setPadding(0, (int) (8 * dp), 0, (int) (4 * dp));
        importBox.addView(info);
        if (p.format != ClipIO.F_JSON || p.schema == 0) {
            TextView w = text(p.format == ClipIO.F_TXT ? getString(R.string.nk_clipioactivity_showpreview_1)
                    : p.format == ClipIO.F_CSV ? getString(R.string.nk_clipioactivity_showpreview_2) : getString(R.string.nk_clipioactivity_showpreview_3), 12, Typeface.NORMAL, 0xFFFBBF24);
            importBox.addView(w);
        }
        if (p.format == ClipIO.F_TXT && rawContent != null && !rawContent.contains(ClipIO.TXT_SEP)) {
            importBox.addView(chipRow(new String[]{"الملف كامل نص واحد", "كل سطر نص مستقل"}, txtLines ? 1 : 0, i -> { txtLines = i == 1; analyse(); }));
        }
        int dups = p.count(ClipIO.DUP) + p.count(ClipIO.DUP_IN_FILE);
        if (dups > 0) {
            importBox.addView(section("المكرر"));
            importBox.addView(chipRow(new String[]{"تجاهله وأبقِ الموجود", "استورده كنسخ مستقلة", "حدّث خصائص الموجود"}, dupMode,
                    i -> { dupMode = i; refreshButton(); }));
        }
        LinearLayout sel = new LinearLayout(this);
        sel.addView(chip("تحديد الكل", false, () -> setAll(0)));
        sel.addView(chip("الجديدة فقط", false, () -> setAll(1)));
        sel.addView(chip("إلغاء التحديد", false, () -> setAll(2)));
        importBox.addView(hscroll(sel));

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setLayoutDirection(L.dir());
        adapter = new BaseAdapter() {
            @Override public int getCount() { return p.items.size(); }
            @Override public Object getItem(int i) { return p.items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) { return row(i, cv); }
        };
        lv.setAdapter(adapter);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f);
        ll.topMargin = (int) (6 * dp);
        importBox.addView(lv, ll);
        importBtn.setVisibility(View.VISIBLE);
        refreshButton();
    }

    private View row(int i, View cv) {
        final ClipIO.Cand c = parsed.items.get(i);
        LinearLayout r;
        CheckBox cb;
        TextView t, s;
        if (cv instanceof LinearLayout && cv.getTag() instanceof Object[]) {
            r = (LinearLayout) cv;
            Object[] tag = (Object[]) cv.getTag();
            cb = (CheckBox) tag[0]; t = (TextView) tag[1]; s = (TextView) tag[2];
        } else {
            r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding((int) (6 * dp), (int) (6 * dp), (int) (6 * dp), (int) (6 * dp));
            cb = new CheckBox(this);
            r.addView(cb);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            t = text("", 13, Typeface.NORMAL, Color.WHITE);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            s = text("", 11, Typeface.BOLD, MUTED);
            col.addView(t);
            col.addView(s);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setTag(new Object[]{cb, t, s});
        }
        String body = c.rec.text == null ? "" : c.rec.text.length() > 160 ? c.rec.text.substring(0, 160) : c.rec.text;
        t.setText((c.rec.title != null ? "【" + c.rec.title + "】 " : "") + body.replace("\r", "").replace('\n', ' '));
        StringBuilder st = new StringBuilder();
        int color;
        switch (c.status) {
            case ClipIO.NEW: st.append(L.t("جديد")); color = 0xFF34D399; break;
            case ClipIO.DUP: st.append(L.t("موجود في الحافظة")); color = 0xFFFBBF24; break;
            case ClipIO.DUP_IN_FILE: st.append(L.t("مكرر داخل الملف")); color = 0xFFFBBF24; break;
            default: st.append(L.t("غير صالح: ")).append(L.t(c.reason)); color = 0xFFF87171;
        }
        if (c.rec.text != null && c.rec.text.length() > 200) st.append("  •  ").append(String.format(java.util.Locale.US, "%,d", c.rec.text.length())).append(L.t(" حرف"));
        if (c.rec.pinned) st.append("  •  📌");
        if (c.rec.fav) st.append("  •  ★");
        if (c.rec.kind == 1) st.append("  •  ").append(L.t("قالب"));
        if (!c.rec.cats.isEmpty()) st.append("  •  ").append(android.text.TextUtils.join("، ", c.rec.cats));
        s.setText(st);
        s.setTextColor(color);
        cb.setOnCheckedChangeListener(null);
        cb.setChecked(c.selected);
        cb.setEnabled(c.status != ClipIO.INVALID);
        cb.setOnCheckedChangeListener((b, v) -> { c.selected = v; refreshButton(); });
        r.setOnClickListener(v -> { if (c.status != ClipIO.INVALID) cb.setChecked(!cb.isChecked()); });
        return r;
    }

    private void setAll(int how) {
        if (parsed == null) return;
        for (ClipIO.Cand c : parsed.items)
            c.selected = c.status != ClipIO.INVALID && (how == 0 || (how == 1 && c.status == ClipIO.NEW));
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshButton();
    }

    private void refreshButton() {
        if (parsed == null) return;
        int n = 0, dupSel = 0;
        for (ClipIO.Cand c : parsed.items) if (c.selected && c.status != ClipIO.INVALID) {
            boolean d = c.status == ClipIO.DUP || c.status == ClipIO.DUP_IN_FILE;
            if (d) dupSel++;
            if (!d || dupMode != ClipIO.DUP_SKIP) n++;
        }
        importBtn.setText(L.t("استيراد ") + n + L.t(" عنصر") + (dupSel > 0 && dupMode == ClipIO.DUP_SKIP ? "  (" + L.t("وتجاهل ") + dupSel + L.t(" مكرر") + ")" : ""));
        importBtn.setEnabled(n > 0);
        importBtn.setAlpha(n > 0 ? 1f : 0.45f);
    }

    private void confirmImport() {
        int dupSel = 0;
        for (ClipIO.Cand c : parsed.items) if (c.selected && (c.status == ClipIO.DUP || c.status == ClipIO.DUP_IN_FILE)) dupSel++;
        if (dupSel > 0 && dupMode == ClipIO.DUP_COPY) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("استيراد المكرر كنسخ؟"))
                    .setMessage(dupSel + L.t(getString(R.string.nk_clipioactivity_confirmimport_1)))
                    .setPositiveButton(L.t("استورد"), (d, w) -> runImport())
                    .setNegativeButton(L.t("إلغاء"), null).show();
        } else if (dupSel > 0 && dupMode == ClipIO.DUP_REPLACE) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("تحديث النصوص الموجودة؟"))
                    .setMessage(dupSel + L.t(getString(R.string.nk_clipioactivity_confirmimport_2)))
                    .setPositiveButton(L.t("حدّث"), (d, w) -> runImport())
                    .setNegativeButton(L.t("إلغاء"), null).show();
        } else runImport();
    }

    private void runImport() {
        final List<ClipIO.Cand> items = parsed.items;
        final int m = dupMode;
        importBtn.setEnabled(false);
        importBtn.setText(L.t("جاري الاستيراد…"));
        ClipStore.get(this).run(r -> ClipIO.apply(r, items, m, System.currentTimeMillis()), rep -> {
            importBtn.setEnabled(true);
            refreshButton();
            if (rep == null || rep.failed) {
                alert("ما تم الاستيراد", L.t(getString(R.string.nk_clipioactivity_refreshbutton_1))
                        + (rep != null && rep.error != null ? "\n\n" + rep.error : ""));
                return;
            }
            StringBuilder b = new StringBuilder();
            b.append(L.t("انضاف: ")).append(rep.added);
            if (rep.replaced > 0) b.append("\n").append(L.t("تحدّث: ")).append(rep.replaced);
            if (rep.skipped > 0) b.append("\n").append(L.t("تجاهلنا المكرر: ")).append(rep.skipped);
            if (rep.invalid > 0) b.append("\n").append(L.t("غير صالح: ")).append(rep.invalid);
            if (rep.unpinned > 0) b.append("\n").append(rep.unpinned).append(L.t(getString(R.string.nk_clipioactivity_alert_2)));
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("تم الاستيراد ✓"))
                    .setMessage(b.toString())
                    .setPositiveButton(L.t("تم"), (d, w) -> finish())
                    .setCancelable(false).show();
        });
    }

    // ================================================================ results

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_CREATE) doExport(data.getData());
        else if (req == REQ_OPEN) readFile(data.getData());
    }

    // ================================================================ helpers

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String s = uri.getLastPathSegment();
        return s == null ? "" : s;
    }

    private long fileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) { }
        return -1;
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t(title)).setMessage(L.t(msg)).setPositiveButton(L.t("تم"), null).show();
    }

    private TextView section(String s) {
        TextView t = text(s, 14, Typeface.BOLD, 0xFFDDE1EA);
        t.setPadding(0, (int) (16 * dp), 0, (int) (8 * dp));
        return t;
    }

    private interface Pick { void pick(int i); }

    private View chipRow(String[] labels, int sel, Pick p) {
        LinearLayout row = new LinearLayout(this);
        final int[] cur = {sel};
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            row.removeAllViews();
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                row.addView(chip(labels[i], i == cur[0], () -> { cur[0] = k; paint[0].run(); p.pick(k); }));
            }
        };
        paint[0].run();
        return hscroll(row);
    }

    private View hscroll(LinearLayout row) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        hs.setPadding(0, (int) (4 * dp), 0, (int) (4 * dp));
        return hs;
    }

    private TextView chip(String label, boolean on, Runnable click) {
        TextView t = text(label, 13, Typeface.BOLD, Color.WHITE);
        t.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
        t.setBackground(on ? round(BLUE, 18, 0) : round(CARD, 18, LINE));
        t.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
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

    private android.graphics.drawable.Drawable round(int color, int r, int stroke) {
        if (color == CARD) return Glass.panel(dp, r, false);
        if (color == BLUE) return Glass.neon(dp, r);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        if (stroke != 0) g.setStroke((int) Math.max(1, dp), stroke);
        return g;
    }
}
