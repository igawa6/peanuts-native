// PEANUTS: a test of the fork's own classes (where the game and its companion go on a phone with two displays, GitHub issue #183); upstream has no dev.peanuts tests
package dev.peanuts;

/** ScreenPlace.decide on the JVM (no argument; prints "ok" or throws). Display 0 is the built-in one, 4 the clip-on (or the AYN Thor's Screen-2). */
public final class ScreenPlaceJvmTest {
    private static final int I = ScreenPlace.INVALID, BUILT_IN = 0, CLIP_ON = 4;

    private static void check(int got, int want, String what) {
        if (got != want) throw new AssertionError(what + ": got " + got + ", want " + want);
    }

    public static void main(String[] args) {
        // Swap screens: the game was started on the clip-on screen, the companion is CompanionScreen on the built-in one
        check(ScreenPlace.decide(CLIP_ON, CLIP_ON, false, true, true, true, I), ScreenPlace.SHOW, "all in place: shown where it is");
        check(ScreenPlace.decide(CLIP_ON, CLIP_ON, false, true, true, false, I), ScreenPlace.SHOW, "Recents or Home closed CompanionScreen, the game is in front again: opened again");
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, false, true, true, true, I), ScreenPlace.MOVE_BACK, "Recents started the game on the built-in display: it goes back");
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, false, true, true, false, I), ScreenPlace.MOVE_BACK, "the same with CompanionScreen already closed");
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, true, true, true, true, I), ScreenPlace.FOLLOW, "Android refused the move: the companion follows the game");
        check(ScreenPlace.decide(BUILT_IN, I, false, true, true, true, I), ScreenPlace.RESHOW, "the clip-on screen came off: CompanionScreen must not stay under the game");
        check(ScreenPlace.decide(BUILT_IN, I, false, true, true, false, CLIP_ON), ScreenPlace.SHOW, "after that the companion is on the other display");
        // Swap screens off: the game on the built-in display, the companion a Presentation on the clip-on one
        check(ScreenPlace.decide(BUILT_IN, BUILT_IN, false, true, true, false, CLIP_ON), ScreenPlace.SHOW, "all in place (hidden after the background: shown again)");
        check(ScreenPlace.decide(CLIP_ON, BUILT_IN, false, true, true, false, CLIP_ON), ScreenPlace.MOVE_BACK, "Recents on the clip-on screen took the game there: it goes back");
        check(ScreenPlace.decide(CLIP_ON, I, false, true, true, false, CLIP_ON), ScreenPlace.RESHOW, "the game stays on the Presentation's display: the companion moves away");
        // Without Swap screens the game shares Home's task: a start on its display made a second game activity, so the companion follows instead
        check(ScreenPlace.decide(CLIP_ON, BUILT_IN, false, false, true, false, CLIP_ON), ScreenPlace.FOLLOW, "the game in Home's task: not moved, the companion follows");
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, false, false, true, true, I), ScreenPlace.FOLLOW, "the same with Swap screens");
        // No companion in this run: the game still goes back to its display, nothing else is done
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, false, true, false, false, I), ScreenPlace.MOVE_BACK, "no companion: the game still goes back");
        check(ScreenPlace.decide(BUILT_IN, CLIP_ON, true, true, false, false, I), ScreenPlace.FOLLOW, "no companion, the move refused: only its display is forgotten");
        check(ScreenPlace.decide(CLIP_ON, CLIP_ON, false, true, false, false, I), ScreenPlace.NOTHING, "no companion, in place: nothing");
        // One display only
        check(ScreenPlace.decide(BUILT_IN, BUILT_IN, false, true, true, false, I), ScreenPlace.SHOW, "one display: show() finds no second display and does nothing");
        System.out.println("ok");
    }
}
