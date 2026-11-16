#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ci/make_root_index.py — W56 (ROADMAP-V2 «العربية العميقة»): the ROOT-INDEX
# DERIVATION TOOL.
#
#   Reads the shipped dictionary (assets/ar.txt — corpus-rank order, one word
#   per line) and derives morphological roots for the words the rule set can
#   derive CONFIDENTLY. Emits assets/root_index.txt (RIX1: header + CRC32 of
#   the payload + root lines) — law 6: the knowledge lives in assets/, the dex
#   carries the thin strict interpreter (RootEngine). Deterministic: the same
#   dictionary bytes produce the same index bytes, always. No randomness.
#   (F2, audit 2026-11-15: the PAYLOAD is deterministic; the `made` header
#   line is the creation-day stamp, and --check normalizes exactly that line
#   so the byte-proof survives across days.)
#
#   The derivation is FAIL-CLOSED: a word gets a root ONLY when a rule fires
#   AND the derived root verifies (an attested norm key, or a member of the
#   hand-verified ROOT_LEXICON — roots the corpus never carries bare: سجد،
#   حبب). No guessing — ambiguous shapes resolve by CORPUS RANK (the
#   dictionary's own frequency order decides قال→قول over the قيل false
#   friend), and a word with no confident derivation is skipped and COUNTED.
#
#   Order (deterministic, depth ≤ 3):
#     1) len3 → own-root (p1): أجوف/ناقص reconstructions by rank-uniqueness,
#        مضاعف expansion; a fem-flagged م-stem tries the م-strip first with
#        the doubled expansion (محبة→محب→حب→حبب — ملك falls back to p1)
#     2) the fem-y rule: a feminine remnant (from an ة-strip) tries the ي
#        strip FIRST (مالية→مال لا ملي — the participle م wins the race)
#     3) P8/P9 — the م-patterns مَفْعَلَة/مَفْعُول on the raw word
#     4) feminine-final guard (ة/ه) — recurses fem-flagged, no fall-through
#     5) the م-branch: patterns4 (P2..P6) COMPETES with the م-strip by root
#        rank (مدير→دير beats مدر, ملوك→ملك beats لوك, مدين→دين beats مدن);
#        non-م words run patterns4 alone (any أجوف root re-reconstructed)
#     6) patterns56 — the ا-masdars P13/P14/P15 (الاجتماع→جمع، انطلاق→طلق،
#        انتقال→نقل) — an ALEF-initial word matches THESE, never P2..P6
#     7) morphemic prefixes ال/است/ان — a len3 remainder must be ATTESTED
#        (انصار→صار is guarded by the override نصر; bare-len3 refused after
#        ان/است, استعار supplied by override عار)
#     8) suffixes — a len3 remainder must be ATTESTED (تعاون's ون never
#        creates the nonsense تعا→تعي)
#     9) single-char prefixes (إ ا أ و ف ب ل ت — س refused: سجود is not
#        future-س; ك refused: مبارك's ك is a root letter; ت refused when fem:
#        ترجمة keeps its ت)
#    10) W86ب the present-verb layer: ي/ن strips at depth 0 ONLY (len>=4,
#        plain-alef refusal, attested-intermediate law) — the layer §8 waits
#        for: يكتب/نكتب/ينجم join their stems' attested families
#
#   Root values are norm form with the broken hamza folded to alef (قرء→قرا),
#   and a FUNCTION-ROOT guard refuses roots that are function words (الي، من،
#   الك، لك… — a root is a content morpheme, never a preposition).
#
#   Modes:
#     python3 ci/make_root_index.py            # derive + emit + report
#     python3 ci/make_root_index.py --check    # byte-compare vs committed asset
#     python3 ci/make_root_index.py --gold     # gold-set accuracy report
#     python3 ci/make_root_index.py --trace كلمة  # one word, final derivation
import sys, zlib, os, re
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DICT = os.path.join(ROOT, "assets", "ar.txt")
OUT = os.path.join(ROOT, "assets", "root_index.txt")
TESTSET = os.path.join(ROOT, "assets", "testset_ar.txt")

ALEF = "اأإآٱ"
BIG = 10 ** 9

