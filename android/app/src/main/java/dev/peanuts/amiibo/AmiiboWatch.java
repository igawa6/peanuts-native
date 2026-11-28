package dev.peanuts.amiibo;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.peanuts.Bridge;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.ui.AmiiboShow;

/**
 * The game's amiibo stand watched from the game's screen (dev.nxnative.android.InGamePanel attaches it), only in a game the player turned amiibo on for
 * (AmiiboSwitch): in any other nothing is started and nothing is read. A waiting thread sleeps in the host until the game opens or closes its amiibo library
 * (Bridge.amiiboWaitLib: nfp's Initialize and Finalize), and only while it is open and the game is in front is the stand read, every 150 ms (AmiiboStand.now;
 * igawa6's review of #713: games without amiibo pay nothing). While the game searches, the quick menu's amiibo tab lights up. When it has searched for 0.4 s,
 * once per search, it acts as the player set it (PeanutsPrefs.amiiboWhen): Suggest shows the card over the game (AmiiboCard), Only light up the tab does
 * nothing more, Place my last one at once places the amiibo last placed in this game (the card when there is none). The quick menu never opens unasked: only
 * the card's Choose opens it. While the card or the show is up they take the keys (InGamePanel.menuKey and backKey ask {@link #key}); B closes the card and
 * the game goes on searching. A placed amiibo comes with the show (dev.peanuts.ui.AmiiboShow), and the game gets the tag at its crack.
 */
public final class AmiiboWatch {
    private AmiiboWatch() {}

    /** What the in-game panel does for it */
    public interface Host {
        boolean menuOpen();
        void openTab();
        void closeMenus();
        int[] keys();   // the Switch's A and B in this game (PadPanel.confirmBack)
        void lit(boolean on);
    }

