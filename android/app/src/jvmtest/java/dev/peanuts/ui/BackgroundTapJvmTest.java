// PEANUTS: BackgroundTap on the JVM (peanuts/tests/test_bottom_screen_peanuts.py); prints "ok <n>" or throws
package dev.peanuts.ui;

public final class BackgroundTapJvmTest {
    private static int n;
    private static final float SLOP = 24f;
    private static final long LONG = 400;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    public static void main(String[] a) {
        BackgroundTap t = new BackgroundTap();

        // a short press on the background is a tap, once
        t.down(100, 200, true);
        eq(true, t.up(102, 201, SLOP, 80, LONG));
        eq(false, t.up(102, 201, SLOP, 80, LONG));

        // a press on a card or a button never pops
        t.down(100, 200, false);
        eq(false, t.up(100, 200, SLOP, 80, LONG));

        // a drag (scrolling the page) is not a tap, even when it comes back to where it started
        t.down(100, 200, true);
        t.move(100, 260, SLOP);
        t.move(100, 200, SLOP);
        eq(false, t.up(100, 200, SLOP, 300, LONG));

        // a lift past the slop with no move between
        t.down(100, 200, true);
        eq(false, t.up(160, 200, SLOP, 80, LONG));

        // within the slop it is still a tap
        t.down(100, 200, true);
        t.move(100 + SLOP, 200 - SLOP, SLOP);
        eq(true, t.up(100, 200, SLOP, 120, LONG));

        // a long press is not a tap
        t.down(100, 200, true);
        eq(false, t.up(100, 200, SLOP, LONG, LONG));

        // a cancel (a second finger, the system took the gesture) ends it
        t.down(100, 200, true);
        t.cancel();
        eq(false, t.up(100, 200, SLOP, 80, LONG));

        System.out.println("ok " + n);
    }
}
