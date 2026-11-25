package dev.peanuts.build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Import again: a game's .nup made anew from the dumps it came from, with the update and the add-on content (DLC) found now. Nothing else imports a game a
 * second time: Build and Rebuild use the .nup that is there. This class is the decision and its words, with no Android class (the JVM tests drive it):
 * <ul>
 * <li>{@link #plan}: which dumps go in, or why it cannot be done. Every package inside the old .nup must be found as a dump again (its base, an update at least
 *     as new, each add-on content), so an import never gives the game less than it had; what is missing is named with the folders that were looked in.</li>
 * <li>the free space: the new file is written beside the old one, which is replaced only when the import is complete, so both are there for a while
 *     ({@link Plan#lowSpace}); deleting the old one first is a second choice with its risk said ({@link #lowSpace}, {@link #deleteFirst}).</li>
 * <li>{@link #confirm}: what is deleted, what is kept and what was found, before anything is done.</li>
 * </ul>
 * {@link BuildService}'s import step does the work ({@link BuildRequest#replaceNup}).
 */
public final class Reimport {
    private Reimport() {}

    public static final int BASE = 0, UPDATE = 1, DLC = 2;

    /** A package inside the .nup (file: null), or a dump found for the game (an NSP or XCI) */
    public static final class Part {
        public final int kind; public final String titleId; public final long version; public final File file;
        public Part(int kind, String titleId, long version, File file) { this.kind = kind; this.titleId = titleId == null ? "" : titleId.toUpperCase(Locale.ROOT); this.version = version; this.file = file; }
    }

    /** What an import again would do; missing != null: nothing, and why */
    public static final class Plan {
        public String missing;
        public File nup, base, update;
        public long updateVersion;
        public final List<File> dlcs = new ArrayList<>();
        /** The old file's size, what the new one needs (the dumps' sizes together, as the first import's check counts it) and what is free where it goes */
        public long oldBytes, needBytes, freeBytes;

        /** The new file does not fit beside the old one */
        public boolean lowSpace() { return freeBytes < needBytes; }
        /** It would fit with the old file deleted first */
        public boolean fitsWithoutOld() { return oldBytes >= needBytes - freeBytes; }   // not free + old: an unknown free space is Long.MAX_VALUE

        /** The dumps as the importer takes them, the base first */
        public List<File> inputs() {
            List<File> l = new ArrayList<>();
            if (base != null) l.add(base);
            if (update != null) l.add(update);
            l.addAll(dlcs);
            return l;
        }
    }

    /**
     * nup: the file to make again; inside: its packages; found: the dumps the library found for the game (and the files the player added in its sheet);
     * chosenUpdate: the update file picked in the sheet (null: the newest found); roots: the folders the library reads; freeBytes: free where the .nup is
     */
    public static Plan plan(File nup, List<Part> inside, List<Part> found, File chosenUpdate, List<File> roots, long freeBytes) {
        Plan p = new Plan();
        p.nup = nup;
        p.oldBytes = nup == null ? 0 : nup.length();
        p.freeBytes = freeBytes;
        List<String> gone = new ArrayList<>();
        for (Part f : found) if (f.kind == BASE && f.file != null && f.file.isFile() && p.base == null) p.base = f.file;
        if (p.base == null) gone.add("the base game's file (an .nsp, .nsz, .xci or .xcz file)");
        // the update: the one picked in the sheet, else the newest found; never older than the one the .nup has
        long had = -1;
        for (Part i : inside) if (i.kind == UPDATE) had = Math.max(had, i.version);
        Part up = null;
        for (Part f : found) {
            if (f.kind != UPDATE || f.file == null || !f.file.isFile()) continue;
            if (chosenUpdate != null && f.file.equals(chosenUpdate)) { up = f; break; }
            if (up == null || f.version > up.version) up = f;
        }
        if (up == null && had >= 0) gone.add("the update the .nup has (" + version(had) + ")");
        else if (up != null && had >= 0 && up.version != 0 && up.version < had)
            gone.add("an update as new as the one the .nup has (" + version(had) + "; " + up.file.getName() + " is " + version(up.version) + ")");
        else if (up != null) { p.update = up.file; p.updateVersion = up.version; }
        // the add-on content: every one the .nup has, and whatever else was found
        List<String> have = new ArrayList<>();
        for (Part f : found) {
            if (f.kind != DLC || f.file == null || !f.file.isFile()) continue;
            if (!p.dlcs.contains(f.file)) p.dlcs.add(f.file);
            have.add(f.titleId);
        }
        List<String> lost = new ArrayList<>();
        for (Part i : inside) if (i.kind == DLC && !have.contains(i.titleId) && !lost.contains(i.titleId)) lost.add(i.titleId);
        if (!lost.isEmpty()) gone.add(lost.size() + " of the DLC the .nup has (" + String.join(", ", lost) + ")");
        for (File f : p.inputs()) p.needBytes += f.length();
        if (!gone.isEmpty()) {
            List<String> where = new ArrayList<>();
            if (roots != null) for (File r : roots) where.add(r.getPath());
            p.missing = "Import again makes the .nup from your game files, and not all of them were found. Missing: " + String.join("; ", gone) + ". Looked in "
                    + (where.isEmpty() ? "no folder (none is set in Settings › Library)" : String.join(", ", where))
                    + ", two folders deep, and at the files added in the game's sheet. Put the files back, or set their folders in Settings › Library, then tap "
                    + "Import again. Nothing was changed.";
        }
        return p;
    }

    /** The question before anything is done: what is deleted, what is kept, what was found */
    public static String confirm(Plan p, String name) {
        return "This makes the .nup of " + name + " again from your game files, with the DLC found now, then builds the game again. It takes about 25 minutes, in the background.\n\n"
                + "Deleted: the .nup that is there now (" + size(p.oldBytes) + ", " + p.nup.getPath() + "). It is replaced only when the new one is complete: if the "
                + "import fails or is stopped, the game stays as it is.\n\n"
                + "Kept: your saves, mods and settings, and the built game until the new build replaces it.\n\n"
                + "Found: " + found(p) + ". These files must stay in their folders until the import is done.";
    }

    /** The dumps in words: "the base game (Game.nsp), update v3 (Update.nsp), 2 DLC" */
    public static String found(Plan p) {
        return "the base game (" + p.base.getName() + "), " + (p.update == null ? "no update" : "update " + version(p.updateVersion) + " (" + p.update.getName() + ")") + ", "
                + (p.dlcs.isEmpty() ? "no DLC" : p.dlcs.size() + " DLC");
    }

    /** The new file does not fit beside the old one: how much is needed, and the second choice when deleting the old one first makes room */
    public static String lowSpace(Plan p) {
        File dir = p.nup.getParentFile();
        String s = "Import again keeps the old .nup until the new one is complete, so both are on the storage for a while. The new one needs about " + size(p.needBytes)
                + " free in " + (dir == null ? "its folder" : dir.getPath()) + ", and " + size(p.freeBytes) + " is free now. Free up " + size(p.needBytes - p.freeBytes)
                + " and tap Import again. Nothing was changed.";
        if (p.fitsWithoutOld()) s += "\n\nOr delete the old .nup first (" + size(p.oldBytes) + "): then there is room. The risk: if the import fails or is stopped, the "
                + "game has no .nup and cannot be played until a build from its sheet has made one.";
        return s;
    }

    /** The second choice's own question: the old file goes before the new one is made */
    public static String deleteFirst(Plan p, String name) {
        return p.nup.getPath() + " (" + size(p.oldBytes) + ") is deleted before the new one is made. If the import fails or is stopped, " + name + " cannot be played "
                + "until a build from its sheet has made the .nup again. Your saves, mods and settings are kept.";
    }

    /** A version as the game sheet writes it: the Switch form when it is one (upper 16 bits = release), else the number */
    static String version(long v) { return v == 0 ? "v0" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }

    /** A size in words: "6.2 GB", "310 MB" */
    public static String size(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes == Long.MAX_VALUE) return "an unknown amount";
        return bytes >= 1L << 30 ? String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30)) : String.format(Locale.US, "%.0f MB", Math.ceil(bytes / (double) (1L << 20)));
    }
}
