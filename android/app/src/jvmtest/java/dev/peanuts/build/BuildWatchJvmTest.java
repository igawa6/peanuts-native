// PEANUTS: the build's watchdog (BuildWatch, issue #348) on the JVM; peanuts/tests/test_build_queue.py compiles and runs it
package dev.peanuts.build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The watchdog without a phone (prints "ok" or throws; args[0]: a folder to write in):
 * <ul>
 * <li>a job's watch over time: a hang only while the packer runs the job, only after the hang time with no change and no CPU time, or after the busy hang
 *     time; a heat or battery pause and CPU time are no hang; a packer that does not run the job stalls; one that went is gone only when the job has not
 *     changed since, also when Android binds a new one;</li>
 * <li>CPU time: a little (the service's messages) is idle, a new process starts the count over, none readable leaves the job's changes alone;</li>
 * <li>a stop: paused, ended, then paused again, so a save of the packer between the two is undone; without kill only paused;</li>
 * <li>the packer's processes and their CPU time from a /proc of files, its forked processes counted, others not;</li>
 * <li>its process tree (PEANUTS: #348): the workers below it at any depth counted and ended, the app's main process's not, and a worker left after its parent;</li>
 * <li>the import's watch: progress keeps it quiet, a hang asks it to stop, a stop that does not return is given up, a return in time is not;</li>
 * <li>a tombstone's crashed thread, and which ends stop a game at once (a native crash in the import's thread, or one the record does not name);</li>
 * <li>a full disk in the importer's and the packer's words, and a process's end in the player's words.</li>
 * </ul>
 */
public final class BuildWatchJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void same(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want <" + want + ">, got <" + got + ">");
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"), "buildwatch-test");
        hangTime();
        watch();
        cpu();
        end();
        proc(new File(dir, "proc"));
        tree(new File(dir, "tree"));   // PEANUTS: #348
        importWatch();
        tombstone();
        full();
        ends();
        System.out.println("ok");
    }

    private static void hangTime() {
        long h = BuildWatch.HANG_MS;
        same(30 * 60 * 1000L, h, "half an hour");
        same(3 * 60 * 60 * 1000L, BuildWatch.busyMs(h), "three hours with CPU time");
        same(6 * 60_000L, BuildWatch.busyMs(60_000), "six test times");
        // the test time: only a build with the fake engine reads the file, and not below 10 s
        same(h, BuildWatch.hangMs(false, "60"), "a real engine's build ignores the file");
        same(60_000L, BuildWatch.hangMs(true, "60\n"), "the fake engine's test time");
        same(h, BuildWatch.hangMs(true, "5"), "not below 10 s");
        same(h, BuildWatch.hangMs(true, "soon"), "a file that does not read");
        same(h, BuildWatch.hangMs(true, null), "no file");
    }

    private static final long HANG = 1_000, BUSY = 6_000, BACK = 500, STALL = 2_000;

    private static void watch() {
        // a hang: running, the hang time without a change and without CPU time
        BuildWatch.Watch w = new BuildWatch.Watch(10_000, HANG, BUSY, BACK, STALL);
        same(BuildWatch.NONE, w.verdict(11_000, true, 0), "not at the hang time itself");
        same(BuildWatch.HANG, w.verdict(11_001, true, 0), "just after it");
        same(1_001L, w.quietMs(11_001), "how long it was quiet");
        check(!w.busy(11_001), "no CPU time");
        // a change starts it over; a heat or battery pause counts as one
        w.changed(10_900);
        same(BuildWatch.NONE, w.verdict(11_001, true, 0), "a change since");
        same(BuildWatch.HANG, w.verdict(11_901, true, 0), "the hang time after the change");
        w.changed(5_000);
        same(BuildWatch.HANG, w.verdict(11_901, true, 0), "an older change does not take it back");

        // CPU time is work: a step that prints nothing for a while is no hang, until the busy hang time
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(0, 1_000, 100);
        for (long t = 250; t <= 5_750; t += 250) {
            w.cpu(t, 1_000 + t / 10, 100);   // a full core: 100 ticks a second
            same(BuildWatch.NONE, w.verdict(t, true, 0), "a quiet step with CPU time, at " + t);
        }
        w.cpu(6_000, 1_600, 100);
        same(BuildWatch.NONE, w.verdict(6_000, true, 0), "not at the busy hang time itself");
        same(BuildWatch.HANG, w.verdict(6_001, true, 0), "a tool that spins: no change for the busy hang time");
        check(w.busy(6_001), "found by the busy hang time");

        // not running: the stall rule, never a hang
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        same(BuildWatch.NONE, w.verdict(1_500, false, 0), "a packer that does not run the job is no hang");
        same(BuildWatch.NONE, w.verdict(2_000, false, 0), "not at the stall time itself");
        same(BuildWatch.STALL, w.verdict(2_001, false, 0), "stalled");

        // a packer that went: gone once the job has not changed for the time since. Its last message and save, read in the poll after it went, are still
        // the old process's (found on a Thor: two crashes within a minute fell to the stall rule after 10 minutes)
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, 1_000_000);
        w.changed(90_000);
        same(BuildWatch.NONE, w.verdict(100_500, false, 100_000), "not at the time itself");
        same(BuildWatch.GONE, w.verdict(100_501, false, 100_000), "gone, nothing changed since");
        w.changed(101_000);
        same(BuildWatch.GONE, w.verdict(101_001, false, 100_000), "a change a moment after it went is the old process's last word");
        same(BuildWatch.NONE, w.verdict(101_001, true, 100_000), "not while a packer runs the job (one Android started again: the hang rule's)");
        w.changed(100_000 + BuildWatch.GONE_GRACE_MS + 1);
        same(BuildWatch.NONE, w.verdict(200_000, false, 100_000), "the job changed after it went: Android started it again and it went on");
        check(w.verdict(1_000_000, true, 0) != BuildWatch.GONE, "never gone without a time it went");

        // PEANUTS: a heat or battery pause (paused): no hang or stall while the packer holds the job; once its process went, or nothing runs it, the pause is no change,
        // so it ends as a packer that ended or stalled (found in review: the heat hold with its process gone waited for good)
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        for (long t = 1_000; t <= 20_000; t += 1_000) { w.paused(t, true); same(BuildWatch.NONE, w.verdict(t, true, 0), "a held pause is no hang, at " + t); }
        for (long t = 20_600; t <= 21_000; t += 100) { w.paused(t, false); same(BuildWatch.NONE, w.verdict(t, false, 20_500), "not before the time it may take to come back, at " + t); }
        w.paused(21_001, false);
        same(BuildWatch.GONE, w.verdict(21_001, false, 20_500), "a pause whose packer went and did not come back: gone");
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.paused(1_000, true);
        w.paused(2_000, false);
        same(BuildWatch.NONE, w.verdict(3_000, false, 0), "a pause nothing runs: not before the stall time");
        w.paused(3_001, false);
        same(BuildWatch.STALL, w.verdict(3_001, false, 0), "a pause nothing runs: stalled");
        w.paused(3_002, true);
        same(BuildWatch.NONE, w.verdict(3_002, true, 0), "a packer that holds it again");
    }

    private static void cpu() {
        // a little CPU time (the service's messages every few seconds) is idle: below 1 % of a core
        BuildWatch.Watch w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        check(w.cpuDue(250), "a reading every quarter of a short hang time");
        check(!w.cpuDue(249), "not before");
        w.cpu(250, 5_000, 100);
        w.cpu(500, 5_000, 100);
        w.cpu(750, 5_000, 100);
        w.cpu(1_000, 5_000, 100);
        same(BuildWatch.HANG, w.verdict(1_001, true, 0), "no CPU time at all");
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(0, 5_000, 100);
        w.cpu(1_000, 5_000, 100);   // 0 ticks
        same(BuildWatch.HANG, w.verdict(1_001, true, 0), "idle");
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(0, 5_000, 100);
        w.cpu(1_000, 5_001, 100);   // 10 ms in 1 s: exactly 1 % is not more than it
        same(BuildWatch.HANG, w.verdict(1_001, true, 0), "1 % of a core is idle");
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(0, 5_000, 100);
        w.cpu(1_000, 5_002, 100);
        same(BuildWatch.NONE, w.verdict(1_001, true, 0), "2 % of a core is work");
        // fewer ticks: a new process, counted from there; none readable: the job's changes alone
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(0, 9_000, 100);
        w.cpu(500, 10, 100);
        w.cpu(1_000, 10, 100);
        same(BuildWatch.HANG, w.verdict(1_001, true, 0), "a new process that does nothing");
        w = new BuildWatch.Watch(0, HANG, BUSY, BACK, STALL);
        w.cpu(500, -1, 100);
        w.cpu(1_000, -1, 100);
        same(BuildWatch.HANG, w.verdict(1_001, true, 0), "no CPU time to read");
    }

    private static void end() {
        // the packer saves "running" once more between the first save and its end: the second save undoes it
        List<String> steps = new ArrayList<>();
        final String[] state = {"running"};
        AtomicInteger looks = new AtomicInteger();
        BuildWatch.Packer p = new BuildWatch.Packer() {
            @Override public void pause() { steps.add("pause"); if (state[0].equals("running")) state[0] = "paused"; }
            @Override public void kill() { steps.add("kill"); state[0] = "running"; }   // its last save, as the end came
            @Override public boolean gone() { return looks.incrementAndGet() > 2; }
        };
        AtomicLong clock = new AtomicLong();
        BuildWatch.end(p, true, clock::get, 5_000, () -> clock.addAndGet(50));
        same(Arrays.asList("pause", "kill", "pause"), steps, "paused, ended, paused again");
        same("paused", state[0], "kept as paused");
        same(3, looks.get(), "waited until its processes were gone");
        // processes that do not go: paused again after the wait
        steps.clear();
        state[0] = "running";
        BuildWatch.Packer stuck = new BuildWatch.Packer() {
            @Override public void pause() { steps.add("pause"); if (state[0].equals("running")) state[0] = "paused"; }
            @Override public void kill() { steps.add("kill"); state[0] = "running"; }
            @Override public boolean gone() { return false; }
        };
        clock.set(0);
        BuildWatch.end(stuck, true, clock::get, 5_000, () -> clock.addAndGet(50));
        same("paused", state[0], "paused after the wait too");
        check(clock.get() >= 5_000 && clock.get() < 5_100, "waited the wait, not longer");
        // no process to end (a packer that went, or stalled): paused only
        steps.clear();
        state[0] = "running";
        BuildWatch.end(p, false, clock::get, 5_000, () -> clock.addAndGet(50));
        same(Collections.singletonList("pause"), steps, "only paused");
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** A stat line: pid, the command in parentheses, then the fields from 3 on with utime, stime, cutime, cstime as fields 14 to 17 */
    private static String stat(int pid, String comm, long utime, long stime, long cutime, long cstime) {
        return stat(pid, comm, 'S', 40, utime, stime, cutime, cstime, 22);   // PEANUTS: #348, the parent and the start as before
    }

    /** A stat line with the state and the parent (fields 3 and 4) and the start (field 22) too. PEANUTS: #348, the packer's process tree */
    private static String stat(int pid, String comm, char state, int parent, long utime, long stime, long cutime, long cstime, long start) {
        StringBuilder b = new StringBuilder().append(pid).append(" (").append(comm).append(") ").append(state).append(' ').append(parent);
        for (int field = 5; field <= 13; field++) b.append(' ').append(field * 10);
        b.append(' ').append(utime).append(' ').append(stime).append(' ').append(cutime).append(' ').append(cstime);
        for (int field = 18; field <= 52; field++) b.append(' ').append(field == 22 ? start : field);
        return b.append('\n').toString();
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    private static void proc(File proc) throws IOException {
        delete(proc);   // a folder of an earlier run
        String pack = "dev.example.app:pack";
        write(new File(proc, "100/cmdline"), pack + "\0");
        write(new File(proc, "100/stat"), stat(100, "ple.app:pack", 400, 100, 30, 20));
        write(new File(proc, "101/cmdline"), pack + "\0\0");   // a forked worker keeps the command line
        write(new File(proc, "101/stat"), stat(101, "a) b (c", 7, 3, 0, 0));   // a command with spaces and parentheses
        write(new File(proc, "102/cmdline"), "dev.example.app\0");   // the app's main process
        write(new File(proc, "102/stat"), stat(102, "dev.example.app", 9_999, 9_999, 0, 0));
        write(new File(proc, "103/cmdline"), pack + "\0");   // one that ended while it was read
        write(new File(proc, "self/cmdline"), pack + "\0");
        write(new File(proc, "1x/cmdline"), pack + "\0");
        List<Integer> pids = BuildWatch.pids(proc, pack);
        Collections.sort(pids);
        same(Arrays.asList(100, 101, 103), pids, "the packer and its forked worker, not the app or other names");
        same(550L, BuildWatch.statTicks(new File(proc, "100/stat")), "utime + stime + cutime + cstime");
        same(10L, BuildWatch.statTicks(new File(proc, "101/stat")), "after the command's last parenthesis");
        same(-1L, BuildWatch.statTicks(new File(proc, "103/stat")), "a stat that is not there");
        same(560L, BuildWatch.cpuTicks(proc, pack), "the packer's processes together");
        same(-1L, BuildWatch.cpuTicks(proc, "dev.example.other:pack"), "none of that name");
        same(-1L, BuildWatch.cpuTicks(new File(proc, "missing"), pack), "no /proc");
        write(new File(proc, "104/cmdline"), pack + "\0");
        write(new File(proc, "104/stat"), "104 (cut");
        same(-1L, BuildWatch.statTicks(new File(proc, "104/stat")), "a stat cut short");
    }

    /** PEANUTS: #348, the packer's process tree: the workers the split and the shader library start through the linker, found by their parent */
    private static void tree(File proc) throws IOException {
        delete(proc);
        String pack = "dev.example.app:pack", worker = "/system/bin/linker64\0/data/app/python/bin/nxpack_worker\0";
        write(new File(proc, "200/cmdline"), pack + "\0");
        write(new File(proc, "200/stat"), stat(200, "ple.app:pack", 'S', 1, 80, 20, 0, 0, 5_000));
        write(new File(proc, "201/cmdline"), pack + "\0");   // a forked one: its name and its parent
        write(new File(proc, "201/stat"), stat(201, "ple.app:pack", 'S', 200, 6, 4, 0, 0, 5_100));
        write(new File(proc, "210/cmdline"), worker);   // a worker of the packer's own process
        write(new File(proc, "210/stat"), stat(210, "linker64", 'R', 200, 250, 50, 0, 0, 5_200));
        write(new File(proc, "211/cmdline"), worker);   // a worker of the forked one
        write(new File(proc, "211/stat"), stat(211, "linker64", 'R', 201, 40, 10, 0, 0, 5_300));
        write(new File(proc, "212/cmdline"), "nxpack_worker\0");   // one a worker started
        write(new File(proc, "212/stat"), stat(212, "nxpack_worker", 'S', 210, 4, 1, 0, 0, 5_400));
        write(new File(proc, "230/cmdline"), "");   // a worker that ended, its parent not told yet: its time is not in the parent's yet
        write(new File(proc, "230/stat"), stat(230, "linker64", 'Z', 200, 5, 2, 0, 0, 5_500));
        write(new File(proc, "220/cmdline"), "dev.example.app\0");   // the app's main process and a worker of its own: not the packer's
        write(new File(proc, "220/stat"), stat(220, "dev.example.app", 'S', 1, 9_000, 999, 0, 0, 100));
        write(new File(proc, "221/cmdline"), worker);
        write(new File(proc, "221/stat"), stat(221, "linker64", 'R', 220, 500, 500, 0, 0, 200));
        List<Integer> pids = BuildWatch.pids(proc, pack);
        same(new java.util.HashSet<>(Arrays.asList(200, 201)), new java.util.HashSet<>(pids.subList(0, 2)), "the named ones first");
        Collections.sort(pids);
        same(Arrays.asList(200, 201, 210, 211, 212, 230), pids, "and every process below them, at any depth");
        same(100L + 10 + 300 + 50 + 5 + 7, BuildWatch.cpuTicks(proc, pack), "the workers' CPU time counted, the main process's not");
        java.util.Map<Integer, Long> run = BuildWatch.running(proc, pack);
        List<Integer> ends = new ArrayList<>(run.keySet());
        same(new java.util.HashSet<>(Arrays.asList(200, 201)), new java.util.HashSet<>(ends.subList(0, 2)), "the named ones are ended first");
        Collections.sort(ends);
        same(Arrays.asList(200, 201, 210, 211, 212), ends, "a stop ends all but the one that ended");
        same(5_200L, run.get(210), "each with its start");
        check(BuildWatch.anyLeft(proc, run), "they run");
        // the packer's processes end: a worker whose parent ended has another parent, and is still found by what was kept
        for (int pid : new int[] {200, 201, 211, 212}) delete(new File(proc, String.valueOf(pid)));
        write(new File(proc, "210/stat"), stat(210, "linker64", 'R', 1, 250, 50, 0, 0, 5_200));
        same(-1L, BuildWatch.cpuTicks(proc, pack), "no packer: nothing of it to read");
        check(BuildWatch.anyLeft(proc, run), "the worker is left");
        write(new File(proc, "210/stat"), stat(210, "linker64", 'Z', 1, 250, 50, 0, 0, 5_200));
        check(!BuildWatch.anyLeft(proc, run), "it ended (a zombie is no process that runs)");
        write(new File(proc, "210/stat"), stat(210, "linker64", 'S', 1, 0, 0, 0, 0, 9_000));
        check(!BuildWatch.anyLeft(proc, run), "its pid given to a new process is not it");
        same(-1L, BuildWatch.started(proc, 999), "a pid that is not there");
    }

    /** Waits up to 5 s for a condition the watch's thread sets */
    private static boolean soon(java.util.function.BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 500; i++) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }

    private static void importWatch() throws InterruptedException {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger cancels = new AtomicInteger(), giveUps = new AtomicInteger();
        BuildWatch.ImportWatch w = new BuildWatch.ImportWatch(clock::get, 100, 200, cancels::incrementAndGet, giveUps::incrementAndGet);
        Thread t = new Thread(w, "watch");
        t.start();
        // progress keeps it quiet
        for (int i = 0; i < 10; i++) { clock.addAndGet(60); w.heard(); Thread.sleep(15); }
        check(!w.hung() && cancels.get() == 0, "an import that goes on");
        // no progress for the hang time: asked to stop, once
        clock.addAndGet(101);
        check(soon(w::hung), "hung");
        check(soon(() -> cancels.get() == 1), "asked to stop");
        Thread.sleep(50);
        same(1, cancels.get(), "once");
        same(0, giveUps.get(), "not given up yet");
        // still no return when the give-up time has passed: given up
        clock.addAndGet(201);
        check(soon(() -> giveUps.get() == 1), "given up");
        t.join(5_000);
        check(!t.isAlive(), "the watch ended");
        check(!w.done(), "the import's return after it was given up says so");

        // a stop that returns in time is not given up
        AtomicInteger giveUps2 = new AtomicInteger();
        BuildWatch.ImportWatch w2 = new BuildWatch.ImportWatch(clock::get, 100, 200, () -> {}, giveUps2::incrementAndGet);
        Thread t2 = new Thread(w2, "watch2");
        t2.start();
        clock.addAndGet(101);
        check(soon(w2::hung), "hung");
        check(w2.done(), "it returned before it was given up");
        clock.addAndGet(1_000);
        t2.join(5_000);
        check(!t2.isAlive(), "the watch ended with the import");
        same(0, giveUps2.get(), "not given up");

        // progress after the stop was asked (a native call that looks at the stop only between files): the give-up time counts from the last progress
        AtomicInteger giveUps3 = new AtomicInteger();
        BuildWatch.ImportWatch w3 = new BuildWatch.ImportWatch(clock::get, 100, 200, () -> {}, giveUps3::incrementAndGet);
        Thread t3 = new Thread(w3, "watch3");
        t3.start();
        clock.addAndGet(101);
        check(soon(w3::hung), "hung");
        clock.addAndGet(150);
        w3.heard();
        clock.addAndGet(150);   // 300 after the stop, 150 after the last progress
        Thread.sleep(100);
        same(0, giveUps3.get(), "not while it still gets further");
        clock.addAndGet(60);
        check(soon(() -> giveUps3.get() == 1), "given up once it stands still for the give-up time");
        t3.join(5_000);
        t.interrupt();
    }

    // ---- a tombstone in protobuf's wire format ----

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static void key(ByteArrayOutputStream o, int field, int type) { varint(o, ((long) field << 3) | type); }

    private static void num(ByteArrayOutputStream o, int field, long v) { key(o, field, 0); varint(o, v); }

    private static void bytes(ByteArrayOutputStream o, int field, byte[] b) { key(o, field, 2); varint(o, b.length); o.write(b, 0, b.length); }

    private static byte[] thread(long id, String name) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        num(t, 1, id);
        bytes(t, 2, name.getBytes(StandardCharsets.UTF_8));
        bytes(t, 3, new byte[] {8, 1, 16, 2});   // a register
        key(t, 6, 0); varint(t, -1L);   // tagged_addr_ctrl, a negative int64: ten bytes
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        num(e, 1, id);
        bytes(e, 2, t.toByteArray());
        return e.toByteArray();
    }

    private static byte[] tombstone(long tid, boolean withTid) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        num(o, 1, 3);   // arch
        bytes(o, 2, "vendor/device:16/build:user/release-keys".getBytes(StandardCharsets.UTF_8));
        num(o, 5, 4_321);   // pid
        if (withTid) num(o, 6, tid);
        key(o, 30, 1); o.write(new byte[8], 0, 8);   // a fixed64 of a later field
        key(o, 31, 5); o.write(new byte[4], 0, 4);   // a fixed32
        bytes(o, 16, thread(4_321, "dev.example.app"));
        bytes(o, 16, thread(4_400, "peanuts-import"));
        bytes(o, 16, thread(4_500, "RenderThread"));
        bytes(o, 17, new byte[] {10, 3, 'l', 'i', 'b'});   // a memory mapping
        return o.toByteArray();
    }

    private static void tombstone() {
        same("peanuts-import", BuildWatch.crashThread(tombstone(4_400, true)), "the import's thread crashed");
        same("RenderThread", BuildWatch.crashThread(tombstone(4_500, true)), "another thread crashed");
        same(null, BuildWatch.crashThread(tombstone(4_400, false)), "no tid");
        same(null, BuildWatch.crashThread(tombstone(9_999, true)), "a tid of no thread listed");
        byte[] t = tombstone(4_400, true);
        same(null, BuildWatch.crashThread(Arrays.copyOf(t, t.length - 3)), "cut short");
        same(null, BuildWatch.crashThread(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}), "not a protobuf");
        same(null, BuildWatch.crashThread(new byte[0]), "empty");
        same("peanuts-import", BuildWatch.crashThread(new ByteArrayInputStream(t), 1 << 20), "from a stream");
        same(null, BuildWatch.crashThread(new ByteArrayInputStream(t), 16), "longer than allowed");
        same(null, BuildWatch.crashThread(null, 1 << 20), "no trace");
        // which ends stop a game at once
        check(BuildWatch.stopsAtOnce(BuildWatch.CRASH_NATIVE, "import", "peanuts-import"), "the importer crashed on the files");
        check(BuildWatch.stopsAtOnce(BuildWatch.CRASH_NATIVE, "import", null), "a crash while importing that the record does not name: the step decides");
        check(!BuildWatch.stopsAtOnce(BuildWatch.CRASH_NATIVE, "import", "RenderThread"), "a crash in another thread is not the importer's");
        check(!BuildWatch.stopsAtOnce(BuildWatch.CRASH_NATIVE, "prepare", "peanuts-import"), "a native crash in another step runs again");
        check(!BuildWatch.stopsAtOnce(BuildWatch.LOW_MEMORY, "import", null), "an end for memory runs again");
        check(!BuildWatch.stopsAtOnce(BuildWatch.SIGNALED, "import", null), "kill -9 runs again");
        check(!BuildWatch.stopsAtOnce(0, "import", null), "no record runs again");
    }

    private static void full() {
        check(BuildWatch.full("write failed: No space left on device"), "the importer's strerror");
        check(BuildWatch.full(null, "OSError: [Errno 28] ENOSPC"), "a tool's errno, in the detail");
        check(BuildWatch.full("Making shaders for this phone stopped. The storage is full. Free some space, then tap Retry.", ""), "the packer's own sentence");
        check(!BuildWatch.full("write failed: Input/output error", null), "another error");
        check(!BuildWatch.full(), "nothing");
    }

    private static void ends() {
        same("it crashed", BuildWatch.endWords(BuildWatch.CRASH_NATIVE), "a native crash");
        same("it crashed", BuildWatch.endWords(BuildWatch.CRASH), "a Java crash");
        same("the phone ran out of memory", BuildWatch.endWords(BuildWatch.LOW_MEMORY), "low memory");
        same("it stopped responding", BuildWatch.endWords(BuildWatch.ANR), "not responding");
        same("it was ended", BuildWatch.endWords(BuildWatch.SIGNALED), "a signal (kill -9)");
        same("", BuildWatch.endWords(0), "no record");
        same("", BuildWatch.endWords(13), "other");
    }
}
