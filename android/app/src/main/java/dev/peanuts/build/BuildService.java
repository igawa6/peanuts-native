package dev.peanuts.build;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The build: a foreground service ("Building &lt;name&gt;" with a progress bar) in the launcher's process that runs the steps in order on one worker thread, each
 * resumable (a step whose output is there already is skipped):
 * <ol>
 * <li><b>import</b> — an NSP/XCI base becomes &lt;gameDir&gt;/&lt;Name&gt;/&lt;Name&gt;.nup with its update and DLC inside ({@link Importer}: dev.peanuts.importer.Native.writeNup
 *     after Native.keys on &lt;files&gt;/keys/prod.keys); skipped when the .nup is there and newer than every input. A .nup or a dump folder passes through.
 *     Import again ({@link Reimport}, {@link BuildRequest#replaceNup}) makes a .nup that is there anew: the new file is written beside it and takes its place
 *     only when the import is complete, and the pack made from the old file is marked ({@link PackNote}) so that Prepare makes its shaders again.</li>
 * <li><b>prepare</b> — the on-device Prepare ({@link Prepare}): upstream's job in &lt;dump&gt;/../&lt;title ID&gt;-work/ondevice/job.json, run by its PackService (":pack"),
 *     watched until the pack is done, then the game into the launcher's list.</li>
 * <li><b>companion</b> — the chosen catalog package into &lt;title ID&gt;-work/peanuts/&lt;Name&gt;/ ({@link Companions}); skipped when that version is installed.</li>
 * </ol>
 * The queue (issue #348, {@link BuildQueue}): a build asked while another runs waits its turn instead of being refused, and the queue is kept in the app's own
 * storage, so a killed app or process goes on with it (a sticky restart, or the next start of the app: {@link BuildPipeline#resume}). One game at a time, in order:
 * <ul>
 * <li>a game whose build fails or is stopped is marked (its build card says why), and the next one starts;</li>
 * <li>before each game the queue waits while the phone is hot or the battery is low and not charging (upstream's PackPolicy, the packer's own rule, with the
 *     Go on anyway remembered for the device, #93), while a game of this app runs (an import and a Prepare beside it take its memory: 8 GB phones, #124), and
 *     while a build with mods runs (it shares the packer); it goes on by itself;</li>
 * <li>a game whose .nup does not fit the free space of its folder, or whose pack would have less than {@link #MIN_PACK_FREE} to be made in, stops before anything
 *     is written;</li>
 * <li>the notification names the game and its place ("2 of 5"); Cancel stops this game, Cancel all the queue. Only the queue's end alerts, once, with what is
 *     ready and what stopped.</li>
 * </ul>
 * ACTION_CANCEL stops the running build between steps (the packer's job stays saved; the build card resumes it). Needs the manifest entry
 * (dev/peanuts/settings/HOOKS.md) and, for Android 13+, the notification permission for the bar to show.
 */
public final class BuildService extends Service {
    public static final String ACTION_BUILD = "dev.peanuts.build.BUILD", ACTION_CANCEL = "dev.peanuts.build.CANCEL", ACTION_CANCEL_ALL = "dev.peanuts.build.CANCEL_ALL",
            ACTION_GO_ON = "dev.peanuts.build.GO_ON", ACTION_PAUSE = "dev.peanuts.build.PAUSE";
    private static final String TAG = Prepare.TAG, CHANNEL = "peanuts_build";
    private static final int NOTE = 7001;
    /** Each step's share of the bar: import 0-45, prepare 45-90, companion 90-100 */
    private static final int IMPORT_END = 45, PREPARE_END = 90;
    /** Below this much free space where the pack is made, Prepare is not started: no game's pack and its working files fit in less */
    static final long MIN_PACK_FREE = 512L * 1024 * 1024;
    /** How often a wait between two games looks again (the battery and thermal broadcasts wake it sooner) */
    private static final long WAIT_MS = 30_000;

    /** Whether this process has the service now (BuildPipeline.resume starts it when the queue has a game waiting and it does not) */
    static volatile boolean alive;

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private Thread worker;   // the queue's worker; set and cleared on the main thread only (onStartCommand, the worker's end)
    private volatile Prepare prepare;
    private volatile boolean cancelled, cancelAll, timedOut, heatGoOn, pausing;
    private volatile int id;
    private volatile String name = "";
    private boolean buildAnyway;   // this run's explicit consent (the item's Build anyway), never inferred
    private volatile boolean lastOk;
    private volatile String lastMessage = "";
    // The game's build log (BuildLog, Share build log): what this run did, in <app files>/build-logs/<title ID>.log beside logcat
    private volatile String logId;
    private long runStart;
    private String logKey = "";
    private int logBucket = -1;
    // The wait between two games: the readings PackPolicy decides on, what the queue waits for now
    private final Object waitLock = new Object();
    private volatile int batteryPct = 100, thermal;
    private volatile boolean charging = true;
    private volatile String waitReason;
    private boolean policyBroken;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = s -> { thermal = s; wakeWait(); };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { battery(i); wakeWait(); }
    };

    /** Runs the queue: the game just added, or the ones a killed process left */
    static Intent run(Context c) { return new Intent(c, BuildService.class).setAction(ACTION_BUILD); }

    @Override public void onCreate() {
        super.onCreate();
        alive = true;
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building games", NotificationManager.IMPORTANCE_LOW));
        pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "peanuts:build");
        wake.setReferenceCounted(false);
        battery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED));
        thermal = pm.getCurrentThermalStatus();
        pm.addThermalStatusListener(getMainExecutor(), thermalListener);
    }

    @Override public void onDestroy() {
        alive = false;
        try { unregisterReceiver(batteryReceiver); } catch (RuntimeException e) { /* not registered */ }
        pm.removeThermalStatusListener(thermalListener);
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_BUILD : intent.getAction();   // no intent: Android started the service again after its process ended (sticky)
        if (ACTION_CANCEL.equals(action) || ACTION_CANCEL_ALL.equals(action) || ACTION_PAUSE.equals(action)) {
            if (ACTION_CANCEL_ALL.equals(action)) {
                cancelAll = true;
                int n = BuildPipeline.queue(this).removeWaiting();
                if (n > 0) Log.i(TAG, "queue: Cancel all: " + n + " waiting game" + (n == 1 ? "" : "s") + " removed");
                BuildPipeline.changed();
                wakeWait();
            }
            if (ACTION_PAUSE.equals(action)) {   // the control interface's job pause: the whole queue pauses, the game in hand goes on first at Resume
                pausing = true;
                BuildPipeline.queue(this).hold();
                BuildPipeline.changed();
                wakeWait();
            }
            if (id != 0) {   // a game runs: it stops between steps
                cancelled = true;
                Importer.cancel(true);   // an import stops within a second (issue #149; it used to run to its end first)
                Prepare p = prepare;
                if (p != null) {
                    boolean own = p.jobStarted();   // before its start the packer runs another game's job, which is not this Stop's
                    p.cancel();
                    if (own) pausePacker();   // the packer's job pauses too: it went on in the background, and the next game's Prepare would wait for it
                }
            }
            Thread w = worker;
            if (w != null) w.interrupt();
            else stopSelf(startId);
            return w != null ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_GO_ON.equals(action)) {   // the wait's Go on anyway: the heat wait is lifted for this run of the queue (CRITICAL or worse still waits)
            heatGoOn = true;
            Log.i(TAG, "queue: Go on anyway (from the notification): the heat wait is lifted until the queue ends");
            wakeWait();
            if (worker == null) { stopSelf(startId); return START_NOT_STICKY; }   // a stale notification: nothing waits here
            return START_STICKY;
        }
        BuildQueue q = BuildPipeline.queue(this);
        try {
            BuildQueue.Item next = q.running() != null ? q.running() : q.next();
            startForeground(NOTE, next == null ? note(0, "Starting") : worker == null ? note(next, 0, "Starting") : current(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the manifest lacks the service type
            Log.w(TAG, "startForeground refused: " + e);
            BuildQueue.Item next = q.next();
            if (next != null) BuildLogShare.line(this, next.titleId(), "queue: Android refused the build's foreground service (" + e + "): the game waits in the queue until the app is opened");
            BuildPipeline.notStarted();
            if (worker == null) stopSelf(startId);
            return worker == null ? START_NOT_STICKY : START_STICKY;
        }
        if (worker != null) return START_STICKY;   // the worker takes the new game when its turn comes
        if (q.next() == null || q.held()) {   // nothing to do (a start from a stale queue, Cancel all came first, the queue is paused)
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        cancelAll = false;
        timedOut = false;
        heatGoOn = false;
        pausing = false;
        worker = new Thread(this::loop, "peanuts-build");
        worker.start();
        return START_STICKY;
    }

    /** Android 15+'s daily limit for dataSync services: the game that runs goes back to the front of the queue, and the service ends within the seconds Android gives */
    @Override public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "queue: Android's daily limit for builds was reached: the queue stops and goes on when the app is opened");
        timedOut = true;
        cancelAll = true;
        if (id != 0) {
            cancelled = true;
            Importer.cancel(true);
            Prepare p = prepare;
            if (p != null) p.cancel();
        }
        Thread w = worker;
        if (w != null) w.interrupt();
        wakeWait();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (w == null) stopSelf();
    }

    // ---- The queue ----

    /** The worker: one game after the other, each after its wait, until none waits or Cancel all */
    private void loop() {
        BuildQueue q = BuildPipeline.queue(this);
        if (q.saveFailed != null) Log.w(TAG, "queue: its file could not be written (" + q.saveFailed + ")");
        BuildQueue.Item last = null;
        try {
            while (!cancelAll && !q.held()) {
                BuildQueue.Item next = q.next();
                if (next == null) break;
                if (!waitTurn(q, next) || q.held()) break;
                next = q.next();   // the one that waited may have been removed meanwhile
                if (next == null) break;
                runItem(q, next);
                last = next;
                Thread.interrupted();   // a Cancel meant for that game does not reach the next
            }
        } finally {
            wakeHeld(false);
            final BuildQueue.Item fl = last;
            main.post(() -> ended(q, fl));
        }
    }

    /** The worker's end, on the main thread: a game added after the worker looked runs on; else the end's notification, and the service stops */
    private void ended(BuildQueue q, BuildQueue.Item last) {
        worker = null;
        waitReason = null;
        BuildPipeline.waiting(null, 0);
        // a game added while the worker ended (Cancel all emptied the queue: one that waits now was added after it) runs on
        if (!timedOut && !q.held() && q.next() != null) {
            cancelAll = false;
            pausing = false;
            worker = new Thread(this::loop, "peanuts-build");
            worker.start();
            return;
        }
        if (q.held() && q.next() != null) nm.notify(NOTE + 1, pausedNote(q));
        else if (last != null && !timedOut) {
            int total = q.total();
            if (total <= 1) nm.notify(NOTE + 1, done(lastOk, lastMessage, !lastOk && cancelled));   // one game: its own end, as before the queue
            else nm.notify(NOTE + 1, queueDone(q.readyCount() == total, q.summary(), cancelAll));
        }
        BuildPipeline.changed();
        stopForeground(STOP_FOREGROUND_REMOVE);
        nm.cancel(NOTE);   // a progress line posted after the daily limit took the service out of the foreground is not removed with it
        stopSelf();
    }

    /**
     * Waits before a game while the phone is hot or the battery low (PackPolicy), a game of this app runs, a build with mods runs, or a pack of this game is
     * being imported (Import pack, GitHub issue #44); true when the game may start, false when the queue is to stop (Cancel all, Android's daily limit)
     */
    private boolean waitTurn(BuildQueue q, BuildQueue.Item next) {
        String was = null;
        while (!cancelAll && !q.held()) {
            String r = waitFor(was, next);
            if (r == null) {
                if (was != null) {
                    BuildLogShare.line(this, next.titleId(), "queue: going on after the wait (" + was + "; " + readings() + ")");
                    Log.i(TAG, "queue: going on after the " + was + " wait: " + readings());
                }
                waitReason = null;
                BuildPipeline.waiting(null, 0);
                return true;
            }
            if (!r.equals(was)) {
                Log.i(TAG, "queue: waiting before " + next.name() + ": " + r + " (" + readings() + ")");
                BuildLogShare.line(this, next.titleId(), "queue: waiting before the build: " + BuildQueue.waitText(r) + " (" + readings() + ")");
                waitReason = r;
                BuildPipeline.waiting(r, next.id);
                main.post(() -> { if (worker != null) nm.notify(NOTE, waitNote(next, r)); });
            }
            was = r;
            wakeHeld(!r.equals(BuildQueue.WAIT_BATTERY));   // a wait for the charger lets the phone sleep: plugging it in wakes the wait
            synchronized (waitLock) {
                try { waitLock.wait(WAIT_MS); } catch (InterruptedException e) { /* Cancel all, or the daily limit: the loop looks */ }
            }
        }
        return false;
    }

    /** What the queue waits for before next now, null when nothing: was is what it waited for at the last look (the heat wait lasts until MODERATE, PackPolicy) */
    private String waitFor(String was, BuildQueue.Item next) {
        String r = policy(BuildQueue.WAIT_HEAT.equals(was) ? "thermal" : BuildQueue.WAIT_BATTERY.equals(was) ? "battery" : null);
        if ("battery".equals(r)) return BuildQueue.WAIT_BATTERY;
        if ("thermal".equals(r)) return BuildQueue.WAIT_HEAT;
        if (ModBuildPipeline.running() != 0) return BuildQueue.WAIT_MODS;
        if (gameRunning()) return BuildQueue.WAIT_GAME;
        if (dev.peanuts.pack.PackImport.busy(next.titleId())) return BuildQueue.WAIT_PACK;   // the import writes the pack folder this build would use (the sheet's Build waits the same way)
        return null;
    }

    /** Upstream's PackPolicy.reason (the packer's pause rule) on this service's readings: "battery", "thermal" or null */
    private String policy(String was) {
        boolean override = heatGoOn || heatGoOnRemembered();
        try {
            return (String) Upstream.call(Upstream.k("PackPolicy"), null, "reason", new Class<?>[] {int.class, boolean.class, int.class, String.class, boolean.class},
                    batteryPct, charging, thermal, was, override);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!policyBroken) { policyBroken = true; Log.w(TAG, "queue: the packer's pause rule cannot be read (" + e + "): the queue does not wait for heat or the battery"); }
            return null;
        }
    }

    /** Whether Go on anyway lifts this heat wait (PackPolicy.canOverride: not at CRITICAL or worse) */
    private boolean canGoOn() {
        try {
            return Boolean.TRUE.equals(Upstream.call(Upstream.k("PackPolicy"), null, "canOverride", new Class<?>[] {String.class, int.class}, "thermal", thermal));
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    /**
     * The packer's remembered Go on anyway (PackService's PEANUTS prefs peanuts_heat / go_on_anyway, #93), read from its file: the ":pack" process writes it,
     * and this process's own SharedPreferences would keep what it read first
     */
    private boolean heatGoOnRemembered() {
        File f = new File(getDataDir(), "shared_prefs/peanuts_heat.xml");
        try { return f.isFile() && new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).matches("(?s).*name=\"go_on_anyway\"\\s+value=\"true\".*"); }
        catch (IOException | RuntimeException e) { return false; }
    }

    /** Whether a game of this app runs (its ":game" process alive and not cached: GameActivity ends the process when the game closes) */
    private boolean gameRunning() {
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> procs = am == null ? null : am.getRunningAppProcesses();
            if (procs == null) return false;
            String want = getPackageName() + ":game";
            for (ActivityManager.RunningAppProcessInfo p : procs) if (want.equals(p.processName) && p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) return true;
        } catch (RuntimeException e) { /* no answer: no wait */ }
        return false;
    }

    private void battery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale;
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private String readings() {
        return "thermal status " + thermal + ", battery " + batteryPct + " %" + (charging ? ", charging" : "") + (heatGoOn ? ", Go on anyway" : "");
    }

    private void wakeWait() { synchronized (waitLock) { waitLock.notifyAll(); } }

    /** The packer's Pause for the job this game prepares (as its notification's Pause): its job is kept, the game's Resume goes on with it */
    private void pausePacker() {
        try {
            Class<?> s = Upstream.k("PackService");
            startService(new Intent(this, s).setAction(Upstream.str(Upstream.getStatic(s, "ACTION_PAUSE"))).putExtra(Upstream.str(Upstream.getStatic(s, "EXTRA_FROM")), "launcher"));
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "the packer's pause: " + e); }
    }

    /** The partial wake lock: held while a game builds and while the queue waits for heat or a game, so the screen can be off all night; not while it waits for a charger */
    private void wakeHeld(boolean on) {
        try {
            if (on && !wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);   // a long build is far shorter; a lock that outlives a stuck worker ends by itself
            else if (!on && wake.isHeld()) wake.release();
        } catch (RuntimeException e) { Log.w(TAG, "wake lock: " + e); }
    }

    /** One game of the queue: its build, then how it ended into the queue and to the screens that follow it */
    private void runItem(BuildQueue q, BuildQueue.Item next) {
        cancelled = false;   // before the id: a Cancel from here on is this game's
        q.start(next.id, System.currentTimeMillis());
        BuildQueue.Item got = q.get(next.id);   // the game asked again meanwhile keeps its place with the newer request
        final BuildQueue.Item it = got != null ? got : next;
        id = it.id;
        name = it.name();
        buildAnyway = it.buildAnyway;
        BuildPipeline.started(it.id, it.titleId(), name);
        wakeHeld(true);
        String place = q.placeText(it);
        main.post(() -> { if (worker != null) nm.notify(NOTE, note(it, 0, "Starting")); });
        String[] end = run(it.request, place);
        boolean ok = "".equals(end[1]);
        String code = ok ? "" : end[1], message = end[0];
        // A pause is not this game's end: the packer's own Pause (its notification, Home's Prepare card), the control interface's job pause, or a pause
        // Android made (the daily limit, a start it refused). The game goes back to the front and the queue keeps it; Stop and Cancel stop this game only
        boolean packerPaused = !ok && BuildListener.CANCELLED.equals(code) && !cancelled;
        if (timedOut && !ok) {
            q.requeue(it.id);
            message = "Paused: Android allows only so much background work a day. The build of " + name + " goes on when you open the app.";
            code = BuildListener.CANCELLED;
            ok = false;
        } else if (!ok && (pausing || packerPaused)) {
            q.requeue(it.id);
            q.hold();
            log("queue: paused" + (packerPaused ? " (the packer's job paused)" : "") + "; " + name + " goes on first when the queue is resumed");
        } else q.finish(it.id, ok, code, message, System.currentTimeMillis());
        if (q.saveFailed != null) log("queue: its file could not be written (" + q.saveFailed + ")");
        lastOk = ok;
        lastMessage = message;
        int fid = it.id;
        id = 0;
        BuildPipeline.finish(fid, ok, message, code);
    }

    /** One line in logcat and in the game's build log */
    private void log(String s) {
        Log.i(TAG, s);
        BuildLogShare.line(this, logId, s);
    }

    private static String fileLine(File f) {
        if (f == null) return "none";
        if (!f.exists()) return f + " (missing)";
        return f + (f.isFile() ? " (" + BuildLogBundle.size(f.length()) + ", " + BuildLogBundle.time(f.lastModified()) + ")" : " (a folder)");
    }

    private static String freeLine(File dir) {
        for (File d = dir; d != null; d = d.getParentFile()) if (d.isDirectory()) return BuildLogBundle.size(d.getUsableSpace()) + " free in " + d;
        return "free space unknown";
    }

    /** The run's first lines: what was asked, from what files, into which folders, with how much memory and space */
    private void logStart(BuildRequest r) {
        runStart = System.currentTimeMillis();
        logKey = "";
        logBucket = -1;
        log(BuildLog.START + "build of " + name + " (" + r.titleId + "), " + Upstream.buildTag(this) + ", engine " + dev.nxnative.android.BuildConfig.NX_PACK_ENGINE
                + (buildAnyway ? ", Build anyway" : "") + (r.replaceNup != null ? ", Import again" + (r.deleteOldFirst ? " (the old .nup deleted first)" : "") : ""));
        log("base: " + fileLine(r.base));
        log("update: " + fileLine(r.update));
        for (File d : r.dlcs) log("dlc: " + fileLine(d));
        log("game folder: " + (r.gameDir == null ? "not set" : r.gameDir + ", " + freeLine(r.gameDir)));
        File b = destination(r);
        log("build folder: " + (b == null ? "not set" : b + ", " + freeLine(b)));
        log("companion: " + (r.companionPackage == null || r.companionPackage.isEmpty() ? "none chosen" : r.companionPackage));
        String m = BuildMemory.readMeminfo();
        log("memory: " + BuildMemory.meminfoMb(m, "MemAvailable") + " of " + BuildMemory.meminfoMb(m, "MemTotal") + " MB free, swap " + BuildMemory.meminfoMb(m, "SwapFree")
                + " of " + BuildMemory.meminfoMb(m, "SwapTotal") + " MB free");
    }

    /** The run's last line ({@link BuildLog#END}): ready, stopped or failed, with the card's message and how long it took */
    private void logEnd(boolean ok, String code, String message) {
        long s = (System.currentTimeMillis() - runStart) / 1000;
        log(BuildLog.END + (ok ? "ready" : BuildListener.CANCELLED.equals(code) ? "stopped (" + code + ")" : "failed (" + code + ")") + ": " + String.valueOf(message).replace('\n', ' ')
                + " (after " + s / 60 + " min " + s % 60 + " s)");
    }

    /**
     * What the ready message adds when the shader library was written without some shaders (they did not translate or validate, and the build went on with Try
     * anyway): the count, so a build that ends as ready does not hide them. Final Fantasy Resonance was "ready" with 115 of 73,496 left out, Octopath Traveler 0
     * with 2,142 of 10,324, and only ondevice/logs/shaders.log said so. Empty when all were built
     */
    private String shadersLeftOut(BuildRequest r, File dump) {
        try {
            File work = Upstream.workOf(dump, r.titleId);
            long[] f = RuntimeShaders.failed(Upstream.jobFileOf(work));
            if (f[0] <= 0) return "";
            Log.w(TAG, "shaders: " + f[0] + " of " + f[1] + " of " + name + "'s shaders are not in its library");
            return String.format(java.util.Locale.ROOT, " %,d of its %,d shaders could not be made, so some effects may not show. Its build log lists them.", f[0], f[1]);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /**
     * Peanuts cracked (dev.peanuts.ui.Cracked): the game version this build made, from its job.json ("version": 0 for the base game, else the update's
     * number), and the shaders this build prepared (its shaders step's total, when this build wrote the job: "Already prepared" and a pack that fitted
     * prepared none). A version counted before adds nothing
     */
    private void cracked(BuildRequest r, File dump, long since) {
        long version = -1, shaders = 0;
        try {
            File jf = Upstream.jobFileOf(Upstream.workOf(dump, r.titleId));
            if (jf.isFile()) {
                org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(jf.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                version = o.optLong("version", -1);
                org.json.JSONObject steps = o.optJSONObject("steps"), sh = steps == null ? null : steps.optJSONObject("shaders");
                if (sh != null && jf.lastModified() >= since) shaders = Math.max(0, sh.optLong("total", 0));
            }
        } catch (ReflectiveOperationException | IOException | org.json.JSONException e) {
            Log.w(TAG, "peanuts cracked: the build's job is not readable: " + e);
        }
        dev.peanuts.ui.Cracked.built(this, r.titleId, version < 0 ? null : "v" + version, shaders);
    }

    /**
     * What the ready message adds when the build took part of its work from earlier builds (issue #120, peanuts/docs/INCREMENTAL-BUILD.md): how long it took and what
     * came from them, read from this build's step logs (BuildReuse). Empty for a build that kept nothing, or when the logs cannot be found
     */
    private String reused(BuildRequest r, File dump) {
        try {
            File logs = new File(Upstream.workOf(dump, r.titleId), "ondevice/logs");
            String t = BuildReuse.text(BuildReuse.read(logs, runStart), (System.currentTimeMillis() - runStart) / 1000);
            if (!t.isEmpty()) log("reused:" + t);
            return t;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** The full check of the shaders this build kept, when it is due, the phone charges and no game runs (ReuseCheck): in the packer, after the build; else later */
    private void startCheck(BuildRequest r, File dump) {
        try {
            File jobFile = Upstream.jobFileOf(Upstream.workOf(dump, r.titleId));
            ReuseCheck.remember(getNoBackupFilesDir(), r.titleId, jobFile);   // for a start later, from the launcher (ReuseCheck.startAny)
            String why = ReuseCheck.start(this, jobFile, r.titleId);
            if (why == null) log("reuse check: the full check of the kept shaders starts in the background (a game that starts stops it)");
            else if (!why.equals("nothing due")) log("reuse check: later (" + why + ")");
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("reuse check: " + e);
        }
    }

    /** One game's build, its steps in order; returns {the card's message, the failure code ("" when it is ready)} */
    private String[] run(BuildRequest r, String place) {
        final long startedAt = System.currentTimeMillis();
        boolean ok = false;
        String message;
        String code = BuildListener.OTHER;
        logId = r.titleId;
        logStart(r);
        if (!place.isEmpty()) log("queue: " + place);
        try {
            if (r.buildDestination != null && !new File(r.buildDestination).isDirectory())
                throw new IOException("The game's build folder cannot be reached. Connect its storage again, or pick another Build destination, then tap Retry.\n" + r.buildDestination);
            File dump = stepImport(r);
            log("game file: " + fileLine(dump));
            try {
                File work = Upstream.workOf(dump, r.titleId);
                BuildLogShare.remember(this, r.titleId, work);
                log("work folder: " + work);
            } catch (ReflectiveOperationException | RuntimeException e) { log("work folder: unknown (" + e + ")"); }
            if (cancelled) throw new BuildListener.Failure("Build cancelled.", BuildListener.CANCELLED);
            // The storage check before Prepare: no pack is made in less than MIN_PACK_FREE, and a build that ran out of space part way would leave its files
            // for the next game of the queue to run out on as well
            File packHome = destination(r) != null ? destination(r) : getExternalFilesDir(null);   // a build folder's <title ID>/, else the app's own storage (AppStorage)
            long packFree = SpaceEstimate.freeBytes(packHome);
            if (packFree < MIN_PACK_FREE) {
                log("space: " + BuildLogBundle.size(packFree) + " free for the pack in " + packHome + ", below " + BuildLogBundle.size(MIN_PACK_FREE) + ": Prepare not started");
                throw new BuildListener.Failure("Not enough free space to build " + name + ": it needs more than " + SpaceEstimate.words(MIN_PACK_FREE) + ", and "
                        + SpaceEstimate.words(packFree) + " is free. Nothing was written. Free up some space, then tap Retry.\n" + packHome, BuildListener.OUT_OF_SPACE);
            }
            File pack = null;
            IOException prepareFailed = null;
            try {
                pack = stepPrepare(r, dump);
                if (!cancelled) pack = stepRuntimeShaders(r, dump, pack);
            } catch (IOException e) {
                prepareFailed = e;   // the companion is still installed (it goes beside the dump's work folder, which Prepare made): the next Build has only the pack left to do
            }
            if (cancelled) throw new BuildListener.Failure("Build cancelled.", BuildListener.CANCELLED);
            GameEnv.ensureFonts(this, r.titleId);
            GameEnv.ensureBuildId(this, r.titleId, dump);
            try {
                stepCompanion(r, pack);
            } catch (IOException e) {
                if (prepareFailed != null) throw new BuildListener.Failure(prepareFailed.getMessage() + "\n\nCompanion: " + e.getMessage(), category(prepareFailed));
                if (e instanceof BuildListener.Failure || cancelled) throw e;
                // the game is built: only its companion is not there. The installer's own words (a URL, a file, an exception) go to Details
                throw new IOException("Installing the dual-screen companion stopped. " + name + " is ready to play without it. Tap Retry to try the companion again.\n" + e.getMessage(), e);
            }
            if (prepareFailed != null) throw prepareFailed;
            ok = true;
            message = name + " is ready to play." + reused(r, dump) + shadersLeftOut(r, dump);
            progress("done", 100, message);
            dev.peanuts.ui.CrackedCount.never(() -> cracked(r, dump, startedAt), e -> Log.w(TAG, "peanuts cracked: the build is not counted: " + e));   // after done; a failure in it is logged, never the build's
            startCheck(r, dump);
        } catch (IOException e) {
            message = e.getMessage() == null ? e.toString() : e.getMessage();
            code = category(e);
            Log.w(TAG, "build " + name + ": " + message);
        } catch (RuntimeException e) {
            message = "The build stopped because of a problem in the app. " + Prepare.RETRY_SHARE;
            Log.e(TAG, "build " + name, e);
            BuildLogShare.line(this, logId, "crash in the build service:\n" + Log.getStackTraceString(e).trim());
        }
        logEnd(ok, code, message);
        return new String[] {message, ok ? "" : code};
    }

    /** The failure category for the build card's one recovery action (P4): a {@link BuildListener.Failure}'s own code, else a plain-message guess at the ones
     *  this service's own throw sites use (Prepare.java tags its own sites directly) */
    private static String category(IOException e) {
        if (e instanceof BuildListener.Failure) return ((BuildListener.Failure) e).code;
        String m = e.getMessage();
        if (m == null) return BuildListener.OTHER;
        if (m.contains("base file is missing") || m.contains("No space left") || m.contains("ENOSPC")) return m.contains("missing") ? BuildListener.FILE_MISSING : BuildListener.OUT_OF_SPACE;
        return BuildListener.OTHER;
    }

    private File destination(BuildRequest r) {
        return r.buildDestination == null ? PeanutsPrefs.buildGameDir(this, r.titleId) : PeanutsPrefs.buildGameDir(r.buildDestination, r.titleId);
    }

    /** (a) the .nup from an NSP/XCI, or the base as it is */
    private File stepImport(BuildRequest r) throws IOException {
        if (r.base == null || !r.base.exists()) throw new BuildListener.Failure("The game's base file is missing." + (r.base == null ? "" : "\n" + r.base), BuildListener.FILE_MISSING);
        if (r.update != null && !r.update.exists()) throw new BuildListener.Failure("The game's update file is missing.\n" + r.update, BuildListener.FILE_MISSING);
        if (!r.needsImport()) { log("import: not needed, the base is a .nup or a dump"); progress("import", IMPORT_END, "Game files ready"); return r.base; }
        // PEANUTS: a build folder (Settings - Library - Build folder), when set, is where this title's .nup and pack both go (<build folder>/<title ID>/),
        // instead of beside the game and in the app's own storage; PeanutsPrefs.buildGameDir validates the title ID and returns null without one set
        File buildGameDir = destination(r);
        File replace = r.replaceNup;   // Import again: the .nup to make anew, where it is
        boolean again = replace != null;
        if (!again && buildGameDir == null && r.gameDir == null) throw new IOException("The app has no game folder to save the imported game in. Set the Game folder in Settings › Library, then tap Retry.");
        List<File> inputs = r.inputs();
        // PEANUTS: both volumes move to the build folder when it is set (the .nup and the pack land under the same <title ID> folder there)
        File importDest = again ? replace.getAbsoluteFile().getParentFile() : buildGameDir != null ? buildGameDir : r.gameDir;
        File packVolume = buildGameDir != null ? new File(buildGameDir, "pack") : getExternalFilesDir(null);
        File dir = again ? importDest : buildGameDir != null ? buildGameDir : new File(r.gameDir, r.fileName()), out = again ? replace : new File(dir, r.fileName() + ".nup");
        if (dir == null) throw new IOException("The app cannot tell where to save the imported game. " + Prepare.RETRY_SHARE + "\n" + out);
        long newest = 0;
        for (File f : inputs) newest = Math.max(newest, f.lastModified());
        if (!again && out.isFile() && out.length() > 0 && out.lastModified() >= newest) { log("import: imported already, the .nup is newer than every input: " + out); progress("import", IMPORT_END, "Game files imported earlier"); return out; }
        if (!Importer.available()) throw new IOException("This app version cannot import NSP, NSZ, XCI or XCZ files. Build from a .nup or a dump folder instead.");
        File keys = new File(new File(getFilesDir(), "keys"), "prod.keys");
        if (!keys.isFile()) throw new IOException("Add your keys first in Settings › Console, then build.");
        // The sizes come from the probe (the keys first): the .nup's from the titles' own records, which a compressed file's length is not, and what
        // unpacking a solid .nsz needs beside it while it imports (issue #149); a file the probe cannot size counts with its length, as before
        progress("import", 0, "Loading keys");
        long[] sized;
        try {
            if (Importer.keys(keys) != 0) throw new IOException("The app could not read your keys. Add them again in Settings › Console, then tap Retry.\n" + Importer.lastError());
            log("import: keys loaded");
            sized = Importer.sizes(inputs, r.titleId);
        } catch (ReflectiveOperationException e) {
            throw new IOException("The import could not start because of a problem in the app. " + Prepare.RETRY_SHARE + "\n" + e.getMessage());
        }
        final long nupBytes = sized[0], unpackBytes = sized[1], checkBytes = sized[2];
        long importNeed = nupBytes + unpackBytes;   // on the .nup's volume while it imports
        // SpaceEstimate's pre-flight (P4): warn-only (found in review — the multiplier is uncalibrated, so it never blocks Build; a real
        // ENOSPC further down still fails with OUT_OF_SPACE through category()). getExternalFilesDir(null), not getFilesDir(): AppStorage puts the pack there
        // (found in review). Importing: the pack's basis is the merged .nup's own size, the game-folder check's need that and the unpacking
        String spaceWarn = SpaceEstimate.warning(importNeed, nupBytes, true, importDest, packVolume);
        if (spaceWarn != null) log("space: " + spaceWarn);
        if (unpackBytes > 0 || checkBytes > 0)
            log("import: compressed files: " + BuildLogBundle.size(unpackBytes) + " unpacked into a temporary file beside the .nup, " + BuildLogBundle.size(checkBytes) + " checked in place");
        // Import again: the new file is written beside the old one, so both must fit (the sheet checked the same and asked; this is the last look before the
        // work). Unlike the first import's estimate above this one stops the build: the player chose between freeing space and deleting the old file first
        boolean oldThere = again && out.isFile();
        if (oldThere && !r.deleteOldFirst && SpaceEstimate.freeBytes(dir) < importNeed)
            throw new BuildListener.Failure("Not enough free space to import " + name + " again: the new game file needs about " + Reimport.size(importNeed) + " beside the old "
                    + "one" + (unpackBytes > 0 ? " while the compressed files are unpacked" : "") + ", and " + Reimport.size(SpaceEstimate.freeBytes(dir)) + " is free. Nothing was changed.\n" + dir, BuildListener.OUT_OF_SPACE);
        // The first import (the queue's storage check, issue #348): the .nup's size is the probe's, from the titles' own records, not the uncalibrated pack
        // estimate above, so a .nup that cannot fit stops here, before anything is written, and the queue goes on with the next game instead of filling the
        // folder. What a killed import left (the .part, deleted below) counts as free
        if (!again) {
            File part = new File(dir, out.getName() + ".part");
            long free = SpaceEstimate.freeBytes(dir), left = part.isFile() ? part.length() : 0;
            if (free != Long.MAX_VALUE && free + left < importNeed) {
                log("space: " + BuildLogBundle.size(free + left) + " free in " + dir + ", the import needs " + BuildLogBundle.size(importNeed) + ": not started");
                throw new BuildListener.Failure("Not enough free space to import " + name + ": its game file needs about " + Reimport.size(importNeed)
                        + (unpackBytes > 0 ? " while the compressed files are unpacked" : "") + ", and " + Reimport.size(free + left) + " is free"
                        + ". Nothing was written. Free up some space, then tap Retry.\n" + dir, BuildListener.OUT_OF_SPACE);
            }
        }
        long importStart = System.currentTimeMillis();
        log("import: " + inputs.size() + " input" + (inputs.size() == 1 ? "" : "s") + ", " + BuildLogBundle.size(nupBytes) + ", into " + out + " (" + freeLine(dir) + ")");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("The app cannot make a folder for the imported game. Check that the game folder can be written to, then tap Retry.\n" + dir);
            File tmp = new File(dir, out.getName() + ".part");
            tmp.delete();
            deleteUnpacked(tmp);   // what an import the app did not live to finish left (the importer's own folder for the temporary files)
            // The old .nup stays where it is until the new one is complete, so a failed or stopped import leaves the game as it was. Only the player's own
            // second choice (not enough room for both, Reimport.lowSpace) deletes it first; the pack made from it is marked before the file goes
            boolean deleted = false;
            if (oldThere && r.deleteOldFirst) {
                markPacksStale(r, out);
                if (!out.delete()) throw new IOException("The app cannot delete the old game file. Nothing was changed.\n" + out);
                deleted = true;
                log("import again: " + out + " deleted first (the player's choice)");
            }
            String kept = !again ? "" : deleted ? " The old game file was deleted first, as you chose. Open the game's sheet and tap Build to make it again."
                    : " Nothing was replaced, the game is as it was.";
            final long[] last = {0};
            final String[] lastStep = {""};
            Importer.cancel(false);   // a Stop meant for an earlier import does not stop this one; a Stop for this one from here on does (then cancelled is true)
            if (cancelled) throw new BuildListener.Failure("Build cancelled." + kept, BuildListener.CANCELLED);
            int rc = Importer.writeNup(tmp, inputs, r.titleId, (step, done, total) -> {
                long now = System.currentTimeMillis();
                boolean newStep = !step.equals(lastStep[0]) && (step.startsWith("unpack/") || step.startsWith("check/"));
                if (newStep) log("import: " + Compressed.stepText(step, 0, 0).toLowerCase(Locale.ROOT));
                lastStep[0] = step;
                if (now - last[0] < 250 && done < total && !newStep) return;
                last[0] = now;
                progress("import", Compressed.percent(step, done, total, unpackBytes + checkBytes, nupBytes, IMPORT_END), Compressed.stepText(step, done, total));
            });
            if (cancelled) { tmp.delete(); deleteUnpacked(tmp); throw new BuildListener.Failure("Build cancelled." + kept, BuildListener.CANCELLED); }
            if (rc != 0) { tmp.delete();
                deleteUnpacked(tmp);
                String why = Importer.lastError(), damaged = Compressed.failure(why, name, !deleted);
                log("import: the importer returned " + rc + ": " + why + " (after " + (System.currentTimeMillis() - importStart) / 1000 + " s)");
                if (damaged != null) throw new BuildListener.Failure(damaged + (deleted ? kept : ""), BuildListener.FILE_DAMAGED);
                throw new IOException("The import stopped." + (again ? kept : "") + " " + Prepare.RETRY_SHARE);
            }
            // A pack made from the file this import replaces (Import again, or inputs newer than the .nup) was made from other game files: marked before the
            // new file takes the old one's place, so no moment has the new file with a pack that counts as up to date (PackNote, Prepare.run0)
            if (out.isFile()) markPacksStale(r, out);
            if (!place(tmp, out)) { tmp.delete(); throw new IOException("The app could not save the imported game." + (again ? kept : "") + " " + Prepare.RETRY_SHARE + "\n" + out); }
        } catch (ReflectiveOperationException e) {
            throw new IOException("The import could not start because of a problem in the app. " + Prepare.RETRY_SHARE + "\n" + e.getMessage());
        }
        long took = Math.max(1, System.currentTimeMillis() - importStart);
        log("import: done in " + took / 1000 + " s, " + BuildLogBundle.size(out.length()) + " (" + BuildLogBundle.size(out.length() * 1000 / took) + "/s)");
        progress("import", IMPORT_END, "Game files imported");
        return out;
    }

    /** The importer's folder for the temporary files of solid .nsz files beside a .part (".<name>.unpack", nximport.h): emptied and removed. The importer
     *  clears it itself, and its files are unlinked as soon as they are open; only a killed import can leave the folder, or on a storage that keeps an open
     *  file's name, the file */
    private void deleteUnpacked(File part) {
        File d = new File(part.getParentFile(), "." + part.getName() + ".unpack");
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.getName().endsWith(".body") && !k.delete()) log("import: cannot delete " + k);
        if (d.isDirectory() && !d.delete()) log("import: cannot delete " + d);
    }

    /** The finished import over the file it replaces, in one step where the storage can (the old file is never deleted first here) */
    private static boolean place(File tmp, File out) {
        try {
            java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "import: moving " + tmp.getName() + " into place: " + e);
            return tmp.renameTo(out);
        }
    }

    /** The packs made from this game file get PackNote's stale row: the pack of the job beside it, and the pack the launcher would start it with */
    private void markPacksStale(BuildRequest r, File nup) {
        java.util.Set<File> packs = new java.util.LinkedHashSet<>();
        try {
            File jf = Upstream.jobFileOf(Upstream.workOf(nup, r.titleId));
            if (jf.isFile()) packs.add(Upstream.jobPack(Upstream.jobRead(jf)));
        } catch (ReflectiveOperationException | RuntimeException e) { /* no job, or one of another format: the pack below is enough */ }
        try { packs.add(Upstream.packFor(this, nup, r.titleId)); } catch (ReflectiveOperationException | RuntimeException e) { /* no pack found */ }
        for (File p : packs) {
            try { if (p != null && PackNote.markStale(p)) log("import: the pack " + p + " is of the old game file: its shaders are made again"); }
            catch (IOException e) { log("import: cannot mark " + p + ": " + e.getMessage()); }
        }
    }

    /** (b) the pack, by upstream's on-device Prepare */
    private File stepPrepare(BuildRequest r, File dump) throws IOException {
        Prepare p = new Prepare(this);
        prepare = p;
        try {
            File dir = r.buildDestination == null ? null : destination(r);
            File pack = dir == null ? null : new File(dir, "pack");
            return p.run(dump, r.titleId, pack, buildAnyway, (pct, detail) -> progress("prepare", IMPORT_END + pct * (PREPARE_END - IMPORT_END) / 100, detail));
        } finally {
            prepare = null;
        }
    }

    /**
     * (b2) the shaders the game compiles at run time ({@link RuntimeShaders}): when the finished job still misses some, the game is started once to capture them
     * and the job is run again from its split; the pack of the second run is returned (the same folder)
     */
    private File stepRuntimeShaders(BuildRequest r, File dump, File pack) throws IOException {
        File work, jobFile;
        try {
            work = Upstream.workOf(dump, r.titleId);
            jobFile = Upstream.jobFileOf(work);
        } catch (ReflectiveOperationException e) {
            log("runtime shaders: " + e);
            return pack;
        }
        int need = RuntimeShaders.missing(jobFile);
        if (!jobFile.isFile()) return pack;
        boolean runtime;
        try { runtime = RuntimeShaders.compilesAtRunTime(this, r.titleId); }
        catch (IOException e) { log("runtime shaders: cannot tell whether " + name + " compiles shaders at run time (" + e.getMessage() + "); no capture"); return pack; }   // an APK without this title's profile: as before the detector, the build goes on
        if (!runtime) { log("runtime shaders: " + need + " missing from the pack of " + name + ", but the title compiles none at run time: nothing to capture"); return pack; }
        need = Math.max(need, RuntimeShaders.missingInPack(this, r.titleId, pack));
        if (need <= 0) return pack;
        log("runtime shaders: " + need + " missing from the pack of " + name + ": capturing");
        int before = RuntimeShaders.captured(RuntimeShaders.captureDir(work));   // captures an earlier build already made
        boolean enough = new RuntimeShaders(this).capture(r.titleId, work, dump, pack, need, (pct, detail) -> progress("prepare", PREPARE_END - 5 + pct * 5 / 100, detail), () -> cancelled);
        if (cancelled) throw new IOException("Build cancelled.");
        if (!enough) {
            int have = RuntimeShaders.captured(RuntimeShaders.captureDir(work));
            if (have == 0) throw new IOException(String.format(Locale.ROOT, "Finishing graphics setup stopped before it got any of the %,d shaders it needs. ", need) + Prepare.RETRY_SHARE);
            log("runtime shaders: only " + have + " of " + need + " captured; re-running the job with those");
        }
        RuntimeShaders.rerun(work, jobFile);
        progress("prepare", IMPORT_END, "Finishing graphics setup");
        File rebuilt = stepPrepare(r, dump);
        int left = RuntimeShaders.missing(jobFile);
        int runtimeLeft = RuntimeShaders.missingInPack(this, r.titleId, rebuilt);
        if (runtimeLeft > 0) throw new IOException(String.format(Locale.ROOT, "Finishing graphics setup stopped with %,d shaders still missing, so some text or graphics may not show. ", runtimeLeft) + Prepare.RETRY_SHARE);
        // Shaders still missing after the capture: when the capture made a difference (fewer missing), or its captures were already there from an earlier
        // build, the rest are not run-time shaders (no game run makes them, as Mario Kart 8 Deluxe's 135 of another version): the pack is kept, with a log
        // line, instead of asking for a Build again that could never get further. Only a capture that changed nothing on its first try is an error.
        if (left > 0 && (left < need || before >= need)) { log("runtime shaders: " + left + " of " + name + "'s shaders are still missing after the capture; they are not made at run time, the pack is kept"); return rebuilt; }
        if (left > 0) throw new IOException(String.format(Locale.ROOT, "Finishing graphics setup stopped with %,d shaders still missing, so some text or graphics may not show. ", left) + Prepare.RETRY_SHARE);
        return rebuilt;
    }

    /** (c) the companion beside the pack: <title ID>-work/peanuts/<Name>/ */
    private void stepCompanion(BuildRequest r, File pack) throws IOException {
        if (r.companionPackage == null || r.companionPackage.isEmpty()) { progress("companion", 100, "No dual-screen companion chosen"); return; }
        File root = companionRoot(pack, dumpWork(r, pack));
        log("companion " + r.companionPackage + " for " + r.titleId + " into " + root);
        progress("companion", PREPARE_END, "Looking up the dual-screen companion");
        Companions.Item item = Companions.find(Companions.fetch(), r.titleId);
        if (item == null) throw new BuildListener.Failure("No dual-screen companion is available for " + name + " now. The game is ready to play without one.", BuildListener.OTHER);
        String have = Companions.installedVersion(root, item.packageName);
        if (have != null && Companions.compareVersions(have, item.version) >= 0) { log("companion: " + Companions.label(item.packageName, have) + " installed already"); progress("companion", 100, name + "'s dual-screen companion is installed already, version " + have); return; }
        Companions.install(item, root, getCacheDir(), (what, done, total) -> progress("companion", PREPARE_END + (total > 0 ? (int) Math.min(9, done * 9 / total) : 0), companionStep(what)));
        log("companion: " + Companions.label(item.packageName, item.version) + " installed");
        progress("companion", 100, name + "'s dual-screen companion is installed, version " + item.version);
    }

    /** The installer's step (Installer.Progress: download, verify, extract) in the card's words */
    private static String companionStep(String what) {
        switch (String.valueOf(what)) {
            case "download": return "Downloading the dual-screen companion";
            case "verify": return "Checking the dual-screen companion";
            default: return "Installing the dual-screen companion";
        }
    }

    /** The work folder from the pack when the pack is in it (dump's ../<title ID>-work/android-pack), else beside the dump (DumpCheck.workOf) */
    private static File dumpWork(BuildRequest r, File pack) {
        File parent = pack == null ? null : pack.getParentFile();
        if (parent != null && parent.getName().endsWith("-work")) return parent;
        File dump = r.base == null ? null : r.base.getAbsoluteFile();
        File dp = dump == null ? null : dump.getParentFile();
        return new File(dp != null ? dp : new File("."), r.titleId + "-work");
    }

    /** Where the runtime looks (dm/peanuts/peanuts.c rt_find_package, dev.peanuts.Games.Entry.companionRoot): beside the pack, <pack>/../peanuts (the app's packs/peanuts for a pack
     *  the app owns, <title ID>-work/peanuts for one beside the dump); without a pack, beside the dump */
    static File companionRoot(File pack, File work) {
        File parent = pack == null ? null : pack.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : work, "peanuts");
    }

    private void progress(String step, int percent, String detail) {
        // into the build log when the step or the kind of detail changes, or every 5 % of the bar (the counts in a detail change every second)
        // (the import's detail names the part it reads, base and update in turns: only its 5 % steps)
        String key = step + "|" + (step.equals("import") ? "" : String.valueOf(detail).replaceAll("[0-9][0-9.,]*", "#"));
        if (!key.equals(logKey) || percent / 5 != logBucket) {
            logKey = key;
            logBucket = percent / 5;
            BuildLogShare.line(this, logId, "progress " + step + " " + percent + " %: " + detail + " (" + (System.currentTimeMillis() - runStart) / 1000 + " s)");
        }
        BuildPipeline.queue(this).step(id, step);   // kept for the build card of a game that stops in it
        lastPercent = percent;
        lastDetail = detail;
        if (worker != null && !timedOut) {
            BuildQueue.Item it = BuildPipeline.queue(this).get(id);
            nm.notify(NOTE, it == null ? note(percent, detail) : note(it, percent, detail));
        }
        BuildPipeline.progress(id, step, percent, detail);
    }

    private volatile int lastPercent;
    private volatile String lastDetail = "Starting";

    /** The notification the service shows now: the wait, or the game that builds */
    private Notification current() {
        BuildQueue q = BuildPipeline.queue(this);
        BuildQueue.Item it = q.get(id);
        String w = waitReason;
        BuildQueue.Item next = q.next();
        if (it == null && w != null && next != null) return waitNote(next, w);
        return it == null ? note(lastPercent, lastDetail) : note(it, lastPercent, lastDetail);
    }

    private Notification note(int percent, String detail) {
        return builder("Building " + name, detail, "").setProgress(100, Math.max(0, Math.min(100, percent)), false).addAction(action("Cancel", ACTION_CANCEL, 1)).build();
    }

    /** The game that builds: its name, the step, its place in the queue ("2 of 5"); Cancel stops it, Cancel all the queue (while others wait) */
    private Notification note(BuildQueue.Item it, int percent, String detail) {
        BuildQueue q = BuildPipeline.queue(this);
        Notification.Builder b = builder("Building " + it.name(), detail, q.placeText(it)).setProgress(100, Math.max(0, Math.min(100, percent)), false)
                .addAction(action("Cancel", ACTION_CANCEL, 1));
        if (!q.waiting().isEmpty()) b.addAction(action("Cancel all", ACTION_CANCEL_ALL, 2));
        return b.build();
    }

    /** The wait before a game: why, and that it goes on by itself; Go on anyway for a heat wait it can lift */
    private Notification waitNote(BuildQueue.Item next, String reason) {
        BuildQueue q = BuildPipeline.queue(this);
        Notification.Builder b = builder("Waiting to build " + next.name(), BuildQueue.waitText(reason), q.placeText(next));
        if (BuildQueue.WAIT_HEAT.equals(reason) && canGoOn()) b.addAction(action("Go on anyway", ACTION_GO_ON, 3));
        b.addAction(action("Cancel all", ACTION_CANCEL_ALL, 2));
        return b.build();
    }

    private Notification.Builder builder(String title, String text, String sub) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
                .setContentText(text).setOnlyAlertOnce(true).setOngoing(true);
        if (!sub.isEmpty()) b.setSubText(sub);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b;
    }

    private Notification.Action action(String label, String what, int request) {
        Intent i = new Intent(this, BuildService.class).setAction(what);
        return new Notification.Action.Builder(null, label, PendingIntent.getService(this, request, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }

    /** The queue paused (silent): which game goes on first, and where Resume is */
    private Notification pausedNote(BuildQueue q) {
        BuildQueue.Item next = q.next();
        int n = q.waiting().size();
        String text = (next == null ? "" : next.name() + (n > 1 ? " and " + (n - 1) + " more " + (n == 2 ? "game wait." : "games wait.") : " waits.")) + " Open the app and tap Resume to go on.";
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("The build queue is paused")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    /** One game's end (a queue of one, as before the queue) */
    private Notification done(boolean ok, String message, boolean stoppedByUser) {
        Notification.Builder b = new Notification.Builder(this, BuildPipeline.endChannel(nm, CHANNEL, stoppedByUser)).setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? name + " is ready to play" : "Building " + name + " stopped").setContentText(firstLine(message)).setStyle(new Notification.BigTextStyle().bigText(firstLine(message))).setAutoCancel(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    /** A message's main text: its first line (the lines after it are the card's Details: paths, the tools' own words) */
    private static String firstLine(String m) { return m == null ? "" : m.split("\n")[0].trim(); }

    /** The queue's end, once for all its games: what is ready, which stopped and why. Silent after Cancel all (the player asked for it) */
    private Notification queueDone(boolean allReady, String summary, boolean stoppedByUser) {
        Notification.Builder b = new Notification.Builder(this, BuildPipeline.endChannel(nm, CHANNEL, stoppedByUser))
                .setSmallIcon(allReady ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(allReady ? "Your games are ready to play" : stoppedByUser ? "The build queue was stopped" : "The build queue has finished")
                .setContentText(summary).setStyle(new Notification.BigTextStyle().bigText(summary)).setAutoCancel(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }
}
