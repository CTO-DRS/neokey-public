package com.hark.neokey;

import java.security.MessageDigest;

/**
 * W69 — الثيمات المولّدة GenThemes: «ثيمٌ يولد من بذرة، صفر بايت أصول».
 *
 * نقي JVM بلا أي استيراد أندرويد (بوابة W69 تترجمه بلا android.jar —
 * النقاء بالبناء لا بالادعاء): بذرة نصية → خلط FNV-1a-64 الموثق → اشتقاق
 * splitmix64 يولّد لوحة كاملة (سبعة ألوان + هندسة الخلفية) مع قانون
 * التباين مفروضاً عند الاشتقاق (نص/خلفية ≥ 4.5، نص المميز/المميز ≥ 3.0 —
 * يُدفع الإضاءة حتمياً حتى يمر، لا تخمين)، والخلفية تُصيَّر إلى مصفوفة
 * بكسلات حتمية بايتياً: جبر صحيح خالص + جدول جيب StrictMath محسوب مرة
 * واحدة (bit-exact بالنص عبر الأجهزة) — نفس البذرة = نفس البكسلات حرفاً،
 * والحزم تعيش كأرقام (سطر "gen" في مواصفة السمة) لا صور.
 *
 * الأوضاع مغلقة افتراضياً (قانون 8): الثيم المولّد لا يُطبَّق إلا ببذرة
 * صريحة يكتبها المستخدم؛ بذرة فارغة/null → البذرة الافتراضية الموثقة
 * fallback حتمي بلا انهيار، ولا حالة عالمية قابلة للتسرب.
 */
final class GenThemes {

    /** البذرة الافتراضية الموثقة (fallback الحتمي). */
    static final String DEFAULT_SEED = "neokey";
    /** أنماط الهندسة الأربعة: موجات، نجوم، حلقات، شبكة قطرية. */
    static final int STYLE_COUNT = 4;
    /** أوضاع الميزة — مغلقة افتراضياً (قانون 8): لا توليد إلا ببذرة صريحة. */
    static final int MODE_OFF = 0, MODE_ON = 1;
    /** حدود التباين ×10: النص/الخلفية 45 (≈4.5)، نص المميز/المميز 30 (≈3.0). */
    static final int TEXT_CONTRAST_X10 = 45, SPECIAL_CONTRAST_X10 = 30;

    private GenThemes() { }

    // ---- البذرة ---------------------------------------------------------------

