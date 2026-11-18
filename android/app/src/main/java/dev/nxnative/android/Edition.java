package dev.nxnative.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A game's edition: a dump folder that also holds program 1 (a Switch 2 Edition: Super Mario Bros. Wonder's 040015100B514001 beside 010015100B514000, at
 * updates/&lt;v&gt;/program/1/ in the console's layout, DumpLayout.programDir) plays either program from one card. The card keeps the application's title ID; the
 * switch (per application, the game page) picks program 1, which has its own profile, host (libnxhost_&lt;its ID&gt;.so) and pack (pack_android.py --program 1),
 * while the save stays the application's, as on the console (GameActivity save_id). Program 1 is offered only when this APK has its profile. A .nup holds
 * program 0 only, so its card has no switch. A dump whose game this APK does not run but whose program 1 it does gets a card of program 1 itself
 * (Pack.playableIdOf), which has no switch either: there is nothing to switch to.
 */
final class Edition {
    private Edition() {}

    private static final Map<String, String> cache = new HashMap<>();   // program 1's main.npdm path and identity → its title ID ("" when unreadable)

    /** Program 1's title ID in a dump folder (upper case, from its main.npdm), null when the dump has none */
    static String program1(File game) {
        File d = DumpLayout.programDir(game, 1);
        if (d == null) return null;
        File npdm = new File(d, "exefs/main.npdm");
        String key = npdm.getAbsolutePath() + ":" + npdm.length() + ":" + npdm.lastModified();
        synchronized (cache) { if (cache.containsKey(key)) { String v = cache.get(key); return v.isEmpty() ? null : v; } }
        String id = "";
        try { id = Pack.programId(npdm); } catch (IOException e) { /* unreadable: no edition offered (Play reports the dump's problem) */ }
        synchronized (cache) { cache.put(key, id); }
        return id.isEmpty() ? null : id;
    }

    /** Whether the card can switch: it is the game's own card (program 0), its dump has program 1 and this APK has program 1's profile */
    static boolean offered(Game g) {
        String p1 = program1(new File(g.gameDir));
        return p1 != null && Profile.of(p1) != null && !p1.equals(g.titleId);
    }

    /** Whether the card is program 1 itself (Pack.playableIdOf: the APK runs the Edition and not the game) */
    static boolean isProgram1(Game g) { return g.titleId.equals(program1(new File(g.gameDir))); }

    /**
     * Program 1's control folder when the card's switch is on and the dump has one (program 1's icon, version and publisher on the Home tile and the game page,
     * GameMeta.loadControl), else null: the card shows the game's own
     */
    static File control(Context c, Game g) {
        if (!isProgram1(g) && (!on(c, g.titleId) || !offered(g))) return null;
        File d = DumpLayout.programDir(new File(g.gameDir), 1), ctl = d == null ? null : new File(d, "control");
        return ctl != null && new File(ctl, "control.nacp").isFile() ? ctl : null;
    }

    /** The switch's state for an application: true = program 1 */
    static boolean on(Context c, String appId) { return Prefs.of(c).getBoolean(Prefs.gameKey("edition_program1", appId), false); }

    static void set(Context c, String appId, boolean program1) { Prefs.of(c).edit().putBoolean(Prefs.gameKey("edition_program1", appId), program1).apply(); }

    /**
     * The game Play starts for a card: the card itself, or with the switch on, program 1 of its dump as a game of its own (its title ID and name, the same dump
     * folder, its pack: the app's packs/&lt;program 1 ID&gt;, else &lt;program 1 ID&gt;-work/android-pack beside the dump folder, as pack_android.py writes it), null when
     * the switch is on but program 1 has no pack yet
     */
    static Game forPlay(Context c, Game g) {
        if (!on(c, g.titleId) || !offered(g)) return g;
        String p1 = program1(new File(g.gameDir));
        File game = new File(g.gameDir).getAbsoluteFile();
        File[] places = {AppStorage.pack(c, p1), new File(game.getParentFile(), p1 + "-work/android-pack")};
        for (File d : places)
            if (new File(d, "pack.tsv").isFile()) return new Game(p1, name(g), g.gameDir, d.getPath());
        return null;
    }

    /** Program 1's name on the card: the game's with the edition */
    static String name(Game g) { return g.name + " - Nintendo Switch 2 Edition"; }
}
