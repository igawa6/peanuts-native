package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The areas of a game's page (GamePage), as data: each area's label, icon
 * and line, and for every row upstream's GameSettings and MainActivity.gameTab build, the area, the group and the Peanuts icon it gets. Keyed by the
 * row's title as upstream builds it. A row this table does not know (upstream added one) goes to the area of the heading it was built under, in a
 * group "More", with its own emoji, so nothing is lost after an update. Plain Java, no Android: peanuts/tests/test_game_page.py runs GameAreasJvmTest on it.
 */
public final class GameAreas {
    private GameAreas() {}

    public static final String GRAPHICS = "graphics", MODS = "mods", SAVES = "saves", OPTIONS = "options", CONTROLS = "controls", COMPANION = "companion",
            FILES = "files", HELP = "help", ADVANCED = "advanced";
    public static final String MORE = "More";

    /** One area: its key, its label on its tile and over its page, its Peanuts icon, and the line the bottom screen and a phone's page show under its name */
    public static final class Area {
        public final String key, label, icon, line;
        Area(String key, String label, String icon, String line) { this.key = key; this.label = label; this.icon = icon; this.line = line; }
    }

    /** The areas in the order of their tiles; Advanced last, as a quieter tile across the width */
    public static final List<Area> ALL = Collections.unmodifiableList(Arrays.asList(
            new Area(GRAPHICS, "Graphics", "picture", "How the game is drawn and how fast it runs. What is not set here follows Settings."),
            new Area(MODS, "Mods & Cheats", "mods", "Cheats and mods you added from this device. A cheat works at once in the game, a mod from its next start."),
            new Area(SAVES, "Saves", "saves", "The game's saves: back one up, bring one back, or give it to another user."),
            new Area(OPTIONS, "Game options", "game", "The game's own switches and its language. Most apply from the next start."),
            new Area(CONTROLS, "Controls", "controller", "Rumble, buttons and motion for this game. What is not set here follows Settings."),
            new Area(COMPANION, "Companion", "companion", "The second screen's page made for this game, and what that screen shows while it runs."),
            new Area(FILES, "Files", "storage", "The game's files, its build, its pack, its folders and its shortcut."),
            new Area(HELP, "Help", "info", "Your play time, the newest log for a bug report, and the game's details."),
            new Area(ADVANCED, "Advanced", "settings", "For testing and troubleshooting. Each switch starts at Auto.")));

    public static Area area(String key) {
        for (Area a : ALL) if (a.key.equals(key)) return a;
        return null;
    }

    /** The area before (-1) or after (+1) this one, round, among those the page has */
    public static String step(List<String> shown, String key, int delta) {
        if (shown.isEmpty()) return key;
        int i = shown.indexOf(key);
        if (i < 0) return shown.get(0);
        return shown.get(Math.floorMod(i + delta, shown.size()));
    }

    /** Where a row goes: its area, its group, its icon (null: keep its emoji) and its place in the group */
    public static final class Place {
        public final String area, group, icon;
        public final int order;
        Place(String area, String group, String icon, int order) { this.area = area; this.group = group; this.icon = icon; this.order = order; }
        @Override public String toString() { return area + "/" + group + "/" + icon; }
    }

    private static final Map<String, Place> ROWS = new HashMap<>();
    private static final List<String> GROUP_ORDER = new ArrayList<>();

    private static void group(String area, String group, String... titlesAndIcons) {
        GROUP_ORDER.add(area + "/" + group);
        for (int i = 0; i < titlesAndIcons.length; i += 2) ROWS.put(titlesAndIcons[i], new Place(area, group, titlesAndIcons[i + 1], i / 2));
    }

    static {
        group(GRAPHICS, "Driver", "GPU driver", "driver");
        group(GRAPHICS, "Picture", "Resolution", "resolution", "Window", "window", "3D scene", "scene", "Upscaling quality", "pie", "Graphics quality", "quality",
                "Console mode", "docked", "Resolution scale", "scale", "Upscaler", "upscale", "Motion clarity", "clarity", "Sharpness", "contrast",
                "Temporal sharpness", "contrast", "FSR sharpness", "contrast", "Display", "display", "Aspect ratio", "aspect");
        group(GRAPHICS, "Speed", "Frame rate cap", "gauge", "Hold 60 fps", "lock", "Full game speed", "fast-forward", "Dynamic resolution", "dynamic");
        group(OPTIONS, "Game options", "Switch 2 Edition", "cartridge", "Full vegetation", "leaf", "Hidden layers off", "layers", "Cheats", "mods",
                "Cheat files", "folder", "Link Play", "link", "Game language", "language", "Time of day", "sun");
        group(CONTROLS, "Controls", "Rumble", "rumble", "Button mapping", "buttons", "Motion sensitivity", "aim", "Xbox button labels", "labels");
        group(COMPANION, "Companion", "Companion", "companion", "Second screen", "screens-cover");
        group(SAVES, "Saves", "Back up saves", "save-up", "Restore saves", "history", "Import save", "save-down", "Copy to another user", "users",
                "Delete save data", "trash");
        group(FILES, "Build", "Build again", "build", "Prepare again", "prism", "Preparing", "hourglass");
        group(FILES, "Pack", "Export pack…", "pack-out", "Import pack…", "pack-in");
        group(FILES, "On this device", "Folders", "folder", "Add to home screen", "shortcut");
        group(HELP, "Help", "Played", "clock", "Share log…", "share", "Share log", "share", "Share build log", "log");
        group(ADVANCED, "Advanced", "GPU timing", "timing", "Instanced draws", "copies", "Classic render passes", "passes", "Background pipelines", "pipeline",
                "RAM saver", "memory", "Use fast cores", "cores", "Pause on return", "pause", "Advanced switches", "search", "Extra environment", "terminal");
    }

