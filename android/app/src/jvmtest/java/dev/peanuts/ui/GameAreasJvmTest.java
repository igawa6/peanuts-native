// PEANUTS: GameAreas on the JVM (peanuts/tests/test_game_page.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GameAreasJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        // the nine areas, in the tiles' order, each with its icon and its line
        List<String> labels = new ArrayList<>();
        Set<String> icons = new HashSet<>();
        for (GameAreas.Area ar : GameAreas.ALL) {
            labels.add(ar.label);
            yes(ar.icon != null && !ar.icon.isEmpty(), ar.key + " has an icon");
            yes(icons.add(ar.icon), ar.key + "'s icon is its own");
            yes(ar.line != null && ar.line.endsWith("."), ar.key + " has a line");
            yes(!ar.line.contains("·") && !ar.line.contains("—") && !ar.line.contains("–"), ar.key + "'s line has no separator glyph or dash");
            eq(ar, GameAreas.area(ar.key));
        }
        eq("[Graphics, Mods & Cheats, Saves, Game options, Controls, Companion, Files, Help, Advanced]", labels);
        eq(null, GameAreas.area("nope"));

        // L1 / R1: round, among the areas the page has
        List<String> shown = Arrays.asList(GameAreas.GRAPHICS, GameAreas.MODS, GameAreas.SAVES, GameAreas.FILES);
        eq(GameAreas.MODS, GameAreas.step(shown, GameAreas.GRAPHICS, 1));
        eq(GameAreas.FILES, GameAreas.step(shown, GameAreas.GRAPHICS, -1));
        eq(GameAreas.GRAPHICS, GameAreas.step(shown, GameAreas.FILES, 1));
        eq(GameAreas.GRAPHICS, GameAreas.step(shown, GameAreas.HELP, 1));   // an area the page lacks: its first
        eq("x", GameAreas.step(new ArrayList<>(), "x", 1));

        // upstream's rows, by their titles, whatever heading they were built under
        eq("graphics/Driver/driver", GameAreas.place("GPU driver", "Speed", "🎮"));
        eq("graphics/Picture/upscale", GameAreas.place("Upscaler", "Picture", "🔍"));
        eq("graphics/Picture/scale", GameAreas.place("Resolution scale", "Picture", "🔍"));
        eq("graphics/Picture/docked", GameAreas.place("Console mode", null, "📺"));
        eq("graphics/Picture/display", GameAreas.place("Display", "Picture", "📺"));
        eq("graphics/Speed/lock", GameAreas.place("Hold 60 fps", "Speed", "🎯"));
        eq("controls/Controls/aim", GameAreas.place("Motion sensitivity", "Controls", "🎯"));
        eq("options/Game options/cartridge", GameAreas.place("Switch 2 Edition", null, "🎮"));
        eq("options/Game options/language", GameAreas.place("Game language", "Game options", "🌐"));
        eq("options/Game options/sun", GameAreas.place("Time of day", "Game options", "🕒"));
        eq("saves/Saves/save-up", GameAreas.place("Back up saves", null, "💾"));
        eq("saves/Saves/users", GameAreas.place("Copy to another user", null, ""));
        eq("files/Build/build", GameAreas.place("Build again", "Pack", "🔨"));
        eq("files/Build/prism", GameAreas.place("Prepare again", "Pack", "🔁"));
        eq("files/Pack/pack-out", GameAreas.place("Export pack…", "Pack", "📦"));
        eq("files/On this device/shortcut", GameAreas.place("Add to home screen", "More", "🏠"));
        eq("help/Help/clock", GameAreas.place("Played", "More", "⏱️"));
        eq("help/Help/share", GameAreas.place("Share log…", "More", "📨"));
        eq("advanced/Advanced/search", GameAreas.place("Advanced switches", "Advanced", "🛠️"));
        eq("advanced/Advanced/memory", GameAreas.place("RAM saver", "Advanced", "💾"));
        eq("companion/Companion/screens-cover", GameAreas.place("Second screen", null, "📱"));

        // a profile's own switch: Game options, its icon by its emoji (GameSettings.optionIcon)
        eq("options/Game options/resolution", GameAreas.place("A game's own switch", "Game options", "🔭"));
        eq("options/Game options/memory", GameAreas.place("Bigger pool", "Game options", "🧠"));
        // a row the table does not know: the area of its heading, in More, its emoji kept
        eq("graphics/More/null", GameAreas.place("A new picture row", "Picture", "🖼️"));
        eq("advanced/More/null", GameAreas.place("A new tester row", "advanced", "🧪"));
        eq("files/More/null", GameAreas.place("A new pack row", "Pack", "📦"));
        eq("help/More/null", GameAreas.place("A new row", "More", "x"));
        eq("options/More/null", GameAreas.place("A row with no heading", null, null));

        // every known row has an icon, and each title has its own (a few share a meaning: the sharpness rows, the two Share log titles)
        for (String t : GameAreas.titles()) yes(GameAreas.place(t, null, null).icon != null, t + " has an icon");

        // the groups of an area in their order, More last
        eq("[Driver, Picture, Speed, More]", GameAreas.groups(GameAreas.GRAPHICS));
        eq("[Build, Pack, On this device, More]", GameAreas.groups(GameAreas.FILES));
        eq("[More]", GameAreas.groups(GameAreas.MODS));

        // icons that follow the value
        GameAreas.Place cm = GameAreas.place("Console mode", null, null);
        eq("docked", GameAreas.icon(cm, "Console mode", "Docked (default)", null));
        eq("console", GameAreas.icon(cm, "Console mode", "Handheld", null));
        eq("docked", GameAreas.icon(cm, "Console mode", "Something new", null));   // other words: the table's
        GameAreas.Place ss = GameAreas.place("Second screen", null, null);
        eq("screens-android", GameAreas.icon(ss, "Second screen", "Off: no second display found, the companion does not load", null));
        eq("screens-cover", GameAreas.icon(ss, "Second screen", "On: the companion shows on the second screen", null));
        eq(null, GameAreas.icon(null, "x", null, null));

        // the tiles' lines
        eq("Fit", GameAreas.shortValue("Fit (keep aspect)"));
        eq("As in Settings", GameAreas.shortValue("As in Settings: Custom (4 buttons moved)"));
        eq("Catalog not loaded", GameAreas.shortValue("Catalog not loaded. Check the connection."));
        eq("Never drops to 30", GameAreas.shortValue("Never drops to 30; less even under 60"));
        eq("Phone's language", GameAreas.shortValue("Phone's language · English (US)"));   // upstream's own separator is cut, not shown on a tile
        eq(null, GameAreas.shortValue("  "));
        eq(null, GameAreas.shortValue(null));
        yes(GameAreas.shortValue("A value that is far too long to fit on a tile at all").length() <= 28, "a long value is cut");
        eq("Recommended, SGSR 1, 30 fps", GameAreas.line("Recommended", null, "SGSR 1", "", "30 fps"));
        eq("German", GameAreas.line("German", "German"));
        eq("", GameAreas.line());

        System.out.println("ok " + n);
    }
}
