package dev.peanuts.build;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import android.os.Handler;
import android.os.Looper;

/**
 * The modded-variant counterpart of {@link BuildPipeline} ("Build with mods" in the game's Mods section, DESIGN-MODS.md "Code injection"): hands a {@link
 * ModBuildRequest} to {@link ModBuildService} (its own foreground service, never {@link BuildService} — the normal build/import/companion pipeline
 * is untouched by this feature) and keeps a {@link BuildListener} for it. Its own id space and listener map, separate from {@link BuildPipeline}'s,
 * so a normal build and a modded-variant build can in principle run at once without fighting over one "running" flag (today each service still
 * only runs one job at a time internally; this class does not add cross-service exclusion beyond what each service already enforces on its own).
 */
public final class ModBuildPipeline {
    private static final String TAG = Prepare.TAG;
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final Map<Integer, BuildListener> LISTENERS = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int running;

    private ModBuildPipeline() {}

    /** Starts the modded build; returns its id (for {@link #cancel}), or 0 when the request is not buildable (the listener is told why) */
    public static int start(Context ctx, ModBuildRequest req, BuildListener l) {
        String why = check(req);
        if (why != null) { if (l != null) MAIN.post(() -> l.done(false, why, BuildListener.OTHER)); return 0; }
        int id = NEXT.getAndIncrement();
        if (l != null) LISTENERS.put(id, l);
        running = id;
        try {
            ctx.getApplicationContext().startForegroundService(ModBuildService.intent(ctx, id, req));
        } catch (RuntimeException e) {
            Log.w(TAG, "ModBuildService start: " + e);
            LISTENERS.remove(id);
            running = 0;
            if (l != null) MAIN.post(() -> l.done(false, "Android did not let the build with mods start. Try again.", BuildListener.OTHER));
            return 0;
        }
        return id;
    }

    /** Whether a modded build is running now (its id), else 0 */
    public static int running() { return running; }

    public static void cancel(Context ctx) {
        try { ctx.startService(new android.content.Intent(ctx, ModBuildService.class).setAction(ModBuildService.ACTION_CANCEL)); } catch (RuntimeException e) { Log.w(TAG, "cancel: " + e); }
    }

    private static String check(ModBuildRequest r) {
        if (r == null) return "Nothing to build.";
        if (r.titleId == null || !r.titleId.matches("[0-9A-Fa-f]{16}")) return "The app cannot tell which game this is. Rescan your games, then try again.";
        if (r.dump == null || !r.dump.exists()) return "The game files are missing. Build the game without mods first.";
        if (r.normalPack == null || !new File(r.normalPack, "pack.tsv").isFile()) return "The game is not built yet. Build it without mods first, then Build with mods.";
        if (r.variantId == null || r.variantId.isEmpty()) return "No mods are enabled that need a build.";
        // without them the job is a build of the game itself: into the variant's folder, and remembered as the game's own job (PackService keeps a job with
        // mod arguments out of Job.remember, and only such a job)
        if (r.modArgs == null || r.modArgs.isEmpty()) return "The mods that are on change nothing a build can use.";
        return null;
    }

    static void progress(int id, String step, int percent, String detail) {
        BuildListener l = LISTENERS.get(id);
        if (l != null) MAIN.post(() -> l.progress(step, percent, detail));
    }

    static void finish(int id, boolean ok, String message, String code) {
        if (id == running) running = 0;
        BuildListener l = LISTENERS.remove(id);
        if (l != null) MAIN.post(() -> l.done(ok, message, code));
    }
}
