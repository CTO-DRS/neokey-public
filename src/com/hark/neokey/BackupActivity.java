package com.hark.neokey;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.util.Map;

/** Backup / restore of settings, themes library, phrases, shortcuts and learned words, as one .neokeybak file. */
public class BackupActivity extends Screen {
    private static final int EXPORT = 51, IMPORT = 52;
    private static final String[] FILES = {"user_ar.txt", "user_en.txt", "bigram_ar.txt", "bigram_en.txt"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        header("النسخ الاحتياطي", getString(R.string.nk_backupactivity_oncreate_1), "download");
        TextView info = text(getString(R.string.nk_backupactivity_header_1),
                13, Typeface.NORMAL, MUTED);
        info.setPadding((int) (4 * dp), (int) (16 * dp), (int) (4 * dp), 0);
        root.addView(info);
        root.addView(button("حفظ نسخة احتياطية", "download", true, v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "NeoKey-backup.neokeybak");
            startActivityForResult(i, EXPORT);
        }), top(18));
        root.addView(button("استعادة من ملف", "upload", false, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, IMPORT);
        }), top(10));
    }

    private static boolean skip(String k) {
        return k.equals("clips") || k.equals("pins") || k.equals("ai_key") || k.startsWith("w_insert") || k.equals("dict_reload");
    }

    private String build() throws Exception {
        JSONObject o = new JSONObject();
        o.put("format", "neokey-backup-1");
        o.put("created", System.currentTimeMillis());
        JSONObject p = new JSONObject();
        for (Map.Entry<String, ?> e : Theme.prefs(this).getAll().entrySet()) {
            if (skip(e.getKey()) || e.getValue() == null) continue;
            Object v = e.getValue();
            String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (t == null) continue;
            p.put(e.getKey(), t + ":" + v);
        }
        o.put("prefs", p);
        JSONObject f = new JSONObject();
        for (String n : FILES) {
            File file = new File(getFilesDir(), n);
            if (!file.exists()) continue;
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) { int r = 0; while (r < data.length) { int c = in.read(data, r, data.length - r); if (c < 0) break; r += c; } }
            f.put(n, new String(data, "UTF-8"));
        }
        o.put("files", f);
        return o.toString();
    }

    private void restore(String s) throws Exception {
        JSONObject o = new JSONObject(s);
        if (!"neokey-backup-1".equals(o.optString("format"))) throw new IllegalArgumentException("format");
        JSONObject p = o.getJSONObject("prefs");
        SharedPreferences.Editor e = Theme.prefs(this).edit();
        java.util.Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (skip(k)) continue;
            String v = p.getString(k);
            if (v.length() < 2) continue;
            String val = v.substring(2);
            try {
                switch (v.charAt(0)) {
                    case 'b': e.putBoolean(k, Boolean.parseBoolean(val)); break;
                    case 'i': e.putInt(k, Integer.parseInt(val)); break;
                    case 'l': e.putLong(k, Long.parseLong(val)); break;
                    case 'f': e.putFloat(k, Float.parseFloat(val)); break;
                    default: e.putString(k, val);
                }
            } catch (Exception ignored) { }
        }
        JSONObject f = o.optJSONObject("files");
        if (f != null) for (String n : FILES) {
            if (!f.has(n)) continue;
            try (java.io.Writer w = new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(), n)), "UTF-8")) { w.write(f.getString(n)); }
        }
        e.putBoolean("dict_reload", true).apply();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == EXPORT) {
                try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(build().getBytes("UTF-8")); }
                Toast.makeText(this, L.t("تم حفظ النسخة الاحتياطية"), Toast.LENGTH_SHORT).show();
            } else if (req == IMPORT) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 20_000_000) throw new IOException("big"); }
                }
                restore(bo.toString("UTF-8"));
                Toast.makeText(this, L.t(getString(R.string.nk_backupactivity_restore_1)), Toast.LENGTH_LONG).show();
            }
        } catch (Exception ex) {
            Toast.makeText(this, req == IMPORT ? "الملف غير صالح أو تالف" : "تعذّر الحفظ", Toast.LENGTH_LONG).show();
        }
    }
}
