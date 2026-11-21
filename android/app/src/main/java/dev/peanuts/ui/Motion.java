package dev.peanuts.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import dev.peanuts.PeanutsPrefs;

import java.util.ArrayList;

/**
 * The launcher's motion: six tokens (a duration and an easing each), used by the library and its sheets, and through {@link Feel} by the pages upstream draws (a game's page), and nothing outside them. Only alpha, translation
 * and scale are animated: they are render node properties, so no frame of an animation measures, lays out or redraws a view's content (the cover grid is one
 * ScrollView holding every cover of the library; one layout pass touches all of them). The one exception is the cover crossfade, which redraws one tile per
 * frame and is capped. With the system's animator scale at 0 every helper sets the end state at once; under reduced motion (the app's setting, battery
 * saver, heat, a screen reader) the scales, the stagger, the pop and the shake go and the fades stay.
 */
final class Motion {
    private Motion() {}

    /** SNAP: a focus lost, a press, things settling back. FOCUS: a focus gained. ENTER: something appears. EXIT: something leaves. EMPHASIS: once per event
     *  (a build's end). PROGRESS: a progress value changes. STAGGER: between two rows that enter */
    static final int SNAP = 90, FOCUS = 130, ENTER = 180, EXIT = 120, EMPHASIS = 320, PROGRESS = 300, STAGGER = 24;
    static final Interpolator OUT = new PathInterpolator(0.2f, 0f, 0f, 1f), IN = new PathInterpolator(0.3f, 0f, 1f, 1f),
            OVERSHOOT = new OvershootInterpolator(1.6f), LINEAR = new LinearInterpolator();

    /** A focused tile's scale: 3.5 % of a cover is under the 4 dp its cell keeps free on each side for the focus ring, so nothing is clipped */
    private static final float FOCUSED = 1.035f, PRESSED = 0.97f, POP_LOW = 0.95f, POP_HIGH = 1.04f;
    private static final long RAPID_MS = 120;   // two focus changes closer than this are a held direction, whatever moved the focus
    private static final int MAX_ROWS = 3, MAX_COVER_FADES = 8, MAX_PIECES = 12;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Rect RECT = new Rect();
    private static boolean enabled = true, reduced;
    private static long lastFocusAt;
    private static boolean held;
    private static View landing;   // the tile whose focus scale waits for the held direction's release
    private static int coverFades;
    private static final ArrayList<Animator> running = new ArrayList<>();   // the pop, the shake and the cover crossfades in flight (cancelled when the grid is rebuilt)
    private static int sheetStyle = -1, sheetStyleReduced = -1, fadeOut = -1, hold = -1;

    /** The settings read again (the system's animator scale, the app's row, battery saver, heat, a screen reader): at every Home refresh and resume */
    static void refresh(Context c) {
        enabled = ValueAnimator.areAnimatorsEnabled();
        reduced = PeanutsPrefs.motionReduced(c) || Haptics.strained(c);
    }

    /** Whether the decorative motion runs (scales, stagger, pop, shake): animators are enabled and motion is not reduced */
    static boolean on() { return enabled && !reduced; }

    // ---- Focus and press (a cover's tile) ----

    /** A direction is held (key repeat), or no longer: the focus scale snaps while it is, see {@link #focus} */
    static void held(boolean on) { held = on; }

    private static final Runnable LAND = () -> {
        View t = landing;
        landing = null;
        if (t != null && on() && coverFocused(t)) scale(t, FOCUSED, FOCUS);
    };

    private static boolean coverFocused(View tile) { ViewParent p = tile.getParent(); return p instanceof View && ((View) p).isFocused() && tile.isAttachedToWindow(); }

    private static void set(View v, float s) { v.setScaleX(s); v.setScaleY(s); }

    private static void scale(View v, float s, int ms) { v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(ms).setInterpolator(OUT); }

