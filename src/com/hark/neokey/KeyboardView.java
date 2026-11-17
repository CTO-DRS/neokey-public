package com.hark.neokey;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/** Custom-drawn keyboard: toolbar/suggestions, number row, letters, emoji, clipboard, phrases, editing, one-handed. */
public class KeyboardView extends View {

    public interface Listener {
        void onText(String text);
        void onEmoji(String emoji);
        void onSuggestion(String word);
        void onSuggestionLong(String word);   // W56: long-press = the root family tree (docs/ROOT.md §5)
        void onDelete();
        void onEnter();
        void onShift();
        void onModeChange(int type);
        void onLanguageToggle();
        void onPicker();
        void onSettings();
        void onResize();
        void onClearClips();
        void onCursor(int dir);
        void onEdit(int action);
        void onOneHand(int mode);
        void onHide();
        void onVoice();
        void onPinClip(long id);
        void onClipInsert(long id);
        void onClipTab(int tab);
        void onClipSearch();
        void onClipClearSearch();
        void onClipAction(long id, int action);
        void onClipChipMenu();
        void onClipCategory(long cat);
        void onClipMore();
        void onClipMovePin(long id, int index);
        void onDecor(int style);
        void onGlide(String keys);
        void onTool(int id);
        void onEmojiSearch();
    }

    static final int SHIFT_OFF = 0, SHIFT_ON = 1, SHIFT_LOCK = 2;
    static final int PANEL_KEYS = 0, PANEL_EMOJI = 1, PANEL_CLIP = 2, PANEL_MORE = 3, PANEL_EDIT = 4, PANEL_PHRASES = 5, PANEL_DECOR = 6;
    static final int ENTER_NEWLINE = 0, ENTER_DONE = 1, ENTER_SEARCH = 2, ENTER_SEND = 3, ENTER_GO = 4;

    // edit actions
    static final int E_SELALL = 0, E_COPY = 1, E_CUT = 2, E_PASTE = 3, E_HOME = 4, E_UP = 5, E_END = 6, E_UNDO = 7,
            E_LEFT = 8, E_SELECT = 9, E_RIGHT = 10, E_REDO = 11, E_DELWORD = 12, E_DOWN = 13, E_NEWLINE = 14;

    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private Listener listener;

    private Theme theme = Theme.ALL[0];
    private Bitmap photo;
    private int photoDim = 45;
    private int animBg = -1;
    private Sounds sounds;
    private int soundPack = 0;
    private float soundVol = 0.5f;
    private final PressFx pfx = new PressFx();
    private int fxType = -1;

    /** Key sound pack (0 = system) and volume 0..100. */
    public void setSoundStyle(int pack, int volPct) {
        soundPack = pack;
        soundVol = Math.max(0.05f, Math.min(1f, volPct / 100f));
        if (pack > 0) { if (sounds == null) sounds = new Sounds(getContext()); sounds.setPack(pack); }
    }

    /** Press effect type (-1 = none). */
    public void setPressFx(int t) { fxType = t; }                 // animated background chosen for the photo theme
    private final long animStart = SystemClock.uptimeMillis();
    private boolean animBgOn;

    /** Animated background for the photo theme (-1 = use the picture). */
    public void setAnimBg(int i) { if (i != animBg) { animBg = i; invalidate(); } }
    private Bitmap avatar;
    // two facing arrows that stay at the toolbar edges; each opens 5 quick features in the middle strip
    private boolean arrowsOn;
    private int[] arrowRight = Features.parse(null, Features.DEF_RIGHT), arrowLeft = Features.parse(null, Features.DEF_LEFT);
    private int expanded;               // 0 none, 1 right arrow open, 2 left arrow open
    private float expandT = 1f, flipR, flipL;
    private float midL, midR;
    private final Key arrowKeyR = Key.tool(Key.T_ARROW_R), arrowKeyL = Key.tool(Key.T_ARROW_L);
    private final List<Key> featKeys = new ArrayList<>();
    private BitmapShader avatarShader;
    private final android.graphics.Matrix avatarM = new android.graphics.Matrix();
    private final Paint avatarP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private List<List<Key>> keyRows;
    private final List<List<Key>> emojiRows = new ArrayList<>();
    private final List<List<Key>> moreRows = new ArrayList<>();
    private final List<List<Key>> editRows = new ArrayList<>();
    private final List<List<Key>> phraseRows = new ArrayList<>();
    private final List<List<Key>> decorRows = new ArrayList<>();
    private java.util.Set<String> pins = new java.util.HashSet<>();
    private int voiceState, decor;
    private String voiceText = "", decorName;
    private float keyScale = 1f;
    private boolean glideOn = true, glideCand, gliding;
    private float gDownY;
    private Key gLast;
    private final StringBuilder gSeq = new StringBuilder();
    private final android.graphics.Path trail = new android.graphics.Path();
    private final Paint trailP = new Paint(Paint.ANTI_ALIAS_FLAG);
    public static final int TOOL_FIXLANG = 0, TOOL_CALC = 1, TOOL_HIJRI = 2, TOOL_TRANSLATE = 3, TOOL_SPEAK = 4, TOOL_DIGITS = 5,
            TOOL_INCOGNITO = 6, TOOL_CASE = 7, TOOL_UNDO = 8, TOOL_SEARCH = 9, TOOL_SHARE = 10,
            TOOL_WRITER = 11, TOOL_BIGKEYS = 12, TOOL_CONTRAST = 13, TOOL_MOTION = 14, TOOL_DIALECT = 15;
    private final List<Key> toolbar = new ArrayList<>();
    private final List<Key> suggKeys = new ArrayList<>();
    static final String CLIP_MARK = "\uE000";
    static final String CLIP_SC_MARK = "\uE001";   // W48: a clip-shortcut expansion suggestion (mark + token)
    private final List<Key> clipKeys = new ArrayList<>();
    private final List<Key> clipBottom = new ArrayList<>();
    private final List<Key> sideKeys = new ArrayList<>();
    private List<ClipItem> clips = new ArrayList<>();
    private int clipTab = 0, clipPinCount = 0;
    private String clipQuery;           // null = no search filter
    private ClipItem clipMenu;          // item whose action menu is open
    private boolean clipDelArm;         // delete needs a second tap
    static final int CA_INSERT = 1, CA_COPY = 2, CA_PIN = 4, CA_FAV = 5, CA_ARCHIVE = 6, CA_SHARE = 8,
            CA_DELETE = 9, CA_BACK = 12, CA_EDIT = 3, CA_TEMPLATE = 13, CA_EXPORT = 14;
    // W48: the pin-reorder action keys are gone from the action sheet — pins reorder by
    // drag on the pinned tab (the W48 «بالسحب بدل ▲▼» law; ci/cliplib_gate.sh guards it).
    private List<ClipRepo.Cat> clipCats = new ArrayList<>();
    private long clipCat;
    private boolean clipHasMore, clipPendingNext;
    private Key dragKey;                // pinned card being dragged
    private boolean dragMoved;
    private float dragX, dragY, downY;
    private final Paint dragP = new Paint(Paint.ANTI_ALIAS_FLAG);
    { dragP.setStyle(Paint.Style.STROKE); }
    private static final int CLIP_PER_PAGE = 6;
    private List<String> recents = new ArrayList<>();
    private List<String> phrases = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private int panel = PANEL_KEYS;
    private int emojiTab = 1, phrasePage = 0, clipPage = 0, decorPage = 0, emojiPage = 0;
    private boolean hasNumberRow = true, selecting, rtl = true;
    private int shiftState = SHIFT_OFF;
    private int enterKind = ENTER_NEWLINE;
    private boolean haptics = true, popups = true, sound = false, swipeCursor = false;
    private int heightLevel = 100, oneHand = 0;

    // geometry
    private float toolH, areaH, gap, radius, cornerR, scale = 1f;
    private int insetL, insetR, insetB;
    private float contentL, contentR, fullL, fullR;

    // paints
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path bgPath = new Path();
    private final RectF tmp = new RectF();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private Shader auroraA, auroraB, auroraC, gradient, hyperEdge;
    private int curType = -1;          // type of the key whose body is being drawn (hyper style)
    private static final int H_PURPLE = 0xFFA855F7, H_INDIGO = 0xFF6366F1, H_CYAN = 0xFF22D3EE;

    // touch
    private Key active;
    private int activePointer = -1;
    private float downX, lastX;
    private boolean swiped, longPressed;
    private String[] altList;
    private int altSel;
    private final RectF altRect = new RectF();
    private long altAt;               // W118: when the alternates popup opened
    private float altPill = -1f;      // W118: sliding selection pill (cell index, animated)
    private float fingerX;            // W118: live finger x (cursor glide / erase trail)
    private int repN;                 // W118: delete repeats in this hold
    private long lastFrame;

