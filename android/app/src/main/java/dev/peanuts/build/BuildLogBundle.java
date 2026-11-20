package dev.peanuts.build;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The pure half of Share build log (the Android half is {@link BuildLogShare}): one .zip with everything a build of one game left that tells why it failed,
 * stopped or ended with the app's process, small enough for a GitHub upload. No Android class here: the JVM tests drive it.
 * <ul>
 * <li>{@link #jobs}: the game's on-device jobs (&lt;work&gt;/ondevice/job.json and its logs/), the plain build's and the builds with mods', from the work folders
 *     the caller found the way Prepare does (DumpCheck.workOf) and the work folders beside them;</li>
 * <li>{@link Zip}: the entries, each file read only up to the length it had when it was opened (a running build appends to its logs; nothing is locked) and cut
 *     to its first and last part past {@link #PER_FILE}, the whole kept under {@link #TOTAL}; only text files (.log, .txt, .json) whose name has nothing of
 *     keys go in, whatever a caller hands it;</li>
 * <li>{@link #redactEnv}: an env.txt line whose name looks like a secret keeps its name only;</li>
 * <li>{@link #validName}: the only file names the provider serves.</li>
 * </ul>
 */
public final class BuildLogBundle {
    /** A file past this many bytes is cut: its first {@link #HEAD} bytes and its last (PER_FILE - HEAD) are kept, at line ends */
    public static final long PER_FILE = 1L << 20, HEAD = 256L << 10;
    /** The bytes of all files together (before compression); a file past it is listed in the summary, not added */
    public static final long TOTAL = 24L << 20;
    /** Bundles older than this are deleted when a new one is made, and never more than {@link #KEEP} are kept */
    public static final long MAX_AGE = 24L * 3600 * 1000;
    public static final int KEEP = 5;
    private static final Pattern NAME = Pattern.compile("build-log-[0-9A-F]{16}-[0-9]{8}-[0-9]{6}(?:-[0-9a-f]{32})?\\.zip");
    private static final Pattern SECRET = Pattern.compile(".*(KEY|TOKEN|SECRET|PASS|AUTH|COOKIE|CREDENTIAL).*");

    private BuildLogBundle() {}

    // ---- Names ----

    /** The timestamp is readable and each export gets its own file. */
    public static String zipName(String titleId, long time) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(time));
        return "build-log-" + BuildLog.title(titleId) + "-" + stamp + "-" + java.util.UUID.randomUUID().toString().replace("-", "") + ".zip";
    }

    /** Whether a name is one {@link #zipName} makes: no folder, no other file of the app's storage */
    public static boolean validName(String n) { return n != null && NAME.matcher(n).matches(); }

    /** Deletes the bundles in dir older than maxAge, and all but the newest keep; other files are not touched */
    public static void clean(File dir, long now, long maxAge, int keep) {
        File[] l = dir == null ? null : dir.listFiles((d, n) -> validName(n) || n.endsWith(".zip.part") && validName(n.substring(0, n.length() - 5)));
        if (l == null) return;
        Arrays.sort(l, Comparator.comparingLong(File::lastModified).reversed());
        int kept = 0;
        for (File f : l) {
            boolean part = f.getName().endsWith(".part");
            if (!part && kept < keep && now - f.lastModified() < maxAge) { kept++; continue; }
            if (part && now - f.lastModified() < 3600 * 1000) continue;   // being written now
            f.delete();
        }
    }

    // ---- env.txt ----

    /** One env.txt line as it goes into the bundle: a name that looks like a secret (KEY, TOKEN, PASS, ...) keeps its name, its value is hidden */
    public static String redactEnv(String line) {
        int eq = line.indexOf('=');
        if (line.trim().startsWith("#") || eq <= 0) return line;
        String k = line.substring(0, eq).trim();
        return SECRET.matcher(k.toUpperCase(Locale.ROOT)).matches() ? k + "=<hidden>" : line;
    }

    public static String redactEnvText(String text) {
        StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) b.append(redactEnv(l)).append('\n');
        return b.substring(0, Math.max(0, b.length() - 1));
    }

    // ---- The jobs ----

    /** One on-device job of the game: its ondevice/ folder, job.json there (it may be missing while its logs are there) */
    public static final class Job {
        public final File dir, file;
        public final boolean mods;
        /** The work folder's name, as the zip's folder: 0100...-work, 0100...-mod-1a2b-work */
        public final String label;
        Job(File dir, boolean mods, String label) { this.dir = dir; this.file = new File(dir, "job.json"); this.mods = mods; this.label = label; }
        public File logs() { return new File(dir, "logs"); }
        @Override public String toString() { return (mods ? "mods " : "") + dir; }
    }

    /**
     * The game's jobs: in each given work folder (&lt;work&gt;/ondevice) and in every &lt;title ID&gt;…-work folder beside it (the builds with mods,
     * ModdedBuild.workDir), and the folder of each given job.json. A job.json of another title is left out; one that cannot be read is kept (its logs tell
     * something). The plain build's jobs come first, then those with mods; each in the order found, a folder once
     */
    public static List<Job> jobs(String titleId, Collection<File> workDirs, Collection<File> jobFiles) {
        String t = BuildLog.title(titleId);
        List<Job> out = new ArrayList<>();
        if (t == null) return out;
        Set<File> dirs = new LinkedHashSet<>();
        if (jobFiles != null) for (File f : jobFiles) if (f != null && f.getParentFile() != null) dirs.add(f.getParentFile());
        if (workDirs != null) for (File w : workDirs) {
            if (w == null) continue;
            dirs.add(new File(w, "ondevice"));
            File p = w.getAbsoluteFile().getParentFile();
            File[] beside = p == null ? null : p.listFiles(d -> d.isDirectory() && d.getName().toUpperCase(Locale.ROOT).startsWith(t) && d.getName().endsWith("-work"));
            if (beside != null) {
                Arrays.sort(beside);
                for (File d : beside) dirs.add(new File(d, "ondevice"));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Job> mods = new ArrayList<>();
        for (File d : dirs) {
            String key = canonical(d);
            if (!seen.add(key)) continue;
            File jf = new File(d, "job.json");
            boolean hasJob = jf.isFile(), hasLogs = new File(d, "logs").isDirectory();
            if (!hasJob && !hasLogs) continue;
            File work = d.getAbsoluteFile().getParentFile();
            String label = work == null ? d.getName() : work.getName();
            boolean mod = label.contains("-mod-");
            if (hasJob) {
                String json = head(jf, 1 << 20);
                String id = json == null ? null : field(json, "title_id");
                if (id != null && !id.isEmpty() && !t.equalsIgnoreCase(id)) continue;
                String args = json == null ? null : field(json, "mod_args");
                if (args != null && !args.isEmpty()) mod = true;
            } else if (!label.toUpperCase(Locale.ROOT).startsWith(t)) continue;
            (mod ? mods : out).add(new Job(d, mod, label));
        }
        out.addAll(mods);
        // two work folders of the same name in different places (beside the game and in the build folder): the zip's folders stay apart
        Map<String, Integer> n = new LinkedHashMap<>();
        List<Job> named = new ArrayList<>();
        for (Job j : out) {
            int k = n.merge((j.mods ? "m/" : "j/") + j.label, 1, Integer::sum);
            named.add(k == 1 ? j : new Job(j.dir, j.mods, j.label + "-" + k));
        }
        return named;
    }

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    /** A top-level string field of a job.json (title_id, mod_args: names the nested step objects do not have), unescaped; null when absent */
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        String s = m.group(1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char e = s.charAt(++i);
            b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
        }
        return b.toString();
    }

    /** The first bytes of a file as text, null when it cannot be read */
    public static String head(File f, int max) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) Math.min(max, r.length())];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- The zip ----

    /** Whether a file may go into a bundle at all: a text file (.log, .txt, .json) whose name has nothing of keys, and nothing of a game */
    public static boolean allowed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("key") || n.contains(".nup") || n.contains(".nsp") || n.contains(".xci") || n.contains(".nsz") || n.contains(".xcz") || n.contains(".ncz")
                || n.contains(".unpack") || n.contains("save")) return false;
        return n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".json");
    }

    public static String size(long b) {
        if (b >= 10L << 30) return String.format(Locale.ROOT, "%d GB", Math.round(b / 1073741824.0));
        if (b >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", b / 1073741824.0);
        if (b >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        if (b >= 1L << 10) return String.format(Locale.ROOT, "%d KB", b >> 10);
        return b + " bytes";
    }

    /**
     * The .zip being written: entries in the order added, each file cut past perFile (its head and its tail, at line ends, with a line that says so between),
     * files past the total listed in {@link #left} instead. The zip is written to &lt;name&gt;.part and takes its name on {@link #finish}, so a reader never
     * sees half of one
     */
    public static final class Zip implements Closeable {
        private final File out, part;
        private final ZipOutputStream z;
        private final long perFile, head, total;
        private final Set<String> paths = new LinkedHashSet<>();
        public int files;
        public long raw;
        /** What was cut, left out or could not be read, one sentence each, for the summary */
        public final List<String> cut = new ArrayList<>(), left = new ArrayList<>(), unreadable = new ArrayList<>();
        private boolean done;

        public Zip(File out) throws IOException { this(out, PER_FILE, HEAD, TOTAL); }

        public Zip(File out, long perFile, long head, long total) throws IOException {
            this.out = out;
            this.part = new File(out.getPath() + ".part");
            this.perFile = perFile;
            this.head = Math.min(head, perFile);
            this.total = total;
            File d = out.getAbsoluteFile().getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("cannot make " + d);
            if (!part.createNewFile()) throw new IOException("another export is writing " + out.getName());
            try { z = new ZipOutputStream(new FileOutputStream(part)); }
            catch (IOException | RuntimeException e) { part.delete(); throw e; }
            z.setLevel(9);
        }

        private ZipEntry entry(String path, long time) throws IOException {
            if (!paths.add(path)) throw new IOException("twice in the zip: " + path);
            ZipEntry e = new ZipEntry(path);
            if (time > 0) e.setTime(time);
            return e;
        }

        /** A text the bundle makes itself (the summary, the app's own messages); refused like a file when its name is not allowed */
        public void text(String path, String text) throws IOException {
            if (!allowed(path)) throw new IOException("not a bundle file: " + path);
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            z.putNextEntry(entry(path, System.currentTimeMillis()));
            z.write(b);
            z.closeEntry();
            files++;
            raw += b.length;
        }

        /** A file, as it is now (its length when opened) and cut when long; false when it was not added (left out, not allowed, unreadable: said in the lists) */
        public boolean file(String path, File f) {
            if (f == null || !f.isFile()) return false;
            if (!allowed(f.getName()) || !allowed(path)) { left.add(path + ": not a log file"); return false; }
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                long len = r.length();
                long take = Math.min(len, perFile);
                if (raw + take > total) { left.add(path + " (" + size(len) + "): the bundle was full"); return false; }
                byte[] body;
                if (len <= perFile) {
                    body = new byte[(int) len];
                    r.readFully(body);
                } else {
                    byte[] h = new byte[(int) head];
                    r.readFully(h);
                    int he = lastNewline(h);
                    long tailLen = perFile - head;
                    byte[] t = new byte[(int) tailLen];
                    r.seek(len - tailLen);
                    r.readFully(t);
                    int ts = firstNewline(t);
                    long keptHead = he, keptTail = t.length - ts;
                    String mark = "\n[Share build log: " + size(len - keptHead - keptTail) + " cut here. The file has " + size(len) + "; its first " + size(keptHead)
                            + " and its last " + size(keptTail) + " are kept]\n\n";
                    byte[] m = mark.getBytes(StandardCharsets.UTF_8);
                    body = new byte[(int) (keptHead + m.length + keptTail)];
                    System.arraycopy(h, 0, body, 0, (int) keptHead);
                    System.arraycopy(m, 0, body, (int) keptHead, m.length);
                    System.arraycopy(t, ts, body, (int) keptHead + m.length, (int) keptTail);
                    cut.add(path + ": " + size(len) + ", its first " + size(keptHead) + " and last " + size(keptTail) + " kept");
                }
                z.putNextEntry(entry(path, f.lastModified()));
                z.write(body);
                z.closeEntry();
                files++;
                raw += body.length;
                return true;
            } catch (EOFException e) {
                unreadable.add(path + ": it got shorter while it was read");
            } catch (IOException | RuntimeException e) {
                unreadable.add(path + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            return false;
        }

        /** The end of the head at a line end (after its last newline), the whole head when it has none */
        private static int lastNewline(byte[] b) {
            for (int i = b.length - 1; i >= 0; i--) if (b[i] == '\n') return i + 1;
            return b.length;
        }

        /** The start of the tail at a line start (after its first newline), the whole tail when it has none */
        private static int firstNewline(byte[] b) {
            for (int i = 0; i < b.length; i++) if (b[i] == '\n') return i + 1 < b.length ? i + 1 : 0;
            return 0;
        }

        /** Closes the zip and gives it its name; the zip */
        public File finish() throws IOException {
            z.close();
            if (out.exists()) throw new IOException("a bundle already exists: " + out);
            if (!part.renameTo(out)) throw new IOException("cannot name " + out);
            done = true;
            return out;
        }

        @Override public void close() {
            if (done) return;
            try { z.close(); } catch (IOException e) { /* given up */ }
            part.delete();
        }
    }

    // ---- The summary's words ----

    /** ApplicationExitInfo's reason (the platform's constants, API 30) in a word */
    public static String reason(int r) {
        switch (r) {
            case 1: return "exit_self";
            case 2: return "signaled";
            case 3: return "low_memory";
            case 4: return "crash";
            case 5: return "crash_native";
            case 6: return "anr";
            case 7: return "initialization_failure";
            case 8: return "permission_change";
            case 9: return "excessive_resource_usage";
            case 10: return "user_requested";
            case 11: return "user_stopped";
            case 12: return "dependency_died";
            case 13: return "other";
            case 14: return "freezer";
            case 15: return "package_state_change";
            case 16: return "package_updated";
            default: return "unknown";
        }
    }

    /** What a reason means for a build, in a sentence */
    public static String meaning(int r, int status) {
        switch (r) {
            case 1: return "The process ended itself.";
            case 2: return status == 9 ? "Ended by signal 9 (SIGKILL): on many phones this is Android's low-memory killer." : "Ended by signal " + status + ".";
            case 3: return "Android ended it to free memory.";
            case 4: return "A Java crash.";
            case 5: return "A native crash.";
            case 6: return "Not responding (ANR).";
            case 10: return "Stopped from the system (Force stop, a reinstall or adb).";
            case 11: return "Stopped by the user from the system.";
            case 16: return "The app was updated.";
            case 9: return "Android ended it for using too many resources.";
            case 14: return "Ended while frozen in the background.";
            default: return "";
        }
    }

    /** RunningAppProcessInfo's importance (as ApplicationExitInfo gives it) in a word */
    public static String importance(int i) {
        switch (i) {
            case 100: return "foreground";
            case 125: return "foreground_service";
            case 150: return "top_sleeping";
            case 200: return "visible";
            case 230: return "perceptible";
            case 300: return "service";
            case 325: return "top_sleeping";
            case 350: return "cant_save_state";
            case 400: return "cached";
            case 1000: return "gone";
            default: return String.valueOf(i);
        }
    }

    /** The summary's text: sections of "key: value" lines */
    public static final class Text {
        private final StringBuilder b = new StringBuilder();
        public Text head(String s) { if (b.length() > 0) b.append('\n'); b.append("== ").append(s).append('\n'); return this; }
        public Text kv(String k, Object v) { b.append(k).append(": ").append(v == null ? "-" : String.valueOf(v).replace("\n", "\n    ")).append('\n'); return this; }
        public Text line(String s) { b.append(s).append('\n'); return this; }
        @Override public String toString() { return b.toString(); }
    }

    public static String time(long ms) {
        return ms <= 0 ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date(ms));
    }
}
