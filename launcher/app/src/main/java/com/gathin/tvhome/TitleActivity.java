package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A title page in the home's glass style: backdrop, poster, name, short description and one Play button for
 * each installed app that streams it. The phone remote's computer starts it over adb, passing the card as a
 * URL-safe Base64 JSON extra (see phone-remote/server.py). Play asks that computer to open the title, because
 * Netflix drops a link at "Who's watching?" and has to be restarted first; with no computer to answer, it
 * opens the link directly.
 */
public class TitleActivity extends Activity {

    private JSONObject card;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        try {
            byte[] raw = Base64.decode(getIntent().getStringExtra("card"), Base64.URL_SAFE);
            card = new JSONObject(new String(raw, "UTF-8"));
            build();
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();   // a second card replaces the first
    }

    private void build() throws Exception {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0E16);

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);   // dark on the text side, the picture shows through on the right
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2080C14, 0xC4080C14, 0x4D080C14}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0x33FFFFFF);
        round(poster, 16);
        // The screen is 960 x 540 dp: text on the left (72 to 632), the poster on the right (668 to 888).
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(220), dp(330), Gravity.END | Gravity.CENTER_VERTICAL);
        pp.rightMargin = dp(72);
        root.addView(poster, pp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        cp.leftMargin = dp(72);
        root.addView(col, cp);

        SpannableStringBuilder meta = new SpannableStringBuilder();
        if (!card.optString("imdb").isEmpty()) {   // "★ 8.4 IMDb (553K)", the score in gold
            String score = "★ " + card.optString("imdb");
            meta.append(score);
            meta.setSpan(new ForegroundColorSpan(0xFFF5C518), 0, score.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            meta.append(" IMDb");
            if (!card.optString("votes").isEmpty()) meta.append(" (").append(card.optString("votes")).append(")");
        }
        for (String k : new String[]{"year", "kind"}) {
            if (!card.optString(k).isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(card.optString(k));
        }
        if (card.optInt("runtime") > 0) meta.append("  ·  ").append(String.valueOf(card.optInt("runtime"))).append(" min");
        col.addView(text(meta, 18, Fonts.regular(this), 0xB3FFFFFF, 1));
        TextView title = text(card.getString("title"), 56, Fonts.semibold(this), 0xFFFFFFFF, 2);
        col.addView(title, margin(6, 0));
        col.addView(text(card.optString("desc"), 20, Fonts.regular(this), 0xE6FFFFFF, 5), margin(18, 0));

        col.addView(text("Play on", 16, Fonts.medium(this), 0x99FFFFFF, 1), margin(30, 2));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(buttons, margin(8, 0));
        TextView status = text("", 16, Fonts.regular(this), 0xB3FFFFFF, 1);
        col.addView(status, margin(18, 0));
        JSONArray offers = card.getJSONArray("offers");
        for (int i = 0; i < offers.length(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(12);
            buttons.addView(playButton(offers.getJSONObject(i), status), lp);
        }
        if (buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();

        setContentView(root);
        load(card.optString("backdrop"), backdrop);
        load(card.optString("poster"), poster);
    }

    private View playButton(final JSONObject offer, final TextView status) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(10), dp(8), dp(22), dp(8));
        btn.setBackgroundResource(R.drawable.card_bg);
        btn.setFocusable(true);
        btn.setClickable(true);
        ImageView icon = new ImageView(this);
        try {
            Drawable app = getPackageManager().getApplicationIcon(offer.getString("package"));
            icon.setImageDrawable(app);
        } catch (Exception ignored) {
            // no icon: the label alone is enough
        }
        round(icon, 8);
        btn.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView label = text(offer.optString("name"), 18, Fonts.medium(this), 0xFFFFFFFF, 1);
        label.setTextColor(getResources().getColorStateList(R.drawable.pill_text));
        label.setDuplicateParentStateEnabled(true);
        btn.addView(label, margin(0, 12));
        btn.setOnClickListener(v -> play(offer, status));
        return btn;
    }

    private void play(final JSONObject offer, TextView status) {
        status.setText("Opening " + offer.optString("name") + "…");
        new Thread(() -> {
            final boolean asked = askComputer(offer);
            runOnUiThread(() -> {
                if (!asked) openDirect(offer);
                finish();
            });
        }).start();
    }

    /** The phone remote's computer does the opening (restart, profile, link). False when it did not answer. */
    private boolean askComputer(JSONObject offer) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(card.getString("server") + "/api/play").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject().put("package", offer.getString("package")).put("id", offer.getString("id"));
            OutputStream out = c.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void openDirect(JSONObject offer) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(offer.getString("uri")))
                    .setPackage(offer.getString("package")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + offer.optString("name"), Toast.LENGTH_SHORT).show();
        }
    }

    private void load(final String url, final ImageView into) {
        if (url.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                final Bitmap bmp = BitmapFactory.decodeStream(c.getInputStream());
                if (bmp == null) return;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    into.setAlpha(0f);
                    into.setImageBitmap(bmp);
                    into.animate().alpha(1f).setDuration(300);
                });
            } catch (Exception ignored) {
                // no picture: the text and buttons still work
            }
        }).start();
    }

    private TextView text(CharSequence s, float sp, android.graphics.Typeface face, int color, int maxLines) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setMaxLines(maxLines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private LinearLayout.LayoutParams margin(int topDp, int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(topDp);
        p.leftMargin = dp(leftDp);
        return p;
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
}
