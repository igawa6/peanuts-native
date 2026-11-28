package dev.peanuts;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import dev.peanuts.settings.CompanionSection;
import dev.peanuts.ui.GameScreen;

/**
 * Peanuts Native: the companion screen of a running game, shown on a second display (the AYN Thor's "Screen-2") as a Presentation owned by the
 * game's activity, in the game's own process (":game"), so the host's memory and input are a function call away. GameActivity calls start() once its
 * window is up and stop() when it is destroyed (the two PEANUTS hooks); without a second display nothing is shown and the game is unchanged.
 *
 * Taking the display at all is gated on the host's companion runtime (dm/peanuts/peanuts.c, Bridge.rtState): with no companion package for this title
 * (or one that failed to load), the second display is left alone entirely, exactly as it is without Peanuts - Android's own launcher/home stays on it
 * - unless the developer readout is explicitly turned on (PeanutsPrefs.developerReadout). RT_PENDING (the host has not decided yet, usually because the
 * game's main module has not loaded) is polled until it resolves or the game ends.
 */
public final class Companion {
    static final String TAG = "peanuts";
    private static final int DECIDE_POLL_MS = 200;
    private static CompanionPresentation shown;
    private static DisplayManager.DisplayListener listener;
    private static DisplayManager listenerDm;   // where the listener is registered: DisplayManager's listeners are process-wide, stop() must unregister it
    private static android.content.Context held;   // the game hold's context while it runs (GameHold)
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Activity decidingGame;
    private static String decidingGameName;
    private static boolean stopped;
    /** Set once decide() resolves: whether this run is allowed to take the second display at all. Until then, a hot-plugged display is ignored. */
    private static boolean allowed;
    /** Set while the game's activity is stopped (Home or another app is in front, or the screen is off): the companion's window is off the second display
     * then, which goes back to whatever Android shows there. A Presentation is not closed by its activity stopping, so without this the companion stayed
     * on the bottom screen over the home screen or the other app (seen on the AYN Thor, 2026-11-15). */
    private static boolean away;
    /** The player hid the second display's companion or page with a four-finger tap on the game ({@link #toggleScreen}): Android's own screen there until the
     *  next one (or the next run). Nothing shows it again meanwhile: not the game's return to the front, a display that comes, or the companion loading late */
    private static boolean hidden;
    /** Temporarily hidden while Switch TextInput (swkbd) is open so Android's soft keyboard can appear on the bottom display. */
    private static boolean keyboardHidden;
    /** Peanuts' page on the second display meanwhile (GameScreen.LOADING / PLAYING), -1 none */
    private static int wanted = -1;
    private static final long LOADING_MAX_MS = 60000;
    /** After a display went away, the time Android takes to move its tasks to the built-in display */
    private static final long DISPLAY_SETTLE_MS = 700;

    private Companion() {}

    /** To the launcher's bottom screen (BottomScreen, the main process): the second display is the companion's, or will not be */
    public static final String STATE = "dev.peanuts.COMPANION_STATE";

    private static void tell(android.content.Context c, String state) {
        if (c == null) return;
        Display d = c instanceof Activity ? ((Activity) c).getDisplay() : null;
        try {
            c.sendBroadcast(new android.content.Intent(STATE).setPackage(c.getPackageName()).putExtra("state", state)
                    .putExtra("game_display", d == null ? Display.INVALID_DISPLAY : d.getDisplayId()));
        } catch (RuntimeException ignored) {}
    }

    /** Until decide(): the start page if a companion is installed, the game's cover if Settings keep one, else nothing */
    private static int firstPage(Activity game) {
        if (!PeanutsPrefs.bottomScreen(game)) return -1;
        String id = titleId(game);
        boolean off = CompanionSection.secondScreen(game, id).equals(CompanionSection.OFF);
        if (!off && companionInstalled(game, id)) return GameScreen.LOADING;
        return PeanutsPrefs.bottomScreenInGames(game) ? GameScreen.PLAYING : -1;
    }

    /** As the runtime finds it: a package beside the pack whose manifest names this title */
    private static boolean companionInstalled(Activity game, String id) {
        String pack = game.getIntent().getStringExtra("pack");
        java.io.File parent = pack == null ? null : new java.io.File(pack).getAbsoluteFile().getParentFile();
        return parent != null && CompanionSection.packageFor(new java.io.File(parent, "peanuts"), id) != null;
    }

