package dev.peanuts.shaders;

import android.app.Activity;
import android.os.SystemClock;

import java.util.List;

/**
 * The backend of {@link ShaderWatch} (Smoother next start): the Vulkan replay's prewarm list. While a game runs the host puts every pipeline it makes on the
 * frame path or in the background into the game's list (DM_TEMP/vk_pipelines.keys, dm/gfx/vk_android/vka_pso.c pw_note): the shaders and the state a draw
 * needed, by the shader library's keys, so the list holds for every driver and drops what a game update or a new shader library no longer has. Where the driver
 * allows it the next start makes the whole list in the background; on Qualcomm's driver before 512.800 it makes none of it (a pipeline holds memory outside the
 * process there), and those pipelines are made again while the game plays, at every start, their draws skipped until then.
 * <p>
 * Learning on (the tab's switch for this game, {@link #LINE} at the start): each run also keeps the pipelines it used, per game and driver (vka_pso.c sw_*), and
 * the next start makes those first and before the game starts, behind "Preparing shaders", also on that driver, where it stops when memory runs short. Off: the
 * host does as upstream's does, and nothing of this is read or written. The numbers come from the list itself through
 * {@link dev.peanuts.Bridge#shaderWatch} (dm/peanuts/peanuts_shaderwatch.c, vka_pso.c vka_pso_watch).
 * <p>
 * Registered once in the game's process: {@link #env} runs at every start of the game (GameActivity.settingsEnv), before the host and the tab.
 */
public final class HostWatch implements ShaderWatch.Backend {
    private static final HostWatch INSTANCE = new HostWatch();
    /** The host is asked at most this often (the tab reads status about once a second, and the walk holds the list's lock) */
    static final long READ_MS = 500;

    private final int[] v = new int[4];
    private boolean ok;
    private long readAt = Long.MIN_VALUE / 2;

    private HostWatch() {}

    static { if (HostCounts.UNKNOWN != ShaderWatch.UNKNOWN) throw new AssertionError("HostCounts.UNKNOWN"); }

    /** The line that turns learning on for a run (vka_pso.c sw_on); without it the host learns and prepares as upstream's does */
    static final String LINE = "PN_SHADERWATCH=1";

    /**
     * At the start of a game, in its process: the backend registered (once is enough; again is harmless), and learning on for this run when the player turned it on
     * for this game. A preparation boot of Build (dev.peanuts.build.ShaderCapture) is no play: never
     */
    public static void env(Activity a, String titleId, List<String> env) {
        ShaderWatch.backend(INSTANCE);
        env.removeIf(s -> s.startsWith("PN_SHADERWATCH="));
        if (titleId != null && ShaderWatch.on(a, titleId) && !dev.peanuts.build.ShaderCapture.active(a)) env.add(LINE);
    }

    private synchronized int[] read() {
        long now = SystemClock.elapsedRealtime();
        if (now - readAt >= READ_MS) {
            int[] o = new int[4];
            ok = dev.peanuts.Bridge.shaderWatchSafe(o);
            if (ok) System.arraycopy(o, 0, v, 0, 4);
            readAt = now;
        }
        return ok ? v.clone() : null;
    }

    @Override public boolean available(android.content.Context c, String titleId) { return HostCounts.available(read()); }
    @Override public int learned() { return HostCounts.learned(read()); }
    @Override public int ready(android.content.Context c, String titleId) { return HostCounts.ready(read()); }
    /** The switch turned while the game runs: the host keeps (or stops keeping) this run's pipelines at once; the next start follows the switch as it is then */
    @Override public void learning(android.content.Context c, String titleId, boolean on) { dev.peanuts.Bridge.shaderWatchLearnSafe(on); }
}
