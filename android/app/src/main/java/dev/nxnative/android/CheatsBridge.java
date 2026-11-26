package dev.nxnative.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.peanuts.mods.CheatFile;
import dev.peanuts.mods.CheatImport;
import dev.peanuts.mods.CheatPick;
import dev.peanuts.mods.CheatSources;
import dev.peanuts.mods.CheatValues;
import dev.peanuts.mods.MncfBuild;
import dev.peanuts.mods.MncfConvert;
import dev.peanuts.mods.MncfExport;

/**
 * PEANUTS: Game settings' Cheat files for every game, on the game's cheat sources (dev.peanuts.mods.CheatSources) instead of upstream's imported/ folder. A
 * Peanuts Native file in upstream's launcher package, as ModCheats.java: Prefs, Pack, Profile, Ui and PadFocus are package-private. CheatsUi.rows asks
 * {@link #filesRow} for the row (PEANUTS hook); the sheet, its pickers and their results are here (LibrarySettings.result asks {@link #result} first).
 * <ul>
 * <li>Import cheat file… takes a .txt or a .zip, Import cheat folder… a folder, each in the Atmosphère, Eden / Yuzu or Ryujinx layout or as a bare build file
 *     (dev.peanuts.mods.CheatImport): the files of the game's build become a source, the other versions' files are kept with it; another game's, a file that
 *     names no build, or another version alone are refused with upstream's words.</li>
 * <li>A source's line: on or off as a whole, or removed. Export cheats… writes the sources as .mncf (one) or .mncp (several: dev.peanuts.mods.MncfExport);
 *     Import cheats… reads upstream's export zip as before (into imported/, which a start or this sheet makes sources: CheatSources.migrate).</li>
 * <li>Every source is an .mncf file: an Atmosphère file the player adds is converted with what the app knows of the game ({@link #known}).</li>
 * </ul>
 */
public final class CheatsBridge {
    private CheatsBridge() {}

    /** Request codes: a cheat file (.txt, .zip), a cheat folder, an export, an export to import (60 to 73: LibrarySettings and its neighbours; 80 to 82: CheatsUi's) */
    public static final int REQ_FILE = 83, REQ_FOLDER = 84, REQ_EXPORT = 85, REQ_IMPORT_ZIP = 86;
    private static Game pendingFor;
    private static Runnable pendingAgain;

    /** The game's cheats folder: the merged file a start writes (DM_CHEATS) and sources/ with the player's imports */
    public static File folder(Context c, String titleId) { return CheatsUi.folder(c, titleId); }

    /** The game's main builds, first 16 hex digits upper case: its pack's build rows, else its profile's builds (a game not prepared yet); empty when neither knows */
    public static Set<String> builds(String titleId, String packDir) {
        if (packDir != null && !packDir.isEmpty()) {
            try { Set<String> b = Cheats.builds16(String.join(" ", Pack.read(new File(packDir)).builds)); if (!b.isEmpty()) return b; } catch (IOException | RuntimeException ignored) { }
        }
        Profile p = Profile.of(titleId);
        return Cheats.builds16(p == null ? "" : p.builds);
    }

