package dev.peanuts.ui;

import android.app.Activity;
import android.app.ActivityManager;
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

import dev.peanuts.build.BuildLog;
import dev.peanuts.build.BuildLogBundle;
import dev.peanuts.build.BuildLogProvider;
import dev.peanuts.build.BuildLogShare;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

/**
 * Share build log's screens: the sheet that makes one game's bundle ({@link BuildLogShare#make}) and offers Share or Save to a folder, Share log's choice
 * between the newest game run and the newest build ({@link #shareLog}, its words in {@link LogChoice}), and the button on upstream's Prepare card. Every place
 * that shares a log asks which when there are both: Settings › Share log and the game's Storage tab (MainActivity), Home's build card (Library), the Prepare
 * card and its Manage sheet (Ondevice), the "Game stopped" notice (ExitWatch), each an upstream line marked PEANUTS. The control interface's share_build_log
 * makes the same bundle (no screen).
 */
public final class BuildLogUi {
    /** Save to a folder's file picker; its result comes through LibrarySettings.result (MainActivity's one PEANUTS line for every picker) */
    public static final int REQ_SAVE = 71;
    private static final String TAG = "peanuts.build", PREFS = "peanuts_buildlog", SAVE = "_save";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BuildLogUi() {}

    /**
     * The game's bundle, made now, in a sheet: what is in it, then Share or Save to a folder. name: the game's name as the caller knows it, or null (the
     * bundle then finds it); gameName: the name for the sheet's title, looked up on Share log's worker (BuildLogShare.gameName reads the launcher's list)
     */
    private static void share(Activity a, String titleId, String name, String gameName) {
        Context app = a.getApplicationContext();
        String shown = gameName != null && !gameName.isEmpty() ? gameName : String.valueOf(titleId);
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
        return m.files + " files, " + BuildLogBundle.size(m.bytes) + ". Holds the build's step logs, its progress file, the app's own messages and a summary of this phone: "
                + "its model, Android version, memory and free space. No keys, game files or saves." + (m.cut ? " Long logs are cut to their first and last part." : "");
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
            if (!zip.isFile()) msg = "The build log is no longer there. A build log is kept for a day. Open Share log again.";
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

    // ---- Share log: a game run or a build ----

    /**
     * What Share log needs to know before it can ask: the newest run log, the build's log and jobs, the game's name in the launcher's list, the exit record
     * and the running processes. All of it is read on a worker ({@link #anyPick}, {@link #gamePick}): on an SD card or a USB drive that can take seconds, and
     * the tap's screen would stall that long. A second tap meanwhile starts nothing (OffMain)
     */
    private static final OffMain LOOK = new OffMain(java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "peanuts-share-log")), MAIN::post);

    /** What a Share log tap found: what to share, and the words of the choice */
    private static final class Pick {
        int what = LogChoice.NONE;
        CharSequence title, run, build;   // the choice's title and its two lines (ASK only)
        File runLog, digest;              // the run to share, and the digest the "Game stopped" notice wrote beside it (or null)
        String buildTitle, buildName;     // the build's game: its title ID, and its name for the sheet
    }

    /**
     * Settings › Share log: the newest run of any game and the newest build of any game. With both, the player picks one (each says when it happened and how it
     * ended, LogChoice); with one, it is shared at once. runLog: MainActivity's own Share log of the newest run (also its "No log yet." when there is none)
     */
    public static void shareLog(Activity a, Runnable runLog) {
        Context app = a.getApplicationContext();
        LOOK.run(() -> anyPick(app), () -> alive(a), p -> {
            if (p.what == LogChoice.BUILD) { share(a, p.buildTitle, null, p.buildName); return; }
            if (p.what != LogChoice.ASK) { runLog.run(); return; }
            ask(a, p.title, p.run, runLog, p.build, () -> share(a, p.buildTitle, null, p.buildName));
        }, e -> notRead(a, e));
    }

    /** Settings' choice, read on the worker */
    private static Pick anyPick(Context c) {
        Pick p = new Pick();
        File run = newestRunLog(c, null);
        BuildLogShare.Found b = BuildLogShare.found(c, null);
        p.what = LogChoice.choose(run != null, b != null);
        if (b == null) return p;
        p.buildTitle = b.titleId;
        p.buildName = BuildLogShare.gameName(c, b.titleId);
        if (p.what != LogChoice.ASK) return p;
        String rt = run.getName().length() > 16 ? run.getName().substring(0, 16) : "";
        if (rt.equalsIgnoreCase(b.titleId)) {   // both of one game: its name in the title, so each line has room for when and how
            p.title = "Share a log of " + p.buildName;
            p.run = runLine(c, run);
            p.build = buildLine(b);
            return p;
        }
        String rn = run.getName().startsWith("selftest") ? "Graphics self-test" : BuildLogShare.gameName(c, rt);
        p.title = "Share log";
        p.run = PUi.joined(rn, runLine(c, run));
        p.build = PUi.joined(p.buildName, buildLine(b));
        return p;
    }

    /**
     * A game's Share log (its page's Storage tab, its build card on Home, the Prepare card and its menu, the "Game stopped" notice): its newest run and its build.
     * With both, the player picks one; with one, it is shared at once. run: the run to offer (the notice's), null for the game's newest; shareRun: how the caller
     * shares that run (upstream's own Share log, with its "No log of this game yet." when there is none), null for MainActivity's shareLog(log, digest)
     */
    public static void shareLog(Activity a, String titleId, String name, File run, Runnable shareRun) {
        Context app = a.getApplicationContext();
        LOOK.run(() -> gamePick(app, titleId, name, run), () -> alive(a), p -> {
            Runnable runShare = shareRun != null ? shareRun : () -> shareRun(a, p.runLog, p.digest);
            switch (p.what) {
                case LogChoice.ASK: ask(a, p.title, p.run, runShare, p.build, () -> share(a, titleId, name, p.buildName)); return;
                case LogChoice.RUN: runShare.run(); return;
                case LogChoice.BUILD: share(a, titleId, name, p.buildName); return;
                default:
                    if (shareRun != null) shareRun.run();   // the caller's own sentence for no log
                    else share(a, titleId, name, p.buildName);   // the sheet's "No build log of ... yet"
            }
        }, e -> notRead(a, e));
    }

    /** A game's choice, read on the worker */
    private static Pick gamePick(Context c, String titleId, String name, File run) {
        Pick p = new Pick();
        p.runLog = run != null && run.isFile() ? run : newestRunLog(c, titleId);
        p.digest = p.runLog == null ? null : digestOf(p.runLog);
        BuildLogShare.Found b = titleId == null ? null : BuildLogShare.found(c, titleId);
        p.what = LogChoice.choose(p.runLog != null, b != null);
        p.buildTitle = titleId;
        p.buildName = name != null && !name.isEmpty() ? name : BuildLogShare.gameName(c, titleId);
        if (p.what == LogChoice.ASK) {
            p.title = "Share a log of " + p.buildName;
            p.run = runLine(c, p.runLog);
            p.build = buildLine(b);
        }
        return p;
    }

    /** The tap's screen is still there for the answer */
    private static boolean alive(Activity a) { return !a.isFinishing() && !a.isDestroyed(); }

    /** The worker could not read what Share log needs (no access to a folder, an unreadable file): said, nothing shared */
    private static void notRead(Activity a, Exception e) {
        Log.w(TAG, "share log: " + e);
        Toast.makeText(a, "The logs could not be read: " + sentence(e.getMessage()), Toast.LENGTH_LONG).show();
    }

    /** The choice: two rows, the game run and the build, each with its line */
    private static void ask(Activity a, CharSequence title, CharSequence run, Runnable shareRun, CharSequence build, Runnable shareBuild) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(new PUi.Setting(a, "🎮", "Newest game run", v -> { d[0].dismiss(); shareRun.run(); }).value(run).chevron(a).view);
        box.addView(new PUi.Setting(a, "🧾", "Newest build", v -> { d[0].dismiss(); shareBuild.run(); }).value(build).chevron(a).view);
        d[0] = PUi.sheet(a, title, box).setNegativeButton("Cancel", null).show();
    }

    /** The newest run log (&lt;app files&gt;/logs/&lt;title ID&gt;-&lt;time&gt;.log, LogProvider) of one game, or of any run (titleId null: the self-test's too); null when there is none */
    static File newestRunLog(Context c, String titleId) {
        File ext = c.getExternalFilesDir(null);
        String t = titleId == null ? null : titleId.trim().toUpperCase(Locale.ROOT) + "-";
        File[] l = ext == null ? null : new File(ext, "logs").listFiles((d, n) -> n.endsWith(".log") && (t == null || n.toUpperCase(Locale.ROOT).startsWith(t)));
        if (l == null || l.length == 0) return null;
        File best = l[0];
        for (File f : l) if (f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    /** The run's line (LogChoice.runLine): its exit record (ExitWatch's last-exit.txt in the app's files) when it is that run's, else the end of its log */
    private static String runLine(Context c, File run) {
        Map<String, String> rec = LogChoice.record(BuildLog.tail(new File(c.getFilesDir(), "last-exit.txt"), 16 << 10));
        boolean alive = false;
        if (run.getName().equals(rec.get("log"))) {
            int pid = -1;
            try { pid = Integer.parseInt(String.valueOf(rec.get("pid"))); } catch (NumberFormatException e) { /* no pid: not running */ }
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
            if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (p.pid == pid && p.processName != null && p.processName.endsWith(":game")) alive = true;
        }
        return LogChoice.runLine(run.getName(), run.lastModified(), BuildLog.tail(run, 32 << 10), rec, alive, System.currentTimeMillis(), TimeZone.getDefault());
    }

    /** The build's line: its job's when the job changed after the build log, else the build log's newest run */
    private static String buildLine(BuildLogShare.Found b) {
        long now = System.currentTimeMillis();
        TimeZone z = TimeZone.getDefault();
        String fromLog = b.log == null ? null : LogChoice.buildLine(b.log, buildsNow(b.titleId), now, z);
        boolean job = b.jobState != null && !b.jobState.isEmpty();
        if (job && (fromLog == null || Math.max(b.jobUpdated, b.jobCreated) > b.logChanged)) return LogChoice.jobLine(b.jobState, b.jobStep, b.jobCreated, b.jobUpdated, now, z);
        return fromLog != null ? fromLog : "An older build";
    }

    /** A build of this game runs in this process now (BuildPipeline) */
    private static boolean buildsNow(String titleId) {
        Map<String, Object> s = dev.peanuts.build.BuildPipeline.snapshot();
        return s != null && Boolean.TRUE.equals(s.get("running")) && titleId.equalsIgnoreCase(String.valueOf(s.get("title_id")));
    }

    /** The digest the "Game stopped" notice wrote beside a run's log (ExitWatch.digestOf), or null when there is none. Reads the folder: on the worker */
    private static File digestOf(File log) {
        File digest = new File(log.getParentFile(), log.getName().replaceFirst("\\.log$", "") + ".digest.txt");
        return digest.isFile() ? digest : null;
    }

    /** A run's log through MainActivity's own Share log, with its digest (digestOf) when there is one */
    static void shareRun(Activity a, File log, File digest) {
        try {
            Method m = a.getClass().getDeclaredMethod("shareLog", File.class, File.class);
            m.setAccessible(true);
            m.invoke(a, log, digest);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "share log: " + e);
            Toast.makeText(a, "The log could not be shared from here. Settings › Share log sends it.", Toast.LENGTH_LONG).show();
        }
    }

    // ---- The Prepare card (Ondevice, one PEANUTS line) ----

    /**
     * Share log under upstream's Prepare card, shown while its job has failed or is paused. job gives {title ID, name, state} of the card's job, or null
     * without one; the button reads it again every second while it is attached (the card itself is upstream's and draws on its own tick)
     */
    public static void card(Activity a, LinearLayout card, Supplier<String[]> job) {
        Button b = PUi.button(a, "Share log", PUi.Kind.QUIET, v -> {
            String[] j = job.get();
            if (j != null) shareLog(a, j[0], j[1], null, null);
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
