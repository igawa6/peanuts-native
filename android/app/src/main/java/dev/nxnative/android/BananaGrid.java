package dev.nxnative.android;

/**
 * The geometry of the launcher's banana background (BananaBackgroundView), with no Android classes so it runs on the JVM: where each banana of the drifting,
 * tilted grid is, and which one a tap hit. A banana is named by its absolute row and column: they do not change as the drift wraps, so a popped banana stays
 * the same banana while it drifts.
 *
 * Grid space has its origin at the view's centre and is rotated by {@link #GRID_DEG} on screen. Row R is at y = R * row + wy; its bananas are at
 * x = C * step + wx, shifted by half a step in the rows where {@link #odd} is true. wx and wy are the drift so far in px, not wrapped.
 */
final class BananaGrid {
    private BananaGrid() {}

    static final float GRID_DEG = -14f;                        // the whole grid's tilt
    static final float TILE_DP = 76, STEP_DP = 124, ROW_DP = 96;
    static final float SPEED_X_DP = 9f, SPEED_Y_DP = 6f;       // drift, dp per second
    static final float HIT_DP = 34;                            // a tap this near a banana's centre pops it (the tile is 76 dp, the banana fills about two thirds)

    /** The rows that lean one way and are shifted half a step sideways (the rotation of the drawn banana follows it: 28° and -8°) */
    static boolean odd(int row) { return (row & 1) == 0; }

    static long key(int row, int col) { return ((long) row << 32) | (col & 0xFFFFFFFFL); }

    static double centreX(int row, int col, float step, double wx) { return col * (double) step + (odd(row) ? step / 2.0 : 0) + wx; }

    static double centreY(int row, float rowPx, double wy) { return row * (double) rowPx + wy; }

    /**
     * The banana a tap at (x, y) in a w × h view is on, as {@link #key}, or {@link #NONE}. wx and wy: the drift in px (phase * speed * density, not wrapped).
     */
    static long hit(float x, float y, float w, float h, double wx, double wy, float density) {
        float step = STEP_DP * density, row = ROW_DP * density, reach = HIT_DP * density;
        double a = Math.toRadians(GRID_DEG), cos = Math.cos(a), sin = Math.sin(a);
        double dx = x - w / 2.0, dy = y - h / 2.0;
        double gx = dx * cos + dy * sin, gy = -dx * sin + dy * cos;   // the tap in grid space: the rotation undone
        int r0 = (int) Math.round((gy - wy) / row);
        long best = NONE;
        double bestD = reach * (double) reach;
        for (int r = r0 - 1; r <= r0 + 1; r++) {
            int c = (int) Math.round((gx - wx - (odd(r) ? step / 2.0 : 0)) / step);
            double ex = gx - centreX(r, c, step, wx), ey = gy - centreY(r, row, wy), d = ex * ex + ey * ey;
            if (d <= bestD) { bestD = d; best = key(r, c); }
        }
        return best;
    }

    static final long NONE = Long.MIN_VALUE;
}
