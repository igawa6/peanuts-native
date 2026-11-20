package dev.peanuts;

/**
 * What a key that reaches CompanionScreen's window does (GitHub issue #183). That window holds the built-in display's focus, so Android hands it Back from that
 * display's navigation bar and, for a moment after its start (before Companion.refocusGame), the controller's keys. Back does what it does in the game (the in-game
 * panel); a controller's key goes to the game; anything else is the activity's own. Plain Java, so the rule is tested on the JVM (ScreenKeysJvmTest); the
 * Android constants below are copied, and peanuts/tests/test_companion_screen.py checks them.
 */
final class ScreenKeys {
    /** The activity handles the key itself */
    static final int OWN = 0;
    /** The game's activity gets the key (GameActivity.dispatchKeyEvent) */
    static final int GAME = 1;
    /** The game's Back (GameActivity.onBackPressed): the release of a Back that was not cancelled */
    static final int BACK = 2;
    /** Taken and dropped: Back's press, or a cancelled Back */
    static final int EAT = 3;

    // android.view.KeyEvent and android.view.InputDevice
    static final int KEYCODE_BACK = 4, ACTION_UP = 1, FLAG_CANCELED = 0x20;
    static final int SOURCE_DPAD = 0x201, SOURCE_GAMEPAD = 0x401, SOURCE_JOYSTICK = 0x1000010;

    private ScreenKeys() {}

    static int route(int keyCode, int source, int action, int flags) {
        if (gamepad(source)) return GAME;   // a controller's Back too: the game reads it as the controller's
        if (keyCode != KEYCODE_BACK) return OWN;
        return action == ACTION_UP && (flags & FLAG_CANCELED) == 0 ? BACK : EAT;
    }

    /** As GameActivity reads a source: a gamepad, a joystick or a d-pad */
    static boolean gamepad(int source) {
        return (source & SOURCE_GAMEPAD) == SOURCE_GAMEPAD || (source & SOURCE_JOYSTICK) == SOURCE_JOYSTICK || (source & SOURCE_DPAD) == SOURCE_DPAD;
    }
}
