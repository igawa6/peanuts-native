package dev.peanuts.build;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The full check of the shaders a build kept (issue #120, peanuts/docs/INCREMENTAL-BUILD.md "The full check"). A build that kept shaders from earlier builds checks
 * a sample of them against a fresh translation; dm/peanuts/reuse/shader_reuse.py then leaves &lt;no_backup&gt;/reuse/&lt;title ID&gt;/check.todo, and this class has the
 * packer (PackService, ":pack") translate every shader of the game again later and compare them all (shader_reuse.py --check): while the phone charges, no game
 * runs and no build runs, from the end of a build (BuildService) and when the launcher shows (Library). The packer does it only for a done job, changes nothing
 * of the job, stops when a game starts or its policy pauses (battery, heat), and goes on another time from the results it kept. A library that was not a full
 * translation's byte for byte is replaced by one there (check.failed says which shaders differed), and a notification says so.
 */
public final class ReuseCheck {
    private ReuseCheck() {}

    private static final String TAG = "peanuts";
    /** PackService ACTION_START's extra: run the done job's full check (PackService.checkKept) */
    public static final String EXTRA_CHECK = "pn_check";
    /** The packer's notification while it checks */
    public static final String NOTE = "Making every shader again, to compare with the ones kept from before. Starting a game stops this, and it goes on another time.";
    static final String TODO = CheckDue.TODO;

    static File reuseDir(File noBackup, String titleId) { return new File(noBackup, "reuse/" + titleId); }

    /** Whether a build's kept shaders wait for their full check */
    public static boolean due(File noBackup, String titleId) {
        return noBackup != null && titleId != null && titleId.matches("[0-9A-Fa-f]{16}") && new File(reuseDir(noBackup, titleId), TODO).isFile();
    }

    /** Whether a game runs now (the :game process): the check waits for it, and stops when one starts */
    public static boolean gameRunning(Context c) { return running(c, ":game"); }

    private static boolean running(Context c, String suffix) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.equals(c.getPackageName() + suffix)) return true;
        return false;
    }

    /** Battery the check needs at least, plugged in: a port that does not keep up under load (a computer's) drains the phone while the check translates */
    static final int MIN_PCT = 40;
    static final String NOT_PLUGGED = "not plugged in with " + MIN_PCT + " % or more";

    /**
     * Whether the phone is plugged in with at least MIN_PCT % (plugged, not "charging": with a charge limit or bypass charging the phone runs from the charger and
     * Android says it is not charging). The packer's own policy still pauses below 20 % on battery
     */
    static boolean charging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null || b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return false;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 || level * 100 / scale >= MIN_PCT;
    }

    /**
     * Has the packer check the job's kept shaders when that is due, the phone charges and no game runs. → why not, or null when it was asked. The packer decides
     * again itself: only a done job of this app version, and only while it runs no other (a start beside a running job does nothing there)
     */
    public static String start(Context c, File jobFile, String titleId) {
        if (!due(c.getNoBackupFilesDir(), titleId)) return "nothing due";
        if (jobFile == null || !jobFile.isFile()) return "no job";
        // the packer checks only with a done job: one not done yet (it starts the check at its end), failed or paused started the service every two minutes
        // for nothing, and held every other game's check (startAny takes one at a time; found in review)
        String state = jobState(jobFile);
        if (!"done".equals(state)) return "its job is " + (state == null ? "unreadable" : state);
        if (!charging(c)) return NOT_PLUGGED;
        if (gameRunning(c)) return "a game runs";
        try {
            c.startForegroundService(Upstream.packStart(c, jobFile).putExtra(EXTRA_CHECK, true));
            Log.i(TAG, "reuse check: " + titleId + ": the packer checks the shaders the last build kept");
            return null;
        } catch (ClassNotFoundException | RuntimeException e) {   // no packer in this build, or Android refused a start from the background
            return "not started: " + e;
        }
    }

    static final String JOB = CheckDue.JOB;   // the job file the check runs with, written by the build that left check.todo (remember)

    /** The job file's state ("done", "failed", ...), null when it does not read */
    static String jobState(File jobFile) {
        try { return new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8)).optString("state", null); }
        catch (IOException | JSONException e) { return null; }
    }
    private static final long EVERY_MS = 120_000L;
    private static long lastTry;

    /** The job a due check runs with, beside its check.todo, so that a later start (startAny) finds it without the game list */
    public static void remember(File noBackup, String titleId, File jobFile) {
        if (!due(noBackup, titleId) || jobFile == null) return;
        CheckDue.write(new File(reuseDir(noBackup, titleId), JOB), jobFile.getPath());   // whole or not at all
    }

    /**
     * From the launcher's timer (Library, every 30 s while the app runs): a due check of any game starts when the phone charges and no game runs, at most every
     * two minutes, off the main thread. The packer lets a job that starts meanwhile go first (PackService)
     */
    public static void startAny(Context c) {
        long now = System.currentTimeMillis();
        synchronized (ReuseCheck.class) {
            if (now - lastTry < EVERY_MS) return;
            lastTry = now;
        }
        Context app = c.getApplicationContext();
        new Thread(() -> {
            File[] games = new File(app.getNoBackupFilesDir(), "reuse").listFiles();
            if (games == null) return;
            for (File g : games) {
                if (!due(app.getNoBackupFilesDir(), g.getName())) continue;
                try {
                    File job = new File(new String(Files.readAllBytes(new File(g, JOB).toPath()), StandardCharsets.UTF_8).trim());
                    String why = start(app, job, g.getName());
                    if (why == null) return;   // one at a time
                    if (!why.equals(NOT_PLUGGED) && !why.equals("a game runs")) Log.i(TAG, "reuse check: " + g.getName() + ": " + why);
                    if (why.equals(NOT_PLUGGED) || why.equals("a game runs")) return;   // the same for every game
                } catch (IOException e) {
                    Log.i(TAG, "reuse check: " + g.getName() + ": no job to check with (" + e.getMessage() + ")");
                }
            }
        }, "pn-reuse-check").start();
    }

    /** The notification's text when the check replaced the pack's library since `since`, else null (CheckDue: check.failed says whether it did, and how
     *  many shaders differed; the count of its first 50 names and "nothing replaced" read wrong before, found in review) */
    public static String replacedText(File noBackup, String titleId, String name, long since) {
        return CheckDue.replacedText(reuseDir(noBackup, titleId), name, since);
    }

    /** One line, with the time, in the job's ondevice/logs/check.log */
    public static void log(File logs, String line) {
        try {
            logs.mkdirs();
            Files.write(new File(logs, "check.log").toPath(), ("[pack] " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + line + "\n")
                    .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "reuse check: " + e);
        }
    }
}
