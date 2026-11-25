package dev.peanuts;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * The companion page (CompanionPage) on the built-in, default display while the game runs on another one (Swap screens, dev.peanuts.Screens, or the app
 * started there; #131). While the game is on a TV (Play on the TV, #358) and no companion takes this screen, the panel is only black (Companion.cover).
 * Android takes no Presentation on the default display, so the page gets an activity of its own there, in the game's process. Companion starts and finishes it.
 * <p>
 * Two windows (#183):
 * <ul>
 * <li>The activity's own window takes the display's focus but no touch: black, under the page. Android lets only a window that can take the focus hide that
 *     display's status and navigation bars (DisplayPolicy.updateSystemBarAttributes), so this one hides them, as a game does: a swipe from the edge shows them
 *     for a moment. It also takes Back from that display's navigation bar, the game's Back here (ScreenKeys). With no focusable window, Android held every
 *     key for 5 s waiting for one, then reported the app as not responding ("does not have a focused window"), and dropped the display's keys after that.</li>
 * <li>The page is a panel above it (a sub-window) that never takes the focus. Touches arrive there, and a tap does not move the focus to this display, so the
 *     controller stays with the game.</li>
 * </ul>
 * A phone without per-display focus (Companion.perDisplayFocus; the AYN Thor has it) keeps a focused window only on its top display. There this display stays
 * on top (Companion.screenTouched), so Back from its navigation bar finds this window, and the controller's keys and sticks reach this window, which hands
 * them to the game.
 */
public final class CompanionScreen extends Activity {
    static CompanionScreen current;
    private CompanionPage page;
    private Dialog panel;
    private boolean started, running, resumed, top;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!Companion.screenWanted()) { finish(); return; }   // a stale start: the game ended or went away meanwhile
        current = this;
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        w.setDecorFitsSystemWindows(false);
        edgeToEdge(w);
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        setContentView(black);
        WindowInsetsController bars = w.getInsetsController();
        if (bars != null) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        }
        panel = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) Companion.screenTouched();   // this display back on top where only the top one has a focused window
                return super.dispatchTouchEvent(e);
            }
        };
        Window p = panel.getWindow();
        p.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);   // a sub-window: never the window that decides the bars, and over this one
        p.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        p.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        p.setWindowAnimations(0);
        p.setDecorFitsSystemWindows(false);
        edgeToEdge(p);
        panel.setCancelable(false);
        content(getIntent());
        w.getDecorView().post(this::showPanel);   // a sub-window needs this window's token: posted, it runs once the window is attached
    }

    private static void edgeToEdge(Window w) {
        WindowManager.LayoutParams a = w.getAttributes();
        a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        w.setAttributes(a);
    }

    /** What the panel shows: the companion's page, or black while the game is on the TV (Companion.cover) until a companion takes this screen */
    private void content(android.content.Intent i) {
        if (page != null || panel == null) return;   // the page stays once it is up
        Window p = panel.getWindow();
        if (Companion.screenPage()) {
            page = new CompanionPage(this, p, i.getStringExtra("name"));   // the page makes the panel NOT_FOCUSABLE
            panel.setContentView(page.create());
            if (started && panel.isShowing()) run(true);
            return;
        }
        p.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);   // as the page: a tap here never takes the controller from the game
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        black.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.setContentView(black);
    }

    /** singleInstance: Companion starts it again to switch from black to the companion's page */
    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        content(i);
    }

    private void showPanel() {
        if (panel == null || isFinishing() || isDestroyed()) return;
        try { panel.show(); } catch (RuntimeException e) { android.util.Log.w(Companion.TAG, "companion page not shown on the built-in display: " + e); return; }
        if (started) run(true);
    }

    private void run(boolean on) {
        if (page == null || on == running || on && (panel == null || !panel.isShowing())) return;
        running = on;
        if (on) page.start(); else page.stop();
    }

    @Override protected void onStart() { super.onStart(); started = true; run(true); }
    @Override protected void onResume() { super.onResume(); resumed = true; getWindow().getDecorView().post(Companion::refocusGame); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    /** The top resumed activity: its display is the top one, where keys and the controller go */
    @Override public void onTopResumedActivityChanged(boolean isTop) { super.onTopResumedActivityChanged(isTop); top = isTop; }
    @Override protected void onStop() { started = false; run(false); super.onStop(); }
    @Override protected void onDestroy() {
        run(false);
        if (panel != null) { try { panel.dismiss(); } catch (RuntimeException ignored) {} panel = null; }
        if (current == this) { current = null; Companion.screenGone(); }   // gone without close(): Home or Recents on this display (noHistory)
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        switch (ScreenKeys.route(e.getKeyCode(), e.getSource(), e.getAction(), e.getFlags())) {
            case ScreenKeys.GAME: if (Companion.gameKey(e)) return true; break;
            case ScreenKeys.BACK: Companion.gameBack(); return true;
            case ScreenKeys.EAT: return true;
            default: break;
        }
        return super.dispatchKeyEvent(e);
    }

    /** A controller's sticks and triggers (joystick motion goes to the focused window, as keys do): the game's */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (ScreenKeys.joystick(e.getSource()) && Companion.gameMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // As GameActivity's window: joystick motion unbatched, each event handed on as it arrives instead of at this window's next frame
        if (hasFocus) getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
    }

    /** Never finishes this activity: Companion does (a key the game did not take can still end up here) */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { Companion.gameBack(); }

    /** Up and resumed: the front activity of its display (each display has a resumed one), not paused behind Recents, Home or another app */
    static boolean inFront() { CompanionScreen s = current; return s != null && s.resumed && !s.isFinishing(); }

    /** Its display is the top one (see onTopResumedActivityChanged) */
    static boolean onTop() { CompanionScreen s = current; return s != null && s.top; }

    static void close() {
        CompanionScreen s = current;
        current = null;
        if (s != null && !s.isFinishing()) s.finish();
    }
}
