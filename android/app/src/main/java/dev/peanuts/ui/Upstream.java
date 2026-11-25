package dev.peanuts.ui;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import dev.nxnative.android.BuildConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Upstream's launcher classes (dev.nxnative.android, package-private) read through reflection, as dev.peanuts.Games does, so no upstream file needs a hook: the APK's
 * profiles (BuildConfig.NX_PROFILES, public), a dump's or .nup's title ID (Pack.titleIdOf), a .nup's packages (Nup.open().packages), SAF URIs → paths (PathUtil),
 * the game list and its readiness (Game.load, Game.ready). Everything that reads files runs off the main thread.
 */
final class Upstream {
    private static final String P = "dev.nxnative.android.";
    private Upstream() {}

    /** Title ID → name of every game this APK runs (NX_PROFILES: one line per game, title ID then name, tab-separated; Profile.java) */
    static Map<String, String> profiles() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : BuildConfig.NX_PROFILES.split("\n")) {
            String[] c = line.split("\t");
            if (c.length >= 1 && c[0].matches("[0-9A-F]{16}")) out.put(c[0], c.length > 1 && !c[1].isEmpty() ? c[1] : c[0]);
        }
        return out;
    }

    /** Profile.missing's sentence for a game this APK has no profile for (null when it has one) */
    static String profileMissing(String titleId, String name) {
        try {
            Method m = Class.forName(P + "Profile").getDeclaredMethod("missing", String.class, String.class);
            m.setAccessible(true);
            Object o = m.invoke(null, titleId, name);
            return o == null ? null : o.toString();
        } catch (Throwable t) {
            return hasProfile(titleId) ? null : "This app version does not include " + name + ". Install an app version that includes it.";
        }
    }

    static boolean hasProfile(String titleId) { return titleId != null && profiles().containsKey(titleId.toUpperCase(Locale.ROOT)); }

    /** A dump folder's or .nup's title ID (Pack.titleIdOf), null when it is neither */
    static String titleIdOf(File game) {
        try {
            Method m = Class.forName(P + "Pack").getDeclaredMethod("titleIdOf", File.class);
            m.setAccessible(true);
            Object o = m.invoke(null, game);
            return o == null ? null : o.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) { Log.w("peanuts", "titleIdOf " + game + ": " + cause(t)); return null; }
    }

    /** One package of a .nup: kind (0 base, 1 update, 2 add-on), its title ID (16 upper-case hex digits) and version */
    static final class NupPkg {
        final int kind, flags;
        final String titleId;
        final long version;

        NupPkg(int kind, String titleId, long version, int flags) {
            this.kind = kind;
            this.titleId = titleId;
            this.version = version;
            this.flags = flags;
        }
    }

    /** The packages of a .nup (Nup.open(f).packages), empty when it cannot be read */
    static List<NupPkg> nupPackages(File nup) {
        List<NupPkg> out = new ArrayList<>();
        try {
            Class<?> k = Class.forName(P + "Nup");
            Method open = k.getDeclaredMethod("open", File.class); open.setAccessible(true);
            Object n = open.invoke(null, nup);
            Field pk = k.getDeclaredField("packages"); pk.setAccessible(true);
            Class<?> pkg = Class.forName(P + "Nup$Pkg");
            Field kind = field(pkg, "kind"), title = field(pkg, "titleId"), ver = field(pkg, "version"), flags = field(pkg, "flags");
            for (Object p : (List<?>) pk.get(n)) out.add(new NupPkg(kind.getInt(p), String.format(Locale.ROOT, "%016X", title.getLong(p)), ver.getLong(p), flags.getInt(p)));
        } catch (Throwable t) { Log.w("peanuts", "nup " + nup + ": " + cause(t)); }
        return out;
    }

    /** A folder picker's tree URI → its /storage path (PathUtil.treeToPath); null off a local volume */
    static String treeToPath(Uri tree) { return path("treeToPath", tree); }
    /** A file picker's document URI → its path (PathUtil.documentToPath); null when the provider gives none */
    static String documentToPath(Uri doc) { return path("documentToPath", doc); }

    private static String path(String method, Uri u) {
        try {
            Method m = Class.forName(P + "PathUtil").getDeclaredMethod(method, Uri.class);
            m.setAccessible(true);
            Object o = m.invoke(null, u);
            return o == null ? null : o.toString();
        } catch (Throwable t) { Log.w("peanuts", method + ": " + cause(t)); return null; }
    }

    /** An entry of upstream's game list (Game): where the game and its pack are, and whether it starts as it is (Game.ready) */
    static final class Entry {
        final String titleId, name, gameDir, packDir; final boolean ready; final long used;
        Entry(String titleId, String name, String gameDir, String packDir, boolean ready) { this(titleId, name, gameDir, packDir, ready, 0); }
        Entry(String titleId, String name, String gameDir, String packDir, boolean ready, long used) { this.titleId = titleId; this.name = name; this.gameDir = gameDir; this.packDir = packDir; this.ready = ready; this.used = used; }
    }

    /** The fields of one of upstream's Game objects (MainActivity's list, handed to Library.fill), without the readiness check; null when it is not one */
    static Entry entryOf(Object g) {
        if (g == null) return null;
        try {
            Class<?> game = g.getClass();
            Field titleId = field(game, "titleId"), name = field(game, "name"), gameDir = field(game, "gameDir"), packDir = field(game, "packDir"), used = field(game, "used");
            return new Entry(str(titleId.get(g)).toUpperCase(Locale.ROOT), str(name.get(g)), str(gameDir.get(g)), str(packDir.get(g)), false, used.getLong(g));
        } catch (Throwable t) { Log.w("peanuts", "game entry: " + cause(t)); return null; }
    }

    /** A scanned game that is ready (a dump or .nup with its pack) into upstream's list (Game.put), so Home shows it as one of Banana's own cards; false when it could not be added */
    static boolean addToList(Context c, File game, File pack, String titleId, String name) {
        try {
            Class<?> k = Class.forName(P + "Game");
            java.lang.reflect.Constructor<?> ctor = k.getDeclaredConstructor(String.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            Object g = ctor.newInstance(titleId, name, game.getPath(), pack.getPath());
            Method put = k.getDeclaredMethod("put", Context.class, k); put.setAccessible(true);
            put.invoke(null, c, g);
            return true;
        } catch (Throwable t) { Log.w("peanuts", "add to list " + game + ": " + cause(t)); return false; }
    }

    /** Upstream's game list with each entry's readiness (reads packs: off the main thread) */
    static List<Entry> games(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            Class<?> game = Class.forName(P + "Game");
            Method load = game.getDeclaredMethod("load", Context.class); load.setAccessible(true);
            Method ready = game.getDeclaredMethod("ready", game); ready.setAccessible(true);
            Field titleId = field(game, "titleId"), name = field(game, "name"), gameDir = field(game, "gameDir"), packDir = field(game, "packDir");
            for (Object g : (List<?>) load.invoke(null, c)) {
                boolean r; try { r = Boolean.TRUE.equals(ready.invoke(null, g)); } catch (Throwable t) { r = false; }
                out.add(new Entry(str(titleId.get(g)).toUpperCase(Locale.ROOT), str(name.get(g)), str(gameDir.get(g)), str(packDir.get(g)), r));
            }
        } catch (Throwable t) { Log.w("peanuts", "game list unreadable: " + cause(t)); }
        return out;
    }

    /** Banana's Play for a listed game (MainActivity.launch(Game): the pack search, the save and shader checks, its dialogs); false when it could not be called */
    static boolean launch(android.app.Activity a, Object g) {
        try {
            Method m = Class.forName(P + "MainActivity").getDeclaredMethod("launch", Class.forName(P + "Game"));
            m.setAccessible(true);
            m.invoke(a, g);
            return true;
        } catch (Throwable t) { Log.w("peanuts", "launch: " + cause(t)); return false; }
    }

    private static Field field(Class<?> k, String n) throws NoSuchFieldException { Field f = k.getDeclaredField(n); f.setAccessible(true); return f; }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static Throwable cause(Throwable t) { return t.getCause() != null ? t.getCause() : t; }
}
