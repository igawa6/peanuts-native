package dev.nxnative.android;

import android.widget.LinearLayout;

import java.io.File;

/**
 * Peanuts-owned, in upstream's package (as CheatsBridge.java): a game's page in areas (dev.peanuts.ui.GamePage). Upstream's GameSettings and
 * MainActivity.gameTab still build every row, its value, its dialog and its switch; this builds the page once off the page (one column with every
 * section, Advanced too) and the Saves and Storage tabs when their area shows, and hands their views to GamePage, which lays them out in areas. The
 * hooks: GameSettings.build's first line ({@link #build}), its wide line and its sections line ({@link #harvesting}), stepTab ({@link #step}) and
 * openOn ({@link #at}).
 */
public final class GamePagePort {
    private GamePagePort() {}

    private static boolean harvesting;   // upstream's page is being built off the page for its rows: the hooks stand aside

    /** GameSettings.build's wide line (PEANUTS hook): while its rows are taken, upstream builds its narrow column, where every section is in the page */
    static boolean harvesting() { return harvesting; }

    /** GameSettings.build's first line (PEANUTS hook): the page in areas into content, true; false where GamePage is not used (upstream's page builds) */
    static boolean build(MainActivity a, LinearLayout content, Game g, Runnable again, Runnable close) {
        if (harvesting || !dev.peanuts.ui.GamePage.used(a)) return false;
        dev.peanuts.ui.GamePage.Facts f = new dev.peanuts.ui.GamePage.Facts();
        f.titleId = g.titleId;
        f.name = g.name;
        f.gameDir = g.gameDir;
        f.packDir = g.packDir;
        f.missing = Profile.missing(g.titleId, g.name);
        f.again = again;
        f.close = close;
        f.top = a::gameSettingsTop;
        if (f.missing == null) f.play = () -> a.launch(g);
        f.edition = Edition.offered(g) && Edition.on(a, g.titleId);
        f.built = g.packDir != null && new File(g.packDir, "pack.tsv").isFile();
        if (f.missing == null) {
            java.util.List<Recommend.State> st = Prefs.recStates(a, g.titleId);
            if (Recommend.line(st, (s, v) -> Prefs.recCardName(a, g.titleId, s, v)) != null) f.recommended = Recommend.changedCount(st) == 0;
        }
        f.savesLine = UserSaves.several(a, g) ? UserSaves.saveOf(a, g, "save").replace(" of " + g.name, "") : null;
        f.meta = done -> GameMeta.loadControl(new File(g.gameDir), Edition.control(a, g), m -> done.meta(m.icon, m.version, m.publisher));
        String area = dev.peanuts.ui.GamePage.area(g.titleId);
        String was = tab();
        harvesting = true;
        try {
            if (f.missing == null) f.performance = off(a, g, GameSettings.PERFORMANCE, again, close);   // one build has every section's rows (the sections line's hook)
            if (dev.peanuts.ui.GamePage.SAVES_AREA.equals(area)) { f.saves = column(a); a.gameTab(f.saves, g, true); }
            if (dev.peanuts.ui.GamePage.storageWanted(area)) { f.storage = column(a); a.gameTab(f.storage, g, false); }
        } finally {
            harvesting = false;
            tab(was);
        }
        dev.peanuts.ui.GamePage.lay(a, content, f);
        return true;
    }

    /** One of upstream's settings tabs, built off the page */
    private static LinearLayout off(MainActivity a, Game g, String t, Runnable again, Runnable close) {
        tab(t);
        LinearLayout c = column(a);
        GameSettings.build(a, c, g, again, close);
        return c;
    }

    private static LinearLayout column(MainActivity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    /**
     * GameSettings.stepTab (PEANUTS hook, after Mods & Cheats' own): L1 / R1 on the page in areas: true when another area shows (the caller builds the
     * page again), false on the tiles (nothing to step); null where GamePage is not used (upstream's tabs step)
     */
    static Boolean step(Game g, int delta) { return dev.peanuts.ui.GamePage.step(g.titleId, delta); }

    /** GameSettings.openOn (Peanuts' line, #422): the page opens at the area of upstream's tab (Saves: a user's save in Settings › Users) */
    static void at(String upstreamTab) {
        dev.peanuts.ui.GamePage.openAt(GameSettings.SAVES.equals(upstreamTab) ? dev.peanuts.ui.GamePage.SAVES_AREA : GameSettings.STORAGE.equals(upstreamTab) ? "files" : null);
    }

    // GameSettings.tab is private: its tab is read and set here, as its own L1 / R1 would
    private static String tab() {
        try {
            java.lang.reflect.Field f = GameSettings.class.getDeclaredField("tab");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            return GameSettings.PERFORMANCE;
        }
    }

    private static void tab(String t) {
        try {
            java.lang.reflect.Field f = GameSettings.class.getDeclaredField("tab");
            f.setAccessible(true);
            f.set(null, t);
        } catch (ReflectiveOperationException ignored) { /* the page builds what it has */ }
    }
}
