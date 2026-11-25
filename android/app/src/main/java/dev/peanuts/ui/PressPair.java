package dev.peanuts.ui;

/**
 * PEANUTS: one button's press and release taken as one (plain Java, JVM-tested: peanuts/tests/test_game_page.py, PressPairJvmTest). A release counts only
 * when this screen saw its press: the same down time, which a key event keeps from its press to its release. A press that began on another screen ends here
 * without acting: the Library's Y opens a game's page on its press, and the page's Y, Play, took that same press's release and started the game (issue #599)
 */
final class PressPair {
    private long down = -1;

    /** A press seen here (its first event, not a repeat) */
    void down(long downTime) { down = downTime; }

    /** A release: true when its press was the last one seen here. Either way the press is over */
    boolean up(long downTime) {
        boolean ours = down >= 0 && down == downTime;
        down = -1;
        return ours;
    }
}
