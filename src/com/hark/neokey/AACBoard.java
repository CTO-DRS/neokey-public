package com.hark.neokey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W70 (roadmap v2): لوحة التواصل AAC — «صوت لمن لا صوت له». لوحة عبارات
 * عربية سريعة بالفئات (احتياجات / مشاعر / تحيات / طوارئ) مبنية على
 * اختصارات الحافظة القائمة (W48): كل عبارة افتراضية تمرّ عبر القاموس
 * المجتمعي نفسه للتخصيص — القصاصة المخصصة تغلب الافتراضية بلا أي
 * ثنائية أو طبقة إضافية. نقي JVM: لا شبكة، لا نموذج، لا حالة كائن —
 * دالة نقية على المدخلات.
 *
 * القوانين:
 *   1. الفئات الأربع ثابتة وموثقة — أي فئة خارجها رفض مغلق موثق
 *      (AAC_NO_SUCH_CATEGORY) لا لوحة فاضية.
 *   2. التخصيص من القاموس المجتمعي فقط — عبارة مخصصة (قصاصة تخص
 *      الفئة عبر وسم aac:الفئة) تغلب الافتراضية بأكملها، واللوحة
 *      الافتراضية تظهر حين لا تخصيص — لا إنشاء مخصص صامت.
 *   3. الحتمية — نفس المدخلات = نفس اللوحة نفس الترتيب دائماً
 *      (لا زمن، لا عشوائية، لا حالة)؛ اختبار الحتمية عضّ أي كسر.
 *   4. أولوية المس الكبيرة — اللوحة تُبنى صفوفاً حتمية (أبجدية
 *      ترتيب ثابتة) وأقصى عبارات لكل صف 3 — أي كسر الشبكة يرفض.
 *   5. الإدراج عبر المسار القائم فقط — الصنف النقي يعيد نص العبارة،
 *      والمسار الحي (حافظة/حقل) يستلمه بلا طبقة ثانية؛ عبارة فاضية
 *      أو فوق الحد ترفض موثقاً (AAC_EMPTY_PHRASE / AAC_PHRASE_TOO_LONG).
 *   6. عربي خالص — نصوص الفئات والعبارة الافتراضية عربية، وكل نص
 *      مخصص بلا حرف عربي يُعلن غير عربي لا يُدرج صامتاً.
 */
public final class AACBoard {

    /** أقصى حروف للعبارة (حد الحافظة الملائم للمس — أعلى من ذلك رفض موثق). */
    public static final int PHRASE_MAX = 120;
    /** أقصى عدد عبارات لكل فئة في اللوحة. */
    public static final int PHRASES_MAX = 12;
    /** أقصى عبارات لكل صف — شبكة المس الكبيرة. */
    static final int ROW_MAX = 3;
    /** وسم التخصيص في القاموس المجتمعي (القصاصات): «aac:الفئة». */
    public static final String TAG_PREFIX = "aac:";

    public static final String NO_SUCH_CATEGORY = "AAC_NO_SUCH_CATEGORY";
    public static final String EMPTY_PHRASE = "AAC_EMPTY_PHRASE";
    public static final String PHRASE_TOO_LONG = "AAC_PHRASE_TOO_LONG";
    /** إعلان النص غير العربي — يعيش في الصنف النقي لا في أصناف shell (قانون الاقتطاع). */
    public static final String NON_ARABIC = "لوحة التواصل عربية خالصة — التخصيص بلا حرف عربي لا يُدرج";

    /** الفئات الأربع الثابتة — أسماء عربية موثقة لا تتغير. */
    public static final String CAT_NEED = "احتياجات";
    public static final String CAT_FEEL = "مشاعر";
    public static final String CAT_GREET = "تحيات";
    public static final String CAT_URGENT = "طوارئ";

    static final String[] CATEGORIES = {CAT_NEED, CAT_FEEL, CAT_GREET, CAT_URGENT};

    /** العبارات الافتراضية — عربية خالصة، كل واحدة تحت الحد، ترتيب ثابت. */
    static final Map<String, String[]> DEFAULTS = new LinkedHashMap<String, String[]>();
    static {
        DEFAULTS.put(CAT_NEED, new String[]{
            "أحتاج ماء", "أحتاج مساعدة", "أنا جائع", "أنا عطشان",
            "أحتاج الحمام", "أريد الجلوس", "أحتاج دوائي", "أريد النوم"});
        DEFAULTS.put(CAT_FEEL, new String[]{
            "أنا بخير", "أنا متعب", "أنا حزين", "أنا سعيد",
            "أشعر بألم", "أنا قلق", "أنا محبط", "أشعر بالبرد"});
        DEFAULTS.put(CAT_GREET, new String[]{
            "السلام عليكم", "صباح الخير", "مساء الخير", "مرحبا",
            "شكراً لك", "من فضلك", "إلى اللقاء", "أهلاً بك"});
        DEFAULTS.put(CAT_URGENT, new String[]{
            "إسعاف الآن", "الوضع طارئ", "أحتاج طبيباً", "خطر قريب",
            "توقف من فضلك", "اتصل بأهلي", "ألم شديد", "لا أستطيع التنفس"});
    }

