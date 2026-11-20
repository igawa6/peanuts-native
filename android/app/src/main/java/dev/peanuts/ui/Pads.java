package dev.peanuts.ui;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

/** Whether a controller is connected, by the game's own rule (GameActivity.gamepadLike), and a call when that changes */
final class Pads {
    private static InputManager.InputDeviceListener listener;
    private static Runnable onChange;

    private Pads() {}

    static boolean connected(Context c) {
        InputManager im = c.getSystemService(InputManager.class);
        if (im == null) return true;   // unknown: the face buttons, as before
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK))) return true;
        }
        return false;
    }

    /** Once a process; change runs on the main thread when a device comes, goes or changes */
    static void watch(Context app, Runnable change) {
        onChange = change;
        if (listener != null) return;
        InputManager im = app.getSystemService(InputManager.class);
        if (im == null) return;
        listener = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id) { if (onChange != null) onChange.run(); }
            @Override public void onInputDeviceRemoved(int id) { if (onChange != null) onChange.run(); }
            @Override public void onInputDeviceChanged(int id) { if (onChange != null) onChange.run(); }
        };
        im.registerInputDeviceListener(listener, new Handler(Looper.getMainLooper()));
    }
}