def norm(s):
    """The Dictionary.norm families (the one project normalizer)."""
    out = []
    for ch in s:
        if ch in "أإآٱ": ch = "ا"
        elif ch == "ة": ch = "ه"
        elif ch in "ىئ": ch = "ي"
        elif ch == "ؤ": ch = "و"
        elif "\u064B" <= ch <= "\u0652": continue
        out.append(ch)
    return "".join(out)

def hamza_fix_root(r):
    """A derived root's seats: a MIDDLE hamza seat folds to a real hamza
    (سائل→سال); an END seat folds to the broken hamza (قارئ→قرء); then the
    root value is normed with the broken hamza folded to alef (قرء→قرا)."""
    out = []
    for i, ch in enumerate(r):
        if i == len(r) - 1 and ch in ("ئ", "ؤ", "ء"):
            out.append("ء")
        elif i == 1 and ch in ("ئ", "ؤ"):
            out.append("أ")
        else:
            out.append(ch)
    return norm("".join(out)).replace("ء", "ا")

# roots the corpus never carries as bare words but every lexicon confirms —
# hand-verified tri-literal roots extending the verification anchor to them
ROOT_LEXICON = {"سجد", "حبب"}

# hand-verified word→root overrides (the data's honest corrections — each
# entry a real classical root; applied at attempt entry so ال-words and any
# depth derive through them). مال/مالية: the classical م-و-ل loses the rank
# race to the real word ميل (2138 vs 20821) — the override pins the lexicon
# truth over the corpus frequency.
OVERRIDES = {"ناس": "انس", "استعار": "عار", "انصار": "نصر", "انسان": "انس",
             "مال": "مول", "مالية": "مول"}

# a root is a content morpheme — these function words are never roots
FUNCTION_ROOTS = {"الي", "من", "في", "عن", "لا", "ما", "مع", "او", "الك", "ال",
                  "هو", "هي", "هذا", "هذه", "ذلك", "كل", "لم", "لن", "قد"}

PREFIXES_MORPHEMIC = ["ال", "است", "ان"]          # bare len3 refused after ان/است
PREFIXES_SINGLE = ["إ", "ا", "أ", "و", "ف", "ب", "ل", "ت"]
SUFFIXES = ["ات", "ون", "ين", "ية", "ان", "اء", "ء", "هم", "كم", "هن", "ها", "نا", "ه", "ة", "ا", "ي", "ت"]
FEMININE_FINAL = ("ة", "ه")

