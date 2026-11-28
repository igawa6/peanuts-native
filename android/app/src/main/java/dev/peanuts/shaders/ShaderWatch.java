package dev.peanuts.shaders;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Smoother next start (experimental; a switch per game in the quick menu's Peanuts tab): while a game runs, Peanuts counts
 * its stutters and learns the shaders the host had to build during play, so that the next start's shader preparation builds them before play begins and the
 * same hitch does not come twice.
 * <p>
 * This class is the contract between the tab and the backend. The tab reads {@link #available}, {@link #on} and {@link #status}, and feeds the frame times
 * it reads anyway ({@link #frames}): the stutter count is the tab's own. The backend (a record of the shaders and pipelines the host builds at run time,
 * kept per game and build, and the next start's preparation taking them) registers itself with {@link #backend} and fills in what was learned and what is
 * ready. Until a backend is there, {@link #available} is false and those numbers are {@link #UNKNOWN}: the tab shows the switch, marked Experimental, and
 * says it is not active yet.
 */
public final class ShaderWatch {
    private ShaderWatch() {}

    /** A number the backend does not know (or there is no backend) */
    public static final int UNKNOWN = -1;

    /** What this session has seen: stutters (the tab's count), shaders learned this session and shaders ready for the next start (the backend's) */
    public static final class Status {
        public final int stutters, learned, ready;
        public final long lastStutterAt;   // SystemClock.elapsedRealtime of the newest stutter, 0 none
        Status(int stutters, int learned, int ready, long lastStutterAt) { this.stutters = stutters; this.learned = learned; this.ready = ready; this.lastStutterAt = lastStutterAt; }
    }

    /** The backend: whether it works for this game (its build, the driver), what it learned this session and what is ready, and learning on or off */
    public interface Backend {
        boolean available(Context c, String titleId);
        int learned();
        int ready(Context c, String titleId);
        void learning(Context c, String titleId, boolean on);
    }

    private static volatile Backend backend;

    /** The backend registers itself here (in the game's process, before the tab opens); null takes it away */
    public static void backend(Backend b) { backend = b; }

    public static boolean available(Context c, String titleId) {
        Backend b = backend;
        try { return b != null && b.available(c, titleId); } catch (RuntimeException e) { return false; }
    }

    /** Learning while playing, for this game: off until the player turns it on (an experimental feature); kept in the game's prefs file */
    public static boolean on(Context c, String titleId) { return prefs(c).getBoolean("learn_" + titleId, false); }

    public static void on(Context c, String titleId, boolean on) {
        prefs(c).edit().putBoolean("learn_" + titleId, on).apply();
        Backend b = backend;
        if (b != null) try { b.learning(c, titleId, on); } catch (RuntimeException ignored) { }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE); }

    public static Status status(Context c, String titleId) {
        Backend b = backend;
        int learned = UNKNOWN, ready = UNKNOWN;
        if (b != null) {
            try { learned = b.learned(); ready = b.ready(c, titleId); } catch (RuntimeException ignored) { }
        }
        synchronized (ShaderWatch.class) { return new Status(stutters, learned, ready, lastStutterAt); }
    }

    // ---- Stutters, from the frame times (Bridge.frameTimes, newest last) ----

    /** A stutter: a frame over STUTTER_MS that also takes more than RATIO times the median of the frames read with it (a scene's own slow pace is no stutter) */
    static final float STUTTER_MS = 50f, RATIO = 2.5f;

    private static int stutters;
    private static long lastStutterAt;
    private static float[] prev = new float[0];
    private static int prevN;

    /** The newest frame times (ms, oldest first, n of them): the new ones since the last call are checked for stutters */
    public static synchronized void frames(float[] ms, int n) {
        int fresh = dev.peanuts.ui.GameStats.newFrames(prev, prevN, ms, n);
        if (fresh > 0 && prevN > 0) {   // the first read only sets the base: its frames are older than the tab
            int found = count(ms, n, fresh);
            if (found > 0) { stutters += found; lastStutterAt = android.os.SystemClock.elapsedRealtime(); }
        }
        if (prev.length < n) prev = new float[ms.length];
        System.arraycopy(ms, 0, prev, 0, n);
        prevN = n;
    }

    /** How many of the newest k frames of ms[0..n) are stutters (pure: ShaderWatchJvmTest) */
    static int count(float[] ms, int n, int k) {
        if (n <= 0 || k <= 0) return 0;
        float[] sorted = java.util.Arrays.copyOf(ms, n);
        java.util.Arrays.sort(sorted);
        float median = sorted[n / 2];
        int c = 0;
        for (int i = Math.max(0, n - k); i < n; i++) if (ms[i] > STUTTER_MS && ms[i] > median * RATIO) c++;
        return c;
    }

    /** A new run of the game (its process): the count starts again */
    public static synchronized void reset() { stutters = 0; lastStutterAt = 0; prevN = 0; }
}
