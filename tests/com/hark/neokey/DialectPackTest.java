package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * بطارية W73 النقية — DialectPackTest: معيار الخروج حرفاً — حزمة مزورة
 * بكل أشكالها تعضّ بوسمها، والدبوس الذهبي حتمي وهوية اللهجة جزء من
 * القلم، والختم بايت-دقيق، والدمج الصادق عبر WordSink. نقي JVM كلياً
 * (فحوص الدمج الحي على القاموس الحقيقي في DialectPackMergeTest داخل
 * بطارية run.sh).
 */
public class DialectPackTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static String row(String word, String diac, String lemma, String pos,
                      String gloss, String contrib, String lic) {
        return word + "," + diac + "," + lemma + "," + pos + "," + gloss + "," + contrib + "," + lic;
    }

    static byte[] csv(String... dataRows) {
        StringBuilder b = new StringBuilder("word,diacritized,lemma,pos,gloss,contributor,license\n");
        for (String r : dataRows) b.append(r).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Set<String> inst(String... words) {
        Set<String> s = new HashSet<>();
        for (String w : words) s.add(CommunityDict.normAr(w));
        return s;
    }

    static DictSign.Dpack publishOf(byte[] csvBytes, Set<String> installed) {
        return DictSign.publish(csvBytes, CommunityDict.review(csvBytes, installed));
    }

    interface F { void run(); }

    static String expect(F f) {
        try { f.run(); return null; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    /** تزوير موقّع ذاتياً: استبدال قيمة سطر وإعادة التوقيع على كل ما قبله — بالبايتات. */
    static byte[] resignWith(byte[] sealed, String linePrefix, String newValue) {
        int tail = "sig ".length() + 64 + 1;
        int bodyLen = sealed.length - tail;                 // كل ما قبل سطر التوقيع (بالبايتات)
        byte[] prefix = Arrays.copyOf(sealed, bodyLen);
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

    /** تزوير خارجي خالص: إفساد قيمة التوقيع الخارجي فقط — بلا إعادة توقيع. */
    static byte[] breakOuterSig(byte[] sealed) {
        byte[] out = sealed.clone();
        int v0 = out.length - 1 - 64;               // أول محرف سداسي في قيمة التوقيع
        out[v0] = (byte) (out[v0] == '0' ? 'f' : '0');
        return out;
    }

    /** WordSink وهمي يجمع الكلمات ويعيد عددها — النقاء بلا قاموس حي. */
    static final class FakeSink implements DialectPack.WordSink {
        final List<String> got = new ArrayList<>();
        public int mergePack(List<String> words) { got.addAll(words); return words.size(); }
    }

    public static void main(String[] args) {
        Set<String> installed = inst("سلام", "كتاب", "قلم");
        byte[] b = csv(
                row("كتابية", "كِتَابِيَّة", "كتاب", "adj", "متعلقة بالكتاب والتحرير", "سالم-قلم", "CC0-1.0"),
                row("عدادية", "عَدَّادِيَّة", "عداد", "adj", "متعلقة بالعد والقياس", "نورة-حرف", "CC0-1.0"));
        DictSign.Dpack d = publishOf(b, installed);

        // ---- الدبوس الذهبي: حتمي وهوية اللهجة جزء من القلم ----------------------
        String p1 = DialectPack.goldenPin("مصري", d.wordsSha);
        String p2 = DialectPack.goldenPin("مصري", d.wordsSha);
        String p3 = DialectPack.goldenPin("شامي", d.wordsSha);
        check("pin: نفس اللهجة ونفس الكلمات = نفس الدبوس حرفاً", p1.equals(p2) && p1.length() == 64);
        check("pin: لهجتان بكلمات واحدة دبوساهما مختلفان (الهوية جزء من القلم)", !p1.equals(p3));
        check("pin: المطبّع داخله — القلم يقصّ الأطراف (نفس الدبوس للقصّ الصحيح)",
                DialectPack.goldenPin("  مصري  ", d.wordsSha).equals(p1));
        check("pin: لهجة تالفة تعيد سلسلة فارغة (مطبّع موثق)",
                DialectPack.dialect("لهجة\nمزورة").isEmpty()
                        && DialectPack.dialect("أبجدهوزحطيكلمنسعفصقرشتثخذ").isEmpty());

        // ---- الختم: بايت-دقيق حتمي ----------------------------------------------
        byte[] s1 = DialectPack.seal("مصري", d);
        byte[] s2 = DialectPack.seal("مصري", d);
        check("seal: نفس اللهجة والحزمة = نفس البايتات حرفاً", Arrays.equals(s1, s2));
        String st = new String(s1, StandardCharsets.UTF_8);
        String sigLine = st.substring(st.lastIndexOf("\nsig ") + 1);
        String sigVal = sigLine.substring("sig ".length(), sigLine.length() - 1);
        String expectSig = CommunityDict.sha256Hex(Arrays.copyOf(s1, s1.length - sigLine.length()));
        check("seal: البنية موثقة (سكيمة/لهجة/دبوس/توقيع على كل ما قبله)",
                st.startsWith(DialectPack.SCHEMA + "\n")
                        && st.contains("\ndialect مصري\n")
                        && st.contains("\npin " + p1 + "\n")
                        && sigVal.equals(expectSig)
                        && sigVal.length() == 64);

        // ---- الفتح الصارم: الإيجابيات والدمج الصادق ------------------------------
        DialectPack.Open o = DialectPack.open(s1, null, null);
        check("open: الجولة كاملة — اللهجة والدبوس والكلمات محفوظة",
                o.dialect.equals("مصري") && o.goldenPin.equals(p1)
                        && o.pack.words.size() == 2 && o.pack.sig.equals(d.sig));
        FakeSink sink = new FakeSink();
        int added = DialectPack.merge(o, sink);
        check("merge: العدد الصادق عبر WordSink (كلمتان)", added == 2 && sink.got.size() == 2);
        check("merge: ترتيب الكلمات محفوظ في الدمج (ترتيب المراجعة)",
                sink.got.get(0).equals("كتابية") && sink.got.get(1).equals("عدادية"));

        // ---- التزوير بكل أشكاله — كل واحد يعض بوسمه ------------------------------
        String t = expect(() -> DialectPack.open(resignWith(s1, "pin ", p3), null, null));
        check("forge: دبوس مزور بإعادة توقيع المهاجم يعض بالدبوس الذهبي (DPACK_PIN_MISMATCH)",
                DialectPack.TAG_PIN_MISMATCH.equals(t));
        String t3 = expect(() -> DialectPack.open(breakOuterSig(s1), null, null));
        check("forge: توقيع ختم مزور يعض (DPACK_SIG)", DialectPack.TAG_SIG.equals(t3));
        String t4 = expect(() -> DialectPack.open(("x" + new String(s1, StandardCharsets.UTF_8).substring(1))
                .getBytes(StandardCharsets.UTF_8), null, null));
        check("forge: سطر السكيمة المحرف يعض (DIALECT_SCHEMA)", DialectPack.TAG_SCHEMA.equals(t4));
        String t5 = expect(() -> DialectPack.seal("", d));
        check("forge: لهجة فارغة ترفض النشر (DIALECT_ID)", DialectPack.TAG_DIALECT.equals(t5));
        String t6 = expect(() -> DialectPack.merge(null, sink));
        String t7 = expect(() -> DialectPack.merge(o, null));
        FakeSink emptySink = new FakeSink();
        String t8 = expect(() -> DialectPack.merge(new DialectPack.Open("مصري", p1,
                new DictSign.Pack(d.batchId, d.sig, new ArrayList<>())), emptySink));
        check("merge: الفريق المرفوض يرفض موثقاً (MERGE_REFUSED)",
                DialectPack.TAG_MERGE.equals(t6) && DialectPack.TAG_MERGE.equals(t7)
                        && DialectPack.TAG_MERGE.equals(t8));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
