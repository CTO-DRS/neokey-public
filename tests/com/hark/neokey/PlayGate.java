package com.hark.neokey;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Store-listing gate (roadmap item D, week 7) — the automation behind
 * play/listing.txt's promise: «الحدود مُتحقَّق آلياً في ci/play_gate.sh».
 *
 * Verifies, on the plain JVM, everything Play Console will actually enforce
 * BEFORE the first signed upload reaches it:
 *   • listing text limits: name ≤ 30, short ≤ 80, full ≤ 4000 chars (per locale,
 *     counted as code points like Play does) — plus the (n/m) annotations in the
 *     file must match reality, so a manual edit can never silently drift;
 *   • assets: exactly 8 phone screenshots 1080×1920, feature graphic 1024×500,
 *     icon 512×512 ≤ 1 MB, promo video ≈ 30 s (25–40 s), privacy.html present;
 *   • consistency: the listing's support email/site equal Feedback's constants,
 *     so the store and the in-app beta loop can never disagree.
 *
 * Output lines PLAYGATE key=value are machine-readable (gates.env); the verdict
 * is PLAY_GATE_OK (exit 0) or PLAY_GATE_FAILED <reason> (exit 1). Hard gate —
 * a listing typo can no longer ship.
 */
public final class PlayGate {

    static final int NAME_MAX = 30, SHORT_MAX = 80, FULL_MAX = 4000;
    static final int SCREEN_W = 1080, SCREEN_H = 1920, SCREENS_REQUIRED = 8;
    static final double VIDEO_MIN_S = 25.0, VIDEO_MAX_S = 40.0;

    static final class Gate extends RuntimeException {
        Gate(String m) { super(m); }
    }
    private static void need(boolean ok, String msg) {
        if (!ok) throw new Gate(msg);
    }

