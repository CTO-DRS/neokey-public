package com.hark.neokey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 1, item B (week 1): the Arabic correction engine v2 — «نموذج أخطاء عربي
 * حقيقي بدل قواعد عامة».
 *
 * What v1 was: ArabicFixes (a fixed map of ~30 joined-phrase mistakes) +
 * Dictionary.correction() (isolated word, first same-normalized-spelling pick).
 * What v2 adds, exactly as the roadmap item B asks:
 *   • جدول الالتباسات — the published confusion table (أ/إ/آ، ة/ه، ي/ى، ؤ/و، ئ/ي)
 *     with per-substitution priors, driving candidate generation;
 *   • ترجيح سياقي مع الجملة الكاملة — the previous word AND the next word vote
 *     through the bigram model, so «إن شاء» beats «أن شاء» and «على كل» beats
 *     «علي كل» (week 2: corpus bigram rows are keyed by the RAW word — the old
 *     norm-keyed store collapsed إن/أن/ان into one row and could never separate them);
 *   • قوائم استثناء — a per-user never-correct set (wired to the existing
 *     nocorrect pref upstream), checked first, no exceptions to it;
 *   • صفر تصحيحات مزعجة — a word that is already a known dictionary spelling is
 *     only re-spelled when a measured rule demands it; the function-word rule
 *     (e) additionally demands REAL context and a huge frequency gap;
 *   • الفاصلة المنقوطة والأرقام العربية — the roadmap's punctuation/numeral
 *     confusion classes: Latin ';' typed on the Arabic layout becomes '؛', and
 *     a digit run attached to Arabic letters becomes Arabic-Indic (and the
 *     reverse for Latin context), guarded by the critical-field policy.
 *
 * Pure decision logic: zero android.* — CorrectionGate measures accuracy, the
 * zero-annoyance rule and latency on the plain JVM, exactly as shipped (and R8
 * strips it like every other pure class).
 */
final class Correction2 {

    private Correction2() { }

    // ------------------------------------------------------------- confusion table

    /** The typed char each row describes (جدول الالتباسات — البند B). */
    private static final char[] TYPED = {'ا', 'ه', 'ي', 'و', 'أ', 'إ', 'آ', 'ة', 'ى', 'ؤ', 'ئ'};
    /** What that typed char may really have meant, most-common first. */
    private static final char[][] ALTS = {
            {'أ', 'إ', 'آ'},   // ا -> أ إ آ  (the classic bare-alef mistake)
            {'ة'},             // ه -> ة
            {'ى', 'ئ'},        // ي -> ى ئ
            {'ؤ'},             // و -> ؤ
            {'ا'},             // أ -> ا  (rare reverse direction)
            {'ا'},             // إ -> ا
            {'ا'},             // آ -> ا
            {'ه'},             // ة -> ه  (rare reverse direction)
            {'ي'},             // ى -> ي
            {'و'},             // ؤ -> و
            {'ي'},             // ئ -> ي
    };
    /** Prior weight per alternative (bigger = more common mistake), same shape as ALTS. */
    private static final int[][] PRIORS = {
            {3, 3, 2},
            {2},
            {2, 1},
            {1},
            {1},
            {1},
            {1},
            {1},
            {2},
            {1},
            {1},
    };

    /** True when the only difference between the two words is confusion-table substitutions. */
    static boolean confusionOf(String typed, String candidate) {
        if (typed == null || candidate == null || typed.length() != candidate.length()) return false;
        for (int i = 0; i < typed.length(); i++) {
            char t = typed.charAt(i), c = candidate.charAt(i);
            if (t == c) continue;
            if (Dictionary.norm(String.valueOf(t)).equals(Dictionary.norm(String.valueOf(c)))) continue;
            return false;
        }
        return true;
    }

    /** Table self-check: rows, alternatives and priors stay aligned (tests + future edits). */
    static boolean tableIntegrity() {
        if (TYPED.length != ALTS.length || TYPED.length != PRIORS.length) return false;
        for (int r = 0; r < TYPED.length; r++)
            if (ALTS[r].length == 0 || ALTS[r].length != PRIORS[r].length) return false;
        return true;
    }

