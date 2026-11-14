package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;

/**
 * بطارية W70 — AACBoardTest: معيار الخروج حرفاً — لقطة أي عبارة إلى
 * أي حقل عبر مسار الإدراج القائم (خطة إدراج نقية تُستهلك من المسار
 * الحي)، وكل عبارة افتراضية تمرّ عبر القاموس المجتمعي نفسه للتخصيص
 * (القصاصات الموسومة «aac:الفئة» تغلب الافتراضية)، الفئات الأربع
 * ثابتة موثقة وفشل مغلق، الشبكة حتمية (نفس المدخلات = نفس اللوحة
 * نفس الترتيب)، وأولوية المس الكبيرة بأقصى 3 لكل صف. نقي JVM.
 */
public class AACBoardTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    /** محاكاة وسم قصاصات — نقية، بترتيب الإدراج. */
    static AACBoard.Lookup lookup(final String[][] tags) {
        return new AACBoard.Lookup() {
            public List<String[]> tagged() {
                List<String[]> out = new ArrayList<String[]>();
                for (String[] t : tags) out.add(t);
                return out;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        // ---- الفئات: ثابتة موثقة وفشل مغلق -------------------------------------
        List<String> cats = AACBoard.categories();
        check("cats: الفئات الأربع الثابتة بالترتيب الموثق (احتياجات/مشاعر/تحيات/طوارئ)",
                cats.size() == 4 && cats.get(0).equals("احتياجات")
                        && cats.get(1).equals("مشاعر") && cats.get(2).equals("تحيات")
                        && cats.get(3).equals("طوارئ"));
        check("cats: فئة خارج الثابتة = null موثق لا لوحة فاضية (AAC_NO_SUCH_CATEGORY)",
                AACBoard.snapshotFor("فئة منقولة", null) == null
                        && AACBoard.snapshotFor(null, null) == null);
        boolean defCounts = true;
        for (String c : cats) if (AACBoard.defaultsCount(c) != 8) defCounts = false;
        check("cats: كل فئة افتراضية بثماني عبارات عربية تحت حد العرض", defCounts);

        // ---- الحتمية: نفس المدخلات = نفس اللوحة نفس الترتيب ----------------------
        AACBoard.Snapshot s1 = AACBoard.snapshotFor("طوارئ", null);
        AACBoard.Snapshot s2 = AACBoard.snapshotFor("طوارئ", null);
        boolean det = s1.rows.size() == s2.rows.size();
        if (det) for (int i = 0; i < s1.rows.size(); i++) {
            List<String> a = s1.rows.get(i).phrases, b = s2.rows.get(i).phrases;
            if (a.size() != b.size()) { det = false; break; }
            for (int j = 0; j < a.size(); j++) if (!a.get(j).equals(b.get(j))) { det = false; break; }
        }
        check("det: نفس الفئة والمستودع = نفس الصفوف والعبارات حرفاً", det);
        boolean grid = true;
        for (String c : cats) {
            AACBoard.Snapshot s = AACBoard.snapshotFor(c, null);
            for (AACBoard.Row r : s.rows) if (r.phrases.size() > 3 || r.phrases.isEmpty()) grid = false;
            if (s.rows.size() < 3) grid = false;   // 8 عبارات/3 لكل صف = 3 صفوف
        }
        check("grid: شبكة المس الكبيرة — أقصى 3 لكل صف ولا صف فاضي في الفئات الأربع", grid);

        // ---- التخصيص: القاموس المجتمعي نفسه يغلب الافتراضية ----------------------
        AACBoard.Lookup lk = lookup(new String[][]{
            {"aac:طوارئ", "أحتاج إسعافاً فوراً"},
            {"aac:طوارئ", "من فضلكوا ادعوا الطبيب"},
            {"aac:مشاعر", "قلبي منفرج"},
            {"قصاصة عادية", "بلا وسم يخص AAC"},
        });
        AACBoard.Snapshot su = AACBoard.snapshotFor("طوارئ", lk);
        boolean custom = su.customized == 2
                && su.rows.get(0).phrases.get(0).equals("أحتاج إسعافاً فوراً")
                && su.rows.get(0).phrases.get(1).equals("من فضلكوا ادعوا الطبيب");
        check("custom: قصاصتا «aac:طوارئ» تغلبان الافتراضية بأكملها بالترتيب", custom);
        check("custom: وسم فئة أخرى لا يتسرب إلى لوحة طوارئ",
                !su.rows.get(su.rows.size() - 1).phrases.contains("قلبي منفرج"));
        check("custom: ملاحظة التخصيص معلومة في اللوحة (القاموس المجتمعي نفسه)",
                su.notes.size() == 1 && su.notes.get(0).contains("2"));
        AACBoard.Snapshot sf = AACBoard.snapshotFor("طوارئ", lookup(new String[][]{
            {"aac:طوارئ", "english only"}, {"aac:طوارئ", "   "}, {"aac:طوارئ", "ج"}}));
        check("custom: التخصيص غير العربي يُعلن ولا يُدرج صامتاً (المقبول: «ج» فقط)",
                sf.customized == 1 && sf.notes.size() == 2
                        && sf.notes.get(0).contains("1") && sf.notes.get(1).contains(AACBoard.NON_ARABIC));
        AACBoard.Snapshot sOver = AACBoard.snapshotFor("مشاعر", lookup(new String[][]{
            {"aac:مشاعر", "واحد"}, {"aac:مشاعر", "اثنان"}, {"aac:مشاعر", "ثلاثة"},
            {"aac:مشاعر", "أربعة"}, {"aac:مشاعر", "خمسة"}, {"aac:مشاعر", "ستة"},
            {"aac:مشاعر", "سبعة"}, {"aac:مشاعر", "ثمانية"}, {"aac:مشاعر", "تسعة"},
            {"aac:مشاعر", "عشرة"}, {"aac:مشاعر", "أحد عشر"}, {"aac:مشاعر", "اثنا عشر"},
            {"aac:مشاعر", "ثلاثة عشر"} }));
        boolean capped = true;
        int cnt = 0;
        for (AACBoard.Row r : sOver.rows) cnt += r.phrases.size();
        if (cnt > AACBoard.PHRASES_MAX) capped = false;
        check("custom: سقف اللوحة 12 عبارة مفروض مهما كثر التخصيص (المقيس " + cnt + ")", capped);

        // ---- الإدراج: خطة نقية عبر المسار القائم، فشل مغلق ------------------------
        AACBoard.Insert i1 = AACBoard.insertPlan("  أحتاج ماء  ");
        check("insert: نص سليم يعود مخلصاً للمسار القائم (قصّ الأطراف فقط)",
                i1.ok && i1.phrase.equals("أحتاج ماء") && i1.refusal == null);
        AACBoard.Insert i2 = AACBoard.insertPlan("   ");
        AACBoard.Insert i3 = AACBoard.insertPlan(null);
        check("insert: العبارة الفاضية/null ترفض موثقاً (AAC_EMPTY_PHRASE)",
                !i2.ok && i2.refusal.equals(AACBoard.EMPTY_PHRASE)
                        && !i3.ok && i3.refusal.equals(AACBoard.EMPTY_PHRASE));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) big.append("طويل ");
        AACBoard.Insert i4 = AACBoard.insertPlan(big.toString().trim());
        check("insert: فوق الحد 120 يرفض موثقاً (AAC_PHRASE_TOO_LONG)",
                !i4.ok && i4.refusal.equals(AACBoard.PHRASE_TOO_LONG));

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
