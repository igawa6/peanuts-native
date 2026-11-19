package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Settings › Library (SPEC.md), the first section of Banana's Settings: the game, update and DLC folders, the keys (prod.keys, copied into files/keys and checked by
 * the importer), the firmware fonts (the importer extracts them into files/firmware/fonts), Rescan, and the Companions catalog. The pickers are the system's;
 * their results come back through MainActivity.onActivityResult to {@link #result} (requests 60–68, also the game sheet's Choose/Add). The same pickers serve
 * Home's first-run card and the sheet's Add keys….
 */
public final class LibrarySettings {
    static final String KEYS_FIRMWARE_GUIDANCE = "Firmware and keys should be the same version.";
    static final int REQ_KEYS = 60, REQ_GAME = 61, REQ_UPDATE_DIR = 62, REQ_DLC_DIR = 63, REQ_FIRMWARE = 64, REQ_SHEET_UPDATE = 65, REQ_SHEET_DLC = 66, REQ_FIRMWARE_ZIP = 67,
            REQ_BUILD_DIR = 68;
    /** dev.peanuts.settings.ModsSection's "Install mod…" (ACTION_OPEN_DOCUMENT_TREE / ACTION_OPEN_DOCUMENT), public: the section lives in another
     *  package and only needs these two request codes, not any other picker here */
    public static final int REQ_MOD_FOLDER = 69, REQ_MOD_ZIP = 70;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String fontsState;   // "Reading the firmware…" and what came of it, while the rows show
    private static PUi.Setting gameRow, updateRow, dlcRow, keysRow, fontsRow, buildRow, companionsRow, hapticsRow, motionRow;
    private static Activity rowsOf;

    private LibrarySettings() {}

    /** The section and its card, added to the Settings column before upstream's own sections */
    public static void add(Activity a, LinearLayout content) {
        rowsOf = a;
        PUi.add(content, PUi.section(a, "Library"), 16);
        LinearLayout card = PUi.add(content, PUi.listCard(a), 8);
        // a row that opens a picker or another screen ticks; Rescan, which acts at once, clicks (Haptics)
        gameRow = new PUi.Setting(a, "📁", "Game folder", Haptics.on(Haptics.Word.TICK, v -> pickFolder(a, REQ_GAME, "Pick your game folder"))).chevron(a);
        card.addView(gameRow.view);
        buildRow = new PUi.Setting(a, "🗂️", "Build folder", Haptics.on(Haptics.Word.TICK, v -> pickFolder(a, REQ_BUILD_DIR, "Pick your build folder"))).chevron(a);
        card.addView(buildRow.view);
        buildRow.view.setOnLongClickListener(v -> { PeanutsPrefs.buildDir(a, null); refreshRows(); Library.rescan(a); return true; });
        updateRow = new PUi.Setting(a, "⬆️", "Update folder", Haptics.on(Haptics.Word.TICK, v -> pickFolder(a, REQ_UPDATE_DIR, "Pick your update folder"))).chevron(a);
        card.addView(updateRow.view);
        updateRow.view.setOnLongClickListener(v -> { PeanutsPrefs.updateDir(a, null); refreshRows(); Library.rescan(a); return true; });
        dlcRow = new PUi.Setting(a, "➕", "DLC folder", Haptics.on(Haptics.Word.TICK, v -> pickFolder(a, REQ_DLC_DIR, "Pick your DLC folder"))).chevron(a);
        card.addView(dlcRow.view);
        dlcRow.view.setOnLongClickListener(v -> { PeanutsPrefs.dlcDir(a, null); refreshRows(); Library.rescan(a); return true; });
        keysRow = new PUi.Setting(a, "🔑", "Keys", Haptics.on(Haptics.Word.TICK, v -> pickKeys(a))).chevron(a);
        card.addView(keysRow.view);
        fontsRow = new PUi.Setting(a, "🔤", "Firmware", Haptics.on(Haptics.Word.TICK, v -> pickFirmwareSource(a))).chevron(a);
        card.addView(fontsRow.view);
        PUi.add(card, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 12);
        card.addView(new PUi.Setting(a, "🔄", "Rescan", Haptics.on(Haptics.Word.CLICK, v -> { Library.rescan(a); Toast.makeText(a, "Looking in the folders again", Toast.LENGTH_SHORT).show(); }))
                .value("Reads the folders again").chevron(a).view);
        card.addView(new PUi.Setting(a, "🧹", "Free up space", Haptics.on(Haptics.Word.TICK, v -> freeUpSpace(a)))
                .value("Delete original dumps of games already built").chevron(a).view);
        // Haptics and Motion: each row is its own switch between two values, System (the default: what Android's settings say) and the quieter one
        hapticsRow = new PUi.Setting(a, "📳", "Haptics", v -> {
            boolean off = !PeanutsPrefs.hapticsOff(a);
            PeanutsPrefs.hapticsOff(a, off);
            Haptics.refresh(a);
            Haptics.play(v, Haptics.Word.CLICK);   // felt when it turns back on, nothing when it turns off
            refreshRows();
        });
        card.addView(hapticsRow.view);
        motionRow = new PUi.Setting(a, "🎞️", "Motion", v -> {
            PeanutsPrefs.motionReduced(a, !PeanutsPrefs.motionReduced(a));
            Motion.refresh(a);
            Haptics.play(v, Haptics.Word.CLICK);
            refreshRows();
        });
        card.addView(motionRow.view);
        companionsRow = new PUi.Setting(a, "🖥️", "Companions", Haptics.on(Haptics.Word.TICK, v -> a.startActivity(new Intent().setClassName(a.getPackageName(), "dev.peanuts.CatalogActivity"))))
                .chevron(a);
        card.addView(companionsRow.view);
        // Developer-only, no row of its own (PeanutsPrefs.developerReadout): a long press flips whether a title with no companion package shows the raw
        // chains/scan readout on the second display instead of leaving it free for Android's own launcher. The row's own value says so when it's on, so
        // it can't be left on by accident when the device is handed back.
        companionsRow.view.setOnLongClickListener(v -> {
            boolean on = !PeanutsPrefs.developerReadout(a);
            PeanutsPrefs.developerReadout(a, on);
            Toast.makeText(a, "Developer second-screen readout: " + (on ? "on" : "off"), Toast.LENGTH_SHORT).show();
            refreshRows();
            return true;
        });
        refreshRows();
        dev.peanuts.online.OnlineSettings.add(a, content);   // Settings › Online: Nextendo Network and its account
    }

    private static String companionsValue(Activity a) {
        String base = "The catalog: install, update or remove a game's companion";
        return PeanutsPrefs.developerReadout(a) ? base + " · developer readout on (long press to turn off)" : base;
    }

    private static void refreshRows() {
        Activity a = rowsOf;
        if (a == null || gameRow == null) return;
        String g = PeanutsPrefs.gameDir(a), u = PeanutsPrefs.updateDir(a), d = PeanutsPrefs.dlcDir(a);
        gameRow.value(g != null ? g : "Not set. Every NSP, XCI, .nup and dump folder inside is shown on Home.");
        String bd = PeanutsPrefs.buildDir(a);
        buildRow.value(bd != null ? bd + " · hold to forget"
                : "Beside the game (default). Pick a folder, on the phone or the microSD card, to keep every build there, one folder per game.");
        updateRow.value(u != null ? u + " · hold to forget" : "Optional. Another folder with update NSPs, matched to their games by title ID.");
        dlcRow.value(d != null ? d + " · hold to forget" : "Optional. Another folder with DLC NSPs, matched to their games by title ID.");
        keysRow.value(keysValue(a));
        fontsRow.value(fontsValue(a, "Used by games and companions. Missing characters in Android's fonts can prevent a game from starting."));
        if (companionsRow != null) companionsRow.value(companionsValue(a));
        if (hapticsRow != null) hapticsRow.value(PeanutsPrefs.hapticsOff(a) ? "Off. Tap to follow the system's touch feedback setting again."
                : "System: follows Android's touch feedback setting. Tap to turn the app's own haptics off.");
        if (motionRow != null) motionRow.value(PeanutsPrefs.motionReduced(a) ? "Reduced: fades and progress only. Tap to follow the system's animation setting again."
                : "System: follows Android's animation setting. Tap for reduced motion (fades and progress only).");
    }

    static String keysValue(Activity a) {
        if (!PeanutsPrefs.hasKeys(a)) return "Needed to build a game (Build asks for them). Tap to pick the prod.keys from your own console.";
        return "prod.keys is in the app (" + PeanutsPrefs.keysFile(a).length() + " bytes)" + (ImporterPort.get().available() ? "" : " · the importer is not in this build");
    }

    private static String firmwareTried;   // the firmware path a read was last started for by firmwareAfterAccess: once a path, not at every drawing of the row

    /** A firmware folder or zip that is set but not read (picked before All files access was given, or the read failed for want of it): read now that the
     * access is there. Called where the Firmware row gets its text, so coming back from Android's access page is enough */
    private static void firmwareAfterAccess(Activity a) {
        String fw = PeanutsPrefs.firmwareDir(a);
        if (fw == null || !fw.startsWith("/") || fw.equals(firmwareTried) || PeanutsPrefs.hasFonts(a) || !Environment.isExternalStorageManager()) return;
        if ("Reading the firmware…".equals(fontsState) || !new File(fw).exists()) return;
        firmwareTried = fw;
        extractFonts(a, fw, null);
    }

    /** The launcher is in front again (MainActivity.onResume): coming back from Android's All files access page reads a firmware that waited for it */
    public static void resume(Activity a) {
        if (fontsState != null && fontsState.startsWith("Waiting for file access") && Environment.isExternalStorageManager()) fontsState = null;
        firmwareAfterAccess(a);
        refreshRows();
    }

    static String fontsValue(Activity a, String none) {
        firmwareAfterAccess(a);
        String fw = PeanutsPrefs.firmwareDir(a);
        if (fontsState != null) return fontsState;
        if (PeanutsPrefs.hasFonts(a)) return "Firmware added" + (fw != null ? " from " + fw : "");
        return fw != null ? fw + " · not added yet" : none;
    }

    /** Home's first-run card: the two optional folders in a small sheet */
    static void extraFolders(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        PUi.add(box, PUi.body(a, "Updates and DLC in other folders are matched to their games by title ID."), 0);
        String u = PeanutsPrefs.updateDir(a), dl = PeanutsPrefs.dlcDir(a);
        PUi.add(box, new PUi.Setting(a, "⬆️", "Update folder", v -> { d[0].dismiss(); pickFolder(a, REQ_UPDATE_DIR, "Pick your update folder"); }).value(u != null ? u : "Optional").chevron(a).view, 12);
        PUi.add(box, new PUi.Setting(a, "➕", "DLC folder", v -> { d[0].dismiss(); pickFolder(a, REQ_DLC_DIR, "Pick your DLC folder"); }).value(dl != null ? dl : "Optional").chevron(a).view, 0);
        d[0] = PUi.sheet(a, "Update and DLC folders", box).setPositiveButton("Done", null).show();
    }

    // ---- Pickers ----

    /** The keys picker (Settings › Library › Keys, the first-run card, the sheet's Add keys…) */
    public static void pickKeys(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        PUi.add(box, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 0);
        PUi.sheet(a, "Firmware and keys", box)
                .setPositiveButton("Choose keys", (d, w) -> pickFile(a, REQ_KEYS, "Pick your prod.keys"))
                .setNegativeButton("Cancel", null).show();
    }

    static void pickFile(Activity a, int request, String title) { pickFile(a, request, title, "*/*"); }

    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip", "application/octet-stream"};

    static void pickFile(Activity a, int request, String title, String mime) {
        Toast.makeText(a, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE);
        // A zip is not application/zip on every phone: a file manager or a download can label it x-zip-compressed, x-zip or plain octet-stream, and the
        // picker then greys it out so it cannot be chosen at all (GitHub issue #42 may be this). The importer says what a wrong file is
        if ("application/zip".equals(mime)) i.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, ZIP_TYPES);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    /** Settings › Library › Firmware fonts: a folder of extracted NCAs (the old way), or a firmware .zip with the same NCAs flat inside it (stored
     * or deflated — users usually have firmware as a zip, not pre-extracted) */
    static void pickFirmwareSource(Activity a) {   // also the game sheet's and the first-run card's firmware buttons
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        PUi.add(box, PUi.body(a, "Pick the folder your firmware is extracted into, or a firmware .zip (its NCA files flat inside, stored or deflated)."), 0);
        PUi.add(box, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 12);
        PUi.add(box, new PUi.Setting(a, "📁", "Folder", v -> { d[0].dismiss(); pickFolder(a, REQ_FIRMWARE, "Pick your firmware folder"); }).chevron(a).view, 12);
        PUi.add(box, new PUi.Setting(a, "🗜️", "Zip file", v -> { d[0].dismiss(); pickFile(a, REQ_FIRMWARE_ZIP, "Pick your firmware zip", "application/zip"); }).chevron(a).view, 0);
        d[0] = PUi.sheet(a, "Firmware", box).setPositiveButton("Cancel", null).show();
    }

    /** As upstream's MainActivity.pickFolder: the system folder picker opened at the shared storage's root */
    static void pickFolder(Activity a, int request, String title) {
        Toast.makeText(a, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    /** MainActivity.onActivityResult's first line: true when the request was one of this package's (handled, whatever the result) */
    public static boolean result(Activity a, int request, int result, Intent data) {
        if (request < REQ_KEYS || request > REQ_MOD_ZIP) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        if (request == REQ_KEYS) { copyKeys(a, u); return true; }
        if (request == REQ_FIRMWARE_ZIP) { pickedFirmwareZip(a, u); return true; }
        if (request == REQ_MOD_FOLDER) { dev.peanuts.settings.ModsSection.pickedFolder(a, u); return true; }
        if (request == REQ_MOD_ZIP) { dev.peanuts.settings.ModsSection.pickedZip(a, u, displayName(a, u)); return true; }
        if (request == REQ_SHEET_UPDATE || request == REQ_SHEET_DLC) {
            String p = Upstream.documentToPath(u);
            if (p == null || !new File(p).isFile()) { error(a, "Pick the file from the phone's storage (not Recent or a cloud provider), so the app can open it by its path."); return true; }
            if (GameSheet.current != null) GameSheet.current.picked(request, new File(p));
            return true;
        }
        String p = Upstream.treeToPath(u);
        if (p == null) { error(a, "That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card."); return true; }
        switch (request) {
            case REQ_FIRMWARE:
                PeanutsPrefs.firmwareDir(a, p);
                // The importer opens the folder by its path, which needs All files access. Without it the read failed here, the dialog below asked for the
                // access, and nothing read the firmware again once it was given: the row stayed at "not added" (GitHub issue #42). The read now waits for it
                if (Environment.isExternalStorageManager()) extractFonts(a, p, null);
                else { firmwareTried = null; fontsState = "Waiting for file access: allow All files access for the app, and the firmware is read."; }
                break;
            case REQ_GAME: PeanutsPrefs.gameDir(a, p); break;
            case REQ_BUILD_DIR: PeanutsPrefs.buildDir(a, p); break;
            case REQ_UPDATE_DIR: PeanutsPrefs.updateDir(a, p); break;
            case REQ_DLC_DIR: PeanutsPrefs.dlcDir(a, p); break;
            default: return true;
        }
        if (!Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(a).setTitle("Allow file access").setMessage("The app needs All files access to read " + p + ". Allow it, then come back.")
                    .setPositiveButton("Open settings", (d, w) -> openAccessSettings(a)).setNegativeButton("Later", null).show();
        }
        refreshRows();
        Library.changed(a);
        if (request != REQ_FIRMWARE) Library.rescan(a);
        return true;
    }

    /** As upstream's MainActivity.openAccessSettings: the app's own All files access page, else the list */
    private static void openAccessSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** The picked document → files/keys/prod.keys (through the content resolver: no path needed), then the importer's check when it is in the build */
    private static void copyKeys(Activity a, Uri u) {
        File out = PeanutsPrefs.keysFile(a);
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = a.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("the picker gave no file");
            byte[] buf = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 1 << 20) throw new IOException("that file is too large for a prod.keys"); }
        } catch (IOException e) { tmp.delete(); error(a, "Could not copy the keys: " + e.getMessage()); return; }
        if (!tmp.renameTo(out)) { tmp.delete(); error(a, "Could not place the keys in " + out); return; }
        ImporterPort imp = ImporterPort.get();
        if (imp.available()) {
            new Thread(() -> {
                int r = imp.keys(out.getPath());
                String err = imp.lastError();
                UI.post(() -> {
                    if (r != 0) error(a, "The importer does not accept these keys: " + err + "\nThe file is kept; pick another to replace it.");
                    else { Haptics.play(a.getWindow().getDecorView(), Haptics.Word.CLICK); Toast.makeText(a, "Keys loaded", Toast.LENGTH_LONG).show(); }
                    keysLanded(a);
                });
            }, "peanuts-keys").start();
        } else keysLanded(a);
    }

    private static void keysLanded(Activity a) {
        // A firmware picked before the keys said "add your prod.keys first" and stayed so after the keys came, until it was picked a second time (seen on a
        // fresh install; GitHub issue #42 reads like it): with keys in the app it is read now
        if (PeanutsPrefs.hasKeys(a) && fontsState != null && fontsState.startsWith("Firmware not added: add your prod.keys")) { fontsState = null; firmwareTried = null; }
        firmwareAfterAccess(a);
        refreshRows();
        if (GameSheet.current != null) GameSheet.current.keysChanged();
        Library.changed(a);
    }

    /** The firmware's shared fonts → files/firmware/fonts (the importer; a note when it is not in the build). source is a folder or a firmware .zip
     * path (nximport_firmware_fonts takes either); deleteAfter is a temp copy (pickedFirmwareZip, when the picked zip was not on local storage) to
     * remove once the importer is done with it, whichever way the extraction went, or null when source is a real picked path that stays as it is. */
    private static void extractFonts(Activity a, String source, File deleteAfter) {
        ImporterPort imp = ImporterPort.get();
        if (!imp.available()) {
            fontsState = "The importer is not in this build: the firmware is read when it is.";
            refreshRows();
            if (deleteAfter != null) deleteAfter.delete();
            return;
        }
        fontsState = "Reading the firmware…";
        refreshRows();
        File out = PeanutsPrefs.fontsDir(a);
        out.mkdirs();
        new Thread(() -> {
            // The importer reads the firmware's files with the user's keys: loaded here when this process has not loaded them yet (GitHub issues #18, #22: "keys not
            // loaded" unless prod.keys had been picked in the same session), and a plain line when there are none
            if (!ImporterPort.ensureKeys(a)) {
                if (deleteAfter != null) deleteAfter.delete();
                UI.post(() -> { fontsState = "Firmware not added: add your prod.keys first (Keys, above). The firmware is read then; choose it again if it is not."; fontsLanded(a); });
                return;
            }
            int r = imp.firmwareFonts(source, out.getPath());
            String err = imp.lastError();
            if (deleteAfter != null) deleteAfter.delete();
            UI.post(() -> { fontsState = r == 0 ? null : "Firmware not added: " + err
                    + "\n" + KEYS_FIRMWARE_GUIDANCE; fontsLanded(a); });
        }, "peanuts-fonts").start();
    }

    private static boolean triedMoreFonts;

    /**
     * A firmware added before the app read its Chinese and Korean fonts too (cjk_sc.ttf and the others beside standard.ttf: a game in those languages drew "?"
     * without them): read once more from the folder or zip it came from, when that is still there. Once per app process; nothing is asked, and a firmware that
     * is gone leaves the two fonts as they are (choosing the firmware again adds the rest)
     */
    public static void addMissingFonts(Activity a) {
        if (triedMoreFonts) return;
        triedMoreFonts = true;
        File d = PeanutsPrefs.fontsDir(a);
        String src = PeanutsPrefs.firmwareDir(a);
        if (src == null || !new File(d, "standard.ttf").isFile() || new File(d, "cjk_sc.ttf").isFile() || !src.startsWith("/") || !new File(src).exists()) return;
        extractFonts(a, src, null);
    }

    private static void fontsLanded(Activity a) {
        refreshRows();
        if (GameSheet.current != null) GameSheet.current.keysChanged();
        Library.changed(a);
    }

    /** The firmware .zip picker's result: a path on the phone's own storage needs no copy (the importer, native code, reads the zip straight from
     * disk by that path, same as the folder path); anything else (a cloud provider, an archive app's content provider) is copied into the app's
     * cache first — about 300 MB for a full firmware zip, so this runs off the UI thread — and deleted again once the importer is done with it. */
    private static void pickedFirmwareZip(Activity a, Uri u) {
        String local = Upstream.documentToPath(u);
        if (local != null && new File(local).isFile()) {
            PeanutsPrefs.firmwareDir(a, local);
            extractFonts(a, local, null);
            return;
        }
        fontsState = "Copying the firmware zip…";
        refreshRows();
        new Thread(() -> {
            File tmp = new File(a.getCacheDir(), "firmware_import.zip");
            try (InputStream in = a.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("the picker gave no file");
                byte[] buf = new byte[1 << 20]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 8L << 30) throw new IOException("that file is too large for a firmware zip"); }
            } catch (IOException e) {
                tmp.delete();
                UI.post(() -> { fontsState = "Firmware not added: could not copy the zip (" + e.getMessage() + ")"; refreshRows(); });
                return;
            }
            String name = displayName(a, u);
            UI.post(() -> { PeanutsPrefs.firmwareDir(a, name != null ? name : "a firmware zip"); extractFonts(a, tmp.getPath(), tmp); });
        }, "peanuts-firmware-copy").start();
    }

    private static String displayName(Activity a, Uri u) {
        try (android.database.Cursor c = a.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { /* no name: the generic label is fine */ }
        return null;
    }

    /**
     * Settings › Library › Free up space: the original NSP/XCI of every game already built into a .nup (LibraryScanner.Title.originals), which the game no
     * longer needs to play or to rebuild. Deleted one by one after a confirmation that says what is lost: a later game update needs the original again (its
     * patch reads the base NCA's raw bytes, which the .nup does not keep). Only a READY title whose .nup is there and not empty, and only plain files.
     */
    static void freeUpSpace(Activity a) {
        java.util.List<LibraryScanner.Title> ready = new java.util.ArrayList<>();
        for (LibraryScanner.Title t : Library.titlesNow()) {
            if (t.status != LibraryScanner.Status.READY || t.base == null || !t.base.isNup() || !t.base.file.isFile() || t.base.file.length() == 0) continue;
            for (LibraryScanner.Source s : t.originals) if (s.file.isFile()) { ready.add(t); break; }
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(PUi.dp(a, 20), PUi.dp(a, 4), PUi.dp(a, 20), 0);
        AlertDialog[] d = new AlertDialog[1];
        if (ready.isEmpty()) PUi.add(box, PUi.small(a, "Nothing to free: no built game still has its original NSP or XCI in your folders (or the library is still scanning)."), 0);
        else PUi.add(box, PUi.small(a, "These games are built, so they play and rebuild without their original dump. Keep the original if you will add a later game update: an update can only be applied to the original."), 8);
        for (LibraryScanner.Title t : ready) {
            for (LibraryScanner.Source s : t.originals) {
                if (!s.file.isFile()) continue;
                File f = s.file;
                String size = String.format(java.util.Locale.ROOT, "%.1f GB", f.length() / 1e9);
                box.addView(new PUi.Setting(a, "🗑️", t.name.isEmpty() ? t.titleId : t.name, v -> PUi.confirmDanger(a, "Delete the original dump?",
                        f.getName() + " (" + size + ") is deleted. " + (t.name.isEmpty() ? "The game" : t.name) + " keeps playing from its built copy, with its saves. "
                                + "To add a later game update you will need this file again.", "Delete", () -> new Thread(() -> {
                            boolean ok = f.isFile() && t.base.file.isFile() && t.base.file.length() > 0 && f.delete();   // the built copy checked again just before
                            UI.post(() -> {
                                if (a.isFinishing() || a.isDestroyed()) return;
                                Toast.makeText(a, ok ? "Deleted " + f.getName() + ", " + size + " freed" : "Could not delete " + f.getName(), Toast.LENGTH_LONG).show();
                                if (d[0] != null) d[0].dismiss();
                                Library.rescan(a);
                            });
                        }, "peanuts-free-space").start()))
                        .value("Original " + size + " · " + f.getName()).view);
            }
        }
        d[0] = PUi.sheet(a, "Free up space", box).setPositiveButton("Done", null).show();
    }

    private static void error(Activity a, String msg) { if (a.isFinishing() || a.isDestroyed()) return; Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Library").setMessage(msg).setPositiveButton("OK", null).show(); }
}
