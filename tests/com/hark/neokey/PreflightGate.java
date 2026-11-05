package com.hark.neokey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * بوابة الفحص التمهيدي — المرحلة 7 W3 (الأسبوع 36) — ci/preflight_gate.sh
 *
 * المعلق الثالث «حسابات المتاجر + الرفع الفعلي» بقي خارجاً — لكن لا شيء
 * يبرر أن يبحث المشغِّل عن الحد بين «المفحوص» و«الخارجي» في نثر يتناقص
 * صدقاه. هذه البوابة تُدير الأداة الواحدة (Preflight) على أدلة اليوم الطازجة
 * وتجعل **الورقة هي المرجع**:
 *
 *   1. الأدلة الطازجة: البوابات الأربع تُعاد خضراء داخل سكربت البوابة قبل
 *      هذا الصنف (تحقق الحزمة، تدريب المخزن، عقد المزوّدين، إقامة الخادم
 *      وجولة السلك) — الأحكام المخبأة لا تُقبل، وكل دليل يُقاس على هوية
 *      الأسبوع المنشورة («المرجع = المنشور»).
 *   2. الورقة تُصدَر حتمياً (بلا طوابع زمن ولا ms) وتُفحص عضّاً: الجرد كامل،
 *      كل دليل CHECKED == الطازج بايت-لطرفاً، كل أمر OUTSIDE حرفٌ في مصدره،
 *      والهوية من المصدر الواحد (ثوابت المُجمِّع — درس W35).
 *   3. أربع سلبيات كلٌّ بوسمها على نسخ ملطخة (الورقة الحقيقية لا تُلمس):
 *      صف محذوف، دليل معتل، خطوة خارجية مدّعى فحصها هنا، أمر مُختلَق —
 *      ثم إعادة تحقق خضراء للورقة الحقيقية.
 *
 * الحكم: `PREFLIGHTGATE_OK checks=N negatives=4/4 rows=10 checked=5
 * outside=5 sheet=build/release/preflight-sheet.md vc=78 vn=3.1`
 * / `PREFLIGHTGATE_FAILED <reason>` (exit 1).
 */
public final class PreflightGate {

    static int checks, reg, negPass, negTotal;

    static void ck(String name, boolean ok, String desc) {
        checks++;
        if (!ok) { reg++; System.out.println("PREFLIGHT regression: " + name + " (" + desc + ")"); }
    }

    static void neg(String name, String tag, String got) {
        checks++; negTotal++;
        if (got != null && got.contains(tag)) { negPass++; System.out.println("NEG-OK " + name + " [" + tag + "]"); }
        else { reg++; System.out.println("PREFLIGHT regression: " + name + " (got: " + got + ")"); }
    }

    static final Path EV = Paths.get("build/preflight-evidence.env");
    static final Path SHEET = Paths.get("build/release/preflight-sheet.md");

