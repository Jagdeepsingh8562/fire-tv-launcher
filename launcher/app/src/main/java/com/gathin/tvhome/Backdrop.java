package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home screen's pictures: couch-tv's five scenes (generated, free to use) then the eight landscape photos,
 * each crossfading into the next. A scene can have a cut-out layer (its scenery without the sky) that is drawn
 * over the big clock, so peaks and hills overlap the bottom of the digits. Two views crossfade; the old bitmap
 * is freed once hidden, so only two pictures are ever in memory. RGB_565 halves the size of each.
 */
final class Backdrop {

    /** One picture and where its big clock goes. Positions are dp from the top left; sizes are sp. */
    static final class Item {
        final String id, bg, fg, caption;
        final boolean left, dark;
        final float clockTop, clockSize, dateTop;

        Item(String id, String bg, String fg, String caption, boolean left, boolean dark,
             float clockTop, float clockSize, float dateTop) {
            this.id = id;
            this.bg = bg;
            this.fg = fg;
            this.caption = caption;
            this.left = left;
            this.dark = dark;
            this.clockTop = clockTop;
            this.clockSize = clockSize;
            this.dateTop = dateTop;
        }
    }

    interface Listener { void onPicture(Item item, Bitmap cutout); }

    private static final Item[] SCENES = {
            new Item("night", "scenes/scene-night.jpg", "scenes/scene-night-fg.webp", "Night", false, false, 75, 200, 48),
            new Item("golden", "scenes/scene-golden.jpg", "scenes/scene-golden-fg.webp", "Golden hour", false, false, 76, 195, 53),
            new Item("water", "scenes/scene-water.jpg", null, "Waterline", false, false, 134, 210, 108),
            new Item("fog", "scenes/scene-fog.jpg", "scenes/scene-fog-fg.webp", "Fog", true, true, 86, 235, 58),
            new Item("dawn", "scenes/scene-dawn.jpg", "scenes/scene-dawn-fg.webp", "Rainforest dawn", false, false, 115, 200, 88),
    };

    private final Activity act;
    private final ImageView wallA, wallB;
    private final TextView caption;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> all = new ArrayList<>();
    private List<Item> on = new ArrayList<>();
    private int index;
    private Bitmap shown;
    private boolean running;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            show(index + 1);
            schedule();
        }
    };

    Backdrop(Activity act, ImageView wallA, ImageView wallB, TextView caption, Listener listener) {
        this.act = act;
        this.wallA = wallA;
        this.wallB = wallB;
        this.caption = caption;
        this.listener = listener;
        all.addAll(catalog(act));
        index = act.getSharedPreferences("tvhome", Activity.MODE_PRIVATE).getInt("wall", 0);
    }

    /** Every picture: the five scenes, then the landscape photos with their captions. */
    static List<Item> catalog(android.content.Context ctx) {
        List<Item> list = new ArrayList<>();
        Collections.addAll(list, SCENES);
        try {
            Map<String, String> names = new HashMap<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("wallpapers/captions.txt"), "UTF-8"))) {
                for (String line; (line = in.readLine()) != null; ) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) names.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
                }
            }
            String[] files = ctx.getAssets().list("wallpapers");
            java.util.Arrays.sort(files);
            for (String f : files) {
                if (f.endsWith(".jpg")) {
                    list.add(new Item(f, "wallpapers/" + f, null, names.containsKey(f) ? names.get(f) : "", false,
                            false, 75, 200, 48));
                }
            }
        } catch (IOException ignored) {
            // the scenes still work without the photos
        }
        return list;
    }

    /** All pictures, for the settings screen. */
    List<Item> all() { return all; }

    /** Re-reads which pictures are on; keeps the current one if it is still on. */
    void reload() {
        Set<String> off = HomePrefs.wallOff(act);
        Item current = on.isEmpty() ? null : on.get(Math.max(0, Math.min(index, on.size() - 1)));
        on = new ArrayList<>();
        for (Item it : all) if (!off.contains(it.id)) on.add(it);
        if (on.isEmpty() && !all.isEmpty()) on.add(all.get(0));   // never an empty screen
        index = current != null && on.contains(current) ? on.indexOf(current) : Math.min(index, Math.max(0, on.size() - 1));
        if (running) {
            show(index);
            schedule();
        }
    }

    void start() {
        running = true;
        if (on.isEmpty()) reload();
        else {
            show(index);
            schedule();
        }
    }

    void stop() {
        running = false;
        handler.removeCallbacks(next);
    }

    private void schedule() {
        handler.removeCallbacks(next);
        int secs = HomePrefs.wallSeconds(act);
        if (running && secs > 0 && on.size() > 1) handler.postDelayed(next, secs * 1000L);
    }

    void show(int i) {
        if (on.isEmpty()) return;
        index = ((i % on.size()) + on.size()) % on.size();
        act.getSharedPreferences("tvhome", Activity.MODE_PRIVATE).edit()
                .putInt("wall", all.indexOf(on.get(index))).apply();
        final Item item = on.get(index);
        Bitmap bmp = load(item.bg, Bitmap.Config.RGB_565);
        if (bmp == null) return;
        Bitmap cutout = HomePrefs.bigClock(act) && item.fg != null ? load(item.fg, Bitmap.Config.ARGB_8888) : null;
        caption.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            caption.setText(item.caption);
            caption.animate().alpha(1f).setDuration(500).start();
        }).start();
        // wallA (below) holds the old picture while the new one fades in on wallB (above); then the old is freed.
        final Bitmap old = shown;
        shown = bmp;
        wallB.animate().cancel();
        wallA.setImageBitmap(old);
        wallB.setImageBitmap(bmp);
        wallB.setAlpha(0f);
        wallB.animate().alpha(1f).setDuration(old == null ? 400 : 1400).withEndAction(() -> {
            wallA.setImageDrawable(null);
            if (old != null && !old.isRecycled()) old.recycle();
        }).start();
        listener.onPicture(item, cutout);
    }

    private Bitmap load(String path, Bitmap.Config config) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = config;
        try (InputStream in = act.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    Item current() { return on.isEmpty() ? null : on.get(index); }
}
