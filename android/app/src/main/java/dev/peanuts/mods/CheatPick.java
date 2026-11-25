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
                    if (paths.contains("pack.toml")) {   // an .mncp (a zip with pack.toml at its root): read again whole, its manifest and hashes checked
                        byte[] b;
                        try (InputStream s = open(a, u)) { b = readMax(s, CheatImport.MAX_PACK + 1); }
                        if (b.length > CheatImport.MAX_PACK) throw new IOException("It is larger than " + CheatImport.MAX_PACK / (1024 * 1024) + " MB: not a cheat pack.");
                        List<String> broken = new ArrayList<>();
                        paths = new ArrayList<>();
                        in = CheatImport.fromPack(b, paths, broken);
                        if (!broken.isEmpty()) android.util.Log.w("peanuts", "cheats: the pack's files left out: " + broken);
                    }
                } else {
                    boolean mncf = display != null && display.toLowerCase(java.util.Locale.ROOT).endsWith(".mncf");
                    int max = mncf ? CheatImport.MAX_MNCF : CheatImport.MAX_FILE;
                    byte[] b;
                    try (InputStream s = open(a, u)) { b = readMax(s, max + 1); }
                    if (b.length > max) throw new IOException("It is larger than " + max / 1024 + " KB: not a cheat file.");
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

    /**
     * A job off the UI thread with a spinner in a dialog meanwhile, as a pick is read (converting cheats reads the game's own main: seconds for a large game);
     * done or failed then runs on the main thread, unless the activity is gone by then
     */
    public static <T> void work(Activity a, String title, java.util.concurrent.Callable<T> job, Consumer<T> done, Consumer<Exception> failed) { work(a, title, null, job, done, failed); }

    /** The same with a line under the spinner that says what is being done (not "Loading": the spinner shows that), null for none */
    public static <T> void work(Activity a, String title, String doing, java.util.concurrent.Callable<T> job, Consumer<T> done, Consumer<Exception> failed) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = Math.round(18 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);
        box.addView(new ProgressBar(a));
        if (doing != null) {
            android.widget.TextView t = dev.peanuts.ui.PUi.small(a, doing);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, p / 2, 0, 0);
            box.addView(t);
        }
        AlertDialog wait = new AlertDialog.Builder(a).setTitle(title).setView(box).setCancelable(false).create();
        wait.show();
        new Thread(() -> {
            T out = null;
            Exception err = null;
            try { out = job.call(); } catch (Exception e) { err = e; }
            T o = out;
            Exception x = err;
            UI.post(() -> {
                wait.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (x != null) failed.accept(x); else done.accept(o);
            });
        }, "peanuts-cheat-work").start();
    }

    private static InputStream open(Context c, Uri u) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("the pick cannot be opened");
        return in;
    }

    /** Whether the picked file is a zip: its name, else its first bytes ("PK") */
    private static boolean zip(Context c, Uri u, String display) throws IOException {
        String n = display == null ? "" : display.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".mncp")) return true;
        if (n.endsWith(".txt") || n.endsWith(".mncf")) return false;
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
