package dev.peanuts;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/**
 * Settings › Library › Swap screens (GitHub issue #131). Some dual-screen setups attach the top screen over USB-C, so Android lists the built-in bottom
 * screen as its default display and the clip-on top one as an external display (the Retroid Pocket 5 with its Dual Screen Add-On). With the setting on,
 * a game starts on the external display and its companion takes the built-in one (Companion.secondDisplay). Off, nothing changes.
 */
public final class Screens {
    private Screens() {}

    /**
     * Start on the external display when requested and allowed. If it disappears or Android
     * rejects the launch, retry on the launcher's display with the original intent.
     */
    public static void startGame(Activity launcher, Intent intent) {
        Display d = PeanutsPrefs.swapScreens(launcher) ? external(launcher) : null;
        if (d != null) {
            Intent external = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ActivityManager manager = launcher.getSystemService(ActivityManager.class);
                int id = d.getDisplayId();
                if (manager != null && manager.isActivityStartAllowedOnDisplay(launcher, id, external)) {
                    launcher.startActivity(external, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());
                    return;
                }
            } catch (SecurityException | IllegalArgumentException e) {
                Log.w(Companion.TAG, "Swap screens: external display refused the game", e);
            }
        }
        launcher.startActivity(intent);
    }

    /** The first valid display besides the default one, as the companion finds it (presentation displays first) */
    static Display external(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return null;
            for (Display d : Companion.displays(dm)) if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.isValid()) return d;
        } catch (RuntimeException ignored) {}
        return null;
    }
}
