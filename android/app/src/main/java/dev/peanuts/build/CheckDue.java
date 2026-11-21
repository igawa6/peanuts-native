package dev.peanuts.build;

import java.io.File;
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
    /** A check that failed this many times (not stopped: the tool ended with an error) is dropped: it was started again every two minutes for good (found in review) */
    public static final int MAX_TRIES = 3;

    /** check.todo's library id, null without one */
    static String library(File reuseDir) {
        String t = read(new File(reuseDir, TODO));
        return t == null ? null : text(t, "library");
    }

    /** The check is due no more: check.todo, check.job, the count of its tries and its own results go (the job's split goes with the packer's next look) */
    public static void drop(File reuseDir) {
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
            return "the check cannot be made with this job: dropped";
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
            return "the check failed " + n + " times: dropped";
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
        return what + " came out different when made again from the start. " + name + " has the ones made from the start now; nothing to do.";
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
