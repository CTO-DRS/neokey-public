package com.hark.neokey;

/**
 * Search normalisation for the clipboard: lowercase, Arabic diacritics and tatweel removed,
 * alef/yaa/taa-marbuta forms unified, Arabic-Indic digits to ASCII. Used only for the
 * hidden search column; the stored text itself is never changed.
 */
final class ClipNorm {
    private ClipNorm() {}

    static boolean isMark(char c) {
        return (c >= '\u064B' && c <= '\u065F') || c == '\u0670' || c == '\u0640'
                || (c >= '\u06D6' && c <= '\u06ED') || c == '\u200C' || c == '\u200D' || c == '\u200F' || c == '\u200E';
    }

    static char fold(char c) {
        switch (c) {
            case 'أ': case 'إ': case 'آ': case 'ٱ': return 'ا';
            case 'ى': return 'ي';
            case 'ة': return 'ه';
            case 'ؤ': return 'و';
            case 'ئ': return 'ي';
        }
        if (c >= '\u0660' && c <= '\u0669') return (char) ('0' + (c - '\u0660'));
        if (c >= '\u06F0' && c <= '\u06F9') return (char) ('0' + (c - '\u06F0'));
        return Character.toLowerCase(c);
    }

    static String of(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isMark(c)) continue;
            b.append(fold(c));
        }
        return b.toString();
    }

    /** Finds q (already normalised) inside raw text; returns {start, end} in raw indices or null. */
    static int[] find(String raw, String q) {
        if (raw == null || q == null || q.isEmpty()) return null;
        int n = raw.length();
        for (int s = 0; s < n; s++) {
            if (isMark(raw.charAt(s))) continue;
            int i = s, j = 0;
            while (i < n && j < q.length()) {
                char c = raw.charAt(i);
                if (isMark(c)) { i++; continue; }
                if (fold(c) != q.charAt(j)) break;
                i++; j++;
            }
            if (j == q.length()) {
                while (i < n && isMark(raw.charAt(i))) i++;   // keep trailing tashkeel with the match
                return new int[]{s, i};
            }
        }
        return null;
    }
}
