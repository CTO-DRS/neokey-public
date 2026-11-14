package com.hark.neokey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Professional writing assistant. Builds a draft from templates (offline) or improves it with an optional
 * AI engine the user configures. Nothing is inserted until the user reviews it and taps «إدراج».
 */
public class WriterActivity extends Screen {
    static final String EXTRA_CTX = "ctx", EXTRA_FROM_KB = "kb";
    private int cat, tone = 1, lang, variant;
    private EditText to, from, topic, result;
    private LinearLayout body;
    private boolean fromKeyboard;
    private TextView aiBtn, count;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // W54: templates live in assets/writer_templates.txt (law 6) — strict load, fail-closed
        try (java.io.InputStream in = getAssets().open("writer_templates.txt")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] wbuf = new byte[4096]; int wn;
            while ((wn = in.read(wbuf)) > 0) bo.write(wbuf, 0, wn);
            Writer.init(bo.toByteArray());
        } catch (java.io.IOException e) { throw new IllegalStateException("writer.templates.asset", e); }
        SharedPreferences p = Theme.prefs(this);
        cat = p.getInt("w_cat", 0);
        tone = p.getInt("w_tone", 1);
        lang = p.getInt("w_lang", 0);
        fromKeyboard = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_KB, false);
        header("مساعد المراسلات", getString(R.string.nk_writeractivity_oncreate_1), "edit_note");
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        to = edit("المستلم (اختياري)", false);
        from = edit("اسمك / التوقيع (اختياري)", false);
        from.setText(p.getString("w_from", ""));
        topic = edit("الموضوع أو التفاصيل", true);
        String ctx = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CTX);
        if (ctx != null) topic.setText(ctx);
        result = edit("المسودة تظهر هنا…", true);
        result.setMinLines(8);
        result.setTextSize(16);
        count = text("", 12, Typeface.NORMAL, MUTED);
        count.setPadding((int) (4 * dp), (int) (4 * dp), 0, 0);
        result.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { count.setText(e.length() + " حرف · قابل للتعديل"); }
        });
        build();
        generate();
    }

    private void build() {
        body.removeAllViews();
        sectionIn("نوع الرسالة");
        body.addView(chips(Writer.CATS, cat, v -> { cat = v; variant = 0; save(); build(); generate(); }));
        if (cat != Writer.SHORT) {
            sectionIn("الأسلوب");
            body.addView(chips(Writer.TONES, tone, v -> { tone = v; save(); build(); generate(); }));
        }
        sectionIn("اللغة");
        body.addView(chips(Writer.LANGS, lang, v -> { lang = v; variant = 0; save(); build(); generate(); }));

        if (cat == Writer.SHORT) {
            sectionIn("اختر رداً");
            String[] r = Writer.shortReplies(lang);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < r.length; i++) {
                final int idx = i;
                TextView t = text(r[i], 15, Typeface.NORMAL, Color.WHITE);
                t.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
                t.setMinHeight((int) (48 * dp));
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                t.setBackground(round(idx == variant ? 0xFF1E2A4A : CARD, 14, idx == variant ? BLUE : LINE, idx == variant ? 2 : 1));
                if (idx == variant) Icons.withIcon(t, "check", Color.WHITE, (int) (16 * dp), false, (int) (8 * dp));
                t.setOnClickListener(v -> { variant = idx; build(); generate(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = (int) (6 * dp);
                list.addView(t, lp);
            }
            body.addView(list);
        } else {
            sectionIn("التفاصيل");
            detach(to); detach(from); detach(topic);
            body.addView(to, new LinearLayout.LayoutParams(-1, -2));
            body.addView(from, top(8));
            body.addView(topic, top(8));
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1.3f);
            l1.setMarginEnd((int) (8 * dp));
            row.addView(button("إنشاء المسودة", "edit_note", true, v -> { save(); generate(); }), l1);
            row.addView(button("صيغة أخرى", "refresh", false, v -> { variant++; generate(); }), new LinearLayout.LayoutParams(0, -2, 1f));
            body.addView(row, top(12));
        }

        sectionIn(getString(R.string.nk_writeractivity_detach_1));
        detach(result);
        body.addView(result, new LinearLayout.LayoutParams(-1, -2));
        detach(count);
        body.addView(count);
        count.setText(result.length() + " حرف · قابل للتعديل\n" + clarityLine());

        LinearLayout acts = new LinearLayout(this);
        if (fromKeyboard) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1.4f);
            l.setMarginEnd((int) (8 * dp));
            acts.addView(button("إدراج في الحقل", "send", true, v -> insert()), l);
        }
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.setMarginEnd((int) (8 * dp));
        acts.addView(button("نسخ", "copy", !fromKeyboard, v -> copy()), l2);
        acts.addView(button("مشاركة", "share", false, v -> share()), new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(acts, top(12));

        sectionIn(getString(R.string.nk_writeractivity_detach_2));
        LinearLayout ai = card();
        boolean hasKey = !Theme.prefs(this).getString("ai_key", "").isEmpty();
        TextView note = text(hasKey
                ? getString(R.string.nk_writeractivity_sectionin_1)
                : getString(R.string.nk_writeractivity_sectionin_2),
                13, Typeface.NORMAL, 0xFFB9BECA);
        ai.addView(note);
        if (hasKey) {
            aiBtn = button("حسّن الصياغة", "ai", true, v -> improve());
            ai.addView(aiBtn, top(10));
        }
        TextView cfg = text(hasKey ? "تغيير المفتاح أو النموذج" : "إضافة مفتاح", 13, Typeface.BOLD, 0xFF93B4FF);
        cfg.setPadding(0, (int) (12 * dp), 0, (int) (4 * dp));
        cfg.setMinHeight((int) (40 * dp));
        cfg.setOnClickListener(v -> aiSettings());
        ai.addView(cfg);
        // D1 (الأسبوع 6): بوابة أذونات الاتصال الخارجي — فشل مغلق، الافتراضي كله مغلق
        ai.addView(divider());
        ai.addView(netRow("الاتصال الخارجي (الكل)", NetGate.MASTER_PREF,
                new String[]{"off", "on"}, new String[]{"متوقف — كل شيء محلي", "مفتوح"}, "off"));
        ai.addView(divider());
        ai.addView(netRow("إذن تحسين الصياغة سحابياً", NetGate.capPref(NetGate.CAP_WRITER_AI),
                new String[]{"off", "ask", "on"}, new String[]{"مغلق", "اسأل قبل كل إرسال", "مفتوح"}, "off"));
        ai.addView(divider());
        TextView audit = text(getString(R.string.nk_writeractivity_sectionin_3), 12, Typeface.NORMAL, 0xFF8A8FA3);
        audit.setPadding(0, (int) (6 * dp), 0, 0);
        ai.addView(audit);
        body.addView(ai);

        // D1 (الأسبوع 6): القدرات المحلية الثلاث — 100% على الجهاز، بلا شبكة وبلا مفتاح
        sectionIn("مساعد محلي — يعمل بلا إنترنت");
        LinearLayout local = card();
        LinearLayout row1 = new LinearLayout(this);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1f);
        b1.setMarginEnd((int) (8 * dp));
        row1.addView(button("لخّص", "ai", false, v -> localSummarize()), b1);
        row1.addView(button("أبسط", "edit_note", false, v -> localRephrase(Assistant.SIMPLE)), b1);
        row1.addView(button("أرقى", "edit_note", false, v -> localRephrase(Assistant.RESPECTFUL)), new LinearLayout.LayoutParams(0, -2, 1f));
        local.addView(row1, top(8));
        local.addView(button(getString(R.string.nk_writeractivity_sectionin_4), "refresh", false, v -> localTones()), top(10));
        TextView ln = text(getString(R.string.nk_writeractivity_sectionin_5), 12, Typeface.NORMAL, 0xFF8A8FA3);
        ln.setPadding(0, (int) (6 * dp), 0, 0);
        local.addView(ln);
        body.addView(local);

        // W70 (AACBoard): صوت لمن لا صوت له — لوحة عبارات عربية بالفئات على
        // مسار الإدراج القائم: أزرار المس الكبيرة تدخل العبارة في المسودة
        // الحية، والتخصيص يعيش في القاموس المجتمعي نفسه (قصاصات موسومة
        // «aac:الفئة» — تقاس بالبطارية على الصنف النقي).
        sectionIn("لوحة التواصل — صوت لمن لا صوت له");
        LinearLayout aac = card();
        for (final String cat : AACBoard.categories()) {
            final AACBoard.Snapshot snap = AACBoard.snapshotFor(cat, null);
            if (snap == null) continue;
            TextView catBtn = text("• " + cat, 14, Typeface.BOLD, 0xFF93B4FF);
            catBtn.setMinHeight((int) (44 * dp));
            catBtn.setGravity(Gravity.CENTER_VERTICAL);
            aac.addView(catBtn, top(6));
            for (AACBoard.Row r : snap.rows) {
                LinearLayout rr = new LinearLayout(this);
                for (final String ph : r.phrases) {
                    LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1f);
                    p1.setMarginEnd((int) (6 * dp));
                    rr.addView(button(ph, "record_voice_over", false, v -> aacInsert(ph)), p1);
                }
                aac.addView(rr, top(6));
            }
        }
        TextView aacNote = text("كل عبارة تدخل المسودة عبر مسار الإدراج القائم — والتخصيص من قصاصاتك الموسومة «aac:الفئة»", 12, Typeface.NORMAL, 0xFF8A8FA3);
        aacNote.setPadding(0, (int) (8 * dp), 0, 0);
        aac.addView(aacNote);
        body.addView(aac);
    }

    /** W70 (AACBoard): الإدراج عبر المسار القائم — النص يعيش في المسودة لا طبقة ثانية. */
    private void aacInsert(String ph) {
        AACBoard.Insert ins = AACBoard.insertPlan(ph);
        if (!ins.ok) { Toast.makeText(this, L.t(ins.refusal), Toast.LENGTH_SHORT).show(); return; }
        String cur = result.getText().toString();
        result.setText(cur.isEmpty() ? ins.phrase : cur + " " + ins.phrase);
        count.setText(result.length() + " حرف · قابل للتعديل\n" + clarityLine());
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, (int) dp)));
        return v;
    }

    /** صف إعداد دوري بسيط (متوقف/اسأل/مفتوح) لبوابة الأذونات. */
    private TextView netRow(String label, final String pref, final String[] vals, final String[] names, String def) {
        SharedPreferences p = Theme.prefs(this);
        String cur = p.getString(pref, def);
        final TextView row = text(label + " · " + nameOf(cur, vals, names), 14, Typeface.BOLD, 0xFF93B4FF);
        row.setMinHeight((int) (44 * dp));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> {
            String c = Theme.prefs(this).getString(pref, def);
            int i = 0;
            for (int k = 0; k < vals.length; k++) if (vals[k].equals(c)) i = k;
            String next = vals[(i + 1) % vals.length];
            Theme.prefs(this).edit().putString(pref, next).apply();
            row.setText(label + " · " + nameOf(next, vals, names));
        });
        return row;
    }

    private static String nameOf(String v, String[] vals, String[] names) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return names[i];
        return v;
    }

    // ---- القدرات المحلية الثلاث (بلا شبكة) ------------------------------------

    private void localSummarize() {
        String src = result.getText().toString().trim();
        if (src.isEmpty()) src = clipText();
        if (src.trim().isEmpty()) { Toast.makeText(this, L.t(getString(R.string.nk_writeractivity_localsummarize_1)), Toast.LENGTH_SHORT).show(); return; }
        String out = Assistant.summarize(src, 3);
        if (out.trim().isEmpty()) { Toast.makeText(this, L.t("ما قدرت ألخص هذا النص"), Toast.LENGTH_SHORT).show(); return; }
        result.setText(out);
        result.setTextDirection(View.TEXT_DIRECTION_RTL);
        Toast.makeText(this, L.t(getString(R.string.nk_writeractivity_localsummarize_2)), Toast.LENGTH_SHORT).show();
    }

    private void localRephrase(String mode) {
        String src = result.getText().toString().trim();
        if (src.isEmpty()) src = clipText();
        if (src.trim().isEmpty()) { Toast.makeText(this, L.t(getString(R.string.nk_writeractivity_localrephrase_1)), Toast.LENGTH_SHORT).show(); return; }
        result.setText(Assistant.rephrase(src, mode));
        result.setTextDirection(View.TEXT_DIRECTION_RTL);
        Toast.makeText(this, L.t(getString(R.string.nk_writeractivity_localrephrase_2)), Toast.LENGTH_SHORT).show();
    }

    private void localTones() {
        String src = result.getText().toString().trim();
        if (src.isEmpty()) src = topic.getText().toString().trim();
        final String note = src;
        final String[] d3 = Assistant.tones(note);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * dp);
        for (int i = 0; i < d3.length; i++) {
            final int idx = i;
            TextView t = text(Assistant.TONE_NAMES[i] + "\n" + d3[i].replace("\n", " · "), 14, Typeface.NORMAL, Color.WHITE);
            t.setPadding(pad, pad, pad, pad);
            t.setBackground(round(CARD, 14, LINE, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = (int) (8 * dp);
            t.setOnClickListener(v -> {
                result.setText(d3[idx]);
                result.setTextDirection(View.TEXT_DIRECTION_RTL);
                Toast.makeText(this, L.t("صياغة " + Assistant.TONE_NAMES[idx] + " — عدّل عليها قبل الإرسال"), Toast.LENGTH_SHORT).show();
            });
            list.addView(t, lp);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("ثلاث نبرات — اضغط ما يناسبك"))
                .setView(list)
                .setNegativeButton(L.t("إلغاء"), null)
                .show();
    }

    private String clipText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence c = cm.getPrimaryClip().getItemAt(0).getText();
            return c == null ? "" : c.toString();
        } catch (Exception e) { return ""; }
    }

    private void sectionIn(String s) {
        TextView t = text(s, 14, Typeface.BOLD, 0xFFDDE1EA);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        t.setPadding((int) (4 * dp), (int) (18 * dp), (int) (4 * dp), (int) (8 * dp));
        body.addView(t);
    }

    private static void detach(View v) {
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
    }

    /**
     * W68 (ClarityMeter): مقياس الوضوح العربي الخالص على النص الحي —
     * يظهر مع كل توليد تحت المسودة مباشرة، درجة مركبة معلومة المكونات
     * (الجملة/التكرار/الحشو/التشكيل/التنوع)، حتمي بلا شبكة ولا نموذج.
     * للنص غير العربي يعلن أنه عربي خالص لا يُقاس على غير عربي.
     */
    private String clarityLine() {
        String s = result.getText() == null ? ""
                : result.getText().toString();
        boolean arabic = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '\u0621' && s.charAt(i) <= '\u064A') {
                arabic = true;
                break;
            }
        }
        if (!arabic) return ClarityMeter.NON_ARABIC;
        return ClarityMeter.evaluate(s).display;
    }

    private void save() {
        Theme.prefs(this).edit().putInt("w_cat", cat).putInt("w_tone", tone).putInt("w_lang", lang)
                .putString("w_from", from.getText().toString().trim()).apply();
    }

    private void generate() {
        result.setText(Writer.compose(cat, tone, lang, to.getText().toString(), from.getText().toString(),
                topic.getText().toString(), variant));
        result.setTextDirection(lang == 1 ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
    }

    private void insert() {
        String s = result.getText().toString();
        if (s.trim().isEmpty()) return;
        Theme.prefs(this).edit().putString("w_insert", s).putLong("w_insert_at", System.currentTimeMillis()).apply();
        Toast.makeText(this, L.t("رجعناك للحقل، النص بيندرج الحين"), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("NeoKey", result.getText().toString()));
        Toast.makeText(this, L.t("انسخ النص"), Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, result.getText().toString());
        startActivity(Intent.createChooser(i, "مشاركة الرسالة"));
    }

    private void aiSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * dp);
        box.setPadding(p, p / 2, p, 0);
        final EditText key = new EditText(this);
        key.setHint(L.t("مفتاح OpenRouter (sk-or-…)"));
        key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(Theme.prefs(this).getString("ai_key", ""));
        final EditText model = new EditText(this);
        model.setHint(L.t("النموذج"));
        model.setSingleLine(true);
        model.setText(Theme.prefs(this).getString("ai_model", "openrouter/auto"));
        box.addView(key);
        box.addView(model);
        new android.app.AlertDialog.Builder(this)
                .setTitle(L.t("محرك الذكاء الاصطناعي"))
                .setView(box)
                .setPositiveButton(L.t("حفظ"), (d, w) -> {
                    Theme.prefs(this).edit().putString("ai_key", key.getText().toString().trim())
                            .putString("ai_model", model.getText().toString().trim().isEmpty() ? "openrouter/auto" : model.getText().toString().trim()).apply();
                    build();
                })
                .setNeutralButton(L.t("حذف المفتاح"), (d, w) -> { Theme.prefs(this).edit().remove("ai_key").apply(); build(); })
                .setNegativeButton(L.t("إلغاء"), null)
                .show();
    }

    private void improve() {
        final String draft = result.getText().toString();
        if (draft.trim().isEmpty()) return;
        // D1 (الأسبوع 6): بوابة الأذونات قبل أي اتصال خارجي — فشل مغلق
        SharedPreferences gp = Theme.prefs(this);
        NetGate.Decision gd = NetGate.full(gp.getString(NetGate.MASTER_PREF, "off"),
                gp.getString(NetGate.capPref(NetGate.CAP_WRITER_AI), "off"), NetGate.CAP_WRITER_AI, false);
        audit(gd);
        if (gd.ask) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(L.t("إذن اتصال خارجي"))
                    .setMessage(L.t(getString(R.string.nk_writeractivity_audit_1)))
                    .setPositiveButton(L.t("أسمح بهذا الإرسال"), (d, w) -> {
                        audit(NetGate.ALLOW_ASK);
                        doImprove();
                    })
                    .setNegativeButton(L.t("لا"), (d, w) -> audit(NetGate.DENY_CAP))
                    .show();
            return;
        }
        if (!gd.allowed) {
            Toast.makeText(this, L.t(getString(R.string.nk_writeractivity_doimprove_1)), Toast.LENGTH_LONG).show();
            return;
        }
        doImprove();
    }

    /** سجل تدقيق محلي: القدرة والقرار والزمن فقط — لا نص مستخدم أبداً (آخر 20 سطراً). */
    private void audit(NetGate.Decision d) {
        SharedPreferences p = Theme.prefs(this);
        String old = p.getString("net_audit", "");
        String line = NetGate.auditLine(NetGate.CAP_WRITER_AI, d, System.currentTimeMillis());
        String all = old.isEmpty() ? line : line + "\n" + old;
        String[] parts = all.split("\n");
        if (parts.length > 20) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 20; i++) b.append(parts[i]).append(i < 19 ? "\n" : "");
            all = b.toString();
        }
        p.edit().putString("net_audit", all).apply();
    }

    private void doImprove() {
        final String draft = result.getText().toString();
        if (draft.trim().isEmpty()) return;
        final String key = Theme.prefs(this).getString("ai_key", "");
        final String model = Theme.prefs(this).getString("ai_model", "openrouter/auto");
        final String style = Writer.CATS[cat] + "، بأسلوب " + Writer.TONES[Math.min(tone, 2)];
        if (aiBtn != null) { aiBtn.setEnabled(false); aiBtn.setText("جاري التحسين…"); }
        new Thread(() -> {
            long t0 = SystemClock.uptimeMillis();
            String out = null, err = null;
            try {
                org.json.JSONObject req = new org.json.JSONObject();
                req.put("model", model);
                org.json.JSONArray msgs = new org.json.JSONArray();
                msgs.put(new org.json.JSONObject().put("role", "system").put("content",
                        "You are a professional writing assistant. Improve the user's message (" + style + ") for clarity, grammar and a professional tone. "
                                + "Keep the same language, meaning, names and placeholders in [brackets]. Return only the improved message text."));
                msgs.put(new org.json.JSONObject().put("role", "user").put("content", draft));
                req.put("messages", msgs);
                HttpURLConnection c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(45000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Title", "NeoKey");
                OutputStream os = c.getOutputStream();
                os.write(req.toString().getBytes("UTF-8"));
                os.close();
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                org.json.JSONObject res = new org.json.JSONObject(bo.toString("UTF-8"));
                if (code >= 400) err = res.optJSONObject("error") != null ? res.optJSONObject("error").optString("message", "خطأ " + code) : "خطأ " + code;
                else out = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
            } catch (Exception e) {
                err = "تعذر الاتصال بالمحرك";
            }
            Perf.record("ai", SystemClock.uptimeMillis() - t0);
            Perf.flush();
            final String fo = out, fe = err;
            runOnUiThread(() -> {
                if (aiBtn != null) { aiBtn.setEnabled(true); aiBtn.setText("حسّن الصياغة"); }
                if (fo != null && !fo.isEmpty()) {
                    result.setText(fo);
                    Toast.makeText(this, L.t("جاهزة. راجعها وعدّل قبل الإدراج"), Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this, fe == null ? "ما رجع نص" : fe, Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
