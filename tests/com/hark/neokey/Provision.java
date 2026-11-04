package com.hark.neokey;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * إجراء تجهيز النشر — المرحلة 6، الأسبوع 1 (الأسبوع 30).
 *
 * الشريحة المحلية القابلة للإغلاق من المعلق الموثق «نشر خادم مزامنة حي بـ TLS —
 * ما يبقى بالضبط: النطاق، شهادة CA تجارية، وضبط الدبابيس عند
 * NetGate.setSyncPins» (docs/PHASE5-W4-REPORT.md §5): إجراء **التجهيز
 * والدوران** يصير آلياً عضّالاً — الأداة تأخذ مخزن الخادم وتقرر بقرار واحد
 * مغلقة الفشل: هل هذه سلسلة صالحة للنشر؟ ثم تشتق الدبوس بالاشتقاق الواحد
 * (SpkiPins.pinOf — نفس اشتقاق بوابة TLS وبيانات DictSign) وتُصدر بيان
 * التجهيز وسطر الضبط الحرفي الذي يضبطه المشغّل عند النشر.
 *
 * فحوص بترتيب حتمي، كل رفض بوسم حرفي واحد:
 *   chain_missing / unreadable_chain (فشل مغلق: أي استثناء غير متوقع = رفض
 *   لا قبول) / chain_type (سحر بايتات الحاوية PKCS12 لا رأي الـJDK) /
 *   alias_missing / no_private_key / no_cert_chain / not_x509 / key_alg /
 *   key_size(<2048) / chain_order / bad_chain_sig / no_ca_anchor / san_mismatch
 *   (dNSName أو iPAddress يطابق النطاق) / not_yet_valid / validity_expired /
 *   validity_short(<30 يوماً — أرضية النشر) / bad_pin_hex / pin_mismatch
 *   (مرساة «المرجع = المنشور» عند تمرير الدبوس المتوقع).
 *
 * نجاح: PROVISION_OK domain=… pin=… pin16=… days_left=… chain=… statement=…
 * فشل:  PROVISION_FAIL <tag> (exit 1).
 *
 * تدريب الدوران (في ProvisionGate): تجديد بنفس المفتاح = الدبوس نفسه
 * (الشفافية — العميل لا يعيد الضبط)، ودوران مفتاح = دبوس جديد يرفضه العميل
 * القديم داخل المصافحة حتى إعادة الضبط ثم تعاد الجولة خضراء.
 *
 * نقي JVM (صفر android.*) — يعمل على أي JDK 8+؛ وهو الصنف نفسه الذي يشغّله
 * المشغّل على السلسلة الحقيقية.
 */
public final class Provision {

    /** أرضية صلاحية النشر: شهادة تبقى أقل من هذا لا تُجهَّز — جدَّدها أولاً. */
    public static final int MIN_DAYS_LEFT = 30;

    /** رفض بوسم حرفي واحد — لا تحليل متسامح ولا غموض. */
    public static final class Refused extends RuntimeException {
        public final String tag;
        public Refused(String tag) { super(tag); this.tag = tag; }
    }

    /** نتيجة تجهيز ناجحة — كل الحقول التي يحتاجها بيان التجهيز والبوابة. */
    public static final class Result {
        public final String domain, pin, issuer, notBefore, notAfter;
        public final long daysLeft;
        public final int chainLen;
        public final boolean selfSigned;
        Result(String domain, String pin, String issuer, String notBefore, String notAfter,
               long daysLeft, int chainLen, boolean selfSigned) {
            this.domain = domain; this.pin = pin; this.issuer = issuer;
            this.notBefore = notBefore; this.notAfter = notAfter;
            this.daysLeft = daysLeft; this.chainLen = chainLen; this.selfSigned = selfSigned;
        }
        public String pin16() { return pin.substring(0, 16); }
        /** سطر الضبط الحرفي — الإجراء الواحد الذي يضبطه المشغّل عند النشر. */
        public String emittedLine() { return "NetGate.setSyncPins(\"" + pin + "\")"; }
    }

    private Provision() { }

    // ---- القرار الواحد -----------------------------------------------------------

    /** تجهيز مخزن خادم: كل الفحوص بترتيبها الحتمي ثم اشتقاق الدبوس. */
    public static Result provision(String ksPath, char[] pass, String alias, String domain,
                                   String expectedPin, boolean allowSelfSigned) {
        return provision(ksPath, pass, alias, domain, expectedPin, allowSelfSigned, null);
    }

