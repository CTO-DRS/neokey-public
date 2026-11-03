package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * المتجر داخل التطبيق — المرحلة 3، الأسبوع 1.
 *
 * المنطق النقي (صفر android.*) لواجهة متجر السمات: كتالوج المؤسسين كأصل محمّل
 * ببنية صارمة، محفظة محلية سنت-دقيقة تقبل المال من عقد مزوّد الدفع القياسي
 * (`PaymentProvider`، الأسبوع 24) حصراً — لا قبض بلا إيصال مختوم مُتحقق منه
 * ولا مسار ثانٍ للمال، ومزوّد حقيقي يستبدل `SimProvider` دون لمس أي دالة
 * شراء). شراء idempotent (الشراء
 * الثاني = صفر بيع)، مكتبة ملكية مثبتة بدبوس sha256 للحزمة المراجَعة، تثبيت
 * لا يمر إلا من بوابة مراجعة ThemePack نفسها + مطابقة الدبوس (عبث ما بعد
 * الشراء يُصطاد)، وإغلاق شهري 70/30 يفوَّض حصراً إلى Economy — دفتر واحد لا
 * نسخة ثانية من الاقتصاد.
 *
 * الصرامة هنا أشد من المتجر الخارجي لا أخف: الكتالوج يرفض نفسه إذا لم يطابق
 * دبوسه، لأن الثقة تُبنى مرة على البايتات المراجَعة ولا تُعاد إلا بها.
 */
public final class StoreUi {

    public static final String SCHEMA = "neokey-founders-1";
    public static final long MAX_PRICE_CENTS = 100_00;
    public static final long MAX_TOPUP_CENTS = 100_00;      // شحنة واحدة (حد محاكاة)
    public static final long MAX_BALANCE_CENTS = 500_00;    // سقف المحفظة المحلية
    public static final int MAX_LIBRARY = 200;

    private StoreUi() { }

    /** قرار عمليات المتجر: ok + وسم رفض ثابت قصير عند الفشل. */
    public static final class Result {
        public final boolean ok;
        public final String tag;
        private Result(boolean ok, String tag) { this.ok = ok; this.tag = tag; }
        public static Result ok() { return new Result(true, "OK"); }
        public static Result no(String t) { return new Result(false, t); }
        @Override public String toString() { return ok ? "OK" : tag; }
    }

    /** حزمة كتالوج: مراجَعة وقت التوليد، دبوسها جزء من عقد التحميل. */
    public static final class Pack {
        public final String id, vendorId, specJson, sha256;
        public final long priceCents;
        public final int bytes;
        Pack(String id, String vendorId, long priceCents, String specJson, String sha256, int bytes) {
            this.id = id; this.vendorId = vendorId; this.priceCents = priceCents;
            this.specJson = specJson; this.sha256 = sha256; this.bytes = bytes;
        }
    }

    public static final class Founder {
        public final String id, name;
        public final List<Pack> packs = new ArrayList<>();
        Founder(String id, String name) { this.id = id; this.name = name; }
    }

    /** كتالوج المؤسسين — لا يُنشأ إلا من بايتات تطابق دبوسها (مراجَع = منشور). */
    public static final class Catalog {
        public final List<Founder> founders = new ArrayList<>();
        public final Map<String, Pack> byId = new LinkedHashMap<>();
        public final Set<String> vendorIds = new HashSet<>();
        public long totalPriceCents;
        public Pack pack(String id) { return byId.get(id); }
        public int packCount() { return byId.size(); }
    }

    /** فحص دبيس 64-hex — مرجع واحد (يستخدمه أيضاً مرشح شكل SyncState). */
    static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String str(Map<String, ThemePack.Json.Node> o, String k) {
        ThemePack.Json.Node n = o.get(k);
        return (n != null && n.isStr()) ? n.str() : null;
    }

