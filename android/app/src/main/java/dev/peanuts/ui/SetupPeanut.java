package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import dev.peanuts.setup.SetupSteps;

/**
 * The first-run setup's peanut (PeanutArt, the one of About): one view for the whole setup (SetupPage keeps it), cracking a little further with each step
 * done (SetupSteps.crack), the seam running on from where it stands ({@link #to}). At the end the seam runs through, the shell springs open, its two halves
 * fall away, and a moment later the two kernels fall after them. The end runs on a clock that started with it ({@link #to}), so a peanut made again
 * meanwhile (a new window, a turned screen) goes on from where the last one was; SetupPage times the wait after it with the same clock ({@link #endLeft}). A
 * step done is felt (a click), the opening too. Under reduced motion, or with the system's animations off, it is drawn as it stands. The view is larger than
 * its round tile ({@link #ROOM}): what falls leaves the tile and fades below it instead of being cut at its edge.
 */
final class SetupPeanut extends View {
    /** The view's side over the tile's: room for the halves and the kernels on their way down */
    static final float ROOM = 1.5f;
    private static final long DELAY_MS = 220, SEAM_MS = 560, SWING_MS = 640, REST_MS = 60, SHELL_FALL_MS = 620, KERNELS_LATER_MS = 260,
            FALL_MS = 620, TOP_LATER_MS = 140, STILL_END_MS = 900;
    private static float shownCrack;      // how far the seam has run: a peanut made again starts there
    private static long endStart;         // when the end began (uptime), 0 before it
    private static float endFrom;         // how far the seam had run then
    private final PeanutArt art = new PeanutArt();
    private float target;
    private boolean open;
    private float crackT, swing, deg, shellDrop, dropTop, dropBottom;
    private int disc;   // the tile's size in pixels: the peanut fills it (0: the view's own size over ROOM)
    private ValueAnimator anim;

    SetupPeanut(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // clipPath of the halves, on every device (as About's)
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        crackT = target = shownCrack;
    }

    /** The setup starts again (a fresh install, the app's data cleared, the setup done): the peanut whole */
    static void reset() { shownCrack = 0; endStart = 0; endFrom = 0; }

    private static boolean moves() { return Motion.on() && ValueAnimator.areAnimatorsEnabled(); }

    /** The end begins now, from a seam this far along, unless it began already: its clock */
    private static void end(float from) {
        if (endStart == 0) { endStart = SystemClock.uptimeMillis(); endFrom = from; }
    }

    /** How long the end still runs (the kernels fallen); 0 before it began */
    static long endLeft() { return endStart == 0 ? 0 : Math.max(0, endStart + endMs() - SystemClock.uptimeMillis()); }

    /** How long the end runs from its start: the seam's rest, the opening, a rest, the halves' fall and the kernels' after it; a short pause without motion */
    static long endMs() {
        return moves() ? DELAY_MS + (long) (SEAM_MS * (1 - endFrom)) + SWING_MS + REST_MS + KERNELS_LATER_MS + TOP_LATER_MS + FALL_MS : STILL_END_MS;
    }

    /** The tile it stands on is this large now (pixels): the peanut drawn to fill it, the view's size unchanged */
    void disc(int px) {
        if (px == disc) return;
        disc = px;
        invalidate();
    }

    /** This screen's peanut: crack how far the seam runs (0 to 1), open the end. The seam runs on from where it stands; nothing moves when nothing changed */
    void to(float crack, boolean open) {
        if (open) {
            if (this.open) return;
            this.open = true;
            end(crackT);   // the clock from the seam as it stands now, attached or not yet
            shownCrack = 1;
            if (isAttachedToWindow()) playEnd();
            return;
        }
        this.open = false;
        shownCrack = crack;   // what a peanut made again starts from (the welcome's reset may have cleared it meanwhile)
        if (crack == target) return;
        target = crack;
        if (isAttachedToWindow()) seam();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (open) playEnd();   // Home built its box again during the end: on from the clock
        else if (crackT != target) seam();
    }

    /** The seam runs on to this step's length; back a step, a step left for later or no motion: as it stands */
    private void seam() {
        stop();
        shownCrack = target;
        if (!moves() || target <= crackT) { crackT = target; invalidate(); return; }
        anim = ValueAnimator.ofFloat(crackT, target);
        anim.setDuration((long) (SEAM_MS * Math.max(0.35f, (target - crackT) * SetupSteps.COUNTED.size())));   // a step's part of the seam in SEAM_MS
        anim.setStartDelay(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> { crackT = (float) v.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { if (isAttachedToWindow()) Haptics.play(SetupPeanut.this, Haptics.Word.CLICK); }
        });
        anim.start();
    }

    /** The end, from where its clock stands: the seam runs through, the halves swing open about the foot (CrackingPeanut's) and fall, the kernels after them */
    private void playEnd() {
        stop();
        end(crackT);
        shownCrack = 1;
        long total = endMs();
        long gone = SystemClock.uptimeMillis() - endStart;
        if (!moves() || gone >= total) { at(total); return; }
        anim = ValueAnimator.ofFloat(gone, total);
        anim.setDuration(total - gone);
        anim.setInterpolator(new LinearInterpolator());
        final boolean[] felt = {gone > DELAY_MS + SEAM_MS * (1 - endFrom)};
        anim.addUpdateListener(v -> {
            at((float) v.getAnimatedValue());
            if (swing > 0 && !felt[0]) { felt[0] = true; Haptics.play(this, Haptics.Word.DONE); }
        });
        anim.start();
    }

    /** The end's picture at ms after its start */
    private void at(float ms) {
        if (!moves()) { crackT = 1; swing = 1; deg = 24.5f; shellDrop = dropTop = dropBottom = 0; invalidate(); return; }   // as it stands: open, the kernels in it
        float seam = SEAM_MS * (1 - endFrom);
        float t0 = ms - DELAY_MS;
        crackT = seam <= 0 ? 1 : Math.min(1f, Math.max(endFrom, endFrom + (1 - endFrom) * t0 / seam));
        float t = Math.min(1f, Math.max(0f, (t0 - seam) / SWING_MS));
        swing = 1f - (1f - t) * (1f - t) * (1f - t);
        deg = 24.5f * swing;
        float shells = t0 - seam - SWING_MS - REST_MS;   // the halves first
        shellDrop = Math.min(1f, Math.max(0f, shells / SHELL_FALL_MS));
        float fall = shells - KERNELS_LATER_MS;   // the kernels a moment after them, the lower one first
        dropBottom = Math.min(1f, Math.max(0f, fall / FALL_MS));
        dropTop = Math.min(1f, Math.max(0f, (fall - TOP_LATER_MS) / FALL_MS));
        invalidate();
    }

    private void stop() { if (anim != null) { anim.removeAllListeners(); anim.removeAllUpdateListeners(); anim.cancel(); anim = null; } }

    @Override protected void onDetachedFromWindow() {
        stop();   // where it stands is kept: attached again (Home's box built again), it goes on from there
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float s = (disc > 0 ? disc : Math.min(getWidth(), getHeight()) / ROOM) / 108f;
        c.save();
        c.translate(getWidth() / 2f - 54 * s, getHeight() / 2f - 56 * s);
        c.scale(s, s);
        art.draw(c, crackT, swing, deg, 255, shellDrop, dropTop, dropBottom);
        c.restore();
    }
}
