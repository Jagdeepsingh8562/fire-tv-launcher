package com.gathin.tvhome;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * https GET for Open-Meteo (weather and place search, free, no key). Trusts only the bundled ISRG roots in
 * res/raw: Android 5.1 does not ship ISRG Root X2, which api.open-meteo.com chains to, and its plain-http
 * answer is held back by ~6 s and never reached the stick. Call off the main thread.
 */
final class OpenMeteo {
    private OpenMeteo() {}

    private static SSLSocketFactory tls;

    static synchronized String get(Context ctx, String url) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setSSLSocketFactory(factory(ctx.getApplicationContext()));
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        } finally {
            c.disconnect();
        }
        return buf.toString("UTF-8");
    }

    private static SSLSocketFactory factory(Context ctx) throws Exception {
        if (tls != null) return tls;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int[] roots = {R.raw.isrg_root_x1, R.raw.isrg_root_x2};
        for (int i = 0; i < roots.length; i++) {
            try (InputStream in = ctx.getResources().openRawResource(roots[i])) {
                ks.setCertificateEntry("isrg" + i, cf.generateCertificate(in));
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), null);
        return tls = sc.getSocketFactory();
    }
}
