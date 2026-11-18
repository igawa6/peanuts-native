package dev.nxnative.android;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import dev.peanuts.build.BuildMemory;   // PEANUTS
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-device job (ONDEVICE.md "The service and the screen"): a foreground service of type dataSync in its own process (:pack), started by the launcher's
 * "Prepare game". It runs the job's steps in order on one worker thread with a partial wake lock, posts an ongoing notification with the step's progress and a
 * Pause action, and goes on when the app is swiped away. Every change goes to job.json, so a killed process loses at most the step in hand (the translation not even
 * that): START_STICKY brings the service back and it resumes a job that was running; the launcher's Resume does the same after a reboot or a force stop.
 * <ul>
 * <li>Pause (the notification or the launcher): the tool is interrupted (the translation stops after the shader in hand), the job is written paused, the service
 *     stops and leaves a notification with Resume.</li>
 * <li>Battery below 20 % and not charging, or thermal status SEVERE or worse (PackPolicy): the tool is interrupted, the job waits (paused, wake lock released) and
 *     goes on by itself when charging / cooled (thermal MODERATE or better). Each pause and each going on is a line in the step's log with the thermal status, the
 *     thermal headroom and the battery. A heat pause offers Go on anyway (ACTION_GO_ON: the card and the notification), which lifts it while this service runs the job.</li>
 * <li>Android 14+'s 6 hours a day for dataSync services (onTimeout): paused like Pause, reason "timeout"; the launcher's Resume, once the app is open, goes on.</li>
 * </ul>
 * The launcher binds with a Messenger (MSG_REGISTER with replyTo) and gets MSG_STATE messages, data "job" = job.json's text, as the job changes.
 */
public final class PackService extends Service {
    static final String ACTION_START = "dev.nxnative.android.pack.START", ACTION_PAUSE = "dev.nxnative.android.pack.PAUSE";
    /** Go on anyway: the heat pause is lifted (PackPolicy) until the job stops in this service; EXTRA_FROM says who asked */
    static final String ACTION_GO_ON = "dev.nxnative.android.pack.GO_ON";
    static final String EXTRA_JOB = "job";
    /** ACTION_PAUSE's sender, for the logs (a job paused with reason "user"): notification, launcher or adb; missing = an intent from elsewhere */
    static final String EXTRA_FROM = "from";
    static final int MSG_REGISTER = 1, MSG_UNREGISTER = 2, MSG_STATE = 3;
    private static final String TAG = "nxpack", CHANNEL = "pack";
    private static final int NOTE = 1;
    /** Starts of one step the process may not live through before the job fails (Job.start) */
    private static final int MAX_STARTS = 3;   // PEANUTS: was 2; the later starts run with fewer workers (Job.memLevel, BuildMemory)
    private static final Pattern PROGRESS = Pattern.compile("^progress:? (\\d+)(?: |/)(\\d+)(?: (\\S+))?");
    /** A tool's "stage: <what it is doing now>" line (Job.stepNote) */
    private static final Pattern STAGE = Pattern.compile("^stage: (.+)$");

    private final List<Messenger> clients = new CopyOnWriteArrayList<>();
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private NotificationManager nm;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private PackEngine engine;
    private volatile Job job;
    private volatile Thread worker;
    private volatile String stopReason;   // user, timeout: the job pauses and the service stops
    private volatile String autoReason;   // battery, thermal: the job waits and goes on by itself
    private volatile boolean inTool;
    private PrepareText.Failure failure;   // what the last step that failed said for the card (step() sets it with its reason; the worker thread only)
    private volatile boolean heatOverride;   // Go on anyway: the heat pause is lifted while this service runs the job
    private int batteryPct = 100, thermal;
    private boolean charging = true;
    private long lastNote, lastSave, lastSend;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = s -> { thermal = s; policy(); };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { battery(i); policy(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        pm = getSystemService(PowerManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Preparing a game", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress while the app prepares a game from your dump");
        nm.createNotificationChannel(ch);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nxnative:pack");
        wake.setReferenceCounted(false);
        engine = PackEngine.create(this);
        battery(registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED));
        thermal = pm.getCurrentThermalStatus();
        pm.addThermalStatusListener(getMainExecutor(), thermalListener);
        policy();
    }

