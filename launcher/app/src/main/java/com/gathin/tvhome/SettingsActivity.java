package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home settings on the TV, after couch-tv's: a menu on the left, the section's rows on the right. Up and down
 * move, OK changes a row, Right goes from the menu into the rows, Left or Back comes out. Changes are saved at
 * once (HomePrefs) and the home applies them when it comes back to the front.
 */
public class SettingsActivity extends Activity {

    private static final String[] SECTIONS = {"Look", "Pictures", "Weather", "Cards", "Apps", "About"};
    private static final int[] EVERY_SECONDS = {20, 60, 300, 900, 3600, 0};
    private static final String[] EVERY_LABELS = {"20 seconds", "1 minute", "5 minutes", "15 minutes", "1 hour", "Never"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private float density;
    private LinearLayout menu, content;
    private ScrollView scroll;
    private TextView title;
    private int section;
    private Runnable back;   // set while a sub-page (place search) is open
    private boolean rebuilding;   // content is being replaced: focus passing through the menu is not a choice

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF152238, 0xFF05070C});
        root.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(56), dp(40), dp(56), dp(24));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        title = text("Home settings", 30, 0xFFFFFFFF, Fonts.light(this));
        left.addView(title, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(22)));
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        left.addView(menu);
        row.addView(left, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(66), dp(8), dp(40));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        sp.leftMargin = dp(36);
        row.addView(scroll, sp);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        for (int i = 0; i < SECTIONS.length; i++) {
            final int idx = i;
            View item = row(SECTIONS[i], null, null);
            item.setBackgroundResource(R.drawable.menu_row_bg);
            item.setOnFocusChangeListener((v, focused) -> {
                // While a sub-page is open, focus passing through the menu (when its rows are cleared) is not a choice.
                if (focused && !rebuilding && back == null && section != idx) {
                    section = idx;
                    show();
                }
            });
            item.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    focusFirstRow();
                    return true;
                }
                return code == KeyEvent.KEYCODE_DPAD_LEFT;
            });
            menu.addView(item);
        }
        show();
        menu.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (back != null) {
            Runnable r = back;
            back = null;
            r.run();
        } else if (menu.findFocus() == null) {
            menu.getChildAt(section).requestFocus();
        } else {
            finish();
        }
    }

    // ---- sections --------------------------------------------------------------------------------

    private void show() {
        rebuilding = true;
        for (int i = 0; i < menu.getChildCount(); i++) menu.getChildAt(i).setActivated(i == section);
        content.removeAllViews();
        switch (section) {
            case 0: look(); break;
            case 1: pictures(); break;
            case 2: weatherSection(); break;
            case 3: cards(); break;
            case 4: apps(); break;
            default: about();
        }
        rebuilding = false;
    }

    /** Replaces the rows and puts focus back on row {@code index}. Only for changes that add or remove rows. */
    private void rebuild(int index) {
        show();
        focusRow(index);
    }

    private void look() {
        add(flagRow("Home layout", "PS5", "Classic", new Flag() {
            public boolean get() { return HomePrefs.ps5(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.ps5(SettingsActivity.this, v); }
        }));
        add(flagRow("Navigation sounds", "On", "Off", new Flag() {
            public boolean get() { return HomePrefs.ps5Sound(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.ps5Sound(SettingsActivity.this, v); }
        }));
        note("PS5 is the home with the pinned apps along the top, a picture behind the focused app and a click sound "
                + "for each move (the pictures and sounds are optional files, see the README). Its last tile, All apps, "
                + "opens the classic home with every app, Keep watching, Now playing and the power menu. The clock "
                + "style below is for the classic home.");
        add(flagRow("Clock style", "Big", "Small", new Flag() {
            public boolean get() { return HomePrefs.bigClock(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.bigClock(SettingsActivity.this, v); }
        }));
        add(flagRow("Clock format", "24 hour", "12 hour", new Flag() {
            public boolean get() { return HomePrefs.clock24h(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.clock24h(SettingsActivity.this, v); }
        }));
        note("Big puts a tall clock behind the scenery, like couch-tv's Night look. The scenes with a cut-out "
                + "(Night, Golden hour, Fog, Rainforest dawn) overlap its bottom edge.");
    }

    private void pictures() {
        final View every = row("Picture changes every", everyLabel(), null);
        every.setOnClickListener(v -> {
            int cur = 0;
            for (int i = 0; i < EVERY_SECONDS.length; i++) if (EVERY_SECONDS[i] == HomePrefs.wallSeconds(this)) cur = i;
            HomePrefs.wallSeconds(this, EVERY_SECONDS[(cur + 1) % EVERY_SECONDS.length]);
            setValue(every, everyLabel());
        });
        add(every);
        final List<Backdrop.Item> items = Backdrop.catalog(this);
        for (final Backdrop.Item it : items) {
            final View r = row(it.caption.isEmpty() ? it.id : it.caption,
                    HomePrefs.wallOff(this).contains(it.id) ? "Off" : "On", null);
            thumb(r, it);
            r.setOnClickListener(v -> {
                Set<String> off = HomePrefs.wallOff(this);
                if (off.contains(it.id)) {
                    off.remove(it.id);
                } else if (items.size() - off.size() <= 1) {
                    Toast.makeText(this, "Keep at least one picture on", Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    off.add(it.id);
                }
                HomePrefs.wallOff(this, off);
                setValue(r, off.contains(it.id) ? "Off" : "On");
            });
            add(r);
        }
    }

    private String everyLabel() {
        for (int i = 0; i < EVERY_SECONDS.length; i++) {
            if (EVERY_SECONDS[i] == HomePrefs.wallSeconds(this)) return EVERY_LABELS[i];
        }
        return HomePrefs.wallSeconds(this) + " seconds";
    }

    private void weatherSection() {
        add(flagRow("Weather", "Shown", "Hidden", new Flag() {
            public boolean get() { return HomePrefs.weatherOn(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.weatherOn(SettingsActivity.this, v); }
        }));
        add(flagRow("Temperature", "°F", "°C", new Flag() {
            public boolean get() { return HomePrefs.fahrenheit(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.fahrenheit(SettingsActivity.this, v); }
        }));
        add(row("Place", HomePrefs.placeName(this), this::placeSearch));
        note("Weather comes from Open-Meteo, free and with no account. Only the place's coordinates are sent.");
    }

    private void cards() {
        add(flagRow("Keep watching", "Shown", "Hidden", new Flag() {
            public boolean get() { return HomePrefs.keepWatching(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.keepWatching(SettingsActivity.this, v); }
        }));
        add(row("Clear the list", "", () -> {
            RecentApps.clear(this);
            Toast.makeText(this, "Keep watching cleared", Toast.LENGTH_SHORT).show();
        }));
        add(flagRow("Now playing", "Shown", "Hidden", new Flag() {
            public boolean get() { return HomePrefs.nowPlaying(SettingsActivity.this); }
            public void set(boolean v) { HomePrefs.nowPlaying(SettingsActivity.this, v); }
        }));
        final boolean access = MediaWatcher.hasAccess(this);
        add(row("Music access", access ? "On" : "Needs setup", () ->
                Toast.makeText(this, access ? "Music access is on" : getString(R.string.np_needs_access), Toast.LENGTH_LONG).show()));
        note("Keep watching lists the apps you opened from this home, newest first. Now playing shows the song, "
                + "artist and cover of music playing in another app, with previous, play or pause and next.");
    }

    private void apps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        final List<String[]> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            list.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
        }
        Collections.sort(list, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        for (final String[] app : list) {
            final View r = row(app[0], HomePrefs.hiddenApps(this).contains(app[1]) ? "Hidden" : "Shown", null);
            r.setOnClickListener(v -> {
                Set<String> hidden = HomePrefs.hiddenApps(this);
                if (!hidden.remove(app[1])) hidden.add(app[1]);
                HomePrefs.hiddenApps(this, hidden);
                setValue(r, hidden.contains(app[1]) ? "Hidden" : "Shown");
            });
            add(r);
        }
        note("Hidden apps stay installed. Open an app from the home, then hold OK on it to pin it to the dock.");
    }

    private void about() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // no version to show
        }
        add(row("Version", version, null));
        final View reset = row("Reset home settings", "", null);
        reset.setOnClickListener(v -> {
            HomePrefs.reset(this);
            RecentApps.clear(this);
            Toast.makeText(this, "Home settings reset", Toast.LENGTH_SHORT).show();
        });
        add(reset);
        note("Scenes: generated for couch-tv, free to use. Photos: CC0 / Unsplash. See CREDITS.md in the project.");
    }

    // ---- place search ----------------------------------------------------------------------------

    private void placeSearch() {
        back = () -> rebuild(2);
        rebuilding = true;
        content.removeAllViews();
        rebuilding = false;
        final EditText box = new EditText(this);
        box.setHint("Type a town or city, then press OK");
        box.setHintTextColor(0x80FFFFFF);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setTypeface(Fonts.regular(this));
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x4DFFFFFF);
        box.setBackground(bg);
        content.addView(box, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, 0, 0, dp(14)));
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        box.setOnEditorActionListener((v, action, e) -> {
            String q = box.getText().toString().trim();
            if (!q.isEmpty()) search(q, results);
            return true;
        });
        box.requestFocus();
    }

    private void search(final String q, final LinearLayout results) {
        results.removeAllViews();
        results.addView(text("Searching…", 16, 0x99FFFFFF, Fonts.regular(this)));
        new Thread(() -> {
            final List<String[]> found = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(OpenMeteo.get(this,
                        "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + URLEncoder.encode(q, "UTF-8")));
                JSONArray arr = o.optJSONArray("results");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    String where = r.optString("name");
                    if (!r.optString("admin1").isEmpty()) where += ", " + r.optString("admin1");
                    if (!r.optString("country").isEmpty()) where += ", " + r.optString("country");
                    found.add(new String[]{r.optString("name"), where, String.valueOf(r.getDouble("latitude")),
                            String.valueOf(r.getDouble("longitude"))});
                }
            } catch (Exception e) {
                found.clear();
            }
            main.post(() -> {
                if (isFinishing()) return;
                results.removeAllViews();
                if (found.isEmpty()) {
                    results.addView(text("No place found. Check the spelling, or your Wi-Fi.", 16, 0x99FFFFFF, Fonts.regular(this)));
                    return;
                }
                for (final String[] p : found) {
                    View r = row(p[1], "", () -> {
                        HomePrefs.place(this, p[0], p[2], p[3]);
                        Toast.makeText(this, p[0] + " set", Toast.LENGTH_SHORT).show();
                        Runnable b = back;
                        back = null;
                        if (b != null) b.run();
                    });
                    results.addView(r);
                }
                results.getChildAt(0).requestFocus();
            });
        }).start();
    }

    // ---- rows ------------------------------------------------------------------------------------

    private interface Flag {
        boolean get();
        void set(boolean value);
    }

    /** An on/off row. Clicking flips the saved value and rewrites the row's own text: no row is removed or rebuilt,
     *  so focus stays put. */
    private View flagRow(String name, final String on, final String off, final Flag f) {
        final View r = row(name, f.get() ? on : off, null);
        r.setOnClickListener(v -> {
            f.set(!f.get());
            setValue(r, f.get() ? on : off);
        });
        return r;
    }

    private void setValue(View row, String text) {
        ((TextView) row.findViewById(R.id.status)).setText(text);
    }

    /** A focusable row: title on the left, value on the right. Menu rows and rows without an action pass null. */
    private View row(String name, String value, Runnable click) {
        View r = getLayoutInflater().inflate(R.layout.item_input, null, false);
        r.findViewById(R.id.icon).setVisibility(View.GONE);
        r.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView t = r.findViewById(R.id.title);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Fonts.medium(this));
        TextView v = r.findViewById(R.id.status);
        v.setText(value == null ? "" : value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.setTypeface(Fonts.regular(this));
        r.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, 0, 0, dp(4)));
        r.setPadding(dp(18), 0, dp(18), 0);
        if (click != null) r.setOnClickListener(x -> click.run());
        // Left from a row goes back to the menu (or out of a sub-page); the menu rows set their own keys.
        r.setOnKeyListener((vw, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT && menu.indexOfChild(vw) < 0) {
                if (back != null) {
                    Runnable b = back;
                    back = null;
                    b.run();
                } else {
                    menu.getChildAt(section).requestFocus();
                }
                return true;
            }
            return false;
        });
        return r;
    }

    private void add(View v) {
        content.addView(v);
    }

    private void note(String s) {
        TextView t = text(s, 14, 0x80FFFFFF, Fonts.regular(this));
        t.setPadding(dp(18), dp(14), dp(18), 0);
        content.addView(t);
    }

    private void thumb(View row, final Backdrop.Item it) {
        final ImageView icon = row.findViewById(R.id.icon);
        icon.setVisibility(View.VISIBLE);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.clearColorFilter();
        icon.setImageTintList(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(32));
        icon.setLayoutParams(p);
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 16;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = getAssets().open(it.bg)) {
                final Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
                main.post(() -> icon.setImageBitmap(bmp));
            } catch (Exception ignored) {
                // no thumbnail
            }
        }).start();
    }

    private void focusFirstRow() {
        focusRow(0);
    }

    private void focusRow(int index) {
        for (int i = Math.min(index, content.getChildCount() - 1); i >= 0; i--) {
            View v = content.getChildAt(i);
            if (v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i).isFocusable()) {
                content.getChildAt(i).requestFocus();
                return;
            }
        }
    }

    private TextView text(String s, float sp, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
