# حزمة نشر خادم المزامنة المرجعي — `neokey-sync-server.jar`

المرحلة 5، الأسبوع 27. الجرة المستقلة التي يشغّلها مشغل النشر فوق **نفس منطق
العقد المرجعي** الذي ثبتت بوابتا `RESTGATE` و`TLSGATE` عقدَه على السلك — لا
نسخة موازية. بلا تبعيات، هدف Java 8، صفر كود تطبيق (deploy/ وtests/ خارج dex
التطبيق بالتصميم — لوحة المفاتيح لا تفتح مقبس استماع أبداً).

## البناء

```bash
ci/build_deploy.sh   # → build/deploy/neokey-sync-server.jar
```

## البصمة المرساة (المرجع = المنشور)

```bash
java -jar neokey-sync-server.jar --print-spki --keystore server.p12 --password ****
# SYNC_SERVER_SPKI e67e815c…a395
```

البصمة تُضبط مرة واحدة عند النشر في العميل: `NetGate.setSyncPins("<بصمة>")` —
وتدوير شهادة الخادم = طباعة البصمة الجديدة وتحديث الدبابيس المعتمدة.

## التجهيز والدوران (الأسبوع 30 — الإجراء الآلي)

قبل ضبط الدبابيس قرار واحد يجب أن يُتخذ: هل سلسلة شهادات الخادم صالحة
للنشر على النطاق المعلن؟ الأداة `Provision` (نقي JVM — تعمل على أي JDK،
وهي الصنف نفسه الذي تتحقق به بوابة `ci/provision_gate.sh`):

```bash
# القرار + الاشتقاق + بيان التجهيز (CA بـ PEM أو DER):
java -cp <classes> com.hark.neokey.Provision server.p12 '<pass>' sync sync.example.com \
  --ca ca.pem --statement provision-statement.json
# PROVISION_OK domain=sync.example.com pin=<64hex> pin16=<16hex> days_left=… chain=… emitted="NetGate.setSyncPins("<pin>")"

# فشل بوسم حرفي واحد: PROVISION_FAIL san_mismatch | ca_mismatch | validity_expired | …
```

الفحوص بترتيب حتمي: الحاوية PKCS12 (بسحر بايتاتها لا برأي الـJDK)، المفتاح
الخاص حاضر، السلسلة [leaf, CA] بترتيبها وتواقيعها، الذيل CA (و= CA المشغّل
المتوقعة عند تمرير `--ca`)، الـ SAN يطابق النطاق (dNSName أو iPAddress)،
الصلاحية فوق أرضية 30 يوماً، والدبوس يُشتق باشتقاق واحد (`SpkiPins.pinOf`).

**الدوران:** تجديد الشهادة بنفس المفتاح = الدبوس نفسه (العملاء لا يعيدون
شيئاً)؛ دوران المفتاح = دبوس جديد — العميل المثبت بالدبوس القديم يُرفض
**داخل المصافحة** (`spki_not_pinned`) حتى ضبط الدبوس الجديد ثم تعود
الجولة خضراء. البوابة تُتمرّن على بدائل ملتزمة (`tests/provision/`) وتثبت
الطبقة كاملة (تدريب/سلبية/إثبات سلبي محفوظ).

## الخدمة

```bash
java -jar neokey-sync-server.jar --keystore server.p12 --password **** --port 443
# SYNC_SERVER_READY port=443 host=127.0.0.1 spki=e67e815c…
```

- سطر الجاهزية واحد على stdout ثم الصمت — سجل الطلبات أدوات المشغل (journald).
- **الربط الافتراضي loopback**؛ كشف واجهة عامة قرار صريح: `--host 0.0.0.0`
  خلف TLS-terminating proxy أو مباشرة.
- كل خطأ قبل سطر الجاهزية = `DEPLOY_REFUSED <وسم>` ورمز خروج غير صفري
  (`keystore_load` عبارة/ملف، `bad_port`، `unknown_arg`، `missing_arg`،
  `keystore_no_key`) — لا يُشغَّل منفذ ولا تُطبع جاهزية أبداً.

## فحص الحي (العقد نفسه هو المسبار)

```bash
java -jar neokey-sync-server.jar --check https://127.0.0.1:443/v1/ \
     --pin e67e815c… --anchor server-cert.pem
# SYNCH_HEALTH_OK base=https://127.0.0.1:443/v1/ code=404 latency_ms=12
# SYNCH_HEALTH_FAIL spki_not_pinned  (قناة بمفتاح مغترب — مرفوضة)
```

لا نقطة نهاية جديدة: مسبار الفحص هو `GET /v1/account/healthprobe0/salt`
المتوقع **404** (الرمضاء الرمادي للعقد = الخادم حي) فوق قناة مثبتة SPKI —
`--pin` إلزامي، و`--anchor` لشهادة ذاتية-التوقيع (النشر الحي بشهادة CA
تجارية يكفي النظام + الدبوس).

## systemd (قالب)

راجع `neokey-sync-server.service` — `Restart=on-failure`، `NoNewPrivileges`،
`ProtectSystem=strict`، والعبارة من `EnvironmentFile` بمقارنة صلاحية 600.

## ما يبقى خارج هذه البيئة موثقاً

نطاق حقيقي + شهادة CA تجارية، تشغيل فعلي خلف DNS، ومراقبة خارجية — العقد
والثقة والحزمة وفحص الحي مثبتة محلياً ببوابة `DEPLOYGATE_OK`.
