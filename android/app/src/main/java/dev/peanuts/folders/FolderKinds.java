package dev.peanuts.folders;

import android.app.Activity;
import android.content.Context;

import dev.peanuts.PeanutsPrefs;
import dev.peanuts.launch.FrontendFolder;
import dev.peanuts.ui.PUi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every kind of folder, in the order Settings › Library lists them (issue #546: several of each). The picker's request codes are the ones these folders had
 * before (LibrarySettings: 61 the game folder, 62 the update folder, 68 a build folder), so a picker still open across an app update lands where it did.
 */
public final class FolderKinds {
    private FolderKinds() {}

    public static final FolderKind GAMES = new FolderKind("games", "Game folder", "Games", "folder",
            "NSP, NSZ, XCI, XCZ, .nup files and dump folders",
            "The app shows every game inside on Home.",
            "Pick a folder with your games", 61, 0, false,
            new FolderKind.Store() {
                @Override public List<String> read(android.content.Context c) { return PeanutsPrefs.gameDirs(c); }
                @Override public void write(android.content.Context c, List<String> dirs) { PeanutsPrefs.gameDirs(c, dirs); }
            }, null);

    public static final FolderKind ADDONS = new FolderKind("addons", "Update and DLC folder", "Updates and DLC", "update",
            "Matched to their games by title ID",
            "Updates and DLC inside are matched to their games by title ID.",
            "Pick a folder with updates or DLC", 62, 0, false,
            new FolderKind.Store() {
                @Override public List<String> read(android.content.Context c) { return PeanutsPrefs.addonDirs(c); }
                @Override public void write(android.content.Context c, List<String> dirs) { PeanutsPrefs.addonDirs(c, dirs); }
            }, null);

    public static final FolderKind BUILDS = new FolderKind("builds", "Build folder", "Builds", "build",
            "Where new builds go, one folder for each game",
            "New builds go to the default folder. A game's sheet can pick another one.",
            "Pick a build folder", 68, 0, true,
            new FolderKind.Store() {
                @Override public List<String> read(android.content.Context c) { return PeanutsPrefs.buildDirs(c); }
                @Override public void write(android.content.Context c, List<String> dirs) { PeanutsPrefs.buildDirs(c, dirs); }
            }, null);

    /**
     * The Frontend folder (issue #475, FrontendFolder): one folder where the app keeps a small entry file per game that is ready to play, which frontends
     * such as Cocoon list and start. Its store is FrontendFolder.set, so the entries move with Pick another and leave with Turn off (later, when that cannot
     * be done now); the row's line is what the last run did, and it follows each run.
     */
    public static final FolderKind FRONTEND = new FolderKind("frontend", "Frontend folder", "Frontend", "external",
            "Each game ready to play gets a small file here that frontends such as Cocoon list and start.",
            "Each game ready to play has a small file here, named after the game. Frontends such as Cocoon list these files as games and start them in this app. The app keeps the files current and changes only the ones it wrote.",
            "Pick a folder your frontend lists games from", 74, 1, false,
            new FolderKind.Store() {
                @Override public List<String> read(Context c) {
                    String d = PeanutsPrefs.frontendDir(c);
                    List<String> dirs = new ArrayList<>();
                    if (d != null) dirs.add(d);
                    return dirs;
                }
                @Override public void write(Context c, List<String> dirs) { FrontendFolder.set(c, dirs.isEmpty() ? null : dirs.get(0), null); }
            },
            new FolderKind.Hooks() {
                @Override public void removing(Activity a, String path, Runnable remove) {
                    PUi.confirmDanger(a, "Turn off the frontend folder?", "The app removes the files it wrote in " + path + ", so frontends no longer list these games after their next rescan. Your games, builds and saves stay.",
                            "Turn off", remove);
                }
                @Override public CharSequence state(Context c, String path) { return FrontendFolder.state(c); }
                @Override public String removeLabel() { return "Turn off"; }
                @Override public CharSequence removeNote() { return "Turn off takes the files the app wrote out of this folder. Your games, builds and saves stay."; }
                @Override public CharSequence sheetLine(Context c, String path) { return FRONTEND.about; }   // not what the library found in it
                @Override public void rows(Activity a, Runnable refresh) { FrontendFolder.onRun(refresh); }
                @Override public void resumed(Activity a) { FrontendFolder.resumed(a); }
                @Override public CharSequence accessNeed(String path) { return "The app needs All files access to write the games' files into " + path + ". Allow it, then come back."; }
            });

    public static final List<FolderKind> ALL = Collections.unmodifiableList(Arrays.asList(GAMES, ADDONS, BUILDS, FRONTEND));

    /** The kind of this key, else null */
    public static FolderKind of(String key) {
        for (FolderKind k : ALL) if (k.key.equals(key)) return k;
        return null;
    }

    /** The kind whose picker this request code is, else null */
    public static FolderKind ofRequest(int request) {
        for (FolderKind k : ALL) if (k.request == request) return k;
        return null;
    }
}