    public static Result provision(String ksPath, char[] pass, String alias, String domain,
                                   String expectedPin, boolean allowSelfSigned, byte[] expectedCaDer) {
        // 1: الوجود — ملف غائب = chain_missing قبل أي شيء آخر.
        if (ksPath == null || ksPath.isEmpty() || !Files.exists(Paths.get(ksPath)))
            throw new Refused("chain_missing");
        // 2: سحر الحاوية — PKCS12 تسلسل DER (0x30) ووليس JKS (feedfeed/cececece)
        //    — بايتات الحاوية نفسها لا رأي الـJDK (درس W28: compat يبتلع JKS).
        try (InputStream magic = new FileInputStream(ksPath)) {
            byte[] head = new byte[4];
            int n = 0, r;
            while (n < 4 && (r = magic.read(head, n, 4 - n)) >= 0) n += r;
            boolean jks = n >= 4 && ((head[0] == (byte) 0xfe && head[1] == (byte) 0xed
                                       && head[2] == (byte) 0xfe && head[3] == (byte) 0xed)
                                  || (head[0] == (byte) 0xce && head[1] == (byte) 0xce
                                       && head[2] == (byte) 0xce && head[3] == (byte) 0xce));
            if (n < 4 || head[0] != 0x30 || jks) throw new Refused("chain_type");
        } catch (Refused re) { throw re;
        } catch (Exception e) { throw new Refused("chain_type"); }
        // 3: التحميل — فشل مغلق: أي استثناء = unreadable_chain (عبارة خاطئة/تالف).
        KeyStore ks;
        try (InputStream in = new FileInputStream(ksPath)) {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(in, pass);
        } catch (Exception e) { throw new Refused("unreadable_chain"); }
        return provision(ks, alias, domain, expectedPin, allowSelfSigned, expectedCaDer);
    }

    /** التجهيز على مخزن محمّل (نفس الفحوص بالترتيب نفسه — مسار واحد للقرار).
     *  expectedCaDer: شهادة CA المشغّل المتوقعة (المرجع = المنشور على مستوى
     *  السلسلة) — عند تمريرها يجب أن يكون ذيل السلسلة هو هذه CA بالضبط. */
    public static Result provision(KeyStore ks, String alias, String domain,
                                   String expectedPin, boolean allowSelfSigned) {
        return provision(ks, alias, domain, expectedPin, allowSelfSigned, null);
    }

