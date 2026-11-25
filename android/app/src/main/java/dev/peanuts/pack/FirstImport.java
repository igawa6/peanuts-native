package dev.peanuts.pack;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Import pack… before a game's first build (GitHub issue #44): the rules and the words of {@link PackImport}, without Android, so
 * peanuts/tests/test_first_import.py runs them on the JVM.
 * <p>
 * Upstream's Import pack… sits on a listed game's Storage tab (PackUi, PackZip): before the first build the game has no list entry, so there was nowhere
 * to import a pack made on a PC or exported from another phone. The game's sheet offers it (dev.peanuts.ui.GameSheet), and the pack goes where that
 * game's next Build puts its own ({@link Game#target}), so Build finds it there and makes only what does not fit (dev.peanuts.build.Prepare, step 3).
 * <ul>
 * <li>The pack must be the sheet's game: its pack.tsv names that title ({@link #otherTitle} otherwise).</li>
 * <li>A game whose file is a dump folder or a .nup ({@link Game#file}) has its pack checked against that file, as upstream's import does (BuildCheck: the
 *     build IDs of the code); a pack that fits gets the game's list entry, so the game is ready to play at once.</li>
 * <li>A game Build still imports from an NSP or XCI has no file to compare: the pack's version must be the one Build will use, the update chosen on the
 *     sheet or else the base ({@link #versionProblem}). Build then makes the .nup and takes the pack.</li>
 * </ul>
 * A refusal says what to do next and ends in "Nothing was changed." (the import writes into a .partial folder beside the pack folder and only renames it
 * in once every check passed: PackZip.stage, PackZip.swapIn).
 */
public final class FirstImport {
    /** The free space an import keeps beside the pack, as upstream's import does */
    public static final long SPARE = 256L << 20;

    private FirstImport() {}

    /** A game of the library, as its sheet would build it */
    public static final class Game {
        public final String titleId, name;
        /** The dump folder or .nup the pack is checked against, null for a game Build still imports from an NSP or XCI */
        public final File file;
        /** The version Build uses: the update chosen on the sheet, else the base's */
        public final long version;
        /** The versions of the updates the library found for the game (what the sheet's Update row can choose) */
        public final Set<Long> updates;
        /** The profile's builds: version to its display ("1.2.0"); a null display when game.tsv names none */
        public final Map<Long, String> builds;
        /** Where the pack goes: where the game's next Build puts its pack (its Build destination, else the app's usual place, AppStorage.pack) */
        public final File target;
        /** What Build prepares: the dump folder or .nup, for an NSP or XCI the .nup it makes (GameSheet.State.nup). Its working folder (&lt;title ID&gt;-work
         *  beside it, DumpCheck.workOf) holds the Prepare job Build would go on with instead of taking a pack. Null when there is no game folder for it yet */
        public final File buildFile;

        public Game(String titleId, String name, File file, long version, Set<Long> updates, Map<Long, String> builds, File target, File buildFile) {
            this.titleId = titleId.toUpperCase(Locale.ROOT);
            this.name = name == null || name.isEmpty() ? this.titleId : name;
            this.file = file;
            this.version = version;
            this.updates = updates == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(updates));
            this.builds = builds == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(builds));
            this.target = target;
            this.buildFile = buildFile;
        }
    }

    /**
     * What the sheet asked before it opened the system picker: the title, and the pack folder whose pack the player agreed to replace (null when none was
     * there). The picker's answer is checked again against the library as it is then (dev.peanuts.ui.PackImportUi.picked): a build may have started while the
     * picker was open, and a pack in the pack folder then that the player was not asked about is asked about before it is replaced ({@link #asked})
     */
    public static final class Pick {
        public final String titleId;
        public final File replaces;

        public Pick(String titleId, File replaces) {
            this.titleId = titleId.toUpperCase(Locale.ROOT);
            this.replaces = replaces;
        }

        /** Whether the player agreed to replace the pack in this pack folder (when one is there now, the import may replace it without asking again) */
        public boolean asked(File target) { return replaces != null && replaces.equals(target); }

        /**
         * As it is kept while the picker is open (dev.peanuts.PeanutsPrefs.packImportPick: Android may end the process meanwhile, and the picker's answer then
         * comes to a new one): "&lt;title ID&gt;\t&lt;pack folder&gt;", the folder empty when none
         */
        public String text() { return titleId + "\t" + (replaces == null ? "" : replaces.getPath()); }

        /** The pick of a {@link #text}, null for none or for a text that is not one */
        public static Pick of(String text) {
            int tab = text == null ? -1 : text.indexOf('\t');
            if (tab != 16 || !text.substring(0, 16).matches("[0-9A-Fa-f]{16}")) return null;
            String dir = text.substring(17);
            return new Pick(text.substring(0, 16), dir.isEmpty() ? null : new File(dir));
        }
    }

    // ---- Versions ----

    /** A pack.tsv version (the Switch version number), -1 when it is none */
    public static long parse(String version) {
        if (version == null) return -1;
        try {
            long v = Long.parseLong(version.trim());
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The version in the sheet's words: "v3" for 196608 (the release in the upper 16 bits), else the number */
    public static String versionText(long v) { return v == 0 ? "v0" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }

    /** "the base game", "the base game (1.0.0)", "update 1.2.0", "update v3" */
    public static String label(long v, Map<Long, String> builds) {
        String d = builds.get(v);
        if (v == 0) return d == null ? "the base game" : "the base game (" + d + ")";
        return "update " + (d != null ? d : versionText(v));
    }

    // ---- The checks' sentences ----

    /**
     * For a game Build still imports (no {@link Game#file}): why a pack of this version does not go in, null when it does. A pack whose version is not a
     * number cannot be told apart: it goes in, and Build checks its code once the .nup is made (it then makes the code again; the shaders are kept)
     */
    public static String versionProblem(Game g, String packVersion) {
        if (g.file != null) return null;   // the game file's build IDs decide (BuildCheck), not the version number
        long v = parse(packVersion);
        if (v < 0 || v == g.version) return null;
        String made = label(v, g.builds), uses = label(g.version, g.builds);
        String next = v != 0 && g.updates.contains(v) ? "Choose " + made + " under Update, then import the pack again."
                : v == 0 ? "Import a pack made from " + uses + "."
                : "Import a pack made from " + uses + ", or add the file of " + made + " to your update folder.";
        return "That pack was made from " + made + " of " + g.name + ", and Build uses " + uses + " here. " + next;
    }

    /** For a game with a dump folder or .nup: a pack made from another build than that file (BuildCheck's OTHER_BUILD and MISSING) */
    public static String otherBuild(Game g, String packVersion) {
        long v = parse(packVersion);
        return (v < 0 ? "That pack was made from another build of " + g.name + " than the game file here."
                : "That pack was made from " + label(v, g.builds) + " of " + g.name + ", another build than the game file here.")
                + " Import a pack made from this game file, or use Build.";
    }

    /** Where the game a pack is for stands in the library: not in it, in it and not built (its sheet has Import pack…), built (its page's Storage tab has it) */
    public enum Other { NOT_LISTED, TO_BUILD, BUILT }

    /** A pack of another game. otherName: the other game's name (null when this app does not know the title) */
    public static String otherTitle(Game g, String otherId, String otherName, Other where) {
        if (otherName == null) return "That pack is for a game this app version does not include (" + otherId + "), not " + g.name + ".";
        String next = where == Other.BUILT ? "Import it on " + otherName + "'s page, on its Storage tab."
                : where == Other.TO_BUILD ? "Import it from " + otherName + "'s sheet."
                : "Add " + otherName + "'s game file to your game folder, then import the pack from its sheet.";
        return "That pack is for " + otherName + ", not " + g.name + ". " + next;
    }

    /** A Prepare job of the game that did not finish: the next Build would go on with it and not use the pack */
    public static String stoppedBuild(Game g, boolean running) {
        if (running) return g.name + " is being built now. Wait for it to finish.";
        return "A build of " + g.name + " stopped part way, and the next Build would go on with it instead of using the build you picked. "
                + "Delete its working files first (the folder is under Details), then import the pack.";
    }

    /** The pack folder is the one another list entry of the same game plays from (a second game file of the title) */
    public static String inUse(Game g) {
        return "Another copy of " + g.name + " in your list plays from this pack folder. Import the pack on that game's page, on its Storage tab.";
    }

    /** A game of the title is running: the pack folder may be in use */
    public static String running(Game g) { return g.name + " is running: stop it first (its in-game panel, Stop the game)."; }

    /** Another import or export of the game's pack is working */
    public static String busy(Game g) { return "An export or import of " + g.name + "'s pack is still working."; }

    /** Not enough free space for the pack and the import's spare room */
    public static String lowSpace(long need, long free) { return "The pack needs " + size(need + SPARE) + " free, and its folder's storage has " + size(free) + "."; }

    /**
     * What Build prepares for a game, whose working folder (DumpCheck.workOf: its folder's &lt;title ID&gt;-work) holds the job Build would go on with: the game
     * file itself (a dump folder or .nup), else the .nup Build makes from the NSP or XCI: in the game's build folder &lt;build folder&gt;/&lt;title ID&gt; when one
     * applies (its Build destination, else the default build folder: BuildService.stepImport), else &lt;home&gt;/&lt;home&gt;.nup beside the game (GameSheet.state).
     * Only its folder matters. Null when there is none (no build folder and no game folder)
     */
    public static File buildFile(File gameFile, File buildGameDir, File home, String titleId) {
        if (gameFile != null) return gameFile;
        if (buildGameDir != null) return new File(buildGameDir, titleId + ".nup");
        return home == null ? null : new File(home, home.getName() + ".nup");
    }

    /**
     * Asked before the picker when the pack folder holds a pack already (from an earlier build or import that no list entry plays from), and after it when one
     * came into the folder while the picker was open
     */
    public static String replaces(Game g) {
        return g.name + " has a pack already, from an earlier build or import. The new pack replaces it once it has been checked; if it is refused, the one there stays.";
    }

    /** The new pack could not be renamed in, and the pack that was there could not be renamed back (PackZip.swapIn): it is kept beside its folder */
    public static String swapFailed(Game g) {
        return "The new pack of " + g.name + " could not be put in place, and the pack that was there could not be put back: it is kept under the name in Details. "
                + "Import the pack again, or give that folder its name back.";
    }

    /** Whether need bytes fit into free with the spare room */
    public static boolean fits(long need, long free) { return free >= need + SPARE; }

    /** A refusal for the player: capital first letter, one full stop, and what happened to the files */
    public static String refusal(String why) {
        String s = why == null || why.trim().isEmpty() ? "The pack could not be read" : why.trim();
        s = s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
        return s + (s.endsWith(".") ? "" : ".") + " Nothing was changed.";
    }

    /**
     * What the player reads once the pack is in. listed: the game got its list entry (a dump folder or .nup the pack fits); libraryProblem: Pack.libraryProblem
     * for the pack (null: this app's shaders); older: the library is of an older version the app plays and updates (Pack.playableLibrary, legacyLibrary)
     */
    public static String imported(Game g, boolean listed, String libraryProblem, boolean older) {
        if (listed) {
            if (libraryProblem == null) return "It fits " + g.name + "'s game file and has this app's shaders: ready to play.";
            return "It fits " + g.name + "'s game file. Its shaders are of an older version: the game plays with them while the app updates them, as after an app update.";
        }
        String build = g.file == null ? "Build makes the game's .nup from your files and uses this pack" : "Build uses this pack";
        if (libraryProblem == null) return build + ", so its shaders are not made again.";
        if (older) return build + ". Its shaders are of an older version: Build brings them up to date.";
        return build + ". " + libraryProblem + " Build makes them again for this app.";
    }

    /** The pack is in, and what should have followed it failed (the log has why): Build finds it where it is */
    public static String importedOnly(Game g) { return "The pack is in " + g.name + "'s pack folder. Build uses what fits of it."; }

    /** Whether the game gets its list entry after the import: a dump folder or .nup the pack fits, with shaders the app plays (this version's or an older one) */
    public static boolean lists(Game g, String libraryProblem, boolean older) { return g.file != null && (libraryProblem == null || older); }

    /** "1.2 MB", "3.40 GB" (SaveStore.size's form) */
    public static String size(long b) {
        if (b < 1024) return b + " bytes";
        if (b < 1 << 20) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1L << 30) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }
}
