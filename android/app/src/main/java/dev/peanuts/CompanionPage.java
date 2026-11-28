package dev.peanuts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * The companion page: CompanionPresentation shows it on the second display, CompanionScreen on the built-in one when Swap screens moved the game to the other
 * (dev.peanuts.Screens). With a companion package loaded, the host's DSMod runtime rasterises the page and this shows it as a bitmap, scaled to
 * fit the display, and sends touches back in page pixels. Without one, the host's snapshot (frames, build, chains, scan) is shown as text: the development view.
 */
final class CompanionPage {
    private static final int POLL_MS = 33;        // frames: the page follows the game's rate
    private static final int TEXT_POLL_MS = 250;  // the text view
    private final String gameName;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poller = this::poll;
    private TextView values;
    private ImageView page;
    private SurfaceView auxSurface;
    private boolean dualSurface;
    private LinearLayout textRoot;
    private Bitmap bitmap;
    private ByteBuffer pixels;
    private int pw, ph;   // the page's size
    private int pollTick;

    private final Context context;
    private final Window window;

    CompanionPage(Context context, Window window, String gameName) {
        this.context = context;
        this.window = window;
        this.gameName = gameName == null ? "" : gameName;
    }

    private Context getContext() { return context; }
    private Window getWindow() { return window; }