    public static Result provision(KeyStore ks, String alias, String domain,
                                   String expectedPin, boolean allowSelfSigned, byte[] expectedCaDer) {
        try {
            // 4: الاسم موجود.
            if (alias == null || alias.isEmpty() || !ks.containsAlias(alias))
                throw new Refused("alias_missing");
            // 5: مخزن النشر يحمل المفتاح الخاص — ثقة بلا مفتاح لا تخدم النشر.
            if (!ks.isKeyEntry(alias)) throw new Refused("no_private_key");
            Certificate[] chain = ks.getCertificateChain(alias);
            // 6: السلسلة موجودة.
            if (chain == null || chain.length == 0) throw new Refused("no_cert_chain");
            // 7: أوراق X509 لا غير.
            if (!(chain[0] instanceof X509Certificate)) throw new Refused("not_x509");
            X509Certificate leaf = (X509Certificate) chain[0];
            // 8/9: الخوارزمية والطول — RSA ≥ 2048 (عقد الهوية نفسه).
            if (!"RSA".equalsIgnoreCase(leaf.getPublicKey().getAlgorithm()))
                throw new Refused("key_alg");
            RSAPublicKey rk = (RSAPublicKey) leaf.getPublicKey();
            if (rk.getModulus().bitLength() < 2048) throw new Refused("key_size");
            boolean selfSigned = chain.length == 1;
            if (!selfSigned) {
                // 10: الترتيب — مصدر كل حلقة = موضوع الحلقة التالية.
                for (int i = 0; i + 1 < chain.length; i++) {
                    X509Certificate c = (X509Certificate) chain[i];
                    X509Certificate up = (X509Certificate) chain[i + 1];
                    if (!c.getIssuerX500Principal().equals(up.getSubjectX500Principal()))
                        throw new Refused("chain_order");
                }
                // 11: التواقيع — كل حلقة موقعة فعلاً بمفتاح الحلقة الأعلى.
                for (int i = 0; i + 1 < chain.length; i++) {
                    try { chain[i].verify(chain[i + 1].getPublicKey()); }
                    catch (Exception e) { throw new Refused("bad_chain_sig"); }
                }
                // 12: مرساة السلسلة — الذيل CA فعلاً (bc حاضر).
                X509Certificate tail = (X509Certificate) chain[chain.length - 1];
                if (tail.getBasicConstraints() < 0) throw new Refused("no_ca_anchor");
            } else if (!allowSelfSigned) {
                // التوقيع الذاتي قرار صريح (الحلبة/loopback) — لا يمرص صمتاً.
                throw new Refused("no_ca_anchor");
            }
            // 12ب: CA المشغّل — عند تمرير CA متوقعة: ذيل السلسلة هو هذه CA بالضبط
            // (سلسلة سليمة شكلاً موقعة من CA مغترب = رفض — قرار التجهيز نسبي
            // لمرساة المشغّل لا «أي سلسلة متسقة تُقبل»).
            if (expectedCaDer != null) {
                Certificate tail = chain[chain.length - 1];
                boolean sameCa;
                try {
                    sameCa = java.util.Arrays.equals(tail.getEncoded(), expectedCaDer);
                } catch (Exception e) { sameCa = false; }
                if (!sameCa) throw new Refused("ca_mismatch");
            }
            // 13: النطاق — الـ SAN يطابق (dNSName أو iPAddress) بلا مطاطية.
            if (!sanMatches(leaf, domain)) throw new Refused("san_mismatch");
            // 14/15/16: الصلاحية — لم تبدأ / انتهت / أرضية 30 يوماً.
            Date now = new Date();
            if (now.before(leaf.getNotBefore())) throw new Refused("not_yet_valid");
            if (now.after(leaf.getNotAfter())) throw new Refused("validity_expired");
            long daysLeft = daysUntil(leaf.getNotAfter());
            if (daysLeft < MIN_DAYS_LEFT) throw new Refused("validity_short");
            // 17: الاشتقاق الواحد — الدبوس من SpkiPins لا من نسخة محلية.
            String pin = SpkiPins.pinOf(leaf.getPublicKey().getEncoded());
            // 18: المرساة — عند تمرير دبوس متوقع يجب أن يطابق (المرجع = المنشور).
            if (expectedPin != null) {
                if (expectedPin.length() != 64 || !isHex(expectedPin))
                    throw new Refused("bad_pin_hex");
                if (!MessageDigest.isEqual(pin.getBytes(), expectedPin.toLowerCase(Locale.ROOT).getBytes()))
                    throw new Refused("pin_mismatch");
            }
            return new Result(domain == null ? "" : domain, pin,
                    leaf.getIssuerX500Principal().getName(),
                    isoUtc(leaf.getNotBefore()), isoUtc(leaf.getNotAfter()),
                    daysLeft, chain.length, selfSigned);
        } catch (Refused re) {
            throw re;
        } catch (Exception e) {
            throw new Refused("unreadable_chain");   // فشل مغلق: ما فهمه = رفض
        }
    }

    // ---- بيان التجهيز -------------------------------------------------------------

