package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * الفحص التمهيدي للمشغِّل — المرحلة 7 W3 (الأسبوع 36) — ci/preflight_gate.sh
 *
 * المعلقات الموثقة الخمسة تُقال إن بقي منها «الخارج» — لكن لا شيء يمنع
 * سطراً واحداً يقول أين الحد بالضبط. هذه الأداة الواحدة تمشي على شروط النشر
 * كلها بأدلة التشغيل الطازج نفسه (البوابات الأربع تُعاد خضراء داخل مرحلة
 * البوابة — أحكام مخبأة لا تُقبل) وتُصدر **ورقة التسليم**: كل خطوة مشغِّل
 * إما `CHECKED` هنا (بدليل السطر الطازج الحرفي) أو `OUTSIDE` بأمرها الحرفي
 * وخارجيتها الموثقة — بلا تظاهر (مصفوفة الأجهزة وأرقام MAU توثَّق كما هي).
 *
 * العقد (نقي JVM، صفر android.*):
 *   • `emit <evidence> <sheet>` يبني الورقة من ملف الأدلة الطازج — حقول
 *     الاهتزاز (days_left/health_ms) تُسقَط فتكون الورقة حتمية بلا طوابع
 *     زمن (توليد ثانٍ = البايتات نفسها)، وكل دليل CHECKED يُقاس على هوية
 *     الأسبوع المنشورة («المرجع = المنشور») — دليل بهوية أسبوع أخرى = رفض
 *     بوسمه، ودليل غائب = رفض: لا صفّ CHECKED بلا تشغيل طازج.
 *   • `check <evidence> <sheet>` يجعل **الورقة هي المرجع** (نمط W2):
 *     صفوفها تُطارد واحداً واحداً — الجرد كامل بترتيبه، دليل كل CHECKED ==
 *     الملف الطازج بايت-لطرفاً، وكل أمر OUTSIDE حرفٌ موجود في مصدره
 *     (السكربت الموجود، أو نص الأمر في الشجرة) — أي كذب في الورقة يُرفض
 *     بوسم ثابت: صف محذوف، دليل معتل، خطوة خارجية مدّعى فحصها هنا، أمر
 *     مُختلَق، سطر هوية كاذب.
 *
 * الحكم في البوابة: `PREFLIGHTGATE_OK checks=N negatives=4/4 rows=10
 * checked=5 outside=5 sheet=build/release/preflight-sheet.md vc=78 vn=3.1`.
 */
public final class Preflight {

    // هوية الأسبوع من المصدر الواحد (ثوابت المُجمِّع — درس W35: الحكم مشتق
    // من مصدر واحد والمرساة النصية تثبّت المنشور)
    static String vc() { return SyncServerPack.VC; }
    static String vn() { return SyncServerPack.VN; }

    /** بصمة هوية تدريب المخزن المنشورة — مرساة صف ksdrill (لا vc/vn فيه). */
    static final String DRILL_FP16 = "a80e71b9a5d15546";

    // مفاتيح الأدلة الطازجة (البوابة تكتبها من تشغيل هذا اليوم لا من ذاكرة)
    static final String K_BUNDLE = "EVIDENCE_BUNDLE";
    static final String K_KSDRILL = "EVIDENCE_KSDRILL";
    static final String K_BILLING = "EVIDENCE_BILLING";
    static final String K_DEPLOYSRV = "EVIDENCE_DEPLOYSRV";

    /** أوامر الإصدار التي يجب أن تكون في الشجرة (صف CHECKED خامس). */
    static final String[] RELEASE_COMMANDS = {
            "build.sh", "build_aab.sh", "ci/build_signedapk.sh", "ci/release.sh", "ci/build_deploy.sh"
    };
    /** الأمران الموثقان لاستعادة الأسرار (المعلق الرابع — deploy/README.md). */
    static final String CMD_SIGN1 = "ci/build_signedapk.sh";
    static final String CMD_SIGN2 = "com.hark.neokey.SignedApk sign";
    static final String SRC_SIGN_DOC = "deploy/README.md";
    /** مصدر الأمرين الآليين لخادم المزامنة (المعلق الثاني — الحزمة المولّدة). */
    static final String SRC_PACK = "tests/com/hark/neokey/SyncServerPack.java";

    private Preflight() { }

    // ---- جرد الخطوات (المرجع: خطة المرحلة 7 W3 من المعلقات الموثقة) ---------

    static final class Step {
        final String id, title, note;          // note = الخارجية الموثقة (لا تظاهر)
        final String evidenceKey;              // CHECKED بالمفتاح، أو null
        final boolean checked;
        final String[] commands;               // OUTSIDE: أوامر حرفية (فارغة = خارجية كاملة)
        final String[] sources;                // لكل أمر مصدره (FILE موجود / TEXT حرف في النص)
        final boolean[] fileMode;              // true = الأمر مسار ملف موجود، false = نص في مصدر

