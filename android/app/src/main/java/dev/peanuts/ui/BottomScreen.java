package dev.peanuts.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

import dev.nxnative.android.R;
import dev.peanuts.Companion;
import dev.peanuts.PeanutsPrefs;

/**
 * The launcher on a second display: the focused cover and its actions, else the logo.
 * Shown while MainActivity is started and hidden while a game runs.
 */
public final class BottomScreen {
    static final String TAG = "peanuts";
    private static final String MAIN = "dev.nxnative.android.MainActivity";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final long LAUNCH_STUCK_MS = 5000, LAUNCH_DIALOG_MS = 300, HANDOFF_MAX_MS = 8000, COVERED_MS = 150;

    /** A cover's card (Library.bottom) */
    static final class Card {
        static final int K_READY = 0, K_BUILD = 1, K_NO_PROFILE = 2, K_BUILDING = 3;
        String titleId = "", name = "";
        Bitmap icon;
        int kind;
        int pos = -1, cols = 1;         // place in the grid: the slide follows the D-pad
        String state = "";              // the cover's own words
        String companion;               // ready: the companion's version, null if none
        long played;                    // ready: last Play, 0 if never
        dev.peanuts.launch.PlayLedger.Entry play;   // ready: its play time (dev.peanuts.launch.PlayTime), null if never played with this app
        String update;                  // ready: a newer update or add-ons beside its .nup (UpdateFound), null if none
        String found = "", space = "";  // to build: what the scan found
        boolean queued;                 // to build: it waits in the build queue (its state is its place, in the cover's quiet pill)
        Runnable primary, second;       // A and Y, null if none
        String primaryLabel = "", secondLabel = "";
        int primaryIcon;
    }

    /** A running or stopped build (Library.buildInfo) */
    static final class BuildInfo {
        String titleId = "", name = "", body = "";
        CharSequence step = "";
        Bitmap icon;
        int percent; boolean failed, indeterminate;
        Runnable stop, screensOff, open;
    }

    /** Settings: the row or the section the focus is on, explained (SettingsPage) */
    public static final class Explain {
        public String icon;                  // a Peanuts icon's name (Icons), null: none
        public String title = "", place = "", text = "";
        public CharSequence value;           // a row's value line as it is now; null for a section
        public String deflt;                 // the app default's words; null: none to name
        public Boolean on;                   // a switch row's state; null: not a switch
        public Runnable flip;                // a switch row: flips it on the top screen
        public java.util.List<CharSequence[]> rows;   // a section: its rows' titles and values; null for a row
        public Object key;                   // what is explained (an equal key: updated in place, no fade)
        public java.util.function.Function<android.content.Context, View> view;   // a view of its own instead (built with this display's context); the fields above unused
        public long cracked = -1;            // About: Peanuts cracked, shown big (its own page); -1: not About
        public java.util.List<String[]> keys;   // the controller's keys for what the focus is on ({key, what it does}; the label "" for a key that shares the next one's), shown only while a controller is connected; null: none
    }

    /** Play pressed: the panel stays until the game says what it shows there (Companion.STATE) */
    private static final class Launch {
        String titleId, name;
        Bitmap icon;
        boolean companion, lostFocus;   // companion: the start page shows
        String said;                    // loading, playing, shown, free, none, stopped
    }

    private static Activity owner;          // the MainActivity
    private static int activeGameDisplay = Display.INVALID_DISPLAY;
    private static BottomPanel panel;
    private static DisplayManager dm;
    private static DisplayManager.DisplayListener displays;
    private static boolean watching, started, resumed;
    private static Runnable openSettings;   // null off Home
    private static Card card;
    private static BuildInfo building;
    private static Explain explain;   // Settings: what the focus is on
    private static long lastExplainAt;
    private static Runnable pendingExplain;
    private static Launch launch;
    private static long lastCardAt;
    private static Runnable pendingCard;
    private static Activity focusOwner;
    private static boolean ownerFocused = true;
    private static boolean setup;                               // first run: no way around it to Settings
    private static final Set<String> pages = new HashSet<>();  // the app's other pages in front (AppScreens)
    private static Activity popsOf;                             // the MainActivity that counts a popped peanut
    private static Runnable pops;

    private BottomScreen() {}