    private final Runnable longPress = new Runnable() {
        @Override public void run() { onLongPress(); }
    };
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (active == null) return;
            if (active.type == Key.DEL) listener.onDelete();
            else if (active.type == Key.EDIT) listener.onEdit(active.id);
            else return;
            buzz();
            repN++;
            invalidate();
            handler.postDelayed(this, active.type == Key.DEL ? Motion.repeatGap(repN) : 70);
        }
    };

    public KeyboardView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        strokeP.setStyle(Paint.Style.STROKE);
        iconP.setStyle(Paint.Style.STROKE);
        iconP.setStrokeCap(Paint.Cap.ROUND);
        iconP.setStrokeJoin(Paint.Join.ROUND);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTypeface(face(Typeface.NORMAL));
        hintP.setTextAlign(Paint.Align.CENTER);
        for (int id = 0; id < 6; id++) toolbar.add(Key.tool(id));
        buildEmojiRows();
        buildMoreRows();
        buildEditRows();
        buildDecorRows();
        computeGeometry(getResources().getDisplayMetrics().widthPixels);
    }

    // ------------------------------------------------------------------ public API

    public void setListener(Listener l) { listener = l; }

    public void setKeys(List<List<Key>> rows, boolean numberRow, boolean rtl) {
        boolean ex = false;
        for (List<Key> r : rows) if (!r.isEmpty() && r.get(0).rowW == Layouts.EXTRA_W) ex = true;
        if (ex != extraRow) {
            extraRow = ex;
            requestLayout();
            computeGeometry(getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels);
        }
        keyRows = rows;
        stripKey = null;   // W117: the suggestion strip, when the layout carries one
        for (List<Key> r : rows) for (Key k : r) if (k.type == Key.LABEL && k.id == Key.STRIP) stripKey = k;
        post(this::a11yChanged);
        hasNumberRow = numberRow;
        this.rtl = rtl;
        active = null;
        altList = null;
        if (panel == PANEL_EMOJI || panel == PANEL_CLIP || panel == PANEL_MORE) panel = PANEL_KEYS;
        layoutAll();
        invalidate();
    }

    private Typeface face(int style) { return Theme.face(theme == null ? 0 : theme.font, style); }

    private boolean pressGlow = true, anims = true, extraRow;
    private Key stripKey;                       // W117 suggestion strip row (null = suggestions in the toolbar)
    private final Path inkPath = new Path();
    private int gapLevel = 1, radiusDp = 0, widthPct = 100;
    private float panelT = 1f;
    private final android.graphics.Matrix shaderM = new android.graphics.Matrix();
    private LinearGradient glassFill, glassEdge;

    private boolean cues = true, bigKeys, contrastOn, reduceMotion;
    private int oneHandPct = 82, vibMs = 20, vibAmp = 160;
    private long showMark, touchMark;

    /** Accessibility: non-colour state cues, one-hand width, custom vibration, quick-toggle states. */
    public void setAccess(boolean cues, int oneHandPct, int vibMs, int vibAmp, boolean big, boolean contrast, boolean reduce) {
        this.cues = cues;
        this.vibMs = vibMs;
        this.vibAmp = vibAmp;
        boolean rebuild = big != bigKeys || contrast != contrastOn || reduce != reduceMotion;
        bigKeys = big; contrastOn = contrast; reduceMotion = reduce;
        if (rebuild) buildMoreRows();
        if (oneHandPct != this.oneHandPct) {
            this.oneHandPct = oneHandPct;
            computeGeometry(getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels);
        }
        layoutAll();
        invalidate();
    }

    /** Marks the moment the keyboard was asked to show; the next frame records the show time. */
    public void markShow() { showMark = SystemClock.uptimeMillis(); invalidate(); }

    // ---- W116 «لمسة NeoKey»: entrance wave, spring release, breathing Enter, flow line, space shimmer, key sheen.
    private boolean sig = true, hasText;
    private long openAt, lastType;
    private float flow;
    private LinearGradient sheen;

    public void setSignature(boolean on) { if (on != sig) { sig = on; invalidate(); } }

    private boolean motionOn() { return sig && anims && !reduceMotion; }

    /** The keyboard window just appeared: play the entrance wave and the space-bar sweep. */
    public void playOpen() {
        flow = 0;
        if (!motionOn()) { openAt = 0; return; }
        openAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Whether the field holds text before the cursor (drives the breathing Enter). */
    public void setHasText(boolean t) {
        if (t == hasText) return;
        hasText = t;
        if (t && sig) invalidate();
    }

    private void noteType() {
        long now = SystemClock.uptimeMillis();
        if (motionOn()) flow = Motion.flowHit(Motion.flowDecay(flow, lastType == 0 ? 9999 : now - lastType),
                lastType == 0 ? 9999 : now - lastType);
        lastType = now;
    }

    private void drawFlow(Canvas c, long now) {
        float lv = Motion.flowDecay(flow, now - lastType);
        if (lv < 0.02f) return;
        float w = getWidth(), half = w * 0.46f * lv, cx = w / 2f, y = toolH - 1.2f * dp;
        int a = theme.enter, b = theme.neon ? theme.glow : blend(theme.enter, 0xFFFFFFFF, 0.35f);
        fillP.setShader(new LinearGradient(cx - half, 0, cx + half, 0,
                new int[]{0, Theme.withAlpha(a, (int) (0xE0 * lv)), Theme.withAlpha(b, 0xFF), Theme.withAlpha(a, (int) (0xE0 * lv)), 0},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        tmp.set(cx - half, y - 1f * dp, cx + half, y + 1f * dp);
        c.drawRoundRect(tmp, dp, dp, fillP);
        fillP.setAlpha((int) (0x55 * lv));
        tmp.inset(0, -2.5f * dp);
        c.drawRoundRect(tmp, 3 * dp, 3 * dp, fillP);
        fillP.setAlpha(255);
        fillP.setShader(null);
    }

    private void drawBreath(Canvas c, Key k, float lv) {
        RectF r = k.rect;
        float grow = (1.5f + 3.5f * lv) * dp;
        tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow);
        strokeP.setStrokeWidth((1.2f + 1.6f * lv) * dp);
        strokeP.setColor(Theme.withAlpha(theme.neon ? theme.glow : theme.enter, (int) (0x90 * (1 - 0.55f * lv))));
        c.drawRoundRect(tmp, radius + grow, radius + grow, strokeP);
    }

    private void drawShimmer(Canvas c, RectF r, float x) {
        float band = r.height() * 1.4f, left = r.left - band + x * (r.width() + band);
        c.save();
        tmp.set(r);
        sigPath.reset();
        sigPath.addRoundRect(tmp, radius, radius, android.graphics.Path.Direction.CW);
        c.clipPath(sigPath);
        fillP.setShader(new LinearGradient(left, r.top, left + band, r.bottom,
                new int[]{0, 0x66FFFFFF, 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(r, fillP);
        fillP.setShader(null);
        c.restore();
    }
    private final android.graphics.Path sigPath = new android.graphics.Path();

    /** Visual extras: press light, transitions, key spacing (0-3), corner radius (0 = by shape), width %. */
    public void setLook(boolean pressGlow, boolean anims, int gapLevel, int radiusDp, int widthPct) {
        this.pressGlow = pressGlow;
        this.anims = anims;
        boolean re = gapLevel != this.gapLevel || radiusDp != this.radiusDp || widthPct != this.widthPct;
        this.gapLevel = gapLevel;
        this.radiusDp = radiusDp;
        this.widthPct = widthPct;
        if (re) {
            requestLayout();
            computeGeometry(getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels);
            layoutAll();
        }
        invalidate();
    }

    public void setTheme(Theme t, Bitmap photo, int dim) {
        theme = t;
        textP.setTypeface(face(Typeface.NORMAL));
        hintP.setTypeface(face(Typeface.NORMAL));
        this.photo = t.photo ? photo : null;
        photoDim = dim;
        buildShaders();
        invalidate();
    }

    /** Personal avatar shown at the start of the toolbar; null hides it. */
    public void setAvatar(Bitmap b) {
        if (b == avatar) return;
        avatar = b;
        avatarShader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Key ak = null;
        for (Key k : toolbar) if (k.id == Key.T_AVATAR) ak = k;
        if (b != null && ak == null) toolbar.add(Key.tool(Key.T_AVATAR));
        else if (b == null && ak != null) toolbar.remove(ak);
        layoutAll();
        invalidate();
    }

    private void drawAvatar(Canvas c, Key k) {
        if (avatar == null) return;
        float cx = k.rect.centerX(), cy = k.rect.centerY();
        float r = Math.min(k.rect.height() * 0.36f, k.rect.width() * 0.4f) * (1f - 0.06f * k.press);
        // soft glow
        fillP.setColor(Theme.withAlpha(theme.enter, 0x2A));
        c.drawCircle(cx, cy, r + 4.5f * dp, fillP);
        // gradient ring
        int a = theme.enter, b = theme.neon ? theme.glow2 : 0xFF8B5CF6;
        strokeP.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{a, b, 0xFF22D3EE, a}, null));
        strokeP.setStrokeWidth(2.2f * dp);
        c.drawCircle(cx, cy, r + 1.6f * dp, strokeP);
        strokeP.setShader(null);
        // photo
        float sc = (2 * r) / Math.min(avatar.getWidth(), avatar.getHeight());
        avatarM.setScale(sc, sc);
        avatarM.postTranslate(cx - avatar.getWidth() * sc / 2f, cy - avatar.getHeight() * sc / 2f);
        avatarShader.setLocalMatrix(avatarM);
        avatarP.setShader(avatarShader);
        c.drawCircle(cx, cy, r, avatarP);
        // verified-style badge
        float br = r * 0.34f, bx = cx + r * 0.72f, by = cy + r * 0.72f;
        fillP.setColor(theme.photo ? 0xFF222222 : theme.bg);
        c.drawCircle(bx, by, br + 1.4f * dp, fillP);
        fillP.setColor(theme.enter);
        c.drawCircle(bx, by, br, fillP);
        iconP.setColor(0xFFFFFFFF);
        iconP.setStrokeWidth(1.5f * dp);
        c.drawLine(bx - br * 0.45f, by + br * 0.02f, bx - br * 0.1f, by + br * 0.38f, iconP);
        c.drawLine(bx - br * 0.1f, by + br * 0.38f, bx + br * 0.5f, by - br * 0.35f, iconP);
    }

    public void setPrefs(boolean haptics, boolean popups, int heightLevel, boolean sound, boolean swipeCursor, int oneHand) {
        this.haptics = haptics;
        this.popups = popups;
        this.sound = sound;
        this.swipeCursor = swipeCursor;
        boolean relayout = heightLevel != this.heightLevel || oneHand != this.oneHand;
        this.heightLevel = heightLevel;
        this.oneHand = oneHand;
        if (relayout) {
            requestLayout();
            computeGeometry(getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels);
            layoutAll();
            invalidate();
        }
    }

    public void setClips(List<ClipItem> c, int tab, String query, int pinCount, List<ClipRepo.Cat> cats, long cat, boolean more) {
        if (tab != clipTab || cat != clipCat) clipPage = 0;
        clips = c;
        clipCats = cats;
        clipCat = cat;
        clipHasMore = more;
        if (clipPendingNext) {
            clipPendingNext = false;
            int pages = Math.max(1, (c.size() + CLIP_PER_PAGE - 1) / CLIP_PER_PAGE);
            if (clipPage + 1 < pages) clipPage++;
        }
        clipTab = tab;
        clipQuery = query;
        clipPinCount = pinCount;
        if (clipMenu != null) {
            ClipItem fresh = null;
            for (ClipItem it : c) if (it.id == clipMenu.id) fresh = it;
            if (fresh != null) clipMenu = fresh;
        }
        if (panel == PANEL_CLIP) { layoutAll(); invalidate(); }
    }

    public void setRecents(List<String> r) {
        recents = r;
        if (emojiTab == 0) { buildEmojiRows(); layoutAll(); invalidate(); }
    }

    public void setPhrases(List<String> p) {
        phrases = p;
        buildPhraseRows();
        layoutAll();
    }

    private boolean emojiStrip;

    /** Emoji search strip: a query chip followed by emoji results. */
    public void setEmojiResults(String chip, List<String> emojis) {
        List<String> l = new ArrayList<>();
        l.add(chip);
        l.addAll(emojis);
        suggestions = l;
        emojiStrip = true;
        layoutSuggestions();
        invalidate();
    }

    public void setSuggestions(List<String> s) {
        emojiStrip = false;
        suggestions = s;
        layoutSuggestions();
        invalidate();
        a11yChanged();
    }

    public void setShiftState(int s) { if (s != shiftState) { shiftState = s; invalidate(); } }

    public void setEnterKind(int k) { enterKind = k; invalidate(); }

    public void setSelecting(boolean s) { selecting = s; invalidate(); }


    public void setKeyScale(float f) { keyScale = f; invalidate(); }

    private int keyShape;
    /** 0 rounded, 1 square, 2 pill, 3 soft. */
    public void setKeyShape(int s) { keyShape = s; requestLayout(); invalidate(); }

    public void setGlide(boolean on) { glideOn = on; }

    /** 0 = idle, 1 = listening, 2 = processing */
    public void setVoice(int state, String text) {
        voiceState = state;
        voiceText = text == null ? "" : text;
        invalidate();
    }

    private boolean incognito;

    public void setIncognito(boolean on) {
        if (incognito == on) return;
        incognito = on;
        buildMoreRows();
        layoutAll();
        invalidate();
    }

    public void setDecor(int style) {
        decor = style;
        decorName = style == Decor.OFF ? null : Decor.NAMES[style];
        buildDecorRows();
        layoutAll();
        invalidate();
    }

    public void showPanel(int p) {
        if (p != panel && anims) { panelT = 0f; lastFrame = 0; }
        if (p == PANEL_CLIP && p != panel) clipSig = Long.MIN_VALUE;   // W121: opening the clipboard deals the cards in
        panel = p;
        active = null;
        altList = null;
        clipMenu = null;
        clipDelArm = false;
        if (p == PANEL_EMOJI && emojiTab == 0 && recents.isEmpty()) { emojiTab = 1; buildEmojiRows(); }
        if (p == PANEL_PHRASES) buildPhraseRows();
        layoutAll();
        invalidate();
        a11yChanged();
    }

    public int getPanel() { return panel; }

    /** Height of the rounded corner area, so the app can be drawn behind it. */
    public int cornerInset() { return (int) cornerR; }

    // ------------------------------------------------------------------ geometry

    private void computeGeometry(int w) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float screenH = dm.heightPixels;
        float lvl = heightLevel <= 3 ? (heightLevel == 0 ? 0.86f : heightLevel == 2 ? 1.14f : 1f) : heightLevel / 100f;
        float rowH = (land ? clamp(screenH * 0.10f, 34 * dp, 48 * dp)
                           : clamp(screenH * 0.064f, 44 * dp, 60 * dp)) * lvl;
        scale = rowH / (54 * dp);
        toolH = (land ? 38 : 48) * dp * clamp(scale, 0.85f, 1.1f);
        areaH = rowH * 4 + rowH * 0.84f + (extraRow ? rowH * Layouts.EXTRA_W : 0);
        float gm = gapLevel == 0 ? 0.4f : gapLevel == 2 ? 1.6f : gapLevel == 3 ? 2.3f : 1f;
        gap = Math.max(1.5f * dp, 5.5f * dp * scale * gm);
        float base = radiusDp > 0 ? radiusDp : keyShape == 1 ? 2.5f : keyShape == 2 ? 40f : keyShape == 3 ? 14f : 8f;
        radius = base * dp * clamp(scale, 0.8f, 1.2f);
        cornerR = 22 * dp;

        float avail = w - insetL - insetR;
        float content = avail;
        if (avail > 720 * dp) content = Math.max(720 * dp, avail * 0.78f);
        if (widthPct < 100 && widthPct >= 60) content = content * widthPct / 100f;
        fullL = insetL + (avail - content) / 2f + 3 * dp;
        fullR = fullL + content - 6 * dp;
        contentL = fullL;
        contentR = fullR;
        if (oneHand != 0 && !land) {
            float cw = (fullR - fullL) * Math.max(0.55f, Math.min(0.95f, oneHandPct / 100f));
            if (oneHand == 1) contentL = fullR - cw; else contentR = fullL + cw;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        computeGeometry(w);
        setMeasuredDimension(w, (int) (toolH + areaH + 8 * dp + insetB));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        computeGeometry(w);
        buildShaders();
        layoutAll();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        int b, l, r;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = in.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            b = i.bottom; l = i.left; r = i.right;
        } else {
            b = in.getSystemWindowInsetBottom();
            l = in.getSystemWindowInsetLeft();
            r = in.getSystemWindowInsetRight();
        }
        if (b != insetB || l != insetL || r != insetR) {
            insetB = b; insetL = l; insetR = r;
            requestLayout();
        }
        return in;
    }

    @Override
    protected void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        requestLayout();
    }

    private void buildShaders() {
        int w = Math.max(getWidth(), 1), h = Math.max(getHeight(), 1);
        auroraA = auroraB = auroraC = gradient = hyperEdge = null;
        if (theme.hyper) {
            hyperEdge = new LinearGradient(0, 0, w, h * 0.35f, new int[]{H_PURPLE, H_INDIGO, H_CYAN}, null, Shader.TileMode.CLAMP);
            auroraC = new RadialGradient(w * 0.55f, h * 0.55f, w * 0.45f, 0x1FEC4899, 0, Shader.TileMode.CLAMP);
        }
        if (theme.neon) {
            auroraA = new RadialGradient(w * 0.15f, h * 0.1f, w * 0.55f,
                    Theme.withAlpha(theme.glow, 0x38), 0, Shader.TileMode.CLAMP);
            auroraB = new RadialGradient(w * 0.9f, h * 0.95f, w * 0.6f,
                    Theme.withAlpha(theme.glow2, 0x30), 0, Shader.TileMode.CLAMP);
        }
        if (theme.bg2 != 0) {
            gradient = new LinearGradient(0, 0, w, h, theme.bg, theme.bg2, Shader.TileMode.CLAMP);
        }
        bgPath.reset();
        bgPath.addRoundRect(new RectF(0, 0, w, h + cornerR),
                new float[]{cornerR, cornerR, cornerR, cornerR, 0, 0, 0, 0}, Path.Direction.CW);
        if (photo != null) {
            float bw = photo.getWidth(), bh = photo.getHeight();
            float s = Math.max(w / bw, h / bh);
            float sw = w / s, sh = h / s;
            srcRect.set((int) ((bw - sw) / 2), (int) ((bh - sh) / 2), (int) ((bw + sw) / 2), (int) ((bh + sh) / 2));
            dstRect.set(0, 0, w, h);
        }
    }

    private void layoutAll() {
        if (getWidth() == 0) return;
        float w = fullR - fullL;
        float tt = cornerR * 0.15f;
        float L0 = fullL, R0 = fullR;
        if (avatar != null) {
            float aw = w / 7f;
            for (Key k : toolbar) if (k.id == Key.T_AVATAR) k.rect.set(R0 - aw, tt, R0, toolH);
            R0 -= aw;
        }
        if (arrowsOn) {
            float arw = Math.min(w / 9f, toolH * 0.95f);
            arrowKeyR.rect.set(R0 - arw, tt, R0, toolH);
            arrowKeyL.rect.set(L0, tt, L0 + arw, toolH);
            R0 -= arw; L0 += arw;
        }
        midL = L0; midR = R0;
        float tw = (R0 - L0) / 6f;
        for (Key k : toolbar) {
            if (k.id == Key.T_AVATAR) continue;
            float x = R0 - (k.id + 1) * tw;
            k.rect.set(x, tt, x + tw, toolH);
        }
        layoutFeatures();
        float top = toolH;
        if (keyRows != null) {
            float[] weights = new float[keyRows.size()];
            float sum = 0;
            for (int i = 0; i < weights.length; i++) {
                List<Key> kr = keyRows.get(i);
                weights[i] = !kr.isEmpty() && kr.get(0).rowW > 0 ? kr.get(0).rowW : 1f;
                sum += weights[i];
            }
            layoutRows(keyRows, weights, sum, top);
        }
        if (stripKey != null) stripKey.rect.set(contentL + gap / 2, stripKey.rect.top, contentR - gap / 2, stripKey.rect.bottom);
        layoutSuggestions();
        layoutRows(emojiRows, equal(emojiRows.size()), emojiRows.size(), top);
        layoutRows(moreRows, equal(moreRows.size()), moreRows.size(), top);
        layoutRows(editRows, equal(editRows.size()), editRows.size(), top);
        if (!phraseRows.isEmpty()) layoutRows(phraseRows, equal(phraseRows.size()), phraseRows.size(), top);
        layoutRows(decorRows, equal(decorRows.size()), decorRows.size(), top);
        layoutClips(top);
        layoutSide(top);
    }

    private static float[] equal(int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = 1;
        return f;
    }

    private void layoutRows(List<List<Key>> rows, float[] weights, float sum, float top) {
        float w = contentR - contentL;
        float maxUnits = 0;
        for (List<Key> r : rows) {
            float u = 0;
            for (Key k : r) u += k.units;
            maxUnits = Math.max(maxUnits, u);
        }
        float unitW = w / maxUnits;
        float y = top;
        for (int i = 0; i < rows.size(); i++) {
            List<Key> r = rows.get(i);
            float rh = areaH * weights[i] / sum;
            float u = 0;
            for (Key k : r) u += k.units;
            float x = contentL + (w - u * unitW) / 2f;
            for (Key k : r) {
                float kw = k.units * unitW;
                k.rect.set(x + gap / 2, y + gap / 2, x + kw - gap / 2, y + rh - gap / 2);
                x += kw;
            }
            y += rh;
        }
    }

    private void layoutSide(float top) {
        sideKeys.clear();
        if (oneHand == 0 || contentR - contentL >= fullR - fullL - 1) return;
        float l = oneHand == 1 ? fullL : contentR;
        float r = oneHand == 1 ? contentL : fullR;
        float cx = (l + r) / 2, s = Math.min(r - l - gap, 46 * dp) / 2;
        Key ex = Key.fn(Key.OH_EXPAND, "", 1f);
        Key sw = Key.fn(Key.OH_SWITCH, "", 1f);
        float cy = top + areaH / 2;
        ex.rect.set(cx - s, cy - s * 2.4f, cx + s, cy - s * 0.2f);
        sw.rect.set(cx - s, cy + s * 0.2f, cx + s, cy + s * 2.4f);
        sideKeys.add(ex);
        sideKeys.add(sw);
    }

    static final int STRIP_MAX = 6;

    /** W119: how many suggestions the service should send (six on the strip row, three in the toolbar). */
    public int suggCap() { return stripKey != null ? STRIP_MAX : 3; }

    private void layoutSuggestions() {
        List<Key> prev = new ArrayList<>(suggKeys);
        suggKeys.clear();
        if (suggestions == null || suggestions.isEmpty()) return;
        boolean strip = stripKey != null;
        float L = strip ? stripKey.rect.left : midL, R = strip ? stripKey.rect.right : midR;
        float T = strip ? stripKey.rect.top : cornerR * 0.15f, B = strip ? stripKey.rect.bottom : toolH;
        long now = SystemClock.uptimeMillis();
        if (emojiStrip) {
            float w = R - L, chipW = w * 0.34f, cell = (w - chipW) / 6f;
            for (int i = 0; i < Math.min(7, suggestions.size()); i++) {
                Key k = new Key(Key.SUGG, suggestions.get(i), suggestions.get(i), 1f, null);
                k.id = i == 0 ? 0 : 10 + i;
                float l, r;
                if (i == 0) { l = rtl ? R - chipW : L; r = l + chipW; }
                else {
                    float off = chipW + (i - 1) * cell;
                    if (rtl) { r = R - off; l = r - cell; } else { l = L + off; r = l + cell; }
                }
                k.rect.set(l, T, r, B);
                k.born = bornOf(prev, k, now);
                suggKeys.add(k);
            }
            return;
        }
        if (strip) {   // W119: up to six chips, widths follow the words, best first in reading order
            int n = Math.min(STRIP_MAX, suggestions.size());
            float sz = 17 * dp * clamp(scale, 0.85f, 1.1f), pad = 22 * dp, minW = (R - L) / 7f;
            textP.setTextSize(sz);
            float[] w = new float[n];
            float tot = 0;
            for (int i = 0; i < n; i++) {
                String lab = suggestions.get(i);
                w[i] = lab.startsWith(CLIP_MARK) ? 120 * dp : textP.measureText(lab) + pad;
            }
            n = Motion.fitChips(w, n, R - L, minW);   // too crowded: the weakest tail drops
            for (int i = 0; i < n; i++) tot += w[i];
            float k0 = (R - L) / tot, x = rtl ? R : L;
            for (int i = 0; i < n; i++) {
                float cw = w[i] * k0;
                Key k = new Key(Key.SUGG, suggestions.get(i), suggestions.get(i), 1f, null);
                k.id = i;
                if (rtl) { k.rect.set(x - cw, T, x, B); x -= cw; } else { k.rect.set(x, T, x + cw, B); x += cw; }
                k.born = bornOf(prev, k, now);
                suggKeys.add(k);
            }
            return;
        }
        int n = Math.min(3, suggestions.size());
        float sw = (R - L) / 3f;
        // center slot = best, then the reading-direction side
        int[] slotOrder = rtl ? new int[]{1, 2, 0} : new int[]{1, 0, 2};
        for (int i = 0; i < n; i++) {
            Key k = new Key(Key.SUGG, suggestions.get(i), suggestions.get(i), 1f, null);
            k.id = i;
            int slot = n == 1 ? 1 : slotOrder[i];
            float x = L + slot * sw;
            k.rect.set(x, T, x + sw, B);
            k.born = bornOf(prev, k, now);
            suggKeys.add(k);
        }
    }

    /** W117: a chip keeps its birth time when the same word stays in the same place (no flicker while typing). */
    private static long bornOf(List<Key> prev, Key k, long now) {
        for (Key o : prev) if (o.id == k.id && o.label != null && o.label.equals(k.label)) return o.born;
        return now;
    }

    private final java.util.Map<Integer, List<String>> drawableCache = new java.util.HashMap<>();

    /** Symbol tabs drop characters this phone's fonts cannot draw (no empty boxes). */
    private List<String> drawable(int tab) {
        List<String> c = drawableCache.get(tab);
        if (c != null) return c;
        c = new ArrayList<>();
        android.graphics.Paint p = new android.graphics.Paint();
        boolean check = tab >= 8 && android.os.Build.VERSION.SDK_INT >= 23;
        for (String e : Layouts.EMOJI[tab]) {
            try { if (!check || p.hasGlyph(e)) c.add(e); } catch (Throwable t) { c.add(e); }
        }
        drawableCache.put(tab, c);
        return c;
    }

    private void buildEmojiRows() {
        emojiRows.clear();
        List<String> list = new ArrayList<>();
        if (emojiTab == 0) list.addAll(recents);
        else list.addAll(drawable(emojiTab - 1));
        boolean paged = list.size() > 36;
        int per = paged ? 34 : 36;
        int epages = Math.max(1, (list.size() + per - 1) / per);
        if (emojiPage >= epages) emojiPage = 0;
        int base = emojiPage * per;
        for (int r = 0; r < 4; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                int i = r * 9 + c;
                Key k;
                if (paged && i == 34) { k = Key.fn(Key.PAGE_PREV, "‹", 1f); k.id = 79; }
                else if (paged && i == 35) { k = Key.fn(Key.PAGE_NEXT, "›", 1f); k.id = 79; }
                else {
                    String e = base + i < list.size() ? list.get(base + i) : "";
                    k = new Key(Key.CHAR, e, e, 1f, null);
                    k.id = 1; // emoji cell
                }
                row.add(k);
            }
            emojiRows.add(row);
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(Key.fn(Key.TO_ALPHA, "ABC", 1.4f));
        Key srch = new Key(Key.EMOJI_TAB, "", "search", 0.8f, null);
        srch.id = 99;
        bottom.add(srch);
        int n = Layouts.EMOJI_TABS.length;
        for (int i = 0; i < n; i++) {
            Key t = new Key(Key.EMOJI_TAB, Layouts.EMOJI_TABS[i], Layouts.EMOJI_TAB_ICONS[i], 5.4f / n, null);
            t.id = i;
            bottom.add(t);
        }
        bottom.add(Key.fn(Key.DEL, "", 1.4f));
        emojiRows.add(bottom);
    }

    private void buildMoreRows() {
        moreRows.clear();
        String[][] items = {
                {"edit", "تحرير"}, {"chat", "عبارات"}, {"sparkle", "زخرفة"}, {"hand", "يد واحدة"},
                {"height", "الحجم"}, {"paste", "الحافظة"}, {"palette", "الثيمات"}, {"globe", "تبديل"},
                {"hide", "إخفاء"}, {"spell", "تصحيح اللغة"}, {"calc", "احسب"}, {"calendar", "هجري"},
                {"translate", "ترجمة"}, {"volume", "استمع"}, {"numbers", "الأرقام"}, {"incognito", incognito ? "إيقاف التخفي" : "وضع التخفي"},
                {"case", "حالة الأحرف"}, {"undo", "تراجع"}, {"search", "بحث"}, {"share", "مشاركة"},
                {"edit_note", "مساعد المراسلات"}, {"text_increase", bigKeys ? "حجم عادي" : "تكبير المفاتيح"},
                {"contrast", contrastOn ? "إلغاء التباين" : "تباين عالٍ"}, {"motion_off", reduceMotion ? "تشغيل الحركة" : "تقليل الحركة"},
                {"refresh", "محوّل اللهجات"},
        };
        for (int r = 0; r < 5; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < 5; c++) {
                int i = r * 5 + c;
                Key k = new Key(Key.MORE_ITEM, items[i][1], items[i][0], 1f, null);
                k.id = i;
                row.add(k);
            }
            moreRows.add(row);
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(Key.fn(Key.TO_ALPHA, "رجوع للكيبورد", 3f));
        moreRows.add(bottom);
    }

    private void buildDecorRows() {
        decorRows.clear();
        int dpages = (Decor.TOTAL + 11) / 12;
        if (decorPage >= dpages) decorPage = 0;
        for (int r = 0; r < 6; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < 2; c++) {
                int i = decorPage * 12 + r * 2 + c;
                if (i >= Decor.TOTAL) { Key blank = Key.fn(Key.LABEL, "", 1f); row.add(blank); continue; }
                Key k = new Key(Key.DECOR, i >= Decor.COUNT ? Decor.frameSample(i - Decor.COUNT) : Decor.sample(i), null, 1f, null);
                k.id = i;
                row.add(k);
            }
            java.util.Collections.reverse(row);
            decorRows.add(row);
        }
        List<Key> bottom = new ArrayList<>();
        Key dp0 = Key.fn(Key.PAGE_PREV, "‹", 1f); dp0.id = 78;
        Key dl = Key.fn(Key.LABEL, (decorPage + 1) + " / " + dpages, 1f);
        Key dn = Key.fn(Key.PAGE_NEXT, "›", 1f); dn.id = 78;
        bottom.add(dp0); bottom.add(dl); bottom.add(dn);
        bottom.add(Key.fn(Key.TO_ALPHA, "رجوع للكيبورد", 3f));
        decorRows.add(bottom);
    }

    private void buildEditRows() {
        editRows.clear();
        String[][] grid = {
                {"تحديد الكل", "نسخ", "قص", "لصق"},
                {"⇤ البداية", "▲", "النهاية ⇥", "↶ تراجع"},
                {"◀", "تحديد", "▶", "إعادة ↷"},
                {"حذف كلمة", "▼", "سطر جديد ↵", ""},
        };
        int[][] ids = {
                {E_SELALL, E_COPY, E_CUT, E_PASTE},
                {E_HOME, E_UP, E_END, E_UNDO},
                {E_LEFT, E_SELECT, E_RIGHT, E_REDO},
                {E_DELWORD, E_DOWN, E_NEWLINE, -1},
        };
        for (int r = 0; r < 4; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                if (ids[r][c] < 0) { row.add(Key.fn(Key.DEL, "", 1f)); continue; }
                Key k = new Key(Key.EDIT, grid[r][c], null, 1f, null);
                k.id = ids[r][c];
                row.add(k);
            }
            editRows.add(row);
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(Key.fn(Key.TO_ALPHA, "رجوع للكيبورد", 4f));
        editRows.add(bottom);
    }

    private void buildPhraseRows() {
        phraseRows.clear();
        int per = 8, pages = Math.max(1, (phrases.size() + per - 1) / per);
        if (phrasePage >= pages) phrasePage = 0;
        for (int r = 0; r < 4; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < 2; c++) {
                int i = phrasePage * per + r * 2 + (rtl ? c : c); // filled right-to-left below
                String p = i < phrases.size() ? phrases.get(i) : "";
                row.add(new Key(Key.PHRASE, p, p, 1f, null));
            }
            // RTL: first phrase on the right
            java.util.Collections.reverse(row);
            phraseRows.add(row);
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(Key.fn(Key.TO_ALPHA, "ABC", 1.3f));
        bottom.add(Key.fn(Key.PAGE_PREV, "‹", 0.9f));
        bottom.add(Key.fn(Key.LABEL, (phrasePage + 1) + " / " + pages, 1.2f));
        bottom.add(Key.fn(Key.PAGE_NEXT, "›", 0.9f));
        bottom.add(Key.fn(Key.DEL, "", 1.3f));
        phraseRows.add(bottom);
    }

    private long clipSig = Long.MIN_VALUE, clipShownAt;   // W121
    private boolean clipAnim;

    private void layoutClips(float top) {
        clipKeys.clear();
        clipBottom.clear();
        float w = contentR - contentL;
        float rh = areaH / 5f;
        if (clipMenu != null) { layoutClipMenu(top, w, rh); return; }

        // header: search + filter tabs
        List<Key> head = new ArrayList<>();
        List<Float> hw = new ArrayList<>();
        Key srch = Key.fn(Key.CLIP_TAB, "", 0.8f); srch.id = clipQuery != null ? 51 : 50;
        head.add(srch); hw.add(clipQuery != null ? 1.9f : 0.75f);
        // order on screen (start side first): all, pinned n/100, favourites, categories, templates, archive
        int[] ids = {0, 1, 2, 5, 4, 3};
        String[] tabs = {"الكل", "📌" + clipPinCount + "/" + ClipRepo.MAX_PINS, "★", "تصنيفات", "قوالب", "أرشيف"};
        float[] tw = {0.8f, 1.45f, 0.6f, 1.15f, 0.9f, 0.9f};
        for (int i = 0; i < tabs.length; i++) {
            Key t = new Key(Key.CLIP_TAB, tabs[i], null, 1f, null);
            t.id = ids[i];
            head.add(t); hw.add(tw[i]);
        }
        float sum = 0; for (float f : hw) sum += f;
        float hx = contentR, hh = rh * 0.82f;
        for (int i = 0; i < head.size(); i++) {
            float ww = w * hw.get(i) / sum;
            Key k = head.get(i);
            k.rect.set(hx - ww + gap / 2, top + gap / 2, hx - gap / 2, top + hh - gap / 2);
            hx -= ww;
            clipBottom.add(k);
        }

        float body = top + hh;
        float ch = (top + 4 * rh - body) / 3f;
        boolean catGrid = clipTab == ClipRepo.TAB_CATS && clipCat == 0 && clipQuery == null;
        if (catGrid) {
            int cp = Math.max(1, (clipCats.size() + CLIP_PER_PAGE - 1) / CLIP_PER_PAGE);
            if (clipPage >= cp) clipPage = 0;
            int st = clipPage * CLIP_PER_PAGE;
            for (int i = 0; i < CLIP_PER_PAGE && st + i < clipCats.size(); i++) {
                ClipRepo.Cat ct = clipCats.get(st + i);
                Key k = new Key(Key.CLIP_TAB, ct.name + "  (" + ct.count + ")", null, 1f, null);
                k.id = 200 + st + i;
                int col = i % 2, row = i / 2;
                float x = contentR - (col + 1) * (w / 2f), yy = body + row * ch;
                k.rect.set(x + gap / 2, yy + gap / 2, x + w / 2f - gap / 2, yy + ch - gap / 2);
                clipBottom.add(k);
            }
        }
        int pages = Math.max(1, ((catGrid ? clipCats.size() : clips.size()) + CLIP_PER_PAGE - 1) / CLIP_PER_PAGE);
        if (clipPage >= pages) clipPage = 0;
        int start = clipPage * CLIP_PER_PAGE;
        int n = catGrid ? 0 : Math.max(0, Math.min(clips.size() - start, CLIP_PER_PAGE));
        long sig = ((clipTab * 131L + clipCat) * 131L + clipPage) * 1_000_003L + (n > 0 ? clips.get(start).id : -1) + (clipQuery == null ? 0 : 7);
        if (sig != clipSig) { clipSig = sig; clipShownAt = SystemClock.uptimeMillis(); }   // W121: a new page of cards deals in
        float cw = w / 2f;
        for (int i = 0; i < n; i++) {
            ClipItem ci = clips.get(start + i);
            String s = ci.title != null ? ci.title : ci.preview;
            Key k = new Key(Key.CLIP, s, s, 1f, null);
            k.clip = ci;
            int col = i % 2, row = i / 2;
            float x = contentR - (col + 1) * cw;
            float y = body + row * ch;
            k.rect.set(x + gap / 2, y + gap / 2, x + cw - gap / 2, y + ch - gap / 2);
            clipKeys.add(k);
        }
        float y = top + 4 * rh;
        Key abc = Key.fn(Key.TO_ALPHA, "ABC", 1f);
        Key clear = Key.fn(Key.CLIP_CLEAR, "مسح السجل", 1f);
        if (clipTab == ClipRepo.TAB_CATS && clipCat != 0) { clear = Key.fn(Key.CLIP_TAB, "‹ التصنيفات", 1f); clear.id = 60; }
        else if (clipTab != ClipRepo.TAB_ALL) { clear = Key.fn(Key.LABEL, "", 1f); }
        Key del = Key.fn(Key.DEL, "", 1f);
        if (pages > 1 || (clipHasMore && !catGrid)) {
            float u = w / 7f;
            Key prev = Key.fn(Key.PAGE_PREV, "‹", 1f); prev.id = 77;
            Key lab = Key.fn(Key.LABEL, (clipPage + 1) + " / " + pages + (clipHasMore && !catGrid ? "+" : ""), 1f);
            Key next = Key.fn(Key.PAGE_NEXT, "›", 1f); next.id = 77;
            Key[] row = {abc, prev, lab, next, clear, del};
            float[] wd = {1.2f, 0.9f, 1f, 0.9f, 1.8f, 1.2f};
            float x = contentL;
            for (int i = 0; i < row.length; i++) {
                row[i].rect.set(x + gap / 2, y + gap / 2, x + wd[i] * u - gap / 2, y + rh - gap / 2);
                x += wd[i] * u;
                clipBottom.add(row[i]);
            }
        } else {
            float bw = w / 4f;
            abc.rect.set(contentL + gap / 2, y + gap / 2, contentL + bw - gap / 2, y + rh - gap / 2);
            clear.rect.set(contentL + bw + gap / 2, y + gap / 2, contentR - bw - gap / 2, y + rh - gap / 2);
            del.rect.set(contentR - bw + gap / 2, y + gap / 2, contentR - gap / 2, y + rh - gap / 2);
            clipBottom.add(abc);
            clipBottom.add(clear);
            clipBottom.add(del);
        }
    }

    /** Action sheet for one item, drawn inside the clip area so the keyboard never resizes. */
    private void layoutClipMenu(float top, float w, float rh) {
        ClipItem m = clipMenu;
        Object[][] acts = m.kind == 1 ? new Object[][]{
                {CA_INSERT, "إدراج", "paste"}, {CA_COPY, "نسخ", "copy"},
                {CA_EDIT, "تعديل", "edit"}, {CA_SHARE, "مشاركة", "share"},
                {CA_EXPORT, "تصدير", "upload"},
                {CA_DELETE, clipDelArm ? "اضغط للتأكيد" : "حذف", "delete"},
        } : new Object[][]{
                {CA_INSERT, "إدراج", "paste"}, {CA_COPY, "نسخ", "copy"},
                {CA_EDIT, "تعديل", "edit"},
                {CA_PIN, m.pinned ? "إلغاء التثبيت" : "تثبيت", "pin"},
                {CA_FAV, m.fav ? "إلغاء المفضلة" : "مفضلة", "star"},
                {CA_ARCHIVE, m.archived ? "استعادة" : "أرشفة", "download"},
                {CA_TEMPLATE, "حفظ كقالب", "book"},
                {CA_SHARE, "مشاركة", "share"},
                {CA_EXPORT, "تصدير", "upload"},
                {CA_DELETE, clipDelArm ? "اضغط للتأكيد" : "حذف", "delete"},
        };
        int cols = acts.length > 8 ? 5 : 4;
        float gy = top + rh * 0.9f, gh = rh * 3.1f / 2f, cw = w / cols;
        for (int i = 0; i < acts.length; i++) {
            Key k = new Key(Key.CLIP_ACT, (String) acts[i][1], (String) acts[i][2], 1f, null);
            k.id = (Integer) acts[i][0];
            int col = i % cols, row = i / cols;
            float x = contentR - (col + 1) * cw;   // reads from the start side
            float y = gy + row * gh;
            k.rect.set(x + gap / 2, y + gap / 2, x + cw - gap / 2, y + gh - gap / 2);
            clipBottom.add(k);
        }
        float y = top + 4 * rh;
        Key back = Key.fn(Key.CLIP_ACT, "رجوع", 1f); back.id = CA_BACK;
        back.rect.set(contentL + gap / 2, y + gap / 2, contentR - gap / 2, y + rh - gap / 2);
        clipBottom.add(back);
    }

    private List<List<Key>> panelRows() {
        switch (panel) {
            case PANEL_EMOJI: return emojiRows;
            case PANEL_MORE: return moreRows;
            case PANEL_EDIT: return editRows;
            case PANEL_PHRASES: return phraseRows;
            case PANEL_DECOR: return decorRows;
            case PANEL_KEYS: return keyRows;
            default: return null;
        }
    }

    private boolean showSugg() { return panel == PANEL_KEYS && stripKey == null && !suggKeys.isEmpty() && voiceState == 0 && expanded == 0; }
    /** W117: suggestions live in the strip row; the toolbar keeps the tools. */
    private boolean inStrip() { return stripKey != null && panel == PANEL_KEYS && voiceState == 0; }

    // ------------------------------------------------------------------ arrows + quick features

    /** Arrow strip config from prefs. */
    public void setArrows(boolean on, int[] right, int[] left) {
        boolean ch = on != arrowsOn || !java.util.Arrays.equals(right, arrowRight) || !java.util.Arrays.equals(left, arrowLeft);
        arrowsOn = on; arrowRight = right; arrowLeft = left;
        if (!on) expanded = 0;
        if (ch) { layoutAll(); invalidate(); }
    }

    private void layoutFeatures() {
        featKeys.clear();
        if (!arrowsOn || expanded == 0) return;
        int[] ids = expanded == 1 ? arrowRight : arrowLeft;
        float fw = (midR - midL) / ids.length, tt = cornerR * 0.15f;
        for (int i = 0; i < ids.length; i++) {
            Key k = Key.tool(1000 + ids[i]);
            // features fan out from the arrow that opened them
            float x = expanded == 1 ? midR - (i + 1) * fw : midL + i * fw;
            k.rect.set(x, tt, x + fw, toolH);
            featKeys.add(k);
        }
    }

    private void toggleArrow(int which) {
        expanded = expanded == which ? 0 : which;
        expandT = anims && !reduceMotion ? 0f : 1f;
        layoutFeatures();
        a11yChanged();
        invalidate();
    }

    /** Collapses the open arrow (used when the keyboard restarts or a feature runs). */
    public void collapseArrows() { if (expanded != 0) { expanded = 0; layoutFeatures(); invalidate(); } }

    private void runFeature(int f) {
        expanded = 0;
        layoutFeatures();
        switch (f) {
            case Features.EMOJI: onTool(Key.T_EMOJI); break;
            case Features.LANG: onTool(Key.T_LANG); break;
            case Features.MIC: onTool(Key.T_MIC); break;
            case Features.SETTINGS: onTool(Key.T_SETTINGS); break;
            case Features.MORE: onTool(Key.T_MORE); break;
            case 5: showPanel(panel == PANEL_CLIP ? PANEL_KEYS : PANEL_CLIP); break;
            default: onMore(f); break;
        }
        invalidate();
    }

    private void drawArrow(Canvas c, Key k, boolean right, float flip) {
        float cx = k.rect.centerX(), cy = k.rect.centerY();
        boolean open = expanded == (right ? 1 : 2);
        float r = Math.min(k.rect.width(), k.rect.height()) * 0.36f * (1f - 0.08f * k.press);
        if (open) {
            fillP.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, theme.enter,
                    theme.neon ? theme.glow2 : 0xFF8B5CF6, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, fillP);
            fillP.setShader(null);
        } else {
            fillP.setColor(Theme.withAlpha(theme.icon, 0x1C + (int) (0x22 * k.press)));
            c.drawCircle(cx, cy, r, fillP);
        }
        // chevron: closed arrows face each other, an open one turns around
        float dir = right ? -1f : 1f;               // closed: right arrow points left, left arrow points right
        float d = dir * (1f - 2f * flip);           // flip 0..1 animates the turn
        float a = r * 0.32f;
        iconP.setColor(open ? 0xFFFFFFFF : theme.icon);
        iconP.setStrokeWidth(2.2f * dp);
        c.drawLine(cx - d * a * 0.6f, cy - a, cx + d * a * 0.6f, cy, iconP);
        c.drawLine(cx + d * a * 0.6f, cy, cx - d * a * 0.6f, cy + a, iconP);
    }

    private void drawFeatures(Canvas c, float dt) {
        if (expandT < 1f) expandT = Math.min(1f, expandT + dt / 200f);
        float e = 1f - (1f - expandT) * (1f - expandT) * (1f - expandT);
        // soft tray behind the features
        tmp.set(midL + 3 * dp, toolH * 0.18f, midR - 3 * dp, toolH * 0.92f);
        fillP.setColor(Theme.withAlpha(theme.enter, (int) (0x1E * e)));
        c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fillP);
        float iz = 21 * dp * clamp(scale, 0.85f, 1.1f);
        for (int i = 0; i < featKeys.size(); i++) {
            Key k = featKeys.get(i);
            float cx = k.rect.centerX(), cy = k.rect.centerY();
            float lag = clamp(e * 1.4f - i * 0.08f, 0f, 1f);
            float origin = expanded == 1 ? midR : midL;
            float x = origin + (cx - origin) * lag;
            if (k.press > 0.01f) {
                fillP.setColor(Theme.withAlpha(theme.enter, (int) (0x33 * k.press)));
                c.drawCircle(x, cy, iz * 0.95f, fillP);
            }
            int f = k.id - 1000;
            int col = Theme.withAlpha(theme.icon, (int) (255 * lag));
            boolean on = (f == 5 && panel == PANEL_CLIP) || (f == Features.EMOJI && panel == PANEL_EMOJI);
            Icons.draw(c, getContext(), Features.icon(f), x, cy, iz, on ? theme.enter : col);
        }
    }

    // ------------------------------------------------------------------ drawing

    private static Bitmap genBm;      // W69: خبيئة الخلفية المولّدة (بذرة×مقاس واحد)
    private static String genBmKey = "";

    /** يصيّر خلفية البذرة إلى بكسلات GenThemes الحتمية ثم إلى Bitmap (خبيئة واحدة). */
    private Bitmap genBackdrop(String seed, int w, int h) {
        String k = seed + "|" + w + "x" + h;
        if (genBm == null || !k.equals(genBmKey)) {
            GenThemes.Pal p = GenThemes.make(GenThemes.seedOf(seed));
            int[] buf = GenThemes.render(p, Math.max(1, w), Math.max(1, h));
            Bitmap bm = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            bm.setPixels(buf, 0, Math.max(1, w), 0, 0, Math.max(1, w), Math.max(1, h));
            genBm = bm;
            genBmKey = k;
        }
        return genBm;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (showMark > 0) { Perf.record("show", now - showMark); showMark = 0; }
        if (touchMark > 0) { Perf.record("frame", now - touchMark); touchMark = 0; }
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;
        int w = getWidth(), h = getHeight();

        // background with rounded top corners (the app shows through the corners)
        fillP.setShader(gradient);
        fillP.setColor(theme.bg);
        c.drawPath(bgPath, fillP);
        fillP.setShader(null);
        if (theme.gen != null && theme.gen.length() > 0) {   // W69: خلفية مولّدة من البذرة (صفر أصول)
            c.save();
            c.clipPath(bgPath);
            c.drawBitmap(genBackdrop(theme.gen, w, h), 0, 0, null);
            c.restore();
        }
        int ab = theme.anim >= 0 ? theme.anim : (theme.photo ? animBg : -1);
        animBgOn = false;
        if (ab >= 0) {
            boolean still = reduceMotion || !anims;
            c.save();
            c.clipPath(bgPath);
            Backdrops.drawAnim(c, ab, w, h, still ? 3f : (now - animStart) / 1000f);
            if (theme.photo) {
                fillP.setColor(Theme.withAlpha(0xFF000000, (int) (photoDim * 2.55f)));
                c.drawRect(0, 0, w, h, fillP);
            }
            c.restore();
            animBgOn = !still;
        } else if (photo != null) {
            c.save();
            c.clipPath(bgPath);
            c.drawBitmap(photo, srcRect, dstRect, bmpP);
            fillP.setColor(Theme.withAlpha(0xFF000000, (int) (photoDim * 2.55f)));
            c.drawRect(0, 0, w, h, fillP);
            c.restore();
        }
        if (theme.neon && auroraA != null) {
            c.save();
            c.clipPath(bgPath);
            fillP.setShader(auroraA);
            c.drawRect(0, 0, w, h, fillP);
            fillP.setShader(auroraB);
            c.drawRect(0, 0, w, h, fillP);
            if (auroraC != null) { fillP.setShader(auroraC); c.drawRect(0, 0, w, h, fillP); }
            fillP.setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{0, theme.glow, theme.glow2, 0}, null, Shader.TileMode.CLAMP));
            c.drawRect(cornerR, 0, w - cornerR, 1.5f * dp, fillP);
            fillP.setShader(null);
            c.restore();
        }

        if (theme.hyper && hyperEdge != null) drawHyperPill(c, 6 * dp, 4 * dp, w - 6 * dp, toolH - 3 * dp);

        boolean animating = false;
        if (voiceState != 0) {
            drawVoice(c, now);
            animating = true;
        } else {
            if (arrowsOn) {
                float tr = expanded == 1 ? 1f : 0f, tl = expanded == 2 ? 1f : 0f;
                float sp = anims && !reduceMotion ? dt / 180f : 1f;
                flipR += Math.signum(tr - flipR) * Math.min(Math.abs(tr - flipR), sp);
                flipL += Math.signum(tl - flipL) * Math.min(Math.abs(tl - flipL), sp);
                animating |= flipR != tr || flipL != tl;
                animating |= step(arrowKeyR, dt); animating |= step(arrowKeyL, dt);
                drawArrow(c, arrowKeyR, true, flipR);
                drawArrow(c, arrowKeyL, false, flipL);
            }
            for (Key k : toolbar) if (k.id == Key.T_AVATAR) { animating |= step(k, dt); drawTool(c, k); }
            if (expanded != 0) {
                for (Key k : featKeys) animating |= step(k, dt);
                drawFeatures(c, dt);
                animating |= expandT < 1f;
            } else if (showSugg()) {
                drawSuggestions(c);
                for (Key k : suggKeys) animating |= step(k, dt);
            } else {
                for (Key k : toolbar) if (k.id != Key.T_AVATAR) { animating |= step(k, dt); drawToolFx(c, k, now); }
            }
        }

        boolean fading = panelT < 1f;
        int layer = -1;
        if (fading) {
            panelT = Math.min(1f, panelT + dt / 190f);
            float e = 1f - (1f - panelT) * (1f - panelT) * (1f - panelT);
            layer = c.saveLayerAlpha(0, toolH, w, h, (int) (255 * e));
            c.translate(0, (1f - e) * 14 * dp);
            animating = true;
        }
        List<List<Key>> rows = panelRows();
        if (rows != null) {
            for (List<Key> r : rows) for (Key k : r) { animating |= step(k, dt); drawKey(c, k); }
            if (inStrip()) {
                for (Key k : suggKeys) animating |= step(k, dt);
                animating |= drawStrip(c, now);
            }
        } else if (panel == PANEL_CLIP) {
            clipAnim = false;
            drawClips(c);
            animating |= clipAnim;
            for (Key k : clipKeys) animating |= step(k, dt);
            for (Key k : clipBottom) { animating |= step(k, dt); drawKey(c, k); }
        }
        for (Key k : sideKeys) { animating |= step(k, dt); drawKey(c, k); }
        if (layer >= 0) c.restoreToCount(layer);

        if (active != null && altList != null) animating |= drawAlts(c, now, dt);
        else if (popups && !gliding && active != null && active.type == Key.CHAR && panel == PANEL_KEYS) animating |= drawPreview(c, active, now);
        if (active != null && swiped && motionOn() && ((active.type == Key.SPACE && swipeCursor) || active.type == Key.DEL)) drawDrag(c, active);
        if (gliding) {
            trailP.setStyle(Paint.Style.STROKE);
            trailP.setStrokeCap(Paint.Cap.ROUND);
            trailP.setStrokeJoin(Paint.Join.ROUND);
            trailP.setStrokeWidth(5 * dp);
            trailP.setColor(Theme.withAlpha(theme.enter, 0xB0));
            c.drawPath(trail, trailP);
        }

        if (pfx.busy()) animating |= pfx.draw(c, now, dp);
        long breathWait = -1;
        if (motionOn()) {
            if (lastType > 0 && panel == PANEL_KEYS && voiceState == 0) {
                drawFlow(c, now);
                animating |= Motion.flowDecay(flow, now - lastType) >= 0.02f;
                if (hasText) {
                    long since = now - lastType;
                    if (since < Motion.BREATH_AFTER) breathWait = Motion.BREATH_AFTER - since;
                    else if (since < Motion.BREATH_AFTER + Motion.BREATH_FOR) breathWait = 40;
                }
            }
            if (openAt > 0) {
                if (now - openAt < Motion.BRAND_MS) animating = true;
                else openAt = 0;
            }
        } else openAt = 0;
        if (animating) postInvalidateOnAnimation();
        else {
            lastFrame = 0;
            if (animBgOn) postInvalidateDelayed(33); // ~30 fps for moving backgrounds
            else if (breathWait > 0) postInvalidateDelayed(breathWait);   // W116 breathing Enter, ~25 fps
        }
    }

    private boolean step(Key k, float dt) {
        boolean ink = false;
        if (k.tdown > 0) {   // W117 ink ripple + hold bar
            long now = SystemClock.uptimeMillis();
            if (k.target == 0f && k.tup == 0) k.tup = now;
            if (!motionOn() || (k.tup > 0 && now - k.tup >= Motion.INK_FADE)) { k.tdown = 0; k.tup = 0; }
            else ink = k.tup > 0 || now - k.tdown < Math.max(Motion.INK_GROW, k.alts != null ? lpDelay : 0) + 40;
        }
        return stepPress(k, dt) | ink;
    }

    private boolean stepPress(Key k, float dt) {
        if (k.press == k.target) {
            if (k.rel == 0) return false;
            if (!motionOn() || SystemClock.uptimeMillis() - k.rel >= Motion.BOUNCE_MS) { k.rel = 0; return false; }
            return true;   // W116: the release spring is still settling
        }
        float speed = k.target > k.press ? 0.04f : 0.01f;
        float d = k.target - k.press;
        float s = Math.signum(d) * speed * dt;
        k.press = Math.abs(s) >= Math.abs(d) ? k.target : k.press + s;
        if (k.press == 0f && k.target == 0f && motionOn() && k.type != Key.LABEL && k.type != Key.SUGG)
            k.rel = SystemClock.uptimeMillis();
        return true;
    }

    private void drawSuggestions(Canvas c) {
        float sw = (midR - midL) / 3f;
        strokeP.setStrokeWidth(1f * dp);
        strokeP.setColor(Theme.withAlpha(theme.icon, 0x40));
        float t = toolH * 0.32f, b = toolH * 0.82f;
        if (emojiStrip && !suggKeys.isEmpty()) {
            Key chip = suggKeys.get(0);
            tmp.set(chip.rect.left + 5 * dp, chip.rect.top + 6 * dp, chip.rect.right - 5 * dp, chip.rect.bottom - 4 * dp);
            fillP.setColor(Theme.withAlpha(theme.enter, 0x30));
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fillP);
        } else {
            c.drawLine(midL + sw, t, midL + sw, b, strokeP);
            c.drawLine(midL + 2 * sw, t, midL + 2 * sw, b, strokeP);
        }
        for (Key k : suggKeys) {
            if (k.press > 0.01f) {
                fillP.setColor(Theme.withAlpha(theme.text, (int) (0x1C * k.press)));
                tmp.set(k.rect.left + 4 * dp, k.rect.top + 4 * dp, k.rect.right - 4 * dp, k.rect.bottom - 2 * dp);
                c.drawRoundRect(tmp, toolH / 2, toolH / 2, fillP);
            }
            boolean best = k.id == 0;
            textP.setColor(best ? theme.text : Theme.withAlpha(theme.text, 0xC8));
            textP.setTypeface(face(best ? Typeface.BOLD : Typeface.NORMAL));
            textP.setTextSize((k.id >= 10 ? 23 : 17) * dp * clamp(scale, 0.85f, 1.1f));
            if (k.label.startsWith(CLIP_MARK)) {
                String body = k.label.substring(CLIP_MARK.length());
                float iz = 17 * dp, gap = 6 * dp;
                CharSequence e = TextUtils.ellipsize(body, textP, k.rect.width() - 16 * dp - iz - gap, TextUtils.TruncateAt.END);
                float tw = textP.measureText(e.toString()), total = tw + iz + gap, left = k.rect.centerX() - total / 2;
                float icx = rtl ? left + tw + gap + iz / 2 : left + iz / 2;
                float tcx = rtl ? left + tw / 2 : left + iz + gap + tw / 2;
                Icons.draw(c, getContext(), "paste", icx, k.rect.centerY(), iz, theme.enter);
                c.drawText(e.toString(), tcx, baseline(k.rect, textP), textP);
                continue;
            }
            CharSequence e = TextUtils.ellipsize(k.label, textP, k.rect.width() - 12 * dp, TextUtils.TruncateAt.END);
            c.drawText(e.toString(), k.rect.centerX(), baseline(k.rect, textP), textP);
        }
        textP.setTypeface(face(Typeface.NORMAL));
    }

    // ------------------------------------------------------------------ W117 strip, ink, tool press

    /** The suggestion strip row: soft pills, the best word lit, chips rising in one after another. */
    private boolean drawStrip(Canvas c, long now) {
        RectF sr = stripKey.rect;
        if (suggKeys.isEmpty()) {   // quiet slot: three faint dots
            fillP.setColor(Theme.withAlpha(theme.icon, 0x30));
            for (int i = -1; i <= 1; i++) c.drawCircle(sr.centerX() + i * 8 * dp, sr.centerY(), 2.2f * dp, fillP);
            return false;
        }
        boolean anim = false;
        float sz = clamp(scale, 0.85f, 1.1f);
        for (Key k : suggKeys) {
            float e = 1f;
            if (motionOn() && k.born > 0) {
                e = Motion.chipIn(now - k.born, k.id >= 10 ? k.id - 9 : k.id);
                anim |= e < 1f;
            }
            if (e <= 0.001f) continue;
            boolean emojiCell = emojiStrip && k.id >= 10, best = k.id == 0;
            float ins = 3 * dp;
            tmp.set(k.rect.left + ins, k.rect.top + ins, k.rect.right - ins, k.rect.bottom - ins);
            float sc = (1f - 0.05f * k.press) * (0.86f + 0.14f * e);
            c.save();
            c.translate(0, (1f - e) * 8 * dp);
            c.scale(sc, sc, tmp.centerX(), tmp.centerY());
            float rr = tmp.height() / 2;
            if (!emojiCell) {
                int base = best ? Theme.withAlpha(theme.enter, 0x2C + (int) (0x34 * k.press))
                        : Theme.withAlpha(theme.text, 0x0E + (int) (0x22 * k.press));
                fillP.setColor(fade(base, e));
                c.drawRoundRect(tmp, rr, rr, fillP);
                if (best) {
                    strokeP.setStrokeWidth(1.2f * dp);
                    strokeP.setColor(fade(Theme.withAlpha(theme.enter, 0x8C), e));
                    c.drawRoundRect(tmp, rr, rr, strokeP);
                }
            } else if (k.press > 0.01f) {
                fillP.setColor(Theme.withAlpha(theme.text, (int) (0x1C * k.press)));
                c.drawRoundRect(tmp, rr, rr, fillP);
            }
            textP.setColor(fade(best ? theme.text : Theme.withAlpha(theme.text, 0xD0), e));
            textP.setTypeface(face(best ? Typeface.BOLD : Typeface.NORMAL));
            textP.setTextSize((emojiCell ? 23 : 17) * dp * sz);
            if (k.label.startsWith(CLIP_MARK)) {
                String body = k.label.substring(CLIP_MARK.length());
                float iz = 17 * dp, g = 6 * dp;
                CharSequence t = TextUtils.ellipsize(body, textP, tmp.width() - 16 * dp - iz - g, TextUtils.TruncateAt.END);
                float tw = textP.measureText(t.toString()), total = tw + iz + g, left = tmp.centerX() - total / 2;
                float icx = rtl ? left + tw + g + iz / 2 : left + iz / 2;
                float tcx = rtl ? left + tw / 2 : left + iz + g + tw / 2;
                Icons.draw(c, getContext(), "paste", icx, tmp.centerY(), iz, fade(theme.enter, e));
                c.drawText(t.toString(), tcx, baseline(k.rect, textP), textP);
            } else {
                CharSequence t = TextUtils.ellipsize(k.label, textP, tmp.width() - 14 * dp, TextUtils.TruncateAt.END);
                c.drawText(t.toString(), tmp.centerX(), baseline(k.rect, textP), textP);
            }
            c.restore();
        }
        textP.setTypeface(face(Typeface.NORMAL));
        return anim;
    }

    private static int fade(int col, float e) {
        int a = (int) (((col >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, e)));
        return (a << 24) | (col & 0xFFFFFF);
    }

    /** Ink from the exact point the finger landed, clipped to the key; a hold bar for keys with extra letters. */
    private void drawTouch(Canvas c, Key k) {
        if (k.tdown == 0 || !motionOn() || panel != PANEL_KEYS && panel != PANEL_EMOJI && panel != PANEL_EDIT && panel != PANEL_CLIP) return;
        long now = SystemClock.uptimeMillis(), t = now - k.tdown;
        float a = Motion.inkAlpha(k.tup > 0 ? now - k.tup : -1);
        if (a <= 0.004f) return;
        RectF r = k.rect;
        float maxR = (float) Math.hypot(Math.max(k.tx - r.left, r.right - k.tx), Math.max(k.ty - r.top, r.bottom - k.ty));
        c.save();
        inkPath.reset();
        inkPath.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(inkPath);
        if (k.type == Key.DEL && k == active && repN > 0) {   // W118: held delete warms up as it speeds up
            fillP.setColor(Theme.withAlpha(0xFFEF4444, (int) (0x70 * Motion.heat(repN))));
            c.drawRect(r, fillP);
        }
        int ink = k.type == Key.ENTER ? 0xFFFFFFFF : theme.enter;
        fillP.setColor(Theme.withAlpha(ink, (int) (0x3A * a)));
        c.drawCircle(clamp(k.tx, r.left, r.right), clamp(k.ty, r.top, r.bottom), maxR * Motion.inkRadius(t), fillP);
        if (k.alts != null && k.tup == 0 && !longPressed) {
            float hp = Motion.hold(t, lpDelay);
            if (hp > 0f) {
                float bw = r.width() * 0.56f * hp, by = r.bottom - 3.2f * dp;
                fillP.setColor(Theme.withAlpha(theme.enter, 0xD0));
                tmp.set(r.centerX() - bw / 2, by - 1.4f * dp, r.centerX() + bw / 2, by + 1.4f * dp);
                c.drawRoundRect(tmp, 1.4f * dp, 1.4f * dp, fillP);
            }
        }
        c.restore();
    }

    /** Toolbar icons dip under the finger, spring back, and glow softly while held. */
    private void drawToolFx(Canvas c, Key k, long now) {
        if (!motionOn()) { drawTool(c, k); return; }
        float s = 1f - 0.14f * k.press;
        if (k.rel > 0 && k.press == 0f) s *= 1f + 2.2f * Motion.bounce(now - k.rel);
        if (k.press > 0.01f) {
            fillP.setColor(Theme.withAlpha(theme.enter, (int) (0x24 * k.press)));
            c.drawCircle(k.rect.centerX(), k.rect.centerY(), Math.min(k.rect.width(), k.rect.height()) * 0.40f, fillP);
        }
        if (s == 1f) { drawTool(c, k); return; }
        c.save();
        c.scale(s, s, k.rect.centerX(), k.rect.centerY());
        drawTool(c, k);
        c.restore();
    }

    private void drawVoice(Canvas c, long now) {
        float l = fullL + 6 * dp, r = fullR - 6 * dp, t = cornerR * 0.25f + 4 * dp, b = toolH - 4 * dp;
        tmp.set(l, t, r, b);
        fillP.setColor(Theme.withAlpha(theme.enter, 0x2A));
        c.drawRoundRect(tmp, (b - t) / 2, (b - t) / 2, fillP);
        float cy = (t + b) / 2, mx = rtl ? r - (b - t) / 2 - 4 * dp : l + (b - t) / 2 + 4 * dp;
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 160.0));
        fillP.setColor(Theme.withAlpha(0xFFEF4444, (int) (0x40 + 0x50 * pulse)));
        c.drawCircle(mx, cy, (b - t) / 2 * (0.75f + 0.25f * pulse), fillP);
        fillP.setColor(0xFFEF4444);
        c.drawCircle(mx, cy, (b - t) * 0.26f, fillP);
        drawMic(c, mx, cy, (b - t) * 0.2f, 0xFFFFFFFF);
        String msg = voiceText.isEmpty() ? (voiceState == 1 ? (rtl ? "تكلّم الآن…" : "Listening…") : "لحظة…") : voiceText;
        textP.setColor(theme.text);
        textP.setTextSize(15 * dp * clamp(scale, 0.85f, 1.1f));
        float avail = r - l - (b - t) * 2.2f;
        CharSequence e = TextUtils.ellipsize(msg, textP, avail, TextUtils.TruncateAt.START);
        c.drawText(e.toString(), (l + r) / 2, cy - (textP.descent() + textP.ascent()) / 2, textP);
    }

    private void drawMic(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(Math.max(1.4f * dp, s * 0.22f));
        tmp.set(cx - s * 0.45f, cy - s * 1.05f, cx + s * 0.45f, cy + s * 0.35f);
        c.drawRoundRect(tmp, s * 0.45f, s * 0.45f, iconP);
        tmp.set(cx - s * 0.85f, cy - s * 0.5f, cx + s * 0.85f, cy + s * 0.75f);
        c.drawArc(tmp, 0, 180, false, iconP);
        c.drawLine(cx, cy + s * 0.75f, cx, cy + s * 1.15f, iconP);
    }

    private void drawTool(Canvas c, Key k) {
        if (k.id == Key.T_AVATAR) { drawAvatar(c, k); return; }
        float cx = k.rect.centerX(), cy = k.rect.centerY();
        float s = 11 * dp * clamp(scale, 0.85f, 1.1f);
        boolean on = (k.id == Key.T_EMOJI && panel == PANEL_EMOJI) || (k.id == Key.T_CLIP && panel == PANEL_CLIP)
                || (k.id == Key.T_MORE && (panel == PANEL_MORE || panel == PANEL_EDIT || panel == PANEL_PHRASES || panel == PANEL_DECOR));
        int col = on ? theme.enter : theme.icon;
        if (theme.hyper && hyperEdge != null) {
            float rad = Math.min(s * 1.75f, k.rect.height() * 0.42f);
            if (on || k.press > 0.01f) {
                float g = on ? 1f : k.press;
                for (int i = 3; i >= 1; i--) {
                    fillP.setColor(Theme.withAlpha(H_PURPLE, (int) (g * 30 / i)));
                    c.drawCircle(cx, cy, rad + i * 3 * dp, fillP);
                }
                fillP.setShader(new LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad, new int[]{H_PURPLE, H_INDIGO, H_CYAN}, null, Shader.TileMode.CLAMP));
                fillP.setAlpha((int) (255 * Math.max(g, 0.35f)));
                c.drawCircle(cx, cy, rad, fillP);
                fillP.setAlpha(255);
                fillP.setShader(null);
                col = 0xFFFFFFFF;
            } else {
                fillP.setColor(0x660B0C24);
                c.drawCircle(cx, cy, rad, fillP);
            }
            strokeP.setShader(hyperEdge);
            strokeP.setStrokeWidth(1f * dp);
            strokeP.setAlpha(on ? 0xFF : 0x8C);
            c.drawCircle(cx, cy, rad, strokeP);
            strokeP.setAlpha(255);
            strokeP.setShader(null);
        } else if (k.press > 0.01f || on) {
            fillP.setColor(Theme.withAlpha(on ? theme.enter : theme.icon, (int) (on ? 0x33 : 0x22 * k.press)));
            c.drawCircle(cx, cy, s * 1.75f, fillP);
        }
        iconP.setColor(col);
        iconP.setStrokeWidth(1.9f * dp);
        fillP.setColor(col);
        switch (k.id) {
            case Key.T_CLIP: {
                tmp.set(cx - s * 0.75f, cy - s * 0.8f, cx + s * 0.75f, cy + s);
                c.drawRoundRect(tmp, 2.5f * dp, 2.5f * dp, iconP);
                tmp.set(cx - s * 0.38f, cy - s * 1.05f, cx + s * 0.38f, cy - s * 0.6f);
                c.drawRoundRect(tmp, 1.5f * dp, 1.5f * dp, fillP);
                c.drawLine(cx - s * 0.4f, cy - s * 0.1f, cx + s * 0.4f, cy - s * 0.1f, iconP);
                c.drawLine(cx - s * 0.4f, cy + s * 0.35f, cx + s * 0.4f, cy + s * 0.35f, iconP);
                break;
            }
            case Key.T_EMOJI: drawSmiley(c, cx, cy, s, col); break;
            case Key.T_LANG: {
                textP.setColor(col);
                textP.setTypeface(face(Typeface.BOLD));
                textP.setTextSize(s * 1.15f);
                c.drawText("ع", cx - s * 0.45f, cy - s * 0.05f, textP);
                c.drawText("A", cx + s * 0.5f, cy + s * 0.85f, textP);
                textP.setTypeface(face(Typeface.NORMAL));
                c.drawLine(cx + s * 0.2f, cy - s * 0.85f, cx + s * 0.85f, cy - s * 0.85f, iconP);
                break;
            }
            case Key.T_MIC: drawMic(c, cx, cy + s * 0.05f, s * 0.95f, col); break;
            case Key.T_SETTINGS: {
                iconP.setStrokeWidth(3.2f * dp);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2 * i / 8;
                    c.drawLine(cx + (float) Math.cos(ang) * s * 0.7f, cy + (float) Math.sin(ang) * s * 0.7f,
                            cx + (float) Math.cos(ang) * s, cy + (float) Math.sin(ang) * s, iconP);
                }
                iconP.setStrokeWidth(1.9f * dp);
                c.drawCircle(cx, cy, s * 0.72f, fillP);
                fillP.setColor(theme.photo ? 0xFF333333 : theme.bg);
                c.drawCircle(cx, cy, s * 0.3f, fillP);
                break;
            }
            default: {
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.6f, cy, 1.9f * dp, fillP);
                break;
            }
        }
    }

    private void drawSmiley(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(1.9f * dp);
        c.drawCircle(cx, cy, s * 0.95f, iconP);
        fillP.setColor(col);
        c.drawCircle(cx - s * 0.33f, cy - s * 0.22f, 1.6f * dp, fillP);
        c.drawCircle(cx + s * 0.33f, cy - s * 0.22f, 1.6f * dp, fillP);
        tmp.set(cx - s * 0.5f, cy - s * 0.35f, cx + s * 0.5f, cy + s * 0.55f);
        c.drawArc(tmp, 30, 120, false, iconP);
    }

    /** Glass pill with a purple-to-cyan neon rim (toolbar / suggestion strip). */
    private void drawHyperPill(Canvas c, float l, float t, float rgt, float b) {
        tmp.set(l, t, rgt, b);
        float rr = tmp.height() / 2;
        strokeP.setShader(hyperEdge);
        for (int i = 3; i >= 1; i--) {
            strokeP.setStrokeWidth(i * 3f * dp);
            strokeP.setAlpha(22 / i);
            c.drawRoundRect(tmp, rr, rr, strokeP);
        }
        strokeP.setAlpha(255);
        strokeP.setShader(null);
        fillP.setColor(0x14FFFFFF);
        c.drawRoundRect(tmp, rr, rr, fillP);
        fillP.setColor(0x0FA855F7);
        c.drawRoundRect(tmp, rr, rr, fillP);
        strokeP.setShader(hyperEdge);
        strokeP.setStrokeWidth(1.1f * dp);
        strokeP.setAlpha(0x99);
        c.drawRoundRect(tmp, rr, rr, strokeP);
        strokeP.setAlpha(255);
        strokeP.setShader(null);
    }

    /** v21 futuristic glass key: translucent body, top sheen, gradient neon rim, glowing enter. */
    private void drawHyper(Canvas c, RectF r, int color, float press, boolean lit) {
        boolean enter = curType == Key.ENTER, space = curType == Key.SPACE;
        float glow = Math.max(press, lit ? 0.7f : 0f);
        if (enter) glow = Math.max(glow, 0.55f);
        if (glow > 0.01f) {
            strokeP.setShader(hyperEdge);
            for (int i = 4; i >= 1; i--) {
                strokeP.setStrokeWidth(i * 3f * dp);
                strokeP.setAlpha((int) (glow * 46 / i));
                c.drawRoundRect(r, radius, radius, strokeP);
            }
            strokeP.setAlpha(255);
            strokeP.setShader(null);
        }
        if (enter) {
            fillP.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{H_PURPLE, H_INDIGO, H_CYAN}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, fillP);
            fillP.setShader(null);
        } else if (space) {
            fillP.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, new int[]{0x4D9333EA, 0x404F46E5, 0x4D0EA5E9}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, fillP);
            fillP.setShader(null);
        } else {
            fillP.setColor(0xB30B0C24);           // dark glass base so the aurora only tints it
            c.drawRoundRect(r, radius, radius, fillP);
            fillP.setColor(color);
            c.drawRoundRect(r, radius, radius, fillP);
        }
        if (glassFill == null) {
            glassFill = new LinearGradient(0, 0, 0, 1, new int[]{0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x0D000000},
                    new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            glassEdge = new LinearGradient(0, 0, 0, 1, new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x14000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        shaderM.setScale(1, r.height());
        shaderM.postTranslate(0, r.top);
        glassFill.setLocalMatrix(shaderM);
        fillP.setShader(glassFill);
        fillP.setAlpha(enter ? 150 : 110);
        c.drawRoundRect(r, radius, radius, fillP);
        fillP.setAlpha(255);
        fillP.setShader(null);
        if (press > 0.01f && !enter) {
            fillP.setColor(Theme.withAlpha(H_PURPLE, (int) (press * 0x50)));
            c.drawRoundRect(r, radius, radius, fillP);
        }
        strokeP.setShader(enter ? null : hyperEdge);
        if (enter) strokeP.setColor(0x80FFFFFF);
        strokeP.setStrokeWidth((press > 0.3f || lit || space ? 1.3f : 0.9f) * dp);
        strokeP.setAlpha(enter ? 0x80 : press > 0.3f || lit ? 255 : space ? 0xCC : 0x80);
        tmp.set(r.left + 0.5f * dp, r.top + 0.5f * dp, r.right - 0.5f * dp, r.bottom - 0.5f * dp);
        c.drawRoundRect(tmp, radius, radius, strokeP);
        strokeP.setAlpha(255);
        strokeP.setShader(null);
    }

    private void drawBody(Canvas c, RectF r, int color, float press, boolean lit) {
        if (theme.hyper && hyperEdge != null) { drawHyper(c, r, color, press, lit); return; }
        if (theme.neon) {
            float glow = Math.max(press, lit ? 0.6f : 0f);
            if (glow > 0.01f) {
                for (int i = 4; i >= 1; i--) {
                    strokeP.setStrokeWidth(i * 3f * dp);
                    strokeP.setColor(Theme.withAlpha(theme.glow, (int) (glow * 40 / i)));
                    c.drawRoundRect(r, radius, radius, strokeP);
                }
            }
            fillP.setColor(color);
            c.drawRoundRect(r, radius, radius, fillP);
            strokeP.setStrokeWidth(1f * dp);
            strokeP.setColor(press > 0.3f || lit ? Theme.withAlpha(theme.glow, 0xCC) : 0x2EFFFFFF);
            c.drawRoundRect(r, radius, radius, strokeP);
        } else if (theme.glass) {
            // light 3D glass: soft drop, translucent body, top gloss, bright upper rim
            fillP.setColor(0x1F000000);
            tmp.set(r.left, r.top + 2f * dp, r.right, r.bottom + 2f * dp);
            c.drawRoundRect(tmp, radius, radius, fillP);
            fillP.setColor(color);
            c.drawRoundRect(r, radius, radius, fillP);
            if (glassFill == null) {
                glassFill = new LinearGradient(0, 0, 0, 1, new int[]{0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x0D000000},
                        new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                glassEdge = new LinearGradient(0, 0, 0, 1, new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x14000000},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
            shaderM.setScale(1, r.height());
            shaderM.postTranslate(0, r.top);
            glassFill.setLocalMatrix(shaderM);
            glassEdge.setLocalMatrix(shaderM);
            fillP.setShader(glassFill);
            c.drawRoundRect(r, radius, radius, fillP);
            fillP.setShader(null);
            strokeP.setShader(glassEdge);
            strokeP.setStrokeWidth(1f * dp);
            tmp.set(r.left + 0.5f * dp, r.top + 0.5f * dp, r.right - 0.5f * dp, r.bottom - 0.5f * dp);
            c.drawRoundRect(tmp, radius, radius, strokeP);
            strokeP.setShader(null);
            drawBorder(c, r, press, lit);
        } else {
            boolean opaque = (color >>> 24) == 0xFF;
            if (opaque && (theme.shadow >>> 24) != 0) {
                fillP.setColor(theme.shadow);
                tmp.set(r.left, r.top + 1.3f * dp, r.right, r.bottom + 1.3f * dp);
                c.drawRoundRect(tmp, radius, radius, fillP);
            }
            fillP.setColor(color);
            c.drawRoundRect(r, radius, radius, fillP);
            if (sig && opaque) {   // W116: soft pillow sheen — light on the top, a breath of shade at the foot
                if (sheen == null) sheen = new LinearGradient(0, 0, 0, 1,
                        new int[]{0x2EFFFFFF, 0x00FFFFFF, 0x00000000, 0x10000000},
                        new float[]{0f, 0.48f, 0.7f, 1f}, Shader.TileMode.CLAMP);
                shaderM.setScale(1, r.height());
                shaderM.postTranslate(0, r.top);
                sheen.setLocalMatrix(shaderM);
                fillP.setShader(sheen);
                c.drawRoundRect(r, radius, radius, fillP);
                fillP.setShader(null);
            }
            if (!opaque && theme.border == 0) {
                strokeP.setStrokeWidth(0.8f * dp);
                strokeP.setColor(0x26FFFFFF);
                c.drawRoundRect(r, radius, radius, strokeP);
            }
            drawBorder(c, r, press, lit);
        }
    }

    private void drawBorder(Canvas c, RectF r, float press, boolean lit) {
        if (theme.border == 0) return;
        float bw = theme.borderW * dp;
        strokeP.setStrokeWidth(bw);
        strokeP.setColor(press > 0.3f || lit ? blend(theme.border | 0xFF000000, theme.enter, 0.6f) : theme.border);
        tmp.set(r.left + bw / 2, r.top + bw / 2, r.right - bw / 2, r.bottom - bw / 2);
        c.drawRoundRect(tmp, radius, radius, strokeP);
    }

    /** Soft light bloom on a pressed key (optional). */
    private void drawPressLight(Canvas c, RectF r, float press) {
        float rad = Math.max(r.width(), r.height()) * 0.9f;
        fillP.setShader(new RadialGradient(r.centerX(), r.centerY(), rad,
                Theme.withAlpha(theme.enter, (int) (0x55 * press)), 0, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, fillP);
        fillP.setShader(null);
        strokeP.setStrokeWidth(1.4f * dp);
        strokeP.setColor(Theme.withAlpha(theme.enter, (int) (0xB0 * press)));
        c.drawRoundRect(r, radius, radius, strokeP);
    }

    private void drawKey(Canvas c, Key k) {
        boolean body = k.type != Key.LABEL && k.type != Key.SUGG && !(k.type == Key.CHAR && k.id == 1);
        float s = 1f, ty = 0f;
        if (body && anims && k.press > 0.01f) s = 1f - 0.06f * k.press;
        if (body && motionOn()) {
            long now = SystemClock.uptimeMillis();
            if (openAt > 0 && panel == PANEL_KEYS) {
                float e = Motion.entrance(now - openAt, Motion.waveDelay(k.rect.centerX(), k.rect.centerY() - toolH,
                        getWidth(), getHeight() - toolH));
                if (e <= 0.001f) return;          // not risen yet
                if (e != 1f) { s *= 0.5f + 0.5f * e; ty = (1f - e) * 18 * dp; }
            }
            if (k.rel > 0 && k.press == 0f) s *= 1f + Motion.bounce(now - k.rel);
        }
        if (s == 1f && ty == 0f) { drawKeyInner(c, k); if (body) drawTouch(c, k); }
        else {
            c.save();
            c.translate(0, ty);
            c.scale(s, s, k.rect.centerX(), k.rect.centerY());
            drawKeyInner(c, k);
            if (body) drawTouch(c, k);
            c.restore();
        }
        if (k.type == Key.ENTER && sig && !reduceMotion && anims && hasText && k.press == 0f) {
            float lv = Motion.breath(SystemClock.uptimeMillis() - lastType, true);
            if (lv > 0.01f && lastType > 0) drawBreath(c, k, lv);
        }
    }

    private void drawKeyInner(Canvas c, Key k) {
        if ((k.type == Key.CHAR || k.type == Key.PHRASE) && k.label.isEmpty()) return;
        RectF r = k.rect;
        boolean emojiCell = k.type == Key.CHAR && k.id == 1;
        boolean shiftLit = k.type == Key.SHIFT && shiftState != SHIFT_OFF;
        boolean tabOn = (k.type == Key.EMOJI_TAB && k.id == emojiTab)
                || (k.type == Key.CLIP_TAB && k.id == clipTab && k.id < 50);
        boolean selOn = (k.type == Key.EDIT && k.id == E_SELECT && selecting)
                || (k.type == Key.DECOR && k.id == decor && decor != Decor.OFF);

        if (emojiCell) {
            if (k.press > 0.01f) {
                fillP.setColor(Theme.withAlpha(theme.text, (int) (0x22 * k.press)));
                c.drawRoundRect(r, radius, radius, fillP);
            }
        } else if (k.type == Key.LABEL) {
            // no body
        } else {
            int base = k.type == Key.ENTER ? theme.enter : k.isSpecial() ? theme.special
                    : k.type == Key.SPACE && theme.space != 0 ? theme.space : theme.key;
            if (tabOn || selOn) base = theme.neon ? Theme.withAlpha(theme.enter, 0x55) : theme.enter;
            int col = k.press > 0.01f && !theme.neon ? blend(base, theme.keyPressed, k.press) : base;
            curType = k.type;
            drawBody(c, r, col, theme.hyper ? Math.max(k.press, tabOn || selOn ? 0.6f : 0f) : pressGlow ? k.press : 0f, shiftLit);
            curType = -1;
            if (cues && (shiftLit || tabOn || selOn)) {
                // state mark that does not rely on colour: bar(s) under the key content
                int mc = (tabOn || selOn) ? theme.enterText : theme.text;
                fillP.setColor(mc);
                float bw = Math.min(r.width() * 0.42f, 22 * dp), bh = 2.6f * dp, by = r.bottom - 5 * dp;
                tmp.set(r.centerX() - bw / 2, by - bh, r.centerX() + bw / 2, by);
                c.drawRoundRect(tmp, bh, bh, fillP);
                if (shiftLit && shiftState == SHIFT_LOCK) {
                    tmp.offset(0, -bh * 1.9f);
                    c.drawRoundRect(tmp, bh, bh, fillP);
                }
            }
            if (pressGlow && k.press > 0.01f && !theme.neon) drawPressLight(c, r, k.press);
            if (theme.neon && !theme.hyper && k.press > 0.01f && k.type != Key.ENTER) {
                fillP.setColor(Theme.withAlpha(theme.glow, (int) (k.press * 0x40)));
                c.drawRoundRect(r, radius, radius, fillP);
            }
        }

        int fg = theme.hyper && (k.type == Key.ENTER || selOn || tabOn) ? 0xFFFFFFFF
                : k.type == Key.ENTER || selOn || (tabOn && !theme.neon) ? theme.enterText
                : k.isSpecial() ? theme.specialText : theme.text;
        if (k.type == Key.LABEL) fg = theme.hint;
        float cx = r.centerX(), cy = r.centerY();
        float fit = clamp(Math.min(scale, r.width() / (30 * dp)), 0.7f, 1.15f);
        float is = 10 * dp * clamp(scale, 0.8f, 1.15f);

        switch (k.type) {
            case Key.DEL: drawBackspace(c, cx, cy, is, fg); return;
            case Key.SHIFT: drawShift(c, cx, cy, is, shiftLit ? theme.enter : fg, shiftState == SHIFT_LOCK); return;
            case Key.ENTER: drawEnter(c, cx, cy, is, fg); return;
            case Key.TO_EMOJI: drawSmiley(c, cx, cy, is * 1.05f, fg); return;
            case Key.OH_EXPAND: drawExpand(c, cx, cy, is, fg); return;
            case Key.OH_SWITCH: drawSwitch(c, cx, cy, is, fg); return;
            case Key.SPACE: {
                if (k.label.isEmpty()) return;
                float since = openAt > 0 && motionOn() ? SystemClock.uptimeMillis() - openAt : -1;
                if (since >= 0) {
                    float sx = Motion.shimmer(since);
                    if (sx >= 0) drawShimmer(c, r, sx);
                    float ba = decorName == null ? Motion.brand(since) : 0f;
                    if (ba > 0.01f) {   // W116: the wordmark greets you, then hands over to the language
                        textP.setColor(Theme.withAlpha(theme.neon ? theme.glow : theme.enter, (int) (255 * ba)));
                        textP.setTextSize(13 * dp * clamp(scale, 0.8f, 1.1f));
                        textP.setLetterSpacing(0.28f);
                        c.drawText("♛ NEOKEY", cx, baseline(r, textP), textP);
                        textP.setLetterSpacing(0f);
                        if (ba > 0.99f) return;
                    }
                }
                textP.setColor(Theme.withAlpha(theme.hint, (int) ((theme.hint >>> 24) * (since >= 0 ? 1 - Motion.brand(decorName == null ? since : -1) : 1f))));
                textP.setTextSize(14 * dp * clamp(scale, 0.8f, 1.1f));
                String l = decorName != null ? decorName : (swipeCursor ? k.label : "‹  " + k.label + "  ›");
                if (decorName != null) textP.setColor(theme.enter);
                String ico = decorName != null ? "sparkle" : incognito ? "incognito" : null;
                if (ico != null) {
                    float isz = 16 * dp * clamp(scale, 0.8f, 1.1f), gap = 6 * dp;
                    float tw = textP.measureText(l), total = tw + isz + gap;
                    float left = cx - total / 2;
                    // icon on the start side (right in RTL)
                    float icx = rtl ? left + tw + gap + isz / 2 : left + isz / 2;
                    float tcx = rtl ? left + tw / 2 : left + isz + gap + tw / 2;
                    Icons.draw(c, getContext(), ico, icx, r.centerY(), isz, decorName != null ? theme.enter : theme.hint);
                    c.drawText(l, tcx, baseline(r, textP), textP);
                } else c.drawText(l, cx, baseline(r, textP), textP);
                return;
            }
            case Key.CLIP_TAB:
                if (k.id == 50) { Icons.draw(c, getContext(), "search", cx, cy, 18 * dp * clamp(scale, 0.8f, 1.1f), fg); return; }
                if (k.id == 51) {
                    textP.setColor(fg);
                    textP.setTextSize(13 * dp * clamp(scale, 0.8f, 1.1f));
                    CharSequence q = TextUtils.ellipsize("✕  " + clipQuery, textP, r.width() - 10 * dp, TextUtils.TruncateAt.END);
                    c.drawText(q.toString(), cx, baseline(r, textP), textP);
                    return;
                }
                break;
            case Key.CLIP_ACT:
                if (k.text == null) break;
                // fall through: icon + label like a "more" tile
            case Key.MORE_ITEM: {
                textP.setColor(fg);
                float ms = clamp(Math.min(scale, r.height() / (44 * dp)), 0.7f, 1.1f);
                Icons.draw(c, getContext(), k.text, cx, cy - 8 * dp * ms, 24 * dp * ms, fg);
                textP.setTextSize(11.5f * dp * ms);
                CharSequence ml = TextUtils.ellipsize(k.label, textP, r.width() - 6 * dp, TextUtils.TruncateAt.END);
                c.drawText(ml.toString(), cx, cy + 15 * dp * ms, textP);
                return;
            }
            case Key.DECOR: {
                textP.setColor(fg);
                textP.setTextSize(17 * dp * clamp(scale, 0.8f, 1.1f));
                CharSequence e = TextUtils.ellipsize(k.label, textP, r.width() - 14 * dp, TextUtils.TruncateAt.END);
                c.drawText(e.toString(), cx, baseline(r, textP), textP);
                return;
            }
            case Key.PHRASE: {
                textP.setColor(fg);
                textP.setTextSize(14.5f * dp * clamp(scale, 0.8f, 1.1f));
                CharSequence e = TextUtils.ellipsize(k.label, textP, r.width() - 14 * dp, TextUtils.TruncateAt.END);
                c.drawText(e.toString(), cx, baseline(r, textP), textP);
                return;
            }
            default: break;
        }

        if (k.type == Key.EMOJI_TAB && k.text != null) {
            Icons.draw(c, getContext(), k.text, cx, cy, 21 * dp * clamp(scale, 0.8f, 1.1f), fg);
            return;
        }
        String label = labelFor(k);
        textP.setColor(fg);
        if (k.type == Key.CHAR) {
            float size = emojiCell ? 26 : (label.length() > 1 ? 18 : 24);
            textP.setTextSize(size * dp * fit * (emojiCell ? 1f : keyScale));
            while (label.length() > 1 && textP.measureText(label) > r.width() - 6 * dp && textP.getTextSize() > 9 * dp)
                textP.setTextSize(textP.getTextSize() - dp);
        } else {
            textP.setTextSize((k.type == Key.EMOJI_TAB ? 19 : k.type == Key.EDIT ? 14.5f : 16) * dp * clamp(scale, 0.75f, 1.1f));
            while (textP.measureText(label) > r.width() - 8 * dp && textP.getTextSize() > 9 * dp)
                textP.setTextSize(textP.getTextSize() - dp);
        }
        c.drawText(label, cx, baseline(r, textP), textP);

        if (k.hint != null && !emojiCell) {
            hintP.setColor(theme.hint);
            hintP.setTextSize(10.5f * dp * fit * keyScale);
            c.drawText(k.hint, r.right - 7 * dp * fit, r.top + 13 * dp * fit, hintP);
        }
    }

    private void drawClips(Canvas c) {
        if (clipMenu != null) { drawClipMenuHead(c); return; }
        if (clipKeys.isEmpty() && !(clipTab == ClipRepo.TAB_CATS && clipCat == 0 && clipQuery == null && !clipCats.isEmpty())) {
            textP.setColor(theme.hint);
            textP.setTextSize(15 * dp);
            float cy = toolH + areaH * 2f / 5f;
            String empty = clipQuery != null ? "ما فيه نتائج لـ «" + clipQuery + "»"
                    : clipTab == 1 ? "ما فيه نصوص مثبّتة" : clipTab == 2 ? "المفضلة فاضية" : clipTab == 3 ? "الأرشيف فاضي"
                    : clipTab == 4 ? "ما فيه قوالب — من خيارات أي نص اختر «حفظ كقالب»"
                    : clipTab == 5 && clipCat == 0 ? "ما فيه تصنيفات — أنشئها من «تعديل» أي نص"
                    : clipTab == 5 ? "ما فيه نصوص في هذا التصنيف"
                    : "الحافظة فارغة — انسخ أي نص ويظهر هنا";
            c.drawText(clipQuery != null ? empty : L.t(empty), (contentL + contentR) / 2, cy, textP);
            textP.setTextSize(12.5f * dp);
            c.drawText(L.t("اضغط مطولاً على أي نص لخياراته"), (contentL + contentR) / 2, cy + 24 * dp, textP);
            return;
        }
        float sc = clamp(scale, 0.8f, 1.1f);
        long now = SystemClock.uptimeMillis();
        boolean mo = motionOn();
        List<Key> order = new ArrayList<>(clipKeys);
        if (dragKey != null && order.remove(dragKey)) order.add(dragKey);
        for (Key k : order) {
            ClipItem ci = k.clip;
            boolean dragging = k == dragKey;
            if (dragging) {
                for (Key t : clipKeys) if (t != k && t.rect.contains(dragX, dragY)) {
                    dragP.setColor(theme.enter);
                    dragP.setStrokeWidth(2 * dp);
                    c.drawRoundRect(t.rect, radius, radius, dragP);
                }
                c.save();
                c.translate(dragX - downX, dragY - downY);
                c.scale(1.05f, 1.05f, k.rect.centerX(), k.rect.centerY());
            }
            float in = mo && !dragging ? Motion.chipIn(now - clipShownAt, clipKeys.indexOf(k)) : 1f;   // W121 deal-in
            if (in < 1f) { clipAnim = true; c.save(); c.translate(0, (1f - in) * 14 * dp); c.scale(0.92f + 0.08f * in, 0.92f + 0.08f * in, k.rect.centerX(), k.rect.centerY()); }
            int col = k.press > 0.01f && !theme.neon ? blend(theme.key, theme.keyPressed, k.press) : theme.key;
            drawBody(c, k.rect, col, k.press, false);
            drawTouch(c, k);
            {   // W121: a glance tag and the clip's age at the card's start-bottom corner
                String tag = Dialect.clipTag(ci.preview), age = Dialect.ago(System.currentTimeMillis() - Math.max(ci.used, ci.created));
                textP.setTextSize(9.5f * dp);
                textP.setColor(theme.hint);
                String meta = tag == null ? age : tag + "  " + age;
                float mw = textP.measureText(meta);
                c.drawText(meta, rtl ? k.rect.right - 7 * dp - mw / 2 : k.rect.left + 7 * dp + mw / 2, k.rect.bottom - 4 * dp, textP);
            }
            float pz = 13 * dp, pad = 8 * dp;
            int marks = (ci.pinned ? 1 : 0) + (ci.fav ? 1 : 0);
            float markW = marks * (pz + 4 * dp);
            float textW = k.rect.width() - 2 * pad - markW - 18 * dp;
            boolean twoLine = k.rect.height() > 40 * dp;
            String body = ci.preview.replace('\n', ' ').replace('\t', ' ');
            textP.setColor(theme.text);
            if (ci.title != null) {
                textP.setFakeBoldText(true);
                textP.setTextSize(13.5f * dp * sc);
                CharSequence t = TextUtils.ellipsize(ci.title, textP, textW, TextUtils.TruncateAt.END);
                float ty = twoLine ? k.rect.centerY() - 3 * dp : baseline(k.rect, textP);
                c.drawText(t.toString(), k.rect.centerX(), ty, textP);
                textP.setFakeBoldText(false);
                if (twoLine) {
                    textP.setColor(theme.hint);
                    textP.setTextSize(11 * dp * sc);
                    CharSequence e = TextUtils.ellipsize(body, textP, k.rect.width() - 2 * pad, TextUtils.TruncateAt.END);
                    c.drawText(e.toString(), k.rect.centerX(), k.rect.centerY() + 12 * dp, textP);
                }
            } else {
                textP.setTextSize(14 * dp * sc);
                CharSequence e = TextUtils.ellipsize(body, textP, textW, TextUtils.TruncateAt.END);
                String shown = e.toString();
                float by = baseline(k.rect, textP);
                if (ci.matchStart >= 0 && ci.matchEnd <= shown.length() && ci.matchEnd > ci.matchStart) {
                    // underline the search hit (text is drawn centred; measure from its left edge)
                    float tw = textP.measureText(shown), left = k.rect.centerX() - tw / 2;
                    android.text.TextPaint tp = new android.text.TextPaint(textP);
                    android.text.StaticLayout sl = new android.text.StaticLayout(shown, tp, (int) Math.ceil(tw) + 2,
                            android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
                    float x1 = sl.getPrimaryHorizontal(ci.matchStart), x2 = sl.getPrimaryHorizontal(ci.matchEnd);
                    if (x2 < x1) { float t = x1; x1 = x2; x2 = t; }
                    fillP.setColor(Theme.withAlpha(theme.enter, 0x55));
                    tmp.set(left + x1, by - textP.getTextSize(), left + x2, by + 4 * dp);
                    c.drawRoundRect(tmp, 3 * dp, 3 * dp, fillP);
                }
                textP.setColor(theme.text);
                c.drawText(shown, k.rect.centerX(), by, textP);
            }
            float mx = rtl ? k.rect.right - pad - pz / 2 : k.rect.left + pad + pz / 2;
            float my = k.rect.top + pad + pz / 2;
            if (ci.pinned) { Icons.draw(c, getContext(), "pin", mx, my, pz, theme.enter); mx += rtl ? -(pz + 4 * dp) : pz + 4 * dp; }
            if (ci.fav) Icons.draw(c, getContext(), "star", mx, my, pz, theme.enter);
            {
                float dx = rtl ? k.rect.left + 13 * dp : k.rect.right - 13 * dp, dy = k.rect.top + 11 * dp;
                fillP.setColor(theme.hint);
                for (int d = 0; d < 3; d++) c.drawCircle(dx, dy + d * 4.5f * dp, 1.7f * dp, fillP);
            }
            if (ci.isLong()) {
                int n = ci.len;
                String badge = n >= 1000 ? String.format(java.util.Locale.US, "%.1fk", n / 1000f) : String.valueOf(n);
                textP.setTextSize(9 * dp);
                textP.setColor(theme.hint);
                float bw = textP.measureText(badge);
                c.drawText(badge, rtl ? k.rect.left + 6 * dp + bw / 2 : k.rect.right - 6 * dp - bw / 2, k.rect.bottom - 4 * dp, textP);
            }
            if (dragging) c.restore();
            if (in < 1f) c.restore();
        }
    }

    private void drawClipMenuHead(Canvas c) {
        ClipItem m = clipMenu;
        float rh = areaH / 5f;
        float top = toolH;
        tmp.set(contentL + gap / 2, top + gap / 2, contentR - gap / 2, top + rh * 0.9f - gap / 2);
        drawBody(c, tmp, theme.key, 0f, false);
        textP.setColor(theme.text);
        textP.setTextSize(13.5f * dp * clamp(scale, 0.8f, 1.1f));
        String s = (m.title != null ? m.title + " — " : "") + m.preview.replace('\n', ' ');
        CharSequence e = TextUtils.ellipsize(s, textP, tmp.width() - 16 * dp, TextUtils.TruncateAt.END);
        c.drawText(e.toString(), tmp.centerX(), tmp.centerY() - 1 * dp, textP);
        textP.setColor(theme.hint);
        textP.setTextSize(10.5f * dp);
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        String info = String.format(java.util.Locale.US, "%,d", m.len) + " " + L.t("حرف") + "  •  " + df.format(new java.util.Date(m.created))
                + (m.modified > m.created + 1000 ? "  •  " + L.t("عُدّل") : "");
        c.drawText(info, tmp.centerX(), tmp.centerY() + 15 * dp, textP);
    }

    // ---- icons
    private void drawBackspace(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(1.8f * dp);
        float l = cx - s * 1.2f, r = cx + s * 1.1f, t = cy - s * 0.8f, b = cy + s * 0.8f;
        path.reset();
        path.moveTo(l, cy);
        path.lineTo(l + s * 0.65f, t);
        path.lineTo(r, t);
        path.lineTo(r, b);
        path.lineTo(l + s * 0.65f, b);
        path.close();
        c.drawPath(path, iconP);
        float x0 = cx + s * 0.05f, d = s * 0.35f;
        c.drawLine(x0 - d, cy - d, x0 + d, cy + d, iconP);
        c.drawLine(x0 - d, cy + d, x0 + d, cy - d, iconP);
    }

    private void drawShift(Canvas c, float cx, float cy, float s, int col, boolean lock) {
        iconP.setColor(col);
        iconP.setStrokeWidth(1.8f * dp);
        path.reset();
        path.moveTo(cx, cy - s);
        path.lineTo(cx + s * 0.95f, cy);
        path.lineTo(cx + s * 0.45f, cy);
        path.lineTo(cx + s * 0.45f, cy + s * 0.7f);
        path.lineTo(cx - s * 0.45f, cy + s * 0.7f);
        path.lineTo(cx - s * 0.45f, cy);
        path.lineTo(cx - s * 0.95f, cy);
        path.close();
        if (shiftState != SHIFT_OFF) {
            fillP.setColor(col);
            c.drawPath(path, fillP);
        }
        c.drawPath(path, iconP);
        if (lock) c.drawLine(cx - s * 0.45f, cy + s * 1.05f, cx + s * 0.45f, cy + s * 1.05f, iconP);
    }

    private void drawExpand(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(1.9f * dp);
        float a = s * 0.8f;
        c.drawLine(cx - a, cy + a, cx + a, cy - a, iconP);
        path.reset();
        path.moveTo(cx + a * 0.1f, cy - a); path.lineTo(cx + a, cy - a); path.lineTo(cx + a, cy - a * 0.1f);
        path.moveTo(cx - a * 0.1f, cy + a); path.lineTo(cx - a, cy + a); path.lineTo(cx - a, cy + a * 0.1f);
        c.drawPath(path, iconP);
    }

    private void drawSwitch(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(1.9f * dp);
        float a = s * 0.9f;
        path.reset();
        path.moveTo(cx - a, cy - a * 0.4f); path.lineTo(cx + a, cy - a * 0.4f);
        path.moveTo(cx + a * 0.5f, cy - a * 0.85f); path.lineTo(cx + a, cy - a * 0.4f); path.lineTo(cx + a * 0.5f, cy + a * 0.05f);
        path.moveTo(cx + a, cy + a * 0.45f); path.lineTo(cx - a, cy + a * 0.45f);
        path.moveTo(cx - a * 0.5f, cy); path.lineTo(cx - a, cy + a * 0.45f); path.lineTo(cx - a * 0.5f, cy + a * 0.9f);
        c.drawPath(path, iconP);
    }

    private void drawEnter(Canvas c, float cx, float cy, float s, int col) {
        iconP.setColor(col);
        iconP.setStrokeWidth(2.3f * dp);
        path.reset();
        switch (enterKind) {
            case ENTER_DONE:
                path.moveTo(cx - s * 0.9f, cy);
                path.lineTo(cx - s * 0.25f, cy + s * 0.65f);
                path.lineTo(cx + s * 0.95f, cy - s * 0.6f);
                c.drawPath(path, iconP);
                break;
            case ENTER_SEARCH:
                c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.62f, iconP);
                c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.85f, cy + s * 0.85f, iconP);
                break;
            case ENTER_SEND:
                path.moveTo(cx - s, cy - s * 0.85f);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s, cy + s * 0.85f);
                path.lineTo(cx - s * 0.6f, cy);
                path.close();
                c.drawPath(path, iconP);
                break;
            case ENTER_GO:
                c.drawLine(cx - s, cy, cx + s, cy, iconP);
                path.moveTo(cx + s * 0.35f, cy - s * 0.65f);
                path.lineTo(cx + s, cy);
                path.lineTo(cx + s * 0.35f, cy + s * 0.65f);
                c.drawPath(path, iconP);
                break;
            default:
                path.moveTo(cx + s * 0.9f, cy - s * 0.8f);
                path.lineTo(cx + s * 0.9f, cy + s * 0.2f);
                path.lineTo(cx - s * 0.9f, cy + s * 0.2f);
                path.moveTo(cx - s * 0.35f, cy - s * 0.35f);
                path.lineTo(cx - s * 0.9f, cy + s * 0.2f);
                path.lineTo(cx - s * 0.35f, cy + s * 0.75f);
                c.drawPath(path, iconP);
                break;
        }
    }

    private String labelFor(Key k) {
        if (k.type == Key.CHAR) {
            String l = shiftState != SHIFT_OFF && panel == PANEL_KEYS ? k.label.toUpperCase() : k.label;
            return isMark(l) ? "ـ" + l : l;
        }
        return k.label;
    }

    private static boolean isMark(String s) {
        return s.length() == 1 && s.charAt(0) >= 0x064B && s.charAt(0) <= 0x0652;
    }

    private boolean drawPreview(Canvas c, Key k, long now) {
        float bw = Math.max(k.rect.width() * 1.2f, 40 * dp);
        float bh = k.rect.height() * 1.15f;
        float left = clamp(k.rect.centerX() - bw / 2, fullL, fullR - bw);
        float top = Math.max(2 * dp, k.rect.top - bh - 6 * dp);
        RectF b = new RectF(left, top, left + bw, top + bh);
        float p = motionOn() && k.tdown > 0 ? Motion.popIn(now - k.tdown) : 1f;   // W118: the bubble rises out of the key
        if (p <= 0.001f) return true;
        c.save();
        c.translate(0, (1f - Math.min(p, 1f)) * bh * 0.35f);
        c.scale(0.6f + 0.4f * p, 0.6f + 0.4f * p, k.rect.centerX(), b.bottom);
        drawBubble(c, b);
        textP.setColor(theme.popupText);
        textP.setTextSize(30 * dp * clamp(scale, 0.75f, 1.1f));
        c.drawText(labelFor(k), b.centerX(), baseline(b, textP), textP);
        c.restore();
        return p != 1f;
    }

    /** W118: the space bar's cursor glide (a thumb under the finger) and the delete swipe's erase trail. */
    private void drawDrag(Canvas c, Key k) {
        RectF r = k.rect;
        if (k.type == Key.SPACE) {
            float x = clamp(fingerX, r.left + r.height() * 0.4f, r.right - r.height() * 0.4f), cy = r.centerY();
            tmp.set(r.left + 8 * dp, cy - 2 * dp, r.right - 8 * dp, cy + 2 * dp);
            fillP.setColor(Theme.withAlpha(theme.enter, 0x40));
            c.drawRoundRect(tmp, 2 * dp, 2 * dp, fillP);
            fillP.setColor(Theme.withAlpha(theme.enter, 0x38));
            c.drawCircle(x, cy, r.height() * 0.36f, fillP);
            fillP.setColor(theme.enter);
            c.drawCircle(x, cy, r.height() * 0.16f, fillP);
            iconP.setColor(Theme.withAlpha(theme.enter, 0xB0));
            iconP.setStrokeWidth(2 * dp);
            float a = 5 * dp, gx = r.height() * 0.62f;
            c.drawLine(x - gx, cy, x - gx + a, cy - a, iconP); c.drawLine(x - gx, cy, x - gx + a, cy + a, iconP);
            c.drawLine(x + gx, cy, x + gx - a, cy - a, iconP); c.drawLine(x + gx, cy, x + gx - a, cy + a, iconP);
        } else {
            float y = r.centerY(), l = Math.min(r.centerX(), fingerX), rr = Math.max(r.centerX(), fingerX);
            fillP.setShader(new LinearGradient(r.centerX(), 0, fingerX, 0, Theme.withAlpha(0xFFEF4444, 0x20),
                    Theme.withAlpha(0xFFEF4444, 0xB0), Shader.TileMode.CLAMP));
            tmp.set(l, y - 3 * dp, rr, y + 3 * dp);
            c.drawRoundRect(tmp, 3 * dp, 3 * dp, fillP);
            fillP.setShader(null);
            fillP.setColor(0xFFEF4444);
            c.drawCircle(fingerX, y, 6 * dp, fillP);
        }
    }

    private boolean drawAlts(Canvas c, long now, float dt) {
        boolean mo = motionOn();
        long t = now - altAt;
        float p = mo ? Motion.popIn(t) : 1f;   // W118: the popup springs out of the held key
        if (altPill < 0 || !mo) altPill = altSel;
        else altPill = Motion.approach(altPill, altSel, dt);
        float ax = active != null ? clamp(active.rect.centerX(), altRect.left, altRect.right) : altRect.centerX();
        c.save();
        c.scale(0.5f + 0.5f * p, 0.5f + 0.5f * p, ax, altRect.bottom);
        drawBubble(c, altRect);
        float cw = altRect.width() / altList.length;
        tmp.set(altRect.left + altPill * cw + 3 * dp, altRect.top + 4 * dp,
                altRect.left + (altPill + 1) * cw - 3 * dp, altRect.bottom - 4 * dp);
        fillP.setColor(theme.enter);
        c.drawRoundRect(tmp, radius, radius, fillP);
        boolean anim = p != 1f || altPill != altSel;
        for (int i = 0; i < altList.length; i++) {
            RectF cell = new RectF(altRect.left + i * cw + 3 * dp, altRect.top + 4 * dp,
                    altRect.left + (i + 1) * cw - 3 * dp, altRect.bottom - 4 * dp);
            float e = mo ? Motion.chipIn(t, Math.abs(i - altSel)) : 1f;
            anim |= e < 1f;
            String s = altList[i];
            if (shiftState != SHIFT_OFF) s = s.toUpperCase();
            if (isMark(s)) s = "ـ" + s;
            float on = Math.max(0f, 1f - Math.abs(altPill - i));
            textP.setColor(fade(blend(theme.popupText, theme.enterText, on), e));
            textP.setTextSize(22 * dp * clamp(scale, 0.75f, 1.1f) * (1f + 0.16f * on));
            c.drawText(s, cell.centerX(), baseline(cell, textP) + (1f - e) * 10 * dp, textP);
        }
        c.restore();
        return anim;
    }

    private void drawBubble(Canvas c, RectF b) {
        if (theme.neon) {
            for (int i = 4; i >= 1; i--) {
                strokeP.setStrokeWidth(i * 3.5f * dp);
                strokeP.setColor(Theme.withAlpha(theme.glow, 34 / i));
                c.drawRoundRect(b, radius + 2 * dp, radius + 2 * dp, strokeP);
            }
        } else {
            fillP.setColor(0x30000000);
            tmp.set(b.left - dp, b.top + dp, b.right + dp, b.bottom + 3 * dp);
            c.drawRoundRect(tmp, radius + 3 * dp, radius + 3 * dp, fillP);
        }
        fillP.setColor(theme.popup);
        c.drawRoundRect(b, radius + 2 * dp, radius + 2 * dp, fillP);
        if (theme.neon) {
            strokeP.setStrokeWidth(1f * dp);
            strokeP.setColor(Theme.withAlpha(theme.glow, 0xCC));
            c.drawRoundRect(b, radius + 2 * dp, radius + 2 * dp, strokeP);
        }
    }

    private static float baseline(RectF r, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return r.centerY() - (fm.ascent + fm.descent) / 2;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int blend(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) touchMark = e.getEventTime();
        boolean r = handleTouch(e);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP)
            Perf.record("key", SystemClock.uptimeMillis() - e.getEventTime());
        return r;
    }

    private boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (active != null) release(true);
                float x = e.getX(idx), y = e.getY(idx);
                if (voiceState != 0 && y < toolH) { listener.onVoice(); return true; }
                Key k = find(x, y);
                if (k == null) return true;
                active = k;
                activePointer = e.getPointerId(idx);
                downX = lastX = x;
                downY = y;
                swiped = longPressed = false;
                altList = null;
                k.target = 1f;
                k.rel = 0;
                k.tx = x; k.ty = y; k.tdown = SystemClock.uptimeMillis(); k.tup = 0;   // W117 ink
                fingerX = x; repN = 0;
                if (k.type == Key.CHAR || k.type == Key.SPACE || k.type == Key.DEL) noteType();
                if (k.type == Key.ENTER) lastType = 0;
                buzz();
                click(k);
                if (fxType >= 0 && !reduceMotion && panel == PANEL_KEYS) {
                    pfx.spawn(fxType, k.rect.centerX(), k.rect.centerY(), Math.min(k.rect.width(), k.rect.height()),
                            theme.enter, k.type == Key.CHAR ? k.label : null, SystemClock.uptimeMillis());
                    invalidate();
                }
                if (k.type == Key.DEL) {
                    listener.onDelete();
                    handler.postDelayed(repeat, 400);
                } else if (k.type == Key.EDIT && (k.id == E_LEFT || k.id == E_RIGHT || k.id == E_UP || k.id == E_DOWN)) {
                    listener.onEdit(k.id);
                    handler.postDelayed(repeat, 400);
                } else if (k.type == Key.SHIFT) {
                    listener.onShift();
                }
                if (k.alts != null || k.type == Key.SPACE || k.type == Key.CLIP
                        || (k.type == Key.SUGG && k.label != null)) handler.postDelayed(longPress, lpDelay);
                glideCand = glideOn && panel == PANEL_KEYS && action == MotionEvent.ACTION_DOWN && isLetterKey(k);
                gliding = false;
                if (glideCand) {
                    gDownY = y;
                    gSeq.setLength(0);
                    gSeq.append(k.text);
                    gLast = k;
                    trail.reset();
                    trail.moveTo(x, y);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active == null) return true;
                int pi = e.findPointerIndex(activePointer);
                if (pi < 0) return true;
                float x = e.getX(pi);
                fingerX = x;
                if (glideCand && altList == null) {
                    float y = e.getY(pi);
                    if (!gliding) {
                        float d = (float) Math.hypot(x - downX, y - gDownY);
                        if (d > Math.max(active.rect.width() * 0.75f, 22 * dp)) {
                            gliding = true;
                            handler.removeCallbacks(longPress);
                        }
                    }
                    if (gliding) {
                        trail.lineTo(x, y);
                        Key k = find(x, y);
                        if (k != null && k != gLast && isLetterKey(k)) {
                            gSeq.append(k.text);
                            gLast = k;
                        }
                        invalidate();
                        return true;
                    }
                }
                if (dragKey != null) {
                    dragX = x;
                    dragY = e.getY(pi);
                    if (Math.hypot(dragX - downX, dragY - downY) > 12 * dp) dragMoved = true;
                    invalidate();
                    return true;
                }
                if (altList != null) {
                    float cw = altRect.width() / altList.length;
                    int sel = (int) ((x - altRect.left) / cw);
                    sel = Math.max(0, Math.min(altList.length - 1, sel));
                    if (sel != altSel) { altSel = sel; buzz(); invalidate(); }
                } else if (active.type == Key.DEL) {
                    float step = 34 * dp;
                    if (!swiped && Math.abs(x - downX) > step) {
                        swiped = true;
                        handler.removeCallbacks(repeat);
                        lastX = downX;
                    }
                    if (swiped) {
                        while (Math.abs(x - lastX) > step) {
                            listener.onEdit(E_DELWORD);
                            lastX += x < lastX ? -step : step;
                            buzz();
                        }
                    }
                } else if (active.type == Key.SPACE) {
                    if (swipeCursor) {
                        float step = 11 * dp;
                        if (!swiped && Math.abs(x - downX) > 14 * dp) {
                            swiped = true;
                            handler.removeCallbacks(longPress);
                            lastX = x;
                        }
                        if (swiped) {
                            while (x - lastX > step) { listener.onCursor(1); lastX += step; buzz(); }
                            while (lastX - x > step) { listener.onCursor(-1); lastX -= step; buzz(); }
                        }
                    } else if (!swiped && Math.abs(x - downX) > 40 * dp) {
                        swiped = true;
                        handler.removeCallbacks(longPress);
                        if (panel == PANEL_KEYS) listener.onLanguageToggle();
                        buzz();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (gliding && active != null && e.getPointerId(idx) == activePointer) {
                    String seq = gSeq.toString();
                    gliding = glideCand = false;
                    swiped = true;
                    release(false);
                    trail.reset();
                    listener.onGlide(seq);
                    invalidate();
                    return true;
                }
                glideCand = false;
                if (active != null && e.getPointerId(idx) == activePointer) release(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                gliding = glideCand = false;
                trail.reset();
                if (active != null) release(false);
                return true;
        }
        return true;
    }

    private static boolean isLetterKey(Key k) {
        return k.type == Key.CHAR && k.id != 1 && k.text != null && k.text.length() == 1
                && Character.isLetter(k.text.charAt(0));
    }

    private void onLongPress() {
        if (active == null) return;
        longPressed = true;
        if (active.type == Key.CLIP) {
            if (active.clip != null && clipTab == ClipRepo.TAB_PINNED && clipQuery == null && active.clip.pinned) {
                // pinned tab: hold and drag to reorder; release without moving opens the menu
                dragKey = active;
                dragMoved = false;
                dragX = downX; dragY = downY;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
                return;
            }
            if (active.clip != null) openClipMenu(active.clip);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        if (active.type == Key.SUGG) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (active.label != null && active.label.startsWith(CLIP_MARK)) listener.onClipChipMenu();
            else listener.onSuggestionLong(active.text);   // W56: the family tree — the service no-ops when unmapped/detached
            return;
        }
        if (active.type == Key.SPACE) {
            listener.onPicker();
            buzz();
            return;
        }
        if (active.alts != null) {
            altList = active.alts;
            altSel = 0;
            altAt = SystemClock.uptimeMillis();
            altPill = -1f;
            float cw = Math.max(active.rect.width(), 38 * dp);
            float bw = cw * altList.length;
            float left = clamp(active.rect.centerX() - cw / 2, fullL, fullR - bw);
            if (bw > fullR - fullL) { bw = fullR - fullL; left = fullL; }
            float h = active.rect.height() * 1.05f;
            float top = Math.max(2 * dp, active.rect.top - h - 6 * dp);
            altRect.set(left, top, left + bw, top + h);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    }

    private void release(boolean commit) {
        Key k = active;
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(repeat);
        active = null;
        activePointer = -1;
        if (k == null) return;
        if (dragKey != null) {
            Key d = dragKey;
            dragKey = null;
            k.target = 0f;
            if (commit) {
                if (!dragMoved) openClipMenu(d.clip);
                else {
                    int slot = -1;
                    for (int i = 0; i < clipKeys.size(); i++) if (clipKeys.get(i).rect.contains(dragX, dragY)) slot = i;
                    if (slot >= 0 && clipKeys.get(slot) != d) {
                        listener.onClipMovePin(d.clip.id, clipPage * CLIP_PER_PAGE + slot);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                }
            }
            invalidate();
            return;
        }
        k.target = 0f;
        if (commit && !swiped) {
            if (altList != null) {
                listener.onText(altList[altSel]);
            } else if (!longPressed || k.type == Key.CHAR) {
                switch (k.type) {
                    case Key.CHAR:
                        if (k.text.isEmpty()) break;
                        if (k.id == 1) listener.onEmoji(k.text); else listener.onText(k.text);
                        break;
                    case Key.CLIP:
                        if (k.clip == null) break;
                        if (inMenuZone(k, downX)) openClipMenu(k.clip);
                        else listener.onClipInsert(k.clip.id);
                        break;
                    case Key.PHRASE: if (!k.text.isEmpty()) listener.onText(k.text); break;
                    case Key.SUGG: listener.onSuggestion(k.text); break;
                    case Key.SPACE: listener.onText(" "); break;
                    case Key.ENTER: listener.onEnter(); break;
                    case Key.TO_SYM:
                    case Key.TO_SYM2: listener.onModeChange(k.type); break;
                    case Key.TO_ALPHA:
                        if (panel != PANEL_KEYS) showPanel(PANEL_KEYS);
                        else listener.onModeChange(k.type);
                        break;
                    case Key.TO_EMOJI: showPanel(PANEL_EMOJI); break;
                    case Key.EMOJI_TAB:
                        if (k.id == 99) { showPanel(PANEL_KEYS); listener.onEmojiSearch(); break; }
                        emojiTab = k.id; emojiPage = 0; buildEmojiRows(); layoutAll(); break;
                    case Key.CLIP_CLEAR: listener.onClearClips(); break;
                    case Key.CLIP_TAB:
                        if (k.id == 50) { showPanel(PANEL_KEYS); listener.onClipSearch(); }
                        else if (k.id == 51) listener.onClipClearSearch();
                        else if (k.id == 60) { clipPage = 0; listener.onClipCategory(0); }
                        else if (k.id >= 200) {
                            int ci = k.id - 200;
                            if (ci < clipCats.size()) { clipPage = 0; listener.onClipCategory(clipCats.get(ci).id); }
                        }
                        else { clipPage = 0; listener.onClipTab(k.id); }
                        break;
                    case Key.CLIP_ACT: onClipAct(k.id); break;
                    case Key.TOOL: onTool(k.id); break;
                    case Key.MORE_ITEM: onMore(k.id); break;
                    case Key.EDIT:
                        if (k.id != E_LEFT && k.id != E_RIGHT && k.id != E_UP && k.id != E_DOWN) listener.onEdit(k.id);
                        break;
                    case Key.PAGE_PREV:
                    case Key.PAGE_NEXT: {
                        if (k.id == 78) {
                            int dpn = (Decor.TOTAL + 11) / 12;
                            decorPage = (decorPage + (k.type == Key.PAGE_NEXT ? 1 : dpn - 1)) % dpn;
                            buildDecorRows(); layoutAll();
                            break;
                        }
                        if (k.id == 79) {
                            int sz = emojiTab == 0 ? recents.size() : drawable(emojiTab - 1).size();
                            int epn = Math.max(1, (sz + 33) / 34);
                            emojiPage = (emojiPage + (k.type == Key.PAGE_NEXT ? 1 : epn - 1)) % epn;
                            buildEmojiRows(); layoutAll();
                            break;
                        }
                        if (k.id == 77) {
                            boolean catGrid = clipTab == ClipRepo.TAB_CATS && clipCat == 0 && clipQuery == null;
                            int cp = Math.max(1, ((catGrid ? clipCats.size() : clips.size()) + CLIP_PER_PAGE - 1) / CLIP_PER_PAGE);
                            if (!catGrid && clipHasMore && k.type == Key.PAGE_NEXT && clipPage >= cp - 2) {
                                if (clipPage == cp - 1) { clipPendingNext = true; listener.onClipMore(); break; }
                                listener.onClipMore();   // prefetch the next batch one page early
                            }
                            clipPage = (clipPage + (k.type == Key.PAGE_NEXT ? 1 : cp - 1)) % cp;
                            layoutAll();
                            break;
                        }
                        int pages = Math.max(1, (phrases.size() + 7) / 8);
                        phrasePage = (phrasePage + (k.type == Key.PAGE_NEXT ? 1 : pages - 1)) % pages;
                        buildPhraseRows();
                        layoutAll();
                        break;
                    }
                    case Key.DECOR: listener.onDecor(k.id); showPanel(PANEL_KEYS); break;
                    case Key.OH_EXPAND: listener.onOneHand(0); break;
                    case Key.OH_SWITCH: listener.onOneHand(oneHand == 1 ? 2 : 1); break;
                    default: break;
                }
            }
        }
        altList = null;
        swiped = false;
        invalidate();
    }

    private void onTool(int id) {
        if (id >= 1000) { runFeature(id - 1000); return; }
        switch (id) {
            case Key.T_CLIP: showPanel(panel == PANEL_CLIP ? PANEL_KEYS : PANEL_CLIP); break;
            case Key.T_EMOJI: showPanel(panel == PANEL_EMOJI ? PANEL_KEYS : PANEL_EMOJI); break;
            case Key.T_LANG:
                if (panel != PANEL_KEYS) showPanel(PANEL_KEYS);
                listener.onLanguageToggle();
                break;
            case Key.T_MIC: listener.onVoice(); break;
            case Key.T_SETTINGS: listener.onSettings(); break;
            case Key.T_AVATAR: listener.onSettings(); break;
            case Key.T_ARROW_R: toggleArrow(1); return;
            case Key.T_ARROW_L: toggleArrow(2); return;
            default:
                boolean inMore = panel == PANEL_MORE || panel == PANEL_EDIT || panel == PANEL_PHRASES || panel == PANEL_DECOR;
                showPanel(inMore ? PANEL_KEYS : PANEL_MORE);
                break;
        }
    }

    private void onMore(int id) {
        switch (id) {
            case 0: showPanel(PANEL_EDIT); break;
            case 1: phrasePage = 0; showPanel(PANEL_PHRASES); break;
            case 2: showPanel(PANEL_DECOR); break;
            case 3: showPanel(PANEL_KEYS); listener.onOneHand(oneHand == 0 ? 1 : 0); break;
            case 4: showPanel(PANEL_KEYS); listener.onResize(); break;
            case 5: clipPage = 0; showPanel(PANEL_CLIP); break;
            case 6: showPanel(PANEL_KEYS); listener.onSettings(); break;
            case 7: showPanel(PANEL_KEYS); listener.onPicker(); break;
            case 8: showPanel(PANEL_KEYS); listener.onHide(); break;
            default: showPanel(PANEL_KEYS); listener.onTool(id - 9); break;
        }
    }

    private Key find(float x, float y) {
        if (y < toolH) {
            for (Key k : toolbar) if (k.id == Key.T_AVATAR && x >= k.rect.left && x < k.rect.right) return k;
            if (arrowsOn) {
                if (x >= arrowKeyR.rect.left && x < arrowKeyR.rect.right) return arrowKeyR;
                if (x >= arrowKeyL.rect.left && x < arrowKeyL.rect.right) return arrowKeyL;
            }
            if (expanded != 0) {
                for (Key k : featKeys) if (x >= k.rect.left && x < k.rect.right) return k;
                return null;
            }
            if (showSugg()) {
                for (Key k : suggKeys) if (x >= k.rect.left && x < k.rect.right) return k;
                return null;
            }
            for (Key k : toolbar) if (x >= k.rect.left && x < k.rect.right) return k;
            return null;
        }
        for (Key k : sideKeys) {
            if (k.rect.contains(x, y)) return k;
        }
        if (oneHand != 0 && (x < contentL - gap || x > contentR + gap)) return null;
        if (inStrip() && y >= stripKey.rect.top - gap / 2 && y < stripKey.rect.bottom + gap / 2) {
            for (Key k : suggKeys) if (x >= k.rect.left && x < k.rect.right) return k;
            return null;
        }
        List<Key> all = new ArrayList<>();
        List<List<Key>> rows = panelRows();
        if (rows != null) for (List<Key> r : rows) all.addAll(r);
        else { all.addAll(clipKeys); all.addAll(clipBottom); }
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key k : all) {
            if (y >= k.rect.top - gap && y <= k.rect.bottom + gap) {
                float d = x < k.rect.left ? k.rect.left - x : (x > k.rect.right ? x - k.rect.right : 0);
                if ((k.type == Key.CLIP || k.type == Key.PHRASE) && d > 0) continue;
                if (d < bestD) { bestD = d; best = k; }
            }
        }
        if (best == null || best.type == Key.LABEL) return null;
        if ((best.type == Key.CHAR || best.type == Key.PHRASE) && best.text.isEmpty()) return null;
        return best;
    }

    private void click(Key k) {
        if (!sound || audio == null) return;
        int fx = k.type == Key.DEL ? AudioManager.FX_KEYPRESS_DELETE
                : k.type == Key.SPACE ? AudioManager.FX_KEYPRESS_SPACEBAR
                : k.type == Key.ENTER ? AudioManager.FX_KEYPRESS_RETURN : AudioManager.FX_KEYPRESS_STANDARD;
        if (soundPack > 0 && sounds != null) {
            int kind = k.type == Key.DEL ? 1 : k.type == Key.SPACE ? 2 : k.type == Key.ENTER ? 3 : 0;
            if (sounds.play(kind, soundVol)) return;
        }
        audio.playSoundEffect(fx, soundVol);
    }

    private int vibLevel = 0, lpDelay = 350;
    private android.os.Vibrator vibrator;

    public void setFeel(int vib, int lp) {
        vibLevel = vib;
        lpDelay = lp == 0 ? 250 : lp == 2 ? 520 : 350;
    }

    private void buzz() {
        if (!haptics) return;
        if (vibLevel > 0) {
            try {
                if (vibrator == null) vibrator = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator() && Build.VERSION.SDK_INT < 26) {
                    vibrator.vibrate(vibLevel == 3 ? Math.max(3, vibMs) : vibLevel == 1 ? 14 : 24);
                    return;
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (vibLevel == 3) vibrator.vibrate(android.os.VibrationEffect.createOneShot(Math.max(3, vibMs), Math.max(1, Math.min(255, vibAmp))));
                    else vibrator.vibrate(android.os.VibrationEffect.createOneShot(vibLevel == 1 ? 14 : 24,
                            vibLevel == 1 ? 140 : 255));
                    return;
                }
            } catch (Exception ignored) { }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    }

    // ------------------------------------------------------------------ accessibility (screen readers)

    private AccessibilityManager am;
    private AccessibilityNodeProvider provider;
    private List<Key> a11yKeys = new ArrayList<>();
    private int hoverId = -1;

    private boolean exploring() {
        if (am == null) am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    private void a11yChanged() {
        if (exploring()) {
            a11yKeys = visibleKeys();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }

    private List<Key> visibleKeys() {
        List<Key> l = new ArrayList<>();
        if (voiceState != 0) return l;
        for (Key k : toolbar) if (k.id == Key.T_AVATAR) l.add(k);
        if (arrowsOn) { l.add(arrowKeyR); }
        if (expanded != 0) l.addAll(featKeys);
        else if (showSugg()) { for (Key k : suggKeys) if (k.label != null && !k.label.isEmpty()) l.add(k); }
        else for (Key k : toolbar) if (k.id != Key.T_AVATAR) l.add(k);
        if (arrowsOn) l.add(arrowKeyL);
        List<List<Key>> rows = panelRows();
        if (rows != null) { for (List<Key> r : rows) for (Key k : r) if (k.type != Key.LABEL && !((k.type == Key.CHAR || k.type == Key.PHRASE) && k.label.isEmpty())) l.add(k); }
        if (inStrip()) for (Key k : suggKeys) if (k.label != null && !k.label.isEmpty()) l.add(k);
        else if (panel == PANEL_CLIP) { l.addAll(clipKeys); l.addAll(clipBottom); }
        l.addAll(sideKeys);
        return l;
    }

    /** Opens the action sheet for one item (also used from the clip chip in the strip). */
    public void openClipMenu(ClipItem it) {
        if (it == null) return;
        if (panel != PANEL_CLIP) showPanel(PANEL_CLIP);
        clipMenu = it;
        clipDelArm = false;
        layoutAll();
        invalidate();
        a11yChanged();
    }

    /** The "⋮" corner of a card: the end side, top half. */
    private boolean inMenuZone(Key k, float x) {
        float z = Math.min(40 * dp, k.rect.width() * 0.3f);
        return rtl ? x < k.rect.left + z : x > k.rect.right - z;
    }

    private void onClipAct(int id) {
        ClipItem m = clipMenu;
        if (m == null) return;
        if (id == CA_BACK) { clipMenu = null; layoutAll(); a11yChanged(); return; }
        if (id == CA_DELETE && !clipDelArm) { clipDelArm = true; layoutAll(); return; }
        clipMenu = null;
        clipDelArm = false;
        listener.onClipAction(m.id, id);
        if (id == CA_INSERT) { showPanel(PANEL_KEYS); return; }
        layoutAll();
        a11yChanged();
    }

    /** Spoken name of a key. */
    private String describe(Key k) { return L.t(describe0(k)); }

    private String describe0(Key k) {
        switch (k.type) {
            case Key.DEL: return "حذف";
            case Key.SPACE: return "مسافة";
            case Key.ENTER: return "إدخال";
            case Key.SHIFT: return "تكبير الحروف" + (shiftState == SHIFT_LOCK ? "، مقفل" : shiftState == SHIFT_ON ? "، مفعّل" : "");
            case Key.TO_SYM: case Key.TO_SYM2: return "الرموز";
            case Key.TO_ALPHA: return panel != PANEL_KEYS ? "رجوع للكيبورد" : "الحروف";
            case Key.LANG: return "تبديل اللغة";
            case Key.TO_EMOJI: return "الإيموجي";
            case Key.EMOJI_TAB: return k.id == 99 ? "بحث الإيموجي" : "قسم إيموجي " + (k.id + 1) + (k.id == emojiTab ? "، محدد" : "");
            case Key.TOOL: {
                if (k.id >= 1000) return Features.label(k.id - 1000);
                if (k.id == Key.T_ARROW_R) return expanded == 1 ? "إغلاق مميزات السهم الأيمن" : "مميزات السهم الأيمن";
                if (k.id == Key.T_ARROW_L) return expanded == 2 ? "إغلاق مميزات السهم الأيسر" : "مميزات السهم الأيسر";
                String[] n = {"الحافظة", "الإيموجي", "تبديل اللغة", "الكتابة بالصوت", "الإعدادات", "المزيد", "صورتك الشخصية"};
                return k.id >= 0 && k.id < n.length ? n[k.id] : "أداة";
            }
            case Key.CLIP_CLEAR: return "مسح الحافظة";
            case Key.SUGG: {
                String t = k.label == null ? "" : k.label.replace(CLIP_MARK, "").replace(CLIP_SC_MARK, "");
                if (k.label != null && k.label.startsWith(CLIP_SC_MARK)) return "توسيع اختصار: " + t;
                return (k.label != null && k.label.startsWith(CLIP_MARK) ? "لصق: " : "اقتراح: ") + t;
            }
            case Key.CLIP: return "نص منسوخ: " + k.label + (k.clip != null && k.clip.pinned ? "، مثبّت" : "") + (k.clip != null && k.clip.fav ? "، مفضّل" : "");
            case Key.CLIP_TAB: return k.id == 50 ? "بحث في الحافظة" : k.id == 51 ? "مسح البحث" : k.label + (k.id == clipTab ? "، محدد" : "");
            case Key.CLIP_ACT: return k.label;
            case Key.PAGE_PREV: return "الصفحة السابقة";
            case Key.PAGE_NEXT: return "الصفحة التالية";
            case Key.OH_EXPAND: return "إلغاء وضع اليد الواحدة";
            case Key.OH_SWITCH: return "نقل الكيبورد للجهة الأخرى";
            case Key.EDIT: {
                switch (k.id) {
                    case E_LEFT: return "المؤشر يسار";
                    case E_RIGHT: return "المؤشر يمين";
                    case E_UP: return "المؤشر أعلى";
                    case E_DOWN: return "المؤشر أسفل";
                    case E_SELECT: return "وضع التحديد" + (selecting ? "، مفعّل" : "");
                    default: return k.label;
                }
            }
            case Key.DECOR: return "زخرفة: " + k.label + (k.id == decor && decor != Decor.OFF ? "، محددة" : "");
            case Key.CHAR: {
                if (k.id == 1) return k.label;
                String l = labelFor(k);
                return l.trim().isEmpty() ? "حرف" : l;
            }
            default: return k.label == null || k.label.isEmpty() ? "زر" : k.label;
        }
    }

    private void sendA11y(int id, int type) {
        if (id < 0 || id >= a11yKeys.size() || getParent() == null) return;
        AccessibilityEvent ev = AccessibilityEvent.obtain(type);
        ev.setPackageName(getContext().getPackageName());
        ev.setClassName("android.inputmethodservice.Keyboard$Key");
        ev.setContentDescription(describe(a11yKeys.get(id)));
        ev.setEnabled(true);
        ev.setSource(this, id);
        getParent().requestSendAccessibilityEvent(this, ev);
    }

    /** Activates a key the way a tap would (used by double-tap and lift-to-type). */
    private void accessClick(Key k) {
        buzz();
        click(k);
        if (k.type == Key.DEL) { listener.onDelete(); return; }
        if (k.type == Key.SHIFT) { listener.onShift(); return; }
        if (k.type == Key.EDIT && (k.id == E_LEFT || k.id == E_RIGHT || k.id == E_UP || k.id == E_DOWN)) { listener.onEdit(k.id); return; }
        active = k;
        activePointer = -1;
        swiped = false;
        longPressed = false;
        altList = null;
        release(true);
        a11yChanged();
    }

    @Override
    public boolean onHoverEvent(MotionEvent e) {
        if (!exploring()) return super.onHoverEvent(e);
        a11yKeys = visibleKeys();
        Key k = find(e.getX(), e.getY());
        int id = k == null ? -1 : a11yKeys.indexOf(k);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (id != hoverId) {
                    if (hoverId >= 0) sendA11y(hoverId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    hoverId = id;
                    if (id >= 0) {
                        sendA11y(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
                        sendA11y(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    }
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT: {
                boolean inside = e.getX() >= 0 && e.getX() < getWidth() && e.getY() >= 0 && e.getY() < getHeight();
                int last = hoverId;
                if (last >= 0) sendA11y(last, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                hoverId = -1;
                if (inside && last >= 0 && last == id) accessClick(a11yKeys.get(last)); // lift to type
                break;
            }
        }
        return true;
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (!exploring()) return super.getAccessibilityNodeProvider();
        if (provider == null) provider = new AccessibilityNodeProvider() {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                if (id == AccessibilityNodeProvider.HOST_VIEW_ID) {
                    AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(KeyboardView.this);
                    onInitializeAccessibilityNodeInfo(n);
                    n.setContentDescription("لوحة مفاتيح NeoKey");
                    a11yKeys = visibleKeys();
                    for (int i = 0; i < a11yKeys.size(); i++) n.addChild(KeyboardView.this, i);
                    return n;
                }
                if (id < 0 || id >= a11yKeys.size()) return null;
                Key k = a11yKeys.get(id);
                AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(KeyboardView.this, id);
                n.setPackageName(getContext().getPackageName());
                n.setClassName("android.inputmethodservice.Keyboard$Key");
                n.setContentDescription(describe(k));
                n.setParent(KeyboardView.this);
                n.setSource(KeyboardView.this, id);
                Rect b = new Rect();
                k.rect.round(b);
                n.setBoundsInParent(b);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                Rect sb = new Rect(b);
                sb.offset(loc[0], loc[1]);
                n.setBoundsInScreen(sb);
                n.setEnabled(true);
                n.setVisibleToUser(true);
                n.setClickable(true);
                n.setFocusable(true);
                n.setAccessibilityFocused(id == hoverId);
                n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                n.addAction(id == hoverId ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
                return n;
            }

            @Override
            public boolean performAction(int id, int action, android.os.Bundle args) {
                if (id == AccessibilityNodeProvider.HOST_VIEW_ID) return performAccessibilityAction(action, args);
                if (id < 0 || id >= a11yKeys.size()) return false;
                switch (action) {
                    case AccessibilityNodeInfo.ACTION_CLICK:
                        accessClick(a11yKeys.get(id));
                        return true;
                    case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                        hoverId = id;
                        sendA11y(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                        return true;
                    case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                        if (hoverId == id) hoverId = -1;
                        sendA11y(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                        return true;
                }
                return false;
            }
        };
        return provider;
    }
}
