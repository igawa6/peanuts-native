package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PEANUTS (issue #120): whether the rewrite step runs in a process of its own beside the split and the shaders, and the command it runs there. Pure Java (the
 * JVM test RewriteBesidePlanJvmTest); RewriteBeside starts and stops the process, PackService decides and waits for it.
 *
 * The shaders step needs the split only, never the rewritten code, and the pack step is the first that needs both. Run one after the other, a build waits for the
 * rewrite (68 s of Super Mario Bros. Wonder's 223 s, 5 min 46 s of FINAL FANTASY RESONANCE's 45 min on an AYN Thor) before the split begins. Beside them, the build
 * takes about as long as the split and the shaders alone.
 *
 * Only where the memory takes both: the rewrite peaks at about 2.7 GB on a large module beside the shader workers' 100-250 MB each (BuildMemory), so only a
 * phone the memory plan gives its full worker count (14 GB and more), never after Android already ended this job's process for memory, never for the full check
 * of kept shaders, never with the simulated engine. PN_REWRITE_BESIDE=0 (ondevice/env.txt) keeps the steps one after the other.
 */
final class RewriteBesidePlan {
    private RewriteBesidePlan() {}

    /** The workers BuildMemory gives a phone of 14 GB and more on the Thor's cores: fewer means less memory, where one step at a time stays */
    static final int MIN_WORKERS = 4;

    /** How the worker runs the tool: sys.argv without the -c, the tool's folder first on the path (as pack_android.py REWRITE_BOOT) */
    static final String BOOT = "import runpy, sys\n"
            + "sys.argv = sys.argv[1:]; sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])\n"
            + "runpy.run_path(sys.argv[0], run_name='__main__')\n";

    /**
     * true when the rewrite may run beside: checking (the full check of kept shaders), simulated (FakeEngine), pyLib (libpython; null: no worker program),
     * workers (BuildMemory's split / shader workers for this phone now), memLevel (Job.memLevel: earlier kills), the split's and the shaders' states,
     * the switch PN_REWRITE_BESIDE (null: on)
     */
    static boolean allowed(boolean checking, boolean simulated, String pyLib, int workers, int memLevel, String splitState, String shadersState, String sw) {
        if (checking || simulated || pyLib == null) return false;
        if (sw != null && sw.trim().equals("0")) return false;
        if (workers < MIN_WORKERS || memLevel > 0) return false;
        return !"done".equals(splitState) || !"done".equals(shadersState);   // something to run beside
    }

    /**
     * The command: a shell that writes its pid to pidFile and becomes the worker (the same pid, so a stop reaches the tool), the worker program
     * (DM_SPLIT_WORKER: "/system/bin/linker64 …/nxpack_worker") running BOOT with the tool and its argv; null without a worker program
     */
    static List<String> command(String worker, String pidFile, String toolPath, String[] argv) {
        if (worker == null || worker.trim().isEmpty()) return null;
        List<String> c = new ArrayList<>(Arrays.asList("/system/bin/sh", "-c", "echo $$ > \"$0\"; exec \"$@\"", pidFile));
        c.addAll(Arrays.asList(worker.trim().split("\\s+")));
        c.add("-c");
        c.add(BOOT);
        c.add(toolPath);
        c.addAll(Arrays.asList(argv));
        return c;
    }
}
