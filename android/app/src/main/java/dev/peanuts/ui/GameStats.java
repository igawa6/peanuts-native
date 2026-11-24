package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The maths of the second screen's stats page (StatsView), pure Java so a JVM test runs them: the frame rate over the newest second of frame times, the
 * battery's drain a hour and the time left, the run's clock, and the page's burn-in shift.
 */
public final class GameStats {
    private GameStats() {}

    /** Frames a second over the newest second of ms[0..n) (oldest first): the frames whose times add up to 1000 ms, over their sum. NaN for no frame */
    public static float fps(float[] ms, int n) {
        float sum = 0f;
        int k = 0;
        for (int i = n - 1; i >= 0 && sum < 1000f; i--) {
            if (!(ms[i] > 0f)) continue;
            sum += ms[i];
            k++;
        }
        return k == 0 || !(sum > 0f) ? Float.NaN : k * 1000f / sum;
    }

    /** The target frames are measured against: the cap's rate ({@code capRate}, 0: none) when the median is within 15 % of it, else target(medianMs) */
    public static int aim(int capRate, float medianMs) {
        if (capRate > 0 && medianMs > 0f && Math.abs(1000f / medianMs - capRate) / capRate <= 0.15f) return capRate;
        return target(medianMs);
    }

    /** The game's own target: the common rate (15, 20, 30, 60) nearest a frame time's median, 0 when none is within 15 % */
    public static int target(float medianMs) {
        if (!(medianMs > 0f)) return 0;
        float r = 1000f / medianMs;
        int best = 0;
        float off = Float.MAX_VALUE;
        for (int t : new int[] {15, 20, 30, 60}) {
            float d = Math.abs(r - t) / t;
            if (d < off) { off = d; best = t; }
        }
        return off <= 0.15f ? best : 0;
    }

    /** Minimum run time and fall (percentage points) before a drain rate is shown: earlier it is noise */
    static final long MIN_MS = 5 * 60_000L;
    static final int MIN_DROP = 2;

    /** The battery's fall in % an hour since a level at a time; NaN while too soon, or when it did not fall */
    public static float drainPerHour(int fromLevel, long fromMs, int level, long nowMs) {
        long dt = nowMs - fromMs;
        int drop = fromLevel - level;
        if (fromLevel < 0 || level < 0 || dt < MIN_MS || drop < MIN_DROP) return Float.NaN;
        return drop * 3_600_000f / dt;
    }

    /** Minutes left at that rate; -1 when there is no rate */
    public static int minutesLeft(int level, float perHour) {
        if (!(perHour > 0f) || level < 0) return -1;
        return Math.round(level / perHour * 60f);
    }

