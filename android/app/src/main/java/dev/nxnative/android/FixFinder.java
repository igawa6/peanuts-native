package dev.nxnative.android;

import java.util.ArrayList;
import java.util.List;

/**
 * The fix finder ("Try this setting"): dm/tools/advanced_fix.tsv as the launcher reads it (the APK's assets/advanced/; build.gradle copies it; dm/tools/advanced_fix.py checks
 * it and writes wiki/Troubleshooting.md's table from it). A run's end (the log digest's rule, or for an end no log line explains the verdict's kind as "exit:&lt;kind&gt;") picks
 * the first row of the table for it whose Advanced row the player can set for this game, takes its value, is still Auto, and was not offered for this game before. Pure Java:
 * dm/tools/tests/test_android_fixfinder.py runs it on the JVM; FixOffer puts it on the exit notice.
 */
final class FixFinder {
    private FixFinder() {}

    static final String ASSET = "advanced/advanced_fix.tsv";
    static final String EXIT = "exit:";

    static final class Fix {
        String rule = "", name = "", value = "", scope = "", sentence = "";
        boolean global() { return scope.equals("global"); }
    }

    /** What the launcher knows of the player's choices: a row's own value for one game (titleId) or for every game (null), null for Auto; whether the fix was offered */
    interface State {
        String own(String name, String titleId);
        boolean offered(String name, String titleId);
    }

    /** The rows of the table's text ('#' and empty lines skipped, the first other line the header: columns found by name) */
    static List<Fix> parse(String text) {
        List<Fix> out = new ArrayList<>();
        String[] head = null;
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (head == null) { head = f; continue; }
            Fix x = new Fix();
            x.rule = col(head, f, "rule_id"); x.name = col(head, f, "advanced_id"); x.value = col(head, f, "value"); x.scope = col(head, f, "scope"); x.sentence = col(head, f, "sentence");
            if (!x.rule.isEmpty() && !x.name.isEmpty() && !x.value.isEmpty()) out.add(x);
        }
        return out;
    }

    private static String col(String[] head, String[] f, String name) {
        for (int i = 0; i < head.length; i++) if (head[i].equals(name)) return i < f.length ? f[i].trim() : "";
        return "";
    }

    /** The key of a run's end: its digest rule's id, else "exit:" and the verdict's kind; null without either */
    static String key(String rule, String kind) {
        if (rule != null && !rule.isEmpty()) return rule;
        return kind == null || kind.isEmpty() ? null : EXIT + kind;
    }

    /** The fix to offer for this end on this game (titleId), or null */
    static Fix pick(List<Fix> fixes, List<AdvancedTable.Row> table, String key, String titleId, State s) {
        if (key == null || titleId == null || titleId.isEmpty()) return null;
        for (Fix f : fixes) {
            if (!f.rule.equals(key)) continue;
            AdvancedTable.Row r = row(table, f, titleId);
            if (r == null || !auto(s, f.name, titleId) || s.offered(f.name, titleId)) continue;
            return f;
        }
        return null;
    }

    /** The Advanced row a fix sets, when this game's page can set it to the fix's value; else null */
    static AdvancedTable.Row row(List<AdvancedTable.Row> table, Fix f, String titleId) {
        AdvancedTable.Row r = AdvancedTable.find(table, f.name);
        if (r == null || !r.editable() || r.developer() || !r.accepts(f.value)) return null;
        return r.forGame(f.global() ? null : titleId) ? r : null;
    }

    /** Whether the row is Auto for this game: neither its own value nor the one for every game is set */
    static boolean auto(State s, String name, String titleId) { return s.own(name, titleId) == null && s.own(name, null) == null; }

    /** Where the fix writes: the game's title ID, or null for every game */
    static String target(Fix f, String titleId) { return f.global() ? null : titleId; }

    /** The preference that remembers the fix was offered for this game */
    static String offeredKey(String name, String titleId) { return "advfix:" + name + "@" + titleId; }

    /** The button */
    static String button(AdvancedTable.Row r) { return "Try: " + r.title(); }

    /** The line above the button, before the tap */
    static String offerText(AdvancedTable.Row r, Fix f) { return "A setting may help: " + r.title() + " " + r.optionLabel(f.value) + ". " + f.sentence; }

    /** The line after the tap */
    static String doneText(AdvancedTable.Row r, Fix f, String game) {
        String who = f.global() ? "every game" : game == null || game.isEmpty() ? "this game" : game;
        return r.title() + " is " + r.optionLabel(f.value) + " for " + who + ". Applies at the next start.";
    }
}
