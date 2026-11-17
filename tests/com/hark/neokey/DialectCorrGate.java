package com.hark.neokey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * W87 gate — «يصحح العامية دون تحويلها إلى الفصحى» measured, not claimed.
 * Measures Correction2.dialectFix AS SHIPPED on the plain JVM:
 *
 *   1. protection: every word of the three shipped dialect lexicons is fed
 *      back clean; with the lexicons attached the count of changes that are
 *      NOT a same-length hamza/ة standard re-spelling MUST be 0. The same
 *      run detached is printed (the pre-W87 damage — the gate bites it).
 *   2. dialect repair: each lexicon word (len ≥ 4) gets a deterministic
 *      typo (its last letter doubled); the active lexicon must restore the
 *      EXACT word at a rate ≥ MIN_REPAIR and strictly above the detached
 *      (MSA-only) chain.
 *   3. detach law: null active + null lexicons == the pre-W87 chain
 *      (fix → correction) on every word of the reference set, clean and with
 *      injected errors — any difference is red.
 *   4. latency: p50 < 8 ms per decision (the CorrectionGate budget class).
 *
 * Verdict: DIALECTCORR_GATE_OK / DIALECTCORR_GATE_FAILED <reason> (exit 1).
 */
public final class DialectCorrGate {

    private static final double MIN_REPAIR = 80.0;
    private static final String[] IDS = {"ar_hijazi", "ar_najdi", "ar_gulf"};

