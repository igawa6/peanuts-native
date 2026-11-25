package dev.peanuts.ui;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The library's walk over one root (LibraryScanner.scan): the dump folders and the files it finds, each root walked two levels deep. A dump folder holds the
 * game's code in a layout upstream's DumpLayout reads ({@link #isDump}), and is not walked further. Mods are left out (issue #46): a mod for Eden or Atmosphere is
 * a folder with exefs/ and romfs/ too, but its exefs/ holds patches and at most its own main.npdm and main, never the SDK's module sdk ({@link #wholeCode});
 * and the walk does not go into a folder named mods, load (Eden's and yuzu's) or atmosphere below the root. Before, a mod with a main.npdm was taken as a dump
 * of the game, and a dump wins over the game's NSP. Plain Java (no android.*) so peanuts/tests/test_library_walk.py runs it on the JVM.
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

    /**
     * Whether a folder is a dump folder: a version of it has the game's code (exefs/main) where upstream's DumpLayout.versions reads it. The base, base/ (in the
     * dump tools' layout, or in the console's as OunceCake serves it: base/program/0/); an update, updates/&lt;version&gt;/ in either layout (a dump of an
     * update alone carries the whole game); or one version's folder, exefs/ at its top, with the game's whole code ({@link #wholeCode}). Before, the walk saw
     * only base/exefs/main and exefs/main: an OunceCake dump was not found, and an update-only dump was listed as its version's folder (updates/&lt;version&gt;)
     * instead of the dump
     */
    static boolean isDump(File d) {
        if (new File(root(new File(d, "base")), "exefs/main").isFile()) return true;
        File[] ups = new File(d, "updates").listFiles(File::isDirectory);
        if (ups != null) for (File u : ups) if (u.getName().matches("\\d+") && new File(root(u), "exefs/main").isFile()) return true;
        return wholeCode(new File(d, "exefs"));
    }

    /**
     * Whether one version's folder (exefs/ at its top) holds the game's whole code: main, main.npdm and sdk. A mod that sits loose in the game folder has this
     * layout too, and a code mod may bring a whole main of its own (and a main.npdm), but never the SDK's module: every game profile's build row checks sdk
     * (game.tsv modules), so a folder without it never plays. The dump tools' layouts (base/, updates/) are never a mod's
     */
    static boolean wholeCode(File exefs) {
        return new File(exefs, "main").isFile() && new File(exefs, "main.npdm").isFile() && new File(exefs, "sdk").isFile();
    }

    /**
     * A version folder's package, the folder with its exefs/ and romfs/, as upstream's DumpLayout.root finds it: the version folder itself, or in the console's
     * layout program/0/ in it (program/0/program/ when a data content shares the index)
     */
    static File root(File verDir) {
        for (String r : new String[] {"", "program/0", "program/0/program"}) {
            File d = r.isEmpty() ? verDir : new File(verDir, r);
            if (new File(d, "exefs").isDirectory() || new File(d, "romfs").isDirectory()) return d;
        }
        return verDir;
    }

    // ---- A dump folder's version from its code (GitHub issue #370) ----

    /**
     * The build ID of the main of a dump folder's base, base/exefs/main (at its package, {@link #root}), else of one version's folder, exefs/main: the 32 bytes at
     * 0x40 of its NSO header, in lower-case hex. null when there is none or it is not an NSO
     */
    static String mainBuildId(File dump) {
        File main = new File(root(new File(dump, "base")), "exefs/main");
        if (!main.isFile()) main = new File(dump, "exefs/main");
        byte[] h = new byte[0x60];
        try (java.io.FileInputStream in = new java.io.FileInputStream(main)) {
            int n = 0;
            for (int r; n < h.length && (r = in.read(h, n, h.length - n)) > 0; ) n += r;
            if (n < h.length || h[0] != 'N' || h[1] != 'S' || h[2] != 'O' || h[3] != '0') return null;
        } catch (java.io.IOException e) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0x40; i < 0x60; i++) s.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
        return s.toString();
    }

    /**
     * The version a main of this build ID is, by the game's builds (game.tsv build rows: version, module to its build ID, 16 bytes or more in hex): the row
     * whose main the ID starts with; -1 when none is or there is no ID. A dump folder has a version number only in updates/&lt;version&gt;/, so a base or one
     * version's folder that holds an update's whole code (a dump updated on a PC, as in the issue: Breath of the Wild 1.9.0) was taken for the base game's
     * version, and the sheet said Wrong version. Upstream's Prepare takes such a dump as that build too (DumpLayout.find: by the build IDs)
     */
    static long versionOf(String mainId, java.util.Map<Long, ? extends java.util.Map<String, String>> builds) {
        if (mainId == null || builds == null) return -1;
        for (java.util.Map.Entry<Long, ? extends java.util.Map<String, String>> b : builds.entrySet()) {
            String m = b.getValue() == null ? null : b.getValue().get("main");
            if (m != null && m.length() >= 32 && mainId.startsWith(m.toLowerCase(Locale.ROOT))) return b.getKey();
        }
        return -1;
    }
}
