package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Spaces the dock row. The dock's N icons share one grid row (N equal cells), so each icon is shifted toward the
 * centre to keep the same spacing a full dock has, keeping the group centred. Behind the row it draws the
 * ambient-glass band: a frosted, translucent capsule with a hairline rim, wider than the grid by BAND_OUT.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final AppAdapter adapter;
    private final int columns;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF band = new RectF();
    private float bandHeight = -1;
    private static final float BAND_OUT = 16, BAND_PAD = 16, BAND_RADIUS = 26;   // dp

    DockDecoration(AppAdapter adapter, int columns, float density) {
        this.adapter = adapter;
        this.columns = columns;
        this.density = density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x4DFFFFFF);
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int n = adapter.dockCount();
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View item = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(item);
            if (pos < 0 || pos >= n) continue;
            View card = item.findViewById(R.id.card);
            top = Math.min(top, item.getTop() + card.getTop());
            bottom = Math.max(bottom, item.getTop() + card.getBottom());
        }
        if (top == Float.MAX_VALUE) return;   // dock scrolled out of view
        float pad = BAND_PAD * density, out = BAND_OUT * density, r = BAND_RADIUS * density;
        band.set(parent.getPaddingLeft() - out, top - pad, parent.getWidth() - parent.getPaddingRight() + out, bottom + pad);
        if (band.height() != bandHeight) {
            bandHeight = band.height();
            fill.setShader(new LinearGradient(0, 0, 0, bandHeight, 0x33FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        }
        c.save();
        c.translate(0, band.top);
        band.offset(0, -band.top);
        c.drawRoundRect(band, r, r, fill);
        c.drawRoundRect(band, r, r, rim);
        c.restore();
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        int n = adapter.dockCount();
        if (pos < 0 || pos >= n) return;
        outRect.top = (int) (6 * density);
        outRect.bottom = (int) (12 * density);
        float inner = parent.getMeasuredWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        float actual = (pos + 0.5f) * inner / n;
        float wanted = inner / 2f + (pos - (n - 1) / 2f) * inner / columns;
        int shift = Math.round(2 * (wanted - actual));   // the item's content is centred: offsets move it by half
        if (shift > 0) outRect.left = shift;
        else outRect.right = -shift;
    }
}
