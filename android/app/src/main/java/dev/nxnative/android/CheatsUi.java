package dev.nxnative.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Game settings' cheat rows for a game whose profile has cheat files (Prefs.cheatsGame): the Cheats switch (Experimental, off by default) and Cheat files, a sheet
 * that lists the game's own file (from the pack; not removable) and the imported ones (Remove each), with Import cheat file…, Export cheats… and Import cheats… (one
 * .zip of the imported files and the cheat switches). The files and the checks are Cheats.java's; this class is the pickers and the dialogs. Nothing here reads or
 * writes a save folder: only &lt;files&gt;/cheats/&lt;title ID&gt;/.
 */
final class CheatsUi {
    private CheatsUi() {}

    static final int PICK_FILE = 80, PICK_ZIP = 81, CREATE_ZIP = 82;   // PEANUTS: 60 to 62 are dev.peanuts.ui.LibrarySettings' pickers, whose result handler MainActivity asks first
    private static Game pendingFor;

    /** The game's cheats folder in the app's own storage: the merged file a start writes (DM_CHEATS) and imported/ with the player's files */
    static File folder(Context c, String titleId) { return new File(new File(c.getFilesDir(), "cheats"), titleId); }

    /** The game's main builds (first 16 hex digits): its pack's build rows, else its profile's builds (a game not prepared yet) */
    static Set<String> builds(Game g) {
        if (g.packDir != null) {
            try { Set<String> b = Cheats.builds16(String.join(" ", Pack.read(new File(g.packDir)).builds)); if (!b.isEmpty()) return b; } catch (IOException ignored) { }
        }
        Profile p = Profile.of(g.titleId);
        return Cheats.builds16(p == null ? "" : p.builds);
    }

    /** The rows, into Game settings' Game options */
    static void rows(MainActivity a, Game g, java.util.function.Consumer<Ui.Setting> add, Runnable again) {
        String id = g.titleId;
        Ui.Setting sw = new Ui.Setting(a, "🧪", a.getString(R.string.cheats_title), null).toggle(a, Prefs.cheats(a, id), (b, on) -> Prefs.cheats(a, id, on));
        sw.badge("Experimental").says(a.getString(R.string.cheats_on), a.getString(R.string.cheats_off));
        add.accept(sw);
        add.accept(CheatsBridge.filesRow(a, g, again));   // PEANUTS: the game's cheat sources (.txt, .zip, a folder) in place of imported/: was the row with sheet(a, g, again), "The game's own and N imported" and R.string.cheats_files_note
    }

    /** The Cheat files sheet: one line per file, a tap on an imported one offers Remove; then the three actions */
    static void sheet(MainActivity a, Game g, Runnable again) {
        File folder = folder(a, g.titleId);
        Set<String> builds = builds(g);
        String build = builds.isEmpty() ? null : builds.iterator().next();
        File shipped = g.packDir != null ? Cheats.packFile(new File(g.packDir), build) : null;
        List<File> imported = Cheats.importedFiles(folder);
        List<String> items = new ArrayList<>();
        items.add(shipped != null ? a.getString(R.string.cheats_shipped, shipped.getName()) : a.getString(R.string.cheats_shipped_missing));
        for (File f : imported) items.add(f.getName() + "  ·  " + a.getString(R.string.cheats_remove));
        items.add(a.getString(R.string.cheats_import_file));
        items.add(a.getString(R.string.cheats_export));
        items.add(a.getString(R.string.cheats_import_zip));
        new PadFocus.Builder(a).setTitle(a.getString(R.string.cheats_files_title) + " · " + g.name).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) return;   // the game's own file: nothing to do
            if (w <= imported.size()) {
                File f = imported.get(w - 1);
                new PadFocus.Builder(a).setTitle(a.getString(R.string.cheats_remove) + " " + f.getName() + "?")
                        .setMessage("Its cheats leave the in-game panel from the next start. The game's own file stays.")
                        .setPositiveButton(a.getString(R.string.cheats_remove), (x, y) -> { Cheats.remove(folder, f.getName()); again.run(); })
                        .setNegativeButton("Cancel", null).show();
                return;
            }
            int k = w - imported.size();
            pendingFor = g;
            if (k == 1) a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_FILE);
            else if (k == 2) a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, Cheats.zipName(g.titleId, new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()))), CREATE_ZIP);
            else a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_ZIP);
        }).setNegativeButton("Close", null).show();
    }

    /** MainActivity.onActivityResult: the documents of Import cheat file…, Export cheats… and Import cheats… */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request != PICK_FILE && request != PICK_ZIP && request != CREATE_ZIP) return false;
        Game g = pendingFor;
        pendingFor = null;
        if (g == null) return true;
        File folder = folder(a, g.titleId);
        Set<String> builds = builds(g);
        ContentResolver cr = a.getContentResolver();
        try {
            if (request == PICK_FILE) {
                File kept = Cheats.importFile(folder, read(cr, doc), name(cr, doc), builds);
                Toast.makeText(a, a.getString(R.string.cheats_imported, kept.getName()), Toast.LENGTH_LONG).show();
            } else if (request == PICK_ZIP) {
                Cheats.ZipContent c;
                try (InputStream in = cr.openInputStream(doc)) {
                    if (in == null) throw new IOException("the zip cannot be opened");
                    c = Cheats.readZip(in, g.titleId, builds);
                }
                int n = Cheats.takeZip(folder, c, builds);
                if (c.master != null) Prefs.cheats(a, g.titleId, c.master);
                if (c.on != null) Prefs.cheatsOn(a, g.titleId, c.on);
                Toast.makeText(a, a.getString(R.string.cheats_zip_done, n), Toast.LENGTH_LONG).show();
            } else {
                String build = builds.isEmpty() ? "" : builds.iterator().next();
                try (OutputStream os = cr.openOutputStream(doc)) {
                    if (os == null) throw new IOException("the place picked cannot be written");
                    Cheats.exportZip(os, g.titleId, build, folder, Prefs.cheats(a, g.titleId), Prefs.cheatsOn(a, g.titleId));
                }
                Toast.makeText(a, a.getString(R.string.cheats_exported), Toast.LENGTH_LONG).show();
            }
        } catch (IOException | RuntimeException e) {
            a.error(a.getString(R.string.cheats_refused, e.getMessage()));
        }
        a.gameSettingsTop();
        return true;
    }

    private static byte[] read(ContentResolver cr, Uri doc) throws IOException {
        try (InputStream in = cr.openInputStream(doc)) {
            if (in == null) throw new IOException("the file cannot be opened");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); if (o.size() > Cheats.MAX_FILE) break; }   // checkImport refuses it by its size
            return o.toByteArray();
        }
    }

    private static String name(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String p = doc.getLastPathSegment();
        return p == null ? "" : p;
    }
}
