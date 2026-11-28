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
    /** What the importer that last read the firmware takes from one (LibrarySettings.FIRMWARE_READ); 0 when it was read before the app kept this */
    public static int firmwareRead(Context c) { return of(c).getInt("firmware_read", 0); }
    public static void firmwareRead(Context c, int what) { of(c).edit().putInt("firmware_read", what).apply(); }
    /** The first game folder, null when there is none (where "is a game folder set" is all a caller asks; the list is {@link #gameDirs}) */
    public static String gameDir(Context c) {
        List<String> l = gameDirs(c);
        return l.isEmpty() ? null : l.get(0);
    }

    /**
     * The game folders (issue #546: several, on the phone and an SD card). The first stays in "game_dir", where the app kept its one game folder before, so an
     * install that had one has it here as the first, and the others follow in "game_dirs_extra". The same way as the build folders (buildDirs)
     */
    public static List<String> gameDirs(Context c) {
        return dev.peanuts.folders.FolderList.read(java.util.Collections.singletonList(of(c).getString("game_dir", null)), extra(c, "game_dirs_extra"));
    }
    public static void gameDirs(Context c, List<String> dirs) { list(c, "game_dir", null, "game_dirs_extra", dirs); }

    /**
     * The folders with updates and DLC (one kind since issue #546: the scan tells an update from a DLC by what the file holds, never by its folder). The update
     * folder and the DLC folder an older version kept ("update_dir", "dlc_dir") are the first two; added ones follow in "addon_dirs_extra". A change keeps the
     * first in "update_dir" and moves the DLC folder into the list
     */
    public static List<String> addonDirs(Context c) {
        SharedPreferences p = of(c);
        return dev.peanuts.folders.FolderList.read(java.util.Arrays.asList(p.getString("update_dir", null), p.getString("dlc_dir", null)), extra(c, "addon_dirs_extra"));
    }
    public static void addonDirs(Context c, List<String> dirs) { list(c, "update_dir", "dlc_dir", "addon_dirs_extra", dirs); }

    /** A JSON list of paths in key ("[]" when none or unreadable) */
    private static List<String> extra(Context c, String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(of(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) { String d = a.optString(i, ""); if (!d.isEmpty()) out.add(d); }
        } catch (org.json.JSONException ignored) { /* the first folder still works */ }
        return out;
    }

    /** A folder list kept as its first in firstKey and the others in extraKey; oldKey (a second single folder of an older version) is folded into the list */
    private static void list(Context c, String firstKey, String oldKey, String extraKey, List<String> dirs) {
        SharedPreferences.Editor e = of(c).edit();
        if (dirs.isEmpty()) e.remove(firstKey); else e.putString(firstKey, dirs.get(0));
        if (oldKey != null) e.remove(oldKey);
        JSONArray extra = new JSONArray();
        for (int i = 1; i < dirs.size(); i++) extra.put(dirs.get(i));
        if (extra.length() == 0) e.remove(extraKey); else e.putString(extraKey, extra.toString());
        e.apply();
    }

    /** The game folder an import of this game file writes its .nup into when no build folder is set: the game folder that holds it, else the first; null without one */
    public static File homeFor(Context c, File gameFile) {
        return dev.peanuts.folders.FolderList.home(gameDirs(c), gameFile == null ? null : gameFile.getAbsolutePath());
    }

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
     * a long press on Settings › Screens › Companions toggles it (LibrarySettings). A title with no companion package normally gets no second-screen window at
     * all, so the AYN Thor's own launcher stays on Screen-2, exactly as without Peanuts. */
    public static boolean developerReadout(Context c) { return of(c).getBoolean("developer_readout", false); }
    public static void developerReadout(Context c, boolean on) { of(c).edit().putBoolean("developer_readout", on).apply(); }

    /** Settings > Library > Bottom screen, on by default */
    public static boolean bottomScreen(Context c) { return of(c).getBoolean("bottom_screen", true); }
    public static void bottomScreen(Context c, boolean on) { of(c).edit().putBoolean("bottom_screen", on).apply(); }
    /** Settings > Library > Games without a companion: Peanuts' page (live stats or the cover) stays on the second display, on by default. A tap on another
     *  app's screen there (the AYN Thor's launcher) made that app the top one and moved the game's process from Android's top-app group to foreground, where the
     *  Thor's scheduler kept its threads on the small cores (94-97 % of their running time, measured 2026-11-24); a tap on Peanuts' page keeps the game the top
     *  app (the page is a window of the game's process). Off before: a player's own choice stays */
    public static boolean bottomScreenInGames(Context c) { return of(c).getBoolean("bottom_screen_games", true); }
    public static void bottomScreenInGames(Context c, boolean on) {
        SharedPreferences.Editor e = of(c).edit().putBoolean("bottom_screen_games", on);
        if (on && !of(c).contains("bottom_screen_games_page")) e.putString("bottom_screen_games_page", GAMES_STATS);   // turned on now: the stats page, not the upgrade's cover
        e.apply();
    }
    /** What those games show there: their live stats (dev.peanuts.ui.StatsView, the default) or their cover */
    public static final String GAMES_STATS = "stats", GAMES_COVER = "cover";
    /** A player who had "Games without a companion" on before the stats page existed keeps the cover they had; turning it on later starts on the stats page */
    public static String bottomScreenGamesPage(Context c) {
        SharedPreferences p = of(c);
        if (!p.contains("bottom_screen_games_page") && p.getBoolean("bottom_screen_games", false)) { p.edit().putString("bottom_screen_games_page", GAMES_COVER).apply(); return GAMES_COVER; }
        return GAMES_COVER.equals(p.getString("bottom_screen_games_page", GAMES_STATS)) ? GAMES_COVER : GAMES_STATS;
    }
    public static void bottomScreenGamesPage(Context c, String page) { of(c).edit().putString("bottom_screen_games_page", page).apply(); }
    /** The stats page's items (dev.peanuts.ui.StatsConfig.format; null: never set, every item) and its burn-in guards (StatsConfig.GUARDS, each on by default), for every game */
    public static String statsShown(Context c) { return of(c).getString("stats_shown", null); }
    public static void statsShown(Context c, String items) { of(c).edit().putString("stats_shown", items).apply(); }
    public static boolean statsGuard(Context c, String guard) { return of(c).getBoolean("stats_guard_" + guard, true); }
    public static void statsGuard(Context c, String guard, boolean on) { of(c).edit().putBoolean("stats_guard_" + guard, on).apply(); }

    /** Settings > Library > Haptics: false (default) = the launcher's haptics follow the system's touch feedback setting, true = none at all */
    public static boolean hapticsOff(Context c) { return of(c).getBoolean("haptics_off", false); }
    public static void hapticsOff(Context c, boolean off) { of(c).edit().putBoolean("haptics_off", off).apply(); }
    /** Settings > Library > Motion: false (default) = the launcher's animations follow the system's animator scale, true = fades and the progress fill only */
    public static boolean motionReduced(Context c) { return of(c).getBoolean("motion_reduced", false); }
    public static void motionReduced(Context c, boolean on) { of(c).edit().putBoolean("motion_reduced", on).apply(); }
    /** Settings > App > Extras > Smooth Experience (dev.peanuts.smooth.Smooth): the switch, off by default, and each of its items (SmoothItems), on by default.
     *  commit, not apply: the game's process reads them at its start, which may come right after the change */
    public static boolean smooth(Context c) { return of(c).getBoolean("smooth", false); }
    public static void smooth(Context c, boolean on) { of(c).edit().putBoolean("smooth", on).commit(); }
    public static boolean smoothItem(Context c, String key) { return of(c).getBoolean("smooth_" + key, true); }
    public static void smoothItem(Context c, String key, boolean on) { of(c).edit().putBoolean("smooth_" + key, on).commit(); }
    /** Settings > Library > Swap screens: false (default) = the game on the built-in screen and the companion on the other display, true = the other way round
     *  (a clip-on top screen that Android lists as the external display, GitHub issue #131). dev.peanuts.Screens applies it */
    public static boolean swapScreens(Context c) { return of(c).getBoolean("swap_screens", false); }
    public static void swapScreens(Context c, boolean on) { of(c).edit().putBoolean("swap_screens", on).apply(); }
    /** Home's Sort: "last" (default: last played, then A–Z), "az", "added" (newest game file first) or "time" (most play time first) */
    public static String librarySort(Context c) { return of(c).getString("library_sort", "last"); }
    public static void librarySort(Context c, String mode) { of(c).edit().putString("library_sort", mode).apply(); }
    /** Home's Sort: true (default) = Ready, Build needed and Not supported yet stay apart in All, the order applies inside each; false = one list */
    public static boolean librarySortGrouped(Context c) { return of(c).getBoolean("library_sort_grouped", true); }
    public static void librarySortGrouped(Context c, boolean on) { of(c).edit().putBoolean("library_sort_grouped", on).apply(); }
    /** Home's view (the View button, Settings › App › Look › Home view): "grid" (default), "row", "compact", "carousel" or "covers" (dev.peanuts.ui.HomeViews) */
    public static String homeView(Context c) { return of(c).getString("home_view", "grid"); }
    public static void homeView(Context c, String key) { of(c).edit().putString("home_view", key).apply(); }

    /** Settings › TV and monitors (dev.peanuts.Screens, #358): true a game plays on a connected TV or monitor; false (the default) the system decides what the TV
     *  shows (Android mirrors the screen, a handheld's firmware may turn its own screens off), and Peanuts only keeps its pages off the TV */
    public static boolean tvPlay(Context c) { return of(c).getBoolean("tv_play", false); }
    public static void tvPlay(Context c, boolean on) { of(c).edit().putBoolean("tv_play", on).apply(); }

    /** The roots the library scans: the game folders first, then the update and DLC folders, then the build folders (walked the same two levels deep, so their
     *  {@code <title ID>/<name>.nup} entries are found); a folder in two lists once */
    public static List<File> roots(Context c) {
        List<String> all = new ArrayList<>(gameDirs(c));
        all.addAll(addonDirs(c));
        all.addAll(buildDirs(c));
        List<File> out = new ArrayList<>();
        for (String p : dev.peanuts.folders.FolderList.read(all, null)) out.add(new File(p));
        return out;
    }

    // ---- Per title (the game sheet's choices) ----

    private static String key(String titleId, String what) { return "t." + titleId.toUpperCase(Locale.ROOT) + "." + what; }

    /** The update file the user chose for a title (null: the newest found) */
    /** When Home first saw the game (dev.peanuts.ui.LibrarySort.firstSeen; Sort's Recently added), 0 before that */
    public static long firstSeen(Context c, String titleId) { return of(c).getLong(key(titleId, "first_seen"), 0); }
    /** Keeps first-seen times by title ID, in one write */
    public static void firstSeen(Context c, java.util.Map<String, Long> seen) {
        SharedPreferences.Editor e = of(c).edit();
        for (java.util.Map.Entry<String, Long> s : seen.entrySet()) e.putLong(key(s.getKey(), "first_seen"), s.getValue());
        e.apply();
    }
    public static String chosenUpdate(Context c, String titleId) { return of(c).getString(key(titleId, "update"), null); }
    public static void chosenUpdate(Context c, String titleId, String path) { put(c, key(titleId, "update"), path); }

    /** The newer update and add-ons found beside a built game that the player answered "Not now" for (dev.peanuts.build.UpdateFound.Found.key): those ask no more */
    public static String foundSeen(Context c, String titleId) { return of(c).getString(key(titleId, "found_seen"), ""); }
    public static void foundSeen(Context c, String titleId, String found) { put(c, key(titleId, "found_seen"), found); }

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

    /**
     * The game sheet's Import pack… while the system picker is open (dev.peanuts.pack.FirstImport.Pick's text; dev.peanuts.ui.PackImportUi): Android may end the
     * process meanwhile, and the picker's answer then comes to a new one. null when no picker of it is open
     */
    public static String packImportPick(Context c) { return of(c).getString("pack_import_pick", null); }
    public static void packImportPick(Context c, String pick) { put(c, "pack_import_pick", pick); }
    /** A game's Mods &amp; Cheats › Show in the game: its Mods tab in the in-game panel (SPEC-mod-menu.md, Package C); on by default */
    public static boolean modsTab(Context c, String titleId) { return of(c).getBoolean(key(titleId, "mods_tab"), true); }
    public static void modsTab(Context c, String titleId, boolean on) { of(c).edit().putBoolean(key(titleId, "mods_tab"), on).apply(); }

    /** Settings › App › Mods tab: the in-game panel's Mods tab for every game; on by default */
    public static boolean modsTabAll(Context c) { return of(c).getBoolean("mods_tab", true); }
    public static void modsTabAll(Context c, boolean on) { of(c).edit().putBoolean("mods_tab", on).apply(); }

    /** Settings › Controls › Controller › Quick menu button (dev.peanuts.ui.QuickButton): the key code of the player's own button for the in-game quick menu,
     *  0 none; the launcher writes it, the game's process reads it at its start */
    public static int quickKey(Context c) { return of(c).getInt("quick_key", 0); }
    public static void quickKey(Context c, int keyCode) { of(c).edit().putInt("quick_key", keyCode).apply(); }

    /**
     * The in-game menus' layout: two menus (the game menu at the left, the quick menu at the right), one menu at the right, or one menu on the bottom screen.
     * Picked once in the launcher's question (dev.peanuts.ui.LayoutChoice, kept in "peanuts", which the launcher writes) and changed later in the menu's own
     * settings (Layout, dev.nxnative.android.InGamePanel, kept in the game's prefs file, which its process writes): each with when, the newer one counts.
     * Two menus until one is picked
     */
    public static final int LAYOUT_TWO = 0, LAYOUT_RIGHT = 1, LAYOUT_BOTTOM = 2;
    private static SharedPreferences panel(Context c) { return c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE); }
    public static int menuLayout(Context c) {
        SharedPreferences game = panel(c), launcher = of(c);
        boolean inGame = game.contains("menu_layout") && game.getLong("menu_layout_at", 0) >= launcher.getLong("menu_layout_pick_at", 0);
        int v = inGame ? game.getInt("menu_layout", LAYOUT_TWO) : launcher.getInt("menu_layout_pick", LAYOUT_TWO);
        return v == LAYOUT_RIGHT || v == LAYOUT_BOTTOM ? v : LAYOUT_TWO;
    }
    /** The menu's own settings (the game's process) */
    public static void menuLayout(Context c, int layout) { panel(c).edit().putInt("menu_layout", layout).putLong("menu_layout_at", System.currentTimeMillis()).apply(); }
    /** The launcher's question */
    public static void menuLayoutPick(Context c, int layout) {
        of(c).edit().putInt("menu_layout_pick", layout).putLong("menu_layout_pick_at", System.currentTimeMillis()).apply();
    }
    /** A layout was picked, in the launcher's question or in the menu's own settings: the question is not asked again */
    public static boolean menuLayoutPicked(Context c) { return of(c).contains("menu_layout_pick") || panel(c).contains("menu_layout"); }
    /** How often the launcher's question showed (LayoutChoice.TIMES at most: closed without a pick, once more at the next start) */
    public static int menuLayoutAsked(Context c) { return of(c).getInt("menu_layout_asked", 0); }
    public static void menuLayoutAsked(Context c, int times) { of(c).edit().putInt("menu_layout_asked", times).apply(); }

    /** The in-game panel's tab shown last (its name), so it opens there again; "" none yet. In a file of its own: the game's process writes it, and the launcher's
     *  process, which keeps "peanuts" in memory, would write its older copy back over it */
    public static String panelTab(Context c) { return c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE).getString("tab", ""); }
    public static void panelTab(Context c, String name) {
        SharedPreferences p = c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE);
        if (!name.equals(p.getString("tab", ""))) p.edit().putString("tab", name).apply();
    }

    // (Removed: a dead, unread duplicate of the "second screen physical/off" pref - dev.peanuts.settings.CompanionSection.secondScreen() is the one
    // Companion.java actually reads, in its own SharedPreferences file ("peanuts", MODE_MULTI_PROCESS).)

    private static void put(Context c, String k, String v) {
        SharedPreferences.Editor e = of(c).edit();
        if (v == null || v.isEmpty()) e.remove(k); else e.putString(k, v);
        e.apply();
    }
}
