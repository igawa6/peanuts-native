package dev.peanuts.ctl;

import android.content.Context;

import dev.nxnative.android.BuildConfig;
import dev.peanuts.GameReturn;
import dev.peanuts.Installer;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuildPipeline;
import dev.peanuts.settings.CompanionSection;
import dev.peanuts.ui.CtlPort;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * &lt;app files&gt;/ctl/status.json (the app's folder on shared storage, which adb reads without root): what the app is doing, for a script. Written whole each
 * time, to a temporary file that is then renamed over the old one, so a reader never sees half a file. A fact the app cannot tell for sure is left out or
 * null, never guessed. Everything here runs on the control interface's own thread ({@link Ctl}); the screen part is read on the main thread and handed in.
 */
final class Status {
    static final int SCHEMA = 1;
    private static final long LIBRARY_MAX_AGE_MS = 30_000;   // the rows read every game's pack: not at every 2 s write

    private static long seq;
    private static List<Map<String, Object>> library;
    private static long libraryAt;
    /** Whether a build, a Prepare job or a game ran at the last write: the file is then written again every 2 s ({@link Ctl}) */
    static volatile boolean busy;

    private Status() {}

    static File dir(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "ctl");
    }

    /** screen: Ctl.screenNow() (main thread). results: the newest first. fresh: the library rows are read again whatever their age */
    static void write(Context c, Map<String, Object> screen, List<Map<String, Object>> results, boolean fresh) {
        File dir = dir(c);
        if (dir == null) return;   // shared storage is not there now
        Map<String, Object> job = job(c), build = BuildPipeline.snapshot(), game = game(c);
        long now = System.currentTimeMillis();
        if (fresh || library == null || now - libraryAt > LIBRARY_MAX_AGE_MS) { library = library(c); libraryAt = now; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("written", now);
        m.put("seq", ++seq);
        m.put("app", app(c));
        m.put("screen", screen);
        m.put("library", building(library, job, build));
        m.put("library_read", libraryAt);
        m.put("job", job);
        m.put("build", build);
        m.put("game", game);
        m.put("last_result", results.isEmpty() ? null : results.get(0));
        m.put("results", results);
        busy = build != null && Boolean.TRUE.equals(build.get("running")) || job != null && "running".equals(job.get("state")) && Boolean.TRUE.equals(job.get("process_alive"))
                || game != null && Boolean.TRUE.equals(game.get("running"));
        save(new File(dir, "status.json"), Json.write(m));
    }

    /** The list command: the library rows alone, in ctl/library.json */
    static void writeLibrary(Context c) {
        File dir = dir(c);
        if (dir == null || library == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("written", System.currentTimeMillis());
        m.put("library", library);
        save(new File(dir, "library.json"), Json.write(m));
    }

    private static void save(File f, String text) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
            if (!tmp.renameTo(f)) throw new IOException("rename to " + f.getName());
        } catch (IOException e) {
            android.util.Log.w(Ctl.TAG, "status not written: " + e);
            tmp.delete();
        }
    }

    // ---- The app ----

    private static Map<String, Object> app(Context c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package", c.getPackageName());
        m.put("version", BuildConfig.VERSION_NAME);
        try { m.put("build_id", Up.buildId()); } catch (ReflectiveOperationException | RuntimeException e) { m.put("build_id", null); }   // as Home's header shows it
        m.put("commit", BuildConfig.NX_COMMIT.isEmpty() ? null : BuildConfig.NX_COMMIT);
        m.put("keys", PeanutsPrefs.hasKeys(c));
        m.put("firmware", PeanutsPrefs.hasFonts(c));
        Map<String, Object> folders = new LinkedHashMap<>();
        folders.put("game", folder(PeanutsPrefs.gameDir(c)));
        folders.put("update", folder(PeanutsPrefs.updateDir(c)));
        folders.put("dlc", folder(PeanutsPrefs.dlcDir(c)));
        folders.put("build", folder(PeanutsPrefs.buildDir(c)));
        List<Map<String, Object>> buildFolders = new ArrayList<>();
        for (String path : PeanutsPrefs.buildDirs(c)) buildFolders.add(folder(path));
        folders.put("build_folders", buildFolders);
        File ext = c.getExternalFilesDir(null);
        folders.put("app", ext == null ? null : folder(ext.getPath()));
        m.put("folders", folders);
        try { m.put("gpu_driver", Up.gpuDriver(c)); } catch (ReflectiveOperationException | RuntimeException e) { m.put("gpu_driver", null); }
        m.put("gate", "dump_permission");
        return m;
    }

    /** A folder with the bytes free on its volume; free is null while the folder cannot be read (its volume is out, or All files access is missing) */
    private static Map<String, Object> folder(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("free", f.isDirectory() ? (Object) f.getUsableSpace() : null);
        return m;
    }

    // ---- The library ----

    private static List<Map<String, Object>> library(Context c) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (CtlPort.Row r : CtlPort.rows(c)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title_id", r.titleId);
                m.put("name", r.name);
                m.put("state", r.state);
                m.put("listed", r.listed);
                m.put("game", r.game);
                m.put("pack", r.pack);
                Map<String, Object> comp = companion(r.companionRoot, r.titleId);
                m.put("has_companion", comp != null);
                m.put("companion", comp);
                out.add(m);
            }
        } catch (RuntimeException e) {
            android.util.Log.w(Ctl.TAG, "library not read: " + e);
        }
        return out;
    }

    /**
     * The game's companion: the package beside its pack whose manifest names the title, as the runtime picks it (CompanionSection.packageFor; the app's own
     * packs share one folder of packages), with its version from installed.json; null when the folder has none for this title
     */
    private static Map<String, Object> companion(File root, String titleId) {
        String pkg = CompanionSection.packageFor(root, titleId);
        if (pkg == null) return null;
        String v = Installer.installedVersion(root, pkg);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package", pkg);
        m.put("version", v == null || v.equals("?") ? null : v);   // "?": installed, its version not recorded
        return m;
    }

    /** The rows with the game that is built now marked building, as its cover is: the library's own build, or upstream's Prepare job. The kept rows are not changed */
    private static List<Map<String, Object>> building(List<Map<String, Object>> rows, Map<String, Object> job, Map<String, Object> build) {
        Object s = job == null ? null : job.get("state");
        String a = job != null && ("running".equals(s) || "paused".equals(s) || "pending".equals(s)) ? String.valueOf(job.get("title_id")) : null;
        String b = build != null && Boolean.TRUE.equals(build.get("running")) ? String.valueOf(build.get("title_id")) : null;
        if (a == null && b == null) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.get("title_id"));
            if (id.equalsIgnoreCase(a) || id.equalsIgnoreCase(b)) { r = new LinkedHashMap<>(r); r.put("state", "building"); }
            out.add(r);
        }
        return out;
    }

    // ---- Upstream's Prepare job (job.json) ----

    private static Map<String, Object> job(Context c) {
        Object j = Up.job(c);
        if (j == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            File f = (File) Up.get(j, "file");
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            String state = o.optString("state"), step = o.optString("step");
            boolean alive = GameReturn.processAlive(c, ":pack", -1);
            m.put("title_id", o.optString("title_id"));
            m.put("name", o.optString("name"));
            m.put("state", state);   // pending, running, paused, failed, done
            m.put("process_alive", alive);
            if (state.equals("running") && !alive) m.put("interrupted", true);   // the service's process is gone while the file still says running
            if (state.equals("paused")) m.put("paused_reason", o.optString("reason"));
            m.put("step", step);
            Map<String, Object> counts = new LinkedHashMap<>();
            JSONObject steps = o.optJSONObject("steps");
            if (steps != null) for (java.util.Iterator<String> it = steps.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject s = steps.optJSONObject(k);
                if (s == null) continue;
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("state", s.optString("state"));
                one.put("done", s.optLong("done"));
                one.put("total", s.optLong("total"));
                counts.put(k, one);
            }
            m.put("counts", counts);
            m.put("percent", Math.round(((Number) Up.call(j, "overall")).doubleValue() * 100));
            long eta = ((Number) Up.call(j, "shadersEta")).longValue();
            String etaText = Up.str(Up.call(Up.k("Job"), null, "etaText", new Class<?>[] {long.class}, eta));
            m.put("eta_s", eta >= 0 ? (Object) eta : null);
            m.put("eta", etaText.isEmpty() ? null : etaText);
            m.put("text", Up.str(Up.call(j.getClass(), j, "statusText", new Class<?>[] {boolean.class}, alive)));   // the card's line
            if (state.equals("failed")) {
                m.put("error", o.optString("error"));
                m.put("error_detail", Up.jobText(j, "errorDetail"));
                m.put("fix", Up.jobText(j, "fix"));
                m.put("cause", o.has("cause") ? o.optString("cause") : null);   // addon_not_imported: the build stops and asks instead of trying anyway by itself
            }
            m.put("try_anyway", o.optBoolean("try_anyway", false));
            m.put("updated", o.optLong("updated"));
            m.put("job_file", f.getPath());
            File log = new File(new File(f.getParentFile(), "logs"), step + ".log");
            m.put("log", log.isFile() ? log.getPath() : null);
        } catch (IOException | org.json.JSONException | ReflectiveOperationException | RuntimeException e) {
            m.put("unreadable", String.valueOf(e.getMessage()));
        }
        return m;
    }

    // ---- The game ----

    static Map<String, String> record(Context c) {
        File f = new File(c.getFilesDir(), "last-exit.txt");
        try { return ExitRecord.parse(f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null); }
        catch (IOException e) { return ExitRecord.parse(null); }
    }

    private static Map<String, Object> game(Context c) {
        Map<String, String> rec = record(c);
        int pid = ExitRecord.pid(rec);
        boolean alive = pid > 0 && GameReturn.processAlive(c, ":game", pid);
        Map<String, Object> g = ExitRecord.game(rec, alive);
        Ctl.Launch l = Ctl.launched;
        // the pack is known only for a start this launcher process made itself (Home's Play): the run's record does not hold it
        if (g != null && alive && l != null && l.titleId.equalsIgnoreCase(String.valueOf(g.get("title_id"))) && g.get("since") instanceof Long && l.time <= (Long) g.get("since")
                && (Long) g.get("since") - l.time < 60_000) {
            g.put("pack", l.pack);
            g.put("mods_pack", dev.peanuts.build.ModdedBuild.isVariantPack(new File(l.pack)));
        }
        return g;
    }
}
