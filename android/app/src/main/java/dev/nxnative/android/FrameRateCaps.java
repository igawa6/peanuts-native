package dev.nxnative.android;

/** Frame limits for profiles checked with higher presentation targets. */
final class FrameRateCaps {
    static final int GAME_OWNED = -2;
    private FrameRateCaps() {}

    static int[] menu() { return new int[] {GAME_OWNED, 120, 90, 0, 45, 40, 30, 20}; }
    static boolean contains(int[] values, int value) { for (int v : values) if (v == value) return true; return false; }

    static int[] rates(int maximum) {
        if (maximum >= 120) return new int[] {GAME_OWNED, 120, 90, 0, 45, 40, 30, 20};
        if (maximum >= 90) return new int[] {GAME_OWNED, 90, 0, 45, 40, 30, 20};
        return new int[] {GAME_OWNED, 0, 45, 40, 30, 20};
    }

    static int[] rates(int maximum, int minimum) {
        int[] all = rates(maximum);
        if (minimum <= 20) return all;
        int count = 0;
        for (int v : all) if (v <= 0 || v >= minimum) count++;
        int[] allowed = new int[count]; int i = 0;
        for (int v : all) if (v <= 0 || v >= minimum) allowed[i++] = v;
        return allowed;
    }

    // Saved value 0 still means 60 fps; higher limits use their actual rate.
    static int normalize(int value, int maximum) {
        if (value == GAME_OWNED) return GAME_OWNED;
        if (maximum >= 120 && value >= 120) return 120;
        if (maximum >= 90 && value >= 90) return 90;
        return value <= 0 || value >= 60 ? 0 : value >= 45 ? 45 : value >= 40 ? 40 : value >= 30 ? 30 : 20;
    }

    // PEANUTS begin: which caps a game offers, from its profile alone (Prefs, GameSettings, SidePanel, the stats page and adb all ask here; fps cap audit 2026-11-24)

    /** The frame limit list (the host's limiter, DM_FPS_LIMIT): paced games, a profile's fps_limit, and opt-in present pacing (fps_limit_present with no fps_limit) */
    static boolean limited(Profile p) { return p != null && (p.decoupled || p.fpsLimit > 0 || p.optInPresentRate()); }
    /** Which list a game was on before 0.1.8, when opt-in present pacing also needed fps_limit_max above 60: the list a saved cap with no list marker was written for (Prefs.migrateFpsCap) */
    static boolean legacyLimited(Profile p) { return p != null && (p.decoupled || p.fpsLimit > 0 || p.presentOnly && p.fpsLimit == 0 && p.fpsLimitMax > 60 && !p.decoupled); }
    /** A framework of whole steps a frame that Hold 60 fps keeps at one (fps_steps, or a frame step without an elapsed clock) */
    static boolean steps(Profile p) { return p != null && p.steps && !p.elapsedFrameStep && !p.fpsLocker.contains(",time_ratio_f32,") && !p.fpsLockerPlan.contains(",clock,-,1"); }
    /** The games that started Game owned until the player picked a rate before 0.1.8's release (opt-in present pacing, fps_owned_default): they start at defaultLimit
     *  now, and Game owned stays a choice (the owner's call for 0.1.8, 2026-11-24) */
    static boolean ownedBefore(Profile p) { return p != null && (p.optInPresentRate() || p.ownedByDefault); }
    /** Such a game's frame limit with no pick of the player's (a stored value): 60 fps (0) when its caps have it, else 30, else Game owned. 60 is on every display */
    static int defaultLimit(Profile p) {
        int[] caps = paceCaps(p);
        return contains(caps, 0) ? 0 : contains(caps, 30) ? 30 : GAME_OWNED;
    }
    /** The highest frame limit the profile allows: fps_limit_max for a limited game that is not paced, else 60 */
    static int paceMax(Profile p) { return p != null && !p.decoupled && (p.fpsLimit > 0 || p.optInPresentRate()) ? p.fpsLimitMax : 60; }
    /** The frame limits the profile allows (stored values: 0 = 60 fps, GAME_OWNED), whatever the display */
    static int[] paceCaps(Profile p) {
        if (p != null && !p.fpsModes.isEmpty()) return p.fpsModeCaps();
        // PEANUTS: Whole-step games expose only their supported divisors.
        return p != null && (steps(p) || p.fpsLocker.contains(",vsync_u32,")) ? new int[] {GAME_OWNED, 0, 30, 20} : rates(paceMax(p), p == null ? 20 : p.fpsLimitMin);
    }

