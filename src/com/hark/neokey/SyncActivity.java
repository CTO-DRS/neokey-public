package com.hark.neokey;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * شاشة الحساب والمزامنة — المرحلة 2، بند A2 (الأسبوع 2): «شاشة حساب: تسجيل/
 * استعادة/نسخ احتياطي ملفي مشفر للسحابة الذاتية» + «المزامنة تمر من NetGate
 * بإذن قدرت جديد (sync) — فشل مغلق كما هو، الافتراضي مغلق» + «سطر تدقيق بلا
 * أي محتوى».
 *
 * انضباط العبارة (E2EE حقيقي): عبارة الحساب لا تُخزن إطلاقاً — تُشتق منها
 * المفاتيح في الذاكرة عند التسجيل أو «فتح الحساب»، وتزول بوفاة العملية. إعادة
 * فتح الجهاز الجديد = إدخال العبارة مرة واحدة (نفس عقد الاستعادة في الخارطة:
 * «بمفتاح مشتق من حساب المستخدم نفسه»). كل العمليات على خيط خلفي عبر ClipStore
 * والنتيجة تُنشر على الواجهة.
 */
public class SyncActivity extends Screen {

    private static final int EXPORT_FILE = 61, IMPORT_FILE = 62;

    /** عبارة الجلسة — ذاكرة فقط، بلا تخزين دائم (المفتاح لا ينام على القرص). */
    private static volatile String sessionPhrase;

    private TextView status;
    private EditText pass, pass2, unlock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        header("الحساب والمزامنة", "مشفرة طرف-لطرف", "shield");

        status = text("", 13, Typeface.NORMAL, 0xFFB9BECA);
        status.setPadding((int) (4 * dp), (int) (14 * dp), (int) (4 * dp), 0);
        root.addView(status);

