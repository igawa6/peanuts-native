package dev.peanuts.ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What each row of Settings does and its app default, for the bottom screen's explanation of the row the focus is on (SettingsPage, BottomPanel's
 * explanation page). Keyed by the row's title as Settings builds it (SettingsSections has the same titles). The default is the words the row's value
 * shows on a fresh install; null for a row that opens a page or does something (Rescan, Share log), and for a default that depends on the device.
 * Plain Java, no Android: peanuts/tests/test_settings_explain.py runs SettingsHelpJvmTest on it.
 */
public final class SettingsHelp {
    private SettingsHelp() {}

    /** A row's explanation and its default (null: none to name) */
    public static final class Help {
        public final String text, deflt;
        Help(String text, String deflt) { this.text = text; this.deflt = deflt; }
    }

    private static final Map<String, Help> ALL;

    private static void put(Map<String, Help> m, String title, String text, String deflt) { m.put(title, new Help(text, deflt)); }

    static {
        Map<String, Help> m = new HashMap<>();
        // Library
        put(m, "Game folder", "A folder the app looks in for your games: NSP, NSZ, XCI and XCZ files, .nup files and dump folders. Everything inside shows on Home. Open it to remove it from the list; its files stay where they are.", null);
        put(m, "Update and DLC folder", "A folder with game updates and DLC as NSP or NSZ files, matched to their games by title ID. Open it to remove it from the list; its files stay where they are.", null);
        put(m, "Build folder", "Where new builds are written, on the phone or on an SD card. The first folder is the default, and a game's sheet can pick another.", null);
        put(m, "Add folder", "Adds a folder with games, with updates and DLC, or for builds: it asks what is in it, then opens the folder picker. Add several in a row, on the phone and on an SD card.", null);
        put(m, "Folders", "One game's folders: where its game files, its pack and its saves are. With more than one game, it asks which game first.", null);
        put(m, "Rescan", "Reads the folders again, for games you added or removed while the app was open.", null);
        put(m, "Free up space", "Deletes the original dump of a game that is built already. The game keeps playing from its build. Keep the original while a later update may come: an update needs it.", null);
        put(m, "Storage", "Finds leftovers and old files the app no longer needs, and removes the ones you tick. Saves are never listed.", null);
        put(m, "Played", "The time you played every game in this app, counted only while a game runs. Each game's own time is on its page and on the second screen's card.", null);
        // Console
        put(m, "Keys", "Your console's prod.keys. The app needs them to read inside NSP and XCI files and to build a game. They stay inside the app.", null);
        put(m, "Firmware", "Your console's firmware, from a folder or a .zip. The app takes its fonts and Mii parts from it. Use the same version as your keys.", null);
        put(m, "Time of day", "The clock a game sees from its next start.", "The phone's clock");
        // Graphics
        put(m, "GPU driver", "The Vulkan driver games run on: the phone's own, or one you installed. A game can have its own.", "System");
        put(m, "Console mode", "Docked draws as on a TV, up to 1080p. Handheld draws as on the Switch's own screen, up to 720p, which is lighter. Unset, each game uses its own mode.", "Each game's own");
        put(m, "Resolution", "The size games with a choice of their own draw at.", "Phone 1080p");
        put(m, "Resolution scale", "Draws the game's picture at a multiple of the size the game asks for. Above 1x is sharper and needs more of the GPU, below 1x is lighter. From the next start.", "1x");
        put(m, "Upscaler", "How the game's picture is scaled up to the screen. FSR and SGSR also sharpen it, the plain filters only scale.", "SGSR 1");
        put(m, "FSR sharpness", "How strongly FSR sharpens the picture. Higher is crisper and can show hard edges.", "Default");
        put(m, "CRT filter", "Draws the picture as a CRT shows it: scanlines, and for the CRT looks an aperture-grille mask and a vignette, curved as well. A game can have its own, and the quick menu changes it live.", "Off");
        put(m, "Dynamic resolution", "Lets a game draw smaller while a scene is heavy, so its frame rate holds.", "40%");
        put(m, "Frame rate cap", "The highest frame rate games run at. A lower cap saves battery and keeps the device cooler.", "60 fps");
        put(m, "Frame generation", "Draws frames between the game's own, for smoother motion with a little more input delay. Needs Lossless.dll.", "Off");
        put(m, "Use fast cores", "Keeps the game's busiest threads off the small cores of the processor. Faster, and a little warmer.", "Off");
        put(m, "Overlay", "Shows the frame rate and other values over the game. Tap the row to pick what it shows.", "On");
        // Controls
        put(m, "Button mapping", "Which button of your controller presses which Switch button. A game can have its own.", null);
        put(m, "On-screen controls", "When the touch controls show over the game.", "Auto");
        put(m, "Show / hide button", "The small button at the top centre of the game that hides the on-screen controls.", "On");
        put(m, "Controller: in-game panel", "How a controller opens the in-game menus: pressed, the game menu; held, the quick menu. By touch, swipe in from the left edge for the game menu or from the right edge for the quick menu.", "Home, or hold − and +");
        put(m, "Quick menu button", "A button of your own that opens the quick menu in a game at once. Home (on the AYN Thor: Back) still opens the game menu, and held the quick menu.", "Not set");
        put(m, "Rumble", "Lets games rumble the controller, or the phone when no controller is connected.", "On");
        // Screens
        put(m, "Bottom screen", "On a device with a second screen: the game you are on, with Play and its page, while you are in the app. Off leaves that screen to Android.", "On");
        put(m, "Games without a companion", "What the second screen shows while you play a game that has no companion: Android's own screen, the game's live stats, or its cover. The stats page's gear picks what it shows and its OLED guards.", "Stats");
        put(m, "Swap screens", "Starts games on the other screen and the companion on this one, for a clip-on top screen. From the next game start.", "Off");
        put(m, "Dual-screen companions", "Dual-screen companions are pages made for one game that show on the second screen while you play, like its map, items or stats. Install, update or remove them here, also from a .dsmod.zip file without the internet.", null);
        put(m, "TV and monitors", "What a connected TV or monitor shows. System leaves it to Android and the device, as for any app. Play on the TV starts a game full screen there, and the screen it left goes black.", "System");
        put(m, "Screen rotation", "Whether a game keeps the screen in landscape, or turns with the phone.", "Landscape");
        put(m, "Keep the screen on while preparing", "Keeps the screen on while Home shows a game being prepared. Some phones stop a job once the screen goes off.", "On");
        // Users
        put(m, "Users", "The console's users, each with saves of their own, and the one games start with.", "Player");
        put(m, "Miis", "Make Miis of your own. Games that ask for a Mii list them as the console's.", null);
        put(m, "amiibo", "Lets games find your amiibo on Peanuts' own stand, no figure or keys needed. Off, a game finds no amiibo, as before. A game's own amiibo switch in its Game options comes first. From the next start.", "Off");
        put(m, "amiibo pictures", "Turning this on downloads each amiibo's picture from AmiiboAPI on GitHub (raw.githubusercontent.com) when a page shows it, while amiibo is on, and keeps it on this device. Off, the app downloads nothing for amiibo and shows its own card.", "Off");
        // App
        put(m, "Haptics", "The app's touch feedback. System follows Android's setting, Off turns the app's own feedback off.", "System");
        put(m, "Motion", "The app's animations. System follows Android's setting, Reduced keeps fades and progress only.", "System");
        put(m, "Mods tab", "The quick menu's Cheats and Mods tabs: the game's cheats and mods while it runs, each tab when the game has some. A game's own Mods & Cheats page can hide them for that game.", "On");
        put(m, "Check for updates", "Looks for a newer version of the app on GitHub.", null);
        put(m, "Smooth Experience", "A game starts and plays without technical details: a peanut and the game's cover instead of log lines, and no notices about graphics work over the game. The parts show under it while it is on. The logs keep every detail.", "Off");
        put(m, "Start screen", "Part of Smooth Experience: the game's cover and a peanut instead of log lines, until the game shows.", "On");
        put(m, "Preparing graphics notice", "Part of Smooth Experience: no notice while a game makes graphics in the background. On a first run parts of the picture may be missing for a moment.", "On");
        put(m, "Graphics driver notice", "Part of Smooth Experience: no notice when the graphics driver left out parts of the picture.", "On");
        // Help
        put(m, "Readiness check", "Checks whether this device can run your games: Android, memory, file access, the GPU and its driver, and each game's needs.", null);
        put(m, "Graphics self-test", "Draws a test picture for a few seconds, with no game, and says whether the GPU and its driver passed.", null);
        put(m, "Questions and answers", "Answers to common questions, and what to try when something fails. Opens the browser.", null);
        put(m, "Share log", "Sends the newest log of a game run or of a build, for a bug report.", null);
        // Developer
        put(m, "Advanced", "Every switch the app's host offers, by area, with a search. Each starts at Auto, which writes nothing.", "Auto");
        put(m, "Tester settings", "Shows the rows made for testing in a game's settings: advanced resolution, Upscaler and Dynamic resolution.", "Off");
        put(m, "ADB control (debug)", "Lets adb commands add games, start Prepare and install GPU drivers. Turn it on only while you use adb.", "Off");
        put(m, "Developer view", "Shows the messages only testers need, such as an unknown switch or an ignored adb command, and the in-game menu's tool words. Off, those messages go to the log only and the in-game menu says it in plain words. On for an app installed before this version, off for a new one.", null);
        ALL = Collections.unmodifiableMap(m);
    }

    /** The row's help, null for a row this table does not know */
    public static Help of(String title) { return title == null ? null : ALL.get(title); }

    /** Every title with help, for the test */
    static java.util.Set<String> titles() { return ALL.keySet(); }

    /** The line under a row's title on the explanation: its section, and for the game defaults whose a game can override, that they are for every game */
    public static String place(SettingsSections.Section s) {
        if (s == null) return "";
        return s.key.equals("graphics") || s.key.equals("controls") ? s.label + ", for every game" : s.label;
    }
}
