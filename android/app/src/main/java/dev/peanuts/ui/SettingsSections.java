package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ten sections of Settings (in place of one long page), as data: each section's label, icon,
 * line under its title and groups, and in each group the rows by their title as Settings builds them (upstream's MainActivity.buildSettings and
 * LibrarySettings.add), each with the Peanuts icon of its tile. SettingsPage puts the rows Settings built into these places. A row this table does not
 * know (upstream added one) goes to the section of the heading it was built under, in a group "More", so nothing is lost after an update.
 * Plain Java, no Android: peanuts/tests/test_settings_sections.py runs SettingsSectionsJvmTest on it.
 */
public final class SettingsSections {
    private SettingsSections() {}

    /** A row: its title as Settings builds it, and its tile's icon (a pi_* drawable's name) */
    public static final class Row {
        public final String title, icon;
        Row(String title, String icon) { this.title = title; this.icon = icon; }
    }

    /** A group of rows under its label, in one card */
    public static final class Group {
        public final String label;
        public final List<Row> rows;
        Group(String label, Row... rows) { this.label = label; this.rows = Collections.unmodifiableList(java.util.Arrays.asList(rows)); }
    }

    /** A section: a page of its own, an entry of the sidebar or the drawer */
    public static final class Section {
        public final String key, label, icon, line;
        public final List<Group> groups;
        Section(String key, String label, String icon, String line, Group... groups) {
            this.key = key; this.label = label; this.icon = icon; this.line = line;
            this.groups = Collections.unmodifiableList(java.util.Arrays.asList(groups));
        }
    }

    /** Where a row goes: its section and its group's label ("More" for a row the table does not know) */
    public static final class Place {
        public final Section section;
        public final String group;
        public final String icon;   // the tile's icon, null when the table does not know the row
        Place(Section s, String group, String icon) { section = s; this.group = group; this.icon = icon; }
        @Override public String toString() { return section.key + "/" + group; }
    }

    public static final String MORE = "More";
    private static final String DEFAULTS = "For every game. A game's own settings come first.";

    private static Row r(String title, String icon) { return new Row(title, icon); }

