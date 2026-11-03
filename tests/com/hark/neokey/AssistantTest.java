package com.hark.neokey;

import java.util.Arrays;

/** اختبارات المساعد المحلي — المرحلة 1، البند D1 (الأسبوع 6): إيجابي وسلبي. */
public class AssistantTest {

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
        final String LONG =
                "أعلنت الوزارة اليوم عن خطة جديدة لتطوير التعليم الرقمي في المدارس الحكومية. "
                + "وتشمل الخطة توزيع أجهزة لوحية على جميع طلاب المرحلة المتوسطة بنهاية العام. "
                + "وقال المتحدث الرسمي إن المشروع يمتد على ثلاث سنوات بميزانية قدرها 500 مليون ريال. "
                + "كما تتضمن الخطة تدريب المعلمين على المنصات الرقمية الحديثة في مراكز متخصصة. "
                + "وأضاف المتحدث أن المرحلة الأولى ستبدأ في ثلاث مناطق تجريبية مختارة بعناية. "
                + "وتؤكد الوزارة أن الهدف النهائي هو رفع جودة التعليم وتقليص الفجوة الرقمية بين المدارس. "
                + "وفي السياق ذاته أعلنت الوزارة عن شراكة مع الجامعات المحلية لتطوير المحتوى العربي التعليمي.";

        // ---------------- تلخيص ------------------------------------------------
        String s3 = Assistant.summarize(LONG, 3);
        check("الملخص أقصر من النص", s3.length() < LONG.length() * 0.75);
        check("الملخص بثلاث جمل كما طُلب", Assistant.sentences(s3).size() == 3);
        check("الملخص يحتفظ بالموضوع المتكرر (المتحدث)", s3.contains("المتحدث"));
        check("الملخص يحافظ على الترتيب الأصلي",
                LONG.indexOf(s3.substring(0, Math.min(10, s3.length())))
                        <= LONG.indexOf(s3.substring(Math.max(0, s3.length() - 10))));
        check("حتمية: مرتان مخرج واحد", s3.equals(Assistant.summarize(LONG, 3)));

        String one = "جملة واحدة قصيرة جدا هنا.";
        check("جمل أقل من المطلوب → النص كله", one.equals(Assistant.summarize(one, 3)));
        check("حراسة: null/فارغ/عدد غير صالح → \"\" بلا استثناء",
                Assistant.summarize(null, 3).isEmpty()
                        && Assistant.summarize("", 3).isEmpty()
                        && Assistant.summarize("   ", 3).isEmpty()
                        && Assistant.summarize(LONG, 0).isEmpty()
                        && Assistant.summarize(LONG, -2).isEmpty());

        String s5 = Assistant.summarize(LONG, 5);
        check("إثبات سلبي: معلمة العدد حية لا مهملة", !s3.equals(s5) && Assistant.sentences(s5).size() == 5);

        String reversed = "مناقشة عامة خفيفة لا موضوع مركزي فيها. "
                + "سطر ثان عام كذلك. "
                + "أعلنت الوزارة الخطة الجديدة لتطوير التعليم الرقمي في المدارس، وقال المتحدث إن المشروع يمتد ثلاث سنوات بميزانية كبيرة.";
        check("إثبات سلبي: الدرجة ترددية لا تموضعية (الأعلى تردداً آخراً يُلتقط)",
                Assistant.summarize(reversed, 1).contains("الوزارة"));

        // p50
        for (int i = 0; i < 20; i++) Assistant.summarize(LONG, 3);
        long[] t = new long[100];
        for (int i = 0; i < 100; i++) {
            long n0 = System.nanoTime();
            Assistant.summarize(LONG, 3);
            t[i] = System.nanoTime() - n0;
        }
        java.util.Arrays.sort(t);
        double p50 = t[50] / 1e6;
        check("p50 تلخيص < 2ms (فعلياً " + String.format("%.4f", p50) + "ms)", p50 < 2.0);

        // ---------------- إعادة الصياغة ----------------------------------------
        String resp = Assistant.rephrase("ابغي منك خدمة بسيطة وش رايك فيه", Assistant.RESPECTFUL);
        check("الرقي: العبارة الأطول (ابغي منك→أرجو منك) تسبق المفردة", resp.contains("أرجو") && !resp.contains("ابغي"));
        check("الرقي: منك→أرجو", resp.contains("أرجو"));
        check("الرقي: وش رايك→ما رأيك (عبارة)", resp.contains("ما رأيك") && !resp.contains("وش"));

        String simp = Assistant.rephrase("نرجو التكرم بالإفادة بخصوص الموضوع في حال الحاجة، بناءً على طلبكم", Assistant.SIMPLE);
        check("الأبسط: نرجو التكرم→رجاءً", simp.contains("رجاءً") && !simp.contains("التكرم"));
        check("الأبسط: بخصوص→عن", simp.contains("عن") && !simp.contains("بخصوص"));
        check("الأبسط: في حال→إذا", simp.contains("إذا") && !simp.contains("في حال"));
        check("الأبسط: بناءً على→حسب", simp.contains("حسب") && !simp.contains("بناء"));

        String long1 = Assistant.rephrase("نرجو منكم التكرم بالاطلاع", Assistant.SIMPLE);
        check("العبارة الأطول تسبق المفردة (نرجو منكم التكرم)", long1.contains("رجاءً") && !long1.contains("نرجو"));

