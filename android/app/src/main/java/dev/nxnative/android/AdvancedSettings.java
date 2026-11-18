package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the player set on the Advanced pages (AdvancedTable's rows), kept as their own preferences, "adv:<NAME>" for every game and "adv:<NAME>@<title ID>" for one (Prefs.gameKey);
 * no entry is Auto: nothing is written and the app, the game's profile or the host decides. They reach the host as KEY=value lines before env.txt's, so a line of env.txt and the
 * game's Extra environment win (GameActivity); each one in effect is a [adv] line of the run's log.
 */
final class AdvancedSettings {
    private AdvancedSettings() {}

    private static final String PREFIX = "adv:";
    private static List<AdvancedTable.Row> cache;

    /** The table from the APK's assets (empty when the asset is missing: an APK built without it shows an empty page) */
    static synchronized List<AdvancedTable.Row> table(Context c) {
        if (cache == null) {
            try (InputStream in = c.getAssets().open(AdvancedTable.ASSET)) {
                cache = AdvancedTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException e) { cache = new ArrayList<>(); }
        }
        return cache;
    }

    private static Map<String, String> meanings;

    /** What a switch does, from docs/env_switches.tsv (assets/advanced/), for the developer rows; null when unknown */
    static synchronized String meaning(Context c, String name) {
        if (meanings == null) {
            try (InputStream in = c.getAssets().open(AdvancedTable.MEANINGS_ASSET)) { meanings = AdvancedTable.meanings(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (IOException e) { meanings = new java.util.HashMap<>(); }
        }
        return meanings.get(name);
    }

    private static String key(String name, String titleId) { return titleId == null ? PREFIX + name : Prefs.gameKey(PREFIX + name, titleId); }

    /** The value set for this game (titleId) or for every game (null); null: Auto */
    static String own(Context c, String name, String titleId) { return Prefs.of(c).getString(key(name, titleId), null); }

    /** The value in effect for a game: its own, else the global one; null: Auto */
    static String effective(Context c, String name, String titleId) {
        String v = titleId == null ? null : own(c, name, titleId);
        return v != null ? v : own(c, name, null);
    }

    static void set(Context c, String name, String titleId, String value) {
        SharedPreferences.Editor e = Prefs.of(c).edit();
        if (value == null) e.remove(key(name, titleId)); else e.putString(key(name, titleId), value);
        e.commit();
    }

    /** Auto again for every row: one game's (titleId) or all of them, the global ones and every game's (null) */
    static void resetAll(Context c, String titleId) {
        SharedPreferences p = Prefs.of(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : new ArrayList<>(p.getAll().keySet())) {
            if (!k.startsWith(PREFIX)) continue;
            if (titleId == null || k.endsWith("@" + titleId)) e.remove(k);
        }
        e.commit();
    }

    /** How many rows are set: for a game its own and the global ones, or for the global page the global ones */
    static int count(Context c, String titleId) { return active(c, titleId, null).size(); }

    /** name -> {value, "game" | "global"} for every row set that the table still lists, can set and accepts; the others are ignored (a row an update removed, a value it no longer takes) */
    static Map<String, String[]> active(Context c, String titleId, Set<String> protectedKeys) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (AdvancedTable.Row r : table(c)) {
            if (!r.editable() || !r.forGame(titleId) || protectedKeys != null && protectedKeys.contains(r.name)) continue;
            String g = titleId == null ? null : own(c, r.name, titleId), v = g != null ? g : own(c, r.name, null);
            if (v != null && r.accepts(v)) out.put(r.name, new String[] {v, g != null ? "game" : "global"});
        }
        return out;
    }

    /** The KEY=value lines for the host's environment (they come before env.txt's lines) */
    static List<String> lines(Context c, String titleId, Set<String> protectedKeys) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : active(c, titleId, protectedKeys).entrySet()) out.add(e.getKey() + "=" + e.getValue()[0]);
        return out;
    }

    /** The log lines: each override in effect with where it came from; fromFiles are the player's own lines of env.txt / the game's Extra environment, in the order GameActivity applies them */
    static List<String> notes(Context c, String titleId, Set<String> protectedKeys, List<String[]> fromFiles) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : active(c, titleId, protectedKeys).entrySet())
            out.add("[adv] " + e.getKey() + "=" + AdvancedTable.redact(e.getKey(), e.getValue()[0]) + " (Advanced, " + e.getValue()[1] + ")");
        for (String[] f : fromFiles) {   // {line, source}
            int i = f[0].indexOf('=');
            if (i > 0 && f[0].startsWith("DM")) out.add("[adv] " + f[0].substring(0, i).trim() + "=" + AdvancedTable.redact(f[0].substring(0, i).trim(), f[0].substring(i + 1).trim()) + " (" + f[1] + ")");
        }
        if (out.isEmpty()) out.add("[adv] none: no Advanced row is set and no DM_ line of env.txt or Extra environment");
        return out;
    }
}
