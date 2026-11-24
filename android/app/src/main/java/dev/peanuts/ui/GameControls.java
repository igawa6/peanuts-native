package dev.peanuts.ui;

/**
 * The running game's console mode and frame rate cap for the second screen's stats page (StatsView's pop-ups). GameActivity hands its Host in at onCreate
 * (the same calls as the in-game panel's Console mode and Frame rate cap rows, SidePanel) and takes it back at onDestroy; null while no game runs.
 */
public final class GameControls {
    public interface Host {
        /** 0 Docked, 1 Handheld (ScreenMode); -1 when the game's Resolution sets the mode (no choice) */
        int consoleMode();
        void consoleMode(int m);
        /** The caps in menu order (0: game owned) */
        int[] caps();
        String capLabel(int v);
        /** False: shown greyed out, not offered for this game */
        boolean capOffered(int v);
        /** The cap picked (kept for the game); -1 when it is none of caps() */
        int cap();
        /** The cap the game runs at now: the picked one, or the one it started with while the picked one waits for a restart (a limited game's 120 or Game owned) */
        int capRunning();
        void cap(int v);
        /** The frame rate cap v holds the game to; 0 when the game owns its rate */
        int capRate(int v);
        /** A line under the caps, or null */
        String capNote();
    }

    private static volatile Host host;
    private GameControls() {}
    public static void host(Host h) { host = h; }
    public static Host host() { return host; }
}
