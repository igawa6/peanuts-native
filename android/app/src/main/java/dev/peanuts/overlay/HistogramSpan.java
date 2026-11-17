package dev.peanuts.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

/** PEANUTS: draws the frame-time histogram over its placeholder text (FrameStats.placeholder): one bar a char, as wide as {@code chars} monospace chars. */
final class HistogramSpan extends ReplacementSpan {
    private static final int[] COLORS = {0xFF4CAF50, 0xFFFFC107, 0xFFF44336};
    private final int chars;

    HistogramSpan(int chars) { this.chars = chars; }

    private float width(Paint p) {
        Paint m = new Paint(p);
        m.setTypeface(Typeface.MONOSPACE);
        return m.measureText("0") * chars;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) { Paint.FontMetricsInt f = paint.getFontMetricsInt(); fm.ascent = f.ascent; fm.descent = f.descent; fm.top = f.top; fm.bottom = f.bottom; }
        return Math.round(width(paint));
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        int n = end - start;
        if (n <= 0) return;
        float w = width(paint), step = w / n, gap = step > 3f ? 1f : 0f;
        float h = paint.descent() - paint.ascent() - 2f, base = y + paint.descent() - 1f;
        if (!canvas.isHardwareAccelerated()) report(canvas, x, base - h, x + w, base);   // the overlay's bitmap: the host paints the new bars there
        Paint p = new Paint(paint);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(start + i);
            p.setColor(COLORS[FrameStats.levelOf(c)]);
            float bar = h * (FrameStats.bucketOf(c) + 1) / FrameStats.BUCKETS;
            canvas.drawRect(x + i * step, base - bar, x + (i + 1) * step - gap, base, p);
        }
    }

    /** Tells the host where the bars are in the bitmap (the canvas's matrix applied), so it can draw a new bar every game frame (dev.peanuts.Bridge.graphRect) */
    private static void report(Canvas canvas, float l, float t, float r, float b) {
        float[] pt = {l, t, r, b};
        canvas.getMatrix().mapPoints(pt);
        int x = Math.round(Math.min(pt[0], pt[2])), y = Math.round(Math.min(pt[1], pt[3]));
        int x2 = Math.round(Math.max(pt[0], pt[2])), y2 = Math.round(Math.max(pt[1], pt[3]));
        try { dev.peanuts.Bridge.graphRect(x, y, x2 - x, y2 - y); } catch (UnsatisfiedLinkError e) { /* no host: the span's own bars stay */ }
    }
}
