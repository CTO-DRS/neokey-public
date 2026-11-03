package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * بوابة عميل المزامنة والتوصيل — المرحلة 2، بند A2 (الأسبوع 2). تقيس المخرَج
 * لا الادعاء: جولة كاملة بجهازين على بيانات حية تجمعها SyncState (قاموس
 * واستثناءات وعبارات واختصارات وتثبيتات وإعدادات)، الصفر-المعرفة على مخزون
 * الخادم نفسه بعد الدفع الجديد، مصفوفة بوابة NetGate للقدرة sync، انضباط سطر
 * التدقيق، الأداء ضمن الميزانيات، وسلبيات: رفض البوابة بلا رفع، عبارة خاطئة،
 * تلاعب ملفي، حصة، وقيم إعدادات خارج القائمة البيضاء تُهمل عند التفكيك.
 *
 *   Verdict: SYNCCLIENT_GATE_OK (exit 0) / SYNCCLIENT_GATE_FAILED <reason> (exit 1)
 *   JVM-only (لا android.*) — تعمل في أي بيئة.
 */
public class SyncClientGate {

    static int checks = 0, failed = 0;

    static void must(String name, boolean ok) {
        checks++;
        if (!ok) { failed++; System.out.println("SYNCCLFAIL " + name); }
    }