    static final String TAG = AmiiboStand.TAG;
    static final int K_A = 1, K_B = 2, K_LEFT = 3, K_RIGHT = 4;
    static final long TICK_MS = 150, CARD_AFTER_MS = 400;
    static final int WAIT_MS = 30000;   // the waiting thread's longest sleep in the host: it looks again whether the game's screen is still there
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Activity act;
    private static FrameLayout root;
    private static Host host;
    private static String titleId;
    private static AmiiboCard card;
    private static AmiiboShow show;
    private static int handled = -1;
    private static boolean seen, lit, resumed, open;   // open: the game has its amiibo library open (the host's word); resumed: its screen is in front
    private static volatile Thread waiter;   // the waiting thread of the screen attached now; an older one ends at its next wake
    private static String readyKey;   // placed in advance from the tab: the show plays when the game finds it
    private static AmiiboStand.Status last = AmiiboStand.parse("");
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Set<Integer> taken = new HashSet<>();

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed || !open) return;
            try { step(); } catch (RuntimeException e) { Log.w(TAG, "amiibo watch", e); }
            UI.postDelayed(this, TICK_MS);
        }
    };

    /** GameActivity.onCreate (through InGamePanel.attach): the game's screen and its root. Nothing at all in a game amiibo is off for */
    public static void attach(Activity a, FrameLayout r, Host h) {
        String id = a.getIntent().getStringExtra("title_id");
        if (!AmiiboSwitch.on(a, id)) { Log.i(TAG, "amiibo: off for this game; the stand is not watched"); return; }
        act = a; root = r; host = h; titleId = id;
        handled = -1; seen = false; lit = false; open = false; card = null; show = null; readyKey = null; last = AmiiboStand.parse("");
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity x) { if (x == act) { resumed = true; follow(); } }
            @Override public void onActivityPaused(Activity x) { if (x == act) { resumed = false; UI.removeCallbacks(tick); } }
            @Override public void onActivityDestroyed(Activity x) {
                if (x != act) return;
                resumed = false; open = false; UI.removeCallbacks(tick); act = null; root = null; host = null; listeners.clear();
                waiter = null;   // the waiting thread ends at its next wake (WAIT_MS at most)
            }
            @Override public void onActivityCreated(Activity x, Bundle b) {}
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) {}
        });
        Thread t = new Thread(AmiiboWatch::waitForTheGame, "peanuts-amiibo-wait");
        t.setDaemon(true);
        waiter = t;
        t.start();
    }

    /** The waiting thread: asleep in the host until the game opens or closes its amiibo library; the stand is read only in between */
    private static void waitForTheGame() {
        int was = 0;
        while (Thread.currentThread() == waiter) {
            int lib = Bridge.amiiboWaitLibSafe(was, WAIT_MS);
            if (lib == Bridge.AMIIBO_OFF) { Log.i(TAG, "amiibo: the host has it off for this game"); return; }
            if (lib == Bridge.AMIIBO_NO_HOST) { try { Thread.sleep(1000); } catch (InterruptedException e) { return; } continue; }   // the game's host, or its modules, not loaded yet
            if (lib == was) continue;
            was = lib;
            boolean now = lib == 1;
            UI.post(() -> { if (act == null) return; open = now; follow(); });
        }
    }

    /** The ticks run while the library is open and the game is in front; when it closes, the stand once more (the lit tab and the card go out) */
    private static void follow() {
        UI.removeCallbacks(tick);
        if (open && resumed) UI.post(tick);
        else if (!open && act != null) { try { step(); } catch (RuntimeException e) { Log.w(TAG, "amiibo watch", e); } }
    }

    /** The stand as last read */
    public static AmiiboStand.Status status() { return last; }
    public static String titleId() { return titleId; }
    /** The tab follows the stand while it shows */
    public static void listen(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    public static void unlisten(Runnable r) { listeners.remove(r); }

    private static void step() {
        Activity a = act;
        if (a == null) return;
        AmiiboStand.Status s = AmiiboStand.now();
        boolean changed = s.state != last.state || s.search != last.search || !s.on.equals(last.on) || !s.ready.equals(last.ready);
        AmiiboStand.Status was = last;
        last = s;
        if (s.lib && !seen) { seen = true; AmiiboStand.seen(a.getFilesDir(), titleId); }
        boolean on = s.searching();
        if (on != lit && host != null) { lit = on; host.lit(on); }
        if (changed) for (Runnable r : new ArrayList<>(listeners)) r.run();
        // placed in advance: the show when the game takes it
        if (readyKey != null && s.onStand() && s.on.equals(readyKey) && !was.on.equals(readyKey)) { String k = readyKey; readyKey = null; showFor(AmiiboStore.find(a.getFilesDir(), k), null); }
        if (card != null && !card.leaving() && (!on || host != null && host.menuOpen())) hideCard();   // the game stopped looking, or a menu opened
        if (!on || s.search == handled || s.searchingMs < CARD_AFTER_MS || show != null) return;
        handled = s.search;
        if (host != null && host.menuOpen()) return;   // the player is in a menu: the lit tab says it
        int when = PeanutsPrefs.amiiboWhen(a);
        if (when == PeanutsPrefs.AMIIBO_LIGHT) return;
        AmiiboDb db = AmiiboStand.db(a);
        Set<String> fits = AmiiboPage.ids(db.fitting(titleId));
        List<AmiiboStore.Own> mine = AmiiboStore.forGame(AmiiboStore.list(a.getFilesDir()), titleId, fits);
        if (when == PeanutsPrefs.AMIIBO_AUTO && !mine.isEmpty() && AmiiboStore.placedIn(mine.get(0), titleId)[0] > 0) { place(mine.get(0)); return; }
        showCard(mine, db, fits.size());
    }

    private static void showCard(List<AmiiboStore.Own> mine, AmiiboDb db, int fits) {
        Activity a = act;
        if (a == null || root == null) return;
        if (card != null) hideCard();
        AmiiboCard c = new AmiiboCard(a, mine, db, titleId, fits, padAttached(), new AmiiboCard.Act() {
            @Override public void place(AmiiboStore.Own o) { AmiiboWatch.place(o); }
            @Override public void choose() { hideCard(); if (host != null) host.openTab(); }
            @Override public void notNow() { hideCard(); }
        });
        card = c;
        root.addView(c, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        c.show();
        dev.peanuts.ui.MenuFeel.open(c);
    }

    private static void hideCard() {
        AmiiboCard c = card;
        card = null;
        if (c != null) c.hide(null);
    }

    /** One of the player's amiibo placed now: the show over the game, the tag at its crack (the card's A, the tab's pick while the game searches, Auto) */
    public static void place(AmiiboStore.Own o) {
        Activity a = act;
        if (a == null || o == null) return;
        Bitmap b = card != null ? card.pictures.get(o.id) : null;
        hideCard();
        if (host != null) host.closeMenus();
        showFor(o, () -> AmiiboStand.place(a, o, titleId), b);   // the game stopped looking meanwhile: the host keeps it for its next search
    }

    /** From the tab while the game does not search: kept for its next search (the show plays when it takes it); true when the host took it */
    public static boolean placeAhead(AmiiboStore.Own o) {
        Activity a = act;
        if (a == null || o == null) return false;
        int r = AmiiboStand.place(a, o, titleId);
        if (r == 2) readyKey = o.key;
        if (r == 1) { if (host != null) host.closeMenus(); showFor(o, null); }   // it was searching after all
        return r > 0;
    }

    /** An amiibo of the app's list: the player's own copy of it (made the first time), then as one of theirs */
    public static AmiiboStore.Own ownCopy(String id) {
        Activity a = act;
        if (a == null) return null;
        try {
            AmiiboStore.Own o = AmiiboStore.listed(a.getFilesDir(), id);
            return o != null ? o : AmiiboStore.fromList(a.getFilesDir(), id);
        } catch (java.io.IOException e) { Log.w(TAG, "amiibo: cannot keep " + id, e); return null; }
    }

    public static void takeOff() { readyKey = null; AmiiboStand.remove(); }

    private static void showFor(AmiiboStore.Own o, Runnable atCrack) { showFor(o, atCrack, null); }

    private static void showFor(AmiiboStore.Own o, Runnable atCrack, Bitmap known) {
        Activity a = act;
        if (a == null || root == null || o == null) { if (atCrack != null) atCrack.run(); return; }
        String name = AmiiboStand.db(a).get(o.id).title();
        boolean[] started = {false};
        java.util.function.Consumer<Bitmap> start = b -> {
            if (started[0] || act == null || root == null) return;
            started[0] = true;
            if (show != null) show.skip();
            show = AmiiboShow.play(root, b, name, () -> { if (atCrack != null) atCrack.run(); }, () -> show = null);
        };
        if (known != null) { start.accept(known); return; }
        AmiiboPictures.load(a, o.id, dev.peanuts.ingame.Look.dp(a, 380), start);
        UI.postDelayed(() -> start.accept(null), 500);   // offline or slow: the show without the picture (Peanuts' own card)
    }

    // ---- keys ----

    /** InGamePanel.backKey and menuKey: the card's A, B, left and right, and any button that skips the show; true when taken (the release with its press) */
    public static boolean key(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down) return taken.remove(k);
        if (show != null) { taken.add(k); if (e.getRepeatCount() == 0) show.skip(); return true; }
        AmiiboCard c = card;
        if (c == null || c.leaving() || host == null) return false;
        int[] ab = host.keys();
        int what = k == ab[0] || k == KeyEvent.KEYCODE_DPAD_CENTER ? K_A : k == ab[1] || k == KeyEvent.KEYCODE_BACK ? K_B
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? K_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? K_RIGHT : 0;
        if (what == 0 && !KeyEvent.isGamepadButton(k) && k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) return false;   // volume and the like
        taken.add(k);   // the card takes the controller while it shows: no button reaches the game
        if (what != 0 && (e.getRepeatCount() == 0 || what == K_LEFT || what == K_RIGHT)) c.key(what);
        return true;
    }

    private static boolean padAttached() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && d.supportsSource(InputDevice.SOURCE_GAMEPAD)) return true;
        }
        return false;
    }
}
