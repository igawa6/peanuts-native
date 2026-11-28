package dev.peanuts.shaders;

/**
 * The host's four numbers (dev.peanuts.Bridge.shaderWatch, dm/gfx/vk_android/vka_pso.c vka_pso_watch) as {@link ShaderWatch}'s, for {@link HostWatch}. Pure, so
 * HostCountsJvmTest checks it on the JVM. v: null when the host cannot say, else {the list: 1 kept, 0 none, -1 not read yet; pipelines new to it this run;
 * pipelines the next start makes ahead on this driver; records in all}.
 */
final class HostCounts {
    private HostCounts() {}

    /** ShaderWatch.UNKNOWN (that class reads Android's prefs, so it is not compiled into the JVM test; HostWatch checks the two are equal) */
    static final int UNKNOWN = -1;

    /** Whether the host keeps a list for this game: it can say, and the list is not off (-1, not read yet, is a list still to come) */
    static boolean available(int[] v) { return v != null && v.length >= 4 && v[0] != 0; }

    /** The pipelines new to the list this run, UNKNOWN before the host has read the list */
    static int learned(int[] v) { return read(v) ? Math.max(0, v[1]) : UNKNOWN; }

    /** The pipelines the next start makes ahead on this driver, UNKNOWN before the host has read the list */
    static int ready(int[] v) { return read(v) ? Math.max(0, v[2]) : UNKNOWN; }

    private static boolean read(int[] v) { return v != null && v.length >= 4 && v[0] == 1; }
}
