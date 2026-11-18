# نيو كيبورد / NeoKey

لوحة مفاتيح عربية لأندرويد — Java خالص، خط بناء مخصص مع نظام إثبات هندسي
من 44+ مرحلة (gates + negative proofs + rebuild drill).

**الإصدار الحالي: [`v0.3.0`](VERSION.md) — versionCode `3` — versionName `0.3.0`**
أول إصدار متكامل موحد (2026-11-09) بعد دمج أنظمة الترقيم الثلاثة القديمة — سلسلة الأدوات كاملة محدثة منذ W130 (r8 9.5.33، apksig 9.5.1، aapt2 9.5.1، android.jar platform-36_r02، targetSdk 36).

---

## البنية

| المسار | المحتوى |
|---|---|
| `src/com/hark/neokey/` | 65 ملفًا مصدريًا: `NeoKeyService`، الحافظة، الثيمات، القاموس، المزامنة، الفوترة، `NQF`… |
| `ci/` | خط الإثبات: `pipeline.sh` + بوابات `*_gate.sh` + سكربتات الإثبات السلبي `*-negative-proof.*` |
| `tests/com/hark/neokey/` | أكثر من 2,450 اختبارًا (JVM خالص بلا `android.*` في وحدات البوابات) |
| `docs/` | تقارير المراحل 1–10 والأسابيع 1–45 + `NQF.md`، `SECURITY.md`، `R8.md`، `STABILITY.md`… |
| `play/` | أصول المتجر: الأيقونة، اللقطات، الفيرو الترويجي، listing، برنامج التجربة |
| `deploy/` | خادم المزامنة (`DeployMain` + وحدة systemd) |
| `res/` + `assets/` | موارد الواجهة + القاموس المطبوع (134,188 كلمة) |
| `evidence/` | أرشيف إثباتات البوابات الأسبوعية (15–21): سجلات، قياسات، خطط نشر |
| `archive/patches/` | الباتشات التراكمية التاريخية (الأسابيع 1–21) |
| `docs/handover/` | حزمة التسليم التاريخية (2026-11-09) + بروتوكول الاستعادة — ابدأ من `PROVENANCE.md` |

## البناء

```bash
./build.sh          # خط الإنتاج: aapt2 + ecj + r8 + توقيع (الأدوات مثبتة في tools/)
ci/pipeline.sh      # خط الإثبات الكامل — 44+ مرحلة، الخروج 0 فقط إذا كان كل شيء أخضر
```

> سقف dex: `610,000B` (الحالي `599,576`). البيئة المرجعية بلا Android SDK —
> مرحلة البناء `SKIP` موثقة منذ الأسبوع الأول؛ الأجهزة الحية هي التي تبني APK.

## الوثائق الأساسية

| الملف | الغرض |
|---|---|
| [`VERSION.md`](VERSION.md) | الهوية الواحدة + جدول تحويل أنظمة الترقيم القديمة |
| [`CHANGELOG.md`](CHANGELOG.md) | سجل التغييرات من `v0.1.0` |
| [`ROADMAP.md`](ROADMAP.md) | خارطة 12 أسبوعًا حتى `v1.0.0` بمعايير خروج مقيسة (التاريخ والقياسات) |
| [`ROADMAP-V2.md`](ROADMAP-V2.md) | خارطة v2 «العربية العميقة» — الابتكار الشامل من W54 (سار التنفيذ) |
| [`docs/ENGINES.md`](docs/ENGINES.md) | عقد المحركات الموحد W55 (fail-closed) + أرضية الأداء الحقيقية المثبتة |
| [`docs/ROOT.md`](docs/ROOT.md) | محرك الجذر RootEngine W56 (RIX1 المختوم + الاشتقاق الحتمي + إشارة الجذر + شجرة العائلة) |
| [`docs/TASHKEEL.md`](docs/TASHKEEL.md) | محرك التشكيل TashkeelEngine W57 (RIX2 المختوم + الدرجات الثلاث + منتقي القراءات + تشكيل النص) |
| [`docs/GRAMMAR.md`](docs/GRAMMAR.md) | النحّاخ الخفيف GrammarNudge W58 (GRX1 المختوم + الأوضاع الثلاث + قانون الإزعاج المقاس + تدقيق النص التعليمي) |
| [`CI.md`](CI.md) | مرجع خط الإثبات: كل بوابة وما تحميه |
| [`NOTES.md`](NOTES.md) | دفتر الملاحظات التشغيلي |
| [`HISTORY.md`](HISTORY.md) | السجل الأسبوعي الكامل (الأسابيع 1–45) |

## القوانين الثابتة

1. الهوية من مكان واحد (`AndroidManifest.xml`) وكل النقاط تتحرك معها في كوميت واحد.
2. لا إنجاز بدون حكم آلي أخضر — لا انتحال، لا «نظريًا يعمل».
3. القياسات التاريخية لا تُعاد كتابة أبدًا؛ الجديد يُوثق فوقها.

## Quran text

`assets/quran.txt` is the Tanzil Quran Text (Simple Clean, v1.1), Copyright (C) 2007-2026 Tanzil Project, used verbatim under Creative Commons Attribution 3.0. Source: https://tanzil.net — check updates at http://tanzil.net/updates/
