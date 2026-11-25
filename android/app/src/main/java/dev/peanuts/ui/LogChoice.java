package dev.peanuts.ui;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.peanuts.build.BuildLog;

/**
 * Share log's choice in words ({@link BuildLogUi#shareLog}): whether a place asks between a game run and a build, and for each when it happened and how it
 * ended ("Stopped today at 14:32 after 18 min", "Failed while translating shaders yesterday at 21:05 after 3 min"). The facts come from the files: a run log's
 * name (its start), its last change (its end), its last lines and the game's exit record (ExitWatch's last-exit.txt); a build log's newest run ({@link BuildLog}:
 * its start and end lines, the job's failed step, the last progress line); a job's job.json. No Android class here: LogChoiceJvmTest drives it.
 */
public final class LogChoice {
    /** What a place does: nothing to share, share the run, share the build, or ask which */
    public static final int NONE = 0, RUN = 1, BUILD = 2, ASK = 3;

    /** The on-device job's steps (Job.STEPS) and how a sentence names each */
    static final String[] STEPS = {"identify", "rewrite", "split", "shaders", "pack"};
    static final String[] STEP_WORDS = {"checking the game files", "converting the game's code", "finding the shaders", "making shaders for this phone", "finishing up"};
    /** Prepare's progress words for the same steps (Prepare.STEP_TEXT), as the build log's progress lines carry them */
    static final String[] STEP_TEXT = {"Checking the game files", "Converting the game's code", "Finding the shaders", "Making shaders for this phone", "Finishing up"};

    /** The same steps' words before 0.1.8 (logs of older builds carry them) */
    static final String[] OLD_STEP_TEXT = {"Checking the dump", "Rewriting the game's code", "Finding the shaders", "Translating shaders", "Finishing the pack"};

    private static final Pattern RUN_TIME = Pattern.compile("(\\d{8}-\\d{6})\\.log$");
    private static final Pattern LINE_TIME = Pattern.compile("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (.*)$");
    private static final Pattern FAILED_AT = Pattern.compile("prepare: the job failed at (\\w+)");
    private static final Pattern PROGRESS = Pattern.compile("^progress (\\w+) -?\\d+ %: (.*) \\(\\d+ s\\)$");

    private LogChoice() {}

    public static int choose(boolean run, boolean build) { return run && build ? ASK : run ? RUN : build ? BUILD : NONE; }

    // ---- Time ----

    /** "today at 14:32", "yesterday at 21:05", "on 12 Nov at 09:10", "on 3 Dec 2025 at 18:40" (another year) */
    public static String when(long t, long now, TimeZone z) {
        String clock = format("HH:mm", t, z), day = format("yyyyMMdd", t, z);
        if (day.equals(format("yyyyMMdd", now, z))) return "today at " + clock;
        Calendar y = Calendar.getInstance(z, Locale.ROOT);
        y.setTimeInMillis(now);
        y.add(Calendar.DAY_OF_MONTH, -1);
        if (day.equals(format("yyyyMMdd", y.getTimeInMillis(), z))) return "yesterday at " + clock;
        boolean thisYear = format("yyyy", t, z).equals(format("yyyy", now, z));
        return "on " + format(thisYear ? "d MMM" : "d MMM yyyy", t, z) + " at " + clock;
    }

    /** How long, in the largest units that matter: "40 s", "18 min", "1 h", "2 h 5 min" */
    public static String took(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return Math.max(1, s) + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        return m / 60 + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min");
    }

