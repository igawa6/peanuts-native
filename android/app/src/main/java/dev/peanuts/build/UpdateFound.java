package dev.peanuts.build;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * A built game whose base is a .nup, with a newer update or add-on content (DLC) found beside it that the .nup does not hold yet (issue #120, ask, then
 * build: the cover says so, a tap asks, and Build takes it in with Import again, whose build keeps what did not change). Only an update the game's profile
 * knows counts (one it does not would build as a version it was not made for), and only one newer than the .nup's. No Android class: the JVM test drives it.
 */
public final class UpdateFound {
    private UpdateFound() {}

    public static final int UPDATE = 1, DLC = 2;

    /** A package of the game: inside its .nup, or a file of its own beside it */
    public static final class Part {
        final int kind; final String titleId; final long version; final boolean inside;
        public Part(int kind, String titleId, long version, boolean inside) {
            this.kind = kind; this.titleId = titleId == null ? "" : titleId.toUpperCase(Locale.ROOT); this.version = version; this.inside = inside;
        }
    }

    /** What is new: the newest update to take in (0: none) and the add-on contents not inside yet */
    public static final class Found {
        public final long update; public final List<String> dlcs;
        Found(long update, List<String> dlcs) { this.update = update; this.dlcs = dlcs; }
        /** What "Not now" remembers: the same finds ask no more, a newer one asks again */
        public String key() { return update + "|" + String.join(",", dlcs); }
    }

    /** → what an Import again would add, or null when nothing (supported: the update versions the game's profile knows; empty: none counts) */
    public static Found of(Collection<Part> parts, Collection<Long> supported) {
        long had = -1, best = 0;
        TreeSet<String> inside = new TreeSet<>();
        for (Part p : parts) if (p.inside) { if (p.kind == UPDATE) had = Math.max(had, p.version); else if (p.kind == DLC) inside.add(p.titleId); }
        for (Part p : parts) if (!p.inside && p.kind == UPDATE && p.version > Math.max(had, 0) && supported.contains(p.version)) best = Math.max(best, p.version);
        TreeSet<String> dlcs = new TreeSet<>();
        for (Part p : parts) if (!p.inside && p.kind == DLC && !p.titleId.isEmpty() && !inside.contains(p.titleId)) dlcs.add(p.titleId);
        if (best == 0 && dlcs.isEmpty()) return null;
        return new Found(best, new ArrayList<>(dlcs));
    }

    /**
     * Whether an Import again takes in the update the question named: f's update, or with only add-ons found the update the .nup holds (had; -1: none), and
     * no other. planned: the update version its plan takes (-1: none). The cover's Build skips Import again's confirmation only then: its plan takes the newest
     * update file beside the .nup (one the profile does not know too) or the one picked on the game's page, which the question did not name (found in review)
     */
    public static boolean takesWhatItNamed(Found f, long had, long planned) {
        if (f == null || planned == 0) return false;   // (0: an update file whose version the scan could not read)
        return planned == (f.update > 0 ? f.update : had);
    }

    /** The cover's question: "Update 3.0.4 and 2 add-ons found" (updateName: the update's name as the game's page shows it) */
    public static String what(Found f, String updateName) {
        String d = f.dlcs.isEmpty() ? "" : f.dlcs.size() == 1 ? "1 add-on" : f.dlcs.size() + " add-ons";
        if (f.update > 0) return "Update " + updateName + (d.isEmpty() ? "" : " and " + d) + " found";
        return (f.dlcs.size() == 1 ? "An add-on" : d.substring(0, 1).toUpperCase(Locale.ROOT) + d.substring(1)) + " found";
    }

    /** The dialog's text below the title */
    public static String ask(Found f, String name) {
        return (f.update > 0 && !f.dlcs.isEmpty() ? "They are" : f.update > 0 || f.dlcs.size() == 1 ? "It is" : "They are") + " beside the game file of " + name
                + ", which does not hold " + (f.update > 0 && !f.dlcs.isEmpty() || f.dlcs.size() > 1 ? "them" : "it") + " yet. Build takes "
                + (f.update > 0 && !f.dlcs.isEmpty() || f.dlcs.size() > 1 ? "them" : "it") + " in with Import again. What did not change since the last build is "
                + "kept from before, so it is much quicker than a first build. Your saves stay.";
    }
}
