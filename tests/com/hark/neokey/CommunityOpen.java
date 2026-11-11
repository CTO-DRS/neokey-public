package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * تدريب فتح قناة المساهمات — تحضير W53 (القاموس المجتمعي: فتح خط المساهمات) — ci/communityopen_gate.sh
 *
 * صف W53 في الخارطة: «فتح قناة المساهمات خارجيًا عبر عقد CSV الصارم (CommunityDict)
 * + صفحة الشكر CONTRIBUTORS.md + docs/COMMUNITY-DICT.md كدليل مساهم»، ومعيار خروجه:
 * «أول دفعة خارجية مقبولة عبر COMMUNITYGATE_OK + صفر رفض خاطئ». الإغلاق نفسه خارجي
 * بطبيعته (الدفعات الحقيقية تأتي من مساهمين حقيقيين بعد نشر الحزمة — المعلقة
 * الخارجية #1) — لكن **آلية** القناة كاملة قابلة للتدريب محلياً بمواد بديلة ثابتة
 * التوثيق، وهذا التدريب يمشيها من أول طرف لآخر بالعقود الإنتاجية الحقيقية ذاتها
 * (CommunityDict + DictSign كما يُشحنان، والقاموس المثبت الحقيقي assets/ar.txt):
 *
 *   1. الهوية            — هوية البيان (SyncServerPack.VC/VN) == هوية البوابة
 *                          (المانيفست) — اشتقاقان مستقلان يتوافقان (المرجع = المنشور).
 *   2. وثيقة الدليل      — docs/COMMUNITY-DICT.md تُقرأ وتُفحص لا تُؤتى بها:
 *                          ترويسة §1 مرآة حرفية لـCommunityDict.HEADER بالترتيب،
 *                          وسوم الرفض الخمسة عشر حاضرة حرفياً، وقوائم pos والتراخيص،
 *                          وقناة الوصول (البريد + الموضوع الموثق)، وقانون الشكر،
 *                          وسلسلة الدمج الموقّع — لا انحراف بين الدليل والكود.
 *   3. مدقق القبول       — قواعد الدليل §1/§2 صارت مدققاً مبدئياً مستقلاً (اشتقاق
 *                          ثانٍ عن الحكم المُشحن): الاتفاق على كل صف من كل دفعة
 *                          = الدليل مرآة أمينة للكود؛ أي انحراف يظهر خلافاً صارخاً.
 *   4. صفحة الشكر        — CONTRIBUTORS.md تُفحص: الترويسة الحرفية، الترخيصان،
 *                          القناة، قانون الشكر (المقبولة فقط / تنازلي بالعدد ثم
 *                          أبجدي / المرفوض لا يظهر)، قانون المعرف، والدمج الموقّع.
 *   5. القناة كما تصل    — دفعة خارجية نظيفة (9 صفوف، 3 مساهمين بديلين) بأسطر
 *                          CRLF وفاصلة مقتبسة وسطر فارغ وتشكيل جزئي — كلها
 *                          مقبولة 9/9 (صفر رفض خاطئ)، والشكر يتولد ميكانيكياً:
 *                          المقبولة فقط، الترتيب تنازلي بالعدد ثم أبجدي، والمرفوض
 *                          (اسم شريد في دفعة مختلطة) لا يظهر أبداً.
 *   6. الدمج الموقّع     — من الدفعة إلى الحزمة عبر DictSign كما يُشحن: publish
 *                          يحمل batchId التقرير وصفوف القبول بترتيبها، البيان
 *                          مرجع توقيع الإنسان، load بالبيان يمر بايت-لطرفاً،
 *                          ومسار الجهاز بلا بيان يمر بالدبوس الذهبي — وثلاث
 *                          سلبيات دمج بأوسامها الحرفية (DPACK_SIG / EMPTY_PACK /
 *                          BATCH_MISMATCH).
 *   7. سلبيات القناة     — ثلاثة عشر سلبياً بمواد سيئة مولَّدة طازجاً تعضّ بوسمها
 *                          الحرفي كلها (فئة الرفض صفية من judge المُشحن + فئتا
 *                          الملف BOM/ترويسة)، و«المحظور قبل المكرر» قانوناً
 *                          مقاساً (كلمة مثبتة بمعنى سام ترفع FORBIDDEN لا
 *                          DUPLICATE)، والاتفاق يمتد إلى السلبيات نفسها.
 *   8. الشفاء            — القناة الخضراء تعاد بايت-لطرفاً بعد كل السلبيات.
 *
 * الحكم: `COMMUNITYOPEN_OK checks=N negatives=M/M words=W contribs=K batches=B
 *         pack=P vc=1 vn=0.1.0` / `COMMUNITYOPEN_FAILED <reason>` (exit 1).
 * المواد البديلة ثابتة التوثيق (لا عشوائية) — الحكم بايت-ثابت عبر التشغيلات.
 * القناة الحقيقية الأولى تبقى على المالك بعد نشر الحزمة: أول دفعة خارجية تجري
 * عبر بوابة الأنبوب القائمة (COMMUNITY_GATE_OK، المرحلة 17) على مواد حقيقية —
 * لا تظاهر هنا ولا هناك. نقي JVM (صفر android.*) — خارج dex التطبيق بالتصميم
 * (نمط W50/W51/W52-prep: لا مرحلة أنبوب ولا مفاتيح gates.env ولا قوائم أغلاق
 * ولا صف اختبارات — 42 مرحلة و1864 اختباراً بايت-ثابتة).
 */
public final class CommunityOpen {

    // ---- الخطوط الموثقة (الثوابت الحاملة — الهجوم يعضّ عليها) -------------------
    /** الترويسة الموحدة الحرفية (الدليل §1 == CommunityDict.HEADER). */
    static final String HEADER_LINE = "word,diacritized,lemma,pos,gloss,contributor,license";
    /** عنوان قسم الشكر الذي يولّده CommunityDict.contributorsMd() حرفياً. */
    static final String THANKS_TITLE = "# شكر المساهمين";
    /** ترويسة جدول الشكر الحرفية. */
    static final String THANKS_TABLE = "| المساهم | مفردات مقبولة |";
    /** صفحة الشكر في جذر المستودع (متطلب W53). */
    static final String PAGE = "CONTRIBUTORS.md";
    /** دليل المساهم (متطلب W53). */
    static final String DOC = "docs/COMMUNITY-DICT.md";
    /** الخط الموثق «أقل من حرفين» — مدقق القبول المبدئي (الاشتقاق الثاني). */
    static final int INTAKE_GLOSS_MIN = 2;
    /** الخط الموثق «{3,32}» — نمط الاسم المستعار (لا بريد ولا هوية). */
    static final String INTAKE_ALIAS = "[A-Za-z0-9_.\\-\\u0621-\\u064A]{3,32}";
    /** الموضوع الموثق لقناة الوصول. */
    static final String BATCH_SUBJECT = "دفعة قاموس";
    /** المساهمون البدلاء الثابتة (موثقة لا مدّعاة — الأسماء الحقيقية تأتي بالدفعات). */
    static final String[] STANDINS = {"amina", "sami", "kareem"};
    /** اسم المُخرب في الدفعة المختلطة — يجب ألا يظهر في الشكر أبداً. */
    static final String SABOTEUR = "saboteur";

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("COMMUNITYOPEN regression: " + name + " (" + desc + ")"); }
    }

    /** سلبة مقاسة: وسم الرفض الحرفي يجب أن يعض على المادة الملطخة. */
    static void neg(String name, String expect, String got) {
        checks++; negTotal++;
        boolean ok = expect.equals(got);
        if (ok) negPass++;
        else System.out.println("NEG-FAILED " + name + " (expected " + expect + ", got " + got + ")");
    }

    static void fail(String reason) {
        System.out.println("COMMUNITYOPEN_FAILED " + reason);
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { fail("identity.args (the gate passes the manifest identity)"); return; }

        // ---- 1: الهوية — اشتقاقان مستقلان يتوافقان ---------------------------
        ck("identity.agrees", SyncServerPack.VC.equals(args[1]) && SyncServerPack.VN.equals(args[0]),
                "the manifest identity == the statement identity (المرجع = المنشور)");

        // القاموس المثبت الحقيقي — المرجع الذي يقاس عليه التكرار
        List<String> installed = Files.readAllLines(Paths.get("assets/ar.txt"), StandardCharsets.UTF_8);
        Set<String> norms = CommunityDict.normSetOf(installed);
        ck("installed.dict", norms.size() > 100000,
                "القاموس المثبت المطبع (المقاس وقت المرحلة 2: 134188 مفتاحاً): " + norms.size());

        // ---- 2: وثيقة الدليل — تُقرأ وتُفحص لا تُؤتى بها ----------------------
        String doc;
        try { doc = new String(Files.readAllBytes(Paths.get(DOC)), StandardCharsets.UTF_8); }
        catch (Exception e) { fail("doc.missing (" + DOC + " unreadable)"); return; }
        ck("doc.guide", doc.contains("دليل المساهم") && doc.contains("كيف تساهم"),
                "الدليل يخدم المساهم: قسم «كيف تساهم» حاضر (متطلب W53)");
        ck("doc.header_mirror", headerMirror(doc),
                "ترويسة §1 مرآة حرفية لـCommunityDict.HEADER بالترتيب (لا انحراف بين الدليل والكود)");
        boolean tags = true;
        for (String t : CommunityDict.ROW_TAGS) tags &= doc.contains(t);
        for (String t : CommunityDict.FILE_TAGS) tags &= doc.contains(t);
        ck("doc.tags", tags, "وسوم الرفض الخمسة عشر حاضرة حرفياً في الدليل");
        boolean pos = true;
        for (String p : CommunityDict.POS) pos &= doc.contains(p);
        ck("doc.pos", pos, "قائمة pos السباعية حاضرة حرفياً");
        boolean lics = doc.contains(CommunityDict.LICENSES[0]) && doc.contains(CommunityDict.LICENSES[1]);
        ck("doc.licenses", lics, "الترخيصان المقبولان حاضران حرفياً");
        ck("doc.channel", doc.contains(Feedback.SUPPORT_EMAIL) && doc.contains(BATCH_SUBJECT),
                "قناة الوصول موثقة: البريد + الموضوع («" + BATCH_SUBJECT + "»)");
        ck("doc.thanks_law", doc.contains("تنازلي بالعدد") && doc.contains("لا يظهرون"),
                "قانون الشكر موثق في الدليل (ترتيب تنازلي بالعدد ثم أبجدي، المرفوضون لا يظهرون)");
        ck("doc.merge_law", doc.contains("neokey-dpack-1") && doc.contains("DictSign"),
                "سلسلة الدمج الموقّع موثقة في الدليل (DictSign → neokey-dpack-1)");

        // ---- 3: صفحة الشكر — تُفحص لا تُؤتى بها ------------------------------
        String page;
        try { page = new String(Files.readAllBytes(Paths.get(PAGE)), StandardCharsets.UTF_8); }
        catch (Exception e) { fail("page.missing (CONTRIBUTORS.md unreadable)"); return; }
        ck("page.header", page.contains(HEADER_LINE),
                "الترويسة الموحدة حرفياً في الصفحة — العقد هو الملف لا وسيلة النقل");
        ck("page.licenses", page.contains(CommunityDict.LICENSES[0]) && page.contains(CommunityDict.LICENSES[1]),
                "الترخيصان في الصفحة");
        ck("page.channel", page.contains(Feedback.SUPPORT_EMAIL) && page.contains(BATCH_SUBJECT),
                "القناة في الصفحة (البريد + الموضوع)");
        ck("page.credit_law", page.contains("المقبولة فقط") && page.contains("لا يظهر")
                        && page.contains("تنازلي بالعدد"),
                "قانون الشكر في الصفحة (المقبولة فقط / تنازلي بالعدد / المرفوض لا يظهر)");
        ck("page.batch_law", page.contains("SHA-256"),
                "قانون المعرف في الصفحة (معرف الدفعة = SHA-256 للبايتات)");
        ck("page.doc_ref", page.contains(DOC), "الصفحة تحيل إلى الدليل");
        ck("page.thanks_title_law", page.contains(THANKS_TITLE),
                "عنوان قسم الشكر المولَّد موثق في الصفحة");
        ck("page.pack_law", page.contains("neokey-dpack-1"),
                "الحزمة الموقعة موثقة في الصفحة");

        // ---- 4: القناة كما تصل — دفعة خارجية نظيفة بأسطرها الحقيقية ----------
        // 9 صفوف، 3 مساهمين (4/3/2 — ترتيب الشكر تنازلي بالعدد ثم أبجدي)، مع
        // أسطر CRLF وفاصلة مقتبسة وسطر فارغ وتشكيل جزئي — قوانين المحلل الحقيقية.
        String[] w = genWords(12, norms);
        StringBuilder csv = new StringBuilder(HEADER_LINE).append('\n');
        csv.append(row(w[0], vocalize(w[0], 0), w[0], "noun", "معنى موثق 1", STANDINS[0], "CC0-1.0")).append('\n');
        csv.append(row(w[1], vocalize(w[1], 1), w[1], "verb", "معنى موثق 2", STANDINS[2], "CC-BY-4.0")).append("\r\n");
        csv.append(row(w[2], vocalize(w[2], 2), w[2], "adj", "\"معنى, موثق\"", STANDINS[1], "CC0-1.0")).append("\r\n");
        csv.append('\n'); // سطر فارغ يُتسامح معه (قانون المحلل الموثق)
        csv.append(row(w[3], vocalize(w[3], 3), w[3], "noun", "معنى موثق 4", STANDINS[2], "CC0-1.0")).append("\r\n");
        csv.append(row(w[4], partial(w[4]), w[4], "adv", "معنى موثق 5", STANDINS[0], "CC0-1.0")).append('\n');
        csv.append(row(w[5], vocalize(w[5], 5), w[5], "proper", "معنى موثق 6", STANDINS[0], "CC-BY-4.0")).append('\n');
        csv.append(row(w[6], vocalize(w[6], 6), w[6], "phrase", "معنى موثق 7", STANDINS[0], "CC0-1.0")).append('\n');
        csv.append(row(w[7], vocalize(w[7], 7), w[7], "noun", "معنى موثق 8", STANDINS[1], "CC0-1.0")).append('\n');
        csv.append(row(w[8], vocalize(w[8], 8), w[8], "particle", "معنى موثق 9", STANDINS[1], "CC-BY-4.0")).append('\n');
        byte[] cleanBytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        CommunityDict.Report clean = CommunityDict.review(cleanBytes, norms);
        int batches = 1;
        ck("intake.accept_all", clean.total == 9 && clean.accepted == 9 && clean.rejected == 0,
                "صفر رفض خاطئ: كل صفوف الدفعة الخارجية النظيفة مقبولة (9/9)");
        agree("clean", CommunityDict.parseCsv(cleanBytes), clean, norms);
        ck("intake.partial_diac", clean.rows.get(4).accepted(),
                "التشكيل الجزئي مقبول — الهيكل هو العقد لا الكمال («كِتاب» شكل، لا «كِتَاب» كمالاً)");
        List<String[]> parsed = CommunityDict.parseCsv(cleanBytes);
        ck("intake.quoted_comma", clean.rows.get(2).accepted()
                        && parsed.get(2)[4].equals("معنى, موثق"),
                "الاقتباس المزدوج يحترم: فاصلة داخل حقل مقتبس لا تقطعه");
        CommunityDict.Report clean2 = CommunityDict.review(cleanBytes, norms);
        ck("intake.determinism", clean2.batchId.equals(clean.batchId)
                        && clean2.contributorsMd().equals(clean.contributorsMd()),
                "نفس البايتات دائماً نفس التقرير (batchId = SHA-256 للبايتات)");

        // ---- 5: صفحة الشكر تتولد بالميكانيكية الموثقة ------------------------
        String thanks = clean.contributorsMd();
        ck("thanks.format", thanks.startsWith(THANKS_TITLE) && thanks.contains(THANKS_TABLE),
                "قسم الشكر يفتح بالعنوان الموثق ويحمل جدول الأعداد");
        Map<String, Integer> contribs = clean.contributors();
        boolean sorted = contribs.size() == 3 && contribs.get("amina") == 4
                && contribs.get("sami") == 3 && contribs.get("kareem") == 2;
        int idx = 0; int[] want = {4, 3, 2};
        String[] order = new String[contribs.size()];
        for (Map.Entry<String, Integer> e : contribs.entrySet()) order[idx++] = e.getKey();
        sorted &= order[0].equals("amina") && order[1].equals("sami") && order[2].equals("kareem")
                && contribs.get(order[0]) == want[0] && contribs.get(order[1]) == want[1]
                && contribs.get(order[2]) == want[2];
        ck("thanks.sorted", sorted, "الترتيب تنازلي بالعدد ثم أبجدي (4/3/2)");

        // الدفعة المختلطة: صف سام من مساهم شريد — المرفوض لا يظهر في الشكر أبداً
        StringBuilder mixed = new StringBuilder(HEADER_LINE).append('\n');
        mixed.append(row(w[9], vocalize(w[9], 9), w[9], "noun", "معنى موثق 10", STANDINS[0], "CC0-1.0")).append('\n');
        mixed.append(row(w[10], vocalize(w[10], 10), w[10], "verb", "معنى موثق 11", STANDINS[2], "CC0-1.0")).append('\n');
        mixed.append(row(w[11], vocalize(w[11], 11), w[11], "noun", "كلام حقير جداً", SABOTEUR, "CC0-1.0")).append('\n');
        byte[] mixedBytes = mixed.toString().getBytes(StandardCharsets.UTF_8);
        CommunityDict.Report mixedRep = CommunityDict.review(mixedBytes, norms);
        batches++;
        ck("thanks.accepted_only", mixedRep.rejected == 1 && !mixedRep.contributorsMd().contains(SABOTEUR)
                        && mixedRep.contributorsMd().contains(STANDINS[0])
                        && mixedRep.contributorsMd().contains(STANDINS[2]),
                "المرفوض لا يظهر في صفحة الشكر أبداً («" + SABOTEUR + "» شريد والشكر يحمل المقبولين فقط)");
        agree("mixed", CommunityDict.parseCsv(mixedBytes), mixedRep, norms);

        // ---- 6: الدمج الموقّع — من الدفعة إلى الحزمة عبر DictSign المُشحن ------
        DictSign.Dpack dp = DictSign.publish(cleanBytes, clean);
        ck("merge.publish", dp.accepted == clean.accepted && dp.batchId.equals(clean.batchId)
                        && dp.words.size() == 9,
                "publish يحمل معرف التقرير وصفوف القبول بترتيبها (9)");
        boolean orderOk = dp.words.size() == 9;
        int wi = 0;
        for (CommunityDict.Row r : clean.rows) {
            if (!r.accepted()) continue;
            if (wi < dp.words.size()) {
                DictSign.Word dw = dp.words.get(wi++);
                orderOk &= dw.word.equals(r.word) && dw.diacritized.equals(r.diacritized)
                        && dw.lemma.equals(r.lemma) && dw.pos.equals(r.pos);
            }
        }
        ck("merge.order", orderOk, "الصفوف تدخل بترتيب القبول نفسه — التكرار الكامل يعيد البايتات نفسها");
        ck("merge.statement", CommunityDict.sha256Hex(dp.statement.getBytes(StandardCharsets.UTF_8)).equals(dp.reportSha),
                "بيان المراجعة مرجع توقيع الإنسان — دبوسه في الترويسة");
        DictSign.Pack p = DictSign.load(dp.bytes, dp.statement, norms);
        ck("merge.load", p != null && p.words.size() == 9 && p.batchId.equals(clean.batchId),
                "التحميل بالبيان يمر بايت-لطرفاً (لا استبدال كلمة نظيفة بكلمة أخرى)");
        DictSign.Pack p2 = DictSign.load(dp.bytes, null, norms);
        ck("merge.load_device", p2 != null && p2.words.size() == 9,
                "مسار الجهاز: بلا بيان — أصل الإصدار محمي بدبوسه الذهبي وإعادة الفحص دفاع في العمق");

        // سلبيات الدمج — كل وسم بحرفه
        byte[] tampered = dp.bytes.clone();
        int wiLast = lastW(tampered);
        tampered[wiLast] = (byte) (tampered[wiLast] ^ 0x04);
        neg("merge.tamper", DictSign.TAG_SIG, loadTag(tampered, dp.statement, norms));
        StringBuilder allBad = new StringBuilder(HEADER_LINE).append('\n')
                .append("latin,latin,latin,noun,معنى موثق,amina,CC0-1.0\n");
        byte[] allBadBytes = allBad.toString().getBytes(StandardCharsets.UTF_8);
        CommunityDict.Report allBadRep = CommunityDict.review(allBadBytes, norms);
        batches++;
        String emptyTag = "NO_THROW";
        try { DictSign.publish(allBadBytes, allBadRep); }
        catch (IllegalArgumentException e) { emptyTag = e.getMessage(); }
        neg("merge.empty_pack", DictSign.TAG_EMPTY, emptyTag);
        String mismatchTag = "NO_THROW";
        try { DictSign.publish(mixedBytes, clean); }
        catch (IllegalArgumentException e) { mismatchTag = e.getMessage(); }
        neg("merge.mismatch", DictSign.TAG_CSV, mismatchTag);

        // ---- 7: سلبيات القناة — مواد سيئة مولَّدة طازجاً تعضّ بأوسامها --------
        StringBuilder negCsv = new StringBuilder(HEADER_LINE).append('\n');
        negCsv.append(row(w[0], vocalize(w[0], 0), w[0], "noun", "x", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[1], vocalize(w[1], 1), w[1], "verb", "معنى موثق", "ab", "CC0-1.0")).append('\n');
        negCsv.append(row("في", "فِي", "في", "particle", "حرف جر", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[2], vocalize(w[2], 2), w[2], "noun", "معنى موثق", STANDINS[1], "CC0-1.0")).append('\n');
        negCsv.append(row(w[2], vocalize(w[2], 2), w[2], "noun", "معنى موثق", STANDINS[1], "CC0-1.0")).append('\n');
        negCsv.append(row("كِتاب", "كِتَاب", "كتاب", "noun", "معنى موثق", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row("latin", "latin", "latin", "noun", "معنى موثق", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row("في", "فِي", "في", "particle", "كلام حقير جداً", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[3], w[3], w[3], "noun", "معنى موثق", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[4], vocalize(w[4], 4), w[4], "interj", "معنى موثق", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[5], vocalize(w[5], 5), "", "noun", "معنى موثق", STANDINS[0], "CC0-1.0")).append('\n');
        negCsv.append(row(w[6], vocalize(w[6], 6), w[6], "noun", "معنى موثق", STANDINS[0], "MIT")).append('\n');
        byte[] negBytes = negCsv.toString().getBytes(StandardCharsets.UTF_8);
        CommunityDict.Report negRep = CommunityDict.review(negBytes, norms);
        batches++;
        List<String[]> negFields = CommunityDict.parseCsv(negBytes);
        neg("neg.gloss_intake", CommunityDict.T_BAD_GLOSS, intakeTag(negFields, negRep, 0, norms));
        neg("neg.alias_intake", CommunityDict.T_BAD_CONTRIB, intakeTag(negFields, negRep, 1, norms));
        neg("neg.dup_installed", CommunityDict.T_DUPLICATE, tagOf(negRep, 2));
        neg("neg.dup_batch", CommunityDict.T_DUPLICATE, tagOf(negRep, 4));
        neg("neg.tashkeel", CommunityDict.T_TASHKEEL, tagOf(negRep, 5));
        neg("neg.not_arabic", CommunityDict.T_NOT_ARABIC, tagOf(negRep, 6));
        neg("neg.forbidden_first", CommunityDict.T_FORBIDDEN, tagOf(negRep, 7));
        neg("neg.no_diac", CommunityDict.T_NO_DIAC, tagOf(negRep, 8));
        neg("neg.pos", CommunityDict.T_BAD_POS, tagOf(negRep, 9));
        neg("neg.lemma", CommunityDict.T_BAD_LEMMA, tagOf(negRep, 10));
        neg("neg.license", CommunityDict.T_BAD_LICENSE, tagOf(negRep, 11));
        agree("negatives", negFields, negRep, norms);
        ck("negatives.accept_one", negRep.rows.get(3).accepted() && negRep.accepted == 1,
                "الصف السليم بين السلبيات يُقبل (الرفض لأسبابه لا بالجملة)");

        // فئتا الملف — الدفعة المكسورة لا تُراجع صفًا صفًا
        byte[] bomBody = (HEADER_LINE + "\n"
                + row(w[7], vocalize(w[7], 7), w[7], "noun", "معنى موثق", STANDINS[0], "CC0-1.0") + "\n"
                + row(w[8], vocalize(w[8], 8), w[8], "noun", "معنى موثق", STANDINS[1], "CC0-1.0") + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[bomBody.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(bomBody, 0, bom, 3, bomBody.length);
        CommunityDict.Report bomRep = CommunityDict.review(bom, norms);
        batches++;
        neg("neg.bom", CommunityDict.T_BAD_ENCODING, batchTag(bomRep, CommunityDict.T_BAD_ENCODING));
        byte[] badHeader = ("word,diacritized,lemma,pos,gloss,contributor\n"
                + row(w[7], vocalize(w[7], 7), w[7], "noun", "معنى موثق", STANDINS[0]) + "\n"
                + row(w[8], vocalize(w[8], 8), w[8], "noun", "معنى موثق", STANDINS[1]) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        CommunityDict.Report hdrRep = CommunityDict.review(badHeader, norms);
        batches++;
        neg("neg.header", CommunityDict.T_BAD_HEADER, batchTag(hdrRep, CommunityDict.T_BAD_HEADER));

        // ---- 8: الشفاء — القناة الخضراء تعاد بايت-لطرفاً ----------------------
        CommunityDict.Report healRep = CommunityDict.review(cleanBytes, norms);
        DictSign.Dpack healDp = DictSign.publish(cleanBytes, healRep);
        boolean heal = healRep.batchId.equals(clean.batchId)
                && healRep.contributorsMd().equals(clean.contributorsMd())
                && Arrays.equals(healDp.bytes, dp.bytes)
                && healDp.sig.equals(dp.sig);
        ck("heal.green", heal,
                "القناة الخضراء تعاد بايت-لطرفاً بعد كل السلبيات (التقرير والشكر والحزمة والتوقيع)");

        // ---- الحكم --------------------------------------------------------------
        if (negPass < negTotal) { fail("negatives=" + negPass + "/" + negTotal); return; }
        if (reg > 0) {
            System.out.println("COMMUNITYOPEN_FAILED regressions=" + reg + "/" + checks
                    + " (see COMMUNITYOPEN regression lines above)");
            System.exit(1);
            return;
        }
        System.out.println("COMMUNITYOPEN_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " words=" + clean.accepted + " contribs=" + clean.contributors().size()
                + " batches=" + batches + " pack=" + dp.accepted
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);
    }

    // ------------------------------------------------------- مدقق القبول المبدئي

    /**
     * قواعد الدليل §1/§2 بترتيبها الحرفي — اشتقاق ثانٍ مستقل عن الحكم المُشحن.
     * الاتفاق مع CommunityDict.judge على كل صف = الدليل مرآة أمينة للكود؛ أي
     * انحراف بين الدليل والكود يظهر خلافاً صارخاً على الصف نفسه. قائمة المحظور
     * محروقة في الكود (توثيق الدليل نفسه) فتُستعار كما هي، والترتيب هو قانون
     * الدليل («المحظور قبل المكرر» قرار أمني مقصود).
     */
    static String intake(String[] f, Set<String> installedNorms, Set<String> seen) {
        if (f == null || f.length != 7) return CommunityDict.T_ROW_FIELDS;
        String word = f[0];
        if (word == null || word.isEmpty()) return CommunityDict.T_EMPTY_WORD;
        if (CommunityDict.hasTashkeel(word)) return CommunityDict.T_TASHKEEL;
        if (!CommunityDict.isArabicWord(word)) return CommunityDict.T_NOT_ARABIC;
        String d = f[1] == null ? "" : f[1];
        if (CommunityDict.stripDiac(d).equals(word)) {
            if (d.equals(word) || !CommunityDict.hasTashkeel(d)) return CommunityDict.T_NO_DIAC;
        } else {
            return CommunityDict.T_BAD_DIAC;
        }
        if (CommunityDict.isForbidden(f[2]) || CommunityDict.isForbidden(f[4])
                || CommunityDict.isForbidden(f[0]) || CommunityDict.isForbidden(f[1]))
            return CommunityDict.T_FORBIDDEN; // المحظور قبل المكرر — قرار الدليل الأمني
        String nw = CommunityDict.normAr(word);
        if (seen.contains(nw)) return CommunityDict.T_DUPLICATE;
        if (installedNorms != null && installedNorms.contains(nw)) return CommunityDict.T_DUPLICATE;
        if (!inList(CommunityDict.POS, f[3])) return CommunityDict.T_BAD_POS;
        if (f[2] == null || f[2].isEmpty() || !CommunityDict.isArabicWord(CommunityDict.stripDiac(f[2])))
            return CommunityDict.T_BAD_LEMMA;
        if (f[4] == null || f[4].length() < INTAKE_GLOSS_MIN) return CommunityDict.T_BAD_GLOSS;
        if (f[5] == null || !f[5].matches(INTAKE_ALIAS)) return CommunityDict.T_BAD_CONTRIB;
        if (!inList(CommunityDict.LICENSES, f[6])) return CommunityDict.T_BAD_LICENSE;
        return null;
    }

    /** قانون الاتفاق: مدقق الدليل والحكم المُشحن يوافقان على كل صف من الدفعة. */
    static void agree(String name, List<String[]> fields, CommunityDict.Report rep, Set<String> norms) {
        Set<String> seen = new HashSet<>();
        String detail = "";
        boolean ok = fields.size() == rep.rows.size();
        if (ok) {
            for (int i = 0; i < fields.size(); i++) {
                String it = intake(fields.get(i), norms, seen);
                String rt = rep.rows.get(i).tag;
                boolean same = (it == null) ? rt == null : it.equals(rt);
                if (!same) { ok = false; detail = "row " + (i + 2) + ": intake="
                        + (it == null ? "ACCEPT" : it) + " review=" + (rt == null ? "ACCEPT" : rt); break; }
                if (rep.rows.get(i).accepted()) seen.add(CommunityDict.normAr(fields.get(i)[0]));
            }
        } else detail = "field count " + fields.size() + " != rows " + rep.rows.size();
        ck("agreement." + name, ok,
                "مدقق الدليل والحكم المُشحن يوافقان على كل صف (لا انحراف بين الوثيقة والتطبيق)" + detail);
    }

    /** وسم مدقق القبول المبدئي لصف السلبية (الشاهد يستهدف الاشتقاق الثاني). */
    static String intakeTag(List<String[]> fields, CommunityDict.Report rep, int i, Set<String> norms) {
        Set<String> seen = new HashSet<>();
        String got = "OUT_OF_RANGE";
        for (int k = 0; k <= i && k < fields.size(); k++) {
            got = intake(fields.get(k), norms, seen);
            if (rep.rows.get(k).accepted()) seen.add(CommunityDict.normAr(fields.get(k)[0]));
        }
        return got == null ? "ACCEPT" : got;
    }

    static String tagOf(CommunityDict.Report rep, int i) {
        if (i >= rep.rows.size()) return "OUT_OF_RANGE";
        String t = rep.rows.get(i).tag;
        return t == null ? "ACCEPT" : t;
    }

    /** وسم دفعة مرفوضة كاملة: كل الصفوف تحمل الوسم نفسه، وإلا فالخلط يُكشف. */
    static String batchTag(CommunityDict.Report rep, String tag) {
        for (CommunityDict.Row r : rep.rows) {
            String t = r.tag == null ? "ACCEPT" : r.tag;
            if (!t.equals(tag)) return "MIXED(" + t + ")";
        }
        return rep.rows.isEmpty() ? "NO_ROWS" : tag;
    }

    static String loadTag(byte[] dpack, String statement, Set<String> norms) {
        try { DictSign.load(dpack, statement, norms); return "NO_THROW"; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    static boolean inList(String[] list, String v) {
        for (String s : list) if (s.equals(v)) return true;
        return false;
    }

    // ------------------------------------------------------------- مرآة الدليل

    /** ترويسة §1 في الدليل == HEADER المشحون بالترتيب (جدول الأعمدة المرقمة). */
    static boolean headerMirror(String doc) {
        List<String> names = new ArrayList<>();
        for (String line : doc.split("\n")) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("^\\| `([a-z]+)` \\| ([0-9]+) \\|").matcher(line.trim());
            if (m.find()) names.add(m.group(1));
        }
        if (names.size() != CommunityDict.HEADER.length) return false;
        for (int i = 0; i < CommunityDict.HEADER.length; i++)
            if (!names.get(i).equals(CommunityDict.HEADER[i])) return false;
        return true;
    }

    // ------------------------------------------------------- مولدات المواد البديلة

    static final String[] SEGS = {"زم", "هر", "دف", "سق", "طل", "عن", "غر", "مض", "فل", "قص"};

    /** كلمات بديلة عربية سليمة خارج القاموس المثبت ومطبّعة-متميزة بينها. */
    static String[] genWords(int n, Set<String> norms) {
        String[] out = new String[n];
        Set<String> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String b = genWord(i);
            while (used.contains(CommunityDict.normAr(b)) || norms.contains(CommunityDict.normAr(b)))
                b = b + "ا";
            used.add(CommunityDict.normAr(b));
            out[i] = b;
        }
        return out;
    }

    static String genWord(int i) {
        StringBuilder b = new StringBuilder();
        b.append(SEGS[i % SEGS.length]);
        b.append(SEGS[(i / SEGS.length + 3) % SEGS.length]);
        b.append(i % 2 == 0 ? "اب" : "ان");
        int extra = i / (SEGS.length * 2);
        for (int k = 0; k < extra && k < 5; k++) b.append("ا");
        return b.toString();
    }

    static final char[] MARKS = {'َ', 'ُ', 'ِ'};

    /** تشكيل كامل (علامة بعد كل حرف زوجي) — الهيكل يعود حرفاً-حرفاً. */
    static String vocalize(String w, int i) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < w.length(); k++) {
            b.append(w.charAt(k));
            if (k % 2 == 0) b.append(MARKS[(i + k) % 3]);
        }
        return b.toString();
    }

    /** تشكيل جزئي (علامة على الحرف الأول فقط) — «كِتاب» شكل لا كمالاً. */
    static String partial(String w) {
        return w.substring(0, 1) + MARKS[0] + w.substring(1);
    }

    static String row(String w, String d, String l, String p, String g, String c, String lic) {
        return w + "," + d + "," + l + "," + p + "," + g + "," + c + "," + lic;
    }

    static String row(String w, String d, String l, String p, String g, String c) {
        return w + "," + d + "," + l + "," + p + "," + g + "," + c;
    }

    /** آخر بايت 'w' في الحزمة = بداية آخر صف كلمات (سطر sig بلا 'w'). */
    static int lastW(byte[] b) {
        for (int i = b.length - 1; i >= 0; i--) if (b[i] == 'w') return i;
        return -1;
    }

    private CommunityOpen() { throw new UnsupportedOperationException(); }
}
