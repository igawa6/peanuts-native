package dev.peanuts.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The app's peanut (res/drawable/ic_banana.xml of the peanuts source set: the same shell, 108 units across) split along its seam: drawn by PrepareCover
 * while shaders are prepared, by CrackedPeanut on About, and by Crack when a peanut of the background is tapped. A crack runs down the seam, the halves
 * swing apart about the foot (54, 90) and show the two kernels. Coordinates are the drawable's 108 units; the caller scales.
 */
final class PeanutArt {
    static final float[] ZIG = {54, 18, 57, 27, 51, 36, 57, 45, 52, 54, 57, 63, 51, 72, 56, 81, 54, 92};   // the seam, top to foot
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shell = new Path(), shade = new Path(), left = new Path(), right = new Path(), crack = new Path();
    private int ink = 255;   // a falling half's alpha, drawn on each of its paints (no layer a frame: the setup's peanut draws in software)

    PeanutArt() {
        shell.moveTo(54, 20); shell.cubicTo(65, 20, 71, 29, 69, 39); shell.cubicTo(68, 46, 62, 50, 58, 54); shell.cubicTo(62, 58, 69, 62, 70, 71);
        shell.cubicTo(72, 82, 65, 90, 54, 90); shell.cubicTo(43, 90, 36, 82, 38, 71); shell.cubicTo(39, 62, 46, 58, 50, 54); shell.cubicTo(46, 50, 40, 46, 39, 39);
        shell.cubicTo(37, 29, 43, 20, 54, 20); shell.close();
        shade.moveTo(58, 54); shade.cubicTo(62, 58, 69, 62, 70, 71); shade.cubicTo(72, 82, 65, 90, 54, 90); shade.cubicTo(49, 90, 45, 88, 42, 85);
        shade.cubicTo(50, 85, 59, 80, 61, 70); shade.cubicTo(63, 62, 60, 56, 58, 54); shade.close();
        left.moveTo(0, 0); right.moveTo(108, 0);
        for (int i = 0; i < ZIG.length; i += 2) { left.lineTo(ZIG[i], ZIG[i + 1]); right.lineTo(ZIG[i], ZIG[i + 1]); }
        left.lineTo(0, 108); left.close(); right.lineTo(108, 108); right.close();
        line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * crackT: how far the seam has run (0 to 1); swing: how far the halves have opened (0 to 1, the kernels show and the seam's line fades with it); deg:
     * each half's angle about the foot; alpha: the whole peanut's (255: opaque, drawn without a layer)
     */
    void draw(Canvas c, float crackT, float swing, float deg, int alpha) { draw(c, crackT, swing, deg, alpha, 0, 0, 0); }

    /**
     * The same, with the open shell falling away and its kernels after it (the first-run setup's end, SetupPeanut): shellDrop how far the halves have fallen,
     * dropTop and dropBottom how far each kernel has; 0 in place, 1 gone below it (each fades on the way)
     */
    void draw(Canvas c, float crackT, float swing, float deg, int alpha, float shellDrop, float dropTop, float dropBottom) {
        int layer = alpha < 255 ? c.saveLayerAlpha(0, 0, 108, 108, Math.max(0, alpha)) : -1;
        if (swing > 0) {   // the kernels, under the halves
            kernel(c, swing, dropTop, 45, 27, 63, 51, 49, 30, 54, 40);
            kernel(c, swing, dropBottom, 44, 58, 64, 84, 48, 62, 54, 73);
            fill.setAlpha(255);
        }
        if (swing <= 0 && deg == 0) whole(c);   // still closed: one shell, so no hairline shows between two clipped halves
        else { half(c, left, -deg, -1, shellDrop); half(c, right, deg, 1, shellDrop); }
        if (crackT > 0 && swing < 1) {   // the seam, as far as it has run
            crack.rewind();
            float end = 18 + (92 - 18) * crackT;
            crack.moveTo(ZIG[0], ZIG[1]);
            for (int i = 2; i < ZIG.length; i += 2) {
                if (ZIG[i + 1] <= end) { crack.lineTo(ZIG[i], ZIG[i + 1]); continue; }
                float f = (end - ZIG[i - 1]) / (ZIG[i + 1] - ZIG[i - 1]);
                crack.lineTo(ZIG[i - 2] + (ZIG[i] - ZIG[i - 2]) * f, end);
                break;
            }
            line.setColor(0xFF5E3A17); line.setStrokeWidth(1.8f); line.setAlpha((int) (255 * (1 - swing)));
            c.save(); c.clipPath(shell); c.drawPath(crack, line); c.restore();
            line.setAlpha(255);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** One kernel and its light, drop of the way down (an accelerating fall of 40 units, a slight turn, fading in its last half) */
    private void kernel(Canvas c, float swing, float drop, float l, float t, float r, float b, float hl, float ht, float hr, float hb) {
        if (drop >= 1) return;
        int a = (int) (255 * Math.min(1f, swing * 2) * (drop > 0.5f ? 1 - (drop - 0.5f) * 2 : 1));
        c.save();
        c.translate(0, 40 * drop * drop);
        c.rotate(18 * drop, (l + r) / 2, (t + b) / 2);
        fill.setColor(0xFFB5532A); fill.setAlpha(a); c.drawOval(l, t, r, b, fill);
        fill.setColor(0xFFD9825A); fill.setAlpha(a); c.drawOval(hl, ht, hr, hb, fill);
        c.restore();
    }

    /** One half about the foot; drop of the way down: falling (44 units, accelerating), out to its side and turning on, fading after its first third */
    private void half(Canvas c, Path side, float deg, int out, float drop) {
        if (drop >= 1) return;
        ink = drop > 0.3f ? (int) (255 * (1 - (drop - 0.3f) / 0.7f)) : 255;
        c.save();
        if (drop > 0) c.translate(out * 12 * drop, 44 * drop * drop);
        c.rotate(deg + out * 22 * drop, 54, 90);
        c.clipPath(side);
        shellOnly(c);
        c.restore();
        ink = 255;
    }

    private void whole(Canvas c) {
        c.save();
        shellOnly(c);
        c.restore();
    }

    private void shellOnly(Canvas c) {
        fill.setColor(0xFFD9A066); fill.setAlpha(ink); c.drawPath(shell, fill);
        fill.setColor(0xFFC08A52); fill.setAlpha(ink); c.drawPath(shade, fill);
        line.setColor(0xFF8C5A2B); line.setAlpha(ink); line.setStrokeWidth(2f); c.drawPath(shell, line);
        fill.setColor(0xFF8C5A2B); fill.setAlpha(ink);
        c.drawOval(48, 27.2f, 52.4f, 32.8f, fill); c.drawOval(60, 30.7f, 63.6f, 35.3f, fill); c.drawOval(46, 65.7f, 49.6f, 70.3f, fill); c.drawOval(59, 72.2f, 63.4f, 77.8f, fill);
        fill.setAlpha(255); line.setAlpha(255);
    }
}
