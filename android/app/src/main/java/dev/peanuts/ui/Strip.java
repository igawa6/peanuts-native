package dev.peanuts.ui;

import dev.nxnative.android.R;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.ProgressBar;

/**
 * The app's one progress bar: the building cover's strip (Library), on the build card, the Prepare card (Ondevice), the second screen's build card
 * (BottomPanel), the build log's and the GPU driver page's bars too, in place of the platform's thin horizontal bar that Ui.progress / PUi.progress make
 * (PUi stays a verbatim copy of Ui; upstream's own screens, the app update and the game's shader prewarm, keep theirs). 6 dp high, rounded ends, banana deep on its track; the height is fixed
 * here (min and max height), so any layout gives the same bar. Indeterminate (upstream's Prepare, which reports no percent): the same 6 dp track, faintly
 * lit, with a soft light gliding through ({@link Sweep}), not the platform's thin animated line.
 */
public final class Strip {
    private Strip() {}

    /** On a card: the track is the card's stroke colour */
    public static ProgressBar card(Context c) { return of(c, PUi.col(c, R.color.bn_card_stroke), false); }

    public static ProgressBar of(Context c, int track, boolean indeterminate) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(indeterminate);
        GradientDrawable fill = PUi.round(PUi.col(c, R.color.bn_banana_deep), PUi.dp(c, 3), 0, 0);
        LayerDrawable ld = new LayerDrawable(new Drawable[] {PUi.round(track, PUi.dp(c, 3), 0, 0), new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        p.setProgressDrawable(ld);
        p.setIndeterminateDrawable(new Sweep(track, PUi.col(c, R.color.bn_banana_deep), PUi.dp(c, 6)));
        p.setMinHeight(PUi.dp(c, 6));
        p.setMaxHeight(PUi.dp(c, 6));
        return p;
    }

    /** The indeterminate bar: the whole track faintly lit, and a soft light, 45 % of its width, gliding through from off the left edge to off the right
     *  one (1.5 s a pass). It fills the whole width, so it never reads as a percent. With Android's animations off the light rests in the middle.
     *  ProgressBar starts and stops it, as it does any Animatable indeterminate drawable */
    static final class Sweep extends android.graphics.drawable.Drawable implements Animatable {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), lit = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int height, fillColor;
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private ValueAnimator anim;
        private android.graphics.LinearGradient gradient;
        private float gradientBand;
        private final android.graphics.Matrix shift = new android.graphics.Matrix();
        private float t = 0.5f;   // where the light is, 0 = off the left edge, 1 = off the right one

        Sweep(int trackColor, int fillColor, int heightPx) {
            track.setColor(trackColor);
            lit.setColor(alpha(fillColor, 0.3f));
            this.fillColor = fillColor;
            height = heightPx;
        }

        private static int alpha(int color, float a) { return (Math.round(Color.alpha(color) * a) << 24) | (color & 0x00FFFFFF); }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float h = Math.min(height, b.height()), top = b.centerY() - h / 2f, rad = h / 2f, w = b.width(), band = w * 0.45f, x0 = b.left - band + t * (w + band);
            r.set(b.left, top, b.right, top + h);
            c.drawRoundRect(r, rad, rad, track);
            c.drawRoundRect(r, rad, rad, lit);
            if (gradient == null || gradientBand != band) {   // one shader for a band's width, moved with a matrix: nothing allocated per frame
                gradient = new android.graphics.LinearGradient(0, 0, band, 0, new int[] {alpha(fillColor, 0f), fillColor, alpha(fillColor, 0f)}, null,
                        android.graphics.Shader.TileMode.CLAMP);
                gradientBand = band;
                light.setShader(gradient);
            }
            shift.setTranslate(x0, 0);
            gradient.setLocalMatrix(shift);
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(r, light);
            c.restore();
        }

        @Override public int getIntrinsicHeight() { return height; }
        @Override public void setAlpha(int alpha) { track.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { track.setColorFilter(cf); lit.setColorFilter(cf); light.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void start() {
            if (isRunning()) return;
            if (!ValueAnimator.areAnimatorsEnabled()) { t = 0.5f; invalidateSelf(); return; }
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1500);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidateSelf(); });
            anim.start();
        }

        @Override public void stop() { if (anim != null) { anim.cancel(); anim = null; } }
        @Override public boolean isRunning() { return anim != null && anim.isRunning(); }
    }
}