    /**
     * A cover gained or lost the focus: its tile grows to 103.5 % (FOCUS) or goes back (SNAP). While a direction is held, and for any two changes closer
     * than 120 ms, nothing is animated: the scale is set, and the tile the focus rests on when the moves stop grows once, as the landing cue. snap: the focus
     * was put there by code (after a rebuild): the end state at once. At most two tiles are ever in flight, the one gaining and the one losing.
     */
    static void focus(View tile, boolean gained, boolean snap) {
        long now = SystemClock.uptimeMillis();
        boolean rapid = held || now - lastFocusAt < RAPID_MS;
        tile.animate().cancel();
        if (landing == tile) landing = null;
        if (!gained) {
            if (rapid || snap || !on()) set(tile, 1f); else scale(tile, 1f, SNAP);
            return;
        }
        lastFocusAt = now;
        if (!on()) { set(tile, 1f); return; }
        if (snap) { set(tile, FOCUSED); return; }
        if (!rapid) { scale(tile, FOCUSED, FOCUS); return; }
        set(tile, 1f);
        landing = tile;
        UI.removeCallbacks(LAND);
        UI.postDelayed(LAND, RAPID_MS + 10);
    }

    /** The landing cue on a tile whose cover has the focus already (Home is back after a game): the focus scale once more, from rest */
    static void land(View tile) {
        if (!on()) return;
        tile.animate().cancel();
        set(tile, 1f);
        scale(tile, FOCUSED, FOCUS);
    }

    /** A press on a cover: its tile dips to 97 % and comes back to where it belongs, while whatever the press started goes on (nothing waits for this) */
    static void press(View tile) {
        if (!on()) return;
        tile.animate().cancel();
        tile.animate().scaleX(PRESSED).scaleY(PRESSED).setStartDelay(0).setDuration(SNAP).setInterpolator(OUT)
                .withEndAction(() -> scale(tile, coverFocused(tile) ? FOCUSED : 1f, SNAP));
    }

    // ---- Things that appear ----

