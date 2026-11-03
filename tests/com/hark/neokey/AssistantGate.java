package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assistant gate (phase 1, items C2+D1, week 6) — the roadmap bar, measured
 * not claimed:
 *
 *   «تبديل فصحى/لهجة حسب السياق + 3 قدرات مساعد محلية (تلخيص الحافظة،
 *    إعادة صياغة، ثلاث نبرات) + بوابة الأذونات للاتصال الخارجي»
 *
 * What it does:
 *   1. SmartSwitch accuracy on a curated context set (assets/testset_switch.txt):
 *      48 rows of "label<TAB>sentence" — auto mode must pick the right DICT
 *      (not just the dialect label): MSA stays MSA, heavy dialect rows activate
 *      their dialect dictionary, single-marker hysteresis rows stay MSA.
 *      Bar: overall ≥95% AND every class 100% of its intended decision family
 *      (the switch wraps C1's 100% detector — a regression here is a red build).
 *   2. determinism: the whole set decided twice → identical verdicts;
 *      latency: decide() p50 < 1ms per row.
 *   3. Assistant local capabilities (100% on-device):
 *      summarize: curated 8 texts — shorter than source (unless fewer
 *      sentences than requested), top-topic sentence retained, exact
 *      determinism, p50 < 2ms; rephrase: BOTH directions fire on a canonical
 *      probe per rule family, no leftover source phrase, word boundaries
 *      intact, p50 < 2ms; tones: 3 distinct drafts, formal/friendly/short
 *      structural markers, 6 intents covered, determinism.
 *   4. NetGate fail-closed matrix: every ambiguous state denies; the only
 *      allowed path is master=on + cap=on (or ask+consent); endpoint
 *      allowlist refuses http/similar-host/prefix tricks; audit line format
 *      has exactly 5 fields and never carries user text.
 *   5. negative proofs (the gate must be able to FAIL):
 *      a) summarize with maxSentences=0 must return "" (parameter live);
 *      b) a frequency-scored sentence placed LAST must still win (scoring
 *         not positional);
 *      c) unknown capability denied even with everything on (fail-closed);
 *      d) mode=msa with heavy dialect text stays MSA (settings respected);
 *      e) endpoint allowlist rejects a look-alike host.
 *
 * Verdict: ASSIST_GATE_OK / ASSIST_GATE_FAILED <reason> (exit 1). Pure JVM.
 */
public final class AssistantGate {

    private static final double MIN_SWITCH_ACC = 95.0;
    private static final double MAX_P50_MS = 1.0;
    private static final double MAX_SUM_P50_MS = 2.0;

    static final class Gate extends RuntimeException {
        Gate(String m) { super(m); }
    }

    static void ok(boolean cond, String msg) {
        if (!cond) throw new Gate(msg);
    }

