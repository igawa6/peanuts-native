package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * One background across both screens while a Peanuts page is on the second one (BottomScreen, GameScreen).
 * Both displays sit in one field, the second centred right below the first. The phase is the clock's, the same in
 * every process, so the launcher's page and the game's line up. The drift itself is BananaBackgroundView's, unchanged.
 */
public final class BackgroundFlow {
    private static final float GAP_DP = 0;   // none: a peanut leaving the top screen comes in below where it left

    private static Display top, bottom;
    private static double phase;              // seconds
    private static final int[] AT = new int[2];
    private static int topId = -1, bottomId = -1;
    private static float topW, topH, bottomW;  // dp

    private BackgroundFlow() {}

    /** A Peanuts page on bottom, under top's */
    static void field(Display t, Display b) { top = t; bottom = b; stale(); }
    static void clear() { top = null; bottom = null; }
    static void stale() { topId = -1; bottomId = -1; }

    /**
     * BananaBackgroundView.onDraw and popAt (PEANUTS hooks): the grid's drift in px (BananaGrid's wx, wy, not wrapped) that puts this view's grid into the
     * field, or null to draw as before. Arguments are upstream's own grid tilt and drift.
     */
    public static double[] drift(View v, float gridDeg, float speedX, float speedY) {
        Display t = top, b = bottom, d = v.getDisplay();
        if (t == null || b == null || d == null || !t.isValid() || !b.isValid()) return null;
        boolean onTop = d.getDisplayId() == t.getDisplayId();
        if (!onTop && d.getDisplayId() != b.getDisplayId()) return null;
        if (t.getDisplayId() != topId || b.getDisplayId() != bottomId) measure(t, b);
        if (ValueAnimator.areAnimatorsEnabled()) phase = AnimationUtils.currentAnimationTimeMillis() / 1000.0;   // still while animations are off
        float density = v.getResources().getDisplayMetrics().density;
        v.getLocationOnScreen(AT);
        // this view's centre in the field (dp)
        float cx = (AT[0] + v.getWidth() / 2f) / density + (onTop ? 0 : (topW - bottomW) / 2f);
        float cy = (AT[1] + v.getHeight() / 2f) / density + (onTop ? 0 : topH + GAP_DP);
        // the grid turns about the top screen's centre: shift it by the distance, in the grid's axes
        double dx = topW / 2f - cx, dy = topH / 2f - cy, a = Math.toRadians(gridDeg), cos = Math.cos(a), sin = Math.sin(a);
        return new double[] {(dx * cos + dy * sin + phase * speedX) * density, (-dx * sin + dy * cos + phase * speedY) * density};
    }

    @SuppressWarnings("deprecation")   // getRealMetrics
    private static void measure(Display t, Display b) {
        DisplayMetrics m = new DisplayMetrics();
        t.getRealMetrics(m);
        topW = m.widthPixels / m.density; topH = m.heightPixels / m.density;
        b.getRealMetrics(m);
        bottomW = m.widthPixels / m.density;
        topId = t.getDisplayId(); bottomId = b.getDisplayId();
    }
}
