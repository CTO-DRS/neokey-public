#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ci/make_tashkeel_index.py — W57 (ROADMAP-V2 «العربية العميقة»): the
# TASHKEEL-INDEX DERIVATION TOOL.
#
#   The W57 knowledge asset: assets/tashkeel_index.txt (RIX2 — header +
#   CRC32 + skeleton TAB fully-diacritized forms). EVERY form is derived
#   HERE, at build time, deterministically — the engine (src/TashkeelEngine.
#   java) is a pure strict LOOKUP with zero rule code (law 6: the knowledge
#   lives in assets/, the dex carries the thin interpreter — and one
#   derivation, no python/java drift).
#
#   The derivation ladder (deterministic, dict-driven, never-guess):
#     1) the hand-verified LEXICON — full lexical diacritization for words
#        with a single common reading, and 2-3 attested variants for the
#        classic AMBIGUOUS skeletons (علم، عمل، ملك…) — each form's norm
#        MUST fold back to its key (the load-time TSX_FORM law too)
#     2) ال-COMPOSITION — an ال-word whose rest is a lexicon key takes the
#        rest's form plus the article marks (sun letter → shadda ON the
#        sun letter, moon letter → sukun ON the lam; ا/آ count as moon);
#        the rest form's final CASE mark (fatha/damma/kasra/tanwin — never
#        shadda/sukun) is dropped, the article's alef stays bare (hamzat
#        wasl — the documented convention)
#     3) ONE و/ف conjunction strip, then re-run the ladder on the rest and
#        prepend وَ/فَ (والكتاب → وَالْكِتَاب) — a rest that derives nothing
#        leaves the word untouched (والد is a direct entry, والدَّ can never
#        exist: the composition refuses rests shorter than 2 letters after ال)
#     4) the BARE article rule — an initial ال on the RAW spelling: sun
#        letter → shadda ON the sun letter, moon letter → sukun ON the lam
#        — a PARTIAL form, honest and prefixless. The WRITTEN-gemination
#        rule was audited and REFUSED (W57 session lesson): adjacent
#        identical letters are orthographically ambiguous (سبب سَبَب vs
#        حبّ حُبّ, قررت قَرَرَت vs مجددًا مُجَدَّدًا — same written shape,
#        different truth) — a blind shadda carried WRONG marks into 10,879
#        entries and no deterministic rule can tell them apart. Geminates
#        enter ONLY through the hand-verified lexicon — never guessed.
#     5) otherwise the word is SKIPPED and COUNTED — no guessing, ever
#
#   The lexical exceptions that PROVE the ladder honest: الذي/التي are
#   direct entries (الَّذي/الَّتي — the shadda sits on the lam; the blind
#   sun-rule would shadda the ذ/ت — the entries override, the gold pins it).
#
#   Convention (docs/TASHKEEL.md): full lexical marks WITHOUT the final case
#   vowel (كِتَاب not كِتَابُ) and WITHOUT hamzat-wasl signs (الْكِتَاب) —
#   case belongs to context; the engine never guesses it.
#
#   Modes:
#     python3 ci/make_tashkeel_index.py            # derive + emit + report
#     python3 ci/make_tashkeel_index.py --check    # byte-compare vs committed asset
#     python3 ci/make_tashkeel_index.py --gold     # gold-set accuracy report
#     python3 ci/make_tashkeel_index.py --testset  # gold + the reference-testset report
#     python3 ci/make_tashkeel_index.py --trace كلمة # one word, its ladder output
import sys, zlib, os, re
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DICT = os.path.join(ROOT, "assets", "ar.txt")
OUT = os.path.join(ROOT, "assets", "tashkeel_index.txt")
TESTSET = os.path.join(ROOT, "assets", "testset_tashkeel.txt")

HARAKAT = "\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652"
CASE_MARKS = "\u064B\u064C\u064D\u064E\u064F\u0650"   # tanwin+fatha/damma/kasra — never stripped of shadda/sukun
SUN = "تثذرزسشصضطظلن"

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

def canon(s):
    """Canonical combining-mark order (docs/TASHKEEL.md): on one letter the
    SHADDA precedes any vowel (الطَّالِب). Enforced MECHANICALLY at build —
    the convention must not depend on how a lexicon string was typed (the
    first testset run bit 7 contradictions from typed non-canonical lexicon
    strings: مُحَمَّد خُطَّة اللَّيْلَة — marks right, order wrong)."""
    out = []
    for ch in s:
        if ch == "\u0651" and out and out[-1] in CASE_MARKS:
            v = out.pop()
            out.append(ch)
            out.append(v)
        else:
            out.append(ch)
    return "".join(out)

def is_arabic_word(w):
    return 2 <= len(w) <= 15 and all("\u0621" <= c <= "\u064A" for c in w)