    /** واجهة رقيقة للقاموس المجتمعي (القصاصات الموسومة) — نقية قابلة للمحاكاة. */
    public interface Lookup {
        /** يعيد كل وسم قصاصة في المستودع (اسم الوسم، نص القصاصة) بترتيب الإدراج. */
        List<String[]> tagged();
    }

    /** صف اللوحة — حتمي معلوم الأعضاء. */
    public static final class Row {
        public final int index;
        public final List<String> phrases = new ArrayList<String>();
        Row(int index) { this.index = index; }
    }

    /** لوحة فئة — فئة موثقة وصفوف شبكة المس الكبيرة وحتمية الترتيب. */
    public static final class Snapshot {
        public final String category;
        public final List<Row> rows = new ArrayList<Row>();
        public final int customized;                 // كم عبارة جاءت من التخصيص
        public final List<String> notes = new ArrayList<String>();
        Snapshot(String category, int customized) { this.category = category; this.customized = customized; }
    }

    /** نتيجة طلب الإدراج — النص أو رفض موثق، بلا طبقة ثانية على المسار الحي. */
    public static final class Insert {
        public final boolean ok;
        public final String phrase;                  // النص حين تُقبل
        public final String refusal;                 // الرفض الموثق حين تفشل
        Insert(boolean ok, String phrase, String refusal) { this.ok = ok; this.phrase = phrase; this.refusal = refusal; }
    }

    /** العبارة الفعّالة للفئة: التخصيص (بالترتيب) ثم الافتراضية حتى الحد. */
    public static Snapshot snapshotFor(String category, Lookup lookup) {
        String[] defs = DEFAULTS.get(category);
        if (defs == null) return null;
        // تخصيصات القاموس المجتمعي — وسم «aac:الفئة» في نص القصاصة أو عنوانها
        List<String> custom = new ArrayList<String>();
        int nonArabic = 0;
        if (lookup != null) {
            String want = TAG_PREFIX + category;
            for (String[] t : lookup.tagged()) {
                if (t.length < 2 || t[0] == null || t[1] == null) continue;
                String tag = t[0].trim();
                if (!tag.equals(want) && !tag.equals(want + " فارغ") && !tag.startsWith(want + " ")) continue;
                String text = t[1].trim();
                if (text.isEmpty()) { continue; }
                if (text.length() > PHRASE_MAX) continue;
                if (!hasArabic(text)) { nonArabic++; continue; }
                custom.add(text);
                if (custom.size() >= PHRASES_MAX) break;
            }
        }
        List<String> all = new ArrayList<String>(custom);
        int taken = custom.size();
        for (String d : defs) {
            if (all.size() >= PHRASES_MAX) break;
            if (!all.contains(d)) all.add(d);
        }
        while (all.size() > PHRASES_MAX) all.remove(all.size() - 1);
        Snapshot s = new Snapshot(category, taken);
        Row cur = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % ROW_MAX == 0) { cur = new Row(s.rows.size()); s.rows.add(cur); }
            cur.phrases.add(all.get(i));
        }
        if (taken > 0) s.notes.add("التخصيص من القاموس المجتمعي: " + taken + " عبارة تغلب الافتراضية");
        if (nonArabic > 0) s.notes.add(NON_ARABIC + " (" + nonArabic + ")");
        return s;
    }

    /** خطة الإدراج — النص عبر المسار القائم أو رفض موثق؛ لا طبقة ثانية. */
    public static Insert insertPlan(String phrase) {
        String p = phrase == null ? "" : phrase.trim();
        if (p.isEmpty()) return new Insert(false, null, EMPTY_PHRASE);
        if (p.length() > PHRASE_MAX) return new Insert(false, null, PHRASE_TOO_LONG);
        return new Insert(true, p, null);
    }

    /** أسماء الفئات الثابتة (الترتيب الموثق). */
    public static List<String> categories() {
        List<String> out = new ArrayList<String>();
        for (String c : CATEGORIES) out.add(c);
        return out;
    }

    /** عدد العبارات الافتراضية لفئة — للبوابة والبطارية. */
    public static int defaultsCount(String category) {
        String[] d = DEFAULTS.get(category);
        return d == null ? -1 : d.length;
    }

    /** هل يحمل النص حرفاً عربياً؟ (نطاق الحروف العربية الأساسي + المدود). */
    static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u0621' && c <= '\u064A') || (c >= '\u0660' && c <= '\u066F')) return true;
        }
        return false;
    }

    private AACBoard() { }
}
