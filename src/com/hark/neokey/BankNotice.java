package com.hark.neokey;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

/** W128: the bank notice, shared by the keyboard (on a bank field) and BankWatch (on opening a bank app). */
final class BankNotice {
    private BankNotice() { }

    /** The trusted keyboards, each a direct store link: {label, package, web prefix or "" for Google Play}. */
    static final String[][] TRUSTED = {
            {"⬇ Gboard من Google", "com.google.android.inputmethod.latin", ""},
            {"⬇ SwiftKey من مايكروسوفت", "com.touchtype.swiftkey", ""},
            {"⬇ لوحة سامسونج (Galaxy Store)", "com.samsung.android.honeyboard", "https://galaxystore.samsung.com/detail/"}};

    private static String shownPkg;
    private static long shownAt;
    private static AlertDialog open;

    /** True when this bank was already warned about in the last minute (keyboard and watcher never double up). */
    static boolean recent(String pkg) {
        return pkg != null && pkg.equals(shownPkg) && System.currentTimeMillis() - shownAt < 60_000L;
    }

    /** The user left the bank: the next visit warns again. */
    static void left() { shownPkg = null; }

    static boolean show(final Context c, String pkg, IBinder token, int type, String go, final Runnable onGo) {
        AlertDialog dlg = new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t(c.getString(R.string.nk_bank_title)))
                .setMessage(body(c))
                .setPositiveButton(L.t(go), (d, w) -> { if (onGo != null) onGo.run(); })
                .setNegativeButton(L.t("إغلاق"), null)
                .create();
        Window win = dlg.getWindow();
        if (win != null) {
            WindowManager.LayoutParams lp = win.getAttributes();
            if (token != null) lp.token = token;
            lp.type = type;
            win.setAttributes(lp);
            win.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
        try {
            if (open != null) try { open.dismiss(); } catch (Exception ignored) { }
            dlg.show();
            open = dlg;
            shownPkg = pkg;
            shownAt = System.currentTimeMillis();
            TextView tv = (TextView) dlg.findViewById(android.R.id.message);
            if (tv != null) {   // right-to-left lines and tappable store links
                tv.setTextDirection(View.TEXT_DIRECTION_RTL);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                tv.setLineSpacing(0f, 1.15f);
                tv.setMovementMethod(LinkMovementMethod.getInstance());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static CharSequence body(final Context c) {
        SpannableStringBuilder b = new SpannableStringBuilder(L.t(c.getString(R.string.nk_bank_why)));
        for (final String[] t : TRUSTED) {
            b.append("\n");
            int at = b.length();
            b.append(t[0]);
            b.setSpan(new ClickableSpan() {
                @Override public void onClick(View v) { openStore(c, t[1], t[2]); }
            }, at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    static void openStore(Context c, String pkg, String web) {
        if (open != null) try { open.dismiss(); } catch (Exception ignored) { }
        String[] uris = web.isEmpty()
                ? new String[] {"market://details?id=" + pkg, "https://play.google.com/store/apps/details?id=" + pkg}
                : new String[] {"samsungapps://ProductDetail/" + pkg, web + pkg};
        for (String u : uris) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) { }
        }
    }
}
