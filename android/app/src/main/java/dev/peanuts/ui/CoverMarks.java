package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.nxnative.android.R;

/**
 * A cover's state as a mark (Home's covers in every view, Round 5 of the Home design): one round mark top right, 24 dp on a cover of 150 dp, smaller on a
 * smaller one. The check for Ready, an arrow for an update found, the tool for Build needed, the folder for a file that is gone, the layers for a version
 * this app does not build, a square for a build that stopped, an hourglass and its place ("#2", on a pale banana) for a game that waits in the build queue. A build that runs grows
 * the mark to the left into a yellow pill (BuildPill): the heavy hammer at its left, the percent at its right, filling from the left. A game without a
 * profile gets the NO SUPPORT tape (Tape) across its greyed cover instead of a mark. The words stay where there is room (the bottom screen's card,
 * Compact, the line under a large cover, the cover's description for TalkBack): the marks are not read out.
 */
final class CoverMarks {
    private CoverMarks() {}

    /** A mark's diameter for a cover of this size, in dp: 24 at 150 dp and more, never under 18 */
    static int size(float coverDp) { return Math.round(Math.max(18f, Math.min(24f, coverDp * 0.16f))); }

    /** The layout of a mark, top right of the cover, a third of its size in from the corner (a cover frame's padding comes on top) */
    static FrameLayout.LayoutParams place(Context c, int markDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, PUi.dp(c, markDp), Gravity.TOP | Gravity.END);
        int m = PUi.dp(c, Math.max(6, Math.round(markDp / 3f)));
        lp.setMargins(m, m, m, m);
        return lp;
    }

    /** An icon in one colour (a Peanuts icon tinted whole), null when this build has none */
    static Drawable icon(Context c, String name, int color) {
        Drawable d = Icons.get(c, name, Icons.Ink.NORMAL);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    /** A round mark: a circle of fill (an outline of stroke when not 0) with the icon in its middle, a soft shadow so it reads on any art */
    static ImageView round(Context c, int markDp, int fill, int stroke, String iconName, int ink) {
        ImageView v = new ImageView(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fill);
        if (stroke != 0) bg.setStroke(Math.max(1, PUi.dp(c, 1.5f)), stroke);
        v.setBackground(bg);
        v.setImageDrawable(icon(c, iconName, ink));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(PUi.dp(c, markDp) * 0.2f);
        v.setPadding(pad, pad, pad, pad);
        v.setElevation(PUi.dp(c, 1.5f));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new FrameLayout.LayoutParams(PUi.dp(c, markDp), PUi.dp(c, markDp)));
        return v;
    }

    /**
     * The queued game's mark: the hourglass and its place in the build queue as "#2" (the same number as "Queued, 2 of 5"; a bare number beside the
     * hourglass read as minutes left), on a pale banana in both themes: the build's yellow, not yet its turn
     */
    static View queued(Context c, int markDp, int place) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int h = PUi.dp(c, markDp);
        int pale = BuildPill.blend(PUi.col(c, R.color.bn_banana), 0xFFFFFFFF, 0.7f);
        v.setBackground(PUi.round(pale, h / 2f, PUi.col(c, R.color.bn_banana_deep), Math.max(1, PUi.dp(c, 1))));
        v.setPadding(Math.round(h * 0.22f), 0, Math.round(h * (place > 0 ? 0.34f : 0.22f)), 0);
        v.setMinimumWidth(h);
        v.setElevation(PUi.dp(c, 1.5f));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView glass = new ImageView(c);
        glass.setImageDrawable(icon(c, "hourglass", PUi.col(c, R.color.bn_on_banana)));
        int g = Math.round(h * 0.62f);
        v.addView(glass, new LinearLayout.LayoutParams(g, g));
        if (place > 0) {
            TextView t = PUi.text(c, "#" + place, markDp * 0.5f, R.color.bn_on_banana, 800);
            t.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = Math.round(h * 0.08f);
            v.addView(t, lp);
        }
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, h));
        return v;
    }

    /** A cover without a profile: grey and darker under its tape */
    static void greyed(View tile) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0.15f);
        ColorMatrix dark = new ColorMatrix();
        dark.setScale(0.62f, 0.62f, 0.62f, 1f);
        m.postConcat(dark);
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(m));
        tile.setLayerType(View.LAYER_TYPE_HARDWARE, p);
    }

    // ---- The building pill ----

    /**
     * The mark of a build that runs: the round yellow mark grown to the left into a pill (its right edge stays where the mark was), the heavy hammer at the
     * left (Hammer), the percent at the right, a deeper yellow filling it from the left. Upstream's own Prepare, which says no percent, shows the hammer alone.
     * On a letter tile (edged) an edge in the letter's colour keeps the pill apart from the tile's own yellow.
     * grow() plays the growth when the build starts while the cover shows (220 ms), finish() the way back to the green check when it ends well (180 ms).
     */
    static final class BuildPill extends View {
        private final Hammer hammer;
        private final Drawable tool, check;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final int h, banana, deep, leaf, onBanana, edge;
        private int percent;   // -1: none to say (upstream's Prepare)
        private float grow = 1f, done;   // 0: the round mark, 1: the pill; done 1: the green check
        private ValueAnimator anim;

        BuildPill(Context c, int markDp, int percent, boolean edged) {
            super(c);
            h = PUi.dp(c, markDp);
            banana = PUi.col(c, R.color.bn_banana); deep = PUi.col(c, R.color.bn_banana_deep); leaf = PUi.col(c, R.color.bn_leaf); onBanana = PUi.col(c, R.color.bn_on_banana);
            edge = edged ? onBanana : 0;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1, PUi.dp(c, 1.5f)));   // as a round mark's outline (round)
            stroke.setColor(onBanana);
            hammer = new Hammer(onBanana);
            hammer.setCallback(this);
            hammer.still(!Motion.on());
            tool = icon(c, "build", onBanana);
            check = icon(c, "check", PUi.col(c, R.color.bn_on_leaf));
            text.setColor(onBanana);
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, 800, false));
            text.setTextSize(h * 0.46f);
            this.percent = percent;
            setElevation(PUi.dp(c, 1.5f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, h));
        }

        /** The percent shown (a build's progress), drawn again in place */
        void percent(int p) {
            p = Math.max(0, Math.min(100, p));
            if (p == percent) return;
            boolean wider = String.valueOf(p).length() != String.valueOf(Math.max(0, percent)).length();
            percent = p;
            if (wider) requestLayout();
            invalidate();
        }

        /** Grows from the round mark (a build that starts in front of the player); at once without motion */
        void grow() {
            if (!Motion.on()) return;
            run(0f, 1f, 220, Motion.OUT, x -> grow = x);
        }

        /** Shrinks back to the round mark, green with the check (the build ended well); after: once it is there */
        void finish(Runnable after) {
            if (!Motion.on()) { grow = 0; done = 1; hammer.stop(); requestLayout(); invalidate(); if (after != null) after.run(); return; }
            run(1f, 0f, 180, Motion.IN, x -> { grow = x; done = 1f - x; });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator x) {
                    hammer.stop();   // the check shows: no strike any more, and no frame drawn for it
                    if (after != null) after.run();
                }
            });
        }

        private void run(float from, float to, int ms, android.view.animation.Interpolator in, java.util.function.Consumer<Float> set) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(from, to);
            anim.setDuration(ms);
            anim.setInterpolator(in);
            anim.addUpdateListener(x -> { set.accept((float) x.getAnimatedValue()); requestLayout(); invalidate(); });
            set.accept(from);
            anim.start();
        }

        private float fullWidth() {
            float hs = h * 0.67f, padL = h * 0.17f, padR = h * 0.34f, gap = h * 0.12f;
            return percent < 0 ? padL + hs + padL : padL + hs + gap + text.measureText(percent + " %") + padR;
        }

        @Override protected void onMeasure(int w, int hh) {
            setMeasuredDimension(Math.round(h + (fullWidth() - h) * grow), h);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (Motion.on() && done == 0) hammer.start(); }
        @Override protected void onDetachedFromWindow() { hammer.stop(); if (anim != null) anim.cancel(); super.onDetachedFromWindow(); }
        @Override protected boolean verifyDrawable(Drawable who) { return who == hammer || super.verifyDrawable(who); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            r.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(r, h / 2f, h / 2f, Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            c.drawColor(blend(banana, leaf, done));
            if (percent > 0 && done == 0) { fill.setColor(deep); c.drawRect(0, 0, w * percent / 100f, h, fill); }
            float hs = h * 0.67f, padL = h * 0.17f;
            int a = Math.round(255 * grow);
            if (a > 0) {
                int top = Math.round((h - hs) / 2f), left = Math.round(padL);
                hammer.setBounds(left, top, left + Math.round(hs), top + Math.round(hs));
                hammer.setAlpha(a);
                hammer.draw(c);
                if (percent >= 0) {
                    text.setAlpha(a);
                    Paint.FontMetrics fm = text.getFontMetrics();
                    String s = percent + " %";
                    c.drawText(s, w - h * 0.34f - text.measureText(s), h / 2f - (fm.ascent + fm.descent) / 2f, text);
                }
            }
            Drawable mark = done > 0.5f ? check : tool;
            if (mark != null && grow < 1f) {
                int s = Math.round(h * 0.6f), x = w - h + (h - s) / 2, y = (h - s) / 2;
                mark.setBounds(x, y, x + s, y + s);
                mark.setAlpha(Math.round(255 * (1f - grow)));
                mark.draw(c);
            }
            c.restoreToCount(save);
            if (edge != 0) {   // inside the pill's own bounds, so nothing is cut at its sides
                float sw = stroke.getStrokeWidth() / 2f;
                r.set(sw, sw, w - sw, h - sw);
                c.drawRoundRect(r, h / 2f - sw, h / 2f - sw, stroke);
            }
        }

        private static int blend(int x, int y, float t) {
            if (t <= 0) return x;
            if (t >= 1) return y;
            int r = Math.round(((x >> 16) & 255) + (((y >> 16) & 255) - ((x >> 16) & 255)) * t);
            int g = Math.round(((x >> 8) & 255) + (((y >> 8) & 255) - ((x >> 8) & 255)) * t);
            int b = Math.round((x & 255) + ((y & 255) - (x & 255)) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    // ---- The NO SUPPORT tape ----

    /**
     * Across the cover of a game this app has no profile for: a strip of banana-yellow tape at -14 degrees with torn ends, NO SUPPORT once in its middle (so
     * no word is ever cut), small peanuts on both sides that may run off the edge. The letters grow with the cover (HomeViews.tapeText, 9 to 12 dp); under
     * 72 dp the tape has only peanuts. Drawn in the cover's rounded corners.
     */
    static final class Tape extends View {
        private static final String WORDS = "NO SUPPORT";
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG), letters = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable peanut;
        private final Path shape = new Path(), round = new Path();
        private final RectF r = new RectF();
        private final float radius;

        Tape(Context c, float radiusPx) {
            super(c);
            radius = radiusPx;
            band.setColor(PUi.col(c, R.color.bn_banana));
            band.setShadowLayer(PUi.dp(c, 2), 0, PUi.dp(c, 1), 0x55000000);
            letters.setColor(PUi.col(c, R.color.bn_on_banana));
            letters.setTypeface(Typeface.create(Typeface.SANS_SERIF, 800, false));
            letters.setLetterSpacing(0.12f);
            peanut = icon(c, "peanut", PUi.col(c, R.color.bn_on_banana));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float density = getResources().getDisplayMetrics().density, coverDp = Math.min(w, h) / density;
            r.set(0, 0, w, h);
            round.reset();
            round.addRoundRect(r, radius, radius, Path.Direction.CW);
            int save = c.save();
            c.clipPath(round);
            c.rotate(-14f, w / 2f, h / 2f);
            float len = (float) Math.hypot(w, h) * 0.98f, bh = Math.max(10 * density, Math.min(w, h) * 0.18f);
            float x0 = (w - len) / 2f, x1 = x0 + len, y0 = (h - bh) / 2f, y1 = y0 + bh;
            // the band, its two ends torn (a short zigzag)
            shape.reset();
            int teeth = 5;
            float tooth = bh * 0.12f;
            shape.moveTo(x0, y0);
            shape.lineTo(x1, y0);
            for (int i = 1; i <= teeth; i++) shape.lineTo(x1 + (i % 2 == 1 ? -tooth : 0), y0 + bh * i / teeth);
            shape.lineTo(x0, y1);
            for (int i = teeth - 1; i >= 0; i--) shape.lineTo(x0 + (i % 2 == 1 ? tooth : 0), y0 + bh * i / teeth);
            shape.close();
            c.drawPath(shape, band);
            // the words once in the middle, then peanuts outwards on both sides
            float textDp = HomeViews.tapeText(coverDp), half = 0, cy = h / 2f;
            if (textDp > 0) {
                letters.setTextSize(textDp * density);
                float tw = letters.measureText(WORDS);
                Paint.FontMetrics fm = letters.getFontMetrics();
                c.drawText(WORDS, (w - tw) / 2f, cy - (fm.ascent + fm.descent) / 2f, letters);
                half = tw / 2f + bh * 0.45f;
            }
            if (peanut != null) {
                float ps = bh * 0.62f, step = ps * 1.55f;
                for (float off = half == 0 ? 0 : half + ps / 2f; off < len / 2f; off += step) {
                    for (int side = (off == 0 ? 1 : -1); side <= 1; side += 2) {
                        float px = w / 2f + side * off;
                        if (px - ps / 2f < x0 + tooth || px + ps / 2f > x1 - tooth) continue;
                        peanut.setBounds(Math.round(px - ps / 2f), Math.round(cy - ps / 2f), Math.round(px + ps / 2f), Math.round(cy + ps / 2f));
                        peanut.draw(c);
                    }
                }
            }
            c.restoreToCount(save);
        }
    }
}
