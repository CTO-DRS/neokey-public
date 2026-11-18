# TEST_REPORT.md — الاختبارات (تشغيل فعلي 2026-11-18)

## النتائج الفعلية (لا ادعاء)
| المشغّل | النتيجة |
|---|---|
| `tests/run.sh` (الجناح النقي JVM) | **1891 passed, 0 failed** |
| خط الإثبات الكامل `ci/pipeline.sh` (41 مرحلة) | **41 PASS, 0 FAIL** (اختبار مرحلة tests = **2483/0**) |
| phase8 closer (إعادة حية: rebuild+evaudit+opverify) | **PHASE8_OK — 0/91 انحدار** |
| phase10 closer | **PHASE10_OK — 2/2 قوانين، 0/54** |
| الاختبارات الجديدة W132 | KeyGuardTest (26) + NetGate translate (+4) + Dictionary english (+6) = **+36** |

## ما أصلحته الاختبارات فعلاً (دورة حية أثناء التنفيذ)
1. جرّم b64 في KeyGuard (b64_char بعد تقليص الحشو) — اكتشفه الاختبار، أُصلح، أعيد التشغيل.
2. توقع اختبار خاطئ (isSealed عبث-الشكل) — صُحح العقد موثقاً.
3. بوابة trim عضّت حرفياتي الطويلة → ترحيل للموارد + دبابيس واعية.
4. بوابة nqfport عضّت الأصل الجديد → MakeGolden conscious repin (34).
5. drill كشف عدم التزام (dex داخلي ≠ خارجي) → التزام ثم إعادة إثبات.

## ما لا يمكن تشغيله هنا (صدق)
- Instrumentation/UI/IME-on-device/accessibility tests: **BLOCKED** — لا جهاز/محاكي في البيئة (لا يوجد adb إطلاقاً). بنية `connectedDebugAndroidTest` غير موجودة في المشروع أصلاً — فراغ موثق لا ادعاء تغطيته.
- PerfBudget الجهازي الحي: NOT TESTED (القيم منصوصة في Perf.LIMITS وتقاس من الشاشة الحية على الجهاز).
