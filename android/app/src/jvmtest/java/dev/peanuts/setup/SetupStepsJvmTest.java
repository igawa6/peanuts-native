// PEANUTS: SetupSteps on the JVM (peanuts/tests/test_setup_steps.py); prints "ok <n>" or throws
package dev.peanuts.setup;

import dev.peanuts.setup.SetupSteps.State;
import dev.peanuts.setup.SetupSteps.Step;

public final class SetupStepsJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    public static void main(String[] a) {
        eq("[WELCOME, ACCESS, KEYS, FIRMWARE, GAMES, DONE]", SetupSteps.ORDER);
        eq("[ACCESS, KEYS, FIRMWARE, GAMES]", SetupSteps.COUNTED);

        // a fresh install: after the welcome, file access; nothing done, nothing can end yet, the peanut whole
        State st = new State();
        eq(Step.ACCESS, SetupSteps.first(st));
        eq(Step.ACCESS, SetupSteps.next(Step.WELCOME));
        eq(false, SetupSteps.canFinish(st));
        eq(false, SetupSteps.canGoOn(Step.ACCESS, st));
        eq(false, SetupSteps.canGoOn(Step.GAMES, st));
        eq(true, SetupSteps.canGoOn(Step.KEYS, st));
        eq(false, SetupSteps.optional(Step.ACCESS));
        eq(0f, SetupSteps.crack(Step.WELCOME, st));
        eq(0f, SetupSteps.crack(Step.ACCESS, st));

        // file access allowed on its step: done, a quarter of the seam, Continue goes on to the keys
        st.furthest = Step.ACCESS;
        st.access = true;
        eq(true, SetupSteps.canGoOn(Step.ACCESS, st));
        eq(0.25f, SetupSteps.crack(Step.ACCESS, st));
        eq(Step.KEYS, SetupSteps.first(st));
        eq(Step.KEYS, SetupSteps.next(Step.ACCESS));

        // keys added: Firmware next, half the seam; keys left for later instead: Firmware next too, nothing more cracked
        st.furthest = Step.KEYS;
        st.keys = true;
        eq(Step.FIRMWARE, SetupSteps.first(st));
        eq(0.5f, SetupSteps.crack(Step.KEYS, st));
        State later = new State();
        later.access = true;
        later.keysLater = true;
        later.furthest = Step.FIRMWARE;
        eq(Step.FIRMWARE, SetupSteps.first(later));
        eq(true, SetupSteps.left(Step.KEYS, later));
        eq(0.25f, SetupSteps.crack(Step.FIRMWARE, later));
        // keys added after Later: done, not left
        later.keys = true;
        eq(true, SetupSteps.done(Step.KEYS, later));
        eq(false, SetupSteps.left(Step.KEYS, later));

        // firmware read: the games, which cannot be left
        st.furthest = Step.FIRMWARE;
        st.firmware = true;
        eq(Step.GAMES, SetupSteps.first(st));
        eq(false, SetupSteps.optional(Step.GAMES));
        eq(false, SetupSteps.left(Step.GAMES, st));
        eq(0.75f, SetupSteps.crack(Step.FIRMWARE, st));

        // a game folder: the end, the seam run through; open on the end itself
        st.furthest = Step.GAMES;
        st.gameFolders = 2;
        eq(Step.DONE, SetupSteps.first(st));
        eq(true, SetupSteps.canFinish(st));
        eq(true, SetupSteps.canGoOn(Step.GAMES, st));
        eq(1f, SetupSteps.crack(Step.GAMES, st));
        eq(1f, SetupSteps.crack(Step.DONE, new State()));
        // B back to the welcome: the seam stays where the player had it
        eq(1f, SetupSteps.crack(Step.WELCOME, st));

        // what the app had before the setup cracks nothing until the player reaches its step: whole on the welcome, a quarter on file access
        State had = new State();
        had.access = true; had.keys = true; had.firmware = true; had.gameFolders = 1;
        eq(0f, SetupSteps.crack(Step.WELCOME, had));
        eq(0.25f, SetupSteps.crack(Step.ACCESS, had));
        had.furthest = Step.KEYS;
        eq(0.5f, SetupSteps.crack(Step.KEYS, had));
        eq(0.5f, SetupSteps.crack(Step.ACCESS, had));   // back a step: what was reached stays cracked

        // the furthest of two screens
        eq(Step.KEYS, SetupSteps.further(Step.KEYS, Step.ACCESS));
        eq(Step.GAMES, SetupSteps.further(Step.ACCESS, Step.GAMES));
        eq(Step.KEYS, SetupSteps.further(Step.KEYS, null));
        eq(Step.KEYS, SetupSteps.further(null, Step.KEYS));
        eq(Step.WELCOME, SetupSteps.further(null, null));

        // an older install that had a game folder but not setup_done: it goes on where something is missing
        State old = new State();
        old.gameFolders = 1;
        eq(Step.ACCESS, SetupSteps.first(old));
        old.access = true;
        eq(Step.KEYS, SetupSteps.first(old));
        old.keysLater = true; old.firmwareLater = true;
        eq(Step.DONE, SetupSteps.first(old));

        // moving through the screens, and the numbers
        eq(Step.FIRMWARE, SetupSteps.next(Step.KEYS));
        eq(Step.DONE, SetupSteps.next(Step.DONE));
        eq(Step.ACCESS, SetupSteps.previous(Step.KEYS));
        eq(Step.WELCOME, SetupSteps.previous(Step.ACCESS));
        eq(Step.WELCOME, SetupSteps.previous(Step.WELCOME));
        eq(Step.GAMES, SetupSteps.previous(Step.DONE));
        eq(0, SetupSteps.number(Step.WELCOME));
        eq(1, SetupSteps.number(Step.ACCESS));
        eq(4, SetupSteps.number(Step.GAMES));
        eq(Step.GAMES, SetupSteps.of("games"));
        eq(Step.ACCESS, SetupSteps.of("access"));
        eq(null, SetupSteps.of("addons"));   // a key of the setup before this one: it starts again where something is missing
        System.out.println("ok " + n);
    }
}