    /** بيان التجهيز JSON — حتمي عمداً فيما عدا وقت التوليد والأيام المتبقية. */
    public static String statement(Result r, String generatedAtUtc) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"schema\": \"neokey-provision-1\",\n");
        sb.append("  \"domain\": \"").append(r.domain).append("\",\n");
        sb.append("  \"pin\": \"").append(r.pin).append("\",\n");
        sb.append("  \"pin16\": \"").append(r.pin16()).append("\",\n");
        sb.append("  \"issuer\": \"").append(jsonEsc(r.issuer)).append("\",\n");
        sb.append("  \"notBefore\": \"").append(r.notBefore).append("\",\n");
        sb.append("  \"notAfter\": \"").append(r.notAfter).append("\",\n");
        sb.append("  \"chainLen\": ").append(r.chainLen).append(",\n");
        sb.append("  \"selfSigned\": ").append(r.selfSigned).append(",\n");
        sb.append("  \"daysLeft\": ").append(r.daysLeft).append(",\n");
        sb.append("  \"emitted\": \"").append(jsonEsc(r.emittedLine())).append("\",\n");
        sb.append("  \"generatedAt\": \"").append(generatedAtUtc).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    // ---- CLI المشغّل ---------------------------------------------------------------

    public static void main(String[] args) {
        try {
            if (args.length < 4) {
                System.err.println("usage: Provision <server.p12> <pass> <alias> <domain>"
                        + " [--pin <expected64hex>] [--ca <expected-ca.der>] [--allow-self-signed] [--statement <out.json>]");
                System.exit(2);
            }
            String ksPath = args[0], alias = args[2], domain = args[3];
            char[] pass = args[1].toCharArray();
            String expectedPin = null, statementOut = null, caPath = null;
            boolean selfSigned = false;
            for (int i = 4; i < args.length; i++) {
                if ("--pin".equals(args[i]) && i + 1 < args.length) expectedPin = args[++i];
                else if ("--allow-self-signed".equals(args[i])) selfSigned = true;
                else if ("--statement".equals(args[i]) && i + 1 < args.length) statementOut = args[++i];
                else if ("--ca".equals(args[i]) && i + 1 < args.length) caPath = args[++i];
                else { System.err.println("PROVISION_FAIL unknown_arg " + args[i]); System.exit(2); }
            }
            byte[] expectedCa = null;
            if (caPath != null) expectedCa = readCertFile(caPath);
            Result r = provision(ksPath, pass, alias, domain, expectedPin, selfSigned, expectedCa);
            String at = isoUtc(new Date());
            String stmt = statement(r, at);
            String path = statementOut;
            if (path != null) {
                Path p = Paths.get(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.write(p, stmt.getBytes("UTF-8"));
            }
            System.out.println("PROVISION_OK domain=" + r.domain + " pin=" + r.pin
                    + " pin16=" + r.pin16() + " days_left=" + r.daysLeft
                    + " chain=" + r.chainLen + (r.selfSigned ? " self_signed=1" : "")
                    + " emitted=\"" + r.emittedLine() + "\""
                    + (path != null ? " statement=" + path : ""));
        } catch (Refused re) {
            System.out.println("PROVISION_FAIL " + re.tag);
            System.exit(1);
        } catch (Exception e) {
            System.out.println("PROVISION_FAIL unreadable_chain");
            System.exit(1);
        }
    }

    // ---- مساعدات -------------------------------------------------------------------

    /** هل الـ SAN يطابق النطاق؟ dNSName مطابقة حرفية صغيرة أو iPAddress مطابقة. */
    static boolean sanMatches(X509Certificate cert, String domain) {
        if (domain == null || domain.isEmpty()) return false;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return false;
            for (List<?> item : sans) {
                if (item == null || item.size() < 2) continue;
                Object type = item.get(0), val = item.get(1);
                if (val == null) continue;
                if (Integer.valueOf(2).equals(type) && domain.equalsIgnoreCase(String.valueOf(val)))
                    return true;
                if (Integer.valueOf(7).equals(type)) {
                    try {
                        if (java.net.InetAddress.getByName(domain).equals(
                                java.net.InetAddress.getByName(String.valueOf(val))))
                            return true;
                    } catch (Exception ignore) { /* عنصر SAN لا يُحل — لا يُقبل ولا يُرفض به وحده */ }
                }
            }
            return false;
        } catch (Exception e) {
            return false;   // SAN غير مقروء = لا مطابقة (فشل مغلق)
        }
    }

    static long daysUntil(Date notAfter) {
        long ms = notAfter.getTime() - System.currentTimeMillis();
        return ms <= 0 ? -1 : ms / 86_400_000L;
    }

    static String isoUtc(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        c.setTime(d);
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02dZ",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    /** قراءة شهادة DER أو PEM (المشغل الحقيقي يستلم PEM من CA التجارية). */
    static byte[] readCertFile(String path) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(path));
        String text = new String(raw, "UTF-8");
        int begin = text.indexOf("-----BEGIN");
        if (begin < 0) return raw;                       // DER خام
        int b64start = text.indexOf('\n', begin);
        int end = text.indexOf("-----END", begin);
        if (b64start < 0 || end < 0 || b64start >= end) throw new Refused("bad_pin_hex");
        StringBuilder b64 = new StringBuilder(text.length());
        for (int i = b64start + 1; i < end; i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=') b64.append(c);
        }
        return java.util.Base64.getMimeDecoder().decode(b64.toString());
    }

    static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** تجميع فحوص مسمّاة للبوابة (عدّ حتمي — نفس ترتيب القرار). */
    public static List<String> checkNames() {
        List<String> names = new ArrayList<>();
        names.add("chain_missing"); names.add("chain_type"); names.add("unreadable_chain");
        names.add("alias_missing"); names.add("no_private_key"); names.add("no_cert_chain");
        names.add("not_x509"); names.add("key_alg"); names.add("key_size");
        names.add("chain_order"); names.add("bad_chain_sig"); names.add("no_ca_anchor");
        names.add("ca_mismatch"); names.add("san_mismatch"); names.add("not_yet_valid"); names.add("validity_expired");
        names.add("validity_short"); names.add("bad_pin_hex"); names.add("pin_mismatch");
        return names;
    }
}
