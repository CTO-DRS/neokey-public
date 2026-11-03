package com.hark.neokey;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * متجر السمات داخل التطبيق — المرحلة 3، الأسبوع 1 (مكوّن #10).
 *
 * تبويبان: «المتجر» (كتالوج المؤسسين بأسعارهم) و«مكتبتي» (المملوك + التثبيت).
 * كل منطق القرار في StoreUi النقي؛ هذه الشاشة ربط فقط: الشراء يخصم من المحفظة
 * المحلية، والشحن يمر من عقد مزوّد الدفع القياسي (الأسبوع 24) بمزوّد المحاكاة
 * المحلي `SimProvider` — الاستبدال بمزوّد حقيقي (Play Billing) عند النشر =
 * تغيير هذا السطر فقط دون لمس أي دالة شراء أو دالة مكتبة أو دالة اقتصاد)،
 * والتثبيت لا يمر إلا من سلسلة الحرس كاملة (ملكية ← دبوس sha256 ← مراجعة
 * ThemePack)، والتطبيق على لوحة المفاتيح يمر من Theme.importPack الجاهز
 * (يضيف للمكتبة ويختار ويطبق إعدادات التخطيط) — لا مسار جانب للسمات.
 */
public class StoreActivity extends Screen {

    StoreUi.Catalog catalog;
    StoreUi.Wallet wallet;
    StoreUi.Store store;
    PaymentProvider sim;                        // مزوّد المحاكاة المحلي — نقطة الاستبدال الوحيدة
    int tab;                                    // 0 = المتجر، 1 = مكتبتي
    LinearLayout list;
    TextView balance;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        header("متجر السمات", "مبدعو المؤسسين — 70/30 للمنصة", "store");
        StoreUi.Catalog c;
        try {
            c = StoreUi.loadCatalog(readAsset("founders_catalog.json"));
        } catch (Exception e) {
            TextView err = text("تعذر تحميل الكتالوج — الدبوس أو الشكل مرفوض", 14, Typeface.BOLD, 0xFFFF8A80);
            root.addView(err, top(16));
            return;
        }
        catalog = c;
        wallet = new StoreUi.Wallet();
        sim = new PaymentProvider.SimProvider();
        wallet.topUp(sim, 2000, "welcome", System.currentTimeMillis());   // شحن ترحيبي للمحاكاة ($20)
        store = new StoreUi.Store(catalog, wallet);

        LinearLayout wc = card();
        balance = text("الرصيد: $20.00", 16, Typeface.BOLD, Color.WHITE);
        wc.addView(balance);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = text("الشحن محاكاة محلياً — الدفع الحقيقي خارج هذه البيئة", 11, Typeface.NORMAL, MUTED);
        tr.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        tr.addView(button("شحن $20", "plus", false, v -> {
            wallet.topUp(sim, 2000, "btn-" + System.currentTimeMillis(), System.currentTimeMillis());
            refresh();
        }));
        wc.addView(tr, top(8));
        root.addView(wc, top(10));

        root.addView(chips(new String[]{"المتجر", "مكتبتي"}, 0, i -> { tab = i; rebuild(); }), top(10));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        rebuild();
    }

    void refresh() {
        balance.setText("الرصيد: $" + money(wallet.balance()));
        rebuild();
    }

    static String money(long cents) {
        return (cents / 100) + "." + (cents % 100 < 10 ? "0" : "") + (cents % 100);
    }

    void rebuild() {
        list.removeAllViews();
        if (tab == 0) {
            for (StoreUi.Founder f : catalog.founders) {
                TextView fn = text(f.name, 14, Typeface.BOLD, 0xFFDDE1EA);
                fn.setPadding((int) (4 * dp), (int) (16 * dp), (int) (4 * dp), (int) (6 * dp));
                list.addView(fn);
                for (StoreUi.Pack p : f.packs) packRow(p);
            }
        } else {
            boolean any = false;
            for (StoreUi.Founder f : catalog.founders) {
                for (StoreUi.Pack p : f.packs) {
                    if (!store.owned(p.id)) continue;
                    any = true;
                    ownedRow(p);
                }
            }
            if (!any) list.addView(text("لا شيء بعد — اشترِ من تبويب المتجر", 13, Typeface.NORMAL, MUTED));
        }
    }

    void packRow(StoreUi.Pack p) {
        LinearLayout r = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(packName(p), 14, Typeface.BOLD, Color.WHITE));
        col.addView(text("$" + money(p.priceCents) + " — " + f_name(p), 12, Typeface.NORMAL, MUTED));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (store.owned(p.id)) row.addView(text("مملوكة ✓", 13, Typeface.BOLD, 0xFF9AE6B4));
        else row.addView(button("شراء", "cart", true, v -> {
            StoreUi.Result r2 = store.purchase(p.id, System.currentTimeMillis());
            toast(r2.ok ? "اشتريت الحزمة" : "مرفوض: " + r2.tag);
            refresh();
        }));
        r.addView(row);
        list.addView(r, top(6));
    }

    String f_name(StoreUi.Pack p) {
        for (StoreUi.Founder f : catalog.founders) if (f.id.equals(p.vendorId)) return f.name;
        return p.vendorId;
    }

    void ownedRow(StoreUi.Pack p) {
        LinearLayout r = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(packName(p), 14, Typeface.BOLD, Color.WHITE));
        col.addView(text(store.installed(p.id) ? "مثبتة" : "غير مثبتة", 12, Typeface.NORMAL, MUTED));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!store.installed(p.id)) row.addView(button("تثبيت وتطبيق", "check", true, v -> {
            StoreUi.Result r2 = store.install(p.id, p.specJson.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
            if (!r2.ok) { toast("مرفوض: " + r2.tag); return; }
            try {
                Theme.importPack(this, new JSONObject(p.specJson));
                toast("طُبّقت السمة");
            } catch (Exception e) {
                toast("سمة غير صالحة");
            }
            refresh();
        }));
        else row.addView(button("إزالة", "close", false, v -> { store.uninstall(p.id); refresh(); }));
        r.addView(row);
        list.addView(r, top(6));
    }

    String packName(StoreUi.Pack p) {
        try {
            ThemePack.Json.Node n = ThemePack.Json.parse(p.specJson);
            ThemePack.Json.Node nm = n.obj().get("name");
            if (nm != null && nm.isStr()) return nm.str();
        } catch (Exception ignored) { }
        return p.id;
    }

    void toast(String s) { Toast.makeText(this, L.t(s), Toast.LENGTH_SHORT).show(); }

    String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
