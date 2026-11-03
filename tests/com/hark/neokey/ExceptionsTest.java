package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 1, item B (week 2): Exceptions — the never-correct list as a data type.
 * Round-trip, dedup by raw/norm spelling, cap keeps latest, remove either form,
 * and the exact skip check the engine and the service share.
 */
public class ExceptionsTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void run() {
        System.out.println("== Exceptions: parse / serialize round-trip ==");
        List<String> l = Exceptions.parse("abc\u0001مشكله\u0001اكيد");
        check("three entries parsed", l.size() == 3 && l.get(1).equals("مشكله"));
        check("empty/null pref -> empty list",
                Exceptions.parse("").isEmpty() && Exceptions.parse(null).isEmpty());
        check("blank tokens dropped, duplicates dropped",
                Exceptions.parse("a\u0001\u0001a\u0001b").size() == 2);
        check("serialize round-trip", Exceptions.serialize(l).equals("abc\u0001مشكله\u0001اكيد"));
        check("serialize empty -> empty pref", Exceptions.serialize(new ArrayList<String>()).isEmpty());

        System.out.println("== Exceptions: add (dedup + cap) ==");
        List<String> l2 = Exceptions.add(new ArrayList<>(Arrays.asList("مشكله")), "مشكلة");
        check("norm duplicate occupies ONE entry", l2.size() == 1 && l2.get(0).equals("مشكلة"));
        List<String> l3 = Exceptions.add(new ArrayList<>(Arrays.asList("old1", "old2")), "اكيد");
        check("newest last", l3.get(2).equals("اكيد"));
        List<String> l4 = l3;
        for (int i = 0; i < Exceptions.CAP + 5; i++) l4 = Exceptions.add(l4, "w" + i);
        check("cap " + Exceptions.CAP + " keeps the latest",
                l4.size() == Exceptions.CAP && l4.get(l4.size() - 1).equals("w" + (Exceptions.CAP + 4)));
        check("null/empty word ignored", Exceptions.add(l2, null) == l2 && Exceptions.add(l2, "").size() == 1);

        System.out.println("== Exceptions: remove either spelling ==");
        List<String> l5 = Exceptions.parse("مشكله\u0001اكيد");
        check("remove by marked spelling",
                Exceptions.remove(l5, "مشكلة").size() == 1
                        && Exceptions.remove(l5, "مشكلة").get(0).equals("اكيد"));
        check("remove by exact entry", Exceptions.remove(l5, "اكيد").size() == 1);

        System.out.println("== Exceptions: shouldSkip (the shared check) ==");
        List<String> l6 = Exceptions.parse("مشكله");
        check("raw hit", Exceptions.shouldSkip("مشكله", l6));
        check("normalized hit", Exceptions.shouldSkip("مشكلة", l6));
        check("other words pass", !Exceptions.shouldSkip("مشكلة2", l6));
        check("empty list/word never skips",
                !Exceptions.shouldSkip("مشكله", new ArrayList<String>())
                        && !Exceptions.shouldSkip("", l6) && !Exceptions.shouldSkip(null, l6));
        check("count helper", Exceptions.count("a\u0001b\u0001c") == 3 && Exceptions.count(null) == 0);
    }
}
