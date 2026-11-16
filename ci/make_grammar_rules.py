#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ci/make_grammar_rules.py — W58: the ONE derivation of assets/grammar_rules.txt
# (law 6: the knowledge lives in the asset, the dex carries the thin
# interpreter src/GrammarNudge.java). Roadmap W58 «النحّاخ الخفيف» —
# the five documented families: همزة القطع، التاء المربوطة/الهاء،
# المقصورة/الياء، لا/لن/لم مع الماضي، أشهر الالتباسات.
#
#   python3 ci/make_grammar_rules.py            # derive + write the sealed asset
#   python3 ci/make_grammar_rules.py --check    # byte-compare vs committed asset
#   python3 ci/make_grammar_rules.py --testset  # the reference-scanner annoyance report
#
# THE HAND-VERIFIED SOURCE (below): every rule carries ONE correct example
# (passes — zero nudges from ANY rule) and ONE wrong example (bites — exactly
# one nudge, its own id + suggestion). The build ENFORCES this with an
# INDEPENDENT reference scanner implemented here (the W53-prep agreement law:
# the derivation-side checker and the shipped Java engine must agree on every
# row — and the engine re-verifies the same sides at load; a divergence refuses).
#
# THE HONEST REFUSALS (audited, the W57 والدّ discipline — documented in
# docs/GRAMMAR.md §5): bare "ان" (أن/إن is a context choice — a blind rule
# guesses), bare "علي" (the name and the preposition are one written shape),
# bare "الا" (إلا/ألا likewise) — none may become a rule; only UNAMBIGUOUS
# written shapes enter.
#
# SCOPE (documented, no inflation): patterns match the EXACT stripped token —
# bare words for particles/prepositions/names/indefinite nouns, ال-forms for
# the definite nouns that dominate running text; attached prefixes (والمدرسه
# and friends) are FUTURE rows, never guessed. Zero false positives on the
# clean reference testset is the law; recall is never claimed beyond the
# attested patterns.

import sys, os, zlib, unicodedata

MADE = "2026-11-13"
SOURCE = "ci/make_grammar_rules.py"
FORMAT = "GRX1"

# ---------------------------------------------------------------- the families
# family id → the Arabic label (docs/GRAMMAR.md §3 mirrors these exactly)
FAMILIES = {
    "hamza":    "همزة القطع الناقصة",
    "taa":      "التاء المربوطة والهاء",
    "maqsura":  "الألف المقصورة والياء",
    "nafi":     "لا/لن/لم مع الماضي",
    "iltibas":  "أشهر الالتباسات",
    "irab":     "الإعراب الخفيف",
}

