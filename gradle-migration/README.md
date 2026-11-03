# هجرة Gradle — مُحضَّرة، غير مُفعَّلة بعد

خارطة الطريق (المرحلة 0، البند B) تنص على هجرة البناء إلى «مجلد مشروع Gradle قياسي يلف نفس مصادر جافا الحالية بلا تغيير منطقها». هذا المجلد هو ذلك التغليف، مُعدًّا مسبقًا؛ **خط البناء المعتمد اليوم يبقى اليدوي** (`build.sh` + `build_aab.sh` + `ci/pipeline.sh`) حتى إتمام خطوات التفعيل الثلاث أدناه على جهاز فيه Gradle.

## ما الذي يفعله هذا التغليف؟
- `build.gradle` يلف **نفس** المجلدات الحالية (`../src`, `../res`, `../assets`, `../AndroidManifest.xml`) — لا نقل ملفات، لا تغيير منطق.
- `versionCode/versionName` يُقرآن من `AndroidManifest.xml` (مصدر الحقيقة الوحيد، كما في الخط اليدوي بعد إصلاح الأسبوع 3).
- توقيع الإصدار من خزنة `.secrets/` بنفس التخطيط الذي يقرؤه `build.sh` — لا أسرار في الملفات.

## خطوات التفعيل (على جهاز به JDK 17 + Android SDK 35)
1. **توليد الـ wrapper** (يحتاج ثنائية gradle مرة واحدة):
   ```bash
   cd gradle-migration
   gradle wrapper --gradle-version 8.10.2
   ```
2. **إزالة `package="com.hark.neokey"` من `AndroidManifest.xml`** — AGP 8 يرفضها (الـ namespace معرّف في `build.gradle`). خطوة واحدة في المانيفست، وبعدها يبقى `build.sh` يعمل لأن aapt2 يستنتج الحزمة من `--manifest` مع `--custom-package` إن لزم — اختبر `bash build.sh` مرة بعد التعديل.
3. **التوازي لمدة إصدار واحد**: أبقِ المسارين، وقارن `build/NeoKey-2036.apk` مع `gradle-migration/build/outputs/apk/release/` (بصمة SHA-256 للشهادة واحدة، المحتوى قد يختلف ترتيبيًا). ثم اجعل CI يستخدم `gradle assembleRelease` وارفع الخط اليدوي كخيار طوارئ.

## لماذا لم نُفعّله في CI فورًا؟
- توليد الـ wrapper يتطلب ثنائية gradle + تنزيل AGP — غير ممكن في بيئة التطوير الحالية (JRE فقط، بلا شبكة لـ services.gradle.org في بعض الفترات).
- المسار اليدوي مُثبت ويعمل في CI (job `compile` في `.github/workflows/ci.yml`) — لا فائدة مرجانية من مخاطرة انقطاع الخط قبل اكتمال خطوات التفعيل.
