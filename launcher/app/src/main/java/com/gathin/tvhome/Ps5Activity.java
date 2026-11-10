package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A PlayStation 5 style home, after the Norma launcher (github.com/ilhamasy/cleanyourtv-ps5-theme): the pinned apps
 * in a row at the top left, the focused one enlarged with a glow in its own colour, a full-screen picture for that
 * app behind everything, its name in large type at the bottom and a small stats card. The pictures and the click
 * sounds are optional files in assets/ps5/ (art/&lt;package&gt;.jpg, sounds/*.wav) that are not in git; an app
 * without a picture gets a gradient in its colour, and without the sounds it is silent. The last tile opens the
 * classic home (MainActivity), which has every app, Keep watching, Now playing and the power menu.
 */
public class Ps5Activity extends Activity {

    private static final float TILE_W = 96, TILE_H = 54, GAP = 14, FOCUS = 1.6f;   // dp, focused scale

    private float d;
    private AppAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int generation;

    private ImageView wallA, wallB;
    private LinearLayout dock, dots;
    private TextView clockDate, name, weatherText, lastOpened, appSize;
    private ImageView weatherIcon, wifi;
    private View weatherPill, statsCard;
    private final List<View> tiles = new ArrayList<>();
    private final List<View> glows = new ArrayList<>();
    private final List<View> rings = new ArrayList<>();
    private final List<String> packages = new ArrayList<>();   // "" for the All apps tile
    private final List<String> labels = new ArrayList<>();
    private final List<Integer> accents = new ArrayList<>();
    private int focused = -1;
    private String lastPkg;   // the tile focused last ("" is All apps); null until one has been

    private Weather weather;
    // The last forecast, kept between visits so the pill is there the moment Home opens.
    private static int cachedTemp = Integer.MIN_VALUE, cachedIcon;
    private int lastTemp = cachedTemp, lastIcon = cachedIcon;
    private SoundPool pool;
    private int soundMove, soundEnter;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clockDate.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date())
                    .toUpperCase(Locale.getDefault()));
            wifi.setImageResource(online() ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
            weather.refreshIfStale();
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        adapter = new AppAdapter(this, null, Math.max(1, getResources().getInteger(R.integer.columns)));
        weather = new Weather(this, (c, icon) -> {
            lastTemp = cachedTemp = c;
            lastIcon = cachedIcon = icon;
            showWeather();
        });
        build();
        initSounds();
        root().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, now) -> {
            if (now == null) return;
            Object tag = now.getTag();
            if (tag instanceof Integer) focusTile((Integer) tag);
            if (oldFocus != null) sound(soundMove);
        });
    }

    private View root() {
        return findViewById(android.R.id.content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!HomePrefs.ps5(this)) {   // switched back to the classic home in Settings
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        adapter.reload();
        fillDock();
        weather.setPlace(HomePrefs.latitude(this), HomePrefs.longitude(this), HomePrefs.fahrenheit(this));
        showWeather();
        handler.post(tick);
        int start = lastPkg == null ? 0 : Math.max(0, packages.indexOf(lastPkg));
        dock.post(() -> tiles.get(Math.min(start, tiles.size() - 1)).requestFocus());
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (pool != null) pool.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- layout ----------------------------------------------------------------------------------

    private void build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF05070C);
        wallA = fullImage(frame);
        wallB = fullImage(frame);

        View scrim = new View(this);   // a shade on the left, the bottom and the top so the type stays readable
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x99000000, 0x00000000});
        GradientDrawable bottom = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000});
        GradientDrawable top = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000});
        LayerDrawable shade = new LayerDrawable(new Drawable[]{left, bottom, top});
        shade.setLayerInset(1, 0, dp(250), 0, 0);   // the bottom shade covers the lower half
        shade.setLayerInset(2, 0, 0, 0, dp(380));   // the top shade the upper fifth
        scrim.setBackground(shade);
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Time and date, top left.
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        String f = HomePrefs.clock24h(this) ? "HH:mm" : "h:mm";
        time.setFormat12Hour(f);
        time.setFormat24Hour(f);
        time.setTypeface(Fonts.regular(this));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        time.setTextColor(0xFFFFFFFF);
        clockDate = text("", 11, Fonts.regular(this), 0xE6FFFFFF);
        clockDate.setLetterSpacing(0.12f);
        clockBox.addView(time);
        clockBox.addView(clockDate);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP);
        cp.leftMargin = dp(56);
        cp.topMargin = dp(26);
        frame.addView(clockBox, cp);

        // Weather, Wi-Fi and Settings, top right.
        LinearLayout pills = new LinearLayout(this);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        weatherPill = new LinearLayout(this);
        LinearLayout wp = (LinearLayout) weatherPill;
        wp.setGravity(Gravity.CENTER_VERTICAL);
        wp.setBackgroundResource(R.drawable.card_bg_static);
        wp.setPadding(dp(14), 0, dp(18), 0);
        weatherIcon = new ImageView(this);
        wp.addView(weatherIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        weatherText = text("", 15, Fonts.medium(this), 0xFFFFFFFF);
        LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(-2, -2);
        wt.leftMargin = dp(8);
        wp.addView(weatherText, wt);
        pills.addView(weatherPill, new LinearLayout.LayoutParams(-2, dp(42)));
        wifi = pillButton(R.drawable.ic_wifi, "Wi-Fi", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        pills.addView(wifi, pillParams());
        ImageView gear = pillButton(R.drawable.ic_settings, "Settings", () -> startActivity(new Intent(this, SettingsActivity.class)));
        gear.setOnLongClickListener(v -> {   // hold OK: the TV's own settings
            open(new Intent(Settings.ACTION_SETTINGS));
            return true;
        });
        pills.addView(gear, pillParams());
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        pp.rightMargin = dp(56);
        pp.topMargin = dp(26);
        frame.addView(pills, pp);

        // The pinned apps, top left under the clock; filled in by fillDock().
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false);
        FrameLayout.LayoutParams dockP = new FrameLayout.LayoutParams(-2, dp(TILE_H), Gravity.START | Gravity.TOP);
        dockP.leftMargin = dp(56);
        dockP.topMargin = dp(108);
        frame.addView(dock, dockP);
        frame.setClipChildren(false);

        // Dots, then the app's name, bottom left.
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams dotsP = new FrameLayout.LayoutParams(-2, dp(8), Gravity.START | Gravity.BOTTOM);
        dotsP.leftMargin = dp(56);
        dotsP.bottomMargin = dp(140);
        frame.addView(dots, dotsP);
        name = text("", 56, Fonts.light(this), 0xFFFFFFFF);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(dp(520), -2, Gravity.START | Gravity.BOTTOM);
        np.leftMargin = dp(52);
        np.bottomMargin = dp(46);
        frame.addView(name, np);

        // Stats card, bottom right.
        LinearLayout card = new LinearLayout(this);
        card.setBackgroundResource(R.drawable.card_bg_static);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(10), dp(20), dp(10));
        lastOpened = stat(card, "Last opened");
        View bar = new View(this);
        bar.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(1), dp(34));
        bp.leftMargin = dp(18);
        bp.rightMargin = dp(18);
        card.addView(bar, bp);
        appSize = stat(card, "App size");
        statsCard = card;
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        sp.rightMargin = dp(56);
        sp.bottomMargin = dp(48);
        frame.addView(card, sp);

        setContentView(frame);
    }

    private TextView stat(LinearLayout card, String title) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 12, Fonts.regular(this), 0xB3FFFFFF));
        TextView value = text("", 20, Fonts.medium(this), 0xFFFFFFFF);
        col.addView(value);
        card.addView(col);
        return value;
    }

    private ImageView fullImage(FrameLayout frame) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(v, new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    private ImageView pillButton(int icon, String description, Runnable click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setImageTintList(getResources().getColorStateList(R.drawable.pill_text));
        b.setContentDescription(description);
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            sound(soundEnter);
            click.run();
        });
        return b;
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(42), dp(42));
        p.leftMargin = dp(12);
        return p;
    }

    // ---- the pinned apps -------------------------------------------------------------------------

    private void fillDock() {
        dock.removeAllViews();
        tiles.clear();
        glows.clear();
        rings.clear();
        packages.clear();
        labels.clear();
        accents.clear();
        for (int i = 0; i < adapter.dockCount(); i++) {
            AppAdapter.Entry e = adapter.entryAt(i);
            Drawable pic = e.art != null ? e.art : e.icon;
            addTile(e.pkg, e.label, e.art, e.icon, accent(pic), dp(TILE_W));
        }
        addTile("", "All apps", null, null, 0xFFE6ECFF, dp(TILE_H));
        fillDots();
        focused = -1;
    }

    private void addTile(final String pkg, String label, Drawable art, Drawable icon, int accent, int width) {
        final int index = tiles.size();
        FrameLayout tile = new FrameLayout(this);
        tile.setClipChildren(false);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(index);

        View glow = new View(this);   // a soft halo in the app's colour
        GradientDrawable halo = new GradientDrawable();
        halo.setColor((accent & 0x00FFFFFF) | 0x4D000000);
        halo.setCornerRadius(dp(20));
        glow.setBackground(halo);
        glow.setAlpha(0f);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.setMargins(-dp(9), -dp(9), -dp(9), -dp(9));
        tile.addView(glow, gp);

        FrameLayout face = new FrameLayout(this);
        round(face, 11);
        if (pkg.isEmpty()) {
            face.setBackgroundColor(0xFF232A38);
            face.addView(new GridGlyph(this), new FrameLayout.LayoutParams(-1, -1));
        } else if (art != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(art.getConstantState() != null ? art.getConstantState().newDrawable() : art);
            face.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            face.setBackgroundResource(R.drawable.dock_tile_bg);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            img.setImageDrawable(icon);
            img.setPadding(dp(10), dp(6), dp(10), dp(6));
            face.addView(img, new FrameLayout.LayoutParams(-1, -1));
        }
        tile.addView(face, new FrameLayout.LayoutParams(-1, -1));

        View ring = new View(this);   // a bright rim round the focused tile
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(Color.TRANSPARENT);
        rim.setStroke(dp(2.5f), blend(accent, 0xFFFFFFFF, 0.55f));
        rim.setCornerRadius(dp(11));
        ring.setBackground(rim);
        ring.setAlpha(0f);
        tile.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        tile.setOnClickListener(v -> {
            sound(soundEnter);
            if (pkg.isEmpty()) {
                startActivity(new Intent(this, MainActivity.class).putExtra("classic", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                lastPkg = pkg;
                adapter.open(pkg);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(TILE_H));
        lp.rightMargin = dp(GAP);
        dock.addView(tile, lp);
        tiles.add(tile);
        glows.add(glow);
        rings.add(ring);
        packages.add(pkg);
        labels.add(label);
        accents.add(accent);
    }

    private void fillDots() {
        dots.removeAllViews();
        for (int i = 0; i < tiles.size(); i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(4));
            g.setColor(0xFFFFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(8), dp(8));
            p.rightMargin = dp(6);
            dots.addView(dot, p);
        }
    }

    /** The tile at {@code index} is focused: enlarge it, move its neighbours aside, and change everything behind it. */
    private void focusTile(int index) {
        if (index == focused || index < 0 || index >= tiles.size()) return;
        focused = index;
        float shift = (FOCUS - 1f) * tiles.get(index).getWidth() / 2f;
        for (int j = 0; j < tiles.size(); j++) {
            boolean on = j == index;
            tiles.get(j).animate().scaleX(on ? FOCUS : 1f).scaleY(on ? FOCUS : 1f)
                    .translationX(on ? 0f : (j < index ? -shift : shift)).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
            glows.get(j).animate().alpha(on ? 1f : 0f).setDuration(180).start();
            rings.get(j).animate().alpha(on ? 1f : 0f).setDuration(180).start();
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) dots.getChildAt(j).getLayoutParams();
            p.width = dp(on ? 24 : 8);
            dots.getChildAt(j).setLayoutParams(p);
            dots.getChildAt(j).setAlpha(on ? 1f : 0.45f);
        }
        String pkg = packages.get(index);
        lastPkg = pkg;
        name.setText(labels.get(index));
        statsCard.setVisibility(pkg.isEmpty() ? View.GONE : View.VISIBLE);
        if (!pkg.isEmpty()) {
            lastOpened.setText(lastOpenedText(pkg));
            appSize.setText(appSizeText(pkg));
        }
        backdrop(pkg, accents.get(index));
    }

    // ---- the picture behind ----------------------------------------------------------------------

    private void backdrop(final String pkg, final int accent) {
        final int gen = ++generation;
        io.execute(() -> {
            if (gen != generation) return;   // focus has moved on already
            final Bitmap bmp = pkg.isEmpty() ? null : loadArt(pkg);
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) return;
                Drawable next = bmp != null ? new android.graphics.drawable.BitmapDrawable(getResources(), bmp)
                        : new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{blend(accent, 0xFF000000, 0.62f), 0xFF05070C});
                wallA.setImageDrawable(wallB.getDrawable());
                wallB.animate().cancel();
                wallB.setImageDrawable(next);
                wallB.setAlpha(0f);
                wallB.animate().alpha(1f).setDuration(380).withEndAction(() -> wallA.setImageDrawable(null)).start();
            });
        });
    }

    private Bitmap loadArt(String pkg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = getAssets().open("ps5/art/" + pkg + ".jpg")) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;   // no picture for this app: a gradient is drawn instead
        }
    }

    // ---- small facts -----------------------------------------------------------------------------

    private String lastOpenedText(String pkg) {
        for (RecentApps.Entry e : RecentApps.list(this)) if (e.pkg.equals(pkg)) return RecentApps.ago(e.at);
        return "—";
    }

    private String appSizeText(String pkg) {
        try {
            long bytes = new File(getPackageManager().getApplicationInfo(pkg, 0).sourceDir).length();
            return bytes >= 1 << 20 ? Math.round(bytes / 1048576f) + " MB" : Math.max(1, bytes >> 10) + " KB";
        } catch (Exception e) {
            return "—";
        }
    }

    private void showWeather() {
        boolean show = HomePrefs.weatherOn(this) && lastTemp != Integer.MIN_VALUE;
        if (show) {
            int t = HomePrefs.fahrenheit(this) ? Math.round(lastTemp * 9 / 5f + 32) : lastTemp;
            weatherIcon.setImageResource(lastIcon);
            weatherText.setText(t + "°  ·  " + HomePrefs.placeName(this));
        }
        weatherPill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // not available on this TV
        }
    }

    // ---- sounds ----------------------------------------------------------------------------------

    private void initSounds() {
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        soundMove = loadSound("navigation.wav");
        soundEnter = loadSound("enter.wav");
    }

    private int loadSound(String file) {
        try (AssetFileDescriptor fd = getAssets().openFd("ps5/sounds/" + file)) {
            return pool.load(fd, 1);
        } catch (IOException e) {
            return 0;   // the sound files are optional
        }
    }

    private void sound(int id) {
        if (pool != null && id != 0 && HomePrefs.ps5Sound(this)) pool.play(id, 0.7f, 0.7f, 1, 0, 1f);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The app's colour: the average of the most colourful pixels of its art; a soft white when it has none. */
    private static int accent(Drawable dr) {
        if (dr == null || dr.getConstantState() == null) return 0xFFE6ECFF;
        Drawable copy = dr.getConstantState().newDrawable().mutate();
        Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 24, 24);
        copy.draw(new Canvas(b));
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, total = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int c = b.getPixel(x, y);
                if (Color.alpha(c) < 200) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue;
                double w = hsv[1] * hsv[2];
                r += Color.red(c) * w;
                g += Color.green(c) * w;
                bl += Color.blue(c) * w;
                total += w;
            }
        }
        b.recycle();
        return total < 0.5 ? 0xFFE6ECFF : Color.rgb((int) (r / total), (int) (g / total), (int) (bl / total));
    }

    private static int blend(int from, int to, float t) {
        return Color.rgb((int) (Color.red(from) * (1 - t) + Color.red(to) * t),
                (int) (Color.green(from) * (1 - t) + Color.green(to) * t),
                (int) (Color.blue(from) * (1 - t) + Color.blue(to) * t));
    }

    private TextView text(String s, float sp, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setSingleLine(true);
        return t;
    }

    private void round(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * d);
            }
        });
        v.setClipToOutline(true);
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    /** Nine rounded squares: the All apps tile. */
    private static final class GridGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cell = new RectF();

        GridGlyph(Context c) {
            super(c);
            paint.setColor(0xF2FFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight()) * 0.46f, step = s / 3f, box = step * 0.74f;
            float x0 = (getWidth() - s) / 2f + (step - box) / 2f, y0 = (getHeight() - s) / 2f + (step - box) / 2f;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    cell.set(x0 + col * step, y0 + row * step, x0 + col * step + box, y0 + row * step + box);
                    canvas.drawRoundRect(cell, box * 0.28f, box * 0.28f, paint);
                }
            }
        }
    }
}
