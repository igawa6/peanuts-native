package dev.peanuts.ctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import dev.peanuts.GameReturn;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuildPipeline;
import dev.peanuts.ui.CtlPort;
import dev.peanuts.ui.ScreensOff;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control interface's commands (peanuts/docs/CONTROL.md). {@link Command}, the activity only adb's shell may start, hands each command to
 * {@link #receive}. A command that needs no screen runs there and then. One that works on the launcher's screen waits in a queue inside this process while
 * the launcher is brought to the front with a plain intent; the launcher takes commands only from that queue ({@link #attach}), never from an intent's
 * extras, so another app that starts the exported launcher cannot make one up. Every command calls what the matching tap calls.
 * <p>
 * Until the first command of a process nothing here does anything: the hooks return at once and no file is written. After it, status.json is written at
 * every command, at each change of the screen, at build progress, and every 2 s while a build, a Prepare job or a game runs. Nothing keeps the process
 * alive or the device awake for it.
 * <p>
 * All of this runs on the main thread except the writing of the file ({@link Status}, on a thread of its own).
 */
public final class Ctl {
    static final String TAG = "peanuts-ctl";
    private static final long QUEUE_MS = 15_000, PREPARE_MS = 30_000, TICK_MS = 2000;

    /** A command waiting for the launcher's screen, or for an answer that comes later (a Prepare check runs off the main thread) */
    private static final class Pending {
        final Request r; final Result res;
        Pending(Request r, Result res) { this.r = r; this.res = res; }
    }

    /** A game Home's Play started from this process */
    static final class Launch {
        final String titleId, game, pack; final long time;
        Launch(String titleId, String game, String pack) { this.titleId = titleId; this.game = game; this.pack = pack; time = System.currentTimeMillis(); }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();   // the newest first, eight kept
    private static volatile boolean armed;
    private static Context app;
    private static Handler writer;
    private static WeakReference<Activity> main = new WeakReference<>(null), front = new WeakReference<>(null);
    private static boolean frontKnown, writeQueued, tickQueued;
    private static long graceUntil;   // a game was just started: the 2 s writes go on until its process has made its record
    private static AlertDialog dialog;   // the first dialog made while a command watches for one
    private static boolean watching;
    private static Result buildWait;
    static volatile Launch launched;

    private Ctl() {}

    // ---- The hooks (each returns at once until a command was received in this process) ----

    /** MainActivity's onCreate and onNewIntent: the launcher is there; the commands that waited for its screen run now */
    public static void attach(Activity a) {
        main = new WeakReference<>(a);
        if (!armed || QUEUE.isEmpty()) return;
        UI.post(() -> {
            Pending p;
            while ((p = QUEUE.poll()) != null) {
                if (p.res.ok != null) continue;   // timed out meanwhile
                if (a.isFinishing() || a.isDestroyed()) { done(p.res.fail("needs_ui", "The launcher's screen went away before the command ran.")); continue; }
                onScreen(a, p.r, p.res);
            }
        });
    }

    /** Something the status file tells of the screen changed: a page, a tab, Home's filter, a sheet, Screens off */
    public static void screen() { if (armed) write(false, 100); }

    /** A build's progress (at most one write a second) */
    public static void progress() { if (armed) write(false, 1000); }

    /** The library's rows changed: a scan ended, a build ended */
    public static void libraryChanged() { if (armed) write(true, 100); }

    /** MainActivity.start: Home's Play started this game with this pack */
    public static void started(String titleId, String game, String pack) {
        if (!armed) return;
        launched = new Launch(titleId, game, pack);
        graceUntil = System.currentTimeMillis() + 20_000;
        write(false, 500);
    }

    /** A dialog was made (dev.peanuts.ui.Feel.dialog: every dialog of upstream's builder): a command that watches for one takes the first */
    public static void dialog(AlertDialog d) { if (watching && dialog == null) dialog = d; }

    /** The pipeline refused a build. true: a command asked for it and reports the refusal, so the library shows no dialog */
    public static boolean buildRefused(String message) {
        Result r = buildWait;
        if (r == null) return false;
        buildWait = null;
        done(r.fail("refused", message));
        return true;
    }

    // ---- A command comes in (Command only) ----

    static void receive(Activity from, Map<String, String> extras) {
        arm(from.getApplication());
        Request r = new Request(extras, System.currentTimeMillis());
        Result res = new Result(r);
        Log.i(TAG, "command " + r.logLine());
        try {
            r.check();
            if (r.needsScreen()) {
                if (gamePid() > 0 && !r.on("force")) {
                    // starting the launcher's activity from here can end a game's screen above it: only when the caller says so
                    res.fail("game_running", "A game runs: the launcher's screen is not brought in front of it. Stop the game first (game, do=stop), or add -e force true.");
                } else {
                    Pending p = new Pending(r, res);
                    QUEUE.add(p);
                    from.startActivity(Up.mainIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));   // no extras: the command itself waits in QUEUE
                    UI.postDelayed(() -> { if (QUEUE.remove(p)) done(res.fail("needs_ui", "The launcher's screen did not come to the front.")); }, QUEUE_MS);
                }
            } else headless(r, res);
        } catch (Request.Bad e) {
            res.fail(e.code, e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            res.fail("internal", "upstream changed, or the app refused: " + e);
        }
        done(res);
    }

    private static void arm(Application a) {
        if (armed) return;
        app = a;
        HandlerThread t = new HandlerThread("peanuts-ctl");
        t.start();
        writer = new Handler(t.getLooper());
        frontKnown = main.get() == null;   // no launcher in this process (the command started it): nothing of the app is in front. Else the next resume or pause tells
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity x) { front = new WeakReference<>(x); frontKnown = true; if (Up.isMain(x)) main = new WeakReference<>(x); screen(); }
            @Override public void onActivityPaused(Activity x) { if (front.get() == x) { front = new WeakReference<>(null); frontKnown = true; screen(); } }
            @Override public void onActivityCreated(Activity x, Bundle b) {}
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
            @Override public void onActivityDestroyed(Activity x) {}
        });
        armed = true;
    }

    /** The result as it stands into the status file (a pending one too: its command is under way), and one log line when it is final */
    private static void done(Result res) {
        Map<String, Object> m = res.toMap();
        for (int i = RESULTS.size() - 1; i >= 0; i--) {
            Map<String, Object> old = RESULTS.get(i);
            if (old.get("time").equals(res.time) && old.get("cmd").equals(res.cmd) && java.util.Objects.equals(old.get("id"), res.id)) RESULTS.remove(i);
        }
        RESULTS.add(0, m);
        while (RESULTS.size() > 8) RESULTS.remove(RESULTS.size() - 1);
        if (res.ok != null) Log.i(TAG, "result cmd=" + res.cmd + (res.id == null ? "" : " id=" + res.id) + " ok=" + res.ok + (res.error == null ? "" : " error=" + res.error)
                + (res.message == null ? "" : ": " + res.message.replace('\n', ' ')));
        write(res.ok != null && (res.cmd.equals("status") || res.cmd.equals("list")), 0);
    }

    // ---- The status file ----

    private static boolean freshQueued, listQueued;

    /** One write after delay ms; writes asked for meanwhile are that same write */
    private static void write(boolean fresh, long delay) {
        if (!onMain()) { UI.post(() -> write(fresh, delay)); return; }
        freshQueued |= fresh;
        if (writeQueued) return;
        writeQueued = true;
        UI.postDelayed(() -> {
            writeQueued = false;
            boolean f = freshQueued, list = listQueued;
            freshQueued = false; listQueued = false;
            Map<String, Object> screen = screenNow();
            List<Map<String, Object>> results = new ArrayList<>(RESULTS);
            writer.post(() -> {
                Status.write(app, screen, results, f);
                if (list) Status.writeLibrary(app);
                UI.post(Ctl::tick);
            });
        }, delay);
    }

    /** While a build, a Prepare job or a game runs: the file again in 2 s. Stops by itself; the next command or screen change starts it again */
    private static void tick() {
        if (tickQueued || !(Status.busy || System.currentTimeMillis() < graceUntil)) return;
        tickQueued = true;
        UI.postDelayed(() -> { tickQueued = false; write(false, 0); }, TICK_MS);
    }

    private static boolean onMain() { return Looper.myLooper() == Looper.getMainLooper(); }

    /** What shows, as far as this process knows: its own resumed activity, and for the launcher its page, Home's filter and Find text, the open sheet */
    private static Map<String, Object> screenNow() {
        Map<String, Object> m = new LinkedHashMap<>();
        Activity f = front.get(), a = main.get();
        m.put("in_front", frontKnown ? (Object) (f != null) : null);   // null: no screen of the app changed since the first command, so it is not known
        m.put("activity", f == null ? null : f.getClass().getSimpleName());
        if (a != null && !a.isDestroyed()) {
            try {
                String page = Up.pageOf(a);
                m.put("page", page);
                if (page.equals("game")) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("title_id", new Up.Game(Up.pageGame(a)).titleId);
                    g.put("tab", Up.tab());
                    m.put("game_page", g);
                }
                if (page.equals("settings")) m.put("settings_section", dev.peanuts.ui.SettingsPage.current());   // the section that shows (dev.peanuts.ui.SettingsPage)
            } catch (ReflectiveOperationException | RuntimeException e) {
                m.put("page", null);
            }
            m.put("home", CtlPort.home());
            String sheet = CtlPort.sheetTitle();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title_id", sheet);
            m.put("sheet", sheet == null ? null : s);
        }
        m.put("screens_off", CtlPort.screensOff());
        return m;
    }

    // ---- Commands that need no screen ----

    private static void headless(Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        switch (r.cmd) {
            case "status": res.ok(null); break;
            case "list":
                res.ok("The library is in ctl/library.json and in status.json.").put("file", "library.json");
                listQueued = true;   // the write that follows this result also writes library.json
                break;
            case "set": set(r, res); break;
            case "screens_off": ScreensOff.hide(); res.ok("The screens are back."); break;
            case "job": pause(r, res); break;
            case "game": {
                String a = r.action();
                if (a.equals("stop")) stopGame(res); else if (a.equals("ctl")) gameCtl(r, res); else gameLive(r, res);   // gameLive: pending until the game answers
                break;
            }
            case "companion": {   // pending until its thread has the answer; a change is in the library rows of the write that follows
                boolean changes = !r.action().equals("list");
                CompanionCmd.start(app, r, res, () -> UI.post(() -> { done(res); if (changes) libraryChanged(); }));
                break;
            }
            case "share_build_log": {   // the game's build log bundle (Share build log) into files/ctl/build-logs, pending until it is written
                String id = r.titleId(true);
                new Thread(() -> {
                    try {
                        dev.peanuts.build.BuildLogShare.Made m = dev.peanuts.ui.BuildLogUi.forCtl(app, id);
                        if (!m.anything) res.fail("no_build_log", "No build log of " + m.name + " yet, and no job folder was found.");
                        else res.ok("The build log of " + m.name + " is in ctl/build-logs/" + m.zip.getName() + ".").put("file", "build-logs/" + m.zip.getName()).put("path", m.zip.getPath())
                                .put("bytes", m.bytes).put("files", m.files).put("jobs", m.jobs).put("mod_jobs", m.modJobs).put("cut", m.cut);
                    } catch (java.io.IOException | RuntimeException e) {
                        res.fail("failed", e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                    UI.post(() -> done(res));
                }, "peanuts-ctl-build-log").start();
                break;
            }
            default: throw new Request.Bad("unknown_command", r.cmd);
        }
    }

    private static void set(Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(false);
        boolean own = id != null;
        if (own && !Up.hasProfile(id)) throw new Request.Bad("unknown_title", "This app has no game " + id + ".");
        boolean paced = own && Up.prefsBool("limited", id);
        boolean drivers = "gpu_driver".equalsIgnoreCase(r.need("key"));   // the installed drivers are read only for the key that names one
        // PEANUTS: the caps the game's Frame rate cap row enables (Prefs.offeredCaps: the frame limits this display shows, else the rates the host held last run)
        int[] caps = own ? Up.offeredCaps(app, id) : null;
        Settings.Change ch;
        try {
            ch = Settings.parse(r.need("key"), r.need("value"), own, caps, paced, drivers ? Up.drivers(app) : null);
        } catch (Request.Bad e) {
            String why = own && paced && "fps_cap".equalsIgnoreCase(r.need("key")) ? Up.screenWhy(app, id, r.need("value"), caps) : null;
            if (why != null) throw new Request.Bad(e.code, e.getMessage() + ". " + why);   // the game has the cap, this screen cannot show it
            throw e;
        }
        String missing = own ? Up.rowMissing(app, ch.key, id) : null;
        if (missing != null) throw new Request.Bad("not_for_this_game", "The game's settings have no such row: " + missing + ".");
        boolean fill = ch.key.equals("display") && Integer.valueOf(2).equals(ch.value);
        if (fill && !Up.fillable(id)) throw new Request.Bad("not_for_this_game", "This game has no Fill. Display offers Fit and Stretch.");
        Up.apply(app, ch, id);
        res.ok(null).put("key", ch.key).put("stored", ch.value == null ? "default" : ch.key.equals("display") ? Settings.DISPLAYS.get((Integer) ch.value) : ch.value).put("effective", ch.effective);
        if (own) res.put("title_id", id);
        Object now = Up.resolved(app, ch.key, id);
        if (now != null) res.put("resolved", now);   // what the game (every game, without title_id) starts with, the default worked out
        if (ch.key.equals("display")) {
            res.put("label", Up.displayLabel(app, id));
            if (fill && Up.fillUntested(id)) res.ok("Fill is untested on this game. It may look wrong or not start. Set display to fit to go back.").put("untested", true);
        }
        if (ch.key.equals("bottom_screen_games") && !PeanutsPrefs.bottomScreen(app)) res.ok("Stored, but the bottom screen is off in Settings › Library, so games do not use it until it is on.");
    }

    /**
     * job, do=pause (or stop): the button of the card that shows. While the library's build imports or installs the companion that is its own card's Stop (what is
     * written stays, resume builds on); while it prepares, and for a Prepare job of upstream's own, it is the Prepare card's Pause.
     */
    private static void pause(Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(false);
        Map<String, Object> b = BuildPipeline.snapshot();
        boolean builds = b != null && Boolean.TRUE.equals(b.get("running"));
        if (builds && id != null && !id.equalsIgnoreCase(String.valueOf(b.get("title_id")))) throw new Request.Bad("no_job", "The build that runs is " + b.get("title_id") + "'s.");
        Object job = Up.job(app);
        boolean prepares = job != null && "running".equals(Up.jobText(job, "state")) && (id == null || id.equalsIgnoreCase(Up.jobText(job, "titleId")));
        // the build queue (issue #348): stop stops this game and the queue goes on with the next; pause pauses the queue, and do=resume goes on with this game
        boolean stop = r.stopAsked();
        if (builds && (stop || !(prepares && "prepare".equals(b.get("step"))))) {
            if (stop) BuildPipeline.cancel(app); else BuildPipeline.pause(app);
            res.ok(stop ? "The build was asked to stop (the build card's Stop): what is written so far stays; the build queue goes on with its next game."
                    : "The build was asked to pause: the build queue keeps its games, and job with do=resume goes on with this one.").put("what", "build");
        } else if (prepares) {
            Up.pausePack(app);
            res.ok("The Prepare job was asked to pause (its card's Pause)." + (builds ? " The build queue pauses with it; job with do=resume goes on with this game." : "")).put("what", "prepare");
        } else throw new Request.Bad("no_job", "No build or Prepare job runs" + (id == null ? "." : " for " + id + "."));
    }

    /** The pid of the running game as its own record names it, -1 when none runs */
    private static int gamePid() {
        int pid = ExitRecord.pid(Status.record(app));
        return pid > 0 && GameReturn.processAlive(app, ":game", pid) ? pid : -1;
    }

    /**
     * game, do=stop: what the in-game panel's Stop does to the process, from outside it: the run's record gets the player's "stopped" line (so the launcher
     * shows no "stopped unexpectedly" notice), then the process named by that record is ended. Only that process: a game process the record does not name
     * is left alone.
     */
    private static void stopGame(Result res) throws Request.Bad {
        int pid = gamePid();
        if (pid < 0) {
            if (GameReturn.processAlive(app, ":game", -1)) throw new Request.Bad("no_record", "A game process runs but its run record does not name it, so it is not ended from here. Stop it in the game's own panel.");
            throw new Request.Bad("not_running", "No game runs.");
        }
        try {
            Files.write(new File(app.getFilesDir(), "last-exit.txt").toPath(), "state=stopped\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new Request.Bad("unwritable", "The run's record could not be marked as stopped, so the game is left running: " + e.getMessage());
        }
        android.os.Process.killProcess(pid);
        res.ok("The game was stopped as its panel's Stop does: marked as stopped by the player, then its process ended. Unsaved progress is lost.").put("pid", pid);
    }

    /**
     * game, do=fps_cap | console_mode | second_screen | controls (GameLive): the game runs in its own process, so the request goes there as an ordered broadcast
     * for this package only. GameLink's receiver in the game (registered by GameActivity, not exported: only this app reaches it) does the work on the game's
     * main thread and sets the broadcast's result; the final receiver here takes it. The result stays pending until then, and fails with no_answer when nothing
     * came within GameLive.ANSWER_MS (or the game's screen has no receiver: the broadcast then ends with its first result code).
     */
    private static void gameLive(Request r, Result res) throws Request.Bad {
        if (gamePid() < 0) throw new Request.Bad("not_running", "No game runs.");
        String want = r.titleId(false), have = Status.record(app).get("title");
        if (want != null && have != null && !want.equalsIgnoreCase(have)) throw new Request.Bad("other_game", "The game that runs is " + have + ", not " + want + ".");
        Intent i = new Intent(GameLive.ACTION).setPackage(app.getPackageName()).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        for (Map.Entry<String, String> e : GameLive.args(r).entrySet()) i.putExtra(e.getKey(), e.getValue());
        boolean[] over = {false};
        Runnable late = () -> {
            if (over[0]) return;
            over[0] = true;
            done(res.fail("no_answer", "The game did not answer in " + GameLive.ANSWER_MS / 1000 + " s."));
        };
        app.sendOrderedBroadcast(i, null, new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent x) {
                if (over[0]) return;   // answered after the wait ended: the result says no_answer already
                over[0] = true;
                UI.removeCallbacks(late);
                Bundle b = getResultExtras(false);
                GameLive.take(getResultCode() == Activity.RESULT_OK && b != null ? b.getString(GameLive.EXTRA_ANSWER) : null, res);
                done(res);
            }
        }, UI, Activity.RESULT_CANCELED, null, null);
        UI.postDelayed(late, GameLive.ANSWER_MS);
    }

    /** game, do=ctl: one line into the file the running game reads its live control lines from (DM_CTL: the launcher sets it to ctl.txt at every start) */
    private static void gameCtl(Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        if (gamePid() < 0) throw new Request.Bad("not_running", "No game runs.");
        Map<String, String> rec = Status.record(app);
        String id = rec.get("title");
        File ext = app.getExternalFilesDir(null);
        if (ext == null) throw new Request.Bad("unwritable", "The app's shared storage is not there now.");
        // the game's environment may have turned the file off or moved it: env.txt, the game's own lines, env-<title ID>.txt, the last line wins
        String ctl = new File(ext, "ctl.txt").getPath();
        List<String> lines = new ArrayList<>(envLines(new File(ext, "env.txt")));
        if (id != null) { for (String l : Up.gameEnv(app, id).split("\n")) lines.add(l.trim()); lines.addAll(envLines(new File(ext, "env-" + id + ".txt"))); }
        for (String l : lines) if (l.startsWith("DM_CTL=")) ctl = l.substring(7).trim();
        if (ctl.isEmpty()) throw new Request.Bad("ctl_off", "This game was started with live control off (a DM_CTL= line in its environment).");
        try (FileWriter w = new FileWriter(ctl, true)) {
            w.write(r.need("line") + "\n");
        } catch (IOException e) {
            throw new Request.Bad("unwritable", "The control file cannot be written (" + e.getMessage() + "). A ctl.txt that adb made belongs to the shell: remove it, or chmod 666 it.");
        }
        res.ok("The line is in the game's control file. Its answer, if it has one, is in the run's log.").put("log", rec.get("log"));
    }

    private static List<String> envLines(File f) {
        List<String> out = new ArrayList<>();
        try { if (f.isFile()) for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) out.add(l.trim()); }
        catch (IOException e) { /* a file the app cannot read is one the game cannot read either */ }
        return out;
    }

    // ---- Commands on the launcher's screen ----

    private static void onScreen(Activity a, Request r, Result res) {
        try {
            switch (r.cmd) {
                case "nav": nav(a, r, res); break;
                case "scan": scan(a, res); break;
                case "build": build(a, res, r.titleId(true), false); break;
                case "reimport": reimport(a, r, res); break;
                case "import_pack": importPack(a, r, res); break;
                case "prepare": prepare(a, r, res); break;
                case "play": play(a, r, res); break;
                case "job": job(a, r, res); break;
                case "screens_off": screensOff(a, res); break;
                default: throw new Request.Bad("unknown_command", r.cmd);
            }
        } catch (Request.Bad e) {
            res.fail(e.code, e.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            res.fail("internal", "upstream changed, or the app refused: " + e);
        }
        done(res);
    }

    private static void home(Activity a) throws ReflectiveOperationException, Request.Bad {
        if (!Up.pageOf(a).equals("home")) Up.page(a, false);
        if (!CtlPort.hasHome(a)) throw new Request.Bad("no_home", "Home could not be drawn.");
    }

    private static void nav(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String to = r.word("to", java.util.Arrays.asList("home", "settings", "game"));
        if (to.equals("home")) {
            home(a);
            String filter = r.word("filter", Request.FILTERS), find = r.str("find");
            if (r.on("find_clear")) find = "";
            String now = CtlPort.view(a, filter, find);
            if (filter != null && !filter.equals(now)) throw new Request.Bad("filter_unavailable", "Home has no " + filter + " filter now (it shows only while something builds). It shows " + now + ".");
            res.ok(null).put("page", "home").put("filter", now);
        } else if (to.equals("settings")) {
            Up.page(a, true);
            String section = r.str("section");
            if (section != null && !dev.peanuts.ui.SettingsPage.go(a, section))
                throw new Request.Bad("no_section", "Settings is open, but it has no section \"" + section + "\". Its sections: " + String.join(", ", dev.peanuts.ui.SettingsSections.names()) + ".");
            res.ok(null).put("page", "settings").put("section", dev.peanuts.ui.SettingsPage.current());
        } else {
            String id = r.titleId(true), tab = r.word("tab", Request.TABS);
            Up.Game g = Up.game(a, id);
            if (g == null) throw new Request.Bad("not_listed", id + " has no page: a game gets one when it is built (or added).");
            if (tab != null && !Up.hasProfile(id) && (tab.equals("performance") || tab.equals("game")))
                throw new Request.Bad("no_tab", "A game this app version does not include has only the Saves and Storage tabs.");
            Up.openGame(a, g.raw, tab);
            res.ok(null).put("page", "game").put("title_id", g.titleId).put("tab", Up.tab());
        }
    }

    private static void scan(Activity a, Result res) throws Request.Bad, ReflectiveOperationException {
        if (PeanutsPrefs.gameDir(a) == null) throw new Request.Bad("no_game_dir", "No game folder is set (Settings › Library).");
        home(a);
        CtlPort.rescan(a);
        res.ok("The folders are being read again (Settings › Library › Rescan). screen.home.scanning says when it has ended; the library follows.");
    }

    /** The library's build, with the files the sheet would choose. Its answer is the pipeline's: accepted (the build then shows under "build"), or refused */
    private static void build(Activity a, Result res, String id, boolean anyway) throws Request.Bad, ReflectiveOperationException {
        home(a);
        Object job = Up.job(app);
        // upstream's own Prepare: the library's builds go into the build queue (issue #348), whose Prepare waits for the packer
        if (!BuildPipeline.busy() && job != null && "running".equals(Up.jobText(job, "state")) && GameReturn.processAlive(app, ":pack", -1)) throw new Request.Bad("busy", Up.jobText(job, "name") + " is being prepared now.");
        String[] no = CtlPort.build(a, id, anyway);
        if (no != null) throw new Request.Bad(no[0], no[1]);
        if (CtlPort.building(a, id)) { res.ok("The build started. Its progress is under \"build\" and, while it prepares, under \"job\"."); return; }
        if (queuedAnswer(res, id)) return;
        // the pipeline refused: its sentence comes through buildRefused in a moment
        buildWait = res;
        UI.postDelayed(() -> { if (buildWait == res) { buildWait = null; done(res.fail("refused", "The build did not start.")); } }, 1000);
    }

    /**
     * The sheet's Import again: the game's .nup made anew from its dumps, then the build. It replaces a file, so it wants -e confirm true as the answer to the
     * sheet's question; without it the refusal (needs_confirm) carries that question and data carries what would be used
     */
    private static void reimport(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(true);
        home(a);
        Object job = Up.job(app);
        if (!BuildPipeline.busy() && job != null && "running".equals(Up.jobText(job, "state")) && GameReturn.processAlive(app, ":pack", -1)) throw new Request.Bad("busy", Up.jobText(job, "name") + " is being prepared now.");
        Map<String, Object> data = new LinkedHashMap<>();
        String[] no = CtlPort.reimport(a, id, r.on("confirm"), r.on("delete_first"), data);
        for (Map.Entry<String, Object> e : data.entrySet()) res.put(e.getKey(), e.getValue());
        if (no != null) throw new Request.Bad(no[0], no[1]);
        if (CtlPort.building(a, id)) { res.ok("The import started: the old .nup is replaced when the new one is complete, then the game is built. Its progress is under \"build\"."); return; }
        if (queuedAnswer(res, id)) return;
        buildWait = res;
        UI.postDelayed(() -> { if (buildWait == res) { buildWait = null; done(res.fail("refused", "The import did not start.")); } }, 1000);
    }

    /**
     * The game sheet's Import pack… without the picker (GitHub issue #44): the pack (a .zip made by Export pack…, or a pack folder) for a game that is not built
     * yet, with the same checks and dialogs. Pending while it is imported; then ok with what the "Pack imported" dialog says, or refused with its sentence
     */
    private static void importPack(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(true);
        home(a);
        String[] no = CtlPort.importPack(a, id, r.need("path"), out -> {
            if (out[0] == null) res.ok(out[1]);
            else res.fail(out[0], out[1]);
            done(res);
        });
        if (no != null) throw new Request.Bad(no[0], no[1]);
    }

    /** A build that went into the build queue (issue #348): ok, with its place ("queue": the place and the batch's total); false when it did not */
    private static boolean queuedAnswer(Result res, String id) {
        dev.peanuts.build.BuildQueue.Item qi = BuildPipeline.queued(id);
        dev.peanuts.build.BuildQueue q = BuildPipeline.queueNow();
        if (qi == null || q == null) return false;
        String p = q.placeText(qi);
        res.ok("Another game builds now: this one is in the build queue" + (p.isEmpty() ? "" : ", " + p) + ". Its state is under \"queue\", and under \"build\" once it starts.")
                .put("queue_place", q.place(qi)).put("queue_total", q.total());
        return true;
    }

    private static void watch() { dialog = null; watching = true; }

    /** Ends the watch. The dialog a command led to is its answer: its text is given back and the dialog closed (as Cancel or Back would); null when none came */
    private static String[] watched() {
        watching = false;
        AlertDialog d = dialog;
        dialog = null;
        if (d == null) return null;
        CharSequence title = d.getWindow() == null ? null : d.getWindow().getAttributes().getTitle();
        StringBuilder text = new StringBuilder();
        if (d.getWindow() != null) texts(d.getWindow().getDecorView(), text);
        try { d.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
        String t = title == null ? "" : title.toString();
        if (t.startsWith(app.getPackageName() + "/")) t = "";   // a dialog without a title: Android names its window after the activity
        String body = text.toString().trim();
        if (!t.isEmpty() && body.startsWith(t)) body = body.substring(t.length()).trim();
        return new String[] {t, body.length() > 1500 ? body.substring(0, 1500) + "…" : body};
    }

    /** The dialog's sentences, without its buttons */
    private static void texts(View v, StringBuilder out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof Button) return;
        if (v instanceof TextView) {
            String s = String.valueOf(((TextView) v).getText()).trim();
            if (!s.isEmpty()) out.append(s).append('\n');
        } else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    private static Request.Bad refusal(String[] d) { return new Request.Bad("refused", (d[0].isEmpty() ? "" : d[0] + ": ") + d[1]); }

    /** Home's Play: the font check of a cover's tap, then MainActivity.launch with its pack choice (mods) and its checks. A dialog it shows is the refusal */
    private static void play(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(true);
        Up.Game g = Up.game(a, id);
        if (g == null) throw new Request.Bad("not_listed", id + " is not a game Home can play: build it first.");
        if (CtlPort.building(a, id)) throw new Request.Bad("busy", g.name + " is being built now.");
        String fonts = CtlPort.fonts(a, id, g.name);
        if (fonts != null) throw new Request.Bad("fonts_needed", fonts);
        launched = null;
        watch();
        String[] d;
        try { Up.launch(a, g.raw); } finally { d = watched(); }
        Launch l = launched;
        if (l != null && l.titleId.equalsIgnoreCase(id)) {
            res.ok("The game was started.").put("title_id", l.titleId).put("game", l.game).put("pack", l.pack).put("mods_pack", dev.peanuts.build.ModdedBuild.isVariantPack(new File(l.pack)));
            return;
        }
        if (d != null) throw refusal(d);
        throw new Request.Bad("not_started", "Play did not start the game and showed no dialog.");
    }

    /** Storage › Prepare again without its question: the card checks the dump off the main thread, then starts the job or says why not in a dialog */
    private static void prepare(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(true);
        Up.Game g = Up.game(a, id);
        if (g == null) throw new Request.Bad("not_listed", id + " is not in Home's list: build it first.");
        if (!Up.hasProfile(id)) throw new Request.Bad("not_in_app", "This app version does not include " + g.name + ".");
        File dump = new File(g.gameDir);
        if (!Up.isDump(dump)) throw new Request.Bad("not_a_dump", "Prepare again works from a dump or a .nup; " + dump.getName() + " is neither.");
        home(a);
        // while the library builds or its queue waits, Prepare again goes into the build queue (issue #348), as Storage › Prepare again does (Ondevice.prepare)
        if (BuildPipeline.busy()) {
            if (CtlPort.building(a, id)) throw new Request.Bad("busy", g.name + " is being built now.");
            if (!dev.peanuts.ui.Library.queuePrepare(a, dump)) throw new Request.Bad("refused", "Prepare again could not be added to the build queue.");
            if (!queuedAnswer(res, id)) throw new Request.Bad("refused", "Prepare again could not be added to the build queue.");
            return;
        }
        Object panel = Up.panel(a);
        if (panel == null) throw new Request.Bad("no_home", "Home's Prepare card is not there.");
        watch();
        try { Up.panelPrepareAgain(panel, dump); } catch (ReflectiveOperationException | RuntimeException e) { watched(); throw e; }
        long until = System.currentTimeMillis() + PREPARE_MS;
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                if (dialog != null && dialog.isShowing()) {
                    String[] d = watched();
                    if (d[0].equals("Ready to play")) done(res.ok(d[1]));   // the pack fits already: nothing to prepare
                    else done(res.fail("refused", (d[0].isEmpty() ? "" : d[0] + ": ") + d[1]));
                    return;
                }
                Object job = Up.job(app);
                try {
                    if (job != null && id.equalsIgnoreCase(Up.jobText(job, "titleId")) && ((Number) Up.call(job, "updated")).longValue() >= r.time - 2000) {
                        String s = Up.jobText(job, "state");
                        if (s.equals("running") || s.equals("pending") || s.equals("paused")) { watched(); done(res.ok("Preparing started. Its progress is under \"job\".")); return; }
                    }
                } catch (ReflectiveOperationException | RuntimeException e) { /* the job does not read yet: asked again */ }
                if (System.currentTimeMillis() > until) { watched(); done(res.fail("no_answer", "The Prepare card gave no answer in 30 s. \"job\" says how it stands.")); return; }
                UI.postDelayed(this, 500);
            }
        }, 500);
    }

    /** job, do=resume / retry / try_anyway: the buttons of the card that shows, the library's build card first (a build that stopped in Prepare shows only that one) */
    private static void job(Activity a, Request r, Result res) throws Request.Bad, ReflectiveOperationException {
        String id = r.titleId(false), act = r.action();
        home(a);
        // a paused build queue (issue #348): resume goes on with its first game, where it stopped (the Up next card's Resume)
        dev.peanuts.build.BuildQueue q = BuildPipeline.queueNow();
        dev.peanuts.build.BuildQueue.Item first = q == null ? null : q.next();
        if (act.equals("resume") && BuildPipeline.held() && first != null && (id == null || id.equalsIgnoreCase(first.titleId()))) {
            BuildPipeline.release(app);
            res.ok("The build queue goes on with " + first.name() + " (the Up next card's Resume).").put("what", "queue");
            return;
        }
        Map<String, Object> card = CtlPort.buildCard();
        String failed = card != null && Boolean.TRUE.equals(card.get("failed")) ? String.valueOf(card.get("title_id")) : null;
        if (failed != null && id != null && !id.equalsIgnoreCase(failed)) failed = null;
        boolean anyway = act.equals("try_anyway");
        if (failed != null || anyway && id != null && CtlPort.wouldAsk(a, id)) {
            // the build card's Resume, or its Retry and the sheet's Build; try_anyway is the answer to the sheet's "Build anyway?"
            build(a, res, failed != null ? failed : id, anyway);
            return;
        }
        Object panel = Up.panel(a), job = panel == null ? null : Up.panelJob(panel);
        if (job == null || id != null && !id.equalsIgnoreCase(Up.jobText(job, "titleId"))) throw new Request.Bad("no_job", "No stopped build or Prepare job" + (id == null ? "." : " of " + id + "."));
        watch();
        boolean need;
        try { need = Up.needAccess(a, (File) Up.call(job, "dump")); } finally { watched(); }
        if (need) throw new Request.Bad("need_access", "The app needs All files access to read the dump.");
        if (anyway) {
            if (!Up.panelOffersAnyway(panel)) throw new Request.Bad("not_offered", "The Prepare card does not offer Try anyway now (it does after a failure that an untested version explains).");
            Up.panelTryAnyway(panel, job);
            res.ok("The job goes on with its checks relaxed (the card's Try anyway): the game may not start or may draw wrongly.");
            return;
        }
        String button = Up.panelButton(panel);
        if (button.equals("Pause")) throw new Request.Bad("running", "The job runs already.");
        if (button.isEmpty()) throw new Request.Bad("not_offered", "The Prepare card offers no Resume or Retry now: " + Up.jobText(job, "state") + ".");
        watch();
        String[] d;
        try { Up.panelGoOrPause(panel); } finally { d = watched(); }
        if (d != null) throw refusal(d);
        res.ok("The card's " + button + " was pressed.").put("button", button);
    }

    private static void screensOff(Activity a, Result res) throws Request.Bad, ReflectiveOperationException {
        Object job = Up.job(app);
        boolean builds = BuildPipeline.busy() || job != null && "running".equals(Up.jobText(job, "state"));   // busy: a game builds, or the build queue waits to build the next (issue #348), as Home shows the button
        if (!builds) throw new Request.Bad("nothing_builds", "Screens off is for a running build: nothing builds now.");
        ScreensOff.show(a);
        if (!ScreensOff.on()) throw new Request.Bad("refused", "The screens could not be covered.");
        res.ok("The screens are black while it builds. A tap, a pad button or screens_off with on=false brings them back.");
    }
}
