package dev.peanuts;

/**
 * Where the game and its companion belong while a game runs on a phone with two displays (GitHub issue #183). Companion asks each time the game comes to the
 * front (GameActivity.onTopResumedActivityChanged, onStart) and when a display goes away.
 * <p>
 * The game stays on the display it was started on (its home: the clip-on screen with Swap screens on, the built-in one without). Android moves a game away from
 * it without asking: a phone's Recents starts the task it is given on the display Recents runs on (the Retroid Pocket 5's bottom screen), and so does the
 * AYN Thor's "move to screen" in its Recents. The game then goes back home once, when its activity is the root of a task of its own (Swap screens starts it
 * so, dev.peanuts.Screens): a start of it on another display moves that task. Started from Home without Swap screens it shares Home's task, and such a start
 * made a second game activity there (seen on the AYN Thor, 2026-11-20), so it is not moved. If it is not moved, or does not get there (Android refused, or the
 * display is gone), the companion follows the game: it takes the display the game is not on.
 * <p>
 * Plain Java, so the rule is tested on the JVM (ScreenPlaceJvmTest).
 */
final class ScreenPlace {
    /** android.view.Display.INVALID_DISPLAY and DEFAULT_DISPLAY */
    static final int INVALID = -1, DEFAULT = 0;

    /** Nothing to do */
    static final int NOTHING = 0;
    /** Start the game's activity on its home display again */
    static final int MOVE_BACK = 1;
    /** The game stays where it is: forget its home, and close and show the companion again (on the display the game is not on) */
    static final int FOLLOW = 2;
    /** The companion is on the game's display: close it and show it again (on the display the game is not on) */
    static final int RESHOW = 3;
    /** The companion is shown where it is (shown again after the background, or opened again after Android closed it) */
    static final int SHOW = 4;

    private ScreenPlace() {}

    /**
     * @param game the game's display now
     * @param home the display the game was started on, INVALID when it is gone
     * @param triedJustNow the game was sent home a moment ago and is still elsewhere: Android refused the move
     * @param movable the game's activity is the root of a task of its own (Activity.isTaskRoot), which a start on another display moves
     * @param companion this run shows a companion (Companion.allowed)
     * @param screenUp CompanionScreen is up on the built-in display
     * @param shown the display of the companion's Presentation, INVALID: none
     */
    static int decide(int game, int home, boolean triedJustNow, boolean movable, boolean companion, boolean screenUp, int shown) {
        if (home != INVALID && game != home) return triedJustNow || !movable ? FOLLOW : MOVE_BACK;   // a game without a companion goes back home too
        if (!companion) return NOTHING;
        if (screenUp && game == DEFAULT) return RESHOW;   // the game is on the built-in display, where CompanionScreen is
        if (shown != INVALID && shown == game) return RESHOW;   // the Presentation is on the game's display
        return SHOW;
    }
}