    /** No companion after all: the game's cover if Settings keep one, else the display goes back to Android */
    /**
     * The start page's limit (LOADING_MAX_MS). It counts from the end of the preparing step: while the host prepares shaders before the game starts
     * (GameActivity.prewarmTick, minutes for a large game) no companion can come up, and the page closed a minute in, the second screen dark for the rest.
     */
    private static final Runnable loadingLimit = new Runnable() {
        @Override public void run() {
            if (allowed || stopped || decidingGame == null) return;
            boolean preparing = Companion.preparing;
            if (preparing) ui.postDelayed(this, 1000); else if (!preparedSeen) settle(decidingGame); else { preparedSeen = false; ui.postDelayed(this, LOADING_MAX_MS); }
            if (preparing) preparedSeen = true;
        }
    };
    private static boolean preparedSeen;
    private static volatile boolean preparing;
    /** GameActivity.prewarmTick: the host is preparing shaders before the game starts (or has not said yet) */
    public static void preparing(boolean on) { preparing = on; if (on) preparedSeen = true; }   // (seen even when it ends before the limit first looks)

    private static void settle(Activity game) {
        wanted = PeanutsPrefs.bottomScreen(game) && PeanutsPrefs.bottomScreenInGames(game) ? GameScreen.PLAYING : -1;
        if (wanted < 0) GameScreen.close(); else if (!away) openPage(game);
        tellPage(game, "none");
    }

