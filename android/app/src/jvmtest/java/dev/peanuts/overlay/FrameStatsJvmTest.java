package dev.peanuts.overlay;

public final class FrameStatsJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        float[] f = new float[100];
        for (int i = 0; i < 99; i++) f[i] = 33.3f;
        f[99] = 100f;
        check(Math.abs(FrameStats.median(f, 100) - 33.3f) < 1e-4, "median");
        check(Math.abs(FrameStats.median(new float[] {1, 2, 3, 4}, 4) - 2.5f) < 1e-4, "median even");
        check(Float.isNaN(FrameStats.median(f, 0)), "median empty");
        check(Math.abs(FrameStats.onePercentLowFps(f, 100) - 1000f / 33.3f) < 1e-2, "1% low: one hitch in 100 is not the low");
        check(Math.abs(FrameStats.onePercentLowFps(f, 100) - 30f) < 0.1f, "1% low ~ 30 fps at a steady 30");
        check(Float.isNaN(FrameStats.onePercentLowFps(f, 9)), "n < 10");
        check(Math.abs(FrameStats.onePercentLowFps(f, 10) - 1000f / 33.3f) < 1e-2, "small n");
        float[] h = new float[100];
        for (int i = 0; i < 90; i++) h[i] = 33.3f;
        for (int i = 90; i < 100; i++) h[i] = 100f;
        check(Math.abs(FrameStats.onePercentLowFps(h, 100) - 10f) < 1e-3, "1% low: 10 hitches in 100 give 10 fps");
        float[] u = new float[512];
        for (int i = 0; i < 512; i++) u[i] = 33.3f;
        u[7] = 400f;
        check(Math.abs(FrameStats.onePercentLowFps(u, 512) - 1000f / 33.3f) < 1e-2, "1% low: a single hitch in 512 frames");
        check(FrameStats.shortDriver("Turnip 27.0.0-R2 (Vulkan 1.4.367)").equals("Turnip 27.0.0-R2"), "driver: drop parenthesis");
        check(FrameStats.shortDriver("Turnip (27.0.0)").equals("Turnip 27.0.0"), "driver: join when no digit before");
        check(FrameStats.shortDriver("  System  ").equals("System"), "driver: trim");
        check(FrameStats.shortDriver(null).isEmpty(), "driver: null");
        check(FrameStats.shortDriver("(27.0.0)").equals("27.0.0"), "driver: only parenthesis");
        check(FrameStats.shortDriver("Adreno (TM) 750").equals("TM 750"), "driver: parenthesis after a digit-free word, text after kept, Adreno dropped");
        check(FrameStats.shortDriver("Turnip Adreno Driver T30 (@Mr_Purple_666) (26.3.0-T30-1.4.359)").equals("Turnip T30"), "driver: name with author and version groups");
        check(FrameStats.shortDriver("Qualcomm 842.44").equals("Qualcomm 842.44"), "driver: plain name unchanged");
        check(FrameStats.shortDriver("").isEmpty(), "driver: empty");
        check(FrameStats.shortDriver("Turnip 27.0.0 (Vulkan 1.4)").equals("Turnip 27.0.0"), "driver: parenthesis after a digit");
        String cut = FrameStats.shortDriver("Turnip Open Source Driver Mesa 25");
        check(cut.length() == FrameStats.MAX_DRIVER && cut.endsWith("…"), "driver: capped with ellipsis");
        check(cut.equals("Turnip Open Sourc…"), "driver: cap is 17 chars then the ellipsis");
        check(FrameStats.shortDriver("Exactly eighteen!!").equals("Exactly eighteen!!"), "driver: 18 chars kept");
        check(FrameStats.level(33.3f, 33.3f) == 0 && FrameStats.level(38f, 33.3f) == 0, "green");
        check(FrameStats.level(39f, 33.3f) == 1 && FrameStats.level(66f, 33.3f) == 1, "yellow");
        check(FrameStats.level(66.6f, 33.3f) == 2 && FrameStats.level(100f, 33.3f) == 2, "red");
        String p1 = FrameStats.placeholder(f, 52, 48, 33.3f), p2 = FrameStats.placeholder(f, 52, 47, 33.3f);
        check(p1.length() == 48, "placeholder length");
        float[] g = f.clone();
        g[60] = 50f;
        check(!p1.equals(FrameStats.placeholder(g, 52, 48, 33.3f)), "placeholder changes with data");
        check(!p1.equals(p2), "placeholder changes with count");
        char c = p1.charAt(47);
        check(FrameStats.levelOf(c) == 2 && FrameStats.bucketOf(c) == 7, "decode red top bar");
        check(FrameStats.levelOf(p1.charAt(0)) == 0, "decode green");
        check(FrameStats.labelColor("FPS") == 0xFF7FD4FF && FrameStats.labelColor("THR") == 0xFF7FD4FF && FrameStats.labelColor("FT") == 0xFF7FD4FF
                && FrameStats.labelColor("1%L") == 0xFF7FD4FF, "label colour: FPS group");
        check(FrameStats.labelColor("GPU") == 0xFFA5E37A, "label colour: GPU");
        check(FrameStats.labelColor("CPU") == 0xFF5FA8FF, "label colour: CPU");
        check(FrameStats.labelColor("SOC") == 0xFFFF9E5E && FrameStats.labelColor("BAT") == 0xFFFF9E5E, "label colour: SOC and BAT");
        check(FrameStats.labelColor("GAME") == 0xFFE58CFF && FrameStats.labelColor("RAM") == 0xFFE58CFF, "label colour: GAME and RAM");
        check(FrameStats.labelColor("DRV") == 0xFFB0B0B0 && FrameStats.labelColor("X") == 0xFFB0B0B0, "label colour: DRV and unknown grey");
        check(FrameStats.batteryLevel(30f) == 0 && FrameStats.batteryLevel(42f) == 0 && FrameStats.batteryLevel(Float.NaN) == 0, "bat: normal up to 42");
        check(FrameStats.batteryLevel(42.5f) == 1 && FrameStats.batteryLevel(45f) == 1, "bat: yellow above 42 up to 45");
        check(FrameStats.batteryLevel(45.5f) == 2, "bat: red above 45");
        check(FrameStats.ramLevel(50, 100, false) == 0 && FrameStats.ramLevel(85, 100, false) == 0, "ram: normal up to 85 %");
        check(FrameStats.ramLevel(86, 100, false) == 1, "ram: yellow above 85 %");
        check(FrameStats.ramLevel(10, 100, true) == 2 && FrameStats.ramLevel(10, 100, false) == 0, "ram: red when low memory");
        check(FrameStats.ramLevel(0, 0, false) == 0, "ram: no total");
        System.out.println("ok");
    }
}
