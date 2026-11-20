package dev.peanuts.ui;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The library's walk over one root (LibraryScanner.scan): the dump folders and the files it finds, each root walked two levels deep. A dump folder holds the
 * game's code, base/exefs/main or exefs/main (one version's folder), and is not walked further. Mods are left out (issue #46): a mod for Eden or Atmosphere is
 * a folder with exefs/ and romfs/ too, but its exefs/ holds patches and at most its own main.npdm, never the game's main; and the walk does not go into a folder
 * named mods, load (Eden's and yuzu's) or atmosphere below the root, where a mod that brings a whole main would sit. Before, a mod with a main.npdm was taken as
 * a dump of the game, and a dump wins over the game's NSP. Plain Java (no android.*) so peanuts/tests/test_library_walk.py runs it on the JVM.
 */
final class LibraryWalk {
    private LibraryWalk() {}

    /** Folders the walk does not go into: where emulators and Atmosphere keep mods */
    private static final Set<String> MOD_FOLDERS = new HashSet<>(Arrays.asList("mods", "load", "atmosphere"));

    /** What the walk found: a dump folder, or a file (any name: the scanner keeps the extensions it reads) */
    interface Sink {
        void dump(File dir);
        void file(File f);
    }

    static void walk(File dir, int depth, Sink out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith("-work")) continue;
            if (f.isDirectory()) {
                if (MOD_FOLDERS.contains(n.toLowerCase(Locale.ROOT))) continue;
                if (isDump(f)) out.dump(f);
                else if (depth < 2) walk(f, depth + 1, out);
            } else out.file(f);
        }
    }

    static boolean isDump(File d) { return new File(d, "base/exefs/main").isFile() || new File(d, "exefs/main").isFile(); }
}
