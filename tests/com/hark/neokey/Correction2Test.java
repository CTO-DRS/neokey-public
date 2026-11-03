package com.hark.neokey;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 1, item B (week 1): Correction2 contract tests — the confusion table,
 * the direction guards, the zero-annoyance policy, and the exception set,
 * against the REAL 150k dictionary exactly as the gate measures it.
 */
public class Correction2Test {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static Dictionary dict;

    public static void run() {
        if (dict == null) {
            File dir = new File("build/test-correction2");
            dir.mkdirs();
            dict = new Dictionary(new FakeContext(dir), "ar");
            long t0 = System.currentTimeMillis();
            while (!dict.isLoaded() && System.currentTimeMillis() - t0 < 15000) {
                try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            }
        }

        System.out.println("== Correction2: confusion table integrity ==");
        check("table rows carry alternatives and priors", Correction2.tableIntegrity());
        check("every alternative is a same-norm spelling",
                Correction2.tableNormConsistency());
        check("confusionOf accepts hamza re-spellings",
                Correction2.confusionOf("الى", "إلى") && Correction2.confusionOf("مشكله", "مشكلة"));
        check("confusionOf rejects different lengths and moved letters",
                !Correction2.confusionOf("سلام", "اسلام") && !Correction2.confusionOf("سلام", "سالم"));
        check("degradesStandard flags bare-form regressions only",
                Correction2.degradesStandard("أرسل", "ارسل")
                && Correction2.degradesStandard("مشكلة", "مشكله")
                && !Correction2.degradesStandard("ارسل", "أرسل")
                && !Correction2.degradesStandard("فى", "في"));
        check("standard direction covers hamza/ة only (ي/ى lexically split)",
                Correction2.isStandardOnlyDirection("الى", "إلى")
                && !Correction2.isStandardOnlyDirection("علي", "على"));

        System.out.println("== Correction2: previous word (pure text logic) ==");
        check("word before the current one",
                "لله".equals(Correction2.previousWord("الحمد لله على", 4))
                && "في".equals(Correction2.previousWord("أنا في الطريق", 6)));
        check("no previous word", Correction2.previousWord("السلام", 6).isEmpty()
                && Correction2.previousWord("", 0).isEmpty()
                && Correction2.previousWord("، سلام", 4).isEmpty());
        check("the word before the tail word",
                "السلام".equals(Correction2.previousWord("السلام عليكم", 6)));
        check("separators between words are skipped",
                "الحمد".equals(Correction2.previousWord("الحمد، لله", 3)));

        System.out.println("== Correction2: the zero-annoyance policy ==");
        Set<String> none = Collections.emptySet();
        check("unknown word, hamza variant -> restored",
                "أكيد".equals(Correction2.fix("اكيد", "", dict, none)));
        check("bare taa-marbuta -> standard ة restored",
                "مشكلة".equals(Correction2.fix("مشكله", "", dict, none)));
        check("hamza normalization on informal spelling",
                "إلى".equals(Correction2.fix("الى", "", dict, none)));
        check("NEVER degrades a correct marked word to its bare form",
                Correction2.fix("أرسل", "", dict, none) == null
                && Correction2.fix("مشكلة", "", dict, none) == null);
        check("conservative on the lexically-split ي/ى class without context",
                Correction2.fix("علي", "", dict, none) == null);
        check("exception set wins over everything",
                Correction2.fix("مشكله", "", dict, set(Dictionary.norm("مشكله"))) == null
                && Correction2.fix("اكيد", "", dict, set(Dictionary.norm("اكيد"))) == null);
        check("short and null input stay untouched",
                Correction2.fix("ا", "", dict, none) == null
                && Correction2.fix(null, "", dict, none) == null);
        check("a word with no candidates is left alone",
                Correction2.fix("كتاب", "", dict, none) == null);

        System.out.println("== Correction2: context votes ==");
        // The seed bigram {تصبح, على} gives the preposition context evidence;
        // the injected mistake علي must be pulled back to على.
        check("previous word pulls the intended spelling",
                "على".equals(Correction2.fix("علي", "تصبح", dict, none)));
        check("context never justifies degrading the standard form",
                Correction2.fix("أرسل", "يا", dict, none) == null);

        System.out.println("== Correction2 (week 2): the next word ==");
        check("first word of the after-cursor text",
                "شاء".equals(Correction2.nextWord(" شاء الله تكون"))
                        && "الله".equals(Correction2.nextWord("الله")));
        check("separators and end handled",
                Correction2.nextWord("، ،.").isEmpty() && Correction2.nextWord("").isEmpty()
                        && Correction2.nextWord(null).isEmpty());
        // إن شاء — the corpus row for RAW إن contains شاء; الأن row does not.
        check("next word resolves the إن/أن family (raw-keyed corpus rows)",
                "إن".equals(Correction2.fix("ان", "", "شاء", dict, none)));
        check("next word resolves الآن with NO prev (sentence start)",
                "الآن".equals(Correction2.fix("الان", "", "في", dict, none)));
        check("the علي/على family follows a real next-word bigram",
                Correction2.fix("علي", "", "بن", dict, none) == null
                        && "على".equals(Correction2.fix("علي", "", "خير", dict, none)));

        System.out.println("== Correction2 (week 2): the function-word rule ==");
        // Mid-sentence neutral context + top-20 function word + huge rank gap.
        check("neutral context fires the function-word rule",
                "على".equals(Correction2.fix("علي", "جزيلا", "المساعدة", dict, none))
                        && Correction2.lastReason.equals("known-word:function"));
        check("NO context at all -> the rule never fires (clean name stays)",
                Correction2.fix("علي", "", "", dict, none) == null);
        check("bigram preference for the typed spelling blocks the rule",
                Correction2.fix("علي", "", "بن", dict, none) == null
                        && Correction2.lastReason.equals("known-word-no-rule"));
        check("the rule never degrades the standard orthography",
                Correction2.fix("مشكلة", "عن", "هذه", dict, none) == null);

        System.out.println("== Correction2 (week 2): الفاصلة المنقوطة والأرقام العربية ==");
        check("punct/numeral table integrity", Correction2.punctTableIntegrity());
        check("Latin ; means ؛ on the Arabic layout only",
                "؛".equals(Correction2.punctFor(";", true)) && Correction2.punctFor(";", false) == null);
        check("digit attached to Arabic text becomes Arabic-Indic",
                Correction2.digitFor('2', 'ح', false) == '٢'
                        && Correction2.digitFor('0', 'ة', false) == '٠');
        check("digit attached to Latin text stays Latin (both directions)",
                Correction2.digitFor('3', 'x', false) == '3'
                        && Correction2.digitFor('٢', 'h', false) == '2');
        check("unattached digit keeps what was typed; row pref wins when ON",
                Correction2.digitFor('5', ' ', true) == '٥'
                        && Correction2.digitFor('5', ' ', false) == '5'
                        && Correction2.digitFor('٥', ' ', false) == '٥'
                        && Correction2.digitFor('6', 'x', true) == '٦');

        System.out.println("== Correction2 (week 2): الحقول الحرجة policy ==");
        check("sensitive field: never silently corrected",
                !Correction2.silentCorrectionAllowed(true, true));
        check("autocorrect off: engine not consulted",
                !Correction2.silentCorrectionAllowed(false, false));
        check("normal field + autocorrect on: allowed",
                Correction2.silentCorrectionAllowed(false, true));
    }

    private static Set<String> set(String... items) {
        return new HashSet<>(java.util.Arrays.asList(items));
    }
}
