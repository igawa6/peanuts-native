package dev.peanuts.ui;

import android.app.Activity;
import android.app.Dialog;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peanuts Native: the screens black while a build runs. A build takes from minutes to over an hour and its card hardly changes, which marks an OLED panel:
 * "Screens off" in Home's header covers the device's own screens (the AYN Thor's two; not a TV, dev.peanuts.Screens) with a black window at the lowest brightness, which an OLED panel shows with
 * its pixels off. The device stays awake (an app cannot turn a screen off itself, and a sleeping device slows the build), a tap on either screen brings the
 * app back, and the cover goes by itself when no build is left ({@link Library}'s render).
 */
public final class ScreensOff {
    private static final List<Dialog> shown = new ArrayList<>();

    private ScreensOff() {}

    public static boolean on() { return !shown.isEmpty(); }

    /** Covers the activity's display and every other display with black; a tap on any of them uncovers all */
    public static void show(Activity a) {
        if (on()) return;
        Dialog own = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        cover(own, a, true);
        shown.add(own);
        DisplayManager dm = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
        int ownId = a.getDisplay() != null ? a.getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        Map<Integer, Display> all = new LinkedHashMap<>();   // the Thor lists its second screen only under the presentation category (Companion.secondDisplay)
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) all.put(d.getDisplayId(), d);
        for (Display d : dm.getDisplays()) all.put(d.getDisplayId(), d);
        for (Display d : all.values()) {
            if (d.getDisplayId() == ownId || !d.isValid() || !dev.peanuts.Screens.ownScreen(a, d)) continue;   // a TV or a cast is not ours to cover (#358)
            try {
                Presentation p = new Presentation(a, d, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                cover(p, a, false);
                shown.add(p);
            } catch (RuntimeException e) {   // a display that takes no window of ours (a private one): the others are covered
                android.util.Log.w("peanuts", "screens off: display " + d.getDisplayId() + ": " + e);
            }
        }
        dev.peanuts.ctl.Ctl.screen();   // the control interface's status file says whether the screens are off
    }

    public static void hide() {
        if (shown.isEmpty()) return;
        List<Dialog> l = new ArrayList<>(shown);
        shown.clear();
        dev.peanuts.ctl.Ctl.screen();
        for (Dialog d : l) try { d.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
    }

    private static void cover(Dialog d, Activity a, boolean hint) {
        FrameLayout root = new FrameLayout(d.getContext());
        root.setBackgroundColor(Color.BLACK);
        if (hint) {   // said once, then gone: nothing stays lit
            TextView t = new TextView(d.getContext());
            t.setText("The build goes on. Tap a screen to bring it back.");
            t.setTextColor(0xFF8A8A8A);
            t.setTextSize(16);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            new Handler(Looper.getMainLooper()).postDelayed(() -> t.animate().alpha(0f).setDuration(600).withEndAction(() -> t.setVisibility(View.GONE)), 3000);
        }
        root.setOnClickListener(v -> hide());
        root.setFocusable(true);
        root.setOnKeyListener((v, code, ev) -> { if (ev.getAction() == android.view.KeyEvent.ACTION_UP) hide(); return true; });   // a button of the pad does the same
        d.setContentView(root);
        d.setCancelable(true);
        d.setOnCancelListener(x -> hide());   // Back
        Window w = d.getWindow();
        if (w != null) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;   // the lowest the panel goes
            lp.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
        try { d.show(); root.requestFocus(); } catch (RuntimeException e) { android.util.Log.w("peanuts", "screens off: " + e); }
    }
}
