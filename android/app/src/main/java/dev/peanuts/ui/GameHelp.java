package dev.peanuts.ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What each row of a game's page does, for the bottom screen's explanation of the row the focus is on (GamePage; the game's page in areas). Keyed by the
 * row's title as upstream builds it (GameAreas has the same titles). For this game only: a value set on the page wins over Settings for this game.
 * Plain Java, no Android: peanuts/tests/test_game_page.py runs GameHelpJvmTest on it.
 */
public final class GameHelp {
    private GameHelp() {}

    private static final Map<String, String> ALL;

    static {
        Map<String, String> m = new HashMap<>();
        // Graphics
        m.put("GPU driver", "The Vulkan driver this game runs on. A driver picked here is for this game only; Default follows Settings.");
        m.put("Resolution", "The size the game draws at. Higher is sharper and needs more of the GPU. From the next start.");
        m.put("Window", "The size of the game's window, set apart from its 3D scene. For testers.");
        m.put("3D scene", "The size of the game's 3D scene inside its window. Menus and text stay sharp. From the next start.");
        m.put("Upscaling quality", "How much of the picture the game draws before it is scaled up. Higher is sharper and slower.");
        m.put("Graphics quality", "The game's own quality for its effects and detail.");
        m.put("Console mode", "Docked draws as on a TV, up to 1080p. Handheld draws as on the Switch's own screen, which is lighter, and turns on its touch screen.");
        m.put("Resolution scale", "Draws the game's picture at a multiple of the size it asks for. Above 1x is sharper and heavier, below 1x is lighter. From the next start.");
        m.put("Upscaler", "How the game's picture is scaled up to the screen. FSR and SGSR also sharpen it. It changes at once in a running game.");
        m.put("Motion clarity", "How much of the frames before the upscaler keeps. Steady hides shimmer, Clear shows less ghosting.");
        m.put("Sharpness", "How strongly the upscaler sharpens the picture. Higher is crisper and can show hard edges.");
        m.put("Temporal sharpness", "How strongly the upscaler sharpens the picture. Higher is crisper and can show hard edges.");
        m.put("FSR sharpness", "How strongly FSR sharpens the picture. Higher is crisper and can show hard edges.");
        m.put("Display", "Fit keeps the game's shape with bars at its sides, Stretch fills the screen, and Fill lets a game that can draw for this screen's shape fill it.");
        m.put("Aspect ratio", "A wider picture where the game can draw one. From the next start.");
        m.put("Frame rate cap", "The highest frame rate this game runs at. A lower cap saves battery and keeps the device cooler. It changes at once in a running game.");
        m.put("Hold 60 fps", "Keeps the game at 60 when a frame is late, instead of dropping to 30.");
        m.put("Full game speed", "Keeps the game at its normal speed when the frame rate drops. Off, it slows down with the frame rate, as on the console.");
        m.put("Dynamic resolution", "Lets the game draw smaller while a scene is heavy, so its frame rate holds.");
        // Game options
        m.put("Switch 2 Edition", "Which game Play starts: the Switch game or its Switch 2 Edition. Both use one save.");
        m.put("Full vegetation", "All grass and foliage, or less of it for less work on the GPU.");
        m.put("Hidden layers off", "Skips layers the picture covers anyway. Faster; turn it off if something is missing from the picture.");
        m.put("Link Play", "Plays together with a PC that hosts the game.");
        m.put("Game language", "The language the game starts in. Default follows the phone's language where the game has it.");
        m.put("Time of day", "The clock the game sees, for its day and night. From the next start.");
        // Controls
        m.put("Rumble", "Lets this game rumble the controller, or the phone when no controller is connected.");
        m.put("Button mapping", "Which button of your controller presses which Switch button, for this game. Default follows Settings.");
        m.put("Motion sensitivity", "How far the phone's gyro turns the aim. From the next start.");
        m.put("Xbox button labels", "For a controller labelled like an Xbox pad: whether A and B, and X and Y, change places.");
        // Companion
        m.put("Companion", "The second screen's page made for this game: install it, update it or remove it.");
        m.put("Second screen", "Whether the companion shows on the second screen while the game runs.");
        // Saves
        m.put("Back up saves", "Copies the save into a backup you can bring back. The game and its save stay as they are.");
        m.put("Restore saves", "Brings back a backup. The save you have now is backed up first.");
        m.put("Import save", "Takes a save from a .zip or a folder. The save you have now is backed up first.");
        m.put("Copy to another user", "Gives this save to another user of this device. Their save is backed up first.");
        m.put("Delete save data", "Backs the save up, then deletes it, so the game starts from the beginning.");
        // Files
        m.put("Build again", "Opens the game's build: rebuild it after an update, or import the game again.");
        m.put("Prepare again", "Makes the game's pack again from its dump, for what no longer fits.");
        m.put("Preparing", "The preparation of this game that runs now: forget it, or delete its files.");
        m.put("Export pack…", "Saves the game's pack as one .zip, in a folder you pick.");
        m.put("Import pack…", "Takes a pack from a .zip or a folder. It is checked before it replaces anything.");
        m.put("Folders", "Where the game's files, its pack and its saves are.");
        m.put("Add to home screen", "A shortcut on the phone's home screen that starts this game.");
        // Help
        m.put("Played", "The time you played this game in this app, counted only while it runs.");
        m.put("Share log…", "Sends the newest log of a run or a build of this game, for a bug report.");
        m.put("Share log", "Sends the newest log of a run of this game, for a bug report.");
        m.put("Share build log", "Sends the logs of this game's build in one .zip, for a bug report.");
        // Advanced
        m.put("GPU timing", "What the game's GPU timestamps measure. Per segment lets its dynamic resolution see the real GPU time.");
        m.put("Instanced draws", "Draws repeated meshes as one: less work for the GPU. For testing.");
        m.put("Classic render passes", "Render passes as before dynamic rendering: less work on the CPU with Qualcomm's driver. For testing.");
        m.put("Background pipelines", "Makes the game's pipelines in the background: a short pop-in instead of a stutter.");
        m.put("RAM saver", "Keeps texture data in a file instead of compressed memory. Needs gigabytes free.");
        m.put("Use fast cores", "Keeps the game's busiest threads off the small cores. Faster, and a little warmer.");
        m.put("Pause on return", "When you come back to the game from another app, it waits paused until a tap.");
        m.put("Advanced switches", "Every switch the app's host has for this game, by area, with a search. Each starts at Auto.");
        m.put("Extra environment", "Lines for the game's environment, for testers.");
        ALL = Collections.unmodifiableMap(m);
    }

    /** Rows that do something (a backup, a build, a share), whose value line says what they do rather than how they are set: the second screen shows no "Now" for them */
    private static final java.util.Set<String> ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "Back up saves", "Restore saves", "Import save", "Copy to another user", "Delete save data", "Build again", "Prepare again", "Preparing",
            "Export pack…", "Import pack…", "Folders", "Add to home screen", "Share log…", "Share log", "Share build log"));

    /** Whether the row does something rather than hold a setting */
    public static boolean action(String title) { return title != null && ACTIONS.contains(title.trim()); }

    /** What the row does, null for a row this table does not know */
    public static String of(String title) { return title == null ? null : ALL.get(title.trim()); }

    /** Every title with help, for the test */
    static java.util.Set<String> titles() { return ALL.keySet(); }
}
