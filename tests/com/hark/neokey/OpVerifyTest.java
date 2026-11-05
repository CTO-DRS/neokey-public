package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * OpVerifyTest — اختبارات وحدة مُحقِّق مخرجات المشغّل (المرحلة 8 W3، الأسبوع 40).
 *
 * مواضع OpVerify النقية تُختبر هنا على حروف وحزم ونسخ معدَّلة تركيبية في
 * مجلدات مؤقتة: قوانين الحرف التسعة، تفويض الحزمة الحرفي إلى
 * SyncServerPack.check (الاشتقاق الواحد)، هوية سطر الاستبدال المنشورة
 * (النص الحرفي == sha256 المنشور == الوثيقة الذاتية)، وقانون التعديل
 * الواحد — إيجابيات على أشكال حقيقية، وسلبيات مصممة تعضّ كلٌّ بوسمها
 * الحرفي (رفض حرس ميت = إخفاق). اختبارات الحزمة تعمل عندما تكون جرة
 * الخادم حاضرة (بناها مسار النشر قبلها — نفس نمط SKIP الموثق في
 * SignedApkTest) وإلا فسطر SKIP صريح لا صمت.
 */
public class OpVerifyTest {

    static int passed = 0, failed = 0, total = 0;

    static void ok(String name, boolean cond, String desc) {
        total++;
        if (cond) { passed++; System.out.println("ok " + name + " — " + desc); }
        else { failed++; System.out.println("FAIL " + name + " — " + desc); }
    }

    static void skip(String name, String why) {
        total++;
        System.out.println("SKIP " + name + " — " + why);
    }

    static String expectTag(OpVerify.Thunk t) {
        try { t.run(); return null; }
        catch (OpVerify.Refused r) { return r.tag; }
        catch (Exception e) { return "exception:" + e.getClass().getSimpleName(); }
    }

    /** الوسم يطابق أو لاحقته البيانية الموثقة (نمط W35: "statement.count 7!=6"). */
    static boolean tagIs(String want, String got) {
        return got != null && (got.equals(want) || got.startsWith(want + " "));
    }

    /** نبّه Thunk من لامدا الاستدعاءات الصامتة (نفس شكل الوحدات الأخيرة). */
    static OpVerify.Thunk th(final OpVerify.Thunk t) { return t; }

    // ---- حرف تركيبي — شكل Provision المنشور نفسه ------------------------------

    static final String PIN = "3f2a9c41e7b85d6019c4f8a2b7e35d149a0c6f83d25e7b419c8a3f60d2e5b741";
    static final String PIN16 = PIN.substring(0, 16);