# ---------------------------------------------------------------------------
# The hand-verified LEXICON — full lexical diacritization, the documented
# convention (no final case vowel, no hamzat-wasl signs). Every key folds
# to a dictionary norm key (validated at build), every form norms back to
# its key (validated here AND at engine load — TSX_FORM).
LEXICON = {
    # ---- الحروف والكلمات الوظيفية ثابتة الشكل ----
    "في": "فِي", "من": "مِنْ", "على": "عَلَى", "لا": "لَا", "هذا": "هَذَا",
    "إلى": "إِلَى", "ما": "مَا", "عن": "عَنْ", "ذلك": "ذَلِكَ", "أنا": "أَنَا",
    "هل": "هَلْ", "لم": "لَمْ", "كان": "كَانَ", "يا": "يَا", "مع": "مَعَ",
    "لقد": "لَقَدْ", "هو": "هُوَ", "أنت": "أَنْتَ", "ماذا": "مَاذَا",
    "هنا": "هُنَا", "أو": "أَوْ", "هناك": "هُنَاكَ", "بعد": "بَعْدَ",
    "ليس": "لَيْسَ", "فقط": "فَقَط", "قد": "قَدْ", "إذا": "إِذَا",
    "هي": "هِيَ", "لن": "لَنْ", "كما": "كَمَا", "بين": "بَيْنَ",
    "خلال": "خِلَالَ", "كيف": "كَيْفَ", "عندما": "عَنْدَمَا", "غير": "غَيْر",
    "تم": "تَمَّ", "لي": "لِي", "سوف": "سَوْفَ", "تلك": "تِلْكَ",
    "حيث": "حَيْثُ", "لماذا": "لِمَاذَا", "منذ": "مُنْذُ", "شكرا": "شُكْرًا",
    "كذلك": "كَذَلِكَ", "لو": "لَوْ", "بها": "بِهَا", "بي": "بِي",
    "إلا": "إِلَّا", "أنني": "أَنَّنِي", "تحت": "تَحْتَ", "سيدي": "سَيِّدِي",
    "كم": "كَمْ", "بذلك": "بِذَلِكَ", "أوه": "أَوه", "فإن": "فَإِنَّ",
    "وهذا": "وَهَذَا", "آسف": "آسِف", "لهذا": "لِهَذَا", "بينما": "بَيْنَمَا",
    "تماما": "تَمَامًا", "أبدا": "أَبَدًا", "أبي": "أَبِي", "ولم": "وَلَمْ",
    "فيما": "فِيمَا", "فقد": "فَقَدْ", "ألا": "أَلَا", "بل": "بَلْ",
    "بأنه": "بِأَنَّه", "نحو": "نَحْوَ", "بالطبع": "بِالطَّبْع",
    "بالنسبة": "بِالنِّسْبَة", "هكذا": "هَكَذَا", "لما": "لِمَا",
    "إذن": "إِذَنْ", "ذات": "ذَات", "عدم": "عَدَم", "عشر": "عَشَرَ",
    "حتى": "حَتَّى", "دائما": "دَائِمًا", "أيضا": "أَيْضًا", "جدا": "جِدًّا",
    "حقا": "حَقًّا", "قليلا": "قَلِيلًا", "كثيرا": "كَثِيرًا", "حين": "حِينَ",
    "مما": "مِمَّا", "لأن": "لِأَنَّ", "ربما": "رُبَّمَا", "هيا": "هَيَّا",
    "مرحبا": "مَرْحَبًا", "وإذا": "وَإِذَا", "فإذا": "فَإِذَا",
    "له": "لَهُ", "لها": "لَهَا", "لهم": "لَهُمْ", "منه": "مِنْهُ",
    "منها": "مِنْهَا", "منهم": "مِنْهُم", "فيه": "فِيهِ", "فيها": "فِيهَا",
    "فيهم": "فِيهِم", "عليه": "عَلَيْهِ", "عليها": "عَلَيْهَا",
    "عليهم": "عَلَيْهِم", "إليه": "إِلَيْهِ", "إليها": "إِلَيْهَا",
    "به": "بِهِ", "بهم": "بِهِم", "عنها": "عَنْهَا", "عنه": "عَنْهُ",
    "عنهم": "عَنْهُم", "معي": "مَعِي", "معه": "مَعَه", "معها": "مَعَهَا",
    "معنا": "مَعَنَا", "لديه": "لَدَيْهِ", "لديها": "لَدَيْهَا",
    "لدينا": "لَدَيْنَا", "علينا": "عَلَيْنَا", "يمكن": "يُمْكِن",
    "يمكنني": "يُمْكِنُنِي", "يمكننا": "يُمْكِنُنَا", "تكون": "تَكُون",
    "يكون": "يَكُون", "كنا": "كُنَّا", "كانوا": "كَانُوا", "كانت": "كَانَتْ",
    "نحن": "نَحْنُ", "أنتم": "أَنْتُمْ", "هم": "هُمْ", "هن": "هُنَّ",
    "هذه": "هَذِهِ", "هؤلاء": "هَؤُلَاء", "أولئك": "أُولَئِك",
    "أما": "أَمَّا", "إما": "إِمَّا", "طبعا": "طَبْعًا", "عادة": "عَادَةً",
    "رغم": "رَغْمَ", "نعم": "نَعَمْ", "سواء": "سَوَاء", "حينها": "حِينَهَا",
    "يومها": "يَوْمَهَا", "والذي": "وَالَّذِي", "والتي": "وَالَّتِي",
    "بالطبع": "بِالطَّبْع", "بالتالي": "بِالتَّالِي", "للأسف": "لِلْأَسَف",
    "للحياة": "لِلْحَيَاة", "للغاية": "لِلْغَايَة", "والد": "وَالِد",
    "والدة": "وَالِدَة", "بأي": "بِأَيِّ",
    # ---- الأسماء الثابتة ----
    "كتاب": "كِتَاب", "مكتب": "مَكْتَب", "مكتبة": "مَكْتَبَة",
    "مدرسة": "مَدْرَسَة", "جامعة": "جَامِعَة", "مسجد": "مَسْجِد",
    "قصر": "قَصْر", "باب": "بَاب", "بيت": "بَيْت", "غرفة": "غُرْفَة",
    "مطبخ": "مَطْبَخ", "حمام": "حَمَّام", "طريق": "طَرِيق", "شارع": "شَارِع",
    "مدينة": "مَدِينَة", "قرية": "قَرْيَة", "بلد": "بَلَد", "وطن": "وَطَن",
    "أرض": "أَرْض", "سماء": "سَمَاء", "شمس": "شَمْس", "قمر": "قَمَر",
    "نجم": "نَجْم", "نجمة": "نَجْمَة", "بحر": "بَحْر", "نهر": "نَهْر",
    "جبل": "جَبَل", "صحراء": "صَحْرَاء", "ماء": "مَاء", "هواء": "هَوَاء",
    "نار": "نَار", "ريح": "رِيح", "مطر": "مَطَر", "ثلج": "ثَلْج",
    "رمل": "رَمْل", "حجر": "حَجَر", "خشب": "خَشَب", "حديد": "حَدِيد",
    "فضة": "فِضَّة", "نحاس": "نُحَاس", "شجرة": "شَجَرَة", "زهرة": "زَهْرَة",
    "وردة": "وَرْدَة", "تفاح": "تُفَّاح", "برتقال": "بُرْتُقَال",
    "خبز": "خُبْز", "ملح": "مِلْح", "لحم": "لَحْم", "دجاج": "دَجَاج",
    "سمك": "سَمَك", "زيت": "زَيْت", "عسل": "عَسَل", "جبن": "جُبْن",
    "بيض": "بَيْض", "قهوة": "قَهْوَة", "شاي": "شَاي", "عصير": "عَصِير",
    "لبن": "لَبَن", "وقت": "وَقْت", "ساعة": "سَاعَة", "دقيقة": "دَقِيقَة",
    "ليلة": "لَيْلَة", "يوم": "يَوْم", "أسبوع": "أُسْبُوع", "شهر": "شَهْر",
    "سنة": "سَنَة", "عصر": "عَصْر", "صباح": "صَبَاح", "مساء": "مَسَاء",
    "فجر": "فَجْر", "غروب": "غُرُوب", "غدا": "غَدًا",
    "أمس": "أَمْس", "تاريخ": "تَارِيخ", "رجل": "رَجُل", "امرأة": "اِمْرَأَة",
    "بنت": "بِنْت", "طفل": "طِفْل", "أطفال": "أَطْفَال",
    "شباب": "شَبَاب", "شيخ": "شَيْخ", "ابن": "اِبْن", "أخ": "أَخ",
    "أخت": "أُخْت", "جد": "جَدّ", "جدة": "جَدَّة", "عائلة": "عَائِلَة",
    "أسرة": "أُسْرَة", "صديق": "صَدِيق", "حبيب": "حَبِيب", "جار": "جَار",
    "ضيف": "ضَيْف", "زوج": "زَوْج", "زوجة": "زَوْجَة", "عمر": "عُمْر",
    "اسم": "اِسْم", "لقب": "لَقَب", "شخص": "شَخْص", "قلب": "قَلْب",
    "عقل": "عَقْل", "فكرة": "فِكْرَة", "رأي": "رَأْي",
    "كلمة": "كَلِمَة", "جملة": "جُمْلَة", "حرف": "حَرْف", "لغة": "لُغَة",
    "صوت": "صَوْت", "صورة": "صُورَة", "لون": "لَوْن", "رقم": "رَقْم",
    "عدد": "عَدَد", "شكل": "شَكْل", "خط": "خَط", "يد": "يَد",
    "رأس": "رَأْس", "فم": "فَم", "أنف": "أَنْف", "أذن": "أُذُن",
    "قدم": "قَدَم", "ساق": "سَاق", "جسم": "جِسْم", "دم": "دَم",
    "عظم": "عَظْم", "جلد": "جِلْد", "قوة": "قُوَّة", "طاقة": "طَاقَة",
    "مال": "مَال", "وظيفة": "وَظِيفَة", "مهنة": "مِهْنَة", "شركة": "شَرِكَة",
    "مشروع": "مَشْرُوع", "خطة": "خُطَّة", "تقرير": "تَقْرِير",
    "اجتماع": "اجْتِمَاع", "مدير": "مُدِير", "موظف": "مُوَظَّف",
    "عميل": "عَمِيل", "سوق": "سُوق", "سعر": "سِعْر", "ثمن": "ثَمَن",
    "قرض": "قَرْض", "راتب": "رَاتِب", "ضريبة": "ضَرِيبَة", "ميزانية": "مِيزَانِيَة",
    "حكومة": "حُكُومَة", "وزارة": "وِزَارَة", "وزير": "وَزِير",
    "مجلس": "مَجْلِس", "رئيس": "رَئِيس", "دولة": "دَوْلَة", "شعب": "شَعْب",
    "جيش": "جَيْش", "حرب": "حَرْب", "سلام": "سَلَام", "أمان": "أَمَان",
    "شرطة": "شُرْطَة", "قانون": "قَانُون", "محكمة": "مَحْكَمَة",
    "قاضي": "قَاضِي", "عقوبة": "عُقُوبَة", "حق": "حَقّ", "حرية": "حُرِّيَّة",
    "كرامة": "كَرَامَة", "صبر": "صَبْر", "رحمة": "رَحْمَة",
    "خير": "خَيْر", "شر": "شَرّ", "صدق": "صِدْق", "كذب": "كِذْب",
    "حساب": "حِسَاب", "دقيقة": "دَقِيقَة", "صورة": "صُورَة", "كلمة": "كَلِمَة",
    "جملة": "جُمْلَة", "سؤال": "سُؤَال", "جواب": "جَوَاب", "كلام": "كَلَام",
    "خبر": "خَبَر", "أخبار": "أَخْبَار", "قصة": "قِصَّة", "رواية": "رِوَايَة",
    "شاعر": "شَاعِر", "كتاب": "كِتَاب", "قارئ": "قَارِئ",
    "مكتبة": "مَكْتَبَة", "مدرسة": "مَدْرَسَة", "مدرس": "مُدَرِّس",
    "معلم": "مُعَلِّم", "طالب": "طَالِب", "طالبة": "طَالِبَة",
    "جامعة": "جَامِعَة", "كلية": "كُلِّيَّة", "شهادة": "شَهَادَة",
    "امتحان": "امْتِحَان", "درجة": "دَرَجَة",
    "علوم": "عُلُوم", "رياضيات": "رِيَاضِيَّات", "فيزياء": "فِيزِيَاء",
    "كيمياء": "كِيمِيَاء", "طب": "طِبّ", "طبيب": "طَبِيب", "دواء": "دَوَاء",
    "صحة": "صِحَّة", "مرض": "مَرَض", "مريض": "مَرِيض", "مستشفى": "مُسْتَشْفَى",
    "هندسة": "هَنْدَسَة", "مهندس": "مُهَنْدِس", "زراعة": "زِرَاعَة",
    "تجارة": "تِجَارَة", "سياحة": "سِيَاحَة", "رياضة": "رِيَاضَة",
    "كرة": "كُرَة", "فريق": "فَرِيق", "لاعب": "لَاعِب", "هدف": "هَدَف",
    "مباراة": "مُبَارَاة", "فوز": "فَوْز", "خسارة": "خَسَارَة",
    # ---- الأفعال (الماضي مضموم الفتحة، المضارع مجرد النهاية) ----
    "قال": "قَالَ", "قرأ": "قَرَأَ", "سمع": "سَمِعَ", "عرف": "عَرَفَ",
    "سأل": "سَأَلَ", "جاء": "جَاءَ", "أكل": "أَكَلَ",
    "شرب": "شَرِبَ", "لبس": "لَبِسَ", "سكن": "سَكَنَ", "عاش": "عَاشَ",
    "مات": "مَاتَ", "جلس": "جَلَسَ", "وقف": "وَقَفَ", "مشى": "مَشَى",
    "سافر": "سَافَرَ", "وصل": "وَصَلَ", "دخل": "دَخَلَ", "خرج": "خَرَجَ",
    "رجع": "رَجَعَ", "عاد": "عَادَ", "نسي": "نَسِيَ", "فهم": "فَهِمَ",
    "أرسل": "أَرْسَلَ", "استلم": "اسْتَلَمَ", "بدأ": "بَدَأَ", "ساعد": "سَاعَدَ",
    "دفع": "دَفَعَ", "أضاف": "أَضَافَ", "حذف": "حَذَفَ", "خسر": "خَسِرَ",
    "ربح": "رَبِحَ", "فاز": "فَازَ", "خاف": "خَافَ", "حزن": "حَزِنَ",
    "ضحك": "ضَحِكَ", "بكى": "بَكَى", "لعب": "لَعِبَ",
    "انتصر": "انْتَصَرَ", "هزم": "هَزَمَ", "بنى": "بَنَى", "صنع": "صَنَعَ",
    "نجح": "نَجَحَ", "نجا": "نَجَا", "فشل": "فَشِلَ", "حاول": "حَاوَلَ",
    "استمر": "اسْتَمَرَّ", "توقف": "تَوَقَّفَ", "أكمل": "أَكْمَلَ",
    "أجاب": "أَجَابَ", "أخبر": "أَخْبَرَ", "شاهد": "شَاهَدَ", "كره": "كَرِهَ",
    "أصبح": "أَصْبَحَ", "صار": "صَارَ", "أراد": "أَرَادَ", "استطاع": "اسْتَطَاعَ",
    "احتاج": "احْتَاجَ", "استخدم": "اسْتَخْدَمَ", "وضع": "وَضَعَ",
    "وجدها": "وَجَدَهَا", "ترك": "تَرَكَ", "أخذ": "أَخَذَ", "أعطى": "أَعْطَى",
    "بيع": "بَيْع", "شكر": "شَكَرَ", "دعا": "دَعَا", "سمح": "سَمَحَ",
    "غفر": "غَفَرَ", "عذب": "عَذَّبَ", "أضر": "أَضَرَّ",
    # ---- الصفات ----
    "كبير": "كَبِير", "صغير": "صَغِير", "جميل": "جَمِيل", "قبيح": "قَبِيح",
    "طويل": "طَوِيل", "قصير": "قَصِير", "جديد": "جَدِيد", "قديم": "قَدِيم",
    "سعيد": "سَعِيد", "حزين": "حَزِين", "قوي": "قَوِيّ", "ضعيف": "ضَعِيف",
    "غني": "غَنِيّ", "فقير": "فَقِير", "سريع": "سَرِيع", "بطيء": "بَطِيء",
    "سهل": "سَهْل", "صعب": "صَعْب", "قريب": "قَرِيب", "بعيد": "بَعِيد",
    "كثير": "كَثِير", "قليل": "قَلِيل", "جيد": "جَيِّد", "خاطئ": "خَاطِئ",
    "صحيح": "صَحِيح", "مؤمن": "مُؤْمِن", "كريم": "كَرِيم", "سخي": "سَخِيّ",
    "شريف": "شَرِيف", "نظيف": "نَظِيف", "حلو": "حِلْو", "مالح": "مَالِح",
    "حامض": "حَامِض", "ثقيل": "ثَقِيل", "خفيف": "خَفِيف", "عالي": "عَالِي",
    "واطئ": "وَاطِئ", "دافئ": "دَافِئ", "بارد": "بَارِد", "حار": "حَارّ",
    "غامق": "غَامِق", "فاتح": "فَاتِح", "عجيب": "عَجِيب", "عادي": "عَادِي",
    "خاص": "خَاص", "عام": "عَام", "رسمي": "رَسْمِي", "قانوني": "قَانُونِي",
    "وطني": "وَطَنِي", "عربي": "عَرَبِي", "أجنبي": "أَجْنَبِي",
    "محلي": "مَحَلِّي", "دولي": "دُوَلِي", "شهير": "شَهِير",
    "مشهور": "مَشْهُور", "معروف": "مَعْرُوف", "واضح": "وَاضِح",
    "مهم": "مُهِمّ", "مفيد": "مُفِيد", "ممكن": "مُمْكِن", "أول": "أَوَّل",
    "آخر": "آخِر", "كل": "كُلّ", "حاضر": "حَاضِر", "واقع": "وَاقِع",
    "مستقبل": "مُسْتَقْبَل",
    # ---- الدين والأعلام ----
    "إسلام": "إِسْلَام", "مسلم": "مُسْلِم", "قرآن": "قُرْآن",
    "صلاة": "صَلَاة", "رمضان": "رَمَضَان", "عيد": "عِيد", "دعاء": "دُعَاء",
    "أذان": "أَذَان", "صوم": "صَوْم", "زكاة": "زَكَاة", "حج": "حَجّ",
    "مكة": "مَكَّة",
    "محمد": "مُحَمَّد", "أحمد": "أَحْمَد", "إبراهيم": "إِبْرَاهِيم",
    "موسى": "مُوسَى", "عيسى": "عِيسَى", "نوح": "نُوح", "يوسف": "يُوسُف",
    "داود": "دَاوُد", "سليمان": "سُلَيْمَان", "فاطمة": "فَاطِمَة",
    "عائشة": "عَائِشَة", "خديجة": "خَدِيجَة", "مريم": "مَرْيَم",
    "عمر": "عُمَر", "عثمان": "عُثْمَان", "بكر": "بَكْر", "الله": "اللَّه",
    "اللهم": "اللَّهُمَّ", "الذي": "الَّذِي", "التي": "الَّتِي",
    "اللذيذ": "اللَّذِيذ", "الليلة": "اللَّيْلَة", "الآن": "الْآن",
    "البركة": "الْبَرَكَة", "الحمد": "الْحَمْد",
    # ---- أدوات الربط المركبة الشائعة ----
    "لكن": "لَكِنْ", "حيثما": "حَيْثُمَا", "وأما": "وَأَمَّا",
    # ---- تردد عالٍ إضافي (من أعلى 300 في القاموس) ----
    "شيء": "شَيْء", "شيئا": "شَيْئًا", "حسنا": "حَسَنًا", "عفوا": "عَفْوًا",
    "بخير": "بِخَيْر", "وفي": "وَفِي", "ومن": "وَمِنْ", "حياة": "حَيَاة",
    "حقيقة": "حَقِيقَة", "خاصة": "خَاصَّة", "عامة": "عَامَّة", "بلاد": "بِلَاد",
    "داخل": "دَاخِل", "مجموعة": "مَجْمُوعَة", "عملية": "عَمَلِيَّة",
    "نهاية": "نِهَايَة", "عديدة": "عَدِيدَة", "عدة": "عِدَّة", "جديدة": "جَدِيدَة",
    "كبيرة": "كَبِيرَة", "جميع": "جَمِيع", "منزل": "مَنْزِل", "لدى": "لَدَى",
    "عند": "عِنْدَ", "أمام": "أَمَام", "وراء": "وَرَاء", "فوق": "فَوْق",
    "خلف": "خَلْفَ", "كي": "كَيْ", "لكي": "لِكَيْ", "شأن": "شَأْن",
    "بشأن": "بِشَأْن", "مرة": "مَرَّة", "أعلم": "أَعْلَم", "تعرف": "تَعْرِف",
    "أعتقد": "أَعْتَقِد", "أريد": "أُرِيد", "يمكنك": "يُمْكِنُكَ",
    "أستطيع": "أَسْتَطِيع", "يحدث": "يَحْدُث", "تفعل": "تَفْعَل",
    "أكثر": "أَكْثَر", "عربية": "عَرَبِيَّة", "قدس": "قُدْس",
    "بداية": "بِدَايَة", "آخرها": "آخِرُهَا", "طبعا": "طَبْعًا",
    "عهد": "عَهْد",
    # ---- تدقيق W57: مضادات قاعدة التضعيف المرفوضة + عوامات متكررة ----
    "أمم": "أُمَم", "سبب": "سَبَب", "بسبب": "بِسَبَب", "تسبب": "تَسَبَّبَ",
    "تغيير": "تَغْيِير", "ملايين": "مَلَايِين", "ولادة": "وِلَادَة",
    "حب": "حُبّ", "ظل": "ظِلّ",
}

