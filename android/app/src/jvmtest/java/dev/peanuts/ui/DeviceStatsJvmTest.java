package dev.peanuts.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class DeviceStatsJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void put(File root, String path, String text) throws IOException {
        File f = new File(root, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.US_ASCII));
    }

    public static void main(String[] a) throws IOException {
        File root = Files.createTempDirectory("devstats").toFile();
        DeviceStats none = new DeviceStats(root);
        check(!none.hasGpu() && none.gpuBusy() == -1 && none.gpuMhz() == -1, "no kgsl: no GPU");
        check(none.cpuKHz() == -1 && Float.isNaN(none.cpuCap()), "no cpufreq: no CPU");

        // the AYN Thor's layout (Snapdragon 8 Gen 2): policy0 cpu0-2, policy3 cpu3-6, policy7 cpu7
        put(root, "sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "47 %\n");
        put(root, "sys/class/kgsl/kgsl-3d0/gpuclk", "550000000\n");
        put(root, "sys/class/kgsl/kgsl-3d0/max_gpuclk", "680000000\n");
        String[][] pol = {{"policy0", "0 1 2", "2016000", "1228800"}, {"policy3", "3 4 5 6", "2803200", "1785600"}, {"policy7", "7", "3187200", "1843200"}};
        for (String[] p : pol) {
            put(root, "sys/devices/system/cpu/cpufreq/" + p[0] + "/related_cpus", p[1] + "\n");
            put(root, "sys/devices/system/cpu/cpufreq/" + p[0] + "/cpuinfo_max_freq", p[2] + "\n");
            put(root, "sys/devices/system/cpu/cpufreq/" + p[0] + "/scaling_max_freq", p[2] + "\n");
            put(root, "sys/devices/system/cpu/cpufreq/" + p[0] + "/scaling_cur_freq", p[3] + "\n");
        }
        DeviceStats d = new DeviceStats(root);
        check(d.hasGpu() && d.gpuBusy() == 47, "GPU busy from \"47 %\"");
        check(d.gpuMhz() == 550 && d.gpuMaxMhz() == 680, "GPU clocks in MHz");
        check(d.mainPolicy().getName().equals("policy3"), "main cluster: the most cores");
        check(d.cpuKHz() == 1785600 && d.cpuMaxKHz() == 2803200, "main cluster clocks");
        check(Math.abs(d.cpuCap() - 1f) < 1e-6, "no cap");
        put(root, "sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq", "1824000\n");
        check(Math.abs(d.cpuCap() - 1824000f / 2803200f) < 1e-6, "capped");
        put(root, "sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "garbage\n");
        check(d.gpuBusy() == -1, "GPU busy unreadable");
        check(d.appCpuBytes(42) == -1 && d.appGpuBytes(42) == -1, "no process files");
        put(root, "proc/42/status", "Name:\tgame\nVmRSS:\t 3563504 kB\nRssAnon:\t 1891268 kB\nRssFile:\t 1668488 kB\nRssShmem:\t    3748 kB\n");
        check(d.appCpuBytes(42) == (1891268L + 3748L) * 1024L, "CPU side: anon + shmem, not the mapped files");
        put(root, "sys/class/kgsl/kgsl/proc/42/gpumem_mapped", "361476096\n");
        put(root, "sys/class/kgsl/kgsl/proc/42/gpumem_unmapped", "1150337024\n");
        check(d.appGpuBytes(42) == 361476096L + 1150337024L, "GPU side: mapped + unmapped");
        System.out.println("ok");
    }
}
