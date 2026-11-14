package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * بطارية W75 النقية — SyncV2Test: الدفعة تُختَم وتُفتح بايت-دقيقة،
 * النونس حتمي من المحتوى، دبوس المحتوى يعض على أي تعديل أو إعادة
 * ترتيب، والتوقيع الخارجي من النهاية بالبايتات، والتزوير بكل شكل يعض
 * بوسمه. نقي JVM كلياً.
 */
public class SyncV2Test {
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

    static List<String> ops() {
        List<String> ops = new ArrayList<>();
        ops.add("fav.add\tكتاب");
        ops.add("hist.add\tمصر");
        ops.add("dict.learn\tقلم");
        return ops;
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
        byte[] s1 = SyncV2.seal(ops());
        byte[] s2 = SyncV2.seal(ops());
        check("seal: نفس العمليات = نفس البايتات حرفاً (حتمية الختم)", Arrays.equals(s1, s2));
        String st = new String(s1, StandardCharsets.UTF_8);
        check("seal: البنية موثقة (سكيمة/نونس/دبوس/عمليات/توقيع من النهاية)",
                st.startsWith(SyncV2.SCHEMA + "\n")
                        && st.contains("\nnonce ")
                        && st.contains("\nbody ")
                        && st.lastIndexOf("\nsig ") > st.indexOf("\nbody "));

        SyncV2.Open o = SyncV2.open(s1);
        check("open: الجولة كاملة — النونس والعمليات الثلاث بترتيبها",
                o.nonce.length() == 12 && o.ops.size() == 3
                        && o.ops.get(0).equals("fav.add\tكتاب")
                        && o.ops.get(2).equals("dict.learn\tقلم"));
        SyncV2.Open o2 = SyncV2.open(SyncV2.seal(ops()));
        check("open: النونس حتمي من المحتوى — نفس الدفعة نفس النونس",
                o2.nonce.equals(o.nonce));
        List<String> other = new ArrayList<>(ops());
        other.set(0, "fav.add\tقلم");
        SyncV2.Open o3 = SyncV2.open(SyncV2.seal(other));
        check("open: محتوى مختلف = نونس مختلف (الإعادة تُفحص بالنونس)",
                !o3.nonce.equals(o.nonce));

        // التزوير بكل أشكاله — كل واحد يعض بوسمه
        String t = expect(() -> SyncV2.open(resignWith(s1, "body ",
                "0f".repeat(32))));
        check("forge: دبوس محتوى مزور بإعادة توقيع المهاجم يعض (DPACK_PIN_MISMATCH)",
                SyncV2.TAG_PIN_MISMATCH.equals(t));
        String t3 = expect(() -> SyncV2.open(breakOuterSig(s1)));
        check("forge: توقيع خارجي مفسود بالقيمة يعض (DPACK_SIG)", SyncV2.TAG_SIG.equals(t3));
        String t4 = expect(() -> SyncV2.open(("x" + new String(s1, StandardCharsets.UTF_8)
                .substring(1)).getBytes(StandardCharsets.UTF_8)));
        check("forge: سطر السكيمة المحرف يعض (SYNCV2_SCHEMA)", SyncV2.TAG_SCHEMA.equals(t4));
        String t5 = expect(() -> SyncV2.seal(new ArrayList<>()));
        check("forge: دفعة فارغة ترفض النشر (SYNCV2_EMPTY)", SyncV2.TAG_EMPTY.equals(t5));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) many.add("op" + i);
        String t6 = expect(() -> SyncV2.seal(many));
        check("forge: فوق السقف (65 عملية) يعض (SYNCV2_LIMIT)", SyncV2.TAG_LIMIT.equals(t6));
        List<String> badOp = new ArrayList<>();
        badOp.add("طويل جداً " + "أ".repeat(520));
        String t7 = expect(() -> SyncV2.seal(badOp));
        check("forge: سطر عملية فوق الحجم يعض (SYNCV2_OP)", SyncV2.TAG_OP.equals(t7));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