    public static void main(String[] args) {
        try {
            run(new File(args.length > 0 ? args[0] : "play"));
        } catch (Gate g) {
            System.out.println("PLAY_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("PLAY_GATE_FAILED internal: " + e);
            System.exit(1);
        }
        System.out.println("PLAY_GATE_OK");
    }

    // ---------------------------------------------------------------- listing

    /** One locale's three text fields, extracted from listing.txt. */
    static final class Fields {
        String name = "", shortDesc = "", full = "";
        String nameNote = "", shortNote = ""; // "(n/m)" annotations when present
    }

    static void run(File dir) throws IOException {
        File listing = new File(dir, "listing.txt");
        need(listing.isFile(), "play/listing.txt not found");
        String text = readFile(listing);

        Fields ar = section(text, "العربية");
        Fields en = section(text, "الإنجليزية");
        checkLocale("ar", ar);
        checkLocale("en", en);

        // -------------------------------------------------------- consistency
        String fixed = after(text, "حقول ثابتة");
        need(fixed.contains(Feedback.SUPPORT_EMAIL),
                "listing must show the in-app support address " + Feedback.SUPPORT_EMAIL);
        need(fixed.contains(Feedback.SITE),
                "listing must show the in-app site " + Feedback.SITE);

        // ------------------------------------------------------------ assets
        File[] pngs = new File[0];
        File screensDir = new File(dir, "screens");
        File[] listed = screensDir.listFiles();
        int n = 0;
        if (listed != null) {
            int k = 0;
            for (File f : listed) if (f.getName().endsWith(".png")) k++;
            pngs = new File[k];
            for (File f : listed) if (f.getName().endsWith(".png")) pngs[n++] = f;
        }
        need(n == SCREENS_REQUIRED, "need exactly " + SCREENS_REQUIRED + " phone screenshots, found " + n);
        java.util.Arrays.sort(pngs);
        for (File f : pngs) {
            String[] wh = pngSize(f);
            need(SCREEN_W == Integer.parseInt(wh[0]) && SCREEN_H == Integer.parseInt(wh[1]),
                    "screenshot " + f.getName() + " is " + wh[0] + "x" + wh[1]
                            + ", must be " + SCREEN_W + "x" + SCREEN_H);
        }

        File feat = new File(dir, "feature-1024x500.png");
        need(feat.isFile(), "play/feature-1024x500.png not found");
        String[] fw = pngSize(feat);
        need("1024".equals(fw[0]) && "500".equals(fw[1]),
                "feature graphic is " + fw[0] + "x" + fw[1] + ", must be 1024x500");

        File icon = new File(dir, "icon-512.png");
        need(icon.isFile(), "play/icon-512.png not found");
        String[] iw = pngSize(icon);
        need("512".equals(iw[0]) && "512".equals(iw[1]),
                "store icon is " + iw[0] + "x" + iw[1] + ", must be 512x512");
        need(icon.length() <= 1024L * 1024, "store icon must be ≤ 1 MB");

        File video = new File(dir, "promo/neokey-promo-30s.mp4");
        need(video.isFile(), "play/promo/neokey-promo-30s.mp4 not found");
        double s = mp4Seconds(video);
        need(s >= VIDEO_MIN_S && s <= VIDEO_MAX_S,
                "promo video is " + String.format(java.util.Locale.US, "%.1f", s)
                        + "s, must be " + (int) VIDEO_MIN_S + "-" + (int) VIDEO_MAX_S + "s");

        File privacy = new File(dir, "privacy.html");
        need(privacy.isFile(), "play/privacy.html not found (upload target: cto-drs.com/neokey/)");
    }

    /** Parses one locale block: everything between its header and the next header. */
    static Fields section(String text, String headerKeyword) {
        int h = headerLine(text, headerKeyword, 0);
        if (h < 0) throw new Gate("listing section header not found: " + headerKeyword);
        int body = text.indexOf('\n', h) + 1;
        int end = headerLine(text, null, body);
        String block = text.substring(body, end < 0 ? text.length() : end);

        Fields f = new Fields();
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (f.name.isEmpty() && isLabel(l, "اسم التطبيق", "App name")) {
                f.name = nextValue(lines, i); f.nameNote = note(l);
            } else if (f.shortDesc.isEmpty() && isLabel(l, "الوصف المختصر", "Short description")) {
                f.shortDesc = nextValue(lines, i); f.shortNote = note(l);
            } else if (f.full.isEmpty() && isLabel(l, "الوصف الكامل", "Full description")) {
                f.full = fullValue(lines, i);
            }
        }
        if (f.name.isEmpty() || f.shortDesc.isEmpty() || f.full.isEmpty())
            throw new Gate("incomplete fields in listing section: " + headerKeyword);
        return f;
    }