    public static void main(String[] args) {
        try {
            run();
            System.out.println("ASSIST_GATE_OK");
        } catch (Gate g) {
            System.out.println("ASSIST_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("ASSIST_GATE_FAILED internal: " + e);
            System.exit(1);
        }
    }

    static void run() throws Exception {
        // ---- 1) SmartSwitch accuracy on the curated set ------------------------
        List<String> lines = Files.readAllLines(Paths.get("assets/testset_switch.txt"), StandardCharsets.UTF_8);
        int total = 0, hit = 0, msaRows = 0, msaHit = 0;
        String firstBad = null;
        int clsTotal = 0, clsHit = 0;
        String curClass = null;
        List<Integer> perCls = new ArrayList<>();
        for (String raw : lines) {
            if (raw.trim().isEmpty() || raw.startsWith("#")) continue;
            int t = raw.indexOf('\t');
            ok(t > 0, "testset_switch.txt: bad row (need label<TAB>sentence): " + raw);
            String expect = raw.substring(0, t);
            String sent = raw.substring(t + 1);
            String got = SmartSwitch.decide(Dialect.tokenize(sent), SmartSwitch.AUTO).dictId;
            total++;
            if (got.equals(expect)) hit++;
            else if (firstBad == null) firstBad = expect + "→" + got + ":" + sent;
            // per-class accounting (class = expected dict id)
            if (!expect.equals(curClass)) {
                if (curClass != null) perCls.add(clsTotal == 0 ? 0 : (clsHit * 100) / clsTotal);
                curClass = expect; clsTotal = 0; clsHit = 0;
            }
            clsTotal++;
            if (got.equals(expect)) clsHit++;
            if ("ar".equals(expect)) { msaRows++; if ("ar".equals(got)) msaHit++; }
        }
        if (curClass != null) perCls.add(clsTotal == 0 ? 0 : (clsHit * 100) / clsTotal);
        double acc = total == 0 ? 0 : 100.0 * hit / total;
        for (int c : perCls) ok(c == 100, "switch class accuracy " + c + "% < 100% (first bad: " + firstBad + ")");
        ok(acc >= MIN_SWITCH_ACC, "switch accuracy " + String.format("%.1f", acc) + "% < " + MIN_SWITCH_ACC
                + "% (first bad: " + firstBad + ")");
        System.out.println("ASSISTGATE switch_acc=" + String.format("%.1f", acc) + "% rows=" + total
                + " classes_ok=" + perCls.size());

        // ---- 2) determinism + latency ------------------------------------------
        for (String raw : lines) {
            if (raw.trim().isEmpty()) continue;
            int t = raw.indexOf('\t');
            String sent = raw.substring(t + 1);
            SmartSwitch.Decision a = SmartSwitch.decide(Dialect.tokenize(sent), SmartSwitch.AUTO);
            SmartSwitch.Decision b = SmartSwitch.decide(Dialect.tokenize(sent), SmartSwitch.AUTO);
            ok(a.equals(b), "switch not deterministic for: " + sent);
        }
        List<String> probe = Dialect.tokenize("ايش الموضوع دحين شوي مليح انشاء الله اخلص اليوم بس صعب ");
        for (int i = 0; i < 30; i++) SmartSwitch.decide(probe, SmartSwitch.AUTO);   // warmup
        long[] t = new long[200];
        for (int i = 0; i < 200; i++) {
            long n0 = System.nanoTime();
            SmartSwitch.decide(probe, SmartSwitch.AUTO);
            t[i] = System.nanoTime() - n0;
        }
        Arrays.sort(t);
        double p50 = t[100] / 1e6;
        ok(p50 < MAX_P50_MS, "switch p50 " + String.format("%.3f", p50) + "ms >= " + MAX_P50_MS);
        System.out.println("ASSISTGATE switch_p50=" + String.format("%.4f", p50) + "ms");

        // ---- 3a) summarize ------------------------------------------------------
        String[] texts = SUM_TEXTS;
        for (String txt : texts) {
            String s3 = Assistant.summarize(txt, 3);
            ok(!s3.trim().isEmpty(), "summarize empty for text: " + txt.substring(0, Math.min(20, txt.length())));
            int nSent = Assistant.sentences(txt).size();
            if (nSent > 3) ok(s3.length() < txt.length(), "summary not shorter for: " + txt.substring(0, 20));
            else ok(s3.length() <= txt.length(), "short text grew");
            ok(Assistant.summarize(txt, 3).equals(s3), "summarize not deterministic");
        }
        String top = Assistant.summarize(SUM_TEXTS[0], 1);
        ok(top.contains("الوزارة") || top.contains("الخطة"), "top-topic sentence missing from summary: " + top);
        ok(Assistant.summarize("نص", 0).isEmpty(), "negative proof (a): maxSentences=0 returned non-empty");
        // frequency-not-position proof
        String freqProbe = "كلام عام لا موضوع مركزي فيه ولا تفاصيل مهمة. "
                + "سطر عام ثان بلا خصائص بارزة في النص. "
                + "الوزارة أعلنت الخطة الجديدة لتطوير التعليم الرقمي، وقال المتحدث إن المشروع يمتد بميزانية كبيرة.";
        ok(Assistant.summarize(freqProbe, 1).contains("الوزارة"), "negative proof (b): scoring is positional, not frequency-based");
        long[] st = new long[100];
        for (int i = 0; i < 20; i++) Assistant.summarize(SUM_TEXTS[0], 3);
        for (int i = 0; i < 100; i++) {
            long n0 = System.nanoTime();
            Assistant.summarize(SUM_TEXTS[0], 3);
            st[i] = System.nanoTime() - n0;
        }
        Arrays.sort(st);
        double sp50 = st[50] / 1e6;
        ok(sp50 < MAX_SUM_P50_MS, "summarize p50 " + String.format("%.3f", sp50) + "ms >= " + MAX_SUM_P50_MS);
        System.out.println("ASSISTGATE summarize_texts=" + texts.length + " p50=" + String.format("%.4f", sp50) + "ms");

        // ---- 3b) rephrase -------------------------------------------------------
        for (String[] p : REPHRASE_PROBES) {
            String out = Assistant.rephrase(p[1], p[0]);
            ok(!out.equals(p[1]), "rephrase(" + p[0] + ") did not change: " + p[1]);
            ok(out.contains(p[2]), "rephrase(" + p[0] + ") missing expected [" + p[2] + "] in: " + out);
            if (p[3] != null) ok(!out.contains(p[3]), "rephrase(" + p[0] + ") leftover source phrase [" + p[3] + "] in: " + out);
            ok(out.equals(Assistant.rephrase(p[1], p[0])), "rephrase not deterministic: " + p[1]);
        }
        // word boundaries: موعد must survive «مو» rule
        String bnd = Assistant.rephrase("موعدنا الساعة مو الوقت نفسه", Assistant.RESPECTFUL);
        ok(bnd.contains("موعدنا"), "word boundary broken: " + bnd);
        System.out.println("ASSISTGATE rephrase_probes=" + REPHRASE_PROBES.length
                + " rules_respectful=" + Assistant.rules(Assistant.RESPECTFUL)
                + " rules_simple=" + Assistant.rules(Assistant.SIMPLE));

        // ---- 3c) tones ----------------------------------------------------------
        String[] tones = Assistant.tones("ممكن ترسل لي التقرير");
        ok(tones.length == 3 && !tones[0].equals(tones[1]) && !tones[1].equals(tones[2]) && !tones[0].equals(tones[2]),
                "tones not distinct");
        ok(tones[0].contains("السلام عليكم") && tones[0].contains("مع التقدير"), "formal markers missing: " + tones[0]);
        ok(tones[0].contains("التقرير"), "formal draft lost the note core");
        ok(tones[2].length() < 80 && !tones[2].contains("\n"), "short tone too long: " + tones[2]);
        String[] intents = {"شكرا على جهودكم", "آسف على التأخير", "ممكن ترسل الملف",
                "تذكير بالاجتماع", "تم تسليم المشروع", "هل وصلتك الرسالة"};
        for (String n : intents) {
            String f = Assistant.draft(n, Assistant.TONE_FORMAL);
            ok(f.contains("السلام عليكم") && f.contains("مع التقدير"), "intent template broken for: " + n);
        }
        ok(Arrays.equals(Assistant.tones("ممكن ترسل لي التقرير"), tones), "tones not deterministic");
        System.out.println("ASSISTGATE tones_intents=6 distinct=3");

        // ---- 4) NetGate fail-closed matrix ---------------------------------------
        ok(NetGate.full(null, "on", NetGate.CAP_WRITER_AI, true) == NetGate.DENY_MASTER, "netgate: null master must deny");
        ok(NetGate.full("off", "on", NetGate.CAP_WRITER_AI, true) == NetGate.DENY_MASTER, "netgate: master off must deny");
        ok(NetGate.full("on", null, NetGate.CAP_WRITER_AI, true) == NetGate.DENY_CAP, "netgate: null cap must deny");
        ok(NetGate.full("on", "off", NetGate.CAP_WRITER_AI, true) == NetGate.DENY_CAP, "netgate: cap off must deny");
        ok(NetGate.full("on", "on", "other_cap", true) == NetGate.DENY_UNKNOWN, "negative proof (c): unknown cap must deny");
        ok(NetGate.full("on", "ask", NetGate.CAP_WRITER_AI, false) == NetGate.ASK_USER, "netgate: ask without consent must ASK");
        ok(NetGate.full("on", "ask", NetGate.CAP_WRITER_AI, true) == NetGate.ALLOW_ASK, "netgate: ask+consent must allow");
        ok(NetGate.full("on", "on", NetGate.CAP_WRITER_AI, false) == NetGate.ALLOW_CAP, "netgate: on+on must allow");
        // mode=msa with heavy dialect stays MSA (settings respected)
        SmartSwitch.Decision d = SmartSwitch.decide(
                Dialect.tokenize("ايش الوضع دحين وايد زين شخبارك"), SmartSwitch.MSA);
        ok("ar".equals(d.dictId), "negative proof (d): mode=msa switched to " + d.dictId);
        // endpoint allowlist
        ok(!NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "https://openrouter.ai.evil.com/api/v1/x"),
                "negative proof (e): look-alike host must be refused");
        ok(NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "https://openrouter.ai/api/v1/chat/completions"),
                "netgate: the real endpoint must pass");
        String audit = NetGate.auditLine(NetGate.CAP_WRITER_AI, NetGate.ALLOW_CAP, 1730000000000L);
        ok(audit.split("\t").length == 5 && audit.startsWith("NETGATE\t"), "audit line format broken: " + audit);
        System.out.println("ASSISTGATE netgate_failclosed=ok audit5=ok");

