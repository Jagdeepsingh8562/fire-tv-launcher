package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Random;

/**
 * The home screen backdrop: a sky, a sun or moon, and five mountain ridges drawn as paths, hazier toward the
 * back. Four palettes follow the time of day. Ridges shift a little with focus (parallax) and sink as the app
 * grid scrolls up. Nothing animates while idle; paths are built once per size, so frames are cheap on 1 GB TVs.
 */
public class MountainView extends View {

    /** Dawn, day, dusk, night: same order as MainActivity.dayPart(). */
    private static final class Palette {
        final int skyTop, skyMid, skyHorizon, sun, sunGlow;
        final float sunX, sunY, sunR;
        final boolean moon;
        final int[] ridges;      // far to near
        final int snow;          // peak tint on the two far ridges

        Palette(int skyTop, int skyMid, int skyHorizon, int sun, int sunGlow, float sunX, float sunY, float sunR,
                boolean moon, int snow, int... ridges) {
            this.skyTop = skyTop;
            this.skyMid = skyMid;
            this.skyHorizon = skyHorizon;
            this.sun = sun;
            this.sunGlow = sunGlow;
            this.sunX = sunX;
            this.sunY = sunY;
            this.sunR = sunR;
            this.moon = moon;
            this.snow = snow;
            this.ridges = ridges;
        }
    }

    private static final Palette[] PALETTES = {
            // Dawn: violet night lifting into peach, low amber sun.
            new Palette(0xFF1E2747, 0xFF8A5A7A, 0xFFF3B07A, 0xFFFFD49A, 0x66FFB067, 0.24f, 0.40f, 0.040f, false,
                    0xFFE9C2B6, 0xFF8C7392, 0xFF6A5679, 0xFF4A3D5E, 0xFF2D2640, 0xFF161424),
            // Day: clear alpine blue, high pale sun, pine-blue ridges.
            new Palette(0xFF2F6FA6, 0xFF6FA9D2, 0xFFCFE6F0, 0xFFFFF8E6, 0x55FFF1C9, 0.60f, 0.17f, 0.032f, false,
                    0xFFF2F6F8, 0xFF8FB0C6, 0xFF5F8299, 0xFF3C5A6B, 0xFF233A45, 0xFF101C22),
            // Dusk: plum to ember, large orange sun on the far ridge.
            new Palette(0xFF1A1433, 0xFF6B2D5C, 0xFFF08A4B, 0xFFFFB25C, 0x77FF7A3D, 0.30f, 0.47f, 0.06f, false,
                    0xFFF0A27C, 0xFF7A3F62, 0xFF57304F, 0xFF3A223D, 0xFF22162A, 0xFF100A16),
            // Night: deep navy, stars, crescent moon, near-black ridges with moonlit snow.
            new Palette(0xFF04070F, 0xFF0B1426, 0xFF1A2944, 0xFFF4F1EA, 0x33C8D8FF, 0.60f, 0.12f, 0.028f, true,
                    0xFF6F84A6, 0xFF33425E, 0xFF243048, 0xFF172033, 0xFF0D1422, 0xFF060A12),
    };

    // Far to near: base line and peak height as fractions of the view height, parallax depth, roughness.
    private static final float[] BASE = {0.56f, 0.63f, 0.71f, 0.80f, 0.90f};
    private static final float[] AMP = {0.30f, 0.20f, 0.13f, 0.09f, 0.06f};
    private static final float[] DEPTH = {0.15f, 0.30f, 0.50f, 0.75f, 1.00f};
    private static final float[] ROUGH = {0.50f, 0.48f, 0.44f, 0.40f, 0.36f};
    private static final int STARS = 140;

