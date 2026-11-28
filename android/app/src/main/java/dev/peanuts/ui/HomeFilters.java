package dev.peanuts.ui;

/**
 * Which of Home's filter chips show (Library.render and its L/R shoulder keys): a chip only for a state that has games, and no chip row when one state
 * has them all (All would only repeat its number). The Building chip is as before: it shows while something builds, and then the row shows with it.
 * Plain Java, no Android (HomeFiltersJvmTest, peanuts/tests/test_home_filters.py).
 */
final class HomeFilters {
    private HomeFilters() {}

    /** Library's filters, in its order */
    static final int ALL = 0, BUILDING = 1, READY = 2, BUILD = 3, NO_PROFILE = 4;

    /** For each filter (by the constants above), whether it has a chip; none at all: Home shows All with no chip row */
    static boolean[] chips(int building, int ready, int toBuild, int noProfile) {
        boolean[] on = new boolean[5];
        int states = (ready > 0 ? 1 : 0) + (toBuild > 0 ? 1 : 0) + (noProfile > 0 ? 1 : 0);
        if (building == 0 && states < 2) return on;
        on[ALL] = true;
        on[BUILDING] = building > 0;
        on[READY] = ready > 0;
        on[BUILD] = toBuild > 0;
        on[NO_PROFILE] = noProfile > 0;
        return on;
    }

    /** The filter Home shows: the chosen one while it has a chip, else All */
    static int shown(int filter, boolean[] chips) {
        return filter >= 0 && filter < chips.length && chips[filter] ? filter : ALL;
    }

    /** The filter an L/R shoulder press goes to from the one shown (d: -1 or 1), around the chips there are; the same one when there is no other chip */
    static int step(int filter, boolean[] chips, int d) {
        int n = chips.length;
        for (int i = 1; i < n; i++) {
            int f = ((filter + d * i) % n + n) % n;
            if (chips[f]) return f;
        }
        return filter;
    }
}
