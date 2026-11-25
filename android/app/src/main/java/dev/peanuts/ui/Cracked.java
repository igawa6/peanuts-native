package dev.peanuts.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Peanuts cracked (CrackedCount's rules) kept in the app's preferences: the taps on the background are upstream's own counter (Prefs.bananasPopped, read
 * here), the points of builds, shaders, play time and firsts are Peanuts' ("peanuts" preferences, one line). About and the second display listen for
 * changes. Every call runs in the launcher's process.
 */
public final class Cracked {
    private Cracked() {}

    private static final String FILE = "peanuts", KEY = "cracked_points";
    private static final String UP_FILE = "launcher", UP_TAPS = "bananas_popped";   // upstream's Prefs (its file and key; read only)
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final List<Runnable> listeners = new ArrayList<>();
    private static SharedPreferences.OnSharedPreferenceChangeListener tapWatch;   // held: SharedPreferences keeps its listeners weakly

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    static synchronized CrackedCount.State state(Context c) { return CrackedCount.load(prefs(c).getString(KEY, null)); }

    private static synchronized void save(Context c, CrackedCount.State s) {
        prefs(c).edit().putString(KEY, CrackedCount.save(s)).apply();
        changed();
    }

    /** Upstream's preferences file, opened as upstream opens it (Prefs.open: MODE_MULTI_PROCESS, so a write of another process is seen) */
    @SuppressWarnings("deprecation")
    private static SharedPreferences upstream(Context c) { return c.getApplicationContext().getSharedPreferences(UP_FILE, Context.MODE_MULTI_PROCESS); }

    /** The peanuts tapped on the background, all time (upstream's counter: the old "Bananas Popped" carries over) */
    static long taps(Context c) {
        try { return upstream(c).getLong(UP_TAPS, 0); }
        catch (ClassCastException e) { return 0; }
    }

    /** Peanuts cracked: the taps and the points */
    public static long total(Context c) { return CrackedCount.total(state(c), taps(c)); }

    /** A build of a game version ended well, having prepared shaders shaders (0 when not known) */
    public static synchronized void built(Context c, String titleId, String version, long shaders) {
        CrackedCount.State s = state(c);
        if (CrackedCount.built(s, titleId, version, shaders) > 0) save(c, s);
    }

    /** A game was played for ms */
    public static synchronized void played(Context c, long ms) {
        CrackedCount.State s = state(c);
        long was = s.playMs;
        CrackedCount.played(s, ms);
        if (s.playMs != was || ms > 0) save(c, s);
    }

    /** A game started */
    public static synchronized void started(Context c, String titleId) {
        CrackedCount.State s = state(c);
        if (CrackedCount.started(s, titleId) > 0) save(c, s);
    }

    // ---- Play time ----

    /** The ledger's total already counted (ms); and the keys of 0.1.7's own measure of each run, read once to tell an update from a fresh install */
    private static final String SEEN = "cracked_play_seen", OLD_RUN = "cracked_run", OLD_SETTLED = "cracked_settled";
    /** One worker, in order: a Play's first start, then every settle after it (back in the launcher, About) */
    private static final java.util.concurrent.Executor BG = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "peanuts-cracked"));

    /**
     * The launcher starts a game (GameReturn.remember: Home's Play, a shortcut, a frontend): the title's first start counts. On the worker, not at the
     * Play's tap (igawa6's note on #315: the preferences, and before the play-time ledger a file and Android's process lists, were read there)
     */
    public static void starting(Context c, String titleId) {
        Context app = c.getApplicationContext();
        BG.execute(() -> started(app, titleId));
    }

    /** Back in the launcher (or About shows): the play time the ledger gained since the last look is counted, off the main thread */
    public static void settle(Context c) { Context app = c.getApplicationContext(); BG.execute(() -> settleNow(app)); }

    /**
     * The play time is the play-time ledger's (dev.peanuts.launch.PlayTime, files/peanuts-playtime.tsv: each game's time, counted only while it runs, not
     * while Build prepares its shaders, written by the game's process every 15 s and when it stops): what its total gained since the last look counts
     * (CrackedCount.fromLedger). 0.1.7's Peanuts cracked measured each run itself; on the first look after that, the ledger counts as counted so far
     * (CrackedCount.seenAtFirstLook), so no hour counts twice. On the worker only: it reads the ledger's file
     */
    private static synchronized void settleNow(Context c) {
        SharedPreferences p = prefs(c);
        long total = dev.peanuts.launch.PlayLedger.total(dev.peanuts.launch.PlayTime.all(c));
        boolean first = !p.contains(SEEN);
        long seen = first ? CrackedCount.seenAtFirstLook(p.contains(OLD_RUN) || p.contains(OLD_SETTLED), total) : p.getLong(SEEN, 0);
        if (!first && seen == total) return;
        p.edit().putLong(SEEN, total).remove(OLD_RUN).remove(OLD_SETTLED).apply();
        long add = CrackedCount.fromLedger(seen, total);
        if (add > 0) played(c, add);
    }

    /** The player saved a Mii of their own */
    public static synchronized void mii(Context c) {
        CrackedCount.State s = state(c);
        if (CrackedCount.mii(s) > 0) save(c, s);
    }

    /** r runs on the main thread when the total changed (a tap, a build, ...) until it is passed to unlisten */
    static void listen(Context c, Runnable r) {
        synchronized (listeners) { if (!listeners.contains(r)) listeners.add(r); }
        if (tapWatch == null) {
            tapWatch = (p, key) -> { if (UP_TAPS.equals(key)) changed(); };
            upstream(c).registerOnSharedPreferenceChangeListener(tapWatch);
        }
    }

    static void unlisten(Runnable r) { synchronized (listeners) { listeners.remove(r); } }

    private static void changed() {
        List<Runnable> now;
        synchronized (listeners) { now = new ArrayList<>(listeners); }
        for (Runnable r : now) UI.post(r);
    }
}
