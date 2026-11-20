// PEANUTS: Share build log's pure half (BuildLog, BuildLogBundle) on the JVM; dm/tools/tests/test_android_build_log.py compiles and runs it
package dev.peanuts.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The rules of Share build log that need no phone (no argument; prints "ok" or throws):
 * <ul>
 * <li>the build log: lines with the time, its run markers, the move to .1.log, the newest title, a title ID that is not one written nowhere;</li>
 * <li>unique bundle names and the cleaning of old bundles;</li>
 * <li>env.txt lines with a secret's name keep the name only;</li>
 * <li>the jobs: the plain build's and the builds with mods' beside it, a job of another title left out, one without job.json but with logs kept, a damaged
 *     job.json kept, a folder found twice once, two folders of one name kept apart;</li>
 * <li>the zip: a long log cut at line ends with its head and tail, a short one whole, no keys or game file whatever the caller hands it, the total kept,
 *     a log that grows while it is read taken as it was when opened, the .part file gone after finish;</li>
 * <li>the summary's words for ApplicationExitInfo's reasons and importances.</li>
 * </ul>
 */
public final class BuildLogJvmTest {
    private static final String T = "0100152000022000", OTHER = "01006BB00C6F0000";

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static File write(File f, String text) throws IOException {
        Files.createDirectories(f.getParentFile().toPath());
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    private static Map<String, String> unzip(File zip) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry x = e.nextElement();
                try (InputStream in = z.getInputStream(x)) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                    m.put(x.getName(), new String(b.toByteArray(), StandardCharsets.UTF_8));
                }
            }
        }
        return m;
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("buildlog").toFile();
        try {
            log(new File(root, "logs"));
            names(new File(root, "out"));
            env();
            jobs(new File(root, "jobs"));
            zip(new File(root, "zip"));
            words();
        } finally {
            deleteTree(root);
        }
        System.out.println("ok");
    }

    static void log(File dir) throws IOException {
        BuildLog.append(dir, "not a title", "nothing");
        check(dir.listFiles() == null || dir.listFiles().length == 0, "a title ID that is not one writes no file");
        BuildLog.append(dir, T.toLowerCase(), BuildLog.START + "build of Mario Kart 8 Deluxe (" + T + ")\nbase: a.nsp", 1_700_000_000_000L);
        File f = BuildLog.file(dir, T);
        check(f.getName().equals(T + ".log"), "the file is named by the title ID in upper case: " + f.getName());
        String text = read(f);
        String[] lines = text.split("\n");
        check(lines.length == 2 && lines[0].matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3} \\[start\\] build of .*") && lines[1].endsWith(" base: a.nsp"), "each line has the time: " + text);
        check(BuildLog.lastEnd(text) == null, "a run without its end line has no end");
        check(BuildLog.lastStart(text).equals("build of Mario Kart 8 Deluxe (" + T + ")"), "the newest run's start: " + BuildLog.lastStart(text));
        BuildLog.append(dir, T, BuildLog.END + "failed (tool_stopped): Preparing failed at finding the shaders.");
        check("failed (tool_stopped): Preparing failed at finding the shaders.".equals(BuildLog.lastEnd(read(f))), "the end line: " + BuildLog.lastEnd(read(f)));
        BuildLog.append(dir, T, BuildLog.START + "build of Mario Kart 8 Deluxe (" + T + "), the second");
        check(BuildLog.lastEnd(read(f)) == null, "the end of an earlier run is not the newest run's");
        check(BuildLog.lastEnd(null) == null && BuildLog.lastStart("") == null, "no text, no run");
        // the move to .1.log at ROLL bytes; one older file is kept
        char[] big = new char[(int) BuildLog.ROLL];
        Arrays.fill(big, 'x');
        BuildLog.append(dir, T, new String(big));
        BuildLog.append(dir, T, "after the move");
        File older = BuildLog.older(dir, T);
        check(older.isFile() && older.length() > BuildLog.ROLL, "the full log moved to .1.log");
        check(read(f).trim().endsWith("after the move") && f.length() < 100, "the new log starts empty: " + f.length());
        BuildLog.append(dir, T, new String(big));
        BuildLog.append(dir, T, "after the second move");
        check(read(BuildLog.older(dir, T)).contains("after the move") && !read(BuildLog.older(dir, T)).contains("[start]"), "the second move replaces the older file");
        // the newest title
        BuildLog.append(dir, OTHER, "one line");
        new File(dir, OTHER + ".log").setLastModified(System.currentTimeMillis() + 60_000);
        check(OTHER.equals(BuildLog.newest(dir)), "the newest log's title: " + BuildLog.newest(dir));
        write(new File(dir, "notes.log"), "not a build log");
        new File(dir, "notes.log").setLastModified(System.currentTimeMillis() + 120_000);
        check(OTHER.equals(BuildLog.newest(dir)), "a file that is not a title's log is not a build log");
        check(BuildLog.newest(new File(dir, "none")) == null, "no folder, no newest");
        check(BuildLog.tail(f, 10).length() == 10 && BuildLog.tail(new File(dir, "none.log"), 10).isEmpty(), "tail");
    }

    static void names(File dir) throws IOException {
        String n = BuildLogBundle.zipName(T.toLowerCase(), 1_700_000_000_000L);
        check(n.matches("build-log-" + T + "-\\d{8}-\\d{6}-[0-9a-f]{32}\\.zip"), "the bundle's name: " + n);
        check(BuildLogBundle.validName(n), "its own name is valid");
        String n2 = BuildLogBundle.zipName(T, 1_700_000_000_000L);
        check(!n.equals(n2) && BuildLogBundle.validName(n2), "same-title exports in the same second use separate files");
        check(BuildLogBundle.validName("build-log-" + T + "-20261118-201500.zip"), "older bundles remain readable");
        for (String bad : new String[] {null, "", "../" + n, "x/" + n, n + ".part", "build-log-" + T + ".zip", "build-log-0100152000022000-20261118-201500.zip.log",
                "prod.keys", T + ".log", "build-log-01001520000220000-20261118-201500.zip", "build-log-0100152000022000-20261118-201500.ZIP"})
            check(!BuildLogBundle.validName(bad), "not a bundle name: " + bad);
        // cleaning: older than a day or past the newest keep, and nothing that is not a bundle
        long now = System.currentTimeMillis();
        List<File> made = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            File f = write(new File(dir, BuildLogBundle.zipName(T, now - i * 60_000L)), "zip " + i);
            f.setLastModified(now - i * 60_000L);
            made.add(f);
        }
        File old = write(new File(dir, BuildLogBundle.zipName(OTHER, now - 2 * BuildLogBundle.MAX_AGE)), "old");
        old.setLastModified(now - 2 * BuildLogBundle.MAX_AGE);
        File other = write(new File(dir, "keep-me.zip"), "not ours");
        other.setLastModified(now - 3 * BuildLogBundle.MAX_AGE);
        File part = write(new File(dir, made.get(0).getName() + ".part"), "being written");
        part.setLastModified(now);
        BuildLogBundle.clean(dir, now, BuildLogBundle.MAX_AGE, 4);
        for (int i = 0; i < 7; i++) check(made.get(i).exists() == (i < 4), "the newest four stay: " + i);
        check(!old.exists(), "a bundle older than a day goes");
        check(other.exists(), "a file that is not a bundle stays");
        check(part.exists(), "a zip being written now stays");
        part.setLastModified(now - 2 * 3600 * 1000);
        BuildLogBundle.clean(dir, now, BuildLogBundle.MAX_AGE, 4);
        check(!part.exists(), "a .part left by an ended process goes");
        BuildLogBundle.clean(new File(dir, "none"), now, 1, 1);   // no folder: nothing to do
    }

    static void env() {
        check(BuildLogBundle.redactEnv("DMSH_VK_XLATE_J=6").equals("DMSH_VK_XLATE_J=6"), "a count stays");
        check(BuildLogBundle.redactEnv("BUILD_RAM_MB=7500").equals("BUILD_RAM_MB=7500"), "the memory stand-in stays");
        check(BuildLogBundle.redactEnv("GITHUB_TOKEN=ghp_abc").equals("GITHUB_TOKEN=<hidden>"), "a token is hidden");
        check(BuildLogBundle.redactEnv(" api_key = 123").equals("api_key=<hidden>"), "a key's name, any case, is hidden");
        check(BuildLogBundle.redactEnv("PASSWORD=x").equals("PASSWORD=<hidden>") && BuildLogBundle.redactEnv("MY_SECRET=").equals("MY_SECRET=<hidden>"), "pass, secret");
        check(BuildLogBundle.redactEnv("# TOKEN=x comment").equals("# TOKEN=x comment"), "a comment stays as it is");
        check(BuildLogBundle.redactEnv("no equals sign").equals("no equals sign"), "a line without = stays");
        check(BuildLogBundle.redactEnvText("A=1\nAUTH=z\n").equals("A=1\nAUTH=<hidden>\n"), "the whole text: " + BuildLogBundle.redactEnvText("A=1\nAUTH=z\n"));
    }

    static void jobs(File root) throws IOException {
        File games = new File(root, "Games/Mario Kart 8 Deluxe");
        File work = new File(games, T + "-work");
        write(new File(work, "ondevice/job.json"), "{\n \"format\": 2,\n \"title_id\": \"" + T + "\",\n \"name\": \"Mario Kart 8 Deluxe\",\n \"steps\": {\"split\": {\"state\": \"pending\"}}\n}");
        write(new File(work, "ondevice/logs/split.log"), "progress: 0/158000\n");
        File mod = new File(games, T + "-mod-1a2b-work");
        write(new File(mod, "ondevice/job.json"), "{\"title_id\":\"" + T + "\",\"mod_args\":\"--modules\\nmain\"}");
        File modLooking = new File(games, T + "-x-work");   // a variant whose folder name says nothing: its job.json's mod_args does
        write(new File(modLooking, "ondevice/job.json"), "{\"mod_args\":\"--mod-exefs\\n\\/sdcard\\/mods\",\"title_id\":\"" + T.toLowerCase() + "\"}");
        write(new File(games, OTHER + "-work/ondevice/job.json"), "{\"title_id\":\"" + OTHER + "\"}");   // another game's folder beside it: not looked at
        File build = new File(root, "builds/" + T);
        write(new File(build, T + "-work/ondevice/job.json"), "{\"title_id\":\"" + OTHER + "\"}");   // named like ours, but its job is another title's
        File picked = new File(root, "app/picked/k1/" + T + "-work");
        write(new File(picked, "ondevice/logs/rewrite.log"), "rewrote main\n");   // only logs: kept (the job.json may have gone)
        File damaged = new File(root, "elsewhere/" + T + "-work");
        write(new File(damaged, "ondevice/job.json"), "{\"title_id\": ");   // damaged: kept, its logs tell something
        File empty = new File(root, "empty/" + T + "-work");
        Files.createDirectories(new File(empty, "ondevice").toPath());   // nothing in it: left out
        File sameName = new File(root, "second/" + T + "-work");
        write(new File(sameName, "ondevice/job.json"), "{\"title_id\":\"" + T + "\"}");
        File noWork = new File(root, "nowhere/" + T + "-work");   // not there at all

        List<BuildLogBundle.Job> j = BuildLogBundle.jobs(T, Arrays.asList(work, build.toPath().resolve(T + "-work").toFile(), picked, damaged, empty, sameName, noWork, null),
                Collections.singletonList(new File(work, "ondevice/job.json")));
        List<String> got = new ArrayList<>();
        for (BuildLogBundle.Job x : j) got.add((x.mods ? "m:" : "j:") + x.label + ":" + x.dir.getParentFile().getParentFile().getName());
        List<String> want = Arrays.asList("j:" + T + "-work:Mario Kart 8 Deluxe", "j:" + T + "-work-2:k1", "j:" + T + "-work-3:elsewhere", "j:" + T + "-work-4:second",
                "m:" + T + "-mod-1a2b-work:Mario Kart 8 Deluxe", "m:" + T + "-x-work:Mario Kart 8 Deluxe");
        check(got.equals(want), "the jobs: " + got);
        check(j.get(0).file.equals(new File(new File(work, "ondevice"), "job.json")) && j.get(0).logs().equals(new File(work, "ondevice/logs")), "a job's files");
        check(BuildLogBundle.jobs("nope", Collections.singletonList(work), null).isEmpty(), "no title, no jobs");
        check(BuildLogBundle.jobs(T, null, null).isEmpty(), "nothing to look in, no jobs");
        check("--modules\nmain".equals(BuildLogBundle.field("{\"mod_args\":\"--modules\\nmain\"}", "mod_args")), "a field is unescaped");
        check("/sdcard/a \"b\"".equals(BuildLogBundle.field("{\"dump\": \"\\/sdcard\\/a \\\"b\\\"\"}", "dump")), "escaped slashes and quotes");
        check(BuildLogBundle.field("{\"x\":1}", "title_id") == null, "a missing field");
    }

    static void zip(File dir) throws IOException {
        // a long log: 3 MB of numbered lines
        StringBuilder b = new StringBuilder();
        for (int i = 0; b.length() < 3 << 20; i++) b.append("line ").append(i).append(" of the shaders step, translating one shader\n");
        File longLog = write(new File(dir, "src/shaders.log"), b.toString());
        File shortLog = write(new File(dir, "src/split.log"), "progress: 2000/158000\nshort\n");
        File keys = write(new File(dir, "src/prod.keys"), "header_key = 00");
        File nup = write(new File(dir, "src/Mario Kart 8 Deluxe.nup"), "NUP0");
        File keysLog = write(new File(dir, "src/title.keys.log"), "x");
        File out = new File(dir, "out/" + BuildLogBundle.zipName(T, System.currentTimeMillis()));
        BuildLogBundle.Zip z = new BuildLogBundle.Zip(out);
        check(z.file("job/a/logs/shaders.log", longLog), "the long log goes in");
        check(z.file("job/a/logs/split.log", shortLog), "the short log goes in");
        check(!z.file("job/a/prod.keys", keys) && !z.file("job/a/logs/x.log", keys), "keys never go in, under any name");
        check(!z.file("job/a/game.log", nup), "a game file never goes in");
        check(!z.file("job/a/logs/title.keys.log", keysLog), "a file whose name has keys never goes in");
        check(!z.file("job/a/logs/none.log", new File(dir, "src/none.log")) && !z.file("job/a/logs/dir.log", new File(dir, "src")), "nothing or a folder: not added");
        boolean refused = false;
        try { z.text("prod.keys", "x"); } catch (IOException e) { refused = true; }
        check(refused, "a text under a keys name is refused");
        refused = false;
        try { z.file("job/a/logs/split.log", shortLog); z.text("job/a/logs/split.log", "again"); } catch (IOException e) { refused = true; }
        check(refused, "a path twice is refused");
        z.text("summary.txt", "the summary");
        File done = z.finish();
        check(done.equals(out) && out.isFile() && !new File(out.getPath() + ".part").exists(), "the zip has its name and no .part");
        Map<String, String> m = unzip(out);
        check(new ArrayList<>(m.keySet()).equals(Arrays.asList("job/a/logs/shaders.log", "job/a/logs/split.log", "summary.txt")), "the entries: " + m.keySet());
        check(m.get("job/a/logs/split.log").equals(read(shortLog)), "a short log is whole");
        String cut = m.get("job/a/logs/shaders.log");
        String full = read(longLog);
        int mark = cut.indexOf("\n[Share build log: ");
        check(mark > 0 && cut.length() <= BuildLogBundle.PER_FILE + 300, "the long log is cut to about PER_FILE: " + cut.length());
        String head = cut.substring(0, mark), tail = cut.substring(cut.indexOf("]\n\n", mark) + 3);
        check(full.startsWith(head) && head.endsWith("\n") && head.length() <= BuildLogBundle.HEAD, "the head is the file's start, at a line end");
        check(full.endsWith(tail) && full.charAt(full.length() - tail.length() - 1) == '\n' && tail.startsWith("line "), "the tail is the file's end, from a line start");
        check(z.cut.size() == 1 && z.cut.get(0).startsWith("job/a/logs/shaders.log: 3.0 MB"), "the summary's cut line: " + z.cut);
        check(z.left.size() == 4, "left out: " + z.left);
        check(z.unreadable.size() == 1 && z.unreadable.get(0).contains("twice"), "the path given twice: " + z.unreadable);
        check(z.files == 3, "three entries: " + z.files);

        // the total: a second file past it is listed, not added
        File out2 = new File(dir, "out/" + BuildLogBundle.zipName(OTHER, 0));
        try (BuildLogBundle.Zip z2 = new BuildLogBundle.Zip(out2, 1000, 400, 1500)) {
            check(z2.file("a.log", longLog), "the first fits");
            check(!z2.file("b.log", longLog), "the second does not");
            check(z2.left.size() == 1 && z2.left.get(0).startsWith("b.log (3.0 MB): the bundle was full"), "the left line: " + z2.left);
            z2.finish();
        }
        check(unzip(out2).keySet().equals(Collections.singleton("a.log")), "only the first is in it");

        // a log that grows while it is read: its entry is the file as it was at some moment (a prefix of the end state, whole lines kept by the writer)
        File grows = write(new File(dir, "src/grows.log"), "");
        AtomicBoolean stop = new AtomicBoolean();
        Thread w = new Thread(() -> {
            try (FileOutputStream o = new FileOutputStream(grows, true)) {
                for (int i = 0; !stop.get() && i < 2_000_000; i++) o.write(("progress: " + i + "/158000\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        w.start();
        while (grows.length() < 64 << 10) Thread.yield();
        File out3 = new File(dir, "out3/" + BuildLogBundle.zipName(T, 0));
        try (BuildLogBundle.Zip z3 = new BuildLogBundle.Zip(out3, 8L << 20, 1 << 20, 64L << 20)) {
            check(z3.file("job/a/logs/split.log", grows), "a growing log is read");
            z3.finish();
        }
        stop.set(true);
        try { w.join(); } catch (InterruptedException e) { throw new IOException(e); }
        String got = unzip(out3).get("job/a/logs/split.log"), end = read(grows);
        check(got.length() >= 64 << 10 && end.startsWith(got), "the entry is the file as it was when it was opened: " + got.length() + " of " + end.length());

        // a zip given up (an error on the way) leaves no file behind
        File out4 = new File(dir, "out4/" + BuildLogBundle.zipName(T, 0));
        try (BuildLogBundle.Zip z4 = new BuildLogBundle.Zip(out4)) { z4.text("summary.txt", "x"); }
        check(!out4.exists() && !new File(out4.getPath() + ".part").exists(), "a zip not finished is deleted");

        check(BuildLogBundle.allowed("split.log") && BuildLogBundle.allowed("job.json") && BuildLogBundle.allowed("env.txt"), "the text files");
        check(!BuildLogBundle.allowed("patched.bin") && !BuildLogBundle.allowed("dmsh_vk.lib") && !BuildLogBundle.allowed("save.json") && !BuildLogBundle.allowed("game.nsp.log"), "nothing else");
        check(BuildLogBundle.size(512).equals("512 bytes") && BuildLogBundle.size(2048).equals("2 KB") && BuildLogBundle.size(1572864).equals("1.5 MB")
                && BuildLogBundle.size(3L << 30).equals("3.0 GB") && BuildLogBundle.size(93684L << 20).equals("91 GB"), "sizes");
    }

    static void words() {
        check(BuildLogBundle.reason(3).equals("low_memory") && BuildLogBundle.meaning(3, 0).equals("Android ended it to free memory."), "low memory");
        check(BuildLogBundle.reason(4).equals("crash") && BuildLogBundle.reason(5).equals("crash_native") && BuildLogBundle.reason(6).equals("anr"), "crashes, ANR");
        check(BuildLogBundle.meaning(2, 9).contains("low-memory killer") && BuildLogBundle.meaning(2, 11).equals("Ended by signal 11."), "a signal");
        check(BuildLogBundle.reason(99).equals("unknown") && BuildLogBundle.meaning(99, 0).isEmpty(), "an unknown reason");
        check(BuildLogBundle.importance(125).equals("foreground_service") && BuildLogBundle.importance(400).equals("cached") && BuildLogBundle.importance(7).equals("7"), "importance");
        String t = new BuildLogBundle.Text().head("App").kv("version", "0.1.4").kv("error", "two\nlines").kv("none", null).toString();
        check(t.equals("== App\nversion: 0.1.4\nerror: two\n    lines\nnone: -\n"), "the summary's text: " + t);
        check(BuildLogBundle.time(0).equals("-") && BuildLogBundle.time(1_700_000_000_000L).matches("2023-11-1\\d \\d\\d:\\d\\d:\\d\\d [+-]\\d{4}"), "times");
    }
}
