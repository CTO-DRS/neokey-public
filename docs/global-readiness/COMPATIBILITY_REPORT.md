# COMPATIBILITY_REPORT.md — التوافق (W132)

## النوافذ المدعومة (من الأصل والبناء)
- **minSdk 21** / **targetSdk 36** (إلزام Play للتحديثات منذ 2026-08-31) — من AndroidManifest + build scripts + aapt2 link في الخط (موثق باختبار badging).
- نطاق Android API المطلوب للفحص: **26 حتى 36** — الحالة: JVM-only مثبت؛ الجهاز الحي عبر النطاق **NOT TESTED (بلا جهاز)**.
- مسارات كود مشروطة بالإصدار (موثقة في الكود): AndroidKeyStore عبر KeyGenParameterSpec يتطلب **API 23+** (21–22 سقوط موثق لملف خاص)، islamicCalendar يتطلب API 24+ (W100/W105 مع سقوط آمن).
- عقدة minSdk 21: java.util.Base64 غير متاح على 21–25 → كل b64 في المشروع نقي Java (نفس السبب في KeyGuard).

## الأجهزة (بلا ادعاء)
ضعيف/متوسط/رائد: غير قابل للقياس هنا — الميزانيات الجهازية موجودة ومفروضة برمجياً (Perf.LIMITS) وتُقاس من شاشة Perf الحية.

## متطلبات Play
targetSdk 36 + AAB للإصدار النهائي + سياسة خصوصية + إفصاح بيانات — البنية جاهزة؛ AAB الفعلي يتطلب SDK/bundletool (BLOCKED هنا) — موثق في RELEASE_READINESS.
