package dev.nxnative.android;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * PEANUTS (issue #120): the rewrite step's tool in a process of its own (RewriteBesidePlan: when, and the command), so the split and the shaders run beside it.
 * Its output goes to the step's log as in the packer (PackService's Tail reads it from there); its environment is the packer's, with the tools' variables
 * (PackEngine.ENV) on top, as the native engine passes them to a run. A stop is SIGINT, as the packer's interrupt: pack_android.py then ends its own rewrite
 * worker and that worker's forks (pn_rewrite_worker); a process still there after STOP_WAIT is killed.
 */
final class RewriteBeside {
    private static final String TAG = "nxpack";
    private static final long STOP_WAIT = 15_000;
    final Process process;
    final int pid;
    final File log, pidFile;
    final long began = SystemClock.elapsedRealtime();
    volatile long ended;   // when its process ended (elapsedRealtime), 0 while it runs: the log's duration is the rewrite's own, not the wait for the pack step

    private RewriteBeside(Process process, int pid, File log, File pidFile) {
        this.process = process;
        this.pid = pid;
        this.log = log;
        this.pidFile = pidFile;
    }

    /** Starts tool (relative to repo) with argv; the log is appended to. IOException: not started (no worker program, the shell refused, no pid) */
    static RewriteBeside start(File repo, String tool, String[] argv, File log, File pidFile) throws IOException {
        Map<String, String> tools;
        synchronized (PackEngine.ENV) { tools = new java.util.LinkedHashMap<>(PackEngine.ENV); }
        Files.deleteIfExists(doneFile(pidFile).toPath());   // an earlier run's
        List<String> cmd = RewriteBesidePlan.command(tools.get("DM_SPLIT_WORKER"), pidFile.getPath(), new File(repo, tool).getPath(), argv);
        if (cmd == null) throw new IOException("no worker program");
        Files.deleteIfExists(pidFile.toPath());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Map<String, String> env = pb.environment();
        env.clear();
        for (String kv : Os.environ()) { int eq = kv.indexOf('='); if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1)); }   // the packer's own (the native engine's too)
        env.putAll(tools);
        env.put("PYTHONUNBUFFERED", "1");   // its progress lines reach the log as they are printed
        Process p = pb.start();
        int pid = -1;
        for (int t = 0; t < 100 && pid < 0; t++) {   // the shell writes it before it becomes the worker
            pid = readPid(pidFile);
            if (pid < 0) {
                if (!p.isAlive()) break;
                SystemClock.sleep(20);
            }
        }
        if (pid < 0) { p.destroyForcibly(); throw new IOException("the rewrite process gave no pid (exit " + (p.isAlive() ? "-" : String.valueOf(p.exitValue())) + ")"); }
        // the split waits on this file before it starts the game's own decoder from the rewritten modules (dm/gfx/tools/rewrite_wait.py, PN_REWRITE_BESIDE_DONE)
        File done = doneFile(pidFile);
        RewriteBeside b = new RewriteBeside(p, pid, log, pidFile);
        Thread w = new Thread(() -> {
            int code;
            try { code = p.waitFor(); } catch (InterruptedException e) { code = 130; }
            b.ended = SystemClock.elapsedRealtime();
            try { Files.write(done.toPath(), (code + "\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { Log.w(TAG, "rewrite beside: " + done + ": " + e.getMessage()); }
        }, "nxpack-rewrite-beside");
        w.setDaemon(true);
        w.start();
        return b;
    }

    /** Waits for the end: its exit code, or -1 when stop() said to stop first (the process is still there then) */
    int waitFor(BooleanSupplier stop) throws InterruptedException {
        while (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) if (stop.getAsBoolean()) return -1;
        return process.exitValue();
    }

    /** Ends the process (SIGINT, then a kill after STOP_WAIT) and forgets its pid */
    void stop() {
        if (process.isAlive()) {
            signal(pid, OsConstants.SIGINT);
            try {
                if (!process.waitFor(STOP_WAIT, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try { Files.deleteIfExists(pidFile.toPath()); } catch (IOException ignored) { }
    }

    /** A rewrite process an earlier packer process left (Android ended the packer, not its child): ended before a job runs again */
    static void stopLeftover(File pidFile) {
        int pid = readPid(pidFile);
        if (pid > 0) {
            String cmdline = "";
            try { cmdline = new String(Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()), StandardCharsets.UTF_8); } catch (IOException ignored) { }
            if (cmdline.contains("nxpack_worker")) {
                Log.i(TAG, "ending a rewrite process an earlier packer left: pid " + pid);
                signal(pid, OsConstants.SIGINT);
                for (int t = 0; t < 75 && new File("/proc/" + pid).exists(); t++) SystemClock.sleep(200);
                if (new File("/proc/" + pid).exists()) signal(pid, OsConstants.SIGKILL);
            }
        }
        try { Files.deleteIfExists(pidFile.toPath()); } catch (IOException ignored) { }
    }

    /** The file the rewrite's exit code is written to when its process ends, beside the pid file */
    static File doneFile(File pidFile) { return new File(pidFile.getParentFile(), "rewrite-beside.done"); }

    private static int readPid(File f) {
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? -1 : Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static void signal(int pid, int sig) {
        try { Os.kill(pid, sig); } catch (ErrnoException e) { Log.w(TAG, "signal " + sig + " to " + pid + ": " + e.getMessage()); }
    }
}