    /** The display's highest refresh rate as the menus use it: never below 60 (every display shows 60 and the caps under it) */
    static int screenMax(int displayHz) { return Math.max(60, displayHz); }
    /** The caps a display can show: those above 60 only up to its highest refresh rate (Display.getSupportedModes), the rest as they are */
    static int[] onScreen(int[] caps, int displayHz) {
        int max = screenMax(displayHz), n = 0;
        for (int v : caps) if (v <= 60 || v <= max) n++;
        int[] out = new int[n]; int i = 0;
        for (int v : caps) if (v <= 60 || v <= max) out[i++] = v;
        return out;
    }
    /** The frame limit list's caps a game offers on a display */
    static int[] paceOffered(Profile p, int displayHz) { return onScreen(paceCaps(p), displayHz); }
    /** Whether the profile allows a cap the display cannot show (the menus then say why it is greyed) */
    static boolean hidesAbove(int[] caps, int displayHz) { for (int v : caps) if (v > screenMax(displayHz)) return true; return false; }
    /** Whether a list offers anything above 60 (the "Above 60 fps is experimental" note) */
    static boolean above60(int[] caps) { for (int v : caps) if (v > 60) return true; return false; }
    /** The limit a stored frame limit runs at on a display: a saved 90 or 120 stays saved, the game runs at the display's highest rate (GAME_OWNED and 0 as they are) */
    static int onScreen(int stored, int displayHz) { return stored > 60 && stored > screenMax(displayHz) ? (screenMax(displayHz) > 60 ? screenMax(displayHz) : 0) : stored; }
    /** The frames a second a stored frame limit holds the host's limiter to on a display (DM_FPS_LIMIT, Native.fpsLimitSet): 60 for the stored 0 */
    static int limitRate(int stored, int displayHz) { int v = onScreen(stored, displayHz); return v > 0 ? v : 60; }

    /** The fps_target list in menu order (Game settings, the in-game panel, the stats page): 0 is Game owned there */
    static int[] targetMenu() { return new int[] {0, 120, 90, 60, 45, 40, 30, 20, 15}; }
    /** The host's rates mask (nxhost_fps_cap_status, hle_fpslock.c dm_fps_lock_rates): bit 0 15, 1 20, 2 30, 3 40, 4 45, 5 60 */
    static int rateBit(int fps) { return fps == 15 ? 0 : fps == 20 ? 1 : fps == 30 ? 2 : fps == 40 ? 3 : fps == 45 ? 4 : fps == 60 ? 5 : -1; }
    /**
     * The fps_target list's caps a game offers (0 = Game owned, always). hostRates: the rates the host said it holds (the mask above, live while the game runs, else the last
     * run's), -1 when no run has said yet. Without an answer: 60, 30, 20 and 15, and 40 and 45 when the profile's engine rows hold any rate (fps_rates); with one: the rates it
     * holds (0: none, the game picks its own rate). Nothing above 60 on this list: the host's target takes 15 to 60 only (hle_fpslock.c target_ok). A profile's fps_modes
     * restricts the list as it does the frame limit list (DRAGON BALL: Sparking! ZERO: 60 only, its battle step is fixed at 1/60 s).
     */
    static int[] targetCaps(Profile p, int hostRates) {
        int[] menu = targetMenu();
        boolean[] on = new boolean[menu.length];
        int n = 0;
        for (int i = 0; i < menu.length; i++) {
            int v = menu[i];
            boolean ok;
            if (v == 0) ok = true;
            else if (v > 60) ok = false;
            else if (hostRates >= 0) ok = (hostRates & 1 << rateBit(v)) != 0;
            else ok = v == 60 || v == 30 || v == 20 || v == 15 || (v == 40 || v == 45) && p != null && ("," + p.fpsRates + ",").contains("," + v + ",");
            if (ok && v > 0 && p != null && !p.fpsModes.isEmpty()) ok = ("," + p.fpsModes + ",").contains("," + v + ",");
            if (ok) { on[i] = true; n++; }
        }
        int[] out = new int[n]; int k = 0;
        for (int i = 0; i < menu.length; i++) if (on[i]) out[k++] = menu[i];
        return out;
    }
    /** A stored fps_target cap as one the list offers: -1 (Default) and 0 (Game owned) as they are, else the highest offered at or below it, else the lowest offered; a list
     *  of Game owned alone (a game that picks its own rate) leaves it as it is, the menus then show Game owned (Prefs.ownRate) */
    static int targetCap(int stored, int[] offered) {
        if (stored <= 0) return stored;
        int best = 0, low = 0;
        for (int v : offered) { if (v > 0 && v <= stored && v > best) best = v; if (v > 0 && (low == 0 || v < low)) low = v; }
        return best > 0 ? best : low > 0 ? low : stored;
    }
    /** A game's fps_target cap with no choice of its own and no recommended one: the main screen's when the player set one (null: never set), else the profile's own
     *  fps_target (-1: not passed, the host starts at it; EA SPORTS FC 27's 30) when it has one, else the main screen's default of 60 (fps cap audit B3) */
    static int targetDefault(Profile p, Integer mainScreen) { return mainScreen != null ? mainScreen : p != null && p.fpsTarget > 0 ? -1 : 60; }

