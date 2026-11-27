package dev.peanuts.display;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;

/** GitHub issue #811: a half turn between the two landscape sides (ROTATION_90 <-> ROTATION_270) changes no Configuration, so the game screen's
 *  onConfigurationChanged never came and the host kept mapping the phone's motion sensors with the old side: the gyro ran inverted. A display listener
 *  sees every turn; MotionRotation tells the host only a real change. Registered while the game screen is resumed. */
public final class RotationWatch implements DisplayManager.DisplayListener {
    private final Activity activity;
    private final Handler handler;
    private final MotionRotation rotation;
    private DisplayManager displays;
    private boolean on;

    public RotationWatch(Activity activity, Handler handler, MotionRotation.Sink sink) {
        this.activity = activity;
        this.handler = handler;
        this.rotation = new MotionRotation(sink);
    }

    /** The screen resumed: the rotation is told again, then followed. */
    public void resume() {
        rotation.forget();
        now();
        if (on) return;
        if (displays == null) displays = activity.getSystemService(DisplayManager.class);
        if (displays == null) return;
        displays.registerDisplayListener(this, handler);
        on = true;
    }

    /** The screen paused or went: no more display events. */
    public void pause() {
        if (!on) return;
        displays.unregisterDisplayListener(this);
        on = false;
    }

    /** The rotation now (a configuration change, the game's start). */
    public void now() {
        Display d = activity.getDisplay();
        if (d != null) rotation.set(d.getRotation());
    }

    @Override public void onDisplayChanged(int displayId) {
        Display d = activity.getDisplay();
        if (d != null && d.getDisplayId() == displayId) rotation.set(d.getRotation());
    }
    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayRemoved(int displayId) { }
}