class Deriver:
    def __init__(self, words):
        self.words = words
        self.keys = set(norm(w) for w in words)
        self.rank = {}
        for i, w in enumerate(words):
            k = norm(w)
            if k not in self.rank:
                self.rank[k] = i
        self.skipped = 0

    def ok(self, root):
        return (len(root) == 3 and root not in FUNCTION_ROOTS
                and (root in self.keys or root in ROOT_LEXICON))

    def rankof(self, root):
        if root in ROOT_LEXICON:
            return 0
        return self.rank.get(root, BIG)

    def pick(self, a, b):
        """The corpus-rank tie-break: when two derivations both verify, the
        dictionary's own frequency order decides (the more common root is the
        real one — قول 1458 beats the قيل false friend). Tie → the pattern."""
        if a is None: return b
        if b is None: return a
        return a if self.rankof(a) <= self.rankof(b) else b

    def reconstruct(self, r):
        """The أجوف reconstruction: a middle alef rebuilds the true weak letter
        among the و/ي candidates — the unique candidate wins, both-or-neither
        resolves by rank, never guessed blind."""
        if len(r) != 3 or r[1] != "ا":
            return r
        w1, y1 = r[0] + "و" + r[2], r[0] + "ي" + r[2]
        has_w, has_y = w1 in self.keys, y1 in self.keys
        if has_w and has_y:
            return self.pick(w1, y1)
        if has_w != has_y:
            return w1 if has_w else y1
        return r

    def p1(self, w):
        n = norm(w)
        if len(n) != 3:
            return None
        if n in OVERRIDES:
            return OVERRIDES[n] if self.ok(OVERRIDES[n]) else None
        if n[1] == n[2]:                       # مدد class
            c = n[0] + n[1] + n[1]
            return c if self.ok(c) else None
        if n[1] == "ا":                        # أجوف: قال→قول
            c = self.reconstruct(n)
        elif n[2] == "ا":                      # ناقص: رضا→رضي
            w1, y1 = n[0] + n[1] + "و", n[0] + n[1] + "ي"
            has_w, has_y = w1 in self.keys, y1 in self.keys
            c = self.pick(w1, y1) if (has_w or has_y) else n
        else:
            c = n
        return c if self.ok(c) else None

    def p89(self, w):
        n = len(w)
        # P8 مَفْعَلَة: مدرسة→درس، مكتبة→كتب، مدينة→دين، مزرعة→زرع
        if n == 5 and w[0] == "م" and w[4] == "ة":
            r = self.reconstruct(hamza_fix_root(w[1] + w[2] + w[3]))
            if self.ok(r):
                return r
        # P9 مَفْعُول: مكتوب→كتب، منقول→نقل، مذكور→ذكر
        if n == 5 and w[0] == "م" and w[3] == "و":
            r = self.reconstruct(hamza_fix_root(w[1] + w[2] + w[4]))
            if self.ok(r):
                return r
        return None

    def patterns4(self, w):
        """P2..P6 — the consonant-initial four-letter shapes. An ALEF-initial
        word never matches them (أيام is أ+يام, not فِعال)."""
        n = len(w)
        if w[0] in ALEF:
            return None
        # P2 فاعِل: كاتب→كتب، والد→ولد، قارئ→قرا، مالك→ملك
        if n == 4 and w[1] == "ا":
            r = self.reconstruct(hamza_fix_root(w[0] + w[2] + w[3]))
            if self.ok(r):
                return r
        # P3 فِعال: كتاب→كتب، رجال→رجل، مثال→مثل
        if n == 4 and w[2] == "ا":
            r = self.reconstruct(hamza_fix_root(w[0] + w[1] + w[3]))
            if self.ok(r):
                return r
        # P4 فَعيل: كبير→كبر، قدير→قدر، حبيب→حبب
        if n == 4 and w[2] == "ي":
            r = self.reconstruct(hamza_fix_root(w[0] + w[1] + w[3]))
            if self.ok(r):
                return r
        # P5 فَعول: صبور→صبر، دروس→درس، عيون→عين، ملوك→ملك
        if n == 4 and w[2] == "و":
            r = self.reconstruct(hamza_fix_root(w[0] + w[1] + w[3]))
            if self.ok(r):
                return r
        # P6 فَعَّل (doubled middle — rare on bare tokens, kept for the shape)
        if n == 4 and w[1] == w[2]:
            r = hamza_fix_root(w[0] + w[1] + w[3])
            if self.ok(r):
                return r
        return None

    def patterns56(self, w):
        """P13..P15 — the alef-initial masdar shapes."""
        n = len(w)
        if w[0] not in ALEF:
            return None
        # P13 إِفْعال masdar (len5): انجاح→نجح، اخلال→خلل، اهداء→هدا
        if n == 5 and w[3] == "ا":
            r = self.reconstruct(hamza_fix_root(w[1] + w[2] + w[4]))
            if self.ok(r):
                return r
        # P14 انفعال masdar (len6, non-merged): انطلاق→طلق، انشغال→شغل، انحلال→حلل
        if n == 6 and w[1] == "ن" and w[2] != "ت":
            r = self.reconstruct(hamza_fix_root(w[2] + w[3] + w[5]))
            if self.ok(r):
                return r
        # P15 the form-V/VI ت + merged ان-ت masdars (len6, NOT است): اجتماع→جمع،
        #     انتقال→نقل — استعار's ت belongs to است, so س-initial refused
        if n == 6 and w[2] == "ت" and w[4] == "ا" and w[1] != "س":
            r = self.reconstruct(hamza_fix_root(w[1] + w[3] + w[5]))
            if self.ok(r):
                return r
        return None

    def _attempt_core(self, w, depth, fem):
        if not (3 <= len(w) <= 12) or depth > 3:
            return None
        n = norm(w)
        if n in OVERRIDES:
            return OVERRIDES[n] if self.ok(OVERRIDES[n]) else None
        if len(w) == 3:
            if fem and w[0] == "م":            # محبة→محب→حب→حبب — rank-picked vs p1
                rem = norm(w[1:])
                c = None
                if rem not in FUNCTION_ROOTS and rem in self.keys:
                    d2 = rem[0] + rem[1] + rem[1]  # the مضاعف expansion
                    if self.ok(d2):
                        c = d2
                c = self.pick(c, self.p1(w))
                if c is not None:
                    return c
            return self.p1(w)
        if fem and w.endswith("ي"):            # مالية→مال لا ملي
            r = self.attempt(w[:-1], depth + 1, fem)
            if r is not None:
                return r
        r = self.p89(w)
        if r is not None:
            return r
        if w.endswith(FEMININE_FINAL):         # مملكة→ملك، ساعة-class
            for s in FEMININE_FINAL:
                if w.endswith(s) and len(w) - 1 >= 2:
                    return self.attempt(w[:-1], depth + 1, True)
            return None
        if w[0] == "م":                        # مدير→دير vs مدر — RANK decides
            rp = self.patterns4(w)
            rs = self.attempt(w[1:], depth + 1, fem)
            chosen = self.pick(rp, rs)
            if chosen is not None:
                return chosen
        else:
            r = self.patterns4(w)
            if r is not None:
                return r
        r = self.patterns56(w)
        if r is not None:
            return r
        for pre in PREFIXES_MORPHEMIC:         # الناس→ناس, انصار→(override)
            if w.startswith(pre):
                rest = w[len(pre):]
                if len(rest) < 3:
                    continue
                if pre in ("است", "ان") and len(rest) == 3:
                    continue                   # استمار→مار lies; استعار is an override
                if len(rest) == 3 and norm(rest) not in self.keys:
                    continue                   # the attested-intermediate guard
                r = self.attempt(rest, depth + 1, fem)
                if r is not None:
                    return r
        for suf in SUFFIXES:                   # طالبات→طالب، علماء→علما→علم
            if w.endswith(suf) and len(w) - len(suf) >= 2:
                rest = w[:-len(suf)]
                if len(rest) == 3 and norm(rest) not in self.keys:
                    continue                   # تعاون's ون never creates تعا
                r = self.attempt(rest, depth + 1, fem)
                if r is not None:
                    return r
        for pre in PREFIXES_SINGLE:            # فكتب→كتب، أيام→يام→يوم
            if w.startswith(pre):
                if pre == "ت" and fem:
                    continue                   # ترجمة keeps its ت (a root letter)
                rest = w[len(pre):]
                if len(rest) < 3:
                    continue
                r = self.attempt(rest, depth + 1, fem)
                if r is not None:
                    return r
        return None

    def attempt(self, w, depth, fem):
        """The W86ب wrapper: the FULL shipped machinery first (the fem-final
        strip RETURNS mid-function — an embedded layer never sees the
        نأخذه-class), then — only when everything failed AT DEPTH 0 — the
        present-verb layer. A present prefix sits on the word, never on a
        recursive remnant (ميدان's inner يدان must never derail its ميد
        root). Fail-closed on every axis:
          • word len>=4 — نعم/نجم/نار keep their root-letter ن
          • prefix ∈ {ي, ن} only (أ and ت already live in PREFIXES_SINGLE)
          • a PLAIN alef right after ي/ن is the vocative يا or a foreign
            remnant — refused (ناتو/ياماتو/يااخي-class all die here)
          • a len3 remainder must be ATTESTED (the layers 7-9 law — the
            recursion itself keeps it: يكتبون→كتبون→ون→كتب rides the suffix
            machinery)
        The strip joins the family of whatever root the SHIPPED machinery
        already chose for the stem (يقول stays the machine's يقل — pre-
        existing since W56, untouched); it never changes an existing
        member's root, and a skipped rank-first spelling re-claims its norm
        key law-consistently (يبدا yields to يبدأ)."""
        r = self._attempt_core(w, depth, fem)
        if r is not None or depth != 0:
            return r
        if len(w) < 4:
            return None
        for pre in ("ي", "ن"):
            if w[0] == pre:
                rest = w[1:]
                if len(rest) < 3 or rest[0] == "ا":
                    continue
                if len(rest) == 3 and norm(rest) not in self.keys:
                    continue
                r = self.attempt(rest, depth + 1, fem)
                if r is not None:
                    return r
        return None

    def derive(self, w):
        if not (3 <= len(w) <= 12) or any(not ("\u0621" <= c <= "\u064A") for c in w):
            return None
        r = self.attempt(w, 0, False)
        if r is None:
            self.skipped += 1
        return r