# The GOLD set: (bare word, expected ladder output — forms joined by a
# space, or "-" when the ladder honestly skips). Each pair hand-verified
# from the first derivation (W57: every emitted mark reviewed) then frozen —
# a regression here bites. Pins every ladder class: lexicon singles, the
# norm fold (مدينة/أخت), the audited anti-gemination fixes (أمم/سبب/حب),
# AMBIG variants, moon/sun composition, R2 sun partials, the conjunction
# (incl. its refusal وطنها), and the lexicon-before-conjunction precedence (والد).
GOLD = [
    ("كتاب", "كِتَاب"), ("في", "فِي"), ("مدينة", "مَدِينَة"),
    ("أخت", "أُخْت"), ("إلى", "إِلَى"), ("شكرا", "شُكْرًا"),
    ("أمم", "أُمَم"), ("سبب", "سَبَب"), ("حب", "حُبّ"),
    ("ولادة", "وِلَادَة"), ("الله", "اللَّه"), ("مكة", "مَكَّة"),
    ("علم", "عِلْم عَلَمَ عَلَّمَ"), ("كتب", "كَتَبَ كُتُب"),
    ("قررت", "قَرَّرَت قَرَرَت"), ("أن", "أَنْ أَنَّ"),
    ("الكتاب", "الْكِتَاب"), ("البيت", "الْبَيْت"),
    ("الأمم", "الْأُمَم"), ("المدينة", "الْمَدِينَة"),
    ("الشمس", "الشَّمْس"), ("النار", "النَّار"), ("الطالب", "الطَّالِب"),
    ("الناس", "النّاس"), ("الشهر", "الشَّهْر"),
    ("والكتاب", "وَالْكِتَاب"), ("والسبب", "وَالسَّبَب"),
    ("والكتب", "وَالْكَتَبَ وَالْكُتُب"), ("وهذه", "وَهَذِهِ"),
    ("فالكتاب", "فَالْكِتَاب"),
    ("والملك", "وَالْمُلْك وَالْمَلَكَ وَالْمَلِك"),
    ("والد", "وَالِد"), ("والأمم", "وَالْأُمَم"), ("والله", "وَاللَّه"),
    ("بالطبع", "بِالطَّبْع"), ("وفي", "وَفِي"), ("والناس", "وَالنّاس"),
    ("قلبت", "-"), ("بالنار", "-"), ("أمها", "-"), ("وطنها", "-"),
]

