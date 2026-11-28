// PEANUTS: ShortcutArt on the JVM (peanuts/tests/test_shortcut_art.py); prints "ok <n>" or throws
package dev.peanuts.launch;

import java.util.Arrays;

public final class ShortcutArtJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    public static void main(String[] a) {
        eq(288, ShortcutArt.shown(432));   // 72 of 108 dp at 4x
        // issue #258: the whole cover inside the shown part, as large as fits, centred
        eq(Arrays.toString(new int[] {72, 72, 360, 360}), Arrays.toString(ShortcutArt.fit(256, 256, 432)));   // a square icon fills the shown part
        eq(Arrays.toString(new int[] {108, 72, 324, 360}), Arrays.toString(ShortcutArt.fit(600, 800, 432)));   // a 3:4 box art: full height, narrower
        eq(Arrays.toString(new int[] {72, 135, 360, 297}), Arrays.toString(ShortcutArt.fit(1920, 1080, 432)));   // a 16:9 picture: full width
        eq(Arrays.toString(new int[] {72, 72, 360, 360}), Arrays.toString(ShortcutArt.fit(0, 10, 432)));   // no size: the shown square
        for (int[] wh : new int[][] {{1, 1000}, {1000, 1}, {333, 777}, {4096, 4096}}) {
            int[] r = ShortcutArt.fit(wh[0], wh[1], 432);
            eq(true, r[0] >= 72 && r[1] >= 72 && r[2] <= 360 && r[3] <= 360 && r[2] > r[0] && r[3] > r[1]);   // never outside the shown part
        }
        // the background: the cover's average colour, opaque; transparent pixels do not count
        eq(Integer.toHexString(0xff7f4020), Integer.toHexString(ShortcutArt.average(new int[] {0xffff8040, 0xff000000})));
        eq(Integer.toHexString(0xffff0000), Integer.toHexString(ShortcutArt.average(new int[] {0xffff0000, 0x0000ff00})));
        eq(Integer.toHexString(0xff202020), Integer.toHexString(ShortcutArt.average(new int[] {0x00ffffff})));
        eq(Integer.toHexString(0xff202020), Integer.toHexString(ShortcutArt.average(null)));
        System.out.println("ok " + n);
    }
}
