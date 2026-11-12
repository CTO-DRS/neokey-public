package com.hark.neokey;

/** W54 theme round-trip probe: loads assets/theme_phrases.txt through the SHIPPED
 *  Theme.initPhrases parser and prints the arrays in the expected format — the
 *  diff against /tmp/w54_theme_expected.txt (extracted from the pre-trim source)
 *  proves the relocation is byte-exact. */
public class W54ThemeProbe {
    public static void main(String[] a) throws Exception {
        byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("assets/theme_phrases.txt"));
        Theme.initPhrases(raw);
        StringBuilder b = new StringBuilder();
        java.lang.reflect.Field f1 = Theme.class.getDeclaredField("DEF_PHRASES");
        java.lang.reflect.Field f2 = Theme.class.getDeclaredField("ADHKAR_ARR");
        f1.setAccessible(true); f2.setAccessible(true);
        for (String s : (String[]) f1.get(null)) b.append("D\t").append(s).append('\n');
        for (String s : (String[]) f2.get(null)) b.append("A\t").append(s).append('\n');
        System.out.print(b);
    }
}
