package dev.nxnative.android;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.InputDevice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A gamepad's own motion sensors (a DualSense's gyroscope and accelerometer) as the player's six-axis sensor: InputDevice.getSensorManager() of the pad (Android 12+; the
 * launcher's minSdk is 33), the samples to Native.padMotion (host: nxhost_input_motion, which takes the gyro's rest offset off and hands them to the six-axis sensor). Nothing
 * of the phone's own sensors is touched (the host's motion thread does that). A pad that gives no gyroscope (the kernel driver of its Bluetooth link does not expose it, or the
 * pad has none) is logged once and left alone: the phone's sensors stay player 1's until controller motion arrives. Sensors run only while the screen is resumed and the game
 * is not paused, as the phone's do (GameActivity). Registered at 200 Hz, the most Android gives an app without the high-sampling-rate permission.
 */
final class PadMotion {
    private static final int PERIOD_US = 5000;
    private final Map<Integer, Pad> pads = new HashMap<>();   // main thread only
    private volatile HandlerThread thread;
    private volatile Handler handler;   // read by the sensor thread too
    private boolean on;

    /** One pad: its sensors, and the last accelerometer sample (the gyroscope sample is sent with it) */
    private final class Pad implements SensorEventListener {
        final int id; final String name; final SensorManager sm; final Sensor acc, gyro;
        private final float[] a = new float[3]; private boolean haveA, dead;   // handler thread
        private volatile boolean registered;
        Pad(int id, String name, SensorManager sm, Sensor acc, Sensor gyro) { this.id = id; this.name = name; this.sm = sm; this.acc = acc; this.gyro = gyro; }
        @Override public void onSensorChanged(SensorEvent e) {
            if (dead) return;
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) { System.arraycopy(e.values, 0, a, 0, 3); haveA = true; }
            else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE && haveA && !Native.padMotion(id, a[0], a[1], a[2], e.values[0], e.values[1], e.values[2])) {
                dead = true;   // the host takes no controller motion: stop asking
                if (handler != null) handler.post(() -> unregister(this));
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int accuracy) {}
    }

    /** A gamepad with a player (GameActivity.padConnected) */
    void attach(int deviceId) {
        if (pads.containsKey(deviceId)) return;
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return;
        SensorManager sm = d.getSensorManager();
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), gyro = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (acc == null || gyro == null) {
            List<Sensor> all = sm == null ? null : sm.getSensorList(Sensor.TYPE_ALL);
            Native.note("padmotion: " + d.getName() + " has no " + (gyro == null ? "gyroscope" : "accelerometer") + " through Android (sensors: " + (all == null || all.isEmpty() ? "none" : all.size()) + ")");
            pads.put(deviceId, null);   // asked once
            return;
        }
        Pad p = new Pad(deviceId, d.getName(), sm, acc, gyro);
        pads.put(deviceId, p);
        Native.note("padmotion: " + p.name + ": gyroscope " + gyro.getName() + ", accelerometer " + acc.getName());
        if (on) register(p);
    }

    /** A gamepad is gone (GameActivity.onInputDeviceRemoved) */
    void detach(int deviceId) {
        Pad p = pads.remove(deviceId);
        if (p != null) unregister(p);
    }

    /** The screen is resumed and the game runs: the sensors on */
    void resume() {
        if (on) return;
        on = true;
        for (Pad p : pads.values()) if (p != null) register(p);
    }

    /** The screen pauses or the game is paused: the sensors off */
    void pause() {
        if (!on) return;
        on = false;
        for (Pad p : pads.values()) if (p != null) unregister(p);
    }

    /** The screen goes */
    void release() {
        pause(); pads.clear();
        if (thread != null) { thread.quitSafely(); thread = null; handler = null; }
    }

    private void register(Pad p) {
        if (p.registered) return;
        if (thread == null) { thread = new HandlerThread("pad motion"); thread.start(); handler = new Handler(thread.getLooper()); }
        boolean a = p.sm.registerListener(p, p.acc, PERIOD_US, 0, handler), g = p.sm.registerListener(p, p.gyro, PERIOD_US, 0, handler);
        p.registered = a || g;
        if (!a || !g) Native.note("padmotion: " + p.name + ": the sensors did not register (accelerometer " + a + ", gyroscope " + g + ")");
    }

    private void unregister(Pad p) {
        p.sm.unregisterListener(p);
        p.registered = false;
    }
}
