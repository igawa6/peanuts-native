// PEANUTS: ShaderUpgrade (the in-place shader upgrade, ~/pn-build/xver-upgrade-spec.md) and CheckDue's unchecked marker on the JVM: which libraries of a pack
// get an upgrade, the tool's two calls and every way they end, the marker that names its library by id (critique B1, review M3/M4), the job record an upgrade
// makes current (M1). peanuts/tests/test_shader_upgrade.py compiles and runs it ("--id <file>" prints a library's id, which that test compares with shader_reuse.py's).
// The versions are made up: nothing here depends on this tree's DMSH_VK_CHAIN
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShaderUpgradeJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static final String CUR = "aaaaaaaaaaaa", OLD = "bbbbbbbbbbbb", OLDER = "cccccccccccc", ABI = "111111111111", OLD_ABI = "222222222222", GAME = "0100ABCD00000000",
            IN = "0123456789ab", OTHER_IN = "ba9876543210";

    /** A library file: dmsh_vk_header's magic, format, vk_chain at 24, game at 40, inputs at 56, blob_off 128 at 96, blob_size at 104, abi at 116, then a blob */
    static File lib(File f, String version, String game, String abi, String inputs, long time) throws IOException {
        byte[] h = new byte[128 + 64];
        int magic = ShaderUpgrade.MAGIC;
        for (int i = 0; i < 4; i++) h[i] = (byte) (magic >> (8 * i));
        h[4] = 1;
        put(h, 24, version); put(h, 40, game); put(h, 56, inputs); put(h, 116, abi);
        h[96] = (byte) 128; h[104] = 64;
        for (int i = 128; i < h.length; i++) h[i] = (byte) (version.charAt(0) + i);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), h);
        if (time > 0) f.setLastModified(time);
        return f;
    }

    static File lib(File f, String version, String game, String abi, long time) throws IOException { return lib(f, version, game, abi, IN, time); }

    private static void put(byte[] h, int at, String s) { byte[] b = s.getBytes(StandardCharsets.US_ASCII); System.arraycopy(b, 0, h, at, b.length); }

    static File libOf(File pack, String v) { return new File(pack, "shaders/v-" + v + "/dmsh_vk.lib"); }

    private static void append(File log, String text) {
        try { Files.write(log.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException e) { throw new AssertionError(e); }
    }

    private static void write(File f, String text) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }

    private static void deleteTree(File f) { File[] l = f.listFiles(); if (l != null) for (File c : l) deleteTree(c); f.delete(); }

    /** A tool that answers --path from a table (path → code) and writes the upgrade as told (what: "ok", "bad-abi", "bad-inputs", "fail", "none") */
    static final class Fake implements ShaderUpgrade.Tool {
        final Map<String, Integer> path = new HashMap<>();
        String what = "ok";
        final List<String> calls = new ArrayList<>();
        final File log;
        Fake(File log) { this.log = log; }
        public int run(String[] argv) {
            calls.add(String.join(" ", argv));
            try {
                if (argv[0].equals("--path")) {
                    int c = path.getOrDefault(argv[1], 3);
                    append(log, c == 0 ? OLD + " " + CUR + "\n" : c == 3 ? "no upgrade: unknown version\n" : "[RED] upgrade failed: OSError: x\n");
                    return c;
                }
                File out = new File(argv[1]), tmp = new File(argv[1] + ".tmp");
                out.getParentFile().mkdirs();
                append(log, "stage: upgrading the shaders\nprogress: 5/10\nprogress: 10/10\n");
                switch (what) {
                    case "ok": lib(tmp, CUR, GAME, ABI, 0); Files.move(tmp.toPath(), out.toPath()); write(new File(out.getParentFile(), "inputs.tsv"), "inputs\tx\n");
                        append(log, "upgraded " + GAME + " v-" + OLD + " -> v-" + CUR + ": 9 entries, 2 rewritten (1 dual, 1 sampler), 0.4 s\n"); return 0;
                    case "bad-abi": lib(tmp, CUR, GAME, OLD_ABI, 0); Files.move(tmp.toPath(), out.toPath()); return 0;
                    case "bad-inputs": lib(tmp, CUR, GAME, ABI, OTHER_IN, 0); Files.move(tmp.toPath(), out.toPath()); return 0;
                    case "fail": lib(tmp, CUR, GAME, ABI, 0); append(log, "[RED] upgrade failed: OSError: no space\n"); return 1;   // a .tmp left behind
                    default: append(log, "no upgrade: x\n"); return 3;
                }
            } catch (IOException e) { throw new AssertionError(e); }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--id")) { System.out.println(ShaderUpgrade.libraryId(new File(args[1]))); return; }
        File root = new File(args.length > 0 ? args[0] : "build/shader-upgrade-test");
        deleteTree(root);
        root.mkdirs();

        // ---- candidates ----
        File pack = new File(root, "pack");
        File a = lib(libOf(pack, OLD), OLD, GAME, OLD_ABI, 2_000_000_000_000L);
        File b = lib(libOf(pack, OLDER), OLDER, GAME, OLD_ABI, 1_000_000_000_000L);
        lib(libOf(pack, "dddddddddddd"), "dddddddddddd", "0100FFFF00000000", OLD_ABI, 0);   // another game's
        lib(libOf(pack, "eeeeeeeeeeee"), "ffffffffffff", GAME, OLD_ABI, 0);   // a header that names another version than its folder
        lib(libOf(pack, "777777777777"), "777777777777", GAME, OLD_ABI, OTHER_IN, 0);   // other shader tables (review M2)
        File marked = lib(libOf(pack, "999999999999"), "999999999999", GAME, OLD_ABI, 0);
        write(ShaderUpgrade.marker(marked), ShaderUpgrade.libraryId(marked) + "\n");
        File legacy = lib(libOf(pack, "666666666666"), "666666666666", GAME, OLD_ABI, 0);
        write(ShaderUpgrade.marker(legacy), "kept shaders never checked\n");   // names no id: refused whatever it was for
        File rebuilt = lib(libOf(pack, "555555555555"), "555555555555", GAME, OLD_ABI, 500_000_000_000L);
        write(ShaderUpgrade.marker(rebuilt), "0123456789abcdef0123456789abcdef\n");   // another library's line: this one, rebuilt since, is not refused (M4)
        File junk = libOf(pack, "888888888888"); write(junk, "not a library");
        new File(pack, "shaders/v-" + CUR).mkdirs();   // an empty folder of this version (a stopped job's): no library yet
        List<File> c = ShaderUpgrade.candidates(pack, GAME, CUR, IN);
        check(c.equals(Arrays.asList(a, b, rebuilt)), "the game's older libraries of its inputs that no marker names, newest first: " + c);
        check(ShaderUpgrade.candidates(pack, GAME.toLowerCase(java.util.Locale.ROOT), CUR, IN).size() == 3, "the title ID in any case");
        check(ShaderUpgrade.candidates(pack, GAME, "not-a-version", IN).isEmpty(), "no version, no upgrade");
        check(ShaderUpgrade.candidates(pack, GAME, CUR, null).isEmpty() && ShaderUpgrade.candidates(pack, GAME, CUR, "unknown").isEmpty(), "no profile inputs, no upgrade");
        check(ShaderUpgrade.candidates(pack, GAME, CUR, OTHER_IN).size() == 1, "only the libraries of the profile's inputs");
        check(ShaderUpgrade.candidates(new File(root, "none"), GAME, CUR, IN).isEmpty(), "no pack, none");
        check(ShaderUpgrade.unchecked(marked) && ShaderUpgrade.unchecked(legacy) && !ShaderUpgrade.unchecked(rebuilt) && !ShaderUpgrade.unchecked(a), "the marker refuses the library it names");
        deleteTree(rebuilt.getParentFile());
        c = ShaderUpgrade.candidates(pack, GAME, CUR, IN);

        // ---- withoutDueCheck: a check due from an earlier version is never run: marked by its id and skipped ----
        String aid = ShaderUpgrade.libraryId(a);
        check(aid != null && aid.matches("[0-9a-f]{32}"), "an id: " + aid);
        check(ShaderUpgrade.withoutDueCheck(c, null, CUR, pack) == c && ShaderUpgrade.withoutDueCheck(c, new String[] {aid, CUR}, CUR, pack) == c, "no check of another version due");
        List<File> w = ShaderUpgrade.withoutDueCheck(c, new String[] {aid, OLD}, CUR, pack);
        check(w.equals(Arrays.asList(b)) && CheckDue.ids(ShaderUpgrade.read(ShaderUpgrade.marker(a))).equals(Arrays.asList(aid)), "the library with the due check is marked by its id and left out");
        check(ShaderUpgrade.candidates(pack, GAME, CUR, IN).equals(Arrays.asList(b)), "and not offered again");
        ShaderUpgrade.marker(a).delete();

        // ---- run ----
        File log = new File(root, "u.log");
        File target = ShaderUpgrade.target(pack, CUR);
        Fake t = new Fake(log);
        t.path.put(b.getPath(), 0);   // only the older one has a path
        ShaderUpgrade.Result r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, t, log);
        check(r.code == ShaderUpgrade.UPGRADED && b.equals(r.from), "upgraded from the one with a path: " + r.code + " " + r.why);
        check(r.line.startsWith("upgraded " + GAME + " v-" + OLD), "its summary: " + r.line);
        check(t.calls.equals(Arrays.asList("--path " + a.getPath(), "--path " + b.getPath(), b.getPath() + " " + target.getPath())), "--path each, the upgrade once: " + t.calls);
        check(ShaderUpgrade.verify(target, GAME, CUR, ABI, IN) == null && a.isFile() && b.isFile(), "this version's library written, the older ones kept");
        t.calls.clear();
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, t, log);
        check(r.code == ShaderUpgrade.NO_PATH && t.calls.isEmpty(), "with this version's library there, nothing is run");
        check(ShaderUpgrade.candidates(pack, GAME, CUR, IN).isEmpty(), "and nothing is a candidate");
        deleteTree(target.getParentFile());

        Fake none = new Fake(log);
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, none, log);
        check(r.code == ShaderUpgrade.NO_PATH && none.calls.size() == 2 && !target.exists() && !target.getParentFile().exists(), "exit 3 everywhere: no path, nothing written");
        check(r.why.contains("no upgrade path"), "why: " + r.why);
        Fake err = new Fake(log);
        err.path.put(a.getPath(), 1);
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, err, log);
        check(r.code == ShaderUpgrade.ERROR && err.calls.size() == 2 && !target.exists(), "a read error is an error");

        for (String bad : new String[] {"bad-abi", "bad-inputs"}) {   // the tool says 0 but wrote something that is not this version's: it goes, with the folder the run made
            Fake f = new Fake(log);
            f.path.put(a.getPath(), 0);
            f.what = bad;
            r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, f, log);
            check(r.code == ShaderUpgrade.ERROR && !target.exists() && !target.getParentFile().exists(), bad + ": a wrong library is deleted: " + r.why);
        }
        Fake half = new Fake(log);
        half.path.put(a.getPath(), 0);
        half.what = "fail";
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, half, log);
        check(r.code == ShaderUpgrade.ERROR && !target.exists() && !new File(target.getPath() + ".tmp").exists() && a.isFile(), "a failed upgrade leaves the pack as it was: " + r.why);
        check(r.why.contains("no space"), "its last line in why: " + r.why);
        target.getParentFile().mkdirs();
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, half, log);
        check(r.code == ShaderUpgrade.ERROR && target.getParentFile().isDirectory(), "the folder that was there stays");
        Fake late = new Fake(log);
        late.path.put(a.getPath(), 0);
        late.what = "none";
        r = ShaderUpgrade.run(c, target, GAME, CUR, ABI, IN, late, log);
        check(r.code == ShaderUpgrade.NO_PATH && late.calls.size() == 3 && !target.exists(), "exit 3 at the upgrade is no path: " + late.calls);
        check(ShaderUpgrade.usable(target) > 0, "free space where the library goes");

        // ---- cleanStale: a killed upgrade's .tmp ----
        File tmp = new File(target.getPath() + ".tmp");
        write(tmp, "half");
        long now = System.currentTimeMillis();
        check(!ShaderUpgrade.cleanStale(pack, CUR, now) && tmp.isFile(), "a .tmp being written stays");
        check(ShaderUpgrade.cleanStale(pack, CUR, now + ShaderUpgrade.STALE_MS + 1000) && !tmp.exists() && !target.getParentFile().exists(), "an old one goes, with the folder it left empty");
        write(tmp, "half"); write(new File(target.getParentFile(), "inputs.tsv"), "x");
        check(ShaderUpgrade.cleanStale(pack, CUR, now + ShaderUpgrade.STALE_MS + 1000) && target.getParentFile().isDirectory(), "a folder with more in it stays");
        deleteTree(target.getParentFile());

        // ---- verify, progress, summary, texts ----
        File v = lib(new File(root, "v/dmsh_vk.lib"), CUR, GAME, ABI, 0);
        check(ShaderUpgrade.verify(v, GAME, CUR, ABI, IN) == null, "this version's");
        check(ShaderUpgrade.verify(v, GAME, OLD, ABI, IN) != null && ShaderUpgrade.verify(v, "0100FFFF00000000", CUR, ABI, IN) != null && ShaderUpgrade.verify(v, GAME, CUR, OLD_ABI, IN) != null
                && ShaderUpgrade.verify(v, GAME, CUR, ABI, OTHER_IN) != null, "another version, game, ABI or inputs");
        check(ShaderUpgrade.verify(new File(root, "v/none.lib"), GAME, CUR, ABI, IN) != null && ShaderUpgrade.verify(junk, GAME, CUR, ABI, IN) != null, "missing or not a library");
        long[] p = ShaderUpgrade.progress("stage: upgrading the shaders\nprogress: 3/40\nprogress: 12/40\nupgraded x\n");
        check(p != null && p[0] == 12 && p[1] == 40, "the last progress line");
        p = ShaderUpgrade.progress("progress: 40/40\nstage: saving the shaders: 1,200 of 9,800 MB\n");
        check(p != null && p[0] == 1200 && p[1] == 9800, "then the file's write, in MB");
        check(ShaderUpgrade.progress("stage: x\n") == null && ShaderUpgrade.progress(null) == null, "none yet");
        check(ShaderUpgrade.progressText(new long[] {12, 40}).equals("Updating the game's shaders for this app version, 30 %."), ShaderUpgrade.progressText(new long[] {12, 40}));
        check(ShaderUpgrade.progressText(null).equals("Updating the game's shaders for this app version."), "before any progress");
        check(ShaderUpgrade.waitText(1L << 20, true).equals("This usually takes a few seconds. Then the game starts."), ShaderUpgrade.waitText(1L << 20, true));
        check(ShaderUpgrade.waitText(1L << 20, false).equals("This usually takes a few seconds."), "the game page's Update shaders: no game starts");
        check(ShaderUpgrade.waitText(3L << 30, false).equals("This can take a minute."), "a big library");
        check(ShaderUpgrade.summary("a\nupgraded G v-1 -> v-2: 3 entries\n").equals("upgraded G v-1 -> v-2: 3 entries") && ShaderUpgrade.summary("x").isEmpty(), "summary");
        String k = ShaderUpgrade.refusedKey(a, CUR);
        check(k.equals(ShaderUpgrade.refusedKey(a, CUR)) && !k.equals(ShaderUpgrade.refusedKey(a, OLD)) && !k.equals(ShaderUpgrade.refusedKey(b, CUR)), "a refusal is the library's and this version's");
        check(ShaderUpgrade.logFile(new File("/f"), "0100abcd00000000").getPath().equals("/f/upgrade/0100ABCD00000000.log"), "the log's place");
        check(ShaderUpgrade.libraryId(junk) == null && !aid.equals(ShaderUpgrade.libraryId(b)), "ids");

        // ---- the job record an upgrade makes current (review M1) ----
        Map<String, String> app = new HashMap<>(Map.of("engine", "native", "dmsh_chain", "c1", "dmsh_vk_chain", CUR, "xlate_source", "x1"));
        Map<String, String> job = new HashMap<>(app);
        check(!ShaderUpgrade.onlyVkChainDiffers(job::get, app::get), "the same app: nothing to take");
        job.put("dmsh_vk_chain", OLD);
        check(ShaderUpgrade.onlyVkChainDiffers(job::get, app::get), "only the shader version older: taken");
        job.put("xlate_source", "x0");
        check(!ShaderUpgrade.onlyVkChainDiffers(job::get, app::get), "another translator too: not taken (its shaders are translated again)");
        job.put("xlate_source", "x1"); job.put("engine", "fake");
        check(!ShaderUpgrade.onlyVkChainDiffers(job::get, app::get), "another engine: not taken");

        // ---- the marker a dropped check leaves (CheckDue, critique B1), by id ----
        File rd = new File(root, "reuse");
        String todo = "{\"library\": \"" + aid + "\", \"version\": \"" + OLD + "\", \"game\": \"" + GAME + "\", \"kept\": 9}";
        write(new File(rd, CheckDue.TODO), todo);
        check(Arrays.equals(CheckDue.todo(rd), new String[] {aid, OLD}), "check.todo's library and version");
        CheckDue.drop(rd, pack);
        check(CheckDue.ids(ShaderUpgrade.read(ShaderUpgrade.marker(a))).equals(Arrays.asList(aid)) && !new File(rd, CheckDue.TODO).exists(), "a drop marks the pack's library by the todo's id");
        check(!ShaderUpgrade.candidates(pack, GAME, CUR, IN).contains(a), "and it is no candidate after");
        ShaderUpgrade.marker(a).delete();
        CheckDue.drop(rd, pack);
        check(!ShaderUpgrade.marker(a).exists(), "nothing due: nothing marked");
        write(new File(rd, CheckDue.TODO), todo.replace(aid, "f".repeat(32)));
        CheckDue.drop(rd, pack);
        check(!ShaderUpgrade.unchecked(a) && ShaderUpgrade.candidates(pack, GAME, CUR, IN).contains(a), "a drop of another library's check refuses nothing here");
        ShaderUpgrade.marker(a).delete();

        String[] before = {aid, OLD};
        write(new File(rd, CheckDue.TODO), todo);
        check(CheckDue.markIfDropped(rd, pack, before) == null && !ShaderUpgrade.marker(a).exists(), "still due: not marked");
        new File(rd, CheckDue.TODO).delete();   // as shader_reuse.py drop_check leaves it
        check(CheckDue.markIfDropped(rd, pack, null) == null, "nothing was due");
        File m = CheckDue.markIfDropped(rd, pack, before);
        check(m != null && m.equals(ShaderUpgrade.marker(a)) && ShaderUpgrade.unchecked(a), "dropped by the tool: marked");
        m.delete();
        write(new File(rd, CheckDue.CHECKED), aid + "\t9\t9\t2026-11-24 10:00:00\n");
        check(CheckDue.markIfDropped(rd, pack, before) == null, "the check passed: not marked");
        new File(rd, CheckDue.CHECKED).delete();
        write(new File(rd, CheckDue.FAILED), "{\"library\": \"" + aid + "\", \"replaced\": true}");
        check(CheckDue.markIfDropped(rd, pack, before) == null, "the full translation took its place: not marked");
        new File(rd, CheckDue.FAILED).delete();
        check(CheckDue.markIfDropped(rd, new File(root, "nopack"), before) == null, "no such library: nothing to mark");
        check(CheckDue.markUnchecked(pack, "not-hex", aid) == null && CheckDue.markUnchecked(pack, OLD, "x") == null, "only a version and an id");

        // ---- copyMarker: the pack step takes a library's marker into the pack, merged ----
        File cache = lib(new File(root, "cache/v-" + OLD + "/dmsh_vk.lib"), OLD, GAME, OLD_ABI, 0);
        check(CheckDue.copyMarker(cache, a) == 0 && !ShaderUpgrade.marker(a).exists(), "no marker, none copied");
        write(ShaderUpgrade.marker(cache), aid + "\n");
        write(ShaderUpgrade.marker(a), "e".repeat(32) + "\n");
        check(CheckDue.copyMarker(cache, a) == 1 && CheckDue.ids(ShaderUpgrade.read(ShaderUpgrade.marker(a))).equals(Arrays.asList("e".repeat(32), aid)), "merged with what was there");
        check(CheckDue.copyMarker(cache, a) == 1 && CheckDue.ids(ShaderUpgrade.read(ShaderUpgrade.marker(a))).size() == 2, "once");
        check(CheckDue.copyMarker(cache, new File(root, "nolib")) == 0, "no library there: nothing");

        deleteTree(root);
        System.out.println("ok");
    }
}
