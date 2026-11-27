package dev.peanuts.ingame;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

/**
 * The in-game menus' look: upstream's grey panel, the banana-yellow focus, the peanut browns,
 * and the solid icons (res drawable/pis_*.xml from design/peanuts-native/icons, colours per state from res values/peanuts_ingame.xml). Also their motion.
 */
public final class Look {
    private Look() {}

    // The panel (SidePanel, ModsTab): the sheet, a row, a chip, text, quiet text, the yellow and the text on it
    public static final int SHEET = 0xF5202124, ROW = 0xFF2D2E31, CHIP = 0xFF3A3B3E, TEXT = 0xFFE8EAED, QUIET = 0xFFB0B3B8, YELLOW = 0xFFFFD54F, ON_YELLOW = 0xFF2A1D0C;
    // The rail: its ground, its edge, the selected block (peanut brown); a section head (peanut tan); a line that went wrong; the scrim over the game
    public static final int RAIL = 0xFF151618, RAIL_EDGE = 0xFF2A2B2E, PEANUT = 0xFF4A3920, TAN = 0xFFCDB48C, BAD = 0xFFFF9670, SCRIM = 0x66000000, HAIRLINE = 0xFF3A3B3E;

    /** The solid icons' colours: REST (the rail, not selected), SELECTED (the selected tab), MENU (the game menu's rows, the back button) */
    public enum Ink {
        REST("PeanutsSolidRest"), SELECTED("PeanutsSolidSelected"), MENU("PeanutsSolidMenu");
        final String style;
        Ink(String style) { this.style = style; }
    }

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final Map<Ink, Resources.Theme> THEMES = new HashMap<>();

    private static int id(Context c, String name, String type) {
        String key = type + "/" + name;
        Integer v = IDS.get(key);
        if (v == null) { v = c.getResources().getIdentifier(name, type, c.getPackageName()); IDS.put(key, v); }
        return v;
    }

    /** The solid icon named (bolt, cheats, ...: pis_<name>) in these colours, at its own 24 dp; null when this build has none */
    public static Drawable icon(Context c, String name, Ink ink) {
        int d = id(c, "pis_" + name.replace('-', '_'), "drawable");
        if (d == 0) return null;
        Resources.Theme t = THEMES.get(ink);
        if (t == null) {
            int s = id(c, ink.style, "style");
            if (s == 0) return null;
            t = c.getResources().newTheme();
            t.setTo(c.getTheme());
            t.applyStyle(s, true);
            THEMES.put(ink, t);
        }
        try { return c.getResources().getDrawable(d, t).mutate(); } catch (Resources.NotFoundException e) { return null; }
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable round(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** The controller's focus: the panel's own frame (SidePanel.focusMoved), a yellow ring with a light yellow tint, here with the row's corners */
    public static GradientDrawable focus(Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x26FFD54F);
        d.setStroke(Math.round(2.5f * c.getResources().getDisplayMetrics().density), YELLOW);
        d.setCornerRadius(radiusDp * c.getResources().getDisplayMetrics().density);
        return d;
    }

    // ---- Motion: calm, no overshoot. In with a long deceleration, out a little quicker, accelerating ----
    public static final int OPEN_MS = 240, CLOSE_MS = 180, TAB_OUT_MS = 60, TAB_IN_MS = 100, BLOCK_MS = 160;
    /** The bottom screen's menu: up from the bottom edge, back down */
    public static final int UP_MS = 280, DOWN_MS = 220;
    public static final Interpolator OUT = new PathInterpolator(0.2f, 0f, 0f, 1f), IN = new PathInterpolator(0.3f, 0f, 1f, 1f);

    /**
     * A global focus listener that stays with v from window to window. One added while v has no window is kept by the first window it joins only: the bottom
     * screen's menu goes into a new window (BottomScreen) at each opening, and from the second opening its focus frame, the rail's tab switching and the
     * focus ticks were gone. Here it is taken off the window v leaves and put on each window v joins
     */
    public static void focusListener(android.view.View v, android.view.ViewTreeObserver.OnGlobalFocusChangeListener l) {
        v.getViewTreeObserver().addOnGlobalFocusChangeListener(l);
        v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View x) {
                android.view.ViewTreeObserver o = x.getViewTreeObserver();
                o.removeOnGlobalFocusChangeListener(l);   // the first window has it already (merged from the detached view's own)
                o.addOnGlobalFocusChangeListener(l);
            }

            @Override public void onViewDetachedFromWindow(android.view.View x) { x.getViewTreeObserver().removeOnGlobalFocusChangeListener(l); }
        });
    }
}