    /**
     * A saved per-game cap moved between the two lists (their values differ: on the fps_target list -1 is Default, 0 Game owned, else the rate; on the frame limit list
     * GAME_OWNED is Game owned, 0 is 60 fps, else the rate), so the player's choice keeps its meaning. null: remove it (back to the game's default). Same list: as it is.
     */
    static Integer migrate(int stored, boolean wasLimited, boolean nowLimited) {
        if (wasLimited == nowLimited) return stored;
        if (nowLimited) {   // fps_target list -> frame limit list
            if (stored < 0) return null;   // Default: the frame limit list's own default (Game owned for opt-in pacing)
            if (stored == 0) return GAME_OWNED;
            if (stored >= 60) return 0;   // the fps_target list held nothing above 60 (Prefs.capOf)
            return stored >= 45 ? 45 : stored >= 40 ? 40 : stored >= 30 ? 30 : 20;
        }
        // frame limit list -> fps_target list
        if (stored == GAME_OWNED) return 0;
        if (stored == 0 || stored >= 60 || stored < 0) return 60;
        return stored;
    }
    /** PEANUTS: whether a SharedPreferences file's text (null: not read) holds key as the int v (its line: {@code <int name="key" value="v" />}); GameActivity.noteCapPick */
    static boolean savedIn(String xml, String key, int v) {
        String name = key.replace("&", "&amp;").replace("\"", "&quot;");
        return xml != null && java.util.regex.Pattern.compile("<int\\s+name=\"" + java.util.regex.Pattern.quote(name) + "\"\\s+value=\"" + v + "\"\\s*/>").matcher(xml).find();
    }
    /** What Prefs.migrateFpsCap writes: the list marker, and the cap (null: removed) when one is saved */
    static final class Saved {
        final String marker; final Integer cap;
        Saved(String marker, Integer cap) { this.marker = marker; this.cap = cap; }
    }
    /** The game's list as its marker names it (Prefs' fps_cap_list@<title ID>) */
    static String list(Profile p) { return limited(p) ? "limit" : "target"; }
    /**
     * Prefs.migrateFpsCap's rule, once per game: marker is the list the saved cap was written for (null: none yet, written before the marker existed, so for the list of
     * the rule before 0.1.8, legacyLimited), stored the saved cap (null: none). null: nothing to write (the marker already names the game's list, or nothing is saved and
     * no marker needs moving); else the marker to write and, with a saved cap, the cap that keeps its meaning on the game's list now
     */
    static Saved migrateSaved(Profile p, String marker, Integer stored) {
        if (p == null || list(p).equals(marker)) return null;
        if (stored == null) return marker == null ? null : new Saved(list(p), null);
        return new Saved(list(p), migrate(stored, marker != null ? marker.equals("limit") : legacyLimited(p), limited(p)));
    }
    // PEANUTS end
}