    public static void main(String[] args) {
        try {
            run();
        } catch (Gate g) {
            System.out.println("DIALECTCORR_GATE_FAILED " + g.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.out.println("DIALECTCORR_GATE_FAILED internal: " + e);
            System.exit(1);
        }
        System.out.println("DIALECTCORR_GATE_OK");
    }

    private static final class Gate extends RuntimeException { Gate(String m) { super(m); } }
    private static void need(boolean ok, String m) { if (!ok) throw new Gate(m); }

    private static boolean standardOnly(String typed, String c) {
        return c.length() == typed.length() && Correction2.isStandardOnlyDirection(typed, c);
    }

    private static void run() throws Exception {
        File files = new File("build/dialectcorr-files");
        files.mkdirs();
        FakeContext fc = new FakeContext(files);
        Dictionary msa = new Dictionary(fc, "ar");
        Dictionary[] lex = new Dictionary[IDS.length];
        for (int i = 0; i < IDS.length; i++) lex[i] = new Dictionary(fc, IDS[i]);
        long t0 = System.currentTimeMillis();
        boolean all;
        do {
            all = msa.isLoaded();
            for (Dictionary d : lex) all &= d.isLoaded();
            if (!all) Thread.sleep(50);
        } while (!all && System.currentTimeMillis() - t0 < 30000);
        need(all, "dictionaries did not load");
        Set<String> none = new HashSet<>();

        // ---- 1. protection
        int words = 0, damageAttached = 0, damageDetached = 0, normalized = 0;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < IDS.length; i++) {
            for (String w : lexicon(IDS[i])) {
                words++;
                String det = Correction2.dialectFix(w, "", "", msa, null, null, none);
                if (det != null && !det.equals(w) && !standardOnly(w, det)) damageDetached++;
                String att = Correction2.dialectFix(w, "", "", msa, lex[i], lex, none);
                if (att == null || att.equals(w)) continue;
                if (standardOnly(w, att)) { normalized++; continue; }
                damageAttached++;
                if (bad.length() < 300) bad.append(w).append("→").append(att).append(' ');
            }
        }
        System.out.println("DIALECTCORR protect words=" + words + " damage_detached=" + damageDetached
                + " damage_attached=" + damageAttached + " standard_respell=" + normalized);
        need(damageDetached > 0, "protect.no_bite (the detached chain shows no damage — the measure is blind)");
        need(damageAttached == 0, "protect.damage " + damageAttached + ": " + bad);

        // ---- 2. dialect repair
        int cases = 0, okAtt = 0, okDet = 0;
        for (int i = 0; i < IDS.length; i++) {
            for (String w : lexicon(IDS[i])) {
                if (w.length() < 4 || w.indexOf(' ') >= 0) continue;
                String typo = w + w.charAt(w.length() - 1);
                if (msa.knows(typo) || lex[i].knows(typo)) continue;
                cases++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, lex[i], lex, none))) okAtt++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, null, null, none))) okDet++;
            }
        }
        double accAtt = cases == 0 ? 0 : 100.0 * okAtt / cases;
        double accDet = cases == 0 ? 0 : 100.0 * okDet / cases;
        System.out.printf("DIALECTCORR repair cases=%d attached=%.1f%% detached=%.1f%%%n", cases, accAtt, accDet);
        need(cases >= 300, "repair.too_few " + cases);
        need(accAtt >= MIN_REPAIR, "repair.below_floor " + accAtt);
        need(okAtt > okDet, "repair.no_gain");

        // ---- 3. detach law
        List<String> ref = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/testset_ar.txt").toPath(), StandardCharsets.UTF_8))
            for (String w : line.trim().split("\\s+")) if (w.length() >= 2) ref.add(w);
        need(ref.size() >= 200, "detach.ref_too_small " + ref.size());
        int diffs = 0, checked = 0;
        long[] lat = new long[ref.size() * 2];
        int li = 0;
        for (String w : ref) {
            for (String t : new String[]{w, w + w.charAt(w.length() - 1)}) {
                String old = Correction2.fix(t, "", "", msa, none);
                if (old == null) old = msa.correction(t);
                long s = System.nanoTime();
                String now = Correction2.dialectFix(t, "", "", msa, null, null, none);
                lat[li++] = System.nanoTime() - s;
                checked++;
                if (old == null ? now != null : !old.equals(now)) diffs++;
            }
        }
        System.out.println("DIALECTCORR detach checked=" + checked + " diffs=" + diffs);
        need(diffs == 0, "detach.diff " + diffs);

        // ---- 3b. MSA no-regression: the reference errors with every lexicon attached
        // (gulf active — the widest) restore at least as often as detached.
        int msaOkAtt = 0, msaOkDet = 0, msaCases = 0;
        for (String w : ref) {
            if (w.length() < 3) continue;
            String t = w + w.charAt(w.length() - 1);
            if (msa.knows(t)) continue;
            msaCases++;
            if (w.equals(Correction2.dialectFix(t, "", "", msa, lex[2], lex, none))) msaOkAtt++;
            if (w.equals(Correction2.dialectFix(t, "", "", msa, null, null, none))) msaOkDet++;
        }
        System.out.println("DIALECTCORR msa cases=" + msaCases + " attached=" + msaOkAtt + " detached=" + msaOkDet);
        need(msaOkAtt >= msaOkDet, "msa.regression " + msaOkAtt + " < " + msaOkDet);

        // ---- 3c. sentence context (W89). Dialect: every lexicon typo placed after a
        // word of a same-dialect sentence (assets/testset_dialects.txt, seeded, the
        // sentence's own next word after it) — with context it must beat no context.
        // MSA: every reference word's typo inside its own testset_ar.txt sentence with
        // the widest lexicon (gulf) attached restores at least as often as detached.
        java.util.Map<String, List<String[]>> sents = new java.util.HashMap<>();
        for (String line : Files.readAllLines(new File("assets/testset_dialects.txt").toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.indexOf('\t') < 0) continue;
            String[] p = line.split("\t");
            sents.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1].trim().split("\\s+"));
        }
        java.util.Random rnd = new java.util.Random(89);
        int cx = 0, cxOk = 0, cxBare = 0;
        for (int i = 0; i < IDS.length; i++) {
            List<String[]> ss = sents.get(IDS[i].substring(3));
            need(ss != null && !ss.isEmpty(), "context.no_sentences " + IDS[i]);
            for (String w : lexicon(IDS[i])) {
                if (w.length() < 4) continue;
                String typo = w + w.charAt(w.length() - 1);
                if (msa.knows(typo) || lex[i].knows(typo)) continue;
                String[] s = ss.get(rnd.nextInt(ss.size()));
                int j = rnd.nextInt(s.length);
                cx++;
                if (w.equals(Correction2.dialectFix(typo, s[j], j + 1 < s.length ? s[j + 1] : "", msa, lex[i], lex, none))) cxOk++;
                if (w.equals(Correction2.dialectFix(typo, "", "", msa, lex[i], lex, none))) cxBare++;
            }
        }
        int ms = 0, msAtt = 0, msDet = 0;
        for (String line : Files.readAllLines(new File("assets/testset_ar.txt").toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] s = line.trim().split("\\s+");
            for (int j = 0; j < s.length; j++) {
                String w = s[j];
                if (w.length() < 4 || !msa.knows(w)) continue;
                String t = w + w.charAt(w.length() - 1);
                if (msa.knows(t)) continue;
                String p = j > 0 ? s[j - 1] : "", nx = j + 1 < s.length ? s[j + 1] : "";
                ms++;
                if (w.equals(Correction2.dialectFix(t, p, nx, msa, lex[2], lex, none))) msAtt++;
                if (w.equals(Correction2.dialectFix(t, p, nx, msa, null, null, none))) msDet++;
            }
        }
        System.out.printf("DIALECTCORR context dialect=%d/%d (bare %d) msa_sentences=%d/%d (detached %d)%n",
                cxOk, cx, cxBare, msAtt, ms, msDet);
        need(cxOk > cxBare, "context.no_gain " + cxOk + " <= " + cxBare);
        need(msAtt >= msDet, "context.msa_regression " + msAtt + " < " + msDet);

        // ---- 3d. dialect synonyms (W90): every row has ≥2 distinct members, every
        // single-word dialect cell is a word of its dialect lexicon (or MSA), and every
        // member opens a picker that never repeats itself.
        String synText = new String(Files.readAllBytes(new File("assets/dialect_synonyms.txt").toPath()), StandardCharsets.UTF_8);
        Dialect.synLoad(synText);
        int rows = 0, members = 0;
        for (String line : synText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t");
            need(cells.length == 4, "syn.columns " + line);
            rows++;
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (int c = 0; c < 4; c++)
                for (String w : cells[c].split("،")) {
                    w = w.trim();
                    need(w.matches("[\\u0621-\\u064A ]{2,20}"), "syn.word " + w);
                    keys.add(Dialect.normAr(w));
                    members++;
                    if (c > 0 && w.indexOf(' ') < 0)
                        need(lex[c - 1].knows(w) || msa.knows(w), "syn.unknown " + IDS[c - 1] + " " + w);
                    List<String> ch = Dialect.synChips(w);
                    need(ch != null && ch.get(0).startsWith("✕"), "syn.no_picker " + w);
                    for (int q = 1; q < ch.size(); q++)
                        need(!Dialect.normAr(ch.get(q)).equals(Dialect.normAr(w)), "syn.self " + w);
                }
            need(keys.size() >= 2, "syn.trivial_row " + line);
        }
        need(Dialect.synChips("بيت") == null, "syn.false_picker");
        System.out.println("DIALECTCORR synonyms rows=" + rows + " members=" + members);

        // ---- 3e. voice N-best (W91): for every synonym row, an MSA first result and
        // the dialect form as an alternative — the active dialect takes its own form
        // whenever that form is a dialect cue; no active dialect, or no cue, keeps
        // the recognizer's first result byte-identical.
        int vCases = 0, vDialect = 0, vKept = 0;
        for (String line : synText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t");
            String m0 = "طيب " + cells[0].split("،")[0].trim() + " الحين";
            for (int c = 1; c < 4; c++) {
                String dw = cells[c].split("،")[0].trim();
                String alt = "طيب " + dw + " الحين";
                List<String> alts = java.util.Arrays.asList(m0, alt);
                need(Correction2.voicePick(alts, msa, null).equals(m0), "voice.detached " + alt);
                String got = Correction2.voicePick(alts, msa, lex[c - 1]);
                boolean cue = Correction2.dialectCue(dw, msa, lex[c - 1]);
                vCases++;
                if (cue && !m0.equals(alt)) { need(got.equals(alt), "voice.miss " + IDS[c - 1] + " " + alt); vDialect++; }
                else { need(got.equals(m0), "voice.flip " + IDS[c - 1] + " " + alt); vKept++; }
            }
        }
        List<String> plain = java.util.Arrays.asList("أريد أن أذهب إلى البيت", "اريد ان اذهب الى البيت");
        for (Dictionary d : lex) need(Correction2.voicePick(plain, msa, d) == plain.get(0), "voice.msa_flip");
        System.out.println("DIALECTCORR voice cases=" + vCases + " dialect=" + vDialect + " kept=" + vKept);

        // ---- 3f. «محوّل اللهجات» (W94): a sentence turns into each column; unknown
        // words, spaces and punctuation stay byte-identical; the «و» prefix rides along.
        String[][] conv = {
                {"وش تبي الحين؟", "ماذا تريد الآن؟", "ايش تبغى دحين؟", "وش تبي ذلحين؟", "شنو تبي الحين؟"},
                {"فين رايح، ووين اهلك", "أين رايح، وأين اهلك", "فين رايح، وفين اهلك", "وين رايح، ووين اهلك", "وين رايح، ووين اهلك"},
                {"Hello 123 بيت", "Hello 123 بيت", "Hello 123 بيت", "Hello 123 بيت", "Hello 123 بيت"},
        };
        for (String[] t : conv)
            for (int c = 0; c < 4; c++)
                need(Dialect.synConvert(t[0], c).equals(t[c + 1]), "conv " + c + " " + t[0] + " -> " + Dialect.synConvert(t[0], c));
        int convN = 0;
        for (String line : synText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t");
            for (String w : line.split("[\t،]")) {
                w = w.trim();
                if (w.indexOf(' ') >= 0 || !Dialect.synChips(w).get(0).contains(w)) continue;
                for (int c = 0; c < 4; c++) {
                    String got = Dialect.synConvert(w, c);
                    need(!got.isEmpty(), "conv.empty " + w);
                    convN++;
                }
            }
        }
        System.out.println("DIALECTCORR convert sentences=" + conv.length + " members=" + convN);

        // ---- 3g. «الأمثال الشعبية الذكية» (W95): every trigger resolves to its own
        // row's proverb (no trigger claimed twice), pinned samples, non-triggers stay null.
        String provText = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/dialect_proverbs.txt")), "UTF-8");
        java.util.Map<String, String> pm = Dialect.pairs(provText);
        int provN = 0, trigN = 0;
        java.util.Set<String> seenTrig = new java.util.HashSet<>();
        for (String line : provText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t");
            need(f.length == 2 && !f[1].trim().isEmpty(), "prov.shape " + line);
            provN++;
            for (String w : f[0].split("،")) {
                need(seenTrig.add(Dialect.normAr(w.trim())), "prov.dup " + w);
                need(f[1].trim().equals(pm.get(Dialect.normAr(w.trim()))), "prov.map " + w);
                trigN++;
            }
        }
        need("الجار قبل الدار".equals(pm.get(Dialect.normAr("جيراني"))), "prov.pin jar");
        need("الصبر مفتاح الفرج".equals(pm.get(Dialect.normAr("صبرت"))), "prov.pin sabr");
        for (String w : new String[]{"وقت", "كلام", "مال", "شكرا", "الحين", "وش", "ابي"})
            need(pm.get(Dialect.normAr(w)) == null, "prov.common " + w);
        System.out.println("DIALECTCORR proverbs=" + provN + " triggers=" + trigN);

        // ---- 3h. «الكتابة اللطيفة» (W96): each hurtful trigger maps to its gentler
        // phrase, no trigger shared with a proverb, no gentle phrase carries a trigger.
        String softText = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/dialect_soften.txt")), "UTF-8");
        java.util.Map<String, String> sm = Dialect.pairs(softText);
        int softN = 0;
        for (String line : softText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t");
            need(f.length == 2 && !f[1].trim().isEmpty(), "soft.shape " + line);
            for (String w : f[0].split("،")) {
                String k = Dialect.normAr(w.trim());
                need(f[1].trim().equals(sm.get(k)), "soft.map " + w);
                need(pm.get(k) == null, "soft.prov " + w);
                softN++;
            }
        }
        for (String g : sm.values())
            for (String x : g.split(" ")) need(sm.get(Dialect.normAr(x)) == null, "soft.self " + g);
        need("خلنا نهدأ شوي".equals(sm.get(Dialect.normAr("اسكت"))), "soft.pin");
        for (String w : new String[]{"حبيبي", "زين", "الحين", "مجنون"}) need(sm.get(Dialect.normAr(w)) == null, "soft.kind " + w);
        System.out.println("DIALECTCORR soften triggers=" + softN);

        // ---- 3i. «عبارات المناسبات» (W97): each occasion trigger maps to its phrase,
        // none shared with a proverb or a soften row, pinned samples, common words null.
        String occText = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/dialect_occasions.txt")), "UTF-8");
        java.util.Map<String, String> om = Dialect.pairs(occText);
        int occN = 0;
        for (String line : occText.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t");
            need(f.length == 2 && !f[1].trim().isEmpty(), "occ.shape " + line);
            for (String w : f[0].split("،")) {
                String k = Dialect.normAr(w.trim());
                need(f[1].trim().equals(om.get(k)), "occ.map " + w);
                need(pm.get(k) == null && sm.get(k) == null, "occ.clash " + w);
                occN++;
            }
        }
        need("جمعة مباركة".equals(om.get(Dialect.normAr("الجمعة"))), "occ.pin jumaa");
        need(om.get(Dialect.normAr("تخرجت")).startsWith("مبروك التخرج"), "occ.pin grad");
        for (String w : new String[]{"وقت", "الحين", "وش", "ابي", "شكرا"}) need(om.get(Dialect.normAr(w)) == null, "occ.common " + w);
        System.out.println("DIALECTCORR occasions triggers=" + occN);

        // ---- 3j. «إكمال العبارات» + «العربيزي» (W98): every opener maps to a phrase
        // that starts with it, none shared with soften; Arabizi dictionary + rule pins,
        // English texting and plain words stay null.
        java.util.Map<String, String> cm = Dialect.pairs(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/dialect_complete.txt")), "UTF-8"));
        for (java.util.Map.Entry<String, String> e : cm.entrySet()) {
            need(sm.get(e.getKey()) == null, "cmp.soft " + e.getKey());
            need(Dialect.normAr(e.getValue()).replace(" ", "").contains(e.getKey()), "cmp.prefix " + e.getKey());
        }
        need("يعطيك العافية".equals(cm.get(Dialect.normAr("يعطيك"))), "cmp.pin");
        java.util.Map<String, String> zd = Dialect.pairs(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/arabizi.txt")), "UTF-8"));
        String[][] azPins = {{"3ashan", "عشان"}, {"Wallah", "والله"}, {"5alas", "خلاص"}, {"7abibi", "حبيبي"},
                {"3omri", "عمري"}, {"ba3d", "بعد"}, {"sha3r", "شعر"}, {"9adeeqi", "صديقي"}, {"la2", "لا"}};
        for (String[] p : azPins) need(p[1].equals(Dialect.arabizi(p[0], zd)), "az.pin " + p[0]);
        for (String w : new String[]{"b4", "gr8", "2day", "hello", "ok", "k", "fee", "zen", "12", "a1"})
            need(Dialect.arabizi(w, zd) == null, "az.english " + w);
        System.out.println("DIALECTCORR complete openers=" + cm.size() + " arabizi words=" + zd.size());

        // ---- 3k. «إيموجي اللهجة» (W99): every row gives one or two emoji tokens (no
        // letters), no trigger is shadowed by soften / completion / occasions / proverbs, pins hold.
        java.util.Map<String, String> em = Dialect.pairs(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/dialect_emoji.txt")), "UTF-8"));
        for (java.util.Map.Entry<String, String> e : em.entrySet()) {
            String[] es = e.getValue().split(" ");
            need(es.length >= 1 && es.length <= 2, "emj.count " + e.getKey());
            for (String x : es) need(!x.isEmpty() && !Character.isLetter(x.codePointAt(0)), "emj.letter " + e.getKey());
            need(sm.get(e.getKey()) == null && cm.get(e.getKey()) == null && om.get(e.getKey()) == null && pm.get(e.getKey()) == null, "emj.shadow " + e.getKey());
        }
        need("😩 🥱".equals(em.get(Dialect.normAr("طفشان"))), "emj.pin bored");
        need("😘 ❤️".equals(em.get(Dialect.normAr("فديتك"))), "emj.pin love");
        for (String w : new String[]{"ميت", "حر", "بر", "سم", "وقت"}) need(em.get(Dialect.normAr(w)) == null, "emj.ambiguous " + w);
        System.out.println("DIALECTCORR emoji words=" + em.size());

        // ---- 3l. «التاريخ بضغطة» (W100): the date words fire, ordinary words do not,
        // (the chip text itself is TextTools.todayArabic, the W-era 🕌 tool's date).
        for (String w : new String[]{"اليوم", "التاريخ", "تاريخ", "هجري", "الهجري"}) need(Dialect.dateWord(Dialect.normAr(w)), "date.word " + w);
        for (String w : new String[]{"يوم", "وقت", "الحين", "بكرة"}) need(!Dialect.dateWord(Dialect.normAr(w)), "date.plain " + w);
        System.out.println("DIALECTCORR date words=5");

        // ---- 3m. «الأرقام كتابةً» (W101): pinned spellings, the 3–10 plural and the
        // 11+ accusative for thousands/millions, Arabic-Indic digits, and the refusals.
        String[][] nwPins = {{"15", "خمسة عشر"}, {"21", "واحد وعشرون"}, {"100", "مائة"}, {"200", "مئتان"},
                {"1500", "ألف وخمسمائة"}, {"2000", "ألفان"}, {"3000", "ثلاثة آلاف"}, {"12000", "اثنا عشر ألفًا"},
                {"250000", "مئتان وخمسون ألفًا"}, {"1000000", "مليون"}, {"2026", "ألفان وستة وعشرون"},
                {"٣٠٠", "ثلاثمائة"}, {"5000500", "خمسة ملايين وخمسمائة"}, {"100000", "مائة ألف"}};
        for (String[] p : nwPins) need(p[1].equals(Dialect.numWords(p[0])), "num.pin " + p[0] + " → " + Dialect.numWords(p[0]));
        for (String w : new String[]{"7", "05", "1234567890", "12a", ""}) need(Dialect.numWords(w) == null, "num.refuse " + w);
        System.out.println("DIALECTCORR numwords pins=" + nwPins.length);

        // ---- 3n. «المبلغ كتابةً» (W102): the cheque form after a number + ريال.
        String[][] amPins = {{"1500", "ريال", "فقط ألف وخمسمائة ريال سعودي لا غير"}, {"5", "ريالات", "فقط خمسة ريال سعودي لا غير"},
                {"250", "ريالًا", "فقط مئتان وخمسون ريال سعودي لا غير"}, {"١٠٠٠", "ريال", "فقط ألف ريال سعودي لا غير"}};
        for (String[] p : amPins) need(p[2].equals(Dialect.amountWords(p[0], p[1])), "amt.pin " + p[0] + " → " + Dialect.amountWords(p[0], p[1]));
        need(Dialect.amountWords("1500", "يورو") == null && Dialect.amountWords("0", "ريال") == null
                && Dialect.amountWords("0500", "ريال") == null, "amt.refuse");
        System.out.println("DIALECTCORR amount pins=" + amPins.length);
        // ---- 3o. halalas (W103).
        String[][] hlPins = {{"1500.50", "فقط ألف وخمسمائة ريال وخمسون هللة لا غير"}, {"10.5", "فقط عشرة ريال وخمسون هللة لا غير"},
                {"25.05", "فقط خمسة وعشرون ريال وخمس هللات لا غير"}, {"0.75", "فقط خمسة وسبعون هللة لا غير"},
                {"3.01", "فقط ثلاثة ريال وهللة واحدة لا غير"}, {"٢٠٫٢٥", "فقط عشرون ريال وخمسة وعشرون هللة لا غير"}};
        for (String[] p : hlPins) need(p[1].equals(Dialect.amountWords(p[0], "ريال")), "hal.pin " + p[0] + " → " + Dialect.amountWords(p[0], "ريال"));
        for (String w : new String[]{"10.", ".5", "10.505", "0.00", "1.2.3"}) need(Dialect.amountWords(w, "ريال") == null, "hal.refuse " + w);
        System.out.println("DIALECTCORR halala pins=" + hlPins.length);
        // ---- 3p. dollar + dirham (W104).
        String[][] fxPins = {{"100", "دولار", "فقط مائة دولار أمريكي لا غير"}, {"20.25", "دولار", "فقط عشرون دولار وخمسة وعشرون سنت لا غير"},
                {"5.03", "دولار", "فقط خمسة دولار وثلاثة سنتات لا غير"}, {"750", "درهم", "فقط سبعمائة وخمسون درهم إماراتي لا غير"},
                {"12.5", "دراهم", "فقط اثنا عشر درهم وخمسون فلس لا غير"}, {"0.02", "درهم", "فقط فلسان لا غير"}};
        for (String[] p : fxPins) need(p[2].equals(Dialect.amountWords(p[0], p[1])), "fx.pin " + p[0] + p[1] + " → " + Dialect.amountWords(p[0], p[1]));
        System.out.println("DIALECTCORR currency pins=" + fxPins.length);
        // ---- 3q. «المحوّل الهجري» (W105): the date parser (the conversion itself is ICU, device-side).
        need(java.util.Arrays.equals(Dialect.parseDate("25/12/2026"), new int[]{2026, 12, 25, 0}), "date.dmy");
        need(java.util.Arrays.equals(Dialect.parseDate("2026-12-25"), new int[]{2026, 12, 25, 0}), "date.ymd");
        need(java.util.Arrays.equals(Dialect.parseDate("١٤٤٨/٧/٥"), new int[]{1448, 7, 5, 1}), "date.hijri");
        need(java.util.Arrays.equals(Dialect.parseDate("29/2/2028"), new int[]{2028, 2, 29, 0}), "date.leap");
        for (String w : new String[]{"29/2/2027", "31/4/2026", "13/13/2026", "1/1/1700", "31/1/1448", "12/25", "1/2/3", "2026/12", "a/1/2026"})
            need(Dialect.parseDate(w) == null, "date.refuse " + w);
        System.out.println("DIALECTCORR dateparse ok");
        // ---- 3r. «حارس الاحتيال» (W106): what trips the guard, the exact span it deletes, and what never does.
        need(Dialect.chatApp("com.whatsapp") && Dialect.chatApp("org.telegram.messenger") && !Dialect.chatApp("com.alrajhibank.android") && !Dialect.chatApp(null), "guard.apps");
        String[][] gPins = {{"482193", "otp", "482193"}, {"٤٨٢١", "otp", "٤٨٢١"}, {"الرمز 7731 ", "otp", "7731 "}, {"code: 55012", "otp", "55012"},
                {"4111 1111 1111 1111", "card", "4111 1111 1111 1111"}, {"خذ 4111-1111-1111-1111 ", "card", "4111-1111-1111-1111 "}};
        for (String[] p : gPins) {
            String[] g = Dialect.scamSpan(p[0]);
            need(g != null && p[1].equals(g[0]) && p[2].equals(g[1]), "guard.pin " + p[0] + " → " + (g == null ? null : g[0] + "|" + g[1]));
        }
        for (String w : new String[]{"2026", "123", "عندي 5000 ريال", "4111 1111 1111 1112", "اتصل 0551234567", "abc12345", "123456789", ""})
            need(Dialect.scamSpan(w) == null, "guard.refuse " + w);
        System.out.println("DIALECTCORR guard pins=" + gPins.length);
        // ---- 3s. «ميزان الشعر» (W107): famous unvocalized hemistichs land on their meters; prose lands on none.
        String[][] mPins = {{"قفا نبك من ذكرى حبيب ومنزل", "الطويل"}, {"الخيل والليل والبيداء تعرفني", "البسيط"},
                {"والسيف والرمح والقرطاس والقلم", "البسيط"}, {"إذا لم تستطع شيئا فدعه", "الوافر"}, {"وجاوزه إلى ما تستطيع", "الوافر"},
                {"أخي جاوز الظالمون المدى", "المتقارب"}, {"لا يسلم الشرف الرفيع من الأذى", "الكامل"}};
        for (String[] p : mPins) {
            int mi = Dialect.meterOf(p[0]);
            need(mi >= 0 && p[1].equals(Dialect.METER_NAMES[mi]), "meter.pin " + p[0] + " → " + (mi < 0 ? "-" : Dialect.METER_NAMES[mi]));
        }
        need(Dialect.onMeter("وجاوزه إلى ما تستطيع", 2) && !Dialect.onMeter("ابشر بكره ارسل لك الملف ان شاء الله", 2), "meter.ajuz");
        for (String w : new String[]{"السلام عليكم كيف حالك اليوم يا اخي", "ابشر بكره ارسل لك الملف ان شاء الله", "وقل لي كيف حالك يا صديقي اليوم", "مرحبا", ""})
            need(Dialect.meterOf(w) < 0, "meter.refuse " + w);
        System.out.println("DIALECTCORR meter pins=" + mPins.length);
        // ---- 3t. «ذاكرة الوعد» (W108): a promise with a time earns the reminder; a time alone or a promise alone does not.
        String[][] pPins = {{"أبشر بكرة أرسله لك", "d", "1"}, {"ان شاء الله بعد بكره نخلصه", "d", "2"}, {"تم بعد ساعة أكلمك", "m", "60"},
                {"اوعدك الخميس أمرك", "w", "5"}, {"سأرسل التقرير الأسبوع الجاي", "d", "7"}, {"أكيد الليلة أتصل", "n", "20"}, {"بكلمك بعد شوي", "m", "30"}};
        for (String[] p : pPins) {
            String[] w = Dialect.promise(p[0]);
            need(w != null && p[1].equals(w[0]) && p[2].equals(w[1]), "promise.pin " + p[0] + " → " + (w == null ? null : w[0] + w[1]));
        }
        for (String w : new String[]{"بكرة الجو حار", "أبشر يا غالي", "الخميس عطلة", "مرحبا", ""})
            need(Dialect.promise(w) == null, "promise.refuse " + w);
        need(Dialect.promiseMarker("بكرة") && Dialect.promiseMarker("أبشر") && !Dialect.promiseMarker("التقرير"), "promise.marker");
        System.out.println("DIALECTCORR promise pins=" + pPins.length);
        // ---- 3u. «مرآة النبرة» (W109): sharp and cold messages are named, warm and plain ones are not; the rewrite is warmer.
        String[][] tPins = {{"ليش ما رديت علي!!", "harsh"}, {"لازم ترسله فورا", "harsh"}, {"ابي الملف حالا", "harsh"}, {"ok", "cold"}, {"طيب", "cold"}};
        for (String[] p : tPins) need(p[1].equals(Dialect.tone(p[0])), "tone.pin " + p[0] + " → " + Dialect.tone(p[0]));
        for (String w : new String[]{"لازم ترسله فورا الله يعافيك", "كيف حالك اليوم", "ليش؟", "حبيبي ليش ما رديت!!", "طيب بكلمك بعدين", ""})
            need(Dialect.tone(w) == null, "tone.refuse " + w);
        need("لو سمحت، ممكن الملف لما تقدر الله يعافيك 🌹".equals(Dialect.warmer("ابي الملف حالا")), "tone.warmer " + Dialect.warmer("ابي الملف حالا"));
        need("لو سمحت، ليش ما رديت علي! الله يعافيك 🌹".equals(Dialect.warmer("ليش ما رديت علي!!")), "tone.marks " + Dialect.warmer("ليش ما رديت علي!!"));
        need("ok، الله يعطيك العافية 🌹".equals(Dialect.warmer("ok")), "tone.cold");
        need(Dialect.tone(Dialect.warmer("لازم ترسله فورا")) == null, "tone.fixed");
        System.out.println("DIALECTCORR tone pins=" + tPins.length);
        // ---- 3v. «بصمة الكتابة» (W110): an owner's rhythm stays owner, a much faster or much more erratic
        // run turns guest, the owner's own run brings it back, a stranger never teaches the profile.
        java.util.Random rr = new java.util.Random(7);
        Dialect.Rhythm rh = new Dialect.Rhythm();
        for (int k = 0; k < 400; k++) rh.add(Math.round(Math.exp(Math.log(220) + 0.25 * rr.nextGaussian())));
        need(rh.n >= Dialect.Rhythm.MIN_PROFILE && !rh.guest, "rhythm.owner n=" + rh.n);
        boolean flip = false;
        for (int k = 0; k < 200; k++) flip |= rh.add(Math.round(Math.exp(Math.log(220) + 0.25 * rr.nextGaussian())));
        need(!flip, "rhythm.owner.stable");
        double before = rh.n;
        for (int k = 0; k < 40; k++) rh.add(Math.round(Math.exp(Math.log(90) + 0.25 * rr.nextGaussian())));
        need(rh.guest, "rhythm.fast.guest");
        need(rh.n - before < 40, "rhythm.stranger.notlearned");
        for (int k = 0; k < 40; k++) rh.add(Math.round(Math.exp(Math.log(220) + 0.25 * rr.nextGaussian())));
        need(!rh.guest, "rhythm.owner.back");
        Dialect.Rhythm er = Dialect.Rhythm.load(rh.save());
        need(Math.abs(er.mean - rh.mean) < 1e-9 && er.n == rh.n, "rhythm.save");
        for (int k = 0; k < 40; k++) er.add(Math.round(Math.exp(Math.log(220) + 1.0 * rr.nextGaussian())) % 1400 + 41);
        need(er.guest, "rhythm.erratic.guest");
        Dialect.Rhythm young = new Dialect.Rhythm();
        for (int k = 0; k < 100; k++) young.add(90);
        need(!young.guest, "rhythm.young.never");
        need(Dialect.Rhythm.load("garbage").n == 0, "rhythm.load.bad");
        System.out.println("DIALECTCORR rhythm n=" + (int) rh.n);
        // ---- 3w. «حارس الآيبان» (W111): length + mod-97, spaces and Arabic digits, the Saudi bank, no false alarm mid-typing.
        String[] ib = Dialect.iban("حوّل على SA0380000000608010167519");
        need(ib != null && "ok".equals(ib[0]) && "الراجحي".equals(ib[3]) && "SA03 8000 0000 6080 1016 7519".equals(ib[2]), "iban.ok");
        need("SA0380000000608010167519".equals(ib[1]), "iban.span");
        need("ok".equals(Dialect.iban("SA03 8000 0000 6080 1016 7519 ")[0]), "iban.spaced");
        need("ok".equals(Dialect.iban("SA٠٣٨٠٠٠٠٠٠٠٦٠٨٠١٠١٦٧٥١٩")[0]), "iban.arabicdigits");
        need("bad".equals(Dialect.iban("SA0380000000608010167518")[0]), "iban.bad");
        need("bad".equals(Dialect.iban("SA0380000000608010167591")[0]), "iban.swap");
        need("len".equals(Dialect.iban("SA03800000006080101675199")[0]), "iban.long");
        need("ok".equals(Dialect.iban("GB82WEST12345698765432")[0]) && "".equals(Dialect.iban("GB82WEST12345698765432")[3]), "iban.gb");
        need("ok".equals(Dialect.iban("AE070331234567890123456")[0]), "iban.ae");
        for (String w : new String[]{"SA038000000060801", "VISA0380000000608010167519", "XX0380000000608010167519", "مرحبا", ""})
            need(Dialect.iban(w) == null, "iban.refuse " + w);
        System.out.println("DIALECTCORR iban ok");

        // ---- 4. latency (attached path)
        long[] la = new long[cases + words];
        int n = 0;
        for (int i = 0; i < IDS.length; i++)
            for (String w : lexicon(IDS[i])) {
                long s = System.nanoTime();
                Correction2.dialectFix(w + "ه", "", "", msa, lex[i], lex, none);
                if (n < la.length) la[n++] = System.nanoTime() - s;
            }
        long[] cut = java.util.Arrays.copyOf(la, n);
        java.util.Arrays.sort(cut);
        double p50 = cut[n / 2] / 1e6;
        System.out.printf("DIALECTCORR latency p50=%.3fms n=%d%n", p50, n);
        need(p50 < 8.0, "latency.p50 " + p50);
    }

    private static List<String> lexicon(String id) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(new File("assets/" + id + ".txt").toPath(), StandardCharsets.UTF_8)) {
            String w = line.trim();
            int sp = w.indexOf('\t');
            if (sp >= 0) w = w.substring(0, sp);
            if (w.length() >= 2) out.add(w);
        }
        return out;
    }
}