    /** The headings upstream builds its rows under (its sections and the Saves and Storage tabs' labels), to the area a row it adds later goes to */
    private static final Map<String, String> HEADINGS = new HashMap<>();
    static {
        for (String h : new String[] {"picture", "speed"}) HEADINGS.put(h, GRAPHICS);
        for (String h : new String[] {"game options", "mods & cheats", "mods"}) HEADINGS.put(h, OPTIONS);
        HEADINGS.put("controls", CONTROLS);
        HEADINGS.put("companion", COMPANION);
        HEADINGS.put("advanced", ADVANCED);
        HEADINGS.put("whose save", SAVES);
        HEADINGS.put("saves", SAVES);
        for (String h : new String[] {"game files", "pack"}) HEADINGS.put(h, FILES);
        HEADINGS.put("more", HELP);
    }

    /** A profile option's switch (GameSettings.optionIcon: an emoji by what its name touches) to its Peanuts icon */
    private static final Map<String, String> OPTION_EMOJI = new HashMap<>();
    static {
        OPTION_EMOJI.put("🔭", "resolution");
        OPTION_EMOJI.put("🧠", "memory");
        OPTION_EMOJI.put("⏩", "fast-forward");
        OPTION_EMOJI.put("🎚️", "settings");
    }

    /** The place of a row by its title; heading is the label it was built under (null: none), emoji its tile's emoji (a profile option's icon) */
    public static Place place(String title, String heading, String emoji) {
        Place p = title == null ? null : ROWS.get(title.trim());
        if (p != null) return p;
        String area = heading == null ? null : HEADINGS.get(heading.trim().toLowerCase(Locale.ROOT));
        if (area == null) area = OPTIONS;
        String icon = emoji == null ? null : OPTION_EMOJI.get(emoji.trim());
        if (area.equals(OPTIONS) && icon != null) return new Place(OPTIONS, "Game options", icon, 2);   // a profile's own switch: after Full vegetation and Hidden layers off, as upstream has it
        return new Place(area, MORE, null, 1000);
    }

    /** The groups of an area in the order its page shows them (More last) */
    public static List<String> groups(String area) {
        List<String> out = new ArrayList<>();
        for (String g : GROUP_ORDER) if (g.startsWith(area + "/")) out.add(g.substring(area.length() + 1));
        out.add(MORE);
        return out;
    }

    /**
     * A row whose icon follows its value (as in Settings: SettingsSections.BY_VALUE): its title, then pairs of the value's first word and the icon; a row
     * with a switch has "on" and "off". The table's icon stays when the value says something else
     */
    static final String[][] BY_VALUE = {
            {"Console mode", "Docked", "docked", "Handheld", "console"},
            {"Second screen", "On", "screens-cover", "Off", "screens-android"}};

    /** The icon of a row as it is set now: by its value or its switch where the row's icon follows them, else the table's (null: keep the emoji) */
    public static String icon(Place p, String title, CharSequence value, Boolean on) {
        if (p == null) return null;
        for (String[] r : BY_VALUE) {
            if (!r[0].equals(title)) continue;
            String v = on != null ? (on ? "on" : "off") : value == null ? "" : value.toString().trim();
            for (int i = 1; i + 1 < r.length; i += 2) if (v.regionMatches(true, 0, r[i], 0, r[i].length())) return r[i + 1];
        }
        return p.icon;
    }

    /** A value shortened for a tile's line: the words before a bracket or a colon (Fit (keep aspect) to Fit), at most 28 characters */
    public static String shortValue(CharSequence v) {
        if (v == null) return null;
        String s = v.toString().trim();
        int cut = s.length();
        for (String c : new String[] {" (", ": ", "; ", ". ", " " + (char) 0xB7 + " "}) { int i = s.indexOf(c); if (i > 0 && i < cut) cut = i; }   // and upstream's middle dot (spelled as a char: no dot in a string here)
        s = s.substring(0, cut).trim();
        if (s.length() > 28) s = s.substring(0, 27).trim() + "…";
        return s.isEmpty() ? null : s;
    }

    /** A tile's line from the values it names, in order, the empty ones left out, joined with commas */
    public static String line(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.trim().isEmpty() && !out.contains(v.trim())) out.add(v.trim());
        return String.join(", ", out);
    }

    /** Every title the table knows, for the test */
    static java.util.Set<String> titles() { return ROWS.keySet(); }
}
