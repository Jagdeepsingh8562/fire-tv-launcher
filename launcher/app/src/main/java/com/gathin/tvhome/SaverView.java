package com.gathin.tvhome;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The Manhattan photos full screen, each slowly zooming in and crossfading, with a small clock in the corner. */
public class SaverView extends FrameLayout {

    private static final int[] PHOTOS = {
            R.drawable.saver_01, R.drawable.saver_02, R.drawable.saver_03, R.drawable.saver_04,
            R.drawable.saver_05, R.drawable.saver_06, R.drawable.saver_07, R.drawable.saver_08,
            R.drawable.saver_09, R.drawable.saver_10, R.drawable.saver_11, R.drawable.saver_12};
    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 2_500;
    private static final float ZOOM = 1.08f;   // reached over one slide, then the next photo takes over

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> order = new ArrayList<>();
    private ImageView front, back;
    private TextClock clock;
    private int next;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    public SaverView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        front = photoView();
        back = photoView();
        addView(back);
        addView(front);
        clock = clock();
        addView(clock);
        for (int p : PHOTOS) order.add(p);
        Collections.shuffle(order);
        front.setAlpha(0f);
        back.setAlpha(0f);
    }

    public void start() { handler.removeCallbacks(advance); handler.post(advance); }

    public void stop() { handler.removeCallbacks(advance); }

    /** Fade the next photo in over the current one, and start its slow zoom. */
    private void showNext() {
        ImageView incoming = back, outgoing = front;
        incoming.setImageResource(order.get(next));
        next = (next + 1) % order.size();
        incoming.animate().cancel();
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        incoming.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(SLIDE_MS + FADE_MS).start();
        outgoing.animate().alpha(0f).setStartDelay(FADE_MS).setDuration(1).start();
        front = incoming;
        back = outgoing;
        clock.bringToFront();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private TextClock clock() {
        TextClock c = new TextClock(getContext());
        c.setFormat12Hour("h:mm");
        c.setFormat24Hour("HH:mm");
        c.setTextColor(0xE6FFFFFF);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            c.setTypeface(getResources().getFont(R.font.open_sans));
            c.setFontVariationSettings("'wght' 300");
        }
        c.setShadowLayer(12f, 0f, 2f, 0x99000000);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (48 * dp), (int) (36 * dp));
        c.setLayoutParams(lp);
        return c;
    }
}