        buildAccount();
        buildNetGate();
        buildFileBackup();
        refreshStatus();
    }

    // ---- الحساب: تسجيل / فتح ------------------------------------------------------

    private void buildAccount() {
        section("الحساب");
        LinearLayout c = card();
        if (!registered()) {
            TextView info = text("العبارة هي مفتاح التشفير الوحيد — لا استعادة إن نُسيت.",
                    13, Typeface.NORMAL, MUTED);
            info.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
            c.addView(info);
            pass = edit("عبارة الحساب", false);
            pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            c.addView(pass, top(6));
            pass2 = edit("تأكيد العبارة", false);
            pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            c.addView(pass2, top(8));
            c.addView(button("إنشاء الحساب", "lock", true, v -> doRegister()), top(12));
        } else if (sessionPhrase == null) {
            TextView info = text("أدخل عبارة حسابك لفتح هذه الجلسة — لا تُخزن أبدًا.",
                    13, Typeface.NORMAL, MUTED);
            info.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
            c.addView(info);
            unlock = edit("عبارة الحساب", false);
            unlock.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            c.addView(unlock, top(6));
            c.addView(button("فتح الحساب", "lock", true, v -> doUnlock()), top(12));
        } else {
            c.addView(button("مزامنة الآن", "refresh", true, v -> syncNow()));
            c.addView(button("استعادة حالتي", "open", false, v -> restoreNow()), top(10));
            TextView leave = text("تسجيل خروج", 14, Typeface.BOLD, 0xFFF87171);
            leave.setGravity(Gravity.CENTER);
            leave.setMinHeight((int) (46 * dp));
            leave.setOnClickListener(v -> new android.app.AlertDialog.Builder(this,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(L.t("يُحذف سجل الحساب من هذا الجهاز فقط."))
                    .setPositiveButton(L.t("خروج"), (d, w) -> {
                        sessionPhrase = null;
                        Theme.prefs(this).edit().remove("sync_salt").remove("sync_account")
                                .remove("sync_rev").remove("sync_last").apply();
                        buildAccount(); refreshStatus();
                    }).setNegativeButton(L.t("إلغاء"), null).show());
            c.addView(leave, top(6));
        }
        root.addView(c, root.getChildCount());
    }

    private void doRegister() {
        String p1 = pass.getText().toString(), p2 = pass2.getText().toString();
        if (p1.length() < 8) { toast("8 محارف على الأقل"); return; }
        if (!p1.equals(p2)) { toast("العبارتان غير متطابقتين"); return; }
        try {
            SyncClient.Session s = SyncClient.register(p1, null);
            sessionPhrase = p1;
            Theme.prefs(this).edit()
                    .putString("sync_salt", SyncVault.b64url(s.salt))
                    .putString("sync_account", s.keys.accountId)
                    .putInt("sync_rev", 1)
                    .putBoolean("sync_vip", false)
                    .apply();
            audit("register", true, 0);
            toast("تم إنشاء الحساب");
            rebuild();
        } catch (RuntimeException e) {
            audit("register", false, 0);
            toast("تعذّر: " + e.getMessage());
        }
    }

    private void doUnlock() {
        String p = unlock.getText().toString();
        android.content.SharedPreferences prefs = Theme.prefs(this);
        try {
            byte[] salt = SyncVault.b64urlDecode(prefs.getString("sync_salt", ""));
            SyncClient.Session s = SyncClient.register(p, salt);
            if (!s.keys.accountId.equals(prefs.getString("sync_account", "")))
                throw new SyncVault.SyncRefused("auth_failed");
            sessionPhrase = p;
            audit("unlock", true, 0);
            rebuild();
        } catch (RuntimeException e) {
            audit("unlock", false, 0);
            toast("عبارة خاطئة");
        }
    }

    private void rebuild() {
        // إعادة بناء بطاقات الشاشة بعد الترويسة وسطر الحالة (الموضعان 0 و1 يبقيان)
        root.removeViews(2, root.getChildCount() - 2);
        buildAccount();
        buildNetGate();
        buildFileBackup();
        refreshStatus();
    }

    // ---- المزامنة (دفع + سحب) عبر NetGate ------------------------------------------

    private void syncNow() {
        final SyncClient.Session s = session();
        if (s == null) return;
        // البوابة أولاً — الافتراضي مغلق، و"اسأل" يعرض موافقة صريحة لهذا الإرسال
        android.content.SharedPreferences gp = Theme.prefs(this);
        NetGate.Decision d = SyncClient.gate(gp.getString(NetGate.MASTER_PREF, "off"),
                gp.getString(NetGate.capPref(NetGate.CAP_SYNC), "off"), false);
        auditGate(d);
        if (d.ask) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("إذن المزامنة"))
                    .setMessage(L.t("ترفع حالتك المشفرة للخادم — لا يفكها إلا عبارة حسابك. تسمح؟"))
                    .setPositiveButton(L.t("أسمح"), (dg, w) -> { auditGate(NetGate.ALLOW_ASK); runSync(s); })
                    .setNegativeButton(L.t("لا"), (dg, w) -> auditGate(NetGate.DENY_CAP))
                    .show();
            return;
        }
        if (!d.allowed) { toast("المزامنة مغلقة — فعّلها من «أذونات الاتصال» أدناه"); return; }
        runSync(s);
    }

    private void runSync(SyncClient.Session s) {
        busy(true);
        final String master = Theme.prefs(this).getString(NetGate.MASTER_PREF, "off");
        final String cap = Theme.prefs(this).getString(NetGate.capPref(NetGate.CAP_SYNC), "off");
        final boolean vip = Theme.prefs(this).getBoolean("sync_vip", false);
        final int rev = Theme.prefs(this).getInt("sync_rev", 1);
        ClipStore.get(this).run(new ClipStore.Job<String>() {
            @Override public String run(ClipRepo r) {
                try {
                    List<SyncVault.Rec> local = SyncState.collect(snapshot(r), rev, System.currentTimeMillis());
                    SyncClient.Transport t = new SyncRestTransport();
                    SyncClient.PushResult pr = SyncClient.push(s, t, local, master, cap, true, vip);
                    audit("push", true, pr.bytes);
                    List<SyncVault.Rec> merged = SyncClient.pull(s, t, local, master, cap, true);
                    SyncState.Plan plan = SyncState.apply(merged);
                    applyPlan(plan, r);
                    Theme.prefs(SyncActivity.this).edit()
                            .putInt("sync_rev", rev + 1)
                            .putLong("sync_last", System.currentTimeMillis()).apply();
                    audit("pull", true, plan.count());
                    return "تمت المزامنة";
                } catch (RuntimeException e) {
                    audit("sync", false, 0);
                    return explain(e);
                }
            }
        }, new ClipStore.Done<String>() {
            @Override public void done(String msg) { busy(false); refreshStatus(); toast(msg); }
        });
    }

    private void restoreNow() {
        final SyncClient.Session s = session();
        if (s == null) return;
        android.content.SharedPreferences gp = Theme.prefs(this);
        NetGate.Decision d = SyncClient.gate(gp.getString(NetGate.MASTER_PREF, "off"),
                gp.getString(NetGate.capPref(NetGate.CAP_SYNC), "off"), false);
        auditGate(d);
        if (!d.allowed) { toast("المزامنة مغلقة — فعّل الإذن أدناه"); return; }
        busy(true);
        final String master = gp.getString(NetGate.MASTER_PREF, "off");
        final String cap = gp.getString(NetGate.capPref(NetGate.CAP_SYNC), "off");
        ClipStore.get(this).run(new ClipStore.Job<String>() {
            @Override public String run(ClipRepo r) {
                try {
                    List<SyncVault.Rec> local = SyncState.collect(snapshot(r), 1, System.currentTimeMillis());
                    List<SyncVault.Rec> merged = SyncClient.pull(s, new SyncRestTransport(), local, master, cap, true);
                    SyncState.Plan plan = SyncState.apply(merged);
                    applyPlan(plan, r);
                    audit("restore", true, plan.count());
                    return "تمت الاستعادة";
                } catch (RuntimeException e) {
                    audit("restore", false, 0);
                    return explain(e);
                }
            }
        }, new ClipStore.Done<String>() {
            @Override public void done(String msg) { busy(false); refreshStatus(); toast(msg); }
        });
    }

    // ---- لقطة المخازن الحقيقية (داخل خيط ClipStore) ----------------------------------

    /** يقرأ المخازن الحقيقية نفسها: prefs + ملفات التعلّم + الحافظة المثبتة. */
    private SyncState.Snapshot snapshot(ClipRepo r) {
        SyncState.Snapshot s = new SyncState.Snapshot();
        android.content.SharedPreferences p = Theme.prefs(this);
        s.themeIdx = p.getInt("theme", 0);
        for (String lang : new String[]{"ar", "en"}) {
            File f = new File(getFilesDir(), "user_" + lang + ".txt");
            if (!f.exists()) continue;
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    int t = line.lastIndexOf('\t');
                    if (t > 0) s.dictWords.put(line.substring(0, t), lang);
                }
                in.close();
            } catch (Exception ignored) { }
        }
        s.exceptionWords.addAll(Exceptions.parse(p.getString("nocorrect", "")));
        s.phrases.addAll(Theme.userPhrases(this));
        s.shortcuts.putAll(Theme.shortcuts(this));
        try {
            List<ClipItem> pins = r.list(ClipRepo.TAB_PINNED, SyncState.CAP_PINS);
            for (ClipItem it : pins) {
                String full = r.getText(it.id);
                if (full != null && !full.isEmpty()) s.pinTexts.add(full);
            }
        } catch (Exception ignored) { }
        for (String k : SyncState.SETTINGS_WHITELIST) {
            Object v = p.getAll().get(k);
            if (v == null) continue;
            String enc = v instanceof Boolean ? "b:" + v
                    : v instanceof Integer ? "i:" + v
                    : v instanceof Long ? "l:" + v
                    : v instanceof Float ? "f:" + v
                    : v instanceof String ? "s:" + v : null;
            if (enc != null) s.settings.put(k, enc);
        }
        return s;
    }

    /** يكتب خطة الاستعادة في المخازن الحقيقية (ترتيب آمن: قاموس ← إعدادات ← حافظة). */
    private void applyPlan(SyncState.Plan plan, ClipRepo r) {
        android.content.SharedPreferences p = Theme.prefs(this);
        android.content.SharedPreferences.Editor e = p.edit();
        if (plan.themeIdx >= 0 && plan.themeIdx < Theme.ALL.length) e.putInt("theme", plan.themeIdx);
        appendDict("user_ar.txt", plan.dictAr);
        appendDict("user_en.txt", plan.dictEn);
        List<String> exc = Exceptions.parse(p.getString("nocorrect", ""));
        for (String w : plan.exceptions) exc = Exceptions.add(exc, w);
        e.putString("nocorrect", Exceptions.serialize(exc));
        List<String> ph = Theme.userPhrases(this);
        for (String w : plan.phrases) if (!ph.contains(w) && ph.size() < SyncState.CAP_PHRASES) ph.add(w);
        e.putString("phrases", android.text.TextUtils.join("\u0001", ph));
        Map<String, String> sc = Theme.shortcuts(this);
        for (Map.Entry<String, String> x : plan.shortcuts.entrySet()) sc.put(x.getKey(), x.getValue());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> x : sc.entrySet())
            sb.append(x.getKey()).append('\u001F').append(x.getValue()).append('\u0001');
        e.putString("shortcuts", sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "");
        for (Map.Entry<String, String> x : plan.settings.entrySet()) {
            String v = x.getValue();
            if (v == null || v.length() < 3) continue;
            String val = v.substring(2);
            try {
                switch (v.charAt(0)) {
                    case 'b': e.putBoolean(x.getKey(), Boolean.parseBoolean(val)); break;
                    case 'i': e.putInt(x.getKey(), Integer.parseInt(val)); break;
                    case 'l': e.putLong(x.getKey(), Long.parseLong(val)); break;
                    case 'f': e.putFloat(x.getKey(), Float.parseFloat(val)); break;
                    default: e.putString(x.getKey(), val);
                }
            } catch (Exception ignored) { }
        }
        e.putBoolean("dict_reload", true).apply();
        // التثبيتات: إضافة فوارَدة بالنص الكامل (الموجود لا يُمس ولا يُحذف)
        try {
            for (String text : plan.pins) {
                if (text == null || text.isEmpty() || text.length() > ClipRepo.MAX_LEN) continue;
                long[] out = new long[1];
                int res = r.add(text, System.currentTimeMillis(), out);
                if (out[0] > 0 && (res == ClipRepo.OK || res == ClipRepo.BUMPED)) r.pin(out[0]);
            }
        } catch (Exception ignored) { }
    }

    private void appendDict(String name, List<String> words) {
        if (words.isEmpty()) return;
        try {
            LinkedHashMap<String, Boolean> have = new LinkedHashMap<>();
            File f = new File(getFilesDir(), name);
            if (f.exists()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    int t = line.lastIndexOf('\t');
                    if (t > 0) have.put(line.substring(0, t), Boolean.TRUE);
                }
                in.close();
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            for (String word : words) if (!have.containsKey(word)) w.write(word + "\t1\n");
            w.close();
        } catch (Exception ignored) { }
    }

    // ---- بوابة الشبكة (الرئيسي + قدرة المزامنة) --------------------------------------

    private void buildNetGate() {
        section("الأذونات");
        LinearLayout c = card();
        c.addView(netRow("الاتصال الخارجي (الكل)", NetGate.MASTER_PREF,
                new String[]{"off", "on"}, new String[]{"متوقف", "مفتوح"}, "off"));
        c.addView(divider());
        c.addView(netRow("إذن المزامنة", NetGate.capPref(NetGate.CAP_SYNC),
                new String[]{"off", "ask", "on"}, new String[]{"مغلق", "اسأل", "مفتوح"}, "off"));
        c.addView(divider());
        TextView note = text("بايتات مشفرة فقط، وكل إرسال بسطر تدقيق بلا نص.",
                12, Typeface.NORMAL, 0xFF8A8FA3);
        note.setPadding(0, (int) (6 * dp), 0, 0);
        c.addView(note);
        root.addView(c);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, (int) dp)));
        return v;
    }

    private TextView netRow(String label, final String pref, final String[] vals, final String[] names, String def) {
        String cur = Theme.prefs(this).getString(pref, def);
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

    // ---- النسخة الاحتياطية الملفية المشفرة (.nsf1 — بلا شبكة) -------------------------

    private void buildFileBackup() {
        section("نسخة احتياطية .nsf1");
        LinearLayout c = card();
        TextView info = text("لا يُفتح إلا بعبارة حسابك.",
                13, Typeface.NORMAL, MUTED);
        info.setPadding(0, (int) (4 * dp), 0, (int) (10 * dp));
        c.addView(info);
        c.addView(button("حفظ نسخة .nsf1", "download", true, v -> {
            if (session() == null) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "NeoKey-sync.nsf1");
            startActivityForResult(i, EXPORT_FILE);
        }), top(8));
        c.addView(button("استعادة من ملف .nsf1", "upload", false, v -> {
            if (session() == null) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, IMPORT_FILE);
        }), top(10));
        root.addView(c);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        final SyncClient.Session s = session();
        if (s == null) return;
        busy(true);
        final ClipStore store = ClipStore.get(this);
        if (req == EXPORT_FILE) {
            final int rev = Theme.prefs(this).getInt("sync_rev", 1);
            store.run(new ClipStore.Job<String>() {
                @Override public String run(ClipRepo r) {
                    try {
                        byte[] file = SyncClient.exportBackup(s,
                                SyncState.collect(snapshot(r), rev, System.currentTimeMillis()));
                        java.io.OutputStream os = getContentResolver().openOutputStream(u);
                        os.write(file);
                        os.close();
                        audit("export", true, file.length);
                        return "تم الحفظ";
                    } catch (RuntimeException ex) { audit("export", false, 0); return explain(ex); }
                    catch (Exception ex) { audit("export", false, 0); return "تعذّر الحفظ"; }
                }
            }, new ClipStore.Done<String>() {
                @Override public void done(String msg) { busy(false); toast(msg); }
            });
        } else if (req == IMPORT_FILE) {
            store.run(new ClipStore.Job<String>() {
                @Override public String run(ClipRepo r) {
                    try {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        java.io.InputStream in = getContentResolver().openInputStream(u);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                            if (bo.size() > SyncVault.MAX_BLOB_BYTES) { in.close(); throw new SyncVault.SyncRefused("too_big"); }
                        }
                        in.close();
                        List<SyncVault.Rec> state = SyncClient.importBackup(s, bo.toByteArray());
                        List<SyncVault.Rec> local = SyncState.collect(snapshot(r), 1, System.currentTimeMillis());
                        SyncState.Plan plan = SyncState.apply(SyncVault.merge(local, state));
                        applyPlan(plan, r);
                        audit("import", true, plan.count());
                        return "تمت الاستعادة";
                    } catch (RuntimeException ex) { audit("import", false, 0); return explain(ex); }
                    catch (Exception ex) { audit("import", false, 0); return "الملف غير صالح"; }
                }
            }, new ClipStore.Done<String>() {
                @Override public void done(String msg) { busy(false); refreshStatus(); toast(msg); }
            });
        }
    }

    // ---- سطر التدقيق ووسائل مساعدة ---------------------------------------------------

    /** سطر تدقيق بلا أي محتوى — القدرة/العملية/النتيجة/الحجم/الزمن فقط. */
    private void audit(String action, boolean ok, int bytes) {
        android.content.SharedPreferences p = Theme.prefs(this);
        String old = p.getString("sync_audit", "");
        String line = SyncClient.auditLine(action, ok, bytes, System.currentTimeMillis());
        String all = old.isEmpty() ? line : line + "\n" + old;
        String[] parts = all.split("\n");
        if (parts.length > 20) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 20; i++) b.append(parts[i]).append(i < 19 ? "\n" : "");
            all = b.toString();
        }
        p.edit().putString("sync_audit", all).apply();
    }

    private void auditGate(NetGate.Decision d) {
        android.content.SharedPreferences p = Theme.prefs(this);
        String old = p.getString("net_audit", "");
        String line = NetGate.auditLine(NetGate.CAP_SYNC, d, System.currentTimeMillis());
        String all = old.isEmpty() ? line : line + "\n" + old;
        String[] parts = all.split("\n");
        if (parts.length > 20) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 20; i++) b.append(parts[i]).append(i < 19 ? "\n" : "");
            all = b.toString();
        }
        p.edit().putString("net_audit", all).apply();
    }

    private boolean registered() {
        return Theme.prefs(this).getString("sync_salt", null) != null
                && Theme.prefs(this).getString("sync_account", null) != null;
    }

    /** الجلسة من الذاكرة + الملح المحفوظ — بلا عبارة = لا شيء يُلمس (فشل مغلق). */
    private SyncClient.Session session() {
        if (sessionPhrase == null) {
            toast("افتح الحساب أولاً من الأعلى");
            return null;
        }
        android.content.SharedPreferences p = Theme.prefs(this);
        try {
            byte[] salt = SyncVault.b64urlDecode(p.getString("sync_salt", ""));
            SyncClient.Session s = SyncClient.register(sessionPhrase, salt);
            if (!s.keys.accountId.equals(p.getString("sync_account", "")))
                throw new SyncVault.SyncRefused("account_mismatch");
            return s;
        } catch (RuntimeException e) {
            sessionPhrase = null;
            toast("تعذّر: " + e.getMessage());
            return null;
        }
    }

    private void refreshStatus() {
        android.content.SharedPreferences p = Theme.prefs(this);
        if (!registered()) {
            status.setText(L.t("لا حساب بعد — أنشئه أدناه. العبارة هي الحساب."));
            return;
        }
        if (sessionPhrase == null) {
            status.setText(L.t("الحساب موجود — أدخل العبارة لفتح هذه الجلسة."));
            return;
        }
        String acct = p.getString("sync_account", "");
        String shortAcct = acct.length() > 12 ? acct.substring(0, 12) + "…" : acct;
        long last = p.getLong("sync_last", 0);
        status.setText(L.t("حساب مشفر: " + shortAcct + " · آخر مزامنة: "
                + (last == 0 ? "لم تحدث بعد" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(last)))));
    }

    private String explain(RuntimeException e) {
        String m = e.getMessage() == null ? "error" : e.getMessage();
        if (m.startsWith("netgate_")) return "المزامنة مغلقة";
        if (m.startsWith("http_") || m.equals("network")) return "الخادم غير متاح الآن";
        if (m.equals("auth_failed")) return "عبارة الحساب لا تطابق";
        if (m.startsWith("quota")) return "حصة الحساب ممتلئة";
        return "خطأ: " + m;
    }

    private void busy(boolean on) { root.setAlpha(on ? 0.55f : 1f); }

    private void toast(String s) { Toast.makeText(this, L.t(s), Toast.LENGTH_LONG).show(); }
}