# The classic AMBIGUOUS skeletons — 2-3 attested readings each, hand-verified.
# The AMBIG mode rides these as source-labeled strip variants; the FULL mode
# offers them all. A word with no confident single reading lives HERE or
# nowhere — never guessed.
AMBIG = {    "علم": ["عِلْم", "عَلَمَ", "عَلَّمَ"],
    "عمل": ["عَمَل", "عَمِلَ"],
    "وجه": ["وَجْه", "وَجَهَ", "وَجَّهَ"],
    "أمر": ["أَمْر", "أَمَرَ"],
    "ذهب": ["ذَهَب", "ذَهَبَ"],
    "فعل": ["فِعْل", "فَعَلَ"],
    "حسن": ["حُسْن", "حَسَنَ"],
    "حدث": ["حَدِث", "حَدَثَ"],
    "أن": ["أَنْ", "أَنَّ"],
    "انه": ["إِنَّه", "أَنَّه"],
    "انها": ["إِنَّهَا", "أَنَّهَا"],
    "لكن": ["لَكِنْ", "لَكِنَّ"],
    "أي": ["أَيْ", "أَيُّ"],
    "أم": ["أَمْ", "أُمّ"],
    "مثل": ["مِثْلَ", "مَثَل"],
    "تعلم": ["تَعَلَّمَ", "تَعْلِم"],
    "ملك": ["مُلْك", "مَلَكَ", "مَلِك"],
    "نظر": ["نَظَرَ", "نَظْر"],
    "شعر": ["شِعْر", "شَعَرَ", "شَعَر"],
    "عين": ["عَيْن", "عَيَّنَ"],
    "قدر": ["قَدَرَ", "قَدْر"],
    "صدر": ["صَدَرَ", "صَدْر"],
    "جمع": ["جَمَعَ", "جَمْع"],
    "وجد": ["وَجَدَ", "وَجْد"],
    "أمل": ["أَمَل", "أَمِلَ"],
    "حلم": ["حُلْم", "حَلَمَ"],
    "وقع": ["وَقَعَ", "وَقْع"],
    "ظهر": ["ظَهَرَ", "ظُهْر"],
    "عدل": ["عَدَلَ", "عَدْل"],
    "كتب": ["كَتَبَ", "كُتُب"],
    "درس": ["دَرَسَ", "دَرْس"],
    "فكر": ["فَكَّرَ", "فِكْر"],
    "خطط": ["خَطَّطَ", "خُطُط"],
    "غنى": ["غَنَّى", "غِنًى"],
    "ولد": ["وَلَد", "وُلِدَ"],
    "عالم": ["عَالَم", "عَالِم"],
    "قررت": ["قَرَّرَت", "قَرَرَت"],
    "رأي": ["رَأْي", "رَأَى"],
}

