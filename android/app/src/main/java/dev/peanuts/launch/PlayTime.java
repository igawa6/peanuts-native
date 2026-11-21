package dev.peanuts.launch;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Total play time per game. {@link #tick} is a one-line hook at the top of {@code GameActivity.fpsTick} (every 500 ms while the game shows, every 2 s while it is
 * stopped or paused): only time with the game running counts, that is started, its screen resumed, not stopped or paused, and not Build's shader preparation
 * (dev.peanuts.build.ShaderCapture). The time is kept in memory and added to {@code files/peanuts-playtime.tsv} ({@link PlayLedger}) every 15 s, when the game stops
 * running, and in {@code onDestroy} ({@link #flush}, the second hook), so a process that is killed loses at most 15 s. The game runs in the {@code :game} process and
 * Home reads the file from the main one: each write goes to a temporary file that is then renamed over it, so a reader never sees half a file.
 * <p>
 * Titles are keyed as {@link RecentGames} keys them: the title ID the library holds ({@code peanuts_list_id}, an edition's start), else the game's own.
 */
public final class PlayTime {
    private static final long FLUSH_MS = 15_000, MAX_STEP_MS = 5_000;
    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "peanuts-playtime"));

    // main thread only (GameActivity's ui handler)
    private static long prevTick, sinceFlush;
    private static String pendingId;
    private static long pendingMs;
    // this process's session (one game per :game process): what Home's toast says after Stop
    private static String sessionId, sessionName;
    private static long sessionMs;

    private PlayTime() { }

    /** From GameActivity.fpsTick: {@code running} is the game running with its screen shown */
    public static void tick(Activity a, String id, boolean running) {
        String key = key(a, id);
        long now = SystemClock.elapsedRealtime();
        boolean on = running && key != null && !dev.peanuts.build.ShaderCapture.active(a);
        if (on) {
            if (pendingId != null && !pendingId.equals(key)) flushAsync(a);
            pendingId = key;
            long d = PlayLedger.step(prevTick, now, MAX_STEP_MS);
            pendingMs += d;
            if (!key.equals(sessionId)) { sessionId = key; sessionMs = 0; }
            sessionMs += d;
            if (sessionName == null && a.getIntent() != null) sessionName = a.getIntent().getStringExtra("name");
            sinceFlush += d;
            prevTick = now;
            if (sinceFlush >= FLUSH_MS) flushAsync(a);
        } else {
            prevTick = 0;
            if (pendingMs > 0) flushAsync(a);
        }
    }

    /**
     * From GameActivity.onDestroy: what is still in memory is written now, on this thread (Stop ends the process soon after). When the screen goes for good
     * (Stop, not a recreation while the game runs on) the session is also left for Home ({@link #sessionNote}).
     */
    public static void flush(Activity a) {
        prevTick = 0;
        String id = pendingId; long ms = pendingMs;
        pendingMs = 0; sinceFlush = 0;
        Context c = a.getApplicationContext();
        boolean ends = a.isFinishing() && !a.isChangingConfigurations() && sessionId != null && sessionMs > 0;
        write(c, id, ms, ends ? sessionId : null, ends ? sessionMs : 0);
        if (ends) {
            String line = sessionId + "\t" + sessionMs + "\t" + System.currentTimeMillis() + "\t" + (sessionName == null ? "" : sessionName.replace('\t', ' ').replace('\n', ' '));
            File f = sessionFile(c), tmp = new File(f.getPath() + ".tmp");
            try {
                Files.write(tmp.toPath(), line.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) { Log.w("peanuts", "PlayTime: session: " + e); }
            sessionId = null; sessionName = null; sessionMs = 0;
        }
    }

    /**
     * Home's resume: the session a game's Stop left, as one line for a toast ("Kirby: 42 min this time, 12 h in all"), once; null when there is none, it is older
     * than two minutes (Home was not where the player came back to) or shorter than a minute
     */
    public static String sessionNote(Context c) {
        File f = sessionFile(c);
        String s = read(f);
        if (s == null) return null;
        f.delete();
        String[] p = s.split("\t", 4);
        try {
            long ms = Long.parseLong(p[1]), end = Long.parseLong(p[2]);
            if (ms < 60_000 || Math.abs(System.currentTimeMillis() - end) > 120_000) return null;
            PlayLedger.Entry all = PlayLedger.parse(read(file(c))).get(p[0].toUpperCase(java.util.Locale.ROOT));
            String name = p.length > 3 && !p[3].trim().isEmpty() ? p[3].trim() : "Played";
            return name + ": " + PlayLedger.text(ms) + " this time" + (all != null && all.ms > ms ? ", " + PlayLedger.text(all.ms) + " in all" : "");
        } catch (RuntimeException e) { return null; }
    }

    /** Settings › Library's Played line ("86 h across 7 games"), or null when nothing was played */
    public static String totalLine(Context c) {
        Map<String, PlayLedger.Entry> m = all(c);
        long ms = PlayLedger.total(m);
        if (ms <= 0) return null;
        int n = 0;
        for (PlayLedger.Entry e : m.values()) if (e.ms > 0) n++;
        return PlayLedger.text(ms) + " across " + n + (n == 1 ? " game" : " games");
    }

    private static void flushAsync(Context c) {
        String id = pendingId; long ms = pendingMs;
        pendingMs = 0; sinceFlush = 0;
        if (id == null || ms <= 0) return;
        Context app = c.getApplicationContext();
        IO.execute(() -> write(app, id, ms, null, 0));
    }

    /** Adds ms to id's total and, when a session ended (runId), stamps its length as that title's Last run */
    private static void write(Context c, String id, long ms, String runId, long runMs) {
        if ((id == null || ms <= 0) && runId == null) return;
        synchronized (LOCK) {
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            try {
                Map<String, PlayLedger.Entry> m = PlayLedger.parse(read(f));
                PlayLedger.add(m, id, ms, System.currentTimeMillis());
                PlayLedger.lastRun(m, runId, runMs);
                Files.write(tmp.toPath(), PlayLedger.format(m).getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) { Log.w("peanuts", "PlayTime: write " + f + ": " + e); }
        }
    }

    /** The title's total and last-played time, or null when it was never played with this app */
    public static PlayLedger.Entry of(Context c, String titleId) {
        if (titleId == null) return null;
        PlayLedger.Entry e = PlayLedger.parse(read(file(c))).get(titleId.toUpperCase(java.util.Locale.ROOT));
        return e == null || e.ms <= 0 ? null : e;
    }

    /** Every title's entry, keyed by its title ID in upper case (Home's covers); empty when nothing was played */
    public static Map<String, PlayLedger.Entry> all(Context c) { return PlayLedger.parse(read(file(c))); }

    /** The game page's Played line ("3 h 5 min · last 20 Nov 2026"), or null when the game was never played with this app */
    public static CharSequence line(Context c, String titleId) {
        PlayLedger.Entry e = of(c, titleId);
        if (e == null) return null;
        return dev.peanuts.ui.PUi.joined(PlayLedger.text(e.ms),
                e.last > 0 ? "last " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(e.last)) : null);
    }

    private static String key(Activity a, String id) {
        String list = a.getIntent() == null ? null : a.getIntent().getStringExtra("peanuts_list_id");
        String k = list != null && !list.isEmpty() ? list : id;
        return k == null || k.isEmpty() || "null".equals(k) ? null : k.toUpperCase(java.util.Locale.ROOT);
    }

    private static String read(File f) {
        try { return f.isFile() && f.length() < (1 << 20) ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; }
        catch (Exception e) { return null; }
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "peanuts-playtime.tsv"); }

    private static File sessionFile(Context c) { return new File(c.getFilesDir(), "peanuts-playtime-session.txt"); }
}
