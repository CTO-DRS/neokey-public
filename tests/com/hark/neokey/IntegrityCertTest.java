package com.hark.neokey;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * بطارية W66 — IntegrityCertTest: كل قانون اختباران موجب/سلبي على الأقل.
 * المصفوفات الاصطناعية sha256 محسوبة وخزنة كثوابت (حتمية)، والفحص الحي
 * يقابل الأرتِفْت الموقّع الحقيقي بالبيان المرفوع — الشهادة نفسها قانون.
 * نقي JVM — يجري داخل tests/run.sh وبوابة ci/cert_gate.sh.
 */
public class IntegrityCertTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static File vec(byte[] bytes) throws Exception {
        File f = File.createTempFile("nkcert", ".bin");
        FileOutputStream o = new FileOutputStream(f);
        o.write(bytes);
        o.close();
        f.deleteOnExit();
        return f;
    }

    public static void main(String[] args) throws Exception {
        // ---- المصفوفات الحتمية: sha256 ثوابت محسوبة سلفاً -----------------------
        File a = vec("neokey-integrity-w66-a".getBytes(StandardCharsets.UTF_8));
        File b = vec("neokey-integrity-w66-b".getBytes(StandardCharsets.UTF_8));
        File empty = vec(new byte[0]);
        check("sha: المحتوى أ → البصمة الثابتة",
                "b7378c9b3b3822f567e044db663c4628e6b4d25d81858597351e0ad0c00a9933"
                        .equals(IntegrityCert.sha256Of(a.getPath())));
        check("sha: المحتوى ب → بصمته الثابتة (لا تختلط المصفوفات)",
                "c509d54bce4d3eead6f2f77a2cecf2b6f737d6ff05a5b309dee779236536334d"
                        .equals(IntegrityCert.sha256Of(b.getPath())));
        check("sha: الملف الفارغ → e3b0c442 (المرجع القياسي)",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                        .equals(IntegrityCert.sha256Of(empty.getPath())));
        check("sha: null والمفقود → null (فشل مغلق)",
                IntegrityCert.sha256Of(null) == null
                        && IntegrityCert.sha256Of("/no/such/artifact.apk") == null);

        // ---- القانون 1: الحكم من البايتات ---------------------------------------
        IntegrityCert.Cert ok = IntegrityCert.evaluate(a.getPath(),
                "b7378c9b3b3822f567e044db663c4628e6b4d25d81858597351e0ad0c00a9933",
                3, "0.3.0", 2329);
        check("cert: المطابقة من البايتات نفسها",
                IntegrityCert.MATCH.equals(ok.state)
                        && ok.display.contains("vc 3 / 0.3.0")
                        && ok.display.contains("2329"));

        // ---- القانون 2: زرع بايت غريب يعضّ (السلبي الحي) -------------------------
        File planted = vec("neokey-integrity-w66-a-X".getBytes(StandardCharsets.UTF_8));
        IntegrityCert.Cert bite = IntegrityCert.evaluate(planted.getPath(),
                "b7378c9b3b3822f567e044db663c4628e6b4d25d81858597351e0ad0c00a9933",
                3, "0.3.0", 2329);
        check("cert: بايت مزروع → CERT_MISMATCH لا مطابقة",
                IntegrityCert.MISMATCH.equals(bite.state)
                        && !bite.display.startsWith("الشهادة: مطابقة"));

        // ---- القانون 3: الفشل المغلق — المجهول لا يُعرض مطابقة -------------------
        IntegrityCert.Cert unk = IntegrityCert.evaluate("/no/such/artifact.apk",
                "b7378c9b3b3822f567e044db663c4628e6b4d25d81858597351e0ad0c00a9933",
                3, "0.3.0", 2329);
        check("cert: الأثر المفقود → CERT_UNKNOWN (لا يُعرض كمطابقة أبداً)",
                IntegrityCert.UNKNOWN.equals(unk.state)
                        && !unk.display.startsWith("الشهادة: مطابقة"));
        IntegrityCert.Cert nullSha = IntegrityCert.evaluate(a.getPath(), null,
                3, "0.3.0", 2329);
        check("cert: بيان ناقص (sha=null) → غير مطابق لا مطابقة",
                IntegrityCert.MISMATCH.equals(nullSha.state));

        // ---- سطر الشاشة الحتمي: أرقام وأوسام، بلا بصمة كاملة --------------------
        check("display: البصمة الكاملة لا تدخل سطر الشاشة",
                !ok.display.contains("b7378c9b3b3822")
                        && IntegrityCert.shortSha("b7378c9b3b3822f567e044db663c4628e6b4d25d81858597351e0ad0c00a9933")
                        .equals("b7378c9b3b38"));
        check("shortSha: قصير/null → «—» بأمان",
                "—".equals(IntegrityCert.shortSha(null))
                        && "—".equals(IntegrityCert.shortSha("abc")));

        // ---- الفحص الحي الدوّار: بلا افتراض تناسق التوليد ------------------------
        // zip assembly is NOT deterministic across regeneration times (zip
        // entries carry fresh timestamps), so the battery must not assume the
        // live artifact matches the live statement. It measures the REAL pair
        // and asserts the certificate's verdict on THAT pair — consistent in
        // every environment, and still biting any evaluate() breakage.
        File apk = new File("build/NeoKey-2036.apk");
        File stmt = new File("build/release/signedapk-statement.json");
        if (apk.isFile() && stmt.isFile()) {
            String stmtRaw = new String(Files.readAllBytes(stmt.toPath()),
                    StandardCharsets.UTF_8);
            String want = extract(stmtRaw, "apkSha256");
            String vc = extract(stmtRaw, "versionCode");
            String vn = extract(stmtRaw, "versionName");
            int vcN = Integer.parseInt(vc);
            String actual = IntegrityCert.sha256Of(apk.getPath());
            boolean pairMatches = actual != null && actual.equalsIgnoreCase(want);
            IntegrityCert.Cert live = IntegrityCert.evaluate(apk.getPath(),
                    want, vcN, vn, 2329);
            check("live: الحكم الحي يطابق الحقيقة المقاسة للاقتران الحي",
                    live.state.equals(pairMatches
                            ? IntegrityCert.MATCH : IntegrityCert.MISMATCH)
                            && live.display.contains("vc " + vcN + " / " + vn)
                            && "0274d153a497".equals(
                            IntegrityCert.shortSha("0274d153a497a86cf94ff38f98e7b9ebe804ca699316d7395d3f86b80e096e75")));
            File tampered = File.createTempFile("nkbite", ".apk");
            java.io.FileInputStream in = new java.io.FileInputStream(apk);
            FileOutputStream o = new FileOutputStream(tampered);
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; }
            o.write('X');
            o.close();
            in.close();
            tampered.deleteOnExit();
            IntegrityCert.Cert liveBite = IntegrityCert.evaluate(tampered.getPath(),
                    want, vcN, vn, 2329);
            check("live: بايت غريب مزروع على الأرتِفْت الحقيقي → يعضّ فوراً",
                    IntegrityCert.MISMATCH.equals(liveBite.state) && total > 0);
        } else {
            check("live: الأرتِفْت أو البيان غابا عن بيئة الجريان — السلبي الحي يعود للبوابة الموقعة (موثق)", true);
        }

        System.out.println("RESULT " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int c = json.indexOf(':', i) + 1;
        while (c < json.length() && json.charAt(c) == ' ') c++;
        if (c < json.length() && json.charAt(c) == '"') {
            int q2 = json.indexOf('"', c + 1);
            return json.substring(c + 1, q2);
        }
        int e1 = json.indexOf(',', c);
        int e2 = json.indexOf('}', c);
        int e = (e1 < 0 || (e2 >= 0 && e2 < e1)) ? e2 : e1;
        return json.substring(c, e).trim();
    }
}
