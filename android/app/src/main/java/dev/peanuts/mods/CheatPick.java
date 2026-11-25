package dev.peanuts.mods;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A picked file (.zip or .txt) or folder read for a game's cheats (CheatImport), off the UI thread with a spinner in a dialog meanwhile. The cheat rows' sheet
 * (dev.nxnative.android.CheatsBridge) and the Mods &amp; Cheats page's Add (CheatImportUi) both read their picks here. Nothing is kept: the caller decides.
 */
public final class CheatPick {
    private CheatPick() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** What a pick held: found (null on an error), the pick's name, where it came from (CheatSources.FROM_*), its file name (null for a folder), or why it could not be read */
    public static final class Result {
        public CheatImport.Found found;
        public String name, from = CheatSources.FROM_FILE, display, error;
    }

    /** Reads the pick of uri (folder: a tree) for the game; done runs on the main thread, unless the activity is gone by then */
    public static void read(Activity a, Uri u, boolean folder, String titleId, Set<String> builds, String title, Consumer<Result> done) {
        String display = folder ? null : displayName(a, u);
        LinearLayout box = new LinearLayout(a);
        box.setGravity(Gravity.CENTER);
        int p = Math.round(18 * a.getResources().getDisplayMetrics().density);
        box.setPadding(0, p, 0, p);
        box.addView(new ProgressBar(a));
        AlertDialog wait = new AlertDialog.Builder(a).setTitle(title).setView(box).setCancelable(false).create();
        wait.show();
        new Thread(() -> {
            Result r = new Result();
            r.display = display;
            r.name = display;
            try {
                List<String> paths = new ArrayList<>();
                List<CheatImport.In> in;
                if (folder) {
                    String path = path("treeToPath", u);
                    if (path == null || !new File(path).isDirectory()) throw new IOException("That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card.");
                    File dir = new File(path);
                    r.name = dir.getName();
                    r.from = CheatSources.FROM_FOLDER;
                    in = CheatImport.fromFolder(dir, paths);
                } else if (zip(a, u, display)) {
                    r.from = CheatSources.FROM_ZIP;
                    try (InputStream s = new BufferedInputStream(open(a, u))) { in = CheatImport.fromZip(s, paths); }
                } else {
                    byte[] b;
                    try (InputStream s = open(a, u)) { b = readMax(s, CheatImport.MAX_FILE + 1); }
                    if (b.length > CheatImport.MAX_FILE) throw new IOException("It is larger than " + CheatImport.MAX_FILE / 1024 + " KB: not a cheat file.");
                    String n = display != null ? display : "cheats.txt";
                    in = new ArrayList<>();
                    in.add(new CheatImport.In(n, b));
                    paths.add(n);
                }
                r.found = CheatImport.read(in, paths, titleId, builds, r.name);
            } catch (IOException | RuntimeException e) {
                r.error = e instanceof IOException && e.getMessage() != null && e.getMessage().endsWith(".") ? e.getMessage() : "It could not be read. Nothing was added.";
                android.util.Log.w("peanuts", "cheats: reading a pick: " + e);
            }
            UI.post(() -> {
                wait.dismiss();
                if (!a.isFinishing() && !a.isDestroyed()) done.accept(r);
            });
        }, "peanuts-cheat-pick").start();
    }

    private static InputStream open(Context c, Uri u) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("the pick cannot be opened");
        return in;
    }

    /** Whether the picked file is a zip: its name, else its first bytes ("PK") */
    private static boolean zip(Context c, Uri u, String display) throws IOException {
        String n = display == null ? "" : display.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip")) return true;
        if (n.endsWith(".txt")) return false;
        try (InputStream in = open(c, u)) { return in.read() == 'P' && in.read() == 'K'; }
    }

    private static byte[] readMax(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0 && o.size() <= max; ) o.write(b, 0, n);
        return o.toByteArray();
    }

    /** The picked document's name (its display name), null when the provider has none */
    public static String displayName(Context c, Uri u) {
        try (Cursor k = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (k != null && k.moveToFirst() && !k.isNull(0)) return k.getString(0);
        } catch (RuntimeException ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? null : new File(p).getName();
    }

    /** A document's path on the device (dev.nxnative.android.PathUtil, package-private there, as ModImportUi reaches it), null when it has none */
    private static String path(String method, Uri u) {
        try {
            java.lang.reflect.Method m = Class.forName("dev.nxnative.android.PathUtil").getDeclaredMethod(method, Uri.class);
            m.setAccessible(true);
            return (String) m.invoke(null, u);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
