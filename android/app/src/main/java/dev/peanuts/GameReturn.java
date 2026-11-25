package dev.peanuts;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Display;

import dev.peanuts.ctl.ExitRecord;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Opening the app from its icon while a game runs goes back to the game, not to Home.
 *
 * Home (MainActivity) is singleTask and the task's root, so the launcher's intent brings Home itself forward and Android closes the game's screen above it. The
 * game is not stopped by that (its process and its host go on, suspended), but the player landed on Home and had to tap the game's cover to get back (seen on
 * the AYN Thor, 2026-11-15). Here the last game started is remembered, and a plain launcher start with the game's process alive starts its screen again: the
 * game shows its "Paused" screen as after any return.
 *
 * Only the launcher's own intent does this (MAIN + LAUNCHER, no extras): a shortcut, a frontend or an adb start that names what it wants is left alone.
 */
public final class GameReturn {
    private static final String PREFS = "peanuts_game_return", GAME_ACTIVITY = "dev.nxnative.android.GameActivity", GAME_PROCESS = ":game";
    private static final String[] KEYS = {"title_id", "name", "game", "pack"};

    private GameReturn() {}

    /** A game's screen is being started with these extras (Home's Play, a shortcut or a frontend): kept for {@link #back}. */
    public static void remember(Context c, String titleId, String name, String game, String pack) {
        if (titleId == null || game == null || pack == null) return;
        dev.peanuts.ui.Cracked.starting(c, titleId);   // Peanuts cracked: a game's first start, and the time the run before it was played
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title_id", titleId).putString("name", name == null ? "" : name).putString("game", game).putString("pack", pack).apply();
    }

    /** The game's screen was started on this display (Screens.startGame: Swap screens), Display.INVALID_DISPLAY: on the launcher's own. Kept for {@link #back} */
    static void startedOn(Context c, int display) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("display", display).apply();
    }

    /** True when {@code launch} is the launcher's plain start and a game runs: its screen was started again, and Home has nothing more to do with this intent. */
    public static boolean back(Activity home, Intent launch) {
        if (launch == null || !Intent.ACTION_MAIN.equals(launch.getAction()) || !launch.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
        if (launch.getExtras() != null && !launch.getExtras().isEmpty()) return false;
        if (!processAlive(home, GAME_PROCESS, -1)) return false;
        SharedPreferences p = home.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // The game that runs must be the remembered one: its own run record names it and its process (last-exit.txt, a few short lines the game writes at
        // its start; the control interface reads it the same way). Another title, or a record whose process is gone, starts nothing
        Map<String, String> rec = record(home);
        int pid = runningPid(rec, p.getString("title_id", null));
        if (pid <= 0 || !processAlive(home, GAME_PROCESS, pid)) {
            Log.i(Companion.TAG, "opened from the launcher while a game process runs, but " + (pid <= 0 ? "its run record names " + rec.get("title") + ", not the game started last"
                    : "the recorded game's process " + pid + " is gone") + ": Home stays");
            return false;
        }
        Intent i = new Intent().setClassName(home, GAME_ACTIVITY);
        for (String k : KEYS) {
            String v = p.getString(k, null);
            if (v == null) return false;   // no game was started by this install's Home yet
            i.putExtra(k, v);
        }
        try {
            // Swap screens started the game on another display (Screens.startGame): it goes back there. A plain start moved its task to Home's display, and the
            // companion to the other one, so the swap was undone (seen on the AYN Thor, 2026-11-19). That display gone or refused: the start as before
            int display = p.getInt("display", Display.INVALID_DISPLAY);
            boolean there = display != Display.INVALID_DISPLAY && Screens.startOn(home, i, display);
            if (!there) home.startActivity(i);
            Log.i(Companion.TAG, "opened from the launcher while " + p.getString("name", "a game") + " runs: back to the game" + (there ? " on display " + display : ""));
            return true;
        } catch (RuntimeException e) {
            Log.w(Companion.TAG, "could not go back to the running game: " + e);
            return false;
        }
    }

    /** The game's own record of its run (dev.peanuts.ctl.ExitRecord); empty when there is none */
    private static Map<String, String> record(Context c) {
        File f = new File(c.getFilesDir(), "last-exit.txt");
        try { return ExitRecord.parse(f.isFile() && f.length() < 4096 ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null); }
        catch (IOException e) { return ExitRecord.parse(null); }
    }

    /** The pid the run record gives for the remembered title; -1 when the record names another title, none, or no pid. No Android class: the JVM test drives it */
    public static int runningPid(Map<String, String> rec, String rememberedTitle) {
        String title = rec.get("title");
        if (rememberedTitle == null || title == null || !title.equalsIgnoreCase(rememberedTitle)) return -1;
        int pid = ExitRecord.pid(rec);
        return pid > 0 ? pid : -1;
    }

    /** A process of this app whose name ends with suffix runs (pid &gt; 0: with that pid). The control interface's status asks the same (dev.peanuts.ctl) */
    public static boolean processAlive(Context c, String suffix, int pid) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.endsWith(suffix) && (pid <= 0 || p.pid == pid)) return true;
        return false;
    }
}
