package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1, item B (week 2): the user's never-correct list as a real data type —
 * «امتداد منطق "تراجع = لا تصحح مرة أخرى" الحالي إلى قوائم استثناء ذكية لكل مستخدم».
 *
 * The pref (nocorrect) stores the list \u0001-joined, newest last. Until now the
 * service inline-split it and Correction2 checked it — the semantics lived in two
 * heads. This class is the single source:
 *   • parse / serialize round-trip;
 *   • add keeps the newest occurrence, dedups by RAW-lowercase AND normalized key
 *     (so «مشكله» and «مشكلة» occupy one entry — a user exclusion can never be
 *     bypassed by the hamza spelling), caps at CAP keeping the latest;
 *   • remove understands either spelling (raw or normalized);
 *   • shouldSkip is the exact check Correction2 and the service apply.
 *
 * Pure logic, zero android.* — JVM-tested as shipped (and R8 strips it).
 */
final class Exceptions {

    private Exceptions() { }

    /** Maximum entries kept (oldest dropped first) — the pref must stay small. */
    static final int CAP = 300;
    /** The separator the pref has always used (join in NeoKeyService since v18.1). */
    static final String SEP = "\u0001";

    /** Parse the pref value into an ordered list (empty/null safe, blanks dropped). */
    static List<String> parse(String pref) {
        List<String> out = new ArrayList<>();
        if (pref == null || pref.isEmpty()) return out;
        for (String x : pref.split(SEP)) if (!x.isEmpty() && !out.contains(x)) out.add(x);
        return out;
    }

    /** Serialize back to the pref value (already capped by add). */
    static String serialize(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String x : list) b.append(x).append(SEP);
        return b.substring(0, b.length() - 1);
    }

    /** Add a word (dedup by raw-lowercase or normalized key; newest wins; cap keeps latest). */
    static List<String> add(List<String> list, String word) {
        if (word == null || word.isEmpty()) return list;
        String raw = word.toLowerCase();
        String norm = Dictionary.norm(word);
        List<String> out = new ArrayList<>();
        for (String x : list) {
            if (x.equalsIgnoreCase(word) || x.toLowerCase().equals(raw)
                    || Dictionary.norm(x).equals(norm)) continue;   // drop older/duplicate forms
            out.add(x);
        }
        out.add(raw);
        if (out.size() > CAP) out = new ArrayList<>(out.subList(out.size() - CAP, out.size()));
        return out;
    }

    /** Remove a word (matches raw, raw-lowercase or normalized spelling). */
    static List<String> remove(List<String> list, String word) {
        if (word == null || word.isEmpty() || list == null) return list;
        String raw = word.toLowerCase();
        String norm = Dictionary.norm(word);
        List<String> out = new ArrayList<>();
        for (String x : list)
            if (!x.toLowerCase().equals(raw) && !Dictionary.norm(x).equals(norm)) out.add(x);
        return out;
    }

    /** Skip-check against any collection (the set in the service, lists in tests). */
    static boolean shouldSkip(String word, java.util.Collection<String> coll) {
        if (word == null || word.isEmpty() || coll == null || coll.isEmpty()) return false;
        String raw = word.toLowerCase();
        String norm = Dictionary.norm(word);
        for (String x : coll)
            if (x.equals(raw) || Dictionary.norm(x).equals(norm)) return true;
        return false;
    }

    /** Count for the settings row label. */
    static int count(String pref) {
        return parse(pref).size();
    }
}
