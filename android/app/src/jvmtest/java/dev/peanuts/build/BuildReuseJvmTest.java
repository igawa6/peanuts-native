// PEANUTS: BuildReuse (issue #120) on the JVM: what a build took from earlier builds, from its step logs; peanuts/tests/test_build_reuse.py compiles and runs it
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** BuildReuse (issue #120): what a build took from earlier builds, from its steps' logs, and the ready message's sentence. Run by peanuts/tests/test_build_reuse.py */
public final class BuildReuseJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    private static File put(File f, String text, long time) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        check(f.setLastModified(time), "set the time of " + f);
        return f;
    }

    public static void main(String[] args) throws IOException {
        File logs = new File(args[0], "ondevice/logs");
        long start = 1_800_000_000_000L, before = start - 3_600_000L, during = start + 60_000L;

        // The lines the reuse tools print on the phone (the 2026-11-19 runs on an AYN Thor), a run before them in the same log
        put(new File(logs, "shaders.log"), "== 2026-11-19 11:50:52 peanuts-native-0.1.5-dev, engine native: dm/peanuts/reuse/shader_reuse.py --target vulkan pkg lib\n"
                + "reuse check: 16 of 16 shaders translated to check are the older library's byte for byte; 384 shaders kept\n"
                + "== 2026-11-19 12:02:34 peanuts-native-0.1.5-dev, engine native: dm/peanuts/reuse/shader_reuse.py --target vulkan pkg lib\n"
                + "reuse: /data/data/x/no_backup/reuse/01006F8002326000/library/v-dfebef5d68a9/dmsh_vk.lib: 9564 shaders kept (their own part of the profile unchanged), 202 translated to check them\n"
                + "reuse check: 202 of 202 shaders translated to check are the older library's byte for byte; 9564 shaders kept\n"
                + "reuse: this library kept for the next build of this game (96 MB)\n", during);
        put(new File(logs, "rewrite.log"), "== 2026-11-19 12:01:00 rewrite\n  main: kept from the app's copy of its last rewrite (the pack had none; its build and its part of the profile are unchanged)\n"
                + "  subsdk0: kept from the app's copy of its last rewrite (the pack had none; its build and its part of the profile are unchanged)\n"
                + "  sdk: kept, its build and its part of the profile are unchanged (the key moved with other builds' rows)\n"
                + "  rtld: unchanged, kept\n  nnrtld: rewriting game.nup:base/exefs/nnrtld (in process)\n", during);
        put(new File(logs, "split.log"), "== 2026-11-19 12:01:40 split\nsplit reuse: 91 of 93 files kept (unchanged since a split before), 2 of them scanned to check (the same), 0 not keyed (archives, files without a hash)\n", during);
        BuildReuse.Kept k = BuildReuse.read(logs, start);
        check(k.shaders == 9564 && k.modules == 4 && k.files == 91, "the last run of each step: " + k);
        check(BuildReuse.text(k, 76).equals(" It took 1 min 16 s, and 9,564 shaders, 4 parts of the game's code and 91 game files were kept from before."), BuildReuse.text(k, 76));

        // A step that did not run in this build (Import again: no rewrite) left an earlier build's log: not counted
        put(new File(logs, "rewrite.log"), "== old\n  main: unchanged, kept\n", before);
        k = BuildReuse.read(logs, start);
        check(k.modules == 0 && k.shaders == 9564, "an earlier build's rewrite log: " + k);
        check(BuildReuse.text(k, 67).equals(" It took 1 min 7 s, and 9,564 shaders and 91 game files were kept from before."), BuildReuse.text(k, 67));

        // A check that failed: the kept shaders were translated again
        put(new File(logs, "shaders.log"), "== run\nreuse: lib: 9564 shaders kept (...), 202 translated to check them\n"
                + "[RED] reuse check: 3 of 202 shaders translated to check differ from the older library (a, b, c): the 9564 kept shaders are translated again\n", during);
        check(BuildReuse.read(logs, start).shaders == 0, "a failed check keeps none");

        // A first build: nothing kept, no sentence; the split's last line counts (a kept result that changed scans every file again)
        put(new File(logs, "shaders.log"), "== run\nreuse: none\n", during);
        put(new File(logs, "split.log"), "== run\nsplit reuse: 5 of 93 files kept\nsplit reuse: 0 of 93 files kept (unchanged since a split before)\n", during);
        k = BuildReuse.read(logs, start);
        check(!k.any() && BuildReuse.text(k, 393).isEmpty(), "nothing kept: " + k);
        check(BuildReuse.text(new BuildReuse.Kept(1, 1, 0), 9).equals(" It took 9 s, and 1 shader and 1 part of the game's code were kept from before."), "singular and seconds");

        // Logs that are not there, or a log without a run line
        check(!BuildReuse.read(new File(args[0], "nothing"), start).any(), "no logs");
        put(new File(logs, "split.log"), "split reuse: 7 of 9 files kept\n", during);
        check(BuildReuse.read(logs, start).files == 7, "a log without a run line is all this run's");
        System.out.println("ok");
    }
}
