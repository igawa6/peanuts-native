package dev.nxnative.android;
// PEANUTS: new file. Console mode (docked / handheld) for the games whose Resolution does not already set the operation mode: every Switch 1 game.
// The host has both modes (hle_oe.c dm_opmode: DM_OPMODE, else the profile's op_mode), but the Android launcher had no row for it outside the
// graphics_scale games, so a game started as its profile says: docked, a 1080p picture, for nearly all of them. It lives in this package because
// Ui.Setting, Prefs.of and Profile are package-private; the hooks are one line each (Prefs.GAME_SETTINGS, GameActivity.settingsEnv, GameSettings' Picture,
// MainActivity's Settings, the card Performance and picture: "Game defaults" before Banana Native v0.3.22).

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScreenMode {
    private ScreenMode() {}

    static final String KEY = "console_mode";   // int: 0 docked, 1 handheld; absent: the default (the game's own key: Prefs.gameKey)
    static final String[] NAMES = {"Docked: the TV picture, up to 1080p", "Handheld: up to 720p, lighter on the GPU"};
    static final String[] SHORT = {"Docked", "Handheld"};
    /** Title IDs whose profile says op_mode handheld (build.gradle handheldGames) */
    private static final Set<String> HANDHELD = new HashSet<>(Arrays.asList(BuildConfig.NX_PEANUTS_HANDHELD.split(" ")));
    /** Handheld in the profile for speed only, not because the game needs it: the player may choose (Fire Emblem: Three Houses, 1080p took 17.4 ms of GPU on the Thor) */
    private static final Set<String> FREE = Set.of("010055D009F78000");
    /** What a mode costs in a game, said under its row */
    private static final Map<String, String> NOTES = Map.of(
            "0100000000010000", "Handheld turns off this game's motion moves.",
            "010055D009F78000", "Docked is 1080p and runs below 60 fps on some devices.",
            "01006BB00C6F0000", "Docked is 1080p and dips below 60 fps on some devices.");

    /** Handheld by default here although the profile says docked: Link's Awakening's 1920x1080 frame took the Thor's GPU 15 ms at the 90th percentile and play dipped to 50 fps; handheld holds 60 */
    private static final Set<String> PREFER_HANDHELD = Set.of("01006BB00C6F0000");

    /** The mode the host starts the game in without a line (its profile's op_mode) */
    static int profile(String id) { return HANDHELD.contains(id) ? 1 : 0; }

    /** The mode a game has when nothing is chosen for it */
    static int own(String id) { return HANDHELD.contains(id) || PREFER_HANDHELD.contains(id) ? 1 : 0; }

    /** null: the player chooses; "": no row (the game's Resolution sets the mode); else the text of a row that cannot be changed */
    static String fixed(String id) {
        Profile p = Profile.of(id);
        if (p != null && (p.resolution.startsWith("scale:") || p.renderCfg())) return "";
        if (HANDHELD.contains(id) && !FREE.contains(id)) return "Handheld: this game needs it (touch or pointer)";
        return null;
    }

    /** The default for every game: 0 docked, 1 handheld, -1 each game's own */
    static int global(Context c) { int v = Prefs.of(c).getInt(KEY, -1); return v == 0 || v == 1 ? v : -1; }

    static void global(Context c, int v) {
        if (v < 0) Prefs.of(c).edit().remove(KEY).commit();
        else Prefs.of(c).edit().putInt(KEY, v).commit();
    }

    static boolean own(Context c, String id) { return Prefs.of(c).contains(Prefs.gameKey(KEY, id)); }

    static int mode(Context c, String id) {
        int g = global(c);
        return Prefs.of(c).getInt(Prefs.gameKey(KEY, id), g < 0 ? own(id) : g) == 1 ? 1 : 0;
    }

    static void set(Context c, String id, Integer v) {
        if (v == null) Prefs.of(c).edit().remove(Prefs.gameKey(KEY, id)).commit();
        else Prefs.of(c).edit().putInt(Prefs.gameKey(KEY, id), v).commit();
    }

    /** The host's line (DM_OPMODE: 1 console, 0 handheld): none for a fixed game, nor when the choice is the profile's own mode */
    static void env(Context c, String id, List<String> env) {
        if (fixed(id) != null) return;
        int m = mode(c, id);
        if (m != profile(id)) env.add("DM_OPMODE=" + (1 - m));
    }

    static String globalLabel(Context c) { int g = global(c); return g < 0 ? "Each game's own (most: Docked)" : SHORT[g]; }

    /** The game's row in Picture, or null for a game without one */
    static Ui.Setting row(MainActivity a, Game g, Runnable done) {
        String id = g.titleId, fx = fixed(id);
        if (fx != null && fx.isEmpty()) return null;
        if (fx != null) return new Ui.Setting(a, "📺", "Console mode", null).value(fx);
        Ui.Setting s = new Ui.Setting(a, "📺", "Console mode", v -> {
            String[] labels = {"Default (" + (global(a) < 0 ? "this game: " + SHORT[own(id)] : "Settings: " + SHORT[global(a)]) + ")", NAMES[0], NAMES[1]};
            new PadFocus.Builder(a).setTitle("Console mode · " + g.name).setSingleChoiceItems(labels, own(a, id) ? mode(a, id) + 1 : 0, (x, w) -> {
                set(a, id, w == 0 ? null : w - 1);
                x.dismiss();
                done.run();
            }).setNegativeButton("Cancel", null).show();
        }).value(SHORT[mode(a, id)] + (own(a, id) ? "" : " (default)")).chevron(a);
        String n = NOTES.get(id);
        return s.note((n != null ? n + " " : "The picture size the game draws at, as on a Switch. Handheld also turns on the game's touch screen. ") + "From the next start; while the game runs, its in-game panel changes it at once.");
    }

    /** Settings › Performance and picture */
    static Ui.Setting defaultRow(MainActivity a) {
        Ui.Setting[] s = new Ui.Setting[1];
        s[0] = new Ui.Setting(a, "📺", "Console mode", v -> new PadFocus.Builder(a).setTitle("Console mode")
                .setSingleChoiceItems(new String[] {"Each game's own", NAMES[0], NAMES[1]}, global(a) + 1, (d, w) -> {
                    global(a, w - 1);
                    s[0].value(globalLabel(a));
                    d.dismiss();
                }).setNegativeButton("Cancel", null).show()).chevron(a);
        return s[0].value(globalLabel(a));
    }
}
