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
        if (title == null) {
            listener.onNowPlaying(null);
            return;
        }
        listener.onNowPlaying(new Info(pick.getPackageName(), title, artist == null ? "" : artist, art, playing, pick));
    }
}
