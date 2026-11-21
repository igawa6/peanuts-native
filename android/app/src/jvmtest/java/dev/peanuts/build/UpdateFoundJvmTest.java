// PEANUTS: UpdateFound (issue #120) on the JVM: a newer update or new add-ons beside a built game; peanuts/tests/test_update_found.py compiles and runs it
package dev.peanuts.build;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class UpdateFoundJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    private static UpdateFound.Part in(int kind, String id, long v) { return new UpdateFound.Part(kind, id, v, true); }
    private static UpdateFound.Part file(int kind, String id, long v) { return new UpdateFound.Part(kind, id, v, false); }

    public static void main(String[] args) {
        final int U = UpdateFound.UPDATE, D = UpdateFound.DLC;
        List<Long> supported = Arrays.asList(196608L, 262144L);   // 3.0.0 and 4.0.0 as numbers, say
        String dlc1 = "01006F8002327001", dlc2 = "01006F8002327002";

        // nothing new: the .nup holds the update and the add-on found beside it
        check(UpdateFound.of(Arrays.asList(in(U, "01006F8002326800", 196608), in(D, dlc1, 0), file(U, "01006F8002326800", 196608), file(D, dlc1, 0)), supported) == null, "nothing new");
        // a newer update the profile knows
        UpdateFound.Found f = UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 262144)), supported);
        check(f != null && f.update == 262144 && f.dlcs.isEmpty(), "a newer update");
        check(UpdateFound.what(f, "4.0.0").equals("Update 4.0.0 found"), UpdateFound.what(f, "4.0.0"));
        // one the profile does not know, or an older one: not offered
        check(UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 327680)), supported) == null, "an update the profile does not know");
        check(UpdateFound.of(Arrays.asList(in(U, "x", 262144), file(U, "x", 196608)), supported) == null, "an older update");
        check(UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 262144)), Collections.emptyList()) == null, "no profile builds: no update counts");
        // a base-only .nup takes its first update
        check(UpdateFound.of(Collections.singletonList(file(U, "x", 196608)), supported).update == 196608, "a first update");
        // new add-ons, and both
        f = UpdateFound.of(Arrays.asList(in(D, dlc1, 0), file(D, dlc1, 0), file(D, dlc2, 0)), supported);
        check(f != null && f.update == 0 && f.dlcs.equals(Collections.singletonList(dlc2)), "one new add-on");
        check(UpdateFound.what(f, "").equals("An add-on found"), UpdateFound.what(f, ""));
        f = UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 262144), file(D, dlc2, 0), file(D, dlc1, 0)), supported);
        check(f.update == 262144 && f.dlcs.equals(Arrays.asList(dlc1, dlc2)), "an update and two add-ons, in order");
        check(UpdateFound.what(f, "4.0.0").equals("Update 4.0.0 and 2 add-ons found"), UpdateFound.what(f, "4.0.0"));
        check(UpdateFound.of(Arrays.asList(file(D, dlc1, 0), file(D, dlc2, 0)), supported) != null
                && UpdateFound.what(UpdateFound.of(Arrays.asList(file(D, dlc1, 0), file(D, dlc2, 0)), supported), "").equals("2 add-ons found"), "two add-ons");
        // "Not now" remembers these finds; a newer one is another key
        check(!UpdateFound.of(Arrays.asList(file(D, dlc1, 0)), supported).key().equals(UpdateFound.of(Arrays.asList(file(D, dlc1, 0), file(D, dlc2, 0)), supported).key()), "keys");
        check(UpdateFound.ask(f, "Animal Crossing").startsWith("They are beside the game file of Animal Crossing, which"), UpdateFound.ask(f, "Animal Crossing"));

        // the cover's Build skips Import again's confirmation only when the plan takes what the question named (found in review: Reimport.plan takes the
        // newest update file beside the .nup, one the profile does not know too, or the one the player picked on the game's page)
        UpdateFound.Found update = UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 262144)), supported);
        check(UpdateFound.takesWhatItNamed(update, 196608, 262144), "the named update");
        check(!UpdateFound.takesWhatItNamed(update, 196608, 327680), "a newer update the profile does not know");
        check(!UpdateFound.takesWhatItNamed(update, 196608, 196608), "the old one, picked on the game's page");
        check(!UpdateFound.takesWhatItNamed(update, 196608, -1), "no update");
        UpdateFound.Found addon = UpdateFound.of(Arrays.asList(in(U, "x", 196608), file(U, "x", 196608), file(D, dlc1, 0)), supported);
        check(addon.update == 0 && UpdateFound.takesWhatItNamed(addon, 196608, 196608), "an add-on: the update the .nup holds");
        check(!UpdateFound.takesWhatItNamed(addon, 196608, 327680), "an add-on, and an update the question did not name");
        UpdateFound.Found addonOnBase = UpdateFound.of(Collections.singletonList(file(D, dlc1, 0)), supported);
        check(UpdateFound.takesWhatItNamed(addonOnBase, -1, -1), "an add-on for a base-only .nup");
        check(!UpdateFound.takesWhatItNamed(addonOnBase, -1, 327680), "an add-on for a base-only .nup, and an update file beside it");
        check(!UpdateFound.takesWhatItNamed(update, 196608, 0) && !UpdateFound.takesWhatItNamed(null, 196608, 262144), "an update of no version; no question");
        System.out.println("ok");
    }
}