    /** Every alternative collapses to the same normalized key as its typed char. */
    static boolean tableNormConsistency() {
        for (int r = 0; r < TYPED.length; r++)
            for (int j = 0; j < ALTS[r].length; j++)
                if (!Dictionary.norm(String.valueOf(TYPED[r]))
                        .equals(Dictionary.norm(String.valueOf(ALTS[r][j])))) return false;
        return true;
    }

    private static int rowOf(char typed) {
        for (int r = 0; r < TYPED.length; r++) if (TYPED[r] == typed) return r;
        return -1;
    }

    private static int priorOf(char typed, char correct) {
        int row = rowOf(typed);
        if (row < 0) return 0;
        for (int j = 0; j < ALTS[row].length; j++)
            if (ALTS[row][j] == correct) return PRIORS[row][j];
        return 0;
    }

    // -------------------------------------------------------------------- scoring

    private static final int CONTEXT_WEIGHT = 3;   // 1 corpus-rank point ≈ 3 frequency points
    private static final int NEXT_WEIGHT = 3;      // next-word votes count like prev-word votes
    private static final int PRIOR_WEIGHT = 600;   // per prior unit of the substitution
    private static final int MIN_CONFIDENT = 8000; // below this the engine stays silent
    private static final int MIN_CONFIDENT_SAME_NORM = 3000; // same-norm confusion candidates: same word, only the hamza/ة dots differ — near-zero annoyance risk
    private static final int RANK_DELTA = 300;     // (b) dominance: beat the typed spelling by ≥300 corpus positions
    private static final int RANK_SLACK = 2000;    // (c) hamza/ة normalization ignores informal-corpus bias up to this lead
    private static final int FUNC_TOP_RANK = 20;   // (e) function-word rule: candidate is a top-20 word…
    private static final int FUNC_TYPED_RANK = 30; //     …the typed spelling sits at rank ≥30…
    private static final int FUNC_REL = 2;         //     …and is ≥2× less frequent

    /** Decision rules for the known-word tier — shipped defaults are the gate-tuned ones. */
    static boolean RULE_CONTEXT = true;    // (a) the corpus bigram model demands the variant
    static boolean RULE_DOMINANCE = true;  // (b) the variant is ≥RANK_DELTA positions more frequent
    static boolean RULE_STANDARD = true;   // (c) bare→marked re-spelling when the marked form is at least as frequent

    /** Why the last fix() decided what it did — gate attribution, single-threaded. */
    static String lastReason = "";

    // ------------------------------------------------- the W56 root signal

    /**
     * The W56 root-preservation signal (docs/ROOT.md §4): when a verified
     * RootEngine rides (RootEngine.attach), a candidate that KEEPS the typed
     * word's root scores ROOT_WEIGHT above one that breaks it — التصحيح
     * الواعي بالجذر (ROADMAP-V2 W56). Null when detached — the scorer is then
     * BYTE-IDENTICAL to the measured W55 baseline (the detach law; the W56
     * gate and its negative proof bite any other reading). The interface
     * lives here so Correction2 compiles — and every gate compiles it —
     * WITHOUT RootEngine: the engine implements it, never the reverse.
     */
    interface RootSignal { int rootOf(String word); }
    static RootSignal rootSignal;
    /** The documented root-preserving bonus: between the substitution priors
     *  (600/unit) and one bigram context vote (3×corpus-rank) — big enough to
     *  reorder honest ties, never big enough to outvote real context. */
    private static final int ROOT_WEIGHT = 400;

    /** Scores one candidate: frequency + context (previous word) + confusion prior. */
    static int score(String typed, String cand, String prev, Dictionary dict) {
        return score(typed, cand, prev, "", dict);
    }

    /** Full scorer: frequency + prev AND next context + confusion prior (week 2). */
    static int score(String typed, String cand, String prev, String next, Dictionary dict) {
        int sc = dict.wordScore(cand);
        int ctx = dict.bigramScore(prev, cand);
        if (ctx > 0) sc += ctx * CONTEXT_WEIGHT;
        int nxt = dict.bigramScore(cand, next);
        if (nxt > 0) sc += nxt * NEXT_WEIGHT;
        if (confusionOf(typed, cand) && cand.length() > 0) {
            int p = 0;
            for (int i = 0; i < cand.length(); i++)
                if (typed.charAt(i) != cand.charAt(i)) {
                    int one = priorOf(typed.charAt(i), cand.charAt(i));
                    if (one > p) p = one;
                }
            sc += p * PRIOR_WEIGHT;
        }
        // W56 root signal: a detached keyboard scores EXACTLY as the W55
        // baseline (sig == null → this block never runs); an attached one
        // prefers the candidate that keeps the root (docs/ROOT.md §4).
        RootSignal sig = rootSignal;
        if (sig != null) {
            int rt = sig.rootOf(typed);
            if (rt >= 0 && rt == sig.rootOf(cand)) sc += ROOT_WEIGHT;
        }
        return sc;
    }

