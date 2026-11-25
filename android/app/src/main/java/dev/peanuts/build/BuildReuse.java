package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a build took from earlier builds (issue #120, peanuts/docs/INCREMENTAL-BUILD.md), for the ready message on the card and in the notification: "It took 1 min
 * 16 s: 9,564 shaders, 3 parts of the game's code and 91 game files came from earlier builds." Read from the steps' logs in &lt;work&gt;/ondevice/logs, each only when this build's
 * job wrote it (a step that did not run leaves an earlier build's log) and only after its last "== " line (each run of a step starts one): the lines the reuse
 * tools print (dm/peanuts/reuse: shader_reuse.py "reuse check: …; N shaders kept", rewrite_reuse.py and pack_android.py "&lt;module&gt;: kept …" / "unchanged, kept",
 * split_reuse.py "split reuse: N of M files kept"). A check that failed keeps nothing, and says so in the log; then nothing is counted. No Android class here: the
 * JVM test (BuildReuseJvmTest, peanuts/tests/test_build_reuse.py) drives it.
 */
public final class BuildReuse {
    private BuildReuse() {}

    static final Pattern SHADERS = Pattern.compile("^reuse check: (\\d+) of \\d+ shaders translated to check are the older library's byte for byte; (\\d+) shaders kept");
    static final Pattern SHADERS_FAILED = Pattern.compile("^\\[RED\\] reuse check: ");
    static final Pattern MODULE_KEPT = Pattern.compile("^\\s*([A-Za-z0-9_.-]+): (kept from the app's copy of its last rewrite|kept, its build and its part of the profile are unchanged|unchanged, kept)");
    static final Pattern SPLIT = Pattern.compile("^split reuse: (\\d+) of (\\d+) files kept");

    /** What one build kept: shaders, modules, game files (the split's), each 0 when none */
    public static final class Kept {
        public final long shaders, modules, files;
        Kept(long shaders, long modules, long files) { this.shaders = shaders; this.modules = modules; this.files = files; }
        public boolean any() { return shaders > 0 || modules > 0 || files > 0; }
        @Override public String toString() { return shaders + " shaders, " + modules + " modules, " + files + " files"; }
    }

    /** The last run of a step's log, when this build wrote it: the text after its last "== " line; "" when it did not run in this build or cannot be read */
    static String lastRun(File log, long since) {
        if (log == null || !log.isFile() || log.lastModified() < since) return "";
        String t;
        try { t = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8); } catch (IOException e) { return ""; }
        int i = t.lastIndexOf("\n== "), at = i >= 0 ? i + 1 : t.startsWith("== ") ? 0 : -1;
        if (at < 0) return t;   // a log without a run line: all of it is this run's
        int nl = t.indexOf('\n', at);
        return nl < 0 ? "" : t.substring(nl + 1);
    }

    /** What the build that began at since (ms) kept, from &lt;work&gt;/ondevice/logs */
    public static Kept read(File logs, long since) {
        return parse(lastRun(new File(logs, "shaders.log"), since), lastRun(new File(logs, "rewrite.log"), since), lastRun(new File(logs, "split.log"), since));
    }

    static Kept parse(String shadersLog, String rewriteLog, String splitLog) {
        long shaders = 0, files = 0;
        java.util.Set<String> modules = new java.util.TreeSet<>();
        for (String l : shadersLog.split("\n")) {
            Matcher m = SHADERS.matcher(l.trim());
            if (m.find()) shaders = Long.parseLong(m.group(2));
            else if (SHADERS_FAILED.matcher(l.trim()).find()) shaders = 0;   // a check that failed: the kept shaders were translated again
        }
        for (String l : rewriteLog.split("\n")) {
            Matcher m = MODULE_KEPT.matcher(l);
            if (m.find()) modules.add(m.group(1));
        }
        for (String l : splitLog.split("\n")) {
            Matcher m = SPLIT.matcher(l.trim());
            if (m.find()) files = Long.parseLong(m.group(1));   // the last one: a split that found a kept result changed scans again and says so
        }
        return new Kept(shaders, modules.size(), files);
    }

    /** The ready message's sentence, "" when nothing was kept: "It took 1 min 16 s, and 9,564 shaders, 3 parts of the game's code and 91 game files were kept from before." */
    public static String text(Kept k, long seconds) {
        if (k == null || !k.any()) return "";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (k.shaders > 0) parts.add(String.format(Locale.ROOT, "%,d shader%s", k.shaders, k.shaders == 1 ? "" : "s"));
        if (k.modules > 0) parts.add(String.format(Locale.ROOT, "%,d part%s of the game's code", k.modules, k.modules == 1 ? "" : "s"));   // (not "modules": the player's words, UI-RULES 5)
        if (k.files > 0) parts.add(String.format(Locale.ROOT, "%,d game file%s", k.files, k.files == 1 ? "" : "s"));
        String list = parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        String took = seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s";
        return " It took " + took + ", and " + list + " were kept from before.";
    }
}
