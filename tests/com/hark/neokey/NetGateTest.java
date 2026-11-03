package com.hark.neokey;

/**
 * اختبارات بوابة الأذونات — المرحلة 1، البند D1 (الأسبوع 6): مصفوفة الفشل
 * المغلق كاملة + القائمة البيضاء + سطر التدقيق. البوابة التي تفتح عند
 * الغموض ليست بوابة.
 */
public class NetGateTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        run();
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void run() {
        // ---- الفشل المغلق ------------------------------------------------------
        check("master غائب/فارغ/off/غريب → DENIED",
                NetGate.full(null, "on", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_MASTER)
                        && NetGate.full("", "on", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_MASTER)
                        && NetGate.full("off", "on", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_MASTER)
                        && NetGate.full("غير معروف", "on", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_MASTER));

        check("قدرة غير معروفة → DENIED حتى مع فتح كل شيء",
                NetGate.full("on", "on", "cloud_vip", true).equals(NetGate.DENY_UNKNOWN)
                        && NetGate.full("on", "on", "", true).equals(NetGate.DENY_UNKNOWN)
                        && NetGate.full("on", "on", null, true).equals(NetGate.DENY_UNKNOWN)
                        && !NetGate.known("cloud_vip")
                        && !NetGate.endpointAllowed("cloud_vip", "https://openrouter.ai/api/v1/chat/completions"));

        check("إذن القدرة مغلق/غائب/غريب → DENIED",
                NetGate.full("on", null, NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_CAP)
                        && NetGate.full("on", "off", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_CAP)
                        && NetGate.full("on", "قيمة غريبة", NetGate.CAP_WRITER_AI, true).equals(NetGate.DENY_CAP));

        // ---- وضع ask -----------------------------------------------------------
        NetGate.Decision ask = NetGate.full("on", "ask", NetGate.CAP_WRITER_AI, false);
        check("ask بلا موافقة → ASK", ask.equals(NetGate.ASK_USER) && !ask.allowed && ask.ask);
        NetGate.Decision granted = NetGate.full("on", "ask", NetGate.CAP_WRITER_AI, true);
        check("ask مع موافقة → ALLOWED لهذا الإرسال",
                granted.equals(NetGate.ALLOW_ASK) && granted.allowed && !granted.ask);

        // ---- الفتح الصريح -------------------------------------------------------
        check("master on + إذن on → ALLOWED",
                NetGate.full("on", "on", NetGate.CAP_WRITER_AI, false).equals(NetGate.ALLOW_CAP)
                        && NetGate.ALLOW_CAP.allowed);

        check("القرارات الثابتة متسقة ومتمايزة",
                !NetGate.DENY_MASTER.equals(NetGate.DENY_CAP)
                        && !NetGate.DENY_UNKNOWN.equals(NetGate.DENY_CAP)
                        && NetGate.DENY_MASTER.equals(NetGate.DENY_MASTER)
                        && !NetGate.ASK_USER.equals(NetGate.ALLOW_ASK));

        // ---- القائمة البيضاء ----------------------------------------------------
        check("النطاق الوحيد للمساعد: openrouter/api/v1/ (https فقط)",
                "https://openrouter.ai/api/v1/".equals(NetGate.endpoint(NetGate.CAP_WRITER_AI))
                        && NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "https://openrouter.ai/api/v1/chat/completions")
                        && !NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "http://openrouter.ai/api/v1/chat/completions")
                        && !NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "https://evil.example.com/api/v1/x")
                        && !NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, "https://openrouter.ai.evil.com/api/v1/x")
                        && !NetGate.endpointAllowed(NetGate.CAP_WRITER_AI, null)
                        && !NetGate.endpointAllowed(null, "https://openrouter.ai/api/v1/x"));

        // ---- سطر التدقيق --------------------------------------------------------
        String a = NetGate.auditLine(NetGate.CAP_WRITER_AI, NetGate.ALLOW_CAP, 1730000000000L);
        check("سطر التدقيق محكم 5 حقول بلا نص مستخدم",
                a.equals("NETGATE\twriter_ai\tALLOWED\tcap_on\t1730000000000") && a.split("\t").length == 5);
        check("سطر DENIED يحمل السبب",
                NetGate.auditLine(NetGate.CAP_WRITER_AI, NetGate.DENY_MASTER, 1L).contains("DENIED")
                        && NetGate.auditLine(NetGate.CAP_WRITER_AI, NetGate.DENY_MASTER, 1L).contains("master_off"));
        check("سطر ASK مميز", NetGate.auditLine(NetGate.CAP_WRITER_AI, NetGate.ASK_USER, 1L).contains("\tASK\t"));

        check("مفاتيح الإعدادات ثابتة",
                "net_state".equals(NetGate.MASTER_PREF) && "net_writer_ai".equals(NetGate.capPref(NetGate.CAP_WRITER_AI)));

        // ---- الأداء -------------------------------------------------------------
        long n0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) NetGate.full("on", "ask", NetGate.CAP_WRITER_AI, false);
        double ms = (System.nanoTime() - n0) / 1e6;
        check("مليون قرار < 1000ms (فعلياً " + String.format("%.0f", ms) + "ms)", ms < 1000);
    }
}
