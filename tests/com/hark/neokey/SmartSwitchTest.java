package com.hark.neokey;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * اختبارات المبدّل الذكي — المرحلة 1، البند C2 (الأسبوع 6). إيجابي وسلبي:
 * الأوضاع كلها، مانع التذبذب، احترام إعداد المستخدم، الحتمية، الأداء.
 */
public class SmartSwitchTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void run() {
        final List<String> HIJAZI = Arrays.asList(
                "والله", "ايش", "الموضوع", "دحين", "صعب", "شوي", "لكن", "مليح", "انشاء", "الله", "اخلص", "اليوم");
        final List<String> NAJDI = Arrays.asList(
                "طيب", "ذلحين", "رايح", "على", "الدوام", "وش", "الوضع", "كذا", "زين", "ما", "شاء", "الله");
        final List<String> GULF = Arrays.asList(
                "وينكم", "الحين", "ابغى", "اشوف", "شلون", "الطريق", "منكم", "خوش", "فرصة", "وايد", "حلوة", "اليوم");
        final List<String> MSA = Arrays.asList(
                "سأذهب", "إلى", "المكتبة", "غدا", "لأقرأ", "كتابا", "في", "الأدب", "العربي", "القديم", "مع", "صديقي");

        // ---- الأوضاع الصريحة ---------------------------------------------------
        SmartSwitch.Decision off = SmartSwitch.decide(HIJAZI, SmartSwitch.OFF);
        check("off: يستخدم الفصحى حتى مع نص لهجة كثيف", "ar".equals(off.dictId) && !off.dialectActive() && "off".equals(off.reason));

        SmartSwitch.Decision msaMode = SmartSwitch.decide(GULF, SmartSwitch.MSA);
        check("msa-mode: احترام إعداد المستخدم حرفياً", "ar".equals(msaMode.dictId) && "msa-mode".equals(msaMode.reason));

        check("مثبتة حجازي: قاموسها بلا كشف", "ar_hijazi".equals(SmartSwitch.decide(MSA, Dialect.HIJAZI).dictId)
                && SmartSwitch.decide(MSA, Dialect.HIJAZI).evidence == -1);
        check("مثبتة نجدي: قاموسها", "ar_najdi".equals(SmartSwitch.decide(MSA, Dialect.NAJDI).dictId));
        check("مثبتة خليجي: قاموسها", "ar_gulf".equals(SmartSwitch.decide(MSA, Dialect.GULF).dictId));

        // ---- auto: التبديل ------------------------------------------------------
        SmartSwitch.Decision h = SmartSwitch.decide(HIJAZI, SmartSwitch.AUTO);
        check("auto: حجازي كثيف → ar_hijazi", "ar_hijazi".equals(h.dictId) && Dialect.HIJAZI.equals(h.dialect)
                && h.evidence >= SmartSwitch.MIN_EVIDENCE);
        check("auto: نجدي كثيف → ar_najdi", "ar_najdi".equals(SmartSwitch.decide(NAJDI, SmartSwitch.AUTO).dictId));
        check("auto: خليجي عام → ar_gulf", "ar_gulf".equals(SmartSwitch.decide(GULF, SmartSwitch.AUTO).dictId));
        SmartSwitch.Decision m = SmartSwitch.decide(MSA, SmartSwitch.AUTO);
        check("auto: فصحى صرفة تبقى فصحى", "ar".equals(m.dictId) && "auto-msa".equals(m.reason));

        // ---- مانع التذبذب -------------------------------------------------------
        List<String> oneWord = Arrays.asList(
                "سأذهب", "إلى", "المكتبة", "وين", "لأقرأ", "كتابا", "في", "الأدب", "العربي", "القديم", "مع", "صديقي");
        SmartSwitch.Decision weak = SmartSwitch.decide(oneWord, SmartSwitch.AUTO);
        check("مانع التذبذب: كلمة لهجة واحدة لا تقلب القاموس",
                "ar".equals(weak.dictId) && "weak-evidence".equals(weak.reason) && weak.evidence == 1);

        List<String> twoWords = Arrays.asList(
                "سأذهب", "إلى", "المكتبة", "وين", "ابغى", "كتابا", "في", "الأدب", "العربي", "القديم", "مع", "صديقي");
        check("مانع التذبذب: دليلان يبدلان", "ar_gulf".equals(SmartSwitch.decide(twoWords, SmartSwitch.AUTO).dictId));

        check("نافذة فارغة/ملغاة في auto = فصحى",
                "ar".equals(SmartSwitch.decide(Collections.<String>emptyList(), SmartSwitch.AUTO).dictId)
                        && "ar".equals(SmartSwitch.decide(null, SmartSwitch.AUTO).dictId));

        // ---- الحتمية والمتانة ---------------------------------------------------
        check("حتمية: نفس المدخل نفس القرار",
                SmartSwitch.decide(GULF, SmartSwitch.AUTO).equals(SmartSwitch.decide(GULF, SmartSwitch.AUTO))
                        && SmartSwitch.decide(HIJAZI, SmartSwitch.AUTO).equals(SmartSwitch.decide(HIJAZI, SmartSwitch.AUTO)));

        check("وضع غير معروف يرتد للتلقائي بلا استثناء",
                SmartSwitch.decide(GULF, "yemeni").equals(SmartSwitch.decide(GULF, SmartSwitch.AUTO))
                        && !SmartSwitch.validMode("yemeni") && SmartSwitch.validMode(SmartSwitch.AUTO));

        check("معرفات القواميس مطابقة لأسماء الأصول",
                "ar_hijazi".equals(SmartSwitch.dictFor(Dialect.HIJAZI))
                        && "ar_najdi".equals(SmartSwitch.dictFor(Dialect.NAJDI))
                        && "ar_gulf".equals(SmartSwitch.dictFor(Dialect.GULF))
                        && "ar".equals(SmartSwitch.dictFor(Dialect.MSA))
                        && "ar".equals(SmartSwitch.dictFor(null)));

        // ---- الأداء -------------------------------------------------------------
        for (int i = 0; i < 30; i++) SmartSwitch.decide(HIJAZI, SmartSwitch.AUTO);   // إحماء
        long[] t = new long[200];
        for (int i = 0; i < 200; i++) {
            long n0 = System.nanoTime();
            SmartSwitch.decide(HIJAZI, SmartSwitch.AUTO);
            t[i] = System.nanoTime() - n0;
        }
        java.util.Arrays.sort(t);
        double p50 = t[100] / 1e6;
        check("p50 < 1ms (فعلياً " + String.format("%.4f", p50) + "ms)", p50 < 1.0);
    }
}
