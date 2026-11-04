# مخازن التدريب — `tests/ks/` (الأسبوع 28، المرحلة 5 W3)

ستة مخازن مفاتيح **مولَّدة للتمرين فقط** (KSDRILL) — لا علاقة لها بالخزنة
الحقيقية إطلاقاً؛ كلماتها موثقة هنا **عمداً**: تدريب بكلمة سرّية يهزم التدريب.
السر الحقيقي (`.secrets/neokey.keystore` + عبارة استعادته) لا يغادر خزنة
المالك أبداً، والمتحقق `tests/com/hark/neokey/KsValidator.java` هو الصنف
نفسه الذي يشغّله المالك على أرتقفه المستعاد بعد الاستعادة من النسخة المشفرة.

## التوليد (مرة واحدة، موثق لا مخفي)

```bash
PW=drill-pass-2026
DN="CN=NeoKey Drill Release, OU=Hark, O=NeoKey, L=Riyadh, C=SA"
A=neokey-drill
# المرجع (بصمته هي المرساة المثبتة في KsDrillGate):
keytool -genkeypair -keystore tests/ks/drill-release.p12 -storetype PKCS12 \
  -alias $A -keyalg RSA -keysize 2048 -validity 7300 \
  -storepass $PW -keypass $PW -dname "$DN"
# المغترب (نفس الاسم وdname — مفتاح مختلف → fp_mismatch):
keytool -genkeypair -keystore tests/ks/drill-rogue.p12  -storetype PKCS12 \
  -alias $A -keyalg RSA -keysize 2048 -validity 7300 \
  -storepass $PW -keypass $PW -dname "$DN"
# الضعيف (RSA 1024 → key_size):
keytool -genkeypair -keystore tests/ks/drill-weak.p12   -storetype PKCS12 \
  -alias $A -keyalg RSA -keysize 1024 -validity 7300 \
  -storepass $PW -keypass $PW -dname "$DN"
# المنتهي (بدأ قبل 400 يوم وصلاحيته 365 → validity_expired):
keytool -genkeypair -keystore tests/ks/drill-expired.p12 -storetype PKCS12 \
  -alias $A -keyalg RSA -keysize 2048 -startdate "-400d" -validity 365 \
  -storepass $PW -keypass $PW -dname "$DN"
# القصير (90 يوماً < أرضية 10 سنوات → validity_short):
keytool -genkeypair -keystore tests/ks/drill-short.p12  -storetype PKCS12 \
  -alias $A -keyalg RSA -keysize 2048 -validity 90 \
  -storepass $PW -keypass $PW -dname "$DN"
# الحاوية الخطأ (JKS → ks_type) — الاسم .p12 عمداً:
#   ملف *.jks سيعضّ تدقيق الأسرار (قاعدة ملفات المخازن خارج .secrets/)،
#   وفحص النوع يقرأ سحر بايتات الحاوية (feedfeed/cececece) لا الاسم.
keytool -genkeypair -keystore tests/ks/drill-jks.p12    -storetype JKS \
  -alias $A -keyalg RSA -keysize 2048 -validity 7300 \
  -storepass $PW -keypass $PW -dname "$DN"
```

## المرساة

بصمة sha256 لشهادة `drill-release.p12` (فوق DER الشهادة — نفس اشتقاق دبابيس
TLS وبيانات DictSign):

```
a80e71b9a5d1554619afe7853b6ef4b20fbbcc05d6e9f4f73a9c98d04092f587
```

مثبتة حرفياً في `KsDrillGate.EXPECTED_DRILL_FP` («المرجع = المنشور»): تبديل
الملف يكسر المرساة من أول تشغيل ولو كان البديل مخزناً سليماً تماماً.

## القاعدة

هذه الملفات أرتقف اختبار عامة بمحتوى معلن — لا تضع هنا أبداً مخزناً حقيقياً
ولا عبارة استعادة حقيقية (تدقيق الأسرار يصطاد الأنماط وملفات `*.jks`/
`*.keystore` خارج `.secrets/`).