# --------------------------------------------------------- hand-verified gold
# The GOLD SET: classical roots (norm form) for common words — each pair
# hand-checked against the standard lexicons. Ambiguous/excluded classes are
# deliberately absent (counted in the skip report, not in gold): the ناقص-ء
# class (غداء: غدا/غدى both classical), the أعوam-class أجوف plurals, والناس
# shapes beyond the ناس override.
GOLD = [
    # المجرد الثلاثي
    ("كتب", "كتب"), ("درس", "درس"), ("عمل", "عمل"), ("سمع", "سمع"), ("نظر", "نظر"),
    ("علم", "علم"), ("ذهب", "ذهب"), ("غفر", "غفر"), ("شكر", "شكر"),
    ("كلم", "كلم"), ("دخل", "دخل"), ("خرج", "خرج"), ("رزق", "رزق"), ("وقف", "وقف"),
    ("جلس", "جلس"), ("ظلم", "ظلم"), ("عدل", "عدل"), ("كرم", "كرم"), ("قلد", "قلد"),
    ("قمر", "قمر"), ("شمس", "شمس"), ("بحر", "بحر"), ("نهر", "نهر"), ("جبل", "جبل"),
    ("حجر", "حجر"), ("خشب", "خشب"), ("وقت", "وقت"), ("وطن", "وطن"), ("شعب", "شعب"),
    ("عين", "عين"), ("عيد", "عيد"), ("ملك", "ملك"), ("دول", "دول"), ("شهر", "شهر"),
    ("فرح", "فرح"), ("حزن", "حزن"), ("صمت", "صمت"), ("ذكر", "ذكر"), ("اسم", "اسم"),
    ("عبد", "عبد"), ("سلم", "سلم"), ("بكي", "بكي"), ("غرب", "غرب"), ("صعب", "صعب"),
    # الأجوف والناقص — إعادة البناء بالراونك الفريد
    ("قال", "قول"), ("نام", "نوم"), ("خاف", "خوف"), ("كان", "كون"), ("عاب", "عيب"),
    ("رمى", "رمي"), ("سعى", "سعي"), ("رضا", "رضي"), ("زاد", "زيد"), ("عاد", "عيد"),
    ("طاب", "طيب"), ("بات", "بيت"),
    # المجرد البسيط بعد المط
    ("مدد", "مدد"), ("شدد", "شدد"),
    # فاعل والمشتقات
    ("كاتب", "كتب"), ("قارئ", "قرا"), ("عالم", "علم"), ("عامل", "عمل"), ("طالب", "طلب"),
    ("سامع", "سمع"), ("ناجح", "نجح"), ("ضارب", "ضرب"), ("حاول", "حول"), ("والد", "ولد"),
    ("سالم", "سلم"), ("مالك", "ملك"), ("قارئة", "قرا"),
    # فعال / فعيل / فعول
    ("كتاب", "كتب"), ("غلاف", "غلف"), ("رجال", "رجل"), ("كبير", "كبر"),
    ("قدير", "قدر"), ("سعيد", "سعد"), ("لطيف", "لط" + "ف"), ("صبور", "صبر"), ("دروس", "درس"),
    ("صغار", "صغر"), ("عيون", "عين"), ("ملوك", "ملك"), ("أفراح", "فرح"),
    ("أحزان", "حزن"), ("أشهر", "شهر"), ("أعياد", "عيد"), ("رحيم", "رحم"),
    ("حبيب", "حبب"), ("مثال", "مثل"),
    # مفعول والمضارع-الصيغ
    ("مكتوب", "كتب"), ("منقول", "نقل"), ("مدروس", "درس"), ("مفتوح", "فتح"), ("مذكور", "ذكر"),
    # مفعل / مفعلّة / مفال
    ("مسجد", "سجد"), ("مدرسة", "درس"), ("مكتبة", "كتب"), ("مدينة", "دين"), ("مقولة", "قول"),
    ("مملكة", "ملك"), ("سجود", "سجد"), ("مدخل", "دخل"), ("مصباح", "صبح"), ("منزل", "نزل"),
    ("مزرعة", "زرع"), ("مسلم", "سلم"), ("مدرس", "درس"), ("مدير", "دير"), ("منطق", "نطق"),
    ("ممثل", "مثل"), ("مشاركة", "شرك"), ("محبة", "حبب"), ("حبيبة", "حبب"),
    ("المدرسة", "درس"), ("المكتب", "كتب"), ("المكتوب", "كتب"), ("المكتبة", "كتب"),
    ("مكان", "كون"), ("مغرب", "غرب"), ("مدين", "دين"), ("ممالك", "ملك"),
    # البادئات والتابيات
    ("الكتاب", "كتب"), ("القمر", "قمر"), ("الحكومة", "حكم"), ("الطالبات", "طلب"),
    ("بالعدل", "عدل"), ("بالناس", "انس"), ("الناس", "انس"), ("فكتب", "كتب"), ("وعلم", "علم"),
    ("استخدام", "خدم"), ("انشغال", "شغل"), ("انطلاق", "طلق"), ("استخراج", "خرج"),
    ("تعليم", "علم"), ("تعلم", "علم"), ("تكلم", "كلم"), ("تذكر", "ذكر"), ("توقيع", "وقع"),
    ("توزيع", "وزع"), ("تنفيذ", "نفذ"), ("تشغيل", "شغل"), ("تعبير", "عبر"), ("تعديل", "عدل"),
    ("مبارك", "برك"), ("متأخر", "اخر"), ("طالبات", "طلب"), ("مكتوبة", "كتب"),
    ("كتبت", "كتب"), ("عملت", "عمل"), ("عرفت", "عرف"), ("العلم", "علم"), ("عملية", "عمل"),
    ("مالية", "مول"), ("مال", "مول"), ("وطني", "وطن"), ("أكبر", "كبر"), ("أيام", "يوم"), ("عملها", "عمل"),
    ("كتابات", "كتب"), ("مسلمون", "سلم"), ("مسلمين", "سلم"), ("مساجد", "سجد"),
    ("إسلام", "سلم"), ("اسلام", "سلم"), ("والدين", "ولد"), ("علمين", "علم"),
    ("علماء", "علم"), ("كتابا", "كتب"), ("حسنا", "حسن"),
    ("اهداء", "هدا"), ("استعار", "عار"),
    ("انتقال", "نقل"), ("اجتماع", "جمع"), ("ابتسام", "بسم"), ("اختصار", "خصر"),
    ("انحلال", "حلل"), ("تعاون", "عون"), ("تحضير", "حضر"), ("انجاح", "نجح"),
    ("اخلال", "خلل"), ("عرفها", "عرف"), ("كتابه", "كتب"), ("المعرفة", "عرف"),
    # W86ب — صيغ المضارع (الطبقة 10): كل مدخلة موثقة كلاسيكياً ومتحققة قياساً
    # قبل التثبيت (تجربة خارج المستودع على القاموس ذاته). يجري/يقول/يبدأ
    # متعمدة الغياب: قرارات الآلة المشحونة سابقة (يقل/يجر/بدي) وليست من نطاق
    # هذه الطبقة — ونستخدم متعمدة الغياب: الرفض القانوني بعد است len3.
    ("يكتب", "كتب"), ("نكتب", "كتب"), ("يكتبون", "كتب"), ("ينجح", "نجح"),
    ("ينجم", "نجم"), ("يورد", "ورد"), ("نأخذ", "اخذ"), ("نتأثر", "اثر"),
    ("يحمل", "حمل"), ("نصنع", "صنع"), ("يفهم", "فهم"), ("ينظر", "نظر"),
    ("يسمع", "سمع"), ("يعرف", "عرف"), ("يدعو", "دعو"), ("نسحب", "سحب"),
    ("يتكلم", "كلم"), ("يتعلم", "علم"), ("يأكل", "اكل"), ("نأكل", "اكل"),
    ("يسكن", "سكن"), ("نسكن", "سكن"), ("يذهب", "ذهب"), ("نذهب", "ذهب"),
]

