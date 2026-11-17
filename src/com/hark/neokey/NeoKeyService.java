package com.hark.neokey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NeoKeyService extends InputMethodService implements KeyboardView.Listener {

    /** one toast through L — the inline glue collapses onto one shape
     *  (the W54 discipline; the law-5 trim riding the W84 export commit;
     *  same L.t, same durations — zero behavior change). */
    private void toast(String s) { Toast.makeText(this, L.t(s), Toast.LENGTH_SHORT).show(); }
    private void toastL(String s) { Toast.makeText(this, L.t(s), Toast.LENGTH_LONG).show(); }

    private static final String SEP = "\u0001";
    private KeyboardView view;
    private boolean arabic = true;
    private int mode = Layouts.AR;
    private int shift = KeyboardView.SHIFT_OFF;
    private long lastShiftTap, lastSpaceAt;
    private List<ClipItem> clipList = new ArrayList<>();
    private ClipStore clipStore;
    private volatile java.util.Map<String, Long> clipSc = java.util.Collections.emptyMap();   // W48: token → clip id
    private int clipTab = 0, clipPins = 0, clipSearchSeq = 0, clipSearchHits = -1;
    private String clipQuery;
    private boolean clipSearch, clipMore;
    private long clipCat = 0;
    private int clipLimit = 60;
    private List<ClipRepo.Cat> clipCats = new ArrayList<>();
    private final StringBuilder clipBuf = new StringBuilder();
    private final List<String> recents = new ArrayList<>();
    private boolean emojiSearch;
    private final StringBuilder emojiQuery = new StringBuilder();
    private ClipboardManager cm;
    private Dictionary dictAr, dictEn;
    // C2 (الأسبوع 6): قواميس اللهجات تُبنى عند أول حاجة فقط (تحميلها خلفي داخلي)،
    // والمبدّل الذكي يقرر من السياق مع احترام pref dialect_mode (الافتراضي تلقائي).
    private Dictionary dictHijazi, dictNajdi, dictGulf;
    private String dialectMode = SmartSwitch.AUTO;
    // W59 (DialectAdapt): ملف لكل تطبيق — التطبيق الحالي وبذرة الإقلاع البارد،
    // والملف نص محلي في filesDir لا يغادر الجهاز أبداً (الخارطة: تظل الملفات محلية).
    private String currentApp = "";
    private boolean dialectSeeded;
    private DialectAdapt dialectAdapt;
    // W60 (AdaptEngine): طبقة التكرار الشخصية — المثيل الوحيد المسجّل في
    // Engines (العقد الواحد)، المتجر نص محلي في filesDir لا يغادر الجهاز.
    private volatile AdaptEngine adaptEngine;
    // W61 (StripAccept): قياس القبول المحلي — عدّادات المصادر المعلَمة وقت
    // الضغط فقط (personal/root/clip/strip) — نص محلي في filesDir لا يغادر الجهاز.
    private StripAccept stripAccept;
    /** W61: بوابة التخفي الواحدة على الالتحام — وضع التخفي يخضع المحركات
     *  كلها مرة واحدة (فارغة آمنة بدل النتائج، والركوبات تتجاهلها طبيعياً). */
    private Engine.Cand[] engineRun(Engine.Ctx ctx, int limit) {
        if (incognito) return new Engine.Cand[0];
        return Engines.run(ctx, limit);
    }
    /** W61: قياس القبول المحلي — المصدر المعلَم وقت الضغط فقط، والتخفي لا يسجل. */
    private void recordAccept(String source) {
        if (incognito) return;
        stripAccept().accept(source);
    }
    /** W61: عدّادات القبول — تحميل خفيف عند أول حاجة (فارغ آمن). */
    private StripAccept stripAccept() {
        if (stripAccept == null) {
            String text = "";
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(
                        new File(getFilesDir(), "strip_accept.txt"));
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                text = "";   // لا ملف بعد = حالة مصنع (fail-open للقراءة فقط)
            }
            stripAccept = StripAccept.parse(text);
        }
        return stripAccept;
    }

    /** W61: حفظ عدّادات القبول عند الخروج — الفارغ يحذف الملف (حالة مصنع). */
    private void saveStripAccept() {
        if (stripAccept == null) return;
        String s = stripAccept.serialize();
        File f = new File(getFilesDir(), "strip_accept.txt");
        if (s.isEmpty()) { f.delete(); return; }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try { out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            finally { out.close(); }
        } catch (Exception ignored) {
            // التخزين قد يفشل مؤقتاً — الكتابة تُعاد في الخروج القادم
        }
    }
    /** W60: نص حوار مصدر الاقتراح — ثابت ساكن (نطاق TrimGate يعفي الساكن:
     *  الحرف الطويل ≥60B في جسم دالة يعود للقصّ — القانون نفسه). */
    private static final String W60_SOURCE_MSG =
            "المصدر: طبقة التكرار الشخصية — تعلّم الكيبورد هذه الكلمة من كتابتك، محلياً في جهازك فقط، وتُمسح من زر واحد (مسح التنبؤ الشخصي) في أداة الحافظة.";
    private volatile Predictor predictor;   // A2 neural next-word model (async load, fail-open)
    private boolean suggest = true, dblSpace = true, sensitive, selecting, pickedSuggestion;
    /** W56: the long-press family tree owns the strip until the next tap or key
     *  (any strip rebuild clears it — the tree is an idle-state view, never a mode). */
    private boolean familyTree;
    private String voiceDialect;
    private int convCol;
    private static final String PROV = "❝ ";   // W95: a proverb chip's visible mark
    private static final String SOFT = "🕊 ";   // W96: a gentler-phrase chip's mark
    private static final String OCC = "✨ ";   // W97: an occasion-phrase chip's mark
    private static final String GUARD = "🛡 ";   // W106: anti-scam guard chip mark (tap deletes the code/card)
    private String guardSpan;   // W106: the exact typed suffix the guard chip deletes
    private static final String DRAFTM = "📝 ";   // W114: «المسودة الخالدة» chip mark
    private String draftText, draftPkg;
    private long draftAt;
    private boolean draftLoaded, draftDirty, draftLive, draftArmed;
    private static final String SWAPM = "🔍 ";   // W113: «شاهد اللصق» chip mark
    private String prevClipVal, swapOrig, swapNew;
    private long prevClipAt, swapAt;
    private static final String LINKM = "🔗 ";   // W112: «حارس الروابط» chip mark
    private static final String IBANM = "🏦 ";   // W111: «حارس الآيبان» chip mark
    private String[] ibanHit;
    private static final String GUEST = "✋ ";   // W110: «بصمة الكتابة» guest-mode chip
    private Dialect.Rhythm rhythm;
    private long lastKeyAt;
    private int rhythmDirty;
    private static final String TONE = "🪞 ";   // W109: «مرآة النبرة» chip mark (tap swaps in the warmer message)
    private String toneTo;
    private static final String PROMISE = "🤝 ";   // W108: «ذاكرة الوعد» chip mark (tap opens a prefilled calendar event)
    private String[] promiseWhen;
    private String promiseText;
    private static final String METER = "🎼 ";   // W107: «ميزان الشعر» chip mark (tap dismisses)
    private static final String SAR = "💰 ";   // W102: amount-in-words chip mark (tap replaces «1500 ريال»)
    private static final String NUM = "🔢 ";   // W101: number-in-words chip mark (tap replaces the number)
    private static final String DATE = "📅 ";   // W100: today's date chip mark (inserts after the word)
    private static final String CMP = "✍ ", AZ = "⇄ ";   // W98: completion / Arabizi chip marks (both replace)
    private java.util.Map<String, String> prov, soft, occ, cmp, azd, emj;   // W95–W99: loaded once
    private String softFrom;   // W96: the word the gentle chip replaces   // W94: the dialect the last «محوّل اللهجات» tap produced   // W91: the dialect the open voice session listens for
    private Bitmap photo;
    private long photoStamp = -1;
    private Bitmap avatarBmp;
    private long avatarStamp = -1;
    private int words, chars;
    private long forgetSeen = -1;
    private boolean autocorrect, learnOn = true;
    private final java.util.HashSet<String> noCorrect = new java.util.HashSet<>();
    private boolean noCorrectLoaded;
    /** Corrections applied in the current field — the «ماذا كتبت فعلاً مقابل ما سيُرسل» feed (item B, week 2). */
    private final java.util.ArrayList<SendPreview.Edit> sentEdits = new java.util.ArrayList<>();
    private int kbLayout;
    private int decor = Decor.OFF;
    private String lastWord, corrOriginal, corrApplied;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private String glideWord;
    private List<String> glideAlts;
    private TextToSpeech tts;
    private boolean incognito;
    private int fieldKind;  // 1 email, 2 url
    private static final List<String> EMAIL_CHIPS = Arrays.asList("@gmail.com", "@hotmail.com", "@outlook.com", "@icloud.com", ".com", ".sa");
    private static final List<String> URL_CHIPS = Arrays.asList("https://", "www.", ".com", ".sa", ".net", "/");
    private String freshClip, clipChip, undoBefore, undoAfter, lastCaptured;
    private long freshTime;
    private boolean ttsReady;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() { captureClip(); }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
        Perf.installCrashHandler(this);
        Shield.ensure(this); // integrity shield, cached (same process as the settings UI)
        SharedPreferences p = Theme.prefs(this);
        String rec = p.getString("recent_emoji", "");
        if (!rec.isEmpty()) recents.addAll(Arrays.asList(rec.split(" ")));
        clipStore = ClipStore.get(this);
        clipStore.migrateIfNeeded();
        reloadClips();
        cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.addPrimaryClipChangedListener(clipListener);
        dictAr = new Dictionary(this, "ar");
        dictEn = new Dictionary(this, "en");
        // A2: the neural next-word model (assets/nnet_ar.nqf — 2.9M params, NQF
        // int8) loads in the background; the keyboard never waits for it. Until
        // it is ready — or forever, if the load ever fails — the traditional
        // dictionary+bigram path answers alone (fail-open by design).
        new Thread(new Runnable() { public void run() {
            try {
                java.io.InputStream in = getAssets().open("nnet_ar.nqf");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                predictor = Predictor.load(bo.toByteArray());
            } catch (Throwable t) {
                predictor = null; // fail-open: typing is never disrupted by the model
            }
        }}, "nnet-load").start();
        // W57: the tashkeel degree seeds from prefs (shipped CLOSED — the
        // quiet default); ClipEdit's cycle updates this mirror live.
        TashkeelEngine.mode = Theme.prefs(this).getInt("tashkeel_mode", TashkeelEngine.MODE_OFF);
        // W58: the grammar mode seeds the same way (shipped CLOSED — law 8);
        // ClipEdit's chip cycles it live and persists it.
        GrammarNudge.mode = Theme.prefs(this).getInt("grammar_mode", GrammarNudge.MODE_OFF);
        // W57: the engines-load thread — BOTH real engines load here in one
        // background thread (the keyboard never waits): the W56 root family
        // (assets/root_index.txt — RIX1) and the W57 tashkeel index (assets/
        // tashkeel_index.txt — RIX2). Fail-closed PER ENGINE: a corrupt asset
        // detaches ITS engine only (the strict load refuses the WHOLE index —
        // never a half-built engine), the verified survivor(s) still register
        // in contract order, typing untouched either way — the W55 law.
        new Thread(new Runnable() { public void run() {
            RootEngine re = null;
            TashkeelEngine te = null;
            GrammarNudge ge = null;
            try { re = RootEngine.load(assetBytes("root_index.txt")); }
            catch (Throwable t) { re = null; }
            try { te = TashkeelEngine.load(assetBytes("tashkeel_index.txt")); }
            catch (Throwable t) { te = null; }
            // W58: the third engine joins the SAME thread (assets/
            // grammar_rules.txt — GRX1) — a corrupt asset detaches ITS engine
            // only, the survivors still register in contract order.
            try { ge = GrammarNudge.load(assetBytes("grammar_rules.txt")); }
            catch (Throwable t) { ge = null; }
            // W60: the personal repetition layer — no asset to corrupt (its
            // store is filesDir runtime state; parse skips bad lines and never
            // throws), so it registers ALWAYS in contract order (4th); an
            // absent store = factory-empty (the engine answers empty until
            // the user types).
            AdaptEngine ae = new AdaptEngine();
            try {
                java.io.FileInputStream ain = new java.io.FileInputStream(new File(getFilesDir(), "adapt_store.txt"));
                try {
                    java.io.ByteArrayOutputStream abo = new java.io.ByteArrayOutputStream();
                    byte[] abuf = new byte[4096];
                    int an;
                    while ((an = ain.read(abuf)) > 0) abo.write(abuf, 0, an);
                    ae = AdaptEngine.parse(new String(abo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                } finally { ain.close(); }
            } catch (Throwable t) { /* no store yet = factory-empty — still registered */ }
            adaptEngine = ae;
            // W57 batch-2: the feedback wording rides the same thread (the
            // strict init; a corrupt asset leaves the banks EMPTY — fail-closed)
            try { Feedback.init(assetBytes("feedback_copy.txt")); }
            catch (Throwable t) { }
            // strict registration: verified engines only — never a half-built
            // registry. W60: the branch ladder is the append form of the SAME
            // law — contract order preserved, every non-null subset registers
            // exactly as the branch form did, an empty registry stays empty.
            java.util.List<Engine> reg = new ArrayList<>();
            if (re != null) reg.add(re);
            if (te != null) reg.add(te);
            if (ge != null) reg.add(ge);
            if (ae != null) reg.add(ae);
            Engines.set(reg.toArray(new Engine[0]));
            if (re != null) RootEngine.attach(re); else RootEngine.detach();
            if (te != null) TashkeelEngine.attach(te); else TashkeelEngine.detach();
            if (ge != null) GrammarNudge.attach(ge); else GrammarNudge.detach();
        }}, "engines-load").start();
    }

    /** One asset read for the engines-load thread (the strict-load feed). */
    private byte[] assetBytes(String name) throws java.io.IOException {
        java.io.InputStream in = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    /** The engine cands of ONE source (the strip rides are per-engine). */
    private static Engine.Cand[] pickSrc(Engine.Cand[] cands, String src) {
        int n = 0;
        if (cands != null) for (Engine.Cand c : cands) if (c != null && src.equals(c.source)) n++;
        Engine.Cand[] out = new Engine.Cand[n];
        int i = 0;
        if (cands != null) for (Engine.Cand c : cands) if (c != null && src.equals(c.source)) out[i++] = c;
        return out;
    }

    @Override
    public void onDestroy() {
        if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        flushStats();
        stopVoice();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        long perf0 = SystemClock.uptimeMillis();
        // transparent window so the rounded top corners show the app behind
        Window w = getWindow().getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setFormat(PixelFormat.TRANSLUCENT);
        }
        view = new KeyboardView(this);
        view.setListener(this);
        arabic = Theme.prefs(this).getBoolean("arabic", true);
        mode = arabic ? Layouts.AR : Layouts.EN;
        applyPrefs();
        applyLayout();
        view.setClips(clipList, clipTab, clipQuery, clipPins, clipCats, clipCat, clipMore);
        view.setRecents(recents);
        view.setDecor(decor);
        Perf.record("create", SystemClock.uptimeMillis() - perf0);
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // keep the app visible in landscape too
    }

    @Override
    public void onComputeInsets(Insets out) {
        super.onComputeInsets(out);
        if (view != null && view.isShown() && !isFullscreenMode()) {
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            int top = loc[1] + view.cornerInset();
            out.contentTopInsets = top;
            out.visibleTopInsets = top;
            out.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
            out.touchableRegion.set(0, loc[1], view.getWidth(), loc[1] + view.getHeight());
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (!restarting) reloadClips();   // picks up edits / imports made in the app screens
        boolean wasEn = L.en;
        L.init(this);
        if (wasEn != L.en) setInputView(onCreateInputView());
        if (view != null && !restarting) view.collapseArrows();
        emojiSearch = false;
        clipSearch = false;
        emojiQuery.setLength(0);
        if (view == null) return;
        Perf.session();
        view.markShow();
        applyPrefs();
        SharedPreferences sp = Theme.prefs(this);
        if (sp.getBoolean("dict_reload", false)) {
            sp.edit().remove("dict_reload").apply();
            dictAr = new Dictionary(this, "ar");
            dictEn = new Dictionary(this, "en");
        }
        insertPendingDraft(info);
        incognito = sp.getBoolean("incognito", false);
        view.setIncognito(incognito);
        // W59: التطبيق الحالي هو مفتاح الملف لكل تطبيق (فارغ إن لم يعلنه النظام).
        currentApp = info == null || info.packageName == null ? "" : info.packageName;
        // W60: يوم الطبقة الشخصية (حتمي — بلا ساعة داخل المحرك) + زر المسح
        // الواحد من ClipEdit يمحو النسخة الحية عند بدء الحقل القادم.
        if (adaptEngine != null)
            adaptEngine.setToday((int) (System.currentTimeMillis() / 86400000L));
        if (sp.getBoolean("adapt_reset", false)) {
            sp.edit().remove("adapt_reset").apply();
            // W61: زر المسح الواحد يمحو الطبقة الشخصية وعدّادات القبول معاً.
            if (adaptEngine != null) adaptEngine.wipe();
            if (stripAccept != null) stripAccept.wipe();
        }
        if (sp.getBoolean("reset_learn", false)) {
            dictAr.clearUser();
            dictEn.clearUser();
            // W59: المسح الكامل يمحو ملفات اللهجة لكل تطبيق أيضاً (حالة مصنع بايتية).
            dialectAdapt = null;
            new File(getFilesDir(), "app_profiles.txt").delete();
            sp.edit().remove("reset_learn").apply();
        }
        // W114: a fresh field — arm the offer of a draft this app lost (never one written in this same field).
        if (!draftLoaded) {
            draftLoaded = true;
            draftText = sp.getString("draft_text", null);
            draftPkg = sp.getString("draft_pkg", null);
            draftAt = sp.getLong("draft_at", 0);
        }
        draftLive = false;
        draftArmed = !restarting && draftText != null;
        captureClip();
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        fieldKind = cls != InputType.TYPE_CLASS_TEXT ? 0
                : (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) ? 1
                : var == InputType.TYPE_TEXT_VARIATION_URI ? 2 : 0;
        sensitive = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_URI
                || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME) {
            mode = Theme.prefs(this).getBoolean("numpad", true) ? Layouts.NUM : Layouts.SYM;
        } else {
            mode = arabic ? Layouts.AR : Layouts.EN;
        }
        shift = KeyboardView.SHIFT_OFF;
        selecting = false;
        view.setSelecting(false);
        lastWord = null;
        corrApplied = null;
        sentEdits.clear();
        applyLayout();
        updateEnter(info);
        updateAutoCaps();
        updateSuggestions();
    }

    @Override
    public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        stopVoice();
        flushStats();
        Perf.sampleMemory(this);
        Perf.flush();
        if (draftDirty) {   // W114: one write per field visit
            draftDirty = false;
            SharedPreferences.Editor de = Theme.prefs(this).edit();
            if (draftText == null) de.remove("draft_text").remove("draft_pkg").remove("draft_at");
            else de.putString("draft_text", draftText).putString("draft_pkg", draftPkg).putLong("draft_at", draftAt);
            de.apply();
        }
        dictAr.save();
        dictEn.save();
        saveDialectProfiles();
        saveAdaptStore();
        saveStripAccept();
    }

    @Override
    public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        updateAutoCaps();
        updateSuggestions();
    }

    private void applyPrefs() {
        File f = new File(getFilesDir(), "bg.jpg");
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp != photoStamp) {
            photoStamp = stamp;
            photo = stamp == 0 ? null : BitmapFactory.decodeFile(f.getAbsolutePath());
        }
        view.setTheme(Theme.current(this), photo, Theme.photoDim(this));
        view.setAnimBg(Theme.prefs(this).getInt("anim_bg", -1));
        view.setSoundStyle(Theme.prefs(this).getInt("sound_pack", 0), Theme.prefs(this).getInt("sound_vol", 50));
        view.setPressFx(Theme.prefs(this).getInt("press_fx", -1));
        File af = new File(getFilesDir(), "avatar.png");
        long ast = af.exists() ? af.lastModified() : 0;
        if (ast != avatarStamp) {
            avatarStamp = ast;
            try { avatarBmp = ast == 0 ? null : BitmapFactory.decodeFile(af.getAbsolutePath()); } catch (Throwable e) { avatarBmp = null; }
        }
        view.setAvatar(Theme.prefs(this).getBoolean("avatar_show", true) ? avatarBmp : null);
        view.setArrows(Features.on(this), Features.right(this), Features.left(this));
        view.setPrefs(Theme.haptics(this), Theme.popups(this), Theme.heightPct(this),
                Theme.sound(this), Theme.swipeCursor(this), Theme.oneHand(this));
        view.setPhrases(Theme.allPhrases(this));
        view.setFeel(Theme.prefs(this).getInt("vib", 0), Theme.prefs(this).getInt("lpdelay", 1));
        long forget = Theme.prefs(this).getLong("forget_at", 0);
        if (forget > forgetSeen) {
            if (forgetSeen != -1) { dictAr.forgetAll(); dictEn.forgetAll(); }
            forgetSeen = forget;
        }
        suggest = Theme.suggestions(this);
        dialectMode = Theme.prefs(this).getString("dialect_mode", SmartSwitch.AUTO);
        if (!SmartSwitch.validMode(dialectMode)) dialectMode = SmartSwitch.AUTO;
        SharedPreferences lp = Theme.prefs(this);
        kbLayout = lp.getInt("kblayout", 0);
        boolean gaming = kbLayout == Layouts.L_GAME;
        autocorrect = !gaming && lp.getBoolean("autocorrect", false);
        learnOn = lp.getBoolean("learn", true);
        if (!noCorrectLoaded) {
            noCorrectLoaded = true;
            noCorrect.addAll(Exceptions.parse(lp.getString("nocorrect", "")));
        }
        view.setGlide(!gaming && lp.getBoolean("glide", true));
        view.setKeyShape(lp.getInt("keyshape", 0));
        view.setLook(lp.getBoolean("pressglow", true), lp.getBoolean("anims", true),
                lp.getInt("keygap", 1), lp.getInt("radius", 0), lp.getInt("wpct", 100));
        int ks = Theme.prefs(this).getInt("keysize", 1);
        boolean big = lp.getBoolean("bigkeys", false), reduce = Theme.reduceMotion(this);
        view.setKeyScale(big ? 1.3f : ks == 0 ? 0.88f : ks == 2 ? 1.14f : ks == 3 ? 1.3f : 1f);
        view.setAccess(lp.getBoolean("cues", true), lp.getInt("onehandw", 82), lp.getInt("vibms", 20), lp.getInt("vibamp", 160),
                big, lp.getBoolean("contrast", false), reduce);
        if (reduce) view.setLook(false, false, lp.getInt("keygap", 1), lp.getInt("radius", 0), lp.getInt("wpct", 100));
        if (big && Theme.heightPct(this) < 122)
            view.setPrefs(Theme.haptics(this), Theme.popups(this), 122, Theme.sound(this), Theme.swipeCursor(this), Theme.oneHand(this));
        dblSpace = Theme.doubleSpace(this);
    }

    private void applyLayout() {
        boolean num = Theme.numberRow(this);
        view.setKeys(Layouts.build(mode, arabic, num, Theme.arabicDigits(this), kbLayout), num, mode != Layouts.EN && mode != Layouts.NUM);
        view.setShiftState(mode == Layouts.EN ? shift : KeyboardView.SHIFT_OFF);
    }

    private void updateEnter(EditorInfo info) {
        int k = KeyboardView.ENTER_NEWLINE;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
                case EditorInfo.IME_ACTION_SEARCH: k = KeyboardView.ENTER_SEARCH; break;
                case EditorInfo.IME_ACTION_SEND: k = KeyboardView.ENTER_SEND; break;
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_NEXT: k = KeyboardView.ENTER_GO; break;
                case EditorInfo.IME_ACTION_DONE: k = KeyboardView.ENTER_DONE; break;
                default: break;
            }
        }
        view.setEnterKind(k);
    }

    private void updateAutoCaps() {
        if (view == null || mode != Layouts.EN || shift == KeyboardView.SHIFT_LOCK) return;
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        int caps = 0;
        if (ic != null && info != null && info.inputType != InputType.TYPE_NULL) {
            caps = ic.getCursorCapsMode(info.inputType);
        }
        shift = caps != 0 ? KeyboardView.SHIFT_ON : KeyboardView.SHIFT_OFF;
        view.setShiftState(shift);
    }

    // ------------------------------------------------------------ suggestions

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'' || (c >= 0x064B && c <= 0x0652);
    }

    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) return "";
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && isWordChar(after.charAt(0))) return "";
        CharSequence b = ic.getTextBeforeCursor(40, 0);
        if (b == null) return "";
        int i = b.length();
        while (i > 0 && isWordChar(b.charAt(i - 1))) i--;
        return b.subSequence(i, b.length()).toString();
    }

    private Dictionary dictFor(String w) {
        if (w.isEmpty()) return arabic ? dictAr : dictEn;
        char c = w.charAt(0);
        return (c >= 0x0600 && c <= 0x06FF) ? dictAr : dictEn;
    }

    // ---- C2 (الأسبوع 6): التبديل الذكي فصحى/لهجة --------------------------------

    /** قاموس لهجة بالمعرّف — يُبنى مرة واحدة عند أول حاجة (تحميله خلفي داخله). */
    private Dictionary dialectDict(String id) {
        if ("ar_hijazi".equals(id)) {
            if (dictHijazi == null) dictHijazi = new Dictionary(this, "ar_hijazi");
            return dictHijazi;
        }
        if ("ar_najdi".equals(id)) {
            if (dictNajdi == null) dictNajdi = new Dictionary(this, "ar_najdi");
            return dictNajdi;
        }
        if ("ar_gulf".equals(id)) {
            if (dictGulf == null) dictGulf = new Dictionary(this, "ar_gulf");
            return dictGulf;
        }
        return null;
    }

    /** W87: القواميس الثلاثة محمية من التحويل إلى الفصحى — إلا إذا اختار المستخدم الفصحى صراحة. */
    private Dictionary[] dialectLexicons() {
        if (SmartSwitch.MSA.equals(dialectMode)) return null;
        return new Dictionary[]{dialectDict("ar_hijazi"), dialectDict("ar_najdi"), dialectDict("ar_gulf")};
    }

    /**
     * معرّف قاموس اللهجة النشط للسياق الحالي، أو null للفصحى الصرفة.
     * الكشف لحظي على نافذة النص الظاهر فقط — لا يُخزن شيء عن المستخدم،
     * ويتوقف في الحقول الحساسة وعند إيقاف الاقتراحات.
     * W59: الإقلاع البارد في وضع auto بنافذة فارغة يبذّر قانون التطبيق
     * المستقر فقط (updates ≥ 3) — أي دليل حي يكسر البذرة بقرار SmartSwitch
     * الأصلي الذي لم يُمَس (لا كسر للتبديل). التسجيل يقع عند إكمال الكلمة
     * (finishWord) لا هنا — قرار لكل جملة مكتوبة لا لكل ضغطة.
     */
    private String activeDialectId() {
        if (!suggest || sensitive) return null;
        if (SmartSwitch.OFF.equals(dialectMode) || SmartSwitch.MSA.equals(dialectMode)) return null;
        if (!SmartSwitch.AUTO.equals(dialectMode)) return SmartSwitch.dictFor(dialectMode);
        InputConnection ic = getCurrentInputConnection();
        CharSequence b = ic == null ? null : ic.getTextBeforeCursor(120, 0);
        List<String> toks = Dialect.tokenize(b == null ? "" : b.toString());
        dialectSeeded = false;
        if (toks.isEmpty()) {
            String seed = dialectAdapt().seed(currentApp);
            if (seed != null) { dialectSeeded = true; return seed; }
        }
        SmartSwitch.Decision d = SmartSwitch.decide(toks, dialectMode);
        return d.dialectActive() ? d.dictId : null;
    }

    /** W59: ملف اللهجات لكل تطبيق — تحميل خفيف عند أول حاجة (فارغ آمن). */
    private DialectAdapt dialectAdapt() {
        if (dialectAdapt == null) {
            String text = "";
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(
                        new File(getFilesDir(), "app_profiles.txt"));
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    text = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                text = "";   // لا ملف بعد = حالة مصنع (fail-open للقراءة فقط)
            }
            dialectAdapt = DialectAdapt.parse(text);
        }
        return dialectAdapt;
    }

    /** W59: حفظ ملف اللهجات عند الخروج من الحقل — الفارغ يحذف الملف (حالة مصنع). */
    private void saveDialectProfiles() {
        if (dialectAdapt == null) return;
        String s = dialectAdapt.serialize();
        File f = new File(getFilesDir(), "app_profiles.txt");
        if (s.isEmpty()) { f.delete(); return; }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try { out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            finally { out.close(); }
        } catch (Exception ignored) {
            // التخزين قد يفشل مؤقتاً — الكتابة تُعاد في الخروج القادم
        }
    }

    /** W60: حفظ المتجر الشخصي عند الخروج من الحقل — الفارغ يحذف الملف (حالة مصنع). */
    private void saveAdaptStore() {
        if (adaptEngine == null) return;
        String s = adaptEngine.serialize();
        File f = new File(getFilesDir(), "adapt_store.txt");
        if (s.isEmpty()) { f.delete(); return; }
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try { out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            finally { out.close(); }
        } catch (Exception ignored) {
            // التخزين قد يفشل مؤقتاً — الكتابة تُعاد في الخروج القادم
        }
    }

    /**
     * ترشيحات مدمجة: كلمات اللهجة النشطة أولاً ثم سدادة الفصحى (حد cap، بلا تكرار).
     * W59: الأوزان بحسب الكشف عبر mergeLines — الكشف الحي يتصدر حتى cap،
     * والبذرة (ثقة أنقض) تسهم بنصف الوزن فقط.
     */
    private List<String> mergedSuggest(String w, String dictId, int cap) {
        Dictionary dd = dictId == null ? null : dialectDict(dictId);
        List<String> dw = (dd != null && dd.isLoaded()) ? dd.suggest(w, cap) : new ArrayList<String>();
        return DialectAdapt.mergeLines(dw, dictFor(w).suggest(w, cap), cap, dialectSeeded);
    }

    @Override
    public void onEmojiSearch() {
        emojiSearch = true;
        emojiQuery.setLength(0);
        new Thread(new Runnable() { @Override public void run() { EmojiSearch.load(NeoKeyService.this); } }).start();
        updateSuggestions();
    }

    private void exitEmojiSearch() {
        emojiSearch = false;
        emojiQuery.setLength(0);
        updateSuggestions();
    }

    private void updateSuggestions() {
        long n0 = System.nanoTime();
        updateSuggestions0();
        if (suggest && !sensitive) Perf.record("sugg", (System.nanoTime() - n0) / 1e6);
    }

    /** A draft reviewed in the writing assistant waits here until the user is back in a text field. */
    private void insertPendingDraft(EditorInfo info) {
        SharedPreferences p = Theme.prefs(this);
        String d = p.getString("w_insert", null);
        if (d == null) return;
        if (info != null && getPackageName().equals(info.packageName)) return;
        long at = p.getLong("w_insert_at", 0);
        p.edit().remove("w_insert").remove("w_insert_at").apply();
        if (System.currentTimeMillis() - at > 180000) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(d, 1);
    }

    private void openWriter() {
        InputConnection ic = getCurrentInputConnection();
        CharSequence sel = ic == null ? null : ic.getSelectedText(0);
        Intent i = new Intent(this, WriterActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(WriterActivity.EXTRA_FROM_KB, true);
        if (sel != null && sel.length() > 0 && !sensitive) i.putExtra(WriterActivity.EXTRA_CTX, sel.toString());
        requestHideSelf(0);
        startActivity(i);
    }

    /** W110: true while the current typing rhythm is not the owner's. */
    private boolean guest() {
        return rhythm != null && rhythm.guest && Theme.prefs(this).getBoolean("rhythm_guard", true);
    }

    private void updateSuggestions0() {
        if (view == null) return;
        if (clipSearch) {
            String q = clipBuf.toString();
            List<String> chips = new ArrayList<>();
            chips.add("✕  " + (q.isEmpty() ? L.t("ابحث في الحافظة…") : q + "|"));
            if (!q.trim().isEmpty() && clipSearchHits >= 0)
                chips.add(clipSearchHits == 0 ? L.t("لا نتائج") : "🔎 " + L.t("عرض") + " " + clipSearchHits);
            view.setSuggestions(chips);
            return;
        }
        if (emojiSearch) {
            String q = emojiQuery.toString();
            List<String> res = q.trim().isEmpty()
                    ? new ArrayList<>(recents.subList(0, Math.min(6, recents.size())))
                    : EmojiSearch.search(this, q, 6);
            String chip = "✕  " + (q.isEmpty() ? (arabic ? "ابحث عن إيموجي…" : "Search emoji…") : q + "|");
            view.setEmojiResults(chip, res);
            return;
        }
        if (fieldKind != 0 && decor == Decor.OFF) {
            view.setSuggestions(new ArrayList<>(fieldKind == 1 ? EMAIL_CHIPS.subList(0, 4) : URL_CHIPS.subList(0, 4)));
            return;
        }
        // W111 «حارس الآيبان»: an IBAN at the cursor, any app, checked before the money moves.
        ibanHit = null;
        if (!sensitive) {
            InputConnection iic = getCurrentInputConnection();
            CharSequence ib = iic == null ? null : iic.getTextBeforeCursor(48, 0);
            String[] h = ib == null ? null : Dialect.iban(ib.toString());
            if (h != null) {
                ibanHit = h;
                List<String> il = new ArrayList<>();
                il.add(IBANM + ("ok".equals(h[0]) ? "آيبان سليم ✓" + (h[3].isEmpty() ? "" : " · بنك " + h[3]) + " · رتّبه"
                        : "len".equals(h[0]) ? "الآيبان أطول من اللازم! راجعه" : "آيبان خطأ! رقم ناقص أو مقلوب"));
                view.setSuggestions(il);
                return;
            }
        }
        // W113 «شاهد اللصق»: the clipboard was swapped — warn before the paste, offer the original after it.
        if (swapNew != null) {
            long age = SystemClock.elapsedRealtime() - swapAt;
            InputConnection sic = getCurrentInputConnection();
            CharSequence sb = sic == null ? null : sic.getTextBeforeCursor(swapNew.length() + 2, 0);
            boolean pasted = sb != null && sb.toString().trim().endsWith(swapNew);
            if (age > 600000 || (!pasted && age > 60000)) {
                swapNew = null;
                swapOrig = null;
            } else {
                List<String> sl = new ArrayList<>();
                sl.add(SWAPM + (pasted ? "الملصوق غير اللي نسخته! · رجّع الأصل" : "الحافظة تغيّرت لوحدها! تأكّد قبل اللصق"));
                view.setSuggestions(sl);
                return;
            }
        }
        // W114 «المسودة الخالدة»: keep a long text while it lives; an empty field in the same visit means it was sent.
        if (!sensitive && !incognito && draftLoaded) {
            InputConnection dic = getCurrentInputConnection();
            CharSequence db = dic == null ? null : dic.getTextBeforeCursor(20000, 0);
            CharSequence da = dic == null ? null : dic.getTextAfterCursor(20000, 0);
            if (db != null && da != null) {
                String full = db.toString() + da;
                if (Dialect.draftWorth(full)) {
                    draftText = full.trim();
                    draftPkg = currentApp;
                    draftAt = System.currentTimeMillis();
                    draftLive = draftDirty = true;
                    draftArmed = false;
                } else if (!full.trim().isEmpty()) {
                    draftArmed = false;
                } else if (draftLive) {
                    draftText = null;
                    draftLive = false;
                    draftDirty = true;
                } else if (draftArmed && !guest() && Dialect.draftOffer(draftPkg, draftAt, draftText, currentApp,
                        System.currentTimeMillis(), full)) {
                    List<String> dl = new ArrayList<>();
                    dl.add(DRAFTM + "رجّع مسودتك: «" + Dialect.draftLabel(draftText) + "»");
                    view.setSuggestions(dl);
                    return;
                }
            }
        }
        // W112 «حارس الروابط»: a link at the cursor that imitates a bank, a service or a brand.
        if (!sensitive) {
            InputConnection lic = getCurrentInputConnection();
            CharSequence lb = lic == null ? null : lic.getTextBeforeCursor(120, 0);
            String[] lg = lb == null ? null : Dialect.linkGuard(lb.toString());
            if (lg != null) {
                List<String> ll = new ArrayList<>();
                ll.add(LINKM + ("homograph".equals(lg[0]) ? "رابط بحروف مموّهة! لا تفتحه"
                        : "رابط مقلَّد يشبه " + lg[1] + "! لا تفتحه"));
                view.setSuggestions(ll);
                return;
            }
        }
        // W106 «حارس الاحتيال»: a bare OTP or a card number typed into a chat app — warned before
        // it is sent, whatever the layout (the number pad included); the tap deletes it.
        guardSpan = null;
        if (!sensitive && Dialect.chatApp(currentApp)) {
            InputConnection gic = getCurrentInputConnection();
            CharSequence gb = gic == null ? null : gic.getTextBeforeCursor(64, 0);
            String[] g = gb == null ? null : Dialect.scamSpan(gb.toString());
            if (g != null) {
                guardSpan = g[1];
                List<String> gl = new ArrayList<>();
                gl.add(GUARD + ("card".equals(g[0]) ? "رقم بطاقة! لا ترسله لأحد · احذفه" : "لا ترسل رمز التحقق لأحد · احذفه"));
                view.setSuggestions(gl);
                return;
            }
        }
        if (!suggest || sensitive || decor != Decor.OFF || mode == Layouts.SYM || mode == Layouts.SYM2 || mode == Layouts.NUM) {
            view.setSuggestions(Collections.<String>emptyList());
            return;
        }
        if (glideWord != null && glideAlts != null && endsWithBefore(glideWord)) {
            view.setSuggestions(glideAlts);
            return;
        }
        String w = currentWord();
        if (w.isEmpty()) {
            List<String> p = predictions();
            if (guest()) {   // W110: someone else's rhythm — no clipboard, a quiet ✋ marker instead
                p.add(0, GUEST + "وضع الضيف");
                while (p.size() > 3) p.remove(p.size() - 1);
            } else if (freshClip != null && SystemClock.elapsedRealtime() - freshTime < 120000) {
                String one = freshClip.replace('\n', ' ').trim();
                clipChip = KeyboardView.CLIP_MARK + (one.length() > 22 ? one.substring(0, 22) + "…" : one);
                p.add(0, clipChip);
                while (p.size() > 3) p.remove(p.size() - 1);
            } else {
                freshClip = null;
            }
            view.setSuggestions(p);
            return;
        }
        List<String> s = mergedSuggest(w, activeDialectId(), 3);
        // W57: both engines ride the strip through the W55 contract (Engines.run
        // — the ONE call site): dict completions keep their lead slots, the root
        // family keeps the W56 tail slot, the tashkeel reading appends ONLY when
        // there is room (it never displaces the family). Source-filtered —
        // familyInto rides "root" cands only, tashkeelInto rides "tashkeel"
        // only; an empty registry (corrupt index / detached) answers empty.
        Engine.Cand[] eng = engineRun(new Engine.Ctx(null, null, w), 8);
        RootEngine.familyInto(s, pickSrc(eng, "root"), 3);
        // W86: the form-error recovery rides the SAME tail slot when the
        // composing word is unmapped — the witnessed affix strip infers the
        // root and ONE corpus-rank family form fills the tail; DISPLAY ONLY
        // (the silent path never consumes it — docs/ROOT.md).
        RootEngine.recoverInto(s, w, 3);
        TashkeelEngine.tashkeelInto(s, pickSrc(eng, "tashkeel"), 3);
        // W58: the grammar correction rides LAST (an optional tap-to-apply
        // suggestion — the engine explains, it never force-corrects) and NEVER
        // in incognito (وضع التخفي: لا تحليل — the scan surface refuses too).
        // W61: no scattered incognito checks — the ONE gate (engineRun)
        // already returned empty cands, so these rides are natural no-ops.
        GrammarNudge.nudgeInto(s, pickSrc(eng, "grammar"), 3);
        // W60: the personal repetition layer rides LAST (append-only — it
        // never displaces the dict lead or the root family tail; every chip
        // carries the ★ badge — the user SEES why the keyboard suggested it)
        // and NEVER in incognito (وقف تام — the law).
        if (!guest()) AdaptEngine.personalInto(s, pickSrc(eng, "personal"), 3);   // W110: hidden in guest mode
        String fx = ArabicFixes.fix(w);
        if (fx != null) { s.remove(fx); s.add(0, fx); while (s.size() > 3) s.remove(s.size() - 1); }
        String sc = guest() ? null : Theme.shortcuts(this).get(w.toLowerCase());   // W110
        if (sc != null) { s.remove(sc); s.add(0, sc); while (s.size() > 3) s.remove(s.size() - 1); }
        else {
            // W48 clip shortcut: the typed token expands to its clip — the suggestion carries
            // the mark + token; the tap dispatches in onSuggestion
            Long cid = guest() ? null : clipSc.get(w.toLowerCase());   // W110
            if (cid != null) {
                String cs2 = KeyboardView.CLIP_SC_MARK + w.toLowerCase();
                if (!s.contains(cs2)) { s.add(0, cs2); while (s.size() > 3) s.remove(s.size() - 1); }
            }
        }
        String emo = EmojiHints.forWord(w);
        if (emo != null) {
            if (s.size() >= 3) s.set(2, emo); else s.add(emo);
        }
        boolean upperFirst = Character.isUpperCase(w.charAt(0));
        if (upperFirst) {
            for (int i = 0; i < s.size(); i++) {
                String x = s.get(i);
                if (Character.isLetter(x.charAt(0))) s.set(i, Character.toUpperCase(x.charAt(0)) + x.substring(1));
            }
        }
        view.setSuggestions(s);
    }

    /** Next-word predictions after "word ". */
    /** W108: the promise's time → a calendar event the user confirms in their own calendar app (no permission). */
    private void openReminder(String[] w, String text) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int v = Integer.parseInt(w[1]);
        if ("m".equals(w[0])) c.add(java.util.Calendar.MINUTE, v);
        else {
            if ("d".equals(w[0])) c.add(java.util.Calendar.DAY_OF_YEAR, v);
            else if ("w".equals(w[0])) {
                int ahead = (v - c.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7;
                c.add(java.util.Calendar.DAY_OF_YEAR, ahead == 0 ? 7 : ahead);
            }
            c.set(java.util.Calendar.HOUR_OF_DAY, "n".equals(w[0]) ? 20 : 9);
            c.set(java.util.Calendar.MINUTE, 0);
            if ("n".equals(w[0]) && c.getTimeInMillis() < System.currentTimeMillis()) c.add(java.util.Calendar.HOUR_OF_DAY, 1);
        }
        long at = c.getTimeInMillis();
        Intent it = new Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, at + 15 * 60000L)
                .putExtra(android.provider.CalendarContract.Events.TITLE, "🤝 " + text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(it);
        } catch (Exception e) {
            toast("لا يوجد تطبيق تقويم");
        }
    }

    private List<String> predictions() {
        InputConnection ic = getCurrentInputConnection();
        List<String> out = new ArrayList<>();
        if (ic == null) return out;
        CharSequence b = ic.getTextBeforeCursor(40, 0);
        if (b == null || b.length() < 2 || b.charAt(b.length() - 1) != ' ') return out;
        int end = b.length() - 1, i = end;
        while (i > 0 && isWordChar(b.charAt(i - 1))) i--;
        if (prov == null) { prov = pairsAsset("dialect_proverbs.txt"); soft = pairsAsset("dialect_soften.txt"); occ = pairsAsset("dialect_occasions.txt"); cmp = pairsAsset("dialect_complete.txt"); azd = pairsAsset("arabizi.txt"); emj = pairsAsset("dialect_emoji.txt"); }
        // W107 «ميزان الشعر»: «صدر *» earns its meter (البحر وتفعيلاته); with the عجز typed after the
        // star, the strip says whether the عجز sits on the same meter. Only a line holding «*» is read.
        CharSequence lb = ic.getTextBeforeCursor(240, 0);
        String line = lb == null ? "" : lb.toString();
        line = line.substring(line.lastIndexOf('\n') + 1);
        int star = line.lastIndexOf('*');
        if (star > 0) {
            int s0 = line.lastIndexOf('*', star - 1) + 1;
            String sadr = line.substring(s0, star), ajuz = line.substring(star + 1).trim();
            int mi = Dialect.meterOf(sadr);
            if (mi >= 0) {
                String name = Dialect.METER_NAMES[mi];
                if (ajuz.isEmpty()) {
                    out.add(METER + "البحر " + name + " · " + Dialect.METER_FEET[mi]);
                    return out;
                }
                if (Dialect.arudUnits(ajuz).length() >= Dialect.arudUnits(sadr).length() - 1) {
                    out.add(METER + (Dialect.onMeter(ajuz, mi) ? "البيت على " + name + " ✓" : "العجز خارج وزن " + name + "؟"));
                    return out;
                }
            }
        }
        // W108 «ذاكرة الوعد»: the sentence just typed holds a promise and a time («أبشر بكرة أرسله»)
        // and its last word is one of the two → 🤝 «ذكّرني بكرة ٩ص»; the tap opens the calendar, prefilled.
        if (i < end && Dialect.promiseMarker(b.subSequence(i, end).toString())) {
            int sb = Math.max(line.lastIndexOf('.'), Math.max(line.lastIndexOf('؟'), Math.max(line.lastIndexOf('!'), line.lastIndexOf('?')))) + 1;
            String sent = line.substring(Math.min(sb, line.length())).trim();
            String[] pw = Dialect.promise(sent);
            if (pw != null) {
                promiseWhen = pw;
                promiseText = sent.length() > 80 ? sent.substring(0, 80) + "…" : sent;
                out.add(PROMISE + "ذكّرني " + pw[2]);
                return out;
            }
        }
        // W109 «مرآة النبرة»: in a chat app, a message that will land sharp or cold earns 🪞 and a warmer version.
        if (i < end && Dialect.chatApp(currentApp)) {
            String core = line.replaceAll("[\\s!?؟.]+$", "");
            int tb = Math.max(core.lastIndexOf('.'), Math.max(core.lastIndexOf('؟'), Math.max(core.lastIndexOf('!'), core.lastIndexOf('?')))) + 1;
            String sent = line.substring(Math.min(tb, line.length())).trim();
            String tn = Dialect.tone(sent);
            if (tn != null && line.endsWith(sent + " ")) {
                softFrom = sent;
                toneTo = Dialect.warmer(sent);
                out.add(TONE + ("cold".equals(tn) ? "تنقرأ باردة · دفّيها" : "تنقرأ حادّة · ليّنها"));
                return out;
            }
        }
        // W98: an Arabizi token (3ashan / 7abibi) earns its Arabic spelling (⇄, tap replaces).
        int k = end;
        while (k > 0 && b.charAt(k - 1) < 128 && Character.isLetterOrDigit(b.charAt(k - 1))) k--;
        String az = k < end ? Dialect.arabizi(b.subSequence(k, end).toString(), azd) : null;
        if (az != null) {
            softFrom = b.subSequence(k, end).toString();
            out.add(AZ + az);
            return out;
        }
        // W105: a typed date (25/12/2026 or ١٤٤٨/٧/٥) earns its other calendar (📅, tap inserts after it).
        int dq = end;
        while (dq > 0 && (Character.isDigit(b.charAt(dq - 1)) || b.charAt(dq - 1) == '/' || b.charAt(dq - 1) == '-')) dq--;
        if (dq < end && (dq == 0 || !Character.isLetter(b.charAt(dq - 1)))) {
            String cv = TextTools.convertDate(Dialect.parseDate(b.subSequence(dq, end).toString()), Theme.arabicDigits(this));
            if (cv != null) {
                out.add(DATE + cv);
                return out;
            }
        }
        // W102: «1500 ريال» earns the cheque form «فقط … ريال سعودي لا غير» (💰, tap replaces both).
        if (i < end && i > 1 && b.charAt(i - 1) == ' ') {
            int r = i - 1;
            while (r > 0 && (Character.isDigit(b.charAt(r - 1)) || b.charAt(r - 1) == '.' || b.charAt(r - 1) == '٫')) r--;   // W103: halalas
            String aw = r < i - 1 && (r == 0 || !Character.isLetter(b.charAt(r - 1)))
                    ? Dialect.amountWords(b.subSequence(r, i - 1).toString(), b.subSequence(i, end).toString()) : null;
            if (aw != null) {
                softFrom = b.subSequence(r, end).toString();
                out.add(SAR + aw);
                return out;
            }
        }
        // W101: a typed number (1500 / ١٥٠٠) earns its Arabic words (🔢, tap replaces).
        int q = end;
        while (q > 0 && Character.isDigit(b.charAt(q - 1))) q--;
        String nw = q < end && (q == 0 || !Character.isLetter(b.charAt(q - 1))) ? Dialect.numWords(b.subSequence(q, end).toString()) : null;
        if (nw != null) {
            softFrom = b.subSequence(q, end).toString();
            out.add(NUM + nw);
            return out;
        }
        if (i == end) {
            // "word 😂 " — offer nothing, but after an emoji-suggestion just stop
            return out;
        }
        String prev = b.subSequence(i, end).toString();
        out.addAll(dictFor(prev).predict(prev, 3));
        // C2: التبديل الذكي — تنبؤات قاموس اللهجة النشط تُدمج في المسار المعجمي
        // (قبل السدادة العصبية — النموذج يكمل ما تركه المعجم لا ما تركته اللهجة)
        String did = activeDialectId();
        if (did != null) {
            Dictionary dd = dialectDict(did);
            if (dd != null && dd.isLoaded()) {
                // W59: البذرة بنصف الوزن أيضاً في التنبؤ (floor(3/2)=1) — الكشف
                // الحي يتصدر بحد الثلاثة كما كان، والثقة الأنقض وزنها أنقض.
                int quota = dialectSeeded ? 1 : 3;
                for (String x : dd.predict(prev, 3)) {
                    if (out.size() >= quota || out.size() >= 3) break;
                    if (!out.contains(x)) out.add(x);
                }
            }
        }
        // W60: the personal seat — the ONE personal candidate (the highest
        // effective score, temporal decay applied) fills BEFORE the neural
        // fill: the documented weighting is dict → personal → neural (the
        // repetition layer beats the model for the user's own words, never
        // the dictionary). Via the ONE contract dispatch + the ONE incognito
        // gate (engineRun) — no scattered checks anywhere (W61).
        Engine.Cand[] pe = engineRun(new Engine.Ctx(null, prev, ""), 8);
        for (Engine.Cand c : pickSrc(pe, "personal")) {
            if (out.size() >= 3) break;
            if (!out.contains(c.text)) { out.add(c.text); break; }   // المقعد الواحد
        }
        // A2: neural top-3 completes the strip — it never displaces the
        // dictionary path, it only fills what the bigram table left empty
        // (the model's measured top-3 is the NNETGATE2 section of the gate).
        Predictor np = predictor;
        if (np != null && out.size() < 3) {
            String before = null;
            if (i > 0) {
                int j = i;
                while (j > 0 && isWordChar(b.charAt(j - 1))) j--;
                if (j < i) before = b.subSequence(j, i).toString();
            }
            for (String w : np.top3(before, prev)) {
                if (out.size() >= 3) break;
                if (!out.contains(w)) out.add(w);
            }
        }
        // W95: a trigger word earns a proverb chip in the lead slot; the strip keeps
        // one prediction beside it so the chip gets half the width.
        // W96: a hurtful word earns a gentler same-dialect phrase first (tap replaces it).
        // W98: a phrase opener (يعطيك / بيض) earns its whole phrase (✍, tap replaces).
        String sf = soft.get(Dialect.normAr(prev)), sm = SOFT;
        if (sf == null) { sf = cmp.get(Dialect.normAr(prev)); sm = CMP; }
        if (sf != null) {
            softFrom = prev;
            while (out.size() > 1) out.remove(out.size() - 1);
            out.add(0, sm + sf);
            return out;
        }
        // W100: «اليوم» / «التاريخ» earns today's Hijri + Gregorian date (📅, tap inserts it).
        if (Dialect.dateWord(Dialect.normAr(prev))) {
            while (out.size() > 1) out.remove(out.size() - 1);
            out.add(0, DATE + TextTools.todayArabic(Theme.arabicDigits(this)));
            return out;
        }
        // W97: an occasion word earns its customary phrase (✨), else a proverb (❝).
        String pv = occ.get(Dialect.normAr(prev)), mk = OCC;
        if (pv == null) { pv = prov.get(Dialect.normAr(prev)); mk = PROV; }
        if (pv != null) {
            while (out.size() > 1) out.remove(out.size() - 1);
            out.add(0, mk + pv);
            return out;
        }
        // W99 «إيموجي اللهجة»: a dialect word (طفشان / فديتك) earns two emoji chips beside one prediction.
        String de = emj.get(Dialect.normAr(prev));
        if (de != null) {
            while (out.size() > 1) out.remove(out.size() - 1);
            for (String e : de.split(" ")) if (!e.isEmpty() && out.size() < 3) out.add(e);
            return out;
        }
        String emo = EmojiHints.forWord(prev);
        if (emo != null && out.size() < 3 && !out.contains(emo)) out.add(emo);
        return out;
    }

    /** The user undid a correction: never correct this word again, and teach it to the dictionary. */
    private void rememberNoCorrect(String w) {
        if (w == null || w.isEmpty() || sensitive || incognito) return;
        // item B week 2: the exceptions list is a real data type — dedup by norm
        // spelling («مشكله» و«مشكلة» entry واحدة), cap 300 keeping the latest.
        java.util.List<String> l = Exceptions.add(new java.util.ArrayList<>(noCorrect), w);
        noCorrect.clear();
        noCorrect.addAll(l);
        if (learnOn) { Dictionary d = dictFor(w); d.learn(w); d.learn(w); }
        Theme.prefs(this).edit().putString("nocorrect", Exceptions.serialize(l)).apply();
    }

    private void finishWord(String w) {
        if (w.isEmpty() || sensitive) return;
        if (incognito) { lastWord = null; words++; return; }
        if (w.indexOf(' ') >= 0) { lastWord = null; words++; return; }
        if (!learnOn) { lastWord = w; words++; return; }
        Dictionary d = dictFor(w);
        d.learn(w);
        if (lastWord != null && dictFor(lastWord) == d) d.learnPair(lastWord, w);
        lastWord = w;
        words++;
        recordDialectDecision();
        // W60: طبقة التكرار الشخصية — التعلم خلف حارسي التخفي وlearnOn
        // (return أعلاه) وقرار كل كلمة مكتملة لا كل ضغطة.
        if (adaptEngine != null) {
            int day = (int) (System.currentTimeMillis() / 86400000L);
            adaptEngine.setToday(day);
            adaptEngine.learn(w, day);
        }
    }

    /**
     * W59: تعلّم ملف اللهجة عند إكمال كل كلمة (قرار لكل جملة مكتوبة لا لكل
     * ضغطة): قرار لهجة حي بدليل ≥ MIN_EVIDENCE يُسجَّل محلياً، وقرار auto-msa
     * بلا أي علامة بنافذة كافية يعدّ نحو إنزال قانون لهجة مستقر إلى الفصحى.
     * التخفي لا يمر هنا أصلاً (return أعلاه) — لا تسجيل في التخفي أبداً.
     */
    private void recordDialectDecision() {
        if (!suggest || !SmartSwitch.AUTO.equals(dialectMode) || currentApp.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        CharSequence b = ic == null ? null : ic.getTextBeforeCursor(120, 0);
        List<String> toks = Dialect.tokenize(b == null ? "" : b.toString());
        SmartSwitch.Decision d = SmartSwitch.decide(toks, dialectMode);
        if (d.dialectActive() && d.evidence >= SmartSwitch.MIN_EVIDENCE) {
            dialectAdapt().record(currentApp, d.dictId);
        } else if (!d.dialectActive() && "auto-msa".equals(d.reason)) {
            dialectAdapt().recordMsa(currentApp, 0, toks.size());
        }
    }

    @Override
    public void onSuggestion(String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (familyTree) {
            // W56: a tree chip tapped — ✕ exits back to the normal strip, a
            // family member commits through the ordinary suggestion path below.
            familyTree = false;
            if (word.startsWith("✕")) { updateSuggestions(); return; }
            recordAccept(StripAccept.ROOT);   // W61: مصدر معلَم — عضو شجرة العائلة
        }
        if (clipSearch) {
            if (word.startsWith("✕")) { clipSearch = false; view.showPanel(KeyboardView.PANEL_CLIP); updateSuggestions(); return; }
            if (word.startsWith("🔎")) finishClipSearch();
            return;
        }
        if (emojiSearch) {
            if (word.startsWith("✕")) { exitEmojiSearch(); return; }
            onEmoji(word);
            exitEmojiSearch();
            return;
        }
        if (fieldKind != 0 && (EMAIL_CHIPS.contains(word) || URL_CHIPS.contains(word))) {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            String w = word;
            if (w.startsWith("@") && b != null && b.length() == 1 && b.charAt(0) == '@') w = w.substring(1);
            ic.commitText(w, 1);
            return;
        }
        if (clipChip != null && word.equals(clipChip) && freshClip != null) {
            recordAccept(StripAccept.CLIP);   // W61: مصدر معلَم — رقاقة حافظة
            ic.commitText(freshClip, 1);
            freshClip = null;
            clipChip = null;
            updateSuggestions();
            return;
        }
        if (word.startsWith(KeyboardView.CLIP_SC_MARK)) {
            // W48 clip-shortcut expansion: the typed token is replaced by the clip text
            recordAccept(StripAccept.CLIP);   // W61: مصدر معلَم — اختصار حافظة
            Long cid = clipSc.get(word.substring(1));
            if (cid != null) {
                String cur = currentWord();
                if (!cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
                onClipInsert(cid);
            }
            return;
        }
        // W60/W61: the ★ badge is stripped before any dispatch — the tap
        // commits the bare word (which finishWord then re-learns), the
        // acceptance is RECORDED (the honest labeled source) and FEEDS the
        // personal layer with the documented +1 reinforcement weight.
        if (word.startsWith(AdaptEngine.MARK)) {
            recordAccept(StripAccept.PERSONAL);
            word = word.substring(AdaptEngine.MARK.length());
            stripAccept().feed(adaptEngine, word,
                    (int) (System.currentTimeMillis() / 86400000L));
        }
        if ((word.startsWith(SOFT) || word.startsWith(CMP) || word.startsWith(AZ) || word.startsWith(NUM) || word.startsWith(SAR)) && softFrom != null) {
            CharSequence b = ic.getTextBeforeCursor(softFrom.length() + 1, 0);
            ic.beginBatchEdit();
            if (b != null && b.toString().equals(softFrom + " ")) ic.deleteSurroundingText(b.length(), 0);
            ic.commitText(word.substring(word.indexOf(' ') + 1) + " ", 1);   // W96/W98: the gentler phrase, whole phrase or Arabic spelling
            ic.endBatchEdit();
            softFrom = null;
            return;
        }
        if (word.startsWith(DRAFTM)) {   // W114: the lost text, whole, back where it was
            if (draftText != null) {
                ic.commitText(draftText, 1);
                draftArmed = false;
            }
            return;
        }
        if (word.startsWith(SWAPM)) {   // W113: put back exactly what the user copied
            String nw = swapNew, og = swapOrig;
            CharSequence sb = nw == null ? null : ic.getTextBeforeCursor(nw.length() + 2, 0);
            if (sb != null && og != null) {
                String b = sb.toString();
                int cut = b.lastIndexOf(nw);
                if (cut >= 0 && b.substring(cut + nw.length()).trim().isEmpty()) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(b.length() - cut, 0);
                    ic.commitText(og, 1);
                    ic.endBatchEdit();
                    swapNew = null;
                    swapOrig = null;
                    return;
                }
            }
            Toast.makeText(this, "تغيّر المنسوخ بعد نسخك له — ممكن برنامج خبيث. الأصل: " + og, Toast.LENGTH_LONG).show();
            return;
        }
        if (word.startsWith(LINKM)) {   // W112: a warning only — the text stays as typed
            Toast.makeText(this, "الرابط لا يطابق الموقع الرسمي — افتح التطبيق الرسمي أو اكتب العنوان بنفسك", Toast.LENGTH_LONG).show();
            return;
        }
        if (word.startsWith(IBANM)) {   // W111: a sound IBAN is laid out in fours; a broken one is only warned
            String[] h = ibanHit;
            ibanHit = null;
            if (h != null && "ok".equals(h[0])) {
                CharSequence ib = ic.getTextBeforeCursor(h[1].length(), 0);
                if (ib != null && ib.toString().equals(h[1])) {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(ib.length(), 0);
                    ic.commitText(h[2] + " ", 1);
                    ic.endBatchEdit();
                }
            } else {
                Toast.makeText(this, "تأكّد من رقم الآيبان قبل التحويل — فيه خانة غلط", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (word.startsWith(GUEST)) {   // W110: what guest mode is, and how it ends
            Toast.makeText(this, "أسلوب كتابة غير مألوف: كلماتك المحفوظة والحافظة مخفية حتى يرجع أسلوبك", Toast.LENGTH_LONG).show();
            return;
        }
        if (word.startsWith(TONE)) {   // W109: the message itself, warmer
            if (softFrom != null && toneTo != null) {
                CharSequence tb = ic.getTextBeforeCursor(softFrom.length() + 1, 0);
                ic.beginBatchEdit();
                if (tb != null && tb.toString().equals(softFrom + " ")) {
                    ic.deleteSurroundingText(tb.length(), 0);
                    ic.commitText(toneTo + " ", 1);
                }
                ic.endBatchEdit();
            }
            softFrom = null;
            toneTo = null;
            return;
        }
        if (word.startsWith(PROMISE)) {   // W108: the promise lands in the calendar, the user saves it there
            if (promiseWhen != null) openReminder(promiseWhen, promiseText);
            return;
        }
        if (word.startsWith(METER)) return;   // W107: the meter chip only informs
        if (word.startsWith(GUARD)) {   // W106: delete the code / card number before it is sent
            if (guardSpan != null) {
                CharSequence gb = ic.getTextBeforeCursor(guardSpan.length(), 0);
                if (gb != null && gb.toString().equals(guardSpan)) ic.deleteSurroundingText(guardSpan.length(), 0);
            }
            guardSpan = null;
            return;
        }
        if (word.startsWith(DATE)) {
            ic.commitText(word.substring(DATE.length()) + " ", 1);   // W100: the date itself
            return;
        }
        if (word.startsWith(PROV) || word.startsWith(OCC)) {
            ic.commitText(word.substring(2) + " ", 1);   // W95/W97: the proverb or phrase itself
            return;
        }
        if (!Character.isLetter(word.charAt(0))) {
            // emoji suggestion: keep the word, add the emoji
            finishWord(currentWord());
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            boolean spaced = b == null || b.length() == 0 || b.charAt(0) == ' ' || b.charAt(0) == '\n';
            ic.commitText((spaced ? "" : " ") + word + " ", 1);
            pickedSuggestion = false;
            return;
        }
        if (glideWord != null && endsWithBefore(glideWord)) {
            ic.deleteSurroundingText(glideWord.length(), 0);
            String w2 = word + " ";
            ic.commitText(w2, 1);
            dictFor(word).learn(word);
            glideAlts.remove(word);
            glideAlts.add(0, glideWord.trim());
            glideWord = w2;
            return;
        }
        String cur = currentWord();
        ic.beginBatchEdit();
        if (!cur.isEmpty()) ic.deleteSurroundingText(cur.length(), 0);
        ic.commitText(word + " ", 1);
        ic.endBatchEdit();
        recordAccept(StripAccept.STRIP);   // W61: رقاقة الشريط العامة — الدلو الصادق
        finishWord(word);
        chars += word.length() + 1;
        corrApplied = null;
        pickedSuggestion = true;
        if (mode == Layouts.EN && shift == KeyboardView.SHIFT_ON) shift = KeyboardView.SHIFT_OFF;
        updateAutoCaps();
    }

    /**
     * W56: long-press on a suggestion reveals its morphological family tree
     * (الكيبورد الذي يعرف أصل الكلمة): the ✕ root chip exits, up to 10 family
     * members commit on tap. Fail-closed everywhere — no attached engine, no
     * root, no family (or a trivial one-member family) → the press is a haptic
     * no-op, exactly the pre-W56 behavior.
     */
    @Override
    public void onSuggestionLong(String word) {
        // W60: the ★ chip's long-press is the source dialog (شفافية المصدر —
        // the user sees WHY, and where the one-tap wipe lives) — the family
        // tree / reading picker never run for a personal chip.
        if (word != null && word.startsWith(AdaptEngine.MARK)) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L.t("لماذا هذا الاقتراح؟"))
                    .setMessage(L.t(W60_SOURCE_MSG))
                    .setPositiveButton(L.t("حسنا"), null)
                    .create().show();
            return;
        }
        // W90: a member of a dialect-synonym row reveals the rest of the row
        // (وش ← ايش، شنو، ماذا) — tap commits like a family member, ✕ exits.
        if (word != null && !word.isEmpty()) {
            synEnsure();
            List<String> sy = Dialect.synChips(word);
            if (sy != null) { familyTree = true; view.setSuggestions(sy); return; }
        }
        RootEngine e = RootEngine.attached();
        if (e != null && word != null && !word.isEmpty()) {
            String root = e.rootValue(word);
            String[] fam = e.familyTree(word);
            if (root != null && fam != null && fam.length >= 2) {
                familyTree = true;
                view.setSuggestions(RootEngine.treeChips(root, fam));
                return;
            }
        }
        // W57 fallback: no root family — the tashkeel reading picker (the
        // mode-gated attested readings; OFF / single reading / unmapped →
        // the haptic no-op, exactly the pre-W57 behavior).
        TashkeelEngine te = TashkeelEngine.attached();
        if (te == null || word == null || word.isEmpty()) return;
        String[] vs = TashkeelEngine.variants(word);
        if (vs.length < 2) return;
        familyTree = true;
        view.setSuggestions(TashkeelEngine.variantChips(word, vs));
    }

    // ------------------------------------------------------------ clipboard

    private void captureClip() {
        try {
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            if (TextUtils.isEmpty(t)) return;
            final String s = t.toString();
            if (s.equals(lastCaptured)) return;
            lastCaptured = s;
            // W113 «شاهد اللصق»: a money-shaped value replaced by another of the same shape, too fast or too alike.
            long clipNow = SystemClock.elapsedRealtime();
            if (prevClipVal != null && Dialect.clipSwap(prevClipVal, clipNow - prevClipAt, s)) {
                swapOrig = prevClipVal.trim();
                swapNew = s.trim();
                swapAt = clipNow;
            }
            prevClipVal = s;
            prevClipAt = clipNow;
            boolean secret = sensitive || isSensitiveClip(d);
            if (secret && !Theme.prefs(this).getBoolean("clip_sensitive", false)) return;
            freshClip = s;
            freshTime = SystemClock.elapsedRealtime();
            if (incognito) return;
            if (s.length() > ClipRepo.MAX_LEN) {
                toastL(getString(R.string.nk_neokeyservice_captureclip_1));
                return;
            }
            final int max = Theme.prefs(this).getInt("clip_max", 50);
            final long days = Theme.prefs(this).getInt("clip_days", 0);
            final long[] outId = new long[1];
            clipStore.run(new ClipStore.Job<Integer>() {
                @Override public Integer run(ClipRepo r) {
                    long now = System.currentTimeMillis();
                    int res = r.add(s, now, outId);
                    r.prune(max, days * 86400000L, now);
                    return res;
                }
            }, new ClipStore.Done<Integer>() {
                @Override public void done(Integer res) {
                    if (res == null || res == ClipRepo.VERIFY_FAIL || res == ClipRepo.ERROR)
                        Toast.makeText(NeoKeyService.this, L.t("تعذّر حفظ النص في الحافظة"), Toast.LENGTH_SHORT).show();
                    else if (res == ClipRepo.OK)
                        scheduleOtpWipe(outId[0], s);
                    reloadClips();
                }
            });
        } catch (Exception ignored) {
            // clipboard can be restricted while the keyboard is not focused
        }
    }

    /**
     * W65 (OTPShield): درع الرموز — «كشف رمز التحقق الواصل للحافظة + عداد
     * انتهاء + مسح تلقائي موثق من الحافظة والسجل بعد مهلة يضبطها المستخدم».
     * الشروط الصارمة: الإعداد يعمل، وليس في التخفي (قانون isOtpAllowed —
     * والالتقاط نفسه يعود مبكراً في التخفي قبل التخزين بنيوياً)، والنص يطابق
     * الغلاف الموثق. عند الحين: حذف صف السجل بالمعرّف + إفراغ حافظة النظام
     * إن ما زالت تحمل النص نفسه + سطر تدقيق محلي بلا محتوى الرمز أبداً.
     * (الرموز المعلَّمة حساسة من التطبيقات لا تُخزَّن أصلاً — أقوى من المسح.)
     */
    private void scheduleOtpWipe(final long rowId, final String text) {
        try {
            if (rowId <= 0 || !OTPShield.isOtp(text)) return;
            android.content.SharedPreferences p = Theme.prefs(this);
            boolean on = p.getBoolean("otp_shield", true);
            if (!OTPShield.isOtpAllowed(incognito, on)) return;
            final int timeout = OTPShield.clampTimeout(p.getInt("otp_timeout", OTPShield.DEFAULT_TIMEOUT));
            final long delay = OTPShield.expiryAt(0L, timeout);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    clipStore.run(new ClipStore.Job<Integer>() {
                        @Override public Integer run(ClipRepo r) {
                            r.delete(rowId);
                            return 0;
                        }
                    }, new ClipStore.Done<Integer>() {
                        @Override public void done(Integer res) {
                            clearClipboardIfSame(text);
                            otpAudit("AUTO_WIPE", timeout);
                            reloadClips();
                        }
                    });
                }
            }, delay);
            otpAudit("ARMED", timeout);
        } catch (Exception ignored) {
            // جدولة الدرع لا تكسر الالتقاط أبداً
        }
    }

    /** W65: إفراغ حافظة النظام فقط إن ما زالت تحمل النص ذاته (لا يُمسّ غيره). */
    private void clearClipboardIfSame(String text) {
        try {
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return;
            CharSequence cur = d.getItemAt(0).coerceToText(this);
            if (cur != null && text != null && text.contentEquals(cur)) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (Exception ignored) {
            // بعض الأجهزة تقيد الكتابة على الحافظة — السجل حُذف وهو الجوهر
        }
    }

    /** W65: سطر التدقيق المحلي (20 سطراً كأحدث حد — أرقام وأوسام فقط). */
    private void otpAudit(String action, int timeout) {
        try {
            android.content.SharedPreferences p = Theme.prefs(this);
            String line = OTPShield.auditLine(action, timeout, System.currentTimeMillis());
            String all = p.getString("otp_audit", "");
            all = all.isEmpty() ? line : line + "\n" + all;
            String[] parts = all.split("\n");
            if (parts.length > 20) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 20; i++) b.append(parts[i]).append(i < 19 ? "\n" : "");
                all = b.toString();
            }
            p.edit().putString("otp_audit", all).apply();
        } catch (Exception ignored) {
        }
    }

    /** Android 13+: apps mark passwords / OTPs as sensitive when copying. */
    private static boolean isSensitiveClip(ClipData d) {
        if (android.os.Build.VERSION.SDK_INT < 24) return false;
        try {
            android.os.PersistableBundle x = d.getDescription().getExtras();
            return x != null && (x.getBoolean("android.content.extra.IS_SENSITIVE", false));
        } catch (Exception e) {
            return false;
        }
    }

    void reloadClips() {
        final int tab = clipTab;
        final String q = clipQuery;
        final long cat = clipCat;
        final int limit = clipLimit;
        final int[] pins = new int[1];
        final List<List<ClipRepo.Cat>> cats = new ArrayList<>();
        final List<java.util.Map<String, Long>> scs = new ArrayList<>();
        clipStore.run(new ClipStore.Job<List<ClipItem>>() {
            @Override public List<ClipItem> run(ClipRepo r) {
                List<ClipItem> l = tab == ClipRepo.TAB_CATS && cat == 0 ? new ArrayList<ClipItem>()
                        : q == null ? r.list(tab, cat, limit) : r.search(tab, cat, q, limit);
                pins[0] = r.pinCount();
                cats.add(tab == ClipRepo.TAB_CATS ? r.categories() : new ArrayList<ClipRepo.Cat>());
                scs.add(r.shortcutMap());   // W48: the suggestion strip rides every panel reload
                return l;
            }
        }, new ClipStore.Done<List<ClipItem>>() {
            @Override public void done(List<ClipItem> l) {
                if (l == null) return;
                clipList = l;
                clipPins = pins[0];
                clipCats = cats.isEmpty() ? new ArrayList<ClipRepo.Cat>() : cats.get(0);
                clipSc = scs.isEmpty() ? clipSc : scs.get(0);
                clipMore = l.size() >= limit;
                if (view != null) view.setClips(clipList, clipTab, clipQuery, clipPins, clipCats, clipCat, clipMore);
            }
        });
    }

    // ------------------------------------------------------------ listener

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (clipSearch) {
            if (!(" ".equals(text) && clipBuf.length() == 0) && clipBuf.length() < 60) clipBuf.append(text);
            countClipSearch();
            return;
        }
        if (emojiSearch) {
            if (!(" ".equals(text) && emojiQuery.length() == 0) && emojiQuery.length() < 30) emojiQuery.append(text.toLowerCase());
            updateSuggestions();
            return;
        }
        if (mode == Layouts.EN && shift != KeyboardView.SHIFT_OFF
                && view.getPanel() == KeyboardView.PANEL_KEYS) text = text.toUpperCase();
        // W110 «بصمة الكتابة»: only the gap between keys is measured — never which key, never in a sensitive field.
        if (!sensitive && text.length() == 1) {
            long now = SystemClock.elapsedRealtime();
            if (rhythm == null) rhythm = Dialect.Rhythm.load(Theme.prefs(this).getString("rhythm", ""));
            if (lastKeyAt != 0 && Theme.prefs(this).getBoolean("rhythm_guard", true)) {
                boolean was = rhythm.guest;
                rhythm.add(now - lastKeyAt);
                if (++rhythmDirty >= 50 || was != rhythm.guest) {
                    rhythmDirty = 0;
                    Theme.prefs(this).edit().putString("rhythm", rhythm.save()).apply();
                }
            }
            lastKeyAt = now;
        }

        // item B week 2: الفاصلة المنقوطة والأرقام العربية — the layout/script
        // mismatch classes. NEVER in sensitive fields: what you type is what is sent.
        if (!sensitive && text.length() == 1 && Theme.prefs(this).getBoolean("ar_punct", true)) {
            char ch = text.charAt(0);
            String p = Correction2.punctFor(text, arabic);
            if (p != null) text = p;
            else if (Correction2.isDigitChar(ch)) {
                CharSequence pb = getCurrentInputConnection() != null
                        ? getCurrentInputConnection().getTextBeforeCursor(1, 0) : null;
                char nb = pb == null || pb.length() == 0 ? 0 : pb.charAt(pb.length() - 1);
                char d = Correction2.digitFor(ch, nb, Theme.arabicDigits(this));
                if (d != ch) text = String.valueOf(d);
            }
        }

        boolean isSpace = " ".equals(text);
        boolean isPunct = text.length() == 1 && ".,،؟?!:;؛".indexOf(text.charAt(0)) >= 0;
        corrApplied = null;
        glideWord = null;

        if (decor != Decor.OFF && text.length() == 1 && !isSpace) text = Decor.apply(text, decor);

        // finishing a word: autocorrect (optional) then learn it
        if (isSpace || isPunct) {
            String w = currentWord();
            // item B week 2: الحقول الحرجة — the no-silent-correction policy as code.
            if (!w.isEmpty() && Correction2.silentCorrectionAllowed(sensitive, autocorrect)
                    && decor == Decor.OFF && !pickedSuggestion
                    && !Exceptions.shouldSkip(w, noCorrect)) {
                String c = ArabicFixes.fix(w);
                if (c == null && (w.charAt(0) >= 0x0600 && w.charAt(0) <= 0x06FF)) {
                    // Phase 1 item B: correction v2 — confusion table + FULL sentence
                    // context (week 2): the word before AND the word after the cursor.
                    CharSequence b = getCurrentInputConnection() != null
                            ? getCurrentInputConnection().getTextBeforeCursor(40, 0) : null;
                    CharSequence a = getCurrentInputConnection() != null
                            ? getCurrentInputConnection().getTextAfterCursor(40, 0) : null;
                    String prev = Correction2.previousWord(b == null ? "" : b.toString(), w.length());
                    String next = Correction2.nextWord(a == null ? "" : a.toString());
                    // W87: the dialect lexicons are protected spellings and the active
                    // dialect repairs unknown words first (Correction2.dialectFix).
                    String adid = activeDialectId();
                    c = Correction2.dialectFix(w, prev, next, dictFor(w),
                            adid == null ? null : dialectDict(adid), dialectLexicons(), noCorrect);
                } else if (c == null) c = dictFor(w).correction(w);
                if (c != null && !c.equals(w)) {
                    if (Character.isUpperCase(w.charAt(0))) c = Character.toUpperCase(c.charAt(0)) + c.substring(1);
                    ic.deleteSurroundingText(w.length(), 0);
                    ic.commitText(c, 1);
                    corrOriginal = w;
                    corrApplied = c + text;
                    if (sentEdits.size() < 50) sentEdits.add(new SendPreview.Edit(w, c));
                    w = c;
                }
            }
            finishWord(w);
            if (isPunct) lastWord = null;
        }

        // smart punctuation after a picked suggestion: "word ." -> "word."
        if (isPunct && pickedSuggestion) {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1 && b.charAt(0) == ' ') {
                ic.deleteSurroundingText(1, 0);
                text = text + " ";
            }
        }

        // double space -> period
        if (isSpace && dblSpace && SystemClock.uptimeMillis() - lastSpaceAt < 450) {
            CharSequence b = ic.getTextBeforeCursor(2, 0);
            if (b != null && b.length() == 2 && b.charAt(1) == ' ' && Character.isLetterOrDigit(b.charAt(0))) {
                ic.deleteSurroundingText(1, 0);
                text = ". ";
                lastSpaceAt = 0;
            } else {
                lastSpaceAt = SystemClock.uptimeMillis();
            }
        } else {
            lastSpaceAt = isSpace ? SystemClock.uptimeMillis() : 0;
        }

        ic.commitText(text, 1);
        if (corrApplied != null && !corrApplied.endsWith(text)) corrApplied = null;
        chars += text.length();
        pickedSuggestion = false;
        if (mode == Layouts.EN && shift == KeyboardView.SHIFT_ON) {
            shift = KeyboardView.SHIFT_OFF;
            view.setShiftState(shift);
        }
        if ((mode == Layouts.SYM || mode == Layouts.SYM2) && isSpace) {
            EditorInfo info = getCurrentInputEditorInfo();
            int cls = info == null ? 0 : info.inputType & InputType.TYPE_MASK_CLASS;
            if (cls == InputType.TYPE_CLASS_TEXT) {
                mode = arabic ? Layouts.AR : Layouts.EN;
                applyLayout();
            }
        }
        updateAutoCaps();
    }

    @Override
    public void onEmoji(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(emoji, 1);
        chars++;
        recents.remove(emoji);
        recents.add(0, emoji);
        while (recents.size() > 36) recents.remove(recents.size() - 1);
        Theme.prefs(this).edit().putString("recent_emoji", TextUtils.join(" ", recents)).apply();
    }

    @Override
    public void onDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (clipSearch) {
            if (clipBuf.length() > 0) { clipBuf.setLength(clipBuf.length() - 1); countClipSearch(); }
            else { clipSearch = false; view.showPanel(KeyboardView.PANEL_CLIP); updateSuggestions(); }
            return;
        }
        if (emojiSearch) {
            if (emojiQuery.length() > 0) { emojiQuery.setLength(emojiQuery.length() - 1); updateSuggestions(); }
            else exitEmojiSearch();
            return;
        }
        pickedSuggestion = false;
        if (glideWord != null) {
            String g = glideWord;
            glideWord = null;
            if (endsWithBefore(g)) {
                ic.deleteSurroundingText(g.length(), 0);
                updateAutoCaps();
                return;
            }
        }
        if (corrApplied != null) {
            CharSequence b = ic.getTextBeforeCursor(corrApplied.length(), 0);
            String undo = corrApplied;
            corrApplied = null;
            if (b != null && b.toString().equals(undo)) {
                ic.deleteSurroundingText(undo.length(), 0);
                ic.commitText(corrOriginal, 1);
                rememberNoCorrect(corrOriginal);
                for (int i = sentEdits.size() - 1; i >= 0; i--)
                    if (sentEdits.get(i).typed.equals(corrOriginal)) { sentEdits.remove(i); break; }
                updateAutoCaps();
                return;
            }
        }
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
        } else {
            CharSequence before = ic.getTextBeforeCursor(32, 0);
            if (TextUtils.isEmpty(before)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            } else {
                String t = before.toString();
                int start;
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    android.icu.text.BreakIterator bi = android.icu.text.BreakIterator.getCharacterInstance();
                    bi.setText(t);
                    start = bi.preceding(t.length());
                    if (start == android.icu.text.BreakIterator.DONE) start = -1;
                } else {
                    java.text.BreakIterator bi = java.text.BreakIterator.getCharacterInstance();
                    bi.setText(t);
                    start = bi.preceding(t.length());
                    if (start == java.text.BreakIterator.DONE) start = -1;
                }
                int n = start < 0 ? 1 : t.length() - start;
                ic.deleteSurroundingText(Math.max(1, n), 0);
            }
        }
        updateAutoCaps();
    }

    @Override
    public void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null) return;
        if (clipSearch) { finishClipSearch(); return; }
        if (emojiSearch) {
            List<String> res = EmojiSearch.search(this, emojiQuery.toString(), 1);
            if (!res.isEmpty()) onEmoji(res.get(0));
            exitEmojiSearch();
            return;
        }
        finishWord(currentWord());
        lastWord = null;
        corrApplied = null;
        int action = info == null ? 0 : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        // item B week 2: «ماذا كتبت فعلاً مقابل ما سيُرسل» — the small window before
        // the send action when corrections are pending. Sensitive fields never get
        // here with pending edits (the engine is never consulted there).
        if (SendPreview.shouldShow(sentEdits, sensitive)) {
            showSendPreview(action, noAction);
            return;
        }
        sendIt(ic, action, noAction);
    }

    /** The send decision split out so the preview window can reuse it. */
    private void sendIt(InputConnection ic, int action, boolean noAction) {
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    /** «ماذا كتبت فعلاً مقابل ما سيُرسل» — the small window (item B, week 2). */
    private void showSendPreview(final int action, final boolean noAction) {
        String body = SendPreview.build(sentEdits);
        if (body == null) { sendIt(getCurrentInputConnection(), action, noAction); return; }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("ماذا كتبت فعلاً مقابل ما سيُرسل؟"))
                .setMessage(L.t(body))
                .setPositiveButton(L.t("إرسال"), (d, w) -> {
                    sentEdits.clear();
                    InputConnection ic2 = getCurrentInputConnection();
                    if (ic2 != null) sendIt(ic2, action, noAction);
                })
                .setNegativeButton(L.t("تعديل"), null)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.token = view.getWindowToken();
            lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            win.setAttributes(lp);
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        try { dlg.show(); } catch (Exception e) {
            // the window could not attach — never block the send behind a UI failure
            sentEdits.clear();
            InputConnection ic2 = getCurrentInputConnection();
            if (ic2 != null) sendIt(ic2, action, noAction);
        }
    }

    @Override
    public void onShift() {
        if (mode != Layouts.EN) return;
        long now = SystemClock.uptimeMillis();
        if (shift == KeyboardView.SHIFT_LOCK) shift = KeyboardView.SHIFT_OFF;
        else if (now - lastShiftTap < 350) shift = KeyboardView.SHIFT_LOCK;
        else shift = shift == KeyboardView.SHIFT_ON ? KeyboardView.SHIFT_OFF : KeyboardView.SHIFT_ON;
        lastShiftTap = now;
        view.setShiftState(shift);
    }

    @Override
    public void onModeChange(int type) {
        if (type == Key.TO_SYM) mode = Layouts.SYM;
        else if (type == Key.TO_SYM2) mode = Layouts.SYM2;
        else mode = arabic ? Layouts.AR : Layouts.EN;
        applyLayout();
        updateAutoCaps();
        updateSuggestions();
    }

    @Override
    public void onLanguageToggle() {
        arabic = !arabic;
        Theme.prefs(this).edit().putBoolean("arabic", arabic).apply();
        if (mode == Layouts.AR || mode == Layouts.EN) mode = arabic ? Layouts.AR : Layouts.EN;
        shift = KeyboardView.SHIFT_OFF;
        applyLayout();
        updateAutoCaps();
    }

    @Override
    public void onPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    @Override
    public void onSettings() {
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Override
    public void onResize() {
        int lvl = (Theme.heightLevel(this) + 1) % 3;
        Theme.prefs(this).edit().putInt("height", lvl).apply();
        applyPrefs();
    }

    @Override
    public void onClearClips() {
        clipStore.run(new ClipStore.Job<Integer>() {
            @Override public Integer run(ClipRepo r) { return r.clearTemporary(); }
        }, new ClipStore.Done<Integer>() {
            @Override public void done(Integer n) {
                Toast.makeText(NeoKeyService.this, L.t(getString(R.string.nk_neokeyservice_onclearclips_1)), Toast.LENGTH_SHORT).show();
                reloadClips();
            }
        });
    }

    @Override
    public void onClipInsert(final long id) {
        clipStore.run(new ClipStore.Job<String>() {
            @Override public String run(ClipRepo r) {
                String t = r.getText(id);
                if (t != null) r.markUsed(id, System.currentTimeMillis());
                return t;
            }
        }, new ClipStore.Done<String>() {
            @Override public void done(String t) {
                if (t == null) return;
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                ic.finishComposingText();
                ic.commitText(t, 1);
                reloadClips();
            }
        });
    }

    @Override
    public void onCursor(int dir) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // in Arabic text the visual direction is reversed
        int code = (dir > 0) ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        sendDownUpKeyEvents(code);
    }

    @Override
    public void onEdit(int action) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (action) {
            case KeyboardView.E_SELALL: ic.performContextMenuAction(android.R.id.selectAll); break;
            case KeyboardView.E_COPY: ic.performContextMenuAction(android.R.id.copy); break;
            case KeyboardView.E_CUT: ic.performContextMenuAction(android.R.id.cut); break;
            case KeyboardView.E_PASTE: ic.performContextMenuAction(android.R.id.paste); break;
            case KeyboardView.E_UNDO: sendMeta(ic, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON); break;
            case KeyboardView.E_REDO: sendMeta(ic, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON); break;
            case KeyboardView.E_SELECT:
                selecting = !selecting;
                view.setSelecting(selecting);
                break;
            case KeyboardView.E_LEFT: move(ic, KeyEvent.KEYCODE_DPAD_LEFT); break;
            case KeyboardView.E_RIGHT: move(ic, KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case KeyboardView.E_UP: move(ic, KeyEvent.KEYCODE_DPAD_UP); break;
            case KeyboardView.E_DOWN: move(ic, KeyEvent.KEYCODE_DPAD_DOWN); break;
            case KeyboardView.E_HOME: homeEnd(ic, true); break;
            case KeyboardView.E_END: homeEnd(ic, false); break;
            case KeyboardView.E_DELWORD: {
                CharSequence b = ic.getTextBeforeCursor(64, 0);
                if (TextUtils.isEmpty(b)) break;
                int i = b.length();
                while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--;
                while (i > 0 && !Character.isWhitespace(b.charAt(i - 1))) i--;
                ic.deleteSurroundingText(b.length() - i, 0);
                break;
            }
            case KeyboardView.E_NEWLINE: ic.commitText("\n", 1); break;
            default: break;
        }
    }

    private void move(InputConnection ic, int code) {
        sendMeta(ic, code, selecting ? KeyEvent.META_SHIFT_ON : 0);
    }

    private void homeEnd(InputConnection ic, boolean home) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) {
            sendMeta(ic, home ? KeyEvent.KEYCODE_MOVE_HOME : KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON);
            return;
        }
        int len = et.startOffset + et.text.length();
        int s0 = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
        int s1 = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
        if (!selecting) { int p = home ? 0 : len; ic.setSelection(p, p); }
        else if (home) ic.setSelection(0, s1);
        else ic.setSelection(s0, len);
    }

    private void sendMeta(InputConnection ic, int code, int meta) {
        long t = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta));
    }

    @Override
    public void onOneHand(int m) {
        Theme.prefs(this).edit().putInt("onehand", m).apply();
        applyPrefs();
    }

    @Override
    public void onHide() {
        requestHideSelf(0);
    }

    private boolean endsWithBefore(String t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence b = ic.getTextBeforeCursor(t.length(), 0);
        return b != null && b.toString().equals(t);
    }

    @Override
    public void onGlide(String keys) {
        if (emojiSearch || clipSearch) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        Dictionary d = mode == Layouts.AR ? dictAr : dictEn;
        List<String> found = d.glide(keys, 4);
        if (found.isEmpty()) return;
        String best = found.get(0);
        if (mode == Layouts.EN && shift != KeyboardView.SHIFT_OFF)
            best = shift == KeyboardView.SHIFT_LOCK ? best.toUpperCase() : Character.toUpperCase(best.charAt(0)) + best.substring(1);
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        boolean needSpace = b != null && b.length() == 1 && !Character.isWhitespace(b.charAt(0));
        if (decor != Decor.OFF) best = Decor.apply(best, decor);
        ic.commitText((needSpace ? " " : "") + best + " ", 1);
        finishWord(best);
        chars += best.length() + 1;
        glideWord = best + " ";
        glideAlts = new ArrayList<>(found.subList(1, found.size()));
        if (shift == KeyboardView.SHIFT_ON) shift = KeyboardView.SHIFT_OFF;
        updateAutoCaps();
        updateSuggestions();
    }

    @Override
    public void onTool(int id) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean ad = Theme.arabicDigits(this);
        if (id == KeyboardView.TOOL_HIJRI) {
            onText(TextTools.todayArabic(ad));
            return;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (id == KeyboardView.TOOL_INCOGNITO) {
            incognito = !incognito;
            Theme.prefs(this).edit().putBoolean("incognito", incognito).apply();
            view.setIncognito(incognito);
            Toast.makeText(this, incognito ? getString(R.string.nk_neokeyservice_ontext_1)
                    : "تم إيقاف وضع التخفي", Toast.LENGTH_SHORT).show();
            return;
        }
        if (id == KeyboardView.TOOL_WRITER) { openWriter(); return; }
        if (id == KeyboardView.TOOL_DIALECT) {
            // W94 «محوّل اللهجات»: each tap turns the selection (or the line) into the
            // next of فصحى → حجازي → نجدي → خليجي; تراجع restores the original.
            boolean selected = !TextUtils.isEmpty(sel);
            String src = selected ? sel.toString() : currentLine(ic);
            if (src == null || src.trim().isEmpty()) {
                Toast.makeText(this, "اكتب نص أو حدّده أول", Toast.LENGTH_SHORT).show();
                return;
            }
            synEnsure();
            convCol = (convCol + 1) % 4;
            String out = Dialect.synConvert(src, convCol);
            replaceText(ic, src, selected, out);
            Toast.makeText(this, out.equals(src) ? "ما لقيت كلمات أحولها"
                    : "حوّلتها لل" + new String[]{"فصحى", "حجازي", "نجدي", "خليجي"}[convCol], Toast.LENGTH_SHORT).show();
            return;
        }
        if (id >= KeyboardView.TOOL_BIGKEYS && id <= KeyboardView.TOOL_MOTION) {
            String key = id == KeyboardView.TOOL_BIGKEYS ? "bigkeys" : id == KeyboardView.TOOL_CONTRAST ? "contrast" : "reducemotion";
            boolean on = !Theme.prefs(this).getBoolean(key, false);
            Theme.prefs(this).edit().putBoolean(key, on).apply();
            applyPrefs();
            applyLayout();
            String[] names = {"تكبير المفاتيح", "التباين العالي", "تقليل الحركة"};
            Toast.makeText(this, names[id - KeyboardView.TOOL_BIGKEYS] + (on ? ": مفعّل" : ": متوقف"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (id == KeyboardView.TOOL_UNDO) {
            CharSequence b = undoAfter == null ? null : ic.getTextBeforeCursor(undoAfter.length(), 0);
            if (b == null || !b.toString().equals(undoAfter)) {
                Toast.makeText(this, "ما فيه شي أتراجع عنه", Toast.LENGTH_SHORT).show();
                return;
            }
            ic.beginBatchEdit();
            ic.deleteSurroundingText(undoAfter.length(), 0);
            ic.commitText(undoBefore, 1);
            ic.endBatchEdit();
            undoAfter = null;
            return;
        }
        if (id == KeyboardView.TOOL_CASE || id == KeyboardView.TOOL_SEARCH || id == KeyboardView.TOOL_SHARE) {
            boolean selected = !TextUtils.isEmpty(sel);
            String src = selected ? sel.toString()
                    : id == KeyboardView.TOOL_CASE ? currentWord() : currentLine(ic);
            if (src == null || src.trim().isEmpty()) {
                Toast.makeText(this, "اكتب نص أو حدّده أول", Toast.LENGTH_SHORT).show();
                return;
            }
            if (id == KeyboardView.TOOL_CASE) {
                replaceText(ic, src, selected, TextTools.cycleCase(src));
                return;
            }
            try {
                Intent it;
                if (id == KeyboardView.TOOL_SEARCH) {
                    it = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q="
                            + java.net.URLEncoder.encode(src.trim(), "UTF-8")));
                } else {
                    it = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, src.trim()), "مشاركة النص");
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                requestHideSelf(0);
            } catch (Exception e) {
                Toast.makeText(this, "ما قدرت أفتحها", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (id == KeyboardView.TOOL_TRANSLATE || id == KeyboardView.TOOL_SPEAK || id == KeyboardView.TOOL_DIGITS) {
            boolean selected = !TextUtils.isEmpty(sel);
            String src = selected ? sel.toString() : currentLine(ic);
            if (src == null || src.trim().isEmpty()) {
                Toast.makeText(this, "اكتب نص أو حدّده أول", Toast.LENGTH_SHORT).show();
                return;
            }
            if (id == KeyboardView.TOOL_SPEAK) speak(src);
            else if (id == KeyboardView.TOOL_DIGITS) replaceText(ic, src, selected, TextTools.swapDigits(src));
            else translate(src, selected);
            return;
        }
        if (id == KeyboardView.TOOL_FIXLANG) {
            String src;
            boolean selected = !TextUtils.isEmpty(sel);
            if (selected) src = sel.toString();
            else {
                CharSequence b = ic.getTextBeforeCursor(120, 0);
                if (b == null || b.length() == 0) return;
                String t = b.toString();
                int nl = t.lastIndexOf('\n');
                src = t.substring(nl + 1);
                if (src.trim().isEmpty()) return;
            }
            String fixed = TextTools.fixLayout(src);
            boolean toArabic = TextTools.isMostlyLatin(src);
            replaceText(ic, src, selected, fixed);
            if (toArabic != arabic) onLanguageToggle();
            return;
        }
        if (id == KeyboardView.TOOL_CALC) {
            String src = !TextUtils.isEmpty(sel) ? sel.toString() : String.valueOf(ic.getTextBeforeCursor(80, 0));
            String expr = TextTools.trailingExpr(src);
            String res = expr == null ? null : TextTools.evaluate(expr, ad);
            if (res == null) {
                Toast.makeText(this, getString(R.string.nk_neokeyservice_replacetext_1), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.isEmpty(sel)) {
                ic.commitText(sel + " = " + res, 1);
            } else {
                CharSequence b = ic.getTextBeforeCursor(1, 0);
                boolean endsEq = src.trim().endsWith("=");
                ic.commitText(endsEq ? (b != null && b.toString().equals(" ") ? "" : " ") + res : " = " + res, 1);
            }
        }
    }

    private String currentLine(InputConnection ic) {
        CharSequence b = ic.getTextBeforeCursor(400, 0);
        if (b == null) return null;
        String t = b.toString();
        return t.substring(t.lastIndexOf('\n') + 1);
    }

    private java.util.Map<String, String> pairsAsset(String name) {
        try { return Dialect.pairs(new String(assetBytes(name), "UTF-8")); }
        catch (Exception x) { return Dialect.pairs(null); }
    }

    private void synEnsure() {
        if (Dialect.synReady()) return;
        try { Dialect.synLoad(new String(assetBytes("dialect_synonyms.txt"), "UTF-8")); }
        catch (Exception x) { Dialect.synLoad(null); }
    }

    private void replaceText(InputConnection ic, String src, boolean selected, String out) {
        undoBefore = src;
        undoAfter = out;
        ic.beginBatchEdit();
        if (!selected) ic.deleteSurroundingText(src.length(), 0);
        ic.commitText(out, 1);
        ic.endBatchEdit();
    }

    private void translate(final String src, final boolean selected) {
        final boolean toEn = !TextTools.isMostlyLatin(src);
        Toast.makeText(this, toEn ? "جاري الترجمة للإنجليزي…" : "جاري الترجمة للعربي…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String out = null;
            try {
                String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                        + (toEn ? "en" : "ar") + "&dt=t&q=" + java.net.URLEncoder.encode(src.trim(), "UTF-8");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                org.json.JSONArray parts = new org.json.JSONArray(bo.toString("UTF-8")).getJSONArray(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONArray(i).optString(0, ""));
                out = sb.toString();
            } catch (Exception ignored) { }
            final String result = out;
            main.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (result == null || result.isEmpty() || ic == null) {
                    Toast.makeText(this, getString(R.string.nk_neokeyservice_thread_1), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selected) {
                    CharSequence cur = ic.getSelectedText(0);
                    if (cur == null || !cur.toString().equals(src)) return;
                    undoBefore = src;
                    undoAfter = result;
                    ic.commitText(result, 1);
                } else {
                    CharSequence b = ic.getTextBeforeCursor(src.length(), 0);
                    if (b == null || !b.toString().equals(src)) return;
                    replaceText(ic, src, false, result);
                }
            });
        }).start();
    }

    private void speak(final String text) {
        final Locale loc = TextTools.isMostlyLatin(text) ? Locale.US : new Locale("ar");
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) doSpeak(text, loc);
                else Toast.makeText(this, "محرك النطق غير متوفر", Toast.LENGTH_SHORT).show();
            });
        } else if (ttsReady) doSpeak(text, loc);
    }

    private void doSpeak(String text, Locale loc) {
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, getString(R.string.nk_neokeyservice_dospeak_1), Toast.LENGTH_LONG).show();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neokey");
    }

    @Override
    public void onClipChipMenu() {
        final String t = freshClip;
        if (t == null || clipStore == null) { view.showPanel(KeyboardView.PANEL_CLIP); return; }
        clipStore.run(new ClipStore.Job<ClipItem>() {
            @Override public ClipItem run(ClipRepo r) {
                long id = r.findSame(t, ClipRepo.hash(t));
                return id < 0 ? null : r.item(id);
            }
        }, new ClipStore.Done<ClipItem>() {
            @Override public void done(ClipItem it) {
                if (view == null) return;
                if (it == null) view.showPanel(KeyboardView.PANEL_CLIP);
                else view.openClipMenu(it);
            }
        });
    }

    @Override
    public void onClipTab(int tab) {
        clipTab = tab;
        clipCat = 0;
        clipLimit = 60;
        reloadClips();
    }

    @Override
    public void onClipCategory(long cat) {
        clipCat = cat;
        clipLimit = 60;
        reloadClips();
    }

    @Override
    public void onClipMore() {
        if (!clipMore) return;
        clipLimit += 60;
        reloadClips();
    }

    @Override
    public void onClipMovePin(final long id, final int index) {
        clipStore.run(new ClipStore.Job<Boolean>() {
            @Override public Boolean run(ClipRepo r) { r.movePin(id, index); return true; }
        }, new ClipStore.Done<Boolean>() {
            @Override public void done(Boolean b) { reloadClips(); }
        });
    }

    @Override
    public void onClipSearch() {
        clipSearch = true;
        clipBuf.setLength(0);
        if (clipQuery != null) clipBuf.append(clipQuery);
        clipSearchHits = -1;
        countClipSearch();
    }

    @Override
    public void onClipClearSearch() {
        clipQuery = null;
        clipLimit = 60;
        reloadClips();
    }

    /** Live result count while typing; only the newest query's answer is shown. */
    private void countClipSearch() {
        final String q = clipBuf.toString();
        final int seq = ++clipSearchSeq;
        final int tab = clipTab;
        final long cat = clipCat;
        updateSuggestions();
        if (q.trim().isEmpty()) return;
        clipStore.run(new ClipStore.Job<Integer>() {
            @Override public Integer run(ClipRepo r) { return seq != clipSearchSeq ? null : r.searchCount(tab, cat, q); }
        }, new ClipStore.Done<Integer>() {
            @Override public void done(Integer n) {
                if (n == null || seq != clipSearchSeq || !clipSearch) return;
                clipSearchHits = n;
                updateSuggestions();
            }
        });
    }

    private void finishClipSearch() {
        String q = clipBuf.toString().trim();
        clipSearch = false;
        clipQuery = q.isEmpty() ? null : q;
        clipLimit = 60;
        view.showPanel(KeyboardView.PANEL_CLIP);
        updateSuggestions();
        reloadClips();
    }

    @Override
    public void onClipAction(final long id, int action) {
        switch (action) {
            case KeyboardView.CA_INSERT: onClipInsert(id); return;
            case KeyboardView.CA_PIN: onPinClip(id); return;
            case KeyboardView.CA_EXPORT: {
                Intent i = new Intent(this, ClipIOActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra(ClipIOActivity.EXTRA_MODE, ClipIOActivity.MODE_EXPORT);
                i.putExtra(ClipIOActivity.EXTRA_IDS, new long[]{id});
                requestHideSelf(0);
                startActivity(i);
                return;
            }
            case KeyboardView.CA_EDIT: {
                Intent i = new Intent(this, ClipEditActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra(ClipEditActivity.EXTRA_ID, id);
                requestHideSelf(0);
                startActivity(i);
                return;
            }
            case KeyboardView.CA_COPY:
            case KeyboardView.CA_SHARE: {
                final boolean share = action == KeyboardView.CA_SHARE;
                clipStore.run(new ClipStore.Job<String>() {
                    @Override public String run(ClipRepo r) {
                        String t = r.getText(id);
                        if (t != null) r.markUsed(id, System.currentTimeMillis());
                        return t;
                    }
                }, new ClipStore.Done<String>() {
                    @Override public void done(String t) {
                        if (t == null) return;
                        if (share) shareText(t);
                        else {
                            lastCaptured = t;
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NeoKey", t));
                            if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(NeoKeyService.this, L.t("انسخ"), Toast.LENGTH_SHORT).show();
                        }
                        reloadClips();
                    }
                });
                return;
            }
            default: break;
        }
        final int a = action;
        clipStore.run(new ClipStore.Job<String>() {
            @Override public String run(ClipRepo r) {
                ClipItem it = r.item(id);
                if (it == null) return null;
                switch (a) {
                    case KeyboardView.CA_FAV: r.setFav(id, !it.fav); return it.fav ? "أُزيل من المفضلة" : "أُضيف للمفضلة";
                    case KeyboardView.CA_ARCHIVE: r.setArchived(id, !it.archived); return it.archived ? "رجع من الأرشيف" : "انتقل للأرشيف";
                    case KeyboardView.CA_DELETE: r.delete(id); return "انحذف";
                    case KeyboardView.CA_TEMPLATE: return r.makeTemplate(id, System.currentTimeMillis()) > 0 ? "انحفظ كقالب في تبويب القوالب" : "";
                    default: return "";
                }
            }
        }, new ClipStore.Done<String>() {
            @Override public void done(String msg) {
                if (msg != null && !msg.isEmpty()) Toast.makeText(NeoKeyService.this, L.t(msg), Toast.LENGTH_SHORT).show();
                reloadClips();
            }
        });
    }

    private void shareText(String t) {
        if (t.length() > 100_000) {
            // binder transactions cap around 1 MB; very long texts are copied or exported instead
            toastL(getString(R.string.nk_neokeyservice_sharetext_1));
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, t);
        Intent ch = Intent.createChooser(send, L.t("مشاركة النص"));
        ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(ch); } catch (Exception e) {
            toast("ما فيه تطبيق للمشاركة");
        }
    }

    @Override
    public void onPinClip(final long id) {
        clipStore.run(new ClipStore.Job<Integer>() {
            @Override public Integer run(ClipRepo r) {
                ClipItem it = r.item(id);
                if (it == null) return ClipRepo.PIN_MISSING;
                if (it.pinned) { r.unpin(id); return -1; }
                return r.pin(id);
            }
        }, new ClipStore.Done<Integer>() {
            @Override public void done(Integer res) {
                if (res == null) return;
                if (res == ClipRepo.PIN_FULL) { askReplacePin(id); return; }
                if (res == ClipRepo.PIN_OK) Toast.makeText(NeoKeyService.this, L.t("تم التثبيت"), Toast.LENGTH_SHORT).show();
                else if (res == -1) Toast.makeText(NeoKeyService.this, L.t("أُلغي التثبيت"), Toast.LENGTH_SHORT).show();
                reloadClips();
            }
        });
    }

    /** 101st pin: offer to give up the last pinned item, or cancel. */
    private void askReplacePin(final long id) {
        if (view == null || view.getWindowToken() == null) {
            toastL("وصلت للحد الأقصى ١٠٠ مثبّت");
            return;
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("المثبّتات ممتلئة ١٠٠/١٠٠"))
                .setMessage(L.t(getString(R.string.nk_neokeyservice_askreplacepin_1)))
                .setPositiveButton(L.t("استبدال"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        clipStore.run(new ClipStore.Job<Integer>() {
                            @Override public Integer run(ClipRepo r) { return r.replacePin(r.lastPin(), id); }
                        }, new ClipStore.Done<Integer>() {
                            @Override public void done(Integer x) { reloadClips(); }
                        });
                    }
                })
                .setNegativeButton(L.t("إلغاء"), null)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.token = view.getWindowToken();
            lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            win.setAttributes(lp);
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        try { dlg.show(); } catch (Exception e) {
            toastL("وصلت للحد الأقصى ١٠٠ مثبّت");
        }
    }

    @Override
    public void onDecor(int style) {
        if (style >= Decor.COUNT) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            String[] f = Decor.FRAMES[style - Decor.COUNT];
            ic.beginBatchEdit();
            ic.finishComposingText();
            CharSequence sel = ic.getSelectedText(0);
            if (sel != null && sel.length() > 0) ic.commitText(f[0] + sel + f[1], 1);
            else { ic.commitText(f[0], 1); ic.commitText(f[1], 0); }
            ic.endBatchEdit();
            updateSuggestions();
            return;
        }
        decor = style;
        view.setDecor(style);
        updateSuggestions();
    }

    // ------------------------------------------------------------ voice typing

    @Override
    public void onVoice() {
        if (listening) { stopVoice(); return; }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent i = new Intent(this, SetupActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("mic", true);
            startActivity(i);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.nk_neokeyservice_startactivity_1), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(voiceListener);
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            // W91: the recognizer hears the active dialect — Gulf speech asks ar-AE
            // (ar-SA stays for Hijazi/Najdi/MSA), and the N-best feeds voicePick.
            voiceDialect = arabic ? activeDialectId() : null;
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, !arabic ? "en-US" : "ar_gulf".equals(voiceDialect) ? "ar-AE" : "ar-SA");
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizer.startListening(it);
            listening = true;
            view.setVoice(1, "");
        } catch (Exception e) {
            stopVoice();
        }
    }

    private void stopVoice() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (view != null) view.setVoice(0, "");
    }

    private void commitVoice(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null || text.trim().isEmpty()) return;
        CharSequence b = ic.getTextBeforeCursor(1, 0);
        boolean needSpace = b != null && b.length() == 1 && !Character.isWhitespace(b.charAt(0));
        ic.commitText((needSpace ? " " : "") + text.trim() + " ", 1);
        chars += text.length();
        words += text.trim().split("\\s+").length;
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { if (view != null) view.setVoice(1, ""); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] bytes) { }
        @Override public void onEndOfSpeech() { if (view != null) view.setVoice(2, ""); }
        @Override public void onError(int error) {
            String msg;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "ما سمعت شي، جرّب مرة ثانية"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "الكتابة بالصوت تحتاج إنترنت"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "اسمح بالمايك من التطبيق"; break;
                default: msg = null;
            }
            if (msg != null) Toast.makeText(NeoKeyService.this, msg, Toast.LENGTH_SHORT).show();
            stopVoice();
        }
        @Override public void onResults(Bundle b) {
            List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) commitVoice(Correction2.voicePick(r, dictAr,
                    voiceDialect == null ? null : dialectDict(voiceDialect)));
            stopVoice();
        }
        @Override public void onPartialResults(Bundle b) {
            List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && view != null) view.setVoice(1, r.get(0));
        }
        @Override public void onEvent(int t, Bundle b) { }
    };

    private void flushStats() {
        if (words == 0 && chars == 0) return;
        SharedPreferences p = Theme.prefs(this);
        p.edit().putInt("stat_words", p.getInt("stat_words", 0) + words)
                .putInt("stat_chars", p.getInt("stat_chars", 0) + chars).apply();
        words = chars = 0;
    }
}
