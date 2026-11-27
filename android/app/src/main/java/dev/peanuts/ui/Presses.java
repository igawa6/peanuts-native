package dev.peanuts.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The presses a page saw go down, for a key the page acts on at its release (GitHub issue #599). Home opens a game's page at Y's press (Library's cover)
 * and the page plays at Y's release, so the release of the press that opened the page came to the page and started the game, even for a quick tap. A
 * release counts here only when this page also saw its press: the same key and the same down time (a release carries its press's down time). A held
 * key's repeats change nothing, and a cancelled release (the window lost the focus) does nothing. Plain Java, so the rule is tested on the JVM
 * (PressesJvmTest); the Android constants below are copied, and peanuts/tests/test_game_page.py checks them.
 */
final class Presses {
    // android.view.KeyEvent
    static final int ACTION_DOWN = 0, ACTION_UP = 1, FLAG_CANCELED = 0x20;

    private final Map<Integer, Long> down = new HashMap<>();

    /**
     * A key event as KeyEvent has it (getKeyCode, getAction, getRepeatCount, getDownTime, getFlags): true for the release of a press seen here, which the
     * page acts on. A press is noted; a release or a cancel forgets its key
     */
    boolean released(int keyCode, int action, int repeat, long downTime, int flags) {
        if (action == ACTION_DOWN) {
            if (repeat == 0) down.put(keyCode, downTime);
            return false;
        }
        Long seen = down.remove(keyCode);
        return action == ACTION_UP && (flags & FLAG_CANCELED) == 0 && seen != null && seen == downTime;
    }
}
