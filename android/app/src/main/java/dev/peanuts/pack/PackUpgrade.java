package dev.peanuts.pack;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.peanuts.Bridge;

/**
 * What a pack made by an earlier build of the app gets at a game's start, without a rebuild. One thing now: main.orig.bin, the game's own bytes of the code the
 * rewriter changed ({@link OrigFill}), which packs have only had since 2026-11-14. Without it the companion runtime refuses a package's load plan (the helper that
 * lets a companion call the game's own functions: Echoes of Wisdom's quick slots, Breath of the Wild, Pokemon Sword), and the companion presses buttons instead,
 * with nothing on the screen saying so.
 * <p>
 * dev.peanuts.Companion.start calls {@link #atGameStart} from the game screen's onCreate, before the host starts. The order that makes the first start count:
 * the unfinished file (main.orig.bin.part) is made right there, on the caller's thread; the work runs on a thread of its own, so the game starts as always; the
 * host's companion runtime does not start while that file is there and fresh (dm/peanuts/peanuts.c rt_orig_pending), and reads main.orig.bin when it does
 * (rt_orig_load). Work that takes more than a minute, or a pack folder the app cannot write, leaves the first start as it was and the log says so.
 * <p>
 * When the file cannot be made (no main.changed.tsv, the game's file gone or of another version, a mod's main), the reason is logged and kept per title for the
 * game's Companion section ({@link #notice}), until the pack has the file.
 */
public final class PackUpgrade {
    // PREFS: a file of its own. The game's process writes it, and the launcher keeps "peanuts" (its folders and choices) in memory and writes it whole
    private static final String TAG = "peanuts", PREFS = "peanuts_pack", KEY = "origfill_", UPSTREAM = "dev.nxnative.android.";
    private static final AtomicBoolean ran = new AtomicBoolean();

    private PackUpgrade() {}