        Step(String id, String title, String evidenceKey, String note) {
            this(id, title, evidenceKey != null, note, evidenceKey, new String[0], new String[0], new boolean[0]);
        }

        Step(String id, String title, boolean checked, String note, String evidenceKey,
             String[] commands, String[] sources, boolean[] fileMode) {
            this.id = id; this.title = title; this.note = note; this.evidenceKey = evidenceKey;
            this.checked = checked; this.commands = commands; this.sources = sources; this.fileMode = fileMode;
        }
    }

    /** الجرد الكامل — عشرة صفوف: خمسة مفحوصة هنا وخمسة خارجية بأمرها الحرفي. */
    static List<Step> inventory() {
        List<Step> L = new ArrayList<>();
        // ---- المفحوصة هنا (تشغيل طازج داخل مرحلة البوابة) -------------------
        L.add(new Step("bundle.reverify", "حزمة رفع المتجر — إعادة تحقق كاملة", K_BUNDLE,
                "الأرتقف ذاته الذي تثبته بوابة W32"));
        L.add(new Step("keystore.drill", "تدريب استعادة مخزن التوقيع — إعادة تشغيل", K_KSDRILL,
                "قرار W28 يعضّ في كل تشغيل لاحق"));
        L.add(new Step("billing.contracts", "جناح عقد مزوّدي الدفع — إعادة تشغيل", K_BILLING,
                "تعادل العقد على المزوّدين (W34)"));
        L.add(new Step("server.wire", "خادم المزامنة — إقامة نسخة الحزمة + جولة سلك", K_DEPLOYSRV,
                "ما يُشغَّل هو المُختبر ذاته (W35)"));
        L.add(new Step("release.commands", "أوامر الإصدار موجودة في الشجرة", "COMMANDS",
                "أمر البناء، الأمر الأول، حزمة AAB، المانيفست، جرة الخادم"));
        // ---- الخارجية (بأمرها الحرفي وخارجيتها الموثقة — بلا تظاهر) ---------
        L.add(new Step("keystore.restore", "استعادة أسرار التوقيع من خزنة المالك", false,
                "الأسرار لا تترك الخزنة (NK-SEC-001) — الأمران نفسهما يتدرب عليهما CI",
                null,
                new String[]{CMD_SIGN1, CMD_SIGN2},
                new String[]{CMD_SIGN1, SRC_SIGN_DOC},
                new boolean[]{true, false}));
        L.add(new Step("billing.console", "سلك BillingClient + مفتاح اللوحة الحقيقي", false,
                "يحتاج جهازاً ومفتاح اللوحة — الاستبدال نفسه مثبت قبل الحساب (W34)",
                null, new String[0], new String[0], new boolean[0]));
        L.add(new Step("sync.server", "النطاق + شهادة CA + نقل الحزمة + الأمران الآليان", false,
                "الشراء والنقل خارج البيئة — أمر القرار وسطر الدبوس حرفان محروسان، والقوس يُكمل بسطر PROVISION_OK المُصدَر",
                null,
                new String[]{"scp -r pack/ deploy@server:~/",
                             SyncServerPack.CMD_PROVISION,
                             SyncServerPack.CMD_PINSET_PREFIX},
                new String[]{SRC_PACK, SRC_PACK, SRC_PACK},
                new boolean[]{false, false, false}));
        L.add(new Step("store.accounts", "حسابات المتاجر + نقطة الرفع نفسها", false,
                "حساب + كونسول — الحزمة موصلة للرفع منذ W32",
                null, new String[0], new String[0], new boolean[0]));
        L.add(new Step("device.matrix", "مصفوفة الأجهزة الحقيقية + أرقام MAU", false,
                "خارجية كاملة (جهاز + حساب) — توثَّق كما هي بلا تظاهر",
                null, new String[0], new String[0], new boolean[0]));
        return L;
    }

    private static void refuse(String tag) { throw new IllegalStateException(tag); }

    // ---- الأدلة الطازجة ------------------------------------------------------

    static final class Evidence {
        final Path file;
        final java.util.Map<String, String> lines = new java.util.LinkedHashMap<>();
        Evidence(Path file) throws Exception {
            this.file = file;
            if (!Files.isRegularFile(file)) refuse("evidence.file_missing " + file);
            for (String raw : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                String l = raw.trim();
                int eq = l.indexOf('=');
                if (eq <= 0 || l.startsWith("#")) continue;
                lines.put(l.substring(0, eq), l.substring(eq + 1).trim());
            }
        }
        String get(String key) { return lines.get(key); }
    }