    // ------------------------------------------------------------------- the fix

    /**
     * The v2 decision for a word just typed (previous-word context only —
     * week-1 signature, kept for callers without a next word).
     */
    static String fix(String typed, String prev, Dictionary dict, Set<String> exceptions) {
        return fix(typed, prev, "", dict, exceptions);
    }

    /**
     * The v2 decision for a word just typed, with the FULL sentence context
     * (week 2): the previous word votes AND the next word votes.
     *
     * @param typed      the raw typed word (never empty)
     * @param prev       the word before it ("" when none)
     * @param next       the word after it ("" when none — sentence end / append typing)
     * @param dict       the language dictionary
     * @param exceptions the user's never-correct set (normalized keys, may be empty)
     * @return the corrected spelling, or null to leave the word alone
     */
    static String fix(String typed, String prev, String next, Dictionary dict, Set<String> exceptions) {
        if (typed == null || typed.length() < 2 || dict == null) return null;
        // exceptions carry raw lowercased words (the nocorrect pref) or normalized keys —
        // both forms are checked so a user exclusion can never be bypassed by normalization.
        if (exceptions != null && (exceptions.contains(Dictionary.norm(typed))
                || exceptions.contains(typed.toLowerCase()))) return null;
        if (prev == null) prev = "";
        if (next == null) next = "";

        // ---- candidates: same-normalized spellings + one confusion substitution
        List<String> cands = new ArrayList<>(dict.normVariants(typed));
        for (int i = 0; i < typed.length(); i++) {
            int row = rowOf(typed.charAt(i));
            if (row < 0) continue;
            for (int j = 0; j < ALTS[row].length; j++) {
                String c = typed.substring(0, i) + ALTS[row][j] + typed.substring(i + 1);
                if (dict.knows(c) && !c.equals(typed) && !cands.contains(c)) cands.add(c);
            }
        }
        if (cands.isEmpty()) { lastReason = "no-candidates"; return null; }

        // ---- rank candidates (prev + next both vote)
        String best = null;
        int bestScore = 0;
        for (String c : cands) {
            int sc = score(typed, c, prev, next, dict);
            if (best == null || sc > bestScore) { best = c; bestScore = sc; }
        }
        if (best == null) { lastReason = "no-best"; return null; }
        boolean sameNormCand = Dictionary.norm(best).equals(Dictionary.norm(typed))
                && confusionOf(typed, best);
        int floor = sameNormCand ? MIN_CONFIDENT_SAME_NORM : MIN_CONFIDENT;
        if (bestScore < floor) { lastReason = "below-confidence"; return null; }

        // ---- the zero-annoyance policy
        if (dict.knows(typed)) {
            // The typed word is itself a valid spelling: re-spell ONLY when the candidate
            // is a confusion-spelling of it AND one of the measured rules demands it.
            if (!confusionOf(typed, best)) { lastReason = "known-word-non-confusion"; return null; }
            int ctxTyped = dict.bigramScore(prev, typed);
            int ctxBest = dict.bigramScore(prev, best);
            int ctxNextTyped = dict.bigramScore(typed, next);
            int ctxNextBest = dict.bigramScore(best, next);
            int rankTyped = dict.wordRank(typed);
            int rankBest = dict.wordRank(best);
            boolean byContext = ctxBest > 0 && ctxBest > ctxTyped && !degradesStandard(typed, best);
            boolean byNextContext = ctxNextBest > 0 && ctxNextBest > ctxNextTyped
                    && !degradesStandard(typed, best);
            boolean byDominance = rankTyped >= 0 && rankTyped != Integer.MAX_VALUE && rankBest >= 0
                    && rankBest <= rankTyped - RANK_DELTA && !degradesStandard(typed, best);
            boolean byStandard = rankBest >= 0 && (rankTyped < 0 || rankTyped == Integer.MAX_VALUE
                    || rankBest <= rankTyped + RANK_SLACK)
                    && isStandardOnlyDirection(typed, best);
            // (e) function-word: a lexically-split pair (علي/على، فى/في) where the
            // candidate is a top-20 function word ≥2× more frequent, REAL context exists,
            // and the context shows ZERO bigram preference for the typed spelling.
            // Without context the rule never fires (a clean «علي» alone stays untouched).
            boolean byFunction = !byContext && !byNextContext && !byDominance && !byStandard
                    && rankBest >= 0 && rankBest <= FUNC_TOP_RANK
                    && rankTyped >= FUNC_TYPED_RANK && rankTyped != Integer.MAX_VALUE
                    && rankBest * FUNC_REL <= rankTyped
                    && (prev.length() > 0 || next.length() > 0)
                    && ctxTyped == 0 && ctxNextTyped == 0
                    && !degradesStandard(typed, best);
            if (byContext && RULE_CONTEXT) { lastReason = "known-word:context"; return best; }
            if (byNextContext && RULE_CONTEXT) { lastReason = "known-word:next-context"; return best; }
            if (byDominance && RULE_DOMINANCE) { lastReason = "known-word:dominance"; return best; }
            if (byStandard && RULE_STANDARD) {
                // Bare→marked re-spelling with NO context evidence: among several
                // standard-direction family members (الان→الآن vs الان→الأن) the priors
                // alone picked the RARER one (الأن rank 551 over الآن rank 38 — measured).
                // With no context, the corpus rank decides: most frequent member wins.
                String topStd = null;
                int topRank = Integer.MAX_VALUE;
                for (String c : cands) {
                    if (!isStandardOnlyDirection(typed, c)) continue;
                    int r = dict.wordRank(c);
                    if (r >= 0 && r < topRank) { topRank = r; topStd = c; }
                }
                if (topStd != null) { lastReason = "known-word:standard"; return topStd; }
                lastReason = "known-word-no-rule";
                return null;
            }
            if (byFunction) { lastReason = "known-word:function"; return best; }
            lastReason = "known-word-no-rule";
            return null;
        }
        lastReason = "unknown-word";
        return best;
    }

