package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;
import dev.peanuts.pack.FirstImport;
import dev.peanuts.pack.PackImport;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * The game sheet's Import pack… (GitHub issue #44): a pack made on a PC or exported from another phone, for a game that is not built yet. Upstream's Import
 * pack… is on a built game's Storage tab (PackUi); before the first build there was nowhere to tap. The work and its checks are dev.peanuts.pack.PackImport's,
 * the words dev.peanuts.pack.FirstImport's.
 * <p>
 * The sheet's button asks what to import (a .zip made by Export pack…, or a pack folder, as upstream's own question does), the system picker gives the path
 * (MainActivity.onActivityResult, LibrarySettings.result, {@link #result}). The sheet's checks run again then, against the library as it is after the picker
 * ({@link #picked}), and once more right before the import with the title taken ({@link #start}); the import runs on a thread of its own behind a dialog
 * that cannot be closed. Then "Pack imported" or "Pack not imported" says what happened, with the folders under Details; the library is scanned again (a
 * game with a dump folder or .nup is ready to play at once; one Build still imports from an NSP or XCI keeps its sheet open, for Build). The control
 * interface's import_pack runs the same without the picker ({@link CtlPort#importPack}).
 */
final class PackImportUi {
    static final int REQ_ZIP = 72, REQ_DIR = 73;   // after LibrarySettings' 60 to 70 and BuildLogUi's 71

    /** What the picker was opened for (the activity's result comes back to a static; it holds no view or activity). Its game is made again from the library
     *  when the answer comes ({@link #picked}). Kept in PeanutsPrefs too: when Android ends the process while the picker is open, the answer comes to a new
     *  process, where this is null */
    private static FirstImport.Pick pending;
    /** A picked pack whose answer came before the library's first scan of this process (Android made the process anew while the picker was open): it goes on
     *  when that scan ends ({@link #scanned}) */
    private static FirstImport.Pick waitingPick;
    private static File waitingSource;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private PackImportUi() {}

    /** Whether the sheet offers Import pack… for this title: a game with a profile, not built, with its base file there */
    static boolean offered(LibraryScanner.Title t) {
        return t != null && t.status == LibraryScanner.Status.BUILD && t.base != null && t.base.file.exists();
    }

    /** Why Import pack… cannot run for this title now: {code, sentence} (the control interface's codes), null when it can */
    static String[] refusal(Activity a, Library lib, LibraryScanner.Title t, String titleId) {
        if (lib == null) return new String[] {"no_home", "Home was not drawn yet."};
        if (t == null) return new String[] {"unknown_title", titleId + " is not in the library (scan first)."};
        if (t.status == LibraryScanner.Status.NO_PROFILE) return new String[] {"not_in_app", String.valueOf(Upstream.profileMissing(t.titleId, t.name))};
        if (t.status == LibraryScanner.Status.READY) return new String[] {"built", t.name + " is built: import a pack on its page, on the Storage tab."};
        if (t.base == null) return new String[] {"no_base", "No base game of " + t.name + " was found. Only an update or DLC is in the folder."};
        if (!t.base.file.exists()) return new String[] {"file_gone", t.base.file.getName() + " is no longer where the app found it. Rescan, then import the pack."};
        if (lib.building(t.titleId)) return new String[] {"busy", "Building now: the progress is on the game's card."};
        if (PackImport.busy(t.titleId)) return new String[] {"busy", "An import of " + t.name + "'s pack is still working."};
        if (!Environment.isExternalStorageManager()) return new String[] {"no_access", "The app needs All files access to read the pack. Allow it, then import the pack."};
        return null;
    }

    /**
     * The game as its sheet would build it: the file the pack is checked against (a dump folder or .nup, none for an NSP or XCI), the version Build uses (the
     * update chosen, else the base's), and where the pack goes: where Build puts it (the game's Build destination, else AppStorage.pack: the app's packs/&lt;id&gt;,
     * or the build folder's &lt;id&gt;/pack when one is set and the app has no pack of the game yet). Reads no file but the pack folder's swap state
     */
    static FirstImport.Game game(Activity a, LibraryScanner.Title t, LibraryScanner.Source chosenUpdate) {
        File file = t.base.isDump() || t.base.isNup() ? t.base.file : null;
        long version = chosenUpdate != null && chosenUpdate.needsImport() ? chosenUpdate.version : t.base.version;   // GameSheet.state: only an update to import goes in
        Set<Long> updates = new HashSet<>();
        for (LibraryScanner.Source u : t.updates) updates.add(u.version);
        String chosen = PeanutsPrefs.chosenBuildDir(a, t.titleId);
        File dest = chosen == null ? null : PeanutsPrefs.buildGameDir(chosen, t.titleId);
        File target = dest != null ? new File(dest, "pack") : dev.nxnative.android.AppStorage.pack(a, t.titleId);
        File buildDir = dest != null ? dest : PeanutsPrefs.buildGameDir(a, t.titleId);   // where Build writes an NSP's .nup (BuildService.stepImport), and so its job
        return new FirstImport.Game(t.titleId, t.name, file, version, updates, t.supportedBuilds, target, FirstImport.buildFile(file, buildDir, t.home, t.titleId));
    }

    /** The sheet's Import pack…: when a pack is there already, whether to replace it; what to import; then the picker */
    static void offer(Activity a, Library lib, LibraryScanner.Title t, LibraryScanner.Source chosenUpdate) {
        String[] no = refusal(a, lib, t, t == null ? "" : t.titleId);
        if (no != null) {
            Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT);
            if (no[0].equals("no_access")) LibrarySettings.askAccess(a, "The app needs All files access to read the pack and write it where Build looks for it.");
            else Toast.makeText(a, no[1], Toast.LENGTH_LONG).show();
            return;
        }
        FirstImport.Game g = game(a, t, chosenUpdate);
        if (new File(g.target, "pack.tsv").isFile()) PUi.confirmDanger(a, "Replace the pack?", FirstImport.replaces(g), "Pick the new pack", () -> choose(a, g, g.target));
        else choose(a, g, null);
    }

    /** What to import: a .zip made by Export pack…, or a pack folder (upstream's own question), then the system picker. replaces: the pack folder whose pack
     *  the player agreed to replace, null when none was there */
    private static void choose(Activity a, FirstImport.Game g, File replaces) {
        new AlertDialog.Builder(a).setTitle("Import " + g.name + "'s pack from…")
                .setItems(new String[] {"A .zip file (made by Export pack…)", "A pack folder (with pack.tsv and shaders/)"}, (d, w) -> {
                    pending = new FirstImport.Pick(g.titleId, replaces);
                    PeanutsPrefs.packImportPick(a, pending.text());
                    if (w == 1) LibrarySettings.pickFolder(a, REQ_DIR, "Pick the pack folder (the one with pack.tsv and shaders/)");
                    else LibrarySettings.pickFile(a, REQ_ZIP, "Pick the pack's .zip file", "application/zip");
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** LibrarySettings.result: the picker's answer; true when the request was one of these (handled, whatever the answer) */
    static boolean result(Activity a, int request, int result, Intent data) {
        if (request != REQ_ZIP && request != REQ_DIR) return false;
        FirstImport.Pick p = pending != null ? pending : FirstImport.Pick.of(PeanutsPrefs.packImportPick(a));   // null here: a process made anew meanwhile
        pending = null;
        PeanutsPrefs.packImportPick(a, null);
        if (p == null || result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        String path = request == REQ_ZIP ? Upstream.documentToPath(data.getData()) : Upstream.treeToPath(data.getData());
        if (path == null) {
            refused(a, "That " + (request == REQ_ZIP ? "file" : "folder") + " is not on the phone's storage by a path the app can read. Pick it from the phone's storage (the menu, then the phone's name), not from Recent, Downloads or a cloud provider.", String.valueOf(data.getData()));
            return true;
        }
        if (!Library.scannedOnce() && PeanutsPrefs.gameDir(a) != null) {   // a process made anew: the library knows the game once its first scan ended
            waitingPick = p;
            waitingSource = new File(path);
            return true;
        }
        picked(a, p, new File(path));
        return true;
    }

    /** Library: a scan ended (or failed). A pick that waited for the library's first scan of the process goes on, on the library's activity */
    static void scanned(Activity a) {
        FirstImport.Pick p = waitingPick;
        File source = waitingSource;
        waitingPick = null;
        waitingSource = null;
        if (p != null) picked(a, p, source);
    }

    /**
     * The picker's answer for p: the game as the library has it now and the sheet's checks again (a build may have started while the picker was open, or the
     * game become ready), then the import. A pack in the pack folder that the player was not asked about (none was there before the picker) is asked about now
     */
    private static void picked(Activity started, FirstImport.Pick p, File source) {
        Activity a = front(started);
        Library lib = Library.now();
        LibraryScanner.Title t = lib == null ? null : lib.title(p.titleId);
        String[] no = refusal(a, lib, t, p.titleId);
        if (no != null) {
            if (no[0].equals("no_access")) LibrarySettings.askAccess(a, "The app needs All files access to read the pack and write it where Build looks for it.");
            else refused(a, FirstImport.refusal(no[1]), "Picked: " + source);
            return;
        }
        FirstImport.Game g = game(a, t, GameSheet.storedUpdate(a, t));   // the sheet's Update row is the stored choice (GameSheet.picked keeps it there)
        if (!p.asked(g.target) && new File(g.target, "pack.tsv").isFile())
            PUi.confirmDanger(a, "Replace the pack?", FirstImport.replaces(g), "Replace it", () -> start(a, g, source, null));
        else start(a, g, source, null);
    }

    /** The end of an import for the control interface: {null, text} when the pack is in, else {code, sentence} (refused, or one of {@link #refusal}'s codes) */
    interface Answer { void answer(String[] result); }

    /**
     * Runs the import of source for g behind a wait dialog, then says how it went; answer (may be null) gets the outcome too. The end is shown on the activity in
     * front then (Library.now: the one a was, or the one that took its place when Android made it again meanwhile).
     * <p>
     * First, on the main thread, the sheet's checks once more, then the title taken (PackImport.take) with nothing in between that lets the main thread do
     * other work: Library.startBuild runs on it too, so no Build of the title starts between this check and the import, and none while it runs (it asks
     * PackImport.busy)
     */
    static void start(Activity a, FirstImport.Game g, File source, Answer answer) {
        Library lib = Library.now();
        String[] no = refusal(a, lib, lib == null ? null : lib.title(g.titleId), g.titleId);
        if (no != null) {
            notStarted(a, g, no, answer);
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        PUi.add(box, PUi.body(a, "Importing " + g.name + "'s pack…"), 0);
        PUi.add(box, PUi.small(a, "It is checked before it replaces anything."), 6);
        ProgressBar bar = PUi.progress(a);
        bar.setIndeterminate(true);
        PUi.add(box, bar, 14, PUi.fill());
        AlertDialog wait = PUi.sheet(a, "Import pack", box).setCancelable(false).create();
        wait.show();
        PackImport.Titles titles = titles();
        if (!PackImport.take(g.titleId)) {   // refusal asked PackImport.busy on this thread just now, and only this thread takes: kept so two imports never run
            try { wait.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
            notStarted(a, g, new String[] {"busy", FirstImport.busy(g)}, answer);
            return;
        }
        new Thread(() -> {   // PackImport.run gives the title back at its end
            PackImport.Done done = null;
            PackImport.Refused refused = null;
            try { done = PackImport.run(a.getApplicationContext(), g, source, titles); } catch (PackImport.Refused e) { refused = e; }
            PackImport.Done fd = done;
            PackImport.Refused fr = refused;
            UI.post(() -> {
                try { wait.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
                Activity now = front(a);
                if (fr != null) {
                    refused(now, fr.getMessage(), fr.detail);
                    if (answer != null) answer.answer(new String[] {"refused", fr.getMessage()});
                    return;
                }
                if (fd.listed) GameSheet.closeIfOpen(g.titleId);   // the game is ready now: the sheet's Build is not its next step
                Library.rescan(now);
                if (!now.isFinishing() && !now.isDestroyed()) {
                    Haptics.play(now.getWindow().getDecorView(), Haptics.Word.DONE);
                    PUi.explain(now, "Pack imported", fd.text, fd.detail).setPositiveButton("OK", null).show();
                }
                if (answer != null) answer.answer(new String[] {null, fd.text});
            });
        }, "peanuts-pack-import").start();
    }

    /** The import did not start: why, in the "Pack not imported" dialog and to answer */
    private static void notStarted(Activity a, FirstImport.Game g, String[] no, Answer answer) {
        refused(front(a), FirstImport.refusal(no[1]), "Pack folder: " + g.target);
        if (answer != null) answer.answer(no);
    }

    /** The library's activity when it is a live one (Android may have made the activity again while the import ran), else started */
    private static Activity front(Activity started) {
        Library l = Library.now();
        Activity x = l == null ? null : l.activity();
        return x != null && !x.isFinishing() && !x.isDestroyed() ? x : started;
    }

    private static void refused(Activity a, String why, String detail) {
        if (a.isFinishing() || a.isDestroyed()) return;
        Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT);
        PUi.explain(a, "Pack not imported", why, detail).setPositiveButton("OK", null).show();
    }

    /** The other titles a pack may name, as the library and the APK's profiles know them now (read on the main thread, asked on the import's) */
    private static PackImport.Titles titles() {
        java.util.Map<String, String> names = new java.util.HashMap<>(Upstream.profiles());
        java.util.Map<String, FirstImport.Other> where = new java.util.HashMap<>();
        for (LibraryScanner.Title t : Library.titlesNow()) {
            if (t.status == LibraryScanner.Status.NO_PROFILE) continue;
            where.put(t.titleId, t.status == LibraryScanner.Status.READY ? FirstImport.Other.BUILT : FirstImport.Other.TO_BUILD);
            if (t.name != null && !t.name.isEmpty()) names.put(t.titleId, t.name);
        }
        return new PackImport.Titles() {
            @Override public String name(String id) { return names.get(id); }
            @Override public FirstImport.Other where(String id) { return where.getOrDefault(id, FirstImport.Other.NOT_LISTED); }
        };
    }
}
