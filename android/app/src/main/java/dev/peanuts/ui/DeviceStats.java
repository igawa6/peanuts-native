package dev.peanuts.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The GPU's load and clock and the CPU's clocks as the kernel reports them (the stats page, StatsView), read from sysfs under a root (/ on the phone, a
 * folder in a JVM test). Pure Java: files only.
 * <ul>
 * <li>GPU: Adreno's kgsl node (sys/class/kgsl/kgsl-3d0): gpu_busy_percentage ("47 %") and gpuclk / max_gpuclk in Hz. A GPU without that node has no tile
 *     ({@link #hasGpu}).</li>
 * <li>CPU: the cpufreq policies (sys/devices/system/cpu/cpufreq/policy*): the main cluster is the one with the most cores, as Overlay's throttle dot takes it;
 *     its scaling_cur_freq (the clock now), scaling_max_freq (the cap now) and cpuinfo_max_freq (its top clock), in kHz.</li>
 * <li>The game's own memory (its process): the CPU side from proc/&lt;pid&gt;/status, the GPU side from kgsl's per-process counters
 *     (sys/class/kgsl/kgsl/proc/&lt;pid&gt;).</li>
 * </ul>
 * Every value is -1 (or NaN) when its file does not read, so a phone that hides one shows a dash there and nothing breaks.
 */
public final class DeviceStats {
    private final File kgsl, kgslProc;
    private final File cpufreq;
    private final File proc;
    private File main;   // the main cluster's policy folder, found once
    private boolean found;

    public DeviceStats(File root) {
        kgsl = new File(root, "sys/class/kgsl/kgsl-3d0");
        kgslProc = new File(root, "sys/class/kgsl/kgsl/proc");
        cpufreq = new File(root, "sys/devices/system/cpu/cpufreq");
        proc = new File(root, "proc");
    }

    /** Whether there is a GPU load to show (the kgsl node reads) */
    public boolean hasGpu() { return gpuBusy() >= 0; }

    /** The GPU's load now, 0..100; -1 unread */
    public int gpuBusy() {
        String s = read(new File(kgsl, "gpu_busy_percentage"));
        if (s == null) return -1;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return -1;
        try { return Math.min(100, Integer.parseInt(s.substring(0, i))); } catch (NumberFormatException e) { return -1; }
    }

    /** The GPU's clock now and its top clock in MHz; -1 unread */
    public int gpuMhz() { return mhz(readLong(new File(kgsl, "gpuclk"))); }
    public int gpuMaxMhz() { return mhz(readLong(new File(kgsl, "max_gpuclk"))); }

    /** The main cluster's clock now, its cap now and its top clock, in kHz; -1 unread */
    public long cpuKHz() { return cluster("scaling_cur_freq"); }
    public long cpuCapKHz() { return cluster("scaling_max_freq"); }
    public long cpuMaxKHz() { return cluster("cpuinfo_max_freq"); }

    /** The main cluster's cap over its top clock (Overlay's mainCap): NaN unread */
    public float cpuCap() {
        long cap = cpuCapKHz(), max = cpuMaxKHz();
        return cap > 0 && max > 0 ? (float) cap / max : Float.NaN;
    }

    /**
     * The memory a process holds on the CPU side: RssAnon + RssShmem of proc/&lt;pid&gt;/status, in bytes; -1 unread. Not VmRSS: that also counts the files the
     * process has mapped (the game's pack and code, 1.7 GB of Kirby's 3.6 GB on the Thor), which the kernel can drop and read again
     */
    public long appCpuBytes(int pid) {
        String s = read(new File(proc, pid + "/status"));
        if (s == null) return -1;
        long anon = statusKb(s, "RssAnon:"), shm = statusKb(s, "RssShmem:");
        return anon < 0 ? -1 : (anon + Math.max(0, shm)) * 1024L;
    }

    /** The GPU memory a process holds (kgsl: gpumem_mapped + gpumem_unmapped, which RSS does not count), in bytes; -1 unread */
    public long appGpuBytes(int pid) {
        File d = new File(kgslProc, String.valueOf(pid));
        long m = readLong(new File(d, "gpumem_mapped")), u = readLong(new File(d, "gpumem_unmapped"));
        return m < 0 && u < 0 ? -1 : Math.max(0, m) + Math.max(0, u);
    }

    private static long statusKb(String status, String key) {
        int i = status.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length(), e = status.indexOf('\n', j);
        String v = (e < 0 ? status.substring(j) : status.substring(j, e)).trim();
        if (v.endsWith("kB")) v = v.substring(0, v.length() - 2).trim();
        try { return Long.parseLong(v); } catch (NumberFormatException x) { return -1; }
    }

    private long cluster(String name) {
        File p = mainPolicy();
        return p == null ? -1 : readLong(new File(p, name));
    }

    /** The policy with the most cores; of those, the one with the lower top clock (the big cluster the game's threads run on, not the one prime core) */
    File mainPolicy() {
        if (found) return main;
        found = true;
        File[] ps = cpufreq.listFiles((d, n) -> n.startsWith("policy"));
        if (ps == null) return null;
        List<File> list = new ArrayList<>();
        for (File p : ps) if (readLong(new File(p, "cpuinfo_max_freq")) > 0) list.add(p);
        list.sort((a, b) -> {
            int ca = cores(a), cb = cores(b);
            return ca != cb ? cb - ca : Long.compare(readLong(new File(a, "cpuinfo_max_freq")), readLong(new File(b, "cpuinfo_max_freq")));
        });
        main = list.isEmpty() ? null : list.get(0);
        return main;
    }

    private static int cores(File policy) {
        String s = read(new File(policy, "related_cpus"));
        return s == null || s.isEmpty() ? 1 : s.split("\\s+").length;
    }

    private static int mhz(long hz) { return hz > 0 ? (int) (hz / 1_000_000L) : -1; }

    private static long readLong(File f) {
        String s = read(f);
        if (s == null) return -1;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    private static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim(); }
        catch (IOException | RuntimeException e) { return null; }
    }
}
