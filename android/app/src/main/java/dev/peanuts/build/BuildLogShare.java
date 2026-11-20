package dev.peanuts.build;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Share build log, the Android half ({@link BuildLogBundle} is the pure one): {@link #make} writes one game's bundle,
 * build-log-&lt;title ID&gt;-&lt;time&gt;.zip, with
 * <ul>
 * <li>build/: the game's build log ({@link BuildLog}: what the build services did, the import included);</li>
 * <li>job/&lt;work folder&gt;/ and mods/&lt;work folder&gt;/: each on-device job's job.json, its env.txt (secrets' values hidden) and logs/*.log;</li>
 * <li>logcat.txt: the app's own lines of the build's tags and of crashes (Android gives an app only its own);</li>
 * <li>exits/: the newest "not responding" trace of each of the app's processes, when Android kept one;</li>
 * <li>summary.txt: the app, the phone, the build's and the jobs' states, the worker plan, free space, how each of the app's processes ended last
 *     (ApplicationExitInfo: low memory, a crash, not responding), and what was cut.</li>
 * </ul>
 * Never in it: keys, game files, .nup files, saves, packs or the split's outputs (only the files named above are read; {@link BuildLogBundle#allowed}
 * refuses anything else). The jobs are found the way Prepare finds them (DumpCheck.workOf of the game file), from the launcher's list, the job the panel
 * remembers, the folders each build of this app noted ({@link #remember}) and the build folder; see {@link BuildLogBundle#jobs}.
 */
public final class BuildLogShare {
    private static final String TAG = Prepare.TAG, PREFS = "peanuts_buildlog";
    /** The tags whose lines go into logcat.txt: the build's, the packer's, the launcher's, and the platform's crash reports of the app's processes */
    private static final Set<String> TAGS = new LinkedHashSet<>(Arrays.asList("peanuts.build", "nxpack", "peanuts", "peanuts-ctl", "AndroidRuntime", "DEBUG", "libc", "System.err"));
    private static final Pattern LOGCAT = Pattern.compile("^\\S+ \\S+\\s+\\d+\\s+\\d+ [VDIWEF] ([^:]*?)\\s*: .*");
    static final int LOGCAT_LINES = 3000, EXITS_PER_PROCESS = 5;

    private BuildLogShare() {}

    /** Where the build logs are: &lt;app files&gt;/build-logs on shared storage (adb reads it), the app's private files when that is not there */
    public static File dir(Context c) {
        File ext = c.getExternalFilesDir(null);
        return new File(ext != null ? ext : c.getFilesDir(), "build-logs");
    }

    /** Where bundles for sharing are written (the provider serves this folder only) */
    public static File outDir(Context c) { return new File(c.getCacheDir(), "build-log"); }

    /** One line in the game's build log (no logcat line: the caller has its own) */
    public static void line(Context c, String titleId, String text) { BuildLog.append(dir(c), titleId, text); }

    /** A work folder a build of this game used, for {@link #make} to look in later (the job may be forgotten, the game not listed) */
    public static void remember(Context c, String titleId, File work) {
        String t = BuildLog.title(titleId);
        if (t == null || work == null) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> l = new ArrayList<>(Arrays.asList(p.getString(t, "").split("\n")));
        l.remove("");
        l.remove(work.getPath());
        l.add(0, work.getPath());
        while (l.size() > 8) l.remove(l.size() - 1);
        p.edit().putString(t, String.join("\n", l)).apply();
    }

    private static List<File> remembered(Context c, String t) {
        List<File> out = new ArrayList<>();
        for (String s : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(t, "").split("\n")) if (!s.isEmpty()) out.add(new File(s));
        return out;
    }

    /** What {@link #make} made */
    public static final class Made {
        public File zip;
        /** false: nothing of a build of this game was found (no build log, no job): no zip */
        public boolean anything;
        public int files, jobs, modJobs;
        public long bytes, raw;
        public boolean cut;
        public String name = "";
    }

