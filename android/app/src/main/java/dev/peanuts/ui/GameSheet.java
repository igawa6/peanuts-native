package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuiltWith;
import dev.peanuts.build.PackNote;
import dev.peanuts.build.Reimport;
import dev.peanuts.build.SpaceEstimate;
import dev.peanuts.build.UpdateFound;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A game's sheet (SPEC.md): a Banana dialog (PUi.sheet, Close as the negative button) of Setting rows: its base, the update that goes in (the newest found;
 * Choose another… picks a file), its DLC (the ones matched by title ID; Add… picks more), its companion from the catalog ("Install after the build" toggle), then
 * Build (Rebuild for a ready game), which hands the sheet's {@link State} to the build pipeline through {@link Library#startBuild} and closes: progress lives on the
 * game's card. Keys and firmware fonts are a one-time setup requirement (owner, 2026-11-13): without either, Build is off here, with "Add keys…"/"Choose
 * firmware…" beside it (the sheet re-renders once they land); {@link Library#startBuild} checks the same two file-presence flags again as a last line of
 * defense, so nothing but a cheap check runs on every build once setup is done. A game this app has no profile for gets Profile.missing's sentence only.
 * <p>
 * A game whose base is a .nup also gets Import again ({@link #importAgain}): Build and Rebuild use the .nup that is there, so this is the one way to make it
 * anew from the dumps, with the update and DLC found now (dev.peanuts.build.Reimport says what it needs and asks first). A pack that was built without some
 * shaders because the DLC was not imported says so above it (dev.peanuts.build.PackNote).
 */
public final class GameSheet {
    static final int REQ_UPDATE = LibrarySettings.REQ_SHEET_UPDATE, REQ_DLC = LibrarySettings.REQ_SHEET_DLC;

    /** What the pipeline gets: the files to import, where the .nup and the pack go, the companion to install. Public: dev.peanuts.build reads it */
    public static final class State {
        public String titleId, name;
        /** The base: a dump folder, a .nup or an NSP/XCI (then needsImport) */
        public File base; public boolean needsImport; public long baseVersion;
        /** The update file to import with the base (null: none), its version; and the DLC files */
        public File update; public long updateVersion;
        public final List<File> dlcs = new ArrayList<>();
        /** The folder of the game: the dump folder, the .nup's folder, or <game folder>/<name>, where the import writes nup (home/<home name>.nup) */
        public File home, nup;
        /** What upstream starts (the dump or the .nup), null before the import makes it */
        public File launch;
        /** The companion to install into companionRoot after the build (null: none), the catalog entry's package name and version */
        public String companionPackage, companionVersion; public boolean installCompanion; public File companionRoot;
        /** The catalog item (CompanionPort.Item), for the installer */
        public Object companionItem;
        /** The player's explicit "Build anyway" for a version not in the profile's build list (P1), or "Try anyway" on a build card that asked: never set on its own */
        public boolean buildAnyway;
        /** Import again: the .nup to make anew from base/update/dlcs (null: an ordinary build), and its second choice, the old file deleted first */
        public File replaceNup; public boolean deleteOldFirst;
    }

    /** The open sheet (the activity forwards the file pickers' results to it: LibrarySettings.result) */
    static GameSheet current;

    private final Activity a;
    private final LibraryScanner.Title title;
    private final CompanionPort.Item companion;
    private final Library lib;
    private AlertDialog dialog;
    private LinearLayout box;
    private LibraryScanner.Source chosenUpdate;
    private final List<File> extraDlc = new ArrayList<>();
    private boolean installCompanion;

    private GameSheet(Activity a, LibraryScanner.Title t, CompanionPort.Item companion, Library lib) {
        this.a = a; this.title = t; this.companion = companion; this.lib = lib;
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) chosenUpdate = u;
        if (chosenUpdate == null && !t.updates.isEmpty()) chosenUpdate = t.updates.get(0);   // sorted newest first
        for (String d : PeanutsPrefs.chosenDlc(a, t.titleId)) extraDlc.add(new File(d));
        installCompanion = companion != null && PeanutsPrefs.installCompanion(a, t.titleId);
    }

    /** MainActivity's Manage › Build again (the PEANUTS row): the sheet of that title */
    public static void open(Activity a, String titleId) { Library.openSheet(a, titleId); }

    static void show(Activity a, LibraryScanner.Title t, CompanionPort.Item companion, Library lib) {
        GameSheet s = new GameSheet(a, t, companion, lib);
        current = s;
        s.open();
        dev.peanuts.ctl.Ctl.screen();   // the control interface's status file names the open sheet
    }

    private void open() {
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        render();
        dialog = PUi.sheet(a, title.name, box).setNegativeButton("Close", null).create();
        dialog.setOnDismissListener(d -> { if (current == this) current = null; dev.peanuts.ctl.Ctl.screen(); });
        Motion.sheet(dialog);
        Haptics.quiet(200);   // the focus that lands inside the sheet is silent: the press that opened it was the feedback
        dialog.show();
        android.widget.Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);   // Close is quiet (cancel is everywhere); the D-pad on it ticks like the rows
        if (close != null) close.setOnKeyListener(Haptics.KEYS);
    }

    private PUi.Setting companionRow;   // the Companion row of this render: its switch changes its line in place (a rebuild would cut the switch's own animation)

    /** The Companion row's line, as the sheet's choice and the disk are now */
    private CharSequence companionLine() {
        String installed = companion == null ? null : CompanionPort.installedVersion(title.companionRoot(), companion.packageName);
        // a page made for this game on the second screen while it plays (map, stats, items); its package name stays out of the line
        return companion == null ? "No dual-screen companion is available for this game"
                : installed == null ? PUi.joined("Version " + companion.version, size(companion.size), installCompanion ? "Installs after the build" : "Not installed after the build")
                : installed.equals(companion.version) ? PUi.joined("Version " + installed + " installed", "up to date")
                : PUi.joined("Version " + installed + " installed", "version " + companion.version + " " + (installCompanion ? "after the build" : "available"));
    }

    private void render() {
        try { rows(); } finally { Haptics.watch(box); }   // the D-pad's focus haptic on every row, switch and button the sheet has now
    }

    private void rows() {
        box.removeAllViews();
        String missing = title.status == LibraryScanner.Status.NO_PROFILE ? Upstream.profileMissing(title.titleId, title.name) : null;
        if (missing != null) { PUi.add(box, PUi.body(a, missing), 0); return; }
        boolean ready = title.status == LibraryScanner.Status.READY;
        boolean building = lib.building(title.titleId);
        boolean nupBase = title.base != null && title.base.isNup();
        PUi.add(box, PUi.body(a, ready
                ? "Rebuild makes again the parts of the game's build that no longer fit this app version, and updates the dual-screen companion. It is quick when little changed."
                + (nupBase ? " It keeps the game's .nup file. Import again makes that file again from your game files, with the update and DLC found now." : "")
                : "Build makes one .nup file from the files below, builds the game from it and installs the dual-screen companion. It takes about 25 minutes, in the background."), 0);
        // A pack that Try anyway built without the shaders of add-on content that was not imported (PackNote, written when that build finished)
        long[] without = nupBase ? PackNote.without(title.packDir) : null;
        if (without != null) PUi.add(box, PUi.warnChip(a, "Built without DLC shaders", "Built without DLC shaders", String.format(Locale.getDefault(),
                "Built without %,d of its %,d shaders. They come with DLC that was not imported. The base game runs, and what the DLC adds is "
                + "drawn without them. Import again takes the DLC found now.", without[0], without[1])), 12);
        if (ready) renderBuiltWith();
        // Base
        LibraryScanner.Source b = title.base;
        boolean baseGone = b != null && !b.file.exists();
        CharSequence baseLine = b == null ? "None found"
                : baseGone ? b.file.getName() + ": this file is no longer where the app found it. Rescan, or pick the game folder again in Settings › Library."
                : PUi.joined(b.file.getName(), b.version > 0 || !b.isDump() && !b.isNup() ? versionText(b.version) : null,
                        b.isDump() ? "dump folder" : b.isNup() ? ".nup" : dev.peanuts.build.Compressed.kind(b.file).isEmpty() ? "import needed" : dev.peanuts.build.Compressed.kind(b.file) + ", import needed",
                        !b.publisher.isEmpty() ? b.publisher : null);
        PUi.add(box, new PUi.Setting(a, "📀", "Base", null).value(baseLine).view, 12);
        // Update
        LibraryScanner.Source newest = title.updates.isEmpty() ? null : title.updates.get(0);
        boolean updateGone = chosenUpdate != null && !chosenUpdate.file.exists();
        CharSequence up = updateGone ? chosenUpdate.file.getName() + ": this file is no longer where the app found it. Pick it again."
                : chosenUpdate == null ? PUi.joined(title.updates.isEmpty() ? "None found" : "None", "Choose…")
                : PUi.joined(chosenUpdate.file.getName(), versionText(chosenUpdate.version) + (chosenUpdate == newest ? ", the newest found" : ""), "Choose another…");
        PUi.add(box, new PUi.Setting(a, "⬆️", "Update", v -> { if (!building) { Haptics.play(v, Haptics.Word.TICK); LibrarySettings.pickFile(a, REQ_UPDATE, "Pick the update (.nsp, .nsz, .xci or .xcz)"); } }).value(up).chevron(a).view, 0);
        // Version (P1): the version the player has vs. what this build's profile supports, read before Build rather than discovered deep into a run
        boolean fileGone = baseGone || updateGone;
        if (!fileGone) renderVersionRow();
        // DLC
        List<String> names = new ArrayList<>();
        for (LibraryScanner.Source d : title.dlcs) names.add(d.name.isEmpty() ? d.file.getName() : d.name);
        for (File f : extraDlc) { boolean known = false; for (LibraryScanner.Source d : title.dlcs) if (d.file.equals(f)) known = true; if (!known) names.add(f.getName()); }
        CharSequence dl = PUi.joined(names.isEmpty() ? "None found" : names.size() + " found: " + String.join(", ", names), "Add…");
        PUi.add(box, new PUi.Setting(a, "➕", "DLC", v -> { if (!building) { Haptics.play(v, Haptics.Word.TICK); LibrarySettings.pickFile(a, REQ_DLC, "Pick a DLC (.nsp, .nsz, .xci or .xcz)"); } }).value(dl).chevron(a).view, 0);
        if (!extraDlc.isEmpty()) PUi.add(box, PUi.button(a, "Forget the DLC you added", PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.CLICK); extraDlc.clear(); saveDlc(); render(); }), 0, PUi.wrap());
        // Companion
        File root = title.companionRoot();
        String installed = companion == null ? null : CompanionPort.installedVersion(root, companion.packageName);
        boolean canInstall = companion != null && !companion.version.equals(installed);
        PUi.Setting comp = new PUi.Setting(a, "🖥️", "Dual-screen companion", null).value(companionLine());
        companionRow = comp;
        // the switch: only the row's line depends on it (the build reads the choice when it starts), so the line is written in place and the rows stay
        if (canInstall) comp.toggle(a, installCompanion, (v, on) -> {
            Haptics.toggle(v, on);
            installCompanion = on;
            PeanutsPrefs.installCompanion(a, title.titleId, on);
            if (companionRow == comp) comp.value(companionLine());
        });
        PUi.add(box, comp.view, 0);
        // P2.5: "Versions…" opens the Companion Versions sheet (dev.peanuts.ui.CompanionVersions) when the catalog has this title at
        // all. onClose: installedVersion (above) is read live from disk on every render, not cached in a field here, so re-rendering after a rollback/switch
        // is the whole refresh — the review's "cached state goes stale" note, satisfied without a separate cache to invalidate
        if (companion != null) PUi.add(box, PUi.button(a, "Versions…", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> dev.peanuts.ui.CompanionVersions.open(a, title.titleId, this::render))), 2, PUi.wrap());
        // Launcher support (owner request, LAUNCHERS.md): a pinned shortcut that starts this title through dev.nxnative.android.FrontendLaunch's own
        // title_id path (FRONTENDS.md) — a ready game starts at once, a game not yet built opens Home with it instead. Shown whenever the launcher
        // supports pinning (dev.peanuts.launch.GameShortcuts.supported), not only for a READY title, matching how Home's own Add-to-home-screen entry
        // (upstream, FRONTENDS.md) is not restricted to ready games either
        if (dev.peanuts.launch.GameShortcuts.supported(a))
            PUi.add(box, PUi.button(a, "Add to home screen", PUi.Kind.QUIET, Haptics.on(Haptics.Word.CLICK, v -> addToHomeScreen())), 2, PUi.wrap());
        PUi.add(box, PUi.divider(a), 12, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, PUi.dp(a, 1)));
        // Build prerequisites (owner, 2026-11-13): keys and firmware fonts are a one-time setup requirement, checked cheaply (file presence only, never
        // re-validated against the importer here) before any build starts — not just an NSP/XCI import. A missing firmware font can crash some games at
        // boot (dm/games/01008CF01BAAC000/README.md). This chip and the two buttons below stop appearing anywhere once both are in the app.
        boolean needKeys = !PeanutsPrefs.hasKeys(a);
        boolean needFonts = !PeanutsPrefs.hasFonts(a);
        boolean missingSetup = needKeys || needFonts;
        if (missingSetup) {
            String what = needKeys && needFonts ? "Keys and firmware are needed before building."
                    : needKeys ? "Keys are needed before building."
                    : "Firmware is needed before building.";
            LinearLayout r = PUi.row(a);
            r.addView(PUi.warnChip(a, "Setup needed", "Setup needed", what));
            TextView t = PUi.small(a, what);
            t.setPadding(PUi.dp(a, 10), 0, 0, 0);
            r.addView(t, PUi.weight());
            PUi.add(box, r, 14);
        }
        if (title.base == null) PUi.add(box, PUi.body(a, "No base game found. Only an update or DLC is in the folder."), 14);
        if (fileGone) PUi.add(box, PUi.body(a, "Pick the file again above before building."), 14);
        if (building) PUi.add(box, PUi.small(a, "Building now: the progress is on the game's card."), 14);
        // the build queue (issue #348): a game that waits says its place; Build here gives it this sheet's choices, Remove from queue takes it out
        dev.peanuts.build.BuildQueue.Item queued = building ? null : Library.queued(title.titleId);
        if (queued != null) {
            dev.peanuts.build.BuildQueue q = dev.peanuts.build.BuildPipeline.queueNow();
            String p = q == null ? "" : q.placeText(queued);
            PUi.add(box, PUi.small(a, (p.isEmpty() ? "In the build queue" : "In the build queue, " + p) + ": it builds when the games before it are done. "
                    + "Add to queue keeps its place with the choices made here."), 14);
            PUi.add(box, PUi.button(a, "Remove from queue", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> {
                if (dev.peanuts.build.BuildPipeline.remove(a, queued.id)) dialog.dismiss();
                else { Haptics.play(v, Haptics.Word.REJECT); rows(); }
            })), 4, PUi.wrap());
        }
        String chosenFolder = PeanutsPrefs.chosenBuildDir(a, title.titleId);
        String defaultFolder = PeanutsPrefs.buildDir(a);
        PUi.Setting destination = new PUi.Setting(a, "🗂️", "Build destination",
                Haptics.on(Haptics.Word.TICK, v -> pickBuildFolder())).value(chosenFolder != null ? chosenFolder
                        : defaultFolder != null ? PUi.joined("Default", defaultFolder) : "Default location. Tap to choose a folder.").chevron(a);
        destination.view.setEnabled(!building);
        PUi.add(box, destination.view, 12);
        // Space needed (P4/SpaceEstimate, warn-only — never disables Build, the multiplier is uncalibrated)
        renderSpaceLine();
        boolean unsupported = chosenVersionState() == LibraryScanner.VersionState.UNSUPPORTED;
        LinearLayout actions = PUi.row(a);
        // while another game builds or waits, Build puts this one into the build queue (issue #348), and says so
        boolean toQueue = !building && dev.peanuts.build.BuildPipeline.queueing();
        PUi.Action build = PUi.action(a, R.drawable.ic_build, toQueue ? "Add to queue" : ready ? "Rebuild" : unsupported ? "Build anyway" : "Build", PUi.Kind.PRIMARY, v -> startBuild());
        build.setEnabled(!building && title.base != null && !missingSetup && title.home != null && !fileGone);
        actions.addView(build, PUi.weight());
        if (needKeys) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(a, 10);
            actions.addView(PUi.button(a, "Add keys…", PUi.Kind.SOFT, Haptics.on(Haptics.Word.TICK, v -> LibrarySettings.pickKeys(a))), lp);
        }
        if (needFonts) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(a, 10);
            actions.addView(PUi.button(a, "Choose firmware…", PUi.Kind.SOFT, Haptics.on(Haptics.Word.TICK, v -> LibrarySettings.pickFirmwareSource(a))), lp);
        }
        PUi.add(box, actions, 14);
        if (nupBase) {
            View again = PUi.button(a, "Import again…", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> importAgain(a, lib, title, companion, () -> { if (dialog != null) dialog.dismiss(); })));
            again.setEnabled(!building);
            PUi.add(box, again, 4, PUi.wrap());
        }
        // Import pack… before the first build (GitHub issue #44): a pack made on a PC or exported from another phone goes where this Build would put its own
        // (PackImportUi). A built game has it on its page's Storage tab (upstream's PackUi)
        if (PackImportUi.offered(title)) {
            View imp = PUi.button(a, "Import pack…", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> PackImportUi.offer(a, lib, title, chosenUpdate)));
            imp.setEnabled(!building);
            PUi.add(box, imp, 4, PUi.wrap());
        }
    }

    // ---- Import again (dev.peanuts.build.Reimport) ----

    /**
     * What an import again of this title would do, from the last scan: the packages inside its .nup (the scan lists them under the title with the .nup as their
     * file), the dumps found for it (the base's originals, the updates and DLC that are files of their own, the DLC added in the sheet), the update picked in
     * the sheet, the folders the library reads and the free space where the .nup is. No file is read here
     */
    static Reimport.Plan plan(Activity a, LibraryScanner.Title t) {
        List<Reimport.Part> inside = new ArrayList<>(), found = new ArrayList<>();
        for (LibraryScanner.Source s : t.originals) found.add(new Reimport.Part(Reimport.BASE, s.titleId, s.version, s.file));
        for (LibraryScanner.Source s : t.updates) (s.needsImport() ? found : inside).add(new Reimport.Part(Reimport.UPDATE, s.titleId, s.version, s.needsImport() ? s.file : null));
        for (LibraryScanner.Source s : t.dlcs) (s.needsImport() ? found : inside).add(new Reimport.Part(Reimport.DLC, s.titleId, s.version, s.needsImport() ? s.file : null));
        for (String d : PeanutsPrefs.chosenDlc(a, t.titleId)) {
            File f = new File(d);
            boolean known = false;
            for (LibraryScanner.Source s : t.dlcs) if (s.file.equals(f)) known = true;
            if (!known && f.isFile()) found.add(new Reimport.Part(Reimport.DLC, "", 0, f));
        }
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        File nup = t.base.file;
        return Reimport.plan(nup, inside, found, picked == null ? null : new File(picked), PeanutsPrefs.roots(a), SpaceEstimate.freeBytes(nup.getParentFile()));
    }

    /** Whether the library found add-on content for this title as a file of its own (not only inside its .nup): what an import again would add */
    static boolean dlcFound(LibraryScanner.Title t) {
        for (LibraryScanner.Source s : t.dlcs) if (s.needsImport() && s.file.isFile()) return true;
        return false;
    }

    /**
     * Import again, from the sheet's button or the build card's: says what is missing and does nothing; else asks (what is deleted, what is kept, what was
     * found), or, when the new file does not fit beside the old one, says how much is needed and offers deleting the old one first as a second question with
     * its risk. started runs when the build was accepted
     */
    static void importAgain(Activity a, Library lib, LibraryScanner.Title t, CompanionPort.Item companion, Runnable started) { importAgain(a, lib, t, companion, started, null); }

    /** named: what the cover's "update found" question named, when the player said Build there (Library.askFound). The checks stay; the confirmation does not
     *  come twice when the plan takes just that (UpdateFound.takesWhatItNamed), and comes when it takes another update (the newest file beside the .nup, one the
     *  profile does not know, or the one picked on the game's page: found in review) */
    static void importAgain(Activity a, Library lib, LibraryScanner.Title t, CompanionPort.Item companion, Runnable started, UpdateFound.Found named) {
        View anchor = a.getWindow().getDecorView();
        if (t == null || t.base == null || !t.base.isNup()) return;
        if (lib.building(t.titleId)) { Haptics.play(anchor, Haptics.Word.REJECT); Toast.makeText(a, "Building now: the progress is on the game's card.", Toast.LENGTH_LONG).show(); return; }
        Reimport.Plan p = plan(a, t);
        if (p.missing != null) { Haptics.play(anchor, Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Import again").setMessage(p.missing).setPositiveButton("OK", null).show(); return; }
        if (p.lowSpace()) {
            Haptics.play(anchor, Haptics.Word.REJECT);
            AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Not enough free space").setMessage(Reimport.lowSpace(p)).setNegativeButton("Close", null);
            if (p.fitsWithoutOld()) b.setNeutralButton("Delete the old file first…", (d, w) -> PUi.confirmDanger(a, "Delete the old game file first?", Reimport.deleteFirst(p, t.name),
                    "Delete and import", () -> startImport(a, lib, t, companion, p, true, started)));
            b.show();
            return;
        }
        long had = -1;   // the update the .nup holds (as Reimport.plan takes it)
        for (LibraryScanner.Source s : t.updates) if (!s.needsImport()) had = Math.max(had, s.version);
        if (UpdateFound.takesWhatItNamed(named, had, p.update != null ? p.updateVersion : -1)) { startImport(a, lib, t, companion, p, false, started); return; }
        PUi.confirmDanger(a, "Import again?", Reimport.confirm(p, t.name), "Import again", () -> startImport(a, lib, t, companion, p, false, started));
    }

    /** The plan to the pipeline, after the player said yes: the sheet's stored choices (the companion's switch), the plan's dumps, and the .nup to replace.
     *  Returns why it could not start, or null */
    static String reimport(Activity a, Library lib, LibraryScanner.Title t, CompanionPort.Item companion, Reimport.Plan p, boolean deleteOldFirst) {
        State s = resumeState(a, t, companion);
        s.base = p.base; s.needsImport = true;
        s.update = p.update; s.updateVersion = p.updateVersion;
        s.dlcs.clear(); s.dlcs.addAll(p.dlcs);
        s.replaceNup = p.nup; s.deleteOldFirst = deleteOldFirst;
        s.nup = p.nup;
        return lib.startBuild(s);
    }

    private static void startImport(Activity a, Library lib, LibraryScanner.Title t, CompanionPort.Item companion, Reimport.Plan p, boolean deleteOldFirst, Runnable started) {
        View anchor = a.getWindow().getDecorView();
        String why = reimport(a, lib, t, companion, p, deleteOldFirst);
        if (why != null) { Haptics.play(anchor, Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Import again").setMessage(why).setPositiveButton("OK", null).show(); return; }
        if (lib.building(t.titleId)) { Haptics.play(anchor, Haptics.Word.HEAVY); if (started != null) started.run(); }   // accepted (a refusal by the pipeline says so in its own dialog)
    }

    private static String size(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0) : String.format(Locale.US, "%.0f KB", bytes / 1024.0); }

    /**
     * The version state of whichever file is actually chosen right now (found in review): {@code title.versionState} (LibraryScanner) is
     * computed from {@code title.version()}, the newest update found on disk — not the file the player picked with "Choose another…", which can be an
     * older, or an unlisted, file. Recomputing this live on every render (instead of caching it) means {@link #picked} needs no extra step to stay current:
     * calling {@code render()} after a pick is already enough. Returns UNKNOWN when there is no profile build data to compare against (never blocks or warns)
     */
    private LibraryScanner.VersionState chosenVersionState() {
        if (title.supportedBuilds.isEmpty()) return LibraryScanner.VersionState.UNKNOWN;
        long have = chosenUpdate != null ? chosenUpdate.version : (title.base != null ? title.base.version : 0);
        if (!title.supportedBuilds.containsKey(have)) return LibraryScanner.VersionState.UNSUPPORTED;
        return have == title.recommendedVersion ? LibraryScanner.VersionState.RECOMMENDED : LibraryScanner.VersionState.SUPPORTED_OTHER;
    }

    /**
     * The Version row (P1): "You have" is the chosen update's version (else the base's), "This build supports" lists the profile's builds with
     * the recommended one marked. Nothing is shown for UNKNOWN (no profile builds data: never a reason to block or warn)
     */
    private void renderVersionRow() {
        LibraryScanner.VersionState vs = chosenVersionState();
        if (vs == LibraryScanner.VersionState.UNKNOWN || vs == LibraryScanner.VersionState.NOT_IN_APP) return;
        long have = chosenUpdate != null ? chosenUpdate.version : (title.base != null ? title.base.version : 0);
        String haveText = versionText(have) + (title.supportedBuilds.get(have) != null ? " (" + title.supportedBuilds.get(have) + ")" : "");
        String supports = supportedShortText();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(PUi.small(a, "You have: " + haveText));
        col.addView(PUi.small(a, "This app version supports: " + supports));
        int tint;
        String note;
        switch (vs) {
            case RECOMMENDED: tint = R.color.bn_leaf; note = "Matches the app's list for this game."; break;
            case SUPPORTED_OTHER: tint = R.color.bn_banana; note = "Supported, but its first build is slower because it looks at every shader. The app's list for this game is for " + recommendedText() + "."; break;
            default: tint = R.color.bn_danger; note = "Untested game version. Build anyway may make a game that does not work. Choose another… picks the recommended update."; break;   // UNSUPPORTED
        }
        TextView n = PUi.text(a, note, 13, tint, 600);
        n.setPadding(0, PUi.dp(a, 2), 0, 0);
        col.addView(n);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView tile = PUi.text(a, "🧩", 18, R.color.bn_text, 400);   // puzzle piece, matches the mockup's Version row icon
        tile.setGravity(android.view.Gravity.CENTER);
        tile.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 12), 0, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40)));
        LinearLayout.LayoutParams clp = PUi.fill();
        clp.setMargins(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        row.addView(col, clp);
        PUi.add(box, row, 10);
    }

    /** The profile's builds in words ("1.0.0, 1.0.1 or 1.2.1 (recommended)"), for the Version row and the "Build anyway" confirm dialog */
    private String supportedShortText() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Long, String> e : title.supportedBuilds.entrySet()) {
            String d = e.getValue() != null ? e.getValue() : versionText(e.getKey());
            parts.add(d + (e.getKey() == title.recommendedVersion ? " (recommended)" : ""));
        }
        return parts.isEmpty() ? "nothing known" : String.join(", ", parts);
    }

    /** The recommended build's display version, else its version number */
    private String recommendedText() {
        String d = title.supportedBuilds.get(title.recommendedVersion);
        return d != null ? d : versionText(title.recommendedVersion);
    }

    /**
     * A dump-folder or .nup base's size on disk, cached across sheet opens by title ID (found in review: Engage's dump folder alone is 206,874
     * files — one base, the game's exefs plus romfs, so the recursive {@code SpaceEstimate.onDiskBytes} walk is real filesystem I/O, not a quick stat, and
     * must never run on the UI thread). A title's base rarely changes between sheet opens, and this is only ever a cosmetic estimate, so a process-lifetime
     * cache (never invalidated by a rescan) is an acceptable trade for not re-walking a 200K-file dump every time its sheet opens
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> ON_DISK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean computingSpace;   // this sheet instance already started the background walk for its base — render() runs again once, not on every pass

    /**
     * The "Needs about X GB" line (P4/SpaceEstimate §3.2): warn-only, never disables Build. Compares
     * the game folder's volume against only the import size, and the app's external storage volume (not internal — {@code getExternalFilesDir(null)},
     * where AppStorage actually puts the pack) against only the pack size, whose basis is the merged .nup's size when importing, else the existing base's
     * real size on disk. For a dump-folder/.nup base not yet in {@link #ON_DISK_CACHE}, this renders "Checking space…" and kicks off exactly one background
     * walk for this sheet instance; the real line replaces it once the walk lands and a render fires again — never a synchronous walk on the UI thread
     */
    private void renderSpaceLine() {
        boolean needsImport = title.base != null && title.base.needsImport();
        // the .nup's size from the probe (a compressed file's length is not it), and what unpacking a solid .nsz needs beside it while it imports
        long importBytes = 0, unpackBytes = 0;
        if (needsImport) {
            List<LibraryScanner.Source> in = new ArrayList<>();
            in.add(title.base);
            if (chosenUpdate != null && chosenUpdate.needsImport()) in.add(chosenUpdate);
            for (LibraryScanner.Source d : title.dlcs) if (d.needsImport()) in.add(d);
            for (LibraryScanner.Source s : in) { importBytes += s.importBytes(); unpackBytes += s.unpack; }
            for (File f : extraDlc) importBytes += f.length();
        }
        Long cachedBasis = needsImport || title.base == null ? null : ON_DISK_CACHE.get(title.titleId);
        if (!needsImport && title.base != null && cachedBasis == null) {
            PUi.add(box, PUi.small(a, "Checking space…"), 14);
            if (!computingSpace) {
                computingSpace = true;
                File baseFile = title.base.file;
                String titleId = title.titleId;
                new Thread(() -> {
                    long size = SpaceEstimate.onDiskBytes(baseFile);
                    ON_DISK_CACHE.put(titleId, size);
                    a.runOnUiThread(() -> { if (current == this && dialog != null && dialog.isShowing()) render(); });
                }, "peanuts-space").start();
            }
            return;
        }
        long packBasis = needsImport ? importBytes : (cachedBasis != null ? cachedBasis : 0L);
        // A build folder (Settings - Library - Build folder), when set, is where this title's .nup and pack both go instead: measure its volume, not the
        // game folder's / the app's own storage
        String root = PeanutsPrefs.chosenBuildDir(a, title.titleId);
        File buildGameDir = root == null ? PeanutsPrefs.buildGameDir(a, title.titleId) : PeanutsPrefs.buildGameDir(root, title.titleId);
        File importDest = buildGameDir != null ? buildGameDir : PeanutsPrefs.homeFor(a, title.base == null ? null : title.base.file);   // the game folder that holds it
        File packVolume = buildGameDir != null ? new File(buildGameDir, "pack") : a.getExternalFilesDir(null);
        String warn = SpaceEstimate.warning(importBytes + unpackBytes, packBasis, needsImport, importDest, packVolume);
        if (warn != null) PUi.add(box, PUi.warnChip(a, "Low disk space", "Low disk space", warn + SpaceEstimate.unpackSentence(unpackBytes) + " Build still works, but it may run out of space part way."), 14);
        else {
            long need = SpaceEstimate.totalNeeded(importBytes + unpackBytes, packBasis, needsImport);
            PUi.add(box, PUi.small(a, "Needs about " + SpaceEstimate.words(need) + " free, a rough estimate." + SpaceEstimate.unpackSentence(unpackBytes)), 14);
        }
    }

    private BuiltWith.Result builtWith;   // the release that made this game's shaders, once read for this sheet
    private boolean readingBuiltWith;

    /**
     * "Built with 0.1.7" (BuiltWith): a quiet line for this app version's shaders or ones the upgrade takes at the next start, the same line with a plain note
     * for ones made again once while they play (BuiltWith.note), a warning chip that points to Rebuild for older ones, nothing when the version is not known. The library headers are read once per sheet on a thread (a slow SD card must not hold the
     * sheet, as the space line's walk); the sheet renders again when they say something
     */
    private void renderBuiltWith() {
        if (builtWith == null) {
            if (readingBuiltWith || title.packDir == null) return;
            readingBuiltWith = true;
            File pack = title.packDir;
            String titleId = title.titleId;
            new Thread(() -> {
                BuiltWith.Result r = BuiltWith.of(pack, titleId, dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN, dev.nxnative.android.BuildConfig.NX_DMSH_CHAIN);
                a.runOnUiThread(() -> {
                    builtWith = r;
                    if (r.kind != BuiltWith.Kind.UNKNOWN && current == this && dialog != null && dialog.isShowing()) render();
                });
            }, "peanuts-built-with").start();
            return;
        }
        String label = BuiltWith.label(builtWith), warning = BuiltWith.warning(builtWith), note = BuiltWith.note(builtWith);
        if (warning != null) PUi.add(box, PUi.warnChip(a, label, label, warning), 12);
        else if (label != null) PUi.add(box, PUi.small(a, note != null ? label + ". " + note : label), 12);
    }

    /** The state the pipeline gets, from the sheet as it is now */
    State state() {
        State s = new State();
        s.titleId = title.titleId; s.name = title.name;
        s.base = title.base == null ? null : title.base.file; s.needsImport = title.base != null && title.base.needsImport(); s.baseVersion = title.base == null ? 0 : title.base.version;
        if (chosenUpdate != null && chosenUpdate.needsImport()) { s.update = chosenUpdate.file; s.updateVersion = chosenUpdate.version; }
        for (LibraryScanner.Source d : title.dlcs) if (d.needsImport() && !s.dlcs.contains(d.file)) s.dlcs.add(d.file);
        for (File f : extraDlc) if (f.isFile() && !s.dlcs.contains(f)) s.dlcs.add(f);
        s.home = title.home; s.launch = title.launch;
        if (s.home != null) s.nup = s.needsImport ? new File(s.home, s.home.getName() + ".nup") : s.launch;
        s.companionRoot = title.companionRoot();
        if (companion != null && installCompanion) { s.companionPackage = companion.packageName; s.companionVersion = companion.version; s.installCompanion = true; s.companionItem = companion; }
        return s;
    }

    /**
     * The same {@link State} {@link #state()} would build, rebuilt from a title and its persisted sheet choices (PeanutsPrefs) with no sheet open — for the
     * build card's "Resume" (found in review): it must actually resume the job, not just reopen the sheet. The job itself resumes on its own
     * (Prepare.java's existing job.json path); this only has to reconstruct the same request the sheet would have sent
     */
    static State resumeState(Activity a, LibraryScanner.Title t, CompanionPort.Item companion) {
        State s = new State();
        s.titleId = t.titleId; s.name = t.name;
        s.base = t.base == null ? null : t.base.file; s.needsImport = t.base != null && t.base.needsImport(); s.baseVersion = t.base == null ? 0 : t.base.version;
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        LibraryScanner.Source chosen = null;
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) chosen = u;
        if (chosen == null && !t.updates.isEmpty()) chosen = t.updates.get(0);
        if (chosen != null && chosen.needsImport()) { s.update = chosen.file; s.updateVersion = chosen.version; }
        for (LibraryScanner.Source d : t.dlcs) if (d.needsImport() && !s.dlcs.contains(d.file)) s.dlcs.add(d.file);
        for (String p : PeanutsPrefs.chosenDlc(a, t.titleId)) { File f = new File(p); if (f.isFile() && !s.dlcs.contains(f)) s.dlcs.add(f); }
        s.home = t.home; s.launch = t.launch;
        if (s.home != null) s.nup = s.needsImport ? new File(s.home, s.home.getName() + ".nup") : s.launch;
        s.companionRoot = t.companionRoot();
        boolean installCompanion = companion != null && PeanutsPrefs.installCompanion(a, t.titleId);
        if (companion != null && installCompanion) { s.companionPackage = companion.packageName; s.companionVersion = companion.version; s.installCompanion = true; s.companionItem = companion; }
        // buildAnyway stays false: a job that already needed it is already marked tryAnyway in its own job.json (Prepare.run0 reads that, not this request's
        // consent, once a job exists), and a job that never needed it does not care
        return s;
    }

    private void pickBuildFolder() {
        if (lib.building(title.titleId)) return;
        List<String> dirs = PeanutsPrefs.buildDirs(a);
        CharSequence[] labels = new CharSequence[dirs.size() + 1];
        labels[0] = dirs.isEmpty() ? "Default location" : PUi.joined("Default", dirs.get(0));
        for (int i = 0; i < dirs.size(); i++) labels[i + 1] = dirs.get(i);
        String chosen = PeanutsPrefs.chosenBuildDir(a, title.titleId);
        new AlertDialog.Builder(a).setTitle("Build destination").setSingleChoiceItems(labels, dirs.indexOf(chosen) + 1, (d, w) -> {
            d.dismiss();
            chooseBuildFolder(w == 0 ? null : dirs.get(w - 1));
        }).setPositiveButton("Add folder…", (d, w) -> LibrarySettings.addBuildFolder(a)).setNegativeButton("Cancel", null).show();
    }

    void chooseBuildFolder(String path) {
        PeanutsPrefs.chosenBuildDir(a, title.titleId, path);
        render();
    }

    /**
     * Build, or Build anyway: for an UNSUPPORTED version (P1) this always asks first — Prepare.java never relaxes the check on its
     * own any more, so without this confirm the build would just stop and ask again from the failed card instead of going on
     */
    private void startBuild() {
        if (chosenVersionState() == LibraryScanner.VersionState.UNSUPPORTED) {
            PUi.confirmDanger(a, "Build anyway?", "Version " + versionText(chosenUpdate != null ? chosenUpdate.version : title.base != null ? title.base.version : 0)
                            + " is untested. This app version supports " + supportedShortText() + ". The build goes on without that check, so the game may not start, "
                            + "may crash or may draw parts wrongly, and its first build is slower because it looks at every shader. Your game files and saves do not change.",
                    "Build anyway", () -> doStartBuild(true));
        } else doStartBuild(false);
    }

    private void doStartBuild(boolean anyway) {
        State s = state();
        s.buildAnyway = anyway;
        if (s.base == null) { Haptics.play(box, Haptics.Word.REJECT); Toast.makeText(a, "No base game found", Toast.LENGTH_SHORT).show(); return; }
        if (s.home == null) { Haptics.play(box, Haptics.Word.REJECT); Toast.makeText(a, "Set the game folder first in Settings › Library", Toast.LENGTH_LONG).show(); return; }
        String why = lib.startBuild(s);
        if (why != null) { Haptics.play(box, Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Build").setMessage(why).setPositiveButton("OK", null).show(); return; }
        if (lib.building(title.titleId) || Library.queued(title.titleId) != null) Haptics.play(box, Haptics.Word.HEAVY);   // the build was accepted or queued (a refusal by the pipeline says so in its own dialog)
        dialog.dismiss();
    }

    /** A picked file (LibrarySettings.result) for the Update and DLC rows */
    void picked(int request, File f) {
        if (request == REQ_UPDATE) {
            PeanutsPrefs.chosenUpdate(a, title.titleId, f.getPath());
            LibraryScanner.Source hit = null;
            for (LibraryScanner.Source u : title.updates) if (u.file.equals(f)) hit = u;
            if (hit == null) {
                List<LibraryScanner.Source> probed = new LibraryScanner(a).probe(f);
                for (LibraryScanner.Source s : probed) if (s.kind == LibraryScanner.UPDATE) hit = s;
                if (hit == null) hit = new LibraryScanner.Source(f, LibraryScanner.UPDATE, title.titleId, title.titleId, 0, "", "");
                title.updates.add(hit);
            }
            chosenUpdate = hit;
        } else if (request == REQ_DLC) {
            if (!dev.peanuts.build.BuildRequest.isDlcFile(f)) {
                Toast.makeText(a, "Choose a DLC file (.nsp, .nsz, .xci or .xcz). Unsupported file: " + f.getName(), Toast.LENGTH_LONG).show();
                return;
            }
            if (!extraDlc.contains(f)) extraDlc.add(f);
            saveDlc();
        }
        render();
    }

    /**
     * Add to home screen (owner request, LAUNCHERS.md): pins a shortcut for this exact title ID, with the library's own cover art as its icon. The
     * icon file is read off the UI thread (it can be a disk read the same as {@link #renderSpaceLine}'s walk, and on a game whose cover has not been
     * fetched yet {@link LibraryScanner#icon} itself may call out to the importer); {@link dev.peanuts.launch.GameShortcuts#pin} only ever receives
     * this title's own titleId and name, never a path, so the shortcut it builds can only ever point back at a title the library itself scanned
     */
    private void addToHomeScreen() {
        String titleId = title.titleId, name = title.name;
        new Thread(() -> {
            File f = LibraryScanner.icon(a, title);
            Bitmap cover = f == null ? null : BitmapFactory.decodeFile(f.getPath());
            a.runOnUiThread(() -> dev.peanuts.launch.GameShortcuts.pin(a, titleId, name, cover));
        }, "peanuts-shortcut").start();
    }

    /** The keys landed (Add keys…): Build comes on if firmware fonts are there too. NOTE for the owner of LibrarySettings.java (not touched here): only
     *  copyKeys calls this today; extractFonts does not call it after "Choose firmware…" lands fonts, so a sheet already open needs closing and reopening
     *  to notice new fonts. Calling this from extractFonts's UI.post the same way keysLanded does would close that gap. */
    void keysChanged() { render(); }

    private void saveDlc() {
        List<String> l = new ArrayList<>();
        for (File f : extraDlc) l.add(f.getPath());
        PeanutsPrefs.chosenDlc(a, title.titleId, l);
    }

    /** Closes the open sheet when it is this title's (an imported pack made the game ready: Build is no longer its next step) */
    static void closeIfOpen(String titleId) {
        GameSheet s = current;
        if (s == null || s.dialog == null || !s.title.titleId.equalsIgnoreCase(titleId)) return;
        try { s.dialog.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
    }

    /** The title of the open sheet, null when none is open (dev.peanuts.ctl's status file) */
    static String openTitle() { GameSheet s = current; return s == null ? null : s.title.titleId; }

    /** The update a build without a sheet uses (as {@link #resumeState} picks it): the one chosen earlier, else the newest found; null when the title has none */
    static LibraryScanner.Source storedUpdate(Activity a, LibraryScanner.Title t) {
        String picked = PeanutsPrefs.chosenUpdate(a, t.titleId);
        for (LibraryScanner.Source u : t.updates) if (picked != null && u.file.getPath().equals(picked)) return u;
        return t.updates.isEmpty() ? null : t.updates.get(0);
    }

    /**
     * Whether Build would ask "Build anyway?" for this title with no sheet open: the version of {@link #storedUpdate} (else the base's) is not one of the
     * profile's builds. The same rule as {@link #chosenVersionState}, from the stored choices.
     */
    static boolean wouldAsk(Activity a, LibraryScanner.Title t) {
        if (t.supportedBuilds.isEmpty()) return false;
        LibraryScanner.Source chosen = storedUpdate(a, t);
        long have = chosen != null ? chosen.version : (t.base != null ? t.base.version : 0);
        return !t.supportedBuilds.containsKey(have);
    }

    private static String versionText(long v) { return v == 0 ? "v0" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }
}
