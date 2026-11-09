package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link #dockCount()} positions are the pinned dock, in the order the user set; the rest
 * is alphabetical. The pinned list is saved on the device and starts from res/values/config.xml (dock_apps).
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Hold OK or press Menu on a tile: pin, unpin, reorder, app info. */
        void onTileMenu(int position, View card);
    }

    // SmartTube is this TV's YouTube: it takes YouTube's name and banner (from the stock app, which stays hidden).
    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, String> BANNER_FROM = new HashMap<>();
    static {
        LABELS.put("org.smarttube.stable", "YouTube");
        BANNER_FROM.put("org.smarttube.stable", "com.amazon.firetv.youtube");
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        /** Wide art filling the tile: the app's TV banner, or its icon when that is already landscape. */
        final Drawable art;
        /** Square icon shown small on a slate tile when the app has no wide art. */
        final Drawable icon;

        Entry(String label, ComponentName component, Drawable art, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.art = art;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.08f;
    static final float CORNER_RATIO = 0.15f;

    private final Context ctx;
    private final TileListener listener;
    private static final String PREFS = "tvhome";
    private static final String PREF_DOCK = "dock";
    private final int maxDock;
    private int dockCount;
    /** Pinned packages in dock order, including any that are not installed right now. */
    private final List<String> pinned = new ArrayList<>();
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int maxDock) {
        this.ctx = ctx;
        this.listener = listener;
        this.maxDock = maxDock;
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_DOCK, null);
        pinned.addAll(saved != null
                ? (saved.isEmpty() ? Collections.emptyList() : Arrays.asList(saved.split(",")))
                : Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> extraHidden = HomePrefs.hiddenApps(ctx);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || extraHidden.contains(pkg)) continue;
            // One tile per app: skip extra launcher entries (AirPlay registers three).
            Intent main = pm.getLeanbackLaunchIntentForPackage(pkg);
            ComponentName mainComponent = main != null ? main.getComponent() : null;
            if (mainComponent != null && !mainComponent.getClassName().equals(ri.activityInfo.name)) continue;
            if (!seen.add(pkg)) continue;
            Drawable icon = ri.loadIcon(pm);
            Drawable art = wideArt(pm, BANNER_FROM.containsKey(pkg) ? BANNER_FROM.get(pkg) : pkg, ri, icon);
            String label = LABELS.containsKey(pkg) ? LABELS.get(pkg) : String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), art, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = pinned.indexOf(a.pkg), fb = pinned.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        int docked = 0;
        while (docked < items.size() && pinned.contains(items.get(docked).pkg)) docked++;
        dockCount = Math.min(docked, maxDock);
        notifyDataSetChanged();
    }

    /** The banner of {@code artPkg}, else a landscape launcher icon (most Fire TV apps ship one), else null. */
    private static Drawable wideArt(PackageManager pm, String artPkg, ResolveInfo ri, Drawable icon) {
        try {
            Drawable b = pm.getApplicationBanner(artPkg);
            if (b == null && !artPkg.equals(ri.activityInfo.packageName)) b = pm.getApplicationIcon(artPkg);
            if (b == null) b = ri.activityInfo.loadBanner(pm);
            if (b != null) return b;
        } catch (PackageManager.NameNotFoundException ignored) {
            // the art source is not installed: fall through to this app's own icon
        }
        int w = icon != null ? icon.getIntrinsicWidth() : 0, h = icon != null ? icon.getIntrinsicHeight() : 0;
        return h > 0 && w >= h * 1.4f ? icon : null;
    }

    // ---- dock editing ----------------------------------------------------------------------------

    int dockCount() {
        return dockCount;
    }

    boolean isDocked(int position) {
        return position >= 0 && position < dockCount;
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : "";
    }

    int positionOf(String pkg) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Adds the app to the end of the dock. False when the dock is full. */
    boolean pin(String pkg) {
        if (pinned.contains(pkg)) return true;
        if (dockCount >= maxDock) return false;
        for (java.util.Iterator<String> it = pinned.iterator(); it.hasNext(); ) if (positionOf(it.next()) < 0) it.remove();   // forget uninstalled pins so they don't hold slots
        pinned.add(pkg);
        saveAndReload();
        return true;
    }

    void unpin(String pkg) {
        pinned.remove(pkg);
        saveAndReload();
    }

    /** Swaps the app with its dock neighbour on the left (-1) or right (+1). */
    void move(String pkg, int delta) {
        int from = pinned.indexOf(pkg), to = from + delta;
        if (from < 0 || to < 0 || to >= pinned.size()) return;
        Collections.swap(pinned, from, to);
        saveAndReload();
    }

    private void saveAndReload() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_DOCK, String.join(",", pinned)).apply();
        reload();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener, this);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        boolean docked = isDocked(position);
        h.name.setVisibility(docked ? View.INVISIBLE : View.VISIBLE);   // the dock uses the tooltip instead
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        h.card.setTranslationY(0f);
        boolean art = e.art != null;
        h.card.setBackground(art ? null : ctx.getDrawable(R.drawable.dock_tile_bg));
        h.banner.setImageDrawable(e.art);
        h.banner.setVisibility(art ? View.VISIBLE : View.GONE);
        h.icon.setImageDrawable(art ? null : e.icon);
        h.icon.setVisibility(art ? View.GONE : View.VISIBLE);
        h.label.setText(e.label);
        h.label.setVisibility(art ? View.GONE : View.VISIBLE);
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, v);
            return true;
        });
    }

    /** The tile for a package, or null when it is not shown (uninstalled or hidden). */
    Entry entryOf(String pkg) {
        for (Entry e : items) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    /** Opens an app by package, like pressing its tile. False when it is not shown. */
    boolean open(String pkg) {
        Entry e = entryOf(pkg);
        if (e == null) return false;
        launch(e);
        return true;
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        RecentApps.record(ctx, e.pkg);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener, AppAdapter owner) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            name.setTypeface(Fonts.medium(v.getContext()));
            label.setTypeface(Fonts.medium(v.getContext()));

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // Squircle-ish corners: 22.5% of the short side, matching the dock icon artwork.
                    float radius = Math.min(view.getWidth(), view.getHeight()) * CORNER_RATIO;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (listener == null || pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    listener.onTileMenu(pos, view);
                    return true;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                // Ambient-glass: a gentle grow, no overshoot.
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 240 : 180)
                        .setInterpolator(new DecelerateInterpolator(1.8f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
