package dev.peanuts.build;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Upstream's on-device classes (dev.nxnative.android: DumpCheck, Job, AppStorage, PackService, Pack, PackCheck, Folders, Game, Prefs; all package-private) reached
 * through reflection, as dev.peanuts.Games does for the game list, so no upstream file needs a hook. Every name here is one of upstream's; a rename shows up
 * as a ReflectiveOperationException in the build's error, not as a compile error.
 */
final class Upstream {
    static final String PKG = "dev.nxnative.android.";
    static final String ACTION_START = "dev.nxnative.android.pack.START", EXTRA_JOB = "job";
    static final int MSG_REGISTER = 1, MSG_UNREGISTER = 2, MSG_STATE = 3;

    private Upstream() {}

    static Class<?> k(String simple) throws ClassNotFoundException { return Class.forName(PKG + simple); }

    static Object call(Class<?> k, Object self, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(self, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new ReflectiveOperationException(name + ": " + c, c);
        }
    }

    static Object get(Object self, String field) throws ReflectiveOperationException {
        Field f = self.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(self);
    }

    /** Sets a declared field on self by reflection (the write-side counterpart of {@link #get}) - used by ModdedBuild/Prepare.runModded to redirect a
     *  freshly made DumpCheck.Result's "work" field at the variant's own work folder before Job.create reads it, instead of DumpCheck's own fixed
     *  &lt;dump&gt;/../&lt;id&gt;-work convention, so the modded job never shares (and so never fights over) the normal job's job.json. */
    static void set(Object self, String field, Object value) throws ReflectiveOperationException {
        Field f = self.getClass().getDeclaredField(field);
        f.setAccessible(true);
        try { f.set(self, value); } catch (IllegalAccessException e) { throw new ReflectiveOperationException(field, e); }
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    // ---- DumpCheck ----

    /** DumpCheck.check(dump, needFree, anyBuild): the Result object (fields dump, work, titleId, error, version, otherBuild, untested, detail) */
    static Object dumpCheck(File dump, boolean needFree, boolean anyBuild) throws ReflectiveOperationException {
        return call(k("DumpCheck"), null, "check", new Class<?>[] {File.class, boolean.class, boolean.class}, dump, needFree, anyBuild);
    }

    static File workOf(File dump, String titleId) throws ReflectiveOperationException {
        return (File) call(k("DumpCheck"), null, "workOf", new Class<?>[] {File.class, String.class}, dump, titleId);
    }

    // ---- Pack / titles ----

    /** Pack.titleIdOf(game): a .nup's or a dump folder's base title id */
    static String titleIdOf(File game) throws ReflectiveOperationException {
        return str(call(k("Pack"), null, "titleIdOf", new Class<?>[] {File.class}, game));
    }

    /** Pack.read(dir), or null when it does not read */
    static Object packRead(File dir) {
        try { return call(k("Pack"), null, "read", new Class<?>[] {File.class}, dir); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    static File appPack(Context c, String titleId) throws ReflectiveOperationException {
        return (File) call(k("AppStorage"), null, "pack", new Class<?>[] {Context.class, String.class}, c, titleId);
    }

    static File packFor(Context c, File game, String titleId) throws ReflectiveOperationException {
        return (File) call(k("Folders"), null, "packFor", new Class<?>[] {Context.class, File.class, String.class}, c, game, titleId);
    }

    /** PackCheck.blocking(c, pack, dump): why the pack's code cannot run with this dump, null when it fits */
    static String packBlocking(Context c, Object pack, File dump) throws ReflectiveOperationException {
        Object s = call(k("PackCheck"), null, "blocking", new Class<?>[] {Context.class, k("Pack"), File.class}, c, pack, dump);
        return s == null ? null : s.toString();
    }

    /** PackCheck.code(c, pack, dump): TRUE when the pack's rewritten code is this APK's rewriter's (its tools, the profile's patches.py and build rows), FALSE when it
     *  is out of date, null when it cannot be judged */
    static Boolean packCode(Context c, Object pack, File dump) throws ReflectiveOperationException {
        Object s = call(k("PackCheck"), null, "code", new Class<?>[] {Context.class, k("Pack"), File.class}, c, pack, dump);
        return s instanceof Boolean ? (Boolean) s : null;
    }

    /** PackCheck.shaders(packDir, titleId): the shader library's problem, null when current */
    static String packShaders(File packDir, String titleId) throws ReflectiveOperationException {
        Object s = call(k("PackCheck"), null, "shaders", new Class<?>[] {File.class, String.class}, packDir, titleId);
        return s == null ? null : s.toString();
    }

    // ---- Job ----

    static File jobFileOf(File work) throws ReflectiveOperationException {
        return (File) call(k("Job"), null, "fileOf", new Class<?>[] {File.class}, work);
    }

    static Object jobRead(File f) throws ReflectiveOperationException {
        return call(k("Job"), null, "read", new Class<?>[] {File.class}, f);
    }

    static Object jobCreate(Object dumpResult, File appPack) throws ReflectiveOperationException {
        return call(k("Job"), null, "create", new Class<?>[] {k("DumpCheck$Result"), File.class}, dumpResult, appPack);
    }

    static Object jobAdopt(Object dumpResult, File packDir, boolean code, boolean shaders) throws ReflectiveOperationException {
        return call(k("Job"), null, "adopt", new Class<?>[] {k("DumpCheck$Result"), File.class, boolean.class, boolean.class}, dumpResult, packDir, code, shaders);
    }

    static void jobSave(Object job) throws ReflectiveOperationException { call(job.getClass(), job, "save", new Class<?>[0]); }

    /** Job.tryAnyway(on): the job goes on with its checks relaxed (an untested game version, shaders the profile does not list) */
    static void jobTryAnyway(Object job, boolean on) throws ReflectiveOperationException { call(job.getClass(), job, "tryAnyway", new Class<?>[] {boolean.class}, on); }

    static boolean jobTryAnyway(Object job) throws ReflectiveOperationException { return Boolean.TRUE.equals(call(job.getClass(), job, "tryAnyway", new Class<?>[0])); }

    /** Job.modArgs(joinedArgv): a modded variant job's extra rewrite-step argv, one token per line (dev.peanuts.build.ModdedBuild.rewriteArgv) */
    static void jobModArgs(Object job, String joinedArgv) throws ReflectiveOperationException {
        call(job.getClass(), job, "modArgs", new Class<?>[] {String.class}, joinedArgv);
    }

    /** Job.reuseShaders(normalPackShaders): skips split/shaders on a modded variant job and points its shader cache at the normal pack's own library */
    static void jobReuseShaders(Object job, File normalPackShaders) throws ReflectiveOperationException {
        call(job.getClass(), job, "reuseShaders", new Class<?>[] {File.class}, normalPackShaders);
    }

    // ---- Prefs: the game's own env lines (its Game settings) ----

    static String gameEnv(Context c, String titleId) throws ReflectiveOperationException {
        return str(call(k("Prefs"), null, "gameEnv", new Class<?>[] {Context.class, String.class}, c, titleId));
    }

    static void gameEnv(Context c, String titleId, String text) throws ReflectiveOperationException {
        call(k("Prefs"), null, "gameEnv", new Class<?>[] {Context.class, String.class, String.class}, c, titleId, text);
    }

    /** Job.fix(): what a failed job's card offers (PrepareText.FIX_*: "retry", "anyway", "none", "update") */
    static String jobFix(Object job) throws ReflectiveOperationException { return str(call(job.getClass(), job, "fix", new Class<?>[0])); }

    static void jobRemember(Context c, File jobFile) throws ReflectiveOperationException {
        call(k("Job"), null, "remember", new Class<?>[] {Context.class, File.class}, c, jobFile);
    }

    static File jobFile(Object job) throws ReflectiveOperationException { return (File) get(job, "file"); }
    static String jobState(Object job) throws ReflectiveOperationException { return str(call(job.getClass(), job, "state", new Class<?>[0])); }
    static String jobName(Object job) throws ReflectiveOperationException { return str(call(job.getClass(), job, "name", new Class<?>[0])); }
    static File jobDump(Object job) throws ReflectiveOperationException { return (File) call(job.getClass(), job, "dump", new Class<?>[0]); }
    static File jobPack(Object job) throws ReflectiveOperationException { return (File) call(job.getClass(), job, "pack", new Class<?>[0]); }
    static long jobVersion(Object job) throws ReflectiveOperationException { return ((Number) call(job.getClass(), job, "version", new Class<?>[0])).longValue(); }
    static long jobCreated(Object job) throws ReflectiveOperationException { return ((Number) call(job.getClass(), job, "created", new Class<?>[0])).longValue(); }

    /** The intent Ondevice's panel sends to run a job: PackService ACTION_START with the job file (the ":pack" process) */
    static Intent packStart(Context c, File jobFile) throws ClassNotFoundException {
        return new Intent(c, k("PackService")).setAction(ACTION_START).putExtra(EXTRA_JOB, jobFile.getPath());
    }

    static Intent packBind(Context c) throws ClassNotFoundException { return new Intent(c, k("PackService")); }

    // ---- The game list (Ondevice.Panel.addGame, once a job is done) ----

    /** Whether the launcher's list has a game with this dump and pack */
    static boolean listed(Context c, File dump, File pack) throws ReflectiveOperationException {
        Class<?> game = k("Game");
        for (Object g : (List<?>) call(game, null, "load", new Class<?>[] {Context.class}, c)) {
            if (samePath(str(get(g, "gameDir")), dump.getPath()) && samePath(str(get(g, "packDir")), pack.getPath())) return true;
        }
        return false;
    }

    /** The launcher's list rows of one title: {name, gameDir, packDir} each (Share build log finds the game's work folders from them) */
    static List<String[]> listedGames(Context c, String titleId) throws ReflectiveOperationException {
        List<String[]> out = new java.util.ArrayList<>();
        Class<?> game = k("Game");
        for (Object g : (List<?>) call(game, null, "load", new Class<?>[] {Context.class}, c))
            if (titleId.equalsIgnoreCase(str(get(g, "titleId")))) out.add(new String[] {str(get(g, "name")), str(get(g, "gameDir")), str(get(g, "packDir"))});
        return out;
    }

    /** Prefs.buildTag(c): the build tag Home's header and every log's first line show */
    static String buildTag(Context c) {
        try { return str(call(k("Prefs"), null, "buildTag", new Class<?>[] {Context.class}, c)); } catch (ReflectiveOperationException | RuntimeException e) { return "?"; }
    }

    /** CpuCores.shaderWorkers(): the packer's worker count by cores (an APK without the on-device packer: 4) */
    static int cpuWorkers() {
        try { return ((Number) call(k("CpuCores"), null, "shaderWorkers", new Class<?>[0])).intValue(); } catch (ReflectiveOperationException | RuntimeException e) { return 4; }
    }

    static boolean samePath(String a, String b) {
        try {
            return (Boolean) call(k("GameList"), null, "samePath", new Class<?>[] {String.class, String.class}, a, b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return a != null && a.equals(b);
        }
    }

    /**
     * Ondevice.Panel.addGame without the panel: the finished job's game into the list with its pack (Game.put), the job marked added ("added" in the ondevice prefs,
     * so the panel does not add it again), a picked pack folder moved to the new pack (Folders.pickedPack). A simulated pack (the fake engine) is not listed.
     * Returns the game's name, or null when nothing was added.
     */
    static String addGame(Context c, Object job) throws ReflectiveOperationException {
        File jf = jobFile(job);
        prefs(c, "ondevice").edit().putString("added", jf.getPath() + "@" + jobCreated(job)).apply();
        File dump = jobDump(job), pack = jobPack(job);
        if (listed(c, dump, pack)) return jobName(job);
        Object p = packRead(pack);
        if (p == null) return null;
        if (Boolean.TRUE.equals(get(p, "simulated"))) return null;
        String name = str(get(p, "name"));
        if (name.isEmpty()) name = jobName(job);
        String titleId = str(get(p, "titleId"));
        if (titleId.isEmpty()) titleId = str(call(job.getClass(), job, "titleId", new Class<?>[0]));
        Class<?> game = k("Game");
        Constructor<?> ctor = game.getDeclaredConstructor(String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        Object g = ctor.newInstance(titleId, name, dump.getPath(), pack.getPath());
        call(game, null, "put", new Class<?>[] {Context.class, game}, c, g);
        try {
            Class<?> folders = k("Folders");
            Object picked = call(folders, null, "pickedPack", new Class<?>[] {Context.class, String.class}, c, titleId);
            if (picked != null && !picked.toString().equals(pack.getPath())) call(folders, null, "pickedPack", new Class<?>[] {Context.class, String.class, String.class}, c, titleId, pack.getPath());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the picked pack folder is a convenience; the game is listed
        }
        return name;
    }

    /** Prefs.open(c, name): MODE_MULTI_PROCESS, shared with the ":pack" process */
    static android.content.SharedPreferences prefs(Context c, String name) {
        try {
            return (android.content.SharedPreferences) call(k("Prefs"), null, "open", new Class<?>[] {Context.class, String.class}, c, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS);
        }
    }
}
