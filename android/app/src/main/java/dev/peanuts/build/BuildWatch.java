package dev.peanuts.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The build's watchdog (issue #348: a game that fails, crashes or hangs never holds the queue's next game). No Android class here: BuildWatchJvmTest drives
 * it. Prepare (a build and a build with mods, and the other game's job a build waits for) and the service's import ask it; what it decides:
 * <ul>
 * <li><b>a hang</b>: the packer runs the job (its worker alive), job.json has not changed for {@link #HANG_MS}, and the packer's processes used no CPU time
 *     in that time ({@link Watch}). A tool says how far it got every few seconds (progress and stage lines, PackService's Tail), but some steps say nothing
 *     for minutes while they work (the rewriter's load, the library's hash check): they use CPU time, so they are no hang. No change at all for
 *     {@link #BUSY_HANG_MS} is a hang even with CPU time (a tool that spins). A heat or battery pause is a state of its own, never a hang. The clock is
 *     uptime: it stops in deep sleep, so a phone that slept after the wake locks ended is no hang when it wakes;</li>
 * <li><b>a packer that ended</b>: its process went (a native crash, Android ending it for memory) and the job has not changed since for
 *     {@link #PACKER_BACK_MS}. Android starts a crashed service again within seconds, but not a second time when it crashed twice within a minute; a service
 *     the bind makes again runs no job. A packer that is not running the job and has not changed it for {@link #STALL_MS} has stalled;</li>
 * <li>each of these keeps the job as paused ({@link #end}), so a packer Android starts later runs nothing behind the next game's back;</li>
 * <li><b>an import that hangs</b> ({@link ImportWatch}): no progress for the hang time and it is asked to stop; not back after {@link #GIVE_UP_MS} and it is
 *     given up;</li>
 * <li><b>a run the app's process did not live through</b>: what ended it, from Android's record (ApplicationExitInfo), for the build log and the card. A native
 *     crash in the import's thread is the importer's, on those files ({@link #crashThread}): that game stops at once, so the queue goes on after the first
 *     crash (two within a minute and Android starts the app's service no more until the app is opened).</li>
 * </ul>
 */
final class BuildWatch {
    /** No change of a running job (or of an import) and no CPU time used for this long: it hangs */
    static final long HANG_MS = 30 * 60 * 1000L;
    /** No change of a running job for this long is a hang even while the packer uses CPU time (a tool that spins): no step is quiet for anywhere near it */
    static final long BUSY_HANG_MS = 3 * 60 * 60 * 1000L;
    /** The packer's process ended and the job has not changed for this long: it is not coming back for this run */
    static final long PACKER_BACK_MS = 5 * 60 * 1000L;
    /** A change seen this soon after the packer's process went is still its own: its last message and its last save are read in the poll after it went */
    static final long GONE_GRACE_MS = 15_000;
    /** The packer is not running the job and it has not changed for this long: stalled */
    static final long STALL_MS = 10 * 60 * 1000L;
    /** An import asked to stop for a hang that has not returned this long after: given up */
    static final long GIVE_UP_MS = 2 * 60 * 1000L;
    /** Below this share of one core the packer's processes are idle (its service's messages every few seconds use a little) */
    static final double IDLE_SHARE = 0.01;
    /** What {@link Watch#verdict} finds */
    static final int NONE = 0, GONE = 1, HANG = 2, STALL = 3;
    /** ApplicationExitInfo.REASON_* (the values Android documents), mirrored so this class needs no Android */
    static final int SIGNALED = 2, LOW_MEMORY = 3, CRASH = 4, CRASH_NATIVE = 5, ANR = 6;
    /** The test file of a build with the fake engine (-PnxPackEngine=fake): the hang time in seconds, so a test on a phone need not wait half an hour */
    static final String TEST_FILE = "test-hang-seconds";
    /** The name of the thread the import's native calls run on (BuildService.stepImport). Linux gives a thread's name to the threads it starts, so the
     *  importer's own threads have it too (the kernel keeps 15 characters) */
    static final String IMPORT_THREAD = "peanuts-import";

    private BuildWatch() {}

    /** The hang time: HANG_MS, or with the fake engine the test file's seconds (from 10 s; the file's text, null when there is none) */
    static long hangMs(boolean fakeEngine, String testFile) {
        if (!fakeEngine || testFile == null) return HANG_MS;
        try {
            long s = Long.parseLong(testFile.trim());
            return s >= 10 ? s * 1000 : HANG_MS;
        } catch (NumberFormatException e) {
            return HANG_MS;
        }
    }

    /** The busy hang time for a hang time: BUSY_HANG_MS, or six times a test time */
    static long busyMs(long hangMs) { return hangMs >= HANG_MS ? BUSY_HANG_MS : 6 * hangMs; }

    /**
     * One job's watch, on the build's thread. Every time is uptime in ms (SystemClock.uptimeMillis). The build calls {@link #changed} at each change of the
     * job (and at a heat or battery pause), {@link #cpu} when {@link #cpuDue}, and {@link #verdict} once a second
     */
    static final class Watch {
        final long hangMs, busyMs, backMs, stallMs;
        private long changedAt, cpuAt, cpuReadAt, cpuTicks = -1;

        Watch(long now, long hangMs) { this(now, hangMs, busyMs(hangMs), PACKER_BACK_MS, STALL_MS); }

        Watch(long now, long hangMs, long busyMs, long backMs, long stallMs) {
            this.hangMs = hangMs;
            this.busyMs = busyMs;
            this.backMs = backMs;
            this.stallMs = stallMs;
            changedAt = cpuAt = cpuReadAt = now;
        }

        /** The job changed (job.json or the packer's message), or a heat or battery pause holds it (no hang) */
        void changed(long now) {
            changedAt = Math.max(changedAt, now);
            cpuAt = Math.max(cpuAt, now);
        }

        /**
         * A heat or battery pause the build waits out, at each look: alive, the packer says its worker holds the job (in the pause, held or waiting). Then it is
         * no hang and no stall (a change). With no packer holding it (its process went, or the one Android bound runs nothing) it is no change: such a pause
         * never goes on by itself, so a packer that went is found as GONE and one that runs nothing as STALL, as for a running job (found in review: the heat
         * hold of #803 with its process gone said "too hot, goes on when cooled" for good)
         */
        void paused(long now, boolean alive) { if (alive) changed(now); }

        /** Whether a CPU reading is due: every 15 s, or every quarter of a shorter hang time */
        boolean cpuDue(long now) { return now - cpuReadAt >= Math.min(15_000, hangMs / 4); }

        /**
         * The packer's processes' CPU time so far ({@link #cpuTicks}, at ticksPerSecond; -1 when none can be read, and then the rule is the job's changes
         * alone). More than IDLE_SHARE of one core since the last reading is work. Fewer ticks than before is another process (Android started it again):
         * counted from here
         */
        void cpu(long now, long ticks, long ticksPerSecond) {
            long dt = now - cpuReadAt;
            cpuReadAt = now;
            if (ticks < 0 || ticksPerSecond <= 0) return;
            if (cpuTicks >= 0 && ticks > cpuTicks && dt > 0 && (ticks - cpuTicks) * 1000.0 / ticksPerSecond > IDLE_SHARE * dt) cpuAt = now;
            cpuTicks = ticks;
        }

        /**
         * What the watch finds now: running, the packer says its worker runs the job; goneAt, when its process went (0: it did not). The time of a process
         * that went is kept when Android binds a new one: a new one runs no job, and a job that goes on changes. A change within GONE_GRACE_MS of it is the
         * old process's last word, read a poll later (found on a Thor: two crashes within a minute fell to the stall rule after 10 minutes)
         */
        int verdict(long now, boolean running, long goneAt) {
            if (goneAt > 0 && !running && changedAt <= goneAt + GONE_GRACE_MS && now - goneAt > backMs) return GONE;
            if (running && now - changedAt > hangMs && (now - cpuAt > hangMs || now - changedAt > busyMs)) return HANG;
            if (!running && now - changedAt > stallMs) return STALL;
            return NONE;
        }

        /** How long the job has not changed */
        long quietMs(long now) { return now - changedAt; }

        /** Whether the packer used CPU time within the hang time (a hang found by the busy hang time) */
        boolean busy(long now) { return now - cpuAt <= hangMs; }
    }

    /** A packer the watchdog stops, as {@link #end} needs it */
    interface Packer {
        /** job.json kept as paused, with the watchdog's reason */
        void pause();
        /** its processes ended */
        void kill();
        /** whether none of its processes is left */
        boolean gone();
    }

    /**
     * Stops a packer and keeps its job as paused: paused first (a packer Android starts again in between finds nothing to run), then its processes ended
     * (kill false: there is none to end), then, once they are gone or waitMs have passed, paused again, since the packer may have saved "running" between
     * the first save and its end
     */
    static void end(Packer p, boolean kill, LongSupplier clock, long waitMs, Runnable nap) {
        p.pause();
        if (!kill) return;
        p.kill();
        long by = clock.getAsLong() + waitMs;
        while (!p.gone() && clock.getAsLong() < by) nap.run();
        p.pause();
    }

    /**
     * The packer's processes, in proc (/proc): the ones named name (an app's process, and the processes it forked, which keep its command line) and every
     * process below them, found by its parent (stat's field 4). The split's and the shader library's workers run nxpack_worker through /system/bin/linker64,
     * so their command line is the linker's and only their parent ties them to the packer. The named ones first, then each level below them
     */
    static List<Integer> pids(File proc, String name) {
        List<Integer> out = new ArrayList<>();
        String[] kids = proc.list();
        if (kids == null) return out;
        Map<Integer, List<Integer>> below = new HashMap<>();
        for (String k : kids) {
            if (k.isEmpty() || k.charAt(0) < '0' || k.charAt(0) > '9') continue;
            int pid;
            try { pid = Integer.parseInt(k); } catch (NumberFormatException e) { continue; }
            if (name.equals(cmdline(new File(proc, k + "/cmdline")))) out.add(pid);
            Stat s = Stat.read(new File(proc, k + "/stat"));
            if (s != null) below.computeIfAbsent(s.parent, p -> new ArrayList<>()).add(pid);
        }
        Set<Integer> seen = new HashSet<>(out);
        for (int i = 0; i < out.size(); i++) {
            List<Integer> b = below.get(out.get(i));
            if (b != null) for (int pid : b) if (seen.add(pid)) out.add(pid);
        }
        return out;
    }

    /**
     * The packer's processes that run now ({@link #pids}), each with its start ({@link #started}): the ones a stop ends, kept before the first is ended,
     * since a worker whose parent ended has another parent then
     */
    static Map<Integer, Long> running(File proc, String name) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (int pid : pids(proc, name)) {
            long s = started(proc, pid);
            if (s >= 0) out.put(pid, s);
        }
        return out;
    }

    /** Whether any of these processes ({@link #running}) still runs: the same pid with the same start, since Linux gives a free pid to a new process */
    static boolean anyLeft(File proc, Map<Integer, Long> procs) {
        for (Map.Entry<Integer, Long> e : procs.entrySet()) if (started(proc, e.getKey()) == e.getValue()) return true;
        return false;
    }

    /** A process's start (stat's field 22, clock ticks since boot), -1 when it is not there or has ended (a zombie: its parent was not told yet) */
    static long started(File proc, int pid) {
        Stat s = Stat.read(new File(proc, pid + "/stat"));
        return s == null || s.state == 'Z' || s.state == 'X' ? -1 : s.start;
    }

    private static String cmdline(File f) {
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * The CPU time the packer's processes ({@link #pids}) used so far, in clock ticks: each one's user and system time and its waited-for children's (a
     * worker's time moves to its parent when it ends, so it is counted once: while it runs, then in its parent). -1 when none can be read
     */
    static long cpuTicks(File proc, String name) {
        long sum = 0;
        boolean any = false;
        for (int pid : pids(proc, name)) {
            long t = statTicks(new File(proc, pid + "/stat"));
            if (t >= 0) { sum += t; any = true; }
        }
        return any ? sum : -1;
    }

    /** proc/&lt;pid&gt;/stat's utime, stime, cutime and cstime (fields 14 to 17), -1 when it cannot be read */
    static long statTicks(File stat) {
        Stat s = Stat.read(stat);
        return s == null ? -1 : s.ticks;
    }

    /** What the watchdog reads of proc/&lt;pid&gt;/stat. The command in parentheses may hold spaces, so the count starts after its last ')': the state is
     *  field 3 */
    private static final class Stat {
        final char state;
        final int parent;
        final long ticks, start;

        private Stat(char state, int parent, long ticks, long start) {
            this.state = state;
            this.parent = parent;
            this.ticks = ticks;
            this.start = start;
        }

        /** null when it cannot be read */
        static Stat read(File stat) {
            try {
                String s = new String(Files.readAllBytes(stat.toPath()), StandardCharsets.UTF_8);
                int close = s.lastIndexOf(')');
                if (close < 0) return null;
                String[] f = s.substring(close + 1).trim().split("\\s+");
                if (f.length < 20 || f[0].isEmpty()) return null;
                return new Stat(f[0].charAt(0), Integer.parseInt(f[1]),
                        Long.parseLong(f[11]) + Long.parseLong(f[12]) + Long.parseLong(f[13]) + Long.parseLong(f[14]), Long.parseLong(f[19]));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * The import's watch (BuildService.stepImport), on a thread of its own. No progress ({@link #heard}) for hangMs: the import is asked to stop (cancel) and
     * counts as hung. Not returned ({@link #done}) and no progress for giveUpMs after that, since a native call that never looks at the stop or a syscall
     * that never returns keeps it: it is given up (giveUp, which ends the app's process; Android starts the queue again with the next game)
     */
    static final class ImportWatch implements Runnable {
        private final LongSupplier clock;
        private final long hangMs, giveUpMs;
        private final Runnable cancel, giveUp;
        private final AtomicLong heard;
        private final AtomicBoolean hung = new AtomicBoolean();
        private boolean done, givenUp;   // guarded by this

        ImportWatch(LongSupplier clock, long hangMs, long giveUpMs, Runnable cancel, Runnable giveUp) {
            this.clock = clock;
            this.hangMs = hangMs;
            this.giveUpMs = giveUpMs;
            this.cancel = cancel;
            this.giveUp = giveUp;
            heard = new AtomicLong(clock.getAsLong());
        }

        /** The import got further (its progress, or a step done) */
        void heard() { heard.set(clock.getAsLong()); }

        /** Whether it was asked to stop for a hang */
        boolean hung() { return hung.get(); }

        /** The import returned: the watch ends. False when it was given up already (the app's process ends) */
        synchronized boolean done() {
            done = true;
            notifyAll();
            return !givenUp;
        }

        @Override public void run() {
            long hungAt = 0, nap = Math.max(10, Math.min(15_000, Math.min(hangMs, giveUpMs) / 4));
            while (true) {
                long now = clock.getAsLong();
                if (hungAt == 0 && now - heard.get() > hangMs) {
                    hung.set(true);
                    hungAt = now;
                    cancel.run();
                } else if (hungAt > 0 && now - Math.max(hungAt, heard.get()) > giveUpMs) {   // an import that still gets further after the stop is not given up yet
                    synchronized (this) {
                        if (done) return;
                        givenUp = true;
                    }
                    giveUp.run();
                    return;
                }
                synchronized (this) {
                    if (done) return;
                    try { wait(nap); } catch (InterruptedException e) { return; }
                    if (done) return;
                }
            }
        }
    }

    /** A process's end in the player's words ("it crashed"), "" when Android's record says nothing a player can use */
    static String endWords(int reason) {
        switch (reason) {
            case CRASH:
            case CRASH_NATIVE: return "it crashed";
            case LOW_MEMORY: return "the phone ran out of memory";
            case ANR: return "it stopped responding";
            case SIGNALED: return "it was ended";
            default: return "";
        }
    }

    /** Whether a tool's or the importer's error says the storage is full (strerror's words for ENOSPC, or the packer's own sentence for them, PrepareText) */
    static boolean full(String... texts) {
        for (String t : texts) if (t != null && (t.contains("No space left") || t.contains("ENOSPC") || t.contains("The storage is full"))) return true;
        return false;
    }

    /**
     * Whether a game whose run ended with the app's process stops at once instead of running again: a native crash while its files were imported, in the
     * import's thread (thread: the crashed thread's name, {@link #crashThread}; null when the record has none, and then the step decides). A crash in
     * another thread is not the importer's: the game runs again, as after any other end
     */
    static boolean stopsAtOnce(int reason, String step, String thread) {
        return reason == CRASH_NATIVE && "import".equals(step) && (thread == null || thread.startsWith(IMPORT_THREAD));
    }

    /**
     * The name of the thread that crashed, from a native crash's tombstone (ApplicationExitInfo.getTraceInputStream for REASON_CRASH_NATIVE: a protobuf of
     * debuggerd's tombstone.proto): its tid (field 6) and the threads map (field 16: entries of key 1 and value 2, a Thread with id 1 and name 2). null when
     * it cannot be read
     */
    static String crashThread(InputStream in, int max) {
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = s.read(buf)) > 0; ) {
                if (b.size() + n > max) return null;
                b.write(buf, 0, n);
            }
            return crashThread(b.toByteArray());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String crashThread(byte[] tombstone) {
        try {
            Proto p = new Proto(tombstone, 0, tombstone.length);
            long tid = -1;
            Map<Long, String> names = new HashMap<>();
            while (p.more()) {
                long key = p.varint();
                int field = (int) (key >>> 3), type = (int) (key & 7);
                if (field == 6 && type == 0) tid = p.varint();
                else if (field == 16 && type == 2) {
                    Proto e = p.sub();
                    long id = -1;
                    String name = null;
                    while (e.more()) {
                        long k = e.varint();
                        int f = (int) (k >>> 3), t = (int) (k & 7);
                        if (f == 1 && t == 0) id = e.varint();
                        else if (f == 2 && t == 2) {
                            Proto th = e.sub();
                            while (th.more()) {
                                long tk = th.varint();
                                int tf = (int) (tk >>> 3), tt = (int) (tk & 7);
                                if (tf == 1 && tt == 0) { long v = th.varint(); if (id < 0) id = v; }
                                else if (tf == 2 && tt == 2) name = th.string();
                                else th.skip(tt);
                            }
                        } else e.skip(t);
                    }
                    if (id >= 0 && name != null) names.put(id, name);
                } else p.skip(type);
            }
            return tid < 0 ? null : names.get(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The protobuf wire format, as far as crashThread reads it */
    private static final class Proto {
        private final byte[] b;
        private final int end;
        private int i;

        Proto(byte[] b, int from, int to) { this.b = b; this.i = from; this.end = to; }

        boolean more() { return i < end; }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                need(1);
                int x = b[i++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if (x < 0x80) return v;
            }
            throw new IllegalArgumentException("a varint longer than 10 bytes");
        }

        private int len() {
            long n = varint();
            if (n < 0 || n > end - i) throw new IllegalArgumentException("a length past the end");
            return (int) n;
        }

        Proto sub() {
            int n = len();
            Proto p = new Proto(b, i, i + n);
            i += n;
            return p;
        }

        String string() {
            int n = len();
            String s = new String(b, i, n, StandardCharsets.UTF_8);
            i += n;
            return s;
        }

        void skip(int type) {
            switch (type) {
                case 0: varint(); break;
                case 1: need(8); i += 8; break;
                case 2: { int n = len(); i += n; break; }   // not i += len(): Java reads i before len() moves it past the length
                case 5: need(4); i += 4; break;
                default: throw new IllegalArgumentException("wire type " + type);
            }
        }

        private void need(int n) { if (end - i < n) throw new IllegalArgumentException("cut short"); }
    }
}