    /** FNV-1a-64 الموثق: نفس العبارة = نفس البذرة على كل جهاز، الفراغات تُقصّ. */
    static long seedOf(String phrase) {
        String p = (phrase == null || phrase.trim().isEmpty()) ? DEFAULT_SEED : phrase.trim();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < p.length(); i++) {
            h ^= (p.charAt(i) & 0xFFFFL);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** splitmix64 — المشتق الوحيد: كل خطوة قيمة حتمية واحدة. */
    private static long sm(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private static int pick(long s, int n) { return (int) Long.remainderUnsigned(s, n); }

    // ---- اللوحة ---------------------------------------------------------------

    /** لوحة السمة المولَّدة: سبعة ألوان ARGB + هندسة الخلفية + تيار البذرة. */
    static final class Pal {
        public final int bg, bg2, key, keyPressed, text, special, specialText, starCol;
        public final int style, stars, ringN, lattice, waveAmp, wavePhase;
        public final int ringCx, ringCy;
        public final long stream;

        Pal(int bg, int bg2, int key, int keyPressed, int text, int special, int specialText,
            int starCol, int style, int stars, int ringN, int lattice, int waveAmp, int wavePhase,
            int ringCx, int ringCy, long stream) {
            this.bg = bg; this.bg2 = bg2; this.key = key; this.keyPressed = keyPressed;
            this.text = text; this.special = special; this.specialText = specialText;
            this.starCol = starCol; this.style = style; this.stars = stars; this.ringN = ringN;
            this.lattice = lattice; this.waveAmp = waveAmp; this.wavePhase = wavePhase;
            this.ringCx = ringCx; this.ringCy = ringCy; this.stream = stream;
        }
    }

    /** HSL→ARGB بالقطع الخطي القياسي (لا دوال متسامية — حتمي عبر الأجهزة). */
    static int hsl(int h, int s, int l) {
        double hh = (h % 360) / 60.0;
        double c = (1 - StrictMath.abs(2 * l / 100.0 - 1)) * (s / 100.0);
        double x = c * (1 - StrictMath.abs(hh % 2 - 1));
        double m = l / 100.0 - c / 2;
        double r, g, b;
        if (hh < 1)      { r = c; g = x; b = 0; }
        else if (hh < 2) { r = x; g = c; b = 0; }
        else if (hh < 3) { r = 0; g = c; b = x; }
        else if (hh < 4) { r = 0; g = x; b = c; }
        else if (hh < 5) { r = x; g = 0; b = c; }
        else             { r = c; g = 0; b = x; }
        int R = clamp255((int) StrictMath.round((r + m) * 255));
        int G = clamp255((int) StrictMath.round((g + m) * 255));
        int B = clamp255((int) StrictMath.round((b + m) * 255));
        return 0xFF000000 | (R << 16) | (G << 8) | B;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** إضاءة WCAG 2.x النسبية — StrictMath.pow حتمي bit-exact عبر الأجهزة. */
    static double lum(int c) {
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            double v = ((c >>> (16 - 8 * i)) & 0xFF) / 255.0;
            sum += (i == 0 ? 0.2126 : (i == 1 ? 0.7152 : 0.0722))
                    * (v <= 0.04045 ? v / 12.92 : StrictMath.pow((v + 0.055) / 1.055, 2.4));
        }
        return sum;
    }

    /** نسبة التباين ×1000 (WCAG) — القانون المقاس للبوابة. */
    static int contrastX1000(int a, int b) {
        double la = lum(a), lb = lum(b);
        double hi = StrictMath.max(la, lb), lo = StrictMath.min(la, lb);
        return (int) StrictMath.round(((hi + 0.05) / (lo + 0.05)) * 1000);
    }

    /**
     * قانون التباين مفروض: يدفع الإضاءة باتجاه القطبية حتمياً (≤12 خطوة
     * موثقة) حتى تجتاز النسبة — يمر فوراً إذا كان المرشح يكفي، لا تخمين.
     */
    private static int forceContrast(int hue, int sat, int startL, int against, int dark, int minx1000) {
        int l = startL;
        for (int k = 0; k < 12; k++) {
            int c = hsl(hue, sat, l);
            if (contrastX1000(c, against) >= minx1000) return c;
            l = dark == 1 ? Math.min(100, l + 2) : Math.max(0, l - 2);
        }
        return hsl(hue, sat, l);
    }

    /** اشتقاق اللوحة الكاملة من البذرة — قانون التباين مفروض داخلها. */
    static Pal make(long seed) {
        long s = seed;
        s = sm(s); int hue = pick(s, 360);
        s = sm(s); int dark = pick(s, 5) < 4 ? 1 : 0;
        s = sm(s); int bgL = dark == 1 ? 10 + pick(s, 7) : 90 + pick(s, 6);
        s = sm(s); int bgS = dark == 1 ? 18 + pick(s, 9) * 2 : 8 + pick(s, 5) * 2;
        s = sm(s); int shift = 24 + pick(s, 40);
        s = sm(s); int keyL = dark == 1 ? 22 + pick(s, 6) * 2 : 80 + pick(s, 5) * 2;
        s = sm(s); int accentOff = 150 + pick(s, 60);
        s = sm(s); int accentS = 62 + pick(s, 20);
        s = sm(s); int accentL = dark == 1 ? 55 + pick(s, 8) : 40 + pick(s, 8);
        int bg = hsl(hue, bgS, bgL);
        int bg2 = hsl((hue + shift) % 360, Math.min(92, bgS + 4),
                dark == 1 ? Math.min(30, bgL + 5) : Math.max(72, bgL - 5));
        int key = hsl((hue + shift / 2) % 360, dark == 1 ? 16 : 10, keyL);
        int keyPressed = hsl((hue + shift / 2) % 360, dark == 1 ? 20 : 12,
                dark == 1 ? Math.min(46, keyL + 10) : Math.max(64, keyL - 10));
        int text = forceContrast((hue + 8) % 360, 12, dark == 1 ? 92 : 10, bg, dark, TEXT_CONTRAST_X10 * 100);
        // نص المميز قطبي ملوّن بالبذرة، والمميز نفسه يُدفع حتمياً حتى يجز
        // التباين مع نصه (قانون 3.0 مفروض عند الاشتقاق — لا تخمين).
        int specialText = hsl((hue + accentOff) % 360, 8, dark == 1 ? 98 : 4);
        int special = hsl((hue + accentOff) % 360, accentS, accentL);
        int spL = accentL;
        for (int k = 0; k < 24; k++) {
            special = hsl((hue + accentOff) % 360, accentS, spL);
            if (contrastX1000(specialText, special) >= SPECIAL_CONTRAST_X10 * 100) break;
            spL = dark == 1 ? Math.max(20, spL - 2) : Math.min(78, spL + 2);
        }
        int starCol = hsl((hue + 40) % 360, dark == 1 ? 30 : 24, dark == 1 ? 86 : 16);
        s = sm(s); int style = pick(s, STYLE_COUNT);
        s = sm(s); int stars = 14 + pick(s, 26);
        s = sm(s); int ringN = 3 + pick(s, 5);
        s = sm(s); int lattice = 24 + pick(s, 25);
        s = sm(s); int waveAmp = 6 + pick(s, 14);
        s = sm(s); int wavePhase = pick(s, 360);
        s = sm(s); int ringCx = pick(s, 256);
        s = sm(s); int ringCy = pick(s, 256);
        return new Pal(bg, bg2, key, keyPressed, text, special, specialText, starCol,
                style, stars, ringN, lattice, waveAmp, wavePhase, ringCx, ringCy, s);
    }

    // ---- التصيير (جبر صحيح حتمي) ----------------------------------------------

    /** جدول جيب ثابت النقطة ×4096 على موجة كاملة 1024 — StrictMath مرة واحدة. */
    private static final int[] SIN = new int[1024];
    static {
        for (int i = 0; i < 1024; i++)
            SIN[i] = (int) StrictMath.round(StrictMath.sin(i * StrictMath.PI * 2 / 1024.0) * 4096);
    }

    private static int sinFx(int x1024) { return SIN[x1024 & 1023]; }

    /** مزج قناة-بقناة بمقطع صحيح 0..255. */
    private static int lerp(int a, int b, int t255) {
        int ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF, bg_ = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int r = ar + (((br - ar) * t255) / 255), g = ag + (((bg_ - ag) * t255) / 255),
                bl = ab + (((bb - ab) * t255) / 255);
        return 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(bl);
    }

    /**
     * تصيير الخلفية إلى مصفوفة بكسلات ARGB — حتمي بايتياً: نفس اللوحة
     * والحجم = نفس المصفوفة حرفاً على أي جهاز (جبر صحيح + جدول موثق).
     */
    static int[] render(Pal p, int w, int h) {
        if (w <= 0 || h <= 0) return new int[0];
        int[] buf = new int[w * h];
        int den = Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            int row = lerp(p.bg, p.bg2, (y * 255) / den);
            int off = y * w;
            for (int x = 0; x < w; x++) buf[off + x] = row;
        }
        if (p.style == 0) { // موجات: ثلاث شرائط جيبية بمزج صحيح
            for (int k = 0; k < 3; k++) {
                int col = k == 0 ? p.special : (k == 1 ? p.key : p.starCol);
                int amp = p.waveAmp * (k + 1) / 2 + 4;
                int base = h / 4 + (h / 4) * k;
                int freq = 3 + k * 2;
                int thick = 2 + k;
                for (int x = 0; x < w; x++) {
                    int yc = base + (amp * sinFx(x * freq * 1024 / Math.max(1, w) + p.wavePhase * 1024 / 360)) / 4096;
                    for (int dy = -thick; dy <= thick; dy++) {
                        int yy = yc + dy;
                        if (yy < 0 || yy >= h) continue;
                        int a = 150 - (Math.abs(dy) * 60);
                        buf[yy * w + x] = lerp(buf[yy * w + x], col, a);
                    }
                }
            }
        } else if (p.style == 1) { // نجوم: حقل نجمي من تيار البذرة
            for (int i = 0; i < p.stars; i++) {
                long q = sm(p.stream + i * 2 + 1);
                int px = pick(q, w), py = pick(sm(q), h);
                int r = 2 + pick(sm(q + 7), 3);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = px + dx, yy = py + dy;
                        if (xx < 0 || xx >= w || yy < 0 || yy >= h) continue;
                        int d2 = dx * dx + dy * dy;
                        if (d2 > r * r) continue;
                        int a = 230 - (d2 * 190) / (r * r);
                        buf[yy * w + xx] = lerp(buf[yy * w + xx], p.starCol, a);
                    }
                }
            }
        } else if (p.style == 2) { // حلقات: متمركزة حول مركز مشتق من البذرة
            int cx = (p.ringCx * w) / 256, cy = (p.ringCy * h) / 256;
            int period = Math.max(8, (Math.min(w, h) * 3) / (p.ringN * 2));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int dx = x - cx, dy = y - cy;
                    int d = (int) StrictMath.sqrt(dx * dx + dy * dy);
                    int band = (d / period) % p.ringN;
                    if (band == 0) buf[y * w + x] = lerp(buf[y * w + x], p.special, 70);
                    else if (band == 1) buf[y * w + x] = lerp(buf[y * w + x], p.key, 45);
                }
            }
        } else { // شبكة قطرية ماسية
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int m1 = ((x + y) % p.lattice), m2 = ((x - y + h + w) % p.lattice);
                    if (m1 < 3 || m2 < 3) buf[y * w + x] = lerp(buf[y * w + x], p.key, 95);
                }
            }
        }
        return buf;
    }

    /** ختم البكسلات SHA-256 (القانون: يتغير بأي بكسل، ويثبت المرجعية). */
    static String pixelHash(Pal p, int w, int h) {
        return pixelHash(render(p, w, h));
    }

    /** ختم مصفوفة بكسلات صريح — عقد كشف التحريف عبر الواجهة نفسها. */
    static String pixelHash(int[] buf) {
        try {
            byte[] bytes = new byte[buf.length * 4];
            for (int i = 0; i < buf.length; i++) {
                bytes[i * 4] = (byte) (buf[i] >>> 24);
                bytes[i * 4 + 1] = (byte) (buf[i] >>> 16);
                bytes[i * 4 + 2] = (byte) (buf[i] >>> 8);
                bytes[i * 4 + 3] = (byte) buf[i];
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append("0123456789abcdef".charAt((b >>> 4) & 15))
                               .append("0123456789abcdef".charAt(b & 15));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("GENTHEMES hash failed", e);
        }
    }

    /** عقد التكامل مع Theme: الألوان السبعة بترتيب الحقول المطبَّقة. */
    static int[] colors(Pal p) {
        return new int[]{p.bg, p.bg2, p.key, p.keyPressed, p.text, p.special, p.specialText};
    }
}