    // ------------------------------------------- W87: التصحيح الذي يحترم اللهجة

    /**
     * W87 — «يصحح العامية دون تحويلها إلى الفصحى». The shipped dialect lexicons
     * (ar_hijazi / ar_najdi / ar_gulf) become protected spellings:
     *   (1) a typed word ANY lexicon knows is re-spelled only by the hamza/ة
     *       standard-orthography class (السالفه→السالفة) — never into another
     *       word (measured live before W87: دحين→دين، مليح→مليء، طاري→طارئ،
     *       وشلون→وشؤون، عيل→على);
     *   (2) a word the MSA chain cannot repair is repaired from the ACTIVE
     *       dialect lexicon (ذحين→ذلحين) with no frequency floor — a curated
     *       lexicon has no noisy tail (W88). W88 measured dialect-first against
     *       MSA-first: dialect-first cost 9/203 MSA repairs, so MSA leads and
     *       the dialect fills what it leaves (MSA attached == detached, gated).
     *       W89: when both offer a different word, a dialect cue in the previous
     *       or next word (dialectCue) gives it to the dialect (قاعدد→قاعد, not قاعدة).
     * Null active + null lexicons = byte-identical to the pre-W87 chain
     * (fix → msa.correction) — the detach law, bitten by DialectCorrGate.
     */
    static String dialectFix(String typed, String prev, String next, Dictionary msa,
                             Dictionary active, Dictionary[] lexicons, Set<String> exceptions) {
        if (typed == null || msa == null) return null;
        boolean prot = false;
        if (lexicons != null)
            for (Dictionary x : lexicons)
                if (x != null && x.isLoaded() && x.knows(typed)) { prot = true; break; }
        String c = fix(typed, prev, next, msa, exceptions);
        if (c == null) c = msa.correction(typed);
        if (active != null && active.isLoaded() && !msa.knows(typed)) {
            String d = active.correction(typed, 0);
            // W89: the sentence decides a tie — a dialect cue beside the typo hands the
            // repair to the dialect, unless the MSA candidate is itself a dialect word.
            if (c == null || (d != null && !d.equals(c) && !active.knows(c)
                    && (dialectCue(prev, msa, active) || dialectCue(next, msa, active)))) c = d;
        }
        if (prot && c != null && !(c.length() == typed.length() && isStandardOnlyDirection(typed, c))) {
            lastReason = "dialect-protected";
            return null;
        }
        return c;
    }

