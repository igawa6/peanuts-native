package dev.peanuts.amiibo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * amiibo as an opt-in (igawa6/peanuts-native#582): a game finds amiibo on Peanuts' stand only when the player turned it on, for every game (Settings ›
 * Users › amiibo, off by default) or for this one (the game's own amiibo switch in its Game options, which comes first). A game started with it on gets
 * PN_AMIIBO=1, and the host's loader gives it dm/peanuts/peanuts_amiibo.c's nfc and nfp; every other game gets hle_sdk16.c's own block, as without amiibo.
 * A change counts from the game's next start. In a file of its own (MODE_MULTI_PROCESS): the launcher writes it, the game's process reads it as it starts.
 */
public final class AmiiboSwitch {
    private AmiiboSwitch() {}

    static final String PREFS = "peanuts_amiibo", ALL = "all";
    /** The rows' words: GameSettings' switch (says) and Settings' row (LibrarySettings) */
    public static final String ON = "Your amiibo on Peanuts' stand, from the next start", OFF = "No amiibo: the game finds none, as before";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS); }
    private static String key(String titleId) { return "t." + titleId.toUpperCase(Locale.ROOT); }

    /** Settings › Users › amiibo: every game, unless its own switch says otherwise. Off by default */
    public static boolean all(Context c) { return prefs(c).getBoolean(ALL, false); }
    public static void all(Context c, boolean on) { prefs(c).edit().putBoolean(ALL, on).apply(); }

    /** The game's own choice; null while it follows Settings */
    public static Boolean game(Context c, String titleId) {
        if (titleId == null) return null;
        SharedPreferences p = prefs(c);
        String k = key(titleId);
        return p.contains(k) ? p.getBoolean(k, false) : null;
    }
    public static void game(Context c, String titleId, boolean on) { if (titleId != null) prefs(c).edit().putBoolean(key(titleId), on).apply(); }

    /**
     * Settings › Users › amiibo pictures: turned on, the app downloads each amiibo's picture from AmiiboAPI on GitHub (raw.githubusercontent.com) when a
     * page shows it, and keeps it (AmiiboPictures). Off by default: nothing is downloaded, and Peanuts' own card shows. The launcher's and the game's
     * process both read it
     */
    public static boolean pictures(Context c) { return prefs(c).getBoolean(PICTURES, false); }
    public static void pictures(Context c, boolean on) { prefs(c).edit().putBoolean(PICTURES, on).apply(); }
    static final String PICTURES = "pictures";

    /**
     * Whether AmiiboPictures may download at all: the pictures are turned on, and amiibo is on somewhere (Settings' row, or a game's own switch). With
     * amiibo off everywhere the app downloads nothing for amiibo, whatever the pictures row says
     */
    public static boolean picturesOn(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(PICTURES, false)) return false;
        if (p.getBoolean(ALL, false)) return true;
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) if (e.getKey().startsWith("t.") && Boolean.TRUE.equals(e.getValue())) return true;
        return false;
    }

    /** Whether this game starts with Peanuts' stand */
    public static boolean on(Context c, String titleId) { Boolean g = game(c, titleId); return g != null ? g : all(c); }

    /**
     * Whether the game's Game options show its amiibo switch: a game that uses amiibo (AmiiboAPI's table lists it, or it was seen opening nfp while amiibo was
     * on for it), or one whose switch was set (so it can be turned off again)
     */
    public static boolean offered(Context c, String titleId) { return titleId != null && (game(c, titleId) != null || AmiiboStand.usesAmiibo(c, titleId)); }

    /** GameActivity's environment: PN_AMIIBO=1 for a game it is on for (a PN_AMIIBO line of the player's own env.txt comes later and wins) */
    public static void env(Context c, String titleId, List<String> env) {
        if (titleId != null && on(c, titleId)) env.add("PN_AMIIBO=1");
    }
}
