package dev.peanuts.smooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import java.util.List;

/**
 * Developer view (Settings › Developer): on, the app shows what testers read, as it always did: the toasts about environment lines not taken, a switch nothing
 * reads and an ignored adb command, and the in-game menu's tool words; off, those toasts go to the log only and the in-game menu says the same in plain words
 * (PlayerWords). Every toast a player needs (cheats, the frame rate cap they picked, fonts, the shader library to update) shows either way. One switch. It
 * starts on for an app that was installed before this version came (nothing changes for anyone who has it) and off for a new install, decided once at the
 * first start of this version and kept, so a later update does not turn a new install's switch on. Its own preferences file with its one key: every process
 * of the app may make that first decision (the game's, a frontend starting a game before the launcher ever showed), and the same answer written twice changes
 * nothing; a process never writes "peanuts", which the launcher keeps changing.
 *
 * <p>Each call gives back what the place showed before while Developer view is on.
 */
public final class DevView {
    private DevView() {}

    /** The setting's name, as SettingsSections, SettingsHelp and LibrarySettings spell it (the test holds them to it) */
    public static final String NAME = "Developer view";

    private static final String FILE = "peanuts_devview", KEY = "on";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    /** Whether the testers' toasts and the in-game menu's tool words show; the first call of this version decides it (installedBefore) */
    public static boolean on(Context c) {
        if (c == null) return true;
        SharedPreferences p = prefs(c);
        if (p.contains(KEY)) return p.getBoolean(KEY, true);
        boolean was = installedBefore(c);
        p.edit().putBoolean(KEY, was).commit();
        return was;
    }

    public static void on(Context c, boolean on) { prefs(c).edit().putBoolean(KEY, on).commit(); }

    /** Developer view is off: the toasts go to the log only, the in-game menu uses plain words */
    public static boolean plain(Context c) { return c != null && !on(c); }

    /** Whether this app was there before this version: Android updated it at least once (an install of its own has the same first and last time) */
    static boolean installedBefore(Context c) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return i.lastUpdateTime > i.firstInstallTime;
        } catch (Exception e) {
            return true;   // not known: as it was
        }
    }

    /** The start's toast from its notes (GameActivity, in order): the first; with Developer view off the first a player needs (PlayerWords.forTesters), null for none */
    public static String startToast(Context c, List<String> notes) {
        boolean plain = plain(c);
        for (String n : notes) if (!plain || !PlayerWords.forTesters(n)) return n;
        return null;
    }

    /** A line of the in-game menu (SidePanel, Lsfg) in player words with Developer view off */
    public static String panel(Context c, String s) { return plain(c) ? PlayerWords.panel(s) : s; }

    /** Frame generation's "Now:" line: the host's status in player words with Developer view off, else (or for a status it does not know) the menu's own line */
    public static String frameGen(Context c, String status, String line) {
        if (!plain(c)) return line;
        String w = PlayerWords.frameGen(status);
        return w != null ? w : line;
    }
}