    /** The window's flags and the page's views; the caller makes the returned view its content */
    View create() {
        // NOT_FOCUSABLE as Eden Duo's AuxPresentation does: a focusable second window takes the input focus when touched, and the game on the main
        // display then loses the controller (seen on the AYN Thor: after a tap on Screen-2 the game ignored its buttons). Touches still arrive.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        // The two panels are lit independently: Screen-2 follows the system brightness (as Eden Duo's), instead of sitting at full while the main screen dims
        applyBrightness(systemBrightness());
        brightnessObserver = new android.database.ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) { applyBrightness(systemBrightness()); }
        };
        try {
            getContext().getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver);
            getContext().getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessObserver);
        } catch (RuntimeException e) { android.util.Log.w("peanuts", "companion: cannot watch brightness: " + e); }
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);
        page = new ImageView(getContext());
        page.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.setVisibility(View.GONE);
        page.setOnTouchListener(this::onPageTouch);
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { panelSet = false; tellPanel(); });
        page.setHapticFeedbackEnabled(true);
        root.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        textRoot = new LinearLayout(getContext());
        textRoot.setOrientation(LinearLayout.VERTICAL);
        textRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        textRoot.setPadding(32, 48, 32, 32);
        textRoot.setBackgroundColor(Color.rgb(0x14, 0x12, 0x0c));
        textRoot.addView(text("Peanuts Native", 30, Color.rgb(0xff, 0xd9, 0x5a), Typeface.DEFAULT_BOLD));
        textRoot.addView(text(gameName, 22, Color.WHITE, Typeface.DEFAULT));
        values = text("", 18, Color.LTGRAY, Typeface.MONOSPACE);
        values.setGravity(Gravity.START);
        textRoot.addView(values);
        root.addView(textRoot, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        auxSurface = new SurfaceView(getContext());
        auxSurface.setVisibility(View.GONE);
        auxSurface.setOnTouchListener(this::onDualTouch);
        auxSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { try { Bridge.setAuxSurface(h.getSurface()); } catch (UnsatisfiedLinkError ignored) { } }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { try { Bridge.setAuxSurface(h.getSurface()); } catch (UnsatisfiedLinkError ignored) { } }
            @Override public void surfaceDestroyed(SurfaceHolder h) { try { Bridge.setAuxSurface(null); } catch (UnsatisfiedLinkError ignored) { } }
        });
        root.addView(auxSurface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    void start() {
        try { Bridge.present(true); } catch (UnsatisfiedLinkError ignored) { }
        ui.removeCallbacks(poller);
        poll();
    }
    void stop() {
        if (dualSurface) { try { Bridge.setAuxSurface(null); } catch (UnsatisfiedLinkError ignored) { } }
        try { Bridge.present(false); } catch (UnsatisfiedLinkError ignored) { }
        ui.removeCallbacks(poller);
        if (brightnessObserver != null) { try { getContext().getContentResolver().unregisterContentObserver(brightnessObserver); } catch (RuntimeException ignored) { } brightnessObserver = null; }
    }

    private android.database.ContentObserver brightnessObserver;

    /** The system brightness as a window brightness: none (follow) in automatic mode, else the manual level over the panel's own full scale */
    private float systemBrightness() {
        try {
            android.content.ContentResolver r = getContext().getContentResolver();
            boolean auto = android.provider.Settings.System.getInt(r, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            if (auto) return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            int raw = android.provider.Settings.System.getInt(r, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1);
            if (raw < 0) return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            return Math.max(0.01f, Math.min(1f, raw / (float) brightnessMax()));
        } catch (RuntimeException e) { return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; }
    }

    /** The panel's full brightness scale: not 255 everywhere (1023 and 2047 are common), so assuming 255 would pin Screen-2 at maximum */
    private static int brightnessMax() {
        try {
            android.content.res.Resources s = android.content.res.Resources.getSystem();
            int id = s.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            return id != 0 ? Math.max(1, s.getInteger(id)) : 255;
        } catch (RuntimeException e) { return 255; }
    }

    private void applyBrightness(float value) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        if (p.screenBrightness != value) { p.screenBrightness = value; getWindow().setAttributes(p); }
    }

    private boolean onDualTouch(View v, MotionEvent e) {
        int vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0) return false;
        // Active 4:3 picture rect centered inside the SurfaceView (matches vka_fit_rect(ew, eh, 1440, 1080))
        float rw = vw, rh = vh;
        if (vw * 3 > vh * 4) rw = vh * 4.0f / 3.0f; else rh = vw * 3.0f / 4.0f;
        float ox = (vw - rw) * 0.5f, oy = (vh - rh) * 0.5f;
        int action = e.getActionMasked();
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL;
        if (!down && !up) return true;
        int idx = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (idx >= 0 && i != idx) continue;
            float u = (e.getX(i) - ox) / rw, vv = (e.getY(i) - oy) / rh;
            if (down && (u < 0f || u >= 1f || vv < 0f || vv >= 1f)) continue;
            try { Bridge.dualTouch(e.getPointerId(i), Math.max(0f, Math.min(0.9999f, u)), Math.max(0f, Math.min(0.9999f, vv)), down); }
            catch (UnsatisfiedLinkError ignored) { }
        }
        return true;
    }

    private void poll() {
        boolean wantDual = false;
        try { wantDual = Bridge.dualWanted(); } catch (UnsatisfiedLinkError ignored) { }
        if (wantDual) {
            if (!dualSurface) {
                dualSurface = true;
                page.setVisibility(View.GONE);
                textRoot.setVisibility(View.GONE);
                auxSurface.setVisibility(View.VISIBLE);
                android.util.Log.i("peanuts", "page: switched to direct Vulkan aux SurfaceView (PN_DUAL_RTT=1)");
            }
            ui.postDelayed(poller, TEXT_POLL_MS);
            return;
        } else if (dualSurface) {
            dualSurface = false;
            try { Bridge.setAuxSurface(null); } catch (UnsatisfiedLinkError ignored) { }
            auxSurface.setVisibility(View.GONE);
            textRoot.setVisibility(View.VISIBLE);
        }
        tellPanel(); tellBattery();
        int w = Bridge.pageWidth(), h = Bridge.pageHeight();
        if (w > 0 && h > 0) {
            if (w != pw || h != ph) {
                pw = w; ph = h;
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                page.setImageBitmap(bitmap);
                // This window's bitmap is empty: ask for the frame there is. Without it a window made again (the game back from the background, the display
                // attached again) stayed black until the companion drew something new, since a frame is handed out once (seen on the AYN Thor, 2026-11-15)
                try { Bridge.frameAgain(); } catch (UnsatisfiedLinkError ignored) { }
            }
            boolean fresh;
            try { fresh = Bridge.frame(pixels, w, h); } catch (UnsatisfiedLinkError e) { fresh = false; }
            if (fresh) { pixels.rewind(); bitmap.copyPixelsFromBuffer(pixels); page.invalidate(); }
            if (page.getVisibility() != View.VISIBLE) { page.setVisibility(View.VISIBLE); textRoot.setVisibility(View.GONE); }
            pollHaptic();
            ui.postDelayed(poller, POLL_MS);
            return;
        }
        if (++pollTick % (TEXT_POLL_MS / POLL_MS) == 0 || values.length() == 0) {
            Map<String, String> v = Bridge.values();
            StringBuilder sb = new StringBuilder();
            if (v.isEmpty()) sb.append("waiting for the host…");
            for (Map.Entry<String, String> e : v.entrySet()) sb.append(String.format("%-12s %s\n", e.getKey(), e.getValue()));
            values.setText(sb.toString());
        }
        ui.postDelayed(poller, POLL_MS);
    }

    /** The runtime's panel is the page view: told once the native library is there (the game process loads it after this window is made) and again on a new size */
    private boolean panelSet;
    private void tellPanel() {
        if (panelSet || page.getWidth() <= 0 || page.getHeight() <= 0) return;
        try { Bridge.panel(page.getWidth(), page.getHeight()); panelSet = true; } catch (UnsatisfiedLinkError ignored) { }
    }

    /** A touch on the page view, in the view's pixels: the runtime knows the view's size (tellPanel) and maps them to its page itself */
    private boolean onPageTouch(View v, MotionEvent e) {
        tellPanel();
        if (!panelSet) return false;
        int action = e.getActionMasked();
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL;
        if (!down && !up) return true;
        int idx = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (idx >= 0 && i != idx) continue;
            try { Bridge.touch(e.getPointerId(i), e.getX(i), e.getY(i), down); } catch (UnsatisfiedLinkError ignored) { return false; }
        }
        return true;
    }

    /** The battery, every few seconds, for a companion that shows it */
    private long batteryAt;
    private void tellBattery() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - batteryAt < 5000) return;
        batteryAt = now;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) Bridge.battery(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging());
        } catch (UnsatisfiedLinkError | RuntimeException ignored) { }
    }

    // --- haptics: one pending pulse from the runtime per poll (Bridge.hapticPulse, dm/peanuts/peanuts.c rt_haptic), played the same way Eden Duo's
    // AuxScreenHost.performHaptic does on its second screen - the page view's own haptic feedback when the device offers one (follows the system's touch-
    // feedback setting and tuned effects), the raw vibrator with a predefined effect otherwise. ---

    private Vibrator vibrator;
    private Vibrator vibrator() {
        if (vibrator == null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibratorManager m = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    vibrator = m != null ? m.getDefaultVibrator() : null;
                } else {
                    vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                }
            } catch (RuntimeException ignored) {}
        }
        return vibrator;
    }

    // Cached, not re-read per pulse (up to 30/s): refreshed at most once a second, which is plenty for a setting the user flips rarely.
    private static final long HAPTICS_SETTING_REFRESH_MS = 1000;
    private boolean systemHapticsEnabledCache = true;
    private long systemHapticsEnabledAt;
    @SuppressWarnings("deprecation")
    private boolean systemHapticsEnabled() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - systemHapticsEnabledAt >= HAPTICS_SETTING_REFRESH_MS) {
            systemHapticsEnabledAt = now;
            try { systemHapticsEnabledCache = Settings.System.getInt(getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0; }
            catch (Exception e) { systemHapticsEnabledCache = true; }
        }
        return systemHapticsEnabledCache;
    }

    // At least this long between two vibrator fallback one-shots, independent of how often pollHaptic() is called (up to 30/s): the view path is cheap and
    // already de-duplicated by the OS, but a raw Vibrator call ignores whatever is still playing and would otherwise retrigger every poll during e.g. a drag.
    private static final long VIBRATOR_MIN_GAP_MS = 50;
    private long lastVibrateAt;

    /** The view-level constant for a kind (Bridge.HAPTIC_*), matching Eden Duo's own distinction between a plain tap, a held action and a refusal -
     * amplitude/ms never reach this path (the platform API takes only a named effect), so the kind is what makes them feel different. */
    private int viewConstantFor(int kind) {
        if (kind == Bridge.HAPTIC_HOLD) return HapticFeedbackConstants.LONG_PRESS;
        if (kind == Bridge.HAPTIC_REFUSED) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.VIRTUAL_KEY_RELEASE;
        }
        return HapticFeedbackConstants.VIRTUAL_KEY;
    }

    /** The vibrator-fallback predefined effect for a kind, used when the view declined (a window the system does not vibrate for on its own). */
    private int predefinedEffectFor(int kind, float amplitude) {
        if (kind == Bridge.HAPTIC_HOLD) return VibrationEffect.EFFECT_HEAVY_CLICK;
        if (kind == Bridge.HAPTIC_REFUSED) return VibrationEffect.EFFECT_DOUBLE_CLICK;
        return amplitude >= 0.4f ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK;
    }

    private void pollHaptic() {
        Bridge.HapticPulse pulse;
        try { pulse = Bridge.hapticPulse(); } catch (UnsatisfiedLinkError e) { return; }
        if (pulse == null) return;
        if (pulse.respectSystem && !systemHapticsEnabled()) return;
        int viewFlags = pulse.respectSystem ? 0 : HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING;
        if (page.isHapticFeedbackEnabled() && page.performHapticFeedback(viewConstantFor(pulse.kind), viewFlags)) {
            return;   // the view played it with the device's own tuned effect for this kind
        }
        vibrateFallback(pulse);
    }

    private void vibrateFallback(Bridge.HapticPulse pulse) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastVibrateAt < VIBRATOR_MIN_GAP_MS) return;   // a view-less window retriggering every poll (e.g. a held drag) would otherwise buzz continuously
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        int ms = Math.max(1, Math.min(200, pulse.ms));   // defence in depth: the runtime's own table never exceeds ~55 ms, but never trust it blindly here
        try {
            VibrationEffect effect;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(predefinedEffectFor(pulse.kind, pulse.amplitude));
            } else {
                effect = VibrationEffect.createOneShot(ms, Math.max(1, Math.min(255, Math.round(pulse.amplitude * 255))));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                v.vibrate(effect);
            }
            lastVibrateAt = now;
        } catch (RuntimeException ignored) {}
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER);
        t.setPadding(16, 8, 16, 8);
        return t;
    }
}
