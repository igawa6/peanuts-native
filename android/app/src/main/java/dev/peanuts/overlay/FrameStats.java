package dev.peanuts.overlay;

import java.util.Arrays;

/**
 * PEANUTS: the overlay table's frame-time maths (pure Java): the median, the 1% low, a frame's level against the target, and the histogram's placeholder text.
 * The target is the median of the window, so a 30 fps game aims at 33.3 ms and a 60 fps one at 16.7 ms.
 */
public final class FrameStats {
    private FrameStats() {}

    /** Levels, as Overlay's throttle dot: 0 green, 1 yellow, 2 red */
    public static final int BUCKETS = 8;

    /** Median of ms[0..n) in ms; NaN for no frame */
    public static float median(float[] ms, int n) {
        if (n <= 0) return Float.NaN;
        float[] c = Arrays.copyOf(ms, n);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2f;
    }

    /**
     * 1000 / the 99th-percentile frame time of ms[0..n) in fps: the value at index ceil(0.99 * n) - 1 of the sorted copy.
     * A percentile, not the mean of the worst 1%, so one long hitch in a 512-frame window does not drag the low down. NaN below 10 frames.
     * ceil(0.99 * n) is computed as the integer (99n + 99) / 100 to avoid floating-point rounding.
     */
    public static float onePercentLowFps(float[] ms, int n) {
        if (n < 10) return Float.NaN;
        float[] c = Arrays.copyOf(ms, n);
        Arrays.sort(c);
        int idx = (99 * n + 99) / 100 - 1;
        float p99 = c[idx];
        return p99 > 0 ? 1000f / p99 : Float.NaN;
    }

    /** The longest DRV value the overlay shows, in chars (the last one is the ellipsis) */
    public static final int MAX_DRIVER = 18;

    /**
     * The short form of a GPU driver's label for the DRV row: every parenthesised group is removed, except that the inner text of the first one is kept when the text
     * before it has no digit ("Turnip (27.0.0)" becomes "Turnip 27.0.0"); then the whole words Adreno, Driver and Vulkan (any case) and any word starting with "@" are
     * dropped, spaces collapsed, and the result trimmed and capped at MAX_DRIVER chars, ending with an ellipsis
     * ("Turnip Adreno Driver T30 (@Mr_Purple_666) (26.3.0-T30-1.4.359)" becomes "Turnip T30").
     */
    public static String shortDriver(String label) {
        if (label == null) return "";
        String s = label.trim();
        int open = s.indexOf('(');
        if (open >= 0) {
            int close = s.indexOf(')', open);
            String head = s.substring(0, open);
            String inner = (close > open ? s.substring(open + 1, close) : s.substring(open + 1)).trim();
            String rest = close > open ? s.substring(close + 1) : "";
            rest = rest.replaceAll("\\([^)]*\\)?", " ");
            s = head.matches(".*\\d.*") || inner.isEmpty() ? head + " " + rest : head + " " + inner + " " + rest;
        }
        s = s.replaceAll("(?i)(^|\\s)(adreno|driver|vulkan|@\\S*)(?=\\s|$)", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > MAX_DRIVER ? s.substring(0, MAX_DRIVER - 1) + '\u2026' : s;
    }

    /** 0 when the frame took up to 115 % of the target, 2 from twice the target, else 1 */
    public static int level(float ms, float target) {
        if (ms <= target * 1.15f) return 0;
        if (ms >= target * 2f) return 2;
        return 1;
    }

    /** The label colours by group: FPS (and THR, FT, 1%L), GPU, CPU, SOC and BAT, GAME and RAM, DRV; any other label is grey */
    public static final int LABEL_FPS = 0xFF7FD4FF, LABEL_GPU = 0xFFA5E37A, LABEL_CPU = 0xFF5FA8FF, LABEL_SOC = 0xFFFF9E5E, LABEL_MEM = 0xFFE58CFF,
            LABEL_DEFAULT = 0xFFB0B0B0;

    /** The colour of a row's label, by its text (Hud) */
    public static int labelColor(String label) {
        switch (label) {
            case "FPS": case "THR": case "FT": case "1%L": return LABEL_FPS;
            case "GPU": return LABEL_GPU;
            case "CPU": return LABEL_CPU;
            case "SOC": case "BAT": return LABEL_SOC;
            case "GAME": case "RAM": return LABEL_MEM;
            default: return LABEL_DEFAULT;
        }
    }

    /** BAT's status from the battery temperature: 0 up to 42 °C, 1 (yellow) above 42, 2 (red) above 45; no reading (NaN) is 0 */
    public static int batteryLevel(float tempC) {
        return tempC > 45f ? 2 : tempC > 42f ? 1 : 0;
    }

    /** RAM's status: 2 when the system reports low memory, 1 (yellow) when more than 85 % of the total is in use, else 0 */
    public static int ramLevel(long used, long total, boolean low) {
        if (low) return 2;
        return total > 0 && (double) used / total > 0.85 ? 1 : 0;
    }

    /** The bar's height class, 0..7: 0 for a frame of no time, 7 from twice the target */
    public static int bucket(float ms, float target) {
        if (!(target > 0) || !(ms > 0)) return 0;
        return Math.min(BUCKETS - 1, (int) (ms / (target * 2f) * BUCKETS));
    }

    /** One char a bar, level * 8 + bucket counted from 'A': what the histogram span draws, and what makes Overlay.key see a change of the data */
    public static String placeholder(float[] ms, int from, int count, float target) {
        StringBuilder b = new StringBuilder(count);
        for (int i = from; i < from + count; i++) b.append((char) ('A' + level(ms[i], target) * BUCKETS + bucket(ms[i], target)));
        return b.toString();
    }

    public static int bucketOf(char c) { return Math.max(0, c - 'A') % BUCKETS; }

    public static int levelOf(char c) { return Math.min(2, Math.max(0, c - 'A') / BUCKETS); }
}