    public static final List<Section> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            new Section("library", "Library", "library", "Where the app finds your games, the space they take and the time you played.",
                    new Group("Folders", r("Game folder", "folder"), r("Update folder", "update"), r("DLC folder", "dlc"), r("Build folders", "build"),
                            r("Folders", "folder"), r("Rescan", "search")),
                    new Group("Space", r("Free up space", "trash"), r("Storage", "storage")),
                    new Group("Play time", r("Played", "clock"))),
            new Section("console", "Console", "console", "What games find on a console of their own: its keys, its firmware and its clock.",
                    new Group("Keys and firmware", r("Keys", "key"), r("Firmware", "driver")),
                    new Group("Clock", r("Time of day", "clock"))),
            new Section("graphics", "Graphics", "driver", DEFAULTS,
                    new Group("Driver", r("GPU driver", "driver")),
                    new Group("Picture", r("Console mode", "game"), r("Resolution", "search"), r("Resolution scale", "search"), r("Upscaler", "search"),
                            r("FSR sharpness", "search"), r("CRT filter", "screen-landscape"), r("Dynamic resolution", "gauge")),
                    new Group("Speed", r("Frame rate cap", "gauge"), r("Frame generation", "gauge"), r("Use fast cores", "gauge"), r("Overlay", "list"))),
            new Section("controls", "Controls", "controller", DEFAULTS,
                    new Group("Buttons", r("Button mapping", "controller"), r("Xbox button label swap", "controller")),
                    new Group("Touch", r("On-screen controls", "game"), r("Show / hide button", "game")),
                    new Group("Controller", r("Controller: in-game panel", "controller"), r("Mods tab", "mods"), r("Rumble", "controller"))),
            new Section("screens", "Screens", "companion", "What each screen shows, and when the screen stays on.",
                    new Group("Second screen", r("Bottom screen", "companion"), r("Games without a companion", "screens-cover"), r("Swap screens", "swap-off"),
                            r("Dual-screen companions", "companion")),
                    new Group("TV", r("TV and monitors", "tv")),
                    new Group("Display", r("Screen rotation", "screen-landscape"), r("Keep the screen on while preparing", "awake"))),
            new Section("users", "Users", "user", "Who plays, and the Miis games can use.",
                    new Group("Users", r("Users", "user")),
                    new Group("Miis", r("Miis", "user"))),
            new Section("app", "App", "settings", "How the app feels, and its updates.",
                    new Group("Feel", r("Haptics", "controller"), r("Motion", "play")),
                    new Group("Updates", r("Check for updates", "download"))),
            new Section("help", "Help", "info", "Checks for this device, the log, and answers to common questions.",
                    new Group("Checks", r("Readiness check", "check"), r("Graphics self-test", "play")),
                    new Group("Questions and logs", r("Questions and answers", "info"), r("Share log", "share"))),
            new Section("developer", "Developer", "build", "For testing and for adb. Leave these as they are unless you were asked to change one.",
                    new Group("Testing", r("Advanced", "settings"), r("Tester settings", "build"), r("ADB control (debug)", "build"))),
            new Section("about", "About", "peanut", "The app, the games in it and the people who made it.")));

    /** What each section holds, in a few words: a tablet's tile of it (SettingsPage's hub) */
    private static final String[][] SUMMARY = {
            {"library", "Game, update and DLC folders, builds, space and play time"}, {"console", "Keys, firmware and the clock"},
            {"graphics", "GPU driver, picture and speed"}, {"controls", "Buttons, touch controls and the controller"},
            {"screens", "Both screens, a TV, rotation, keeping the screen on"}, {"users", "Users and Miis"}, {"app", "Haptics, motion and updates"},
            {"help", "Readiness check, self-test, log and answers"}, {"developer", "Advanced, tester settings and adb"},
            {"about", "The app, its games, credits, licenses and Peanuts cracked"}};

    /** A section's few words of what it holds; its line when there are none. twoScreens: the device has a second screen (Screens says so) */
    public static String summary(Section s, boolean twoScreens) {
        if (s.key.equals("screens") && !twoScreens) return "Rotation, a TV, keeping the screen on, and a second screen";
        for (String[] x : SUMMARY) if (x[0].equals(s.key)) return x[1];
        return s.line;
    }

    /** A section's icon on this device: Screens shows two screens on a device with a second one, one screen on any other */
    public static String icon(Section s, boolean twoScreens) {
        return s.key.equals("screens") && !twoScreens ? "screen-landscape" : s.icon;
    }

    /**
     * The rows whose icon shows what they are set to now: the row's title, then pairs of how its value line starts (a whole word or more) and the icon
     * for it. A row with a switch has the pairs "on" and "off" instead. A value that starts otherwise (upstream changed its text) keeps the table's icon
     */
    static final String[][] BY_VALUE = {
            {"Bottom screen", "On", "companion", "Off", "screens-android"},
            {"Games without a companion", "The game's cover", "screens-cover", "Stats", "gauge", "Android's", "screens-android", "Needs", "screens-android"},
            {"Swap screens", "On", "swap-on", "Off", "swap-off"},
            {"Screen rotation", "Automatic", "screen-rotate", "Landscape", "screen-landscape"},
            {"Keep the screen on while preparing", "on", "awake", "off", "sleep"},
            {"Console mode", "Docked", "docked", "Handheld", "console"}};

    /** Whether the row's icon follows its value (BY_VALUE) */
    public static boolean followsValue(String title) {
        for (String[] r : BY_VALUE) if (r[0].equals(title)) return true;
        return false;
    }

    /**
     * The icon of a row's tile as it is set now: from its value line, or from its switch (on: null when it has none); otherwise the table's icon (the
     * place's), null for a row the table does not know
     */
    public static String icon(Place p, String title, CharSequence value, Boolean on) {
        for (String[] r : BY_VALUE) {
            if (!r[0].equals(title)) continue;
            String now = on != null ? (on ? "on" : "off") : value == null ? "" : value.toString();
            for (int i = 1; i + 1 < r.length; i += 2) if (startsWord(now, r[i])) return r[i + 1];
        }
        return p == null ? null : p.icon;
    }

    private static boolean startsWord(String s, String start) {
        return s.startsWith(start) && (s.length() == start.length() || !Character.isLetter(s.charAt(start.length())));
    }

    /** Upstream's headings of the one long page, and the section their rows belong to now (for a row the table does not know, and for Ctl's old names) */
    private static final String[][] HEADINGS = {
            {"Library", "library"}, {"Updates", "app"}, {"Display", "screens"}, {"Controls", "controls"}, {"Performance and picture", "graphics"},
            {"Game defaults", "graphics"}, {"System", "console"}, {"Help", "help"}, {"About", "about"}};

    public static Section section(String key) {
        for (Section s : ALL) if (s.key.equals(key)) return s;
        return null;
    }

    /** Where a row of this title goes; heading is the label it was built under on the long page (null: none, the first card) */
    public static Place place(String title, String heading) {
        for (Section s : ALL) for (Group g : s.groups) for (Row r : g.rows) if (r.title.equals(title)) return new Place(s, g.label, r.icon);
        return new Place(ofHeading(heading), MORE, null);
    }

    /** The section of one of upstream's headings; App for one this table does not know */
    static Section ofHeading(String heading) {
        if (heading != null) for (String[] h : HEADINGS) if (h[0].equalsIgnoreCase(heading.trim())) return section(h[1]);
        return section("app");
    }

    /** The place a name points at, for the control interface (`nav to=settings section=...`): a section's label or key, a group's label, or one of the
     *  long page's old headings (its section). Null when it is none of these */
    public static Place find(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Section s : ALL) if (s.label.equalsIgnoreCase(n) || s.key.equalsIgnoreCase(n)) return new Place(s, null, s.icon);
        for (Section s : ALL) for (Group g : s.groups) if (g.label.equalsIgnoreCase(n)) return new Place(s, g.label, s.icon);
        for (String[] h : HEADINGS) if (h[0].equalsIgnoreCase(n)) return new Place(section(h[1]), null, null);
        return null;
    }

    /** Every name find() takes, for the control interface's error line */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Section s : ALL) out.add(s.label);
        return out;
    }

    /** The group order of a section, with "More" last for rows the table does not know */
    public static List<String> groupOrder(Section s) {
        List<String> out = new ArrayList<>();
        for (Group g : s.groups) out.add(g.label);
        out.add(MORE);
        return out;
    }

    /** The position of a row in its group (the table's order; a row it does not know after the others, in the order it was built) */
    public static int order(Section s, String group, String title) {
        for (Group g : s.groups) if (g.label.equals(group)) for (int i = 0; i < g.rows.size(); i++) if (g.rows.get(i).title.equals(title)) return i;
        return Integer.MAX_VALUE;
    }
}