# The normed-key views: the ladder (and the emitted asset) key everything by
# Dictionary.norm — raw spellings fold 1:1 (أخت→اخت، مدينة→مدينه).
LEXICON_N = {norm(k): canon(v) for k, v in LEXICON.items()}
AMBIG_N = {norm(k): [canon(f) for f in v] for k, v in AMBIG.items()}

# ---------------------------------------------------------------------------
def article_marks(bare):
    """R2 on a true initial ال: sun letter → shadda ON the sun letter,
    moon letter → sukun ON the lam. Runs on the RAW spelling so the hamza
    seats survive (الأمم → الْأمم). Returns the marked string or None."""
    if not (bare.startswith("ال") and len(bare) >= 4):
        return None
    rest0 = bare[2]
    if rest0 in SUN:
        return bare[:2] + rest0 + "\u0651" + bare[3:]
    return bare[0] + bare[1] + "\u0652" + bare[2:]

def strip_case(form):
    """Drop the trailing CASE mark only (never shadda/sukun — those are
    lexical): the composition law for borrowed rest-forms."""
    if form and form[-1] in CASE_MARKS:
        return form[:-1]
    return form

def compose(rest0, rest_form):
    """ال + rest: the article marks merged onto the rest's lexicon form."""
    if rest0 in SUN:
        return "ال" + rest0 + "\u0651" + rest_form[1:]
    return "ال" + "\u0652" + rest_form

