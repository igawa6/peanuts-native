package dev.nxnative.android;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * PEANUTS: the CRT filter: the present draws the picture as a CRT shows it (dm/gfx/vk_android/vka_present.c crt_copy, DM_VK_CRT): scanlines, and for the CRT looks an
 * aperture-grille mask and a vignette, the last one curved as well. It takes the place of the present's scaling of the window to the screen, so it costs one
 * full-screen pass. Settings › Graphics › Picture holds the default for every game, Game settings › Display › CRT filter each game's own, and the in-game panel's Display tab
 * changes it live (a DM_CTL line, as GameUpscaler sends its choice).
 */
final class CrtFilter {
    private CrtFilter() {}

    /** The choices in the order shown; the index is DM_VK_CRT's value. New choices go at the end: the stored choice is an index into this list */
    static final String[] MODES = {"Off", "Scanlines", "CRT (scanlines, mask, vignette)", "Curved CRT (as CRT, with a curved screen)"};
    static final int OFF = 0;

    /** The default for every game (Settings › Graphics › Picture) */
    static int mode(Context c) { return clamp(Prefs.of(c).getInt("crt_mode", OFF)); }
    static void modeDefault(Context c, int m) { Prefs.of(c).edit().putInt("crt_mode", m).commit(); }
    static boolean ownMode(Context c, String titleId) { return Prefs.of(c).contains(Prefs.gameKey("crt_mode", titleId)); }
    /** The game's own choice, else the default */
    static int mode(Context c, String titleId) { return clamp(Prefs.of(c).getInt(Prefs.gameKey("crt_mode", titleId), mode(c))); }
    /** The game's own choice (null: follow the default), sent to a running game at once */
    static void mode(Context c, String titleId, Integer m) {
        if (m == null) Prefs.of(c).edit().remove(Prefs.gameKey("crt_mode", titleId)).commit(); else Prefs.of(c).edit().putInt(Prefs.gameKey("crt_mode", titleId), m).commit();
        push(c, titleId);
    }
    static String label(int m) { return GameSettings.shortName(MODES[clamp(m)]); }
    private static int clamp(int m) { return m >= 0 && m < MODES.length ? m : OFF; }

    /** The host's switch for a run (env.txt lines come after it and win) */
    static String line(Context c, String titleId) { return "DM_VK_CRT=" + mode(c, titleId); }

    private static void push(Context c, String titleId) {
        File ext = c.getExternalFilesDir(null);   // GameActivity's DM_CTL: <app files>/ctl.txt (only the running game reads it)
        if (ext == null) return;
        try (FileWriter w = new FileWriter(new File(ext, "ctl.txt"), true)) {
            w.write("env " + line(c, titleId) + "\n");
        } catch (IOException e) { Native.note("[RED] CRT filter: the setting could not be sent to the game (" + e.getMessage() + "); it applies at the next start"); }
    }

    /** The in-game panel's rows (Display tab, under its "CRT filter" heading): one choice, applied at once and kept for this game */
    static void rows(Context c, LinearLayout box, String titleId) {
        RadioGroup g = new RadioGroup(c);
        for (int i = 0; i < MODES.length; i++) { RadioButton b = new RadioButton(c); b.setText(MODES[i]); b.setId(3400 + i); g.addView(b); if (i == mode(c, titleId)) b.setChecked(true); }
        g.setOnCheckedChangeListener((gr, id) -> mode(c, titleId, id - 3400));
        box.addView(g);
    }
}