    /** The version game.tsv names for a main build ("2.0.2"), null when it names none */
    public static String version(String titleId, String build16) {
        Profile p = Profile.of(titleId);
        if (p == null || build16 == null) return null;
        try {
            for (Map.Entry<Long, Map<String, String>> e : p.buildMap().entrySet()) {
                String main = e.getValue().get("main");
                if (main != null && main.length() >= 16 && main.substring(0, 16).equalsIgnoreCase(build16)) return p.display(e.getKey());
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** The cheats that come with the app for this build (the profile's file in the pack), null when the pack has none */
    public static String appText(String packDir, String build) {
        File f = packDir == null || packDir.isEmpty() ? null : Cheats.packFile(new File(packDir), build);
        if (f == null) return null;
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    /**
     * Upstream's imported/ files made sources (once; CheatSources.migrate) and an earlier version's Atmosphère sources made .mncf (CheatSources.upgrade), quietly:
     * a failure leaves them where they are, and a start tries again
     */
    static void migrate(File folder, MncfConvert.Known k) {
        try { CheatSources.migrate(folder, System.currentTimeMillis(), k); } catch (IOException | RuntimeException e) { android.util.Log.w("peanuts", "cheats: imported files not moved: " + e); }
        try { CheatSources.upgrade(folder, k); } catch (RuntimeException e) { android.util.Log.w("peanuts", "cheats: earlier sources not converted: " + e); }
    }

    /**
     * What a conversion to .mncf knows of the game (MncfConvert.Known): its title ID and name, every build's version the profile names, the pack's build and its
     * own main (read only when a file is converted), and who adds the file: the name of the user games start with, unless it is still the host's "Player"
     */
    public static MncfConvert.Known known(Context c, String titleId, String gameName, String packDir) {
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        Profile p = Profile.of(titleId);
        if (p != null) {
            try {
                for (Map.Entry<Long, Map<String, String>> e : p.buildMap().entrySet()) {
                    String main = e.getValue().get("main"), d = p.display(e.getKey());
                    if (main != null && main.length() >= 16 && d != null) versions.put(main.substring(0, 16).toUpperCase(Locale.ROOT), d);
                }
            } catch (RuntimeException ignored) { }
        }
        String author = null;
        try {
            String uid = Users.defaultUid(c);
            for (Users.User u : Users.list(c)) if (u.uid.equals(uid) && !u.name.equals("Player") && !u.name.trim().isEmpty()) author = u.name.trim();
        } catch (RuntimeException ignored) { }
        File pack = packDir == null || packDir.isEmpty() ? null : new File(packDir);
        String build = first(builds(titleId, packDir));
        return new MncfConvert.Known(titleId, gameName, author, pack != null && dev.peanuts.mods.MncfMain.has(pack) ? build : null, versions, MncfConvert.of(pack));
    }

    /** Upstream's imported files and an earlier version's Atmosphère sources made .mncf now (CheatSources.migrate, upgrade), with what the app knows of the game */
    public static void convert(Context c, String titleId, String gameName, String packDir) {
        File folder = folder(c, titleId);
        boolean any = new File(folder, "imported").isDirectory();
        for (CheatSources.Source s : CheatSources.list(folder)) any |= CheatSources.unconverted(s);
        if (any) migrate(folder, known(c, titleId, gameName, packDir));
    }

    /** The game for the .mncf sources (MncfBuild.Game): its build that runs, that build's version, its pack (the game's own main) */
    public static MncfBuild.Game game(String titleId, String build, String packDir) {
        return new MncfBuild.Game(titleId, build, version(titleId, build), packDir == null || packDir.isEmpty() ? null : new File(packDir));
    }

    private static String first(Set<String> s) { return s.isEmpty() ? null : s.iterator().next(); }

    // ---- For the Mods & Cheats page (dev.peanuts.mods.CheatsPage, Part D) ----

    /** The size of main's code in the pack (main.fixups.tsv "# TEXT_END"), which tells a code cheat from a data cheat; -1 when the pack has none */
    public static long textEnd(String packDir) {
        if (packDir == null || packDir.isEmpty()) return -1;
        File f = new File(packDir, "main.fixups.tsv");
        if (!f.isFile()) return -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null && l.startsWith("#"); ) {
                String[] w = l.substring(1).trim().split("\\s+");
                if (w.length >= 2 && w[0].equals("TEXT_END")) return Long.parseLong(w[1].replaceFirst("^0[xX]", ""), 16);
            }
        } catch (IOException | NumberFormatException ignored) { }
        return -1;
    }

    /**
     * The merge a start would make now (ModCheats.install): the app's cheats, the player's sources and the enabled mods' cheat files of this build, with every
     * cheat's key; textEnd gives the kinds (CheatFile.parse). The page switches by these keys
     */
    public static CheatSources.Merged merged(Context c, String titleId, String gameDir, String packDir, String build, long textEnd) {
        return merged(c, titleId, gameDir, packDir, build, textEnd, false);
    }

    /**
     * The same; make: a source's .mncf not made ready for the build yet is made now (MncfBuild: it reads the game's main, seconds for a large game, so not on
     * the main thread); without, it is listed as pending (CheatSources.Block.pending) until {@link #prepare} has made it
     */
    public static CheatSources.Merged merged(Context c, String titleId, String gameDir, String packDir, String build, long textEnd, boolean make) {
        String app = appText(packDir, build);
        List<ModCheats.ModFile> mods = ModCheats.of(modDirs(gameDir, titleId, packDir), build, null);
        CheatSources.Merged m = CheatSources.merge(ModCheats.blocks(app == null ? "" : app, folder(c, titleId), game(titleId, build, packDir), mods, textEnd, make));
        // What the player set for a file that got a new version, kept by cheat id, under the cheats' names once the new file is in the merge (CheatUpdates)
        Set<String> on = Prefs.cheatsOn(c, titleId);
        if (dev.peanuts.mods.CheatUpdates.applyPending(folder(c, titleId), m, on)) Prefs.cheatsOn(c, titleId, on);
        return m;
    }

    /** Every source of the game made ready for the build (off the main thread): what a page shows as pending is listed once this is done */
    public static void prepare(Context c, String titleId, String packDir, String build) {
        MncfBuild.Game g = game(titleId, build, packDir);
        for (CheatSources.Source s : CheatSources.list(folder(c, titleId))) {
            if (!s.file().isFile()) continue;
            try { s.ready(g); } catch (IOException | RuntimeException e) { android.util.Log.w("peanuts", "cheats: " + s.name + " not made ready for " + build + ": " + e); }
        }
    }

    /** The enabled mods' cheats folders, as GameActivity.start finds them (dev.peanuts.mods.ModRuntime.cheatsDirs beside the game) */
    static List<File> modDirs(String gameDir, String titleId, String packDir) {
        if (gameDir == null || gameDir.isEmpty()) return java.util.Collections.emptyList();
        File game = new File(gameDir);
        try {
            return dev.peanuts.mods.ModRuntime.cheatsDirs(game.isDirectory() ? game : game.getParentFile(), titleId, packDir == null || packDir.isEmpty() ? null : new File(packDir));
        } catch (RuntimeException e) {
            return java.util.Collections.emptyList();
        }
    }

    /** The player's toggles, by key (the merged file's names) */
    public static Set<String> on(Context c, String titleId) { return Prefs.cheatsOn(c, titleId); }
    public static void on(Context c, String titleId, Set<String> keys) { Prefs.cheatsOn(c, titleId, keys); }
    public static void on(Context c, String titleId, String key, boolean on) { Prefs.cheatOn(c, titleId, key, on); }

    /**
     * Prefs.cheats (PEANUTS hook): whether a start hands the host the game's cheats. Any game may (the profile's cheats rows are no longer asked); it does when a
     * source is on. The first time for a game, its earlier state moves over: the app's own cheats are on when the game's old Cheats switch was (master), and upstream's
     * imported files become sources (CheatSources.migrate)
     */
    public static boolean wanted(Context c, String titleId, boolean master) {
        File folder = folder(c, titleId);
        try {
            Profile p = Profile.of(titleId);
            if (!CheatSources.appKnown(folder) && (master || p != null && p.cheats)) CheatSources.appEnabled(folder, master);   // only a game that has had the switch writes a state
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("peanuts", "cheats: " + titleId + ": the earlier state could not be moved: " + e);
            return master;
        }
        // upstream's imported files and an earlier version's Atmosphère sources count as they are: the start (ModCheats.install) and the page make them .mncf,
        // where the pack is known, so the converter has the game's own main for patterns
        if (Boolean.TRUE.equals(CheatSources.appEnabled(folder))) return true;
        for (CheatSources.Source s : CheatSources.list(folder)) if (s.enabled && (s.file().isFile() || CheatSources.unconverted(s))) return true;
        File[] imported = new File(folder, "imported").listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        return imported != null && imported.length > 0;
    }

    // ---- For the in-game Mods tab (dev.peanuts.mods.ModsTab, Part E), in the game's process ----

    /** Every entry of the host's cheat file: "key\tstate\tflags\treason" a line (Native.cheatStatus, Part B); null when no file is loaded or the host has no such call */
    public static String status() {
        try { return Native.cheatStatus(); } catch (UnsatisfiedLinkError e) { return null; }
    }

    /** The host reads the merged file and its .enabled again (Native.cheatsReload): the entries now loaded, -1 the file could not be read, -2 not in this host */
    public static int reload() {
        try { return Native.cheatsReload(); } catch (UnsatisfiedLinkError e) { return -2; }
    }

    /** A cheat on or off in the running game by its key (Native.cheatSet): 1 done, 0 not a plain cheat (refused, broken), -1 no such key, -2 not in this host */
    public static int set(String key, boolean on) {
        try { return Native.cheatSet(key, on); } catch (UnsatisfiedLinkError e) { return -2; }
    }

    /** The running game stopped and started again (the Mods tab's Restart: a mod's files and code are read at a start) */
    public static void restart(Activity a) { ModsPanel.restart(a); }

    /** The game's old Cheats switch (Game settings › Cheats, Experimental), kept for {@link #wanted}'s first look */
    public static boolean master(Context c, String titleId) { return Prefs.of(c).getBoolean(Prefs.gameKey("cheats", titleId), false); }

    // ---- The row and its sheet ----

    /** CheatsUi.rows (PEANUTS hook): Cheat files, what the game has, opening the sheet. Its tile is the Peanuts folder icon (the sheet imports files and folders) */
    static Ui.Setting filesRow(MainActivity a, Game g, Runnable again) {
        File folder = folder(a, g.titleId);
        migrate(folder, known(a, g.titleId, g.name, g.packDir));
        int n = CheatSources.list(folder).size();
        Profile p = Profile.of(g.titleId);
        boolean own = p != null && p.cheats;   // a profile with its own cheat file
        String value = own ? (n == 0 ? "The game's own" : "The game's own and " + n + " imported") : n == 0 ? "None imported yet" : n + " imported";
        Ui.Setting row = new Ui.Setting(a, "", a.getString(R.string.cheats_files_title), v -> sheet(a, g, again)).value(value)
                .note("Atmosphère, Eden or Ryujinx cheats for this game's build: a .txt, a .zip or a folder").chevron(a).sub();
        dev.peanuts.ui.Icons.tile(row.tile, "folder");   // after sub(): the icon fits the smaller tile
        return row;
    }

    /** The sheet: the game's own file, each source (a tap: on or off, remove), then Import cheat file…, Import cheat folder…, Export cheats…, Import cheats… */
    static void sheet(MainActivity a, Game g, Runnable again) {
        File folder = folder(a, g.titleId);
        migrate(folder, known(a, g.titleId, g.name, g.packDir));
        String build = first(builds(g.titleId, g.packDir));
        Profile p = Profile.of(g.titleId);
        File shipped = g.packDir != null && build != null ? Cheats.packFile(new File(g.packDir), build) : null;
        List<CheatSources.Source> sources = CheatSources.list(folder);
        List<String> items = new ArrayList<>();
        boolean own = p != null && p.cheats;
        if (own) items.add(shipped != null ? a.getString(R.string.cheats_shipped, shipped.getName()) : a.getString(R.string.cheats_shipped_missing));
        for (CheatSources.Source s : sources) items.add(sourceLine(s, game(g.titleId, build, g.packDir)));
        items.add(a.getString(R.string.cheats_import_file));
        items.add("Import cheat folder…");
        items.add(a.getString(R.string.cheats_export));
        items.add(a.getString(R.string.cheats_import_zip));
        int base = own ? 1 : 0;
        new PadFocus.Builder(a).setTitle(a.getString(R.string.cheats_files_title)).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w < base) return;   // the game's own file: nothing to do
            if (w < base + sources.size()) { source(a, folder, sources.get(w - base), game(g.titleId, build, g.packDir), again); return; }
            pendingFor = g;
            pendingAgain = again;
            int k = w - base - sources.size();
            if (k == 0) pick(a, new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE);
            else if (k == 1) pick(a, new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
            else if (k == 2) pick(a, new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, MncfExport.fileName(g.name, sources.size() > 1)), REQ_EXPORT);
            else pick(a, new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT_ZIP);
        }).setNegativeButton("Close", null).show();
    }

