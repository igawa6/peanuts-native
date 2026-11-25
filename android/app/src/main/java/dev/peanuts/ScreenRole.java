package dev.peanuts;

/**
 * What a display is to Peanuts (GitHub issue #358): one of the device's own screens, a TV or monitor, or one to leave alone.
 * <p>
 * Before, every display besides the default one counted as the second screen, so a TV that a phone mirrored to (Samsung Smart View, a Wi-Fi display) got
 * the launcher's bottom-screen page, and on the AYN Thor a TV on USB-C would have been taken for its Screen-2: Android lists both as presentation displays,
 * and the presentation list names external and Wi-Fi displays before built-in ones.
 * <ul>
 * <li>{@link #DEVICE}: a screen of the device: the built-in ones (the Thor's two), and a small screen on a cable (the Retroid Pocket 5's clip-on top screen).
 *     The bottom screen, the companion and Swap screens use these.</li>
 * <li>{@link #TV}: a TV or monitor, on a cable or wireless (Miracast, Smart View). Never the bottom screen; a game plays there when Settings say so.</li>
 * <li>{@link #NONE}: a private display (screen recording, an app's own) or another app's virtual display (a cast, a remote desktop): nothing of Peanuts goes
 *     there, and no game starts there.</li>
 * </ul>
 * Plain Java, so the rule is tested on the JVM (ScreenRoleJvmTest); Screens reads the facts from android.view.Display.
 */
final class ScreenRole {
    static final int DEVICE = 0, TV = 1, NONE = 2;

    /** android.view.Display.TYPE_* (hidden constants, the same since Android 4.2); UNKNOWN: the type could not be read */
    static final int UNKNOWN = 0, INTERNAL = 1, EXTERNAL = 2, WIFI = 3, OVERLAY = 4, VIRTUAL = 5;

    /** A screen on a cable up to this diagonal is a device's own (a clip-on screen), larger is a TV or monitor. Handhelds stop near 7 inches, monitors start near 13 */
    static final double CLIP_ON_MAX_INCHES = 9.0;

    private ScreenRole() {}

    /**
     * @param type          the display's type, UNKNOWN when it could not be read
     * @param isPrivate     Display.FLAG_PRIVATE
     * @param productInfo   Display.getDeviceProductInfo() is there: a physical display that tells its maker (EDID). Wi-Fi, overlay and virtual displays have none
     * @param inches        the diagonal from the display's size and dots per inch, 0 or less when not known (a monitor without a size in its EDID)
     * @param clipOnSetup   Swap screens is on: the player has a clip-on screen, so a screen on a cable is that one
     * @param emulatorScreen the Android emulator's extra display, which stands in for a second built-in screen in development
     */
    static int of(int type, boolean isPrivate, boolean productInfo, double inches, boolean clipOnSetup, boolean emulatorScreen) {
        if (isPrivate) return NONE;
        switch (type) {
            case INTERNAL: return DEVICE;
            case EXTERNAL: return byCable(inches, clipOnSetup);
            case WIFI: case OVERLAY: return TV;   // Miracast and Smart View; an overlay is a simulated one (Developer options)
            case VIRTUAL: return emulatorScreen ? DEVICE : NONE;
            default:   // the type could not be read: what a public reading tells
                if (!productInfo) return emulatorScreen ? DEVICE : NONE;   // a Wi-Fi TV or another app's display: not ours to fill
                return byCable(inches, clipOnSetup);
        }
    }

    /** A screen on a cable: with Swap screens on it is the clip-on screen whatever size it reports (a missing EDID size reads as Android's fallback density) */
    private static int byCable(double inches, boolean clipOnSetup) {
        if (clipOnSetup) return DEVICE;
        return inches > 0 && inches <= CLIP_ON_MAX_INCHES ? DEVICE : TV;
    }

    /** The diagonal in inches from the size in pixels and the dots per inch on each axis; 0 when the dots per inch are not real (below 20, a missing EDID size) */
    static double inches(int width, int height, float xdpi, float ydpi) {
        if (width <= 0 || height <= 0 || xdpi < 20 || ydpi < 20) return 0;
        return Math.hypot(width / (double) xdpi, height / (double) ydpi);
    }

    /** Lines of the game's picture on a TV or monitor at most: a Switch game's docked picture is 1080p, and a 4K TV gets it scaled up by the display hardware */
    static final int TV_LINES = 1080;

    /**
     * The game surface's size on a TV or monitor (TvPicture): at most {@link #TV_LINES} high, with the window's shape, so a 4K TV's window takes a 1920x1080
     * surface that the display hardware scales, instead of the GPU writing 3840x2160 every frame. Null: the surface follows the window (a device screen, or a
     * window no taller than that)
     */
    static int[] tvSurface(int width, int height, boolean tv) {
        if (!tv || width <= 0 || height <= TV_LINES) return null;
        int w = (int) Math.round(width * (double) TV_LINES / height);
        return new int[] {w + (w & 1), TV_LINES};   // even, as a video mode's width
    }

    static String name(int role) { return role == DEVICE ? "device screen" : role == TV ? "TV or monitor" : "not used"; }

    static String typeName(int type) {
        switch (type) {
            case INTERNAL: return "built-in";
            case EXTERNAL: return "cable";
            case WIFI: return "wireless";
            case OVERLAY: return "overlay";
            case VIRTUAL: return "virtual";
            default: return "unknown type";
        }
    }
}
