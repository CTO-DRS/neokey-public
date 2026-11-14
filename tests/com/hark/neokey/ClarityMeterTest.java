package com.hark.neokey;

import java.util.List;

/**
 * بطارية W68 — ClarityMeterTest: **كل مقياس من الخمسة له اختبار مرجعي
 * (نص يفشل ونص يمر)** — معيار الخروج نصّ عليها — مع الحتمية (نفس النص =
 * نفس الدرجة دائماً) والفشل المغلق وسطر العرض. نقي JVM — يجري داخل
 * tests/run.sh وبوابة ci/clarity_gate.sh.
 */
public class ClarityMeterTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** حكم مكوّن معيّن داخل نص. */
    static boolean partPass(String text, int idx) {
        return ClarityMeter.evaluate(text).parts.get(idx).pass;
    }

    public static void main(String[] args) throws Exception {
        // ---- المقياس 1: طول الجملة (نص يفشل + نص يمر) ---------------------------
        String longSent = "الكتاب يشرح أساس الهندسة الحديثة ويبدأ من المبادئ "
                + "الرياضية ثم ينتقل إلى التطبيقات العملية في الميدان مع أمثلة "
                + "موسعة عن التصميم الإنشائي وأسس المقاومة والانحناء في الجسور "
                + "الخرسانية المسلحة حديثة التنفيذ على ضفاف الوادي الكبير";
        String shortSents = "الجو صافٍ اليوم. الطائرات تُحلّق عالياً. الرحلة ممتعة.";
        check("sent: النص الطويل الجملة يفشل (متوسط > " + ClarityMeter.SENT_MAX + ")",
                !partPass(longSent, 0)
                        && ClarityMeter.evaluate(longSent).parts.get(0).value != null);
        check("sent: الجمل القصيرة تمر",
                partPass(shortSents, 0));

        // ---- المقياس 2: التكرار (نص يفشل + نص يمر) -------------------------------
        String repeatFail = "المشروع نجح. المشروع أكمل. المشروع استمر. المشروع توسع. المشروع انتهى.";
        String repeatPass = "المهندس قدّم التصميم فاعتمدت اللجنة الخطة وبدأ التنفيذ فوراً.";
        check("repeat: الكلمة المسيطرة يفشل (تردد > 25٪)",
                !partPass(repeatFail, 1));
        check("repeat: النص المتنوع يمر",
                partPass(repeatPass, 1));

        // ---- المقياس 3: الحشو (نص يفشل + نص يمر) ---------------------------------
        String fillFail = "في الحقيقة الأمر واضح، نوعا ما النتيجة جيدة، على العموم نتجاوز.";
        String fillPass = "الأمر واضح والنتيجة جيدة ونتجاوز الطريق.";
        check("filler: الحشو المعلن يُكتسب ظاهرياً ويُفشل",
                !partPass(fillFail, 2)
                        && ClarityMeter.evaluate(fillFail).parts.get(2).value.equals("3"));
        check("filler: النص النظيف يمر بصفر حشو",
                partPass(fillPass, 2)
                        && ClarityMeter.evaluate(fillPass).parts.get(2).value.equals("0"));

        // ---- المقياس 4: كثافة التشكيل (نص يفشل + نص يمر) -------------------------
        String tashFail = "مُحَمَّدٌ رَسُولُ اللهِ صَلَّى اللهُ عَلَيْهِ وَسَلَّمَ";
        String tashPass = "محمد رسول الله صلى الله عليه وسلم";
        check("tashkeel: النص المشكَّل بالكامل يفشل (كثافة > 35٪)",
                !partPass(tashFail, 3));
        check("tashkeel: النص السليم يمر",
                partPass(tashPass, 3));

        // ---- المقياس 5: تنوع المفردات TTR (نص يفشل + نص يمر) ----------------------
        String ttrFail = "الكتاب الكتاب الورق الكتاب الورق الكتاب الورق الكتاب";
        String ttrPass = "العلم نور والعمل أساس النجاح والإخلاص فعل";
        check("ttr: المفردات المكررة يفشل (TTR < 40٪)",
                !partPass(ttrFail, 4));
        check("ttr: المفردات المتنوعة تمر",
                partPass(ttrPass, 4));

        // ---- القانون 2: الحتمية — نفس النص = نفس الدرجة دائماً ---------------------
        ClarityMeter.Meter a = ClarityMeter.evaluate(fillFail);
        ClarityMeter.Meter b = ClarityMeter.evaluate(new StringBuilder(fillFail).toString());
        check("det: نفس النص = نفس الدرجة والمكونات وسطر العرض حرفاً",
                a.score == b.score && a.state.equals(b.state)
                        && a.display.equals(b.display)
                        && a.parts.size() == b.parts.size());
        boolean sameParts = true;
        for (int i = 0; i < a.parts.size(); i++) {
            sameParts &= a.parts.get(i).pass == b.parts.get(i).pass
                    && a.parts.get(i).value.equals(b.parts.get(i).value);
        }
        check("det: القيم الخمس مطابقة حرفاً بين جريانين", sameParts);
        List<ClarityMeter.Part> c = ClarityMeter.evaluate(longSent).parts;
        int sum = 0;
        for (ClarityMeter.Part p : c) if (p.pass) sum += 20;
        check("det: الدرجة = مجموع المكوّنات 20×الناجحة (معلومة المكونات)",
                sum == ClarityMeter.evaluate(longSent).score);

        // ---- القانون 4: فشل مغلق — النص القصير غير كافٍ لا درجة مصطنعة ------------
        ClarityMeter.Meter tiny = ClarityMeter.evaluate("كلمة وحيدة");
        check("failclosed: النص الأقصر من 3 محتوى → SCORE_INSUFFICIENT (score=-1)",
                ClarityMeter.INSUFFICIENT.equals(tiny.state) && tiny.score == -1
                        && tiny.display.contains("غير كافٍ"));
        check("failclosed: النص الفارغ وnull غير كافيان بأمان",
                ClarityMeter.INSUFFICIENT.equals(
                        ClarityMeter.evaluate("").state)
                        && ClarityMeter.INSUFFICIENT.equals(
                        ClarityMeter.evaluate(null).state));

        // ---- القانون 5: سطر العرض الحتمي ------------------------------------------
        ClarityMeter.Meter full = ClarityMeter.evaluate(shortSents);
        check("display: السطر يحمل الدرجة على 100 و المكونات الخمسة",
                full.display.contains("/100") && full.parts.size() == 5
                        && full.display.contains("الجملة"));
        int passCount = 0;
        for (ClarityMeter.Part p : full.parts) if (p.pass) passCount++;
        check("display: علامات ✓/✗ تطابق عدد المكونات الناجحة",
                full.display.replace("✓", "").length()
                        == full.display.length() - passCount);

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
