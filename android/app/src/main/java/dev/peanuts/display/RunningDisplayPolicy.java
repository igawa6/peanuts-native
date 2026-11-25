package dev.peanuts.display;

/** Pure policy for the running game's window, independent of saved or pending preferences. */
public final class RunningDisplayPolicy {
    private RunningDisplayPolicy() {}

    public static final class Mode {
        public final int id, width, height;
        public final float hz;
        public Mode(int id, int width, int height, float hz) {
            this.id = id; this.width = width; this.height = height; this.hz = hz;
        }
    }

    /** The fourth native status field is the held cap; a zero configured game rate means unavailable state. */
    public static int effectiveCap(String status) {
        if (status == null) return 0;
        String[] fields = status.trim().split("\\s+");
        try { return fields.length >= 4 && Integer.parseInt(fields[2]) > 0 ? Integer.parseInt(fields[3]) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * On a TV or monitor (Play on the TV, #358): a mode near 60 Hz when the display runs at a rate that is not a whole multiple of 60 (30 or 50 Hz, or 144),
     * at its own resolution when it has one, else the largest below it (a 4K TV behind an HDMI 1.4 adapter runs 4K at 30 Hz and 1080p at 60). A game's
     * 30 and 60 fps fall evenly on 60 and 120 Hz; on 30 or 50 Hz they judder. Zero: no request.
     */
    public static int tvMode(int width, int height, float hz, Mode[] modes) {
        if (evenFor60(hz)) return 0;
        int id = 0; long best = -1;
        for (Mode mode : modes) {
            if (Math.abs(mode.hz - 60f) >= 0.5f || mode.width > width || mode.height > height) continue;
            long pixels = (long) mode.width * mode.height;
            if (pixels > best) { best = pixels; id = mode.id; }
        }
        return id;
    }

    /** 60, 120, 180 or 240 Hz (59.94 counts): a game's 30 and 60 fps fall on whole refreshes */
    static boolean evenFor60(float hz) {
        float m = hz / 60f;
        return m >= 0.99f && Math.abs(m - Math.round(m)) < 0.01f;
    }

    /** Zero releases the request. Unknown frame-generation state must never restrict its output. */
    public static int mode(int cap, int frameGenerationEnabled, int width, int height, Mode[] modes) {
        if (cap != 30 || frameGenerationEnabled != 0) return 0;
        int id = 0; float distance = 0.5f;
        for (Mode mode : modes) {
            float delta = Math.abs(mode.hz - 60f);
            if (mode.width == width && mode.height == height && delta < distance) {
                id = mode.id; distance = delta;
            }
        }
        return id;
    }
}
