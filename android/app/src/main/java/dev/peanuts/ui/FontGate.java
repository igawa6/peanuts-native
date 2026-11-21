package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;

import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.GameEnv;

/**
 * Before Play: a game known to stop at boot without the firmware's shared fonts says so, instead of crashing. Android's own fallback font lacks
 * characters such as U+25A1, and Echoes of Wisdom (dm/games/01008CF01BAAC000/README.md) dereferences a null glyph and crashes on the first frame it
 * draws. With the fonts in the app the game's env is brought up to date first (a game built before they were added has no DM_SHARED_FONT line).
 */
final class FontGate {
    private FontGate() {}

    /** Titles that crashed at boot with the fallback font (reports and README notes); other games run without firmware fonts and are never held back */
    private static final String[] NEED_FONTS = {"01008CF01BAAC000", "01006B601380E000"};

    static boolean needs(String titleId) {
        if (titleId != null) for (String t : NEED_FONTS) if (t.equalsIgnoreCase(titleId)) return true;
        return false;
    }

    /** Brings the game's env up to date for a start and says why the game should not start as it is, null when it can (the control interface's Play reports the sentence) */
    static String refusal(Activity a, String titleId, String name) {
        if (PeanutsPrefs.hasFonts(a)) { GameEnv.ensureFonts(a, titleId); return null; }
        GameEnv.ensureDefaults(a, titleId);   // without the fonts too: a line retired from the game's env is out before this start (ensureFonts does it above)
        return needs(titleId) ? name + " stops at startup with Android's fonts. Add your firmware first (Settings › Console › Firmware), then play." : null;
    }

    /** Runs {@code go} at once when the game can start; else a dialog with "Choose firmware…" and "Play anyway" (which runs it) */
    static void check(Activity a, String titleId, String name, Runnable go) {
        String why = refusal(a, titleId, name);
        if (why == null) { go.run(); return; }
        new AlertDialog.Builder(a).setTitle("Firmware needed")
                .setMessage(why)
                .setPositiveButton("Choose firmware…", (d, w) -> LibrarySettings.pickFirmwareSource(a))
                .setNeutralButton("Play anyway", (d, w) -> go.run())
                .setNegativeButton("Cancel", null).show();
    }
}
