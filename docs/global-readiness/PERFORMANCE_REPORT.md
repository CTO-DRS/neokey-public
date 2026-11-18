# PERFORMANCE_REPORT.md — الأداء (قياس طازج 2026-11-18 على JVM 21 في هذه البيئة)

## القياس الحي الطازج (perfbaseline_gate: PERFBASELINE_OK checks=27 metrics=8 device=7)
| المقياس | المقاس الآن | الميزانية المفروضة | الحالة |
|---|---|---|---|
| dict.load | **588ms** | 1612ms | PASS |
| dict.suggest p50 | **0.264ms** | مشتقة من القاعدة | PASS |
| dict.predict | **0.002ms** | — | PASS |
| nnet.top3 p50 | **1.129ms** | 2.713ms | PASS |
| nnet.top3 p95 | **1.217ms** | 3.824ms | PASS |
| heap | **64.783MB** | 140.5MB | PASS |

القاعدة المثبتة ذاتياً (self-consistent): كل ميزانية = دالة مقاسة (p50×1.5+1 …) — لا أرقام يدوية.
سجل الجهاز (Perf.LIMITS: create≤900/show≤300/key≤20/frame≤50/sugg≤35/ai≤7000/mem≤160) مفروض من شاشة PerfActivity الحية — **قياسات الجهاز الحي NOT TESTED هنا (بلا جهاز)**، والبوابة تقيس من src/Perf.java ضد السجل (anti-rewrite).

## حجم الأرتيفاكت (مقاس في الخط)
- dex (R8 Full Mode): **603,748/610,000** — trim saved=5804، regrown=0.
- APK كامل (أسلوب drill): ~6.1MB (البنية الأخيرة الموقعة قبل W132 = 6,141,820B).

## الاستقرار
- جلسات طويلة: على JVM عبر Suites الكاملة (1891 فحص متتالي بلا تسريب ملاحظ)؛ جهاز حي **NOT TESTED**.
- أثر W132 على الأداء: +0B تخصيص في المسار الساخن (hasArabic فحص O(n) بسيط قبل استدعاء نادر) — القياسات الطازجة أعلاه بعد الإصلاح.
