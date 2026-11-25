// PEANUTS: GameStats on the JVM (dm/tools/tests/test_android_game_stats.py); prints "ok" or throws
package dev.peanuts.ui;

public final class GameStatsJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        float[] f = new float[100];
        for (int i = 0; i < 100; i++) f[i] = 33.3f;
        check(Math.abs(GameStats.fps(f, 100) - 30f) < 0.1f, "fps: a steady 30");
        for (int i = 0; i < 100; i++) f[i] = 16.7f;
        check(Math.abs(GameStats.fps(f, 100) - 60f) < 0.2f, "fps: a steady 60");
        check(Float.isNaN(GameStats.fps(f, 0)), "fps: no frame");
        float[] old = new float[100];
        for (int i = 0; i < 50; i++) old[i] = 100f;
        for (int i = 50; i < 100; i++) old[i] = 33.3f;
        check(Math.abs(GameStats.fps(old, 100) - 30f) < 0.1f, "fps: only the newest second counts");
        check(Math.abs(GameStats.fps(new float[] {50f, 50f}, 2) - 20f) < 1e-3, "fps: less than a second of frames");

        check(GameStats.target(33.3f) == 30, "target 30");
        check(GameStats.target(16.7f) == 60, "target 60");
        check(GameStats.target(36f) == 30, "target: a slow 30 is still 30");
        check(GameStats.target(25f) == 0, "target: 40 fps is none of them");
        check(GameStats.target(Float.NaN) == 0, "target: no median");

        long min = 60_000L;
        check(Float.isNaN(GameStats.drainPerHour(90, 0, 85, 4 * min)), "drain: too soon");
        check(Float.isNaN(GameStats.drainPerHour(90, 0, 89, 30 * min)), "drain: fell too little");
        check(Float.isNaN(GameStats.drainPerHour(80, 0, 85, 30 * min)), "drain: it rose");
        check(Math.abs(GameStats.drainPerHour(90, 0, 81, 30 * min) - 18f) < 1e-3, "drain: 9 points in 30 min is 18 %/h");
        check(GameStats.minutesLeft(81, 18f) == 270, "left: 81 % at 18 %/h");
        check(GameStats.minutesLeft(81, Float.NaN) == -1, "left: no rate");
        check(GameStats.duration(270).equals("4h 30m"), "duration hours");
        check(GameStats.duration(38).equals("38m"), "duration minutes");
        check(GameStats.duration(-1).isEmpty(), "duration none");

        check(GameStats.clock(724_000L).equals("12:04"), "clock");
        check(GameStats.clock(3_909_000L).equals("1:05:09"), "clock hours");
        check(GameStats.clock(-5).equals("0:00"), "clock negative");

        int[] o0 = GameStats.orbit(0), o1 = GameStats.orbit(GameStats.SHIFT_MS);
        check(o0[0] == GameStats.ORBIT_PX && o0[1] == 0, "orbit starts on the circle's right");
        check(Math.abs(o1[0] - o0[0]) + Math.abs(o1[1] - o0[1]) <= 2, "orbit: about a pixel a step");
        check(GameStats.orbit(GameStats.SHIFT_MS - 1)[0] == o0[0] && GameStats.orbit(GameStats.SHIFT_MS - 1)[1] == o0[1], "orbit: still until the step");
        int steps = GameStats.orbitSteps();
        check(GameStats.orbit(GameStats.SHIFT_MS * steps)[0] == o0[0], "orbit: one round, then again");
        int worst = 0;
        for (int i = 0; i < steps; i++) { int[] p = GameStats.orbit(GameStats.SHIFT_MS * i), q = GameStats.orbit(GameStats.SHIFT_MS * (i + 1)); worst = Math.max(worst, Math.max(Math.abs(p[0] - q[0]), Math.abs(p[1] - q[1]))); }
        check(worst <= 1, "orbit: never more than one pixel a step on either axis");
        check(GameStats.OFF_MS > GameStats.DIM_MS, "black after dim");
        float[] p = new float[64], q = new float[64];
        for (int i = 0; i < 64; i++) p[i] = 33f + i * 0.013f;
        check(GameStats.newFrames(p, 0, p, 64) == 64, "new frames: first read");
        check(GameStats.newFrames(p, 64, p, 64) == 0, "new frames: none");
        System.arraycopy(p, 2, q, 0, 62);
        q[62] = 34.1f; q[63] = 32.9f;
        check(GameStats.newFrames(p, 64, q, 64) == 2, "new frames: two");
        System.arraycopy(p, 1, q, 0, 63);
        q[63] = 35f;
        check(GameStats.newFrames(p, 64, q, 64) == 1, "new frames: one");
        float[] grow = java.util.Arrays.copyOf(p, 10), more = java.util.Arrays.copyOf(p, 13);
        check(GameStats.newFrames(grow, 10, more, 13) == 3, "new frames: while the host's buffer fills");
        float[] other = new float[64];
        for (int i = 0; i < 64; i++) other[i] = 50f + i * 0.017f;
        check(GameStats.newFrames(p, 64, other, 64) == 64, "new frames: nothing lines up");
        check(Math.abs(GameStats.amps(-781879L) - 0.781879f) < 1e-6, "amps: microamperes, either sign");
        check(Math.abs(GameStats.amps(780L) - 0.78f) < 1e-6, "amps: a device reporting milliamperes");
        check(Float.isNaN(GameStats.amps(0)) && Float.isNaN(GameStats.amps(Integer.MIN_VALUE)) && Float.isNaN(GameStats.amps(Long.MIN_VALUE)), "amps: no reading");
        check(Math.abs(GameStats.watts(1.5f, 4000) - 6f) < 1e-4, "watts");
        check(Float.isNaN(GameStats.watts(Float.NaN, 4000)) && Float.isNaN(GameStats.watts(1f, 0)), "watts: no reading");
        check(GameStats.minutesLeftAt(6_000_000L, 1.5f) == 240, "minutes left: 6 Ah at 1.5 A");
        check(GameStats.minutesLeftAt(-1, 1.5f) == -1 && GameStats.minutesLeftAt(6_000_000L, 0f) == -1, "minutes left: no reading");
        check(GameStats.shortDuration(270).equals("4h30") && GameStats.shortDuration(38).equals("38m") && GameStats.shortDuration(-1).isEmpty(), "short duration");
        check(GameStats.throttle(-1, Float.NaN, Float.NaN) == -1, "throttle: nothing read");
        check(GameStats.throttle(0, 0.5f, 1f) == 0, "throttle: cool");
        check(GameStats.throttle(1, Float.NaN, Float.NaN) == 1 && GameStats.throttle(0, 0.9f, 1f) == 1 && GameStats.throttle(0, Float.NaN, 0.8f) == 1, "throttle: warm");
        check(GameStats.throttle(2, Float.NaN, Float.NaN) == 2 && GameStats.throttle(0, 1.0f, 1f) == 2 && GameStats.throttle(-1, Float.NaN, 0.6f) == 2, "throttle: throttling");
        float[] pace = {33f, 33f, 70f, 33f, 39f};
        check(Math.abs(GameStats.onPace(pace, 5, 4, 33.3f) - 0.5f) < 1e-6, "on pace: the newest 4, two on pace");
        check(Math.abs(GameStats.onPace(pace, 5, 100, 33.3f) - 0.6f) < 1e-6, "on pace: fewer frames than asked");
        check(Float.isNaN(GameStats.onPace(pace, 0, 4, 33.3f)) && Float.isNaN(GameStats.onPace(pace, 5, 4, Float.NaN)), "on pace: nothing");
        check(GameStats.cause(0, 50, 0.99f) == GameStats.COOL, "cause: cool");
        check(GameStats.cause(1, 50, 0.99f) == GameStats.WARM, "cause: warm");
        check(GameStats.cause(2, 99, 0.5f) == GameStats.THROTTLING, "cause: throttling comes first");
        check(GameStats.cause(0, 97, 0.8f) == GameStats.GPU_BOUND, "cause: GPU-bound when behind with a full GPU");
        check(GameStats.cause(1, 97, 0.8f) == GameStats.GPU_BOUND, "cause: GPU-bound over warm");
        check(GameStats.cause(0, 97, 0.95f) == GameStats.COOL, "cause: a full GPU on pace is fine");
        check(GameStats.cause(0, 60, 0.5f) == GameStats.COOL, "cause: behind but the GPU is not full: not claimed");
        check(GameStats.cause(-1, -1, Float.NaN) == -1, "cause: nothing read");
        check(GameStats.cause(0, 97, Float.NaN) == GameStats.COOL, "cause: no pace, no claim");
        float[] wm = {33f, 40f, 34f, 70f, 33f};
        long[] we = {100, 400, 600, 900, 1600};
        float[] out = new float[4];
        GameStats.worst(wm, we, 5, 0, 4, 500, out);
        check(out[0] == 40f && out[1] == 70f && Float.isNaN(out[2]) && out[3] == 33f, "worst: per bucket, an empty bucket NaN");
        GameStats.worst(wm, we, 5, 1, 4, 500, out);
        check(out[0] == 70f && Float.isNaN(out[1]) && out[2] == 33f && Float.isNaN(out[3]), "worst: from a later first bucket, older frames left out");
        check(GameStats.hidesTop(true, true, true, true, true), "hides top: all on");
        check(!GameStats.hidesTop(false, true, true, true, true), "hides top: setting off");
        check(!GameStats.hidesTop(true, false, true, true, true), "hides top: not playing");
        check(!GameStats.hidesTop(true, true, false, true, true), "hides top: no stats page");
        check(!GameStats.hidesTop(true, true, true, false, true), "hides top: window not shown");
        check(!GameStats.hidesTop(true, true, true, true, false), "hides top: display off");
        check(GameStats.aim(120, 1000f / 119f) == 120, "aim: near a 120 cap");
        check(GameStats.aim(90, 1000f / 88f) == 90, "aim: near a 90 cap");
        check(GameStats.aim(120, 1000f / 60f) == 60, "aim: far below the cap, the common rate");
        check(GameStats.aim(0, 1000f / 30f) == 30, "aim: no cap, the common rate");
        check(GameStats.aim(120, Float.NaN) == 0, "aim: no frames, none");
        System.out.println("ok");
    }
}
