package dev.peanuts.ingame;

import android.app.Presentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The window of the menu on the bottom screen (Menu settings › Layout: Bottom screen):
 * a see-through window on the second display, over whatever shows there (the game's live stats, a companion, Android's launcher), made when the menu opens
 * and gone when it has slid back down, so the screen is given back. It never takes the input focus: the controller stays with the game's window, which
 * hands its keys to the menu (dev.nxnative.android.InGamePanel).
 */
public final class BottomScreen extends Presentation {
    private final View content;
    private final Runnable touched;

    /** touched: a finger went down on the window (the menu is driven by touch again, InGamePanel.keyDriven) */
    public BottomScreen(Context game, Display d, View content, Runnable touched) {
        super(game, d);
        this.content = content;
        this.touched = touched;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && touched != null) touched.run();
        return super.dispatchTouchEvent(e);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        getWindow().setBackgroundDrawable(new ColorDrawable(0));
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);   // the whole screen, under a navigation bar if the display has one
        getWindow().setNavigationBarColor(0);
        getWindow().setStatusBarColor(0);
        FrameLayout root = new FrameLayout(getContext());
        if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** The menu leaves the window (before it goes): the panel is reused the next time */
    public void release() { if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content); }
}
