package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * جسر الحالة — المرحلة 2، بند A2 (الأسبوع 2): «تجميع الحالة من المخازن الحقيقية
 * (Theme/ClipRepo/Dictionary/Exceptions/Prefs) وتفكيكها إليها عند الاستعادة —
 * السجلات الخمسة تصبح بيانات حية لا أمثلة».
 *
 * نقي JVM: صفر android.* — المخازن نفسها (sqlite/ملفات/تفضيلات) تقرأها الواجهة
 * وتمرر قيماً عادية، وهذه الوحدة تبني منها سجلات {@link SyncVault.Rec} المستقرة،
 * والعكس: خطة استعادة حتمية تُكتب في المخازن. القرارات كلها هنا تُقاس في
 * SyncClientTest وSyncClientGate.
 *
 * الهوية المستقرة لكل سجل (id) تُشتق من المحتوى لا من الترتيب، فنفس القيمة على
 * جهازين = نفس id — وهذا ما يجعل الدمج الحتمي صحيحاً عبر الأجهزة:
 *   theme     : "theme:current"            (سجل واحد — السمة الحالية)
 *   clip_pin  : "pin:<b64url(sha16)>"      (النص الكامل، key = الموضع 0000..)
 *   dict_word : "dw:<lang>:<b64url(sha16(norm))>"  (إملاء الهمزات عائلة واحدة)
 *   shortcut  : "sc:<b64url(sha16(key))>"  (اختصار نصي: مفتاح → توسعة)
 *   phrase    : "ph:<b64url(sha16(text))>" (عبارة سريعة للمستخدم)
 *   setting   : "st:<prefkey>"             (من قائمة بيضاء صريحة فقط)
 *
 * ما لا يُزامن أبداً (فشل مغلق): مفتاح الذكاء الاصطناعي (ai_key)، حالة بوابة
 * الشبكة وسطور التدقيق (net_*)، أسرار الجلسة (sync_*)، الحافظة المؤقتة نفسها،
 * سجل الأعطال، عدادات الإحصاء المحلية، صورة الخلفية، ومكتبة السمات (تُزامن لاحقاً
 * كسجلات سمات كاملة بعد قياس حصتها من الحصة).
 */
public final class SyncState {

    // ---- السقوف (نفس أسقف المخازن — تُقاس في الاختبارات) ------------------------
    static final int CAP_PINS = 100;          // ClipRepo.MAX_PINS
    static final int CAP_PHRASES = 100;
    static final int CAP_SHORTCUTS = 100;
    static final int CAP_DICT_WORDS = 3000;   // يبقى JSON ضمن الحصة المجانية 1MB
    static final int CAP_SETTINGS = 120;

    /** مفاتيح الإعدادات القابلة للمزامنة — قائمة بيضاء صريحة (أي شيء آخر يُرفض).
     *  السمة الحالية مستبعدة عمداً — تُزامن كسجل theme:current وحده (لا تكرار
     *  لمصدر حقيقة واحد تحت مفتاحين). */
    public static final String[] SETTINGS_WHITELIST = {
            "suggest", "learn", "dblspace", "autocorrect", "ar_punct", "glide",
            "haptics", "sound", "popup", "numrow", "numpad", "pressglow", "anims",
            "kblayout", "hpct", "wpct", "radius", "keygap", "keysize", "keyshape",
            "swipe", "digits", "onehand", "onehandw", "vib", "vibms", "vibamp", "lpdelay",
            "autotheme", "automode", "day_from", "night_from", "day_theme", "night_theme",
            "backdrop", "contrast", "contrast_mode", "bigkeys", "reducemotion", "cues",
            "ui_en", "incognito", "clip_max", "clip_days",
    };

    static boolean syncable(String key) {
        for (String k : SETTINGS_WHITELIST) if (k.equals(key)) return true;
        return false;
    }

    // ---- مدخلات التجميع (قيم عادية تقرأها الواجهة من المخازن) -------------------

