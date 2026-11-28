// PEANUTS: HomeFilters on the JVM (peanuts/tests/test_home_filters.py); prints "ok <n>" or exits 1 naming the first case that differs
package dev.peanuts.ui;

import static dev.peanuts.ui.HomeFilters.ALL;
import static dev.peanuts.ui.HomeFilters.BUILD;
import static dev.peanuts.ui.HomeFilters.BUILDING;
import static dev.peanuts.ui.HomeFilters.NO_PROFILE;
import static dev.peanuts.ui.HomeFilters.READY;

/** Home's filter chips: which show for a library, where a chosen filter goes when its chip goes, and where L/R step */
public final class HomeFiltersJvmTest {
    static int n;

    static void check(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { System.out.println(what + ": got " + got + ", want " + want); System.exit(1); }
    }

    /** The chips as letters in Library's order: A(ll) B(uilding) R(eady) N(eeds a build) U(nsupported); "-" for no chip row */
    static String row(boolean[] on) {
        StringBuilder s = new StringBuilder();
        String letters = "ABRNU";
        for (int f = 0; f < on.length; f++) if (on[f]) s.append(letters.charAt(f));
        return s.length() == 0 ? "-" : s.toString();
    }

    public static void main(String[] args) {
        // building, ready, build needed, not supported yet
        check("every game ready: no row (All 12 and Ready 12 say the same)", row(HomeFilters.chips(0, 12, 0, 0)), "-");
        check("every game needs a build", row(HomeFilters.chips(0, 0, 3, 0)), "-");
        check("every game not supported yet", row(HomeFilters.chips(0, 0, 0, 2)), "-");
        check("an empty library", row(HomeFilters.chips(0, 0, 0, 0)), "-");
        check("ready and build needed: no Not Supported Yet 0", row(HomeFilters.chips(0, 10, 2, 0)), "ARN");
        check("ready and not supported yet: no Build needed 0", row(HomeFilters.chips(0, 10, 0, 1)), "ARU");
        check("all three", row(HomeFilters.chips(0, 1, 1, 1)), "ARNU");
        check("something builds, the rest ready: the row stays with Building", row(HomeFilters.chips(1, 11, 0, 0)), "ABR");
        check("the only game builds: All and Building as before", row(HomeFilters.chips(1, 0, 0, 0)), "AB");
        check("a queue of three", row(HomeFilters.chips(3, 4, 2, 0)), "ABRN");

        // a chosen filter whose chip went: All
        boolean[] readyOnly = HomeFilters.chips(0, 12, 0, 0), mixed = HomeFilters.chips(0, 10, 2, 0);
        check("Build needed chosen, its last game built: All", HomeFilters.shown(BUILD, HomeFilters.chips(0, 12, 0, 0)), ALL);
        check("Ready chosen, still there", HomeFilters.shown(READY, mixed), READY);
        check("Not Supported Yet chosen, none left", HomeFilters.shown(NO_PROFILE, mixed), ALL);
        check("Building chosen, the build ended", HomeFilters.shown(BUILDING, mixed), ALL);
        check("no row: All", HomeFilters.shown(READY, readyOnly), ALL);
        check("an unknown filter: All", HomeFilters.shown(9, mixed), ALL);

        // L/R step through the chips there are, around
        check("R from All skips Building", HomeFilters.step(ALL, mixed, 1), READY);
        check("R from Ready", HomeFilters.step(READY, mixed, 1), BUILD);
        check("R from Build needed skips Not Supported Yet, around to All", HomeFilters.step(BUILD, mixed, 1), ALL);
        check("L from All, around to Build needed", HomeFilters.step(ALL, mixed, -1), BUILD);
        boolean[] full = HomeFilters.chips(2, 1, 1, 1);
        check("R from All with a build", HomeFilters.step(ALL, full, 1), BUILDING);
        check("L from Building", HomeFilters.step(BUILDING, full, -1), ALL);
        check("R from Not Supported Yet", HomeFilters.step(NO_PROFILE, full, 1), ALL);
        check("no row: L/R stay", HomeFilters.step(ALL, readyOnly, 1) + "," + HomeFilters.step(ALL, readyOnly, -1), ALL + "," + ALL);
        check("one build only: R goes to Building and back", HomeFilters.step(HomeFilters.step(ALL, HomeFilters.chips(1, 0, 0, 0), 1), HomeFilters.chips(1, 0, 0, 0), 1), ALL);

        System.out.println("ok " + n);
    }
}