    /** A face button's glyph as the bottom screen draws it (Settings' X): the key in the page's colour on an accent disc */
    static Drawable keyGlyph(Context c, String key, int diameterDp) {
        return new BottomPanel.KeyGlyph(c, key, PUi.col(c, R.color.bn_accent), PUi.col(c, R.color.bn_bg), diameterDp);
    }

    public static Drawable settingsGlyph(Context c, int diameterDp) {
        return new BottomPanel.KeyGlyph(c, "X", PUi.col(c, R.color.bn_accent), PUi.col(c, R.color.bn_bg), diameterDp);
    }

    // ---- Hooks ----

    /** MainActivity.buildHome (PEANUTS hook) */
    public static void home(Activity a, Runnable settings) {
        SettingsPage.homeBuilt();   // a tablet's Settings opens on its tiles again
        dev.peanuts.mods.CheatsPage.left();   // Home shows: a game's Mods & Cheats is closed
        GamePage.left();   // and a game's page opens on its tiles again
        watch(a);
        openSettings = settings;
        explain = null;
        watchFocus(a);
        UI.post(BottomScreen::show);   // back from Settings or a game's page: the Settings hint again
    }

    /** Losing window focus to a dialog dims the panel. */
    private static void watchFocus(Activity a) {
        if (focusOwner == a) return;
        focusOwner = a;
        View decor = a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(f -> {
            if (a != owner) return;
            ownerFocused = f;
            Launch l = launch;
            if (l != null) {   // the start page is not dimmed
                if (!f) { l.lostFocus = true; UI.removeCallbacks(launchDialog); UI.postDelayed(launchDialog, LAUNCH_DIALOG_MS); }
                else if (l.lostFocus && started) launchCancelled();   // a dialog came and went: no game
                return;
            }
            if (panel != null) panel.dim(!f && resumed);   // a dialog; another page of the app pauses this one first
        });
    }

    /** MainActivity.onCreate (PEANUTS hook): what a peanut popped on this screen does, the same as the top screen's (count, Settings' line) */
    public static void onPop(Activity a, Runnable counted) { popsOf = a; pops = counted; }

    /** BottomPanel: a peanut popped */
    static void popped() {
        Activity a = popsOf;
        if (a != null && pops != null && !a.isFinishing() && !a.isDestroyed()) pops.run();
    }

    /** Off Home (Settings, a game's page): the logo */
    static void page(Activity a) {
        if (a != owner) return;
        openSettings = null;
        card = null; building = null; explain = null;
        show();
    }

