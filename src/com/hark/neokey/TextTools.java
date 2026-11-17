package com.hark.neokey;

import java.util.HashMap;
import java.util.Map;

/** Smart text tools: wrong-layout fixer, inline calculator, Hijri date. */
final class TextTools {
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvnm,./`QWERTYUIOPASDFGHJKLZXCVNM?";
    private static final String AR = "ضصثقفغعهخحجدشسيبلاتنمكطئءؤرىةوزظذضصثقفغعهخحشسيبلاتنمئءؤرىة؟";
    private static final Map<Character, String> EN2AR = new HashMap<>();
    private static final Map<Character, Character> AR2EN = new HashMap<>();

    static {
        for (int i = 0; i < EN.length(); i++) EN2AR.put(EN.charAt(i), String.valueOf(AR.charAt(i)));
        EN2AR.put('b', "لا");
        EN2AR.put('B', "لا");
        // reverse: lowercase only
        String en = "qwertyuiop[]asdfghjkl;'zxcvnm,./`";
        String ar = "ضصثقفغعهخحجدشسيبلاتنمكطئءؤرىةوزظذ";
        for (int i = 0; i < en.length(); i++) AR2EN.put(ar.charAt(i), en.charAt(i));
        AR2EN.put('؟', '?');
        AR2EN.put('أ', 'h');
        AR2EN.put('إ', 'h');
        AR2EN.put('آ', 'h');
        AR2EN.put('،', ',');
    }

    static boolean isMostlyLatin(String s) {
        int lat = 0, ar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) ar++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || "[];',./`".indexOf(c) >= 0) lat++;
        }
        return lat > ar;
    }

    /** Converts text typed on the wrong layout to the other language. */
    static String fixLayout(String s) {
        StringBuilder b = new StringBuilder();
        if (isMostlyLatin(s)) {
            for (int i = 0; i < s.length(); i++) {
                String m = EN2AR.get(s.charAt(i));
                b.append(m != null ? m : String.valueOf(s.charAt(i)));
            }
        } else {
            String t = s.replace("لا", "b").replace("لأ", "b").replace("لإ", "b").replace("لآ", "b");
            for (int i = 0; i < t.length(); i++) {
                Character m = AR2EN.get(t.charAt(i));
                b.append(m != null ? m : t.charAt(i));
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ calculator

    /** Trailing math expression in s, or null. */
    static String trailingExpr(String s) {
        int i = s.length();
        while (i > 0 && " ".indexOf(s.charAt(i - 1)) >= 0) i--;
        if (i > 0 && s.charAt(i - 1) == '=') i--;
        int end = i;
        while (i > 0 && "0123456789٠١٢٣٤٥٦٧٨٩.,٫+-*/×÷()%^ ".indexOf(s.charAt(i - 1)) >= 0) i--;
        String e = s.substring(i, end).trim();
        boolean hasOp = false, hasDigit = false;
        for (int k = 0; k < e.length(); k++) {
            char c = e.charAt(k);
            if ("+-*/×÷%^".indexOf(c) >= 0 && k > 0) hasOp = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        return hasOp && hasDigit ? e : null;
    }

    static String evaluate(String expr, boolean arabicDigits) {
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            else if (c == '×') c = '*';
            else if (c == '÷') c = '/';
            else if (c == '٫') c = '.';
            else if (c == ',') continue;
            if (c != ' ') n.append(c);
        }
        try {
            Parser p = new Parser(n.toString());
            double v = p.expr();
            if (p.pos != p.s.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            String out;
            if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) out = String.valueOf((long) Math.rint(v));
            else {
                out = String.format(java.util.Locale.US, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return arabicDigits ? toArabicDigits(out) : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s = s; }
        char peek() { return pos < s.length() ? s.charAt(pos) : 0; }
        double expr() {
            double v = term();
            while (peek() == '+' || peek() == '-') { char o = s.charAt(pos++); double r = term(); v = o == '+' ? v + r : v - r; }
            return v;
        }
        double term() {
            double v = power();
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                char o = s.charAt(pos++);
                if (o == '%' && (peek() == 0 || peek() == '+' || peek() == '-' || peek() == ')')) { v = v / 100; continue; }
                double r = power();
                v = o == '*' ? v * r : o == '/' ? v / r : v % r;
            }
            return v;
        }
        double power() {
            double v = unary();
            if (peek() == '^') { pos++; v = Math.pow(v, power()); }
            return v;
        }
        double unary() {
            if (peek() == '-') { pos++; return -unary(); }
            if (peek() == '+') { pos++; return unary(); }
            if (peek() == '(') { pos++; double v = expr(); if (peek() == ')') pos++; return v; }
            int st = pos;
            while (Character.isDigit(peek()) || peek() == '.') pos++;
            if (st == pos) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(st, pos));
        }
    }

    /** Western digits -> Arabic-Indic, or back if the text already uses Arabic-Indic. */
    static String swapDigits(String s) {
        int west = 0, east = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') west++;
            else if (c >= '٠' && c <= '٩') east++;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (west >= east && c >= '0' && c <= '9') b.append((char) ('٠' + (c - '0')));
            else if (east > west && c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else b.append(c);
        }
        return b.toString();
    }

    /** lower -> Title -> UPPER -> lower */
    static String cycleCase(String s) {
        if (s.equals(s.toLowerCase())) {
            StringBuilder b = new StringBuilder();
            boolean start = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                b.append(start ? Character.toUpperCase(c) : c);
                start = Character.isWhitespace(c);
            }
            String t = b.toString();
            return t.equals(s) ? s.toUpperCase() : t;
        }
        if (s.equals(s.toUpperCase())) return s.toLowerCase();
        return s.toUpperCase();
    }

    static String toArabicDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append((char) ('٠' + (c - '0')));
            else if (c == '.') b.append('٫');
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------ dates

    private static final String[] HIJRI_MONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final String[] G_MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو",
            "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
    private static final String[] DAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};

    static String todayArabic(boolean arabicDigits) {
        java.util.Calendar g = java.util.Calendar.getInstance();
        String day = DAYS[g.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        String greg = g.get(java.util.Calendar.DAY_OF_MONTH) + " " + G_MONTHS[g.get(java.util.Calendar.MONTH)]
                + " " + g.get(java.util.Calendar.YEAR) + "م";
        String hijri = null;
        if (android.os.Build.VERSION.SDK_INT >= 24) try {
            android.icu.util.IslamicCalendar h = new android.icu.util.IslamicCalendar();
            h.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            h.setTimeInMillis(System.currentTimeMillis());
            hijri = h.get(android.icu.util.Calendar.DAY_OF_MONTH) + " "
                    + HIJRI_MONTHS[h.get(android.icu.util.Calendar.MONTH)] + " "
                    + h.get(android.icu.util.Calendar.YEAR) + "هـ";
        } catch (Throwable ignored) { }
        String s = day + " " + (hijri != null ? hijri + " الموافق " : "") + greg;
        return arabicDigits ? toArabicDigits(s) : s;
    }

    /** W105: the other calendar for a typed date (Umm al-Qura, Android 7+), as «الموافق …»; null when unavailable. */
    static String convertDate(int[] ymdh, boolean arabicDigits) {
        if (ymdh == null || android.os.Build.VERSION.SDK_INT < 24) return null;
        try {
            android.icu.util.IslamicCalendar h = new android.icu.util.IslamicCalendar();
            h.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            String s;
            if (ymdh[3] == 1) {
                h.clear();
                h.set(ymdh[0], ymdh[1] - 1, ymdh[2]);
                java.util.Calendar g = java.util.Calendar.getInstance();
                g.setTimeInMillis(h.getTimeInMillis());
                s = "الموافق " + DAYS[g.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + g.get(java.util.Calendar.DAY_OF_MONTH) + " "
                        + G_MONTHS[g.get(java.util.Calendar.MONTH)] + " " + g.get(java.util.Calendar.YEAR) + "م";
            } else {
                java.util.Calendar g = java.util.Calendar.getInstance();
                g.clear();
                g.set(ymdh[0], ymdh[1] - 1, ymdh[2], 12, 0);
                h.setTimeInMillis(g.getTimeInMillis());
                s = "الموافق " + DAYS[g.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + h.get(android.icu.util.Calendar.DAY_OF_MONTH) + " "
                        + HIJRI_MONTHS[h.get(android.icu.util.Calendar.MONTH)] + " " + h.get(android.icu.util.Calendar.YEAR) + "هـ";
            }
            return arabicDigits ? toArabicDigits(s) : s;
        } catch (Throwable e) {
            return null;
        }
    }
}
