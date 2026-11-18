package dev.nxnative.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * The main screen's background: the icon's banana (res/drawable/ic_banana.xml) tiled in a diagonal grid, faint, drifting slowly. A tap on one pops it
 * ({@link #popAt}; the geometry is BananaGrid): it bursts into flecks and comes back 5 s later.
 * The drift wraps by whole grid periods, so it is seamless. It animates only between {@link #setRunning} true and false (the
 * activity's onResume / onPause, so never while a game runs over it) and only while the system's animations are on; otherwise
 * it is a still picture. One banana bitmap is drawn per tile: no allocation per frame.
 */
public final class BananaBackgroundView extends View implements Choreographer.FrameCallback {
    private static final long POP_MS = 260, RESPAWN_MS = 5000, GROW_MS = 380;   // a popped banana bursts, stays away 5 s, then grows back
    private static final int SHARDS = 7;                                          // peel flecks flying out of a burst
    private static final long MIN_FRAME_NS = 15_000_000L;          // at most ~60 redraws a second on 90 / 120 Hz panels

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int baseAlpha;
    private Bitmap banana;
    private boolean wanted, attached, posted;
    private long lastNs;
    private double phase;   // seconds of drift so far
    private final java.util.HashMap<Long, Long> popped = new java.util.HashMap<>();   // BananaGrid.key → uptime (ms) of its pop
    private final Paint shard = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Runnable onPop;

    public BananaBackgroundView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        baseAlpha = night ? 40 : 72;   // low contrast: text and buttons stay readable
        paint.setAlpha(baseAlpha);
        shard.setColor(getContext().getColor(R.color.bn_banana_deep));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Animates while true and the system's animations are on (Settings: remove animations / animator duration scale 0 = still). */
    public void setRunning(boolean on) {
        wanted = on;
        update();
    }

    private boolean running() { return wanted && attached && ValueAnimator.areAnimatorsEnabled(); }

    private void update() {
        boolean running = running();
        if (running && !posted) {
            lastNs = 0;
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else if (!running && posted) {
            posted = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override public void doFrame(long nowNs) {
        posted = false;
        if (!running()) return;
        if (lastNs == 0) lastNs = nowNs;
        long dt = nowNs - lastNs;
        if (dt >= MIN_FRAME_NS) {
            phase += Math.min(dt, 100_000_000L) / 1e9;   // a long stall does not jump
            lastNs = nowNs;
            invalidate();
        }
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        update();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        update();
        super.onDetachedFromWindow();
    }

    private Bitmap banana() {
        if (banana == null) {
            int px = Math.max(1, Math.round(BananaGrid.TILE_DP * density));
            Drawable d = getContext().getDrawable(R.drawable.ic_banana);
            banana = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(banana));
        }
        return banana;
    }

    /** Called once for each banana popped (the launcher counts it and gives a light tick) */
    public void setOnPop(Runnable r) { onPop = r; }

    /**
     * A tap at (x, y) in this view's coordinates: pops the banana under it, if there is one that is not already popped or growing back.
     * It bursts (see {@link #onDraw}), stays away for 5 s, then grows back; the pop is pinned to its grid cell, so it drifts with the grid.
     * @return true when a banana was popped
     */
    public boolean popAt(float x, float y) {
        long k = BananaGrid.hit(x, y, getWidth(), getHeight(), phase * BananaGrid.SPEED_X_DP * density, phase * BananaGrid.SPEED_Y_DP * density, density);
        if (k == BananaGrid.NONE) return false;
        long now = SystemClock.uptimeMillis();
        Long at = popped.get(k);
        if (at != null && now - at < RESPAWN_MS + GROW_MS) return false;
        popped.put(k, now);
        invalidate();
        if (!running()) postInvalidateDelayed(RESPAWN_MS + GROW_MS);   // a still picture: the banana comes back without an animation
        if (onPop != null) onPop.run();
        return true;
    }

    /** A banana's look at this time since its pop: scale and alpha factor, and how far its flecks have flown (0 = none) */
    private void drawBanana(Canvas canvas, Bitmap b, float half, Long at, long now, boolean odd) {
        float scale = 1f, alpha = 1f, burst = -1f;
        if (at != null) {
            long age = now - at;
            boolean anim = ValueAnimator.areAnimatorsEnabled();
            if (age < POP_MS && anim) {   // burst: swells out of its shape while fading, flecks fly out
                float t = age / (float) POP_MS, e = 1f - (1f - t) * (1f - t);
                scale = 1f + 0.45f * e;
                alpha = 1f - t;
                burst = e;
            } else if (age < RESPAWN_MS || !anim && age < RESPAWN_MS + GROW_MS) {
                return;   // gone
            } else {      // back: grows from nothing, a little past full size, then settles
                float t = Math.min(1f, (age - RESPAWN_MS) / (float) GROW_MS);
                float o = t - 1f;
                scale = Math.max(0f, 1f + 2.2f * o * o * o + 1.2f * o * o);   // overshoot ease-out
                alpha = Math.min(1f, t * 2f);
            }
        }
        if (burst >= 0) {
            float reach = BananaGrid.HIT_DP * density * 1.3f * burst, r = 3.2f * density * (1f - burst * 0.6f);
            shard.setAlpha((int) (Math.min(255, baseAlpha * 3) * (1f - burst)));
            for (int s = 0; s < SHARDS; s++) {
                double a = s * 2 * Math.PI / SHARDS + 0.4;
                canvas.drawCircle((float) Math.cos(a) * reach, (float) Math.sin(a) * reach, r, shard);
            }
        }
        paint.setAlpha(Math.round(baseAlpha * alpha));
        canvas.save();
        canvas.rotate(odd ? 28f : -8f);
        canvas.scale(scale, scale);
        canvas.drawBitmap(b, -half, -half, paint);
        canvas.restore();
        paint.setAlpha(baseAlpha);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        Bitmap b = banana();
        float step = BananaGrid.STEP_DP * density, row = BananaGrid.ROW_DP * density, half = b.getWidth() / 2f;
        // Rows alternate a half step sideways and a mirrored tilt, so the pattern repeats every step across and every two rows down.
        // Each banana has an absolute row and column (BananaGrid), which do not change as the drift wraps: that is how a pop stays on its banana.
        double wx = phase * BananaGrid.SPEED_X_DP * density, wy = phase * BananaGrid.SPEED_Y_DP * density;
        int shiftC = (int) Math.floor(wx / step), shiftR = (int) Math.floor(wy / (2 * row));
        float cx = w / 2f, cy = h / 2f, reach = (float) Math.hypot(w, h) / 2f + step;
        int cols = (int) Math.ceil(reach / step) + 1, rows = 2 * ((int) Math.ceil(reach / row / 2) + 1);
        long now = SystemClock.uptimeMillis();
        if (!popped.isEmpty()) popped.values().removeIf(at -> now - at >= RESPAWN_MS + GROW_MS);   // grown back: forgotten
        canvas.save();
        canvas.rotate(BananaGrid.GRID_DEG, cx, cy);
        for (int r = -rows; r <= rows; r++) {
            int rowId = r - 2 * shiftR - 1;
            for (int c = -cols; c <= cols; c++) {
                int colId = c - shiftC - 1;
                canvas.save();
                canvas.translate(cx + (float) BananaGrid.centreX(rowId, colId, step, wx), cy + (float) BananaGrid.centreY(rowId, row, wy));
                drawBanana(canvas, b, half, popped.isEmpty() ? null : popped.get(BananaGrid.key(rowId, colId)), now, BananaGrid.odd(rowId));
                canvas.restore();
            }
        }
        canvas.restore();
    }
}
