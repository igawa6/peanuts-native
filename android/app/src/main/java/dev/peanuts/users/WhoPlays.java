package dev.peanuts.users;

import java.util.List;

/**
 * "Who's playing?" (Settings › Users), with two or more users. When the app opens, the player picks who plays: that user is the one games start with (upstream's
 * preselected user, DM_USER), and every game started in the app plays as them until another is picked (the picture at Home's top right switches). A game
 * started without the app, from a home screen shortcut or an emulator frontend, asks for itself, the user who played that game last picked already; "Always X
 * for this game" skips that question for the game. "Don't ask" asks neither: games start with the user games start with. A pick for one game reaches the host
 * through {@link #EXTRA} (GameActivity). The rules themselves have no Android classes: UserPicturesJvmTest checks them.
 */
public final class WhoPlays {
    private WhoPlays() {}

    /** The setting: ask when the app opens and when a game starts without it (the default), or never. "Always X" when the app opens is Don't ask */
    public static final int ASK = 0, DONT_ASK = 1;
    /** The launch intent's extra that names the picked user (32 hex digits) */
    public static final String EXTRA = "dev.peanuts.user";

    /** What happens at a start: ask (with preselected first) or start with uid */
    public static final class Decision {
        public final boolean ask; public final String uid;
        Decision(boolean ask, String uid) { this.ask = ask; this.uid = uid; }
    }

    /** When the app opens: ask (the user playing now picked) with two or more users unless the setting says not to; else go on as the user playing now */
    public static Decision atAppStart(List<String> users, String def, int mode) {
        String d = users.contains(def) ? def : users.isEmpty() ? def : users.get(0);
        return new Decision(users.size() > 1 && mode == ASK, d);
    }

    /**
     * A game's start. users: the users' IDs in their order; def: the user games start with (the one playing now); mode: the setting; always: this game's
     * "Always X" user or null; last: who played this game last, or null; outside: started without the app (a shortcut, a frontend). A start in the app plays as
     * the user playing now, without a question
     */
    public static Decision decide(List<String> users, String def, int mode, String always, String last, boolean outside) {
        String d = users.contains(def) ? def : users.isEmpty() ? def : users.get(0);
        if (users.size() < 2 || !outside) return new Decision(false, d);
        if (always != null && users.contains(always)) return new Decision(false, always);
        if (mode == DONT_ASK) return new Decision(false, d);
        return new Decision(true, last != null && users.contains(last) ? last : d);
    }

    /** The user a start names (GameActivity's hook): the extra when it is a user ID, else def */
    public static String uid(String extra, String def) { return extra != null && extra.matches("[0-9a-f]{32}") ? extra : def; }
}