    static String letterJson(String pin, String pin16, String emitted, String days,
                             String chain, String domain, boolean withIssuer) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schema\": \"neokey-provision-1\",\n");
        sb.append("  \"domain\": \"").append(domain).append("\",\n");
        sb.append("  \"pin\": \"").append(pin).append("\",\n");
        sb.append("  \"pin16\": \"").append(pin16).append("\",\n");
        if (withIssuer) sb.append("  \"issuer\": \"CN=Test CA\",\n");
        sb.append("  \"notBefore\": \"2026-01-01T00:00:00Z\",\n");
        sb.append("  \"notAfter\": \"2027-01-01T00:00:00Z\",\n");
        sb.append("  \"chainLen\": ").append(chain).append(",\n");
        sb.append("  \"selfSigned\": true,\n");
        sb.append("  \"daysLeft\": ").append(days).append(",\n");
        sb.append("  \"emitted\": \"").append(emitted.replace("\"", "\\\"")).append("\",\n");
        sb.append("  \"generatedAt\": \"2026-11-06T00:00:00Z\"\n}");
        return sb.toString();
    }

    static String goodEmitted() { return "NetGate.setSyncPins(\"" + PIN + "\")"; }

    static Path writeLetter(String body) throws Exception {
        Path p = Files.createTempFile("opverify-letter", ".json");
        Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // ---- الإيجابيات --------------------------------------------------------
        Path good = writeLetter(letterJson(PIN, PIN16, goodEmitted(), "200", "2", "sync.example.com", true));
        try {
            String out = OpVerify.letter(good, null, "sync.example.com");
            ok("letter.positive", out.startsWith("OPVERIFY_LETTER_OK ") && out.contains("days_left=200") && out.contains("pin16=" + PIN16),
                    "a well-formed letter passes every published-shape law");
        } catch (Exception e) { ok("letter.positive", false, "refused: " + e.getMessage()); }
        try {
            String out = OpVerify.letter(good, good, null);
            ok("letter.reference_equal", out.startsWith("OPVERIFY_LETTER_OK "),
                    "a letter against an equal-field reference passes the field-set law");
        } catch (Exception e) { ok("letter.reference_equal", false, "refused: " + e.getMessage()); }
        try {
            // القيم تختلف بين الشهادتين والأسماء لا — المرجع ب نطاق/أيام مختلفة يمرّ.
            Path other = writeLetter(letterJson(PIN, PIN16, goodEmitted(), "95", "3", "other.example.com", true));
            String out = OpVerify.letter(good, other, null);
            ok("letter.values_may_differ", out.startsWith("OPVERIFY_LETTER_OK "),
                    "the reference law binds field NAMES not values (two real certs differ)");
        } catch (Exception e) { ok("letter.values_may_differ", false, "refused: " + e.getMessage()); }

        // ---- الحزمة: التفويض الحرفي (تعمل حين تكون جرة الخادم حاضرة) -----------
        Path jar = Paths.get("build/deploy/neokey-sync-server.jar");
        if (Files.isRegularFile(jar)) {
            try {
                Path pd = Files.createTempDirectory("opverify-test-pack");
                SyncServerPack.assemble(pd);
                String out = OpVerify.pack(pd);
                ok("pack.delegates", out.startsWith("OPVERIFY_PACK_OK members=6 "), "the pack re-verifies through SyncServerPack.check itself: " + out);
            } catch (Exception e) { ok("pack.delegates", false, "refused: " + e.getMessage()); }
        } else {
            skip("pack.delegates", "build/deploy/neokey-sync-server.jar absent — the deploysrv path builds it before the gate ever runs");
        }

        // ---- سطر الاستبدال: الهوية المنشورة ------------------------------------
        try {
            String out = OpVerify.swap(null);
            ok("swap.committed", out.startsWith("OPVERIFY_SWAP_OK line16=") && out.contains("replaced=false"),
                    "the committed tree carries the swap line with its published identity");
        } catch (Exception e) { ok("swap.committed", false, "refused: " + e.getMessage()); }
        String rederived = OpVerify.sha256Hex(OpVerify.SWAP_LINE.getBytes(StandardCharsets.UTF_8));
        ok("swap.sha_rederives", OpVerify.SWAP_SHA256.equals(rederived),
                "the published sha256 literal re-derives from the published line literal (no rot)");
        ok("swap.doc_self_consistent", OpVerify.swapDoc().contains(OpVerify.SWAP_SHA256)
                && OpVerify.swapDoc().contains(OpVerify.SWAP_FILE) && OpVerify.swapDoc().contains(OpVerify.SWAP_LINE),
                "the self-document carries the identities it re-derives");
        try {
            // نسخة معدَّلة صحيحة: سطر واحد، المُحوّل المُبرهَن، مفتاح base64 طويل.
            List<String> ed = new ArrayList<>();
            String b64 = Base64.getEncoder().encodeToString(new byte[256]);
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                ed.add(ln.trim().equals(OpVerify.SWAP_LINE)
                        ? "        sim = new PaymentProvider.PlayProvider(\"" + b64 + "\");" : ln);
            Path ef = Files.createTempFile("opverify-edited", ".java");
            Files.write(ef, String.join("\n", ed).getBytes(StandardCharsets.UTF_8));
            String out = OpVerify.swap(ef.toString());
            ok("edit.accepted", out.startsWith("OPVERIFY_SWAP_OK ") && out.contains("replaced=true key16="),
                    "a one-line paste into the gated line with a real key shape verifies");
        } catch (Exception e) { ok("edit.accepted", false, "refused: " + e.getMessage()); }

        // ---- سلبيات الحرف — كلٌّ بوسمها الحرفي ----------------------------------
        ok("neg.letter.missing", "letter.missing".equals(expectTag(th(() -> OpVerify.letter(Paths.get("build/opverify/no-such-letter.json"), null, null)))),
                "a missing letter refuses");
        ok("neg.letter.empty", "letter.empty".equals(expectTag(th(() -> {
            Path p = Files.createTempFile("opverify-empty", ".json");
            Files.write(p, "not a statement\n".getBytes(StandardCharsets.UTF_8));
            return OpVerify.letter(p, null, null);
        }))), "an unparseable letter refuses");
        ok("neg.letter.schema", "letter.schema".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "200", "2", "d", true).replace("neokey-provision-1", "neokey-provision-0")), null, null)))),
                "a broken schema literal refuses");
        ok("neg.letter.pin_hex", "letter.pin_hex".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN.toUpperCase(), PIN16, goodEmitted().replace(PIN, PIN.toUpperCase()), "200", "2", "d", true)), null, null)))),
                "an uppercase pin refuses (the derivation is lowercase hex)");
        ok("neg.letter.pin16_prefix", "letter.pin16_prefix".equals(expectTag(th(() -> {
            String bad16 = (PIN16.charAt(0) == 'a' ? "b" : "a") + PIN16.substring(1);
            return OpVerify.letter(writeLetter(letterJson(PIN, bad16, goodEmitted(), "200", "2", "d", true)), null, null);
        }))), "a pin16 that is not the pin's own prefix refuses");
        ok("neg.letter.emitted_shape", "letter.emitted_shape".equals(expectTag(th(() -> {
            String flipped = OpVerify.flipHex(PIN, 20);
            return OpVerify.letter(writeLetter(letterJson(flipped, flipped.substring(0, 16), goodEmitted(), "200", "2", "d", true)), null, null);
        }))), "a pin flipped past position 16 passes pin16 and is caught by the emitted coupling");
        ok("neg.letter.emitted_shape.2", "letter.emitted_shape".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted().substring(0, goodEmitted().length() - 3) + "9\")", "200", "2", "d", true)), null, null)))),
                "an emitted line carrying a different pin refuses (hand-paste split)");
        ok("neg.letter.days_shape", "letter.days_shape".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "soon", "2", "d", true)), null, null)))),
                "a non-numeric daysLeft refuses");
        ok("neg.letter.days_left", "letter.days_left".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "29", "2", "d", true)), null, null)))),
                "days_left 29 refuses (the deployment floor is 30)");
        ok("neg.letter.chain", "letter.chain".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "200", "0", "d", true)), null, null)))),
                "a chain of zero refuses");
        ok("neg.letter.domain_expect", "letter.domain_expect".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "200", "2", "d", true)), null, "expected.example.com")))),
                "a domain that misses --expect-domain refuses");
        ok("neg.letter.reference_fields", "letter.reference_fields".equals(expectTag(th(() ->
                OpVerify.letter(writeLetter(letterJson(PIN, PIN16, goodEmitted(), "200", "2", "d", false)), good, null)))),
                "a dropped field drifts the field set from the fresh reference");

        // ---- سلبيات الحزمة (حين تكون الجرة حاضرة) -------------------------------
        if (Files.isRegularFile(jar)) {
            ok("neg.pack.member_sha", true && expectTag(th(() -> {
                Path pd = Files.createTempDirectory("opv-neg-pack");
                SyncServerPack.assemble(pd);
                Files.write(pd.resolve("health-server.sh"), new byte[7]);
                return OpVerify.pack(pd);
            })).startsWith("pack.member_sha"), "a truncated member is caught by the delegated sha law");
            ok("neg.pack.statement_missing", "statement.missing".equals(expectTag(th(() -> {
                Path pd = Files.createTempDirectory("opv-neg-pack");
                SyncServerPack.assemble(pd);
                Files.delete(pd.resolve("statement.json"));
                return OpVerify.pack(pd);
            }))), "a dropped statement refuses");
            ok("neg.pack.count", tagIs("statement.count", expectTag(th(() -> {
                Path pd = Files.createTempDirectory("opv-neg-pack");
                SyncServerPack.assemble(pd);
                Files.write(pd.resolve("extra.bin"), new byte[2]);
                return OpVerify.pack(pd);
            }))), "an undeclared member refuses");
            ok("neg.pack.consistent_forger", "install.provision_command".equals(expectTag(th(() -> {
                Path pd = Files.createTempDirectory("opv-neg-pack");
                SyncServerPack.assemble(pd);
                String s = new String(Files.readAllBytes(pd.resolve("INSTALL.md")), StandardCharsets.UTF_8);
                Files.write(pd.resolve("INSTALL.md"), s.replace(SyncServerPack.CMD_PROVISION, "# gone").getBytes(StandardCharsets.UTF_8));
                SyncServerPack.restamp(pd, "INSTALL.md");
                return OpVerify.pack(pd);
            }))), "the consistent forger re-stamps the statement and the deeper literal bites him");
        } else {
            skip("neg.pack.member_sha", "jar absent — the gate proves the pack negatives where the jar lives");
            skip("neg.pack.statement_missing", "jar absent");
            skip("neg.pack.count", "jar absent");
            skip("neg.pack.consistent_forger", "jar absent");
        }

        // ---- سلبيات السطر والتعديل ----------------------------------------------
        ok("neg.swap.single", "swap.single".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-swap", ".java");
            List<String> out = new ArrayList<>();
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                if (!ln.trim().equals(OpVerify.SWAP_LINE)) out.add(ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.swapScan(cp);
        }))), "a copy without the swap line refuses");
        ok("neg.swap.duplicate", "swap.duplicate".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-swap", ".java");
            List<String> out = new ArrayList<>(Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8));
            out.add("        " + OpVerify.SWAP_LINE);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.swapScan(cp);
        }))), "a second provider construction refuses");
        ok("neg.swap.identity", "swap.identity".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-swap", ".java");
            List<String> out = new ArrayList<>();
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE) ? ln.replace("sim = new", "sim  = new") : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.swapScan(cp);
        }))), "one drifted character in the literal line breaks the published identity");
        ok("neg.edit.line_count", "edit.line_count".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>(Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8));
            out.add("// an extra line");
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "an edited copy with a different line count refuses");
        ok("neg.edit.no_change", "edit.no_change".equals(expectTag(th(() ->
                OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), Paths.get(OpVerify.SWAP_FILE))))),
                "an identical copy is not an edit");
        ok("neg.edit.single_line", "edit.single_line".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            String b64 = Base64.getEncoder().encodeToString(new byte[256]);
            boolean done = false;
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8)) {
                if (ln.trim().equals(OpVerify.SWAP_LINE) && !done) { out.add("        sim = new PaymentProvider.PlayProvider(\"" + b64 + "\");"); done = true; }
                else if (ln.contains("متجر السمات")) out.add(ln + " // tampered");
                else out.add(ln);
            }
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a paste that touches a second line refuses");
        ok("neg.edit.not_swap_position", "edit.not_swap_position".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.contains("header(\"متجر السمات\"") ? ln.replace("متجر", "متجـ") : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "an edit to a line that is not the swap line refuses");
        ok("neg.edit.not_play_provider", "edit.not_play_provider".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            String b64 = Base64.getEncoder().encodeToString(new byte[256]);
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE) ? "        sim = new OtherProvider(\"" + b64 + "\");" : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a replacement that does not build the proven adapter refuses");
        ok("neg.edit.still_sim", "edit.still_sim".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            String b64 = Base64.getEncoder().encodeToString(new byte[256]);
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE)
                        ? "        sim = new PaymentProvider.PlayProvider(\"" + b64 + "\"); // " + OpVerify.SWAP_LINE
                        : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a line that keeps a SimProvider construction refuses even beside the adapter");
        ok("neg.edit.key_missing", "edit.key_missing".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE) ? "        sim = new PaymentProvider.PlayProvider(key, prices, src);" : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a replacement without the pasted key literal refuses");
        ok("neg.edit.key_shape", "edit.key_shape".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE) ? "        sim = new PaymentProvider.PlayProvider(\"shortkey\");" : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a pasted key below the real-length floor refuses");
        ok("neg.edit.key_shape.b64", "edit.key_shape".equals(expectTag(th(() -> {
            Path cp = Files.createTempFile("opv-edited", ".java");
            List<String> out = new ArrayList<>();
            String junk = "this is !!not!! base64 at all ........";
            for (String ln : Files.readAllLines(Paths.get(OpVerify.SWAP_FILE), StandardCharsets.UTF_8))
                out.add(ln.trim().equals(OpVerify.SWAP_LINE) ? "        sim = new PaymentProvider.PlayProvider(\"" + junk + "\");" : ln);
            Files.write(cp, String.join("\n", out).getBytes(StandardCharsets.UTF_8));
            return OpVerify.editedScan(Paths.get(OpVerify.SWAP_FILE), cp);
        }))), "a pasted key that is not base64 refuses");

        System.out.println("OpVerifyTest: RESULT " + passed + " passed, " + failed + " failed ("
                + (System.currentTimeMillis() - t0) + "ms)");
        if (failed > 0) System.exit(1);
    }
}