    @Override public void onDestroy() {
        unregisterReceiver(batteryReceiver);
        pm.removeThermalStatusListener(thermalListener);
        if (wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return messenger.getBinder(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_GO_ON.equals(action)) {
            String from = intent.getStringExtra(EXTRA_FROM);
            boolean running = worker != null && worker.isAlive();
            if (running && !heatOverride) {
                heatOverride = true;
                stepLog(job, "going on anyway: the heat pause is lifted (from " + (from != null ? from : "an intent without \"from\"") + "; " + readings() + ")");
                policy();
            }
            else if (worker == null) stopSelf(startId);
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            String from = intent.getStringExtra(EXTRA_FROM);
            boolean running = worker != null && worker.isAlive();
            logPause(job, (from != null ? from : "an intent without \"from\"") + (running ? "" : ", nothing running"));
            if (running) requestStop("user");
            else if (worker == null) stopSelf(startId);   // a stale notification: nothing runs here
            return START_STICKY;
        }
        String path = intent == null ? null : intent.getStringExtra(EXTRA_JOB);
        File f = path != null ? new File(path) : Job.remembered(this);
        Job j = null;
        try {
            if (f != null) j = Job.read(f);
        } catch (IOException e) {
            Log.w(TAG, "job: " + e.getMessage());
        }
        // A sticky restart (no intent: the process was killed) resumes only a job that was running; a start resumes one that is not done
        boolean go = j != null && (intent == null ? "running".equals(j.state()) : !"done".equals(j.state()) || j.apkChanged());
        if (go && intent == null) stepLog(j, "going on after Android ended the job's process (a sticky restart; " + readings() + ")");
        if (!go && (worker == null || !worker.isAlive())) {
            // startForegroundService must be answered by startForeground even when there is nothing to do
            if (intent != null) startForeground(j);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            startForeground(job);
            return START_STICKY;
        }
        job = j;
        if (!startForeground(j)) {
            // Android refused a foreground service here (a start from the background): the job stays as it is, the launcher's Resume starts it
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if ("running".equals(j.state())) {   // PEANUTS: still "running" at a start: the process before this one was ended (Android's low-memory killer, most likely): fewer workers from now on
            // PEANUTS: ...unless the app was installed again after the job's last note: an update ends every process of the app, and that is no sign of low memory.
            // Three installs during one build left Mario Kart 8 Deluxe's on one worker with 10 GB free
            long installed = 0;
            try { installed = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; } catch (android.content.pm.PackageManager.NameNotFoundException e) { /* 0: counted as a kill */ }
            if (installed > j.updated()) stepLog(j, "the build's process ended when the app was updated; resuming with the same workers (level " + j.memLevel() + "; " + memLine() + ")");
            else {
                j.memLevelUp();
                stepLog(j, "the build's process ended before its step finished (most often Android freeing memory); resuming with fewer workers (level " + j.memLevel() + "; " + memLine() + ")");
            }
        }
        stopReason = null;
        heatOverride = false;   // asked again for each run of the job
        if (intent != null) j.endedAll();   // the user's Start / Resume / Retry: earlier deaths in a step count no more (only sticky restarts add up)
        // PEANUTS: a build with code mods (Job.modArgs, dev.peanuts.build.Prepare.runModded) is not remembered: Home's panel follows the remembered job
        // and puts a finished one's pack into the game list by title ID, which made the modded pack the game's own (its Play)
        if (j.modArgs().isEmpty()) Job.remember(this, j.file);
        worker = new Thread(this::work, "nxpack-job");
        worker.start();
        return START_STICKY;
    }

    /** Android 14+'s daily dataSync limit (6 h): pause cleanly and stop within the seconds Android gives (else it ends the app) */
    @Override public void onTimeout(int startId, int fgsType) {
        Job j = job;
        stepLog(j, "pausing: timeout: Android's daily limit for background work (6 h of dataSync) is reached; " + readings());
        if (j != null) {
            j.state("paused", "timeout", null);
            save(j, true);
        }
        requestStop("timeout");
        stopForeground(STOP_FOREGROUND_DETACH);
        if (j != null) nm.notify(NOTE, note(j));
        stopSelf();
    }

    private boolean startForeground(Job j) {
        try {
            startForeground(NOTE, note(j), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            return true;
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the type's time limit used up
            Log.w(TAG, "startForeground refused: " + e);
            if (j != null && !"done".equals(j.state())) {
                stepLog(j, "pausing: refused: Android did not let the job start in the background (" + e.getClass().getSimpleName() + ")");
                j.state("paused", "refused", null);
                save(j, true);
            }
            return false;
        }
    }

    /** A pause the user asked for, in logcat (nxpack) and in the current step's log (ondevice/logs/<step>.log): who asked and when */
    private void logPause(Job j, String from) { stepLog(j, "pause asked (from " + from + ")"); }

    /** One line, with the time, in logcat (nxpack) and in the current step's log (ondevice/logs/<step>.log) */
    private void stepLog(Job j, String what) {
        String line = "[pack] " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + what;
        Log.i(TAG, line);
        if (j == null) return;
        int i = j.current();
        if (i < 0) return;
        File logs = new File(j.dir(), "logs");
        try {
            logs.mkdirs();
            Files.write(new File(logs, Job.STEPS[i] + ".log").toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.w(TAG, "step log: " + e);
        }
    }

    private void requestStop(String reason) {
        stopReason = reason;
        engine.interrupt();
        synchronized (lock) { lock.notifyAll(); }
        Thread w = worker;
        // a tool that does not stop within 20 s goes with the process: every step is restartable, and the job says paused
        main.postDelayed(() -> {
            if (w != null && w.isAlive() && worker == w) {
                Job j = job;
                stepLog(j, "the tool did not stop within 20 s of the " + reason + " pause: ending the process (the step runs again on Resume)");
                if (j != null) {
                    j.state("paused", reason, null);
                    save(j, true);
                    stopForeground(STOP_FOREGROUND_DETACH);
                    nm.notify(NOTE, note(j));
                }
                Process.killProcess(Process.myPid());
            }
        }, 20_000);
    }

    // ---- Battery and heat ----

    private void battery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batteryPct = level * 100 / scale;
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** The thermal headroom now (getThermalHeadroom: 1.0 is SEVERE), NaN when the phone has none or it was asked less than a second ago */
    private float headroom() {
        try {
            return pm.getThermalHeadroom(0);
        } catch (RuntimeException e) {
            return Float.NaN;
        }
    }

    /** PEANUTS: an env.txt value for a worker count, kept to the memory plan's counts */
    private String capped(String key, String value, BuildMemory.Plan mem) {
        if (key.equals("DMSH_VK_XLATE_J")) return BuildMemory.cap(value, mem.xlate);
        if (key.equals("J") || key.equals("DMSH_VK_WORKERS") || key.equals("DMSH_VK_PROCS")) return BuildMemory.cap(value, mem.workers);
        return value;
    }

    /** PEANUTS: the memory now, for the step log */
    private String memLine() { String m = BuildMemory.readMeminfo(); return "memory " + BuildMemory.meminfoMb(m, "MemAvailable") + " of " + BuildMemory.meminfoMb(m, "MemTotal") + " MB free"; }

    /** PEANUTS: only logged (a running step cannot change its workers); RUNNING_LOW and up is what comes before a kill */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && worker != null && worker.isAlive()) stepLog(job, "Android reports low memory (trim level " + level + "; " + memLine() + ")");
    }

    private String readings() { return PackPolicy.readings(batteryPct, charging, thermal, headroom()); }

    /** The automatic pause (PackPolicy): below 20 % on battery, or SEVERE heat (until MODERATE or better) unless Go on anyway lifted it */
    private void policy() {
        String r = PackPolicy.reason(batteryPct, charging, thermal, autoReason, heatOverride);
        String was = autoReason;
        autoReason = r;
        Job j = job;
        boolean working = worker != null && worker.isAlive();
        if (r != null && !r.equals(was)) {
            String why = r.equals("battery") ? "battery below " + PackPolicy.BATTERY_MIN + " % and not charging" : "thermal status " + PackPolicy.thermalName(thermal)
                    + (heatOverride ? " (critical or worse pauses even after Go on anyway)" : " (SEVERE or worse pauses; it goes on at MODERATE or better)");
            if (working) stepLog(j, "pausing: " + r + ": " + why + "; " + readings());
            else Log.i(TAG, "pausing: " + r + " (no job running): " + readings());
            if (inTool) engine.interrupt();
        }
        if (r == null && was != null) {
            if (working) stepLog(j, "going on after the " + was + " pause: " + readings());
            else Log.i(TAG, "going on: " + readings());
            synchronized (lock) { lock.notifyAll(); }
        }
    }

    // ---- The job ----

    /**
     * Deletes the split's kept scan results (Job.splitScan, in the app's own storage) of every game but this job's, as the job starts: one job runs at a time,
     * so no other game's split can resume from them before a job of that game starts again (after Forget, or a job whose folder went: a few hundred MB).
     * Decided from the job that runs, read from its job.json, never from the remembered path: shared storage that is briefly unreadable deletes nothing.
     */
    private void dropOtherSplitScans(Job j) {
        String keep = j.titleId();
        if (keep.isEmpty()) return;
        File[] games = j.splitScan(this).getParentFile().listFiles();
        if (games != null) for (File g : games) if (!g.getName().equals(keep)) {
            Log.i(TAG, "deleting the kept split results of another game: " + g);
            Job.deleteTree(g);
        }
    }

    private void work() {
        Job j = job;
        wake.acquire(7 * 3600_000L);
        try {
            // A job of a game this app version does not include (a job.json an APK with its profile made: the launcher remembers it across installs): its steps would
            // run with no profile in tools.zip and stop at an error no one can act on. One sentence instead, and no Retry (the card shows none for FIX_NONE)
            String missing = Profile.missing(j.titleId(), j.name());
            if (missing != null) {
                stepLog(j, "not started: " + missing);
                j.fail(PrepareText.notIncluded(missing));
                save(j, true);
                return;
            }
            dropOtherSplitScans(j);
            if (j.apkChanged()) {
                stepLog(j, "the job's shaders are from another app version: translating them again");
                j.resetChainSteps();
                j.deleteResults(this);
                Job.deleteTree(new File(j.dir(), "lib"));
            }
            ranNow.clear();
            dropMissing(j, "before it goes on");
            j.state("running", null, null);
            save(j, true);
            int i;
            while ((i = j.next()) >= 0) {
                if (!waitWhileAuto(j)) break;
                String s = Job.STEPS[i];
                j.stepState(s, "running");
                int starts = j.start(s);
                save(j, true);
                if (starts > MAX_STARTS) {   // twice ended by Android inside this step (START_STICKY brought it back): stop instead of looping
                    stepLog(j, "stopping: the step was ended by Android " + (starts - 1) + " times before it finished (most likely for memory); " + readings());
                    j.ended(s);
                    j.stepState(s, "pending");
                    j.fail(PrepareText.killed(Job.STEP_TEXT[i], starts - 1, s.equals("rewrite") ? "2.7" : "1.5"));
                    save(j, true);
                    break;
                }
                failure = null;
                String fail = step(j, i);
                j.ended(s);
                if (stopReason != null) { j.stepState(s, "pending"); break; }
                // interrupted by the policy (which may have cleared since): wait if it still holds, then run the step again
                if (fail != null && (autoReason != null || fail.equals("interrupted"))) { j.stepState(s, "pending"); continue; }
                if (fail != null) {
                    j.stepState(s, "pending");
                    stepLog(j, "failed: " + fail);
                    j.fail(failure != null ? failure : new PrepareText.Failure(PrepareText.capital(fail) + ".", "", PrepareText.FIX_RETRY));
                    if (PrepareText.FIX_UPDATE.equals(j.fix())) j.failApk(Job.installed(this));   // this install cannot do it; another one may
                    save(j, true);
                    break;
                }
                j.stepState(s, "done");
                ranNow.add(s);
                if (s.equals("rewrite")) j.codeModules(Outputs.codeModules(j.pack()));   // what the pack step and a later check of this step look for
                save(j, true);
                if (s.equals("split")) Job.deleteTree(j.splitScan(this));   // recorded done: the scan results are no longer needed
            }
            if (j.next() < 0) {
                j.state("done", null, null);
                // the split and the translation results are not needed once the library is in the pack
                try {
                    Outputs.delete(new File(j.dir(), "split"));
                } catch (IOException e) {
                    Log.w(TAG, "split: " + e.getMessage());   // the job is done all the same; a later split begins by deleting it again
                }
                Job.deleteTree(j.splitScan(this));
                Job.deleteTree(j.ueShaders(this));   // PEANUTS: an Unreal game's decoded archives (Job.ueShaders)
                j.deleteResults(this);
                Job.deleteTree(new File(j.dir(), "lib"));
            } else if (stopReason != null) {
                j.state("paused", stopReason, null);
                stepLog(j, "paused: " + stopReason + " (" + Job.reasonText(stopReason) + ")");
            }
            save(j, true);
        } catch (Exception e) {
            Log.e(TAG, "job", e);
            j.fail(PrepareText.crashed(Job.STEP_TEXT[j.current()], String.valueOf(e)));
            save(j, true);
        } finally {
            if (wake.isHeld()) wake.release();
            main.post(this::finished);
        }
    }

    private void finished() {
        Job j = job;
        worker = null;
        publish(j, true);
        stopForeground(STOP_FOREGROUND_DETACH);
        if (j != null) nm.notify(NOTE, note(j));
        stopSelf();
    }

    /** Waits while the policy pauses the job; false when the job is to stop */
    private boolean waitWhileAuto(Job j) throws InterruptedException {
        if (autoReason == null) return stopReason == null;
        if (wake.isHeld()) wake.release();
        String shown = null;
        synchronized (lock) {
            String r;
            while ((r = autoReason) != null && stopReason == null) {
                if (!r.equals(shown)) {   // the card and the notification: why, the readings, and whether Go on anyway applies
                    shown = r;
                    j.state("paused", r, null);
                    j.reasonDetail(readings(), PackPolicy.canOverride(r, thermal));
                    save(j, true);
                }
                lock.wait(30_000);
            }
        }
        if (stopReason != null) return false;
        wake.acquire(7 * 3600_000L);
        j.state("running", null, null);
        save(j, true);
        return true;
    }

    /** The open descriptors of the .nup files used where they are, by stand-in path: opened once per process, kept for its life (the tools duplicate them) */
    private static final java.util.Map<String, android.os.ParcelFileDescriptor> nupFds = new java.util.HashMap<>();

    /** A descriptor of a picked .nup for the tools (PickedNup.descriptor: a plain sentence when the app can no longer open it) */
    private int nupFd(File dump) throws IOException {
        synchronized (nupFds) {
            android.os.ParcelFileDescriptor p = nupFds.get(dump.getAbsolutePath());
            if (p == null) nupFds.put(dump.getAbsolutePath(), p = PickedNup.descriptor(this, dump));
            return p.getFd();
        }
    }

    /** Runs step i; null when it is done, else why it failed */
    private String step(Job j, int i) throws IOException {
        String s = Job.STEPS[i];
        if (s.equals("identify")) {
            DumpCheck.Result r = DumpCheck.check(j.dump(), false, j.tryAnyway());
            if (!r.ok()) {
                failure = new PrepareText.Failure(r.error, r.detail, r.otherBuild ? PrepareText.FIX_ANYWAY : PrepareText.FIX_RETRY);
                return r.error;
            }
            if (!r.titleId.equals(j.titleId()) || r.version != j.version()) return "the dump changed: it is now " + r.titleId + " " + r.version;
            j.stepProgress(s, 1, 1, "identify");
            return null;
        }
        File od = j.dir(), dump = j.dump(), pack = j.pack();
        String v = String.valueOf(j.version());
        // The update's RomFS and main: folders of the dump, or (a .nup) <file>.nup:update/<version>/romfs and …/exefs/main (base/… for version 0), which the
        // tools take where they take the folders (android/NUP.md "Naming a package"); the dump itself goes to pack_android.py as it is, folder or .nup
        String upRomfs = DumpCheck.versionPath(dump, j.version(), "romfs"), upMain = DumpCheck.versionPath(dump, j.version(), "exefs/main");
        File split = new File(od, "split"), splitTmp = new File(od, "split.tmp"), splitScan = j.splitScan(this), xlate = j.results(this), lib = new File(od, "lib");
        String tool;
        String[] argv;
        List<String> selected = null;
        String keysFrom = ShaderLibs.SELECTED;   // where the shaders step's key list comes from, for its log line
        String otherVersionsNote = null;   // PEANUTS: the line for a dump that lacks only other listed versions' shaders (ShaderLibs.otherVersionsLine), for the step's log
        // The tools' arguments: the in-process forms (pack_android.py --in-process: the rewriter in the same Python; shader_split.py with J=1 below: no process pool;
        // vk_android_build.py with the translator and the validator as libraries, XLATE / SPIRV_VAL below; the app's process must not fork, ONDEVICE.md "No subprocesses").
        // The split is resumable by RomFS file: its scan results are kept in the app's own storage (Job.splitScan, DM_SPLIT_RESUME, shader_split.py ScanCache) and a rerun scans only the files
        // that have none; it writes its outputs into split/ at its end, which counts as complete only once its marker is written after them (Outputs: no folder is
        // renamed on shared storage, where MediaProvider may refuse it).
        // The shaders step is resumable by shader: each result is a file in the app's own storage (Job.results, DMSH_VK_RESULTS) and a rerun skips the shaders that have one; it writes the
        // library (lib/v-<DMSH_VK_CHAIN>/) from the results at its end. The pack step copies the library into the pack (--shader-cache)
        switch (s) {
            case "rewrite":
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--code-only", "-j", "1", "--in-process"};
                // PEANUTS begin: a modded variant build's extra argv (--modules/--mod-exefs/--mod-patches), carried on the job as mod_args
                // (Job.modArgs, dev.peanuts.build.ModdedBuild/Prepare.runModded). Empty for a normal job, so this never changes an unmodded argv.
                String modArgs = j.modArgs();
                if (!modArgs.isEmpty()) {
                    String[] extra = modArgs.split("\n");
                    String[] withMods = java.util.Arrays.copyOf(argv, argv.length + extra.length);
                    System.arraycopy(extra, 0, withMods, argv.length, extra.length);
                    argv = withMods;
                }
                // PEANUTS end
                break;
            case "split":
                // only outputs a stopped run may have half written: the split writes them all at its end; what it kept is in Job.splitScan. The marker goes first
                Outputs.begin(split);
                Job.deleteTree(splitTmp);   // left by an older build, which wrote into split.tmp/ and renamed it
                tool = "dm/gfx/tools/shader_split.py";
                argv = new String[] {upRomfs, upMain, new File(split, "pkg").getPath(),
                        new File(split, "shader_map.tsv").getPath(), new File(split, "shader_facts.tsv").getPath(), new File(split, "shader_pairs.tsv").getPath(),
                        new File(split, "pso_pairs.tsv").getPath()};
                break;
            case "shaders": {
                // the split went (a finished job deletes it; an APK update then translates again), or it is not complete (no marker; also an older build's split/): split first
                if (!Outputs.complete(split)) {
                    j.stepState("split", "pending");
                    return "interrupted";
                }
                // Try anyway: the split need not be the profile's list byte for byte; vk_android_build.py still finds each shader by its key, and with DMSH_VK_REQUIRE_ALL=0 (below)
                // writes the library without the shaders this build of the game lacks, counted in one [RED] line of shaders.log
                // PEANUTS begin: a split that read no shader makes an empty library, and a pack that cannot draw: stopped here, with or without Try anyway, unless
                // the profile says the game has no NVN shaders (scripts/pack_android.py empty_split is the same stop on the PC)
                String empty = emptySplit(j, split);
                if (empty != null) return empty;
                // PEANUTS end
                String bad = j.tryAnyway() ? null : splitMatchesProfile(j, split);
                if (bad != null) return bad;
                tool = "dm/gfx/tools/vk_android_build.py";
                argv = new String[] {"--target", "vulkan", new File(split, "pkg").getPath(), lib.getPath()};
                // a profile with a selected set (shader_seen.tsv): only its keys, with their paired pixel shaders, as the Mac's selected build (android/SHADERS.md "Selected shaders")
                selected = selection(j);
                // a split without some of the profile's optional shaders (shader_optional.tsv, splitMatchesProfile): the key list is the map's (or the selection's) other
                // keys, so vk_android_build.py with DMSH_VK_REQUIRE_ALL=1 neither stops on them nor passes a shader that did not build
                List<String> absent = j.tryAnyway() || engine.simulated() ? null : ShaderLibs.absentOptional(new File(PackNative.repo(), "dm/games/" + j.titleId()), new File(split, "shader_map.tsv"), j.version());   // PEANUTS: j.version(): also the shaders only another listed version of the game has (ShaderLibs.otherVersionKeys)
                if (absent == null && !j.tryAnyway() && !engine.simulated()) absent = dev.peanuts.build.RuntimeShaderMap.missingRuntime(   // PEANUTS: translate all file-based shaders before internal runtime capture
                        new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv"), new File(split, "shader_map.tsv"));   // PEANUTS
                if (absent != null) {
                    if (selected == null) selected = new java.util.ArrayList<>(new java.util.TreeSet<>(ShaderLibs.mapKeys(new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv"))));
                    selected.removeAll(absent);
                    keysFrom = "shader_map.tsv without " + absent.size() + " absent shaders";   // PEANUTS: optional or boot-generated outputs
                    // PEANUTS begin: a dump of one of the versions the tables are the union of (game.tsv shader_versions): one plain line, in logcat and in the step's log
                    File prof = new File(PackNative.repo(), "dm/games/" + j.titleId());
                    java.util.Set<String> theirs = ShaderLibs.otherVersionKeys(prof, j.version());
                    theirs.retainAll(absent);
                    if (!theirs.isEmpty()) {
                        String line = ShaderLibs.otherVersionsLine(theirs.size(), ShaderLibs.mapKeys(new File(prof, "shader_map.tsv")).size(), ShaderLibs.otherVersions(prof, j.version()), j.version());
                        Log.i(TAG, "shaders: " + line);
                        otherVersionsNote = line;
                        keysFrom = "shader_map.tsv without " + absent.size() + " shaders of other versions of the game";
                    }
                    // PEANUTS end
                }
                if (selected != null && selected.isEmpty()) return "the profile's " + ShaderLibs.SELECTED + " names none of the shaders of its shader_map.tsv";
                // a split that kept the code of another selection (DM_SPLIT_SELECTED below; an older app's set): split again
                if (!splitHolds(split, selected)) {
                    Outputs.delete(split);
                    j.stepState("split", "pending");
                    return "interrupted";
                }
                if (selected != null) {
                    String[] a = java.util.Arrays.copyOf(argv, argv.length + selected.size());
                    for (int k = 0; k < selected.size(); k++) a[argv.length + k] = selected.get(k);
                    argv = a;
                }
                break;
            }
            case "pack": {
                // the code and the library it takes may have gone since their steps were recorded done (the pack folder went with an uninstalled app, BananaNative
                // issue #3): those steps run again first, instead of pack_android.py --keep-code stopping at a missing module
                List<String> gone = dropMissing(j, "before the pack step");
                if (!gone.isEmpty()) {
                    for (String g : gone) if (ranNow.contains(g)) {   // made in this very run and gone already: something else deletes it, a rerun would loop
                        String why = "the " + g + " step's outputs are gone right after it finished (pack folder " + pack + ")";
                        failure = new PrepareText.Failure("The app's files for " + j.name() + " went missing while it prepared the game. Tap Retry.", why, PrepareText.FIX_RETRY);
                        return why;
                    }
                    return "interrupted";   // work() sets this step pending and goes on with the first step not done
                }
                tool = "scripts/pack_android.py";
                argv = new String[] {dump.getPath(), "--version", v, "--out", pack.getPath(), "--shader-cache", j.shaderCache().getPath(), "--keep-code", "-j", "1", "--in-process",
                        "--made-by", "app " + BuildConfig.VERSION_NAME};   // the pack's own marker: the launcher updates its code by itself when only the tools change (Ondevice.ownPack)
                // PEANUTS begin: a build with code mods packs with the same extra argv as its rewrite step (Job.modArgs): pack.tsv's modules row is then the
                // variant's (the host loads the modules that row names, main.c pack_modules), where it listed the game's own and the added module of
                // Luminescent Platinum for Pokemon Brilliant Diamond (subsdk1, rewritten and in the pack) was never loaded. --keep-code rewrites nothing
                String packModArgs = j.modArgs();
                if (!packModArgs.isEmpty()) {
                    String[] extra = packModArgs.split("\n");
                    String[] withMods = java.util.Arrays.copyOf(argv, argv.length + extra.length);
                    System.arraycopy(extra, 0, withMods, argv.length, extra.length);
                    argv = withMods;
                }
                // PEANUTS end
                break;
            }
            default:
                return "unknown step " + s;
        }
        File logs = new File(od, "logs");
        logs.mkdirs();
        File log = new File(logs, s + ".log");
        String head = "== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + Prefs.buildTag(this) + ", engine " + BuildConfig.NX_PACK_ENGINE + ": "
                + tool + " " + (selected == null ? String.join(" ", argv)
                        : String.join(" ", java.util.Arrays.copyOf(argv, argv.length - selected.size())) + " <" + selected.size() + " keys of " + keysFrom + ">") + "\n";
        Files.write(log.toPath(), head.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (otherVersionsNote != null) Files.write(log.toPath(), ("shaders: " + otherVersionsNote + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);   // PEANUTS
        if (s.equals("shaders")) {   // a failed shader's kept result would fail every Retry the same way: those shaders are translated again (ShaderResults)
            List<ShaderResults.Failed> again = ShaderResults.dropFailed(xlate);
            StringBuilder b = new StringBuilder();
            for (ShaderResults.Failed f : again) b.append("[retry] translating again, it failed before: ").append(f).append('\n');
            if (b.length() > 0) Files.write(log.toPath(), b.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        }
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        // PEANUTS: worker counts for this phone's memory (BuildMemory): 16 GB keeps CpuCores' count and 4 translator calls, an 8 GB phone 2 and 2, each earlier kill of this
        // job's process halves them. ondevice/env.txt BUILD_RAM_MB=<mb> stands in for the phone's total memory (a test of the 8 GB plan on a larger phone)
        String meminfo = BuildMemory.readMeminfo();
        long ramMb = BuildMemory.meminfoMb(meminfo, "MemTotal"), availMb = BuildMemory.meminfoMb(meminfo, "MemAvailable");
        File envRam = new File(od, "env.txt");
        if (envRam.isFile()) for (String l : Files.readAllLines(envRam.toPath(), StandardCharsets.UTF_8)) if (l.startsWith("BUILD_RAM_MB=")) { try { ramMb = Long.parseLong(l.substring(13).trim()); } catch (NumberFormatException e) { /* ignored */ } }
        final BuildMemory.Plan mem = BuildMemory.plan(ramMb, availMb, j.memLevel(), engine.simulated() ? 4 : CpuCores.shaderWorkers());
        final int shaderWorkers = mem.workers;
        workers = Math.min(workers, Math.max(1, mem.workers * 2));
        stepLog(j, s + ": " + mem.why);
        if (j.memLevel() > 0 && (s.equals("split") || s.equals("shaders"))) j.stepNote(s, "The build stopped before (most often for memory); using " + mem.workers + " worker" + (mem.workers == 1 ? "" : "s") + " now");
        PackEngine.setenv("DM_GAME", j.titleId());
        PackEngine.setenv("DM_REWRITE_J", "1");
        PackEngine.setenv("NX_PACK_JOBS", String.valueOf(workers));
        // PEANUTS: the split's J is its process pool, one worker per fast core (CpuCores), the workers nxpack_worker (dm/peanuts/pack_worker, unpacked with the Python home) run
        // through the linker (an app may not execute a file of its own storage) with the APK's libpython3.14.so on their library path: shader_split.py DM_SPLIT_WORKER, split_pool.py.
        // J=1 in env.txt puts the scan back in this process
        String pyLib = engine.simulated() ? null : ((dalvik.system.BaseDexClassLoader) getClassLoader()).findLibrary("python3.14");   // PEANUTS: …/base.apk!/lib/arm64-v8a/libpython3.14.so
        PackEngine.setenv("J", s.equals("split") ? String.valueOf(pyLib == null ? 1 : shaderWorkers) : String.valueOf(workers));   // PEANUTS
        if (s.equals("split")) Log.i(TAG, "split workers: libpython " + pyLib + ", J " + (pyLib == null ? 1 : shaderWorkers));   // PEANUTS
        PackEngine.setenv("DM_SPLIT_WORKER", pyLib == null ? "" : "/system/bin/linker64 " + new File(PackNative.repo().getParentFile(), "python/bin/nxpack_worker").getPath());   // PEANUTS
        PackEngine.setenv("LD_LIBRARY_PATH", pyLib == null ? "" : new File(pyLib).getParent());   // PEANUTS: read by the workers' linker only (this process's read it at its start)
        PackEngine.setenv("DM_SPLIT_RESUME", splitScan.getPath());   // the split's kept scan results (only shader_split.py reads it)
        // a profile with a selected set: the split keeps the code of only those shaders (and their pixel shaders), as the shaders step builds only them; a full
        // package of Pokopia held 4.3 GB and its scan ran out of memory (docs/reference/memory.md, A shader split that keeps every shader's code in memory)
        PackEngine.setenv("DM_SPLIT_SELECTED", s.equals("split") && selection(j) != null ? "1" : "0");
        // the split reads only the files the profile's shader_map.tsv names when the dump's build is the profile's, and keeps that result only when its tables are the
        // profile's; otherwise it scans every file (android/SHADERS.md, Targeted split)
        PackEngine.setenv("DM_SPLIT_TARGETED", "1");
        // PEANUTS: an Unreal game's shader code archives are decoded by the split itself (ue_ondevice.py; the Mac's NSP script did it before the split): into the app's own storage,
        // Oodle-compressed shaders by the game's own decoder in this title's host library, loaded by a worker process (the probe on the Thor: Octopath Traveler 0, main+0x4f77e10)
        PackEngine.setenv("DM_UE_DECODE", j.ueShaders(this).getPath());
        PackEngine.setenv("DM_UE_HOST", pyLib == null ? "" : "/system/bin/linker64 " + new File(PackNative.repo().getParentFile(), "python/bin/nxpack_worker").getPath());
        // PEANUTS: -PnxGenericHost=true ships one libnxhost.so for every title, not libnxhost_<title ID>.so; the worker process
        // (DM_UE_HOST) goes through the same host_preload() that loads the profile, so it needs DM_PROFILE_PATH/DM_PROFILE_SEED too, inherited from this process' env
        PackEngine.setenv("DM_UE_HOST_LIB", BuildConfig.NX_GENERIC_HOST ? "libnxhost.so" : "libnxhost_" + j.titleId() + ".so");
        for (String kv : dev.peanuts.build.GenericProfile.envLines(this, j.titleId())) {
            int eq = kv.indexOf('='); PackEngine.setenv(kv.substring(0, eq), kv.substring(eq + 1));
        }
        PackEngine.setenv("DM_UE_MODULES", pack.getPath());
        PackEngine.setenv("DM_SPLIT_DIRECT", "1");   // a targeted file whose map sources name their containers (#BNSH@ / #BAHS@ / #IDRK@ offsets) is read at those containers only, not whole // PEANUTS
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);   // pack.tsv's packer: no repository on the phone to ask
        PackEngine.setenv("NX_PACK_WORK", od.getPath());
        PackEngine.setenv("NX_PACK_VERSION", v);
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        // A .nup used where it is (PickedNup): the tools read its stand-in path through a descriptor this process holds (dm/tools/nup.py, DM_NUP_FD=<fd>:<path>);
        // empty for any other dump
        String nupFd = "";
        if (Nup.picked(dump)) {
            try {
                nupFd = nupFd(dump) + ":" + dump.getAbsolutePath();
            } catch (IOException e) {   // moved, deleted, or its permission taken back: the user picks it again (its card), then Retry
                failure = new PrepareText.Failure(e.getMessage(), "", PrepareText.FIX_RETRY);
                return e.getMessage();
            }
        }
        PackEngine.setenv("DM_NUP_FD", nupFd);
        // vk_android_build.py: the per-shader results (kept until the job is done: a rerun resumes from them), the translator and SPIRV-Tools from the APK's lib/
        // (bare names: the linker finds them in the APK, as System.loadLibrary does; ctypes loads them in this process)
        PackEngine.setenv("DMSH_VK_RESULTS", xlate.getPath());
        // the delta update: entries of the pack's older libraries that the profile's manifest has unchanged are kept, only the rest translated (android/SHADERS.md#delta-updates)
        PackEngine.setenv("DMSH_VK_DELTA_FROM", new File(pack, "shaders").getPath());
        PackEngine.setenv("XLATE", "libdmsh_xlate.so");
        PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");
        // a library without every shader is a failed step, never a finished one; with Try anyway (the player's choice, warned that parts may not show) it is written
        PackEngine.setenv("DMSH_VK_REQUIRE_ALL", j.tryAnyway() ? "0" : "1");
        PackEngine.setenv("DMSH_VK_ZSTD_LEVEL", "6");   // PEANUTS: the library's modules at zstd 6, not 19: 23x faster for a library 19 % larger (vk_android_build.py ZSTD_LEVEL); level 19 was half of the shaders step
        PackEngine.setenv("DMSH_VK_XLATE_J", String.valueOf(Math.min(mem.xlate, 4)));   // PEANUTS: BuildMemory (was 2, then Prepare's env.txt line 4)
        //       // two translator calls at once: more threads of one process add little on the phone (android/ONDEVICE.md, The translator on the phone); the env file below can change it
        // worker interpreters (sub-interpreters of this process, each with its own GIL: the Python half no longer runs on one core), one a big or mid core, at most 4
        // (CpuCores; android/SHADERS.md#build); the library is the same for any count. J above is then the threads of all the workers together
        PackEngine.setenv("DMSH_VK_PROCS", String.valueOf(shaderWorkers));   // PEANUTS
        // PEANUTS: worker processes instead (the split's nxpack_worker through the linker, LD_LIBRARY_PATH above; each loads libdmsh_xlate.so and SPIRV-Tools itself):
        // the Python half around the translator ran under one GIL, ~0.4 core (vk_android_build.py DMSH_VK_WORKER, split_pool.py). DMSH_VK_WORKERS=1 in env.txt turns them off
        PackEngine.setenv("DMSH_VK_WORKER", pyLib == null ? "" : "/system/bin/linker64 " + new File(PackNative.repo().getParentFile(), "python/bin/nxpack_worker").getPath());   // PEANUTS
        PackEngine.setenv("DMSH_VK_WORKERS", String.valueOf(pyLib == null ? 1 : shaderWorkers));   // PEANUTS
        // Tests: ondevice/env.txt, KEY=VALUE lines, overrides any of the above for the tools (e.g. DMSH_VK_XLATE_J=6 to compare the translator's threads)
        File envFile = new File(od, "env.txt");
        if (envFile.isFile()) for (String l : Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
            int eq = l.indexOf('=');
            if (eq > 0 && !l.startsWith("#")) { PackEngine.setenv(l.substring(0, eq).trim(), capped(l.substring(0, eq).trim(), l.substring(eq + 1).trim(), mem));  /* PEANUTS: an env.txt count never goes over the memory plan */ Files.write(log.toPath(), ("[env.txt] " + l.trim() + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); }
        }
        if (s.equals("shaders")) j.shadersMissing(0, 0);   // the step's own count (Tail), not an earlier run's
        Tail tail = new Tail(j, s, log);
        tail.start();
        int code;
        inTool = true;
        try {
            if (autoReason != null || stopReason != null) return "interrupted";
            code = engine.run(tool, argv, log.getPath());
        } finally {
            inTool = false;
            tail.finish();
        }
        Log.i(TAG, s + ": exit " + code);
        if (code == PackEngine.INTERRUPTED) return "interrupted";
        if (code != 0) {
            String why = tail.reason();   // the last [RED] line, or a traceback's last line after it (PrepareText.StopReason)
            failure = PrepareText.toolStopped(Job.STEP_TEXT[i], code, why, log.getPath());
            return s + " stopped with code " + code + (why != null ? ": " + why.replaceFirst("^\\[RED\\] ", "") : "") + " (log: " + log + ")";
        }
        if (s.equals("split")) {
            Outputs.finish(split);   // every output synced, then the marker: a reboot after the step is recorded done cannot leave cut tables accepted in split/
            // the scan results go only once job.json records the split done (work()): a kill before that resumes from them instead of scanning everything again
        }
        long t = j.stepTotal(i);
        j.stepProgress(s, t > 0 ? t : 1, t > 0 ? t : 1, "");
        return null;
    }

    /** The steps that finished in this run of the job (work()): a step whose outputs are already gone again is not run a third time */
    private final java.util.Set<String> ranNow = new java.util.HashSet<>();

    /** Job.dropMissing with a line in the step's log for each step that runs again; → those steps */
    private List<String> dropMissing(Job j, String when) {
        File cache = j.shaderCache();   // before the check: a library the pack held that went is no longer the job's
        List<String> gone = j.dropMissing();
        if (!gone.isEmpty()) {
            stepLog(j, "outputs missing " + when + ": " + String.join(", ", gone) + " recorded done, but "
                    + (gone.contains("rewrite") ? "the pack folder " + j.pack() + " has no complete " + String.join(", ", j.codeModules()) + " (patched.bin and its key)" : "")
                    + (gone.contains("rewrite") && gone.contains("shaders") ? " and " : "")
                    + (gone.contains("shaders") ? "no shader library v-" + BuildConfig.NX_DMSH_VK_CHAIN + " in " + cache : "")
                    + ": running " + (gone.size() == 1 ? "it" : "them") + " again (finished shader results are kept)");
            save(j, true);
        }
        return gone;
    }

    /** The profile's selected shader keys (ShaderLibs.selection), null when it has no selected set (or the engine is simulated: no repository) */
    private List<String> selection(Job j) throws IOException {
        if (engine.simulated()) return null;
        return ShaderLibs.selection(new File(PackNative.repo(), "dm/games/" + j.titleId()));
    }

    /**
     * null when the split found the shaders the app's profile lists (its shader_map.tsv byte for byte, as a Mac split of the same dump does), else what to do.
     * vk_android_build.py builds the keys of the profile's map from the split's package: another map means a dump with other shaders (modified, or incomplete).
     */
    private static java.util.List<String> tableRows(byte[] b) {   // PEANUTS: a table's rows without its comment lines
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : new String(b, StandardCharsets.UTF_8).split("\n")) if (!l.startsWith("#")) out.add(l);
        return out;
    }

    private String splitMatchesProfile(Job j, File split) {
        if (engine.simulated()) return null;
        File have = new File(split, "shader_map.tsv");
        try {
            File want = new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv");
            if (tableRows(Files.readAllBytes(want.toPath())).equals(tableRows(Files.readAllBytes(have.toPath())))) return null;   // PEANUTS: without the comment lines (shader_split.py tables_data, upstream's since Banana Native v0.3.22; table_rows before: the header prose changed with the tool's wording on 2026-10-28, and 86 profiles carry the older prose)
            if (ShaderLibs.absentOptional(want.getParentFile(), have, j.version()) != null) return null;   // a registered build that lacks only the profile's optional shaders (the shaders step leaves them out); PEANUTS: j.version(): or only what another listed version of the game has, checked before any message below
            if (dev.peanuts.build.RuntimeShaderMap.missingRuntime(want, have) != null) return null;   // PEANUTS: boot-generated shaders are completed by Build, not missing DLC
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            // the app's own list is missing (work() refuses a game this APK does not include, so this is an app built without the profile's tables) or the split's
            String why = "cannot compare the dump's shaders with the app's list: " + e.getMessage();
            failure = new PrepareText.Failure("The app could not compare your dump's shaders with its list for " + j.name() + ". Tap Retry. If it stops again, Details has the error.",
                    why, PrepareText.FIX_RETRY);
            return why;
        }
        Lack lack = lack(j, have);   // PEANUTS: what the split lacks and whether the dump has those files, read before the split's tables go
        try {
            Outputs.delete(split);   // a Retry splits again, from the start
        } catch (IOException e) {
            Log.w(TAG, "split: " + e.getMessage());   // its step is pending: the next run's Outputs.begin tries again
        }
        Job.deleteTree(j.splitScan(this));
        j.stepState("split", "pending");
        // a file the split could not read is not the dump's fault when the phone ran out of memory reading it: say what happened (its "unreadable:" lines)
        List<String> skipped = splitSkipped(new File(new File(j.dir(), "logs"), "split.log"));
        String mem = null;
        for (String w : skipped) if (w.contains("MemoryError")) { mem = w; break; }
        String log = "Log: " + new File(new File(j.dir(), "logs"), "split.log");
        if (mem != null) {
            failure = new PrepareText.Failure("The phone ran out of memory while finding the shaders. Close other apps, then tap Retry.", "Left out: " + mem + "\n" + log, PrepareText.FIX_RETRY);
            return "the phone ran out of memory while finding the shaders in " + mem.replaceFirst(":.*", "") + " (its shaders were left out). Close other apps, then Retry";
        }
        // PEANUTS begin: files compressed with the game's own decoder that the app could not run are the app's limit, not a damaged dump (Pokemon Legends: Arceus's 678 GFPAK archives)
        String dec = null;
        for (String w : skipped) if (w.contains("own decoder") || w.contains("host failed on a")) { dec = w; break; }
        if (dec != null) {
            failure = new PrepareText.Failure("The app could not decode " + skipped.size() + (skipped.size() == 1 ? " compressed file" : " compressed files") + " of this game. "
                    + "Your dump is not the cause: this app version cannot build this game on the device yet. Share the log (Details).",
                    "Not decoded: " + String.join("\n", skipped) + "\n" + log, PrepareText.FIX_RETRY);
            return "the split could not decode " + skipped.size() + (skipped.size() == 1 ? " compressed file" : " compressed files") + " of " + j.name() + " (" + dec
                    + "): the app's limit, not the dump's";
        }
        // PEANUTS end
        if (!skipped.isEmpty()) {
            failure = new PrepareText.Failure("The app could not read " + skipped.size() + (skipped.size() == 1 ? " file" : " files") + " of your dump. The file may be damaged: "
                    + "dump the game again, then tap Retry.", "Not read: " + String.join("\n", skipped) + "\n" + log, PrepareText.FIX_RETRY);
            return "the split could not read " + skipped.size() + (skipped.size() == 1 ? " file" : " files") + " of the dump (" + skipped.get(0)
                    + "), so its shaders are not the ones this app knows for " + j.name() + ". The file may be damaged: dump the game again, then Retry";
        }
        // PEANUTS begin: say how many shaders are missing and name one; when every one of them comes from a file the import does not have, the import has no
        // add-on content and the tables are of this one version, DLC that was not imported is named as one possible reason (ShaderLibs.addonCase, the rule of
        // scripts/pack_android.py lacking_shaders); an import that could not be asked gets the old words and the DLC as a possible reason
        String ver = Profile.versionName(j.version()), differs = "The split's " + have.getName() + " differs from the app's dm/games/" + j.titleId() + "/shader_map.tsv";
        if (lack == null) {
            failure = PrepareText.shadersDifferUnread(j.name(), ver, differs + ".\n" + log);
            return "the dump's shaders are not the ones this app knows for " + j.name() + " (" + ver + ", " + have.getName() + " differs). The dump may be incomplete or "
                    + "modified: dump the game again, then Retry. When the game has add-on content (DLC), it may not have been part of the import";
        }
        // PEANUTS: said only when nothing else is known to explain the missing shaders. With a union of versions (shader_versions), or a dump of a version the
        // tables were not made from, a missing file is more likely another version's; a missing shader whose file is there differs for another reason. No game
        // is known that needs this message: the case it was written for was another version's shaders, not DLC
        if (ShaderLibs.addonCase(lack.missing, lack.absent, lack.addons, lack.versions, j.version())) {
            boolean nup = Nup.isNup(j.dump());
            failure = PrepareText.addonNotImported(j.name(), lack.absent, lack.total, lack.absentFile, nup, differs + ": " + lack.missing + " of its " + lack.total
                    + " shaders are missing, all of them from files that are not in " + j.dump() + " (" + lack.absentFile + "), which has no add-on content.\n" + log);
            String what = nup ? "import" : "dump";
            return "the dump's shaders lack " + lack.absent + " of the " + lack.total + " this app knows for " + j.name() + " (" + ver + "): all of them come from files the "
                    + what + " does not have (e.g. " + lack.absentFile + "), and it has no add-on content. The " + what + " is incomplete; one possible reason is that the game's "
                    + "add-on content (DLC) was not part of the " + what + ". If you have DLC for this game: " + (nup ? "set the DLC folder in Settings, then Import again in the "
                    + "game's sheet (" + j.dump() + " is made again)" : "dump the game again with its DLC, then Retry") + ". Try anyway builds without them";
        }
        if (lack.missing > 0) {
            String from = lack.file.isEmpty() ? "" : " from " + lack.file;
            failure = PrepareText.shadersDiffer(j.name(), ver, lack.missing, lack.total, lack.file.isEmpty() ? lack.key : lack.file, differs + ": " + lack.missing + " of its "
                    + lack.total + " shaders are missing (" + lack.key + from + "; " + lack.absent + " from files that are not in the dump, which has " + lack.addons
                    + " add-on content package(s)).\n" + log);
            return "the dump's shaders are not the ones this app knows for " + j.name() + " (" + ver + ", " + have.getName() + " differs: " + lack.missing + " of "
                    + lack.total + " missing, e.g. " + lack.key + from + "). The dump may be incomplete or modified: dump the game again, then Retry";
        }
        // PEANUTS end
        failure = PrepareText.shadersDiffer(j.name(), Profile.versionName(j.version()), "The split's " + have.getName() + " differs from the app's dm/games/" + j.titleId()
                + "/shader_map.tsv.\n" + log);
        return "the dump's shaders are not the ones this app knows for " + j.name() + " (" + Profile.versionName(j.version()) + ", " + have.getName()
                + " differs). The dump may be incomplete or modified: dump the game again, then Retry";
    }

    // PEANUTS begin: a split that read no shader
    /** null when the split's map has a shader, or the profile says the game has none (ShaderLibs.noNvnShaders); else why the job stops, with the split taken away so
     *  that a Retry (or the next app version) splits again */
    private String emptySplit(Job j, File split) {
        if (engine.simulated()) return null;
        try {
            if (!ShaderLibs.mapKeys(new File(split, "shader_map.tsv")).isEmpty() || ShaderLibs.noNvnShaders(new File(PackNative.repo(), "dm/games/" + j.titleId()))) return null;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return null;   // the split's map does not read: splitMatchesProfile says so
        }
        try {
            Outputs.delete(split);
        } catch (IOException e) {
            Log.w(TAG, "split: " + e.getMessage());
        }
        Job.deleteTree(j.splitScan(this));
        j.stepState("split", "pending");
        failure = PrepareText.noShadersRead(j.name(), "The split read no shader from " + j.dump() + ": the pack would have an empty shader library. A game that draws "
                + "through the Vulkan or OpenGL layer says \"no NVN shaders\" in its profile's shader_note (dm/games/" + j.titleId() + "/game.tsv).\nLog: "
                + new File(new File(j.dir(), "logs"), "split.log"));
        return "the split read no shader from the dump of " + j.name() + ": the pack would have an empty shader library and the game could not draw. The dump is "
                + "incomplete, or this app version does not read this game's shaders yet";
    }
    // PEANUTS end

    // PEANUTS begin: the evidence for splitMatchesProfile's message (ShaderLibs.missingSources, the dump's own file list and its add-on packages)
    /** What a split lacks of the profile's map: missing of total keys (key, file: the first one and its RomFS path, "" when it has none), absent of them from files the
     *  dump's RomFS does not have (absentFile: one), and how many add-on packages (DLC) the dump holds */
    private static final class Lack {
        int missing, total, absent, addons;   // missing: beyond what a dump of this version may lack (ShaderLibs.mayLack), as the PC counts them
        List<String> versions;   // the versions the profile's tables are the union of (game.tsv shader_versions)
        String key = "", file = "", absentFile = "";
    }

    /** null when the tables or the dump cannot be read (the caller then says only what it knows) */
    private static Lack lack(Job j, File splitMap) {
        try {
            File want = new File(PackNative.repo(), "dm/games/" + j.titleId() + "/shader_map.tsv");
            Lack l = new Lack();
            l.total = ShaderLibs.mapKeys(want).size();
            l.versions = ShaderLibs.shaderVersions(want.getParentFile());
            java.util.Set<String> may = ShaderLibs.mayLack(want.getParentFile(), j.version());
            java.util.Map<String, Boolean> there = new java.util.HashMap<>();   // each file is looked up once
            for (java.util.Map.Entry<String, String> e : ShaderLibs.missingSources(want, splitMap).entrySet()) {
                String f = e.getValue();
                if (may.contains(e.getKey())) continue;
                if (l.missing++ == 0) { l.key = e.getKey(); l.file = f; }
                if (f.isEmpty()) continue;
                Boolean has = there.get(f);
                if (has == null) there.put(f, has = there.size() >= 512 || inRomfs(j.dump(), j.version(), f));   // a dump of another build lacks thousands: the rest count as there
                if (!has && l.absent++ == 0) l.absentFile = f;
            }
            File dump = j.dump();
            String[] dlc = new File(dump, "dlc").list();
            l.addons = Nup.isNup(dump) ? Nup.open(dump).of(Nup.ADDON).size() : dlc == null ? 0 : dlc.length;
            return l;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "split: what it lacks: " + e);
            return null;
        }
    }

    /** Whether the version's RomFS (the one the split read: DumpCheck.versionPath) has the file a source's path names; the path may go on into a container, so its
     *  longest part that exists decides: a file is there, a folder (or nothing) is not */
    private static boolean inRomfs(File dump, long version, String path) throws IOException {
        String romfs = DumpCheck.versionPath(dump, version, "romfs");
        Nup n = Nup.isNup(dump) ? Nup.open(dump) : null;
        Nup.Pkg p = n == null ? null : n.find(Nup.parse(romfs).selector);
        if (n != null && p == null) throw new IOException(dump.getName() + " has no package for " + romfs);
        for (String s = path; !s.isEmpty(); s = s.substring(0, s.lastIndexOf('/'))) {
            if (n != null) { Nup.Node node = n.lookup(p, "romfs" + s); if (node != null) return !node.dir; }
            else { File f = new File(romfs + s); if (f.exists()) return f.isFile(); }
        }
        return false;
    }
    // PEANUTS end

    /** The files the last split left out and why: its "unreadable: <file>: <reason>" lines after the log's last "== " header (shader_split.py); empty when none or no log */
    static List<String> splitSkipped(File log) {
        List<String> out = new java.util.ArrayList<>();
        try {
            for (String l : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                if (l.startsWith("== ")) out.clear();
                else if (l.startsWith("unreadable: ")) out.add(l.substring(12).trim());
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "split log: " + e);
        }
        return out;
    }

    /** Whether the split's package holds the code of every selected shader: a package written with DM_SPLIT_SELECTED lists its keys in pkg/selected.txt; a full one has none */
    static boolean splitHolds(File split, List<String> selected) {
        File f = new File(new File(split, "pkg"), "selected.txt");
        if (!f.isFile()) return true;
        if (selected == null) return false;
        try {
            java.util.Set<String> have = new java.util.HashSet<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            return have.containsAll(selected);
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads the step's log as it grows: progress lines into the job, the reason for a failure's message (the last [RED] line, or a traceback's: PrepareText.StopReason) */
    private final class Tail extends Thread {
        private final Job j;
        private final String step;
        private final File log;
        private volatile boolean done;
        private long pos;
        private final StringBuilder partial = new StringBuilder();
        private final PrepareText.StopReason stop = new PrepareText.StopReason();

        Tail(Job j, String step, File log) {
            super("nxpack-tail");
            this.j = j;
            this.step = step;
            this.log = log;
            pos = log.length();
        }

        @Override public void run() {
            while (!done) {
                read();
                SystemClock.sleep(300);
            }
            read();
        }

        void finish() {
            done = true;
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void read() {
            try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
                long len = f.length();
                if (len <= pos) return;
                f.seek(pos);
                byte[] b = new byte[(int) Math.min(len - pos, 1 << 20)];
                f.readFully(b);
                pos += b.length;
                partial.append(new String(b, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            int nl;
            boolean changed = false;
            while ((nl = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, nl).trim();
                partial.delete(0, nl + 1);
                Matcher m = PROGRESS.matcher(line), st = STAGE.matcher(line);
                if (m.find()) {
                    j.stepProgress(step, Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), m.group(3));
                    changed = true;
                } else if (st.find()) {
                    j.stepNote(step, st.group(1).trim());
                    changed = true;
                } else if (stop.line(line)) {
                    long[] miss = PrepareText.missingShaders(line);   // Try anyway: shaders this game version lacks, said once the game is ready (Ondevice.addGame)
                    if (miss != null) { j.shadersMissing(miss[0], miss[1]); changed = true; }
                }
            }
            if (changed) save(j, false);
        }

        /** Why the step stopped, read once the tool is done (finish): null when its log gave no reason */
        String reason() { return stop.reason; }
    }

    // ---- State out: job.json, the notification, the launcher ----

    /** Writes job.json (at most every 2 s unless forced) and tells the notification and the launcher */
    private void save(Job j, boolean force) {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (force || now - lastSave >= 2000) {
                lastSave = now;
                try {
                    j.save();
                } catch (IOException e) {
                    Log.e(TAG, "job.json: " + e);
                }
            }
        }
        main.post(() -> publish(j, force));
    }

    private void publish(Job j, boolean force) {
        if (j == null) return;
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastSend >= 250) {
            lastSend = now;
            Message m = Message.obtain(null, MSG_STATE);
            Bundle b = new Bundle();
            b.putString("job", j.json());
            b.putString("file", j.file.getPath());
            b.putBoolean("alive", worker != null);
            m.setData(b);
            for (Messenger c : clients) {
                try {
                    c.send(Message.obtain(m));
                } catch (RemoteException e) {
                    clients.remove(c);
                }
            }
        }
        if (worker != null && (force || now - lastNote >= 1000)) {
            lastNote = now;
            nm.notify(NOTE, note(j));
        }
    }

    private boolean handle(Message m) {
        switch (m.what) {
            case MSG_REGISTER:
                if (m.replyTo != null) {
                    clients.add(m.replyTo);
                    if (job != null) publish(job, true);
                }
                return true;
            case MSG_UNREGISTER:
                clients.remove(m.replyTo);
                return true;
            default:
                return false;
        }
    }

    private Notification note(Job j) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_banana_mono).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        String name = j == null ? "a game" : j.name();
        String sim = engine.simulated() ? " (simulated)" : "";
        if (j == null) return b.setContentTitle("Preparing " + name + sim).setContentText("Starting…").build();
        String state = j.state();
        int pct = (int) Math.round(j.overall() * 100);
        int i = j.current();
        if (state.equals("done")) return b.setContentTitle(name + " is ready to play" + sim).setContentText("Open " + getString(R.string.app_name) + " and tap Play.")   /* PEANUTS: the app's own name */.setAutoCancel(true).build();
        boolean running = state.equals("running") && worker != null;
        boolean waiting = state.equals("paused") && worker != null;   // the policy's pause: it goes on by itself
        b.setContentTitle((running ? "Preparing " : waiting ? "Waiting: " : state.equals("failed") ? "Could not prepare " : "Paused: ") + name + sim);
        Intent pause = new Intent(this, PackService.class).setAction(ACTION_PAUSE).putExtra(EXTRA_FROM, "notification");
        Notification.Action pauseAction = new Notification.Action.Builder(null, "Pause",
                PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
        if (waiting) {
            String why = Job.reasonText(j.reason()) + (j.reasonDetail().isEmpty() ? "" : "\n" + j.reasonDetail());
            b.setContentText(Job.reasonText(j.reason())).setStyle(new Notification.BigTextStyle().bigText(j.stepText() + " · " + pct + " %\n" + why))
                    .setSubText("Step " + (i + 1) + " of " + Job.STEPS.length + " · " + pct + " %").setOngoing(true).addAction(pauseAction);
            if (j.canGoOn()) {
                Intent goOn = new Intent(this, PackService.class).setAction(ACTION_GO_ON).putExtra(EXTRA_FROM, "notification");
                b.addAction(new Notification.Action.Builder(null, "Go on anyway",
                        PendingIntent.getService(this, 3, goOn, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
            }
            return b.build();
        }
        if (running) {
            long d = j.stepDone(i), t = j.stepTotal(i);
            if (t > 1) b.setProgress((int) Math.min(t, Integer.MAX_VALUE), (int) Math.min(d, t), false);
            else b.setProgress(100, pct, false);
            b.setContentText(j.stepText() + j.quietText()).setSubText("Step " + (i + 1) + " of " + Job.STEPS.length + " · " + pct + " %").setOngoing(true).addAction(pauseAction);
        } else {
            String why = state.equals("failed") ? j.error() : Job.reasonText(j.reason());
            b.setContentText(j.stepText() + " · " + pct + " %").setStyle(new Notification.BigTextStyle().bigText(j.stepText() + " · " + pct + " %\n" + why)).setOngoing(false);
            Intent resume = new Intent(this, PackService.class).setAction(ACTION_START).putExtra(EXTRA_JOB, j.file.getPath());
            b.addAction(new Notification.Action.Builder(null, state.equals("failed") ? "Retry" : "Resume",
                    PendingIntent.getForegroundService(this, 2, resume, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }
}
