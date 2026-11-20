package dev.nxnative.android;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import dev.peanuts.Bridge;
import dev.peanuts.overlay.FrameStats;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * PEANUTS MASTER: bounded local performance telemetry for one game session.
 * Samples real frame history and Vulkan GPU time alongside process CPU/RAM
 * and Android thermal pressure. It never reads game assets, saves, keys or user documents.
 *
 * Disk writes run on a dedicated low-priority thread. Sampling is sparse and never
 * performs file I/O or fsync on the game Activity/UI thread.
 */
final class MasterTelemetry {
    private static final long INTERVAL_MS = 2000;
    private static final int FRAME_WINDOW = 512;
    private static final int FLUSH_EVERY_RECORDS = 4;

    private final Context context;
    private final String titleId;
    private final ActivityManager activityManager;
    private final PowerManager powerManager;
    private final File output;
    private final float[] frameTimes = new float[FRAME_WINDOW];
    private final float[] nativeRate = new float[6];
    private HandlerThread writerThread;
    private Handler writer;
    private BufferedOutputStream stream;
    private int pendingRecords;
    private long startedAt, lastSampleAt, lastCpuAt, lastCpuMs;
    private long samples;
    private boolean started, finished;

    MasterTelemetry(Context context, String titleId, File directory) {
        this.context = context.getApplicationContext();
        this.titleId = titleId == null ? "unknown" : titleId.replaceAll("[^0-9A-Fa-f]", "");
        this.activityManager = this.context.getSystemService(ActivityManager.class);
        this.powerManager = this.context.getSystemService(PowerManager.class);
        this.output = new File(directory, "master-" + this.titleId + "-" + System.currentTimeMillis() + ".jsonl");
    }

    synchronized void start() {
        if (started || finished) return;
        started = true;
        startedAt = SystemClock.elapsedRealtime();
        lastCpuAt = startedAt;
        lastCpuMs = Process.getElapsedCpuTime();
        writerThread = new HandlerThread("PeanutsTelemetry", Process.THREAD_PRIORITY_BACKGROUND);
        writerThread.start();
        writer = new Handler(writerThread.getLooper());
        append("{\"type\":\"session_start\",\"title_id\":\"" + titleId + "\",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"abi\":\"" + Build.SUPPORTED_ABIS[0] + "\",\"elapsed_ms\":" + startedAt + "}");
    }

    synchronized void tick() {
        if (!started || finished) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSampleAt < INTERVAL_MS) return;
        lastSampleAt = now;

        boolean fresh = false;
        try { fresh = Native.fps(nativeRate); } catch (UnsatisfiedLinkError ignored) { }
        int frameCount = 0;
        try { frameCount = Math.max(0, Math.min(FRAME_WINDOW, Bridge.frameTimes(frameTimes))); }
        catch (UnsatisfiedLinkError ignored) { }

        long cpuNow = Process.getElapsedCpuTime();
        long cpuDelta = Math.max(0, cpuNow - lastCpuMs);
        long wallDelta = Math.max(1, now - lastCpuAt);
        float cpuPct = 100f * cpuDelta / wallDelta;
        lastCpuMs = cpuNow;
        lastCpuAt = now;

        Debug.MemoryInfo processMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(processMemory);
        ActivityManager.MemoryInfo systemMemory = new ActivityManager.MemoryInfo();
        if (activityManager != null) activityManager.getMemoryInfo(systemMemory);
        Runtime runtime = Runtime.getRuntime();
        long javaHeapUsed = runtime.totalMemory() - runtime.freeMemory();
        long javaHeapCommitted = runtime.totalMemory();
        long javaHeapMax = runtime.maxMemory();
        long nativeHeapAllocated = Debug.getNativeHeapAllocatedSize();
        long nativeHeapSize = Debug.getNativeHeapSize();
        long nativeHeapFree = Debug.getNativeHeapFreeSize();
        double systemRamUsedPct = systemMemory.totalMem > 0
                ? 100.0 * (systemMemory.totalMem - systemMemory.availMem) / systemMemory.totalMem : -1.0;

