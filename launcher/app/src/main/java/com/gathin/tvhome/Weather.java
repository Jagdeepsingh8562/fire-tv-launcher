package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

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
 * Current temperature and sky from Open-Meteo (free, no key), over https with Let's Encrypt's ISRG roots
 * bundled in res/raw: Android 5.1 does not ship ISRG Root X2, which api.open-meteo.com chains to, and its
 * plain-http answer is held back by ~6 s and never reached the stick. Fetched at most
 * every REFRESH_MS, only when the home asks (it asks on resume and on its minute tick), so nothing polls in
 * the background while an app is playing.
 */
final class Weather {
    interface Listener { void onWeather(int celsius, int icon); }

    private static final long REFRESH_MS = 30 * 60_000L;
    private final Context ctx;
    private final String lat, lon;
    private SSLSocketFactory tls;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long fetchedAt = -REFRESH_MS;
    private boolean busy;

    Weather(Context ctx, String lat, String lon, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.lat = lat.trim();
        this.lon = lon.trim();
        this.listener = listener;
    }

    /** Fetch if the last answer is older than REFRESH_MS. */
    void refreshIfStale() {
        if (lat.isEmpty() || busy || SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day");
                HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
                c.setSSLSocketFactory(tls());
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[4096];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                } finally {
                    c.disconnect();
                }
                JSONObject cur = new JSONObject(buf.toString("UTF-8")).getJSONObject("current");
                int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                int icon = icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                main.post(() -> {
                    fetchedAt = SystemClock.elapsedRealtime();
                    busy = false;
                    listener.onWeather(temp, icon);
                });
            } catch (Exception e) {
                main.post(() -> busy = false);   // try again on the next tick; the last reading stays
            }
        }).start();
    }

    /** TLS that trusts only the bundled ISRG roots: this connection only ever talks to Open-Meteo. */
    private SSLSocketFactory tls() throws Exception {
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

    /** WMO weather code to one of the wx_ icons. */
    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code <= 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 95) return R.drawable.wx_storm;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        return R.drawable.wx_rain;   // drizzle, rain, showers
    }

}
