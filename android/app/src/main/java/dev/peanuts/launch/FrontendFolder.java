package dev.peanuts.launch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import dev.nxnative.android.BuildConfig;
import dev.peanuts.PeanutsPrefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings › Library's Frontend folder (FolderKinds.FRONTEND, LAUNCHERS.md "Frontend folder", GitHub issue #475): a folder the user picks, where the app
 * keeps one small entry file per game that is ready to play ({@link FrontendEntry}), so a frontend such as Cocoon lists the games on its next rescan and
 * starts them through the launch alias, as a home screen shortcut does. The games are those of Home's list (upstream's Game list) that this app runs.
 * <ul>
 * <li>A game gets its file once it is ready to play (Game.ready: its pack fits). A file stays while its game is on Home, even when the game needs something
 *     again (shaders after an update), so the frontend keeps its entry and play time; the launch then opens Home with the game, as a shortcut does.</li>
 * <li>A game that leaves Home loses its file; a game whose name changed gets its file renamed.</li>
 * <li>Only this app's own files are changed: a file is the app's when its text starts with this app's marker ({@link FrontendEntry#text}). Any other file,
 *     and any subfolder, is never touched; a name another file has is left to it. A list that does not read whole changes nothing.</li>
 * <li>A folder that is turned off or swapped for another loses the app's files; when that cannot be done yet (no All files access, its SD card out), it is
 *     done on a later run.</li>
 * </ul>
 * When it runs: once a process when Home is first drawn ({@link #watch}), whenever Home's list is saved (a build or an import added a game, Remove took one
 * away: the list's preferences tell), after a scan and a build, and in full when the folder is picked. Everything that reads or writes files runs on one thread
 * of its own, so two runs never overlap.
 */
public final class FrontendFolder {
    private static final String TAG = "peanuts";
    private static final String P = "dev.nxnative.android.";
    private static final long SETTLE_MS = 1500;   // a build saves the list a few times in a row: one run after the last
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService ONE = Executors.newSingleThreadExecutor(r -> new Thread(r, "peanuts-frontend-folder"));
    /** Home's list: upstream's Game list, kept in these preferences under "list" (dev.nxnative.android.Game, Prefs.open) */
    private static final String LIST_PREFS = "games", LIST_KEY = "list";

    private static volatile Context app;
    private static SharedPreferences.OnSharedPreferenceChangeListener onList;   // held here: SharedPreferences keeps its listeners only weakly
    private static volatile int entries = -1;     // the entries in the folder after the last run, -1: none ran yet
    private static volatile String problem;      // why the last run could not finish, null when it did
    private static volatile boolean needsAccess; // the last run stopped for want of All files access
    private static volatile Runnable shown;      // the Settings row's refresh, after every run (onRun)
    private static final Runnable settled = () -> { Context c = app; if (c != null) ONE.execute(() -> sync(c)); };

    private FrontendFolder() {}

    /** Home is drawn (Library.fill): from now on every save of Home's list brings the folder up to date, and it is brought up to date once now */
    public static void watch(Context c) {
        if (onList != null) return;
        app = c.getApplicationContext();
        onList = (prefs, key) -> changed(app);   // every add, rename and removal saves the list
        app.getSharedPreferences(LIST_PREFS, Context.MODE_MULTI_PROCESS).registerOnSharedPreferenceChangeListener(onList);
        changed(app);
    }

    /** Something may have changed what the folder should hold (a scan, a build): it is brought up to date once things settle */
    public static void changed(Context c) {
        if (PeanutsPrefs.frontendDir(c) == null && PeanutsPrefs.frontendClear(c).isEmpty()) return;
        app = c.getApplicationContext();
        UI.removeCallbacks(settled);
        UI.postDelayed(settled, SETTLE_MS);
    }

    /** The app is in front again (FolderKinds.FRONTEND's resumed, from LibrarySettings.resume): a run that waited for All files access runs now that it may be there */
    public static void resumed(Context c) {
        if ((needsAccess || !PeanutsPrefs.frontendClear(c).isEmpty()) && Environment.isExternalStorageManager()) changed(c);
    }

    /**
     * Settings: the folder is dir now (null: off). The entries this app wrote in the folder before are taken out of it (on a later run when that cannot be done
     * now), then the new one gets every game's; then runs on the main thread once that is done
     */
    public static void set(Context c, String dir, Runnable then) {
        Context a = c.getApplicationContext();
        app = a;
        String old = PeanutsPrefs.frontendDir(a);
        PeanutsPrefs.frontendDir(a, dir);
        List<String> clear = PeanutsPrefs.frontendClear(a);
        if (old != null && !old.equals(dir) && !clear.contains(old)) clear.add(old);
        if (dir != null) clear.remove(dir);   // picked again: its entries are the folder's again
        PeanutsPrefs.frontendClear(a, clear);
        if (dir == null) { entries = -1; problem = null; needsAccess = false; }
        ONE.execute(() -> {
            sync(a);
            if (then != null) UI.post(then);
        });
    }

    /** Settings shows the row: r runs on the main thread after every run, so its line says what the folder holds now */
    public static void onRun(Runnable r) { shown = r; }

    /** What the last run did, for the Settings row: null when none ran yet or the folder is off */
    public static String state(Context c) {
        if (PeanutsPrefs.frontendDir(c) == null) return null;
        if (problem != null) return problem;
        if (entries < 0) return null;
        return entries == 1 ? "1 game" : entries + " games";
    }

    // ---- The run ----

    /** One game of Home's list that this app runs: its title ID and name, and upstream's Game object (for Game.ready) */
    private static final class Listed {
        final String titleId, name; final Object game;
        Listed(String titleId, String name, Object game) { this.titleId = titleId; this.name = name; this.game = game; }
    }

    /** The folders turned off or swapped are cleared, then the folder is brought up to date with Home's list, then the Settings row is told. On the folder's thread */
    private static void sync(Context c) {
        try {
            clearOld(c);
            syncNow(c);
        } finally {
            Runnable r = shown;
            if (r != null) UI.post(r);
        }
    }

    private static void syncNow(Context c) {
        String dir = PeanutsPrefs.frontendDir(c);
        if (dir == null) return;
        File d = new File(dir);
        if (!Environment.isExternalStorageManager()) {
            needsAccess = true;
            problem = "Waiting for All files access. Allow it, and the games are added.";
            return;
        }
        needsAccess = false;
        if (!d.isDirectory()) { problem = "The folder is not there. Pick it again, or another one."; return; }
        Map<String, Listed> games;
        try {
            games = listed(c);
        } catch (ReflectiveOperationException | JSONException | RuntimeException e) {
            Log.w(TAG, "[frontend] Home's list did not read whole, the folder stays as it is: " + e);
            return;
        }
        String appId = c.getPackageName();
        leftovers(d, appId);
        Map<String, List<File>> ours = ours(d, appId);
        int failed = 0, have = 0;
        // a game that left Home: its entries go
        for (Map.Entry<String, List<File>> e : ours.entrySet()) {
            if (games.containsKey(e.getKey())) continue;
            for (File f : e.getValue()) if (f.delete()) Log.i(TAG, "[frontend] removed " + f.getName());
        }
        for (Listed g : games.values()) {
            String name = FrontendEntry.fileName(g.name, g.titleId);
            File target = new File(d, name);
            List<File> mine = ours.get(g.titleId);
            if (mine != null) {
                // the game's entry is there: under its name now, once (a second copy of it goes)
                File keep = null;
                for (File f : mine) if (f.getName().equals(name)) keep = f;
                if (keep == null) keep = rename(mine.get(0), target);
                for (File f : mine) if (f != keep && !f.getName().equalsIgnoreCase(keep.getName()) && f.delete()) Log.i(TAG, "[frontend] removed the second entry " + f.getName());
                have++;
                continue;
            }
            if (target.exists()) { Log.i(TAG, "[frontend] " + name + " is there and not this app's: left as it is"); continue; }
            if (!ready(g.game)) continue;   // not playable yet: its entry comes once it is
            if (write(target, FrontendEntry.text(appId, g.titleId))) { have++; Log.i(TAG, "[frontend] added " + name); }
            else failed++;
        }
        entries = have;
        problem = failed == 0 ? null : "Could not add " + (failed == 1 ? "1 game" : failed + " games") + " to the folder. Check that it can be written to, or pick another one.";
    }

    /** The folders turned off or swapped for another lose this app's entries; a folder that cannot be cleared yet waits for a later run */
    private static void clearOld(Context c) {
        List<String> left = PeanutsPrefs.frontendClear(c);
        if (left.isEmpty() || !Environment.isExternalStorageManager()) return;
        List<String> still = new ArrayList<>();
        for (String p : left) if (!clear(c, new File(p))) still.add(p);
        if (still.size() != left.size()) PeanutsPrefs.frontendClear(c, still);
    }

    /** Takes this app's entries out of a folder; false when it cannot be read now (its storage is not there), true when done or there is no such folder */
    private static boolean clear(Context c, File d) {
        if (!d.isDirectory()) return !d.exists() && d.getParentFile() != null && d.getParentFile().isDirectory();   // gone from storage that is there
        String appId = c.getPackageName();
        leftovers(d, appId);
        for (List<File> fs : ours(d, appId).values())
            for (File f : fs) if (f.delete()) Log.i(TAG, "[frontend] removed " + f.getName() + " (the folder is no longer the frontend folder)");
        return true;
    }

    /**
     * The games of Home's list this APK runs, by title ID (the first card of a title: the one a title ID launch starts), in the list's order. The list is read
     * strictly, card by card as Game.fromJson reads one: a list that does not read whole (Game.load would give the cards before the damage, or none) throws, so
     * no entry is taken away for a game that is still on Home
     */
    private static Map<String, Listed> listed(Context c) throws ReflectiveOperationException, JSONException {
        Class<?> k = Class.forName(P + "Game");
        Method fromJson = k.getDeclaredMethod("fromJson", JSONObject.class);
        fromJson.setAccessible(true);
        JSONArray list = new JSONArray(c.getSharedPreferences(LIST_PREFS, Context.MODE_MULTI_PROCESS).getString(LIST_KEY, "[]"));
        Map<String, String> profiles = profiles();
        Map<String, Listed> out = new LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            Object g = fromJson.invoke(null, o);   // throws as Game.load would stop: a card without its title ID, name, game or pack
            String tid = o.getString("title_id").toUpperCase(Locale.ROOT);
            if (!profiles.containsKey(tid) || out.containsKey(tid)) continue;
            String n = o.getString("name");
            if (n.isEmpty() || n.equalsIgnoreCase(tid)) n = profiles.get(tid);   // a card named by its title ID (a pack with no name): the game's name in this APK
            out.put(tid, new Listed(tid, n, g));
        }
        return out;
    }

    /** Title ID → name of every game this APK runs (BuildConfig.NX_PROFILES: a line per game, title ID then name, tab-separated) */
    private static Map<String, String> profiles() {
        Map<String, String> out = new HashMap<>();
        for (String line : BuildConfig.NX_PROFILES.split("\n")) {
            String[] col = line.split("\t");
            if (col.length >= 1 && col[0].matches("[0-9A-F]{16}")) out.put(col[0], col.length > 1 && !col[1].isEmpty() ? col[1] : col[0]);
        }
        return out;
    }

    /** Game.ready(g): its pack fits this game build and this app's shader library (reads the pack) */
    private static boolean ready(Object g) {
        try {
            Method m = Class.forName(P + "Game").getDeclaredMethod("ready", g.getClass());
            m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(null, g));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "[frontend] readiness unknown: " + e);
            return false;
        }
    }

    /** This app's entries in the folder, by the title ID their text names: only plain files at the folder's top level whose text is this app's */
    private static Map<String, List<File>> ours(File d, String appId) {
        Map<String, List<File>> out = new HashMap<>();
        for (File f : top(d)) {
            if (!FrontendEntry.isEntry(f.getName())) continue;
            String tid = idOf(f, appId);
            if (tid == null) continue;
            List<File> l = out.get(tid);
            if (l == null) out.put(tid, l = new ArrayList<>());
            l.add(f);
        }
        return out;
    }

    /** The part files a run that was stopped left (hidden, ".part"), when their text is this app's: an empty one is left, as it cannot tell whose it is */
    private static void leftovers(File d, String appId) {
        for (File f : top(d))
            if (isPart(f.getName()) && idOf(f, appId) != null && f.delete()) Log.i(TAG, "[frontend] removed the part file " + f.getName());
    }

    private static boolean isPart(String n) { return n.startsWith(".") && n.endsWith(".part") && n.toLowerCase(Locale.ROOT).contains(FrontendEntry.EXT + "."); }

    /** The plain files at the folder's top level (no subfolder is ever looked into) */
    private static List<File> top(File d) {
        List<File> out = new ArrayList<>();
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) out.add(f);
        return out;
    }

    /** The title ID a small file's text names when the text is this app's, else null */
    private static String idOf(File f, String appId) {
        if (f.length() > FrontendEntry.MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) { return FrontendEntry.titleIdOfText(read(in), appId); } catch (IOException e) { return null; }
    }

    /** The entry under its new name (the game's name changed); the old one when the new name is another file's, or the rename failed */
    private static File rename(File from, File to) {
        if (!to.exists()) {
            if (from.renameTo(to)) { Log.i(TAG, "[frontend] renamed " + from.getName() + " to " + to.getName()); return to; }
            return from;
        }
        if (!same(from, to)) { Log.i(TAG, "[frontend] " + to.getName() + " is there and not this app's: " + from.getName() + " keeps its name"); return from; }
        // only the case changed, on a folder that ignores case: the two names are one file, so it goes through a name of its own first
        File step = fresh(to);
        if (step == null) return from;
        if (!from.renameTo(step)) { step.delete(); return from; }   // step: the empty file fresh made, this run's own
        if (!to.exists() && step.renameTo(to)) { Log.i(TAG, "[frontend] renamed " + from.getName() + " to " + to.getName()); return to; }
        step.renameTo(from);
        return from;
    }

    /** Whether two paths are one file (a folder that ignores case): their file keys (device and inode) are the same */
    private static boolean same(File a, File b) {
        try {
            Object ka = Files.readAttributes(a.toPath(), BasicFileAttributes.class).fileKey(), kb = Files.readAttributes(b.toPath(), BasicFileAttributes.class).fileKey();
            return ka != null && ka.equals(kb);
        } catch (IOException | RuntimeException e) {
            return false;   // cannot tell: the old name stays
        }
    }

    /** A hidden part file beside f that did not exist before (made new, empty): its own name each time, so it can never be another file; null when it cannot be made */
    private static File fresh(File f) {
        for (int i = 0; i < 3; i++) {
            File part = new File(f.getParentFile(), "." + f.getName() + "." + Long.toHexString(System.nanoTime()) + ".part");
            try { if (part.createNewFile()) return part; } catch (IOException e) { return null; }
        }
        return null;
    }

    /** The entry written whole or not at all: a part file of its own first (a frontend lists only the entry's extension), then renamed into place if the name is still free */
    private static boolean write(File f, String text) {
        File part = fresh(f);
        if (part == null) { Log.w(TAG, "[frontend] could not make a file beside " + f); return false; }
        try (FileOutputStream o = new FileOutputStream(part)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "[frontend] could not write " + f + ": " + e);
            part.delete();
            return false;
        }
        if (!f.exists() && part.renameTo(f)) return true;   // checked again just before: a file that came meanwhile keeps its place
        Log.w(TAG, "[frontend] could not name " + part.getName() + " " + f.getName());
        part.delete();
        return false;
    }

    // ---- A frontend starts an entry (FrontendLaunch) ----

    /**
     * FrontendLaunch's first look at the intent: when it names an entry (the extra "game" with its path, or the data's URI), the title ID it starts, which the
     * launch then checks against Home's list as a shortcut's; null for anything else. The title ID comes from the file's name, else from its text (a renamed
     * entry): through the URI the frontend granted, or by the path. Reads a few hundred bytes at most, and only of an entry, so it may run on the main thread
     */
    public static String titleIdOf(Context c, Intent i) {
        Uri u = i.getData();
        String path = i.getStringExtra("game");
        String name = u != null ? nameOf(c, u) : path != null ? new File(path).getName() : null;
        if (!FrontendEntry.isEntry(name)) return null;
        String tid = FrontendEntry.titleIdOfName(name);
        if (tid != null) return tid;
        try (InputStream in = u != null ? c.getContentResolver().openInputStream(u) : new FileInputStream(path)) {
            return in == null ? null : FrontendEntry.titleIdOfText(read(in), null);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "[frontend] the entry " + name + " is not readable: " + e);
            return null;
        }
    }

    /**
     * A URI's file name: its last path segment's (a file:// path, a storage document's ID ends in the file's path); only a segment that names no file at all
     * (a number, say) asks the provider for its display name, so a .nup's URI costs nothing more than before
     */
    private static String nameOf(Context c, Uri u) {
        String s = u.getLastPathSegment();
        if (s != null) s = s.substring(s.lastIndexOf('/') + 1);
        if (s != null && s.lastIndexOf('.') > 0) return s;
        if (!"content".equalsIgnoreCase(u.getScheme())) return s;
        try (Cursor q = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getString(0);
        } catch (RuntimeException e) { /* no name from the provider */ }
        return s;
    }

    /** At most MAX_BYTES + 1 bytes of the stream as UTF-8: an entry's text, or enough to tell it is not one */
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[FrontendEntry.MAX_BYTES + 1];
        int n;
        while (b.size() <= FrontendEntry.MAX_BYTES && (n = in.read(buf, 0, FrontendEntry.MAX_BYTES + 1 - b.size())) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