    /** W89: a neighbour that speaks the active dialect — in its lexicon, and either a
     *  detection marker or unknown to the MSA dictionary (generic shared words are no cue). */
    static boolean dialectCue(String w, Dictionary msa, Dictionary active) {
        return w != null && !w.isEmpty() && active.knows(w)
                && (!msa.knows(w) || Dialect.markers().containsKey(Dialect.normAr(w)));
    }

    /** W91: the voice N-best pick for the active dialect — the alternative with the
     *  most dialect-cue words wins; a tie keeps the recognizer's own order, so with no
     *  dialect evidence (or no active dialect) the first result rides unchanged. */
    static String voicePick(List<String> alts, Dictionary msa, Dictionary active) {
        String best = alts.get(0);
        if (active == null || msa == null) return best;
        int top = -1;
        for (String a : alts) {
            int n = 0;
            for (String w : Dialect.tokenize(a)) if (dialectCue(w, msa, active)) n++;
            if (n > top) { top = n; best = a; }
        }
        return best;
    }

    /** True when the change STRIPS hamza/ة from the typed word (أ→ا، ة→ه…) —
     *  degrading the standard orthography regardless of what the informal corpus
     *  prefers (أرسل→ارسل is never an acceptable auto-correction). */
    static boolean degradesStandard(String typed, String cand) {
        return isStandardOnlyDirection(cand, typed);
    }

    /** True when every substitution is hamza/taa-marbuta standard-orthography
     *  (ا→أ/إ/آ، ه→ة) — the classes where the marked form is ALWAYS the canonical
     *  spelling. The ي/ى and و/ؤ classes are lexically split (علي/على، في/فى are
     *  all correct words) so they are decided by context or dominance only.
     *  Package-visible: CorrectionGate uses it to classify documented normalizations. */
    static boolean isStandardOnlyDirection(String typed, String cand) {
        for (int i = 0; i < typed.length(); i++) {
            char t = typed.charAt(i), c = cand.charAt(i);
            if (t == c) continue;
            boolean hamzaClass = (t == 'ا' && (c == 'أ' || c == 'إ' || c == 'آ')) || (t == 'ه' && c == 'ة');
            if (!hamzaClass) return false;
        }
        return true;
    }

    // ------------------------------------------------------- previous word helper

    /**
     * The word before the current one, from the same before-cursor text the
     * service already reads — pure text logic so it is JVM-tested.
     *
     * @param before      the text before the cursor (up to ~40 chars is enough)
     * @param currentLen  length of the current word carved out of `before`'s tail
     */
    static String previousWord(String before, int currentLen) {
        if (before == null || currentLen < 0 || currentLen > before.length()) return "";
        int end = before.length() - currentLen;
        int i = end;
        while (i > 0 && !isWordChar(before.charAt(i - 1))) i--;   // skip separator(s)
        if (i == 0) return "";
        int j = i;
        while (j > 0 && isWordChar(before.charAt(j - 1))) j--;
        return before.substring(j, i);
    }

    private static boolean isWordChar(char c) {
        return !(c == ' ' || c == '\n' || c == '\t' || c == '.' || c == ',' || c == '،'
                || c == '!' || c == '?' || c == ':' || c == ';' || c == '؛' || c == '-');
    }

    /**
     * The word after the cursor (week 2 — the other half of «الجملة الكاملة»),
     * from the after-cursor text the service reads: first word token, skipping
     * leading separators. Pure text logic so it is JVM-tested.
     */
    static String nextWord(String after) {
        if (after == null) return "";
        int i = 0, n = after.length();
        while (i < n && !isWordChar(after.charAt(i))) i++;   // skip separator(s)
        if (i >= n) return "";
        int j = i;
        while (j < n && isWordChar(after.charAt(j))) j++;
        return after.substring(i, j);
    }

