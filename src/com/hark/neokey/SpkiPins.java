package com.hark.neokey;

import java.security.MessageDigest;

/**
 * دبابيس SPKI لقناة المزامنة — المرحلة 5، الأسبوع 1 (الأسبوع 26).
 *
 * الشريحة المحلية القابلة للإغلاق من المعلق الموثق «نشر خادم مزامنة حي بـ TLS
 * على نطاق حقيقي» (docs/PHASE4-W4-REPORT.md §5): عقد ثقة العميل — القناة
 * المقبولة تُعرَّف بهوية مفتاح الخادم (sha256 على DER لـ SubjectPublicKeyInfo)
 * لا بسلسلة شهادات قابلة للتجديد، فحتى مهاجم بشهادة سلسلة صحيحة لا يُقبل إلا
 * إذا كان مفتاحه نفسه مثبتاً.
 *
 * عقد الفشل المغلق (لا حالة غامضة):
 *   • لا دبابيس (فارغ/صفر)   → رفض عند البناء "no_pins"      — قناة بلا ثقة لا تُفتح.
 *   • دبوس غير سداسي/طول 64   → "bad_pin_hex"                 — لا تحليل متسامح.
 *   • دبوس مكرر              → "dup_pin"                      — لا غموض في القائمة.
 *   • مفتاح غير مثبت         → verify=false → رفض المصافحة    — القناة تُغلق قبل أي بايت.
 *
 * نقي JVM: صفر android.* — يعمل على الجهاز (javax.net.ssl من الإطار) وعلى أي JDK.
 * التحقق بـ MessageDigest.isEqual على بايتات الملخص (زمن ثابت على الملخص).
 */
public final class SpkiPins {

    /** الدبابيس سداسي صغير حرفي 64 محرفاً — بترتيب الإدخال بلا تكرار. */
    private final String[] pins;
    /** الملخصات بايتية المحسوبة مرة واحدة عند البناء. */
    private final byte[][] pinBytes;

    /** يُبنى بدبابيس سداسية — أي شكل غريب أو قائمة فارغة رفض عند البناء. */
    public SpkiPins(String... hexPins) {
        if (hexPins == null || hexPins.length == 0)
            throw new SyncVault.SyncRefused("no_pins");
        pins = new String[hexPins.length];
        pinBytes = new byte[hexPins.length][];
        for (int i = 0; i < hexPins.length; i++) {
            String p = hexPins[i];
            if (p == null || p.length() != 64 || !isHex(p))
                throw new SyncVault.SyncRefused("bad_pin_hex");
            String lower = p.toLowerCase(java.util.Locale.ROOT);
            for (int j = 0; j < i; j++)
                if (pins[j].equals(lower)) throw new SyncVault.SyncRefused("dup_pin");
            pins[i] = lower;
            pinBytes[i] = fromHex(lower);
        }
    }

    /** sha256 على DER المفتاح العمومي (SPKI) — بصمة الهوية المثبتة. */
    public static String pinOf(byte[] spkiDer) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(spkiDer);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                              .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new SyncVault.SyncRefused("pin_digest");
        }
    }

    /** هل هذا المفتاح مثبت؟ مقارنة زمن-ثابت على بايتات الملخص. */
    public boolean verify(byte[] spkiDer) {
        if (spkiDer == null || spkiDer.length == 0) return false;
        byte[] p;
        try { p = MessageDigest.getInstance("SHA-256").digest(spkiDer); }
        catch (Exception e) { return false; }
        boolean any = false;
        for (byte[] pin : pinBytes) if (MessageDigest.isEqual(pin, p)) any = true;
        return any;
    }

    /** الدبابيس كما أُدخلت (سداسي صغير). */
    public String[] pins() { return pins.clone(); }
    public int size() { return pins.length; }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private SpkiPins() { throw new UnsupportedOperationException(); }
}
