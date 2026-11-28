package dev.peanuts.launch;

/**
 * PEANUTS: where a game's cover goes in its home-screen shortcut (plain Java, JVM-tested: peanuts/tests/test_shortcut_art.py, ShortcutArtJvmTest). An adaptive
 * icon is 108 dp square and a launcher shows only about its middle 72 dp, cut to its own shape. The cover was cropped to a square and drawn over all 108 dp, so a
 * launcher that does not let the player change it, such as the phone's own, showed about half of a tall cover, zoomed in (issue #258). The whole cover now goes
 * into the middle two thirds, on its own average colour.
 */
final class ShortcutArt {
    private ShortcutArt() {}

    /** The part of a size x size adaptive icon a launcher shows: its middle two thirds (72 of 108 dp) */
    static int shown(int size) { return size * 2 / 3; }

    /** Where a w x h cover goes in a size x size adaptive icon: {left, top, right, bottom}, the whole cover as large as fits the shown part, centred */
    static int[] fit(int w, int h, int size) {
        int box = shown(size);
        if (w <= 0 || h <= 0) return new int[] {(size - box) / 2, (size - box) / 2, (size + box) / 2, (size + box) / 2};
        int dw = w >= h ? box : Math.max(1, (int) Math.round((double) box * w / h)), dh = h >= w ? box : Math.max(1, (int) Math.round((double) box * h / w));
        int left = (size - dw) / 2, top = (size - dh) / 2;
        return new int[] {left, top, left + dw, top + dh};
    }

    /** The average colour of the cover's pixels (ARGB), opaque, for the icon's background around the cover; pixels mostly transparent count less */
    static int average(int[] argb) {
        long r = 0, g = 0, b = 0, wsum = 0;
        if (argb != null) for (int p : argb) {
            int a = p >>> 24;
            r += (long) a * ((p >> 16) & 0xff); g += (long) a * ((p >> 8) & 0xff); b += (long) a * (p & 0xff); wsum += a;
        }
        if (wsum == 0) return 0xff202020;
        return 0xff000000 | (int) (r / wsum) << 16 | (int) (g / wsum) << 8 | (int) (b / wsum);
    }
}
