package dev.peanuts.build;

import java.io.File;

/**
 * Which app release made a game's shaders, for the game's sheet (GameSheet): the vk_chain in its library's header (read by ShaderUpgrade.header) looked up in
 * the releases' chains. A library the in-place upgrade takes (ShaderUpgrade, vk_android_upgrade.py HOPS: a path of hops to this app version's DMSH_VK_CHAIN,
 * and the same DMSH_CHAIN, since the tool refuses a library of another translator front half) upgrades itself in seconds at the next start, so it gets a quiet
 * line. One of REMAKE_FROM or newer that it does not take still plays while its shaders are made again once (an Update shaders or a Rebuild), so it gets a
 * plain line that says so. An older one only plays, without the graphics fixes since, so it gets a warning that points to Rebuild. A version the table does
 * not know (another test build) says nothing. BuiltWithJvmTest, run by peanuts/tests/test_built_with.py
 */
public final class BuiltWith {
    private BuiltWith() {}

    /**
     * CURRENT: this app version's library; FAST: one the upgrade takes; REMAKE: one whose shaders are made again once while it plays; OLD: one only a rebuild
     * brings up to date; UNKNOWN: nothing to say
     */
    public enum Kind { CURRENT, FAST, REMAKE, OLD, UNKNOWN }

    /**
     * Each release's DMSH_VK_CHAIN and DMSH_CHAIN, oldest first, from the release tags' dm/gfx/nvn/dmsh_chain_vk.h and dmsh_chain.h (v0.1.0 to v0.1.2 as the
     * project's GitHub tags, v0.1.3-1 on). 0.1.2 and 0.1.3 have the same versions (4d3a924dcd04): it reads as 0.1.3. This release's own come from the build
     * (BuildConfig.NX_DMSH_VK_CHAIN, NX_DMSH_CHAIN)
     */
    static final String[][] RELEASES = {
            {"4b1886941d10", "9b2613d5216b", "0.1.0"}, {"c3959e8b8f88", "9b2613d5216b", "0.1.1"}, {"4d3a924dcd04", "9474b20069ad", "0.1.3"},
            {"c13dc8b37b0f", "20aaebbe3106", "0.1.4"}, {"dfebef5d68a9", "a6bde736ed83", "0.1.5"}, {"e084efc69b46", "cdcf140b1558", "0.1.6"},
            {"c679ddfbc5db", "cdcf140b1558", "0.1.7"}, {"327c6383b614", "cdcf140b1558", "0.1.8"}};
    /**
     * This release's test builds before its own chains (DMSH_VK_CHAIN, DMSH_CHAIN): they read as this app version when the upgrade takes them. 0.1.9 moved the
     * chains once (#572, the translator rebuilt), to the build's own, so it has none
     */
    static final String[][] THIS_CYCLE = {};
    /** vk_android_upgrade.py HOPS as (from, to) DMSH_VK_CHAIN pairs, in its order (test_built_with.py checks they are the tool's) */
    static final String[][] HOPS = {{"c679ddfbc5db", "c9337a3f4e01"}, {"c9337a3f4e01", "68162be2976c"}, {"68162be2976c", "327c6383b614"}};
    /** The oldest release whose library still plays while its shaders are made again once (its ABI is this host's or one it also plays, vka_abi_compat.h) */
    static final String REMAKE_FROM = "0.1.7";

    /** What the sheet says: the kind, and the release (null: this app version) */
    public static final class Result {
        public final Kind kind;
        public final String release;
        final int rank;   // the newer the higher: a release's place in RELEASES, then this cycle's builds, then this app version
        Result(Kind kind, String release, int rank) { this.kind = kind; this.release = release; this.rank = rank; }
    }

    static final Result UNKNOWN = new Result(Kind.UNKNOWN, null, -1);

    /** Whether the in-place upgrade has a complete path of HOPS from vkChain to current, and the library's DMSH_CHAIN is this app version's */
    static boolean upgradable(String vkChain, String dmshChain, String current, String currentDmsh) {
        if (vkChain == null || current == null || dmshChain == null || !dmshChain.equals(currentDmsh)) return false;
        String v = vkChain;
        for (int steps = 0; steps <= HOPS.length && !v.equals(current); steps++) {
            String next = null;
            for (String[] h : HOPS) if (h[0].equals(v)) next = h[1];
            if (next == null) return false;
            v = next;
        }
        return v.equals(current);
    }

    /** A library version's release, current / currentDmsh: this app version's DMSH_VK_CHAIN and DMSH_CHAIN */
    static Result of(String chain, String current, String currentDmsh) {
        if (chain == null || chain.isEmpty()) return UNKNOWN;
        if (chain.equals(current)) return new Result(Kind.CURRENT, null, RELEASES.length + 1);
        for (String[] c : THIS_CYCLE) if (c[0].equals(chain) && upgradable(c[0], c[1], current, currentDmsh)) return new Result(Kind.FAST, null, RELEASES.length);
        int remake = RELEASES.length;
        for (int i = 0; i < RELEASES.length; i++) if (RELEASES[i][2].equals(REMAKE_FROM)) remake = i;
        for (int i = 0; i < RELEASES.length; i++) {
            if (!RELEASES[i][0].equals(chain)) continue;
            Kind k = upgradable(RELEASES[i][0], RELEASES[i][1], current, currentDmsh) ? Kind.FAST : i >= remake ? Kind.REMAKE : Kind.OLD;
            return new Result(k, RELEASES[i][2], i);
        }
        return UNKNOWN;
    }

    /**
     * The release of the shaders titleId's game plays from packDir: this app version's library when the pack has it, else the newest release among the game's
     * other shaders/v-&lt;V&gt;/dmsh_vk.lib (header: a library, this game, V as its version). An older release is told only when no library of an unknown
     * version is there too (that one could be newer). Reads a few bytes of each library: not on the main thread
     */
    public static Result of(File packDir, String titleId, String current, String currentDmsh) {
        if (packDir == null || titleId == null || current == null) return UNKNOWN;
        File cur = ShaderUpgrade.target(packDir, current);
        if (cur.isFile()) {
            ShaderUpgrade.Header h = ShaderUpgrade.header(cur);
            return h != null && current.equals(h.vkChain) && titleId.equalsIgnoreCase(h.game) ? of(current, current, currentDmsh) : UNKNOWN;
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
            Result r = of(h.vkChain, current, currentDmsh);
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

    /** The plain sentence after a REMAKE line's words (GameSheet: "Built with 0.1.8. " and this); null for any other kind */
    public static String note(Result r) {
        return r == null || r.kind != Kind.REMAKE ? null : "This app version makes shaders in a new way, so this game's shaders are made again once, with Rebuild or"
                + " Update shaders. It plays with the shaders it has until then.";
    }
}
