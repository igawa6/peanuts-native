package dev.peanuts.ingame;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The second screen's side of the bottom screen's menu (dev.nxnative.android.InGamePanel opens it): the game's live stats page (dev.peanuts.ui.GameScreen,
 * StatsView) gets a faint grip at its bottom edge, which moves with the page's pixel shifter, and a swipe up from that edge opens the menu on Game.
 * Only while the layout (Menu settings › Layout) is Bottom screen.
 */
public final class BottomMenu {
    private BottomMenu() {}

    /** Opens the menu on Game (InGamePanel.attach sets it for the running game) */
    public static Runnable opener;

    public static boolean on(Context c) { return dev.peanuts.PeanutsPrefs.menuLayout(c) == dev.peanuts.PeanutsPrefs.LAYOUT_BOTTOM; }

    /** The grip: 40 x 4 dp, the panel's row grey, at the bottom centre of the stats page's shifting content */
    public static View grip(Context c) {
        View g = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF3A3B3E);
        d.setCornerRadius(Look.dp(c, 2));
        g.setBackground(d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Look.dp(c, 40), Look.dp(c, 4), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Look.dp(c, 6);
        g.setLayoutParams(lp);
        return g;
    }

    /**
     * The page's root with the bottom edge watched: a swipe up of 32 dp that starts in the bottom 28 dp opens the menu. It only looks at the touches on their
     * way (onInterceptTouchEvent) and takes a gesture once it is that swipe, so a tap or any other touch near the edge still reaches the page under it
     */
    public static FrameLayout edgeRoot(Context c) {
        return new FrameLayout(c) {
            private final float dp = c.getResources().getDisplayMetrics().density;
            private float downY = -1;

            /** True once the gesture is the swipe (the menu opened) */
            private boolean swipe(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) downY = e.getY() >= getHeight() - 28 * dp ? e.getRawY() : -1;
                else if (a == MotionEvent.ACTION_MOVE && downY >= 0 && downY - e.getRawY() > 32 * dp) { downY = -1; if (opener != null) opener.run(); return true; }
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) downY = -1;
                return false;
            }

            @Override public boolean onInterceptTouchEvent(MotionEvent e) { return swipe(e); }   // a page's view took the touch: watched on its way there

            // No view under it took the touch: the root has the gesture, kept from a down at the edge on so its moves are seen
            @Override public boolean onTouchEvent(MotionEvent e) { boolean edge = downY >= 0; swipe(e); return edge || downY >= 0; }
        };
    }
}