    private static void openPage(Activity game) {
        if (hidden || wanted < 0 || listenerDm == null) return;
        Display d = secondDisplay(game, listenerDm);
        // The built-in display only accepts an activity, which opens when the companion is ready.
        if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY)
            GameScreen.open(game, d, titleId(game), decidingGameName, wanted);
    }

    /** What is really on the second display now; nothing: {@code otherwise} */
    private static void tellPage(Activity game, String otherwise) {
        int m = GameScreen.mode();
        tell(game, m == GameScreen.LOADING ? "loading" : m == GameScreen.PLAYING ? "playing" : otherwise);
    }

    private static String titleId(Activity game) { return String.valueOf(game.getIntent().getStringExtra("title_id")); }

    /** Whether PN_DUAL_RTT is enabled in the run's env.txt or env-&lt;title ID&gt;.txt (read before Native.start hands env lines to the host). */
    private static boolean dualInEnv(Activity game) {
        for (java.io.File f : new java.io.File[] { new java.io.File(game.getExternalFilesDir(null), "env.txt"),
                                                   new java.io.File(game.getExternalFilesDir(null), "env-" + titleId(game) + ".txt") }) {
            try {
                if (!f.isFile()) continue;
                for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    if (line.substring(0, eq).trim().equals("PN_DUAL_RTT")) {
                        String v = line.substring(eq + 1).trim();
                        if (!v.isEmpty() && !v.chars().allMatch(c -> c == '0')) return true;
                    }
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** The game left the foreground (GameActivity.onStop): the companion's window is taken off the second display until {@link #back}. */
    public static void away() {
        away = true;
        tell(decidingGame, "away");
        if (screenUp) { screenUp = false; CompanionScreen.close(); Log.i(TAG, "the game left the foreground: the companion's screen is closed until it is back"); }
        GameScreen.away();
        if (shown != null) {
            boolean dual = false;
            try { dual = Bridge.dualWanted(); } catch (UnsatisfiedLinkError ignored) {}
            if (dual) {
                // Direct Vulkan SurfaceView must be dismissed so Android removes its SurfaceFlinger layer cleanly
                closeWindows();
            } else {
                // Hidden, not dismissed: a window made again came back black until the companion drew something new (its page is only sent when it changes)
                try { shown.hide(); } catch (RuntimeException ignored) {}
                try { Bridge.present(false); } catch (UnsatisfiedLinkError ignored) {}   // hide() runs no onStop: the runtime is told here, and held fingers are released
            }
            Log.i(TAG, "the game left the foreground: the companion is off the second display until it is back");
        }
    }

    /** The game is in front again (GameActivity.onStart): the companion is shown again when this run had it. */
    public static void back(Activity game) {
        if (!away) return;
        away = false;
        if (stopped) return;
        tellPage(game, "free");
        if (wanted >= 0 && !hidden) { if (GameScreen.mode() < 0) openPage(game); else GameScreen.back(); }
        place(game);   // the game may come back on another display (Recents): it goes home first, the companion is shown after that
    }

    /** The game is the top activity again (GameActivity.onTopResumedActivityChanged): back from Recents, or a tap on its display. See {@link #place} */
    public static void gameOnTop(Activity game) {
        if (game != decidingGame || stopped || away) return;
        place(game);
    }

    /** The game's display: Android moves the game's task without telling the activity (Recents starts it on the display Recents runs on) */
    private static int displayOf(Activity game) {
        Display d = game.getDisplay();
        return d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    /** The game back on its home display, the companion where it belongs (ScreenPlace), and the built-in screen black while the game is on the TV ({@link #cover}):
     *  after the background, on each return to the front, and when a display comes or goes. Before, a game that Recents had moved to the companion's display
     *  stayed there, and the companion was gone or on the other screen (#183) */
    private static void place(Activity game) {
        placeCompanion(game);
        cover(game);
        TvPicture.fit(game);   // a move between displays with the same configuration calls no onConfigurationChanged
    }

    private static void placeCompanion(Activity game) {
        DisplayManager dm = listenerDm;
        if (dm == null || stopped || away || game.isFinishing() || game.isDestroyed()) return;
        int g = displayOf(game);
        if (home != Display.INVALID_DISPLAY) { Display h = dm.getDisplay(home); if (h == null || !h.isValid()) home = Display.INVALID_DISPLAY; }
        boolean tried = false;
        if (movedHomeAt != 0) {   // the game was sent home: it got there, it is still on its way (wait for the check below), or Android refused
            if (home == Display.INVALID_DISPLAY || g == home) movedHomeAt = 0;
            else if (SystemClock.uptimeMillis() - movedHomeAt < MOVE_HOME_MS) return;
            else tried = true;
        }
        Display sd = shown != null ? shown.getDisplay() : null;
        int on = sd != null ? sd.getDisplayId() : Display.INVALID_DISPLAY;
        switch (ScreenPlace.decide(g, home, tried, game.isTaskRoot(), allowed, screenUp, on)) {
            case ScreenPlace.MOVE_BACK:
                movedHomeAt = SystemClock.uptimeMillis();
                if (moveHome(game, home)) {
                    Log.i(TAG, "the game came to the front on display " + g + ", not on display " + home + " where it was started: it goes back there");
                    ui.postDelayed(() -> { if (decidingGame == game) place(game); }, MOVE_HOME_MS + 100);   // a move Android ignored: the companion follows then
                    return;
                }
                // fall through: refused
            case ScreenPlace.FOLLOW:
                Log.i(TAG, "the game stays on display " + g + " (display " + home + " refused it or is gone, or the game shares Home's task): the companion takes the other display");
                home = Display.INVALID_DISPLAY;
                movedHomeAt = 0;
                if (allowed) { closeWindows(); show(game, decidingGameName, dm); }
                return;
            case ScreenPlace.RESHOW:
                Log.i(TAG, "the companion was on the game's display " + g + ": it moves to the other one");
                closeWindows();
                show(game, decidingGameName, dm);
                return;
            case ScreenPlace.SHOW:
                if (hidden) return;   // a four-finger tap hid it (toggleScreen)
                if (keyboardHidden) return;   // TextInput soft keyboard is open
                if (shown != null) {
                    if (shown.isShowing()) return;
                    try { shown.show(); try { Bridge.present(true); } catch (UnsatisfiedLinkError ignored) {} return; }
                    catch (RuntimeException e) { Log.w(TAG, "companion not shown again: " + e); shown = null; }   // its display went away meanwhile
                }
                if (!screenUp || !screenPage) show(game, decidingGameName, dm);   // nothing up (back from the background, or Android closed CompanionScreen: Recents, Home), or only the black screen of a game on the TV
                else if (!CompanionScreen.inFront() && SystemClock.uptimeMillis() - screenOpenedAt > SCREEN_OPENING_MS) { Log.i(TAG, "the companion's screen was behind another one on its display: it comes to the front"); screenToFront(); }   // the AYN Thor's Recents and Home can leave it paused behind them
                return;
            default:
        }
    }

    /** Starts the game's activity on display id: singleTask, so Android moves its task there and brings it to the front (an intent with no title ID is a
     *  no-op in GameActivity.onNewIntent; GameReturn goes this way too) */
    private static boolean moveHome(Activity game, int id) {
        try {
            game.startActivity(new android.content.Intent(game, game.getClass()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());
            return true;
        } catch (RuntimeException e) { Log.w(TAG, "the game could not go back to display " + id + ": " + e); return false; }
    }

    /** The companion's windows closed, to be shown again on another display */
    private static void closeWindows() {
        if (screenUp) { screenUp = false; CompanionScreen.close(); }
        if (shown != null) {
            try { shown.dismiss(); } catch (RuntimeException ignored) {}
            shown = null;
            try { Bridge.present(false); } catch (UnsatisfiedLinkError ignored) {}
        }
    }

    /** Whether the built-in display's companion activity (CompanionScreen, Swap screens or a game on the TV) should be up now */
    static boolean screenWanted() { return screenUp && !stopped && !away; }
    private static boolean screenUp;
    /** CompanionScreen shows the companion's page; false: it is only black, the screen a game on the TV left ({@link #cover}) */
    private static boolean screenPage;
    static boolean screenPage() { return screenPage; }
    /** This run plays on a TV or monitor: Play on the TV started it there (Screens.TV), or moved it there when the TV came ({@link #toTv}) */
    private static boolean tvRun;

    /** CompanionScreen on the built-in, default display (singleInstance: when it is up already, its onNewIntent takes the new mode) */
    private static void startScreen(Activity game, String gameName) {
        game.startActivity(new android.content.Intent(game, CompanionScreen.class).putExtra("name", gameName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
    }

    /**
     * Play on the TV (#358): while the game is on the TV, the device's screen it left is black, as if it were off (the AYN Thor's top screen; a phone's own
     * screen the same), unless the system has turned that screen off itself (a handheld's own TV mode comes first). Nothing there to tap by mistake, and Home does not stay up behind the game: a tap on Home took the controller from it. A companion goes
     * on as before: on the Thor's Screen-2, or on this screen on a phone (CompanionScreen takes its page then). Back on a screen of the device (the TV came off
     * and Android moved the game to the built-in one), the black screen goes.
     */
    private static void cover(Activity game) {
        if (stopped || away || game.isFinishing() || game.isDestroyed()) return;
        if (!(tvRun && Screens.onTv(game))) {
            if (screenUp && !screenPage) { screenUp = false; CompanionScreen.close(); Log.i(TAG, "the game is on a screen of the device again: the black screen goes"); }
            return;
        }
        if (screenUp) {   // the page or the black screen is up: in front again after Home or Recents on its display
            if (!screenPage && !CompanionScreen.inFront() && SystemClock.uptimeMillis() - screenOpenedAt > SCREEN_OPENING_MS) screenToFront();
            return;
        }
        Display builtIn = listenerDm != null ? listenerDm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (builtIn == null || !builtIn.isValid()) return;
        if (builtIn.getState() == Display.STATE_OFF) { Log.i(TAG, "the game is on the TV and the system has turned the built-in screen off: nothing of Peanuts goes there"); return; }   // a handheld's own TV mode (the AYN firmware turns its screens off on HDMI)
        try {
            screenUp = true;
            screenPage = false;
            screenOpenedAt = SystemClock.uptimeMillis();
            startScreen(game, decidingGameName);
            Log.i(TAG, "the game is on the TV: the built-in screen is black meanwhile");
        } catch (RuntimeException e) {
            screenUp = false;
            Log.w(TAG, "the built-in screen was not made black: " + e);
        }
    }

    /**
     * A display came while the game runs on a screen of the device: with Play on the TV, a TV or monitor takes the game, as a console does when it is docked.
     * Only a game in a task of its own can move (one Play on the TV or Swap screens started); a game in Home's task stays (moving it made a second game activity,
     * ScreenPlace), and Android may show a copy of the built-in screen there until the next start
     */
    private static void toTv(Activity game, int id) {
        DisplayManager dm = listenerDm;
        if (dm == null || stopped || away || game.isFinishing() || !PeanutsPrefs.tvPlay(game) || Screens.onTv(game)) return;
        Display d = dm.getDisplay(id);
        if (d == null || !d.isValid() || Screens.role(game, d) != ScreenRole.TV) return;
        if (!game.isTaskRoot()) { Log.i(TAG, "a TV or monitor came: the game shares Home's task, so it stays on this screen and starts on the TV next time"); return; }
        if (!moveHome(game, id)) return;
        Log.i(TAG, "a TV or monitor came: the game moves there (" + Screens.describe(game, d) + ")");
        tvRun = true;
        home = id;
        movedHomeAt = SystemClock.uptimeMillis();
        GameReturn.startedOn(game, id);   // the app's icon brings it back there
        ui.postDelayed(() -> { if (decidingGame == game) place(game); }, MOVE_HOME_MS + 100);
    }
    /** The display the game was started on (ScreenPlace), INVALID_DISPLAY once it is gone or refused the game */
    private static int home = Display.INVALID_DISPLAY;
    /** When the game was last sent home (uptime), 0: not in this round */
    private static long movedHomeAt;
    /** How long the game may take to get home after it was sent there: still elsewhere then, Android refused the move */
    private static final long MOVE_HOME_MS = 3000;
    /** When CompanionScreen was last started (uptime): until it is resumed it is not in front yet, and needs no second start */
    private static long screenOpenedAt;
    private static final long SCREEN_OPENING_MS = 1500;
    /** Each display keeps a focused window of its own (perDisplayFocus), read at the start */
    private static boolean perDisplay = true;

    /** When this run started (SystemClock.elapsedRealtime): the stats page's run clock (GameScreen) */
    public static long startedAt() { return startedAt; }
    private static long startedAt;

    /** CompanionScreen is up: its start made the built-in display the top one, which would hand the controller to its window (gameKey passes the keys on, but
     *  the game's own window should have them). The game's activity is started again on its own display: it is singleTask, so that only brings it to the front, and an intent with no title ID
     *  is a no-op in its onNewIntent. Its display, and the controller, are the game's again. */
    static void refocusGame() {
        Activity game = decidingGame;
        Display d = game != null ? game.getDisplay() : null;
        if (!perDisplay || game == null || d == null || stopped || away || game.isFinishing()) return;   // without per-display focus the built-in display stays on top (screenTouched)
        try {
            game.startActivity(new android.content.Intent(game, game.getClass()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(d.getDisplayId()).toBundle());
        } catch (RuntimeException e) { Log.w(TAG, "the game's display did not take the focus back: " + e); }
    }

    /** CompanionScreen went away without {@link #away} or {@link #stop}: Home or Recents on its display finished it (noHistory). It is opened again when the game
     *  comes back to the front ({@link #back}); before, screenUp stayed set and the companion never came back in this run. */
    static void screenGone() {
        if (!screenUp) return;
        screenUp = false;
        Log.i(TAG, "the companion's screen was closed on its display: it opens again when the game is back in front");
    }

    /** A controller's key that reached CompanionScreen (its window has the built-in display's focus; ScreenKeys): the game's, as from its own window */
    static boolean gameKey(android.view.KeyEvent e) {
        Activity game = decidingGame;
        return game != null && !stopped && !game.isFinishing() && game.dispatchKeyEvent(e);
    }

    /** A controller's stick or trigger that reached CompanionScreen: the game's, as {@link #gameKey} */
    static boolean gameMotion(android.view.MotionEvent e) {
        Activity game = decidingGame;
        return game != null && !stopped && !game.isFinishing() && game.dispatchGenericMotionEvent(e);
    }

    /** A touch on the companion's page on the built-in display. Without per-display focus only the top display has a focused window, and Android hands Back
     *  from the built-in display's navigation bar to that display's focused window: with the game's display on top there is none, and Back waited 5 s and
     *  ended in "isn't responding" (the AYN Thor keeps a focused window per display, a phone may not). A tap on the game's screen puts its display on top;
     *  a touch here (the edge swipe that shows the navigation bar starts here too) puts the built-in one back on top before Back can come. The controller
     *  then reaches CompanionScreen, which hands it to the game (gameKey, gameMotion) */
    static void screenTouched() {
        CompanionScreen s = CompanionScreen.current;
        if (perDisplay || !screenWanted() || s == null || CompanionScreen.onTop() || s.isFinishing()) return;
        screenToFront();
    }

    /** CompanionScreen to the front of the built-in display, and that display to the top (singleInstance: the activity there is brought forward) */
    private static void screenToFront() {
        CompanionScreen s = CompanionScreen.current;
        if (s == null || s.isFinishing()) return;
        try {
            s.startActivity(new android.content.Intent(s, CompanionScreen.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        } catch (RuntimeException e) { Log.w(TAG, "the built-in display did not come to the top: " + e); }
    }

    /** Back on CompanionScreen's display (its navigation bar): what Back does in the game, the in-game panel */
    @SuppressWarnings("deprecation")
    static void gameBack() {
        Activity game = decidingGame;
        if (game != null && !stopped && !game.isFinishing()) game.onBackPressed();
    }

    /** Whether each display keeps a focused window of its own (config_perDisplayFocusEnabled; the AYN Thor: yes). Without it only the top display has one, and Back
     *  from the built-in display's navigation bar finds no window there while the game's display is on top: logged with the companion's start for reports. */
    private static boolean perDisplayFocus() {
        try {
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("config_perDisplayFocusEnabled", "bool", "android");
            return id != 0 && r.getBoolean(id);
        } catch (RuntimeException e) { return false; }
    }

    /**
     * A four-finger tap on the game's screen (GameActivity.fourFingerTap): the second display's companion or Peanuts' page is hidden, and Android's own screen is
     * there (the AYN Thor's launcher on Screen-2) until the next four-finger tap, which shows it again. With nothing of Peanuts' there (no companion, Settings ›
     * Games without a companion off), the tap brings the game's page for this run. Hidden the way {@link #away} hides it: the windows stay, so the companion
     * comes back with its page as it was. → whether something is shown now; null: nothing to do (no second display, the game in the background or stopping)
     */
    public static Boolean toggleScreen(Activity game) {
        if (!canToggle(game)) return null;
        if (!hidden) {
            if (screenIsUp()) {
                hidden = true;
                GameScreen.away();
                if (shown != null) {
                    boolean dual = false;
                    try { dual = Bridge.dualWanted(); } catch (UnsatisfiedLinkError ignored) {}
                    if (dual) closeWindows();
                    else {
                        try { shown.hide(); } catch (RuntimeException ignored) {}
                        try { Bridge.present(false); } catch (UnsatisfiedLinkError ignored) {}   // as away(): the runtime is told, held fingers are released
                    }
                }
                if (screenUp && screenPage) { screenUp = false; screenPage = false; CompanionScreen.close(); }   // the companion on the built-in display (Swap screens, the TV)
                tell(game, "none");
                Log.i(TAG, "four-finger tap: the second screen is Android's until the next one");
                return false;
            }
            if (!allowed && wanted < 0) wanted = GameScreen.PLAYING;   // nothing of Peanuts' there: the game's page for this run
        }
        hidden = false;
        Log.i(TAG, "four-finger tap: the " + (allowed ? "companion" : "game's page") + " is back on the second screen");
        if (allowed) place(game);
        else { if (GameScreen.mode() < 0) openPage(game); else GameScreen.back(); tellPage(game, "none"); }
        return true;
    }

    private static boolean canToggle(Activity game) { return game == decidingGame && !stopped && !away && listenerDm != null && secondDisplay(game, listenerDm) != null; }
    private static boolean screenIsUp() { return shown != null && shown.isShowing() || GameScreen.mode() >= 0 || screenUp && screenPage; }

    /** Whether the second display shows the companion or Peanuts' page now (what {@link #toggleScreen} would hide); null when toggleScreen could do nothing. The adb control's game command */
    public static Boolean screenShown(Activity game) { return canToggle(game) ? !hidden && screenIsUp() : null; }

    /** Temporarily release the second display while Switch TextInput (swkbd) is open so Android's soft keyboard can show there. */
    public static void keyboardOpen(boolean open) {
        if (open) {
            keyboardHidden = true;
            if (shown != null) {
                Log.i(TAG, "keyboard open: dismissing second-screen Presentation so IME can show");
                closeWindows();
                tell(decidingGame, "none");
            }
        } else if (keyboardHidden) {
            keyboardHidden = false;
            if (!hidden && !away && !stopped && allowed && decidingGame != null) {
                Log.i(TAG, "keyboard closed: restoring second-screen Presentation");
                place(decidingGame);
            }
        }
    }

    /** Starts the game hold and, once the host has decided whether a companion loaded, shows it (or leaves the display alone). Keeps trying as displays come and go. */
    public static void start(Activity game, String gameName) {
        if (listener != null) return;
        // A pack from before main.orig.bin gets the file now, on a thread of its own, before the host starts: the runtime waits for it (dev.peanuts.pack.PackUpgrade)
        try { dev.peanuts.pack.PackUpgrade.atGameStart(game); } catch (RuntimeException e) { Log.w(TAG, "pack upgrade: " + e); }
        stopped = false;
        startedAt = android.os.SystemClock.elapsedRealtime();
        preparedSeen = false;
        allowed = false;
        away = false;
        hidden = false;
        keyboardHidden = false;
        showTries = 0;
        screenUp = false;
        screenPage = false;
        tvRun = game.getIntent().getBooleanExtra(Screens.TV, false);
        home = displayOf(game);
        movedHomeAt = 0;
        perDisplay = perDisplayFocus();
        Screens.log(game, "the game starts on display " + home);
        DisplayManager dm = (DisplayManager) game.getSystemService(Activity.DISPLAY_SERVICE);
        listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {
                Screens.log(game, "display " + id + " came");
                toTv(game, id);
                if (allowed) show(game, gameName, dm); else if (!away) openPage(game);
            }
            @Override public void onDisplayRemoved(int id) {
                Log.i(TAG, "display " + id + " went away");
                if (id == home && screenUp) { screenUp = false; CompanionScreen.close(); Log.i(TAG, "the game's display went away: the built-in screen is cleared before Android moves the game there"); }   // the black screen, or the page (shown again where it belongs, below)
                if (id == home) home = Display.INVALID_DISPLAY;   // the clip-on screen or the TV came off: the game stays where Android puts it (the built-in display)
                if (allowed && shown != null && shown.getDisplay().getDisplayId() == id) { shown = null; show(game, gameName, dm); }
                if (!allowed) { GameScreen.close(); if (!away) openPage(game); }
                ui.postDelayed(() -> { if (decidingGame == game) place(game); }, DISPLAY_SETTLE_MS);   // Android moves the game's task off that display: CompanionScreen must not stay under it
            }
            @Override public void onDisplayChanged(int id) {}
        };
        dm.registerDisplayListener(listener, null);
        listenerDm = dm;
        decidingGame = game; decidingGameName = gameName;
        wanted = firstPage(game);
        openPage(game);
        tellPage(game, "free");
        if (wanted == GameScreen.LOADING) ui.postDelayed(loadingLimit, LOADING_MAX_MS);
        decide(dm);
        cover(game);   // Play on the TV: the built-in screen black while the game is there
        // A TV that is there already: Android makes the game's screen again when it moves between displays, and a TV that came meanwhile reached no listener
        // (seen on the AYN Thor, 2026-11-23: one simulated TV taken off and another put on at once left the game on the built-in screen)
        Display tv = PeanutsPrefs.tvPlay(game) && !Screens.onTv(game) ? Screens.tv(game, home) : null;
        if (tv != null) toTv(game, tv.getDisplayId());
        GameHold.start(game, gameName);
        held = game.getApplicationContext();
    }

    public static void stop() {
        tell(held, "stopped");
        stopped = true;
        wanted = -1;
        GameScreen.close();
        decidingGame = null;
        ui.removeCallbacksAndMessages(null);
        if (held != null) { GameHold.stop(held); held = null; }
        if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} shown = null; }
        screenUp = false; screenPage = false; tvRun = false; CompanionScreen.close();
        if (listenerDm != null && listener != null) { try { listenerDm.unregisterDisplayListener(listener); } catch (RuntimeException ignored) {} }
        listener = null; listenerDm = null;   // unregistered: the listener holds the game activity, and DisplayManager's listeners outlive it
    }

    /** Polls Bridge.rtState() until it leaves RT_PENDING, then decides once whether to take the display. A title update/reinstall never changes this mid-run,
     * so one decision for the game's whole life is enough - onDisplayAdded/Removed above keep tracking the physical display after that. */
    private static void decide(DisplayManager dm) {
        Activity game = decidingGame;
        if (game == null || stopped || game.isFinishing() || game.isDestroyed()) return;
        int state = Bridge.rtStateSafe();
        boolean developerReadout = PeanutsPrefs.developerReadout(game);
        boolean dual = dualInEnv(game);
        try { dual |= Bridge.dualWanted(); } catch (UnsatisfiedLinkError ignored) { }
        if (state == Bridge.RT_PENDING && !developerReadout && !dual) {
            ui.postDelayed(() -> decide(dm), DECIDE_POLL_MS);
            return;
        }
        if (CompanionSection.secondScreen(game, String.valueOf(game.getIntent().getStringExtra("title_id"))).equals(CompanionSection.OFF)) {
            Log.i(TAG, "second screen is Off in this title's settings: companion not shown");
            settle(game);
            return;
        }
        if (state != Bridge.RT_LOADED && !developerReadout && !dual) {
            Log.i(TAG, "no companion loaded for this title (state=" + state + "): the second display is left to Android, not taken over");
            settle(game);
            return;
        }
        if (state != Bridge.RT_LOADED && dual && !developerReadout) Log.i(TAG, "direct dual-screen Vulkan presentation enabled (PN_DUAL_RTT=1)");
        if (state == Bridge.RT_FAILED) Log.w(TAG, "a companion package was found but the runtime refused it (see earlier \"peanuts\" logs); " +
                (developerReadout ? "showing the developer readout instead" : "second display left to Android"));
        allowed = true;
        show(game, decidingGameName, dm);
    }

    private static void show(Activity game, String gameName, DisplayManager dm) {
        if (shown != null || screenUp && screenPage || away || hidden || keyboardHidden || game.isFinishing() || game.isDestroyed()) return;   // away: shown again by back(); hidden: by toggleScreen/keyboard
        Display d = secondDisplay(game, dm);
        if (d == null) { Log.i(TAG, "no second display: companion not shown"); tell(game, "none"); return; }
        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {   // Swap screens or the TV: Android takes no Presentation there, the page gets an activity (CompanionScreen)
            try {
                boolean wasBlack = screenUp;
                screenUp = true;
                screenPage = true;
                screenOpenedAt = SystemClock.uptimeMillis();
                startScreen(game, gameName);   // over the black screen of a game on the TV: the same activity takes the page (CompanionScreen.onNewIntent)
                Log.i(TAG, "companion on the built-in display (the game is on another one" + (wasBlack ? ", the black screen gives way" : "") + "; per-display focus "
                        + (perDisplay ? "on" : "off: this display stays on top, the controller goes to the game through it") + ")");
                wanted = -1;
                GameScreen.close();
                tell(game, "shown");
            } catch (RuntimeException e) {
                screenUp = false;
                Log.w(TAG, "companion not shown on the built-in display: " + e);
                settle(game);
            }
            return;
        }
        try {
            shown = new CompanionPresentation(game, d, gameName);
            shown.show();
            Log.i(TAG, "companion on display " + d.getDisplayId() + " \"" + d.getName() + "\"");
            wanted = -1;
            GameScreen.closeSoon();
            tell(game, "shown");
        } catch (RuntimeException e) {   // the display refuses app windows, or went away between the query and show(); an activity not started yet gets no token: try again
            shown = null;
            if (++showTries <= 10) {
                Log.w(TAG, "companion not shown on display " + d.getDisplayId() + " yet (" + e + "): trying again");
                ui.postDelayed(() -> { if (allowed && !stopped && !away && !hidden && !keyboardHidden && shown == null) show(game, gameName, dm); }, 500);
                return;
            }
            Log.w(TAG, "companion not shown on display " + d.getDisplayId() + ": " + e);
            settle(game);
        }
    }
    private static int showTries;

    /** The companion's display: one of the device's own screens that the game is not on, never a TV (dev.peanuts.Screens, #358). With the game on the built-in,
     *  default display: the device's second screen (the Thor's Screen-2). With the game elsewhere (on the TV, or the other screen with Swap screens): the device's
     *  second screen when the game is not on it, else the built-in, default display */
    static Display secondDisplay(Activity game, DisplayManager dm) {
        int own = displayOf(game);
        Display second = Screens.deviceScreen(game, dm, own);
        if (own == Display.DEFAULT_DISPLAY || second != null) return second;
        Display builtIn = dm.getDisplay(Display.DEFAULT_DISPLAY);
        return builtIn != null && builtIn.isValid() ? builtIn : null;
    }

    /** The second display for the game's in-game menu on the bottom screen (dev.nxnative.android.InGamePanel), as secondDisplay */
    public static Display second(Activity game, DisplayManager dm) { return secondDisplay(game, dm); }

    /** Whether the device has a second screen of its own now (dev.peanuts.Screens; a TV or a cast does not count): what a title's companion settings default to
     * while the user has not chosen (CompanionSection.secondScreen, PeanutsPrefs.installCompanion). True when the displays cannot be read: the old default */
    public static boolean hasSecondDisplay(android.content.Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(android.content.Context.DISPLAY_SERVICE);
            return dm == null || Screens.deviceScreen(c, dm, Display.DEFAULT_DISPLAY) != null;
        } catch (RuntimeException e) { return true; }
    }

    /** The game's launch environment: PN_COMPANION=off when the title's Second screen is Off (chosen, or the default on a device without a second display), so the
     * host loads no companion at all (dm/peanuts/peanuts.c rt_start) instead of running one that has nowhere to show */
    public static void env(android.content.Context c, String titleId, java.util.List<String> env) {
        if (!CompanionSection.secondScreen(c, titleId).equals(CompanionSection.OFF)) return;
        for (String s : env) if (s.startsWith("PN_COMPANION=")) return;
        env.add("PN_COMPANION=off");
    }
}