    /**
     * تحميل الكتالوج ببنية صارمة — كل خرق شكل = IllegalArgumentException بوسم
     * ثابت. الدبوس يفحص هنا: كتالوج بايتاته لا تطابق sha256 المعلن يُرفض كله
     * (لا ثقة جزئية في كتالوج مسموم).
     */
    public static Catalog loadCatalog(String json) {
        ThemePack.Json.Node root = ThemePack.Json.parse(json);
        if (!root.isObj()) throw new IllegalArgumentException("CATALOG_SHAPE");
        Map<String, ThemePack.Json.Node> o = root.obj();
        ThemePack.Json.Node sc = o.get("schema");
        if (sc == null || !sc.isStr() || !SCHEMA.equals(sc.str())) throw new IllegalArgumentException("CATALOG_SCHEMA");
        ThemePack.Json.Node fs = o.get("founders");
        if (fs == null || !fs.isArr() || fs.arr().isEmpty()) throw new IllegalArgumentException("CATALOG_FOUNDERS");
        Catalog c = new Catalog();
        Set<String> founderIds = new HashSet<>();
        for (ThemePack.Json.Node fn : fs.arr()) {
            if (!fn.isObj()) throw new IllegalArgumentException("CATALOG_FOUNDER_SHAPE");
            Map<String, ThemePack.Json.Node> f = fn.obj();
            String id = str(f, "id"), name = str(f, "name");
            if (id == null || id.isEmpty() || !founderIds.add(id)) throw new IllegalArgumentException("CATALOG_FOUNDER_ID");
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("CATALOG_FOUNDER_NAME");
            ThemePack.Json.Node ps = f.get("packs");
            if (ps == null || !ps.isArr() || ps.arr().isEmpty()) throw new IllegalArgumentException("CATALOG_PACKS");
            Founder fo = new Founder(id, name);
            for (ThemePack.Json.Node pn : ps.arr()) {
                if (!pn.isObj()) throw new IllegalArgumentException("CATALOG_PACK_SHAPE");
                Map<String, ThemePack.Json.Node> p = pn.obj();
                String pid = str(p, "id"), spec = str(p, "spec_json"), sha = str(p, "sha256");
                ThemePack.Json.Node pr = p.get("price_cents"), by = p.get("bytes");
                if (pid == null || pid.isEmpty() || c.byId.containsKey(pid)) throw new IllegalArgumentException("CATALOG_PACK_ID");
                if (pr == null || !pr.isNum()) throw new IllegalArgumentException("CATALOG_PRICE");
                long price = (long) pr.num();
                if (price < 1 || price > MAX_PRICE_CENTS) throw new IllegalArgumentException("CATALOG_PRICE");
                if (spec == null || spec.isEmpty()) throw new IllegalArgumentException("CATALOG_SPEC");
                if (by == null || !by.isNum() || (long) by.num() != spec.getBytes(StandardCharsets.UTF_8).length)
                    throw new IllegalArgumentException("CATALOG_BYTES");
                if (!isHex64(sha)) throw new IllegalArgumentException("CATALOG_SHA");
                if (!ThemePack.sha256hex(spec.getBytes(StandardCharsets.UTF_8)).equals(sha))
                    throw new IllegalArgumentException("CATALOG_PIN");
                Pack pk = new Pack(pid, id, price, spec, sha, (int) (long) by.num());
                fo.packs.add(pk);
                c.byId.put(pid, pk);
                c.totalPriceCents += price;
            }
            c.founders.add(fo);
            c.vendorIds.add(id);
        }
        return c;
    }

    /**
     * محفظة محلية سنت-دقيقة — دفتر يومية نصي قابل للتدقيق.
     *
     * المال يدخل من عقد المزوّد حصراً (الأسبوع 24): سقوف الشحن والرصيد
     * محفظية تُفحص قبل مناداة المزوّد وتظل نافذة مهما كان مزوّداً، والإيصال
     * لا يُصدّق إلا بإعادة اشتقاق ختمه الحتمي، ومعرف الطلب لا يُقبض مرتين
     * (REPLAY) — أي إعادة تشغيل إيصال أو إعادة بثّ طلب تُرفض بلا قيد ثانٍ.
     */
    public static final class Wallet {
        private long balance;
        private final List<String> journal = new ArrayList<>();
        private final Set<String> usedRequests = new HashSet<>();

        public long balance() { return balance; }
        public List<String> journal() { return new ArrayList<>(journal); }
        public boolean requestUsed(String requestId) { return usedRequests.contains(requestId); }

