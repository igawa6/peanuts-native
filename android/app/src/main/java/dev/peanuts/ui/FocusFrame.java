package dev.peanuts.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The controller's focus frame (upstream's PadFocus.frame, put on the focused view as its foreground by PadFocus.watch) where the view is on screen. A view
 * draws its foreground in its scrolled coordinates, and a one-line text with its gravity centred scrolls far: a TextView lays a single line out on a very
 * wide line and scrolls to its middle. So on every button (Ui.button, PUi.button), every tab of a Segmented and every chip, all one-line and centred, the
 * frame was drawn far off to the right and only the background's faint focus tint, or Android's grey default highlight, showed: Check for updates on About
 * showed nothing at all. Here the frame follows the view's scroll back. It also says it shows the focus, so Android adds no grey highlight of its own.
 */
public final class FocusFrame extends Drawable {
    private final View view;
    private final Drawable frame;

    private FocusFrame(View view, Drawable frame) {
        this.view = view;
        this.frame = frame;
    }

    /** PadFocus.watch (PEANUTS hook): the frame for this view, drawn where the view shows */
    public static Drawable at(View view, Drawable frame) {
        return frame == null ? null : new FocusFrame(view, frame);
    }

    @Override public void draw(Canvas canvas) {
        int sx = view.getScrollX(), sy = view.getScrollY();
        if (sx == 0 && sy == 0) { frame.draw(canvas); return; }
        int save = canvas.save();
        canvas.translate(sx, sy);
        frame.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onBoundsChange(Rect bounds) { frame.setBounds(bounds); }

    // the focus shows (View.isDefaultFocusHighlightNeeded asks the foreground): no grey default highlight over the frame
    @Override public boolean isStateful() { return true; }
    @Override public boolean hasFocusStateSpecified() { return true; }
    @Override protected boolean onStateChange(int[] state) { return false; }

    @Override public void setAlpha(int alpha) { frame.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { frame.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
