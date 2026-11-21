// PEANUTS: SettingsSections on the JVM (peanuts/tests/test_settings_sections.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SettingsSectionsJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        // the ten sections, in the sidebar's order, each with its icon
        List<String> labels = new ArrayList<>();
        for (SettingsSections.Section s : SettingsSections.ALL) {
            labels.add(s.label);
            yes(s.icon != null && !s.icon.isEmpty(), s.key + " has an icon");
            yes(s.line != null && !s.line.isEmpty(), s.key + " has a line under its title");
            yes(!SettingsSections.summary(s, true).equals(s.line), s.key + " has a few words of what it holds (a tablet's tile)");
            eq(s.key.equals("screens") ? "screen-landscape" : s.icon, SettingsSections.icon(s, false));   // one screen: only Screens changes its icon
            eq(s.icon, SettingsSections.icon(s, true));
        }
        eq("[Library, Console, Graphics, Controls, Screens, Users, App, Help, Developer, About]", labels);
        eq(labels, SettingsSections.names());

        // the rows of the long page, by the heading they were built under
        eq("library/Folders", SettingsSections.place("Game folder", "Library"));
        eq("library/Space", SettingsSections.place("Storage", "System"));
        eq("console/Keys and firmware", SettingsSections.place("Keys", "Library"));
        eq("console/Keys and firmware", SettingsSections.place("Firmware", "Library"));
        eq("console/Clock", SettingsSections.place("Time of day", "System"));
        eq("graphics/Driver", SettingsSections.place("GPU driver", "System"));
        eq("graphics/Picture", SettingsSections.place("Upscaler", "Performance and picture"));
        eq("graphics/Speed", SettingsSections.place("Overlay", "Display"));
        eq("controls/Buttons", SettingsSections.place("Xbox button label swap", "Controls"));
        eq("screens/Second screen", SettingsSections.place("Swap screens", "Library"));
        eq("screens/Display", SettingsSections.place("Keep the screen on while preparing", "System"));
        eq("users/Users", SettingsSections.place("Users", "System"));
        eq("users/Miis", SettingsSections.place("Miis", "Library"));
        eq("app/Feel", SettingsSections.place("Haptics", "Library"));
        eq("app/Updates", SettingsSections.place("Check for updates", "Updates"));
        eq("help/Questions and logs", SettingsSections.place("Questions and answers", "Updates"));
        eq("help/Questions and logs", SettingsSections.place("Share log", "Help"));
        eq("developer/Testing", SettingsSections.place("Advanced", "System"));
        eq("developer/Testing", SettingsSections.place("ADB control (debug)", "Help"));
        eq("graphics", SettingsSections.place("Upscaler", null).section.key);   // the title decides, whatever the heading
        eq("search", SettingsSections.place("Upscaler", null).icon);

        // a row the table does not know: the section of its heading, in More, no icon (its emoji stays)
        eq("screens/More", SettingsSections.place("A new display row", "Display"));
        eq("graphics/More", SettingsSections.place("A new picture row", "performance and picture"));
        eq("app/More", SettingsSections.place("A row under a new heading", "Somewhere new"));
        eq("app/More", SettingsSections.place("A row with no heading", null));
        eq("null", SettingsSections.place("A row with no heading", null).icon);

        // no title in two places, every group has rows, More comes last
        Set<String> seen = new HashSet<>();
        for (SettingsSections.Section s : SettingsSections.ALL) {
            List<String> order = SettingsSections.groupOrder(s);
            eq(SettingsSections.MORE, order.get(order.size() - 1));
            for (SettingsSections.Group g : s.groups) {
                yes(!g.rows.isEmpty(), s.key + "/" + g.label + " has rows");
                for (SettingsSections.Row r : g.rows) {
                    yes(seen.add(r.title), r.title + " is in one place only");
                    yes(r.icon != null && !r.icon.isEmpty(), r.title + " has an icon");
                    eq(g.rows.indexOf(r), SettingsSections.order(s, g.label, r.title));
                }
            }
        }
        eq(Integer.MAX_VALUE, SettingsSections.order(SettingsSections.section("graphics"), "Picture", "Not in the table"));

        // the control interface's names: a section (label or key), a group, an old heading
        eq("graphics/null", SettingsSections.find("Graphics"));
        eq("graphics/null", SettingsSections.find("  graphics "));
        eq("graphics/Picture", SettingsSections.find("picture"));
        eq("graphics/null", SettingsSections.find("Performance and picture"));
        eq("console/null", SettingsSections.find("System"));
        eq("app/Updates", SettingsSections.find("Updates"));   // a group of App now, and the old heading's rows are there
        eq("screens/Display", SettingsSections.find("Display"));   // the old heading Display is a group of Screens now
        eq("null", SettingsSections.find("Nowhere"));
        eq("null", SettingsSections.find(null));

        // icons that show what a row is set to: its value line's first word(s), or its switch
        SettingsSections.Place screens = SettingsSections.place("Bottom screen", null);
        eq("companion", SettingsSections.icon(screens, "Bottom screen", "On: a second screen shows the game you are on", null));
        eq("screens-android", SettingsSections.icon(screens, "Bottom screen", "Off: a second screen shows Android's own launcher", null));
        eq("swap-on", SettingsSections.icon(screens, "Swap screens", "On: games start on the other screen", null));
        eq("swap-off", SettingsSections.icon(screens, "Swap screens", "Off: games start on this screen", null));
        eq("swap-off", SettingsSections.icon(SettingsSections.place("Swap screens", null), "Swap screens", "Onward", null));   // a word, not a prefix: the table's icon
        eq("screens-cover", SettingsSections.icon(screens, "Games without a companion", "The game's cover stays on the second screen", null));
        eq("screens-android", SettingsSections.icon(screens, "Games without a companion", "Needs Bottom screen on.", null));
        eq("screen-rotate", SettingsSections.icon(screens, "Screen rotation", "Automatic: the game follows the phone's rotation", null));
        eq("screen-landscape", SettingsSections.icon(screens, "Screen rotation", "Landscape: the game window stays horizontal", null));
        eq("awake", SettingsSections.icon(screens, "Keep the screen on while preparing", "While Home shows a game being prepared", true));
        eq("sleep", SettingsSections.icon(screens, "Keep the screen on while preparing", "While Home shows a game being prepared", false));
        SettingsSections.Place mode = SettingsSections.place("Console mode", null);
        eq("docked", SettingsSections.icon(mode, "Console mode", "Docked", null));
        eq("console", SettingsSections.icon(mode, "Console mode", "Handheld", null));
        eq("game", SettingsSections.icon(mode, "Console mode", "Each game's own (most: Docked)", null));   // no word of its own: the table's
        eq("search", SettingsSections.icon(SettingsSections.place("Upscaler", null), "Upscaler", "FSR", null));   // a row that does not follow its value
        eq("null", String.valueOf(SettingsSections.icon(SettingsSections.place("Not in the table", null), "Not in the table", "On", null)));
        yes(SettingsSections.followsValue("Swap screens") && !SettingsSections.followsValue("Upscaler"), "followsValue");
        yes(!SettingsSections.summary(SettingsSections.section("screens"), false).contains("Both"), "one screen: Screens' tile says nothing of two");

        System.out.println("ok " + n);
    }
}