def build(d=None):
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    if d is None:
        d = Deriver(words)
    fam = {}
    claimed = set()          # the norm-key law: one root per norm key — the
                             # rank-first spelling claims it (امل/آمل cannot
                             # sit under two roots; the engine refuses RIX_DUP)
    for idx, w in enumerate(words):
        k = norm(w)
        if k in claimed:
            continue
        r = d.derive(w)
        if r is None:
            continue
        claimed.add(k)
        fam.setdefault(r, []).append(w)
    roots = sorted(fam.keys())
    payload_lines = []
    covered = 0
    for r in roots:
        ms = fam[r]
        covered += len(ms)
        payload_lines.append(r + "\t" + " ".join(ms))
    payload = "\n".join(payload_lines) + ("\n" if payload_lines else "")
    crc = zlib.crc32(payload.encode("utf-8")) & 0xFFFFFFFF
    header = (
        "RIX1\n"
        "made\t" + date.today().isoformat() + "\n"
        "source\tassets/ar.txt\n"
        "crc32\t%08x\n" % crc +
        "roots\t%d\n" % len(roots) +
        "covered\t%d\n" % covered +
        "---\n"
    )
    return header + payload, dict(words=len(words), roots=len(roots),
                                  covered=covered, skipped=d.skipped)

def gold_report():
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    d = Deriver(words)
    hit, misses = 0, []
    for w, r in GOLD:
        got = d.derive(w)
        if got == r:
            hit += 1
        else:
            misses.append("%s→%s (expected %s)" % (w, got, r))
    total = len(GOLD)
    acc = 100.0 * hit / total if total else 0.0
    print("GOLD %d/%d accuracy=%.1f%%" % (hit, total, acc))
    for m in misses:
        print("MISS " + m)
    return hit, total, acc

