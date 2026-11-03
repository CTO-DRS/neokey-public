package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static com.hark.neokey.ThemePack.*;

/**
 * اختبارات مدقق حزم السمات — المرحلة 2، البند B: المحلل الصارم (مكررات/محارف
 * تحكم/أعماق/لاحق زائد)، قائمة المفاتيح المسموحة، جودة النص العربي في الاسم،
 * الألوان والتباين، النطاقات، ومتجه SHA-256 الحقيقي للدبوس.
 */
public class ThemePackTest {

    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static Set<String> vendors() {
        Set<String> s = new HashSet<String>();
        for (int i = 1; i <= 10; i++) s.add(String.format("f%02d", i));
        return s;
    }

    static Verdict rev(String json) { return review(json.getBytes(StandardCharsets.UTF_8), vendors()); }

    static String base() {
        return "{\"app\":\"NeoKey\",\"bg\":\"#0E190D\",\"enter\":\"#A02BEE\",\"enterText\":\"#F5F2F7\","
                + "\"key\":\"#2A2438E6\",\"name\":\"زجاج نيون\",\"neokey\":1,\"neon\":true,"
                + "\"glow\":\"#AC38FA\",\"glow2\":\"#FA429B\",\"text\":\"#F2F3EF\",\"vendor\":\"f01\"}";
    }

    static boolean has(Verdict v, String tag) {
        for (String r : v.reasons) if (r.equals(tag)) return true;
        return false;
    }

