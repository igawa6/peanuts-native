package dev.peanuts.build;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The companion catalog and installer (dev.peanuts.Catalog / dev.peanuts.Installer, package-private in dev.peanuts) for the build pipeline and the game settings'
 * Companion section, reached through reflection so these packages stay separate from dev.peanuts. Same files on disk as CatalogActivity: &lt;companion root&gt;/&lt;package&gt;/
 * with installed.json.
 */
public final class Companions {
    public interface Progress { void on(String what, long done, long total); }

    /** One catalog entry: the newest release of a title's companion */
    public static final class Item {
        public final String titleId, packageName, version, url, tag, gameName;
        public final long size;
        final Object raw;   // the dev.peanuts.Catalog.Item behind it, for Installer.install

        Item(Object raw) throws ReflectiveOperationException {
            this.raw = raw;
            titleId = str(raw, "titleId"); packageName = str(raw, "packageName"); version = str(raw, "version"); url = str(raw, "url"); tag = str(raw, "tag"); gameName = str(raw, "gameName");
            Object s = field(raw, "size");
            size = s instanceof Number ? ((Number) s).longValue() : 0;
        }
    }

    private static final String CATALOG = "dev.peanuts.Catalog", INSTALLER = "dev.peanuts.Installer";

    private Companions() {}

    /** Network: off the main thread */
    public static List<Item> fetch() throws IOException {
        try {
            List<Item> out = new ArrayList<>();
            for (Object o : (List<?>) call(Class.forName(CATALOG), "fetch", new Class<?>[0])) out.add(new Item(o));
            return out;
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause();   // the catalog's own failure (no network, a bad answer): its words go after the first line, for Details
            android.util.Log.w("peanuts", "companions: catalog: " + (e.getCause() != null ? e.getCause() : e));   // the raw words for the log; the player reads the sentence
            throw new IOException("The list of dual-screen companions could not be loaded. Check the internet connection, then try again.\n" + (c != null ? c : e), e);
        }
    }

    public static Item find(List<Item> items, String titleId) {
        for (Item i : items) if (i.titleId.equalsIgnoreCase(titleId)) return i;
        return null;
    }

    /** The installed version of a package in root, or null */
    public static String installedVersion(File root, String packageName) {
        if (root == null) return null;
        try {
            Object v = call(Class.forName(INSTALLER), "installedVersion", new Class<?>[] {File.class, String.class}, root, packageName);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The package folders in root that hold a companion (package.json), by name */
    public static List<String> installed(File root) {
        List<String> out = new ArrayList<>();
        File[] dirs = root == null ? null : root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) if (d.isDirectory() && !d.getName().endsWith(".new") && !d.getName().endsWith(".old") && new File(d, "package.json").isFile()) out.add(d.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Installer.installedVerified(root, package): true when the installed version was checked against a publisher-provided digest (its sha256, or a sibling
     * .sha256 asset), false for "unverified" (no digest was published anywhere, so only this device's own first-install fingerprint is on file) or not installed */
    public static boolean installedVerified(File root, String packageName) {
        if (root == null) return false;
        try {
            return (Boolean) call(Class.forName(INSTALLER), "installedVerified", new Class<?>[] {File.class, String.class}, root, packageName);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Installer.repair(root, package): completes or rolls back a swap a killed process left half-done (no single app-startup hook reaches dev.peanuts from
     * here, so this is called opportunistically wherever a package is touched - CompanionSection's refreshInstalled() calls it). Kept for callers with no
     * Context (never skips for a running game). */
    public static void repair(File root, String packageName) { repair(root, packageName, null); }

    /** As above, plus ctx (nullable): skips entirely while this app's own game process is running - see dev.peanuts.ui.CompanionPort's matching overload */
    public static void repair(File root, String packageName, Context ctx) {
        if (root == null) return;
        try {
            call(Class.forName(INSTALLER), "repair", new Class<?>[] {File.class, String.class, Context.class}, root, packageName, ctx);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // best effort; the next install()/activateLocalVersion() retries it anyway
        }
    }

    /** Catalog.compareVersions: &gt;0 when a is newer */
    public static int compareVersions(String a, String b) {
        try {
            return (Integer) call(Class.forName(CATALOG), "compareVersions", new Class<?>[] {String.class, String.class}, a, b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return a.compareTo(b);
        }
    }

    /** Never refuses for a running game (no Context) - the build pipeline's own install, where the title being built can't be the one playing */
    public static void install(Item item, File root, File cacheDir, Progress progress) throws IOException { install(item, root, cacheDir, progress, null); }

    /** As above, plus ctx (nullable): refuses with "close the game first" if this app's own game process is running (Installer.gameRunning) -
     * CompanionSection's Install/Update can run while a different companion's game session is live, same hazard as CompanionVersions' Switch to. */
    public static void install(Item item, File root, File cacheDir, Progress progress, Context ctx) throws IOException {
        try {
            Class<?> inst = Class.forName(INSTALLER);
            Class<?> pi = Class.forName(INSTALLER + "$Progress");
            Object proxy = Proxy.newProxyInstance(inst.getClassLoader(), new Class<?>[] {pi}, (p, m, args) -> {
                if (m.getName().equals("on") && args != null && args.length == 3) { if (progress != null) progress.on(String.valueOf(args[0]), ((Number) args[1]).longValue(), ((Number) args[2]).longValue()); return null; }
                if (m.getName().equals("toString")) return "Companions.Progress";
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == args[0];
                return null;
            });
            call(inst, "install", new Class<?>[] {Class.forName(CATALOG + "$Item"), File.class, File.class, pi, String.class, Context.class}, item.raw, root, cacheDir, proxy, null, ctx);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            android.util.Log.w("peanuts", "companions: install: " + (e.getCause() != null ? e.getCause() : e));   // the raw words for the log; the player reads the sentence
            throw new IOException("The dual-screen companion could not be installed because of a problem in the app.\n" + (c != null ? c : e), e);
        }
    }

    /** Never refuses for a running game (no Context) */
    public static void remove(File root, String packageName) throws IOException { remove(root, packageName, null); }

    /** As above, plus ctx (nullable): refuses ("close the game first") while the game is running - see install()'s overload */
    public static void remove(File root, String packageName, Context ctx) throws IOException {
        try {
            call(Class.forName(INSTALLER), "remove", new Class<?>[] {File.class, String.class, Context.class}, root, packageName, ctx);
        } catch (ReflectiveOperationException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            android.util.Log.w("peanuts", "companions: remove: " + (e.getCause() != null ? e.getCause() : e));   // the raw words for the log; the player reads the sentence
            throw new IOException("The dual-screen companion could not be removed because of a problem in the app.\n" + (c != null ? c : e), e);
        }
    }

    /** "Name 1.2.0" for a line of text */
    public static String label(String packageName, String version) {
        return version == null ? packageName : String.format(Locale.ROOT, "%s %s", packageName, version);
    }

    private static Object call(Class<?> k, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new ReflectiveOperationException(name + ": " + c, c);
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String str(Object o, String name) throws ReflectiveOperationException {
        Object v = field(o, name);
        return v == null ? "" : v.toString();
    }
}