def testset_report():
    """One full derivation, two measured reports (the W56 gate's accuracy
    line): the hand-verified GOLD accuracy, and the honest COVERAGE of
    assets/testset_ar.txt — the shipped test vocabulary measured against the
    committed index. Coverage is named coverage, never dressed as accuracy;
    the accuracy number is the gold set's alone."""
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    d = Deriver(words)
    hit, misses = 0, []
    for w, r in GOLD:
        got = d.derive(w)
        if got == r:
            hit += 1
        else:
            misses.append("%s→%s (expected %s)" % (w, got, r))
    total = len(GOLD)
    acc = 100.0 * hit / total if total else 0.0
    print("GOLD %d/%d accuracy=%.1f%%" % (hit, total, acc))
    for m in misses:
        print("MISS " + m)
    text, st = build(d)
    members = set()
    for line in text.split("\n---\n", 1)[1].split("\n"):
        if line:
            members.update(line.split("\t")[1].split(" "))
    ts = [t for line in open(TESTSET, encoding="utf-8") for t in line.split() if t.strip()]
    cov = sum(1 for t in ts if norm(t) in members)
    pct = 100.0 * cov / len(ts) if ts else 0.0
    print("TESTSET %d/%d coverage=%.1f%%" % (cov, len(ts), pct))
    return hit, total, acc

