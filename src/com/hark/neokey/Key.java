package com.hark.neokey;

import android.graphics.RectF;

final class Key {
    static final int CHAR = 0, SHIFT = 1, DEL = 2, ENTER = 3, SPACE = 4,
            TO_SYM = 5, TO_SYM2 = 6, TO_ALPHA = 7, LANG = 8,
            TO_EMOJI = 9, EMOJI_TAB = 10, TOOL = 11, CLIP = 12, CLIP_CLEAR = 13,
            SUGG = 14, MORE_ITEM = 15, EDIT = 16, PHRASE = 17, PAGE_PREV = 18, PAGE_NEXT = 19,
            OH_EXPAND = 20, OH_SWITCH = 21, LABEL = 22, DECOR = 23, CLIP_TAB = 24, CLIP_ACT = 25;

    // toolbar ids
    static final int T_CLIP = 0, T_EMOJI = 1, T_LANG = 2, T_MIC = 3, T_SETTINGS = 4, T_MORE = 5, T_AVATAR = 6, T_ARROW_R = 7, T_ARROW_L = 8;

    final int type;
    final String label;
    final String text;      // what gets typed (CHAR / CLIP)
    float units;
    String[] alts;
    String hint;            // small corner hint (digit)
    int id;                 // tool id / emoji tab index
    ClipItem clip;          // CLIP keys: the row behind it (preview only)
    float rowW;             // row height weight set on a row's first key (0 = normal)

    final RectF rect = new RectF();
    float press, target;
    long rel;               // W116: uptime the key came back to rest (spring bounce)
    float tx, ty;           // W117: where the finger landed (ink ripple)
    long tdown, tup, born;  // W117: touch down / lift uptimes; chip birth for the staggered entrance
    static final int STRIP = -7;   // W117: id of the suggestion-strip row placeholder

    Key(int type, String label, String text, float units, String[] alts) {
        this.type = type;
        this.label = (type == CHAR || type == CLIP) ? label : L.k(label);
        this.text = text;
        this.units = units;
        this.alts = alts;
    }

    static Key ch(String s, float u, String alts) { return new Key(CHAR, s, s, u, split(alts)); }
    static Key fn(int type, String label, float units) { return new Key(type, label, null, units, null); }
    static Key tool(int id) { Key k = new Key(TOOL, "", null, 1f, null); k.id = id; return k; }

    boolean isSpecial() {
        return type == SHIFT || type == DEL || type == TO_SYM || type == TO_SYM2 || type == TO_ALPHA
                || type == LANG || type == TO_EMOJI || type == EMOJI_TAB || type == CLIP_CLEAR
                || type == PAGE_PREV || type == PAGE_NEXT || type == EDIT || type == MORE_ITEM
                || type == OH_EXPAND || type == OH_SWITCH;
    }

    static String[] split(String s) {
        if (s == null || s.isEmpty()) return null;
        return s.split(" ");
    }
}
