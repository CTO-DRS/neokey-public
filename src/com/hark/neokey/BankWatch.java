package com.hark.neokey;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;

/**
 * W128: optional, off until the user allows it in Accessibility settings. It reads only which
 * app came to the front (window-state events, no window content) so the bank notice shows the
 * moment a banking app opens, before any field is tapped.
 */
public class BankWatch extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") || pkg.equals("android")) return;
        if (!Dialect.bankApp(pkg)) { BankNotice.left(); return; }
        if (BankNotice.recent(pkg) || !Theme.prefs(this).getBoolean("bank_auto", true)) return;
        if (Build.VERSION.SDK_INT < 22) return;
        BankNotice.show(this, pkg, null, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, "اختيار لوحة", () -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
    }

    @Override
    public void onInterrupt() { }

    /** True when the user turned BankWatch on in Accessibility settings. */
    static boolean on(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/") && s.contains("BankWatch");
    }
}