    /** "4h 40m", "38m"; empty for -1 */
    public static String duration(int minutes) {
        if (minutes < 0) return "";
        return minutes >= 60 ? String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60) : minutes + "m";
    }

    /** The run's clock: "12:04", "1:05:09" */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * BatteryManager.BATTERY_PROPERTY_CURRENT_NOW in amperes. It is microamperes by the documentation; some devices report milliamperes, so a value under
     * 20000 is taken as mA (a phone draws 0.1-6 A: 1e5-6e6 uA, 100-6000 mA). The sign differs between devices too: magnitude only. NaN for no reading
     * (Integer.MIN_VALUE, Long.MIN_VALUE or 0).
     */
    public static float amps(long now) {
        if (now == 0 || now == Integer.MIN_VALUE || now == Long.MIN_VALUE) return Float.NaN;
        long a = Math.abs(now);
        return a < 20000 ? a / 1e3f : a / 1e6f;
    }

    /** Watts from amperes and the battery's voltage in millivolts (BatteryManager.EXTRA_VOLTAGE); NaN without either */
    public static float watts(float amps, int millivolts) {
        return amps > 0f && millivolts > 0 ? amps * millivolts / 1000f : Float.NaN;
    }

    /** Minutes the remaining charge lasts at a current: BATTERY_PROPERTY_CHARGE_COUNTER (uAh) over the current; -1 without either */
    public static int minutesLeftAt(long chargeUah, float amps) {
        if (chargeUah <= 0 || chargeUah == Long.MIN_VALUE || !(amps > 0.02f)) return -1;
        return Math.round(chargeUah / 1e6f / amps * 60f);
    }

    /** "4h30", "38m": the short form for a tile's label; empty for -1 */
    public static String shortDuration(int minutes) {
        if (minutes < 0) return "";
        return minutes >= 60 ? String.format(Locale.ROOT, "%dh%02d", minutes / 60, minutes % 60) : minutes + "m";
    }

    /**
     * The throttle state as Overlay's dot reads it: 2 (throttling) at thermal status MODERATE or worse, thermal headroom 1.0 or more, or the main CPU cluster
     * capped below 65 % of its top clock; 1 (warm) at status LIGHT, headroom 0.85 or more, or a cap below 85 %; 0 (cool) otherwise; -1 when nothing reads.
     * thermal -1, headroom NaN and cap NaN are no reading.
     */
    public static int throttle(int thermal, float headroom, float cap) {
        if (thermal < 0 && Float.isNaN(headroom) && Float.isNaN(cap)) return -1;
        if (thermal >= 2 || headroom >= 1.0f || cap < 0.65f) return 2;
        if (thermal >= 1 || headroom >= 0.85f || cap < 0.85f) return 1;
        return 0;
    }

    /** The header's state pill: COOL, WARM, THROTTLING (Android's thermal signals, {@link #throttle}) or GPU_BOUND (the game is behind and the GPU is full) */
    public static final int COOL = 0, WARM = 1, THROTTLING = 2, GPU_BOUND = 3;

    /**
     * Why the game is slow, or that it is not: THROTTLING first (the system is cutting the clocks), then GPU_BOUND (fewer than 90 % of the frames on pace
     * while the GPU is 95 % busy or more), then WARM or COOL. -1 when nothing reads and the GPU is not the cause. thermal is {@link #throttle}'s level,
     * gpuBusy -1 unread, pace NaN unread.
     */
    public static int cause(int thermal, int gpuBusy, float pace) {
        if (thermal >= 2) return THROTTLING;
        if (gpuBusy >= 95 && pace < 0.9f) return GPU_BOUND;
        return thermal;
    }

    /** Whether the overlay over the game hides because the second screen shows the stats page (GameScreen, Hud.hideForStats): only while that page is really up */
    public static boolean hidesTop(boolean setting, boolean playing, boolean statsPage, boolean windowShown, boolean displayOn) {
        return setting && playing && statsPage && windowShown && displayOn;
    }

    /**
     * The worst frame time of each of {@code buckets} time buckets, oldest first, into out (NaN for a bucket with no frame): the 30-second graph. Frame i
     * took ms[i] and ended at endNs[i] (oldest first); bucket j holds the frames that ended in [(first + j) * bucketNs, (first + j + 1) * bucketNs).
     */
    public static void worst(float[] ms, long[] endNs, int n, long first, int buckets, long bucketNs, float[] out) {
        for (int j = 0; j < buckets; j++) out[j] = Float.NaN;
        for (int i = 0; i < n; i++) {
            long j = Math.floorDiv(endNs[i], bucketNs) - first;
            if (j < 0 || j >= buckets) continue;
            int b = (int) j;
            if (Float.isNaN(out[b]) || ms[i] > out[b]) out[b] = ms[i];
        }
    }

    /** The share (0..1) of the newest count frames of ms[0..n) that were on pace: within 15 % of the target (FrameStats.level 0). NaN for no frame or target */
    public static float onPace(float[] ms, int n, int count, float target) {
        int k = Math.min(n, count);
        if (k <= 0 || !(target > 0f)) return Float.NaN;
        int on = 0;
        for (int i = n - k; i < n; i++) if (ms[i] <= target * 1.15f) on++;
        return (float) on / k;
    }

    /** How many frames the read has to overlap the previous one by to count as the same stream */
    static final int OVERLAP = 12;

    /**
     * How many frames are new in cur[0..cn) since prev[0..pn) (two reads of Bridge.frameTimes, newest last, which carry no counter): the smallest k for which
     * cur without its newest k frames ends with the same frames as prev (up to OVERLAP of them; the host's values are exact, microseconds over 1000).
     * cn when nothing lines up (the first read, a reset, or more new frames than the read holds); 0 for no change.
     */
    public static int newFrames(float[] prev, int pn, float[] cur, int cn) {
        if (pn <= 0) return cn;
        for (int k = 0; k < cn; k++) {
            int l = Math.min(Math.min(cn - k, pn), OVERLAP);
            if (l < Math.min(4, Math.min(cn, pn))) break;   // too short an overlap to trust
            boolean same = true;
            for (int i = 1; i <= l && same; i++) same = cur[cn - k - i] == prev[pn - i];
            if (same) return k;
        }
        return cn;
    }

    /**
     * Burn-in guard, the pixel shifter: the whole page goes round a circle of ORBIT_PX pixels, a step of one pixel every SHIFT_MS (one round in about half an
     * hour), so no pixel shows one thing for long and the move cannot be seen. Pixels, not dp: one step is the panel's smallest.
     */
    public static final int ORBIT_PX = 8;
    public static final long SHIFT_MS = 30_000L;
    /** No touch this long: the page dims; this long: it goes black but for a small moving dot */
    public static final long DIM_MS = 10 * 60_000L, OFF_MS = 30 * 60_000L;

    /** The circle as a walk of pixels, each next to the one before (at most one pixel on either axis): sampled finely, repeats dropped */
    private static final int[][] PATH = path();

    private static int[][] path() {
        List<int[]> p = new ArrayList<>();
        for (int i = 0; i < 2048; i++) {
            double a = 2 * Math.PI * i / 2048;
            int[] q = {(int) Math.round(ORBIT_PX * Math.cos(a)), (int) Math.round(ORBIT_PX * Math.sin(a))};
            int[] last = p.isEmpty() ? null : p.get(p.size() - 1);
            if (last == null || last[0] != q[0] || last[1] != q[1]) p.add(q);
        }
        int[] first = p.get(0), end = p.get(p.size() - 1);
        if (first[0] == end[0] && first[1] == end[1]) p.remove(p.size() - 1);
        return p.toArray(new int[0][]);
    }

    /** The offset {x, y} in pixels for a time since the run started: the next pixel of the circle every SHIFT_MS */
    public static int[] orbit(long sinceStartMs) {
        return PATH[(int) (Math.max(0, sinceStartMs) / SHIFT_MS % PATH.length)];
    }

    /** How many steps one round takes */
    public static int orbitSteps() { return PATH.length; }
}
