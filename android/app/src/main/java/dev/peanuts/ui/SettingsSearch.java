package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One search over every setting (SettingsPage's search sheet), with Advanced's rule (AdvancedTable.visible): a row matches when every word of the query is
 * in its text, here its title, its section and group, its value and what it does (SettingsHelp). Rows whose title starts with the query come first, then
 * rows whose title has every word, then the rest, each in the order Settings shows them. Plain Java, no Android: peanuts/tests/test_settings_search.py runs
 * SettingsSearchJvmTest on it.
 */
public final class SettingsSearch {
    private SettingsSearch() {}

    /** The query's words, lower case; none for a blank query */
    public static String[] words(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    /** Whether every word is in the text (Advanced's rule) */
    public static boolean matches(String text, String[] words) {
        String hay = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    /** The text a row is found by: its title, section, group, value and what it does */
    public static String text(String title, String section, String group, CharSequence value) {
        SettingsHelp.Help h = SettingsHelp.of(title);
        return title + " " + section + " " + (group == null ? "" : group) + " " + (value == null ? "" : value) + " " + (h == null ? "" : h.text + " " + (h.deflt == null ? "" : h.deflt));
    }

    /**
     * The rows that match, best first, as indexes into titles (and texts, the same order: each row's whole text, text()); none for a blank query
     */
    public static List<Integer> find(List<String> titles, List<String> texts, String query) {
        String[] w = words(query);
        List<Integer> start = new ArrayList<>(), title = new ArrayList<>(), rest = new ArrayList<>();
        if (w.length == 0) return rest;
        String q = String.join(" ", w);
        for (int i = 0; i < titles.size(); i++) {
            if (!matches(texts.get(i), w)) continue;
            String t = titles.get(i).toLowerCase(Locale.ROOT);
            if (t.startsWith(q)) start.add(i);
            else if (matches(t, w)) title.add(i);
            else rest.add(i);
        }
        start.addAll(title);
        start.addAll(rest);
        return start;
    }
}