    private static String format(String pattern, long t, TimeZone z) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ENGLISH);
        f.setTimeZone(z);
        return f.format(new Date(t));
    }

    private static long parse(String pattern, String s, TimeZone z) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(z);
        f.setLenient(false);
        try { return f.parse(s).getTime(); } catch (ParseException e) { return -1; }
    }

    // ---- A game run ----

    /** The run's exit record (ExitWatch: last-exit.txt, "key=value" lines, the last of a key wins); an empty map for none */
    public static Map<String, String> record(String text) {
        Map<String, String> m = new HashMap<>();
        if (text == null) return m;
        for (String l : text.split("\n")) {
            int eq = l.indexOf('=');
            if (eq > 0) m.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
        }
        return m;
    }

    /** When a run started, from its log's name (&lt;title ID&gt;-yyyyMMdd-HHmmss.log, selftest-yyyyMMdd-HHmmss.log); -1 when the name has no time */
    public static long runStart(String name, TimeZone z) {
        Matcher m = RUN_TIME.matcher(name == null ? "" : name);
        return m.find() ? parse("yyyyMMdd-HHmmss", m.group(1), z) : -1;
    }

    /**
     * How a run ended, in a word or two; null while it runs. The exit record tells it for the run it names (the newest run of any game): Stop, a crash the app
     * saw, the host's return, or an end the app did not see (Android closed it, or it was swiped away). Any other run is told from the end of its log
     */
    static String runHow(String name, String tail, Map<String, String> rec, boolean alive) {
        String log = tail == null ? "" : tail;
        String st = name != null && name.equals(rec.get("log")) ? rec.get("state") : null;
        if ("running".equals(st) && alive) return null;
        if ("stopped".equals(st)) return "Stopped";
        if ("java".equals(st) || "native".equals(st) || crashed(log)) return "Crashed";
        if ("host_exit".equals(st)) { String rc = rec.get("rc"); return rc == null || rc.equals("0") ? "Ended" : "Ended with an error"; }
        if (log.contains("FATAL ")) return "Ended with an error";
        if ("running".equals(st)) return log.contains("graceful exit") ? "Ended" : "Closed";
        return "Ended";
    }

    /** The lines the host and the launcher write when the game crashes (ExitNotice reads the same) */
    private static boolean crashed(String log) {
        return log.contains("CRASH sig=") || log.contains("[RED] crash:") || log.contains("GPU hang") || log.contains("VK_ERROR_DEVICE_LOST");
    }

    /** A run's line: "Stopped today at 14:32 after 18 min", "Running now, started today at 14:10". modified: the log's last change, the run's end */
    public static String runLine(String name, long modified, String tail, Map<String, String> rec, boolean alive, long now, TimeZone z) {
        long start = runStart(name, z);
        String how = runHow(name, tail, rec, alive);
        if (how == null) return "Running now, started " + when(start > 0 ? start : modified, now, z);
        return how + " " + when(modified, now, z) + (start > 0 && modified >= start ? " after " + took(modified - start) : "");
    }

    // ---- A build ----

    /** A step of the job (Job.STEPS) as a sentence names it; null for none */
    static String stepWords(String step) {
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i].equals(step)) return STEP_WORDS[i];
        return null;
    }

    /** Where a build was, from its log: the step the job failed at, else the last progress line's step (import, prepare, companion) and words */
    static String where(String failedStep, String step, String detail) {
        String s = stepWords(failedStep);
        if (s != null) return s;
        if (step == null) return null;
        switch (step) {
            case "import": return "importing the game files";
            case "companion": return "installing the dual-screen companion";
            case "prepare": break;
            default: return null;
        }
        String d = detail == null ? "" : detail;
        if (d.startsWith("Trying anyway: ")) d = d.substring(15);
        if (d.startsWith("Paused: ")) { int dot = d.indexOf(". "); d = dot < 0 ? "" : d.substring(dot + 2); }
        for (int i = 0; i < STEP_TEXT.length; i++) if (d.startsWith(STEP_TEXT[i]) || d.startsWith(OLD_STEP_TEXT[i])) return STEP_WORDS[i];
        if (d.startsWith("Checking ")) return STEP_WORDS[0];
        if (d.startsWith("Finishing graphics setup") || d.startsWith("Completing graphics setup")) return "finishing graphics setup";   // "Completing": logs before 0.1.8
        return "building the game";
    }

    /** How a build that did not finish ended, from its end line's failure category (BuildListener's codes) and where it was */
    static String failed(String code, String at) {
        switch (code) {
            case "cancelled": return "Stopped";
            case "stalled": return at == null ? "Stopped responding" : "Stopped responding while " + at;
            case "out_of_space": return at == null ? "Ran out of space" : "Ran out of space while " + at;
            case "wrong_version": return "Stopped at an untested game version";
            case "addon_missing": return "Stopped because it needs the DLC";
            case "file_missing": return "Failed because a game file is missing";
            case "file_damaged": return "Failed because a game file is damaged";
            case "invalid_dump": return "Failed because the game files cannot be used";
            default: return at == null ? "Failed" : "Failed while " + at;
        }
    }

    /**
     * The newest build in a build log's text (BuildLog: "yyyy-MM-dd HH:mm:ss.SSS " before every line, each run from its [start] to its [end] line): "Finished
     * today at 14:32 after 18 min", "Failed while translating shaders today at 14:32 after 3 min", "Stopped when the app closed ..." (no end line: the app's
     * process ended during it), "Building now, started today at 14:10" (runningNow: the build runs in this process now). Null when the text has no timed line
     */
    public static String buildLine(String text, boolean runningNow, long now, TimeZone z) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        int from = -1;
        for (int i = lines.length - 1; i >= 0 && from < 0; i--) if (lines[i].contains(BuildLog.START)) from = i;
        long start = -1, last = -1, endAt = -1;
        String end = null, failedStep = null, step = null, detail = null;
        for (int i = Math.max(0, from); i < lines.length; i++) {
            Matcher m = LINE_TIME.matcher(lines[i]);
            if (!m.matches()) continue;
            long t = parse("yyyy-MM-dd HH:mm:ss.SSS", m.group(1), z);
            String body = m.group(2);
            if (t > 0) last = t;
            if (i == from) start = t;
            if (body.startsWith(BuildLog.END)) { end = body.substring(BuildLog.END.length()).trim(); endAt = t; continue; }
            Matcher f = FAILED_AT.matcher(body);
            if (f.find()) failedStep = f.group(1);
            Matcher p = PROGRESS.matcher(body);
            if (p.matches()) { step = p.group(1); detail = p.group(2); }
        }
        if (last < 0) return null;
        if (from < 0) return "Ended " + when(last, now, z);
        String how;
        if (end == null) how = runningNow ? null : "Stopped when the app closed";
        else if (end.startsWith("ready")) how = "Finished";
        else {
            int a = end.indexOf('('), b = end.indexOf(')');
            String code = a > 0 && b > a ? end.substring(a + 1, b) : "other";
            how = failed(code, where(failedStep, step, detail));
        }
        if (how == null) return "Building now, started " + when(start > 0 ? start : last, now, z);
        long at = endAt > 0 ? endAt : last;
        return how + " " + when(at, now, z) + (start > 0 && at >= start ? " after " + took(at - start) : "");
    }

    /** A job.json's state (Job: pending, running, paused, failed, done) as a line; updated: its last change, created: its start */
    public static String jobLine(String state, String step, long created, long updated, long now, TimeZone z) {
        String at = stepWords(step);
        long t = updated > 0 ? updated : created;
        String s = state == null ? "" : state;
        if (t <= 0) return s.equals("done") ? "Finished" : s.equals("failed") ? "Failed" : "Not started yet";
        switch (s) {
            case "done": return "Finished " + when(t, now, z);
            case "failed": return (at == null ? "Failed" : "Failed while " + at) + " " + when(t, now, z);
            case "paused": return (at == null ? "Paused" : "Paused while " + at) + " " + when(t, now, z);
            case "running": return "Building now, started " + when(created > 0 ? created : t, now, z);
            default: return "Not started yet";
        }
    }
}
