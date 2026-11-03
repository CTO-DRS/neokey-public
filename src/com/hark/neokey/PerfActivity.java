package com.hark.neokey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Developer performance center: local metrics, stability, benchmark + battery, prediction accuracy. */
public class PerfActivity extends Screen {
    private LinearLayout body;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Shield.ensure(this); // cheap after first call (process-wide cache)
        header("مركز قياس الأداء", getString(R.string.nk_perfactivity_oncreate_1), "monitoring");
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (body != null && !running) build();
    }

    private void build() {
        Perf.flush();
        body.removeAllViews();

        LinearLayout priv = card();
        TextView pt = text(getString(R.string.nk_perfactivity_build_1),
                13, Typeface.NORMAL, 0xFFCFE3FF);
        Icons.withIcon(pt, "shield", 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        priv.addView(pt);
        Switch sw = new Switch(this);
        sw.setText("جمع القياسات محلياً");
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(15);
        sw.setChecked(Perf.enabled());
        sw.setPadding(0, (int) (12 * dp), 0, (int) (4 * dp));
        sw.setOnCheckedChangeListener((x, on) -> Perf.setEnabled(on));
        priv.addView(sw);
        body.addView(priv, top(16));

        sectionIn("القياسات الحية", "speed");
        LinearLayout m = card();
        for (int i = 0; i < Perf.METRICS.length; i++) {
            if (i > 0) m.addView(line());
            m.addView(metricRow(i));
        }
        body.addView(m);
        TextView hint = text(getString(R.string.nk_perfactivity_sectionin_1), 12, Typeface.NORMAL, MUTED);
        hint.setPadding((int) (4 * dp), (int) (6 * dp), 0, 0);
        body.addView(hint);

        sectionIn("الثبات", "bug");
        LinearLayout st = card();
        long s = Perf.sessions(), c = Perf.crashes();
        double rate = s == 0 ? 0 : c * 100.0 / s;
        st.addView(kv("مرات فتح الكيبورد", String.valueOf(s)));
        st.addView(kv("عدد الانهيارات", String.valueOf(c)));
        st.addView(kv("معدل الانهيار", String.format(Locale.US, "%.2f٪", rate) + " · " + (c == 0 ? "ممتاز" : rate < 0.5 ? "مقبول" : "يحتاج إصلاح")));
        if (!Perf.lastCrash().isEmpty()) {
            st.addView(kv("آخر انهيار", Perf.lastCrash()));
            long ageDays = Perf.lastCrashAt() <= 0 ? 0
                    : (System.currentTimeMillis() - Perf.lastCrashAt()) / 86400000L;
            st.addView(kv(L.t("عمر آخر عطل"), ageDays == 0 ? L.t("اليوم") : ageDays + "d"));
        }
        st.addView(kv(L.t("حالة الدرع"), shieldLabel()));
        st.addView(line());
        LinearLayout srow = new LinearLayout(this);
        LinearLayout.LayoutParams sl1 = new LinearLayout.LayoutParams(0, -2, 1f);
        sl1.setMarginEnd((int) (8 * dp));
        srow.addView(button(L.t("تقرير الأعطال المجهول"), "bug", false, v -> showAnonymous()), sl1);
        srow.addView(button(L.t("خط الأساس المرجعي (P50/P95)"), "speed", false, v -> showBaseline()),
                new LinearLayout.LayoutParams(0, -2, 1f));
        st.addView(srow, top(10));
        TextView sn = text(L.t(getString(R.string.nk_perfactivity_sectionin_2)), 12, Typeface.NORMAL, MUTED);
        sn.setPadding(0, (int) (8 * dp), 0, 0);
        st.addView(sn);
        st.addView(button(L.t("تصفير عداد الأعطال"), "delete", false, v -> {
            new android.app.AlertDialog.Builder(this)
                    .setMessage(L.t("تصفير عدد الانهيارات وآخر عطل؟"))
                    .setPositiveButton(L.t("حسناً"), (d, w) -> { Perf.resetCrashes(); build(); })
                    .setNegativeButton(L.t("إلغاء"), null).show();
        }), top(10));
        body.addView(st);

        sectionIn("اختبار الأداء والبطارية", "battery");
        LinearLayout bench = card();
        bench.addView(resultView("bench", new String[][]{
                {"ops_per_sec", "عمليات اقتراح في الثانية", ""}, {"avg_ms", "متوسط زمن الاقتراح", " ms"},
                {"p95_ms", "p95 زمن الاقتراح", " ms"}, {"load_ms", "تحميل القاموس", " ms"},
                {"pss_mb", "الذاكرة بعد الاختبار", " MB"}, {"battery_mah", "البطارية المستهلكة", " mAh"},
                {"avg_current_ma", "متوسط التيار", " mA"}, {"battery_note", "ملاحظة البطارية", ""}}));
        TextView bb = button("تشغيل الاختبار (30 ثانية)", "speed", true, v -> runBench());
        bench.addView(bb, top(10));
        TextView bh = text(getString(R.string.nk_perfactivity_sectionin_3), 12, Typeface.NORMAL, MUTED);
        bh.setPadding(0, (int) (8 * dp), 0, 0);
        bench.addView(bh);
        body.addView(bench);

        sectionIn("دقة التنبؤ بالكلمات", "target");
        LinearLayout acc = card();
        acc.addView(resultView("accuracy", new String[][]{
                {"ar_c2", "عربي: إكمال من حرفين (أعلى 3)", "٪"}, {"ar_c3", "عربي: إكمال من 3 أحرف (أعلى 3)", "٪"},
                {"ar_next", "عربي: الكلمة التالية (أعلى 3)", "٪"}, {"en_c2", "English: 2-letter completion (top 3)", "٪"},
                {"en_c3", "English: 3-letter completion (top 3)", "٪"}, {"en_next", "English: next word (top 3)", "٪"},
                {"cases", "عدد حالات الاختبار", ""}}));
        acc.addView(button("تشغيل اختبار الدقة", "target", true, v -> runAccuracy()), top(10));
        TextView ah = text(getString(R.string.nk_perfactivity_sectionin_4),
                12, Typeface.NORMAL, MUTED);
        ah.setPadding(0, (int) (8 * dp), 0, 0);
        acc.addView(ah);
        body.addView(acc);

        LinearLayout acts = new LinearLayout(this);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        l1.setMarginEnd((int) (8 * dp));
        acts.addView(button("تصدير التقرير", "upload", false, v -> export()), l1);
        acts.addView(button("مسح القياسات", "delete", false, v -> {
            Perf.clear();
            Toast.makeText(this, L.t("انمسحت القياسات"), Toast.LENGTH_SHORT).show();
            build();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(acts, top(20));
    }

    private void sectionIn(String s, String icon) {
        TextView t = text(s, 15, Typeface.BOLD, 0xFFDDE1EA);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        Icons.withIcon(t, icon, 0xFF93B4FF, (int) (20 * dp), false, (int) (8 * dp));
        t.setPadding((int) (4 * dp), (int) (22 * dp), (int) (4 * dp), (int) (10 * dp));
        body.addView(t);
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) dp));
        return v;
    }

    private View kv(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        r.addView(text(k, 14, Typeface.NORMAL, 0xFFB9BECA), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = text(v, 14, Typeface.BOLD, Color.WHITE);
        val.setTextDirection(View.TEXT_DIRECTION_LTR);
        r.addView(val);
        r.setContentDescription(k + ": " + v);
        return r;
    }

    private View metricRow(int i) {
        String[] d = Perf.METRICS[i];
        Perf.Stat s = Perf.get(d[0]);
        double p50 = s.pct(0.5f), p95 = s.pct(0.95f);
        String status = Perf.status(i, p50);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(d[1], 14, Typeface.BOLD, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
        // status: word + icon shape, colour is only an extra cue
        int sc = status.equals("ممتاز") ? 0xFF22C55E : status.equals("مقبول") ? 0xFFF59E0B : status.equals("بطيء") ? 0xFFEF4444 : MUTED;
        String si = status.equals("ممتاز") ? "check" : status.equals("مقبول") ? "schedule" : status.equals("بطيء") ? "bolt" : "close";
        TextView chip = text(status, 12, Typeface.BOLD, sc);
        Icons.withIcon(chip, si, sc, (int) (14 * dp), false, (int) (4 * dp));
        chip.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
        chip.setBackground(round(Theme.withAlpha(sc, 0x22), 12, Theme.withAlpha(sc, 0x66)));
        top.addView(chip);
        col.addView(top);
        String u = " " + d[2];
        String line = s.count == 0 ? "لا توجد بيانات بعد"
                : String.format(Locale.US, "p50 %s%s · p95 %s%s · أقصى %s%s · العدد %d", fmt(p50), u, fmt(p95), u, fmt(s.max), u, s.count);
        TextView sub = text(line, 12, Typeface.NORMAL, MUTED);
        sub.setPadding(0, (int) (4 * dp), 0, 0);
        col.addView(sub);
        col.setContentDescription(d[1] + "، " + status + "، " + line);
        return col;
    }

    private static String fmt(double v) { return v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v); }

    private View resultView(String key, String[][] rows) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String r = Perf.result(key);
        if (r == null) {
            box.addView(text("ما تم تشغيله بعد.", 13, Typeface.NORMAL, MUTED));
            return box;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(r);
            for (String[] row : rows) if (o.has(row[0])) box.addView(kv(row[1], o.get(row[0]) + row[2]));
            box.addView(kv("آخر تشغيل", android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", o.optLong("at")).toString()));
        } catch (Exception ignored) { }
        return box;
    }

    // ------------------------------------------------------------ tests

    private List<String> sentences(String lang) {
        List<String> l = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("testset_" + lang + ".txt"), "UTF-8"));
            String s;
            while ((s = r.readLine()) != null) if (!s.trim().isEmpty()) l.add(s.trim());
            r.close();
        } catch (Exception ignored) { }
        return l;
    }

    private Dictionary loadDict(String lang) {
        Dictionary d = new Dictionary(this, lang, true);
        long end = SystemClock.uptimeMillis() + 25000;
        while (!d.isLoaded() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        return d;
    }

    private void busy(String msg) {
        running = true;
        body.removeAllViews();
        LinearLayout c = card();
        TextView t = text(msg, 15, Typeface.BOLD, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (30 * dp), 0, (int) (30 * dp));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        c.addView(t);
        body.addView(c, top(16));
    }

    private void runBench() {
        if (running) return;
        busy(getString(R.string.nk_perfactivity_runbench_1));
        new Thread(() -> {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int plug = bi == null ? 0 : bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                long t0 = SystemClock.uptimeMillis();
                Dictionary d = loadDict("ar");
                long load = SystemClock.uptimeMillis() - t0;
                List<String> words = new ArrayList<>();
                for (String s : sentences("ar")) Collections.addAll(words, s.split(" "));
                for (String s : sentences("en")) Collections.addAll(words, s.split(" "));
                Dictionary en = loadDict("en");
                int c0 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                Random rnd = new Random(7);
                long start = SystemClock.uptimeMillis(), lastSample = 0;
                long ops = 0;
                double curSum = 0;
                int curN = 0;
                List<Float> lat = new ArrayList<>();
                while (SystemClock.uptimeMillis() - start < 30000) {
                    String w = words.get(rnd.nextInt(words.size()));
                    Dictionary dd = w.charAt(0) < 128 ? en : d;
                    long n0 = System.nanoTime();
                    dd.suggest(w.substring(0, Math.min(2, w.length())), 3);
                    dd.predict(w, 3);
                    float ms = (System.nanoTime() - n0) / 1e6f;
                    if (lat.size() < 20000) lat.add(ms);
                    ops++;
                    long now = SystemClock.uptimeMillis();
                    if (now - lastSample > 1000) {
                        lastSample = now;
                        int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                        if (cur != Integer.MIN_VALUE && cur != 0) { curSum += Math.abs(cur); curN++; }
                    }
                }
                int c1 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                Collections.sort(lat);
                double sum = 0;
                for (float f : lat) sum += f;
                android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
                android.os.Debug.getMemoryInfo(mi);
                o.put("at", System.currentTimeMillis());
                o.put("duration_s", 30);
                o.put("ops", ops);
                o.put("ops_per_sec", ops / 30);
                o.put("avg_ms", Perf.round(sum / Math.max(1, lat.size())));
                o.put("p95_ms", Perf.round(lat.isEmpty() ? 0 : lat.get((int) (lat.size() * 0.95))));
                o.put("load_ms", load);
                o.put("pss_mb", Perf.round(mi.getTotalPss() / 1024.0));
                boolean chargeOk = c0 > 0 && c1 > 0 && c0 != Integer.MIN_VALUE && c1 != Integer.MIN_VALUE;
                if (chargeOk) o.put("battery_mah", Perf.round(Math.max(0, c0 - c1) / 1000.0));
                if (curN > 0) {
                    double ma = curSum / curN;
                    if (ma > 5000) ma /= 1000.0; // most phones report µA
                    o.put("avg_current_ma", Perf.round(ma));
                }
                o.put("battery_note", plug != 0 ? "الشاحن موصول، القراءة غير دقيقة"
                        : chargeOk || curN > 0 ? getString(R.string.nk_perfactivity_thread_1) : "الجهاز ما يوفر قراءة البطارية");
                Perf.putResult("bench", o.toString());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, L.t("تعذر إكمال الاختبار"), Toast.LENGTH_SHORT).show());
            }
            runOnUiThread(() -> { running = false; build(); });
        }).start();
    }

    private void runAccuracy() {
        if (running) return;
        busy("جاري اختبار دقة التنبؤ…");
        new Thread(() -> {
            org.json.JSONObject o = new org.json.JSONObject();
            int cases = 0;
            try {
                for (String lang : new String[]{"ar", "en"}) {
                    Dictionary d = loadDict(lang);
                    int c2 = 0, c3 = 0, n2 = 0, n3 = 0, nx = 0, nn = 0;
                    for (String s : sentences(lang)) {
                        String[] w = s.split(" ");
                        for (int i = 0; i < w.length; i++) {
                            String target = Dictionary.norm(w[i]);
                            if (w[i].length() >= 3) { n2++; if (hit(d.suggest(w[i].substring(0, 2), 3), target)) c2++; }
                            if (w[i].length() >= 4) { n3++; if (hit(d.suggest(w[i].substring(0, 3), 3), target)) c3++; }
                            if (i > 0) { nn++; if (hit(d.predict(w[i - 1], 3), target)) nx++; }
                        }
                    }
                    cases += n2 + n3 + nn;
                    o.put(lang + "_c2", pct(c2, n2));
                    o.put(lang + "_c3", pct(c3, n3));
                    o.put(lang + "_next", pct(nx, nn));
                }
                o.put("cases", cases);
                o.put("at", System.currentTimeMillis());
                Perf.putResult("accuracy", o.toString());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, L.t("تعذر إكمال الاختبار"), Toast.LENGTH_SHORT).show());
            }
            runOnUiThread(() -> { running = false; build(); });
        }).start();
    }

    private static boolean hit(List<String> l, String target) {
        if (l == null) return false;
        for (String s : l) if (Dictionary.norm(s).equals(target)) return true;
        return false;
    }

    private static double pct(int a, int n) { return n == 0 ? 0 : Math.round(a * 1000.0 / n) / 10.0; }

    // ------------------------------------------------- stability panel (item A)

    /** Timing metrics recorded in the Phase-0 exit criterion (board open, press, switching, suggestions). */
    private String shieldLabel() {
        switch (Shield.current()) {
            case OK:       return L.t("سليم");
            case MISMATCH: return L.t("توقيع غير رسمي");
            case SANDBOX:  return L.t("بناء تطوير");
            default:       return L.t("لم يُفحص");
        }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private int versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    /** The anonymous stability report: numbers + our sanitized own frame only (CrashReport contract). */
    private void showAnonymous() {
        final String body = CrashReport.build(versionName(), versionCode(), android.os.Build.VERSION.SDK_INT,
                Perf.sessions(), Perf.crashes(), Perf.lastCrash(), Perf.lastCrashAt(),
                Shield.current().name(), Perf.STAB_KEYS, Perf.stabPct(), System.currentTimeMillis());
        shareDialog(L.t("تقرير الاستقرار المجهول"), body);
    }

    /** The recorded P50/P95 baseline (Phase-0 exit criterion) — file it next to the release. */
    private void showBaseline() {
        final String body = CrashReport.baseline(Perf.STAB_KEYS, Perf.stabPct(), versionCode(),
                android.os.Build.VERSION.SDK_INT, System.currentTimeMillis());
        shareDialog(L.t("خط الأساس المرجعي"), body);
    }

    private void shareDialog(String title, String body) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setItems(new String[]{L.t("مشاركة"), L.t("نسخ")}, (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, title.toString());
                        i.putExtra(Intent.EXTRA_TEXT, body);
                        startActivity(Intent.createChooser(i, L.t("مشاركة")));
                    } else {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("report", body));
                        Toast.makeText(this, L.t("انسخ التقرير"), Toast.LENGTH_SHORT).show();
                    }
                }).setPositiveButton(L.t("حسناً"), null).show();
    }

    private void export() {
        final String r = Perf.report(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("تقرير الأداء (أرقام فقط)"))
                .setItems(new String[]{"مشاركة", "نسخ"}, (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, "NeoKey performance report");
                        i.putExtra(Intent.EXTRA_TEXT, r);
                        startActivity(Intent.createChooser(i, "مشاركة التقرير"));
                    } else {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("report", r));
                        Toast.makeText(this, L.t("انسخ التقرير"), Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
}
