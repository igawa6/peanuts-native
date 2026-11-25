package dev.peanuts.build;

import java.io.File;

/**
 * One version's folder: a dump folder with exefs/ and romfs/ at its top instead of in base/ or updates/&lt;version&gt;/ (a game extracted on a PC, GitHub issue
 * #370). The library lists it (dev.peanuts.ui.LibraryWalk), but upstream's Prepare starts only from base/ or updates/ (DumpLayout.versions) and refuses it
 * with "Pick the dump folder above it, the one with base/", which does not help when there is none. Prepare says this instead: the files go into a folder
 * named base, and Build then finds their build by the build IDs, also an update's whole code (DumpLayout.find). No Android classes: peanuts/tests runs it.
 */
public final class VersionFolder {
    private VersionFolder() {}

    /** Whether the folder is one version's: exefs/main at its top, and neither base/ nor updates/ */
    public static boolean is(File dump) {
        return dump != null && dump.isDirectory() && new File(dump, "exefs/main").isFile() && !new File(dump, "base").exists() && !new File(dump, "updates").exists();
    }

    /** What Build says for one version's folder, null for any other */
    public static String why(File dump) {
        if (!is(dump)) return null;
        String n = dump.getName();
        return n + " has one version's exefs and romfs folders at its top, and Build looks for them in a folder named base. Make a folder named base in "
                + n + ", move exefs and romfs into it, then Rescan and build again.";
    }
}
