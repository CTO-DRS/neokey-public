package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;

/** Pure-Java find/replace and counting for the clip editor (works on 100k+ char texts). */
final class ClipText {
    private ClipText() {}

    static final int MAX_HITS = 100_000;

    /** Finds every match; returns [start, end) spans in the original text. */
    static List<int[]> findAll(String text, String q, boolean caseSensitive, boolean wholeWord, boolean ignoreMarks) {
        List<int[]> out = new ArrayList<>();
        if (text == null || q == null || q.isEmpty()) return out;
        if (ignoreMarks) {
            String nq = caseSensitive ? stripMarks(q) : ClipNorm.of(q);
            if (nq.isEmpty()) return out;
            int n = text.length();
            for (int s = 0; s < n && out.size() < MAX_HITS; s++) {
                if (ClipNorm.isMark(text.charAt(s))) continue;
                int i = s, j = 0;
                while (i < n && j < nq.length()) {
                    char c = text.charAt(i);
                    if (ClipNorm.isMark(c)) { i++; continue; }
                    char a = caseSensitive ? c : ClipNorm.fold(c);
                    if (a != nq.charAt(j)) break;
                    i++; j++;
                }
                if (j == nq.length()) {
                    while (i < n && ClipNorm.isMark(text.charAt(i))) i++;
                    if (!wholeWord || isWord(text, s, i)) { out.add(new int[]{s, i}); s = i - 1; }
                }
            }
            return out;
        }
        String hay = caseSensitive ? text : text.toLowerCase(java.util.Locale.ROOT);
        String nd = caseSensitive ? q : q.toLowerCase(java.util.Locale.ROOT);
        if (hay.length() != text.length()) { hay = text; nd = q; }   // rare case-mapping length change: fall back to exact
        int from = 0;
        while (out.size() < MAX_HITS) {
            int i = hay.indexOf(nd, from);
            if (i < 0) break;
            int e = i + nd.length();
            if (!wholeWord || isWord(text, i, e)) { out.add(new int[]{i, e}); from = e; }
            else from = i + 1;
        }
        return out;
    }

    static String stripMarks(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (!ClipNorm.isMark(s.charAt(i))) b.append(s.charAt(i));
        return b.toString();
    }

    /**
     * W48 text tool: whitespace tidy. Inside a line, every whitespace run becomes one space and
     * line edges are trimmed; N consecutive blank lines collapse to ONE blank line; leading and
     * trailing blank lines disappear. Words and paragraph structure are never touched.
     */
    static String tidy(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length());
        int n = s.length(), i = 0;
        boolean pendingBlank = false;
        while (i < n) {
            StringBuilder line = new StringBuilder();
            boolean ws = false;
            while (i < n && s.charAt(i) != '\n') {
                char c = s.charAt(i++);
                if (Character.isWhitespace(c)) ws = true;
                else { if (ws && line.length() > 0) line.append(' '); ws = false; line.append(c); }
            }
            i++;   // the newline itself
            if (line.length() == 0) pendingBlank = true;
            else {
                if (pendingBlank && b.length() > 0) b.append('\n');
                pendingBlank = false;
                if (b.length() > 0) b.append('\n');
                b.append(line);
            }
        }
        return b.toString();
    }

    private static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || ClipNorm.isMark(c) || c == '_'; }

    static boolean isWord(String t, int s, int e) {
        return (s == 0 || !isWordChar(t.charAt(s - 1))) && (e >= t.length() || !isWordChar(t.charAt(e)));
    }

    /** Replaces the given spans (from findAll) in one pass. */
    static String replaceSpans(String text, List<int[]> spans, String with) {
        StringBuilder b = new StringBuilder(text.length());
        int last = 0;
        for (int[] s : spans) {
            b.append(text, last, s[0]).append(with);
            last = s[1];
        }
        b.append(text, last, text.length());
        return b.toString();
    }

    /** {chars, words, lines} — chars counts code points, so an emoji is one. */
    static int[] counts(String t) {
        if (t == null || t.isEmpty()) return new int[]{0, 0, 0};
        int chars = t.codePointCount(0, t.length());
        int words = 0, lines = 1;
        boolean in = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') lines++;
            boolean ws = Character.isWhitespace(c);
            if (!ws && !in) { words++; in = true; } else if (ws) in = false;
        }
        return new int[]{chars, words, lines};
    }
}
