package com.hark.neokey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * اختبارات عميل المزامنة والتوصيل — المرحلة 2، بند A2 (الأسبوع 2). تقيس أن
 * السجلات الخمسة صارت بيانات حية: التجميع من أشكال المخازن الحقيقية نفسها
 * (pref \u0001 وملفات word\tcount وخريطة shortcuts)، التفكيك خطة كتابة صحيحة،
 * البوابة فشل مغلق، التدقيق بلا محتوى، والنسخة الملفية NSF1 ذهاباً وعوداً.
 * نقي JVM — تعمل في أي بيئة.
 */
public class SyncClientTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    // ---- لقطة مخازن حقيقية (نفس الأشكال التي تقرأها الواجهة) --------------------

    static SyncState.Snapshot realSnapshot() {
        SyncState.Snapshot s = new SyncState.Snapshot();
        s.themeIdx = 7;
        // user_ar.txt: أسطر word\tcount — من ضمنها عائلات همزة متكررة وتشكيل
        s.dictWords.put("المدرسة", "ar");
        s.dictWords.put("المدرسه", "ar");        // نفس norm → سجل واحد
        s.dictWords.put("الآن", "ar");
        s.dictWords.put("الان", "ar");           // نفس norm → سجل واحد
        s.dictWords.put("مشكله", "ar");
        s.dictWords.put("deadline", "en");
        s.dictWords.put("سprint", "xx");         // لغة غريبة → يُرفض
        s.exceptionWords.addAll(Arrays.asList("مشكله", "شكراً", "انشالله"));
        s.phrases.addAll(Arrays.asList("تم ✅", "أنا في الطريق 🚗", "جمعة مباركة"));
        s.shortcuts.put("سع", "السلام عليكم ورحمة الله وبركاته");
        s.shortcuts.put("omw", "on my way");
        s.pinTexts.add("رقم الجوال: ٠٥٥٥٥٥٥٥٥٥");
        s.pinTexts.add("عنوان المكتب: الرياض، طريق الملك فهد");
        s.settings.put("autocorrect", "b:true");
        s.settings.put("hpct", "i:110");
        s.settings.put("ai_key", "s:SECRET-NEVER");   // خارج القائمة → يُرفض
        s.settings.put("net_sync", "s:on");           // خارج القائمة → يُرفض
        return s;
    }

    static SyncVault.Rec rec(String id, String kind, String key, String body, int rev, long ts) {
        return new SyncVault.Rec(id, kind, key, body, rev, ts, false);
    }

    public static void main(String[] args) {
        // ===== 1) التجميع: لقطة حقيقية → سجلات =================================
        SyncState.Snapshot snap = realSnapshot();
        List<SyncVault.Rec> state = SyncState.collect(snap, 3, 1762000000000L);
        int theme = 0, dict = 0, exc = 0, phr = 0, sc = 0, pin = 0, set = 0;
        for (SyncVault.Rec r : state) {
            if (r.id.startsWith("theme:")) theme++;
            else if (r.id.startsWith("dw:")) dict++;
            else if (r.id.startsWith("ex:")) exc++;
            else if (r.id.startsWith("ph:")) phr++;
            else if (r.id.startsWith("sc:")) sc++;
            else if (r.id.startsWith("pin:")) pin++;
            else if (r.id.startsWith("st:")) set++;
        }
        check("التجميع: سجل السمة واحد (themeIdx=7)", theme == 1 && state.get(0).body.equals("7"));
        check("التجميع: عائلات الهمزة سجل واحد (المدرسة/المدرسه، الآن/الان)", dict == 4);
        check("التجميع: الاستثناءات 3 (حد Exceptions.CAP)", exc == 3);
        check("التجميع: العبارات 3 والاختصارات 2", phr == 3 && sc == 2);
        check("التجميع: التثبيتات 2 بمفاتيح مواضع مرتبة", pin == 2
                && state.get(13).key.equals("0000") && state.get(14).key.equals("0001"));
        check("التجميع: القائمة البيضاء تمنع ai_key وnet_sync وtheme المزدوج", set == 2);
        boolean noSecret = true;
        for (SyncVault.Rec r : state) if (r.body != null && r.body.contains("SECRET")) noSecret = false;
        check("التجميع: لا سر في أي سجل", noSecret);
        boolean idsStable = SyncState.collect(snap, 3, 1762000000000L).equals(state);
        check("التجميع: حتمي — نفس اللقطة = نفس السجلات", idsStable);
        List<SyncVault.Rec> otherRev = SyncState.collect(snap, 4, 1762000000000L);
        boolean sameIds = true;
        for (int i = 0; i < state.size(); i++)
            if (!state.get(i).id.equals(otherRev.get(i).id)) sameIds = false;
        check("التجميع: الهوية من المحتوى لا من rev (نفس ids مع rev مختلف)", sameIds);

        // ===== 2) التفكيك: سجلات → خطة كتابة في المخازن =========================
        SyncState.Plan plan = SyncState.apply(state);
        check("التفكيك: السمة 7", plan.themeIdx == 7);
        check("التفكيك: عربي 3 كلمات (بعد إملاء الهمزات) وإنجليزي 1", plan.dictAr.size() == 3 && plan.dictEn.size() == 1);
        check("التفكيك: استثناءات 3 وعبارات 3 واختصارات 2", plan.exceptions.size() == 3
                && plan.phrases.size() == 3 && plan.shortcuts.size() == 2);
        check("التفكيك: التثبيتان بالترتيب الأصلي", plan.pins.size() == 2
                && plan.pins.get(0).startsWith("رقم الجوال"));
        check("التفكيك: الإعدادات 2 فقط (الممنوع رُفض)", plan.settings.size() == 2
                && "110".equals(plan.settings.get("hpct").replace("i:", "")));
        check("التفكيك: الخطة عدد سجلاتها الكامل", plan.count() == 1 + 4 + 3 + 3 + 2 + 2 + 2);

        // ===== 3) جولة الجهازين على بيانات حية (نفس قناة W1) ====================
        java.util.Random saltRnd = new java.util.Random(20261103);
        byte[] saltA = new byte[SyncVault.SALT_LEN];
        saltRnd.nextBytes(saltA);
        SyncClient.Session a = SyncClient.register("عبارة الحساب القوية", saltA);
        SyncServerSim srv = SyncServerSim.fresh();
        srv.register(a.keys.accountId, a.salt);
        SyncClient.PushResult pr = null;
        boolean pushed = false;
        try {
            pr = SyncClient.push(a, srv, state, "on", "on", false, false);
            pushed = true;
        } catch (SyncVault.SyncRefused e) { }
        check("الدفع: يمر بإذن on ويرفع كتلة بالحجم المتوقع", pushed && pr != null
                && pr.bytes == SyncVault.HEADER_LEN + SyncVault.stateToBytes(state).length + 16);

        // جهاز B: دخول بالملح العلني ثم سحب ثم دمج مع حالته المحلية الخاصة
        SyncClient.Session b = SyncClient.login(srv, "عبارة الحساب القوية", a.keys.accountId);
        check("الدخول: نفس الحساب المستعار من الملح العلني", b.keys.accountId.equals(a.keys.accountId));
        List<SyncVault.Rec> localB = new ArrayList<>();
        localB.add(rec("dw:ar:" + SyncState.tag("جديد_محلي"), SyncVault.KIND_DICT_WORD, "ar", "جديد_محلي", 1, 100));
        List<SyncVault.Rec> merged = SyncClient.pull(b, srv, localB, "on", "on", false);
        boolean hasA = false, hasB = false;
        for (SyncVault.Rec r : merged) {
            if (r.id.equals("theme:current") && r.body.equals("7")) hasA = true;
            if (r.body.equals("جديد_محلي")) hasB = true;
        }
        check("السحب: حالة A كاملة + محلي B محفوظ في الدمج", hasA && hasB);
        SyncState.Plan planB = SyncState.apply(merged);
        check("التفكيك بعد الدمج: خطة كاملة على بيانات الجهازين", planB.themeIdx == 7
                && planB.dictAr.size() == 4 && planB.pins.size() == 2);

        // ===== 4) البوابة: فشل مغلق بالكامل =====================================
        boolean closed = true;
        String[] closedCases = {null, "off", "قيمة غريبة"};
        for (String m : closedCases) {
            try { SyncClient.push(a, srv, state, m, "on", false, false); closed = false; }
            catch (SyncVault.SyncRefused e) { if (!e.getMessage().startsWith("netgate_")) closed = false; }
        }
        check("البوابة: master off/غائب/غريب → لا اتصال إطلاقاً", closed);
        boolean capClosed = true;
        for (String c : new String[]{null, "off", "غريب"}) {
            try { SyncClient.push(a, srv, state, "on", c, false, false); capClosed = false; }
            catch (SyncVault.SyncRefused e) { if (!e.getMessage().startsWith("netgate_")) capClosed = false; }
            try { SyncClient.pull(b, srv, localB, "on", c, false); capClosed = false; }
            catch (SyncVault.SyncRefused e) { if (!e.getMessage().startsWith("netgate_")) capClosed = false; }
        }
        check("البوابة: قدرة sync مغلقة → رفض الدفع والسحب معاً", capClosed);
        NetGate.Decision ask = SyncClient.gate("on", "ask", false);
        NetGate.Decision granted = SyncClient.gate("on", "ask", true);
        check("البوابة: ask بلا موافقة = ASK، مع موافقة = ALLOWED",
                ask == NetGate.ASK_USER && granted == NetGate.ALLOW_ASK);
        boolean askBlocked = false;
        try { SyncClient.push(a, srv, state, "on", "ask", false, false); }
        catch (SyncVault.SyncRefused e) { askBlocked = e.getMessage().equals("netgate_ask"); }
        check("البوابة: ask بلا موافقة لا يرفع بايتاً واحداً", askBlocked);
        check("البوابة: نطاق sync الأبيض فقط — نطاق آخر مرفوض",
                !NetGate.endpointAllowed(NetGate.CAP_SYNC, "https://evil.example.com/v1/x")
                        && !NetGate.endpointAllowed(NetGate.CAP_SYNC, "http://sync.neokey.app/v1/")
                        && NetGate.endpointAllowed(NetGate.CAP_SYNC, "https://sync.neokey.app/v1/account"));

        // ===== 5) الحصة والرفض ==================================================
        SyncVault.Quota over = SyncVault.checkQuota(SyncVault.ACCOUNT_FREE_BYTES + 1, 0, false);
        SyncVault.Quota vipOk = SyncVault.checkQuota(SyncVault.ACCOUNT_FREE_BYTES + 1, 0, true);
        check("الحصة: تجاوز المجاني يُرفض وVIP يتسع", !over.allowed && vipOk.allowed);
        boolean emptyDenied = false;
        try { SyncClient.register("", new byte[SyncVault.SALT_LEN]); }
        catch (SyncVault.SyncRefused e) { emptyDenied = e.getMessage().equals("empty_passphrase"); }
        check("التسجيل: عبارة فارغة → empty_passphrase", emptyDenied);
        boolean badSalt = false;
        try { SyncClient.register("x", new byte[8]); }
        catch (SyncVault.SyncRefused e) { badSalt = e.getMessage().equals("bad_salt"); }
        check("التسجيل: ملح ناقص → bad_salt", badSalt);
        boolean mismatch = false;
        try { SyncClient.login(srv, "عبارة الحساب القوية", "حساب-آخر"); }
        catch (RuntimeException e) { mismatch = true; }   // unknown_account أو account_mismatch
        check("الدخول: حساب لا يطابق الملح → رفض", mismatch);

        // ===== 6) النسخة الملفية NSF1 (بلا شبكة) =================================
        byte[] file = SyncClient.exportBackup(a, state);
        check("النسخة الملفية: ترويسة NSF1 صحيحة", file.length > SyncVault.HEADER_LEN + 16
                && file[0] == 'N' && file[1] == 'S' && file[2] == 'F' && file[3] == '1');
        List<SyncVault.Rec> round = SyncClient.importBackup(a, file);
        check("النسخة الملفية: ذهاباً وعوداً = نفس السجلات", round.equals(state));
        boolean wrongKey = false;
        try { SyncClient.importBackup(SyncClient.register("عبارة أخرى", a.salt), file); }
        catch (SyncVault.SyncRefused e) { wrongKey = e.getMessage().equals("auth_failed"); }
        check("النسخة الملفية: عبارة خاطئة → auth_failed", wrongKey);
        byte[] tampered = file.clone();
        tampered[SyncVault.HEADER_LEN + 5] ^= 0x40;
        boolean tamperRefused = false;
        try { SyncClient.importBackup(a, tampered); }
        catch (SyncVault.SyncRefused e) { tamperRefused = e.getMessage().equals("auth_failed"); }
        check("النسخة الملفية: بايت مُعدّل في المتن → رفض", tamperRefused);

        // ===== 7) سطر التدقيق: بلا أي محتوى =====================================
        String al = SyncClient.auditLine("push", true, 1234, 1762000001000L);
        String alBad = SyncClient.auditLine("pull", false, 0, 1762000002000L);
        check("التدقيق: الصيغة SYNCAUDIT\\taction\\tverdict\\tbytes\\tts",
                al.equals("SYNCAUDIT\tpush\tOK\t1234\t1762000001000")
                        && alBad.equals("SYNCAUDIT\tpull\tREFUSED\t0\t1762000002000"));
        boolean noContent = true;
        for (SyncVault.Rec r : state)
            // النصوص ذات المعنى (≥3 محارف) فقط — حرف رقمي واحد يطابق الطوابع مصادفةً
            if (r.body != null && r.body.length() >= 3 && (al + alBad).contains(r.body)) noContent = false;
        for (String w : new String[]{"المدرسة", "السلام", "جوال", "الرياض", "SECRET"})
            if ((al + alBad).contains(w)) noContent = false;
        check("التدقيق: لا نص مستخدم ولا معرف كتلة في السطر", noContent);

        // ===== 8) أداء التجميع: قاموس 3000 كلمة =================================
        SyncState.Snapshot big = new SyncState.Snapshot();
        big.themeIdx = 3;
        for (int i = 0; i < 3000; i++) big.dictWords.put("كلمة" + i + "مدرسة" + (i % 7), "ar");
        long t0 = System.nanoTime();
        List<SyncVault.Rec> bigState = SyncState.collect(big, 9, 1762000000000L);
        double collectMs = (System.nanoTime() - t0) / 1e6;
        check("الأداء: تجميع 3000 كلمة ≤ 120ms (" + String.format("%.1f", collectMs) + "ms، "
                + bigState.size() + " سجلاً)", collectMs <= 120 && bigState.size() >= 3000);
        byte[] bigBytes = SyncVault.stateToBytes(bigState);
        check("الحجم: حالة 3000 كلمة ضمن الحصة المجانية (" + bigBytes.length + "B ≤ 1MB)",
                bigBytes.length <= SyncVault.ACCOUNT_FREE_BYTES);

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
