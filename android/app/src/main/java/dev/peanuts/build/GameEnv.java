package dev.peanuts.build;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.peanuts.PeanutsPrefs;

/**
 * The game's own env lines (upstream's Game settings, Prefs.gameEnv) that the library fills in for the player: the console's shared fonts. A game that decodes the
 * shared font at boot (SDK 20's ScalableFontEngineHelper: The Legend of Zelda: Echoes of Wisdom) crashes without DM_SHARED_FONT, and button glyphs need the extended
 * font (DM_SHARED_FONT_EXT). The files come from Setup's firmware step (PeanutsPrefs.fontsDir: standard.ttf, nintendo_ext.ttf); a line the player set stays.
 */
public final class GameEnv {
    private static final String TAG = Prepare.TAG;

    private GameEnv() {}

    /**
     * Peanuts' own per-title defaults ({title ID, key, value}), each a line the player can override in the game's settings. None now: Link's Awakening's handheld
     * mode, the one there was, is the default of its Console mode row (see RETIRED)
     */
    private static final String[][] DEFAULTS = {};
    /**
     * Lines an earlier build put into a game's Extra environment as a default, taken out again once: Link's Awakening's DM_OPMODE=0 is now the default of the game's
     * Console mode row (dev.nxnative.android.ScreenMode: handheld for this game), and the line, which wins over every row, would keep the row from doing anything.
     * A player who wants the line can put it back; it is only removed the first time
     */
    private static final String[][] RETIRED = {{"01006BB00C6F0000", "DM_OPMODE", "0"}};

    public static void ensureDefaults(Context c, String titleId) {
        if (titleId == null) return;
        for (String[] d : RETIRED) {
            if (!d[0].equalsIgnoreCase(titleId)) continue;
            android.content.SharedPreferences p = c.getSharedPreferences("peanuts", Context.MODE_PRIVATE);
            String flag = "env_retired_" + d[1] + "@" + d[0];
            if (p.getBoolean(flag, false)) continue;
            try {
                String env = Upstream.gameEnv(c, titleId);
                String out = without(env, d[1], d[2]);
                if (!out.equals(env == null ? "" : env)) { Upstream.gameEnv(c, titleId, out); Log.i(TAG, "env " + titleId + ": " + d[1] + "=" + d[2] + " taken out (now a setting)"); }
                p.edit().putBoolean(flag, true).apply();
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "env " + titleId + ": " + e);
            }
        }
        for (String[] d : DEFAULTS) {
            if (!d[0].equalsIgnoreCase(titleId)) continue;
            try {
                String env = Upstream.gameEnv(c, titleId);
                String out = withDefault(env, d[1], d[2]);
                if (!out.equals(env == null ? "" : env)) { Upstream.gameEnv(c, titleId, out); Log.i(TAG, "env " + titleId + ": " + d[1] + "=" + d[2] + " (Peanuts default)"); }
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "env " + titleId + ": " + e);
            }
        }
        try {   // the player's own Miis (Settings › Miis): games list them as the console's; independent of the fonts
            String env = Upstream.gameEnv(c, titleId);
            String out = withDefault(env, "DM_MII_DB", dev.peanuts.mii.MiiStore.database(c.getFilesDir()).getPath());
            if (!out.equals(env == null ? "" : env)) { Upstream.gameEnv(c, titleId, out); Log.i(TAG, "env " + titleId + ": DM_MII_DB set"); }
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "env " + titleId + ": " + e);
        }
    }

    public static void ensureFonts(Context c, String titleId) {
        ensureDefaults(c, titleId);
        if (titleId == null || titleId.isEmpty()) return;
        File dir = PeanutsPrefs.fontsDir(c);
        File std = new File(dir, "standard.ttf"), ext = new File(dir, "nintendo_ext.ttf");
        if (!std.isFile()) return;
        try {
            String env = Upstream.gameEnv(c, titleId);
            String out = withDefault(env, "DM_SHARED_FONT", std.getPath());
            if (ext.isFile()) out = withDefault(out, "DM_SHARED_FONT_EXT", ext.getPath());
            File mii = PeanutsPrefs.miiModelDir(c);   // the console's MiiModel (Mario Kart 8 Deluxe's Mii heads): the host mounts it instead of its empty stand-in
            if (new File(mii, "ShapeHigh.dat").isFile()) out = withDefault(out, "DM_MII_MODEL", mii.getPath());
            if (!out.equals(env == null ? "" : env)) { Upstream.gameEnv(c, titleId, out); Log.i(TAG, "env " + titleId + ": shared fonts set"); }
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "env " + titleId + ": " + e);
        }
    }

    /**
     * PN_BUILD_ID=<main's whole NSO build id, 64 hex digits> for the companion runtime: the host knows a build by its first 8 bytes, a package's native module may list the
     * whole id (Echoes of Wisdom's) and refuses a prefix. From upstream's DumpLayout.find (the launcher's own version check), for the dump as it is now; set anew each time
     */
    public static void ensureBuildId(Context c, String titleId, File dump) {
        if (titleId == null || dump == null) return;
        try {
            Object found = Upstream.call(Upstream.k("DumpLayout"), null, "find", new Class<?>[] {File.class, boolean.class}, dump, true);
            Object ids = Upstream.get(found, "buildIds");
            Object main = ids instanceof java.util.Map ? ((java.util.Map<?, ?>) ids).get("main") : null;
            if (main == null || main.toString().length() < 16) {   // no PN_BUILD_ID: a companion module that lists whole build ids then refuses the game, so say why
                Log.w(TAG, "env " + titleId + " build id: none from " + dump + ": " + Upstream.get(found, "error"));
                return;
            }
            String env = Upstream.gameEnv(c, titleId);
            String out = RuntimeShaders.withLine(env, "PN_BUILD_ID=" + main.toString().toUpperCase(java.util.Locale.ROOT));
            if (!out.equals(env == null ? "" : env)) Upstream.gameEnv(c, titleId, out);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "env " + titleId + " build id: " + e);
        }
    }

    /** text without the line key=value (that exact value: another value is the player's own); text as it is when it has no such line */
    static String without(String text, String key, String value) {
        String t = text == null ? "" : text;
        List<String> out = new ArrayList<>();
        boolean found = false;
        for (String l : t.split("\n")) {
            if (l.trim().equals(key + "=" + value)) { found = true; continue; }
            if (!l.trim().isEmpty()) out.add(l);
        }
        return found ? String.join("\n", out) : t;
    }

    /** text with KEY=value added when no line sets KEY */
    static String withDefault(String text, String key, String value) {
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n")) {
            if (l.trim().isEmpty()) continue;
            if (l.trim().startsWith(key + "=")) return text;
            out.add(l);
        }
        out.add(key + "=" + value);
        return String.join("\n", out);
    }
}
