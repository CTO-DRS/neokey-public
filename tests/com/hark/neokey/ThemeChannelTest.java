package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * بطارية W74 النقية — ThemeChannelTest: القناة المنسّقة تُختم وتُفتح
 * بايت-دقيقة، والفتح حتمي، والتصيير عبر GenThemes حتمي (نفس البذرة =
 * نفس البكسلات مرتين)، والتزوير بكل شكل يعض بوسمه: الدبوس بإعادة
 * توقيع المهاجم، التوقيع الخارجي بإفساد القيمة، السكيمة، المدخل
 * المشوه، القالب خارج النطاق، والعدد.
 */
public class ThemeChannelTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    interface F { void run(); }

    static String expect(F f) {
        try { f.run(); return null; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    /** تزوير موقّع ذاتياً: استبدال قيمة سطر وإعادة التوقيع على كل ما قبله. */
    static byte[] resignWith(byte[] chan, String linePrefix, String newValue) {
        int tail = "sig ".length() + 64 + 1;
        byte[] prefix = Arrays.copyOf(chan, chan.length - tail);
        String head = new String(prefix, StandardCharsets.UTF_8);
        String patched = head.replaceFirst("(?m)^" + linePrefix + "[0-9a-f]{64}", linePrefix + newValue);
        byte[] out2 = patched.getBytes(StandardCharsets.UTF_8);
        String sig = CommunityDict.sha256Hex(out2);
        byte[] t2 = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[out2.length + t2.length];
        System.arraycopy(out2, 0, out, 0, out2.length);
        System.arraycopy(t2, 0, out, out2.length, t2.length);
        return out;
    }

    /** تزوير خارجي خالص: إفساد أول محرف سداسي في قيمة التوقيع — بلا إعادة توقيع. */
    static byte[] breakOuterSig(byte[] chan) {
        byte[] out = chan.clone();
        int v0 = out.length - 1 - 64;
        out[v0] = (byte) (out[v0] == '0' ? 'f' : '0');
        return out;
    }

    public static void main(String[] args) {
        List<ThemeChannel.Entry> es = new ArrayList<>();
        es.add(new ThemeChannel.Entry("ليل الصحراء فوق الرمال", 0));
        es.add(new ThemeChannel.Entry("قهوة الصباح والنافذة", 2));
        byte[] c1 = ThemeChannel.seal(es);
        byte[] c2 = ThemeChannel.seal(es);
        check("seal: نفس المدخلات = نفس البايتات حرفاً", Arrays.equals(c1, c2));
        String st = new String(c1, StandardCharsets.UTF_8);
        check("seal: البنية موثقة (سكيمة/دبوس/توقيع من النهاية)",
                st.startsWith(ThemeChannel.SCHEMA + "\n")
                        && st.contains("\npin ")
                        && st.lastIndexOf("\nsig ") > st.indexOf("\npin "));

        ThemeChannel.Open o = ThemeChannel.open(c1);
        check("open: الجولة كاملة — مدخلان بنفس البذور والقوالب والترتيب",
                o.entries.size() == 2
                        && o.entries.get(0).seed.equals("ليل الصحراء فوق الرمال")
                        && o.entries.get(0).style == 0
                        && o.entries.get(1).style == 2);
        ThemeChannel.Open o2 = ThemeChannel.open(ThemeChannel.seal(es));
        check("open: حتمي — نفس البايتات نفس المدخلات مرتين",
                o2.entries.size() == o.entries.size()
                        && o2.entries.get(1).seed.equals(o.entries.get(1).seed));

        // التصيير الحتمي عبر GenThemes: نفس البذرة = نفس البكسلات مرتين
        GenThemes.Pal p = GenThemes.make(GenThemes.seedOf("ليل الصحراء فوق الرمال"));
        int[] r1 = GenThemes.render(p, 8, 8);
        int[] r2 = GenThemes.render(p, 8, 8);
        check("render: نفس البذرة = نفس البكسلات على أي جهاز (حتمية القناة)",
                Arrays.equals(r1, r2) && r1.length == 64);

        // التزوير بكل أشكاله — كل واحد يعض بوسمه
        String t = expect(() -> ThemeChannel.open(resignWith(c1, "pin ",
                CommunityDict.sha256Hex("خاطئ".getBytes(StandardCharsets.UTF_8)))));
        check("forge: دبوس مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)",
                ThemeChannel.TAG_PIN_MISMATCH.equals(t));
        String t3 = expect(() -> ThemeChannel.open(breakOuterSig(c1)));
        check("forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)", ThemeChannel.TAG_SIG.equals(t3));
        String t4 = expect(() -> ThemeChannel.open(("x" + new String(c1, StandardCharsets.UTF_8)
                .substring(1)).getBytes(StandardCharsets.UTF_8)));
        check("forge: سطر السكيمة المحرف يعض (CHAN_SCHEMA)", ThemeChannel.TAG_SCHEMA.equals(t4));
        // ملف مزور كامل البنية بمدخل قالب خارج النطاق — بصمة المهاجم الذاتية
        String head = ThemeChannel.SCHEMA + "\nseed\tبذرة\tstyle\t7\n";
        byte[] headB = head.getBytes(StandardCharsets.UTF_8);
        String pin = CommunityDict.sha256Hex(headB);
        byte[] prefix = (head + "pin " + pin + "\n").getBytes(StandardCharsets.UTF_8);
        String sig = CommunityDict.sha256Hex(prefix);
        byte[] forged = (new String(prefix, StandardCharsets.UTF_8) + "sig " + sig + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String t5 = expect(() -> ThemeChannel.open(forged));
        check("forge: قالب خارج النطاق يعض (CHAN_ENTRY)", ThemeChannel.TAG_ENTRY.equals(t5));
        String t6 = expect(() -> ThemeChannel.seal(new ArrayList<>()));
        check("forge: قناة فارغة ترفض النشر (CHAN_EMPTY)", ThemeChannel.TAG_EMPTY.equals(t6));
        String t9 = expect(() -> ThemeChannel.seal(Arrays.asList(new ThemeChannel.Entry("بذرة", 7))));
        check("forge: النشر يرفض قالباً خارج النطاق (CHAN_ENTRY)", ThemeChannel.TAG_ENTRY.equals(t9));
        List<ThemeChannel.Entry> many = new ArrayList<>();
        for (int i = 0; i < 33; i++) many.add(new ThemeChannel.Entry("بذرة" + i, i % 4));
        String t7 = expect(() -> ThemeChannel.seal(many));
        check("forge: فوق السقف (33 مدخلاً) يعض (CHAN_COUNT)", ThemeChannel.TAG_COUNT.equals(t7));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