    /** حقول الاهتزاز تُسقَط — الورقة حتمية بلا طوابع زمن ولا ms. */
    static String stripJitter(String line) {
        return line.replaceAll(" days_left=[0-9]+", "").replaceAll(" health_ms=[0-9]+", "");
    }

    /** دليل صف CHECKED من الأدلة الطازجة — غائب أو بهوية أسبوع أخرى = رفض. */
    private static String freshEvidence(Evidence ev, Step s) {
        String line = ev.get(s.evidenceKey);
        if (line == null || line.isEmpty()) refuse("evidence.missing " + s.id);
        if (s.id.equals("keystore.drill")) {
            if (!line.contains("fp16=" + DRILL_FP16)) refuse("evidence.identity " + s.id);
        } else if (!line.contains("vc=" + vc() + " vn=" + vn())) {
            refuse("evidence.identity " + s.id);
        }
        return stripJitter(line);
    }

    /** دليل صف release.commands — الأوامر موجودة في الشجرة (فحص محلي حتمي). */
    private static String commandsEvidence() {
        StringBuilder sb = new StringBuilder("commands:");
        for (String c : RELEASE_COMMANDS) {
            if (!Files.isRegularFile(Paths.get(c))) refuse("commands.missing " + c);
            sb.append(' ').append(c);
        }
        return sb.toString();
    }

    // ---- emit: بناء ورقة التسليم من الأدلة الطازجة --------------------------

    /** يبني الورقة ويكتبها — يُعيد سطر الملخص `PREFLIGHT_SHEET_OK …`. */
    public static String emit(Path evidenceFile, Path sheet) throws Exception {
        Evidence ev = new Evidence(evidenceFile);
        List<Step> inv = inventory();
        int checked = 0, outside = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("# ورقة تسليم NeoKey ").append(vn()).append(" (versionCode ").append(vc())
                .append(") — الفحص التمهيدي للمشغّل\n");
        sb.append("identity: vc=").append(vc()).append(" vn=").append(vn()).append('\n');
        sb.append("<!-- مولّدة بـ com.hark.neokey.Preflight من أدلة التشغيل الطازج نفسه — حتمية بلا طوابع زمن -->\n\n");
        sb.append("| # | الخطوة | الحالة | الدليل (تشغيل طازج) أو الأمر الحرفي والخارجية الموثقة |\n");
        sb.append("|---|--------|--------|------------------------------------------------------|\n");
        int n = 0;
        for (Step s : inv) {
            n++;
            String payload;
            if (s.checked) {
                checked++;
                payload = s.evidenceKey.equals("COMMANDS") ? commandsEvidence() : freshEvidence(ev, s);
            } else {
                outside++;
                StringBuilder p = new StringBuilder();
                for (int i = 0; i < s.commands.length; i++) {
                    if (i > 0) p.append(" ثم ");
                    p.append('`').append(s.commands[i]).append('`');
                }
                if (s.commands.length > 0) p.append(" — ");
                p.append(s.note);
                payload = p.toString();
            }
            sb.append("| ").append(n).append(" | `").append(s.id).append("` — ").append(s.title)
                    .append(" | ").append(s.checked ? "CHECKED" : "OUTSIDE").append(" | ").append(payload).append(" |\n");
        }
        sb.append("\nPREFLIGHT_SHEET_OK rows=").append(inv.size()).append(" checked=").append(checked)
                .append(" outside=").append(outside).append(" vc=").append(vc()).append(" vn=").append(vn()).append('\n');
        if (sheet.getParent() != null) Files.createDirectories(sheet.getParent());
        Files.write(sheet, sb.toString().getBytes(StandardCharsets.UTF_8));
        return "PREFLIGHT_SHEET_OK rows=" + inv.size() + " checked=" + checked + " outside=" + outside
                + " vc=" + vc() + " vn=" + vn();
    }

    // ---- check: الورقة هي المرجع — كل صف يُطارد ------------------------------

    /** فحص عضّ للورقة القائمة مقابل الأدلة الطازجة ومصادر الشجرة. */
    public static String check(Path evidenceFile) throws Exception {
        return check(evidenceFile, Paths.get("build/release/preflight-sheet.md"));
    }

