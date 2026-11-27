package dev.peanuts.ui;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import dev.peanuts.PeanutsPrefs;

/**
 * The launcher's haptics: six words, used by the library, the game sheet and Settings > Library, and through {@link Feel} by the pages upstream draws (a game's page), and nothing outside them. A word is played with the view's
 * own haptic constant first (tuned by the device maker, follows the system's touch feedback setting, needs no permission) and with the vibrator's composition
 * primitives or a predefined effect when the view declines (USAGE_TOUCH, so the system's setting gates that path too). Every word has a minimum gap, a focus
 * tick is dropped right after a stronger word (one haptic per action) and while a direction is held (a buzz at the key repeat rate), and nothing is ever
 * played from a background thread. The in-game pad (TouchControls) and the second screen (CompanionPresentation) have their own.
 */
final class Haptics {
    private Haptics() {}

    /** TICK: the focus moved by one press. CLICK: an action was accepted. HEAVY: a weighty commit or a context menu (Play, Build, Y). EDGE: the focus cannot
     *  go further. DONE: a long job ended well. REJECT: the app refused, or a job failed */
    enum Word { TICK, CLICK, HEAVY, EDGE, DONE, REJECT }

    private static final long[] GAP_MS = {120, 60, 60, 250, 0, 300};   // by Word.ordinal()
    private static final long AFTER_STRONG_MS = 150;
    private static final long[] lastAt = new long[GAP_MS.length];
    private static long lastStrongAt, quietUntil;
    private static boolean off, systemOff, light;   // the app's setting; the system's touch feedback setting; no TICK or EDGE (battery saver, heat, a screen reader)
    private static boolean edgeArmed = true;   // an EDGE plays once, then not again until the focus has moved
    private static Vibrator vibrator;
    private static Boolean primitives;

    /** The settings read again (the app's row, the system's touch feedback, battery saver, heat, a screen reader): at every Home refresh and resume, not per call */
    @SuppressWarnings("deprecation")
    static void refresh(Context c) {
        off = PeanutsPrefs.hapticsOff(c);
        try { systemOff = Settings.System.getInt(c.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0; }
        catch (RuntimeException e) { systemOff = false; }
        light = strained(c);
        if (vibrator == null) {
            try {
                VibratorManager m = (VibratorManager) c.getApplicationContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = m == null ? null : m.getDefaultVibrator();
            } catch (RuntimeException e) { vibrator = null; }
        }
    }

    /** Battery saver, a thermal status of SEVERE or worse, or a screen reader that speaks the focus itself: the light words and the decorative motion go */
    static boolean strained(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            if (pm != null && (pm.isPowerSaveMode() || pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE)) return true;
            AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
            return am != null && am.isTouchExplorationEnabled();
        } catch (RuntimeException e) { return false; }
    }

    /** Whether the app's own setting leaves haptics on (the haptics the system plays for a view by itself, the long press, are switched with it) */
    static boolean enabled() { return !off; }

    /** No TICK and no EDGE for this long: the focus is about to be moved by code (after a rebuild, a filter step, a dialog opening) */
    static void quiet(long ms) { quietUntil = Math.max(quietUntil, SystemClock.uptimeMillis() + ms); }

    static void play(View v, Word w) { play(v, w, constant(w)); }

    /** A switch: CLICK when it turns on, TICK when it turns off (the platform's own toggle constants from API 34) */
    static void toggle(View v, boolean on) {
        Word w = on ? Word.CLICK : Word.TICK;
        play(v, w, Build.VERSION.SDK_INT >= 34 ? (on ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF) : constant(w));
    }

    private static int constant(Word w) {
        switch (w) {
            case TICK: return Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
            case CLICK: return HapticFeedbackConstants.VIRTUAL_KEY;
            case HEAVY: return HapticFeedbackConstants.LONG_PRESS;
            case EDGE: return HapticFeedbackConstants.TEXT_HANDLE_MOVE;
            case DONE: return HapticFeedbackConstants.CONFIRM;
            default: return HapticFeedbackConstants.REJECT;
        }
    }

    private static void play(View v, Word w, int viewConstant) {
        if (off || systemOff || v == null || Looper.myLooper() != Looper.getMainLooper()) return;
        boolean lightWord = w == Word.TICK || w == Word.EDGE;
        long now = SystemClock.uptimeMillis();
        if (lightWord && (light || now < quietUntil || now - lastStrongAt < AFTER_STRONG_MS)) return;
        int i = w.ordinal();
        if (now - lastAt[i] < GAP_MS[i]) return;
        lastAt[i] = now;
        if (!lightWord) lastStrongAt = now;
        // never FLAG_IGNORE_GLOBAL_SETTING: the user's system setting wins
        if (v.isHapticFeedbackEnabled() && v.performHapticFeedback(viewConstant)) return;
        fallback(w);
    }

    /** The vibrator, when the view declined: composition primitives where the motor has them (the only path with a strength), else a predefined effect */
    private static void fallback(Word w) {
        Vibrator vib = vibrator;
        if (vib == null || !vib.hasVibrator()) return;
        try {
            if (primitives == null) primitives = vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE);
            VibrationEffect e;
            if (primitives && w == Word.TICK) e = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f).compose();
            else if (primitives && w == Word.CLICK) e = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f).compose();
            else if (primitives && w == Word.EDGE) e = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f).compose();
            else if (primitives && w == Word.DONE) e = VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 40).compose();
            else e = VibrationEffect.createPredefined(w == Word.HEAVY ? VibrationEffect.EFFECT_HEAVY_CLICK : w == Word.REJECT ? VibrationEffect.EFFECT_DOUBLE_CLICK
                    : w == Word.TICK || w == Word.EDGE ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK);
            if (Build.VERSION.SDK_INT >= 33) vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            else vib.vibrate(e);   // Android 12: VibrationAttributes is API 33; the vibrator's default usage
        } catch (RuntimeException ignored) { /* no effect on this motor: silence */ }
    }

    /**
     * A key on a focused view, before the focus moves; never consumes it. A D-pad press that will move the focus is a TICK, one with nothing further in that
     * direction an EDGE (once: on the first press or the first repeat that reaches the wall, then not until the focus has moved). A repeat (the direction is
     * held) plays nothing, and tells {@link Motion} so the focus scale snaps as well.
     */
    static void focusKey(View v, KeyEvent e) {
        int dir;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
            default: return;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) { Motion.held(false); return; }
        boolean repeat = e.getRepeatCount() > 0;
        Motion.held(repeat);
        View to = v.focusSearch(dir);
        if (to == null || to == v) {
            if (edgeArmed) { edgeArmed = false; play(v, Word.EDGE); }
            return;
        }
        edgeArmed = true;
        if (!repeat) play(v, Word.TICK);
    }

    /** {@link #focusKey} as a key listener, for views with no key handling of their own */
    static final View.OnKeyListener KEYS = (v, code, e) -> { focusKey(v, e); return false; };

    /** {@link #KEYS} on every focusable view under root (a sheet's rows, switches and buttons) */
    static void watch(View root) {
        if (root.isFocusable()) root.setOnKeyListener(KEYS);
        if (root instanceof ViewGroup) { ViewGroup g = (ViewGroup) root; for (int i = 0; i < g.getChildCount(); i++) watch(g.getChildAt(i)); }
    }

    /** A click listener that plays w, then runs l */
    static View.OnClickListener on(Word w, View.OnClickListener l) { return v -> { play(v, w); if (l != null) l.onClick(v); }; }
}
