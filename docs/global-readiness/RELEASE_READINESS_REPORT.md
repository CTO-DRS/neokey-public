# RELEASE_READINESS_REPORT.md — جاهزية الإصدار (W132)

## معايير القبول (§18 من التوجيه) — الحالة الواحدة-واحدة
| المعيار | الحالة |
|---|---|
| لا مشكلات أمنية P0 | **PASS** (صفر P0؛ P1 الثلاثة أُغلقت) |
| لا تعارض خصوصية↔تنفيذ | **PASS** (كل egress منبوّا؛ privacy.html مطابقة) |
| بناء Release APK | **PASS عبر خط المشروع**: SignedApk يبني APK كاملاً v1+v2 من الشجرة الحية (SIGNEDAPKGATE_OK checks=14 negatives=6/6؛ sha256 متحكم به) — التوقيع بمخزن drill الموثق؛ توقيع الإنتاج بمفتاح المالك فقط. **W133: مسار Gradle الثاني مُثبت حيًا** — `./gradlew assembleRelease` (Gradle 9.6.0 + AGP 9.5.1) → `NeoKey-release.apk` بادجته مطابقة للخط اليدوي بايت-المعنى (`com.hark.neokey` vc3 0.3.0، v1+v2، شهادة drill `a80e71b9a5d15546…`) و`assembleDebug` → `com.hark.neokey.debug` |
| بناء AAB | **PASS (W133)** — كان BLOCKED على غياب SDK+bundletool؛ حُلّت البيئة (SDK 36 + bundletool 1.18.3) وانكشف عطلان كامنان في `build_aab.sh` نفسها (`-storepass:env` صيغة apksigner لا يقبلها jarsigner — والمسار النسبي للخزنة بعد `cd build/aab`) — أُصلحا (AUDIT F12/F13) وبُني حزمة حقيقية موقعة: `NeoKey.aab` ‏6,224,247B — `bundletool validate` أخضر — dump manifest: `com.hark.neokey` vc3 0.3.0، minSdk21/targetSdk36، 3 صلاحيات — sha256 `f90c35e46ba5eadb…` — التوقيع drill الموثق |
| الاختبارات المطلوبة | **PASS** (2483/0 + phase8/phase10) |
| إثبات لوحة المفاتيح على أجهزة حقيقية | **BLOCKED** — لا جهاز/محاكي في هذه البيئة (الوحدة النقية + البنية IME مغطاة بالكود) |
| إثبات جودة المحركات | **PASS (JVM)** — مجموعات testset + golden يعاد إثباتها كل تشغيل |
| استقرار الحافظة | **PASS (JVM)** — 93+124 فحص حية + OTP wipe |
| توافق النوافذ المستهدفة | **PARTIAL** — minSdk21/targetSdk36 مثبت بالبناء؛ النطاق الحي NOT TESTED |
| توثيق الأداء | **PASS** (قياسات طازجة + ميزانيات مفروضة) |
| التراخيص | **PASS** — صفر اعتماديات خارجية في الشحن (pure Java + assets مفتوحة المصدر الموثقة: WMT News Crawl ar) |
| مراجعة متطلبات Play | **PARTIAL** — targetSdk/السياسة/الإفصاح جاهزة؛ AAB+رفع فعلي للمالك |
| نسخة احتياطية وخطة تراجع | **PASS** — git + drill «الإثبات من لا شيء» (استعادة موثقة) + نظام النسخ اليدوي المشفّر |
| تقارير نهائية بأدلة | **PASS** — هذا المجلد |

## لا نشر إنتاجي دون موافقة صريحة (قاعدة 12)
- لم يُرفع tag إصدار جديد، ولا لم يُبنى AAB، ولا لم يُعدل Play — الكود فقط في main (neokey-new + neokey-public).

## عوائق النشر المتبقية (بالمالك)
1. توقيع الإنتاج بمخزن الخزنة (never leaves vault).
2. نشر دبابيس SPKI للمزامنة (NetGate.setSyncPins) — وإلا تبقى المزامنة مغلقة فشل-مغلق.
3. بناء AAB ورفعه من CI (release.yml جاهزة بأسرار مستندة).
