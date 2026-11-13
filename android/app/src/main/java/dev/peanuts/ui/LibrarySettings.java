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
 * their results come back through MainActivity.onActivityResult to {@link #result} (requests 60–66, also the game sheet's Choose/Add). The same pickers serve
 * Home's first-run card and the sheet's Add keys….
 */
public final class LibrarySettings {
    static final int REQ_KEYS = 60, REQ_GAME = 61, REQ_UPDATE_DIR = 62, REQ_DLC_DIR = 63, REQ_FIRMWARE = 64, REQ_SHEET_UPDATE = 65, REQ_SHEET_DLC = 66;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String fontsState;   // "Extracting the fonts…" and what came of it, while the rows show
    private static PUi.Setting gameRow, updateRow, dlcRow, keysRow, fontsRow, companionsRow;
    private static Activity rowsOf;

    private LibrarySettings() {}

    /** The section and its card, added to the Settings column before upstream's own sections */
    public static void add(Activity a, LinearLayout content) {
        rowsOf = a;
        PUi.add(content, PUi.section(a, "Library"), 16);
        LinearLayout card = PUi.add(content, PUi.listCard(a), 8);
        gameRow = new PUi.Setting(a, "📁", "Game folder", v -> pickFolder(a, REQ_GAME, "Pick your game folder")).chevron(a);
        card.addView(gameRow.view);
        updateRow = new PUi.Setting(a, "⬆️", "Update folder", v -> pickFolder(a, REQ_UPDATE_DIR, "Pick your update folder")).chevron(a);
        card.addView(updateRow.view);
        updateRow.view.setOnLongClickListener(v -> { PeanutsPrefs.updateDir(a, null); refreshRows(); Library.rescan(a); return true; });
        dlcRow = new PUi.Setting(a, "➕", "DLC folder", v -> pickFolder(a, REQ_DLC_DIR, "Pick your DLC folder")).chevron(a);
        card.addView(dlcRow.view);
        dlcRow.view.setOnLongClickListener(v -> { PeanutsPrefs.dlcDir(a, null); refreshRows(); Library.rescan(a); return true; });
        keysRow = new PUi.Setting(a, "🔑", "Keys", v -> pickKeys(a)).chevron(a);
        card.addView(keysRow.view);
        fontsRow = new PUi.Setting(a, "🔤", "Firmware fonts", v -> pickFolder(a, REQ_FIRMWARE, "Pick your firmware folder")).chevron(a);
        card.addView(fontsRow.view);
        card.addView(new PUi.Setting(a, "🔄", "Rescan", v -> { Library.rescan(a); Toast.makeText(a, "Looking in the folders again", Toast.LENGTH_SHORT).show(); })
                .value("Reads the folders again").chevron(a).view);
        companionsRow = new PUi.Setting(a, "🖥️", "Companions", v -> a.startActivity(new Intent().setClassName(a.getPackageName(), "dev.peanuts.CatalogActivity")))
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
        updateRow.value(u != null ? u + " · hold to forget" : "Optional. Another folder with update NSPs, matched to their games by title ID.");
        dlcRow.value(d != null ? d + " · hold to forget" : "Optional. Another folder with DLC NSPs, matched to their games by title ID.");
        keysRow.value(keysValue(a));
        fontsRow.value(fontsValue(a, "Optional. Lets companions draw the system font."));
        if (companionsRow != null) companionsRow.value(companionsValue(a));
    }

    static String keysValue(Activity a) {
        if (!PeanutsPrefs.hasKeys(a)) return "Optional. Needed for NSP and XCI files; a .nup or a dump folder plays without them.";
        return "prod.keys is in the app (" + PeanutsPrefs.keysFile(a).length() + " bytes)" + (ImporterPort.get().available() ? "" : " · the importer is not in this build");
    }

    static String fontsValue(Activity a, String none) {
        String fw = PeanutsPrefs.firmwareDir(a);
        if (fontsState != null) return fontsState;
        if (PeanutsPrefs.hasFonts(a)) return "Fonts extracted" + (fw != null ? " from " + fw : "");
        return fw != null ? fw + " · no fonts extracted yet" : none;
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
    public static void pickKeys(Activity a) { pickFile(a, REQ_KEYS, "Pick your prod.keys"); }

    static void pickFile(Activity a, int request, String title) {
        Toast.makeText(a, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
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
        if (request < REQ_KEYS || request > REQ_SHEET_DLC) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        if (request == REQ_KEYS) { copyKeys(a, u); return true; }
        if (request == REQ_SHEET_UPDATE || request == REQ_SHEET_DLC) {
            String p = Upstream.documentToPath(u);
            if (p == null || !new File(p).isFile()) { error(a, "Pick the file from the phone's storage (not Recent or a cloud provider), so the app can open it by its path."); return true; }
            if (GameSheet.current != null) GameSheet.current.picked(request, new File(p));
            return true;
        }
        String p = Upstream.treeToPath(u);
        if (p == null) { error(a, "That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card."); return true; }
        switch (request) {
            case REQ_FIRMWARE: PeanutsPrefs.firmwareDir(a, p); extractFonts(a, p); break;
            case REQ_GAME: PeanutsPrefs.gameDir(a, p); break;
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
                    else Toast.makeText(a, "Keys accepted", Toast.LENGTH_SHORT).show();
                    keysLanded(a);
                });
            }, "peanuts-keys").start();
        } else keysLanded(a);
    }

    private static void keysLanded(Activity a) {
        refreshRows();
        if (GameSheet.current != null) GameSheet.current.keysChanged();
        Library.changed(a);
    }

    /** The firmware's shared fonts → files/firmware/fonts (the importer; a note when it is not in the build) */
    private static void extractFonts(Activity a, String dir) {
        ImporterPort imp = ImporterPort.get();
        if (!imp.available()) { fontsState = "The importer is not in this build: the fonts are extracted when it is."; refreshRows(); return; }
        fontsState = "Extracting the fonts…";
        refreshRows();
        File out = PeanutsPrefs.fontsDir(a);
        out.mkdirs();
        new Thread(() -> {
            int r = imp.firmwareFonts(dir, out.getPath());
            String err = imp.lastError();
            UI.post(() -> { fontsState = r == 0 ? null : "Fonts not extracted: " + err; refreshRows(); Library.changed(a); });
        }, "peanuts-fonts").start();
    }

    private static void error(Activity a, String msg) { if (a.isFinishing() || a.isDestroyed()) return; new AlertDialog.Builder(a).setTitle("Library").setMessage(msg).setPositiveButton("OK", null).show(); }
}
