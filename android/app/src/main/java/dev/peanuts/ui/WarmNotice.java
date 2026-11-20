package dev.peanuts.ui;

/**
 * The in-game "Preparing graphics" notice (android/README.md, In-game notices; pure Java: dm/tools/tests/test_android_warmnotice.py runs it on the JVM). With background
 * pipelines (DM_VK_PSO_ASYNC) the host skips a draw whose pipeline is still being made, so on a game's first run parts of the picture can be missing for a while: EA SPORTS
 * FC 27's pitch was black with only the HUD for a minute on an AYN Thor. Native.warmStatus() is the number of pipelines still waiting while draws are skipped for them
 * (vka_exec.c nxhost_warm_status, vka_warmnote.h), 0 when nothing waits, -1 when the host cannot say. GameActivity polls it every second and shows this text, small, at
 * the top, for as long as it is above 0.
 */
public final class WarmNotice {
    private WarmNotice() {}

    /** Whether the host's answer asks for the notice */
    public static boolean shown(int waiting) { return waiting > 0; }

    /** Whether to keep asking: -1 is a host without the entry point */
    public static boolean poll(int waiting) { return waiting >= 0; }

    /** The notice for that many pipelines still waiting */
    public static String text(int waiting) {
        return "Preparing graphics: " + waiting + (waiting == 1 ? " pipeline" : " pipelines") + " left. Parts of the picture may be missing until they are ready (first run only).";
    }
}
