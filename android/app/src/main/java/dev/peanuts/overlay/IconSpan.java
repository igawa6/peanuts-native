package dev.peanuts.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/**
 * PEANUTS: a small icon in the overlay's pill (Hud.pill), drawn in code in one colour, so it needs no resource (the Peanuts drawables pi_temp and pi_battery are only
 * in the peanuts build's res) and follows the text's size, Size's RelativeSizeSpan included. The shapes are those icons' (24 x 24, a 1.75 round stroke); RAM is a
 * memory stick in the same style. It replaces one placeholder char; its colour is in the pill's value colours, so Overlay.key still sees a change of state.
 */
final class IconSpan extends ReplacementSpan {
    /** SEP: the thin line between the pill's two groups, half a char wide */
    static final int TEMP = 0, BATTERY = 1, RAM = 2, SEP = 3;
    private final int kind, color;

    IconSpan(int kind, int color) { this.kind = kind; this.color = color; }

    /** The icon's side: a little taller than the digits */
    private static float side(Paint p) { return p.getTextSize() * 1.1f; }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) { Paint.FontMetricsInt f = paint.getFontMetricsInt(); fm.ascent = f.ascent; fm.descent = f.descent; fm.top = f.top; fm.bottom = f.bottom; }
        return Math.round(kind == SEP ? paint.getTextSize() * 0.5f : side(paint));
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        float s = side(paint), k = s / 24f;
        float cy = y + (paint.descent() + paint.ascent()) / 2f;   // the middle of the text's height
        if (kind == SEP) {
            Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
            l.setColor(color);
            l.setStrokeWidth(Math.max(1f, paint.getTextSize() * 0.07f));
            float mx = x + paint.getTextSize() * 0.25f, half = paint.getTextSize() * 0.45f;
            canvas.drawLine(mx, cy - half, mx, cy + half, l);
            return;
        }
        canvas.save();
        canvas.translate(x, cy - s / 2f);
        canvas.scale(k, k);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.75f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        switch (kind) {
            case TEMP: {
                Path stem = new Path(), bulb = new Path();
                stem.addRoundRect(new RectF(10f, 3.5f, 14f, 15f), 2f, 2f, Path.Direction.CW);
                bulb.addCircle(12f, 17f, 4f, Path.Direction.CW);
                stem.op(bulb, Path.Op.UNION);
                canvas.drawPath(stem, line);
                canvas.drawCircle(12f, 17f, 1.75f, fill);
                break;
            }
            case BATTERY:
                canvas.drawRoundRect(new RectF(3.5f, 7f, 18.5f, 17f), 2f, 2f, line);
                canvas.drawLine(21f, 10.5f, 21f, 13.5f, line);
                canvas.drawRoundRect(new RectF(6.5f, 10f, 13f, 14f), 0.75f, 0.75f, fill);
                break;
            default:
                canvas.drawRoundRect(new RectF(3f, 8f, 21f, 16f), 1f, 1f, line);
                for (float px : new float[] {7f, 11f, 15f, 19f}) canvas.drawLine(px, 16f, px, 18.5f, line);
                for (float px : new float[] {6.5f, 10.5f, 14.5f}) canvas.drawRect(px, 10.5f, px + 3f, 13.5f, fill);
                break;
        }
        canvas.restore();
    }
}
