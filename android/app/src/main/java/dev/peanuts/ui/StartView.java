package dev.peanuts.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import dev.nxnative.android.R;

/**
 * A game on its way: its cover in a breathing ring, name, step and a gliding light.
 * Used by the launcher (BottomPanel) and the game's process (GameScreen) with the same layout and a clock-driven animation,
 * so the hand-over between the two windows does not jump.
 */
public final class StartView extends FrameLayout implements Choreographer.FrameCallback {
    private static final PathInterpolator EASE = new PathInterpolator(0.45f, 0f, 0.55f, 1f);
    private final TextView tile, name, status;
    private final View ring;
    private final LinearLayout info;
    private final Sweep sweep;
    private boolean animating, quiet, posted;

    public StartView(Context c) {
        super(c);
        Configuration cf = c.getResources().getConfiguration();
        int w = cf.screenWidthDp, h = cf.screenHeightDp;
        boolean wide = w >= 600 && w >= h * 1.25f, tall = h >= 340;
        int pad = PUi.dp(c, wide ? 24 : 16);
        setPadding(pad, pad, pad, pad + (tall ? PUi.dp(c, 56) : 0));   // as BottomPanel's pages
        setClipChildren(false);
        setClipToPadding(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        int size = wide ? clamp(Math.round(h * 0.4f), 140, 300) : clamp(Math.round(Math.min(w, h) * 0.36f), 96, 200);
        int gap = PUi.dp(c, 7);
        FrameLayout art = new FrameLayout(c);
        art.setClipChildren(false);
        ring = new View(c);
        ring.setBackground(PUi.round(Color.TRANSPARENT, PUi.dp(c, 16) + gap, PUi.col(c, R.color.bn_banana), PUi.dp(c, 2)));
        ring.setAlpha(0f);
        art.addView(ring, new FrameLayout.LayoutParams(PUi.dp(c, size) + 2 * gap, PUi.dp(c, size) + 2 * gap, Gravity.CENTER));
        tile = PUi.tile(c, "?", size);
        art.addView(tile, new FrameLayout.LayoutParams(PUi.dp(c, size), PUi.dp(c, size), Gravity.CENTER));
        col.addView(art, PUi.wrap());
        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        name = PUi.text(c, "", wide ? 26 : 22, R.color.bn_text, 800);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, PUi.wrap());
        status = PUi.text(c, "", 14, R.color.bn_text_2, 600);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        PUi.add(info, status, 6, PUi.wrap());
        sweep = new Sweep(c);
        PUi.add(info, sweep, 14, new LinearLayout.LayoutParams(PUi.dp(c, 128), PUi.dp(c, 4)));
        PUi.add(col, info, 20, PUi.wrap());
        addView(col, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    TextView tile() { return tile; }
    View info() { return info; }

    public void bind(String gameName, Bitmap icon) {
        name.setText(gameName);
        String n = gameName == null ? "" : gameName.trim();
        tile.setText(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
        PUi.tileIcon(tile, null);
        if (icon != null) PUi.tileIcon(tile, icon);
    }

    public void icon(Bitmap b) { if (b != null) Motion.cover(tile, b); }

    public void status(CharSequence s) { status.setText(s); }

    /** Now playing: no light, no ring */
    public void quiet(boolean on) {
        quiet = on;
        sweep.setVisibility(on ? View.GONE : View.VISIBLE);
        ring.setAlpha(0f);
        setAnimating(animating);
    }

    /** Ring and light follow the clock; still under reduced motion */
    public void setAnimating(boolean on) {
        animating = on;
        boolean run = on && !quiet && Motion.on() && isAttachedToWindow();
        if (run && !posted) { posted = true; Choreographer.getInstance().postFrameCallback(this); }
        else if (!run && posted) { posted = false; Choreographer.getInstance().removeFrameCallback(this); }
        if (!run) { ring.setAlpha(on && !quiet ? 0.6f : 0f); sweep.at(0.5f); }
    }

    @Override public void doFrame(long nowNs) {
        posted = false;
        if (!animating || quiet) return;
        long now = SystemClock.uptimeMillis();   // the same in every process
        ring.setAlpha(0.3f + 0.55f * EASE.getInterpolation(pingPong(now, 1300)));
        sweep.at(EASE.getInterpolation(pingPong(now, 1100)));
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); setAnimating(animating); }
    @Override protected void onDetachedFromWindow() { if (posted) { posted = false; Choreographer.getInstance().removeFrameCallback(this); } super.onDetachedFromWindow(); }

    private static float pingPong(long now, long period) {
        float p = (now % (2 * period)) / (float) period;
        return p <= 1f ? p : 2f - p;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Slim track, a light at t (0..1) */
    private static final class Sweep extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float t = 0.5f;

        Sweep(Context c) {
            super(c);
            track.setColor(PUi.col(c, R.color.bn_tint));
            light.setColor(PUi.col(c, R.color.bn_banana));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void at(float v) { if (v != t) { t = v; invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), lw = w * 0.32f, x = t * (w - lw);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, track);
            r.set(x, 0, x + lw, h);
            c.drawRoundRect(r, h / 2, h / 2, light);
        }
    }
}
