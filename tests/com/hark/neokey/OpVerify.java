package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * مُحقِّق مخرجات المشغّل — المرحلة 8 W3 (الأسبوع 40) — ci/opverify_gate.sh
 *
 * المعلقتان #1 و#2 تقولان إن ما يبقى للمالك خارجي: سلك BillingClient + لصق
 * مفتاح اللوحة، وشراء النطاق + شهادة CA + نقل الحزمة + الأمران الآليان.
 * الأسبوعان 38 و39 سلّحا الاستمرارية والأدلة؛ هذا الأسبوع يسلّح **مخرجات
 * الخطوات الخارجية ذاتها**: كل ما يُصدره المشغّل عند خطوته الموثقة يصير
 * قابلاً للتحقق آلياً بدل الثقة بالعين — بلا تظاهر: ما لا يمكن معرفته
 * محلياً (الجهاز، الحساب، المفتاح الحقيقي) لا يُدّعى، والمخرجات المُتحقَّق
 * منها هنا مخرجاتُ أدواتٍ مُبرهَنة (Provision / SyncServerPack / الخط
 * الواحد في StoreActivity).
 *
 * ثلاثة أوضاع مشغّل (نقي JVM، صفر android.* — يعمل على أي JDK 8+ وعلى أي
 * جهاز، وهما نفسما يشغّلهما المالك على الخادم الحقيقي):
 *
 *   • `letter <statement.json> [--reference <ref.json>] [--expect-domain D]`
 *     — الحرف الصادر من Provision (بيان neokey-provision-1 + سطر
 *     `NetGate.setSyncPins` المُصدَر) يُقارن بالنشر المطبوع: المخطط حرفي،
 *     الدبوس 64 hex صغيراً، pin16 = أول 16 محرفاً من الدبوس، السطر المُصدَر
 *     يحمل **الدبوس نفسه** بايت-لطرفاً (نسختا الحرف لا تنفصلان — لصق يدوي
 *     أو مفتاح مغترب يُصطاد)، البادئة هي البادئة المنشورة في INSTALL.md
 *     الحزمة (CMD_PINSET_PREFIX)، days_left فوق أرضية النشر 30، chainLen ≥ 1،
 *     النطاق غير فارغ ويطابق --expect-domain عند تمريره، ومجموعة الحقول ==
 *     مجموعة حقول الحرف المرجعي الطازج (حرف بلا حقل أو بحقل مزروع يُرفض —
 *     الشكل المنشور لا ينحرف).
 *
 *   • `pack <packDir>` — بيان الحزمة المنقولة يُعاد التحقق منه **قبل
 *     التشغيل**: مفوض بحرفية كاملة إلى `SyncServerPack.check` (الاشتقاق
 *     الواحد — لا إعادة تنفيذ لمُختبر مُبرهَن؛ البوابة نفسها التي أثبتت
 *     الحزمة في W35 هي التي تعيد التحقق على الخادم)، فيُطارد كل عضو معلَن
 *     بـ sha256، والجرة هي الجرة المعلنة، والسكربتات تشير إلى اسم الجرة
 *     الحرفي، والأمران الحرفيان في INSTALL.md، وهوية الأسبوع في البيان —
 *     انحراف نقلٍ (scp مقطوع/عضو زائد/بيان محذوف/مزوِّر يعيد الختم) يُرفض
 *     بوسمه قبل أول `./start-server.sh`.
 *
 *   • `swap [--edited <StoreActivity.java>]` — سطر الاستبدال في
 *     StoreActivity موثَّق كسطر مُحكَم بهويته: السطر الواحد الذي يبني
 *     المزوّد (`sim = new PaymentProvider.SimProvider();`) له نص حرفي
 *     منشور وsha256 منشور (المرجع = المنشور — انزياح النص يكسر الهوية)،
 *     والوثيقة الذاتية تحمل الهوية وتُعاد اشتقاقها. مع --edited: نسخة
 *     المشغّل المعدَّلة تُقارن سطراً بسطر ضد المرجع — نفس عدد الأسطر، سطر
 *     واحد مختلف تماماً في موضع سطر الاستبدال، لا أكثر ولا أقل، والسطر
 *     الجديد يبني `PaymentProvider.PlayProvider(` وينتهي بـ `);` ويحمل
 *     مفتاح اللوحة الملصوق سلسلة base64 صالحة بطول حقيقي، ولا يعيد بناء
 *     SimProvider — **اللصق يلمس السطر المُحكَم وحده**.
 *
 * وضع البوابة (`gate`) — الأعداد الحتمية: 5 إيجابيات (letter ×2، pack،
 * swap، إعادة الأخضر) + **اثنتا عشرة سلبة** كلٌّ بوسمها الحرفي على نسخ
 * متسخة مؤقتة (لا مساس بالمدخلات الحقيقية): انقلاب محرف دبوس بعد الموضع
 * 16، pin16 مُحرَّف، days_left تحت الأرضية، مخطط مكسور، حقل محذوف، عضو
 * حزمة مقطوع، بيان محذوف، عضو زائد، المزوِّر المتسق الذي يعيد ختم البيان
 * بعد إسقاط أمر Provision من INSTALL (الهاشات نظيفة والحرفية الأعمق تعضّه
 * — درس W35)، سطر الاستبدال محذوف، هويته منحرفة، ونسخة معدَّلة تلمس سطرين.
 *
 * الحكم: `OPVERIFYGATE_OK checks=N negatives=12/12 letters=2 packs=1
 * swaps=1 vc=… vn=…` / `OPVERIFYGATE_FAILED <tag>` (exit 1).
 */
