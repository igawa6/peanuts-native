package dev.peanuts.build;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The library's Build button (dev.peanuts.ui.BuildPort → here): {@link #start} hands a {@link BuildRequest} to {@link BuildService} (a foreground service in this
 * process) and keeps the sheet's {@link BuildListener} for it; the service reports through {@link #progress}/{@link #finish}, delivered on the main thread. The
 * listener is held weakly in effect: {@link #detach} drops it when the sheet goes away (the notification still shows the build), {@link #attach} binds a new one
 * to a running build (the sheet reopened). One build runs at a time; one asked while another runs or waits goes into the build queue ({@link BuildQueue}, issue
 * #348) and starts when its turn comes. The ids are the queue's, kept in its file, so a screen made after the app's process ended still finds its build.
 * {@link #watch} tells the library of every change of the queue (a game added, started, ended, removed, a wait).
 */
public final class BuildPipeline {
    private static final String TAG = Prepare.TAG;
    private static final Map<Integer, BuildListener> LISTENERS = new ConcurrentHashMap<>();
    private static final List<Runnable> WATCHERS = new CopyOnWriteArrayList<>();
    private static volatile BuildQueue queue;
    private static volatile String waitReason;
    private static volatile int waitingFor;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int running;
    private static volatile String lastStep = "", lastDetail = "";
    private static volatile int lastPercent;
    // What only a listener knew before: which game the build is for, when it started and how the last one ended (the control interface's status file reads them)
    private static volatile String title = "", name = "", endMessage = "", endCode = "";
    private static volatile long startedAt, endedAt;
    private static volatile Boolean endOk;

    private BuildPipeline() {}

    /** The running build, or the last one of this process, for dev.peanuts.ctl's status file; null when none was started */
    public static java.util.Map<String, Object> snapshot() {
        if (startedAt == 0) return null;
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        boolean runs = running != 0;
        m.put("running", runs);
        m.put("title_id", title);
        m.put("name", name);
        m.put("step", lastStep);
        m.put("percent", lastPercent);
        m.put("detail", lastDetail);
        m.put("started", startedAt);
        if (!runs && endOk != null) {
            m.put("ended", endedAt);
            m.put("failed", !endOk);
            if (!endOk) { m.put("error", endMessage); m.put("code", endCode); }
        }
        return m;
    }

    /** The queue for dev.peanuts.ctl's status file: each game with its state and place, and what the queue waits for; null before the queue was read */
    public static Map<String, Object> queueSnapshot() {
        BuildQueue q = queue;
        if (q == null) return null;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (BuildQueue.Item i : q.items()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("id", i.id);
            r.put("title_id", i.titleId());
            r.put("name", i.name());
            r.put("state", i.state);
            r.put("place", q.place(i));
            r.put("tries", i.tries);
            if (i.stopped()) { r.put("code", i.code); r.put("error", i.message); r.put("step", i.step); }
            rows.add(r);
        }
        m.put("items", rows);
        m.put("total", q.total());
        m.put("waiting_for", waitReason);
        return m;
    }

    /** The queue of this process, read from its file once (a "running" game in it is one the last process did not live to finish: it goes first again) */
    public static BuildQueue queue(Context c) {
        BuildQueue q = queue;
        if (q != null) return q;
        synchronized (BuildPipeline.class) {
            if (queue == null) queue = new BuildQueue(new File(c.getApplicationContext().getFilesDir(), BuildQueue.FILE));
            return queue;
        }
    }

    /** The queue when it was read already, else null (for a caller without a Context) */
    public static BuildQueue queueNow() { return queue; }

    /** Called on the main thread whenever the queue changes (one watcher a screen that shows it; the library's is registered once a process) */
    public static void watch(Runnable r) { if (r != null && !WATCHERS.contains(r)) WATCHERS.add(r); }

    static void changed() {
        // changes that come before the watchers ran are told once: each watcher reads the queue as it is then (Home is drawn again once, not once a change)
        if (TELL.compareAndSet(false, true)) MAIN.post(() -> { TELL.set(false); for (Runnable r : WATCHERS) r.run(); });
        dev.peanuts.ctl.Ctl.progress();
    }
    private static final java.util.concurrent.atomic.AtomicBoolean TELL = new java.util.concurrent.atomic.AtomicBoolean();

    /** Whether a build runs or one waits in the queue: another build (with mods, upstream's own Prepare) waits for this to end */
    public static boolean busy() {
        BuildQueue q = queue;
        return running != 0 || q != null && (q.running() != null || q.next() != null && !q.held());   // a paused queue runs nothing: it holds nobody up
    }

    /** Whether a game builds or waits in the queue, paused or not (the sheet's Build then adds to it) */
    public static boolean queueing() {
        BuildQueue q = queue;
        return running != 0 || q != null && q.active();
    }

    /** What the queue waits for before its next game (BuildQueue.WAIT_*), null when it does not wait */
    public static String waitReason() { return waitReason; }

    /** The game the queue waits to start, 0 when it does not wait */
    public static int waitingFor() { return waitingFor; }

    /** The service: the queue waits for reason before the game of id (null, 0: it waits no more) */
    static void waiting(String reason, int id) {
        String was = waitReason;
        waitReason = reason;
        waitingFor = reason == null ? 0 : id;
        if (reason != null && running == id) running = 0;   // the game start() took for started waits first: it is no build that runs
        if (was == null ? reason != null : !was.equals(reason)) changed();
    }

    /** The service: a game of the queue starts now (its listener, if a screen holds one, hears its progress) */
    static void started(int id, String titleId, String gameName) {
        running = id;
        lastStep = "import"; lastPercent = 0; lastDetail = "Starting";
        title = titleId == null ? "" : titleId; name = gameName == null ? "" : gameName; startedAt = System.currentTimeMillis(); endOk = null;
        changed();
    }

    /** The service could not come to the front (Android refused it in the background): the queue keeps its games, the app's next start goes on with them */
    static void notStarted() {
        running = 0;
        changed();
    }

    /** The app's screen came up: a queue that a killed process left (or that Android did not let start) goes on */
    public static void resume(Context ctx) {
        BuildQueue q = queue(ctx);
        if (BuildService.alive || q.next() == null || q.held()) return;
        Log.i(TAG, "queue: going on with " + q.waiting().size() + " waiting game(s)");
        try { ctx.getApplicationContext().startForegroundService(BuildService.run(ctx)); }
        catch (RuntimeException e) { Log.w(TAG, "queue: the build service did not start: " + e); }
    }

    /** A waiting game leaves the queue, or a stopped game's card goes (Remove, Dismiss); false when it runs or is not there */
    public static boolean remove(Context ctx, int id) {
        boolean ok = queue(ctx).remove(id);
        if (ok) changed();
        return ok;
    }

    /** Pause (the control interface's job pause): the game that builds stops between steps and goes on first at Resume; the queue keeps its games */
    public static void pause(Context ctx) {
        try { ctx.startService(new android.content.Intent(ctx, BuildService.class).setAction(BuildService.ACTION_PAUSE)); }
        catch (RuntimeException e) { Log.w(TAG, "pause: " + e); }
    }

    /** Resume after a pause: the queue goes on with its first game */
    public static void release(Context ctx) {
        BuildQueue q = queue(ctx);
        q.release();
        changed();
        if (q.next() == null) return;
        try { ctx.getApplicationContext().startForegroundService(BuildService.run(ctx)); }   // a service still alive takes it as well: its worker, or a new one
        catch (RuntimeException e) { Log.w(TAG, "queue: the build service did not start: " + e); }
    }

    /**
     * A paused queue whose first game's job runs again in the packer (the Resume of upstream's Prepare card on Home, which starts the packer itself): the queue
     * goes on as well, and its build follows that job (Prepare resumes a running job). Home calls it as it draws, and every 30 s
     */
    public static void followPacker(Context c) {
        BuildQueue q = queue;
        BuildQueue.Item first = q == null || !q.held() ? null : q.next();
        if (first == null) return;
        try {
            File jf = Upstream.jobRemembered(c);
            if (jf == null || !jf.isFile()) return;
            Object j = Upstream.jobRead(jf);
            if (!first.titleId().equalsIgnoreCase(Upstream.jobTitle(j))) return;
            String st = Upstream.jobState(j);
            // it runs again, or it was run to its end after the pause began (a short job ends before the next look)
            boolean on = "running".equals(st) && dev.peanuts.GameReturn.processAlive(c, ":pack", -1) || "done".equals(st) && Upstream.jobUpdated(j) > q.heldAt();
            if (!on) return;
        } catch (ReflectiveOperationException | RuntimeException e) { return; }
        Log.i(TAG, "queue: " + first.name() + "'s job runs again in the packer: the paused queue goes on");
        release(c);
    }

    /** Whether a built game's pack fits this app as it is, as its build would find (Build all that need it); off the main thread: it reads the pack and the game's code */
    public static boolean upToDate(Context c, File game, String titleId, File packDir) {
        return new Prepare(c).upToDate(packDir, game, titleId);
    }

    /** Whether the queue is paused (it keeps its games and starts none until Resume) */
    public static boolean held() {
        BuildQueue q = queue;
        return q != null && q.held();
    }

    /** Cancel all: the game that builds stops and the waiting ones leave the queue */
    public static void cancelAll(Context ctx) {
        try { ctx.startService(new android.content.Intent(ctx, BuildService.class).setAction(BuildService.ACTION_CANCEL_ALL)); }
        catch (RuntimeException e) {
            Log.w(TAG, "cancel all: " + e);
            if (queue(ctx).removeWaiting() > 0) changed();
        }
    }

    /** The id of the notification channel a build's end is posted on while none of the app's own screens is in front: default importance, vibration on, no sound */
    static final String CHANNEL_DONE = "peanuts_build_done";
    private static volatile boolean front;
    /** The library tells whether one of the app's own screens is resumed (dev.peanuts.ui.Library, activity lifecycle callbacks of this process) */
    public static void front(boolean on) { front = on; }

    /**
     * The channel for a build's end notification. The progress channel is LOW (silent) and its importance cannot be raised once it exists, so a build that
     * ends while the app is not in front goes to a second channel that alerts (the system vibrates for it; the service never calls the vibrator). In front
     * the library itself gives the feedback, and a build the user stopped (Stop on the card, Cancel on the notification) stays silent: both keep the
     * progress channel. A job Android paused on its own has the CANCELLED code too, but nobody asked for that, so it alerts.
     */
    static String endChannel(android.app.NotificationManager nm, String progressChannel, boolean stoppedByUser) {
        if (front || stoppedByUser) return progressChannel;
        android.app.NotificationChannel c = new android.app.NotificationChannel(CHANNEL_DONE, "Build finished", android.app.NotificationManager.IMPORTANCE_DEFAULT);
        c.enableVibration(true);
        c.setSound(null, null);
        nm.createNotificationChannel(c);   // no change when it exists already (the user's own settings for it are kept)
        return CHANNEL_DONE;
    }

    /** Starts the build, or puts it into the queue; returns its id (for {@link #attach}/{@link #cancel}), or 0 when the request is not buildable (the listener is told why) */
    public static int start(Context ctx, BuildRequest req, BuildListener l) { return start(ctx, req, false, l); }

    /**
     * Same, with buildAnyway: the player's explicit "Build anyway" on the sheet's Version row (P1) for a dump whose version is not
     * one of the profile's builds. Without it, a job that turns out to need that relaxation stops and asks instead of trying it on its own (Prepare.run).
     * While another game builds or waits, this one goes into the queue ({@link #queued} says its place): its listener hears it when it starts
     */
    public static int start(Context ctx, BuildRequest req, boolean buildAnyway, BuildListener l) {
        return startAll(ctx, java.util.Collections.singletonList(req), java.util.Collections.singletonList(buildAnyway), java.util.Collections.singletonList(l))[0];
    }

    /**
     * Several games at once (Home's Build all), each as {@link #start} takes one, but as one change: the queue's file written once, the build service asked
     * once, and the library told once. One start a game posted the service's foreground notification, wrote the file and redrew Home 50 times for 50 games,
     * all on the main thread (the lag of Build all). Returns each request's id, in their order (0: not buildable, its listener is told why)
     */
    public static int[] startAll(Context ctx, List<BuildRequest> reqs, List<Boolean> buildAnyway, List<BuildListener> ls) {
        int n = reqs.size();
        int[] ids = new int[n];
        BuildQueue q = queue(ctx);
        List<BuildRequest> go = new java.util.ArrayList<>();
        List<Boolean> anyway = new java.util.ArrayList<>();
        List<Integer> at = new java.util.ArrayList<>();
        java.util.Set<String> there = new java.util.HashSet<>();   // the games that waited before: a refused start keeps their item (with the newer request)
        for (int k = 0; k < n; k++) {
            BuildRequest req = reqs.get(k);
            BuildListener l = ls.get(k);
            String why = check(ctx, req);
            if (why != null) { if (l != null) MAIN.post(() -> l.done(false, why, BuildListener.OTHER)); continue; }
            BuildQueue.Item was = q.of(req.titleId);
            if (was != null && was.waiting()) there.add(req.titleId.toUpperCase(java.util.Locale.ROOT));
            go.add(req); anyway.add(buildAnyway.get(k)); at.add(k);
        }
        if (go.isEmpty()) return ids;
        List<BuildQueue.Item> items = q.addAll(go, anyway, System.currentTimeMillis());
        List<Integer> added = new java.util.ArrayList<>();
        for (int j = 0; j < go.size(); j++) {
            BuildRequest req = go.get(j);
            BuildListener l = ls.get(at.get(j));
            BuildQueue.Item it = items.get(j);
            if (it == null) {   // this very game builds now
                String nm = req.name == null || req.name.isEmpty() ? req.titleId : req.name;
                if (l != null) MAIN.post(() -> l.done(false, nm + " is being built now. Wait for it to finish.", BuildListener.OTHER));
                continue;
            }
            ids[at.get(j)] = it.id;
            if (l != null) LISTENERS.put(it.id, l);
            if (!there.contains(req.titleId.toUpperCase(java.util.Locale.ROOT))) added.add(it.id);
            BuildQueue.Item next = q.next();
            if (q.running() == null && running == 0 && waitReason == null && next != null && next.id == it.id) {   // nothing before it: it starts now, as before the queue
                running = it.id;
                lastStep = "import"; lastPercent = 0; lastDetail = "Starting";
                title = req.titleId; name = req.name == null ? "" : req.name; startedAt = System.currentTimeMillis(); endOk = null;
            }
        }
        boolean any = false;
        for (int id : ids) any |= id != 0;
        if (!any) return ids;
        try {
            ctx.getApplicationContext().startForegroundService(BuildService.run(ctx));
        } catch (RuntimeException e) {   // not in the manifest, or Android refused a foreground service from the background
            Log.w(TAG, "BuildService start: " + e);
            q.removeAll(added);
            String refused = "Android did not let the build start. Tap Build again.";   // Android's own words are in the log line above
            for (int k = 0; k < n; k++) {
                int id = ids[k];
                if (id == 0) continue;
                LISTENERS.remove(id);
                if (running == id) running = 0;
                ended(false, refused, BuildListener.OTHER);
                BuildListener l = ls.get(k);
                if (l != null) MAIN.post(() -> l.done(false, refused, BuildListener.OTHER));
                ids[k] = 0;
            }
            changed();
            return ids;
        }
        changed();
        return ids;
    }

    /** The game's waiting item (its place in the queue), null when it does not wait */
    public static BuildQueue.Item queued(String titleId) {
        BuildQueue q = queue;
        BuildQueue.Item i = q == null || titleId == null ? null : q.of(titleId);
        return i != null && i.waiting() ? i : null;
    }

    /** Whether a build is running now (its id), else 0 */
    public static int running() { return running; }

    /** A sheet reopened while a build runs: it gets the latest progress at once and the rest as it comes */
    public static void attach(int id, BuildListener l) {
        if (l == null) return;
        LISTENERS.put(id, l);
        if (running == id) { String s = lastStep, d = lastDetail; int p = lastPercent; MAIN.post(() -> l.progress(s, p, d)); }
    }

    public static void detach(int id) { LISTENERS.remove(id); }

    public static void cancel(Context ctx) {
        try { ctx.startService(new android.content.Intent(ctx, BuildService.class).setAction(BuildService.ACTION_CANCEL)); } catch (RuntimeException e) { Log.w(TAG, "cancel: " + e); }
    }

    private static String check(Context ctx, BuildRequest r) {
        if (r == null) return "Nothing to build.";
        if (r.titleId == null || !r.titleId.matches("[0-9A-Fa-f]{16}")) return "The app cannot tell which game this is. Rescan your games, then build again.";
        if (r.base == null) return "Pick the game's base file first.";
        if (!r.base.exists()) { Log.w(TAG, "check: the base file is missing: " + r.base); return "The game's base file is missing. Open the game's sheet to pick it again."; }
        if (r.needsImport() && r.gameDir == null && r.buildDestination == null && dev.peanuts.PeanutsPrefs.buildDir(ctx) == null)
            return "Set a Game folder in Settings › Library, or pick a Build destination, then build.";
        return null;
    }

    private static void ended(boolean ok, String message, String code) {
        endOk = ok; endMessage = message == null ? "" : message; endCode = code == null ? "" : code; endedAt = System.currentTimeMillis();
        dev.peanuts.ctl.Ctl.libraryChanged();   // the status file: the build's end, and the game's row read again
    }

    static void progress(int id, String step, int percent, String detail) {
        if (id == running) { lastStep = step; lastPercent = percent; lastDetail = detail; dev.peanuts.ctl.Ctl.progress(); }
        BuildListener l = LISTENERS.get(id);
        if (l != null) MAIN.post(() -> l.progress(step, percent, detail));
    }

    static void finish(int id, boolean ok, String message, String code) {
        if (id == running) { running = 0; ended(ok, message, code); }
        BuildListener l = LISTENERS.remove(id);
        if (l != null) MAIN.post(() -> l.done(ok, message, code));
        changed();
    }
}
