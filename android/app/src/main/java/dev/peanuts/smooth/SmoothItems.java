package dev.peanuts.smooth;

import java.util.Collections;
import java.util.List;

/**
 * Smooth Experience (Settings › App › Extras): a game that starts and plays as on a console. One switch, off by default, and, while it is on, one switch
 * per thing it takes away right under it on the same page, all on: the start's log lines (a peanut and the game's cover instead) and the notices about
 * graphics work over the game. With the switch off nothing changes. A notice it hides still goes to the log (Native.note), so a bug report keeps its
 * facts. Crashes, failures with Share log, anything about the player's save and the answers to the player's own gestures always show. The overlay has
 * its own switches (Settings › Graphics › Overlay); the testers' toasts and the in-game menu's tool words are Developer view's (DevView).
 * Plain Java, no Android: peanuts/tests/test_smooth_experience.py runs SmoothJvmTest on it.
 */
public final class SmoothItems {
    private SmoothItems() {}

    /** The setting's name, the one place the Smooth classes take it from (SettingsSections, SettingsHelp and LibrarySettings spell it too; the test holds them to it) */
    public static final String NAME = "Smooth Experience";

    // The items' keys (PeanutsPrefs: "smooth_<key>")
    public static final String START_LOG = "start_log", WARM = "warm", SKIP = "skip";

    /** One switch under Smooth Experience: its key, its row's title (as Settings spells it) and the line under it (what goes, and what shows instead) */
    public static final class Item {
        public final String key, title, line;
        Item(String key, String title, String line) { this.key = key; this.title = title; this.line = line; }
    }

    public static final List<Item> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            new Item(START_LOG, "Start screen", "The game's cover and a peanut instead of log lines, until the game shows."),
            new Item(WARM, "Preparing graphics notice", "No notice while the game makes graphics in the background. On a first run parts of the picture may be missing for a moment."),
            new Item(SKIP, "Graphics driver notice", "No notice when the graphics driver left out parts of the picture.")));

    public static Item of(String key) {
        for (Item it : ALL) if (it.key.equals(key)) return it;
        return null;
    }

    /** The settings row's line: off, or on with how many of the switches under it are on */
    public static String rowLine(boolean on, int picked) {
        if (!on) return "Off. Turn it on for a start and play without logs and notices.";
        if (picked == ALL.size()) return "On. A calm start and no notices over the game. Pick each part below.";
        if (picked == 0) return "On, but nothing below is picked.";
        return "On. " + picked + " of " + ALL.size() + " parts below picked.";
    }
}
