package dev.nxnative.android;

/** Frame limits for profiles checked with higher presentation targets. */
final class FrameRateCaps {
    static final int GAME_OWNED = -2;
    private FrameRateCaps() {}

    static int[] menu() { return new int[] {GAME_OWNED, 120, 90, 0, 45, 40, 30, 20}; }
    static boolean contains(int[] values, int value) { for (int v : values) if (v == value) return true; return false; }

    static int[] rates(int maximum) {
        if (maximum >= 120) return new int[] {GAME_OWNED, 120, 90, 0, 45, 40, 30, 20};
        if (maximum >= 90) return new int[] {GAME_OWNED, 90, 0, 45, 40, 30, 20};
        return new int[] {GAME_OWNED, 0, 45, 40, 30, 20};
    }

    static int[] rates(int maximum, int minimum) {
        int[] all = rates(maximum);
        if (minimum <= 20) return all;
        int count = 0;
        for (int v : all) if (v <= 0 || v >= minimum) count++;
        int[] allowed = new int[count]; int i = 0;
        for (int v : all) if (v <= 0 || v >= minimum) allowed[i++] = v;
        return allowed;
    }

    // Saved value 0 still means 60 fps; higher limits use their actual rate.
    static int normalize(int value, int maximum) {
        if (value == GAME_OWNED) return GAME_OWNED;
        if (maximum >= 120 && value >= 120) return 120;
        if (maximum >= 90 && value >= 90) return 90;
        return value <= 0 || value >= 60 ? 0 : value >= 45 ? 45 : value >= 40 ? 40 : value >= 30 ? 30 : 20;
    }
}