# sev: S = fires in ناعم (soft) AND صارم (strict); X = strict-only.
# kind: W = one-word pattern → one-word suggestion; P = pair rows below.
RULES = [
    # ---------- همزة القطع الناقصة (soft) ----------
    ("hamza", "S", "W", "اذا",   "إذا",   "إذا شرطية فعلية تبدأ بهمزة قطع مكسورة"),
    ("hamza", "S", "W", "احمد",  "أحمد",  "أحمد اسم علم وهمزته قطع على الألف"),
    ("hamza", "S", "W", "ايام",  "أيام",  "أيام جمع يوم وهمزتها قطع"),
    ("hamza", "S", "W", "استاذ", "أستاذ", "أستاذ على وزن استفعل وهمزته قطع"),
    ("hamza", "S", "W", "اسرة",  "أسرة",  "أسرة من أسر وهمزتها قطع"),
    ("hamza", "S", "W", "اساس",  "أساس",  "أساس همزته قطع على أول الكلمة"),
    ("hamza", "S", "W", "اكرم",  "أكرم",  "أكرم اسم علم وهمزته قطع"),
    # 2026-11-15 expansion (owner directive, NOTES): same audited discipline —
    # every pattern below is an UNAMBIGUOUS written shape; bare لان was
    # REFUSED (لانَ يَلين is a real verb — a context choice never a rule).
    ("hamza", "S", "W", "امس",     "أمس",     "أمس ظرف الماضي وهمزته قطع"),
    ("hamza", "S", "W", "الان",    "الآن",    "الآن ظرف وهمزته قطع على الألف"),
    ("hamza", "S", "W", "انترنت",  "إنترنت",  "إنترنت همزته قطع مكسورة على الألف"),
    ("hamza", "S", "W", "الاخبار", "الأخبار", "الأخبار جمع خبر وهمزته قطع"),
    ("hamza", "S", "W", "اكثر",    "أكثر",    "أكثر على وزن أفعل وهمزته قطع"),
    ("hamza", "S", "W", "اقل",     "أقل",     "أقل على وزن أفعل وهمزته قطع"),
    ("hamza", "S", "W", "اول",     "أول",     "أول على وزن أفعل وهمزته قطع"),
    ("hamza", "S", "W", "شئ",      "شيء",     "شيء تنتهي بهمزة القطع على النبرة لا بحرف جر"),
    ("hamza", "S", "W", "مبادي",   "مبادئ",   "مبادئ جمع مبدأ وتنتهي بهمزة القطع على النبرة"),
    # ---------- التاء المربوطة والهاء (soft) ----------
    ("taa", "S", "W", "المدرسه",  "المدرسة",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "الجامعه",  "الجامعة",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "مساحه",   "مساحة",   "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "الحديقه",  "الحديقة",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "لغه",     "لغة",     "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "الكليه",  "الكلية",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "النهايه",  "النهاية",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "الحقيبه",  "الحقيبة",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "فاطمه",    "فاطمة",    "فاطمة اسم علم مؤنث ينتهي بتاء مربوطة"),
    ("taa", "S", "W", "عائله",    "عائلة",    "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "ثلاجه",    "ثلاجة",    "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    ("taa", "S", "W", "بالنسبه",  "بالنسبة",  "المفرد المؤنث ينتهي بتاء مربوطة لا بهاء"),
    # ---------- الألف المقصورة والياء (soft) ----------
    ("maqsura", "S", "W", "فى",   "في",   "جار في تُكتب بياء منقوطة لا بألف مقصورة"),
    ("maqsura", "S", "W", "الذى",  "الذي",  "الاسم الموصول يُكتب بياء منقوطة لا بمقصورة"),
    ("maqsura", "S", "W", "التى",  "التي",  "الاسم الموصول يُكتب بياء منقوطة لا بمقصورة"),
    ("maqsura", "S", "W", "لكى",   "لكي",   "لكي التعليلية بياء منقوطة لا بمقصورة"),
    ("maqsura", "S", "W", "حتي",   "حتى",   "حتى تنتهي بألف مقصورة لا بياء"),
    ("maqsura", "S", "W", "الى",   "إلى",   "إلى مجرورة بهمزة قطع تحت الألف لا بمقصورة"),
    ("maqsura", "S", "W", "مستشفي", "مستشفى", "مستشفى تنتهي بألف مقصورة لا بياء"),
    # ---------- لا/لن/لم مع الماضي (strict-only, pair rows below) ----------
    ("nafi", "X", "P", "لا-ماض", "لم+مضارع", "لا تنفي المضارع ولإنكار الماضي لم مع الفعل مضارعا"),
    ("nafi", "X", "P", "لم-ماض", "لم+مضارع", "لم تجزم المضارع وتنفي الماضي كلم يذهب"),
    ("nafi", "X", "P", "لن-ماض", "لن+مضارع", "لن تجزم المضارع وتنفي المستقبل كلم يذهب"),
    # ---------- أشهر الالتباسات (soft) ----------
    ("iltibas", "S", "W", "هاذا",      "هذا",         "اسم الإشارة للقريب هذا بلا هواء بين الحاء والألف"),
    ("iltibas", "S", "W", "لاكن",      "لكن",         "عطف البيان لكن ألفه مفردة بلا لام"),
    ("iltibas", "S", "W", "لانه",      "لأنه",        "لأنه لام ثم أن ثم هاء وهمزتها قطع فوق الألف"),
    ("iltibas", "S", "W", "بالتالى",   "بالتالي",     "بالتالي بياء منقوطة لا بألف مقصورة"),
    ("iltibas", "S", "W", "إنشاءالله", "إن شاء الله",  "تُفصل إن شاء الله فعل وفاعل ومفعول به"),
    ("iltibas", "S", "W", "انشاءالله", "إن شاء الله", "الصورة المدموجة بلا همزة خاطئة كذلك — تُفصل دائما"),
    ("iltibas", "S", "W", "ذالك",      "ذلك",         "ذلك اسم إشارة للبعيد بلا لام بعد الذال"),
    ("iltibas", "S", "W", "اللذي",     "الذي",        "الاسم الموصول الذي بلام واحدة لا بلامين"),
    ("iltibas", "S", "W", "هاذي",      "هذه",         "هذه اسم إشارة للقريب المؤنث بهاء ساكنة لا بألف وياء"),
    # ---------- الإعراب الخفيف (W85 — GRX2) ----------
    # 2026-11-16 (owner directive, NOTES): the i'rab layer — the same W58
    # contract, and every explanation carries the GRAMMATICAL LESSON of the
    # confused pair. Only UNAMBIGUOUS bare shapes enter: همزة الوصل في
    # الموصولين والمعدول والمجرورات. The audited REFUSALS (a context choice
    # is never a rule): bare "فان" (ف+إنّ/ف+أن), "اثني عشر/اثنا عشر" (the
    # case follows the role), "لن يذهبون" (needs the PREVIOUS word — the
    # scan is word-level, ون الجماعة alone is the CORRECT rafa' form).
    ("irab", "S", "W", "اللذين", "الذين", "الذين اسم موصول للمذكرين بكل إعرابه وهمزته وصل لا قطع"),
    ("irab", "S", "W", "اللتي",  "اللاتي", "اللاتي اسم موصول للمؤنث في النصب والجر بألف قبل التاء"),
    ("irab", "S", "W", "إثنين",  "اثنين",  "اثنين معدول عن المثنى في النصب والجر وهمزته وصل مكسورة لا قطع"),
    ("irab", "S", "W", "إثنان",  "اثنان",  "اثنان معدول عن المثنى في الرفع وهمزته وصل مكسورة لا قطع"),
    ("irab", "S", "W", "اليه",   "إليه",   "إليه اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
    ("irab", "S", "W", "الينا",  "إلينا",  "إلينا اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
    ("irab", "S", "W", "اليها",  "إليها",  "إليها اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
    ("irab", "S", "W", "اليهم",  "إليهم",  "إليهم اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
    ("irab", "S", "W", "اليك",   "إليك",   "إليك اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
    ("irab", "S", "W", "اليكم",  "إليكم",  "إليكم اسم مجرور بحرف الجر إلى وهمزته قطع مكسورة"),
]

# the negation verb table (hand-verified past 3ms → present 3ms, 16 common verbs)
VERBS = [
    ("ذهب", "يذهب"), ("كتب", "يكتب"), ("قال", "يقول"), ("أكل", "يأكل"),
    ("شرب", "يشرب"), ("دخل", "يدخل"), ("خرج", "يخرج"), ("سافر", "يسافر"),
    ("وصل", "يصل"),  ("رأى", "يرى"),  ("أخذ", "يأخذ"), ("درس", "يدرس"),
    ("عمل", "يعمل"), ("سمع", "يسمع"), ("لعب", "يلعب"), ("نسي", "ينسى"),
]
# pair rules: wrong-pair → fixed-pair. The نafi rows above are the rule
# shells; each pair is one P row (wrong pair, space-separated, → fixed pair).
PAIRS = []  # (rule-key, wrong, fixed) — filled below
for v_past, v_pres in VERBS:
    PAIRS.append(("لا-ماض", "لا " + v_past,  "لم " + v_pres))
    PAIRS.append(("لم-ماض", "لم " + v_past,  "لم " + v_pres))
    PAIRS.append(("لن-ماض", "لن " + v_past,  "لن " + v_pres))

# the examples: rule pattern-key → (good, bad). For word rules the key is the
# pattern word; for pair rules the key is the rule's pair-key above.
EXAMPLES = {
    "اذا":   ("إذا وصل أخوك فأكرمه",          "اذا وصل أخوك فأكرمه"),
    "احمد":  ("أكرم وأحمد صديقان موثوقان",      "زارنا احمد الاثنين الماضي"),
    "ايام":  ("أيام العطلة قصيرة ولطيفة",       "ايام العطلة قصيرة ولطيفة"),
    "استاذ": ("الأستاذ شرح الدرس بوضوح",        "سلمت على استاذ المدرسة"),
    "اسرة":  ("أسرة صديقي تستقبل ضيوفها اليوم", "اسرة صديقي تستقبل ضيوفها اليوم"),
    "اساس":  ("الأساس الصلب يحمي البناء",       "البيت بني على اساس صلب"),
    "اكرم":  ("شكر أكرم معلمه على المساعدة",    "شكرت اكرم على مساعدته"),
    "المدرسه":  ("دخل التلاميذ المدرسة مبكرين",   "دخل التلاميذ المدرسه مبكرين"),
    "الجامعه":  ("قبل في الجامعة هذا العام",      "قبل في الجامعه هذا العام"),
    "مساحه":   ("تحتاج المكتبة مساحة واسعة",      "تحتاج المكتبة مساحه واسعة"),
    "الحديقه":  ("الورد في الحديقة جميل",         "الورد في الحديقه جميل"),
    "لغه":     ("أحب تعلم كل لغة جديدة",          "أحب تعلم كل لغه جديدة"),
    "الكليه":   ("درس ابنه في الكلية أربع سنوات", "درس ابنه في الكليه أربع سنوات"),
    "النهايه":  ("القصة جميلة النهاية",           "القصة جميلة النهايه"),
    "فى":   ("الهدية في العلبة ملونة",        "الهدية فى العلبة ملونة"),
    "الذى":  ("المعلم الذي زارنا غائب اليوم",  "المعلم الذى زارنا غائب اليوم"),
    "التى":  ("الحقيبة التي اشتريتها ممزقة",   "الحقيبة التى اشتريتها ممزقة"),
    "لكى":   ("درست لكي أنجح",                "جاء مبكرا لكى يجد مقعده"),
    "حتي":  ("انتظرت حتى الفجر",              "انتظرت حتي الفجر"),
    "الى":  ("سافرنا إلى جدة بالفجر",         "سافرنا الى جدة بالفجر"),
    "لا-ماض": ("لم يصل الرد حتى الليلة",       "لا وصل الرد حتى الليلة"),
    "لم-ماض": ("لم يكتب الجواب لأنه مشغول",    "لم كتب الجواب لأنه مشغول"),
    "لن-ماض": ("لن يذهب أخي إلى الحفل",        "لن ذهب أخي إلى الحفل"),
    "هاذا":     ("هذا الكتاب مفيد",                "هاذا الكتاب مفيد"),
    "لاكن":     ("الطريق بعيد لكن ممتع",           "الطريق بعيد لاكن ممتع"),
    "لانه":     ("سافر لأنه وجد عملا",            "سافر لانه وجد عملا"),
    "بالتالى":  ("تأخر الموعد وبالتالي غيرنا الخطة", "تأخر الموعد بالتالى غيرنا الخطة"),
    "إنشاءالله": ("إن شاء الله نلتقي غدا",         "نلتقي غدا إنشاءالله"),
    "امس":     ("زرنا الجد أمس",                   "زرنا الجد امس"),
    "الان":    ("الآن فهمت الدرس",                 "الان فهمت الدرس"),
    "انترنت":  ("إنترنت الجوال بطيء اليوم",        "انترنت الجوال بطيء اليوم"),
    "الاخبار": ("الأخبار الجيدة تسر القلب",        "الاخبار الجيدة تسر القلب"),
    "اكثر":    ("الأكثر قراءة أصح الكتب",          "قرأ اكثر الكتب"),
    "اقل":     ("الأقل كلاما أنفع",                "كلامه اقل نفعا"),
    "اول":     ("أول الصف ممتلئ",                  "جاء اول الصف"),
    "شئ":      ("لا شيء يخفى",                     "لم يبق شئ من الوقت"),
    "مبادي":   ("مبادئ المشروع واضحة",             "ناقشنا مبادي الاتفاق ثم وقعنا"),
    "الحقيبه":  ("الحقيبة الثقيلة على الطاولة",     "نسيت الحقيبه على الطاولة"),
    "فاطمه":   ("فاطمة طبيبة ماهرة",               "زارتنا فاطمه الصباح"),
    "عائله":   ("عائلته كبيرة الوفرة",             "عائله كبيرة تساعدنا"),
    "ثلاجه":   ("الثلاجة الجديدة هادئة",           "اشترينا ثلاجه جديدة"),
    "بالنسبه": ("بالنسبة للدرس تقدمت",             "تقدمت بالنسبه للدرس"),
    "مستشفي":  ("المستشفى الجديد قريب",            "بنوا مستشفي جديدا"),
    "انشاءالله": ("سنجد حلا إن شاء الله",          "سنجد حلا انشاءالله"),
    "ذالك":    ("ذلك الرجل صديقي القديم",          "أعجبني ذالك الكلام"),
    "اللذي":   ("الكتاب الذي قرأته ممتع",          "الكتاب اللذي قرأته ممتع"),
    "هاذي":    ("هذه الحقيبة جديدة",               "هاذي الحقيبة جديدة"),
    # W85 (الإعراب الخفيف): the good side must pass EVERY rule; the bad side
    # bites exactly its own row — and the good examples' grammar is audited
    # (الذين مرفوع في موقع الفاعل، اللاتي في نصب المفعول به، اثنان في الرفع).
    "اللذين":  ("الطلاب الذين نجحوا يحتفلون الليلة",    "الطلاب اللذين نجحوا يحتفلون الليلة"),
    "اللتي":   ("رأيت الطالبات اللاتي نجحن في المسابقة", "رأيت الطالبات اللتي نجحن في المسابقة"),
    "إثنين":   ("حضر اثنين من الضيوف في العرس",         "حضر إثنين من الضيوف في العرس"),
    "إثنان":   ("حضر اثنان من المعلمين الاجتماع",       "حضر إثنان من المعلمين الاجتماع"),
    "اليه":    ("سلّمت الرسالة إليه صباحاً",            "سلّمت الرسالة اليه صباحاً"),
    "الينا":   ("قال إلينا الحقيقة كاملة في الاجتماع",   "قال الينا الحقيقة كاملة في الاجتماع"),
    "اليها":   ("ذهبت إليها في المساء بعد العشاء",      "ذهبت اليها في المساء بعد العشاء"),
    "اليهم":   ("بشّرنا إليهم بالنجاح قبل الموعد",      "بشّرنا اليهم بالنجاح قبل الموعد"),
    "اليك":    ("سأرسل الجواب إليك في الصباح",          "سأرسل الجواب اليك في الصباح"),
    "اليكم":   ("التقينا إليكم في العيد الماضي",        "التقينا اليكم في العيد الماضي"),
}

# ------------------------------------------------------------------ canonical
def canon(s):
    s = unicodedata.normalize("NFC", s)
    return " ".join(s.split())

HARAKAT = [chr(c) for c in range(0x064B, 0x0653)]

def is_arabic(ch):
    return 0x0621 <= ord(ch) <= 0x0652

def strip_marks(t):
    return "".join(ch for ch in t if ch not in HARAKAT)

def tokens(text):
    """The reference tokenizer — maximal Arabic-letter runs (0x0621-0x0652,
    harakat INCLUDED then stripped at match time). Mirrors the Java engine
    exactly (the agreement law)."""
    out, i, n = [], 0, len(text)
    while i < n:
        if is_arabic(text[i]):
            j = i
            while j < n and is_arabic(text[j]):
                j += 1
            out.append((i, text[i:j]))
            i = j
        else:
            i += 1
    return out

def build_index():
    """pattern → (rule-index, sev, kind, suggestion). Word patterns keyed by
    the stripped word; pair patterns by 'w1 w2' (stripped)."""
    words, pairs = {}, {}
    for idx, (fam, sev, kind, pat, sug, exp) in enumerate(RULES):
        if kind == "W":
            words[pat] = idx
        else:
            pairs[pat] = idx
    for key, wrong, fixed in PAIRS:
        pairs[wrong] = RULES.index(next(r for r in RULES if r[3] == key))
    return words, pairs

def ref_scan(text, mode, words, pairs):
    """The INDEPENDENT reference scanner (Python): nudges as
    (start, end, rule-idx). mode: 0 off / 1 soft / 2 strict — mirrors the
    shipped engine's gates exactly."""
    if mode == 0 or not text:
        return []
    out = []
    toks = tokens(text)
    stripped = [strip_marks(t[1]) for t in toks]
    for i, st in enumerate(stripped):
        if st in words:
            idx = words[st]
            sev = RULES[idx][1]
            if sev == "S" or mode == 2:
                out.append((toks[i][0], toks[i][0] + len(toks[i][1]), idx))
    for i in range(len(stripped) - 1):
        two = stripped[i] + " " + stripped[i + 1]
        if two in pairs:
            idx = pairs[two]
            sev = RULES[idx][1]
            if sev == "S" or mode == 2:
                out.append((toks[i][0], toks[i + 1][0] + len(toks[i + 1][1]), idx))
    out.sort(key=lambda x: x[0])
    return out

# --------------------------------------------------------------------- build
def verify_sides(words, pairs):
    """The wrong-side law (the agreement law, derivation side): every GOOD
    example scans to ZERO nudges (from any rule); every BAD example scans to
    EXACTLY ONE nudge with its own rule. Returns (bad_g, bad_b) refuse lists."""
    bad_g, bad_b = [], []
    for idx, (fam, sev, kind, pat, sug, exp) in enumerate(RULES):
        key = pat if kind == "W" else pat
        g, b = EXAMPLES[key]
        ng = ref_scan(g, 2, words, pairs)
        if ng:
            bad_g.append(pat + " ← " + " ".join(RULES[i][3] for _, _, i in ng))
        nb = ref_scan(b, 2, words, pairs)
        ok = len(nb) == 1 and nb[0][2] == idx
        if not ok:
            detail = " ".join(RULES[i][3] for _, _, i in nb) if nb else "no-match"
            bad_b.append(pat + " ← " + detail)
    return bad_g, bad_b

def build_payload():
    """R rows (authoring order) → P rows → E rows. The canon order is THIS
    order; the committed asset must be byte-identical to the derivation."""
    rows = []
    for fam, sev, kind, pat, sug, exp in RULES:
        rows.append("R\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (rule_id(pat), fam, sev, kind, pat, sug, exp))
    for key, wrong, fixed in PAIRS:
        rows.append("P\t%s\t%s\t%s" % (rule_id(key), wrong, fixed))
    for fam, sev, kind, pat, sug, exp in RULES:
        key = pat
        g, b = EXAMPLES[key]
        rows.append("E\t%s\tG\t%s" % (rule_id(pat), g))
        rows.append("E\t%s\tB\t%s" % (rule_id(pat), b))
    return "\n".join(rows) + "\n"

IDS = {}
def rule_id(key):
    if key not in IDS:
        IDS[key] = "r%02d" % (len(IDS) + 1)
    return IDS[key]

def derive():
    global IDS
    IDS = {}
    # the id assignment must follow the canon order (R rows first) — force it
    for fam, sev, kind, pat, sug, exp in RULES:
        rule_id(pat)
    for key, wrong, fixed in PAIRS:
        rule_id(key)

    # --- mechanical laws at build (any breach refuses the derivation) ---
    words, pairs = build_index()
    seen = {}
    for fam, sev, kind, pat, sug, exp in RULES:
        need(fam in FAMILIES, "family " + str(fam))
        need(sev in ("S", "X"), "sev")
        need(kind in ("W", "P"), "kind")
        need(pat and canon(pat) == pat, "pattern-canonical " + pat)
        need(sug and canon(sug) == sug, "suggest-canonical " + sug)
        need(exp and canon(exp) == exp and "\t" not in exp, "explain-canonical")
        need(pat not in seen, "duplicate-pattern " + pat)
        seen[pat] = True
        if kind == "W":
            need(" " not in pat and pat not in pairs, "word-shape " + pat)
            need(pat != sug, "self-suggest " + pat)
        else:
            need(pat.count(" ") == 0 and "-ماض" in pat, "pair-key-shape " + pat)
    for key, wrong, fixed in PAIRS:
        need(key in seen, "pair-key-unknown " + key)
        need(wrong.count(" ") == 1 and fixed.count(" ") == 1, "pair-shape " + wrong)
        need(wrong != fixed and wrong not in words, "pair-shape " + wrong)
        need(wrong not in pairs or pairs[wrong] is not None, "")
    # the pair patterns themselves are unique
    pset = [w for _, w, _ in PAIRS]
    need(len(pset) == len(set(pset)), "duplicate-pair")
    # every pattern has its two examples, and the sides are RIGHT (the
    # agreement law — derivation side)
    for fam, sev, kind, pat, sug, exp in RULES:
        key = pat
        need(key in EXAMPLES, "missing-examples " + pat)
        g, b = EXAMPLES[key]
        need(g and b and g != b, "example-shape " + pat)
    bad_g, bad_b = verify_sides(words, pairs)
    if bad_g:
        print("NUDGERULES_REFUSED wrong-side-G: %s" % " | ".join(bad_g[:8]))
        sys.exit(1)
    if bad_b:
        print("NUDGERULES_REFUSED wrong-side-B: %s" % " | ".join(bad_b[:8]))
        sys.exit(1)

    n_rules = len(RULES)
    n_pairs = len(PAIRS)
    n_examples = 2 * len(RULES)
    payload = build_payload()
    crc = zlib.crc32(payload.encode("utf-8")) & 0xFFFFFFFF
    asset = (
        FORMAT + "\n"
        + "made\t%s\n" % MADE
        + "source\t%s\n" % SOURCE
        + "crc32\t%08x\n" % crc
        + "rules\t%d\n" % n_rules
        + "examples\t%d\n" % n_examples
        + "pairs\t%d\n" % n_pairs
        + "---\n"
        + payload
    )
    return asset.encode("utf-8"), n_rules, n_pairs, n_examples

def need(ok, what):
    if not ok:
        print("NUDGERULES_REFUSED %s" % what)
        sys.exit(1)

ASSET = "assets/grammar_rules.txt"

def main():
    asset, nr, np_, ne = derive()
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        cur = open(ASSET, "rb").read() if os.path.exists(ASSET) else b""
        if cur != asset:
            print("NUDGERULES_CHECK_FAILED non_deterministic (derived != committed)")
            sys.exit(1)
        print("NUDGERULES_CHECK_OK bytes=%d rules=%d pairs=%d examples=%d"
              % (len(asset), nr, np_, ne))
        return
    if len(sys.argv) > 1 and sys.argv[1] == "--testset":
        words, pairs = build_index()
        text = open("assets/testset_grammar.txt", encoding="utf-8").read()
        toks = tokens(text)
        hits = ref_scan(text, 2, words, pairs)
        rate = (len(hits) * 100.0 / len(toks)) if toks else 0.0
        print("NUDGERULES_TESTSET words=%d nudges=%d rate=%.1f bound=2.0"
              % (len(toks), len(hits), rate))
        for st, en, idx in hits:
            print("HIT " + text[st:en] + " → " + RULES[idx][4])
        if hits:
            sys.exit(1)
        return
    with open(ASSET, "wb") as f:
        f.write(asset)
    print("GRX1_WRITTEN bytes=%d rules=%d pairs=%d examples=%d"
          % (len(asset), nr, np_, ne))

if __name__ == "__main__":
    main()
