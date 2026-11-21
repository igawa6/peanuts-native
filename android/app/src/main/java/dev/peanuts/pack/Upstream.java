package dev.peanuts.pack;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Upstream's launcher classes that {@link PackImport} works with (dev.nxnative.android: PackZip, Pack, BuildCheck, SaveStore, PackUi, SaveUi, Game, GameList,
 * Folders, Prefs, Job, DumpCheck; all package-private) reached through reflection, as dev.peanuts.build.Upstream does, so no upstream file needs a hook. Every
 * name here is one of upstream's; peanuts/tests/test_first_import.py checks that each still exists with these parameters. An IOException upstream throws comes
 * through as itself (its sentence is the player's), anything else a rename or a changed signature causes as a ReflectiveOperationException.
 */
final class Upstream {
    private static final String PKG = "dev.nxnative.android.";

    private Upstream() {}

    private static Class<?> k(String simple) throws ClassNotFoundException { return Class.forName(PKG + simple); }

    private static Object call(Class<?> k, Object self, String name, Class<?>[] types, Object... args) throws IOException, ReflectiveOperationException {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(self, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new ReflectiveOperationException(name + ": " + c, c);
        }
    }

    private static Object get(Object self, String field) throws ReflectiveOperationException {
        Field f = self.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(self);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    // ---- PackZip: the import's own steps ----

    /** PackZip.titleOf(source): the title ID a zip's or a pack folder's pack.tsv names, upper case */
    static String titleOf(File source) throws IOException, ReflectiveOperationException {
        return str(call(k("PackZip"), null, "titleOf", new Class<?>[] {File.class}, source));
    }

    /** PackZip.stage(source, packs, want): the pack unpacked or copied into &lt;packs&gt;/&lt;title ID&gt;.partial and checked; the Pack (its dir is the .partial folder) */
    static Object stage(File source, File packs, String want) throws IOException, ReflectiveOperationException {
        return call(k("PackZip"), null, "stage", new Class<?>[] {File.class, File.class, String.class}, source, packs, want);
    }

    /** PackZip.swapIn(part, target): the checked .partial folder in place of target, the pack that was there kept until the new one has its name */
    static void swapIn(File part, File target) throws IOException, ReflectiveOperationException {
        call(k("PackZip"), null, "swapIn", new Class<?>[] {File.class, File.class}, part, target);
    }

    /** SaveStore.deleteTree(f) */
    static void deleteTree(File f) throws ReflectiveOperationException {
        try { call(k("SaveStore"), null, "deleteTree", new Class<?>[] {File.class}, f); } catch (IOException e) { /* it throws none */ }
    }

    // ---- Pack and its checks ----

    /** A Pack's dir */
    static File packDir(Object pack) throws ReflectiveOperationException { return (File) get(pack, "dir"); }

    /** A Pack's version (pack.tsv's version row) */
    static String packVersion(Object pack) throws ReflectiveOperationException { return str(get(pack, "version")); }

    /** BuildCheck.build(pack, game): {kind (OK, OTHER_BUILD, MISSING, UNREADABLE, UNSUPPORTED, ROMFS), message, detail} */
    static String[] buildCheck(Object pack, File game) throws IOException, ReflectiveOperationException {
        Object r = call(k("BuildCheck"), null, "build", new Class<?>[] {k("Pack"), File.class}, pack, game);
        return new String[] {((Enum<?>) get(r, "kind")).name(), str(get(r, "message")), str(get(r, "detail"))};
    }

    /** Pack.libraryProblem(dir, titleId): null when the pack has this app's shader library, else why not in a sentence */
    static String libraryProblem(File dir, String titleId) throws IOException, ReflectiveOperationException {
        return str(call(k("Pack"), null, "libraryProblem", new Class<?>[] {File.class, String.class}, dir, titleId));
    }

    /** Whether the pack's library is an older one the host plays while the app updates it (Pack.playableLibrary, Pack.legacyLibrary) */
    static boolean olderLibrary(File dir, String titleId) throws IOException, ReflectiveOperationException {
        Class<?> p = k("Pack");
        return call(p, null, "playableLibrary", new Class<?>[] {File.class, String.class}, dir, titleId) != null
                || call(p, null, "legacyLibrary", new Class<?>[] {File.class, String.class}, dir, titleId) != null;
    }

    // ---- Who else works with the pack folder ----

    /** PackUi.busy(titleId): an export or import of this game's pack on its Storage tab is working */
    static boolean packUiBusy(String titleId) throws IOException, ReflectiveOperationException {
        return Boolean.TRUE.equals(call(k("PackUi"), null, "busy", new Class<?>[] {String.class}, titleId));
    }

    private static Object game(String titleId, String name, String gameDir, String packDir) throws ReflectiveOperationException {
        Constructor<?> ctor = k("Game").getDeclaredConstructor(String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(titleId, name, gameDir, packDir);
    }

    /** SaveUi.gameRunning(c, game): the game's save is in use, so a game of this title runs (the save folder is the title's: Folders.save) */
    static boolean gameRunning(Context c, String titleId, String name, String gameDir, String packDir) throws IOException, ReflectiveOperationException {
        Object g = game(titleId, name, gameDir, packDir);
        return Boolean.TRUE.equals(call(k("SaveUi"), null, "gameRunning", new Class<?>[] {Context.class, k("Game")}, c, g));
    }

    /** The launcher's list rows of one title (Game.load): {name, gameDir, packDir} each */
    static List<String[]> listed(Context c, String titleId) throws IOException, ReflectiveOperationException {
        List<String[]> out = new ArrayList<>();
        for (Object g : (List<?>) call(k("Game"), null, "load", new Class<?>[] {Context.class}, c))
            if (titleId.equalsIgnoreCase(str(get(g, "titleId")))) out.add(new String[] {str(get(g, "name")), str(get(g, "gameDir")), str(get(g, "packDir"))});
        return out;
    }

    /** Game.put(c, game): the game into the launcher's list with this pack (replacing the entry of the same file or the same title and build) */
    static void addGame(Context c, String titleId, String name, String gameDir, String packDir) throws IOException, ReflectiveOperationException {
        Class<?> game = k("Game");
        call(game, null, "put", new Class<?>[] {Context.class, game}, c, game(titleId, name, gameDir, packDir));
    }

    /** GameList.samePath(a, b): the same path once both are canonical */
    static boolean samePath(String a, String b) throws IOException, ReflectiveOperationException {
        return Boolean.TRUE.equals(call(k("GameList"), null, "samePath", new Class<?>[] {String.class, String.class}, a, b));
    }

    // ---- A pack folder picked in Folders (it wins over the imported pack at the next build: Folders.packFor) ----

    /** Folders.pickedPack(c, id): the pack folder picked for this title, null when none */
    static String pickedPack(Context c, String titleId) throws IOException, ReflectiveOperationException {
        return str(call(k("Folders"), null, "pickedPack", new Class<?>[] {Context.class, String.class}, c, titleId));
    }

    /** Folders.pickedPack(c, id, null): the title's picked pack folder let go (the folder stays on the storage as it is) */
    static void forgetPickedPack(Context c, String titleId) throws IOException, ReflectiveOperationException {
        call(k("Folders"), null, "pickedPack", new Class<?>[] {Context.class, String.class, String.class}, c, titleId, null);
    }

    /** Prefs.folder(c, "pack"): the pack folder picked last (the next pick opens there) */
    static String lastPackFolder(Context c) throws IOException, ReflectiveOperationException {
        return str(call(k("Prefs"), null, "folder", new Class<?>[] {Context.class, String.class}, c, "pack"));
    }

    /** Prefs.folder(c, "pack", null) */
    static void forgetLastPackFolder(Context c) throws IOException, ReflectiveOperationException {
        call(k("Prefs"), null, "folder", new Class<?>[] {Context.class, String.class, String.class}, c, "pack", null);
    }

    // ---- Prepare jobs (the on-device packer's job.json) ----

    /** DumpCheck.workOf(game, titleId): &lt;the game file's folder&gt;/&lt;title ID&gt;-work, where its job lives */
    static File workOf(File game, String titleId) throws IOException, ReflectiveOperationException {
        return (File) call(k("DumpCheck"), null, "workOf", new Class<?>[] {File.class, String.class}, game, titleId);
    }

    /** Job.fileOf(work): &lt;work&gt;/ondevice/job.json */
    static File jobFileOf(File work) throws IOException, ReflectiveOperationException {
        return (File) call(k("Job"), null, "fileOf", new Class<?>[] {File.class}, work);
    }

    /** Job.remembered(c): the job file Home's Prepare card shows, null when none */
    static File rememberedJob(Context c) throws IOException, ReflectiveOperationException {
        return (File) call(k("Job"), null, "remembered", new Class<?>[] {Context.class}, c);
    }

    /** Job.read(f): {state, pack folder} of a job file; IOException when it is not a job of this app's format */
    static String[] job(File f) throws IOException, ReflectiveOperationException {
        Object j = call(k("Job"), null, "read", new Class<?>[] {File.class}, f);
        return new String[] {str(call(j.getClass(), j, "state", new Class<?>[0])), ((File) call(j.getClass(), j, "pack", new Class<?>[0])).getPath()};
    }
}
