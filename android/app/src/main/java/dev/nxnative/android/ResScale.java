package dev.nxnative.android;
// PEANUTS: new file. Resolution scale: the size the game's picture is drawn at, as a factor of the size the game itself asks for, for every game (the host's
// DM_RES_SCALE, dm/gfx/vk_android/vka_resscale.inc: the game's render targets are made larger or smaller without the game knowing). A default in Settings › Game
// defaults and a row in each game's Picture settings, from the next start. Not offered for a game with a render_cfg profile row: it has its own Resolution and
// Upscaling quality, and its upscaler works in the sizes the game made its targets at. It lives in this package because Ui.Setting, Prefs.of and Profile are
// package-private; the hooks are one line each (Prefs.GAME_SETTINGS, GameActivity.settingsEnv, GameSettings' Picture, MainActivity's Settings, the card Performance and picture), as ScreenMode's.

import android.content.Context;

import java.util.List;

final class ResScale {
    private ResScale() {}

    static final String KEY = "res_scale";   // int: the factor in quarters (2 = 0.5x … 8 = 2x); absent: the default (the game's own key: Prefs.gameKey)
    static final int ONE = 4;
    private static final int[] QUARTERS = {2, 3, 4, 5, 6, 8};
    private static final String[] NAMES = {"0.5x", "0.75x", "1x (the game's own)", "1.25x", "1.5x", "2x"};
    private static final String[] SHORT = {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"};
    private static final String[] VALUE = {"0.5", "0.75", "1", "1.25", "1.5", "2"};   // DM_RES_SCALE's values
    static final String NOTE = "The size the game's picture is drawn at. Above 1x is sharper and heavier, below 1x is lighter. From the next start.";

    private static int index(int quarters) {
        for (int i = 0; i < QUARTERS.length; i++) if (QUARTERS[i] == quarters) return i;
        return 2;   // anything else stored: 1x
    }

    /** A game without the row: its profile has a render_cfg row (its own Resolution and Upscaling quality) */
    static boolean hidden(String id) { Profile p = Profile.of(id); return p != null && p.renderCfg(); }

    /** The default for every game, in quarters (1x when nothing is chosen) */
    static int global(Context c) { return QUARTERS[index(Prefs.of(c).getInt(KEY, ONE))]; }

    static void global(Context c, int quarters) {
        if (quarters == ONE) Prefs.of(c).edit().remove(KEY).commit();
        else Prefs.of(c).edit().putInt(KEY, quarters).commit();
    }

    static boolean own(Context c, String id) { return Prefs.of(c).contains(Prefs.gameKey(KEY, id)); }

    /** The factor a game starts with, in quarters */
    static int quarters(Context c, String id) { return QUARTERS[index(Prefs.of(c).getInt(Prefs.gameKey(KEY, id), global(c)))]; }

    static void set(Context c, String id, Integer quarters) {
        if (quarters == null) Prefs.of(c).edit().remove(Prefs.gameKey(KEY, id)).commit();
        else Prefs.of(c).edit().putInt(Prefs.gameKey(KEY, id), quarters).commit();
    }

    /** The host's line: none at 1x, nor for a game without the row */
    static void env(Context c, String id, List<String> env) {
        if (hidden(id)) return;
        int q = quarters(c, id);
        if (q != ONE) env.add("DM_RES_SCALE=" + VALUE[index(q)]);
    }

    /** The game's row in Picture, or null for a game without one */
    static Ui.Setting row(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        if (hidden(id)) return null;
        Ui.Setting s = new Ui.Setting(a, "🔍", "Resolution scale", v -> {
            String[] labels = new String[NAMES.length + 1];
            labels[0] = "Default (Settings: " + SHORT[index(global(a))] + ")";
            System.arraycopy(NAMES, 0, labels, 1, NAMES.length);
            new PadFocus.Builder(a).setTitle("Resolution scale · " + g.name).setSingleChoiceItems(labels, own(a, id) ? index(quarters(a, id)) + 1 : 0, (x, w) -> {
                set(a, id, w == 0 ? null : QUARTERS[w - 1]);
                x.dismiss();
                done.run();
            }).setNegativeButton("Cancel", null).show();
        }).value(SHORT[index(quarters(a, id))] + (own(a, id) ? "" : " (default)")).chevron(a);
        return s.note(NOTE);
    }

    /** Settings › Performance and picture */
    static Ui.Setting defaultRow(MainActivity a) {
        Ui.Setting[] s = new Ui.Setting[1];
        s[0] = new Ui.Setting(a, "🔍", "Resolution scale", v -> new PadFocus.Builder(a).setTitle("Resolution scale")
                .setSingleChoiceItems(NAMES, index(global(a)), (d, w) -> {
                    global(a, QUARTERS[w]);
                    s[0].value(NAMES[index(global(a))]);
                    d.dismiss();
                }).setNegativeButton("Cancel", null).show()).chevron(a);
        return s[0].value(NAMES[index(global(a))]).note(NOTE);
    }
}
