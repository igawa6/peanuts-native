package dev.nxnative.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * docs/advanced_settings.tsv as the launcher reads it (the APK's assets/advanced/; build.gradle copies it; dm/tools/advanced_switches.py writes and checks it, the columns are
 * described there; pure Java: dm/tools/tests/test_android_advanced.py runs it on the JVM). One row for each switch of docs/env_switches.tsv: the tier decides whether and where the
 * Advanced pages show it, the type and choices say what a player may set, and "Auto" (nothing written) is always the first choice.
 */
final class AdvancedTable {
    private AdvancedTable() {}

    static final String ASSET = "advanced/advanced_settings.tsv", MEANINGS_ASSET = "advanced/env_switches.tsv";
    static final int SEARCH_MAX = 50;
    static final String[] AREAS = {"Graphics", "Performance", "Memory", "Input", "Audio", "Packing", "Game", "Diagnostics"};

    static final class Row {
        String name = "", tier = "", area = "", label = "", tryIf = "", type = "", choices = "", deflt = "", scope = "", warn = "", owner = "", profileKey = "", games = "", fixes = "", reason = "";

        boolean shown() { return !tier.equals("internal"); }
        boolean developer() { return tier.equals("developer"); }
        boolean experimental() { return !warn.isEmpty(); }
        boolean owned() { return !owner.isEmpty(); }
        /** Whether the page lets the player set it: a launcher control owns the others */
        boolean editable() { return shown() && !owned(); }
        String title() { return label.isEmpty() ? name : label; }

        /** toggle and choice: {label, value} pairs, Auto not included */
        List<String[]> options() {
            List<String[]> out = new ArrayList<>();
            if (!type.equals("toggle") && !type.equals("choice")) return out;
            for (String c : choices.split(";")) {
                int i = c.indexOf('=');
                if (i > 0) out.add(new String[] {c.substring(0, i).trim(), c.substring(i + 1).trim()});
            }
            return out;
        }

        /** number: the range text "min..max [unit]" split; null when the row is not a number */
        double[] range() {
            if (!type.equals("number")) return null;
            String r = choices.split("[ |]")[0];
            int i = r.indexOf("..");
            try { return new double[] {Double.parseDouble(r.substring(0, i)), Double.parseDouble(r.substring(i + 2))}; } catch (RuntimeException e) { return null; }
        }

        String unit() {
            if (!type.equals("number")) return "";
            String c = choices.split("\\|")[0].trim();
            int i = c.indexOf(' ');
            return i > 0 ? c.substring(i + 1).trim() : "";
        }

        /** number: the preset values (chips), empty when the row has none */
        List<String> presets() {
            List<String> out = new ArrayList<>();
            int i = choices.indexOf('|');
            if (type.equals("number") && i >= 0) for (String v : choices.substring(i + 1).split(",")) if (!v.trim().isEmpty()) out.add(v.trim());
            return out;
        }

        /** Whether this value may be written as the switch's value (a choice's own values, a number in range, any single-line text for a developer row) */
        boolean accepts(String v) {
            if (v == null || v.isEmpty() || v.indexOf('\n') >= 0 || v.indexOf('=') >= 0 && !type.equals("text")) return false;
            switch (type) {
                case "toggle": case "choice":
                    for (String[] o : options()) if (o[1].equals(v)) return true;
                    return false;
                case "number": {
                    double[] r = range();
                    try { double d = Double.parseDouble(v); return r != null && d >= r[0] && d <= r[1]; } catch (NumberFormatException e) { return false; }
                }
                default: return developer();
            }
        }

        /** The title of the option with this value (the value itself when it is none of them) */
        String optionLabel(String v) {
            for (String[] o : options()) if (o[1].equals(v)) return o[0];
            return type.equals("number") && !unit().isEmpty() ? v + " " + unit() : v;
        }

        /** Shown for this game: a row with no games names applies to all, and a game-only row is not in the global list */
        boolean forGame(String titleId) {
            if (titleId == null) return !scope.equals("game") && games.isEmpty();
            if (scope.equals("global")) return false;
            if (games.isEmpty()) return true;
            for (String g : games.split(",")) if (g.trim().equalsIgnoreCase(titleId)) return true;
            return false;
        }
    }

    /** The rows of the table's text ('#' lines and empty lines skipped, the first other line the header: columns are found by name). Throws IllegalArgumentException for a header without name and tier */
    static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        String[] head = null;
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (head == null) {
                head = f;
                if (indexOf(head, "name") < 0 || indexOf(head, "tier") < 0) throw new IllegalArgumentException("the header has no name and tier columns");
                continue;
            }
            Row r = new Row();
            r.name = col(head, f, "name"); r.tier = col(head, f, "tier"); r.area = col(head, f, "area"); r.label = col(head, f, "label"); r.tryIf = col(head, f, "try_if");
            r.type = col(head, f, "type"); r.choices = col(head, f, "choices"); r.deflt = col(head, f, "default"); r.scope = col(head, f, "scope"); r.warn = col(head, f, "warn");
            r.owner = col(head, f, "owner"); r.profileKey = col(head, f, "profile_key"); r.games = col(head, f, "games"); r.fixes = col(head, f, "fixes"); r.reason = col(head, f, "reason");
            if (r.name.startsWith("DM_") || r.name.startsWith("DMSH_VK_")) rows.add(r);
        }
        return rows;
    }

    private static int indexOf(String[] a, String s) { for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i; return -1; }
    private static String col(String[] head, String[] f, String name) { int i = indexOf(head, name); return i >= 0 && i < f.length ? f[i] : ""; }

    /** name -> meaning from docs/env_switches.tsv's text (the developer rows show it) */
    static java.util.Map<String, String> meanings(String text) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 3);
            if (f.length == 3 && f[0].startsWith("DM")) out.put(f[0], f[2]);
        }
        return out;
    }

    /** The row for a switch, or null */
    static Row find(List<Row> rows, String name) { for (Row r : rows) if (r.name.equals(name)) return r; return null; }

    /**
     * The rows a page shows: for a game (titleId) or the global list (null), the developer rows and the rows a launcher control owns only with dev on (a query that is exactly a DM_ name finds it either way), and with a
     * query only the rows whose name, label, "try this if" line or fix words contain every word of it, at most SEARCH_MAX
     */
    static List<Row> visible(List<Row> rows, String titleId, boolean dev, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.shown() || !r.forGame(titleId)) continue;
            boolean exact = q.equals(r.name.toLowerCase(Locale.ROOT));
            if (r.developer() && !dev && !exact) continue;
            if (r.owned() && !dev) continue;   // a launcher control already sets it: listed only for developers
            String hay = (r.name + " " + r.label + " " + r.tryIf + " " + r.fixes.replace(';', ' ') + " " + r.area).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (!all) continue;
            out.add(r);
            if (words.length > 0 && out.size() >= SEARCH_MAX) break;
        }
        return out;
    }

    /** A value for a log or a report: the folder and file switches (names ending _DIR, _PATH, _FILE, _ROOT) keep their path to themselves */
    static String redact(String name, String value) {
        return name.matches(".*_(DIR|PATH|FILE|ROOT)$") || name.equals("HOME") || value.startsWith("/") ? "<path>" : value;
    }
}
