package com.hark.neokey;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * W66 (roadmap v2): شهادة النزاهة IntegrityCert — «الكيبورد الذي يحمل
 * سجل بواباته». نقي JVM: sha256 للأثر المثبت يُحسب بتيار مجزأ ويُقابل
 * بيان الإصدار المرفوع (build/release/signedapk-statement.json: apkSha256
 * للبناء الموقّع) + حالة الهوية (vc/vn) + عدّاد بوابات آخر كوميت — روح
 * IDENTITY_IS_ONE على شاشة المستخدم.
 *
 * القوانين:
 *   1. الحكم من البايتات لا من الادعاء — المطابقة تعني أن الأثر المثبت
 *      هو الأرتِفْت الموقّع المرفوع نفسه، لا نسخة عنه.
 *   2. فشل مغلق — مفقود/غير مقروء = CERT_UNKNOWN، ولا يجوز أن يُعرض
 *      أبداً كمطابقة (القانون الثالث في W66).
 *   3. العرض الحتمي — سطر واحد بأرقام وأوسام فقط: الحالة + الهوية +
 *      العدّاد، والبصمة الكاملة لا تدخل سطر الشاشة (مختصرة 12).
 *
 * الحساب الحي المؤكد عند التصميم: build/NeoKey-2036.apk (5,648,680 بايت)
 * sha256 = 0274d153a497a86cf94ff38f98e7b9ebe804ca699316d7395d3f86b80e096e75
 * يطابق البيان المرفوع بايت-لطرفاً.
 */
public final class IntegrityCert {

    public static final String MATCH = "CERT_MATCH";
    public static final String MISMATCH = "CERT_MISMATCH";
    public static final String UNKNOWN = "CERT_UNKNOWN";

    private IntegrityCert() {
    }

    /** الحكم النهائي: الحالة + سطر الشاشة الحتمي. */
    public static final class Cert {
        public final String state;
        public final String display;

        Cert(String state, String display) {
            this.state = state;
            this.display = display;
        }
    }

    /**
     * يقابل أثراً مثبتاً بالبيان المرفوع: (المسار) مقابل (sha المتوقعة +
     * الهوية vc/vn + عدّاد الفحوص من آخر كوميت). حتمي بالبايتات.
     */
    public static Cert evaluate(String apkPath, String expectedSha256,
                                int expectVc, String expectVn, int gatesChecks) {
        String actual = sha256Of(apkPath);
        if (actual == null) {
            return new Cert(UNKNOWN,
                    "الشهادة: مجهولة — الأثر غير مقروء، ولا تعرض كمطابقة أبداً");
        }
        if (!actual.equalsIgnoreCase(safe(expectedSha256))) {
            return new Cert(MISMATCH,
                    "الشهادة: غير مطابقة — الأثر المثبت ليس البيان المرفوع");
        }
        return new Cert(MATCH,
                "الشهادة: مطابقة — الهوية vc " + expectVc + " / " + expectVn
                        + " — فحوص آخر كوميت " + gatesChecks);
    }

    /** sha256 بتيار مجزأ 4KB — null عند الفشل (فشل مغلق). */
    public static String sha256Of(String path) {
        if (path == null) return null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            return null;
        }
        FileInputStream in = null;
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            in = new FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {
            }
        }
    }

    /** البصمة المختصرة لسطر الشاشة (أول 12 حرفاً). */
    public static String shortSha(String hex) {
        if (hex == null || hex.length() < 12) return "—";
        return hex.substring(0, 12);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
