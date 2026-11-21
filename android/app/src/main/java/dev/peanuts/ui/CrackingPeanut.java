package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The app's peanut (PeanutArt, the one of About and the shader screen) cracking open once, for a short wait such as switching users
 * (dev.peanuts.users.ProfileButton): the seam runs down it, the halves swing open about the foot and the kernels show, then it rests open. Under reduced motion,
 * or with the system's animations off, it is drawn open at once.
 */
public final class CrackingPeanut extends View {
    private static final long SEAM_MS = 520, SWING_MS = 640;
    private final PeanutArt art = new PeanutArt();
    private ValueAnimator anim;
    private float crackT, swing, deg;

    public CrackingPeanut(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // clipPath of the halves, on every device (as CrackedPeanut's)
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!Motion.on() || !ValueAnimator.areAnimatorsEnabled()) { crackT = 1; swing = 1; deg = 24.5f; return; }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SEAM_MS + SWING_MS);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> {
            float ms = (float) v.getAnimatedValue() * (SEAM_MS + SWING_MS);
            crackT = Math.min(1f, ms / SEAM_MS);
            float t = Math.max(0f, (ms - SEAM_MS) / SWING_MS);
            swing = 1f - (1f - t) * (1f - t) * (1f - t);   // eases out: opens fast, settles calmly
            deg = 24.5f * swing;
            invalidate();
        });
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
        art.draw(c, crackT, swing, deg, 255);
        c.restore();
    }
}
