// PEANUTS: PressPair on the JVM (peanuts/tests/test_game_page.py); prints "ok <n>" or throws
package dev.peanuts.ui;

public final class PressPairJvmTest {
    private static int n;

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        PressPair y = new PressPair();
        // issue #599: the Library's Y opened the page on its press; the page sees only the release
        yes(!y.up(1000), "a release whose press this screen did not see does nothing");
        // a press and its release on the page: Play
        y.down(2000);
        yes(y.up(2000), "the release of a press seen here counts");
        yes(!y.up(2000), "once: a second release of the same press does not");
        // a press seen here, then the release of another press (the event of an earlier one, delivered late)
        y.down(3000);
        yes(!y.up(2500), "a release of another press does not count");
        yes(!y.up(3000), "and the press seen is over after any release");
        // a new press replaces one whose release went elsewhere
        y.down(4000);
        y.down(5000);
        yes(!y.up(4000), "the older press's release does not count once a newer press was seen");
        y.down(6000);
        yes(y.up(6000), "the newest press's does");
        System.out.println("ok " + n);
    }
}