    public static String check(Path evidenceFile, Path sheetFile) throws Exception {
        if (!Files.isRegularFile(sheetFile)) refuse("sheet.missing");
        Evidence ev = new Evidence(evidenceFile);
        List<String> rows = new ArrayList<>();
        String identityLine = null, summary = null;
        for (String raw : new String(Files.readAllBytes(sheetFile), StandardCharsets.UTF_8).split("\n")) {
            if (raw.startsWith("| ") && !raw.startsWith("|---") && !raw.contains("| # |")) rows.add(raw);   // صفوف البيانات لا الرأس
            else if (raw.startsWith("identity: ")) identityLine = raw;
            else if (raw.startsWith("PREFLIGHT_SHEET_OK")) summary = raw;
        }
        // 1) سطر الهوية من المصدر الواحد — لا كذب هوية في الورقة
        if (identityLine == null || !identityLine.equals("identity: vc=" + vc() + " vn=" + vn()))
            refuse("identity.mismatch");
        // 2) الجرد كامل بترتيبه — الصفوف تُطارد من الجرد لا من الملف (نمط W2:
        //    صف محذوف يُصطاد من الجرد عند أول عدم تطابق موضعي، لا بعدّاد)
        List<Step> inv = inventory();
        int n = Math.min(rows.size(), inv.size());
        for (int i = 0; i < n; i++) {
            String id = rowId(rows.get(i));
            if (!id.equals(inv.get(i).id)) refuse("sheet.row_missing " + inv.get(i).id + " (found " + id + ")");
        }
        if (rows.size() < inv.size()) refuse("sheet.row_missing " + inv.get(rows.size()).id);
        if (rows.size() > inv.size()) refuse("sheet.row_extra " + rowId(rows.get(inv.size())));
        int checked = 0, outside = 0;
        for (int i = 0; i < inv.size(); i++) {
            Step s = inv.get(i);
            String[] c = rows.get(i).split("\\|", -1);
            // "| n | `id` — title | STATUS | payload |" -> ["", " n ", " id… ", " STATUS ", " payload ", ""]
            if (c.length < 6) refuse("sheet.malformed " + s.id);
            String status = c[3].trim();
            String payload = c[4].trim();
            if (s.checked) {
                checked++;
                if (!status.equals("CHECKED")) refuse("sheet.status " + s.id + " (CHECKED step marked " + status + ")");
                String want = s.evidenceKey.equals("COMMANDS") ? commandsEvidence() : freshEvidence(ev, s);
                // 3) دليل كل CHECKED == الطازج بايت-لطرفاً — لا دليل معتل ولا مستعار
                if (!payload.equals(want)) refuse("sheet.evidence_drift " + s.id);
            } else {
                outside++;
                if (!status.equals("OUTSIDE")) refuse("sheet.status " + s.id + " (OUTSIDE step claimed " + status + ")");
                // 4) الخارجية موثقة بلا تظاهر + أوامرها تطابق الجرد + كل أمر حرفٌ في مصدره
                if (!payload.contains("—")) refuse("outside.undocumented " + s.id);
                for (String cmd : s.commands)
                    if (!payload.contains(cmd)) refuse("sheet.command_drift " + s.id + " (" + cmd + ")");
                for (int k = 0; k < s.commands.length; k++) {
                    String cmd = s.commands[k];
                    boolean ok;
                    if (s.fileMode[k]) {
                        ok = Files.isRegularFile(Paths.get(cmd));
                    } else {
                        String src = new String(Files.readAllBytes(Paths.get(s.sources[k])), StandardCharsets.UTF_8);
                        // الحرفية في المصدر كما هي أو مهرَّبة كما تتطلب Java (\" داخل سلسلة)
                        ok = src.contains(cmd) || src.contains(cmd.replace("\"", "\\\""));
                    }
                    if (!ok) refuse("outside.command_missing " + s.id + " (" + cmd + ")");
                }
            }
        }
        // 5) الملخص يوافق الجرد
        String wantSummary = "PREFLIGHT_SHEET_OK rows=" + inv.size() + " checked=" + checked + " outside=" + outside
                + " vc=" + vc() + " vn=" + vn();
        if (summary == null || !summary.trim().equals(wantSummary)) refuse("sheet.summary");
        return "PREFLIGHT_SHEET_CHECK_OK checked=" + checked + " outside=" + outside;
    }

    /** معرّف الخطوة من خلية الصف (بين الحاظرين الأولين). */
    private static String rowId(String row) {
        String[] c = row.split("\\|", -1);
        if (c.length < 3) return "?";
        String idCell = c[2].trim();
        return idCell.contains("`") ? idCell.substring(idCell.indexOf('`') + 1, idCell.lastIndexOf('`')) : idCell;
    }

    // ---- CLI -------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 3 && "emit".equals(args[0])) { System.out.println(emit(Paths.get(args[1]), Paths.get(args[2]))); return; }
            if (args.length == 3 && "check".equals(args[0])) { System.out.println(check(Paths.get(args[1]), Paths.get(args[2]))); return; }
            if (args.length == 2 && "check".equals(args[0])) { System.out.println(check(Paths.get(args[1]))); return; }
            System.err.println("usage: Preflight emit <evidence> <sheet> | check <evidence> [sheet]");
            System.exit(2);
        } catch (IllegalStateException e) {
            System.out.println("PREFLIGHT_SHEET_FAIL " + e.getMessage());
            System.exit(2);
        }
    }
}
