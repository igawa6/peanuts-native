package dev.peanuts.ui;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Peanuts cracked, the count on About (it replaces upstream's "Bananas Popped"), as rules over a small state. The total is the peanuts tapped on the
 * background (upstream's own counter, Prefs.bananasPopped, so the old count carries over and every tap still counts there) plus the points here:
 * <ul>
 * <li>25 for the build of a game version not built before, and 1 for every 1,000 shaders that build prepared. The same version built again (Import
 * again, Rebuild) adds nothing: its shaders are not new.</li>
 * <li>5 for every hour played, the minutes carried from session to session.</li>
 * <li>10 for a game's first start, 5 for the player's first own Mii.</li>
 * </ul>
 * Plain Java, no Android: Cracked keeps the state in the app's preferences, peanuts/tests/test_peanuts_cracked.py runs CrackedCountJvmTest on it.
 */
public final class CrackedCount {
    private CrackedCount() {}

    public static final int BUILD = 25, PER_THOUSAND_SHADERS = 1, PER_HOUR = 5, FIRST_START = 10, FIRST_MII = 5;

    /**
     * Counts with no risk to the caller: a RuntimeException in count (a job file that is not what it should be, the preferences, reflection into upstream)
     * goes to failed and no further. BuildService counts a finished build this way, so the count can never turn it into "Build failed"
     */
    public static void never(Runnable count, java.util.function.Consumer<RuntimeException> failed) {
        try {
            count.run();
        } catch (RuntimeException e) {
            if (failed != null) failed.accept(e);
        }
    }
    static final long HOUR_MS = 3_600_000L;
    /** The milestones the second display shows; after the last, every further 100,000 */
    static final long[] MILESTONES = {100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000};

    /** What has been counted so far (besides the taps, which upstream counts) */
    public static final class State {
        public long points;                                      // the points of builds, shaders, play time and firsts
        public final Set<String> builds = new LinkedHashSet<>(); // "<title ID>@<version>" of every version counted
        public long shaders;                                     // shaders not yet worth a point (under 1,000)
        public long playMs;                                      // play time not yet worth points (under an hour)
        public final Set<String> started = new LinkedHashSet<>();// title IDs started once
        public boolean mii;                                      // the first own Mii was counted
    }

    /** A build of titleId at version ended well, having prepared shaders shaders: the points it is worth (0: that version was counted before) */
    public static long built(State s, String titleId, String version, long shaders) {
        if (titleId == null || titleId.isEmpty()) return 0;
        String key = titleId.toUpperCase(Locale.ROOT) + "@" + (version == null || version.isEmpty() ? "?" : version);
        if (!s.builds.add(key)) return 0;
        long all = s.shaders + Math.max(0, shaders);
        long add = BUILD + all / 1000 * PER_THOUSAND_SHADERS;
        s.shaders = all % 1000;
        s.points += add;
        return add;
    }

    // ---- Play time: from the play-time ledger ----

    /**
     * The play time the ledger's total gained since the last look (seen): the ledger (dev.peanuts.launch.PlayLedger) only adds, so this is the time played
     * meanwhile; never below 0 (a ledger made smaller, a game's line gone, counts nothing and is the new start)
     */
    public static long fromLedger(long seen, long total) { return Math.max(0, total - seen); }

    /**
     * What the first look at the ledger takes as counted already: nothing on an install that never measured play time, so all the ledger holds counts;
     * the whole ledger after 0.1.7, whose Peanuts cracked measured each run itself (measuredBefore), so those hours do not count twice
     */
    public static long seenAtFirstLook(boolean measuredBefore, long total) { return measuredBefore ? Math.max(0, total) : 0; }

    /** A game was played for ms: 5 points for every whole hour, the rest carried */
    public static long played(State s, long ms) {
        if (ms <= 0) return 0;
        long all = s.playMs + ms;
        long add = all / HOUR_MS * PER_HOUR;
        s.playMs = all % HOUR_MS;
        s.points += add;
        return add;
    }

    /** A game started: 10 points the first time for this title ID */
    public static long started(State s, String titleId) {
        if (titleId == null || titleId.isEmpty() || !s.started.add(titleId.toUpperCase(Locale.ROOT))) return 0;
        s.points += FIRST_START;
        return FIRST_START;
    }

    /** The player saved a Mii of their own: 5 points the first time */
    public static long mii(State s) {
        if (s.mii) return 0;
        s.mii = true;
        s.points += FIRST_MII;
        return FIRST_MII;
    }

    /** The total with the taps on the background */
    public static long total(State s, long taps) { return Math.max(0, taps) + s.points; }

    /** The next milestone above total */
    public static long next(long total) {
        for (long m : MILESTONES) if (m > total) return m;
        long last = MILESTONES[MILESTONES.length - 1];
        return (total / last + 1) * last;
    }

    /** The milestone below or at total that the bar starts from (0 before the first) */
    public static long previous(long total) {
        long p = 0;
        for (long m : MILESTONES) if (m <= total) p = m;
        long last = MILESTONES[MILESTONES.length - 1];
        return total >= last ? total / last * last : p;
    }

    /** How far total is from previous() to next(), 0 to 1 */
    public static float progress(long total) {
        long a = previous(total), b = next(total);
        return b > a ? Math.min(1f, Math.max(0f, (total - a) / (float) (b - a))) : 1f;
    }

    /** The four milestones the second display shows around total: the two last reached (or the first ones) and the next ones */
    public static long[] around(long total) {
        int i = 0;
        while (i < MILESTONES.length && MILESTONES[i] <= total) i++;
        if (i >= MILESTONES.length) { long p = previous(total), n = next(total); return new long[] {p > MILESTONES[MILESTONES.length - 1] ? p - 100_000 : MILESTONES[MILESTONES.length - 2], p, n, n + 100_000}; }
        int from = Math.max(0, Math.min(i - 2, MILESTONES.length - 4));
        return new long[] {MILESTONES[from], MILESTONES[from + 1], MILESTONES[from + 2], MILESTONES[from + 3]};
    }

    // ---- The state as text, for the preferences ----

    /** One line: points, shaders, playMs, mii, then the builds and the starts, each list joined by "," */
    public static String save(State s) {
        return s.points + ";" + s.shaders + ";" + s.playMs + ";" + (s.mii ? 1 : 0) + ";" + String.join(",", s.builds) + ";" + String.join(",", s.started);
    }

    /** The state save() wrote; a fresh one for null or text it cannot read */
    public static State load(String text) {
        State s = new State();
        if (text == null || text.isEmpty()) return s;
        String[] f = text.split(";", -1);
        try {
            s.points = Long.parseLong(f[0]);
            s.shaders = Long.parseLong(f[1]);
            s.playMs = Long.parseLong(f[2]);
            s.mii = "1".equals(f[3]);
            if (f.length > 4) for (String b : f[4].split(",")) if (!b.isEmpty()) s.builds.add(b);
            if (f.length > 5) for (String t : f[5].split(",")) if (!t.isEmpty()) s.started.add(t);
        } catch (RuntimeException e) {
            return new State();
        }
        return s;
    }
}
