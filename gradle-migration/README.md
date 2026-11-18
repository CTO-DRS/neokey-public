# هجرة Gradle — مُفعَّلة (W133) على مسارين متوازيين، والتحويل الكامل لـ CI يبقى موثقًا لا منفذًا

خارطة الطريق (المرحلة 0، البند B) تنص على هجرة البناء إلى «مجلد مشروع Gradle قياسي يلف نفس مصادر جافا الحالية بلا تغيير منطقها». هذا المجلد هو ذلك التغليف. **في W133 أُنجزت خطوتا التفعيل الأولى والثانية بأدلة حية، وبُني المخرَجان Release/Debug فعليًا عبر Gradle**؛ خط البناء اليدوي (`build.sh` + `build_aab.sh` + `ci/pipeline.sh`) يبقى المعتمد حتى إتمام الخطوة 3 (تحويل CI).

## ما الذي يفعله هذا التغليف؟
- `build.gradle` يلف **نفس** المجلدات الحالية (`../src`, `../res`, `../assets`, `../AndroidManifest.xml`) — لا نقل ملفات، لا تغيير منطق.
- `versionCode/versionName` يُقرآن من `AndroidManifest.xml` (مصدر الحقيقة الوحيد، كما في الخط اليدوي بعد إصلاح الأسبوع 3).
- توقيع الإصدار من خزنة `.secrets/` بنفس التخطيط الذي يقرؤه `build.sh` — لا أسرار في الملفات. (W133: `keyAlias` يُقرأ من `NEOKEY_KEY_ALIAS` في ملف الخزنة أولًا بقيمة افتراضية `neokey` — نفس ترتيب التحميل في `build.sh`، فيعمل بديل التدريب `neokey-drill` دون تلفيق أي مفتاح.)

## خطوات التفعيل — الحالة الحية بعد W133

### 1. الـ wrapper — **منجَز (W133)** بأدلة حية
```bash
gradle wrapper --gradle-version 9.6.0   # الحد الأدنى لـ AGP 9.5.1 — Gradle 9.6.0 موجود فعليًا على services.gradle.org
```
المخرجات مُcommitted: `gradlew` + `gradlew.bat` + `gradle/wrapper/gradle-wrapper.{jar,properties}` (distributionUrl = gradle-9.6.0-bin.zip). أول تشغيل لـ `./gradlew` ينزّل AGP 9.5.1 من maven.google.com — **تحقق حي: `com.android.tools.build:gradle:9.5.1` موجود فعليًا في فهرس maven** (W130 كان قرارًا موثقًا؛ W133 أثبت وجوده بالتنزيل).

### 2. المانيفست — **منجَز (W133) لكن بخلاف ما كُتب هنا سابقًا** — التصحيح التجريبي:
- **المزعوم القديم كان خاطئًا**: «AGP 8/9 يرفض `package=`» — **الاختبار الحي مع AGP 9.5.1 + Gradle 9.6.0 أثبت العكس**: المانيفست يحتفظ بـ `package="com.hark.neokey"` والدمج ينجح ما دام الـ namespace مطابقًا له. **لم تُلمس `package=` إطلاقًا** — وهي تبقى مصدر بادئة الحزمة التي تقرأها بوابات signedapk/uploadbundle/build_r8 من المانيفست المصدر.
- **المانع الحقيقي كان `<uses-sdk>`** (رفض صريح من AGP 9.0+: «these attributes have been deprecated within the manifest»). أُزيل من `AndroidManifest.xml` (W133): إصدارات SDK تُعلن في `build.gradle` (minSdk 21 / targetSdk 36)، والخط اليدوي كان يحقنها أصلًا عند `aapt2 link` (‎--min-sdk-version 21 ‎--target-sdk-version 36) — **المانيفست الثنائي لم يتغير في أي من المسارين**.
- **إثبات عدم الانحدار**: `bash build.sh` بعد التعديل → `BUILD_OK` (توقيع drill موثق) + badging مطابق: `com.hark.neokey vc=3 vn=0.3.0`.

### 3. التوازي — **الأول منفذ (W133)**، التحويل الكامل لـ CI موثق لا منفذ
- **Gradle**: `./gradlew assembleRelease` → `NeoKey-release.apk` موقّع v1+v2 (بشهادة drill)، badging `com.hark.neokey vc3 0.3.0` مطابق للخط اليدوي بايت-معنى. `assembleDebug` → `com.hark.neokey.debug`.
- **اليدوي**: `build.sh` → `NeoKey-2036.apk` بنفس البادجة. SHA-256 للملفين مختلفة (ترتيب zip/طوابع زمن مختلفة — كما توقع هذا الملف سابقًا)؛ **بصمة الشهادة واحدة** (drill `a80e71b9a5d15546…` — بديل التدريب الموثق).
- **البوينت AAB**: `build_aab.sh` — كانت مسجلة BLOCKED؛ في W133 بُني حزمة حقيقية `NeoKey.aab` (6,224,247 بايت) و validate عبر bundletool 1.18.3 نجح، وبُغلِتن خلطان كامنان كانا سيفشلان الخط على أي جهاز SDK-ready: `-storepass:env` (صيغة apksigner لا يقبلها jarsigner) والمسار النسبي للخزنة بعد `cd build/aab` (كلاهما موثقان في AUDIT_REPORT.md F12/F13).
- **التبقي (موثق، بلا ادعاء)**: جعل CI يستخدم `gradle assembleRelease` (خطوة تحويل workflow) — لا يُنفذ قبل موافقة المالك على عزل المسارين، والخط اليدوي يبقى خيار الطوارئ.

## متطلبات البيئة (كما تُثبت في W133)
- JDK 17+ حقيقي بـ `javac` (JRE لا يكفي: Gradle toolchain يرفض `does not provide the required capabilities: [JAVA_COMPILER]`) — CI يستخدم temurin 21.
- Android SDK 36: `platforms/android-36` + `build-tools/36.0.0` + licenses مقبولة.
- الشبكة: services.gradle.org + dl.google.com + repo1.maven.org (أول تشغيل فقط).

## لماذا بقيت الخطوة 3 غير منفذة في CI؟
- المسار اليدوي هو المرجع الذي تُشتق منه كل الدبابيس (badging، الشهادة، Dex budget، بيان signedapk) — تحويل CI يغيّر مصدر الأرتِفْت ويستوجب تدقيق دبابيس جديدًا موافقة المالك، لا قراءةً هندسية صامتة.
