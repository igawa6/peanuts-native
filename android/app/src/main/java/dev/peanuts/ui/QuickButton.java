package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

/**
 * The player's own button for the in-game quick menu (Settings › Controls › Controller › Quick menu button). On a device whose Home belongs to Android
 * (the AYN Thor: Home goes to the launcher and closes the game, Back opens the game menu) no button opens the quick menu at once. It is set up from its
 * row by pressing the button; the app's start asks nothing, and the game menu's Quick menu row names the button once one is set. A button games use (A,
 * the shoulders, the sticks' presses, the D-pad, + and −) gets a warning first: while it opens the quick menu, the game no longer gets it. Home stays the
 * game menu's (pressed) and the quick menu's (held). The game's process reads it at the start (dev.nxnative.android.InGamePanel.quickKey).
 */
public final class QuickButton {
    private QuickButton() {}

    /** The button's key code, 0 none */
    public static int key(Context c) { return PeanutsPrefs.quickKey(c); }

    /** The row's value: the button, or that none is set */
    public static String label(Context c) {
        int k = key(c);
        return k == 0 ? "Not set: hold Home (on the AYN Thor: Back) for the quick menu" : "Opens the quick menu in a game: " + name(k);
    }

    /** A button's name as players know it */
    public static String name(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R";
            case KeyEvent.KEYCODE_BUTTON_L2: return "ZL";
            case KeyEvent.KEYCODE_BUTTON_R2: return "ZR";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Left stick press";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Right stick press";
            case KeyEvent.KEYCODE_BUTTON_START: return "+ (Start)";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "− (Select)";
            case KeyEvent.KEYCODE_BUTTON_C: return "C";
            case KeyEvent.KEYCODE_BUTTON_Z: return "Z";
            case KeyEvent.KEYCODE_DPAD_UP: return "D-pad up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-pad down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-pad left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-pad right";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "D-pad centre";
            default:
                if (k >= KeyEvent.KEYCODE_BUTTON_1 && k <= KeyEvent.KEYCODE_BUTTON_16) return "Button " + (k - KeyEvent.KEYCODE_BUTTON_1 + 1);
                String s = KeyEvent.keyCodeToString(k).replace("KEYCODE_", "").replace("BUTTON_", "").replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                return s.isEmpty() ? "Button " + k : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    /** A button games use: while it opens the quick menu, the game no longer gets it */
    static boolean games(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL: case KeyEvent.KEYCODE_BUTTON_THUMBR: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }

    /** A button the in-game menus move with (the D-pad, A, B, X, Y, L, R, + and −): while a menu is open it keeps that job there */
    public static boolean moves(int k) {
        return games(k) && k != KeyEvent.KEYCODE_BUTTON_L2 && k != KeyEvent.KEYCODE_BUTTON_R2 && k != KeyEvent.KEYCODE_BUTTON_THUMBL && k != KeyEvent.KEYCODE_BUTTON_THUMBR;
    }

    /** Why a button cannot be it, null when it can */
    static String refused(int k) {
        if (k == KeyEvent.KEYCODE_BUTTON_MODE) return "Home opens the game menu already, and held the quick menu.";
        if (k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_APP_SWITCH || k == KeyEvent.KEYCODE_POWER) return "Android keeps that button for itself.";
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return "That button sets the volume.";
        return null;
    }

    /**
     * A key of a controller, not of a keyboard or the screen: a gamepad's button (Android gives those the gamepad's source), or any key of a real device
     * that is a gamepad (its Back, Home and the like have the keyboard's source alone)
     */
    public static boolean fromController(KeyEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        InputDevice d = e.getDevice();
        return d != null && !d.isVirtual() && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

    /** The settings row: set one up, or with one set, change or remove it; done refreshes the row */
    public static void row(Activity a, Runnable done) {
        int k = key(a);
        if (k == 0) { setUp(a, done); return; }
        new AlertDialog.Builder(a)
                .setTitle("Quick menu button: " + name(k))
                .setItems(new CharSequence[] {"Change the button", "Remove it"}, (d, w) -> {
                    if (w == 0) setUp(a, done);
                    else { PeanutsPrefs.quickKey(a, 0); if (done != null) done.run(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Waits for the button: the controller's next press is it (Back cancels); a button games use asks first */
    public static void setUp(Activity a, Runnable done) {
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Press a button")
                .setMessage("Press the button on your controller that should open the quick menu in a game. Back cancels.")
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnKeyListener((dlg, k, e) -> {
            if (k == KeyEvent.KEYCODE_BACK || !fromController(e)) return false;   // Back cancels; a keyboard's keys and the screen's work the dialog
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
            String no = refused(k);
            if (no != null) { d.setMessage(no + " Press another button, or Back to cancel."); return true; }
            d.dismiss();
            if (games(k)) warn(a, k, done); else take(a, k, done);
            return true;
        });
        d.show();
    }

    /** A button games use: taken only when the player says so */
    private static void warn(Activity a, int k, Runnable done) {
        new AlertDialog.Builder(a)
                .setTitle(name(k) + " is a button games use")
                .setMessage("While it opens the quick menu, the game no longer gets this button: whatever the game does with it stops working. "
                        + "A button games leave free, such as an extra button on the back of the controller, is better.")
                .setPositiveButton("Use it anyway", (x, w) -> take(a, k, done))
                .setNegativeButton("Pick another", (x, w) -> setUp(a, done))
                .show();
    }

    private static void take(Activity a, int k, Runnable done) {
        PeanutsPrefs.quickKey(a, k);
        Toast.makeText(a, name(k) + " now opens the quick menu in a game", Toast.LENGTH_SHORT).show();
        if (done != null) done.run();
    }
}