    /** The game screen was made (its intent names the game, the pack and the title); the host has not started. Once per process */
    public static void atGameStart(Activity game) {
        if (!ran.compareAndSet(false, true)) return;
        String g = game.getIntent().getStringExtra("game"), p = game.getIntent().getStringExtra("pack"), id = game.getIntent().getStringExtra("title_id");
        if (g == null || p == null || id == null) return;
        Context app = game.getApplicationContext();
        File pack = new File(p), part = new File(pack, "main" + OrigFill.PART);
        if (new File(pack, "main.orig.bin").isFile()) {   // every pack since 2026-11-14, and every later start of an older one
            part.delete();
            note(app, id, null, null);
            return;
        }
        if (!new File(pack, "main.patched.bin").isFile()) return;   // not a pack: the launcher says so
        boolean held = false;
        if (new File(pack, "main.changed.tsv").isFile()) {
            try { new FileOutputStream(part).close(); held = true; } catch (IOException e) { /* the work below reports the folder */ }
        }
        final boolean waits = held;
        Thread t = new Thread(() -> fill(app, id, new File(g), pack, part, waits), "peanuts-origfill");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void fill(Context app, String id, File game, File pack, File part, boolean waits) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            String[] label = {null};
            OrigFill.Module main = main(game, pack, label);
            long n = OrigFill.fill(pack, "main", main, label[0]);
            long ms = SystemClock.elapsedRealtime() - t0;
            boolean late = Bridge.rtStateSafe() != Bridge.RT_PENDING;
            if (n < 0) Log.i(TAG, "pack " + id + ": main.orig.bin is there");
            else Log.i(TAG, "pack " + id + ": main.orig.bin made from the game's file " + label[0] + " (" + n + " bytes of the code the rewriter changed, " + ms
                    + " ms): the pack was built before the app kept them"
                    + (late ? "; the companion runtime had started already" + (waits ? " (the work took too long)" : "") + ", so it is used from the next start"
                            : "; the companion runtime reads it at this start"));
            note(app, id, null, null);
        } catch (IOException | ReflectiveOperationException | RuntimeException | OutOfMemoryError e) {
            part.delete();
            String why = e instanceof OutOfMemoryError ? "the app ran out of memory reading the game's main" : e.getMessage() == null ? e.toString() : e.getMessage();
            Log.w(TAG, "pack " + id + ": main.orig.bin not made: " + why + ". Companions read main's code as rewritten and a companion's direct actions (its load plan) stay off"
                    + " until the game is built again");
            note(app, id, pack, why);
        }
    }

    /**
     * The game's main of the pack's version, through upstream's own readers (Pack.gameVersion, BuildCheck.mod: a dump folder's file, or a .nup's, read whole and
     * checked against its SHA-256). label[0] gets its name for the log
     */
    private static OrigFill.Module main(File game, File pack, String[] label) throws IOException, ReflectiveOperationException {
        Class<?> packK = Class.forName(UPSTREAM + "Pack");
        Object pk = call(packK, null, "read", new Class<?>[] {File.class}, pack);
        String version = String.valueOf(call(packK, pk, "gameVersion", new Class<?>[] {File.class}, game));
        Object mod = call(Class.forName(UPSTREAM + "BuildCheck"), null, "mod", new Class<?>[] {File.class, String.class, String.class}, game, version, "main");
        label[0] = String.valueOf(get(mod, "label"));
        Object error = get(mod, "error"), file = get(mod, "file"), nup = get(mod, "nup"), node = get(mod, "node");
        if (error != null) throw new IOException(error.toString());
        if (file != null) {
            File f = (File) file;
            if (!f.isFile()) throw new IOException("the game's file is not there (" + f + ")");
            return () -> new BufferedInputStream(new FileInputStream(f), 1 << 16);
        }
        if (nup == null || node == null || Boolean.TRUE.equals(get(node, "dir"))) throw new IOException("the game has no " + label[0] + " (" + game.getName() + ")");
        byte[] all = (byte[]) call(nup.getClass(), nup, "read", new Class<?>[] {node.getClass(), long.class}, node, -1L);
        return () -> new ByteArrayInputStream(all);
    }

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

    // ---- What the game's Companion section says ----

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS); }

    /** Keeps why the title's pack has no main.orig.bin (pack and why null: nothing to say any more) */
    private static void note(Context c, String id, File pack, String why) {
        SharedPreferences p = prefs(c);
        String key = KEY + id.toUpperCase(java.util.Locale.ROOT);
        if (pack == null) { if (p.contains(key)) p.edit().remove(key).commit(); }
        else p.edit().putString(key, pack.getPath() + "\n" + why).commit();
    }

    /**
     * {title, sentence} for the Companion section of a game whose pack could not get main.orig.bin at its last start, null when there is nothing to say (no such
     * start, or the pack has the file since: a rebuild). packageDir is the installed companion's folder: one with a load plan loses its direct actions, any
     * other may wait on code it cannot recognise. Reads two small files: not for every frame
     */
    public static String[] notice(Context c, String titleId, File packageDir) {
        if (titleId == null || packageDir == null) return null;
        String v = prefs(c).getString(KEY + titleId.toUpperCase(java.util.Locale.ROOT), null);
        int nl = v == null ? -1 : v.indexOf('\n');
        if (nl <= 0 || new File(v.substring(0, nl), "main.orig.bin").isFile()) return null;
        // (v's second line, why the file is missing, is in the log from when it was not made: not for the notice)
        boolean plan = false;
        File manifest = new File(packageDir, "dualscreen/manifest.json");
        try (InputStream in = new FileInputStream(manifest)) {
            byte[] b = new byte[1 << 16];
            int n = 0;
            for (int k; n < b.length && (k = in.read(b, n, b.length - n)) > 0; ) n += k;
            plan = new String(b, 0, n, StandardCharsets.UTF_8).contains("\"load_plan\"");
        } catch (IOException e) { /* no manifest to read: the general sentence */ }
        return plan
                ? new String[] {"Direct actions are off", "This companion can act in the game directly, but the game's build is missing a file it needs for that, so it presses buttons instead. Rebuild the game to turn direct actions on."}
                : new String[] {"Built before companions could read the game", "Parts of this companion may stay empty because the game's build is missing a file they need. Rebuild the game to fix that."};
    }
}
