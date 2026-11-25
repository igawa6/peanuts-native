// PEANUTS: a test of the fork's own classes (which display is a screen of the device and which a TV, GitHub issue #358)
package dev.peanuts;

/** ScreenRole on the JVM (no argument; prints "ok" or throws) */
public final class ScreenRoleJvmTest {
    private static void check(int got, int want, String what) {
        if (got != want) throw new AssertionError(what + ": got " + ScreenRole.name(got) + ", want " + ScreenRole.name(want));
    }

    private static void near(double got, double want, String what) {
        if (Math.abs(got - want) > 0.05) throw new AssertionError(what + ": got " + got + ", want " + want);
    }

    private static void size(int[] got, int[] want, String what) {
        String g = got == null ? "window" : got[0] + "x" + got[1], w = want == null ? "window" : want[0] + "x" + want[1];
        if (!g.equals(w)) throw new AssertionError(what + ": got " + g + ", want " + w);
    }

    public static void main(String[] args) {
        final int D = ScreenRole.DEVICE, T = ScreenRole.TV, N = ScreenRole.NONE;
        // The AYN Thor's Screen-2: built in, whatever else it tells
        check(ScreenRole.of(ScreenRole.INTERNAL, false, true, 4.5, false, false), D, "Thor Screen-2");
        check(ScreenRole.of(ScreenRole.INTERNAL, false, false, 0, false, false), D, "a built-in screen without a maker or size");
        // A TV or monitor on USB-C or HDMI
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 55, false, false), T, "a 55 inch TV");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 13.8, false, false), T, "a monitor that reports no size (Android's 160 dpi fallback for 1080p)");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 0, false, false), T, "no size at all");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, false, 0, false, false), T, "no maker, no size");
        // A clip-on screen on a cable (the Retroid Pocket 5's Dual Screen Add-On)
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 3.9, false, false), D, "a small screen on a cable");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 9.0, false, false), D, "up to 9 inches");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 9.2, false, false), T, "above 9 inches");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 0, true, false), D, "Swap screens on: a cable screen of unknown size is the clip-on one");
        check(ScreenRole.of(ScreenRole.EXTERNAL, false, true, 10.3, true, false), D, "Swap screens on: also when the fallback density makes it look large");
        // Wireless, simulated and virtual displays
        check(ScreenRole.of(ScreenRole.WIFI, false, false, 6, false, false), T, "Smart View or Miracast (the report in #358)");
        check(ScreenRole.of(ScreenRole.OVERLAY, false, false, 6, false, false), T, "a simulated display (Developer options)");
        check(ScreenRole.of(ScreenRole.VIRTUAL, false, false, 0, false, false), N, "another app's virtual display: no game, no page there");
        check(ScreenRole.of(ScreenRole.VIRTUAL, false, false, 0, false, true), D, "the emulator's extra display stands in for Screen-2");
        check(ScreenRole.of(ScreenRole.VIRTUAL, true, false, 0, false, true), N, "private comes first");
        check(ScreenRole.of(ScreenRole.EXTERNAL, true, true, 55, false, false), N, "a private display (screen recording) is never used");
        check(ScreenRole.of(ScreenRole.INTERNAL, true, true, 4, false, false), N, "private, even built in");
        // The type not readable: the public facts decide
        check(ScreenRole.of(ScreenRole.UNKNOWN, false, false, 6, false, false), N, "no maker: a Wi-Fi TV or a cast, left alone");
        check(ScreenRole.of(ScreenRole.UNKNOWN, false, false, 0, false, true), D, "the emulator's extra display");
        check(ScreenRole.of(ScreenRole.UNKNOWN, false, true, 4.5, false, false), D, "a maker and a small size: a screen of the device");
        check(ScreenRole.of(ScreenRole.UNKNOWN, false, true, 27, false, false), T, "a maker and a large size: a monitor");
        // Sizes
        near(ScreenRole.inches(1920, 1080, 40, 40), 55.07, "1080p at 40 dpi");
        near(ScreenRole.inches(1240, 1080, 369, 369), 4.46, "the Thor's Screen-2");
        near(ScreenRole.inches(1920, 1080, 0, 0), 0, "no dots per inch");
        near(ScreenRole.inches(1920, 1080, -1, -1), 0, "a missing EDID size");
        near(ScreenRole.inches(0, 1080, 400, 400), 0, "no width");
        // The game surface on a TV or monitor
        size(ScreenRole.tvSurface(3840, 2160, true), new int[] {1920, 1080}, "a 4K TV");
        size(ScreenRole.tvSurface(2560, 1440, true), new int[] {1920, 1080}, "a 1440p monitor");
        size(ScreenRole.tvSurface(3440, 1440, true), new int[] {2580, 1080}, "an ultrawide monitor keeps its shape");
        size(ScreenRole.tvSurface(1920, 1080, true), null, "a 1080p TV: the window's size");
        size(ScreenRole.tvSurface(2560, 1080, true), null, "1080 lines already");
        size(ScreenRole.tvSurface(1280, 720, true), null, "a 720p TV");
        size(ScreenRole.tvSurface(3840, 2160, false), null, "a device screen: the window's size");
        size(ScreenRole.tvSurface(2161, 1081, true), new int[] {2160, 1080}, "even width");
        System.out.println("ok");
    }
}
