package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * "Keep watching": the apps opened from this home, newest first. Fire OS 5 has no watch history to read, so this
 * is what the launcher itself saw. Kept as "package@millis" pairs in one preference.
 */
final class RecentApps {
    private static final int MAX = 8;
    private RecentApps() {}

    static final class Entry {
        final String pkg;
        final long at;
        Entry(String pkg, long at) { this.pkg = pkg; this.at = at; }
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
    }

    static void record(Context c, String pkg) {
        List<Entry> list = list(c);
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(pkg, System.currentTimeMillis()));
        for (Entry e : list) if (!e.pkg.equals(pkg) && out.size() < MAX) out.add(e);
        save(c, out);
    }

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = p(c).getString("recent", "");
        for (String part : raw.split(",")) {
            int at = part.lastIndexOf('@');
            if (at <= 0) continue;
            try {
                out.add(new Entry(part.substring(0, at), Long.parseLong(part.substring(at + 1))));
            } catch (NumberFormatException ignored) {
                // a damaged entry is dropped
            }
        }
        return out;
    }

    static void clear(Context c) {
        p(c).edit().remove("recent").apply();
    }

    private static void save(Context c, List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(sb.length() > 0 ? "," : "").append(e.pkg).append('@').append(e.at);
        p(c).edit().putString("recent", sb.toString()).apply();
    }

    /** "5 min ago", "2 h ago", "yesterday", "3 days ago". */
    static String ago(long at) {
        long mins = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (mins < 1) return "just now";
        if (mins < 60) return mins + " min ago";
        if (mins < 24 * 60) return mins / 60 + " h ago";
        long days = mins / (24 * 60);
        return days == 1 ? "yesterday" : days + " days ago";
    }
}
