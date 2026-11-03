package com.hark.neokey;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.util.*;

/** Personal dictionary editor: see, search, add and delete the words the keyboard learned on this phone. */
public class DictActivity extends Screen {
    private int lang = 0;                       // 0 ar, 1 en
    private final LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
    private LinearLayout list;
    private EditText search;
    private TextView count;
    private View0 chipsHolder;

    private static final class View0 { android.view.View v; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        header("قاموسي الشخصي", "الكلمات اللي تعلّمها الكيبورد من كتابتك", "book");
        chipsHolder = new View0();
        buildChips();
        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        final EditText nw = edit("أضف كلمة جديدة", false);
        add.addView(nw, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ab = button("إضافة", "add", true, v -> {
            String w = nw.getText().toString().trim();
            if (w.isEmpty() || w.contains(" ") || w.contains("\t")) { Toast.makeText(this, L.t("اكتب كلمة واحدة بدون مسافات"), Toast.LENGTH_SHORT).show(); return; }
            words.put(w, Math.max(words.containsKey(w) ? words.get(w) : 0, 40));
            nw.setText("");
            save();
            render();
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.setMarginStart((int) (8 * dp));
        add.addView(ab, alp);
        root.addView(add, top(14));
        search = edit("ابحث في كلماتك", false);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { render(); }
            public void afterTextChanged(Editable e) { }
        });
        root.addView(search, top(10));
        count = text("", 12, Typeface.NORMAL, MUTED);
        count.setPadding((int) (4 * dp), (int) (10 * dp), (int) (4 * dp), (int) (4 * dp));
        root.addView(count);
        list = card();
        root.addView(list, top(6));
        TextView clear = button("حذف كل كلمات هذه اللغة", "delete", false, v -> new android.app.AlertDialog.Builder(this)
                .setMessage(L.t("أكيد تبي تحذف كل الكلمات المتعلّمة لهذه اللغة؟"))
                .setPositiveButton(L.t("حذف"), (d, w) -> { words.clear(); save(); render(); })
                .setNegativeButton(L.t("إلغاء"), null).show());
        clear.setTextColor(0xFFF87171);
        root.addView(clear, top(14));
        load();
    }

    private void buildChips() {
        if (chipsHolder.v != null) root.removeView(chipsHolder.v);
        chipsHolder.v = chips(new String[]{"العربية", "English"}, lang, i -> { lang = i; buildChips(); load(); });
        root.addView(chipsHolder.v, 1, top(14));
    }

    private File file() { return new File(getFilesDir(), "user_" + (lang == 0 ? "ar" : "en") + ".txt"); }

    private void load() {
        words.clear();
        File f = file();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                List<Map.Entry<String, Integer>> l = new ArrayList<>();
                while ((line = r.readLine()) != null) {
                    int t = line.lastIndexOf('\t');
                    if (t > 0) try { l.add(new AbstractMap.SimpleEntry<>(line.substring(0, t), Integer.parseInt(line.substring(t + 1)))); } catch (Exception ignored) { }
                }
                Collections.sort(l, (a, b2) -> b2.getValue() - a.getValue());
                for (Map.Entry<String, Integer> e : l) words.put(e.getKey(), e.getValue());
            } catch (Exception ignored) { }
        }
        if (list != null) render();
    }

    private void save() {
        try (java.io.Writer w = new OutputStreamWriter(new FileOutputStream(file()), "UTF-8")) {
            for (Map.Entry<String, Integer> e : words.entrySet()) w.write(e.getKey() + "\t" + e.getValue() + "\n");
        } catch (Exception ignored) { }
        Theme.prefs(this).edit().putBoolean("dict_reload", true).apply();
    }

    private void render() {
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim();
        int shown = 0;
        for (final Map.Entry<String, Integer> e : words.entrySet()) {
            if (!q.isEmpty() && !e.getKey().contains(q)) continue;
            if (shown++ >= 300) break;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) (46 * dp));
            TextView w = text(e.getKey(), 16, Typeface.NORMAL, Color.WHITE);
            row.addView(w, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(text("×" + e.getValue(), 12, Typeface.NORMAL, MUTED));
            TextView del = text("", 12, Typeface.NORMAL, Color.WHITE);
            Icons.iconOnly(del, "delete", 0xFFF87171, (int) (20 * dp), (int) (40 * dp));
            del.setContentDescription(L.t("حذف ") + e.getKey());
            del.setOnClickListener(v -> { words.remove(e.getKey()); save(); render(); });
            row.addView(del, new LinearLayout.LayoutParams((int) (40 * dp), (int) (40 * dp)));
            list.addView(row);
        }
        if (shown == 0) list.addView(text(words.isEmpty() ? "ما فيه كلمات متعلّمة لهذه اللغة للحين. اكتب بالكيبورد وبتتجمع هنا." : "ما فيه نتائج.", 13, Typeface.NORMAL, MUTED));
        count.setText(words.size() + " كلمة" + (shown >= 300 ? " (يظهر أول 300، استخدم البحث)" : ""));
    }
}
