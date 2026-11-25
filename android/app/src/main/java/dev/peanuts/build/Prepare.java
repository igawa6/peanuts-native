package dev.peanuts.build;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Prepare step without MainActivity's Ondevice panel: what Ondevice.Panel.picked → checked → go/adopt does, done directly against upstream's classes
 * (reflection, {@link Upstream}) and its PackService (the ":pack" process):
 * <ol>
 * <li>DumpCheck.check on the .nup / dump folder (the title id, the work folder &lt;dump&gt;/../&lt;title ID&gt;-work, whether this app can prepare it);</li>
 * <li>a job.json already in the work folder for this dump (running, paused, failed, pending) is resumed; a pack beside the dump that fits (PackCheck) is adopted
 *     as it is, one that fits in part has only the rest redone (Job.adopt); else a whole job (Job.create into the app's packs/&lt;title ID&gt;);</li>
 * <li>the job is saved and remembered (Job.remember: the panel's card shows it too when Home is open), and PackService is started with ACTION_START + the job file,
 *     exactly as the panel's startService does. The build service is a foreground service, so startForegroundService from here is allowed while it runs; the
 *     service is also bound (BIND_AUTO_CREATE) so the ":pack" process is held and its MSG_STATE messages carry progress between job.json writes;</li>
 * <li>progress is read from &lt;work&gt;/ondevice/job.json (format 2: "state", "step", "steps".&lt;step&gt;.done/total/stage) once a second until the job is done,
 *     failed, or paused for a reason that stops the service (user, timeout, refused); heat and battery pauses are waited out, as the service does;</li>
 * <li>a done job's game goes into the launcher's list with its pack (Upstream.addGame = Ondevice.Panel.addGame), unless the pack is the fake engine's placeholder.</li>
 * </ol>
 * Two things a pack folder's own note says ({@link PackNote}) change this: a pack whose game file was imported again since (stale) is never "Already prepared"
 * and never adopted as it is; its job is left and its shaders and pack are made again from the new file. And a job that failed because the game's add-on
 * content was not imported is not tried anyway for the player ({@link AutoRelax}): the build stops with {@link BuildListener#ADDON_MISSING}, and the card's Try
 * anyway comes back here as consent.
 */
final class Prepare {
    interface Progress { void on(int percent, String detail); }

    static final String TAG = "peanuts.build";
    private static final String[] STEPS = {"identify", "rewrite", "split", "shaders", "pack"};
    private static final String[] STEP_TEXT = {"Checking the game files", "Converting the game's code", "Finding the shaders", "Making shaders for this phone", "Finishing up"};
    /** What to do after a stop with no known cause (PrepareText.RETRY_SHARE's words) */
    static final String RETRY_SHARE = "Tap Retry. If it stops again, share the build log with a bug report.";
    /** Where a build with mods is started again (it has no card of its own) */
    private static final String MODS_AGAIN = "Try again from the game's Mods section: hold the cover, then open the Game tab.";
    /** A game version none of the app's list for the game names: the card's first line (the checker's own words go to Details) */
    private static final String UNTESTED = "This game version is untested. To try it anyway, open the game's sheet and tap Build anyway.";
    /** How long a job may sit "running" with no job.json change and no service message before we call it stalled (the service writes at least every few seconds while it runs) */
    private static final long STALL_MS = 10 * 60 * 1000;
    /** How long Try anyway's restart waits for the failed run's end in the packer (its worker's last save and its end's message come moments after the failure) */
    private static final long RUN_END_MS = 30_000;

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile String fromService;   // the newest job JSON the service sent over the Messenger, of this run's job only
    private volatile long fromServiceAt;
    // The build queue (issue #348) runs games back to back: the packer may still run the job of the game before (stopping after its Stop, or upstream's own
    // Prepare), and it drops a start that comes while it does. ownFile: this run's job.json; otherRuns: the service's last message was of another job it runs
    private volatile File ownFile;
    private volatile boolean heard, otherRuns;
    private volatile String otherName = "";
    /** This run started the packer on its job (so a Stop may pause the packer: before, the packer runs another game's job) */
    private volatile boolean jobStarted;

    boolean jobStarted() { return jobStarted; }
    private volatile Link link;   // set on the main thread (bind, unbind), read by the build's thread (the stall rule, runEnded)
    private volatile String logId;   // the game whose build log (BuildLog) this run's lines go to

    Prepare(Context c) { this.c = c.getApplicationContext(); }

    /** One line in logcat and in the game's build log (Share build log) */
    private void log(String s) {
        Log.i(TAG, s);
        BuildLogShare.line(c, logId, s);
    }

    void cancel() { cancelled = true; }

    /**
     * Runs the whole step; returns the pack folder of the ready game. Throws with a user-readable message when it cannot. consent: the player's explicit
     * "Build anyway" on the sheet's Version row for THIS build (P1) — without it, a dump whose version is not one of the profile's
     * builds stops and asks instead of being retried untested on its own (the old behaviour: see the otherBuild branch in run0)
     */
    File run(File dump, String titleId, File destination, boolean consent, Progress p) throws IOException {
        logId = titleId;
        try {
            return run0(dump, titleId, destination, consent, p);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "prepare: upstream reflection", e);
            BuildLogShare.line(c, logId, "prepare: upstream reflection: " + e);
            throw new IOException("The build could not start because of a problem in the app. " + RETRY_SHARE);
        } finally {
            unbind();
        }
    }

    private File run0(File dump, String titleId, File destination, boolean consent, Progress p) throws IOException, ReflectiveOperationException {
        p.on(0, STEP_TEXT[0]);
        // 1. a job already there for this dump?
        String id = titleId;
        try { id = Upstream.titleIdOf(dump); } catch (ReflectiveOperationException | RuntimeException e) { /* DumpCheck says what is wrong */ }
        if (id == null || id.isEmpty()) id = titleId;
        File jf = Upstream.jobFileOf(Upstream.workOf(dump, id));
        boolean anyBuild = false;
        Object existing = null;
        if (jf.isFile()) {
            try {
                existing = Upstream.jobRead(jf);
                anyBuild = Boolean.TRUE.equals(Upstream.call(existing.getClass(), existing, "tryAnyway", new Class<?>[0]));
            } catch (ReflectiveOperationException | RuntimeException e) {
                existing = null;   // another format or damaged: a new job replaces it
            }
        }
        long firstRead = existing == null ? -1 : updatedOf(existing);   // job.json's "updated" as this run first read it: a job the packer still holds from then is not this run's
        // The game file was imported again since the job's pack was made (BuildService's import marked it: PackNote). The old job says nothing about the new
        // file: it is left below, and its Try anyway with it, unless the version itself needed that (had). Without this a job that finished without the DLC's
        // shaders answered "Already prepared" for a .nup that now has the DLC
        boolean fresh = existing == null, reimported = false, had = anyBuild;
        if (existing != null) {
            try { reimported = PackNote.stale(Upstream.jobPack(existing)); } catch (ReflectiveOperationException | RuntimeException e) { /* no pack named: not stale */ }
            if (reimported) { anyBuild = false; log("prepare: " + dump.getName() + " was imported again since its pack was made: the shaders and the pack are made again"); }
        }
        // 2. the dump. A version this app's profile does not list (otherBuild) used to be retried silently here ("Try anyway" done for the player without asking:
        // found in review, the DQ3 HD-2D case — ~10 minutes lost on a build the sheet never warned about). Now it only goes on when the sheet's own
        // Version row got the player's explicit "Build anyway" for this request (consent); a resumed job that was already marked tryAnyway (anyBuild from the
        // existing job.json) still resumes as it was, since that choice was already made once
        Object r = Upstream.dumpCheck(dump, fresh, anyBuild);
        String error = (String) Upstream.get(r, "error");
        if (error != null && !anyBuild && Boolean.TRUE.equals(Upstream.get(r, "otherBuild"))) {
            if (!consent && !had) {
                String detail = Upstream.str(Upstream.get(r, "detail"));
                throw new BuildListener.Failure(UNTESTED + "\n" + error + (detail.isEmpty() ? "" : "\n" + detail)
                        + "\nBuild anyway may make a game that does not start or crashes, and its first build looks at every shader.",
                        BuildListener.WRONG_VERSION);
            }
            log("prepare: untested version (" + error + "), building anyway (the player chose Build anyway)");
            Object again = Upstream.dumpCheck(dump, fresh, true);
            if (Upstream.get(again, "error") == null) { r = again; error = null; anyBuild = true; p.on(0, "Untested game version, building it anyway"); }
        }
        if (error != null) {
            // not a version mismatch (that already returned above): an incomplete/corrupt dump, no RomFS, the wrong title ID, a bad file name, … — a bad dump,
            // not a missing one (found in review; this used to say FILE_MISSING and offer "pick the file again", which does not fix a bad dump)
            boolean other = Boolean.TRUE.equals(Upstream.get(r, "otherBuild"));
            String detail = Upstream.str(Upstream.get(r, "detail"));
            String flat = other ? null : VersionFolder.why(dump);   // one version's folder: where its files go, not "pick the folder above it" (GitHub issue #370)
            // the card's first line in the player's words; the checker's own sentence (file names, folders, title IDs) goes to Details. A version's folder
            // (VersionFolder) is the exception: its sentence is the fix, so it is the first line
            String head = flat != null ? flat : other ? "This game version is untested." : "Checking the game files stopped. The app cannot use these game files as they are.";
            throw new BuildListener.Failure(head + (flat != null ? "" : "\n" + error) + (detail.isEmpty() ? "" : "\n" + detail),
                    other ? BuildListener.WRONG_VERSION : BuildListener.INVALID_DUMP);
        }
        String rid = Upstream.str(Upstream.get(r, "titleId"));
        if (!rid.isEmpty()) id = rid;
        File dumpR = (File) Upstream.get(r, "dump");
        long version = ((Number) Upstream.get(r, "version")).longValue();
        Object job = null;
        if (reimported) existing = null;   // the job of the old game file: step 3 takes its pack over with the shaders to make again
        if (existing != null) {
            String s = Upstream.jobState(existing);
            boolean same = Upstream.jobDump(existing).getPath().equals(dumpR.getPath()) && Upstream.jobVersion(existing) == version;
            if (destination != null && !destination.equals(Upstream.jobPack(existing))) {
                if (same && !s.equals("done") && !s.isEmpty())
                    throw new BuildListener.Failure("This game has an unfinished build in another folder. To go on with it, pick that folder as the game's "
                            + "Build destination, then tap Build.\n" + Upstream.jobPack(existing), BuildListener.OTHER);
                same = false;
            }
            if (same && (s.equals("running") || s.equals("paused") || s.equals("failed") || s.equals("pending"))) job = existing;   // resumed where it stopped
            else if (same && s.equals("done") && Upstream.jobPack(existing).isDirectory() && Upstream.packRead(Upstream.jobPack(existing)) != null
                    && current(Upstream.jobPack(existing), dumpR, id) && !noOrig(Upstream.jobPack(existing))) {   // (a pack an app update left behind is redone below: step 3; so is one without main.orig.bin)
                // already prepared by an earlier run: the game only needs listing
                p.on(100, "Already built");
                Upstream.addGame(c, existing);
                return Upstream.jobPack(existing);
            }
            else if (!s.equals("done") && !s.isEmpty()) throw new BuildListener.Failure("These game files already have an unfinished build of another version. Let that build "
                    + "finish, or delete its working files, then tap Retry.\n" + Upstream.jobPack(existing), BuildListener.OTHER);
        }
        // 3. a pack beside the dump that fits (PackCheck): adopted, or only the rest redone
        if (job == null) {
            File packDir = destination;
            if (packDir == null) {
                try { packDir = Upstream.packFor(c, dumpR, id); } catch (ReflectiveOperationException | RuntimeException e) { /* no pack: the whole job */ }
            }
            Object pack = packDir == null ? null : Upstream.packRead(packDir);
            if (pack != null && !Boolean.TRUE.equals(Upstream.get(pack, "simulated")) && id.equals(Upstream.str(Upstream.get(pack, "titleId")))) {
                String blocking = Upstream.packBlocking(c, pack, dumpR);
                String shaders = Upstream.packShaders(packDir, id);
                // The code is redone when it cannot be started (blocking) and also when it is another rewriter's: an app update with a changed rewriter or
                // profile patch (PackCheck.code, the check Home's own Prepare makes: Metroid Dread's 60 fps patch never reached a pack built before it, 2026-11-14)
                // ...and when main was rewritten before the rewriter kept the file's own bytes of what it changed (main.orig.bin, which a companion package's
                // module reads: dm/peanuts/peanuts.c rt_orig). Such a pack still counts as up to date, so nothing rebuilds on its own; a build the user starts
                // gives it the file (Octopath Traveler 0, 2026-11-14: Build again left its code as it was)
                // ...and the shaders are made again when the game file was imported again since the pack was made (PackNote: the old job's pack, or this one),
                // though the library in the pack is this APK's: it was made from the shaders the old file had
                boolean stale = reimported || PackNote.stale(packDir);
                boolean code = blocking != null || noOrig(packDir) || Boolean.FALSE.equals(Upstream.packCode(c, pack, dumpR)), sh = shaders != null || stale || needsShaderRepair(packDir, id);
                job = Upstream.jobAdopt(r, packDir, code, sh);
                Upstream.jobSave(job);
                Upstream.jobRemember(c, Upstream.jobFile(job));
                if (!code && !sh) {
                    p.on(100, "The game's build is up to date, nothing to do");
                    Upstream.addGame(c, job);
                    return packDir;
                }
                log("prepare: redoing " + (code ? "code " : "") + (sh ? "shaders " : "") + "of " + packDir + (blocking != null ? " (" + blocking + ")" : "") + (shaders != null ? " (" + shaders + ")" : "")
                        + (stale ? " (the game file was imported again)" : ""));
            }
        }
        if (job == null) {
            job = Upstream.jobCreate(r, destination != null ? destination : Upstream.appPack(c, id));
            Upstream.jobSave(job);
            Upstream.jobRemember(c, Upstream.jobFile(job));
        }
        if (anyBuild && !Upstream.jobTryAnyway(job)) { Upstream.jobTryAnyway(job, true); Upstream.jobSave(job); }
        // The card's Try anyway for a job that stopped and asked (AutoRelax.asks: add-on content not imported): the player's own choice, made on the build card
        // or by the control interface, arrives as this run's consent. Only a failed job whose failure offers it is relaxed
        if (consent && job == existing && Upstream.jobState(job).equals("failed") && AutoRelax.FIX_ANYWAY.equals(Upstream.jobFix(job)) && !Upstream.jobTryAnyway(job)) {
            log("prepare: going on with the checks relaxed (the player chose Try anyway)");
            Upstream.jobTryAnyway(job, true);
            Upstream.jobSave(job);
        }
        File file = Upstream.jobFile(job);
        log("prepare: job " + file + " (" + (job == existing ? "resumed, it was " + Upstream.jobState(job) : "new") + "), pack " + Upstream.jobPack(job));
        BuildLogShare.remember(c, id, file.getParentFile() == null ? null : file.getParentFile().getParentFile());
        boolean relaxed = false;   // one restart with the checks relaxed when the job fails for something Try anyway skips
        // The translator calls at once (4 on a phone with 14 GB or more, 2 on an 8 GB one) are set by PackService from the phone's memory (BuildMemory), not by a line here:
        // a fixed 4 in env.txt ran an 8 GB phone out of memory (Fire Emblem Engage on a Thor Base, issue #10). A line env.txt has still applies, up to that count
        // 4. the service, as the panel starts it (once it runs no other job)
        // the states from before this run, by their "updated" (issue #120): the job's as this run last read or wrote it (its state until the packer saves it
        // again is the job's from before) and job.json's as this run first read it (a done or failed job of an earlier run of this game the packer still
        // held, which it sends a client that binds: a game's builds share one job.json). A job the packer finished while the build got ready has neither
        Set<Long> earlier = earlier(updatedOf(job), firstRead);
        ownFile = file;   // the service's messages of this job only (Link.onMessage)
        bind();
        waitForPacker(p);
        long startedAt = System.currentTimeMillis();   // a pause job.json says was written before this start is the last run's (a Resume of a paused job)
        try {
            c.startForegroundService(Upstream.packStart(c, file));
            jobStarted = true;
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException (Android 12+): the app counts as background now
            log("PackService start refused: " + e);
            throw new BuildListener.Failure("Android did not let the build start in the background. Nothing is lost. Open " + appName() + " and tap Resume on the game's card.", BuildListener.CANCELLED);
        }
        p.on(1, STEP_TEXT[0]);
        // 5. progress from job.json (and the service's messages while bound) until the job ends
        // Allow a temporary read failure at any point, not just during the first 30 seconds.
        long lastChange = SystemClock.elapsedRealtime(), lastReadable = lastChange;
        String lastJson = null;
        while (!cancelled) {
            SystemClock.sleep(1000);
            String json = read(file);
            String fs = fromService;
            if (fs != null && fromServiceAt > lastChange) json = fs;
            if (json == null) {
                if (SystemClock.elapsedRealtime() - lastReadable > 30000) { log("prepare: the job file went missing: " + file); throw new BuildListener.Failure("The build lost track of its progress. Tap Retry.", BuildListener.OTHER); }
                continue;
            }
            lastReadable = SystemClock.elapsedRealtime();
            if (!json.equals(lastJson)) { lastJson = json; lastChange = lastReadable; }
            JSONObject o;
            try { o = new JSONObject(json); } catch (org.json.JSONException e) { continue; }
            String state = o.optString("state"), reason = o.optString("reason"), step = o.optString("step");
            // a paused or failed job resumed (while the full check holds the packer, or its worker is slow to start), or one the packer still held from an
            // earlier run: its state is not this run's until the packer saves it. A message counts as the file does: a state whose write failed (a full
            // disk) reaches the build by it alone
            if (earlier.contains(o.optLong("updated", -1))) state = "";
            int si = Math.max(0, java.util.Arrays.asList(STEPS).indexOf(step));
            JSONObject st = o.optJSONObject("steps") != null ? o.optJSONObject("steps").optJSONObject(STEPS[si]) : null;
            long done = st == null ? 0 : st.optLong("done"), total = st == null ? 0 : st.optLong("total");
            String stage = st == null ? "" : st.optString("stage");
            if (state.equals("done")) {
                log("prepare: the job is done");
                p.on(100, "Build finished");
                Object j = Upstream.jobRead(file);
                note(Upstream.jobPack(j), o);
                Upstream.addGame(c, j);
                return Upstream.jobPack(j);
            }
            if (state.equals("failed")) {
                String err = o.optString("error");
                log("prepare: the job failed at " + step + ": " + err + (o.optString("error_detail").isEmpty() ? "" : " (" + o.optString("error_detail").replace('\n', ' ') + ")"));
                String fix = o.optString("fix"), cause = o.optString("cause");
                // Not pressed for the player when the cause is theirs to mend (add-on content that was not imported): the build stops with the card's words,
                // and the card offers Import again with the DLC, and Try anyway as a choice
                if (AutoRelax.asks(fix, cause, o.optBoolean("try_anyway"))) {
                    String detail = o.optString("error_detail");
                    throw new BuildListener.Failure(err + (detail.isEmpty() ? "" : "\n" + detail), BuildListener.ADDON_MISSING);
                }
                if (AutoRelax.auto(fix, cause, o.optBoolean("try_anyway"), relaxed)) {   // the panel's "Try anyway…" button, pressed for the player: once
                    relaxed = true;
                    // the failed run's end first (issue #120 review): read from job.json, its "failed" can come before the packer's worker has ended. Its last
                    // save then wrote the job over this Try anyway (the restart failed the same way), a start beside a worker that runs is dropped (the build
                    // waited with nothing running), and the end's message ("failed") came after the restart and ended the build
                    boolean ended = runEnded();
                    if (cancelled) break;   // the build is cancelled (below the loop), and nothing restarts: also when the run's end came in the same moment
                    if (!ended) {
                        log("prepare: the packer still ran the failed job " + RUN_END_MS / 1000 + " s after it failed: not trying anyway now");
                        throw new BuildListener.Failure(dev.nxnative.android.PrepareText.failureSummary(STEP_TEXT[si], err, o.optString("error_detail")), BuildListener.TOOL_STOPPED);
                    }
                    log("prepare: failed with the Try anyway fix (" + err + "): going on with the checks relaxed");
                    Object j = Upstream.jobRead(file);   // as the packer left it
                    add(earlier, o.optLong("updated", -1));   // the failed state as this run saw it, and as the packer left the file: not the new run's
                    add(earlier, updatedOf(j));
                    Upstream.jobTryAnyway(j, true);
                    Upstream.jobSave(j);
                    add(earlier, updatedOf(j));   // the failed run's state is not the new run's
                    fromService = null;
                    lastJson = null;
                    lastChange = SystemClock.elapsedRealtime();
                    p.on(pct(si, done, total), "Trying anyway: " + STEP_TEXT[si]);
                    try { c.startForegroundService(Upstream.packStart(c, file)); } catch (RuntimeException e) { throw new BuildListener.Failure("Android did not let the build start again. Nothing is lost. Open " + appName() + " and tap Resume on the game's card.", BuildListener.CANCELLED); }
                    continue;
                }
                throw new BuildListener.Failure(dev.nxnative.android.PrepareText.failureSummary(STEP_TEXT[si], err, o.optString("error_detail")), BuildListener.TOOL_STOPPED);
            }
            int pct = pct(si, done, total);
            if (state.equals("paused")) {
                // the pause of the run before (its "updated" older than this start): the packer has not taken the job yet. A queue would take it for a pause
                // of this run and hold all its games; after 30 s it counts, as before
                boolean before = o.optLong("updated") < startedAt && System.currentTimeMillis() - startedAt < 30_000;
                if (before) { p.on(pct, STEP_TEXT[si]); continue; }
                if (reason.equals("user") || reason.equals("timeout") || reason.equals("refused") || reason.equals("apk")) {
                    log("prepare: the job paused at " + step + " (" + reason + ")");
                    throw new BuildListener.Failure(pausedText(reason) + " Tap Resume on the game's card to go on.", BuildListener.CANCELLED);
                }
                p.on(pct, "Paused: " + reasonText(reason) + ". " + stepText(si, done, total, stage));
                lastChange = SystemClock.elapsedRealtime();   // a heat or battery pause is not a stall
                continue;
            }
            p.on(pct, stepText(si, done, total, stage));
            if (SystemClock.elapsedRealtime() - lastChange > STALL_MS && !(link != null && link.alive)) {
                log("prepare: no change of the job for " + STALL_MS / 60000 + " min and the packer is not bound: stalled at " + step);
                throw new BuildListener.Failure("The build stopped making progress. Tap Resume on the game's card to go on.", BuildListener.STALLED);
            }
        }
        throw new BuildListener.Failure("Build cancelled. What it made so far is kept. Tap Resume on the game's card to go on.", BuildListener.CANCELLED);
    }

    /**
     * A modded variant build (DESIGN-MODS.md "Code injection"/"ExeFS binary patch", ModdedBuild/ModBuild): the same dump/update as the normal
     * build (no re-import), but its own work folder (workDir) and pack folder (packDir, never the normal build's own — AppStorage.pack(c,
     * variantId) or a build-folder sibling), the rewrite step's extra argv (modArgs, {@code dev.peanuts.build.ModdedBuild.rewriteArgvJoined}),
     * and the normal pack's shader library reused as-is (normalPackShaders, never re-translated — code mods never change shaders). Unlike
     * {@link #run0}, this never adopts or resumes the *normal* job: workDir/packDir are the variant's own, so there is nothing of the normal
     * build's to adopt, and a resume only ever means "this exact variant's own job is still going".
     */
    File runModded(File dump, String titleId, File workDir, File packDir, String modArgs, File normalPackShaders, Progress p) throws IOException {
        logId = titleId;
        try {
            return runModded0(dump, titleId, workDir, packDir, modArgs, normalPackShaders, p);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "modded prepare: upstream reflection", e);
            BuildLogShare.line(c, logId, "modded prepare: upstream reflection: " + e);
            throw new IOException("The build with mods could not start because of a problem in the app. " + MODS_AGAIN);
        } finally {
            unbind();
        }
    }

    /**
     * A finished job's pack gets its note (PackNote): it is of the game file that is there now, and when the job went on by Try anyway after its shaders were
     * found to come from add-on content that was not imported (job.json's "addon_missing": the count and the total, kept from that failure), how many it lacks
     */
    private static void note(File pack, JSONObject job) {
        org.json.JSONArray a = job.optBoolean("try_anyway") ? job.optJSONArray("addon_missing") : null;
        try { PackNote.built(pack, a == null ? 0 : a.optLong(0), a == null ? 0 : a.optLong(1)); }
        catch (IOException e) { Log.w(TAG, "prepare: the pack's note: " + e.getMessage()); }
    }

    /**
     * Whether a built game's pack fits this app as it is, as a build of it would find (Build all that need it, issue #348): not of a game file imported again
     * since, main.orig.bin there, its code and shaders this app's ({@link #current}). A pack that cannot be judged counts as fitting: it is not offered
     */
    boolean upToDate(File packDir, File dump, String titleId) {
        try { return packDir == null || !packDir.isDirectory() || !PackNote.stale(packDir) && !noOrig(packDir) && current(packDir, dump, titleId); }
        catch (IOException | RuntimeException e) { return true; }
    }

    /** Whether the pack fits this APK as it is: startable code of this rewriter (PackCheck.blocking / code) and this APK's shader library (PackCheck.shaders) */
    /** A pack whose main was rewritten before the rewriter kept the file's own bytes of what it changed (main.orig.bin) */
    private static boolean noOrig(File packDir) { return new File(packDir, "main.changed.tsv").isFile() && !new File(packDir, "main.orig.bin").isFile(); }

    private boolean current(File packDir, File dump, String id) throws IOException {
        try {
            Object pack = Upstream.packRead(packDir);
            return pack != null && Upstream.packBlocking(c, pack, dump) == null && !Boolean.FALSE.equals(Upstream.packCode(c, pack, dump)) && Upstream.packShaders(packDir, id) == null && !needsShaderRepair(packDir, id);
        } catch (ReflectiveOperationException | RuntimeException e) { return true; }   // cannot judge: as before
    }

    /** A valid version header alone accepted old packs whose decoder silently omitted a whole shader group (issue #78). */
    private boolean needsShaderRepair(File pack, String titleId) throws IOException {
        if (PackNote.without(pack) != null) return false;   // the player's explicit choice to build without add-on content
        File library = new File(pack, "shaders/v-" + dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN + "/dmsh_vk.lib");
        if (!library.isFile()) return false;   // the normal version check already rebuilds it
        try {
            Object p = Upstream.packRead(pack);
            @SuppressWarnings("unchecked") java.util.List<String> builds = (java.util.List<String>) Upstream.get(p, "builds");
            if (!RuntimeShaderMap.matchesBuild(c.getAssets().open("pack/tools.zip"), titleId, builds)) return false;
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }   // cannot establish that this map describes the pack
        int missing;
        try { missing = RuntimeShaderMap.missingStatic(c.getAssets().open("pack/tools.zip"), titleId, library); }
        catch (IOException e) {   // a library that cannot be read, is another game's or has a cut entry table is what this repair is for: its shaders are made again
            log("prepare: the shader library of " + titleId + " cannot be checked (" + e.getMessage() + "): rebuilding shaders");
            return true;
        }
        if (missing > 0) log("prepare: " + missing + " required static shaders are absent from " + titleId + ": rebuilding shaders");
        return missing > 0;
    }

    private File runModded0(File dump, String titleId, File workDir, File packDir, String modArgs, File normalPackShaders, Progress p)
            throws IOException, ReflectiveOperationException {
        p.on(0, STEP_TEXT[0]);
        String id = titleId;
        try { id = Upstream.titleIdOf(dump); } catch (ReflectiveOperationException | RuntimeException e) { /* DumpCheck says what is wrong */ }
        if (id == null || id.isEmpty()) id = titleId;
        Object r = Upstream.dumpCheck(dump, true, true);   // true/true: the normal build already checked the version; a modded rebuild of the same dump never asks again
        String error = (String) Upstream.get(r, "error");
        if (error != null) throw new BuildListener.Failure("The app cannot use these game files for a build with mods.\n" + error, BuildListener.INVALID_DUMP);
        String rid = Upstream.str(Upstream.get(r, "titleId"));
        if (!rid.isEmpty()) id = rid;
        // The variant's own work folder, not DumpCheck's fixed <dump>/../<id>-work convention (Job.create reads r.work directly)
        Upstream.set(r, "work", workDir);
        File jf = Upstream.jobFileOf(workDir);
        Object job = null;
        long firstRead = -1;   // job.json's "updated" as this run first read it (as in run0)
        if (jf.isFile()) {
            try {
                Object existing = Upstream.jobRead(jf);
                firstRead = updatedOf(existing);
                String s = Upstream.jobState(existing);
                // resume this exact variant's own job where it stopped. A finished one is not the answer to Build / Rebuild with mods: the mod's files or the
                // app's tools may have changed since (the variant's id is only the mods' names, versions and order), so a new job runs, and its rewrite step
                // keeps every module whose key still fits (pack_android.py rewrite: "unchanged, kept") and redoes the others. Returning the finished pack here
                // left a pack an earlier app built wrong as it was, whatever was tapped.
                // Nor is a stopped one whose extra arguments are not this build's (the mod's patch folders changed, or an update of the app writes them
                // another way): resumed, it went on with the folders and the module list it was created with
                String had = Upstream.str(Upstream.call(existing.getClass(), existing, "modArgs", new Class<?>[0]));
                if (!s.equals("done") && had.equals(modArgs == null ? "" : modArgs)) job = existing;
            } catch (ReflectiveOperationException | RuntimeException e) { /* damaged: a fresh job replaces it */ }
        }
        boolean resumedMod = job != null;
        if (job == null) {
            job = Upstream.jobCreate(r, packDir);
            Upstream.jobModArgs(job, modArgs == null ? "" : modArgs);
            Upstream.jobReuseShaders(job, normalPackShaders);
            Upstream.jobSave(job);
            // Not Job.remember: Home's on-device panel follows the remembered job and puts a finished one's pack into the game list by title ID
            // (Ondevice.Panel.addGame), so a remembered variant took over the game's own card (Pokémon Brilliant Diamond's Play pointed at the modded
            // pack, 2026-11-13). This loop polls the job file itself and PackService gets it in the intent
        }
        File file = Upstream.jobFile(job);
        log("modded prepare: job " + file + " (" + (resumedMod ? "resumed, it was " + Upstream.jobState(job) : "new") + "), pack " + packDir);
        BuildLogShare.remember(c, id, workDir);
        // the states from before this run, by their "updated" (issue #120): the job's as this run last read or wrote it (its state until the packer saves it
        // again is the job's from before) and job.json's as this run first read it (a done or failed job of an earlier run of this game the packer still
        // held, which it sends a client that binds: a game's builds share one job.json). A job the packer finished while the build got ready has neither
        Set<Long> earlier = earlier(updatedOf(job), firstRead);
        ownFile = file;   // the service's messages of this job only (Link.onMessage)
        bind();
        waitForPacker(p);
        try {
            c.startForegroundService(Upstream.packStart(c, file));
            jobStarted = true;
        } catch (RuntimeException e) {
            log("PackService start refused (modded): " + e);
            throw new BuildListener.Failure("Android did not let the build with mods start in the background. Nothing is lost. " + MODS_AGAIN, BuildListener.CANCELLED);
        }
        p.on(1, STEP_TEXT[0]);
        long lastChange = SystemClock.elapsedRealtime(), lastReadable = lastChange;
        String lastJson = null;
        while (!cancelled) {
            SystemClock.sleep(1000);
            String json = read(file);
            String fs = fromService;
            if (fs != null && fromServiceAt > lastChange) json = fs;
            if (json == null) {
                if (SystemClock.elapsedRealtime() - lastReadable > 30000) { log("prepare: the modded build's job file went missing: " + file); throw new BuildListener.Failure("The build with mods lost track of its progress. " + MODS_AGAIN, BuildListener.OTHER); }
                continue;
            }
            lastReadable = SystemClock.elapsedRealtime();
            if (!json.equals(lastJson)) { lastJson = json; lastChange = lastReadable; }
            JSONObject o;
            try { o = new JSONObject(json); } catch (org.json.JSONException e) { continue; }
            String state = o.optString("state"), reason = o.optString("reason"), step = o.optString("step");
            // a paused or failed job resumed (while the full check holds the packer, or its worker is slow to start), or one the packer still held from an
            // earlier run: its state is not this run's until the packer saves it. A message counts as the file does: a state whose write failed (a full
            // disk) reaches the build by it alone
            if (earlier.contains(o.optLong("updated", -1))) state = "";
            int si = Math.max(0, java.util.Arrays.asList(STEPS).indexOf(step));
            JSONObject st = o.optJSONObject("steps") != null ? o.optJSONObject("steps").optJSONObject(STEPS[si]) : null;
            long done = st == null ? 0 : st.optLong("done"), total = st == null ? 0 : st.optLong("total");
            String stage = st == null ? "" : st.optString("stage");
            if (state.equals("done")) {
                p.on(100, "Build with mods finished");
                return (File) Upstream.call(job.getClass(), job, "pack", new Class<?>[0]);
            }
            if (state.equals("failed")) {
                throw new BuildListener.Failure(dev.nxnative.android.PrepareText.failureSummary(STEP_TEXT[si], o.optString("error"), o.optString("error_detail"))
                        + "\n" + MODS_AGAIN, BuildListener.TOOL_STOPPED);
            }
            int pct = pct(si, done, total);
            if (state.equals("paused")) {
                if (reason.equals("user") || reason.equals("timeout") || reason.equals("refused") || reason.equals("apk"))
                    throw new BuildListener.Failure(pausedText(reason) + " " + MODS_AGAIN, BuildListener.CANCELLED);
                p.on(pct, "Paused: " + reasonText(reason) + ". " + stepText(si, done, total, stage));
                lastChange = SystemClock.elapsedRealtime();
                continue;
            }
            p.on(pct, stepText(si, done, total, stage));
            if (SystemClock.elapsedRealtime() - lastChange > STALL_MS && !(link != null && link.alive))
                throw new BuildListener.Failure("The build with mods stopped making progress. " + MODS_AGAIN, BuildListener.STALLED);
        }
        throw new BuildListener.Failure("Build cancelled.", BuildListener.CANCELLED);
    }

    private static int pct(int si, long done, long total) {
        return (int) Math.min(99, Math.round(si * 100.0 / STEPS.length + (total > 0 ? Math.min(1.0, done / (double) total) * 100.0 / STEPS.length : 0)));
    }

    private static String stepText(int si, long done, long total, String stage) {
        if (si == 3) {
            if (stage.equals("library") || stage.equals("done")) return "Saving the shaders";
            if (stage.equals("check")) return "Checking the saved shaders";
        }
        if (stage.equals("code") && total > 0) return String.format(Locale.getDefault(), "%s (%d %%)", STEP_TEXT[si], Math.min(100, done * 100 / total));
        if (total > 0) return String.format(Locale.getDefault(), "%s: %,d of %,d%s", STEP_TEXT[si], done, total, si == 2 ? " files" : "");
        return STEP_TEXT[si];
    }

    /** A pause that ends the build, as the card's first sentence: "You paused the build.", "Paused: the app was updated, so the shaders are made again." */
    private static String pausedText(String r) {
        return r.equals("user") ? "You paused the build." : "Paused: " + reasonText(r) + ".";
    }

    private static String reasonText(String r) {
        switch (r) {
            case "user": return "you paused the build";
            case "battery": return "the battery is below 20 %. Plug in the charger to go on";
            case "thermal": return "the phone is too hot. It goes on when it has cooled down. If the phone does not feel hot, tap Go on anyway in the build notification";
            case "timeout": return "Android allows only so much background work a day";
            case "refused": return "Android did not let it start in the background";
            case "apk": return "the app was updated, so the shaders are made again";
            default: return r.isEmpty() ? "stopped" : r;
        }
    }

    private String appName() {
        try { return c.getString(c.getApplicationInfo().labelRes); } catch (RuntimeException e) { return "the app"; }
    }

    /**
     * The job's "updated" as this run last read or wrote it (Job.save writes it at every save), -1 when unknown. Not job.json read again at the start: a job the
     * packer finished while the build got ready (one it ran on after a build was cancelled) would be taken for the state from before, and the packer writes
     * nothing more for a done job
     */
    private static long updatedOf(Object job) {
        try { return ((JSONObject) Upstream.get(job, "o")).optLong("updated", -1); } catch (ReflectiveOperationException | RuntimeException e) { return -1; }
    }

    /** The "updated" of states from before a run, the unknown ones (-1) left out: a state without one counts */
    private static Set<Long> earlier(long... updated) {
        Set<Long> s = new HashSet<>();
        for (long u : updated) if (u > 0) s.add(u);
        return s;
    }

    private static void add(Set<Long> earlier, long updated) { if (updated > 0) earlier.add(updated); }

    /**
     * Waits until the packer has ended the job's run, at most RUN_END_MS: its worker gone (the last message of this build's job says so; PackService.finished
     * sends one), or no packer to wait for (not bound, or its process ended). False when it still runs then, or the build was cancelled meanwhile
     */
    private boolean runEnded() {
        long until = SystemClock.elapsedRealtime() + RUN_END_MS;
        while (true) {
            Link l = link;
            if (l == null || l.service == null || l.heard && !l.alive) return true;
            if (cancelled || SystemClock.elapsedRealtime() > until) return false;
            SystemClock.sleep(100);
        }
    }

    private static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    // ---- The Messenger link to PackService (Ondevice.Panel's protocol: MSG_REGISTER with replyTo → MSG_STATE with "job" JSON and "alive") ----

    private void bind() {
        main.post(() -> {
            if (link != null) return;
            link = new Link();
            try {
                if (!c.bindService(Upstream.packBind(c), link, Context.BIND_AUTO_CREATE)) { Log.w(TAG, "PackService: bind refused"); link = null; }
            } catch (ClassNotFoundException | RuntimeException e) {
                Log.w(TAG, "PackService: bind " + e);
                link = null;
            }
        });
    }

    private void unbind() {
        main.post(() -> {
            if (link == null) return;
            Link l = link;
            link = null;
            if (l.service != null) {
                try { Message m = Message.obtain(null, Upstream.MSG_UNREGISTER); m.replyTo = l.client; l.service.send(m); } catch (RemoteException e) { /* gone */ }
            }
            try { c.unbindService(l); } catch (RuntimeException e) { /* not bound */ }
        });
    }

    private final class Link implements ServiceConnection {
        final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        volatile Messenger service;   // read by the build's thread too (runEnded)
        volatile boolean alive;
        volatile boolean heard;   // a message of this build's job came: alive is then the packer's word (runEnded)

        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            Message m = Message.obtain(null, Upstream.MSG_REGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { service = null; }
        }

        @Override public void onServiceDisconnected(ComponentName n) { service = null; alive = false; otherRuns = false; }

        boolean onMessage(Message m) {
            if (m.what != Upstream.MSG_STATE) return false;
            Bundle d = m.getData();
            String j = d.getString("job"), f = d.getString("file");
            boolean on = d.getBoolean("alive");
            Prepare.this.heard = true;   // any job's message: the packer is bound and has said what it runs (waitForPacker); Link.heard is this build's job's only (runEnded)
            // the service tells every client of the job it runs: another game's (the queue's game before this one) is no state of this run's job
            File own = ownFile;
            if (f != null && own != null && !new File(f).getAbsolutePath().equals(own.getAbsolutePath())) {
                otherRuns = on;
                String n = "";
                try { n = j == null ? "" : new JSONObject(j).optString("name"); } catch (org.json.JSONException e) { /* no name: said without one */ }
                otherName = n;
                return true;
            }
            otherRuns = false;
            if (j != null) { fromService = j; fromServiceAt = SystemClock.elapsedRealtime(); }
            alive = on;
            heard = true;
            return true;
        }
    }

    /**
     * The packer runs one job, and a start that comes while it runs another is dropped (this build would then wait for a job that never starts). After the
     * bind its first message says what it runs: while that is another job (the queue's game before, which stops within seconds of its Stop, or upstream's own
     * Prepare), this waits, with the card saying so. No message within 3 s: it runs none
     */
    private void waitForPacker(Progress p) throws IOException {
        long until = SystemClock.elapsedRealtime() + 3000;
        while (!heard && !cancelled && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        boolean said = false;
        while (otherRuns && !cancelled && link != null) {
            if (!said) { log("prepare: the packer still runs another job (" + otherName + "): this one starts when it has ended"); said = true; }
            p.on(0, otherName.isEmpty() ? "Waiting for another game's build to finish" : "Waiting for " + otherName + " to finish building");
            SystemClock.sleep(1000);
        }
        if (cancelled) throw new BuildListener.Failure("Build cancelled.", BuildListener.CANCELLED);
        if (said) log("prepare: the packer is free");
    }
}