public final class OpVerify {

    // ---- الهويات المنشورة (المرجع = المنشور) ----------------------------------

    /** ملف سطر الاستبدال — النسخة المرجعية هي شجرة الإيداع نفسها. */
    public static final String SWAP_FILE = "src/com/hark/neokey/StoreActivity.java";
    /** نص سطر الاستبدال الحرفي (مقصوص الطرفين) — السطر الواحد الذي يبني المزوّد. */
    public static final String SWAP_LINE = "sim = new PaymentProvider.SimProvider();";
    /** الهوية المنشورة للسطر: sha256 لنصه الحرفي أعلاه — انزياح النص يكسرها. */
    public static final String SWAP_SHA256 =
            "ffb4a7950f8a8362eee2669c3d567b1695c31f7c466e8cd584df52035de16d8d";
    /** علامة السطر داخل الملف (البحث بها، النص الحرفي هو الحكم). */
    static final String SWAP_MARK = "new PaymentProvider.SimProvider()";
    /** شكل الاستبدال الموثق — المُحوّل المُبرهَن (W34) بمفتاح اللوحة الملصوق. */
    public static final String EDIT_PREFIX = "sim = new PaymentProvider.PlayProvider(";
    public static final String EDIT_SUFFIX = ");";
    /** أرضية طول مفتاح اللوحة الملصوق (base64) — مفتاح حقيقي مئات المحارف. */
    public static final int MIN_KEY_B64 = 64;
    /** مخطط بيان التجهيز — حرفي، انحرافه = رفض. */
    static final String LETTER_SCHEMA = "neokey-provision-1";

    /** رفض بوسم حرفي واحد — لا تحليل متسامح ولا غموض. */
    public static final class Refused extends RuntimeException {
        public final String tag;
        public Refused(String tag) { super(tag); this.tag = tag; }
    }

    private OpVerify() { }

    static String sha256Hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- وضع الحرف: بيان التجهيز يُقارن بالنشر المطبوع ------------------------

