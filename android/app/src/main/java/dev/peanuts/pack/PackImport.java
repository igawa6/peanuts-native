package dev.peanuts.pack;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Import pack… before a game's first build (GitHub issue #44; the rules and words are {@link FirstImport}'s): a .zip made by Export pack…, or a pack folder,
 * into the folder the game's next Build uses, through upstream's own import steps (PackZip.stage, then PackZip.swapIn: unpacked into a .partial folder
 * beside the pack folder, checked, and only then renamed in, the pack that was there kept until the new one has its name).
 * <p>
 * Before anything is written: no other import or export of the title works, no game of the title runs, no other list entry plays from the pack folder, no
 * Prepare job of the game is unfinished (the next Build would go on with it and not use the pack), the pack is this game's (pack.tsv's title) and its space
 * fits. After staging: the pack's code fits the game file (a dump folder or .nup: BuildCheck), or its version is the one Build uses (an NSP or XCI not
 * imported yet). Once it is in, a pack folder picked for the title in Folders is let go (it would win at the next Build: Folders.packFor), and a game with
 * a dump folder or .nup gets its list entry (Game.put), so it is ready to play.
 * <p>
 * {@link #run} works on the caller's thread (never the main thread: it copies the whole pack); dev.peanuts.ui.PackImportUi takes the title ({@link #take}) on
 * the main thread with its last check and then runs it, and dev.peanuts.ui.Library starts no build of the title from then until the end ({@link #busy}).
 */
public final class PackImport {
    private static final String TAG = "peanuts";
    private static final Set<String> BUSY = new HashSet<>();

    private PackImport() {}

    /** The other titles a pack may name: the name this app knows the title by (null when it has no profile for it), and where the library has it */
    public interface Titles {
        String name(String titleId);
        FirstImport.Other where(String titleId);
    }

    /** Why the pack did not go in: the sentence for the player (ending in "Nothing was changed.") and the details (folders, build IDs; may be empty) */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        public final String detail;
        Refused(String why, String detail) { this(FirstImport.refusal(why), detail, true); }
        /** said: the whole sentence as the player reads it; unchanged false: no "Nothing was changed." was added, since something was */
        private Refused(String said, String detail, boolean unchanged) { super(said); this.detail = detail == null ? "" : detail; }
    }

    /**
     * Whether a failed swap left the pack folder's old pack aside (PackZip.swapIn moved it to &lt;target&gt;.replaced, and neither the new one nor the old one got
     * the name back): then something was changed, and the player is told where the old pack is
     */
    static boolean aside(File target) { return !target.exists() && new File(target.getPath() + ".replaced").isDirectory(); }

    /** A failure: the plain refusal, or, when the old pack was left aside, the sentence that says so */
    static Refused failed(FirstImport.Game g, String why, String detail) {
        if (!aside(g.target)) return new Refused(why, detail);
        return new Refused(FirstImport.swapFailed(g), "Kept as: " + g.target.getPath() + ".replaced\n" + detail, false);
    }

    /** The pack is in: whether the game got its list entry, what the player reads, the details */
    public static final class Done {
        public final boolean listed;
        public final String text, detail;
        Done(boolean listed, String text, String detail) { this.listed = listed; this.text = text; this.detail = detail; }
    }

    /** Whether an import of this title's pack is working (the sheet's Build and the control interface's build wait for it) */
    public static synchronized boolean busy(String titleId) { return BUSY.contains(key(titleId)); }

    /**
     * Marks an import of the title as working; false when one is. dev.peanuts.ui.PackImportUi takes it on the main thread right after its last check, so no
     * Build of the title starts between that check and the import (Library.startBuild asks {@link #busy} on the main thread too). {@link #run} gives it back
     */
    public static synchronized boolean take(String titleId) { return BUSY.add(key(titleId)); }

    private static synchronized void give(String titleId) { BUSY.remove(key(titleId)); }

    /** The one spelling of a title ID in {@link #BUSY}, whoever asks: upper case, as the library and FirstImport.Game have it */
    private static String key(String titleId) { return titleId.toUpperCase(Locale.ROOT); }

    /** Imports source (a .zip or a pack folder) for g, whose import the caller took ({@link #take}); see the class comment. Gives it back at its end, whatever
     *  happened. Off the main thread */
    public static Done run(Context c, FirstImport.Game g, File source, Titles titles) throws Refused {
        if (!busy(g.titleId)) throw new IllegalStateException("import pack: " + g.titleId + " was not taken");
        try {
            return run0(c, g, source, titles);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "import pack: " + g.titleId + " from " + source + ": " + e, e);
            throw failed(g, "The pack could not be imported: the app ran into an error (see Details)", "Pack folder: " + g.target + "\n" + e);
        } finally {
            give(g.titleId);
        }
    }

    private static Done run0(Context c, FirstImport.Game g, File source, Titles titles) throws Refused, ReflectiveOperationException {
        String where = "Pack folder: " + g.target;
        try {
            before(c, g, where);
            String id = Upstream.titleOf(source);
            if (!id.equals(g.titleId)) throw new Refused(FirstImport.otherTitle(g, id, titles.name(id), titles.where(id)), "The pack's title ID: " + id + "\nThis game's: " + g.titleId);
            long need = size(source), free = freeSpace(g.target);
            if (!FirstImport.fits(need, free)) throw new Refused(FirstImport.lowSpace(need, free), where);
        } catch (IOException e) {
            throw new Refused(e.getMessage(), "Source: " + source + "\n" + where);
        }
        Object pack;
        try {
            pack = Upstream.stage(source, g.target.getParentFile(), g.titleId);   // beside the pack folder: the rename stays on its storage
        } catch (IOException e) {
            throw new Refused(e.getMessage(), "Source: " + source + "\n" + where);
        }
        File part = Upstream.packDir(pack);
        try {
            check(c, g, pack, where);
            if (Upstream.gameRunning(c, g.titleId, g.name, saveKey(g), g.target.getPath())) throw new Refused(FirstImport.running(g), where);   // started meanwhile
            Upstream.swapIn(part, g.target);
        } catch (IOException e) {
            throw failed(g, e.getMessage(), where);
        } finally {
            if (part.exists()) Upstream.deleteTree(part);
        }
        Log.i(TAG, "import pack: " + g.titleId + " from " + source + " into " + g.target);
        return after(c, g, where);
    }

    /** The checks before anything is read from the source: who else works with the pack folder */
    private static void before(Context c, FirstImport.Game g, String where) throws Refused, IOException, ReflectiveOperationException {
        if (Upstream.packUiBusy(g.titleId)) throw new Refused(FirstImport.busy(g), where);
        if (Upstream.gameRunning(c, g.titleId, g.name, saveKey(g), g.target.getPath())) throw new Refused(FirstImport.running(g), where);
        boolean packThere = new File(g.target, "pack.tsv").isFile();
        for (String[] row : Upstream.listed(c, g.titleId)) {   // {name, gameDir, packDir}
            boolean same = g.file != null && Upstream.samePath(row[1], g.file.getPath());   // this game's own entry (not ready): Game.put replaces it
            if (!same && packThere && Upstream.samePath(row[2], g.target.getPath())) throw new Refused(FirstImport.inUse(g), where + "\nThe other copy: " + row[1]);
        }
        if (g.buildFile != null) {   // this game's own job: the next Build resumes it whatever pack folder it writes to (dev.peanuts.build.Prepare, step 2)
            File work = Upstream.workOf(g.buildFile, g.titleId), jf = Upstream.jobFileOf(work);
            String[] j = jf.isFile() ? job(jf) : null;
            if (j != null && !"done".equals(j[0]) && !j[0].isEmpty()) throw new Refused(FirstImport.stoppedBuild(g, "running".equals(j[0])), "Working files: " + work + "\n" + where);
        }
        File remembered = Upstream.rememberedJob(c);   // Home's Prepare card: a job of any game that writes to this pack folder (upstream's import asks the same)
        String[] j = remembered != null && remembered.isFile() ? job(remembered) : null;
        if (j != null && !"done".equals(j[0]) && Upstream.samePath(j[1], g.target.getPath()))
            throw new Refused(FirstImport.stoppedBuild(g, "running".equals(j[0])), "Working files: " + remembered.getParentFile().getParentFile() + "\n" + where);
    }

    /** A job file's {state, pack}, null when it is not one of this app's format (it then writes nothing) */
    private static String[] job(File f) throws ReflectiveOperationException {
        try { return Upstream.job(f); } catch (IOException e) { return null; }
    }

    /** The staged pack against the game: its file's build (a dump folder or .nup), else its version */
    private static void check(Context c, FirstImport.Game g, Object pack, String where) throws Refused, IOException, ReflectiveOperationException {
        String version = Upstream.packVersion(pack);
        if (g.file == null) {
            String bad = FirstImport.versionProblem(g, version);
            if (bad != null) throw new Refused(bad, "The pack's version: " + version + "\n" + where);
            return;
        }
        String[] r = Upstream.buildCheck(pack, g.file);   // {kind, message, detail}
        if (r[0].equals("OK")) return;
        String detail = (r[2] == null || r[2].isEmpty() ? "" : r[2] + "\n") + "Game file: " + g.file + "\n" + where;
        if (r[0].equals("OTHER_BUILD") || r[0].equals("MISSING")) throw new Refused(FirstImport.otherBuild(g, version), detail);
        throw new Refused(r[1], detail);   // UNREADABLE, UNSUPPORTED, ROMFS: upstream's own sentence
    }

    /** The pack is in: a picked pack folder let go, the list entry, the sentence */
    private static Done after(Context c, FirstImport.Game g, String where) {
        String detail = where;
        try {
            String picked = Upstream.pickedPack(c, g.titleId);
            if (picked != null && !Upstream.samePath(picked, g.target.getPath())) {
                Upstream.forgetPickedPack(c, g.titleId);   // Folders.packFor takes a picked folder first: Build would not use the imported pack
                if (picked.equals(Upstream.lastPackFolder(c))) Upstream.forgetLastPackFolder(c);
                detail += "\nThe pack folder picked before (" + picked + ") is kept as it was; the game now uses this one.";
            }
            String lp = Upstream.libraryProblem(g.target, g.titleId);
            boolean older = lp != null && Upstream.olderLibrary(g.target, g.titleId);
            boolean listed = FirstImport.lists(g, lp, older);
            if (listed) Upstream.addGame(c, g.titleId, g.name, g.file.getPath(), g.target.getPath());
            return new Done(listed, FirstImport.imported(g, listed, lp, older), detail);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            // the pack is in (so never "Nothing was changed" from here); what failed is the bookkeeping after it, and Build finds the pack where it is
            Log.w(TAG, "import pack: " + g.titleId + ": after the import: " + e, e);
            return new Done(false, FirstImport.importedOnly(g), detail + "\n" + e);
        }
    }

    /** The game path the save lookup goes by (Folders.save: the title's save, else an older one beside the game file); the pack folder for a game Build
     *  still imports (no older save sits beside a file that is not made yet) */
    private static String saveKey(FirstImport.Game g) { return g.file != null ? g.file.getPath() : g.target.getPath(); }

    private static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += size(x);
        return n;
    }

    /** The usable space where the pack goes: the nearest folder of its path that is there (a build folder's &lt;title ID&gt; may not be yet) */
    private static long freeSpace(File target) {
        for (File f = target; f != null; f = f.getParentFile()) if (f.isDirectory()) return f.getUsableSpace();
        return 0;
    }
}