    /** A source's cheats that can run for this build (its kept .mncf for it; -1 when it was not made ready yet) */
    static int count(CheatSources.Source s, MncfBuild.Game g) {
        try {
            MncfBuild.Ready r = MncfBuild.kept(s.file(), g);
            if (r == null) return -1;
            int n = 0;
            for (MncfBuild.Cheat c : r.cheats) if (!c.master && !c.refused()) n++;
            return n;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** A source's line in the sheet: its name, how many cheats it has for this build, off */
    static String sourceLine(CheatSources.Source s, MncfBuild.Game g) {
        int n = count(s, g);
        String what = n < 0 ? "not checked for this build yet" : n == 0 ? "none for this build" : n + " cheats";
        return s.name + " (" + what + (s.enabled ? "" : ", off") + ")";
    }

    private static void source(MainActivity a, File folder, CheatSources.Source s, MncfBuild.Game g, Runnable again) {
        int n = count(s, g);
        String msg = (n == 0 ? "None of its cheats are for this game's build." : n < 0 ? "Its cheats are checked for this game's build when you open Mods & Cheats." : n + " cheats for this game's build.")
                + (s.enabled ? " They are listed in the in-game panel from the next start." : " It is off: its cheats are not in the in-game panel.");
        new PadFocus.Builder(a).setTitle(s.name).setMessage(msg)
                .setPositiveButton(s.enabled ? "Turn off" : "Turn on", (x, y) -> {
                    try { CheatSources.enabled(folder, s.id, !s.enabled); } catch (IOException e) { a.error(a.getString(R.string.cheats_refused, "It could not be changed. Its files are as they were.")); }
                    again.run();
                })
                .setNeutralButton(a.getString(R.string.cheats_remove), (x, y) -> new PadFocus.Builder(a).setTitle(a.getString(R.string.cheats_remove) + " " + s.name + "?")
                        .setMessage("Its cheats leave the in-game panel from the next start, and its files are deleted from this device. The game and its saves are not changed.")
                        .setPositiveButton(a.getString(R.string.cheats_remove), (u, v) -> { CheatSources.remove(folder, s.id); again.run(); })
                        .setNegativeButton("Cancel", null).show())
                .setNegativeButton("Cancel", null).show();
    }

    private static void pick(Activity a, Intent i, int request) {
        try { a.startActivityForResult(i, request); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(a, "This device has no file picker.", Toast.LENGTH_LONG).show(); }
    }

    // ---- The pickers' results ----

    /** LibrarySettings.result (PEANUTS): the documents of the sheet's pickers; true when the request was one of them */
    public static boolean result(Activity act, int request, int result, Intent data) {
        if (request < REQ_FILE || request > REQ_IMPORT_ZIP) return false;
        Game g = pendingFor;
        Runnable again = pendingAgain;
        pendingFor = null; pendingAgain = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || g == null || !(act instanceof MainActivity)) return true;
        MainActivity a = (MainActivity) act;
        Uri doc = data.getData();
        File folder = folder(a, g.titleId);
        Set<String> builds = builds(g.titleId, g.packDir);
        if (request == REQ_FILE || request == REQ_FOLDER) {
            CheatPick.read(a, doc, request == REQ_FOLDER, g.titleId, builds, a.getString(R.string.cheats_import_file).replace("…", ""), r -> {
                taken(a, g, folder, builds, r);
                a.gameSettingsTop();
            });
            return true;
        }
        try {
            if (request == REQ_EXPORT) {
                String b = first(builds);
                MncfExport.Out out = MncfExport.make(folder, g.name, b == null ? null : merged(a, g.titleId, g.gameDir, g.packDir, b, textEnd(g.packDir)), Prefs.cheatsOn(a, g.titleId), CheatValues.read(folder));
                if (out == null) throw new IOException("there are no imported cheats to export");
                try (OutputStream os = a.getContentResolver().openOutputStream(doc)) {
                    if (os == null) throw new IOException("the place picked cannot be written");
                    os.write(out.bytes);
                }
                Toast.makeText(a, a.getString(R.string.cheats_exported), Toast.LENGTH_LONG).show();
            } else {   // upstream's Import cheats…: its zip into imported/, then made sources
                Cheats.ZipContent c;
                try (InputStream in = a.getContentResolver().openInputStream(doc)) {
                    if (in == null) throw new IOException("the zip cannot be opened");
                    c = Cheats.readZip(in, g.titleId, builds);
                }
                Cheats.takeZip(folder, c, builds);
                int n = CheatSources.dropDuplicates(folder, CheatSources.migrate(folder, System.currentTimeMillis(), known(a, g.titleId, g.name, g.packDir)));   // its files as sources (.mncf); one the game has already is not added twice
                if (c.master != null) Prefs.cheats(a, g.titleId, c.master);
                if (c.on != null) Prefs.cheatsOn(a, g.titleId, c.on);
                Toast.makeText(a, a.getString(R.string.cheats_zip_done, n), Toast.LENGTH_LONG).show();
            }
        } catch (IOException | RuntimeException e) {
            a.error(a.getString(R.string.cheats_refused, e.getMessage()));
        }
        if (again != null) a.gameSettingsTop();
        return true;
    }

    /** A pick read: its sources kept (the game's build and the other versions with it), or upstream's refusal with the reason */
    static void taken(MainActivity a, Game g, File folder, Set<String> builds, CheatPick.Result r) {
        if (r.error != null) { a.error(a.getString(R.string.cheats_refused, r.error)); return; }
        CheatImport.Found f = r.found;
        String build = first(builds);
        if (f.mod) { a.error(a.getString(R.string.cheats_refused, "It is a mod (it has romfs or exefs folders). Install it with Install mod… in the Mods section below.")); return; }
        if (builds.isEmpty()) { a.error(a.getString(R.string.cheats_refused, "This game's build ID is not known yet: Prepare the game first.")); return; }
        if (f.forGame.isEmpty()) {
            String why;
            if (!f.otherVersion.isEmpty()) {
                List<String> theirs = new ArrayList<>();
                for (CheatImport.Hit h : f.otherVersion) if (!theirs.contains(h.bid)) theirs.add(h.bid);
                why = "It is for build " + String.join(", ", theirs) + "; this game is build " + String.join(", ", builds) + ". Cheats of another build write the wrong places, so it was not imported.";
            } else if (!f.otherGame.isEmpty()) why = "It is for another game (" + f.otherGame.get(0).tid + "), not " + g.titleId + ".";
            else if (!f.noBuild.isEmpty()) why = "It does not say which build of the game it is for: name it <build ID>.txt (as Atmosphère cheat files are named) or start it with a line \"build <build ID>\". This game is build " + build + ".";
            else why = "There is no cheat file in it ([Name] followed by code lines).";
            a.error(a.getString(R.string.cheats_refused, why));
            return;
        }
        List<String> added = new ArrayList<>(), same = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            MncfConvert.Known k = known(a, g.titleId, g.name, g.packDir);
            for (Map.Entry<String, Map<String, String>> e : f.sources().entrySet()) {
                CheatSources.Source had = null;
                for (Map.Entry<String, String> file : e.getValue().entrySet()) if (builds.contains(file.getKey())) had = CheatSources.sameAs(folder, file.getValue());
                if (had != null) { same.add(had.name); continue; }
                added.add(CheatSources.add(folder, e.getKey(), r.from, e.getValue(), now++, k, r.display != null ? r.display : r.name).name);
            }
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("peanuts", "cheats: keeping a source: " + e);
            a.error(a.getString(R.string.cheats_refused, "It could not be kept in the app's storage. Free some space and import it again."));
            return;
        }
        if (added.isEmpty()) { Toast.makeText(a, "Imported already: " + String.join(", ", same) + ".", Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(a, a.getString(R.string.cheats_imported, String.join(", ", added)), Toast.LENGTH_LONG).show();
    }
}