    private final float density;
    private final Paint sky = new Paint();
    private final Paint ridge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] paths = new Path[BASE.length];
    private final Shader[] shaders = new Shader[BASE.length];
    private final Path moonPath = new Path();
    private float[] starXY;
    private int[] starAlpha;
    private Palette p = PALETTES[3];
    private int part = -1;

    private float parallax, parallaxTarget;   // -1..1, from the focused tile's x
    private float scroll;                     // px the grid has scrolled up
    private ValueAnimator parallaxAnim;

    public MountainView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        star.setColor(0xFFFFFFFF);
    }

    void setDayPart(int dayPart) {
        if (dayPart == part) return;
        part = dayPart;
        p = PALETTES[dayPart];
        if (getWidth() > 0) build(getWidth(), getHeight());
        invalidate();
    }

    /** Eases the ridges toward -1 (focus far left) .. 1 (far right). */
    void setParallax(float target) {
        target = Math.max(-1f, Math.min(1f, target));
        if (Math.abs(target - parallaxTarget) < 0.001f) return;
        parallaxTarget = target;
        if (parallaxAnim != null) parallaxAnim.cancel();
        parallaxAnim = ValueAnimator.ofFloat(parallax, target);
        parallaxAnim.setDuration(900);
        parallaxAnim.setInterpolator(new DecelerateInterpolator(2f));
        parallaxAnim.addUpdateListener(a -> {
            parallax = (float) a.getAnimatedValue();
            invalidate();
        });
        parallaxAnim.start();
    }

    /** The app grid's scroll offset: near ridges sink faster than far ones, so the scene gains depth. */
    void setScroll(float px) {
        if (px == scroll) return;
        scroll = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        build(w, h);
    }

    private void build(int w, int h) {
        sky.setShader(new LinearGradient(0, 0, 0, h * BASE[1],
                new int[]{p.skyTop, p.skyMid, p.skyHorizon}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        float margin = 40 * density;   // ridges are wider than the screen so parallax never shows an edge
        Random rnd = new Random(7);    // fixed seed: the same range every time
        for (int i = 0; i < BASE.length; i++) {
            float base = BASE[i] * h, amp = AMP[i] * h;
            float[] ys = ridgeLine(rnd, 64, ROUGH[i]);
            Path path = new Path();
            path.moveTo(-margin, h);
            float step = (w + 2 * margin) / (ys.length - 1);
            for (int k = 0; k < ys.length; k++) path.lineTo(-margin + k * step, base - ys[k] * amp);
            path.lineTo(w + margin, h);
            path.close();
            paths[i] = path;
            int color = p.ridges[i];
            // Far ridges: snowy, sun-lit tops fading into the ridge colour. Near ones: a light haze at the crest.
            int top = i < 2 ? p.snow : lighten(color, 0.10f);
            // Crest tint, ridge colour, then a little darker toward the foot: three stops, so no hard edge.
            shaders[i] = new LinearGradient(0, base - amp, 0, base + amp * 0.8f,
                    new int[]{top, color, lighten(color, -0.18f)}, new float[]{0f, i < 2 ? 0.45f : 0.35f, 1f},
                    Shader.TileMode.CLAMP);
        }
        float sx = p.sunX * w, sy = p.sunY * h, sr = p.sunR * w;
        glow.setShader(new RadialGradient(sx, sy, sr * 6f, new int[]{p.sunGlow, p.sunGlow & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        disc.setColor(p.sun);
        moonPath.reset();
        if (p.moon) {
            Path cut = new Path();
            moonPath.addCircle(sx, sy, sr, Path.Direction.CW);
            cut.addCircle(sx + sr * 0.45f, sy - sr * 0.25f, sr * 0.92f, Path.Direction.CW);
            moonPath.op(cut, Path.Op.DIFFERENCE);
        }
        Random s = new Random(11);
        starXY = new float[STARS * 2];
        starAlpha = new int[STARS];
        for (int i = 0; i < STARS; i++) {
            starXY[2 * i] = s.nextFloat() * w;
            starXY[2 * i + 1] = s.nextFloat() * s.nextFloat() * h * 0.6f;   // denser toward the top
            starAlpha[i] = 60 + s.nextInt(170);
        }
    }

    /** Midpoint displacement: a jagged line of values in 0..1, sharper with higher roughness. */
    private static float[] ridgeLine(Random rnd, int segments, float roughness) {
        float[] y = new float[segments + 1];
        y[0] = 0.3f + rnd.nextFloat() * 0.4f;
        y[segments] = 0.3f + rnd.nextFloat() * 0.4f;
        float spread = 0.9f;
        for (int size = segments; size > 1; size /= 2) {
            for (int i = size / 2; i < segments; i += size) {
                y[i] = (y[i - size / 2] + y[i + size / 2]) / 2f + (rnd.nextFloat() - 0.5f) * spread;
            }
            spread *= roughness;
        }
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : y) { min = Math.min(min, v); max = Math.max(max, v); }
        for (int i = 0; i < y.length; i++) y[i] = (y[i] - min) / Math.max(0.0001f, max - min);
        return y;
    }

    /** Mixes toward white (f > 0) or black (f < 0). */
    private static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int t = f > 0 ? 255 : 0;
        f = Math.abs(f);
        r += (int) ((t - r) * f);
        g += (int) ((t - g) * f);
        b += (int) ((t - b) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, sky);
        float shift = 18 * density;
        float sinkMax = h * 0.35f;
        float sink = Math.min(scroll, sinkMax);

        canvas.save();
        canvas.translate(-parallax * shift * 0.08f, sink * 0.05f);
        if (p.moon) {
            for (int i = 0; i < STARS; i++) {
                star.setAlpha(starAlpha[i]);
                canvas.drawCircle(starXY[2 * i], starXY[2 * i + 1], (starAlpha[i] > 200 ? 1.4f : 0.9f) * density, star);
            }
        }
        canvas.drawPaint(glow);
        if (p.moon) canvas.drawPath(moonPath, disc);
        else canvas.drawCircle(p.sunX * w, p.sunY * h, p.sunR * w, disc);
        canvas.restore();

        for (int i = 0; i < paths.length; i++) {
            canvas.save();
            canvas.translate(-parallax * shift * DEPTH[i], sink * DEPTH[i] * 0.45f);
            ridge.setShader(shaders[i]);
            canvas.drawPath(paths[i], ridge);
            canvas.restore();
        }
    }
}
