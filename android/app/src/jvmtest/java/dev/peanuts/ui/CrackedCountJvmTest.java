// PEANUTS: CrackedCount on the JVM (peanuts/tests/test_peanuts_cracked.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.Arrays;

public final class CrackedCountJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    public static void main(String[] a) {
        CrackedCount.State s = new CrackedCount.State();

        // the old count carries over: the taps are upstream's counter, all time
        eq(412, CrackedCount.total(s, 412));
        eq(0, CrackedCount.total(s, -3));

        // a build of a new version: 25, and 1 for every 1,000 shaders it prepared
        eq(25 + 22, CrackedCount.built(s, "0100f2c0115b6000", "v1", 22_852));
        eq(852, s.shaders);   // the rest waits for the next build
        // the same version again (Import again, Rebuild): nothing, whatever it prepared
        eq(0, CrackedCount.built(s, "0100F2C0115B6000", "v1", 22_852));
        eq(852, s.shaders);
        // the next version of the same game counts, with the carried shaders
        eq(25 + 1, CrackedCount.built(s, "0100F2C0115B6000", "v2", 300));
        eq(152, s.shaders);
        // no shaders (a game whose pack came ready): the build alone
        eq(25, CrackedCount.built(s, "01006BB00C6F0000", "v0", 0));
        eq(0, CrackedCount.built(s, "", "v0", 5000));
        eq(0, CrackedCount.built(s, null, "v0", 5000));
        eq(25, CrackedCount.built(s, "0100000000010000", null, -4));   // no version known: counted once as "?"
        eq(0, CrackedCount.built(s, "0100000000010000", "", 0));
        eq(25 + 22 + 26 + 25 + 25, s.points);

        // play time: 5 for every whole hour, minutes carried between sessions
        long points = s.points;
        eq(0, CrackedCount.played(s, 40 * 60_000L));
        eq(5, CrackedCount.played(s, 30 * 60_000L));        // 70 min in all
        eq(10 * 60_000L, s.playMs);
        eq(10, CrackedCount.played(s, 2 * 3_600_000L));
        eq(0, CrackedCount.played(s, 0));
        eq(0, CrackedCount.played(s, -5));
        eq(points + 15, s.points);

        // PEANUTS: play time from the play-time ledger: what its total gained since the last look
        eq(0, CrackedCount.fromLedger(0, 0));
        eq(18 * 60_000L, CrackedCount.fromLedger(3_600_000L, 3_600_000L + 18 * 60_000L));
        eq(0, CrackedCount.fromLedger(5_000_000L, 4_000_000L));   // a smaller ledger (a game's line gone): nothing, and the new start
        // the first look: a fresh install counts the whole ledger; after 0.1.7's own measure, the ledger so far was counted already
        eq(0, CrackedCount.seenAtFirstLook(false, 7_200_000L));
        eq(7_200_000L, CrackedCount.seenAtFirstLook(true, 7_200_000L));
        eq(0, CrackedCount.fromLedger(CrackedCount.seenAtFirstLook(true, 7_200_000L), 7_200_000L));
        eq(2 * 3_600_000L, CrackedCount.fromLedger(CrackedCount.seenAtFirstLook(false, 7_200_000L), 7_200_000L));
        CrackedCount.State ledger = new CrackedCount.State();
        eq(10, CrackedCount.played(ledger, CrackedCount.fromLedger(0, 2 * 3_600_000L + 59 * 60_000L)));   // two whole hours, 59 minutes carried
        eq(5, CrackedCount.played(ledger, CrackedCount.fromLedger(2 * 3_600_000L + 59 * 60_000L, 3 * 3_600_000L)));

        // firsts: a game's first start, the first own Mii, once each
        eq(10, CrackedCount.started(s, "0100F2C0115B6000"));
        eq(0, CrackedCount.started(s, "0100f2c0115b6000"));
        eq(10, CrackedCount.started(s, "01006BB00C6F0000"));
        eq(0, CrackedCount.started(s, null));
        eq(5, CrackedCount.mii(s));
        eq(0, CrackedCount.mii(s));

        // kept as text and read back the same; unreadable text is a fresh state
        CrackedCount.State back = CrackedCount.load(CrackedCount.save(s));
        eq(CrackedCount.save(s), CrackedCount.save(back));
        eq(s.points, back.points);
        eq(s.builds, back.builds);
        eq(s.started, back.started);
        eq(true, back.mii);
        eq(0, CrackedCount.load(null).points);
        eq(0, CrackedCount.load("not;a;state").points);
        eq("0;0;0;0;;", CrackedCount.save(CrackedCount.load("")));

        // milestones: the next one, the bar between the last reached and the next, the four shown
        eq(100, CrackedCount.next(0));
        eq(500, CrackedCount.next(412));
        eq(250, CrackedCount.previous(412));
        eq(0.648f, Math.round(CrackedCount.progress(412) * 1000) / 1000f);
        eq(250, CrackedCount.next(100));
        eq(0, CrackedCount.previous(99));
        eq("[100, 250, 500, 1000]", Arrays.toString(CrackedCount.around(412)));
        eq("[100, 250, 500, 1000]", Arrays.toString(CrackedCount.around(5)));
        eq("[1000, 2500, 5000, 10000]", Arrays.toString(CrackedCount.around(4000)));
        eq("[10000, 25000, 50000, 100000]", Arrays.toString(CrackedCount.around(60_000)));
        eq(200_000, CrackedCount.next(150_000));
        eq(100_000, CrackedCount.previous(150_000));
        eq("[50000, 100000, 200000, 300000]", Arrays.toString(CrackedCount.around(150_000)));
        eq("[100000, 200000, 300000, 400000]", Arrays.toString(CrackedCount.around(250_000)));

        // PEANUTS: counting never fails its caller (BuildService: a finished build stays finished)
        java.util.List<RuntimeException> seen = new java.util.ArrayList<>();
        CrackedCount.never(() -> { throw new IllegalStateException("job.json is not a job"); }, seen::add);
        eq(1, seen.size());
        eq("job.json is not a job", seen.get(0).getMessage());
        CrackedCount.never(() -> { throw new NullPointerException(); }, null);   // nothing to tell: still no throw
        int[] ran = {0};
        CrackedCount.never(() -> ran[0]++, seen::add);
        eq(1, ran[0]);
        eq(1, seen.size());

        System.out.println("ok " + n);
    }
}