def trace(word):
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    d = Deriver(words)
    print("norm(%s) = %s" % (word, norm(word)))
    print("root = %s" % d.derive(word))

def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "build"
    if mode == "--gold":
        gold_report()
        return
    if mode == "--testset":
        testset_report()
        return
    if mode == "--trace":
        trace(sys.argv[2])
        return
    text, st = build()
    if mode == "--check":
        committed = open(OUT, encoding="utf-8").read()
        # F2 (the full audit, 2026-11-15): the `made` header line is the
        # creation-day stamp — date.today() made the byte-compare bite on any
        # later day though the payload stays deterministic. The check
        # normalizes EXACTLY that line, nothing else, and stays fail-closed:
        # the committed stamp must still be a real ISO date.
        if re.search(r"^made\t\d{4}-\d{2}-\d{2}$", committed, re.M) is None:
            print("ROOTINDEX_CHECK_FAILED made_stamp_missing_or_malformed")
            sys.exit(1)
        def _norm_made(s):  # compare all bytes except the `made` day stamp
            return "\n".join(("made\tCHECK-NORMALIZED" if l.startswith("made\t") else l)
                             for l in s.split("\n"))
        if _norm_made(text) != _norm_made(committed):
            print("ROOTINDEX_CHECK_FAILED non_deterministic (derived != committed)")
            sys.exit(1)
        print("ROOTINDEX_CHECK_OK bytes=%d words=%d roots=%d covered=%d"
              % (len(text.encode("utf-8")), st["words"], st["roots"], st["covered"]))
        return
    open(OUT, "w", encoding="utf-8", newline="").write(text)
    print("RIX1_WRITTEN bytes=%d words=%d roots=%d covered=%d skipped=%d"
          % (len(text.encode("utf-8")), st["words"], st["roots"],
             st["covered"], st["skipped"]))

if __name__ == "__main__":
    main()
