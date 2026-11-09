package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;


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
    private String lat = "", lon = "";
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long fetchedAt = -REFRESH_MS;
    private boolean busy;

    Weather(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** A new place (or the same one): forget the last reading when the place changed so it is fetched again. */
    void setPlace(String lat, String lon, boolean fahrenheitIgnored) {
        lat = lat.trim();
        lon = lon.trim();
        if (!lat.equals(this.lat) || !lon.equals(this.lon)) fetchedAt = -REFRESH_MS;
        this.lat = lat;
        this.lon = lon;
    }

    /** Fetch if the last answer is older than REFRESH_MS. */
    void refreshIfStale() {
        if (lat.isEmpty() || busy || SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                String body = OpenMeteo.get(ctx, "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day");
                JSONObject cur = new JSONObject(body).getJSONObject("current");
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