    /** لقطة المخازن كما تقرأها الواجهة — كل الحقول قابلة للفراغ (فارغ = لا سجلات). */
    public static final class Snapshot {
        public int themeIdx = -1;                                  // pref "theme"
        public final Map<String, String> dictWords = new LinkedHashMap<>(); // "word\tlang" → لا؛ word → lang
        public final List<String> exceptionWords = new ArrayList<>();       // pref "nocorrect"
        public final List<String> phrases = new ArrayList<>();              // pref "phrases"
        public final Map<String, String> shortcuts = new LinkedHashMap<>(); // pref "shortcuts"
        public final List<String> pinTexts = new ArrayList<>();             // الحافظة المثبتة بالترتيب
        public final Map<String, String> settings = new LinkedHashMap<>();  // من القائمة البيضاء فقط
    }

    // ---- التجميع: Snapshot → سجلات ----------------------------------------------

    /**
     * بناء سجلات المزامنة من لقطة المخازن. rev/ts يختمان على كل سجل (عدّاد الجهاز
     * المرفوع مع كل دفعة). حتمي تماماً: نفس اللقطة + نفس rev/ts = نفس البايتات.
     */
    public static List<SyncVault.Rec> collect(Snapshot s, int rev, long ts) {
        if (rev < 1) throw new SyncVault.SyncRefused("rev");
        if (ts < 0) throw new SyncVault.SyncRefused("ts");
        List<SyncVault.Rec> out = new ArrayList<>();
        if (s == null) return out;
        if (s.themeIdx >= 0)
            out.add(new SyncVault.Rec("theme:current", SyncVault.KIND_THEME, "current",
                    String.valueOf(s.themeIdx), rev, ts, false));
        // الكلمات المتعلّمة: هوية معيارية (norm) حتى لا تتضارب عائلات الهمزة
        LinkedHashMap<String, SyncVault.Rec> seen = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.dictWords.entrySet()) {
            String word = e.getKey(), lang = e.getValue();
            if (word == null || word.isEmpty() || word.length() > 64) continue;
            if (!"ar".equals(lang) && !"en".equals(lang)) continue;
            if (seen.size() >= CAP_DICT_WORDS) break;
            String id = "dw:" + lang + ":" + tag(Dictionary.norm(word));
            if (seen.containsKey(id)) continue;                     // norm متكرر = عائلة واحدة
            seen.put(id, new SyncVault.Rec(id, SyncVault.KIND_DICT_WORD, lang, word, rev, ts, false));
        }
        out.addAll(seen.values());
        for (String w : cap(s.exceptionWords, Exceptions.CAP))
            out.add(new SyncVault.Rec("ex:" + tag(Dictionary.norm(w)), SyncVault.KIND_SETTING,
                    "nocorrect", w, rev, ts, false));
        for (String p : cap(s.phrases, CAP_PHRASES))
            out.add(new SyncVault.Rec("ph:" + tag(p), SyncVault.KIND_SHORTCUT, "phrase", p, rev, ts, false));
        for (Map.Entry<String, String> e : s.shortcuts.entrySet())
            out.add(new SyncVault.Rec("sc:" + tag(e.getKey().toLowerCase()), SyncVault.KIND_SHORTCUT,
                    e.getKey(), e.getValue(), rev, ts, false));
        for (int i = 0; i < s.pinTexts.size() && i < CAP_PINS; i++) {
            String t = s.pinTexts.get(i);
            out.add(new SyncVault.Rec("pin:" + tag(t), SyncVault.KIND_CLIP_PIN,
                    String.format("%04d", i), t, rev, ts, false));
        }
        int n = 0;
        for (Map.Entry<String, String> e : s.settings.entrySet()) {
            if (n >= CAP_SETTINGS) break;
            if (!syncable(e.getKey())) continue;                    // فشل مغلق: ما ليس في القائمة لا يُزامن
            out.add(new SyncVault.Rec("st:" + e.getKey(), SyncVault.KIND_SETTING,
                    e.getKey(), e.getValue(), rev, ts, false));
            n++;
        }
        return out;
    }

    // ---- التفكيك: سجلات → خطة كتابة في المخازن ----------------------------------

    /** خطة استعادة حتمية — الواجهة تكتبها في المخازن الحقيقية بترتيبها الآمن. */
    public static final class Plan {
        public int themeIdx = -1;                                  // -1 = لا تغيير
        public final List<String> dictAr = new ArrayList<>();      // كلمات تُضاف إلى user_ar.txt
        public final List<String> dictEn = new ArrayList<>();      // كلمات تُضاف إلى user_en.txt
        public final List<String> exceptions = new ArrayList<>();  // تُضاف إلى nocorrect
        public final List<String> phrases = new ArrayList<>();     // تُدمج مع عبارات المستخدم
        public final Map<String, String> shortcuts = new LinkedHashMap<>();
        public final List<String> pins = new ArrayList<>();        // نصوص بالترتيب (استبدال قائمة التثبيت)
        public final Map<String, String> settings = new LinkedHashMap<>(); // من القائمة البيضاء فقط

        public int count() {
            return (themeIdx >= 0 ? 1 : 0) + dictAr.size() + dictEn.size() + exceptions.size()
                    + phrases.size() + shortcuts.size() + pins.size() + settings.size();
        }
    }

    /**
     * بناء الخطة من سجلات مدموجة (بعد الاستعادة والدمج). تُهمل الشواهد (tomb)،
     * وتُرتب التثبيتات بمفتاح الموضع، وتُرفض قيم إعدادات خارج القائمة البيضاء.
     */
    public static Plan apply(List<SyncVault.Rec> state) {
        Plan p = new Plan();
        if (state == null) return p;
        // أحدث rev لكل id يفوز — الحالة القادمة من merge مرتبة ومرشحة أصلاً،
        // لكن الخطة تُبنى دفاعياً: آخر ظهور لكل id (حتمي).
        LinkedHashMap<String, SyncVault.Rec> byId = new LinkedHashMap<>();
        for (SyncVault.Rec r : state) byId.put(r.id, r);
        LinkedHashMap<Integer, String> pinsByPos = new LinkedHashMap<>();
        for (SyncVault.Rec r : byId.values()) {
            if (r.tomb || r.body == null) continue;
            if (r.id.startsWith("theme:")) {
                try { p.themeIdx = Integer.parseInt(r.body); } catch (NumberFormatException ignored) { }
            } else if (r.id.startsWith("dw:ar:")) p.dictAr.add(r.body);
            else if (r.id.startsWith("dw:en:")) p.dictEn.add(r.body);
            else if (r.id.startsWith("ex:")) p.exceptions.add(r.body);
            else if (r.id.startsWith("ph:")) p.phrases.add(r.body);
            else if (r.id.startsWith("sc:")) p.shortcuts.put(r.key, r.body);
            else if (r.id.startsWith("pin:")) {
                try { pinsByPos.put(Integer.parseInt(r.key), r.body); } catch (NumberFormatException ignored) { }
            } else if (r.id.startsWith("st:")) {
                if (syncable(r.key)) p.settings.put(r.key, r.body);
            }
        }
        for (Map.Entry<Integer, String> e : pinsByPos.entrySet()) p.pins.add(e.getValue());
        return p;
    }

    // ---- أدوات -------------------------------------------------------------------

    /** بصمة هوية قصيرة من النص: sha-256 → 16 بايت → base64url (نقية، بلا android). */
    static String tag(String v) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return SyncVault.b64url(Arrays.copyOf(d, 16));   // 22 محرف b64url بلا حشو
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new SyncVault.SyncRefused("sha_unavailable");
        }
    }

    private static List<String> cap(List<String> in, int cap) {
        return in.size() <= cap ? in : in.subList(in.size() - cap, in.size());
    }

    private SyncState() { }
}