    /** Index of a header line (a rule of ≥8 '─') containing kw, or the next header when kw==null. */
    static int headerLine(String text, String kw, int from) {
        int i = from;
        while (i >= 0 && i < text.length()) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) nl = text.length();
            int start = text.lastIndexOf('\n', Math.min(i, nl - 1)) + 1;
            String line = text.substring(start, nl);
            if (isHeaderLine(line) && (kw == null || line.contains(kw))) return start;
            i = nl + 1;
        }
        return -1;
    }
    static boolean isHeaderLine(String line) {
        int dashes = 0;
        for (int i = 0; i < line.length() && dashes < 8; i++)
            if (line.charAt(i) == '─') dashes++;
        return dashes >= 8;
    }

    private static boolean isLabel(String line, String ar, String en) {
        return line.startsWith(ar) || line.startsWith(en);
    }

    /** First non-empty line after the label line = the field's value. */
    private static String nextValue(String[] lines, int labelIdx) {
        for (int i = labelIdx + 1; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        }
        return "";
    }

    /** Full description = all lines after the label until the next section header. */
    private static String fullValue(String[] lines, int labelIdx) {
        StringBuilder b = new StringBuilder();
        for (int i = labelIdx + 1; i < lines.length; i++) {
            if (isHeaderLine(lines[i])) break;
            if (b.length() > 0) b.append('\n');
            b.append(lines[i]);
        }
        return b.toString().trim();
    }

    /** Extracts "(n/m)" from a label line, "" when absent. */
    private static String note(String line) {
        int o = line.indexOf('('), c = line.indexOf(')', o);
        return (o >= 0 && c > o) ? line.substring(o + 1, c) : "";
    }

    static void checkLocale(String tag, Fields f) {
        int name = f.name.codePointCount(0, f.name.length());
        int sh = f.shortDesc.codePointCount(0, f.shortDesc.length());
        int full = f.full.codePointCount(0, f.full.length());
        need(name > 0 && name <= NAME_MAX, tag + " app name is " + name + " chars, must be 1-" + NAME_MAX);
        need(sh > 0 && sh <= SHORT_MAX, tag + " short description is " + sh + " chars, must be 1-" + SHORT_MAX);
        need(full > 0 && full <= FULL_MAX, tag + " full description is " + full + " chars, must be 1-" + FULL_MAX);
        System.out.println("PLAYGATE name_" + tag + "=" + name + "/" + NAME_MAX);
        System.out.println("PLAYGATE short_" + tag + "=" + sh + "/" + SHORT_MAX);
        System.out.println("PLAYGATE full_" + tag + "=" + full + "/" + FULL_MAX);
        // The file's own (n/m) annotations must be TRUE — a stale annotation is a
        // lie someone will copy into Play Console by hand.
        if (!f.nameNote.isEmpty()) need(f.nameNote.equals(name + "/" + NAME_MAX),
                tag + " name annotation (" + f.nameNote + ") is stale — reality is " + name + "/" + NAME_MAX);
        if (!f.shortNote.isEmpty()) need(f.shortNote.equals(sh + "/" + SHORT_MAX),
                tag + " short annotation (" + f.shortNote + ") is stale — reality is " + sh + "/" + SHORT_MAX);
    }

    // ----------------------------------------------------------------- assets

    /** PNG IHDR: signature(8) + len(4) + "IHDR" + width(4) + height(4). */
    static String[] pngSize(File f) throws IOException {
        byte[] b = new byte[24];
        try (FileInputStream in = new FileInputStream(f)) {
            int got = in.read(b);
            if (got != 24) throw new Gate("PNG too small: " + f.getName());
        }
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++)
            if (b[i] != sig[i]) throw new Gate("not a PNG: " + f.getName());
        return new String[]{String.valueOf(u32(b, 16)), String.valueOf(u32(b, 20))};
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16)
                | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    /** mp4 mvhd box: [size][type='mvhd'][ver][flags] · ver0: ts@+16 dur@+20 · ver1: ts@+24 dur@+28. */
    static double mp4Seconds(File f) throws IOException {
        byte[] d = readAll(f);
        for (int i = 0; i + 36 <= d.length; i++) {
            if (d[i] == 'm' && d[i + 1] == 'v' && d[i + 2] == 'h' && d[i + 3] == 'd') {
                int ver = d[i + 4] & 0xFF;
                long ts, dur;
                if (ver == 1) {
                    ts = u32(d, i + 24);
                    dur = ((d[i + 28] & 0xFFL) << 56) | ((d[i + 29] & 0xFFL) << 48)
                            | ((d[i + 30] & 0xFFL) << 40) | ((d[i + 31] & 0xFFL) << 32)
                            | ((d[i + 32] & 0xFFL) << 24) | ((d[i + 33] & 0xFFL) << 16)
                            | ((d[i + 34] & 0xFFL) << 8) | (d[i + 35] & 0xFFL);
                } else {
                    ts = u32(d, i + 16);
                    dur = u32(d, i + 20);
                }
                return ts == 0 ? 0 : dur / (double) ts;
            }
        }
        throw new Gate("no mvhd box in promo video (not an mp4?)");
    }

    // ------------------------------------------------------------------ util

    private static String readFile(File f) throws IOException {
        return new String(readAll(f), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] out = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int r = in.read(out, off, out.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return out;
    }

    private static String after(String text, String kw) {
        int i = text.indexOf(kw);
        return i < 0 ? "" : text.substring(i);
    }
}