    public static void main(String[] args) throws Exception {
        // ---- 1: الأدلة الطازجة حاضرة ومقاسة على هوية الأسبوع ----------------
        ck("evidence.file", Files.isRegularFile(EV), "the gate script writes the fresh verdicts before this class runs");
        Preflight.Evidence ev;
        try { ev = new Preflight.Evidence(EV); }
        catch (IllegalStateException e) { System.out.println("PREFLIGHTGATE_FAILED " + e.getMessage()); System.exit(1); return; }
        String[][] identities = {
                { Preflight.K_BUNDLE, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN },
                { Preflight.K_KSDRILL, "fp16=" + Preflight.DRILL_FP16 },
                { Preflight.K_BILLING, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN },
                { Preflight.K_DEPLOYSRV, "vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN },
        };
        for (String[] idv : identities) {
            String line = ev.get(idv[0]);
            ck("evidence.fresh." + idv[0], line != null && !line.isEmpty(),
                    "the fresh verdict line for " + idv[0]);
            ck("evidence.identity." + idv[0], line != null && line.contains(idv[1]),
                    "the fresh verdict must carry the published identity (" + idv[1] + ") — got " + line);
        }
        if (reg > 0) { System.out.println("PREFLIGHTGATE_FAILED evidence"); System.exit(1); return; }

        // ---- 2: الورقة تُصدَر من الأدلة الطازجة ------------------------------
        String summary;
        try { summary = Preflight.emit(EV, SHEET); }
        catch (IllegalStateException e) { System.out.println("PREFLIGHTGATE_FAILED sheet.emit " + e.getMessage()); System.exit(1); return; }
        System.out.println("SHEET " + summary);
        ck("sheet.emitted", summary.startsWith("PREFLIGHT_SHEET_OK"), "the handover sheet emits from fresh evidence");

        // ---- 3: الورقة هي المرجع — فحص عضّ -----------------------------------
        String chk;
        try { chk = Preflight.check(EV); }
        catch (IllegalStateException e) { System.out.println("PREFLIGHTGATE_FAILED sheet.check " + e.getMessage()); System.exit(1); return; }
        System.out.println("SHEET " + chk);
        ck("sheet.check_green", chk.startsWith("PREFLIGHT_SHEET_CHECK_OK"), "every row chased against the fresh evidence and the sources");

        // ---- 4: الحتمية — توليد ثانٍ = البايتات نفسها ------------------------
        byte[] before = Files.readAllBytes(SHEET);
        try { Preflight.emit(EV, SHEET); }
        catch (IllegalStateException e) { ck("sheet.deterministic", false, e.getMessage()); }
        ck("sheet.deterministic", java.util.Arrays.equals(before, Files.readAllBytes(SHEET)),
                "emit twice = same bytes (no timestamps, no ms)");

        // ---- 5: الحرفيات الأعمق — أوامر الحزمة في INSTALL المولّد ذاته --------
        // البوابة المعادة طازجة أعادت تجميع الحزمة: INSTALL.md فيها هو ما
        // سيلصقه المشغل — نص الأمرين فيه يُقاس هنا لا في نثر منفصل.
        String install;
        try { install = new String(Files.readAllBytes(Paths.get("build/deploy/pack/INSTALL.md")), StandardCharsets.UTF_8); }
        catch (Exception e) { install = ""; }
        ck("outside.install_literals", install.contains(SyncServerPack.CMD_PROVISION)
                        && install.contains(SyncServerPack.CMD_PINSET_PREFIX),
                "the pack's own INSTALL carries the two automated commands verbatim");

        // ---- 6: أربع سلبيات على نسخ ملطخة — الورقة الحقيقية لا تُلمس ---------
        negSheet("neg.row_dropped", "sheet.row_missing", dirty -> {
            Files.write(dirty, new String(Files.readAllBytes(dirty), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^\\| 3 \\|.*\\n", "").getBytes(StandardCharsets.UTF_8));
        });
        negSheet("neg.evidence_tampered", "sheet.evidence_drift", dirty -> {
            Files.write(dirty, new String(Files.readAllBytes(dirty), StandardCharsets.UTF_8)
                    .replaceFirst("checks=14", "checks=15").getBytes(StandardCharsets.UTF_8));
        });
        negSheet("neg.status_flipped", "sheet.status", dirty -> {
            Files.write(dirty, new String(Files.readAllBytes(dirty), StandardCharsets.UTF_8)
                    .replaceFirst("\\| 10 \\| (`device\\.matrix`[^|]+)\\| OUTSIDE \\|",
                            "| 10 | $1| CHECKED | KSDRILLGATE_OK checks=12 negatives=8/8 fp16=" + Preflight.DRILL_FP16 + " |")
                    .getBytes(StandardCharsets.UTF_8));
        });
        negSheet("neg.command_fabricated", "sheet.command_drift", dirty -> {
            Files.write(dirty, new String(Files.readAllBytes(dirty), StandardCharsets.UTF_8)
                    .replaceFirst("com\\.hark\\.neokey\\.Provision", "com.hark.neokey.ProvisionX")
                    .getBytes(StandardCharsets.UTF_8));
        });
        try { ck("sheet.green_after_negatives", Preflight.check(EV).startsWith("PREFLIGHT_SHEET_CHECK_OK"), "the real sheet still checks green"); }
        catch (IllegalStateException e) { ck("sheet.green_after_negatives", false, e.getMessage()); }

        // ---- الحكم ------------------------------------------------------------
        if (reg > 0) {
            System.out.println("PREFLIGHTGATE_FAILED regressions=" + reg + "/" + checks + " (see PREFLIGHT regression lines above)");
            System.exit(1);
        }
        int rows = 0, ch = 0, out = 0;
        for (Preflight.Step s : Preflight.inventory()) { rows++; if (s.checked) ch++; else out++; }
        System.out.println("PREFLIGHTGATE_OK checks=" + checks + " negatives=" + negPass + "/" + negTotal
                + " rows=" + rows + " checked=" + ch + " outside=" + out + " sheet=" + SHEET
                + " vc=" + SyncServerPack.VC + " vn=" + SyncServerPack.VN);   // مصدر واحد: ثوابت المُجمِّع
    }

    /** سلبي الورقة: نسخة ملطخة يجب أن يرفضها `check` بوسمها — الحقيقية لا تُلمس. */
    interface SheetMut { void m(Path sheet) throws Exception; }

    static void negSheet(String name, String tag, SheetMut mut) throws Exception {
        Path dirty = Paths.get("build/release/preflight-sheet-dirty.md");
        Files.copy(SHEET, dirty, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        mut.m(dirty);
        String got;
        try { Preflight.check(EV, dirty); got = "NO_REFUSAL"; }
        catch (IllegalStateException e) { got = e.getMessage(); }
        neg(name, tag, got);
        Files.deleteIfExists(dirty);
    }
}
