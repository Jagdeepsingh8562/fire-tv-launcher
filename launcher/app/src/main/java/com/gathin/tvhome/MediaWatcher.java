package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing in other apps (song, artist, cover, controls) for the Now playing card. Works only while the
 * notification listener in MediaWatcherService is on. Listens for changes, so nothing polls. A paused song stays
 * for ten minutes, like couch-tv's card.
 */
final class MediaWatcher {

    static final class Info {
        final String pkg, title, artist;
        final Bitmap art;
        final boolean playing;
        final MediaController controller;

        Info(String pkg, String title, String artist, Bitmap art, boolean playing, MediaController controller) {
            this.pkg = pkg;
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.playing = playing;
            this.controller = controller;
        }
    }

    interface Listener { void onNowPlaying(Info info); }

    private static final long PAUSED_KEEP_MS = 10 * 60_000L;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private MediaSessionManager manager;
    private List<MediaController> controllers = new ArrayList<>();
    /** YouTube ids SmartTube sends with no title (a video opened from a link): looked up once from oEmbed. */
    private final java.util.Map<String, String[]> resolved = new java.util.HashMap<>();   // id -> {title, author}
    private final java.util.Map<String, Bitmap> covers = new java.util.HashMap<>();
    private final java.util.Set<String> fetching = new java.util.HashSet<>();
    private static final java.util.regex.Pattern VIDEO_ID = java.util.regex.Pattern.compile("[\\w-]{11}");
    private long pausedAt;
    private String pausedPkg = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> {
        watch(list);
        publish();
    };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onSessionDestroyed() { publish(); }
    };

    MediaWatcher(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.component = new ComponentName(ctx, MediaWatcherService.class);
    }

    static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    void start() {
        if (!hasAccess(ctx)) {
            listener.onNowPlaying(null);
            return;
        }
        try {
            manager = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            manager.addOnActiveSessionsChangedListener(sessions, component, main);
            watch(manager.getActiveSessions(component));
            publish();
        } catch (SecurityException e) {
            listener.onNowPlaying(null);   // access was switched off again
        }
    }

    void stop() {
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessions);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers = new ArrayList<>();
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : controllers) c.unregisterCallback(callback);
        controllers = list == null ? new ArrayList<MediaController>() : new ArrayList<>(list);
        for (MediaController c : controllers) c.registerCallback(callback, main);
    }

    private String appName(String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Title, channel and thumbnail from YouTube's public oEmbed, off the main thread; then publishes again. */
    private void lookUp(final String id) {
        if (!fetching.add(id)) return;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(
                                "https://www.youtube.com/watch?v=" + id, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] b = new byte[4096];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                }
                org.json.JSONObject o = new org.json.JSONObject(buf.toString("UTF-8"));
                final String[] info = {o.optString("title"), o.optString("author_name")};
                Bitmap thumb = null;
                String t = o.optString("thumbnail_url");
                if (!t.isEmpty()) {
                    try (java.io.InputStream in = new java.net.URL(t).openStream()) {
                        android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                        opt.inSampleSize = 2;
                        thumb = android.graphics.BitmapFactory.decodeStream(in, null, opt);
                    } catch (Exception ex) {
                                // no cover: the card shows a note icon
                    }
                }
                final Bitmap cover = thumb;
                main.post(() -> {
                    resolved.put(id, info);
                    if (cover != null) covers.put(id, cover);
                    publish();
                });
            } catch (Exception e) {
                // offline or unknown video: the card keeps the app name
            }
        }).start();
    }

    private void publish() {
        MediaController pick = null;
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING && c.getMetadata() != null) {
                pick = c;
                break;
            }
        }
        boolean playing = pick != null;
        if (pick == null) {
            for (MediaController c : controllers) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PAUSED && c.getMetadata() != null) {
                    if (!c.getPackageName().equals(pausedPkg)) {
                        pausedPkg = c.getPackageName();
                        pausedAt = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - pausedAt < PAUSED_KEEP_MS) pick = c;
                    break;
                }
            }
        } else {
            pausedPkg = "";
        }
        if (pick == null) {
            listener.onNowPlaying(null);
            return;
        }
        MediaMetadata m = pick.getMetadata();
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (title == null) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        boolean youtubeId = id != null && VIDEO_ID.matcher(id).matches();
        if (art == null && youtubeId) {   // a cover from YouTube's thumbnail when the app sent none
            art = covers.get(id);
            if (art == null) lookUp(id);
        }
        if (title == null) {
            // No title: show the app and its state, and look the video up when it has a YouTube id.
            String[] known = id == null ? null : resolved.get(id);
            if (known != null) {
                title = known[0];
                artist = known[1];
                if (art == null) art = covers.get(id);
            } else {
                if (youtubeId) lookUp(id);
                title = appName(pick.getPackageName());
                artist = playing ? "Playing" : "Paused";
            }
        }
        listener.onNowPlaying(new Info(pick.getPackageName(), title, artist == null ? "" : artist, art, playing, pick));
    }
}
