// PEANUTS: LogChoice on the JVM (peanuts/tests/test_share_log_choice.py)
package dev.peanuts.ui;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Share log's choice in words (LogChoice): what a place does, the time words, a run's and a build's line from the files' text. Prints "ok N" */
public final class LogChoiceJvmTest {
    private static int n;
    private static final TimeZone Z = TimeZone.getTimeZone("Europe/Berlin");

    private static void eq(String want, String got, String what) {
        n++;
        if (!want.equals(got)) throw new AssertionError(what + ": wanted \"" + want + "\", got \"" + got + "\"");
    }

    private static long at(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(Z);
        return f.parse(s).getTime();
    }

    /** A build log line as BuildLog.append writes it */
    private static String line(String time, String text) { return time + ".000 " + text + "\n"; }

    public static void main(String[] args) throws Exception {
        // what a place does: ask only when there are both
        eq("3", "" + LogChoice.choose(true, true), "both");
        eq("1", "" + LogChoice.choose(true, false), "run only");
        eq("2", "" + LogChoice.choose(false, true), "build only");
        eq("0", "" + LogChoice.choose(false, false), "none");

        long now = at("2026-11-21 15:00:00");
        eq("today at 14:32", LogChoice.when(at("2026-11-21 14:32:10"), now, Z), "today");
        eq("today at 00:05", LogChoice.when(at("2026-11-21 00:05:00"), now, Z), "just after midnight");
        eq("yesterday at 21:05", LogChoice.when(at("2026-11-20 21:05:00"), now, Z), "yesterday");
        eq("on 12 Nov at 09:10", LogChoice.when(at("2026-11-12 09:10:00"), now, Z), "this year");
        eq("on 3 Dec 2025 at 18:40", LogChoice.when(at("2025-12-03 18:40:00"), now, Z), "another year");
        eq("yesterday at 23:30", LogChoice.when(at("2026-03-28 23:30:00"), at("2026-03-29 10:00:00"), Z), "yesterday across the clock change");
        eq("40 s", LogChoice.took(40_000), "seconds");
        eq("1 s", LogChoice.took(200), "under a second");
        eq("18 min", LogChoice.took(18 * 60_000 + 59_000), "minutes");
        eq("1 h", LogChoice.took(60 * 60_000), "an hour");
        eq("2 h 5 min", LogChoice.took(125 * 60_000), "hours and minutes");

        // a run: its start from the log's name, its end from the log's last change
        String name = "0100000000010000-20261121-141400.log";
        long end = at("2026-11-21 14:32:00");
        eq("" + at("2026-11-21 14:14:00"), "" + LogChoice.runStart(name, Z), "start from the name");
        eq("-1", "" + LogChoice.runStart("notes.log", Z), "a name without time");
        Map<String, String> none = LogChoice.record(null);
        Map<String, String> stopped = LogChoice.record("v=1\ntitle=0100000000010000\nlog=" + name + "\npid=4321\nstart=1\nstate=running\nstate=stopped\n");
        eq("stopped", stopped.get("state"), "the record's last state wins");
        eq("Stopped today at 14:32 after 18 min", LogChoice.runLine(name, end, "...", stopped, false, now, Z), "the player's Stop");
        Map<String, String> running = LogChoice.record("log=" + name + "\npid=4321\nstate=running\n");
        eq("Running now, started today at 14:14", LogChoice.runLine(name, end, "", running, true, now, Z), "a game that still runs");
        eq("Closed today at 14:32 after 18 min", LogChoice.runLine(name, end, "frame 9000\n", running, false, now, Z), "ended without the app seeing it");
        eq("Ended today at 14:32 after 18 min", LogChoice.runLine(name, end, "[host] graceful exit\n", running, false, now, Z), "the game's own exit");
        Map<String, String> crash = LogChoice.record("log=" + name + "\nstate=running\nstate=native\nshown=1\n");
        eq("Crashed today at 14:32 after 18 min", LogChoice.runLine(name, end, "", crash, false, now, Z), "the signal handler's record");
        Map<String, String> hostExit = LogChoice.record("log=" + name + "\nstate=host_exit\nrc=3\n");
        eq("Ended with an error today at 14:32 after 18 min", LogChoice.runLine(name, end, "", hostExit, false, now, Z), "the host returned an error");
        // an older run (the record names another): its log's end tells it
        String older = "0100000000010000-20261120-210000.log";
        long oend = at("2026-11-20 21:45:00");
        eq("Crashed yesterday at 21:45 after 45 min", LogChoice.runLine(older, oend, "x\n[nxhost] CRASH sig=11 pc=0x1234\n", stopped, false, now, Z), "a crash line");
        eq("Ended with an error yesterday at 21:45 after 45 min", LogChoice.runLine(older, oend, "FATAL unimplemented svc\n", stopped, false, now, Z), "a FATAL line");
        eq("Ended yesterday at 21:45 after 45 min", LogChoice.runLine(older, oend, "frame 100\n", none, false, now, Z), "nothing said: ended");
        eq("Ended yesterday at 21:45 after 45 min", LogChoice.runLine(older, oend, "frame 100\n", running, true, now, Z), "the record's running state is not an older run's");
        eq("Ended today at 14:00", LogChoice.runLine("x.log", at("2026-11-21 14:00:00"), "", none, false, now, Z), "no start in the name: no length");

        // a build: the newest run of the build log (BuildService's and Prepare's lines)
        String earlier = line("2026-11-20 10:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-20 10:20:00", "[end] ready: Game is ready to play. (after 20 min 0 s)");
        String failed = earlier
                + line("2026-11-21 14:10:00", "[start] build of Game (0100000000010000), peanuts test, engine fake")
                + line("2026-11-21 14:10:01", "base: /x/game.nsp (12 GB, 2026-11-01)")
                + line("2026-11-21 14:11:00", "progress import 45 %: Imported game.nup (60 s)")
                + line("2026-11-21 14:20:00", "progress prepare 70 %: Translating shaders: 1,234 of 22,852 (600 s)")
                + line("2026-11-21 14:31:00", "prepare: the job failed at shaders: shaders.py exited with 1")
                + line("2026-11-21 14:32:00", "[end] failed (tool_stopped): Translating shaders stopped. (after 22 min 0 s)");
        eq("Failed while making shaders for this phone today at 14:32 after 22 min", LogChoice.buildLine(failed, false, now, Z), "the job's failed step");
        eq("Finished yesterday at 10:20 after 20 min", LogChoice.buildLine(earlier, false, now, Z), "a finished build");
        String stoppedBuild = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:05:00", "progress import 20 %: Reading base (300 s)")
                + line("2026-11-21 14:06:00", "[end] stopped (cancelled): Build cancelled. (after 6 min 0 s)");
        eq("Stopped today at 14:06 after 6 min", LogChoice.buildLine(stoppedBuild, false, now, Z), "the player's Stop");
        String noEnd = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:18:00", "progress prepare 60 %: Rewriting the game's code (50 %) (1080 s)");
        eq("Stopped when the app closed today at 14:18 after 18 min", LogChoice.buildLine(noEnd, false, now, Z), "the app's process ended during it");
        eq("Building now, started today at 14:00", LogChoice.buildLine(noEnd, true, now, Z), "it runs now");
        // the same with the step words of 0.1.8 on (the line above has the older ones)
        String paused18 = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:30:00", "progress prepare 80 %: Paused: the phone is too hot. Making shaders for this phone: 1,234 of 22,852 (1800 s)")
                + line("2026-11-21 14:40:00", "[end] failed (stalled): The build stopped. (after 40 min 0 s)");
        eq("Stopped responding while making shaders for this phone today at 14:40 after 40 min", LogChoice.buildLine(paused18, false, now, Z), "the step read from 0.1.8's words");
        String space = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:01:00", "progress import 10 %: Reading base (60 s)")
                + line("2026-11-21 14:02:00", "[end] failed (out_of_space): No space left on the device. (after 2 min 0 s)");
        eq("Ran out of space while importing the game files today at 14:02 after 2 min", LogChoice.buildLine(space, false, now, Z), "out of space at the import");
        String paused = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:30:00", "progress prepare 80 %: Paused: too warm. Translating shaders: 5 of 9 (1800 s)")
                + line("2026-11-21 14:40:00", "[end] failed (stalled): The build stopped. (after 40 min 0 s)");
        eq("Stopped responding while making shaders for this phone today at 14:40 after 40 min", LogChoice.buildLine(paused, false, now, Z), "stalled, from a paused progress line");
        String version = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:00:05", "[end] failed (wrong_version): Untested game version. (after 0 min 5 s)");
        eq("Stopped at an untested game version today at 14:00 after 5 s", LogChoice.buildLine(version, false, now, Z), "a wrong version");
        String other = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)")
                + line("2026-11-21 14:00:30", "[end] failed (other): Android refused the build's foreground service. (after 0 min 30 s)");
        eq("Failed today at 14:00 after 30 s", LogChoice.buildLine(other, false, now, Z), "failed before any step");
        String companion = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:20:00", "progress companion 92 %: Downloading (1200 s)")
                + line("2026-11-21 14:21:00", "[end] failed (other): The companion could not be downloaded. (after 21 min 0 s)");
        eq("Failed while installing the dual-screen companion today at 14:21 after 21 min", LogChoice.buildLine(companion, false, now, Z), "the companion step");
        // graphics setup, in the words of 0.1.8 on and in the older ones
        for (String g : new String[] {"Finishing graphics setup: 3 of 9", "Completing graphics setup"}) {
            String setup = line("2026-11-21 14:00:00", "[start] build of Game (0100000000010000)") + line("2026-11-21 14:20:00", "progress prepare 90 %: " + g + " (1200 s)")
                    + line("2026-11-21 14:21:00", "[end] failed (other): Finishing graphics setup stopped. (after 21 min 0 s)");
            eq("Failed while finishing graphics setup today at 14:21 after 21 min", LogChoice.buildLine(setup, false, now, Z), "graphics setup: " + g);
        }
        eq("Ended today at 13:00", LogChoice.buildLine(line("2026-11-21 13:00:00", "crash in the build service:"), false, now, Z), "lines without a start line");
        eq("null", String.valueOf(LogChoice.buildLine("", false, now, Z)), "no timed line");

        // a job.json (the Prepare card's job)
        long created = at("2026-11-21 13:00:00"), updated = at("2026-11-21 14:25:00");
        eq("Failed while converting the game's code today at 14:25", LogChoice.jobLine("failed", "rewrite", created, updated, now, Z), "a failed job");
        eq("Paused while making shaders for this phone today at 14:25", LogChoice.jobLine("paused", "shaders", created, updated, now, Z), "a paused job");
        eq("Finished today at 14:25", LogChoice.jobLine("done", "pack", created, updated, now, Z), "a finished job");
        eq("Building now, started today at 13:00", LogChoice.jobLine("running", "split", created, updated, now, Z), "a running job");
        eq("Not started yet", LogChoice.jobLine("pending", "identify", created, 0, now, Z), "a pending job");

        // the words keep the app's text rules: no middle dot, no en or em dash
        for (String s : new String[] {LogChoice.buildLine(failed, false, now, Z), LogChoice.runLine(name, end, "", stopped, false, now, Z)}) {
            n++;
            if (s.indexOf('·') >= 0 || s.indexOf('–') >= 0 || s.indexOf('—') >= 0) throw new AssertionError("separator in: " + s);
        }
        System.out.println("ok " + n);
    }
}