    public static void main(String[] args) {
        // ===== 1) حالة حية كبيرة تجمعها SyncState من أشكال المخازن ==============
        SyncState.Snapshot snap = new SyncState.Snapshot();
        snap.themeIdx = 11;
        Random rnd = new Random(20261103);
        String[] arSeeds = {"مدرسة", "كتاب", "قهوة", "اجتماع", "تقرير", "مشروع", "فاتورة", "عميل"};
        for (int i = 0; i < 400; i++) snap.dictWords.put(arSeeds[i % arSeeds.length] + i, "ar");
        for (int i = 0; i < 60; i++) snap.dictWords.put("term" + i, "en");
        for (int i = 0; i < 40; i++) snap.exceptionWords.add("استثناء" + i);
        for (int i = 0; i < 30; i++) snap.phrases.add("عبارة سريعة رقم " + i + " ✅");
        for (int i = 0; i < 20; i++) snap.shortcuts.put("م" + i, "التوسعة الكاملة رقم " + i);
        for (int i = 0; i < 25; i++) snap.pinTexts.add("نص مثبّت رقم " + i + " — الرياض");
        snap.settings.put("autocorrect", "b:true");
        snap.settings.put("hpct", "i:112");
        snap.settings.put("kblayout", "i:3");
        snap.settings.put("digits", "i:1");
        List<SyncVault.Rec> deviceA = SyncState.collect(snap, 5, 1762000000000L);
        // 1 سمة + 460 كلمة + 40 استثناء + 30 عبارة + 20 اختصار + 25 تثبيت + 4 إعدادات
        must("الحالة الحية: 580 سجلاً من اللقطة (سقوف مطبقة)", deviceA.size() == 580);
        must("الحالة الحية: كل السجلات مقبولة من Rec", deviceA.size() == 580);

        // ===== 2) الجولة الكاملة: دفع → جهاز جديد → سحب → خطة ===================
        byte[] salt = new byte[SyncVault.SALT_LEN];
        rnd.nextBytes(salt);
        SyncClient.Session a = SyncClient.register("عبارة بوابة الأسبوع ٢٠٢٦", salt);
        SyncServerSim srv = SyncServerSim.fresh();
        srv.register(a.keys.accountId, a.salt);
        SyncClient.PushResult pr = SyncClient.push(a, srv, deviceA, "on", "on", false, false);
        must("الدفع: كتلة واحدة بالحجم المحدد (header+pt+tag)",
                pr.bytes == SyncVault.HEADER_LEN + SyncVault.stateToBytes(deviceA).length + 16);
        must("الخادم: كتلة واحدة بعد الدفع", srv.list(a.keys.accountId).size() == 1);

        SyncClient.Session b = SyncClient.login(srv, "عبارة بوابة الأسبوع ٢٠٢٦", a.keys.accountId);
        must("الدخول: نفس الحساب المستعار", b.keys.accountId.equals(a.keys.accountId));
        long t0 = System.nanoTime();
        List<SyncVault.Rec> restored = SyncClient.pull(b, srv, new ArrayList<SyncVault.Rec>(), "on", "on", false);
        double pullMs = (System.nanoTime() - t0) / 1e6;
        // الدمج يُرتب بالهوية — المقارنة ضد الترتيب القانوني (merge مع فارغ)
        must("السحب: الحالة كاملة (نفس السجلات بالترتيب القانوني)",
                restored.equals(SyncVault.merge(deviceA, new ArrayList<SyncVault.Rec>())));
        SyncState.Plan planB = SyncState.apply(restored);
        must("الخطة على الجهاز الجديد: سمة 11 وقاموس 460 واستثناءات 40",
                planB.themeIdx == 11 && planB.dictAr.size() == 400 && planB.dictEn.size() == 60
                        && planB.exceptions.size() == 40 && planB.phrases.size() == 30
                        && planB.shortcuts.size() == 20 && planB.pins.size() == 25
                        && planB.settings.size() == 4);

        // جهاز B دفع تعديله المحلي (كلمة متعلّمة جديدة) → دمج على A
        SyncState.Snapshot bSnap = new SyncState.Snapshot();
        bSnap.dictWords.put("مصطلح_محلي_جديد", "ar");
        bSnap.settings.put("night_theme", "i:2");
        List<SyncVault.Rec> bState = SyncState.collect(bSnap, 1, 1762000005000L);
        SyncClient.push(b, srv, bState, "on", "on", false, false);
        List<SyncVault.Rec> mergedA = SyncClient.pull(a, srv, deviceA, "on", "on", false);
        boolean hasLocal = false;
        for (SyncVault.Rec r : mergedA) if (r.body.equals("مصطلح_محلي_جديد")) hasLocal = true;
        // دفعة B تحمل سجلين جديدين على A: كلمة متعلّمة + إعداد night_theme
        must("الدفع والسحب عكسياً: تعديل B يصل إلى A (+سجلان جديدان)",
                hasLocal && mergedA.size() == deviceA.size() + 2);

        // ===== 3) الصفر-المعرفة على مخزون الخادم بعد الدفع الجديد ================
        byte[] dump = srv.dump();
        String d = new String(dump, java.nio.charset.StandardCharsets.ISO_8859_1);
        boolean noArabic = true;
        for (String w : new String[]{"مدرسة", "عبارة", "التوسعة", "الرياض", "استثناء", "مصطلح"})
            if (d.contains(w)) noArabic = false;
        must("مخزون الخادم: بلا أي عربية من الحالة الجديدة", noArabic);
        // السحر NSF1 مرئي بتصريح عند بدايات الكتل فقط (باقة تنسيق كـ PGP) —
        // أي ظهور آخر داخل النص المشفر = تسريب؛ وأنواع السجلات/JSON/الحساب ممنوعة.
        int blobs = srv.list(a.keys.accountId).size();
        int magicCount = 0;
        for (int at = d.indexOf("NSF1"); at >= 0; at = d.indexOf("NSF1", at + 1)) magicCount++;
        boolean noKeys = magicCount == blobs;
        for (String k : new String[]{"theme", "clip_pin", "dict_word", "shortcut", "setting",
                "{\"v\":1", "recs", "عبارة"})
            if (noKeys && d.contains(k)) noKeys = false;
        must("مخزون الخادم: السحر عند بدايات الكتل فقط (" + magicCount + "/" + blobs
                + ") وبلا أنواع ولا JSON ولا حساب مستعار", noKeys);
        double entropy = entropy(dump);
        must("مخزون الخادم: إنتروبيا ≥ 7.5 بت/بايت (" + String.format("%.2f", entropy) + ")",
                entropy >= 7.5);

        // ===== 4) مصفوفة بوابة NetGate للقدرة sync ==============================
        must("البوابة: master مفقود/off/غريب → DENY(master_off)",
                SyncClient.gate(null, "on", true).equals(NetGate.DENY_MASTER)
                        && SyncClient.gate("off", "on", true).equals(NetGate.DENY_MASTER)
                        && SyncClient.gate("غريب", "on", true).equals(NetGate.DENY_MASTER));
        must("البوابة: قدرة مفقودة/off/غريبة → DENY(cap_off)",
                SyncClient.gate("on", null, true).equals(NetGate.DENY_CAP)
                        && SyncClient.gate("on", "off", true).equals(NetGate.DENY_CAP)
                        && SyncClient.gate("on", "غريبة", true).equals(NetGate.DENY_CAP));
        must("البوابة: ask بلا موافقة = ASK ومعها = ALLOWED",
                SyncClient.gate("on", "ask", false).equals(NetGate.ASK_USER)
                        && SyncClient.gate("on", "ask", true).equals(NetGate.ALLOW_ASK));
        must("البوابة: on = ALLOWED(cap_on)", SyncClient.gate("on", "on", false).equals(NetGate.ALLOW_CAP));
        must("البوابة: القدرة معروفة في القلب (unknown_cap لغيرها)",
                NetGate.full("on", "on", "قدرة_مجهولة", true).equals(NetGate.DENY_UNKNOWN));
        must("النطاق الأبيض: نطاق sync وحده، وأي آخر مرفوض",
                NetGate.endpointAllowed(NetGate.CAP_SYNC, "https://sync.neokey.app/v1/x")
                        && !NetGate.endpointAllowed(NetGate.CAP_SYNC, "https://sync.neokey.app.evil/v1/")
                        && !NetGate.endpointAllowed(NetGate.CAP_SYNC, "http://sync.neokey.app/v1/"));
        // سلوك رفض الدفع فعلياً بلا أي رفع: الخادم يبقى بكتلة واحدة
        int blobsBefore = srv.list(a.keys.accountId).size();
        boolean refusedNoUpload = false;
        try { SyncClient.push(a, srv, deviceA, "off", "on", false, false); }
        catch (SyncVault.SyncRefused e) { refusedNoUpload = e.getMessage().equals("netgate_master_off"); }
        must("البوابة: رفض الدفع لا يرفع بايتاً (الخادم كما هو)",
                refusedNoUpload && srv.list(a.keys.accountId).size() == blobsBefore);

        // ===== 5) سطر التدقيق: بلا أي محتوى =====================================
        String al = SyncClient.auditLine("push", true, pr.bytes, 1762000001000L)
                + "\n" + SyncClient.auditLine("pull", false, 0, 1762000002000L);
        must("التدقيق: صيغة SYNCAUDIT حتمية بلا محتوى",
                al.contains("SYNCAUDIT\tpush\tOK\t" + pr.bytes) && al.contains("SYNCAUDIT\tpull\tREFUSED\t0"));
        boolean noContent = true;
        for (SyncVault.Rec r : deviceA)
            if (r.body != null && r.body.length() >= 3 && al.contains(r.body)) noContent = false;
        must("التدقيق: لا سجلاً واحداً من الحالة يسرب للسطر", noContent);

        // ===== 6) النسخة الملفية: سلبية صارمة ===================================
        byte[] file = SyncClient.exportBackup(a, deviceA);
        must("الملف: NSF1 صالح ويفتح بنفس الجلسة",
                SyncClient.importBackup(a, file).equals(deviceA));
        boolean wrongPass = false;
        try { SyncClient.importBackup(SyncClient.register("عبارة خاطئة", salt), file); }
        catch (SyncVault.SyncRefused e) { wrongPass = e.getMessage().equals("auth_failed"); }
        must("الملف: عبارة خاطئة → auth_failed", wrongPass);
        byte[] hdr = file.clone(); hdr[10] ^= 0x01;
        boolean hdrRefused = false;
        try { SyncClient.importBackup(a, hdr); }
        catch (SyncVault.SyncRefused e) { hdrRefused = e.getMessage().equals("salt_mismatch"); }
        must("الملف: تبديل بايت في الملح (ترويسة) → salt_mismatch", hdrRefused);
        byte[] body = file.clone();
        body[SyncVault.HEADER_LEN + 5] ^= 0x40;
        boolean bodyRefused = false;
        try { SyncClient.importBackup(a, body); }
        catch (SyncVault.SyncRefused e) { bodyRefused = e.getMessage().equals("auth_failed"); }
        must("الملف: تبديل بايت في المتن المشفر → auth_failed", bodyRefused);
        byte[] cut = new byte[file.length - 30];
        System.arraycopy(file, 0, cut, 0, cut.length);
        boolean cutRefused = false;
        try { SyncClient.importBackup(a, cut); }
        catch (SyncVault.SyncRefused e) { cutRefused = true; }
        must("الملف: ملف مقصوص → رفض", cutRefused);

        // ===== 7) القائمة البيضاء عند التفكيك (دفاع من سجل مزروع) ================
        List<SyncVault.Rec> planted = new ArrayList<>(deviceA);
        planted.add(new SyncVault.Rec("st:ai_key", SyncVault.KIND_SETTING, "ai_key", "s:STOLEN", 9, 99, false));
        planted.add(new SyncVault.Rec("st:net_sync", SyncVault.KIND_SETTING, "net_sync", "s:on", 9, 99, false));
        SyncState.Plan pPlanted = SyncState.apply(planted);
        must("القائمة البيضاء عند التفكيك: سجل مزروع ai_key/net_sync يُهمل",
                !pPlanted.settings.containsKey("ai_key") && !pPlanted.settings.containsKey("net_sync")
                        && pPlanted.settings.size() == 4);

        // ===== 8) الحصة والأداء ==================================================
        boolean quotaRefused = false;
        try {
            SyncVault.Quota q = SyncVault.checkQuota(SyncVault.ACCOUNT_FREE_BYTES, 1, false);
            if (!q.allowed) quotaRefused = true;
        } catch (RuntimeException e) { quotaRefused = false; }
        must("الحصة: كتلة تُكمل الحصة المجانية تُرفض (مجاني 1MB)", quotaRefused);

        SyncState.Snapshot big = new SyncState.Snapshot();
        big.themeIdx = 2;
        for (int i = 0; i < 3000; i++) big.dictWords.put("قاموس" + i + "عينة" + (i % 9), "ar");
        t0 = System.nanoTime();
        List<SyncVault.Rec> bigState = SyncState.collect(big, 7, 1762000000000L);
        byte[] bigBlob = SyncVault.seal(a.keys, SyncVault.stateToBytes(bigState),
                new byte[SyncVault.NONCE_LEN]);
        double ms = (System.nanoTime() - t0) / 1e6;
        must("الأداء: تجميع+ختم 3000 كلمة ≤ 150ms (" + String.format("%.1f", ms) + "ms)",
                ms <= 150 && bigBlob.length > SyncVault.HEADER_LEN);
        must("الحجم: حالة 3000 كلمة ≤ الحصة المجانية",
                SyncVault.stateToBytes(bigState).length <= SyncVault.ACCOUNT_FREE_BYTES);

        long kdf0 = System.nanoTime();
        SyncVault.derive("عبارة بوابة الأسبوع ٢٠٢٦", salt);
        double kdfMs = (System.nanoTime() - kdf0) / 1e6;
        must("KDF: ضمن ميزانية فتح الجلسة 400ms (" + String.format("%.0f", kdfMs) + "ms)", kdfMs <= 400);

        // ===== الخلاصة ============================================================
        System.out.println("SYNCCLGATE state_recs=" + deviceA.size()
                + " blob_bytes=" + pr.bytes
                + " pull_ms=" + String.format("%.1f", pullMs)
                + " entropy=" + String.format("%.2f", entropy)
                + " big_ms=" + String.format("%.1f", ms)
                + " kdf_ms=" + String.format("%.0f", kdfMs)
                + " checks=" + checks + " failed=" + failed);
        if (failed == 0) { System.out.println("SYNCCLIENT_GATE_OK"); System.exit(0); }
        System.out.println("SYNCCLIENT_GATE_FAILED failed=" + failed);
        System.exit(1);
    }

    static double entropy(byte[] data) {
        int[] freq = new int[256];
        for (byte b : data) freq[b & 0xFF]++;
        double h = 0;
        for (int f : freq) {
            if (f == 0) continue;
            double p = (double) f / data.length;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }
}
