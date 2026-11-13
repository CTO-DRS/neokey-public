package com.hark.neokey;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * W67 (roadmap v2): الطمأنينة PanicWipe — إيماءة/زر طوارئ: مسح فوري
 * شامل (سجل الحافظة + التكيّف الشخصي + معاينات المقاطع) مع تأكيد
 * بخطوتين، + وضع تخفي مؤقت بمؤقت يعود فيه كل شيء مغلقًا. نقي JVM:
 * المخازن مجردة تُمرر من الربط (الربط الحي يمسح عبر المسارات القائمة:
 * ClipRepo.clearAllClips + AdaptEngine.wipe + درع ShieldOTP).
 *
 * القوانين:
 *   1. لا مسح بلا تأكيد — arm يولد رمزاً حتمياً، وconfirm يطابقه؛ رمز
 *      غائب/خاطئ = PANIC_REFUSED ولا يُصفر بايت واحد.
 *   2. البايت-فارغ الحرفي — المسح إعادة كتابة صفرية كاملة بطول المخزن
 *      الأصلي (لا حذفاً منطقياً فقط): بعد اللمسة كل بايت صفر.
 *   3. النسخ الموقعة محصنة — أي مخزن اسمه يبدأ «signed-backup» يُتخطى
 *      دائماً ويظهر في kept، حتى بتأكيد صحيح: الاستعادة من النسخ
 *      الموقعة تبقى ممكنة عبر المسار القائم، والإتلاف لا يلمسها.
 *   4. فشل مغلق — مخزن معطوب يجعل الحالة PANIC_PARTIAL موثقة (مع عدّاد
 *      ما مُسح)، لا نجاحاً صامتاً.
 *   5. التخفي المؤقت يعيد كل شيء مغلق — عند انتهاء المؤقت الحالة
 *      الافتراضية ALL_CLOSED تُعاد (التخفي مغلق، الشبكة مغلقة، الدرع
 *      على القانون الثنائي)، والمؤقت غير الموجب يرفض.
 */
public final class PanicWipe {

    public static final String WIPED = "PANIC_WIPED";
    public static final String PARTIAL = "PANIC_PARTIAL";
    public static final String REFUSED = "PANIC_REFUSED";
    public static final String ALL_CLOSED = "ALL_CLOSED";

    private PanicWipe() {
    }

    /** المخزن المستهدف: اسم + البيانات الحية (تُعاد كتابتها في نفس المصفوفة). */
    public static final class Store {
        public final String name;
        public final byte[] data;

        public Store(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    /** نتيجة اللمسة: الحالة + ما مُسح + ما بقي محصوناً. */
    public static final class Result {
        public final String state;
        public final int wipedStores;
        public final long zeroedBytes;
        public final List<String> kept;

        Result(String state, int wipedStores, long zeroedBytes, List<String> kept) {
            this.state = state;
            this.wipedStores = wipedStores;
            this.zeroedBytes = zeroedBytes;
            this.kept = kept;
        }
    }

    /**
     * رمز التأكيد الحتمي: sha256(«panic-arm|» + الأسماء بترتيبها + «|» +
     * مجموع الأحجام) مختصراً 8 — نفس المخازن يعطي نفس الرمز دوماً.
     */
    public static String armToken(List<Store> stores) {
        StringBuilder sb = new StringBuilder("panic-arm|");
        long total = 0;
        for (Store s : stores) {
            sb.append(s.name).append('|');
            if (s.data != null) total += s.data.length;
        }
        sb.append(total);
        return sha256(sb.toString()).substring(0, 8);
    }

    /** اللمسة الكاملة: تأكيد + مسح بايت-فارغ + حماية النسخ الموقعة. */
    public static Result wipeAll(List<Store> stores, String confirmToken,
                                 String expectedToken) {
        if (confirmToken == null || expectedToken == null
                || !confirmToken.equals(expectedToken)) {
            return new Result(REFUSED, 0, 0, new ArrayList<String>());
        }
        int wiped = 0;
        long zeroed = 0;
        boolean partial = false;
        List<String> kept = new ArrayList<String>();
        for (Store s : stores) {
            if (s.name != null && s.name.startsWith("signed-backup")) {
                kept.add(s.name);                      // القانون 3: محصون دوماً
                continue;
            }
            if (s.data == null) {
                partial = true;                        // القانون 4: فشل مغلق
                continue;
            }
            for (int i = 0; i < s.data.length; i++) s.data[i] = 0;
            wiped++;
            zeroed += s.data.length;
        }
        return new Result(partial ? PARTIAL : WIPED, wiped, zeroed, kept);
    }

    /** بدء وضع التخفي المؤقت — مدة غير موجبة ترفض (0 = لا تخفي). */
    public static long stealthBegin(long nowSec, long durSec) {
        if (durSec <= 0) return -1;
        return nowSec + durSec;
    }

    /**
     * فحص التخفي: ACTIVE داخل المهلة، وعند الانتهاء EXPIRED — والحالة
     * الافتراضية ALL_CLOSED تُعاد (كل شيء مغلق: التخفي، الشبكة، الدرع
     * على القانون الثنائي).
     */
    public static String stealthCheck(long nowSec, long untilSec) {
        if (untilSec < 0) return ALL_CLOSED;
        return nowSec < untilSec ? "STEALTH_ACTIVE" : ALL_CLOSED;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                    .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "panic-arm-unknown";
        }
    }
}