        int thermal = -1;
        float headroom = Float.NaN;
        if (Build.VERSION.SDK_INT >= 29 && powerManager != null) {
            try { thermal = powerManager.getCurrentThermalStatus(); } catch (RuntimeException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 30 && powerManager != null) {
            try { headroom = powerManager.getThermalHeadroom(10); } catch (RuntimeException ignored) { }
        }

        float median = FrameStats.median(frameTimes, frameCount);
        float p95 = FrameStats.percentileFrameTime(frameTimes, frameCount, 95);
        float p99 = FrameStats.percentileFrameTime(frameTimes, frameCount, 99);
        float oneLow = FrameStats.onePercentLowFps(frameTimes, frameCount);
        float gpuMs = fresh ? nativeRate[5] : Float.NaN;
        String record = String.format(Locale.ROOT,
                "{\"type\":\"sample\",\"t_ms\":%d,\"native_fps\":%s,\"present_fps\":%s,\"frame_ms\":%s,\"gpu_ms\":%s,"
                        + "\"frame_samples\":%d,\"median_ms\":%s,\"p95_ms\":%s,\"p99_ms\":%s,\"one_percent_low_fps\":%s,"
                        + "\"frame_generation_state\":%d,\"images_per_game_frame\":%s,\"process_cpu_pct\":%.2f,"
                        + "\"process_pss_kb\":%d,\"system_ram_total_kb\":%d,\"system_ram_available_kb\":%d,"
                        + "\"system_low_memory\":%s,\"system_ram_used_pct\":%.2f,"
                        + "\"java_heap_used_kb\":%d,\"java_heap_committed_kb\":%d,\"java_heap_max_kb\":%d,"
                        + "\"native_heap_allocated_kb\":%d,\"native_heap_size_kb\":%d,\"native_heap_free_kb\":%d,"
                        + "\"thermal_status\":%d,\"thermal_headroom\":%s}",
                now - startedAt, number(fresh ? nativeRate[0] : Float.NaN), number(fresh ? nativeRate[1] : Float.NaN),
                number(fresh ? nativeRate[2] : Float.NaN), number(gpuMs), frameCount, number(median), number(p95),
                number(p99), number(oneLow), fresh ? (int) nativeRate[3] : -1,
                number(fresh ? nativeRate[4] : Float.NaN), cpuPct, processMemory.getTotalPss(),
                systemMemory.totalMem / 1024, systemMemory.availMem / 1024, systemMemory.lowMemory, systemRamUsedPct,
                javaHeapUsed / 1024, javaHeapCommitted / 1024, javaHeapMax / 1024,
                nativeHeapAllocated / 1024, nativeHeapSize / 1024, nativeHeapFree / 1024,
                thermal, number(headroom));
        append(record);
        samples++;
    }

    synchronized void memoryPressure(int level) {
        if (!started || finished) return;
        append("{\"type\":\"memory_pressure\",\"t_ms\":" + (SystemClock.elapsedRealtime() - startedAt)
                + ",\"trim_level\":" + level + "}");
    }

    synchronized void finish() {
        if (!started || finished) return;
        finished = true;
        append("{\"type\":\"session_end\",\"duration_ms\":" + (SystemClock.elapsedRealtime() - startedAt)
                + ",\"samples\":" + samples + "}");
        Handler target = writer;
        if (target != null) target.post(() -> {
            closeStream();
            if (writerThread != null) writerThread.quitSafely();
        });
    }

    private void append(String line) {
        Handler target = writer;
        if (target == null) return;
        target.post(() -> writeRecord(line));
    }

    private void writeRecord(String line) {
        try {
            if (stream == null) {
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return;
                stream = new BufferedOutputStream(new FileOutputStream(output, true), 16 * 1024);
            }
            stream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            if (++pendingRecords >= FLUSH_EVERY_RECORDS) {
                stream.flush();
                pendingRecords = 0;
            }
        } catch (IOException | RuntimeException ignored) {
            closeStream();
            // Telemetry must never interrupt or fail a game session.
        }
    }

    private void closeStream() {
        if (stream == null) return;
        try { stream.flush(); } catch (IOException ignored) { }
        try { stream.close(); } catch (IOException ignored) { }
        stream = null;
        pendingRecords = 0;
    }

    private static String number(float value) {
        return Float.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "null";
    }
}
