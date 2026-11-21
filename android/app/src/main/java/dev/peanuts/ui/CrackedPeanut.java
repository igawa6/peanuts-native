package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The cracked peanut of About and of the second display's About page: the app's own peanut (PeanutArt, PrepareCover's) open, its kernels showing. The
 * halves open and close a little, slowly, once every six seconds (each half between 22 and 27 degrees about the foot); no rocking. Still under reduced
 * motion and while the system's animations are off.
 */
final class CrackedPeanut extends View {
    private static final long BREATH_MS = 6000;
    private final PeanutArt art = new PeanutArt();
    private ValueAnimator anim;
    private float deg = 24.5f;

    CrackedPeanut(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // clipPath of the halves, on every device (as PrepareCover's)
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!Motion.on() || !ValueAnimator.areAnimatorsEnabled()) return;
        anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        anim.setDuration(BREATH_MS);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { deg = 24.5f - 2.5f * (float) Math.cos((float) v.getAnimatedValue()); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 108f;
        c.save();
        c.translate(getWidth() / 2f - 54 * s, getHeight() / 2f - 56 * s);
        c.scale(s, s);
        art.draw(c, 1f, 1f, deg, 255);
        c.restore();
    }
}
