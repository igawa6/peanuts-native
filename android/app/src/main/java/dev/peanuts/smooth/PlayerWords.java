package dev.peanuts.smooth;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Developer view off (DevView, Settings › Developer): the in-game menu's plain words, and which of the start's notes only testers need. Each function hands
 * back the menu's own line when it does not know it, so an upstream change of a line shows the old words, never a broken sentence. Plain Java, no Android
 * (SmoothJvmTest).
 */
public final class PlayerWords {
    private PlayerWords() {}

    // ---- The start's notes ----

    /** A note of the game's start only a tester needs: a switch nothing reads (Readiness.unknownLine), DM_TEST_CRASH outside the test app */
    public static boolean forTesters(String note) {
        return note != null && (note.contains(" is not a switch of this build: nothing reads it") || note.contains("DM_TEST_CRASH not taken"));
    }

    // ---- The in-game menu's words (Developer view off) ----

    private static final Map<String, String> PANEL = new HashMap<>();
    static {
        PANEL.put("Applies at the next start (this run takes no live changes: DM_CTL is off).", "Applies at the next start.");   // SidePanel
        PANEL.put("This build has no frame rate cap.", "This version of the app has no frame rate cap.");                         // SidePanel.refresh
        PANEL.put("Not in this build (built with -PnxLsfg=false).", "Not in this version of the app.");                           // Lsfg.rows
        PANEL.put("Mode: performance (the only shader set Eden's LSFG uses)", "Mode: performance.");                              // Lsfg.rows
    }

    /** A line of the in-game menu in player words; the line itself when there is none for it */
    public static String panel(String s) {
        String p = s == null ? null : PANEL.get(s);
        return p != null ? p : s;
    }

    private static final Pattern HZ = Pattern.compile("^on, the display runs at (\\d+) Hz");

    /**
     * Frame generation's "Now:" line from the host's status (Native.frameGenStatus: "<state> <reason>", vka_framegen.c), in player words. null when the reason
     * is not one of the host's known ones (the caller keeps its own line)
     */
    public static String frameGen(String status) {
        if (status == null) return null;
        int sp = status.indexOf(' ');
        String code = sp > 0 ? status.substring(0, sp) : status, why = sp > 0 ? status.substring(sp + 1).trim() : "";
        if (why.equals("generating")) return "Now: adding frames.";
        if (why.equals("on, waiting for a steady frame rate")) return "Now: waiting for a steady frame rate.";
        Matcher hz = HZ.matcher(why);
        if (hz.find()) return "Now: paused. At " + hz.group(1) + " Hz the screen has no room for extra frames.";
        if (why.equals("off")) return "Now: off.";
        if (why.startsWith("no Lossless.dll")) return "Now: needs your Lossless.dll. Pick it in Settings › Graphics › Frame generation.";
        if (why.startsWith("the GPU lacks")) return "Now: this phone's GPU cannot run it.";
        if (why.startsWith("the frame generation chain could not be made")) return "Now: could not start. Share log has the details.";
        if (code.equals("-2") || why.startsWith("not in this build") || why.startsWith("not in this host")) return "Now: not in this version of the app.";
        if (code.equals("-1") && why.contains("Lossless.dll")) return "Now: your Lossless.dll could not be read. Pick it again in Settings.";
        if (code.equals("0") && why.isEmpty()) return "Now: not running yet.";
        return null;
    }
}