        // ---- settings wiring sanity (mode pref validation is pure) --------------
        for (String m : new String[]{SmartSwitch.AUTO, SmartSwitch.OFF, SmartSwitch.MSA,
                Dialect.HIJAZI, Dialect.NAJDI, Dialect.GULF})
            ok(SmartSwitch.validMode(m), "validMode rejected a legal mode: " + m);
        ok(!SmartSwitch.validMode("yemeni"), "validMode accepted an unknown mode");
        System.out.println("ASSISTGATE modes_valid=6");
    }

    /** نصوص التلخيص المنسقة — داخل المستودع قابلة للفحص (نفس منهجية C1). */
    private static final String[] SUM_TEXTS = {
            "أعلنت الوزارة اليوم عن خطة جديدة لتطوير التعليم الرقمي في المدارس الحكومية. "
            + "وتشمل الخطة توزيع أجهزة لوحية على جميع طلاب المرحلة المتوسطة بنهاية العام. "
            + "وقال المتحدث الرسمي إن المشروع يمتد على ثلاث سنوات بميزانية قدرها 500 مليون ريال. "
            + "كما تتضمن الخطة تدريب المعلمين على المنصات الرقمية الحديثة في مراكز متخصصة. "
            + "وأضاف المتحدث أن المرحلة الأولى ستبدأ في ثلاث مناطق تجريبية مختارة بعناية. "
            + "وتؤكد الوزارة أن الهدف النهائي هو رفع جودة التعليم وتقليص الفجوة الرقمية بين المدارس.",
            "شهدت السوق المالية تذبذباً ملحوظاً خلال الجلسة الأخيرة من هذا الأسبوع. "
            + "وارتفع مؤشر الأسهم الرئيسي بنسبة واحدة بالمئة مدفوعاً بقطاع الطاقة والبنوك. "
            + "وأوضح محللون أن هذه الحركة تأتي مع ترقب نتائج الشركات الكبرى في الربع الثالث. "
            + "في المقابل تراجع سعر الذهب قليلاً مع تحسن معنويات المستثمرين تجاه المخاطرة. "
            + "ويتوقع خبراء استمرار التقلبات حتى صدور بيانات التضخم الشهرية في الأسبوع المقبل.",
            "عقد الفريق الاجتماع الشهري لمراجعة التقدم في المشروع ومتابعة المهام المتأخرة. "
            + "وأكد قائد الفريق أن مرحلة التطوير الأساسية انتهت في موعدها المحدد سلفاً. "
            + "وتناول الاجتماع أيضاً خطة الاختبار الشاملة التي تبدأ الأسبوع القادم بالتعاون مع فريق الجودة. "
            + "وشكر الفريق على الجهود المبذولة وشدّد على أهمية الالتزام بالجدول الزمني للمرحلة المتبقية. "
            + "واختتم الاجتماع بتوزيع مهام جديدة وتحديد موعد المراجعة القادمة نهاية الشهر.",
            "الرياضة اليوم نتائج مهمة في دوري أبطال آسيا بعد مباريات الأمس المثيرة. "
            + "وفاز فريقنا بهدفين نظيفين في مباراة أقيمت على ملعبه أمام جمهور غفير ملأ المدرجات. "
            + "وسجل الهدفين المهاجم الشاب في الشوط الثاني بعد استغلال خطأ دفاعي واضح من الخصم. "
            + "ويرفع هذا الفوز رصيد الفريق إلى تسع نقاط في صدارة مجموعته قبل جولتين من نهاية دور المجموعات. "
            + "ويلعب الفريق مباراته القادمة خارج ملعبه ضد المتصدر السابق في مباراة معبرة عن مستوى الفريق.",
            "أصدرت الهيئة تقريرها السنوي الجديد عن حالة البنية التحتية الرقمية في المملكة. "
            + "وبيّن التقرير أن تغطية الإنترنت عالي السرعة وصلت إلى تسعين بالمئة من المباني السكنية في المدن الرئيسية. "
            + "كما سجل قطاع الاتصالات نمواً بنسبة خمسة عشر بالمئة مقارنة بالعام السابق مدعوماً بتوسع شبكات الجيل الخامس. "
            + "وأوصى التقرير بمواصلة الاستثمار في مراكز البيانات وتأهيل الكوادر الوطنية في مجال الأمن السيبراني. "
            + "وأكد مسؤولو الهيئة أن الأهداف الطموحة للرؤية تسير على المسار الصحيح بفضل تعاون القطاعين العام والخاص.",
            "مؤتمر التقنية السنوي يستهل أعماله غداً بمشاركة أكثر من مئة متحدث من عشرين دولة. "
            + "وتناقش جلسات المؤتمر هذا العام الذكاء الاصطناعي التوليدي وأخلاقياته وتأثيره على سوق العمل الإقليمي. "
            + "ويستضيف المؤتمر أيضاً معرضاً مصاحباً لأكثر من ستين شركة ناشئة محلية وإقليمية تعرض حلولاً تقنية جديدة. "
            + "وتتوقع اللجنة المنظمة تجاوز أعداد الزوار لخمسة عشر ألف زائر خلال أيام المؤتمر الثلاثة. "
            + "وأعلن المنظمون عن شراكة جديدة مع جامعات محلية لتقديم ورش عمل تدريبية مجانية للطلاب.",
            "توقعت هيئة الأرصاد أمطار رعدية على مرتفعات المنطقة الغربية مساء اليوم والغد. "
            + "وتشمل التوقعات نشاطاً في الرياح السطحية المثيرة للأتربة يقل مع تقدم ساعات الليل تدريجياً. "
            + "ونصحت الهيئة باتخاذ الحيطة في الطرق الجبلية وعدم الخروج إلا للضرورة أثناء فترة الأمطار. "
            + "وترتفع درجات الحرارة تدريجياً مطلق الأسبوع القادم لتقارب معدلاتها المناخية المعتادة لهذا الوقت من العام. "
            + "وتدعو الهيئة الجميع لمتابعة النشرات الدورية عبر قنواتها الرسمية المعتمدة.",
            "افتتحت الجامعة مركزاً جديداً لريادة الأعمال يخدم طلابها وخريجيها من مؤسسي الشركات الناشئة. "
            + "ويقدم المركز برامج إرشاد متخصصة وحضانات مؤسسية مجانية لمدة سنة كاملة للمشاريع المقبولة. "
            + "ويعمل المركز بالتعاون مع صندوق تنمية الموارد البشرية وصناديق استثمار جسور محلية متخصصة. "
            + "وقالت عميدة شؤون الطلاب إن المركز يستهدف تأسيس خمسين شركة ناشئة خلال أول سنتين من تشغيله. "
            + "ويبدأ المركز استقبال طلبات الانضمام من الأسبوع المقبل عبر بوابة الجامعة الإلكترونية.",
    };

    /** مجسات إعادة الصياغة: {الاتجاه، المدخل، يجب أن يظهر، يجب أن يختفي} */
    private static final String[][] REPHRASE_PROBES = {
            {Assistant.RESPECTFUL, "ابغى اشوفك اليوم", "أرغب", "ابغى"},
            {Assistant.RESPECTFUL, "وشلونك", "كيف حالك", null},
            {Assistant.RESPECTFUL, "ليش ما رديت", "لماذا", "ليش"},
            {Assistant.RESPECTFUL, "المكان وايد حلو", "جداً", "وايد"},
            {Assistant.RESPECTFUL, "دحين روح", "الآن", "دحين"},
            {Assistant.RESPECTFUL, "مب زين كذا", "ليس", "مب"},
            {Assistant.SIMPLE, "نرجو التكرم بالاطلاع", "رجاءً", "التكرم"},
            {Assistant.SIMPLE, "بخصوص موضوعك", "عن", "بخصوص"},
            {Assistant.SIMPLE, "في حال الحاجة", "إذا", "في حال"},
            {Assistant.SIMPLE, "بناءً على طلبكم", "حسب", "بناءً"},
            {Assistant.SIMPLE, "نرجو منكم التكرم بالمتابعة", "رجاءً", "التكرم"},
    };

    private AssistantGate() { }
}
