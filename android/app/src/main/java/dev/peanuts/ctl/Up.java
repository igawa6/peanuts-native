package dev.peanuts.ctl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Upstream's launcher classes (dev.nxnative.android: MainActivity's pages, Game, GameSettings, Ondevice's Prepare card, Job, Prefs and the settings beside it;
 * all package-private) reached through reflection, as dev.peanuts.ui.Upstream and dev.peanuts.build.Upstream do, so the control commands call the launcher's
 * own methods and upstream's files need no change for them. Every name here is one of upstream's: a rename shows as a command's error
 * ("upstream changed: ..."), not as a compile error.
 */
final class Up {
    private static final String PKG = "dev.nxnative.android.";

    private Up() {}

    static Class<?> k(String simple) throws ClassNotFoundException { return Class.forName(PKG + simple); }

    static Object call(Class<?> k, Object self, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(self, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new ReflectiveOperationException(name + ": " + c, c);
        }
    }

    /** A method without arguments of the object's own class */
    static Object call(Object self, String name) throws ReflectiveOperationException { return call(self.getClass(), self, name, new Class<?>[0]); }

    static Object get(Class<?> k, Object self, String field) throws ReflectiveOperationException {
        Field f = k.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(self);
    }

    static Object get(Object self, String field) throws ReflectiveOperationException { return get(self.getClass(), self, field); }

