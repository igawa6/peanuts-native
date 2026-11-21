package dev.peanuts.ui;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a launcher page is: its scroll position and the row a controller is on. MainActivity builds a page anew for every change (new views in a new
 * ScrollView), so Settings and a game's page came back at the top, the focus on the first row, after Advanced, a rotation or the activity made again.
 * A place is taken before the rebuild and put back once the new page is laid out (PEANUTS hooks in MainActivity, UPSTREAM-EDITS Round 41).
 */
public final class PagePlace {
    private static final String K_Y = "pn_place_y", K_WORD = "pn_place_word", K_NTH = "pn_place_nth", K_INDEX = "pn_place_index";
    private static final long SETTLE_MS = 1000;   // rows that fill in after the build (read off the main thread) may still make the page taller
    private static PagePlace under;               // the page Advanced covers (MainActivity.openAdvanced), back at closeAdvanced

    final int y;
    final String word;   // the focused row's first text; null: no row focused (touch)
    final int nth, index;

    private PagePlace(int y, String word, int nth, int index) { this.y = y; this.word = word; this.nth = nth; this.index = index; }

    /** Where the page in this ScrollView is now; null without one */
    public static PagePlace of(ScrollView s) {
        if (s == null) return null;
        View f = s.findFocus();
        if (f == null || f.isInTouchMode()) return new PagePlace(s.getScrollY(), null, -1, -1);
        List<View> rows = rows(s);
        int i = rows.indexOf(f);
        return new PagePlace(s.getScrollY(), i < 0 ? null : word(f), i < 0 ? -1 : PlacePick.nth(words(rows), i), i);
    }

    /** The page built again in this ScrollView: back to the same scroll position, and with a controller to the same row, once it is laid out */
    public void restore(ScrollView s) {
        if (s == null) return;
        long until = SystemClock.uptimeMillis() + SETTLE_MS;
        s.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int left = -1;   // the scroll position this left the page at; -1 before the first layout
            @Override public void onGlobalLayout() {
                View content = s.getChildCount() > 0 ? s.getChildAt(0) : null;
                if (content == null || content.getHeight() == 0) return;
                boolean first = left < 0;
                if (!first && (s.getScrollY() != left || SystemClock.uptimeMillis() > until || !s.isAttachedToWindow())) { done(); return; }   // scrolled since, or settled
                s.scrollTo(0, y);   // clamped while the page is shorter than it was: once more at the next layout, while rows still fill in
                left = s.getScrollY();
                if (first && index >= 0) {
                    if (!s.isInTouchMode()) { focus(s); left = s.getScrollY(); }   // in this first layout, before the page's own first-row focus (MainActivity.build posts it)
                    else focusOnKey(s, left);   // a new window starts in touch mode: the row comes back with the first D-pad press
                }
                if (left == y) done();
            }

            private void done() { s.getViewTreeObserver().removeOnGlobalLayoutListener(this); }
        });
    }

    /** The row this place was on, in the rebuilt page; null when there is none */
    private View row(ScrollView s) {
        List<View> rows = rows(s);
        int k = PlacePick.pick(words(rows), word, nth, index);
        return k >= 0 ? rows.get(k) : null;
    }

    private void focus(ScrollView s) {
        View r = row(s);
        if (r != null) r.requestFocus();
    }

    /**
     * The row, at a controller's first press: until then the window is in touch mode and shows no focus. As the page's default focus, Android's own
     * leaveTouchMode gives it the focus and takes that press (it would focus the first row shown); only while the page was not scrolled since
     */
    private void focusOnKey(ScrollView s, int at) {
        View r = row(s);
        if (r == null) return;
        r.setFocusedByDefault(true);
        s.getViewTreeObserver().addOnTouchModeChangeListener(new ViewTreeObserver.OnTouchModeChangeListener() {
            @Override public void onTouchModeChanged(boolean touch) {
                if (touch) return;
                s.getViewTreeObserver().removeOnTouchModeChangeListener(this);
                if (s.isAttachedToWindow() && (s.getScrollY() == at || s.getScrollY() == y)) s.post(() -> r.setFocusedByDefault(false));   // after this press
                else r.setFocusedByDefault(false);   // scrolled since, or another page: Android's own choice
            }
        });
    }

    /** Every row a controller can reach, in focus order, also while the window is in touch mode */
    private static List<View> rows(ScrollView s) {
        ArrayList<View> out = new ArrayList<>();
        s.addFocusables(out, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        return out;
    }

    // ---- MainActivity's hooks ----

    /** openAdvanced: the page under it, before Advanced is built in its place */
    public static void cover(ScrollView s) { under = of(s); }

    /** closeAdvanced: the page under it was built again */
    public static void uncover(ScrollView s) {
        PagePlace p = under;
        under = null;
        if (p != null) p.restore(s);
    }

    /** onSaveInstanceState: the page's place (with Advanced open, the page under it: the activity made again shows that page, as upstream does) */
    public static void save(Bundle out, ScrollView s, boolean covered) {
        PagePlace p = covered && under != null ? under : of(s);
        if (p == null) return;
        out.putInt(K_Y, p.y);
        out.putString(K_WORD, p.word);
        out.putInt(K_NTH, p.nth);
        out.putInt(K_INDEX, p.index);
    }

    /** onCreate, after the first build: the place the activity had before it was made again */
    public static void restore(Bundle saved, ScrollView s) {
        if (saved == null || !saved.containsKey(K_Y)) return;
        new PagePlace(saved.getInt(K_Y), saved.getString(K_WORD), saved.getInt(K_NTH, -1), saved.getInt(K_INDEX, -1)).restore(s);
    }

    // ---- A row's words ----

    private static List<String> words(List<View> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (View v : rows) out.add(word(v));
        return out;
    }

    /** A row's first text (a settings row's title, a button's label, a tab's name); "" when it has none */
    static String word(View v) {
        if (v instanceof TextView) { CharSequence t = ((TextView) v).getText(); if (t != null && t.length() > 0) return t.toString(); }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { String w = word(g.getChildAt(i)); if (!w.isEmpty()) return w; }
        }
        CharSequence d = v.getContentDescription();
        return d != null ? d.toString() : "";
    }
}
