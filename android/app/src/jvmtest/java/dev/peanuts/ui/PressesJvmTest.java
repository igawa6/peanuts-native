// PEANUTS: Presses on the JVM (peanuts/tests/test_game_page.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import static dev.peanuts.ui.Presses.ACTION_DOWN;
import static dev.peanuts.ui.Presses.ACTION_UP;
import static dev.peanuts.ui.Presses.FLAG_CANCELED;

/**
 * Issue #599 as the window sees it: Library's cover takes Y's press and opens the game's page in the same window, and the keys that follow go to the
 * page, whose Y plays at the release (GamePage.playKey). Home and Page below stand in for the two: the cover acts on a press, the page asks Presses.
 */
public final class PressesJvmTest {
    private static int n;
    private static final int Y = 100, A = 96, L1 = 102;   // KeyEvent.KEYCODE_BUTTON_Y, _A, _L1

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    /** The game's page: Y's release plays when Presses says the page saw its press */
    private static final class Page {
        final Presses presses = new Presses();
        int plays;

        void key(int code, int action, int repeat, long downTime, int flags) {
            if (code == Y && presses.released(code, action, repeat, downTime, flags)) plays++;
        }
        void key(int code, int action, int repeat, long downTime) { key(code, action, repeat, downTime, 0); }
    }

    /** Home: Y's press on a cover opens the page (Library: hold.run() at ACTION_DOWN); from then on the window's keys go to the page */
    private static final class Window {
        Page page;
        final Page shown;
        Window(Page p) { shown = p; }

        void key(int code, int action, int repeat, long downTime, int flags) {
            if (page != null) { page.key(code, action, repeat, downTime, flags); return; }
            if (code == Y && action == ACTION_DOWN && repeat == 0) page = shown;   // the cover's Y: the game's page, in this window
        }
        void key(int code, int action, int repeat, long downTime) { key(code, action, repeat, downTime, 0); }
    }

    public static void main(String[] a) {
        // the issue: a quick tap of Y on a cover opens the page, and its release does not play
        Page p = new Page();
        Window w = new Window(p);
        w.key(Y, ACTION_DOWN, 0, 1000);
        eq(p, w.page);
        w.key(Y, ACTION_UP, 0, 1000);
        eq(0, p.plays);

        // Y pressed again on the page plays, once
        w.key(Y, ACTION_DOWN, 0, 2000);
        eq(0, p.plays);
        w.key(Y, ACTION_UP, 0, 2000);
        eq(1, p.plays);
        w.key(Y, ACTION_UP, 0, 2000);   // a second release of the same press
        eq(1, p.plays);

        // Y held on a cover: the page opens at the press; its repeats and its release on the page do not play
        p = new Page();
        w = new Window(p);
        w.key(Y, ACTION_DOWN, 0, 3000);
        eq(p, w.page);
        for (int r = 1; r <= 20; r++) w.key(Y, ACTION_DOWN, r, 3000);
        w.key(Y, ACTION_UP, 0, 3000);
        eq(0, p.plays);

        // Y held on the page: its repeats change nothing, the release plays once
        w.key(Y, ACTION_DOWN, 0, 4000);
        for (int r = 1; r <= 20; r++) w.key(Y, ACTION_DOWN, r, 4000);
        eq(0, p.plays);
        w.key(Y, ACTION_UP, 0, 4000);
        eq(1, p.plays);

        // a release Android cancels (the window lost the focus while Y was down) does not play
        w.key(Y, ACTION_DOWN, 0, 5000);
        w.key(Y, ACTION_UP, 0, 5000, FLAG_CANCELED);
        eq(1, p.plays);

        // a press the page saw whose release went elsewhere (Back to Home while Y was held) does not arm the next one: the page opened by the next
        // cover press does not play at that press's release
        Presses s = new Presses();
        eq(false, s.released(Y, ACTION_DOWN, 0, 6000, 0));
        eq(false, s.released(Y, ACTION_UP, 0, 7000, 0));   // the release of a press at 7000, which Home took
        eq(false, s.released(Y, ACTION_UP, 0, 6000, 0));   // and the old one is forgotten with it

        // other keys: A's or L1's press does not arm Y, and a key's press is its own
        s = new Presses();
        eq(false, s.released(A, ACTION_DOWN, 0, 8000, 0));
        eq(false, s.released(L1, ACTION_DOWN, 0, 8000, 0));
        eq(false, s.released(Y, ACTION_UP, 0, 8000, 0));
        eq(true, s.released(A, ACTION_UP, 0, 8000, 0));
        eq(true, s.released(L1, ACTION_UP, 0, 8000, 0));

        // Y down, another key pressed and let go meanwhile, then Y up: still Y's own release
        s = new Presses();
        s.released(Y, ACTION_DOWN, 0, 9000, 0);
        s.released(A, ACTION_DOWN, 0, 9100, 0);
        s.released(A, ACTION_UP, 0, 9100, 0);
        eq(true, s.released(Y, ACTION_UP, 0, 9000, 0));

        System.out.println("ok " + n);
    }
}
