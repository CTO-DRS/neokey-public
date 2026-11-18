# SECURITY_REPORT.md — الأمن (W132)

## خريطة الخروج الشبكي (egress) — بعد الإصلاح: كل موقع منبوّا
كل `new URL|openConnection|Socket` في src/ = **3 مواقع بالضبط**، كلها الآن خلف بوابة NetGate (فشل مغلق: master off + إذن القدرة off افتراضياً + قائمة بيضاء حرفية بالبادئة + تدقيق بلا نص مستخدم):

1. **المزامنة المشفرة** — SyncRestTransport.java:134 → `https://sync.neokey.app/v1/` — بوابتان + دبابيس SPKI داخل المصافحة (PinnedTls.PinTrustManager يرمي قبل أي بايت) — مغلقة فعلياً حتى نشر دبابيس المالك (https_unpinned).
2. **تحسين الصياغة (OpenRouter)** — WriterActivity.java:499 — مفتاح **المستخدم نفسه** (لا مفتاح مضمن)، من حقل password، يُرسل نص المسودة فقط، والسياق من لوحة المفاتيح يُقطع في الحقول الحساسة (NeoKeyService.java:788–791).
3. **الترجمة** — NeoKeyService.translateSend — بعد إصلاح W132 أصبحت خلف `CAP_TRANSLATE` بنفس العقد (كانت F1).

## المفاتيح والأسرار
- لا مفتاح API مضمن في الشجرة (فحص `sk-`/`Bearer`/`apikey` — البصمة الوحيدة placeholder في hint).
- مفتاح OpenRouter الآن **مختم سكونياً** (KeyGuard AES-256-GCM، AndroidKeyStore API 23+) — النص الصريح لا يُكتب بعد W132، والقديم يُرحَّل ويُمسح عند أول قراءة/حفظ.
- أسرار التوقيع خارج git إجمالاً (.secrets/ + env fail-fast)؛ كلمات مخازن التدريب معلنة عمداً كمواد تدريب (tests/ks/README.md).

## الخصائص المثبتة بالكود
- صفر تسجيل logcat في src/ (grep `android.util.Log|Log.[diwev]` = صفر) — النص المكتوب لا يصل logcat.
- CrashReport: أرقام فقط + `Class.method:line` منقّى (sanitizeWhere) — **لا رافع رفع إطلاقاً** في هذه النسخة (CrashReport.java:13–14) — الخروج الوحيد mailto بإجراء المستخدم.
- OTPShield: يخزن سطور تدقيق بلا محتوى الرمز، والمسح المؤجل يمسح صف clips.db ويفرغ حافظة النظام فقط إذا ما زالت تحمل النص نفسه.
- BankWatch: `canRetrieveWindowContent="false"` — يقرأ اسم الحزمة الأمامية فقط (13 بنك سعودي معروف + heuristic)، افتراضي مغلق، ومقيد زمنياً 1/دقيقة/تطبيق.
- الصلاحيات 3 فقط: VIBRATE/RECORD_AUDIO/INTERNET — والمكوّنان المصدران محميان بBIND_INPUT_METHOD/BIND_ACCESSIBILITY_SERVICE.
- TLS: https بلا دبابيس يُرفض في المزامنة؛ http مسموح فقط لـ127.0.0.1 (ساحة الاختبار).

## ما لم يُختبر هنا (صدق كامل)
- اختبار Keystore الجهازي وتحصينات الجهاز الحي (STRONGBOX/TEE) — **BLOCKED: لا جهاز في هذه البيئة**.
- اختبار اختراق شبكي حي ضد خادم حقيقي — **NOT TESTED** (بنية الاختبار موجودة: TlsGate/SpkiPinsTest على JVM).

## الحكم
لا مشكلات P0 أمنية. الدورة الكاملة للمفاتيح (توليد/استخدام/محو/نسخ احتياطي) مراجعة وموثقة؛ الاستثناء الجهازي موثق بصدق.
