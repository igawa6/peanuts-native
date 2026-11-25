package dev.peanuts.build;

import java.io.File;

/**
 * Which app release made a game's shaders, for the game's sheet (GameSheet): the vk_chain in its library's header (read by ShaderUpgrade.header) looked up in
 * the releases' chains. A library of 0.1.7 or of this release's test builds upgrades itself in seconds at the next start (ShaderUpgrade, vk_android_upgrade.py
 * HOPS), so it gets a quiet line; an older one only plays, without the graphics fixes since, so it gets a warning that points to Rebuild. A version the table
 * does not know (another test build) says nothing. BuiltWithJvmTest, run by peanuts/tests/test_built_with.py
 */
public final class BuiltWith {
    private BuiltWith() {}

    /** CURRENT: this app version's library; FAST: one the upgrade takes; OLD: one only a rebuild brings up to date; UNKNOWN: nothing to say */
    public enum Kind { CURRENT, FAST, OLD, UNKNOWN }

    /**
     * Each release's DMSH_VK_CHAIN, oldest first, from the release tags' dm/gfx/nvn/dmsh_chain_vk.h (v0.1.0 to v0.1.2 as the project's GitHub tags, v0.1.3-1 on).
     * 0.1.2 and 0.1.3 have the same version (4d3a924dcd04): it reads as 0.1.3. This release's own comes from the build (BuildConfig.NX_DMSH_VK_CHAIN)
     */
    static final String[][] RELEASES = {
            {"4b1886941d10", "0.1.0"}, {"c3959e8b8f88", "0.1.1"}, {"4d3a924dcd04", "0.1.3"}, {"c13dc8b37b0f", "0.1.4"}, {"dfebef5d68a9", "0.1.5"},
            {"e084efc69b46", "0.1.6"}, {"c679ddfbc5db", "0.1.7"}};
    /** The oldest release whose library the upgrade takes */
    static final String FAST_FROM = "0.1.7";
    /** This release's test builds (0.1.8): the upgrade takes them, and they read as this app version */
    static final String[] THIS_CYCLE = {"c9337a3f4e01", "68162be2976c"};

    /** What the sheet says: the kind, and the release (null: this app version) */
    public static final class Result {
        public final Kind kind;
        public final String release;
        final int rank;   // the newer the higher: a release's place in RELEASES, then this cycle's builds, then this app version
        Result(Kind kind, String release, int rank) { this.kind = kind; this.release = release; this.rank = rank; }
    }

    static final Result UNKNOWN = new Result(Kind.UNKNOWN, null, -1);

    /** A library version's release, current: this app version's (DMSH_VK_CHAIN) */
    static Result of(String chain, String current) {
        if (chain == null || chain.isEmpty()) return UNKNOWN;
        if (chain.equals(current)) return new Result(Kind.CURRENT, null, RELEASES.length + 1);
        for (String c : THIS_CYCLE) if (c.equals(chain)) return new Result(Kind.FAST, null, RELEASES.length);
        int fast = -1;
        for (int i = 0; i < RELEASES.length; i++) if (RELEASES[i][1].equals(FAST_FROM)) fast = i;
        for (int i = 0; i < RELEASES.length; i++) if (RELEASES[i][0].equals(chain)) return new Result(i >= fast ? Kind.FAST : Kind.OLD, RELEASES[i][1], i);
        return UNKNOWN;
    }

    /**
     * The release of the shaders titleId's game plays from packDir: this app version's library when the pack has it, else the newest release among the game's
     * other shaders/v-&lt;V&gt;/dmsh_vk.lib (header: a library, this game, V as its version). An older release is told only when no library of an unknown
     * version is there too (that one could be newer). Reads a few bytes of each library: not on the main thread
     */
    public static Result of(File packDir, String titleId, String current) {
        if (packDir == null || titleId == null || current == null) return UNKNOWN;
        File cur = ShaderUpgrade.target(packDir, current);
        if (cur.isFile()) {
            ShaderUpgrade.Header h = ShaderUpgrade.header(cur);
            return h != null && current.equals(h.vkChain) && titleId.equalsIgnoreCase(h.game) ? of(current, current) : UNKNOWN;
        }
        File[] dirs = new File(packDir, "shaders").listFiles(f -> f.getName().matches("v-[0-9a-f]{12}"));
        if (dirs == null) return UNKNOWN;
        Result best = UNKNOWN;
        boolean unknown = false;
        for (File d : dirs) {
            File lib = new File(d, ShaderUpgrade.LIB);
            if (!lib.isFile()) continue;
            ShaderUpgrade.Header h = ShaderUpgrade.header(lib);
            if (h == null || !titleId.equalsIgnoreCase(h.game) || !d.getName().substring(2).equals(h.vkChain)) continue;
            Result r = of(h.vkChain, current);
            if (r.kind == Kind.UNKNOWN) unknown = true;
            else if (r.rank > best.rank) best = r;
        }
        return best.kind == Kind.OLD && unknown ? UNKNOWN : best;
    }

    /** The chip's or the line's words: "Built with 0.1.3", "Built with this app version"; null for UNKNOWN */
    public static String label(Result r) {
        return r == null || r.kind == Kind.UNKNOWN ? null : "Built with " + (r.release == null ? "this app version" : r.release);
    }

    /** What the OLD chip says when tapped; null for any other kind */
    public static String warning(Result r) {
        return r == null || r.kind != Kind.OLD ? null : "This game's graphics were prepared with Peanuts Native " + r.release
                + ". It still plays, but it misses the graphics fixes since then. Rebuild it to get them.";
    }
}
