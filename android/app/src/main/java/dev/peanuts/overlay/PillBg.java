package dev.peanuts.overlay;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * PEANUTS: the overlay pill's background (Hud.margins sets it on the stats block's view): a round-ended shape in the Peanuts palette, kept quiet so the game
 * stays first. A very dark peanut brown, a little lighter at the top (a sheen); a thin rim in the shell's colour (#D9A066, PeanutArt) on the inside with a soft glow
 * that fades inwards; a light line along the top edge. The rim and glow scale with the block's height, so every Size keeps the look.
 */
final class PillBg extends Drawable {
    /** The shell (PeanutArt's #D9A066) at the rim's, the glow's steps' and the top line's alphas */
    private static final int RIM = 0x4DD9A066, TOP = 0x4DFFD6A0;
    private static final int[] GLOW = {0x29D9A066, 0x17D9A066, 0x0AD9A066};
    /** The fill: dark peanut brown at about 72 %, from a lighter top to a darker body */
    private static final int FILL_TOP = 0xB8402C18, FILL_BODY = 0xB8140D07;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    PillBg() { line.setStyle(Paint.Style.STROKE); }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float h = b.height(), r = h / 2f, w = Math.max(1f, h * 0.035f);
        RectF o = new RectF(b);
        fill.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.58f, FILL_TOP, FILL_BODY, Shader.TileMode.CLAMP));
        fill.setAlpha(alpha);
        c.drawRoundRect(o, r, r, fill);
        // the glow: steps inside the rim, each fainter and further in
        line.setStrokeWidth(w * 1.6f);
        for (int i = 0; i < GLOW.length; i++) {
            float in = w * (1.5f + i * 1.6f);
            RectF g = new RectF(o.left + in, o.top + in, o.right - in, o.bottom - in);
            stroke(c, g, r - in, GLOW[i]);
        }
        // the rim
        line.setStrokeWidth(w);
        RectF rim = new RectF(o.left + w / 2f, o.top + w / 2f, o.right - w / 2f, o.bottom - w / 2f);
        stroke(c, rim, r - w / 2f, RIM);
        // the sheen's line: the rim again in a light tone, on the top third only
        c.save();
        c.clipRect(o.left, o.top, o.right, o.top + h * 0.34f);
        RectF top = new RectF(rim.left, rim.top + w, rim.right, rim.bottom + w);
        stroke(c, top, r - w / 2f, TOP);
        c.restore();
    }

    private void stroke(Canvas c, RectF rect, float radius, int color) {
        line.setColor(color);
        line.setAlpha(Math.round(((color >>> 24) & 255) * alpha / 255f));
        c.drawRoundRect(rect, Math.max(0f, radius), Math.max(0f, radius), line);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter f) { fill.setColorFilter(f); line.setColorFilter(f); invalidateSelf(); }
    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
