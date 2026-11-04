# مخازن تدريب التجهيز — `tests/provision/` (الأسبوع 30، المرحلة 6 W1)

سلاسل شهادات **مولَّدة للتمرين فقط** (PROVISION) — لا علاقة لها بأي نطاق أو
CA تجاري حقيقي إطلاقاً؛ كلماتها موثقة هنا **عمداً**: تدريب بكلمة سرّية يهزم
التدريب. الشريحة المحلية من المعلق الموثق «نشر خادم مزامنة حي — ما يبقى:
النطاق، شهادة CA تجارية، وضبط الدبابيس عند `NetGate.setSyncPins`» (نص
`docs/PHASE5-W4-REPORT.md` §5): **إجراء التجهيز والدوران** يُتمرَّن هنا على
بدائل ملتزمة، بينما القرار على الشهادة الحقيقية يبقى للمشغّل بالأداة نفسها
(`tests/com/hark/neokey/Provision.java` — الصنف نفسه الذي يشغّله المشغّل على
السلسلة الحقيقية عند النشر).

## التوليد (مرة واحدة، موثق لا مخفي — `scripts/gen_provision_fixtures.sh`)

```bash
PW=prov-pass-2026
# CA المشروع المحلي (يوقّع كل شيء محلي):
keytool -genkeypair -keystore tests/provision/ca.p12 -storetype PKCS12 -alias nk-ca \
  -keyalg RSA -keysize 2048 -validity 7300 -storepass $PW -keypass $PW \
  -dname "CN=NeoKey Provision Test CA, OU=Hark, O=NeoKey, L=Riyadh, C=SA" -ext bc=ca:true
# ورقة الخادم المرجعية: مفتاح + CSR + توقيع CA + استيراد السلسلة [leaf, CA]:
keytool -genkeypair -keystore server.p12 ... -ext san=dns:sync.neokey.test
keytool -certreq ... && keytool -gencert -keystore ca.p12 -validity 730 -ext san=dns:sync.neokey.test
keytool -importcert -alias nk-ca -file ca.cer && keytool -importcert -alias sync -file sync.cer
# التجديد: CSR جديد من نفس المفتاح (نفس المخزن منسوخاً) بصلاحية أطول → نفس الدبوس بالضرورة
# الدوران: مفتاح جديد بنفس الاسم والـ SAN → دبوس جديد بالضرورة
# السلبيات: wrongdom (SAN مغترب) / expired-leaf (gencert -startdate -400d) /
#           foreign (CA مغترب ca2) — كل واحدة تعضّ بوسمها الحرفي
```

## الملفات

| الملف | الدور |
|---|---|
| `ca.p12` | CA التدريب (`nk-ca`, RSA-2048, 20 سنة, `bc=ca:true`) — يوقّع السلاسل المحلية |
| `ca2.p12` | CA مغترب ثانٍ — لتوقيع `foreign.p12` (سلسلة سليمة شكلاً، CA خطأ → `bad_chain_sig`) |
| `server.p12` | **المرجع**: ورقة `sync` + سلسلة [leaf, CA]، SAN `dns:sync.neokey.test`، 730 يوماً |
| `renew.p12` | التجديد: **نفس المفتاح**، شهادة CA جديدة 900 يوماً → الدبوس نفسه (`rotation.stable`) |
| `rotate.p12` | الدوران: **مفتاح جديد** بنفس الاسم والـ SAN → دبوس مختلف (`rotation.rotated`) |
| `wrongdom.p12` | SAN `dns:other.neokey.test` → `san_mismatch` |
| `expired-leaf.p12` | سلسلة CA سليمة، الشهادة منتهية منذ ~35 يوماً → `validity_expired` |
| `foreign.p12` | موقّعة من CA2 → `bad_chain_sig` |

## الربط بالمرساة

المرساة أول الحواجز (قاعدة «المرجع = المنشور»): تشغيل `Provision` على مخزن
الحلبة المنشور `tests/tls/arena-server.p12` (alias `nkarena`، self-signed،
SAN IP:127.0.0.1) يجب أن يعيد **الدبوس المثبت حرفياً** في `TlsGate
.EXPECTED_ARENA_PIN` — نفس الاشتقاق الواحد (`SpkiPins.pinOf`). تبديل ملف
الحلبة يكسر المرساة من أول تشغيل ولو كانت الشهادة البديلة صالحة تقنياً.