class Deriver:
    def __init__(self, dict_keys):
        self.keys = dict_keys          # the set of dictionary norm keys
        self.skipped = 0

    def derive(self, w, depth=0):
        """The full ladder for one raw dictionary word; returns a list of
        forms or None. Deterministic; dict-driven (a word not in ar.txt
        never reaches here). The rules run on the RAW spelling (norm folds
        hamza seats 1:1 — length preserved, positions aligned)."""
        if not is_arabic_word(w) or depth > 1:
            return None
        k = norm(w)
        forms = self._ladder(k, w)
        if forms is None and depth == 0 and len(k) >= 3 and k[0] in "وف":
            sub = self.derive(w[1:], depth=1)
            if sub:
                conj = "وَ" if k[0] == "و" else "فَ"
                return [conj + f for f in sub]
        return forms

    def _ladder(self, k, raw):
        # 1) the hand-verified lexicon (singles and ambiguous variants)
        if k in LEXICON_N:
            return [LEXICON_N[k]]
        if k in AMBIG_N:
            return list(AMBIG_N[k])
        # 2) ال-composition: rest must be a lexicon key (≥2 letters after ال)
        #    — k >= 4 chars (ال+rest≥2); the old >=5 guard silently dropped
        #    the two-letter rests (الْأُمّ، الْأَخ) — the W57 audit lesson
        if k.startswith("ال") and len(k) >= 4:
            rest = k[2:]
            if len(rest) >= 2 and rest in LEXICON_N:
                return [compose(rest[0], LEXICON_N[rest])]
            if len(rest) >= 2 and rest in AMBIG_N:
                return [compose(rest[0], f) for f in AMBIG_N[rest]]
        # 3) the bare article rule — RAW spelling (hamza seats survive) —
        #    the ONLY bare rule; gemination is refused (see header)
        r2 = article_marks(raw)
        if r2 is not None:
            return [r2]
        return None