        String bounds = Assistant.rephrase("موعدنا الساعة مو الوقت نفسه", Assistant.RESPECTFUL);
        check("حدود الكلمة: موعدنا لا تُمس", bounds.contains("موعدنا"));
        check("حدود الكلمة: مو→ليس تعمل على الحد", bounds.contains("ليس"));

        check("التطبيع يلتقط الهمزات المهملة (ابغى=أبغى)",
                Assistant.rephrase("ابغى اشوفك", Assistant.RESPECTFUL).contains("أرغب"));

        String msa = "يسعدني أن ألتحق بكم في الاجتماع المقرر غدا";
        String msaOut = Assistant.rephrase(msa, Assistant.RESPECTFUL);
        check("نص فصيح يمر شبه بلا لمس", msaOut.contains("يسعدني") && msaOut.contains("الاجتماع"));

        check("حراسة: null/فارغ/وضع غير معروف بلا استثناء ولا تخمين",
                Assistant.rephrase(null, Assistant.SIMPLE).isEmpty()
                        && Assistant.rephrase("", Assistant.RESPECTFUL).isEmpty()
                        && Assistant.rephrase("نص كما هو", "غير معروف").equals("نص كما هو")
                        && Assistant.rephrase("نص كما هو", null).equals("نص كما هو"));

        String rt = "ابغي وش رايك نرجو التكرم";
        check("حتمية إعادة الصياغة",
                Assistant.rephrase(rt, Assistant.RESPECTFUL).equals(Assistant.rephrase(rt, Assistant.RESPECTFUL))
                        && Assistant.rephrase(rt, Assistant.SIMPLE).equals(Assistant.rephrase(rt, Assistant.SIMPLE)));

        check("عدد القواعد موثق فوق الحد",
                Assistant.rules(Assistant.RESPECTFUL) >= 40 && Assistant.rules(Assistant.SIMPLE) >= 25);

        // ---------------- ثلاث نبرات -------------------------------------------
        String[] tn = Assistant.tones("ممكن ترسل لي التقرير");
        check("ثلاث نبرات ثلاثة مخارج متميزة",
                tn.length == 3 && !tn[0].equals(tn[1]) && !tn[1].equals(tn[2]) && !tn[0].equals(tn[2]));

        check("الرسمية بطاقاتها (السلام عليكم + مع التقدير + الجوهر)",
                tn[0].contains("السلام عليكم") && tn[0].contains("مع التقدير") && tn[0].contains("التقرير"));
        check("الودية بصيغة أخوية", tn[1].contains("هلا") || tn[1].contains("مشكور"));
        check("القصيرة سطر موجز", tn[2].length() < 80 && !tn[2].contains("\n") && tn[2].contains("التقرير"));

        String thanksF = Assistant.draft("شكرا على مساعدتك الكريمة", Assistant.TONE_FORMAL);
        check("نية الشكر تغير القالب", thanksF.contains("نشكر لكم"));
        check("نية الاعتذار في القصير", Assistant.draft("آسف على التأخير في الرد", Assistant.TONE_SHORT).startsWith("آسف"));
        check("نية السؤال في الرسمي", Assistant.draft("هل الاجتماع صباحا", Assistant.TONE_FORMAL).contains("الإفادة"));

        String[] notes = {"شكرا على جهودكم", "آسف على التأخير", "ممكن ترسل الملف",
                "تذكير بالاجتماع", "تم تسليم المشروع", "هل وصلتك الرسالة"};
        String[] expect = {"thanks", "apology", "request", "followup", "confirm", "question"};
        boolean intents = true;
        for (int i = 0; i < notes.length; i++) {
            String f = Assistant.draft(notes[i], Assistant.TONE_FORMAL);
            if (!(f.contains("السلام عليكم") && f.contains("مع التقدير"))) intents = false;
            if (!expect[i].equals(Assistant.intent(notes[i]))) intents = false;
        }
        check("النوايا الست مغطاة بالقوالب والكشف", intents);
        check("نية عامة لغيرها", "general".equals(Assistant.intent("تفاح برتقال")));

        String[] en = Assistant.tones(null);
        boolean emptyOk = en.length == 3;
        for (String x : en) if (x.trim().isEmpty()) emptyOk = false;
        String[] blank = Assistant.tones("   ");
        for (String x : blank) if (x.trim().isEmpty()) emptyOk = false;
        check("حراسة: null/فارغ ينتج قوالب صالحة", emptyOk);

        check("نبرة خارج النطاق ترتد للرسمية",
                Assistant.draft("شيء ما", 99).equals(Assistant.draft("شيء ما", Assistant.TONE_FORMAL))
                        && Assistant.draft("شيء ما", -1).equals(Assistant.draft("شيء ما", Assistant.TONE_FORMAL)));

        check("حتمية النبرات", Arrays.equals(Assistant.tones("ممكن ترسل لي التقرير"), Assistant.tones("ممكن ترسل لي التقرير")));

        check("المسار كله محلي: يعمل بلا مفاتيح ولا شبكة",
                !Assistant.draft("أي شيء", Assistant.TONE_SHORT).trim().isEmpty());
    }
}
