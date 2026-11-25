package dev.peanuts;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/** The host's Peanuts entry points (dm/peanuts/peanuts_jni.c, in the game's libnxlaunch, loaded by Native.load before the game starts). */
public final class Bridge {
    private Bridge() {}

    /** The newest snapshot as "key=value" lines (peanuts.h); empty before the host runs or without Peanuts in it. */
    public static native String snapshot();
    /** The companion page's size as w << 16 | h, 0 while there is no page. */
    public static native int frameSize();
    /** The newest page frame (RGBA8) into a direct buffer of at least w*h*4 bytes: true when a new one was written. */
    public static native boolean frame(ByteBuffer out, int w, int h);
    /** The next {@link #frame} writes the frame there is even when it was handed out before: for a page window made again, which has no pixels yet. */
    public static native void frameAgain();
    /** A touch on the page, in page pixels. */
    public static native void touch(int id, float x, float y, boolean down);
    /** The page's window is shown or gone: the runtime's presence; gone releases held fingers (dm/peanuts/peanuts.c nxhost_peanuts_present) */
    public static native void present(boolean shown);
    /** The page view's size in pixels: the runtime's panel. Touches are in these pixels from then on (nxhost_peanuts_panel) */
    public static native void panel(int w, int h);
    /** The device's battery for a companion that shows it (dm/peanuts/peanuts_services.c) */
    public static native void battery(int percent, boolean charging);
    /** 0 still deciding, 1 a companion package loaded, 2 none for this title, 3 found but refused (logcat "peanuts" says why). */
    public static native int rtState();
    /** One pending haptic pulse, packed (see peanuts_jni.c): kind(4 bits) &lt;&lt; 25 | respect_system &lt;&lt; 24 | amplitude(1..255) &lt;&lt; 16 | ms(1..65535); 0 = none to play. */
    public static native int haptic();
    /** The newest game frame times in ms into out, oldest first: how many were written (0 before the first frame; dm/peanuts/peanuts_jni.c) */
    public static native int frameTimes(float[] out);
    /** Where the frame-time bars sit in the overlay's top-right bitmap, in its pixels: the host paints a new bar there on every game frame (dm/peanuts/peanuts_graph.c); 0 size = none */
    public static native void graphRect(int x, int y, int w, int h);
    /** The columns the host splits that box into, 1..48 (dm/peanuts/peanuts_graph.c pn_graph_bars): the table's 48, the pill's fewer */
    public static native void graphBars(int n);
    /** On-pace bars light grey (the pill) or green (the table): peanuts_graph.c pn_graph_plain */
    public static native void graphPlain(boolean on);
    /** The corner of the screen for the overlay's stats block (slot 1), as dm/gfx/vk_android/vka_ovl.h: 0 top left, 1 top right, 2 bottom left, 3 bottom right (dm/peanuts/peanuts_graph.c) */
    public static native void overlayCorner(int corner);

    /** The console mode the game sees now: 1 docked, 0 handheld (dm/peanuts/peanuts_services.c, the host's oe::GetOperationMode); it can change while the game runs */
    public static native int opMode();
    /** opMode(), or -1 when the host does not have it (an older libnxhost), is not loaded, or the game has not shown a frame yet (asking earlier would fix the mode) */
    public static int opModeSafe() { try { return opMode(); } catch (UnsatisfiedLinkError e) { return -1; } }

    public static final int RT_PENDING = 0, RT_LOADED = 1, RT_NONE = 2, RT_FAILED = 3;

    /** Core::Mods::HapticKind (dm/peanuts/peanuts.h): what raised a pulse. */
    public static final int HAPTIC_TAP = 0, HAPTIC_WRITE = 1, HAPTIC_SELECT = 2, HAPTIC_DRAG = 3, HAPTIC_DROP = 4,
            HAPTIC_MARKER = 5, HAPTIC_REFUSED = 6, HAPTIC_HOLD = 7, HAPTIC_SWIPE = 8;

    /** rtState(), or RT_PENDING when the native library is not loaded yet. */
    public static int rtStateSafe() { try { return rtState(); } catch (UnsatisfiedLinkError e) { return RT_PENDING; } }

    /** One pending haptic pulse (amplitude, duration and what raised it), or null when there is none (or the call fails). */
    public static final class HapticPulse {
        public final float amplitude;   // 0..1
        public final int ms;
        public final boolean respectSystem;
        public final int kind;   // HAPTIC_* above
        HapticPulse(float amplitude, int ms, boolean respectSystem, int kind) {
            this.amplitude = amplitude; this.ms = ms; this.respectSystem = respectSystem; this.kind = kind;
        }
    }

    public static HapticPulse hapticPulse() {
        int packed;
        try { packed = haptic(); } catch (UnsatisfiedLinkError e) { return null; }
        if (packed == 0) return null;
        float amplitude = ((packed >> 16) & 0xff) / 255f;
        int ms = packed & 0xffff;
        boolean respectSystem = ((packed >> 24) & 1) != 0;
        int kind = (packed >> 25) & 0xf;
        return new HapticPulse(amplitude, ms, respectSystem, kind);
    }

    public static Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        String s;
        try { s = snapshot(); } catch (UnsatisfiedLinkError e) { return m; }
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    public static int pageWidth() { try { return frameSize() >>> 16; } catch (UnsatisfiedLinkError e) { return 0; } }
    public static int pageHeight() { try { return frameSize() & 0xffff; } catch (UnsatisfiedLinkError e) { return 0; } }
}
