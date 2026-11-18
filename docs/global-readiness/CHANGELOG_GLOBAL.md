# CHANGELOG_GLOBAL.md — سجل التغييرات العالمي

## W133 (2026-11-18) — NEOKEY 1.0 GLOBAL: المرحلة C (البناء الموحد) — Gradle مُفعَّل حيًا + أول AAB حقيقي
- **الـ wrapper منجَز بأدلة حية**: gradle-9.6.0 فعلي من services.gradle.org (وليست رقمًا موثقًا فقط) — `gradlew` + wrapper jar مُcommitted؛ AGP 9.5.1 أُثبت وجوده بالتنزيل الفعلي من maven.google.com ثم بُني به.
- **تصحيح تجريبي للمانيفست**: الرفض الحقيقي في AGP 9 كان `<uses-sdk>` — أُزيل من `AndroidManifest.xml` (الإصدارات تُعلن في build.gradle، والخط اليدوي كان يحقنها عند aapt2 link أصلًا؛ المانيفست الثنائي لم يتغير في أي مسار). **المزعوم القديم «AGP يرفض package=» ثبت خطؤه حيًا** — AGP 9.5.1 يقبلها ما دام الـ namespace مطابقًا، فبقيت بلا لمسة لأن ثلاث بوابات تقرأها من المصدر.
- **أول بناء Gradle حي**: `./gradlew assembleRelease` → `NeoKey-release.apk` موقّع v1+v2، بادجة مطابقة للخط اليدوي (`com.hark.neokey` vc3 0.3.0، label نيو كيبورد) + `assembleDebug` → `com.hark.neokey.debug`. البيئة طلبت JDK 21 حقيقيًا بـ javac (JRE وحده يرفضه Gradle toolchain) — نفس temurin 21 الذي يستخدمه CI.
- **أول AAB حقيقي**: `build_aab.sh` كانت BLOCKED، وحيدها كان يخفي عطلتين كامنتين كانتا ستقتلان الخط على أي جهاز SDK-ready: `jarsigner -storepass:env` (صيغة apksigner لا يقبلها jarsigner — F12) والمسار النسبي للخزنة بعد `cd build/aab` (F13). أُصلحا وبُني وvalidate عبر bundletool 1.18.3: `NeoKey.aab` ‏6,224,247B — sha256 ‏`f90c35e46ba5eadb…` (توقيع drill الموثق، ليس إنتاجًا).
- **عطل اختبار كامن انكشف بالصدفة الصحيحة**: `IntegrityCertTest` الحي كان يفشل كذبًا على أي زوج (APK↔statement) ستال بعد أي بناء خارج خط الإنتاج — شرط identity-display معناه فقط في فرع MATCH — أُصلح منطقُ الاختبار لا عُطِّل (F14). أول بناء build.sh خارج الخط منذ أسابيع هو ما كشفه.
- **الأرقام الحية**: اختبارات 2483/0، dex ‏602,320/610,000 (javac يضغط أضيق من ecj: كان 603,748 على بيئة JRE/ecj)، PHASE8_OK ‏0/91، PHASE10_OK ‏0/54، مراحل الخط PASS (أُعيد إثباتها بعد كل تعديل).
- **لا إصدار إنتاجي** (القاعدة 12): كل التوقيع بديل drill الموثق `tests/ks/drill-release.p12` — لا tag، لا رفع Play، لا لمس للخزنة الإنتاجية.

## W132 (2026-11-18) — NEOKEY 1.0 GLOBAL: المرحلة B (الأمن والخصوصية) + التعدد اللغوي الجاهز
- **بوابة الترجمة**: أداة الترجمة كانت الاتصال الوحيد خارج NetGate → باقة `translate` رسمية (فشل مغلق + موافقة صريحة + حرس نطاق + تدقيق بلا نص).
- **بوابة لغة النموذج العصبي**: منع تسرب العربية للشريط الإنجليزي (Dictionary.hasArabic — مفردات النموذج 9298/9300 عربية مقاسة).
- **allowBackup=false**: إغلاق نسخ المنصة السحابي لبيانات الكتابة (كانت غائبة → true).
- **KeyGuard**: AES-256-GCM مع AndroidKeyStore (API 23+؛ 21–22 ملف خاص موثق) — مفتاح OpenRouter مختم سكونياً + ترحيل صامت + فشل مغلق.
- **بذرة الثنائيات الإنجليزية**: bigram_en_base.txt (143 صفاً) — كانت غائبة وتُبتلع صامتاً.
- **+36 اختباراً** (KeyGuardTest/NetGate/Dictionary english) — المجموع الحي 2483/0.
- **الدبابيس تتحرك معاً**: NQF-GOLDEN 34 (MakeGolden)، TRIM 147، الدبابيس السبعة للبوابات المغلقة، dex ‏603,748/610,000.
- commit: ac20e43 — ممتد على W131 (JDK 21) وW130/W130b (سلسلة الأدوات r8 9.5.33…).

## ما لم يُنفذ هنا (بصدق، بانتظار بيئة/مالك)
- Gradle Wrapper: بنية gradle-migration جاهزة (AGP 9.5.1 يتطلب Gradle 9.6+) — تفعيلها الكامل يتطلب SDK للتحقق الحي.
- AAB، الجهاز الحي، Instrumentation، نشر دبابيس المزامنة، توقيع إنتاجي — موثقة BLOCKED/PARTIAL أعلاه.