    /**
     * Writes the game's bundle into outDir (made on a worker thread: it reads files and asks logcat). name: the game's name for the summary, or null.
     * Throws with a sentence when it cannot be written; a part that cannot be read is said in the summary instead.
     */
    public static Made make(Context c, String titleId, String name, File outDir) throws IOException {
        String t = BuildLog.title(titleId);
        if (t == null) throw new IOException("No title ID.");
        long now = System.currentTimeMillis();
        BuildLogBundle.clean(outDir, now, BuildLogBundle.MAX_AGE, BuildLogBundle.KEEP - 1);
        Made m = new Made();
        File logDir = dir(c);
        File log = BuildLog.file(logDir, t), older = BuildLog.older(logDir, t);
        List<String> where = new ArrayList<>();
        List<BuildLogBundle.Job> jobs = BuildLogBundle.jobs(t, workDirs(c, t, where), jobFiles(c));
        for (BuildLogBundle.Job j : jobs) if (j.mods) m.modJobs++; else m.jobs++;
        m.anything = log.isFile() || older.isFile() || !jobs.isEmpty();
        m.name = name != null && !name.isEmpty() ? name : nameOf(jobs, log, t);
        if (!m.anything) return m;
        File out = new File(outDir, BuildLogBundle.zipName(t, now));
        try (BuildLogBundle.Zip z = new BuildLogBundle.Zip(out)) {
            z.file("build/" + older.getName(), older);
            z.file("build/" + log.getName(), log);
            for (BuildLogBundle.Job j : jobs) {
                String p = (j.mods ? "mods/" : "job/") + j.label + "/";
                z.file(p + "job.json", j.file);
                File env = new File(j.dir, "env.txt");
                if (env.isFile()) {
                    String e = BuildLogBundle.head(env, 64 << 10);
                    if (e != null) z.text(p + "env.txt", BuildLogBundle.redactEnvText(e));
                }
                File[] logs = j.logs().listFiles((d, n) -> n.endsWith(".log"));
                if (logs != null) {
                    Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());   // the newest step first: when the bundle is full, the oldest steps are left out
                    for (File f : logs) z.file(p + "logs/" + f.getName(), f);
                }
            }
            String lc = logcat();
            z.text("logcat.txt", lc);
            List<String> exits = new ArrayList<>();
            Map<String, String> traces = new LinkedHashMap<>();
            exits(c, exits, traces);
            for (Map.Entry<String, String> e : traces.entrySet()) z.text("exits/" + e.getKey(), e.getValue());
            z.text("summary.txt", summary(c, t, m.name, log, older, jobs, where, exits, z));
            File done = z.finish();
            m.zip = done;
            m.files = z.files;
            m.raw = z.raw;
            m.bytes = done.length();
            m.cut = !z.cut.isEmpty() || !z.left.isEmpty();
        }
        android.util.Log.i(TAG, "build log of " + t + ": " + m.zip + " (" + m.files + " files, " + m.bytes + " bytes)");
        return m;
    }

    /** The work folders to look in, and in where a line for each kind of place (the summary says where it looked) */
    private static List<File> workDirs(Context c, String t, List<String> where) {
        Set<File> w = new LinkedHashSet<>();
        for (File f : remembered(c, t)) w.add(f);
        if (!w.isEmpty()) where.add("the work folders this app's builds noted: " + w.size());
        try {
            for (String[] g : Upstream.listedGames(c, t)) {
                File game = new File(g[1]);
                w.add(Upstream.workOf(game, t));
                where.add("the launcher's list: " + g[1]);
            }
        } catch (ReflectiveOperationException | RuntimeException e) { where.add("the launcher's list could not be read: " + e); }
        for (File b : dev.peanuts.PeanutsPrefs.buildGameDirs(c, t)) {
            w.add(new File(b, t + "-work"));
            where.add("a build folder: " + b);
        }
        // a .nup the build imported beside the game (<game folder>/<name>/<name>.nup) before any of the above knew it: one level of the game folder
        String gd = dev.peanuts.PeanutsPrefs.gameDir(c);
        if (gd != null) {
            File root = new File(gd);
            w.add(new File(root, t + "-work"));
            File[] sub = root.listFiles(File::isDirectory);
            if (sub != null) for (File s : sub) { File x = new File(s, t + "-work"); if (x.isDirectory()) w.add(x); }
            where.add("the game folder and its folders: " + gd);
        }
        return new ArrayList<>(w);
    }

    /** The job the Prepare card shows (Job.remembered: the "ondevice" prefs' "job") */
    private static List<File> jobFiles(Context c) {
        List<File> out = new ArrayList<>();
        String p = Upstream.prefs(c, "ondevice").getString("job", null);
        if (p != null) out.add(new File(p));
        return out;
    }

    private static String nameOf(List<BuildLogBundle.Job> jobs, File log, String t) {
        for (BuildLogBundle.Job j : jobs) {
            String json = BuildLogBundle.head(j.file, 1 << 20);
            String n = json == null ? null : BuildLogBundle.field(json, "name");
            if (n != null && !n.isEmpty()) return n;
        }
        String s = BuildLog.lastStart(BuildLog.tail(log, 256 << 10));
        Matcher m = s == null ? null : Pattern.compile("build of (.*) \\(" + t + "\\)").matcher(s);
        return m != null && m.find() ? m.group(1) : t;
    }

    // ---- logcat ----

    /** The app's own logcat lines of the build's tags and of crashes, the newest LOGCAT_LINES; a sentence when logcat cannot be read */
    static String logcat() {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
            Deque<String> keep = new ArrayDeque<>();
            long all = 0;
            Process fp = p;
            Thread killer = new Thread(() -> { try { if (!fp.waitFor(10, TimeUnit.SECONDS)) fp.destroy(); } catch (InterruptedException e) { fp.destroy(); } }, "peanuts-logcat-limit");
            killer.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    all++;
                    Matcher m = LOGCAT.matcher(l);
                    if (!(m.matches() ? TAGS.contains(m.group(1).trim()) : l.startsWith("--------- beginning of crash"))) continue;
                    keep.addLast(l);
                    if (keep.size() > LOGCAT_LINES) keep.removeFirst();
                }
            }
            StringBuilder b = new StringBuilder("# The app's own logcat lines (Android shows an app only its own) of the tags " + String.join(", ", TAGS)
                    + ": the newest " + keep.size() + " of " + all + " lines read. Android's buffer is shared by every app and on a busy phone reaches back only minutes;"
                    + " the build's own log (build/) is the full record.\n");
            for (String l : keep) b.append(l).append('\n');
            return b.toString();
        } catch (IOException | RuntimeException e) {
            return "# logcat could not be read: " + e + "\n";
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ---- How the app's processes ended ----

    /** The newest exits of each of the app's processes into lines, and the newest ANR trace of each into traces (file name, text) */
    static void exits(Context c, List<String> lines, Map<String, String> traces) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> l;
        try { l = am == null ? null : am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 0); }
        catch (RuntimeException e) { lines.add("Android did not say: " + e); return; }
        if (l == null || l.isEmpty()) { lines.add("Android has no record of an ended process of this app."); return; }
        Map<String, List<ApplicationExitInfo>> by = new LinkedHashMap<>();
        for (ApplicationExitInfo e : l) by.computeIfAbsent(e.getProcessName() == null ? "?" : e.getProcessName(), k -> new ArrayList<>()).add(e);
        for (Map.Entry<String, List<ApplicationExitInfo>> p : by.entrySet()) {
            List<ApplicationExitInfo> es = new ArrayList<>(p.getValue());
            es.sort(Comparator.comparingLong(ApplicationExitInfo::getTimestamp).reversed());
            String proc = p.getKey(), shortName = proc.startsWith(c.getPackageName()) ? proc.substring(c.getPackageName().length()) : proc;
            lines.add((shortName.isEmpty() ? "main" : shortName) + " (" + proc + "), " + es.size() + " kept by Android:");
            boolean traced = false;
            for (int i = 0; i < Math.min(EXITS_PER_PROCESS, es.size()); i++) {
                ApplicationExitInfo e = es.get(i);
                String meaning = BuildLogBundle.meaning(e.getReason(), e.getStatus());
                lines.add("  " + BuildLogBundle.time(e.getTimestamp()) + ": " + BuildLogBundle.reason(e.getReason()) + (meaning.isEmpty() ? "" : " (" + meaning + ")")
                        + " status " + e.getStatus() + ", importance " + BuildLogBundle.importance(e.getImportance()) + ", pss " + e.getPss() / 1024 + " MB, rss " + e.getRss() / 1024
                        + " MB, pid " + e.getPid() + (e.getDescription() == null || e.getDescription().isEmpty() ? "" : ", \"" + e.getDescription().replace('\n', ' ') + "\""));
                if (!traced && e.getReason() == ApplicationExitInfo.REASON_ANR) {
                    traced = true;
                    try (InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            byte[] b = new byte[512 << 10];
                            int n = 0;
                            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                            traces.put("anr-" + (shortName.isEmpty() ? "main" : shortName.replace(":", "")) + ".txt", new String(b, 0, n, StandardCharsets.UTF_8));
                        }
                    } catch (IOException | RuntimeException x) { lines.add("    its trace could not be read: " + x.getMessage()); }
                }
            }
        }
    }

    // ---- summary.txt ----

    private static String summary(Context c, String t, String name, File log, File older, List<BuildLogBundle.Job> jobs, List<String> where, List<String> exits, BuildLogBundle.Zip z) {
        long now = System.currentTimeMillis();
        String meminfo = BuildMemory.readMeminfo();
        long total = BuildMemory.meminfoMb(meminfo, "MemTotal"), avail = BuildMemory.meminfoMb(meminfo, "MemAvailable");
        BuildLogBundle.Text s = new BuildLogBundle.Text();
        s.line("Peanuts Native build log of " + name + " (" + t + ")");
        s.kv("made", BuildLogBundle.time(now));
        s.line("Attach this .zip to the issue as it is. It holds logs and this summary only: no keys, game files, saves or packs.");

        s.head("App");
        s.kv("package", c.getPackageName());
        s.kv("version", dev.nxnative.android.BuildConfig.VERSION_NAME + " (" + dev.nxnative.android.BuildConfig.VERSION_CODE + ")");
        s.kv("build tag", Upstream.buildTag(c));
        s.kv("commit", dev.nxnative.android.BuildConfig.NX_COMMIT + dev.nxnative.android.BuildConfig.NX_DIRTY + " (" + dev.nxnative.android.BuildConfig.NX_BRANCH + ")");
        s.kv("pack engine", dev.nxnative.android.BuildConfig.NX_PACK_ENGINE + ("fake".equals(dev.nxnative.android.BuildConfig.NX_PACK_ENGINE) ? " (simulated: a test build, its step logs are made up)" : ""));
        s.kv("shader chains", dev.nxnative.android.BuildConfig.NX_DMSH_CHAIN + " / " + dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN + ", translator " + dev.nxnative.android.BuildConfig.NX_XLATE_SOURCE);
        try { s.kv("installed", BuildLogBundle.time(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime)); } catch (Exception e) { s.kv("installed", "?"); }

        s.head("Phone");
        s.kv("model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        s.kv("chip", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        s.kv("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        s.kv("memory", "total " + total + " MB, available " + avail + " MB, swap " + BuildMemory.meminfoMb(meminfo, "SwapFree") + " of " + BuildMemory.meminfoMb(meminfo, "SwapTotal") + " MB free");
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.kv("low memory now", mi.lowMemory + " (Android's threshold " + mi.threshold / 1048576 + " MB)");
            boolean pack = false, game = false;
            List<ActivityManager.RunningAppProcessInfo> rp = am.getRunningAppProcesses();
            if (rp != null) for (ActivityManager.RunningAppProcessInfo p : rp) { if (p.processName.endsWith(":pack")) pack = true; if (p.processName.endsWith(":game")) game = true; }
            s.kv("processes now", "main" + (pack ? ", :pack (the packer)" : "") + (game ? ", :game" : ""));
        }

        s.head("Build in this app process");
        Map<String, Object> snap = BuildPipeline.snapshot();
        if (snap == null) s.line("None since the app started (a build before it ended with the app's process, or was in an earlier start of the app).");
        else for (Map.Entry<String, Object> e : snap.entrySet()) s.kv(e.getKey(), e.getKey().equals("started") || e.getKey().equals("ended") ? BuildLogBundle.time(((Number) e.getValue()).longValue()) : e.getValue());

        s.head("Build log");
        String text = BuildLog.tail(log, 256 << 10);
        s.kv("file", log.isFile() ? log.getName() + ", " + BuildLogBundle.size(log.length()) + (older.isFile() ? "; older runs in " + older.getName() : "") : "none (no build of this game since the app had build logs)");
        String start = BuildLog.lastStart(text), end = BuildLog.lastEnd(text);
        if (start != null) {
            s.kv("newest run", start);
            boolean runs = snap != null && Boolean.TRUE.equals(snap.get("running")) && t.equalsIgnoreCase(String.valueOf(snap.get("title_id")));
            s.kv("its end", end != null ? end : runs ? "none yet: it runs now" : "NONE: the app's process ended during this build (see Process exits below)");
        }

        s.head("Jobs (" + jobs.size() + ")");
        s.kv("looked in", where.isEmpty() ? "-" : String.join("\n", where));
        if (jobs.isEmpty()) s.line("No job folder was found: the build stopped before Prepare, or the job's working files were deleted.");
        int memLevel = 0;
        for (BuildLogBundle.Job j : jobs) {
            s.line("");
            s.kv((j.mods ? "job with mods" : "job") + " in zip", (j.mods ? "mods/" : "job/") + j.label);
            s.kv("folder", j.dir);
            String json = BuildLogBundle.head(j.file, 1 << 20);
            if (json == null) { s.kv("job.json", j.file.isFile() ? "could not be read" : "missing (only its logs are there)"); continue; }
            try {
                JSONObject o = new JSONObject(json);
                s.kv("game", o.optString("name") + ", version " + o.optLong("version") + (o.optBoolean("try_anyway") ? ", Try anyway" : ""));
                s.kv("state", o.optString("state") + (o.optString("reason").isEmpty() ? "" : " (" + o.optString("reason") + ")") + ", step " + o.optString("step"));
                if (!o.optString("error").isEmpty()) s.kv("error", o.optString("error"));
                if (!o.optString("error_detail").isEmpty()) s.kv("error detail", o.optString("error_detail"));
                if (o.has("fix")) s.kv("fix offered", o.optString("fix"));
                if (o.has("cause")) s.kv("cause", o.optString("cause"));
                if (!o.optString("reason_detail").isEmpty()) s.kv("pause readings", o.optString("reason_detail"));
                s.kv("memory level", o.optInt("mem_level") + " (each time Android ended the job's process the workers were halved)");
                memLevel = Math.max(memLevel, o.optInt("mem_level"));
                JSONObject steps = o.optJSONObject("steps");
                if (steps != null) for (String st : new String[] {"identify", "rewrite", "split", "shaders", "pack"}) {
                    JSONObject x = steps.optJSONObject(st);
                    if (x != null) s.kv("  " + st, x.optString("state") + ", " + x.optLong("done") + " of " + x.optLong("total") + (x.optInt("starts") > 1 ? ", started " + x.optInt("starts") + " times" : ""));
                }
                JSONObject apk = o.optJSONObject("apk");
                boolean other = apk == null || !dev.nxnative.android.BuildConfig.NX_PACK_ENGINE.equals(apk.optString("engine")) || !dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN.equals(apk.optString("dmsh_vk_chain"))
                        || !dev.nxnative.android.BuildConfig.NX_DMSH_CHAIN.equals(apk.optString("dmsh_chain")) || !dev.nxnative.android.BuildConfig.NX_XLATE_SOURCE.equals(apk.optString("xlate_source"));
                s.kv("made by", apk == null ? "an older app" : "engine " + apk.optString("engine") + ", version code " + apk.optInt("version_code") + (other ? " (ANOTHER app version: its shaders are made again)" : " (this app version)"));
                s.kv("created", BuildLogBundle.time(o.optLong("created")));
                s.kv("updated", BuildLogBundle.time(o.optLong("updated")) + " (" + Math.max(0, (now - o.optLong("updated")) / 60000) + " min ago)");
                if (!o.optString("mod_args").isEmpty()) s.kv("mod arguments", o.optString("mod_args").split("\n").length + " (the job's own job.json has them)");
                s.kv("game file", o.optString("dump"));
                s.kv("pack", o.optString("pack"));
            } catch (org.json.JSONException e) {
                s.kv("job.json", "damaged: " + e.getMessage());
            }
            File env = new File(j.dir, "env.txt");
            if (env.isFile()) s.kv("env.txt", "in the zip beside job.json (its lines change the packer's settings)");
        }

        s.head("Worker plan now");
        long ram = total;
        for (BuildLogBundle.Job j : jobs) {
            String e = BuildLogBundle.head(new File(j.dir, "env.txt"), 64 << 10);
            if (e != null) for (String l : e.split("\n")) if (l.startsWith("BUILD_RAM_MB=")) { try { ram = Long.parseLong(l.substring(13).trim()); s.kv("BUILD_RAM_MB", ram + " (env.txt stands in for the phone's memory)"); } catch (NumberFormatException x) { /* as the packer: ignored */ } }
        }
        s.kv("plan", BuildMemory.plan(ram, avail, memLevel, Upstream.cpuWorkers()).why);

        s.head("Free space");
        free(s, "game folder", dev.peanuts.PeanutsPrefs.gameDir(c));
        free(s, "build folder", dev.peanuts.PeanutsPrefs.buildDir(c));
        File ext = c.getExternalFilesDir(null);
        free(s, "app storage", ext == null ? null : ext.getPath());
        free(s, "app private storage", c.getFilesDir().getPath());
        for (BuildLogBundle.Job j : jobs) free(s, "work folder " + j.label, j.dir.getParent());

        s.head("Process exits (newest first)");
        for (String l : exits) s.line(l);

        s.head("In this zip");
        s.kv("files", (z.files + 1) + ", " + BuildLogBundle.size(z.raw) + " before compression");
        if (!z.cut.isEmpty()) s.kv("cut", String.join("\n", z.cut));
        if (!z.left.isEmpty()) s.kv("left out", String.join("\n", z.left));
        if (!z.unreadable.isEmpty()) s.kv("could not be read", String.join("\n", z.unreadable));
        return s.toString();
    }

    private static void free(BuildLogBundle.Text s, String what, String path) {
        if (path == null) { s.kv(what, "not set"); return; }
        File f = new File(path);
        s.kv(what, path + ": " + (f.isDirectory() ? BuildLogBundle.size(f.getUsableSpace()) + " free of " + BuildLogBundle.size(f.getTotalSpace()) : "cannot be read now (not there, its volume is out, or no access)"));
    }

    /** The game's name for a sheet's title: the launcher's list, else the newest build's start line, else the title ID */
    public static String gameName(Context c, String titleId) {
        String t = BuildLog.title(titleId);
        if (t == null) return String.valueOf(titleId);
        try { for (String[] g : Upstream.listedGames(c, t)) if (!g[0].isEmpty()) return g[0]; } catch (ReflectiveOperationException | RuntimeException e) { /* the log's */ }
        return nameOf(new ArrayList<>(), BuildLog.file(dir(c), t), t);
    }

    /** Whether a build log of this game exists (the entry points show their row for any game; this only picks words) */
    public static boolean has(Context c, String titleId) {
        String t = BuildLog.title(titleId);
        return t != null && (BuildLog.file(dir(c), t).isFile() || BuildLog.older(dir(c), t).isFile());
    }

    /** The newest build log's game and the time it changed, or null */
    public static Object[] newest(Context c) {
        String t = BuildLog.newest(dir(c));
        if (t == null) return null;
        File f = BuildLog.file(dir(c), t);
        String text = BuildLog.tail(f, 64 << 10);
        return new Object[] {t, f.lastModified(), BuildLog.lastEnd(text), BuildLog.lastStart(text)};
    }
}
