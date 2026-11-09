package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** The settings the on-TV Home settings screen changes. Defaults live here, so a fresh install needs no setup. */
final class HomePrefs {
    private HomePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
    }

    // ---- look ---------------------------------------------------------------------------------
    static boolean bigClock(Context c) { return p(c).getBoolean("clock_big", true); }
    static void bigClock(Context c, boolean v) { p(c).edit().putBoolean("clock_big", v).apply(); }

    static boolean clock24h(Context c) { return p(c).getBoolean("clock_24", false); }
    static void clock24h(Context c, boolean v) { p(c).edit().putBoolean("clock_24", v).apply(); }

    /** Seconds each picture stays; 0 keeps one picture. */
    static int wallSeconds(Context c) { return p(c).getInt("wall_secs", 300); }
    static void wallSeconds(Context c, int v) { p(c).edit().putInt("wall_secs", v).apply(); }

    /** Ids of pictures switched off (all are on by default). */
    static Set<String> wallOff(Context c) {
        return new HashSet<>(p(c).getStringSet("wall_off", new HashSet<String>()));
    }
    static void wallOff(Context c, Set<String> ids) { p(c).edit().putStringSet("wall_off", ids).apply(); }

    // ---- weather ------------------------------------------------------------------------------
    static boolean weatherOn(Context c) { return p(c).getBoolean("wx_on", true); }
    static void weatherOn(Context c, boolean v) { p(c).edit().putBoolean("wx_on", v).apply(); }

    static boolean fahrenheit(Context c) { return p(c).getBoolean("wx_f", false); }
    static void fahrenheit(Context c, boolean v) { p(c).edit().putBoolean("wx_f", v).apply(); }

    static String placeName(Context c) { return p(c).getString("wx_name", c.getString(R.string.weather_place)); }
    static String latitude(Context c) { return p(c).getString("wx_lat", c.getString(R.string.weather_latitude)); }
    static String longitude(Context c) { return p(c).getString("wx_lon", c.getString(R.string.weather_longitude)); }
    static void place(Context c, String name, String lat, String lon) {
        p(c).edit().putString("wx_name", name).putString("wx_lat", lat).putString("wx_lon", lon).apply();
    }

    // ---- cards above the dock ------------------------------------------------------------------
    static boolean keepWatching(Context c) { return p(c).getBoolean("keep_on", true); }
    static void keepWatching(Context c, boolean v) { p(c).edit().putBoolean("keep_on", v).apply(); }

    static boolean nowPlaying(Context c) { return p(c).getBoolean("np_on", true); }
    static void nowPlaying(Context c, boolean v) { p(c).edit().putBoolean("np_on", v).apply(); }

    // ---- apps ---------------------------------------------------------------------------------
    /** Apps hidden from the home on top of the hidden_apps list in config.xml. */
    static Set<String> hiddenApps(Context c) {
        return new HashSet<>(p(c).getStringSet("hidden_extra", new HashSet<String>()));
    }
    static void hiddenApps(Context c, Set<String> pkgs) { p(c).edit().putStringSet("hidden_extra", pkgs).apply(); }

    /** Back to the defaults. The dock itself (which apps, in what order) is kept. */
    static void reset(Context c) {
        p(c).edit().remove("clock_big").remove("clock_24").remove("wall_secs").remove("wall_off").remove("wx_on")
                .remove("wx_f").remove("wx_name").remove("wx_lat").remove("wx_lon").remove("keep_on").remove("np_on")
                .remove("hidden_extra").apply();
    }
}
