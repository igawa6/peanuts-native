package dev.peanuts;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Peanuts Native's own settings (SharedPreferences "peanuts", never upstream's "prefs"): the setup (keys, firmware, the library's folders) and what the user chose per
 * title in its game sheet (the update file, the DLC files, the companion package). Paths are /storage paths the host can open (PathUtil's form), the keys and the
 * firmware's fonts live in the app's files folder (DESIGN-library.md "Folders and files").
 */
public final class PeanutsPrefs {
    private PeanutsPrefs() {}

    static SharedPreferences of(Context c) { return c.getSharedPreferences("peanuts", Context.MODE_PRIVATE); }

    // ---- Setup ----

    /** files/keys/prod.keys: where LibrarySettings copies the picked prod.keys (never shipped) — PEANUTS: fixed a stale comment naming the deleted SetupActivity */
    public static File keysFile(Context c) { return new File(new File(c.getFilesDir(), "keys"), "prod.keys"); }
    /** files/firmware/fonts: the shared fonts extracted from the user's firmware (standard.ttf, nintendo_ext.ttf) */
    public static File fontsDir(Context c) { return new File(new File(c.getFilesDir(), "firmware"), "fonts"); }
    /** files/icons: the titles' icons as PNG, <title ID>.png */
    public static File iconsDir(Context c) { return new File(c.getFilesDir(), "icons"); }

    public static boolean hasKeys(Context c) { return keysFile(c).isFile() && keysFile(c).length() > 0; }
    /** files/firmware/miimodel: the console's MiiModel system data extracted from the user's firmware beside the fonts (nximport.c font_try_mii), for DM_MII_MODEL */
    public static File miiModelDir(Context c) { return new File(fontsDir(c).getParentFile(), "miimodel"); }
    public static boolean hasFonts(Context c) { return new File(fontsDir(c), "standard.ttf").isFile(); }

    public static String firmwareDir(Context c) { return of(c).getString("firmware_dir", null); }
    public static void firmwareDir(Context c, String p) { put(c, "firmware_dir", p); }
    public static String gameDir(Context c) { return of(c).getString("game_dir", null); }
    public static void gameDir(Context c, String p) { put(c, "game_dir", p); }
    public static String updateDir(Context c) { return of(c).getString("update_dir", null); }
    public static void updateDir(Context c, String p) { put(c, "update_dir", p); }
    public static String dlcDir(Context c) { return of(c).getString("dlc_dir", null); }
    public static void dlcDir(Context c, String p) { put(c, "dlc_dir", p); }

    /** Settings › Library › Build folder: null (default) = beside the game, today's behaviour. A folder the user picked, on the phone or an SD card,
     *  to keep every build organized by title ID instead (one folder per game, nothing loose at its top level): {@code <build folder>/<title ID>/}. */
    public static String buildDir(Context c) { return of(c).getString("build_dir", null); }
    /** The default remains in the original build_dir preference. Other folders follow in the order added. */
    public static List<String> buildDirs(Context c) {
        SharedPreferences prefs = of(c);
        List<String> dirs = new ArrayList<>();
        String first = prefs.getString("build_dir", null);
        if (first != null && !first.isEmpty()) dirs.add(first);
        try {
            JSONArray extra = new JSONArray(prefs.getString("build_dirs_extra", "[]"));
            for (int i = 0; i < extra.length(); i++) {
                String dir = extra.optString(i, "");
                if (!dir.isEmpty() && !dirs.contains(dir)) dirs.add(dir);
            }
        } catch (org.json.JSONException ignored) { /* the saved default still works */ }
        return dirs;
    }

    public static void buildDirs(Context c, List<String> dirs) {
        SharedPreferences.Editor e = of(c).edit();
        if (dirs.isEmpty()) e.remove("build_dir"); else e.putString("build_dir", dirs.get(0));
        JSONArray extra = new JSONArray();
        for (int i = 1; i < dirs.size(); i++) extra.put(dirs.get(i));
        e.putString("build_dirs_extra", extra.toString()).apply();
    }

    public static String chosenBuildDir(Context c, String titleId) {
        String dir = of(c).getString(key(titleId, "build_dir"), null);
        return dir != null && buildDirs(c).contains(dir) ? dir : null;
    }
    public static void chosenBuildDir(Context c, String titleId, String dir) { put(c, key(titleId, "build_dir"), dir); }

    private static final java.util.regex.Pattern TITLE_ID = java.util.regex.Pattern.compile("[0-9A-Fa-f]{16}");

    /** Whether titleId is the 16 hex digits a build folder's own subfolder is named after: a build folder's top level only ever gets one of these, never a
     *  loose file or a folder named after anything else. */
    public static boolean validTitleId(String titleId) { return titleId != null && TITLE_ID.matcher(titleId).matches(); }

    /** {@code <build folder>/<title ID>}, when a build folder is set and titleId passes {@link #validTitleId}; null otherwise, so a caller falls back to
     *  today's places (beside the game, the app's own storage). */
    public static File buildGameDir(Context c, String titleId) {
        return buildGameDir(buildDir(c), titleId);
    }

    public static File buildGameDir(String root, String titleId) {
        if (root == null || root.isEmpty() || !validTitleId(titleId)) return null;
        return new File(root, titleId.toUpperCase(Locale.ROOT));
    }

    public static List<File> buildGameDirs(Context c, String titleId) {
        List<File> out = new ArrayList<>();
        if (validTitleId(titleId)) for (String root : buildDirs(c)) out.add(buildGameDir(root, titleId));
        return out;
    }

    /** {@code <build folder>/<title ID>/pack}: where a NEW build's pack goes when a build folder is set (AppStorage.pack still prefers an existing pack
     *  already in the app's own storage, so a build made before the folder was set keeps working from there). null without a build folder, or an invalid titleId. */
    public static File buildPackDir(Context c, String titleId) {
        java.util.regex.Matcher m = VARIANT_ID.matcher(titleId == null ? "" : titleId);
        if (m.matches()) {   // a build with code mods (ModStore.variantId) stays inside its game's folder: <title ID>/mod-<hash>/pack
            File g = buildGameDir(c, m.group(1));
            return g == null ? null : new File(g, "mod-" + m.group(2).toLowerCase(Locale.ROOT) + "/pack");
        }
        File g = buildGameDir(c, titleId);
        return g == null ? null : new File(g, "pack");
    }

    private static final java.util.regex.Pattern VARIANT_ID = java.util.regex.Pattern.compile("([0-9A-Fa-f]{16})-mod-([0-9A-Fa-f]+)");

    public static boolean setupDone(Context c) { return of(c).getBoolean("setup_done", false); }
    public static void setupDone(Context c, boolean on) { of(c).edit().putBoolean("setup_done", on).apply(); }

    /** Off by default: dev.peanuts.Companion only shows the chains/scan developer readout on the second display (instead of leaving it free) when this is on -
     * a long press on Settings › Library › Companions toggles it (LibrarySettings). A title with no companion package normally gets no second-screen window at
     * all, so the AYN Thor's own launcher stays on Screen-2, exactly as without Peanuts. */
    public static boolean developerReadout(Context c) { return of(c).getBoolean("developer_readout", false); }
    public static void developerReadout(Context c, boolean on) { of(c).edit().putBoolean("developer_readout", on).apply(); }

    /** Settings > Library > Bottom screen, on by default */
    public static boolean bottomScreen(Context c) { return of(c).getBoolean("bottom_screen", true); }
    public static void bottomScreen(Context c, boolean on) { of(c).edit().putBoolean("bottom_screen", on).apply(); }
    /** Settings > Library > Games without a companion: their cover stays on the second display, off by default */
    public static boolean bottomScreenInGames(Context c) { return of(c).getBoolean("bottom_screen_games", false); }
    public static void bottomScreenInGames(Context c, boolean on) { of(c).edit().putBoolean("bottom_screen_games", on).apply(); }

    /** Settings > Library > Haptics: false (default) = the launcher's haptics follow the system's touch feedback setting, true = none at all */
    public static boolean hapticsOff(Context c) { return of(c).getBoolean("haptics_off", false); }
    public static void hapticsOff(Context c, boolean off) { of(c).edit().putBoolean("haptics_off", off).apply(); }
    /** Settings > Library > Motion: false (default) = the launcher's animations follow the system's animator scale, true = fades and the progress fill only */
    public static boolean motionReduced(Context c) { return of(c).getBoolean("motion_reduced", false); }
    public static void motionReduced(Context c, boolean on) { of(c).edit().putBoolean("motion_reduced", on).apply(); }
    /** Settings > Library > Swap screens: false (default) = the game on the built-in screen and the companion on the other display, true = the other way round
     *  (a clip-on top screen that Android lists as the external display, GitHub issue #131). dev.peanuts.Screens applies it */
    public static boolean swapScreens(Context c) { return of(c).getBoolean("swap_screens", false); }
    public static void swapScreens(Context c, boolean on) { of(c).edit().putBoolean("swap_screens", on).apply(); }

    /** The roots the library scans: the game folder first, then the update and DLC folders when set and different, then the build folder (walked the same
     *  two levels deep, so its {@code <title ID>/<name>.nup} entries are found) */
    public static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        for (String p : new String[] {gameDir(c), updateDir(c), dlcDir(c)}) {
            if (p == null || p.isEmpty()) continue;
            File f = new File(p);
            if (!out.contains(f)) out.add(f);
        }
        for (String p : buildDirs(c)) {
            File f = new File(p);
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    // ---- Per title (the game sheet's choices) ----

    private static String key(String titleId, String what) { return "t." + titleId.toUpperCase(Locale.ROOT) + "." + what; }

    /** The update file the user chose for a title (null: the newest found) */
    public static String chosenUpdate(Context c, String titleId) { return of(c).getString(key(titleId, "update"), null); }
    public static void chosenUpdate(Context c, String titleId, String path) { put(c, key(titleId, "update"), path); }

    /** The DLC files the user added by hand for a title (beside the ones the scan matched) */
    public static List<String> chosenDlc(Context c, String titleId) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(of(c).getString(key(titleId, "dlc"), "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (org.json.JSONException e) { /* an unreadable list is an empty one */ }
        return out;
    }
    public static void chosenDlc(Context c, String titleId, List<String> paths) {
        JSONArray a = new JSONArray();
        for (String p : paths) a.put(p);
        of(c).edit().putString(key(titleId, "dlc"), a.toString()).apply();
    }

    /** The companion package the user wants installed for a title (null: none chosen) */
    public static String companion(Context c, String titleId) { return of(c).getString(key(titleId, "companion"), null); }
    public static void companion(Context c, String titleId, String packageName) { put(c, key(titleId, "companion"), packageName); }

    /** "Install the companion after the build" (the sheet's checkbox; on by default when the catalog has one and the device has a second display to show it on) */
    public static boolean installCompanion(Context c, String titleId) {
        String k = key(titleId, "install_companion");
        return of(c).contains(k) ? of(c).getBoolean(k, true) : Companion.hasSecondDisplay(c);
    }
    public static void installCompanion(Context c, String titleId, boolean on) { of(c).edit().putBoolean(key(titleId, "install_companion"), on).apply(); }

    // (Removed: a dead, unread duplicate of the "second screen physical/off" pref - dev.peanuts.settings.CompanionSection.secondScreen() is the one
    // Companion.java actually reads, in its own SharedPreferences file ("peanuts", MODE_MULTI_PROCESS).)

    private static void put(Context c, String k, String v) {
        SharedPreferences.Editor e = of(c).edit();
        if (v == null || v.isEmpty()) e.remove(k); else e.putString(k, v);
        e.apply();
    }
}
