package dev.peanuts;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which display is which, and where a game starts.
 * <p>
 * Every display is one of the device's own screens, a TV or monitor, or one to leave alone (ScreenRole, GitHub issue #358). The bottom screen, the companion
 * and Swap screens only ever use the device's own screens; a TV never gets Peanuts' pages.
 * <p>
 * Where a game starts: on a TV or monitor when one is connected and Settings › TV and monitors says Play on the TV (by default the system decides what a TV
 * shows: Android mirrors the screen, a handheld's firmware may turn its own screens off); else, with Settings › Screens ›
 * Swap screens (GitHub issue #131) on, on the other screen of the device (the Retroid Pocket 5's clip-on top screen, which Android lists as an external display
 * while its own screen is the default one), and its companion takes the built-in one (Companion.secondDisplay). Otherwise where the launcher is.
 */
public final class Screens {
    /** The game's intent: Play on the TV started it on a TV or monitor (Companion makes the screen it left black) */
    static final String TV = "peanuts_tv";

    private Screens() {}

    /**
     * Start on the TV, or on the other screen with Swap screens, when there is one and Android allows it. If it disappears or Android
     * rejects the launch, retry on the launcher's display with the original intent. A launcher that is on a TV itself (Samsung DeX) starts the game there.
     */
    public static void startGame(Activity launcher, Intent intent) {
        if (dev.peanuts.users.WhoPlaysActivity.ask(launcher, intent)) return;   // Who's playing? first, with two or more users (Settings › Users); it starts the game
        int own = displayOf(launcher);
        Display tv = PeanutsPrefs.tvPlay(launcher) && role(launcher, launcher.getDisplay()) != ScreenRole.TV ? tv(launcher, own) : null;
        if (tv != null && startOn(launcher, new Intent(intent).putExtra(TV, true), tv.getDisplayId())) {
            Log.i(Companion.TAG, "the game starts on the TV or monitor: " + describe(launcher, tv));
            GameReturn.startedOn(launcher, tv.getDisplayId());
            return;
        }
        Display d = PeanutsPrefs.swapScreens(launcher) ? otherScreen(launcher) : null;
        if (d != null && startOn(launcher, intent, d.getDisplayId())) {
            GameReturn.startedOn(launcher, d.getDisplayId());
            return;
        }
        GameReturn.startedOn(launcher, Display.INVALID_DISPLAY);
        launcher.startActivity(intent);
    }

    /**
     * Starts intent in a task of its own on display id, when that display is still there and Android allows the start on it. False: nothing was started,
     * and the caller starts it as before. GameReturn takes this way too, so the app's icon brings a game back to the display it was started on.
     */
    static boolean startOn(Activity launcher, Intent intent, int id) {
        Intent there = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            DisplayManager dm = launcher.getSystemService(DisplayManager.class);
            Display d = dm == null || id == Display.INVALID_DISPLAY ? null : dm.getDisplay(id);
            ActivityManager manager = launcher.getSystemService(ActivityManager.class);
            if (d != null && d.isValid() && manager != null && manager.isActivityStartAllowedOnDisplay(launcher, id, there)) {
                launcher.startActivity(there, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());
                return true;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(Companion.TAG, "display " + id + " refused the game", e);
        }
        return false;
    }

    /** Swap screens: the first of the device's own screens besides the default one */
    static Display otherScreen(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        return dm == null ? null : deviceScreen(c, dm, Display.DEFAULT_DISPLAY);
    }

    // ---- Which display is which ----

    /** Every display Android lists: the presentation ones first (the AYN Thor lists its Screen-2 only there), then the plain list (an emulator's extra one only there) */
    static List<Display> all(DisplayManager dm) {
        Map<Integer, Display> all = new LinkedHashMap<>();
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) all.put(d.getDisplayId(), d);
        for (Display d : dm.getDisplays()) all.put(d.getDisplayId(), d);
        return new ArrayList<>(all.values());
    }

    /** The first valid screen of the device besides the default display and {@code not} (the AYN Thor's Screen-2); null when there is none */
    public static Display deviceScreen(Context c, DisplayManager dm, int not) {
        for (Display d : all(dm)) {
            int id = d.getDisplayId();
            if (id != Display.DEFAULT_DISPLAY && id != not && d.isValid() && role(c, d) == ScreenRole.DEVICE) return d;
        }
        return null;
    }

    /** The first valid TV or monitor besides display {@code not}; null when there is none */
    static Display tv(Context c, int not) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return null;
            for (Display d : all(dm)) if (d.getDisplayId() != not && d.isValid() && role(c, d) == ScreenRole.TV) return d;
        } catch (RuntimeException ignored) {}
        return null;
    }

    /** Whether the activity is on a TV or monitor (a game Play on the TV started there) */
    public static boolean onTv(Activity a) { return role(a, a.getDisplay()) == ScreenRole.TV; }

    /** Whether the display is one of the device's own screens */
    public static boolean ownScreen(Context c, Display d) { return role(c, d) == ScreenRole.DEVICE; }

    /** ScreenRole of a display; the default display is always the device's own */
    static int role(Context c, Display d) {
        if (d == null) return ScreenRole.NONE;
        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) return ScreenRole.DEVICE;
        int type = type(d);
        return ScreenRole.of(type, (d.getFlags() & Display.FLAG_PRIVATE) != 0, productInfo(d) != null, inches(d),
                PeanutsPrefs.swapScreens(c), type == ScreenRole.VIRTUAL || type == ScreenRole.UNKNOWN ? emulatorScreen(d) : false);
    }

    private static Method getType;
    private static boolean typeLooked;

    /**
     * Display.getType(), which Android keeps out of the public API but has had since 4.2 (and lets apps call). Without it the role comes from what the public
     * API tells (the display's maker and its size), which cannot tell a Wi-Fi TV from another app's virtual display: both are left alone then
     */
    static int type(Display d) {
        try {
            if (!typeLooked) { typeLooked = true; getType = Display.class.getMethod("getType"); }
            if (getType != null) {
                int t = (Integer) getType.invoke(d);
                return t >= ScreenRole.INTERNAL && t <= ScreenRole.VIRTUAL ? t : ScreenRole.UNKNOWN;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (getType != null) Log.w(Companion.TAG, "display type not readable: " + e);
            getType = null;
        }
        return ScreenRole.UNKNOWN;
    }

    private static DeviceProductInfo productInfo(Display d) {
        try { return d.getDeviceProductInfo(); } catch (RuntimeException e) { return null; }
    }

    @SuppressWarnings("deprecation")
    private static double inches(Display d) {
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return ScreenRole.inches(m.widthPixels, m.heightPixels, m.xdpi, m.ydpi);
    }

    /** The Android emulator's extra display (Extended controls › Displays), the second built-in screen of the development setup */
    private static boolean emulatorScreen(Display d) {
        String n = d.getName();
        return n != null && n.startsWith("Emulator") && (Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish") || Build.FINGERPRINT.contains("generic"));
    }

    /** One line for the log: what Android tells about a display and what Peanuts makes of it (a report of a TV problem needs it) */
    @SuppressWarnings("deprecation")
    static String describe(Context c, Display d) {
        if (d == null) return "none";
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        DeviceProductInfo p = productInfo(d);
        Display.Mode mode = d.getMode();
        return "display " + d.getDisplayId() + " \"" + d.getName() + "\" " + ScreenRole.typeName(type(d)) + ", " + m.widthPixels + "x" + m.heightPixels
                + " at " + Math.round(mode.getRefreshRate()) + " Hz, " + String.format(java.util.Locale.ROOT, "%.1f in", inches(d))
                + (p != null ? ", maker " + p.getManufacturerPnpId() + " \"" + p.getName() + "\"" : ", no maker")
                + ((d.getFlags() & Display.FLAG_PRESENTATION) != 0 ? ", presentation" : "") + ((d.getFlags() & Display.FLAG_PRIVATE) != 0 ? ", private" : "")
                + ": " + ScreenRole.name(role(c, d));
    }

    /** Every display in the log, when the displays change (Companion, BottomScreen) */
    public static void log(Context c, String why) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            StringBuilder b = new StringBuilder("displays (").append(why).append("):");
            for (Display d : all(dm)) b.append("\n  ").append(describe(c, d));
            Log.i(Companion.TAG, b.toString());
        } catch (RuntimeException e) { Log.w(Companion.TAG, "displays not listed: " + e); }
    }

    private static int displayOf(Activity a) { Display d = a.getDisplay(); return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY; }
}