    public static void main(String[] a) {
        // ---- strict JSON parser ----
        check("json.simple", ThemePack.Json.parse("{\"a\":1,\"b\":[true,null,\"x\"]}").isObj());
        try { ThemePack.Json.parse("{\"a\":1,\"a\":2}"); check("json.dup-refused", false); }
        catch (IllegalArgumentException e) { check("json.dup-refused", true); }
        try { ThemePack.Json.parse("{} trailing"); check("json.trailing-refused", false); }
        catch (IllegalArgumentException e) { check("json.trailing-refused", true); }
        try { ThemePack.Json.parse("{\"a\":\"x\u0007y\"}"); check("json.ctrl-refused", false); }
        catch (IllegalArgumentException e) { check("json.ctrl-refused", true); }
        check("json.root-array-parses", ThemePack.Json.parse("[1,2]").isArr());
        check("review.shape-array-refused", has(rev("[1,2]"), "shape"));
        check("json.unicode-escape", ThemePack.Json.parse("{\"a\":\"\\u0632\"}").obj().get("a").str().equals("ز"));
        check("json.nested", ThemePack.Json.parse("{\"l\":{\"k\":1}}").obj().get("l").obj().get("k").num() == 1);
        boolean deep = false;
        try {
            String s = ""; for (int i = 0; i <= MAX_DEPTH + 1; i++) s += "{\"a\":"; s += "1";
            for (int i = 0; i <= MAX_DEPTH + 1; i++) s += "}"; ThemePack.Json.parse(s);
        } catch (IllegalArgumentException e) { deep = true; }
        check("json.depth-refused", deep);

        // ---- review: the good pack ----
        Verdict ok = rev(base());
        check("review.good-pass", ok.ok && ok.reasons.length == 0);
        check("review.name-echo", "زجاج نيون".equals(ok.name) && "f01".equals(ok.vendor));
        check("review.nanos-measured", ok.nanos >= 0);

        // ---- version / app ----
        check("review.version-required", has(rev(base().replace("\"neokey\":1", "\"neokey\":2")), "version"));
        check("review.app-mismatch", has(rev(base().replace("NeoKey", "NeoCopy")), "app"));
        check("review.empty", has(rev(""), "empty"));

        // ---- allowlist catches planted fields ----
        check("review.allowlist", has(rev(base().replace("\"name\"", "\"api_key\":\"x\",\"name\"")), "key:api_key"));

        // ---- vendor registry ----
        check("review.vendor-unknown", has(rev(base().replace("f01", "f77")), "vendor.unknown"));
        check("review.vendor-null-registry", !review(base().getBytes(StandardCharsets.UTF_8), null).ok);

        // ---- Arabic copy quality ----
        check("arabic.bidi", hasBidi("ز\u200Fج"));
        check("arabic.bidi-isolate", hasBidi("ز\u2067ج"));
        check("arabic.tatweel", hasTatweel("زجــاج"));
        check("arabic.tashkeel", hasTashkeel("زُجاج"));
        check("arabic.clean-name-pass", !has(rev(base()), "name.tashkeel"));
        check("arabic.name-ws", has(rev(base().replace("زجاج نيون", " زجاج")), "name.ws"));
        check("arabic.name-length", has(rev(base().replace("زجاج نيون", str(41))), "name.length"));
        check("arabic.name-empty", has(rev(base().replace("زجاج نيون", "")), "name.length"));

        // ---- colours ----
        check("color.rgb3", parseColor(nStr("\"#F1A\"")) != null);
        check("color.argb8", parseColor(nStr("\"#FF112233\"")) == 0xFF112233);
        check("color.rrggbb-opaque", (parseColor(nStr("\"#112233\"")) >>> 24) == 0xFF);
        check("color.bad", parseColor(nStr("\"#GG1122\"")) == null);
        check("color.bad2", parseColor(nStr("\"112233\"")) == null);
        check("color.review-bad-hex", has(rev(base().replace("#A02BEE", "#ZZZZZZ")), "color:enter"));
        check("color.bg-alpha-refused", has(rev(base().replace("\"bg\":\"#0E190D\"", "\"bg\":\"#100E190D\"")), "color.bg.alpha"));
        check("review.required", has(rev("{\"neokey\":1,\"vendor\":\"f01\",\"name\":\"س\"}"), "required"));

        // ---- ranges ----
        check("range.font", has(rev(base().replace("\"name\"", "\"font\":9,\"name\"")), "range.font"));
        check("range.anim", has(rev(base().replace("\"name\"", "\"anim\":30,\"name\"")), "range.anim"));
        check("range.anim-neg-ok", !has(rev(base().replace("\"name\"", "\"anim\":-1,\"name\"")), "range.anim"));
        check("range.borderw", has(rev(base().replace("\"name\"", "\"borderW\":20,\"name\"")), "range.borderW"));
        check("range.borderw-frac-ok", !has(rev(base().replace("\"name\"", "\"borderW\":2.5,\"name\"")), "range.borderW"));
        check("layout.range", has(rev(base().replace("\"name\"", "\"layout\":{\"hpct\":300},\"name\"")), "layout.hpct"));
        check("layout.bool", has(rev(base().replace("\"name\"", "\"layout\":{\"pressglow\":1},\"name\"")), "layout.pressglow"));
        check("layout.unknown-key", has(rev(base().replace("\"name\"", "\"layout\":{\"hack\":1},\"name\"")), "layout.key:hack"));

        // ---- contrast math (WCAG 2.1, composited) ----
        check("contrast.white-black", contrastRatio(0xFFFFFFFF, 0xFF000000) > 15.0);
        check("contrast.same-zero", contrastRatio(0xFF808080, 0xFF808080) < 1.01);
        check("contrast.opaque-bg", contrastRatio(0xFFF2F3EF, 0xFF0E190D) >= MIN_CONTRAST);
        check("contrast.blend-full-alpha", blend(0xFF336699, 0xFF000000) == 0xFF336699);
        check("contrast.blend-half", blend(0x80FFFFFF, 0xFF000000) == 0xFF808080);
        check("contrast.review-violation", has(rev(base().replace("\"text\":\"#F2F3EF\"", "\"text\":\"#1A1720\"")), "contrast.key"));

        // ---- neon identity ----
        check("neon.glow-required", has(rev(base().replace(",\"glow\":\"#AC38FA\"", "")), "neon.glow"));

        // ---- size / publish pinning ----
        check("size.cap", review(new byte[MAX_BYTES + 1], vendors()).reasons[0].equals("size"));
        String sha = sha256hex("neokey".getBytes(StandardCharsets.UTF_8));
        check("sha256.known-vector", sha.equals("dafc00d728d375272ab94a4358a529d9019d6274d07c7f77a08d6a3fca31dfb2"));
        check("sha256.deterministic", sha256hex("neokey".getBytes(StandardCharsets.UTF_8)).equals(sha));

        System.out.println();
        System.out.println("ThemePackTest: " + pass + " pass, " + fail + " fail");
        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static String str(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('ص');
        return b.toString();
    }

    static ThemePack.Json.Node nStr(String s) { return ThemePack.Json.parse(s); }
}
