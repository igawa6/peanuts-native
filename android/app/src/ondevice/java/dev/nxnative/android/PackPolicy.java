package dev.nxnative.android;

import java.util.Locale;

/**
 * PackService's automatic pause (ONDEVICE.md "The service and the screen"), with no Android classes so PackPolicyJvmTest runs it:
 * <ul>
 * <li>battery below 20 % and not charging;</li>
 * <li>heat: thermal status (PowerManager.getCurrentThermalStatus) SEVERE or worse, and once paused for heat, until it is LIGHT or better, or MODERATE once it
 *     has stayed below SEVERE for {@link #COOL_MS} (PEANUTS, #803: it went on at MODERATE seconds after each pause, and an AYN Odin 3 on its charger was back at SEVERE
 *     within a minute, 74 times in 4 hours).</li>
 * </ul>
 * PEANUTS (#803): a heat pause stops the step in hand only when the step keeps what it did ({@link #heatStops}: the split's scan results, the translated
 * shaders); any other step is held where it is (PackService's hold file, pack_android.py held()) and goes on from there, or finishes when its tool cannot hold.
 * "Go on anyway" (the card and the notification, while a heat pause waits) overrides the heat pause for as long as the job runs in this service: a thermal HAL that
 * reports SEVERE on a cool handheld (GitHub #38) no longer holds the job. CRITICAL or worse still pauses (until SEVERE or better): there the system itself is about
 * to throttle hard or shut down. The battery pause has no override.
 */
final class PackPolicy {
    private PackPolicy() {}

    /** PowerManager.THERMAL_STATUS_* (API 29) */
    static final int NONE = 0, LIGHT = 1, MODERATE = 2, SEVERE = 3, CRITICAL = 4, EMERGENCY = 5, SHUTDOWN = 6;
    static final int BATTERY_MIN = 20;
    // PEANUTS begin: the heat pause's cool-down and the steps it stops (#803)
    /** A heat pause at MODERATE goes on once the status has stayed below SEVERE this long (PackService counts from the fall); at LIGHT or better it goes on at once */
    static final long COOL_MS = 120_000;
    /** From this many heat pauses in one run of the job the card and the notification put Go on anyway first, with {@link #HOT_AGAIN} */
    static final int HOT_AGAIN_AT = 2;
    static final String HOT_AGAIN = "The phone keeps getting hot; building on a charger heats it more. Go on anyway builds without pausing.";

    /** Whether a heat pause stops (interrupts) the tool of this step: only a step that keeps what it did and goes on from it. Every other one is held */
    static boolean heatStops(String step) { return "split".equals(step) || "shaders".equals(step); }

    /**
     * Whether a sticky restart (Android ended the packer's process; no intent) runs the job again: one that was running, or one this policy paused (heat,
     * battery), which then waits in the pause again (PackService.waitWhileAuto) and goes on by itself. A paused heat hold whose process ended was left paused with
     * nothing to take it on, and the build said "too hot, goes on when cooled" for good (found in review). The player's and Android's pauses stay paused
     */
    static boolean resumesAfterRestart(String state, String reason) {
        return "running".equals(state) || "paused".equals(state) && ("thermal".equals(reason) || "battery".equals(reason));
    }
    // PEANUTS end

    /** Why the job waits ("battery", "thermal"), null when it may run. was: the reason it waits now (null: it runs); heatOverride: Go on anyway was tapped */
    static String reason(int batteryPct, boolean charging, int thermal, String was, boolean heatOverride) {
        return reason(batteryPct, charging, thermal, was, heatOverride, Long.MAX_VALUE);   // PEANUTS: no cool-down known (the build queue's wait between games)
    }

    /** PEANUTS: the same, hotMs: how long the heat pause has been below SEVERE (COOL_MS, #803) */
    static String reason(int batteryPct, boolean charging, int thermal, String was, boolean heatOverride, long hotMs) {
        if (batteryPct < BATTERY_MIN && !charging) return "battery";
        boolean hot = "thermal".equals(was);
        if (heatOverride ? thermal >= CRITICAL || hot && thermal > SEVERE
                : thermal >= SEVERE || hot && (thermal > MODERATE || thermal == MODERATE && hotMs < COOL_MS)) return "thermal";   // PEANUTS: LIGHT, or MODERATE after COOL_MS
        return null;
    }

    /** Whether Go on anyway applies to this wait: a heat pause that the override lifts (not one at CRITICAL or worse) */
    static boolean canOverride(String reason, int thermal) { return "thermal".equals(reason) && thermal < CRITICAL; }

    static String thermalName(int s) {
        switch (s) {
            case NONE: return "none";
            case LIGHT: return "light";
            case MODERATE: return "moderate";
            case SEVERE: return "severe";
            case CRITICAL: return "critical";
            case EMERGENCY: return "emergency";
            case SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }

    /** The same for the card and the notification, in the player's words: "phone temperature: hot, battery 80 %, charging" */   // PEANUTS: build text in the player's words (0.1.8)
    static String plainReadings(int batteryPct, boolean charging, int thermal) {
        String heat = thermal >= CRITICAL ? "very hot" : thermal >= SEVERE ? "hot" : thermal >= LIGHT ? "warm" : "normal";
        return "phone temperature: " + heat + ", battery " + batteryPct + " %" + (charging ? ", charging" : "");
    }

    /** The readings behind a decision, for the step's log: "thermal status 3 (severe), headroom 0.62, battery 80 %, charging" */
    static String readings(int batteryPct, boolean charging, int thermal, float headroom) {
        return "thermal status " + thermal + " (" + thermalName(thermal) + "), headroom " + (Float.isNaN(headroom) ? "not known" : String.format(Locale.ROOT, "%.2f", headroom))
                + ", battery " + batteryPct + " %" + (charging ? ", charging" : "");
    }
}