    static void set(Class<?> k, Object self, String field, Object value) throws ReflectiveOperationException {
        Field f = k.getDeclaredField(field);
        f.setAccessible(true);
        f.set(self, value);
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    // ---- The launcher's screen ----

    static boolean isMain(Activity a) { return a != null && a.getClass().getName().equals(PKG + "MainActivity"); }
    static Intent mainIntent(Context c) throws ClassNotFoundException { return new Intent(c, k("MainActivity")); }

    /** Home (false) or Settings (true): MainActivity.page. The Advanced page (upstream's, open over Settings or a game's page) is closed first: page() alone only
     *  clears its flag when the page under it is the one asked for, and the screen went on showing Advanced */
    static void page(Activity main, boolean settings) throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(get(k("MainActivity"), main, "advancedOpen"))) call(k("MainActivity"), main, "closeAdvanced", new Class<?>[0]);
        call(k("MainActivity"), main, "page", new Class<?>[] {boolean.class}, settings);
    }

    /** home, settings, game or advanced: the page MainActivity shows (advanced: upstream's Advanced page, over Settings or over a game's page) */
    static String pageOf(Activity main) throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(get(k("MainActivity"), main, "advancedOpen"))) return "advanced";
        if (get(k("MainActivity"), main, "settingsGame") != null) return "game";
        return Boolean.TRUE.equals(get(k("MainActivity"), main, "settingsPage")) ? "settings" : "home";
    }

    /** The game whose page shows (a Game), else null */
    static Object pageGame(Activity main) throws ReflectiveOperationException { return get(k("MainActivity"), main, "settingsGame"); }

    static String tab() throws ReflectiveOperationException { return str(get(k("GameSettings"), null, "tab")); }

    /** Opens a game's page (GameSettings.open: a tap on its tile), then its tab when one is named; the tabs a game without a profile has are Saves and Storage */
    static void openGame(Activity main, Object game, String tab) throws ReflectiveOperationException {
        call(k("GameSettings"), null, "open", new Class<?>[] {k("MainActivity"), k("Game")}, main, game);
        if (tab == null || tab.equals(tab())) return;
        set(k("GameSettings"), null, "tab", tab);
        call(k("MainActivity"), main, "gameSettingsTop", new Class<?>[0]);
    }

    /** MainActivity.launch: Home's Play, with its checks and dialogs */
    static void launch(Activity main, Object game) throws ReflectiveOperationException { call(k("MainActivity"), main, "launch", new Class<?>[] {k("Game")}, game); }

    /** MainActivity.needAccess(file): true when All files access is missing for it (the launcher then shows its prompt) */
    static boolean needAccess(Activity main, File f) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(k("MainActivity"), main, "needAccess", new Class<?>[] {File.class}, f));
    }

    // ---- The game list ----

    /** One entry of upstream's list */
    static final class Game {
        final Object raw; final String titleId, name, gameDir, packDir; final long used;
        Game(Object raw) throws ReflectiveOperationException {
            this.raw = raw;
            titleId = str(get(raw, "titleId")); name = str(get(raw, "name")); gameDir = str(get(raw, "gameDir")); packDir = str(get(raw, "packDir"));
            used = ((Number) get(raw, "used")).longValue();
        }
    }

    static List<Game> games(Context c) throws ReflectiveOperationException {
        List<Game> out = new ArrayList<>();
        for (Object g : (List<?>) call(k("Game"), null, "load", new Class<?>[] {Context.class}, c)) out.add(new Game(g));
        return out;
    }

    /** The list's entry of a title: the one played last when it holds the title twice; null when it is not in the list */
    static Game game(Context c, String titleId) throws ReflectiveOperationException {
        Game hit = null;
        for (Game g : games(c)) if (g.titleId.equalsIgnoreCase(titleId) && (hit == null || g.used > hit.used)) hit = g;
        return hit;
    }

    static boolean hasProfile(String titleId) throws ReflectiveOperationException { return call(k("Profile"), null, "of", new Class<?>[] {String.class}, titleId) != null; }

    /** Pack.isDump: a game Prepare can work from */
    static boolean isDump(File game) throws ReflectiveOperationException { return Boolean.TRUE.equals(call(k("Pack"), null, "isDump", new Class<?>[] {File.class}, game)); }

    // ---- Upstream's Prepare card (Ondevice.Panel) and its job ----

    /** The Prepare card of this activity, null when Home has not built one */
    static Object panel(Activity main) throws ReflectiveOperationException {
        Object p = get(k("Ondevice"), null, "panel");
        return p != null && get(p, "a") == main ? p : null;
    }

    static Object panelJob(Object panel) throws ReflectiveOperationException { return get(panel, "job"); }

    /** The card's first button as it reads now: Pause, Resume, Retry or Start; "" while it is hidden */
    static String panelButton(Object panel) throws ReflectiveOperationException {
        android.widget.Button go = (android.widget.Button) get(panel, "go");
        return go.getVisibility() == android.view.View.VISIBLE ? str(go.getText()) : "";
    }

    static boolean panelOffersAnyway(Object panel) throws ReflectiveOperationException {
        return ((android.view.View) get(panel, "anyway")).getVisibility() == android.view.View.VISIBLE;
    }

    static void panelGoOrPause(Object panel) throws ReflectiveOperationException { call(panel, "goOrPause"); }

    /** The card's Prepare again without its question (the path the launcher's own start flag takes): checked off the main thread, then started or refused in a dialog */
    static void panelPrepareAgain(Object panel, File dump) throws ReflectiveOperationException {
        call(panel.getClass(), panel, "picked", new Class<?>[] {File.class, boolean.class, File.class, boolean.class}, dump, true, null, true);
    }

    /** What the card's Try anyway does after its warning was accepted: the job goes on with its checks relaxed, from where it stopped */
    static void panelTryAnyway(Object panel, Object job) throws ReflectiveOperationException {
        call(job.getClass(), job, "tryAnyway", new Class<?>[] {boolean.class}, true);
        call(job, "save");
        call(panel, "show");
        call(panel.getClass(), panel, "startService", new Class<?>[] {job.getClass()}, job);
    }

    /** The job the launcher shows (Job.remembered), read from its file; null when there is none or it does not read */
    static Object job(Context c) {
        try {
            File f = (File) call(k("Job"), null, "remembered", new Class<?>[] {Context.class}, c);
            return f == null || !f.isFile() ? null : call(k("Job"), null, "read", new Class<?>[] {File.class}, f);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String jobText(Object job, String method) throws ReflectiveOperationException { return str(call(job, method)); }

    /** Asks the Prepare service to pause its job, as the card's Pause does (PackService.ACTION_PAUSE; "adb" is who asked, for its log) */
    static void pausePack(Context c) throws ReflectiveOperationException {
        Class<?> s = k("PackService");
        c.startService(new Intent(c, s).setAction(str(get(s, null, "ACTION_PAUSE"))).putExtra(str(get(s, null, "EXTRA_FROM")), "adb"));
    }

    // ---- Settings ----

    static SharedPreferences prefs(Context c, String name) { return c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS); }   // Prefs.open's mode: the game and the Prepare service read them too

    static String buildId() throws ReflectiveOperationException { return str(call(k("Prefs"), null, "buildId", new Class<?>[0])); }

    static boolean prefsBool(String method, String titleId) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(k("Prefs"), null, method, new Class<?>[] {String.class}, titleId));
    }

    static boolean prefsBool(Context c, String method, String titleId) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(k("Prefs"), null, method, new Class<?>[] {Context.class, String.class}, c, titleId));
    }

    static int[] caps(boolean paced) throws ReflectiveOperationException { return ((int[]) get(k("Prefs"), null, paced ? "PACE_CAPS" : "FPS_CAPS")).clone(); }
    static int[] caps(boolean paced, String titleId) throws ReflectiveOperationException {
        return paced ? (int[]) call(k("Prefs"), null, "paceCaps", new Class<?>[] {String.class}, titleId) : caps(false);
    }

    /** The game's own environment lines (its Game settings) */
    static String gameEnv(Context c, String titleId) throws ReflectiveOperationException {
        return str(call(k("Prefs"), null, "gameEnv", new Class<?>[] {Context.class, String.class}, c, titleId));
    }

    /** Why the game has no such row in its settings (null: it has one). The rows are ScreenMode's, ResScale's, the Upscaler's and the Frame rate cap's */
    static String rowMissing(Context c, String key, String titleId) throws ReflectiveOperationException {
        switch (key) {
            case "console_mode": {
                Object fx = call(k("ScreenMode"), null, "fixed", new Class<?>[] {String.class}, titleId);
                return fx == null ? null : str(fx).isEmpty() ? "this game's Resolution sets the console mode" : str(fx);
            }
            case "res_scale":
                return Boolean.TRUE.equals(call(k("ResScale"), null, "hidden", new Class<?>[] {String.class}, titleId)) ? "this game has its own Resolution and Upscaling quality" : null;
            case "upscaler":
                if (prefsBool("dlssGame", titleId)) return "this game's Upscaler row is the DLSS stand-in's";
                return prefsBool(c, "simpleRes", titleId) ? "this game's Resolution presets set the upscaler (Tester settings shows the row)" : null;
            case "fps_cap":
                return null;   // Game owned remains selectable; the allowed-cap list rejects unavailable rates
            default: return null;
        }
    }

    /** Stores a checked change through the setter its row uses */
    static void apply(Context c, Settings.Change ch, String titleId) throws ReflectiveOperationException {
        Class<?>[] global = {Context.class, int.class}, own = {Context.class, String.class, Integer.class};
        switch (ch.key) {
            case "console_mode":
                if (ch.perGame) call(k("ScreenMode"), null, "set", own, c, titleId, ch.value); else call(k("ScreenMode"), null, "global", global, c, ch.value);
                break;
            case "res_scale":
                if (ch.perGame) call(k("ResScale"), null, "set", own, c, titleId, ch.value); else call(k("ResScale"), null, "global", global, c, ch.value);
                break;
            case "upscaler":
                if (ch.perGame) call(k("GameUpscaler"), null, "mode", own, c, titleId, ch.value); else call(k("GameUpscaler"), null, "modeDefault", global, c, ch.value);
                break;
            case "fps_cap":
                if (ch.perGame) call(k("Prefs"), null, "fpsCap", own, c, titleId, ch.value); else call(k("Prefs"), null, "fpsCap", global, c, ch.value);
                break;
            case "overlay": call(k("Prefs"), null, "overlay", new Class<?>[] {Context.class, boolean.class}, c, ch.value); break;
            case "keep_screen_on": call(k("Prefs"), null, "keepScreenPreparing", new Class<?>[] {Context.class, boolean.class}, c, ch.value); break;
            default: throw new NoSuchMethodException(ch.key);
        }
    }

    /** The GPU driver every game starts with unless it has its own: the picked driver's label, "system" for the phone's own */
    static String gpuDriver(Context c) throws ReflectiveOperationException {
        Object info = call(k("GpuDriver"), null, "selected", new Class<?>[] {Context.class}, c);
        return info == null ? "system" : str(call(info, "label"));
    }
}
