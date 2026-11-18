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
