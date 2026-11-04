package com.hark.neokey;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * قناة TLS مثبتة SPKI — المرحلة 5، الأسبوع 1 (الأسبوع 26).
 *
 * يبني SSLContext تحققُه: (1) سلسلة الشهادات سليمة قبالة مرساة الثقة (مرساة
 * الحلبة المزوَّدة أو مخزن النظام)، ثم (2) مفتاح الخادم الطرفي مثبت حرفياً في
 * {@link SpkiPins}. الفحص يعيش **داخل** TrustManager أي داخل المصافحة نفسها —
 * لا بايت واحد من الطلب يغادر الجهاز قبل أن يكون مفتاح الخادم مثبتاً (الفرق
 * الجوهري عن فحص ما بعد الاتصال: الشهادة قد تُرسل بعد الجسم في بروتوكولات
 * متدفقة).
 *
 * الفشل مغلق: أي غموض (سلسلة فارغة، مفتاح غير قابل للاشتقاق، دبوس غائب) =
 * CertificateException بعلامة حرفية تُرى في الإثبات السلبي:
 *   • "spki_not_pinned"  — سلسلة صحيحة لكن المفتاح غير مثبت (الهجوم المركز).
 *   • "empty_chain"      — لا شهادات تُقيَّم.
 *   • "spki_undigestable"— مفتاح لا يمكن ضغطه (لا يحدث عملياً = إغلاق).
 *
 * نقي JVM: javax.net.ssl من الإطار (أندرويد وJDK) — صفر android.*، صفر مكتبات.
 */
public final class PinnedTls {

    /** بروتوكول آخر مصافحة خادم ناجحة — تُقاس عليه أرضية TLS في البوابة. */
    public static volatile String LAST_PROTOCOL = "";

    /** سياق الحلبة: مرساة الثقة = شهادة المرساة المزوَّدة (DER) حصراً. */
    public static SSLContext arenaContext(byte[] caCertDer, SpkiPins pins) throws Exception {
        if (caCertDer == null || caCertDer.length == 0)
            throw new SyncVault.SyncRefused("no_trust_anchor");
        if (pins == null) throw new SyncVault.SyncRefused("no_pins");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate anchor = cf.generateCertificate(new ByteArrayInputStream(caCertDer));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("nk-arena-anchor", anchor);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return wrap(tmf.getTrustManagers(), pins);
    }

    /** سياق الإنتاج: سلاسل النظام كاملة + دبوس إلزامي على مفتاح الطرفي. */
    public static SSLContext systemContext(SpkiPins pins) throws Exception {
        if (pins == null) throw new SyncVault.SyncRefused("no_pins");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return wrap(tmf.getTrustManagers(), pins);
    }

    private static SSLContext wrap(TrustManager[] delegates, SpkiPins pins) throws Exception {
        X509ExtendedTrustManager tm = new PinTrustManager((X509ExtendedTrustManager) delegates[0], pins);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx;
    }

    /** المفوّض ثم الدبوس — الترتيب ثابت: السلسلة أولاً فالهوية، والفشل مُغلَق. */
    private static final class PinTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final SpkiPins pins;

        PinTrustManager(X509ExtendedTrustManager delegate, SpkiPins pins) {
            this.delegate = delegate; this.pins = pins;
        }

        private void pinCheck(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("empty_chain");
            byte[] spki;
            try { spki = chain[0].getPublicKey().getEncoded(); }
            catch (Exception e) { throw new CertificateException("spki_undigestable"); }
            if (!pins.verify(spki)) throw new CertificateException("spki_not_pinned");
        }

        /** بروتوكول المصافحة الجارية — getSession() هنا يحاجز حتى اكتمال المصافحة
         *  (جمود: يُستدعى داخل المصافحة نفسها — اصطاده الإثبات قبل التثبيت)،
         *  فالصحيح getHandshakeSession() المصمم لسياق TrustManager. على أجهزة
         *  أندرويد أقدم من API 24 يغيب التابع — تُلهم بهدوء (المقياس بوابي لا جهازي). */
        private void note(javax.net.ssl.SSLSocket s) {
            try {
                String p = s.getHandshakeSession().getProtocol();
                if (p != null && !p.isEmpty()) LAST_PROTOCOL = p;
            } catch (Throwable ignore) { /* API < 24: المقياس بوابي فقط */ }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket s) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, s);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine e) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, e);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            pinCheck(chain);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket s) throws CertificateException {
            delegate.checkServerTrusted(chain, authType, s);
            pinCheck(chain);
            if (s instanceof SSLSocket) note((SSLSocket) s);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine e) throws CertificateException {
            delegate.checkServerTrusted(chain, authType, e);
            pinCheck(chain);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }

    private PinnedTls() { throw new UnsupportedOperationException(); }
}