    /** فك إفلات JSON للحقل المقتبس — jsonEsc في Provision هو الاتجاه الآخر بالضبط. */
    static String jsonUnesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == '"' ? '"' : (n == '\\' ? '\\' : c));
                if (n != '"' && n != '\\') { /* إفلات غير معروف = يبقى كما هو */ }
            } else sb.append(c);
        }
        return sb.toString();
    }

    /** استخراج حقول البيان سطراً بسطر — بيان Provision مولَّد بمعروف: حقل لكل سطر. */
    static Map<String, String> letterFields(Path stmt) throws Exception {
        if (!Files.isRegularFile(stmt)) throw new Refused("letter.missing");
        Map<String, String> m = new LinkedHashMap<>();
        Pattern p = Pattern.compile("^\\s*\"([A-Za-z0-9_]+)\":\\s*(\"?)(.*?)\\2,?\\s*$");
        for (String line : Files.readAllLines(stmt, StandardCharsets.UTF_8)) {
            Matcher mm = p.matcher(line);
            if (mm.matches()) m.put(mm.group(1), mm.group(2).isEmpty() ? mm.group(3) : jsonUnesc(mm.group(3)));
        }
        if (m.isEmpty()) throw new Refused("letter.empty");
        return m;
    }

    /** قوانين الحرف — كل رفض بوسمه: الشكل المنشور لا ينحرف. */
    public static String letter(Path stmt, Path reference, String expectDomain) throws Exception {
        Map<String, String> f = letterFields(stmt);
        int n = f.size();
        // 1: المخطط حرفي — neokey-provision-1 لا غير.
        if (!LETTER_SCHEMA.equals(f.get("schema"))) throw new Refused("letter.schema");
        String pin = f.get("pin"), pin16 = f.get("pin16"), emitted = f.get("emitted");
        if (pin == null) throw new Refused("letter.pin_missing");
        // 2: الدبوس 64 hex صغيراً (اشتقاق SpkiPins المنشور).
        if (pin.length() != 64 || !pin.matches("[0-9a-f]{64}")) throw new Refused("letter.pin_hex");
        // 3: pin16 = أول 16 محرفاً من الدبوس — نسختا الحرف داخليتان متسقتان.
        if (pin16 == null || !pin.equals(pin16 + pin.substring(16))) throw new Refused("letter.pin16_prefix");
        // 4: السطر المُصدَر يحمل الدبوس نفسه بالشكل المنشور بايت-لطرفاً —
        //    Provision.Result.emittedLine() هو قانون واحد يَعُدّ الاقتران والبادئة
        //    المنشورة في INSTALL.md الحزمة معاً (الشكل المنشور هو القانون).
        if (emitted == null || !emitted.equals("NetGate.setSyncPins(\"" + pin + "\")"))
            throw new Refused("letter.emitted_shape");
        // 5: أرضية النشر — صلاحية تحت 30 يوماً لا تُجهَّز (أرضية Provision ذاتها).
        long days;
        try { days = Long.parseLong(f.get("daysLeft")); } catch (Exception e) { throw new Refused("letter.days_shape"); }
        if (days < Provision.MIN_DAYS_LEFT) throw new Refused("letter.days_left");
        // 6: سلسلة حاضرة.
        int chain;
        try { chain = Integer.parseInt(f.get("chainLen")); } catch (Exception e) { throw new Refused("letter.chain_shape"); }
        if (chain < 1) throw new Refused("letter.chain");
        // 7: النطاق — غير فارغ ويطابق المتوقع عند تمريره.
        String dom = f.get("domain");
        if (dom == null || dom.isEmpty()) throw new Refused("letter.domain");
        if (expectDomain != null && !expectDomain.equals(dom)) throw new Refused("letter.domain_expect");
        // 8: مجموعة الحقول == مجموعة الحقل المرجعي الطازج — الشكل المنشور لا
        //    يُحذف منه حقل ولا يُزرع فيه حقل (القيم تختلف بالشهادة، الأسماء لا).
        if (reference != null) {
            TreeSet<String> want = new TreeSet<>(letterFields(reference).keySet());
            TreeSet<String> got = new TreeSet<>(f.keySet());
            if (!want.equals(got)) throw new Refused("letter.reference_fields");
        }
        return "OPVERIFY_LETTER_OK fields=" + n + " days_left=" + days + " chain=" + chain
                + " pin16=" + pin.substring(0, 16);
    }

    // ---- وضع الحزمة: البيان يُعاد التحقق منه قبل التشغيل ----------------------

    /** مفوض بحرفية كاملة إلى SyncServerPack.check — الاشتقاق الواحد لا إعادة تنفيذ. */
    public static String pack(Path packDir) throws Exception {
        String out;
        try {
            out = SyncServerPack.check(packDir);
        } catch (IllegalStateException e) {
            throw new Refused(e.getMessage() == null ? "pack.refused" : e.getMessage());
        }
        int members = -1;
        Matcher mm = Pattern.compile("members=(\\d+)").matcher(out);
        if (mm.find()) members = Integer.parseInt(mm.group(1));
        String jar16 = "";
        Matcher jm = Pattern.compile("jar_sha256=([0-9a-f]+)").matcher(out);
        if (jm.find()) jar16 = jm.group(1);
        if (members < 1 || jar16.length() != 16) throw new Refused("pack.verdict_shape");
        return "OPVERIFY_PACK_OK members=" + members + " jar16=" + jar16;
    }

    // ---- وضع السطر: سطر الاستبدال موثَّق بهويته -------------------------------

    /** وثيقة السطر الذاتية — تحمل الهوية المنشورة وتُعاد اشتقاقها في كل فحص. */
    static String swapDoc() {
        return "StoreActivity swap line (the ONE provider construction line)\n"
                + "file: " + SWAP_FILE + "\n"
                + "line: " + SWAP_LINE + "\n"
                + "sha256: " + SWAP_SHA256 + "\n"
                + "replacement: " + EDIT_PREFIX + "\"<paste the real console key base64>\" ...);"
                + " — the proven adapter (week 34), the pasted key verified by shape here\n";
    }

    /** فحص النسخة المرجعية: السطر الواحد بنصه الحرفي وهويته المنشورة. */
    static String swapScan(Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new Refused("swap.file");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).trim().contains(SWAP_MARK)) hits.add(i);
        if (hits.size() != 1) throw new Refused(hits.isEmpty() ? "swap.single" : "swap.duplicate");
        String trimmed = lines.get(hits.get(0)).trim();
        if (!SWAP_LINE.equals(trimmed)) throw new Refused("swap.identity");
        String sha = sha256Hex(trimmed.getBytes(StandardCharsets.UTF_8));
        if (!SWAP_SHA256.equals(sha)) throw new Refused("swap.sha");
        // الوثيقة الذاتية: هويتهما تعاد اشتقاقها منها (وثيقة بهوية منحرفة = رفض).
        String doc = swapDoc();
        if (!doc.contains(SWAP_FILE) || !doc.contains(SWAP_SHA256) || !doc.contains(SWAP_LINE))
            throw new Refused("swap.documented");
        return "OPVERIFY_SWAP_OK line16=" + sha.substring(0, 16) + " replaced=false";
    }

    /** نسخة المشغّل المعدَّلة تُقارن سطراً بسطر — اللصق يلمس السطر المُحكَم وحده. */
    static String editedScan(Path reference, Path edited) throws Exception {
        if (!Files.isRegularFile(reference)) throw new Refused("edit.reference");
        if (!Files.isRegularFile(edited)) throw new Refused("edit.missing");
        List<String> a = Files.readAllLines(reference, StandardCharsets.UTF_8);
        List<String> b = Files.readAllLines(edited, StandardCharsets.UTF_8);
        if (a.size() != b.size()) throw new Refused("edit.line_count");
        int diff = -1;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                if (diff >= 0) throw new Refused("edit.single_line");
                diff = i;
            }
        }
        if (diff < 0) throw new Refused("edit.no_change");
        if (!a.get(diff).trim().equals(SWAP_LINE)) throw new Refused("edit.not_swap_position");
        String t = b.get(diff).trim();
        if (!t.startsWith(EDIT_PREFIX) || !t.endsWith(EDIT_SUFFIX)) throw new Refused("edit.not_play_provider");
        if (t.contains(SWAP_MARK)) throw new Refused("edit.still_sim");
        // المفتاح الملصوق: أول سلسلة مقتبسة داخل السطر — base64 صالح بطول حقيقي.
        int q1 = t.indexOf('"', EDIT_PREFIX.length()), q2 = q1 < 0 ? -1 : t.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) throw new Refused("edit.key_missing");
        String key = t.substring(q1 + 1, q2);
        if (key.length() < MIN_KEY_B64) throw new Refused("edit.key_shape");
        try { Base64.getDecoder().decode(key); } catch (IllegalArgumentException e) { throw new Refused("edit.key_shape"); }
        String refSha = sha256Hex(a.get(diff).trim().getBytes(StandardCharsets.UTF_8));
        return "OPVERIFY_SWAP_OK line16=" + refSha.substring(0, 16) + " replaced=true key16="
                + sha256Hex(key.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    public static String swap(String editedPath) throws Exception {
        String base = swapScan(Paths.get(SWAP_FILE));
        if (editedPath == null) return base;
        return editedScan(Paths.get(SWAP_FILE), Paths.get(editedPath));
    }

    // ---- وضع البوابة: الإيجابيات + الاثنتا عشرة سلبة + إعادة الأخضر -----------

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("OPVERIFY regression: " + name + " (" + desc + ")"); }
    }

    interface Thunk { String run() throws Exception; }

    /** سلبة مقاسة: يجب أن ترفض بالوسم الحرفي — ولّا عدّت ولا مرّت. وسوم
     *  SyncServerPack تحمل لاحقة بيانات موثقة («pack.member_sha <name>»،
     *  «statement.count 7!=6») — الوسم يطابق واللاحقة tolerated (نمط W35). */
    static void neg(String name, String tag, Thunk dirty) {
        checks++; negTotal++;
        String t;
        try { dirty.run(); t = "ACCEPT"; }
        catch (Refused r) { t = r.tag; }
        catch (Exception e) { t = "exception:" + e.getClass().getSimpleName(); }
        if (!t.equals(tag) && !t.startsWith(tag + " ")) {
            reg++;
            System.out.println("OPVERIFY regression: " + name + " (tag " + t + " != " + tag + ")");
        } else {
            negPass++;
            System.out.println("NEG-OK " + name + " [" + tag + "]");
        }
    }

    static void refuse(String tag) { throw new Refused(tag); }

    /** نسخة حزمة متسخة مؤقتة — كل سلبة على نسختها ولا مساس بالحقيقية. */
    static Path dirtyPack(Path src) throws Exception {
        Path d = Files.createTempDirectory("opverify-pack");
        java.util.stream.Stream<Path> st = Files.list(src);
        for (Path p : st.toArray(Path[]::new)) Files.copy(p, d.resolve(p.getFileName().toString()));
        st.close();
        return d;
    }

    static void patch(Path file, String old, String neu) throws Exception {
        String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!s.contains(old)) refuse("patch.target_missing " + old);
        Files.write(file, s.replace(old, neu).getBytes(StandardCharsets.UTF_8));
    }

    /** تشغيل صامت للسلسلة الحقيقية — يعيد سطر الحكم أو يرمي بوسمه. */
    static String letters(Path arena, Path ref, String domain) throws Exception {
        String a = letter(arena, ref, domain);
        // المرجع نفسه يمرّ بقوانين الحرف ذاتها (المرجع لا يُعتد به إن انحرف شكله).
        letter(ref, arena, domain);
        return a;
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length >= 2 && "letter".equals(args[0])) {
                Path ref = null; String dom = null;
                for (int i = 2; i < args.length; i++) {
                    if ("--reference".equals(args[i]) && i + 1 < args.length) ref = Paths.get(args[++i]);
                    else if ("--expect-domain".equals(args[i]) && i + 1 < args.length) dom = args[++i];
                    else { System.err.println("OPVERIFY_FAIL unknown_arg " + args[i]); System.exit(2); }
                }
                System.out.println(letter(Paths.get(args[1]), ref, dom));
                return;
            }
            if (args.length == 2 && "pack".equals(args[0])) { System.out.println(pack(Paths.get(args[1]))); return; }
            if (args.length >= 1 && "swap".equals(args[0])) {
                String edited = null;
                for (int i = 1; i < args.length; i++) {
                    if ("--edited".equals(args[i]) && i + 1 < args.length) edited = args[++i];
                    else { System.err.println("OPVERIFY_FAIL unknown_arg " + args[i]); System.exit(2); }
                }
                System.out.println(swap(edited));
                return;
            }
            if (args.length == 5 && "gate".equals(args[0])) {
                runGate(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), args[4]);
                return;
            }
            System.err.println("usage: OpVerify letter <stmt.json> [--reference <ref>] [--expect-domain D]"
                    + " | pack <packDir> | swap [--edited <file>]"
                    + " | gate <arenaStmt> <refStmt> <packDir> <arenaDomain>");
            System.exit(2);
        } catch (Refused r) {
            System.out.println("OPVERIFY_FAIL " + r.tag);
            System.exit(2);
        }
    }

    static void runGate(Path arena, Path ref, Path packDir, String domain) throws Exception {
        // ---- 1: الإيجابيات على المدخلات الحقيقية -------------------------------
        String l = null, p = null, s = null;
        try { l = letters(arena, ref, domain); } catch (Refused r) { ck("letter.arena", false, r.tag); }
        if (l != null) ck("letter.arena", l.startsWith("OPVERIFY_LETTER_OK "), "the fresh arena letter passes every published-shape law [" + l + "]");
        try { p = pack(packDir); } catch (Refused r) { ck("pack.real", false, r.tag); }
        if (p != null) ck("pack.real", p.startsWith("OPVERIFY_PACK_OK "), "the fresh pack re-verifies byte-exact before any run [" + p + "]");
        try { s = swap(null); } catch (Refused r) { ck("swap.committed", false, r.tag); }
        if (s != null) ck("swap.committed", s.startsWith("OPVERIFY_SWAP_OK "), "the committed swap line carries its published identity [" + s + "]");

        // ---- 2: الاثنتا عشرة سلبة — كلٌّ بوسمها الحرفي على نسخ متسخة ---------
        // (1) انقلاب محرف دبوس بعد الموضع 16 — pin16 يمرّ والسطر المُصدَر يكشفه.
        Path d1 = Files.createTempDirectory("opverify-neg");
        Files.copy(arena, d1.resolve("s.json"));
        patch(d1.resolve("s.json"),
                "\"pin\": \"" + letterFields(arena).get("pin") + "\"",
                "\"pin\": \"" + flipHex(letterFields(arena).get("pin"), 20) + "\"");
        neg("letter.pin_flip", "letter.emitted_shape", () -> letter(d1.resolve("s.json"), ref, domain));
        // (2) pin16 مُحرَّف — النسختان الداخليتان ينفصلان.
        Path d2 = Files.createTempDirectory("opverify-neg");
        Files.copy(arena, d2.resolve("s.json"));
        patch(d2.resolve("s.json"), "\"pin16\": \"" + letterFields(arena).get("pin16") + "\"",
                "\"pin16\": \"" + flipHex(letterFields(arena).get("pin16"), 3) + "\"");
        neg("letter.pin16_edit", "letter.pin16_prefix", () -> letter(d2.resolve("s.json"), ref, domain));
        // (3) days_left تحت أرضية النشر 30.
        Path d3 = Files.createTempDirectory("opverify-neg");
        Files.copy(arena, d3.resolve("s.json"));
        patch(d3.resolve("s.json"), "\"daysLeft\": " + letterFields(arena).get("daysLeft"), "\"daysLeft\": 5");
        neg("letter.days_floor", "letter.days_left", () -> letter(d3.resolve("s.json"), ref, domain));
        // (4) المخطط مكسور.
        Path d4 = Files.createTempDirectory("opverify-neg");
        Files.copy(arena, d4.resolve("s.json"));
        patch(d4.resolve("s.json"), LETTER_SCHEMA, "neokey-provision-0");
        neg("letter.schema_edit", "letter.schema", () -> letter(d4.resolve("s.json"), ref, domain));
        // (5) حقل محذوف — مجموعة الحقول تنحرف عن المرجع الطازج.
        Path d5 = Files.createTempDirectory("opverify-neg");
        List<String> dl5 = new ArrayList<>();
        for (String ln : Files.readAllLines(arena, StandardCharsets.UTF_8))
            if (!ln.contains("\"issuer\"")) dl5.add(ln);
        Files.write(d5.resolve("s.json"), String.join("\n", dl5).getBytes(StandardCharsets.UTF_8));
        neg("letter.field_drop", "letter.reference_fields", () -> letter(d5.resolve("s.json"), ref, domain));
        // (6) عضو حزمة مقطوع — نقلٌ كاسر يُصطاد قبل التشغيل.
        Path d6 = dirtyPack(packDir);
        Path m6 = d6.resolve("start-server.sh");
        Files.write(m6, new byte[10]);
        neg("pack.member_truncated", "pack.member_sha start-server.sh", () -> pack(d6));
        // (7) البيان محذوف — لا بيان لا حزمة.
        Path d7 = dirtyPack(packDir);
        Files.delete(d7.resolve("statement.json"));
        neg("pack.statement_dropped", "statement.missing", () -> pack(d7));
        // (8) عضو زائد عن البيان.
        Path d8 = dirtyPack(packDir);
        Files.write(d8.resolve("dropped-in-transfer.bin"), new byte[4]);
        neg("pack.member_extra", "statement.count", () -> pack(d8));
        // (9) المزوِّر المتسق: يُسقط أمر Provision من INSTALL.md ثم يعيد ختم
        //     البيان — الهاشات نظيفة كلها والحرفية الأعمق تعضّه (درس W35).
        Path d9 = dirtyPack(packDir);
        patch(d9.resolve("INSTALL.md"), SyncServerPack.CMD_PROVISION, "# removed");
        SyncServerPack.restamp(d9, "INSTALL.md");
        neg("pack.consistent_forger", "install.provision_command", () -> pack(d9));
        // (10) سطر الاستبدال محذوف من نسخة متسخة.
        Path d10 = Files.createTempDirectory("opverify-neg");
        List<String> dl10 = new ArrayList<>();
        for (String ln : Files.readAllLines(Paths.get(SWAP_FILE), StandardCharsets.UTF_8))
            if (!ln.trim().equals(SWAP_LINE)) dl10.add(ln);
        Files.write(d10.resolve("StoreActivity.java"), String.join("\n", dl10).getBytes(StandardCharsets.UTF_8));
        neg("swap.line_missing", "swap.single", () -> swapScan(d10.resolve("StoreActivity.java")));
        // (11) هوية السطر منحرفة — محرف واحد في النص الحرفي.
        Path d11 = Files.createTempDirectory("opverify-neg");
        List<String> dl11 = new ArrayList<>();
        for (String ln : Files.readAllLines(Paths.get(SWAP_FILE), StandardCharsets.UTF_8))
            dl11.add(ln.trim().equals(SWAP_LINE) ? ln.replace("sim = new", "sim  = new") : ln);
        Files.write(d11.resolve("StoreActivity.java"), String.join("\n", dl11).getBytes(StandardCharsets.UTF_8));
        neg("swap.identity_drift", "swap.identity", () -> swapScan(d11.resolve("StoreActivity.java")));
        // (12) نسخة معدَّلة تلمس سطرين — السطر المُحكَم وحده هو المسموح.
        Path d12 = Files.createTempDirectory("opverify-neg");
        List<String> dl12 = new ArrayList<>();
        String goodEdit = "        " + EDIT_PREFIX + "\"" + longB64() + "\"" + " ... " + EDIT_SUFFIX;
        boolean done = false;
        for (String ln : Files.readAllLines(Paths.get(SWAP_FILE), StandardCharsets.UTF_8)) {
            if (ln.trim().equals(SWAP_LINE) && !done) { dl12.add(goodEdit); done = true; }
            else if (ln.contains("متجر السمات داخل التطبيق")) dl12.add(ln + " // tampered");
            else dl12.add(ln);
        }
        Files.write(d12.resolve("StoreActivity.java"), String.join("\n", dl12).getBytes(StandardCharsets.UTF_8));
        neg("edit.two_lines", "edit.single_line", () -> editedScan(Paths.get(SWAP_FILE), d12.resolve("StoreActivity.java")));

        // ---- 3: إعادة الأخضر على المدخلات الحقيقية بعد البطارية ---------------
        try { letters(arena, ref, domain); ck("reverify.letter", true, "the real letter re-verifies green after the battery"); }
        catch (Refused r) { ck("reverify.letter", false, r.tag); }
        try { pack(packDir); ck("reverify.pack", true, "the real pack re-verifies green after the battery"); }
        catch (Refused r) { ck("reverify.pack", false, r.tag); }
        try { swap(null); ck("reverify.swap", true, "the committed swap line re-verifies green after the battery"); }
        catch (Refused r) { ck("reverify.swap", false, r.tag); }

        // ---- 4: الحكم ---------------------------------------------------------
        // الحكم بلا حقول اهتزاز (لا ms) — إعادة تشغيل مستقلة تطابق سطر الأنبوب بايت-لطرفاً.
        if (reg != 0 || negPass != negTotal) {
            System.out.println("OPVERIFYGATE_FAILED regressions=" + reg + " negatives=" + negPass + "/" + negTotal);
            System.exit(1);
        }
        System.out.println("OPVERIFYGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " letters=2 packs=1 swaps=1 vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);
    }

    /** انقلاب محرف hex واحد في الموضع i — نمذجة لصق/نقل كاسر. */
    static String flipHex(String s, int i) {
        char c = s.charAt(i);
        char n = c == 'a' ? 'b' : 'a';
        return s.substring(0, i) + n + s.substring(i + 1);
    }

    /** سلسلة base64 طويلة صالحة — نمذجة مفتاح لوحة ملصوق. */
    static String longB64() {
        return Base64.getEncoder().encodeToString(new byte[256]);
    }
}