def build():
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    keys = set(norm(w) for w in words)
    # the lexicon must be reality-anchored: every key folds to a dict key
    bad = [k for k in list(LEXICON) + list(AMBIG)
           if norm(k) not in keys]
    if bad:
        print("TASHKEEL_LEXICON_REFUSED keys-not-in-dictionary: %s" % " ".join(sorted(bad)[:12]))
        sys.exit(1)
    forms_bad = []
    for k, v in LEXICON.items():
        if norm(v) != norm(k): forms_bad.append(k)
    for k, vs in AMBIG.items():
        if not vs or any(norm(f) != norm(k) for f in vs): forms_bad.append(k)
    if forms_bad:
        print("TASHKEEL_LEXICON_REFUSED form-does-not-fold-to-key: %s" % " ".join(sorted(set(forms_bad))[:12]))
        sys.exit(1)
    d = Deriver(keys)
    claimed = set()   # one line per norm key — the rank-first spelling claims it
    lines = []
    covered = 0
    for w in words:
        k = norm(w)
        if k in claimed:
            continue
        fs = d.derive(w)
        if not fs:
            continue
        claimed.add(k)
        covered += len(fs)
        lines.append(k + "\t" + " ".join(canon(f) for f in fs))
    payload = "\n".join(lines) + ("\n" if lines else "")
    crc = zlib.crc32(payload.encode("utf-8")) & 0xFFFFFFFF
    header = (
        "RIX2\n"
        "made\t" + date.today().isoformat() + "\n"
        "source\tci/make_tashkeel_index.py\n"
        "crc32\t%08x\n" % crc +
        "entries\t%d\n" % len(lines) +
        "covered\t%d\n" % covered +
        "---\n"
    )
    return header + payload, dict(words=len(words), entries=len(lines),
                                  covered=covered, skipped=len(keys - claimed))