    // ------------------------------------------ الفاصلة المنقوطة والأرقام العربية
    // Roadmap item B lists them INSIDE the confusion table: punctuation and numerals
    // are the classes users mistype when the layout/script mismatches the text.

    /** Latin semicolon as typed on the shared punctuation key. */
    static final String SEMICOLON_TYPED = ";";
    /** The Arabic semicolon (الفاصلة المنقوطة) it means in Arabic text. */
    static final String SEMICOLON_AR = "؛";
    static final String DIGITS_LATIN = "0123456789";
    static final String DIGITS_ARABIC = "٠١٢٣٤٥٦٧٨٩";

    /**
     * The punctuation the typed char means, or null to keep it.
     * Today's class: Latin ';' → Arabic '؛' on the Arabic layout only — the
     * reverse direction never happens (an Arabic '؛' is already Arabic).
     */
    static String punctFor(String typed, boolean arabicMode) {
        if (typed == null || typed.length() != 1 || !arabicMode) return null;
        return SEMICOLON_TYPED.equals(typed) ? SEMICOLON_AR : null;
    }

    /** True when c is a Latin or Arabic-Indic digit. */
    static boolean isDigitChar(char c) {
        return (c >= '0' && c <= '9') || (c >= '٠' && c <= '٩');
    }

    /**
     * The digit to commit for a Latin-or-Indic digit char (the numeral confusion
     * class): a digit attached to Arabic text (no space — «في2026») is meant
     * Arabic-Indic; a digit attached to Latin text («abc١٢٣») is meant Latin;
     * an unattached digit stays exactly what was typed. The user's number-row
     * pref (rowIndic) wins everywhere when ON.
     *
     * @param digit    the typed digit (either script)
     * @param neighbor the char before it (0 when none)
     * @param rowIndic the user's Arabic-Indic number-row pref
     * @return the digit char to commit
     */
    static char digitFor(char digit, char neighbor, boolean rowIndic) {
        int li = DIGITS_LATIN.indexOf(digit);
        int ai = DIGITS_ARABIC.indexOf(digit);
        if (li < 0 && ai < 0) return digit;
        int idx = li >= 0 ? li : ai;
        if (rowIndic) return DIGITS_ARABIC.charAt(idx);   // the row pref wins everywhere
        boolean nbArabic = isArabicLetter(neighbor);
        boolean nbLatin = Character.isLetter(neighbor) && !nbArabic;
        if (nbArabic) return DIGITS_ARABIC.charAt(idx);   // في2026 → في٢٠٢٦
        if (nbLatin) return DIGITS_LATIN.charAt(idx);     // abc١٢٣ → abc123
        return digit;                                     // unattached: keep what was typed
    }

    static boolean isArabicLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || c == 0x0671;   // hamza..ي + ٱ
    }

    /** Table self-check extension: the punctuation and numeral classes stay aligned. */
    static boolean punctTableIntegrity() {
        if (DIGITS_LATIN.length() != 10 || DIGITS_ARABIC.length() != 10) return false;
        for (int i = 0; i < 10; i++) {
            char la = DIGITS_LATIN.charAt(i), ar = DIGITS_ARABIC.charAt(i);
            if (DIGITS_ARABIC.indexOf(la) >= 0 || DIGITS_LATIN.indexOf(ar) >= 0) return false;
            if (digitFor(la, 'ح', false) != ar) return false;   // attached to Arabic
            if (digitFor(ar, 'x', false) != la) return false;   // attached to Latin
            if (digitFor(la, ' ', true) != ar) return false;    // row pref wins
        }
        return SEMICOLON_AR.equals(punctFor(SEMICOLON_TYPED, true))
                && punctFor(SEMICOLON_TYPED, false) == null
                && punctFor("؟", true) == null;
    }

    // ------------------------------------------------------- الحقول الحرجة policy

    /**
     * «لا تصحيح صامت في الحقول الحرجة» — the roadmap acceptance rule as code.
     * A sensitive field (password / email / URI / no-suggestions) or autocorrect
     * switched off: the engine is never consulted, so what the user typed IS
     * what will be sent. (Incognito is stateless correction — it still corrects,
     * it just never learns or records; see the published privacy text.)
     */
    static boolean silentCorrectionAllowed(boolean sensitive, boolean autocorrect) {
        return !sensitive && autocorrect;
    }
}