    private static final View.OnAttachStateChangeListener CANCEL_ON_DETACH = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) { v.animate().cancel(); v.removeOnAttachStateChangeListener(this); }
    };

    /**
     * The rows of box from index first on enter after a rebuild the user caused: each fades in and rises 8 dp (ENTER), STAGGER apart. Only rows inside the
     * viewport and at most three, decided once in a pre-draw pass after the layout: three bounded layers, whatever the library's size (a fade of the whole
     * grid would need a layer taller than any texture, or blend every cover every frame).
     */
    static void enterRows(ViewGroup box, int first) {
        if (!on() || first >= box.getChildCount()) return;
        box.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = box.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                float rise = 8 * box.getResources().getDisplayMetrics().density;
                int n = 0;
                for (int i = first; i < box.getChildCount() && n < MAX_ROWS; i++) {
                    View r = box.getChildAt(i);
                    if (!r.getGlobalVisibleRect(RECT)) { if (n > 0) break; continue; }
                    r.setAlpha(0f);
                    r.setTranslationY(rise);
                    r.addOnAttachStateChangeListener(CANCEL_ON_DETACH);
                    r.animate().alpha(1f).translationY(0f).setStartDelay((long) n * STAGGER).setDuration(ENTER).setInterpolator(OUT).withLayer();
                    n++;
                }
                return true;
            }
        });
    }

    /**
     * A page, or the part of one under its tabs, was built for the user (a game's page opened, another tab or section picked): what shows of column's
     * children from index first on fades in and rises 8 dp (ENTER), the first three STAGGER apart; the fade alone under reduced motion. Decided once, in a
     * pre-draw pass after the layout. A child taller than the window is not one layer (a card of thirty rows): its own children enter instead, so every
     * layer is smaller than the window; at most twelve pieces, and what is under the fold just shows. Never called for a page built again after a choice.
     */
    static void enterPage(ViewGroup column, int first) {
        if (!enabled || first >= column.getChildCount()) return;
        column.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = column.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                if (!column.isAttachedToWindow()) return true;
                int window = column.getRootView().getHeight();
                int[] n = {0};
                for (int i = first; i < column.getChildCount(); i++) piece(column.getChildAt(i), window, on() ? 8 * column.getResources().getDisplayMetrics().density : 0f, n);
                return true;
            }
        });
    }

    private static void piece(View v, int window, float rise, int[] n) {
        if (n[0] >= MAX_PIECES || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f || !v.getGlobalVisibleRect(RECT)) return;
        if (v.getHeight() > window) {
            if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) piece(g.getChildAt(i), window, rise, n); }
            return;
        }
        float rest = v.getAlpha();   // a row that is off rests at 60 %, a button that is off at 42 %: the fade ends there
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.addOnAttachStateChangeListener(CANCEL_ON_DETACH);
        v.animate().alpha(rest).translationY(0f).setStartDelay(rise > 0f ? (long) Math.min(n[0], MAX_ROWS) * STAGGER : 0).setDuration(ENTER).setInterpolator(OUT).withLayer();
        n[0]++;
    }

    /**
     * A press on a row or a button of a page: it dips and comes back (SNAP each way) while what the press started goes on. The dip is the 3 dp a side a
     * cover's tile dips by (97 % of a cover), so a row as wide as the page does not shrink by 12 dp a side. Nothing else of the view is cancelled: a row
     * pressed while it still enters keeps its fade.
     */
    static void pressed(View v) {
        if (!on() || v.getWidth() <= 0) return;
        float s = Math.max(PRESSED, 1f - 6f * v.getResources().getDisplayMetrics().density / v.getWidth());
        v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(SNAP).setInterpolator(OUT).withEndAction(() -> scale(v, 1f, SNAP));
    }

    /** A line of text that was not there before (the scan's "Looking in...", the empty library's sentence): it fades in (ENTER) */
    static void fadeIn(View v) {
        if (!enabled) return;
        v.setAlpha(0f);
        v.addOnAttachStateChangeListener(CANCEL_ON_DETACH);
        v.animate().alpha(1f).setStartDelay(0).setDuration(ENTER).setInterpolator(OUT);
    }

    /** A mark that is new on a cover (the Ready check after a build): it grows from nothing, once (EMPHASIS) */
    static void popIn(View v) {
        if (!on()) return;
        set(v, 0f);
        v.addOnAttachStateChangeListener(CANCEL_ON_DETACH);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(EMPHASIS).setInterpolator(OVERSHOOT);
    }

    /**
     * A cover's art landed while its tile shows: the letter tile crossfades to it (ENTER; SNAP under reduced motion). Only for a tile on screen, and at most
     * eight at once: each crossfade redraws its tile every frame, and a first scan lands a few hundred icons. Everything else gets the art at once
     * (PUi.tileIcon), as does every tile whose art is known when it is built. TransitionDrawable does not follow the system's animator scale, so that is
     * asked first.
     */
    static void cover(TextView tile, Bitmap b) {
        int ink = tile.getCurrentTextColor();
        Drawable letter = tile.getBackground();
        boolean fade = b != null && enabled && coverFades < MAX_COVER_FADES && letter != null && Color.alpha(ink) != 0 && tile.isShown() && tile.getGlobalVisibleRect(RECT);
        PUi.tileIcon(tile, b);
        if (!fade) return;
        Drawable art = tile.getBackground();
        int ms = reduced ? SNAP : ENTER;
        TransitionDrawable t = new TransitionDrawable(new Drawable[] {letter, art});   // the same minimum size as the art alone: no layout pass
        tile.setBackground(t);
        t.startTransition(ms);
        ObjectAnimator a = ObjectAnimator.ofArgb(tile, "textColor", ink, ink & 0x00FFFFFF);
        a.setDuration(ms);
        a.setInterpolator(LINEAR);
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) { coverFades--; running.remove(x); tile.setTextColor(Color.TRANSPARENT); }
        });
        coverFades++;
        running.add(a);
        a.start();
    }

    // ---- A build's progress and end ----

    /** A progress value: the platform animates the fill (a draw, no layout); first: the value a bar is built with, shown as it is (no run up from 0) */
    static void progress(ProgressBar p, int value, boolean first) { p.setProgress(Math.max(0, Math.min(p.getMax(), value)), enabled && !first); }

    private static void once(ObjectAnimator a, Interpolator i) {
        a.setDuration(EMPHASIS);
        a.setInterpolator(i);
        a.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator x) { running.remove(x); } });
        running.add(a);
        a.start();
    }

    /** A build ended well: its cover's tile dips and swells once (95 %, 104 %, back; EMPHASIS). 104 % is what the cell's 4 dp margin leaves room for */
    static void pop(View tile) {
        if (!on()) return;
        tile.animate().cancel();
        float rest = coverFocused(tile) ? FOCUSED : 1f;
        once(ObjectAnimator.ofPropertyValuesHolder(tile, PropertyValuesHolder.ofFloat(View.SCALE_X, rest, POP_LOW, POP_HIGH, rest),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, rest, POP_LOW, POP_HIGH, rest)), OUT);
    }

    /** A build failed: its chip shakes once (4 dp left, 4 dp right, 2 dp left, back; EMPHASIS) */
    static void shake(View v) {
        if (!on()) return;
        float d = v.getResources().getDisplayMetrics().density;
        once(ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -4 * d, 4 * d, -2 * d, 0f), LINEAR);
    }

    /** The grid is about to be rebuilt: what is in flight ends now (each animation's end state is the resting state) */
    static void clear() {
        UI.removeCallbacks(LAND);
        landing = null;
        for (Animator a : new ArrayList<>(running)) a.end();
        running.clear();
    }

    // ---- Windows (run by the system, outside the app's UI thread) ----

    private static int id(Context c, String name, String type) { return c.getResources().getIdentifier(name, type, c.getPackageName()); }

    /** A sheet's window animation: in with a fade and a 96 % scale (ENTER), out with a fade (EXIT); the fade alone under reduced motion. The styles are
     *  found by name (they are in the Peanuts resource folder only); without them the platform's dialog animation stays */
    static void sheet(Dialog d) {
        if (d == null || d.getWindow() == null) return;
        Context c = d.getContext();
        if (sheetStyle < 0) { sheetStyle = id(c, "PeanutsSheet", "style"); sheetStyleReduced = id(c, "PeanutsSheetReduced", "style"); }
        int s = reduced ? sheetStyleReduced : sheetStyle;
        if (s != 0) d.getWindow().setWindowAnimations(s);
    }

    private static int drawerStyle = -1, drawerStyleReduced = -1;

    /** Settings' drawer of the sections (a phone, SettingsPage): in with a fade from 8 % to the left (ENTER), out the same way (EXIT); the fade alone under
     *  reduced motion. Found by name as the sheets' are */
    static void drawer(Dialog d) {
        if (d == null || d.getWindow() == null) return;
        Context c = d.getContext();
        if (drawerStyle < 0) { drawerStyle = id(c, "PeanutsDrawer", "style"); drawerStyleReduced = id(c, "PeanutsDrawerReduced", "style"); }
        int s = reduced || !enabled ? drawerStyleReduced : drawerStyle;
        if (s != 0) d.getWindow().setWindowAnimations(s);
    }

    /**
     * Right after a game's activity was started: Home fades out (EXIT) over the game's window, which is black until its first frame, so the launch reads as
     * a fade through black. The system runs it, so a busy main thread during the launch does not hold it. Called when the launch call returned, whether it
     * started the game or opened one of its own dialogs first (a start from such a dialog keeps the platform's transition).
     */
    @SuppressWarnings("deprecation")   // overrideActivityTransition (API 34) is set by the activity that opens, which is upstream's GameActivity in another process
    static void launched(Activity a) {
        if (fadeOut < 0) { fadeOut = id(a, "peanuts_fade_out", "anim"); hold = id(a, "peanuts_hold", "anim"); }
        if (fadeOut != 0 && hold != 0) a.overridePendingTransition(hold, fadeOut);
    }
}
