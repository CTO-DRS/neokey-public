package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * تدريب أسبوع بيتا — تحضير W52 (الإطلاق الناعم: برنامج التجربة) — ci/betaprogram_gate.sh
 *
 * صف W52 في الخارطة: «تفعيل play/beta-program.md + مراقبة الأعطال عبر تشخيصات
 * last_crash.txt + قناة ملاحظات Feedback»، ومعيار خروجه: «دفعة مختبرين أولى +
 * صفر أعطال P0 لمدة أسبوع كامل». الإغلاق نفسه خارجي (يحتاج رفع الحزمة أولاً —
 * المعلقة الخارجية #1 — ثم أسبوعاً حقيقياً بمختبرين حقيقيين) — لكن **آلية**
 * الأسبوع كاملة قابلة للتدريب محلياً بمواد بديلة ثابتة التوثيق، وهذا التدريب
 * يمشيها من أول طرف لآخر بالعقود الإنتاجية الحقيقية ذاتها:
 *
 *   1. الهوية            — هوية البيان (SyncServerPack.VC/VN) == هوية البوابة
 *                          (المانيفست) — اشتقاقان مستقلان يتوافقان، وsubject
 *                          البريد يحملهما (المرجع = المنشور).
 *   2. وثيقة البرنامج    — play/beta-program.md يُقرأ ويُفحص: مقاعد الأربع
 *                          شرائح مجموعها 100، ونموذج §4 الخمسي مرآةً حرفية
 *                          لـFeedback.surveyQuestions() (لا انحراف بين الوثيقة
 *                          والتطبيق — قانون PlayGate نفسه)، وخطوط §5 الخمسة
 *                          حاضرة حرفياً (1% / 0.47% / صفر حرجة / 4.4 / 3 هواتف).
 *   3. حلقة الانضمام     — عقد Feedback كله: joinSteps تحمل البريد وعنوان
 *                          الانضمام (الحلقة لا تموت قبل وجود رابط)، surveyText
 *                          مرقّمة 1..5، mailBody يخرج التقرير المجهول حرفياً
 *                          وتلميح «لا تقرير بعد» حين يغيب.
 *   4. تقارير المختبرين  — سبعة تقارير يومية تُبنى عبر CrashReport.build
 *                          الحقيقية (عقد neokey-crash-1 كما يُشحن) من مواد
 *                          بديلة ثابتة، وتُفحص: العقد حاضر، المعدل يُقرأ ويطابق
 *                          sessions/crashes، الخصوصية حية (regex المعرفات)،
 *                          الموقع يُطهَّر (مسار → unknown)، الأيام بلا أوقات.
 *   5. دفتر الأسبوع      — معيار الخروج صار ميكانيكياً: اليوم الأحمر (P0) =
 *                          يوم معدله ≥ الخط الموثق 1% (p0.day_rate)، والأسبوع
 *                          يُرفض إن قصر (week.short) أو حمل معرفاً
 *                          (privacy.identifier) أو نقص تصنيف نموذج
 *                          (classification.incomplete) أو نقص تغطية شريحة
 *                          (coverage.segment) أو هبط التقييم (rating.below)
 *                          أو فاض ميزانية ANR (anr.budget) — والحكم الأخضر
 *                          BETA_WEEK_OK يتطلب 7/7 يوماً خضراء وp0=0.
 *   6. سلبيات الدفتر     — ست سلبيات بمواد سيئة مولَّدة طازجاً تعضّ كلٌّ
 *                          بوسمها الحرفي، ثم الدفتر الأخضر يعاد (الشجرة تُشفى).
 *
 * الحكم: `BETAPROGRAM_OK checks=N negatives=M/M seats=100 batch=22 rate=…%
 *         vc=1 vn=0.1.0` / `BETAPROGRAM_FAILED <reason>` (exit 1).
 * المواد البديلة ثابتة التوثيق (لا عشوائية) — الحكم بايت-ثابت عبر التشغيلات.
 * الأسبوع الحقيقي الأول يبقى على المالك بعد رفع الحزمة (المعلقة الخارجية #1):
 * يُغذّى الدفتر نفسه بتقارير المختبرين الحقيقية — لا تظاهر هنا ولا هناك.
 * نقي JVM (صفر android.*) — خارج dex التطبيق بالتصميم (نمط W51-prep).
 */
public final class BetaProgram {

    // ---- مواد التدريب الثابتة (موثقة لا مدّعاة — الحكم حتمي) -------------------
    static final long GEN_MS = 1_700_000_000_000L;
    static final long DAY_MS = 86_400_000L;
    /** جلسات الأيام السبعة (مجموعها 10066) وأعطالها (مجموعها 3). */
    static final long[] DAY_SESSIONS = {1382, 1471, 1240, 1519, 1408, 1602, 1444};
    static final long[] DAY_CRASHES = {0, 1, 0, 0, 2, 0, 0};
    /** آخر عطل بالأيام التي فيها أعطال — إطاراتنا نحن فقط (عقد neokey-crash-1). */
    static final String[] DAY_LAST_CRASH = {
            "", "IllegalStateException @ ClipRepo.insert:123", "", "",
            "NullPointerException @ KeyboardView.onDraw:210", "", ""};
    /** أعمدة المشغّل الأسبوعية (البديلة): vitals ANR + التقييم + التغطية + التصنيف. */
    static final double OP_ANR_PCT = 0.02;
    static final double OP_RATING = 4.6;
    static final int[] OP_SEGMENTS = {5, 9, 8};   // 2GB / 3-4GB / 6+GB — دفعة 22
    static final int OP_SURVEYS = 22, OP_CLASSIFIED = 22, OP_BATCH = 22;
    static final int OP_CRASH_SURVEYS = 3, OP_IMPROVE = 9, OP_FUTURE = 10;

    static final String[] METRIC_KEYS = {"create", "show", "key"};
    static final double[][] METRIC_PCT = {{41.0, 95.0}, {63.0, 140.0}, {9.0, 24.0}};

    // ---- خطوط §5 الموثقة (الثوابت الحاملة — الهجوم يعضّ عليها) -----------------
    /** الخط الموثق «معدل أعطال < 1% جلسات» — اليوم الفائت هو يوم P0. */
    static final double P0_RATE_PCT = 1.0;
    /** الخط الموثق «ANR < 0.47%». */
    static final double ANR_PCT_MAX = 0.47;
    /** الخط الموثق «تقييم داخلي ≥ 4.4». */
    static final double RATING_MIN = 4.4;
    /** الخط الموثق «3 هواتف على الأقل من كل شريحة رام». */
    static final int COVER_MIN = 3;
    /** الأسبوع الموثق: سبعة أيام كاملة. */
    static final int WEEK_DAYS = 7;
    /** regex الخصوصية (نمط CrashReportTest نفسه) — المعرف لا يعبر الدفتر. */
    static final String LEAK = "(?s).*(imei|serial|android_id|\"model\"|\"fingerprint\"|\"mac\"|device_name|/data/|http).*";

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("BETAPROGRAM regression: " + name + " (" + desc + ")"); }
    }

    /** سلبة مقاسة: وسم الرفض الحرفي يجب أن يعض على المادة الملطخة. */
    static void neg(String name, String expect, String got) {
        checks++; negTotal++;
        boolean ok = expect.equals(got);
        if (ok) negPass++;
        else System.out.println("NEG-FAILED " + name + " (expected " + expect + ", got " + got + ")");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { fail("identity.args (the gate passes the manifest identity)"); return; }
        String vn = args[0];
        int vc;
        try { vc = Integer.parseInt(args[1]); } catch (NumberFormatException e) { fail("identity.vc " + args[1]); return; }

        // ---- 1: الهوية — اشتقاقان مستقلان يتوافقان ---------------------------
        ck("identity.agrees", SyncServerPack.VC.equals(args[1]) && SyncServerPack.VN.equals(args[0]),
                "the manifest identity == the statement identity (المرجع = المنشور)");
        if (reg > 0) { fail("identity.agrees"); return; }
        String subject = Feedback.mailSubject(vn, vc);
        ck("mail.subject_identity", subject.contains(vn) && subject.contains("(" + vc + ")"),
                "the feedback subject carries the build identity: " + subject);

        // ---- 2: وثيقة البرنامج — تُقرأ وتُفحص لا تُؤتى بها --------------------
        Path doc = Paths.get("play/beta-program.md");
        String md;
        try { md = new String(Files.readAllBytes(doc), StandardCharsets.UTF_8); }
        catch (Exception e) { fail("doc.missing (play/beta-program.md unreadable)"); return; }
        ck("doc.seats", seatsSum(md) == 100, "the four seat segments sum to 100 (got " + seatsSum(md) + ")");
        ck("doc.survey_mirror", surveyMirror(md),
                "the doc's §4 survey mirrors Feedback.surveyQuestions() (5/5, same order)");
        ck("doc.threshold_rate", md.contains("أعطال < 1% جلسات"), "the documented < 1% sessions line is present");
        ck("doc.threshold_anr", md.contains("ANR < 0.47%"), "the documented ANR < 0.47% line is present");
        ck("doc.threshold_p0", md.contains("صفر أعطال حرجة مفتوحة"), "the documented zero-open-critical line is present");
        ck("doc.threshold_rating", md.contains("تقييم داخلي ≥ 4.4"), "the documented >= 4.4 internal rating line is present");
        ck("doc.threshold_coverage", md.contains("3 هواتف على الأقل من كل شريحة"), "the documented 3-devices-per-segment line is present");

        // ---- 3: حلقة الانضمام — عقد Feedback كله ------------------------------
        String steps = Feedback.joinSteps();
        ck("join.email", steps.contains(Feedback.SUPPORT_EMAIL), "joinSteps carries the support email");
        ck("join.subject", steps.contains(Feedback.JOIN_SUBJECT), "joinSteps carries the join subject («" + Feedback.JOIN_SUBJECT + "»)");
        String survey = Feedback.surveyText();
        boolean numbered = true;
        String[] qs = Feedback.surveyQuestions();
        for (int i = 0; i < qs.length; i++) numbered &= survey.contains((i + 1) + ") " + qs[i]);
        ck("survey.numbered", numbered, "surveyText numbers all five questions 1..5");
        String body = Feedback.mailBody("ملاحظة المختبر", "تقرير الاستقرار المجهول:", null);
        ck("mail.body_no_report_hint", body.contains("لا تقرير بعد"),
                "an empty report degrades to the documented no-report hint, never silence");

        // ---- 4: تقارير المختبرين — عقد neokey-crash-1 الحقيقي ------------------
        List<String> week = new ArrayList<>();
        for (int d = 0; d < WEEK_DAYS; d++) {
            long rawAt = DAY_LAST_CRASH[d].isEmpty() ? 0 : GEN_MS - (WEEK_DAYS - d) * DAY_MS;
            week.add(CrashReport.build(vn, vc, 33, DAY_SESSIONS[d], DAY_CRASHES[d],
                    DAY_LAST_CRASH[d], rawAt, "ok", METRIC_KEYS, METRIC_PCT, GEN_MS));
        }
        boolean schema = true, rates = true, privacy = true;
        for (int d = 0; d < week.size(); d++) {
            String r = week.get(d);
            schema &= r.contains("\"schema\":\"neokey-crash-1\"");
            long s = num(r, "sessions"), c = num(r, "crashes");
            double rate = doubleOf(r, "crash_rate_pct");
            rates &= (s == DAY_SESSIONS[d]) && (c == DAY_CRASHES[d])
                    && Math.abs(rate - CrashReport.crashRate(c, s)) < 1e-9;
            privacy &= !r.matches(LEAK);
        }
        ck("report.schema", schema, "all seven reports carry the published neokey-crash-1 schema");
        ck("report.rate_parses", rates, "every report's crash_rate_pct parses back and matches its sessions/crashes");
        ck("report.privacy", privacy, "no identifier, path or url survives in any report (the numbers-only law)");
        String evil = CrashReport.build(vn, vc, 33, 1000, 1,
                "SomeError @ /data/user/0/com.hark/Secret.java:1", GEN_MS - DAY_MS, "ok", METRIC_KEYS, METRIC_PCT, GEN_MS);
        ck("report.where_sanitized", evil.contains("\"where\":\"unknown\""),
                "an external path degrades to unknown inside the built report (sanitizeWhere live)");
        String bucketed = CrashReport.build(vn, vc, 33, 1000, 1, "X @ A.b:1", GEN_MS - 3 * DAY_MS, "ok", METRIC_KEYS, METRIC_PCT, GEN_MS);
        ck("report.days_ago", bucketed.contains("\"days_ago\":3"), "the last crash exports as day buckets (3 days ago), never a clock time");
        ck("mail.body_verbatim", Feedback.mailBody("ملاحظة المختبر", "تقرير الاستقرار المجهول:", week.get(1))
                .contains(week.get(1).trim()), "the mail body carries the tester's report VERBATIM");

        // ---- 5: دفتر الأسبوع — معيار الخروج ميكانيكياً -------------------------
        String greenVerdict = ledger(week, OP_ANR_PCT, OP_RATING, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS);
        System.out.println(greenVerdict);
        ck("ledger.days", greenVerdict.contains("days=7/7"), "the ledger accepts exactly the documented seven-day week");
        ck("ledger.p0", greenVerdict.startsWith("BETA_WEEK_OK") && greenVerdict.contains("p0=0"),
                "zero P0 days across the week (the documented exit criterion, mechanically)");
        ck("ledger.rate", greenVerdict.contains("rate=0.03"), "the weekly rate is the sessions/crashes sum (0.03% < 1%)");
        ck("ledger.anr", greenVerdict.contains("anr=0.02"), "the operator's vitals ANR sits under the 0.47% budget");
        ck("ledger.rating", greenVerdict.contains("rating=4.6"), "the internal rating clears the documented 4.4 floor");
        ck("ledger.coverage", greenVerdict.contains("seg=5/9/8"), "every RAM segment carries at least three devices (5/9/8)");
        ck("ledger.classified", greenVerdict.contains("classified=22/22"),
                "every survey is classified crash/improvement/future (3/9/10 of 22)");

        // ---- 6: سلبيات الدفتر — مواد سيئة مولَّدة طازجة، كل رفض بوسمه ---------
        List<String> p0Week = new ArrayList<>(week);
        p0Week.set(4, CrashReport.build(vn, vc, 33, 1500, 20, "AssertionError @ Perf.record:77", GEN_MS - 2 * DAY_MS, "ok", METRIC_KEYS, METRIC_PCT, GEN_MS));
        neg("neg.p0_day_rate", "p0.day_rate", refuseTag(ledger(p0Week, OP_ANR_PCT, OP_RATING, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS)));

        neg("neg.week_short", "week.short", refuseTag(ledger(week.subList(0, 6), OP_ANR_PCT, OP_RATING, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS)));

        List<String> leakWeek = new ArrayList<>(week);
        leakWeek.set(0, leakWeek.get(0).replace("{\"schema\":", "{\"model\":\"Pixel 8\",\"schema\":"));
        neg("neg.privacy_identifier", "privacy.identifier", refuseTag(ledger(leakWeek, OP_ANR_PCT, OP_RATING, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS)));

        neg("neg.rating_below", "rating.below", refuseTag(ledger(week, OP_ANR_PCT, 4.2, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS)));

        neg("neg.coverage_segment", "coverage.segment", refuseTag(ledger(week, OP_ANR_PCT, OP_RATING, new int[]{2, 9, 8}, OP_CLASSIFIED, OP_SURVEYS)));

        neg("neg.classification", "classification.incomplete", refuseTag(ledger(week, OP_ANR_PCT, OP_RATING, OP_SEGMENTS, 21, OP_SURVEYS)));

        // ---- الشفاء: الدفتر الأخضر يعاد بعد كل السلبيات ------------------------
        String heal = ledger(week, OP_ANR_PCT, OP_RATING, OP_SEGMENTS, OP_CLASSIFIED, OP_SURVEYS);
        ck("heal.week_green", heal.startsWith("BETA_WEEK_OK") && heal.equals(greenVerdict),
                "the green week re-runs byte-identical after every negative (the tree heals)");

        // ---- الحكم --------------------------------------------------------------
        if (negPass < negTotal) { fail("negatives=" + negPass + "/" + negTotal); return; }
        if (reg > 0) {
            System.out.println("BETAPROGRAM_FAILED regressions=" + reg + "/" + checks + " (see BETAPROGRAM regression lines above)");
            System.exit(1);
            return;
        }
        System.out.println("BETAPROGRAM_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " seats=100 batch=" + OP_BATCH + " rate=" + CrashReport.crashRate(3, 10066)
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);
    }

    // ---- دفتر الأسبوع: خطوط §5 الخمسة ميكانيكياً، كل رفض بوسم ثابت -------------

    /**
     * الدفتر يستهلك مخارج العقد الحقيقي نفسها (نصوص neokey-crash-1 كما يصدّرها
     * CrashReport.build — مواد المختبرين غير موثوقة فتُفحص لا تُؤتى بها):
     * الخصوصية أولاً ثم سقف اليوم (P0) ثم سقف الأسبوع ثم أعمدة المشغّل
     * (ANR/التقييم/التغطية/التصنيف) — كل انحراف يُرفض بوسمه الحرفي.
     */
    static String ledger(List<String> dayReports, double anrPct, double rating,
                         int[] segments, int classified, int surveys) {
        if (dayReports.size() != WEEK_DAYS)
            return "BETA_WEEK_REFUSED week.short (" + dayReports.size() + " days, the documented week is " + WEEK_DAYS + ")";
        long sessions = 0, crashes = 0;
        for (String r : dayReports) {
            if (r.matches(LEAK)) return "BETA_WEEK_REFUSED privacy.identifier (the ledger consumes reports, it never trusts them)";
            long s = num(r, "sessions"), c = num(r, "crashes");
            double rate = CrashReport.crashRate(c, s);
            if (rate >= P0_RATE_PCT) return "BETA_WEEK_REFUSED p0.day_rate (day rate " + rate + "% >= " + P0_RATE_PCT + "%)";
            sessions += s; crashes += c;
        }
        double weekly = CrashReport.crashRate(crashes, sessions);
        if (weekly >= P0_RATE_PCT) return "BETA_WEEK_REFUSED p0.week_rate (week rate " + weekly + "% >= " + P0_RATE_PCT + "%)";
        if (anrPct >= ANR_PCT_MAX) return "BETA_WEEK_REFUSED anr.budget (" + anrPct + "% >= " + ANR_PCT_MAX + "%)";
        if (rating < RATING_MIN) return "BETA_WEEK_REFUSED rating.below (" + rating + " < " + RATING_MIN + ")";
        for (int v : segments) if (v < COVER_MIN) return "BETA_WEEK_REFUSED coverage.segment (" + Arrays.toString(segments) + ")";
        if (classified != surveys) return "BETA_WEEK_REFUSED classification.incomplete (" + classified + "/" + surveys + ")";
        return "BETA_WEEK_OK days=" + dayReports.size() + "/" + WEEK_DAYS + " p0=0 rate=" + weekly
                + " anr=" + anrPct + " rating=" + rating
                + " seg=" + segments[0] + "/" + segments[1] + "/" + segments[2]
                + " classified=" + classified + "/" + surveys;
    }

    /** وسم الرفض من سطر الدفتر: BETA_WEEK_REFUSED <tag> (...) → <tag>. */
    static String refuseTag(String verdict) {
        if (verdict.startsWith("BETA_WEEK_OK")) return "NO_REFUSAL";
        int sp = verdict.indexOf(' ');
        int op = verdict.indexOf('(', sp);
        return op < 0 ? verdict.substring(sp + 1) : verdict.substring(sp + 1, op).trim();
    }

    /** مجموع مقاعد §2 (الأعداد في صفوف الجدول). */
    static int seatsSum(String md) {
        String sec = section(md, "## 2.", "## 3.");
        if (sec == null) return -1;
        int sum = 0, rows = 0;
        for (String line : sec.split("\n")) {
            if (!line.startsWith("|")) continue;
            if (line.contains("---") || line.contains("الشريحة")) continue;
            Matcher m = Pattern.compile("\\|\\s*(\\d+)\\s*\\|").matcher(line);
            if (m.find()) { sum += Integer.parseInt(m.group(1)); rows++; }
        }
        return rows == 4 ? sum : -1;
    }

    /** مرآة §4: أسئلة الوثيقة الخمسة == Feedback.surveyQuestions() بالترتيب. */
    static boolean surveyMirror(String md) {
        String sec = section(md, "## 4.", "## 5.");
        if (sec == null) return false;
        List<String> docQ = new ArrayList<>();
        for (String line : sec.split("\n")) {
            Matcher m = Pattern.compile("^(\\d+)\\. (.+)$").matcher(line.trim());
            if (m.matches()) docQ.add(m.group(2));
        }
        String[] appQ = Feedback.surveyQuestions();
        if (docQ.size() != appQ.length) return false;
        for (int i = 0; i < appQ.length; i++) {
            String q = docQ.get(i);
            if (q.endsWith(" (NPS مصغر)")) q = q.substring(0, q.length() - " (NPS مصغر)".length());
            if (!q.equals(appQ[i])) return false;
        }
        return true;
    }

    static String section(String md, String from, String to) {
        int a = md.indexOf(from), b = md.indexOf(to);
        if (a < 0 || b < 0 || b <= a) return null;
        return md.substring(a, b);
    }

    static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static double doubleOf(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    static void fail(String reason) {
        System.out.println("BETAPROGRAM_FAILED " + reason);
        System.exit(1);
    }

    private BetaProgram() { throw new UnsupportedOperationException(); }
}
