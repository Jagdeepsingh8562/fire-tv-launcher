package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private TextView heroName;
    private boolean heroShown;
    private Weather weather;
    private Backdrop backdrop;
    private ImageView wallFg;
    private View bigBlock, leftBlock;
    private TextView bigClock, bigDate, bigWeatherTemp, caption;
    private View bigWeather, weatherBox;
    private ImageView bigWeatherIcon, weatherIcon;
    private TextView weatherTemp;
    private Bitmap cutout;
    private int lastTemp = Integer.MIN_VALUE, lastIcon;
    private CardsRow cards;
    private MediaWatcher media;
    private MediaWatcher.Info nowPlaying;
    private TextView greeting;
    private View gridShade;
    private boolean shadeOn;
    private TextView btnWifi;
    private BudsToggle buds;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int COLOR_WARN = 0xFFFFB340;   // connected, no internet
    private static final int COLOR_ERROR = 0xFFFF5A52;  // no network
    private static final int[] WIFI_ICONS = {R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi};
    // Refresh the Wi-Fi pill as soon as the connection changes, not on the next minute tick.
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onLost(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            weather.refreshIfStale();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        caption = findViewById(R.id.caption);
        gridShade = findViewById(R.id.gridShade);
        btnWifi = findViewById(R.id.btnWifi);
        applyFonts();
        leftBlock = findViewById(R.id.leftBlock);
        bigBlock = findViewById(R.id.bigBlock);
        bigClock = findViewById(R.id.bigClock);
        bigDate = findViewById(R.id.bigDate);
        bigWeather = findViewById(R.id.bigWeather);
        bigWeatherIcon = findViewById(R.id.bigWeatherIcon);
        bigWeatherTemp = findViewById(R.id.bigWeatherTemp);
        wallFg = findViewById(R.id.wallFg);
        weatherBox = findViewById(R.id.weather);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherTemp.setTypeface(Fonts.regular(this));
        bigClock.setTypeface(Fonts.antonio(this));
        bigDate.setTypeface(Fonts.medium(this));
        bigWeatherTemp.setTypeface(Fonts.regular(this));
        backdrop = new Backdrop(this, (ImageView) findViewById(R.id.wallA), (ImageView) findViewById(R.id.wallB),
                caption, this::onPicture);
        weather = new Weather(this, (c, icon) -> {
            lastTemp = c;
            lastIcon = icon;
            showWeather();
        });

        grid = findViewById(R.id.grid);
        // One span unit per cell of any dock size 1..COLUMNS: the dock row splits evenly, grid rows are COLUMNS wide.
        final int spans = lcmUpTo(COLUMNS);
        GridLayoutManager glm = new GridLayoutManager(this, spans);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isDocked(position) ? spans / adapter.dockCount() : spans / COLUMNS;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);

        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.addItemDecoration(new DockDecoration(adapter, COLUMNS, density));
        grid.setAdapter(adapter);
        cards = new CardsRow(this, adapter, new CardsRow.Callbacks() {
            @Override public void up() { btnWifi.requestFocus(); }
            @Override public void down() { focusTile(adapter.isDocked(lastFocused) ? lastFocused : 0); }
            @Override public void open(String pkg) { adapter.open(pkg); }
        });
        cards.setVisibility(View.GONE);
        root.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        media = new MediaWatcher(this, info -> {
            nowPlaying = info;
            refreshCards();
        });

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((123 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow + (int) (8 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                placeCards();
                // The dock just moved: put the glass and the title back on the focused tile after this layout.
                grid.post(() -> {
                    View f = getCurrentFocus();
                    RecyclerView.ViewHolder vh = f != null && f.getId() == R.id.card ? grid.findContainingViewHolder(f) : null;
                    if (vh != null && vh.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                        onTileFocused(vh.getBindingAdapterPosition(), f);
                    }
                });
            }
        });

        // Keep the glass glued to its tile while the grid scrolls, and snap it to the tile's real place once the
        // scroll stops (summed scroll steps drift, e.g. when Home opens with the grid still scrolled down).
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE || !glassShown) return;
                View f = getCurrentFocus();
                if (f == null || f.getId() != R.id.card) return;
                Rect r = new Rect(0, 0, f.getWidth(), f.getHeight());
                root.offsetDescendantRectToMyCoords(f, r);
                float pad = 6f * density;
                glassTargetY = r.exactCenterY() - (f.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad) / 2f;
                springY.animateToFinalPosition(glassTargetY);
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.9f));
        }

        View btnSettings = findViewById(R.id.btnSettings);
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnWifi.setOnFocusChangeListener((v, focused) -> updateWifiLabel());
        ((ViewGroup) findViewById(R.id.topButtons)).getLayoutTransition()
                .enableTransitionType(android.animation.LayoutTransition.CHANGING);
        btnSettings.setOnClickListener(v -> showSettingsMenu(v));
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu(v);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_MENU && v.getId() == R.id.btnSettings) {
                showSettingsMenu(v);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnWifi) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnPower;
        };
        tintIcons((TextView) btnSettings);
        TextView btnBt = findViewById(R.id.btnBt);
        tintIcons(btnBt);
        btnBt.setOnKeyListener(pillKeys);
        buds = new BudsToggle(this, btnBt, icon -> {
            btnBt.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            tintIcons(btnBt);
        });
        TextView btnPower = findViewById(R.id.btnPower);
        tintIcons(btnPower);
        btnPower.setOnClickListener(v -> showPowerMenu(v));
        btnPower.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                setShade(false);
                hideGlass();
                hideHero();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        adapter.reload();
        refreshCards();
        if (HomePrefs.nowPlaying(this)) media.start();
        // Test hook: adb shell am start -n com.gathin.tvhome/.MainActivity --ez sampleSong true
        if (getIntent().getBooleanExtra("sampleSong", false)) {
            nowPlaying = new MediaWatcher.Info("com.spotify.music", "Blinding Lights", "The Weeknd", null, true, null);
            refreshCards();
        }
        grid.scheduleLayoutAnimation();
        updateGreeting();
        applyLook();
        updateWifiLabel();
        weather.refreshIfStale();
        handler.postDelayed(minuteTick, MINUTE_MS);
        buds.resume();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().build(), networkCallback);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            RecyclerView.ViewHolder vh = focused != null ? grid.findContainingViewHolder(focused) : null;
            if (vh != null && focused.getId() == R.id.card && vh.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                onTileFocused(vh.getBindingAdapterPosition(), focused);   // still focused: re-place glass and title
                return;
            }
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        buds.pause();
        super.onPause();
        handler.removeCallbacks(minuteTick);
        backdrop.stop();
        media.stop();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        boolean docked = adapter.isDocked(position);
        setShade(!docked);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(Math.min(card.getWidth(), card.getHeight()) * AppAdapter.CORNER_RATIO * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (docked) {
            // Dock row back to its resting place at the bottom; the glass follows via the scroll listener.
            int dy = item.getTop() - (int) (6 * density) - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            r.offset(0, -dy);
            showHero(position, r);
            return;
        }
        hideHero();
        // Scroll just enough so the whole glass (not only the tile) is visible.
        Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
        grid.requestChildRectangleOnScreen(item, want, false);
    }

    // ---- hero title ------------------------------------------------------------------------------

    /** The focused dock app's name in large light type, above the left end of the glass band. */
    private void showHero(int position, Rect card) {
        heroName.setText(adapter.labelAt(position));
        heroName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float bandLeft = grid.getPaddingLeft() - 16 * density;
        Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, g);
        // Where the dock rests (grid padding + decoration 6dp + item padding 8dp), minus the band's 16dp margin;
        // not the card's current rect, which is still moving on the first layout or while scrolling back.
        float bandTop = g.top + grid.getPaddingTop() + (6 + 8 - 16) * density;
        heroName.setX(g.left + bandLeft + 8 * density);
        heroName.setY(bandTop - 6 * density - heroName.getMeasuredHeight());
        if (!heroShown) {
            // Fade only: setY() above works through translationY, so animating that would undo it.
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dock = adapter.dockCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dock) {                          // dock row: the cards above it, else the top bar
                View first = cards.getVisibility() == View.VISIBLE ? cards.firstCard() : null;
                if (first == null) return false;
                first.requestFocus();
                return true;
            }
            int col = (position - dock) % COLUMNS;
            if (position - dock < COLUMNS) {
                if (dock == 0) return false;
                target = Math.min(col, dock - 1);          // first grid row: up to the dock
            } else {
                target = position - COLUMNS;
            }
        } else {
            if (position < dock) {
                if (count == dock) return true;            // nothing below the dock
                target = Math.min(dock + position, count - 1);
            } else {
                int lastRowStart = dock + ((count - 1 - dock) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true; // already on the last row: stay put
                target = Math.min(position + COLUMNS, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    private static int lcmUpTo(int n) {
        int l = 1;
        for (int i = 2; i <= n; i++) {
            int a = l, b = i;
            while (b != 0) { int t = a % b; a = b; b = t; }
            l = l / a * i;
        }
        return l;
    }

    // ---- dock editing ----------------------------------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        String pkg = adapter.pkgAt(position);
        boolean docked = adapter.isDocked(position);
        int dock = adapter.dockCount();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (220 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        if (docked) {
            addInputRow(rows, popup, R.drawable.ic_unpin, getString(R.string.unpin_from_dock), -1, () -> {
                adapter.unpin(pkg);
                focusAfterLayout(pkg);
            });
            if (position > 0) addInputRow(rows, popup, R.drawable.ic_left, getString(R.string.move_left), -1, () -> {
                adapter.move(pkg, -1);
                focusAfterLayout(pkg);
            });
            if (position < dock - 1) addInputRow(rows, popup, R.drawable.ic_right, getString(R.string.move_right), -1, () -> {
                adapter.move(pkg, 1);
                focusAfterLayout(pkg);
            });
        } else {
            addInputRow(rows, popup, R.drawable.ic_pin, getString(R.string.pin_to_dock), -1, () -> {
                if (adapter.pin(pkg)) focusAfterLayout(pkg);
                else Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_info, getString(R.string.app_info), -1, () ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));

        // Float the menu just above the tile, centred on it; the name tooltip steps aside while it's open.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        float top = r.top;
        int x = (int) (r.exactCenterX() - width / 2f);
        int y = (int) (top - 12 * density - panel.getMeasuredHeight());
        heroName.animate().alpha(0f).setDuration(120).start();
        popup.setOnDismissListener(() -> {
            if (adapter.isDocked(lastFocused) && heroShown) heroName.animate().alpha(1f).setDuration(160).start();
        });
        panel.setAlpha(0f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** Settings button: Home settings (this launcher's own) or the TV's own settings. */
    private void showSettingsMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (230 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.home_settings), -1,
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        addInputRow(rows, popup, R.drawable.ic_tv, getString(R.string.tv_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        panel.setAlpha(0f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** Small menu under the power button: Sleep, and Restart (Fire OS only lets its own apps reboot). */
    private void showPowerMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (250 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        addInputRow(rows, popup, R.drawable.ic_sleep, getString(R.string.sleep), -1, this::sleepNow);
        int left = SleepTimer.minutesLeft(this);
        if (left > 0) {
            addInputRow(rows, popup, R.drawable.ic_timer, getString(R.string.sleep_timer_cancel, left), -1, () -> {
                SleepTimer.cancel(this);
                Toast.makeText(this, R.string.sleep_timer_off, Toast.LENGTH_SHORT).show();
            });
        }
        for (int m : new int[]{30, 60, 90}) {
            addInputRow(rows, popup, R.drawable.ic_timer, getString(R.string.sleep_in, m), -1, () -> {
                if (!adminOn()) return;
                SleepTimer.start(this, m);
                Toast.makeText(this, getString(R.string.sleep_timer_set, m), Toast.LENGTH_SHORT).show();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_restart, getString(R.string.restart), -1, () ->
                open(new Intent().setClassName("com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.device.DeviceActivity")));
        panel.setAlpha(0f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void sleepNow() {
        if (adminOn()) SleepTimer.sleepNow(this);
    }

    /** Device admin is what lets the launcher put the TV to sleep; say how to turn it on when it is off. */
    private boolean adminOn() {
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isAdminActive(new android.content.ComponentName(this, AdminReceiver.class))) return true;
        Toast.makeText(this, R.string.sleep_needs_admin, Toast.LENGTH_LONG).show();
        return false;
    }

    /** After the dock changes, focus the app's new tile once the grid has re-laid out. */
    private void focusAfterLayout(String pkg) {
        hideHero();
        ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup().invalidateSpanIndexCache();
        grid.invalidateItemDecorations();
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int pos = adapter.positionOf(pkg);
                RecyclerView.ViewHolder vh = pos >= 0 ? grid.findViewHolderForAdapterPosition(pos) : null;
                if (vh == null) {
                    focusTile(Math.max(pos, 0));
                    return;
                }
                View c = vh.itemView.findViewById(R.id.card);
                c.requestFocus();
                onTileFocused(pos, c);
            }
        });
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    // Morning 5-12, afternoon 12-17, evening 17-22, night 22-5. Indexes GREETINGS.
    private static final int[] GREETINGS = {
            R.string.good_morning, R.string.good_afternoon, R.string.good_evening, R.string.good_night};

    private static int dayPart() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return 3;
        if (hour < 12) return 0;
        if (hour < 17) return 1;
        if (hour < 22) return 2;
        return 3;
    }

    private void updateGreeting() {
        String text = getString(GREETINGS[dayPart()]);
        String name = getString(R.string.user_name).trim();
        greeting.setText(name.isEmpty() ? text : text + ", " + name);
    }

    /** Reads the Home settings: clock style and format, weather, pictures. Called on every resume. */
    private void applyLook() {
        boolean big = HomePrefs.bigClock(this), h24 = HomePrefs.clock24h(this);
        String f = h24 ? "HH:mm" : "h:mm";
        for (int id : new int[]{R.id.clock, R.id.bigClock}) {
            TextClock c = findViewById(id);
            c.setFormat12Hour(f);
            c.setFormat24Hour(f);
        }
        findViewById(R.id.ampm).setVisibility(h24 ? View.GONE : View.VISIBLE);
        leftBlock.setVisibility(big ? View.INVISIBLE : View.VISIBLE);
        bigBlock.setVisibility(big ? View.VISIBLE : View.GONE);
        weather.setPlace(HomePrefs.latitude(this), HomePrefs.longitude(this), HomePrefs.fahrenheit(this));
        showWeather();
        weather.refreshIfStale();
        backdrop.reload();
        backdrop.start();
        // Test hook: adb shell am start -n com.gathin.tvhome/.MainActivity --ei picture 0..12
        int forced = getIntent().getIntExtra("picture", -1);
        if (forced >= 0) backdrop.show(forced);
    }

    /** The backdrop changed picture: place the big clock for it and swap its cut-out layer. */
    private void onPicture(Backdrop.Item item, Bitmap newCutout) {
        boolean big = HomePrefs.bigClock(this);
        float d = density;
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) bigClock.getLayoutParams();
        // clockTop is where the digits start; Antonio leaves ~0.23 em of space above them inside its line.
        cp.topMargin = (int) ((item.clockTop - 0.23f * item.clockSize) * d);
        cp.leftMargin = item.left ? (int) (48 * d) : 0;
        bigClock.setGravity(item.left ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        bigClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.clockSize);
        bigClock.setTextColor(item.dark ? 0xCC1B2622 : 0xE6FFFFFF);
        bigClock.setLayoutParams(cp);
        FrameLayout.LayoutParams dp = (FrameLayout.LayoutParams) bigDateRow().getLayoutParams();
        dp.topMargin = (int) (item.dateTop * d);
        dp.leftMargin = item.left ? (int) (52 * d) : 0;
        ((LinearLayout) bigDateRow()).setGravity(item.left ? Gravity.START | Gravity.CENTER_VERTICAL
                : Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        bigDateRow().setLayoutParams(dp);
        bigDate.setTextColor(item.dark ? 0xFF16201D : 0xF2FFFFFF);
        bigWeatherTemp.setTextColor(item.dark ? 0xB816201D : 0xD9FFFFFF);
        bigDate.setShadowLayer(item.dark ? 0 : 10, 0, 0, 0x66000000);
        // The cut-out fades in over the new picture, behind the buttons and dock but in front of the clock.
        final Bitmap old = cutout;
        cutout = big ? newCutout : null;
        wallFg.animate().cancel();
        wallFg.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            wallFg.setImageBitmap(cutout);
            wallFg.setVisibility(cutout != null ? View.VISIBLE : View.GONE);
            if (cutout != null) wallFg.animate().alpha(1f).setDuration(900).start();
            if (old != null && old != cutout && !old.isRecycled()) old.recycle();
        }).start();
    }

    private View bigDateRow() {
        return findViewById(R.id.bigDateRow);
    }

    private void showWeather() {
        boolean show = HomePrefs.weatherOn(this) && lastTemp != Integer.MIN_VALUE;
        String t = (HomePrefs.fahrenheit(this) ? Math.round(lastTemp * 9 / 5f + 32) : lastTemp) + "\u00B0";
        weatherIcon.setImageResource(lastIcon);
        weatherTemp.setText(t);
        bigWeatherIcon.setImageResource(lastIcon);
        bigWeatherTemp.setText(t);
        weatherBox.setVisibility(show ? View.VISIBLE : View.GONE);
        bigWeather.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void applyFonts() {
        ((TextView) findViewById(R.id.greeting)).setTypeface(Fonts.semibold(this));
        ((TextView) findViewById(R.id.clock)).setTypeface(Fonts.light(this));
        ((TextView) findViewById(R.id.ampm)).setTypeface(Fonts.medium(this));
        ((TextView) findViewById(R.id.date)).setTypeface(Fonts.regular(this));
        heroName.setTypeface(Fonts.light(this));
        caption.setTypeface(Fonts.regular(this));
        btnWifi.setTypeface(Fonts.medium(this));
    }

    private void refreshCards() {
        boolean has = cards.refresh(HomePrefs.keepWatching(this), HomePrefs.nowPlaying(this) ? nowPlaying : null);
        cards.setVisibility(has ? View.VISIBLE : View.GONE);
        placeCards();
    }

    /** The cards sit just above the big app name, which sits above the glass band. */
    private void placeCards() {
        if (grid == null || cards == null || cards.getVisibility() != View.VISIBLE) return;
        Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, g);
        float bandLeft = g.left + grid.getPaddingLeft() - 16 * density;
        float bandTop = g.top + grid.getPaddingTop() + (6 + 8 - 16) * density;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cards.getLayoutParams();
        p.leftMargin = (int) (bandLeft - 14 * density);
        p.rightMargin = (int) (bandLeft - 14 * density);
        p.topMargin = (int) (bandTop - 66 * density - 74 * density);
        cards.setLayoutParams(p);
    }

    private void setShade(boolean on) {
        if (on == shadeOn) return;
        shadeOn = on;
        gridShade.animate().alpha(on ? 1f : 0f).setDuration(on ? 320 : 240).start();
        cards.animate().alpha(on ? 0f : 1f).setDuration(on ? 200 : 300).start();
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    // Signal bars only (no network name, so screenshots stay private); text only when something is wrong.
    private void updateWifiLabel() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        if (ni == null || !ni.isConnected()) {
            setWifiPill(R.drawable.ic_wifi_off, getString(R.string.offline), COLOR_ERROR, null);
            return;
        }
        // ponytail: API 22 has no NET_CAPABILITY_VALIDATED, so "no internet" is not detected here.
        boolean online = true;
        // Highlighted, a healthy button opens into a pill with the network name and the TV's IP address.
        boolean detail = btnWifi.isFocused();
        String ip = ipAddress();
        if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
            setWifiPill(R.drawable.ic_ethernet, getString(R.string.ethernet), 0,
                    detail ? join(getString(R.string.ethernet), ip) : null);
            return;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        int bars = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), WIFI_ICONS.length) : 0;
        if (!online) {
            setWifiPill(WIFI_ICONS[bars], getString(R.string.no_internet), COLOR_WARN, null);
            return;
        }
        String ssid = info != null ? info.getSSID() : null;
        if (ssid != null) ssid = ssid.replace("\"", "");
        if (ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>")) ssid = getString(R.string.wifi);
        int v4 = info != null ? info.getIpAddress() : 0;   // little-endian; no INTERNET permission needed
        if (v4 != 0) ip = (v4 & 0xFF) + "." + (v4 >> 8 & 0xFF) + "." + (v4 >> 16 & 0xFF) + "." + (v4 >>> 24);
        setWifiPill(WIFI_ICONS[bars], getString(R.string.wifi), 0, detail ? join(ssid, ip) : null);
    }

    private static String join(String a, String b) {
        return b == null ? a : a + "  \u00B7  " + b;
    }

    /** The TV's IPv4 address from its network interfaces (Ethernet), or null. */
    private static String ipAddress() {
        try {
            for (java.net.NetworkInterface nif : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) {
            // no address to show
        }
        return null;
    }

    /** Dark icon on the white focused pill. android:drawableTint needs API 23, so tint in code for Fire OS 5. */
    private void tintIcons(TextView b) {
        android.content.res.ColorStateList tint = getResources().getColorStateList(R.drawable.pill_text);
        for (android.graphics.drawable.Drawable d : b.getCompoundDrawablesRelative())
            if (d != null) d.mutate().setTintList(tint);
    }

    private void setWifiPill(int icon, String label, int alertColor, String detail) {
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        tintIcons(btnWifi);
        btnWifi.setContentDescription(label);
        // Healthy: a round icon button like Inputs and Settings. Alert: a pill with the status text.
        boolean iconOnly = alertColor == 0 && detail == null;
        ViewGroup.LayoutParams lp = btnWifi.getLayoutParams();
        lp.width = iconOnly ? (int) (42 * density) : ViewGroup.LayoutParams.WRAP_CONTENT;
        btnWifi.setLayoutParams(lp);
        btnWifi.setPaddingRelative((int) ((iconOnly ? 10 : 16) * density), 0, (int) ((iconOnly ? 10 : 20) * density), 0);
        btnWifi.setCompoundDrawablePadding(iconOnly ? 0 : (int) (9 * density));
        if (iconOnly) {
            btnWifi.setText("");
            return;
        }
        if (detail != null) {
            btnWifi.setText(detail);
            return;
        }
        SpannableString text = new SpannableString("\u25CF  " + label);
        text.setSpan(new ForegroundColorSpan(alertColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        TextView titleView = row.findViewById(R.id.title);
        titleView.setText(title);
        titleView.setTypeface(Fonts.medium(this));
        TextView status = row.findViewById(R.id.status);
        status.setTypeface(Fonts.regular(this));
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
