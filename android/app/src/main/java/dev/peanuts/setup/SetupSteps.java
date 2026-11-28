package dev.peanuts.setup;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The first-run setup's steps as data (dev.peanuts.ui.SetupPage draws them): a welcome, four steps (file access, keys, firmware, the game folders, which
 * also take the update and DLC folders) and the end. What makes a step done, which a player may leave for later, where the setup goes on, and how far the
 * peanut has cracked: whole on the welcome, a quarter for each step done that the player has reached, open at the end. File access and a game folder are
 * needed to finish: keys and firmware are needed before the first build, which asks for them then. Plain Java (no android.*):
 * peanuts/tests/test_setup_steps.py runs SetupStepsJvmTest on it.
 */
public final class SetupSteps {
    private SetupSteps() {}

    /** A screen of the setup: its key (kept while the setup is open) and the icon of its explanation on the second screen */
    public enum Step {
        WELCOME("welcome", "peanut"), ACCESS("access", "lock"), KEYS("keys", "key"), FIRMWARE("firmware", "driver"), GAMES("games", "folder"), DONE("done", "peanut");

        public final String key, icon;
        Step(String key, String icon) { this.key = key; this.icon = icon; }
    }

    /** The screens in their order */
    public static final List<Step> ORDER = Collections.unmodifiableList(Arrays.asList(Step.WELCOME, Step.ACCESS, Step.KEYS, Step.FIRMWARE, Step.GAMES, Step.DONE));
    /** The steps that count ("Step 2 of 4"): file access first (the game folders and the firmware are read with it), then the keys, so the first scan of the
     * games has their names and covers */
    public static final List<Step> COUNTED = Collections.unmodifiableList(Arrays.asList(Step.ACCESS, Step.KEYS, Step.FIRMWARE, Step.GAMES));

    /** What is set up now, and what the player left */
    public static final class State {
        public boolean access;                  // Android's All files access
        public boolean keys, firmware;          // prod.keys in the app; the firmware's fonts read
        public int gameFolders, addonFolders;   // the folder lists' sizes
        public boolean keysLater, firmwareLater;   // Later on Keys or Firmware
        public Step furthest = Step.WELCOME;    // the furthest screen the player has reached: the peanut cracks only for a step reached
    }

    /** Whether a step is done (the welcome and the end never are) */
    public static boolean done(Step s, State st) {
        switch (s) {
            case ACCESS: return st.access;
            case KEYS: return st.keys;
            case FIRMWARE: return st.firmware;
            case GAMES: return st.gameFolders > 0;
            default: return false;
        }
    }

    /** Whether a step that is not done was left with Later (Keys, Firmware). File access and the games cannot be left */
    public static boolean left(Step s, State st) {
        if (done(s, st)) return false;
        return s == Step.KEYS ? st.keysLater : s == Step.FIRMWARE && st.firmwareLater;
    }

    /** Whether the player may leave a step without doing it (its Later button) */
    public static boolean optional(Step s) { return s == Step.KEYS || s == Step.FIRMWARE; }

    /** Where the setup goes on after the welcome: the first step that is neither done nor left, the end when every one is */
    public static Step first(State st) {
        for (Step s : COUNTED) if (!done(s, st) && !left(s, st)) return s;
        return Step.DONE;
    }

    /** The screen after s (the end after the last), and the one before (the welcome before the first) */
    public static Step next(Step s) { int i = ORDER.indexOf(s); return ORDER.get(Math.min(i + 1, ORDER.size() - 1)); }
    public static Step previous(Step s) { int i = ORDER.indexOf(s); return ORDER.get(Math.max(i - 1, 0)); }

    /** "Step 2 of 4": the step's number, 0 for the welcome and the end */
    public static int number(Step s) { return COUNTED.indexOf(s) + 1; }

    /** Whether the setup can end (Show my games, and Continue on the games): a game folder is set */
    public static boolean canFinish(State st) { return st.gameFolders > 0; }

    /** Whether a step's Continue may go on: file access needs to be allowed and the games a folder; Keys and Firmware may be passed by Later (marked left) */
    public static boolean canGoOn(Step s, State st) {
        if (s == Step.ACCESS) return st.access;
        return s != Step.GAMES || st.gameFolders > 0;
    }

    /**
     * How far the peanut's seam has run on a screen: a quarter for each step done that the player has reached (the furthest screen so far, or this one), so
     * the peanut is whole on the welcome whatever the app had before, and cracks as the player gets through; a step left for later cracks nothing. 1 on the
     * end, where it opens
     */
    public static float crack(Step s, State st) {
        if (s == Step.DONE) return 1f;
        int reached = Math.max(s.ordinal(), st.furthest == null ? 0 : st.furthest.ordinal());
        int n = 0;
        for (Step c : COUNTED) if (c.ordinal() <= reached && done(c, st)) n++;
        return n / (float) COUNTED.size();
    }

    /** The further of two screens (the one a player has reached), a null as the welcome */
    public static Step further(Step a, Step b) {
        if (a == null) return b == null ? Step.WELCOME : b;
        return b == null || a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The step of a key (kept in the app's preferences while the setup is open), null for an unknown one (a key of an older setup) */
    public static Step of(String key) {
        for (Step s : ORDER) if (s.key.equals(key)) return s;
        return null;
    }
}
