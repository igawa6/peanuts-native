package dev.peanuts.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The files of a due full check (issue #120, ReuseCheck) in &lt;no_backup&gt;/reuse/&lt;title ID&gt;/, without Android: dropping a check that cannot be made,
 * counting the checks that failed, and the notification's text after one that replaced the pack's library. dm/peanuts/reuse/shader_reuse.py writes check.todo
 * and check.failed (JSON of its own, read here by key); ReuseCheck writes check.job. No Android class: CheckDueJvmTest drives it.
 */
public final class CheckDue {
    private CheckDue() {}

    static final String TODO = "check.todo", JOB = "check.job", FAILED = "check.failed", TRIES = "check.tries", DIR = "check", RUNNING = "check.running";
    /** shader_reuse.py's RECORDS_DIR and KEEP_DIR: the records of the shaders, without which a build keeps nothing, and the kept copy of the last library */
    static final String RECORDS = "shaders", KEPT = "library";
    /** What a dropped check says in check.log, as shader_reuse.py DROPPED */
    static final String DROPPED = "dropped, with the records of the kept shaders: the next build of this game translates every shader";
    /** A check that failed this many times (not stopped: the tool ended with an error) is dropped: it was started again every two minutes for good (found in review) */
    public static final int MAX_TRIES = 3;

    /** check.todo's library id, null without one */
    static String library(File reuseDir) {
        String t = read(new File(reuseDir, TODO));
        return t == null ? null : text(t, "library");
    }

    // PEANUTS begin: the unchecked marker (the in-place shader upgrade, ShaderUpgrade; critique B1, review M3/M4). <lib>.unchecked names, one line each, the ids
    // (shader_reuse.py library_id) of libraries written at that path with kept shaders whose full check has not passed. shader_reuse.py writes a line with the
    // library a build keeps shaders in and takes it out when the full check passes or replaces it, so a dropped, replaced or forgotten check leaves it; the pack
    // step's copy takes it into the pack (copyMarker), and the drops here write one for check.todo's library too (a library from before the marker). It goes
    // wherever the pack goes (copied, exported, restored), which the records in no_backup do not, and refuses only the library it names
    /** shader_reuse.py's CHECKED: the libraries a full check found a full translation's byte for byte */
    static final String CHECKED = "checked.tsv";

    /** check.todo's library id and version, null without one (what a drop marks: shaders/v-&lt;version&gt;/dmsh_vk.lib of the job's pack, by that id) */
    public static String[] todo(File reuseDir) {
        String t = read(new File(reuseDir, TODO));
        String lib = t == null ? null : text(t, "library"), version = t == null ? null : text(t, "version");
        return lib == null || version == null ? null : new String[] {lib, version};
    }

    /** Beside a library whose kept shaders were never checked: &lt;lib&gt;.unchecked (vk_android_upgrade.py and shader_reuse.py UNCHECKED) */
    public static final String UNCHECKED = ".unchecked";

    /** The marker beside a library */
    public static File marker(File lib) { return new File(lib.getPath() + UNCHECKED); }

    /** The library ids a marker's text names (lines of 32 hex characters) */
    public static java.util.List<String> ids(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text != null) for (String l : text.split("\n")) { String t = l.trim(); if (t.matches("[0-9a-f]{32}") && !out.contains(t)) out.add(t); }
        return out;
    }

    /** id into the marker beside lib (written whole: a temporary file, then a rename) → the marker, null when it could not be written */
    static File addId(File lib, String id) {
        File m = marker(lib);
        java.util.List<String> have = ids(read(m));
        if (have.contains(id)) return m;
        have.add(id);
        StringBuilder b = new StringBuilder();
        for (String i : have) b.append(i).append('\n');
        File tmp = new File(m.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return null;
        }
        try { Files.move(tmp.toPath(), m.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); return m; }
        catch (IOException e) { tmp.delete(); return null; }
    }

    /** The marker naming id beside shaders/v-&lt;version&gt;/dmsh_vk.lib of a pack, when the pack has that library → the marker, null when none was written */
    public static File markUnchecked(File packDir, String version, String id) {
        if (packDir == null || version == null || !version.matches("[0-9a-f]{12}") || id == null || !id.matches("[0-9a-f]{32}")) return null;
        File lib = new File(new File(packDir, "shaders/v-" + version), "dmsh_vk.lib");
        return lib.isFile() ? addId(lib, id) : null;
    }

    /**
     * The pack step's copy of a library (PackService, after pack_android.py copied from to to): the ids of from's marker go into to's (a library with kept shaders
     * reaches the pack with its marker; one without leaves to's marker as it is: its lines name other libraries) → how many ids it had
     */
    public static int copyMarker(File from, File to) {
        java.util.List<String> have = ids(read(marker(from)));
        if (!to.isFile()) return 0;
        for (String i : have) addId(to, i);
        return have.size();
    }

    /** drop, and the marker naming check.todo's library beside the pack's library of its version (pack: the job's pack folder; null: no marker) */
    public static void drop(File reuseDir, File pack) {
        String[] t = todo(reuseDir);
        drop(reuseDir);
        if (t != null) markUnchecked(pack, t[1], t[0]);
    }

    /**
     * After a full check's run (PackService.checkKept), given check.todo as it was before (todo: its library id and version): whether the check was dropped,
     * by this class (ended, died) or by shader_reuse.py drop_check (no room, an app update since the build, its library gone): check.todo went, and neither
     * checked.tsv names the library (the check passed) nor check.failed names it (the check ran to its end and replaced it). A drop writes the marker naming
     * that library beside the pack's library of that version → the marker, null when there was nothing to mark
     */
    public static File markIfDropped(File reuseDir, File pack, String[] todo) {
        if (todo == null || new File(reuseDir, TODO).isFile()) return null;   // nothing was due, or it still is
        String checked = read(new File(reuseDir, CHECKED));
        if (checked != null) for (String l : checked.split("\n")) if (l.startsWith(todo[0] + "\t")) return null;
        String failed = read(new File(reuseDir, FAILED));
        if (failed != null && todo[0].equals(text(failed, "library"))) return null;   // the check ran to its end: the full translation took the library's place, or the pack holds another one now
        return markUnchecked(pack, todo[1], todo[0]);
    }
    // PEANUTS end

    /**
     * The check will never run (its job cannot make it, it failed MAX_TRIES times, an app update since the build): the kept shaders were then checked by the
     * sample alone, so every record of the game's shaders and the kept copy of its library go first, and the next build of the game keeps no shader and
     * translates every one (shader_reuse.py drop_check, which does the same for the checks the tool drops). Before, the records stayed, the next build kept the
     * same entries again, and a wrong one the sample missed could stay in the pack for good (found in review). Then check.todo, check.job, the count of its
     * tries and its own results go (the job's split goes with the packer's next look). A stop in between leaves the check due, with no record left to keep by
     */
    public static void drop(File reuseDir) {
        for (String n : new String[] {RECORDS, KEPT}) deleteTree(new File(reuseDir, n));
        for (String n : new String[] {TODO, JOB, TRIES, RUNNING}) new File(reuseDir, n).delete();
        deleteTree(new File(reuseDir, DIR));
    }

    /**
     * How a check ended (PackService.checkKept: "done", "interrupted", "waits (...)", or why it failed) → what became of it for the log, null when it stays as it
     * was. A check this job can never make (its split gone, or of another selection) is dropped; one that failed for another reason counts a try, and the
     * MAX_TRIES-th drops it. The count is check.todo's library's: the next build's check starts it over
     */
    public static String ended(File reuseDir, String what) {
        new File(reuseDir, RUNNING).delete();
        if (what == null || what.equals("done") || what.equals("interrupted") || what.startsWith("waits")) return null;
        String lib = library(reuseDir);
        if (lib == null) return null;   // not due (the check ended it, or a build since)
        if (what.equals("no split to check with") || what.equals("the split holds another selection")) {
            drop(reuseDir);
            return "the check cannot be made with this job: " + DROPPED;
        }
        File f = new File(reuseDir, TRIES);
        String had = read(f);
        int n = 1;
        if (had != null) {
            String[] c = had.trim().split("\t");
            if (c.length == 2 && c[0].equals(lib)) try { n = Integer.parseInt(c[1]) + 1; } catch (NumberFormatException e) { n = 1; }
        }
        if (n >= MAX_TRIES) {
            drop(reuseDir);
            return "the check failed " + n + " times: " + DROPPED;
        }
        write(f, lib + "\t" + n + "\n");
        return "the check failed (" + n + " of " + MAX_TRIES + " tries)";
    }

    /** Before the check's tool runs: its mark, which every end takes with it (ended) and only an end of the process leaves (died) */
    public static void begin(File reuseDir) {
        String lib = library(reuseDir);
        if (lib != null) write(new File(reuseDir, RUNNING), lib + "\n");
    }

    /**
     * At a check's start: a mark an earlier check left, whose process ended while it ran (a low-memory kill, the 20 s end of a stop, a crash: its own end never
     * ran, and it was asked again every two minutes for good, found in review), counts as a try of its own (ended); null when there is none
     */
    public static String died(File reuseDir) {
        if (!new File(reuseDir, RUNNING).isFile()) return null;
        String r = ended(reuseDir, "the packer's process ended during the check");
        return r != null ? r : "";
    }

    /**
     * The notification's text when the check took the pack's library's place since `since` (check.failed's "replaced"), else null: nothing to say when the pack
     * held another library by then, and nothing before (an older check.failed)
     */
    public static String replacedText(File reuseDir, String name, long since) {
        File f = new File(reuseDir, FAILED);
        if (!f.isFile() || f.lastModified() < since) return null;
        String t = read(f);
        if (t == null || !Pattern.compile("\"replaced\"\\s*:\\s*true").matcher(t).find()) return null;
        long n = number(t, "differ_count") + Math.max(0, number(t, "missing_count")) + Math.max(0, number(t, "extra_count"));
        return text(name, n, number(t, "of"));
    }

    static String text(String name, long differ, long of) {
        String what = differ > 0 && of > 0 ? String.format(Locale.ROOT, "%,d of its %,d shaders", differ, of) : "Some of its shaders";
        return what + " came out different when made again from the start. " + name + " now uses the ones made from the start, so there is nothing to do.";
    }

    private static String text(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static long number(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    /** text to f, whole or not at all: a temporary file, then a rename */
    static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            tmp.delete();
        }
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }
}
