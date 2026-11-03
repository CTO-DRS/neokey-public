package com.hark.neokey;

/**
 * Aggregated runner for the Week-2 hot-core suites (roadmap Phase 0: 89 -> 200+ tests).
 * ClipRepoTest (89 tests) runs separately via tests/run.sh; this runner covers the rest.
 */
public class CoreTests {
    public static void main(String[] args) {
        long t0 = System.currentTimeMillis();
        ArabicNormTest.run();
        ArabicFixesTest.run();
        EmojiHintsTest.run();
        EmojiSearchTest.run();
        DictionaryTest.run();
        DecorTest.run();
        WriterTest.run();
        IntegrityTest.run();
        CrashReportTest.run();
        FeedbackTest.run();
        Correction2Test.run();
        ExceptionsTest.run();
        SendPreviewTest.run();
        NNetTest.run();
        PredictorTest.run();
        int p = ArabicNormTest.pass + ArabicFixesTest.pass + EmojiHintsTest.pass + EmojiSearchTest.pass
                + DictionaryTest.pass + DecorTest.pass + WriterTest.pass + IntegrityTest.pass
                + CrashReportTest.pass + FeedbackTest.pass + Correction2Test.pass
                + ExceptionsTest.pass + SendPreviewTest.pass + NNetTest.pass + PredictorTest.pass;
        int f = ArabicNormTest.fail + ArabicFixesTest.fail + EmojiHintsTest.fail + EmojiSearchTest.fail
                + DictionaryTest.fail + DecorTest.fail + WriterTest.fail + IntegrityTest.fail
                + CrashReportTest.fail + FeedbackTest.fail + Correction2Test.fail
                + ExceptionsTest.fail + SendPreviewTest.fail + NNetTest.fail + PredictorTest.fail;
        System.out.printf("CoreTests time: %d ms%n", System.currentTimeMillis() - t0);
        System.out.println("RESULT " + p + " passed, " + f + " failed");
        if (f > 0) System.exit(1);
    }
}
