package com.hark.neokey;

import android.content.Context;

/** Catalog of quick features the two toolbar arrows can hold (5 each). Ids < 100 are "more" panel items. */
final class Features {
    static final int EMOJI = 100, LANG = 101, MIC = 102, SETTINGS = 103, MORE = 104;
    static final int SLOTS = 5;

    /** {id, icon, Arabic label} in the order the picker lists them. */
    static final Object[][] ALL = {
            {5, "paste", "الحافظة"}, {EMOJI, "mood", "الإيموجي"}, {LANG, "globe", "تغيير اللغة"},
            {MIC, "mic", "الكتابة بالصوت"}, {SETTINGS, "tune", "الإعدادات"}, {MORE, "tools", "كل الأدوات"},
            {0, "edit", "تحرير"}, {1, "chat", "عبارات"}, {2, "sparkle", "زخرفة"}, {3, "hand", "يد واحدة"},
            {4, "height", "الحجم"}, {6, "palette", "الثيمات"}, {7, "keyboard", "تبديل الكيبورد"},
            {8, "hide", "إخفاء"}, {9, "spell", "تصحيح اللغة"}, {10, "calc", "احسب"}, {11, "calendar", "هجري"},
            {12, "translate", "ترجمة"}, {13, "volume", "استمع"}, {14, "numbers", "الأرقام"},
            {15, "incognito", "وضع التخفي"}, {16, "case", "حالة الأحرف"}, {17, "undo", "تراجع"},
            {18, "search", "بحث"}, {19, "share", "مشاركة"}, {20, "edit_note", "مساعد المراسلات"},
            {21, "text_increase", "تكبير المفاتيح"}, {22, "contrast", "تباين عالٍ"}, {23, "motion_off", "تقليل الحركة"},
    };

    static final String DEF_RIGHT = "5,100,101,102,103";
    static final String DEF_LEFT = "0,2,12,10,17";

    static int index(int id) {
        for (int i = 0; i < ALL.length; i++) if ((int) ALL[i][0] == id) return i;
        return -1;
    }

    static String icon(int id) { int i = index(id); return i < 0 ? "tools" : (String) ALL[i][1]; }
    static String label(int id) { int i = index(id); return i < 0 ? "" : L.t((String) ALL[i][2]); }

    static int[] parse(String s, String def) {
        int[] out = new int[SLOTS];
        String[] p = (s == null ? def : s).split(",");
        String[] d = def.split(",");
        for (int i = 0; i < SLOTS; i++) {
            int v;
            try { v = Integer.parseInt(i < p.length ? p[i].trim() : d[i]); } catch (Exception e) { v = Integer.parseInt(d[i]); }
            out[i] = index(v) < 0 ? Integer.parseInt(d[i]) : v;
        }
        return out;
    }

    static int[] right(Context c) { return parse(Theme.prefs(c).getString("arrow_r", null), DEF_RIGHT); }
    static int[] left(Context c) { return parse(Theme.prefs(c).getString("arrow_l", null), DEF_LEFT); }
    static boolean on(Context c) { return Theme.prefs(c).getBoolean("arrows", true); }

    static void save(Context c, boolean right, int[] ids) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ids.length; i++) { if (i > 0) b.append(','); b.append(ids[i]); }
        Theme.prefs(c).edit().putString(right ? "arrow_r" : "arrow_l", b.toString()).apply();
    }
}
