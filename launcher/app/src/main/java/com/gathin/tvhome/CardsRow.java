package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A glass row of cards above the dock, after couch-tv's Keep watching: first the Now playing card (cover, song,
 * artist, previous / play-pause / next) while something plays, then the apps opened recently, newest first.
 * Up and Down leave the row through the callbacks; Left and Right move along it.
 */
final class CardsRow extends HorizontalScrollView {

    interface Callbacks {
        void up();
        void down();
        void open(String pkg);
    }

    private final Callbacks cb;
    private final AppAdapter adapter;
    private final LinearLayout row;
    private final float d;
    private final ColorStateList ink;

    CardsRow(Context ctx, AppAdapter adapter, Callbacks cb) {
        super(ctx);
        this.adapter = adapter;
        this.cb = cb;
        d = ctx.getResources().getDisplayMetrics().density;
        ink = ctx.getResources().getColorStateList(R.drawable.pill_text);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(dp(14), dp(8), dp(14), dp(8));
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Rebuilds the row. Returns whether there is anything to show. */
    boolean refresh(boolean showRecent, MediaWatcher.Info np) {
        row.removeAllViews();
        if (np != null) row.addView(nowPlayingCard(np), lp());
        if (showRecent) {
            List<RecentApps.Entry> recent = RecentApps.list(getContext());
            for (RecentApps.Entry e : recent) {
                AppAdapter.Entry app = adapter.entryOf(e.pkg);
                if (app == null) continue;
                row.addView(appCard(app, RecentApps.ago(e.at)), lp());
            }
        }
        return row.getChildCount() > 0;
    }

    View firstCard() {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = firstFocusable(row.getChildAt(i));
            if (v != null) return v;
        }
        return null;
    }

    private static View firstFocusable(View v) {
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    // ---- cards -----------------------------------------------------------------------------------

    private View appCard(final AppAdapter.Entry app, String ago) {
        LinearLayout card = card();
        card.setOnClickListener(v -> cb.open(app.pkg));
        ImageView art = new ImageView(getContext());
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable img = app.art != null ? app.art : app.icon;
        art.setImageDrawable(img != null && img.getConstantState() != null ? img.getConstantState().newDrawable() : img);
        round(art, 8);
        card.addView(art, new LinearLayout.LayoutParams(dp(80), dp(45)));
        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setDuplicateParentStateEnabled(true);
        TextView name = label(app.label, 15, Fonts.medium(getContext()));
        TextView when = label(ago, 12, Fonts.regular(getContext()));
        when.setAlpha(0.7f);
        text.addView(name);
        text.addView(when);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(10);
        card.addView(text, tp);
        return card;
    }

    private View nowPlayingCard(final MediaWatcher.Info np) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(6), dp(10), dp(6));
        card.setBackgroundResource(R.drawable.card_bg_static);
        ImageView cover = new ImageView(getContext());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (np.art != null) {
            cover.setImageBitmap(np.art);
        } else {
            cover.setImageResource(R.drawable.ic_music);
            cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            cover.setPadding(dp(10), dp(10), dp(10), dp(10));
            cover.setBackgroundColor(0x33FFFFFF);
        }
        round(cover, 8);
        card.addView(cover, new LinearLayout.LayoutParams(dp(45), dp(45)));
        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(np.title, 15, Fonts.medium(getContext()));
        t.setTextColor(0xFFFFFFFF);
        TextView a = label(np.artist.isEmpty() ? appName(np.pkg) : np.artist, 12, Fonts.regular(getContext()));
        a.setTextColor(0xB3FFFFFF);
        text.addView(t);
        text.addView(a);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(10);
        card.addView(text, tp);
        final MediaController c = np.controller;
        card.addView(control(R.drawable.ic_prev, "Previous", () -> {
            if (c != null) c.getTransportControls().skipToPrevious();
        }));
        card.addView(control(np.playing ? R.drawable.ic_pause : R.drawable.ic_play, np.playing ? "Pause" : "Play", () -> {
            if (c == null) return;
            if (np.playing) c.getTransportControls().pause();
            else c.getTransportControls().play();
        }));
        card.addView(control(R.drawable.ic_next, "Next", () -> {
            if (c != null) c.getTransportControls().skipToNext();
        }));
        return card;
    }

    private String appName(String pkg) {
        try {
            return String.valueOf(getContext().getPackageManager().getApplicationLabel(
                    getContext().getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private View control(int icon, String description, final Runnable action) {
        ImageView b = new ImageView(getContext());
        b.setImageResource(icon);
        b.setImageTintList(ink);
        b.setContentDescription(description);
        b.setBackgroundResource(R.drawable.pill_bg);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        b.setOnKeyListener(vertical());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.leftMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(6), dp(12), dp(6));
        card.setBackgroundResource(R.drawable.card_bg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnKeyListener(vertical());
        return card;
    }

    private OnKeyListener vertical() {
        return (v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                cb.up();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                cb.down();
                return true;
            }
            return false;
        };
    }

    private TextView label(String s, float sp, android.graphics.Typeface face) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(ink);
        t.setDuplicateParentStateEnabled(true);
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

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(58));
        p.rightMargin = dp(12);
        return p;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
