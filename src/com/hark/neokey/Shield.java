package com.hark.neokey;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Android bridge for {@link Integrity} (roadmap Phase 0 item C — security layer 6).
 * Gathers the few published inputs (signing certificates, debuggable flag, debugger
 * attachment, emulator hint), computes the verdict ONCE per process, stores it in
 * prefs, and stays SILENT: no toasts, no dialogs during typing. The only user-facing
 * surface is the shield row in Settings → الخصوصية, which shows state + the anonymous
 * inspection report (Integrity.reportJson) on tap.
 *
 * Call sites: SetupActivity.onCreate and NeoKeyService.onCreate (same process, so the
 * static cache makes every call after the first a cheap field read).
 */
public final class Shield {

    private Shield() { }

    private static volatile boolean computed = false;
    private static volatile Integrity.State state = Integrity.State.UNKNOWN;

    /** Package name of a Play Store install — recorded only (beta APKs install from elsewhere). */
    private static final String PLAY_INSTALLER = "com.android.vending";

    /** Compute once per process; every later call is a plain field read. */
    public static Integrity.State ensure(Context c) {
        if (computed) return state;
        synchronized (Shield.class) {
            if (computed) return state;
            try {
                state = compute(c); // compute() records the full anonymous details itself
            } catch (Throwable t) {
                // Fail-open by design: an OEM quirk or a PackageManager hiccup must never
                // degrade a legitimate user. Recorded as UNKNOWN, never as an accusation.
                state = Integrity.State.UNKNOWN;
                record(c, state, 0, false, false, "unknown", false);
            }
            computed = true;
            return state;
        }
    }

    private static Integrity.State compute(Context c) throws Exception {
        boolean debuggable = (c.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        int flags = (Build.VERSION.SDK_INT >= 28)
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES; // pre-28: the only API (deprecated, fine for 21..27)
        PackageInfo pi;
        try {
            pi = c.getPackageManager().getPackageInfo(c.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            return Integrity.verdict(new String[0], debuggable);
        }

        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            sigs = pi.signingInfo.getApkContentsSigners();
        } else if (pi.signatures != null) {
            sigs = pi.signatures;
        }
        String[] fps = new String[sigs == null ? 0 : sigs.length];
        for (int i = 0; i < fps.length; i++) fps[i] = Integrity.sha256Hex(sigs[i].toByteArray());

        boolean debugger = Debug.isDebuggerConnected();
        boolean emu = Integrity.emulatorHint(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE);

        String installer = "";
        if (Build.VERSION.SDK_INT >= 30) {
            try { installer = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName(); }
            catch (Exception ignored) { }
        } else {
            try { installer = c.getPackageManager().getInstallerPackageName(c.getPackageName()); }
            catch (Exception ignored) { }
        }

        Integrity.State s = Integrity.verdict(fps, debuggable);
        record(c, s, fps.length, debugger, emu,
                installer.isEmpty() ? "unknown" : (PLAY_INSTALLER.equals(installer) ? "play" : "external"),
                !Integrity.class.getName().equals("com.hark.neokey.Integrity"));
        return s;
    }

    private static void record(Context c, Integrity.State s, int sigCount, boolean debugger,
                               boolean emu, String installer, boolean obfuscated) {
        try {
            Theme.prefs(c).edit()
                    .putString("integrity_state", s.name())
                    .putLong("integrity_at", System.currentTimeMillis())
                    .putInt("integrity_sigs", sigCount)
                    .putBoolean("integrity_debugger", debugger)
                    .putBoolean("integrity_emu", emu)
                    .putString("integrity_installer", installer)
                    .putBoolean("integrity_obfuscated", obfuscated)
                    .apply();
        } catch (Throwable ignored) { }
    }

    /** Feature gate for revenue-sensitive features; uncomputed = allow (see Integrity.allowFeature). */
    public static boolean allowFeature() {
        return Integrity.allowFeature(computed ? state : Integrity.State.UNKNOWN);
    }

    /** Current verdict without forcing computation (UI reads this). */
    public static Integrity.State current() { return computed ? state : Integrity.State.UNKNOWN; }

    /** One-line Arabic status for the Settings privacy card. */
    public static String statusLine(Context c) {
        Integrity.State s = current();
        String label;
        int color; // unused here on purpose — colouring happens at the call site
        switch (s) {
            case OK:       label = "درع الحماية: سليم ✓"; break;
            case MISMATCH: label = "درع الحماية: توقيع غير رسمي — نسخة معاد توقيعها؟"; break;
            case SANDBOX:  label = "درع الحماية: بناء تطوير (فحص التوقيع متسامح)"; break;
            default:       label = "درع الحماية: لم يُفحص بعد"; break;
        }
        long at = Theme.prefs(c).getLong("integrity_at", 0L);
        if (at > 0) {
            String t = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(at));
            label += " · آخر فحص " + t;
        }
        return label;
    }

    /** Full anonymous inspection report for the Settings dialog — the published contract. */
    public static String details(Context c) {
        int sigCount = Theme.prefs(c).getInt("integrity_sigs", -1);
        boolean debugger = Theme.prefs(c).getBoolean("integrity_debugger", false);
        boolean emu = Theme.prefs(c).getBoolean("integrity_emu", false);
        boolean obf = Theme.prefs(c).getBoolean("integrity_obfuscated", false);
        String installer = Theme.prefs(c).getString("integrity_installer", "unknown");
        return "الفحوصات المحدودة والموثقة في الكود (بلا تجسس وبلا شبكة):\n"
                + "• بصمة توقيع الحزمة مقابل البصمة الرسمية\n"
                + "• هل الحزمة debuggable (بناء تطوير)\n"
                + "• هل يوجد مصحح أخطاء متصل الآن\n"
                + "• تلميح محاكي (يُسجَّل فقط ولا يُتخذ به إجراء)\n"
                + "• مصدر التثبيت: " + installer + "\n\n"
                + "التقرير المجهول: "
                + Integrity.reportJson(current(), sigCount, debugger, emu, obf);
    }
}
