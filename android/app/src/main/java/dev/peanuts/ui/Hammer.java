package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The building cover's hammer (a heavy one), drawn on a 24 x 24 grid in the Peanuts icon strokes (1.75): an anvil, a handle and a head that flares to its
 * face. It rests on the anvil for almost a second, is lifted slowly, holds at the top, falls hard without bouncing back, anvil and hammer give a hair at the
 * blow, and a few light sparks fly from the face's corners: 1.9 s a strike. Without motion (Motion.on is false) it stands still at the blow, the sparks in
 * the air. Only the drawable's own canvas moves: no view is measured or laid out again.
 */
final class Hammer extends Drawable implements Animatable {
    private static final long STRIKE_MS = 1900;
    private static final float PIVOT_X = 3.5f, PIVOT_Y = 12f;
    /** The sparks: where each starts (the face's lower corners at the blow), the way it flies (unit), how far, and when it has faded (part of a strike) */
    private static final float[][] SPARKS = {
            {18.5f, 15.9f, 0.62f, -0.78f, 4.6f, 0.90f}, {19.0f, 16.6f, 0.96f, -0.28f, 4.4f, 0.88f},
            {11.3f, 15.9f, -0.55f, -0.83f, 4.7f, 0.89f}, {10.8f, 16.6f, -0.95f, -0.30f, 4.2f, 0.87f}};
    private static final Interpolator LIFT = new PathInterpolator(0.45f, 0f, 0.55f, 1f), HOLD = new PathInterpolator(0f, 0f, 0.58f, 1f),
            FALL = new PathInterpolator(0.75f, 0f, 1f, 0.45f), SETTLE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path anvil = new Path(), handle = new Path(), head = new Path();
    private ValueAnimator clock;
    private float phase;   // where in a strike, 0..1
    private boolean still;

    Hammer(int color) {
        for (Paint p : new Paint[] {ink, spark}) { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(color); }
        ink.setStrokeWidth(1.75f);
        spark.setStrokeWidth(1.35f);
        anvil.moveTo(11f, 17.25f); anvil.lineTo(21.5f, 17.25f); anvil.lineTo(20f, 20.75f); anvil.lineTo(12.5f, 20.75f); anvil.close();
        handle.moveTo(3.5f, 12f); handle.lineTo(12.3f, 12f);
        head.moveTo(13f, 8.4f); head.lineTo(16.6f, 8.4f); head.lineTo(17.7f, 16.4f); head.lineTo(11.9f, 16.4f); head.close();
    }

    /** The hammer still, at the blow with its sparks in the air (Motion off), or striking again */
    void still(boolean on) {
        still = on;
        if (on) stop();
        invalidateSelf();
    }

    /** The head's turn about the handle's end at a point of the strike, in degrees (negative: lifted) */
    static float angle(float t) {
        if (t < 0.24f) return 0;
        if (t < 0.58f) return -36f * LIFT.getInterpolation((t - 0.24f) / 0.34f);
        if (t < 0.66f) return -36f - 3f * HOLD.getInterpolation((t - 0.58f) / 0.08f);
        if (t < 0.75f) return -39f + 39f * FALL.getInterpolation((t - 0.66f) / 0.09f);
        return 0;
    }

    /** How far anvil and hammer give at the blow, in grid units */
    static float give(float t) {
        if (t < 0.745f || t >= 0.82f) return 0;
        if (t < 0.76f) return 0.7f * (t - 0.745f) / 0.015f;
        return 0.7f * (1f - SETTLE.getInterpolation((t - 0.76f) / 0.06f));
    }

    @Override public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        float t = still ? 0.80f : phase;
        int save = c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(w / 24f, h / 24f);
        c.translate(0, still ? 0 : give(t));
        c.drawPath(anvil, ink);
        int turn = c.save();
        c.rotate(still ? 0 : angle(t), PIVOT_X, PIVOT_Y);
        c.drawPath(handle, ink);
        c.drawPath(head, ink);
        c.restoreToCount(turn);
        // the sparks: out of the face's corners from the blow, smaller and fainter as they fly
        if (t >= 0.749f) {
            for (float[] s : SPARKS) {
                if (t >= s[5]) continue;
                float k = Math.max(0f, (t - 0.755f) / (s[5] - 0.755f));
                float alpha = t < 0.755f ? (t - 0.749f) / 0.006f : 1f - k;
                float off = still ? 1.6f : k * s[4], len = 2f * (1f - 0.5f * k);
                float x0 = s[0] + s[2] * off, y0 = s[1] + s[3] * off;
                spark.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha)) * ink.getAlpha() / 255f));
                c.drawLine(x0, y0, x0 + s[2] * len, y0 + s[3] * len, spark);
            }
        }
        c.restoreToCount(save);
    }

    // ---- Animatable: one clock per hammer, started while its view is on screen ----

    @Override public void start() {
        if (still || (clock != null && clock.isRunning())) return;
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(STRIKE_MS);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(x -> { phase = (float) x.getAnimatedValue(); invalidateSelf(); });
        clock.start();
    }

    @Override public void stop() {
        if (clock != null) { clock.cancel(); clock = null; }
        phase = 0;
    }

    @Override public boolean isRunning() { return clock != null && clock.isRunning(); }

    @Override public void setAlpha(int a) {
        if (a == ink.getAlpha()) return;   // the pill sets it in every draw: drawing again only for a change, or it would draw every frame
        ink.setAlpha(a);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter f) { ink.setColorFilter(f); spark.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }
}
