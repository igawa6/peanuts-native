package dev.peanuts.ui;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

/**
 * How the in-game menus feel (dev.peanuts.ingame: the game menu, the quick menu, the bottom screen's menu), as a console's menus do: a fine tick of the
 * motor for each step of the focus and a firmer one for a choice ({@link Haptics}, the launcher's words, its gaps and its respect for the system's touch
 * feedback, battery saver and heat), and soft menu sounds ({@link Sounds}, the app's one place for them: these are its MOVE, TAB, SELECT, OPEN and BACK
 * tones, an edge its EDGE). Two settings in the menu's own settings: Vibrate on each step (on), kept in the game's prefs file (its process writes it), and
 * Menu sounds, which is the app's Sounds switch (Settings › App › Feel). Main thread only.
 */
public final class MenuFeel {
    private MenuFeel() {}

    private static boolean ready, haptics = true;
    private static long lastMove, lastOther;   // a step's tick waits for neither a held direction nor the firmer word of the same press

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE); }

    public static boolean haptics(Context c) { return prefs(c).getBoolean("menu_haptics", true); }
    /** The app's Sounds switch (Sounds.on): the in-game menu's Menu sounds row and Settings › App › Feel › Sounds are one setting */
    public static boolean sounds(Context c) { return Sounds.on(c); }
    public static void haptics(Context c, boolean on) { prefs(c).edit().putBoolean("menu_haptics", on).apply(); haptics = on; }
    public static void sounds(Context c, boolean on) { Sounds.on(c, on); }

    /** A menu opens: the settings read again (the app's haptics row, the system's touch feedback, battery saver, heat, the sounds' setting and Android's) */
    public static void refresh(Context c) {
        Haptics.refresh(c);
        haptics = haptics(c);
        ready = true;
    }

    private static void ready(View v) { if (!ready && v != null) refresh(v.getContext()); }

    /** The focus moved one step */
    public static void move(View v) {
        ready(v);
        long now = SystemClock.uptimeMillis();
        boolean held = now - lastMove < 90;   // a held direction: no buzz and no rattle at the key repeat rate
        lastMove = now;
        if (held || now - lastOther < 150) return;   // the focus a tab, an opening or a press put somewhere: that press has its word already
        if (haptics) Haptics.buzz(v, Haptics.Word.TICK);
        Sounds.play(v.getContext(), UiTones.MOVE);
    }

    /** The focus cannot go further that way */
    public static void edge(View v) { ready(v); if (haptics) Haptics.buzz(v, Haptics.Word.EDGE); if (v != null) Sounds.play(v.getContext(), UiTones.EDGE); }

    /** A row pressed, a choice or a switch taken */
    public static void select(View v) { ready(v); lastOther = SystemClock.uptimeMillis(); if (haptics) Haptics.buzz(v, Haptics.Word.CLICK); if (v != null) Sounds.play(v.getContext(), UiTones.SELECT); }

    /** Another tab (the rail, L and R) */
    public static void tab(View v) { ready(v); lastOther = SystemClock.uptimeMillis(); if (haptics) Haptics.buzz(v, Haptics.Word.TICK); if (v != null) Sounds.play(v.getContext(), UiTones.TAB); }

    /** A menu opens */
    public static void open(View v) { if (v != null) refresh(v.getContext()); lastOther = SystemClock.uptimeMillis(); if (v != null) Sounds.play(v.getContext(), UiTones.OPEN); }

    /** A menu closes, or B goes one step back */
    public static void back(View v) { ready(v); if (v != null) Sounds.play(v.getContext(), UiTones.BACK); }
}
