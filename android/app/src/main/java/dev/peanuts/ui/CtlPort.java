package dev.peanuts.ui;

import android.app.Activity;
import android.content.Context;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the control interface (dev.peanuts.ctl) reads and does in the library: Home's filter and Find text, the open game sheet, the rows with their state, a
 * rescan, and a build started with the files the sheet would choose. The library's classes stay package-private; this is their one public face, and it only
 * calls what a tap on Home or in the sheet calls. Except {@link #rows}, everything here is for the main thread.
 */
public final class CtlPort {
    private CtlPort() {}

    /** Home's view state (filter, find, show_hidden, scanning); null when no Home was drawn in this process */
    public static Map<String, Object> home() {
        Library l = Library.now();
        if (l == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filter", l.filterName());
        m.put("find", l.findText());
        m.put("show_hidden", l.hiddenShown());
        m.put("scanning", l.scanningNow());
        return m;
    }

    /** The title whose game sheet is open, null when none is */
    public static String sheetTitle() { return GameSheet.openTitle(); }

    public static boolean screensOff() { return ScreensOff.on(); }

    /** Whether this activity's Home was drawn (the library works on the activity that shows it) */
    public static boolean hasHome(Activity a) { return Library.of(a) != null; }

    /** Home's filter chip and Find text set as a tap would (null: left as it is); returns the filter Home shows afterwards */
    public static String view(Activity a, String filter, String find) {
        Library l = Library.of(a);
        if (l == null) return null;
        l.view(filter, find);
        return l.filterName();
    }

    /** Settings › Library › Rescan; false when this activity's Home was never drawn */
    public static boolean rescan(Activity a) {
        if (Library.of(a) == null) return false;
        Library.rescan(a);
        return true;
    }

    /** The build card's game and how it stands (title_id, name, step, percent, failed, error, code); null when Home shows no build */
    public static Map<String, Object> buildCard() {
        Library.Build b = Library.buildNow();
        if (b == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title_id", b.titleId);
        m.put("name", b.name);
        m.put("step", b.step);
        m.put("percent", b.percent);
        m.put("failed", b.failed);
        if (b.failed) { m.put("error", b.error); m.put("code", b.code); }
        return m;
    }

    /** Whether Build would ask "Build anyway?" for this title (its version is not one of the profile's builds) */
    public static boolean wouldAsk(Activity a, String titleId) {
        Library l = Library.of(a);
        LibraryScanner.Title t = l == null ? null : l.title(titleId);
        return t != null && GameSheet.wouldAsk(a, t);
    }

    /**
     * The sheet's Build for a title with no sheet open: the base, the update chosen earlier (else the newest found), the DLC found and added, the companion
     * as its switch stands (GameSheet.resumeState, what the build card's Resume uses). anyway is the answer to "Build anyway?"; without it a title that
     * would be asked is refused. A game that is built already is refused: this is the first build, not Rebuild.
     * Returns null when the build was handed to the pipeline (a refusal of the pipeline itself then comes through dev.peanuts.ctl.Ctl.buildRefused), else
     * {code, sentence}.
     */
    public static String[] build(Activity a, String titleId, boolean anyway) {
        Library lib = Library.of(a);
        if (lib == null) return new String[] {"no_home", "Home was not drawn yet."};
        LibraryScanner.Title t = lib.title(titleId);
        if (t == null) return new String[] {"unknown_title", titleId + " is not in the library (scan first)."};
        if (t.status == LibraryScanner.Status.NO_PROFILE) return new String[] {"not_in_app", String.valueOf(Upstream.profileMissing(t.titleId, t.name))};
        if (lib.building(titleId)) return new String[] {"busy", t.name + " is being built now. Wait for it to finish."};   // another game that builds: this one goes into the build queue (issue #348)
        // a built game is not built again from here, except to go on with its own stopped build (the build card's Resume, Retry and Try anyway)
        Library.Build stopped = Library.buildNow();
        boolean goesOn = stopped != null && stopped.failed && stopped.titleId.equalsIgnoreCase(titleId);
        if (t.status == LibraryScanner.Status.READY && !goesOn) return new String[] {"already_built", t.name + " is built already. This command makes the first build only (prepare remakes what does not fit, reimport makes the .nup again)."};
        if (t.base == null) return new String[] {"no_base", "No base game found. Only an update or DLC is in the folder."};
        if (!t.base.file.exists()) return new String[] {"file_missing", "The base file is no longer where the app found it: " + t.base.file.getName()};
        LibraryScanner.Source up = GameSheet.storedUpdate(a, t);
        if (up != null && !up.file.exists()) return new String[] {"file_missing", "The update file is no longer where the app found it: " + up.file.getName()};
        if (t.home == null) return new String[] {"no_game_dir", "Set the game folder first (Settings › Library)."};
        if (!PeanutsPrefs.hasKeys(a)) return new String[] {"no_keys", "Add your keys first (Settings › Console), then build."};
        if (!PeanutsPrefs.hasFonts(a)) return new String[] {"no_firmware", "Add your firmware first (Settings › Console), then build."};
        if (!anyway && GameSheet.wouldAsk(a, t))
            return new String[] {"wrong_version", "This version is not one of this app's versions of " + t.name + ": the sheet asks \"Build anyway?\" first. The job command's try_anyway with this title_id gives that answer."};
        GameSheet.State s = GameSheet.resumeState(a, t, CompanionPort.forTitle(Library.catalogNow(), t.titleId));
        s.buildAnyway = anyway;
        String why = lib.startBuild(s);
        return why == null ? null : new String[] {"refused", why};
    }

    /**
     * The sheet's Import again for a title with no sheet open (GameSheet.importAgain, dev.peanuts.build.Reimport): the .nup made anew from the dumps found now,
     * then the build. data gets what the plan found (nup, old_bytes, base, update, update_version, dlc, need_bytes, free_bytes) whatever the answer. Refused
     * with dumps_missing (which, and where it looked) or low_space (how much; delete_first is the second choice when that makes room). confirm is the answer to
     * the sheet's question: without it the command is refused with needs_confirm and the question's own text. Returns null when the build was handed to the
     * pipeline, else {code, sentence}.
     */
    public static String[] reimport(Activity a, String titleId, boolean confirm, boolean deleteFirst, Map<String, Object> data) {
        Library lib = Library.of(a);
        if (lib == null) return new String[] {"no_home", "Home was not drawn yet."};
        LibraryScanner.Title t = lib.title(titleId);
        if (t == null) return new String[] {"unknown_title", titleId + " is not in the library (scan first)."};
        if (t.status == LibraryScanner.Status.NO_PROFILE) return new String[] {"not_in_app", String.valueOf(Upstream.profileMissing(t.titleId, t.name))};
        if (lib.building(titleId)) return new String[] {"busy", t.name + " is being built now. Wait for it to finish."};   // another game that builds: this one goes into the build queue (issue #348)
        if (t.base == null || !t.base.isNup()) return new String[] {"not_a_nup", "Import again makes a game's .nup anew: " + t.name + " has none (" + (t.base == null ? "no base game found" : t.base.file.getName()) + ")."};
        if (!PeanutsPrefs.hasKeys(a)) return new String[] {"no_keys", "Add your keys first (Settings › Console), then import."};
        if (!PeanutsPrefs.hasFonts(a)) return new String[] {"no_firmware", "Add your firmware first (Settings › Console), then import."};
        dev.peanuts.build.Reimport.Plan p = GameSheet.plan(a, t);
        data.put("nup", p.nup.getPath());
        data.put("old_bytes", p.oldBytes);
        data.put("base", p.base == null ? null : p.base.getPath());
        data.put("update", p.update == null ? null : p.update.getPath());
        data.put("update_version", p.update == null ? null : (Object) p.updateVersion);
        List<String> dlc = new ArrayList<>();
        for (File f : p.dlcs) dlc.add(f.getPath());
        data.put("dlc", dlc);
        data.put("need_bytes", p.needBytes);
        data.put("free_bytes", p.freeBytes == Long.MAX_VALUE ? null : (Object) p.freeBytes);
        if (p.missing != null) return new String[] {"dumps_missing", p.missing};
        if (p.lowSpace() && !(deleteFirst && p.fitsWithoutOld()))
            return new String[] {"low_space", dev.peanuts.build.Reimport.lowSpace(p) + (p.fitsWithoutOld() ? " That second choice is -e delete_first true." : "")};
        boolean first = deleteFirst && p.lowSpace();   // the old file goes first only when it has to: with room for both it is kept until the new one is complete
        data.put("delete_first", first);
        if (!confirm) return new String[] {"needs_confirm", (first ? dev.peanuts.build.Reimport.deleteFirst(p, t.name) + " Found: " + dev.peanuts.build.Reimport.found(p) + "."
                : dev.peanuts.build.Reimport.confirm(p, t.name)) + " Add -e confirm true."};
        String why = GameSheet.reimport(a, lib, t, CompanionPort.forTitle(Library.catalogNow(), t.titleId), p, first);
        return why == null ? null : new String[] {"refused", why};
    }

    /**
     * The sheet's Import pack… with a path instead of the picker (the control interface's import_pack): {code, sentence} when it cannot start, else null, and
     * answer gets {null, what "Pack imported" says} or {"refused", the sentence} once the import ended (both dialogs show, as from the sheet; PackImportUi.start
     * checks once more on the main thread, and a refusal there comes with its code). It never
     * replaces a pack (peanuts/docs/CONTROL.md, "Not offered"): a game whose pack folder holds one already is refused with pack_there
     */
    public static String[] importPack(Activity a, String titleId, String path, java.util.function.Consumer<String[]> answer) {
        Library lib = Library.of(a);
        LibraryScanner.Title t = lib == null ? null : lib.title(titleId);
        String[] no = PackImportUi.refusal(a, lib, t, titleId);
        if (no != null) return no;
        File source = new File(path);
        if (!source.isFile() && !source.isDirectory()) return new String[] {"no_file", path + " is neither a file nor a folder."};
        dev.peanuts.pack.FirstImport.Game g = PackImportUi.game(a, t, GameSheet.storedUpdate(a, t));
        if (new File(g.target, "pack.tsv").isFile()) return new String[] {"pack_there", g.target + " holds a pack already, and this command replaces none. Import the pack on the game's sheet."};
        PackImportUi.start(a, g, source, answer::accept);
        return null;
    }

    /** Whether the library's build of this title runs now */
    public static boolean building(Activity a, String titleId) {
        Library l = Library.of(a);
        return l != null && l.building(titleId);
    }

    /** Before Play, as a cover's tap: the game's env is brought up to date, and a game that stops without the firmware's fonts says so (null: it can start) */
    public static String fonts(Activity a, String titleId, String name) { return FontGate.refusal(a, titleId, name); }

    /** One game of the library as the status file lists it */
    public static final class Row {
        public String titleId, name, state, game, pack;
        public boolean listed;
        public File companionRoot;
    }

    /**
     * The library's games: upstream's list (a game there is ready only when its pack really starts it, Game.ready, which a cover does not check) and the
     * scan's titles that are not in it. state: ready, build, not_in_app (the caller marks the game that is being built).
     * Reads packs: off the main thread.
     */
    public static List<Row> rows(Context c) {
        List<LibraryScanner.Title> titles = Library.titlesNow();
        List<Row> out = new ArrayList<>();
        Set<LibraryScanner.Title> taken = new HashSet<>();
        for (Upstream.Entry e : Upstream.games(c)) {
            Row r = new Row();
            r.titleId = e.titleId; r.name = e.name; r.game = e.gameDir; r.pack = e.packDir.isEmpty() ? null : e.packDir; r.listed = true;
            r.state = !Upstream.hasProfile(e.titleId) ? "not_in_app" : e.ready ? "ready" : "build";
            for (LibraryScanner.Title t : titles) if (t.launch != null && new File(e.gameDir).equals(t.launch)) { taken.add(t); if (!t.name.isEmpty()) r.name = t.name; break; }
            File pack = r.pack == null ? null : new File(r.pack).getParentFile();
            r.companionRoot = pack == null ? null : new File(pack, "peanuts");
            out.add(r);
        }
        for (LibraryScanner.Title t : titles) {
            if (taken.contains(t)) continue;
            Row r = new Row();
            r.titleId = t.titleId; r.name = t.name;
            r.game = t.launch != null ? t.launch.getPath() : t.base != null ? t.base.file.getPath() : null;
            r.pack = t.packDir == null ? null : t.packDir.getPath();
            r.state = t.status == LibraryScanner.Status.READY ? "ready" : t.status == LibraryScanner.Status.BUILD ? "build" : "not_in_app";
            r.companionRoot = t.companionRoot();
            out.add(r);
        }
        return out;
    }
}
