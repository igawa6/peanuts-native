package dev.peanuts.mods;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * "Install mod…" (DESIGN-MODS.md, this task's step 1; dev.peanuts.settings.ModsSection): the system pickers (folder or zip), resolved to a real path exactly as
 * {@code dev.peanuts.ui.LibrarySettings}' own pickers do (a cloud-provider document with no real path is refused with the same wording), then handed
 * to {@link ModImporter} off the UI thread with a progress callback. Request codes and the {@code onActivityResult} hookup live in
 * {@code LibrarySettings} (which already owns that range); this class only does the picking UI and the import work once a path is in hand, so it has
 * no dependency on {@code dev.peanuts.ui} beyond the one small path-resolution call every other picker in this app already makes the same way.
 */
public final class ModImportUi {
    private ModImportUi() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public interface Callback { void onDone(boolean ok, String message); }

    /** "Install mod…": folder or zip, same two-choice shape as Settings › Library's "Firmware" picker */
    public static void pick(Activity a, String titleId, Runnable onFolder, Runnable onZip) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.app.AlertDialog[] d = new android.app.AlertDialog[1];
        box.addView(textBody(a, "Pick a mod folder or zip. If it contains multiple mod folders, each is installed as a separate option you can enable or disable. "
                + "Nothing is downloaded or checked against a catalog, only files already on this device."));
        box.addView(row(a, "📁", "Folder", v -> { d[0].dismiss(); onFolder.run(); }));
        box.addView(row(a, "🗜️", "Zip file", v -> { d[0].dismiss(); onZip.run(); }));
        d[0] = sheet(a, "Install mod", box);
        d[0].show();
    }

    public static void pickFolder(Activity a, int request) {
        Toast.makeText(a, "Pick the mod's folder", Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    public static void pickZip(Activity a, int request) {
        Toast.makeText(a, "Pick the mod's zip file", Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    /** A picked folder's tree result (ACTION_OPEN_DOCUMENT_TREE): resolved to a real path (as every other folder picker in this app does), then
     *  imported directly from there — no separate staging copy for the folder case, since the source is already real files on the device's own
     *  storage and {@link ModImporter} never writes into it. {@code progress} and {@code done} are called on the main thread. */
    public static void importFolder(Activity a, Uri tree, File modsRoot, String titleId, String displayName, Map<String, String> gameBuilds, ProgressUi progress, Callback done) {
        String path = treeToPath(tree);
        if (path == null) { done.onDone(false, "That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card."); return; }
        File staging = new File(path);
        progress.onProgress(0, 0, "Checking free space…");
        // The free-space check walks the whole source tree (ModImporter.freeSpaceProblem → estimateSize), which for a multi-GB romfs mod is real
        // disk I/O: off the UI thread, same as the copy that follows it, never a synchronous stat walk on the main thread
        new Thread(() -> {
            String problem;
            try { problem = ModImporter.freeSpaceProblem(staging, modsRoot); } catch (RuntimeException e) { problem = "Could not read that folder: " + e; }   // always an end: the section waits for done
            final String fproblem = problem;
            if (fproblem != null) { UI.post(() -> done.onDone(false, fproblem)); return; }
            doImport(staging, modsRoot, titleId, displayName, gameBuilds, false, progress, done);
        }, "peanuts-mod-import").start();
    }

    /** A picked zip's result (ACTION_OPEN_DOCUMENT): a path on local storage is read straight from disk (no copy); anything else (a cloud
     *  provider, an archive app's content provider) is copied into the app's cache first, exactly as {@code LibrarySettings.pickedFirmwareZip}
     *  does for a firmware zip, since the content resolver's stream can only be read once and extraction needs to retry-safe seek in spirit (and,
     *  more simply, so the free-space check below can see the whole file's size before extracting a byte). */
    public static void importZip(Activity a, Uri doc, File modsRoot, String titleId, String displayNameHint, Map<String, String> gameBuilds, ProgressUi progress, Callback done) {
        String local = documentToPath(doc);
        String displayName = displayNameHint != null ? ModImporter.nameFromPickedDisplayName(displayNameHint) : null;
        if (local != null && new File(local).isFile()) { extractAndImport(a, new File(local), null, modsRoot, titleId, displayName, gameBuilds, progress, done); return; }
        progress.onProgress(0, 0, "Copying the zip…");
        new Thread(() -> {
            File tmp = new File(a.getCacheDir(), "mod_import_" + System.nanoTime() + ".zip");
            try (InputStream in = a.getContentResolver().openInputStream(doc); OutputStream o = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new IOException("the picker gave no file");
                byte[] buf = new byte[1 << 20]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 16L << 30) throw new IOException("that zip is too large"); }
            } catch (IOException e) {
                tmp.delete();
                UI.post(() -> done.onDone(false, "Could not copy the zip: " + e.getMessage()));
                return;
            }
            UI.post(() -> extractAndImport(a, tmp, tmp, modsRoot, titleId, displayName, gameBuilds, progress, done));
        }, "peanuts-mod-copy").start();
    }

    private static void extractAndImport(Activity a, File zipFile, File deleteAfter, File modsRoot, String titleId, String displayName, Map<String, String> gameBuilds, ProgressUi progress, Callback done) {
        String problem = sizeProblem(zipFile, modsRoot);
        if (problem != null) { if (deleteAfter != null) deleteAfter.delete(); done.onDone(false, problem); return; }
        new Thread(() -> {
            File staging = new File(a.getCacheDir(), "mod_stage_" + System.nanoTime());
            staging.mkdirs();
            try {
                // How far: the zip's own bytes read, of its size (the bytes written are the files' full sizes, which went past 100 % of the zip's)
                long[] read = {0};
                try (InputStream in = new java.io.FilterInputStream(new FileInputStream(zipFile)) {
                    @Override public int read() throws IOException { int b = super.read(); if (b >= 0) read[0]++; return b; }
                    @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) read[0] += n; return n; }
                }) {
                    ModImporter.Progress p = throttled(progress);
                    ModImporter.extractZip(in, staging, zipFile.length(), (done2, total, name) -> p.onProgress(Math.min(read[0], total), total, name));
                }
                doImport(staging, modsRoot, titleId, displayName, gameBuilds, true, progress, done);
            } catch (IOException | RuntimeException e) {
                // RuntimeException: ZipInputStream throws IllegalArgumentException at an entry whose name is not UTF-8 (a zip made on Windows with names in
                // another code page); uncaught it ended this thread without an end, and the section showed "Installing a mod" until the app was closed
                ModImporter.deleteTree(staging);
                UI.post(() -> done.onDone(false, "Could not extract the zip: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            } finally {
                if (deleteAfter != null) deleteAfter.delete();
            }
        }, "peanuts-mod-extract").start();
    }

    /** A rough pre-check for the zip case: the zip's own compressed size plus 3x headroom for uncompressed romfs content (a loose multiplier —
     *  the exact uncompressed size is only known entry by entry during extraction, and {@code ZipFile} central-directory sizes are good enough
     *  here, so this stays simple rather than opening the whole archive twice) against the mods volume's free space */
    private static String sizeProblem(File zip, File modsRoot) {
        long need = zip.length() * 3;
        File probe = modsRoot;
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        long free = probe == null ? Long.MAX_VALUE : probe.getUsableSpace();
        if (free >= need) return null;
        return "This zip may need more space to extract than is free on this device. Free up space, or import a smaller mod.";
    }

    /** Already off the UI thread (called from {@code importFolder}'s or {@code extractAndImport}'s own worker thread): the import itself, plus the
     *  staging cleanup and the result callback, so every import path reports the same "Installed <name>" / "<error>" wording */
    private static void doImport(File staging, File modsRoot, String titleId, String displayName, Map<String, String> gameBuilds, boolean deleteStagingAfter, ProgressUi progress, Callback done) {
        ModImporter.Result r = ModImporter.importFrom(staging, modsRoot, titleId, displayName, gameBuilds, throttled(progress));
        if (deleteStagingAfter) ModImporter.deleteTree(staging);
        // installed on (ModImporter); a code mod's build is started by the section that asked for the install, which shows it
        String installed = r.modNames.size() == 1 ? "Installed " + r.modName + "." : "Installed " + r.modNames.size() + " mods as separate options.";
        UI.post(() -> done.onDone(r.ok, r.ok ? installed + " They are on from the game's next start."
                + (r.hasCode ? " Code mods need a build with mods, which starts by itself: the Mods section shows how far it is." : "") : r.error));
    }

    public interface ProgressUi { void onProgress(long bytesDone, long total, String currentFile); }

    /**
     * progress on the main thread, at most five times a second. The importer reports every 64 KiB of a zip and every file of a copy: 26,000 messages for a
     * 1.7 GB mod, each of which made the section read the mods' folders and build its rows again on the main thread. Called from the one worker thread of an import
     */
    private static ModImporter.Progress throttled(ProgressUi progress) {
        long[] last = {0};
        return (bytesDone, total, name) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - last[0] < 200) return;
            last[0] = now;
            UI.post(() -> progress.onProgress(bytesDone, total, name));
        };
    }

    /**
     * Takes away what an import that did not end left in the app's cache: a copied zip (mod_import_*.zip) and an extracted one (mod_stage_*), GBs each for a
     * large mod, which nothing else removes when the process ended in the middle. Lists on the caller's thread and deletes on its own; only to be called while
     * no import runs (dev.peanuts.settings.ModsSection: once per process, before its first import can start)
     */
    public static void sweepStaging(android.content.Context c) {
        File[] left = c.getCacheDir().listFiles((d, n) -> n.startsWith("mod_stage_") || n.startsWith("mod_import_"));
        if (left == null || left.length == 0) return;
        new Thread(() -> { for (File f : left) ModImporter.deleteTree(f); }, "peanuts-mod-sweep").start();
    }

    // ---- the one path-resolution call every picker in this app makes (dev.nxnative.android.PathUtil, package-private upstream), reached the same
    // way dev.peanuts.ui.Upstream does, so this package needs no cross-package access to dev.peanuts.ui.Upstream for just these two calls ----

    private static String treeToPath(Uri u) { return path("treeToPath", u); }
    private static String documentToPath(Uri u) { return path("documentToPath", u); }

    private static String path(String method, Uri u) {
        try {
            Method m = Class.forName("dev.nxnative.android.PathUtil").getDeclaredMethod(method, Uri.class);
            m.setAccessible(true);
            return (String) m.invoke(null, u);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---- tiny dialog bits (PUi lives in dev.peanuts.ui and this package deliberately does not depend on it) ----

    private static android.widget.TextView textBody(Activity a, String s) {
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(s);
        t.setTextSize(15);
        return t;
    }

    private static android.widget.LinearLayout row(Activity a, String emoji, String label, android.view.View.OnClickListener l) {
        android.widget.LinearLayout r = new android.widget.LinearLayout(a);
        r.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        r.setPadding(0, 24, 0, 8);
        r.setClickable(true);
        r.setOnClickListener(l);
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(emoji + "  " + label);
        t.setTextSize(16);
        r.addView(t);
        return r;
    }

    private static android.app.AlertDialog sheet(Activity a, String title, android.view.View content) {
        int p = Math.round(20 * a.getResources().getDisplayMetrics().density);
        content.setPadding(p, p / 4, p, p / 2);
        return new android.app.AlertDialog.Builder(a).setTitle(title).setView(content).setNegativeButton("Cancel", null).create();
    }
}
