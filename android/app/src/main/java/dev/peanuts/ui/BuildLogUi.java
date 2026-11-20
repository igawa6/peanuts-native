package dev.peanuts.ui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import dev.peanuts.build.BuildLogBundle;
import dev.peanuts.build.BuildLogProvider;
import dev.peanuts.build.BuildLogShare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Share build log's screens: the sheet that makes one game's bundle ({@link BuildLogShare#make}) and offers Share or Save to a folder, the Settings
 * › Share log choice between the newest game run and the newest build, and the button on upstream's Prepare card. Every entry point shares the same bundle:
 * Home's build card (Library), the Prepare card and its Manage sheet (Ondevice, PEANUTS lines), the game's Storage tab and Settings › Share log
 * (MainActivity, PEANUTS lines), and the control interface's share_build_log (no screen).
 */
public final class BuildLogUi {
    /** Save to a folder's file picker; its result comes through LibrarySettings.result (MainActivity's one PEANUTS line for every picker) */
    public static final int REQ_SAVE = 71;
    private static final String TAG = "peanuts.build", PREFS = "peanuts_buildlog", SAVE = "_save";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BuildLogUi() {}

    /** The game's bundle, made now, in a sheet: what is in it, then Share or Save to a folder. name: the game's name, or null */
    public static void share(Activity a, String titleId, String name) {
        Context app = a.getApplicationContext();
        String shown = name != null && !name.isEmpty() ? name : BuildLogShare.gameName(app, titleId);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        ProgressBar bar = Strip.card(a);
        bar.setIndeterminate(true);
        PUi.add(box, bar, 12);
        TextView text = PUi.body(a, "");
        text.setVisibility(View.GONE);
        PUi.add(box, text, 4);
        AlertDialog d = PUi.sheet(a, "Build log of " + shown, box).setPositiveButton("Share", null).setNeutralButton("Save to a folder", null).setNegativeButton("Cancel", null).show();
        Button share = d.getButton(AlertDialog.BUTTON_POSITIVE), save = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        share.setEnabled(false);
        save.setEnabled(false);
        new Thread(() -> {
            BuildLogShare.Made m = null;
            String error = null;
            try { m = BuildLogShare.make(app, titleId, name, BuildLogShare.outDir(app)); }
            catch (IOException | RuntimeException e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); Log.w(TAG, "build log of " + titleId, e); }
            BuildLogShare.Made fm = m;
            String fe = error;
            MAIN.post(() -> {
                if (a.isFinishing() || a.isDestroyed() || !d.isShowing()) return;
                bar.setVisibility(View.GONE);
                text.setVisibility(View.VISIBLE);
                if (fm == null || !fm.anything) {
                    text.setText(fm == null ? "The build log could not be made: " + sentence(fe) : "No build log of " + shown + " yet. A log is written from the next build on.");
                    share.setVisibility(View.GONE);
                    save.setVisibility(View.GONE);
                    d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("OK");
                    return;
                }
                text.setText(contents(fm));
                share.setEnabled(true);
                save.setEnabled(true);
                share.setOnClickListener(v -> { d.dismiss(); send(a, fm.zip, fm.name); });
                save.setOnClickListener(v -> { d.dismiss(); saveTo(a, fm.zip); });
            });
        }, "peanuts-build-log").start();
    }

    /** What the sheet says the bundle holds */
    static String contents(BuildLogShare.Made m) {
        return m.files + " files, " + BuildLogBundle.size(m.bytes) + ". Holds the build's step logs, its job file, the app's own messages and a summary of this phone "
                + "(model, Android, memory, free space). No keys, game files or saves." + (m.cut ? " Long logs are cut to their first and last part." : "");
    }

    private static String sentence(String s) {
        if (s == null || s.isEmpty()) return "unknown reason.";
        return s.endsWith(".") ? s : s + ".";
    }

    /** ACTION_SEND of the zip, read-only, on the display the app is on (the Thor's two screens: the chooser comes up where the player tapped) */
    static void send(Activity a, File zip, String name) {
        Uri uri = BuildLogProvider.uri(a, zip);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Peanuts Native build log: " + name).putExtra(Intent.EXTRA_TITLE, zip.getName());
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Share the build log");
        try {
            a.startActivity(chooser, onThisDisplay(a));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "share build log: " + e);
            new AlertDialog.Builder(a).setMessage("No app on this phone can share a file. Save it to a folder instead.")
                    .setPositiveButton("Save to a folder", (x, w) -> saveTo(a, zip)).setNegativeButton("Cancel", null).show();
        }
    }

    private static android.os.Bundle onThisDisplay(Activity a) {
        try { return a.getDisplay() == null ? null : ActivityOptions.makeBasic().setLaunchDisplayId(a.getDisplay().getDisplayId()).toBundle(); }
        catch (RuntimeException e) { return null; }
    }

    /** Save to a folder: the system's file picker makes the file, the zip is copied into it ({@link #result}) */
    static void saveTo(Activity a, File zip) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SAVE, zip.getPath()).apply();   // the picker may outlive this process
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, zip.getName());
        try { a.startActivityForResult(i, REQ_SAVE, onThisDisplay(a)); }
        catch (ActivityNotFoundException e) { Toast.makeText(a, "This phone has no file picker to save with.", Toast.LENGTH_LONG).show(); }
    }

    /** The file picker's answer (LibrarySettings.result asks here first); true when it was Save to a folder's */
    public static boolean result(Activity a, int request, int result, Intent data) {
        if (request != REQ_SAVE) return false;
        String p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SAVE, null);
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SAVE).apply();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || p == null) return true;
        File zip = new File(p);
        Uri to = data.getData();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String msg;
            if (!zip.isFile()) msg = "The build log is no longer there (it is kept for a day). Open Share build log again.";
            else try (InputStream in = new FileInputStream(zip); OutputStream out = app.getContentResolver().openOutputStream(to, "w")) {
                if (out == null) throw new IOException("the folder took no file");
                byte[] b = new byte[64 << 10];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                msg = "Saved " + zip.getName() + ".";
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "save build log: " + e);
                msg = "The build log was not saved: " + sentence(e.getMessage());
            }
            String fm = msg;
            MAIN.post(() -> Toast.makeText(app, fm, Toast.LENGTH_LONG).show());
        }, "peanuts-build-log-save").start();
        return true;
    }

    // ---- Settings › Share log ----

    /**
     * Settings › Share log: the newest game run's log as before (runLog: MainActivity's own shareLog), unless a build log changed after it: then the player
     * picks the newest run or the newest build
     */
    public static void shareLog(Activity a, Runnable runLog) {
        Object[] nb = BuildLogShare.newest(a);
        File run = newestRunLog(a);
        if (nb == null || run != null && run.lastModified() >= (Long) nb[1]) { runLog.run(); return; }
        String t = (String) nb[0];
        String bname = BuildLogShare.gameName(a, t);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        if (run != null) {
            String rn = run.getName().startsWith("selftest") ? "Graphics self-test" : BuildLogShare.gameName(a, run.getName().length() > 16 ? run.getName().substring(0, 16) : "");
            box.addView(new PUi.Setting(a, "🎮", "Newest game run", v -> { d[0].dismiss(); runLog.run(); }).value(rn + ", " + when(run.lastModified())).chevron(a).view);
        }
        box.addView(new PUi.Setting(a, "🧾", "Newest build", v -> { d[0].dismiss(); share(a, t, bname); })
                .value(bname + ", " + state((String) nb[2], (String) nb[3], t) + ", " + when((Long) nb[1])).chevron(a).view);
        d[0] = PUi.sheet(a, "Share log", box).setNegativeButton("Cancel", null).show();
    }

    /** The newest run log (MainActivity's Share log: &lt;app files&gt;/logs/*.log), null when there is none */
    private static File newestRunLog(Context c) {
        File ext = c.getExternalFilesDir(null);
        File[] l = ext == null ? null : new File(ext, "logs").listFiles((d, n) -> n.endsWith(".log"));
        if (l == null || l.length == 0) return null;
        File best = l[0];
        for (File f : l) if (f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    /** The newest build's state in a word from its log's end line ("ready", "stopped", "failed"), or what its missing end line means */
    static String state(String end, String start, String titleId) {
        if (end != null) { int sp = end.indexOf(' '); String w = (sp < 0 ? end : end.substring(0, sp)).replace(":", ""); return w.isEmpty() ? "ended" : w; }
        if (start == null) return "no build";
        java.util.Map<String, Object> s = dev.peanuts.build.BuildPipeline.snapshot();
        boolean runs = s != null && Boolean.TRUE.equals(s.get("running")) && titleId.equalsIgnoreCase(String.valueOf(s.get("title_id")));
        return runs ? "running" : "ended with the app";
    }

    private static String when(long t) { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(t)); }

    // ---- The Prepare card (Ondevice, one PEANUTS line) ----

    /**
     * Share build log under upstream's Prepare card, shown while its job has failed or is paused. job gives {title ID, name, state} of the card's job, or
     * null without one; the button reads it again every second while it is attached (the card itself is upstream's and draws on its own tick)
     */
    public static void card(Activity a, LinearLayout card, Supplier<String[]> job) {
        Button b = PUi.button(a, "Share build log", PUi.Kind.QUIET, v -> {
            String[] j = job.get();
            if (j != null) share(a, j[0], j[1]);
        });
        b.setVisibility(View.GONE);
        PUi.add(card, b, 8, PUi.wrap());   // as on Home's build card: a quiet action under the card's own buttons
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!b.isAttachedToWindow()) return;
            String[] j = job.get();
            b.setVisibility(j != null && ("failed".equals(j[2]) || "paused".equals(j[2])) ? View.VISIBLE : View.GONE);
            b.postDelayed(tick[0], 1000);
        };
        b.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.removeCallbacks(tick[0]); tick[0].run(); }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(tick[0]); }
        });
    }

    /** The control interface's share_build_log: the bundle into &lt;app files&gt;/ctl/build-logs (adb pulls it), no screen. Throws with a sentence */
    public static BuildLogShare.Made forCtl(Context c, String titleId) throws IOException {
        File ext = c.getExternalFilesDir(null);
        if (ext == null) throw new IOException("The app's folder on shared storage is not there.");
        return BuildLogShare.make(c, titleId, null, new File(new File(ext, "ctl"), "build-logs"));
    }
}
