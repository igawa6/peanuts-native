package dev.peanuts.users;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.R;
import dev.peanuts.ui.PUi;

/**
 * A page's second half: on a device with a second screen (the Thor's bottom one) it is shown there, touch-first, on the app's background with its drifting
 * peanuts, while the page itself stays on the top screen for the D-pad; on a phone with one screen it goes into the page's own host view (below or beside the
 * page). The page gives a builder with {@link #set} whenever its content changes; the builder gets the context of the screen it draws on (the bottom screen
 * has a density of its own), and runs again when the content moves between screens. The window comes with the activity's start and goes with its stop.
 */
public final class Bottom implements DisplayManager.DisplayListener {
    private final Activity a;
    private final ViewGroup inline;
    private final DisplayManager dm;
    private Panel panel;
    private Builder content;
    private boolean started;

    /** Makes the content in the context of the screen it shows on */
    public interface Builder { View build(Context c); }

    /** inline: where the content goes when there is no second screen (left empty and hidden when there is one) */
    public Bottom(Activity a, ViewGroup inline) {
        this.a = a; this.inline = inline;
        dm = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
        // The page draws edge to edge (PUi.page): the half on this screen keeps clear of the bars itself, beside the page (a wide screen) from the status bar
        // down, below it (a phone) above the navigation bar
        boolean beside = a.getResources().getConfiguration().screenWidthDp >= 600;
        inline.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(beside ? 0 : s.left, beside ? s.top : 0, s.right, s.bottom);
            return ins;
        });
    }

    /**
     * How many cells of cellDp (with marginDp around each) a page's grid has room for: the screen's width less the side half (sideDp) when it shows beside
     * the page, less the page's gutters (PUi.page: 20 dp a side) and at most its 760 dp; two at least, max at most
     */
    public static int columns(Activity a, int cellDp, int marginDp, int sideDp, int max) {
        int w = a.getResources().getConfiguration().screenWidthDp;
        if (w >= 600 && !onSecondScreen(a)) w -= sideDp;
        w = Math.min(w - 40, 760);
        return Math.max(2, Math.min(max, w / (cellDp + 2 * marginDp)));
    }

    /** Whether the content shows on a screen of its own */
    public boolean dual() { return second() != null; }

    public void set(Builder b) {
        content = b;
        place();
    }

    /** The content again (a value in it changed) */
    public void refresh() { place(); }

    public void start() {
        started = true;
        if (dm != null) dm.registerDisplayListener(this, null);
        place();
    }

    public void stop() {
        started = false;
        if (dm != null) dm.unregisterDisplayListener(this);
        if (panel != null) { panel.dismiss(); panel = null; }
    }

    private void place() {
        Display d = started ? second() : null;
        if (panel != null && (d == null || panel.getDisplay() == null || panel.getDisplay().getDisplayId() != d.getDisplayId())) { panel.dismiss(); panel = null; }
        if (d != null && content != null) {
            inline.removeAllViews();
            inline.setVisibility(View.GONE);
            if (panel == null) {
                panel = new Panel(a, d);
                try { panel.show(); } catch (WindowManager.InvalidDisplayException e) { panel = null; }
            }
            if (panel != null) { panel.put(content.build(panel.getContext())); return; }
        }
        inline.removeAllViews();
        if (content != null) inline.addView(content.build(a), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inline.setVisibility(content != null ? View.VISIBLE : View.GONE);
    }

    private Display second() { return second(a, dm); }

    /** Whether a page of a shows its second half on a screen of its own: a second screen, and Settings › Screens › Bottom screen on */
    public static boolean onSecondScreen(Activity a) { return second(a, (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE)) != null; }

    /**
     * The first valid display besides the page's own (the Thor lists its bottom screen as a presentation display, the emulator only in the plain list), null
     * when there is none or the player turned the bottom screen off (Settings › Screens › Bottom screen, which the launcher's own bottom screen follows too)
     */
    private static Display second(Activity a, DisplayManager dm) {
        if (dm == null || !dev.peanuts.PeanutsPrefs.bottomScreen(a)) return null;
        int own = a.getDisplay() != null ? a.getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        Map<Integer, Display> all = new LinkedHashMap<>();
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) all.put(d.getDisplayId(), d);
        for (Display d : dm.getDisplays()) all.put(d.getDisplayId(), d);
        for (Display d : all.values()) if (d.getDisplayId() != own && d.isValid()) return d;
        return null;
    }

    @Override public void onDisplayAdded(int id) { place(); }
    @Override public void onDisplayRemoved(int id) { place(); }
    @Override public void onDisplayChanged(int id) { if (panel != null && panel.getDisplay() != null && panel.getDisplay().getDisplayId() == id) place(); }

    /** The window on the second screen: the app's background, its peanuts, the page's content over them; it never takes the controller */
    private static final class Panel extends Presentation {
        private FrameLayout stage;
        private BananaBackgroundView bg;

        Panel(Activity owner, Display d) { super(owner, d, R.style.Theme_Banana); }

        @Override protected void onCreate(Bundle saved) {
            super.onCreate(saved);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().setDecorFitsSystemWindows(false);
            Context c = getContext();
            FrameLayout root = new FrameLayout(c);
            root.setBackgroundColor(PUi.col(c, R.color.bn_bg));
            bg = new BananaBackgroundView(c);
            root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            stage = new FrameLayout(c);
            root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        @Override protected void onStart() { super.onStart(); if (bg != null) bg.setRunning(true); }
        @Override protected void onStop() { if (bg != null) bg.setRunning(false); super.onStop(); }

        void put(View v) {
            if (stage == null) return;
            stage.removeAllViews();
            stage.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
}