# ---------------------------------------------------------------------------
def derive_forms(k):
    """One derivation, reused by gold and testset (the engine-equivalent)."""
    d = _DERIVER
    return d.derive(k) if d else None

_DERIVER = None

def gold_report():
    hit, misses = 0, []
    for w, want in GOLD:
        fs = derive_forms(w)
        got = " ".join(fs) if fs else "-"
        if got == want:
            hit += 1
        else:
            misses.append("%s → %s (expected %s)" % (w, got, want))
    acc = 100.0 * hit / len(GOLD) if GOLD else 0.0
    print("GOLD %d/%d accuracy=%.1f%%" % (hit, len(GOLD), acc))
    for m in misses:
        print("MISS " + m)
    return hit, len(GOLD), acc

def marks_of(form):
    """Per-skeleton-letter mark strings of a diacritized form."""
    out = []
    for ch in form:
        if ch in HARAKAT:
            if out: out[-1] += ch
        else:
            out.append("")
    return out

def testset_report():
    """The reference testset: every word hand-diacritized by the documented
    convention. For each word: an engine form EXACTLY equal to the reference
    is a full hit; every mark the engine DID emit must be an order-consistent
    PREFIX of the reference's marks at the same skeleton letter — fewer marks
    is honest partial coverage (السَّياره vs السَّيَّارَة), a CONFLICTING
    mark is a CONTRADICTION (a hard gate failure); no emission is honest
    uncovered. Coverage is named coverage, never dressed as accuracy; the
    accuracy line is the gold's."""
    hit, total, contra, cov = 0, 0, 0, 0
    examples = []
    for line in open(TESTSET, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        for tok in line.split():
            # punctuation ONLY — a token's final HARAKAT are real marks
            # (هَذِهِ، قَرَأَ، حَارّ) and edge-stripping them manufactured
            # 18 fake contradictions in the first run (W57 lesson)
            core = tok.strip("،,.!?؛:«»()\"'-؟")
            if not core or not is_arabic_word(norm(core)):
                continue
            total += 1
            fs = derive_forms(norm(core))
            if not fs:
                continue
            cov += 1
            ref_marks = marks_of(core)
            ref_skel = norm(core)
            exact = any(f == core for f in fs)
            if exact:
                hit += 1
            bad = False
            for f in fs:
                if norm(f) != ref_skel:
                    bad = True; break
                m = marks_of(f)
                for i, mark in enumerate(m):
                    if mark and i >= len(ref_marks):
                        bad = True; break
                    # prefix law: fewer marks = honest partial; a conflicting
                    # (non-prefix) mark = contradiction
                    if mark and not ref_marks[i].startswith(mark):
                        bad = True; break
                if bad: break
            if bad:
                contra += 1
                if len(examples) < 12:
                    examples.append("%s: engine=%s ref=%s" % (core, " ".join(fs), core))
    full_pct = 100.0 * hit / total if total else 0.0
    cov_pct = 100.0 * cov / total if total else 0.0
    print("TESTSET words=%d full=%d (%.1f%%) coverage=%d (%.1f%%) contradictions=%d"
          % (total, hit, full_pct, cov, cov_pct, contra))
    for e in examples:
        print("CONTRA " + e)
    return total, hit, cov, contra

def trace(word):
    fs = derive_forms(word)
    print("%s → %s" % (word, " ".join(fs) if fs else "-"))

def main():
    global _DERIVER
    words = [l.rstrip("\n") for l in open(DICT, encoding="utf-8") if l.strip()]
    _DERIVER = Deriver(set(norm(w) for w in words))
    mode = sys.argv[1] if len(sys.argv) > 1 else "build"
    if mode == "--gold":
        gold_report(); return
    if mode == "--testset":
        gold_report(); testset_report(); return
    if mode == "--trace":
        trace(sys.argv[2]); return
    text, st = build()
    if mode == "--check":
        committed = open(OUT, encoding="utf-8").read()
        # F2 (the full audit, 2026-11-15): the `made` header line is the
        # creation-day stamp — see make_root_index.py; normalize exactly that
        # line and keep the committed stamp fail-closed on its ISO shape.
        if re.search(r"^made\t\d{4}-\d{2}-\d{2}$", committed, re.M) is None:
            print("TASHKEELINDEX_CHECK_FAILED made_stamp_missing_or_malformed")
            sys.exit(1)
        def _norm_made(s):  # compare all bytes except the `made` day stamp
            return "\n".join(("made\tCHECK-NORMALIZED" if l.startswith("made\t") else l)
                             for l in s.split("\n"))
        if _norm_made(text) != _norm_made(committed):
            print("TASHKEELINDEX_CHECK_FAILED non_deterministic (derived != committed)")
            sys.exit(1)
        print("TASHKEELINDEX_CHECK_OK bytes=%d words=%d entries=%d covered=%d"
              % (len(text.encode("utf-8")), st["words"], st["entries"], st["covered"]))
        return
    open(OUT, "w", encoding="utf-8", newline="").write(text)
    print("RIX2_WRITTEN bytes=%d words=%d entries=%d covered=%d"
          % (len(text.encode("utf-8")), st["words"], st["entries"], st["covered"]))

if __name__ == "__main__":
    main()
