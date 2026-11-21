package dev.peanuts.users;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings › Users' own preferences: the "Who's playing?" setting, and for each game the user who played it last and its "Always X for this game" */
public final class UsersPrefs {
    private UsersPrefs() {}

    static SharedPreferences of(Context c) { return c.getSharedPreferences("peanuts_users", Context.MODE_PRIVATE); }

    public static int whoMode(Context c) { return of(c).getInt("who_plays", WhoPlays.ASK); }
    public static void whoMode(Context c, int mode) { of(c).edit().putInt("who_plays", mode).apply(); }

    /** Who played the game last; null if nobody yet */
    public static String last(Context c, String titleId) { return of(c).getString("last_" + titleId, null); }
    public static void last(Context c, String titleId, String uid) { of(c).edit().putString("last_" + titleId, uid).apply(); }

    /** The game's "Always X" user; null when it asks */
    public static String always(Context c, String titleId) { return of(c).getString("always_" + titleId, null); }
    public static void always(Context c, String titleId, String uid) {
        SharedPreferences.Editor e = of(c).edit();
        if (uid == null) e.remove("always_" + titleId); else e.putString("always_" + titleId, uid);
        e.apply();
    }
}
