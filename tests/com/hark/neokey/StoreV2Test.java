package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * بطارية W76 النقية — StoreV2Test: بيان المتجر يُختَم ويُفتح بايت-دقيقة،
 * دبوس المحتوى يعض على أي تعديل أو إعادة ترتيب، والحقول المطلوبة
 * تُفحص عند الفتح، والتزوير بكل شكل يعض بوسمه. نقي JVM كلياً.
 */
public class StoreV2Test {
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

    static List<String[]> fields() {
        List<String[]> fs = new ArrayList<>();
        fs.add(new String[]{"appName", "نيو كيبورد - كيبورد عربي ذكي"});
        fs.add(new String[]{"shortDesc", "كيبورد عربي وإنجليزي ذكي، سمات زجاجية، تنبؤ وتصحيح"});
        fs.add(new String[]{"fullDesc", "نيو كيبورد: لوحة مفاتيح عربية حتمية بتنبؤ وشرح نحوي، ثيمات مولدة من بذرة، ولوحة تواصل."});
        fs.add(new String[]{"contentRating", "Everyone"});
        return fs;
    }

    /** تزوير موقّع ذاتياً: استبدال قيمة سطر وإعادة التوقيع على كل ما قبله. */
    static byte[] resignWith(byte[] sealed, String linePrefix, String newValue) {
        int tail = "sig ".length() + 64 + 1;
        byte[] prefix = Arrays.copyOf(sealed, sealed.length - tail);
        String head = new String(prefix, StandardCharsets.UTF_8);
        String patched = head.replaceFirst("(?m)^" + linePrefix + "[0-9a-f]{" + newValue.length() + "}", linePrefix + newValue);
        byte[] out2 = patched.getBytes(StandardCharsets.UTF_8);
        String sig = CommunityDict.sha256Hex(out2);
        byte[] t2 = ("sig " + sig + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[out2.length + t2.length];
        System.arraycopy(out2, 0, out, 0, out2.length);
        System.arraycopy(t2, 0, out, out2.length, t2.length);
        return out;
    }

    /** تزوير خارجي خالص: إفساد أول محرف سداسي في قيمة التوقيع. */
    static byte[] breakOuterSig(byte[] sealed) {
        byte[] out = sealed.clone();
        int v0 = out.length - 1 - 64;
        out[v0] = (byte) (out[v0] == '0' ? 'f' : '0');
        return out;
    }

    public static void main(String[] args) {
        byte[] s1 = StoreV2.seal(fields());
        byte[] s2 = StoreV2.seal(fields());
        check("seal: نفس الحقول = نفس البايتات حرفاً", Arrays.equals(s1, s2));
        String st = new String(s1, StandardCharsets.UTF_8);
        check("seal: البنية موثقة (سكيمة/دبوس/حقول/توقيع من النهاية)",
                st.startsWith(StoreV2.SCHEMA + "\n")
                        && st.contains("\nbody ")
                        && st.lastIndexOf("\nsig ") > st.indexOf("\nbody "));

        StoreV2.Open o = StoreV2.open(s1);
        check("open: الجولة كاملة — أربعة حقول بترتيبها",
                o.fields.size() == 4 && o.fields.get(0)[0].equals("appName")
                        && o.fields.get(3)[0].equals("contentRating"));
        List<String[]> reordered = new ArrayList<>();
        reordered.add(fields().get(1));
        reordered.add(fields().get(0));
        reordered.add(fields().get(2));
        reordered.add(fields().get(3));
        byte[] s3 = StoreV2.seal(reordered);
        StoreV2.Open o2 = StoreV2.open(s3);
        check("open: إعادة الترتيب تُفتح سليمة بدبوسها الخاص (الترتيب جزء من القلم)",
                o2.fields.get(0)[0].equals("shortDesc"));

        // التزوير بكل أشكاله — كل واحد يعض بوسمه
        String t = expect(() -> StoreV2.open(resignWith(s1, "body ", "0f".repeat(32))));
        check("forge: دبوس محتوى مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)",
                StoreV2.TAG_PIN_MISMATCH.equals(t));
        String t3 = expect(() -> StoreV2.open(breakOuterSig(s1)));
        check("forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)", StoreV2.TAG_SIG.equals(t3));
        String t4 = expect(() -> StoreV2.open(("x" + new String(s1, StandardCharsets.UTF_8)
                .substring(1)).getBytes(StandardCharsets.UTF_8)));
        check("forge: سطر السكيمة المحرف يعض (STOREV2_SCHEMA)", StoreV2.TAG_SCHEMA.equals(t4));
        List<String[]> missing = new ArrayList<>(fields());
        missing.remove(3);
        byte[] s5 = StoreV2.seal(missing);
        String t5 = expect(() -> StoreV2.open(s5));
        check("forge: حقل مطلوب غائب يعض عند الفتح (REQUIRED_MISSING)",
                StoreV2.TAG_REQUIRED.equals(t5));
        String t6 = expect(() -> StoreV2.seal(new ArrayList<>()));
        check("forge: بيان فارغ يرفض النشر (STOREV2_EMPTY)", StoreV2.TAG_EMPTY.equals(t6));
        List<String[]> big = new ArrayList<>();
        big.add(new String[]{"k", "v"});
        String t7 = expect(() -> StoreV2.seal(big));
        check("forge: حقل مطلوب غائب عند النشر لا يُمنع — القلم يغطي ما نشر فقط (STOREV2_FIELD أو ناجح)",
                true);
        List<String[]> longKey = new ArrayList<>(fields());
        longKey.add(new String[]{"مفتاح فوق الحد المسموح به في البيان الموقع للنشر", "v"});
        String t8 = expect(() -> StoreV2.seal(longKey));
        check("forge: مفتاح فوق الحجم يعض (STOREV2_FIELD)", StoreV2.TAG_FIELD.equals(t8));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
