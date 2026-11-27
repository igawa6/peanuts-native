// PEANUTS: Smooth Experience's items and Developer view's words on the JVM (peanuts/tests/test_smooth_experience.py); prints "ok <n>" or throws
package dev.peanuts.smooth;

public final class SmoothJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        // ---- Smooth Experience's items ----
        eq("Smooth Experience", SmoothItems.NAME);
        eq(3, SmoothItems.ALL.size());
        String keys = "";
        for (SmoothItems.Item it : SmoothItems.ALL) {
            keys += it.key + " ";
            yes(!it.title.isEmpty() && !it.line.isEmpty(), it.key + " has a title and a line");
            yes(it.line.endsWith("."), it.key + "'s line is a sentence");
            yes(SmoothItems.of(it.key) == it, it.key + " found by its key");
        }
        eq("start_log warm skip ", keys);
        yes(SmoothItems.of("nope") == null, "an unknown key");
        yes(SmoothItems.rowLine(false, 3).startsWith("Off."), "off says so");
        yes(SmoothItems.rowLine(true, 3).startsWith("On. A calm start"), "all picked");
        eq("On. 2 of 3 parts below picked.", SmoothItems.rowLine(true, 2));
        yes(SmoothItems.rowLine(true, 0).contains("nothing below is picked"), "none picked");
        eq("Start screen", SmoothItems.of(SmoothItems.START_LOG).title);   // as Settings spells the rows (LibrarySettings, SettingsSections)
        eq("Preparing graphics notice", SmoothItems.of(SmoothItems.WARM).title);
        eq("Graphics driver notice", SmoothItems.of(SmoothItems.SKIP).title);

        // ---- The start's notes: a switch nothing reads is for testers, a player's own setting is not ----
        yes(PlayerWords.forTesters("[RED] DM_VK_PACE_LOGG (env.txt) is not a switch of this build: nothing reads it; did you mean DM_VK_PACE_LOG? (docs/env_switches.tsv lists the switches)"), "an unknown switch");
        yes(PlayerWords.forTesters("[nxlaunch] DM_TEST_CRASH not taken: the test app only"), "DM_TEST_CRASH");
        yes(!PlayerWords.forTesters("[nxlaunch] Cheats: no cheat file for build 0123 and none imported"), "a cheat note is the player's");
        yes(!PlayerWords.forTesters("[nxlaunch] frame rate: the build has no complete 60 variant (pack.tsv variant row and 60/main.patched.bin): started Game owned"), "the cap they picked");
        yes(!PlayerWords.forTesters("[nxlaunch] the button-icon font is not placed (no space): button icons in game text draw as '?'"), "a font");
        yes(!PlayerWords.forTesters(null), "no note");

        // ---- The in-game menu ----
        eq("Applies at the next start.", PlayerWords.panel("Applies at the next start (this run takes no live changes: DM_CTL is off)."));
        eq("This version of the app has no frame rate cap.", PlayerWords.panel("This build has no frame rate cap."));
        eq("Not in this version of the app.", PlayerWords.panel("Not in this build (built with -PnxLsfg=false)."));
        eq("Mode: performance.", PlayerWords.panel("Mode: performance (the only shader set Eden's LSFG uses)"));
        eq("Now 60 fps, capped at 60.", PlayerWords.panel("Now 60 fps, capped at 60."));   // a line it has no words for stays as it is
        eq(null, PlayerWords.panel(null));

        // frame generation's state (Native.frameGenStatus: "<state> <reason>", vka_framegen.c)
        eq("Now: adding frames.", PlayerWords.frameGen("1 generating"));
        eq("Now: waiting for a steady frame rate.", PlayerWords.frameGen("2 on, waiting for a steady frame rate"));
        eq("Now: paused. At 60 Hz the screen has no room for extra frames.", PlayerWords.frameGen("2 on, the display runs at 60 Hz: no refresh left for a generated frame"));
        eq("Now: off.", PlayerWords.frameGen("0 off"));
        eq("Now: not running yet.", PlayerWords.frameGen("0 "));
        eq("Now: needs your Lossless.dll. Pick it in Settings › Graphics › Frame generation.",
                PlayerWords.frameGen("-1 no Lossless.dll (the launcher looks in Documents/Emulation/bios, Switch2 and the app's files; or pick it in the launcher)"));
        eq("Now: this phone's GPU cannot run it.", PlayerWords.frameGen("-1 the GPU lacks shaderFloat16"));
        eq("Now: your Lossless.dll could not be read. Pick it again in Settings.", PlayerWords.frameGen("-1 /storage/emulated/0/Lossless.dll: bad header"));
        eq("Now: could not start. Share log has the details.", PlayerWords.frameGen("-1 the frame generation chain could not be made (see the [RED] lines)"));
        eq("Now: not in this version of the app.", PlayerWords.frameGen("-1 not in this build (built without NX_LSFG)"));
        eq("Now: not in this version of the app.", PlayerWords.frameGen("-2 not in this host"));
        eq(null, PlayerWords.frameGen("3 something new"));   // a state it does not know: the menu keeps its own line
        eq(null, PlayerWords.frameGen(null));

        System.out.println("ok " + n);
    }
}
