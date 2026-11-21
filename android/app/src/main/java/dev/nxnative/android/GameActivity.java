package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
// PEANUTS: (the startup shader progress has its own view now, dev.peanuts.ui.PrepareCover: no ProgressBar here)
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs one game in this process (":game"): sets up its folders and environment, starts nxhost_main, and shows a SurfaceView (handed to the host's Vulkan replay with
 * nxhost_set_window) with the newest log lines and the on-screen controls (TouchControls) over it. Gamepad keys and axes, touches, the Activity lifecycle, audio focus and audio route changes go to the host through
 * Native (libnxlaunch.so → libnxhost.so's C interface); all of it reaches the host instance of this process. Leaving ends the process: the host starts once per process.
 */
public final class GameActivity extends Activity implements SurfaceHolder.Callback, InputManager.InputDeviceListener {
    private static final int KEEP_LOGS = 10;

    private TextView overlay;
    private TextView tag, fps;   // the build tag (top left) and the frame rate with the other items (top right, Overlay) as views over the game, shown only while the host's present does not draw them (fpsTick)
    private Overlay stats;
    private final dev.peanuts.display.RunningDisplayMode runningDisplayMode = new dev.peanuts.display.RunningDisplayMode();   // PEANUTS: running-cap platform mitigation
    private Overlay.Block tagBlock, fpsBlock;   // the build tag and the items as pixels for the host's present (fpsTick)
    private String fpsShown;   // the text the top-right view has (Overlay.key): set again only when it changes
    private static Boolean overlayEnv;   // DM_OVERLAY in env.txt: overrides the setting for this run (static: a screen created again keeps it)
    private TouchControls controls;
    private SidePanel panel;
    private final HashSet<Integer> pads = new HashSet<>();   // the gamepads with a player: the on-screen controls hide while there is one
    private PadOrder order;   // which gamepad gets a player, and which is player 1 (hostSetup)
    // The in-game panel with a controller (PadPanel): the open / close combination; the pad keys the host holds down (released when the panel opens: releaseGameInput),
    // and the keys the panel took a press of (their release is the panel's too, also after it closed)
    private PadPanel.Combo combo = new PadPanel.Combo(PadPanel.OPEN_BOTH);
    private final HashMap<Integer, HashSet<Integer>> gameKeys = new HashMap<>();
    private final HashSet<Integer> panelKeys = new HashSet<>();
    private final Runnable comboHeld = () -> { if (panel != null && combo.fire(android.os.SystemClock.uptimeMillis())) panel.padToggle(); };
    private File log;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PadMotion padMotion;   // a gamepad's own gyroscope and accelerometer as its player's six-axis sensor (PadMotion), the phone's own sensors stay the host's
    private volatile Rumble rumble;   // the game's vibration on the controller's or the phone's vibrator
    private boolean input, started;
    private InputManager inputManager;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final AudioFocus focusState = new AudioFocus();
    private boolean requestFocus() { boolean g = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED; focusState.requested(g); return g; }
    private volatile AudioDeviceCallback route;
    private boolean routeReady;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        fitOrientation();
        // PEANUTS: the game stays behind the lock screen (GitHub issue #198: after the lid closed it was drawn over the lock screen, which then had no way to the PIN
        // prompt). Only a control command asks for it (--ez show_when_locked true, as Ondevice's), for test runs on a phone that locks
        boolean onLock = getIntent().getBooleanExtra("show_when_locked", false);
        setShowWhenLocked(onLock);
        setTurnScreenOn(onLock);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // This game's own host (libnxhost_<title ID>.so, with its profile compiled in): a game this APK has no profile for gets a sentence, not a crash
        String id = String.valueOf(getIntent().getStringExtra("title_id"));
        // The run's exit record and the uncaught-exception handler (ExitWatch): first, so even a failure below reaches the launcher's notice. A new record only for a new run
        ExitWatch.game(this, id, getIntent().getStringExtra("name"), saved == null && !hostRunning);
        try {
            Native.load(id);
        } catch (IllegalStateException e) {
            setContentView(new FrameLayout(this));
            fail(e.getMessage());
            return;
        }
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface);
        // The log's last lines (tail()): bottom left, one block only as high as its text, so it stays clear of the build tag in the corner above
        overlay = overlayText(Gravity.BOTTOM | Gravity.START, 1f);
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        // The on-screen controls take every touch and pass it to the host, which tells controls from touchscreen fingers
        controls = new TouchControls(this, Native.hasInput());
        root.addView(controls, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // The build tag: an overlay item, on by default (fpsTick follows the switches); bold, flush in the top-left corner
        tag = overlayText(Gravity.TOP | Gravity.START, 1f);
        tag.setText(Prefs.buildTag(this));
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setVisibility(overlayOn() && Prefs.overlayItem(this, "tag") ? View.VISIBLE : View.GONE);
        root.addView(tag, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        // The log block may grow at most up to the tag's bottom edge (its top margin), so a long log never runs under the tag
        tag.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
            int top = b + ((FrameLayout.LayoutParams) tag.getLayoutParams()).topMargin;
            if (lp.topMargin != top) { lp.topMargin = top; overlay.post(overlay::requestLayout); }
        });
        fps = overlayText(Gravity.TOP | Gravity.END, 0.85f);
        root.addView(fps, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        // The same two blocks for the host's present, which draws them into the game's images (Overlay.Block): the views show only when it does not
        TextView tagPic = overlayText(Gravity.TOP | Gravity.START, 1f);
        tagPic.setTypeface(Typeface.DEFAULT_BOLD);
        tagBlock = new Overlay.Block(0, tagPic);
        fpsBlock = new Overlay.Block(1, overlayText(Gravity.TOP | Gravity.END, 0.85f));
        stats = new Overlay(this);
        panel = new SidePanel(this, new SidePanel.Rows() {
            @Override public void controls(LinearLayout box) { controls.controlRows(GameActivity.this, box); }
            @Override public void position(LinearLayout box) { controls.positionRows(GameActivity.this, box); }
        });
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        controls.openSettings = panel::open;
        combo = new PadPanel.Combo(Prefs.panelPad(this));   // Settings › Controller: in-game panel
        panel.padButtons(PadPanel.confirmBack(Prefs.padMap(this, id), Prefs.xboxButtons(this, id) == 2 ? 2 : 0));   // the game's own A and B, as the host maps them from this start
        panel.onOpen = this::releaseGameInput;
        // The layout editor needs the whole screen: the panel closes, and Paused (over the controls: its first tap would continue the game) hides while it is open;
        // the game stays paused
        controls.onEditStart = () -> { panel.close(); pausedView.setVisibility(View.GONE); };
        controls.onEditEnd = () -> { if (paused) pausedView.setVisibility(View.VISIBLE); };
        // The panel's handle: an invisible strip along the right edge, from a sixth to a half of the way down (over the HUD rather than the controls), that opens the panel
        // on a swipe in from the edge (a tap goes nowhere: a stray touch near the edge does not open it). Under gesture navigation the system's own Back swipe from that
        // edge opens it too (onBackPressed). The one-time hint (panelHint) says so; TalkBack reads it as a button
        float dp = getResources().getDisplayMetrics().density;
        View handle = new View(this);
        float[] downX = {-1};
        handle.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) downX[0] = e.getRawX();
            else if (a == MotionEvent.ACTION_MOVE && downX[0] >= 0 && downX[0] - e.getRawX() > 24 * dp) { downX[0] = -1; panel.open(); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) downX[0] = -1;
            return true;
        });
        handle.setContentDescription("Open the in-game panel");
        handle.setOnClickListener(v -> panel.open());   // TalkBack's double tap
        root.addView(handle, root.indexOfChild(panel), new FrameLayout.LayoutParams(Math.round(20 * dp), Math.round(140 * dp), Gravity.END | Gravity.TOP));
        // Auto with a gamepad connected, or Off: the handle goes with the controls (Back or a gamepad's Home button opens the panel)
        controls.onSwitch = v -> handle.setVisibility(v ? View.VISIBLE : View.GONE);
        controls.setMode(TouchControls.mode(this));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> handle.setTranslationY((b - t) * 0.3f - 70 * dp));
        // Paused (pause(): the in-game panel's Pause, or the screen coming back with pause-on-return on): the game is suspended until a tap, or A / Start on a gamepad.
        // Under the panel and its handle, so Stop stays reachable
        pausedView = new TextView(this);
        pausedView.setText("Paused\n\nTap to continue  (A or Start on a gamepad)");
        pausedView.setTextColor(Color.WHITE);
        pausedView.setTextSize(22);
        pausedView.setGravity(Gravity.CENTER);
        pausedView.setBackgroundColor(0x99000000);
        pausedView.setVisibility(View.GONE);
        pausedView.setOnClickListener(v -> unpause());
        root.addView(pausedView, root.indexOfChild(handle), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panelHint(root);
        dev.peanuts.build.ShaderCapture.cover(this, root);   // PEANUTS: internal shader preparation stays behind a progress screen
        root.setOnApplyWindowInsetsListener((v, in) -> { insetOverlay(in); return in; });   // again whenever the insets change (a turn, a fold)
        setContentView(root);
        immersive();
        if (!dev.peanuts.build.ShaderCapture.active(this)) {   // PEANUTS: preparation is not a play session
            dev.peanuts.Companion.start(this, getIntent().getStringExtra("name"));   // PEANUTS: the companion on the second display
            dev.peanuts.launch.RecentGames.touch(this, getIntent().getStringExtra("peanuts_list_id") != null ? getIntent().getStringExtra("peanuts_list_id") : id, getIntent().getStringExtra("name"));   // PEANUTS: last-3-played dynamic shortcuts (LAUNCHERS.md), by the title ID the list holds (an edition's start: MainActivity.start)
        }   // PEANUTS
        TextInput.screen(this);
        CtrlScreen.screen(this);   // the controller applet's join screen shows on this screen (again, when the game was waiting on it as the screen was recreated)
        UserPicker.screen(this);   // the user selector shows on this screen, as the input box   // the keyboard applet's input box shows on this screen (again, when the game was waiting on it as the screen was recreated)
        // The host starts once per process. A screen created again while it runs (Android recreated the activity: "Don't keep activities", a font or language
        // change while in the background) takes its window over; a screen restored into a new process (Android ended the old one while it was in the background)
        // has no game to show: the game was stopped, and the user starts it again
        if (hostRunning) attach();
        else if (saved == null) start();
        else {
            String why = ExitWatch.restoredCause(this);   // what Android said about the end, told here once (the launcher does not tell it again)
            fail("The game was stopped while it was in the background (Android ended its process)." + (why != null ? " " + why : "") + " Unsaved progress is lost; start it again from the launcher.");
        }
    }

    /** The overlay's text (build tag, stats, log lines): all one small size, 8 sp (at 11-12 sp they covered the touch buttons games put in the corners), white with a
     *  shadow on a faint dark pill with tight padding, so it reads over any scene; touches pass through to the controls */
    static final int OVERLAY_SP = 8;
    private TextView overlayText(int gravity, float alpha) {
        TextView t = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(2, 0, 0, Color.BLACK);
        t.setTextSize(OVERLAY_SP);
        t.setGravity(gravity);
        t.setAlpha(alpha);
        t.setPadding(Math.round(3 * dp), Math.round(1 * dp), Math.round(3 * dp), Math.round(1 * dp));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x80000000);
        bg.setCornerRadius(3 * dp);
        t.setBackground(bg);
        t.setClickable(false);
        t.setFocusable(false);
        return t;
    }

    /**
     * The overlay stays clear of the window's rounded corners and the display cutout (issue #105: the window is edge to edge, so a corner cut the top-right items in half).
     * From the window's insets, per corner, whatever the display: RoundedCorner's radius (null where the window does not reach a corner), and the cutout's safe insets
     * (OverlayInset). The views get it as margins, the blocks the host draws as transparent margin in their bitmaps (Overlay.Block.inset)
     */
    private void insetOverlay(android.view.WindowInsets in) {
        android.view.RoundedCorner tl = in.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT), tr = in.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT),
                bl = in.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT);
        android.view.DisplayCutout cut = in.getDisplayCutout();
        int cl = cut == null ? 0 : cut.getSafeInsetLeft(), ct = cut == null ? 0 : cut.getSafeInsetTop(), cr = cut == null ? 0 : cut.getSafeInsetRight();
        int[] a = OverlayInset.topLeft(tl == null ? 0 : tl.getRadius(), cl, ct), b = OverlayInset.topRight(tr == null ? 0 : tr.getRadius(), cr, ct);
        tagBlock.inset(a[0], a[1], 0);
        fpsBlock.inset(0, b[1], b[0]);
        margins(tag, a[0], a[1], 0);
        margins(fps, 0, b[1], b[0]);
        int[] c = OverlayInset.topLeft(bl == null ? 0 : bl.getRadius(), cl, 0);   // the log block, bottom left: sideways by the corner, up by the same as a top one down
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        if (lp.leftMargin != c[0] || lp.bottomMargin != c[1]) { lp.leftMargin = c[0]; lp.bottomMargin = c[1]; overlay.requestLayout(); }
    }
    private static void margins(View v, int l, int t, int r) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.leftMargin == l && lp.topMargin == t && lp.rightMargin == r) return;
        lp.leftMargin = l; lp.topMargin = t; lp.rightMargin = r;
        v.requestLayout();
    }

    // PEANUTS begin: show the cold pipeline build while the native host waits to start the game.
    private final Runnable prewarmPoll = this::prewarmTick;
    private dev.peanuts.ui.PrepareCover prewarmCover;   // PEANUTS: the home screen's background, the game's cover, a peanut cracking open, the count (dev.peanuts.ui.PrepareCover)

    private void prewarmTick() {
        ui.removeCallbacks(prewarmPoll);
        long progress = Native.prewarmProgress();
        dev.peanuts.Companion.preparing(progress != 0);   // PEANUTS: the second screen's start page stays up meanwhile
        if (progress == 0) {
            if (prewarmCover != null) {
                rootView.removeView(prewarmCover);
                prewarmCover = null;
            }
            return;
        }
        if (progress > 0) {
            int total = (int) (progress >>> 32), done = (int) progress;
            if (prewarmCover == null) {
                GpuDriver.Info drv = GpuDriver.selected(this, titleId());
                prewarmCover = new dev.peanuts.ui.PrepareCover(this, titleId(), getIntent().getStringExtra("name"), drv == null ? null : drv.label(),
                        Native::prewarmSkip, this::end);
                rootView.addView(prewarmCover, rootView.indexOfChild(pausedView), new FrameLayout.LayoutParams(-1, -1));
            }
            prewarmCover.set(done, total);
        }
        ui.postDelayed(prewarmPoll, 150);
    }
    // PEANUTS end

    private boolean overlayOn() { return overlayEnv != null ? overlayEnv : Prefs.overlay(this); }

    /** Thread placement (Native.pinThreads): every 5 s, for threads the game and the replay start later; stops when the phone reports no clocks */
    private final Runnable pinRun = this::pinTick;
    private void pinTick() {
        if (Native.pinThreads() < 0) return;
        ui.postDelayed(pinRun, 5000);
    }
    /** At the start and when a recreated screen attaches to the running game (attach: the old screen's loops were stopped); one loop, never two */
    private void pinStart() {
        ui.removeCallbacks(pinRun);
        if (Prefs.pinThreads(this, titleId())) ui.postDelayed(pinRun, 2000);
    }

    /**
     * The skipped-work notice (SkipNotice): every 5 s from 30 s after the start, ask the host whether the replay skipped a large share of the game's draws for causes of the
     * GPU driver (Native.skipStatus, DM_VK_SKIP_WARN); the host says so once, after the first loading seconds, and the notice shows once per run: small, at the bottom,
     * a tap dismisses it, it fades after 20 s. Stops after showing, or when this host cannot say (no such entry point: an empty answer every time is still polled, cheaply)
     */
    private static boolean skipShown;
    private FrameLayout rootView;
    private final Runnable skipRun = this::skipTick;
    private void skipTick() {
        if (skipShown || rootView == null) return;
        if (!stopped && !paused) {
            int pct = SkipNotice.percent(Native.skipStatus());
            if (pct > 0) { skipShown = true; skipShow(pct); return; }
        }
        ui.postDelayed(skipRun, 5000);
    }
    private void skipStart() {
        ui.removeCallbacks(skipRun);
        warmStart();   // PEANUTS: the "Preparing graphics" notice, started and stopped with this one
        if (!skipShown) ui.postDelayed(skipRun, 30000);
    }
    private void skipShow(int pct) {
        GpuDriver.Info drv = GpuDriver.selected(this, titleId());
        String st = Native.skipStatus();
        Native.note("skipped work: notice shown (" + pct + " % of draws; driver: " + (drv == null ? "system" : drv.label()) + ")");
        TextView h = new TextView(this);
        h.setText(SkipNotice.noMesh(st) ? SkipNotice.textNoMesh(pct) : SkipNotice.text(pct, drv == null ? null : drv.label()));
        h.setTextColor(Color.WHITE);
        h.setTextSize(13);
        float dp = getResources().getDisplayMetrics().density;
        int p = Math.round(10 * dp);
        h.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(10 * dp);
        h.setBackground(bg);
        h.setMaxWidth(Math.round(460 * dp));
        h.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(24 * dp);
        rootView.addView(h, panel != null ? rootView.indexOfChild(panel) : rootView.getChildCount(), lp);
        Runnable gone = () -> h.animate().alpha(0).setDuration(600).withEndAction(() -> rootView.removeView(h)).start();
        h.setOnClickListener(v -> gone.run());
        h.postDelayed(gone, 20000);
    }

    // PEANUTS begin: the "Preparing graphics" notice (dev.peanuts.ui.WarmNotice; vka_warmnote.h). Every second, ask the host how many pipelines its background threads
    // still make while draws are skipped for them (Native.warmStatus): shown, small, at the top, while that is above 0, with the count; gone when it is 0. A host without
    // the entry point (-1) is not asked again. Without it a first run looked broken: EA SPORTS FC 27's pitch was black for a minute with no word why on screen
    private TextView warmView;
    private final Runnable warmRun = this::warmTick;
    private void warmStart() { ui.removeCallbacks(warmRun); ui.postDelayed(warmRun, 1000); }
    private void warmTick() {
        if (rootView == null) return;
        int n = stopped || paused ? 0 : Native.warmStatus();
        if (dev.peanuts.ui.WarmNotice.shown(n)) {
            if (warmView == null) {
                warmView = new TextView(this);
                warmView.setTextColor(Color.WHITE);
                warmView.setTextSize(13);
                float dp = getResources().getDisplayMetrics().density;
                int p = Math.round(8 * dp);
                warmView.setPadding(p, p, p, p);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xCC000000);
                bg.setCornerRadius(10 * dp);
                warmView.setBackground(bg);
                warmView.setMaxWidth(Math.round(460 * dp));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = Math.round(16 * dp);
                rootView.addView(warmView, panel != null ? rootView.indexOfChild(panel) : rootView.getChildCount(), lp);
                Native.note("preparing graphics: notice shown (" + n + " pipelines waiting)");
            }
            warmView.setText(dev.peanuts.ui.WarmNotice.text(n));
        } else if (warmView != null) {
            rootView.removeView(warmView);
            warmView = null;
            Native.note("preparing graphics: notice hidden");
        }
        if (dev.peanuts.ui.WarmNotice.poll(n)) ui.postDelayed(warmRun, 1000);
    }
    // PEANUTS end

    // PEANUTS begin: the active display reports the compositor's required buffer-queue lead, independent of nominal refresh.
    private void presentationDeadlineUpdate() {
        android.view.Display display = getDisplay();
        Native.presentationDeadlineSet(display == null ? 0 : display.getPresentationDeadlineNanos());
    }
    // PEANUTS end

    /**
     * The overlay, twice a second: top right the frame rate and the other items that are on (Overlay), top left the build tag; nothing is read while it is off. Both go to
     * the host's present, which draws them into the game's images (Overlay.Block, redrawn only when the text changed), so the window over the SurfaceView stays still; the
     * views show them only while the present does not (not yet drawn, DM_VK_OVERLAY=0, a failure), the top-right one set again only when its text changed
     */
    private void fpsTick() {
        // PEANUTS: Stable 30 uses a window-local 60 Hz mode; requested FG and the running cap are authoritative.
        if (started && resumedNow && !stopped && !paused) runningDisplayMode.update(this, Native.fpsCapStatus(), Native.frameGenEnabled());
        else runningDisplayMode.clear(this);
        if (stopped || paused) { ui.postDelayed(this::fpsTick, 2000); return; }   // nothing to show: no readings (thermal, battery, the host's counters) while suspended
        if (started && resumedNow) presentationDeadlineUpdate();   // PEANUTS: reuse the active 500 ms tick, after the mode request
        boolean on = overlayOn();
        CharSequence t = on ? stats.tick() : null, g = on && Prefs.overlayItem(this, "tag") ? tag.getText() : null;
        boolean host = tagBlock.host(g) & fpsBlock.host(t);   // both every time: a block that is off empties its corner
        fps.setVisibility(!host && t != null ? View.VISIBLE : View.GONE);
        tag.setVisibility(!host && g != null ? View.VISIBLE : View.GONE);
        if (!host && t != null) {
            String k = Overlay.key(t);
            if (!k.equals(fpsShown)) { fps.setText(t); fpsShown = k; }   // the same text again would lay the window out again for nothing
        }
        ui.postDelayed(this::fpsTick, 500);
    }

    private String saveHow = "new", saveNote;

    /**
     * The game's save folder: the one picked in Folders, else the app's own saves/&lt;id&gt; (AppStorage.save; the device save beside it). An older version's save in
     * the old default folder beside the game (&lt;Switch2 root&gt;/saves/&lt;id&gt; for the play app, saves-test/&lt;id&gt; for the test app: Prefs.oldSaveDefault) is
     * copied in once, when the app's save is empty (defaultSave, SaveMigrate). An empty folder is then filled from the private save of the first versions
     * (&lt;app files&gt;/save/&lt;id&gt;, "migrated"), else from &lt;Switch2 root&gt;/save-seed/&lt;id&gt; (seeded). Falls back to that private folder.
     */
    private File saveDir(File files, File game, String id) throws IOException {
        File priv = new File(files, "save/" + id);
        String chosen = Prefs.folder(this, "save_" + id);   // the user's pick (Folders…): used as it is, never migrated into or seeded (the launcher asked about copying)
        File dir = testSave != null ? new File(testSave) : chosen != null ? new File(chosen) : defaultSave(game, id);
        // A picked save folder that is gone is never made again here: that would start a new empty save in its place (the launcher asks: the app's save, or pick it again)
        if (testSave == null && chosen != null && !dir.isDirectory()) throw new IOException(GameFile.saveGoneText(dir.getName()));
        // The save's lock first (kept while the game runs), then a restore (Restore saves…) or swap stopped part way is finished (SaveStore's journal, the host's
        // rules); one that cannot be finished stops the start: never a half save
        holdLock(dir);
        try {
            if (SaveStore.recoverLocked(dir)) saveNote = "a save restore or swap that was stopped was finished first";
        } catch (IOException e) {
            throw new IOException("The save folder has an unfinished restore that cannot be finished: " + e.getMessage());
        }
        if (testSave != null) {   // a test run's own save folder (env.txt, test app only): used as it is
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create the test save folder " + dir);
            saveHow = "DM_SAVE from env-" + id + ".txt (test app)";
            return dir;
        }
        try {
            File root = game.getAbsoluteFile().getParentFile();
            for (String x : new String[] {".old", ".pend1", ".pend2"})   // a commit was interrupted: the host finishes it at mount (plat_swap_recover), before anything is migrated or seeded
                if (new File(dir.getPath() + x).exists()) { saveHow = "recovering"; return dir; }
            if (!dir.isDirectory() && !dir.mkdirs() || !dir.canWrite()) throw new IOException("cannot create " + dir);
            if (chosen != null) { saveHow = isEmpty(dir) ? "chosen, new" : "chosen"; return dir; }
            if (!isEmpty(dir)) { saveHow = "existing"; return dir; }
            if (!isEmpty(priv)) { copyTree(priv, dir); saveHow = "migrated"; return dir; }
            File seed = new File(root, "save-seed/" + id);
            if (!isEmpty(seed)) { copyTree(seed, dir); saveHow = "seeded"; return dir; }
            return dir;
        } catch (IOException | RuntimeException e) {
            saveNote = "save folder " + dir + " unusable (" + e.getMessage() + "), using the app's private folder";
            saveHow = isEmpty(priv) ? "new" : "existing";
            return mkdirs(priv);
        }
    }

    /**
     * The default save: the app's saves/&lt;id&gt;, after the one-time copy of the old default's save into it (SaveMigrate: checked file by file, under both saves'
     * locks, never over a save the app has, the old folder only read). When the copy fails, this start plays the old folder, as before, and says so; the next start
     * tries again. Once copied (Prefs.migratedFrom), the app's save is the game's for good
     */
    private File defaultSave(File game, String id) {
        File app = AppStorage.save(this, id), old = Prefs.oldSaveDefault(game, id);
        if (Prefs.migratedFrom(this, id) != null) return app;
        SaveMigrate.Result r = SaveMigrate.migrate(old, app);
        switch (r.state) {
            case COPIED:
                Prefs.migratedFrom(this, id, old.getPath());
                saveNote = "copied once from " + old + " (" + r.copied.files + " files, " + SaveStore.size(r.copied.bytes) + ", checked); that folder is kept as it was";
                Toast.makeText(this, "Your save was copied into the app's storage (" + app + "). The old folder " + old + " is kept as it was.", Toast.LENGTH_LONG).show();
                return app;
            case FAILED:
                saveNote = "not copied into the app's storage (" + r.message + "): this start plays the save in " + old + "; the next start tries again";
                Toast.makeText(this, "Your save could not be copied into the app's storage (" + r.message + "). This start plays it where it is, " + old + ".", Toast.LENGTH_LONG).show();
                return old;
            default:   // the app has a save already, or there is none to copy
                return app;
        }
    }

    private static boolean isEmpty(File d) { String[] l = d.list(); return l == null || l.length == 0; }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] l = from.listFiles();
            if (l != null) for (File f : l) copyTree(f, new File(to, f.getName()));
        } else Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- The readiness check before the host starts (Readiness.java, android/README.md "Readiness check"): the Vulkan facts of the driver this game starts with, against the
    // host's needs and the game's readiness.tsv. Required items missing: a dialog names them with the GPU and driver, and the player goes back or tries anyway; degraded
    // ones: a notice the player can turn off for this phone and driver. Its one line goes into the game's log after the start ([readiness] ...) ----
    private boolean readinessDone, triedAnyway;
    /** The DM_ switches this build reads (BuildConfig.NX_ENV_SWITCHES, from docs/env_switches.tsv) */
    static final java.util.Set<String> KNOWN_SWITCHES = new java.util.HashSet<>(java.util.Arrays.asList(BuildConfig.NX_ENV_SWITCHES.split(" ")));
    private final List<String> unknownNotes = new ArrayList<>();
    private List<String> advNotes = new ArrayList<>();   // the [adv] log lines: every override in effect and where it came from (AdvancedSettings.notes)
    private String shapeNote;   // Aspect ratio › Screen shape: the window it read and the shape passed, for the log
    private int readinessEnv;   // the lines the readiness check ran with (readinessHold), for the log

    /** Where a user line with this key came from, for the [RED] line: the last of env.txt, the game's settings, env-<id>.txt that has it */
    private String switchFrom(String key, File files, String id) {
        String from = "env.txt";
        if (has(envLines(Prefs.gameEnv(this, id)), key)) from = "the game's settings";
        if (has(quietEnv(new File(files, "env-" + id + ".txt")), key)) from = "env-" + id + ".txt";
        return from;
    }
    private static boolean has(List<String> lines, String key) { for (String kv : lines) if (kv.substring(0, kv.indexOf('=')).trim().equals(key)) return true; return false; }

    /**
     * The environment the launcher's settings give the game, in start()'s order before the user's lines (which come after and win): Game resolution, frame generation,
     * FSR, the frame rate cap, the GPU driver, GPU timing, the button labels, the two Vulkan options and the game language. start() and the readiness check (readinessHold) both take it.
     * Returns the GPU driver's line for the log
     */
    /** The display's refresh rate in whole Hz (60, 90, 120...), 60 when it can't be read */
    private int displayHz() {
        android.view.Display d = getDisplay();
        int hz = d == null ? 60 : Math.round(d.getRefreshRate());
        return hz >= 30 && hz <= 240 ? hz : 60;
    }

    private int capDisplayHz(int maximum) {
        android.view.Display d = getDisplay();
        int hz = displayHz();
        if (d != null) {
            android.view.Display.Mode current = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight())
                    hz = Math.max(hz, Math.round(m.getRefreshRate()));
            }
        }
        return Math.max(60, Math.min(maximum, hz));
    }

    private Profile.Render startRender;   // the render setting this run started with (settingsEnv), null for a game without a render_cfg row
    Profile.Render startRender() { return startRender; }

    // The native run survives Activity recreation (attach); keep its launch timing mode too.
    private static boolean gameOwnedAtStart;
    boolean gameOwnedAtStart() { return gameOwnedAtStart; }
    private static int fpsModeAtStart;
    boolean fpsRestartRequired(int selected) {
        Profile p = Profile.of(titleId());
        return p != null ? p.fpsRestart(fpsModeAtStart, selected) : (selected == FrameRateCaps.GAME_OWNED) != gameOwnedAtStart;
    }
    private String settingsEnv(String id, Pack pack, List<String> env) {   // PEANUTS: pass the pack build for FPSLocker selection
        // Game resolution: the game's own setting, else the main screen's: the window size the host gives the game (window_fn), or the graphics scale and operation
        // mode that give that size (graphics_scale rows), as its profile says (Profile.resolutionEnv); nothing for a game with neither
        Profile prof = Profile.of(id);
        ScreenMode.env(this, id, env);   // PEANUTS: Console mode (DM_OPMODE) for a game whose Resolution does not set it
        ResScale.env(this, id, env);   // PEANUTS: Resolution scale (DM_RES_SCALE) when it is not 1x
        dev.peanuts.Companion.env(this, id, env);   // PEANUTS: companions off (PN_COMPANION=off) when the title's Second screen is Off, the default without a second display
        if (prof != null) env.addAll(prof.resolutionEnv(Prefs.startWindow(this, id)));   // a Resolution preset's window, else Game resolution's (Prefs.startWindow, ResPresets)
        // Resolution, Upscaling quality and Graphics quality (a render_cfg row: Profile.renderCfg), kept as the setting this run starts with: the loader sizes the render target
        // pool for it, and the in-game panel compares a pick with it (Profile.renderRestartNote)
        if (prof != null && prof.renderCfg()) { startRender = Prefs.render(this, prof, id); env.addAll(Profile.renderEnv(startRender)); }
        {   // Time of day (the launcher's System › Time of day): the host's DM_TIME, so a game with a day/night cycle can be started at a chosen time
            String tod = Prefs.timeOverride(this);
            if (!tod.isEmpty()) env.add("DM_TIME=" + tod);
        }
        if (prof != null && prof.vegetationDetail && !Prefs.fullVegetation(this, id)) env.add("DM_VK_VEGETATION=0");
        if (prof != null) env.addAll(prof.optionsEnv(optEnv -> Prefs.gameOption(this, id, optEnv)));
        Lsfg.env(this, env);   // frame generation: the user's Lossless.dll and the settings
        GameUpscaler.env(this, id, env);   // the Upscaler (Game settings, the in-game panel): the game's own upscaler and the present's scaling
        if (Prefs.drsGame(id)) { int[] drs = Prefs.DRS[Prefs.drs(this, id)]; env.add("DM_DRS=" + drs[0] + "," + drs[1]); }   // dynamic resolution range (Prefs.DRS)
        fpsModeAtStart = Prefs.paceCap(this, id);
        // PEANUTS: a supported update may lack this game's reviewed FPSLocker plan.
        boolean hasFpsPlan = prof == null || prof.fpsLockerPlanMatches(BuildCheck.builds(pack).get("main"));
        gameOwnedAtStart = !hasFpsPlan || Prefs.limited(id) && fpsModeAtStart == FrameRateCaps.GAME_OWNED;
        if (prof != null && prof.fpsPlan120.isEmpty() && (!prof.fpsLocker.isEmpty() || !prof.fpsLockerPlan.isEmpty()) && !gameOwnedAtStart) env.add("DM_FPSLOCKER_INTERVAL=1");
        if (prof != null && hasFpsPlan && !prof.fpsLockerPlan.isEmpty()) env.add("DM_FPSLOCKER_PLAN=" + prof.fpsLockerPlan);   // PEANUTS: only pass a plan the loader can apply
        if (prof != null && prof.fpsPlan120.isEmpty() && !prof.fpsLocker.isEmpty() && !gameOwnedAtStart) env.add("DM_FPSLOCKER=" + prof.fpsLocker);
        int cap = Prefs.fpsCap(this, id);   // the game's own frame rate cap (its in-game panel, its Game settings), else the main screen's (unset: the profile's fps_target)
        if (gameOwnedAtStart) {
            env.add("DM_FPSLOCKER="); env.add("DM_FPSLOCKER_OWNED=1"); env.add("DM_FPSLOCKER_INTERVAL=0"); env.add("DM_FPS_TARGET=0"); env.add("DM_FPS_LIMIT=0");
            env.add("DM_PRESENT_INTERVAL=game"); env.add("DM_PRESENT_HZ=60"); env.add("DM_VSYNC_HZ=60"); env.add("DM_FPS_DECOUPLE=0");
        } else if (prof != null && !prof.fpsPlan120.isEmpty()) {
            env.addAll(prof.fpsModeEnv(fpsModeAtStart));
        } else if (Prefs.paced(id)) {   // speed follows the measured rate: presents on the host's own tick at the display's refresh rate, no interval locking, and a frame limit (Prefs.PACE_CAPS)
            int pace = Prefs.paceCap(this, id);
            env.add("DM_PRESENT_INTERVAL=display"); env.add("DM_PRESENT_HZ=" + displayHz()); env.add("DM_FPS_TARGET=0"); env.add("DM_FPS_LIMIT=" + (pace > 0 ? pace : 60));
            if (!Prefs.decouple(this, id)) env.add("DM_FPS_DECOUPLE=0");   // Full game speed at low frame rates off (the in-game panel changes it live)
        } else if (Prefs.limited(id)) {   // a profile's default frame limit (fps_limit), which the host applies itself: only the player's own choice is passed (60 fps = none: the game's own interval)
            if (prof != null && prof.usesFpsModeTarget()) env.add("DM_FPS_TARGET=" + prof.fpsModeTarget(fpsModeAtStart));   // PEANUTS: select the native engine mode on launch
            int maximum = Prefs.paceMax(id);
            if (maximum > 60) {   // keep presentation and the guest's vsync event on the same high tick for live cap changes
                int hz = capDisplayHz(maximum);
                env.add("DM_PRESENT_HZ=" + hz); env.add("DM_VSYNC_HZ=" + hz);
                // PEANUTS: Keep the elapsed-time target while changing display cadence.
                if (prof == null || !prof.elapsedFrameStep) env.add("DM_FPS_TARGET=0");
                if (prof != null && (prof.optInPresentRate() || prof.elapsedFrameStep)) env.add("DM_PRESENT_INTERVAL=display");
            }
            if (Prefs.chosenFpsCap(this, id)) { int pace = Prefs.paceCap(this, id); env.add("DM_FPS_LIMIT=" + (pace > 0 ? pace : 60)); }
        } else if (cap >= 0) env.add("DM_FPS_TARGET=" + cap);
        if (Prefs.steps(id) && Prefs.hold60(this, id)) env.add("DM_FPS_HOLD60=1");   // Hold 60 fps: one step a frame at the cap of 60 (the in-game panel changes it live)
        String gpuNote = GpuDriver.env(this, id, env);   // the game's GPU driver, else the main screen's (DM_VK_DRIVER)
        dev.peanuts.overlay.Hud.driver(GpuDriver.selected(this, id) != null ? GpuDriver.selected(this, id).label() : Readiness.driverWord(Prefs.facts(this, Recommend.SYSTEM)));   // PEANUTS: the overlay's DRV row
        String ts = Prefs.gpuTs(this, id);   // Game settings › GPU timing; unset: the profile's gpu_timestamps (a DM_NVN_GPU_TS line of env.txt or the game's own lines comes later and wins)
        if (ts != null) env.add("DM_NVN_GPU_TS=" + ts);
        int xb = Prefs.xboxButtons(this, id);   // Xbox button label swap (Game settings, Settings)
        if (xb > 0) env.add("DM_XBOX_LABELS=1");
        if (xb == 2) env.add("DM_PAD_LABELLED=2");
        if (Prefs.instancedDraws(this, id)) env.add("DM_VK_INSTANCE=1");   // Game settings › Instanced draws
        if (Prefs.classicPasses(this, id)) env.add("DM_VK_CLASSIC_RP=1");   // Game settings › Classic render passes
        if (Prefs.skipLayersGame(id) && Prefs.skipLayers(this, id)) env.add("DM_VK_SKIP_FS=" + Prefs.skipLayersKeys(id));   // Game settings › Speedhack: Turn off hidden layers
        env.add("DM_VK_PSO_ASYNC=" + (Prefs.psoAsync(this, id) ? 2 : 0));   // Game settings › Background pipelines (an env.txt line of the player's comes later and wins)
        if (Prefs.ramSaver(this, id)) { env.add("DM_MEM_FILEBACK=1"); env.add("DM_MEM_FILEBACK_KINDS=shader,texture"); }   // Game settings › RAM saver: texture pools in a file in TMPDIR, the cache folder (vka_mem.c "File-backed pools"); lines of env.txt or the game's own come later and win
        String padMap = Prefs.padMap(this, id);   // Button mapping (Game settings, Settings): wins over the label swap's A/B for the buttons it names
        if (!padMap.isEmpty()) env.add("DM_PAD_MAP=" + padMap);
        env.add("DM_MOTION_SENS=" + Prefs.motionFactor(this, id));   // Game settings › Controls › Motion sensitivity (the phone's gyro, 50 % unless the player chose; env.txt lines come later and win)
        if (Prefs.stretch(this, id)) env.add("DM_VK_STRETCH=1");   // Game settings › Display › Stretch to screen (or the in-game panel's Picture rows)
        String aspect = Prefs.aspect(this, id), variant = aspect == null || Profile.of(id) == null ? null : Profile.of(id).aspectVariant(aspect);
        // PEANUTS: aspect_plan rows (aspect_plan.h) are exact-build: pass them, and a shape, only for a main build they name
        boolean aspectPlanOk = prof == null || prof.aspectPlanMatches(BuildCheck.builds(pack).get("main"));
        if (prof != null && aspectPlanOk && !prof.aspectPlan.isEmpty()) env.add("DM_ASPECT_PLAN=" + prof.aspectPlan);
        runFill = aspectPlanOk && variant == null && ScreenShape.CHOICE.equals(aspect) && GameSettings.fillable(Profile.of(id));   // PEANUTS: Display > Fill at this start
        if (variant != null) {   // Game settings › Aspect ratio: the pack's native-aspect modules (<pack>/<variant>/, loader.c) and the picture shown at that aspect
            env.add("DM_PATCH_VARIANT=" + variant);
            env.add("DM_VK_PICTURE_ASPECT=" + aspect);
        } else if (!aspectPlanOk) {   // PEANUTS: aspect_plan rows that do not cover this pack's main build: the game as shipped, not a squeezed picture
            shapeNote = "[nxlaunch] aspect ratio: the profile's aspect rows are for another game version; 16:9 kept";
        } else if (aspect != null && Profile.of(id) != null && Arrays.asList(Profile.of(id).cameraAspects.split(",")).contains(aspect)) {   // a shape the host's camera patch follows (game.tsv aspect rows): the picture at that shape, no variant
            env.add("DM_VK_PICTURE_ASPECT=" + aspect);
        } else if (ScreenShape.CHOICE.equals(aspect) && GameSettings.fillable(Profile.of(id))) {   // PEANUTS: fillable, so aspect_plan rows alone do (Display › Fill)   // Screen shape: the window's own shape (ScreenShape), read now, once; a 16:9-ish window passes nothing
            android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            String shape = ScreenShape.ratio(b.width(), b.height(), orientationFixed());
            if (shape != null) env.add("DM_VK_PICTURE_ASPECT=" + shape);
            shapeNote = ("[nxlaunch] aspect ratio: screen shape, window " + b.width() + "x" + b.height() + (orientationFixed() ? " (landscape)" : "") + (shape == null ? ": about 16:9, nothing passed" : " -> " + shape));
        }
        int picSize = Prefs.pictureSize(this, id);   // the in-game panel's Picture size; 100 (the default) is not passed
        if (picSize < PictureFit.SIZE_MAX) env.add("DM_VK_PICTURE_SIZE=" + picSize);
        String lang = GameLanguage.env(Prefs.gameLang(this, id), GameSettings.phoneTag());   // Game settings › Game language: the phone's by default (a DM_LANG line of the user's wins)
        if (lang != null) env.add(lang);
        env.addAll(Users.env(this));   // Settings › Users: the users the game's account calls list and its user selector offers (hle_account.c), and the one it starts with
        return gpuNote;
    }
    private String readinessLine;
    private static final long READINESS_WAIT_MS = 8000;

    /** true: the start waits for the check (it calls start() again when the player may go on) */
    private boolean readinessHold(String id, Pack pack) {   // PEANUTS: pass the pack build for FPSLocker selection
        if (readinessDone) return false;
        File files = getExternalFilesDir(null);
        Prefs.pinDriver(this, id);   // a recommended driver stays the one this check probes for the whole start (Prefs.pinDriver)
        // the game's environment as start() will set it (the launcher's settings, then the user's lines, which win), less the paths start() sets: the check makes the
        // process's first Vulkan device, and a driver reads some variables only then (FD_DEV_FEATURES, TU_DEBUG: GitHub #40)
        List<String> genv = new ArrayList<>();
        settingsEnv(id, pack, genv);   // PEANUTS: pass the pack build for FPSLocker selection
        genv.addAll(AdvancedSettings.lines(this, id, PROTECTED));   // Advanced rows come before env.txt's lines
        genv.addAll(quietEnv(new File(files, "env.txt")));
        genv.addAll(envLines(Prefs.gameEnv(this, id)));
        genv.addAll(quietEnv(new File(files, "env-" + id + ".txt")));
        String[] denv = Readiness.gameEnv(genv, PROTECTED);
        readinessEnv = denv.length;
        probedChoice = probedChoice(denv, id);
        overlay.setText(getIntent().getStringExtra("name") + ": checking this phone's GPU…");
        final boolean[] answered = {false};
        Runnable decide = () -> {   // on the UI thread, once: with the facts, or without them after READINESS_WAIT_MS
            if (answered[0] || isFinishing()) return;
            answered[0] = true;
            readinessDecide(id, probeFacts);
        };
        new Thread(() -> { probeFacts = Native.readiness(denv); probeDone = true; ui.post(decide); }, "nxreadiness").start();
        ui.postDelayed(() -> { if (!probeDone) probeFacts = "unchecked=the GPU check did not answer in " + READINESS_WAIT_MS / 1000 + " s"; decide.run(); }, READINESS_WAIT_MS);
        return true;
    }
    private volatile String probeFacts;
    private String probedChoice;   // the driver choice the check probes (Recommend.SYSTEM or a folder name), null when a user line picks another: its facts are then not kept

    /** The driver choice the check's environment loads: the launcher's own (Prefs.driverChoice), the system driver for a user line DM_VK_DRIVER=system, else null (Recommend.probedChoice) */
    private String probedChoice(String[] denv, String id) {
        String line = null;
        for (String kv : denv) if (kv.startsWith("DM_VK_DRIVER=")) line = kv;
        GpuDriver.Info i = GpuDriver.selected(this, id);
        String own = i == null ? null : "DM_VK_DRIVER=" + i.dir.getPath();
        return Recommend.probedChoice(line, own, Prefs.driverChoice(this, id));
    }
    private volatile boolean probeDone;

    private List<String> quietEnv(File f) {
        try { return f.isFile() ? envLines(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new ArrayList<>(); }
        catch (IOException | SecurityException e) { return new ArrayList<>(); }
    }

    private void readinessDecide(String id, String vulkan) {
        Profile p = Profile.of(id);
        Map<String, String> f = Readiness.merge(vulkan, ReadinessActivity.ramGib(this), Build.VERSION.SDK_INT);
        if (probedChoice != null) Prefs.rememberFacts(this, probedChoice, vulkan);   // what the recommended settings match on (Recommend), for this driver
        List<Readiness.Check> l = new ArrayList<>();
        Readiness.Check st = Readiness.status(p != null ? p.status : null);
        if (st != null) l.add(st);
        l.addAll(Readiness.evaluate(f, Readiness.parseReqs(p != null ? p.reqs : null)));
        rFacts = f;
        rChecks = l;
        readinessLine = Readiness.summary(id, f, l);
        String report = Readiness.report(ReadinessActivity.header(this, (p != null ? p.name + " " : "") + id + " (start-up check)"), f, l);
        try { Files.write(new File(getExternalFilesDir(null), "readiness-" + id + ".txt").toPath(), report.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { /* the screen's report leaves it out */ }
        readinessDone = true;
        List<Readiness.Check> fails = Readiness.of(l, Readiness.Status.FAIL), warns = Readiness.of(l, Readiness.Status.WARNING);
        String gpu = "GPU: " + Readiness.gpu(f) + "\nMemory: " + f.get("ram_gib") + " GiB as Android reports it.";
        // The notice is short (what is missing, by name); Details has each item's sentence, the GPU and Copy report
        if (!fails.isEmpty()) {
            if (dev.peanuts.build.ShaderCapture.active(this)) { fail(readinessLine); return; }   // PEANUTS: report preparation failure to Build, no Play prompt
            StringBuilder m = new StringBuilder("Missing: ");
            for (Readiness.Check c : fails) m.append(c.title).append(", ");
            m.setLength(m.length() - 2);
            // Without placed memory maps the GPU sees none of the game's memory: draws are off (vka_draw.c), so the result is certain, not "may"
            boolean placed = false;
            for (Readiness.Check c : fails) placed |= "ext.VK_KHR_map_memory2".equals(c.fact) || "ext.VK_EXT_map_memory_placed".equals(c.fact) || "feature.memoryMapPlaced".equals(c.fact);
            GpuDriver.Info drv = GpuDriver.selected(this, id);
            boolean custom = Readiness.customDriversFit(f);   // the custom drivers are Adreno drivers: no help on another GPU (its reported vendor ID)
            if (placed && drv == null && !custom) m.append(".\n\nThis GPU's own driver has no placed memory maps, so nothing is drawn: the screen stays black while the game runs. "
                    + "The custom drivers the app can install are for Adreno GPUs only, so this phone cannot draw the game yet.");
            else if (placed) m.append(".\n\nWith this GPU driver (").append(drv == null ? "System" : drv.label()).append(") nothing is drawn: the screen stays black while the game runs. ")
                    .append(drv == null ? "Pick a custom driver that has placed memory maps" : "Pick System (the phone's own driver)")
                    .append(" in Settings › GPU driver, or in this game's Game settings.");
            else m.append(custom ? ".\n\nThe game may stop or draw wrongly. A custom GPU driver (Settings › GPU driver) may help." : ".\n\nThe game may stop or draw wrongly.");
            readinessDialog("Not ready for this game", m.toString(), details(fails, warns, gpu), report, placed ? "Start anyway (black screen)" : "Try anyway", true);
        } else {   // warnings only ("Ready, with limits"): no notice at the start, it stopped every start; the log has the line (readinessLine) and Settings › Readiness check the details
            start();
        }
    }

    /** The Details of the start-up notice: each failure and warning with its sentence, then the GPU and memory */
    private static String details(List<Readiness.Check> fails, List<Readiness.Check> warns, String gpu) {
        StringBuilder m = new StringBuilder();
        for (Readiness.Check c : fails) m.append("✕ ").append(c.title).append(": ").append(c.message).append("\n\n");
        for (Readiness.Check c : warns) m.append("! ").append(c.title).append(": ").append(c.message).append("\n\n");
        return m.append(gpu).toString();
    }

    /** What the notice was about (the warnings, the GPU and driver): "Don't show again" holds until one of them changes */
    private static String warnKey(Map<String, String> f, List<Readiness.Check> warns) {
        StringBuilder s = new StringBuilder(Readiness.gpu(f));
        for (Readiness.Check c : warns) s.append('|').append(c.title);
        return Integer.toHexString(s.toString().hashCode());
    }

    /**
     * The start-up notice. Required items missing: Try anyway, Back, Details. Only degraded items: Continue (one tap), with "Don't show this again" (until the GPU,
     * the driver or the warnings change), Back and Details; Settings › Readiness check shows the details again at any time.
     */
    private void readinessDialog(String title, String msg, String details, String report, String go, boolean required) {
        String id = titleId();
        android.widget.CheckBox again = required ? null : new android.widget.CheckBox(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setCancelable(false).setNeutralButton("Details", null);
        if (again == null) b.setMessage(msg);
        else {
            int p = Math.round(20 * getResources().getDisplayMetrics().density);
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding(p, p / 2, p, 0);
            TextView t = new TextView(this);
            t.setText(msg + "\n\nDetails are also in Settings › Readiness check.");
            t.setTextSize(15);
            box.addView(t);
            again.setText("Don't show this again");
            box.addView(again);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);   // a short screen with several warnings still reaches the box
            sv.addView(box);
            b.setView(sv);
        }
        b.setPositiveButton(go, (d, w) -> {
            if (again != null && again.isChecked())
                Prefs.of(this).edit().putString(Prefs.gameKey("readiness_seen", id), warnKey(rFacts, Readiness.of(rChecks, Readiness.Status.WARNING))).apply();
            triedAnyway = required;
            start();
        });
        b.setNegativeButton("Back", (d, w) -> end());
        AlertDialog d = b.create();
        // Details opens over the notice and keeps it open; Copy report there keeps Details open
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            AlertDialog dd = new AlertDialog.Builder(this).setTitle("Readiness check").setMessage(details).setPositiveButton("Close", null).setNeutralButton("Copy report", null).create();
            dd.setOnShowListener(y -> dd.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(u -> ReadinessActivity.copy(this, report)));
            dd.show();
        }));
        d.show();
    }
    private void noteOwnRate() {
        try {
            String[] f = Native.fpsCapStatus().split(" ");
            if (Integer.parseInt(f[0]) >= 0 && Integer.parseInt(f[2]) > 0) Prefs.ownRate(this, titleId(), Integer.parseInt(f[1]) == 0);   // presenting: main is loaded
        } catch (RuntimeException e) { /* no answer: left as it was */ }
    }

    // ---- Cheats (Game settings › Cheats, Cheats.java): with the switch on, the pack's profile cheat file and the player's imported ones merged into the game's cheats
    // folder with the toggles as <build>.enabled, passed as DM_CHEATS; the in-game panel lists cheatList. Switch off: nothing passed, nothing listed ----
    private List<Cheats.Entry> cheatList = java.util.Collections.emptyList();
    private String cheatNote;   // the log line of the cheats this run started with (after the start, with the save's)
    private List<File> cheatMods; private boolean cheatsMerged;   // PEANUTS: the enabled mods' cheats folders of this start (ModCheats); whether cheatsEnv merged them into upstream's folder
    /** The cheats this run started with, for the in-game panel's Cheats section: empty while Game settings › Cheats is off (Cheats.panel) */
    List<Cheats.Entry> cheats() { return cheatList; }

    private void cheatsEnv(String id, Pack pack, File packDir, List<String> env) {
        boolean has = Prefs.cheatsGame(id), on = Prefs.cheats(this, id);
        if (!has || !on) return;
        java.util.Set<String> builds = Cheats.builds16(String.join(" ", pack.builds));
        String build = builds.isEmpty() ? null : builds.iterator().next();
        if (build == null) { unknownNotes.add("[nxlaunch] Cheats: the pack names no main build; this start runs without cheats"); return; }
        File folder = CheatsUi.folder(this, id), shipped = Cheats.packFile(packDir, build);
        try {
            // PEANUTS begin: was Cheats.install(shipped, folder, build, Prefs.cheatsOn(this, id)): the enabled mods' cheat files are in the one list too (ModCheats),
            // the cheats a mod's own .enabled turns on are on the first time they are listed, and a mod's file that cannot be read is a note, not a start without cheats
            java.util.Set<String> con = Prefs.cheatsOn(this, id); int con0 = con.size(); List<String> cnotes = new ArrayList<>();
            List<Cheats.Entry> l = ModCheats.install(shipped, folder, build, con, cheatMods, cnotes);
            if (con.size() != con0) Prefs.cheatsOn(this, id, con);
            for (String n : cnotes) unknownNotes.add("[nxlaunch] Cheats: " + n);
            // PEANUTS end
            if (l == null) { unknownNotes.add("[nxlaunch] Cheats: no cheat file for build " + build + (shipped == null ? " in the pack (Prepare again brings the game's own) and none imported" : "")); return; }
            env.addAll(Cheats.env(true, true, folder));
            cheatsMerged = true;   // PEANUTS: the mods' cheats are in this folder: no second DM_CHEATS line (start)
            cheatList = Cheats.panel(true, true, l);
            cheatNote = "[nxlaunch] cheats: " + cheatList.size() + " cheats for build " + build + (shipped == null ? " (imported only: the pack has no profile cheat file)" : "") + ", " + Prefs.cheatsOn(this, id).size() + " on";
        } catch (IOException e) { unknownNotes.add("[nxlaunch] Cheats: not set up (" + e.getMessage() + "); this start runs without cheats"); }
    }

    private Map<String, String> rFacts;
    private List<Readiness.Check> rChecks;

    private void start() {
        String id = getIntent().getStringExtra("title_id"), name = getIntent().getStringExtra("name");
        File game = new File(getIntent().getStringExtra("game")), packDir = new File(getIntent().getStringExtra("pack"));
        File files = getExternalFilesDir(null);
        try {
            Pack pack = Pack.read(packDir);
            String romfs = pack.romfsPath(game);   // a folder's RomFS folder, or <file>.nup:<selector>/romfs (NUP.md)
            if (romfs == null) throw new IOException(Nup.isNup(game) ? game.getName() + " has no " + pack.romfsSelector() + " with a RomFS" : "no RomFS in " + game);
            if (readinessHold(id, pack)) return;   // after the pack's own checks (a missing pack says so first), before the save is locked or anything is written (PEANUTS: pack build)
            // A .nup used where it is (PickedNup): the host gets a descriptor of it, fd:<n>/<name>, wherever it would get the file's path (DM_ROMFS, the add-on links);
            // it stays open for this process's life, and the host duplicates it for each image it opens (nup_image.h)
            int nupFd = PickedNup.is(this, game.getPath()) ? PickedNup.detachedFd(this, game) : -1;
            if (nupFd >= 0) romfs = PickedPath.forHost(romfs, game, nupFd);
            // The user's lines, read first: the paths the launcher sets cannot be changed by them (guard), but the test app's env-<id>.txt may name a test save folder (one game's file only: a global DM_SAVE would point every game at one save)
            List<String> user = new ArrayList<>(guard(AdvancedSettings.lines(this, id, PROTECTED), "Advanced", false));   // Settings > Advanced and the game's Advanced switches: before env.txt, so its lines and the game's own win
            List<String> envTxt = guard(userEnv(new File(files, "env.txt")), "env.txt", false);
            user.addAll(envTxt);   // diagnostic switches (every game: never DM_SAVE) (DM_SVC=1, DM_FS=1, ...), as the iPhone's Documents/env.txt
            user.addAll(guard(LinkPlay.env(this, id), "Link Play", false));   // Game settings › Link Play (before the typed lines, which win)
            List<String> extra = guard(envLines(Prefs.gameEnv(this, id)), "the game's settings", false);
            user.addAll(extra);   // the game's own lines (its Game settings): after env.txt, so they win for this game
            List<String[]> fromFiles = new ArrayList<>();   // for the log's [adv] lines
            for (String l : envTxt) fromFiles.add(new String[] {l, "env.txt"});
            for (String l : extra) fromFiles.add(new String[] {l, "Extra environment"});
            advNotes = AdvancedSettings.notes(this, id, PROTECTED, fromFiles);
            user.addAll(guard(userEnv(new File(files, "env-" + id + ".txt")), "env-" + id + ".txt", testApp()));   // and env-<title ID>.txt (adb), the same for one game
            // the save is the application's: a program pack (a Switch 2 Edition's program 1, its pack.tsv's application row) keeps the game's save, as on the console;
            // its temp, logs and host are its own. So every start of it (the card's edition switch, a frontend, a shortcut) plays the same save
            String saveId = pack.application.isEmpty() ? id : pack.application;
            File save = dev.peanuts.build.ShaderCapture.save(this);   // PEANUTS: a preparation boot never opens the player's save
            if (save == null) save = saveDir(files, game, saveId);   // PEANUTS
            File temp = mkdirs(new File(files, "temp/" + id)), root = mkdirs(new File(files, "root"));   // PEANUTS: save is chosen above
            // The DLC links live in internal app storage: the external files folder is shared storage (FUSE), which refuses symbolic links (errno not yet confirmed on a phone; a refused link is now a [RED] line), so
            // every link failed there and no game saw its DLC (docs/reference/packing.md "DLC links in shared storage"). Their targets stay where they are
            File aoc = aocLinks(mkdirs(new File(getFilesDir(), "aoc/" + id)), game, pack, nupFd);
            Pack.removeDir(new File(files, "aoc/" + id));   // the folder earlier builds used (empty, or links on a device that took them)
            log = new File(LogProvider.dir(this), id + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".log");
            pruneLogs(id);
            ExitWatch.log(log);
            List<String> env = new ArrayList<>(List.of(
                    "DM_ROMFS=" + romfs, "DM_SAVE=" + save, "DM_TEMP=" + temp, "DM_ROOT=" + root, "DM_AOC_DIR=" + aoc,
                    "HOME=" + files, "TMPDIR=" + getCacheDir(), "DM_BUILD_COMMIT=" + BuildConfig.NX_COMMIT,
                    "DM_CTL=" + new File(files, "ctl.txt"), "NX_EXIT_RECORD=" + ExitWatch.record(this)));   // live control lines (android/TESTING.md "Live control", android/tools/nxctl.sh); DM_CTL= in env.txt turns it off
            env.addAll(dev.peanuts.build.GenericProfile.envLines(this, id, packDir));   // PEANUTS: packDir: a modded variant's own profile.bin (GenericProfile)   // PEANUTS: -PnxGenericHost=true DM_PROFILE_PATH/DM_PROFILE_SEED; empty list otherwise
            env.add(0, "NX_BUILD_TAG=" + Prefs.buildTag(this) + " \u00b7 signer " + Prefs.signerId(this));   // nxlaunch.c writes it as the log's first line; the signer says which key signed the APK (Signer)
            // a program pack (a Switch 2 Edition's program 1): each program index's RomFS for the indexed MountRom (hle_fs.c DM_ROMFS_PROGRAM_<i>; 0 is the game's, "conorig")
            for (Map.Entry<Integer, String> e : pack.romfsProgramPaths(game).entrySet())
                env.add("DM_ROMFS_PROGRAM_" + e.getKey() + "=" + e.getValue());
            try (java.io.InputStream fin = getAssets().open(GameFonts.ASSET)) {   // the shared-font stand-in (dm/third_party/fonts): the host serves it instead of the phone's own fonts; a DM_FONT_DIR line of the user's comes later and wins
                File fontDir = new File(getFilesDir(), "fonts");
                GameFonts.place(fontDir, GameFonts.STANDARD, fin.readAllBytes());
                env.add(GameFonts.envLine(fontDir));
            } catch (IOException e) { unknownNotes.add("[nxlaunch] the shared font stand-in is not placed (" + e.getMessage() + "): the host uses the phone's own fonts"); }
            try (java.io.InputStream fin = getAssets().open(GameFonts.EXT_ASSET)) {   // the button icons in game text (shared-font type 5: hle_sdk16.c), in the same folder; without it they draw as '?'
                GameFonts.place(new File(getFilesDir(), "fonts"), GameFonts.EXT, fin.readAllBytes());
            } catch (java.io.FileNotFoundException e) {   // an APK built without fontTools: the host's [YELLOW] line says so
            } catch (IOException e) { unknownNotes.add("[nxlaunch] the button-icon font is not placed (" + e.getMessage() + "): button icons in game text draw as '?'"); }
            String gpuNote = settingsEnv(id, pack, env);   // PEANUTS: pass the pack build for FPSLocker selection
            cheatsMerged = false;   // PEANUTS: decided anew at each start (this screen's object may start a game again)
            cheatMods = dev.peanuts.mods.ModRuntime.cheatsDirs(game.isDirectory() ? game : game.getParentFile(), id, packDir);   // PEANUTS: for cheatsEnv (ModCheats)
            cheatsEnv(id, pack, packDir, env);
            // The 3D scene (Game settings › Resolution, ResPresets; Advanced › 3D scene, SceneRes): the chosen RomFS overlay in the pack, made by the pack step from the player's files,
            // or the chosen game arguments (scene_arg); a DM_ROMFS_OVERLAY or DM_GAME_ARGS_ADD line of the user's comes later and wins
            Profile sceneProf = Profile.of(id);
            List<SceneRes.Preset> scenes = SceneRes.presets(sceneProf);
            List<ResPresets.Preset> resPresets = ResPresets.presets(sceneProf);
            String sceneNote = null;
            String sceneName = Prefs.startScene(this, id);
            ResPresets.Preset rp = ResPresets.of(resPresets, sceneName);
            File sceneOverlay = null;   // PEANUTS: mods (the scene overlay, kept for the mods' line below)
            if (rp != null) {   // a preset with a scene of its own: its overlay is named by its id
                File ov = SceneRes.overlay(pack.scenes, packDir, rp.id);
                if (ov != null) env.add(SceneRes.envLine(ov));
                sceneNote = SceneRes.note(rp.id, rp.label + " \u00b7 " + rp.detail(), ov);
                sceneOverlay = ov;   // PEANUTS: mods (see below)
            } else if (!scenes.isEmpty()) {   // an overlay (scene_scale), or game arguments the engine takes (scene_arg: DM_GAME_ARGS_ADD, nothing in the pack)
                File ov = SceneRes.overlay(scenes, pack.scenes, packDir, sceneName);
                if (ov != null) env.add(SceneRes.envLine(ov));
                String argLine = SceneRes.argLine(scenes, sceneName);
                if (argLine != null) env.add(argLine);
                sceneNote = SceneRes.note(scenes, sceneName, ov);
                sceneOverlay = ov;   // PEANUTS: mods (see below)
            }
            // PEANUTS: Mods (DESIGN-MODS.md) \u2014 the enabled mods' romfs, highest-priority first, ahead of the scene overlay above (hle_fs.c:222-223
            // DM_ROMFS_OVERLAY is ':'-separated, first root to have the file wins, hle_fs.c:375-388/1289-1326); replaces the scene's own line above
            // only when a mod actually has a romfs (env is applied setenv-overwrite style, so the last DM_ROMFS_OVERLAY line here wins \u2014 same rule
            // the scene comment above already relies on for the user's own env.txt). DM_CHEATS stages the enabled mods' cheats into one folder
            // (hle_cheats.c reads a single flat folder by build ID); a "code" mod's exefs/exefs_patches never reach this process at all.
            String modOverlay = dev.peanuts.mods.ModRuntime.romfsOverlayEnv(this, game.isDirectory() ? game : game.getParentFile(), id, sceneOverlay, packDir);   // PEANUTS: mods beside the game; packDir: a code mod's files only with its build
            if (modOverlay != null) env.add(modOverlay);
            String modCheats = dev.peanuts.mods.ModRuntime.cheatsEnv(this, game.isDirectory() ? game : game.getParentFile(), id, packDir);   // PEANUTS: mods beside the game; a code mod's cheats only with its build
            if (modCheats != null && !cheatsMerged) env.add(modCheats);   // PEANUTS: not beside upstream's own DM_CHEATS, which holds the mods' cheats then (cheatsEnv, ModCheats): the later line won, and the panel listed cheats the host did not run
            String resNote = resPresets.isEmpty() ? null   // the choice in force, for the log: which preset (or Advanced), and the window and scene it gave
                    : "[nxlaunch] resolution " + Prefs.resolutionChoice(this, id) + (Prefs.resPreset(this, id).isEmpty() ? " (nothing chosen: the earlier settings' match or the default)" : "")
                    + ": window " + Prefs.startWindow(this, id) + ", scene " + (sceneName.isEmpty() ? "the game's own palette" : sceneName);
            pinStart();   // Use fast cores (Game settings, Settings)
            skipStart();
            // DM_TEST_CRASH (a test crash, ExitWatch): the test app only
            if (!testApp() && user.removeIf(kv -> kv.startsWith("DM_TEST_CRASH="))) unknownNotes.add("[nxlaunch] DM_TEST_CRASH not taken: the test app only");
            env.addAll(user);
            dev.peanuts.build.ShaderCapture.environment(this, env);   // PEANUTS: isolated silent capture, after user overrides
            for (String k : Readiness.unknownSwitches(user, KNOWN_SWITCHES)) unknownNotes.add(Readiness.unknownLine(k, switchFrom(k, files, id), KNOWN_SWITCHES));
            boolean stretch = false;
            String testCrash = null;
            int picSize = PictureFit.SIZE_MAX;
            float picAspect = 16f / 9f;
            String ctl = null;
            for (String kv : env) {   // the last line wins, as for the host
                if (kv.startsWith("DM_OVERLAY=")) overlayEnv = !kv.substring(11).trim().equals("0");
                if (kv.startsWith("DM_VK_STRETCH=")) stretch = envOn(kv.substring(14));
                if (kv.startsWith("DM_VK_PICTURE_SIZE=")) picSize = PictureFit.size((int) envNum(kv.substring(19)));
                if (kv.startsWith("DM_VK_PICTURE_ASPECT=")) {   // w:h, as the present reads it
                    String[] wh = kv.substring(21).trim().split(":");
                    try { if (wh.length == 2 && Integer.parseInt(wh[0]) > 0 && Integer.parseInt(wh[1]) > 0) picAspect = Integer.parseInt(wh[0]) / (float) Integer.parseInt(wh[1]); } catch (NumberFormatException e) { /* the present ignores it too */ }
                }
                if (kv.startsWith("DM_CTL=")) ctl = kv.substring(7).trim();
                if (kv.startsWith("DM_TEST_CRASH=")) testCrash = kv.substring(14).trim();
            }
            runCtl = ctl == null || ctl.isEmpty() ? null : ctl;
            controls.setAspect(picAspect);
            controls.setPicture(runStretch = stretch, runPictureSize = picSize);
            String[] args = {"nxhost", packDir.getPath()};
            int r = Native.start(args, env.toArray(new String[0]), log.getPath(), files.getPath());
            if (r != 0) throw new IOException("the host did not start (" + r + (r == -1 ? ": a start was already tried in this process" : "") + ")");
            // Rumble (Game settings, Settings) once the host has its environment and the log (Native.start): its DM_RUMBLE / DM_RUMBLE_GAIN are read then, and the
            // setup lines reach the game's log. Pads registered before get their vibrator now
            if (Prefs.rumble(this, id)) { rumble = new Rumble(this); if (!rumble.start()) rumble = null; else for (int p : pads) rumble.route(p); }
            // Whether this game picks its own frame rate (the host's cap status: no rate it can hold), remembered for its Game settings, which then offer no cap.
            // Read once the game has run a while (the host finds the game's limiter after it starts); the in-game panel updates it too
            ui.postDelayed(this::noteOwnRate, 30000);
            hostRunning = true;
            prewarmTick();   // PEANUTS: startup shader progress
            if ("java".equals(testCrash)) { Native.note("[RED] DM_TEST_CRASH=java: an exception on the main thread in 10 s"); ExitWatch.testCrash(ui); }   // =native: nxlaunch.c
            if (readinessLine != null) Native.note(readinessLine + (triedAnyway ? "; the player chose Try anyway" : ""));   // the readiness check (readinessHold)
            Native.note("[nxlaunch] game: " + game + (nupFd >= 0 ? " (.nup used where it is, descriptor " + nupFd + ")" : Nup.isNup(game) ? " (.nup)" : " (folder)") + ", DM_ROMFS " + romfs
                    + ", pack " + packDir);
            Native.note("[nxlaunch] save: " + save + " (" + saveHow + ")" + (saveNote != null ? "; " + saveNote : ""));
            if (cheatNote != null) Native.note(cheatNote);
            // PEANUTS: -PnxGenericHost=true loads libnxhost.so (one for every title), not libnxhost_<id>.so
            Native.note("nxnative " + BuildConfig.VERSION_NAME + " (" + BuildConfig.NX_COMMIT + "), host " + (BuildConfig.NX_GENERIC_HOST ? "libnxhost.so" : "libnxhost_" + id + ".so") + "; " + name + " " + id + " version " + pack.version);
            Native.note("device " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0]
                    + "; game " + game + "; pack " + packDir);
            if (envProblem != null) Native.note("[RED] env.txt: " + envProblem);
            if (aocLine != null) Native.note(aocLine);
            for (String n : aocProblems) Native.note(n);
            if (readinessEnv > 0) Native.note("[nxlaunch] readiness check: ran with the game's " + readinessEnv + " environment lines (all but the paths), set before the GPU driver's first device");
            for (String n : unknownNotes) Native.note(n);
            if (!unknownNotes.isEmpty()) android.widget.Toast.makeText(this, unknownNotes.get(0).replace("[RED] ", ""), android.widget.Toast.LENGTH_LONG).show();
            if (!refused.isEmpty()) {
                String msg = "not taken: " + String.join(", ", refused) + ": the launcher sets the game's paths (DM_SAVE, DM_ROMFS, DM_TEMP, DM_ROOT, DM_AOC_DIR, HOME)"
                        + (testApp() ? "; DM_SAVE is taken from env-<title ID>.txt only" : "");
                Native.note("[RED] env: " + msg);
                android.widget.Toast.makeText(this, "Environment lines " + msg, android.widget.Toast.LENGTH_LONG).show();
            }
            if (testSave != null) Native.note("[nxlaunch] test app: DM_SAVE from env-" + id + ".txt: " + testSave);
            Native.note("frame generation: " + (Lsfg.on(this) ? "on" : "off") + "; " + Lsfg.note);
            Native.note(gpuNote + " (picked in the launcher; a DM_VK_DRIVER line in env.txt wins, the host's [vk] driver line says which ran)");
            Native.note(Recommend.logLine(Prefs.recStates(this, id)));   // each recommended setting with its value and source (yours / recommended / default); env.txt lines come later and win
            if (resNote != null) Native.note(resNote);
            if (shapeNote != null) Native.note(shapeNote);
            for (String n : advNotes) Native.note(n);
            if (sceneNote != null) Native.note(sceneNote);
            input = Native.hasInput();
            started = true;
            hostSetup();
            overlay.setText(name + ": starting. Log: " + log);
            ui.postDelayed(this::tail, 500);
            ui.postDelayed(this::fpsTick, 500);
        } catch (IOException | RuntimeException e) {
            fail(String.valueOf(e.getMessage()));
        }
    }

    /** Native.start returned 0 in this process: the game runs (until the process ends), and a new screen attaches to it instead of starting it */
    private static boolean hostRunning;
    /** The run's DM_VK_STRETCH (Game settings › Display, the in-game panel) and DM_VK_PICTURE_SIZE, as the host has them now, for a screen that attaches to the running game */
    private static boolean runStretch;
    private static int runPictureSize = PictureFit.SIZE_MAX;
    /** The run's live control file (DM_CTL), null when an env line turned it off: the in-game panel's changes then apply at the next start */
    private static String runCtl;
    /** A numeric switch's value as the host reads it: a number other than 0 is on */
    static boolean envOn(String v) { return envNum(v) != 0; }
    static double envNum(String v) { try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return 0; } }

    /** Whether the running game takes live switches (DM_CTL): the in-game panel says "applies at the next start" otherwise */
    boolean live() { return hostRunning && runCtl != null; }
    boolean runStretch() { return runStretch; }
    private static boolean runFill;   // PEANUTS: this run started with Display > Fill (the screen's own shape asked of the game), for the in-game panel's restart line
    boolean runFill() { return runFill; }   // PEANUTS
    int runPictureSize() { return runPictureSize; }

    /**
     * The in-game panel's Picture rows: Fit / Stretch and the picture size, kept for this game's next start and sent to the running game as live control lines
     * (DM_CTL, read within 100 ms and applied at the next present: vka_present.c), while the touch mapping takes the same rectangle at once (PictureFit.rect).
     * Without a live control file only the next start changes.
     */
    void picture(boolean stretch, int sizePct) {
        String id = titleId();
        int sz = PictureFit.size(sizePct);
        Prefs.stretch(this, id, stretch);
        Prefs.pictureSize(this, id, sz);
        if (!live()) return;
        try (java.io.FileWriter w = new java.io.FileWriter(runCtl, true)) {
            w.write("env DM_VK_STRETCH=" + (stretch ? 1 : 0) + "\nenv DM_VK_PICTURE_SIZE=" + sz + "\n");
        } catch (IOException e) { Native.note("[RED] picture: the setting could not be sent to the game (" + e.getMessage() + "); it applies at the next start"); return; }
        if (stretch != runStretch || sz != runPictureSize) Native.note("picture: " + (stretch ? "stretched" : "fitted") + " at " + sz + " % (the in-game panel)");
        runStretch = stretch; runPictureSize = sz;
        controls.setPicture(stretch, sz);
    }

    /**
     * The in-game panel's Rumble: kept for this game (the same setting as Game settings › Rumble) and applied at once. Off mutes the listener (the host's listener is
     * set once a process: Native.setRumbleListener), on unmutes it, or starts it when the game started with rumble off
     */
    void rumble(boolean on) {
        Prefs.rumble(this, titleId(), on);
        if (!started) return;
        if (rumble == null && on) {
            Rumble r = new Rumble(this);
            if (r.start()) { rumble = r; for (int p : pads) r.route(p); }
        }
        if (rumble != null) { rumble.mute(!on); Native.note("rumble: " + (on ? "on" : "off") + " (the in-game panel)"); }
    }

    /** The in-game panel's Player 1 choice: the gamepads connected, the one on player 1 (null: none) */
    java.util.List<PadOrder.Pad> gamepads() { return order != null ? order.known() : java.util.Collections.emptyList(); }
    PadOrder.Pad playerOne() { return order != null ? order.first() : null; }
    /** Player 1: this device from now on and at later starts (a descriptor; "" = automatic, PadOrder) */
    void choosePlayerOne(String key) {
        Prefs.padFirst(this, key);
        if (order == null) return;
        order.choose(key);
        PadOrder.Pad one = order.first();
        Native.note("input: player 1 " + (key.isEmpty() ? "automatic" : "chosen") + " (the in-game panel): " + (one != null ? one.name : "no gamepad"));
    }

    /** The in-game panel's Button mapping shortcut: this game as Game settings sees it */
    Game game() {
        android.content.Intent i = getIntent();
        return new Game(titleId(), String.valueOf(i.getStringExtra("name")), String.valueOf(i.getStringExtra("game")), String.valueOf(i.getStringExtra("pack")));
    }

    /**
     * This screen takes over the running game's window (the activity was created again while the game runs). Nothing of start() runs: the save is locked and in use
     * by the host (its recovery must not run beside the host's commits), the DLC links and the log are the first screen's
     */
    private void attach() {
        Native.note("the game's screen opened again; the running game carries on");
        log = null;   // the run's log is the first screen's; this one shows no log
        overlay.setVisibility(View.GONE);
        input = Native.hasInput();
        started = true;
        controls.setPicture(runStretch, runPictureSize);
        hostSetup();
        prewarmTick();   // PEANUTS: restore the progress cover after recreation
        // The host is still stopped (the screen that went sent onStop; nothing runs before onStart). It stays paused when it was (the in-game panel's Pause, or a return
        // not yet continued), and is paused when pause-on-return is on, as a return from the background is: the overlay shows whenever the host is paused, a tap continues
        int hostPaused = Native.paused();
        if (hostPaused < 0) Native.lifecycle(5);   // a host without nxhost_paused: its pause is not known, so it is continued and paused again by the setting alone
        // Pause-on-return only after a real trip to the background: a screen recreated while it showed (a wallpaper colour or theme change) does not pause the game
        if (hostPaused == 1 || hostInBackground && Prefs.pauseOnReturn(this, titleId())) { paused = true; Native.lifecycle(4); pausedView.setVisibility(View.VISIBLE); }
        ui.postDelayed(this::fpsTick, 500);
        pinStart();
        skipStart();
    }

    /** The game's screen was stopped (the game in the background) and not started again; a stop for a configuration change is no trip to the background */
    private static boolean hostInBackground;

    /**
     * A start that cannot go on: the reason, then the activity and its process end, as Stop does. A failed start must not leave an idle :game process: it would hold
     * this game's host (the next Play of another game would land in it, Native.load) and the save's lock
     */
    private void fail(String why) {
        if (dev.peanuts.build.ShaderCapture.active(this)) { dev.peanuts.build.ShaderCapture.failure(this, why); end(); return; }   // PEANUTS: Build owns preparation errors
        new AlertDialog.Builder(this).setTitle("Cannot start " + getIntent().getStringExtra("name")).setMessage(why)
                .setPositiveButton("OK", (d, w) -> end()).setCancelable(false).show();
    }

    private void end() {
        ExitWatch.stopped();
        backToFrontend();
        finish();
        // On its own handler: onDestroy clears ui's callbacks
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    /** A game a frontend started (FrontendLaunch): the app's task goes behind the frontend, so its stop shows the frontend, not Home */
    void backToFrontend() { if (getIntent().getBooleanExtra("frontend", false)) moveTaskToBack(true); }

    /**
     * The screen goes (Stop, or Android recreating the activity while the game runs, which then attaches a new one): what hostSetup registered is let go, and the
     * overlay and log loops stop, so a screen created again does not double them (two deviceAdded per pad, two route resets per change) or keep this one alive.
     * The game itself is left as it is: onStop has suspended it
     */
    @Override protected void onDestroy() {
        runningDisplayMode.clear(this);   // PEANUTS: release this game window's display request
        dev.peanuts.Companion.stop();   // PEANUTS
        TextInput.gone(this);
        UserPicker.gone(this);
        CtrlScreen.gone(this);
        ui.removeCallbacksAndMessages(null);
        if (rumble != null) rumble.stopAll();
        if (padMotion != null) { padMotion.release(); padMotion = null; }
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        if (audio != null) {
            if (focus != null) audio.abandonAudioFocusRequest(focus);
            if (route != null) { audio.unregisterAudioDeviceCallback(route); route = null; }
        }
        super.onDestroy();
    }

    /** Play on another game while this one runs reaches this activity (singleTask, the same :game process): this game stays, and the user is told to stop it first */
    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        String want = i.getStringExtra("title_id"), have = getIntent().getStringExtra("title_id");
        if (want == null || want.equals(have)) return;
        new AlertDialog.Builder(this).setTitle(getIntent().getStringExtra("name") + " is running")
                .setMessage("Stop it first (the in-game panel › Stop the game), then start " + i.getStringExtra("name") + ". One game runs at a time.")
                .setPositiveButton("OK", null).show();
    }

    // ---- The save's lock (SaveStore.Lock on <save>.lock): taken before the save is recovered and kept while this process lives, so a backup or restore of this save
    // never runs beside the game (SaveUi asks SaveStore.inUse); the process ending releases it ----
    private static SaveStore.Lock saveLock;
    private static File saveLocked;

    private static synchronized void holdLock(File save) throws IOException {
        if (saveLock != null && SaveStore.same(saveLocked, save)) return;   // this process holds it already (the game's screen opened again)
        SaveStore.Lock l = SaveStore.tryLock(save);
        if (l == null) throw new IOException("The save " + save + " is in use: a backup or restore of it is working. Try again when it is done.");
        if (saveLock != null) saveLock.close();
        saveLock = l;
        saveLocked = save;
    }

    /** Environment keys only the launcher sets: a line of env.txt, env-<id>.txt or the game's own lines that names one is refused (DM_SAVE: see allowSave) */
    static final java.util.Set<String> PROTECTED = new java.util.HashSet<>(java.util.Arrays.asList("DM_SAVE", "DM_ROMFS", "DM_TEMP", "DM_ROOT", "DM_AOC_DIR", "HOME"));
    private final List<String> refused = new ArrayList<>();
    private String testSave;   // DM_SAVE from env-<id>.txt in the test app: a test run's own save folder (a copied seed), never the user's

    /** lines without the protected keys; each one refused is noted (refused). allowSave: DM_SAVE may stay (the test app's files, which only adb writes) */
    private List<String> guard(List<String> lines, String from, boolean allowSave) {
        List<String> out = new ArrayList<>();
        for (String kv : lines) {
            String k = kv.substring(0, kv.indexOf('=')).trim();
            if (k.equals("DM_SAVE") && allowSave) { testSave = kv.substring(kv.indexOf('=') + 1).trim(); continue; }   // taken as the save folder (locked, recovered) below
            if (PROTECTED.contains(k)) { refused.add(k + " (" + from + ")"); continue; }
            out.add(kv);
        }
        return out;
    }

    static boolean testApp() { return !BuildConfig.NX_PLAY; }

    private static File mkdirs(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        return d;
    }

    /**
     * DM_AOC_DIR/<index> → each DLC's RomFS folder in the game folder, or DM_AOC_DIR/<index>.nup → the .nup (hle_fs.c aoc_dir; Pack.aocLinks), as symbolic links in
     * app storage. A .nup used where it is (nupFd ≥ 0): the link's target is fd:<n>/<name>, which the host reads with readlink (nothing is at that path).
     * A link that cannot be made, or a pack.tsv aoc row whose DLC the game's folder or .nup has not got (Pack.aocLinks skipped), is a [RED] line in the run's
     * log (aocProblems): the game runs without that DLC
     */
    private File aocLinks(File dir, File game, Pack pack, int nupFd) {
        List<String> skipped = new ArrayList<>();
        Map<String, String> links = pack.aocLinks(game, skipped);
        for (String p : skipped) aocProblems.add("[RED] [nxlaunch] DLC: " + p);
        for (String p : Pack.makeAocLinks(dir, links, t -> nupFd >= 0 ? PickedPath.forHost(t, game, nupFd) : t, (target, path) -> {
            try { Os.symlink(target, path); } catch (ErrnoException e) { throw new IOException(android.system.OsConstants.errnoName(e.errno) + " (" + e.getMessage() + ")"); }
        })) aocProblems.add("[RED] [nxlaunch] DLC: " + p);
        aocLine = "[nxlaunch] DLC: " + links.size() + " link" + (links.size() == 1 ? "" : "s") + " in DM_AOC_DIR " + dir + (links.isEmpty() ? "" : " " + links.keySet());
        return dir;
    }
    private final List<String> aocProblems = new ArrayList<>();
    private String aocLine;

    // A file `adb shell` writes here belongs to the shell user with mode 0660 and group ext_data_rw, which the app is not in: it exists but cannot be read
    // (TESTING.md, Diagnostic switches: chmod 666 after writing it). The run goes on without the switches and the log says why
    private String envProblem;
    private List<String> userEnv(File f) {
        if (!f.exists()) return new ArrayList<>();
        try {
            return envLines(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | SecurityException e) {
            envProblem = f + " exists but cannot be read (" + e + "): no switches from it this run; after writing it with adb, chmod 666 it";
            return new ArrayList<>();
        }
    }

    /** KEY=VALUE lines; blank lines and # comments skipped */
    static List<String> envLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#") && l.indexOf('=') > 0) out.add(l);
        }
        return out;
    }

    /** The newest KEEP_LOGS logs of this game are kept (each game's logs are <title ID>-<time>.log: one game's runs never push another's out) */
    private void pruneLogs(String id) {
        File[] logs = LogProvider.dir(this).listFiles((d, n) -> n.startsWith(id + "-") && n.endsWith(".log"));
        if (logs == null || logs.length < KEEP_LOGS) return;
        Arrays.sort(logs, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i <= logs.length - KEEP_LOGS; i++) { logs[i].delete(); ExitWatch.digestOf(logs[i]).delete(); }   // and its digest (the notice's), if any
    }

    /** The last lines of the log over the surface, once a second: until the game's first frame, then only with the setting on (TouchControls.showLog), or after a crash */
    private boolean drawing, failed;
    private void tail() {
        if (log == null) return;
        if (stopped || paused) { ui.postDelayed(this::tail, 2000); return; }   // the log does not move while the game is suspended
        try (RandomAccessFile r = new RandomAccessFile(log, "r")) {
            long n = r.length(), from = Math.max(0, n - 2048);
            byte[] b = new byte[(int) (n - from)];
            r.seek(from);
            r.readFully(b);
            String text = new String(b, StandardCharsets.UTF_8);
            if (text.contains("[nvn] frame ") || text.contains("[longframe]") || text.contains("window frame rate")) drawing = true;   // the host logs these once frames are presented
            if (text.contains("CRASH sig=") || text.contains("FATAL")) failed = true;
            overlay.setVisibility(!drawing || failed || TouchControls.showLog(this) ? View.VISIBLE : View.GONE);
            String[] lines = text.split("\n");
            StringBuilder s = new StringBuilder();
            for (int i = Math.max(from > 0 ? 1 : 0, lines.length - 8); i < lines.length; i++) s.append(lines[i]).append('\n');
            overlay.setText(s.toString().trim());
        } catch (IOException ignored) { /* next time */ }
        ui.postDelayed(this::tail, 1000);
    }

    // ---- The host's side of the Activity: input devices, audio focus and route, motion, lifecycle ----
    private void hostSetup() {
        presentationDeadlineUpdate();   // PEANUTS: publish the initial display deadline once the host is ready
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(this, ui);
        padMotion = new PadMotion();
        order = new PadOrder(new PadOrder.Host() {
            @Override public int add(PadOrder.Pad p) { int pl = Native.deviceAdded(p.id, p.name, false); if (pl >= 0) padConnected(p.id); return pl; }
            @Override public int first(PadOrder.Pad p) {
                int pl = Native.deviceFirst(p.id, p.name);
                if (pl == 0 && rumble != null) rumble.reroute(pads);   // two players traded devices: each gets its device's vibrator
                return pl;
            }
            @Override public int player(int id) { return Native.devicePlayer(id); }
        }, Prefs.padFirst(this));
        ui.postDelayed(padPlayers, 1000);
        java.util.List<PadOrder.Pad> now = new java.util.ArrayList<>();
        for (int id : inputManager.getInputDeviceIds()) { InputDevice d = InputDevice.getDevice(id); if (gamepadLike(d)) now.add(pad(d)); }
        order.start(now);   // best first: an external controller is player 1 before a phone's own gamepad-like device (PadOrder)
        Native.motionRotation(getDisplay().getRotation());
        if (resumedNow && !paused) { Native.motionStart(); padMotion.resume(); }   // the screen was resumed before the game started (onResume then did nothing): the sensors start here
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setOnAudioFocusChangeListener(f -> Native.audioInterrupt(focusState.changed(f)), ui)
                .build();
        // Granted at once calls no listener: an interruption the screen that went saw (a call while in the background) is over for this one
        if (requestFocus()) Native.audioInterrupt(false);
        else Native.note("audio focus not granted at start");
        // The callback first reports the devices already there: only later changes reopen the streams
        route = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { if (routeReady) Native.audioRouteChanged(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { if (routeReady) Native.audioRouteChanged(); }
        };
        // Off the UI thread: the call lists the audio ports in system_server and can take seconds (issue 251: ANR in AudioSystem.listAudioPatches)
        final AudioDeviceCallback cb = route;
        Thread rt = new Thread(() -> {
            audio.registerAudioDeviceCallback(cb, ui);
            if (route != cb) audio.unregisterAudioDeviceCallback(cb);   // the screen went meanwhile
            else ui.post(() -> routeReady = true);
        }, "audio-route");
        rt.start();
    }

    // ---- Paused: the game suspended while its screen shows (nxhost_lifecycle 4 / 5); on its return from the background with pause-on-return on (the game's setting, on by
    // default), and by the in-game panel's Pause. The host keeps it suspended (clock, graphics, guest threads, audio, focus Background) until unpause() ----
    private boolean paused, stopped;
    private TextView pausedView;

    private String titleId() { return String.valueOf(getIntent().getStringExtra("title_id")); }

    /**
     * Once per install: how the in-game panel opens, now that it has no visible button (a pill at the right edge for 8 s, touches pass through it). Settings ›
     * Controller: in-game panel says the same
     */
    private void panelHint(FrameLayout root) {
        if (Prefs.panelHinted(this)) return;
        Prefs.panelHinted(this, true);
        int how = Prefs.panelPad(this);
        String pad = how == PadPanel.OPEN_OFF ? "" : how == PadPanel.OPEN_HOME ? "\nController: press Home" : how == PadPanel.OPEN_MINUS_PLUS ? "\nController: hold − and +"
                : "\nController: press Home, or hold − and +";
        TextView h = new TextView(this);
        h.setText("Menu: swipe in from the right edge, or press Back" + pad);
        h.setTextColor(Color.WHITE);
        h.setTextSize(14);
        float dp = getResources().getDisplayMetrics().density;
        int p = Math.round(10 * dp);
        h.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(10 * dp);
        h.setBackground(bg);
        h.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = Math.round(28 * dp);
        root.addView(h, root.indexOfChild(panel), lp);
        h.postDelayed(() -> h.animate().alpha(0).setDuration(600).withEndAction(() -> root.removeView(h)).start(), 8000);
    }

    /**
     * The panel opens over the running game: every pad button the host holds is released and every stick and trigger centred, so the game sees no press stuck
     * while the panel takes the controller (their releases go to the panel)
     */
    private void releaseGameInput() {
        if (!input) return;
        for (Map.Entry<Integer, HashSet<Integer>> d : gameKeys.entrySet()) for (int k : d.getValue()) Native.key(d.getKey(), k, false);
        gameKeys.clear();
        for (Map.Entry<Integer, int[]> d : deviceAxes.entrySet())
            if (order != null && order.plays(d.getKey()) && d.getValue().length > 0) Native.axes(d.getKey(), d.getValue(), new float[d.getValue().length]);
    }

    /** The in-game panel's Pause */
    void pause() {
        if (!started || paused) return;
        paused = true;
        Native.lifecycle(4);
        Native.motionStop();
        if (padMotion != null) padMotion.pause();
        if (rumble != null) rumble.stopAll();
        pausedView.setVisibility(View.VISIBLE);
    }

    private void unpause() {
        if (!paused) return;
        paused = false;
        pausedView.setVisibility(View.GONE);
        Native.lifecycle(5);
        if (resumedNow) { Native.motionStart(); if (padMotion != null) padMotion.resume(); }
    }

    /**
     * The orientation request, by the display's size (GameOrientation): sensorLandscape from the manifest on a phone; none on a large screen (a tablet, a foldable's inner
     * screen), where the system does not turn the display for the request but letterboxes the landscape-locked window inside the portrait-shaped display (the bottom
     * 346 px of a 2172x2352 inner screen showed the wallpaper). Without a request the window is the whole display and the present fits the 16:9 picture into it, so a
     * resize (the window's size changes, surfaceChanged) stands in for the rotation. Not resizeableActivity, minAspectRatio or the compat-override properties: the window
     * is already resizable (targetSdk 35), and these only change which part of the display the system gives a window that asks for an orientation, not the request
     */
    private void fitOrientation() {
        int want = orientationFixed() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }
    /** Whether the window asks for landscape (GameOrientation): Settings › Screen rotation Landscape, on a phone; Automatic asks for nothing */
    private boolean orientationFixed() {
        // DeX or another external display (PR #73): the activity's own display is not the default one, so the desktop sizes the window and the monitor's orientation decides.
        // The activity's display is what counts: screen recording, casting and virtual displays appear in DisplayManager.getDisplays() and must not unlock the phone.
        android.view.Display d = getDisplay();
        if (d != null && d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return false;
        android.graphics.Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
        return GameOrientation.fixedLandscape(b.width(), b.height(), getResources().getDisplayMetrics().density, Prefs.rotationAuto(this));
    }

    /** Full screen: the status and navigation bars (and the swipe handle) are hidden, a swipe from the edge shows them for a moment; the picture also runs under a display cutout */
    private void immersive() {
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(android.view.WindowInsets.Type.systemBars());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();   // the bars come back after a dialog, the keyboard or the notification shade
        else combo.reset();   // a dialog or the background: the combination's keys may come up elsewhere
        // Gamepad sticks, triggers and a DualShock's d-pad (hat axes) come as joystick motion events, which Android batches by default and hands over at the window's next
        // frame (Choreographer): up to one display refresh late (16.7 ms at 60 Hz), later when the main thread is busy then. Unbuffered, each reaches onGenericMotionEvent
        // and the host (Native.axes) as it arrives; the game reads it at its next input read. Button presses (key events) were never batched. Asked of the window's root
        // once it is attached (focus), so its ViewRootImpl takes it (View.requestUnbufferedDispatch(int), API 30)
        if (hasFocus) getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!started) return;
        // back from the background: stays suspended (4 before 2, so the host never lets it run in between) until the tap
        if (stopped && !paused && Prefs.pauseOnReturn(this, titleId()) && !getIntent().getBooleanExtra("no_pause", false)) {
            paused = true; Native.lifecycle(4);
            if (!controls.editing()) pausedView.setVisibility(View.VISIBLE);   // the layout editor stays usable; its Done shows Paused (onEditEnd)
        }
        stopped = false;
        hostInBackground = false;
        dev.peanuts.Companion.back(this);   // PEANUTS: the companion returns to the second display with the game (it left at onStop)
        // Another app took the audio focus for good while the game was away (AudioFocus): no gain would come, and the game stayed silent. Asked for again here
        if (audio != null && focus != null && focusState.askAgain()) {
            if (requestFocus()) { Native.note("audio focus asked for again on the return: granted"); Native.audioInterrupt(false); }
            else Native.note("audio focus asked for again on the return: refused");
        }
        Native.lifecycle(2);
    }
    @Override protected void onResume() {
        super.onResume();
        resumedNow = true;   // also before the start: start() can come after the first onResume (the readiness check), and hostSetup() then starts the sensors
        if (!started) return;
        Native.lifecycle(3);
        Native.motionRotation(getDisplay().getRotation());
        if (!paused) { Native.motionStart(); if (padMotion != null) padMotion.resume(); }   // paused: the sensors stay off (they would wake the host at their rate for a frozen game); unpause starts them
    }
    private boolean resumedNow;
    @Override protected void onPause() {
        runningDisplayMode.clear(this);   // PEANUTS: a background activity keeps no display request
        resumedNow = false;
        if (started) { Native.motionStop(); if (padMotion != null) padMotion.pause(); Native.lifecycle(0); }
        super.onPause();
    }
    @Override protected void onStop() {
        if (started) { stopped = true; if (!isChangingConfigurations()) hostInBackground = true; Native.lifecycle(1); }
        if (!isChangingConfigurations()) dev.peanuts.Companion.away();   // PEANUTS: Home or another app is in front: the companion's window leaves the second display (a Presentation outlives its activity's stop)
        super.onStop();
    }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fitOrientation();   // a fold or an unfold changes the display: the other screen may want the other answer
        if (started) Native.motionRotation(getDisplay().getRotation());
    }

    @Override public void surfaceCreated(SurfaceHolder h) { Native.setSurface(h.getSurface()); }
    // The replay letterboxes the game's 16:9 picture into the window (vka_present.c); TouchControls gives the host that rectangle (nxhost_touch_layout) with its own size
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) { Native.setSurface(h.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { Native.setSurface(null); }

    private static boolean gamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /** Gamepads and joysticks only: a keyboard with arrow keys (SOURCE_DPAD) gets a player from its first pad key, if it ever sends one (PadOrder.active) */
    private static boolean gamepadLike(InputDevice d) {
        return d != null && !d.isVirtual() && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
    /** What PadOrder ranks a device by: external, gamepad buttons with a stick, a vendor ID; never its name */
    private static PadOrder.Pad pad(InputDevice d) {
        boolean[] keys = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B);
        boolean full = keys[0] && keys[1] && d.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null
                && d.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null;
        return new PadOrder.Pad(d.getId(), d.getDescriptor(), d.getName(), d.isExternal(), full, d.getVendorId());
    }
    /** A pad key that gives a device without a player one (the keys the host takes: gamepad buttons and the D-pad) */
    private static boolean padKey(int code) {
        return KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    /**
     * The host moves a controller to another player when the game does not read its own (input_android.c fill: after a connect or a disconnect, when the game's supported
     * npads change, when its controller applet opens), so the players are looked at once a second and the rumble follows them
     */
    private final java.util.HashMap<Integer, Integer> padPlayer = new java.util.HashMap<>();
    private final Runnable padPlayers = new Runnable() {
        @Override public void run() {
            boolean moved = false;
            for (int id : pads) { int p = Native.devicePlayer(id); Integer was = padPlayer.put(id, p); if (was != null && was != p) moved = true; }
            padPlayer.keySet().retainAll(pads);
            if (moved && rumble != null) rumble.reroute(pads);
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onInputDeviceAdded(int id) {
        InputDevice d = InputDevice.getDevice(id);
        if (order != null && gamepadLike(d)) order.connected(pad(d));
        deviceAxes.remove(id);
    }
    @Override public void onInputDeviceRemoved(int id) {
        Native.deviceRemoved(id); deviceAxes.remove(id);
        if (padMotion != null) padMotion.detach(id);
        if (order != null) order.removed(id);
        if (pads.remove(id)) controls.setGamepad(!pads.isEmpty());
    }
    private void padConnected(int id) {
        if (!pads.add(id)) return;
        controls.setGamepad(true);
        if (rumble != null) rumble.route(id);
        if (padMotion != null) padMotion.attach(id);
    }
    @Override public void onInputDeviceChanged(int id) { deviceAxes.remove(id); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (dev.peanuts.build.ShaderCapture.active(this) && gamepad(e.getSource())) return true;   // PEANUTS: no gameplay input during preparation
        if (prewarmCover != null && gamepad(e.getSource())) return super.dispatchKeyEvent(e);   // PEANUTS: Preparing shaders: the pad moves over its buttons; nothing goes to the game or the panel under it
        if (panel == null) return super.dispatchKeyEvent(e);   // no host for this game (onCreate): only the message is shown
        // A controller opens and closes the in-game panel (PadPanel.Combo: Home, or − and + held; Settings › Controller: in-game panel); while it is open the pad moves
        // through it and the game gets no pad keys. A key the panel took a press of stays the panel's until it comes up
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
        if (gamepad(e.getSource()) || k == KeyEvent.KEYCODE_BUTTON_MODE) {
            int c = combo.key(k, down, e.getRepeatCount(), e.getEventTime());
            if (c == PadPanel.TOGGLE) { panel.padToggle(); return true; }
            if (c == PadPanel.EAT) { if (up) panelKeys.remove(k); return true; }   // the pair's release after it fired (the panel may have taken its press)
            long due = combo.due();
            ui.removeCallbacks(comboHeld);
            if (due >= 0) ui.postAtTime(comboHeld, due);   // the event's time is uptimeMillis
        }
        if (panel.isOpen() && gamepad(e.getSource())) {
            boolean took = panel.padKey(e);
            if (took && down) panelKeys.add(k);
            if (up) panelKeys.remove(k);
            return took || super.dispatchKeyEvent(e);
        }
        if (gamepad(e.getSource()) && panelKeys.contains(k)) { if (up) panelKeys.remove(k); return true; }
        if (paused && gamepad(e.getSource())) {   // paused: A or Start continues; no pad key reaches the game
            if (e.getAction() == KeyEvent.ACTION_UP && (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A || e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START)) unpause();
            return true;
        }
        // Back from the phone and keyboard keys stay with the launcher: the host takes only pad buttons
        // A device without a player gets one from its first pad key (PadOrder.active: a phone's own gamepad-like device that never sends one takes none)
        if (input && gamepad(e.getSource()) && e.getDevice() != null && !e.getDevice().isVirtual()
                && (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP)
                && (order.plays(e.getDeviceId()) || e.getAction() == KeyEvent.ACTION_DOWN && padKey(e.getKeyCode()) && order.active(pad(e.getDevice())))
                && Native.key(e.getDeviceId(), e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN)) {
            HashSet<Integer> held = gameKeys.computeIfAbsent(e.getDeviceId(), x -> new HashSet<>());
            if (down) held.add(k); else held.remove(k);
            padConnected(e.getDeviceId());
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // The axes the host reads (input_map.h), and per device the ones it has (InputDevice.getMotionRange): absent axes would read 0 and pull a stick or trigger back
    private static final int[] AXES = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE};
    private final HashMap<Integer, int[]> deviceAxes = new HashMap<>();
    private final float[] axisValues = new float[AXES.length];

    private int[] axesOf(InputDevice d, int source) {
        int[] have = deviceAxes.get(d.getId());
        if (have != null) return have;
        int n = 0;
        int[] tmp = new int[AXES.length];
        for (int a : AXES) if (d.getMotionRange(a, source) != null) tmp[n++] = a;
        have = Arrays.copyOf(tmp, n);
        deviceAxes.put(d.getId(), have);
        return have;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (dev.peanuts.build.ShaderCapture.active(this)) return true;   // PEANUTS: no gameplay input during preparation
        if (panel != null && panel.isOpen() && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return panel.padMotion(e);   // the panel's, never the game's
        if (!input || panel.isOpen() || paused || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE || e.getDevice() == null)
            return super.onGenericMotionEvent(e);
        int[] have = axesOf(e.getDevice(), e.getSource());
        for (int i = 0; i < have.length; i++) axisValues[i] = e.getAxisValue(have[i]);
        // A device without a player gets one when a stick or trigger is pushed past half (PadOrder.wakes), not from a resting stick's noise
        if (!order.plays(e.getDeviceId()) && !(gamepadLike(e.getDevice()) && PadOrder.wakes(axisValues, have.length) && order.active(pad(e.getDevice()))))
            return super.onGenericMotionEvent(e);
        return Native.axes(e.getDeviceId(), have, axisValues) || super.onGenericMotionEvent(e);   // the host reads have.length values
    }


    /** Back (the phone's button or its edge swipe) opens and closes the in-game panel, which has Stop; while the controller applet's join screen shows, it cancels that */
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (prewarmCover != null) return; /* PEANUTS: Preparing shaders has its own Close; the panel is under it */ if (dev.peanuts.build.ShaderCapture.active(this)) { dev.peanuts.build.ShaderCapture.cancel(this); return; } /* PEANUTS: Back cancels preparation */ if (panel != null && !panel.isOpen() && CtrlScreen.back()) return; if (panel != null) panel.toggle(); else end(); }

    // For CtrlScreen: the view tree it goes into (under the in-game panel), and whether a gamepad is connected (then the on-screen controls are hidden and cannot join)
    FrameLayout rootView() { return rootView; }
    View panelView() { return panel; }
    boolean gamepadConnected() { return !pads.isEmpty(); }
}