        /** معرف طلب مقبول: 1..64 من [A-Za-z0-9._:-] — يرتكز في الجرنة والختم. */
        static boolean validRequestId(String s) {
            if (s == null || s.isEmpty() || s.length() > 64) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == ':';
                if (!ok) return false;
            }
            return true;
        }

        /**
         * المرجع الخارجي حر لكنه ليس عربة حقن: نص طباعي ASCII من 1..64 بلا
         * TAB/CR/LF — أي محاولة زرع أعمدة جرنة عبره = إيصال مرفوض كله.
         */
        static boolean validExternalRef(String s) {
            if (s == null) return true;              // الحقل اختياري
            if (s.isEmpty() || s.length() > 64) return false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c > 0x7E) return false;
            }
            return true;
        }

        /**
         * الشحن عبر المزوّد — نقطة الاستبدال القياسية (الأسبوع 24). الترتيب
         * مقصود: الفحوص الشكلية والمحفظية أولاً (المزوّد الشريد لا يصل إلى
         * تجاوز السقوف)، ثم REPLAY، ثم مناداة المزوّد، ثم تحقق الإيصال، وقبض
         * المعرف يُحرَّر عند أي فشل (الفشل لا يحرق المعرف — النجاح وحده يقفله).
         */
        public Result topUp(PaymentProvider p, long cents, String requestId, long ts) {
            if (p == null) return Result.no("BAD_PROVIDER");
            if (!validRequestId(requestId)) return Result.no("BAD_REQUEST_ID");
            if (cents <= 0) return Result.no("BAD_TOPUP");
            if (cents > MAX_TOPUP_CENTS) return Result.no("TOPUP_CAP");
            if (balance + cents > MAX_BALANCE_CENTS) return Result.no("BALANCE_CAP");
            if (!usedRequests.add(requestId)) return Result.no("REPLAY");
            PaymentProvider.Receipt r;
            try {
                r = p.charge(new PaymentProvider.ChargeRequest(requestId, cents, ts));
            } catch (PaymentProvider.Declined d) {
                usedRequests.remove(requestId);
                String t = d.tag;
                return Result.no(t == null || t.isEmpty() ? "PROVIDER_DECLINED" : t);
            } catch (RuntimeException e) {
                usedRequests.remove(requestId);
                return Result.no("PROVIDER_ERROR");
            }
            if (r == null) {
                usedRequests.remove(requestId);
                return Result.no("RECEIPT_MISSING");
            }
            if (!p.id().equals(r.providerId) || !requestId.equals(r.requestId) || cents != r.cents
                    || !validExternalRef(r.externalRef)
                    || !PaymentProvider.Receipt.seal(p.id(), requestId, cents).equals(r.receiptId)) {
                usedRequests.remove(requestId);
                return Result.no("RECEIPT_MISMATCH");
            }
            balance += cents;   // النجاح وحده يُبقي قفل المعرف — الإيصال نفسه هو القفل
            journal.add("TOPUP\t" + cents + "\t" + ts + "\t" + p.id() + "\t" + requestId
                    + "\t" + r.receiptId + "\t" + (r.externalRef == null ? "-" : r.externalRef));
            return Result.ok();
        }

        Result debit(long cents, String packId, long ts) {
            if (cents < 0) return Result.no("BAD_PRICE");
            if (balance < cents) return Result.no("INSUFFICIENT");
            balance -= cents;
            journal.add("BUY\t" + packId + "\t" + cents + "\t" + ts);
            return Result.ok();
        }
    }

    /** المتجر: شراء/مكتبة/تثبيت/إغلاق شهري — الملكية قبل الثنائيات دائماً. */
    public static final class Store {
        private final Catalog catalog;
        private final Wallet wallet;
        private final Map<String, String> owned = new LinkedHashMap<>();    // packId -> دبوس الشراء
        private final Map<String, Long> paid = new LinkedHashMap<>();       // packId -> سنت مدفوع
        private final Map<String, Long> library = new LinkedHashMap<>();    // packId -> ts التثبيت
        private final List<Economy.Sale> sales = new ArrayList<>();

        public Store(Catalog catalog, Wallet wallet) {
            this.catalog = catalog;
            this.wallet = wallet;
        }

        public Wallet wallet() { return wallet; }
        public boolean owned(String packId) { return owned.containsKey(packId); }
        public boolean installed(String packId) { return library.containsKey(packId); }
        public int ownedCount() { return owned.size(); }
        public int installedCount() { return library.size(); }
        public List<Economy.Sale> sales() { return new ArrayList<>(sales); }

        /** الشراء idempotent: الحزمة المملوكة لا تُشترى ثانية (بيع صفر، لا خصم). */
        public Result purchase(String packId, long ts) {
            Pack p = catalog.pack(packId);
            if (p == null) return Result.no("NO_PACK");
            if (owned.containsKey(packId)) return Result.no("ALREADY_OWNED");
            Result r = wallet.debit(p.priceCents, packId, ts);
            if (!r.ok) return r;
            owned.put(packId, p.sha256);
            paid.put(packId, p.priceCents);
            sales.add(Economy.Sale.sale(p.vendorId, packId, p.priceCents));
            return Result.ok();
        }

        /**
         * التثبيت: الملكية أولاً، ثم الدبوس (ما بعد الشراء لا يعبث)، ثم مراجعة
         * ThemePack نفسها بموردي الكتالوج المسجلين — لا مسار جانب.
         */
        public Result install(String packId, byte[] packBytes, long ts) {
            String pin = owned.get(packId);
            if (pin == null) return Result.no("NOT_OWNED");
            if (!ThemePack.sha256hex(packBytes).equals(pin)) return Result.no("PIN_MISMATCH");
            ThemePack.Verdict v = ThemePack.review(packBytes, catalog.vendorIds);
            if (!v.ok) return Result.no(v.reasons.length > 0 ? "REVIEW_" + v.reasons[0] : "REVIEW_FAIL");
            if (library.size() >= MAX_LIBRARY && !library.containsKey(packId)) return Result.no("LIBRARY_CAP");
            library.put(packId, ts);
            return Result.ok();
        }

        public Result uninstall(String packId) {
            if (!library.containsKey(packId)) return Result.no("NOT_INSTALLED");
            library.remove(packId);
            return Result.ok();
        }

        public long paidCents() {
            long t = 0;
            for (long v : paid.values()) t += v;
            return t;
        }

        /** حالة الملكية للمزامنة (المرحلة 3، الأسبوع 3): packId → دبوس الشراء. */
        public Map<String, String> ownedPins() { return new LinkedHashMap<>(owned); }

        /** الإغلاق الشهري مفوَّض حصراً للاقتصاد الوحيد (70/30 سنت-دقيق). */
        public Economy.Month monthClose() { return Economy.monthClose(sales); }

        /**
         * منح ملكية من مزامنة جهاز آخر (المرحلة 3، الأسبوع 3): الاستعادة تعيد
         * الملكية لا الثنائيات — الدبيس يجب أن يطابق كتالوج هذا الجهاز حرفياً
         * (المصدر الوحيد للثنائيات هو الكتالوج المثبت بدبوسه الذهبي)، وإلا فشل
         * مغلق. idempotent بالكامل: إعادة منح نفس الملكية = نجاح بلا أثر ثانٍ،
         * لا بيع ولا قيد في الدفتر — المال لا يُلمس هنا (المشترى دفع على جهازه).
         */
        public Result grantFromSync(String packId, String pin) {
            Pack p = catalog.pack(packId);
            if (p == null) return Result.no("NO_PACK");
            if (!p.sha256.equals(pin)) return Result.no("PIN_MISMATCH");
            if (!owned.containsKey(packId)) owned.put(packId, p.sha256);
            return Result.ok();
        }

        /** سطر استحقاقات حتمي (مُزامن كنوع سجل entitlement منذ الأسبوع 3): packId:pin8 مرتبة. */
        public String restoreCode() {
            List<String> ids = new ArrayList<>(owned.keySet());
            Collections.sort(ids);
            StringBuilder b = new StringBuilder("STOREENT1");
            for (String id : ids) b.append('|').append(id).append(':').append(owned.get(id), 0, 8);
            return b.toString();
        }
    }
}