    /**
     * Settings (SettingsPage): the row or section the focus is on, on this display; null: the logo again. While the D-pad is held only the one it stops on is
     * shown, as the covers on Home
     */
    public static void explain(Activity a, Explain e) {
        if (a != owner) return;
        if (pendingExplain != null) { UI.removeCallbacks(pendingExplain); pendingExplain = null; }
        Explain was = explain;
        if (e != null && was != null && e.key != null && e.key.equals(was.key)) {   // the same row, its value changed: in place
            explain = e;
            if (showing() && (panel.page() == BottomPanel.EXPLAIN || panel.page() == BottomPanel.VIEW)) panel.showExplain(e);
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean rested = now - lastExplainAt > 250;
        lastExplainAt = now;
        pendingExplain = () -> { pendingExplain = null; explain = e; show(); };
        if (rested) pendingExplain.run(); else UI.postDelayed(pendingExplain, 90);
    }

    static boolean showing() { return panel != null && panel.isShowing(); }

    static boolean settings() {
        if (!showing() || openSettings == null || launch != null || setup) return false;
        openSettings.run();
        return true;
    }

    /** A cover got focus; while the D-pad is held, only the one it stops on is shown */
    static void focus(Card c, BuildInfo b) {
        if (c == null || owner == null || launch != null) return;
        if (pendingCard != null) UI.removeCallbacks(pendingCard);
        long now = SystemClock.uptimeMillis();
        boolean rested = now - lastCardAt > 250;
        lastCardAt = now;
        pendingCard = () -> { pendingCard = null; setup = false; card = c; building = c.kind == Card.K_BUILDING ? b : null; show(); };
        if (rested) pendingCard.run(); else UI.postDelayed(pendingCard, 90);
    }

    /** The shown card's total play time read again (Library.playTimesAgain, Home's resume after a game): its line is written again when it changed */
    static void playTime(String titleId, dev.peanuts.launch.PlayLedger.Entry e) {
        Card c = card;
        if (c == null || c.kind != Card.K_READY || !c.titleId.equalsIgnoreCase(titleId) || e == null) return;
        if (c.play != null && c.play.ms == e.ms && c.play.lastRun == e.lastRun) return;
        c.play = e;
        if (launch == null) show();
    }

    /** Build progress; null: the build is gone */
    static void build(BuildInfo b) {
        Card c = card;
        if (b == null) { if (c != null && c.kind == Card.K_BUILDING) { card = null; building = null; show(); } return; }
        if (c != null && c.kind == Card.K_BUILDING && b.titleId.equalsIgnoreCase(c.titleId)) {
            BuildInfo was = building;
            building = b;
            if (launch == null && was != null && panel != null && panel.isShowing() && panel.page() == BottomPanel.BUILD) { panel.updateBuild(b); return; }
            show();
        }
    }

    /** Nothing focused: the logo; first run without the Settings hint */
    static void idle(boolean firstRun) {
        setup = firstRun;
        if (pendingCard != null) { UI.removeCallbacks(pendingCard); pendingCard = null; }
        card = null; building = null;
        show();
    }

    /** The grid changed (a filter, Find): a game no longer in it leaves the bottom screen too */
    static void only(Set<String> ids) {
        Card c = card;
        if (c != null && !ids.contains(c.titleId)) idle(setup);
    }

    /** A cover's art landed */
    static void icon(String titleId, Bitmap b) {
        if (card != null && card.titleId.equals(titleId)) card.icon = b;
        if (building != null && building.titleId.equals(titleId)) building.icon = b;
        if (launch != null && launch.titleId.equals(titleId)) launch.icon = b;
        if (showing()) panel.icon(titleId, b);
    }

    // ---- Play ----

    /** Library.play, before the launch. With a companion the cover grows into the start page; without, the card stays */
    static void launching(String titleId, String name, Bitmap icon, boolean companion) {
        if (!showing() || !started) return;
        Launch l = new Launch();
        l.titleId = titleId; l.name = name; l.icon = icon; l.companion = companion;
        launch = l;
        if (pendingCard != null) { UI.removeCallbacks(pendingCard); pendingCard = null; }
        if (companion) { panel.dim(false); panel.showLaunch(titleId, name, icon, "Starting the game"); }
        UI.removeCallbacks(launchStuck);
        UI.postDelayed(launchStuck, LAUNCH_STUCK_MS);
    }

    /** The launch did not start a game: back to the card */
    static void launchCancelled() {
        if (launch == null) return;
        launch = null;
        UI.removeCallbacks(launchStuck);
        UI.removeCallbacks(launchDialog);
        UI.removeCallbacks(launchEnd);
        show();
        if (panel != null) panel.dim(!ownerFocused);
    }

    /** Still in front a while after Play (an error, a toast): no game is coming */
    private static final Runnable launchStuck = () -> { if (launch != null && started) launchCancelled(); };
    /** Focus gone but still resumed: a dialog, not the game (a game pauses the launcher first) */
    private static final Runnable launchDialog = () -> { if (launch != null && resumed && !ownerFocused) launchCancelled(); };
    /** The game never said: let go */
    private static final Runnable launchEnd = BottomScreen::handOff;

    /** The game is in front and has said what it shows on this display: covered, or left to Android */
    private static void handOff() {
        Launch l = launch;
        if (l == null) return;
        launch = null;
        UI.removeCallbacks(launchEnd);
        UI.removeCallbacks(launchStuck);
        UI.removeCallbacks(launchDialog);
        if (started || !showing()) return;
        boolean covered = "loading".equals(l.said) || "playing".equals(l.said) || "shown".equals(l.said);
        if (covered) UI.postDelayed(() -> { if (!started && launch == null) hide(); }, COVERED_MS);   // its window has drawn by then
        else leave();
    }

    /** The launcher left the front: the page fades, then the display is Android's */
    private static void leave() {
        if (!showing()) return;
        BottomPanel p = panel;
        p.fadeOut(() -> { if (panel != p) return; if (!inApp()) hide(); else p.appear(); });   // back already: fade in again
    }

    /** Home or another page of the app in front */
    static boolean inApp() { return started || !pages.isEmpty(); }

    /** Left the app, unless one of its pages in another process says it is in front within a moment */
    private static final Runnable leaveLater = () -> { if (!inApp() && launch == null) leave(); };
    private static void leaveSoon() { UI.removeCallbacks(leaveLater); UI.postDelayed(leaveLater, 300); }

    /** From the app's pages in other processes (AppScreens) */
    private static final BroadcastReceiver pageState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String k = i.getStringExtra("page");
            if (k == null) return;
            if (i.getBooleanExtra("front", false)) { pages.add(k); show(); }
            else { pages.remove(k); if (!inApp()) leaveSoon(); }
        }
    };

    /** From the game's process (Companion.tell) */
    private static final BroadcastReceiver companionState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String state = i.getStringExtra("state");
            int previous = activeGameDisplay;
            activeGameDisplay = "stopped".equals(state) || "away".equals(state)
                    ? Display.INVALID_DISPLAY : i.getIntExtra("game_display", Display.INVALID_DISPLAY);
            if (previous != activeGameDisplay) show();
            Launch l = launch;
            if (l == null) return;
            l.said = state;
            if (!started) handOff();
        }
    };

    // ---- The panel ----

    private static void show() {
        Activity a = owner;
        if (a == null || !inApp() || a.isFinishing() || a.isDestroyed() || !PeanutsPrefs.bottomScreen(a)) { if (launch == null) hide(); return; }
        UI.removeCallbacks(leaveLater);
        Display d = panel != null && panel.getDisplay() != null && panel.getDisplay().isValid() ? panel.getDisplay() : secondDisplay(a);
        if (d == null) { hide(); return; }
        if (d.getDisplayId() == activeGameDisplay) { hide(); return; }
        if (panel != null && (panel.getDisplay() == null || panel.getDisplay().getDisplayId() != d.getDisplayId())) { dismiss(); }
        boolean was = showing();
        if (panel == null) {
            try { panel = new BottomPanel(a, d); }
            catch (RuntimeException e) { Log.w(TAG, "bottom screen: no window on display " + d.getDisplayId() + ": " + e); panel = null; return; }
            panel.dim(!ownerFocused && launch == null);
            panel.setPad(Pads.connected(a));
            BackgroundFlow.stale();
            BottomPanel made = panel;
            // cancelled by Android (display change): make a new one
            made.setOnDismissListener(x -> { if (panel == made) { panel = null; UI.post(BottomScreen::show); } });
        }
        try {
            if (!panel.isShowing()) panel.show();
        } catch (RuntimeException e) {   // display refuses app windows, or just went away
            Log.w(TAG, "bottom screen not shown on display " + d.getDisplayId() + ": " + e);
            dismiss();
            return;
        }
        panel.setActive(on(d));
        BackgroundFlow.field(a.getDisplay(), d);
        Launch l = launch;
        if (l != null && l.companion) panel.showLaunch(l.titleId, l.name, l.icon, "Starting the game");
        else if (started && building != null && card != null && card.kind == Card.K_BUILDING) panel.showBuild(building);
        else if (started && card != null && openSettings != null) panel.showCard(card);
        else if (started && explain != null && openSettings == null) panel.showExplain(explain);
        else panel.showLogo(started && openSettings != null && !setup);
        if (!was) panel.appear();
    }

    /** Hidden, not dismissed: a new window flashes black */
    private static void hide() {
        if (panel != null && panel.isShowing()) { panel.setActive(false); try { panel.hide(); } catch (RuntimeException ignored) { } }
        BackgroundFlow.clear();
    }

    private static void dismiss() {
        if (panel != null) { try { panel.dismiss(); } catch (RuntimeException ignored) { } panel = null; }
        BackgroundFlow.clear();
    }

    private static boolean on(Display d) { return d.getState() != Display.STATE_OFF; }

    /** Settings › Screens › Bottom screen */
    static void settingChanged() {
        if (owner == null) return;
        if (PeanutsPrefs.bottomScreen(owner)) show(); else dismiss();
    }

    /** The device's own second screen (the Thor's Screen-2), never a TV, a monitor or a cast (dev.peanuts.Screens, #358: a phone mirroring to a TV showed this page there) */
    static Display secondDisplay(Activity a) {
        DisplayManager m = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
        if (m == null) return null;
        int own = a.getDisplay() != null ? a.getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        return dev.peanuts.Screens.deviceScreen(a, m, own);
    }

    // ---- Lifecycle ----

    private static void watch(Activity a) {
        if (owner != a) { if (owner != null) dismiss(); owner = a; started = resumed = a.hasWindowFocus(); }
        if (watching) return;
        watching = true;
        a.getApplicationContext().registerReceiver(companionState, new IntentFilter(Companion.STATE), Context.RECEIVER_NOT_EXPORTED);
        a.getApplicationContext().registerReceiver(pageState, new IntentFilter(AppScreens.STATE), Context.RECEIVER_NOT_EXPORTED);
        Context app = a.getApplicationContext();
        Pads.watch(app, () -> { if (panel != null) panel.setPad(Pads.connected(app)); });
        a.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity x) {
                if (AppScreens.isPage(x)) { pages.add(AppScreens.key(x)); UI.post(BottomScreen::show); return; }
                if (!isMain(x)) return;
                owner = x; started = true;
                pages.clear();   // whatever was in front has gone; no stale page keeps the display
                launch = null; UI.removeCallbacks(launchEnd); UI.removeCallbacks(launchStuck); UI.removeCallbacks(launchDialog);   // back from the game
                listen(x);
                UI.post(BottomScreen::show);
                Cracked.settle(x);   // back from a game: its play time counts for Peanuts cracked
            }
            @Override public void onActivityResumed(Activity x) { if (isMain(x) && x == owner) { resumed = true; if (!showing()) UI.post(BottomScreen::show); } }
            @Override public void onActivityStopped(Activity x) {
                if (AppScreens.isPage(x)) { pages.remove(AppScreens.key(x)); if (!inApp()) leaveSoon(); return; }
                if (x != owner) return;
                started = false;
                Launch l = launch;
                if (l == null || !showing()) { launch = null; if (inApp()) show(); else leaveSoon(); return; }
                UI.removeCallbacks(launchStuck);
                UI.removeCallbacks(launchDialog);
                if (l.said != null) handOff(); else UI.postDelayed(launchEnd, HANDOFF_MAX_MS);
            }
            @Override public void onActivityDestroyed(Activity x) {
                if (x == popsOf) { popsOf = null; pops = null; }
                if (x != owner) return;
                launch = null; UI.removeCallbacks(launchEnd); UI.removeCallbacks(launchStuck); UI.removeCallbacks(launchDialog);
                dismiss();
                if (dm != null && displays != null) { try { dm.unregisterDisplayListener(displays); } catch (RuntimeException ignored) { } }
                dm = null; displays = null; owner = null; openSettings = null; card = null; building = null; explain = null; started = resumed = false;
                focusOwner = null; ownerFocused = true;
            }
            @Override public void onActivityCreated(Activity x, Bundle b) {}
            @Override public void onActivityPaused(Activity x) { if (x == owner) resumed = false; }
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
        });
        listen(a);
        if (started) UI.post(BottomScreen::show);
    }

    private static boolean isMain(Activity x) { return MAIN.equals(x.getClass().getName()); }

    private static void listen(Activity a) {
        if (displays != null) return;
        dm = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return;
        dev.peanuts.Screens.log(a, "the launcher starts");   // which display is which, for a report
        displays = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { if (owner != null) dev.peanuts.Screens.log(owner, "display " + id + " came"); show(); }
            @Override public void onDisplayRemoved(int id) { if (panel != null && panel.getDisplay() != null && panel.getDisplay().getDisplayId() == id) dismiss(); show(); }
            @Override public void onDisplayChanged(int id) {
                BackgroundFlow.stale();
                Display d = panel != null ? panel.getDisplay() : null;
                if (d == null || d.getDisplayId() != id) return;
                if (!panel.fits()) { dismiss(); show(); return; }
                if (showing()) panel.setActive(on(d));   // the screen turned off or on
            }
        };
        dm.registerDisplayListener(displays, UI);
    }
}
